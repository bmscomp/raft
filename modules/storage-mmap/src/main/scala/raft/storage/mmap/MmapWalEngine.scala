package raft.storage.mmap

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, Path as JPath}
import scala.jdk.CollectionConverters.*
import fs2.{Stream, Chunk}
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
  * Uses the Foreign Function & Memory API (JDK 22+) for zero-copy I/O via
  * `MemorySegment`. Log entries are stored in segmented WAL files (default 64
  * MB each) with automatic rotation when a segment fills up, and an in-memory
  * [[WalIndex]] provides O(1) lookups by log index.
  */
class MmapWalEngine[F[_]: Async] private (
    dataDir: JPath,
    activeSegmentRef: Ref[F, MmapSegment],
    sealedSegmentsRef: Ref[F, Vector[(Int, MmapSegment)]],
    walIndex: Ref[F, WalIndex],
    hardState: MmapHardState,
    nextSegmentId: Ref[F, Int],
    activeSegmentId: Ref[F, Int],
    segmentSize: Long
) extends StorageEngine[F]:

  private val F = Async[F]
  private val fs2Files = Files.forAsync[F]
  private val walDir = dataDir.resolve("wal")
  private val snapshotDir = dataDir.resolve("snapshots")
  private val checkpointPath = dataDir.resolve("index.ckpt")

  def appendEntries(entries: Seq[Log]): F[Unit] =
    appendChunked(entries.toVector)

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
            _ <- walIndex.update { idx =>
              results.foreach { (logIdx, offset) =>
                idx.put(logIdx, segId, offset)
              }
              idx
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
      lastIdx <- walIndex.get.map(_.lastIndex)
      newSeg <- F.delay(MmapSegment.create(segPath, lastIdx + 1L, allocBytes))
      _ <- sealedSegmentsRef.update(_ :+ (oldId, oldSeg))
      _ <- activeSegmentRef.set(newSeg)
    yield newSeg

  def readEntry(index: LogIndex): F[Option[Log]] =
    walIndex.get.flatMap { idx =>
      val segId = idx.getSegmentId(index.value)
      if segId == -1 then F.pure(None)
      else
        val offset = idx.getFileOffset(index.value)
        findSegment(segId).flatMap {
          case Some(seg) =>
            F.delay(Some(seg.readEntryUnchecked(offset)))
          case None => F.pure(None)
        }
    }

  def readRange(start: LogIndex, end: LogIndex): F[Vector[Log]] =
    walIndex.get.flatMap { idx =>
      val locations = (start.value to end.value).flatMap { i =>
        val segId = idx.getSegmentId(i)
        if segId == -1 then None
        else Some((i, segId, idx.getFileOffset(i)))
      }
      if locations.isEmpty then F.pure(Vector.empty)
      else
        val bySegment = locations.groupBy(_._2)
        bySegment.toVector
          .flatTraverse { case (segId, locs) =>
            findSegment(segId).map {
              case Some(seg) =>
                locs.map { case (_, _, offset) =>
                  seg.readEntryUnchecked(offset)
                }.toVector
              case None => Vector.empty
            }
          }
          .map(_.sortBy(_.index))
    }

  def lastLogIndex: F[LogIndex] =
    walIndex.get.map(idx => LogIndex.unsafeFrom(idx.lastIndex))

  def termAt(index: LogIndex): F[Option[Term]] =
    readEntry(index).map(_.map(l => Term.unsafeFrom(l.term)))

  def logIsEmpty: F[Boolean] =
    walIndex.get.map(_.isEmpty)

  def truncateFrom(index: LogIndex): F[Unit] =
    for
      idx <- walIndex.get
      _ <- idx.get(index.value) match
        case Some(loc) =>
          for
            segOpt <- findSegment(loc.segmentId)
            _ <- segOpt match
              case Some(seg) =>
                val newCount = (1L until index.value).count { i =>
                  idx.get(i).exists(_.segmentId == loc.segmentId)
                }
                F.delay(seg.truncateAt(loc.fileOffset, newCount.toLong))
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
            _ <- walIndex.update { i =>
              i.truncateFrom(index.value)
              i
            }
            _ <- F.delay { val _ = JFiles.deleteIfExists(checkpointPath) }
          yield ()
        case None => F.unit
    yield ()

  def discardBefore(index: LogIndex): F[Unit] =
    val splitIndex = index.value
    for
      _ <- walIndex.update { idx =>
        idx.discardBefore(splitIndex)
        idx
      }
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
      lastActiveIndex = oldActive.firstIndex + oldActive.currentEntryCount - 1L
      _ <-
        if oldActive.currentEntryCount > 0 && lastActiveIndex < splitIndex then
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

  def clearLog: F[Unit] =
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
      newSeg <- F.delay(MmapSegment.create(segmentPath(newId), 1L, segmentSize))
      _ <- activeSegmentRef.set(newSeg)
      _ <- walIndex.update { idx =>
        idx.clear()
        idx
      }
    yield ()

  def readHardState: F[HardState] =
    F.delay(hardState.read)

  def writeHardState(state: HardState): F[Unit] =
    F.delay(hardState.write(state))

  def saveSnapshot(snapshot: Snapshot[F]): F[Unit] =
    val fileName = s"snapshot-${"%06d".format(snapshot.lastIncludedIndex)}"
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
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
      } >>
      F.delay {
        val listing = JFiles.list(snapshotDir)
        try
          listing
            .iterator()
            .asScala
            .filter(p => p.toString.endsWith(".bin") && !p.equals(snapshotPath))
            .foreach { p =>
              val _ = JFiles.deleteIfExists(p)
            }
        finally listing.close()
      }

  def loadSnapshot: F[Option[Snapshot[F]]] =
    F.delay(JFiles.exists(snapshotDir)).flatMap { exists =>
      if !exists then F.pure(None)
      else
        F.delay {
          val listing = JFiles.list(snapshotDir)
          try
            listing
              .iterator()
              .asScala
              .filter(_.toString.endsWith(".bin"))
              .toList
              .sortBy(_.getFileName.toString)
              .lastOption
          finally listing.close()
        }.flatMap {
          case Some(path) =>
            val nioPath = Path.fromNioPath(path)
            fs2Files
              .readAll(nioPath)
              .pull
              .unconsN(16, allowFewer = false)
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
                              val dataStream =
                                fs2Files.readAll(nioPath).drop(dataOffset)
                              fs2.Pull.output1(
                                Snapshot(
                                  lastIndex,
                                  lastTerm,
                                  dataStream,
                                  Some(config)
                                )
                              )
                            case None =>
                              fs2.Pull.raiseError[F](
                                new RuntimeException(
                                  "Truncated snapshot config"
                                )
                              )
                          }
                      else
                        val dataOffset = 16L + 4L
                        val dataStream =
                          fs2Files.readAll(nioPath).drop(dataOffset)
                        fs2.Pull.output1(
                          Snapshot(lastIndex, lastTerm, dataStream, None)
                        )
                    case None =>
                      fs2.Pull.raiseError[F](
                        new RuntimeException("Truncated snapshot config length")
                      )
                  }
                case None =>
                  fs2.Pull.raiseError[F](
                    new RuntimeException("Truncated snapshot metadata")
                  )
              }
              .stream
              .compile
              .last
          case None => F.pure(None)
        }
    }

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
    walIndex.get.flatMap { idx =>
      if idx.size % 1000 == 0 && idx.size > 0 then
        F.delay { val _ = JFiles.write(checkpointPath, idx.serialize) }
      else F.unit
    }

  private def serializeMetadata(snapshot: Snapshot[F]): Array[Byte] =
    val configBytes =
      snapshot.config.map(ClusterConfig.encode).getOrElse(Array.emptyByteArray)
    val buf = java.nio.ByteBuffer.allocate(
      16 + 4 + configBytes.length
    )
    buf.putLong(snapshot.lastIncludedIndex)
    buf.putLong(snapshot.lastIncludedTerm)
    buf.putInt(configBytes.length)
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
    for
      sealedSegs <- sealedSegmentsRef.get
      _ <- sealedSegs.traverse_ { case (_, seg) => F.delay(seg.close()) }
      active <- activeSegmentRef.get
      _ <- F.delay(active.close())
      _ <- F.delay(hardState.close())
    yield ()

