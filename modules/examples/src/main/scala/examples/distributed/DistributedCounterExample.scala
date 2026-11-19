package examples.distributed

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import scala.concurrent.duration.*

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.logic.*
import raft.effect.Effect.*
import raft.spi.StateMachine

/** Distributed computing with RAFT: a complete example.
  *
  * This example demonstrates how to build a distributed system using the RAFT
  * consensus library. We implement a replicated counter that maintains
  * consistency across multiple nodes, tolerating failures.
  *
  * The architecture layers from top to bottom:
  *   - '''Your Application''' (Counter, KV Store, Lock Service, etc.)
  *   - '''RAFT Library''' (`RaftNode`, `RaftLogic`, `TimerService`)
  *   - '''SPI implementations''' (`Transport`, `LogStore`, `StableStore`,
  *     `StateMachine`)
  *
  * The replication flow:
  *   1. CLIENT submits a command to the LEADER node
  *   1. LEADER appends command to its log and replicates to FOLLOWERS
  *   1. Once MAJORITY acknowledges, LEADER advances commitIndex
  *   1. LEADER responds to CLIENT with result
  *   1. FOLLOWERS learn of commit via `leaderCommit` in heartbeats
  *
  * Fault tolerance guarantees:
  *   - ''Leader failure'': `TimerService` triggers election timeout, new leader
  *     elected
  *   - ''Follower failure'': Leader continues with remaining majority
  *   - ''Network partition'': Only partition with majority can make progress
  *   - ''Split brain'': Term comparison ensures at most one leader per term
  *
  * This example simulates a 3-node cluster with in-memory networking,
  * demonstrating leader election, command replication, state machine
  * application, and commit index advancement.
  *
  * @see
  *   [[raft.logic.RaftLogic]] for the pure consensus logic
  * @see
  *   [[raft.spi.StateMachine]] for the state machine SPI
  */
