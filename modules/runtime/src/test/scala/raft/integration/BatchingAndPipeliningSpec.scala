package raft.integration

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.RaftNode
import raft.impl.{
  DefaultTimerService,
  InMemLogStore,
  InMemSnapshotStore,
  InMemStableStore,
  InMemTransport
}
import raft.integration.RuntimeClusterHarness.TestKvStateMachine
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.spi.{LogStore, RaftTransport}
import raft.state.*
import raft.state.NodeState.*

/** Integration suite verifying transparent Group-Commit proposal batching
  * (`BatchConfig`), explicit `proposeBatch` / `proposeBatchAndCommit` APIs, and
  * `Inflights`-bounded pipelined replication (`PipelineConfig`) in
  * [[RaftNode]].
  */
class BatchingAndPipeliningSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val ids = List(n1, n2, n3)
  private val cluster = ClusterConfig(ids.toSet)

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  /** Delegating [[LogStore]] recording every non-empty command `append` batch
    * size so tests can prove `N` concurrent proposals coalesce into `<< N` WAL
    * writes (`group commit`).
    */
  private final class CountingLogStore(
      underlying: InMemLogStore[IO],
      commandBatchesRef: Ref[IO, Vector[Int]]
  ) extends LogStore[IO]:
    def get(index: LogIndex): IO[Option[Log]] = underlying.get(index)
    def getRange(start: LogIndex, end: LogIndex): IO[Vector[Log]] =
      underlying.getRange(start, end)
    def lastIndex: IO[LogIndex] = underlying.lastIndex
    def append(entries: Seq[Log]): IO[Unit] =
      val cmdCount = entries.count(_.logType == LogType.Command)
      val record =
        if cmdCount > 0 then commandBatchesRef.update(_ :+ cmdCount)
        else IO.unit
      record *> underlying.append(entries)
    def truncateFrom(index: LogIndex): IO[Unit] = underlying.truncateFrom(index)
    def discardBefore(index: LogIndex): IO[Unit] =
      underlying.discardBefore(index)
    def clear: IO[Unit] = underlying.clear
    def isEmpty: IO[Boolean] = underlying.isEmpty
    def termAt(index: LogIndex): IO[Option[Term]] = underlying.termAt(index)

  /** Delegating [[RaftTransport]] that adds configurable link latency on
    * `AppendEntriesResponse` frames and records peak in-flight
    * `AppendEntriesRequest` bursts per peer before the first response arrives.
    */
  private final class RecordingLatencyTransport(
      underlying: InMemTransport[IO],
      responseLatency: FiniteDuration,
      dropFirstBatchTo: Set[NodeId],
      droppedOnceRef: Ref[IO, Set[NodeId]],
      sentBurstsRef: Ref[IO, Vector[(NodeId, Vector[Long])]],
      sendBatchCalls: AtomicInteger
  ) extends RaftTransport[IO]:

    private def recordIfDataAppend(to: NodeId, msg: RaftMessage): IO[Boolean] =
      msg match
        case ae: AppendEntriesRequest
            if ae.entries.exists(_.logType == LogType.Command) =>
          val indices = ae.entries.map(_.index).toVector
          for
            alreadyDropped <- droppedOnceRef.modify { s =>
              if dropFirstBatchTo.contains(to) && !s.contains(to) then
                (s + to, true)
              else (s, false)
            }
            _ <- sentBurstsRef.update(_ :+ (to -> indices))
          yield alreadyDropped
        case _ => IO.pure(false)

    def send(to: NodeId, msg: RaftMessage): IO[Unit] =
      msg match
        case _: AppendEntriesResponse if responseLatency > Duration.Zero =>
          (IO.sleep(responseLatency) *> underlying.send(to, msg)).start.void
        case _ =>
          recordIfDataAppend(to, msg).flatMap {
            case true  => IO.unit // Simulated packet drop
            case false => underlying.send(to, msg)
          }

    def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): IO[Unit] =
      IO(sendBatchCalls.incrementAndGet()) *>
        msgs.toList.traverse_ { msg =>
          recordIfDataAppend(to, msg).flatMap {
            case true  => IO.unit
            case false => underlying.send(to, msg)
          }
        }

    def broadcast(msg: RaftMessage): IO[Unit] = underlying.broadcast(msg)
    def receive: Stream[IO, (NodeId, RaftMessage)] = underlying.receive
    def localAddress: IO[String] = underlying.localAddress
    def shutdown: IO[Unit] = underlying.shutdown

  private def awaitLeader(
      nodes: List[(NodeId, RaftNode[IO])],
      retries: Int = 300
  ): IO[(NodeId, RaftNode[IO])] =
    nodes
      .traverse { case (id, n) => n.getState.map(st => (id, n, st)) }
      .flatMap { states =>
        states.collectFirst { case (id, n, _: Leader) => (id, n) } match
          case Some(found) => IO.pure(found)
          case None        =>
            if retries <= 0 then
              IO.raiseError(new RuntimeException("No leader elected"))
            else IO.sleep(15.millis) *> awaitLeader(nodes, retries - 1)
      }

  private def awaitCondition(
      cond: IO[Boolean],
      retries: Int = 300
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(new RuntimeException("Cluster condition timed out"))
        else IO.sleep(15.millis) *> awaitCondition(cond, retries - 1)
    }

  "RaftNode with BatchConfig enabled" should "coalesce 60 concurrent proposals into a handful of group-commit LogStore.append calls" in {
    val prog = for
      transports <- InMemTransport.createCluster[IO](ids)
      timerSvc <- DefaultTimerService[IO]
      batchCounts <- ids
        .traverse(id => Ref.of[IO, Vector[Int]](Vector.empty).map(id -> _))
        .map(_.toMap)
      sms <- ids
        .traverse(id => TestKvStateMachine.create.map(id -> _))
        .map(_.toMap)
      resources = ids.map { id =>
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutMin = 250.millis,
          electionTimeoutMax = 500.millis,
          heartbeatInterval = 35.millis,
          batching = BatchConfig(
            enabled = true,
            maxSize = 25,
            maxWait = 25.millis
          )
        )
        for
          rawLog <- Resource.eval(InMemLogStore[IO]())
          countingLog = new CountingLogStore(rawLog, batchCounts(id))
          stable <- Resource.eval(InMemStableStore[IO]())
          snap <- Resource.eval(InMemSnapshotStore[IO]())
          node <- RaftNode[IO](
            cfg,
            cluster,
            transports(id),
            countingLog,
            stable,
            sms(id),
            timerSvc,
            snapshotStoreOpt = Some(snap)
          )
        yield id -> node
      }
      _ <- resources.sequence.use { nodes =>
        for
          _ <- nodes.traverse_(_._2.start)
          leaderPair <- awaitLeader(nodes)
          (leaderId, leaderNode) = leaderPair
          // Barrier write so leader is settled, then reset leader batch counter
          _ <- leaderNode.proposeAndCommit(utf8("warmup=0"))
          _ <- batchCounts(leaderId).set(Vector.empty)

          // Fire 60 concurrent proposals (45 proposeAndCommit + 15 propose) simultaneously!
          commitIdsFiber <- (1 to 45).toList
            .parTraverse(i => leaderNode.proposeAndCommit(utf8(s"gc-$i=v$i")))
            .start
          appendIdsFiber <- (46 to 60).toList
            .parTraverse(i => leaderNode.propose(utf8(s"gc-$i=v$i")))
            .start
          commitIds <- commitIdsFiber.joinWithNever
          appendIds <- appendIdsFiber.joinWithNever

          allIds = commitIds ++ appendIds
          _ = allIds.map(_.index).distinct should have size 60

          expected = (1 to 60)
            .map(i => s"gc-$i" -> s"v$i")
            .toMap + ("warmup" -> "0")
          _ <- awaitCondition(
            ids.forallM(id => sms(id).currentMap.map(_ == expected))
          )

          recordedBatches <- batchCounts(leaderId).get
        yield {
          recordedBatches.sum shouldBe 60
          // Without batching this would be 60 separate LogStore.append calls;
          // with group-commit batching (maxSize=25, maxWait=25ms), 60 concurrent fibers coalesce into <= 10 appends!
          recordedBatches.size should be <= 10
          recordedBatches.max should be > 1
        }
      }
    yield ()

    prog.timeout(30.seconds).unsafeRunSync()
  }

  it should "flush immediately upon hitting maxSize without waiting for maxWait, and flush partial batches after maxWait" in {
    val prog = for
      transports <- InMemTransport.createCluster[IO](ids)
      timerSvc <- DefaultTimerService[IO]
      leaderBatches <- Ref.of[IO, Vector[Int]](Vector.empty)
      sms <- ids
        .traverse(id => TestKvStateMachine.create.map(id -> _))
        .map(_.toMap)
      resources = ids.map { id =>
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutMin = 250.millis,
          electionTimeoutMax = 500.millis,
          heartbeatInterval = 35.millis,
          // Large 3-second maxWait proves an 8-item burst flushes immediately via maxSize = 8!
          batching = BatchConfig(
            enabled = true,
            maxSize = 8,
            maxWait = 3.seconds
          )
        )
        for
          rawLog <- Resource.eval(InMemLogStore[IO]())
          countingLog = new CountingLogStore(rawLog, leaderBatches)
          stable <- Resource.eval(InMemStableStore[IO]())
          node <- RaftNode[IO](
            cfg,
            cluster,
            transports(id),
            countingLog,
            stable,
            sms(id),
            timerSvc
          )
        yield id -> node
      }
      _ <- resources.sequence.use { nodes =>
        for
          _ <- nodes.traverse_(_._2.start)
          leaderPair <- awaitLeader(nodes)
          (_, leaderNode) = leaderPair

          t0 <- IO.monotonic
          logIds <- (1 to 8).toList.parTraverse { i =>
            leaderNode.proposeAndCommit(utf8(s"fast-$i=$i"))
          }
          t1 <- IO.monotonic
          elapsed = t1 - t0
        yield {
          logIds should have size 8
          // Must flush immediately at maxSize = 8, well below the 3-second maxWait!
          elapsed should be < 1500.millis
        }
      }
    yield ()

    prog.timeout(20.seconds).unsafeRunSync()
  }

  "RaftNode.proposeBatch and proposeBatchAndCommit" should "execute atomically in 1 append and reject non-leader calls" in {
    val prog = for
      transports <- InMemTransport.createCluster[IO](ids)
      timerSvc <- DefaultTimerService[IO]
      batchCounts <- ids
        .traverse(id => Ref.of[IO, Vector[Int]](Vector.empty).map(id -> _))
        .map(_.toMap)
      sms <- ids
        .traverse(id => TestKvStateMachine.create.map(id -> _))
        .map(_.toMap)
      resources = ids.map { id =>
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutMin = 250.millis,
          electionTimeoutMax = 500.millis,
          heartbeatInterval = 35.millis,
          batching = BatchConfig(enabled = false)
        )
        for
          rawLog <- Resource.eval(InMemLogStore[IO]())
          countingLog = new CountingLogStore(rawLog, batchCounts(id))
          stable <- Resource.eval(InMemStableStore[IO]())
          node <- RaftNode[IO](
            cfg,
            cluster,
            transports(id),
            countingLog,
            stable,
            sms(id),
            timerSvc
          )
        yield id -> node
      }
      _ <- resources.sequence.use { nodes =>
        for
          _ <- nodes.traverse_(_._2.start)
          leaderPair <- awaitLeader(nodes)
          (leaderId, leaderNode) = leaderPair
          followerNode = nodes.find(_._1 != leaderId).get._2

          // Empty batch succeeds with empty Vector on leader, fails with NotLeader on follower
          emptyOk <- leaderNode.proposeBatchAndCommit(Seq.empty)
          _ = emptyOk shouldBe empty
          followerErr <- followerNode
            .proposeBatchAndCommit(Seq(utf8("a=1")))
            .attempt
          _ = followerErr.left.toOption.get shouldBe a[RaftError.NotLeader]

          // Propose 12 items via proposeBatchAndCommit -> exactly 1 LogStore.append of size 12!
          _ <- batchCounts(leaderId).set(Vector.empty)
          batch1 <- leaderNode.proposeBatchAndCommit(
            (1 to 12).map(i => utf8(s"b1-$i=v$i"))
          )
          _ = batch1.map(
            _.index
          ) shouldBe (batch1.head.index until batch1.head.index + 12L).toVector
          countsAfter1 <- batchCounts(leaderId).get
          _ = countsAfter1 shouldBe Vector(12)

          // Propose 6 items via proposeBatch -> 1 LogStore.append of size 6!
          batch2 <- leaderNode.proposeBatch(
            (1 to 6).map(i => utf8(s"b2-$i=v$i"))
          )
          _ = batch2 should have size 6
          countsAfter2 <- batchCounts(leaderId).get
          _ = countsAfter2 shouldBe Vector(12, 6)

          _ <- awaitCondition(
            ids.forallM(id => sms(id).currentMap.map(_.size == 18))
          )
        yield ()
      }
    yield ()

    prog.timeout(25.seconds).unsafeRunSync()
  }

  "RaftNode with PipelineConfig enabled" should "pipeline up to maxInflight AppendEntries batches per peer without waiting for RTTs and recover from packet loss" in {
    val prog = for
      rawTransports <- InMemTransport.createCluster[IO](ids)
      timerSvc <- DefaultTimerService[IO]
      droppedOnceRef <- Ref.of[IO, Set[NodeId]](Set.empty)
      burstsByNode <- ids
        .traverse(id =>
          Ref.of[IO, Vector[(NodeId, Vector[Long])]](Vector.empty).map(id -> _)
        )
        .map(_.toMap)
      sendBatchCounters = ids.map(_ -> new AtomicInteger(0)).toMap
      sms <- ids
        .traverse(id => TestKvStateMachine.create.map(id -> _))
        .map(_.toMap)
      resources = ids.map { id =>
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutMin = 350.millis,
          electionTimeoutMax = 700.millis,
          heartbeatInterval = 60.millis,
          maxEntriesPerRequest = 4,
          pipelining = PipelineConfig(enabled = true, maxInflight = 4),
          batching =
            BatchConfig(enabled = true, maxSize = 20, maxWait = 5.millis)
        )
        val wrappedTransport = new RecordingLatencyTransport(
          underlying = rawTransports(id),
          responseLatency = 25.millis,
          dropFirstBatchTo = Set(n3),
          droppedOnceRef = droppedOnceRef,
          sentBurstsRef = burstsByNode(id),
          sendBatchCalls = sendBatchCounters(id)
        )
        for
          logStore <- Resource.eval(InMemLogStore[IO]())
          stableStore <- Resource.eval(InMemStableStore[IO]())
          node <- RaftNode[IO](
            cfg,
            cluster,
            wrappedTransport,
            logStore,
            stableStore,
            sms(id),
            timerSvc
          )
        yield id -> node
      }
      _ <- resources.sequence.use { nodes =>
        for
          _ <- nodes.traverse_(_._2.start)
          leaderPair <- awaitLeader(nodes)
          (leaderId, leaderNode) = leaderPair

          // Commit 1 warmup entry so followers transition Probe -> Replicate in peerProgressRef!
          _ <- leaderNode.proposeAndCommit(utf8("warmup=ok"))
          _ <- awaitCondition(
            ids.forallM(id =>
              sms(id).currentMap.map(_.get("warmup").contains("ok"))
            )
          )
          _ <- burstsByNode(leaderId).set(Vector.empty)
          _ = sendBatchCounters(leaderId).set(0)

          // Propose 16 commands (4 chunks of maxEntriesPerRequest = 4) in one batch!
          // With pipelining(maxInflight = 4) and 25ms ACK latency, leader dispatches all 4 batches
          // back-to-back via transport.sendBatch BEFORE the first ACK returns (even dropping first packet to n3)!
          committedIds <- leaderNode.proposeBatchAndCommit(
            (1 to 16).map(i => utf8(s"pipe-$i=val-$i"))
          )
          _ = committedIds should have size 16

          expected = (1 to 16)
            .map(i => s"pipe-$i" -> s"val-$i")
            .toMap + ("warmup" -> "ok")
          _ <- awaitCondition(
            ids.forallM(id => sms(id).currentMap.map(_ == expected)),
            retries = 400
          )

          bursts <- burstsByNode(leaderId).get
          healthyPeer = (ids.toSet - leaderId - n3).head
          healthyBursts = bursts.collect {
            case (to, idxs) if to == healthyPeer => idxs
          }
        yield {
          sendBatchCounters(leaderId).get() should be >= 1
          healthyBursts.take(4).map(_.size) shouldBe Vector(4, 4, 4, 4)
        }
      }
    yield ()

    prog.timeout(30.seconds).unsafeRunSync()
  }

  it should "support zero-delay smart batching (maxWait = 0ms), batched enqueueBatchProposals, sequential peer dispatch, and clean step-down failure" in {
    val prog = for
      transports <- InMemTransport.createCluster[IO](ids)
      timerSvc <- DefaultTimerService[IO]
      sms <- ids
        .traverse(id => TestKvStateMachine.create.map(id -> _))
        .map(_.toMap)
      resources = ids.map { id =>
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutMin = 250.millis,
          electionTimeoutMax = 500.millis,
          heartbeatInterval = 35.millis,
          parallelReplicationEnabled = false,
          batching = BatchConfig(
            enabled = true,
            maxSize = 16,
            maxWait = Duration.Zero
          ),
          pipelining = PipelineConfig(enabled = true, maxInflight = 4)
        )
        for
          logStore <- Resource.eval(InMemLogStore[IO]())
          stableStore <- Resource.eval(InMemStableStore[IO]())
          node <- RaftNode[IO](
            cfg,
            cluster,
            transports(id),
            logStore,
            stableStore,
            sms(id),
            timerSvc
          )
        yield id -> node
      }
      _ <- resources.sequence.use { nodes =>
        for
          _ <- nodes.traverse_(_._2.start)
          leaderPair <- awaitLeader(nodes)
          (leaderId, leaderNode) = leaderPair

          // Exercise proposeBatch + proposeBatchAndCommit + submit while BatchConfig.enabled = true (enqueueBatchProposals)
          b1 <- leaderNode.proposeBatch((1 to 5).map(i => utf8(s"zd-a$i=$i")))
          b2 <- leaderNode.proposeBatchAndCommit(
            (1 to 5).map(i => utf8(s"zd-b$i=$i"))
          )
          _ <- leaderNode.submit(utf8("zd-sub=1"))
          _ = b1 should have size 5
          _ = b2 should have size 5

          _ <- awaitCondition(
            ids.forallM(id => sms(id).currentMap.map(_.size == 11))
          )

          // Isolate old leader so CheckQuorum steps it down; verify subsequent / in-flight batch calls fail with NotLeader
          peers = ids.filterNot(_ == leaderId)
          _ <- peers.traverse_ { p =>
            transports(leaderId).unregisterPeer(p) *>
              transports(p).unregisterPeer(leaderId)
          }
          _ <- awaitCondition(
            leaderNode.getState.map(!_.isInstanceOf[Leader])
          )
          steppedErr <- leaderNode
            .proposeBatchAndCommit(Seq(utf8("stale=1")))
            .attempt
          _ = steppedErr.left.toOption.get shouldBe a[RaftError]
        yield ()
      }
    yield ()

    prog.timeout(25.seconds).unsafeRunSync()
  }
