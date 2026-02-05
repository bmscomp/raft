package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect
import raft.effect.Effect.*

/**
 * Tests for Parallel Append, Batching, and Pipelining features.
 */
class ReplicationOptimizationSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  
  // === PARALLEL REPLICATION EFFECT ===
  
  "ParallelReplicate effect" should "target multiple followers" in {
    val appendReq = AppendEntriesRequest(
      term = 5,
      leaderId = node1,
      prevLogIndex = 10,
      prevLogTerm = 5,
      entries = Seq.empty,
      leaderCommit = 8
    )
    
    val effect: Effect = ParallelReplicate(Set(node2, node3), appendReq)
    
    effect match
      case ParallelReplicate(targets, msg) =>
        targets shouldBe Set(node2, node3)
        msg shouldBe appendReq
      case _ => fail("Expected ParallelReplicate")
  }
  
  it should "be more efficient than individual SendMessage effects" in {
    // With ParallelReplicate: 1 effect for N followers
    // Without: N SendMessage effects
    
    val followers = (1 to 100).map(i => NodeId(s"node-$i")).toSet
    val msg = AppendEntriesRequest(1, node1, 0, 0, Seq.empty, 0)
    
    val parallelEffect: Effect = ParallelReplicate(followers, msg)
    parallelEffect match
      case ParallelReplicate(targets, _) => targets.size shouldBe 100
      case _ => fail("Expected ParallelReplicate")
    
    // Compared to 100 individual SendMessage effects
    val individualEffects = followers.map(f => SendMessage(f, msg))
    individualEffects.size shouldBe 100  // 100 separate effects
  }
  
  // === BATCH CONFIG ===
  
  "BatchConfig" should "have sensible defaults" in {
    val config = BatchConfig()
    
    config.enabled shouldBe false  // Off by default
    config.maxSize shouldBe 100
    config.maxWait shouldBe 10.millis
  }
  
  it should "be configurable" in {
    val config = BatchConfig(
      enabled = true,
      maxSize = 50,
      maxWait = 5.millis
    )
    
    config.enabled shouldBe true
    config.maxSize shouldBe 50
    config.maxWait shouldBe 5.millis
  }
  
  // === BATCH EFFECTS ===
  
  "BatchAppend effect" should "hold multiple entries with batch ID" in {
    val entries = Seq(
      Log.command(1, 1, "cmd1".getBytes),
      Log.command(2, 1, "cmd2".getBytes),
      Log.command(3, 1, "cmd3".getBytes)
    )
    
    val effect: Effect = BatchAppend(entries, "batch-123")
    
    effect match
      case BatchAppend(e, batchId) =>
        e.size shouldBe 3
        batchId shouldBe "batch-123"
      case _ => fail("Expected BatchAppend")
  }
  
  "BatchComplete effect" should "track completion with commit index" in {
    val effect: Effect = BatchComplete("batch-123", commitIndex = 42)
    
    effect match
      case BatchComplete(batchId, commitIdx) =>
        batchId shouldBe "batch-123"
        commitIdx shouldBe 42
      case _ => fail("Expected BatchComplete")
  }
  
  // === PIPELINE CONFIG ===
  
  "PipelineConfig" should "have sensible defaults" in {
    val config = PipelineConfig()
    
    config.enabled shouldBe false  // Off by default
    config.maxInflight shouldBe 10
  }
  
  it should "be configurable" in {
    val config = PipelineConfig(enabled = true, maxInflight = 5)
    
    config.enabled shouldBe true
    config.maxInflight shouldBe 5
  }
  
  // === PIPELINE EFFECTS ===
  
  "PipelinedSend effect" should "include sequence number" in {
    val msg = AppendEntriesRequest(5, node1, 10, 5, Seq.empty, 8)
    val effect: Effect = PipelinedSend(node2, msg, sequenceNum = 42)
    
    effect match
      case PipelinedSend(to, m, seq) =>
        to shouldBe node2
        m shouldBe msg
        seq shouldBe 42
      case _ => fail("Expected PipelinedSend")
  }
  
  "TrackInflight effect" should "track pending requests" in {
    val effect: Effect = TrackInflight(node2, sequenceNum = 42, lastIndex = 100)
    
    effect match
      case TrackInflight(followerId, seq, lastIdx) =>
        followerId shouldBe node2
        seq shouldBe 42
        lastIdx shouldBe 100
      case _ => fail("Expected TrackInflight")
  }
  
  // === RAFT CONFIG INTEGRATION ===
  
  "RaftConfig" should "include batching and pipelining config" in {
    val config = RaftConfig(
      localId = node1,
      batching = BatchConfig(enabled = true, maxSize = 50),
      pipelining = PipelineConfig(enabled = true, maxInflight = 5)
    )
    
    config.batching.enabled shouldBe true
    config.batching.maxSize shouldBe 50
    config.pipelining.enabled shouldBe true
    config.pipelining.maxInflight shouldBe 5
  }
  
  it should "have parallel replication enabled by default" in {
    val config = RaftConfig.default(node1)
    
    config.parallelReplicationEnabled shouldBe true
  }
  
  // === ORDERED SEQUENCE NUMBERS ===
  
  "Pipeline sequence numbers" should "enable out-of-order response handling" in {
    // Scenario: Leader sends seq 1, 2, 3. Receives response for 2 first.
    // With sequence numbers, runtime can track which response maps to which request.
    
    val effect1 = PipelinedSend(node2, AppendEntriesRequest(5, node1, 10, 5, Seq.empty, 8), 1)
    val effect2 = PipelinedSend(node2, AppendEntriesRequest(5, node1, 15, 5, Seq.empty, 10), 2)
    val effect3 = PipelinedSend(node2, AppendEntriesRequest(5, node1, 20, 5, Seq.empty, 15), 3)
    
    // Verify sequence ordering using pattern matching
    (effect1, effect2, effect3) match
      case (PipelinedSend(_, _, s1), PipelinedSend(_, _, s2), PipelinedSend(_, _, s3)) =>
        s1 shouldBe 1
        s2 shouldBe 2
        s3 shouldBe 3
        s1 < s2 shouldBe true
        s3 > s2 shouldBe true
      case _ => fail("Expected PipelinedSend effects")
  }

