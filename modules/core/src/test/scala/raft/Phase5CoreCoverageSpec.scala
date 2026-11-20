package raft

import scala.concurrent.duration.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import raft.codec.JsonCodec
import raft.effect.Effect.*
import raft.logic.RaftLogic
import raft.message.RaftMessage.*
import raft.raw.{MemStorage, RawNode}
import raft.state.*
import raft.state.NodeState.*

/** Comprehensive unit coverage for pure functional domain ADTs, smart
  * constructors, codecs, and `ConsensusKernel` / `RawNode` management
  * transitions.
  */
class Phase5CoreCoverageSpec extends AnyFreeSpec with Matchers:

  "Phase 5 — Pure Domain & ConsensusKernel Exhaustive Coverage" - {

    "Bytes methods, Base64 round-trips, IArray interop, and slices" in {
      val empty = Bytes.empty
      empty.isEmpty shouldBe true
      empty.nonEmpty shouldBe false
      empty.size shouldBe 0
      empty.utf8String shouldBe ""
      empty.toBase64 shouldBe ""
      empty.toArray.length shouldBe 0
      empty.toIArray.length shouldBe 0
      Bytes.fromBase64("") shouldBe Right(Bytes.empty)
      Bytes.fromBase64("!!!not-base64!!!").isLeft shouldBe true

      val b1 = Bytes.of(1.toByte, 2.toByte, 3.toByte)
      val b2 = Bytes.fromIArray(IArray[Byte](4, 5))
      val joined = b1 ++ b2
      val cat = Bytes.concat(Seq(Bytes.empty, b1, Bytes.empty, b2))
      joined shouldBe cat
      joined(0) shouldBe 1.toByte
      joined(4) shouldBe 5.toByte
      joined.slice(0, 5) shouldBe joined
      joined.slice(2, 2) shouldBe Bytes.empty
      joined.slice(1, 4) shouldBe Bytes.of(2.toByte, 3.toByte, 4.toByte)
      (Bytes.empty ++ b1) shouldBe b1
      (b1 ++ Bytes.empty) shouldBe b1
      joined.toString shouldBe "Bytes(len=5)"
      joined.equals("other-type") shouldBe false
      Bytes.fromBase64(joined.toBase64) shouldBe Right(joined)
      joined.toIArray.toSeq shouldBe Seq[Byte](1, 2, 3, 4, 5)
    }

    "Validated, ConfigError messages, BatchConfig, PipelineConfig, RaftConfig, and ClusterConfig v1/v2" in {
      val allErrors = Vector(
        ConfigError.EmptyNodeId,
        ConfigError.OversizedNodeId(300, 256),
        ConfigError.ControlCharacterInNodeId("bad\n"),
        ConfigError.EmptyGroupId,
        ConfigError.NegativeTerm(-1L),
        ConfigError.NegativeLogIndex(-2L),
        ConfigError.EmptyVoterSet("ctx"),
        ConfigError.InvalidPositiveInt("f", 0),
        ConfigError.InvalidDuration("d", "bad"),
        ConfigError.InvalidIntervalOrdering("ord", "bad"),
        ConfigError.InvalidField("fld", "bad")
      )
      allErrors.forall(_.message.nonEmpty) shouldBe true

      BatchConfig.validate(true, 0, (-1).millis).isInvalid shouldBe true
      BatchConfig.validate(true, 32, 2.millis).isValid shouldBe true
      PipelineConfig.validate(true, 0).isInvalid shouldBe true
      PipelineConfig.validate(true, 8).isValid shouldBe true

      val badRaft = RaftConfig.validate(
        localId = NodeId("n1"),
        electionTimeoutMin = (-5).millis,
        electionTimeoutMax = (-10).millis,
        heartbeatInterval = 0.millis,
        maxEntriesPerRequest = 0,
        leaderLeaseDuration = (-1).millis,
        electionTimeoutTicks = 0,
        heartbeatIntervalTicks = 5,
        maxInboundQueueSize = 0,
        maxPendingProposals = 0
      )
      badRaft.isInvalid shouldBe true

      val n1 = NodeId("n1")
      val n2 = NodeId("n2")
      val n3 = NodeId("n3")
      val base = ClusterConfig
        .single(n1)
        .addMember(n2, NodeRole.Learner)
        .addMember(n3, NodeRole.Witness)
      base.learners shouldBe Set(n2)
      base.witnesses shouldBe Set(n3)
      base.canCampaign(n1) shouldBe true
      base.canCampaign(n2) shouldBe false
      base.canCampaign(n3) shouldBe false

      val promoted = base.promoteLearner(n2).removeMember(n3)
      promoted.isVoter(n2) shouldBe true
      promoted.contains(n3) shouldBe false

      val badCluster = ClusterConfig.validate(
        members = Map(n1 -> NodeRole.Voter),
        pendingConfig = Some(Map(n2 -> NodeRole.Learner))
      )
      badCluster.isInvalid shouldBe true

      val legacyV1 = "v1|n1:0,n2:1|n1:0|n1:0,n2:0"
      val decodedV1 = ClusterConfig.decode(legacyV1.getBytes("UTF-8"))
      decodedV1.isRight shouldBe true
      decodedV1.toOption.get.isInJointConsensus shouldBe true

      val v2Raw = ClusterConfig.encode(promoted)
      v2Raw(v2Raw.length - 1) = (v2Raw(v2Raw.length - 1) ^ 0xff).toByte
      ClusterConfig.decode(v2Raw).isLeft shouldBe true
    }

    "RawNode Witness campaign suppression, AddServer/RemoveServer RPCs, and JsonCodec bytesCodec" in {
      val n1 = NodeId("n1")
      val n2 = NodeId("n2")
      val witnessCfg = ClusterConfig(
        Map(n1 -> NodeRole.Witness, n2 -> NodeRole.Voter)
      )
      val witnessStore = MemStorage.create(witnessCfg)
      val witnessNode = new RawNode(
        RaftConfig(
          localId = n1,
          electionTimeoutTicks = 4,
          heartbeatIntervalTicks = 1
        ),
        witnessStore,
        42L
      )
      for _ <- 1 to 15 do witnessNode.tick()
      witnessNode.state.term shouldBe 0L

      val soloStore = MemStorage.create(ClusterConfig.single(n2))
      val soloNode = new RawNode(
        RaftConfig(
          localId = n2,
          preVoteEnabled = false,
          electionTimeoutTicks = 3,
          heartbeatIntervalTicks = 1
        ),
        soloStore,
        99L
      )
      for _ <- 1 to 6 do soloNode.tick()
      while soloNode.hasReady do
        val rd = soloNode.ready()
        soloStore.persistReady(rd)
        val lr = soloNode.advance(rd)
        lr.commitIndex.foreach(ci =>
          soloStore.setHardState(soloStore.hardState.copy(commitIndex = ci))
        )

      soloNode.step(
        n1,
        AddServerRequest(soloNode.state.term, n1, isLearner = true)
      )
      while soloNode.hasReady do
        val rd = soloNode.ready()
        soloStore.persistReady(rd)
        val lr = soloNode.advance(rd)
        lr.commitIndex.foreach(ci =>
          soloStore.setHardState(soloStore.hardState.copy(commitIndex = ci))
        )
      soloNode.clusterConfig.isLearner(n1) shouldBe true

      soloNode.step(n1, RemoveServerRequest(soloNode.state.term, n1))
      while soloNode.hasReady do
        val rd = soloNode.ready()
        soloStore.persistReady(rd)
        val lr = soloNode.advance(rd)
        lr.commitIndex.foreach(ci =>
          soloStore.setHardState(soloStore.hardState.copy(commitIndex = ci))
        )
      soloNode.clusterConfig.contains(n1) shouldBe false

      val addResp = AddServerResponse(1L, success = true, Some(n2))
      val encodedBytes = JsonCodec.bytesCodec.encode(addResp)
      JsonCodec.bytesCodec.decode(encodedBytes) shouldBe Right(addResp)
    }

    "SnapshotMetadata v1/v2 envelopes, NodeState helpers, and ClusterConfig malformed frames" in {
      val n1 = NodeId("n1")
      val n2 = NodeId("n2")
      val n3 = NodeId("n3")
      val cfg = ClusterConfig(Set(n1, n2, n3))

      // SnapshotMetadata v1 + v2 + fallbacks
      val arrV1 =
        SnapshotMetadata.encodePayload(Some(cfg), Array[Byte](1, 2, 3))
      val (decCfg1, decArr1) = SnapshotMetadata.decodePayload(arrV1)
      decCfg1 shouldBe Some(cfg)
      decArr1.toSeq shouldBe Seq[Byte](1, 2, 3)

      val arrV1None = SnapshotMetadata.encodePayload(None, Array[Byte](9, 8))
      SnapshotMetadata.decodePayload(arrV1None)._1 shouldBe None

      val v2HdrSome = SnapshotMetadata.encodeStreamingHeader(Some(cfg))
      val v2WireSome = v2HdrSome ++ Bytes.fromUtf8("stream-body")
      val (v2CfgSome, v2BodySome) =
        SnapshotMetadata.decodePayloadBytes(v2WireSome)
      v2CfgSome shouldBe Some(cfg)
      v2BodySome.utf8String shouldBe "stream-body"

      val v2HdrNone = SnapshotMetadata.encodeStreamingHeader(None)
      val (v2CfgNone, v2BodyNone) =
        SnapshotMetadata.decodePayloadBytes(v2HdrNone ++ Bytes.fromUtf8("raw"))
      v2CfgNone shouldBe None
      v2BodyNone.utf8String shouldBe "raw"

      SnapshotMetadata.decodePayloadBytes(Bytes.empty) shouldBe (
        None,
        Bytes.empty
      )
      val rawCfgBytes = ClusterConfig.encodeBytes(cfg)
      SnapshotMetadata.decodePayloadBytes(rawCfgBytes) shouldBe (
        Some(cfg),
        Bytes.empty
      )
      val shortRaw = Bytes.fromUtf8("hello")
      SnapshotMetadata.decodePayloadBytes(shortRaw) shouldBe (None, shortRaw)

      // NodeState extensions & commit calculators
      val fol = Follower(3L, Some(n1), Some(n2), 4L)
      val cand = Candidate(4L, Set(n1), 4L)
      val ldr: NodeState.Leader = Leader(5L, Map(n2 -> 6L), Map(n2 -> 5L), 4L)
      fol.typedTerm shouldBe Term.unsafeFrom(3L)
      fol.typedCommitIndex shouldBe LogIndex.unsafeFrom(4L)
      fol.isFollower shouldBe true
      ldr.isFollower shouldBe false
      ldr.leaderHint(n1) shouldBe Some(n1)
      fol.leaderHint(n1) shouldBe Some(n2)
      cand.leaderHint(n1) shouldBe None
      ldr.calculateCommitIndex(0, _ => Some(5L), 6L) shouldBe 4L
      ldr.calculateCommitIndex(1, _ => Some(5L), 6L) shouldBe 6L
      ldr.calculateCommitIndex(1, _ => Some(5L), -1L) shouldBe 5L
      val fullMapLdr: NodeState.Leader =
        Leader(5L, Map.empty, Map(n1 -> 6L, n2 -> 5L, n3 -> 5L), 4L)
      fullMapLdr.calculateCommitIndex(3, _ => Some(5L), -1L) shouldBe 5L

      // ClusterConfig malformed binary/legacy frames
      val badVer = Array[Byte](0x52, 0x43, 0x46, 0x47, 99, 0, 0, 0, 0, 0)
      ClusterConfig.decode(badVer).isLeft shouldBe true
      val shortV2 = Array[Byte](0x52, 0x43, 0x46, 0x47, 2, 0, 0, 0, 0, 0, 0)
      ClusterConfig.decode(shortV2).isLeft shouldBe true
      ClusterConfig.decode("v1||n1:0|".getBytes("UTF-8")).isLeft shouldBe true
      ClusterConfig
        .decode("v1|bad_entry||".getBytes("UTF-8"))
        .isLeft shouldBe true
      ClusterConfig.decode("v1|n1:99||".getBytes("UTF-8")).isLeft shouldBe true
      ClusterConfig.decode("v1|n1:1||".getBytes("UTF-8")).isLeft shouldBe true
      ClusterConfig
        .decode("unknown|format".getBytes("UTF-8"))
        .isLeft shouldBe true
    }

    "RaftLogic InstallSnapshotResponse, AddServerRequest, and RemoveServerRequest across all roles" in {
      val n1 = NodeId("n1")
      val n2 = NodeId("n2")
      val n3 = NodeId("n3")
      val n4 = NodeId("n4")
      val rCfg = RaftConfig(localId = n1)
      val cl = ClusterConfig(
        Map(n1 -> NodeRole.Voter, n2 -> NodeRole.Voter, n3 -> NodeRole.Learner)
      )
      val ldr: NodeState.Leader = Leader(
        term = 4L,
        nextIndex = Map(n2 -> 2L, n3 -> 1L),
        matchIndex = Map(n2 -> 1L, n3 -> 0L),
        commitIndex = 2L
      )
      val termLookup: Long => Option[Long] =
        idx => if idx <= 5L then Some(4L) else None

      // InstallSnapshotResponse: higher-term step-down + quorum commit advancement + matchIndex==0 heartbeat
      val snapStepDown = RaftLogic.onMessage(
        state = ldr,
        message =
          InstallSnapshotResponse(term = 9L, success = true, matchIndex = 5L),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        getTermAt = termLookup,
        sender = Some(n2),
        clusterOpt = Some(cl)
      )
      snapStepDown.state shouldBe Follower(9L, None, None, 2L)

      val snapCommit = RaftLogic.onMessage(
        state = ldr,
        message =
          InstallSnapshotResponse(term = 4L, success = true, matchIndex = 5L),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        getTermAt = termLookup,
        sender = Some(n2),
        clusterOpt = Some(cl)
      )
      snapCommit.state.commitIndex shouldBe 5L
      snapCommit.effects.exists {
        case CommitEntries(_) => true
        case _                => false
      } shouldBe true

      val snapZero = RaftLogic.onMessage(
        state = ldr,
        message =
          InstallSnapshotResponse(term = 4L, success = true, matchIndex = 0L),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        getTermAt = termLookup,
        sender = Some(n2),
        clusterOpt = None
      )
      snapZero.state.isLeader shouldBe true

      // AddServerRequest: Voter (joint consensus), Learner, rejected when in joint consensus, Follower, Candidate
      val addVoter = RaftLogic.onMessage(
        state = ldr,
        message = AddServerRequest(4L, n4, isLearner = false),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        getTermAt = termLookup,
        sender = Some(n4),
        clusterOpt = Some(cl)
      )
      addVoter.effects.exists {
        case ApplyClusterConfig(c, _, _) => c.isInJointConsensus
        case _                           => false
      } shouldBe true

      val addRejected = RaftLogic.onMessage(
        state = ldr.copy(transferTarget = Some(n2)),
        message = AddServerRequest(4L, n4, isLearner = false),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        getTermAt = termLookup,
        sender = Some(n4),
        clusterOpt = Some(cl)
      )
      addRejected.effects should contain(
        SendMessage(n4, AddServerResponse(4L, success = false, Some(n1)))
      )

      val addOnFollower = RaftLogic.onMessage(
        state = Follower(4L, None, Some(n1), 2L),
        message = AddServerRequest(4L, n4, isLearner = true),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        sender = Some(n4)
      )
      addOnFollower.effects should contain(
        SendMessage(n4, AddServerResponse(4L, success = false, Some(n1)))
      )

      val addOnCandidate = RaftLogic.onMessage(
        state = Candidate(4L, Set(n2), 2L),
        message = AddServerRequest(4L, n4, isLearner = true),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        sender = Some(n4)
      )
      addOnCandidate.effects should contain(
        SendMessage(n4, AddServerResponse(4L, success = false, None))
      )

      // RemoveServerRequest: Voter (joint consensus), Learner, rejected sole voter, Follower, Candidate
      val remVoter = RaftLogic.onMessage(
        state = ldr,
        message = RemoveServerRequest(4L, n2),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        getTermAt = termLookup,
        sender = Some(n2),
        clusterOpt = Some(cl)
      )
      remVoter.effects.exists {
        case ApplyClusterConfig(c, _, _) => c.isInJointConsensus
        case _                           => false
      } shouldBe true

      val remLearner = RaftLogic.onMessage(
        state = ldr,
        message = RemoveServerRequest(4L, n3),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        getTermAt = termLookup,
        sender = Some(n3),
        clusterOpt = Some(cl)
      )
      remLearner.effects.exists {
        case ApplyClusterConfig(c, _, _) => !c.contains(n3)
        case _                           => false
      } shouldBe true

      val remSoleRejected = RaftLogic.onMessage(
        state = ldr,
        message = RemoveServerRequest(4L, n1),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 1,
        getTermAt = termLookup,
        sender = Some(n1),
        clusterOpt = Some(ClusterConfig.single(n1))
      )
      remSoleRejected.effects should contain(
        SendMessage(n1, RemoveServerResponse(4L, success = false, Some(n1)))
      )

      val remOnFollower = RaftLogic.onMessage(
        state = Follower(4L, None, Some(n1), 2L),
        message = RemoveServerRequest(4L, n2),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        sender = Some(n2)
      )
      remOnFollower.effects should contain(
        SendMessage(n2, RemoveServerResponse(4L, success = false, Some(n1)))
      )

      val remOnCandidate = RaftLogic.onMessage(
        state = Candidate(4L, Set(n2), 2L),
        message = RemoveServerRequest(4L, n2),
        config = rCfg,
        lastLogIndex = 5L,
        lastLogTerm = 4L,
        clusterSize = 2,
        sender = Some(n2)
      )
      remOnCandidate.effects should contain(
        SendMessage(n2, RemoveServerResponse(4L, success = false, None))
      )

      RaftLogic.isLogUpToDate(10L, 3L, 20L, 2L) shouldBe true
      RaftLogic.isLogUpToDate(5L, 2L, 8L, 2L) shouldBe false
    }
  }
