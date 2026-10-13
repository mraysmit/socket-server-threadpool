# Gossip protocol plugin for a swarm of socket servers

| Document metadata | Details |
| --- | --- |
| Author | Mark Andrew Ray-Smith, Cityline Ltd |
| Status | Proposed plugin architecture and implementation plan |
| Last updated | 2026-10-13 |
| Applies to | Socket Client Server 1.0-SNAPSHOT |

## Purpose and scope

Implement a plugin that lets instances of this server discover one another and maintain a local view of the swarm. Each instance periodically exchanges membership information with a few peers through the existing JSON command socket. Every member can initiate and answer exchanges; seed servers help members find the swarm but have no permanent coordinating role.

The first implementation should gossip membership and small, owner-controlled metadata, such as a node's supported application instructions. It should expose the local membership view for inspection. Application event delivery can be added later, with its own retention and deduplication rules.

This is an implementation proposal. The repository currently supplies the transport and plugin infrastructure, but has no gossip implementation. Most of the design fits inside an external plugin JAR. A small host lifecycle extension is necessary to manage periodic work during shutdown and failed startup.

The intended first deployment is a small swarm on a trusted LAN, with mutually reachable TCP endpoints and unique configured server IDs. Membership is eventually consistent: nodes can temporarily disagree, and a timeout indicates suspected unreachability rather than proof that a process has stopped. Membership alone must not authorize exclusive ownership, elect a unique leader, or guarantee that a command executes once.

## Existing server features to reuse

| Existing feature | Use in the gossip plugin |
|---|---|
| [`CommandPlugin`](../src/main/java/dev/mars/plugin/CommandPlugin.java) | One provider owns startup, the command handler, membership state, and periodic work. |
| [`PluginContext`](../src/main/java/dev/mars/plugin/PluginContext.java) | Supplies the local server ID and actual main/admin ports, including ports allocated with `0`. |
| [`PluginRegistry`](../src/main/java/dev/mars/plugin/PluginRegistry.java) | Discovers the external provider through `ServiceLoader`, checks instruction uniqueness, and normalizes results to JSON. |
| [`WorkerRunnable`](../src/main/java/dev/mars/worker/WorkerRunnable.java) | Runs incoming gossip commands on the existing virtual thread for each connection. |
| [`TextMessageClient`](../src/main/java/dev/mars/client/TextMessageClient.java) | Sends one JSON command per connection and validates response IDs and the expected server ID. |
| [`CommandProtocol`](../src/main/java/dev/mars/protocol/CommandProtocol.java) | Provides versioned envelopes, UUID correlation, parameter objects, and typed errors. |
| [`AddressBroadcastPlugin`](../src/main/java/dev/mars/plugin/builtin/AddressBroadcastPlugin.java) | Offers optional LAN bootstrap hints through its existing UDP announcements on port 9002. |
| [`ServerInstance`](../src/main/java/dev/mars/server/ServerInstance.java) | Binds both ports before plugin startup and owns shutdown and plugin loader cleanup. |

No additional TCP listener is needed. Send gossip to the main command port; the administrative port remains for the existing status and shutdown commands. The HTTP and legacy text paths retain their current roles.

