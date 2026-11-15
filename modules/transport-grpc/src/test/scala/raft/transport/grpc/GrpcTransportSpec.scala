package raft.transport.grpc

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

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
      maxRetries = 10
    )
    config.host shouldBe "10.0.0.1"
    config.port shouldBe 5000
    config.useTls shouldBe true
    config.tlsCertPath shouldBe Some("/etc/certs/raft.pem")
    config.maxRetries shouldBe 10
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
