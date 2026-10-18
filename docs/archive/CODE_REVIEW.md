# Gossip Plugin Code Review

> **Archived 2026-10-18.** The planned work is done and this document is no longer updated, so its line references match the code as it was then. It left three items open, and all three were fixed on the same day, after archiving: a test that never ends now fails at its timeout ([Open](#open)), and the protocol messages are typed records, with gossip version 1 removed ([problem 1](#problems-that-run-through-the-whole-plugin), [#8](#8-version-fallback-is-never-remembered--fixed-2026-10-16)). A swapped `GossipConfig` setting can no longer go unnoticed ([Less urgent](#less-urgent)).

Scope: the main source files in [plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/). There were 17 when the review began at commit `e8a6675` and there are 21 now: `GossipDnsLookup` is gone, and `Departure`, `GossipExchangeException`, `GossipFailure`, `GossipFields` and `package-info.java` are new. Line references are refreshed with each change, most recently on 2026-10-18. Focus: efficiency, plus code that is hard to follow or subtly wrong.

## Summary

The protocol logic is careful and mostly correct: version checks, conflict detection and tombstones all hold up. The inefficiency comes mainly from three repeated habits, not from bad core algorithms:

1. **Data is encoded to JSON just to measure its size.** Each incoming record is JSON-encoded about 7–8 times per hop.
2. **`MembershipTable.snapshot()` is used like a getter.** It copies the whole table every time, and some callers only want one field that never changes.
3. **Every merge copies the whole table**, even though a batch holds at most 32 records.

The config caps (`max.records`, a 64 KB `max.exchange.bytes`) keep most of this to wasted CPU and memory churn rather than unbounded growth. The exceptions are `members()`, which does quadratic work, and the DNS resolver, which starts a JVM per lookup.

Line numbers and links refer to the code as of the latest change recorded here.

## Status (2026-10-18)

**Fixed:** every finding, #1 to #13 and all six low-impact items. #10 to #13 came from a second pass that measured where the remaining time went; see [Second pass](#second-pass-efficiency-deep-dive-2026-10-16). A profiling pass between the two cut decoding costs; see [After the review](#after-the-review-measured-decoding-costs-2026-10-16). What was still open after #10 to #13 was then fixed too; see [Follow-up](#follow-up-what-was-still-open-2026-10-16). A check of how the plugin uses the host's plugin interface then found two deviations, both fixed, and one agreed exception; see [Plugin interface check](#plugin-interface-check-2026-10-16). Its two open items then got the tests they lacked; see [Open items tested](#open-items-tested-2026-10-16). A review of the batch selector's design followed, and its proposal was carried out; see [Batch selector design](#batch-selector-design-2026-10-17). A readability review of the other large classes followed; see [Readability review of the larger classes](#readability-review-of-the-larger-classes-2026-10-17). Its 13 steps are done, and so is every finding they turned up but one; see [Found during the readability steps](#found-during-the-readability-steps-2026-10-17). The three items left open at archiving were fixed after it. Two related changes have also landed since the review:

- **Address fallback.** When a peer's hostname resolves to several addresses and connecting to one fails (refused or unreachable), the same exchange now tries the others. `GossipDnsResolver.resolveAll` returns every address, and `GossipTransport` and `GossipRuntime` use it. This affects how #4 should be fixed; see the note there.
- **Test support moved to the host.** Plugin tests now use the host's `dev.mars.testing` library. This changes no finding below.

## High impact

### 1. Re-encoding JSON only to check sizes — fixed 2026-10-16

**Was:** for every record in every exchange, `requireForwardable` built and encoded three complete envelopes: a request with the record as sender, a reply holding the record, and a members page holding the record. It also rebuilt a Base64 cursor that never changed, once per record. A reply carrying 31 records needed about 94 envelope encodings, roughly 10× the bytes actually sent. The same record was encoded again by `GossipWire.decode`'s request-size check, by `MembershipTable.checkRecordSize`, and by every call to `MemberRecord.serializedBytes()` or `fingerprint()`.

**Now:**
- [`MemberRecord`](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L156-L168) is a final class that computes its encoded size and fingerprint once, then reuses them.
- [`GossipWire`](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L179-L183) adds sizes up instead of encoding envelopes. A record's JSON is identical standalone or nested, so an envelope is a fixed overhead plus its records plus one comma between neighbours. Each overhead is measured once.
- [`requireForwardable`](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L218-L238) needs no envelope encoding per record. Its two local-record overheads were recomputed only when the local record changed in more than its heartbeat; since step 6 of the readability review nothing is recomputed, and the local record is added by its size.
- The request-size check in `decode` ([GossipWire.java:120](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L120)) no longer re-encodes the request.

**Verified by** `GossipSizeAccountingTest`. It keeps the original encode-everything algorithms as a reference and requires identical decisions, sweeping record sizes across every byte limit. It includes JSON-escaped and multi-byte metadata, and plain-ASCII sweeps that land on each exact limit. Six deliberately introduced one-byte errors were each caught.

**Measured:** an inbound exchange of 31 records into a 128-member table went from about 430 µs to about 208 µs. The remaining cost is mostly decoding records (#9), snapshots (#5) and encoding the actual reply.

**Follow-up, done 2026-10-16:** `GossipBatchSelector`'s own per-record size cache, redundant once `MemberRecord` cached its size, was removed (about 35 lines).

### 2. `members()` does quadratic work — fixed 2026-10-16

**Was:** each time a row was added, the code copied and encoded the entire page built so far. With 32 rows that was about 500 rows encoded per call, around 160 KB of JSON to produce a reply of about 30 KB.

**Now:** [GossipCommands.java:156-177](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L156-L177) measures the empty page once and each row once, and keeps a running total. The total accounts for the comma between rows and the exact width of the next-page cursor. Paging decisions are unchanged, and `GossipSizeAccountingTest` checks every page against the original row-by-row algorithm. The finished page was still encoded once as a final safety check; step 7 of the readability review removed that, since the page is sized exactly. Measured: a 32-row page went from about 485 µs to about 88 µs.

### 3. Merging copies the whole table — fixed 2026-10-16

**Was:** on every inbound and outbound merge, `apply` copied both `entries` and `suppressions` into new maps, so a conflict thrown partway through a batch could not leave the table half-changed.

**Now:** the validation loop only reads the table; it collects updates and admissions, and every change is applied after it ([MembershipTable.java:147-149](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L147-L149), [175-176](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L175-L176)). A conflict throws before anything is applied, so a rejected batch still leaves the table untouched, with no copy. Batch keys are already unique, so reading the live table gives the same answers the copies did.

**Verified by** the existing rollback tests. A deliberately introduced variant that applied each update inside the loop failed five of them.

**Later:** the active-member count was at first still recomputed with one pass over the table per merge, because keeping a counter in step across merges, expiry and departure would add more code than it saves. #11 made the count run only when a batch admits new members, which a stable swarm's batches never do.

**Measured with #9:** an inbound exchange went from about 185 µs to about 166 µs.

### 4. A whole JVM is started for each DNS lookup — fixed 2026-10-16

**Was:** each hostname lookup ran `java -cp … GossipDnsLookup` as a separate process, about 130 ms of JVM startup and tens of MB per cold lookup, so that stop could kill a lookup blocked inside the platform resolver.

**Now:** lookups run in-process, each on its own short-lived daemon thread, with the same two-second timeout, `max.inflight` bound and 60-second cache ([GossipDnsResolver.java:74-134](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L74-L134)). `resolveAll` still returns every address with the first one rotating, as the connection fallback requires. The helper program `GossipDnsLookup` and its test fixture program are deleted.

**Trade-off, agreed before the change:** a lookup blocked inside the platform resolver can no longer be killed. On timeout or stop, the waiting caller is released at once and the lookup thread is interrupted; one that ignores the interrupt is abandoned, finishing in the background and counting toward the bound until it returns ([GossipDnsResolver.java:162-175](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L162-L175)).

**Verified by** the resolver and runtime tests, rewritten on an in-process lookup fixture with the same behaviours as the old fixture program: stall, wait for release, fail, empty reply, mixed addresses. The `localhost` load profile no longer asserts that sampling caught a lookup in flight, since in-process lookups finish in microseconds; its convergence through the hostname seed shows the lookups ran.

**Also fixed:** a concurrent request for the same host now shares the lookup already running ([GossipDnsResolver.java:85](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L85)).

## Medium impact

### 5. Full snapshots used as getters — fixed 2026-10-16

**Was:** `MembershipTable.snapshot()` builds a `MemberView` for every entry and copies the list, and it computed `assessment()` twice per entry. Several callers took a full snapshot only to read something cheap:

- the runtime, **inside the per-peer loop** of `round()`, just to read the local `serverId`;
- the runtime, on every exchange, for `selector.resolved()`, which usually returns at once because the peer isn't a configured seed;
- the transport, three times per outbound exchange, including once only to check for identity conflicts;
- the commands, to read `self()` and `serverId`.

**Now:**
- `MembershipTable` has [`localKey()`, `self()` and `identityConflicts()`](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L360-L368), which read the local identity, the local record, or the conflict set without copying the table. `snapshot()` computes each entry's assessment once.
- `GossipRuntime` and `GossipCommands` keep the local `serverId` in a field ([GossipRuntime.java:89](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L89), [GossipCommands.java:71](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L71)).
- The exchange path passes the snapshot to `PeerSelector.resolved` as a supplier ([GossipRuntime.java:306-307](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L306-L307)). It is taken only when a configured seed resolves to a different address and its alias must be confirmed.
- The transport took one snapshot per outbound exchange instead of three ([GossipTransport.java:190](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L190), [241](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L241)), and the commands one per inbound exchange instead of two. #11 later removed these too: exchanges now read only the table's records.

The snapshots that remain are in peer selection, `leave`, `members` and `status`; batch selection stopped using one in #11. The review suggested having the merge methods return the conflicts. A separate `identityConflicts()` was used instead, because `MergeResult` is a value type that tests compare directly.

**Verified by** the existing suites, which all pass, plus new tests. They check that `self()` and `localKey()` follow the local record through heartbeats and departure, that `identityConflicts()` matches the snapshot as conflicts appear and clear, and that alias confirmation takes a snapshot only for a configured seed whose address differs. **Measured:** an inbound exchange went from about 208 µs (after #1 and #2) to about 185 µs. The outbound path, which saw the largest reduction, is not covered by the benchmark.

### 6. The transport opens a `Selector` per connection, on virtual threads — fixed 2026-10-16

**Was:** every connection attempt opened a `Selector` (with its own wakeup channel) beside a non-blocking `SocketChannel`, and waited in an `await` loop, although exchanges run on virtual threads where plain blocking I/O is cheap.

**Now:** one blocking `Socket` per attempt ([GossipTransport.java:312-356](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L312-L356)). Connects and reads use socket timeouts; each read waits at most the read-idle limit and never past the total deadline ([GossipTransport.java:359-383](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L359-L383)). A blocking write has no timeout, so a small virtual-thread watchdog closes the socket once the total deadline passes. A socket closed by the watchdog, an interrupt or `close()` is reported as a timeout, an interruption or a closed transport, as before. The `Selector`, the `await` loop and the `Connection` wrapper are gone.

**Verified by** the existing transport tests, all passing unchanged: connect, read-idle and total deadlines, trickling peers, interruption, close cancellation, the address fallback and the byte limits. **Not covered by a test at first:** the write watchdog, because loopback buffers absorb a full 64 KB request, so a stalled write can't be produced reliably. A test with a stand-in socket was added later; see [Open items tested](#open-items-tested-2026-10-16).

**Limitation, fixed in the follow-up:** a caller on a platform thread was not woken from a blocked read by its interrupt, only by the deadlines, up to 15 s later. The watchdog now also closes the socket within 50 ms when such a caller is interrupted ([GossipTransport.java:319-330](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L319-L330)). The runtime's own workers are virtual threads.

**Measured:** outbound exchanges over loopback TCP with about 29 KB of records per reply went from about 3.7 ms to about 2.1 ms (three runs each).

### 7. Reading the reply one byte at a time — fixed 2026-10-16

**Was:** `readLine` copied the reply into a `ByteArrayOutputStream` one byte at a time. `ByteArrayOutputStream.write(int)` is `synchronized`, so a 64 KB reply meant 64K lock acquisitions, plus a final `toByteArray()` copy.

**Now:** [GossipTransport.java:359-383](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L359-L383) reads straight into one buffer of `max.exchange.bytes + 1` bytes and scans only the newly read bytes for the terminator. It decodes the line in place, without a copy. The spare byte keeps the limit exact: a reply of exactly the maximum length followed by its terminator is accepted, and one byte more is rejected.

**Verified by** the existing byte-limit, invalid-UTF-8, empty-reply and oversize tests. A new test sends the same boundary replies in 37-byte chunks, with each terminator (`\n`, `\r\n`, `\r`, or none with the peer closing), and with bytes after the terminator. Four deliberately introduced errors were each caught: a buffer one byte too small, keeping the bytes after the terminator, a missing over-limit check, and not treating `\r` as a terminator.

**Measured:** outbound exchanges over loopback TCP, with about 29 KB of records per reply, took about 4.3 ms with the old reader and about 3.7 ms with the new one, averaged over three alternating runs. That's roughly 15%, but the runs were noisy. The reader was a small share of the exchange; connection setup (#6) and decoding (#9) cost more.

### 8. Version fallback is never remembered — fixed 2026-10-16

**Was:** every exchange with a version-1 peer first sent version 2 and was rejected, so each one cost two TCP connections and two round trips.

**Now:**
- After a peer rejects version 2 and a full, identity-checked version-1 exchange with it succeeds, the transport remembers it and sends version 1 directly ([GossipTransport.java:179](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L179), [189](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L189), [264-287](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L264-L287)).
- Entries are keyed by server ID and port. They expire after 10 minutes, so an upgraded peer gets version 2 again, and at most one entry is kept per member or seed.
- A failed version-1 retry is never remembered, so a stray or failed reply can't make a downgrade stick.

**The exact-message matching stays, now documented** in one constant ([GossipTransport.java:54-59](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L54-L59)). Version-1 peers are already deployed, and these two rejection messages are the only signal they send. **Corrected 2026-10-18:** that was wrong. Nothing has been deployed, so there were no version-1 peers. The claim came from the plugin's own comments and was repeated here without checking. Gossip version 1, its fallback, this memory and these messages were removed; every exchange is version 2, and any other version is rejected. A structured error code would only help peers that don't exist yet, because version-2 peers never reject version 2.

**Verified by** four new tests. Connection counts are exact, because the test peer fails if it receives more or fewer connections than expected. The tests check that only the first exchange with a version-1 peer pays for the rejected attempt, that the peer is offered version 2 again exactly at the recheck interval, that a failed version-1 retry isn't remembered, and that the memory stays within its bound. Four deliberately introduced errors were each caught: never remembering, remembering before the version-1 exchange succeeds, an off-by-one in the expiry, and no size limit.

**Effect:** an exchange with a version-1 peer now uses one connection and one round trip instead of two, except for the first exchange and one re-check every 10 minutes.

### 9. Each record's metadata is copied twice and round-tripped through JSON — fixed 2026-10-16

**Was:** `fromWire` deep-copied the metadata and the constructor copied it again, then encoded and re-parsed it. The same happened on every heartbeat and departure, although that metadata was already frozen.

**Now:** `fromWire` hands the metadata straight to the constructor, which copies and checks it once ([MemberRecord.java:122-136](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L122-L136)). A record built from an existing one, for a heartbeat, departure, local update or the widest-heartbeat stand-in, uses `with(heartbeat, state)`, which shares the already-checked metadata ([MemberRecord.java:98-112](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L98-L112)).

## Low impact and readability

- ~~**Exceptions used for normal control flow.**~~ Fixed 2026-10-16: `GossipValidation.literal` recognises a hostname by its characters before calling `InetAddress.ofLiteral`, so hostnames no longer cost an exception ([GossipValidation.java:49-62](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L49-L62)). It replaces the try/catch in `PeerSelector.normalized`, `PeerSelector.isSelf` and the resolver, with the same results.
- ~~**Regex compiled on every call.**~~ Fixed 2026-10-16: the cursor patterns are compiled once ([GossipCommands.java:45-46](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L45-L46)); the ID pattern, at first compiled once too, was later replaced by a character loop (#12, [GossipValidation.java:36-47](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L36-L47)). `validHost` now remembers its answer for up to 1,024 hosts, checking length before caching so network input cannot inflate the cache; it went from about 360 ns to about 17 ns per call.
- ~~**Convoluted recovery-fill loop.**~~ Fixed 2026-10-16: a bounded loop that relied on the cursor yielding each recovery peer once per cycle; step 5 replaced it with slot counts ([PeerSelector.java:90-95](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L90-L95)); the hand-written shuffle is now `Collections.shuffle`.
- ~~**Rotation that isn't actually fair (a correctness problem).**~~ Fixed 2026-10-16: both rotations now remember the member that is due next, not just its index. If that member was removed, its successor has taken its index, so resuming there was taken to be right. It was not when an earlier member was removed too: the indexes shift, and the rotation could probe again the member it had just probed. Both cursors have since been replaced: the batch selector's by a rotation, see [Batch selector design](#batch-selector-design-2026-10-17), and this one by a queue of turns ([PeerSelector.java:141-146](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L141-L146)), see [step 5](#readability-review-of-the-larger-classes-2026-10-17). Two new tests reproduced the skipped member before the fix and pass after it.
- ~~**Clock inconsistency.**~~ Fixed 2026-10-16: departure timing uses the injected clock ([GossipRuntime.java:175](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L175), [231](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L231)).
- ~~**Duplicated self-detection logic.**~~ Fixed 2026-10-16: the transport now uses the shared `PeerSelector.isSelf` ([GossipTransport.java:173](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L173)).

## After the review: measured decoding costs (2026-10-16)

Profiling inbound exchanges with Java Flight Recorder showed that the metadata check, which writes each record's metadata to JSON and reads it back, was the largest single cost of an exchange, and that measuring each new record's size encoded the metadata a second time.

| Share of an inbound exchange | Small metadata | Large metadata |
|---|---|---|
| Metadata check: JSON read | 17% | 24% |
| Metadata check: JSON write | 10% | 13% |
| Measuring each new record's size | 29% | 15% |

A probe showed that, within the metadata limits (4 KB, depth 16), the read-back only ever rejects numbers of about 1,000 digits or more, the codec's default number-length limit.

**Changes** ([MemberRecord.java:292-304](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L292-L304), [311-325](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L311-L325)):
- The read-back runs only when the metadata holds a number of 100 characters or more. Anything that could reach the codec's limit is still checked by the real codec, so validation is unchanged unless the host's number limit is set below 100 characters.
- The metadata's byte count from that check is kept and added to the size of the record's other fields, so the metadata is encoded once; #10 later computed those other fields by arithmetic too.

**Verified by** two new tests: every accepted record reads back through the host codec across a sweep of integer and decimal lengths through the gate and the codec's limit, and `serializedBytes` equals the real encoding for built, derived and decoded records. Four deliberately introduced errors were each caught: a gate threshold far too high, the longest number never recorded, an off-by-one in the size arithmetic, and `with()` dropping the measured size.

**Measured** (inbound exchanges, profiled): small metadata 121 µs → 89 µs (−26%); large metadata 733 µs → 424 µs (−42%). The read-back no longer appears in the profile, and size measurement fell to 21% and 1.8%.

**Reply size check removed (2026-10-16).** Each inbound reply was encoded once just to confirm it fit, and the host then encoded it again to send it. The selector now gets exactly the space the empty reply leaves, using the reply's real admission value, so the reply fits by construction and is no longer encoded to check ([GossipCommands.java:130-136](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L130-L136)). A new test checks, across 1,202 replies in both protocol versions, that every reply fits, that its real size equals the arithmetic sum, and that some fill the limit exactly; a budget one byte too generous fails it. Measured back to back over three runs each, large-metadata exchanges took about 412 µs without the check and about 540 µs with it (about 24% faster); small-metadata exchanges went from 89 µs to 75 µs.

**Largest remaining costs:** the metadata deep copy (12% small, 41% large), a defensive copy of untrusted input worth keeping; the merge (15–16%); and, for small metadata, encoding each record's fixed fields to measure it. For production records, whose metadata is empty, that last cost was the largest of all until #10 removed it.

## Second pass: efficiency deep dive (2026-10-16)

Scope: all 16 main source files at commit `d25d08c`, looking for remaining inefficiency, weak algorithms, bloat and repeated work. Findings #10 to #13 were fixed the same day; see [Result](#result).

**How the fixes were tested.** Each fix was made test first. Where a fix added behaviour (#11), its tests were written first and failed before the code existed. The other fixes change no behaviour, so tests pinning that behaviour were written first and passed on the old code; deliberately introduced errors in the new code then showed that each test can fail.

**How this was measured.** In-process timings on JDK 27, taking the best of five passes after a warm-up pass, and a Java Flight Recorder profile of peer selection. Inbound exchanges carried 32 records (the sender and 31 members) against tables of 128 and 480 members. The times cover the plugin only, not the host's JSON handling or the network.

**Production records carry empty metadata.** Nothing in the plugin calls `MembershipTable.updateLocal`, so every node advertises metadata `{}`; the design intends metadata for things such as a node's supported instructions ([GOSSIP_PLUGIN.md](../GOSSIP_PLUGIN.md)). In a swarm of these plugins every record is small, so fixed per-record costs dominate. Records with metadata were measured too. At the time of the review:

| Plugin time | 128 members | 480 members |
|---|---|---|
| Inbound exchange, empty metadata (as in production) | 34 µs | 61 µs |
| Inbound exchange, small metadata | 57 µs | 76 µs |
| Peer selection, once per round | 55 µs | 210 µs |

### 10. Each received record is encoded to JSON just to learn its size — fixed 2026-10-16

**Was:** `serializedBytes()` put the six fixed fields in a map and ran the JSON encoder on it. The result was cached, but every received record is a new object, so the encoder ran once for every record in every exchange, inbound and outbound: about 0.45–0.6 µs per record, roughly twice the 0.25 µs it takes to decode and validate an empty-metadata record. A 32-record exchange spent about 14–16 µs on it, roughly 40% of an empty-metadata inbound exchange at 128 members.

**Now:** each record computes its size from its field lengths when it is built, and `serializedBytes()` reads it ([MemberRecord.java:60-65](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L60-L65), [311-325](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L311-L325)). Every fixed field is ASCII that JSON never escapes: the server ID matches `[A-Za-z0-9._-]`, the boot ID is a canonical UUID, the port and heartbeat are integers, and the state is an enum name. Host validation accepts no character that JSON escapes or encodes in more than one byte; this was checked for every Unicode character in three positions in a host.

**Verified by** a new test that compares the size with the real encoding for every digit count of the port and heartbeat, every host form, the shortest and longest server IDs and both states, for records built directly and derived with `with()`. It passed on the old code. Four deliberately introduced errors were each caught: a fixed part one byte short, digit counting wrong at each power of ten (caught only by the new test), the state's length ignored, and `with()` keeping the base record's size.

### 11. Each exchange makes three passes over the whole table — fixed 2026-10-16

**Was:** an empty-metadata inbound exchange grew from 34 µs at 128 members to 61 µs at 480, although its batch is the same size. Three passes over the table caused this, and an outbound exchange made the same three:

- **A full snapshot where only the records were needed.** `snapshot()` assesses every entry, builds a conflict map and a `MemberView` per entry, then copies the list. The batch selector read only each member's record and the local record. At 480 members the snapshot took about 10 µs; a plain list of the records takes about 1 µs.
- **The batch selector rebuilt its index on every call.** It wrapped every member in a `Sized` record, walked that list again, then swept its map for removed keys: about 10 µs at 480 members. The separate sizing pass dated from when the selector measured sizes itself (#1).
- **The live-member recount on every merge.** The count is used only when a batch admits new members, and a stable swarm admits none: about 2–5 µs at 480 members.

**Now:**
- Both exchange directions choose their batch from `MembershipTable.peers()`: the table's records without the local one, with no assessments, views or copies ([MembershipTable.java:370-378](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L370-L378), [GossipCommands.java:130-136](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L130-L136), [GossipTransport.java:172-174](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L172-L174)).
- The selector refreshes its index in one pass, reads a size only for each record a batch considers, and sweeps for removed members only when the table has shrunk ([GossipBatchSelector.java:113-155](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L113-L155), [203-243](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L203-L243)). The `Sized` wrapper and the separate sizing pass are gone.
- The live-member count runs only when a batch admits new members ([MembershipTable.java:266-267](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L266-L267)).

**Verified by** tests written first. A table test requires `peers()` to equal the snapshot's records without the local one, in the same order, through merges, a departure, a local heartbeat and expiry. A selector test requires sizes to be read only for the records a batch considers; it failed on the old selector, which measured all 32 records to choose 2. The selector's test that sizes are measured outside its lock was replaced: it guarded against slow size encoding, which #10 removed. The existing capacity tests pin the conditional count. Eight deliberately introduced errors were each caught: five in the selector and `peers()`, three in the count.

### 12. Peer selection re-validates data that is already valid — fixed 2026-10-16

**Was:** `PeerSelector.select` was the most expensive single call in the plugin. Profiled at 480 members:

| Share of `PeerSelector.select` | |
|---|---|
| Building two `Seed` objects per member, each re-running validation | 58% |
| …of which the server ID regex alone | 44% |
| `isSelf` parsing the local host again for every candidate | 19% |
| The stream-based character check in `literal()` | 13% |
| Shuffling the candidates | 0% |

It ran once per round (every second by default), so even at 480 members it used about 0.02% of a core.

**Now:**
- Server IDs are checked with a character loop, about 60 ns down to 4 ns, and `literal()` uses a plain loop instead of a stream ([GossipValidation.java:36-62](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L36-L62)). Every received record's server ID goes through the first, so decoding gains too.
- Peer selection parses the local host once per round and each candidate's host once, and normalising returns a seed already in normal form instead of validating a copy ([PeerSelector.java:106-126](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L106-L126), [221-272](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L221-L272)).

**Verified by** tests that pinned the behaviour first and passed on the old code: the ID check against the documented pattern for every UTF-16 character and the boundary lengths, `literal()` against the real address parser, and selection with addresses written differently. One deliberately introduced error, comparing each candidate with the local address instead of its own, was at first not caught: no test had a neighbour on the local port at another address, which is the normal deployment with every node on one port. The test now includes one. Nine deliberate errors were each caught.

### 13. The reply budget encodes an empty reply on every inbound exchange — fixed 2026-10-16

**Was:** `replyMemberBudget` encoded a complete empty reply, including the local record, for every inbound exchange: about 1 µs with the empty local metadata used in production, and about 10 µs with large local metadata.

**Now:** the empty reply's fixed overhead is measured once for each protocol version and admission value, and each budget adds the local record's size and server ID ([GossipWire.java:190-198](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L190-L198)), the way request sizes are already added up.

**Verified by** a test that passed on the old code first, comparing the budget with the encoded empty reply for both protocol versions, every admission value, three server ID lengths, both states and many metadata sizes, all through one `GossipWire`, so that what it measures once cannot depend on the first record it saw. Four deliberately introduced errors were each caught, two of them only by this test.

### Bloat and repeated checks

These cost little at run time; the cost is in reading the code.

- ~~**Repeated checks on each inbound exchange.**~~ Fixed in the [follow-up](#follow-up-what-was-still-open-2026-10-16): duplicate keys and the batch limit are each checked once by the table, the decoder keeps only the limit check that bounds its own work, and an exchange's batch is copied once fewer.
- ~~**Maps built three times.**~~ Fixed in the [follow-up](#follow-up-what-was-still-open-2026-10-16): each request and reply is built as one map.
- ~~**About 30 lines used only by tests.**~~ Fixed in the [follow-up](#follow-up-what-was-still-open-2026-10-16), except `MembershipTable.mergeEncoded` and the public `GossipTransport` constructors: the design document offers those to callers that own a standalone table, so this pass was wrong to list them as test-only.

### Checked and not worth changing

- **Per-connection costs in the transport:** a watchdog thread, a fresh read buffer and a new decoder for each exchange. These are estimated at microseconds, against a loopback round trip of about 2 ms (#6).
- **`identityConflicts()` after each outbound exchange:** 1–4 µs, also small against the network.
- **The `members` command:** it still encodes each row, and a cursor per row, to size the page, but only operators call it.
- **Copying and comparing metadata:** needed because metadata comes from untrusted peers.
- **Expiry once per round (`maintain()`):** 0.3–1.8 µs.

### Outside the plugin

The host passes every plugin result through `Json.normalizeResult`, which encodes and then parses it, and then encodes the reply again to send it ([PluginRegistry.java:222](../../src/main/java/dev/mars/plugin/PluginRegistry.java#L222)). Encoding a 31-record reply with small metadata takes about 20 µs, so this costs more than anything left inside the plugin. It belongs in a review of the host.

### Result

Measured with the same benchmark before and after the fixes, median of three runs each:

| Plugin time | 128 members, before | after | 480 members, before | after |
|---|---|---|---|---|
| Inbound exchange, empty metadata | 36.3 µs | 17.8 µs | 61.1 µs | 22.5 µs |
| Inbound exchange, small metadata | 60.7 µs | 33.5 µs | 82.5 µs | 40.0 µs |
| Peer selection, once per round | 49.7 µs | 18.1 µs | 189.7 µs | 60.5 µs |

Empty-metadata inbound exchanges are twice as fast at 128 members and 2.7 times as fast at 480; with small metadata, 1.8 and 2.1 times. Growing the table from 128 to 480 members now adds about 5 µs to an exchange, down from about 25 µs. Peer selection is about three times as fast. The module's 264 tests (258 before; seven added, one replaced) and its 8 integration tests pass, as do the host's 337.

## Follow-up: what was still open (2026-10-16)

After #10 to #13, the problems still open were fixed rather than listed. Four remain by decision, and two are outside the plugin or were agreed earlier; all are listed at the end with reasons. Every change was made test first: new behaviour started from a failing test, and unchanged behaviour was pinned by tests that passed on the old code before the change. 25 deliberately introduced errors were each caught, one of them by sending a test into an endless loop that had to be stopped.

**Correctness and safety**
- **The host check #10 relies on is now a test.** #10 counts a host's characters as its JSON bytes, which is right only while validation accepts nothing JSON escapes and nothing UTF-8 encodes in more than one byte. That had been checked once by a separate program. A test now asserts it for every character in every position a host can take, including IPv6 scope IDs ([MemberRecordTest.java:159](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/MemberRecordTest.java#L159)); loosening validation to accept internationalised hostnames fails it.
- **A flaky test fixed.** `GossipDepartureTest` waited for two calls, then read two states that its client records just after each call, so the check could see only one ([GossipDepartureTest.java:254-256](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipDepartureTest.java#L254-L256)).
- **Interrupting a caller on a platform thread ends its exchange promptly.** Java does not wake a platform thread from blocking socket I/O when it is interrupted, so an embedding application that cancelled `exchangeWith` waited for the socket to time out, up to 15 s. The watchdog now also closes the socket within 50 ms of such an interrupt ([GossipTransport.java:319-330](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L319-L330)). The interrupt test runs on both kinds of thread; the platform case failed before the change.
- **The public API is intact.** `MembershipTable.mergeEncoded` and the public `GossipTransport` constructors were first removed as test-only, then restored when the design document turned out to offer them to callers that own a standalone table. `mergeEncoded` now also has a test that it rejects duplicate keys.

**Repeated work removed**
- **Host lookups take no lock.** Every decoded record and endpoint looked its host up in one synchronized map that reordered itself on every read. It is now a concurrent map that reads without locking and starts again when full ([GossipValidation.java:29-32](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L29-L32), [64-73](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L64-L73)); a test now checks its bound.
- **Empty metadata is not encoded to measure it** ([MemberRecord.java:293](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L293)): `{}` is 2 bytes, and writing it cost about half of building an empty-metadata record.
- **Boot IDs are checked without printing a copy** ([MemberKey.java:30-50](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberKey.java#L30-L50)); a test compares acceptance with parsing and printing back identically, for every position of three UUIDs replaced by every ASCII character.
- **Each record builds its wire map and its endpoint once** ([MemberRecord.java:138-154](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L138-L154), [170-180](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L170-L180)). Requests and replies carry the same records many times, and peer selection, departure and conflict logging built and validated a new `Seed` every time. The cached maps cost about 450 bytes per record, about 230 KB at 512 records.
- **The batch selector finds each member by position first** ([GossipBatchSelector.java:121-139](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L121-L139)): the table rarely reorders, so the hash lookup is now the exception, and a member whose record has not changed costs one reference comparison, without reading the record. `peers()` compares map keys by identity ([MembershipTable.java:370-378](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L370-L378)).
- **Checked, not changed:** profiles put about a fifth of an inbound exchange in `MemberKey.equals`. Writing it by hand only moved those samples: the cost was reading 480 entries from memory, which the shortcut above now mostly avoids. The hand-written version was removed again.
- **Each check once** ([GossipWire.java:116-117](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L116-L117), [MembershipTable.java:110-132](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L110-L132)): duplicate keys are rejected only by the table, which checks every batch it is given; the batch limit is checked once by the table and once by the decoder before it decodes anything, which bounds its work; an exchange's batch is copied once fewer. Requests and replies are each built as one map, with constant field-name sets ([GossipWire.java:153-172](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L153-L172)).
- **No boxed `Long` per exchange or round** ([GossipMetrics.java:36-40](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipMetrics.java#L36-L40), [76-81](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipMetrics.java#L76-L81)); a new test pins that both ages read as unknown until first set.
- **`isSelf` parses hosts only when the ports match**, as before #12 ([PeerSelector.java:242-245](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L242-L245)).
- **Test-only code removed from main:** the six-argument `Snapshot` and two-argument `Exchange` constructors, `PeerSelector.success(Seed)` and `resolved(…, Snapshot)`, `GossipDnsResolver.resolve`, and the transport's clock-only constructor.

**Verified, unchanged:** the batch selector no longer filters out the local record. Handing it every record, the local one included, fails the existing exchange tests in both directions, so callers are held to `peers()`.

**Measured** with the same benchmark, the versions run alternately, medians of three runs each. Times are in µs for tables of 128 and then 480 members; the first column comes from an earlier alternating run of the same benchmark on the same day.

| Plugin time | before #10 (`d25d08c`) | after #10 to #13 (`34d4cd1`) | after the follow-up |
|---|---|---|---|
| Inbound exchange, empty metadata (as in production) | 37.0 / 61.0 | 17.5 / 24.8 | 11.7 / 14.7 |
| Inbound exchange, small metadata | 58.7 / 76.8 | 32.8 / 42.4 | 30.9 / 34.8 |
| Peer selection, once per round | 47.3 / 169.7 | 17.2 / 59.2 | 10.6 / 44.3 |

Growing the table from 128 to 480 members now adds about 3 µs to an empty-metadata exchange, against about 24 µs before #10. Outbound exchanges over loopback TCP, which include connection setup and the host's JSON handling, took about 1.0–1.3 ms in every version; the differences between versions were within the run-to-run noise, also when the run order alternated.

**Where the time goes now**, in a profile of the final code (inbound exchange, 480 members, empty metadata): decoding the 32 received records 43% (checking their boot IDs and server IDs 18%), copying the table's records for the batch 18%, refreshing the selector 17%, merging 15%, and building the reply 2%.

**Deliberately unchanged**
- **Two passes over the table per exchange remain.** `peers()` copies the records so that the table lock is held only for the copy, and the selector visits every record to keep its rotation fair and its change queues current. Removing them needs a change log between the table and the selector.
- **Records the table already holds are still decoded in full.** With empty metadata, skipping identical records would save about 1–2 µs per exchange, but would couple decoding to the table and take its lock for the lookups. It pays off only with large metadata, which nothing sends today.
- **Peer selection still parses and normalises each member's address every round.** Caching the parsed forms would save part of that, once a second.
- **The write watchdog (#6) still had no test.** One was added later, with a stand-in socket, because shrinking the socket's send buffer turned out not to stall a write on Windows; see [Open items tested](#open-items-tested-2026-10-16).
- **Outside this plugin, or agreed earlier:** the host's double encoding and the DNS lookup trade-off (#4).

## Plugin interface check (2026-10-16)

A check that the plugin uses the host's plugin interface as the README describes, and owns no resource that the host should provide or would not know about.

**As intended:** all inbound gossip arrives as commands through the host's listener and uses the host's protocol classes and `Json`; the plugin's JAR bundles no copy of them. The constructor allocates nothing. `start()` starts the round scheduler, and `stop()` stops new rounds, closes the transport's sockets, and joins the scheduler and the exchange workers. Outbound exchanges open their own sockets because the host offers plugins no client: `PluginContext` carries only the server ID, port and admin port.

**Fixed**
- **The plugin's threads ran with the host's context class loader.** The host calls each hook with the plugin's own class loader as the thread's context class loader, and the README says each invocation runs with it. The runtime replaced it on its round scheduler and exchange workers with the loader of the host's `CommandPlugin`, so a library on those threads that looks resources up through the context loader would not find the plugin's JAR. Both overrides are removed, and the threads inherit the loader the host set around `start()` ([GossipRuntime.java:105-106](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L105-L106), [251](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L251)). A new test starts the runtime from a thread with a marker loader and checks that an exchange runs with it ([GossipRuntimeTest.java:271](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipRuntimeTest.java#L271)). It failed before the change, and restoring either override fails it.
- **Exchanges did not wait for their watchdog.** Each exchange interrupted its watchdog on the way out but did not wait for it, so a watchdog could still be running after stop had joined every worker. The README requires a stop hook to join its workers before returning. An exchange now waits for its watchdog to end, keeping the caller's interrupt status ([GossipTransport.java:350-354](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L350-L354), [395-403](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L395-L403)), so stop, which joins the workers, waits for every watchdog too. A new test holds the watchdog of a platform caller inside its interrupt check and asserts that the exchange does not return until the watchdog is released, with and without an interrupted caller ([GossipTransportTest.java:684](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipTransportTest.java#L684)). It failed without the wait, and it also catches a wait that gives up when the caller is interrupted and one that loses the caller's interrupt status.

**Cost of the wait:** waking the watchdog and then the caller takes about 60 µs per exchange at the median and up to about 200 µs at p99, measured on its own on the development machine (Windows) after a 1 ms pause like an exchange's. An alternating benchmark of loopback exchanges, about 1.0–1.6 ms each, could not reliably separate it from run-to-run noise. Exchanges happen once per selected peer per round.

**Remaining exception:** a hostname lookup can outlive stop. A lookup blocked inside the platform resolver cannot be interrupted, so stop abandons its daemon thread, which finishes in the background. This is the trade-off agreed for #4, against the README's rule not to rely on daemon threads. Removing it needs a resolver that can be cancelled. Tests now pin how it behaves; see [Open items tested](#open-items-tested-2026-10-16).

## Open items tested (2026-10-16)

Both open items from the plugin interface check had behaviour that no test covered. Each new test was checked against deliberate errors: the error it targets fails it, while every other test still passes under that error, which shows the behaviour had been untested.

- **Abandoned hostname lookups.** The resolver tests' stuck lookup ended when interrupted, unlike a lookup inside the platform resolver, so nothing tested a lookup that ignores its interrupt. Two tests now use one ([GossipDnsResolverTest.java:151](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipDnsResolverTest.java#L151), [175](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipDnsResolverTest.java#L175)). Close releases the waiting caller at once and returns after its one-second wait, leaving the lookup to finish in the background. A lookup that timed out keeps its slot until it returns, which is what stops abandoned lookups from piling up. They catch three errors: close waiting without a limit, close releasing callers only after its wait, and a timed-out lookup giving up its slot.
- **The write watchdog (#6).** A real loopback socket could not stall the transport's write on the development machine. Even with 1-byte send and receive buffers, Windows accepted a whole 64 KiB write and one more write, and blocked only on the write after that; the transport makes exactly two, the request and its newline. A smaller send buffer would therefore not have helped. Instead, a new package-private constructor accepts the socket factory ([GossipTransport.java:105](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L105)), and a test supplies a socket whose writes block until it is closed, as a real write does once a peer that never reads has filled the buffers. The test checks that the exchange still ends at its deadline with a timeout ([GossipTransportTest.java:657](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipTransportTest.java#L657)). A watchdog that no longer closes the socket at the deadline passed every test before; it now fails this one.
- **What the Windows result means:** with requests capped at 64 KiB, the transport's write cannot block on Windows, so there the watchdog's deadline close never runs. It remains a safety net for systems whose socket buffers are smaller than a request; other systems were not measured.

## Batch selector design (2026-10-17)

`GossipBatchSelector` decides which members travel in each message. It was hard to follow, so it now has comments that explain it as it stands. A review of the design itself found that most of its complexity comes from two choices, and that its structure hides how data flows through it. The proposal at its end has since been carried out; the problems described refer to the code before that change.

**What it has to do.** A message carries the sender plus at most `max.batch.members - 1` members, within the bytes the envelope leaves. Two rules take turns, one pick each, and the turn carries over between messages: priority, where changed members go first and departures go before other changes; and a scan, a round-robin over the whole table. A member that does not fit is not skipped, and each direction keeps its own progress. GOSSIP_PLUGIN.md documents all of this, and tests pin it.

**Where the complexity comes from.**
- **The scan is a cursor over table order.** That one choice causes the dense half of `select`: an `included` flag on every entry for the batch being built, a loop that skips members already taken by priority, the `candidate.index == position` arithmetic, and resuming by member key with an index fallback for when that member has left. It also lets a member go out twice in a row: with members A to D and two per message, after [A, B] and [C, D], a change to B gives [B, A] and then [B, C].
- **`refresh` has a performance shortcut.** It reuses last call's entry at the same position, to avoid a map lookup per member. It is worth keeping (see the measurements below), but it is woven through the comparison logic.

**How the structure hides the data flow.** `select(records, …)` reads like a function of its arguments, but its result depends on state left by earlier calls, and its third line calls `refresh(records)`, a void method. `refresh` does not modify `records`: the list is an unmodifiable copy made by [`MembershipTable.peers()`](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L371). What it changes is the selector's own state, about eight fields and every `Entry`, and neither signature shows that.
- `select`'s loop silently depends on `refresh` having just run. This temporal coupling through fields is visible only by reading both methods.
- The name `refresh` says nothing. What it does is compare a table snapshot with what the selector saw last time.
- It does three jobs: it rebuilds the scan order, queues new and changed members, and drops removed ones.
- Scratch data for a single call (`index`, `included`) lives in long-lived objects, so correctness depends on `refresh` resetting it first.
- The root cause is that the table hands out snapshots rather than changes, so each consumer has to work out what changed by comparison.

**A prototype of a simpler scan.** In a throwaway copy of the code, the scan became a queue of every member, least recently sent first. The scan takes the front, and sending a member, by either rule, moves it to the back. That removes the cursor, the resume key and its fallback, both per-entry flags, and all the position arithmetic; the loop becomes "pick the candidate, check that it fits, send it". In the example above, B is not repeated: [B, A] is followed by [C, D]. Every member is still reached within one pass of the table, and one-member messages still alternate between the rules. All 276 gossip tests passed unchanged. Inbound exchange cost, in µs per exchange, medians of three interleaved runs:

| Inbound exchange | Current | Rotation | Rotation without the shortcut |
|---|---|---|---|
| 128 members, empty metadata | 12.5 | 12.9 | 13.4 |
| 128 members, small metadata | 33.0 | 31.8 | 33.9 |
| 480 members, empty metadata | 14.5 | 15.0 | 17.4 |
| 480 members, small metadata | 34.0 | 34.2 | 37.4 |

Peer selection, which these changes do not touch, varied by about ±15% between runs, so differences under 1 µs are noise. The rotation's cost was lost in that noise (measured on its own, it is not free; see the outcome below), while removing `refresh`'s shortcut costs about 3 µs per exchange at 480 members.

**Making the data flow explicit without copying.** A fully pure design, in which each call returns new immutable state, would copy the membership state on almost every exchange, because every selection changes the rotation and the turn. Java's standard collections are not persistent, and a persistent-collection library such as Vavr or PCollections is not worth adding for this. Copying is not needed, though. Each step can read the state by reference and return a small result, and one explicit call applies the results:

```java
Changes changes = Changes.between(state, records);               // Only what changed: a few members per exchange.
Selection selection = choose(state, changes, maxBatch, budget);  // The batch, and which members were sent.
state.apply(changes, selection);                                 // The one mutation, visible at the call site.
```

The only copy the selector needs is the one `peers()` already makes, under the table's lock, because other threads keep changing the table.

**Proposal.** Adopt the rotation together with this explicit data flow, keeping `refresh`'s shortcut inside the comparison step. Make the change test first, starting from a test that fails on the current code (a member just sent by priority must not open the next batch), and benchmark it against the current code. A larger, separate change would remove the comparison altogether: the table could publish its changes, for example with a sequence number per update.

**Outcome (2026-10-17).** Carried out as proposed. `select` now takes three explicit steps ([GossipBatchSelector.java:68-78](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L68-L78)): `MemberTracker.update` returns a `Diff` of the snapshot ([113-155](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L113-L155)), `BatchQueues.apply` takes it in, and `BatchQueues.choose` picks the batch without changing anything ([203-243](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L203-L243)) before `sent` records it ([246-259](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L246-L259)). The cursor, the resume key and its fallback, the per-entry `index` and `included` fields, and the skip loop are gone; the state a single call needs lives in local variables. The tracker keeps the position shortcut.

Test first: a new test that a member just sent by priority does not open the next batch failed on the old code, which gave [B, C] after [B, A]. Two more new tests check what the tracker reports, and that choosing changes nothing until the batch is recorded as sent. All 279 gossip tests pass. Six deliberate errors were each caught: a queue, or the rotation, no longer passing over members already in the batch; not moving sent members to the back; the news flag stuck at true, or at false; and a reorder reported as a change. Two of them were caught only by the new tracker test.

**Cost, accepted.** The redesign is slower. The selector alone, in ns per call, medians of three interleaved runs:

| Selector call | Before | After |
|---|---|---|
| 128 members, no changes | 750 | 1348 |
| 128 members, 4 changes per call | 1245 | 1838 |
| 480 members, no changes | 2810 | 3256 |
| 480 members, 4 changes per call | 3027 | 4612 |

Most of the difference is the rotation itself: the prototype, which had no explicit steps, cost about the same. Moving each sent member to the back of a linked structure scatters pointer updates through memory, where the old cursor stepped through an array; the explicit steps add at most 0.3 µs. An inbound exchange costs about 13 to 20 µs in process, so this adds roughly 3 to 10% to it, and under 2 µs per message against about 1 ms for the network round trip. Storing the rotation in an array ring would win most of it back, at the price of the index arithmetic this change removed; the simpler design was chosen.

## Readability review of the larger classes (2026-10-17)

After the batch selector, the seven largest classes were read in full for readability. The selector's problems are not isolated: the same habits recur across the plugin, and `GossipRuntime` is the worst. Classes are listed from hardest to read to easiest. Each finding fixed since says which step fixed it.

### GossipRuntime

- **One class does seven jobs:** round scheduling, exchange workers, DNS, detecting the local process, the departure protocol, failure classification, and shutdown. It has 31 fields, 12 of them for departure alone. That is a state machine scattered across fields and kept in step by hand. Fixed in step 2, below.
- **States are strings.** `departureState` is "NOT_REQUESTED", "DISSEMINATING" and so on; `departureOutcome` is "FAILED", "COMPLETED", "EXHAUSTED" or "CANCELLED"; `metrics.workerState` takes seven values, set from about a dozen places; and `exchange` tracks its outcome as a string and switches on it. The compiler cannot catch a typo in any of them. Fixed in step 1, below.
- **`exchange` tracks its progress in four mutable flags**: `resolvedKey`, `acquired`, `resolving` and `outcome`, across try, catch and finally. The outcome is decided in three different places. One line packs five statements, including `resolvedKey = null` as a hidden "do not release" signal. Fixed in step 2, below.
- **`classify` matches substrings of exception messages:** "mismatch", "disagrees", "CAPACITY:", "gossipVersion", "swarmId". Rewording an error message in `GossipTransport` or `MembershipTable` silently moves failures into another metrics category. Fixed in step 1, below.
- **`round` does everything in one synchronized method**: heartbeat, maintenance, reaping finished workers, completing a departure, counting free slots, choosing peers, and starting workers. Fixed in step 2, below.
- **`closeOwned(boolean departed)` takes a flag argument**, sets state with a nested ternary (`failure == null ? departed ? "QUIESCED" : "STOPPED" : "CLEANUP_FAILED"`), and logs inside a `finally`. `run()` may also call it on the scheduler thread itself, through a `tryLock`. Fixed in step 2, below.

### MembershipTable

- **`apply` is a 90-line method**. It decides conflicts, compares versions, handles tombstones and direct-success evidence, and then admits members against capacity, sorting them first. Fixed in step 3, below.
- **The rule for which record wins is never named.** It is spread over nested conditions such as `suppressed.state() == LEFT || (incoming.state() == ALIVE && (incoming.heartbeat() < suppressed.heartbeat() || (sameVersion && !directRecord)))`. Fixed in step 3, below.
- **The variable `old` ends up holding the new entry.** It is reassigned three times. Fixed in step 3, below.
- **Nulls select the mode.** Four public merge methods funnel into `apply(records, direct, sender)`, passing `null` for whichever modes do not apply. Fixed in step 3, below.
- **`snapshot` and `conflicts` match up two parallel sequences by a shared `index++`:** a list of assessments and the map's iteration order. Fixed in step 3, below.

### GossipTransport

- **`exchange` is 70 lines in a `while (true)` loop** whose `continue` handles the version-1 downgrade. It sends, makes five checks on the reply, merges under a lock, remembers version-1 peers, logs conflicts, and maps errors to metrics. Fixed in step 4, below.
- **The reply checks throw plain `IOException`s whose message text `GossipRuntime.classify` later parses**: the same string coupling, seen from the other side. Fixed in step 1, below.
- **Two `exchangeBytes` overloads do different things:** one tries a list of addresses, the other runs a single connection. Fixed in step 4, below.

### PeerSelector

- **`select` decides which candidate wins inside cryptic merge lambdas**: `(a, b) -> a.recovery() ? b : a` and `(ignored, known) -> known != null && !known.recovery() ? known : …`. The reserved recovery slot is a bare `++selections % 3 == 0`. Fixed in step 5, below.
- **`nextRecovery` uses the cursor-plus-key-fallback pattern that was removed from the batch selector**, with an O(n) `indexOf` on every call. Fixed in step 5, below.
- **The backoff in `failure` is inline arithmetic**: a doubling loop, then `low + random.nextLong(Math.max(1, Math.min(MAX_BACKOFF - low, base / 2) + 1))`. Fixed in step 5, below.
- **`normalized`, `canonical` and `isSelf` are three similar ideas** with overloads in which a null `InetAddress` means "this is a hostname". Fixed in step 8, below.

### GossipWire

- **The same lazy cache is written out four times**: volatile fields with -1 or null sentinels, each calculated from whichever record arrives first. Fixed in step 6, below.
- **Reply overheads live in an array indexed by `1 + admission.ordinal()`**. Reordering the enum would silently break it; an `EnumMap` would not. Fixed in step 6, below.
- **`requireForwardable` reports three different failures with one message**. Fixed in step 6, below.
- **`GossipWire` doubles as the plugin's general helper class:** `GossipWire.invalid`, `exactLong` and `bytes` are called from the runtime, the commands and the selector. Fixed in step 6, below.

### GossipCommands

- **The constructor takes a snapshot and runs four encode-and-check validations**. Fixed in step 7, below.
- **`members` pages by arithmetic with unexplained constants**, `cursor.length() + 2 - 4`, and then re-encodes the whole page to check it anyway, which undoes the point of the arithmetic. Fixed in step 7, below.

### Less urgent

- **`MemberRecord`** is dense, but each piece is justified and documented ([MemberRecord.java:43-77](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L43-L77)).
- **`GossipConfig`** is mostly fine. One risk: it is a 15-component record built by position ([GossipConfig.java:118-124](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipConfig.java#L118-L124)), and several neighbouring `int` and `long` arguments can be swapped without a compile error. Not made one of the steps below. Fixed 2026-10-18, after archiving. The record is built by position in one place only, `fromProperties`, and a swap there went unnoticed: in a scratch copy, swapping `fanout` and `max.inflight`, which both default to 2, passed every config test. A new test, `everyKeyReachesItsOwnSetting`, gives every key a value no other key has and checks each setting, so it catches any swap; it caught all five swaps tried, before and after the change. `fromProperties` now reads each setting into a local named after its component, so an argument in the wrong place also shows as a mismatched name.

### Problems that run through the whole plugin

1. **Stringly-typed code:** state names, outcomes, failure categories found by matching message text, and the protocol handled as `Map<String, Object>`, with the same field-name literals repeated in six places. Step 1 fixed all but the protocol maps; step 12 wrote their field names once, though the messages were still maps. Fixed 2026-10-18, after archiving: the messages are typed records (`GossipWire.ExchangeRequest`, `ExchangeReply` and `MembersPage`), and `GossipWire` turns them into maps only where they reach or leave the host. A new test pins the exact JSON of every message, and passed before and after the change.
2. **Raw `long` times with hand-written `* 1_000_000` conversions**, in the table, the selector, the runtime, the wire and the metrics, where `Duration` would do. Fixed in step 11, below.
3. **Null as a mode switch:** `apply`'s `direct` and `sender`, `Long lastDirectSuccess`, and the hostname overloads in `PeerSelector`. Step 3 removed the first, step 10 the second, and step 8 the last. Two more were found and fixed later: `GossipMetrics.ageMillis` and `Exchange.senderAdmission`; see [Found during the readability steps](#found-during-the-readability-steps-2026-10-17).
4. **Several statements per line inside braces, and lines over 120 characters.** They are hard to read, and impossible to put a breakpoint on one statement. Fixed in step 13, below.
5. **Lock order is never written down.** For example, the runtime takes the selector's lock and then the table's ([GossipRuntime.java:307](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L307)). There is no cycle today, but nothing stops one being added. Fixed in step 9, below.

### Suggested order

1. ~~**Replace the string states with enums, and classification by message text with a typed failure kind.** This is small, mechanical and safe, and it removes the hidden coupling between classes.~~ Done 2026-10-17; see below.
2. ~~**`GossipRuntime`:** move departure into its own class, and break `exchange` and `round` into named steps.~~ Done 2026-10-17; see below.
3. ~~**`MembershipTable.apply`:** pull the version-comparison rule out as a named decision, separate from admission.~~ Done 2026-10-17; see below.
4. ~~**`GossipTransport.exchange`:** split it into sending, checking the reply, and merging.~~ Done 2026-10-17; see below.
5. ~~**`PeerSelector`:** name the candidate rules and the backoff calculation, and replace the recovery cursor.~~ Done 2026-10-17; see below.
6. ~~**`GossipWire`:** measure each overhead once, from the config, and give each forwarding check its own message.~~ Done 2026-10-17; see below.
7. ~~**`GossipCommands`:** validate the configuration once, and page members without re-encoding the page.~~ Done 2026-10-17; see below.
8. ~~**`PeerSelector`:** replace the overloads in which a null address means a hostname.~~ Done 2026-10-17; see below.
9. ~~**Lock order (problem 5):** write the order down, and test that no class can reach one above it.~~ Done 2026-10-17; see below.
10. ~~**`Long lastDirectSuccess` (the rest of problem 3):** an explicit type in place of the null.~~ Done 2026-10-17; see below.
11. ~~**Hand-written time conversions (problem 2):** the JDK's units in place of `* 1_000_000`.~~ Done 2026-10-17; see below.
12. ~~**Protocol field names (the rest of problem 1):** one constant for each name, in place of literals repeated across three files.~~ Done 2026-10-18; see below.
13. ~~**One statement per line, and lines within 120 characters (problem 4):** last, since it touches every file.~~ Done 2026-10-18; see below.

Steps 9 to 13 take the problems that run through the plugin, smallest first.

Each change should be made test first, with the existing tests as the safety net, and benchmarked wherever it touches the exchange path.

**Step 1 done (2026-10-17).** The runtime's lifecycle, departure and exchange states are enums in `GossipRuntime`: `State`, which the runtime owns and status reads through `state()`, and the private `DepartureState` and `Outcome`. Status and departure results report the same names as before. Failures now carry their metrics category. `MembershipException` has a kind, which defaults from its reason and is set to version or swarm where those mismatches are detected; the transport's own failures are a new [`GossipExchangeException`](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipExchangeException.java) with a kind; and the top-level [`GossipFailure`](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipFailure.java) enum holds the kinds, with `GossipFailure.of` in place of `GossipRuntime.classify`. Neither enum lives in `GossipMetrics`: metrics count by the failure kind rather than define it, and the transport no longer calls into the runtime to name a failure. Only a remote peer's error, which arrives as text, is still classified by its wording, in one method. No message changed, since some of them reach peers.

Test first: a test that a plain `IOException` reading "Gossip server ID mismatch" counts as a network failure failed before the change, and a test of the typed kinds did not compile before it. The category of each of the 17 kinds of bad reply in `GossipTransportTest` is now pinned end to end; all 17 passed before and after. All 281 gossip tests pass. Nine deliberate errors were each caught, three of them only by the new pin: an untagged version failure, an untagged swarm failure, and an unreadable reply passed on without a kind.

Seen while doing this, and left as it was: a request over the byte budget ("Outgoing exchange exceeds byte budget") is counted as a network failure, although it is a local protocol problem.

**Step 2 done (2026-10-17).** The departure is its own class, [`Departure`](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/Departure.java): twelve fields became one object with named operations, which the runtime uses under its own lock as before. In `GossipRuntime`, `exchange` now resolves the peer, claims the endpoint ([GossipRuntime.java:304](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L304)), exchanges, and records the reply or the failure (`answered`, `failed`) before `finish` releases the claim. A `Claim` record replaces the `resolvedKey` and `acquired` flags; the `resolvedKey = null` line turned out to be dead code. `round` is a sequence of named steps, and `run` waits through `awaitRound` and hands a finished departure to `finishDeparture`. `closeOwned` takes a `StopReason`, which carries the state a clean stop ends in, and is split into `beginStop`, `joinAll` and `finishStop`. No behaviour changed.

Test first: five new `DepartureTest` tests did not compile before the class existed, and the existing runtime, departure and swarm tests pinned everything else, unchanged. All 286 gossip tests pass. Nine deliberate errors were each caught, four in `Departure` and five in the runtime: the target cap ignored, a tried target kept, an undecided failure replacing how the departure ended, skips counted as failures, an endpoint claimed twice, a failure counted after stop, the final states swapped, the outcome never published, and a departure completing while its exchanges still ran.

**Step 3 done (2026-10-17).** `MembershipTable.apply` ([MembershipTable.java:151](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L151)) now switches over an explicit decision for each record: `decide` returns `Skip`, `Keep`, `Advance` or `Admit`, from three short rules, `fromLocal`, `fromKnown` and `fromUnknown` ([MembershipTable.java:183-249](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L183-L249)). They form a sealed type, so the switch must handle every case. A sealed `Evidence` type, `Forwarded`, `FromSender` or `FromResponder`, replaces the two nullable mode parameters, and admission against capacity is its own method, `admit` ([MembershipTable.java:264](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L264)). The tombstone rule's condition is split into named parts. Snapshots work from entry and assessment pairs instead of two lists matched up by index. No behaviour changed, and Sonar no longer flags `apply`, which it had rated at a cognitive complexity of 70.

Test first: before the change, deliberate errors in each of the merge's 20 rules showed two that no test pinned: a different departure at the same version being a conflict, and an advance keeping the member's last direct success. Both now have tests, which passed before the change. All 288 gossip tests pass, and 22 deliberate errors in the new code were each caught. A merge of an inbound exchange's sender and 30 forwarded records costs the same as before, within 3% either way at 128 and 480 members, for unchanged and for newer records.

**Step 4 done (2026-10-17).** `GossipTransport.exchange` ([GossipTransport.java:127](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L127)) now checks its arguments, takes a permit and runs one `Conversation`, turning its failures into metrics and identity log lines. `Conversation.run` ([GossipTransport.java:171-195](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L171-L195)) reads in the order the protocol works: choose the batch, send, ask again in version 1 if the peer rejects version 2, accept the reply, merge it, and remember a version-1 peer. Each step is a named method ([GossipTransport.java:201-252](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L201-L252)). `send` returns a reply once it is correlated and comes from the intended peer. `accepted` returns the exchange once the reply is a success, in the version asked for, from the sender it names, with every record forwardable. `merge` commits it unless the transport has closed. The `while (true)` loop and its `continue` are gone. The two `exchangeBytes` overloads are now `exchangeOverFirstReachable` ([GossipTransport.java:292](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L292)), which tries each address in turn, and `exchangeOver`, which runs one connection. No behaviour changed.

Test first: before the change, deliberate errors in 20 of the exchange's rules showed seven that no test pinned. Six now have tests, which passed before the change: a closed transport refuses work without counting a failed exchange, an exchange with this process is refused before any connection, a version-1 retry goes only to the address that answered, records that could not be forwarded are rejected before merging, a transport closed while its reply arrives merges nothing, and an identity conflict found while merging a reply is logged. The seventh is the request's byte-budget check, which cannot fail because the batch is chosen to fit; it stays as a guard, and a comment now says so. All 294 gossip tests pass, and 20 deliberate errors in the new code were each caught. Over nine interleaved runs of each version, the median cost of a loopback outbound exchange was within 2% of before, at 128 and at 480 members. Single runs of either version varied by up to 45%, so a smaller difference would not show.

**Step 5 done (2026-10-17).** `PeerSelector.select` ([PeerSelector.java:62](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L62)) gathers its candidates in `candidates` ([PeerSelector.java:103-128](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L103-L128)), where two candidates at one endpoint are settled by one named rule, `Candidate.preferred` ([PeerSelector.java:287](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L287)): a healthy one is kept, otherwise the later one, so that a recovering seed keeps its hostname. The two merge lambdas turned out to be that same rule written twice. The reserved slot is `RESERVED_EVERY`, and the backoff is `backoff(failures)` ([PeerSelector.java:204-210](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L204-L210)), whose comment states the doubling, the 30 s cap and the jitter. The recovery cursor is replaced by a queue of turns ([PeerSelector.java:51](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L51), `takeTurns`): each recovery peer has a turn number and the lowest goes next, a probed peer goes to the back, and a peer that newly needs recovery joins behind every peer already waiting. Nothing is found again by index, so peers leaving or arriving cannot make it skip or repeat one. The reserved-slot condition lost `|| ordinary.isEmpty()`, which made no difference: with no healthy peers, every slot goes to recovery peers anyway.

The queue changes two outcomes, both test first. When the peer due next left along with an earlier one, the cursor fell back to an index and could probe again the peer it had just probed: with recovery peers A, B, C and D, after A and B, removing A and C gave B again although D had never been probed. The 2026-10-16 fix handled the due peer moving, not leaving. And a newcomer could be probed before a peer that had been waiting since before it arrived; it now waits its turn, so newcomers can never starve the others.

Found by the deliberate errors and fixed, test first: a full member known by its hostname, rather than as a configured seed, lost its backoff. `capacity` kept the backoff under the address that answered, but selection looks a member up under its own endpoint, so the member was retried every round and its backoff never grew. `capacity` ([PeerSelector.java:174](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L174)) now keeps it under the peer's canonical endpoint, which for a seed is still the address its alias was confirmed at.

Test first: before the change, deliberate errors in 33 of the selector's rules showed 16 that no selector test caught. Thirteen of them were rules that now have tests, which passed before the change: a member that has left is never offered; a healthy record wins over a recovering one at the same endpoint; retry state is dropped for a peer no longer offered, but kept while its exchange runs; the fanout caps the selection; a success clears backoff kept under the resolved address; a first backoff is jittered below one round as well as above; retry state is bounded; the local hostname matches in any case; a failure after an alias expires is charged to the hostname; only configured seeds gain aliases; an unseen seed gets only reserved slots; and the backoff never passes 30 s when a round is longer. Two made no difference: the reserved-slot condition above, and removing expired aliases at the start of each round, since `canonical` already ignores them and there is at most one per seed. The last was the capacity bug. Deliberate errors in the new code then found two more rules that no test pinned, both now tested: a peer that recovers and later needs recovery again rejoins at the back, which also keeps the queue bounded, and a seed that failed by its hostname and is then found full keeps growing its backoff. The second was just as unpinned before. All 311 gossip tests pass, and 36 of 37 deliberate errors in the new code were each caught; the other removes expired aliases, which makes no difference, as above. Selection costs no more when every member is healthy: within 5% at 128 and 480 members, and faster if anything. With a quarter of the members dead, so that turns are taken every round, its median cost rose 5% at 128 members (11.8 to 12.4 µs) and 13% at 480 (46.0 to 52.1 µs), over five interleaved runs of each; the fastest runs at 480 differ by 10%. That is the turn map's upkeep, about 5 µs once a round.

**Step 6 done (2026-10-17).** Every size overhead in `GossipWire` depends only on the config, so the constructor measures each once, around a sample record whose own bytes it then takes away ([GossipWire.java:85-101](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L85-L101)). The four lazy caches, with their volatile fields and -1 or null sentinels, are `final` fields, and the reply overheads are an `EnumMap` by admission instead of an array indexed by `1 + ordinal()`. `requireForwardable` ([GossipWire.java:218-238](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L218-L238)) keeps no memo of the local record's limits either: `MemberRecord.with` sizes the local record at its widest heartbeat without encoding it, so the limits are added up on each call. Its failures now say which check failed: the local record alone, a record as a request sender, or a record as a members-page row. Both reply messages still begin with the old wording, so anything matching it still does, and neither contains a word that remote classification looks for. The check of a record beside the local sender in a reply is gone: a members-page row costs more than the same record in a reply, by a constant that `aRecordTooLargeForTheReplyIsAlsoTooLargeForTheMembersPage` pins, so that check could never fail first. `invalid` is now `MembershipException.invalidRecord`, and `exactLong` and `exactInteger` are in `GossipValidation`; `bytes` and `memberView` stay, since they are the wire format.

Test first: before the change, deliberate errors in 23 of the size rules showed eight that no test caught. Four were the local record's limits: that it is budgeted at its widest heartbeat, and that its limits follow a change of state, host or port. The sweep of the local record paired it only with a peer whose metadata made the peer's own row fail first. It now also runs beside the smallest peer, and a new sweep changes one field of the local record at a time; both passed before the change and catch all four. The other four never decided an outcome, because the reply checks shared the row check's message: the beside-sender check, its batch condition, the empty-reply check, and the admission it was sized for. With a message of its own, the local-record check decides at its own threshold; the sweep must now produce exactly four outcomes, OK, the request-size failure, the local record alone and the page row, which failed before the change. All 312 gossip tests pass, and 21 of 22 deliberate errors in the new code were each caught; the other applies the request-size check to the local record too, which cannot fail first, since the local record is checked first in an empty reply at its widest heartbeat, and a reply's overhead is larger than a request's. An inbound exchange in process costs the same within the noise: over five interleaved runs of each, its medians moved between -6% and +9% across the four cases, while peer selection, whose code is identical in both, moved between -21% and +3% in the same runs; the fastest runs of each case are within 4%.

**Step 7 done (2026-10-17).** The `GossipCommands` constructor checks, in `requireRepliesFit` ([GossipCommands.java:76-84](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L76-L84)), that the local record is forwardable and that status fits, and it takes no snapshot to do so. The other two of its four checks could not fail once the first had passed: the forwardable check already sizes the larger version-2 empty reply, and a members page holding the local record's row, at their widest; and the first member of a snapshot is always the local record. `members` hands the paging to `membersPage` ([GossipCommands.java:156-177](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L156-L177)). The cursor arithmetic is `cursorBytes` ([GossipCommands.java:188](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L188)), whose comment says why a cursor costs its length plus two quotes, less the four bytes of `null`, and the default page size is `DEFAULT_PAGE_LIMIT`. A finished page is no longer encoded again to check it: it is sized exactly, as exchange replies already are. The failure for a single row that does not fit stays, as a guard that cannot be reached, since every record was checked as a page row at its widest before it was merged. No behaviour changed.

Test first: before the change, deliberate errors in 13 of the commands' rules showed seven that no test caught. A new test starts commands for local records of every size the table accepts, at the smallest byte limit and a larger one, and checks that each start fails exactly when the local record cannot be forwarded or status cannot be sent. It produces all four outcomes, OK, status too large, the local record alone and the page row, and it catches the removal of either check that decides. A second new test pins the default page size. Both passed before the change. The other four made no difference, before or after: the two start-up checks that the first implies, the final page check, and the unreachable guard. All 314 gossip tests pass, and 12 of 13 deliberate errors in the new code were each caught; the other removes the unreachable guard. One of the 12, a cursor that never runs out, made a capacity test page forever, so it was run against the commands tests alone, which caught it. Paging and start-up are not on the exchange path, so they were not benchmarked; a members page is now encoded once fewer.

**Step 8 done (2026-10-17).** `PeerSelector` parses each host into a small sealed type, `Host` ([PeerSelector.java:256-272](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L256-L272)): a `Literal` holding its address, or a `Name` holding the hostname in lower case. `normalized` takes the normal form from `Host.normal()`, and `isSelf` ([PeerSelector.java:248](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L248)) is the same port and equal hosts. That is what its old pair of checks came to, a case-insensitive comparison of the text and then of the parsed addresses: hostnames are validated ASCII, so lower-case equality is the case-insensitive one, and two literals are equal exactly when their addresses are. No overload takes a null to mean a hostname any more, and each host is still parsed once a round. No behaviour changed.

Test first: before the change, deliberate errors in 11 of the host rules showed one that no test caught. Without its null check, comparing a local process advertised by hostname with a different host on its port threw a `NullPointerException`. A new test, which passed before the change, pins that such a host is not this process; with `Host` there is no null to check. All 315 gossip tests pass, and all 9 deliberate errors in the new code were each caught. Selection costs the same within the noise in three of the four cases: over five interleaved runs of each, their medians are within 2% of before, and the fastest runs of all four cases are within 2%. In the fourth, 480 members with a quarter dead, the median rose 9%, with runs from 43 to 56 µs against 42 to 48 µs before. The change parses the same hosts in every case, so this is more likely noise, but it is the one result here that cannot be ruled out.

**Step 9 done (2026-10-17).** The lock order is written down once, in the package documentation ([package-info.java](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/package-info.java)): the plugin, the runtime's close lock and then its monitor, the transport and the selector, which are never held together, the table, and then the leaf locks. Checking every place a lock is taken found no cycle, and showed why there is none: no class keeps a reference to one whose lock comes before its own. A new test, `GossipLockOrderTest`, checks exactly that, for every field of each class and of the classes nested in it, and through interfaces too, such as the runtime's `ExchangeClient`. It cannot see a callback passed down the order, so the documentation names the one there is: the snapshot supplier the selector calls under its own lock. No code changed.

Test first: the new test passed against the code as it was. Six deliberate upward references were each caught, with a message naming the field: from the table to the runtime, from the selector to its peer the transport, from the log to the metrics, from a record nested in the table to the selector, from the transport to the runtime, and from the metrics to the transport behind its interface.

**Step 10 done (2026-10-17).** A member's last direct success is an `OptionalLong` in the table's `Entry` ([MembershipTable.java:446](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L446)), empty until this process has exchanged with the member itself, and so is its age in `MemberView`. `evidenceAge` ([MembershipTable.java:426-439](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L426-L439)) still takes the most recent evidence by comparing ages rather than times, since only nanoTime differences survive wraparound; a comment now says so. The members page still writes a missing direct success as `null` ([GossipWire.java:271-274](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L271-L274)). `MemberView` is public, so `directSuccessAgeNanos` changed type, from `Long` to `OptionalLong`; nothing outside the plugin uses it. No behaviour changed.

Test first: before the change, deliberate errors in 10 of the direct-success rules showed two that no test caught: the local member starting with a direct success, and a departing peer, which keeps answering exchanges with its LEFT record, being kept fresh by those replies. Both now have tests, which passed before the change. All 318 gossip tests pass, and 12 deliberate errors in the new code were each caught. A snapshot now allocates an `OptionalLong` for each member with a direct success. Snapshots are taken once a round and for status and members requests, not once an exchange, so this was not benchmarked.

**Step 11 done (2026-10-17).** The ten hand-written conversions, `* 1_000_000` and `/ 1_000_000`, are `TimeUnit` conversions, in the table, the selector, the runtime, the departure, the wire, the metrics and the config. Times stay `long` nanoseconds inside: the table and the selector compare them for every member in every snapshot, where a `Duration` would be allocated for each comparison. The config's limit on millisecond settings is written as what it means, the largest value that still converts to nanoseconds ([GossipConfig.java:38-39](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipConfig.java#L38-L39)). The results are the same: every millisecond setting is within that limit, so neither form overflows, and both truncate a remainder the same way. No behaviour changed.

Test first: before the change, deliberate errors in the ten conversions, each a thousand times out, showed five that no test caught: the config's limit, the metrics' ages, the heartbeat interval, and the two ages a members page reports. Each now has a test, which passed before the change. The limit is pinned at exactly its value, the ages are read after a known wait and from a known age, and the heartbeat must not advance after four milliseconds of a five-millisecond interval. All 322 gossip tests pass, and ten deliberate errors in the new code, each converting with the wrong unit, were each caught. The full build for this step failed once, on a race in the DNS resolver that is older than this review; see [Found during the readability steps](#found-during-the-readability-steps-2026-10-17).

**Step 12 done (2026-10-18).** The protocol's field names are each written once, in `GossipFields` ([GossipFields.java](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipFields.java#L1-L47)): the exchange's six, the seven of the members page and its capacity, and the members request's two. The operation names are constants in `GossipCommands`, which dispatches on them, and a member record's seven field names are constants in `MemberRecord` ([MemberRecord.java:47-54](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L47-L54)). `GossipFailure` still matches the word `gossipVersion` in a remote peer's error text: that is the wording deployed peers send, not the field. The messages stay maps; typed messages, the other half of problem 1, were not part of this step. A `Pattern` in `GossipCommands` was named `CURSOR`, like the constant for the cursor field, and a field of a class hides a constant it imports by the same name without a warning, so the pattern is `CURSOR_CHARACTERS` now. No behaviour changed.

Test first: with each field name a single constant, a misspelling changes encoding and decoding together, so only a test that names the field as peers send it can catch one. Deliberate errors misspelling each of the 23 names were each caught, and so were eight errors in the reshaped `decode` and one in the runtime's capacity check: 32 of 32. All 328 gossip tests pass.

**Step 13 done (2026-10-18).** Step 13 left no line of the main sources longer than 120 characters, and none with several statements inside one pair of braces: 111 long lines and 17 packed ones were laid out again, in the style the code already used for longer blocks and continuations. A small tool did it, and checked every file: its code tokens, and the words of its comments in order, came out exactly as they went in, so only the layout changed. Three `Map.of` calls were laid out by hand, to keep each key beside its value, and so was a ternary that read wrongly when broken at the `||` inside its last branch; it gained a comment. Short one-statement blocks, such as `if (x) { return y; }`, are the code's style and stayed. Layout changes no behaviour, so no deliberate errors were run; the compile and all 328 tests confirm it.

## Found during the readability steps (2026-10-17)

Problems found while carrying out the steps above, beyond what each step set out to fix.

### Fixed

- **The DNS resolver counted a finished lookup against its helper limit.** A lookup thread wakes its callers first and leaves the active lookups only afterwards. A caller woken in between that asked for another host was refused with "Gossip resolver capacity is full", and in the runtime that counts as a failed exchange and backs the peer off. Found when the full build for step 11 failed in `GossipDnsResolverTest`, which it had passed many times: the race shows only under load. A test that cannot depend on luck holds the resolver's lock, so a finished helper cannot clean up, and asks for a second host with one helper allowed ([GossipDnsResolverTest.java:86](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipDnsResolverTest.java#L86)); it failed every time before the fix. A lookup now counts as running only until it has finished ([GossipDnsResolver.java:158](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L158)), for the limit and for the count the metrics report. All four deliberate errors in the change were caught, including one that lets the limit lapse altogether.
- **Two requests for one host could start two lookups, and an answer that came after its caller had given up was lost.** Only a waiting caller stored the answer, after the lookup had already left the active set, so a request in between found neither, and an answer no caller waited for was never stored. The lookup thread now stores its own answer in the same step as it leaves the active set ([GossipDnsResolver.java:96-101](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L96-L101)), so a later request for the host finds one or the other. A test whose caller gives up before the answer comes ([GossipDnsResolverTest.java:193](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipDnsResolverTest.java#L193)) found two lookups before the fix and one after, and a second pins that a closed resolver keeps nothing ([GossipDnsResolverTest.java:210](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipDnsResolverTest.java#L210)). Three of four deliberate errors were caught; the fourth showed that the caller's own store made no difference, so it is gone. `concurrentRequestsForOneHostShareASingleLookup`, which this gap could fail under load, can no longer fail on timing: a second request either joins the lookup or finds its answer.
- **A paging bug made two tests loop forever instead of failing.** `GossipCapacityTest` and `GossipSwarmTest` paged until the cursor was null, so a cursor that never ran out hung the build and wrote gigabytes of log. Both now fail as soon as a cursor repeats ([GossipCapacityTest.java:86](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipCapacityTest.java#L86), [GossipSwarmTest.java:177](../../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipSwarmTest.java#L177)), as the other paging tests already did; the deliberate error that hung them now fails them. `GossipSizeAccountingTest`'s loop compares each page with the original algorithm's, so it could never loop.
- **`GossipMetrics.ageMillis` returned null for a time never taken.** It is an `OptionalLong` now ([GossipMetrics.java:76-81](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipMetrics.java#L76-L81)). The `NEVER` sentinel stays inside the class, where it saves boxing a time on every exchange, and status and runtime replies still write `null` for none, through `GossipWire.orNull` ([GossipWire.java:283](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L283)). Four deliberate errors were each caught.
- **`GossipWire.decode` was hard to follow.** It makes the same checks in the same order, now with three named steps, `expectedFields`, `withinBatch` and `senderAdmission`, and no nested ternary ([GossipWire.java:104-147](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L104-L147)). Before the change, deliberate errors in its 13 rules showed four that no test caught: a request naming another operation, the batch limit and its boundary, which bound the decoder's own work before the table checks the same limit, and a reply that is not a JSON object, which threw a `ClassCastException` instead of a protocol failure. Three new tests pin them, and passed before the change.
- **`Exchange.senderAdmission` was null for a request and for a version-1 reply**, another null used as a mode, found while reading `decode`. It is an `Optional` now ([GossipWire.java:75-80](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L75-L80)), and the runtime backs off a peer whose reply says `Optional.of(CAPACITY)` ([GossipRuntime.java:321](../../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L321)).
- **Commit `6d2746b` contains a deliberate error.** It was made while a deliberate-error run for step 6 had changed `GossipWire`, so it holds an off-by-one in the request overhead that no working tree ever kept. `8a6b9c6` restores the line. Deliberate-error runs now work on a scratch copy of the tree, never on the repository itself.
- **Five links into test files had gone stale**, pointing at blank or unrelated lines after tests were added above them. They point at the tests they describe again.
- **Two DNS resolver tests raced the lookup thread** (found 2026-10-18, after archiving, by a failed full build). A lookup's thread stores its answer and leaves the active set just after its caller has the answer, so a test that checked the cache, or advanced its clock, straight away could see the cache before the answer was in it. Delaying that step by 200 ms in a scratch copy made `cachedAddressesRotateExpireAndStayWithinTheHostCap` and `actualSystemResolverHelperResolvesLocalhostAndNumericEndpointsNeedNoHelper` fail every time. Both now wait for the lookup to settle, and pass with the delay. The resolver itself was right: a request in that moment joins the finished lookup and gets its answer, which is only microseconds old.

### Open

- ~~**Nothing in the build stops a test that never ends.**~~ Fixed 2026-10-18, after archiving. Surefire's fork timeout had been tried without effect, but the cause was JUnit's: the tests that hung had `@Timeout`, whose default mode only interrupts the test's thread and waits for it, so a loop that ignores interrupts or is stuck in blocking socket I/O never ended. Each module's `junit-platform.properties` now runs timed tests on a separate thread, which JUnit abandons and fails at the deadline, and gives a test without `@Timeout` 60 seconds. In a scratch copy, a probe test that spins and ignores interrupts hung the build until it was killed before the change, and failed in 12 seconds after it. An abandoned thread still runs until its test JVM exits at the end of the module.

### Checked and left as they are

- **Sonar's warnings in the test sources** edited during the steps. Two are false positives: S2142 in `GossipDnsResolverTest`, whose fixture ignores interrupts on purpose, as the platform resolver does, and restores the interrupt status afterwards; and S4030 in `GossipRuntimeTest`, whose lists are read inside `await` lambdas. S3776 rates two sweep tests in `GossipSizeAccountingTest` above the complexity limit, and `GossipRuntimeTest` prints a result line with `System.out` (S106). The rest are naming and style, such as `record` used as a name (S6213), unnamed patterns (S7467), `int` additions (S2184) and wildcard return types (S1452), or expected in tests, such as IP literals (S1313).

## Suggested order of work

1. ~~**#1 and #2 together:** store each record's encoded size, then compute envelope and page sizes by adding them up.~~ Done 2026-10-16.
2. ~~**#5:** quick, low-risk cleanup of unneeded snapshots.~~ Done 2026-10-16.
3. ~~**#7, #8 and the low-impact items:** small, local changes.~~ Done 2026-10-16.
4. ~~**#3, #4 and #6:** larger refactors. Each needs its own change and careful testing of rollback, cancellation and timeout behaviour.~~ Done 2026-10-16.
5. ~~**#10:** the largest gain, in one method and its size test.~~ Done 2026-10-16.
6. ~~**The recount in #11:** a one-line change.~~ Done 2026-10-16.
7. ~~**#12:** small, local changes to validation and peer selection.~~ Done 2026-10-16.
8. ~~**The rest of #11:** changes the batch selector and the table's interface.~~ Done 2026-10-16.
9. ~~**#13:** a small change in `GossipWire`.~~ Done 2026-10-16.
10. ~~**The batch selector redesign:** the rotation with explicit data flow, test first.~~ Done 2026-10-17; see [Batch selector design](#batch-selector-design-2026-10-17).
11. ~~**The readability findings,** in the order given in [Readability review of the larger classes](#readability-review-of-the-larger-classes-2026-10-17).~~ Done 2026-10-18: all 13 steps, and every finding in [Found during the readability steps](#found-during-the-readability-steps-2026-10-17) but one, a build that cannot stop a test that never ends. Two points in the review itself were not made steps: `GossipConfig` built by position, and the protocol messages as maps (problem 1). All three were fixed after archiving.

The module's existing tests (`MembershipTableTest`, `GossipBatchSelectorTest`, `PeerSelectorTest`, `GossipTransportTest`, `GossipCommandsTest`, `GossipDnsResolverTest` and others) cover these areas and should be run after each change.
