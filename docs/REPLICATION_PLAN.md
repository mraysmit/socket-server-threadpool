# Replication: phased implementation plan

| Document metadata | Details |
| --- | --- |
| Status | Plan, approved. **Phases 1 and 2 are complete** (2026-10-20). This is the review gate after Phase 2: Phase 3 has not been started. |
| Last updated | 2026-10-20 |
| Applies to | [`plugins/kv-plugin`](../plugins/kv-plugin), the gossip plugin, the host, and the test harness in `socket-server-swarm` |
| Target design | [`PLUGIN_SERVICES_DESIGN.md`](PLUGIN_SERVICES_DESIGN.md), [`REPLICATION_ENGINE_DESIGN.md`](REPLICATION_ENGINE_DESIGN.md) and [`REPLICATION_PROFILES_DESIGN.md`](REPLICATION_PROFILES_DESIGN.md) describe where this ends up. This document says in what order to build it. |

## 1. Principle

The three design documents describe a general system: a plugin service registry, a namespace-based replication engine, a
configuration plugin with signed writes. That is the destination, not the first step. The first step is to make key-value
data visibly replicate across a swarm, in the simplest way that works, and to prove it in the harness. Each abstraction is
built only when a second user needs it, so that it removes real duplication instead of guessing at it.

Rules for every phase:

1. **A phase ends with something demonstrable in the harness** and a passing test suite.
2. **Each phase leaves the earlier tests passing, unchanged.** A phase that has to change an earlier test is a design
   change and is raised before it is made.
3. **Replication stays off unless it is enabled**, so existing users and tests see no difference.
4. **No limit or tunable is hard-coded** (decision 32). Defaults live in a properties file bundled with the plugin.
5. **A "not yet" list is part of each phase.** Anything on it is left out on purpose, even when it looks easy.
6. **Review gates** follow Phases 2 and 3. Work stops there until the result has been looked at.

## 2. The test-driven protocol

Every change to production code is made test-first, in small steps:

1. **Red.** Write one test for one behaviour. Run it. It must fail, and for the *right reason* (the behaviour is missing, not
   a typo or a compile error). A test that passes the first time proves nothing and is rewritten until it can fail.
2. **Green.** Write the least production code that makes that test pass. No code for a behaviour that no failing test asks
   for. Run the test, then the whole module, and see them pass.
3. **Refactor.** With everything green, improve names and structure, removing duplication, running the tests after each
   change. No behaviour changes here, and no new tests.
4. **Repeat** with the next behaviour.

Working agreements:

- Production code is never written ahead of a failing test. If code turns out to be needed for something no test exercises,
  the test is written first.
- Tests describe behaviour (`aNewerWriteReplacesAnOlderOne`), not implementation.
- Unit tests use fakes for the clock, the peer list and the peer connection, so they are fast and deterministic. A
  real-server test comes at the end of each slice, to prove the pieces work together.
- A bug found later gets a failing test that reproduces it before it is fixed.
- The whole suite of each touched project is run before a phase is called done. The build uses JDK 27.
- Nothing is committed until asked.

## 3. Simplifications in the early phases

Each of these is a deliberate shortcut relative to the design documents. Each is listed with the phase that removes it, so none
becomes permanent by accident.

| Shortcut | Design says | Removed in |
| --- | --- | --- |
| The version's counter `c` is always 0. To keep versions from one server distinct, `w` is made strictly increasing per server (at least one more than the last it issued or saw). The record format already has `c`. | A hybrid logical clock with a counter | Phase 3 |
| Peers come from asking the server's own gossip over loopback (`gossip members`). | A `MembershipView` service | Phase 4 |
| A write is pushed to every `ALIVE` peer. | Fanout and forwarding | Phase 3 |
| Each push opens its own connection. | A shared keep-alive client | Phase 3 |
| A replicated record that would exceed the pair limit is refused and counted. | A soft ceiling | Phase 3 |
| No swarm ID in messages. Peers come only from this server's own gossip, so a push never leaves the swarm. | A swarm ID check | Phase 2 |
| A push that fails is not retried. | Repaired by anti-entropy | Phase 2 |
| The kv plugin holds the replication code. | A separate engine with namespaces | Phase 4 |
| No snapshots. | Opt-in snapshots | Phase 4 |

