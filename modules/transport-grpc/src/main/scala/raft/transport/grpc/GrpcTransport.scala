package raft.transport.grpc

import java.io.File
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.naming.ldap.LdapName
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

import cats.effect.{Async, Ref, Resource, Sync}
import cats.effect.implicits.*
import cats.effect.std.{AtomicCell, Dispatcher, Queue}
import cats.syntax.all.*
import com.google.protobuf.ByteString
import fs2.Stream
import io.grpc.{
  Context,
  Contexts,
  Grpc,
  ManagedChannel,
  Metadata,
  Server,
  ServerCall,
  ServerCallHandler,
  ServerInterceptor,
  ServerInterceptors,
  Status,
  StatusException,
  StatusRuntimeException
}
import io.grpc.netty.shaded.io.grpc.netty.{
  GrpcSslContexts,
  NettyChannelBuilder,
  NettyServerBuilder
}
import io.grpc.netty.shaded.io.netty.handler.ssl.{
  ClientAuth,
  SslContextBuilder,
  SslProvider
}
import io.grpc.stub.StreamObserver
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger

import raft.multigroup.{
  CoalescedHeartbeatConfig,
  GroupEnvelope,
  MultiGroupTransport
}
import raft.spi.{RaftTransport, Transport, TransportConfig, TransportFactory}
import raft.state.NodeId
import raft.transport.grpc.proto.*

/** Pure functional gRPC transport for Raft inter-node communication.
  *
  *   - **Lock-free pure channel pool (`AtomicCell`)**: replaces mutable
  *     `ConcurrentHashMap`, `@volatile`, and `synchronized` monitor locks with
  *     pure immutable [[GrpcTransport.ChannelPoolState]] transitions inside
  *     `AtomicCell[F, ...]` (**fixes FP7**).
  *   - **Single-RPC batch framing (`WireEnvelope.payloads`)**: `sendBatch`
  *     packs pipelined `AppendEntries` bursts into one HTTP/2 `WireEnvelope`
  *     RPC instead of `N` sequential unary calls (**fixes ST4**).
  *   - **`WireAck(accepted = false)` backpressure**: honors receiver queue
  *     saturation via bounded exponential backoff (`RESOURCE_EXHAUSTED`) rather
  *     than silently dropping messages (**fixes ST4**).
  *   - **mTLS X.509 peer identity enforcement**: under
  *     `useTls && tlsClientAuth && verifyPeerIdentity`, verifies that every
  *     inbound `WireEnvelope.sender_id` matches the authenticated client X.509
  *     certificate (`CN`, non-loopback `DNS` SAN, or SPIFFE `URI` SAN),
  *     rejecting spoofed `sender_id`s with `PERMISSION_DENIED` (**fixes ST4**).
  */
