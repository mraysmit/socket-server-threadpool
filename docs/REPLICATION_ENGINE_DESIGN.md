# A generic replication engine for a gossip swarm

| Document metadata | Details |
| --- | --- |
| Status | Proposal, for review. Nothing in this document is implemented. |
| Last updated | 2026-10-20 |
| Applies to | A `replication` plugin, and the plugins that use it ([`kv-plugin`](../plugins/kv-plugin) and a configuration plugin) |
| Part of | A set of three documents. [`PLUGIN_SERVICES_DESIGN.md`](PLUGIN_SERVICES_DESIGN.md) lets plugins call each other. This document is the engine. [`REPLICATION_PROFILES_DESIGN.md`](REPLICATION_PROFILES_DESIGN.md) applies it to key-value data and to configuration. |

## 1. Summary

A swarm of servers needs to share some data: key-value pairs today, configuration next, and probably more later. The
work of sharing is the same in each case: give every record a version, decide which of two versions wins, push changes to
a few peers, and repair differences in the background. It does not depend on what the data means.

This document proposes one **replication plugin** that does that work for named collections of records called
**namespaces**. Other plugins register a namespace and use it through a Java service. The key-value plugin keeps its
command interface and stores its pairs in a `kv` namespace. A configuration plugin stores settings documents in a
`config` namespace. The gossip plugin tells the engine who the active peers are.

The version, the merge rule, deletion records, the root hash and the sync protocol belong to the engine and are shared by
every namespace. How a namespace's values are checked, applied and exposed to clients belongs to the plugin that owns it.

### Decisions already made

| # | Decision |
| --- | --- |
| 11 | The engine is a separate `replication` plugin that publishes its stores as a service. |
| 12 | Plugins register namespaces at start, each with its own limits, retention, validator and change listener. Clients never create namespaces. |
| 13 | Clients reach replicated data only through the plugin that owns the namespace, plus a read-only generic view for diagnostics. |
| 14 | The `kv` instruction and its replies stay as they are, as a thin layer on the `kv` namespace. |
| 15 | The merge rule is an interface per namespace. Newest-write-wins is the only one built. |
| 16 | Values are JSON only. |
| 17 | One clock per server, shared by every namespace, with a bound on clock skew. |
| 18 | Each namespace has its own root hash and its own sync, taking turns, so a large namespace cannot delay a small one. |
| 19 | A server belongs to exactly one swarm. |
| 29 | A namespace may opt in to a local snapshot file. Configuration does. The key-value store does not. |
| 30 | A write never waits for peers. |
| 31 | A server that is still catching up answers reads normally and reports that it is catching up. |
| 32 | Every limit is configurable. Nothing is hard-coded. |
| 33 | Replication is off unless it is turned on. |
| 34 | The plugin is `replication-plugin`, its instruction is `replicate`, and plugins reach it through `ReplicationService`. |
| 35, 36 | Three documents; delivery in the order service registry, then engine with key-value, then configuration. |
| 49 | The reader for `namespace.<name>.*` settings is one small class in the host module, used by the engine and by plugins that run without it. |
| 50 | One keep-alive client with stale-connection retry moves into the host module and is shared by the gossip plugin, the engine and the harness. |
| 51 | "No limit is hard-coded" means tunable limits and policies. A check that protects the protocol, such as a sync message having to hold at least one maximum-size record, is code, and is checked as a relationship between configured values. A plain bound such as "at least 1" is not a limit. |
| 52 | The generic read view shows every namespace not marked private. |
| 53 | A plugin cannot declare limits for its namespace. They come only from configuration, and a plugin enforces anything else in its own validator. |
| 54 | No per-namespace write rate limit for now. |

## 2. Goals and non-goals

Goals:

1. Data written through any active server becomes readable through every other active server, normally within seconds.
2. A server that joins, restarts or returns after a partition converges without an operator.
3. A deletion stays a deletion.
4. Any plugin can replicate its own data by registering a namespace, without writing replication code.
5. Cost stays flat as the swarm grows. No work per write that grows with the number of members.
6. Convergence is visible per namespace.
7. Every limit and policy is configuration, with no numeric limit fixed in code.

