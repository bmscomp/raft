package raft.storage.mmap

import java.lang.foreign.{Arena, ValueLayout}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import cats.effect.{Deferred, IO}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.storage.HardState

class Phase2CrashSafeWalSpec
    extends AsyncFreeSpec
    with AsyncIOSpec
    with Matchers:

  private def deleteRecursive(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val s = Files.list(path)
        try s.iterator().forEachRemaining(deleteRecursive)
        finally s.close()
      val _ = Files.deleteIfExists(path)

  "Phase 2 — Crash-Safe FFM WAL, Segment Leases & Streaming RSNP v2 Snapshots" - {

    "WalIndexState (pure immutable 64-bit paged radix index)" - {
      "should preserve referential transparency and handle 64-bit log indices > Int.MaxValue" in IO {
        val base = 5_000_000_000L // > Int.MaxValue (2,147,483,647)
        val s0 = WalIndexState.empty
        val entries =
          (0L until 500L).map(i => (base + i, (i * 64L) + 24L)).toVector
        val s1 = s0.putBatch(segmentId = 7, entries = entries)

        s0.isEmpty shouldBe true
        s1.size shouldBe 500
        s1.firstIndex shouldBe base
        s1.lastIndex shouldBe (base + 499L)
        s1.getSegmentId(base) shouldBe 7
        s1.getFileOffset(base + 250L) shouldBe (250L * 64L + 24L)
        s1.getSegmentId(base - 1L) shouldBe -1
        s1.getSegmentId(base + 500L) shouldBe -1

        val s2 = s1.truncateFrom(base + 300L)
        val s3 = s2.discardBefore(base + 100L)

        // Referential transparency: s1 and s2 remain untouched
        s1.size shouldBe 500
        s2.size shouldBe 300
        s2.lastIndex shouldBe (base + 299L)

        s3.size shouldBe 200
        s3.firstIndex shouldBe (base + 100L)
        s3.lastIndex shouldBe (base + 299L)
        s3.get(base + 99L) shouldBe None
        s3.get(base + 100L) shouldBe Some(WalLocation(7, 100L * 64L + 24L))

        val ckpt = s3.serialize
        val restored = WalIndexState.deserializeChecked(ckpt)
        restored.isRight shouldBe true
        val r = restored.toOption.get
        r.size shouldBe 200
        r.firstIndex shouldBe (base + 100L)
        r.lastIndex shouldBe (base + 299L)
        r.get(base + 299L) shouldBe Some(WalLocation(7, 299L * 64L + 24L))
      }

      "should span multiple 4,096-slot super-pages and 64-slot chunks accurately" in IO {
        val entries = (1L to 9000L).map(i => (i, i * 32L)).toVector
        val idx = WalIndexState.empty.putBatch(3, entries)
        idx.size shouldBe 9000
        idx.countSegmentEntries(3, 100L, 5000L) shouldBe 4900L

        val sliced = idx.discardBefore(4100L).truncateFrom(8200L)
        sliced.size shouldBe 4100
        sliced.firstIndex shouldBe 4100L
        sliced.lastIndex shouldBe 8199L
        sliced.rangeLocations(4098L, 4102L).map(_._1) shouldBe Vector(
          4100L,
          4101L,
          4102L
        )
      }
    }

    "SegmentLease & zero-allocation termAt (ST1)" - {
      "should defer Arena.close() and file deletion until in-flight readers release their pin" in {
        for
          tmpDir <- IO(Files.createTempDirectory("raft-seg-lease-"))
          segPath = tmpDir.resolve("segment-000001.wal")
          seg <- IO {
            val s = MmapSegment.create(segPath, 1L, 4096L)
            val _ = s.appendEntry(
              Log.command(
                1L,
                42L,
                "pinned-payload".getBytes(StandardCharsets.UTF_8)
              )
            )
            s.force()
            s
          }
          lease <- SegmentLease.create[IO](1, seg)
          readerEntered <- Deferred[IO, Unit]
          allowReaderExit <- Deferred[IO, Unit]

          readerFiber <- lease.withReader { pinnedSeg =>
            for
              _ <- readerEntered.complete(())
              _ <- allowReaderExit.get
              term <- IO(pinnedSeg.readTerm(HeaderSize.toLong))
              entry <- IO(pinnedSeg.readEntry(HeaderSize.toLong))
            yield (term, entry.payload.utf8String)
          }.start

          _ <- readerEntered.get
          activeCount <- lease.activeReaderCount
          retireFiber <- lease.retire(deleteFile = true).start
          _ <- lease.isRetiring.iterateUntil(identity)

          // New readers arriving after retire is requested are cleanly rejected (None)
          rejectedRead <- lease.withReader(s =>
            IO(s.readTerm(HeaderSize.toLong))
          )
          stillExistsDuringRead <- IO(Files.exists(segPath))

          _ <- allowReaderExit.complete(())
          readerOut <- readerFiber.joinWithNever
          _ <- retireFiber.joinWithNever
          existsAfterDrain <- IO(Files.exists(segPath))
          _ <- IO(deleteRecursive(tmpDir))
        yield
          activeCount shouldBe 1
          rejectedRead shouldBe None
          stillExistsDuringRead shouldBe true
          readerOut shouldBe Some((42L, "pinned-payload"))
          existsAfterDrain shouldBe false
      }

      "should survive heavy concurrent readRange/termAt vs rotation, cross-segment truncateFrom, and discardBefore" in {
        for
          tmpDir <- IO(Files.createTempDirectory("raft-lease-stress-"))
          engine <- MmapWalEngine.open[IO](tmpDir, segmentSize = 512L)
          _ <- engine.appendEntries(
            (1L to 40L).map(i =>
              Log.command(i, 1L, s"init-$i".getBytes(StandardCharsets.UTF_8))
            )
          )

          readers <- (1 to 6).toList.traverse { r =>
            Stream
              .range(0, 60)
              .evalMap { k =>
                val targetIdx = LogIndex.unsafeFrom(((r + k) % 35) + 1L)
                engine.termAt(targetIdx) *>
                  engine.readRange(
                    LogIndex.unsafeFrom(1L),
                    LogIndex.unsafeFrom(35L)
                  )
              }
              .compile
              .drain
              .start
          }

          writerOps =
            for
              _ <- engine.appendEntries(
                (41L to 80L).map(i =>
                  Log.command(i, 2L, s"b2-$i".getBytes(StandardCharsets.UTF_8))
                )
              )
              // Cross-segment truncation back into an earlier sealed segment
              _ <- engine.truncateFrom(LogIndex.unsafeFrom(25L))
              _ <- engine.appendEntries(
                (25L to 70L).map(i =>
                  Log.command(i, 3L, s"b3-$i".getBytes(StandardCharsets.UTF_8))
                )
              )
              _ <- engine.discardBefore(LogIndex.unsafeFrom(30L))
            yield ()

          _ <- writerOps
          _ <- readers.traverse_(_.joinWithNever)

          t29 <- engine.termAt(LogIndex.unsafeFrom(29L))
          t30 <- engine.termAt(LogIndex.unsafeFrom(30L))
          t70 <- engine.termAt(LogIndex.unsafeFrom(70L))
          _ <- engine.close
          _ <- IO(deleteRecursive(tmpDir))
        yield
          t29 shouldBe None
          t30 shouldBe Some(Term.unsafeFrom(3L))
          t70 shouldBe Some(Term.unsafeFrom(3L))
      }
    }

    "Torn-tail self-healing WAL recovery (ST2)" - {
      "should self-heal an unflushed zeroed recordLen tail (preventing infinite loop) and continue appending" in {
        for
          tmpDir <- IO(Files.createTempDirectory("raft-wal-torntail-zero-"))
          e1 <- MmapWalEngine.open[IO](tmpDir, segmentSize = 4096L)
          _ <- e1.appendEntries(
            (1L to 5L).map(i =>
              Log.command(i, 2L, s"ok-$i".getBytes(StandardCharsets.UTF_8))
            )
          )
          _ <- e1.writeHardState(
            HardState(2L, None, commitIndex = 5L)
          )
          _ <- e1.close

          // Simulate OS crash where header entryCount was incremented to 7 while bytes for 6..7 remained zeroed
          segPath = tmpDir.resolve("wal").resolve("segment-000001.wal")
          _ <- IO {
            val ch = FileChannel.open(
              segPath,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE
            )
            try
              val arena = Arena.ofShared()
              try
                val mapped =
                  ch.map(FileChannel.MapMode.READ_WRITE, 0L, ch.size(), arena)
                mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, 16L, 7L)
                val _ = mapped.force()
              finally arena.close()
            finally ch.close()
          }

          e2 <- MmapWalEngine.open[IO](tmpDir, segmentSize = 4096L)
          lastAfterHeal <- e2.lastLogIndex
          _ <- e2.appendEntries(
            Seq(
              Log.command(6L, 3L, "healed-6".getBytes(StandardCharsets.UTF_8))
            )
          )
          read6 <- e2.readEntry(LogIndex.unsafeFrom(6L))
          _ <- e2.close
          _ <- IO(deleteRecursive(tmpDir))
        yield
          lastAfterHeal shouldBe LogIndex.unsafeFrom(5L)
          read6.map(_.payload.utf8String) shouldBe Some("healed-6")
      }

      "should self-heal a half-written uncommitted tail entry (truncated file or zeroed CRC) but fail fast if committed prefix is torn" in {
        for
          tmpDir <- IO(Files.createTempDirectory("raft-wal-torntail-eof-"))
          e1 <- MmapWalEngine.open[IO](tmpDir, segmentSize = 4096L)
          _ <- e1.appendEntries(
            (1L to 4L).map(i =>
              Log.command(i, 1L, s"entry-$i".getBytes(StandardCharsets.UTF_8))
            )
          )
          // Commit only up to 3; entry 4 is uncommitted
          _ <- e1.writeHardState(
            HardState(1L, None, commitIndex = 3L)
          )
          _ <- e1.close

          // Truncate the .wal file right through the middle of uncommitted entry 4!
          segPath = tmpDir.resolve("wal").resolve("segment-000001.wal")
          _ <- IO {
            val seg = MmapSegment.open(segPath, readOnly = true)
            val endOff = seg.currentWriteOffset
            seg.close()
            val ch = FileChannel.open(segPath, StandardOpenOption.WRITE)
            try
              val _ = ch.truncate(endOff - 6L)
            finally ch.close()
          }

          // Reopen heals uncommitted entry 4 cleanly back to index 3!
          e2 <- MmapWalEngine.open[IO](tmpDir, segmentSize = 4096L)
          lastHealed <- e2.lastLogIndex
          e3 <- e2.readEntry(LogIndex.unsafeFrom(3L))
          _ <- e2.close

          // Now truncate into COMMITTED entry 3 (index <= commitIndex = 3): MUST fail fast!
          _ <- IO {
            val seg3 = MmapSegment.open(segPath, readOnly = true)
            val end3 = seg3.currentWriteOffset
            seg3.close()
            val ch = FileChannel.open(segPath, StandardOpenOption.WRITE)
            try
              val _ = ch.truncate(end3 - 6L)
            finally ch.close()
          }
          committedCorruptAttempt <- MmapWalEngine
            .open[IO](tmpDir, segmentSize = 4096L)
            .attempt
          _ <- IO(deleteRecursive(tmpDir))
        yield
          lastHealed shouldBe LogIndex.unsafeFrom(3L)
          e3.map(_.payload.utf8String) shouldBe Some("entry-3")
          committedCorruptAttempt.isLeft shouldBe true
          committedCorruptAttempt.left.toOption.get shouldBe a[
            CorruptedEntryException
          ]
      }
    }

    "Streaming RSNP v2 checksummed snapshots (ST3)" - {
      "should stream multi-chunk snapshots + ClusterConfig and detect both payload and header bit-rot" in {
        val totalPayload = 200_000
        val rawPayload =
          Array.tabulate[Byte](totalPayload)(i => (i * 31 + 7).toByte)
        val cfg = ClusterConfig(
          Map(
            NodeId("node|1") -> NodeRole.Voter,
            NodeId("node,2") -> NodeRole.Voter,
            NodeId("witness:3") -> NodeRole.Witness
          )
        )

        for
          tmpDir <- IO(Files.createTempDirectory("raft-snap-rsnp-v2-"))
          engine <- MmapWalEngine.open[IO](tmpDir)
          chunkedStream = Stream
            .chunk(Chunk.array(rawPayload))
            .chunkLimit(16 * 1024)
            .unchunks
            .covary[IO]
          _ <- engine.saveSnapshot(
            Snapshot[IO](
              lastIncludedIndex = 1234L,
              lastIncludedTerm = 9L,
              data = chunkedStream,
              config = Some(cfg)
            )
          )

          metas <- engine.listSnapshots
          loadedOpt <- engine.loadSnapshot
          restoredBytes <- loadedOpt.get.data.compile.to(Array)

          // Corrupt 1 byte inside the snapshot payload on disk -> streaming CRC32 must catch it!
          snapFile = tmpDir
            .resolve("snapshots")
            .resolve(s"${MmapWalEngine.formatSnapshotStem(1234L)}.bin")
          _ <- IO {
            val all = Files.readAllBytes(snapFile)
            val mid = all.length / 2
            all(mid) = (all(mid) ^ 0x40).toByte
            val _ = Files.write(snapFile, all)
          }
          corruptPayloadSnap <- engine.loadSnapshot
          payloadVerifyAttempt <-
            corruptPayloadSnap.get.data.compile.drain.attempt

          // Corrupt 1 byte inside the RSNP v2 header -> loadSnapshot must fail immediately!
          _ <- IO {
            val all = Files.readAllBytes(snapFile)
            all(10) = (all(10) ^ 0x01).toByte
            val _ = Files.write(snapFile, all)
          }
          headerVerifyAttempt <- engine.loadSnapshot.attempt

          _ <- engine.close
          _ <- IO(deleteRecursive(tmpDir))
        yield
          metas.size shouldBe 1
          metas.head.sizeBytes shouldBe totalPayload.toLong
          loadedOpt.get.config shouldBe Some(cfg)
          java.util.Arrays.equals(restoredBytes, rawPayload) shouldBe true
          payloadVerifyAttempt.left.toOption.get shouldBe a[
            RaftError.StorageCorruption
          ]
          headerVerifyAttempt.left.toOption.get shouldBe a[
            RaftError.StorageCorruption
          ]
      }

      "should preserve retained active/sealed segments on discardBefore after clearLog or non-1 starting index" in {
        for
          tmpDir <- IO(Files.createTempDirectory("raft-wal-discard-offset-"))
          engine <- MmapWalEngine.open[IO](tmpDir, segmentSize = 4096L)
          _ <- engine.clearLog
          // Append entries starting at index 16 (not 1!) up to 31
          _ <- engine.appendEntries(
            (16L to 31L).map(i =>
              Log.command(i, 3L, s"k-$i".getBytes(StandardCharsets.UTF_8))
            )
          )
          // Discard before 30 -> entries 30 and 31 MUST remain readable!
          _ <- engine.discardBefore(LogIndex.unsafeFrom(30L))
          _ <- engine.appendEntries(
            (32L to 42L).map(i =>
              Log.command(i, 3L, s"k-$i".getBytes(StandardCharsets.UTF_8))
            )
          )
          range <- engine.readRange(
            LogIndex.unsafeFrom(31L),
            LogIndex.unsafeFrom(42L)
          )
          _ <- engine.close
          _ <- IO(deleteRecursive(tmpDir))
        yield
          range.map(_.index) shouldBe (31L to 42L).toVector
          range.head.payload.utf8String shouldBe "k-31"
      }

      "should dynamically allocate an oversized segment when a single entry exceeds segmentSize and survive reopen" in {
        val bigPayload = Array.tabulate[Byte](2048)(i => (i % 199).toByte)
        for
          tmpDir <- IO(Files.createTempDirectory("raft-wal-oversized-"))
          e1 <- MmapWalEngine.open[IO](tmpDir, segmentSize = 512L)
          _ <- e1.appendEntries(
            Seq(
              Log.command(1L, 1L, "small-1".getBytes(StandardCharsets.UTF_8)),
              Log.command(2L, 1L, bigPayload),
              Log.command(3L, 2L, "small-3".getBytes(StandardCharsets.UTF_8))
            )
          )
          _ <- e1.close

          e2 <- MmapWalEngine.open[IO](tmpDir, segmentSize = 512L)
          all <- e2.readRange(LogIndex.unsafeFrom(1L), LogIndex.unsafeFrom(3L))
          t2 <- e2.termAt(LogIndex.unsafeFrom(2L))
          _ <- e2.close
          _ <- IO(deleteRecursive(tmpDir))
        yield
          all.map(_.index) shouldBe Vector(1L, 2L, 3L)
          all(0).payload.utf8String shouldBe "small-1"
          java.util.Arrays.equals(all(1).data, bigPayload) shouldBe true
          all(2).payload.utf8String shouldBe "small-3"
          t2 shouldBe Some(Term.unsafeFrom(1L))
      }
    }
  }
