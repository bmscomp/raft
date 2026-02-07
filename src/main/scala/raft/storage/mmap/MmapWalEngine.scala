package raft.storage.mmap

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

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
    dataDir: Path,
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

  def clearLog: IO[Unit] =
    for
      sealedSegs <- sealedSegmentsRef.getAndSet(Vector.empty)
      _ <- sealedSegs.traverse_ { case (_, seg) => IO(seg.close()) }
      active <- activeSegmentRef.get
      _ <- IO(active.close())
      _ <- IO {
        if Files.exists(walDir) then
          Files
            .list(walDir)
            .iterator()
            .asScala
            .filter(_.toString.endsWith(".wal"))
            .foreach(Files.delete)
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

  def saveSnapshot(snapshot: Snapshot): IO[Unit] =
    IO {
      Files.createDirectories(snapshotDir)
      val snapshotPath =
        snapshotDir.resolve(
          s"snapshot-${"%06d".format(snapshot.lastIncludedIndex)}.bin"
        )
      Files.write(snapshotPath, serializeSnapshot(snapshot))
    }

  def loadSnapshot: IO[Option[Snapshot]] =
    IO {
      if !Files.exists(snapshotDir) then None
      else
        val snapshotFiles = Files
          .list(snapshotDir)
          .iterator()
          .asScala
          .filter(_.toString.endsWith(".bin"))
          .toList
          .sortBy(_.getFileName.toString)
        snapshotFiles.lastOption.map { path =>
          val bytes = Files.readAllBytes(path)
          deserializeSnapshot(bytes)
        }
    }

  private def findSegment(segmentId: Int): IO[Option[MmapSegment]] =
    for
      currentActiveId <- activeSegmentId.get
      result <-
        if segmentId == currentActiveId then activeSegmentRef.get.map(Some(_))
        else sealedSegmentsRef.get.map(_.find(_._1 == segmentId).map(_._2))
    yield result

  private def segmentPath(id: Int): Path =
    walDir.resolve(s"segment-${"%06d".format(id)}.wal")

  private def maybeCheckpointIndex: IO[Unit] =
    walIndex.get.flatMap { idx =>
      if idx.size % 1000 == 0 && idx.size > 0 then
        IO(Files.write(checkpointPath, idx.serialize))
      else IO.unit
    }

  private def serializeSnapshot(snapshot: Snapshot): Array[Byte] =
    val configBytes =
      snapshot.config.map(serializeConfig).getOrElse(Array.emptyByteArray)
    val buf = java.nio.ByteBuffer.allocate(
      24 + snapshot.data.length + configBytes.length
    )
    buf.putLong(snapshot.lastIncludedIndex)
    buf.putLong(snapshot.lastIncludedTerm)
    buf.putInt(snapshot.data.length)
    buf.put(snapshot.data)
    buf.putInt(configBytes.length)
    if configBytes.nonEmpty then buf.put(configBytes)
    buf.array()

  private def deserializeSnapshot(bytes: Array[Byte]): Snapshot =
    val buf = java.nio.ByteBuffer.wrap(bytes)
    val lastIncludedIndex = buf.getLong()
    val lastIncludedTerm = buf.getLong()
    val dataLen = buf.getInt()
    val data = new Array[Byte](dataLen)
    buf.get(data)
    val configLen = buf.getInt()
    val config =
      if configLen > 0 then
        val configBytes = new Array[Byte](configLen)
        buf.get(configBytes)
        Some(deserializeConfig(configBytes))
      else None
    Snapshot(lastIncludedIndex, lastIncludedTerm, data, config)

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
  def open(dataDir: Path): IO[MmapWalEngine] =
    for
      _ <- IO(Files.createDirectories(dataDir.resolve("wal")))
      _ <- IO(Files.createDirectories(dataDir.resolve("snapshots")))
      hs <- IO(MmapHardState.open(dataDir.resolve("hard_state.bin")))
      existingSegments <- discoverSegments(dataDir.resolve("wal"))
      result <-
        if existingSegments.isEmpty then initFresh(dataDir, hs)
        else recoverFromWal(dataDir, hs, existingSegments)
    yield result

  private def discoverSegments(walDir: Path): IO[List[Path]] =
    IO {
      if Files.exists(walDir) then
        Files
          .list(walDir)
          .iterator()
          .asScala
          .filter(_.toString.endsWith(".wal"))
          .toList
          .sortBy(_.getFileName.toString)
      else List.empty
    }

  private def initFresh(dataDir: Path, hs: MmapHardState): IO[MmapWalEngine] =
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
      dataDir: Path,
      hs: MmapHardState,
      segmentPaths: List[Path]
  ): IO[MmapWalEngine] =
    val checkpointPath = dataDir.resolve("index.ckpt")
    for
      idx <- IO {
        if Files.exists(checkpointPath) then
          val bytes = Files.readAllBytes(checkpointPath)
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
  def resource(dataDir: Path): Resource[IO, MmapWalEngine] =
    Resource.make(open(dataDir))(_.close)

  /** Factory implementation for the [[StorageEngineFactory]] SPI. */
  def factory: StorageEngineFactory[IO] = new StorageEngineFactory[IO]:
    def open(dataDir: String): Resource[IO, StorageEngine[IO]] =
      MmapWalEngine.resource(Path.of(dataDir)).widen[StorageEngine[IO]]
