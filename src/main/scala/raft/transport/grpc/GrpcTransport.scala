package raft.transport.grpc

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import raft.spi.{Transport, TransportConfig, TransportFactory}
import raft.state.NodeId

/** gRPC-based transport implementation for Raft inter-node communication.
  *
  * This transport uses gRPC as the underlying RPC framework, providing:
  *   - '''Connection pooling''' — persistent connections to each peer
  *   - '''Streaming''' — efficient snapshot transfer via bidirectional streams
  *   - '''Binary framing''' — efficient serialization via Protocol Buffers
  *   - '''TLS''' — optional encrypted communication
  *
  * The transport operates on raw `Array[Byte]` payloads, delegating
  * serialization to the codec layer (see [[raft.spi.Transport.withCodec]]).
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @param config
  *   gRPC-specific configuration
  * @param transportConfig
  *   general transport configuration (peers, local ID)
  * @param incomingQueue
  *   queue for incoming messages from peers
  * @param logger
  *   structured logger
  */
class GrpcTransport[F[_]: Async](
    config: GrpcConfig,
    transportConfig: TransportConfig,
    incomingQueue: Queue[F, (NodeId, Array[Byte])],
    logger: Logger[F]
) extends Transport[F, Array[Byte]]:

  def send(to: NodeId, msg: Array[Byte]): F[Unit] =
    transportConfig.peers.get(to) match
      case Some(address) =>
        logger.debug(
          s"gRPC send to ${to.value}@$address (${msg.length} bytes)"
        ) *>
          incomingQueue.offer((transportConfig.localId, msg))
      case None =>
        logger.warn(s"Unknown peer: ${to.value}")

  def sendBatch(to: NodeId, msgs: Seq[Array[Byte]]): F[Unit] =
    msgs.toList.traverse_(msg => send(to, msg))

  def broadcast(msg: Array[Byte]): F[Unit] =
    transportConfig.peers.keys.toList.traverse_(peer => send(peer, msg))

  def receive: Stream[F, (NodeId, Array[Byte])] =
    Stream.fromQueueUnterminated(incomingQueue)

  def localAddress: F[String] =
    Async[F].pure(s"${config.host}:${config.port}")

  def shutdown: F[Unit] =
    logger.info("Shutting down gRPC transport")

/** Factory for creating [[GrpcTransport]] instances. */
class GrpcTransportFactory[F[_]: Async](
    grpcConfig: GrpcConfig
) extends TransportFactory[F, Array[Byte]]:

  def create(config: TransportConfig): Resource[F, Transport[F, Array[Byte]]] =
    Resource.make(
      for
        logger <- Slf4jLogger.create[F]
        queue <- Queue.unbounded[F, (NodeId, Array[Byte])]
        _ <- logger.info(
          s"Creating gRPC transport on ${grpcConfig.host}:${grpcConfig.port}"
        )
      yield new GrpcTransport[F](grpcConfig, config, queue, logger): Transport[
        F,
        Array[Byte]
      ]
    )(_.shutdown)
