# Chapter 17: Property-Based Testing for Raft

*Raft's correctness rests on a handful of precise safety invariants. This chapter shows how to express those invariants as machine-checkable properties using ScalaCheck, then throw thousands of random message schedules at them. If any schedule violates an invariant, you have a bug — and the test gives you the exact sequence that triggers it.*

---

## Why Property-Based Testing Matters for Consensus

Unit tests verify specific scenarios you thought of. Property-based tests verify invariants across scenarios **you didn't think of**. For a consensus algorithm, this distinction is critical: the dangerous bugs are the ones that emerge from unusual message orderings, partial failures, and timing coincidences — exactly the scenarios that are hard to enumerate by hand.

The library's pure functional design makes property-based testing unusually practical. Since `RaftLogic.onMessage` is a pure function with no side effects, you can:

1. **Generate** random sequences of Raft events (elections, votes, heartbeats, appends)
2. **Apply** them to the pure state machine
3. **Assert** that safety invariants hold after every transition
4. **Shrink** failing cases to the minimal reproduction

No mocks, no timeouts, no network simulators. Just functions and data.

## The Five Safety Properties

The Raft paper (§5.2) defines five properties that a correct implementation must satisfy. These are our test targets:

| Property | Invariant | Informal Meaning |
|----------|-----------|-----------------|
| **Election Safety** | At most one leader per term | Two nodes can't both think they're leading in the same term |
| **Leader Append-Only** | A leader never overwrites or deletes entries in its log | Leaders only append; followers may truncate |
| **Log Matching** | If two logs have entries with the same index and term, all preceding entries are identical | Agreement at any point implies agreement on the entire prefix |
| **Leader Completeness** | If an entry is committed in a term, that entry is present in the logs of all leaders in later terms | Elected leaders always have all committed data |
| **State Machine Safety** | If a server has applied a log entry at a given index, no other server will ever apply a different entry at that index | Different nodes never apply conflicting commands |

## Setting Up the Test Infrastructure

### The Simulated Cluster

```scala
import org.scalacheck.*
import org.scalacheck.Prop.*
import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.logic.RaftLogic

case class SimulatedNode(
  id: NodeId,
  state: NodeState,
  log: Vector[Log],
  commitIndex: Long,
  appliedEntries: Vector[Log]
)

case class SimulatedCluster(
  nodes: Map[NodeId, SimulatedNode],
  history: Vector[String]
):
  def leaders: Map[Long, Set[NodeId]] =
    nodes.values.collect {
      case n if n.state.isInstanceOf[Leader] =>
        n.state.asInstanceOf[Leader].term -> n.id
    }.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
```

### The Event Generator

```scala
object RaftEventGen:

  val nodeIds = List(NodeId("n1"), NodeId("n2"), NodeId("n3"))

  val electionTimeout: Gen[RaftMessage] = Gen.const(ElectionTimeout)
  val heartbeatTimeout: Gen[RaftMessage] = Gen.const(HeartbeatTimeout)

  def voteResponse(term: Long): Gen[RaftMessage] =
    for
      granted <- Gen.oneOf(true, false)
    yield RequestVoteResponse(term = term, voteGranted = granted, isPreVote = false)

  def appendResponse(term: Long): Gen[RaftMessage] =
    for
      success <- Gen.oneOf(true, false)
      idx     <- Gen.choose(0L, 100L)
    yield AppendEntriesResponse(
      term = term, success = success, matchIndex = idx, lastLogIndex = idx
    )

  def randomEvent(currentTerm: Long): Gen[RaftMessage] =
    Gen.frequency(
      3 -> electionTimeout,
      2 -> heartbeatTimeout,
      2 -> voteResponse(currentTerm),
      2 -> appendResponse(currentTerm),
      1 -> voteResponse(currentTerm + 1)  // messages from higher terms
    )
```

## Property 1: Election Safety

```scala
property("Election Safety: at most one leader per term") =
  forAll(Gen.listOfN(100, RaftEventGen.randomEvent(0))) { events =>
    val cluster = events.foldLeft(initialCluster) { (cluster, event) =>
      applyEventToRandomNode(cluster, event)
    }

    // Check: for every term, at most one node is Leader
    cluster.leaders.forall { (term, leaderSet) =>
      leaderSet.size <= 1
    }
  }
```

