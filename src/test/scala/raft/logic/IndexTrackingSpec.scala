package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*

/**
 * Index Tracking Tests
 * 
 * Tests for nextIndex and matchIndex initialization and updates.
 */
class IndexTrackingSpec extends AnyFlatSpec with Matchers:
  
  "Leader index initialization" should "set nextIndex to lastLogIndex + 1 for all followers" in {
    val leader: Leader = Leader(term = 5)
    val followers = Set(NodeId("f1"), NodeId("f2"), NodeId("f3"))
    val lastLogIndex = 100L
    
    val initialized = leader.initializeIndices(followers, lastLogIndex)
    
    initialized.nextIndex(NodeId("f1")) shouldBe 101L
    initialized.nextIndex(NodeId("f2")) shouldBe 101L
    initialized.nextIndex(NodeId("f3")) shouldBe 101L
  }
  
  it should "set matchIndex to 0 for all followers" in {
    val leader: Leader = Leader(term = 5)
    val followers = Set(NodeId("f1"), NodeId("f2"), NodeId("f3"))
    val lastLogIndex = 100L
    
    val initialized = leader.initializeIndices(followers, lastLogIndex)
    
    initialized.matchIndex(NodeId("f1")) shouldBe 0L
    initialized.matchIndex(NodeId("f2")) shouldBe 0L
    initialized.matchIndex(NodeId("f3")) shouldBe 0L
  }
  
  "matchIndex updates" should "update on successful replication response" in {
    val leader: Leader = Leader(
      term = 5,
      nextIndex = Map(NodeId("f1") -> 100),
      matchIndex = Map(NodeId("f1") -> 50)
    )
    
    // After successful replication to index 75
    val updated = leader.withMatchIndex(NodeId("f1"), 75)
    
    updated.matchIndex(NodeId("f1")) shouldBe 75L
  }
  
  "nextIndex updates" should "update after successful replication" in {
    val leader: Leader = Leader(
      term = 5,
      nextIndex = Map(NodeId("f1") -> 100),
      matchIndex = Map(NodeId("f1") -> 0)
    )
    
    // After successful replication, nextIndex should point to next entry
    val updated = leader.withNextIndex(NodeId("f1"), 76)
    
    updated.nextIndex(NodeId("f1")) shouldBe 76L
  }
  
  it should "be decremented on replication failure" in {
    // This is handled at the runtime level, but we test the withNextIndex method
    val leader: Leader = Leader(
      term = 5,
      nextIndex = Map(NodeId("f1") -> 100),
      matchIndex = Map(NodeId("f1") -> 0)
    )
    
    // Decrement by 1 on failure
    val updated = leader.withNextIndex(NodeId("f1"), 99)
    
    updated.nextIndex(NodeId("f1")) shouldBe 99L
  }
  
  "Leader state" should "preserve commitIndex on index updates" in {
    val leader: Leader = Leader(
      term = 5,
      nextIndex = Map.empty,
      matchIndex = Map.empty,
      commitIndex = 50
    )
    
    val updated = leader
      .withNextIndex(NodeId("f1"), 100)
      .withMatchIndex(NodeId("f1"), 75)
    
    updated.commitIndex shouldBe 50L
  }
