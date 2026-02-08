package raft.jepsen.cluster

import cats.effect.{Async, IO, Ref, Resource}
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.syntax.all.*
import raft.state.{NodeId, NodeRole, NodeState, ClusterConfig, RaftConfig}
import raft.impl.{InMemLogStore, InMemStableStore, DefaultTimerService}
import raft.spi.{LogStore, StableStore, StateMachine, TimerService}
import raft.message.RaftMessage
import raft.RaftNode
import raft.jepsen.nemesis.{NemesisAction, NemesisScheduler}
import examples.kvstore.{KVStateMachine, KVCommand, KVResult}
import fs2.Stream
import scala.concurrent.duration.*

/** Result of submitting a command to the cluster. */
enum SubmitResult:
  case Success(value: String)
  case NotLeader(leader: Option[NodeId])
  case Timeout
  case Error(msg: String)

/** Result of reading from the cluster. */
enum ReadResult:
  case Found(value: Option[String])
  case NotLeader(leader: Option[NodeId])
  case Timeout
  case Error(msg: String)

/** An in-process simulated Raft cluster for Jepsen-style testing.
  *
  * Manages N RaftNode instances connected via PartitionableTransport. Each node
  * runs the KVStateMachine for linearizability testing. Supports full lifecycle
  * control (start/stop/kill/restart).
  */
class SimulatedCluster[F[_]: Async](
    val nodeIds: List[NodeId],
    nodesRef: Ref[F, Map[NodeId, RaftNode[F]]],
    transports: Map[NodeId, PartitionableTransport[F]],
    stateMachines: Map[NodeId, KVStateMachine[F]],
    stateRef: Ref[F, PartitionState],
    runningFibers: Ref[F, Map[NodeId, cats.effect.Fiber[F, Throwable, Unit]]]
):

  /** Start all nodes in the cluster. */
  def start: F[Unit] =
    nodesRef.get.flatMap { nodes =>
      nodes.toList.traverse_ { case (id, node) =>
        Async[F].start(node.start.handleError(_ => ())).flatMap { fiber =>
          runningFibers.update(_.updated(id, fiber))
        }
      }
    }

  /** Stop all nodes in the cluster. */
  def stop: F[Unit] =
    for
      nodes <- nodesRef.get
      _ <- nodes.values.toList.traverse_(_.shutdown.handleError(_ => ()))
      fibers <- runningFibers.get
      _ <- fibers.values.toList.traverse_(_.cancel)
    yield ()

  /** Stop a single node (simulates kill). */
  def stopNode(id: NodeId): F[Unit] =
    for
      nodes <- nodesRef.get
      _ <- nodes.get(id).traverse_(_.shutdown.handleError(_ => ()))
      _ <- runningFibers.get.flatMap(_.get(id).traverse_(_.cancel))
      _ <- runningFibers.update(_ - id)
    yield ()

  /** Restart a node (re-start its event loop). */
  def restartNode(id: NodeId): F[Unit] =
    nodesRef.get.flatMap { nodes =>
      nodes.get(id).traverse_ { node =>
        Async[F].start(node.start.handleError(_ => ())).flatMap { fiber =>
          runningFibers.update(_.updated(id, fiber))
        }
      }
    }

  /** Get the current leader (if any). */
  def getLeader: F[Option[NodeId]] =
    nodesRef.get.flatMap { nodes =>
      nodes.toList
        .traverseFilter { case (id, node) =>
          node.getState
            .map {
              case _: NodeState.Leader => Some(id)
              case _                   => None
            }
            .handleError(_ => None)
        }
        .map(_.headOption)
    }

  /** Submit a write (PUT) command to a specific node.
    *
    * Submits to the leader and polls the state machine to confirm commit. Only
    * records as Success after confirming the value was applied. If confirmation
    * times out, returns Timeout (recorded as Info/indeterminate by the client —
    * correct Jepsen semantics for unconfirmed writes).
    */
  def submitPut(nodeId: NodeId, key: String, value: String): F[SubmitResult] =
    val cmd = KVCommand.Put(key, value)
    val data = KVCommand.encode(cmd)
    nodesRef.get.flatMap { nodes =>
      nodes.get(nodeId) match
        case Some(node) =>
          node
            .submit(data)
            .flatMap { _ =>
              waitForCommit(nodeId, key, value)
                .as(SubmitResult.Success("ok"): SubmitResult)
            }
            .timeoutTo(3.seconds, Async[F].pure(SubmitResult.Timeout))
            .handleError { e =>
              if e.getMessage != null && e.getMessage.contains("Not leader")
              then SubmitResult.NotLeader(None)
              else SubmitResult.Error(e.getMessage)
            }
        case None =>
          Async[F].pure(SubmitResult.Error(s"Node $nodeId not found"))
    }

  /** Poll the state machine for the expected value. Uses a short poll interval
    * with bounded retries.
    */
  private def waitForCommit(
      nodeId: NodeId,
      key: String,
      expected: String
  ): F[Unit] =
    stateMachines.get(nodeId) match
      case Some(sm) =>
        def poll(remaining: Int): F[Unit] =
          if remaining <= 0 then
            Async[F].raiseError(
              new RuntimeException(s"Commit poll exhausted for $key=$expected")
            )
          else
            sm.get(key).flatMap {
              case Some(v) if v == expected => Async[F].unit
              case _ => Async[F].sleep(5.millis) >> poll(remaining - 1)
            }
        poll(500)
      case None => Async[F].unit

  /** Read via the leader's state machine with a read-fence.
    *
    * The read-fence checks leadership both before and after reading from the
    * state machine. If leadership is lost between the two checks, the read is
    * discarded (returns NotLeader) to prevent returning stale data that would
    * violate linearizability.
    */
  def submitGet(nodeId: NodeId, key: String): F[ReadResult] =
    nodesRef.get.flatMap { nodes =>
      (nodes.get(nodeId), stateMachines.get(nodeId)) match
        case (Some(node), Some(sm)) =>
          node.getState
            .flatMap {
              case _: NodeState.Leader =>
                sm.get(key)
                  .flatMap { v =>
                    // Read-fence: verify still leader after reading
                    node.getState.map {
                      case _: NodeState.Leader =>
                        ReadResult.Found(v): ReadResult
                      case _ =>
                        ReadResult.NotLeader(None): ReadResult
                    }
                  }
                  .timeoutTo(2.seconds, Async[F].pure(ReadResult.Timeout))
                  .handleError(e => ReadResult.Error(e.getMessage))
              case _ =>
                Async[F].pure(ReadResult.NotLeader(None))
            }
            .handleError(_ => ReadResult.Error("Node unavailable"))
        case _ =>
          Async[F].pure(ReadResult.Error(s"Node $nodeId not found"))
    }

  /** Get the nemesis scheduler for this cluster. */
  def createNemesis: NemesisScheduler[F] =
    new NemesisScheduler[F](
      stateRef,
      nodeIds,
      onNodeKill = id => stopNode(id),
      onNodeRestart = id => restartNode(id),
      onGetLeader = getLeader
    )

