# Raft plugin: phased implementation plan

| Document metadata | Details |
| --- | --- |
| Status | Plan, for review. Nothing in this document is built. |
| Last updated | 2026-10-31 |
| Applies to | A new `plugins/raft-plugin`, and the test harness and page in `socket-server-swarm`. The host, the gossip plugin and the kv plugin do not change. |
| Background | [`DISTRIBUTED_SYSTEMS_PRINCIPLES.md`](DISTRIBUTED_SYSTEMS_PRINCIPLES.md) says why membership alone cannot elect a leader. The algorithm is the one in Ongaro and Ousterhout, *In Search of an Understandable Consensus Algorithm* (2014), mainly its Figure 2. |

## 1. Summary

The plugin adds a replicated log with Raft leader election, as a `raft` instruction. It stands alone: it needs neither gossip
nor kv. A command committed to the log is applied, in order, to a plugin on every server. Load the unchanged kv plugin beside
it and the result is a strongly consistent key-value store, next to today's last-writer-wins one, under the same chaos scripts.

### Decisions already made

| # | Decision |
| --- | --- |
| 1 | Raft has no dependency on any other plugin. It runs alone, with gossip, with kv, or with both. |
| 2 | Raft keeps its log, term and vote in its own files. It does not store them in kv, because kv's newest-write-wins replication could overwrite a committed entry. |
| 3 | The kv plugin does not change. On a Raft cluster it runs with `replication=off`, which is already its shipped default. |
| 4 | A log entry is an instruction and its parameters, opaque to Raft. Applying one means calling that instruction on this server, so kv is the first user, not a special case. |
| 5 | Gossip never decides who votes, and never carries Raft messages. Voters come from a saved `bootstrap` command, and later from the log. |
| 6 | Raft messages go through the host's `KeepAliveClient` with the server's ID as its source, so the harness's network faults and call log apply without any further work. |
| 7 | Loading kv later gives distributed kv: Raft replays the log into it. Plugins load only at server start, so this means restarting servers one at a time. |
| 8 | A direct `kv put` on a cluster member still succeeds and affects only that server. kv is unchanged, so it cannot refuse. This is documented, not prevented. |

## 2. Principle and working method

The rules and the test-driven protocol of [`REPLICATION_PLAN.md`](REPLICATION_PLAN.md) sections 1 and 2 apply as they stand: a
phase ends with something demonstrable and a passing suite, earlier tests stay unchanged, no limit is hard-coded, each phase has a
"not yet" list, and nothing is committed until asked. Three additions, because a consensus bug usually passes ordinary tests:

1. **The core is written first, as pure logic.** It has no clock, threads, files or sockets, so a simulator can drive it through
   thousands of hostile schedules, repeatably from a seed.
2. **Safety properties are checked after every simulated step**, not only at the end of a test.
3. **The checks must be shown to catch bugs.** Each is run against a deliberately broken variant of the core (section 7). A check
   that has never failed proves nothing.

## 3. Design

### 3.1 Parts

| Part | Job | Notes |
| --- | --- | --- |
| `RaftNode` | The algorithm: takes an event, returns actions | Pure. Time and randomness are passed in. |
| `LogStore`, `MetaStore` | Durable log, and term and vote | Interfaces. Files in production; a test version loses unsynced writes in a crash. |
| `RaftRunner` | One event loop per server: timers, a sender per peer, group sync | Waits on the `RunGate` before each piece of work, so a frozen server does nothing. |
| `TcpRaftTransport` | Sends `vote` and `append` | `KeepAliveClient` with `source` set. |
| `Applier` | Applies committed entries in order through the server's own port | Also uses a client with `source` set. A client without one would make the harness's call log take these calls for a client's. |
| `RaftPlugin` | The `raft` instruction | Thin. |
| `RaftConfig` | Settings from `raft-defaults.properties`, `-Draft.config` and `server.<id>.` overrides | The same scheme as `KvConfig`. |

### 3.2 Operations of the `raft` instruction

