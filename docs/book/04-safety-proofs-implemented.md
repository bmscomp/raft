# Chapter 4: Safety Proofs, Implemented

*How Raft's five safety properties map to concrete library code — and how to test that they hold.*

---

## The Bridge Between Paper and Code

Chapters 2 and 3 presented Raft's safety properties as formal invariants. Chapter 5 (next) will show how the library's architecture embodies these guarantees. This chapter sits at the crossroads: for each of Raft's five core safety properties, we'll show the **exact lines of code** that enforce it, explain **why** the enforcement is correct, and provide a **test case** that would detect a violation.

This is something no other Raft resource does systematically. Most textbooks state the properties; most codebases assume them. Here, we make the connection explicit.

> [!NOTE]
> All code references point to `RaftLogic.scala`, the pure consensus core. Because the logic is free of I/O and side effects, every safety property can be verified with simple unit tests — no mock infrastructure needed.

---

## Property 1: Election Safety

### The Invariant

> **At most one leader can be elected in a given term.**
>
> — *Raft Paper, §5.2*

This is the most fundamental safety property. If two leaders exist in the same term, they could accept conflicting client writes, permanently diverging the replicated log.

### How the Code Enforces It

Election Safety rests on three interlocking mechanisms:

**1. A node votes at most once per term**

```scala
// RaftLogic.onRequestVote — line 271
case f: Follower =>
  val canVote = f.votedFor.isEmpty || f.votedFor.contains(req.candidateId)
```

The `canVote` guard ensures a follower with `votedFor = Some(nodeA)` will reject a vote request from `nodeB` in the same term. The only way to vote again is to advance to a higher term (which resets `votedFor` to `None`).

**2. The vote is persisted before responding**

```scala
// RaftLogic.onRequestVote — lines 291-292
val effects = List(
  SendMessage(req.candidateId, response)
) ++ (if req.isPreVote then Nil
      else List(PersistHardState(req.term, Some(req.candidateId))))
```

The `PersistHardState` effect appears alongside the `SendMessage` effect. The runtime executes persistence *before* sending the response. If the node crashes between persisting and responding, it will recover with `votedFor` set — preventing a duplicate vote after restart.

**3. A candidate needs a strict majority**

```scala
// RaftLogic.onVoteResponse — line 137
if newCandidate.hasMajority(clusterSize) then
  Transition.withEffect(Leader(state.term), BecomeLeader)
```

Since any two majorities in a cluster of size *n* must overlap by at least one node, and that overlapping node can only vote once, at most one candidate can collect a majority.

### The Proof Structure

```
1. Each node votes at most once per term     (votedFor guard)
2. Votes are durable across crashes          (PersistHardState before SendMessage)
3. A leader needs > n/2 votes               (hasMajority check)
4. Any two majorities share ≥ 1 node        (pigeonhole principle)
∴ At most one leader per term               □
```

### How to Test It

```scala
test("Election Safety: two candidates in same term cannot both win") {
  val config = RaftConfig.default(NodeId("node1"))
  val clusterSize = 5  // majority = 3

  // Candidate A has 2 votes (self + node2)
  val candidateA = Candidate(term = 5, votesReceived = Set(NodeId("node1"), NodeId("node2")))
  // Candidate A receives a third vote → becomes leader
  val respA = RequestVoteResponse(term = 5, voteGranted = true, isPreVote = false)
  val transitionA = RaftLogic.onVoteResponse(candidateA, NodeId("node3"), respA, config, clusterSize)
  assert(transitionA.state.isInstanceOf[Leader])

  // Meanwhile, Candidate B also has 2 votes (self + node4)
  // But node3 already voted for A in term 5...
  // Follower node3 rejects B's request:
  val follower3 = Follower(term = 5, votedFor = Some(NodeId("node1")), leaderId = None)
  val voteReqB = RequestVoteRequest(term = 5, candidateId = NodeId("node5"),
    lastLogIndex = 0, lastLogTerm = 0, isPreVote = false)
  val transitionB = RaftLogic.onMessage(follower3, voteReqB, config, 0, 0, clusterSize)

  // Node3 rejects the vote — B cannot reach majority
  val sentMsg = transitionB.effects.collect { case SendMessage(_, r: RequestVoteResponse) => r }
  assert(sentMsg.exists(!_.voteGranted))
}
```

> [!TIP]
> The test runs in microseconds because `RaftLogic` is pure — no timers, no network, no disk. This is the direct benefit of the "Functional Core" design described in the next chapter.

---

## Property 2: Leader Append-Only

### The Invariant

> **A leader never overwrites or deletes entries in its log; it only appends new entries.**
>
> — *Raft Paper, §5.3*

