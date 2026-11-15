package raft.transport.grpc

import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import cats.effect.{Async, Resource, Sync}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all.*
import com.google.protobuf.ByteString
import fs2.Stream
import io.grpc.{
  ManagedChannel,
  Server,
  Status,
  StatusException,
  StatusRuntimeException
}
import io.grpc.netty.shaded.io.grpc.netty.{
  NettyChannelBuilder,
  NettyServerBuilder
}
import io.grpc.stub.StreamObserver
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import raft.spi.{RaftTransport, Transport, TransportConfig, TransportFactory}
import raft.state.NodeId
import raft.transport.grpc.proto.*

/** gRPC-based transport implementation for Raft inter-node communication.
  *
  * Operates on raw `Array[Byte]` payloads wrapped in Protobuf [[WireEnvelope]]
  * frames over persistent HTTP/2 Netty channels, delegating consensus payload
  * serialization to [[ProtobufMessageCodec]] (or any custom
  * [[raft.message.SimpleMessageCodec]] via [[raft.spi.Transport.withCodec]]).
  *
  * Features:
  *   - '''Bounded inbound backpressure''': `Queue.bounded(maxInboundQueueSize)`
  *     suspends Netty RPC handlers before exhausting JVM heap under flood load.
  *   - '''Channel lifecycle hygiene''': `updatePeers` evicts and shuts down
  *     removed peer `ManagedChannel`s so membership rotations never leak
  *     sockets.
  *   - '''Bounded transient retry''': transient `UNAVAILABLE` and
  *     `DEADLINE_EXCEEDED` failures retry up to `config.maxRetries` with
  *     bounded exponential backoff before yielding cleanly to Raft's next
  *     heartbeat/election tick.
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

  private[grpc] def activeChannelAddresses: Set[String] =
    channels.keySet().asScala.toSet

  /** Dynamically updates the peer address routing table and shuts down any
    * `ManagedChannel`s whose target addresses are no longer in `newPeers`.
    */
  def updatePeers(newPeers: Map[NodeId, String]): F[Unit] =
    Sync[F].blocking {
      val keepAddresses = newPeers.values.toSet
      val existingKeys = peerMap.keySet().asScala.toList
      existingKeys.foreach { key =>
        if !newPeers.contains(key) then
          val _ = peerMap.remove(key)
      }
      newPeers.foreach { case (id, addr) =>
        val _ = peerMap.put(id, addr)
      }

      val evicted = channels
        .keySet()
        .asScala
        .toList
        .filterNot(keepAddresses.contains)
        .flatMap(addr => Option(channels.remove(addr)))

      evicted.foreach(shutdownChannel(_, 1L))
    }

  def send(to: NodeId, msg: Array[Byte]): F[Unit] =
    Option(peerMap.get(to)) match
      case Some(address) =>
        val envelope = WireEnvelope(
          senderId = transportConfig.localId.value,
          payload = ByteString.copyFrom(msg)
        )
        sendWithRetry(to, address, envelope, attempt = 0).handleErrorWith {
          err =>
            logger.warn(
              s"gRPC delivery to ${to.value}@$address failed after retries: ${err.getMessage}"
            )
        }

      case None =>
        logger.warn(s"Unknown peer: ${to.value}")

  private def sendWithRetry(
      to: NodeId,
      address: String,
      envelope: WireEnvelope,
      attempt: Int
  ): F[Unit] =
    val singleAttempt =
      for
        channel <- getOrCreateChannel(address)
        _ <- logger.debug(
          s"gRPC send to ${to.value}@$address (${envelope.payload.size()} bytes, attempt=${attempt + 1})"
        )
        _ <- Async[F].fromFuture(
          Sync[F].delay(
            RaftServiceGrpc.stub(channel).sendEnvelope(envelope)
          )
        )
      yield ()

    singleAttempt.handleErrorWith { err =>
      if attempt < config.maxRetries && isTransientGrpcError(err) then
        val shift = math.min(attempt, 6)
        val rawDelayMs = config.retryBackoff.toMillis * (1L << shift)
        val boundedDelayMs =
          math.min(rawDelayMs, config.maxRetryBackoff.toMillis)
        (if boundedDelayMs > 0L then Async[F].sleep(boundedDelayMs.millis)
         else Async[F].unit) *>
          prepareChannelForRetry(address) *>
          sendWithRetry(to, address, envelope, attempt + 1)
      else Async[F].raiseError(err)
    }

  private def isTransientGrpcError(err: Throwable): Boolean =
    val codeOpt = err match
      case sre: StatusRuntimeException => Some(sre.getStatus.getCode)
      case se: StatusException         => Some(se.getStatus.getCode)
      case _                           => None
    codeOpt.exists(c =>
      c == Status.Code.UNAVAILABLE || c == Status.Code.DEADLINE_EXCEEDED
    )

  private def prepareChannelForRetry(address: String): F[Unit] =
    Sync[F].delay {
      Option(channels.get(address)).foreach { ch =>
        if ch.isShutdown || ch.isTerminated then
          val _ = channels.remove(address, ch)
        else ch.resetConnectBackoff()
      }
    }

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
        channels.values().asScala.foreach(shutdownChannel(_, 2L))
        channels.clear()
        serverOpt.foreach { srv =>
          val _ = srv.shutdown()
          if !srv.awaitTermination(5, TimeUnit.SECONDS) then
            val _ = srv.shutdownNow()
        }
      }

  private def shutdownChannel(ch: ManagedChannel, waitSeconds: Long): Unit =
    val _ = ch.shutdown()
    if !ch.awaitTermination(waitSeconds, TimeUnit.SECONDS) then
      val _ = ch.shutdownNow()

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

  /** Creates a live Netty-backed [[GrpcTransport]] with a bounded inbound
    * queue, bound gRPC server, and automatic channel/server teardown.
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
          queue <- Queue.bounded[F, (NodeId, Array[Byte])](
            grpcConfig.maxInboundQueueSize
          )
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

  /** Creates a live Netty-backed `(GrpcTransport[F], RaftTransport[F])` pair
    * wired with [[ProtobufMessageCodec]] by default.
    */
  def raftResource[F[_]: Async](
      grpcConfig: GrpcConfig,
      transportConfig: TransportConfig
  ): Resource[F, (GrpcTransport[F], RaftTransport[F])] =
    raftResource(grpcConfig, transportConfig, NoOpLogger[F])

  def raftResource[F[_]: Async](
      grpcConfig: GrpcConfig,
      transportConfig: TransportConfig,
      logger: Logger[F]
  ): Resource[F, (GrpcTransport[F], RaftTransport[F])] =
    resource(grpcConfig, transportConfig, logger).map { raw =>
      (raw, Transport.withCodec(raw, ProtobufMessageCodec))
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
            .withDescription("Use SendEnvelope + ProtobufMessageCodec framing")
            .asRuntimeException()
        )

      override def requestVote(
          request: RequestVoteRequest
      ): Future[RequestVoteResponse] =
        Future.failed(
          Status.UNIMPLEMENTED
            .withDescription("Use SendEnvelope + ProtobufMessageCodec framing")
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
                .withDescription(
                  "Use SendEnvelope + ProtobufMessageCodec framing"
                )
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
