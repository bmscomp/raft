# Chapter 8: Getting Started

*This chapter takes you from zero to a working Raft election in under 50 lines of code. No networking, no threads, no server processes — just pure function calls. By the end, you'll have built a complete event loop and understand how to test any Raft scenario.*

---

## Setup

Add the library to your `build.sbt` (Scala `3.3.9` LTS, JDK 21+ for `raft-core` / `raft-runtime`, JDK 22+/25 for `raft-storage-mmap`):

```scala
val scala3Version = "3.3.9"

lazy val root = project
  .in(file("."))
  .settings(
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      // Zero-dependency pure consensus core (RaftLogic, ConsensusKernel, RawNode)
      "com.github.bmscomp" % "raft-core"           % "<version>",
      // Optional turnkey Cats Effect 3.7 + FS2 3.14 runtime, FFM WAL, and Netty gRPC
      "com.github.bmscomp" % "raft-runtime"        % "<version>",
      "com.github.bmscomp" % "raft-storage-mmap"   % "<version>",
      "com.github.bmscomp" % "raft-transport-grpc" % "<version>"
    )
  )
```

`raft-core` (`RaftLogic`, `ConsensusKernel`, `RawNode`, `Ready`, `ProgressTracker`, `NodeState`, `RaftMessage`, `Effect`, `Bytes`, `LogId`) has **zero external runtime dependencies** — pure Scala 3 standard library only. Add `raft-runtime` (`cats-effect` 3.7.1, `fs2-core` 3.14.0, `log4cats-core` 2.8.0) when you want the turnkey typed `RaftNode[F, R]`, `MultiRaftNode[F]`, `CompactionManager`, and SPI abstractions.

## Your First Election: A Step-by-Step Walkthrough

Let's elect a leader in a 3-node cluster using nothing but function calls. This is the "hello world" of Raft — and because the logic is pure, you can run this in a worksheet, REPL, or test class with no setup whatsoever.

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

// Disable pre-vote for this demo so we can see the raw election mechanics
val config = RaftConfig(localId = n1, preVoteEnabled = false)

// All three nodes start as Followers at term 0
var state1: NodeState = Follower(term = 0, votedFor = None, leaderId = None)
var state2: NodeState = Follower(term = 0, votedFor = None, leaderId = None)
var state3: NodeState = Follower(term = 0, votedFor = None, leaderId = None)
```

At this point, we have three nodes. None of them know about the others — there's no network, no discovery mechanism. The cluster topology is implied by the `clusterSize` parameter in the function calls below.

### Step 2: Fire the Election Timeout

In a real deployment, the election timer fires after a random delay. Here we simulate it by sending `ElectionTimeout` directly:

```scala
val election = RaftLogic.onMessage(state1, ElectionTimeout, config, 0, 0, 3)

state1 = election.state
// state1 → Candidate(term = 1, votesReceived = {node-1})
```

Node-1 transitions to `Candidate`, increments its term to 1, and votes for itself. Now look at the effects the function produced — these are instructions for the runtime:

```scala
election.effects.foreach(println)
// PersistHardState(1, Some(node-1))          ← persist the new term and self-vote
// Broadcast(RequestVoteRequest(term=1, ...)) ← ask all peers for their votes
// ResetElectionTimer                         ← restart the timer for split-vote retry
```

Three things happened, all expressed as data. No network calls were made. No disk was written to. The effects are just descriptions — plain Scala objects waiting to be executed.

### Step 3: Followers Receive the Vote Request

Extract the vote request from the effects, and feed it into node-2's state machine:

```scala
val voteReq = election.effects.collectFirst {
  case Broadcast(req: RequestVoteRequest) => req
}.get

val t2 = RaftLogic.onMessage(state2, voteReq, RaftConfig(n2), 0, 0, 3)
state2 = t2.state   // Follower(term = 1, votedFor = Some(node-1))

val vote2 = t2.effects.collectFirst {
  case SendMessage(_, r: RequestVoteResponse) => r
}.get
// vote2.voteGranted == true
```

Node-2 evaluates the vote request: "Is the candidate's term at least as large as mine? Is the candidate's log at least as up-to-date as mine? Have I already voted in this term?" All checks pass, so node-2 grants its vote.

Notice that node-2 is now a follower at term 1 with `votedFor = Some(node-1)`. Its hard state has changed, and the effects include `PersistHardState` — if this were a real system, the runtime would fsync this to disk before sending the vote response.

### Step 4: Win the Election

Feed the vote response back to node-1. Since we have a 3-node cluster and node-1 already has its own vote, this second vote gives it a majority (2/3):

```scala
val afterVote = RaftLogic.onVoteResponse(
  state1.asInstanceOf[Candidate], n2, vote2, config, 3
)

state1 = afterVote.state
// state1 → Leader(term = 1)

