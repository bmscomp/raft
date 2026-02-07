# Chapter 8: Log Replication in Practice

*Data enters the cluster through the leader and flows to followers via AppendEntries. This chapter shows how to create, replicate, and commit log entries using the library, and how to tune throughput with batching and pipelining.*

---

## The Replication Flow

```
  Client ──(command)──> Leader
                          │
                    ┌─────┼─────┐
                    ▼     ▼     ▼
                 Node-1 Node-2 Node-3
                 (self) (follow)(follow)
                          │     │
                          └──┬──┘
                             │ Majority replicated
                             ▼
                        COMMITTED
                             │
                    ┌────────┼────────┐
                    ▼        ▼        ▼
                   FSM      FSM      FSM
             (apply cmd)(apply cmd)(apply cmd)
```

## Creating Log Entries

```scala
import raft.state.Log

// Client commands — your application data
val entry1 = Log.command(index = 1, term = 1, data = "SET x=1".getBytes)
val entry2 = Log.command(index = 2, term = 1, data = "SET y=2".getBytes)

// No-op — leaders append one on election to establish commit authority
val noop = Log.noOp(index = 1, term = 1)
```

## Sending AppendEntries

The leader constructs an `AppendEntriesRequest` for each follower:

```scala
val appendReq = AppendEntriesRequest(
  term         = 1,
  leaderId     = n1,
  prevLogIndex = 0,    // index of entry before the new ones
  prevLogTerm  = 0,    // term at prevLogIndex
  entries      = Seq(entry1, entry2),
  leaderCommit = 0     // leader's current commit index
)
```

The follower processes it through `RaftLogic.onMessage`:

```scala
val followerTrans = RaftLogic.onMessage(
  followerState, appendReq, followerConfig,
  lastLogIndex = 0, lastLogTerm = 0, clusterSize = 3
)

val response = followerTrans.effects.collectFirst {
  case SendMessage(_, r: AppendEntriesResponse) => r
}.get

response.success    // true — entries accepted
response.matchIndex // 2   — highest replicated index
```

## Handling Log Conflicts

When a follower has entries that conflict with the leader (different term at the same index), the leader must back up and find the point of agreement.

```
Leader's log:    [1:t1] [2:t1] [3:t2] [4:t2]
Follower's log:  [1:t1] [2:t1] [3:t1] [4:t1]  ← entries 3,4 have wrong term

Leader sends AppendEntriesRequest(prevLogIndex=4, prevLogTerm=2)
Follower checks: termAt(4) = Some(1) ≠ 2  →  REJECT

Leader decrements: AppendEntriesRequest(prevLogIndex=2, prevLogTerm=1)
Follower checks: termAt(2) = Some(1) = 1  →  ACCEPT
Follower truncates entries 3+ and appends leader's entries
```

In code:

```scala
// Follower receives conflicting request
val conflictTrans = RaftLogic.onMessage(
  followerState, conflictReq, config,
  lastLogIndex = 4, lastLogTerm = 1, clusterSize = 3,
  getTermAt = {
    case 4 => Some(1L)  // follower has term 1 at index 4
    case 2 => Some(1L)  // matches at index 2
    case _ => None
  }
)

// Effects include TruncateLog + AppendLogs
conflictTrans.effects.collect {
  case TruncateLog(fromIndex)   => s"Truncate from $fromIndex"
  case AppendLogs(entries)      => s"Append ${entries.size} entries"
}
```

## Commit Index Advancement

The leader tracks each follower's `matchIndex`. An entry is committed when a majority has replicated it:

```scala
// Leader state after receiving AppendEntriesResponses
val leader = Leader(
  term = 1,
  matchIndex = Map(
    n2 -> 5L,   // follower n2 has replicated through index 5
    n3 -> 3L    // follower n3 has replicated through index 3
  ),
  commitIndex = 0
)

// Calculate new commit index
// majority of {self(5), n2(5), n3(3)} → median = 5, but only consider current term
val getTermAt: Long => Option[Long] = {
  case 3 => Some(1L)  // entry at index 3, term 1
  case _ => None
}

val newCommit = leader.calculateCommitIndex(clusterSize = 3, getTermAt)
// newCommit == 3 (all three nodes have entries through 3)
```

When the commit index advances, the library emits a `CommitEntries` effect:

```scala
// In your effect interpreter
case CommitEntries(upToIndex) =>
  for
    currentApplied <- appliedIndexRef.get
    entries        <- logStore.getRange(currentApplied + 1, upToIndex)
    _              <- entries.traverse(stateMachine.apply)
    _              <- appliedIndexRef.set(upToIndex)
  yield ()
```

## Batching

Batching combines multiple client commands into a single `AppendEntries` request:

```scala
val config = RaftConfig(
  localId = n1,
  batching = BatchConfig(
    enabled = true,
    maxSize = 100,     // max entries per batch
    maxWait = 10.millis // max time to wait before flushing
  )
)
```

When batching is enabled, the runtime buffers incoming commands and flushes them periodically or when the buffer is full. This dramatically reduces the number of network round-trips.

### Performance Impact

| Scenario | Without Batching | With Batching (100) |
|----------|-----------------|-------------------|
| 1000 commands | 1000 AppendEntries RPCs | ~10 RPCs |
| Network round-trips | 1000 | ~10 |
| Disk syncs | 1000 | ~10 |

## Pipelining

Pipelining allows the leader to send new `AppendEntries` requests without waiting for responses:

```scala
val config = RaftConfig(
  localId = n1,
  pipelining = PipelineConfig(
    enabled = true,
    maxInflight = 10  // up to 10 unacknowledged requests
  )
)
```

Without pipelining, the leader waits for each follower's response before sending the next batch. With pipelining, it sends up to `maxInflight` requests ahead, reducing latency from `n × RTT` to `RTT + n × processing_time`.

### When to Use What

| Technique | Best For | Trade-off |
|-----------|----------|-----------|
| **Neither** | Low-throughput, simple deployments | High latency per op |
| **Batching only** | Write-heavy workloads | Slight delay before flush |
| **Pipelining only** | Latency-sensitive, moderate throughput | Memory for in-flight tracking |
| **Both** | High-throughput production | Most complex configuration |

## Parallel Replication

Parallel replication sends `AppendEntries` to all followers concurrently instead of sequentially:

```scala
val config = RaftConfig(
  localId = n1,
  parallelReplicationEnabled = true  // default
)
```

When enabled, the library produces `ParallelReplicate` effects instead of individual `SendMessage` effects:

```scala
case ParallelReplicate(peers, msg) =>
  peers.parTraverse_(peer => transport.send(peer, msg))
```

## Summary

| Concept | API |
|---------|-----|
| **Create entries** | `Log.command(index, term, data)` |
| **Replicate** | `AppendEntriesRequest` → `RaftLogic.onMessage` |
| **Resolve conflicts** | `TruncateLog` + `AppendLogs` effects |
| **Commit** | `leader.calculateCommitIndex(clusterSize, getTermAt)` |
| **Batching** | `BatchConfig(maxSize, maxWait)` |
| **Pipelining** | `PipelineConfig(maxInflight)` |
| **Parallel append** | `parallelReplicationEnabled = true` |

---

*Next: [Chapter 9 — Case Study: Distributed Key-Value Store](09-case-distributed-kv.md) builds a complete replicated data store using everything we've learned.*