class GrpcTransport[F[_]: Async](
    config: GrpcConfig,
    transportConfig: TransportConfig,
    incomingQueue: Queue[F, (NodeId, Array[Byte])],
    logger: Logger[F],
    serverOpt: Option[Server] = None,
    poolCellOpt: Option[AtomicCell[F, GrpcTransport.ChannelPoolState]] = None,
    statsRefOpt: Option[Ref[F, GrpcTransportStats]] = None
) extends Transport[F, Array[Byte]]:

  def this(
      config: GrpcConfig,
      transportConfig: TransportConfig,
      incomingQueue: Queue[F, (NodeId, Array[Byte])]
  ) = this(config, transportConfig, incomingQueue, NoOpLogger[F], None)

  private val F = Async[F]

  private val lazyStateCell =
    new AtomicReference[
      Option[
        (
            AtomicCell[F, GrpcTransport.ChannelPoolState],
            Ref[F, GrpcTransportStats]
        )
      ]
    ](
      for
        cell <- poolCellOpt
        stats <- statsRefOpt
      yield (cell, stats)
    )

  private def stateHandles: F[
    (AtomicCell[F, GrpcTransport.ChannelPoolState], Ref[F, GrpcTransportStats])
  ] =
    F.defer {
      lazyStateCell.get() match
        case Some(pair) => F.pure(pair)
        case None       =>
          for
            freshCell <- AtomicCell[F].of(
              GrpcTransport.ChannelPoolState(
                peers = transportConfig.peers,
                channels = Map.empty
              )
            )
            freshStats <- Ref.of[F, GrpcTransportStats](GrpcTransportStats())
            resolved <- F.delay {
              val candidate = Some((freshCell, freshStats))
              if lazyStateCell.compareAndSet(None, candidate) then
                (freshCell, freshStats)
              else lazyStateCell.get().getOrElse((freshCell, freshStats))
            }
          yield resolved
    }

  private def poolCell: F[AtomicCell[F, GrpcTransport.ChannelPoolState]] =
    stateHandles.map(_._1)

  /** Inspect currently active outbound Netty channel target addresses. */
  def activeChannelAddresses: F[Set[String]] =
    poolCell.flatMap(_.get.map(_.channels.keySet))

  /** Inspect operational counters (`envelopesSent`, `batchesSent`,
    * `backpressureRetries`, `rejectedSpoofedEnvelopes`).
    */
  def transportStats: F[GrpcTransportStats] =
    stateHandles.flatMap(_._2.get)

  /** Dynamically updates the peer address routing table and shuts down any
    * `ManagedChannel`s whose target addresses are no longer in `newPeers`.
    */
  def updatePeers(newPeers: Map[NodeId, String]): F[Unit] =
    for
      cell <- poolCell
      evicted <- cell.evalModify { st =>
        val keepAddresses = newPeers.values.toSet
        val (retained, toEvict) = st.channels.partition { case (addr, _) =>
          keepAddresses.contains(addr)
        }
        F.pure(
          (
            st.copy(peers = newPeers, channels = retained),
            toEvict.values.toList
          )
        )
      }
      _ <-
        if evicted.isEmpty then F.unit
        else F.blocking(evicted.foreach(shutdownChannel(_, 1L)))
    yield ()

  def send(to: NodeId, msg: Array[Byte]): F[Unit] =
    for
      handles <- stateHandles
      (cell, statsRef) = handles
      st <- cell.get
      _ <- st.peers.get(to) match
        case Some(address) =>
          val envelope = WireEnvelope(
            senderId = transportConfig.localId.value,
            payload = ByteString.copyFrom(msg)
          )
          statsRef.update(s => s.copy(envelopesSent = s.envelopesSent + 1L)) *>
            sendWithRetry(to, address, envelope, attempt = 0, cell, statsRef)
              .handleErrorWith { err =>
                logger.warn(
                  s"gRPC delivery to ${to.value}@$address failed after retries: ${err.getMessage}"
                )
              }
        case None =>
          logger.warn(s"Unknown peer: ${to.value}")
    yield ()

  def sendBatch(to: NodeId, msgs: Seq[Array[Byte]]): F[Unit] =
    if msgs.isEmpty then F.unit
    else if msgs.size == 1 then send(to, msgs.head)
    else
      for
        handles <- stateHandles
        (cell, statsRef) = handles
        st <- cell.get
        _ <- st.peers.get(to) match
          case Some(address) =>
            val envelope = WireEnvelope(
              senderId = transportConfig.localId.value,
              payloads = msgs.map(ByteString.copyFrom)
            )
            statsRef.update(s =>
              s.copy(
                envelopesSent = s.envelopesSent + 1L,
                batchesSent = s.batchesSent + 1L
              )
            ) *>
              sendWithRetry(to, address, envelope, attempt = 0, cell, statsRef)
                .handleErrorWith { err =>
                  logger.warn(
                    s"gRPC batch delivery (${msgs.size} frames) to ${to.value}@$address failed: ${err.getMessage}"
                  )
                }
          case None =>
            logger.warn(s"Unknown peer: ${to.value}")
      yield ()

  def broadcast(msg: Array[Byte]): F[Unit] =
    poolCell
      .flatMap(_.get.map(_.peers.keys.toList))
      .flatMap(_.parTraverse_(peer => send(peer, msg)))

  def receive: Stream[F, (NodeId, Array[Byte])] =
    Stream.fromQueueUnterminated(incomingQueue)

  def localAddress: F[String] =
    Sync[F].delay {
      val boundPort = serverOpt.map(_.getPort).getOrElse(config.port)
      s"${config.host}:$boundPort"
    }

  def shutdown: F[Unit] =
    for
      _ <- logger.info("Shutting down gRPC transport")
      cell <- poolCell
      toClose <- cell.evalModify { st =>
        F.pure(
          (
            st.copy(channels = Map.empty, closed = true),
            st.channels.values.toList
          )
        )
      }
      _ <- F.blocking {
        toClose.foreach(shutdownChannel(_, 2L))
        serverOpt.foreach { srv =>
          val _ = srv.shutdown()
          if !srv.awaitTermination(5, TimeUnit.SECONDS) then
            val _ = srv.shutdownNow()
        }
      }
    yield ()

  private def sendWithRetry(
      to: NodeId,
      address: String,
      envelope: WireEnvelope,
      attempt: Int,
      cell: AtomicCell[F, GrpcTransport.ChannelPoolState],
      statsRef: Ref[F, GrpcTransportStats]
  ): F[Unit] =
    val singleAttempt =
      for
        channel <- getOrCreateChannel(address, cell)
        _ <- logger.debug(
          s"gRPC send to ${to.value}@$address (batch=${envelope.payloads.size}, attempt=${attempt + 1})"
        )
        ack <- F.fromFuture(
          F.delay(
            RaftServiceGrpc
              .stub(channel)
              .withDeadlineAfter(
                transportConfig.connectionTimeout.toMillis,
                TimeUnit.MILLISECONDS
              )
              .sendEnvelope(envelope)
          )
        )
        _ <-
          if ack.accepted then F.unit
          else
            statsRef.update(s =>
              s.copy(backpressureRetries = s.backpressureRetries + 1L)
            ) *>
              F.raiseError(
                Status.RESOURCE_EXHAUSTED
                  .withDescription(
                    s"Peer ${to.value} inbound queue saturated (WireAck accepted=false)"
                  )
                  .asRuntimeException()
              )
      yield ()

    singleAttempt.handleErrorWith { err =>
      GrpcTransport.transientStatusCode(err) match
        case Some(code) if attempt < config.maxRetries =>
          val shift = math.min(attempt, 6)
          val rawDelayMs = config.retryBackoff.toMillis * (1L << shift)
          val boundedDelayMs =
            math.min(rawDelayMs, config.maxRetryBackoff.toMillis)
          val sleepEff =
            if boundedDelayMs > 0L then F.sleep(boundedDelayMs.millis)
            else F.unit
          val resetEff =
            if code == Status.Code.RESOURCE_EXHAUSTED then F.unit
            else prepareChannelForRetry(address, cell)
          sleepEff *> resetEff *>
            sendWithRetry(to, address, envelope, attempt + 1, cell, statsRef)
        case _ =>
          F.raiseError(err)
    }

  private def prepareChannelForRetry(
      address: String,
      cell: AtomicCell[F, GrpcTransport.ChannelPoolState]
  ): F[Unit] =
    cell.evalModify { st =>
      st.channels.get(address) match
        case None     => F.pure((st, ()))
        case Some(ch) =>
          F.delay {
            if ch.isShutdown || ch.isTerminated then
              (st.copy(channels = st.channels - address), ())
            else
              ch.resetConnectBackoff()
              (st, ())
          }
    }

  private def getOrCreateChannel(
      address: String,
      cell: AtomicCell[F, GrpcTransport.ChannelPoolState]
  ): F[ManagedChannel] =
    cell.evalModify { st =>
      if st.closed then
        F.raiseError(
          Status.UNAVAILABLE
            .withDescription("GrpcTransport is shut down")
            .asRuntimeException()
        )
      else
        st.channels.get(address) match
          case Some(existing)
              if !existing.isShutdown && !existing.isTerminated =>
            F.pure((st, existing))
          case priorOpt =>
            F.blocking {
              priorOpt.foreach(shutdownChannel(_, 1L))
              val built = buildNettyChannel(address)
              (st.copy(channels = st.channels.updated(address, built)), built)
            }
    }

  private def buildNettyChannel(address: String): ManagedChannel =
    val base = NettyChannelBuilder
      .forTarget(address)
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
      if config.useTls then
        val baseSsl = GrpcSslContexts.configure(
          GrpcSslContexts.forClient(),
          SslProvider.JDK
        )
        val withTrust = config.tlsTrustCertPath.fold(baseSsl)(ca =>
          baseSsl.trustManager(new File(ca))
        )
        val withClientCert =
          (config.tlsCertPath, config.tlsKeyPath) match
            case (Some(cert), Some(key)) =>
              withTrust.keyManager(new File(cert), new File(key))
            case _ => withTrust
        base.sslContext(withClientCert.build())
      else base.usePlaintext()
    configured.build()

  private def shutdownChannel(ch: ManagedChannel, waitSeconds: Long): Unit =
    val _ = ch.shutdown()
    if !ch.awaitTermination(waitSeconds, TimeUnit.SECONDS) then
      val _ = ch.shutdownNow()