## 4. The phases

### Phase 1: key-value pairs replicate by push

**Delivers**

- The three size limits (key length, value size, pair count) come from configuration, with defaults in a bundled properties
  file, a per-server override mechanism like gossip's, and clear errors for bad values.
- Each pair carries a version `{w, c, n}` and a deleted flag. The newest version wins. A delete leaves a deletion record.
  `get` and `list` ignore deletion records, and the existing reply shapes do not change.
- A new `apply` operation, for servers to call on each other, merges a list of records by that rule.
- After a local write, if replication is on, the server pushes the record to its `ALIVE` peers, on virtual threads, without
  delaying the writer's reply. A failing peer does not affect the writer or the other peers.
- A new `status` operation reports whether replication is on, the counts, and counters.
- Replication is off by default and is switched on in configuration. It needs the gossip plugin on the same server.
- The harness can enable it (`--kv-replicate`) and generates the kv configuration file the way it does for gossip.

**Demo.** Start five servers with gossip and kv, `put` on one in the Command tab, `get` on each of the others.

**Tests.** The existing kv unit and integration tests pass unchanged. New: configuration, versions and merge rules (including
order-independence), `apply`, the pusher with a fake peer list and a fake connection, the gossip peer reader against canned
replies, and a five-server swarm test in the harness.

**Not yet.** Catch-up for a server that was down, repair of a failed push, root hashes, forwarding, fanout limits, connection
reuse, a full clock, namespaces, snapshots, the service registry, configuration documents, signing.

**Done when.** The demo works, a delete reaches all five servers, a restarted server is the only thing that does not catch up
(that is Phase 2), and the whole suites of both projects pass.

### Phase 2: it converges

**Delivers.** A root hash over the store, a periodic sync that compares hashes with a peer and, when they differ, exchanges
only the differences in pages; a server that starts or restarts pulls everything; deletion records expire by their own
timestamp; the swarm ID check; `status` shows the root hash and each peer's last sync; the harness shows "kv in sync N of M".

**Tests.** A joining server receives every pair; a restarted server is restored from its peers; a stopped server catches up when
it returns; a delete stays deleted; concurrent writes to one key converge to the same value everywhere; a failed push is repaired
by the next sync; two swarms do not replicate into each other.

**Not yet.** Everything in Phase 3 onward.

**Review gate.** This is the first version people can use. Stop and look at it.

### Phase 3: it scales

**Delivers.** Forwarding of a write by the servers that receive it, with a fanout limit; the shared keep-alive client with
stale-connection retry moved into the host (decision 50), used by the gossip plugin, the harness and kv; the full hybrid
logical clock with its counter; the clock-skew refusal; the soft ceiling for replicated pairs.

**Tests.** The efficiency and acceptance tests in the engine design: a hundred servers at `--gossip-fast` timings with
steady writes, readable everywhere within the target, `TIME_WAIT` flat; a partition test using the plugin repository's TCP
relay, with a write on each side and a delete before the split.

**Review gate.** Key-value replication is complete here. The next phases generalise it, and nothing after this point is
needed for key-value data.

### Phase 4: extract the engine (a refactor)

**Delivers.** The plugin service registry, `RequestContext` and the API version check in the host; `MembershipView` published
by gossip; the replication plugin with namespaces and the `replicate` instruction; kv moved onto it behind its unchanged
`kv` instruction; opt-in snapshots; the shared limits loader in the host.

**Done when.** Every Phase 1 to 3 test passes unchanged. This phase adds no behaviour, so any difference is a regression.

### Phase 5: configuration, basic

