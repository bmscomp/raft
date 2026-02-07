# Chapter 5: Core API

*A complete tour of the library's public API — the types, functions, and data structures you use to build distributed systems with Raft.*

---

## Entry Point: `RaftLogic.onMessage`

This is the single function that drives the entire protocol. Every incoming event — an RPC from another node, a timer expiry, a client request — goes through `onMessage`:

```scala
object RaftLogic:
  def onMessage(
    state: NodeState,          // current node state
    msg: RaftMessage,          // incoming event
    config: RaftConfig,        // node configuration
    lastLogIndex: Long,        // index of last log entry
    lastLogTerm: Long,         // term of last log entry
    clusterSize: Int,          // total nodes in cluster
    getTermAt: Long => Option[Long] = _ => None  // log term lookup
  ): Transition
```

It returns a `Transition`:

```scala
case class Transition(state: NodeState, effects: List[Effect])
```

Your runtime loop:

1. Receive an event (message, timeout)
2. Call `RaftLogic.onMessage(currentState, event, ...)`
3. Update `currentState ← transition.state`
4. Execute each effect in `transition.effects`
5. Repeat

## Node States

Nodes transition through a hierarchy of states:

```
                    ElectionTimeout
        ┌──────────────────────────────┐
        │                              ▼
   ┌──────────┐  preVote        ┌──────────────┐
   │ Follower │────────────────>│ PreCandidate │
   └──────────┘                 └──────────────┘
        ▲                              │ majority pre-votes
        │ higher term                  ▼
        │                      ┌──────────────┐
        │                      │  Candidate   │
        │                      └──────────────┘
        │ steps down                   │ majority votes
        │                              ▼
        │                      ┌──────────────┐
        └──────────────────────│    Leader    │
                               └──────────────┘
```

### Follower

```scala
case class Follower(
  term: Long,
  votedFor: Option[NodeId],
  leaderId: Option[NodeId]
)
```

The default state. Responds to `AppendEntries` from the leader and `RequestVote` from candidates. Transitions to PreCandidate/Candidate when the election timer fires.

### PreCandidate

```scala
case class PreCandidate(
  term: Long,
  preVotesReceived: Set[NodeId]
)
```

Only exists when `preVoteEnabled = true`. Sends pre-vote requests without incrementing the term. If a majority responds positively, transitions to Candidate.

### Candidate

```scala
case class Candidate(
  term: Long,
  votesReceived: Set[NodeId]
)
```

Has incremented its term and voted for itself. Sends `RequestVote` to all peers. Transitions to Leader on majority, to Follower on higher term.

### Leader

```scala
case class Leader(
  term: Long,
  matchIndex: Map[NodeId, Long] = Map.empty,
  commitIndex: Long = 0,
  ...
)
```

The active leader. Accepts client commands, replicates log entries, advances the commit index, and sends heartbeats.

## Messages

All protocol messages extend `RaftMessage`:

```scala
enum RaftMessage:
  case RequestVoteRequest(term: Long, candidateId: NodeId,
    lastLogIndex: Long, lastLogTerm: Long, isPreVote: Boolean = false)

  case RequestVoteResponse(term: Long, voteGranted: Boolean,
    isPreVote: Boolean = false)

  case AppendEntriesRequest(term: Long, leaderId: NodeId,
    prevLogIndex: Long, prevLogTerm: Long,
    entries: Seq[Log], leaderCommit: Long)

  case AppendEntriesResponse(term: Long, success: Boolean, matchIndex: Long)

  case InstallSnapshotRequest(term: Long, leaderId: NodeId,
    lastIncludedIndex: Long, lastIncludedTerm: Long,
    data: Array[Byte], done: Boolean)

  case InstallSnapshotResponse(term: Long)

  case ElectionTimeout    // internal: election timer fired
  case HeartbeatTimeout   // internal: heartbeat timer fired
  case TimeoutNow(target: NodeId)  // leadership transfer
```

## The Effect Catalog

Every effect produced by `RaftLogic`:

