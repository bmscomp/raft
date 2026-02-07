# Chapter 14: Troubleshooting & Operational Pitfalls

*Production Raft clusters hit failure modes that don't appear in textbooks. This chapter catalogs the most common operational problems, explains why they happen, and provides concrete diagnostic steps. If you're running Raft in production and something goes wrong, start here.*

---

## Symptom: Frequent Leadership Changes

### What You See

The cluster keeps electing new leaders — multiple elections per minute, even though no nodes are crashing. Clients experience intermittent write failures as the leader changes.

### Why It Happens

The most common cause is **election timeout misconfiguration**. If the election timeout is too close to the heartbeat interval, normal network jitter can cause followers to time out and start elections:

```
Example: heartbeatInterval = 100ms, electionTimeoutMin = 150ms

If a heartbeat is delayed by just 60ms (common in virtualized environments),
the follower's timer fires → unnecessary election → leader is deposed.
```

### The Fix

Use the Raft paper's recommendation: `electionTimeout >> heartbeatInterval`. A 10:1 ratio is a good starting point:

```scala
val config = RaftConfig(
  localId = n1,
  heartbeatInterval = 100.millis,
  electionTimeoutMin = 1000.millis,  // 10× heartbeat
  electionTimeoutMax = 2000.millis   // 20× heartbeat
)
```

> **Note — Cloud environments:** In AWS, GCP, or Azure, network latency can spike during VM migration, snapshot creation, or noisy-neighbor effects. Be generous with your timeouts. An election timeout of 2-5 seconds is not unusual for cloud deployments.

Other causes:
- **Pre-Vote disabled** with an unstable network (see [Chapter 3](03-raft-advanced-theory.md) — Pre-Vote prevents term inflation from partitioned nodes)
- **Leader Stickiness disabled** with aggressive followers that vote too eagerly
- **Garbage collection pauses** in the JVM that freeze the leader long enough for followers to start elections

---

## Symptom: Writes Stall but the Cluster Looks Healthy

### What You See

The leader is elected, heartbeats are flowing, but client writes never commit. The `commitIndex` is stuck.

### Why It Happens

Writes commit when replicated to a **majority**. If a majority of followers are too far behind (or unreachable), the leader can append entries to its own log but never advance the commit index.

Common scenarios:

1. **Slow followers** — a follower's disk is slow, causing `PersistHardState` and `AppendLogs` effects to take too long. The leader sends entries faster than the follower can persist them.

2. **The current-term safety check** — Raft will not commit entries from previous terms by counting replicas (see [Chapter 2](02-raft-fundamentals.md), "Commit Rules"). After a leader election, the new leader must commit at least one entry from its *own* term before older entries can be committed. If no new writes arrive, old entries remain uncommitted.

3. **Network asymmetry** — the leader can receive from followers but followers can't receive from the leader (or vice versa).

### The Fix

```scala
// Check 1: Is the leader producing the initial no-op?
// The library does this automatically via the BecomeLeader effect.
// Verify your effect interpreter handles BecomeLeader by appending a no-op.

// Check 2: Are effects being executed?
transition.effects.foreach { effect =>
  logger.info(s"Effect: $effect")
}
// If you see AppendLogs and SendMessage effects but no CommitEntries,
// followers aren't responding — check network connectivity.

// Check 3: Is matchIndex advancing?
leader match
  case Leader(term, matchIndex) =>
    logger.info(s"matchIndex: $matchIndex")
    // If matchIndex values are not advancing, followers are rejecting entries
```

---

## Symptom: Split-Brain After Network Partition

### What You See

After a network partition heals, you have two nodes that both believe they are the leader, or clients that received conflicting acknowledgments.

### Why It Happens

This should **not** happen with a correct Raft implementation. If it does, the most likely causes are:

1. **Persistence failures** — the `StableStore` is not actually durable. If a node crashes and loses its `term` and `votedFor`, it can vote twice in the same term, potentially electing two leaders.

2. **Clock-based lease reads with excessive clock skew** — if you're using lease-based reads (see [Chapter 3](03-raft-advanced-theory.md)) and the clock skew exceeds your `maxClockSkew` setting, a deposed leader can serve stale reads during its (incorrectly) extended lease.

3. **Bug in the effect interpreter** — if `PersistHardState` effects are not executed before responding to RPCs (i.e., the persist is async but the response is sent immediately), safety invariants can be violated.

