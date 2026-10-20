# Replicated key-value data and replicated configuration

| Document metadata | Details |
| --- | --- |
| Status | Proposal, for review. Nothing in this document is implemented. |
| Last updated | 2026-10-20 |
| Applies to | [`plugins/kv-plugin`](../plugins/kv-plugin) and a new configuration plugin |
| Part of | A set of three documents. [`PLUGIN_SERVICES_DESIGN.md`](PLUGIN_SERVICES_DESIGN.md) lets plugins call each other. [`REPLICATION_ENGINE_DESIGN.md`](REPLICATION_ENGINE_DESIGN.md) is the generic engine. This document describes the two users of it, called profiles: what each one stores, checks, applies and exposes. |

## 1. Summary

The engine replicates records and knows nothing about what they mean. A **profile** is the plugin-specific half: the
namespace it registers, the commands it offers clients, how values are validated, and what happens when one arrives.

- The **key-value profile** keeps today's `kv` instruction and its replies, and stores its pairs in a `kv` namespace.
  Its size limits, which are constants in the code today, become configuration.
- The **configuration profile** is a new plugin that stores settings documents in a `config` namespace, accepts changes only
  from a sender that proves it knows a shared secret, validates every change before it spreads, and tells the plugin that
  owns each document to apply it.

### Decisions already made

| # | Decision |
| --- | --- |
| 14 | `kv` keeps its instruction and replies as a thin layer on a `kv` namespace. |
| 20 | Only dynamic, live-reloadable settings are replicated. Bootstrap configuration stays local. No setting is built in: each plugin adds its own documents. |
| 21 | One record per settings document, validated as a whole. |
| 22 | A configuration write may carry an expected version ("only if still at version N"). It is optional and is checked on the receiving server only. |
| 23 | (Revised twice.) Configuration changes (`put` and `delete`) arrive on the main port and are accepted by any server that holds the swarm's shared secret, only when the request carries an HMAC-SHA-256 proving the sender knows it. A server with no secret refuses every change. Reads need no proof. The secret is bootstrap configuration, local to each server and never replicated, and several secrets can be configured at once so it can be rotated. The admin port is not used. The network is still trusted for everything else. A `signature` field is reserved on the record so signed documents can be added later. |
| 24 | Settings are swarm-wide, with optional per-server overrides, resolved server over swarm over the plugin's shipped default. |
| 25 | A server that cannot apply a value keeps and forwards it, and reports why: applied live, restart required, rejected by the validator, or no plugin. A value that fails validation on the writing server is refused before it spreads. |
| 26 | Each server reports which version it applied. No staged rollout and no rollback. |
| 27 | Audit is the origin server and time already in each version. |
| 28 | Some keys can be marked non-replicable, so secrets never cross the network in a document. |
| 29 | Configuration persists to a local snapshot. Key-value does not. |
| 32 | Every limit is configuration, not code. |
| 37 | A configuration write sends the settings as a JSON string (`valueText`) and the signature covers that text exactly, so no canonical form is needed and the host does not change. |
| 38 | A refused configuration write returns `INVALID_PARAMETERS` with a clear message. A timestamp out of range says so. Other failures say only that authentication failed. |
| 39 | A command-line configuration tool is deferred. `ConfigClient` and the harness come first. |
| 40 | Repeated failed authentication is throttled by sender address. It needs the host to pass the sender's address to plugins (plugin services document, section 5). |
| 41 | The secret is read from a file named per key ID. Files the harness generates are restricted to the current user where the platform allows. An inline value is allowed for tests. |
| 42 | A sender's clock must be within a configurable window of the server's. |
| 43 | Holding the secret alone decides whether a server accepts writes. There is no separate switch. |
| 44 | A server's overrides overlay the swarm document property by property. JSON `null` in an override removes it. |
| 45 | A write for a document the receiving server cannot validate is refused unless the sender sets `unvalidated`. |
| 46 | Any server answers configuration reads, with no proof. |
| 47 | The configuration namespace has no overshoot allowance: a document beyond a server's limit is refused and reported. |
| 48 | A plugin's document is named after its command instruction (with `.suffix` for more than one). |

## 2. The key-value profile

