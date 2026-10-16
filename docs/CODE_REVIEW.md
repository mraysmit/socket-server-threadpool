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

**Fixed:** every finding, #1 to #9 and all six low-impact items, with one small part deliberately left (noted under #3). A later profiling pass cut decoding costs further; see [After the review](#after-the-review-measured-decoding-costs-2026-10-16). Two related changes have also landed since the review:

- **Address fallback.** When a peer's hostname resolves to several addresses and connecting to one fails (refused or unreachable), the same exchange now tries the others. `GossipDnsResolver.resolveAll` returns every address, and `GossipTransport` and `GossipRuntime` use it. This affects how #4 should be fixed; see the note there.
- **Test support moved to the host.** Plugin tests now use the host's `dev.mars.testing` library. This changes no finding below.

**Open:** #10 to #13, from a second pass that measured where the remaining time goes; see [Second pass](#second-pass-efficiency-deep-dive-2026-10-16).

## High impact

### 1. Re-encoding JSON only to check sizes — fixed 2026-10-16

**Was:** for every record in every exchange, `requireForwardable` built and encoded three complete envelopes: a request with the record as sender, a reply holding the record, and a members page holding the record. It also rebuilt a Base64 cursor that never changed, once per record. A reply carrying 31 records needed about 94 envelope encodings, roughly 10× the bytes actually sent. The same record was encoded again by `GossipWire.decode`'s request-size check, by `MembershipTable.checkRecordSize`, and by every call to `MemberRecord.serializedBytes()` or `fingerprint()`.

**Now:**
- [`MemberRecord`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L132-L149) is a final class that computes its encoded size and fingerprint once, then reuses them.
- [`GossipWire`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L119-L123) adds sizes up instead of encoding envelopes. A record's JSON is identical standalone or nested, so an envelope is a fixed overhead plus its records plus one comma between neighbours. Each overhead is measured once.
- [`requireForwardable`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L165-L180) needs no envelope encoding per record. Its two local-record overheads are recomputed only when the local record changes in more than its heartbeat.
- The request-size check in `decode` ([GossipWire.java:83](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L83)) no longer re-encodes the request.

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