Non-goals:

1. **Strong consistency.** No transactions, no linearizable reads, and no write that waits for peers. A conditional write
   (see the profiles document) is checked on the receiving server only.
2. **Sharding.** Every active server holds every record of every replicated namespace.
3. **Events and counters as first-class types.** Newest-write-wins is wrong for a counter (increments must add) or a set
   (adds and removes must merge), and an event must be delivered once. Section 5.5 leaves room for other merge rules.
   Events would need a different primitive and are not covered.
4. **Binary values.** JSON only.
5. **Replicating bootstrap configuration** (server ID, ports, plugin directories, seeds). See the profiles document.

## 3. What this builds on

- The **gossip plugin** knows the swarm's members, rates each one `ALIVE`, `SUSPECT`, `DEAD`, `LEFT` or `CANDIDATE`, and
  holds at most 128 active members by default. It publishes that knowledge through `MembershipView`
  (plugin services document, section 6).
- The **plugin service registry** is how the engine finds `MembershipView` and how other plugins find
  `ReplicationService`.
- The **host protocol** carries every message: one JSON line per request, with `"connection":"keep-alive"` to reuse a
  connection. The gossip plugin and the test harness already pool connections this way. A connection per request caused
  the dynamic-port exhaustion described in the harness README, so the engine must reuse connections (section 7.4).
- **Gossip's exchange limit** (`max.exchange.bytes`, 32,768 by default, at most 65,536) is the model for the size limit of a
  sync message.

## 4. Architecture

```text
   key-value plugin           configuration plugin            (future plugins)
   kv commands                config commands (signed writes)
        |  namespace "kv"          |  namespace "config"
        v                          v
   +----------------------------------------------------------------+
   |  replication plugin:  ReplicationService                        |
   |   per namespace: records, merge rule, validator, root hash      |
   |   one clock per server                                          |
   |   push, forwarding, anti-entropy sync, bootstrap, snapshots     |
   +----------------------------------------------------------------+
        ^  peer list                                   |  replicate instruction
        |                                              v  (apply, sync-root, sync-digest)
   gossip plugin: MembershipView                  other servers' replication plugins
```

The engine never decides on its own whether a server is alive. It asks `MembershipView` and talks only to members rated
`ALIVE` in the same swarm.

## 5. Concepts

### 5.1 Namespace

A namespace is a named set of records with its own policy: limits, retention, merge rule, validator, change listener,
whether it is persisted, and whether it is replicated at all. A name matches `[a-z][a-z0-9._-]{0,63}` (the length is
itself a configured limit). The names `kv` and `config` are used by the plugins described in the profiles document.

Only a plugin can register a namespace (decision 12). A request from a client that names an unregistered namespace is
refused. Registering the same namespace twice in one server is refused.

### 5.2 Record

```text
namespace   the collection it belongs to
key         a string; its longest length is a namespace limit
value       any JSON; absent on a deletion record; its largest size is a namespace limit
version     the clock value in 5.3
deleted     true for a deletion record (a "tombstone")
origin      the server that wrote this version (also the last part of the version)
signature   optional, opaque text; carried and stored with the record and never interpreted by the engine (reserved for signed documents)
```

### 5.3 Version: the hybrid logical clock

```json
{"w": 1760900000000, "c": 0, "n": "alpha-Server-001"}
```

`w` is wall-clock milliseconds, `c` is a counter that breaks ties within a millisecond, and `n` is the writing server's ID,
which breaks the remaining ties. Versions compare by `w`, then `c`, then `n`, so they are totally ordered and every server
picks the same winner.

There is **one clock per server**, used by every namespace (decision 17). A write in any namespace is therefore stamped
later than every record the server has seen in any namespace, which keeps ordering consistent across them. On receiving a
version greater than its clock, a server moves its clock forward to match.

A record whose `w` is more than `max.clock.skew.millis` ahead of the receiver's wall clock is refused, counted and logged,
because one server with a wrong clock could otherwise write a version nothing could overwrite. In the test harness all
servers share a JVM and a clock, so skew does not arise there.

