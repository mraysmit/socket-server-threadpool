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

**Fixed:** #1 and #2. **Partly fixed:** #5. **Open:** everything else. Two related changes have also landed since the review:

- **Address fallback.** When a peer's hostname resolves to several addresses and connecting to one fails (refused or unreachable), the same exchange now tries the others. `GossipDnsResolver.resolveAll` returns every address, and `GossipTransport` and `GossipRuntime` use it. This affects how #4 should be fixed; see the note there.
- **Test support moved to the host.** Plugin tests now use the host's `dev.mars.testing` library. This changes no finding below.

## High impact

### 1. Re-encoding JSON only to check sizes — fixed 2026-10-16

**Was:** for every record in every exchange, `requireForwardable` built and encoded three complete envelopes: a request with the record as sender, a reply holding the record, and a members page holding the record. It also rebuilt a Base64 cursor that never changed, once per record. A reply carrying 31 records needed about 94 envelope encodings, roughly 10× the bytes actually sent. The same record was encoded again by `GossipWire.decode`'s request-size check, by `MembershipTable.checkRecordSize`, and by every call to `MemberRecord.serializedBytes()` or `fingerprint()`.

**Now:**
- [`MemberRecord`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L109-L126) is a final class that computes its encoded size and fingerprint once, then reuses them.
- [`GossipWire`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L123-L127) adds sizes up instead of encoding envelopes. A record's JSON is identical standalone or nested, so an envelope is a fixed overhead plus its records plus one comma between neighbours. Each overhead is measured once.
- [`requireForwardable`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L169-L184) needs no envelope encoding per record. Its two local-record overheads are recomputed only when the local record changes in more than its heartbeat.
- The request-size check in `decode` ([GossipWire.java:87](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L87)) no longer re-encodes the request.

**Verified by** `GossipSizeAccountingTest`. It keeps the original encode-everything algorithms as a reference and requires identical decisions, sweeping record sizes across every byte limit. It includes JSON-escaped and multi-byte metadata, and plain-ASCII sweeps that land on each exact limit. Six deliberately introduced one-byte errors were each caught.

