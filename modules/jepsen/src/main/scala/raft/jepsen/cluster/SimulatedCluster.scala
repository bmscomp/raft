package raft.jepsen.cluster

import scala.concurrent.duration.*

import cats.effect.{Async, Ref}
import cats.effect.implicits.*
import cats.syntax.all.*

import raft.RaftNode
import raft.impl.{
  DefaultTimerService,
  InMemLogStore,
  InMemSnapshotStore,
  InMemStableStore
}
import raft.jepsen.kv.{KVCommand, KVResult, KVStateMachine}
import raft.jepsen.nemesis.NemesisScheduler
import raft.state.{
  ClusterConfig,
  CompactionConfig,
  CompactionLevel,
  NodeId,
  NodeRole,
  NodeState,
  RaftConfig,
  RaftError
}

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

/** An in-process simulated Raft cluster for Jepsen-style linearizability
  * verification.
  *
  *   - **Event-driven typed writes**: `submitPut` and `submitCAS` await quorum
  *     commit and state-machine execution directly via
  *     `RaftNode.proposeAndApply` (`0` busy-polling loops).
  *   - **True `LeaseRead` -> `ReadIndex` linearizable reads**: `submitGet`
  *     exercises `RaftNode.leaseRead` falling back to quorum
  *     `RaftNode.readIndex` + `KVStateMachine.get` (rather than mutating the
  *     Raft log), directly verifying stale-leader fencing under partitions
  *     (**fixes E2E1**).
  */
