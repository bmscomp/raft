package raft.storage

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

import raft.state.{Log, LogIndex, NodeId, Term}
import raft.storage.mmap.MmapWalEngine

class StorageBackedAdaptersSpec
    extends AsyncFreeSpec
    with AsyncIOSpec
    with Matchers:

  private def withAdapters(
      test: (StorageBackedLogStore[IO], StorageBackedStableStore[IO]) => IO[
        Unit
      ]
  ): IO[Unit] =
    val tmpDir = Files.createTempDirectory("raft-adapter-test-")
    MmapWalEngine.open[IO](tmpDir).flatMap { engine =>
      val logStore = StorageBackedLogStore(engine)
      val stableStore = StorageBackedStableStore(engine)
      test(logStore, stableStore).guarantee(
        engine.close *> IO(deleteRecursive(tmpDir))
      )
    }

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).iterator().forEachRemaining(deleteRecursive)
    Files.deleteIfExists(path)

  private def mkLog(index: Long, term: Long, data: String = "cmd"): Log =
    Log.command(index, term, data.getBytes("UTF-8"))

  "StorageBackedLogStore" - {
    "should satisfy the LogStore interface" in withAdapters { (logStore, _) =>
      for
        empty <- logStore.isEmpty
        _ <- logStore.append((1L to 5L).map(i => mkLog(i, 1L)))
        nonEmpty <- logStore.isEmpty
        last <- logStore.lastIndex
        entry <- logStore.get(LogIndex.unsafeFrom(3))
      yield
        empty shouldBe true
        nonEmpty shouldBe false
        last shouldBe LogIndex.unsafeFrom(5)
        entry shouldBe defined
        entry.get.index shouldBe 3L
    }

    "should support getRange" in withAdapters { (logStore, _) =>
      for
        _ <- logStore.append((1L to 10L).map(i => mkLog(i, 1L, s"v-$i")))
        range <- logStore.getRange(
          LogIndex.unsafeFrom(4),
          LogIndex.unsafeFrom(8)
        )
      yield
        range.size shouldBe 5
        range.head.index shouldBe 4L
        range.last.index shouldBe 8L
    }

    "should support termAt" in withAdapters { (logStore, _) =>
      for
        _ <- logStore.append(Seq(mkLog(1, 3L), mkLog(2, 5L)))
        t1 <- logStore.termAt(LogIndex.unsafeFrom(1))
        t2 <- logStore.termAt(LogIndex.unsafeFrom(2))
        tmiss <- logStore.termAt(LogIndex.unsafeFrom(99))
      yield
        t1 shouldBe Some(Term.unsafeFrom(3L))
        t2 shouldBe Some(Term.unsafeFrom(5L))
        tmiss shouldBe None
    }

    "should support truncateFrom" in withAdapters { (logStore, _) =>
      for
        _ <- logStore.append((1L to 10L).map(i => mkLog(i, 1L)))
        _ <- logStore.truncateFrom(LogIndex.unsafeFrom(4))
        last <- logStore.lastIndex
        e3 <- logStore.get(LogIndex.unsafeFrom(3))
        e4 <- logStore.get(LogIndex.unsafeFrom(4))
      yield
        last shouldBe LogIndex.unsafeFrom(3)
        e3 shouldBe defined
        e4 shouldBe None
    }

    "should support clear" in withAdapters { (logStore, _) =>
      for
        _ <- logStore.append((1L to 5L).map(i => mkLog(i, 1L)))
        _ <- logStore.clear
        empty <- logStore.isEmpty
      yield empty shouldBe true
    }

    "should support discardBefore (compaction)" in withAdapters {
      (logStore, _) =>
        for
          _ <- logStore.append((1L to 10L).map(i => mkLog(i, 1L)))
          // Compact everything strictly before index 6 (i.e., keep 6..10)
          _ <- logStore.discardBefore(LogIndex.unsafeFrom(6))

          last <- logStore.lastIndex
          e1 <- logStore.get(LogIndex.unsafeFrom(1))
          e5 <- logStore.get(LogIndex.unsafeFrom(5))
          e6 <- logStore.get(LogIndex.unsafeFrom(6))
        yield
          last shouldBe LogIndex.unsafeFrom(10)
          e1 shouldBe None
          e5 shouldBe None
          e6 shouldBe defined
    }
  }

  "StorageBackedStableStore" - {
    "should initialize with zero term and no vote" in withAdapters {
      (_, stableStore) =>
        for
          term <- stableStore.currentTerm
          voted <- stableStore.votedFor
        yield
          term shouldBe Term.Zero
          voted shouldBe None
    }

    "should set and read current term" in withAdapters { (_, stableStore) =>
      for
        _ <- stableStore.setCurrentTerm(Term.unsafeFrom(10L))
        term <- stableStore.currentTerm
      yield term shouldBe Term.unsafeFrom(10L)
    }

    "should set and read votedFor" in withAdapters { (_, stableStore) =>
      for
        _ <- stableStore.setVotedFor(Some(NodeId("candidate-1")))
        voted <- stableStore.votedFor
      yield voted shouldBe Some(NodeId("candidate-1"))
    }

    "should clear votedFor" in withAdapters { (_, stableStore) =>
      for
        _ <- stableStore.setVotedFor(Some(NodeId("old-vote")))
        _ <- stableStore.setVotedFor(None)
        voted <- stableStore.votedFor
      yield voted shouldBe None
    }

    "should preserve term when setting votedFor" in withAdapters {
      (_, stableStore) =>
        for
          _ <- stableStore.setCurrentTerm(Term.unsafeFrom(7L))
          _ <- stableStore.setVotedFor(Some(NodeId("voter")))
          term <- stableStore.currentTerm
          voted <- stableStore.votedFor
        yield
          term shouldBe Term.unsafeFrom(7L)
          voted shouldBe Some(NodeId("voter"))
    }

    "should preserve votedFor when setting term" in withAdapters {
      (_, stableStore) =>
        for
          _ <- stableStore.setVotedFor(Some(NodeId("node-a")))
          _ <- stableStore.setCurrentTerm(Term.unsafeFrom(99L))
          voted <- stableStore.votedFor
          term <- stableStore.currentTerm
        yield
          voted shouldBe Some(NodeId("node-a"))
          term shouldBe Term.unsafeFrom(99L)
    }

    "should handle multiple term updates" in withAdapters { (_, stableStore) =>
      for
        _ <- stableStore.setCurrentTerm(Term.unsafeFrom(1L))
        _ <- stableStore.setCurrentTerm(Term.unsafeFrom(5L))
        _ <- stableStore.setCurrentTerm(Term.unsafeFrom(100L))
        term <- stableStore.currentTerm
      yield term shouldBe Term.unsafeFrom(100L)
    }
  }

  "LogStore and StableStore sharing the same engine" - {
    "should operate independently on the same engine" in withAdapters {
      (logStore, stableStore) =>
        for
          _ <- logStore.append(Seq(mkLog(1, 1L, "data")))
          _ <- stableStore.setCurrentTerm(Term.unsafeFrom(5L))
          _ <- stableStore.setVotedFor(Some(NodeId("node-x")))
          entry <- logStore.get(LogIndex.unsafeFrom(1))
          term <- stableStore.currentTerm
          voted <- stableStore.votedFor
        yield
          entry shouldBe defined
          new String(entry.get.data) shouldBe "data"
          term shouldBe Term.unsafeFrom(5L)
          voted shouldBe Some(NodeId("node-x"))
    }

    "should allow log operations without affecting hard state" in withAdapters {
      (logStore, stableStore) =>
        for
          _ <- stableStore.setCurrentTerm(Term.unsafeFrom(3L))
          _ <- logStore.append((1L to 10L).map(i => mkLog(i, 1L)))
          _ <- logStore.truncateFrom(LogIndex.unsafeFrom(5))
          _ <- logStore.clear
          term <- stableStore.currentTerm
        yield term shouldBe Term.unsafeFrom(3L)
    }
  }
