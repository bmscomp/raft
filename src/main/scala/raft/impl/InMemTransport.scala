package raft.impl

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import raft.state.NodeId
import raft.message.RaftMessage
import raft.spi.RaftTransport

/**
 * In-memory transport for testing (local process only).
 * 
 * Implements RaftTransport directly for simplified testing.
 */
class InMemTransport[F[_]: Async] private (
  localId: NodeId,
  peers: Ref[F, Map[NodeId, Queue[F, (NodeId, RaftMessage)]]],
  val inbox: Queue[F, (NodeId, RaftMessage)]
) extends RaftTransport[F]:
  
  def send(to: NodeId, msg: RaftMessage): F[Unit] =
    peers.get.flatMap { p =>
      p.get(to) match
        case Some(q) => q.offer((localId, msg))
        case None    => Async[F].unit
    }
  
  def broadcast(msg: RaftMessage): F[Unit] =
    peers.get.flatMap { p =>
      p.removed(localId).values.toList.traverse_(_.offer((localId, msg)))
    }
  
  def receive: Stream[F, (NodeId, RaftMessage)] =
    Stream.fromQueueUnterminated(inbox)
  
  def localAddress: F[String] = Async[F].pure(localId.value)
  
  def shutdown: F[Unit] = Async[F].unit
  
  def registerPeer(peerId: NodeId, peerQueue: Queue[F, (NodeId, RaftMessage)]): F[Unit] =
    peers.update(_.updated(peerId, peerQueue))

object InMemTransport:
  def apply[F[_]: Async](localId: NodeId): F[InMemTransport[F]] =
    for
      peers <- Ref.of[F, Map[NodeId, Queue[F, (NodeId, RaftMessage)]]](Map.empty)
      inbox <- Queue.unbounded[F, (NodeId, RaftMessage)]
    yield new InMemTransport[F](localId, peers, inbox)
  
  def createCluster[F[_]: Async](nodeIds: List[NodeId]): F[Map[NodeId, InMemTransport[F]]] =
    for
      transports <- nodeIds.traverse(id => InMemTransport[F](id).map(id -> _)).map(_.toMap)
      // Wire up all peers
      _ <- transports.toList.traverse_ { case (id, transport) =>
        transports.toList.traverse_ { case (peerId, peerTransport) =>
          if id != peerId then
            transport.registerPeer(peerId, peerTransport.inbox)
          else Async[F].unit
        }
      }
    yield transports
