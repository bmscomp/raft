package raft.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.Id
import raft.spi.CompactionPolicy

/** Tests for [[CompactionConfig]] and [[CompactionLevel]].
  *
  * Validates:
  *   - Level-to-threshold mapping
  *   - Policy resolution for all levels
  *   - Custom policy injection
  *   - Default configuration values
  */
class CompactionConfigSpec extends AnyFlatSpec with Matchers:

  "CompactionLevel" should "map to correct default thresholds" in {
    CompactionLevel.Disabled.defaultThreshold shouldBe Long.MaxValue
    CompactionLevel.Conservative.defaultThreshold shouldBe 10_000
    CompactionLevel.Moderate.defaultThreshold shouldBe 1_000
    CompactionLevel.Aggressive.defaultThreshold shouldBe 100
    CompactionLevel.Custom.defaultThreshold shouldBe 0
  }

  "CompactionConfig defaults" should "use Conservative level" in {
    val config = CompactionConfig()
    config.level shouldBe CompactionLevel.Conservative
    config.snapshotChunkSize shouldBe 64 * 1024
    config.retainedEntries shouldBe 100
    config.policy shouldBe None
  }

  "resolvePolicy" should "return disabled policy for Disabled level" in {
    val config = CompactionConfig(level = CompactionLevel.Disabled)
    val policy = config.resolvePolicy[Id]

    policy.shouldCompact(100000, 0, 100000) shouldBe false
  }

  it should "return threshold(10000) for Conservative level" in {
    val config = CompactionConfig(level = CompactionLevel.Conservative)
    val policy = config.resolvePolicy[Id]

    policy.shouldCompact(9999, 0, 9999) shouldBe false
    policy.shouldCompact(10000, 0, 10000) shouldBe true
  }

  it should "return threshold(1000) for Moderate level" in {
    val config = CompactionConfig(level = CompactionLevel.Moderate)
    val policy = config.resolvePolicy[Id]

    policy.shouldCompact(999, 0, 999) shouldBe false
    policy.shouldCompact(1000, 0, 1000) shouldBe true
  }

  it should "return threshold(100) for Aggressive level" in {
    val config = CompactionConfig(level = CompactionLevel.Aggressive)
    val policy = config.resolvePolicy[Id]

    policy.shouldCompact(99, 0, 99) shouldBe false
    policy.shouldCompact(100, 0, 100) shouldBe true
  }

  it should "use custom policy when provided" in {
    val customPolicy = new CompactionPolicy[Id]:
      def shouldCompact(
          lastApplied: Long,
          lastSnapshotIndex: Long,
          logSize: Long
      ): Boolean = lastApplied == 77

      def compactionIndex(lastApplied: Long): Long = lastApplied

    val config = CompactionConfig(
      level = CompactionLevel.Custom,
      policy = Some(customPolicy)
    )
    val resolved = config.resolvePolicy[Id]

    resolved.shouldCompact(76, 0, 76) shouldBe false
    resolved.shouldCompact(77, 0, 77) shouldBe true
    resolved.shouldCompact(78, 0, 78) shouldBe false
  }

  it should "prefer custom policy over level threshold" in {
    val alwaysCompact = new CompactionPolicy[Id]:
      def shouldCompact(
          lastApplied: Long,
          lastSnapshotIndex: Long,
          logSize: Long
      ): Boolean = true

      def compactionIndex(lastApplied: Long): Long = lastApplied

    val config = CompactionConfig(
      level = CompactionLevel.Disabled,
      policy = Some(alwaysCompact)
    )
    val resolved = config.resolvePolicy[Id]

    resolved.shouldCompact(1, 0, 1) shouldBe true
  }

  "snapshotChunkSize" should "be configurable" in {
    val config = CompactionConfig(snapshotChunkSize = 1024 * 1024)
    config.snapshotChunkSize shouldBe 1024 * 1024
  }

  "retainedEntries" should "be configurable" in {
    val config = CompactionConfig(retainedEntries = 500)
    config.retainedEntries shouldBe 500
  }