object DistributedCounterExample extends IOApp.Simple:

  /** Commands that can be applied to the distributed counter.
    *
    * The `StateMachine` is your application logic. RAFT ensures all nodes apply
    * the same commands in the same order, achieving consistency.
    */
  enum CounterCommand:
    case Increment
    case Decrement
    case Add(value: Int)
    case Reset

  object CounterCommand:
    def encode(cmd: CounterCommand): Array[Byte] = cmd match
      case Increment => "INC".getBytes
      case Decrement => "DEC".getBytes
      case Add(v)    => s"ADD:$v".getBytes
      case Reset     => "RST".getBytes

    def decode(data: Array[Byte]): CounterCommand =
      new String(data) match
        case "INC"                     => Increment
        case "DEC"                     => Decrement
        case "RST"                     => Reset
        case s if s.startsWith("ADD:") => Add(s.drop(4).toInt)
        case other => throw new Exception(s"Unknown command: $other")

  /** Replicated counter state machine.
    *
    * Every node maintains its own copy, but RAFT ensures they all apply
    * commands in identical order. Implements [[raft.spi.StateMachine]] with
    * `Long` as the result type, returning the current counter value after each
    * operation.
    *
    * @param counter
    *   atomic reference holding the current counter value
    * @param appliedLog
    *   atomic reference tracking applied commands for demonstration
    */
  class CounterStateMachine[F[_]: Sync](
      counter: Ref[F, Long],
      appliedLog: Ref[F, List[String]] // For demonstration
  ) extends StateMachine[F, Long]:

    def apply(log: Log): F[Long] =
      val cmd = CounterCommand.decode(log.data)
      for
        _ <- appliedLog.update(_ :+ s"index=${log.index}: $cmd")
        result <- cmd match
          case CounterCommand.Increment => counter.updateAndGet(_ + 1)
          case CounterCommand.Decrement => counter.updateAndGet(_ - 1)
          case CounterCommand.Add(v)    => counter.updateAndGet(_ + v)
          case CounterCommand.Reset     => counter.set(0) *> Sync[F].pure(0L)
      yield result

    def snapshot: F[Stream[F, Byte]] =
      counter.get.map(v => Stream.emits(v.toString.getBytes).covary[F])

    def restore(data: Stream[F, Byte]): F[Unit] =
      data.compile.to(Array).flatMap { bytes =>
        counter.set(new String(bytes).toLong)
      }

    def getValue: F[Long] = counter.get
    def getAppliedLog: F[List[String]] = appliedLog.get

  object CounterStateMachine:
    def apply[F[_]: Sync]: F[CounterStateMachine[F]] =
      for
        counter <- Ref.of[F, Long](0L)
        log <- Ref.of[F, List[String]](Nil)
      yield new CounterStateMachine[F](counter, log)

  /** In-memory message bus simulating a network between nodes.
    *
    * In production you would implement the [[raft.spi.Transport]] SPI with real
    * networking (gRPC, HTTP, TCP, etc.). This simulation uses a per-node
    * `Queue` so that messages can be sent and received asynchronously.
    *
    * @param queues
    *   map from `NodeId` to the corresponding incoming message queue
    */
  class SimulatedNetwork[F[_]: Async](
      queues: Map[NodeId, Queue[F, (NodeId, RaftMessage)]]
  ):
    def send(from: NodeId, to: NodeId, msg: RaftMessage): F[Unit] =
      queues.get(to) match
        case Some(q) => q.offer((from, msg))
        case None    => Async[F].unit // Node not reachable

    def broadcast(from: NodeId, msg: RaftMessage): F[Unit] =
      queues.toList.traverse_ { case (nodeId, q) =>
        if nodeId != from then q.offer((from, msg))
        else Async[F].unit
      }

    def receive(nodeId: NodeId): F[(NodeId, RaftMessage)] =
      queues(nodeId).take

    def tryReceive(nodeId: NodeId): F[Option[(NodeId, RaftMessage)]] =
      queues(nodeId).tryTake

  object SimulatedNetwork:
    def apply[F[_]: Async](nodeIds: List[NodeId]): F[SimulatedNetwork[F]] =
      nodeIds
        .traverse(id => Queue.unbounded[F, (NodeId, RaftMessage)].map(id -> _))
        .map(_.toMap)
        .map(new SimulatedNetwork[F](_))

  /** Simplified RAFT node that demonstrates the core event loop.
    *
    * In production use the full [[raft.RaftNode]] implementation. This class
    * wires together `RaftLogic` state transitions with effect execution,
    * showing how the pure consensus core drives side effects.
    *
    * @param nodeId
    *   unique identifier for this node
    * @param config
    *   RAFT configuration (timeouts, etc.)
    * @param network
    *   simulated transport layer
    * @param stateMachine
    *   application state machine for command execution
    * @param stateRef
    *   mutable reference to the current `NodeState`
    * @param logRef
    *   mutable reference to the replicated log
    * @param commitIndex
    *   mutable reference to the highest committed index
    */
  class SimulatedNode[F[_]: Async](
      val nodeId: NodeId,
      config: RaftConfig,
      cluster: ClusterConfig,
      network: SimulatedNetwork[F],
      stateMachine: CounterStateMachine[F],
      stateRef: Ref[F, NodeState],
      logRef: Ref[F, Vector[Log]],
      commitIndex: Ref[F, Long]
  ):
    private val clusterSize = cluster.voters.size

    def getState: F[NodeState] = stateRef.get
    def getLog: F[Vector[Log]] = logRef.get
    def getCommitIndex: F[Long] = commitIndex.get
    def getStateMachineValue: F[Long] = stateMachine.getValue
    def getAppliedLog: F[List[String]] = stateMachine.getAppliedLog

    /** Process a single message and execute resulting effects. */
    def processMessage(from: NodeId, msg: RaftMessage): F[Unit] =
      for
        state <- stateRef.get
        logs <- logRef.get

        // Get log info for RaftLogic
        lastLogIndex = if logs.isEmpty then 0L else logs.last.index
        lastLogTerm = if logs.isEmpty then 0L else logs.last.term
        getTermAt = (idx: Long) => logs.find(_.index == idx).map(_.term)

        // Pure state transition
        transition = RaftLogic.onMessage(
          state,
          msg,
          config,
          lastLogIndex,
          lastLogTerm,
          clusterSize,
          getTermAt,
          sender = Some(from),
          clusterOpt = Some(cluster)
        )

        // Apply new state
        _ <- stateRef.set(transition.state)

        // Execute effects
        _ <- transition.effects.traverse_(executeEffect)
      yield ()

    private def executeEffect(
        effect: raft.effect.Effect
    ): F[Unit] =
      effect match
        case SendMessage(to, msg) =>
          network.send(nodeId, to, msg)

        case Broadcast(msg) =>
          network.broadcast(nodeId, msg)

        case AppendLogs(entries) =>
          logRef.update(_ ++ entries)

        case TruncateLog(fromIndex) =>
          logRef.update(_.filter(_.index < fromIndex))

        case BecomeLeader =>
          Async[F].delay(println(s"    🎉 ${nodeId.value} became LEADER!"))

        case CommitEntries(upToIndex) =>
          for
            logs <- logRef.get
            currentCommit <- commitIndex.get
            targetCommit = math.max(currentCommit, upToIndex.value)
            toApply = logs.filter(l =>
              l.index > currentCommit && l.index <= targetCommit
            )
            _ <- toApply.traverse_(log => stateMachine.apply(log))
            _ <- commitIndex.set(targetCommit)
          yield ()

        case _ => Async[F].unit // Simplified - ignore timer/metrics effects

    /** Submit a command (only works if this node is the leader). */
    def submitCommand(cmd: CounterCommand): F[Boolean] =
      for
        state <- stateRef.get
        result <- state match
          case l: Leader =>
            for
              logs <- logRef.get
              prevIdx = logs.lastOption.map(_.index).getOrElse(0L)
              prevTerm = logs.lastOption.map(_.term).getOrElse(0L)
              newIndex = prevIdx + 1L
              newLog = Log.command(newIndex, l.term, CounterCommand.encode(cmd))
              _ <- logRef.update(_ :+ newLog)
              _ <- Async[F].delay(
                println(s"    📝 Leader appended: $cmd at index $newIndex")
              )
              appendReq = AppendEntriesRequest(
                term = l.term,
                leaderId = nodeId,
                prevLogIndex = prevIdx,
                prevLogTerm = prevTerm,
                entries = Seq(newLog),
                leaderCommit = l.commitIndex
              )
              _ <- network.broadcast(nodeId, appendReq)
            yield true
          case _ =>
            Async[F].pure(false) // Not leader
      yield result

  object SimulatedNode:
    def apply[F[_]: Async](
        nodeId: NodeId,
        cluster: ClusterConfig,
        network: SimulatedNetwork[F]
    ): F[SimulatedNode[F]] =
      for
        fsm <- CounterStateMachine[F]
        state <- Ref.of[F, NodeState](Follower(0, None, None))
        log <- Ref.of[F, Vector[Log]](Vector.empty)
        commit <- Ref.of[F, Long](0L)
        config = RaftConfig(
          nodeId,
          electionTimeoutMin = 150.millis,
          electionTimeoutMax = 300.millis,
          heartbeatInterval = 50.millis
        )
      yield new SimulatedNode[F](
        nodeId,
        config,
        cluster,
        network,
        fsm,
        state,
        log,
        commit
      )

  def run: IO[Unit] =
    for
      _ <- IO.println("═" * 70)
      _ <- IO.println(
        "  DISTRIBUTED COMPUTING WITH RAFT: Replicated Counter Example"
      )
      _ <- IO.println("═" * 70)
      _ <- IO.println("")
      _ <- runSimulationWithResult.void
    yield ()

  private def drainNetwork(
      nodes: List[SimulatedNode[IO]],
      network: SimulatedNetwork[IO]
  ): IO[Unit] =
    val nodeMap = nodes.map(n => n.nodeId -> n).toMap
    def loop: IO[Unit] =
      nodes
        .traverse { n =>
          network.tryReceive(n.nodeId).flatMap {
            case Some((from, msg)) =>
              nodeMap(n.nodeId).processMessage(from, msg).as(true)
            case None => IO.pure(false)
          }
        }
        .flatMap { progressed =>
          if progressed.exists(identity) then loop else IO.unit
        }
    loop

  def runSimulationWithResult: IO[List[Long]] =
    for
      // Create 3-node cluster
      _ <- IO.println("Step 1: Creating 3-node cluster...")
      nodeIds = List(NodeId("node-1"), NodeId("node-2"), NodeId("node-3"))
      cluster = ClusterConfig(nodeIds.toSet)
      network <- SimulatedNetwork[IO](nodeIds)

      node1 <- SimulatedNode[IO](nodeIds(0), cluster, network)
      node2 <- SimulatedNode[IO](nodeIds(1), cluster, network)
      node3 <- SimulatedNode[IO](nodeIds(2), cluster, network)
      allNodes = List(node1, node2, node3)

      _ <- IO.println("   ✓ Created nodes: node-1, node-2, node-3\n")

      // Step 2: Trigger election on node-1 and pump Pre-Vote + Real Vote rounds
      _ <- IO.println("Step 2: Triggering election (node-1 times out)...")
      _ <- node1.processMessage(nodeIds(0), ElectionTimeout)
      preState <- node1.getState
      _ <- IO.println(s"   node-1 initial transition: $preState")

      _ <- IO.println(
        "\nStep 3: Pumping Pre-Vote & Vote messages across cluster..."
      )
      _ <- drainNetwork(allNodes, network)

      leaderState <- node1.getState
      _ <- IO.println(s"   node-1 state after election: $leaderState\n")

      // Step 4: Submit commands to leader and replicate to followers
      _ <- IO.println("Step 4: Submitting & replicating commands to leader...")

      _ <- node1.submitCommand(CounterCommand.Increment)
      _ <- drainNetwork(allNodes, network)
      _ <- node1.submitCommand(CounterCommand.Increment)
      _ <- drainNetwork(allNodes, network)
      _ <- node1.submitCommand(CounterCommand.Add(10))
      _ <- drainNetwork(allNodes, network)
      _ <- node1.submitCommand(CounterCommand.Decrement)
      _ <- drainNetwork(allNodes, network)

      // Broadcast heartbeat carrying updated leaderCommit so followers apply up to commitIndex
      _ <- node1.processMessage(nodeIds(0), HeartbeatTimeout)
      _ <- drainNetwork(allNodes, network)

      log1 <- node1.getLog
      _ <- IO.println(s"\n   Leader log: ${log1.size} entries")

      // Step 5: Show state machine state across all 3 replicas
      _ <- IO.println(
        "\nStep 5: Verifying replicated state machine across all 3 nodes..."
      )

      value1 <- node1.getStateMachineValue
      value2 <- node2.getStateMachineValue
      value3 <- node3.getStateMachineValue
      applied1 <- node1.getAppliedLog

      _ <- IO.println(
        s"\n   Counter values: node-1=$value1, node-2=$value2, node-3=$value3"
      )
      _ <- IO.println(s"   Applied log on leader:")
      _ <- applied1.traverse_(line => IO.println(s"      $line"))

      // Summary
      _ <- IO.println("\n" + "═" * 70)
      _ <- IO.println("  DISTRIBUTED SYSTEM SUMMARY")
      _ <- IO.println("═" * 70)
      _ <- IO.println("""
   HOW TO BUILD YOUR OWN DISTRIBUTED SYSTEM:
   
   1. IMPLEMENT StateMachine[F, Result]
      - Define your application's commands (Put, Get, Delete, etc.)
      - Implement apply() to execute commands
      - Implement snapshot()/restore() for log compaction
   
   2. IMPLEMENT Transport[F, Wire]
      - Choose your networking: gRPC, HTTP, TCP, etc.
      - Implement send(), broadcast(), receive()
      - Use Transport.withCodec() to handle serialization
   
   3. IMPLEMENT LogStore[F]
      - Use log-structured storage (RocksDB, LevelDB)
      - Implement append(), get(), truncate()
      - Ensure durability before acknowledging
   
   4. IMPLEMENT StableStore[F]
      - Store term and votedFor persistently
      - Critical for crash recovery
   
   5. CREATE RaftNode
      val node = RaftNode[IO](config, cluster, transport, logStore, stableStore, stateMachine)
      node.use(_.start *> ...)
   
   6. SUBMIT COMMANDS
      Only to the leader! If not leader, redirect to known leader.
      node.submit(myCommand.encode) // Blocks until committed or fails
""")
      _ <- IO.println("═" * 70)
      _ <- IO.println("\n✅ Distributed computing example complete!")
    yield List(value1, value2, value3)
