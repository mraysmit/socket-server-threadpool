# Gossip Plugin Code Review

Scope: all 14 main source files in [plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/). Reviewed at commit `e8a6675`; line references refreshed on 2026-10-16 against the current code. Focus: efficiency, plus code that is hard to follow or subtly wrong.

## Summary

The protocol logic is careful and mostly correct: version checks, conflict detection and tombstones all hold up. The inefficiency comes mainly from three repeated habits, not from bad core algorithms:

1. **Data is encoded to JSON just to measure its size.** Each incoming record is JSON-encoded about 7–8 times per hop.
2. **`MembershipTable.snapshot()` is used like a getter.** It copies the whole table every time, and some callers only want one field that never changes.
3. **Every merge copies the whole table**, even though a batch holds at most 32 records.

The config caps (`max.records`, a 64 KB `max.exchange.bytes`) keep most of this to wasted CPU and memory churn rather than unbounded growth. The exceptions are `members()`, which does quadratic work, and the DNS resolver, which starts a JVM per lookup.

Line numbers and links refer to the code as of 2026-10-16.

## Status (2026-10-16)

**Fixed:** every finding, #1 to #13 and all six low-impact items. #10 to #13 came from a second pass that measured where the remaining time went; see [Second pass](#second-pass-efficiency-deep-dive-2026-10-16). A profiling pass between the two cut decoding costs; see [After the review](#after-the-review-measured-decoding-costs-2026-10-16). What was still open after #10 to #13 was then fixed too; see [Follow-up](#follow-up-what-was-still-open-2026-10-16). A check of how the plugin uses the host's plugin interface then found two deviations, both fixed, and one agreed exception; see [Plugin interface check](#plugin-interface-check-2026-10-16). Its two open items then got the tests they lacked; see [Open items tested](#open-items-tested-2026-10-16). Two related changes have also landed since the review:

- **Address fallback.** When a peer's hostname resolves to several addresses and connecting to one fails (refused or unreachable), the same exchange now tries the others. `GossipDnsResolver.resolveAll` returns every address, and `GossipTransport` and `GossipRuntime` use it. This affects how #4 should be fixed; see the note there.
- **Test support moved to the host.** Plugin tests now use the host's `dev.mars.testing` library. This changes no finding below.

## High impact

### 1. Re-encoding JSON only to check sizes — fixed 2026-10-16

**Was:** for every record in every exchange, `requireForwardable` built and encoded three complete envelopes: a request with the record as sender, a reply holding the record, and a members page holding the record. It also rebuilt a Base64 cursor that never changed, once per record. A reply carrying 31 records needed about 94 envelope encodings, roughly 10× the bytes actually sent. The same record was encoded again by `GossipWire.decode`'s request-size check, by `MembershipTable.checkRecordSize`, and by every call to `MemberRecord.serializedBytes()` or `fingerprint()`.

**Now:**
- [`MemberRecord`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L145-L157) is a final class that computes its encoded size and fingerprint once, then reuses them.
- [`GossipWire`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L113-L117) adds sizes up instead of encoding envelopes. A record's JSON is identical standalone or nested, so an envelope is a fixed overhead plus its records plus one comma between neighbours. Each overhead is measured once.
- [`requireForwardable`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L169-L184) needs no envelope encoding per record. Its two local-record overheads are recomputed only when the local record changes in more than its heartbeat.
- The request-size check in `decode` ([GossipWire.java:79](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L79)) no longer re-encodes the request.

**Verified by** `GossipSizeAccountingTest`. It keeps the original encode-everything algorithms as a reference and requires identical decisions, sweeping record sizes across every byte limit. It includes JSON-escaped and multi-byte metadata, and plain-ASCII sweeps that land on each exact limit. Six deliberately introduced one-byte errors were each caught.

