package raft.multigroup

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic

import raft.state.{GroupId, NodeId}
import raft.message.RaftMessage
import raft.spi.RaftTransport

/** Group-aware transport that multiplexes messages for multiple Raft groups
  * over a shared network connection, preserving per-message unicast (`to`) vs.
  * broadcast (`None`) routing.
  */
class MultiGroupTransport[F[_]: Async](
    localId: NodeId,
    inbound: Topic[F, GroupEnvelope],
    sendEnvelope: GroupEnvelope => F[Unit]
):

  /** Create a per-group transport view that filters the shared inbound stream
    * to only deliver messages addressed to this group and node.
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
        inbound
          .subscribe(256)
          .filter(env => env.groupId == groupId && env.to.forall(_ == localId))
          .map(env => (env.from, env.message))

      def localAddress: F[String] =
        Async[F].pure(localId.value)

      def shutdown: F[Unit] =
        Async[F].unit

  /** Publish an incoming [[GroupEnvelope]] to all per-group subscribers. */
  def dispatch(envelope: GroupEnvelope): F[Unit] =
    inbound.publish1(envelope).void

/** Companion for [[MultiGroupTransport]] providing a factory method. */
object MultiGroupTransport:

  /** Create a new [[MultiGroupTransport]] backed by an FS2 [[Topic]]. */
  def apply[F[_]: Async](
      localId: NodeId,
      sendEnvelope: GroupEnvelope => F[Unit]
  ): F[MultiGroupTransport[F]] =
    Topic[F, GroupEnvelope].map { topic =>
      new MultiGroupTransport[F](localId, topic, sendEnvelope)
    }