### 2.1 What stays the same

The `kv` instruction keeps its four operations and their parameters and reply fields: `put` (`created`), `get` (`found`,
`value`), `delete` (`deleted`) and `list` (`keys`, `count`). Error replies keep their codes and messages. A server that does
not turn replication on, or does not load the engine, behaves as it does today: the plugin keeps the pairs in its own map.
The existing unit and integration tests must pass unchanged with replication off.

### 2.2 What changes

When the engine is available (`ReplicationService` found, replication on), the plugin registers the `kv` namespace and
delegates storage to it:

- `put` and `delete` call the namespace and return as soon as the local write succeeds. Replication happens afterwards.
  `created` and `deleted` describe this server's own state at the time.
- `get` and `list` read live records. Deletion records are not shown.
- A value is still any JSON up to the value size limit, and a key is still a string.
- A `ChangeListener` is not needed. Nothing happens when a pair arrives except that it is stored.

When the engine appears after the plugin started, the plugin registers then and takes its existing pairs into the namespace
as local writes, so nothing is lost by turning replication on later. When the engine goes away, the plugin keeps working on a
copy held locally.

### 2.3 Limits become configuration

Today `KvPlugin` fixes three numbers: key length 256, value size 16 KB, and 1,000 pairs. They become settings:

| Setting | Shipped default | Meaning |
| --- | --- | --- |
| `namespace.kv.max.key.length` | 256 | Longest key. |
| `namespace.kv.max.value.bytes` | 16384 | Largest value. |
| `namespace.kv.max.records` | 1000 | Pairs a server accepts from its own clients. |
| `namespace.kv.soft.ceiling.factor` | 2 | Replicated pairs may exceed `max.records` up to this multiple, each counted and logged. |
| `namespace.kv.max.deleted.records` | 1000 | Deletion records kept. |
| `namespace.kv.tombstone.retention.millis` | 1800000 | Matches gossip's terminal retention. |
| `namespace.kv.persist` | false | Key-value data is not saved to disk. |

These are the *shipped default values* in a properties file bundled in the plugin jar. The Java code contains no number
that limits the store. The plugin reads them through one small loader in the host module that both the plugin (when the
engine is absent) and the engine (when it registers the namespace) use, so a limit is parsed and checked identically either
way and there is one place that defines what the keys mean. A server overrides them with a file selected by
`-Dreplication.config=...`, with `server.<serverId>.namespace.kv.<setting>` keys for one server.

A change to the soft-ceiling rule is the only behavioural difference a client can see: with replication on, a new key written
locally is refused above `max.records`, as today, while pairs arriving from peers are accepted up to the ceiling.

### 2.4 Operations added to `kv`

| Operation | Purpose |
| --- | --- |
| `status` | The namespace's counts, root hash, bootstrap state and last sync with each peer, or `{"replication":"off"}`. It reads from the engine's `status` and is for clients and the harness. |

Peer-to-peer operations (`apply`, `sync-root`, `sync-digest`) belong to the engine's `replicate` instruction, not to `kv`.

## 3. The configuration profile

### 3.1 What is and is not configuration here

Configuration in this sense means **settings a plugin can apply while it runs**. Today almost nothing qualifies: the host
reads server ID, ports and plugin directories from its command line, and the gossip plugin reads its timings and seeds from
a properties file once at start. Those stay local and are never replicated, for two reasons. Some of them (ports, server ID,
advertise host, seeds, the plugin directories) are needed before replication can start, so replicating them is circular.
Others identify one server, so sharing them makes no sense.

