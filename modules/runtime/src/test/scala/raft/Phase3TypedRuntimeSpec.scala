package raft

import java.nio.ByteBuffer
import java.nio.file.Files
import scala.concurrent.duration.*

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.noop.NoOpLogger

import raft.compaction.SnapshotTransfer
import raft.impl.{
  DefaultTimerService,
  InMemLogStore,
  InMemStableStore,
  InMemTransport
}
import raft.message.RaftMessage
import raft.message.RaftMessage.AppendEntriesRequest
import raft.multigroup.{
  CoalescedHeartbeatConfig,
  GroupEnvelope,
  MultiGroupTransport,
  MultiRaftNode,
  TypedGroupConfig
}
import raft.spi.{RaftTransport, StateMachine}
import raft.state.*
import raft.state.NodeState.Leader

class Phase3TypedRuntimeSpec
    extends AsyncFreeSpec
    with AsyncIOSpec
    with Matchers:

  /** Pure atomic `Long` accumulator state machine returning the post-apply sum
    * directly as `R = Long` (zero side-channel result map!).
    */
  private final class TypedAdderStateMachine(ref: Ref[IO, Long])
      extends StateMachine[IO, Long]:
    def apply(log: Log): IO[Long] =
      val delta =
        if log.payload.length >= 8 then log.payload.asReadOnlyByteBuffer.getLong
        else log.payload.utf8String.trim.toLongOption.getOrElse(0L)
      ref.updateAndGet(_ + delta)

    def snapshot: IO[Stream[IO, Byte]] =
      ref.get.map { v =>
        val buf = ByteBuffer.allocate(8).putLong(v)
        Stream.chunk(Chunk.array(buf.array())).covary[IO]
      }

    def restore(data: Stream[IO, Byte]): IO[Unit] =
      data.compile.to(Array).flatMap { arr =>
        val v = if arr.length >= 8 then ByteBuffer.wrap(arr).getLong else 0L
        ref.set(v)
      }

    def current: IO[Long] = ref.get

  private def encodeLong(v: Long): Bytes =
    Bytes.unsafeWrap(ByteBuffer.allocate(8).putLong(v).array())

  private def awaitLeader[R](
      nodes: List[(NodeId, RaftNode[IO, R])],
      retries: Int = 300
  ): IO[(NodeId, RaftNode[IO, R])] =
    nodes
      .traverse { case (id, n) => n.getState.map(st => (id, n, st)) }
      .flatMap { states =>
        states
          .collect { case (id, n, l: Leader) => (id, n, l.term) }
          .sortBy(-_._3)
          .headOption match
          case Some((id, n, _)) => IO.pure(id -> n)
          case None             =>
            if retries <= 0 then
              IO.raiseError(new RuntimeException("No leader elected"))
            else IO.sleep(15.millis) *> awaitLeader(nodes, retries - 1)
      }

  "Phase 3 — Typed RaftNode[F, R], Lock-Free Network I/O & Streaming Snapshots" - {

    "should return strongly-typed Applied[Long] outputs from proposeAndApply and proposeBatchAndApply (FP3)" in {
      val ids = List(NodeId("n1"), NodeId("n2"), NodeId("n3"))
      val cluster = ClusterConfig(ids.toSet)

      val res = for
        transports <- Resource.eval(InMemTransport.createCluster[IO](ids))
        nodes <- ids.traverse { id =>
          for
            ls <- Resource.eval(InMemLogStore[IO]())
            ss <- Resource.eval(InMemStableStore[IO]())
            smRef <- Resource.eval(Ref.of[IO, Long](0L))
            sm = new TypedAdderStateMachine(smRef)
            timer <- Resource.eval(DefaultTimerService[IO])
            rn <- RaftNode[IO](
              RaftConfig(
                localId = id,
                electionTimeoutMin = 120.millis,
                electionTimeoutMax = 240.millis,
                heartbeatInterval = 30.millis,
                batching =
                  BatchConfig(enabled = true, maxSize = 16, maxWait = 4.millis),
                pipelining = PipelineConfig(enabled = true, maxInflight = 4)
              ),
              cluster,
              transports(id),
              ls,
              ss,
              sm,
              timer
            )
            _ <- Resource.eval(rn.start)
          yield (id, rn, sm)
        }
      yield nodes

      res.use { triples =>
        val pairs = triples.map { case (id, rn, _) => id -> rn }
        for
          leaderPair <- awaitLeader(pairs)
          leader = leaderPair._2
          first <- leader.proposeAndApply(encodeLong(10L))
          batchOut <- leader.proposeBatchAndApply(
            Vector(encodeLong(5L), encodeLong(20L), encodeLong(7L))
          )
          concurrentOut <- (1 to 10).toList.parTraverse { i =>
            leader.proposeAndApply(encodeLong(i.toLong))
          }
          finalTotals <- triples
            .traverse(_._3.current)
            .iterateUntil(_.forall(_ == 97L))
        yield
          first.value shouldBe 10L
          first.logId.index should be >= 2L
          batchOut.map(_.value) shouldBe Vector(15L, 35L, 42L)
          concurrentOut.map(_.value).sorted.last shouldBe 97L
          finalTotals shouldBe List(97L, 97L, 97L)
      }
    }

    "should not block quorum proposals or leaseReads when a slow peer stalls transport.send, and should strip Witness payloads (FP4, C6)" in {
      val n1 = NodeId("n1")
      val n2 = NodeId("n2-witness")
      val n3 = NodeId("n3-slow")
      val ids = List(n1, n2, n3)
      val cluster = ClusterConfig(
        Map(
          n1 -> NodeRole.Voter,
          n2 -> NodeRole.Witness,
          n3 -> NodeRole.Voter
        )
      )

      for
        rawTransports <- InMemTransport.createCluster[IO](ids)
        witnessObserved <- Ref.of[IO, Vector[Log]](Vector.empty)
        unblockSlowPeer <- Deferred[IO, Unit]

        // Wrap n1's outbound transport so sends to n3 block on unblockSlowPeer AND inspect Witness entries sent to n2
        n1Transport = new RaftTransport[IO]:
          private val base = rawTransports(n1)
          def send(to: NodeId, msg: RaftMessage): IO[Unit] =
            val recordWitness =
              if to == n2 then
                msg match
                  case ae: AppendEntriesRequest if ae.entries.nonEmpty =>
                    witnessObserved.update(_ ++ ae.entries)
                  case _ => IO.unit
              else IO.unit
            val maybeStall =
              if to == n3 then unblockSlowPeer.get else IO.unit
            recordWitness *> maybeStall *> base.send(to, msg)

          def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): IO[Unit] =
            msgs.toList.traverse_(m => send(to, m))

          def broadcast(msg: RaftMessage): IO[Unit] =
            List(n2, n3).parTraverse_(p => send(p, msg))

          def receive: Stream[IO, (NodeId, RaftMessage)] = base.receive
          def localAddress: IO[String] = base.localAddress
          def shutdown: IO[Unit] = base.shutdown

        // Isolate n3 inbound -> n1 as well so n1 + n2-witness form the active quorum
        _ <- rawTransports(n3).unregisterPeer(n1)
        _ <- rawTransports(n3).unregisterPeer(n2)

        res = ids.traverse { id =>
          for
            ls <- Resource.eval(InMemLogStore[IO]())
            ss <- Resource.eval(InMemStableStore[IO]())
            smRef <- Resource.eval(Ref.of[IO, Long](0L))
            sm = new TypedAdderStateMachine(smRef)
            timer <- Resource.eval(DefaultTimerService[IO])
            tp = if id == n1 then n1Transport else rawTransports(id)
            // Make n1 win election quickly; Witness n2 never campaigns!
            eMin = if id == n1 then 90.millis else 500.millis
            eMax = if id == n1 then 150.millis else 900.millis
            rn <- RaftNode[IO](
              RaftConfig(
                localId = id,
                electionTimeoutMin = eMin,
                electionTimeoutMax = eMax,
                heartbeatInterval = 25.millis,
                leaseReadEnabled = true,
                leaderLeaseDuration = 200.millis
              ),
              cluster,
              tp,
              ls,
              ss,
              sm,
              timer
            )
            _ <- Resource.eval(rn.start)
          yield (id, rn)
        }

        outcome <- res.use { pairs =>
          (for
            leaderPair <- awaitLeader(pairs.filter(_._1 == n1))
            (ldrId, leader) = leaderPair
            applied <- leader
              .proposeAndApply(Bytes.fromUtf8("25"))
              .timeout(1500.millis)
            leaseIdx <- leader.leaseRead.timeout(500.millis)
            witnessLogs <- witnessObserved.get
          yield (ldrId, applied, leaseIdx, witnessLogs))
            .guarantee(unblockSlowPeer.complete(()).void)
        }
      yield
        val (ldrId, applied, leaseIdx, witnessLogs) = outcome
        ldrId shouldBe n1
        applied.value shouldBe 25L
        leaseIdx.isDefined shouldBe true
        val witnessCommands = witnessLogs.filter(_.logType == LogType.Command)
        witnessCommands.nonEmpty shouldBe true
        // Witness peer MUST receive stripped zero-byte command payloads!
        witnessCommands.forall(_.payload.isEmpty) shouldBe true
    }

    "should stream multi-chunk FilePendingSnapshot payloads and preserve monotonic offsets in sendSnapshotPipelined (FP5)" in {
      for
        pending <- FilePendingSnapshot.create[IO](500L, 4L)
        tmpPath = pending.tempFile
        cfg = ClusterConfig(Set(NodeId("a"), NodeId("b"), NodeId("c")))
        rawPayload = Array.tabulate[Byte](90_000)(i => (i % 251).toByte)
        wireHdr = SnapshotMetadata.encodeStreamingHeader(Some(cfg))
        fullWire = wireHdr ++ Bytes.copyFrom(rawPayload)
        chunks = (0 until fullWire.length by 8192).map { start =>
          fullWire.slice(start, start + 8192)
        }.toVector
        filled <- chunks.foldLeftM(pending)((acc, ch) =>
          acc.appendChunkBytes(ch)
        )
        decoded <- filled.decodeStreamingPayload
        (decodedCfg, bodyStream) = decoded
        restoredBytes <- bodyStream.compile.to(Array)
        _ <- filled.cleanup
        existsAfterCleanup <- IO(Files.exists(tmpPath))

        // Verify SnapshotTransfer.sendSnapshotPipelined delivers chunks in strict monotonic offset order
        recordedOffsets <- Ref.of[IO, Vector[Long]](Vector.empty)
        captureTransport = new RaftTransport[IO]:
          def send(to: NodeId, msg: RaftMessage): IO[Unit] =
            msg match
              case req: RaftMessage.InstallSnapshotRequest =>
                recordedOffsets.update(_ :+ req.offset)
              case _ => IO.unit
          def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): IO[Unit] =
            msgs.toList.traverse_(send(to, _))
          def broadcast(msg: RaftMessage): IO[Unit] = IO.unit
          def receive: Stream[IO, (NodeId, RaftMessage)] = Stream.empty
          def localAddress: IO[String] = IO.pure("local")
          def shutdown: IO[Unit] = IO.unit

        transfer = new SnapshotTransfer[IO](
          chunkSize = 10_000,
          transport = captureTransport,
          logger = NoOpLogger[IO],
          maxInFlight = 4
        )
        sentChunks <- transfer.sendSnapshotPipelined(
          leaderId = NodeId("n1"),
          target = NodeId("n2"),
          snapshot = Snapshot[IO](
            500L,
            4L,
            Stream
              .chunk(Chunk.array(rawPayload))
              .chunkLimit(4096)
              .unchunks
              .covary[IO],
            Some(cfg)
          ),
          leaderTerm = 5L
        )
        offsets <- recordedOffsets.get
      yield
        decodedCfg shouldBe Some(cfg)
        java.util.Arrays.equals(restoredBytes, rawPayload) shouldBe true
        existsAfterCleanup shouldBe false
        sentChunks shouldBe 9
        offsets shouldBe offsets.sorted
        offsets.head shouldBe 0L
    }

    "should bound unknown-group staging in MultiGroupTransport and serialize concurrent MultiRaftNode.createTypedGroup calls (FP6)" in {
      val local = NodeId("host-1")
      val voters = ClusterConfig(Set(local))
      val shardId = GroupId("typed-shard-0")

      for
        mg <- MultiGroupTransport.withBatchSender[IO](
          localId = local,
          sendBatchEnvelopes = _ => IO.unit,
          maxQueueSize = 64,
          coalescing = CoalescedHeartbeatConfig(),
          strictRegistration = false,
          maxUnregisteredGroups = 4
        )
        // Flood 50 distinct unknown GroupIds -> at most 4 staged, 46 dropped!
        _ <- (1 to 50).toList.traverse_ { i =>
          mg.dispatch(
            GroupEnvelope(
              GroupId(s"unknown-$i"),
              NodeId("attacker"),
              RaftMessage.HeartbeatTimeout
            )
          )
        }
        statsAfterStagingCap <- mg.coalescerStats
        _ <- mg.setStrictGroupRegistration(true)
        _ <- (1 to 10).toList.traverse_ { i =>
          mg.dispatch(
            GroupEnvelope(
              GroupId(s"strict-unknown-$i"),
              NodeId("attacker"),
              RaftMessage.HeartbeatTimeout
            )
          )
        }
        statsAfterStrict <- mg.coalescerStats

        coord <- MultiRaftNode[IO](local, mg)
        _ <- coord.start
        // Register a typed group and verify race-free duplicate rejection + typed proposeAndApply!
        ls <- InMemLogStore[IO]()
        ss <- InMemStableStore[IO]()
        smRef <- Ref.of[IO, Long](100L)
        sm = new TypedAdderStateMachine(smRef)
        timer <- DefaultTimerService[IO]
        typedNode <- coord.createTypedGroup[Long](
          shardId,
          TypedGroupConfig(
            raftConfig = RaftConfig(
              localId = local,
              electionTimeoutMin = 80.millis,
              electionTimeoutMax = 150.millis,
              heartbeatInterval = 25.millis
            ),
            cluster = voters,
            logStore = ls,
            stableStore = ss,
            stateMachine = sm,
            timerService = timer
          )
        )
        dupAttempt <- coord
          .createTypedGroup[Long](
            shardId,
            TypedGroupConfig(
              raftConfig = RaftConfig.default(local),
              cluster = voters,
              logStore = ls,
              stableStore = ss,
              stateMachine = sm,
              timerService = timer
            )
          )
          .attempt
        _ <- awaitLeader(List(local -> typedNode))
        typedApplied <- typedNode.proposeAndApply(encodeLong(55L))
        erasedApplied <- coord.proposeAndApplyToGroup(shardId, encodeLong(5L))
        _ <- coord.shutdown
      yield
        statsAfterStagingCap.droppedUnknownGroupEnvelopes shouldBe 46L
        statsAfterStrict.droppedUnknownGroupEnvelopes shouldBe 56L
        dupAttempt.isLeft shouldBe true
        typedApplied.value shouldBe 155L
        erasedApplied.value shouldBe 160L
    }

    "should cover TermRingBuffer window operations, FilePendingSnapshot v1/raw fallbacks, and Validated configs" in {
      val ring0 = TermRingBuffer.empty(64)
      val entries150 = (1L to 150L).map(i => Log.noOp(i, (i / 10L) + 1L))
      val ring1 = ring0.append(entries150)
      // Overlapping append & discontiguous seedFromEntries
      val ring2 = ring1.append(Vector(Log.noOp(145L, 99L), Log.noOp(146L, 99L)))
      val discontig = Vector(
        Log.noOp(10L, 1L),
        Log.noOp(11L, 1L),
        Log.noOp(25L, 2L),
        Log.noOp(26L, 2L)
      )
      val seeded = TermRingBuffer.empty(64).seedFromEntries(discontig)
      val truncMid = ring2.truncateFrom(120L)
      val truncAll = truncMid.truncateFrom(1L)
      val discMid = ring1.discardBefore(110L)
      val discAll = discMid.discardBefore(500L)
      val withSnap = ring1.withSnapshot(140L, 15L).clearLogOnly

      // CoalescedHeartbeatConfig & SnapshotMetadata & ConfigError validation
      val invalidHb = CoalescedHeartbeatConfig.validate(
        enabled = true,
        flushInterval = (-1).millis,
        maxBatchSize = 0
      )
      val validHb = CoalescedHeartbeatConfig.validate(
        enabled = true,
        flushInterval = 5.millis,
        maxBatchSize = 64
      )
      val invalidSnap = SnapshotMetadata.validate(-1L, -2L)
      val validSnap = SnapshotMetadata.validate(10L, 2L)
      val cfg = ClusterConfig(Set(NodeId("a"), NodeId("b")))
      val v1Payload = SnapshotMetadata.encodePayloadBytes(
        Some(cfg),
        Bytes.fromUtf8("state-v1")
      )
      val (decCfg, decBody) = SnapshotMetadata.decodePayloadBytes(v1Payload)
      val hdrNone = SnapshotMetadata.encodeStreamingHeader(None)

      val tmpDir = Files.createTempDirectory("raft-fps-test-")
      for
        emptySnap <- FilePendingSnapshot.createIn[IO](tmpDir, 10L, 1L)
        emptyDec <- emptySnap.decodeStreamingPayload
        _ <- emptySnap.cleanup

        // v1 RSNP envelope inside FilePendingSnapshot
        v1File <- FilePendingSnapshot.createIn[IO](tmpDir, 20L, 2L)
        v1Filled <- v1File.appendChunk(v1Payload.toArray)
        v1Dec <- v1Filled.decodeStreamingPayload
        v1Bytes <- v1Dec._2.compile.to(Array)
        asSnapBytes <- v1Filled.toSnapshot.data.compile.to(Array)
        _ <- v1Filled.cleanup

        // Raw ClusterConfig fallback (<= 4096B)
        rawCfgFile <- FilePendingSnapshot.createIn[IO](tmpDir, 30L, 3L)
        rawCfgFilled <- rawCfgFile.appendChunkBytes(
          ClusterConfig.encodeBytes(cfg)
        )
        rawCfgDec <- rawCfgFilled.decodeStreamingPayload
        _ <- rawCfgFilled.cleanup

        // Raw arbitrary payload (> 4096B) fallback
        bigRaw = Array.fill[Byte](6000)(42.toByte)
        bigFile <- FilePendingSnapshot.createIn[IO](tmpDir, 40L, 4L)
        bigFilled <- bigFile.appendChunk(bigRaw)
        bigDec <- bigFilled.decodeStreamingPayload
        bigOut <- bigDec._2.compile.to(Array)
        _ <- bigFilled.cleanup
        _ <- IO(Files.deleteIfExists(tmpDir))
      yield
        ring0.lastCoordinate shouldBe None
        ring0.termAt(0L) shouldBe Some(0L)
        ring1.termAt(1L) shouldBe None
        ring1.termAt(150L) shouldBe Some(16L)
        ring2.termAt(146L) shouldBe Some(99L)
        ring2.lastCoordinate shouldBe Some((146L, 99L))
        seeded.termAt(26L) shouldBe Some(2L)
        truncMid.termAt(120L) shouldBe None
        truncAll.lastCoordinate shouldBe None
        discMid.termAt(109L) shouldBe None
        discMid.termAt(110L) shouldBe defined
        discAll.lastCoordinate shouldBe None
        withSnap.lastCoordinate shouldBe Some((140L, 15L))
        withSnap.termAt(140L) shouldBe Some(15L)

        invalidHb.toEither.left.toOption.get.size shouldBe 2
        invalidHb.toEither.left.toOption.get
          .forall(_.message.nonEmpty) shouldBe true
        validHb.isValid shouldBe true
        invalidSnap.toEither.left.toOption.get.size shouldBe 2
        validSnap.toEither.toOption.get.data.length shouldBe 0
        decCfg shouldBe Some(cfg)
        decBody.utf8String shouldBe "state-v1"
        hdrNone.length shouldBe 10

        emptyDec._1 shouldBe None
        v1Dec._1 shouldBe Some(cfg)
        new String(v1Bytes, "UTF-8") shouldBe "state-v1"
        asSnapBytes.length shouldBe v1Payload.length
        rawCfgDec._1 shouldBe Some(cfg)
        bigDec._1 shouldBe None
        bigOut.length shouldBe 6000
    }

    "should support group-commit proposeAndApply/proposeBatchAndApply, mapOutput/widenOutput, and bounded proposal backpressure" in {
      val local = NodeId("solo-typed")
      val cluster = ClusterConfig(Set(local))

      val res = for
        tpMap <- Resource.eval(InMemTransport.createCluster[IO](List(local)))
        ls <- Resource.eval(InMemLogStore[IO]())
        ss <- Resource.eval(InMemStableStore[IO]())
        smRef <- Resource.eval(Ref.of[IO, Long](0L))
        sm = new TypedAdderStateMachine(smRef)
        timer <- Resource.eval(DefaultTimerService[IO])
        rn <- RaftNode[IO](
          RaftConfig(
            localId = local,
            electionTimeoutMin = 60.millis,
            electionTimeoutMax = 110.millis,
            heartbeatInterval = 20.millis,
            leaseReadEnabled = true,
            parallelReplicationEnabled = false,
            batching = BatchConfig(
              enabled = true,
              maxSize = 16,
              maxWait = 3.millis
            ),
            maxPendingProposals = 32
          ),
          cluster,
          tpMap(local),
          ls,
          ss,
          sm,
          timer
        )
        _ <- Resource.eval(rn.start)
      yield rn

      res.use { rn =>
        val mapped: RaftNode[IO, String] = rn.mapOutput(v => s"sum=$v")
        val widened: AnyRaftNode[IO] = rn.widenOutput
        for
          _ <- awaitLeader(List(local -> rn))
          one <- mapped.proposeAndApply(encodeLong(12L).toArray)
          batch <- mapped.proposeBatchAndApplyArrays(
            Seq(encodeLong(8L).toArray, encodeLong(22L).toArray)
          )
          _ <- mapped.proposeAndCommit(encodeLong(3L).toArray)
          _ <- mapped.proposeBytesAndCommit(encodeLong(5L))
          _ <- mapped.proposeBatchAndCommit(Seq(encodeLong(10L).toArray))
          _ <- mapped.propose(encodeLong(1L).toArray)
          _ <- mapped.proposeBatch(Seq(encodeLong(2L).toArray))
          _ <- mapped.submit(encodeLong(7L).toArray)
          rIdx <- mapped.readIndex
          lIdx <- mapped.leaseRead
          widenOut <- widened.proposeAndApply(encodeLong(30L))
          st <- mapped.getState
          cl <- mapped.getClusterConfig
        yield
          one.value shouldBe "sum=12"
          batch.map(_.value) shouldBe Vector("sum=20", "sum=42")
          rIdx should be >= 1L
          lIdx.isDefined shouldBe true
          widenOut.value shouldBe 100L
          st.term should be >= 1L
          cl.voters shouldBe Set(local)
      }
    }
  }
