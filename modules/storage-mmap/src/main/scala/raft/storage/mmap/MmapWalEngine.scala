package raft.storage.mmap

import java.nio.file.{Files as JFiles, Path as JPath, StandardCopyOption}
import scala.jdk.CollectionConverters.*

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.io.file.{Files, Path}

import raft.state.{
  ClusterConfig,
  Log,
  LogIndex,
  NodeId,
  NodeRole,
  Snapshot,
  Term
}
import raft.storage.{HardState, StorageEngine, StorageEngineFactory}

/** Memory-mapped WAL storage engine for Raft.
  *
  * Uses JDK's Foreign Function & Memory API (`MemorySegment`) for zero-copy
  * segmented WAL persistence, guarded by a Cats Effect [[Mutex]] so concurrent
  * appends, truncations, compactions, and snapshot operations execute without
  * races. Checkpoints (`index.ckpt`) are written atomically with `"WIDX"` CRC32
  * framing and automatically fall back to full `.wal` segment replay if torn or
  * corrupted.
  */
class MmapWalEngine[F[_]: Async] private (
    val dataDir: JPath,
    writeMutex: Mutex[F],
    activeSegmentRef: Ref[F, MmapSegment],
    sealedSegmentsRef: Ref[F, Vector[(Int, MmapSegment)]],
    walIndex: WalIndex,
    hardState: MmapHardState,
    nextSegmentId: Ref[F, Int],
    activeSegmentId: Ref[F, Int],
    lastCheckpointedCount: Ref[F, Int],
    segmentSize: Long
) extends StorageEngine[F]:

  private val F = Async[F]
  private val fs2Files = Files.forAsync[F]
  private val walDir = dataDir.resolve("wal")
  private val snapshotDir = dataDir.resolve("snapshots")
  private val checkpointPath = dataDir.resolve("index.ckpt")
  private val checkpointTmpPath = dataDir.resolve("index.ckpt.tmp")

  def appendEntries(entries: Seq[Log]): F[Unit] =
    writeMutex.lock.surround(appendChunked(entries.toVector))

  private def appendChunked(remaining: Vector[Log]): F[Unit] =
    if remaining.isEmpty then F.unit
    else
      activeSegmentRef.get.flatMap { seg =>
        val firstCost = (EntryOverhead + remaining.head.data.length).toLong
        if seg.remaining < firstCost then
          val minRequired = HeaderSize.toLong + firstCost
          rotateSegment(math.max(segmentSize, minRequired)) >>
            appendChunked(remaining)
        else
          var budget = seg.remaining
          var takeCount = 0
          while takeCount < remaining.length &&
            (EntryOverhead + remaining(takeCount).data.length).toLong <= budget
          do
            budget -= (EntryOverhead + remaining(takeCount).data.length).toLong
            takeCount += 1

          val (batch, rest) = remaining.splitAt(takeCount)
          for
            segId <- activeSegmentId.get
            batchStart = seg.currentWriteOffset
            results <- F.delay(seg.appendBatch(batch))
            _ <- F.delay {
              results.foreach { (logIdx, offset) =>
                walIndex.put(logIdx, segId, offset)
              }
            }
            _ <- F.delay(seg.forceRange(batchStart, seg.currentWriteOffset))
            _ <- maybeCheckpointIndex
            _ <- appendChunked(rest)
          yield ()
      }

  private def rotateSegment(allocBytes: Long): F[MmapSegment] =
    for
      oldSeg <- activeSegmentRef.get
      _ <- F.delay(oldSeg.force())
      newId <- nextSegmentId.getAndUpdate(_ + 1)
      oldId <- activeSegmentId.getAndSet(newId)
      segPath = segmentPath(newId)
      lastIdx <- F.delay(walIndex.lastIndex)
      newSeg <- F.delay(MmapSegment.create(segPath, lastIdx + 1L, allocBytes))
      _ <- sealedSegmentsRef.update(_ :+ (oldId, oldSeg))
      _ <- activeSegmentRef.set(newSeg)
    yield newSeg

  def readEntry(index: LogIndex): F[Option[Log]] =
    F.delay {
      val segId = walIndex.getSegmentId(index.value)
      if segId == -1 then None
      else Some((segId, walIndex.getFileOffset(index.value)))
    }.flatMap {
      case None                  => F.pure(None)
      case Some((segId, offset)) =>
        findSegment(segId).flatMap {
          case Some(seg) => F.delay(Some(seg.readEntryUnchecked(offset)))
          case None      => F.pure(None)
        }
    }

  def readRange(start: LogIndex, end: LogIndex): F[Vector[Log]] =
    if end.value < start.value then F.pure(Vector.empty)
    else
      F.delay {
        (start.value to end.value).flatMap { i =>
          val segId = walIndex.getSegmentId(i)
          if segId == -1 then None
          else Some((i, segId, walIndex.getFileOffset(i)))
        }
      }.flatMap { locations =>
        if locations.isEmpty then F.pure(Vector.empty)
        else
          val bySegment = locations.groupBy(_._2)
          bySegment.toVector
            .flatTraverse { case (segId, locs) =>
              findSegment(segId).flatMap {
                case Some(seg) =>
                  F.delay {
                    locs.map { case (_, _, offset) =>
                      seg.readEntryUnchecked(offset)
                    }.toVector
                  }
                case None => F.pure(Vector.empty)
              }
            }
            .map(_.sortBy(_.index))
      }

  def lastLogIndex: F[LogIndex] =
    F.delay(LogIndex.unsafeFrom(walIndex.lastIndex))

  def termAt(index: LogIndex): F[Option[Term]] =
    readEntry(index).map(_.map(l => Term.unsafeFrom(l.term)))

  def logIsEmpty: F[Boolean] =
    F.delay(walIndex.isEmpty)

  def truncateFrom(index: LogIndex): F[Unit] =
    writeMutex.lock.surround {
      F.delay(walIndex.get(index.value)).flatMap {
        case Some(loc) =>
          for
            segOpt <- findSegment(loc.segmentId)
            _ <- segOpt match
              case Some(seg) =>
                val scanStart = math.max(1L, seg.firstIndex)
                F.delay {
                  var newCount = 0L
                  var i = scanStart
                  while i < index.value do
                    if walIndex.getSegmentId(i) == loc.segmentId then
                      newCount += 1L
                    i += 1L
                  seg.truncateAt(loc.fileOffset, newCount)
                }
              case None => F.unit
            droppedAfter <- sealedSegmentsRef.modify { segs =>
              segs.partition(_._1 <= loc.segmentId)
            }
            _ <- droppedAfter.traverse_ { case (_, seg) =>
              F.delay {
                seg.close()
                val _ = JFiles.deleteIfExists(seg.path)
              }
            }
            curActId <- activeSegmentId.get
            _ <-
              if loc.segmentId < curActId then
                for
                  oldAct <- activeSegmentRef.get
                  _ <- F.delay {
                    oldAct.close()
                    val _ = JFiles.deleteIfExists(oldAct.path)
                  }
                  _ <- sealedSegmentsRef
                    .modify { segs =>
                      val target = segs.find(_._1 == loc.segmentId)
                      (segs.filterNot(_._1 == loc.segmentId), target)
                    }
                    .flatMap {
                      case Some((sid, promoted)) =>
                        F.delay(promoted.close()) >>
                          F.delay(
                            MmapSegment.open(promoted.path, readOnly = false)
                          ).flatMap(rw =>
                            activeSegmentId.set(sid) >> activeSegmentRef.set(rw)
                          )
                      case None => F.unit
                    }
                yield ()
              else F.unit
            _ <- F.delay(walIndex.truncateFrom(index.value))
            _ <- lastCheckpointedCount.set(walIndex.size)
            _ <- F.delay { val _ = JFiles.deleteIfExists(checkpointPath) }
          yield ()
        case None => F.unit
      }
    }

  def discardBefore(index: LogIndex): F[Unit] =
    writeMutex.lock.surround {
      val splitIndex = index.value
      for
        _ <- F.delay(walIndex.discardBefore(splitIndex))
        _ <- lastCheckpointedCount.set(walIndex.size)
        obsoleteSealed <- sealedSegmentsRef.modify { segments =>
          segments.partition { case (_, seg) =>
            val lastIndex = seg.firstIndex + seg.currentEntryCount - 1L
            lastIndex >= splitIndex
          }
        }
        _ <- obsoleteSealed.traverse_ { case (_, seg) =>
          F.delay {
            seg.close()
            val _ = JFiles.deleteIfExists(seg.path)
          }
        }
        oldActive <- activeSegmentRef.get
        lastActiveIndex =
          oldActive.firstIndex + oldActive.currentEntryCount - 1L
        _ <-
          if oldActive.currentEntryCount > 0 && lastActiveIndex < splitIndex
          then
            for
              _ <- F.delay {
                oldActive.close()
                val _ = JFiles.deleteIfExists(oldActive.path)
              }
              newId <- nextSegmentId.getAndUpdate(_ + 1)
              _ <- activeSegmentId.set(newId)
              newSeg <- F.delay(
                MmapSegment.create(segmentPath(newId), splitIndex, segmentSize)
              )
              _ <- activeSegmentRef.set(newSeg)
            yield ()
          else F.unit
        _ <- F.delay { val _ = JFiles.deleteIfExists(checkpointPath) }
      yield ()
    }

  def clearLog: F[Unit] =
    writeMutex.lock.surround {
      for
        sealedSegs <- sealedSegmentsRef.getAndSet(Vector.empty)
        _ <- sealedSegs.traverse_ { case (_, seg) => F.delay(seg.close()) }
        active <- activeSegmentRef.get
        _ <- F.delay(active.close())
        _ <- F.delay {
          if JFiles.exists(walDir) then
            val stream = JFiles.list(walDir)
            try
              stream
                .iterator()
                .asScala
                .filter(_.toString.endsWith(".wal"))
                .foreach { p =>
                  val _ = JFiles.deleteIfExists(p)
                }
            finally stream.close()
          val _ = JFiles.deleteIfExists(checkpointPath)
        }
        newId <- nextSegmentId.getAndUpdate(_ + 1)
        _ <- activeSegmentId.set(newId)
        newSeg <- F.delay(
          MmapSegment.create(segmentPath(newId), 1L, segmentSize)
        )
        _ <- activeSegmentRef.set(newSeg)
        _ <- F.delay(walIndex.clear())
        _ <- lastCheckpointedCount.set(0)
      yield ()
    }

  def readHardState: F[HardState] =
    writeMutex.lock.surround(F.delay(hardState.read))

  def writeHardState(state: HardState): F[Unit] =
    writeMutex.lock.surround(F.delay(hardState.write(state)))

  def saveSnapshot(snapshot: Snapshot[F]): F[Unit] =
    writeMutex.lock.surround {
      val fileName =
        MmapWalEngine.formatSnapshotStem(snapshot.lastIncludedIndex)
      val snapshotPath = snapshotDir.resolve(s"$fileName.bin")
      val tmpPath = snapshotDir.resolve(s"$fileName.tmp")
      val metadata = Chunk.array(serializeMetadata(snapshot))
      val stream = Stream.chunk(metadata) ++ snapshot.data

      F.delay { val _ = JFiles.createDirectories(snapshotDir) } >>
        stream
          .through(fs2Files.writeAll(Path.fromNioPath(tmpPath)))
          .compile
          .drain >>
        F.delay {
          val _ = JFiles.move(
            tmpPath,
            snapshotPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
          )
        } >>
        F.delay {
          val listing = JFiles.list(snapshotDir)
          try
            listing
              .iterator()
              .asScala
              .filter(p =>
                p.toString.endsWith(".bin") && !p.equals(snapshotPath)
              )
              .foreach { p =>
                val _ = JFiles.deleteIfExists(p)
              }
          finally listing.close()
        }
    }

  def loadSnapshot: F[Option[Snapshot[F]]] =
    writeMutex.lock.surround {
      F.delay(JFiles.exists(snapshotDir)).flatMap { exists =>
        if !exists then F.pure(None)
        else
          F.delay {
            val listing = JFiles.list(snapshotDir)
            try
              listing
                .iterator()
                .asScala
                .filter(_.getFileName.toString.endsWith(".bin"))
                .flatMap(p => MmapWalEngine.parseSnapshotIndex(p).map(_ -> p))
                .toList
                .maxByOption(_._1)
                .map(_._2)
            finally listing.close()
          }.flatMap {
            case None       => F.pure(None)
            case Some(path) =>
              F.blocking(JFiles.readAllBytes(path)).flatMap { rawBytes =>
                parseSnapshotBytes(rawBytes).map(Some(_))
              }
          }
      }
    }

  /** List persisted snapshot metadata ordered by `lastIncludedIndex` ascending.
    */
  def listSnapshots: F[Vector[raft.spi.SnapshotMetadata]] =
    writeMutex.lock.surround {
      F.delay {
        if !JFiles.exists(snapshotDir) then Vector.empty
        else
          val listing = JFiles.list(snapshotDir)
          try
            listing
              .iterator()
              .asScala
              .filter(_.getFileName.toString.endsWith(".bin"))
              .flatMap { p =>
                MmapWalEngine.parseSnapshotIndex(p).flatMap { _ =>
                  readSnapshotHeader(p)
                }
              }
              .toVector
              .sortBy(_.lastIncludedIndex)
          finally listing.close()
      }
    }

  /** Delete a specific persisted snapshot by `lastIncludedIndex`. */
  def deleteSnapshot(index: Long): F[Unit] =
    writeMutex.lock.surround {
      F.delay {
        if JFiles.exists(snapshotDir) then
          val listing = JFiles.list(snapshotDir)
          try
            listing
              .iterator()
              .asScala
              .filter(p => MmapWalEngine.parseSnapshotIndex(p).contains(index))
              .foreach { p =>
                val _ = JFiles.deleteIfExists(p)
              }
          finally listing.close()
      }
    }

  private def readSnapshotHeader(
      path: JPath
  ): Option[raft.spi.SnapshotMetadata] =
    val bytes = JFiles.readAllBytes(path)
    if bytes.length < 20 then None
    else
      val buf = java.nio.ByteBuffer.wrap(bytes)
      val lastIndex = buf.getLong()
      val lastTerm = buf.getLong()
      val configLen = buf.getInt()
      val headerSize = 20L + math.max(0, configLen).toLong
      val payloadBytes = math.max(0L, bytes.length.toLong - headerSize)
      Some(raft.spi.SnapshotMetadata(lastIndex, lastTerm, payloadBytes))

  private def parseSnapshotBytes(rawBytes: Array[Byte]): F[Snapshot[F]] =
    if rawBytes.length < 16 then
      F.raiseError(new RuntimeException("Truncated snapshot metadata"))
    else if rawBytes.length < 20 then
      F.raiseError(new RuntimeException("Truncated snapshot config length"))
    else
      val buf = java.nio.ByteBuffer.wrap(rawBytes)
      val lastIndex = buf.getLong()
      val lastTerm = buf.getLong()
      val configLen = buf.getInt()
      if configLen < 0 || rawBytes.length < 20 + configLen then
        F.raiseError(new RuntimeException("Truncated snapshot config"))
      else
        val cfgOpt =
          if configLen > 0 then
            val cfgBytes = new Array[Byte](configLen)
            val _ = buf.get(cfgBytes)
            Some(deserializeConfig(cfgBytes))
          else None
        val dataOffset = 20 + configLen
        val dataLen = rawBytes.length - dataOffset
        val dataStream =
          if dataLen > 0 then
            Stream.chunk(Chunk.array(rawBytes, dataOffset, dataLen)).covary[F]
          else Stream.empty.covary[F]
        F.pure(Snapshot[F](lastIndex, lastTerm, dataStream, cfgOpt))

  private def findSegment(segmentId: Int): F[Option[MmapSegment]] =
    for
      currentActiveId <- activeSegmentId.get
      result <-
        if segmentId == currentActiveId then activeSegmentRef.get.map(Some(_))
        else sealedSegmentsRef.get.map(_.find(_._1 == segmentId).map(_._2))
    yield result

  private def segmentPath(id: Int): JPath =
    walDir.resolve(s"segment-${"%06d".format(id)}.wal")

  private def maybeCheckpointIndex: F[Unit] =
    for
      prev <- lastCheckpointedCount.get
      cur <- F.delay(walIndex.size)
      _ <-
        if cur > 0 && (cur - prev >= 1000) then
          F.delay(walIndex.serialize).flatMap(writeCheckpointAtomically) >>
            lastCheckpointedCount.set(cur)
        else F.unit
    yield ()

  private def writeCheckpointAtomically(bytes: Array[Byte]): F[Unit] =
    F.delay {
      val _ = JFiles.write(checkpointTmpPath, bytes)
      val _ = JFiles.move(
        checkpointTmpPath,
        checkpointPath,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
    }

  private def serializeMetadata(snapshot: Snapshot[F]): Array[Byte] =
    val configBytes =
      snapshot.config.map(ClusterConfig.encode).getOrElse(Array.emptyByteArray)
    val buf = java.nio.ByteBuffer.allocate(
      16 + 4 + configBytes.length
    )
    val _ = buf.putLong(snapshot.lastIncludedIndex)
    val _ = buf.putLong(snapshot.lastIncludedTerm)
    val _ = buf.putInt(configBytes.length)
    val _ = buf.put(configBytes)
    buf.array()

  private def deserializeConfig(bytes: Array[Byte]): ClusterConfig =
    ClusterConfig.decode(bytes).getOrElse {
      val str = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
      val members = str
        .split(",")
        .filter(_.nonEmpty)
        .map { entry =>
          val parts = entry.split(":")
          NodeId(parts(0)) -> NodeRole.fromOrdinal(parts(1).toInt)
        }
        .toMap
      ClusterConfig(members)
    }

  /** Close all resources (segments, hard state, etc.). */
  def close: F[Unit] =
    writeMutex.lock.surround {
      for
        sealedSegs <- sealedSegmentsRef.get
        _ <- sealedSegs.traverse_ { case (_, seg) => F.delay(seg.close()) }
        active <- activeSegmentRef.get
        _ <- F.delay(active.close())
        _ <- F.delay(hardState.close())
      yield ()
    }

object MmapWalEngine:

  /** Format snapshot stem using 20 zero-padded digits (`Long.MaxValue` safe).
    */
  def formatSnapshotStem(lastIncludedIndex: Long): String =
    s"snapshot-${"%020d".format(lastIncludedIndex)}"

  /** Parse numeric `lastIncludedIndex` from both legacy 6-digit and 20-digit
    * snapshot filenames (`snapshot-<digits>.bin`).
    */
  def parseSnapshotIndex(path: JPath): Option[Long] =
    val name = path.getFileName.toString
    if name.startsWith("snapshot-") && name.endsWith(".bin") then
      name.stripPrefix("snapshot-").stripSuffix(".bin").toLongOption
    else None

  /** Open or initialize a WAL engine at the given directory. */
  def open[F[_]: Async](
      dataDir: JPath,
      segmentSize: Long = MmapSegment.DefaultSegmentSize
  ): F[MmapWalEngine[F]] =
    val F = Async[F]
    for
      _ <- F.delay { val _ = JFiles.createDirectories(dataDir.resolve("wal")) }
      _ <- F.delay {
        val _ = JFiles.createDirectories(dataDir.resolve("snapshots"))
      }
      mutex <- Mutex[F]
      hs <- F.delay(MmapHardState.open(dataDir.resolve("hard_state.bin")))
      existingSegments <- discoverSegments(dataDir.resolve("wal"))
      result <-
        if existingSegments.isEmpty then
          initFresh(dataDir, mutex, hs, segmentSize)
        else recoverFromWal(dataDir, mutex, hs, existingSegments, segmentSize)
    yield result

  private def parseSegmentId(path: JPath): Int =
    val name = path.getFileName.toString
    val digits = name.stripPrefix("segment-").stripSuffix(".wal")
    digits.toIntOption.getOrElse(1)

  private def discoverSegments[F[_]: Async](walDir: JPath): F[List[JPath]] =
    Async[F].delay {
      if JFiles.exists(walDir) then
        val listing = JFiles.list(walDir)
        try
          listing
            .iterator()
            .asScala
            .filter(_.toString.endsWith(".wal"))
            .toList
            .sortBy(parseSegmentId)
        finally listing.close()
      else List.empty
    }

  private def initFresh[F[_]: Async](
      dataDir: JPath,
      mutex: Mutex[F],
      hs: MmapHardState,
      segmentSize: Long
  ): F[MmapWalEngine[F]] =
    val F = Async[F]
    for
      segPath <- F.pure(dataDir.resolve("wal").resolve("segment-000001.wal"))
      activeSeg <- F.delay(MmapSegment.create(segPath, 1L, segmentSize))
      activeRef <- Ref.of[F, MmapSegment](activeSeg)
      sealedRef <- Ref.of[F, Vector[(Int, MmapSegment)]](Vector.empty)
      nextId <- Ref.of[F, Int](2)
      activeId <- Ref.of[F, Int](1)
      lastCkpt <- Ref.of[F, Int](0)
    yield new MmapWalEngine[F](
      dataDir,
      mutex,
      activeRef,
      sealedRef,
      new WalIndex,
      hs,
      nextId,
      activeId,
      lastCkpt,
      segmentSize
    )

  private def recoverFromWal[F[_]: Async](
      dataDir: JPath,
      mutex: Mutex[F],
      hs: MmapHardState,
      segmentPaths: List[JPath],
      segmentSize: Long
  ): F[MmapWalEngine[F]] =
    val F = Async[F]
    val checkpointPath = dataDir.resolve("index.ckpt")
    for
      idx <- F.delay {
        if JFiles.exists(checkpointPath) then
          val bytes = JFiles.readAllBytes(checkpointPath)
          WalIndex.deserializeChecked(bytes) match
            case Right(loaded) => loaded
            case Left(_)       =>
              val _ = JFiles.deleteIfExists(checkpointPath)
              new WalIndex
        else new WalIndex
      }
      segments <- segmentPaths.zipWithIndex.traverse { case (path, i) =>
        val segId = parseSegmentId(path)
        val isLast = i == segmentPaths.size - 1
        F.delay(MmapSegment.open(path, readOnly = !isLast))
          .map(seg => (segId, seg))
      }
      checkpointLastIdx = idx.lastIndex
      _ <- F.delay {
        segments.foreach { case (segId, seg) =>
          val segLastIdx = seg.firstIndex + seg.currentEntryCount - 1L
          if segLastIdx > checkpointLastIdx then
            seg.replay { (log, offset) =>
              if log.index > checkpointLastIdx then
                idx.put(log.index, segId, offset)
            }
        }
      }
      sealedSegs = segments.init.toVector
      activeEntry = segments.last
      activeRef <- Ref.of[F, MmapSegment](activeEntry._2)
      sealedRef <- Ref.of[F, Vector[(Int, MmapSegment)]](sealedSegs)
      nextId <- Ref.of[F, Int](activeEntry._1 + 1)
      activeId <- Ref.of[F, Int](activeEntry._1)
      lastCkpt <- Ref.of[F, Int](idx.size)
    yield new MmapWalEngine[F](
      dataDir,
      mutex,
      activeRef,
      sealedRef,
      idx,
      hs,
      nextId,
      activeId,
      lastCkpt,
      segmentSize
    )

  /** Resource-based lifecycle for use with `cats.effect.Resource`. */
  def resource[F[_]: Async](
      dataDir: JPath,
      segmentSize: Long = MmapSegment.DefaultSegmentSize
  ): Resource[F, MmapWalEngine[F]] =
    Resource.make(open[F](dataDir, segmentSize))(_.close)

  /** Factory implementation for the [[StorageEngineFactory]] SPI. */
  def factory[F[_]: Async]: StorageEngineFactory[F] =
    new StorageEngineFactory[F]:
      def open(dataDir: String): Resource[F, StorageEngine[F]] =
        MmapWalEngine.resource[F](JPath.of(dataDir)).widen[StorageEngine[F]]