### 5.4 Deletion records

A deletion is a record with `deleted=true` and a fresh version, written even for a key the server does not hold, since a
peer may hold it. Deletion records are kept for the namespace's `tombstone.retention.millis`, and dropped by comparing the
record's own `w` to the current wall clock, not by when each server received it, so servers drop the same records at nearly
the same moment. The root hash excludes expired deletion records for the same reason. A write over a deletion record
revives the key with a newer version.

A partition longer than the retention can let a stale server bring a deleted key back. The gossip plugin documents the same
limit for its own records.

### 5.5 Merge rule

```java
public interface MergePolicy {
    /** The record to keep. Must be commutative, associative and idempotent, so any order of arrival gives one result. */
    Record merge(Record local, Record remote);
}
```

`LastWriterWins` keeps the record with the greater version and is the only policy built (decision 15). The interface exists
so that a counter or a set can be added later without changing the sync protocol, which carries versions and records and
does not interpret them. A namespace names its policy in configuration.

A policy that is not last-writer-wins would also need a different notion of "version" in the digest. That is a reason it is
deferred, not a reason to hard-code the rule.

## 6. The service interface

This is the Java surface a plugin uses. It is a sketch for review, not final signatures.

```java
public interface ReplicationService {
    /** Registers a namespace. Its limits and retention come from configuration under its name (section 10), never from the caller. */
    Namespace register(NamespaceSpec spec);

    Optional<Namespace> namespace(String name);
    Status status();              // every namespace: counts, root hash, bootstrap state, peers

    record NamespaceSpec(String name, MergePolicy mergePolicy, Validator validator, ChangeListener listener) { }

    interface Validator { void validate(String key, JsonNode value) throws InvalidParametersException; }

    /** Called after a record is applied from a peer or from a local write, on a virtual thread. */
    interface ChangeListener { void changed(Record record, Origin origin); }

    enum Origin { LOCAL, REMOTE, RESTORED }
}

public interface Namespace {
    String name();
    WriteResult put(String key, JsonNode value, WriteOptions options) throws InvalidParametersException;
    WriteResult delete(String key, WriteOptions options) throws InvalidParametersException;
    Optional<Record> get(String key);                 // live records only
    List<Record> list(String after, int limit);       // live records, in key order
    NamespaceStatus status();

    record WriteOptions(Optional<Version> ifVersion) { }  // checked on this server only; see the profiles document
    record WriteResult(Version version, boolean created) { }
}
```

A write validates the key and value against the namespace's limits and its `Validator`, stamps a version, stores it, informs
the `ChangeListener`, and returns. Replication happens after the return (decision 30).

## 7. Replication

### 7.1 The `replicate` instruction

All messages between servers use one instruction, `replicate`, on the main port, with an `operation`. Every message carries
`syncVersion`, `swarmId` and, except for `status`, a `namespace`. A receiver refuses a message whose swarm ID differs from
its own, or whose namespace it has not registered, or when replication is off. Like gossip, the design assumes a trusted
network.

| Operation | Purpose |
| --- | --- |
| `apply` | Carries records of one namespace. The receiver merges each one and replies how many it applied. Used for both pushes and sync. |
| `sync-root` | Carries the sender's root hash, record count and clock for a namespace. The reply says whether the receiver's root is equal, and carries its own clock. |
| `sync-digest` | Carries a page of the sender's (key, version, deleted) triples after a cursor. The reply carries the records the receiver has that are newer or missing, and the keys it wants from the sender. |
| `status` | Diagnostics: every namespace's counts, root hash and bootstrap state, and per-peer last sync. |
| `get`, `list` | Read-only access to any namespace, for diagnostics (decision 13). Not offered for namespaces whose configuration marks them private. |

An `apply` request (one line on the wire, wrapped here):

```json
{"instruction":"replicate","parameters":{
  "operation":"apply","syncVersion":1,"swarmId":"alpha","namespace":"kv","from":"alpha-Server-001",
  "records":[
    {"key":"colour","value":"blue","version":{"w":1760900000000,"c":0,"n":"alpha-Server-001"},"deleted":false},
    {"key":"size","version":{"w":1760900000450,"c":0,"n":"alpha-Server-001"},"deleted":true}]}}
```

