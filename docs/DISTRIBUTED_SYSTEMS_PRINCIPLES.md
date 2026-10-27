# Core principles of classic distributed systems

| Document metadata | Details |
| --- | --- |
| Status | Reference. Background reading, not a design. |
| Last updated | 2026-10-27 |
| Applies to | The whole project, especially [`GOSSIP_PLUGIN.md`](GOSSIP_PLUGIN.md), [`REPLICATION_ENGINE_DESIGN.md`](REPLICATION_ENGINE_DESIGN.md) and [`REPLICATION_PROFILES_DESIGN.md`](REPLICATION_PROFILES_DESIGN.md) |
| Sources | Wikipedia, listed in [section 12](#12-sources). Items marked *(standard)* are textbook material not taken from those pages. |

## 1. Summary

This document lists the well-known principles, results and trade-offs that classic distributed systems work rests on. It
is meant as a shared vocabulary for the design documents in this folder. Section 11 maps the principles to the parts of
this project where they matter.

## 2. What a distributed system is

- **Autonomous nodes, each with local memory, that communicate only by passing messages.**
- **Three basic challenges:** components run concurrently, there is **no global clock**, and components **fail
  independently**.
- **Partial view.** Each node sees only part of the system and its input. No node has a complete, current picture.
- **Other typical traits:** the network structure may not be known in advance, hardware and links are heterogeneous, and
  the system can change while it runs.

## 3. The eight fallacies of distributed computing

L. Peter Deutsch and others at Sun Microsystems listed the first seven in 1994, building on four from Bill Joy and Dave
Lyon. James Gosling added the eighth around 1997. Each is an assumption that newcomers make and that eventually fails.

| # | Fallacy |
| --- | --- |
| 1 | The network is reliable. |
| 2 | Latency is zero. |
| 3 | Bandwidth is infinite. |
| 4 | The network is secure. |
| 5 | Topology doesn't change. |
| 6 | There is one administrator. |
| 7 | Transport cost is zero. |
| 8 | The network is homogeneous. |

## 4. Impossibility results and trade-offs

| Result | Origin | Statement |
| --- | --- | --- |
| CAP theorem | Conjectured by Eric Brewer (2000), proved by Seth Gilbert and Nancy Lynch (2002) | A distributed data store cannot guarantee **consistency** (every read sees the most recent write or an error), **availability** (every request to a non-failing node gets a response) and **partition tolerance** (the system keeps working when messages between nodes are lost or delayed) at the same time. Partitions cannot be avoided, so during a partition the real choice is between consistency and availability. |
| PACELC | 2010 | Extends CAP to normal operation. If there is a **P**artition, choose **A**vailability or **C**onsistency. **E**lse, choose **L**atency or **C**onsistency. |
| FLP impossibility | Fischer, Lynch and Paterson (1985) | In a fully asynchronous message-passing system where even one process may crash, no deterministic algorithm can guarantee that consensus is reached. Randomized algorithms and timing assumptions get around it in practice. |
| Byzantine generals bound | Lamport, Shostak and Pease | With unsigned messages, consensus is impossible when n ≤ 3f, so tolerating f arbitrary (malicious) nodes needs at least **3f + 1** nodes. Signed messages allow far more faults. |
| Two generals problem | *(standard)* | Two parties cannot be certain of agreement over a link that can lose messages, however many acknowledgements they exchange. |

Brewer noted in 2012 that "two out of three" is misleading: partition recovery techniques let a system get high levels of
both consistency and availability, and without a partition both can be satisfied.

## 5. Consensus and coordination

- **Properties of consensus:**
  - *Agreement:* all correct processes decide the same value.
  - *Validity (integrity):* the decided value was proposed by some process.
  - *Termination:* every correct process eventually decides.
- **Classic protocols:** Paxos (Lamport), Multi-Paxos and Raft for crash faults; PBFT for Byzantine faults. Google's Chubby
  lock service is a well-known Paxos deployment.
- **Leader election.** Choosing one coordinator, first formulated by LeLann for token rings.
- **Mutual exclusion and deadlock avoidance.** The dining philosophers problem is the classic illustration.

## 6. Time, ordering and causality

- **Logical clocks and happened-before.** Lamport (1978) showed how to order events by cause and effect without physical
  clocks.
- **Vector clocks.** Extend logical clocks to tell concurrent events apart from ordered ones. *(standard)*
- **Physical clock synchronization.** Gives timestamps that are consistent across nodes, within a known error.
- **Synchronizers.** Let algorithms written for synchronous networks run on asynchronous ones.

## 7. Fault tolerance

- **Fault models.** Crash (a node stops) versus Byzantine (a node behaves arbitrarily or maliciously).
- **No single point of failure.** Redundancy and replication so the system survives the loss of any one part.
- **A timeout is suspicion, not proof.** A slow node and a dead node look the same from outside. *(standard)*
- **Self-stabilization.** The system returns to a correct state from any starting state.
- **Fault isolation.** Cell-based architecture and circuit breakers keep a failure from spreading.
- **Safety and liveness.** Safety: nothing bad ever happens. Liveness: something good eventually happens. Every
  correctness property is one or a combination of both. *(standard)*

## 8. Consistency and replication

- **Consistency models**, from strongest to weakest: linearizability, sequential consistency, causal consistency, and
  **eventual consistency** (replicas converge once updates stop).
- **Quorums.** With N replicas, choosing read and write quorums so that R + W > N makes every read overlap the latest
  write. *(standard)*
- **ACID versus BASE.** Transactional guarantees versus basically available, soft-state, eventually consistent designs.
  *(standard)*

## 9. Messaging semantics

- **Delivery guarantees:** at-most-once, at-least-once and exactly-once.
- **Idempotency.** Exactly-once is usually achieved by making operations safe to repeat, not by the network.
- **Loose coupling.** Asynchronous events and publish–subscribe let senders and receivers change independently.

## 10. Transparency, scalability and architecture

### Transparency

The system should hide the fact that it is distributed. These goals can never be met fully: distance, for example, always
shows up as latency.

| Kind | What is hidden |
| --- | --- |
| Access | Differences in how resources are reached. |
| Location | Where a resource physically is. |
| Migration | That a resource has moved. |
| Relocation | That a resource moved while in use. |
| Replication | That there are several copies. |
| Concurrency | That other users share the resource. |
| Failure | Failures and recovery of components. |
| Persistence | Whether a resource is in volatile or permanent storage. |

### Scalability

Marc Brooker's definition: a system is scalable in the range where the marginal cost of additional work is nearly
constant. Measure latency at the 99th percentile, because the median and the average hide the slow requests users notice.

### Architecture

- **Resource sharing:** shared memory, shared disk, shared nothing.
- **Basic architectures:** client–server, three-tier and n-tier, peer-to-peer.

## 11. Where these principles appear in this project

| Principle | Where it matters here |
| --- | --- |
| Partial view, eventual consistency | Gossip membership is eventually consistent: nodes can disagree for a while. See [`GOSSIP_PLUGIN.md`](GOSSIP_PLUGIN.md). |
| A timeout is suspicion, not proof | The gossip plugin treats a timeout as suspected unreachability, not as proof a process stopped. |
| Consensus is not free | Membership alone does not elect a unique leader, grant exclusive ownership or guarantee a command runs once. Any of those needs a consensus protocol. |
| Fallacies 1, 2, 5 | Peer retries with bounded backoff, jittered rounds, and partition recovery tests in the gossip plugin. |
| Fallacy 4 | Not addressed yet: the host, gossip and kv accept unauthenticated requests on a trusted LAN. The proposed configuration profile would require an HMAC proving the sender knows a shared secret. See [`REPLICATION_PROFILES_DESIGN.md`](REPLICATION_PROFILES_DESIGN.md). |
| Logical time and ordering | Gossip orders records by a per-boot heartbeat counter and never compares clocks between nodes. Kv versions are (wall-clock milliseconds, counter, server ID), pushed forward by any later timestamp received, and concurrent writes to one key resolve by last-writer-wins, so the losing write is silently dropped. The design proposes a full hybrid logical clock with a skew bound. See [`REPLICATION_ENGINE_DESIGN.md`](REPLICATION_ENGINE_DESIGN.md), section 5.3. |
| CAP during a partition | The replication engine chooses availability: a partitioned server keeps accepting writes, and both sides merge after the partition heals. A deletion can come back if the partition lasts longer than the retention period. |

## 12. Sources

- [Distributed computing](https://en.wikipedia.org/wiki/Distributed_computing), Wikipedia
- [Fallacies of distributed computing](https://en.wikipedia.org/wiki/Fallacies_of_distributed_computing), Wikipedia
- [CAP theorem](https://en.wikipedia.org/wiki/CAP_theorem), Wikipedia
- [Consensus (computer science)](https://en.wikipedia.org/wiki/Consensus_(computer_science)), Wikipedia
- [Transparency (human–computer interaction)](https://en.wikipedia.org/wiki/Transparency_(human%E2%80%93computer_interaction)), Wikipedia