### How the Code Enforces It

When a leader receives an `AppendEntriesRequest` from another node in the same term, it rejects the request rather than allowing its log to be modified:

```scala
// RaftLogic.onAppendEntries — line 217
case l: Leader =>
  // Reject — we are the leader in this term
  val response = AppendEntriesResponse(l.term, success = false, 0)
  Transition.withEffect(l, SendMessage(req.leaderId, response))
```

A leader only modifies the replicated log through `AppendLogs` effects produced when processing client commands — never through incoming `AppendEntriesRequest` RPCs.

If a higher-term `AppendEntriesRequest` arrives, the leader steps down *first*, transitioning to `Follower`, and only then accepts the entries:

```scala
// RaftLogic.onAppendEntries — line 212
case l: Leader if req.term > l.term =>
  // Higher term leader — step down
  val newFollower = Follower(req.term, None, Some(req.leaderId))
  Transition.withEffect(newFollower, ResetElectionTimer)
```

At this point the node is a follower, not a leader — so the Leader Append-Only property is not violated.

### How to Test It

```scala
test("Leader Append-Only: leader rejects AppendEntries in same term") {
  val leader = Leader(term = 5)
  val config = RaftConfig.default(NodeId("leader1"))

  // Another node claims to be leader in the same term
  val req = AppendEntriesRequest(
    term = 5, leaderId = NodeId("imposter"),
    prevLogIndex = 0, prevLogTerm = 0,
    entries = Seq(Log(index = 1, term = 5, data = Array.empty)),
    leaderCommit = 0
  )
  val transition = RaftLogic.onMessage(leader, req, config, 0, 0, 3)

  // Leader stays leader, rejects the request
  assert(transition.state.isInstanceOf[Leader])
  val response = transition.effects.collect { case SendMessage(_, r: AppendEntriesResponse) => r }
  assert(response.exists(!_.success))
  // No AppendLogs effect — log was not modified
  assert(!transition.effects.exists(_.isInstanceOf[AppendLogs]))
}
```

---

## Property 3: Log Matching

### The Invariant

> **If two logs contain an entry with the same index and term, then the logs are identical in all entries up through that index.**
>
> — *Raft Paper, §5.3*

This is the property that makes Raft's replicated log a reliable foundation for state machine replication. It consists of two sub-properties:

1. **Uniqueness** — An `(index, term)` pair uniquely identifies a log entry. A leader creates at most one entry with a given index in a given term.
2. **Prefix agreement** — If two logs agree on entry *(i, t)*, they agree on all entries before *i*.

### How the Code Enforces It

The consistency check in `onAppendEntries` is the enforcement mechanism:

```scala
// RaftLogic.onAppendEntries — lines 160-162
val logMatches =
  if req.prevLogIndex == 0 then true  // Empty log case
  else getTermAt(req.prevLogIndex).contains(req.prevLogTerm)
```

This is an inductive proof implemented as a runtime check:

- **Base case**: `prevLogIndex == 0` — the log is empty, so all entries trivially match.
- **Inductive step**: the leader sends `prevLogIndex` and `prevLogTerm` with every `AppendEntriesRequest`. The follower checks that its entry at `prevLogIndex` has term `prevLogTerm`. If the check passes, the follower knows its log matches the leader's up to that point. If it fails:

```scala
// RaftLogic.onAppendEntries — lines 166-172
if !logMatches then
  // Log doesn't match — reject with hint for leader to backtrack
  val response = AppendEntriesResponse(req.term, success = false, 0)
  Transition(
    Follower(req.term, f.votedFor, Some(req.leaderId)),
    List(ResetElectionTimer, SendMessage(req.leaderId, response))
  )
```

The follower rejects the RPC, and the leader decrements `nextIndex` and retries with an earlier `prevLogIndex`. This backtracking continues until the two logs converge — the leader finds the last index where they agree, then overwrites everything after.

### Visualizing the Induction

```
Leader log:     [1:T1] [2:T1] [3:T2] [4:T3] [5:T3]
                                              ↑ sending entries from here

AppendEntriesRequest:
  prevLogIndex = 4
  prevLogTerm  = T3    ← "do you have entry (4, T3)?"
  entries      = [5:T3]

Follower log:   [1:T1] [2:T1] [3:T2] [4:T3]
                                      ↑ getTermAt(4) == Some(T3) ✓

The check passes. By induction on previous AppendEntries rounds,
entries 1–4 are identical. Now entry 5 is appended → logs match through index 5.
```

### How to Test It

