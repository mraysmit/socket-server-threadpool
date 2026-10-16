# Gossip protocol plugin for a swarm of socket servers

| Document metadata | Details |
| --- | --- |
| Author | Mark Andrew Ray-Smith, Cityline Ltd |
| Status | Seed runtime, departure, bounded capacity/fairness/partition/load validation, and operational logging complete; optional UDP discovery pending |
| Last updated | 2026-10-15 |

## Purpose and scope

Implement a plugin that lets instances of this server discover one another and maintain a local view of the swarm. Each instance periodically exchanges membership information with a few peers through the existing JSON command socket. Every member can initiate and answer exchanges; seed servers help members find the swarm but have no permanent coordinating role.

The first implementation should gossip membership and small, owner-controlled metadata, such as a node's supported application instructions. It should expose the local membership view for inspection. Application event delivery can be added later, with its own retention and deduplication rules.

The external provider in [`plugins/gossip-plugin`](../plugins/gossip-plugin/pom.xml) loads through `ServiceLoader`, resolves per-instance settings, creates a fresh boot ID, and supports `exchange`, paginated `members`, `status`, and asynchronous `leave`. It automatically contacts seeds through jittered rounds, publishes owner heartbeats, maintains local assessments and retention, and retries unavailable peers with bounded backoff. Immutable records, atomic merges, complete canonical-envelope budgets, fair bounded batches, and bounded outbound TCP work are implemented. Real-socket tests verify capacity-safe admission at 128 members, complete batch coverage during changes, controlled partition recovery, and resource cleanup under scheduled load. Packaged providers additionally verify three-node convergence and departure. Optional UDP discovery remains pending. The host lifecycle prerequisite and baseline request hardening are implemented: shutdown closes the main listener before invoking plugin stop hooks, accepted requests have a bounded drain, and request/header lines and concurrently serviced connections have limits. Plugin stop hooks also run during failed startup, before loader cleanup.

The intended first deployment is a small swarm on a trusted LAN, with mutually reachable TCP endpoints and unique configured server IDs. Membership is eventually consistent: nodes can temporarily disagree, and a timeout indicates suspected unreachability rather than proof that a process has stopped. Membership alone must not authorize exclusive ownership, elect a unique leader, or guarantee that a command executes once.

## Existing server features to reuse

| Existing feature | Use in the gossip plugin |
|---|---|
| [`CommandPlugin`](../src/main/java/dev/mars/plugin/CommandPlugin.java) | One provider owns startup, the command handler, membership state, and periodic work. |
| [`PluginContext`](../src/main/java/dev/mars/plugin/PluginContext.java) | Supplies the local server ID and actual main/admin ports, including ports allocated with `0`. |
| [`PluginRegistry`](../src/main/java/dev/mars/plugin/PluginRegistry.java) | Discovers the external provider through `ServiceLoader`, checks instruction uniqueness, and normalizes results to JSON. |
| [`WorkerRunnable`](../src/main/java/dev/mars/worker/WorkerRunnable.java) | Runs incoming gossip commands on the existing virtual thread for each connection. |
| [`TextMessageClient`](../src/main/java/dev/mars/client/TextMessageClient.java) | Sends manual inspection commands. The plugin transport reuses the same host envelopes and reply validation with bounded response reading. |
| [`CommandProtocol`](../src/main/java/dev/mars/protocol/CommandProtocol.java) | Provides versioned envelopes, UUID correlation, parameter objects, and typed errors. |
| [`AddressBroadcastPlugin`](../src/main/java/dev/mars/plugin/builtin/AddressBroadcastPlugin.java) | Offers optional LAN bootstrap hints through its existing UDP announcements on port 9002. |
| [`ServerInstance`](../src/main/java/dev/mars/server/ServerInstance.java) | Binds both ports before plugin startup and owns shutdown and plugin loader cleanup. |

No additional TCP listener is needed. Send gossip to the main command port; the administrative port remains for the existing status and shutdown commands. The HTTP and legacy text paths retain their current roles.

The main listener currently services at most 1024 connections concurrently; excess connections wait in the kernel backlog. Transient `accept()` failures retry after 100 ms. These are host protections shared by gossip and application commands, not a substitute for the plugin's smaller outbound concurrency and message budgets. The connection cap and drain timeout have package-private setters for tests; neither is a CLI option.