afterVote.effects.contains(BecomeLeader)
// true
```

That's it. **A complete Raft leader election in 15 lines of pure Scala.** No mocks, no network, no concurrency, fully deterministic. Every call is a pure function — same input, same output, every time.

> **Note — Why disable pre-vote for the demo?** With pre-vote enabled (the default), the election would take two rounds: a pre-vote phase (to check electability) followed by the real vote. The mechanics are identical — you just process two rounds of `RequestVote`/`RequestVoteResponse` instead of one. We disabled it here to keep the walkthrough concise. In production, always keep pre-vote enabled.

## The Event Loop: From Demo to Production

The step-by-step walkthrough above manually fed events and collected effects. A real application automates this with an **event loop** — a continuous cycle that receives events (from the network and timers), processes them through `RaftLogic.onMessage`, and executes the resulting effects:

```mermaid
flowchart LR
    subgraph Sources["Event Sources"]
        N[Network Messages]
        ET[Election Timer]
        HT[Heartbeat Timer]
    end

    M["FS2 merge\n(concurrent)"] --> P["RaftLogic.onMessage\n(sequential)"]
    N --> M
    ET --> M
    HT --> M

    P --> E["Effect Interpreter\n(pattern match)"]
    E --> Send["SendMessage"]
    E --> Persist["PersistHardState"]
    E --> Append["AppendLogs"]
    E --> Commit["CommitEntries"]
    E --> Timer["ResetTimer"]
