# Gossip Plugin Code Review

Scope: all 14 main source files in [plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/), at commit `e8a6675`. Focus: efficiency, plus code that is hard to follow or subtly wrong.

## Summary

The protocol logic is careful and mostly correct: version checks, conflict detection and tombstones all hold up. The inefficiency comes mainly from three repeated habits, not from bad core algorithms:

1. **Data is encoded to JSON just to measure its size.** Each incoming record is JSON-encoded about 7–8 times per hop.
2. **`MembershipTable.snapshot()` is used like a getter.** It copies the whole table every time, and some callers only want one field that never changes.
3. **Every merge copies the whole table**, even though a batch holds at most 32 records.

The config caps (`max.records`, a 64 KB `max.exchange.bytes`) keep most of this to wasted CPU and memory churn rather than unbounded growth. The exceptions are `members()`, which does quadratic work, and the DNS resolver, which starts a JVM per lookup.

Line numbers refer to commit `e8a6675`.

## High impact

### 1. Re-encoding JSON only to check sizes

[GossipWire.java:143-163](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L143-L163)

For every record in every exchange, `requireForwardable` builds and encodes three complete envelopes:

- a request with the record as sender ([L148](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L148));
- a reply holding the record ([L152](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L152));
- a members page holding the record ([L159](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L159)).

It also rebuilds the Base64 cursor inside the loop ([L157-158](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L157-L158)), although the cursor never changes. A reply carrying 31 records therefore needs about 94 envelope encodings, roughly 10× the bytes actually sent.

The same record is encoded again elsewhere:

