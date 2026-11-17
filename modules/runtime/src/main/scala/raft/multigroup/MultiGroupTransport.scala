package raft.multigroup

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream

import raft.state.{GroupId, NodeId}
import raft.message.RaftMessage
import raft.spi.RaftTransport

/** Group-aware transport that multiplexes messages for multiple Raft groups
  * over a shared network connection with `O(1)` per-group bounded mailbox
  * demultiplexing.
  *
  * Unlike an FS2 `Topic` broadcast (which fans every incoming envelope out to
  * all `G` group subscribers and drops envelopes arriving before lazy stream
  * subscription), `MultiGroupTransport` routes each inbound [[GroupEnvelope]]
  * directly to the target group's bounded [[Queue]], buffering startup traffic
  * cleanly until the group's event loop begins pulling from `receive`.
  */
class MultiGroupTransport[F[_]: Async] private (
    localId: NodeId,
    queuesRef: Ref[F, Map[GroupId, Queue[F, (NodeId, RaftMessage)]]],
    closedGroupsRef: Ref[F, Set[GroupId]],
    sendEnvelope: GroupEnvelope => F[Unit],
    maxQueueSize: Int
):

  /** Eagerly allocate (or re-activate) the bounded mailbox for `groupId` and
    * return its per-group [[RaftTransport]] view.
    */
  def registerGroup(groupId: GroupId): F[RaftTransport[F]] =
    closedGroupsRef.update(_ - groupId) *>
      getOrCreateQueue(groupId).as(transportForGroup(groupId))

  /** Remove a group's mailbox so buffered messages are released and late
    * packets for a removed group do not allocate new queues.
    */
  def unregisterGroup(groupId: GroupId): F[Unit] =
    closedGroupsRef.update(_ + groupId) *>
      queuesRef.update(_ - groupId)

  /** Create a per-group transport view backed by `groupId`'s bounded mailbox.
    */
  def transportForGroup(groupId: GroupId): RaftTransport[F] =
    new RaftTransport[F]:
      def send(to: NodeId, msg: RaftMessage): F[Unit] =
        sendEnvelope(GroupEnvelope(groupId, localId, msg, Some(to)))

      def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit] =
        msgs.toList.traverse_(msg =>
          sendEnvelope(GroupEnvelope(groupId, localId, msg, Some(to)))
        )

      def broadcast(msg: RaftMessage): F[Unit] =
        sendEnvelope(GroupEnvelope(groupId, localId, msg, None))

      def receive: Stream[F, (NodeId, RaftMessage)] =
        Stream
          .eval(
            closedGroupsRef.update(_ - groupId) *> getOrCreateQueue(groupId)
          )
          .flatMap(q => Stream.fromQueueUnterminated(q))

      def localAddress: F[String] =
        Async[F].pure(localId.value)

      def shutdown: F[Unit] =
        unregisterGroup(groupId)

  /** Route an incoming [[GroupEnvelope]] to its target group mailbox in `O(1)`.
    *
    * Envelopes addressed to another physical node (`envelope.to ==
    * Some(other)`) or to an explicitly removed group are ignored without
    * blocking the shared wire reader.
    */
  def dispatch(envelope: GroupEnvelope): F[Unit] =
    if !envelope.to.forall(_ == localId) then Async[F].unit
    else
      closedGroupsRef.get.flatMap { closed =>
        if closed.contains(envelope.groupId) then Async[F].unit
        else
          getOrCreateQueue(envelope.groupId).flatMap { q =>
            q.tryOffer((envelope.from, envelope.message)).void
          }
      }

  private def getOrCreateQueue(
      groupId: GroupId
  ): F[Queue[F, (NodeId, RaftMessage)]] =
    queuesRef.get.flatMap { current =>
      current.get(groupId) match
        case Some(existing) => Async[F].pure(existing)
        case None           =>
          Queue.bounded[F, (NodeId, RaftMessage)](maxQueueSize).flatMap { q =>
            queuesRef.modify { latest =>
              latest.get(groupId) match
                case Some(won) => (latest, won)
                case None      => (latest.updated(groupId, q), q)
            }
          }
    }

/** Companion for [[MultiGroupTransport]] providing factory methods. */
object MultiGroupTransport:

  /** Create a new [[MultiGroupTransport]] with bounded per-group mailboxes. */
  def apply[F[_]: Async](
      localId: NodeId,
      sendEnvelope: GroupEnvelope => F[Unit],
      maxQueueSize: Int = 1024
  ): F[MultiGroupTransport[F]] =
    require(maxQueueSize > 0, s"maxQueueSize must be > 0, got $maxQueueSize")
    for
      queues <- Ref.of[F, Map[GroupId, Queue[F, (NodeId, RaftMessage)]]](
        Map.empty
      )
      closed <- Ref.of[F, Set[GroupId]](Set.empty)
    yield new MultiGroupTransport[F](
      localId,
      queues,
      closed,
      sendEnvelope,
      maxQueueSize
    )