The protocol follows periodic peer exchanges and bounded information propagation described in [Wikipedia's gossip protocol overview](https://en.wikipedia.org/wiki/Gossip_protocol). Failure detection and dissemination should remain separate responsibilities, following the useful distinction in the [SWIM paper](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf). The simpler heartbeat design below is not an implementation of SWIM and does not inherit its performance guarantees.

## One provider and one instruction

Register one public provider class, `dev.mars.plugins.gossip.GossipPlugin`, with instruction name `gossip`. Use an `operation` parameter to select behavior:

| Operation | Purpose | Network activity inside the handler |
|---|---|---|
| `exchange` | Merge a peer's records and return a bounded selection of local records. | None. |
| `members` | Return a bounded, paginated local membership snapshot and diagnostics. | None. |
| `status` | Report local identity, worker state, last round, and counters. | None. |
| `leave` | Publish terminal `LEFT`, schedule bounded departure attempts, and stop ordinary outbound work. | Wake owned work only; return immediately. |

All four operations are implemented. Status reports `implementationStage="departure-runtime"`, `supportedOperations=["exchange","members","status","leave"]`, and normally `workerState="RUNNING"`. Departure changes it to `LEAVING` and then `QUIESCED`; shutdown changes it through `STOPPING` to `STOPPED`. A scheduler exit or cleanup failure reports `FAILED` or `CLEANUP_FAILED`. Status includes capacity and assessment counts, reachable identity-conflict count, inbound/outbound success counts, outbound failures, rejected exchanges, in-flight calls, and last-success age. Its `runtime` object includes `rounds`, `roundFailures`, `skipped`, `workers`, `dnsHelpers`, `retryingPeers`, `lastRoundAgeMillis`, and failure counts classified as `DNS`, `NETWORK`, `TIMEOUT`, `PROTOCOL`, `IDENTITY`, `CAPACITY`, `VERSION`, or `SWARM`. Its `departure` object reports the attempt state and counters described below. Worker/helper gauges are observational rather than a transactional snapshot. `isolated` remains true until a valid exchange is processed or an outbound exchange succeeds. Inbound success means the handler accepted and returned the result; the SPI cannot observe whether the host delivered it to the client. Owner heartbeats advance even while isolated, and stop permanently on `leave`.

Each discovered provider instance is shared by concurrent requests. Keep its runtime state in instance fields. Do not use a static singleton: tests and Java applications can start several `ServerInstance` objects in one JVM.

Using one instruction avoids registering multiple providers that accidentally create separate membership tables. The current SPI assigns one instruction to each provider. Extra public commands would require a deliberate shared-runtime design; the operation field avoids that requirement.

Use `InvalidParametersException` for invalid operations, incompatible gossip versions, wrong swarm IDs, and malformed records. These map to the existing `INVALID_PARAMETERS` reply. Unexpected execution failures use the host's existing `PLUGIN_ERROR` behavior. A valid exchange returns its data as the normal `result` object.

## Plugin configuration and identity

The ServiceLoader provider requires a UTF-8 properties file selected by `-Dgossip.config=...`. Missing files or invalid settings fail plugin startup and trigger host cleanup. The host currently has no plugin configuration object or arbitrary plugin command-line flags. Do not add imaginary `--gossip-*` options to `ServerOptions`.

Example configuration for one process:

```properties
swarm.id=demo-swarm
advertise.host=127.0.0.1
seeds=Server-001@127.0.0.1:9000,Server-002@127.0.0.1:9010
round.millis=1000
heartbeat.millis=5000
fanout=2
max.inflight=2
max.members=128
max.records=512
max.batch.members=32
max.exchange.bytes=32768
suspect.after.millis=60000
dead.after.millis=180000
terminal.retention.millis=1800000
discovery.enabled=false
```

These values are defaults for the small-LAN runtime, not measured convergence or failure-detection guarantees. Only `swarm.id` and `advertise.host` are required; omitted seeds default to an empty list. Tune runtime values using the tests below. A seed entry contains an expected server ID, a host, and a main TCP port. The parser supports hostnames, IPv4, and bracketed IPv6 seed endpoints, such as `Server-001@[::1]:9000`, without resolving DNS during startup. Use an unbracketed IPv6 address for `advertise.host`.

The parser rejects unknown keys, malformed or duplicate seed entries, more than `max.members` configured seeds, non-integer/overflowing limits, and inconsistent timing or capacity settings. IDs use 1-128 letters, digits, dots, underscores, or hyphens. Intervals must be positive and convertible to nanoseconds; require `heartbeat < suspect < dead <= terminal retention`. Capacities require `1 <= max.members <= max.records`, batch count between 1 and `max.records`, and fanout/in-flight limits between 1 and `max.members`. The exchange budget must be 1024-65,536 bytes. `discovery.enabled=true` fails startup until discovery is implemented.

Use `PluginContext.port()` for the advertised main port. A configured `advertise.host` supplies the reachable address because `PluginContext` has no host/interface field. Loopback works only for processes on the same machine; on separate machines configure a LAN address or resolvable hostname reachable by all members. The existing listeners bind to wildcard addresses, but firewall and routing rules must still allow access.

Configuration is per plugin instance. A single JVM property selects a file for a normal one-server process. For multiple instances in one JVM, global properties act as defaults and `server.<serverId>.<key>` overrides are resolved separately at each start:

```properties
swarm.id=demo-swarm
advertise.host=127.0.0.1
server.Server-001.advertise.host=192.168.1.10
server.Server-002.advertise.host=192.168.1.11
```

Embedded applications and tests can instead supply an immutable [`GossipConfig`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipConfig.java) to the explicit `GossipPlugin(config)` constructor. No process-wide mutable configuration or plugin runtime is cached.

Give every concurrently running server a distinct `serverId`. Generate a fresh random `bootId` UUID when the plugin starts. The membership key is `(serverId, bootId)`: a restart creates a new member record even when the logical server ID stays the same. UUIDs distinguish processes; do not sort them to infer which restart is newer. Two reachable processes advertising the same server ID are a configuration conflict. Report both and require an operator to resolve the conflict rather than silently choosing a winner.

## Membership records and local observations

Separate information a member publishes about itself from observations made by a receiver.

An owner record contains:

| Field | Meaning |
|---|---|
| `serverId`, `bootId` | Logical server identity and process identity. |
| `host`, `port` | Main command endpoint. |
| `heartbeat` | Nonnegative integer incremented by this process every heartbeat interval. |
| `state` | Owner-published `ALIVE` or terminal `LEFT`. |
| `metadata` | A small JSON object describing this member; bound its serialized size. |

Only the owner advances its heartbeat or publishes `LEFT`. A metadata or endpoint change also advances the heartbeat. Validate integer fields exactly; decoded JSON integers use `BigInteger` in this project, so do not cast them directly to `Integer` or `Long` or accept fractional values through `Number.longValue()`.

The implemented [`MemberRecord`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberRecord.java) accepts only the seven owner fields shown above, canonical lowercase UUID strings, integral port values in 1-65,535, and heartbeats in 0-`Long.MAX_VALUE`. Owner state is exactly `ALIVE` or `LEFT`; local `SUSPECT`/`DEAD` assessments are never decoded as owner state. Metadata is deeply immutable, supports JSON nulls, and is limited to 4,096 serialized UTF-8 bytes and depth 16. It rejects cycles, arbitrary Java objects, nonfinite numbers, unpaired Unicode surrogates, and values the host JSON codec cannot read back. Within these limits only a very long number can fail the codec (its default limit is 1,000 characters), so the read-back check runs only for metadata holding a number of 100 characters or more. Numeric wrapper types and object key order are normalized so equivalent metadata does not create false version conflicts. Decimal precision and absolute scale are additionally bounded at 4,096.

Each receiver additionally stores `lastAdvanceNanos`, `lastDirectSuccessNanos`, a local reachability assessment, and retry/backoff information. Use a monotonic clock for elapsed time. These observations are local and are not copied from another machine's timestamps.

Apply these merge rules under a short lock:

1. Validate the entire exchange, including limits and identity fields, before mutating membership state.
2. For a new key, admit the record if capacity permits, then initialize its local observation time. Treat forwarded endpoints as candidates until a direct exchange succeeds.
3. For an existing `ALIVE` key, accept only a strictly higher heartbeat as new owner information. Update `lastAdvanceNanos` on that advance. Equal versions are duplicates and must not refresh freshness. If equal versions have conflicting contents, reject that record as a protocol conflict.
4. Once `LEFT` is accepted for a boot ID, it dominates `ALIVE` for that same boot ID. A departing process stops producing heartbeats; rejoining requires a new boot ID. Keep a compact tombstone after removing the full record.
5. Never let a forwarded record overwrite the local process's own record. Flag conflicting copies of its current version and continue owning its own state.

A successful direct exchange refreshes direct reachability for the peer that actually answered. It must not refresh every member listed in that response. A newer forwarded heartbeat is indirect evidence of activity; forwarding the same old heartbeat repeatedly is not.

Derive local `SUSPECT` and `DEAD` assessments when neither an owner-record advance nor direct success has occurred for the configured intervals. Do not increment another member's heartbeat or circulate these local assessments as owner records. Continue occasional probes of suspected and dead endpoints. A successful probe can restore reachability for the same boot ID; `DEAD` in this design is a reversible local opinion, while `LEFT` is terminal for that boot ID.

At the initial settings, mark suspect after 60 seconds and locally dead after 180 seconds. These thresholds also depend on swarm size, batching, pauses, and network latency. Stop selecting dead members for ordinary fanout but reserve some retry opportunities for them and for seeds. Remove inactive full records after the retention interval, retaining bounded terminal suppression state where possible.

Finite retention cannot prevent every stale resurrection after arbitrarily long partitions. Old records can briefly reappear after their tombstones expire, then age out when no fresh heartbeat or direct reply arrives. Cap retained identities, including old boot IDs, at `max.records`; cap the active candidate/member set at `max.members`. Skip admissions that cannot fit, count them, and report the sender's admission outcome in version 2 replies. Existing members must still be able to exchange updates when capacity is full.

### Implemented table behavior

[`MembershipTable`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MembershipTable.java) owns one local [`MemberKey`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/MemberKey.java) and receives a monotonic `LongSupplier` clock, defaulting to `System.nanoTime`. Its methods perform no network I/O:

- `mergeEncoded` validates and decodes a complete record batch before applying it. `merge` accepts immutable records. Both treat forwarded endpoints as candidates. `mergeDirect` additionally records success for one responder after the caller validates its outer reply and identity; it does not refresh members carried in the batch. A newer advertised endpoint needs its own direct verification.
- Merge planning occurs under a lock and validates the entire batch before publishing any changes. Malformed records and version conflicts reject the exchange without changing owner data or observation times, including when the conflicting record would not fit. Valid known-member updates commit even at capacity. Known departures free active slots before admission decisions, regardless of batch order. Eligible recoveries of compact retained identities take priority over genuinely new identities; each group otherwise uses wire order. Records that cannot fit `max.members` or `max.records` are skipped and counted in `MergeResult.ignored()` alongside stale and duplicate records. They do not consume slots, discard suppression state, or reject the exchange. Unknown terminal records still require a retained-identity slot. `MembershipException.reason()` distinguishes invalid records and version conflicts; transport retains compatibility with remote `CAPACITY:` errors from older peers.
- `snapshot` returns immutable records, local observation ages, endpoint-verification flags, and capacity counts. Fresh forwarded members are `CANDIDATE` until directly verified; both candidates and verified members become `SUSPECT`/`DEAD` on elapsed-time thresholds. Reachable duplicate logical IDs are reported on every retained record for that ID, without choosing a boot-ID winner. Unverified candidates remain visible but do not yet establish a reachable identity conflict.
- `peers` returns the records other than the local one, in table order, without the assessments and views a snapshot computes; both exchange directions choose their batch from it.
- `maintain` explicitly expires retained state; merge rejection cannot prune as a side effect. Nonlocal `LEFT` full records are compacted after one retention interval from departure acceptance. Inactive `ALIVE` records are compacted after one interval without an advance or direct success. Compact entries retain identity, state, heartbeat, an owner-record fingerprint, and local timing; they expire after one additional retention interval. Repeated stale copies do not extend either interval.
- Compact `LEFT` entries suppress every `ALIVE` version for their boot ID. Compact inactive entries suppress stale forwarded versions; a newer heartbeat or a directly confirmed matching version can restore a full record if capacity permits. Direct recovery preserves the old advance time and updates only direct-success time. Older versions cannot restore retired owner data. A compact entry still detects conflicting contents at its retained version.
- Full nonterminal records, including locally dead ones awaiting compaction, count toward `max.members`. Full records and compact entries together count toward `max.records`; terminal full records release their active slot. Local owner state is retained for the lifetime of the table. `advanceLocal` and `updateLocal` advance only the owner heartbeat, reject overflow, and stop after `leaveLocal` publishes terminal `LEFT` at its current heartbeat. The runtime disseminates that record through bounded departure attempts.
- A departed owner ignores forwarded copies of its own `ALIVE` record at or below its terminal heartbeat. The exchange still applies valid peer updates and replies with the owner's `LEFT` record, allowing peers with stale views to learn the departure. Higher claimed local heartbeats and conflicting contents at the same local state/version remain conflicts; rejected exchanges roll back peer updates and direct-success observations together.

The model enforces batch count (including a direct responder) and rejects a single record larger than `max.exchange.bytes`. The command/transport layer adds complete canonical-envelope budgets, swarm/version checks, pagination, and reply identity validation before merges. Every scheduled round checks owner heartbeat publication and calls `maintain`, including rounds where all exchange workers are busy.

## Bootstrap and discovery

Configured seeds are the baseline bootstrap mechanism. On startup, add the local record and seed candidates. Exclude the current local endpoint, retry unavailable seeds with jitter and bounded exponential backoff, and keep the local server usable if no seed is reachable. Advertise `isolated=true` until an exchange succeeds. Each connected component needs at least one reachable seed or another explicit introduction to join the others.

Use `GossipTransport.exchange(GossipConfig.Seed)` when contacting a configured seed. It pins the expected server ID, validates the correlated host reply, and checks agreement with the result's sender, canonical boot identity, supported gossip version, and swarm ID before merging. A future discovery path must supply the announcement's claimed server ID for the same checks. Identity checks prevent accidental cross-connection mistakes; without authentication they do not establish trust. A request claiming the receiving process's own compound identity is rejected, so contacting a local hostname alias cannot create false bootstrap success.

The existing address broadcaster is an optional source of candidate endpoints:

```json
{"version":1,"type":"SERVER_STARTED","serverId":"Server-001","ip":"192.168.1.10","port":9000,"adminPort":9001}
```

An optional receiver listens on UDP port 9002, validates a small datagram, and queues a TCP handshake. Do not admit a member solely because it appeared in a datagram. Existing announcements carry no `swarmId` or `bootId`; the TCP exchange supplies and checks them. Ignore the admin port for gossip transport.

There are three reasons seeds remain necessary:

- Announcements happen once at startup. They are lost if no receiver is listening and do not cross ordinary subnet boundaries.
- Plugin startup hooks run before TCP listener tasks begin. A discovered endpoint can be bound but not yet ready to answer; retry the handshake asynchronously.
- Several server processes on one host cannot portably each bind UDP 9002 and receive every announcement. Socket reuse is not a portable fanout mechanism. Use seeds for this layout, or an optional single host discovery agent that forwards candidates to local plugins.

The built-in broadcaster runs independently of this plugin and may announce before its receiver starts. Providers initialize concurrently on virtual threads, with no guaranteed hook execution or completion order. Do not depend on class-name startup order to make discovery reliable. Optional receiver bind failures should disable that discovery path and leave seed-based startup working.

Set `-Ddev.mars.announce=false` to disable announcements from the ServiceLoader-constructed broadcaster. This property is independent of the proposed `discovery.enabled` receiver setting. Host Surefire tests and existing child-JVM tests disable real announcements. Apply the same setting to the new plugin test suite and every child JVM it starts; exercise discovery through controlled UDP fixtures rather than broadcasting onto the LAN.

## Exchange protocol

Keep host protocol version `1` unchanged. Put a separate `gossipVersion` inside the plugin parameters and result so that the swarm protocol can evolve independently.

The current gossip protocol supports versions 1 and 2. Each transport exchange starts with version 2; a correlated `INVALID_PARAMETERS` error with exactly `INVALID_RECORD: gossipVersion must be between 1 and 1` or `INVALID_RECORD: Unsupported gossipVersion` permits one retry using version 1. Both attempts reuse the selected records and resolved address, have fresh message IDs, and share one permit and one overall socket-work deadline. Other errors, correlation failures, and unsolicited version changes never trigger fallback. Version 1 replies keep their original four fields and decode with unknown admission. `GossipWire.parameters(self, members)` remains a version 1 convenience API; use its three-argument overload to explicitly request version 2.

The following is one complete wire request. Pretty printing is for readability; send it as a single UTF-8 JSON line, with escaped newlines inside any strings.

```json
{
  "version": 1,
  "messageId": "11111111-1111-4111-8111-111111111111",
  "instruction": "gossip",
  "parameters": {
    "operation": "exchange",
    "gossipVersion": 2,
    "swarmId": "demo-swarm",
    "sender": {
      "serverId": "Server-001",
      "bootId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      "host": "127.0.0.1",
      "port": 9000,
      "heartbeat": 12,
      "state": "ALIVE",
      "metadata": {}
    },
    "members": []
  }
}
```

The normal `CommandReply` wraps a result of this shape:

```json
{
  "gossipVersion": 2,
  "swarmId": "demo-swarm",
  "sender": {
    "serverId": "Server-002",
    "bootId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    "host": "127.0.0.1",
    "port": 9010,
    "heartbeat": 9,
    "state": "ALIVE",
    "metadata": {}
  },
  "members": [],
  "senderAdmission": "ADMITTED"
}
```

The result's sender is always the responding process's current record. Merge it and the batch only after validating the outer correlated reply, supported gossip version, expected swarm ID, and agreement between the outer `serverId` and the result's sender. A seed can restart with the same server ID and a different boot ID; treat that as a new process record.

Version 2 replies require `senderAdmission`, captured atomically with the receiver's committed merge: `ADMITTED` means the requesting sender's identity is present as a full record (including an already retained identity); `CAPACITY` means that sender's admission was skipped for lack of an active or retained-identity slot; `IGNORED` means suppression rules prevented restoration of its full record. This reports retention of the identity, not acceptance of every forwarded record or exact owner version. The admission field is forbidden in requests and version 1 replies. Unknown values or fields are rejected before the caller merges a reply. Complete reply budgets include the hint; forwardability reserves its largest encoded size and the maximum-width admission-skip counter in inspection pages.

Status and membership-page `capacity` objects include `admissionsSkipped`, a per-instance cumulative counter updated with committed inbound and outbound merges. Each capacity-skipped record increments it, including repeated attempts for the same identity; stale and duplicate known records, suppression-only ignores, and rejected invalid/conflicting batches do not. The counter saturates at `Long.MAX_VALUE`. `MergeResult.ignored()` still aggregates all ignore reasons for Java callers. `mergeExchange` additionally returns the atomic sender admission outcome. An `ALIVE` identity restored from suppression needs an active-member slot but no new retained-identity slot.

Do not echo arbitrary input fields into the result. Validate required keys, string lengths, endpoint ranges, canonical UUIDs, integer bounds, allowed states, metadata size, and duplicate member keys. Count the sender toward the maximum batch count and enforce the serialized byte budget on the complete envelope, including the outer reply. Reserve room for envelope overhead before selecting records. A single oversized record is a validation error, not an excuse to exceed the budget.

[`GossipWire`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipWire.java) implements this accounting. Outgoing requests and raw incoming reply lines count complete UTF-8 JSON envelope bytes, excluding line terminators. Reply selection reserves a full UUID and the largest legal positive connection ID (`Long.MAX_VALUE`) because the SPI does not expose the actual reply connection ID. Incoming requests are validated and measured as complete canonical envelopes reconstructed from decoded owner records. The SPI does not expose original whitespace, escape spelling, or raw byte counts, so this server-side check is after host parsing; it is not a pre-parse raw request byte limit.

Before admission, records must also fit individually beside the local sender and in a one-row inspection page, with space reserved for cursor, ages, capacity diagnostics, and the owner's maximum heartbeat width. This prevents admitting a record that makes subsequent bounded responses or inspection impossible as its heartbeat grows. Request and response batch selection have independent rotating cursors; progress advances only for records included. Handler validation, budget checks, and table conflict/admission planning precede mutation. Invalid records and owner conflicts map to `INVALID_PARAMETERS` with an `INVALID_RECORD:` or `CONFLICT:` prefix. Local capacity saturation skips only unadmittable records and still returns a normal correlated exchange reply; receiving a valid reply does not guarantee that the peer admitted the sender. No implemented handler initiates outbound work.

Embedded callers holding a running plugin instance can initiate an explicit exchange:

```java
GossipWire.Exchange reply = plugin.exchangeWith(
        new GossipConfig.Seed("Server-002", "127.0.0.1", 9010));
// The validated reply is already merged into this plugin's own membership table.
```

Callers that own a standalone `MembershipTable` can instead own a `GossipTransport(config, table)` and call `exchange(seed)`. Close the transport when finished. These are Java APIs, not additional console operations. The scheduler uses this transport after bounded hostname resolution. Every exchange opens a fresh connection; the host closes each connection after one reply. Retries use new message IDs because host UUIDs provide correlation, not deduplication. Record-version merging makes exchange retries idempotent.

`members` accepts optional `limit` (an exact integer from 1 through `max.batch.members`, defaulting to the smaller of 32 and that cap) and `cursor`. Its bounded result contains `swarmId`, `self`, rows with owner data and local diagnostics, capacity counts, and nullable `nextCursor`. Row diagnostics are assessment, advance/direct-success ages in milliseconds, endpoint verification, and identity conflict. Cursors are opaque, tied to the local boot ID, and rejected by another instance. Paging is a best-effort view of changing state; it is not a transactional snapshot. Byte budgets can produce fewer rows than the requested limit.

## Periodic work and bounded propagation

The runtime starts one virtual scheduling thread per plugin instance, with an initial delay greater than zero and at most `round.millis`. Subsequent round delays have jitter from approximately 0.8 to 1.2 times that interval. Startup initializes local state and schedules work without waiting for seeds. It owns at most `max.inflight` virtual exchange workers and has no work queue. Known local endpoints are excluded before selection, local hostname aliases are filtered after resolution, and resolved destinations already in flight are skipped. Each round also remembers its resolved destinations until all its workers finish, preventing a delayed hostname lookup from redialing an endpoint whose earlier call in that round has already completed.

Each round performs the following work:

1. Advance the owner's heartbeat when its interval has elapsed, even if the node is isolated. Missed intervals coalesce into one advance rather than a burst of catch-up work.
2. Update local reachability assessments and prune expired retained state.
3. Select up to `fanout` distinct peers within available worker slots. Ordinary `ALIVE` peers, including healthy configured seeds, are randomized. Unknown seeds, unverified candidates, suspects, and locally dead peers receive rotating recovery opportunities, with a reserved slot every third selection and additional opportunities when ordinary peers leave slots free. Multiple boot IDs sharing the same expected server ID and endpoint do not cause duplicate calls.
4. Snapshot a bounded record batch and start exchanges only while capacity is available. Release each transport permit and worker assignment in `finally`. Local saturation skips work without penalizing a peer.
5. Merge each validated result and record metrics. Classify network errors, incompatible versions, capacity errors, and identity conflicts separately.

Failures use exponential retry backoff beginning at the round interval, with approximately 0.75-1.25 jitter and an absolute 30-second cap. Success resets the peer's backoff except when an ordinary version 2 exchange reports `senderAdmission=CAPACITY`: the runtime preserves and increases that destination's backoff, remembers verified DNS aliases, and increments the runtime `CAPACITY` category without a network failure. A valid exchange still clears `isolated`; reachability and remote admission are separate facts. `IGNORED` and version 1's unknown admission do not assert capacity failure. Departure attempts count successful terminal exchanges normally rather than starting ordinary admission retries.

Member-derived candidates remain bounded by `max.members` (including self), but every configured nonlocal seed remains eligible even when the table is full. Candidate count is therefore at most `max.members - 1 + seeds.size()`, before alias coalescing; retry state is bounded by `max.members + seeds.size()` and removed when an endpoint ceases to be a candidate or active worker. This lets an unadmitted node try another configured seed while a full peer backs off. `LEFT` records are excluded from member-derived targets, while configured seeds remain bootstrap targets across restarts. Legacy remote `CAPACITY:` errors still classify separately and back off as failed exchanges.

Configured hostname seeds share a candidate, busy exclusion, and retry backoff with matching numeric member endpoints after identity verification. A successful correlated exchange establishes the alias, as does resolution to an already directly verified `ALIVE` member with the same expected server ID and port. Forwarded unverified records cannot establish aliases, and distinct numeric endpoints or expected IDs remain distinct. Alias state is bounded by the configured seed count and expires after 60 seconds, allowing a fresh hostname lookup after DNS changes. Recovering seeds retain their configured hostname as the dial target so cached alternative DNS answers remain usable. Selection itself performs no DNS or network I/O.

Never hold the membership lock across socket I/O. Never make an outbound request from `execute(exchange)`: the handler only validates, merges, snapshots, and returns. Otherwise two members can create chains of nested requests and shutdown dependencies.

Use immutable records and a short lock around table changes and snapshots. `ConcurrentHashMap` by itself does not make compound version comparisons and updates atomic. Deep-copy mutable metadata on input and when producing snapshots; `CommandRequest` only makes its outer parameter map unmodifiable.

Batch selection includes the local record and alternates recently changed records with a rotating scan of the entire retained table. Terminal departures lead the changed-record selection. Even a batch with one additional record alternates priority and scan opportunities, so continuous changes cannot starve the scan. Progress is separate for outgoing exchanges and incoming responses and advances only through records actually included. Continued fair exchanges repair missed updates even after the priority set drains. Repeated sending is intentional; the receiver ignores duplicate versions. Wire coverage at 128 records and bounded live-swarm recovery measurements are recorded below.

`GossipBatchSelector` maintains separate insertion-ordered departure and change queues for constant-time priority lookup, and reuses scan entries keyed by member identity. Both exchange directions select from `MembershipTable.peers()`, the table's records without the local one, rather than a full snapshot. Record sizes come from `MemberRecord`, which computes its exact UTF-8 size from its field lengths when it is built, so the selector keeps no size cache and reads sizes, under its lock, only for the records a batch considers. Refreshing the scan remains linear in the retained member count, but finds each member's entry at its position from the last call, looking it up only when the table's order has changed; it allocates only for members it has not seen before, and sweeps for removed members only when the table has shrunk.

The wire layer computes the complete envelope's size with an empty member array, including the sender, and reserves `Long.MAX_VALUE` connection-ID width for replies; each envelope's fixed overhead is measured once. Selection subtracts record sizes and one byte per array comma instead of serializing every growing batch. Outgoing requests are checked once encoded, before transmission; inbound replies fit by construction and are encoded only by the host, to send them. Selection bookkeeping is O(N + K) for N retained peers and K selected peers, and encodes nothing.

The initial scheme sends record batches directly rather than full digests followed by fetches. Each message remains bounded, while a complete sweep takes more exchanges as the swarm grows. Memory grows with the capped membership table. This design has no proven fixed convergence deadline. For substantially larger swarms, measure saturation and consider a digest/delta protocol or SWIM-style probes with piggybacked membership changes.

The implemented [`GossipTransport`](../plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip/GossipTransport.java) uses one blocking socket per connection attempt, which costs little on the virtual-thread workers that run exchanges, with defaults of a 5-second connect deadline, 10-second read-idle deadline, and 15-second total connect/write/read deadline after DNS resolution. Connects and reads use socket timeouts, each read waiting at most the read-idle limit and never past the total deadline, so the total deadline bounds trickling responses as well as idle ones. A blocking write has no timeout of its own, so a small virtual-thread watchdog closes the socket once the total deadline passes. Constructor overloads accept shorter durations for tests or embedded callers; no new CLI flags or timeout properties have been added. A fixed permit cap rejects overlapping work immediately instead of queueing it. With two permits, two stalled exchanges can still occupy the budget for several rounds; the scheduler continues heartbeat/maintenance work and skips outbound work until capacity is available.

Transport close rejects new calls, closes active sockets synchronously, wakes blocked I/O, and prevents uncommitted replies from merging after close. Interrupting a caller closes its socket, ends the exchange, and releases the permit: on a virtual thread at once, and on a platform thread, which an interrupt does not wake from blocking socket I/O, within 50 ms, when the watchdog sees the interrupt. Apart from that short-lived watchdog, the transport creates no threads; callers own and join theirs. Transport tests observe stalled-I/O cancellation and caller completion within a two-second deadline. Explicit Java API calls with hostnames still use platform DNS outside socket deadlines; their caller-owned threads have no promised overall lookup/join bound.

Scheduled hostname lookups instead run in-process, each on its own short-lived daemon thread; a concurrent request for the same host shares the lookup already running. The runtime supplies only numeric addresses to the transport. Lookup wait time is two seconds. On timeout, interruption or stop, the waiting caller is released at once and the lookup thread is interrupted. A lookup blocked inside the platform resolver cannot be interrupted, so it is abandoned: it finishes in the background and still counts toward the lookup bound until it returns. Numeric endpoints need no lookup. Successful answers are cached for 60 seconds in a cache capped at `max.members` hostnames, with at most 16 addresses per answer. The first address tried rotates across attempts; if a connection to it fails with a socket error (refused or unreachable), the same exchange tries the host's other addresses in order, within the same permit and deadline, before the peer is backed off. Connect timeouts and protocol or identity failures do not fall back, and an address that would reach this process is never tried. So one unreachable address, such as an unrouted IPv6 record, cannot fail every other exchange with a peer. Lookups count toward the worker budget and cannot exceed `max.inflight`. DNS timeout/cache settings are fixed implementation defaults. Numeric and hostname load profiles below exercise these caps; broader deployment performance measurements remain pending.

The host now bounds each request and HTTP header line at `WorkerRunnable.MAX_LINE_LENGTH`, currently 65,536 Java UTF-16 code units, excluding the line terminator. An overlong first line receives `INVALID_REQUEST`; an overlong HTTP header closes the connection. This is a decoded-character limit, not a UTF-8 byte budget, and it does not cap the total HTTP header section. Keep the proposed 32,768-byte exchange budget on complete serialized envelopes independently of this host limit.

Gossip responses now have a raw byte-counted limit before JSON parsing and strict UTF-8 decoding in the plugin transport. The general-purpose `TextMessageClient` still has an unbounded reply reader and is used for manual inspection, not the plugin's outbound exchanges. Before deployment beyond a trusted environment, add a raw request byte limit before host JSON parsing and cap aggregate HTTP header bytes; harden the general-purpose client too if it will contact untrusted servers. These remaining host/client transport tasks are separate from the gossip merge algorithm.

## Operational logging

The plugin writes diagnostic entries to standard error by default, so they appear in the server's terminal alongside host request logs. Each entry is one line with a UTC timestamp, severity, `plugin=gossip`, event name, local `serverId`, `bootId`, and `swarmId`. State and counters remain available through `status` independently of logging.

During shutdown, `status` retains `workerState="STOPPING"` until owned resource cleanup completes. Requesting stop alone no longer reports `STOPPED` while workers are still exiting. A deterministic regression holds cleanup open and verifies both states before and after release.

| Event | Severity | Details |
|---|---|---|
| `IDENTITY_CONFLICT` | WARN | Inbound owner-record conflicts, outbound correlation/identity failures, and reachable duplicate logical IDs. Includes direction, expected peer server ID, peer host/port, reason, and outbound message ID when available. |
| `ROUND_FAILED` | WARN | Unexpected scheduler exceptions, including the exception type and message. The round failure counter still advances for every failure. |
| `CLEANUP_FAILED` | ERROR | One entry per failed transport, DNS-resolver, or worker cleanup, with resource and exception details. The original aggregated cleanup failure still propagates. Repeated close does not log the same retained failure again. |
| `DEPARTURE_FINISHED` | INFO or WARN | One final entry per requested departure after cleanup, including state, targets, attempts, successes, failures, skipped, and cancelled counts. Successful completion without failed attempts and cancellation use INFO; other outcomes use WARN. Repeated `leave`/close calls do not duplicate it. Ordinary shutdown without a departure request emits no departure entry. |

Identity warnings share one 30-second monotonic-time window per plugin instance, across inbound/outbound peers; scheduler warnings have their own window. The first warning is immediate. Repeated warnings are suppressed, and the next permitted entry includes `suppressed=<count>` for the previous window. The limiter has constant-size state, with no per-peer log cache or queue. Cleanup and final departure entries bypass these warning windows. Neither successful rounds nor ordinary DNS/network failures produce per-round logs; their counts remain in `status`.

Message/error fields are bounded and escape control characters and delimiters to preserve one-line formatting. Raw requests, replies, and member metadata are not included. Inbound SPI calls do not expose the host message ID, so inbound entries use `messageId=-`; the host request log supplies that correlation. Runtime exceptions thrown by the diagnostic sink are ignored rather than replacing protocol or cleanup exceptions. Logging creates no threads and does not write while holding membership locks or the runtime state monitor.

Output is synchronous on the calling thread. Rate limiting bounds repeated warning volume, not output latency: a blocked standard-error destination can delay an exchange, the scheduler, or cleanup. Cleanup entries are written after the join/deadline bookkeeping, while close remains serialized, so the three-second join deadline does not bound console writes or total `stop()` latency. Existing sink tests cover exceptions from the sink, not a sink that blocks indefinitely.

Example entries (abbreviated context):

```text
[2026-10-15T09:00:00Z] WARN | plugin=gossip | event=IDENTITY_CONFLICT | serverId=Server-001 | bootId=<uuid> | swarmId=demo | direction=outbound | peerServerId=Server-002 | peerHost=127.0.0.1 | peerPort=9010 | messageId=<uuid> | reason=Gossip server ID mismatch | suppressed=0
[2026-10-15T09:00:01Z] INFO | plugin=gossip | event=DEPARTURE_FINISHED | serverId=Server-001 | bootId=<uuid> | swarmId=demo | state=COMPLETED | targets=2 | attempts=2 | successes=2 | failures=0 | skipped=0 | cancelled=0
```

Run logging, protocol, scheduler, and lifecycle coverage with terminal output:

```powershell
mvn -f plugins/gossip-plugin/pom.xml test "-Dtest=GossipLogTest,GossipCommandsTest,GossipTransportTest,GossipRuntimeTest,GossipDepartureTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

The packaged-JAR suite additionally captures real child-JVM standard error, verifies one departure outcome after repeated `leave` and host close, and prints `Gossip logging test PASSED` with the verified entry. Run it through the standalone project's `mvn verify` workflow below.

## Asynchronous departure (implemented)

`leave` accepts only the `operation` parameter. Under the runtime lock it publishes local terminal `LEFT` once, stops heartbeats and ordinary peer selection, interrupts existing ordinary workers, and wakes the scheduler. The handler performs no DNS, outbound socket work, or joining. Concurrent/repeated requests share this transition and return the current `departure` snapshot; they never create a second dissemination attempt. A stopped instance that never requested departure rejects `leave` with `INVALID_PARAMETERS`.

The runtime snapshots at most `max.members - 1` distinct expected-server/host/port targets, taking nonlocal, non-`LEFT` table members first and filling remaining slots with configured seeds. It excludes known self endpoints and filters resolved local aliases. Newly discovered endpoints do not expand this fixed snapshot. Each selected target receives at most one attempt, ignoring ordinary retry backoff. Attempts use existing serialization, byte/record limits, DNS isolation, and transport identity checks. Each scheduler pass starts at most `fanout` workers within `max.inflight`; there is no unbounded task queue.

The dissemination budget is five seconds of monotonic elapsed time from acceptance. A departure pass checks progress about every 25 ms. When all selected attempts finish, or the budget expires, the scheduler closes outbound resources, cancels remaining work, and joins its exchange workers using the existing cleanup procedure. The five seconds bound dissemination; process/socket cleanup can add the cleanup waits documented below. Shutdown cancels departure immediately through the same cleanup path. No departure RPC runs synchronously in `stop()`.

Both the `leave` reply and `status` contain a `departure` object with `state`, `budgetMillis` (5000), `targets`, `attempts`, `successes`, `failures`, `skipped`, and `cancelled`. Attempts count workers started, including DNS work; skipped attempts cover local aliases, duplicate resolved endpoints, or local saturation. Cancelled attempts had not completed when cleanup interrupted them. Targets never attempted before the deadline remain visible through `targets - attempts`.

| Departure state | Meaning |
|---|---|
| `NOT_REQUESTED` | No departure transition has occurred. |
| `DISSEMINATING` | Terminal state is published; attempts or cleanup are in progress. |
| `COMPLETED` | All selected targets were processed and cleanup finished. Individual attempts may have failed or been skipped; an isolated node completes with zero attempts. |
| `EXHAUSTED` | The dissemination deadline expired; outstanding work was cancelled and cleanup finished. |
| `CANCELLED` | Instance shutdown interrupted the departure attempt and cleanup finished. |
| `FAILED` | The scheduler failed during departure or cleanup failed; cleanup errors remain available to subsequent stop calls. |

Success counts validated peer replies, and completion describes the local attempt. Neither guarantees receipt by the whole swarm. The scheduler publishes final departure state after worker cleanup and then exits. `QUIESCED` retains the local table and command handler: inspection, incoming exchanges, and other host commands remain usable. New explicit `exchangeWith` calls are rejected once leaving begins. Rejoining requires a new plugin instance and boot ID; stale `ALIVE` versions cannot revive the departed boot ID. Invoke `leave` before shutdown and inspect its outcome when voluntary departure is desired.

## Host lifecycle prerequisite (implemented)

The SPI now has optional `start()` and `stop()` hooks alongside `execute()`. `PluginRegistry.close()` invokes stop as a fallback before closing the external class loader. Plugins must implement their own resource shutdown; closing a loader does not stop a scheduler, outbound socket, UDP receiver, or plugin-created thread. Daemon threads and a JVM shutdown hook do not solve instance shutdown in a process hosting multiple servers.

The backward-compatible default method is:

```java
default void stop() throws Exception { }
```

The implemented host contract is:

- `PluginRegistry` discovers and validates providers sequentially, then initializes them concurrently on virtual threads. It waits for all startup tasks before reporting success or failure and serializes lifecycle calls so startup and stop hooks never overlap. It tracks providers whose startup has been entered, including a provider that throws after allocating resources. Its idempotent `stop()` invokes their hooks in reverse recorded startup-entry order, continues after hook exceptions or nonfatal errors, and aggregates failures. Constructors should avoid acquiring resources.
- `ServerInstance.requestStop()` is an idempotent instance stop-request path that first closes the main listener, then quiesces plugins, then stops the admin monitor. Administrative `shutdown` uses this path. Already accepted handlers retain access to plugin state while draining. On Windows, the OS can briefly queue a connection while the listener descriptor closes; tests assert that no request is served during quiescing rather than requiring immediate connection refusal.
- `ServerInstance.close()` invokes that stop-request path, joins listener tasks, invokes request draining, and then closes the registry/class loader. The main listener task also drains in its `finally` block. Each `ThreadPooledServer.drain()` call waits up to the configured timeout (default 5 seconds), interrupts remaining workers with `shutdownNow()`, and waits up to that timeout again. Interrupting virtual threads blocked on socket I/O closes their sockets. A handler that ignores interruption can outlive these waits, so this is not a guarantee that arbitrary plugin execution has finished before loader close. Preserve cleanup through `finally` even when a hook fails.
- Registry close also invokes its idempotent stop path as a fallback. Failed startup follows the same cleanup path, including hooks already entered. Every discovered provider still has its loader resources released.

The admin command runs inside the monitor task. Its stop-request path must not join that same task or call `listeners.close()` from inside it. Preserve the current console behavior: `shutdown` requests a stop; `quit` or end-of-input reaches instance close and completes joins and loader cleanup.

The gossip stop hook prevents new rounds, interrupts its scheduler and exchange workers, closes active transport sockets, releases and interrupts DNS lookups, and joins every owned runtime thread. Joining uses a shared three-second deadline measured from the beginning of cleanup; DNS lookup threads additionally get up to one second each to end, and one blocked in the platform resolver is abandoned rather than reported as a failure. This is a bounded cleanup attempt, not a hard real-time guarantee for operating-system calls or synchronous diagnostic output. Failure propagates through the host's aggregated cleanup errors and reports `CLEANUP_FAILED`. Repeated close is serialized and preserves any cleanup failure. The membership table remains available to already accepted handlers. Optional UDP discovery must later join this same resource lifecycle. Keep handlers short and responsive to cancellation.

The explicit `leave` operation publishes a terminal local record and schedules the bounded asynchronous attempt described above. `stop()` cancels that attempt, closes resources, and joins owned threads. Other nodes eventually time out a process that exits without delivering `LEFT`.

Host tests exercise normal close, administrative shutdown, console quit/end-of-input, partial initialization, concurrent startup failures, stop-hook failures, concurrent shutdown, instance isolation, and loader availability for accepted commands that complete during draining. They also cover idle/trickling clients and main-listener closure before plugin quiescing. A real external provider owns a worker blocked on UDP receive; its stop hook closes the socket and joins the worker, and its JAR can be deleted after host cleanup on Windows. Stop hooks run synchronously without a host-enforced deadline. Gossip tests additionally stop scheduled/departure work during stalled TCP and DNS calls, roll back after another provider fails while an exchange is active, and verify real console quit, end-of-input, and admin-shutdown cleanup. Packaged tests delete the gossip JAR after hostname-seeded convergence/departure and after console cleanup while each child JVM remains alive. Optional discovery receiver cleanup remains pending.

## Suggested plugin classes

The implementation uses package `dev.mars.plugins.gossip`, with production sources under `plugins/gossip-plugin/src/main/java/dev/mars/plugins/gossip` and tests under `plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip`. Tests start host servers only through the host-owned test-support JAR: `dev.mars.testing.TestServer` for in-process servers and `dev.mars.testing.HostProcess` for separate host JVMs that load the packaged plugin from `--plugins-dir`. `dev.mars.plugins.gossip.fixture` holds only `DnsProcessFixture`, which imitates the plugin's own DNS helper. Service registration names `dev.mars.plugins.gossip.GossipPlugin`. Embedded Java callers must update their imports and rebuild against the renamed classes. The command instruction remains `gossip`.

| Class | Responsibility |
|---|---|
| `GossipPlugin` | Implements the SPI, owns a runtime instance, and dispatches operations. |
| `GossipConfig` | Parses and validates configuration for the resolved server ID. |
| `GossipRuntime` | Coordinates rounds, lifecycle, in-flight limits, and diagnostics. |
| `GossipMetrics`, `GossipLog` | Share per-instance counters and rate-limited console diagnostics across runtime, commands, and transport. |
| `MembershipTable` | Validates merges, versions, observations, expiry, and snapshots. |
| `MemberRecord` and `MemberKey` | Immutable owner data and compound identity. |
| `PeerSelector` | Random fanout, fair recovery opportunities, and retry backoff. |
| `GossipTransport` | Owns bounded TCP I/O, deadlines, cancellation, reply validation, and direct-success merges. |
| `GossipDnsResolver` | Bound, cached, timed-out scheduled hostname resolution, with every address available for connection fallback. |
| `GossipWire`, `GossipCommands`, `GossipBatchSelector` | Encode/validate gossip data and envelopes, dispatch local operations, and rotate bounded batches. |
| `DiscoveryReceiver` | Optional UDP candidate ingestion; never a source of authoritative membership. |

Inject a monotonic clock, random source, and transport boundary into the runtime to make timing and selection tests repeatable. Supply real defaults in the public no-argument constructor required for service discovery. Avoid depending on startup hooks of other plugins: `PluginContext` does not expose their instances or the registry.

## Packaging and running a local swarm

The [gossip project](../plugins/gossip-plugin/pom.xml) follows the existing [multiply plugin example](../plugins/multiply-plugin/pom.xml): a separate Java 27 Maven project depends on `dev.mars:socket-client-server:1.0-SNAPSHOT` with scope `provided` and packages only the plugin implementation and its own resources. Host SPI classes are supplied by the host.

The project includes this service file:

```text
src/main/resources/META-INF/services/dev.mars.plugin.CommandPlugin
```

Its contents are:

```text
dev.mars.plugins.gossip.GossipPlugin
```

Use a dedicated plugin directory containing the gossip JAR and any dependencies it needs. Built-in providers are inherited through the host's parent class loader. Loading duplicate gossip providers fails startup because their instruction names conflict. Restart members to load a new plugin JAR; there is no hot reload.

Use Java 27 for Maven and the server processes. Build and install the host API, verify the standalone plugin, and copy the host's runtime dependencies:

```powershell
mvn clean install
mvn -f plugins/gossip-plugin/pom.xml clean verify
mvn dependency:copy-dependencies "-DincludeScope=runtime"
```

`mvn clean install` also installs the host's test-support JAR, which the plugin's tests use. To build and verify the host and every plugin together, run `mvn -f plugins/pom.xml clean verify`.

The project builds `plugins/gossip-plugin/target/gossip-plugin-1.0-SNAPSHOT.jar`. The local demo files [`gossip-001.properties`](../config/gossip-001.properties), [`gossip-002.properties`](../config/gossip-002.properties), and [`gossip-003.properties`](../config/gossip-003.properties) share a swarm ID and seed list. Load the JAR directly from its target directory, or copy it to a dedicated runtime folder. Start each process from the repository root in its own PowerShell terminal; these seed-only demo commands disable LAN announcements:

```powershell
java "-Ddev.mars.announce=false" "-Dgossip.config=config/gossip-001.properties" -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-001 --port 9000 --admin-port 9001 --plugins-dir plugins/gossip-plugin/target
java "-Ddev.mars.announce=false" "-Dgossip.config=config/gossip-002.properties" -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-002 --port 9010 --admin-port 9011 --plugins-dir plugins/gossip-plugin/target
java "-Ddev.mars.announce=false" "-Dgossip.config=config/gossip-003.properties" -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-003 --port 9020 --admin-port 9021 --plugins-dir plugins/gossip-plugin/target
```

All three advertise `127.0.0.1` for this local demonstration. On different machines use reachable addresses and update the seeds. Automatic TCP allocation also works because the startup context supplies bound ports, but static seed files need a stable entry point or an independent discovery mechanism.

At each console, inspect the plugin using the existing `send` command:

```text
send {"instruction":"gossip","parameters":{"operation":"status"}}
send {"instruction":"gossip","parameters":{"operation":"members","limit":32}}
```

Each process initially reports itself, then automatically contacts the configured seeds and discovers the others. Heartbeats advance even before bootstrap succeeds. `members` returns a bounded page with local assessments and observation ages; pass a returned `nextCursor` as `cursor` to continue. Reachable duplicate logical IDs appear as conflicts rather than one healthy entry. Use `status` to inspect isolation, rounds, retry failures, and active work.

Stop Server-002, observe local suspicion/dead assessments, then restart it with the same server ID. Verify that the new boot ID becomes reachable and the old boot ID ages out. Disconnect and reconnect a member to observe recovery. To depart voluntarily, send `leave` and inspect `status` until `departure.state` is final and `workerState` is `QUIESCED` (or reports cleanup failure), then use `quit`. Repeat the status command as needed before quitting:

```text
send {"instruction":"gossip","parameters":{"operation":"leave"}}
send {"instruction":"gossip","parameters":{"operation":"status"}}
quit
```

## Validation and acceptance criteria

Use JUnit and the existing real-socket/external-JAR approach. Mockito and replacement mocking frameworks are prohibited. Purpose-built clock, random, and transport fakes are suitable for algorithm tests; they do not replace tests of serialization, TCP exchange, service discovery, and resource cleanup.

| Test area | Required observations |
|---|---|
| Merge behavior | Higher heartbeats win; duplicates do not refresh freshness; conflicts are rejected; `LEFT` dominates its boot ID; a restart creates a distinct identity. |
| Failure assessment | Monotonic elapsed time drives suspicion; one failed request does not declare failure; stale forwarded data cannot keep a peer alive; successful probes restore local reachability. |
| Wire behavior | Actual JSON requests and replies preserve UUIDs, numeric types, Unicode, null handling, complete-envelope byte limits, and server/swarm identity checks. Distinguish UTF-8 byte budgets from the host's decoded-character line limit. Invalid input leaves the table unchanged. |
| Three-member swarm | Real servers converge from seeds; built-in `echo`, HTTP, and admin status continue working during exchanges. |
| Larger swarm | Test at the configured membership cap with multiple batches. Every record receives fair transmission opportunities and outbound concurrency stays bounded. |
| Loss and partitions | A protocol fixture or controllable TCP relay drops, delays, or reorders complete exchanges. After reconnection and fair exchanges, current owner records converge. |
| Bootstrap edge cases | Seeds start late, some are down, UDP is unavailable, same-host instances share a seed list, and gossip versions or swarm IDs disagree. |
| Lifecycle | Failed startup, `quit`, end-of-input, and admin `shutdown` stop plugin work. Idle UDP receive and stalled outbound requests terminate within the plugin's documented bound, independently of the host request drain timeout. Accepted handlers can still use retained state during draining. |
| Packaging | Load a real external JAR, execute gossip on virtual threads, close instances, and delete the JAR on Windows after cleanup. |
| Isolation | Multiple instances in one JVM have distinct tables, boot IDs, configuration, and worker lifecycles. Closing one leaves the others working. |
| Test network hygiene | Plugin tests and spawned JVMs disable built-in LAN announcements. Discovery fixtures use controlled UDP endpoints and do not broadcast onto real interfaces. |

Use fake elapsed time for precise expiry tests and eventual assertions with bounded deadlines for live convergence. Record observed convergence times rather than promising logarithmic timing from the general gossip model. Verify startup rollback after a later provider fails, so cleanup covers gossip workers that were already scheduled.

The plugin currently has 232 unit/real-socket tests and eight packaged-JAR integration test cases, all passing on Java 27. The latest verification on 2026-10-15 ran `mvn -B -f plugins/gossip-plugin/pom.xml verify "-Dmaven.test.redirectTestOutputToFile=true"` after admission reporting and version compatibility were added. Test output from that run is under `plugins/gossip-plugin/target/surefire-reports/` and `plugins/gossip-plugin/target/failsafe-reports/`; use `false` for terminal output. The unchanged host suite previously passed all 327 tests with `mvn -o test` during departure/lifecycle completion. The rebuilt JAR contains `dev/mars/plugins/gossip/GossipPlugin.class`, registers `dev.mars.plugins.gossip.GossipPlugin`, and contains no classes outside `dev/mars/plugins/gossip/`. Root `mvn test` does not run the separate plugin project; use its `mvn verify` command above.

The initial clean run during admission work hit the existing six-node hostname profile's 20-second convergence deadline. Its focused rerun passed (about 13 seconds for hostname convergence), and the subsequent full verification passed without changing that deadline. This remains a variable live-network/DNS test rather than a fixed convergence guarantee.

In addition to configuration and deterministic-clock model coverage, command/transport tests verify real two-node exchanges, Unicode/null metadata, exact envelope byte boundaries, oversized UTF-8 replies, strict paging/cursors, independent rotating batches, identity/version/swarm failures, remote capacity errors, self-contact aliases, concurrency caps, read-idle and trickle deadlines, interruption, and close cancellation. Runtime tests cover jitter/backoff, fair priority/scan batches, heartbeat/maintenance while saturated, late seeds, three-node convergence, restart recovery, unchanged echo/HTTP/admin behavior, incompatible swarms, active-exchange startup rollback, and stalled TCP/DNS cleanup. An in-process lookup fixture exercises lookup caching, address rotation, timeout, empty and failed replies, and cancellation on stop.

Admission coverage checks committed skip counts for inbound/outbound merges, duplicates and terminal suppression, rollback on invalid/conflicting batches, sender-specific outcomes when only forwarded records are skipped, and active/retained capacity limits. Codec and real transport tests reject missing/malformed hints without mutation or permit leaks. UTF-8 byte-boundary tests cover both gossip versions in each direction. Legacy fallback tests check both recognized version errors, fresh correlation IDs, unchanged selected records, successful metrics, rejection of uncorrelated/unrelated errors and unsolicited version changes, and a shared overall deadline. Scheduler/selector tests verify that a full seed backs off exponentially while an alternate configured seed remains eligible even at local capacity; legacy `CAPACITY:` errors explicitly increment only the capacity failure category. The packaged provider verifies version 1 shape and version 2 `ADMITTED`/`CAPACITY` replies with the real runtime JAR.

Run these regressions with terminal output:

```powershell
mvn -f plugins/gossip-plugin/pom.xml test "-Dtest=GossipWireTest,MembershipTableTest,GossipCommandsTest,GossipTransportTest,PeerSelectorTest,GossipRuntimeTest,GossipBatchSelectorTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

Verified socket/compatibility/scheduler outcomes print `Admission hint test PASSED`, `Admission compatibility test PASSED`, and `Admission seed test PASSED`. The packaged assertions run through the standalone project's `mvn verify` workflow.

Departure tests verify concurrent/idempotent requests, terminal publication, replacement of an active ordinary exchange, successful and failed attempts, deadline cancellation of actual stalled TCP and DNS work, cleanup-failure reporting, retained handlers after quiescing, stale-`ALIVE` suppression, admin cancellation during departure, and repeated close. A real TCP regression also forwards a departed origin's last `ALIVE` record back to it: the exchange succeeds, replies with `LEFT`, and updates the stale peer. Model tests cover older/equal local `ALIVE` replays, valid peer updates in the same batch, and transactional rejection of forged local records. The packaged fixture excludes provider implementation classes from its parent classpath and verifies actual ServiceLoader handling, automatic three-node hostname bootstrap and departure propagation, paginated inspection, instance isolation, startup rollback, and real console quit/end-of-input/admin-shutdown cleanup. JAR deletion is checked while the Windows JVM remains alive after cleanup.

Run only the departed-origin TCP regression with visible output:

```powershell
mvn -f plugins/gossip-plugin/pom.xml test "-Dtest=GossipDepartureTest#departedOriginAcceptsPeerExchangeForwardingItsLastAliveRecord" "-Dmaven.test.redirectTestOutputToFile=false"
```

It prints `Departure replay test PASSED | origin=Departing | peer=Peer | stale=ALIVE | reply=LEFT` after checking the result.

Expose round counts, exchange successes/failures, last successful exchange age, in-flight count, rejected records, identity conflicts, capacity usage, and membership counts by local assessment. Avoid logging every member on every round. These measurements distinguish isolation, transport failure, invalid peers, and an overloaded batch budget.

### Capacity, fairness, and recovery test profiles

The following profiles exercise selector work, capacity, fairness, and recovery. Network profiles use real JSON/TCP traffic and host backends:

- [`GossipCapacityTest`](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipCapacityTest.java) fills the receiver to 128 active members with retained caps of 128 and 256. Repeated mixed batches advance known owner versions and metadata while skipping excess identities, in either member order. A terminal record frees active capacity within the same batch regardless of order, while retained-identity limits remain enforced. Concurrent admissions compete for the final slot and both batches' known-member updates commit. Model tests additionally verify ignored counts, retained-identity recovery priority, preserved suppression after skipped recovery, and whole-batch rollback on malformed records or conflicts.
- [`GossipBatchSelectorTest`](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipBatchSelectorTest.java) checks priority/scan fairness, byte truncation without skipped progress, UTF-8 request/reply boundaries including commas, removal of departed identities, concurrent selection without duplicate members, and that sizes are read only for the records a batch considers. Run it with `mvn -f plugins/gossip-plugin/pom.xml test "-Dtest=GossipBatchSelectorTest,GossipPropagationTest" "-Dmaven.test.redirectTestOutputToFile=false"`.
- [`GossipPropagationTest`](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipPropagationTest.java) transfers a 128-record table with eight-record/3072-byte limits. Four records change on every exchange and one later becomes `LEFT`. Over 254 exchanges, both request and response frames cover all 128 keys, contain no duplicates, and remain within both budgets; the byte budget truncates frames below the record cap. Separate fault coverage drops a complete request and reply, delays an older reply until a newer heartbeat has committed, and verifies recovery without version rollback or permit leakage.
- [`GossipRecoveryLoadTest`](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipRecoveryLoadTest.java) partitions six scheduled nodes into two groups of three until cross-group records are locally `DEAD`. A departure propagates only within its connected group. Healing restores current owner heartbeats and carries `LEFT` across the partition; stale `ALIVE` still cannot revive that boot ID. Load profiles run 12 numeric-address nodes and six hostname-address nodes at their configured membership caps, each with three-record/4096-byte frames, fanout two, and two in-flight workers. They sample worker/helper/transport and membership counts, stall every outbound worker, verify that heartbeats continue, and close all instances while saturated.

The shared [`GossipTestNetwork`](../plugins/gossip-plugin/src/test/java/dev/mars/plugins/gossip/GossipTestNetwork.java) advertises relay endpoints, so forwarded member addresses also traverse fault controls. It checks reply correlation and actual frame sizes through the production transport/host path. Cleanup verifies zero plugin workers, DNS helpers, transport calls, and relay sockets, then joins the actual relay threads. The virtual-thread executor's termination signal alone can precede final thread exit, so the fixture explicitly joins its owned threads too.

Propagation and recovery runs print `GOSSIP_MEASURE` observations; capacity cases print `Capacity test PASSED`. Run these profiles with terminal output:

```powershell
mvn -f plugins/gossip-plugin/pom.xml "-Dtest=GossipCapacityTest,GossipPropagationTest,GossipRecoveryLoadTest" test "-Dmaven.test.redirectTestOutputToFile=false"
```

The earlier capacity/seed-selection full-suite run on 2026-10-15 produced these observations (milliseconds):

| Profile | Convergence | Recovery | Saturated cleanup | Other observations |
|---|---:|---:|---:|---|
| 128-record wire transfer | 646 | — | — | All 128 keys appeared in each direction over 254 exchanges; `LEFT` was sent and returned in its publication round. |
| 12 scheduled nodes, numeric endpoints | 6360 | — | 8 | Peak 2 workers and 2 transport calls per node; 24 total workers; no DNS helpers. |
| 6 scheduled nodes, hostname endpoints | 2865 | — | 9 | Peak 2 workers, 2 helpers, and 2 transport calls per node; 12 total workers. |
| 6 scheduled nodes, partition/heal | 373 | 181 | — | 102 intercepted requests; current heartbeats recovered and terminal state stayed suppressed. |

These are bounded Windows/Java 27 loopback integration profiles with accelerated timing: rounds 40 ms, heartbeats 120 ms, suspicion 800 ms, local death 1600 ms, and retention 20 seconds. The 128-record test measures initial transfer through explicit exchanges. Load-profile convergence includes server startup and automatic seed bootstrap; the partition profile measures bootstrap after all servers have started, then recovery from the moment of healing. Cleanup measures concurrent instance close and release of active relay connections, before fixture listeners are closed. The load tests hold all outbound workers stalled for at least three heartbeat advances before closing. Numeric and hostname profiles use different swarm sizes and are not a controlled estimate of DNS overhead. These timings are observations, not convergence guarantees. Default production intervals remain unchanged. A 128-process swarm, long-running load, and partitions beyond terminal retention still require deployment-specific measurement.

## Implementation sequence and task status

The checked tasks below are implemented in the current repository. Bounded capacity, fairness, partition, and load profiles now pass. The next optional feature is UDP discovery; seed-based operation remains the baseline.

- [x] **Host lifecycle prerequisite:** Optional stop hooks, concurrent startup rollback, administrative stop requests, cleanup after hook failures, and loader cleanup are implemented and covered by host tests.
- [x] **Baseline host request hardening:** Main-listener closure before plugin quiescing, bounded drain with worker interruption, per-line character limits, a concurrent connection cap, transient accept retries, and test announcement suppression are implemented. Byte-counted transport limits remain pending below.
- [x] **Plugin scaffold and configuration:** The separate Maven project, `GossipPlugin` provider, service registration, per-instance configuration, and local demo configuration files are implemented. Tests and spawned JVMs disable real announcements. Acceptance verified: real external-JAR loading, isolated instances, correlated local status, invalid-configuration cleanup, and JAR deletion after instance cleanup while the Windows JVM remains alive.
- [x] **Package structure:** Production and test sources use `dev.mars.plugins.gossip` and `dev.mars.plugins.gossip.fixture`, with matching directories, imports, documentation links, helper launches, and `dev.mars.plugins.gossip.GossipPlugin` service registration. No test class declares a host package. Acceptance verified: a clean build passes the standalone suite and the rebuilt JAR contains only plugin classes.
- [x] **Membership model:** Immutable records, exact numeric/identity/metadata validation, atomic merges, local observations, capacity rules, compact suppression, and owner publication are implemented. Acceptance verified with deterministic-clock and concurrent tests: duplicate freshness, direct recovery, version conflicts, terminal `LEFT`, distinct restart identities, bounded retention/capacity, and rejection without partial mutation.
- [x] **Command and transport path:** `exchange`, paginated `members`, and diagnostic `status` are implemented with complete canonical-envelope accounting, bounded raw response reading, reply identity checks, and independent batch cursors. Real two-node and packaged-JAR tests verify serialization, malformed replies, version/swarm mismatches, capacity errors, paging, deadlines, and cancellation. The no-thread transport owns its channels/selectors; DNS is explicitly outside socket cancellation/deadlines. Raw pre-parse host request bytes remain a separate deployment prerequisite below.
- [x] **Scheduled seed-based runtime:** Jittered rounds, owner heartbeats, bounded fanout/in-flight workers, priority/fair batches, seed backoff, recovery probes, local assessments, expiry, and metrics are implemented. Scheduled hostname lookups are bounded, cached and timed out; stop cancels DNS/TCP work and joins owned threads. Acceptance verified: three real nodes and a packaged hostname-seeded swarm converge, late/down seeds recover, restarts are distinct, closing one instance leaves others working, and stalled exchanges or startup rollback leave no plugin workers behind.
- [x] **Departure and lifecycle completion:** Idempotent asynchronous `leave` publishes terminal `LEFT`, stops heartbeats/ordinary rounds, attempts a bounded target snapshot within five seconds, and exposes completion/cancellation/failure counters. Acceptance verified: terminal propagation and stale-record suppression, retained handlers after quiescing, active DNS/TCP deadline cancellation, admin cancellation during departure, startup rollback, real console quit/end-of-input/admin-shutdown cleanup, repeated close, and packaged JAR release before JVM exit. Acceptance includes the packaged-plugin suite and the 327-test host suite.
- [x] **Capacity, fairness, and partition validation:** Tests verify known updates at saturation, bounded admission at 128 members, complete request/response transmission opportunities during churn, dropped/delayed/reordered exchange recovery, six-node partition healing with terminal suppression, and 6/12-node scheduled load at configured caps. Seed-selection tests verify healthy-seed ordinary fanout, hostname/numeric alias handling, shared backoff/busy state, alias expiry and recovery, distinct identities/endpoints, and delayed-DNS deduplication across a whole round. Worker/helper/transport counts remain bounded; saturated shutdown releases plugin and relay resources. Timing observations and the tested profiles are documented above. Broader deployment benchmarks remain necessary before tuning defaults.
- [ ] **Optional UDP discovery:** Add only after seed-based operation passes the preceding checks. Acceptance: controlled datagrams only queue validated TCP handshakes; unavailable UDP, lost announcements, and same-host port contention leave seeds working. Include receiver cancellation in the existing lifecycle tests.
- [ ] **Beyond-trusted-LAN prerequisites:** Add raw host request byte limits before parsing, aggregate HTTP header limits, authenticated membership/transport, and an advertised-destination policy before dialing forwarded candidates. Harden the general-purpose client's response reader if used with untrusted servers. Gossip transport response bytes are already bounded before parsing. Test oversized/slow input, identity failures, and denied destinations. These prerequisites remain separate from canonical plugin request budgets and `swarmId` validation.

### Next task: optional UDP discovery

The seed-based path and bounded validation profiles are complete. If optional LAN discovery is added, keep it within the existing instance lifecycle:

1. Accept bounded, validated datagrams as candidate hints and queue only bounded TCP handshakes; admit members after the existing identity/version/swarm checks.
2. Preserve seed bootstrap when UDP is unavailable, announcements are lost, or same-host port binding conflicts occur.
3. Close the receiver and join its worker during stop, departure, and startup rollback.
4. Use controlled UDP fixtures and keep real LAN announcements disabled throughout tests. Verify malformed/oversized datagrams, duplicate candidates, lifecycle cleanup, and seed fallback before enabling `discovery.enabled=true`.

A `swarmId` separates accidental configurations but is not an authentication credential. The host currently supplies neither TLS nor authenticated peer identity, and the plugin cannot infer the socket's remote address from `execute(Map)` alone. Keep administrative ports restricted because the existing shutdown command is unauthenticated.

Once membership works, application features can select reachable peers from its local view. Keep gossip records descriptive: receiving an instruction name as metadata must never execute that instruction. Distributed task routing, event delivery, and deduplication require explicit additional protocols.
