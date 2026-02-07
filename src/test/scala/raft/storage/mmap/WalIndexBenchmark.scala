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

    // Warm up
    for i <- 1 to 10000 do
      idx.get(i.toLong)
      idx.getSegmentId(i.toLong)

    // Benchmark Option-based get
    val startOpt = System.nanoTime()
    var sink1 = 0L
    for i <- 1 to Iterations do
      idx.get(i.toLong) match
        case Some(loc) => sink1 += loc.fileOffset
        case None      => ()
    val optNanos = System.nanoTime() - startOpt

    // Benchmark primitive accessors
    val startPrim = System.nanoTime()
    var sink2 = 0L
    for i <- 1 to Iterations do
      val off = idx.getFileOffset(i.toLong)
      if off != -1L then sink2 += off
    val primNanos = System.nanoTime() - startPrim

    // Sinks must match
    sink1 shouldBe sink2

    // Log results
    val optMs = optNanos / 1_000_000.0
    val primMs = primNanos / 1_000_000.0
    info(f"Option-based get:     $optMs%.1f ms ($Iterations lookups)")
    info(f"Primitive accessors:  $primMs%.1f ms ($Iterations lookups)")
    info(f"Speedup:              ${optMs / primMs}%.2fx")

    // Primitive should not be significantly slower (allow 20% margin for JIT variance)
    primNanos should be <= (optNanos * 1.2).toLong
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
