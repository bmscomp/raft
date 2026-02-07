# Chapter 2: Raft Fundamentals

*This chapter presents the core Raft protocol: how leaders are elected, how logs are replicated, and why the algorithm is safe. Every concept maps directly to an API in the library.*

---

## The Big Picture

Raft decomposes consensus into three sub-problems:

```
┌───────────────────────────────────────────────────┐
│                  RAFT PROTOCOL                    │
├─────────────────┬────────────────┬────────────────┤
│ Leader Election │ Log Replication│    Safety      │
│                 │                │                │
│ Who leads?      │ How data flows │ Why it's       │
│ When to change? │ from leader    │ always correct │
└─────────────────┴────────────────┴────────────────┘
```

At any given time, every node in a Raft cluster is in one of three roles:

| Role | Responsibility |
|------|---------------|
| **Leader** | Accepts client requests, replicates log entries, manages commits |
| **Follower** | Passive — responds to RPCs from the leader |
| **Candidate** | Attempting to become the new leader |

In normal operation, there is exactly **one leader** and all other nodes are followers. The leader handles all client requests. If the leader fails, a new one is elected.

## Terms: Raft's Logical Clock

Raft divides time into **terms** — monotonically increasing integers that act as a logical clock. Each term begins with an election. If a candidate wins, it serves as leader for the rest of the term. If no candidate wins (split vote), the term ends with no leader and a new term begins.

```
    Term 1          Term 2      Term 3      Term 4
┌──────────────┐┌──────────┐┌──────────┐┌──────────────┐
│  Election →  ││ Election ││ Election ││  Election →  │
│  Leader A    ││ (no win) ││ Leader B ││  Leader C    │
│  Normal ops  ││          ││ Normal   ││  Normal ops  │
└──────────────┘└──────────┘└──────────┘└──────────────┘
```

**Key invariant**: There is at most one leader per term.

Terms are the mechanism Raft uses to detect stale information. Every RPC includes the sender's term. If a node receives a message with a higher term, it immediately updates its own term and reverts to follower. If it receives a message with a lower term, it rejects it.

In the library, terms are represented as an opaque type for type safety:

```scala
opaque type Term = Long

object Term:
  def apply(value: Long): Term = value
  val Zero: Term = 0L
```

## Leader Election

### How It Starts

When a follower hasn't heard from a leader within its **election timeout** (a randomized interval, typically 150–300ms), it assumes the leader has failed and starts an election:

1. The follower increments its term
2. It transitions to the **Candidate** state
3. It votes for itself
4. It sends `RequestVote` RPCs to all other nodes

### How Votes Work

A node grants a vote if all of the following are true:

- The candidate's term is ≥ the voter's current term
- The voter hasn't already voted for someone else in this term
- The candidate's log is **at least as up-to-date** as the voter's log

The "at least as up-to-date" check compares the last entry in each log:

```
Candidate's log ends at (term=3, index=7)
Voter's log ends at     (term=3, index=5)
→ Candidate is more up-to-date (higher index at same term) → grant vote

Candidate's log ends at (term=2, index=10)
Voter's log ends at     (term=3, index=3)
→ Voter is more up-to-date (higher last term wins) → deny vote
```

This check is called the **election restriction** — it is the single most important safety mechanism in Raft and ensures that a leader always has the most complete log.

### How It Ends

An election ends in one of three ways:

| Outcome | Condition | Next State |
|---------|-----------|------------|
| **Win** | Candidate receives votes from a majority | Becomes Leader |
| **Lose** | Another node becomes leader (higher or equal term) | Becomes Follower |
| **Timeout** | No majority reached within election timeout | New election (term + 1) |

**Split vote avoidance**: Raft uses randomized election timeouts to make split votes rare. Each node picks a random timeout between `electionTimeoutMin` and `electionTimeoutMax`. The node with the shortest timeout starts its election first and usually wins before others time out.

In the library, a complete election looks like this:

```scala
import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.logic.RaftLogic
import raft.effect.Effect.*

val n1 = NodeId("node-1")
val config = RaftConfig(localId = n1, preVoteEnabled = false)

// Step 1: Election timeout fires
val follower = Follower(term = 0, votedFor = None, leaderId = None)
val election = RaftLogic.onMessage(follower, ElectionTimeout, config, 0, 0, 3)
// election.state → Candidate(term = 1, votesReceived = {node-1})
// election.effects → [PersistHardState(1, node-1), Broadcast(RequestVoteRequest(...)), ...]

// Step 2: Receive a vote
val candidate = election.state.asInstanceOf[Candidate]
val vote = RequestVoteResponse(term = 1, voteGranted = true, isPreVote = false)
val afterVote = RaftLogic.onVoteResponse(candidate, NodeId("node-2"), vote, config, 3)
// afterVote.state → Leader(term = 1)
// afterVote.effects → [BecomeLeader]
```

