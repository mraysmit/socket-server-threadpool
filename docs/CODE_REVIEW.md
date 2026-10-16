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

**Fixed:** #1, #2, #4, #5, #6, #7 and #8, plus two low-impact items (the recovery loop and the duplicated self-detection). **Partly fixed:** #9. **Open:** everything else. Two related changes have also landed since the review:

- **Address fallback.** When a peer's hostname resolves to several addresses and connecting to one fails (refused or unreachable), the same exchange now tries the others. `GossipDnsResolver.resolveAll` returns every address, and `GossipTransport` and `GossipRuntime` use it. This affects how #4 should be fixed; see the note there.
- **Test support moved to the host.** Plugin tests now use the host's `dev.mars.testing` library. This changes no finding below.

## High impact

### 1. Re-encoding JSON only to check sizes — fixed 2026-10-16

**Was:** for every record in every exchange, `requireForwardable` built and encoded three complete envelopes: a request with the record as sender, a reply holding the record, and a members page holding the record. It also rebuilt a Base64 cursor that never changed, once per record. A reply carrying 31 records needed about 94 envelope encodings, roughly 10× the bytes actually sent. The same record was encoded again by `GossipWire.decode`'s request-size check, by `MembershipTable.checkRecordSize`, and by every call to `MemberRecord.serializedBytes()` or `fingerprint()`.

**Now:**
- [`MemberRecord`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L107-L124) is a final class that computes its encoded size and fingerprint once, then reuses them.
- [`GossipWire`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L119-L123) adds sizes up instead of encoding envelopes. A record's JSON is identical standalone or nested, so an envelope is a fixed overhead plus its records plus one comma between neighbours. Each overhead is measured once.
- [`requireForwardable`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L165-L180) needs no envelope encoding per record. Its two local-record overheads are recomputed only when the local record changes in more than its heartbeat.
- The request-size check in `decode` ([GossipWire.java:83](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L83)) no longer re-encodes the request.

**Verified by** `GossipSizeAccountingTest`. It keeps the original encode-everything algorithms as a reference and requires identical decisions, sweeping record sizes across every byte limit. It includes JSON-escaped and multi-byte metadata, and plain-ASCII sweeps that land on each exact limit. Six deliberately introduced one-byte errors were each caught.