So the configuration plugin starts with no setting of its own (decision 20). A plugin that wants a setting to be changeable
at runtime, and shared across a swarm, registers a **document** and says how to validate and apply it. Making a particular
setting live-reloadable (gossip's timings, for example) is that plugin's own work and its own design.

### 3.2 Documents and scopes

A document is a JSON object owned by one plugin. Its name is its plugin's command instruction, such as `gossip` or `kv`, with
`.suffix` when a plugin has more than one (decision 48). Instructions are already unique within a server, so names cannot collide.
It is stored as one record (decision 21):

| Record key | Meaning |
| --- | --- |
| `swarm/<document>` | The swarm-wide settings. |
| `server.<serverId>/<document>` | Overrides for one server. |

The settings a plugin sees are the **effective** document: the plugin's shipped default, overlaid property by property with
`swarm/<document>`, then with `server.<serverId>/<document>` (decision 24). A property set to JSON `null` in an override
means "remove my override, use the lower level". A property that is absent keeps the lower level.

Overlay is by top-level property, not whole-document replacement, because an override usually changes one value. The
validator receives the whole effective result, so a set of settings that must hold together is checked together, even though a
lower level supplied part of them. (Gossip's timings, which must satisfy `heartbeat < suspect < dead ≤ retention`, are the
model case.)

### 3.3 What a plugin provides

The configuration plugin publishes a `ConfigService` (a plugin service, see the plugin services document):

```java
public interface ConfigService {
    Registration register(String document, JsonNode shippedDefault, ConfigConsumer consumer);

    interface ConfigConsumer {
        /** Checks the whole effective document, or throws InvalidParametersException with a message naming the problem. */
        void validate(JsonNode effective) throws InvalidParametersException;

        /** Puts it into effect. Runs on a virtual thread whenever the effective document for this server changes. */
        ApplyOutcome apply(JsonNode effective);
    }

    enum Applied { APPLIED_LIVE, RESTART_REQUIRED, REJECTED }
    record ApplyOutcome(Applied result, String detail) { }
}
```

`validate` is called by the configuration plugin on every server before a stored value is applied, and by the writing server
before a write is accepted. `apply` is called after the value is stored. A consumer that returns `RESTART_REQUIRED` is saying
"stored, takes effect when I next start". The detail string appears in the status report.

### 3.4 Writing and authentication

The configuration plugin is an ordinary command plugin with the instruction `config`. Its writing operations, `put` and
`delete`, are accepted by **any server that holds the swarm's shared secret**, and only for a request that proves its sender
knows that secret (decision 23). Reads need no proof (decision 46). A server with no secret configured refuses every write, so
giving the secret to only some servers is also how an operator keeps the others from accepting changes, and there is no
separate switch (decision 43). A write accepted by one server is replicated to every server, whether that server holds the
secret or not, and a server without the secret still applies what replicates to it.

| Operation | Parameters | Needs a MAC | Effect |
| --- | --- | --- | --- |
| `put` | `document`, `scope` (`swarm` or a server ID), `valueText` (the settings object, as JSON text in a string), `auth`, optional `ifVersion`, optional `unvalidated` | yes | Validates and stores a document. |
| `delete` | `document`, `scope`, `auth`, optional `ifVersion` | yes | Writes a deletion record for the document, which removes that level. |
| `get` | `document`, optional `scope` | no | The stored document as an object, or with no scope the effective document for this server and where each property came from. |
| `list` | none | no | Every document this server holds, with its versions. |
| `status` | none | no | Per document: the stored versions, and this server's outcome (section 3.6). Also the authentication counters. |

#### Authenticating a write

A write carries an `auth` object:

```json
{"instruction":"config","parameters":{
  "operation":"put","document":"gossip","scope":"swarm",
  "valueText":"{\"round.millis\":2000}",
  "auth":{"keyId":"k1","timestamp":1760900000000,"nonce":"q3Jk0m9T1b2uZyWc5r8Hqg==","mac":"Zk0m...(base64)"}}}
```

The settings travel as a JSON **string**, `valueText`, and the signature covers that exact text (decision 37). A string's bytes
cannot be reformatted between the signer and the verifier, so there is no need for a canonical JSON form and no change to the
host. The server verifies the signature first, and only then parses `valueText` as a JSON object. The client library builds
the string and hides the extra level of escaping.

The MAC is the Base64 of HMAC-SHA-256, keyed with the secret named by `keyId`, over this text, one item per line:

```text
config-auth-v1
<the serverId of the server the request is sent to>
<keyId>
<timestamp, milliseconds since the epoch>
<nonce>
<operation>
<document>
<scope>
<ifVersion as the compact clock value, or empty>
<valueText exactly as sent, or empty for delete>
```

The receiving server checks, in this order, and refuses the write if any check fails:

1. The sender's address is not currently throttled (below).
2. It holds a secret for `keyId`.
3. The timestamp is within `config.auth.max.skew.millis` of the server's own clock (decision 42).
4. The nonce has not been seen within that window. Seen nonces are kept in a bounded cache
   (`config.auth.nonce.cache.size`) and expire after twice the window.
5. The MAC equals the one it computes over the same text, compared in constant time. The server's own ID is part of the text,
   so a request signed for one server fails on another.

Each check has a purpose: the timestamp and nonce stop a captured request being replayed, which would otherwise let anyone who
saw one write put an old value back over a newer one. Including the target server's ID stops a request for one server being
replayed to another. Including the value text and the version in the MAC stops a request being altered in transit.

A refusal returns `INVALID_PARAMETERS` (decision 38). A timestamp out of range says so and gives the server's time, because
that is the one failure a caller can fix and it reveals nothing secret. A server with no secret says that configuration writes
are disabled. A throttled sender is told it has made too many failed attempts. Every other failure says only that
authentication failed, so a caller cannot tell a wrong key ID from a wrong secret. The server logs the real reason (never the
secret or the MAC) and counts it.

#### Throttling failed attempts

The plugin counts failed authentications per sender address (the address only, not the port), taken from the request context
(plugin services document, section 5). When one address has `config.auth.throttle.max.failures` failures within
`config.auth.throttle.window.millis`, every write from that address is refused for `config.auth.throttle.block.millis`, even a
correctly signed one. The table of addresses is bounded (`config.auth.throttle.max.addresses`, oldest dropped first). A request
with no `auth` at all counts as a failure. A sender behind a shared proxy appears as the proxy's address, so all senders behind
it are throttled together, which is the limit of address-based throttling. `status` reports the failure and throttle counters.

What this protects and what it does not:

- It proves the sender knows the secret, and that the request was not forged, altered or replayed.
- It does not encrypt. Settings documents are sent in the clear, which is acceptable because they hold no secrets
  (section 3.7). The secret itself is never sent.
- It does not identify who the sender is. Everyone with the secret is the same administrator.
- It authenticates the **entry point only**. Once a server accepts a write, it replicates it to its peers unsigned, and peers
  trust each other, as they do for every other replicated record. A compromised server inside the swarm could therefore inject
  configuration. Signed documents, which every server verifies, would close that, and the record's reserved `signature` field
  leaves room for them.

#### The secret

The secret is **bootstrap configuration**: it lives in each server's local configuration, is read at start, and is never
written into a document, a snapshot, a log, a status reply or the replicated namespace. A secret is configured under a key ID,
and the plugin can hold several at once:

| Setting | Shipped default | Meaning |
| --- | --- | --- |
| `config.secret.<keyId>.file` | none | Path to a file containing the secret (decision 41). The way to configure it in practice. |
| `config.secret.<keyId>.value` | none | The secret inline. For tests only. |
| `config.secret.min.length` | 32 | The shortest accepted secret, in bytes. A shorter one is refused when the plugin starts, naming the key. |
| `config.secret.reload.millis` | 60000 | How often a secret file is read again, so a rotated secret takes effect without a restart. 0 reads it once. |
| `config.auth.max.skew.millis` | 60000 | How far a request's timestamp may differ from the server's clock. |
| `config.auth.nonce.cache.size` | 10000 | How many recent nonces are remembered. When it is full the oldest are dropped, and the timestamp window still bounds replay. |
| `config.auth.throttle.max.failures` | 5 | Failed attempts from one address, within the window, before it is blocked. |
| `config.auth.throttle.window.millis` | 60000 | The window those failures are counted in. |
| `config.auth.throttle.block.millis` | 60000 | How long a blocked address stays blocked. |
| `config.auth.throttle.max.addresses` | 1000 | Addresses tracked at once. |

As with every limit in these documents, none of these values is in the code. They are the shipped defaults in the plugin's
bundled properties file.

The plugin checks at start whether a secret file can be read by other users, where the platform lets it tell, and logs a
warning naming the file. The harness restricts the files it generates to the current user.

If no secret is configured, the plugin starts, answers reads, and refuses all writes with a message saying that writes are
disabled. That is the default.

#### Rotating the secret

Several key IDs may be configured at once, and a request names the one it was signed with. Rotation is therefore:

1. Add the new secret under a new key ID (for example `k2`) to every server that accepts writes. They now accept `k1` and `k2`.
2. Switch the clients that send writes to `k2`.
3. Remove `k1` from every server.

Step 1 must finish everywhere that a client might send to before step 2 begins. Only the server that receives a write needs the
key, because replication to peers is not authenticated, so a server that does not accept writes needs no key at all.

#### A `put`, step by step

1. Authenticate it as above.
2. Parse `valueText` as a JSON object, check it is within the namespace limits, and that no property name is on the
   non-replicable list (section 3.7). A violation is `INVALID_PARAMETERS` naming the property.
3. If `ifVersion` is present, compare it with the stored version for that scope and refuse with a conflict error if it
   differs (decision 22). The check is local, so two administrators writing through different servers at the same moment can
   both succeed, and one change is lost under the engine's newest-wins rule. The reply carries the new version, so the next
   conditional write can use it.
4. Compute the effective document for the target scope and run the owning consumer's `validate` on it. A swarm-scope write
   also validates the effective result for every server override the receiving server holds, so a swarm value cannot be
   accepted if it would make some server's effective document invalid. The error names the server.
5. If this server has no consumer for the document, the write is refused unless `unvalidated` is true (decision 45): a swarm
   may legitimately hold a document for a plugin that only some servers load, and the sender must then accept that nobody
   validated it here.
6. Write the record to the `config` namespace. The engine replicates it. The reply carries the version.

Each server, on receiving a record, runs the same `validate`, then `apply`, and records the outcome. A record that fails
`validate` on a server is still stored and forwarded (decision 25), so the swarm converges, and that server reports
`REJECTED` and keeps the previous effective document in force. A record for a document with no consumer is stored and
forwarded and reported `NO_PLUGIN`.

### 3.5 Reading

Any server answers `get`, `list` and `status` on the main port with no proof. Documents hold no secrets by design
(section 3.7), so reads are not restricted. The `config` namespace is still marked private in the engine's generic read view
(`replicate get` and `list`), so there is one way to read configuration and not two.

### 3.6 Who is on which version

Each server keeps, per document, the versions of the records it holds and the outcome of the last apply, and reports them
through `config status`:

```json
{"serverId":"alpha-Server-002","documents":[
  {"document":"gossip",
   "stored":{"swarm":{"w":1760900000000,"c":0,"n":"alpha-Server-001"},"server":null},
   "outcome":"APPLIED_LIVE","detail":"","appliedAt":"2026-10-20T09:15:01Z"}]}
```

The harness reads this from every server and shows which servers are on the current version, and which reported
`RESTART_REQUIRED`, `REJECTED` or `NO_PLUGIN` (decision 26). There is no staging of a rollout and no rollback. To undo a
change, write the old value, which becomes the newest version.

### 3.7 Settings that must not be replicated

A list of property name patterns, itself configuration (`config.local.only.patterns`, empty by default), marks properties such
as passwords or keys. A document containing a matching property is refused at write time with a message that says the
property must be supplied through a server's local configuration instead (decision 28). The list is checked on the writing
server and again on receipt, so a document from a server with a shorter list still cannot put such a property into a
swarm's records.

### 3.8 Persistence

The `config` namespace has `persist=true` (decision 29). Each server writes its records to a snapshot file and loads it at
start, merged by version with whatever peers hold, so a restart of every server in a swarm does not lose the settings and a
stale snapshot does not override newer ones. Without it, restarting every server at once would erase configuration that
nothing else holds. The snapshot directory is configuration (`snapshot.dir`) and is not shared.

### 3.9 Limits

| Setting | Shipped default | Meaning |
| --- | --- | --- |
| `namespace.config.max.key.length` | 256 | `swarm/<document>` and `server.<id>/<document>`. |
| `namespace.config.max.value.bytes` | 65536 | One document. |
| `namespace.config.max.records` | 200 | Documents, counting each scope. |
| `namespace.config.soft.ceiling.factor` | 1 | No overshoot (decision 47). |
| `namespace.config.tombstone.retention.millis` | 1800000 | As for key-value. |
| `namespace.config.persist` | true | Snapshots on. |
| `namespace.config.private` | true | Hidden from the generic read view; read through the `config` operations (section 3.5). |

These are shipped defaults in a bundled properties file, overridable like any other, as in section 2.3.

With no overshoot, a document that replication delivers beyond a server's limit is refused by that server and counted in
`status` as an overrun, rather than absorbed as key-value pairs are. Servers disagreeing about how many documents may exist is a
misconfiguration to fix, and hiding it would delay finding it.

## 4. Harness and UI changes

These belong in the `socket-server-swarm` project and follow the pattern used for gossip.

- Options to turn replication on and set its keys, in the form of `--gossip-option`: `--replication` and
  `--replication-option key=value`, generating the properties file per swarm, with per-server overrides.
- The snapshot adds each server's `replicate status` where the engine answers it, with the unknown-instruction result cached
  as for gossip. The page shows, per swarm and namespace, how many servers share the majority root, and marks a server whose
  root differs.
- The harness generates a random secret for each swarm and writes it, as a file restricted to the current user where the
  platform allows (decision 41), into each server's local configuration. An option, `--config-secret-file <path>`, supplies a
  fixed one. It signs configuration writes itself using the `ConfigClient` class, which builds `valueText` and the MAC, so the
  secret stays in the harness's Java side and is never sent to the browser.
- For configuration, the harness reads `config status` from each server and shows which version each server applied and any
  `RESTART_REQUIRED`, `REJECTED` or `NO_PLUGIN`, and the authentication failure counters.
- A configuration form in the page sends writes through the harness (`POST /api/servers/<id>/config`), which signs them. The
  Command tab can still send an unsigned `config put`, which is refused with the authentication error, and that is a useful
  thing to see. The Try buttons and the command reference gain entries for `kv status`, `replicate status` and the `config`
  operations (marked "needs a signed request" for `put` and `delete`).

## 5. Test plan

**Key-value profile.**

1. The existing `KvPluginTest` and `KvPluginIT` pass unchanged with replication off and with the engine absent.
2. Limits come from configuration: a server started with a 10-byte value limit refuses an 11-byte value, and a test that
   greps for the old constants finds none in code.
3. With replication on, all of the engine's in-process swarm tests apply to `kv`, run through the `kv` commands (a `put` on one
   server is found on the others, a `delete` is seen everywhere, a joining server receives every pair).