### The Fix

> [!CAUTION]
> Split-brain is a **data safety issue**. If you see it, stop and investigate before allowing further writes.

- Verify your `StableStore` persists to durable storage and survives process restarts
- Verify `PersistHardState` effects are awaited before sending any responses
- If using lease reads, increase `maxClockSkew` margin or switch to ReadIndex
- Run the library's property-based safety tests to verify your integration (see [Chapter 16](16-property-based-testing.md))

---

## Symptom: Log Growing Without Bound

### What You See

The log keeps growing, consuming disk space, and follower recovery after a crash takes increasingly long as the entire log must be replayed.

### Why It Happens

Raft logs are append-only. Without compaction, every command ever executed remains in the log forever.

### The Fix

Implement **log compaction** via snapshots:

1. Periodically snapshot the state machine's state at a known commit index
2. Persist the snapshot to durable storage
3. Truncate the log up to the snapshot's index
4. When a follower is too far behind, send the snapshot via `InstallSnapshot` instead of replaying the entire log

```scala
// Snapshot trigger: compact when log exceeds threshold
if logStore.lastIndex - logStore.firstIndex > snapshotThreshold then
  val snapshot = stateMachine.snapshot()
  snapshotStore.save(snapshot, commitIndex)
  logStore.truncateBefore(commitIndex)
```

A common compaction strategy is to snapshot every N committed entries (e.g., every 10,000) or when the log exceeds a size threshold.

---

## Symptom: Partitioned Node Causes Term Inflation

### What You See

A node that was temporarily partitioned rejoins with a much higher term than the rest of the cluster, forcing an unnecessary leader election.

### Why It Happens

Without Pre-Vote, a partitioned node repeatedly starts elections, incrementing its term each time. When it rejoins, its high term forces all other nodes to step up to that term (the term rule from [Chapter 2](02-raft-fundamentals.md)).

### The Fix

Enable Pre-Vote:

```scala
val config = RaftConfig(
  localId = n1,
  preVoteEnabled = true  // prevents term inflation
)
```

With Pre-Vote enabled, the partitioned node's pre-election checks will fail (it can't reach a majority), so its term never increments. See [Chapter 3](03-raft-advanced-theory.md) for the full Pre-Vote protocol.

---

## Symptom: Slow Follower Slowing Down the Whole Cluster

### What You See

One follower is slow (bad disk, high CPU load) and it's causing commit latency to increase for the entire cluster.

### Why It Happens

Without parallel replication, the leader sends `AppendEntries` sequentially. A slow follower blocks communication with other followers, delaying the commit quorum.

### The Fix

1. **Enable parallel replication** — the leader sends to all followers concurrently:

```scala
val config = RaftConfig(
  localId = n1,
  parallelReplicationEnabled = true
)
```

2. **Demote to learner** — if the slow node is consistently behind, convert it to a learner. Learners receive replication but don't participate in quorum, so they can't slow down commits:

```scala
// Reconfigure: move slow node to learner role
val newConfig = clusterConfig.beginJointConsensus(
  Map(slowNode -> NodeRole.Learner, ...otherNodes -> NodeRole.Voter)
)
```

See [Chapter 3](03-raft-advanced-theory.md) for learner semantics and [Chapter 8](08-log-replication-practice.md) for parallel replication.

---

## Diagnostic Checklist

When debugging a Raft cluster, check these in order:

| Check | How | What It Tells You |
|-------|-----|-------------------|
| **Terms match** | Compare `term` across all nodes | If terms diverge wildly, a partition happened |
| **Log lengths** | Compare `lastLogIndex` | If a follower is far behind, check its persistence speed |
| **Commit index** | Compare leader's `commitIndex` with followers | If followers are committed but leader isn't, the leader lost legitimacy |
| **Effect execution** | Log all effects and verify execution | Missing effect interpretation is the most common integration bug |
| **Persistence durability** | Kill -9 the process, restart, check state | If term/votedFor is lost, your StableStore is broken |
| **Timer accuracy** | Log election timeout and heartbeat intervals | If timers are imprecise, elections will be flaky |

---

*Next: [Chapter 15 — End-to-End Integration](15-end-to-end-integration.md) shows how to wire all the SPIs together into a production-ready `RaftNode` runtime.*