| Operation | Called by | What it does |
| --- | --- | --- |
| `bootstrap` | Operator or harness, once per server | Saves the cluster ID and the voters with their addresses. Repeating the same one is fine; a different one is refused. |
| `propose` | Clients | Commits a command and returns its result once applied on the leader. |
| `read` | Clients | Runs a command on the leader after confirming it is still leader, without adding to the log. |
| `status` | Clients, harness | Role, term, leader, commit and applied positions, agreement checkpoint, what applying is waiting for, counters. |
| `log` | Clients, page | A page of entries, for inspection. |
| `vote`, `append` | Peers | The two Raft requests. Each carries the cluster ID, and a mismatch is refused. |

`propose` and `read` answer with one of:

| Answer | Meaning | What a client should do |
| --- | --- | --- |
| The command's result | Committed and applied (or read, on a leader confirmed current) | Done. |
| `NOT_LEADER`, with the leader if known | Not started here | Retry on that leader. |
| `REFUSED` | Too large, or not an allowed instruction | Do not retry. |
| `OUTCOME_UNKNOWN` | Accepted, then leadership was lost or time ran out. **It may still commit.** | Read before retrying. |

A server that was never bootstrapped is idle and costs nothing, so the plugin can be loaded on every server in a harness.

### 3.3 Applying entries

Each committed entry is applied once per run of the server, in order. What happens next depends on the reply:

| Reply | Action | Why |
| --- | --- | --- |
| Success, or an error caused by the command (bad parameters, a limit) | Record the outcome and move on | Every server gets the same answer, so they stay in step. |
| `UNKNOWN_INSTRUCTION` | Pause, keep committing, report `waitingFor` | The plugin is not loaded here yet. Skipping would leave a permanent gap. |
| No reply, or an internal error | Pause and retry | Not known to happen on every server, so it must never be skipped. |

- `propose` refuses any instruction not in `apply.instructions`, default `kv`. This keeps a typo from pausing every server for good,
  and keeps `exec` out of the log. A listed instruction not yet loaded still commits, which is what makes late activation work.
- The applied position is not saved. After a restart Raft replays the whole log, which rebuilds kv's memory-only store.
  During that time a direct `kv get` can be behind, and `raft read` waits until it has caught up.
- **Agreement checkpoint.** kv's root hash cannot show agreement, because it includes each server's own write timestamps. Raft keeps
  a running hash over each entry's position, term, command and outcome class, and `status` reports its value at the last multiple of
  `checkpoint.interval`. Two servers reporting different hashes for one position have diverged, which also catches a kv limit that
  differs on one server.

### 3.4 Storage

- One directory per server, `<data.dir>/<serverId>`. `data.dir` has no default: `bootstrap` is refused without it, rather than
  silently keeping consensus state somewhere temporary.
- The log is an append-only file of records: length, CRC, term, position, command. At start, reading stops at the first incomplete or
  corrupt record and truncates there. A bad record before the end of the file is reported and stops the server instead, because
  that is damage rather than a torn write.
- Term and vote are an append-only file of CRC-checked records, where the last valid one wins, rewritten now and then. This avoids the usual
  rename-then-sync-the-directory approach, which is not available on Windows, the development platform.
- **Sync rules.** Term and vote are synced before a vote is answered or a request is sent in the new term. Entries are synced before an
  `append` is confirmed, and before a leader counts itself. Syncs are grouped across waiting entries.

### 3.5 Limits and settings

All of these live in `raft-defaults.properties`. Timings are starting points, to be set from measurements in Phase 3.

| Setting | Default | Constraint |
| --- | --- | --- |
| `heartbeat.millis` | 100 | |
| `election.timeout.min.millis`, `.max.millis` | 500, 1000 | Well above the heartbeat. |
| `connect.timeout.millis`, `request.timeout.millis` | 200, 400 | Below the election timeout. A request lost by a network rule waits out the connect timeout, so each peer has its own sender and one slow peer cannot delay the others. |
| `max.command.bytes` | 32768 | Must leave room in the host's 64 KB request line (`WorkerRunnable.MAX_LINE_LENGTH`), and hold kv's 16 KB value once encoded. |
| `max.append.bytes` | 48000 | Must also fit in that line. A larger backlog goes in several requests. |
| `apply.instructions` | `kv` | |
| `checkpoint.interval` | 64 | |
| `propose.timeout.millis` | 5000 | After this the answer is `OUTCOME_UNKNOWN`. |

### 3.6 Harness

