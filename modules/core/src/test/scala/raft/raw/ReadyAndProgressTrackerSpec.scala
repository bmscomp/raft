package raft.raw

import java.nio.charset.StandardCharsets
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import raft.logic.{
  Inflights,
  Progress,
  ProgressState,
  ProgressTracker,
  VoteResult
}
import raft.message.RaftMessage.HeartbeatTimeout
import raft.state.*
import raft.state.NodeState.Follower

class ReadyAndProgressTrackerSpec
    extends AnyFreeSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val n4 = NodeId("n4")
  private val n5 = NodeId("n5")

  "Ready and LightReady" - {
    "should accurately report isEmpty and nonEmpty for every field" in {
      val emptyReady = Ready()
      emptyReady.isEmpty shouldBe true
      emptyReady.nonEmpty shouldBe false

      val nonEmptyVariants = List(
        Ready(softState = Some(SoftState(Some(n1), Follower(1L)))),
        Ready(hardState = Some(HardState(1L, Some(n1), 0L))),
        Ready(truncateFromIndex = Some(3L)),
        Ready(entries = Vector(Log.noOp(1L, 1L))),
        Ready(snapshot = Some(SnapshotMetadata(10L, 2L))),
        Ready(messages = Vector(OutboundMessage(n1, n2, HeartbeatTimeout))),
        Ready(persistedMessages =
          Vector(OutboundMessage(n1, n2, HeartbeatTimeout))
        ),
        Ready(committedEntries = Vector(Log.noOp(1L, 1L))),
        Ready(readStates = Vector(ReadState(5L, "ctx-1")))
      )

      nonEmptyVariants.foreach { rd =>
        rd.isEmpty shouldBe false
        rd.nonEmpty shouldBe true
      }

      LightReady().isEmpty shouldBe true
      LightReady(commitIndex = Some(2L)).isEmpty shouldBe false
      LightReady(committedEntries =
        Vector(Log.noOp(1L, 1L))
      ).isEmpty shouldBe false
      LightReady(messages =
        Vector(OutboundMessage(n1, n2, HeartbeatTimeout))
      ).isEmpty shouldBe false
    }
  }

  "MemStorage" - {
    "should enforce maxBytes in entries, resolve snapshot boundary terms, and compact cleanly" in {
      val cfg = ClusterConfig(Set(n1, n2, n3))
      val store = MemStorage.create(cfg)
      store.firstIndex shouldBe 1L
      store.lastIndex shouldBe 0L
      store.term(0L) shouldBe Some(0L)
      store.term(1L) shouldBe None
      store.entries(1L, 1L) shouldBe empty

      val payload10 = Array.fill[Byte](10)(1.toByte)
      store.append(
        Vector(
          Log.command(1L, 1L, payload10),
          Log.command(2L, 1L, payload10),
          Log.command(3L, 2L, payload10)
        )
      )

      // maxBytes = 5 still returns at least the first entry (10 bytes) to prevent starvation
      store.entries(1L, 4L, maxBytes = 5L).map(_.index) shouldBe Vector(1L)
      // maxBytes = 20 returns entries 1 and 2, stopping before entry 3 (30 > 20)
      store.entries(1L, 4L, maxBytes = 20L).map(_.index) shouldBe Vector(1L, 2L)

      val snapPayload = "snap-v1".getBytes(StandardCharsets.UTF_8)
      store.compactTo(2L, snapPayload)
      store.firstIndex shouldBe 3L
      store.lastIndex shouldBe 3L
      store.term(1L) shouldBe None
      store.term(2L) shouldBe Some(1L)
      store.term(3L) shouldBe Some(2L)
      store.snapshotMetadata.get.data.toSeq shouldBe snapPayload.toSeq

      val expandedCfg = ClusterConfig(Set(n1, n2, n3, n4))
      store.applySnapshot(
        SnapshotMetadata(10L, 4L, Some(expandedCfg), snapPayload)
      )
      store.firstIndex shouldBe 11L
      store.lastIndex shouldBe 10L
      store.clusterConfig shouldBe expandedCfg
      store.term(10L) shouldBe Some(4L)
      store.term(9L) shouldBe None
    }
  }

  "Inflights window" - {
    "should track capacity, free prefixes, and reject non-positive capacity" in {
      an[IllegalArgumentException] should be thrownBy Inflights(0)
      an[IllegalArgumentException] should be thrownBy Inflights(-3)

      val inf0 = Inflights(3)
      inf0.isEmpty shouldBe true
      inf0.isFull shouldBe false

      val inf3 = inf0.add(10L).add(20L).add(30L)
      inf3.count shouldBe 3
      inf3.isFull shouldBe true
      // Saturated add is a safe no-op
      inf3.add(40L).count shouldBe 3

      val infFreed = inf3.freeTo(20L)
      infFreed.count shouldBe 1
      infFreed.isFull shouldBe false

      infFreed.freeFirstOne.isEmpty shouldBe true
      inf3.reset.isEmpty shouldBe true
    }
  }

  "Progress state transitions and conflict backtracking" - {
    "should transition cleanly across Probe, Replicate, and Snapshot" in {
      val p0 = Progress(matched = 5L, nextIdx = 6L, inflights = Inflights(2))
      p0.isPaused shouldBe false

      val pProbeSent = p0.optimisticUpdate(6L)
      pProbeSent.probeSent shouldBe true
      pProbeSent.isPaused shouldBe true

      val pRep = pProbeSent.becomeReplicate()
      pRep.state shouldBe ProgressState.Replicate
      pRep.isPaused shouldBe false

      val pRepFull = pRep.optimisticUpdate(7L).optimisticUpdate(8L)
      pRepFull.nextIdx shouldBe 9L
      pRepFull.isPaused shouldBe true

      val (pAcked, moved) = pRepFull.maybeUpdate(7L)
      moved shouldBe true
      pAcked.matched shouldBe 7L
      pAcked.isPaused shouldBe false

      val (pStale, movedStale) = pAcked.maybeUpdate(6L)
      movedStale shouldBe false
      pStale.matched shouldBe 7L

      val pSnap = pAcked.becomeSnapshot(20L)
      pSnap.isPaused shouldBe true
      pSnap.optimisticUpdate(25L) shouldBe pSnap
      pSnap.maybeDecrTo(19L) shouldBe (pSnap, false)

      val pAfterSnap = pSnap.becomeProbe()
      pAfterSnap.state shouldBe ProgressState.Probe
      pAfterSnap.nextIdx shouldBe 21L
    }

    "should backtrack accurately using §5.3 conflictTerm and conflictIndex hints" in {
      // 1. Replicate -> Probe on valid rejection; ignore stale rejection <= matched
      val rep = Progress(
        matched = 10L,
        nextIdx = 15L,
        state = ProgressState.Replicate
      )
      rep.maybeDecrTo(10L) shouldBe (rep, false)
      val (probing, dec1) = rep.maybeDecrTo(14L)
      dec1 shouldBe true
      probing.state shouldBe ProgressState.Probe
      probing.nextIdx shouldBe 11L

      // 2. Probe with conflictTerm known to leader: jump to lastIdxOfTerm + 1
      val probeHigh =
        Progress(matched = 2L, nextIdx = 20L, state = ProgressState.Probe)
      val (jumpedKnown, dec2) = probeHigh.maybeDecrTo(
        rejectedIndex = 19L,
        conflictIndex = Some(5L),
        conflictTerm = Some(3L),
        findLastIndexOfTerm = {
          case 3L => Some(8L)
          case _  => None
        }
      )
      dec2 shouldBe true
      jumpedKnown.nextIdx shouldBe 9L

      // 3. Probe with conflictTerm absent on leader: jump directly to conflictIndex
      val (jumpedUnknown, dec3) = probeHigh.maybeDecrTo(
        rejectedIndex = 19L,
        conflictIndex = Some(5L),
        conflictTerm = Some(3L),
        findLastIndexOfTerm = _ => None
      )
      dec3 shouldBe true
      jumpedUnknown.nextIdx shouldBe 5L

      // 4. Stale probe rejection (< matched) is ignored
      val probeMatched =
        Progress(matched = 10L, nextIdx = 15L, state = ProgressState.Probe)
      probeMatched.maybeDecrTo(8L) shouldBe (probeMatched, false)
    }
  }

  "ProgressTracker quorums, Joint Consensus, and monotonicity" - {
    "should tally votes in single and Joint Consensus configurations" in {
      val singleCfg = ClusterConfig(Set(n1, n2, n3))
      val pt0 = ProgressTracker.forLeader(n1, singleCfg, lastLogIndex = 10L)

      val pt1 = pt0.recordVote(n1, true)
      pt1.tallyVotes shouldBe VoteResult.Pending
      // Duplicate vote from same voter is ignored
      pt1.recordVote(n1, false).votes(n1) shouldBe true
      pt1.recordVote(n2, true).tallyVotes shouldBe VoteResult.Won
      pt0.recordVote(n2, false).recordVote(n3, false).tallyVotes shouldBe
        VoteResult.Lost

      // Joint Consensus: old = {n1, n2, n3}, new = {n3, n4, n5}
      val jointCfg = singleCfg.beginJointConsensus(
        Map(n3 -> NodeRole.Voter, n4 -> NodeRole.Voter, n5 -> NodeRole.Voter)
      )
      val jpt = ProgressTracker.forLeader(n3, jointCfg, lastLogIndex = 10L)
      // Won in old ({n1, n2}), pending in new ({n3}) -> overall Pending
      val jptOldOnly =
        jpt.recordVote(n1, true).recordVote(n2, true).recordVote(n3, true)
      jptOldOnly.tallyVotes shouldBe VoteResult.Pending
      // Add n4 -> Won in both old and new!
      jptOldOnly.recordVote(n4, true).tallyVotes shouldBe VoteResult.Won
      // Lost in new ({n4=false, n5=false}) -> overall Lost even if old won!
      jptOldOnly
        .recordVote(n4, false)
        .recordVote(n5, false)
        .tallyVotes shouldBe VoteResult.Lost
    }

    "should track CheckQuorum activity and dynamic config updates" in {
      val cfg = ClusterConfig(Set(n1, n2, n3))
      val pt0 = ProgressTracker.forLeader(n1, cfg, lastLogIndex = 5L)
      pt0.quorumRecentlyActive(n1) shouldBe false

      val ptActive = pt0.updateProgress(n2, _.copy(recentActive = true))
      ptActive.quorumRecentlyActive(n1) shouldBe true

      val ptReset = ptActive.resetRecentActive(n1)
      ptReset.quorumRecentlyActive(n1) shouldBe false
      ptReset.updateProgress(NodeId("missing"), identity) shouldBe ptReset

      val withLearnerCfg = cfg.addMember(n4, NodeRole.Learner)
      val ptReconf = ptActive.withConfig(withLearnerCfg, lastIndex = 12L)
      ptReconf.progress(n4).isLearner shouldBe true
      ptReconf.progress(n4).nextIdx shouldBe 13L
    }

    "should satisfy commit-index monotonicity under single and Joint Consensus" in {
      val matchGen = for
        m1 <- Gen.choose(0L, 500L)
        m2 <- Gen.choose(0L, 500L)
        m3 <- Gen.choose(0L, 500L)
        m4 <- Gen.choose(0L, 500L)
        m5 <- Gen.choose(0L, 500L)
        delta <- Gen.choose(1L, 100L)
      yield (m1, m2, m3, m4, m5, delta)

      val jointCfg = ClusterConfig(Set(n1, n2, n3)).beginJointConsensus(
        Map(
          n1 -> NodeRole.Voter,
          n2 -> NodeRole.Voter,
          n3 -> NodeRole.Voter,
          n4 -> NodeRole.Voter,
          n5 -> NodeRole.Voter
        )
      )

      forAll(matchGen) { case (m1, m2, m3, m4, m5, delta) =>
        val base = ProgressTracker
          .forLeader(n1, jointCfg, 0L)
          .updateProgress(n1, _.copy(matched = m1))
          .updateProgress(n2, _.copy(matched = m2))
          .updateProgress(n3, _.copy(matched = m3))
          .updateProgress(n4, _.copy(matched = m4))
          .updateProgress(n5, _.copy(matched = m5))

        val c0 = base.maximalCommittedIndex
        val advanced =
          base.updateProgress(n2, p => p.copy(matched = p.matched + delta))
        val c1 = advanced.maximalCommittedIndex
        c1 should be >= c0
      }
    }
  }
