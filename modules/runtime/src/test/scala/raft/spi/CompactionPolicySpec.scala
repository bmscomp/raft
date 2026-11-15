package raft.spi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.Id

/** Tests for [[CompactionPolicy]] — the pluggable compaction algorithm SPI.
  *
  * Validates all built-in algorithms:
  *   - `disabled` — never triggers compaction
  *   - `threshold` — triggers after N entries since last snapshot
  *   - `sizeBased` — triggers when absolute log size exceeds limit
  *   - `timed` — triggers on every Nth applied entry
  *   - Custom — user-provided implementation
  */
class CompactionPolicySpec extends AnyFlatSpec with Matchers:

  "disabled policy" should "never trigger compaction" in {
    val policy = CompactionPolicy.disabled[Id]

    policy.shouldCompact(0, 0, 0) shouldBe false
    policy.shouldCompact(1000, 0, 1000) shouldBe false
    policy.shouldCompact(100000, 0, 100000) shouldBe false
    policy.shouldCompact(Long.MaxValue, 0, Long.MaxValue) shouldBe false
  }

  it should "still return lastApplied as compaction index" in {
    val policy = CompactionPolicy.disabled[Id]
    policy.compactionIndex(42) shouldBe 42
    policy.compactionIndex(0) shouldBe 0
  }

  "threshold policy" should "trigger when entries since snapshot exceed threshold" in {
    val policy = CompactionPolicy.threshold[Id](1000)

    policy.shouldCompact(999, 0, 999) shouldBe false
    policy.shouldCompact(1000, 0, 1000) shouldBe true
    policy.shouldCompact(1500, 0, 1500) shouldBe true
  }

  it should "account for last snapshot index" in {
    val policy = CompactionPolicy.threshold[Id](1000)

    policy.shouldCompact(1500, 500, 1000) shouldBe true
    policy.shouldCompact(1499, 500, 999) shouldBe false
    policy.shouldCompact(2500, 1500, 1000) shouldBe true
  }

  it should "never trigger for threshold of 1 with no entries" in {
    val policy = CompactionPolicy.threshold[Id](1)
    policy.shouldCompact(0, 0, 0) shouldBe false
  }

  it should "trigger immediately with threshold of 1 and one entry" in {
    val policy = CompactionPolicy.threshold[Id](1)
    policy.shouldCompact(1, 0, 1) shouldBe true
  }

  it should "return lastApplied as compaction index" in {
    val policy = CompactionPolicy.threshold[Id](100)
    policy.compactionIndex(500) shouldBe 500
  }

  "sizeBased policy" should "trigger when log size exceeds limit" in {
    val policy = CompactionPolicy.sizeBased[Id](5000)

    policy.shouldCompact(4999, 0, 4999) shouldBe false
    policy.shouldCompact(5000, 0, 5000) shouldBe true
    policy.shouldCompact(10000, 0, 10000) shouldBe true
  }

  it should "ignore last snapshot index" in {
    val policy = CompactionPolicy.sizeBased[Id](5000)

    // logSize is what matters, not entries since snapshot
    policy.shouldCompact(10000, 8000, 5000) shouldBe true
    policy.shouldCompact(10000, 8000, 4999) shouldBe false
  }

  it should "trigger at exact boundary" in {
    val policy = CompactionPolicy.sizeBased[Id](100)
    policy.shouldCompact(100, 0, 100) shouldBe true
    policy.shouldCompact(100, 0, 99) shouldBe false
  }

  "timed policy" should "trigger on interval boundaries" in {
    val policy = CompactionPolicy.timed[Id](5000)

    policy.shouldCompact(0, 0, 0) shouldBe false
    policy.shouldCompact(4999, 0, 4999) shouldBe false
    policy.shouldCompact(5000, 0, 5000) shouldBe true
    policy.shouldCompact(5001, 0, 5001) shouldBe false
    policy.shouldCompact(10000, 0, 10000) shouldBe true
  }

  it should "not trigger at zero" in {
    val policy = CompactionPolicy.timed[Id](1)
    policy.shouldCompact(0, 0, 0) shouldBe false
  }

  it should "trigger at every entry when interval is 1" in {
    val policy = CompactionPolicy.timed[Id](1)
    policy.shouldCompact(1, 0, 1) shouldBe true
    policy.shouldCompact(2, 0, 2) shouldBe true
    policy.shouldCompact(100, 0, 100) shouldBe true
  }

  "custom policy" should "use user-provided implementation" in {
    val customPolicy = new CompactionPolicy[Id]:
      def shouldCompact(
          lastApplied: Long,
          lastSnapshotIndex: Long,
          logSize: Long
      ): Boolean =
        lastApplied > 0 && lastApplied % 42 == 0

      def compactionIndex(lastApplied: Long): Long =
        lastApplied - 10

    customPolicy.shouldCompact(41, 0, 41) shouldBe false
    customPolicy.shouldCompact(42, 0, 42) shouldBe true
    customPolicy.shouldCompact(84, 42, 42) shouldBe true
    customPolicy.compactionIndex(100) shouldBe 90
  }

  "all policies" should "respect the contract: compactionIndex ≤ lastApplied" in {
    val policies: List[CompactionPolicy[Id]] = List(
      CompactionPolicy.disabled[Id],
      CompactionPolicy.threshold[Id](100),
      CompactionPolicy.sizeBased[Id](500),
      CompactionPolicy.timed[Id](50)
    )

    val testIndices = List(0L, 1L, 50L, 100L, 500L, 10000L)

    for
      policy <- policies
      idx <- testIndices
    do policy.compactionIndex(idx) should be <= idx
  }
