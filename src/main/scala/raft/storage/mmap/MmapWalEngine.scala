package raft.storage.mmap

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, Path as JPath}
import scala.jdk.CollectionConverters.*
import fs2.{Stream, Chunk}
import _root_.fs2.io.file.{Files, Path}

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
  * Uses the Foreign Function & Memory API (JDK 22+) for zero-copy I/O via
  * `MemorySegment`. Log entries are stored in segmented WAL files (default 64
  * MB each), and an in-memory [[WalIndex]] provides O(1) lookups by log index.
  *
  * '''Disk layout''':
  * {{{
  * <dataDir>/
  * ├── wal/
  * │   ├── segment-000001.wal
  * │   ├── segment-000002.wal
  * │   └── segment-000003.wal  (active)
  * ├── hard_state.bin
  * ├── index.ckpt
  * └── snapshots/
  *     └── snapshot-042000.bin
  * }}}
  *
  * @see
  *   [[MmapSegment]] for the segment binary format
  * @see
  *   [[raft.storage.StorageEngine]] for the pluggable SPI this implements
  */
class MmapWalEngine private (
    dataDir: JPath,
    activeSegmentRef: Ref[IO, MmapSegment],
    sealedSegmentsRef: Ref[IO, Vector[(Int, MmapSegment)]],
    walIndex: Ref[IO, WalIndex],
    hardState: MmapHardState,
    nextSegmentId: Ref[IO, Int],
    activeSegmentId: Ref[IO, Int]
) extends StorageEngine[IO]:

  private val walDir = dataDir.resolve("wal")
  private val snapshotDir = dataDir.resolve("snapshots")
  private val checkpointPath = dataDir.resolve("index.ckpt")

  def appendEntries(entries: Seq[Log]): IO[Unit] =
    if entries.isEmpty then IO.unit
    else
      for
        seg <- activeSegmentRef.get
        segId <- activeSegmentId.get
        batchStart = seg.currentWriteOffset
        results <- IO(seg.appendBatch(entries))
        _ <- walIndex.update { idx =>
          results.foreach { (logIdx, offset) => idx.put(logIdx, segId, offset) }
          idx
        }
        _ <- IO(seg.forceRange(batchStart, seg.currentWriteOffset))
        _ <- maybeCheckpointIndex
      yield ()

  private def rotateSegment: IO[MmapSegment] =
    for
      oldSeg <- activeSegmentRef.get
      _ <- IO(oldSeg.force())
      newId <- nextSegmentId.getAndUpdate(_ + 1)
      oldId <- activeSegmentId.getAndSet(newId)
      segPath = segmentPath(newId)
      lastIdx <- walIndex.get.map(_.lastIndex)
      newSeg <- IO(MmapSegment.create(segPath, lastIdx + 1))
      _ <- sealedSegmentsRef.update(_ :+ (oldId, oldSeg))
      _ <- activeSegmentRef.set(newSeg)
    yield newSeg

  def readEntry(index: LogIndex): IO[Option[Log]] =
    walIndex.get.flatMap { idx =>
      idx.get(index.value) match
        case Some(loc) =>
          findSegment(loc.segmentId).flatMap {
            case Some(seg) => IO(Some(seg.readEntryUnchecked(loc.fileOffset)))
            case None      => IO.pure(None)
          }
        case None => IO.pure(None)
    }

  def readRange(start: LogIndex, end: LogIndex): IO[Vector[Log]] =
    walIndex.get.flatMap { idx =>
      val locations = (start.value to end.value).flatMap { i =>
        idx.get(i).map(loc => (i, loc))
      }
      if locations.isEmpty then IO.pure(Vector.empty)
      else
        val bySegment = locations.groupBy(_._2.segmentId)
        bySegment.toVector
          .flatTraverse { case (segId, locs) =>
            findSegment(segId).map {
              case Some(seg) =>
                locs.map { case (_, loc) =>
                  seg.readEntryUnchecked(loc.fileOffset)
                }.toVector
              case None => Vector.empty
            }
          }
          .map(_.sortBy(_.index))
    }

  def lastLogIndex: IO[LogIndex] =
    walIndex.get.map(idx => LogIndex.unsafeFrom(idx.lastIndex))

  def termAt(index: LogIndex): IO[Option[Term]] =
    readEntry(index).map(_.map(l => Term.unsafeFrom(l.term)))

  def logIsEmpty: IO[Boolean] =
    walIndex.get.map(_.isEmpty)

  def truncateFrom(index: LogIndex): IO[Unit] =
    for
      idx <- walIndex.get
      _ <- idx.get(index.value) match
        case Some(loc) =>
          for
            segOpt <- findSegment(loc.segmentId)
            _ <- segOpt match
              case Some(seg) =>
                // Count entries in this segment before the truncation point
                val newCount = (1L until index.value).count { i =>
                  idx.get(i).exists(_.segmentId == loc.segmentId)
                }
                IO(seg.truncateAt(loc.fileOffset, newCount.toLong))
              case None => IO.unit
            _ <- sealedSegmentsRef.update(_.filter(_._1 <= loc.segmentId))
            _ <- walIndex.update { i =>
              i.truncateFrom(index.value)
              i
            }
          yield ()
        case None => IO.unit
    yield ()

  def discardBefore(index: LogIndex): IO[Unit] =
    val splitIndex = index.value
    for
      // 1. Update index to remove old entries from memory
      _ <- walIndex.update { idx =>
        idx.discardBefore(splitIndex)
        idx
      }

      // 2. Identify and remove obsolete sealed segments
      obsoleteSealed <- sealedSegmentsRef.modify { segments =>
        val (kept, obsolete) = segments.partition { case (_, seg) =>
          // Keep segment if it contains any entry >= splitIndex
          // Last index roughly calculated as first + count - 1.
          // If count=0, lastIndex < firstIndex, which is fine (handled as keep/discard depending on logic).
          // However, sealed segments shouldn't be empty.
          val lastIndex = seg.firstIndex + seg.currentEntryCount - 1
          lastIndex >= splitIndex
        }
        (kept, obsolete)
      }

      // 3. Delete obsolete sealed segments from disk
      _ <- obsoleteSealed.traverse_ { case (_, seg) =>
        IO(seg.close()) >> IO(JFiles.deleteIfExists(seg.path))
      }

      // 4. Handle active segment if fully obsolete
      oldActive <- activeSegmentRef.get
      // If active has entries and they are all < splitIndex, rotate (discard) it.
      // We check count > 0 to avoid rotating an empty segment we just created.
      lastActiveIndex = oldActive.firstIndex + oldActive.currentEntryCount - 1
      _ <-
        if oldActive.currentEntryCount > 0 && lastActiveIndex < splitIndex then
          for
            _ <- IO(oldActive.close())
            _ <- IO(JFiles.deleteIfExists(oldActive.path))
            newId <- nextSegmentId.getAndUpdate(_ + 1)
            _ <- activeSegmentId.set(newId)
            // Create new active segment starting at splitIndex (aligned with snapshot)
            newSeg <- IO(MmapSegment.create(segmentPath(newId), splitIndex))
            _ <- activeSegmentRef.set(newSeg)
          yield ()
        else IO.unit
    yield ()

  def clearLog: IO[Unit] =
    for
      sealedSegs <- sealedSegmentsRef.getAndSet(Vector.empty)
      _ <- sealedSegs.traverse_ { case (_, seg) => IO(seg.close()) }
      active <- activeSegmentRef.get
      _ <- IO(active.close())
      _ <- IO {
        if JFiles.exists(walDir) then
          JFiles
            .list(walDir)
            .iterator()
            .asScala
            .filter(_.toString.endsWith(".wal"))
            .foreach(JFiles.delete)
      }
      newId <- nextSegmentId.getAndUpdate(_ + 1)
      newSeg <- IO(MmapSegment.create(segmentPath(newId), 1L))
      _ <- activeSegmentRef.set(newSeg)
      _ <- walIndex.update { idx =>
        idx.clear()
        idx
      }
    yield ()

  def readHardState: IO[HardState] =
    IO(hardState.read)

  def writeHardState(state: HardState): IO[Unit] =
    IO(hardState.write(state))

  def saveSnapshot(snapshot: Snapshot[IO]): IO[Unit] =
    val snapshotPath = snapshotDir.resolve(
      s"snapshot-${"%06d".format(snapshot.lastIncludedIndex)}.bin"
    )
    val metadata = Chunk.array(serializeMetadata(snapshot))
    val stream = Stream.chunk(metadata) ++ snapshot.data

    IO(JFiles.createDirectories(snapshotDir)) >>
      stream
        .through(Files[IO].writeAll(Path.fromNioPath(snapshotPath)))
        .compile
        .drain

  def loadSnapshot: IO[Option[Snapshot[IO]]] =
    IO(JFiles.exists(snapshotDir)).flatMap { exists =>
      if !exists then IO.pure(None)
      else
        IO {
          JFiles
            .list(snapshotDir)
            .iterator()
            .asScala
            .filter(_.toString.endsWith(".bin"))
            .toList
            .sortBy(_.getFileName.toString)
            .lastOption
        }.flatMap {
          case Some(path) =>
            val nioPath = Path.fromNioPath(path)
            Files[IO]
              .readAll(nioPath)
              .pull
              .unconsN(16, allowFewer = false) // Index(8) + Term(8)
              .flatMap {
                case Some((metaChunk, rest)) =>
                  val buf = metaChunk.toByteBuffer
                  val lastIndex = buf.getLong
                  val lastTerm = buf.getLong

                  rest.pull.unconsN(4, allowFewer = false).flatMap {
                    case Some((lenChunk, rest2)) =>
                      val configLen = lenChunk.toByteBuffer.getInt
                      if configLen > 0 then
                        rest2.pull
                          .unconsN(configLen, allowFewer = false)
                          .flatMap {
                            case Some((configChunk, _)) =>
                              val config =
                                deserializeConfig(configChunk.toArray)
                              val dataOffset = 16L + 4L + configLen
                              // Create a fresh stream for data that opens the file when run
                              val dataStream =
                                Files[IO].readAll(nioPath).drop(dataOffset)
                              fs2.Pull.output1(
                                Snapshot(
                                  lastIndex,
                                  lastTerm,
                                  dataStream,
                                  Some(config)
                                )
                              )
                            case None =>
                              fs2.Pull.raiseError[IO](
                                new RuntimeException(
                                  "Truncated snapshot config"
                                )
                              )
                          }
                      else
                        val dataOffset = 16L + 4L
                        val dataStream =
                          Files[IO].readAll(nioPath).drop(dataOffset)
                        fs2.Pull.output1(
                          Snapshot(lastIndex, lastTerm, dataStream, None)
                        )
                    case None =>
                      fs2.Pull.raiseError[IO](
                        new RuntimeException("Truncated snapshot config length")
                      )
                  }
                case None =>
                  fs2.Pull.raiseError[IO](
                    new RuntimeException("Truncated snapshot metadata")
                  )
              }
              .stream
              .compile
              .last
          case None => IO.pure(None)
        }
    }

  private def findSegment(segmentId: Int): IO[Option[MmapSegment]] =
    for
      currentActiveId <- activeSegmentId.get
      result <-
        if segmentId == currentActiveId then activeSegmentRef.get.map(Some(_))
        else sealedSegmentsRef.get.map(_.find(_._1 == segmentId).map(_._2))
    yield result

  private def segmentPath(id: Int): JPath =
    walDir.resolve(s"segment-${"%06d".format(id)}.wal")

  private def maybeCheckpointIndex: IO[Unit] =
    walIndex.get.flatMap { idx =>
      if idx.size % 1000 == 0 && idx.size > 0 then
        IO(JFiles.write(checkpointPath, idx.serialize))
      else IO.unit
    }

  private def serializeMetadata(snapshot: Snapshot[IO]): Array[Byte] =
    val configBytes =
      snapshot.config.map(serializeConfig).getOrElse(Array.emptyByteArray)
    val buf = java.nio.ByteBuffer.allocate(
      16 + 4 + configBytes.length
    )
    buf.putLong(snapshot.lastIncludedIndex)
    buf.putLong(snapshot.lastIncludedTerm)
    buf.putInt(configBytes.length)
    if configBytes.nonEmpty then buf.put(configBytes)
    buf.array()

  private def serializeConfig(config: ClusterConfig): Array[Byte] =
    // Format: "nodeId:role,nodeId:role,..."
    val str = config.members
      .map { case (nodeId, role) =>
        s"${nodeId.value}:${role.ordinal}"
      }
      .mkString(",")
    str.getBytes(java.nio.charset.StandardCharsets.UTF_8)

  private def deserializeConfig(bytes: Array[Byte]): ClusterConfig =
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

  /** Close all resources (segments, hard state, etc.). */
  def close: IO[Unit] =
    for
      sealedSegs <- sealedSegmentsRef.get
      _ <- sealedSegs.traverse_ { case (_, seg) => IO(seg.close()) }
      active <- activeSegmentRef.get
      _ <- IO(active.close())
      _ <- IO(hardState.close())
    yield ()

