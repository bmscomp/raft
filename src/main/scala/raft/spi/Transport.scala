package raft.spi

import cats.effect.kernel.{Async, Resource}
import fs2.Stream
import raft.state.NodeId
import raft.message.{RaftMessage, SimpleMessageCodec, MessageCodec, CodecError}

/** Service Provider Interface for network transport between RAFT nodes.
  *
  * Users implement this trait to integrate their preferred networking layer
  * (Netty, gRPC, Akka HTTP, etc.) with the RAFT consensus engine.
  */
trait Transport[F[_], Wire]:
  /** Send a wire-format message to a specific peer node in the cluster. */
  def send(to: NodeId, msg: Wire): F[Unit]
  
  /** Broadcast a wire-format message to all other nodes in the cluster. */
  def broadcast(msg: Wire): F[Unit]
  
  /** Stream of incoming messages from other nodes, paired with sender identity. */
  def receive: Stream[F, (NodeId, Wire)]
  
  /** Retrieve the local node's network address for logging and debugging. */
  def localAddress: F[String]
  
  /** Gracefully shutdown the transport, closing connections and releasing resources. */
  def shutdown: F[Unit]

/** Factory for creating Transport instances with proper resource lifecycle management. */
trait TransportFactory[F[_], Wire]:
  /** Create a new transport instance as a managed Cats Effect Resource. */
  def create(config: TransportConfig): Resource[F, Transport[F, Wire]]

/** Configuration for transport layer including peer addresses and timeouts. */
case class TransportConfig(
  localId: NodeId,
  bindAddress: String,
  peers: Map[NodeId, String],
  connectionTimeout: scala.concurrent.duration.FiniteDuration = 
    scala.concurrent.duration.Duration(5, "seconds"),
  retryAttempts: Int = 3
)

object Transport:
  /** Create a RaftTransport that automatically encodes/decodes using a SimpleMessageCodec. */
  def withCodec[F[_]: Async, Wire](
    underlying: Transport[F, Wire],
    codec: SimpleMessageCodec[Wire]
  ): RaftTransport[F] =
    new RaftTransport[F]:
      def send(to: NodeId, msg: RaftMessage): F[Unit] =
        underlying.send(to, codec.encode(msg))
      
      def broadcast(msg: RaftMessage): F[Unit] =
        underlying.broadcast(codec.encode(msg))
      
      def receive: Stream[F, (NodeId, RaftMessage)] =
        underlying.receive.evalMapFilter { case (from, raw) =>
          import cats.syntax.all.*
          codec.decode(raw) match
            case Right(msg) => Async[F].pure(Some((from, msg)))
            case Left(_)    => Async[F].pure(None)
        }
      
      def localAddress: F[String] = underlying.localAddress
      
      def shutdown: F[Unit] = underlying.shutdown
  
  /** Create a RaftTransport using an effectful MessageCodec for async encode/decode. */
  def withEffectfulCodec[F[_]: Async, Wire](
    underlying: Transport[F, Wire],
    codec: MessageCodec[F, Wire]
  ): RaftTransport[F] =
    new RaftTransport[F]:
      def send(to: NodeId, msg: RaftMessage): F[Unit] =
        import cats.syntax.all.*
        codec.encode(msg).flatMap(w => underlying.send(to, w))
      
      def broadcast(msg: RaftMessage): F[Unit] =
        import cats.syntax.all.*
        codec.encode(msg).flatMap(underlying.broadcast)
      
      def receive: Stream[F, (NodeId, RaftMessage)] =
        underlying.receive.evalMapFilter { case (from, raw) =>
          import cats.syntax.all.*
          codec.decode(raw).map(msg => Some((from, msg))).handleError(_ => None)
        }
      
      def localAddress: F[String] = underlying.localAddress
      
      def shutdown: F[Unit] = underlying.shutdown

/** High-level transport interface that works directly with RaftMessage types.
  * This is the interface consumed by RaftNode for all network operations.
  */
trait RaftTransport[F[_]]:
  def send(to: NodeId, msg: RaftMessage): F[Unit]
  def broadcast(msg: RaftMessage): F[Unit]
  def receive: Stream[F, (NodeId, RaftMessage)]
  def localAddress: F[String]
  def shutdown: F[Unit]
