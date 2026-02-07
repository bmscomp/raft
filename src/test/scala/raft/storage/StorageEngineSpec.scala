package raft.storage

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import raft.state.{Log, LogIndex, LogType, NodeId, Term}

/** Abstract test suite that validates any [[StorageEngine]] implementation.
  *
  * Concrete test classes override `createEngine` to provide the specific engine
  * under test. All tests use temporary directories automatically cleaned up
  * after each test.
  */
trait StorageEngineSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers:

  def createEngine: IO[(StorageEngine[IO], IO[Unit])]

  private def withEngine(test: StorageEngine[IO] => IO[Unit]): IO[Unit] =
    createEngine.flatMap { case (engine, cleanup) =>
      test(engine).guarantee(cleanup)
    }

  private def mkLog(index: Long, term: Long, data: String = "cmd"): Log =
    Log.command(index, term, data.getBytes("UTF-8"))

  "StorageEngine" - {
    "should start with an empty log" in withEngine { engine =>
      for
        empty <- engine.logIsEmpty
        lastIdx <- engine.lastLogIndex
      yield
        empty shouldBe true
        lastIdx shouldBe LogIndex.Zero
    }

    "should append and read back entries" in withEngine { engine =>
      val entries = (1L to 5L).map(i => mkLog(i, 1L, s"cmd-$i"))
      for
        _ <- engine.appendEntries(entries)
        empty <- engine.logIsEmpty
        lastIdx <- engine.lastLogIndex
        entry1 <- engine.readEntry(LogIndex.unsafeFrom(1))
        entry5 <- engine.readEntry(LogIndex.unsafeFrom(5))
        missing <- engine.readEntry(LogIndex.unsafeFrom(10))
      yield
        empty shouldBe false
        lastIdx shouldBe LogIndex.unsafeFrom(5)
        entry1 shouldBe defined
        entry1.get.index shouldBe 1
        new String(entry1.get.data) shouldBe "cmd-1"
        entry5 shouldBe defined
        entry5.get.index shouldBe 5
        missing shouldBe None
    }

    "should read ranges of entries" in withEngine { engine =>
      val entries = (1L to 10L).map(i => mkLog(i, 1L))
      for
        _ <- engine.appendEntries(entries)
        range <- engine.readRange(
          LogIndex.unsafeFrom(3),
          LogIndex.unsafeFrom(7)
        )
      yield
        range.size shouldBe 5
        range.map(_.index) shouldBe Vector(3L, 4L, 5L, 6L, 7L)
    }

    "should return term at index" in withEngine { engine =>
      val entries = Seq(mkLog(1, 1L), mkLog(2, 1L), mkLog(3, 2L))
      for
        _ <- engine.appendEntries(entries)
        term1 <- engine.termAt(LogIndex.unsafeFrom(1))
        term3 <- engine.termAt(LogIndex.unsafeFrom(3))
        termMissing <- engine.termAt(LogIndex.unsafeFrom(99))
      yield
        term1 shouldBe Some(Term.unsafeFrom(1L))
        term3 shouldBe Some(Term.unsafeFrom(2L))
        termMissing shouldBe None
    }

    "should truncate from index" in withEngine { engine =>
      val entries = (1L to 10L).map(i => mkLog(i, 1L))
      for
        _ <- engine.appendEntries(entries)
        _ <- engine.truncateFrom(LogIndex.unsafeFrom(6))
        lastIdx <- engine.lastLogIndex
        entry5 <- engine.readEntry(LogIndex.unsafeFrom(5))
        entry6 <- engine.readEntry(LogIndex.unsafeFrom(6))
      yield
        lastIdx shouldBe LogIndex.unsafeFrom(5)
        entry5 shouldBe defined
        entry6 shouldBe None
    }

    "should clear the log" in withEngine { engine =>
      val entries = (1L to 5L).map(i => mkLog(i, 1L))
      for
        _ <- engine.appendEntries(entries)
        _ <- engine.clearLog
        empty <- engine.logIsEmpty
        lastIdx <- engine.lastLogIndex
      yield
        empty shouldBe true
        lastIdx shouldBe LogIndex.Zero
    }

    "should read and write hard state" in withEngine { engine =>
      val state = HardState(Term.unsafeFrom(5L), Some(NodeId("node-2")))
      for
        initial <- engine.readHardState
        _ <- engine.writeHardState(state)
        read <- engine.readHardState
      yield
        initial.currentTerm shouldBe Term.Zero
        initial.votedFor shouldBe None
        read.currentTerm shouldBe Term.unsafeFrom(5L)
        read.votedFor shouldBe Some(NodeId("node-2"))
    }

    "should persist and load snapshots" in withEngine { engine =>
      val snapshot = raft.state.Snapshot(
        lastIncludedIndex = 100L,
        lastIncludedTerm = 5L,
        data = "snapshot-data".getBytes("UTF-8")
      )
      for
        before <- engine.loadSnapshot
        _ <- engine.saveSnapshot(snapshot)
        after <- engine.loadSnapshot
      yield
        before shouldBe None
        after shouldBe defined
        after.get.lastIncludedIndex shouldBe 100L
        after.get.lastIncludedTerm shouldBe 5L
        new String(after.get.data) shouldBe "snapshot-data"
    }

    "should handle NoOp entries" in withEngine { engine =>
      val entries = Seq(
        Log.noOp(1, 1L),
        mkLog(2, 1L, "real-cmd"),
        Log.noOp(3, 2L)
      )
      for
        _ <- engine.appendEntries(entries)
        entry1 <- engine.readEntry(LogIndex.unsafeFrom(1))
        entry2 <- engine.readEntry(LogIndex.unsafeFrom(2))
        entry3 <- engine.readEntry(LogIndex.unsafeFrom(3))
      yield
        entry1.get.logType shouldBe LogType.NoOp
        entry1.get.data shouldBe empty
        entry2.get.logType shouldBe LogType.Command
        entry3.get.logType shouldBe LogType.NoOp
    }

    "should handle large entries" in withEngine { engine =>
      val largeData = new Array[Byte](100_000) // 100 KB
      java.util.Arrays.fill(largeData, 42.toByte)
      val entry = Log.command(1, 1L, largeData)
      for
        _ <- engine.appendEntries(Seq(entry))
        read <- engine.readEntry(LogIndex.unsafeFrom(1))
      yield
        read shouldBe defined
        read.get.data.length shouldBe 100_000
        read.get.data(0) shouldBe 42.toByte
        read.get.data(99_999) shouldBe 42.toByte
    }

    "should handle many appends" in withEngine { engine =>
      val entries = (1L to 500L).map(i => mkLog(i, 1L, s"data-$i"))
      for
        _ <- engine.appendEntries(entries)
        lastIdx <- engine.lastLogIndex
        first <- engine.readEntry(LogIndex.unsafeFrom(1))
        last <- engine.readEntry(LogIndex.unsafeFrom(500))
      yield
        lastIdx shouldBe LogIndex.unsafeFrom(500)
        first shouldBe defined
        last shouldBe defined
        new String(last.get.data) shouldBe "data-500"
    }

    "should append entries in multiple batches" in withEngine { engine =>
      for
        _ <- engine.appendEntries((1L to 5L).map(i => mkLog(i, 1L)))
        _ <- engine.appendEntries((6L to 10L).map(i => mkLog(i, 2L)))
        lastIdx <- engine.lastLogIndex
        entry6 <- engine.readEntry(LogIndex.unsafeFrom(6))
      yield
        lastIdx shouldBe LogIndex.unsafeFrom(10)
        entry6.get.term shouldBe 2L
    }
  }
