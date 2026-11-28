package raft.transport.grpc

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.sys.process.{Process, ProcessLogger}

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import raft.codec.JsonCodec
import raft.message.RaftMessage.*
import raft.spi.{Transport, TransportConfig}
import raft.state.NodeId

class GrpcTransportSpec extends AnyFlatSpec with Matchers:

  val n1 = NodeId("node-1")
  val n2 = NodeId("node-2")
  val n3 = NodeId("node-3")
  given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private def mkTransport(
      grpcConfig: GrpcConfig = GrpcConfig(),
      localId: NodeId = n1,
      peers: Map[NodeId, String] =
        Map(n2 -> "127.0.0.1:9091", n3 -> "127.0.0.1:9092")
  ): IO[GrpcTransport[IO]] =
    for
      queue <- Queue.unbounded[IO, (NodeId, Array[Byte])]
      config = TransportConfig(
        localId,
        s"${grpcConfig.host}:${grpcConfig.port}",
        peers
      )
    yield new GrpcTransport[IO](grpcConfig, config, queue, logger)

  "GrpcTransport" should "report local address" in {
    val test = for
      transport <- mkTransport(GrpcConfig(host = "0.0.0.0", port = 9090))
      address <- transport.localAddress
    yield address shouldBe "0.0.0.0:9090"

    test.unsafeRunSync()
  }

  it should "handle unknown peer gracefully" in {
    val test = for
      transport <- mkTransport()
      unknownPeer = NodeId("unknown")
      _ <- transport.send(unknownPeer, "lost-msg".getBytes)
    yield succeed

    test.unsafeRunSync()
  }

  it should "absorb unreachable peer errors without throwing" in {
    val test = for
      transport <- mkTransport(
        GrpcConfig(host = "127.0.0.1", port = 0),
        n1,
        Map(n2 -> "127.0.0.1:1")
      )
      _ <- transport.send(n2, "unreachable".getBytes(StandardCharsets.UTF_8))
      _ <- transport.shutdown
    yield succeed

    test.unsafeRunSync()
  }

  it should "shutdown gracefully" in {
    val test = for
      transport <- mkTransport()
      _ <- transport.shutdown
    yield succeed

    test.unsafeRunSync()
  }

  it should "deliver unicast, batch, and broadcast Raft messages over live Netty HTTP/2 sockets" in {
    val ephemeral = GrpcConfig(host = "127.0.0.1", port = 0)
    val resources = for
      t1 <- GrpcTransport.resource[IO](
        ephemeral,
        TransportConfig(n1, "127.0.0.1:0", Map.empty),
        logger
      )
      t2 <- GrpcTransport.resource[IO](
        ephemeral,
        TransportConfig(n2, "127.0.0.1:0", Map.empty),
        logger
      )
      t3 <- GrpcTransport.resource[IO](
        ephemeral,
        TransportConfig(n3, "127.0.0.1:0", Map.empty),
        logger
      )
    yield (t1, t2, t3)

    val test = resources.use { case (t1, t2, t3) =>
      for
        addr1 <- t1.localAddress
        addr2 <- t2.localAddress
        addr3 <- t3.localAddress
        _ <- t1.updatePeers(Map(n2 -> addr2, n3 -> addr3))
        _ <- t2.updatePeers(Map(n1 -> addr1, n3 -> addr3))
        _ <- t3.updatePeers(Map(n1 -> addr1, n2 -> addr2))

        // 1. Typed RaftMessage over Transport.withCodec + live gRPC wire
        typed1 = Transport.withCodec(t1, JsonCodec.byteCodec)
        typed2 = Transport.withCodec(t2, JsonCodec.byteCodec)
        voteReq = RequestVoteRequest(
          term = 7L,
          candidateId = n1,
          lastLogIndex = 42L,
          lastLogTerm = 6L,
          isPreVote = false
        )
        _ <- typed1.send(n2, voteReq)
        receivedVote <- typed2.receive.head.compile.lastOrError
          .timeout(5.seconds)
        _ = receivedVote shouldBe (n1 -> voteReq)

        // 2. Batch delivery from n2 -> n1
        batchPayloads = Seq("msg-a", "msg-b", "msg-c").map(
          _.getBytes(StandardCharsets.UTF_8)
        )
        _ <- t2.sendBatch(n1, batchPayloads)
        receivedBatch <- t1.receive
          .take(3)
          .compile
          .toList
          .timeout(5.seconds)
        _ = receivedBatch.map(_._1) shouldBe List(n2, n2, n2)
        _ = receivedBatch.map(p =>
          new String(p._2, StandardCharsets.UTF_8)
        ) shouldBe List("msg-a", "msg-b", "msg-c")

        // 3. Broadcast from n1 -> {n2, n3}
        bcastBytes = "heartbeat-tick".getBytes(StandardCharsets.UTF_8)
        _ <- t1.broadcast(bcastBytes)
        from2 <- t2.receive.head.compile.lastOrError.timeout(5.seconds)
        from3 <- t3.receive.head.compile.lastOrError.timeout(5.seconds)
        _ = from2._1 shouldBe n1
        _ = new String(
          from2._2,
          StandardCharsets.UTF_8
        ) shouldBe "heartbeat-tick"
        _ = from3._1 shouldBe n1
        _ = new String(
          from3._2,
          StandardCharsets.UTF_8
        ) shouldBe "heartbeat-tick"
      yield ()
    }

    test.unsafeRunSync()
  }

  "GrpcConfig" should "have sensible defaults" in {
    val config = GrpcConfig()
    config.host shouldBe "0.0.0.0"
    config.port shouldBe 9090
    config.maxMessageSize shouldBe 4 * 1024 * 1024
    config.useTls shouldBe false
    config.maxRetries shouldBe 5
  }

  it should "be customizable" in {
    val config = GrpcConfig(
      host = "10.0.0.1",
      port = 5000,
      maxMessageSize = 1024 * 1024,
      useTls = true,
      tlsCertPath = Some("/etc/certs/raft.pem"),
      tlsKeyPath = Some("/etc/certs/raft.key"),
      tlsTrustCertPath = Some("/etc/certs/ca.pem"),
      tlsClientAuth = true,
      maxRetries = 10
    )
    config.host shouldBe "10.0.0.1"
    config.port shouldBe 5000
    config.useTls shouldBe true
    config.tlsCertPath shouldBe Some("/etc/certs/raft.pem")
    config.tlsTrustCertPath shouldBe Some("/etc/certs/ca.pem")
    config.tlsClientAuth shouldBe true
    config.maxRetries shouldBe 10
  }

  it should "fail fast when useTls is true without tlsCertPath/tlsKeyPath" in {
    val badCfg = GrpcConfig(host = "127.0.0.1", port = 0, useTls = true)
    intercept[IllegalArgumentException] {
      GrpcTransport
        .resource[IO](badCfg, TransportConfig(n1, "127.0.0.1:0", Map.empty))
        .use_
        .unsafeRunSync()
    }
  }

  "GrpcTransportFactory" should "create transport as managed resource with bound ephemeral port" in {
    val test = for
      factory <- IO(
        new GrpcTransportFactory[IO](
          GrpcConfig(host = "127.0.0.1", port = 0),
          logger
        )
      )
      config = TransportConfig(
        n1,
        "127.0.0.1:0",
        Map(n2 -> "127.0.0.1:9091")
      )
      _ <- factory.create(config).use { transport =>
        for
          addr <- transport.localAddress
          _ = addr should startWith("127.0.0.1:")
          boundPort = addr.stripPrefix("127.0.0.1:").toInt
          _ = boundPort should be > 0
        yield ()
      }
    yield succeed

    test.unsafeRunSync()
  }

  "GrpcTransport.raftResource" should "deliver binary Protobuf messages and evict channels on updatePeers" in {
    val ephemeral = GrpcConfig(host = "127.0.0.1", port = 0)
    val resources = for
      pair1 <- GrpcTransport.raftResource[IO](
        ephemeral,
        TransportConfig(n1, "127.0.0.1:0", Map.empty),
        logger
      )
      pair2 <- GrpcTransport.raftResource[IO](
        ephemeral,
        TransportConfig(n2, "127.0.0.1:0", Map.empty),
        logger
      )
      pair3 <- GrpcTransport.raftResource[IO](
        ephemeral,
        TransportConfig(n3, "127.0.0.1:0", Map.empty),
        logger
      )
    yield (pair1, pair2, pair3)

    val test = resources.use { case ((raw1, raft1), (raw2, raft2), (raw3, _)) =>
      for
        addr2 <- raw2.localAddress
        addr3 <- raw3.localAddress
        _ <- raw1.updatePeers(Map(n2 -> addr2, n3 -> addr3))

        msg = AppendEntriesResponse(
          term = 9L,
          success = false,
          matchIndex = 0L,
          conflictIndex = Some(14L),
          conflictTerm = Some(3L)
        )
        _ <- raft1.send(n2, msg)
        _ <- raft1.send(n3, msg)

        got <- raft2.receive.head.compile.lastOrError.timeout(5.seconds)
        _ = got shouldBe (n1 -> msg)
        addrsBefore <- raw1.activeChannelAddresses
        _ = addrsBefore should contain allOf (addr2, addr3)

        // Remove n3 from peer map -> channel to addr3 must be evicted and shut down
        _ <- raw1.updatePeers(Map(n2 -> addr2))
        addrsAfter <- raw1.activeChannelAddresses
        _ = addrsAfter shouldBe Set(addr2)
      yield ()
    }

    test.unsafeRunSync()
  }

  it should "retry transient UNAVAILABLE sends and succeed once peer starts" in {
    // Pick a free TCP port, release it, start sending with retryBackoff, and start server mid-flight
    val probeSocket = new java.net.ServerSocket(0)
    val reservedPort = probeSocket.getLocalPort
    probeSocket.close()

    val senderCfg = GrpcConfig(
      host = "127.0.0.1",
      port = 0,
      maxRetries = 8,
      retryBackoff = 50.millis,
      maxRetryBackoff = 150.millis
    )
    val receiverCfg = GrpcConfig(host = "127.0.0.1", port = reservedPort)

    val test = GrpcTransport
      .raftResource[IO](
        senderCfg,
        TransportConfig(
          n1,
          "127.0.0.1:0",
          Map(n2 -> s"127.0.0.1:$reservedPort")
        ),
        logger
      )
      .use { case (_, senderRaft) =>
        for
          sendFiber <- senderRaft.send(n2, TimeoutNowRequest(4L, n1)).start
          _ <- IO.sleep(80.millis)
          received <- GrpcTransport
            .raftResource[IO](
              receiverCfg,
              TransportConfig(n2, s"127.0.0.1:$reservedPort", Map.empty),
              logger
            )
            .use { case (_, receiverRaft) =>
              sendFiber.joinWithNever *>
                receiverRaft.receive.head.compile.lastOrError.timeout(5.seconds)
            }
        yield received shouldBe (n1 -> TimeoutNowRequest(4L, n1))
      }

    test.unsafeRunSync()
  }

  it should "validate GrpcConfig via error-accumulating Validated smart constructor" in {
    val bad = GrpcConfig.validate(
      host = "   ",
      port = 70000,
      maxMessageSize = 0,
      keepAliveInterval = 0.millis,
      keepAliveTimeout = (-1).millis,
      useTls = true,
      tlsCertPath = None,
      tlsKeyPath = Some("  "),
      maxRetries = -1,
      retryBackoff = 100.millis,
      maxRetryBackoff = 10.millis,
      maxInboundQueueSize = 0
    )
    bad.isValid shouldBe false
    bad.toEither.left.toOption.get.size shouldBe 10

    val good = GrpcConfig.validate(
      host = "127.0.0.1",
      port = 0,
      useTls = false,
      retryBackoff = 10.millis,
      maxRetryBackoff = 50.millis
    )
    good.isValid shouldBe true
  }

  it should "pack sendBatch into a single WireEnvelope.payloads RPC and retry on WireAck(accepted = false) backpressure (FP7, ST4)" in {
    val senderCfg = GrpcConfig(
      host = "127.0.0.1",
      port = 0,
      maxRetries = 10,
      retryBackoff = 25.millis,
      maxRetryBackoff = 80.millis
    )
    // Receiver queue capacity = 5 for the batch test, then = 1 for saturation backpressure!
    val batchTest = (for
      sender <- GrpcTransport.resource[IO](
        senderCfg,
        TransportConfig(n1, "127.0.0.1:0", Map.empty),
        logger
      )
      receiver <- GrpcTransport.resource[IO](
        GrpcConfig(host = "127.0.0.1", port = 0, maxInboundQueueSize = 5),
        TransportConfig(n2, "127.0.0.1:0", Map.empty),
        logger
      )
    yield (sender, receiver)).use { case (sender, receiver) =>
      for
        rAddr <- receiver.localAddress
        _ <- sender.updatePeers(Map(n2 -> rAddr))
        frames = (1 to 5).map(i => s"frame-$i".getBytes(StandardCharsets.UTF_8))
        _ <- sender.sendBatch(n2, frames)
        got <- receiver.receive.take(5).compile.toVector.timeout(5.seconds)
        stats <- sender.transportStats
      yield
        got.map(p => new String(p._2, StandardCharsets.UTF_8)) shouldBe
          Vector("frame-1", "frame-2", "frame-3", "frame-4", "frame-5")
        stats.batchesSent shouldBe 1L
        stats.envelopesSent shouldBe 1L
    }

    val backpressureTest = (for
      sender <- GrpcTransport.resource[IO](
        senderCfg,
        TransportConfig(n1, "127.0.0.1:0", Map.empty),
        logger
      )
      tinyReceiver <- GrpcTransport.resource[IO](
        GrpcConfig(host = "127.0.0.1", port = 0, maxInboundQueueSize = 1),
        TransportConfig(n2, "127.0.0.1:0", Map.empty),
        logger
      )
    yield (sender, tinyReceiver)).use { case (sender, tinyReceiver) =>
      for
        rAddr <- tinyReceiver.localAddress
        _ <- sender.updatePeers(Map(n2 -> rAddr))
        // Fill the single slot in tinyReceiver's inbound queue
        _ <- sender.send(n2, "first".getBytes(StandardCharsets.UTF_8))
        // Second send hits WireAck(accepted = false) -> backs off & retries until consumer drains!
        secondFiber <- sender
          .send(n2, "second".getBytes(StandardCharsets.UTF_8))
          .start
        _ <- IO.sleep(70.millis)
        firstGot <- tinyReceiver.receive.head.compile.lastOrError
          .timeout(5.seconds)
        _ <- secondFiber.joinWithNever
        secondGot <- tinyReceiver.receive.head.compile.lastOrError
          .timeout(5.seconds)
        stats <- sender.transportStats
      yield
        new String(firstGot._2, StandardCharsets.UTF_8) shouldBe "first"
        new String(secondGot._2, StandardCharsets.UTF_8) shouldBe "second"
        stats.backpressureRetries should be >= 1L
    }

    (batchTest *> backpressureTest).unsafeRunSync()
  }

  it should "enforce mTLS X.509 client certificate identity against spoofed sender_id (ST4)" in {
    def deleteRec(p: Path): Unit =
      if Files.exists(p) then
        if Files.isDirectory(p) then
          val s = Files.list(p)
          try s.iterator().forEachRemaining(deleteRec)
          finally s.close()
        val _ = Files.deleteIfExists(p)

    def runOpenSsl(cwd: Path, args: Seq[String]): Unit =
      val log = ProcessLogger(_ => (), _ => ())
      val code = Process(args, cwd.toFile).!(log)
      require(code == 0, s"openssl failed ($code): ${args.mkString(" ")}")

    val pkiDir = Files.createTempDirectory("raft-grpc-mtls-id-")
    try
      val caKey = pkiDir.resolve("ca.key")
      val caCrt = pkiDir.resolve("ca.crt")
      runOpenSsl(
        pkiDir,
        Seq(
          "openssl",
          "req",
          "-x509",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          caKey.toString,
          "-out",
          caCrt.toString,
          "-days",
          "2",
          "-subj",
          "/CN=RaftTestCA"
        )
      )

      def issueCert(id: String): (Path, Path) =
        val rawKey = pkiDir.resolve(s"$id-raw.key")
        val key = pkiDir.resolve(s"$id.key")
        val csr = pkiDir.resolve(s"$id.csr")
        val crt = pkiDir.resolve(s"$id.crt")
        val ext = pkiDir.resolve(s"$id.ext")
        val _ = Files.writeString(
          ext,
          s"subjectAltName=IP:127.0.0.1,DNS:localhost,URI:spiffe://raft.local/ns/default/sa/$id\nextendedKeyUsage=serverAuth,clientAuth\n"
        )
        runOpenSsl(
          pkiDir,
          Seq(
            "openssl",
            "req",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-keyout",
            rawKey.toString,
            "-out",
            csr.toString,
            "-subj",
            s"/CN=$id"
          )
        )
        runOpenSsl(
          pkiDir,
          Seq(
            "openssl",
            "pkcs8",
            "-topk8",
            "-nocrypt",
            "-in",
            rawKey.toString,
            "-out",
            key.toString
          )
        )
        runOpenSsl(
          pkiDir,
          Seq(
            "openssl",
            "x509",
            "-req",
            "-in",
            csr.toString,
            "-CA",
            caCrt.toString,
            "-CAkey",
            caKey.toString,
            "-CAcreateserial",
            "-out",
            crt.toString,
            "-days",
            "2",
            "-extfile",
            ext.toString
          )
        )
        (crt, key)

      val (n1Crt, n1Key) = issueCert("node-1")
      val (n2Crt, n2Key) = issueCert("node-2")

      val n1GrpcCfg = GrpcConfig(
        host = "127.0.0.1",
        port = 0,
        useTls = true,
        tlsCertPath = Some(n1Crt.toString),
        tlsKeyPath = Some(n1Key.toString),
        tlsTrustCertPath = Some(caCrt.toString),
        tlsClientAuth = true,
        verifyPeerIdentity = true
      )
      val n2GrpcCfg = GrpcConfig(
        host = "127.0.0.1",
        port = 0,
        useTls = true,
        tlsCertPath = Some(n2Crt.toString),
        tlsKeyPath = Some(n2Key.toString),
        tlsTrustCertPath = Some(caCrt.toString),
        tlsClientAuth = true,
        verifyPeerIdentity = true,
        maxRetries = 1
      )

      val prog = (for
        srv1 <- GrpcTransport.resource[IO](
          n1GrpcCfg,
          TransportConfig(n1, "127.0.0.1:0", Map.empty),
          logger
        )
        honestPeer2 <- GrpcTransport.resource[IO](
          n2GrpcCfg,
          TransportConfig(n2, "127.0.0.1:0", Map.empty),
          logger
        )
        // Compromised node-2 holds CA-valid node-2.crt, but spoofs localId = node-3!
        spoofingPeer2 <- GrpcTransport.resource[IO](
          n2GrpcCfg,
          TransportConfig(n3, "127.0.0.1:0", Map.empty),
          logger
        )
      yield (srv1, honestPeer2, spoofingPeer2)).use {
        case (srv1, honestPeer2, spoofingPeer2) =>
          for
            addr1 <- srv1.localAddress
            _ <- honestPeer2.updatePeers(Map(n1 -> addr1))
            _ <- spoofingPeer2.updatePeers(Map(n1 -> addr1))

            // 1. Spoofed sender_id = "node-3" using node-2.crt MUST be rejected with PERMISSION_DENIED
            _ <- spoofingPeer2.send(
              n1,
              "spoofed-vote".getBytes(StandardCharsets.UTF_8)
            )
            // 2. Honest sender_id = "node-2" using node-2.crt MUST succeed
            _ <- honestPeer2.send(
              n1,
              "authentic-vote".getBytes(StandardCharsets.UTF_8)
            )
            received <- srv1.receive.head.compile.lastOrError.timeout(5.seconds)
            srvStats <- srv1.transportStats
          yield
            received._1 shouldBe n2
            new String(received._2, StandardCharsets.UTF_8) shouldBe
              "authentic-vote"
            srvStats.rejectedSpoofedEnvelopes shouldBe 1L
      }

      prog.unsafeRunSync()
    finally deleteRec(pkiDir)
  }

  it should "classify transient gRPC status codes, handle createClient/sendBatch edge cases, and enforce RPC guardrails" in {
    import io.grpc.{ManagedChannelBuilder, Status, StatusRuntimeException}
    import io.grpc.stub.StreamObserver
    import raft.transport.grpc.proto as pb
    import java.util.concurrent.CountDownLatch
    import java.util.concurrent.TimeUnit

    GrpcTransport.transientStatusCode(
      Status.UNAVAILABLE.asRuntimeException()
    ) shouldBe Some(Status.Code.UNAVAILABLE)
    GrpcTransport.transientStatusCode(
      Status.DEADLINE_EXCEEDED.asException()
    ) shouldBe Some(Status.Code.DEADLINE_EXCEEDED)
    GrpcTransport.transientStatusCode(
      Status.RESOURCE_EXHAUSTED.asRuntimeException()
    ) shouldBe Some(Status.Code.RESOURCE_EXHAUSTED)
    GrpcTransport.transientStatusCode(
      Status.PERMISSION_DENIED.asRuntimeException()
    ) shouldBe None
    GrpcTransport.transientStatusCode(new RuntimeException("boom")) shouldBe
      None

    val clientTest = for
      q <- Queue.unbounded[IO, (NodeId, Array[Byte])]
      client <- GrpcTransport.createClient[IO](
        GrpcConfig(host = "127.0.0.1", port = 0, maxRetries = 0),
        TransportConfig(n1, "127.0.0.1:0", Map(n2 -> "127.0.0.1:1")),
        q
      )
      _ <- client.sendBatch(n2, Seq.empty)
      _ <- client.sendBatch(
        NodeId("unknown-peer"),
        Seq(Array[Byte](1), Array[Byte](2))
      )
      _ <- client.shutdown
      _ <- client.send(n2, Array[Byte](7))
      _ <- client.sendBatch(n2, Seq(Array[Byte](7), Array[Byte](8)))
    yield succeed

    val serverGuardrailsTest = GrpcTransport
      .resource[IO](
        GrpcConfig(host = "127.0.0.1", port = 0),
        TransportConfig(n1, "127.0.0.1:0", Map.empty),
        logger
      )
      .use { srv =>
        srv.localAddress.flatMap { addr =>
          IO.blocking {
            val ch = ManagedChannelBuilder
              .forTarget(addr)
              .usePlaintext()
              .build()
            try
              val blocking = pb.RaftServiceGrpc.blockingStub(ch)

              val badSenderEx = intercept[StatusRuntimeException] {
                val _ = blocking.sendEnvelope(
                  pb.WireEnvelope(senderId = "   ")
                )
              }
              badSenderEx.getStatus.getCode shouldBe Status.Code.INVALID_ARGUMENT

              val aeEx = intercept[StatusRuntimeException] {
                val _ = blocking.appendEntries(pb.AppendEntriesRequest())
              }
              aeEx.getStatus.getCode shouldBe Status.Code.UNIMPLEMENTED

              val rvEx = intercept[StatusRuntimeException] {
                val _ = blocking.requestVote(pb.RequestVoteRequest())
              }
              rvEx.getStatus.getCode shouldBe Status.Code.UNIMPLEMENTED

              val asyncStub = pb.RaftServiceGrpc.stub(ch)
              val latch = new CountDownLatch(1)
              var snapErr: Throwable = null
              val reqObs = asyncStub.installSnapshot(
                new StreamObserver[pb.InstallSnapshotResponse]:
                  override def onNext(v: pb.InstallSnapshotResponse): Unit = ()
                  override def onError(t: Throwable): Unit =
                    snapErr = t
                    latch.countDown()
                  override def onCompleted(): Unit = latch.countDown()
              )
              reqObs.onNext(pb.InstallSnapshotChunk())
              reqObs.onCompleted()
              latch.await(5, TimeUnit.SECONDS) shouldBe true
              snapErr shouldBe a[StatusRuntimeException]
              snapErr
                .asInstanceOf[StatusRuntimeException]
                .getStatus
                .getCode shouldBe Status.Code.UNIMPLEMENTED
            finally
              val _ = ch.shutdownNow()
          }
        }
      }

    (clientTest *> serverGuardrailsTest).unsafeRunSync()
  }

  it should "extract CN, non-loopback DNS SAN, and SPIFFE URI SAN identities from X.509 certificates" in {
    import java.io.ByteArrayInputStream
    import java.security.cert.{CertificateFactory, X509Certificate}

    val pkiDir = Files.createTempDirectory("raft-grpc-x509-id-")
    try
      val keyPath = pkiDir.resolve("peer.key")
      val crtPath = pkiDir.resolve("peer.crt")
      val log = ProcessLogger(_ => (), _ => ())
      val code = Process(
        Seq(
          "openssl",
          "req",
          "-x509",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          keyPath.toString,
          "-out",
          crtPath.toString,
          "-days",
          "1",
          "-subj",
          "/CN=node-cn-1",
          "-addext",
          "subjectAltName=DNS:localhost,DNS:node-dns-1,URI:spiffe://raft.local/ns/prod/sa/node-spiffe-1"
        ),
        pkiDir.toFile
      ).!(log)
      code shouldBe 0

      val cf = CertificateFactory.getInstance("X.509")
      val cert = cf
        .generateCertificate(
          new ByteArrayInputStream(Files.readAllBytes(crtPath))
        )
        .asInstanceOf[X509Certificate]

      val ids = GrpcTransport.extractCertificateIdentities(cert)
      ids should contain("node-cn-1")
      ids should contain("node-dns-1")
      ids should contain("spiffe://raft.local/ns/prod/sa/node-spiffe-1")
      ids should contain("node-spiffe-1")
      ids should not contain "localhost"
    finally
      Files.deleteIfExists(pkiDir.resolve("peer.key"))
      Files.deleteIfExists(pkiDir.resolve("peer.crt"))
      Files.deleteIfExists(pkiDir)
  }

  it should "not classify oversized RESOURCE_EXHAUSTED as transient and must drop spoofed Multi-Raft envelopes" in {
    import io.grpc.Status
    import raft.multigroup.GroupEnvelope
    import raft.state.GroupId

    val oversized = Status.RESOURCE_EXHAUSTED
      .withDescription("gRPC message exceeds maximum size 4194304: 5000000")
      .asRuntimeException()
    GrpcTransport.transientStatusCode(oversized) shouldBe None

    val backpressure = Status.RESOURCE_EXHAUSTED
      .withDescription("Receiver inbound queue full")
      .asRuntimeException()
    GrpcTransport.transientStatusCode(backpressure) shouldBe Some(
      Status.Code.RESOURCE_EXHAUSTED
    )

    val gid = GroupId("g-auth")
    val spoofTest = (for
      pair1 <- GrpcTransport.multiGroupResource[IO](
        GrpcConfig(host = "127.0.0.1", port = 0),
        TransportConfig(n1, "127.0.0.1:0", Map.empty)
      )
      pair2 <- GrpcTransport.multiGroupResource[IO](
        GrpcConfig(host = "127.0.0.1", port = 0),
        TransportConfig(n2, "127.0.0.1:0", Map.empty)
      )
    yield (pair1, pair2)).use { case ((raw1, _), (raw2, mg2)) =>
      for
        addr2 <- raw2.localAddress
        _ <- raw1.updatePeers(Map(n2 -> addr2))
        t2 <- mg2.registerGroup(gid)
        // raw1 sends with WireEnvelope.sender_id = n1, but inner GroupEnvelope claims from = n3 (spoofed!)
        spoofedEnv = GroupEnvelope(
          gid,
          from = NodeId("n3"),
          message = TimeoutNowRequest(1L, NodeId("n3")),
          to = Some(n2)
        )
        validEnv = GroupEnvelope(
          gid,
          from = n1,
          message = TimeoutNowRequest(2L, n1),
          to = Some(n2)
        )
        _ <- raw1.send(
          n2,
          ProtobufMultiGroupCodec.encodeBatch(Vector(spoofedEnv, validEnv))
        )
        received <- t2.receive.head.compile.lastOrError.timeout(5.seconds)
      yield received shouldBe (n1 -> TimeoutNowRequest(2L, n1))
    }

    spoofTest.unsafeRunSync()
  }