4. Pairs held before the engine appeared are replicated once it does.
5. A new key is refused above `max.records` locally, and accepted from a peer up to the soft ceiling, with the overrun
   counted.

**Configuration profile.**

6. A signed `config put` on one server reaches every server, and each applies it and reports `APPLIED_LIVE`.
7. A value that fails the consumer's validation is refused on the writing server and replicated nowhere.
8. A swarm-scope value that would make a server's override invalid is refused, naming that server.
9. Overlay: a server override beats the swarm value for the properties it sets, the rest come from the swarm value, and
   removing an override with `null` restores the swarm value.
10. `ifVersion` with a stale version is refused with a conflict, and with the current version succeeds.
11. A server whose consumer returns `RESTART_REQUIRED`, `REJECTED` or no consumer stores and forwards the value and reports
    that outcome, and the swarm still converges.
12. Authentication:
    - a correctly signed `put` and `delete` are accepted;
    - a request signed with the wrong secret, with an altered value, with another document or scope, or with another server's ID
      is refused;
    - a timestamp outside the window is refused, and so is a repeated nonce;
    - an unknown key ID is refused, with the same message as a bad MAC;
    - a server with no secret refuses every write and still answers `get`, `list` and `status`;
    - a secret shorter than the minimum is refused when the plugin starts, naming the key;
    - reads need no proof.
