# Functional RAFT Library for Scala 3

A **purely functional** implementation of the [Raft consensus algorithm](https://raft.github.io/) in Scala 3. The core protocol logic has zero side effects — every state transition is a pure function that returns the new state and a list of effect descriptors. Your runtime interprets the effects, giving you complete control over networking, storage, and timers.

**Inspired by:** [etcd/raft](https://github.com/etcd-io/raft) · [MicroRaft](https://microraft.io/) · [tikv/raft-rs](https://github.com/tikv/raft-rs) · [OpenRaft](https://github.com/datafuselabs/openraft) · [SOFAJRaft](https://github.com/sofastack/sofa-jraft)

---

## Why This Library?

Most Raft libraries are **frameworks** — they own the event loop, manage threads, and call your code through callbacks. This library is different. It's a **state machine** that you drive:

```scala
// The entire Raft protocol is behind this single pure function call
val transition = RaftLogic.onMessage(state, message, config, lastLogIndex, lastLogTerm, clusterSize)

transition.state   // → the new node state (Follower, Candidate, or Leader)
transition.effects // → List[Effect] — what your runtime should do next
```

No threads are spawned. No network connections are opened. No disk writes happen. The function takes state in, and returns state and effects out. **Effects are data** — plain Scala objects you pattern match on and execute however you want. This means:

- **Testability**: test any Raft scenario with pure function calls — no mocks, no waits, no flakiness
- **Portability**: runs on JVM, Scala.js, GraalVM native — anywhere Scala runs
- **Composability**: embed the protocol in any architecture — Cats Effect, ZIO, Akka, or plain Scala
- **Determinism**: same input always produces the same output, making debugging and formal verification straightforward

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                  RaftNode[F[_], M]                       │
│          Event-driven runtime (Cats Effect + FS2)        │
├──────────────────────────────────────────────────────────┤
│          Pure Logic — RaftLogic.onMessage()              │
│   No I/O, no side effects, fully deterministic           │
│                                                          │
│   Pre-Vote · Leader Stickiness · Joint Consensus         │
│   ReadIndex · Lease Reads · Leadership Transfer          │
│   Batching · Pipelining · Parallel Replication           │
│   Multi-Raft Groups · Transport Multiplexing             │
├──────────────────────────────────────────────────────────┤
│          Effect ADT — what should happen next            │
│   SendMessage · Broadcast · PersistHardState             │
│   AppendLogs · CommitEntries · ResetElectionTimer · ...  │
├──────────────────────────────────────────────────────────┤
│          SPI — pluggable infrastructure                  │
│   Transport[F] · LogStore[F] · StableStore[F]            │
│   StateMachine[F,R] · TimerService[F]                    │
└──────────────────────────────────────────────────────────┘
```

The SPI (Service Provider Interface) layer defines abstract traits for all infrastructure concerns. The library ships with in-memory implementations for testing; you provide production implementations for your deployment environment.

---

## Features

### Core Protocol

| Feature | Description |
|---------|-------------|
| **Leader Election** | Randomized timeouts, majority voting, automatic step-down on higher terms |
| **Log Replication** | AppendEntries with consistency check, conflict detection, and automatic repair |
| **Commit Tracking** | Median-based commit index advancement with current-term safety check |
| **Log Matching** | Ensures log consistency across all nodes (the Log Matching Property from §5.3 of the Raft paper) |

### Advanced Protocol Extensions

| Feature | Description |
|---------|-------------|
| **Pre-Vote** | Two-phase election that prevents partitioned nodes from disrupting the cluster with unnecessary term inflation |
| **Leader Stickiness** | Followers reject votes while they have a healthy leader, reducing unnecessary elections |
| **Joint Consensus** | Safe membership changes via dual-quorum transitions — never lose availability during reconfiguration |
| **Linearizable Reads (ReadIndex)** | Strong consistency reads by confirming leadership via heartbeat quorum |
| **Lease-Based Reads** | Zero-network-round-trip reads when bounded clock skew is acceptable |
| **Leadership Transfer** | Graceful leader handoff to a target node — useful for rolling upgrades and load balancing |
| **Learner Nodes** | Non-voting members that receive log replication but don't participate in elections or quorum |
| **Witness Nodes** | Lightweight voting members for tie-breaking without full log storage |
| **Log Compaction** | `InstallSnapshot` request/response for snapshotting and log truncation |

### Performance Optimizations

| Feature | Description |
|---------|-------------|
| **Batching** | Combine multiple commands into a single AppendEntries RPC — dramatically reduces per-entry overhead |
| **Pipelining** | Send new AppendEntries requests without waiting for acknowledgments — critical for WAN deployments |
| **Parallel Replication** | Replicate to all followers concurrently — a slow follower doesn't delay communication with others |

### Multi-Raft Group Support

| Feature | Description |
|---------|-------------|
| **MultiRaftNode** | Coordinator managing multiple independent consensus groups on one node — dynamic create/remove lifecycle |
| **GroupId** | Type-safe opaque group identifiers (zero runtime cost) |
| **Transport Multiplexing** | FS2 Topic-based multiplexing/demultiplexing — all groups share one connection per node pair |
| **GroupEnvelope** | Wire-level message wrapper tagging each RPC with its target group for routing |
| **Per-Group Isolation** | Each group has independent log, stable store, state machine, and timers |

---

## Quick Start

### Prerequisites

- Scala 3.3.7+
- sbt 1.x

### Add to Your Project

```scala
// build.sbt
libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "3.6.3",
  "co.fs2"        %% "fs2-core"    % "3.12.2"
)
```

The core protocol logic (`RaftLogic`, `NodeState`, `RaftMessage`, `Effect`) has **zero dependencies**. Cats Effect and FS2 are only needed for the SPI implementations and `RaftNode` runtime.

### Your First Election (15 Lines)

```scala
import raft.state.*, raft.state.NodeState.*, raft.message.RaftMessage.*, raft.logic.RaftLogic

val config = RaftConfig(localId = NodeId("node-1"), preVoteEnabled = false)
var state  = Follower(term = 0, votedFor = None, leaderId = None): NodeState

// 1. Election timeout fires → become Candidate
val election = RaftLogic.onMessage(state, ElectionTimeout, config, 0, 0, 3)
state = election.state  // Candidate(term=1, votesReceived={node-1})

// 2. Receive a vote from node-2 → become Leader (majority: 2/3)
val voteReq = election.effects.collectFirst { case Broadcast(r: RequestVoteRequest) => r }.get
// ... feed voteReq to node-2, get a RequestVoteResponse back ...

val afterVote = RaftLogic.onVoteResponse(state.asInstanceOf[Candidate], NodeId("node-2"), voteResponse, config, 3)
state = afterVote.state  // Leader(term=1)
```

No network. No threads. No mocks. Pure functions, deterministic results.

---

## The Effect System

Every state transition produces a list of effects — data objects describing what side effects the runtime should perform. Your effect interpreter pattern-matches on these and executes them:

```scala
transition.effects.traverse_ {
  case SendMessage(to, msg)      => transport.send(to, msg)
  case Broadcast(msg)            => transport.broadcast(msg)
  case PersistHardState(term, v) => stableStore.setCurrentTerm(term) *> stableStore.setVotedFor(v)
  case AppendLogs(entries)       => logStore.append(entries)
  case TruncateLog(fromIdx)      => logStore.truncateFrom(fromIdx)
  case CommitEntries(upTo)       => applyToStateMachine(upTo)
  case ResetElectionTimer        => timerService.resetElectionTimer
  case ResetHeartbeatTimer       => timerService.resetHeartbeatTimer
  case BecomeLeader              => initializeLeaderState()
  case ConfirmLeadership(ci)     => handleReadIndexConfirmation(ci)
  case LeaseReadReady(ci)        => serveLeaseRead(ci)
  case TransferLeadership(t, n)  => initiateTransfer(t, n)
  case ParallelReplicate(p, m)   => peers.parTraverse_(p => transport.send(p, m))
  case _                         => IO.unit
}
```

---

## Examples

The library ships with 12 runnable examples covering protocol fundamentals, cluster simulation, and complete applications:

```bash
# Protocol mechanics
sbt "runMain examples.protocol.PreVoteExample"
sbt "runMain examples.protocol.LogMatchingExample"
sbt "runMain examples.protocol.CommitTrackingExample"
sbt "runMain examples.protocol.TimerServiceExample"

# Full cluster simulation
sbt "runMain examples.cluster.ThreeNodeClusterExample"

# Application case studies
sbt "runMain examples.kvstore.KVStoreExample"
sbt "runMain examples.lock.DistributedLockExample"
sbt "runMain examples.counter.CounterWithCodecExample"
sbt "runMain examples.distributed.DistributedCounterExample"
sbt "runMain examples.distributed.RaftTransactionExample"
sbt "runMain examples.distributed.DistributedTransactionExample"

# Multi-Raft groups
sbt "runMain examples.distributed.MultiGroupExample"
```

---

## Project Structure

```
src/main/scala/raft/
├── logic/
│   └── RaftLogic.scala          # Pure state machine — the heart of the library
├── state/
│   ├── NodeState.scala          # Follower | PreCandidate | Candidate | Leader
│   ├── Log.scala                # Log entries (Command, NoOp, Configuration)
│   ├── RaftConfig.scala         # Configuration (timeouts, batching, pipelining)
│   └── ClusterConfig.scala      # Membership (voters, learners, witnesses, joint consensus)
├── message/
│   ├── RaftMessage.scala        # All Raft RPCs and internal events
│   └── MessageCodec.scala       # Wire format SPI
├── effect/
│   ├── Effect.scala             # Side-effect descriptors (the Effect ADT)
│   └── Transition.scala         # State + Effects bundle
├── spi/
│   ├── Transport.scala          # Network abstraction
│   ├── LogStore.scala           # Persistent log storage
│   ├── StableStore.scala        # Term + votedFor persistence
│   ├── StateMachine.scala       # Application state machine (your code)
│   └── TimerService.scala       # Election + heartbeat timers
├── impl/
│   ├── InMemLogStore.scala      # In-memory log (for testing)
│   ├── InMemStableStore.scala   # In-memory stable store (for testing)
│   ├── InMemTransport.scala     # In-memory network (for testing)
│   └── DefaultTimerService.scala # FS2-based timer implementation
├── codec/
│   └── JsonCodec.scala          # JSON message encoding
├── metrics/
│   └── RaftMetrics.scala        # Observable metrics
├── multigroup/
│   ├── GroupEnvelope.scala      # Wire-level group routing wrapper
│   ├── GroupConfig.scala        # Per-group dependency bundle
│   ├── MultiRaftNode.scala      # Multi-group coordinator
│   ├── MultiGroupTransport.scala # FS2 Topic-based multiplexing
│   └── GroupAwareCodec.scala    # Envelope-aware serialization
├── storage/
│   └── mmap/                    # Memory-mapped WAL engine (segments, index, hard state)
├── compaction/
│   └── SnapshotTransfer.scala   # Pipelined, ack-based snapshot streaming
└── RaftNode.scala               # Cats Effect runtime combining logic + SPIs
```

```
src/test/scala/raft/              # 527 tests across 50+ suites
├── logic/                        # Unit tests for every protocol path
├── state/                        # State type tests (ClusterConfig, etc.)
├── integration/                  # Multi-node integration tests
├── chaos/                        # Adversarial network/timing scenarios
├── property/                     # Property-based safety invariants
├── benchmark/                    # Election, recovery, and multi-raft benchmarks
├── storage/                      # WAL engine and storage adapter tests
└── multigroup/                   # Multi-raft group tests
```

---

## Testing

The pure design makes the test suite fast and deterministic — **527 tests complete in ~2 seconds**:

```bash
sbt test                              # all 527 tests
sbt "testOnly *LogicSpec"             # core protocol unit tests
sbt "testOnly *IntegrationSpec"       # multi-node cluster tests
sbt "testOnly *ChaosScenarioSpec"     # adversarial network scenarios
sbt "testOnly *SafetyPropertySpec"    # property-based safety invariants
sbt "testOnly *ClusterConfigSpec"     # membership change safety
sbt "testOnly *MultiRaftNodeSpec"     # multi-raft group tests
```

---

## Documentation

The library includes a comprehensive **21-chapter book** covering both Raft theory and practical implementation:

📖 **[Raft: Theory and Practice](docs/book/README.md)**

| Part | Chapters | What You'll Learn |
|------|----------|-------------------|
| **I — Foundations** | 1–4 | Consensus theory, Raft protocol, advanced extensions, safety proofs |
| **II — Architecture** | 5–7 | Pure functional design, Core API, SPI layer |
| **III — Building** | 8–11 | Setup, replication, performance tuning, failure modes |
| **IV — Case Studies** | 12–15 | KV store, lock service, counter, transactions |
| **V — Ecosystem** | 16 | Comparison with etcd/raft, tikv/raft-rs, OpenRaft, SOFAJRaft, MicroRaft |
| **VI — Operations** | 17–20 | Troubleshooting, integration, property testing, Jepsen verification |
| **VII — Scaling** | 21 | Multi-Raft group design, transport multiplexing, partitioning strategies |

---

## Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Scala 3 | 3.3.7 | Language |
| Cats Effect | 3.6.3 | Concurrent effect system |
| FS2 | 3.12.2 | Streaming (timer events, message channels) |
| Log4Cats + Logback | 2.7.1 / 1.5.27 | Structured logging |
| ScalaTest + ScalaCheck | 3.2.19 | Testing (unit + property-based) |

---

## License

Apache 2.0
