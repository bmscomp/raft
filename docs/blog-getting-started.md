# Building Distributed Systems with the Functional RAFT Library

*A practical guide to the Scala 3 RAFT consensus library — from first election to production-ready features.*

---

The **Functional RAFT Library** is a pure, effect-free implementation of the RAFT consensus algorithm in Scala 3. Unlike traditional RAFT implementations that couple protocol logic with I/O, this library separates state transitions from side effects, giving you full control over networking, storage, and timers.

This post walks through the library from the ground up, with runnable code examples at every step.

## Table of Contents

1. [Core Idea: Pure State Transitions](#core-idea-pure-state-transitions)
2. [Setup](#setup)
3. [Your First Election](#your-first-election)
4. [Understanding Effects](#understanding-effects)
5. [Log Replication](#log-replication)
6. [Building a Key-Value Store](#building-a-key-value-store)
7. [Pre-Vote Protocol](#pre-vote-protocol)
8. [Advanced Features](#advanced-features)
9. [The SPI Layer](#the-spi-layer)
10. [Testing](#testing)

---

## Core Idea: Pure State Transitions

Traditional RAFT libraries do everything: they manage sockets, timers, and disk I/O inside the consensus engine. This library takes a fundamentally different approach.

Every protocol event — receiving a message, an election timeout, a heartbeat tick — is processed by a **pure function** that returns the new state and a list of **effects** to execute:

```scala
val transition = RaftLogic.onMessage(state, message, config, lastLogIndex, lastLogTerm, clusterSize)
// transition.state   → the new node state (Follower, Candidate, Leader, ...)
// transition.effects → what the runtime should do next (send messages, persist state, ...)
```

The library never opens a socket, writes to disk, or starts a timer. **You** decide how to execute the effects. This makes the core logic trivially testable, portable, and composable with any effect system.

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

The core protocol logic (`RaftLogic`, `NodeState`, `RaftMessage`, `Effect`) has zero runtime dependencies — Cats Effect and FS2 are only needed for the SPI implementations and the `RaftNode` runtime.

---

## Your First Election

Let's simulate a 3-node cluster electing a leader, using only pure function calls. No I/O, no threads, no timers.

```scala
import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.logic.RaftLogic
import raft.effect.Effect.*

// Three nodes, all starting as Followers
val n1 = NodeId("node-1")
val n2 = NodeId("node-2")
val n3 = NodeId("node-3")

val config = RaftConfig(localId = n1, preVoteEnabled = false)

var state1: NodeState = Follower(term = 0, votedFor = None, leaderId = None)
var state2: NodeState = Follower(term = 0, votedFor = None, leaderId = None)
var state3: NodeState = Follower(term = 0, votedFor = None, leaderId = None)
```

### Step 1: Election Timeout

Node-1's election timer fires. The pure logic bumps its term and transitions to `Candidate`:

```scala
val election = RaftLogic.onMessage(state1, ElectionTimeout, config, 0, 0, 3)

state1 = election.state
// state1 is now Candidate(term = 1, votesReceived = {node-1})

val voteRequest = election.effects.collectFirst {
  case Broadcast(req: RequestVoteRequest) => req
}.get
// RequestVoteRequest(term=1, candidateId=node-1, lastLogIndex=0, lastLogTerm=0)
```

### Step 2: Followers Vote

The other nodes receive the vote request and respond:

```scala
val t2 = RaftLogic.onMessage(state2, voteRequest, RaftConfig(n2), 0, 0, 3)
state2 = t2.state
val vote2 = t2.effects.collectFirst {
  case SendMessage(_, r: RequestVoteResponse) => r
}.get
// vote2.voteGranted == true

val t3 = RaftLogic.onMessage(state3, voteRequest, RaftConfig(n3), 0, 0, 3)
state3 = t3.state
val vote3 = t3.effects.collectFirst {
  case SendMessage(_, r: RequestVoteResponse) => r
}.get
// vote3.voteGranted == true
```

### Step 3: Candidate Becomes Leader

Node-1 collects the votes. With 2 votes + self = 3/3, it reaches majority:

```scala
val candidate = state1.asInstanceOf[Candidate]

val afterVote2 = RaftLogic.onVoteResponse(candidate, n2, vote2, config, 3)
// afterVote2.state is still Candidate (2/3, needs 2 for majority of 3)
// Actually 2 > 3/2 = 1, so this already reaches majority!

state1 = afterVote2.state
// state1 is now Leader(term = 1)

afterVote2.effects.contains(BecomeLeader)
// true — the runtime should initialize leader indices, start heartbeats, etc.
```

That is a complete RAFT election in **10 lines of pure Scala**. No network, no threads, fully deterministic.

---

## Understanding Effects

Every call to `RaftLogic.onMessage` or `RaftLogic.onVoteResponse` returns a `Transition` containing a list of `Effect` values. These are descriptors of work to be done — the library never executes them.

Here are the most common effects:

| Effect | When It's Produced | What Your Runtime Does |
|--------|-------------------|----------------------|
| `Broadcast(msg)` | Election start, heartbeat | Send `msg` to all peers |
| `SendMessage(to, msg)` | Vote response, append response | Send `msg` to a specific peer |
| `PersistHardState(term, votedFor)` | Term change, vote cast | Write to durable storage |
| `AppendLogs(entries)` | Follower accepts entries | Append to log store |
| `TruncateLog(fromIndex)` | Log conflict detected | Remove entries from index onward |
| `CommitEntries(upToIndex)` | Commit index advanced | Apply committed entries to FSM |
| `BecomeLeader` | Election won | Initialize leader state |
| `ResetElectionTimer` | Heartbeat received, vote cast | Restart the election timeout |
| `ResetHeartbeatTimer` | Became leader | Start periodic heartbeats |

A typical effect interpreter looks like:

```scala
import cats.effect.IO

def executeEffects(effects: List[Effect], transport: RaftTransport[IO]): IO[Unit] =
  effects.traverse_ {
    case Broadcast(msg)            => transport.broadcast(msg)
    case SendMessage(to, msg)      => transport.send(to, msg)
    case PersistHardState(term, v) => stableStore.setCurrentTerm(term) *> stableStore.setVotedFor(v)
    case AppendLogs(entries)       => logStore.append(entries)
    case CommitEntries(upTo)       => applyCommittedEntries(upTo)
    case ResetElectionTimer        => timerService.resetElectionTimer
    case ResetHeartbeatTimer       => timerService.resetHeartbeatTimer
    case _                         => IO.unit
  }
```

---

## Log Replication

Once a leader is elected, it replicates entries to followers via `AppendEntriesRequest`.

### Creating Log Entries

```scala
import raft.state.Log

// Create command entries
val entry1 = Log.command(index = 1, term = 1, data = "SET x=1".getBytes)
val entry2 = Log.command(index = 2, term = 1, data = "SET y=2".getBytes)

// Create a no-op entry (leaders append one on election)
val noop = Log.noOp(index = 1, term = 1)
```

### Sending AppendEntries

```scala
val appendReq = AppendEntriesRequest(
  term         = 1,
  leaderId     = n1,
  prevLogIndex = 0,
  prevLogTerm  = 0,
  entries      = Seq(entry1, entry2),
  leaderCommit = 0
)

// Follower processes the request
val followerTrans = RaftLogic.onMessage(followerState, appendReq, followerConfig, 0, 0, 3)

// Check the response
val response = followerTrans.effects.collectFirst {
  case SendMessage(_, r: AppendEntriesResponse) => r
}.get

response.success    // true — entries accepted
response.matchIndex // 2   — highest replicated index
```

### Commit Advancement

The leader tracks `matchIndex` for each follower. When a majority has replicated an entry, it's committed:

```scala
val leader: Leader = Leader(
  term       = 1,
  matchIndex = Map(n2 -> 2L, n3 -> 2L),
  commitIndex = 0
)

// Calculate new commit index (requires majority at current term)
val getTermAt: Long => Option[Long] = {
  case 2 => Some(1L) // entry at index 2 was written in term 1
  case _ => None
}

val newCommit = leader.calculateCommitIndex(clusterSize = 3, getTermAt)
// newCommit == 2 (both followers have replicated index 2)
```

---

## Building a Key-Value Store

Here's a practical example: a replicated key-value store using the `StateMachine` SPI.

### Define Your Commands

```scala
enum KVCommand:
  case Put(key: String, value: String)
  case Delete(key: String)
  case Get(key: String)

object KVCommand:
  def encode(cmd: KVCommand): Array[Byte] = cmd match
    case Put(k, v) => s"PUT\t$k\t$v".getBytes
    case Delete(k) => s"DEL\t$k".getBytes
    case Get(k)    => s"GET\t$k".getBytes

  def decode(data: Array[Byte]): Either[String, KVCommand] =
    new String(data).split("\t").toList match
      case "PUT" :: k :: v :: Nil => Right(Put(k, v))
      case "DEL" :: k :: Nil      => Right(Delete(k))
      case "GET" :: k :: Nil      => Right(Get(k))
      case other                  => Left(s"Invalid: $other")
```

### Implement the State Machine

```scala
import cats.effect.*
import raft.spi.StateMachine
import raft.state.Log

class KVStateMachine[F[_]: Sync](
  store: Ref[F, Map[String, String]]
) extends StateMachine[F, String]:

  def apply(log: Log): F[String] =
    KVCommand.decode(log.data) match
      case Right(KVCommand.Put(k, v)) =>
        store.update(_.updated(k, v)).as("OK")
      case Right(KVCommand.Delete(k)) =>
        store.update(_ - k).as("OK")
      case Right(KVCommand.Get(k)) =>
        store.get.map(m => m.getOrElse(k, "(not found)"))
      case Left(err) =>
        Sync[F].pure(s"ERROR: $err")

  def snapshot: F[Array[Byte]] =
    store.get.map { m =>
      m.map { case (k, v) => s"$k=$v" }.mkString("\n").getBytes
    }

  def restore(data: Array[Byte]): F[Unit] =
    val entries = new String(data).split("\n").flatMap { line =>
      line.split("=", 2) match
        case Array(k, v) => Some(k -> v)
        case _           => None
    }.toMap
    store.set(entries)
```

### Wire It Together

```scala
import cats.effect.IO

val program: IO[Unit] = for
  fsm <- Ref.of[IO, Map[String, String]](Map.empty).map(new KVStateMachine[IO](_))

  // Simulate committed log entries
  entries = List(
    Log.command(1, 1, KVCommand.encode(KVCommand.Put("user:1", "Alice"))),
    Log.command(2, 1, KVCommand.encode(KVCommand.Put("user:2", "Bob"))),
    Log.command(3, 1, KVCommand.encode(KVCommand.Delete("user:2")))
  )

  // Apply each committed entry in order
  _ <- entries.traverse(entry => fsm.apply(entry).flatMap(r => IO.println(s"idx=${entry.index}: $r")))
  // idx=1: OK
  // idx=2: OK
  // idx=3: OK

  // Query the state
  _ <- fsm.apply(Log.command(4, 1, KVCommand.encode(KVCommand.Get("user:1"))))
       .flatMap(r => IO.println(s"user:1 = $r"))
  // user:1 = Alice
yield ()
```

Run it:

```bash
sbt "runMain examples.kvstore.KVStoreExample"
```

---

## Pre-Vote Protocol

The Pre-Vote protocol (§9.6 of Ongaro's thesis) prevents partitioned nodes from disrupting stable clusters by inflating their term on every election timeout.

### How It Works

Without pre-vote, a partitioned node keeps incrementing its term. When it rejoins, its high term forces everyone to step down, disrupting the cluster.

With pre-vote enabled (the default), a node first sends a **pre-vote request** without incrementing its term. Only if a majority grants the pre-vote does it start a real election:

```
Standard:  Follower → Candidate(term+1) → may force cluster step-down
Pre-Vote:  Follower → PreCandidate(same term) → if majority → Candidate(term+1)
```

### Example

```scala
val config = RaftConfig(localId = n1, preVoteEnabled = true) // enabled by default

val follower = Follower(term = 5, votedFor = None, leaderId = None)
val trans = RaftLogic.onMessage(follower, ElectionTimeout, config, 10, 5, 5)

// Becomes PreCandidate, NOT Candidate
trans.state match
  case PreCandidate(term, preVotesReceived) =>
    println(s"Term unchanged: $term")       // 5  (not incremented!)
    println(s"Pre-votes: $preVotesReceived") // {node-1} (self)

// The broadcast request has isPreVote = true
val preVoteReq = trans.effects.collectFirst {
  case Broadcast(req: RequestVoteRequest) => req
}.get
preVoteReq.isPreVote // true
preVoteReq.term      // 6 (requests vote for next term, but doesn't commit to it)
```

**Key invariant**: No `PersistHardState` effects are produced during pre-vote. The term is only incremented and persisted when the real election starts.

---

## Advanced Features

### Linearizable Reads (ReadIndex)

Strong consistency reads without going through the log. The leader confirms it's still the leader by getting a heartbeat quorum, then returns the current commit index:

```scala
val readTrans = RaftLogic.handleReadIndex(leaderState, config, clusterSize)

readTrans.effects.collectFirst {
  case ConfirmLeadership(commitIndex) =>
    // Runtime: broadcast heartbeat, wait for majority ack
    // Then serve the read at this commitIndex
    commitIndex
}
```

### Lease-Based Reads

Even faster reads when the leader holds a valid lease (no heartbeat round-trip needed):

```scala
val config = RaftConfig(
  localId = n1,
  leaderLeaseDuration = 100.millis
)

val leaseTrans = RaftLogic.handleLeaseRead(leaderState, config)

leaseTrans.effects.collectFirst {
  case LeaseReadReady(commitIndex) =>
    // Lease is valid — serve read immediately
    commitIndex
}
```

### Leadership Transfer

Gracefully hand off leadership to a specific node:

```scala
val transferTrans = RaftLogic.handleTransferLeadership(leaderState, targetNode, config)

transferTrans.effects.collectFirst {
  case TimeoutNow(target) =>
    // Runtime: send TimeoutNow to the target, causing it to start an immediate election
    target
}
```

### Joint Consensus

Safe cluster membership changes using dual-quorum. The cluster transitions through a joint configuration where both the old and new member sets must agree:

```scala
import raft.state.ClusterConfig

val oldConfig = ClusterConfig(voters = Set(n1, n2, n3))
val newConfig = ClusterConfig(voters = Set(n1, n2, n4)) // replacing n3 with n4

// Create a joint configuration entry
val jointConfig = ClusterConfig.joint(oldConfig, newConfig)
// Both {n1,n2,n3} AND {n1,n2,n4} must form a quorum during the transition
```

---

## The SPI Layer

The library defines four Service Provider Interfaces that you implement for your infrastructure:

### Transport

Handles network communication between nodes:

```scala
trait RaftTransport[F[_]]:
  def send(to: NodeId, msg: RaftMessage): F[Unit]
  def broadcast(msg: RaftMessage): F[Unit]
  def receive: Stream[F, (NodeId, RaftMessage)]
  def localAddress: F[String]
  def shutdown: F[Unit]
```

For testing, use the built-in `InMemTransport`:

```scala
import raft.impl.InMemTransport

val cluster: IO[Map[NodeId, InMemTransport[IO]]] =
  InMemTransport.createCluster[IO](List(n1, n2, n3))
```

### LogStore

Persistent log storage:

```scala
trait LogStore[F[_]] extends LogReader[F] with LogWriter[F]

// LogReader: get, getRange, lastIndex, termAt, isEmpty
// LogWriter: append, truncateFrom, clear
```

For testing:

```scala
import raft.impl.InMemLogStore

val store: IO[InMemLogStore[IO]] = InMemLogStore[IO]()
```

### StableStore

Durable storage for the hard state (current term and voted-for):

```scala
trait StableStore[F[_]]:
  def currentTerm: F[Term]
  def setCurrentTerm(term: Term): F[Unit]
  def votedFor: F[Option[NodeId]]
  def setVotedFor(nodeId: Option[NodeId]): F[Unit]
```

For testing:

```scala
import raft.impl.InMemStableStore

val stable: IO[InMemStableStore[IO]] = InMemStableStore[IO]()
```

### StateMachine

Your application logic — what happens when committed entries are applied:

```scala
trait StateMachine[F[_], R]:
  def apply(log: Log): F[R]
  def snapshot: F[Array[Byte]]
  def restore(data: Array[Byte]): F[Unit]
```

---

## Configuration

`RaftConfig` provides sensible defaults with full control when you need it:

```scala
val config = RaftConfig(
  localId                   = NodeId("node-1"),
  electionTimeoutMin        = 150.millis,   // RAFT paper recommendation
  electionTimeoutMax        = 300.millis,
  heartbeatInterval         = 50.millis,    // must be << election timeout
  maxEntriesPerRequest      = 100,
  preVoteEnabled            = true,         // prevents term inflation
  leaderStickinessEnabled   = true,         // rejects votes while leader is alive
  leaderLeaseDuration       = 100.millis,   // for lease-based reads
  parallelReplicationEnabled = true,        // replicate to followers concurrently
  batching = BatchConfig(
    enabled = true,
    maxSize = 100,
    maxWait = 10.millis
  ),
  pipelining = PipelineConfig(
    enabled = true,
    maxInflight = 10
  )
)
```

Or use the defaults:

```scala
val config = RaftConfig.default(NodeId("node-1"))
```

---

## Testing

The pure design makes testing straightforward. No mocking frameworks needed — just call functions and check the result.

### Unit Testing State Transitions

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

    val trans = RaftLogic.onMessage(leader, higherTermMsg, config, 0, 0, 3)

    trans.state shouldBe a[Follower]
    trans.state.term shouldBe 10
  }
```

### Property-Based Testing

Use ScalaCheck to verify safety invariants across randomized inputs:

```scala
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

class SafetySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  "Term" should "never decrease after any message" in {
    val termGen = Gen.choose(1L, 100L)

    forAll(Gen.listOfN(10, termGen)) { terms =>
      var state: NodeState = Follower(0, None, None)
      for msgTerm <- terms do
        val msg = AppendEntriesRequest(msgTerm, NodeId("leader"), 0, 0, Seq.empty, 0)
        val oldTerm = state.term
        val trans = RaftLogic.onMessage(state, msg, config, 0, 0, 3)
        trans.state.term should be >= oldTerm
        state = trans.state
    }
  }
```

### Integration Testing with In-Memory Infrastructure

```scala
import raft.impl.*

class IntegrationSpec extends AnyFlatSpec with Matchers:

  "InMemTransport" should "deliver messages between peers" in {
    val test = for
      cluster <- InMemTransport.createCluster[IO](List(NodeId("a"), NodeId("b")))
      _       <- cluster(NodeId("a")).send(NodeId("b"), HeartbeatTimeout)
      (from, msg) <- cluster(NodeId("b")).inbox.take
    yield (from, msg)

    val (from, msg) = test.unsafeRunSync()
    from shouldBe NodeId("a")
    msg shouldBe HeartbeatTimeout
  }
```

Run the full test suite:

```bash
sbt test          # all 326 tests
sbt "testOnly *ChaosScenarioSpec"    # adversarial scenarios
sbt "testOnly *SafetyPropertySpec"   # property-based invariants
```

---

## Running the Examples

The library ships with 11 runnable examples:

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

---

## What's Next?

The library provides a solid foundation for building distributed systems. Here are some directions to explore:

- **Implement a real `Transport`** using gRPC, Netty, or HTTP/2
- **Implement a durable `LogStore`** using RocksDB, SQLite, or append-only files
- **Add your own `StateMachine`** for configuration management, distributed queues, or coordination
- **Enable advanced features** like pipelining and batching for high-throughput workloads
- **Use lease-based reads** for low-latency read paths in read-heavy applications

The pure state machine design means you can test every invariant without deploying a real cluster. Build confidence in your implementation before you ever open a socket.

---

*Apache 2.0 Licensed — Contributions welcome.*
