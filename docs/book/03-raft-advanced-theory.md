# Chapter 3: Advanced Raft Theory

*The basic Raft protocol handles elections, replication, and safety. Production systems need more: protection against term inflation, safe membership changes, efficient reads, and graceful leadership handoff. This chapter covers the theoretical foundations for each.*

---

## Pre-Vote Protocol

### The Problem: Term Inflation

Consider a network partition that isolates a single follower:

```
┌─────────────┐      ╳ Partition ╳       ┌─────────────┐
│   Node A    │                          │   Node C    │
│ (Follower)  │◄─────────────────────────│  (Leader)   │
│   term = 5  │       Network OK         │   term = 5  │
└─────────────┘                          └─────────────┘
                                         ┌─────────────┐
                                         │   Node B    │
         ╳ Partition ╳                   │ (Follower)  │
                                         │   term = 5  │
┌─────────────┐                          └─────────────┘
│   Node D    │   ← Isolated! Cannot reach anyone
│ (Follower)  │
│   term = 5  │
└─────────────┘
```

Without pre-vote, Node D repeatedly times out and starts elections, incrementing its term each time: 6, 7, 8, 9, ... When the partition heals, D's inflated term forces the entire cluster to step down, disrupting a perfectly healthy leader.

### The Solution: Two-Phase Elections