This property generates 100 random Raft events, applies each one to a randomly chosen node, and then checks that no term has two leaders. ScalaCheck will run this with hundreds of different random sequences.

## Property 2: Log Matching

```scala
property("Log Matching: same index+term implies same prefix") =
  forAll(Gen.listOfN(200, RaftEventGen.randomEvent(0))) { events =>
    val cluster = events.foldLeft(initialCluster) { (cluster, event) =>
      applyEventToRandomNode(cluster, event)
    }

    val logs = cluster.nodes.values.map(_.log).toList

    // For every pair of logs, check the Log Matching Property
    logs.combinations(2).forall { case List(log1, log2) =>
      val commonIndices = (0 until math.min(log1.size, log2.size))

      commonIndices.forall { i =>
        if log1(i).term == log2(i).term then
          // Same index and term → all preceding entries must also match
          (0 to i).forall(j => log1(j) == log2(j))
        else
          true // different terms at this index, no constraint
      }
    }
  }
```

## Property 3: Leader Append-Only

```scala
property("Leader Append-Only: leaders never delete entries") =
  forAll(Gen.listOfN(50, RaftEventGen.randomEvent(0))) { events =>
    var leaderLogs: Map[NodeId, Vector[Log]] = Map.empty

    events.foldLeft(initialCluster) { (cluster, event) =>
      val updated = applyEventToRandomNode(cluster, event)

      updated.nodes.foreach { (id, node) =>
        node.state match
          case _: Leader =>
            leaderLogs.get(id) match
              case Some(prevLog) =>
                // The current log must be a prefix-extension of the previous log
                assert(node.log.startsWith(prevLog),
                  s"Leader $id truncated its log!")
              case None => ()
            leaderLogs = leaderLogs + (id -> node.log)
          case _ =>
            leaderLogs = leaderLogs - id
      }

      updated
    }

    true
  }
```

## Property 4: State Machine Safety

```scala
property("State Machine Safety: no conflicting applies") =
  forAll(Gen.listOfN(100, RaftEventGen.randomEvent(0))) { events =>
    val cluster = events.foldLeft(initialCluster) { (cluster, event) =>
      applyEventToRandomNode(cluster, event)
    }

    val allApplied = cluster.nodes.values.map(_.appliedEntries).toList

    // For every index, all nodes that applied an entry at that index
    // applied the SAME entry
    val maxIdx = allApplied.map(_.size).maxOption.getOrElse(0)
    (0 until maxIdx).forall { i =>
      val entriesAtI = allApplied.flatMap(_.lift(i))
      entriesAtI.distinct.size <= 1
    }
  }
```

## Running the Properties

```scala
// In your test suite
class SafetyPropertySpec extends Properties("Raft Safety"):
  include(ElectionSafetyProps)
  include(LogMatchingProps)
  include(LeaderAppendOnlyProps)
  include(StateMachineSafetyProps)
```

```bash
sbt "testOnly *SafetyPropertySpec"
```

ScalaCheck will run each property 100 times by default (configurable via `minSuccessfulTests`). If a property fails, ScalaCheck **shrinks** the input to find the minimal failing case — typically reducing a 100-event sequence to 3-5 events that trigger the bug.

## Interpreting Failures

When a property-based test fails, ScalaCheck reports the **seed** and **shrunk input**:

```
! Raft Safety.Election Safety: Falsified after 42 tests
> ARG_0: List(ElectionTimeout, ElectionTimeout, VoteResponse(term=1, true))
> Seed: 1234567890
```

You can reproduce the exact failure:

```scala
property("...").check(Test.Parameters.default.withInitialSeed(1234567890))
```

Because the logic is pure, this reproduces deterministically. No flaky tests, no timing dependencies, no "it works on my machine."

> **Note — Coverage.** Property-based tests complement but don't replace scenario tests. They excel at finding unexpected edge cases but can miss scenarios that require specific multi-step setups (e.g., "leader commits entry, then partition, then new leader, then partition heals"). Use both approaches: property tests for invariants, scenario tests for specific operational sequences.

---

*Next: [Chapter 19 — Multi-Raft Group Design](19-multi-raft-groups.md) discusses how to scale beyond a single Raft group to handle larger datasets and higher throughput.*
