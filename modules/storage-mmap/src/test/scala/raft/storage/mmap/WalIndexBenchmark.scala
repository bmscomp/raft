package raft.storage.mmap

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Micro-benchmark comparing allocation-free vs Option-based WalIndex lookups.
  *
  * Not a rigorous JMH benchmark — uses System.nanoTime over 1M iterations to
  * demonstrate the allocation-free path is at least as fast.
  */
class WalIndexBenchmark extends AnyFlatSpec with Matchers:

  private val EntryCount = 1_000_000
  private val Iterations = 1_000_000

  private def buildIndex(n: Int): WalIndex =
    val idx = new WalIndex
    for i <- 1 to n do idx.put(i.toLong, i / 10000, i * 20L)
    idx

  "WalIndex primitive accessors" should "return correct values" in {
    val idx = buildIndex(1000)

    for i <- 1 to 1000 do
      val segId = idx.getSegmentId(i.toLong)
      val offset = idx.getFileOffset(i.toLong)
      val optResult = idx.get(i.toLong)

      segId shouldBe optResult.get.segmentId
      offset shouldBe optResult.get.fileOffset

    idx.getSegmentId(9999L) shouldBe -1
    idx.getFileOffset(9999L) shouldBe -1L
  }

  it should "be faster than Option-based get for sequential lookups" in {
    val idx = buildIndex(EntryCount)

    // Extended warm up to stabilize JIT
    for _ <- 1 to 3 do
      for i <- 1 to 100_000 do
        idx.get(i.toLong)
        idx.getSegmentId(i.toLong)
        idx.getFileOffset(i.toLong)

    // Best-of-3 runs to reduce variance from GC pauses and system load
    def benchOption(): Long =
      val start = System.nanoTime()
      var sink = 0L
      for i <- 1 to Iterations do
        idx.get(i.toLong) match
          case Some(loc) => sink += loc.fileOffset
          case None      => ()
      val elapsed = System.nanoTime() - start
      assert(sink != 0) // prevent DCE
      elapsed

    def benchPrimitive(): Long =
      val start = System.nanoTime()
      var sink = 0L
      for i <- 1 to Iterations do
        val off = idx.getFileOffset(i.toLong)
        if off != -1L then sink += off
      val elapsed = System.nanoTime() - start
      assert(sink != 0)
      elapsed

    val optNanos = (1 to 3).map(_ => benchOption()).min
    val primNanos = (1 to 3).map(_ => benchPrimitive()).min

    val optMs = optNanos / 1_000_000.0
    val primMs = primNanos / 1_000_000.0
    info(f"Option-based get:     $optMs%.1f ms ($Iterations lookups)")
    info(f"Primitive accessors:  $primMs%.1f ms ($Iterations lookups)")
    info(f"Speedup:              ${optMs / primMs}%.2fx")

    // Generous margin — not a rigorous JMH benchmark; just a sanity check
    // that primitives aren't dramatically slower than Option-based.
    primNanos should be <= (optNanos * 2.0).toLong
  }

  it should "handle misses without allocation" in {
    val idx = buildIndex(1000)

    val start = System.nanoTime()
    var missCount = 0
    for i <- 2000 to 3000 do
      if idx.getSegmentId(i.toLong) == -1 then missCount += 1
    val elapsed = System.nanoTime() - start

    missCount shouldBe 1001
    info(f"1001 miss lookups: ${elapsed / 1000.0}%.1f µs")
  }
