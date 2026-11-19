package raft.kernel

import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration.*

import raft.effect.Effect
import raft.logic.RaftLogic
import raft.message.RaftMessage.*
import raft.raw.{MemStorage, RawNode}
import raft.state.*
import raft.state.NodeState.*

class ConsensusKernelAndFpDomainSpec
    extends AnyFreeSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val n4 = NodeId("n4")
  private val n5 = NodeId("n5")

  "Bytes (immutable value byte sequence)" - {
    "should isolate Log, SnapshotMetadata, and InstallSnapshotRequest from caller Array[Byte] mutations (C7)" in {
      val src = Array[Byte](1, 2, 3, 4)
      val log = Log.command(1L, 1L, src)
      val snap = SnapshotMetadata(10L, 2L, None, src)
      val req1 = InstallSnapshotRequest(2L, n1, 10L, 2L, 0L, src, done = true)
      val req2 = InstallSnapshotRequest(
        2L,
        n1,
        10L,
        2L,
        0L,
        Array[Byte](1, 2, 3, 4),
        done = true
      )

      // Structural value equality & hashCode across distinct backing arrays!
      req1 shouldBe req2
      req1.hashCode shouldBe req2.hashCode
      Effect.SendMessage(n2, req1) shouldBe Effect.SendMessage(n2, req2)

      // Mutate original array AND extracted .data array -> zero effect on immutable values!
      src(0) = 99.toByte
      val extracted = log.data
      extracted(1) = 88.toByte

      log.payload shouldBe Bytes.of(1, 2, 3, 4)
      snap.payload shouldBe Bytes.of(1, 2, 3, 4)
      req1.payload shouldBe Bytes.of(1, 2, 3, 4)
    }

    "should satisfy concatenation, slicing, UTF-8, Base64, and IArray round-trip laws" in {
      forAll(Gen.listOf(Gen.choose(Byte.MinValue, Byte.MaxValue))) { byteList =>
        val raw = byteList.toArray
        val b = Bytes.copyFrom(raw)
        b.length shouldBe raw.length
        b.toIArray.toSeq shouldBe raw.toSeq

        val half = b.length / 2
        (b.slice(0, half) ++ b.slice(half, b.length)) shouldBe b
        Bytes.concat(Seq(b.slice(0, half), b.slice(half, b.length))) shouldBe b
        Bytes.fromBase64(b.toBase64) shouldBe Right(b)
      }
      Bytes.fromBase64("%%%not-base64%%%").isLeft shouldBe true
    }
  }

  "Validated & ConfigError pure smart constructors" - {
    "should accumulate multiple configuration errors without throwing exceptions" in {
      val badResult = RaftConfig.validate(
        localId = NodeId(""),
        electionTimeoutMin = 0.millis,
        electionTimeoutMax = (-5).millis,
        heartbeatInterval = 0.millis,
        maxEntriesPerRequest = 0,
        electionTimeoutTicks = 1,
        heartbeatIntervalTicks = 5
      )

      badResult.isInvalid shouldBe true
      val errors = badResult.toEither.left.toOption.get
      errors.size should be >= 5
      errors.exists(_.message.nonEmpty) shouldBe true

      val validResult = RaftConfig.validate(localId = n1)
      validResult.isValid shouldBe true
    }

    "should validate NodeId, GroupId, LogId, HardState, and ClusterConfig purely" in {
      NodeId.validate("node-alpha\n").isInvalid shouldBe true
      NodeId.validate("x" * 257).isInvalid shouldBe true
      NodeId.validate("node-alpha").isValid shouldBe true

      GroupId.validate("").isInvalid shouldBe true
      GroupId.validate("shard-0").isValid shouldBe true

      LogId.validate(-1L, -2L).toEither.left.toOption.get.size shouldBe 2
      HardState
        .validate(-1L, Some(NodeId("")), -5L)
        .toEither
        .left
        .toOption
        .get
        .size shouldBe 3

      ClusterConfig
        .validate(Map(n1 -> NodeRole.Learner))
        .isInvalid shouldBe true
    }
  }

  "ClusterConfig RCFG v2 binary framing (C4)" - {
    "should round-trip NodeIds containing '|', ',', ':', '=', and Unicode and detect CRC32 bit flips" in {
      val trickyIds = Map(
        NodeId("dc1|rack,2:node=alpha") -> NodeRole.Voter,
        NodeId("eu-west|witness:0,1") -> NodeRole.Witness,
        NodeId("ap,south|learner:9") -> NodeRole.Learner
      )
      val joint = ClusterConfig(Map(n1 -> NodeRole.Voter))
        .beginJointConsensus(trickyIds)

      val encoded = ClusterConfig.encodeBytes(joint)
      ClusterConfig.decodeBytes(encoded) shouldBe Right(joint)

      // Flip 1 payload byte -> CRC32 must reject
      val corrupted = encoded.toArray
      corrupted(8) = (corrupted(8) ^ 0xff).toByte
      ClusterConfig.decode(corrupted).isLeft shouldBe true

      // Legacy v1 text format must still decode cleanly
      val legacyV1 = "v1|n1:0,n2:1||".getBytes("UTF-8")
      val decodedLegacy = ClusterConfig.decode(legacyV1)
      decodedLegacy shouldBe Right(
        ClusterConfig(Map(n1 -> NodeRole.Voter, n2 -> NodeRole.Learner))
      )
    }
  }

  "ConsensusKernel & RawNode safety fixes" - {
    "C1: should NEVER emit outbound RPCs in prePersistMsgs when term or votedFor is unpersisted" in {
      val cluster = ClusterConfig(Set(n1, n2, n3))
      val store = MemStorage.create(cluster)
      val cfg = RaftConfig(localId = n2)
      val raw = new RawNode(cfg, store, initialSeed = 11L)

      // Follower at term=0 receives a higher-term heartbeat (term=5, empty entries -> mutatedLog=false!)
      raw.step(
        n1,
        AppendEntriesRequest(
          term = 5L,
          leaderId = n1,
          prevLogIndex = 0L,
          prevLogTerm = 0L,
          entries = Seq.empty,
          leaderCommit = 0L
        )
      )

      val rd1 = raw.ready()
      rd1.hardState shouldBe Some(HardState(5L, None, 0L))
      // CRITICAL §5.1 INVARIANT: Reply MUST wait in persistedMessages, NOT pre-persist messages!
      rd1.messages shouldBe empty
      rd1.persistedMessages.map(_.message) should contain(
        AppendEntriesResponse(5L, success = true, matchIndex = 0L)
      )

      store.persistReady(rd1)
      val _ = raw.advance(rd1)

      // Now that term=5 IS persisted on disk, a subsequent heartbeat at term=5 CAN reply fast-path!
      raw.step(
        n1,
        AppendEntriesRequest(
          term = 5L,
          leaderId = n1,
          prevLogIndex = 0L,
          prevLogTerm = 0L,
          entries = Seq.empty,
          leaderCommit = 0L
        )
      )
      val rd2 = raw.ready()
      rd2.hardState shouldBe None
      rd2.messages.map(_.message) should contain(
        AppendEntriesResponse(5L, success = true, matchIndex = 0L)
      )
    }

    "C2: should roll back uncommitted Joint Consensus membership on both RawNode and MemStorage when truncated" in {
      val initialCfg = ClusterConfig(Set(n1, n2, n3))
      val expandedMembers =
        List(n1, n2, n3, n4, n5).map(_ -> NodeRole.Voter).toMap
      val jointCfg = initialCfg.beginJointConsensus(expandedMembers)

      val store = MemStorage.create(initialCfg)
      val raw = new RawNode(RaftConfig(localId = n2), store, initialSeed = 22L)

      // Old leader n1 (term=2) appends NoOp@1 (committed) and uncommitted JointConfig@2
      val noOp1 = Log.noOp(1L, 2L)
      val jointEntry2 = Log.configuration(2L, 2L, jointCfg)
      raw.step(
        n1,
        AppendEntriesRequest(
          term = 2L,
          leaderId = n1,
          prevLogIndex = 0L,
          prevLogTerm = 0L,
          entries = Seq(noOp1, jointEntry2),
          leaderCommit = 1L
        )
      )
      val rd1 = raw.ready()
      store.persistReady(rd1)
      val _ = raw.advance(rd1)

      // Both RawNode and MemStorage immediately adopted uncommitted C_old,new
      raw.clusterConfig.isInJointConsensus shouldBe true
      raw.clusterConfig.allNodes shouldBe Set(n1, n2, n3, n4, n5)
      store.clusterConfig.isInJointConsensus shouldBe true

      // New leader n3 (elected in term=3 under {n1,n2,n3}) overwrites index=2 with a normal Command!
      val overwriteCmd2 = Log.command(2L, 3L, "winner=n3".getBytes("UTF-8"))
      raw.step(
        n3,
        AppendEntriesRequest(
          term = 3L,
          leaderId = n3,
          prevLogIndex = 1L,
          prevLogTerm = 2L,
          entries = Seq(overwriteCmd2),
          leaderCommit = 2L
        )
      )

      // RawNode MUST immediately roll back its active ClusterConfig to {n1, n2, n3}!
      raw.clusterConfig.isInJointConsensus shouldBe false
      raw.clusterConfig.voters shouldBe Set(n1, n2, n3)

      val rd2 = raw.ready()
      rd2.truncateFromIndex shouldBe Some(2L)
      store.persistReady(rd2)
      val _ = raw.advance(rd2)

      // MemStorage MUST also roll back its persisted ClusterConfig to {n1, n2, n3}!
      store.clusterConfig.isInJointConsensus shouldBe false
      store.clusterConfig.voters shouldBe Set(n1, n2, n3)
    }

    "C3 & C6: should protect committed prefixes from truncation/regression and emit ReadIndexRejected on higher-term step-down" in {
      val cfg = RaftConfig(localId = n1)
      val cluster = ClusterConfig(Set(n1, n2, n3))
      val getTerm: Long => Option[Long] = {
        case 1L | 2L | 3L => Some(2L)
        case _            => None
      }

      // 1. Follower with commitIndex = 3 ignores regressed leaderCommit = 1 and protects committed indices 1..3
      val f0 = Follower(
        term = 2L,
        votedFor = None,
        leaderId = Some(n2),
        commitIndex = 3L
      )
      val delayedHeartbeat = AppendEntriesRequest(
        term = 2L,
        leaderId = n2,
        prevLogIndex = 1L,
        prevLogTerm = 2L,
        entries = Seq.empty,
        leaderCommit = 1L
      )
      val t1 =
        RaftLogic.onMessage(f0, delayedHeartbeat, cfg, 3L, 2L, 3, getTerm)
      t1.state.commitIndex shouldBe 3L
      t1.effects.collect { case c: Effect.CommitEntries => c } shouldBe empty

      // 2. Overlapping batch spanning [2..4] skips committed [2..3] and appends only index 4!
      val e2 =
        Log.command(2L, 99L, Array[Byte](9)) // would conflict if not skipped!
      val e3 = Log.command(3L, 2L, Array[Byte](3))
      val e4 = Log.command(4L, 2L, Array[Byte](4))
      val spanningReq = AppendEntriesRequest(
        term = 2L,
        leaderId = n2,
        prevLogIndex = 1L,
        prevLogTerm = 2L,
        entries = Seq(e2, e3, e4),
        leaderCommit = 4L
      )
      val t2 = RaftLogic.onMessage(f0, spanningReq, cfg, 3L, 2L, 3, getTerm)
      t2.state.commitIndex shouldBe 4L
      t2.effects.collect { case t: Effect.TruncateLog => t } shouldBe empty
      t2.effects should contain(Effect.AppendLogs(Seq(e4)))

      // 3. Leader stepping down on higher-term RPC MUST reject all pending ReadIndex requests!
      val leaderWithReads = Leader(
        term = 2L,
        commitIndex = 3L,
        pendingReads = Map(
          "r-1" -> PendingRead("r-1", 3L, Set(n1)),
          "r-2" -> PendingRead("r-2", 3L, Set(n1))
        )
      )
      val higherTermResp =
        AppendEntriesResponse(term = 5L, success = false, matchIndex = 0L)
      val t3 = RaftLogic.onMessage(
        leaderWithReads,
        higherTermResp,
        cfg,
        3L,
        2L,
        3,
        getTerm,
        Some(n2),
        Some(cluster)
      )
      t3.state shouldBe Follower(5L, None, None, commitIndex = 3L)
      val rejectedIds = t3.effects.collect {
        case Effect.ReadIndexRejected(id, _, _) => id
      }
      rejectedIds should contain allOf ("r-1", "r-2")
    }

    "C5: should reassemble multi-chunk InstallSnapshot streams in ConsensusKernel / RawNode and reject gapped chunks" in {
      val initCfg = ClusterConfig(Set(n1, n2, n3))
      val newCfg = ClusterConfig(Set(n1, n2, n3, n4))
      val store = MemStorage.create(initCfg)
      val raw = new RawNode(RaftConfig(localId = n4), store, initialSeed = 33L)

      val fullEnvelope = SnapshotMetadata.encodePayloadBytes(
        Some(newCfg),
        Bytes.fromUtf8("k1=v1\nk2=v2\nk3=v3")
      )
      val part1 = fullEnvelope.slice(0, 12)
      val part2 = fullEnvelope.slice(12, 28)
      val part3 = fullEnvelope.slice(28, fullEnvelope.length)

      // Gapped chunk (offset = 12 before offset = 0) -> rejected with success = false!
      raw.step(
        n1,
        new InstallSnapshotRequest(3L, n1, 50L, 3L, 12L, part2, done = false)
      )
      val badRd = raw.ready()
      badRd.persistedMessages.map(_.message) should contain(
        InstallSnapshotResponse(3L, success = false, matchIndex = 0L)
      )
      store.persistReady(badRd)
      val _ = raw.advance(badRd)

      // Stream valid 3-chunk snapshot [0..12), [12..28), [28..end]
      raw.step(
        n1,
        new InstallSnapshotRequest(3L, n1, 50L, 3L, 0L, part1, done = false)
      )
      val r1 = raw.ready()
      store.persistReady(r1)
      val _ = raw.advance(r1)

      raw.step(
        n1,
        new InstallSnapshotRequest(3L, n1, 50L, 3L, 12L, part2, done = false)
      )
      val r2 = raw.ready()
      store.persistReady(r2)
      val _ = raw.advance(r2)

      raw.step(
        n1,
        new InstallSnapshotRequest(3L, n1, 50L, 3L, 28L, part3, done = true)
      )
      val r3 = raw.ready()
      r3.snapshot shouldBe defined
      r3.snapshot.get.lastIncludedIndex shouldBe 50L
      r3.snapshot.get.config shouldBe Some(newCfg)
      r3.snapshot.get.payload.utf8String shouldBe "k1=v1\nk2=v2\nk3=v3"
      raw.clusterConfig shouldBe newCfg
      raw.commitIndex shouldBe 50L
    }

    "Witness replication: should strip Command payloads for Witness peers while advancing quorum commit when a data Voter is offline" in {
      val witnessId = NodeId("w1")
      val cluster = ClusterConfig(
        Map(
          n1 -> NodeRole.Voter,
          n2 -> NodeRole.Voter,
          witnessId -> NodeRole.Witness
        )
      )
      val s1 = MemStorage.create(cluster)
      val sW = MemStorage.create(cluster)
      val r1 = new RawNode(
        RaftConfig(localId = n1, electionTimeoutTicks = 5),
        s1,
        initialSeed = 1L
      )
      val rW = new RawNode(
        RaftConfig(localId = witnessId, electionTimeoutTicks = 5),
        sW,
        initialSeed = 2L
      )

      // Witness itself must NEVER campaign even after 20 ticks!
      for _ <- 1 to 20 do rW.tick()
      rW.hasReady shouldBe false
      rW.state shouldBe a[Follower]

      // Elect n1 using w1's PreVote + Vote while n2 is completely offline!
      for _ <- 1 to 12 do
        r1.tick()
        if r1.hasReady then
          val rd = r1.ready()
          s1.persistReady(rd)
          val outs = rd.messages ++ rd.persistedMessages
          val light = r1.advance(rd)
          (outs ++ light.messages).filter(_.to == witnessId).foreach { env =>
            rW.step(env.from, env.message)
            if rW.hasReady then
              val wrd = rW.ready()
              sW.persistReady(wrd)
              val wouts = wrd.messages ++ wrd.persistedMessages
              val wlight = rW.advance(wrd)
              (wouts ++ wlight.messages).filter(_.to == n1).foreach { wenv =>
                r1.step(wenv.from, wenv.message)
              }
          }

      r1.state shouldBe a[Leader]

      // Propose a large secret command on n1; replicate ONLY to Witness w1 (n2 still offline!)
      val propIdx = r1.proposeBytes(Bytes.fromUtf8("secret-payload-bytes"))
      propIdx.isRight shouldBe true

      for _ <- 1 to 3 do
        if r1.hasReady then
          val rd = r1.ready()
          s1.persistReady(rd)
          val outs = rd.messages ++ rd.persistedMessages
          val light = r1.advance(rd)
          (outs ++ light.messages).filter(_.to == witnessId).foreach { env =>
            rW.step(env.from, env.message)
            if rW.hasReady then
              val wrd = rW.ready()
              sW.persistReady(wrd)
              val wouts = wrd.messages ++ wrd.persistedMessages
              val wlight = rW.advance(wrd)
              (wouts ++ wlight.messages).filter(_.to == n1).foreach { wenv =>
                r1.step(wenv.from, wenv.message)
              }
          }

      // n1 + w1 achieved 2/3 quorum commit, AND w1 stored 0 command payload bytes!
      r1.commitIndex shouldBe propIdx.toOption.get
      val wCmdEntry =
        sW.entries(propIdx.toOption.get, propIdx.toOption.get + 1L).head
      wCmdEntry.logType shouldBe LogType.Command
      wCmdEntry.payload shouldBe Bytes.empty
    }

    "Referential transparency: stepping identical KernelState values twice produces identical outputs" in {
      val cluster = ClusterConfig(Set(n1, n2, n3))
      val store = MemStorage.create(cluster)
      val cfg = RaftConfig(localId = n1, electionTimeoutTicks = 5)
      val k0 = ConsensusKernel.initialize(cfg, store, initialSeed = 777L)

      val runA =
        (1 to 10).foldLeft(k0)((st, _) => ConsensusKernel.tick(st, cfg, store))
      val runB =
        (1 to 10).foldLeft(k0)((st, _) => ConsensusKernel.tick(st, cfg, store))

      runA shouldBe runB
      ConsensusKernel.ready(runA, cfg, store) shouldBe ConsensusKernel.ready(
        runB,
        cfg,
        store
      )
    }
  }
