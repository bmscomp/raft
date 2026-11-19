package raft.jepsen.cluster

import cats.effect.{Async, Ref}
import cats.effect.implicits.*
import cats.syntax.all.*
import raft.state.{
  NodeId,
  NodeRole,
  NodeState,
  ClusterConfig,
  RaftConfig,
  RaftError
}
import raft.impl.{InMemLogStore, InMemStableStore, DefaultTimerService}
import raft.RaftNode
import raft.jepsen.nemesis.NemesisScheduler
import raft.jepsen.kv.{KVStateMachine, KVCommand, KVResult}
import scala.concurrent.duration.*

/** Result of submitting a command to the cluster. */
enum SubmitResult:
  case Success(value: String)
  case CasFailed(currentValue: Option[String])
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
    nodesRef: Ref[F, Map[NodeId, RaftNode[F, raft.jepsen.kv.KVResult]]],
    val transports: Map[NodeId, PartitionableTransport[F]],
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

  /** Submit a write (PUT) command to a specific node and await quorum commit of
    * its exact `(term, index)` log entry.
    */
  def submitPut(nodeId: NodeId, key: String, value: String): F[SubmitResult] =
    val data = KVCommand.encode(KVCommand.Put(key, value))
    proposeAndAwait(nodeId, data, 3.seconds)
      .map {
        case KVResult.Ok         => SubmitResult.Success("ok")
        case KVResult.Error(msg) => SubmitResult.Error(msg)
        case other => SubmitResult.Error(s"Unexpected PUT result: $other")
      }
      .timeoutTo(3.seconds, Async[F].pure(SubmitResult.Timeout))
      .handleError(mapSubmitError)

  /** Submit an atomic CAS (Compare-And-Swap) command and await quorum commit of
    * its exact `(term, index)` log entry.
    */
  def submitCAS(
      nodeId: NodeId,
      key: String,
      expected: String,
      newValue: String
  ): F[SubmitResult] =
    val data = KVCommand.encode(KVCommand.CAS(key, expected, newValue))
    proposeAndAwait(nodeId, data, 3.seconds)
      .map {
        case KVResult.CasOk              => SubmitResult.Success("ok")
        case KVResult.CasFailed(current) => SubmitResult.CasFailed(current)
        case KVResult.Error(msg)         => SubmitResult.Error(msg)
        case other => SubmitResult.Error(s"Unexpected CAS result: $other")
      }
      .timeoutTo(3.seconds, Async[F].pure(SubmitResult.Timeout))
      .handleError(mapSubmitError)

  /** Linearizable quorum read via Raft log commitment (§8).
    *
    * Appending and committing `GET` through the leader's Raft log guarantees a
    * partitioned minority old leader can never serve stale local state.
    */
  def submitGet(nodeId: NodeId, key: String): F[ReadResult] =
    val data = KVCommand.encode(KVCommand.Get(key))
    proposeAndAwait(nodeId, data, 2.seconds)
      .map {
        case KVResult.Value(v)   => ReadResult.Found(v)
        case KVResult.Error(msg) => ReadResult.Error(msg)
        case other => ReadResult.Error(s"Unexpected GET result: $other")
      }
      .timeoutTo(2.seconds, Async[F].pure(ReadResult.Timeout))
      .handleError {
        case RaftError.NotLeader(leader)  => ReadResult.NotLeader(leader)
        case RaftError.ElectionInProgress => ReadResult.NotLeader(None)
        case RaftError.LeadershipTransferInProgress(_) =>
          ReadResult.NotLeader(None)
        case e
            if e.getMessage != null && e.getMessage.toLowerCase.contains(
              "not leader"
            ) =>
          ReadResult.NotLeader(None)
        case e
            if e.getMessage != null && e.getMessage.startsWith(
              "Commit poll exhausted"
            ) =>
          ReadResult.Timeout
        case e =>
          ReadResult.Error(
            Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          )
      }

  private def mapSubmitError(e: Throwable): SubmitResult = e match
    case RaftError.NotLeader(leader)  => SubmitResult.NotLeader(leader)
    case RaftError.ElectionInProgress => SubmitResult.NotLeader(None)
    case RaftError.LeadershipTransferInProgress(_) =>
      SubmitResult.NotLeader(None)
    case _
        if e.getMessage != null && e.getMessage.toLowerCase.contains(
          "not leader"
        ) =>
      SubmitResult.NotLeader(None)
    case _
        if e.getMessage != null && e.getMessage.startsWith(
          "Commit poll exhausted"
        ) =>
      SubmitResult.Timeout
    case _ =>
      SubmitResult.Error(
        Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
      )

  private def proposeAndAwait(
      nodeId: NodeId,
      data: Array[Byte],
      maxWait: FiniteDuration
  ): F[KVResult] =
    nodesRef.get.flatMap { nodes =>
      (nodes.get(nodeId), stateMachines.get(nodeId)) match
        case (Some(node), Some(sm)) =>
          node.propose(data).flatMap { logId =>
            val maxPolls = math.max(1, (maxWait / 5.millis).toInt)
            def poll(remaining: Int): F[KVResult] =
              if remaining <= 0 then
                Async[F].raiseError(
                  new RuntimeException(s"Commit poll exhausted for $logId")
                )
              else
                sm.getResult(logId).flatMap {
                  case Some(res) => Async[F].pure(res)
                  case None => Async[F].sleep(5.millis) >> poll(remaining - 1)
                }
            poll(maxPolls)
          }
        case _ =>
          Async[F].raiseError(new RuntimeException(s"Node $nodeId not found"))
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
          ).allocated.map { case (node, _) =>
            id -> (node: RaftNode[F, raft.jepsen.kv.KVResult])
          }
        }
        .map(_.toMap)

      nodesRef <- Ref
        .of[F, Map[NodeId, RaftNode[F, raft.jepsen.kv.KVResult]]](nodes)
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