- `GossipWire.decode` re-encodes the request it has just parsed, only to check its size ([GossipWire.java:82](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java#L82)).
- `MembershipTable.validateBatch` → `checkRecordSize` encodes every record again ([MembershipTable.java:125, 137-141](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L137-L141)).
- `MemberRecord.serializedBytes()` and `fingerprint()` recompute the encoding on every call ([MemberRecord.java:83-89](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L83-L89)).

**Fix:**
- Turn `MemberRecord` into a final class that computes its encoded size the first time it is needed and stores it. A Java `record` cannot hold a cached field like this.
- Envelope sizes add up: a full envelope is a fixed overhead plus the size of each record, plus one comma between records. Each check then becomes `fixedOverhead + recordBytes <= max.exchange.bytes`.
- Compute each fixed overhead (request, reply and members page) once per local heartbeat, not once per record.

### 2. `members()` does quadratic work

[GossipCommands.java:116-126](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L116-L126)

Each time a row is added, the code copies and encodes the entire page built so far (`replyFits(pageResult(...))`). With 32 rows that is about 500 rows encoded per call, around 160 KB of JSON to produce a reply of about 30 KB.

**Fix:** measure each row once and add `rowBytes + 1` to a running total. Reserve space for the longest possible cursor up front, so the page never needs to be re-encoded while it is being filled.

### 3. Merging copies the whole table

[MembershipTable.java:149-150, 208](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L149-L150)

On every inbound and outbound merge, `apply` copies both `entries` and `suppressions` into new `LinkedHashMap`s, and counts the active members with a full stream over the table.

The copy exists for a good reason: if a conflict is thrown partway through a batch, the table must be left unchanged. That guarantee can be kept more cheaply with two passes:

1. Validate the batch and plan the changes in a small map sized to the batch, without mutating anything.
2. Commit the planned changes.

Keep an `activeCount` field updated as entries change, instead of recounting on every merge.

### 4. A whole JVM is started for each DNS lookup

[GossipDnsResolver.java:73, 115-123](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L115-L123)

Each hostname lookup runs `java -cp … GossipDnsLookup` as a separate process. That costs about 100 ms–1 s of startup and tens of MB of memory per lookup. Concurrent lookups of the same host each start their own process, because nothing lets them share a lookup that is already in progress.

The goal, being able to kill a lookup that blocks inside the platform resolver, is valid. Cheaper ways to get it:

- run `InetAddress.getAllByName` on a bounded pool of platform threads with a timeout, and abandon a lookup that gets stuck;
- or plug in a custom resolver through `InetAddressResolverProvider` (JEP 418).

Either way, have concurrent requests for the same host share one lookup.

## Medium impact

### 5. Full snapshots used as getters

`MembershipTable.snapshot()` builds a `MemberView` for every entry and then copies the list with `List.copyOf`. It also calls `assessment()` twice per entry ([MembershipTable.java:299, 309](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java#L299)).

Several callers take a snapshot they don't need:

- [GossipRuntime.java:222](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L222) takes a full snapshot **inside the per-peer loop** of `round()`, just to read the local `serverId`, which never changes.
- [GossipRuntime.java:252](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L252) builds `table.snapshot()` while holding the runtime lock and passes it to `selector.resolved()`. That method usually returns immediately because the peer isn't a configured seed, so the snapshot is thrown away. Line 253 takes another snapshot just to read the local port.
- [GossipTransport.java:93, 124, 131](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L124) takes three snapshots per exchange. The last one is only used to check for identity conflicts.
- [GossipCommands.java:65, 71, 84, 99](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L84) uses snapshots to read `self()` and `serverId`.

**Fix:**
- Store the local `serverId` in a field.
- Add a cheap synchronized `MembershipTable.self()`.
- Have the merge methods return whether there are identity conflicts.
- In `snapshot()`, compute each entry's assessment once.

### 6. The transport opens a `Selector` per connection, on virtual threads

[GossipTransport.java:213-222, 257](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L257)

`Selector.open()` creates its own internal wakeup channel, so every exchange allocates extra OS handles on top of the socket. These exchanges already run on virtual threads, where plain blocking I/O is cheap.

**Fix:** use a blocking `SocketChannel` with timeouts and remove the `await` loop. Interrupting the thread still closes the channel, so cancellation keeps working.

### 7. Reading the reply one byte at a time

[GossipTransport.java:197-204](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L197-L204)

`readLine` copies the reply into a `ByteArrayOutputStream` one byte at a time. `ByteArrayOutputStream.write(int)` is `synchronized`, so a 64 KB reply means 64K lock acquisitions.

**Fix:** scan the read buffer for `\n` and write the whole slice in one call.

### 8. Version fallback is never remembered

[GossipTransport.java:102-116](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L102-L116)

Every exchange with a v1 peer first tries v2 and gets rejected. Each such exchange costs two TCP connections and two round trips.

**Fix:** remember the agreed version per peer. Detecting the rejection by matching exact error-message strings ([L112-113](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L112-L113)) is also fragile; a structured error code would be more reliable.

### 9. Each record's metadata is copied twice and round-tripped through JSON

[MemberRecord.java:50-51, 66-67](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java#L50-L51)

`fromWire` deep-copies the metadata, then the constructor deep-copies it again. The constructor then encodes and re-parses it with `Json.write` + `Json.read`. The same happens on every heartbeat in `MembershipTable.advanceLocal`, even though the metadata it passes in is already frozen.

**Fix:** skip the copy and the round trip when the metadata comes from an existing `MemberRecord`, for example through a private constructor.

## Low impact and readability

- **Exceptions used for normal control flow.** `InetAddress.ofLiteral` throws an exception, with a full stack trace, for every hostname that isn't an IP literal. It is called on every candidate every round: see [PeerSelector.java:170-182](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L170-L182) and [GossipDnsResolver.java:68](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipDnsResolver.java#L68). Normalize seed endpoints once when the config is loaded.
- **Regex compiled on every call.** `String.matches` in `GossipValidation.validId` compiles a new pattern for every incoming `MemberKey`, and `validHost` builds a `URI` for every record ([GossipValidation.java:25-40](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipValidation.java#L25-L40)). The same applies to `decodeCursor` ([GossipCommands.java:170, 177](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipCommands.java#L170)). Precompile the `Pattern`s.
- **Convoluted recovery-fill loop.** [PeerSelector.java:90-95](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L90-L95) calls `containsAll` twice per iteration. A bounded `for (i < recovery.size())` loop that adds each peer if it isn't already selected does the same thing.
- **Rotation that isn't actually fair (a correctness problem).** In `GossipBatchSelector`, `cursor` is an index into a `scan` list that is rebuilt from the snapshot on every call ([GossipBatchSelector.java:57, 77, 81-94](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipBatchSelector.java#L81-L94)). `PeerSelector.recoveryCursor` has the same problem against a changing `recovery` list ([PeerSelector.java:99-103](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/PeerSelector.java#L99-L103)). When members expire, the indexes shift and the rotation skips or repeats peers. Track the position by `MemberKey` instead of by index.
- **Clock inconsistency.** Departure timing uses `System.nanoTime()` instead of the injected `clock` ([GossipRuntime.java:172, 211](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipRuntime.java#L211)), which gets around the deterministic-clock hook used by tests.
- **Duplicated self-detection logic.** [GossipTransport.java:94](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java#L94) detects the local endpoint with an exact string compare, while `PeerSelector.isSelf` also handles letter case and IP literals. Use one shared check.

## Suggested order of work

1. **#1 and #2 together:** store each record's encoded size, then compute envelope and page sizes by adding them up. This removes most of the wasted CPU.
2. **#5:** quick, low-risk cleanup of unneeded snapshots.
3. **#7, #8 and the low-impact items:** small, local changes.
4. **#3, #4 and #6:** larger refactors. Each needs its own change and careful testing of rollback, cancellation and timeout behaviour.

The module's existing tests (`MembershipTableTest`, `GossipBatchSelectorTest`, `PeerSelectorTest`, `GossipTransportTest`, `GossipCommandsTest`, `GossipDnsResolverTest` and others) cover these areas and should be run after each change.
