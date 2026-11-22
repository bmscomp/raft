package raft.multigroup

import cats.effect.*
import cats.effect.std.AtomicCell
import cats.effect.syntax.monadCancel.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import raft.{AnyRaftNode, RaftNode}
import raft.metrics.RaftMetrics
import raft.state.{
  Applied,
  Bytes,
  ClusterConfig,
  GroupId,
  LogId,
  NodeId,
  NodeState,
  RaftError
}
import raft.state.NodeState.Leader

/** Coordinator for managing multiple independent Raft consensus groups on a
  * single physical node (CockroachDB / TiKV Multi-Raft model).
  *
  * Group lifecycles (`createGroup`, `createTypedGroup`, `removeGroup`,
  * `shutdown`) are serialized atomically via Cats Effect [[AtomicCell]] with
  * resource-safe error rollback (`0` TOCTOU races, `0` leaked fibers).
  */
trait MultiRaftNode[F[_]]:

  /** Create and start a new Raft consensus group. */
  def createGroup(groupId: GroupId, config: GroupConfig[F]): F[Unit]

  /** Create and start a strongly-typed Raft consensus group, returning its
    * typed `RaftNode[F, R]` handle so callers can invoke `proposeAndApply`
    * directly with output type `R`.
    */
  def createTypedGroup[R](
      groupId: GroupId,
      config: TypedGroupConfig[F, R]
  ): F[RaftNode[F, R]]

  /** Gracefully shut down and remove a Raft group, releasing its resources. */
  def removeGroup(groupId: GroupId): F[Unit]

  /** Look up a running group's [[RaftNode]]. */
  def getGroup(groupId: GroupId): F[Option[AnyRaftNode[F]]]

  /** Return all currently active group identifiers. */
  def listGroups: F[Set[GroupId]]

  /** Submit a fire-and-forget command to a specific group's leader. */
  def submitToGroup(groupId: GroupId, data: Array[Byte]): F[Unit]

  /** Propose a command to `groupId` and return its local `(term, index)`. */
  def proposeToGroup(groupId: GroupId, data: Array[Byte]): F[LogId]

  /** `Bytes` variant of [[proposeToGroup]]. */
  def proposeBytesToGroup(groupId: GroupId, payload: Bytes): F[LogId] =
    proposeToGroup(groupId, payload.toArray)

  /** Propose a command to `groupId` and suspend until committed and applied. */
  def proposeAndCommitToGroup(groupId: GroupId, data: Array[Byte]): F[LogId]

  /** Propose an immutable [[Bytes]] command to `groupId` and return the
    * committed `LogId` alongside the state-machine output.
    */
  def proposeAndApplyToGroup(
      groupId: GroupId,
      payload: Bytes
  ): F[Applied[Any]]

  /** Atomically propose a batch of commands to `groupId` and suspend until all
    * entries are committed and applied.
    */
  def proposeBatchAndCommitToGroup(
      groupId: GroupId,
      commands: Seq[Array[Byte]]
  ): F[Vector[LogId]]

  /** Confirm quorum leadership on `groupId` (§6.4 `ReadIndex`) and return its
    * linearizable read index.
    */
  def readIndexFromGroup(groupId: GroupId): F[Long]

  /** Attempt a zero-RTT lease read on `groupId` (§6.4 `LeaseRead`). */
  def leaseReadFromGroup(groupId: GroupId): F[Option[Long]]

  /** Initiate graceful leadership transfer on `groupId` to `target` (§3.10). */
  def transferGroupLeadership(groupId: GroupId, target: NodeId): F[Unit]

  /** Propose a Joint Consensus / membership configuration change on `groupId`
    * (§6).
    */
  def proposeConfChangeToGroup(
      groupId: GroupId,
      newConfig: ClusterConfig
  ): F[LogId]

  /** Inspect the active [[ClusterConfig]] of `groupId`. */
  def getGroupClusterConfig(groupId: GroupId): F[Option[ClusterConfig]]

  /** Rebalance shard leadership across physical hosts. */
  def rebalanceLeadership(preferredLeaders: Map[GroupId, NodeId]): F[Int]

  /** Query the current consensus state of a specific group. */
  def getGroupState(groupId: GroupId): F[Option[NodeState]]

  /** Start the coordinator. */
  def start: F[Unit]

  /** Shut down all groups and release all allocated resources. */
  def shutdown: F[Unit]

/** Raised when an operation targets a [[GroupId]] not registered on this host.
  */
final case class GroupNotFoundException(groupId: GroupId)
    extends RuntimeException(s"Raft group not found: ${groupId.value}"):
  def toRaftError: RaftError.GroupNotFound =
    RaftError.GroupNotFound(groupId)

