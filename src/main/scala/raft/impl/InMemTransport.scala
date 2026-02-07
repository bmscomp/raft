package raft.impl

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import raft.state.NodeId
import raft.message.RaftMessage
import raft.spi.RaftTransport

/** In-memory [[RaftTransport]] implementation for testing.
  *
  * Routes messages through in-process `Queue`s, providing instant delivery
  * without any network I/O. Each node gets its own `InMemTransport`, and peers
  * are wired together via [[registerPeer]] or the
  * [[InMemTransport.createCluster]] factory.
  *
  * Messages sent to unregistered peers are silently dropped.
  *
  * @tparam F
  *   the effect type (requires `Async`)
  * @param localId
  *   the identifier of this node
  * @see
  *   [[raft.spi.RaftTransport]] for the SPI contract
  * @see
  *   [[InMemTransport.createCluster]] for convenient multi-node setup
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

  /** Register a peer's inbox queue so this transport can send messages to it.
    *
    * @param peerId
    *   the identifier of the peer to register
    * @param peerQueue
    *   the peer's incoming message queue
    */
  def registerPeer(
      peerId: NodeId,
      peerQueue: Queue[F, (NodeId, RaftMessage)]
  ): F[Unit] =
    peers.update(_.updated(peerId, peerQueue))

/** Companion for [[InMemTransport]] providing factory methods. */
object InMemTransport:
  /** Create a single [[InMemTransport]] for the given node.
    *
    * The transport starts with no registered peers. Use
    * [[InMemTransport.registerPeer]] or [[createCluster]] to wire up peers.
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @param localId
    *   the identifier of this node
    * @return
    *   a new in-memory transport, wrapped in `F`
    */
  def apply[F[_]: Async](localId: NodeId): F[InMemTransport[F]] =
    for
      peers <- Ref.of[F, Map[NodeId, Queue[F, (NodeId, RaftMessage)]]](
        Map.empty
      )
      inbox <- Queue.unbounded[F, (NodeId, RaftMessage)]
    yield new InMemTransport[F](localId, peers, inbox)

  /** Create a fully connected cluster of in-memory transports for testing.
    *
    * Every node's transport is wired to every other node's inbox, enabling
    * instant message delivery across the cluster.
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @param nodeIds
    *   the list of node identifiers for the cluster
    * @return
    *   a map from node ID to its transport, with all peers registered
    */
  def createCluster[F[_]: Async](
      nodeIds: List[NodeId]
  ): F[Map[NodeId, InMemTransport[F]]] =
    for
      transports <- nodeIds
        .traverse(id => InMemTransport[F](id).map(id -> _))
        .map(_.toMap)
      // Wire up all peers
      _ <- transports.toList.traverse_ { case (id, transport) =>
        transports.toList.traverse_ { case (peerId, peerTransport) =>
          if id != peerId then
            transport.registerPeer(peerId, peerTransport.inbox)
          else Async[F].unit
        }
      }
    yield transports