### 7.2 Root hash

Per namespace: the first 16 hex characters of SHA-256 over the records in key order, each contributing `key`, `w`, `c`, `n`
and `deleted`. Values are not hashed, because equal versions mean equal values. Expired deletion records are excluded
(5.4). Two servers with equal roots for a namespace hold the same records in it.

### 7.3 Push and anti-entropy

**Push.** After a local write, the server sends an `apply` with that record to up to `push.fanout` peers it rates `ALIVE`,
chosen at random. A receiver that finds the record newer than what it holds applies it and forwards it to `push.fanout`
peers of its own, excluding the sender. A receiver that already had it, or something newer, stops. A write reaches the swarm
in a few hops, and no server makes more than a handful of connections per write however large the swarm is. Pushes run on a
virtual thread and never delay the writer's reply.

**Anti-entropy.** Every `sync.round.millis`, a server picks `sync.fanout` `ALIVE` peers and, for one namespace per peer in
turn, sends `sync-root`. If the roots are equal, that is the whole round. Otherwise it walks its keys in order, a page at a
time, sending `sync-digest` (a page is as many triples as fit in `sync.max.bytes`), and then sends an `apply` with the records
the peer wants. Both sides end holding the union of the two stores' winners.

Namespaces take turns by round-robin (decision 18), and a namespace that is already equal costs one small request and
reply, so a large namespace cannot delay a small one, and many namespaces do not multiply the cost of a swarm that is in
sync.

A digest triple is about 100 bytes for a short key and up to about 350 for a long one, so a 32 KB page holds roughly 90 to
300 triples. The figures change with the configured limits.

### 7.4 Peer connections

The engine keeps keep-alive connections to its peers and reuses them, with a stale-connection retry. A connection per
request would repeat the problem the harness found at 100 servers: gossip opening a connection per exchange produced roughly
200 new sockets a second and exhausted the Windows dynamic port range. The gossip plugin's `GossipTransport` and the harness's
`ConnectionPool` already implement this. The engine should share one implementation rather than add a third copy, which
means moving it into the host module. That move is part of the first phase of this work (decision 50).

### 7.5 Joining, restarting and returning

- **A server that starts** (or restarts, which gives it a new boot ID and an empty store unless a snapshot restores one)
  does not wait for the next round. Once it can see an `ALIVE` peer it runs a full sync of every namespace with that peer,
  then with a second peer. Until the first completes, `status` reports `bootstrapping` and reads are answered normally
  (decision 31).
- **Start order.** The engine registers for `MembershipView` with `whenAvailable` (plugin services, 4.3) and begins when it
  appears. A plugin that registers a namespace before the engine exists registers when `ReplicationService` appears, and
  works as a single-server store until then.
- **A partitioned server** keeps accepting writes. After the partition heals, the next sync merges both sides by the merge
  rule.
- **A removed, stopped or dead server** is skipped, because only `ALIVE` peers are used. A server whose gossip rating is
  `SUSPECT` is skipped until it is rated `ALIVE` again.
- **A server that leaves** has already pushed its writes to peers. Because every server holds every record, the data
  survives the departure of any one server.

### 7.6 Snapshots

A namespace whose configuration sets `persist=true` writes its records to a snapshot file under `snapshot.dir`, per server,
by writing a temporary file and renaming it, and loads it at start. Restored records are merged like any other, so a stale
snapshot loses to newer records from peers (and a record restored after a long shutdown is still subject to deletion record
expiry, which is a reason the retention should exceed the longest expected outage for persistent namespaces). Writing is
asynchronous and batched, so a write never waits for the disk. A namespace that does not persist is lost when every server
stops. The key-value store does not persist and configuration does (decision 29).

## 8. Limits

Every limit below is a configuration value, per namespace unless stated, read at start and validated. None is a constant in
the code. The numbers in the profiles document are the *shipped default values* in a properties file bundled with each
plugin, not limits written into the Java.

