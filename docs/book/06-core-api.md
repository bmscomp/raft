# Chapter 6: Core API

*This chapter is a complete tour of the library's public API — the types, functions, and data structures you'll work with when building distributed systems. Think of it as the reference companion to the conceptual material in Chapters 2–4. Each section connects back to the protocol concepts we've covered, so you'll see how theory maps to code.*

---

## Entry Point: `RaftLogic.onMessage`

This is the single function that drives the entire Raft protocol. Every event — a message from a peer, a timer firing, a client request — enters the system through this function:

```scala
object RaftLogic:
  def onMessage(
    state: NodeState,          // current node state (Follower, Candidate, Leader, etc.)
    msg: RaftMessage,          // the incoming event
    config: RaftConfig,        // static configuration for this node
    lastLogIndex: Long,        // index of the last entry in the log
    lastLogTerm: Long,         // term of the last entry in the log
    clusterSize: Int,          // total number of nodes in the cluster
    getTermAt: Long => Option[Long] = _ => None  // function to look up a log entry's term
  ): Transition
```

And it returns a `Transition`:

```scala
case class Transition(state: NodeState, effects: List[Effect])
```

That's it. The entire Raft protocol is driven by calling this function in a loop. Your runtime looks like:

```
             ┌──────────┐
             │  Event   │  (message from peer, timer, client request)
             └────┬─────┘
                  │
                  ▼
    ┌───────────────────────────┐
    │  RaftLogic.onMessage(     │
    │    currentState,          │
    │    event,                 │
    │    config,                │
    │    lastLogIndex,          │
    │    lastLogTerm,           │
    │    clusterSize            │
    │  )                        │
    └────────────┬──────────────┘
                 │
                 ▼
         ┌──────────────┐
         │  Transition  │
         │              │
         │  .state  ─────────→  update currentState
         │  .effects ────────→  execute each effect
         └──────────────┘
```

There's something worth noting about the `getTermAt` parameter: it's a function, not a value. The protocol logic sometimes needs to look up the term of a specific log entry (for example, to fill in the `prevLogTerm` field of an `AppendEntries` request). Rather than passing the entire log, we pass a lookup function. This keeps the interface minimal — the protocol logic only queries the log when it needs to, and only for the specific information it requires.

> **Note — Why pass log metadata separately?** You might wonder why `lastLogIndex` and `lastLogTerm` are passed as parameters rather than being derived from the log itself. The reason is the pure function architecture: the protocol logic doesn't have access to the log store (that's an SPI concern). Instead, the runtime reads these values from the log store and passes them in. This maintains the separation between pure logic and effectful I/O.

## Node States

Every node in a Raft cluster is in exactly one of four states. These are modeled as a sealed trait hierarchy:

```
                     ElectionTimeout
         ┌──────────────────────────────┐
         │                              ▼
    ┌──────────┐  preVote        ┌──────────────┐
    │ Follower │────────────────→│ PreCandidate │
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

Let's look at each state in detail.

### Follower

```scala
case class Follower(
  term: Long,
  votedFor: Option[NodeId],
  leaderId: Option[NodeId]
)
```

The **default** and most common state. A follower passively responds to RPCs:
- **`AppendEntries`** from the leader: append entries to the log, update commit index, reset election timer
- **`RequestVote`** from candidates: grant or deny vote based on the rules from Chapter 2
- **`ElectionTimeout`**: transition to PreCandidate (if pre-vote is enabled) or Candidate

The `votedFor` field records who this node voted for in the current term. It's `None` if the node hasn't voted. This field is part of the **hard state** — it must be persisted to disk before responding to any RPC, because a crash-and-restart must not allow the node to vote again in the same term.

The `leaderId` field tracks the current leader. It's `None` when the follower doesn't know who the leader is (e.g., right after a term change). Client libraries use this field to redirect requests to the leader.

### PreCandidate

```scala
case class PreCandidate(
  term: Long,
  preVotesReceived: Set[NodeId]
)
```

Only exists when `preVoteEnabled = true` in the config (which is the default). This is the intermediate state from the Pre-Vote protocol (Chapter 3).

The pre-candidate sends `RequestVote` RPCs with `isPreVote = true` at the **next term** (current + 1), but crucially **does not increment its own term**. The `preVotesReceived` set tracks which peers have granted their pre-vote. If the set reaches a majority, the node transitions to Candidate (and only then increments its term).

### Candidate

```scala
case class Candidate(
  term: Long,
  votesReceived: Set[NodeId]
)
```

A candidate has incremented its term, voted for itself, and is actively seeking votes. The `votesReceived` set always starts with the candidate's own `NodeId` (self-vote). As vote responses arrive, the set grows. When it reaches a majority of the cluster size, the candidate becomes Leader.

Three things can end a candidacy:
1. **Win**: `votesReceived.size > clusterSize / 2` → become Leader
2. **Lose**: receive an `AppendEntries` from a leader with `term >= candidate.term` → become Follower
3. **Timeout**: election timer fires without achieving a majority → start a new election (increment term, re-vote)

### Leader

```scala
case class Leader(
  term: Long,
  matchIndex: Map[NodeId, Long] = Map.empty,
  commitIndex: Long = 0,
  // ... additional fields for advanced features
)
```

The active leader. This is the only state that accepts client commands and drives log replication. Key fields:

- **`matchIndex`**: for each follower, the highest log index known to be replicated on that follower. The leader uses this to calculate the commit index (the median of all match indices, including its own).
- **`commitIndex`**: the highest log index that has been replicated to a majority and is safe to apply to the state machine.

When a leader first takes office, it doesn't know any follower's state. `matchIndex` starts empty, and the leader discovers each follower's position through the `AppendEntries` consistency check (the "search backward" mechanism from Chapter 2).

## Messages

All protocol messages — both inter-node RPCs and internal events — are modeled as a single sealed hierarchy:

```scala
enum RaftMessage:
  // Inter-node RPCs
  case RequestVoteRequest(
    term: Long,
    candidateId: NodeId,
    lastLogIndex: Long,
    lastLogTerm: Long,
    isPreVote: Boolean = false
  )

  case RequestVoteResponse(
    term: Long,
    voteGranted: Boolean,
    isPreVote: Boolean = false
  )

  case AppendEntriesRequest(
    term: Long,
    leaderId: NodeId,
    prevLogIndex: Long,
    prevLogTerm: Long,
    entries: Seq[Log],
    leaderCommit: Long
  )

  case AppendEntriesResponse(
    term: Long,
    success: Boolean,
    matchIndex: Long
  )

  case InstallSnapshotRequest(
    term: Long,
    leaderId: NodeId,
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    data: Array[Byte],
    done: Boolean
  )

  case InstallSnapshotResponse(term: Long)

  // Internal events (not sent over the network)
  case ElectionTimeout        // the election timer fired
  case HeartbeatTimeout       // the heartbeat timer fired
  case TimeoutNow(target: NodeId)  // leadership transfer trigger