12a. Rotation: with `k1` and `k2` both configured, requests signed with either are accepted. After `k1` is removed, a request
    signed with it is refused. A changed secret file takes effect after `config.secret.reload.millis` without a restart.
12b. The secret never appears in any log line, status reply, snapshot or replicated record (a test searches all of them for it).
12c. `valueText` survives exactly: a document containing Unicode, characters that need escaping, very large and very small
    numbers, and nested arrays and objects is stored and read back unchanged, and a request whose `valueText` differs by one
    byte from the signed text is refused.
12e. Throttling: failures from one address block that address, after the configured count within the window, and only that
    address. A correctly signed write from a blocked address is refused until the block expires. A request with no `auth` counts
    as a failure. The table of addresses stays within its bound. The counters appear in `status`.
12f. A timestamp out of range gets the specific message with the server's time, and every other authentication failure gets
    the same generic message.
12d. A write accepted by one server is applied on servers that hold no secret.
13. A document containing a non-replicable property is refused on write and on receipt.
14. After every server restarts, the documents are restored from snapshots, and a stale snapshot loses to a newer record
    from a peer.
15. A configuration write on one side of a partition and a different write on the other converge to the newer after it heals.

**Acceptance.**

| Case | Target |
| --- | --- |
| A `config put` on one of 5 servers at `--gossip-fast` | applied on all within 5 seconds, every time in 100 runs |
| An invalid value | refused on the receiving server, applied nowhere, every time |
| A swarm restart | all documents restored from snapshots |
| An unsigned, altered, stale, replayed or mis-addressed write | refused every time |

