package raft.multigroup

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import raft.RaftNode
import raft.state.{GroupId, NodeId, NodeState}
import raft.metrics.RaftMetrics

/** Coordinator for managing multiple independent Raft consensus groups on a
  * single physical node.
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

  /** Submit a command to a specific group's leader. */
  def submitToGroup(groupId: GroupId, data: Array[Byte]): F[Unit]

  /** Query the current consensus state of a specific group. */
  def getGroupState(groupId: GroupId): F[Option[NodeState]]

  /** Start the coordinator. */
  def start: F[Unit]

  /** Shut down all groups and release all allocated resources. */
  def shutdown: F[Unit]

/** Backward-compatible alias extending [[raft.state.RaftError]]. */
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

private final class MultiRaftNodeImpl[F[_]: Async](
    localId: NodeId,
    transport: MultiGroupTransport[F],
    groupsRef: Ref[F, Map[GroupId, (RaftNode[F], F[Unit])]],
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
            resolvedMetrics = config.metrics.getOrElse(RaftMetrics.noop[F])
            groupTransport = transport.transportForGroup(groupId)
            nodeResource = RaftNode[F](
              config.raftConfig,
              config.cluster,
              groupTransport,
              config.logStore,
              config.stableStore,
              config.stateMachine,
              config.timerService,
              Some(resolvedMetrics),
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
          node.shutdown *> release *>
            logger.info(s"Group ${groupId.value} removed and shut down")
        case None =>
          logger.warn(s"Group ${groupId.value} not found for removal")
    yield ()

  def getGroup(groupId: GroupId): F[Option[RaftNode[F]]] =
    groupsRef.get.map(_.get(groupId).map(_._1))

  def listGroups: F[Set[GroupId]] =
    groupsRef.get.map(_.keySet)

  def submitToGroup(groupId: GroupId, data: Array[Byte]): F[Unit] =
    for
      groups <- groupsRef.get
      _ <- groups.get(groupId) match
        case Some((node, _)) => node.submit(data)
        case None            =>
          Async[F].raiseError(GroupNotFoundException(groupId))
    yield ()

  def getGroupState(groupId: GroupId): F[Option[NodeState]] =
    for
      groups <- groupsRef.get
      state <- groups.get(groupId) match
        case Some((node, _)) => node.getState.map(Some(_))
        case None            => Async[F].pure(None)
    yield state

  def start: F[Unit] =
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
    yield ()