The protocol follows periodic peer exchanges and bounded information propagation described in [Wikipedia's gossip protocol overview](https://en.wikipedia.org/wiki/Gossip_protocol). Failure detection and dissemination should remain separate responsibilities, following the useful distinction in the [SWIM paper](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf). The simpler heartbeat design below is not an implementation of SWIM and does not inherit its performance guarantees.

## One provider and one instruction

Register one public provider class, `example.gossip.GossipPlugin`, with instruction name `gossip`. Use an `operation` parameter to select behavior:

| Operation | Purpose | Network activity inside the handler |
|---|---|---|
| `exchange` | Merge a peer's records and return a bounded selection of local records. | None. |
| `members` | Return a bounded, paginated local membership snapshot and diagnostics. | None. |
| `status` | Report local identity, worker state, last round, and counters. | None. |
| `leave` | Schedule a bounded departure announcement and stop initiating exchanges. | Queue work only; return immediately. |

Each discovered provider instance is shared by concurrent requests. Keep its runtime state in instance fields. Do not use a static singleton: tests and Java applications can start several `ServerInstance` objects in one JVM.

Using one instruction avoids registering multiple providers that accidentally create separate membership tables. The current SPI assigns one instruction to each provider. Extra public commands would require a deliberate shared-runtime design; the operation field avoids that requirement.

Use `InvalidParametersException` for invalid operations, incompatible gossip versions, wrong swarm IDs, and malformed records. These map to the existing `INVALID_PARAMETERS` reply. Unexpected execution failures use the host's existing `PLUGIN_ERROR` behavior. A valid exchange returns its data as the normal `result` object.

## Plugin configuration and identity

Read a plugin-specific properties file from a JVM property such as `-Dgossip.config=...`. The host currently has no plugin configuration object or arbitrary plugin command-line flags. Do not add imaginary `--gossip-*` options to `ServerOptions`.

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

These are proposed starting values for a small LAN swarm, not measured convergence or failure-detection guarantees. Tune them using the tests below. A seed entry contains an expected server ID, a host, and a main TCP port. Use a proper endpoint parser if IPv6 is supported.

Use `PluginContext.port()` for the advertised main port. A configured `advertise.host` supplies the reachable address because `PluginContext` has no host/interface field. Loopback works only for processes on the same machine; on separate machines configure a LAN address or resolvable hostname reachable by all members. The existing listeners bind to wildcard addresses, but firewall and routing rules must still allow access.

Configuration is per plugin instance. A single JVM property can select a file for a normal one-server process. For multiple instances in one JVM, let the file contain sections keyed by `context.serverId()`, or supply configuration through an explicit constructor in the test harness. Do not cache the first process-wide configuration as shared mutable state.

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

Finite retention cannot prevent every stale resurrection after arbitrarily long partitions. Old records can briefly reappear after their tombstones expire, then age out when no fresh heartbeat or direct reply arrives. Cap retained identities, including old boot IDs, at `max.records`; cap the active candidate/member set at `max.members`. Reject new admissions with a clear capacity error rather than allocating without limit. Existing members must still be able to exchange updates when capacity is full.

## Bootstrap and discovery

Configured seeds are the baseline bootstrap mechanism. On startup, add the local record and seed candidates. Exclude the current local endpoint, retry unavailable seeds with jitter and bounded exponential backoff, and keep the local server usable if no seed is reachable. Advertise `isolated=true` until an exchange succeeds. Each connected component needs at least one reachable seed or another explicit introduction to join the others.

Use `TextMessageClient(host, port, expectedServerId)` when contacting a configured seed. On first contact with an unverified discovery candidate, validate the returned outer server ID against its claimed identity and validate that the result's sender has the same identity. Thereafter pin the expected server ID. Also validate boot ID and swarm ID in the result. Identity checks prevent accidental cross-connection mistakes; without authentication they do not establish trust.

The existing address broadcaster is an optional source of candidate endpoints:

```json
{"version":1,"type":"SERVER_STARTED","serverId":"Server-001","ip":"192.168.1.10","port":9000,"adminPort":9001}
```

An optional receiver listens on UDP port 9002, validates a small datagram, and queues a TCP handshake. Do not admit a member solely because it appeared in a datagram. Existing announcements carry no `swarmId` or `bootId`; the TCP exchange supplies and checks them. Ignore the admin port for gossip transport.

There are three reasons seeds remain necessary:

- Announcements happen once at startup. They are lost if no receiver is listening and do not cross ordinary subnet boundaries.
- Plugin startup hooks run before TCP listener tasks begin. A discovered endpoint can be bound but not yet ready to answer; retry the handshake asynchronously.
- Several server processes on one host cannot portably each bind UDP 9002 and receive every announcement. Socket reuse is not a portable fanout mechanism. Use seeds for this layout, or an optional single host discovery agent that forwards candidates to local plugins.

The built-in broadcaster runs independently of this plugin and may announce before its receiver starts. Do not depend on class-name startup order to make discovery reliable. Optional receiver bind failures should disable that discovery path and leave seed-based startup working.

## Exchange protocol

Keep host protocol version `1` unchanged. Put a separate `gossipVersion` inside the plugin parameters and result so that the swarm protocol can evolve independently.

The following is one complete wire request. Pretty printing is for readability; send it as a single UTF-8 JSON line, with escaped newlines inside any strings.

```json
{
  "version": 1,
  "messageId": "11111111-1111-4111-8111-111111111111",
  "instruction": "gossip",
  "parameters": {
    "operation": "exchange",
    "gossipVersion": 1,
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
  "gossipVersion": 1,
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
  "members": []
}
```

The result's sender is always the responding process's current record. Merge it and the batch only after validating the outer correlated reply, supported gossip version, expected swarm ID, and agreement between the outer `serverId` and the result's sender. A seed can restart with the same server ID and a different boot ID; treat that as a new process record.

Do not echo arbitrary input fields into the result. Validate required keys, string lengths, endpoint ranges, canonical UUIDs, integer bounds, allowed states, metadata size, and duplicate member keys. Count the sender toward the maximum batch count and enforce the serialized byte budget on the complete envelope, including the outer reply. Reserve room for envelope overhead before selecting records. A single oversized record is a validation error, not an excuse to exceed the budget.

For Java callers, the existing client can initiate an exchange with:

```java
var client = new TextMessageClient(peer.host(), peer.port(), peer.serverId());
CommandReply reply = client.execute("gossip", Map.of(
        "operation", "exchange",
        "gossipVersion", 1,
        "swarmId", config.swarmId(),
        "sender", encode(localRecord),
        "members", encodeBatch(selectedRecords)));
// Validate and merge reply.result() as the exchange result described above.
```

`peer`, `config`, and encoding helpers above are proposed plugin classes and methods. The client itself already exists. Always open a fresh connection for an exchange; the host closes each connection after one reply. Retries may use new message IDs because host UUIDs provide correlation, not deduplication. Record-version merging makes exchange retries idempotent.

## Periodic work and bounded propagation

Start one lightweight scheduling task per plugin instance. Add random jitter to the initial delay and each round so processes do not all contact peers at the same instant. Startup initializes local state and schedules work; it must not wait for seeds or exchange with itself before returning from `start()`.

Each round performs the following work:

1. Advance the owner's heartbeat when its interval has elapsed, even if the node is isolated.
2. Update local reachability assessments and prune expired retained state.
3. Select up to two distinct reachable peers at random, excluding self and endpoints already in flight. Reserve periodic selection slots for seeds, new candidates, and recovery probes.
4. Snapshot a bounded record batch and submit exchanges only while a permit is available. Release each permit in `finally`.
5. Merge each validated result and record metrics. Classify network errors, incompatible versions, capacity errors, and identity conflicts separately.

Never hold the membership lock across socket I/O. Never make an outbound request from `execute(exchange)`: the handler only validates, merges, snapshots, and returns. Otherwise two members can create chains of nested requests and shutdown dependencies.

Use immutable records and a short lock around table changes and snapshots. `ConcurrentHashMap` by itself does not make compound version comparisons and updates atomic. Deep-copy mutable metadata on input and when producing snapshots; `CommandRequest` only makes its outer parameter map unmodifiable.

For batch selection, include the local record, prioritize recently changed records and departures, and reserve part of every batch for a rotating scan of the entire retained table. Track progress separately for outgoing exchanges and incoming responses so a busy peer cannot starve others. Advance a cursor by the records actually included, wrapping fairly; do not always return the first 32 sorted members. Continued fair exchanges repair missed updates even after the initial priority queue drains. Repeated sending is intentional; the receiver ignores duplicate versions.

The initial scheme sends record batches directly rather than full digests followed by fetches. Each message remains bounded, while a complete sweep takes more exchanges as the swarm grows. Memory grows with the capped membership table. This design has no proven fixed convergence deadline. For substantially larger swarms, measure saturation and consider a digest/delta protocol or SWIM-style probes with piggybacked membership changes.

The existing client uses a 5-second connect timeout and a 10-second read timeout. With two permits, two stalled requests can occupy the outbound budget for several rounds. Skip overlapping work rather than queueing unbounded tasks. The first version can retain these defaults and use conservative failure thresholds. Shorter configurable timeouts require extending the client or implementing the same envelope and validation behavior in a bounded plugin transport. Account for DNS lookup and cancellation as well as socket timeouts when promising an overall deadline.

The host currently reads unbounded lines before plugin validation. Plugin batch limits constrain cooperative peers but cannot protect the host from an oversized incoming line. Before using this beyond a trusted environment, add a byte-counted limit to the main socket reader and to gossip response reading before JSON parsing. Likewise, cap HTTP header bytes if the listener is exposed. This is a transport hardening change, separate from the gossip merge algorithm.

## Lifecycle extension required in the host

The current SPI has `start()` and `execute()` but no stop hook. `PluginRegistry.close()` closes the external class loader only; that does not stop a scheduler, outbound socket, UDP receiver, or plugin-created thread. Daemon threads and a JVM shutdown hook do not solve instance shutdown in a process hosting multiple servers.

Add one backward-compatible default method:

```java
// Proposed addition to CommandPlugin, not present today.
default void stop() throws Exception { }
```

Implement the host contract as follows:

- `PluginRegistry` tracks providers whose startup has been entered, including a provider that throws after allocating resources. Its idempotent `stop()` invokes their hooks in reverse startup order, attempts every hook, and aggregates failures. Constructors should avoid acquiring resources.
- Add an idempotent instance stop-request path that first quiesces plugins and then stops the admin and main listeners. Wire administrative `shutdown` through this path rather than directly calling only `ThreadPooledServer.stop()`.
- `ServerInstance.close()` invokes that stop-request path, joins listener tasks, waits for accepted request tasks, and only then closes the registry/class loader. Preserve cleanup through `finally` even when a hook fails.
- Registry close also invokes its idempotent stop path as a fallback. Failed startup follows the same cleanup path, including hooks already entered. Every discovered provider still has its loader resources released.

The admin command runs inside the monitor task. Its stop-request path must not join that same task or call `listeners.close()` from inside it. Preserve the current console behavior: `shutdown` requests a stop; `quit` or end-of-input reaches instance close and completes joins and loader cleanup.

The gossip stop hook prevents new rounds, cancels scheduled work, closes the optional UDP receiver, and cancels active outbound work. If using the current client, account for its finite socket waits and test the shutdown bound. For prompt cancellation, extend the transport to own and close its active sockets rather than assuming interrupting any arbitrary networking call is sufficient. Join plugin background tasks before returning so no background thread retains the plugin loader. Keep the in-memory table available to already accepted handlers until the host drains them.

Do not send a synchronous departure RPC from `stop()`: shutdown must still finish when the network is down. The explicit `leave` operation publishes a terminal local record, queues a bounded dissemination attempt, and then quiesces periodic work. Make it asynchronous and expose completion in `status`. Invoke it before shutdown when graceful departure is desired. Other nodes eventually time out a process that exits without delivering `LEFT`.

With no host changes, an external plugin can demonstrate periodic exchanges, but the host cannot guarantee per-instance cleanup. Treat the stop-hook work as a prerequisite for a managed swarm plugin rather than hiding the missing contract in the plugin.

## Suggested plugin classes

| Class | Responsibility |
|---|---|
| `GossipPlugin` | Implements the SPI, owns a runtime instance, and dispatches operations. |
| `GossipConfig` | Parses and validates configuration for the resolved server ID. |
| `GossipRuntime` | Coordinates rounds, lifecycle, in-flight limits, and diagnostics. |
| `MembershipTable` | Validates merges, versions, observations, expiry, and snapshots. |
| `MemberRecord` and `MemberKey` | Immutable owner data and compound identity. |
| `PeerSelector` | Random fanout, fair recovery opportunities, and retry backoff. |
| `GossipTransport` | Adapts `TextMessageClient` and validates exchange results. |
| `DiscoveryReceiver` | Optional UDP candidate ingestion; never a source of authoritative membership. |

Inject a monotonic clock, random source, and transport boundary into the runtime to make timing and selection tests repeatable. Supply real defaults in the public no-argument constructor required for service discovery. Avoid depending on startup hooks of other plugins: `PluginContext` does not expose their instances or the registry.

## Packaging and running a local swarm

Follow the existing [multiply plugin example](../examples/multiply-plugin/pom.xml): create a separate Java 27 Maven project, depend on `dev.mars:socket-client-server:1.0-SNAPSHOT` with scope `provided`, and package only the plugin implementation and its own resources. Do not include copies of host SPI classes.

Create this service file inside the plugin project:

```text
src/main/resources/META-INF/services/dev.mars.plugin.CommandPlugin
```

Its contents are:

```text
example.gossip.GossipPlugin
```

Use a dedicated plugin directory containing the gossip JAR and any dependencies it needs. Built-in providers are inherited through the host's parent class loader. Loading duplicate gossip providers fails startup because their instruction names conflict. Restart members to load a new plugin JAR; there is no hot reload.

After implementing the plugin and lifecycle extension, build the host and plugin, then copy the plugin JAR into `plugins/gossip`. The following illustrates a proposed project at `examples/gossip-plugin`:

```powershell
mvn clean install
mvn -f examples/gossip-plugin/pom.xml clean verify
mvn dependency:copy-dependencies "-DincludeScope=runtime"
```

The gossip project, configuration files, and plugin directory in this section must be created during implementation; they do not exist yet. Use separate config files with the same swarm ID and seed list. Start each process from the repository root in its own PowerShell terminal:

```powershell
java "-Dgossip.config=config/gossip-001.properties" -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-001 --port 9000 --admin-port 9001 --plugins-dir plugins/gossip
java "-Dgossip.config=config/gossip-002.properties" -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-002 --port 9010 --admin-port 9011 --plugins-dir plugins/gossip
java "-Dgossip.config=config/gossip-003.properties" -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-003 --port 9020 --admin-port 9021 --plugins-dir plugins/gossip
```

All three advertise `127.0.0.1` for this local demonstration. On different machines use reachable addresses and update the seeds. Automatic TCP allocation also works because the startup context supplies bound ports, but static seed files need a stable entry point or an independent discovery mechanism.

At each console, inspect the proposed plugin using the existing `send` command:

```text
send {"instruction":"gossip","parameters":{"operation":"status"}}
send {"instruction":"gossip","parameters":{"operation":"members","limit":32}}
```

`members` should return `swarmId`, `self`, a bounded list of records with their local assessments and observation ages, and an opaque `nextCursor` when more records remain. Document that paging is a best-effort view of changing state, not a transactional snapshot. A duplicate logical ID appears as a conflict rather than a single healthy entry.

Stop Server-002, observe local suspicion/dead assessments, then restart it with the same server ID. Verify that the new boot ID becomes reachable and the old boot ID ages out. Disconnect and reconnect a member to observe recovery. To leave voluntarily, issue the proposed `leave` command, allow its bounded attempt to complete, and then use the existing `quit` command:

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
| Wire behavior | Actual JSON requests and replies preserve UUIDs, numeric types, Unicode, null handling, byte limits, and server/swarm identity checks. Invalid input leaves the table unchanged. |
| Three-member swarm | Real servers converge from seeds; built-in `echo`, HTTP, and admin status continue working during exchanges. |
| Larger swarm | Test at the configured membership cap with multiple batches. Every record receives fair transmission opportunities and outbound concurrency stays bounded. |
| Loss and partitions | A protocol fixture or controllable TCP relay drops, delays, or reorders complete exchanges. After reconnection and fair exchanges, current owner records converge. |
| Bootstrap edge cases | Seeds start late, some are down, UDP is unavailable, same-host instances share a seed list, and gossip versions or swarm IDs disagree. |
| Lifecycle | Failed startup, `quit`, end-of-input, and admin `shutdown` stop plugin work. Idle UDP receive and stalled outbound requests terminate within the documented bound. |
| Packaging | Load a real external JAR, execute gossip on virtual threads, close instances, and delete the JAR on Windows after cleanup. |
| Isolation | Multiple instances in one JVM have distinct tables, boot IDs, configuration, and worker lifecycles. Closing one leaves the others working. |

Use fake elapsed time for precise expiry tests and eventual assertions with bounded deadlines for live convergence. Record observed convergence times rather than promising logarithmic timing from the general gossip model. Verify startup rollback after a later provider fails, so cleanup covers gossip workers that were already scheduled.

Expose round counts, exchange successes/failures, last successful exchange age, in-flight count, rejected records, identity conflicts, capacity usage, and membership counts by local assessment. Avoid logging every member on every round. These measurements distinguish isolation, transport failure, invalid peers, and an overloaded batch budget.

## Implementation sequence

1. Add and test the host stop-hook contract, including administrative shutdown and partial startup rollback.
2. Create the separate plugin project and service registration. Implement configuration, immutable records, exact validation, and membership merges.
3. Implement the `exchange`, `members`, and `status` operations using the existing socket protocol. Verify a real two-node round trip before adding scheduling.
4. Add jittered rounds, fair batches, bounded fanout, seed retries, local failure assessments, and expiry. Verify three-node convergence and restart handling.
5. Add bounded departure behavior and lifecycle cleanup tests. Run the existing host suite and packaged-plugin integration tests.
6. Add optional UDP discovery after seed-based operation is reliable. Measure the configured maximum swarm size and tune batch and timing settings.

For deployment beyond a trusted LAN, also implement transport limits and authenticated membership. A `swarmId` separates accidental configurations but is not an authentication credential. The host currently supplies neither TLS nor authenticated peer identity, and the plugin cannot infer the socket's remote address from `execute(Map)` alone. Restrict advertised endpoint destinations before dialing forwarded candidates. Keep administrative ports restricted because the existing shutdown command is unauthenticated.

Once membership works, application features can select reachable peers from its local view. Keep gossip records descriptive: receiving an instruction name as metadata must never execute that instruction. Distributed task routing, event delivery, and deduplication require explicit additional protocols.