object MultiRaftNode:

  def apply[F[_]: Async](
      localId: NodeId,
      transport: MultiGroupTransport[F],
      loggerOpt: Option[Logger[F]] = None
  ): F[MultiRaftNode[F]] =
    val resolvedLogger = loggerOpt.getOrElse(NoOpLogger[F])
    AtomicCell[F]
      .of(Map.empty[GroupId, (AnyRaftNode[F], F[Unit])])
      .map(groupsCell =>
        new MultiRaftNodeImpl[F](localId, transport, groupsCell, resolvedLogger)
      )

  def resource[F[_]: Async](
      localId: NodeId,
      transport: MultiGroupTransport[F],
      loggerOpt: Option[Logger[F]] = None
  ): Resource[F, MultiRaftNode[F]] =
    Resource.make(apply(localId, transport, loggerOpt).flatTap(_.start))(
      _.shutdown
    )

private final class MultiRaftNodeImpl[F[_]: Async](
    localId: NodeId,
    transport: MultiGroupTransport[F],
    groupsCell: AtomicCell[F, Map[GroupId, (AnyRaftNode[F], F[Unit])]],
    logger: Logger[F]
) extends MultiRaftNode[F]:
  private val F = Async[F]

  private def withGroupNode[A](
      groupId: GroupId
  )(fn: AnyRaftNode[F] => F[A]): F[A] =
    groupsCell.get.flatMap { groups =>
      groups.get(groupId) match
        case Some((node, _)) => fn(node)
        case None            => F.raiseError(GroupNotFoundException(groupId))
    }

  def createGroup(groupId: GroupId, config: GroupConfig[F]): F[Unit] =
    groupsCell.evalUpdate { groups =>
      if groups.contains(groupId) then
        logger
          .warn(s"Group ${groupId.value} already exists, skipping creation")
          .as(groups)
      else
        allocateAndStartNode(
          groupId = groupId,
          raftConfig = config.raftConfig,
          cluster = config.cluster,
          logStore = config.logStore,
          stableStore = config.stableStore,
          stateMachine = config.stateMachine,
          timerService = config.timerService,
          metrics = config.metrics,
          snapshotStore = config.snapshotStore
        ).map { case (node, release) =>
          groups.updated(groupId, (node.widenOutput, release))
        }
    }

  def createTypedGroup[R](
      groupId: GroupId,
      config: TypedGroupConfig[F, R]
  ): F[RaftNode[F, R]] =
    groupsCell.evalModify { groups =>
      if groups.contains(groupId) then
        F.raiseError(
          RaftError.ProposalDropped(
            s"Typed group ${groupId.value} already exists on ${localId.value}"
          )
        )
      else
        allocateAndStartNode[R](
          groupId = groupId,
          raftConfig = config.raftConfig,
          cluster = config.cluster,
          logStore = config.logStore,
          stableStore = config.stableStore,
          stateMachine = config.stateMachine,
          timerService = config.timerService,
          metrics = config.metrics,
          snapshotStore = config.snapshotStore
        ).map { case (typedNode, release) =>
          (groups.updated(groupId, (typedNode.widenOutput, release)), typedNode)
        }
    }

  private def allocateAndStartNode[R](
      groupId: GroupId,
      raftConfig: raft.state.RaftConfig,
      cluster: ClusterConfig,
      logStore: raft.spi.LogStore[F],
      stableStore: raft.spi.StableStore[F],
      stateMachine: raft.spi.StateMachine[F, R],
      timerService: raft.spi.TimerService[F],
      metrics: Option[RaftMetrics[F]],
      snapshotStore: Option[raft.spi.SnapshotStore[F]]
  ): F[(RaftNode[F, R], F[Unit])] =
    val resolvedMetrics = metrics.getOrElse(RaftMetrics.noop[F])
    F.uncancelable { poll =>
      for
        _ <- logger.info(
          s"Creating raft group ${groupId.value} with ${cluster.voters.size} voters"
        )
        groupTransport <- transport.registerGroup(groupId)
        nodeResource = RaftNode.typed[F, R](
          config = raftConfig,
          cluster = cluster,
          transport = groupTransport,
          logStore = logStore,
          stableStore = stableStore,
          stateMachine = stateMachine,
          timerService = timerService,
          metrics = Some(resolvedMetrics),
          snapshotStoreOpt = snapshotStore,
          loggerOpt = Some(logger)
        )
        pair <- poll(nodeResource.allocated).guaranteeCase {
          case Outcome.Succeeded(_) => F.unit
          case _                    => transport.unregisterGroup(groupId)
        }
        (node, release) = pair
        _ <- poll(node.start).guaranteeCase {
          case Outcome.Succeeded(_) => F.unit
          case _                    =>
            release *> transport.unregisterGroup(groupId)
        }
        _ <- logger.info(s"Group ${groupId.value} started")
      yield (node, release)
    }

  def removeGroup(groupId: GroupId): F[Unit] =
    groupsCell
      .evalModify { groups =>
        groups.get(groupId) match
          case Some(entry) => F.pure((groups - groupId, Some(entry)))
          case None        => F.pure((groups, None))
      }
      .flatMap {
        case Some((node, release)) =>
          node.shutdown *> release *> transport.unregisterGroup(groupId) *>
            logger.info(s"Group ${groupId.value} removed and shut down")
        case None =>
          logger.warn(s"Group ${groupId.value} not found for removal")
      }

  def getGroup(groupId: GroupId): F[Option[AnyRaftNode[F]]] =
    groupsCell.get.map(_.get(groupId).map(_._1))

  def listGroups: F[Set[GroupId]] =
    groupsCell.get.map(_.keySet)

  def submitToGroup(groupId: GroupId, data: Array[Byte]): F[Unit] =
    withGroupNode(groupId)(_.submit(data))

  def proposeToGroup(groupId: GroupId, data: Array[Byte]): F[LogId] =
    withGroupNode(groupId)(_.propose(data))

  override def proposeBytesToGroup(groupId: GroupId, payload: Bytes): F[LogId] =
    withGroupNode(groupId)(_.proposeBytes(payload))

  def proposeAndCommitToGroup(groupId: GroupId, data: Array[Byte]): F[LogId] =
    withGroupNode(groupId)(_.proposeAndCommit(data))

  def proposeAndApplyToGroup(
      groupId: GroupId,
      payload: Bytes
  ): F[Applied[Any]] =
    withGroupNode(groupId)(_.proposeAndApply(payload))

  def proposeBatchAndCommitToGroup(
      groupId: GroupId,
      commands: Seq[Array[Byte]]
  ): F[Vector[LogId]] =
    withGroupNode(groupId)(_.proposeBatchAndCommit(commands))

  def readIndexFromGroup(groupId: GroupId): F[Long] =
    withGroupNode(groupId)(_.readIndex)

  def leaseReadFromGroup(groupId: GroupId): F[Option[Long]] =
    withGroupNode(groupId)(_.leaseRead)

  def transferGroupLeadership(groupId: GroupId, target: NodeId): F[Unit] =
    withGroupNode(groupId)(_.transferLeadership(target))

  def proposeConfChangeToGroup(
      groupId: GroupId,
      newConfig: ClusterConfig
  ): F[LogId] =
    withGroupNode(groupId)(_.proposeConfChange(newConfig))

  def getGroupClusterConfig(groupId: GroupId): F[Option[ClusterConfig]] =
    groupsCell.get.flatMap { groups =>
      groups.get(groupId) match
        case Some((node, _)) => node.getClusterConfig.map(Some(_))
        case None            => F.pure(None)
    }

  def rebalanceLeadership(preferredLeaders: Map[GroupId, NodeId]): F[Int] =
    groupsCell.get.flatMap { groups =>
      preferredLeaders.toList
        .traverse { case (gid, preferredNode) =>
          if preferredNode == localId then F.pure(0)
          else
            groups.get(gid) match
              case None            => F.pure(0)
              case Some((node, _)) =>
                for
                  st <- node.getState
                  cfg <- node.getClusterConfig
                  transferred <- st match
                    case l: Leader
                        if l.transferTarget.isEmpty && cfg.canCampaign(
                          preferredNode
                        ) =>
                      node
                        .transferLeadership(preferredNode)
                        .as(1)
                        .handleError(_ => 0)
                    case _ => F.pure(0)
                yield transferred
        }
        .map(_.sum)
    }

  def getGroupState(groupId: GroupId): F[Option[NodeState]] =
    groupsCell.get.flatMap { groups =>
      groups.get(groupId) match
        case Some((node, _)) => node.getState.map(Some(_))
        case None            => F.pure(None)
    }

  def start: F[Unit] =
    transport.startCoalescer *>
      logger.info(s"MultiRaftNode started on ${localId.value}")

  def shutdown: F[Unit] =
    groupsCell
      .evalModify(groups => F.pure((Map.empty, groups)))
      .flatMap { groups =>
        logger.info(
          s"Shutting down ${groups.size} groups on ${localId.value}"
        ) *>
          groups.values.toList.traverse_ { case (node, release) =>
            node.shutdown *> release
          } *>
          transport.shutdown
      }
