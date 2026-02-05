package raft.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

/**
 * Tests for RaftConfig and related configuration classes.
 */
class RaftConfigSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")
  
  // === BATCH CONFIG ===
  
  "BatchConfig" should "have sensible defaults" in {
    val config = BatchConfig()
    
    config.enabled shouldBe false
    config.maxSize shouldBe 100
    config.maxWait shouldBe 10.millis
  }
  
  it should "be fully configurable" in {
    val config = BatchConfig(
      enabled = true,
      maxSize = 50,
      maxWait = 5.millis
    )
    
    config.enabled shouldBe true
    config.maxSize shouldBe 50
    config.maxWait shouldBe 5.millis
  }
  
  it should "support zero wait time" in {
    val config = BatchConfig(enabled = true, maxWait = 0.millis)
    config.maxWait shouldBe 0.millis
  }
  
  it should "support large batch sizes" in {
    val config = BatchConfig(maxSize = 10000)
    config.maxSize shouldBe 10000
  }
  
  // === PIPELINE CONFIG ===
  
  "PipelineConfig" should "have sensible defaults" in {
    val config = PipelineConfig()
    
    config.enabled shouldBe false
    config.maxInflight shouldBe 10
  }
  
  it should "be fully configurable" in {
    val config = PipelineConfig(
      enabled = true,
      maxInflight = 5
    )
    
    config.enabled shouldBe true
    config.maxInflight shouldBe 5
  }
  
  it should "support minimal inflight" in {
    val config = PipelineConfig(enabled = true, maxInflight = 1)
    config.maxInflight shouldBe 1
  }
  
  it should "support high concurrency" in {
    val config = PipelineConfig(enabled = true, maxInflight = 100)
    config.maxInflight shouldBe 100
  }
  
  // === RAFT CONFIG ===
  
  "RaftConfig" should "have correct defaults" in {
    val config = RaftConfig.default(node1)
    
    config.localId shouldBe node1
    config.electionTimeoutMin shouldBe 150.millis
    config.electionTimeoutMax shouldBe 300.millis
    config.heartbeatInterval shouldBe 50.millis
    config.maxEntriesPerRequest shouldBe 100
    config.preVoteEnabled shouldBe true
    config.leaderStickinessEnabled shouldBe true
    config.leaderLeaseDuration shouldBe 100.millis
    config.parallelReplicationEnabled shouldBe true
    config.batching.enabled shouldBe false
    config.pipelining.enabled shouldBe false
  }
  
  it should "include parallel replication flag" in {
    val config = RaftConfig(localId = node1, parallelReplicationEnabled = false)
    config.parallelReplicationEnabled shouldBe false
  }
  
  it should "nest BatchConfig correctly" in {
    val config = RaftConfig(
      localId = node1,
      batching = BatchConfig(enabled = true, maxSize = 200)
    )
    
    config.batching.enabled shouldBe true
    config.batching.maxSize shouldBe 200
  }
  
  it should "nest PipelineConfig correctly" in {
    val config = RaftConfig(
      localId = node1,
      pipelining = PipelineConfig(enabled = true, maxInflight = 20)
    )
    
    config.pipelining.enabled shouldBe true
    config.pipelining.maxInflight shouldBe 20
  }
  
  it should "support full customization" in {
    val config = RaftConfig(
      localId = node1,
      electionTimeoutMin = 100.millis,
      electionTimeoutMax = 500.millis,
      heartbeatInterval = 25.millis,
      maxEntriesPerRequest = 50,
      preVoteEnabled = false,
      leaderStickinessEnabled = false,
      leaderLeaseDuration = 200.millis,
      parallelReplicationEnabled = true,
      batching = BatchConfig(enabled = true, maxSize = 25, maxWait = 2.millis),
      pipelining = PipelineConfig(enabled = true, maxInflight = 3)
    )
    
    config.electionTimeoutMin shouldBe 100.millis
    config.electionTimeoutMax shouldBe 500.millis
    config.heartbeatInterval shouldBe 25.millis
    config.maxEntriesPerRequest shouldBe 50
    config.preVoteEnabled shouldBe false
    config.leaderStickinessEnabled shouldBe false
    config.leaderLeaseDuration shouldBe 200.millis
    config.parallelReplicationEnabled shouldBe true
    config.batching.enabled shouldBe true
    config.batching.maxSize shouldBe 25
    config.batching.maxWait shouldBe 2.millis
    config.pipelining.enabled shouldBe true
    config.pipelining.maxInflight shouldBe 3
  }
  
  // === CONFIG VALIDATION SCENARIOS ===
  
  "Election timeout" should "have min less than max" in {
    val config = RaftConfig.default(node1)
    config.electionTimeoutMin should be < config.electionTimeoutMax
  }
  
  "Heartbeat interval" should "be less than election timeout min" in {
    // Best practice: heartbeat << election timeout to prevent false timeouts
    val config = RaftConfig.default(node1)
    config.heartbeatInterval should be < config.electionTimeoutMin
  }
