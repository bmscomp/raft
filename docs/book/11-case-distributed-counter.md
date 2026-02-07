# Chapter 11: Case Study — Replicated Counter with Full Cluster Simulation

*This case study goes beyond a single state machine. We simulate a complete 3-node Raft cluster with in-memory networking, showing how election, replication, and commit work end-to-end.*

---

## The Problem

You need a globally consistent counter — one that multiple services increment concurrently, with the guarantee that no increments are lost and every reader sees the same value.

This is a deceptively hard problem. A naive counter on a single server loses increments when it crashes. A counter replicated via "last-write-wins" loses increments during concurrent updates. Only consensus-backed replication preserves every operation.

## Architecture

This example simulates the full Raft lifecycle:

```
┌─────────────────────────────────────────────────────────┐
│                    Test Harness                         │
│                                                         │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐           │
│  │  Node-1  │◄──►│  Node-2  │◄──►│  Node-3  │           │
│  │          │    │          │    │          │           │
│  │ RaftLogic│    │ RaftLogic│    │ RaftLogic│           │
│  │ Counter  │    │ Counter  │    │ Counter  │           │
│  └──────────┘    └──────────┘    └──────────┘           │
│       ▲               ▲               ▲                 │
│       └───────────────┴───────────────┘                 │
│              SimulatedNetwork (queues)                  │
└─────────────────────────────────────────────────────────┘
```

## Step 1: Define the Counter Commands

```scala
enum CounterCommand:
  case Increment
  case Decrement
  case Reset
  case Add(n: Int)

object CounterCommand:
  def encode(cmd: CounterCommand): Array[Byte] =
    (cmd match
      case Increment => "INC"
      case Decrement => "DEC"
      case Reset     => "RST"
      case Add(n)    => s"ADD:$n"
    ).getBytes

  def decode(data: Array[Byte]): CounterCommand =
    new String(data) match
      case "INC"                     => Increment
      case "DEC"                     => Decrement
      case "RST"                     => Reset
      case s if s.startsWith("ADD:") => Add(s.drop(4).toInt)
```

## Step 2: The Counter State Machine

```scala
class CounterStateMachine[F[_]: Sync](
  counter: Ref[F, Long],
  history: Ref[F, List[String]]
) extends StateMachine[F, Long]:

  def apply(log: Log): F[Long] =
    val cmd = CounterCommand.decode(log.data)
    for
      result <- cmd match
        case CounterCommand.Increment => counter.updateAndGet(_ + 1)
        case CounterCommand.Decrement => counter.updateAndGet(_ - 1)
        case CounterCommand.Reset     => counter.getAndSet(0).as(0L)
        case CounterCommand.Add(n)    => counter.updateAndGet(_ + n)
      _ <- history.update(_ :+ s"idx=${log.index}: $cmd → $result")
    yield result

  def getValue: F[Long] = counter.get
  def getHistory: F[List[String]] = history.get

  def snapshot: F[Array[Byte]] =
    counter.get.map(v => v.toString.getBytes)

  def restore(data: Array[Byte]): F[Unit] =
    counter.set(new String(data).toLong)
```

## Step 3: Simulated Network

Instead of real TCP, we use queues to simulate point-to-point messaging:

```scala
class SimulatedNetwork[F[_]: Async](
  queues: Map[NodeId, Queue[F, (NodeId, RaftMessage)]]
):
  def send(from: NodeId, to: NodeId, msg: RaftMessage): F[Unit] =
    queues.get(to).traverse_(_.offer((from, msg)))

  def broadcast(from: NodeId, msg: RaftMessage): F[Unit] =
    queues.toList.traverse_ { case (nodeId, q) =>
      if nodeId != from then q.offer((from, msg))
      else Async[F].unit
    }

  def receive(nodeId: NodeId): F[(NodeId, RaftMessage)] =
    queues(nodeId).take
```

## Step 4: The Simulated Node

Each node wraps `RaftLogic` with mutable state and an effect interpreter:

