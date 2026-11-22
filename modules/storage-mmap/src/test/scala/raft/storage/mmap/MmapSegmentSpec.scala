package raft.storage.mmap

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

import raft.state.{Log, LogType}

class MmapSegmentSpec extends AnyFreeSpec with Matchers:

  private def withSegment(firstIndex: Long = 1L, size: Long = 4096L)(
      test: (MmapSegment, Path) => Unit
  ): Unit =
    val tmpDir = Files.createTempDirectory("raft-seg-test-")
    val segPath = tmpDir.resolve("test.wal")
    val seg = MmapSegment.create(segPath, firstIndex, size)
    try test(seg, segPath)
    finally
      seg.close()
      Files.deleteIfExists(segPath)
      Files.deleteIfExists(tmpDir)

  private def mkLog(index: Long, term: Long, data: String): Log =
    Log.command(index, term, data.getBytes("UTF-8"))

  "MmapSegment" - {
    "create" - {
      "should produce a valid file with correct header" in withSegment() {
        (seg, path) =>
          seg.firstIndex shouldBe 1L
          seg.currentEntryCount shouldBe 0L
          seg.currentWriteOffset shouldBe HeaderSize.toLong
          Files.exists(path) shouldBe true
      }

      "should pre-allocate the full file size" in {
        val tmpDir = Files.createTempDirectory("raft-seg-size-")
        val segPath = tmpDir.resolve("sized.wal")
        val seg = MmapSegment.create(segPath, 1L, 1024L)
        try seg.capacity shouldBe 1024L
        finally
          seg.close()
          Files.deleteIfExists(segPath)
          Files.deleteIfExists(tmpDir)
      }
    }

    "appendEntry and readEntry" - {
      "should round-trip a single entry" in withSegment() { (seg, _) =>
        val log = mkLog(1, 1L, "hello")
        val offset = seg.appendEntry(log)
        seg.force()

        offset shouldBe HeaderSize.toLong
        seg.currentEntryCount shouldBe 1L

        val read = seg.readEntry(offset)
        read.index shouldBe 1L
        read.term shouldBe 1L
        read.logType shouldBe LogType.Command
        new String(read.data) shouldBe "hello"
      }

      "should round-trip multiple entries sequentially" in withSegment(size =
        8192L
      ) { (seg, _) =>
        val offsets = (1L to 10L).map { i =>
          seg.appendEntry(mkLog(i, 1L, s"entry-$i"))
        }
        seg.force()

        seg.currentEntryCount shouldBe 10L
        offsets.distinct.size shouldBe 10 // all unique offsets

        for i <- 1L to 10L do
          val read = seg.readEntry(offsets((i - 1).toInt))
          read.index shouldBe i
          new String(read.data) shouldBe s"entry-$i"
      }

      "should handle empty data (NoOp)" in withSegment() { (seg, _) =>
        val log = Log.noOp(1, 1L)
        val offset = seg.appendEntry(log)
        seg.force()

        val read = seg.readEntry(offset)
        read.logType shouldBe LogType.NoOp
        read.data shouldBe empty
      }

      "should handle large data payload" in withSegment(size = 1024 * 1024) {
        (seg, _) =>
          val bigData = new Array[Byte](50_000)
          java.util.Arrays.fill(bigData, 0xab.toByte)
          val log = Log.command(1, 1L, bigData)
          val offset = seg.appendEntry(log)
          seg.force()

          val read = seg.readEntry(offset)
          read.data.length shouldBe 50_000
          read.data(0) shouldBe 0xab.toByte
          read.data(49_999) shouldBe 0xab.toByte
      }

      "should handle entries with varying data sizes" in withSegment(size =
        16384L
      ) { (seg, _) =>
        val sizes = Seq(0, 1, 10, 100, 1000, 5000)
        val offsets = sizes.zipWithIndex.map { case (sz, i) =>
          val data = new Array[Byte](sz)
          java.util.Arrays.fill(data, (i + 1).toByte)
          val log = Log.command(i + 1L, 1L, data)
          seg.appendEntry(log)
        }
        seg.force()

        for (sz, i) <- sizes.zipWithIndex do
          val read = seg.readEntry(offsets(i))
          read.data.length shouldBe sz
          if sz > 0 then read.data(0) shouldBe (i + 1).toByte
      }
    }

    "remaining" - {
      "should decrease as entries are appended" in withSegment(size = 4096L) {
        (seg, _) =>
          val initialRemaining = seg.remaining
          seg.appendEntry(mkLog(1, 1L, "test"))
          seg.remaining should be < initialRemaining
      }
    }

    "truncateAt" - {
      "should reset write offset and entry count" in withSegment(size = 8192L) {
        (seg, _) =>
          val _ = seg.appendEntry(mkLog(1, 1L, "first"))
          val offset2 = seg.appendEntry(mkLog(2, 1L, "second"))
          seg.appendEntry(mkLog(3, 1L, "third"))
          seg.force()

          seg.truncateAt(offset2, 1L)
          seg.currentEntryCount shouldBe 1L
          seg.currentWriteOffset shouldBe offset2

          // Overwrite with new data
          val newOffset = seg.appendEntry(mkLog(2, 2L, "replaced"))
          seg.force()
          newOffset shouldBe offset2
          val read = seg.readEntry(newOffset)
          new String(read.data) shouldBe "replaced"
          read.term shouldBe 2L
      }
    }

    "replay" - {
      "should iterate all entries in order" in withSegment(size = 8192L) {
        (seg, _) =>
          (1L to 5L).foreach(i => seg.appendEntry(mkLog(i, 1L, s"data-$i")))
          seg.force()

          var replayed = Vector.empty[(Long, Long)]
          seg.replay { (log, offset) =>
            replayed = replayed :+ (log.index, offset)
          }

          replayed.size shouldBe 5
          replayed.map(_._1) shouldBe (1L to 5L).toVector
      }

      "should replay zero entries on an empty segment" in withSegment() {
        (seg, _) =>
          var count = 0
          seg.replay { (_, _) => count += 1 }
          count shouldBe 0
      }
    }

    "CRC validation" - {
      "should detect flipped data bytes" in {
        val tmpDir = Files.createTempDirectory("raft-seg-crc-")
        val segPath = tmpDir.resolve("crc-test.wal")
        val seg = MmapSegment.create(segPath, 1L, 4096L)
        val offset = seg.appendEntry(mkLog(1, 1L, "important"))
        seg.force()
        seg.close()

        // Corrupt one data byte
        val bytes = Files.readAllBytes(segPath)
        val dataStart = (offset + 25).toInt // after header fields
        bytes(dataStart) = (bytes(dataStart) ^ 0xff).toByte
        Files.write(segPath, bytes)

        val reopened = MmapSegment.open(segPath)
        try
          val ex = intercept[CorruptedEntryException] {
            reopened.readEntry(offset)
          }
          ex.logIndex shouldBe 1L
          ex.offset shouldBe offset
        finally
          reopened.close()
          Files.deleteIfExists(segPath)
          Files.deleteIfExists(tmpDir)
      }

      "should detect flipped term byte" in {
        val tmpDir = Files.createTempDirectory("raft-seg-crc2-")
        val segPath = tmpDir.resolve("crc-test2.wal")
        val seg = MmapSegment.create(segPath, 1L, 4096L)
        val offset = seg.appendEntry(mkLog(1, 1L, "data"))
        seg.force()
        seg.close()

        // Corrupt the term field (offset + 12)
        val bytes = Files.readAllBytes(segPath)
        val termByte = (offset + 12).toInt
        bytes(termByte) = (bytes(termByte) ^ 0x01).toByte
        Files.write(segPath, bytes)

        val reopened = MmapSegment.open(segPath)
        try intercept[CorruptedEntryException] { reopened.readEntry(offset) }
        finally
          reopened.close()
          Files.deleteIfExists(segPath)
          Files.deleteIfExists(tmpDir)
      }
    }

    "open" - {
      "should reopen a segment with correct state" in {
        val tmpDir = Files.createTempDirectory("raft-seg-reopen-")
        val segPath = tmpDir.resolve("reopen.wal")
        val seg1 = MmapSegment.create(segPath, 1L, 4096L)
        (1L to 3L).foreach(i => seg1.appendEntry(mkLog(i, 1L, s"v-$i")))
        seg1.force()
        val writePos = seg1.currentWriteOffset
        seg1.close()

        val seg2 = MmapSegment.open(segPath)
        try
          seg2.firstIndex shouldBe 1L
          seg2.currentEntryCount shouldBe 3L
          seg2.currentWriteOffset shouldBe writePos
        finally
          seg2.close()
          Files.deleteIfExists(segPath)
          Files.deleteIfExists(tmpDir)
      }

      "should reject a file with bad magic bytes" in {
        val tmpDir = Files.createTempDirectory("raft-seg-badmagic-")
        val segPath = tmpDir.resolve("bad.wal")
        Files.write(segPath, Array.fill[Byte](4096)(0))
        try intercept[IllegalStateException] { MmapSegment.open(segPath) }
        finally
          Files.deleteIfExists(segPath)
          Files.deleteIfExists(tmpDir)
      }

      "should reject invalid offsets, inconsistent recordLen/dataLen, and invalid LogType ordinal before heap allocation" in {
        import java.nio.{ByteBuffer, ByteOrder}

        val tmpDir = Files.createTempDirectory("raft-seg-bounds-")
        val segPath = tmpDir.resolve("bounds.wal")
        val seg = MmapSegment.create(segPath, 1L, 4096L)
        val off = seg.appendEntry(mkLog(1L, 1L, "payload"))
        seg.force()
        try
          intercept[CorruptedEntryException] { seg.readEntry(0L) }
          intercept[CorruptedEntryException] { seg.readEntry(4090L) }
        finally seg.close()

        val raw1 = Files.readAllBytes(segPath)
        ByteBuffer
          .wrap(raw1)
          .order(ByteOrder.nativeOrder())
          .putInt(off.toInt, 10)
        Files.write(segPath, raw1)
        intercept[CorruptedEntryException] {
          val s1 = MmapSegment.open(segPath, readOnly = true)
          s1.close()
        }

        val fresh = MmapSegment.create(segPath, 1L, 4096L)
        val off2 = fresh.appendEntry(mkLog(1L, 1L, "payload"))
        fresh.force()
        fresh.close()

        val raw2 = Files.readAllBytes(segPath)
        raw2((off2 + 20L).toInt) = 99.toByte
        Files.write(segPath, raw2)
        val s2 = MmapSegment.open(segPath, readOnly = true)
        try intercept[CorruptedEntryException] { s2.readEntry(off2) }
        finally
          s2.close()
          Files.deleteIfExists(segPath)
          Files.deleteIfExists(tmpDir)
      }
    }
  }
