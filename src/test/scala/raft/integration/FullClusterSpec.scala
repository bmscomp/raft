package raft.integration

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.logic.RaftLogic
import raft.impl.{InMemLogStore, InMemStableStore, InMemTransport}
import raft.effect.Effect.*

/**
 * Full Cluster Integration Tests
 * 
 * Tests end-to-end RAFT behavior with 3-node cluster.
 */
class FullClusterSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers:
  
  val baseConfig = RaftConfig(
    localId = NodeId("node-1"),
    electionTimeoutMin = 150.millis,
    electionTimeoutMax = 300.millis,
    heartbeatInterval = 50.millis
  )
  
  "3-node cluster" should "elect a leader after election timeout" in {
    val config = baseConfig.copy(localId = NodeId("node-1"))
    val follower = Follower(0, None, None)
    
    // Simulate election timeout (with pre-vote enabled by default)
    val transition = RaftLogic.onMessage(
      follower, ElectionTimeout, config,
      lastLogIndex = 0, lastLogTerm = 0, clusterSize = 3
    )
    
    // Should become PreCandidate first (pre-vote phase)
    transition.state shouldBe a[PreCandidate]
    transition.state.term shouldBe 0  // Term not incremented during pre-vote
    
    // Should broadcast pre-vote requests
    val broadcasts = transition.effects.collect { case Broadcast(msg) => msg }
    broadcasts should not be empty
    broadcasts.head.asInstanceOf[RequestVoteRequest].isPreVote shouldBe true
  }
  
  it should "candidate becomes leader with majority votes" in {
    val config = baseConfig.copy(localId = NodeId("candidate"))
    val candidate: Candidate = Candidate(
      term = 5,
      votesReceived = Set(NodeId("candidate"))  // Self-vote
    )
    
    // Receive vote from node-2
    val voteResp: RequestVoteResponse = RequestVoteResponse(term = 5, voteGranted = true, isPreVote = false)
    
    // Use onVoteResponse for proper voter tracking
    val transition = RaftLogic.onVoteResponse(
      candidate, NodeId("node-2"), voteResp, config, clusterSize = 3
    )
    
    // Now has 2 votes out of 3 = majority
    transition.state shouldBe a[Leader]
    
    // Should have BecomeLeader effect
    transition.effects should contain(BecomeLeader)
  }
  
  it should "follower grants vote to qualified candidate" in {
    val config = baseConfig.copy(localId = NodeId("follower"))
    val follower = Follower(3, None, None)
    
    val voteReq = RequestVoteRequest(
      term = 5,
      candidateId = NodeId("candidate"),
      lastLogIndex = 10,
      lastLogTerm = 4,
      isPreVote = false
    )
    
    val transition = RaftLogic.onMessage(
      follower, voteReq, config,
      lastLogIndex = 5, lastLogTerm = 3, clusterSize = 3
    )
    
    // Should grant vote - candidate's log is more up-to-date
    transition.state shouldBe a[Follower]
    transition.state.asInstanceOf[Follower].votedFor shouldBe Some(NodeId("candidate"))
    
    val response = transition.effects.collectFirst {
      case SendMessage(_, resp: RequestVoteResponse) => resp
    }
    response shouldBe defined
    response.get.voteGranted shouldBe true
  }
  
  it should "leader sends heartbeats with commit index" in {
    val config = baseConfig.copy(localId = NodeId("leader"))
    val leader = Leader(
      term = 5,
      nextIndex = Map.empty,
      matchIndex = Map.empty,
      commitIndex = 42
    )
    
    val transition = RaftLogic.onMessage(
      leader, HeartbeatTimeout, config,
      lastLogIndex = 50, lastLogTerm = 5, clusterSize = 3
    )
    
    // Should broadcast heartbeat
    val heartbeat = transition.effects.collectFirst {
      case Broadcast(ae: AppendEntriesRequest) => ae
    }
    
    heartbeat shouldBe defined
    heartbeat.get.term shouldBe 5
    heartbeat.get.leaderCommit shouldBe 42
    heartbeat.get.entries shouldBe empty
  }
  
  "Log replication" should "follower appends entries from leader" in {
    val config = baseConfig.copy(localId = NodeId("follower"))
    val follower = Follower(5, None, Some(NodeId("leader")))
    
    val entries = Seq(
      Log.command(11, 5, "cmd1".getBytes),
      Log.command(12, 5, "cmd2".getBytes)
    )
    
    val appendReq = AppendEntriesRequest(
      term = 5,
      leaderId = NodeId("leader"),
      prevLogIndex = 10,
      prevLogTerm = 4,
      entries = entries,
      leaderCommit = 8
    )
    
    // Mock log match
    val getTermAt: Long => Option[Long] = {
      case 10 => Some(4L)
      case _ => None
    }
    
    val transition = RaftLogic.onMessage(
      follower, appendReq, config,
      lastLogIndex = 10, lastLogTerm = 4, clusterSize = 3,
      getTermAt = getTermAt
    )
    
    // Should have AppendLogs effect
    val appendEffect = transition.effects.collectFirst {
      case AppendLogs(logs) => logs
    }
    
    appendEffect shouldBe defined
    appendEffect.get.size shouldBe 2
    
    // Should reply with success
    val response = transition.effects.collectFirst {
      case SendMessage(_, resp: AppendEntriesResponse) => resp
    }
    response shouldBe defined
    response.get.success shouldBe true
    response.get.matchIndex shouldBe 12  // 10 + 2 entries
  }
  
  "Node stepping down" should "higher term causes step down" in {
    val config = baseConfig.copy(localId = NodeId("leader"))
    val leader = Leader(term = 5)
    
    // Receive AppendEntries from higher term leader
    val appendReq = AppendEntriesRequest(
      term = 7,  // Higher term
      leaderId = NodeId("new-leader"),
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val transition = RaftLogic.onMessage(
      leader, appendReq, config,
      lastLogIndex = 10, lastLogTerm = 5, clusterSize = 3
    )
    
    // Should step down to follower
    transition.state shouldBe a[Follower]
    transition.state.term shouldBe 7
  }