## 6. Implementation plan

This document describes the target. The order of work is in [`REPLICATION_PLAN.md`](REPLICATION_PLAN.md). Key-value replication
is built first, inside the key-value plugin (Phases 1 to 3). The key-value profile in section 2 is reached in Phase 4, when the
engine is extracted and kv is moved onto it. The configuration profile in section 3 is Phases 5 and 6: Phase 5 is the namespace,
documents, validation and apply, and signed writes with one secret; Phase 6 is rotation, secret reloading and throttling. The
command-line tool (decision 39) comes later.

## 7. What this does not do

- It does not make any existing setting live-reloadable. That is each owning plugin's work.
- It does not identify individual administrators. Anyone who holds the secret can change configuration, and nothing says which
  of them did. The version records which server accepted the write, not who sent it.
- It does not encrypt configuration in transit, and it authenticates only the server a write is sent to. Peers trust each
  other, so a compromised server inside the swarm could inject configuration. The record carries a reserved `signature` field
  so signed documents, verified by every server, can be added later without changing the format.
- It does not provide the secret to anyone. Getting the secret onto each accepting server, and to the people who send writes,
  is an operational task outside these designs.
- It does not roll out changes in stages, keep a history, or roll back.
- It does not guarantee that two administrators changing a setting at the same moment, through different servers, do not
  overwrite each other. A conditional write narrows that, and does not close it.

