package raft.storage.mmap

import cats.effect.IO
import java.nio.file.{Files, Path}

import raft.storage.{StorageEngine, StorageEngineSpec}

class MmapWalEngineSpec extends StorageEngineSpec:

  def createEngine: IO[(StorageEngine[IO], IO[Unit])] =
    IO {
      val tmpDir = Files.createTempDirectory("raft-wal-test-")
      (tmpDir, IO(deleteRecursive(tmpDir)))
    }.flatMap { case (tmpDir, cleanup) =>
      MmapWalEngine
        .open(tmpDir)
        .map(engine => (engine: StorageEngine[IO], engine.close *> cleanup))
    }

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      Files.list(path).iterator().forEachRemaining(deleteRecursive)
    Files.deleteIfExists(path)

  "MmapWalEngine" - {
    "should survive close and reopen (crash recovery)" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-recovery-"))
        // Write entries
        engine1 <- MmapWalEngine.open(tmpDir)
        entries = (1L to 20L).map(i =>
          raft.state.Log.command(i, 1L, s"cmd-$i".getBytes("UTF-8"))
        )
        _ <- engine1.appendEntries(entries)
        _ <- engine1.writeHardState(
          raft.storage.HardState(
            raft.state.Term.unsafeFrom(3L),
            Some(raft.state.NodeId("node-1"))
          )
        )
        _ <- engine1.close

        // Reopen and verify
        engine2 <- MmapWalEngine.open(tmpDir)
        lastIdx <- engine2.lastLogIndex
        entry1 <- engine2.readEntry(raft.state.LogIndex.unsafeFrom(1))
        entry20 <- engine2.readEntry(raft.state.LogIndex.unsafeFrom(20))
        hs <- engine2.readHardState
        _ <- engine2.close
        _ <- IO(deleteRecursive(tmpDir))
      yield
        lastIdx shouldBe raft.state.LogIndex.unsafeFrom(20)
        entry1 shouldBe defined
        new String(entry1.get.data) shouldBe "cmd-1"
        entry20 shouldBe defined
        new String(entry20.get.data) shouldBe "cmd-20"
        hs.currentTerm shouldBe raft.state.Term.unsafeFrom(3L)
        hs.votedFor shouldBe Some(raft.state.NodeId("node-1"))
      test
    }

    "should detect CRC corruption" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-crc-"))
        engine <- MmapWalEngine.open(tmpDir)
        _ <- engine.appendEntries(
          Seq(
            raft.state.Log.command(1, 1L, "test-data".getBytes("UTF-8"))
          )
        )
        _ <- engine.close

        // Corrupt the WAL file
        _ <- IO {
          val walFile = tmpDir.resolve("wal").resolve("segment-000001.wal")
          val bytes = Files.readAllBytes(walFile)
          // Flip a data byte (skip 24-byte header + 4-byte recordLen + 8 index + 8 term + 1 type + 4 datalen = offset 49)
          bytes(49) = (bytes(49) ^ 0xff).toByte
          Files.write(walFile, bytes)
        }

        // Reopen should succeed (index is rebuilt)
        result <- MmapWalEngine.open(tmpDir).attempt
        _ <- IO(deleteRecursive(tmpDir))
      yield
        // The corrupted entry should cause a CRC error during replay
        result.isLeft shouldBe true
        result.left.getOrElse(null) shouldBe a[CorruptedEntryException]
      test
    }

    "should handle segment with multiple entries after reopen" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-multi-"))
        engine1 <- MmapWalEngine.open(tmpDir)
        // Write entries one at a time to test incremental append
        _ <- engine1.appendEntries(
          Seq(raft.state.Log.command(1, 1L, "a".getBytes))
        )
        _ <- engine1.appendEntries(
          Seq(raft.state.Log.command(2, 1L, "b".getBytes))
        )
        _ <- engine1.appendEntries(
          Seq(raft.state.Log.command(3, 2L, "c".getBytes))
        )
        _ <- engine1.close

        engine2 <- MmapWalEngine.open(tmpDir)
        lastIdx <- engine2.lastLogIndex
        range <- engine2.readRange(
          raft.state.LogIndex.unsafeFrom(1),
          raft.state.LogIndex.unsafeFrom(3)
        )
        _ <- engine2.close
        _ <- IO(deleteRecursive(tmpDir))
      yield
        lastIdx shouldBe raft.state.LogIndex.unsafeFrom(3)
        range.size shouldBe 3
        range.map(_.index) shouldBe Vector(1L, 2L, 3L)
      test
    }

    "should handle hard state with no vote" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-novote-"))
        engine <- MmapWalEngine.open(tmpDir)
        _ <- engine.writeHardState(
          raft.storage.HardState(raft.state.Term.unsafeFrom(7L), None)
        )
        hs <- engine.readHardState
        _ <- engine.close
        _ <- IO(deleteRecursive(tmpDir))
      yield
        hs.currentTerm shouldBe raft.state.Term.unsafeFrom(7L)
        hs.votedFor shouldBe None
      test
    }

    "should handle truncate-then-append (log conflict resolution)" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-conflict-"))
        engine <- MmapWalEngine.open(tmpDir)
        // Leader writes entries 1-10 in term 1
        _ <- engine.appendEntries(
          (1L to 10L).map(i =>
            raft.state.Log.command(i, 1L, s"old-$i".getBytes)
          )
        )
        // New leader truncates from 6 and appends in term 2
        _ <- engine.truncateFrom(raft.state.LogIndex.unsafeFrom(6))
        _ <- engine.appendEntries(
          (6L to 8L).map(i => raft.state.Log.command(i, 2L, s"new-$i".getBytes))
        )
        lastIdx <- engine.lastLogIndex
        e5 <- engine.readEntry(raft.state.LogIndex.unsafeFrom(5))
        e6 <- engine.readEntry(raft.state.LogIndex.unsafeFrom(6))
        e8 <- engine.readEntry(raft.state.LogIndex.unsafeFrom(8))
        e9 <- engine.readEntry(raft.state.LogIndex.unsafeFrom(9))
        _ <- engine.close
        _ <- IO(deleteRecursive(tmpDir))
      yield
        lastIdx shouldBe raft.state.LogIndex.unsafeFrom(8)
        e5.get.term shouldBe 1L
        e6.get.term shouldBe 2L
        new String(e6.get.data) shouldBe "new-6"
        e8.get.term shouldBe 2L
        e9 shouldBe None
      test
    }

    "should persist and load snapshots with data integrity" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-snap-"))
        engine <- MmapWalEngine.open(tmpDir)
        snapshotData = "state-machine-snapshot-v1".getBytes("UTF-8")
        snapshot = raft.state.Snapshot(
          lastIncludedIndex = 500L,
          lastIncludedTerm = 10L,
          data = snapshotData
        )
        _ <- engine.saveSnapshot(snapshot)
        _ <- engine.close

        // Reopen and verify snapshot survives
        engine2 <- MmapWalEngine.open(tmpDir)
        loaded <- engine2.loadSnapshot
        _ <- engine2.close
        _ <- IO(deleteRecursive(tmpDir))
      yield
        loaded shouldBe defined
        loaded.get.lastIncludedIndex shouldBe 500L
        loaded.get.lastIncludedTerm shouldBe 10L
        new String(loaded.get.data) shouldBe "state-machine-snapshot-v1"
      test
    }

    "should work with Resource-based lifecycle" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-resource-"))
        _ <- MmapWalEngine.resource(tmpDir).use { engine =>
          for
            _ <- engine.appendEntries(
              (1L to 5L)
                .map(i => raft.state.Log.command(i, 1L, s"r-$i".getBytes))
            )
            _ <- engine.writeHardState(
              raft.storage.HardState(
                raft.state.Term.unsafeFrom(3L),
                Some(raft.state.NodeId("n1"))
              )
            )
          yield ()
        }
        // Resource should have closed the engine — reopen to verify data
        engine2 <- MmapWalEngine.open(tmpDir)
        last <- engine2.lastLogIndex
        hs <- engine2.readHardState
        _ <- engine2.close
        _ <- IO(deleteRecursive(tmpDir))
      yield
        last shouldBe raft.state.LogIndex.unsafeFrom(5)
        hs.currentTerm shouldBe raft.state.Term.unsafeFrom(3L)
      test
    }

    "should survive multiple close-reopen cycles" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-cycles-"))
        // Cycle 1: write entries 1-5
        e1 <- MmapWalEngine.open(tmpDir)
        _ <- e1.appendEntries(
          (1L to 5L).map(i => raft.state.Log.command(i, 1L, s"c1-$i".getBytes))
        )
        _ <- e1.close
        // Cycle 2: write entries 6-10
        e2 <- MmapWalEngine.open(tmpDir)
        _ <- e2.appendEntries(
          (6L to 10L).map(i => raft.state.Log.command(i, 2L, s"c2-$i".getBytes))
        )
        _ <- e2.close
        // Cycle 3: write entries 11-15
        e3 <- MmapWalEngine.open(tmpDir)
        _ <- e3.appendEntries(
          (11L to 15L).map(i =>
            raft.state.Log.command(i, 3L, s"c3-$i".getBytes)
          )
        )
        _ <- e3.close
        // Cycle 4: verify everything
        e4 <- MmapWalEngine.open(tmpDir)
        last <- e4.lastLogIndex
        entry1 <- e4.readEntry(raft.state.LogIndex.unsafeFrom(1))
        entry10 <- e4.readEntry(raft.state.LogIndex.unsafeFrom(10))
        entry15 <- e4.readEntry(raft.state.LogIndex.unsafeFrom(15))
        _ <- e4.close
        _ <- IO(deleteRecursive(tmpDir))
      yield
        last shouldBe raft.state.LogIndex.unsafeFrom(15)
        new String(entry1.get.data) shouldBe "c1-1"
        entry10.get.term shouldBe 2L
        new String(entry15.get.data) shouldBe "c3-15"
      test
    }

    "should append after clear" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-clear-append-"))
        engine <- MmapWalEngine.open(tmpDir)
        _ <- engine.appendEntries(
          (1L to 5L).map(i => raft.state.Log.command(i, 1L, s"old-$i".getBytes))
        )
        _ <- engine.clearLog
        _ <- engine.appendEntries(
          (1L to 3L).map(i => raft.state.Log.command(i, 2L, s"new-$i".getBytes))
        )
        last <- engine.lastLogIndex
        e1 <- engine.readEntry(raft.state.LogIndex.unsafeFrom(1))
        e3 <- engine.readEntry(raft.state.LogIndex.unsafeFrom(3))
        _ <- engine.close
        _ <- IO(deleteRecursive(tmpDir))
      yield
        last shouldBe raft.state.LogIndex.unsafeFrom(3)
        e1.get.term shouldBe 2L
        new String(e1.get.data) shouldBe "new-1"
        new String(e3.get.data) shouldBe "new-3"
      test
    }

    "should handle truncate-all as clear" in {
      val test = for
        tmpDir <- IO(Files.createTempDirectory("raft-wal-truncateall-"))
        engine <- MmapWalEngine.open(tmpDir)
        _ <- engine.appendEntries(
          (1L to 5L).map(i => raft.state.Log.command(i, 1L, "data".getBytes))
        )
        _ <- engine.truncateFrom(raft.state.LogIndex.unsafeFrom(1))
        empty <- engine.logIsEmpty
        last <- engine.lastLogIndex
        _ <- engine.close
        _ <- IO(deleteRecursive(tmpDir))
      yield
        empty shouldBe true
        last shouldBe raft.state.LogIndex.Zero
      test
    }
  }