**Delivers.** The `config` namespace and plugin; documents with per-server overrides; the validator and apply hook; `config
status`; signed writes (one secret, a timestamp and a nonce), with writes refused when no secret is configured; `ConfigClient`;
the harness signs on behalf of its swarms and shows who applied what; persistence to a snapshot.

**Not yet.** Rotation, secret reloading, throttling.

### Phase 6: authentication hardening

**Delivers.** Several key IDs and rotation; secret reloading; throttling by sender address; owner-only secret files; the
related tests.

### Later, only if needed

The operator command-line tool, the digest published in gossip metadata, signed documents, other merge rules, per-namespace
write rate limits.

## 5. Phase 1 as test-first steps

The order is chosen so each step has a failing test to write and a small amount of code to make it pass. It builds from the
inside out, so that the first tests need no sockets.

**A. Configuration** (`KvConfig`)

1. The bundled defaults give key length 256, value size 16,384, 1,000 pairs, and replication off.
2. A configuration file overrides them, and a `server.<id>.` key overrides them for one server.
3. A non-numeric, zero or negative limit, and an unknown key, are rejected with a message naming the key.
4. The plugin applies the configured limits (a key longer than a configured 10 is refused), and reads its file from
   `-Dkv.config`, at start, using its own server ID.

**B. Versions and the store** (`Version`, `VersionedStore`)

5. Versions order by `w`, then `c`, then `n`.
6. `put` then `get` returns the value. `list` is in key order.
7. `delete` removes the key from `get` and `list` but keeps a deletion record, including for a key the store never held.
8. Two writes in the same millisecond, and a clock that goes backwards, still produce strictly increasing versions.
9. Receiving a record with a later `w` moves the local clock past it, so the next local write is newer.
10. `apply` keeps the newer of two records, ignores an equal or older one, and a deletion beats an older write and a write beats
    an older deletion.
11. The result of applying the same records in any order is the same (checked over random orders).
12. Limits: a replicated record beyond the pair limit, or with an oversized key or value, is refused and counted.

**C. The plugin on the store** (`KvPlugin`)

13. The existing plugin tests still pass with the plugin running on the versioned store (a refactor step: no new test, no
    new behaviour).
14. `apply` through the instruction: applies new records, ignores stale ones, reports the counts, and refuses bad shapes with
    `INVALID_PARAMETERS`. It is refused when replication is off.
15. `status` reports replication off or on, the live and deleted counts, and the counters.

**D. Pushing** (`Replicator`, with a fake peer list and a fake connection)

16. With replication on, a local `put` sends one `apply` with that record to each active peer.
17. A `delete` sends the deletion record. A write that is refused sends nothing.
18. With replication off, nothing is sent.
19. A peer whose connection fails does not stop the others or the writer, and the failure is counted.
20. The writer's reply does not wait for the pushes (a fake connection that blocks).
21. A record received through `apply` is not pushed on (forwarding is Phase 3).

**E. Peers from gossip** (`GossipPeers`, against canned replies)

22. It asks the server's own `gossip` instruction for `members`, follows the cursor across pages, and keeps the members rated
    `ALIVE` other than itself.
23. A gossip that is missing, or that errors, gives no peers and is counted, and does not break a write.

**F. Real servers** (in the harness)

24. The harness option `--kv-replicate` and its generated kv configuration file (parsing, file contents, per-server entries).
25. Five real servers with gossip and kv: a `put` on each is readable on all, a `delete` is seen on all, and a server without
    replication does not receive pushes.
26. The harness's Command tab gets the `kv status` and `kv apply` reference entries, and the README describes the option.

## 5b. Phase 2 as test-first steps

