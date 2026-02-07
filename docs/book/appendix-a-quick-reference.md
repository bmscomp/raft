# Appendix A: Quick Reference

*A one-page lookup for every type, effect, message, and configuration parameter in the library.*

---

## Node States

```scala
enum NodeState:
  case Follower(term: Long, votedFor: Option[NodeId], leaderId: Option[NodeId])
  case PreCandidate(term: Long, votesReceived: Set[NodeId])
  case Candidate(term: Long, votesReceived: Set[NodeId])
  case Leader(term: Long, matchIndex: Map[NodeId, Long])
```

| State | When | Transitions To |
|-------|------|---------------|
| **Follower** | Startup, or on receiving higher term | PreCandidate (if preVote), Candidate (if not) |
| **PreCandidate** | Election timeout with preVote enabled | Candidate (if pre-vote majority), Follower (if rejected) |
| **Candidate** | Election timeout or pre-vote success | Leader (if majority), Follower (if higher term seen) |
| **Leader** | Wins election (majority votes) | Follower (if higher term seen) |

---

## Messages (RaftMessage)

### Internal Events

| Message | Trigger |
|---------|---------|
| `ElectionTimeout` | Election timer fires |
| `HeartbeatTimeout` | Heartbeat timer fires |

### RPCs

| Request | Response | Purpose |
|---------|----------|---------|
| `RequestVoteRequest(term, candidateId, lastLogIndex, lastLogTerm, isPreVote)` | `RequestVoteResponse(term, voteGranted, isPreVote)` | Leader election |
| `AppendEntriesRequest(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit)` | `AppendEntriesResponse(term, success, matchIndex, lastLogIndex)` | Log replication + heartbeats |
| `InstallSnapshotRequest(term, leaderId, lastIncludedIndex, lastIncludedTerm, data)` | `InstallSnapshotResponse(term)` | Snapshot transfer |

---

## Effects

### Messaging

| Effect | Arguments | When Produced |
|--------|-----------|---------------|
| `SendMessage(to, message)` | Target node, RPC message | Targeted communication |
| `Broadcast(message)` | RPC message | Elections, heartbeats |
| `ParallelReplicate(targets, message)` | Follower set, AppendEntries | Parallel replication enabled |
| `PipelinedSend(to, message, sequenceNum)` | Target, message, seq# | Pipelining enabled |

### Persistence

| Effect | Arguments | When Produced |
|--------|-----------|---------------|
| `PersistHardState(term, votedFor)` | Term, optional vote | Every term/vote change |
| `AppendLogs(entries)` | Log entries | Leader or follower append |
| `TruncateLog(fromIndex)` | Start index | Follower conflict resolution |

### State Machine

| Effect | Arguments | When Produced |
|--------|-----------|---------------|
| `ApplyToStateMachine(entry)` | Log entry | Entry committed |
| `CommitEntries(upToIndex)` | Commit index | Majority replication achieved |
| `TakeSnapshot(lastIncludedIndex, lastIncludedTerm)` | Index, term | Log compaction trigger |

### Timers

| Effect | When Produced |
|--------|---------------|
| `ResetElectionTimer` | Follower receives valid heartbeat or grants vote |
| `ResetHeartbeatTimer` | Leader sends heartbeats |

### Leadership

| Effect | Arguments | When Produced |
|--------|-----------|---------------|
| `BecomeLeader` | — | Candidate wins election |
| `InitializeLeaderState(followers, lastLogIndex)` | Follower set, last index | New leader initialization |
| `UpdateFollowerIndex(followerId, matchIndex, nextIndex)` | Follower ID, indices | AppendEntries response |
| `TransferLeadership(target)` | Target node | Leadership transfer initiated |
| `TimeoutNow(target)` | Target node | Target's log is caught up |

### Linearizable Reads

| Effect | Arguments | When Produced |
|--------|-----------|---------------|
| `ConfirmLeadership(requestId, pendingReadIndex)` | Request ID, index | ReadIndex request received |
| `ReadIndexReady(requestId, readIndex)` | Request ID, index | Leadership confirmed |
| `ReadIndexRejected(requestId, leaderHint)` | Request ID, hint | Not leader or quorum lost |
| `ExtendLease(until)` | Epoch millis | Heartbeat quorum confirmed |
| `LeaseReadReady(requestId)` | Request ID | Lease active, serve read |