**Measured:** an inbound exchange of 31 records into a 128-member table went from about 430 µs to about 208 µs. The remaining cost is mostly decoding records (#9), snapshots (#5) and encoding the actual reply.

**Follow-up:** `GossipBatchSelector` still keeps its own per-record size cache. That cache is now redundant and could be removed.

### 2. `members()` does quadratic work — fixed 2026-10-16

**Was:** each time a row was added, the code copied and encoded the entire page built so far. With 32 rows that was about 500 rows encoded per call, around 160 KB of JSON to produce a reply of about 30 KB.

**Now:** [GossipCommands.java:119-133](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L119-L133) measures the empty page once and each row once, and keeps a running total. The total accounts for the comma between rows and the exact width of the next-page cursor. Paging decisions are unchanged, and `GossipSizeAccountingTest` checks every page against the original row-by-row algorithm. The finished page is still encoded once as a final safety check. Measured: a 32-row page went from about 485 µs to about 88 µs.

### 3. Merging copies the whole table

[MembershipTable.java:149-150, 208](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L149-L150)

On every inbound and outbound merge, `apply` copies both `entries` and `suppressions` into new `LinkedHashMap`s, and counts the active members with a full stream over the table.

The copy exists for a good reason: if a conflict is thrown partway through a batch, the table must be left unchanged. That guarantee can be kept more cheaply with two passes:

1. Validate the batch and plan the changes in a small map sized to the batch, without mutating anything.
2. Commit the planned changes.

Keep an `activeCount` field updated as entries change, instead of recounting on every merge.

### 4. A whole JVM is started for each DNS lookup

[GossipDnsResolver.java:79, 125-133](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L125-L133)

Each hostname lookup runs `java -cp … GossipDnsLookup` as a separate process. That costs about 100 ms–1 s of startup and tens of MB of memory per lookup. Concurrent lookups of the same host each start their own process, because nothing lets them share a lookup that is already in progress.

The goal, being able to kill a lookup that blocks inside the platform resolver, is valid. Cheaper ways to get it:

- run `InetAddress.getAllByName` on a bounded pool of platform threads with a timeout, and abandon a lookup that gets stuck;
- or plug in a custom resolver through `InetAddressResolverProvider` (JEP 418).

Either way, have concurrent requests for the same host share one lookup.

**Note (2026-10-16):** the resolver now also supplies every address of a host for the connection fallback (`resolveAll`). Any replacement must keep returning all addresses, with the first one rotating between calls.

## Medium impact

### 5. Full snapshots used as getters

`MembershipTable.snapshot()` builds a `MemberView` for every entry and then copies the list with `List.copyOf`. It also calls `assessment()` twice per entry ([MembershipTable.java:299, 309](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L299)).

Several callers take a snapshot they don't need:

- [GossipRuntime.java:226](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L226) takes a full snapshot **inside the per-peer loop** of `round()`, just to read the local `serverId`, which never changes.
- [GossipRuntime.java:257-259](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L257-L259) builds `table.snapshot()` while holding the runtime lock and passes it to `selector.resolved()`. That method usually returns immediately because the peer isn't a configured seed. *Partly fixed:* the second snapshot, taken only to read the local port, is gone; the same snapshot is now reused for it. A full snapshot is still built on every exchange, though only the local port is usually needed.
- [GossipTransport.java:109, 142, 149](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L142) takes three snapshots per exchange. The last one is only used to check for identity conflicts.
- [GossipCommands.java:65, 71, 84, 99](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L84) uses snapshots to read `self()` and `serverId`.

**Fix:**
- Store the local `serverId` in a field.
- Add a cheap synchronized `MembershipTable.self()`.
- Have the merge methods return whether there are identity conflicts.
- In `snapshot()`, compute each entry's assessment once.

### 6. The transport opens a `Selector` per connection, on virtual threads

[GossipTransport.java:248-257, 292](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L292)

`Selector.open()` creates its own internal wakeup channel, so every exchange allocates extra OS handles on top of the socket. With the address fallback, an exchange can open one per address it tries. These exchanges already run on virtual threads, where plain blocking I/O is cheap.

**Fix:** use a blocking `SocketChannel` with timeouts and remove the `await` loop. Interrupting the thread still closes the channel, so cancellation keeps working.

### 7. Reading the reply one byte at a time

[GossipTransport.java:232-240](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L232-L240)

`readLine` copies the reply into a `ByteArrayOutputStream` one byte at a time. `ByteArrayOutputStream.write(int)` is `synchronized`, so a 64 KB reply means 64K lock acquisitions.

**Fix:** scan the read buffer for `\n` and write the whole slice in one call.

### 8. Version fallback is never remembered

[GossipTransport.java:118-135](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L118-L135)

Every exchange with a v1 peer first tries v2 and gets rejected. Each such exchange costs two TCP connections and two round trips.

**Fix:** remember the agreed version per peer. Detecting the rejection by matching exact error-message strings ([L130-131](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L130-L131)) is also fragile; a structured error code would be more reliable.

### 9. Each record's metadata is copied twice and round-tripped through JSON

[MemberRecord.java:62-63, 91-92](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L62-L63)

`fromWire` deep-copies the metadata, then the constructor deep-copies it again. The constructor then encodes and re-parses it with `Json.write` + `Json.read`. The same happens on every heartbeat in `MembershipTable.advanceLocal`, even though the metadata it passes in is already frozen.

**Fix:** skip the copy and the round trip when the metadata comes from an existing `MemberRecord`, for example through a private constructor.

## Low impact and readability

- **Exceptions used for normal control flow.** `InetAddress.ofLiteral` throws an exception, with a full stack trace, for every hostname that isn't an IP literal. It is called on every candidate every round: see [PeerSelector.java:170-182](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L170-L182) and [GossipDnsResolver.java:74](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L74). Normalize seed endpoints once when the config is loaded.
- **Regex compiled on every call.** `String.matches` in `GossipValidation.validId` compiles a new pattern for every incoming `MemberKey`, and `validHost` builds a `URI` for every record ([GossipValidation.java:25-40](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L25-L40)). The same applies to `decodeCursor` ([GossipCommands.java:178, 185](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L178)). Precompile the `Pattern`s.
- **Convoluted recovery-fill loop.** [PeerSelector.java:90-95](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L90-L95) calls `containsAll` twice per iteration. A bounded `for (i < recovery.size())` loop that adds each peer if it isn't already selected does the same thing.
- **Rotation that isn't actually fair (a correctness problem).** In `GossipBatchSelector`, `cursor` is an index into a `scan` list that is rebuilt from the snapshot on every call ([GossipBatchSelector.java:57, 77, 81-94](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L81-L94)). `PeerSelector.recoveryCursor` has the same problem against a changing `recovery` list ([PeerSelector.java:99-103](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L99-L103)). When members expire, the indexes shift and the rotation skips or repeats peers. Track the position by `MemberKey` instead of by index.
- **Clock inconsistency.** Departure timing uses `System.nanoTime()` instead of the injected `clock` ([GossipRuntime.java:176, 215](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L215)), which gets around the deterministic-clock hook used by tests.
- **Duplicated self-detection logic.** [GossipTransport.java:111](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L111) detects the local endpoint with an exact string compare, while `PeerSelector.isSelf` also handles letter case and IP literals. Use one shared check.

## Suggested order of work

1. ~~**#1 and #2 together:** store each record's encoded size, then compute envelope and page sizes by adding them up.~~ Done 2026-10-16.
2. **#5:** quick, low-risk cleanup of unneeded snapshots.
3. **#7, #8 and the low-impact items:** small, local changes.
4. **#3, #4 and #6:** larger refactors. Each needs its own change and careful testing of rollback, cancellation and timeout behaviour.

The module's existing tests (`MembershipTableTest`, `GossipBatchSelectorTest`, `PeerSelectorTest`, `GossipTransportTest`, `GossipCommandsTest`, `GossipDnsResolverTest` and others) cover these areas and should be run after each change.