```scala
class SimulatedNode[F[_]: Async](
  val nodeId: NodeId,
  config: RaftConfig,
  network: SimulatedNetwork[F],
  stateMachine: CounterStateMachine[F],
  stateRef: Ref[F, NodeState],
  logRef: Ref[F, Vector[Log]],
  commitIndex: Ref[F, Long]
):
  private val clusterSize = 3

  def processMessage(from: NodeId, msg: RaftMessage): F[Unit] =
    for
      state <- stateRef.get
      logs  <- logRef.get

      lastIdx  = logs.lastOption.map(_.index).getOrElse(0L)
      lastTerm = logs.lastOption.map(_.term).getOrElse(0L)

      transition = RaftLogic.onMessage(
        state, msg, config, lastIdx, lastTerm, clusterSize,
        getTermAt = idx => logs.find(_.index == idx).map(_.term)
      )

      _ <- stateRef.set(transition.state)
      _ <- transition.effects.traverse_(executeEffect(from, _))
    yield ()

  private def executeEffect(from: NodeId, effect: Effect): F[Unit] =
    effect match
      case SendMessage(to, msg) =>
        network.send(nodeId, to, msg)
      case Broadcast(msg) =>
        network.broadcast(nodeId, msg)
      case AppendLogs(entries) =>
        logRef.update(_ ++ entries)
      case CommitEntries(upToIndex) =>
        for
          logs  <- logRef.get
          ci    <- commitIndex.get
          toApply = logs.filter(l => l.index > ci && l.index <= upToIndex)
          _ <- toApply.traverse_(stateMachine.apply)
          _ <- commitIndex.set(upToIndex)
        yield ()
      case _ => Async[F].unit
```

## Step 5: Running the Simulation

```scala
val simulation: IO[Unit] = for
  // Create the network
  network <- SimulatedNetwork[IO](List(n1, n2, n3))

  // Create nodes
  node1 <- createNode(n1, network)
  node2 <- createNode(n2, network)
  node3 <- createNode(n3, network)
  nodes = Map(n1 -> node1, n2 -> node2, n3 -> node3)

  // Step 1: Trigger election on node-1
  _ <- node1.processMessage(n1, ElectionTimeout)
  _ <- IO.println("Node-1 started election")

  // Step 2: Deliver vote requests and responses
  _ <- deliverPendingMessages(nodes, network)
  _ <- IO.println("Election complete")

  // Step 3: Submit counter commands to the leader
  _ <- node1.submitCommand(CounterCommand.Increment)
  _ <- node1.submitCommand(CounterCommand.Increment)
  _ <- node1.submitCommand(CounterCommand.Add(10))

  // Step 4: Replicate to followers
  _ <- deliverPendingMessages(nodes, network)

  // Step 5: Check all nodes have the same value
  v1 <- node1.getStateMachineValue
  v2 <- node2.getStateMachineValue
  v3 <- node3.getStateMachineValue
  _ <- IO.println(s"Node-1: $v1, Node-2: $v2, Node-3: $v3")
  // Node-1: 12, Node-2: 12, Node-3: 12
yield ()
```

## What This Demonstrates

| Phase | What Happens | Raft Mechanism |
|-------|-------------|---------------|
| Election | Node-1 becomes leader | `RequestVote` + majority |
| Command | Leader appends to local log | `Log.command(...)` |
| Replication | Leader sends `AppendEntries` to followers | `Broadcast` effect |
| Commitment | Majority replication → commit | `CommitEntries` effect |
| Application | All nodes apply same commands in same order | `StateMachine.apply` |
| Consistency | All counters show 12 | Replicated state machine |

## Fault Tolerance

The simulation can demonstrate failure scenarios:

```scala
// Simulate node-3 failure: stop processing its messages
// Node-1 and node-2 still form a majority (2/3)
// → Commands continue to commit

// Simulate network partition: node-1 isolated
// Node-2 and node-3 elect a new leader
// → New leader serves commands
// → Node-1 catches up when partition heals
```

## Run the Example

```bash
sbt "runMain examples.distributed.DistributedCounterExample"
```

---

*Next: [Chapter 12 — Case Study: Distributed Transactions](12-case-distributed-transactions.md) tackles multi-key atomic operations with conflict detection.*
