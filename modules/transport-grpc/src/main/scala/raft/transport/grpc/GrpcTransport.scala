package raft.transport.grpc

import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

import cats.effect.{Async, Resource, Sync}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all.*
import com.google.protobuf.ByteString
import fs2.Stream
import io.grpc.{ManagedChannel, Server, Status}
import io.grpc.netty.shaded.io.grpc.netty.{
  NettyChannelBuilder,
  NettyServerBuilder
}
import io.grpc.stub.StreamObserver
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import raft.spi.{Transport, TransportConfig, TransportFactory}
import raft.state.NodeId
import raft.transport.grpc.proto.*

/** gRPC-based transport implementation for Raft inter-node communication.
  *
  * Operates on raw `Array[Byte]` payloads wrapped in Protobuf [[WireEnvelope]]
  * frames over persistent HTTP/2 Netty channels, delegating consensus payload
  * serialization to the codec layer (see [[raft.spi.Transport.withCodec]]).
  *
  * Network errors on individual sends are logged and absorbed so transient peer
  * outages or partitions never crash the consensus event loop; Raft's own
  * heartbeat and retry ticks handle retransmission.
  */
class GrpcTransport[F[_]: Async](
    config: GrpcConfig,
    transportConfig: TransportConfig,
    incomingQueue: Queue[F, (NodeId, Array[Byte])],
    logger: Logger[F],
    serverOpt: Option[Server] = None
) extends Transport[F, Array[Byte]]:

  def this(
      config: GrpcConfig,
      transportConfig: TransportConfig,
      incomingQueue: Queue[F, (NodeId, Array[Byte])]
  ) = this(config, transportConfig, incomingQueue, NoOpLogger[F], None)

  private val peerMap = new ConcurrentHashMap[NodeId, String]()
  transportConfig.peers.foreach { case (id, addr) =>
    val _ = peerMap.put(id, addr)
  }

  private val channels = new ConcurrentHashMap[String, ManagedChannel]()

  /** Dynamically updates the peer address routing table (useful for Joint
    * Consensus membership changes or ephemeral test port discovery).
    */
  def updatePeers(newPeers: Map[NodeId, String]): F[Unit] =
    Sync[F].delay {
      peerMap.clear()
      newPeers.foreach { case (id, addr) =>
        val _ = peerMap.put(id, addr)
      }
    }

  def send(to: NodeId, msg: Array[Byte]): F[Unit] =
    Option(peerMap.get(to)) match
      case Some(address) =>
        val rpcCall =
          for
            channel <- getOrCreateChannel(address)
            envelope = WireEnvelope(
              senderId = transportConfig.localId.value,
              payload = ByteString.copyFrom(msg)
            )
            _ <- logger.debug(
              s"gRPC send to ${to.value}@$address (${msg.length} bytes)"
            )
            _ <- Async[F].fromFuture(
              Sync[F].delay(
                RaftServiceGrpc.stub(channel).sendEnvelope(envelope)
              )
            )
          yield ()

        rpcCall.handleErrorWith { err =>
          logger.warn(
            s"gRPC delivery to ${to.value}@$address failed: ${err.getMessage}"
          )
        }

      case None =>
        logger.warn(s"Unknown peer: ${to.value}")

  def sendBatch(to: NodeId, msgs: Seq[Array[Byte]]): F[Unit] =
    msgs.toList.traverse_(msg => send(to, msg))

  def broadcast(msg: Array[Byte]): F[Unit] =
    Sync[F]
      .delay(peerMap.keySet().asScala.toList)
      .flatMap(_.traverse_(peer => send(peer, msg)))

  def receive: Stream[F, (NodeId, Array[Byte])] =
    Stream.fromQueueUnterminated(incomingQueue)

  def localAddress: F[String] =
    Sync[F].delay {
      val boundPort = serverOpt.map(_.getPort).getOrElse(config.port)
      s"${config.host}:$boundPort"
    }

  def shutdown: F[Unit] =
    logger.info("Shutting down gRPC transport") *>
      Sync[F].blocking {
        channels.values().asScala.foreach { ch =>
          val _ = ch.shutdown()
          if !ch.awaitTermination(2, TimeUnit.SECONDS) then
            val _ = ch.shutdownNow()
        }
        channels.clear()
        serverOpt.foreach { srv =>
          val _ = srv.shutdown()
          if !srv.awaitTermination(5, TimeUnit.SECONDS) then
            val _ = srv.shutdownNow()
        }
      }

  private def getOrCreateChannel(address: String): F[ManagedChannel] =
    Sync[F].delay {
      channels.computeIfAbsent(
        address,
        target => {
          val base = NettyChannelBuilder
            .forTarget(target)
            .maxInboundMessageSize(config.maxMessageSize)
            .keepAliveTime(
              config.keepAliveInterval.toMillis,
              TimeUnit.MILLISECONDS
            )
            .keepAliveTimeout(
              config.keepAliveTimeout.toMillis,
              TimeUnit.MILLISECONDS
            )
          val configured =
            if config.useTls then base else base.usePlaintext()
          configured.build()
        }
      )
    }

