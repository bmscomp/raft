# Chapter 11: Case Study — Replicated Counter with Full Cluster Simulation

*The previous case studies focused on individual state machines. This one goes further: we simulate a complete 3-node Raft cluster with in-memory networking, showing how election, replication, and commit work end-to-end. The counter itself is simple — the value is in seeing the full system work together.*

---

## The Problem

You need a globally consistent counter — one that multiple services increment concurrently, with the guarantee that **no increments are lost** and **every reader sees the same value**.

This is a deceptively hard problem:

- **A naive counter on a single server** loses increments when it crashes (if not fsynced) and is unavailable during downtime.
- **A counter replicated via "last-write-wins"** loses increments during concurrent updates. Client A reads 5, Client B reads 5, both write 6 — one increment is silently eaten.
- **A counter using CRDTs** (conflict-free replicated data types, like a G-Counter) avoids conflicts but has weaker consistency — reads can return different values on different replicas, and there's no concept of "the current value."

Only consensus-backed replication guarantees that every `Increment` operation is applied exactly once, in a total order, on every replica, producing an identical counter value everywhere.

## Architecture

Unlike the previous case studies where we manually constructed log entries and passed them to a state machine, this example simulates the **full lifecycle** — election, log replication, commit, and state machine application — across three nodes communicating via simulated network queues:

```
┌────────────────────────────────────────────────────────────┐
│                      Test Harness                          │
│                                                            │
│  ┌──────────┐     ┌──────────┐     ┌──────────┐           │
│  │  Node-1  │◄───►│  Node-2  │◄───►│  Node-3  │           │
│  │          │     │          │     │          │           │
│  │ RaftLogic│     │ RaftLogic│     │ RaftLogic│           │
│  │ Counter  │     │ Counter  │     │ Counter  │           │
│  └──────────┘     └──────────┘     └──────────┘           │
│       ▲                ▲                ▲                  │
│       └────────────────┴────────────────┘                  │
│            SimulatedNetwork (bounded queues)               │
└────────────────────────────────────────────────────────────┘
```

Each node has its own instance of `RaftLogic`, its own log, its own state, and its own counter state machine. The simulated network delivers messages between nodes via in-memory queues. There are no real sockets, no real timers — the entire cluster runs deterministically in a single test.

## Step 1: Define the Counter Commands

The counter supports four operations, each encoded as a simple string:

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

The state machine maintains a counter and an operation history for observability:

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

The `history` field is not part of the replicated state — it's a debugging aid. Each node's history will contain the same entries (because the same commands are applied in the same order), making it easy to verify correctness.

## Step 3: Simulated Network

Instead of real TCP connections, we use Cats Effect bounded queues to simulate point-to-point messaging:

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

This simulated network has important properties:
- **Ordered delivery per sender-receiver pair** (FIFO queues)
- **No message loss** (queues don't drop messages, unlike real networks)
- **No latency** (messages are available immediately after sending)

These properties make the simulation deterministic and easy to reason about, but they're also more favorable than real networks. Production systems must handle message loss (Raft does this inherently) and reordering (Raft messages carry enough metadata to detect and handle this).

> **Note — Simulating failures:** You can extend the simulated network to model failures by adding message-dropping logic (e.g., drop messages with a configurable probability), delay queues, or network partition simulation (block messages between specific node pairs). This is how the library's `ChaosScenarioSpec` tests adversarial network conditions.

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

This is a fully functional Raft node — it processes messages, updates state, and executes effects. The only difference from a production node is the in-memory storage and simulated network. The `RaftLogic` calls are identical to what a production node would make.

## Step 5: Running the Simulation

```scala
val simulation: IO[Unit] = for
  // Create the simulated network
  network <- SimulatedNetwork[IO](List(n1, n2, n3))

  // Create three nodes
  node1 <- createNode(n1, network)
  node2 <- createNode(n2, network)
  node3 <- createNode(n3, network)
  nodes = Map(n1 -> node1, n2 -> node2, n3 -> node3)

  // Phase 1: Election
  _ <- node1.processMessage(n1, ElectionTimeout)
  _ <- IO.println("Node-1 started election")
  _ <- deliverPendingMessages(nodes, network)
  _ <- IO.println("Election complete — Node-1 is leader")

  // Phase 2: Submit counter commands
  _ <- node1.submitCommand(CounterCommand.Increment)    // counter: 0 → 1
  _ <- node1.submitCommand(CounterCommand.Increment)    // counter: 1 → 2
  _ <- node1.submitCommand(CounterCommand.Add(10))      // counter: 2 → 12

  // Phase 3: Replicate and commit
  _ <- deliverPendingMessages(nodes, network)

  // Phase 4: Verify all nodes agree
  v1 <- node1.getStateMachineValue
  v2 <- node2.getStateMachineValue
  v3 <- node3.getStateMachineValue
  _ <- IO.println(s"Node-1: $v1, Node-2: $v2, Node-3: $v3")
  // → Node-1: 12, Node-2: 12, Node-3: 12
yield ()
```

The result `12` is the same on all three nodes — proof that the replicated state machine works correctly. Each node applied `Increment`, `Increment`, `Add(10)` in the same order, producing the same final value.

## What This Demonstrates

| Phase | What Happened | Raft Mechanism Used |
|-------|--------------|-------------------|
| **Election** | Node-1 became leader after receiving majority votes | `RequestVote` RPCs + vote counting |
| **Command submission** | Leader created log entries from counter commands | `Log.command(...)` |
| **Replication** | Leader sent entries to followers via `AppendEntries` | `Broadcast` effect |
| **Commitment** | Majority replication → commit index advanced | `CommitEntries` effect |
| **Application** | All 3 nodes applied the same commands in the same order | `StateMachine.apply` |
| **Consistency** | All counters show `12` | Replicated state machine guarantee |

## Extending the Simulation: Fault Tolerance

The simulation can be extended to demonstrate failure scenarios:

```scala
// Simulate node-3 failure: stop processing its messages
// Nodes 1 and 2 still form a majority (2/3)
// → Commands continue to commit on the remaining two nodes

// Simulate network partition: node-1 isolated
// Nodes 2 and 3 detect the missing heartbeats
// → Node-2 or Node-3 triggers election and becomes new leader
// → New leader serves commands
// → When the partition heals, node-1 catches up via AppendEntries
```

You can also verify Raft's safety invariants:

```scala
// After any sequence of failures and recoveries:
// 1. All live nodes have the same committed entries
// 2. All live nodes' state machines show the same counter value
// 3. No committed entry is ever lost (even if a minority of nodes crash permanently)
```

## Run the Example

```bash
sbt "runMain examples.distributed.DistributedCounterExample"
```

---

*Next: [Chapter 12 — Case Study: Distributed Transactions](12-case-distributed-transactions.md) tackles the hardest problem yet — multi-key atomic operations with optimistic concurrency control and serializable isolation.*
