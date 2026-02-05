package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect.*

/**
 * Log Matching Validation Tests
 * 
 * RAFT requires that AppendEntries includes prevLogIndex and prevLogTerm
 * to ensure log consistency. These tests verify the validation logic.
 */
class LogMatchingSpec extends AnyFlatSpec with Matchers:
  
  val config = RaftConfig(
    localId = NodeId("node-1"),
    electionTimeoutMin = scala.concurrent.duration.Duration(150, "ms"),
    electionTimeoutMax = scala.concurrent.duration.Duration(300, "ms"),
    heartbeatInterval = scala.concurrent.duration.Duration(50, "ms")
  )
  
  "Log matching" should "accept when prevLogIndex is 0 (empty log)" in {
    val follower = Follower(1, None, None)
    val req = AppendEntriesRequest(
      term = 1,
      leaderId = NodeId("leader"),
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val transition = RaftLogic.onMessage(
      follower, req, config,
      lastLogIndex = 0, lastLogTerm = 0, clusterSize = 3,
      getTermAt = _ => None
    )
    
    // Should accept - empty log always matches prevLogIndex=0
    val response = findResponse(transition.effects)
    response shouldBe defined
    response.get.success shouldBe true
  }
  
  it should "accept when log matches prevLogIndex and prevLogTerm" in {
    val follower = Follower(5, None, Some(NodeId("leader")))
    val req = AppendEntriesRequest(
      term = 5,
      leaderId = NodeId("leader"),
      prevLogIndex = 10,
      prevLogTerm = 4,
      entries = Seq(Log.command(11, 5, "cmd".getBytes)),
      leaderCommit = 0
    )
    
    // Mock: log has entry at index 10 with term 4
    val getTermAt: Long => Option[Long] = {
      case 10 => Some(4L)
      case _  => None
    }
    
    val transition = RaftLogic.onMessage(
      follower, req, config,
      lastLogIndex = 10, lastLogTerm = 4, clusterSize = 3,
      getTermAt = getTermAt
    )
    
    val response = findResponse(transition.effects)
    response shouldBe defined
    response.get.success shouldBe true
    response.get.matchIndex shouldBe 11 // prevLogIndex + entries.size
  }
  
  it should "reject when log is too short (prevLogIndex missing)" in {
    val follower = Follower(5, None, None)
    val req = AppendEntriesRequest(
      term = 5,
      leaderId = NodeId("leader"),
      prevLogIndex = 100,  // We don't have this entry
      prevLogTerm = 4,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    // Mock: log only has 10 entries
    val getTermAt: Long => Option[Long] = _ => None
    
    val transition = RaftLogic.onMessage(
      follower, req, config,
      lastLogIndex = 10, lastLogTerm = 4, clusterSize = 3,
      getTermAt = getTermAt
    )
    
    val response = findResponse(transition.effects)
    response shouldBe defined
    response.get.success shouldBe false
  }
  
  it should "reject when term at prevLogIndex doesn't match" in {
    val follower = Follower(5, None, None)
    val req = AppendEntriesRequest(
      term = 5,
      leaderId = NodeId("leader"),
      prevLogIndex = 10,
      prevLogTerm = 4,  // Leader thinks term is 4
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    // Mock: entry at index 10 has term 3, not 4
    val getTermAt: Long => Option[Long] = {
      case 10 => Some(3L)  // Different term!
      case _  => None
    }
    
    val transition = RaftLogic.onMessage(
      follower, req, config,
      lastLogIndex = 10, lastLogTerm = 3, clusterSize = 3,
      getTermAt = getTermAt
    )
    
    val response = findResponse(transition.effects)
    response shouldBe defined
    response.get.success shouldBe false
  }
  
  it should "reject AppendEntries from older term" in {
    val follower = Follower(10, None, None)  // Current term is 10
    val req = AppendEntriesRequest(
      term = 5,  // Old term
      leaderId = NodeId("old-leader"),
      prevLogIndex = 0,
      prevLogTerm = 0,
      entries = Seq.empty,
      leaderCommit = 0
    )
    
    val transition = RaftLogic.onMessage(
      follower, req, config,
      lastLogIndex = 0, lastLogTerm = 0, clusterSize = 3
    )
    
    val response = findResponse(transition.effects)
    response shouldBe defined
    response.get.success shouldBe false
    response.get.term shouldBe 10  // Reply with our higher term
  }
  
  private def findResponse(effects: List[raft.effect.Effect]): Option[AppendEntriesResponse] =
    effects.collectFirst {
      case SendMessage(_, resp: AppendEntriesResponse) => resp
    }
