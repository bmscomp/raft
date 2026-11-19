package raft.multigroup

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import raft.RaftNode
import raft.metrics.RaftMetrics
import raft.state.{ClusterConfig, GroupId, LogId, NodeId, NodeState}
import raft.state.NodeState.Leader

/** Coordinator for managing multiple independent Raft consensus groups on a
  * single physical node (CockroachDB / TiKV Multi-Raft model).
  */
trait MultiRaftNode[F[_]]:

  /** Create and start a new Raft consensus group. */
  def createGroup(groupId: GroupId, config: GroupConfig[F]): F[Unit]

  /** Gracefully shut down and remove a Raft group, releasing its resources. */
  def removeGroup(groupId: GroupId): F[Unit]

  /** Look up a running group's [[RaftNode]]. */
  def getGroup(groupId: GroupId): F[Option[RaftNode[F]]]

  /** Return all currently active group identifiers. */
  def listGroups: F[Set[GroupId]]

  /** Submit a fire-and-forget command to a specific group's leader. */
  def submitToGroup(groupId: GroupId, data: Array[Byte]): F[Unit]

  /** Propose a command to `groupId` and return its local `(term, index)`. */
  def proposeToGroup(groupId: GroupId, data: Array[Byte]): F[LogId]

  /** Propose a command to `groupId` and suspend until committed and applied. */
  def proposeAndCommitToGroup(groupId: GroupId, data: Array[Byte]): F[LogId]

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

  /** Rebalance shard leadership across physical hosts: for every group in
    * `preferredLeaders` where this node is currently the active `Leader` and
    * the preferred target is a different voting peer, initiates
    * `transferLeadership(target)` and returns the count of transfers started.
    */
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
    extends RuntimeException(s"Raft group not found: ${groupId.value}")

object MultiRaftNode:

  def apply[F[_]: Async](
      localId: NodeId,
      transport: MultiGroupTransport[F],
      loggerOpt: Option[Logger[F]] = None
  ): F[MultiRaftNode[F]] =
    val resolvedLogger = loggerOpt.getOrElse(NoOpLogger[F])
    Ref
      .of[F, Map[GroupId, (RaftNode[F], F[Unit])]](Map.empty)
      .map(groupsRef =>
        new MultiRaftNodeImpl[F](localId, transport, groupsRef, resolvedLogger)
      )

  /** Managed [[Resource]] creating, starting, and cleanly shutting down a
    * [[MultiRaftNode]].
    */
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
    groupsRef: Ref[F, Map[GroupId, (RaftNode[F], F[Unit])]],
    logger: Logger[F]
) extends MultiRaftNode[F]:

  private def withGroupNode[A](
      groupId: GroupId
  )(fn: RaftNode[F] => F[A]): F[A] =
    groupsRef.get.flatMap { groups =>
      groups.get(groupId) match
        case Some((node, _)) => fn(node)
        case None            =>
          Async[F].raiseError(GroupNotFoundException(groupId))
    }

  def createGroup(groupId: GroupId, config: GroupConfig[F]): F[Unit] =
    for
      groups <- groupsRef.get
      _ <-
        if groups.contains(groupId) then
          logger.warn(
            s"Group ${groupId.value} already exists, skipping creation"
          )
        else
          for
            _ <- logger.info(
              s"Creating raft group ${groupId.value} with ${config.cluster.voters.size} voters"
            )
            resolvedMetrics = config.metrics.getOrElse(RaftMetrics.noop[F])
            groupTransport <- transport.registerGroup(groupId)
            nodeResource = RaftNode[F](
              config.raftConfig,
              config.cluster,
              groupTransport,
              config.logStore,
              config.stableStore,
              config.stateMachine,
              config.timerService,
              Some(resolvedMetrics),
              snapshotStoreOpt = config.snapshotStore,
              loggerOpt = Some(logger)
            )
            pair <- nodeResource.allocated
            (node, release) = pair
            _ <- node.start
            _ <- groupsRef.update(_ + (groupId -> (node, release)))
            _ <- logger.info(s"Group ${groupId.value} started")
          yield ()
    yield ()

  def removeGroup(groupId: GroupId): F[Unit] =
    for
      maybeEntry <- groupsRef.modify { groups =>
        groups.get(groupId) match
          case Some(entry) => (groups - groupId, Some(entry))
          case None        => (groups, None)
      }
      _ <- maybeEntry match
        case Some((node, release)) =>
          node.shutdown *> release *> transport.unregisterGroup(groupId) *>
            logger.info(s"Group ${groupId.value} removed and shut down")
        case None =>
          logger.warn(s"Group ${groupId.value} not found for removal")
    yield ()

  def getGroup(groupId: GroupId): F[Option[RaftNode[F]]] =
    groupsRef.get.map(_.get(groupId).map(_._1))

  def listGroups: F[Set[GroupId]] =
    groupsRef.get.map(_.keySet)

  def submitToGroup(groupId: GroupId, data: Array[Byte]): F[Unit] =
    withGroupNode(groupId)(_.submit(data))

  def proposeToGroup(groupId: GroupId, data: Array[Byte]): F[LogId] =
    withGroupNode(groupId)(_.propose(data))

  def proposeAndCommitToGroup(groupId: GroupId, data: Array[Byte]): F[LogId] =
    withGroupNode(groupId)(_.proposeAndCommit(data))

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
    groupsRef.get.flatMap { groups =>
      groups.get(groupId) match
        case Some((node, _)) => node.getClusterConfig.map(Some(_))
        case None            => Async[F].pure(None)
    }

  def rebalanceLeadership(preferredLeaders: Map[GroupId, NodeId]): F[Int] =
    groupsRef.get.flatMap { groups =>
      preferredLeaders.toList
        .traverse { case (gid, preferredNode) =>
          if preferredNode == localId then Async[F].pure(0)
          else
            groups.get(gid) match
              case None            => Async[F].pure(0)
              case Some((node, _)) =>
                for
                  st <- node.getState
                  cfg <- node.getClusterConfig
                  transferred <- st match
                    case l: Leader
                        if l.transferTarget.isEmpty && cfg.isVoter(
                          preferredNode
                        ) =>
                      node
                        .transferLeadership(preferredNode)
                        .as(1)
                        .handleError(_ => 0)
                    case _ => Async[F].pure(0)
                yield transferred
        }
        .map(_.sum)
    }

  def getGroupState(groupId: GroupId): F[Option[NodeState]] =
    groupsRef.get.flatMap { groups =>
      groups.get(groupId) match
        case Some((node, _)) => node.getState.map(Some(_))
        case None            => Async[F].pure(None)
    }

  def start: F[Unit] =
    transport.startCoalescer *>
      logger.info(s"MultiRaftNode started on ${localId.value}")

  def shutdown: F[Unit] =
    for
      groups <- groupsRef.getAndSet(Map.empty)
      _ <- logger.info(
        s"Shutting down ${groups.size} groups on ${localId.value}"
      )
      _ <- groups.values.toList.traverse_ { case (node, release) =>
        node.shutdown *> release
      }
      _ <- transport.shutdown
    yield ()
