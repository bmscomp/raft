# Chapter 7: Getting Started

*This chapter takes you from zero to a working election in under 50 lines. No networking, no threads — just pure function calls.*

---

## Setup

Add the library to your `build.sbt`:

```scala
val scala3Version = "3.3.1"

lazy val root = project
  .in(file("."))
  .settings(
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.2",
      "co.fs2"        %% "fs2-core"    % "3.9.3"
    )
  )
```

The core protocol logic (`RaftLogic`, `NodeState`, `RaftMessage`, `Effect`) has **zero** runtime dependencies. Cats Effect and FS2 are only needed if you use the SPI implementations and `RaftNode` runtime.

## Your First Election

Let's elect a leader in a 3-node cluster using nothing but pure function calls.

### Step 1: Define the Cluster

```scala
import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.logic.RaftLogic
import raft.effect.Effect.*

val n1 = NodeId("node-1")
val n2 = NodeId("node-2")
val n3 = NodeId("node-3")

val config = RaftConfig(localId = n1, preVoteEnabled = false)

var state1: NodeState = Follower(term = 0, votedFor = None, leaderId = None)
var state2: NodeState = Follower(term = 0, votedFor = None, leaderId = None)
var state3: NodeState = Follower(term = 0, votedFor = None, leaderId = None)
```

### Step 2: Fire the Election Timeout

Node-1's election timer fires. It becomes a candidate:

```scala
val election = RaftLogic.onMessage(state1, ElectionTimeout, config, 0, 0, 3)

state1 = election.state
// state1 → Candidate(term = 1, votesReceived = {node-1})
```

The effects tell your runtime what to do:

```scala
election.effects.foreach(println)
// PersistHardState(1, Some(node-1))          ← save new term + vote
// Broadcast(RequestVoteRequest(term=1, ...)) ← ask others for votes
// ResetElectionTimer                         ← restart election timer
```

### Step 3: Followers Vote

Node-2 and Node-3 receive the vote request:

```scala
val voteReq = election.effects.collectFirst {
  case Broadcast(req: RequestVoteRequest) => req
}.get

val t2 = RaftLogic.onMessage(state2, voteReq, RaftConfig(n2), 0, 0, 3)
state2 = t2.state
val vote2 = t2.effects.collectFirst {
  case SendMessage(_, r: RequestVoteResponse) => r
}.get
// vote2.voteGranted == true
```

### Step 4: Win the Election

Node-1 receives the vote and becomes leader:

```scala
val afterVote = RaftLogic.onVoteResponse(
  state1.asInstanceOf[Candidate], n2, vote2, config, 3
)

state1 = afterVote.state
// state1 → Leader(term = 1)

afterVote.effects.contains(BecomeLeader)
// true
```

That's a complete Raft election in pure Scala. No mocks, no network, fully deterministic.

## The Effect Interpreter Loop

In a real application, you wrap this in a loop that receives events and executes effects:

```scala
import cats.effect.IO
import cats.syntax.all.*

def eventLoop(
  stateRef: Ref[IO, NodeState],
  config: RaftConfig,
  transport: RaftTransport[IO],
  logStore: LogStore[IO],
  stableStore: StableStore[IO],
  timerService: TimerService[IO]
): IO[Unit] =
  val events: Stream[IO, RaftMessage] =
    transport.receive.map(_._2)
      .merge(timerService.electionTimeouts.as(ElectionTimeout))
      .merge(timerService.heartbeatTicks.as(HeartbeatTimeout))

  events.evalMap { msg =>
    for
      state        <- stateRef.get
      lastIdx      <- logStore.lastIndex
      lastTerm     <- logStore.termAt(lastIdx).map(_.getOrElse(0L))
      clusterSize  =  3  // from your cluster config

      transition   =  RaftLogic.onMessage(state, msg, config, lastIdx, lastTerm, clusterSize)

      _            <- stateRef.set(transition.state)
      _            <- executeEffects(transition.effects, transport, logStore, stableStore, timerService)
    yield ()
  }.compile.drain
```

### Effect Interpreter

```scala
def executeEffects(
  effects: List[Effect],
  transport: RaftTransport[IO],
  logStore: LogStore[IO],
  stableStore: StableStore[IO],
  timerService: TimerService[IO]
): IO[Unit] =
  effects.traverse_ {
    case Broadcast(msg)            => transport.broadcast(msg)
    case SendMessage(to, msg)      => transport.send(to, msg)
    case PersistHardState(term, v) => stableStore.setCurrentTerm(term) *> stableStore.setVotedFor(v)
    case AppendLogs(entries)       => logStore.append(entries)
    case TruncateLog(fromIdx)      => logStore.truncateFrom(fromIdx)
    case CommitEntries(upTo)       => IO.println(s"Committed up to index $upTo")
    case ResetElectionTimer        => timerService.resetElectionTimer
    case ResetHeartbeatTimer       => timerService.resetHeartbeatTimer
    case BecomeLeader              => IO.println("I am the leader!")
    case _                         => IO.unit
  }
```

## Running the Examples

The library ships with 11 runnable examples that demonstrate everything from basic election to distributed transactions:

```bash
# Protocol fundamentals
sbt "runMain examples.protocol.PreVoteExample"
sbt "runMain examples.protocol.LogMatchingExample"
sbt "runMain examples.protocol.CommitTrackingExample"
sbt "runMain examples.protocol.TimerServiceExample"

# Cluster simulation
sbt "runMain examples.cluster.ThreeNodeClusterExample"

# Applications
sbt "runMain examples.kvstore.KVStoreExample"
sbt "runMain examples.lock.DistributedLockExample"
sbt "runMain examples.counter.CounterWithCodecExample"
sbt "runMain examples.distributed.DistributedCounterExample"
sbt "runMain examples.distributed.RaftTransactionExample"
sbt "runMain examples.distributed.DistributedTransactionExample"
```

## Testing Your Code

The pure design makes testing trivial:

```scala
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MyRaftSpec extends AnyFlatSpec with Matchers:

  "A Follower" should "become Candidate on election timeout" in {
    val follower = Follower(term = 0, votedFor = None, leaderId = None)
    val config = RaftConfig(NodeId("n1"), preVoteEnabled = false)

    val trans = RaftLogic.onMessage(follower, ElectionTimeout, config, 0, 0, 3)

    trans.state shouldBe a[Candidate]
    trans.state.term shouldBe 1
  }

  "A Leader" should "step down when receiving higher term" in {
    val leader = Leader(term = 5)
    val higherTermMsg = AppendEntriesRequest(
      term = 10, leaderId = NodeId("n2"),
      prevLogIndex = 0, prevLogTerm = 0,
      entries = Seq.empty, leaderCommit = 0
    )

    val trans = RaftLogic.onMessage(leader, higherTermMsg,
      RaftConfig(NodeId("n1")), 0, 0, 3)

    trans.state shouldBe a[Follower]
    trans.state.term shouldBe 10
  }
```

Run the full test suite:

```bash
sbt test                            # all tests
sbt "testOnly *ChaosScenarioSpec"   # adversarial scenarios
sbt "testOnly *SafetyPropertySpec"  # property-based invariants
```

## Summary

| Concept | What You Learned |
|---------|-----------------|
| **Pure elections** | 10 lines of code, no I/O |
| **Effect interpreter** | Your runtime executes descriptors |
| **Event loop** | Merge `transport.receive` + timer streams |
| **Testing** | Call functions, check results |

---

*Next: [Chapter 8 — Log Replication in Practice](08-log-replication-practice.md) covers how data actually flows through the cluster.*
