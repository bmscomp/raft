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
  }