object GrpcTransport:

  /** Creates a live Netty-backed [[GrpcTransport]] with a bound gRPC server and
    * automatic channel/server teardown.
    */
  def resource[F[_]: Async](
      grpcConfig: GrpcConfig,
      transportConfig: TransportConfig
  ): Resource[F, GrpcTransport[F]] =
    resource(grpcConfig, transportConfig, NoOpLogger[F])

  def resource[F[_]: Async](
      grpcConfig: GrpcConfig,
      transportConfig: TransportConfig,
      logger: Logger[F]
  ): Resource[F, GrpcTransport[F]] =
    Dispatcher.parallel[F].flatMap { dispatcher =>
      Resource.make(
        for
          queue <- Queue.unbounded[F, (NodeId, Array[Byte])]
          service = buildService(queue, dispatcher)
          server <- Sync[F].blocking(startServer(grpcConfig, service))
          _ <- logger.info(
            s"Started gRPC Raft server for ${transportConfig.localId.value} on ${grpcConfig.host}:${server.getPort}"
          )
        yield new GrpcTransport[F](
          grpcConfig,
          transportConfig,
          queue,
          logger,
          Some(server)
        )
      )(_.shutdown)
    }

  private def startServer(
      grpcConfig: GrpcConfig,
      service: RaftServiceGrpc.RaftService
  ): Server =
    val base = NettyServerBuilder
      .forAddress(new InetSocketAddress(grpcConfig.host, grpcConfig.port))
      .maxInboundMessageSize(grpcConfig.maxMessageSize)
      .keepAliveTime(
        grpcConfig.keepAliveInterval.toMillis,
        TimeUnit.MILLISECONDS
      )
      .keepAliveTimeout(
        grpcConfig.keepAliveTimeout.toMillis,
        TimeUnit.MILLISECONDS
      )
      .addService(
        RaftServiceGrpc.bindService(service, ExecutionContext.global)
      )

    val configured =
      if grpcConfig.useTls then
        (grpcConfig.tlsCertPath, grpcConfig.tlsKeyPath) match
          case (Some(cert), Some(key)) =>
            base.useTransportSecurity(new File(cert), new File(key))
          case _ =>
            throw new IllegalArgumentException(
              "GrpcConfig.useTls is true, but tlsCertPath or tlsKeyPath was not provided"
            )
      else base

    configured.build().start()

  private def buildService[F[_]](
      incomingQueue: Queue[F, (NodeId, Array[Byte])],
      dispatcher: Dispatcher[F]
  ): RaftServiceGrpc.RaftService =
    new RaftServiceGrpc.RaftService:
      override def sendEnvelope(request: WireEnvelope): Future[WireAck] =
        val enqueue = incomingQueue.offer(
          (NodeId(request.senderId), request.payload.toByteArray)
        )
        dispatcher
          .unsafeToFuture(enqueue)
          .map(_ => WireAck(accepted = true))(
            ExecutionContext.parasitic
          )

      override def appendEntries(
          request: AppendEntriesRequest
      ): Future[AppendEntriesResponse] =
        Future.failed(
          Status.UNIMPLEMENTED
            .withDescription("Use SendEnvelope wire framing")
            .asRuntimeException()
        )

      override def requestVote(
          request: RequestVoteRequest
      ): Future[RequestVoteResponse] =
        Future.failed(
          Status.UNIMPLEMENTED
            .withDescription("Use SendEnvelope wire framing")
            .asRuntimeException()
        )

      override def installSnapshot(
          responseObserver: StreamObserver[InstallSnapshotResponse]
      ): StreamObserver[InstallSnapshotChunk] =
        new StreamObserver[InstallSnapshotChunk]:
          override def onNext(value: InstallSnapshotChunk): Unit = ()
          override def onError(t: Throwable): Unit = ()
          override def onCompleted(): Unit =
            responseObserver.onError(
              Status.UNIMPLEMENTED
                .withDescription("Use SendEnvelope wire framing")
                .asRuntimeException()
            )

/** Factory for creating live Netty-backed [[GrpcTransport]] instances. */
class GrpcTransportFactory[F[_]: Async](
    grpcConfig: GrpcConfig,
    logger: Logger[F]
) extends TransportFactory[F, Array[Byte]]:

  def this(grpcConfig: GrpcConfig) = this(grpcConfig, NoOpLogger[F])

  def create(config: TransportConfig): Resource[F, Transport[F, Array[Byte]]] =
    GrpcTransport
      .resource[F](grpcConfig, config, logger)
      .widen[Transport[F, Array[Byte]]]