```

Internal events like `ElectionTimeout` and `HeartbeatTimeout` are modeled as messages for a specific reason: it means the timer system doesn't need special access to the protocol logic. The timer fires, the runtime wraps it as a `HeartbeatTimeout` message, and it goes through the same `onMessage` path as any other event. This uniformity simplifies both the runtime and testing.

> **Note — The `isPreVote` flag:** Notice that `RequestVoteRequest` and `RequestVoteResponse` carry an `isPreVote` boolean rather than being separate message types. This is a pragmatic choice — it means the serialization format and transport code don't need to change when Pre-Vote is enabled or disabled. The protocol logic checks this flag internally to decide whether to actually persist state changes.

## The Effect Catalog

Every effect the protocol logic can produce is documented here. Understanding this catalog is essential for writing a correct effect interpreter.

| Effect | When It's Produced | What the Runtime Should Do |
|--------|-------------------|---------------------------|
| `SendMessage(to, msg)` | Responding to a specific peer (vote reply, append reply) | Deliver `msg` to node `to` via transport |
| `Broadcast(msg)` | Starting an election, sending heartbeats | Deliver `msg` to **all** peers |
| `PersistHardState(term, votedFor)` | Term changes, votes cast | fsync `term` and `votedFor` to durable storage |
| `AppendLogs(entries)` | Follower accepts entries from leader | Append entries to log store (atomic, durable) |
| `TruncateLog(fromIndex)` | Log conflict detected | Remove entries at and after `fromIndex` |
| `CommitEntries(upToIndex)` | Commit index advances | Apply entries through `upToIndex` to the state machine |
| `BecomeLeader` | Election won | Initialize leader state (send heartbeats, etc.) |
| `ResetElectionTimer` | Heartbeat received, or vote granted | Restart the election countdown |
| `ResetHeartbeatTimer` | Became leader, or heartbeat interval elapsed | Schedule the next heartbeat |
| `ConfirmLeadership(commitIndex)` | ReadIndex requested | Start a heartbeat round to confirm leadership |
| `ReadIndexReady(commitIndex)` | Leadership confirmed for a read | Serve the linearizable read after applying through `commitIndex` |
| `ReadIndexRejected(hint)` | Non-leader asked to serve a read | Return error to client (with optional leader hint) |
| `LeaseReadReady(commitIndex)` | Lease-based read requested and lease is valid | Serve the read immediately |
| `ExtendLease(duration)` | Heartbeat acknowledged by majority | Extend the leadership lease |
| `TimeoutNow(target)` | Leadership transfer initiated | Send `TimeoutNow` to the target node |
| `TransferLeadership(target)` | Admin requests leadership transfer | Begin the transfer process (catch up target, then TimeoutNow) |
| `ParallelReplicate(peers, msg)` | Parallel replication enabled | Send `AppendEntries` to multiple peers concurrently |
| `BatchAppend(batchId, entries)` | Batching enabled | Batch multiple entries into a single log append |
| `BatchComplete(batchId, results)` | Batch of entries committed | Notify clients of batch results |

> **Note — Effect ordering matters.** The runtime should execute `PersistHardState` **before** `SendMessage` or `Broadcast`. The reason: if the node crashes after sending a message but before persisting the term/vote, it might restart and repeat an action that violates safety (like voting twice in the same term). Persist first, communicate second. This is a standard pattern in distributed systems, sometimes called "write-ahead".

## Configuration: `RaftConfig`

Every node is configured via a `RaftConfig` instance. This is an immutable case class — create it once at startup and pass it to every `onMessage` call:

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

Here's guidance on tuning the key parameters:

| Parameter | Default | Tuning Guidance |
|-----------|---------|-----------------|
| `electionTimeoutMin/Max` | 150–300ms | Should be at least **10× the network round-trip time**. In a LAN (~1ms RTT), the defaults work well. In a WAN (~50ms RTT), increase to 500–1000ms. |
| `heartbeatInterval` | 50ms | Must be **much less than** `electionTimeoutMin`. A good rule of thumb is `electionTimeoutMin / 3`. |
| `maxEntriesPerRequest` | 100 | Controls the maximum number of log entries in a single `AppendEntries` RPC. Higher values improve throughput for lagging followers but increase memory usage per RPC. |
| `preVoteEnabled` | true | **Always enable in production.** Disable only for testing the raw election logic. |
| `leaderStickinessEnabled` | true | Reduces spurious elections. Safe to leave on in all environments. |
| `leaderLeaseDuration` | 100ms | Must be **less than** `electionTimeoutMin` and should account for clock skew between nodes. Conservative: `electionTimeoutMin × 0.5`. |

> **Note — Timing constraints:** The Raft paper formally requires: `broadcastTime ≪ electionTimeout ≪ MTBF`. The `broadcastTime` is the average time to send an RPC to all nodes (~0.5–20ms on a LAN). The MTBF (mean time between failures) is typically months or years. Your election timeout must sit comfortably between these two extremes. If the election timeout is too close to the broadcast time, followers will start elections before heartbeats arrive. If it's too close to the MTBF, the cluster might not recover from failures quickly enough.

## Cluster Configuration: `ClusterConfig`

The `ClusterConfig` type manages the cluster membership and supports the joint consensus protocol for safe membership changes:

```scala
case class ClusterConfig(
  members: Map[NodeId, NodeRole],
  jointConfig: Option[Map[NodeId, NodeRole]] = None,
  pendingConfig: Option[Map[NodeId, NodeRole]] = None
)

enum NodeRole:
  case Voter, Learner, Witness
