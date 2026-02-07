package raft.spi

import cats.effect.kernel.{Async, Resource}
import fs2.Stream
import raft.state.NodeId
import raft.message.{RaftMessage, SimpleMessageCodec, MessageCodec, CodecError}

/** Service Provider Interface for raw network transport between RAFT nodes.
  *
  * Users implement this trait to integrate their preferred networking layer
  * (Netty, gRPC, Akka HTTP, etc.) with the RAFT consensus engine. The transport
  * operates on a generic wire format `Wire` and is codec-agnostic — message
  * serialization is handled by [[MessageCodec]] or [[SimpleMessageCodec]].
  *
  * Use [[Transport.withCodec]] or [[Transport.withEffectfulCodec]] to obtain a
  * [[RaftTransport]] that works directly with [[RaftMessage]] types.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @tparam Wire
  *   the wire format type (e.g., `String`, `Array[Byte]`)
  * @see
  *   [[RaftTransport]] for the high-level message-typed transport
  * @see
  *   [[TransportConfig]] for connection and peer configuration
  */
trait Transport[F[_], Wire]:
  /** Send a wire-format message to a specific peer node.
    *
    * @param to
    *   the target node identifier
    * @param msg
    *   the wire-format payload to send
    */
  def send(to: NodeId, msg: Wire): F[Unit]

  /** Send a batch of wire-format messages to a specific peer node.
    *
    * Batching improves throughput by reducing network overhead.
    *
    * @param to
    *   the target node identifier
    * @param msgs
    *   the sequence of wire-format payloads to send
    */
  def sendBatch(to: NodeId, msgs: Seq[Wire]): F[Unit]

  /** Broadcast a wire-format message to all other nodes in the cluster.
    *
    * @param msg
    *   the wire-format payload to broadcast
    */
  def broadcast(msg: Wire): F[Unit]

  /** Stream of incoming messages from other nodes, paired with sender identity.
    *
    * @return
    *   an `fs2.Stream` of `(senderId, wirePayload)` tuples
    */
  def receive: Stream[F, (NodeId, Wire)]

  /** Retrieve the local node's network address for logging and diagnostics.
    *
    * @return
    *   the bind address or identifier of this node
    */
  def localAddress: F[String]

  /** Gracefully shut down the transport, closing connections and releasing
    * resources.
    */
  def shutdown: F[Unit]

/** Factory for creating [[Transport]] instances with proper resource lifecycle
  * management.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @tparam Wire
  *   the wire format type
  */
trait TransportFactory[F[_], Wire]:
  /** Create a new transport as a managed Cats Effect Resource.
    *
    * @param config
    *   the transport configuration (bind address, peers, timeouts)
    * @return
    *   a managed resource wrapping the created transport
    */
  def create(config: TransportConfig): Resource[F, Transport[F, Wire]]

/** Configuration for the network transport layer.
  *
  * @param localId
  *   the identifier of this node in the cluster
  * @param bindAddress
  *   the local address to bind the transport to
  * @param peers
  *   map of peer node IDs to their network addresses
  * @param connectionTimeout
  *   maximum duration to wait when establishing a connection
  * @param retryAttempts
  *   number of times to retry a failed send before giving up
  */
case class TransportConfig(
    localId: NodeId,
    bindAddress: String,
    peers: Map[NodeId, String],
    connectionTimeout: scala.concurrent.duration.FiniteDuration =
      scala.concurrent.duration.Duration(5, "seconds"),
    retryAttempts: Int = 3
)

/** Companion for [[Transport]] providing codec-integration factory methods. */
object Transport:
  /** Create a [[RaftTransport]] that wraps a raw transport with synchronous
    * encode/decode.
    *
    * Encoding failures are silently ignored on the receive side (malformed
    * messages are filtered out).
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @tparam Wire
    *   the raw wire format type
    * @param underlying
    *   the raw transport to wrap
    * @param codec
    *   the synchronous codec for message serialization
    * @return
    *   a high-level transport operating on [[RaftMessage]] types
    */
  def withCodec[F[_]: Async, Wire](
      underlying: Transport[F, Wire],
      codec: SimpleMessageCodec[Wire]
  ): RaftTransport[F] =
    new RaftTransport[F]:
      def send(to: NodeId, msg: RaftMessage): F[Unit] =
        underlying.send(to, codec.encode(msg))

      def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit] =
        underlying.sendBatch(to, msgs.map(codec.encode))

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

  /** Create a [[RaftTransport]] that wraps a raw transport with effectful
    * encode/decode.
    *
    * Decoding failures are caught and the message is silently dropped.
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @tparam Wire
    *   the raw wire format type
    * @param underlying
    *   the raw transport to wrap
    * @param codec
    *   the effectful codec for message serialization
    * @return
    *   a high-level transport operating on [[RaftMessage]] types
    */
  def withEffectfulCodec[F[_]: Async, Wire](
      underlying: Transport[F, Wire],
      codec: MessageCodec[F, Wire]
  ): RaftTransport[F] =
    new RaftTransport[F]:
      def send(to: NodeId, msg: RaftMessage): F[Unit] =
        import cats.syntax.all.*
        codec.encode(msg).flatMap(w => underlying.send(to, w))

      def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit] =
        import cats.syntax.all.*
        msgs.traverse(codec.encode).flatMap(ws => underlying.sendBatch(to, ws))

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

/** High-level transport interface that works directly with [[RaftMessage]]
  * types.
  *
  * This is the interface consumed by [[raft.RaftNode]] for all network
  * operations. It abstracts away wire-format details, providing a clean
  * message-typed API.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[Transport]] for the raw wire-format transport
  * @see
  *   [[Transport.withCodec]] for creating a `RaftTransport` from a raw
  *   transport
  */
trait RaftTransport[F[_]]:
  /** Send a RAFT message to a specific peer.
    *
    * @param to
    *   the target node identifier
    * @param msg
    *   the RAFT protocol message to send
    */
  def send(to: NodeId, msg: RaftMessage): F[Unit]

  /** Send a batch of RAFT messages to a specific peer.
    *
    * @param to
    *   the target node identifier
    * @param msgs
    *   the sequence of RAFT messages to send
    */
  def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit]

  /** Broadcast a RAFT message to all other nodes in the cluster.
    *
    * @param msg
    *   the RAFT protocol message to broadcast
    */
  def broadcast(msg: RaftMessage): F[Unit]

  /** Stream of incoming RAFT messages from other nodes.
    *
    * @return
    *   an `fs2.Stream` of `(senderId, message)` tuples
    */
  def receive: Stream[F, (NodeId, RaftMessage)]

  /** Retrieve the local node's network address.
    *
    * @return
    *   the bind address or identifier of this node
    */
  def localAddress: F[String]

  /** Gracefully shut down the transport. */
  def shutdown: F[Unit]
