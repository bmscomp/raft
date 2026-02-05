# Functional RAFT Library (Scala 3)

A functional implementation of the RAFT consensus algorithm in Scala 3.

**Inspired by:** HashiCorp Raft • MicroRaft • tikv/raft-rs • openraft • SOFAJRaft

---

## Design Principles

| Principle | Implementation |
|-----------|----------------|
| **Immutability** | Case classes, immutable collections |
| **Pure Functions** | State transitions return `(State, Effects)` |
| **Event-Driven** | No tick polling, reacts to RAFT events |
| **Extensible** | User-defined message codec, pluggable SPI |

---

## Architecture

```
┌────────────────────────────────────────────────────┐
│               RaftNode[F[_], M]                    │
│        Event-driven, user message codec            │
├────────────────────────────────────────────────────┤
│        Pure Logic (no I/O, fully testable)         │
│   Pre-voting • Leader stickiness • Batching        │
├────────────────────────────────────────────────────┤
│   Effects: SendMsg | Persist | Apply | Timer       │
├────────────────────────────────────────────────────┤
│   SPI: Transport[F,M] • LogStore[F] • FSM[F]       │
└────────────────────────────────────────────────────┘
```

---

## Core Features

| Feature | Description | Source |
|---------|-------------|--------|
| **Pre-Vote Protocol** | Prevents partitioned nodes from disrupting clusters | tikv, MicroRaft |
| **Leader Stickiness** | Reject votes if leader is alive | MicroRaft |
| **Log Matching** | Ensures log consistency across nodes | RAFT paper |
| **Commit Tracking** | Median-based commit index advancement | RAFT paper |
| **Randomized Timers** | Jittered election timeouts | Best practice |

### Pre-Vote Protocol

Two-phase election that prevents term inflation:

```
Standard:  Follower → Candidate(term+1) → may disrupt cluster
Pre-Vote:  Follower → PreCandidate(same term) → if majority → Candidate
```

---

## Advanced Features

| Feature | Messages | Status |
|---------|----------|--------|
| **Log Compaction** | `InstallSnapshotRequest/Response` | ✓ Messages defined |
| **Leadership Transfer** | `TransferLeadershipRequest` | ✓ Messages defined |
| **Linearizable Reads** | `ReadIndexRequest/Response` | ✓ Messages defined |
| **Membership Changes** | `AddServerRequest/Response`, `RemoveServerRequest/Response` | ✓ Messages defined |

---

## Effects System

Pure effects produced by state transitions:

| Effect | Description |
|--------|-------------|
| `SendMessage` | Send to specific peer |
| `Broadcast` | Send to all peers |
| `PersistHardState` | Persist term + votedFor |
| `AppendLogs` | Append entries to log |
| `TruncateLog` | Remove conflicting entries |
| `CommitEntries` | Apply committed entries |
| `TakeSnapshot` | Trigger log compaction |
| `BecomeLeader` | Initialize leader state |
| `TransferLeadership` | Graceful handoff |
| `ResetElectionTimer` | Randomized timeout |
| `ResetHeartbeatTimer` | Heartbeat scheduling |

---

## Examples

| Example | Description |
|---------|-------------|
| `ThreeNodeClusterExample` | Full 3-node cluster simulation |
| `PreVoteExample` | Pre-vote protocol demonstration |
| `LogMatchingExample` | Log consistency verification |
| `CommitTrackingExample` | Commit index advancement |
| `KVStoreExample` | Distributed key-value store |
| `DistributedLockExample` | Consensus-based locking |
| `CounterWithCodecExample` | Custom message codec |
| `DistributedCounterExample` | Distributed counter with consensus |
| `RaftTransactionExample` | Multi-key transactions |
| `DistributedTransactionExample` | Atomic transaction processing |
| `TimerServiceExample` | Timer SPI usage |

```bash
sbt "runMain examples.protocol.PreVoteExample"
sbt "runMain examples.cluster.ThreeNodeClusterExample"
sbt "runMain examples.kvstore.KVStoreExample"
```

---

## Project Structure

```
src/main/scala/raft/
├── state/
│   ├── NodeState.scala     # Follower|PreCandidate|Candidate|Leader
│   ├── Log.scala           # Log entries
│   └── RaftConfig.scala    # Configuration
├── message/
│   ├── RaftMessage.scala   # All RAFT RPCs
│   └── MessageCodec.scala  # Wire format SPI
├── effect/
│   ├── Effect.scala        # Side-effect descriptors
│   └── Transition.scala    # State + Effects
├── logic/
│   └── RaftLogic.scala     # Pure state machine
├── spi/
│   ├── Transport.scala     # Network SPI
│   ├── LogStore.scala      # Persistence SPI
│   ├── TimerService.scala  # Timer SPI
│   └── StateMachine.scala  # FSM SPI
└── impl/
    ├── InMemLogStore.scala
    ├── InMemStableStore.scala
    ├── InMemTransport.scala
    └── DefaultTimerService.scala
```

---

## Quick Start

```scala
val config = RaftConfig(
  localId = NodeId("node-1"),
  preVoteEnabled = true,           // Pre-vote (default)
  leaderStickinessEnabled = true   // Leader stickiness (default)
)

// Use pure logic directly
val transition = RaftLogic.onMessage(state, msg, config, lastLogIndex, lastLogTerm, clusterSize)
// transition.state = new state
// transition.effects = effects to execute
```

---

## Testing

```bash
sbt compile                         # Build
sbt test                            # All 153 tests
sbt "testOnly *LogicSpec"           # Unit tests
sbt "testOnly *IntegrationSpec"     # Cluster tests
```

---

## License

Apache 2.0

