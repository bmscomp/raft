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
      initial <- store.getCurrentTerm
      _ <- store.setCurrentTerm(Term.unsafeFrom(42))
      updated <- store.getCurrentTerm
    yield (initial, updated)

    val (initial, updated) = test.unsafeRunSync()
    initial shouldBe 0L
    updated shouldBe 42L
  }

  it should "persist and retrieve votedFor" in {
    val nodeId = NodeId("candidate-1")
    val test = for
      store <- InMemStableStore[IO]()
      initial <- store.getVotedFor
      _ <- store.setVotedFor(Some(nodeId))
      updated <- store.getVotedFor
      _ <- store.setVotedFor(None)
      cleared <- store.getVotedFor
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
      term <- store.getCurrentTerm
      voted <- store.getVotedFor
    yield (term, voted)

    val (term, voted) = test.unsafeRunSync()
    term shouldBe 10L
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
      _ <- store.appendLogs(entries)
      log1 <- store.getLog(1)
      log3 <- store.getLog(3)
      missing <- store.getLog(99)
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
      _ <- store.appendLogs(Seq(Log.command(1, 1, "a".getBytes)))
      nonEmpty <- store.isEmpty
      idx1 <- store.lastIndex
      _ <- store.appendLogs(
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
      _ <- store.appendLogs(entries)
      range <- store.getLogs(3, 7)
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
      _ <- store.appendLogs(entries)
      _ <- store.truncateFrom(LogIndex.unsafeFrom(3))
      remaining <- store.getLogs(1, 5)
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
      _ <- store.appendLogs(entries)
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
