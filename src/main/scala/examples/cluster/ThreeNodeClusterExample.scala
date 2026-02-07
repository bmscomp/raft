package examples.cluster

import cats.effect.*
import cats.syntax.all.*
import raft.*
import raft.state.*
import raft.message.*
import raft.logic.*
import raft.effect.*
import raft.impl.*
import raft.spi.*

import scala.concurrent.duration.*

/** Example 4: 3-Node Cluster Simulation
  *
  * Demonstrates setting up a complete 3-node RAFT cluster using pure logic
  * functions.
  */
/** Simple logging state machine for cluster demonstration.
  *
  * Interprets each log entry's data as a UTF-8 string and appends it to an
  * in-memory list. Useful for verifying that all cluster nodes apply entries in
  * the same order.
  *
  * @param applied
  *   atomic reference to the ordered list of applied command strings
  */

class LoggingStateMachine[F[_]: Sync](
    applied: Ref[F, List[String]]
) extends StateMachine[F, Unit]:

  def apply(log: Log): F[Unit] =
    val cmd = new String(log.data)
    applied.update(_ :+ cmd) *>
      Sync[F].delay(println(s"    [FSM] Applied: $cmd"))

  def getApplied: F[List[String]] = applied.get

  def snapshot: F[Array[Byte]] =
    applied.get.map(_.mkString("\n").getBytes)

  def restore(data: Array[Byte]): F[Unit] =
    applied.set(new String(data).split("\n").toList)

object LoggingStateMachine:
  def apply[F[_]: Sync]: F[LoggingStateMachine[F]] =
    Ref.of[F, List[String]](Nil).map(new LoggingStateMachine[F](_))

object ThreeNodeClusterExample extends IOApp.Simple:

  val node1Id = NodeId("node-1")
  val node2Id = NodeId("node-2")
  val node3Id = NodeId("node-3")

  val config1 = RaftConfig.default(node1Id).copy(preVoteEnabled = false)
  val config2 = RaftConfig.default(node2Id)
  val config3 = RaftConfig.default(node3Id)

  def run: IO[Unit] =
    IO.println("=== 3-Node RAFT Cluster Simulation ===") *>
      IO.println("\nThis example demonstrates state transitions without") *>
      IO.println("actual networking - using RaftLogic directly.\n") *>
      runSimulation

  private def runSimulation: IO[Unit] = IO {
    // Initialize three nodes as followers
    println("1. Initializing 3 nodes as Followers...")

    var node1State: NodeState =
      NodeState.Follower(term = 0, votedFor = None, leaderId = None)
    var node2State: NodeState =
      NodeState.Follower(term = 0, votedFor = None, leaderId = None)
    var node3State: NodeState =
      NodeState.Follower(term = 0, votedFor = None, leaderId = None)

    println(s"   Node-1: $node1State")
    println(s"   Node-2: $node2State")
    println(s"   Node-3: $node3State")

    // Simulate election timeout on node-1
    println("\n2. Node-1 times out, starts election...")

    val transition1 = RaftLogic.onMessage(
      node1State,
      RaftMessage.ElectionTimeout,
      config1,
      lastLogIndex = 0,
      lastLogTerm = 0,
      clusterSize = 3
    )
    node1State = transition1.state

    println(s"   Node-1 state: $node1State")
    println(
      s"   Effects: ${transition1.effects.map(e => e.getClass.getSimpleName)}"
    )

    // Find the broadcast vote request
    val voteReq = transition1.effects.collectFirst {
      case Effect.Broadcast(msg: RaftMessage.RequestVoteRequest) => msg
    }.get

    // Node-2 and Node-3 receive vote request
    println("\n3. Node-2 and Node-3 receive vote request...")

    val trans2 = RaftLogic.onMessage(node2State, voteReq, config2, 0, 0, 3)
    val trans3 = RaftLogic.onMessage(node3State, voteReq, config3, 0, 0, 3)

    node2State = trans2.state
    node3State = trans3.state

    println(s"   Node-2 state: $node2State")
    println(
      s"   Node-2 effects: ${trans2.effects.map(e => e.getClass.getSimpleName)}"
    )
    println(s"   Node-3 state: $node3State")
    println(
      s"   Node-3 effects: ${trans3.effects.map(e => e.getClass.getSimpleName)}"
    )

    // Extract vote responses
    val resp2 = trans2.effects.collectFirst {
      case Effect.SendMessage(_, msg: RaftMessage.RequestVoteResponse) => msg
    }.get
    val resp3 = trans3.effects.collectFirst {
      case Effect.SendMessage(_, msg: RaftMessage.RequestVoteResponse) => msg
    }.get

    println(s"\n   Vote from Node-2: granted=${resp2.voteGranted}")
    println(s"   Vote from Node-3: granted=${resp3.voteGranted}")

    // Simulate heartbeat from new leader
    println("\n4. Node-1 becomes leader, sends heartbeats...")

    node1State = NodeState.Leader(term = 1, Map.empty, Map.empty)

    val heartbeatTrans = RaftLogic.onMessage(
      node1State,
      RaftMessage.HeartbeatTimeout,
      config1,
      0,
      0,
      3
    )

    val heartbeat = heartbeatTrans.effects.collectFirst {
      case Effect.Broadcast(msg: RaftMessage.AppendEntriesRequest) => msg
    }.get

    println(
      s"   Leader heartbeat: term=${heartbeat.term}, leader=${heartbeat.leaderId.value}"
    )

    // Followers receive heartbeat
    println("\n5. Followers receive heartbeat, acknowledge leader...")

    val trans2b = RaftLogic.onMessage(node2State, heartbeat, config2, 0, 0, 3)
    val trans3b = RaftLogic.onMessage(node3State, heartbeat, config3, 0, 0, 3)

    node2State = trans2b.state
    node3State = trans3b.state

    println(s"   Node-2 state: $node2State")
    println(s"   Node-3 state: $node3State")

    // Final state
    println("\n6. Final cluster state:")
    println(s"   Node-1 (Leader):   $node1State")
    println(s"   Node-2 (Follower): $node2State")
    println(s"   Node-3 (Follower): $node3State")

    println("\n✅ Cluster simulation complete!")
  }