## 8. Review outcomes

| Point | Outcome |
| --- | --- |
| Per-property overlay, or replace the whole document | Per property, with `null` removing an override (decision 44). |
| A write for a document the receiving server cannot validate | Refused unless `unvalidated` (decision 45). |
| Who may read configuration | Any server, with no proof (decision 46). |
| The soft ceiling for configuration | None: over-limit documents are refused and reported (decision 47). |
| Document names | The plugin's instruction, with `.suffix` for more than one (decision 48). |
| An administrator tool | Deferred (decision 39). `ConfigClient` and the harness first. |
| The shared limits loader | One class in the host module (decision 49, engine document). |
| The refusal error code | `INVALID_PARAMETERS` with a clear message (decision 38). |
| Where the secret lives | A file per key ID, restricted to the current user where possible (decision 41). |
| Canonical JSON or raw bytes | Settings sent as a string and signed as exact text (decision 37). |
| Throttling failed attempts | By sender address (decision 40), which needs `RequestContext` in the host. |
| A separate "writes enabled" switch | No. Holding the secret decides (decision 43). |
| Clock agreement | Required within a configurable window (decision 42). |

## 9. Things still to settle during implementation

These are detail, not direction:

1. The exact names and defaults of the throttle settings, once there is real traffic to tune them against.
2. How the harness restricts a generated file on each platform. POSIX permissions and Windows access control lists differ, and the
   code has to handle both, and to say so when it cannot.
3. Whether the harness's configuration form shows the secret's key ID in use, and how it reports a rotation in progress.