**Design in brief.** Each server can summarise its store as a root hash over (key, version, deleted) in key order, excluding deletion
records older than the retention. Two servers with equal roots hold the same records. When they differ, the initiator walks its
keys in pages (as many as fit in `sync.max.bytes`), and the other server answers with the records it holds that are newer or
missing and the keys it wants, so both end with the newer of every record. A reply that would be too large is cut off with a
continuation key. Peer messages carry the swarm ID, and a server refuses another swarm's. One thing in Phase 1 has to change
(see the note after the list): the `status` reply gains fields.

New configuration (all with shipped defaults in `kv-defaults.properties`): `sync.round.millis` (2000), `sync.fanout` (1),
`sync.max.bytes` (32768), `namespace.kv.tombstone.retention.millis` (1800000, matching gossip's terminal retention).

**G. Root hash and expiry** (`VersionedStore`, with a fake clock)

27. Two stores holding the same records have the same root, whatever order they were written in, and any write, deletion, or
    different version changes it.
28. A deletion record older than the retention, by its own timestamp, is dropped, and is left out of the root before it is dropped.
    A live pair is never dropped.

**H. Pages and the other side's answer** (`VersionedStore`, `SyncResponder`)

29. A page of the digest is the next keys in order after a given key, as many as fit a byte budget, always at least one.
30. Given a page, the responder returns the records it holds that are newer than, or missing from, the page, and the keys it wants
    (where the page is newer, or it lacks the key). Records of its own that fall in the page's range but not in the page are
    returned too, and the last page's range runs to the end.
31. A reply too large for the budget is cut off with the key to continue after, and the keys it did not reach are not reported
    as wanted.

**I. The initiator** (`Syncer`, two stores joined by an in-process link)

32. Equal roots: one root exchange and nothing else.
33. Differing stores end identical, with every record the newer of the two, deletions included.
34. A store with many records, and a small page budget, takes several pages and still ends identical.
35. An empty store pulls everything from a full one, through the continuation path.
36. A peer that fails part-way leaves what was applied so far, reports the failure, and a later sync completes.

**J. Over the wire** (`KvPlugin`, `TcpPeerLink`)

37. `sync-root` and `sync-digest` through the instruction, with validation, refused when replication is off, and refused when
    the swarm ID is wrong or missing. `apply` takes an optional swarm ID, checked when present.
38. Pushes carry the swarm ID. The real link carries a sync over real sockets between two in-process servers.
39. `status` shows the root, and per peer its last sync time and result, and the sync counters.

**K. The loop** (`SyncLoop`)

40. A server that starts with replication on pulls everything from a peer as soon as one is found, before its first round.
41. Each round syncs with the configured number of alive peers, chosen at random, and a failing peer does not stop the round.
42. A write whose push failed is repaired by the next round (two real in-process servers).
43. Starting, stopping and restarting a server: a restarted server (empty, new process) is restored; a stopped server catches up
    when it returns.

**L. Real servers and the page** (harness)

44. The harness snapshot carries each server's kv root, and the page shows "kv in sync N of M" for a swarm.
45. Swarm tests: a server added to a running swarm receives everything; a stopped and restarted server is restored; two writers
    of one key converge to the same value; a failed push is repaired.

**A change to Phase 1 tests, announced here before it is made.** `status` was designed to grow, and Phase 2 adds `root` and `sync`
after `counters`. The Phase 1 test `statusFieldsComeInAFixedOrderAndItTakesNoParameters` asserts the exact field list, so it is
updated to the new order. Nothing else in Phase 1 is touched: the push counters keep their own map.

## 5c. Phase 2 result

Built test-first, following section 5b in order, with the same discipline as Phase 1 (each step began with a failing test, and
a test that passed first time was a guard that was then checked by breaking the code it guards).

| Project | Tests | Failures |
| --- | --- | --- |
| `plugins/kv-plugin`, unit | 207 (108 at the end of Phase 1) | 0 |
| `plugins/kv-plugin`, integration (real server processes) | 5, unchanged | 0 |
| `socket-server-swarm` (the harness) | 112 (102 after Phase 1) | 0 |

Delivered as planned in 5b: the root hash and deletion-record expiry; digest pages and the responder's reply with a continuation
key; the initiator; `sync-root` and `sync-digest` and the swarm ID check; `status` with `root` and `sync`; the start-up pull and the
periodic round; the harness snapshot, the page's "kv: N of M in sync" line, the inspector facts, and events; swarm tests with real
servers (a server added to a running swarm, a stopped and restarted server, two writers of one key).

Two earlier tests changed, both announced or forced by the design, and nothing else from Phase 1 was touched:

- The Phase 1 `status` field-order test now lists `root` and `sync` after `counters` (announced in 5b).
- The harness's `ConnectionPoolTest` pins the exact number of requests a snapshot of a plugin-free swarm sends: nine before,
  twelve now, because the snapshot also asks each server once whether the key-value plugin is there, as it already did for gossip.

What the work taught that is worth knowing:

- Rows in a `sync-digest` request have to be in key order, and the plugin now refuses them if not. A test that sent them out of order
  produced wrong answers silently, which is how that gap was found.
- A first version of the swarm tests' "everyone is alive" wait, and later a set of tests comparing `Integer` with the `BigInteger`
  that the JSON decoder returns, were mistakes in the tests and not in the code; each was fixed in the test.
- A push that cannot name its swarm (gossip's status not answering) goes without a swarm ID instead of failing, so the Phase 1
  default-wiring test with a stand-in gossip keeps passing. A comparison always names the swarm, and cannot go ahead without.
- Seven `KvConvergenceTest` scenarios with real servers and a 100 ms round settle in under two seconds, so the periodic loop is
  cheap to test.

Known limits that remain, all from section 3 and all Phase 3: a write is pushed to every alive peer, every push and comparison opens
its own connection, the counter `c` is always 0, and a replicated record over a limit is refused rather than absorbed.

## 6. Phase 1 result

Built test-first, following section 5 in order. Every step started with a test that failed for the reason it was meant to, and a
test that passed the first time was either a deliberate guard (checked by temporarily breaking the code it guards) or was
rewritten.

| Project | Tests | Failures |
| --- | --- | --- |
| `plugins/kv-plugin`, unit | 108 (the original 14 unchanged, 94 new) | 0 |
| `plugins/kv-plugin`, integration (real server processes) | 5, unchanged | 0 |
| `socket-server-swarm` (the harness) | 102 (89 before, 13 new) | 0 |

Delivered as planned: configuration with the three limits in a bundled properties file, versions and the merge rule, `apply`,
`status`, the pusher, peers read from gossip, the harness `--kv-replicate` option, a five-server swarm test, and the Command tab
entries. Shortcuts taken, all from section 3 and none added: the counter `c` is always 0 and `w` is made strictly increasing;
peers are found by a loopback `gossip members` call on every push; a push goes to every alive peer on a connection of its own; a
replicated record over a limit is refused; no swarm ID in messages; no retry.

Things the work taught that are worth knowing:

- Gossip's `members` list includes a row for the server itself. The peer reader skips it, and the swarm test waits for every member
  to be rated alive, itself included, before writing. A first version of that wait was one peer short and made the swarm tests
  time out, which showed up only because they ran against real servers.
- `NumberFormatException` is an `IllegalArgumentException`, so a test that only checks the exception type can pass for the wrong
  reason. The configuration tests check that the message names the setting.
- Existing tests refer to `KvPlugin.MAX_KEY_LENGTH`, `MAX_VALUE_BYTES` and `MAX_PAIRS`. They remain, but now take their values from
  the bundled defaults file at class load, so the code holds no limiting number and the original tests did not have to change.
- A guard against a runaway members cursor uses repeat detection, which needs no page limit in the code.

## 7. What a reviewer should see at the end of Phase 1

- A short list of changes, each step above traceable to a test.
- The unchanged existing kv tests, passing.
- The harness demo working in the UI.
- The "not yet" list unchanged: nothing from Phase 2 onward has crept in.