- `--plugins-dir` applies to the whole harness, so every swarm loads the same plugins. Raft is switched on per swarm: `start <name> <n> raft`
  in the console and `"mode":"raft"` in `POST /api/swarms`. The harness starts the servers, then sends `bootstrap` to each.
- A generated `RaftConfigFile`, like `GossipConfigFile`, sets `data.dir` to a directory the harness owns and deletes. A restart keeps a
  server's directory, stopping a swarm deletes its directories, and restart already keeps a server's ports, so saved addresses stay right.
- `KvConfigFile` gains `server.<id>.replication=off` lines for Raft swarms, so `--kv-replicate` can stay on for other swarms in the same
  harness. kv already reads such lines. A test proves a Raft server's kv is not replicating, since both at once would be silently wrong.
- `SwarmSnapshot` adds each server's `raft status`, asked once of a server without the plugin, as it does for kv.

## 4. Shortcuts in the early phases

| Shortcut | The full version | Removed in |
| --- | --- | --- |
| Every server of a Raft swarm is a voter, and the harness limits such a swarm to 9. | Non-voting members | Later |
| The voters are fixed at `bootstrap`. The harness refuses Add and Remove on a Raft swarm. | Membership changes through the log | Phase 7 |
| The log is kept whole, so it grows and replays get slower. | Snapshots with per-plugin adapters | Phase 8 |
| A retried `propose` can apply twice, which a kv `put` tolerates. | Client sessions that drop duplicates | Later |
| A rejoining server can force a needless election. This is safe but disruptive. | Pre-vote and check-quorum | Phase 6 |
| Addresses are those given to `bootstrap`. | Optional refresh from gossip when present | Later |

## 5. The phases

### Phase 1: the algorithm, in simulation