| Setting | Meaning |
| --- | --- |
| `max.key.length` | Longest key, in characters. |
| `max.value.bytes` | Largest value, as UTF-8 bytes of its JSON. |
| `max.records` | Live records a server accepts from its own clients. |
| `soft.ceiling.factor` | Replicated records may exceed `max.records` up to this multiple, each one counted and logged. Dropping a replicated record would make servers disagree for good. |
| `max.deleted.records` | Deletion records kept, counted separately from live records so deletions cannot crowd out live data. |
| `tombstone.retention.millis` | How long deletion records are kept. Should be at least gossip's `terminal.retention.millis`. |
| `sync.max.bytes` | Largest sync message. |
| `max.clock.skew.millis` | A record this far ahead of the receiver's clock is refused. |

Memory in the worst case per server is `max.records × soft.ceiling.factor × max.value.bytes` plus deletion records, summed
over namespaces. The engine logs that figure at start so an operator can see what the configuration allows.

## 9. What this does and does not guarantee

Guaranteed, while servers keep their roles:

- **Convergence.** If writes stop and every active server can reach one other, all servers end with the same records in each
  replicated namespace.
- **Writes to different keys never interfere.**
- **Deletions stay deleted** for as long as the deletion record is retained.
- **One server's writes to a key take effect in the order it made them.**

Not guaranteed:

- **Read-your-writes across servers.** A read elsewhere straight after a write can still see the old value.
- **Concurrent writes to one key** keep only the greater version. The other is silently lost under last-writer-wins.
- **Durability** past the loss of every server, for a namespace that does not persist.
- **A write whose push failed**, if its server stops before the next sync. The window is one sync round.
- **Deletions across a partition longer than the retention.**

Two servers may briefly disagree about who is active, because gossip is itself eventually consistent. Sending to a server that
turns out to be down fails and is retried at the next round.

## 10. Configuration

The engine reads a properties file selected by `-Dreplication.config=...`, overridable per server with
`server.<serverId>.` keys, exactly as the gossip plugin does, so the test harness can generate it the same way. Settings for
a namespace are keyed `namespace.<name>.<setting>`. The plugin jar bundles a defaults file, so a server starts with no
configuration at all and with sensible values, but every value can be changed and none is compiled in.

| Key | Meaning |
| --- | --- |
| `replication` | `on` or `off` for the whole engine. Off by default (decision 33). |
| `push.fanout` | Peers a write, and a forwarded write, is sent to. |
| `sync.round.millis` | Time between anti-entropy rounds. |
| `sync.fanout` | Peers compared per round. |
| `snapshot.dir` | Where persistent namespaces write snapshots. |
| `namespace.<name>.replicated` | `true` to replicate, `false` to keep the namespace local. |
| `namespace.<name>.persist` | `true` to snapshot. |
| `namespace.<name>.merge` | The merge policy's name. |
| `namespace.<name>.<limit>` | Each setting in section 8, for that namespace. |
| `namespace.<name>.private` | `true` to hide the namespace from the read-only generic view. |

A plugin that uses a namespace without the engine (replication off, or the engine not loaded) reads the same `namespace.<name>.*`
keys through a small loader in the host module, which the engine uses too, so a limit is parsed and checked identically either
way and there is one definition of the keys (profiles document, section 2.3).

A value that is missing a required relationship, for example `tombstone.retention.millis` below gossip's, or a limit that
cannot hold even one maximum-size record in a sync message, fails the engine at start with a message naming the keys, the
way the gossip plugin's configuration does.

## 11. Observability

- `replicate status` reports, per namespace: live and deleted counts, root hash, the clock, bootstrap state, per-peer last
  sync time and result (`EQUAL`, `SYNCED`, `FAILED`), and counters (pushed, applied, ignored, sync rounds, differences,
  skew refusals, soft-ceiling overruns, validator rejections).
- The test harness reads it for each server and shows, per swarm and namespace, how many servers share the majority root.
  A server whose root differs is marked.

## 12. Optional, later: publishing a digest in gossip metadata

Each server could publish its namespace roots in its gossip `metadata` (a few dozen bytes, well inside the 4,096 allowed).
Existing gossip exchanges would then spread each server's state to everyone, and a server would open a sync connection only to
a peer whose published root differs from its own. In a swarm that is in sync the engine would send nothing of its own.

