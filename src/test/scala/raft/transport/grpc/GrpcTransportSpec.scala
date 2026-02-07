package raft.transport.grpc

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.std.Queue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import raft.spi.TransportConfig
import raft.state.NodeId

class GrpcTransportSpec extends AnyFlatSpec with Matchers:

  val n1 = NodeId("node-1")
  val n2 = NodeId("node-2")
  val n3 = NodeId("node-3")
  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private def mkTransport(
      grpcConfig: GrpcConfig = GrpcConfig(),
      localId: NodeId = n1,
      peers: Map[NodeId, String] =
        Map(n2 -> "localhost:9091", n3 -> "localhost:9092")
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

  it should "shutdown gracefully" in {
    val test = for
      transport <- mkTransport()
      _ <- transport.shutdown
    yield succeed

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

  "GrpcTransportFactory" should "create transport as managed resource" in {
    val test = for
      factory <- IO(new GrpcTransportFactory[IO](GrpcConfig()))
      config = TransportConfig(
        n1,
        "localhost:9090",
        Map(n2 -> "localhost:9091")
      )
      _ <- factory.create(config).use { transport =>
        for
          addr <- transport.localAddress
          _ = addr shouldBe "0.0.0.0:9090"
        yield ()
      }
    yield succeed

    test.unsafeRunSync()
  }
