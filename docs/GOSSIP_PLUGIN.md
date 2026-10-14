# Gossip protocol plugin for a swarm of socket servers

| Document metadata | Details |
| --- | --- |
| Author | Mark Andrew Ray-Smith, Cityline Ltd |
| Status | Architecture proposal; provider and configuration scaffold implemented |
| Last updated | 2026-10-14 |

## Purpose and scope

Implement a plugin that lets instances of this server discover one another and maintain a local view of the swarm. Each instance periodically exchanges membership information with a few peers through the existing JSON command socket. Every member can initiate and answer exchanges; seed servers help members find the swarm but have no permanent coordinating role.

The first implementation should gossip membership and small, owner-controlled metadata, such as a node's supported application instructions. It should expose the local membership view for inspection. Application event delivery can be added later, with its own retention and deduplication rules.

The external provider and configuration scaffold now exist in [`plugins/gossip-plugin`](../plugins/gossip-plugin/pom.xml). It loads through `ServiceLoader`, resolves per-instance settings, creates a fresh boot ID, and answers `status`. Membership merges, peer exchanges, scheduling, departure, and UDP discovery remain proposed. The host lifecycle prerequisite and baseline request hardening are implemented: shutdown closes the main listener before invoking optional plugin stop hooks, accepted requests have a bounded drain, and request/header lines and concurrently serviced connections have limits. Plugin stop hooks also run during failed startup, before loader cleanup.

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

The main listener currently services at most 1024 connections concurrently; excess connections wait in the kernel backlog. Transient `accept()` failures retry after 100 ms. These are host protections shared by gossip and application commands, not a substitute for the plugin's smaller outbound concurrency and message budgets. The connection cap and drain timeout have package-private setters for tests; neither is a CLI option.

