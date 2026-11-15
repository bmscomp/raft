package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect
import raft.effect.Effect.*

/** Adversarial and edge-case scenarios stressing RAFT protocol safety.
  *
  * Each test simulates a scenario where incorrect implementations would violate
  * consensus safety. These are intentionally hostile inputs.
  */
class ChaosScenarioSpec extends AnyFlatSpec with Matchers:

  val n1 = NodeId("node-1")
  val n2 = NodeId("node-2")
  val n3 = NodeId("node-3")
  val n4 = NodeId("node-4")
  val n5 = NodeId("node-5")

  val config = RaftConfig.default(n1).copy(preVoteEnabled = false)

  "Stale leader with concurrent writes" should "be rejected by followers at higher term" in {
    // Follower already at term 7 receives entries from old leader at term 5
    val follower = Follower(term = 7, votedFor = None, leaderId = Some(n3))

    val staleAppend = AppendEntriesRequest(
      term = 5,
      leaderId = n2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq(Log.command(1, 5, "stale-cmd".getBytes)),
      leaderCommit = 0
    )

    val trans = RaftLogic.onMessage(follower, staleAppend, config, 10, 7, 5)

    // Must reject: stale term
    trans.state.term shouldBe 7
    val resp = trans.effects.collectFirst {
      case SendMessage(_, r: AppendEntriesResponse) => r
    }.get
    resp.success shouldBe false
    resp.term shouldBe 7

    // No log append effects
    trans.effects.collect { case AppendLogs(_) => () } shouldBe empty
  }

  "Cascading elections" should "stabilize with monotonically increasing terms" in {
    // Three consecutive election timeouts on different nodes
    val configs = Map(
      n1 -> RaftConfig.default(n1).copy(preVoteEnabled = false),
      n2 -> RaftConfig.default(n2).copy(preVoteEnabled = false),
      n3 -> RaftConfig.default(n3).copy(preVoteEnabled = false)
    )

    // Round 1: node-1 starts election at term 1
    val t1 = RaftLogic.onMessage(
      Follower(0, None, None),
      ElectionTimeout,
      configs(n1),
      0,
      0,
      3
    )
    t1.state shouldBe a[Candidate]
    t1.state.term shouldBe 1

    // Round 2: node-2 also times out at term 1
    val t2 = RaftLogic.onMessage(
      Follower(0, None, None),
      ElectionTimeout,
      configs(n2),
      0,
      0,
      3
    )
    t2.state shouldBe a[Candidate]
    t2.state.term shouldBe 1

    // Neither gets majority → both timeout again
    val t1b =
      RaftLogic.onMessage(t1.state, ElectionTimeout, configs(n1), 0, 0, 3)
    t1b.state.term shouldBe 2 // term incremented

    val t2b =
      RaftLogic.onMessage(t2.state, ElectionTimeout, configs(n2), 0, 0, 3)
    t2b.state.term shouldBe 2

    // Round 3: node-3 starts at term 1, but sees voteReq from node-1 at term 2
    val voteReq = t1b.effects.collectFirst {
      case Broadcast(r: RequestVoteRequest) => r
    }.get
    voteReq.term shouldBe 2

    // node-3 receives vote request, steps up to term 2 and votes
    val t3 = RaftLogic.onMessage(
      Follower(0, None, None),
      voteReq,
      configs(n3),
      0,
      0,
      3
    )
    t3.state.term shouldBe 2

    // node-1 gets vote from node-3 → becomes leader
    val resp = t3.effects.collectFirst {
      case SendMessage(_, r: RequestVoteResponse) => r
    }.get
    resp.voteGranted shouldBe true

    val leaderTrans = RaftLogic.onVoteResponse(
      t1b.state.asInstanceOf[Candidate],
      n3,
      resp,
      configs(n1),
      3
    )
    leaderTrans.state shouldBe a[Leader]
    leaderTrans.state.term shouldBe 2
  }

  "Large term jump" should "cause immediate step-down" in {
    // Leader at term 1 receives message from term 1000
    val leader = Leader(term = 1)
    val hugeTermMsg = AppendEntriesRequest(
      term = 1000,
      leaderId = n2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )

    val trans = RaftLogic.onMessage(leader, hugeTermMsg, config, 0, 0, 3)
    trans.state shouldBe a[Follower]
    trans.state.term shouldBe 1000
    trans.state.asInstanceOf[Follower].leaderId shouldBe Some(n2)
  }

  "Duplicate AppendEntries" should "produce identical effects both times" in {
    val follower = Follower(term = 5, votedFor = None, leaderId = Some(n2))
    val appendReq = AppendEntriesRequest(
      term = 5,
      leaderId = n2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq(Log.command(1, 5, "cmd".getBytes)),
      leaderCommit = 0
    )

    val trans1 = RaftLogic.onMessage(follower, appendReq, config, 0, 0, 3)
    val trans2 = RaftLogic.onMessage(follower, appendReq, config, 0, 0, 3)

    // Same resulting state
    trans1.state.term shouldBe trans2.state.term
    trans1.state.getClass shouldBe trans2.state.getClass

    // Same number and types of effects
    trans1.effects.size shouldBe trans2.effects.size
    trans1.effects.zip(trans2.effects).foreach { case (e1, e2) =>
      e1.getClass shouldBe e2.getClass
    }
  }

  "Vote request from future term during active election" should "cause candidate to step down" in {
    val candidate = Candidate(term = 5, votesReceived = Set(n1))

    val futureVoteReq = RequestVoteRequest(
      term = 10,
      candidateId = n3,
      lastLogIndex = 100,
      lastLogTerm = 9,
      isPreVote = false
    )

    val trans = RaftLogic.onMessage(candidate, futureVoteReq, config, 50, 5, 5)

    // Must step down to follower at term 10
    trans.state shouldBe a[Follower]
    trans.state.term shouldBe 10
  }

  "Leader receiving AppendEntries at same term" should "reject from another leader" in {
    val leader = Leader(term = 5)
    val conflictAppend = AppendEntriesRequest(
      term = 5,
      leaderId = n2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )

    val trans = RaftLogic.onMessage(leader, conflictAppend, config, 10, 5, 3)

    // Leader at same term rejects — there can only be one leader per term
    val resp = trans.effects.collectFirst {
      case SendMessage(_, r: AppendEntriesResponse) => r
    }.get
    resp.success shouldBe false
  }

  "Follower receiving AppendEntries from two different leaders" should "only accept the higher term" in {
    val follower = Follower(term = 5, votedFor = None, leaderId = None)

    // First: leader at term 5
    val append1 = AppendEntriesRequest(
      term = 5,
      leaderId = n2,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    val trans1 = RaftLogic.onMessage(follower, append1, config, 0, 0, 3)
    trans1.state.asInstanceOf[Follower].leaderId shouldBe Some(n2)

    // Second: "leader" at term 4 (stale)
    val append2 = AppendEntriesRequest(
      term = 4,
      leaderId = n3,
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    val trans2 = RaftLogic.onMessage(trans1.state, append2, config, 0, 0, 3)

    // Must reject stale leader
    val resp = trans2.effects.collectFirst {
      case SendMessage(_, r: AppendEntriesResponse) => r
    }.get
    resp.success shouldBe false
    resp.term shouldBe 5

    // Leader ID unchanged
    trans2.state.asInstanceOf[Follower].leaderId shouldBe Some(n2)
  }

  "Multiple higher-term messages in sequence" should "always adopt the highest term seen" in {
    var state: NodeState = Follower(term = 1, votedFor = None, leaderId = None)

    for termValue <- List(3L, 7L, 15L, 100L) do
      val msg = AppendEntriesRequest(
        term = termValue,
        leaderId = n2,
        prevLogIndex = 0,
        prevLogTerm = 0,
        entries = Seq.empty,
        leaderCommit = 0
      )
      val trans = RaftLogic.onMessage(state, msg, config, 0, 0, 3)
      trans.state.term shouldBe termValue
      state = trans.state

    state.term shouldBe 100
  }

  "AppendEntriesResponse from OLD term" should "be ignored by leader" in {
    val leader = Leader(
      term = 10,
      nextIndex = Map(n2 -> 50),
      matchIndex = Map(n2 -> 0),
      commitIndex = 5
    )

    // Stale response from term 5
    val staleResp =
      AppendEntriesResponse(term = 5, success = true, matchIndex = 100)

    val trans = RaftLogic.onMessage(leader, staleResp, config, 50, 10, 3)

    // Leader should remain leader
    trans.state shouldBe a[Leader]
    trans.state.term shouldBe 10
  }