object MmapWalEngine:

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
      hs <- F.delay(MmapHardState.open(dataDir.resolve("hard_state.bin")))
      existingSegments <- discoverSegments(dataDir.resolve("wal"))
      result <-
        if existingSegments.isEmpty then initFresh(dataDir, hs, segmentSize)
        else recoverFromWal(dataDir, hs, existingSegments, segmentSize)
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
      hs: MmapHardState,
      segmentSize: Long
  ): F[MmapWalEngine[F]] =
    val F = Async[F]
    for
      idx <- Ref.of[F, WalIndex](new WalIndex)
      segPath = dataDir.resolve("wal").resolve("segment-000001.wal")
      activeSeg <- F.delay(MmapSegment.create(segPath, 1L, segmentSize))
      activeRef <- Ref.of[F, MmapSegment](activeSeg)
      sealedRef <- Ref.of[F, Vector[(Int, MmapSegment)]](Vector.empty)
      nextId <- Ref.of[F, Int](2)
      activeId <- Ref.of[F, Int](1)
    yield new MmapWalEngine[F](
      dataDir,
      activeRef,
      sealedRef,
      idx,
      hs,
      nextId,
      activeId,
      segmentSize
    )

  private def recoverFromWal[F[_]: Async](
      dataDir: JPath,
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
          WalIndex.deserialize(bytes)
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
      idxRef <- Ref.of[F, WalIndex](idx)
      nextId <- Ref.of[F, Int](activeEntry._1 + 1)
      activeId <- Ref.of[F, Int](activeEntry._1)
    yield new MmapWalEngine[F](
      dataDir,
      activeRef,
      sealedRef,
      idxRef,
      hs,
      nextId,
      activeId,
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