class SimulatedCluster[F[_]: Async](
    val nodeIds: List[NodeId],
    nodesRef: Ref[F, Map[NodeId, RaftNode[F, KVResult]]],
    releaseHooksRef: Ref[F, Map[NodeId, F[Unit]]],
    val transports: Map[NodeId, PartitionableTransport[F]],
    stateMachines: Map[NodeId, KVStateMachine[F]],
    stateRef: Ref[F, PartitionState]
):
  private val F = Async[F]

  /** Start all nodes in the cluster. */
  def start: F[Unit] =
    nodesRef.get.flatMap { nodes =>
      nodes.values.toList.traverse_(_.start.handleError(_ => ()))
    }

  /** Stop all nodes and release their underlying resources cleanly. */
  def stop: F[Unit] =
    for
      nodes <- nodesRef.get
      _ <- nodes.values.toList.traverse_(_.shutdown.handleError(_ => ()))
      hooks <- releaseHooksRef.getAndSet(Map.empty)
      _ <- hooks.values.toList.traverse_(_.handleError(_ => ()))
    yield ()

  /** Stop a single node (simulates abrupt process stop). */
  def stopNode(id: NodeId): F[Unit] =
    nodesRef.get.flatMap { nodes =>
      nodes.get(id).traverse_(_.shutdown.handleError(_ => ()))
    }

  /** Restart a stopped node's consensus event loop. */
  def restartNode(id: NodeId): F[Unit] =
    nodesRef.get.flatMap { nodes =>
      nodes.get(id).traverse_(_.start.handleError(_ => ()))
    }

  /** Get the highest-term active leader (excluding killed nodes). */
  def getLeader: F[Option[NodeId]] =
    for
      ps <- stateRef.get
      nodes <- nodesRef.get
      found <- nodes.toList
        .filterNot { case (id, _) => ps.killed.contains(id) }
        .traverse { case (id, node) =>
          node.getState
            .map {
              case l: NodeState.Leader => Some((id, l.term))
              case _                   => None
            }
            .handleError(_ => None)
        }
        .map(_.flatten.sortBy(-_._2).headOption.map(_._1))
    yield found

  /** Submit a write (`PUT`) command via `proposeAndApply`. */
  def submitPut(nodeId: NodeId, key: String, value: String): F[SubmitResult] =
    stateRef.get.flatMap { ps =>
      if ps.killed.contains(nodeId) then F.pure(SubmitResult.NotLeader(None))
      else
        val data = KVCommand.encode(KVCommand.Put(key, value))
        proposeTyped(nodeId, data)
          .map {
            case KVResult.Ok         => SubmitResult.Success("ok")
            case KVResult.Error(msg) => SubmitResult.Error(msg)
            case other => SubmitResult.Error(s"Unexpected PUT result: $other")
          }
          .timeoutTo(3.seconds, F.pure(SubmitResult.Timeout))
          .handleError(mapSubmitError)
    }

  /** Submit an atomic `CAS` command via `proposeAndApply`. */
  def submitCAS(
      nodeId: NodeId,
      key: String,
      expected: String,
      newValue: String
  ): F[SubmitResult] =
    stateRef.get.flatMap { ps =>
      if ps.killed.contains(nodeId) then F.pure(SubmitResult.NotLeader(None))
      else
        val data = KVCommand.encode(KVCommand.CAS(key, expected, newValue))
        proposeTyped(nodeId, data)
          .map {
            case KVResult.CasOk              => SubmitResult.Success("ok")
            case KVResult.CasFailed(current) => SubmitResult.CasFailed(current)
            case KVResult.Error(msg)         => SubmitResult.Error(msg)
            case other => SubmitResult.Error(s"Unexpected CAS result: $other")
          }
          .timeoutTo(3.seconds, F.pure(SubmitResult.Timeout))
          .handleError(mapSubmitError)
    }

  /** Execute a linearizable read via `leaseRead` -> `readIndex` +
    * `KVStateMachine.get` (§6.4).
    */
  def submitGet(nodeId: NodeId, key: String): F[ReadResult] =
    stateRef.get.flatMap { ps =>
      if ps.killed.contains(nodeId) then F.pure(ReadResult.NotLeader(None))
      else
        nodesRef.get.flatMap { nodes =>
          (nodes.get(nodeId), stateMachines.get(nodeId)) match
            case (Some(node), Some(sm)) =>
              val confirmReadIndex: F[Long] =
                node.leaseRead.flatMap {
                  case Some(idx) => F.pure(idx)
                  case None      => node.readIndex
                }
              confirmReadIndex
                .flatMap(_ => sm.get(key).map(ReadResult.Found.apply))
                .timeoutTo(2.seconds, F.pure(ReadResult.Timeout))
                .handleError(mapReadError)
            case _ =>
              F.pure(ReadResult.Error(s"Node $nodeId not found"))
        }
    }

  private def proposeTyped(nodeId: NodeId, data: Array[Byte]): F[KVResult] =
    nodesRef.get.flatMap { nodes =>
      nodes.get(nodeId) match
        case Some(node) => node.proposeAndApply(data).map(_.value)
        case None       =>
          F.raiseError(new RuntimeException(s"Node $nodeId not found"))
    }

  private def mapReadError(e: Throwable): ReadResult = e match
    case RaftError.NotLeader(leader)       => ReadResult.NotLeader(leader)
    case RaftError.ElectionInProgress      => ReadResult.NotLeader(None)
    case RaftError.LeaderNotReadyInTerm(_) => ReadResult.NotLeader(None)
    case RaftError.LeadershipTransferInProgress(_) => ReadResult.NotLeader(None)
    case RaftError.ProposalDropped(_)              => ReadResult.Timeout
    case other                                     =>
      Option(other.getMessage) match
        case Some(m) if m.toLowerCase.contains("not leader") =>
          ReadResult.NotLeader(None)
        case Some(m) => ReadResult.Error(m)
        case None    => ReadResult.Error(other.getClass.getSimpleName)

  private def mapSubmitError(e: Throwable): SubmitResult = e match
    case RaftError.NotLeader(leader)  => SubmitResult.NotLeader(leader)
    case RaftError.ElectionInProgress => SubmitResult.NotLeader(None)
    case RaftError.LeadershipTransferInProgress(_) =>
      SubmitResult.NotLeader(None)
    case RaftError.ProposalDropped(_) => SubmitResult.Timeout
    case other                        =>
      Option(other.getMessage) match
        case Some(m) if m.toLowerCase.contains("not leader") =>
          SubmitResult.NotLeader(None)
        case Some(m) => SubmitResult.Error(m)
        case None    => SubmitResult.Error(other.getClass.getSimpleName)

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

  /** Create a simulated cluster with specific node IDs, aggressive snapshot
    * compaction, `CheckQuorum`, and linearizable `LeaseRead` (`200ms < 300ms`).
    */
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
      snapStores <- ids
        .traverse(id => InMemSnapshotStore[F]().map(id -> _))
        .map(_.toMap)
      stateMachines <- ids
        .traverse(id => KVStateMachine[F].map(id -> _))
        .map(_.toMap)
      timerServices <- ids
        .traverse(id => DefaultTimerService[F].map(id -> _))
        .map(_.toMap)

      clusterConfig = ClusterConfig(ids.map(_ -> NodeRole.Voter).toMap)

      allocatedPairs <- ids.traverse { id =>
        val config = RaftConfig(
          localId = id,
          electionTimeoutMin = 300.millis,
          electionTimeoutMax = 600.millis,
          heartbeatInterval = 90.millis,
          preVoteEnabled = true,
          checkQuorumEnabled = true,
          leaseReadEnabled = true,
          leaderLeaseDuration = 180.millis,
          compaction = CompactionConfig(
            level = CompactionLevel.Aggressive,
            snapshotChunkSize = 512,
            retainedEntries = 8L
          )
        )
        RaftNode[F](
          config = config,
          cluster = clusterConfig,
          transport = transports(id),
          logStore = logStores(id),
          stableStore = stableStores(id),
          stateMachine = stateMachines(id),
          timerService = timerServices(id),
          snapshotStoreOpt = Some(snapStores(id))
        ).allocated.map { case (node, release) =>
          (id, node: RaftNode[F, KVResult], release)
        }
      }

      nodes = allocatedPairs.map(t => t._1 -> t._2).toMap
      releases = allocatedPairs.map(t => t._1 -> t._3).toMap
      nodesRef <- Ref.of[F, Map[NodeId, RaftNode[F, KVResult]]](nodes)
      releaseHooksRef <- Ref.of[F, Map[NodeId, F[Unit]]](releases)
    yield new SimulatedCluster[F](
      ids,
      nodesRef,
      releaseHooksRef,
      transports,
      stateMachines,
      stateRef
    )
