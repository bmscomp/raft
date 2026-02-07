package raft.spi

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.kernel.Resource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.NodeId
import fs2.Stream

/** Tests for [[TransportRegistry]] — the pluggable transport backend manager.
  *
  * Validates:
  *   - Registration and lookup
  *   - Available transport listing
  *   - Unknown transport lookup returns None
  *   - Multiple transport registration
  */
class TransportRegistrySpec extends AnyFlatSpec with Matchers:

  private class DummyTransport extends Transport[IO, String]:
    def send(to: NodeId, msg: String): IO[Unit] = IO.unit
    def sendBatch(to: NodeId, msgs: Seq[String]): IO[Unit] = IO.unit
    def broadcast(msg: String): IO[Unit] = IO.unit
    def receive: Stream[IO, (NodeId, String)] = Stream.empty
    def localAddress: IO[String] = IO.pure("localhost:0")
    def shutdown: IO[Unit] = IO.unit

  private class DummyFactory extends TransportFactory[IO, String]:
    def create(config: TransportConfig): Resource[IO, Transport[IO, String]] =
      Resource.pure(new DummyTransport)

  "TransportRegistry" should "register and retrieve a transport" in {
    val test = for
      registry <- TransportRegistry.create[IO]
      _ <- registry.register("dummy", new DummyFactory)
      result <- registry.get("dummy")
    yield result shouldBe defined

    test.unsafeRunSync()
  }

  it should "return None for unknown transport" in {
    val test = for
      registry <- TransportRegistry.create[IO]
      result <- registry.get("nonexistent")
    yield result shouldBe None

    test.unsafeRunSync()
  }

  it should "list all available transports" in {
    val test = for
      registry <- TransportRegistry.create[IO]
      _ <- registry.register("grpc", new DummyFactory)
      _ <- registry.register("in-memory", new DummyFactory)
      _ <- registry.register("http", new DummyFactory)
      available <- registry.available
    yield {
      available should contain allOf ("grpc", "in-memory", "http")
      available shouldBe sorted
    }

    test.unsafeRunSync()
  }

  it should "start empty" in {
    val test = for
      registry <- TransportRegistry.create[IO]
      available <- registry.available
    yield available shouldBe empty

    test.unsafeRunSync()
  }

  it should "override existing registration" in {
    val test = for
      registry <- TransportRegistry.create[IO]
      factory1 = new DummyFactory
      factory2 = new DummyFactory
      _ <- registry.register("test", factory1)
      _ <- registry.register("test", factory2)
      result <- registry.get("test")
    yield result shouldBe defined

    test.unsafeRunSync()
  }
