package raft.compaction

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.Ref
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.spi.RaftTransport
import raft.state.*
import fs2.Stream
import cats.syntax.all.*

/** Tests for [[SnapshotTransfer]] — the chunked snapshot sender.
  *
  * Validates:
  *   - Single-chunk transfer for small snapshots
  *   - Multi-chunk transfer with correct offsets
  *   - Empty snapshot handling
  *   - Chunk boundary correctness
  *   - Final chunk `done` flag
  *   - Pipelined transfer with bounded concurrency
  */
class SnapshotTransferSpec extends AnyFlatSpec with Matchers:

  val leaderId = NodeId("leader")
  val followerId = NodeId("follower-1")
  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private class RecordingTransport extends RaftTransport[IO]:
    private var sent: Vector[(NodeId, RaftMessage)] = Vector.empty

    def send(to: NodeId, msg: RaftMessage): IO[Unit] = IO {
      this.synchronized {
        sent = sent :+ (to, msg)
      }
    }

    def broadcast(msg: RaftMessage): IO[Unit] = IO.unit
    def receive: Stream[IO, (NodeId, RaftMessage)] = Stream.empty
    def localAddress: IO[String] = IO.pure("localhost:0")
    def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): IO[Unit] =
      msgs.toList.traverse_(msg => send(to, msg))
    def shutdown: IO[Unit] = IO.unit

    def sentMessages: Vector[(NodeId, RaftMessage)] =
      this.synchronized(sent)

  "SnapshotTransfer" should "send single chunk for small snapshot" in {
    val test = for
      transport <- IO(new RecordingTransport)
      transfer = new SnapshotTransfer[IO](1024, transport, logger)
      snapshot = Snapshot[IO](
        lastIncludedIndex = 100,
        lastIncludedTerm = 5,
        data = Stream.emits("small-data".getBytes).covary[IO]
      )
      chunks <- transfer.sendSnapshot(leaderId, followerId, snapshot)
    yield {
      chunks shouldBe 1
      val msgs = transport.sentMessages
      msgs.size shouldBe 1
      val (to, msg) = msgs.head
      to shouldBe followerId
      msg match
        case req: InstallSnapshotRequest =>
          req.term shouldBe 5
          req.leaderId shouldBe leaderId
          req.lastIncludedIndex shouldBe 100
          req.lastIncludedTerm shouldBe 5
          req.offset shouldBe 0
          req.done shouldBe true
          new String(req.data) shouldBe "small-data"
        case _ => fail("Expected InstallSnapshotRequest")
    }

    test.unsafeRunSync()
  }

  it should "split large snapshot into multiple chunks" in {
    val test = for
      transport <- IO(new RecordingTransport)
      chunkSize = 10
      transfer = new SnapshotTransfer[IO](chunkSize, transport, logger)
      data = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" // 26 bytes -> 3 chunks (10, 10, 6)
      snapshot = Snapshot[IO](
        lastIncludedIndex = 200,
        lastIncludedTerm = 8,
        data = Stream.emits(data.getBytes).covary[IO]
      )
      totalChunks <- transfer.sendSnapshot(leaderId, followerId, snapshot)
    yield {
      totalChunks shouldBe 3
      val msgs = transport.sentMessages
      msgs.size shouldBe 3

      // First chunk: offset=0, done=false
      msgs(0)._2 match
        case req: InstallSnapshotRequest =>
          req.offset shouldBe 0
          req.done shouldBe false
          new String(req.data) shouldBe "ABCDEFGHIJ"
        case _ => fail("Expected InstallSnapshotRequest")

      // Second chunk: offset=10, done=false
      msgs(1)._2 match
        case req: InstallSnapshotRequest =>
          req.offset shouldBe 10
          req.done shouldBe false
          new String(req.data) shouldBe "KLMNOPQRST"
        case _ => fail("Expected InstallSnapshotRequest")

      // Third chunk: offset=20, done=true
      msgs(2)._2 match
        case req: InstallSnapshotRequest =>
          req.offset shouldBe 20
          req.done shouldBe true
          new String(req.data) shouldBe "UVWXYZ"
        case _ => fail("Expected InstallSnapshotRequest")
    }

    test.unsafeRunSync()
  }

  it should "handle empty snapshot" in {
    val test = for
      transport <- IO(new RecordingTransport)
      transfer = new SnapshotTransfer[IO](1024, transport, logger)
      snapshot = Snapshot[IO](
        lastIncludedIndex = 50,
        lastIncludedTerm = 3,
        data = Stream.empty.covary[IO]
      )
      chunks <- transfer.sendSnapshot(leaderId, followerId, snapshot)
    yield {
      chunks shouldBe 1
      val msgs = transport.sentMessages
      msgs.size shouldBe 1
      msgs.head._2 match
        case req: InstallSnapshotRequest =>
          req.done shouldBe true
          req.data shouldBe empty
        case _ => fail("Expected InstallSnapshotRequest")
    }

    test.unsafeRunSync()
  }

  it should "handle exact chunk-size boundary" in {
    val test = for
      transport <- IO(new RecordingTransport)
      chunkSize = 5
      transfer = new SnapshotTransfer[IO](chunkSize, transport, logger)
      data = "ABCDEFGHIJ" // 10 bytes -> 2 chunks of exactly 5
      snapshot = Snapshot[IO](
        lastIncludedIndex = 10,
        lastIncludedTerm = 1,
        data = Stream.emits(data.getBytes).covary[IO]
      )
      chunks <- transfer.sendSnapshot(leaderId, followerId, snapshot)
    yield {
      chunks shouldBe 2
      val msgs = transport.sentMessages

      msgs(0)._2 match
        case req: InstallSnapshotRequest =>
          req.done shouldBe false
          new String(req.data) shouldBe "ABCDE"
        case _ => fail("Expected InstallSnapshotRequest")

      msgs(1)._2 match
        case req: InstallSnapshotRequest =>
          req.done shouldBe true
          new String(req.data) shouldBe "FGHIJ"
        case _ => fail("Expected InstallSnapshotRequest")
    }

    test.unsafeRunSync()
  }

  it should "set correct metadata on all chunks" in {
    val test = for
      transport <- IO(new RecordingTransport)
      transfer = new SnapshotTransfer[IO](5, transport, logger)
      snapshot = Snapshot[IO](
        lastIncludedIndex = 999,
        lastIncludedTerm = 42,
        data = Stream.emits("0123456789AB".getBytes).covary[IO]
      )
      _ <- transfer.sendSnapshot(leaderId, followerId, snapshot)
    yield {
      val msgs = transport.sentMessages
      msgs.size shouldBe 3

      msgs.foreach { case (to, msg) =>
        to shouldBe followerId
        msg match
          case req: InstallSnapshotRequest =>
            req.term shouldBe 42
            req.leaderId shouldBe leaderId
            req.lastIncludedIndex shouldBe 999
            req.lastIncludedTerm shouldBe 42
          case _ => fail("Expected InstallSnapshotRequest")
      }
    }

    test.unsafeRunSync()
  }

  it should "send pipelined chunks with correct total count" in {
    val test = for
      transport <- IO(new RecordingTransport)
      transfer = new SnapshotTransfer[IO](5, transport, logger, maxInFlight = 2)
      data = "ABCDEFGHIJKLMNO" // 15 bytes -> 3 chunks of 5
      snapshot = Snapshot[IO](
        lastIncludedIndex = 50,
        lastIncludedTerm = 3,
        data = Stream.emits(data.getBytes).covary[IO]
      )
      totalChunks <- transfer.sendSnapshotPipelined(
        leaderId,
        followerId,
        snapshot
      )
    yield
      totalChunks shouldBe 3
      val msgs = transport.sentMessages
      msgs.size shouldBe 3
      msgs.foreach { case (to, msg) =>
        to shouldBe followerId
        msg shouldBe a[InstallSnapshotRequest]
      }
      val doneChunks = msgs.collect {
        case (_, req: InstallSnapshotRequest) if req.done => req
      }
      doneChunks.size shouldBe 1

    test.unsafeRunSync()
  }

  it should "respect maxInFlight bound during pipelined transfer" in {
    import scala.concurrent.duration.*

    val test = for
      maxConcurrentRef <- Ref.of[IO, Int](0)
      currentRef <- Ref.of[IO, Int](0)
      sentRef <- Ref.of[IO, Vector[(NodeId, RaftMessage)]](Vector.empty)

      transport = new RaftTransport[IO]:
        def send(to: NodeId, msg: RaftMessage): IO[Unit] =
          for
            _ <- currentRef.update(_ + 1)
            current <- currentRef.get
            _ <- maxConcurrentRef.update(max => math.max(max, current))
            _ <- IO.sleep(10.millis)
            _ <- sentRef.update(_ :+ (to, msg))
            _ <- currentRef.update(_ - 1)
          yield ()
        def broadcast(msg: RaftMessage): IO[Unit] = IO.unit
        def receive: Stream[IO, (NodeId, RaftMessage)] = Stream.empty
        def localAddress: IO[String] = IO.pure("localhost:0")
        def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): IO[Unit] =
          msgs.toList.traverse_(msg => send(to, msg))
        def shutdown: IO[Unit] = IO.unit

      maxInFlight = 2
      transfer = new SnapshotTransfer[IO](
        chunkSize = 10,
        transport = transport,
        logger = logger,
        maxInFlight = maxInFlight
      )
      data = new Array[Byte](100) // 10 chunks of 10 bytes
      snapshot = Snapshot[IO](
        lastIncludedIndex = 100,
        lastIncludedTerm = 5,
        data = Stream.emits(data).covary[IO]
      )
      totalChunks <- transfer.sendSnapshotPipelined(
        leaderId,
        followerId,
        snapshot
      )
      maxConcurrent <- maxConcurrentRef.get
      sent <- sentRef.get
    yield
      totalChunks shouldBe 10
      sent.size shouldBe 10
      maxConcurrent should be <= maxInFlight

    test.unsafeRunSync()
  }

  it should "handle empty snapshot in pipelined mode" in {
    val test = for
      transport <- IO(new RecordingTransport)
      transfer = new SnapshotTransfer[IO](
        1024,
        transport,
        logger,
        maxInFlight = 4
      )
      snapshot = Snapshot[IO](
        lastIncludedIndex = 50,
        lastIncludedTerm = 3,
        data = Stream.empty.covary[IO]
      )
      chunks <- transfer.sendSnapshotPipelined(leaderId, followerId, snapshot)
    yield
      chunks shouldBe 1
      val msgs = transport.sentMessages
      msgs.size shouldBe 1
      msgs.head._2 match
        case req: InstallSnapshotRequest =>
          req.done shouldBe true
          req.data shouldBe empty
        case _ => fail("Expected InstallSnapshotRequest")

    test.unsafeRunSync()
  }