The pre-vote protocol (§9.6 of Ongaro's thesis) adds a preliminary phase:

```
Standard:    Follower → Candidate(term+1) → may force cluster step-down
Pre-Vote:    Follower → PreCandidate(same term) → majority? → Candidate(term+1)
```

In the pre-vote phase, the node sends `RequestVote` RPCs with `isPreVote = true` at the **next** term. Crucially:

- The pre-candidate does **not** increment its own term
- No `PersistHardState` effects are produced (the term is not persisted)
- Other nodes do **not** update their term in response

Only if a majority grants the pre-vote does the node proceed to a real election. If the node is partitioned, it can never get a majority and its term stays stable.

### Formal Guarantee

Pre-vote preserves all of Raft's safety properties because it only gates entry into a real election — the election itself proceeds identically to standard Raft.

## Leader Stickiness

### The Problem: Unnecessary Elections

Even with pre-vote, a follower might start an election if its election timer fires slightly before a heartbeat arrives. This can happen during brief network delays, causing unnecessary leader churn.

### The Solution

Leader stickiness adds a simple check: a follower rejects a `RequestVote` if it has recently heard from a valid leader (within the election timeout window). The intuition is: if the leader is alive and communicating, there's no reason to elect a new one.

```
Follower receives RequestVote:
  if (lastHeartbeatFromLeader was recent):
    reject vote  ← "I already have a leader"
  else:
    evaluate vote normally
```

This reduces disruptive elections during transient network issues without affecting safety — if the leader truly fails, heartbeats stop arriving and votes proceed normally.

## Joint Consensus

### The Problem: Safe Membership Changes

Changing the cluster membership (adding or removing nodes) is dangerous because there might be a moment when two different majorities exist simultaneously — one from the old configuration and one from the new:

```
Old config: {A, B, C}          → majority = 2
New config: {A, B, C, D, E}    → majority = 3

Dangerous moment: if old A,B and new C,D,E both think they're a majority,
two leaders could be elected simultaneously!
```

### The Solution: Dual-Quorum Transition

Joint consensus (§6 of the Raft paper) introduces a transitional configuration where **both** the old and new configurations must agree:

```
Phase 1: C_old           → Normal operation
Phase 2: C_old,new       → Both configurations must form a quorum
Phase 3: C_new           → Normal operation

┌──────────┐    ┌───────────────┐    ┌──────────┐
│  C_old   │───>│  C_old,new    │───>│  C_new   │
│          │    │               │    │          │
│ Quorum:  │    │ Quorum needs  │    │ Quorum:  │
│ old maj  │    │ OLD majority  │    │ new maj  │
│          │    │ AND           │    │          │
│          │    │ NEW majority  │    │          │
└──────────┘    └───────────────┘    └──────────┘
```

During joint consensus:
- The leader replicates a **joint configuration entry** to the log
- All decisions (elections, commits) require agreement from majorities in **both** configs
- Once the joint entry is committed, a second entry for the new configuration is appended
- When the new configuration entry is committed, the transition is complete

### Safety Argument

Joint consensus is safe because at no point can two independent majorities exist:

- Before `C_old,new` is committed: only `C_old` makes decisions
- While `C_old,new` is active: both `C_old` AND `C_new` must agree
- After `C_new` is committed: only `C_new` makes decisions

Any leader elected during the transition must have the joint entry (by the election restriction), so it knows to require dual quorum.

### Aborting a Transition

If a membership change fails or times out during joint consensus, the cluster can safely revert to the old configuration by discarding the uncommitted joint entry. This is the `abortJointConsensus` operation.

## Linearizable Reads

### The Problem: Stale Reads

A naive read from the leader might return stale data:

```
Time 0: Leader A serves read(x) → "5"
Time 1: Network partition isolates A
Time 2: New leader B is elected, writes x = "7"
Time 3: Client reads from A → still returns "5" (stale!)
```

Leader A doesn't know it's been deposed. Its term hasn't changed because it hasn't received any messages from the new leader.

### Solution 1: ReadIndex

The leader confirms it's still the leader by performing a heartbeat round:

```
1. Client requests read(x)
2. Leader records current commit index as readIndex
3. Leader sends heartbeats to all followers
4. If majority responds → leader is still valid
5. Leader waits until state machine has applied through readIndex
6. Leader serves the read
```

This adds one network round-trip but guarantees linearizability: the read reflects all writes committed before the request.

### Solution 2: Lease-Based Reads

If the leader holds a **lease** — a period during which no other leader can be elected — it can serve reads immediately without a heartbeat round:

```
1. Leader receives heartbeat ack from majority at time T
2. Lease is valid until T + leaderLeaseDuration
3. If current time < lease expiry → serve read immediately
4. Otherwise → fall back to ReadIndex
```

Lease-based reads require **bounded clock skew** between nodes. If one node's clock runs too fast, it might start an election before the leader's lease expires, violating consistency.

| Approach | Latency | Requirement |
|----------|---------|-------------|
| **Through the log** | Log replication round-trip | None |
| **ReadIndex** | One heartbeat round-trip | None |
| **Lease-based** | Zero round-trips | Bounded clock skew |

## Leadership Transfer

### The Problem

Sometimes you need to move leadership deliberately:

- **Rolling upgrades**: drain the leader before restarting it
- **Load balancing**: move leadership to a less loaded node
- **Planned maintenance**: gracefully move leadership before taking a node offline

### The Solution: TimeoutNow

The leadership transfer protocol:

```
1. Leader stops accepting new client requests
2. Leader sends remaining log entries to the target node
3. Leader sends TimeoutNow to the target
4. Target immediately starts an election (skipping the timeout)
5. Target wins (it has the most up-to-date log)
6. Old leader steps down
```

The `TimeoutNow` message bypasses the election timeout and pre-vote phase, causing the target to immediately transition to Candidate with an incremented term. Since the leader ensured the target's log is fully caught up, the target wins the election.

## Log Compaction and Snapshots

### The Problem: Unbounded Log Growth

The log grows indefinitely as commands are appended. Even entries that have been applied to the state machine remain in the log. Eventually, a node runs out of disk space, and new nodes joining the cluster must replay the entire log from the beginning.

### The Solution: Snapshots

Periodically, each node captures a **snapshot** of its state machine at a particular log index, then discards all log entries through that index:

```
Before:  [entry 1] [entry 2] [entry 3] [entry 4] [entry 5] [entry 6]
                                                              ↑ applied

After:   [SNAPSHOT at index 4] [entry 5] [entry 6]
         Captures the cumulative effect of entries 1–4
```

When a follower is so far behind that the leader has already discarded the entries it needs, the leader sends an `InstallSnapshot` RPC instead of `AppendEntries`.

### Snapshot Contents

A snapshot must contain:
- The complete state machine state at the snapshot index
- The term and index of the last included log entry
- The cluster configuration at that point

## Non-Voting Members

### Learner Nodes

Learners receive log replication from the leader but do not vote in elections or count toward quorum. Uses include:

- **Catch-up**: new nodes join as learners to build up their log before being promoted to voters
- **Read replicas**: serve stale reads without affecting write quorum
- **Geo-replication**: replicate data to distant data centers without increasing quorum latency

### Witness Nodes

Witnesses vote in elections (ensuring quorum for leader election) but store minimal state. They are lightweight tie-breakers for even-sized clusters.

| Role | Votes? | Stores Log? | Applies FSM? |
|------|--------|-------------|--------------|
| **Voter** | Yes | Full | Yes |
| **Learner** | No | Full | Yes |
| **Witness** | Yes | Metadata only | No |

## Summary

| Extension | Problem Solved | Mechanism |
|-----------|---------------|-----------|
| **Pre-Vote** | Term inflation from partitions | Two-phase election |
| **Leader Stickiness** | Unnecessary elections | Reject votes when leader is alive |
| **Joint Consensus** | Unsafe membership changes | Dual-quorum transition |
| **ReadIndex** | Stale reads | Heartbeat confirmation |
| **Lease Reads** | Read latency | Time-bounded leadership |
| **Leadership Transfer** | Graceful handoff | TimeoutNow message |
| **Snapshots** | Unbounded log growth | State machine checkpoint |
| **Learners** | Slow catch-up, read replicas | Non-voting replication |

---

*Next: [Chapter 4 — Design Philosophy](04-design-philosophy.md) explains how the Functional RAFT Library implements these concepts using pure state transitions and an effects system.*