object MmapWalEngine:

  /** Open or initialize a WAL engine at the given directory.
    *
    * On first run, creates the directory structure and initial segment. On
    * subsequent runs, replays the WAL (or loads a checkpoint) to rebuild the
    * in-memory index.
    *
    * @param dataDir
    *   root directory for all persistent data
    * @return
    *   a fully initialized storage engine
    */
  def open(dataDir: JPath): IO[MmapWalEngine] =
    for
      _ <- IO(JFiles.createDirectories(dataDir.resolve("wal")))
      _ <- IO(JFiles.createDirectories(dataDir.resolve("snapshots")))
      hs <- IO(MmapHardState.open(dataDir.resolve("hard_state.bin")))
      existingSegments <- discoverSegments(dataDir.resolve("wal"))
      result <-
        if existingSegments.isEmpty then initFresh(dataDir, hs)
        else recoverFromWal(dataDir, hs, existingSegments)
    yield result

  private def discoverSegments(walDir: JPath): IO[List[JPath]] =
    IO {
      if JFiles.exists(walDir) then
        JFiles
          .list(walDir)
          .iterator()
          .asScala
          .filter(_.toString.endsWith(".wal"))
          .toList
          .sortBy(_.getFileName.toString)
      else List.empty
    }

  private def initFresh(dataDir: JPath, hs: MmapHardState): IO[MmapWalEngine] =
    for
      idx <- Ref.of[IO, WalIndex](new WalIndex)
      segPath = dataDir.resolve("wal").resolve("segment-000001.wal")
      activeSeg <- IO(MmapSegment.create(segPath, 1L))
      activeRef <- Ref.of[IO, MmapSegment](activeSeg)
      sealedRef <- Ref.of[IO, Vector[(Int, MmapSegment)]](Vector.empty)
      nextId <- Ref.of[IO, Int](2)
      activeId <- Ref.of[IO, Int](1)
    yield new MmapWalEngine(
      dataDir,
      activeRef,
      sealedRef,
      idx,
      hs,
      nextId,
      activeId
    )

  private def recoverFromWal(
      dataDir: JPath,
      hs: MmapHardState,
      segmentPaths: List[JPath]
  ): IO[MmapWalEngine] =
    val checkpointPath = dataDir.resolve("index.ckpt")
    for
      idx <- IO {
        if JFiles.exists(checkpointPath) then
          val bytes = JFiles.readAllBytes(checkpointPath)
          WalIndex.deserialize(bytes)
        else new WalIndex
      }
      segments <- segmentPaths.zipWithIndex.traverse { case (path, i) =>
        val segId = i + 1
        val isLast = i == segmentPaths.size - 1
        IO(MmapSegment.open(path, readOnly = !isLast)).map(seg => (segId, seg))
      }
      checkpointLastIdx = idx.lastIndex
      _ <- IO {
        segments.foreach { case (segId, seg) =>
          // Skip segments whose entries are fully covered by the checkpoint
          val segLastIdx = seg.firstIndex + seg.currentEntryCount - 1
          if segLastIdx > checkpointLastIdx then
            seg.replay { (log, offset) =>
              if log.index > checkpointLastIdx then
                idx.put(log.index, segId, offset)
            }
        }
      }
      sealedSegs = segments.init.toVector
      activeEntry = segments.last
      activeRef <- Ref.of[IO, MmapSegment](activeEntry._2)
      sealedRef <- Ref.of[IO, Vector[(Int, MmapSegment)]](sealedSegs)
      idxRef <- Ref.of[IO, WalIndex](idx)
      nextId <- Ref.of[IO, Int](activeEntry._1 + 1)
      activeId <- Ref.of[IO, Int](activeEntry._1)
    yield new MmapWalEngine(
      dataDir,
      activeRef,
      sealedRef,
      idxRef,
      hs,
      nextId,
      activeId
    )

  /** Resource-based lifecycle for use with `cats.effect.Resource`. */
  def resource(dataDir: JPath): Resource[IO, MmapWalEngine] =
    Resource.make(open(dataDir))(_.close)

  /** Factory implementation for the [[StorageEngineFactory]] SPI. */
  def factory: StorageEngineFactory[IO] = new StorageEngineFactory[IO]:
    def open(dataDir: String): Resource[IO, StorageEngine[IO]] =
      MmapWalEngine.resource(JPath.of(dataDir)).widen[StorageEngine[IO]]