object GrpcTransport:

  private[grpc] final case class ChannelPoolState(
      peers: Map[NodeId, String],
      channels: Map[String, ManagedChannel],
      closed: Boolean = false
  )

  private val PeerIdsContextKey: Context.Key[Option[Set[String]]] =
    Context.keyWithDefault("raft-mtls-peer-ids", None)

  private[grpc] def transientStatusCode(err: Throwable): Option[Status.Code] =
    val codeOpt = err match
      case sre: StatusRuntimeException => Some(sre.getStatus.getCode)
      case se: StatusException         => Some(se.getStatus.getCode)
      case _                           => None
    codeOpt.filter(c =>
      c == Status.Code.UNAVAILABLE ||
        c == Status.Code.DEADLINE_EXCEEDED ||
        c == Status.Code.RESOURCE_EXHAUSTED
    )

  /** Extract authenticated peer identities (`CN`, non-loopback `DNS` SANs, and
    * SPIFFE `URI` SANs) from a verified X.509 client certificate.
    */
  private[grpc] def extractCertificateIdentities(
      cert: X509Certificate
  ): Set[String] =
    val cnIds: Set[String] =
      Try {
        val ldap = new LdapName(cert.getSubjectX500Principal.getName)
        ldap.getRdns.asScala.iterator
          .filter(_.getType.equalsIgnoreCase("CN"))
          .map(_.getValue.toString.trim)
          .filter(_.nonEmpty)
          .toSet
      }.getOrElse(Set.empty)

    val sanIds: Set[String] =
      Try {
        Option(cert.getSubjectAlternativeNames).toList
          .flatMap(_.asScala.toList)
          .flatMap { entry =>
            val items = entry.asScala.toList
            items match
              case (tag: Integer) :: value :: Nil =>
                val str = value.toString.trim
                tag.intValue() match
                  case 2
                      if str.nonEmpty && !str.equalsIgnoreCase("localhost") =>
                    List(str)
                  case 6 if str.nonEmpty =>
                    val tailSeg = str.split('/').lastOption.filter(_.nonEmpty)
                    str :: tailSeg.toList
                  case _ => Nil
              case _ => Nil
          }
          .toSet
      }.getOrElse(Set.empty)

    cnIds ++ sanIds

  /** Creates a client-only [[GrpcTransport]] with an eagerly allocated pure
    * `AtomicCell` channel pool (no listening server).
    */
  def createClient[F[_]: Async](
      grpcConfig: GrpcConfig,
      transportConfig: TransportConfig,
      incomingQueue: Queue[F, (NodeId, Array[Byte])]
  ): F[GrpcTransport[F]] =
    createClient(grpcConfig, transportConfig, incomingQueue, NoOpLogger[F])

  def createClient[F[_]: Async](
      grpcConfig: GrpcConfig,
      transportConfig: TransportConfig,
      incomingQueue: Queue[F, (NodeId, Array[Byte])],
      logger: Logger[F]
  ): F[GrpcTransport[F]] =
    for
      poolCell <- AtomicCell[F].of(
        ChannelPoolState(peers = transportConfig.peers, channels = Map.empty)
      )
      statsRef <- Ref.of[F, GrpcTransportStats](GrpcTransportStats())
    yield new GrpcTransport[F](
      grpcConfig,
      transportConfig,
      incomingQueue,
      logger,
      serverOpt = None,
      poolCellOpt = Some(poolCell),
      statsRefOpt = Some(statsRef)
    )

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
          poolCell <- AtomicCell[F].of(
            ChannelPoolState(
              peers = transportConfig.peers,
              channels = Map.empty
            )
          )
          statsRef <- Ref.of[F, GrpcTransportStats](GrpcTransportStats())
          enforceIdentity =
            grpcConfig.useTls &&
              grpcConfig.tlsClientAuth &&
              grpcConfig.verifyPeerIdentity
          service = buildService(
            queue,
            dispatcher,
            enforceIdentity,
            statsRef
          )
          server <- Sync[F].blocking(startServer(grpcConfig, service))
          _ <- logger.info(
            s"Started gRPC Raft server for ${transportConfig.localId.value} on ${grpcConfig.host}:${server.getPort}"
          )
        yield new GrpcTransport[F](
          grpcConfig,
          transportConfig,
          queue,
          logger,
          Some(server),
          Some(poolCell),
          Some(statsRef)
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

  /** Creates a live Netty-backed `(GrpcTransport[F], MultiGroupTransport[F])`
    * pair multiplexing arbitrary Raft groups over a single HTTP/2 channel per
    * peer host using binary [[ProtobufMultiGroupCodec]].
    */
  def multiGroupResource[F[_]: Async](
      grpcConfig: GrpcConfig,
      transportConfig: TransportConfig,
      coalescing: CoalescedHeartbeatConfig = CoalescedHeartbeatConfig(),
      maxGroupQueueSize: Int = 1024,
      loggerOpt: Option[Logger[F]] = None
  ): Resource[F, (GrpcTransport[F], MultiGroupTransport[F])] =
    val logger = loggerOpt.getOrElse(NoOpLogger[F])
    for
      raw <- resource(grpcConfig, transportConfig, logger)
      sendBatch: (Vector[GroupEnvelope] => F[Unit]) = envs =>
        if envs.isEmpty then Async[F].unit
        else
          val (unicast, bcast) = envs.partition(_.to.isDefined)
          val byPeer = unicast.groupBy(_.to.get).toList
          val sendUnicast = byPeer.parTraverse_ { case (dest, slice) =>
            raw.send(dest, ProtobufMultiGroupCodec.encodeBatch(slice))
          }
          val sendBcast =
            if bcast.isEmpty then Async[F].unit
            else
              bcast.traverse_(env =>
                raw.broadcast(ProtobufMultiGroupCodec.encode(env))
              )
          sendUnicast *> sendBcast
      mg <- MultiGroupTransport.resource[F](
        localId = transportConfig.localId,
        sendBatchEnvelopes = sendBatch,
        maxQueueSize = maxGroupQueueSize,
        coalescing = coalescing
      )
      _ <- raw.receive
        .evalMap { case (_, bytes) =>
          ProtobufMultiGroupCodec.decode(bytes) match
            case Right(envs) => mg.dispatchBatch(envs)
            case Left(err)   =>
              logger.warn(
                s"Dropping malformed Multi-Raft frame on ${transportConfig.localId.value}: ${err.message}"
              )
        }
        .compile
        .drain
        .background
    yield (raw, mg)

  private val peerIdentityInterceptor: ServerInterceptor =
    new ServerInterceptor:
      override def interceptCall[ReqT, RespT](
          call: ServerCall[ReqT, RespT],
          headers: Metadata,
          next: ServerCallHandler[ReqT, RespT]
      ): ServerCall.Listener[ReqT] =
        val idsOpt: Option[Set[String]] =
          Try {
            Option(call.getAttributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
              .flatMap(_.getPeerCertificates.headOption)
              .collect { case x509: X509Certificate =>
                extractCertificateIdentities(x509)
              }
          }.toOption.flatten
        val ctx = Context.current().withValue(PeerIdsContextKey, idsOpt)
        Contexts.interceptCall(ctx, call, headers, next)

  private def startServer(
      grpcConfig: GrpcConfig,
      service: RaftServiceGrpc.RaftService
  ): Server =
    val boundSvc = RaftServiceGrpc.bindService(service, ExecutionContext.global)
    val interceptedSvc =
      if grpcConfig.useTls && grpcConfig.tlsClientAuth then
        ServerInterceptors.intercept(boundSvc, peerIdentityInterceptor)
      else boundSvc

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
      .permitKeepAliveTime(
        math.min(5000L, grpcConfig.keepAliveInterval.toMillis),
        TimeUnit.MILLISECONDS
      )
      .permitKeepAliveWithoutCalls(true)
      .addService(interceptedSvc)

    val configured =
      if grpcConfig.useTls then
        (grpcConfig.tlsCertPath, grpcConfig.tlsKeyPath) match
          case (Some(cert), Some(key)) =>
            val baseSsl = GrpcSslContexts.configure(
              SslContextBuilder.forServer(new File(cert), new File(key)),
              SslProvider.JDK
            )
            val withTrust = grpcConfig.tlsTrustCertPath.fold(baseSsl)(ca =>
              baseSsl.trustManager(new File(ca))
            )
            val finalSsl =
              if grpcConfig.tlsClientAuth then
                withTrust.clientAuth(ClientAuth.REQUIRE)
              else withTrust
            base.sslContext(finalSsl.build())
          case _ =>
            throw new IllegalArgumentException(
              "GrpcConfig.useTls is true, but tlsCertPath or tlsKeyPath was not provided"
            )
      else base

    configured.build().start()

  private def buildService[F[_]: Async](
      incomingQueue: Queue[F, (NodeId, Array[Byte])],
      dispatcher: Dispatcher[F],
      enforcePeerIdentity: Boolean,
      statsRef: Ref[F, GrpcTransportStats]
  ): RaftServiceGrpc.RaftService =
    val F = Async[F]
    new RaftServiceGrpc.RaftService:
      override def sendEnvelope(request: WireEnvelope): Future[WireAck] =
        val trimmedSender = request.senderId.trim
        if trimmedSender.isEmpty then
          Future.failed(
            Status.INVALID_ARGUMENT
              .withDescription("WireEnvelope.sender_id must be non-empty")
              .asRuntimeException()
          )
        else
          val authorized =
            if !enforcePeerIdentity then true
            else PeerIdsContextKey.get().exists(_.contains(trimmedSender))

          if !authorized then
            val rejectEff = statsRef
              .update(s =>
                s.copy(rejectedSpoofedEnvelopes =
                  s.rejectedSpoofedEnvelopes + 1L
                )
              ) *>
              F.raiseError[WireAck](
                Status.PERMISSION_DENIED
                  .withDescription(
                    s"mTLS peer identity mismatch: sender_id='$trimmedSender' is not authorized by client X.509 certificate"
                  )
                  .asRuntimeException()
              )
            dispatcher.unsafeToFuture(rejectEff)
          else
            val sender = NodeId(trimmedSender)
            val frames: Vector[Array[Byte]] =
              if request.payloads.nonEmpty then
                request.payloads.iterator.map(_.toByteArray).toVector
              else Vector(request.payload.toByteArray)

            val offerAll = frames.foldLeftM(true) { (accOk, bytes) =>
              if !accOk then F.pure(false)
              else incomingQueue.tryOffer((sender, bytes))
            }

            dispatcher
              .unsafeToFuture(offerAll)
              .map(accepted => WireAck(accepted = accepted))(
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
