package raft.storage.mmap

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

import raft.state.{NodeId, Term}
import raft.storage.HardState

class MmapHardStateSpec extends AnyFreeSpec with Matchers:

  private def withHardState(test: (MmapHardState, Path) => Unit): Unit =
    val tmpDir = Files.createTempDirectory("raft-hs-test-")
    val hsPath = tmpDir.resolve("hard_state.bin")
    val hs = MmapHardState.open(hsPath)
    try test(hs, hsPath)
    finally
      hs.close()
      Files.deleteIfExists(hsPath)
      Files.deleteIfExists(tmpDir)

  "MmapHardState" - {
    "should initialize with empty state on first open" in withHardState {
      (hs, _) =>
        val state = hs.read
        state.currentTerm shouldBe Term.Zero
        state.votedFor shouldBe None
    }

    "should write and read back term and votedFor" in withHardState { (hs, _) =>
      val state = HardState(Term.unsafeFrom(42L), Some(NodeId("leader-node")))
      hs.write(state)
      val read = hs.read
      read.currentTerm shouldBe Term.unsafeFrom(42L)
      read.votedFor shouldBe Some(NodeId("leader-node"))
    }

    "should handle None votedFor" in withHardState { (hs, _) =>
      hs.write(HardState(Term.unsafeFrom(10L), None))
      val read = hs.read
      read.currentTerm shouldBe Term.unsafeFrom(10L)
      read.votedFor shouldBe None
    }

    "should overwrite previous state" in withHardState { (hs, _) =>
      hs.write(HardState(Term.unsafeFrom(1L), Some(NodeId("a"))))
      hs.write(HardState(Term.unsafeFrom(5L), Some(NodeId("b"))))
      val read = hs.read
      read.currentTerm shouldBe Term.unsafeFrom(5L)
      read.votedFor shouldBe Some(NodeId("b"))
    }

    "should transition from voted to unvoted" in withHardState { (hs, _) =>
      hs.write(HardState(Term.unsafeFrom(3L), Some(NodeId("candidate"))))
      hs.write(HardState(Term.unsafeFrom(4L), None))
      val read = hs.read
      read.currentTerm shouldBe Term.unsafeFrom(4L)
      read.votedFor shouldBe None
    }

    "should handle long node IDs" in withHardState { (hs, _) =>
      val longId = "node-" + "x" * 200
      hs.write(HardState(Term.unsafeFrom(1L), Some(NodeId(longId))))
      val read = hs.read
      read.votedFor shouldBe defined
      read.votedFor.get.value should startWith("node-")
    }

    "should survive close and reopen" in {
      val tmpDir = Files.createTempDirectory("raft-hs-reopen-")
      val hsPath = tmpDir.resolve("hard_state.bin")

      val hs1 = MmapHardState.open(hsPath)
      hs1.write(HardState(Term.unsafeFrom(99L), Some(NodeId("survivor"))))
      hs1.close()

      val hs2 = MmapHardState.open(hsPath)
      try
        val read = hs2.read
        read.currentTerm shouldBe Term.unsafeFrom(99L)
        read.votedFor shouldBe Some(NodeId("survivor"))
      finally
        hs2.close()
        Files.deleteIfExists(hsPath)
        Files.deleteIfExists(tmpDir)
    }

    "should handle rapid successive writes" in withHardState { (hs, _) =>
      for i <- 1L to 50L do
        hs.write(HardState(Term.unsafeFrom(i), Some(NodeId(s"node-$i"))))
      val read = hs.read
      read.currentTerm shouldBe Term.unsafeFrom(50L)
      read.votedFor shouldBe Some(NodeId("node-50"))
    }

    "should create a file of the expected size" in withHardState { (_, path) =>
      Files.size(path) shouldBe 280L
    }

    "should detect single-bit CRC32 corruption on read" in {
      val tmpDir = Files.createTempDirectory("raft-hs-crc-")
      val hsPath = tmpDir.resolve("hard_state.bin")

      val hs1 = MmapHardState.open(hsPath)
      hs1.write(HardState(Term.unsafeFrom(17L), Some(NodeId("voter-alpha"))))
      hs1.close()

      // Flip 1 bit in the term field on disk without updating CRC32
      val raw = Files.readAllBytes(hsPath)
      raw(7) = (raw(7) ^ 0x01).toByte
      Files.write(hsPath, raw)

      val hs2 = MmapHardState.open(hsPath)
      try
        val ex = intercept[raft.state.RaftError.StorageCorruption] {
          hs2.read
        }
        ex.reason should include("CRC32 mismatch")
      finally
        hs2.close()
        Files.deleteIfExists(hsPath)
        Files.deleteIfExists(tmpDir)
    }

    "should zero-fill trailing votedFor bytes when overwriting a long NodeId with a shorter one" in {
      val tmpDir = Files.createTempDirectory("raft-hs-zeropad-")
      val hsPath = tmpDir.resolve("hard_state.bin")

      val hs = MmapHardState.open(hsPath)
      try
        hs.write(
          HardState(
            Term.unsafeFrom(2L),
            Some(NodeId("very-long-candidate-node-identifier-1234567890"))
          )
        )
        hs.write(HardState(Term.unsafeFrom(3L), Some(NodeId("n1"))))
        hs.read shouldBe HardState(Term.unsafeFrom(3L), Some(NodeId("n1")))

        val raw = Files.readAllBytes(hsPath)
        // Bytes [14..268) must be strictly zero-padded
        raw.slice(14, 268).forall(_ == 0.toByte) shouldBe true
      finally
        hs.close()
        Files.deleteIfExists(hsPath)
        Files.deleteIfExists(tmpDir)
    }

    "should reject votedFor NodeId exceeding 256 UTF-8 bytes" in withHardState {
      (hs, _) =>
        val oversized = NodeId("x" * 257)
        intercept[IllegalArgumentException] {
          hs.write(HardState(Term.unsafeFrom(1L), Some(oversized)))
        }
    }

    "should persist commitIndex across close and reopen and guard it with CRC32" in {
      val tmpDir = Files.createTempDirectory("raft-hs-commit-")
      val hsPath = tmpDir.resolve("hard_state.bin")

      val hs1 = MmapHardState.open(hsPath)
      hs1.write(
        HardState(
          term = 7L,
          votedFor = Some(NodeId("voter-1")),
          commitIndex = 12_345L
        )
      )
      hs1.close()

      val hs2 = MmapHardState.open(hsPath)
      try
        hs2.read shouldBe HardState(
          term = 7L,
          votedFor = Some(NodeId("voter-1")),
          commitIndex = 12_345L
        )
      finally hs2.close()

      // Flip 1 bit in commitIndex at offset 279 and verify CRC32 detects corruption
      val raw = Files.readAllBytes(hsPath)
      raw(279) = (raw(279) ^ 0x01).toByte
      Files.write(hsPath, raw)

      val hs3 = MmapHardState.open(hsPath)
      try
        val ex = intercept[raft.state.RaftError.StorageCorruption] {
          hs3.read
        }
        ex.reason should include("CRC32 mismatch")
      finally
        hs3.close()
        Files.deleteIfExists(hsPath)
        Files.deleteIfExists(tmpDir)
    }

    "should reject negative commitIndex" in withHardState { (hs, _) =>
      intercept[IllegalArgumentException] {
        hs.write(HardState(term = 1L, votedFor = None, commitIndex = -1L))
      }
    }

    "should read an all-zero existing file as HardState.Empty" in {
      val tmpDir = Files.createTempDirectory("raft-hs-zero-")
      val hsPath = tmpDir.resolve("hard_state.bin")
      Files.write(hsPath, new Array[Byte](280))
      val hs = MmapHardState.open(hsPath)
      try hs.read shouldBe HardState.Empty
      finally
        hs.close()
        Files.deleteIfExists(hsPath)
        Files.deleteIfExists(tmpDir)
    }

    "should reject CRC-valid files with negative term, out-of-range votedForLen, or negative commitIndex" in {
      import java.nio.{ByteBuffer, ByteOrder}
      import java.util.zip.CRC32

      def writeWithValidCrc(
          path: Path,
          term: Long,
          votedForLen: Int,
          commitIdx: Long
      ): Unit =
        val buf = ByteBuffer.allocate(280).order(ByteOrder.nativeOrder())
        buf.putLong(0, term)
        buf.putInt(8, votedForLen)
        buf.putLong(272, commitIdx)
        val arr = buf.array()
        val crc = new CRC32()
        crc.update(arr, 0, 268)
        crc.update(arr, 272, 8)
        buf.putInt(268, crc.getValue.toInt)
        val _ = Files.write(path, arr)

      val cases = List(
        ("negative term", -5L, 0, 0L, "negative term"),
        ("negative votedForLen", 1L, -1, 0L, "invalid votedForLen"),
        ("oversized votedForLen", 1L, 300, 0L, "invalid votedForLen"),
        ("negative commitIndex", 1L, 0, -9L, "negative commitIndex")
      )

      cases.foreach { case (_, term, vfLen, cIdx, expectedMsg) =>
        val tmpDir = Files.createTempDirectory("raft-hs-sem-")
        val hsPath = tmpDir.resolve("hard_state.bin")
        writeWithValidCrc(hsPath, term, vfLen, cIdx)
        val hs = MmapHardState.open(hsPath)
        try
          val ex = intercept[raft.state.RaftError.StorageCorruption] {
            hs.read
          }
          ex.reason should include(expectedMsg)
        finally
          hs.close()
          Files.deleteIfExists(hsPath)
          Files.deleteIfExists(tmpDir)
      }
    }
  }