```

During normal operation, `jointConfig` and `pendingConfig` are `None`, and `members` contains the active membership. During a joint consensus transition (Chapter 3), `jointConfig` holds the old configuration and `pendingConfig` holds the target new configuration.

Here are the key operations:

```scala
// Create a cluster from a set of voter node IDs
val config = ClusterConfig(Set(n1, n2, n3))

// Add a learner (non-voting member for catch-up)
val withLearner = config.addMember(n4, NodeRole.Learner)

// Promote a learner to voter
val promoted = withLearner.promoteLearner(n4)

// Begin joint consensus for a membership change
val newMembers = Map(
  n1 -> NodeRole.Voter,
  n2 -> NodeRole.Voter,
  n4 -> NodeRole.Voter
)
val joint     = config.beginJointConsensus(newMembers)
val completed = joint.completeJointConsensus   // switch to new config
val aborted   = joint.abortJointConsensus      // rollback to old config

// Query the configuration
config.isVoter(n1)              // true
config.isLearner(n4)            // true (before promotion)
config.contains(n5)             // false
config.hasQuorum(Set(n1, n2))   // true (2/3 majority)
config.isInJointConsensus       // false (unless mid-transition)
config.voters                   // Set(n1, n2, n3)
config.allNodes                 // Set(n1, n2, n3)
```

> **Note — Quorum During Joint Consensus:** When `isInJointConsensus` is `true`, the `hasQuorum` method requires a majority of **both** the old configuration (stored in `jointConfig`) and the current membership. This dual-quorum check is what makes joint consensus safe (Chapter 3).

## Log Entries

Log entries are the fundamental unit of replicated data:

```scala
case class Log(
  index: Long,
  term: Long,
  data: Array[Byte],
  entryType: EntryType
)

enum EntryType:
  case Command        // a client-submitted command
  case NoOp           // leader's initial no-op entry (see Chapter 2)
  case Configuration  // a membership change entry
```

Factory methods make entry creation clear and intentional:

```scala
val clientCmd = Log.command(index = 1, term = 1, data = "SET x=1".getBytes)
val leaderInit = Log.noOp(index = 1, term = 1)
```

The `data` field is an opaque byte array — the protocol logic never inspects its contents. Your application defines the serialization format (JSON, Protobuf, Avro, or anything else). The protocol's job is to ensure that every node receives and applies the same entries in the same order.

## Vote Response Handling

For tracking voter identity and accumulating votes, the library provides a dedicated function:

```scala
val afterVote = RaftLogic.onVoteResponse(
  state = candidate,                    // current Candidate state
  voter = NodeId("node-2"),             // who sent the response
  resp = RequestVoteResponse(
    term = 1,
    voteGranted = true,
    isPreVote = false
  ),
  config = config,                      // RaftConfig
  clusterSize = 3                       // total nodes
)
// If this was the 2nd vote in a 3-node cluster → majority reached
// afterVote.state → Leader(term = 1)
// afterVote.effects → [BecomeLeader, ...]
```

This function is separate from `onMessage` because vote responses require the voter's identity (to prevent double-counting votes from the same node). Regular `onMessage` doesn't carry sender identity.

## Summary

Here's the complete API surface at a glance:

```
                    ┌──────────────────────────┐
                    │       RaftLogic          │
                    │                          │
  RaftMessage ─────→│  onMessage(state, msg)   │─────→ Transition
  Timer events      │  onVoteResponse(...)     │       ├── NodeState
                    │  handleReadIndex(...)    │       └── List[Effect]
                    │  handleLeaseRead(...)    │
                    │  handleTransferLeader..  │
                    └──────────────────────────┘

  NodeState: Follower | PreCandidate | Candidate | Leader
  Effect:    SendMessage | Broadcast | PersistHardState | AppendLogs
             TruncateLog | CommitEntries | BecomeLeader | ResetElectionTimer
             ResetHeartbeatTimer | ConfirmLeadership | ReadIndexReady
             LeaseReadReady | ExtendLease | TimeoutNow | ...
  Config:    RaftConfig (tuning), ClusterConfig (membership)
  Data:      Log (entries), Transition (state + effects)
```

---

*Next: [Chapter 7 — The SPI Layer](07-spi-layer.md) covers the four interfaces you implement to connect the pure protocol logic to your infrastructure: Transport, LogStore, StableStore, and StateMachine.*