```

```scala
import cats.effect.{Fiber, IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import raft.effect.Effect
import raft.effect.Effect.*
import raft.logic.RaftLogic
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.spi.*
import raft.state.*

def eventLoop(
  stateRef: Ref[IO, NodeState],
  electionFiberRef: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
  heartbeatFiberRef: Ref[IO, Option[Fiber[IO, Throwable, Unit]]],
  timerQueue: Queue[IO, RaftMessage],
  config: RaftConfig,
  transport: RaftTransport[IO],
  logStore: LogStore[IO],
  stableStore: StableStore[IO],
  timerService: TimerService[IO]
): IO[Unit] =
  // Merge peer RPCs with logical timer events into one serialized stream
  val events: Stream[IO, (Option[NodeId], RaftMessage)] =
    transport.receive
      .map { case (from, msg) => (Some(from), msg) }
      .merge(Stream.fromQueueUnterminated(timerQueue).map(msg => (None, msg)))

  val resetElection: IO[Unit] =
    for
      delay <- timerService.randomElectionTimeout(config.electionTimeoutMin)
      fiber <- timerService.scheduleElection(delay, timerQueue.offer(ElectionTimeout))
      prev  <- electionFiberRef.getAndSet(Some(fiber))
      _     <- prev.traverse_(_.cancel)
    yield ()

  val resetHeartbeat: IO[Unit] =
    for
      fiber <- timerService.scheduleHeartbeat(config.heartbeatInterval, timerQueue.offer(HeartbeatTimeout))
      prev  <- heartbeatFiberRef.getAndSet(Some(fiber))
      _     <- prev.traverse_(_.cancel)
    yield ()

  // Process each event sequentially through the pure consensus core
  events.evalMap { case (senderOpt, msg) =>
    for
      state    <- stateRef.get
      lastIdx  <- logStore.lastIndex
      lastTerm <- logStore.termAt(lastIdx).map(_.map(_.value).getOrElse(0L))
      transition = RaftLogic.onMessage(
        state        = state,
        message      = msg,
        config       = config,
        lastLogIndex = lastIdx.value,
        lastLogTerm  = lastTerm,
        clusterSize  = 3,
        sender       = senderOpt
      )
      _ <- stateRef.set(transition.state)
      _ <- executeEffects(
        transition.effects, transport, logStore, stableStore,
        resetElection, resetHeartbeat
      )
    yield ()
  }.compile.drain
```

The event sources are merged using FS2's `merge`, while `evalMap` serializes every transition on the local node (preventing concurrent state-machine mutations).

### The Effect Interpreter

The effect interpreter is a straightforward pattern match over the `Effect` ADT — preserving **atomic** hard-state persistence before outbound network sends:

```scala
def executeEffects(
  effects: List[Effect],
  transport: RaftTransport[IO],
  logStore: LogStore[IO],
  stableStore: StableStore[IO],
  resetElection: IO[Unit],
  resetHeartbeat: IO[Unit]
): IO[Unit] =
  effects.traverse_ {
    case PersistHardState(term, v) => stableStore.setHardState(Term.unsafeFrom(term), v)
    case AppendLogs(entries)       => logStore.append(entries)
    case TruncateLog(fromIdx)      => logStore.truncateFrom(LogIndex.unsafeFrom(fromIdx))
    case Broadcast(msg)            => transport.broadcast(msg)
    case SendMessage(to, msg)      => transport.send(to, msg)
    case CommitEntries(upTo)       => IO.println(s"Committed up to index ${upTo.value}")
    case ResetElectionTimer        => resetElection
    case ResetHeartbeatTimer       => resetHeartbeat
    case BecomeLeader              => IO.println("I am the leader!") *> resetHeartbeat
    case _                         => IO.unit
  }
```

> **Note — Choosing Your Abstraction Level:** Writing a custom interpreter teaches every moving part of Raft, while in production you rarely need to hand-roll one:
> - Use **`RawNode` + `Ready` / `LightReady`** (`raft-core`, [Chapter 22](22-sans-io-rawnode-ready.md)) when embedding Raft inside your own Virtual Thread, Netty, ZIO, or Pekko loop with zero external dependencies.
> - Use **`RaftNode[F, R]`** or **`MultiRaftNode[F]`** (`raft-runtime`, [Chapters 18 & 21](18-end-to-end-integration.md)) when you want a turnkey Cats Effect runtime with `proposeAndApply -> F[Applied[R]]`, lock-free network dispatch, group-commit batching, streaming compaction, and `MmapWalEngine` + `GrpcTransport`.

## Running All 18 Included Examples

The `modules/examples` project ships **18 runnable, test-verified examples** (`ExamplesVerificationSpec` + `AdvancedShardedLedgerE2ESpec`) covering every tier of the library. Run any example with `sbt "examples/runMain <class>"`:

```bash
# Tier 1: Pure RaftLogic protocol mechanics & single-phase cluster
sbt "examples/runMain examples.cluster.ThreeNodeClusterExample"
sbt "examples/runMain examples.protocol.PreVoteExample"
sbt "examples/runMain examples.protocol.LogMatchingExample"
sbt "examples/runMain examples.protocol.CommitTrackingExample"
sbt "examples/runMain examples.protocol.TimerServiceExample"

# Tier 2: Zero-dependency Sans-I/O RawNode, Virtual Threads, DST & Advanced Protocol
sbt "examples/runMain examples.rawnode.RawNodeHostLoopExample"
sbt "examples/runMain examples.simulation.DeterministicSimulationExample"
sbt "examples/runMain examples.protocol.ReadIndexAndLeaseExample"
sbt "examples/runMain examples.protocol.LeadershipTransferExample"
sbt "examples/runMain examples.cluster.JointConsensusExample"

# Tier 3: Application StateMachines, Custom Codecs & Multi-Raft
sbt "examples/runMain examples.kvstore.KVStoreExample"
sbt "examples/runMain examples.lock.DistributedLockExample"
sbt "examples/runMain examples.counter.CounterWithCodecExample"
sbt "examples/runMain examples.distributed.DistributedCounterExample"
sbt "examples/runMain examples.distributed.DistributedTransactionExample"
sbt "examples/runMain examples.distributed.RaftTransactionExample"
sbt "examples/runMain examples.distributed.MultiGroupExample"

# Tier 4: Flagship Full-Stack Production System (Multi-Raft + gRPC + FFM WAL + 2PC)
sbt "examples/runMain examples.advanced.ShardedBankLedgerExample"
```

See [Appendix B — Annotated Guide to All 18 Runnable Examples](appendix-b-examples-catalog.md) for a complete walkthrough and learning roadmap across every example.

## Testing Your Code

The pure design makes testing truly effortless. Here are two examples that demonstrate the pattern:

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
    trans.effects should contain (PersistHardState(1, Some(NodeId("n1"))))
  }

  "A Leader" should "step down when receiving a higher term" in {
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

  "CommitEntries" should "be emitted when commit index advances" in {
    // You can test specific protocol behaviors by constructing
    // precise states and messages — no cluster setup required
  }
```

Run the verification suites across all 7 modules:

```bash
sbt test                                          # full unit, property, DST, FFM WAL, gRPC, itest & examples suites
sbt "core/testOnly *DeterministicSimulationSpec"  # sub-second seeded 5-node DST chaos suite
sbt "core/testOnly *SafetyPropertySpec"           # ScalaCheck safety invariants
sbt jepsenSmoke                                   # Wing & Gong linearizability + partition nemesis suite
```

The test suite runs in seconds, not minutes, because there are no real network connections or timer waits. This is the payoff of the pure function architecture.

## Summary

| What You Built | What You Learned |
|---------------|-----------------|
| **Election walkthrough** | 15 lines of pure function calls elect a leader with no infrastructure |
| **Effect interpreter** | Your runtime executes effect descriptors — you control the "how" |
| **Event loop** | Merge `transport.receive` + timer streams into a single processing pipeline |
| **Tests** | Call functions, check outputs — no mocks, no waits, deterministic results |

---

*Next: [Chapter 9 — Log Replication in Practice](09-log-replication-practice.md) covers how data actually flows through the cluster — creating entries, handling conflicts, advancing the commit index, and tuning throughput with batching and pipelining.*
