package raft.multigroup

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic

import raft.state.{GroupId, NodeId}
import raft.message.RaftMessage
import raft.spi.RaftTransport

/** Group-aware transport that multiplexes messages for multiple Raft groups
  * over a shared network connection.
  *
  * In a Multi-Raft deployment, opening one network connection per group pair
  * would be prohibitively expensive (thousands of connections per node).
  * Instead, all groups share the same underlying [[RaftTransport]] and rely on
  * [[GroupEnvelope]] for routing.
  *
  * This transport provides two key capabilities:
  *   1. '''Multiplexing''' — outgoing messages are wrapped in a
  *      [[GroupEnvelope]] before being sent over the shared connection.
  *   1. '''Demultiplexing''' — incoming [[GroupEnvelope]]s are routed to the
  *      correct per-group message queue via [[subscribe]].
  *
  * @tparam F
  *   the effect type (requires `Concurrent` for `Topic`)
  * @param localId
  *   this node's identifier
  * @param inbound
  *   topic for broadcasting incoming envelopes to per-group subscribers
  * @param sendEnvelope
  *   callback to send a [[GroupEnvelope]] over the wire
  * @see
  *   [[MultiRaftNode]] for the coordinator that creates per-group transports
  * @see
  *   [[GroupEnvelope]] for the multiplexing wrapper
  */
class MultiGroupTransport[F[_]: Async](
    localId: NodeId,
    inbound: Topic[F, GroupEnvelope],
    sendEnvelope: GroupEnvelope => F[Unit]
):

  /** Create a per-group transport view that filters the shared inbound stream
    * to only deliver messages for the specified group.
    *
    * @param groupId
    *   the group to create a transport for
    * @return
    *   a [[RaftTransport]] that sends/receives messages scoped to one group
    */
  def transportForGroup(groupId: GroupId): RaftTransport[F] =
    new RaftTransport[F]:
      def send(to: NodeId, msg: RaftMessage): F[Unit] =
        sendEnvelope(GroupEnvelope(groupId, localId, msg))

      def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit] =
        import cats.syntax.all.*
        msgs.traverse_(msg =>
          sendEnvelope(GroupEnvelope(groupId, localId, msg))
        )

      def broadcast(msg: RaftMessage): F[Unit] =
        sendEnvelope(GroupEnvelope(groupId, localId, msg))

      def receive: Stream[F, (NodeId, RaftMessage)] =
        inbound
          .subscribe(256)
          .filter(_.groupId == groupId)
          .map(env => (env.from, env.message))

      def localAddress: F[String] =
        Async[F].pure(localId.value)

      def shutdown: F[Unit] =
        Async[F].unit

  /** Publish an incoming [[GroupEnvelope]] to all per-group subscribers.
    *
    * Called by the [[MultiRaftNode]] when an envelope arrives from the network.
    *
    * @param envelope
    *   the incoming group-tagged message
    */
  def dispatch(envelope: GroupEnvelope): F[Unit] =
    inbound.publish1(envelope).void

/** Companion for [[MultiGroupTransport]] providing a factory method. */
object MultiGroupTransport:

  /** Create a new [[MultiGroupTransport]] backed by an FS2 [[Topic]].
    *
    * @tparam F
    *   the effect type (requires `Concurrent`)
    * @param localId
    *   this node's identifier
    * @param sendEnvelope
    *   callback to send envelopes over the wire
    * @return
    *   a new multi-group transport instance
    */
  def apply[F[_]: Async](
      localId: NodeId,
      sendEnvelope: GroupEnvelope => F[Unit]
  ): F[MultiGroupTransport[F]] =
    Topic[F, GroupEnvelope].map { topic =>
      new MultiGroupTransport[F](localId, topic, sendEnvelope)
    }