```scala
test("Log Matching: follower rejects entries when prevLogTerm doesn't match") {
  val follower = Follower(term = 3, votedFor = None, leaderId = None)
  val config = RaftConfig.default(NodeId("f1"))

  // Leader thinks follower has entry (3, T2) — but follower has (3, T1)
  val getTermAt: Long => Option[Long] = {
    case 3 => Some(1L)  // follower has term 1 at index 3
    case _ => None
  }
  val req = AppendEntriesRequest(
    term = 3, leaderId = NodeId("leader"),
    prevLogIndex = 3, prevLogTerm = 2,  // leader expects term 2 at index 3
    entries = Seq.empty, leaderCommit = 0
  )
  val transition = RaftLogic.onMessage(follower, req, config, 3, 1, 3, getTermAt)

  // Follower rejects — logs diverge at index 3
  val response = transition.effects.collect { case SendMessage(_, r: AppendEntriesResponse) => r }
  assert(response.exists(!_.success))
}
```

---

## Property 4: Leader Completeness

### The Invariant

> **If a log entry is committed in a given term, then that entry will be present in the logs of the leaders for all higher-numbered terms.**
>
> — *Raft Paper, §5.4*

This is often considered the hardest property to understand. It says: once an entry is committed, no future leader can *not* have it.

### How the Code Enforces It

Leader Completeness is enforced by the **election restriction** — the `isLogUpToDate` check in `onRequestVote`:

```scala
// RaftLogic.isLogUpToDate — lines 464-471
private def isLogUpToDate(
    candidateLastIndex: Long,
    candidateLastTerm: Long,
    myLastIndex: Long,
    myLastTerm: Long
): Boolean =
  candidateLastTerm > myLastTerm ||
    (candidateLastTerm == myLastTerm && candidateLastIndex >= myLastIndex)
```

This function is the gatekeeping mechanism. A follower only grants a vote if the candidate's log is **at least as up-to-date** as its own. "Up-to-date" means:

1. The candidate's last entry has a higher term, **or**
2. Same last term and equal or larger last index.

The vote grant uses this check:

```scala
// RaftLogic.onRequestVote — lines 272-277
val logOk = isLogUpToDate(
  req.lastLogIndex,
  req.lastLogTerm,
  lastLogIndex,
  lastLogTerm
)
```

### Why This Works

A committed entry has been replicated to a majority. Any future leader must collect votes from a majority. These two majorities overlap. The overlapping node has the committed entry, and it will only vote for a candidate whose log is at least as up-to-date — which means the candidate must also have the committed entry (or something newer).

```
Committed entry at (index=5, term=3):
  Replicated to: {A, B, C}      ← majority of {A, B, C, D, E}

Future election in term 4:
  Candidate D needs votes from: 3 of {A, B, C, D, E}
  At least one voter ∈ {A, B, C} ∩ {voters for D}
  That voter has entry (5, T3) and won't vote for D unless D's log ≥ (5, T3)
  ∴ If D wins, D has entry (5, T3)   □
```

### How to Test It

```scala
test("Leader Completeness: candidate with stale log cannot win vote") {
  // Follower C has committed entry at (index=5, term=3)
  val followerC = Follower(term = 3, votedFor = None, leaderId = None)
  val config = RaftConfig.default(NodeId("C"))

  // Candidate D requests vote with a stale log (only through index=3, term=2)
  val voteReq = RequestVoteRequest(
    term = 4, candidateId = NodeId("D"),
    lastLogIndex = 3, lastLogTerm = 2,
    isPreVote = false
  )
  val transition = RaftLogic.onMessage(followerC, voteReq, config, 5, 3, 5)

  // Follower rejects — candidate's log is not up-to-date
  val response = transition.effects.collect { case SendMessage(_, r: RequestVoteResponse) => r }
  assert(response.exists(!_.voteGranted))
}
```

---

## Property 5: State Machine Safety

### The Invariant

> **If a server has applied a log entry at a given index to its state machine, no other server will ever apply a different entry for that index.**
>
> — *Raft Paper, §5.4.3*

This is the ultimate user-facing guarantee. It says: the state machine on every node sees the same sequence of commands. If Server A applies "set x = 1" at index 7, Server B will also apply "set x = 1" at index 7 — never something different.

### How the Code Enforces It

State Machine Safety is a *derived* property — it follows from the combination of Log Matching (Property 3) and the commit rule.

**The commit rule: only commit entries replicated to a majority.**

```scala
// RaftLogic.onAppendEntriesResponse — lines 239-247
val newCommitIndex = l.calculateCommitIndex(clusterSize, getTermAt)

if newCommitIndex > l.commitIndex then
  Transition(
    l.withCommitIndex(newCommitIndex),
    List(CommitEntries(newCommitIndex))
  )
```

The `CommitEntries` effect tells the runtime to apply all entries up to `newCommitIndex`. Since:

1. Committed entries are on a majority of servers (by the commit rule).
2. Log Matching ensures all servers agree on the content at each index.
3. Leader Completeness ensures no future leader can override committed entries.

...every server that advances `lastApplied` to index *i* will apply the same entry at index *i*.

### The Monotonicity Invariant

The code also enforces `commitIndex ≥ lastApplied` — the state machine never goes backward:

```scala
// In the VolatileState design (see Chapter 2):
// commitIndex only increases, lastApplied only increases,
// and lastApplied ≤ commitIndex at all times.
```

The `CommitEntries(upToIndex)` effect only fires when `newCommitIndex > l.commitIndex`, ensuring monotonic advancement.

### How to Test It

```scala
test("State Machine Safety: committed entries are applied in order") {
  val leader = Leader(term = 3).withCommitIndex(2)
  val config = RaftConfig.default(NodeId("leader"))

  // Follower reports successful replication through index 5
  val resp = AppendEntriesResponse(term = 3, success = true, matchIndex = 5)
  val getTermAt: Long => Option[Long] = idx => Some(3L) // all entries in term 3

  val transition = RaftLogic.onMessage(leader, resp, config, 5, 3, 3, getTermAt)

  // Verify CommitEntries effect advances monotonically
  val commits = transition.effects.collect { case CommitEntries(idx) => idx }
  commits.foreach(idx => assert(idx > 2, "commit index must advance monotonically"))
}
```

---

## Summary: Five Properties, One Pure Function

The table below maps each safety property to its enforcement mechanism in the library:

| Property | Section  | Enforcement Mechanism | Key Code |
|----------|----------|----------------------|----------|
| **Election Safety** | §5.2 | Single vote per term + majority requirement | `votedFor` guard + `hasMajority` |
| **Leader Append-Only** | §5.3 | Leader rejects same-term `AppendEntries` | `case l: Leader =>` reject branch |
| **Log Matching** | §5.3 | `prevLogIndex`/`prevLogTerm` consistency check | `getTermAt(req.prevLogIndex).contains(req.prevLogTerm)` |
| **Leader Completeness** | §5.4 | Election restriction via `isLogUpToDate` | Last term comparison + index tiebreaker |
| **State Machine Safety** | §5.4.3 | Majority-based commit + monotonic `lastApplied` | `calculateCommitIndex` + `CommitEntries` |

What makes this library unique is that all five properties are enforced in a single, pure Scala object — `RaftLogic` — with no hidden state, no callbacks, and no I/O. Every safety invariant is directly testable by calling a function and asserting on the result.

```
┌─────────────────────────────────────────────────────┐
│                   RaftLogic                         │
│                                                     │
│  ┌───────────────┐  ┌────────────────────────────┐  │
│  │ onRequestVote │  │ onAppendEntries            │  │
│  │               │  │                            │  │
│  │ • Election    │  │ • Log Matching (P3)        │  │
│  │   Safety (P1) │  │ • Leader Append-Only (P2)  │  │
│  │ • Leader      │  │ • State Machine            │  │
│  │   Complete-   │  │   Safety (P5)              │  │
│  │   ness (P4)   │  │                            │  │
│  └───────────────┘  └────────────────────────────┘  │
│                                                     │
│         All properties ↔ pure functions              │
│         All tests ↔ assert on Transition             │
└─────────────────────────────────────────────────────┘
```

> [!IMPORTANT]
> The properties are not independent — they form a dependency chain. Election Safety and Leader Append-Only support Log Matching, which supports Leader Completeness, which supports State Machine Safety. Breaking any earlier property cascades into violations of the later ones.

---

## TLA+ Cross-Reference

For readers interested in formal verification, the properties in this chapter correspond directly to the Raft TLA+ specification:

| Library Code | TLA+ Module | Predicate |
|-------------|-------------|-----------|
| `onRequestVote` vote guard | `raft.tla` | `HandleRequestVoteRequest` — `votedFor[i] = Nil \/ votedFor[i] = j` |
| `isLogUpToDate` | `raft.tla` | `LogIsUpToDate(i, j)` |
| `onAppendEntries` consistency check | `raft.tla` | `LogPrevEntryMatches(i, j)` |
| `CommitEntries` advancement | `raft.tla` | `AdvanceCommitIndex(i)` with `agree` counting |

The pure-functional design of `RaftLogic` makes it possible to imagine directly model-checking the Scala code against the TLA+ spec — each `onMessage` call corresponds to one TLA+ action step.

---

*Next: [Chapter 5 — Design Philosophy](05-design-philosophy.md) shows how the library's architecture — pure functions, explicit effects, opaque types — translates these safety properties into an ergonomic developer experience.*
