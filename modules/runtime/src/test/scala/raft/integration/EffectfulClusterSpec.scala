package raft.integration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import raft.state.*
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.impl.*

/** IO-based integration tests exercising the SPI layer.
  *
  * Uses `InMemLogStore`, `InMemStableStore`, and `InMemTransport` to verify the
  * full infrastructure stack works correctly when wired together, without
  * relying on the pure `RaftLogic` API.
  */
class EffectfulClusterSpec extends AnyFlatSpec with Matchers:

  "InMemStableStore" should "persist and retrieve term" in {
    val test = for
      store <- InMemStableStore[IO]()
      initial <- store.currentTerm
      _ <- store.setCurrentTerm(Term.unsafeFrom(42))
      updated <- store.currentTerm
    yield (initial, updated)

    val (initial, updated) = test.unsafeRunSync()
    initial.value shouldBe 0L
    updated.value shouldBe 42L
  }

  it should "persist and retrieve votedFor" in {
    val nodeId = NodeId("candidate-1")
    val test = for
      store <- InMemStableStore[IO]()
      initial <- store.votedFor
      _ <- store.setVotedFor(Some(nodeId))
      updated <- store.votedFor
      _ <- store.setVotedFor(None)
      cleared <- store.votedFor
    yield (initial, updated, cleared)

    val (initial, updated, cleared) = test.unsafeRunSync()
    initial shouldBe None
    updated shouldBe Some(nodeId)
    cleared shouldBe None
  }

  it should "support current term and voted-for round trip together" in {
    val test = for
      store <- InMemStableStore[IO]()
      _ <- store.setCurrentTerm(Term.unsafeFrom(10))
      _ <- store.setVotedFor(Some(NodeId("node-5")))
      term <- store.currentTerm
      voted <- store.votedFor
    yield (term, voted)

    val (term, voted) = test.unsafeRunSync()
    term.value shouldBe 10L
    voted shouldBe Some(NodeId("node-5"))
  }

  "InMemLogStore" should "append and retrieve entries" in {
    val entries = Seq(
      Log.command(1, 1, "cmd-1".getBytes),
      Log.command(2, 1, "cmd-2".getBytes),
      Log.command(3, 2, "cmd-3".getBytes)
    )

    val test = for
      store <- InMemLogStore[IO]()
      _ <- store.append(entries)
      log1 <- store.get(LogIndex.unsafeFrom(1))
      log3 <- store.get(LogIndex.unsafeFrom(3))
      missing <- store.get(LogIndex.unsafeFrom(99))
    yield (log1, log3, missing)

    val (log1, log3, missing) = test.unsafeRunSync()
    log1 shouldBe defined
    new String(log1.get.data) shouldBe "cmd-1"
    log3 shouldBe defined
    log3.get.term shouldBe 2
    missing shouldBe None
  }

  it should "report correct lastIndex and isEmpty" in {
    val test = for
      store <- InMemLogStore[IO]()
      emptyCheck <- store.isEmpty
      idx0 <- store.lastIndex
      _ <- store.append(Seq(Log.command(1, 1, "a".getBytes)))
      nonEmpty <- store.isEmpty
      idx1 <- store.lastIndex
      _ <- store.append(
        Seq(Log.command(2, 1, "b".getBytes), Log.command(3, 2, "c".getBytes))
      )
      idx3 <- store.lastIndex
    yield (emptyCheck, idx0.value, nonEmpty, idx1.value, idx3.value)

    val (empty, i0, nonEmpty, i1, i3) = test.unsafeRunSync()
    empty shouldBe true
    i0 shouldBe 0
    nonEmpty shouldBe false
    i1 shouldBe 1
    i3 shouldBe 3
  }

  it should "retrieve range of entries" in {
    val entries =
      (1 to 10).map(i => Log.command(i.toLong, 1, s"cmd-$i".getBytes))

    val test = for
      store <- InMemLogStore[IO]()
      _ <- store.append(entries)
      range <- store.getRange(LogIndex.unsafeFrom(3), LogIndex.unsafeFrom(7))
    yield range

    val range = test.unsafeRunSync()
    range.size shouldBe 5
    range.head.index shouldBe 3
    range.last.index shouldBe 7
  }

  it should "truncate from a given index" in {
    val entries =
      (1 to 5).map(i => Log.command(i.toLong, 1, s"cmd-$i".getBytes))

    val test = for
      store <- InMemLogStore[IO]()
      _ <- store.append(entries)
      _ <- store.truncateFrom(LogIndex.unsafeFrom(3))
      remaining <- store.getRange(
        LogIndex.unsafeFrom(1),
        LogIndex.unsafeFrom(5)
      )
      lastIdx <- store.lastIndex
    yield (remaining, lastIdx.value)

    val (remaining, lastIdx) = test.unsafeRunSync()
    remaining.size shouldBe 2
    remaining.map(_.index) shouldBe Seq(1, 2)
    lastIdx shouldBe 2
  }

  it should "look up term at a specific index" in {
    val entries = Seq(
      Log.command(1, 3, "a".getBytes),
      Log.command(2, 3, "b".getBytes),
      Log.command(3, 5, "c".getBytes)
    )

    val test = for
      store <- InMemLogStore[IO]()
      _ <- store.append(entries)
      t1 <- store.termAt(LogIndex.unsafeFrom(1))
      t3 <- store.termAt(LogIndex.unsafeFrom(3))
      tMissing <- store.termAt(LogIndex.unsafeFrom(99))
    yield (t1, t3, tMissing)

    val (t1, t3, tMissing) = test.unsafeRunSync()
    t1 shouldBe defined
    t1.get.value shouldBe 3
    t3 shouldBe defined
    t3.get.value shouldBe 5
    tMissing shouldBe None
  }

  "InMemTransport" should "create a cluster with all peers wired" in {
    val ids = List(NodeId("a"), NodeId("b"), NodeId("c"))

    val test =
      for cluster <- InMemTransport.createCluster[IO](ids)
      yield cluster

    val cluster = test.unsafeRunSync()
    cluster.size shouldBe 3
    cluster.keys should contain allOf (NodeId("a"), NodeId("b"), NodeId("c"))
  }

  it should "deliver messages between peers" in {
    val a = NodeId("a")
    val b = NodeId("b")

    val test = for
      cluster <- InMemTransport.createCluster[IO](List(a, b))
      transportA = cluster(a)
      transportB = cluster(b)
      _ <- transportA.send(b, HeartbeatTimeout)
      received <- transportB.inbox.take
    yield received

    val (fromId, msg) = test.unsafeRunSync()
    fromId shouldBe NodeId("a")
    msg shouldBe HeartbeatTimeout
  }

  it should "broadcast to all peers except self" in {
    val ids = List(NodeId("a"), NodeId("b"), NodeId("c"))

    val test = for
      cluster <- InMemTransport.createCluster[IO](ids)
      _ <- cluster(NodeId("a")).broadcast(ElectionTimeout)
      msgB <- cluster(NodeId("b")).inbox.take
      msgC <- cluster(NodeId("c")).inbox.take
    yield (msgB, msgC)

    val ((fromB, _), (fromC, _)) = test.unsafeRunSync()
    fromB shouldBe NodeId("a")
    fromC shouldBe NodeId("a")
  }

  it should "deliver protocol messages correctly" in {
    val a = NodeId("a")
    val b = NodeId("b")

    val voteReq = RequestVoteRequest(
      term = 5,
      candidateId = a,
      lastLogIndex = 10,
      lastLogTerm = 4,
      isPreVote = false
    )

    val test = for
      cluster <- InMemTransport.createCluster[IO](List(a, b))
      _ <- cluster(a).send(b, voteReq)
      received <- cluster(b).inbox.take
    yield received._2

    val msg = test.unsafeRunSync()
    msg.isInstanceOf[RequestVoteRequest] shouldBe true
    val req: RequestVoteRequest = msg.asInstanceOf[RequestVoteRequest]
    req.term shouldBe 5
    req.candidateId shouldBe a
    req.isPreVote shouldBe false
  }

  "Transport.withEffectfulCodec and CodecInterop" should "encode, batch, broadcast, and filter invalid frames" in {
    import java.nio.charset.StandardCharsets
    import cats.effect.std.Queue
    import fs2.Stream
    import raft.codec.JsonCodec
    import raft.spi.{CodecInterop, Transport}
    import raft.spi.CodecInterop.mapWire

    val n1 = NodeId("n1")
    val n2 = NodeId("n2")
    val effectfulCodec = CodecInterop
      .fromSimple[IO, String](JsonCodec)
      .mapWire[Array[Byte]](
        _.getBytes(StandardCharsets.UTF_8),
        new String(_, StandardCharsets.UTF_8)
      )

    val prog = for
      q <- Queue.unbounded[IO, (NodeId, Array[Byte])]
      raw = new Transport[IO, Array[Byte]]:
        import cats.syntax.all.*
        def send(to: NodeId, msg: Array[Byte]): IO[Unit] = q.offer((n1, msg))
        def sendBatch(to: NodeId, msgs: Seq[Array[Byte]]): IO[Unit] =
          msgs.toList.traverse_(m => q.offer((n1, m)))
        def broadcast(msg: Array[Byte]): IO[Unit] = q.offer((n1, msg))
        def receive: Stream[IO, (NodeId, Array[Byte])] =
          Stream.fromQueueUnterminated(q)
        def localAddress: IO[String] = IO.pure("mem://n1")
        def shutdown: IO[Unit] = IO.unit

      wrapped = Transport.withEffectfulCodec(raw, effectfulCodec)
      addr <- wrapped.localAddress
      _ <- wrapped.send(n2, HeartbeatTimeout)
      _ <- q.offer((n2, "not-valid-json".getBytes(StandardCharsets.UTF_8)))
      _ <- wrapped.sendBatch(
        n2,
        Seq(ElectionTimeout, CheckQuorumTimeout)
      )
      _ <- wrapped.broadcast(RequestVoteResponse(3L, voteGranted = true))
      decoded <- wrapped.receive.take(4).compile.toVector
      _ <- wrapped.shutdown
      timerSvc <- DefaultTimerServiceFactory[IO].create
      timeout <- timerSvc.randomElectionTimeout(
        scala.concurrent.duration.DurationInt(100).millis
      )
    yield {
      addr shouldBe "mem://n1"
      decoded.map(_._2) shouldBe Vector(
        HeartbeatTimeout,
        ElectionTimeout,
        CheckQuorumTimeout,
        RequestVoteResponse(3L, voteGranted = true)
      )
      timeout.toMillis should be >= 100L
    }

    prog.unsafeRunSync()
  }
