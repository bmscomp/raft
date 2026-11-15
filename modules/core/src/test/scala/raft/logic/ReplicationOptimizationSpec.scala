package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import raft.state.*

/** Tests for Batching, Pipelining, and `Inflights` windowing in `raft-core`. */
class ReplicationOptimizationSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")

  "BatchConfig" should "have sensible defaults and validate bounds" in {
    val defaults = BatchConfig()
    defaults.enabled shouldBe false
    defaults.maxSize shouldBe 100
    defaults.maxWait shouldBe 10.millis

    val custom = BatchConfig(enabled = true, maxSize = 50, maxWait = 5.millis)
    custom.enabled shouldBe true
    custom.maxSize shouldBe 50
    custom.maxWait shouldBe 5.millis

    an[IllegalArgumentException] should be thrownBy BatchConfig(maxSize = 0)
    an[IllegalArgumentException] should be thrownBy BatchConfig(
      maxWait = (-1).millis
    )
  }

  "PipelineConfig" should "have sensible defaults, validate bounds, and bound Inflights" in {
    val defaults = PipelineConfig()
    defaults.enabled shouldBe false
    defaults.maxInflight shouldBe 10

    an[IllegalArgumentException] should be thrownBy PipelineConfig(
      maxInflight = 0
    )

    val inflights = Inflights(capacity = 3)
      .add(10L)
      .add(20L)
      .add(30L)
    inflights.isFull shouldBe true
    inflights.freeTo(20L).count shouldBe 1
  }

  "RaftConfig" should "include batching, pipelining, and parallel replication settings" in {
    val config = RaftConfig(
      localId = node1,
      batching = BatchConfig(enabled = true, maxSize = 50),
      pipelining = PipelineConfig(enabled = true, maxInflight = 5)
    )

    config.batching.enabled shouldBe true
    config.batching.maxSize shouldBe 50
    config.pipelining.enabled shouldBe true
    config.pipelining.maxInflight shouldBe 5
    RaftConfig.default(node1).parallelReplicationEnabled shouldBe true
  }