**Measured:** an inbound exchange of 31 records into a 128-member table went from about 430 µs to about 208 µs. The remaining cost is mostly decoding records (#9), snapshots (#5) and encoding the actual reply.

**Follow-up, done 2026-10-16:** `GossipBatchSelector`'s own per-record size cache, redundant once `MemberRecord` cached its size, was removed (about 35 lines).

### 2. `members()` does quadratic work — fixed 2026-10-16

**Was:** each time a row was added, the code copied and encoded the entire page built so far. With 32 rows that was about 500 rows encoded per call, around 160 KB of JSON to produce a reply of about 30 KB.

**Now:** [GossipCommands.java:122-140](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L122-L140) measures the empty page once and each row once, and keeps a running total. The total accounts for the comma between rows and the exact width of the next-page cursor. Paging decisions are unchanged, and `GossipSizeAccountingTest` checks every page against the original row-by-row algorithm. The finished page is still encoded once as a final safety check. Measured: a 32-row page went from about 485 µs to about 88 µs.

### 3. Merging copies the whole table — fixed 2026-10-16

**Was:** on every inbound and outbound merge, `apply` copied both `entries` and `suppressions` into new maps, so a conflict thrown partway through a batch could not leave the table half-changed.

**Now:** the validation loop only reads the table; it collects updates and admissions, and every change is applied after it ([MembershipTable.java:145-147, 204](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L145-L147)). A conflict throws before anything is applied, so a rejected batch still leaves the table untouched, with no copy. Batch keys are already unique, so reading the live table gives the same answers the copies did.

**Verified by** the existing rollback tests. A deliberately introduced variant that applied each update inside the loop failed five of them.

**Later:** the active-member count was at first still recomputed with one pass over the table per merge, because keeping a counter in step across merges, expiry and departure would add more code than it saves. #11 made the count run only when a batch admits new members, which a stable swarm's batches never do.

**Measured with #9:** an inbound exchange went from about 185 µs to about 166 µs.

### 4. A whole JVM is started for each DNS lookup — fixed 2026-10-16

**Was:** each hostname lookup ran `java -cp … GossipDnsLookup` as a separate process, about 130 ms of JVM startup and tens of MB per cold lookup, so that stop could kill a lookup blocked inside the platform resolver.

**Now:** lookups run in-process, each on its own short-lived daemon thread, with the same two-second timeout, `max.inflight` bound and 60-second cache ([GossipDnsResolver.java:72-111](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L72-L111)). `resolveAll` still returns every address with the first one rotating, as the connection fallback requires. The helper program `GossipDnsLookup` and its test fixture program are deleted.

**Trade-off, agreed before the change:** a lookup blocked inside the platform resolver can no longer be killed. On timeout or stop, the waiting caller is released at once and the lookup thread is interrupted; one that ignores the interrupt is abandoned, finishing in the background and counting toward the bound until it returns ([GossipDnsResolver.java:133-146](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L133-L146)).

**Verified by** the resolver and runtime tests, rewritten on an in-process lookup fixture with the same behaviours as the old fixture program: stall, wait for release, fail, empty reply, mixed addresses. The `localhost` load profile no longer asserts that sampling caught a lookup in flight, since in-process lookups finish in microseconds; its convergence through the hostname seed shows the lookups ran.

**Also fixed:** a concurrent request for the same host now shares the lookup already running ([GossipDnsResolver.java:81](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L81)).

## Medium impact

### 5. Full snapshots used as getters — fixed 2026-10-16

**Was:** `MembershipTable.snapshot()` builds a `MemberView` for every entry and copies the list, and it computed `assessment()` twice per entry. Several callers took a full snapshot only to read something cheap:

- the runtime, **inside the per-peer loop** of `round()`, just to read the local `serverId`;
- the runtime, on every exchange, for `selector.resolved()`, which usually returns at once because the peer isn't a configured seed;
- the transport, three times per outbound exchange, including once only to check for identity conflicts;
- the commands, to read `self()` and `serverId`.

**Now:**
- `MembershipTable` has [`localKey()`, `self()` and `identityConflicts()`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L292-L300), which read the local identity, the local record, or the conflict set without copying the table. `snapshot()` computes each entry's assessment once.
- `GossipRuntime` and `GossipCommands` keep the local `serverId` in a field ([GossipRuntime.java:100](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L100), [GossipCommands.java:51](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L51)).
- The exchange path passes the snapshot to `PeerSelector.resolved` as a supplier ([GossipRuntime.java:248-249](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L248-L249)). It is taken only when a configured seed resolves to a different address and its alias must be confirmed.
- The transport took one snapshot per outbound exchange instead of three ([GossipTransport.java:165, 174](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L165-L174)), and the commands one per inbound exchange instead of two. #11 later removed these too: exchanges now read only the table's records.

The snapshots that remain are in peer selection, `leave`, `members` and `status`; batch selection stopped using one in #11. The review suggested having the merge methods return the conflicts. A separate `identityConflicts()` was used instead, because `MergeResult` is a value type that tests compare directly.

**Verified by** the existing suites, which all pass, plus new tests. They check that `self()` and `localKey()` follow the local record through heartbeats and departure, that `identityConflicts()` matches the snapshot as conflicts appear and clear, and that alias confirmation takes a snapshot only for a configured seed whose address differs. **Measured:** an inbound exchange went from about 208 µs (after #1 and #2) to about 185 µs. The outbound path, which saw the largest reduction, is not covered by the benchmark.

### 6. The transport opens a `Selector` per connection, on virtual threads — fixed 2026-10-16

**Was:** every connection attempt opened a `Selector` (with its own wakeup channel) beside a non-blocking `SocketChannel`, and waited in an `await` loop, although exchanges run on virtual threads where plain blocking I/O is cheap.

**Now:** one blocking `Socket` per attempt ([GossipTransport.java:233-274](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L233-L274)). Connects and reads use socket timeouts; each read waits at most the read-idle limit and never past the total deadline ([GossipTransport.java:277-298](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L277-L298)). A blocking write has no timeout, so a small virtual-thread watchdog closes the socket once the total deadline passes. A socket closed by the watchdog, an interrupt or `close()` is reported as a timeout, an interruption or a closed transport, as before. The `Selector`, the `await` loop and the `Connection` wrapper are gone.

**Verified by** the existing transport tests, all passing unchanged: connect, read-idle and total deadlines, trickling peers, interruption, close cancellation, the address fallback and the byte limits. **Not covered by a test at first:** the write watchdog, because loopback buffers absorb a full 64 KB request, so a stalled write can't be produced reliably. A test with a stand-in socket was added later; see [Open items tested](#open-items-tested-2026-10-16).

**Limitation, fixed in the follow-up:** a caller on a platform thread was not woken from a blocked read by its interrupt, only by the deadlines, up to 15 s later. The watchdog now also closes the socket within 50 ms when such a caller is interrupted ([GossipTransport.java:240-251](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L240-L251)). The runtime's own workers are virtual threads.

**Measured:** outbound exchanges over loopback TCP with about 29 KB of records per reply went from about 3.7 ms to about 2.1 ms (three runs each).

### 7. Reading the reply one byte at a time — fixed 2026-10-16

**Was:** `readLine` copied the reply into a `ByteArrayOutputStream` one byte at a time. `ByteArrayOutputStream.write(int)` is `synchronized`, so a 64 KB reply meant 64K lock acquisitions, plus a final `toByteArray()` copy.

**Now:** [GossipTransport.java:277-298](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L277-L298) reads straight into one buffer of `max.exchange.bytes + 1` bytes and scans only the newly read bytes for the terminator. It decodes the line in place, without a copy. The spare byte keeps the limit exact: a reply of exactly the maximum length followed by its terminator is accepted, and one byte more is rejected.

**Verified by** the existing byte-limit, invalid-UTF-8, empty-reply and oversize tests. A new test sends the same boundary replies in 37-byte chunks, with each terminator (`\n`, `\r\n`, `\r`, or none with the peer closing), and with bytes after the terminator. Four deliberately introduced errors were each caught: a buffer one byte too small, keeping the bytes after the terminator, a missing over-limit check, and not treating `\r` as a terminator.

**Measured:** outbound exchanges over loopback TCP, with about 29 KB of records per reply, took about 4.3 ms with the old reader and about 3.7 ms with the new one, averaged over three alternating runs. That's roughly 15%, but the runs were noisy. The reader was a small share of the exchange; connection setup (#6) and decoding (#9) cost more.

### 8. Version fallback is never remembered — fixed 2026-10-16

**Was:** every exchange with a version-1 peer first sent version 2 and was rejected, so each one cost two TCP connections and two round trips.

**Now:**
- After a peer rejects version 2 and a full, identity-checked version-1 exchange with it succeeds, the transport remembers it and sends version 1 directly ([GossipTransport.java:139-140, 173](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L139-L140), [191-209](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L191-L209)).
- Entries are keyed by server ID and port. They expire after 10 minutes, so an upgraded peer gets version 2 again, and at most one entry is kept per member or seed.
- A failed version-1 retry is never remembered, so a stray or failed reply can't make a downgrade stick.

**The exact-message matching stays, now documented** in one constant ([GossipTransport.java:57-60](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L57-L60)). Version-1 peers are already deployed, and these two rejection messages are the only signal they send. A structured error code would only help peers that don't exist yet, because version-2 peers never reject version 2.

**Verified by** four new tests. Connection counts are exact, because the test peer fails if it receives more or fewer connections than expected. The tests check that only the first exchange with a version-1 peer pays for the rejected attempt, that the peer is offered version 2 again exactly at the recheck interval, that a failed version-1 retry isn't remembered, and that the memory stays within its bound. Four deliberately introduced errors were each caught: never remembering, remembering before the version-1 exchange succeeds, an off-by-one in the expiry, and no size limit.

**Effect:** an exchange with a version-1 peer now uses one connection and one round trip instead of two, except for the first exchange and one re-check every 10 minutes.

### 9. Each record's metadata is copied twice and round-tripped through JSON — fixed 2026-10-16

**Was:** `fromWire` deep-copied the metadata and the constructor copied it again, then encoded and re-parsed it. The same happened on every heartbeat and departure, although that metadata was already frozen.

**Now:** `fromWire` hands the metadata straight to the constructor, which copies and checks it once ([MemberRecord.java:113-125](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L113-L125)). A record built from an existing one, for a heartbeat, departure, local update or the widest-heartbeat stand-in, uses `with(heartbeat, state)`, which shares the already-checked metadata ([MemberRecord.java:89-103](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L89-L103)).

## Low impact and readability

- ~~**Exceptions used for normal control flow.**~~ Fixed 2026-10-16: `GossipValidation.literal` recognises a hostname by its characters before calling `InetAddress.ofLiteral`, so hostnames no longer cost an exception ([GossipValidation.java:46-58](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L46-L58)). It replaces the try/catch in `PeerSelector.normalized`, `PeerSelector.isSelf` and the resolver, with the same results.
- ~~**Regex compiled on every call.**~~ Fixed 2026-10-16: the cursor patterns are compiled once ([GossipCommands.java:32-33](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L32-L33)); the ID pattern, at first compiled once too, was later replaced by a character loop (#12, [GossipValidation.java:34-43](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L34-L43)). `validHost` now remembers its answer for up to 1,024 hosts, checking length before caching so network input cannot inflate the cache; it went from about 360 ns to about 17 ns per call.
- ~~**Convoluted recovery-fill loop.**~~ Fixed 2026-10-16: a bounded loop that relies on the cursor yielding each recovery peer once per cycle ([PeerSelector.java:92-95](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L92-L95)); the hand-written shuffle is now `Collections.shuffle`.
- ~~**Rotation that isn't actually fair (a correctness problem).**~~ Fixed 2026-10-16: both rotations now remember the member that is due next, not just its index ([GossipBatchSelector.java:55-57, 80](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L55-L57), [PeerSelector.java:99-105](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L99-L105)). If that member was removed, its successor has taken its index, so resuming there is still right. Two new tests reproduced the skipped member before the fix and pass after it.
- ~~**Clock inconsistency.**~~ Fixed 2026-10-16: departure timing uses the injected clock ([GossipRuntime.java:168, 207](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L207)).
- ~~**Duplicated self-detection logic.**~~ Fixed 2026-10-16: the transport now uses the shared `PeerSelector.isSelf` ([GossipTransport.java:132](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L132)).

## After the review: measured decoding costs (2026-10-16)

Profiling inbound exchanges with Java Flight Recorder showed that the metadata check, which writes each record's metadata to JSON and reads it back, was the largest single cost of an exchange, and that measuring each new record's size encoded the metadata a second time.

| Share of an inbound exchange | Small metadata | Large metadata |
|---|---|---|
| Metadata check: JSON read | 17% | 24% |
| Metadata check: JSON write | 10% | 13% |
| Measuring each new record's size | 29% | 15% |

A probe showed that, within the metadata limits (4 KB, depth 16), the read-back only ever rejects numbers of about 1,000 digits or more, the codec's default number-length limit.

**Changes** ([MemberRecord.java:267-277](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L267-L277), [284-298](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L284-L298)):
- The read-back runs only when the metadata holds a number of 100 characters or more. Anything that could reach the codec's limit is still checked by the real codec, so validation is unchanged unless the host's number limit is set below 100 characters.
- The metadata's byte count from that check is kept and added to the size of the record's other fields, so the metadata is encoded once; #10 later computed those other fields by arithmetic too.

**Verified by** two new tests: every accepted record reads back through the host codec across a sweep of integer and decimal lengths through the gate and the codec's limit, and `serializedBytes` equals the real encoding for built, derived and decoded records. Four deliberately introduced errors were each caught: a gate threshold far too high, the longest number never recorded, an off-by-one in the size arithmetic, and `with()` dropping the measured size.

**Measured** (inbound exchanges, profiled): small metadata 121 µs → 89 µs (−26%); large metadata 733 µs → 424 µs (−42%). The read-back no longer appears in the profile, and size measurement fell to 21% and 1.8%.

**Reply size check removed (2026-10-16).** Each inbound reply was encoded once just to confirm it fit, and the host then encoded it again to send it. The selector now gets exactly the space the empty reply leaves, using the reply's real admission value, so the reply fits by construction and is no longer encoded to check ([GossipCommands.java:102-108](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L102-L108)). A new test checks, across 1,202 replies in both protocol versions, that every reply fits, that its real size equals the arithmetic sum, and that some fill the limit exactly; a budget one byte too generous fails it. Measured back to back over three runs each, large-metadata exchanges took about 412 µs without the check and about 540 µs with it (about 24% faster); small-metadata exchanges went from 89 µs to 75 µs.

**Largest remaining costs:** the metadata deep copy (12% small, 41% large), a defensive copy of untrusted input worth keeping; the merge (15–16%); and, for small metadata, encoding each record's fixed fields to measure it. For production records, whose metadata is empty, that last cost was the largest of all until #10 removed it.

## Second pass: efficiency deep dive (2026-10-16)

Scope: all 16 main source files at commit `d25d08c`, looking for remaining inefficiency, weak algorithms, bloat and repeated work. Findings #10 to #13 were fixed the same day; see [Result](#result).

**How the fixes were tested.** Each fix was made test first. Where a fix added behaviour (#11), its tests were written first and failed before the code existed. The other fixes change no behaviour, so tests pinning that behaviour were written first and passed on the old code; deliberately introduced errors in the new code then showed that each test can fail.

**How this was measured.** In-process timings on JDK 27, taking the best of five passes after a warm-up pass, and a Java Flight Recorder profile of peer selection. Inbound exchanges carried 32 records (the sender and 31 members) against tables of 128 and 480 members. The times cover the plugin only, not the host's JSON handling or the network.

**Production records carry empty metadata.** Nothing in the plugin calls `MembershipTable.updateLocal`, so every node advertises metadata `{}`; the design intends metadata for things such as a node's supported instructions ([GOSSIP_PLUGIN.md](GOSSIP_PLUGIN.md)). In a swarm of these plugins every record is small, so fixed per-record costs dominate. Records with metadata were measured too. At the time of the review:

| Plugin time | 128 members | 480 members |
|---|---|---|
| Inbound exchange, empty metadata (as in production) | 34 µs | 61 µs |
| Inbound exchange, small metadata | 57 µs | 76 µs |
| Peer selection, once per round | 55 µs | 210 µs |

### 10. Each received record is encoded to JSON just to learn its size — fixed 2026-10-16

**Was:** `serializedBytes()` put the six fixed fields in a map and ran the JSON encoder on it. The result was cached, but every received record is a new object, so the encoder ran once for every record in every exchange, inbound and outbound: about 0.45–0.6 µs per record, roughly twice the 0.25 µs it takes to decode and validate an empty-metadata record. A 32-record exchange spent about 14–16 µs on it, roughly 40% of an empty-metadata inbound exchange at 128 members.

**Now:** each record computes its size from its field lengths when it is built, and `serializedBytes()` reads it ([MemberRecord.java:52-57](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L52-L57), [284-298](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L284-L298)). Every fixed field is ASCII that JSON never escapes: the server ID matches `[A-Za-z0-9._-]`, the boot ID is a canonical UUID, the port and heartbeat are integers, and the state is an enum name. Host validation accepts no character that JSON escapes or encodes in more than one byte; this was checked for every Unicode character in three positions in a host.

**Verified by** a new test that compares the size with the real encoding for every digit count of the port and heartbeat, every host form, the shortest and longest server IDs and both states, for records built directly and derived with `with()`. It passed on the old code. Four deliberately introduced errors were each caught: a fixed part one byte short, digit counting wrong at each power of ten (caught only by the new test), the state's length ignored, and `with()` keeping the base record's size.

### 11. Each exchange makes three passes over the whole table — fixed 2026-10-16

**Was:** an empty-metadata inbound exchange grew from 34 µs at 128 members to 61 µs at 480, although its batch is the same size. Three passes over the table caused this, and an outbound exchange made the same three:

- **A full snapshot where only the records were needed.** `snapshot()` assesses every entry, builds a conflict map and a `MemberView` per entry, then copies the list. The batch selector read only each member's record and the local record. At 480 members the snapshot took about 10 µs; a plain list of the records takes about 1 µs.
- **The batch selector rebuilt its index on every call.** It wrapped every member in a `Sized` record, walked that list again, then swept its map for removed keys: about 10 µs at 480 members. The separate sizing pass dated from when the selector measured sizes itself (#1).
- **The live-member recount on every merge.** The count is used only when a batch admits new members, and a stable swarm admits none: about 2–5 µs at 480 members.

**Now:**
- Both exchange directions choose their batch from `MembershipTable.peers()`: the table's records without the local one, with no assessments, views or copies ([MembershipTable.java:302-310](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L302-L310), [GossipCommands.java:102-108](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L102-L108), [GossipTransport.java:131-135](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L131-L135)).
- The selector refreshes its index in one pass, reads a size only for each record a batch considers, and sweeps for removed members only when the table has shrunk ([GossipBatchSelector.java:43-120](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L43-L120)). The `Sized` wrapper and the separate sizing pass are gone.
- The live-member count runs only when a batch admits new members ([MembershipTable.java:205-207](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L205-L207)).

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
- Server IDs are checked with a character loop, about 60 ns down to 4 ns, and `literal()` uses a plain loop instead of a stream ([GossipValidation.java:33-58](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L33-L58)). Every received record's server ID goes through the first, so decoding gains too.
- Peer selection parses the local host once per round and each candidate's host once, and normalising returns a seed already in normal form instead of validating a copy ([PeerSelector.java:57-76](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L57-L76), [169-196](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L169-L196)).

**Verified by** tests that pinned the behaviour first and passed on the old code: the ID check against the documented pattern for every UTF-16 character and the boundary lengths, `literal()` against the real address parser, and selection with addresses written differently. One deliberately introduced error, comparing each candidate with the local address instead of its own, was at first not caught: no test had a neighbour on the local port at another address, which is the normal deployment with every node on one port. The test now includes one. Nine deliberate errors were each caught.

### 13. The reply budget encodes an empty reply on every inbound exchange — fixed 2026-10-16

**Was:** `replyMemberBudget` encoded a complete empty reply, including the local record, for every inbound exchange: about 1 µs with the empty local metadata used in production, and about 10 µs with large local metadata.

**Now:** the empty reply's fixed overhead is measured once for each protocol version and admission value, and each budget adds the local record's size and server ID ([GossipWire.java:134-152](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L134-L152)), the way request sizes are already added up.

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

The host passes every plugin result through `Json.normalizeResult`, which encodes and then parses it, and then encodes the reply again to send it ([PluginRegistry.java:222](../src/main/java/dev/mars/plugin/PluginRegistry.java#L222)). Encoding a 31-record reply with small metadata takes about 20 µs, so this costs more than anything left inside the plugin. It belongs in a review of the host.

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
- **The host check #10 relies on is now a test.** #10 counts a host's characters as its JSON bytes, which is right only while validation accepts nothing JSON escapes and nothing UTF-8 encodes in more than one byte. That had been checked once by a separate program. A test now asserts it for every character in every position a host can take, including IPv6 scope IDs ([MemberRecordTest.java:159](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/MemberRecordTest.java#L159)); loosening validation to accept internationalised hostnames fails it.
- **A flaky test fixed.** `GossipDepartureTest` waited for two calls, then read two states that its client records just after each call, so the check could see only one ([GossipDepartureTest.java:254-256](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipDepartureTest.java#L254-L256)).
- **Interrupting a caller on a platform thread ends its exchange promptly.** Java does not wake a platform thread from blocking socket I/O when it is interrupted, so an embedding application that cancelled `exchangeWith` waited for the socket to time out, up to 15 s. The watchdog now also closes the socket within 50 ms of such an interrupt ([GossipTransport.java:240-251](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L240-L251)). The interrupt test runs on both kinds of thread; the platform case failed before the change.
- **The public API is intact.** `MembershipTable.mergeEncoded` and the public `GossipTransport` constructors were first removed as test-only, then restored when the design document turned out to offer them to callers that own a standalone table. `mergeEncoded` now also has a test that it rejects duplicate keys.

**Repeated work removed**
- **Host lookups take no lock.** Every decoded record and endpoint looked its host up in one synchronized map that reordered itself on every read. It is now a concurrent map that reads without locking and starts again when full ([GossipValidation.java:26-29](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L26-L29), [60-69](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L60-L69)); a test now checks its bound.
- **Empty metadata is not encoded to measure it** ([MemberRecord.java:268](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L268)): `{}` is 2 bytes, and writing it cost about half of building an empty-metadata record.
- **Boot IDs are checked without printing a copy** ([MemberKey.java:30-44](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberKey.java#L30-L44)); a test compares acceptance with parsing and printing back identically, for every position of three UUIDs replaced by every ASCII character.
- **Each record builds its wire map and its endpoint once** ([MemberRecord.java:127-143](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L127-L143), [159-164](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L159-L164)). Requests and replies carry the same records many times, and peer selection, departure and conflict logging built and validated a new `Seed` every time. The cached maps cost about 450 bytes per record, about 230 KB at 512 records.
- **The batch selector finds each member by position first** ([GossipBatchSelector.java:84-102](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L84-L102)): the table rarely reorders, so the hash lookup is now the exception, and a member whose record has not changed costs one reference comparison, without reading the record. `peers()` compares map keys by identity ([MembershipTable.java:302-310](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L302-L310)).
- **Checked, not changed:** profiles put about a fifth of an inbound exchange in `MemberKey.equals`. Writing it by hand only moved those samples: the cost was reading 480 entries from memory, which the shortcut above now mostly avoids. The hand-written version was removed again.
- **Each check once** ([GossipWire.java:69-71](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L69-L71), [MembershipTable.java:106-126](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L106-L126)): duplicate keys are rejected only by the table, which checks every batch it is given; the batch limit is checked once by the table and once by the decoder before it decodes anything, which bounds its work; an exchange's batch is copied once fewer. Requests and replies are each built as one map, with constant field-name sets ([GossipWire.java:89-106](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L89-L106)).
- **No boxed `Long` per exchange or round** ([GossipMetrics.java:36-40](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipMetrics.java#L36-L40), [76-77](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipMetrics.java#L76-L77)); a new test pins that both ages read as unknown until first set.
- **`isSelf` parses hosts only when the ports match**, as before #12 ([PeerSelector.java:186-189](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L186-L189)).
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
- **The plugin's threads ran with the host's context class loader.** The host calls each hook with the plugin's own class loader as the thread's context class loader, and the README says each invocation runs with it. The runtime replaced it on its round scheduler and exchange workers with the loader of the host's `CommandPlugin`, so a library on those threads that looks resources up through the context loader would not find the plugin's JAR. Both overrides are removed, and the threads inherit the loader the host set around `start()` ([GossipRuntime.java:115-116](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L115-L116), [219](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L219)). A new test starts the runtime from a thread with a marker loader and checks that an exchange runs with it ([GossipRuntimeTest.java:234](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipRuntimeTest.java#L234)). It failed before the change, and restoring either override fails it.
- **Exchanges did not wait for their watchdog.** Each exchange interrupted its watchdog on the way out but did not wait for it, so a watchdog could still be running after stop had joined every worker. The README requires a stop hook to join its workers before returning. An exchange now waits for its watchdog to end, keeping the caller's interrupt status ([GossipTransport.java:268-272](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L268-L272), [308-316](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L308-L316)), so stop, which joins the workers, waits for every watchdog too. A new test holds the watchdog of a platform caller inside its interrupt check and asserts that the exchange does not return until the watchdog is released, with and without an interrupted caller ([GossipTransportTest.java:669](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipTransportTest.java#L669)). It failed without the wait, and it also catches a wait that gives up when the caller is interrupted and one that loses the caller's interrupt status.

**Cost of the wait:** waking the watchdog and then the caller takes about 60 µs per exchange at the median and up to about 200 µs at p99, measured on its own on the development machine (Windows) after a 1 ms pause like an exchange's. An alternating benchmark of loopback exchanges, about 1.0–1.6 ms each, could not reliably separate it from run-to-run noise. Exchanges happen once per selected peer per round.

**Remaining exception:** a hostname lookup can outlive stop. A lookup blocked inside the platform resolver cannot be interrupted, so stop abandons its daemon thread, which finishes in the background. This is the trade-off agreed for #4, against the README's rule not to rely on daemon threads. Removing it needs a resolver that can be cancelled. Tests now pin how it behaves; see [Open items tested](#open-items-tested-2026-10-16).

## Open items tested (2026-10-16)

Both open items from the plugin interface check had behaviour that no test covered. Each new test was checked against deliberate errors: the error it targets fails it, while every other test still passes under that error, which shows the behaviour had been untested.

- **Abandoned hostname lookups.** The resolver tests' stuck lookup ended when interrupted, unlike a lookup inside the platform resolver, so nothing tested a lookup that ignores its interrupt. Two tests now use one ([GossipDnsResolverTest.java:138](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipDnsResolverTest.java#L138), [162](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipDnsResolverTest.java#L162)). Close releases the waiting caller at once and returns after its one-second wait, leaving the lookup to finish in the background. A lookup that timed out keeps its slot until it returns, which is what stops abandoned lookups from piling up. They catch three errors: close waiting without a limit, close releasing callers only after its wait, and a timed-out lookup giving up its slot.
- **The write watchdog (#6).** A real loopback socket could not stall the transport's write on the development machine. Even with 1-byte send and receive buffers, Windows accepted a whole 64 KiB write and one more write, and blocked only on the write after that; the transport makes exactly two, the request and its newline. A smaller send buffer would therefore not have helped. Instead, a new package-private constructor accepts the socket factory ([GossipTransport.java:100](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L100)), and a test supplies a socket whose writes block until it is closed, as a real write does once a peer that never reads has filled the buffers. The test checks that the exchange still ends at its deadline with a timeout ([GossipTransportTest.java:642](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipTransportTest.java#L642)). A watchdog that no longer closes the socket at the deadline passed every test before; it now fails this one.
- **What the Windows result means:** with requests capped at 64 KiB, the transport's write cannot block on Windows, so there the watchdog's deadline close never runs. It remains a safety net for systems whose socket buffers are smaller than a request; other systems were not measured.

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

The module's existing tests (`MembershipTableTest`, `GossipBatchSelectorTest`, `PeerSelectorTest`, `GossipTransportTest`, `GossipCommandsTest`, `GossipDnsResolverTest` and others) cover these areas and should be run after each change.