**Measured:** an inbound exchange of 31 records into a 128-member table went from about 430 µs to about 208 µs. The remaining cost is mostly decoding records (#9), snapshots (#5) and encoding the actual reply.

**Follow-up, done 2026-10-16:** `GossipBatchSelector`'s own per-record size cache, redundant once `MemberRecord` cached its size, was removed (about 35 lines).

### 2. `members()` does quadratic work — fixed 2026-10-16

**Was:** each time a row was added, the code copied and encoded the entire page built so far. With 32 rows that was about 500 rows encoded per call, around 160 KB of JSON to produce a reply of about 30 KB.

**Now:** [GossipCommands.java:119-133](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L119-L133) measures the empty page once and each row once, and keeps a running total. The total accounts for the comma between rows and the exact width of the next-page cursor. Paging decisions are unchanged, and `GossipSizeAccountingTest` checks every page against the original row-by-row algorithm. The finished page is still encoded once as a final safety check. Measured: a 32-row page went from about 485 µs to about 88 µs.

### 3. Merging copies the whole table

[MembershipTable.java:146-147, 205](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L146-L147)

On every inbound and outbound merge, `apply` copies both `entries` and `suppressions` into new `LinkedHashMap`s, and counts the active members with a full stream over the table.

The copy exists for a good reason: if a conflict is thrown partway through a batch, the table must be left unchanged. That guarantee can be kept more cheaply with two passes:

1. Validate the batch and plan the changes in a small map sized to the batch, without mutating anything.
2. Commit the planned changes.

Keep an `activeCount` field updated as entries change, instead of recounting on every merge.

### 4. A whole JVM is started for each DNS lookup — fixed 2026-10-16

**Was:** each hostname lookup ran `java -cp … GossipDnsLookup` as a separate process, about 130 ms of JVM startup and tens of MB per cold lookup, so that stop could kill a lookup blocked inside the platform resolver.

**Now:** lookups run in-process, each on its own short-lived daemon thread, with the same two-second timeout, `max.inflight` bound and 60-second cache ([GossipDnsResolver.java:73-109](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L73-L109)). `resolveAll` still returns every address with the first one rotating, as the connection fallback requires. The helper program `GossipDnsLookup` and its test fixture program are deleted.

**Trade-off, agreed before the change:** a lookup blocked inside the platform resolver can no longer be killed. On timeout or stop, the waiting caller is released at once and the lookup thread is interrupted; one that ignores the interrupt is abandoned, finishing in the background and counting toward the bound until it returns ([GossipDnsResolver.java:131-144](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L131-L144)).

**Verified by** the resolver and runtime tests, rewritten on an in-process lookup fixture with the same behaviours as the old fixture program: stall, wait for release, fail, empty reply, mixed addresses. The `localhost` load profile no longer asserts that sampling caught a lookup in flight, since in-process lookups finish in microseconds; its convergence through the hostname seed shows the lookups ran.

**Still open:** concurrent lookups of the same host do not share one lookup in progress.

## Medium impact

### 5. Full snapshots used as getters — fixed 2026-10-16

**Was:** `MembershipTable.snapshot()` builds a `MemberView` for every entry and copies the list, and it computed `assessment()` twice per entry. Several callers took a full snapshot only to read something cheap:

- the runtime, **inside the per-peer loop** of `round()`, just to read the local `serverId`;
- the runtime, on every exchange, for `selector.resolved()`, which usually returns at once because the peer isn't a configured seed;
- the transport, three times per outbound exchange, including once only to check for identity conflicts;
- the commands, to read `self()` and `serverId`.

**Now:**
- `MembershipTable` has [`localKey()`, `self()` and `identityConflicts()`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L293-L301), which read the local identity, the local record, or the conflict set without copying the table. `snapshot()` computes each entry's assessment once.
- `GossipRuntime` and `GossipCommands` keep the local `serverId` in a field ([GossipRuntime.java:101](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L101), [GossipCommands.java:48](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L48)).
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

### 9. Each record's metadata is copied twice and round-tripped through JSON — partly fixed 2026-10-16

[MemberRecord.java:62-63](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L62-L63)

`fromWire` deep-copies the metadata, then the constructor deep-copies it again. The constructor then encodes and re-parses it with `Json.write` + `Json.read`. The same happens on every heartbeat in `MembershipTable.advanceLocal`, even though the metadata it passes in is already frozen.

**Fix:** skip the copy and the round trip when the metadata comes from an existing `MemberRecord`, for example through a private constructor.

**Partly fixed:** `fromWire` no longer copies the metadata before the constructor copies and checks it ([MemberRecord.java:80-91](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L80-L91)). Records built from an existing record, such as each heartbeat, still copy and round-trip already-frozen metadata.

## Low impact and readability

- **Exceptions used for normal control flow.** `InetAddress.ofLiteral` throws an exception, with a full stack trace, for every hostname that isn't an IP literal. It is called on every candidate every round: see [PeerSelector.java:172-184](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L172-L184) and [GossipDnsResolver.java:79](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L79). Normalize seed endpoints once when the config is loaded.
- **Regex compiled on every call.** `String.matches` in `GossipValidation.validId` compiles a new pattern for every incoming `MemberKey`, and `validHost` builds a `URI` for every record ([GossipValidation.java:25-40](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L25-L40)). The same applies to `decodeCursor` ([GossipCommands.java:178, 185](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L178)). Precompile the `Pattern`s.
- ~~**Convoluted recovery-fill loop.**~~ Fixed 2026-10-16: a bounded loop that relies on the cursor yielding each recovery peer once per cycle ([PeerSelector.java:88-91](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L88-L91)); the hand-written shuffle is now `Collections.shuffle`.
- **Rotation that isn't actually fair (a correctness problem).** In `GossipBatchSelector`, `cursor` is an index into a `scan` list that is rebuilt from the snapshot on every call ([GossipBatchSelector.java:62, 82, 86-111](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L86-L111)). `PeerSelector.recoveryCursor` has the same problem against a changing `recovery` list ([PeerSelector.java:95-99](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L95-L99)). When members expire, the indexes shift and the rotation skips or repeats peers. Track the position by `MemberKey` instead of by index.
- **Clock inconsistency.** Departure timing uses `System.nanoTime()` instead of the injected `clock` ([GossipRuntime.java:170, 209](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L209)), which gets around the deterministic-clock hook used by tests.
- ~~**Duplicated self-detection logic.**~~ Fixed 2026-10-16: the transport now uses the shared `PeerSelector.isSelf` ([GossipTransport.java:124](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L124)).

## Suggested order of work

1. ~~**#1 and #2 together:** store each record's encoded size, then compute envelope and page sizes by adding them up.~~ Done 2026-10-16.
2. ~~**#5:** quick, low-risk cleanup of unneeded snapshots.~~ Done 2026-10-16.
3. **#7, #8 and the low-impact items:** small, local changes. #7 and #8 done 2026-10-16.
4. **#3, #4 and #6:** larger refactors. Each needs its own change and careful testing of rollback, cancellation and timeout behaviour. #4 and #6 done 2026-10-16.

The module's existing tests (`MembershipTableTest`, `GossipBatchSelectorTest`, `PeerSelectorTest`, `GossipTransportTest`, `GossipCommandsTest`, `GossipDnsResolverTest` and others) cover these areas and should be run after each change.
