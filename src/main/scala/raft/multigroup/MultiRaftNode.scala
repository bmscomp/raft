package raft.multigroup

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import raft.RaftNode
import raft.state.{GroupId, NodeId, NodeState}
import raft.metrics.RaftMetrics

/** Coordinator for managing multiple independent Raft consensus groups on a
  * single physical node.
  *
  * Production distributed databases (CockroachDB, TiKV) partition data into
  * many independent shards/ranges, each running its own Raft group for
  * replication. A single node participates in hundreds or thousands of these
  * groups simultaneously. `MultiRaftNode` is the coordinator that manages this
  * collection of groups.
  *
  * Each group operates with:
  *   - Its own [[RaftNode]] instance (independent leader election, log, state)
  *   - Its own [[raft.spi.LogStore]] and [[raft.spi.StableStore]] (isolated
  *     persistence)
  *   - Its own [[raft.spi.StateMachine]] (independent application logic)
  *   - A shared [[MultiGroupTransport]] for network efficiency
  *
  * The coordinator provides group lifecycle management (create/remove) and a
  * unified API for submitting commands and querying group state.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[GroupConfig]] for per-group configuration
  * @see
  *   [[MultiGroupTransport]] for the shared transport layer
  */
trait MultiRaftNode[F[_]]:

  /** Create and start a new Raft consensus group.
    *
    * The group begins in Follower state and participates in elections according
    * to its configuration. If a group with the same ID already exists, this
    * operation is a no-op.
    *
    * @param groupId
    *   unique identifier for the new group
    * @param config
    *   the group's configuration and dependencies
    */
  def createGroup(groupId: GroupId, config: GroupConfig[F]): F[Unit]

  /** Remove and shut down a Raft consensus group.
    *
    * The group's event loop is stopped and its resources are released. If the
    * group does not exist, this operation is a no-op.
    *
    * @param groupId
    *   the identifier of the group to remove
    */
  def removeGroup(groupId: GroupId): F[Unit]

  /** Get the [[RaftNode]] instance for a specific group.
    *
    * @param groupId
    *   the group to look up
    * @return
    *   `Some(node)` if the group exists, `None` otherwise
    */
  def getGroup(groupId: GroupId): F[Option[RaftNode[F]]]

  /** List all active group identifiers on this node.
    *
    * @return
    *   the set of currently registered group IDs
    */
  def listGroups: F[Set[GroupId]]

  /** Submit a client command to a specific group.
    *
    * The command is forwarded to the group's [[RaftNode.submit]] method. If the
    * group does not exist, a [[GroupNotFoundException]] is raised.
    *
    * @param groupId
    *   the target group
    * @param data
    *   the serialized command payload
    */
  def submitToGroup(groupId: GroupId, data: Array[Byte]): F[Unit]

  /** Get the current state of a specific group's Raft node.
    *
    * @param groupId
    *   the group to query
    * @return
    *   `Some(state)` if the group exists, `None` otherwise
    */
  def getGroupState(groupId: GroupId): F[Option[NodeState]]

  /** Start all registered groups and the shared transport layer. */
  def start: F[Unit]

  /** Shut down all groups and release shared resources. */
  def shutdown: F[Unit]

/** Exception raised when an operation targets a non-existent group. */
case class GroupNotFoundException(groupId: GroupId)
    extends RuntimeException(s"Raft group not found: ${groupId.value}")

/** Factory for creating [[MultiRaftNode]] instances. */
object MultiRaftNode:

  /** Create a new [[MultiRaftNode]] coordinator.
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @param localId
    *   this physical node's identifier
    * @param transport
    *   the shared multi-group transport for all groups
    * @return
    *   a new multi-raft node coordinator
    */
  def apply[F[_]: Async](
      localId: NodeId,
      transport: MultiGroupTransport[F]
  ): F[MultiRaftNode[F]] =
    for
      logger <- Slf4jLogger.create[F]
      groupsRef <- Ref.of[F, Map[GroupId, RaftNode[F]]](Map.empty)
    yield new MultiRaftNodeImpl[F](localId, transport, groupsRef, logger)

private class MultiRaftNodeImpl[F[_]: Async](
    localId: NodeId,
    transport: MultiGroupTransport[F],
    groupsRef: Ref[F, Map[GroupId, RaftNode[F]]],
    logger: Logger[F]
) extends MultiRaftNode[F]:

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
            resolvedMetrics =
              if config.metrics == null then RaftMetrics.noop[F]
              else config.metrics
            groupTransport = transport.transportForGroup(groupId)
            nodeResource = RaftNode[F](
              config.raftConfig,
              config.cluster,
              groupTransport,
              config.logStore,
              config.stableStore,
              config.stateMachine,
              config.timerService,
              resolvedMetrics
            )
            // Allocate the resource (in production, track the finalizer)
            pair <- nodeResource.allocated
            (node, _) = pair
            _ <- node.start
            _ <- groupsRef.update(_ + (groupId -> node))
            _ <- logger.info(s"Group ${groupId.value} started")
          yield ()
    yield ()

  def removeGroup(groupId: GroupId): F[Unit] =
    for
      maybeNode <- groupsRef.modify { groups =>
        groups.get(groupId) match
          case Some(node) => (groups - groupId, Some(node))
          case None       => (groups, None)
      }
      _ <- maybeNode match
        case Some(node) =>
          node.shutdown *>
            logger.info(s"Group ${groupId.value} removed and shut down")
        case None =>
          logger.warn(s"Group ${groupId.value} not found for removal")
    yield ()

  def getGroup(groupId: GroupId): F[Option[RaftNode[F]]] =
    groupsRef.get.map(_.get(groupId))

  def listGroups: F[Set[GroupId]] =
    groupsRef.get.map(_.keySet)

  def submitToGroup(groupId: GroupId, data: Array[Byte]): F[Unit] =
    for
      groups <- groupsRef.get
      _ <- groups.get(groupId) match
        case Some(node) => node.submit(data)
        case None       =>
          Async[F].raiseError(GroupNotFoundException(groupId))
    yield ()

  def getGroupState(groupId: GroupId): F[Option[NodeState]] =
    for
      groups <- groupsRef.get
      state <- groups.get(groupId) match
        case Some(node) => node.getState.map(Some(_))
        case None       => Async[F].pure(None)
    yield state

  def start: F[Unit] =
    logger.info(s"MultiRaftNode started on ${localId.value}")

  def shutdown: F[Unit] =
    for
      groups <- groupsRef.get
      _ <- logger.info(
        s"Shutting down ${groups.size} groups on ${localId.value}"
      )
      _ <- groups.values.toList.traverse_(_.shutdown)
      _ <- groupsRef.set(Map.empty)
    yield ()