**Delivers.** `RaftNode` with elections, log replication and commit, including the rule that a leader commits entries from earlier terms
only by committing one from its own (the paper's Figure 8), and a no-op entry on winning. An in-memory store that loses unsynced writes in a crash.
A seeded simulator with message loss, delay, duplication and reordering, partitions, and crashes. The safety checks of section 7.

**Demo.** A test run that prints a schedule from a seed: elections, a partition, a crash, and every check passing, followed by each broken
variant being caught.

**Not yet.** Files, sockets, threads, the plugin.

**Done when.** Thousands of seeded schedules pass, any failure can be repeated from its seed, and all of the variants in section 7 that apply are caught.

**Review gate.** Everything later rests on this core. Stop and review it line by line against the paper's Figure 2.

### Phase 2: durable storage

**Delivers.** `FileLogStore` and `FileMetaStore`, conforming to the same tests as the in-memory stores, with the format and sync rules of section 3.4.

**Tests.** A log file cut at every byte offset of its last record recovers to the last whole record. A corrupt record in the middle is reported, not
skipped. A fake file layer that drops unsynced data shows nothing is confirmed before it is synced. Term and vote survive a crash at each point of a
rewrite. Truncating a conflicting tail survives a crash part way through.

**Note.** Chaos scripts cannot test this. The harness's `kill` is a clean stop inside one process, and the operating system keeps what was written, so
every script would pass with syncing removed. Crash durability is only proved here.

**Not yet.** Snapshots, compression.

### Phase 3: Raft alone, on real servers

**Delivers.** `RaftRunner`, `TcpRaftTransport`, `RaftPlugin` with `bootstrap`, `propose`, `status` and `log`, the module in `plugins/pom.xml`, and the harness
work of section 3.6. No entry is applied to anything yet. New `SwarmScript` verbs: `startRaft`, `propose`, `raftStatus`, `leader`, `awaitLeader`, `awaitCommit`.

**Demo.** A five-server Raft swarm with neither gossip nor kv loaded. Stop the leader and watch a new one elected. The calls appear on the page without any page changes, under Other.

**Tests.** Scripts in `scripts/chaos`:

| Script | What it proves |
| --- | --- |
| `raft-leader-loss` | Killing the leader elects another within a measured target, and nothing committed is lost. |
| `raft-frozen-leader` | A leader frozen past the election timeout is replaced. Thawed, it steps down without committing anything. |
| `raft-partition` | In a 4/6 split with the leader on the small side, that side commits nothing, the large side elects a leader and commits, and after healing all logs agree. |
| `raft-restart-all` | Every server stopped and restarted keeps every committed entry. |
| `raft-lossy` | Delay and loss on the network slow commits without breaking agreement. |

Also: a restarted server keeps its vote, an unbootstrapped server is idle, and swarms do not cross-talk (cluster ID).

**Done when.** Timings are chosen from measurements and written down, the scripts pass repeatedly, and existing suites and scripts still pass.

**Review gate.** Consensus works on its own. Stop and look at it.

### Phase 4: applying to plugins, and distributed kv

**Delivers.** The `Applier` and its rules (section 3.3), `apply.instructions`, the `read` operation with a leadership check, the agreement checkpoint, and the `SwarmScript` verbs `raftRead` and `awaitApplied`.
A history checker for live runs (section 7).

**Demo.** Raft and kv loaded: `propose` a `kv put` on one server, and read it with `raft read` through any of them.

**Tests.**

| Script or test | What it proves |
| --- | --- |
| `raft-kv-partition` | The minority side refuses writes, and no acknowledged write is lost. The history checker passes. |
| `raft-kv-restart-all` | All data returns, though kv itself keeps nothing. |
| `raft-kv-late` | Commit `kv` commands with kv not loaded, then restart servers one at a time with it: each catches up, and they all agree. |
| Limit mismatch | One server with a lower kv limit shows up as a checkpoint difference. |
| A pause | A server without kv reports `waitingFor`, and the others carry on. |
| A typo | `propose` refuses an instruction not in `apply.instructions`. |

**Done when.** The four combinations (Raft alone, with gossip, with kv, with both) start and pass, and kv's own tests pass unchanged.

**Review gate.** The feature is usable. Stop and look at it.

### Phase 5: seeing it

**Delivers.** On the page:

- The leader marked on the canvas with its term, including a stale leader, shown as such.
- A swarm line: `raft: term 7 · leader 003 · commit 412 · agree 5 of 5`.
- Raft facts in the inspector, and a page of the log.
- Filter chips for votes and appends.
- Leader and term kept in the rewind history, so a replay shows elections happening.
- The `raft` reference in the Command tab, stating that writes must go through `propose`.

A new script `compare-split`: runs one split on a last-writer-wins swarm and a Raft swarm at once and reports what each did.

**Demo.** The two swarms side by side through one partition: both sides of one keep taking writes and one write is lost, while one side of the other refuses and nothing is lost.

### Phase 6: staying stable

**Delivers.** Pre-vote, so a server returning from isolation does not force an election, and check-quorum, so a leader that cannot reach a majority steps down rather than leaving clients waiting.

**Tests.** A server isolated for a minute rejoins without changing the term. A leader cut off from its majority steps down within a bounded time. The simulator's checks still pass with both on.

### Phase 7: changing the voters

**Delivers.** Adding and removing one voter at a time through the log, and Add and Remove on a Raft swarm in the harness. The addition catches up before it gets a vote.

**Tests.** Changes during partitions and crashes in the simulator, including the known trap in single-server changes: a new leader must commit an entry in its own term before starting a change. A removed leader hands over cleanly.

### Phase 8: snapshots

**Delivers.** Log compaction, a snapshot request for a server too far behind, and a per-plugin adapter interface. The kv adapter uses kv's existing `list`, `get` and `put`, so kv still does not change.

**Tests.** A server restored from a snapshot agrees with the others. **Entries for a plugin not loaded are never compacted away**, so late activation still works after compaction.

### Later, only if needed

Client sessions that drop duplicates; non-voting members; address refresh from gossip; leader transfer; authentication between peers (the
trusted-LAN assumption in [`DISTRIBUTED_SYSTEMS_PRINCIPLES.md`](DISTRIBUTED_SYSTEMS_PRINCIPLES.md) still applies); locks with fencing tokens, as a plugin on top of the log.

## 6. Phases 1 and 2 as test-first steps

**A. Terms and elections** (`RaftNode`)

1. A new server is a follower in term 0. An unbootstrapped one never starts an election.
2. An election timeout makes it a candidate: term up, votes for itself, asks every voter.
3. A majority of votes makes it leader, and a single-voter cluster elects itself at once.
4. A server votes at most once per term, and only for a log at least as up to date as its own (last term, then length).
5. Any message with a higher term makes a server a follower in that term, and any with a lower term is refused.
6. A split vote times out and retries in a new term, with randomized timeouts.

**B. Replication** (`RaftNode`)

7. A new leader appends a no-op, and heartbeats at the set interval.
8. A proposal on a follower is refused with the leader's name if known.
9. `append` is refused if the entry before the new ones does not match.
10. A leader backs up on refusal until logs match, then the follower's conflicting tail is replaced, never a committed entry.
11. An entry commits once a majority holds it and it is from the leader's term.
12. An older-term entry on a majority does not commit by counting alone (the Figure 8 schedule, as an exact test).
13. Followers learn the commit position from the leader. It never moves backward.
14. Actions are ordered so nothing is sent or confirmed before what it relies on is synced.

**C. The simulator**

15. The same seed gives the same schedule, and a failure prints its seed.
16. It delivers, drops, duplicates, delays and reorders messages, partitions servers, and crashes and restarts them, losing unsynced writes.
17. The safety checks run after every step.
18. Each broken variant in section 7 is caught.

**D. Files** (`FileLogStore`, `FileMetaStore`)

19. The store contract tests pass against both the in-memory and file versions.
20. A record written and synced is read back after reopening.
21. A file cut at every offset of its last record recovers to the last whole record.
22. A corrupt record in the middle stops the server, with a message saying where.
23. Term and vote: the last valid record wins, including across a crash during a rewrite.
24. Truncating a tail is durable, and a crash part way leaves either the old or new tail, both of them valid.
25. `data.dir` missing or unwritable refuses `bootstrap` with a clear message.

## 7. How correctness is checked

**Properties checked after every simulated step**

| Property | Meaning |
| --- | --- |
| Election safety | At most one leader per term. |
| Log matching | Two logs with the same term at a position are identical up to it. |
| Leader completeness | Every committed entry is in the log of every later leader. |
| State machine safety | No two servers apply different entries at one position. |
| No lost commit | A committed entry survives any permitted set of crashes. |

**Broken variants the checks must catch.** These exist only in test code, and no production setting can turn them on.

| Variant | Expected to break |
| --- | --- |
| Votes are not saved before replying | Election safety, after a crash |
| Entries are confirmed before they are synced | No lost commit |
| Older-term entries commit by counting (Figure 8) | Leader completeness |
| Votes ignore how up to date the candidate's log is | Leader completeness |
| A follower truncates without a real conflict | Log matching |

**The history checker (Phase 4).** Scripts record each client operation's start, end and answer, and a checker decides whether some valid order explains them (linearizability), per key. An
`OUTCOME_UNKNOWN` write may or may not have happened, and the checker must allow both. It is itself tested on hand-written histories, valid and invalid, and run on a kv swarm with `replication=on`,
where it must find violations under a partition. If it can't, it isn't working.

## 8. Risks

| Risk | What this plan does about it |
| --- | --- |
| Subtle safety bugs that ordinary tests miss | The pure core, the simulator, the checks and the broken variants, before any socket exists. |
| Durability bugs chaos scripts cannot see | Section 3.4's fake file layer, as the Phase 2 note explains. |
| False elections when one JVM hosts many servers and pauses | Each Raft swarm has at most 9 servers, timings are measured in Phase 3, and other swarms' load is part of that measurement. |
| Slow disks: every commit syncs | Group sync. Measured in Phase 3, with the number written down. |
| Deadlock from blocking in a class initializer on a virtual thread | No file or resource reads in static initializers. The harness hit this at 100 servers through `KvPlugin`, and now starts servers on platform threads. |
| A direct `kv put` diverges a cluster member | Documented and stated in the page's reference. It cannot be prevented without changing kv, and the checkpoint does not see it, because it bypasses the log. |
| Long replays as the log grows | Accepted until Phase 8. |

## 9. Questions for review

Each has a default the plan assumes, so none of them blocks Phase 1.

| Question | Default |
| --- | --- |
| Should `raft propose` be the only way in, or should there be a shorthand such as `raft put`? | `propose` only, which keeps Raft unaware of kv. |
| Should Phase 5 wait for Phase 6? | No. Seeing elections first makes stability problems easier to find. |
| Update `DISTRIBUTED_SYSTEMS_PRINCIPLES.md` section 11, which says consensus is absent? | Yes, at the end of Phase 4, along with the plugin's own guide. |
