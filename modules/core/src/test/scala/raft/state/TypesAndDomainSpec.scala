package raft.state

import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class TypesAndDomainSpec
    extends AnyFreeSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  private val nonNegLongGen: Gen[Long] = Gen.choose(0L, 1_000_000_000L)
  private val negLongGen: Gen[Long] = Gen.choose(-1_000_000L, -1L)

  "Term opaque type" - {
    "should validate non-negative values and reject negative values" in {
      Term.Zero.value shouldBe 0L
      Term(0L) shouldBe Right(Term.Zero)
      Term(42L).map(_.value) shouldBe Right(42L)

      forAll(negLongGen) { neg =>
        Term(neg) match
          case Left(err) =>
            err shouldBe InvalidTerm(neg)
            err.getMessage should include(neg.toString)
          case Right(other) =>
            fail(s"Expected InvalidTerm, got $other")
      }
    }

    "should satisfy total-order, arithmetic, and min/max laws" in {
      forAll(nonNegLongGen, nonNegLongGen) { (aRaw, bRaw) =>
        val a = Term.unsafeFrom(aRaw)
        val b = Term.unsafeFrom(bRaw)

        a.increment.value shouldBe (aRaw + 1L)
        (a > b) shouldBe (aRaw > bRaw)
        (a >= b) shouldBe (aRaw >= bRaw)
        (a < b) shouldBe (aRaw < bRaw)
        (a <= b) shouldBe (aRaw <= bRaw)
        (a == b) shouldBe (aRaw == bRaw)
        a.max(b).value shouldBe math.max(aRaw, bRaw)
        a.min(b).value shouldBe math.min(aRaw, bRaw)
        summon[Ordering[Term]].compare(a, b).sign shouldBe
          java.lang.Long.compare(aRaw, bRaw).sign
      }
    }
  }

  "LogIndex opaque type" - {
    "should validate non-negative indices, clamp prev at 0, and reject negative indices" in {
      LogIndex.Zero.value shouldBe 0L
      LogIndex.First.value shouldBe 1L
      LogIndex.Zero.prev shouldBe LogIndex.Zero
      LogIndex.First.prev shouldBe LogIndex.Zero
      LogIndex.First.next.value shouldBe 2L
      LogIndex(0L) shouldBe Right(LogIndex.Zero)

      forAll(negLongGen) { neg =>
        LogIndex(neg) match
          case Left(err) =>
            err shouldBe InvalidLogIndex(neg)
            err.getMessage should include(neg.toString)
          case Right(other) =>
            fail(s"Expected InvalidLogIndex, got $other")
      }
    }

    "should satisfy arithmetic, distance, min/max, and total-order laws" in {
      forAll(nonNegLongGen, nonNegLongGen, Gen.choose(0L, 1000L)) {
        (aRaw, bRaw, offset) =>
          val a = LogIndex.unsafeFrom(aRaw)
          val b = LogIndex.unsafeFrom(bRaw)

          (a + offset).value shouldBe (aRaw + offset)
          (a - b) shouldBe (aRaw - bRaw)
          if aRaw > 0L then a.prev.value shouldBe (aRaw - 1L)
          else a.prev.value shouldBe 0L

          (a > b) shouldBe (aRaw > bRaw)
          (a >= b) shouldBe (aRaw >= bRaw)
          (a < b) shouldBe (aRaw < bRaw)
          (a <= b) shouldBe (aRaw <= bRaw)
          (a == b) shouldBe (aRaw == bRaw)
          a.max(b).value shouldBe math.max(aRaw, bRaw)
          a.min(b).value shouldBe math.min(aRaw, bRaw)
          summon[Ordering[LogIndex]].compare(a, b).sign shouldBe
            java.lang.Long.compare(aRaw, bRaw).sign
      }
    }
  }

  "GroupId opaque type" - {
    "should preserve string value and lexical ordering" in {
      val g0 = GroupId("shard-0")
      val g1 = GroupId("shard-1")
      g0.value shouldBe "shard-0"
      List(g1, g0).sorted shouldBe List(g0, g1)
    }
  }

  "LogId compound coordinate" - {
    "should enforce non-negative invariants and construct from Log" in {
      LogId.Zero shouldBe LogId(0L, 0L)
      val entry = Log.command(17L, 4L, Array[Byte](1, 2))
      LogId.fromLog(entry) shouldBe LogId(4L, 17L)

      an[IllegalArgumentException] should be thrownBy LogId(-1L, 0L)
      an[IllegalArgumentException] should be thrownBy LogId(0L, -1L)
    }

    "should obey strict lexicographic (term, index) total-order laws (§5.4.1)" in {
      val logIdGen = for
        t <- Gen.choose(0L, 50L)
        i <- Gen.choose(0L, 200L)
      yield LogId(t, i)

      // Higher term wins even with shorter index
      (LogId(5L, 1L) > LogId(4L, 999L)) shouldBe true

      forAll(logIdGen, logIdGen, logIdGen) { (a, b, c) =>
        // Reflexivity
        a.compare(a) shouldBe 0
        // Antisymmetry
        a.compare(b).sign shouldBe -b.compare(a).sign
        // Transitivity
        if a <= b && b <= c then (a <= c) shouldBe true
        // Consistency with Ordering[LogId]
        summon[Ordering[LogId]].compare(a, b).sign shouldBe a.compare(b).sign
      }
    }
  }

  "HardState" - {
    "should expose Empty, typed Term view, and reject negative fields" in {
      HardState.Empty.isEmpty shouldBe true
      HardState.empty shouldBe HardState.Empty
      HardState(0L, Some(NodeId("n1")), 0L).isEmpty shouldBe false
      HardState(0L, None, 1L).isEmpty shouldBe false

      val hs = HardState(Term.unsafeFrom(9L), Some(NodeId("voter-2")))
      hs.term shouldBe 9L
      hs.currentTerm.value shouldBe 9L
      hs.votedFor shouldBe Some(NodeId("voter-2"))
      hs.commitIndex shouldBe 0L
      hs.isEmpty shouldBe false

      an[IllegalArgumentException] should be thrownBy HardState(-1L, None, 0L)
      an[IllegalArgumentException] should be thrownBy HardState(0L, None, -5L)
    }
  }

  "Log value equality & hashing" - {
    "should compare payload byte contents rather than array identity" in {
      val bytes1 = Array[Byte](10, 20, 30)
      val bytes2 = Array[Byte](10, 20, 30)
      val bytesDiff = Array[Byte](10, 20, 99)

      val logA = Log.command(5L, 2L, bytes1)
      val logB = Log.command(5L, 2L, bytes2)
      val logC = Log.command(5L, 2L, bytesDiff)
      val noOp = Log.noOp(5L, 2L)

      logA shouldBe logB
      logA.hashCode() shouldBe logB.hashCode()
      logA should not be logC
      logA should not be noOp
      logA.equals("not-a-log") shouldBe false
      noOp.logType shouldBe LogType.NoOp
      noOp.data shouldBe empty
    }
  }

  "RaftError hierarchy" - {
    "should format descriptive messages across all 8 subtypes" in {
      val cfg = ClusterConfig(Set(NodeId("n1")))
      val errors: List[RaftError] = List(
        RaftError.NotLeader(Some(NodeId("n1"))),
        RaftError.NotLeader(None),
        RaftError.ElectionInProgress,
        RaftError.LeadershipTransferInProgress(NodeId("n2")),
        RaftError.ConfigurationChangeInProgress(cfg),
        RaftError.LeaderNotReadyInTerm(4L),
        RaftError.ProposalDropped("shutting down"),
        RaftError.GroupNotFound(GroupId("g-9")),
        RaftError.StorageCorruption("CRC mismatch")
      )

      errors.foreach { err =>
        err.detail should not be empty
        err.getMessage shouldBe err.detail
      }

      RaftError.NotLeader(Some(NodeId("n1"))).detail should include("n1")
      RaftError.NotLeader(None).detail should include("unknown")
      RaftError.LeaderNotReadyInTerm(4L).detail should include("term 4")
      RaftError.GroupNotFound(GroupId("g-9")).detail should include("g-9")
      RaftError.StorageCorruption("CRC mismatch").detail should include("CRC")
    }
  }

  "RaftMessage.term extension and CompactionConfig.shouldCompactPure" - {
    "should extract Some(term) for RPC messages and None for read/timer messages" in {
      import raft.message.RaftMessage
      import raft.message.RaftMessage.*

      val n1 = NodeId("n1")
      val withTerms: List[(RaftMessage, Long)] = List(
        AppendEntriesRequest(3L, n1, 0L, 0L, Nil, 0L) -> 3L,
        AppendEntriesResponse(4L, true, 1L) -> 4L,
        RequestVoteRequest(5L, n1, 0L, 0L) -> 5L,
        RequestVoteResponse(6L, true) -> 6L,
        InstallSnapshotRequest(
          7L,
          n1,
          10L,
          2L,
          0L,
          Array.emptyByteArray,
          true
        ) -> 7L,
        InstallSnapshotResponse(8L, true, 10L) -> 8L,
        TransferLeadershipRequest(9L, n1) -> 9L,
        TimeoutNowRequest(10L, n1) -> 10L,
        AddServerRequest(11L, n1, false) -> 11L,
        AddServerResponse(12L, true, Some(n1)) -> 12L,
        RemoveServerRequest(13L, n1) -> 13L,
        RemoveServerResponse(14L, true, None) -> 14L
      )
      withTerms.foreach { case (m, expected) =>
        m.term shouldBe Some(expected)
      }

      val withoutTerms: List[RaftMessage] = List(
        ReadIndexRequest("r1"),
        ReadIndexResponse("r1", true, 5L),
        ElectionTimeout,
        HeartbeatTimeout,
        CheckQuorumTimeout
      )
      withoutTerms.foreach(_.term shouldBe None)
    }

    "should evaluate pure compaction thresholds across all CompactionLevel presets" in {
      CompactionConfig(CompactionLevel.Disabled).shouldCompactPure(
        50_000L,
        0L
      ) shouldBe false
      CompactionConfig(CompactionLevel.Custom).shouldCompactPure(
        50_000L,
        0L
      ) shouldBe false
      CompactionConfig(CompactionLevel.Aggressive).shouldCompactPure(
        99L,
        0L
      ) shouldBe false
      CompactionConfig(CompactionLevel.Aggressive).shouldCompactPure(
        100L,
        0L
      ) shouldBe true
      CompactionConfig(CompactionLevel.Moderate).shouldCompactPure(
        1_000L,
        0L
      ) shouldBe true
      CompactionConfig(CompactionLevel.Conservative).shouldCompactPure(
        10_000L,
        0L
      ) shouldBe true
    }
  }