It needs gossip to publish its metadata at runtime. `MembershipTable.updateLocal` exists, but nothing calls it and no operation
exposes it. It would be a further service interface from the gossip plugin (plugin services document, section 6). Each
metadata change advances gossip's heartbeat, so continuous writes would need a rate limit. This is why it is a later phase.

## 13. Test plan

**Unit tests (no network).** Merge-policy properties (commutative, associative, idempotent) over random record sets; clock
ordering and tie-breaks; clock advance on receipt; skew refusal; a deletion record for an absent key; revival by a newer write;
deletion expiry by the record's own timestamp; root hash equal for equal stores and different after any change; digest paging at
the byte limit, including a value near `max.value.bytes`; limit enforcement and the soft ceiling; namespace name rules;
configuration validation errors; that no limit is read from a constant (a test registers a namespace with unusual limits and
sees them honoured).

**In-process swarm tests** (in the harness, real servers, real gossip, skipped when the plugins are not built):

1. Five servers. A write on each in turn is readable on all within a deadline, in each of two namespaces.
2. A delete is seen everywhere, and a later write revives the key.
3. A server added to a running swarm receives every record of every namespace.
4. A removed server's records remain on the others. A restarted server is restored from peers.
5. Concurrent writes to one key on two servers converge to the same value on every server.
6. A stopped server is skipped and catches up after a restart.
7. Roots are equal across the swarm once converged, and differ while a write is in flight.
8. With replication off, a namespace behaves as a single-server store, and the server refuses `apply` from a peer.
9. A swarm with another swarm ID is refused.
10. A namespace set `persist=true` is restored from its snapshot after every server restarts, and a stale snapshot loses to
    newer records from peers.
11. A large namespace and a small one: a change in the small one converges within one round even while the large one is
    mid-sync.
12. A consumer plugin started before the engine works alone, then replicates once the engine appears.

**Partition tests.** The plugin repository already has a TCP relay used by the gossip partition tests. Put it between two
groups, write on both sides, remove it, and assert convergence by the merge rule, and that a key deleted on one side before the
partition stays deleted afterwards.

**Scale and cost.** One hundred servers at `--gossip-fast` timings with steady writes: every write readable everywhere within
30 seconds, `TIME_WAIT` count flat, and no server above a few dozen connections.

| Case | Target |
| --- | --- |
| 5 servers, `--gossip-fast`, a write on any server | readable on all within 5 seconds, on every one of 100 runs |
| 100 servers | readable on all within 30 seconds |
| New or restarted server holding the configured maximum | holds all of it within 30 seconds |
| Two concurrent writes to one key | every server holds the same one within one sync round after the last |
| Delete, then a partition shorter than the retention heals | the key stays deleted |
| Steady state, nothing changing | one `sync-root` request and reply per namespace per round, no `sync-digest` |
| 100 servers, 10 minutes | no growth in `TIME_WAIT` above the harness baseline |

## 14. Review outcomes

| Point | Outcome |
| --- | --- |
| Share the keep-alive client in the host module | Yes (decision 50). It is a refactor of working code in the gossip plugin and the harness, and it comes first. |
| Do valid-range checks count as hard-coded limits | No (decision 51). Protocol relationships are checked as relationships between configured values. |
| The generic read view and namespaces not marked private | Shown (decision 52). Configuration is marked private. |
| A plugin cannot insist on a limit | Accepted (decision 53). It enforces its own in its validator. |
| One shared reader for `namespace.<name>.*` settings in the host | Yes (decision 49). |
| A per-namespace write rate limit | Not now (decision 54). Easy to add if a need appears. |

## 15. Implementation plan

This document describes the target. The order of work is in [`REPLICATION_PLAN.md`](REPLICATION_PLAN.md). Replication is first
built inside the key-value plugin (Phases 1 to 3: push, convergence, scale), and the engine described here is extracted from it
in Phase 4, once there is working code to extract it from. The optional gossip metadata digest (section 12) is not scheduled.