## Log Replication

### The Replicated Log

Raft's core data structure is the **replicated log** — an ordered sequence of commands that every node applies in the same order to its state machine:

```
┌───────────────────────────────────────────────┐
│ Index:   1       2       3       4       5    │
│ Term:    1       1       1       2       2    │
│ Data:    SET x=1 SET y=2 DEL z   SET x=3 NOP │
└───────────────────────────────────────────────┘
```

Each entry contains:
- An **index** (position in the log, 1-based)
- A **term** (the leader's term when the entry was created)
- A **command** (the data to apply to the state machine)

In the library:

```scala
import raft.state.Log

val entry = Log.command(index = 1, term = 1, data = "SET x=1".getBytes)
val noop  = Log.noOp(index = 1, term = 1)  // leaders append on election
```

### AppendEntries RPC

The leader replicates entries to followers using `AppendEntries`:

```
Leader sends to Follower:
  ┌─────────────────────────────────────────────┐
  │ AppendEntriesRequest                        │
  │   term         = 2  (leader's current term) │
  │   leaderId     = "node-1"                   │
  │   prevLogIndex = 3  (index before new ones) │
  │   prevLogTerm  = 1  (term at prevLogIndex)  │
  │   entries      = [{idx=4, term=2, ...}]     │
  │   leaderCommit = 3  (leader's commit index) │
  └─────────────────────────────────────────────┘
```

The follower checks whether it has an entry at `prevLogIndex` with term `prevLogTerm`. If yes, the logs are consistent up to that point and the new entries are appended. If not, the follower rejects the request and the leader decrements `prevLogIndex` to find the point of divergence.

### The Log Matching Property

This consistency check guarantees two powerful invariants:

1. **If two entries in different logs have the same index and term, they store the same command.**
2. **If two entries in different logs have the same index and term, all preceding entries are identical.**

Together, these ensure that once a log entry is replicated to a majority, every server that has that entry also has every entry before it in the same order.

### Commit Rules

An entry is **committed** when it has been replicated to a majority of servers. Once committed, it will eventually be applied to every node's state machine. The leader tracks how far each follower has replicated via a `matchIndex` map:

```
Leader (3 nodes):
  matchIndex = { node-2 → 5, node-3 → 3 }

  Commit index = median of {5, 3} with self = max
  → Can commit up to index 3 (majority has entries through 3)
```

**The term check**: A leader can only commit entries from its own term. This prevents a subtle bug where a leader from a new term could commit an old entry that might later be overwritten. Once an entry from the current term is committed, all prior entries are implicitly committed too.

## Safety Properties

Raft guarantees five safety properties:

| Property | Guarantee |
|----------|-----------|
| **Election Safety** | At most one leader per term |
| **Leader Append-Only** | A leader never overwrites or deletes entries in its log |
| **Log Matching** | If two logs contain an entry with the same index and term, the logs are identical through that index |
| **Leader Completeness** | If an entry is committed in a given term, it will be present in the logs of all leaders for higher terms |
| **State Machine Safety** | If a server applies a log entry at a given index, no other server will ever apply a different entry at that index |

The **election restriction** (candidates must have up-to-date logs) is the key mechanism that ensures **Leader Completeness**. A candidate cannot win an election unless its log contains all committed entries, because a majority voted for it and a majority had the committed entry.

## State Machine

The state machine is the application logic that consumes committed log entries:

```
          ┌──────────────┐
 Log ────>│ State Machine │────> Application State
          │              │
          │ apply(entry) │      "x" → "1"
          │              │      "y" → "2"
          └──────────────┘
```

Every node applies the same entries in the same order, producing identical state. This is the fundamental guarantee of replicated state machines: **any node can serve a read and return the same result**.

## Heartbeats

The leader periodically sends **heartbeat** messages (empty `AppendEntries`) to all followers. These serve two purposes:

1. **Preventing election timeouts**: Followers reset their election timer when they receive a heartbeat
2. **Communicating commit index**: The `leaderCommit` field tells followers which entries are safe to apply

The heartbeat interval must be significantly shorter than the election timeout:

```
heartbeatInterval ≪ electionTimeout
     50ms                150–300ms
```

This ensures that followers don't start unnecessary elections during normal operation.

## Summary

| Concept | Mechanism |
|---------|-----------|
| **Logical time** | Terms (monotonically increasing integers) |
| **Leader election** | Randomized timeouts + majority vote |
| **Log replication** | `AppendEntries` RPC with consistency check |
| **Safety** | Election restriction ensures leader completeness |
| **Commitment** | Majority replication = committed |
| **Heartbeats** | Prevent elections, propagate commit index |

---

*Next: [Chapter 3 — Advanced Raft Theory](03-raft-advanced-theory.md) covers the extensions needed for production systems: Pre-Vote, joint consensus, linearizable reads, and leadership transfer.*