The protocol follows periodic peer exchanges and bounded information propagation described in [Wikipedia's gossip protocol overview](https://en.wikipedia.org/wiki/Gossip_protocol). Failure detection and dissemination should remain separate responsibilities, following the useful distinction in the [SWIM paper](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf). The simpler heartbeat design below is not an implementation of SWIM and does not inherit its performance guarantees.

## One provider and one instruction

Register one public provider class, `example.gossip.GossipPlugin`, with instruction name `gossip`. Use an `operation` parameter to select behavior:

| Operation | Purpose | Network activity inside the handler |
|---|---|---|
| `exchange` | Merge a peer's records and return a bounded selection of local records. | None. |
| `members` | Return a bounded, paginated local membership snapshot and diagnostics. | None. |
| `status` | Report local identity, worker state, last round, and counters. | None. |
| `leave` | Schedule a bounded departure announcement and stop initiating exchanges. | Queue work only; return immediately. |

Currently only `status` is implemented. Its result reports `implementationStage="scaffold"`, `supportedOperations=["status"]`, `isolated=true`, and `workerState="NOT_SCHEDULED"`. The local record has heartbeat zero; no background work or network exchanges run. Unsupported operations return `INVALID_PARAMETERS`. The other operations and full runtime diagnostics in this document are acceptance targets for later increments.

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

These values are parsed defaults for the future small-LAN runtime, not measured convergence or failure-detection guarantees. Only `swarm.id` and `advertise.host` are required; omitted seeds default to an empty list. Tune runtime values using the tests below. A seed entry contains an expected server ID, a host, and a main TCP port. The parser supports hostnames, IPv4, and bracketed IPv6 seed endpoints, such as `Server-001@[::1]:9000`, without resolving DNS during startup. Use an unbracketed IPv6 address for `advertise.host`.

The parser rejects unknown keys, malformed or duplicate seed entries, non-integer/overflowing limits, and inconsistent timing or capacity settings. IDs use 1-128 letters, digits, dots, underscores, or hyphens. Intervals must be positive and convertible to nanoseconds; require `heartbeat < suspect < dead <= terminal retention`. Capacities require `1 <= max.members <= max.records`, batch count between 1 and `max.records`, and fanout/in-flight limits between 1 and `max.members`. The exchange budget must be 1024-65,536 bytes. `discovery.enabled=true` fails startup until discovery is implemented.

Use `PluginContext.port()` for the advertised main port. A configured `advertise.host` supplies the reachable address because `PluginContext` has no host/interface field. Loopback works only for processes on the same machine; on separate machines configure a LAN address or resolvable hostname reachable by all members. The existing listeners bind to wildcard addresses, but firewall and routing rules must still allow access.

Configuration is per plugin instance. A single JVM property selects a file for a normal one-server process. For multiple instances in one JVM, global properties act as defaults and `server.<serverId>.<key>` overrides are resolved separately at each start:

```properties
swarm.id=demo-swarm
advertise.host=127.0.0.1
server.Server-001.advertise.host=192.168.1.10
server.Server-002.advertise.host=192.168.1.11
```

Embedded applications and tests can instead supply an immutable [`GossipConfig`](../plugins/gossip-plugin/src/main/java/example/gossip/GossipConfig.java) to the explicit `GossipPlugin(config)` constructor. No process-wide mutable configuration or plugin runtime is cached.

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

The built-in broadcaster runs independently of this plugin and may announce before its receiver starts. Providers initialize concurrently on virtual threads, with no guaranteed hook execution or completion order. Do not depend on class-name startup order to make discovery reliable. Optional receiver bind failures should disable that discovery path and leave seed-based startup working.

Set `-Ddev.mars.announce=false` to disable announcements from the ServiceLoader-constructed broadcaster. This property is independent of the proposed `discovery.enabled` receiver setting. Host Surefire tests and existing child-JVM tests disable real announcements. Apply the same setting to the new plugin test suite and every child JVM it starts; exercise discovery through controlled UDP fixtures rather than broadcasting onto the LAN.

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

The host now bounds each request and HTTP header line at `WorkerRunnable.MAX_LINE_LENGTH`, currently 65,536 Java UTF-16 code units, excluding the line terminator. An overlong first line receives `INVALID_REQUEST`; an overlong HTTP header closes the connection. This is a decoded-character limit, not a UTF-8 byte budget, and it does not cap the total HTTP header section. Keep the proposed 32,768-byte exchange budget on complete serialized envelopes independently of this host limit.

`TextMessageClient` still reads replies with an unbounded `BufferedReader.readLine()` before JSON parsing. Plugin validation can reject an oversized result only after that allocation. Before deployment beyond a trusted environment, add byte-counted limits before JSON parsing to both request and gossip response readers, and cap aggregate HTTP header bytes. Response limits can be added to the existing client or a plugin transport that preserves its correlation and server-identity checks. These remaining transport tasks are separate from the gossip merge algorithm.

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

The gossip stop hook prevents new rounds, cancels scheduled work, closes the optional UDP receiver, and cancels active outbound work. If using the current client, account for its finite socket waits and test the shutdown bound. For prompt cancellation, extend the transport to own and close its active sockets rather than assuming interrupting any arbitrary networking call is sufficient. Join plugin background tasks before returning so no background thread retains the plugin loader. Keep the in-memory table available to already accepted handlers during draining, and keep handlers short and responsive to cancellation. The host's request drain timeout does not bound synchronous plugin stop hooks or their outbound work.

Do not send a synchronous departure RPC from `stop()`: shutdown must still finish when the network is down. The explicit `leave` operation publishes a terminal local record, queues a bounded dissemination attempt, and then quiesces periodic work. Make it asynchronous and expose completion in `status`. Invoke it before shutdown when graceful departure is desired. Other nodes eventually time out a process that exits without delivering `LEFT`.

Tests exercise normal close, administrative shutdown, console quit/end-of-input, partial initialization, concurrent startup failures, stop-hook failures, concurrent shutdown, instance isolation, and loader availability for accepted commands that complete during draining. They also cover idle/trickling clients and main-listener closure before plugin quiescing. A real external provider owns a worker blocked on UDP receive; its stop hook closes the socket and joins the worker, and its JAR can be deleted after host cleanup on Windows. Stop hooks run synchronously without a host-enforced deadline. The gossip plugin will still need to implement its own cancellation and bounded resource cleanup.

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

The [gossip project](../plugins/gossip-plugin/pom.xml) follows the existing [multiply plugin example](../plugins/multiply-plugin/pom.xml): a separate Java 27 Maven project depends on `dev.mars:socket-client-server:1.0-SNAPSHOT` with scope `provided` and packages only the plugin implementation and its own resources. Host SPI classes are supplied by the host.

The project includes this service file:

```text
src/main/resources/META-INF/services/dev.mars.plugin.CommandPlugin
```

Its contents are:

```text
example.gossip.GossipPlugin
```

Use a dedicated plugin directory containing the gossip JAR and any dependencies it needs. Built-in providers are inherited through the host's parent class loader. Loading duplicate gossip providers fails startup because their instruction names conflict. Restart members to load a new plugin JAR; there is no hot reload.

Use Java 27 for Maven and the server processes. Build and install the host API, verify the standalone plugin, and copy the host's runtime dependencies:

```powershell
mvn clean install
mvn -f plugins/gossip-plugin/pom.xml clean verify
mvn dependency:copy-dependencies "-DincludeScope=runtime"
```

The project builds `plugins/gossip-plugin/target/gossip-plugin-1.0-SNAPSHOT.jar`. The local demo files [`gossip-001.properties`](../config/gossip-001.properties), [`gossip-002.properties`](../config/gossip-002.properties), and [`gossip-003.properties`](../config/gossip-003.properties) share a swarm ID and seed list. Load the JAR directly from its target directory, or copy it to a dedicated runtime folder. Start each process from the repository root in its own PowerShell terminal; these seed-only demo commands disable LAN announcements:

```powershell
java "-Ddev.mars.announce=false" "-Dgossip.config=config/gossip-001.properties" -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-001 --port 9000 --admin-port 9001 --plugins-dir plugins/gossip-plugin/target
java "-Ddev.mars.announce=false" "-Dgossip.config=config/gossip-002.properties" -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-002 --port 9010 --admin-port 9011 --plugins-dir plugins/gossip-plugin/target
java "-Ddev.mars.announce=false" "-Dgossip.config=config/gossip-003.properties" -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-003 --port 9020 --admin-port 9021 --plugins-dir plugins/gossip-plugin/target
```

All three advertise `127.0.0.1` for this local demonstration. On different machines use reachable addresses and update the seeds. Automatic TCP allocation also works because the startup context supplies bound ports, but static seed files need a stable entry point or an independent discovery mechanism.

At each console, inspect the implemented scaffold using the existing `send` command:

```text
send {"instruction":"gossip","parameters":{"operation":"status"}}
```

Each process currently reports only its own identity and remains isolated. The following commands and convergence/recovery checks apply after membership and runtime tasks are implemented:

```text
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

The scaffold currently has 56 unit tests covering configuration parsing and provider lifecycle/status, plus three packaged-JAR integration tests. The integration fixture excludes plugin implementation classes from its parent classpath, starts two real servers in one JVM, verifies distinct configurations and boot IDs through TCP, closes one while the other remains usable, and deletes the JAR after both instances close while the JVM is still alive. It also verifies rejection of invalid configuration and invalid/unimplemented command operations. Root `mvn test` does not run this separate project; use its `mvn verify` command above.

Expose round counts, exchange successes/failures, last successful exchange age, in-flight count, rejected records, identity conflicts, capacity usage, and membership counts by local assessment. Avoid logging every member on every round. These measurements distinguish isolation, transport failure, invalid peers, and an overloaded batch budget.

## Implementation sequence and task status

The checked tasks below are implemented in the current repository. The next action is the membership model. The provider/configuration scaffold answers only `status`; the command/transport task remains pending for peer exchanges and pagination. Complete the trusted-LAN path in order, adding lifecycle verification as soon as background work exists.

- [x] **Host lifecycle prerequisite:** Optional stop hooks, concurrent startup rollback, administrative stop requests, cleanup after hook failures, and loader cleanup are implemented and covered by host tests.
- [x] **Baseline host request hardening:** Main-listener closure before plugin quiescing, bounded drain with worker interruption, per-line character limits, a concurrent connection cap, transient accept retries, and test announcement suppression are implemented. Byte-counted transport limits remain pending below.
- [x] **Plugin scaffold and configuration:** The separate Maven project, `GossipPlugin` provider, service registration, per-instance configuration, and local demo configuration files are implemented. Tests and spawned JVMs disable real announcements. Acceptance verified: real external-JAR loading, isolated instances, correlated local status, invalid-configuration cleanup, and JAR deletion after instance cleanup while the Windows JVM remains alive.
- [ ] **Membership model:** Implement immutable records, exact numeric/identity/metadata validation, atomic merges, local observations, capacity rules, and tombstones. Acceptance: deterministic clock-based tests prove duplicate freshness, conflict handling, terminal `LEFT`, restart identity, and rejection without partial mutation.
- [ ] **Command and transport path:** Implement `exchange`, paginated `members`, and `status`; enforce complete-envelope budgets and reply identity checks. Acceptance: real two-node TCP exchanges cover serialization, malformed replies, incompatible versions/swarm IDs, and capacity failures before scheduling is introduced. Choose the transport's timeout and cancellation approach here and document its cleanup bound; the current client has unbounded response reading.
- [ ] **Scheduled seed-based runtime:** Add jittered rounds, owner heartbeats, bounded fanout/in-flight work, fair batches, seed backoff, recovery probes, local assessments, expiry, and metrics. Implement cancellation and joining in `stop()` with the first background task. Acceptance: three real nodes converge, late/down seeds recover, restarts are distinct, closing one instance leaves others working, and stalled exchanges or startup rollback leave no plugin workers behind.
- [ ] **Departure and lifecycle completion:** Implement asynchronous bounded `leave`, terminal dissemination, and completion reporting. Acceptance: departures stay terminal; failed startup, admin shutdown, console quit/end-of-input, and repeated close clean up promptly while accepted handlers retain their table during draining. Run the host suite and packaged-plugin integration tests.
- [ ] **Capacity, fairness, and partition validation:** Exercise the configured membership cap with multiple batches and a controllable TCP fixture/relay. Measure convergence and prove fair record transmission, bounded outbound concurrency, capacity-safe updates, and recovery after partitions. Tune the proposed limits and intervals from these results before calling the trusted-LAN baseline ready.
- [ ] **Optional UDP discovery:** Add only after seed-based operation passes the preceding checks. Acceptance: controlled datagrams only queue validated TCP handshakes; unavailable UDP, lost announcements, and same-host port contention leave seeds working. Include receiver cancellation in the existing lifecycle tests.
- [ ] **Beyond-trusted-LAN prerequisites:** Add byte-counted request and response limits before parsing, aggregate HTTP header limits, authenticated membership/transport, and an advertised-destination policy before dialing forwarded candidates. Test oversized/slow input, identity failures, and denied destinations. These are deployment prerequisites for that environment, not completed by the existing character limit or `swarmId` validation.

A `swarmId` separates accidental configurations but is not an authentication credential. The host currently supplies neither TLS nor authenticated peer identity, and the plugin cannot infer the socket's remote address from `execute(Map)` alone. Keep administrative ports restricted because the existing shutdown command is unauthenticated.

Once membership works, application features can select reachable peers from its local view. Keep gossip records descriptive: receiving an instruction name as metadata must never execute that instruction. Distributed task routing, event delivery, and deduplication require explicit additional protocols.