object SimulatedCluster:

  /** Create a simulated cluster of the given size. */
  def create[F[_]: Async](size: Int): F[SimulatedCluster[F]] =
    val ids = (1 to size).map(i => NodeId(s"n$i")).toList
    createWithIds(ids)

  /** Create a simulated cluster with specific node IDs. */
  def createWithIds[F[_]: Async](ids: List[NodeId]): F[SimulatedCluster[F]] =
    for
      result <- PartitionableTransport.createCluster[F](ids)
      (transports, stateRef) = result

      logStores <- ids
        .traverse(id => InMemLogStore[F]().map(id -> _))
        .map(_.toMap)
      stableStores <- ids
        .traverse(id => InMemStableStore[F]().map(id -> _))
        .map(_.toMap)

      stateMachines <- ids
        .traverse(id => KVStateMachine[F].map(id -> _))
        .map(_.toMap)

      timerServices <- ids
        .traverse(id => DefaultTimerService[F].map(id -> _))
        .map(_.toMap)

      clusterConfig = ClusterConfig(ids.map(_ -> NodeRole.Voter).toMap)

      nodes <- ids
        .traverse { id =>
          val config = RaftConfig(
            localId = id,
            electionTimeoutMin = 300.millis,
            electionTimeoutMax = 600.millis,
            heartbeatInterval = 100.millis,
            preVoteEnabled = true
          )
          RaftNode[F](
            config,
            clusterConfig,
            transports(id),
            logStores(id),
            stableStores(id),
            stateMachines(id),
            timerServices(id)
          ).allocated.map { case (node, _) => id -> node }
        }
        .map(_.toMap)

      nodesRef <- Ref.of[F, Map[NodeId, RaftNode[F]]](nodes)
      fibersRef <- Ref
        .of[F, Map[NodeId, cats.effect.Fiber[F, Throwable, Unit]]](Map.empty)
    yield new SimulatedCluster[F](
      ids,
      nodesRef,
      transports,
      stateMachines,
      stateRef,
      fibersRef
    )