**Not done:** the active-member count is still recomputed with one pass over the table per merge, rather than kept in a field. That pass allocates nothing, and keeping a counter in step across merges, expiry and departure would add more code than it saves. The second pass found a simpler option: count only when a batch admits new members (#11).

**Measured with #9:** an inbound exchange went from about 185 µs to about 166 µs.

### 4. A whole JVM is started for each DNS lookup — fixed 2026-10-16

**Was:** each hostname lookup ran `java -cp … GossipDnsLookup` as a separate process, about 130 ms of JVM startup and tens of MB per cold lookup, so that stop could kill a lookup blocked inside the platform resolver.

**Now:** lookups run in-process, each on its own short-lived daemon thread, with the same two-second timeout, `max.inflight` bound and 60-second cache ([GossipDnsResolver.java:74-113](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L74-L113)). `resolveAll` still returns every address with the first one rotating, as the connection fallback requires. The helper program `GossipDnsLookup` and its test fixture program are deleted.

**Trade-off, agreed before the change:** a lookup blocked inside the platform resolver can no longer be killed. On timeout or stop, the waiting caller is released at once and the lookup thread is interrupted; one that ignores the interrupt is abandoned, finishing in the background and counting toward the bound until it returns ([GossipDnsResolver.java:135-148](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L135-L148)).

**Verified by** the resolver and runtime tests, rewritten on an in-process lookup fixture with the same behaviours as the old fixture program: stall, wait for release, fail, empty reply, mixed addresses. The `localhost` load profile no longer asserts that sampling caught a lookup in flight, since in-process lookups finish in microseconds; its convergence through the hostname seed shows the lookups ran.

**Also fixed:** a concurrent request for the same host now shares the lookup already running ([GossipDnsResolver.java:83](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L83)).

## Medium impact

### 5. Full snapshots used as getters — fixed 2026-10-16

**Was:** `MembershipTable.snapshot()` builds a `MemberView` for every entry and copies the list, and it computed `assessment()` twice per entry. Several callers took a full snapshot only to read something cheap:

- the runtime, **inside the per-peer loop** of `round()`, just to read the local `serverId`;
- the runtime, on every exchange, for `selector.resolved()`, which usually returns at once because the peer isn't a configured seed;
- the transport, three times per outbound exchange, including once only to check for identity conflicts;
- the commands, to read `self()` and `serverId`.

**Now:**
- `MembershipTable` has [`localKey()`, `self()` and `identityConflicts()`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L290-L298), which read the local identity, the local record, or the conflict set without copying the table. `snapshot()` computes each entry's assessment once.
- `GossipRuntime` and `GossipCommands` keep the local `serverId` in a field ([GossipRuntime.java:101](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L101), [GossipCommands.java:51](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L51)).
- The exchange path passes the snapshot to `PeerSelector.resolved` as a supplier ([GossipRuntime.java:251-252](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L251-L252)). It is taken only when a configured seed resolves to a different address and its alias must be confirmed.
- The transport takes one snapshot per outbound exchange instead of three ([GossipTransport.java:157, 166](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L157-L166)). The commands take one per inbound exchange instead of two.

The snapshots that remain are the ones that need the member list: peer selection, batch selection, `leave`, `members` and `status`. The review suggested having the merge methods return the conflicts. A separate `identityConflicts()` was used instead, because `MergeResult` is a value type that tests compare directly.

**Verified by** the existing suites, which all pass, plus new tests. They check that `self()` and `localKey()` follow the local record through heartbeats and departure, that `identityConflicts()` matches the snapshot as conflicts appear and clear, and that alias confirmation takes a snapshot only for a configured seed whose address differs. **Measured:** an inbound exchange went from about 208 µs (after #1 and #2) to about 185 µs. The outbound path, which saw the largest reduction, is not covered by the benchmark.

### 6. The transport opens a `Selector` per connection, on virtual threads — fixed 2026-10-16

**Was:** every connection attempt opened a `Selector` (with its own wakeup channel) beside a non-blocking `SocketChannel`, and waited in an `await` loop, although exchanges run on virtual threads where plain blocking I/O is cheap.

**Now:** one blocking `Socket` per attempt ([GossipTransport.java:222-254](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L222-L254)). Connects and reads use socket timeouts; each read waits at most the read-idle limit and never past the total deadline ([GossipTransport.java:257-278](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L257-L278)). A blocking write has no timeout, so a small virtual-thread watchdog closes the socket once the total deadline passes. A socket closed by the watchdog, an interrupt or `close()` is reported as a timeout, an interruption or a closed transport, as before. The `Selector`, the `await` loop and the `Connection` wrapper are gone.

**Verified by** the existing transport tests, all passing unchanged: connect, read-idle and total deadlines, trickling peers, interruption, close cancellation, the address fallback and the byte limits. **Not covered by a test:** the write watchdog, because loopback buffers absorb a full 64 KB request, so a stalled write can't be produced reliably.

**Limitation:** a caller on a platform thread is not interrupted out of a blocked read, but stays bounded by the deadlines. The runtime's workers are virtual threads.

**Measured:** outbound exchanges over loopback TCP with about 29 KB of records per reply went from about 3.7 ms to about 2.1 ms (three runs each).

### 7. Reading the reply one byte at a time — fixed 2026-10-16

**Was:** `readLine` copied the reply into a `ByteArrayOutputStream` one byte at a time. `ByteArrayOutputStream.write(int)` is `synchronized`, so a 64 KB reply meant 64K lock acquisitions, plus a final `toByteArray()` copy.

**Now:** [GossipTransport.java:257-278](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L257-L278) reads straight into one buffer of `max.exchange.bytes + 1` bytes and scans only the newly read bytes for the terminator. It decodes the line in place, without a copy. The spare byte keeps the limit exact: a reply of exactly the maximum length followed by its terminator is accepted, and one byte more is rejected.

**Verified by** the existing byte-limit, invalid-UTF-8, empty-reply and oversize tests. A new test sends the same boundary replies in 37-byte chunks, with each terminator (`\n`, `\r\n`, `\r`, or none with the peer closing), and with bytes after the terminator. Four deliberately introduced errors were each caught: a buffer one byte too small, keeping the bytes after the terminator, a missing over-limit check, and not treating `\r` as a terminator.

**Measured:** outbound exchanges over loopback TCP, with about 29 KB of records per reply, took about 4.3 ms with the old reader and about 3.7 ms with the new one, averaged over three alternating runs. That's roughly 15%, but the runs were noisy. The reader was a small share of the exchange; connection setup (#6) and decoding (#9) cost more.

### 8. Version fallback is never remembered — fixed 2026-10-16

**Was:** every exchange with a version-1 peer first sent version 2 and was rejected, so each one cost two TCP connections and two round trips.

**Now:**
- After a peer rejects version 2 and a full, identity-checked version-1 exchange with it succeeds, the transport remembers it and sends version 1 directly ([GossipTransport.java:131-132, 165](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L131-L132), [183-201](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L183-L201)).
- Entries are keyed by server ID and port. They expire after 10 minutes, so an upgraded peer gets version 2 again, and at most one entry is kept per member or seed.
- A failed version-1 retry is never remembered, so a stray or failed reply can't make a downgrade stick.

**The exact-message matching stays, now documented** in one constant ([GossipTransport.java:56-59](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L56-L59)). Version-1 peers are already deployed, and these two rejection messages are the only signal they send. A structured error code would only help peers that don't exist yet, because version-2 peers never reject version 2.

**Verified by** four new tests. Connection counts are exact, because the test peer fails if it receives more or fewer connections than expected. The tests check that only the first exchange with a version-1 peer pays for the rejected attempt, that the peer is offered version 2 again exactly at the recheck interval, that a failed version-1 retry isn't remembered, and that the memory stays within its bound. Four deliberately introduced errors were each caught: never remembering, remembering before the version-1 exchange succeeds, an off-by-one in the expiry, and no size limit.

**Effect:** an exchange with a version-1 peer now uses one connection and one round trip instead of two, except for the first exchange and one re-check every 10 minutes.

### 9. Each record's metadata is copied twice and round-tripped through JSON — fixed 2026-10-16

**Was:** `fromWire` deep-copied the metadata and the constructor copied it again, then encoded and re-parsed it. The same happened on every heartbeat and departure, although that metadata was already frozen.

**Now:** `fromWire` hands the metadata straight to the constructor, which copies and checks it once ([MemberRecord.java:103-114](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L103-L114)). A record built from an existing one, for a heartbeat, departure, local update or the widest-heartbeat stand-in, uses `with(heartbeat, state)`, which shares the already-checked metadata ([MemberRecord.java:80-93](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L80-L93)).

## Low impact and readability

- ~~**Exceptions used for normal control flow.**~~ Fixed 2026-10-16: `GossipValidation.literal` recognises a hostname by its characters before calling `InetAddress.ofLiteral`, so hostnames no longer cost an exception ([GossipValidation.java:41-45](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L41-L45)). It replaces the try/catch in `PeerSelector.normalized`, `PeerSelector.isSelf` and the resolver, with the same results.
- ~~**Regex compiled on every call.**~~ Fixed 2026-10-16: the ID and cursor patterns are compiled once ([GossipValidation.java:28](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L28), [GossipCommands.java:32-33](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L32-L33)). `validHost` now remembers its answer for up to 1,024 hosts, checking length before caching so network input cannot inflate the cache; it went from about 360 ns to about 17 ns per call.
- ~~**Convoluted recovery-fill loop.**~~ Fixed 2026-10-16: a bounded loop that relies on the cursor yielding each recovery peer once per cycle ([PeerSelector.java:88-91](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L88-L91)); the hand-written shuffle is now `Collections.shuffle`.
- ~~**Rotation that isn't actually fair (a correctness problem).**~~ Fixed 2026-10-16: both rotations now remember the member that is due next, not just its index ([GossipBatchSelector.java:64-65, 86](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L64-L65), [PeerSelector.java:95-101](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L95-L101)). If that member was removed, its successor has taken its index, so resuming there is still right. Two new tests reproduced the skipped member before the fix and pass after it.
- ~~**Clock inconsistency.**~~ Fixed 2026-10-16: departure timing uses the injected clock ([GossipRuntime.java:170, 209](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L209)).
- ~~**Duplicated self-detection logic.**~~ Fixed 2026-10-16: the transport now uses the shared `PeerSelector.isSelf` ([GossipTransport.java:124](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L124)).

## After the review: measured decoding costs (2026-10-16)

Profiling inbound exchanges with Java Flight Recorder showed that the metadata check, which writes each record's metadata to JSON and reads it back, was the largest single cost of an exchange, and that measuring each new record's size encoded the metadata a second time.

| Share of an inbound exchange | Small metadata | Large metadata |
|---|---|---|
| Metadata check: JSON read | 17% | 24% |
| Metadata check: JSON write | 10% | 13% |
| Measuring each new record's size | 29% | 15% |

A probe showed that, within the metadata limits (4 KB, depth 16), the read-back only ever rejects numbers of about 1,000 digits or more, the codec's default number-length limit.

**Changes** ([MemberRecord.java:252-261](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L252-L261), [132-138](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L132-L138)):
- The read-back runs only when the metadata holds a number of 100 characters or more. Anything that could reach the codec's limit is still checked by the real codec, so validation is unchanged unless the host's number limit is set below 100 characters.
- The metadata's byte count from that check is kept, and a record's size is computed as the record with empty metadata plus that count, so the metadata is encoded once.

**Verified by** two new tests: every accepted record reads back through the host codec across a sweep of integer and decimal lengths through the gate and the codec's limit, and `serializedBytes` equals the real encoding for built, derived and decoded records. Four deliberately introduced errors were each caught: a gate threshold far too high, the longest number never recorded, an off-by-one in the size arithmetic, and `with()` dropping the measured size.

**Measured** (inbound exchanges, profiled): small metadata 121 µs → 89 µs (−26%); large metadata 733 µs → 424 µs (−42%). The read-back no longer appears in the profile, and size measurement fell to 21% and 1.8%.

**Reply size check removed (2026-10-16).** Each inbound reply was encoded once just to confirm it fit, and the host then encoded it again to send it. The selector now gets exactly the space the empty reply leaves, using the reply's real admission value, so the reply fits by construction and is no longer encoded to check ([GossipCommands.java:102-108](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L102-L108)). A new test checks, across 1,202 replies in both protocol versions, that every reply fits, that its real size equals the arithmetic sum, and that some fill the limit exactly; a budget one byte too generous fails it. Measured back to back over three runs each, large-metadata exchanges took about 412 µs without the check and about 540 µs with it (about 24% faster); small-metadata exchanges went from 89 µs to 75 µs.

**Largest remaining costs:** the metadata deep copy (12% small, 41% large), a defensive copy of untrusted input worth keeping; the merge (15–16%); and, for small metadata, encoding each record's fixed fields to measure it. For production records, whose metadata is empty, that last cost is the largest of all; see #10.

## Second pass: efficiency deep dive (2026-10-16)

Scope: all 16 main source files at commit `d25d08c`, looking for remaining inefficiency, weak algorithms, bloat and repeated work. Findings #10 to #13 are open; none of the changes below has been made yet.

**How this was measured.** In-process timings on JDK 27, taking the best of five passes after a warm-up pass, and a Java Flight Recorder profile of peer selection. Inbound exchanges carried 32 records (the sender and 31 members) against tables of 128 and 480 members. The times cover the plugin only, not the host's JSON handling or the network.

**Production records carry empty metadata.** Nothing in the plugin calls `MembershipTable.updateLocal`, so every node advertises metadata `{}`; the design intends metadata for things such as a node's supported instructions ([GOSSIP_PLUGIN.md](GOSSIP_PLUGIN.md)). In a swarm of these plugins every record is small, so fixed per-record costs dominate. Records with metadata were measured too.

| Plugin time | 128 members | 480 members |
|---|---|---|
| Inbound exchange, empty metadata (as in production) | 34 µs | 61 µs |
| Inbound exchange, small metadata | 57 µs | 76 µs |
| Peer selection, once per round | 55 µs | 210 µs |

### 10. Each received record is encoded to JSON just to learn its size — open

`serializedBytes()` puts the six fixed fields in a map and runs the JSON encoder on it ([MemberRecord.java:132-138](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L132-L138)). The result is cached, but every received record is a new object, so the encoder runs once for every record in every exchange, inbound and outbound.

**Measured:** about 0.45–0.6 µs per record, roughly twice the 0.25 µs it takes to decode and validate an empty-metadata record. A 32-record exchange spends about 14–16 µs on it, roughly 40% of an empty-metadata inbound exchange at 128 members.

**Suggested fix:** add up the field lengths instead. Every fixed field is ASCII that JSON never escapes: the server ID matches `[A-Za-z0-9._-]`, the boot ID is a canonical UUID, the port and heartbeat are integers, and the state is an enum name. Host validation accepts no character that JSON escapes or encodes in more than one byte; this was checked for every Unicode character in three positions in a host. A prototype took 8 ns and matched the real encoding for IPv4 and IPv6 hosts, both states, the largest heartbeat, and empty, small and large metadata.

**Risk:** the arithmetic fixes the JSON layout of these fields. A test comparing it with the real encoder across field widths would catch any drift, as `GossipSizeAccountingTest` does for envelopes.

### 11. Each exchange makes three passes over the whole table — open

An empty-metadata inbound exchange grows from 34 µs at 128 members to 61 µs at 480, although its batch is the same size. Three passes over the table cause this, and an outbound exchange makes the same three:

- **A full snapshot where only the records are needed** ([GossipCommands.java:102](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L102), [GossipTransport.java:123](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L123)). `snapshot()` assesses every entry, builds a conflict map and a `MemberView` per entry, then copies the list ([MembershipTable.java:300-315](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L300-L315), [47-50](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L47-L50)). The batch selector reads only each member's record and the local record. At 480 members the snapshot takes about 10 µs; a plain list of the records takes about 1 µs.
- **The batch selector rebuilds its index on every call** ([GossipBatchSelector.java:43-115](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L43-L115)). It wraps every member in a `Sized` record, walks that list again in `refresh`, then sweeps its map for removed keys: about 10 µs at 480 members. The separate sizing pass dates from when the selector measured sizes itself (#1); records now cache their size, so one pass would do.
- **The live-member recount on every merge** ([MembershipTable.java:205](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L205)). The count is used only when a batch admits new members ([MembershipTable.java:210-224](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L210-L224)), and a stable swarm admits none. Counting only in that case removes the pass without the counter field that #3 rejected. It costs about 2–5 µs at 480 members.

**Suggested fix:** a records-only view of the table for the two exchange paths, a single-pass selector, and the conditional count. Peer selection, `leave`, `members` and `status` need the full snapshot and keep it.

### 12. Peer selection re-validates data that is already valid — open

`PeerSelector.select` is the most expensive single call in the plugin ([PeerSelector.java:51-93](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L51-L93), [169-186](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L169-L186)). Profiled at 480 members:

| Share of `PeerSelector.select` | |
|---|---|
| Building two `Seed` objects per member, each re-running validation | 58% |
| …of which the server ID regex alone | 44% |
| `isSelf` parsing the local host again for every candidate | 19% |
| The stream-based character check in `literal()` | 13% |
| Shuffling the candidates | 0% |

It runs once per round (every second by default), so even at 480 members it uses about 0.02% of a core. The fixes are small, and the first also speeds up decoding:

- Check server IDs with a character loop instead of a regex ([GossipValidation.java:28-38](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L28-L38)): about 60 ns down to about 4 ns. Every received record's server ID goes through this check, so it also saves about 2 µs per 32-record exchange.
- Use a plain loop instead of a stream in `literal()` ([GossipValidation.java:42](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L42)): about 11 ns down to 1–2 ns.
- Parse the local host once, and each member's address once.

### 13. The reply budget encodes an empty reply on every inbound exchange — open

`replyMemberBudget` encodes a complete empty reply, including the local record, for every inbound exchange ([GossipWire.java:145-147](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L145-L147)). This costs about 1 µs with the empty local metadata used in production, and about 10 µs with large local metadata. It could be added up the way request sizes already are: a cached overhead for each protocol version and admission value (four in all), plus the local record's cached size.

### Bloat and repeated checks

These cost little at run time; the cost is in reading the code.

- **Repeated checks on each inbound exchange.** Duplicate keys are checked twice ([GossipWire.java:68-75](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L68-L75), [MembershipTable.java:119-123](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L119-L123)), the batch limit four times ([GossipWire.java:64](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L64), [MembershipTable.java:108-118](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L108-L118)), and each record's size twice. The member list is copied four times. The table's checks protect its public methods and the wire checks protect the protocol, so some of the overlap is deliberate.
- **Maps built three times.** `GossipWire.parameters` and the version-2 `GossipWire.result` build each map three times: `Map.of`, then a `LinkedHashMap`, then `Map.copyOf` ([GossipWire.java:93-112](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L93-L112)). That costs about 0.2 µs per reply; most of the time spent building a reply goes to each record's `toWire()`. Building one map is a clarity fix.
- **About 30 lines used only by tests.** `MembershipTable.mergeEncoded`, the six-argument `Snapshot` constructor, the two-argument `Exchange` constructor, `PeerSelector.success(Seed)` and `PeerSelector.resolved(…, Snapshot)`, `GossipDnsResolver.resolve`, and four of the five `GossipTransport` constructors. Removing them means rewriting the tests that call them.

### Checked and not worth changing

- **Per-connection costs in the transport:** a watchdog thread, a fresh read buffer and a new decoder for each exchange. These are estimated at microseconds, against a loopback round trip of about 2 ms (#6).
- **`identityConflicts()` after each outbound exchange:** 1–4 µs, also small against the network.
- **The `members` command:** it still encodes each row, and a cursor per row, to size the page, but only operators call it.
- **Copying and comparing metadata:** needed because metadata comes from untrusted peers.
- **Expiry once per round (`maintain()`):** 0.3–1.8 µs.

### Outside the plugin

The host passes every plugin result through `Json.normalizeResult`, which encodes and then parses it, and then encodes the reply again to send it ([PluginRegistry.java:222](../src/main/java/dev/mars/plugin/PluginRegistry.java#L222)). Encoding a 31-record reply with small metadata takes about 20 µs, so this costs more than anything left inside the plugin. It belongs in a review of the host.

### Expected effect

Estimated from the measured parts, #10 to #13 together would take an empty-metadata inbound exchange from 34 µs to about 14 µs at 128 members and from 61 µs to about 27 µs at 480, and make peer selection two to three times faster. Each change should be measured before and after, as the first review's changes were.

## Suggested order of work

1. ~~**#1 and #2 together:** store each record's encoded size, then compute envelope and page sizes by adding them up.~~ Done 2026-10-16.
2. ~~**#5:** quick, low-risk cleanup of unneeded snapshots.~~ Done 2026-10-16.
3. ~~**#7, #8 and the low-impact items:** small, local changes.~~ Done 2026-10-16.
4. ~~**#3, #4 and #6:** larger refactors. Each needs its own change and careful testing of rollback, cancellation and timeout behaviour.~~ Done 2026-10-16.
5. **#10:** the largest gain, in one method and its size test.
6. **The recount in #11:** a one-line change.
7. **#12:** small, local changes to validation and peer selection.
8. **The rest of #11:** changes the batch selector and the table's interface.
9. **#13:** a small change in `GossipWire`.

The module's existing tests (`MembershipTableTest`, `GossipBatchSelectorTest`, `PeerSelectorTest`, `GossipTransportTest`, `GossipCommandsTest`, `GossipDnsResolverTest` and others) cover these areas and should be run after each change.
