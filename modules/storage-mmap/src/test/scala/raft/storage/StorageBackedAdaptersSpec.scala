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

    "should persist term and votedFor atomically via setHardState" in withAdapters {
      (_, stableStore) =>
        for
          _ <- stableStore.setHardState(
            Term.unsafeFrom(12L),
            Some(NodeId("atomic-leader"))
          )
          hs <- stableStore.hardState
        yield hs shouldBe HardState(
          Term.unsafeFrom(12L),
          Some(NodeId("atomic-leader"))
        )
    }
  }

  "StorageBackedSnapshotStore" - {
    "should save, loadLatest, list, and delete snapshots over MmapWalEngine" in {
      val tmpDir = Files.createTempDirectory("raft-snap-adapter-test-")
      MmapWalEngine.open[IO](tmpDir).flatMap { engine =>
        val snapStore = StorageBackedSnapshotStore(engine)
        val snap = raft.state.Snapshot[IO](
          lastIncludedIndex = 42L,
          lastIncludedTerm = 4L,
          data =
            fs2.Stream.emits("kv-snapshot-payload".getBytes("UTF-8")).covary[IO]
        )
        (for
          initial <- snapStore.loadLatest
          _ <- snapStore.save(snap)
          latest <- snapStore.loadLatest
          latestBytes <- latest.get.data.compile.to(Array)
          listed <- snapStore.list
          _ <- snapStore.delete(42L)
          afterDelete <- snapStore.list
        yield
          initial shouldBe None
          latest.map(_.lastIncludedIndex) shouldBe Some(42L)
          new String(latestBytes, "UTF-8") shouldBe "kv-snapshot-payload"
          listed.map(_.lastIncludedIndex) shouldBe Vector(42L)
          afterDelete shouldBe empty
        ).guarantee(engine.close *> IO(deleteRecursive(tmpDir)))
      }
    }

    "should support generic StorageBackedSnapshotStore.fromEngine and MmapWalEngine.factory" in {
      val tmpDir = Files.createTempDirectory("raft-snap-factory-test-")
      MmapWalEngine
        .factory[IO]
        .open(tmpDir.toString)
        .use { genericEngine =>
          val genericSnapStore =
            StorageBackedSnapshotStore.fromEngine(genericEngine)
          val snap = raft.state.Snapshot[IO](
            lastIncludedIndex = 19L,
            lastIncludedTerm = 2L,
            data = fs2.Stream.emits("generic-snap".getBytes("UTF-8")).covary[IO]
          )
          for
            emptyList <- genericSnapStore.list
            _ <- genericSnapStore.save(snap)
            nonEmptyList <- genericSnapStore.list
            _ <- genericSnapStore.delete(19L)
          yield
            emptyList shouldBe empty
            nonEmptyList.map(_.lastIncludedIndex) shouldBe Vector(19L)
            nonEmptyList.head.sizeBytes shouldBe 12L
        }
        .guarantee(IO(deleteRecursive(tmpDir)))
    }
  }

  "EngineBackedRaftStorage" - {
    "should persist Ready/LightReady, compact snapshots, and recover cleanly from MmapWalEngine" in {
      val tmpDir = Files.createTempDirectory("raft-engine-raw-storage-")
      val voters = raft.state.ClusterConfig(
        Set(NodeId("n1"), NodeId("n2"), NodeId("n3"))
      )
      (for
        eng1 <- MmapWalEngine.open[IO](tmpDir, segmentSize = 2048L)
        rs1 <- EngineBackedRaftStorage.open[IO](eng1, voters)
        _ = rs1.initialState shouldBe (HardState.Empty, voters)
        _ = rs1.firstIndex shouldBe 1L
        _ = rs1.lastIndex shouldBe 0L

        rd1 = raft.raw.Ready(
          hardState = Some(HardState(2L, Some(NodeId("n1")), 2L)),
          entries = Vector(
            mkLog(1L, 1L, "e1"),
            mkLog(2L, 2L, "e2"),
            mkLog(3L, 2L, "e3")
          )
        )
        _ <- rs1.persistReady(rd1)
        _ <- rs1.persistLightReady(
          raft.raw.LightReady(commitIndex = Some(3L))
        )
        _ = rs1.entries(1L, 4L).map(_.index) shouldBe Vector(1L, 2L, 3L)
        _ = rs1.term(2L) shouldBe Some(2L)
        _ <- rs1.compactTo(2L, "snap-at-2".getBytes("UTF-8"))
        _ = rs1.firstIndex shouldBe 3L
        _ = rs1.snapshotMetadata.map(_.lastIncludedIndex) shouldBe Some(2L)
        _ <- eng1.close

        // Re-open MmapWalEngine from disk and verify full state recovery
        eng2 <- MmapWalEngine.open[IO](tmpDir, segmentSize = 2048L)
        rs2 <- EngineBackedRaftStorage.open[IO](eng2, voters)
        _ = rs2.hardState shouldBe HardState(2L, Some(NodeId("n1")), 3L)
        _ = rs2.snapshotMetadata.map(s =>
          (s.lastIncludedIndex, new String(s.data, "UTF-8"))
        ) shouldBe Some((2L, "snap-at-2"))
        _ = rs2.entries(3L, 4L).map(e => new String(e.data, "UTF-8")) shouldBe
          Vector("e3")
        _ <- eng2.close
      yield succeed).guarantee(IO(deleteRecursive(tmpDir)))
    }
  }