| Effect | Description | Typical Trigger |
|--------|-------------|-----------------|
| `SendMessage(to, msg)` | Send a message to a specific peer | Vote response, append response |
| `Broadcast(msg)` | Send to all peers | Election start, heartbeat |
| `PersistHardState(term, votedFor)` | Persist term + vote to durable store | Term change, vote cast |
| `AppendLogs(entries)` | Append entries to log store | Follower accepts entries |
| `TruncateLog(fromIndex)` | Remove entries from index onward | Log conflict resolution |
| `CommitEntries(upToIndex)` | Apply committed entries to FSM | Commit index advances |
| `BecomeLeader` | Initialize leader state | Election won |
| `ResetElectionTimer` | Restart election timeout | Heartbeat received |
| `ResetHeartbeatTimer` | Start/restart heartbeat timer | Became leader |
| `ConfirmLeadership(commitIndex)` | Confirm leadership for read | ReadIndex request |
| `ReadIndexReady(commitIndex)` | Ready to serve linearizable read | Leadership confirmed |
| `ReadIndexRejected(hint)` | Cannot serve read (not leader) | Non-leader read |
| `LeaseReadReady(commitIndex)` | Immediate read (lease valid) | Lease-based read |
| `ExtendLease(duration)` | Extend leader lease | Heartbeat ack majority |
| `TimeoutNow(target)` | Trigger immediate election | Leadership transfer |
| `TransferLeadership(target)` | Begin transfer process | Admin request |
| `ParallelReplicate(peers, msg)` | Replicate concurrently | Parallel append enabled |
| `BatchAppend(batchId, entries)` | Batched log append | Batching enabled |

## Configuration: `RaftConfig`

```scala
case class RaftConfig(
  localId: NodeId,
  electionTimeoutMin: FiniteDuration = 150.millis,
  electionTimeoutMax: FiniteDuration = 300.millis,
  heartbeatInterval: FiniteDuration  = 50.millis,
  maxEntriesPerRequest: Int          = 100,
  preVoteEnabled: Boolean            = true,
  leaderStickinessEnabled: Boolean   = true,
  leaderLeaseDuration: FiniteDuration = 100.millis,
  parallelReplicationEnabled: Boolean = true,
  batching: BatchConfig  = BatchConfig(),
  pipelining: PipelineConfig = PipelineConfig()
)
```

Key tuning parameters:

| Parameter | Default | Guidance |
|-----------|---------|----------|
| `electionTimeoutMin/Max` | 150–300ms | Network RTT × 10 |
| `heartbeatInterval` | 50ms | Must be ≪ election timeout |
| `maxEntriesPerRequest` | 100 | Throughput vs. memory |
| `preVoteEnabled` | true | Disable only for debugging |
| `leaderLeaseDuration` | 100ms | Clock skew bound |

## Cluster Configuration: `ClusterConfig`

Manages membership and supports joint consensus:

```scala
case class ClusterConfig(
  members: Map[NodeId, NodeRole],
  jointConfig: Option[Map[NodeId, NodeRole]] = None
)

enum NodeRole:
  case Voter, Learner, Witness
```

Key operations:

```scala
// Create a cluster
val config = ClusterConfig(Set(n1, n2, n3))

// Add members
val withLearner = config.addMember(n4, NodeRole.Learner)
val promoted    = withLearner.promoteLearner(n4)  // Learner → Voter

// Joint consensus
val newMembers = Map(n1 -> NodeRole.Voter, n2 -> NodeRole.Voter, n4 -> NodeRole.Voter)
val joint     = config.beginJointConsensus(newMembers)
val completed = joint.completeJointConsensus
val aborted   = joint.abortJointConsensus  // rollback if needed

// Queries
config.isVoter(n1)              // true
config.isLearner(n4)            // true (before promotion)
config.contains(n5)             // false
config.hasQuorum(Set(n1, n2))   // true (2/3 majority)
config.isInJointConsensus       // false (unless in transition)
```

## Log Entries

```scala
case class Log(
  index: Long,
  term: Long,
  data: Array[Byte],
  entryType: EntryType
)

enum EntryType:
  case Command, NoOp, Configuration
```

Factory methods:

```scala
val cmd  = Log.command(index = 1, term = 1, data = "SET x=1".getBytes)
val noop = Log.noOp(index = 1, term = 1)
```

## Vote Response Handling

For tracking explicit voter identity:

```scala
val afterVote = RaftLogic.onVoteResponse(
  state = candidate,
  voter = NodeId("node-2"),
  resp = RequestVoteResponse(term = 1, voteGranted = true),
  config = config,
  clusterSize = 3
)
```

## Summary

```
                   ┌─────────────────────────┐
                   │       RaftLogic          │
                   │                          │
  RaftMessage ────>│  onMessage(state, msg)   │────> Transition
  Timer events     │  onVoteResponse(...)     │      ├── NodeState
                   │  handleReadIndex(...)    │      └── List[Effect]
                   │  handleLeaseRead(...)    │
                   │  handleTransferLeader..  │
                   └─────────────────────────┘
```

---

*Next: [Chapter 6 — The SPI Layer](06-spi-layer.md) covers the four interfaces you implement to connect the protocol to your infrastructure.*