### Batching

| Effect | Arguments | When Produced |
|--------|-----------|---------------|
| `BatchAppend(entries, batchId)` | Entries, batch ID | Batching buffer flush |
| `BatchComplete(batchId, commitIndex)` | Batch ID, commit index | Batch committed |
| `TrackInflight(followerId, sequenceNum, lastIndex)` | Follower, seq#, index | Pipelined request sent |

---

## Configuration (RaftConfig)

```scala
RaftConfig(
  localId: NodeId,                           // Required: unique node identifier
  electionTimeoutMin: FiniteDuration,        // Default: 150ms
  electionTimeoutMax: FiniteDuration,        // Default: 300ms
  heartbeatInterval: FiniteDuration,         // Default: 50ms
  maxEntriesPerRequest: Int,                 // Default: 100
  preVoteEnabled: Boolean,                   // Default: true
  leaderStickinessEnabled: Boolean,          // Default: true
  leaderLeaseDuration: FiniteDuration,       // Default: 100ms
  parallelReplicationEnabled: Boolean,       // Default: true
  batching: BatchConfig,                     // Default: disabled
  pipelining: PipelineConfig                 // Default: disabled
)
```

### BatchConfig

| Parameter | Default | Description |
|-----------|---------|-------------|
| `enabled` | `false` | Enable command batching |
| `maxSize` | `100` | Max entries per batch |
| `maxWait` | `10ms` | Max time to wait before flushing |

### PipelineConfig

| Parameter | Default | Description |
|-----------|---------|-------------|
| `enabled` | `false` | Enable AppendEntries pipelining |
| `maxInflight` | `10` | Max unacknowledged requests per follower |

---

## Log Entry Types

```scala
enum Log:
  case Command(index: Long, term: Long, data: Array[Byte])
  case NoOp(index: Long, term: Long)
  case Configuration(index: Long, term: Long, config: ClusterConfig)
```

| Type | Created By | Applied to StateMachine? |
|------|-----------|------------------------|
| `Command` | Client writes | Yes |
| `NoOp` | New leader (one per term) | No |
| `Configuration` | Membership changes | No (handled internally) |

---

## ClusterConfig

```scala
case class ClusterConfig(
  members: Map[NodeId, NodeRole],
  jointConfig: Option[Map[NodeId, NodeRole]],
  pendingConfig: Option[Map[NodeId, NodeRole]]
)
```

| NodeRole | Participates in Elections? | Contributes to Quorum? | Receives Replication? |
|----------|--------------------------|----------------------|---------------------|
| `Voter` | Yes | Yes | Yes |
| `Learner` | No | No | Yes |
| `Witness` | Yes | Yes | No (lightweight) |

---

## SPI Interfaces

| Interface | Methods | Purpose |
|-----------|---------|---------|
| `Transport[F]` | `send`, `broadcast`, `receive` | Network communication |
| `LogStore[F]` | `append`, `getRange`, `truncateFrom`, `lastIndex`, `termAt` | Persistent log storage |
| `StableStore[F]` | `getCurrentTerm`, `setCurrentTerm`, `getVotedFor`, `setVotedFor` | Hard state persistence |
| `StateMachine[F, R]` | `apply`, `snapshot`, `restore` | Application state machine |
| `TimerService[F]` | `electionTimeouts`, `heartbeatTicks`, `resetElectionTimer`, `resetHeartbeatTimer` | Timer events |

---

## Key API Entry Points

| Function | Signature | Purpose |
|----------|-----------|---------|
| `RaftLogic.onMessage` | `(state, message, config, lastLogIndex, lastLogTerm, clusterSize) → Transition` | Process any Raft event |
| `RaftLogic.onVoteResponse` | `(candidate, from, response, config, clusterSize) → Transition` | Handle vote response |
| `RaftLogic.calculateCommitIndex` | `(leader, clusterSize, getTermAt) → Option[Long]` | Compute new commit index |
| `RaftNode.create` | `(config, transport, logStore, ...) → F[RaftNode[F, M]]` | Create a running node |
