package raft.storage.mmap

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{
  Files as JFiles,
  Path as JPath,
  StandardCopyOption,
  StandardOpenOption
}
import java.util.zip.CRC32
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.{Pull, Stream}
import fs2.io.file.{Files, Path}

import raft.state.{
  ClusterConfig,
  Log,
  LogIndex,
  NodeId,
  NodeRole,
  RaftError,
  Snapshot,
  Term
}
import raft.storage.{HardState, StorageEngine, StorageEngineFactory}

/** Crash-safe, lease-protected off-heap FFM WAL & streaming snapshot engine.
  *
  *   - **Coherent immutable state (`EngineState`)**: active/sealed
  *     [[SegmentLease]]s and the pure 2-level paged [[WalIndexState]] live
  *     inside a single atomic `Ref[F, EngineState[F]]` (`0` `var`s, `0` `while`
  *     loops, `0` `synchronized` blocks).
  *   - **Lock-free leased reads**: `readEntry`, `readRange`, and `O(1)`
  *     zero-allocation `termAt` pin [[SegmentLease]]s without acquiring
  *     `writeMutex`, preventing FFM `Arena.close()` races during concurrent
  *     truncation or log compaction (**fixes ST1**).
  *   - **Torn-tail self-healing recovery**: uncommitted partial/torn records at
  *     the tail of the active `.wal` segment after an OS `SIGKILL` are cleanly
  *     truncated on startup while mid-log or committed bit-rot fails fast with
  *     [[CorruptedEntryException]] (**fixes ST2**).
  *   - **Streaming `"RSNP"` v2 checksummed snapshots + directory `fsync`**:
  *     snapshots stream directly between `fs2.Stream[F, Byte]` and
  *     `FileChannel` with header + payload CRC32 verification and durable
  *     `force(true)` on both the snapshot file and `snapshots/` directory
  *     (**fixes ST3**).
  */
class MmapWalEngine[F[_]: Async] private (
    val dataDir: JPath,
    writeMutex: Mutex[F],
    stateRef: Ref[F, MmapWalEngine.EngineState[F]],
    hardState: MmapHardState,
    segmentSize: Long
) extends StorageEngine[F]:
  import MmapWalEngine.*

  private val F = Async[F]
  private val fs2Files = Files.forAsync[F]
  private val walDir = dataDir.resolve("wal")
  private val snapshotDir = dataDir.resolve("snapshots")
  private val checkpointPath = dataDir.resolve("index.ckpt")
  private val checkpointTmpPath = dataDir.resolve("index.ckpt.tmp")

  def appendEntries(entries: Seq[Log]): F[Unit] =
    if entries.isEmpty then F.unit
    else writeMutex.lock.surround(appendChunked(entries.toVector))

  private def appendChunked(remaining: Vector[Log]): F[Unit] =
    if remaining.isEmpty then F.unit
    else
      stateRef.get.flatMap { st =>
        val seg = st.activeLease.segment
        val firstCost =
          (EntryOverhead + remaining.head.payload.length).toLong
        if seg.remaining < firstCost then
          val minRequired = HeaderSize.toLong + firstCost
          rotateSegment(st, math.max(segmentSize, minRequired)) >>
            appendChunked(remaining)
        else
          val takeCount = countFittingEntries(remaining, seg.remaining, 0)
          val (batch, rest) = remaining.splitAt(takeCount)
          val segId = st.activeLease.segmentId
          val batchStart = seg.currentWriteOffset
          for
            results <- F.delay {
              val appended = seg.appendBatch(batch)
              seg.forceRange(batchStart, seg.currentWriteOffset)
              appended
            }
            nextIndex = st.walIndex.putBatch(segId, results)
            nextSt = st.copy(walIndex = nextIndex)
            _ <- stateRef.set(nextSt)
            _ <- maybeCheckpointIndex(nextSt)
            _ <- appendChunked(rest)
          yield ()
      }

  @tailrec
  private def countFittingEntries(
      entries: Vector[Log],
      budget: Long,
      idx: Int
  ): Int =
    if idx >= entries.length then idx
    else
      val cost = (EntryOverhead + entries(idx).payload.length).toLong
      if cost > budget then idx
      else countFittingEntries(entries, budget - cost, idx + 1)

  private def rotateSegment(
      st: EngineState[F],
      allocBytes: Long
  ): F[Unit] =
    val oldLease = st.activeLease
    val newId = st.nextSegmentId
    val nextFirstIdx = st.walIndex.lastIndex + 1L
    for
      _ <- F.delay(oldLease.segment.force())
      newSeg <- F.delay(
        MmapSegment.create(segmentPath(newId), nextFirstIdx, allocBytes)
      )
      newLease <- SegmentLease.create[F](newId, newSeg)
      _ <- stateRef.set(
        st.copy(
          activeLease = newLease,
          sealedLeases = st.sealedLeases.updated(oldLease.segmentId, oldLease),
          sealedOrder = st.sealedOrder :+ oldLease.segmentId,
          nextSegmentId = newId + 1
        )
      )
    yield ()

  def readEntry(index: LogIndex): F[Option[Log]] =
    stateRef.get.flatMap { st =>
      val segId = st.walIndex.getSegmentId(index.value)
      if segId == -1 then F.pure(None)
      else
        val offset = st.walIndex.getFileOffset(index.value)
        st.findLease(segId) match
          case None        => F.pure(None)
          case Some(lease) =>
            lease.withReader(seg => F.delay(seg.readEntryUnchecked(offset)))
    }

  def readRange(start: LogIndex, end: LogIndex): F[Vector[Log]] =
    if end.value < start.value then F.pure(Vector.empty)
    else
      stateRef.get.flatMap { st =>
        val locations = st.walIndex.rangeLocations(start.value, end.value)
        if locations.isEmpty then F.pure(Vector.empty)
        else
          val grouped = groupBySegmentOrder(locations)
          grouped
            .flatTraverse { case (segId, offsets) =>
              st.findLease(segId) match
                case None        => F.pure(Vector.empty[Log])
                case Some(lease) =>
                  lease
                    .withReader { seg =>
                      F.delay(offsets.map(seg.readEntryUnchecked))
                    }
                    .map(_.getOrElse(Vector.empty[Log]))
            }
            .map(_.sortBy(_.index))
      }

  def lastLogIndex: F[LogIndex] =
    stateRef.get.map(st => LogIndex.unsafeFrom(st.walIndex.lastIndex))

  /** Zero-allocation `O(1)` term read directly from the mapped header bytes. */
  def termAt(index: LogIndex): F[Option[Term]] =
    stateRef.get.flatMap { st =>
      val segId = st.walIndex.getSegmentId(index.value)
      if segId == -1 then F.pure(None)
      else
        val offset = st.walIndex.getFileOffset(index.value)
        st.findLease(segId) match
          case None        => F.pure(None)
          case Some(lease) =>
            lease.withReader(seg =>
              F.delay(Term.unsafeFrom(seg.readTerm(offset)))
            )
    }

  def logIsEmpty: F[Boolean] =
    stateRef.get.map(_.walIndex.isEmpty)

  def truncateFrom(index: LogIndex): F[Unit] =
    writeMutex.lock.surround {
      stateRef.get.flatMap { st =>
        st.walIndex.get(index.value) match
          case None      => F.unit
          case Some(loc) =>
            val nextIdxState = st.walIndex.truncateFrom(index.value)
            if nextIdxState.isEmpty then
              val newId = st.nextSegmentId
              for
                newSeg <- F.delay(
                  MmapSegment
                    .create(segmentPath(newId), index.value, segmentSize)
                )
                newLease <- SegmentLease.create[F](newId, newSeg)
                _ <- stateRef.set(
                  EngineState(
                    activeLease = newLease,
                    sealedLeases = Map.empty,
                    sealedOrder = Vector.empty,
                    walIndex = WalIndexState.empty,
                    nextSegmentId = newId + 1,
                    lastCheckpointedCount = 0
                  )
                )
                _ <- st.sealedLeases.values.toList
                  .traverse_(_.retire(deleteFile = true))
                _ <- st.activeLease.retire(deleteFile = true)
                _ <- F.delay { val _ = JFiles.deleteIfExists(checkpointPath) }
              yield ()
            else if loc.segmentId == st.activeLease.segmentId then
              for
                _ <- F.delay {
                  val keepCount = st.activeLease.segment
                    .countEntriesBeforeOffset(loc.fileOffset)
                  st.activeLease.segment.truncateAt(loc.fileOffset, keepCount)
                }
                _ <- stateRef.set(
                  st.copy(
                    walIndex = nextIdxState,
                    lastCheckpointedCount = nextIdxState.size
                  )
                )
                _ <- F.delay(
                  writeCheckpointAtomicallySync(
                    checkpointTmpPath,
                    checkpointPath,
                    nextIdxState.serialize
                  )
                )
              yield ()
            else
              // Truncation crossed back into an earlier sealed segment:
              // 1. Publish updated state removing > loc.segmentId & promoting reopened RW segment
              val droppedSealedIds =
                st.sealedOrder.filter(_ > loc.segmentId)
              val keptSealedOrder =
                st.sealedOrder.filter(_ < loc.segmentId)
              val keptSealedMap =
                keptSealedOrder
                  .flatMap(id => st.sealedLeases.get(id).map(id -> _))
                  .toMap
              val targetLeaseOpt = st.sealedLeases.get(loc.segmentId)
              val oldActive = st.activeLease

              targetLeaseOpt match
                case None =>
                  stateRef.set(
                    st.copy(
                      walIndex = nextIdxState,
                      lastCheckpointedCount = nextIdxState.size
                    )
                  )
                case Some(targetSealed) =>
                  val keepCount = targetSealed.segment
                    .countEntriesBeforeOffset(loc.fileOffset)
                  val targetPath = targetSealed.segment.path
                  for
                    // Retire target RO lease first so all active readers drain before RW reopen
                    _ <- targetSealed.retire(deleteFile = false)
                    rwSeg <- F.delay {
                      val opened =
                        MmapSegment.open(targetPath, readOnly = false)
                      opened.truncateAt(loc.fileOffset, keepCount)
                      opened
                    }
                    rwLease <- SegmentLease.create[F](loc.segmentId, rwSeg)
                    _ <- stateRef.set(
                      st.copy(
                        activeLease = rwLease,
                        sealedLeases = keptSealedMap,
                        sealedOrder = keptSealedOrder,
                        walIndex = nextIdxState,
                        lastCheckpointedCount = nextIdxState.size
                      )
                    )
                    _ <- droppedSealedIds.traverse_ { sid =>
                      st.sealedLeases
                        .get(sid)
                        .traverse_(_.retire(deleteFile = true))
                    }
                    _ <- oldActive.retire(deleteFile = true)
                    _ <-
                      if nextIdxState.nonEmpty then
                        F.delay(
                          writeCheckpointAtomicallySync(
                            checkpointTmpPath,
                            checkpointPath,
                            nextIdxState.serialize
                          )
                        )
                      else
                        F.delay {
                          val _ = JFiles.deleteIfExists(checkpointPath)
                        }
                  yield ()
      }
    }

  def discardBefore(index: LogIndex): F[Unit] =
    writeMutex.lock.surround {
      val splitIndex = index.value
      stateRef.get.flatMap { st =>
        val nextIdxState = st.walIndex.discardBefore(splitIndex)
        val minKeptSegId =
          if nextIdxState.isEmpty then Int.MaxValue
          else nextIdxState.getSegmentId(nextIdxState.firstIndex)
        val (keptIds, obsoleteIds) =
          st.sealedOrder.partition(_ >= minKeptSegId)
        val keptMap =
          keptIds.flatMap(id => st.sealedLeases.get(id).map(id -> _)).toMap

        val oldActive = st.activeLease
        val oldSeg = oldActive.segment
        val replaceActive =
          oldSeg.currentEntryCount > 0L && nextIdxState.isEmpty

        for
          activeReplacement <-
            if replaceActive then
              val newId = st.nextSegmentId
              for
                newSeg <- F.delay(
                  MmapSegment
                    .create(segmentPath(newId), splitIndex, segmentSize)
                )
                newLease <- SegmentLease.create[F](newId, newSeg)
              yield (newLease, newId + 1, Some(oldActive))
            else F.pure((oldActive, st.nextSegmentId, None))

          (nextActive, nextId, retiredActiveOpt) = activeReplacement
          _ <- stateRef.set(
            EngineState(
              activeLease = nextActive,
              sealedLeases = keptMap,
              sealedOrder = keptIds,
              walIndex = nextIdxState,
              nextSegmentId = nextId,
              lastCheckpointedCount = nextIdxState.size
            )
          )
          _ <- obsoleteIds.traverse_ { sid =>
            st.sealedLeases.get(sid).traverse_(_.retire(deleteFile = true))
          }
          _ <- retiredActiveOpt.traverse_(_.retire(deleteFile = true))
          _ <-
            if nextIdxState.nonEmpty then
              F.delay(
                writeCheckpointAtomicallySync(
                  checkpointTmpPath,
                  checkpointPath,
                  nextIdxState.serialize
                )
              )
            else F.delay { val _ = JFiles.deleteIfExists(checkpointPath) }
        yield ()
      }
    }

  def clearLog: F[Unit] =
    writeMutex.lock.surround {
      for
        st <- stateRef.get
        newId = st.nextSegmentId
        newSeg <- F.delay(
          MmapSegment.create(segmentPath(newId), 1L, segmentSize)
        )
        newLease <- SegmentLease.create[F](newId, newSeg)
        _ <- stateRef.set(
          EngineState(
            activeLease = newLease,
            sealedLeases = Map.empty,
            sealedOrder = Vector.empty,
            walIndex = WalIndexState.empty,
            nextSegmentId = newId + 1,
            lastCheckpointedCount = 0
          )
        )
        _ <- st.sealedOrder.traverse_ { sid =>
          st.sealedLeases.get(sid).traverse_(_.retire(deleteFile = true))
        }
        _ <- st.activeLease.retire(deleteFile = true)
        _ <- F.delay {
          deleteStrayWalFilesExcept(walDir, newSeg.path)
          val _ = JFiles.deleteIfExists(checkpointPath)
        }
      yield ()
    }

  def readHardState: F[HardState] =
    writeMutex.lock.surround(F.delay(hardState.read))

  def writeHardState(state: HardState): F[Unit] =
    writeMutex.lock.surround(F.delay(hardState.write(state)))

  /** Stream `snapshot.data` directly to a `"RSNP"` v2 `.tmp` file with header
    * and payload CRC32s, `fsync` the file, atomically rename to `.bin`, `fsync`
    * `snapshotDir`, and prune superseded snapshots.
    */
  def saveSnapshot(snapshot: Snapshot[F]): F[Unit] =
    writeMutex.lock.surround {
      val fileName = formatSnapshotStem(snapshot.lastIncludedIndex)
      val snapshotPath = snapshotDir.resolve(s"$fileName.bin")
      val tmpPath = snapshotDir.resolve(s"$fileName.tmp")
      val configBytes =
        snapshot.config
          .map(ClusterConfig.encode)
          .getOrElse(Array.emptyByteArray)
      val headerSize = SnapshotV2FixedHeaderBytes + configBytes.length

      val writeAndSyncTmp: F[Unit] =
        Resource
          .fromAutoCloseable(
            F.blocking(
              FileChannel.open(
                tmpPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
              )
            )
          )
          .use { ch =>
            F.blocking(ch.position(headerSize.toLong)) *>
              snapshot.data.chunks
                .evalFold((0L, new CRC32())) { case ((written, crc), chunk) =>
                  F.blocking {
                    val bb = chunk.toByteBuffer
                    val dup = bb.duplicate()
                    crc.update(dup)
                    writeFully(ch, bb)
                    (written + chunk.size.toLong, crc)
                  }
                }
                .compile
                .lastOrError
                .flatMap { case (payloadBytes, dataCrc) =>
                  F.blocking {
                    val trailer = ByteBuffer.allocate(4)
                    val _ = trailer.putInt(dataCrc.getValue.toInt)
                    val _ = trailer.flip()
                    writeFully(ch, trailer)

                    val hdrBuf = buildV2SnapshotHeader(
                      lastIndex = snapshot.lastIncludedIndex,
                      lastTerm = snapshot.lastIncludedTerm,
                      payloadBytes = payloadBytes,
                      configBytes = configBytes
                    )
                    writeFullyAt(ch, hdrBuf, 0L)
                    ch.force(true)
                  }
                }
          }

      F.blocking { val _ = JFiles.createDirectories(snapshotDir) } *>
        writeAndSyncTmp.onError { case _ =>
          F.blocking { val _ = JFiles.deleteIfExists(tmpPath) }
        } *>
        F.blocking {
          val _ = JFiles.move(
            tmpPath,
            snapshotPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
          )
          fsyncDirectory(snapshotDir)
          pruneOtherSnapshots(snapshotDir, snapshotPath)
          fsyncDirectory(snapshotDir)
        }
    }

  /** Lazily load the highest-indexed snapshot without materializing its payload
    * in heap (`fs2.io.file.Files[F].readRange` + streaming CRC32 verification).
    */
  def loadSnapshot: F[Option[Snapshot[F]]] =
    writeMutex.lock.surround {
      F.blocking(findLatestSnapshotPath(snapshotDir)).flatMap {
        case None       => F.pure(None)
        case Some(path) =>
          F.blocking(parseSnapshotEnvelope(path)).flatMap {
            case Left(err)  => F.raiseError(err)
            case Right(env) =>
              val stream = buildSnapshotPayloadStream(path, env)
              F.pure(
                Some(
                  Snapshot[F](
                    lastIncludedIndex = env.lastIndex,
                    lastIncludedTerm = env.lastTerm,
                    data = stream,
                    config = env.config
                  )
                )
              )
          }
      }
    }

  /** List persisted snapshot metadata ordered by `lastIncludedIndex` ascending
    * using `O(1)` channel header reads (never reading full `.bin` payloads).
    */
  def listSnapshots: F[Vector[raft.spi.SnapshotMetadata]] =
    writeMutex.lock.surround {
      F.blocking {
        if !JFiles.exists(snapshotDir) then Vector.empty
        else
          val listing = JFiles.list(snapshotDir)
          try
            listing
              .iterator()
              .asScala
              .filter(_.getFileName.toString.endsWith(".bin"))
              .flatMap { p =>
                parseSnapshotIndex(p).flatMap { _ =>
                  parseSnapshotEnvelope(p).toOption.map { env =>
                    raft.spi.SnapshotMetadata(
                      env.lastIndex,
                      env.lastTerm,
                      env.payloadBytes
                    )
                  }
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
      F.blocking {
        if JFiles.exists(snapshotDir) then
          val listing = JFiles.list(snapshotDir)
          try
            listing
              .iterator()
              .asScala
              .filter(p => parseSnapshotIndex(p).contains(index))
              .foreach { p =>
                val _ = JFiles.deleteIfExists(p)
              }
          finally listing.close()
          fsyncDirectory(snapshotDir)
      }
    }

  private def buildSnapshotPayloadStream(
      path: JPath,
      env: SnapshotEnvelope
  ): Stream[F, Byte] =
    env.expectedDataCrc match
      case Some(expectedCrc) =>
        if env.payloadBytes == 0L then
          if expectedCrc == 0 then Stream.empty.covary[F]
          else
            Stream.raiseError[F](
              RaftError.StorageCorruption(
                f"Snapshot $path empty payload CRC32 mismatch: stored=0x$expectedCrc%08x, computed=0x00000000"
              )
            )
        else
          val rawStream = fs2Files.readRange(
            Path.fromNioPath(path),
            SnapshotStreamChunkSize,
            env.dataOffset,
            env.dataOffset + env.payloadBytes
          )
          verifySnapshotCrcPipe(
            rawStream,
            path,
            env.payloadBytes,
            expectedCrc
          )

      case None =>
        if env.payloadBytes <= 0L then Stream.empty.covary[F]
        else
          fs2Files.readRange(
            Path.fromNioPath(path),
            SnapshotStreamChunkSize,
            env.dataOffset,
            env.dataOffset + env.payloadBytes
          )

  private def verifySnapshotCrcPipe(
      src: Stream[F, Byte],
      path: JPath,
      expectedBytes: Long,
      expectedCrc: Int
  ): Stream[F, Byte] =
    def go(
        s: Stream[F, Byte],
        seenBytes: Long,
        crc: CRC32
    ): Pull[F, Byte, Unit] =
      s.pull.uncons.flatMap {
        case Some((chunk, rest)) =>
          val dup = chunk.toByteBuffer.duplicate()
          crc.update(dup)
          Pull.output(chunk) >> go(rest, seenBytes + chunk.size.toLong, crc)
        case None =>
          val computed = crc.getValue.toInt
          if seenBytes != expectedBytes then
            Pull.raiseError[F](
              RaftError.StorageCorruption(
                s"Truncated snapshot payload in $path: read $seenBytes bytes, expected $expectedBytes"
              )
            )
          else if computed != expectedCrc then
            Pull.raiseError[F](
              RaftError.StorageCorruption(
                f"Snapshot payload CRC32 mismatch in $path: stored=0x$expectedCrc%08x, computed=0x$computed%08x"
              )
            )
          else Pull.done
      }

    Stream.suspend(go(src, 0L, new CRC32()).stream)

  private def segmentPath(id: Int): JPath =
    walDir.resolve(s"segment-${"%06d".format(id)}.wal")

  private def maybeCheckpointIndex(st: EngineState[F]): F[Unit] =
    val cur = st.walIndex.size
    if cur > 0 && (cur - st.lastCheckpointedCount >= 1000) then
      F.blocking {
        val bytes = st.walIndex.serialize
        writeCheckpointAtomicallySync(checkpointTmpPath, checkpointPath, bytes)
      } *> stateRef.update(_.copy(lastCheckpointedCount = cur))
    else F.unit

  /** Close all active/sealed segment leases (awaiting any in-flight readers)
    * and close `MmapHardState`.
    */
  def close: F[Unit] =
    writeMutex.lock.surround {
      for
        st <- stateRef.get
        _ <- st.sealedOrder.traverse_ { sid =>
          st.sealedLeases.get(sid).traverse_(_.retire(deleteFile = false))
        }
        _ <- st.activeLease.retire(deleteFile = false)
        _ <- F.delay(hardState.close())
      yield ()
    }

object MmapWalEngine:
  private val SnapshotMagic: Int = 0x52534e50 // "RSNP"
  private val SnapshotVersionV2: Byte = 2
  // magic(4) + ver(1) + idx(8) + term(8) + payloadLen(8) + cfgLen(4) + hdrCrc(4)
  private val SnapshotV2FixedHeaderBytes: Int = 37
  private val SnapshotStreamChunkSize: Int = 64 * 1024

  private[mmap] final case class EngineState[F[_]](
      activeLease: SegmentLease[F],
      sealedLeases: Map[Int, SegmentLease[F]],
      sealedOrder: Vector[Int],
      walIndex: WalIndexState,
      nextSegmentId: Int,
      lastCheckpointedCount: Int
  ):
    def findLease(segId: Int): Option[SegmentLease[F]] =
      if activeLease.segmentId == segId then Some(activeLease)
      else sealedLeases.get(segId)

  private final case class SnapshotEnvelope(
      lastIndex: Long,
      lastTerm: Long,
      payloadBytes: Long,
      config: Option[ClusterConfig],
      dataOffset: Long,
      expectedDataCrc: Option[Int]
  )

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

  /** Open or initialize a WAL engine at `dataDir`. */
  def open[F[_]: Async](
      dataDir: JPath,
      segmentSize: Long = MmapSegment.DefaultSegmentSize
  ): F[MmapWalEngine[F]] =
    val F = Async[F]
    for
      _ <- F.delay {
        val _ = JFiles.createDirectories(dataDir.resolve("wal"))
        val _ = JFiles.createDirectories(dataDir.resolve("snapshots"))
      }
      mutex <- Mutex[F]
      hs <- F.delay(MmapHardState.open(dataDir.resolve("hard_state.bin")))
      result <- F
        .defer {
          discoverSegments(dataDir.resolve("wal")).flatMap { existingSegments =>
            if existingSegments.isEmpty then
              initFresh(dataDir, mutex, hs, segmentSize)
            else
              recoverFromWal(dataDir, mutex, hs, existingSegments, segmentSize)
          }
        }
        .onError { case _ => F.delay(hs.close()) }
    yield result

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
    val segPath = dataDir.resolve("wal").resolve("segment-000001.wal")
    for
      activeSeg <- F.delay(MmapSegment.create(segPath, 1L, segmentSize))
      activeLease <- SegmentLease.create[F](1, activeSeg)
      stRef <- Ref.of[F, EngineState[F]](
        EngineState(
          activeLease = activeLease,
          sealedLeases = Map.empty,
          sealedOrder = Vector.empty,
          walIndex = WalIndexState.empty,
          nextSegmentId = 2,
          lastCheckpointedCount = 0
        )
      )
    yield new MmapWalEngine[F](dataDir, mutex, stRef, hs, segmentSize)

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
      persistedHs <- F.delay(hs.read)
      minProtected = persistedHs.commitIndex
      ckptOpt <- F.delay {
        if JFiles.exists(checkpointPath) then
          val bytes = JFiles.readAllBytes(checkpointPath)
          WalIndexState.deserializeChecked(bytes) match
            case Right(loaded) => Some(loaded)
            case Left(_)       =>
              val _ = JFiles.deleteIfExists(checkpointPath)
              None
        else None
      }
      recovered <- F.delay {
        openSegmentsAtomically(segmentPaths, minProtected)
      }
      (segTriples, anyHealed) = recovered
      walLastIdx = segTriples
        .flatMap(_._3.lastOption.map(_._1.index))
        .lastOption
        .getOrElse(0L)
      usableCkpt = ckptOpt.filter { ckpt =>
        !anyHealed && ckpt.lastIndex <= walLastIdx
      }
      discardFloor = ckptOpt.map(_.firstIndex).getOrElse(1L)
      rebuiltIdx0 = buildRecoveredIndex(usableCkpt, segTriples)
      rebuiltIdx =
        if discardFloor > 1L then rebuiltIdx0.discardBefore(discardFloor)
        else rebuiltIdx0
      finalTriples <-
        if rebuiltIdx.isEmpty && segTriples.exists(_._3.nonEmpty) then
          val freshId = segTriples.last._1 + 1
          val freshPath =
            dataDir.resolve("wal").resolve(f"segment-$freshId%06d.wal")
          F.delay {
            val freshSeg =
              MmapSegment.create(freshPath, discardFloor, segmentSize)
            segTriples.foreach { case (_, oldSeg, _) =>
              oldSeg.close()
              val _ = JFiles.deleteIfExists(oldSeg.path)
            }
            val _ = JFiles.deleteIfExists(checkpointPath)
            Vector((freshId, freshSeg, Vector.empty[(Log, Long)]))
          }
        else F.pure(segTriples)
      _ <-
        if ckptOpt.isDefined && usableCkpt.isEmpty then
          if rebuiltIdx.nonEmpty then
            F.delay(
              writeCheckpointAtomicallySync(
                dataDir.resolve("index.ckpt.tmp"),
                checkpointPath,
                rebuiltIdx.serialize
              )
            )
          else F.delay { val _ = JFiles.deleteIfExists(checkpointPath) }
        else F.unit
      leases <- finalTriples.traverse { case (segId, seg, _) =>
        SegmentLease.create[F](segId, seg).map(segId -> _)
      }
      sealedPairs = leases.init
      activePair = leases.last
      stRef <- Ref.of[F, EngineState[F]](
        EngineState(
          activeLease = activePair._2,
          sealedLeases = sealedPairs.toMap,
          sealedOrder = sealedPairs.map(_._1),
          walIndex = rebuiltIdx,
          nextSegmentId = activePair._1 + 1,
          lastCheckpointedCount = rebuiltIdx.size
        )
      )
    yield new MmapWalEngine[F](dataDir, mutex, stRef, hs, segmentSize)

  private def openSegmentsAtomically(
      segmentPaths: List[JPath],
      minProtectedIndex: Long
  ): (Vector[(Int, MmapSegment, Vector[(Log, Long)])], Boolean) =
    val opened = Vector.newBuilder[(Int, MmapSegment, Vector[(Log, Long)])]
    val total = segmentPaths.size

    @tailrec
    def loop(
        rem: List[JPath],
        idx: Int,
        healedAny: Boolean
    ): (Vector[(Int, MmapSegment, Vector[(Log, Long)])], Boolean) =
      rem match
        case Nil          => (opened.result(), healedAny)
        case path :: rest =>
          val segId = parseSegmentId(path)
          val isLast = idx == total - 1
          val rec =
            MmapSegment.openForRecovery(path, isLast, minProtectedIndex)
          opened += ((segId, rec.segment, rec.entries))
          loop(rest, idx + 1, healedAny || rec.healedTornTail)

    try loop(segmentPaths, 0, healedAny = false)
    catch
      case t: Throwable =>
        opened.result().foreach { case (_, seg, _) =>
          try seg.close()
          catch case NonFatal(_) => ()
        }
        throw t

  private def buildRecoveredIndex(
      ckptOpt: Option[WalIndexState],
      segTriples: Vector[(Int, MmapSegment, Vector[(Log, Long)])]
  ): WalIndexState =
    val base = ckptOpt.getOrElse(WalIndexState.empty)
    val cutoff = base.lastIndex
    segTriples.foldLeft(base) { case (acc, (segId, _, entries)) =>
      val delta = entries.collect {
        case (log, off) if log.index > cutoff => (log.index, off)
      }
      if delta.isEmpty then acc else acc.putBatch(segId, delta)
    }

  private def groupBySegmentOrder(
      locations: Vector[(Long, Int, Long)]
  ): Vector[(Int, Vector[Long])] =
    @tailrec
    def loop(
        pos: Int,
        acc: Vector[(Int, Vector[Long])]
    ): Vector[(Int, Vector[Long])] =
      if pos >= locations.length then acc
      else
        val segId = locations(pos)._2
        val b = Vector.newBuilder[Long]

        @tailrec
        def collectSameSeg(i: Int): Int =
          if i < locations.length && locations(i)._2 == segId then
            b += locations(i)._3
            collectSameSeg(i + 1)
          else i

        val nextPos = collectSameSeg(pos)
        loop(nextPos, acc :+ (segId -> b.result()))

    loop(0, Vector.empty)

  private def deleteStrayWalFilesExcept(walDir: JPath, keep: JPath): Unit =
    if JFiles.exists(walDir) then
      val stream = JFiles.list(walDir)
      try
        stream
          .iterator()
          .asScala
          .filter(p => p.toString.endsWith(".wal") && !p.equals(keep))
          .foreach { p =>
            val _ = JFiles.deleteIfExists(p)
          }
      finally stream.close()

  private def pruneOtherSnapshots(
      snapshotDir: JPath,
      keepPath: JPath
  ): Unit =
    if JFiles.exists(snapshotDir) then
      val listing = JFiles.list(snapshotDir)
      try
        listing
          .iterator()
          .asScala
          .filter(p => p.toString.endsWith(".bin") && !p.equals(keepPath))
          .foreach { p =>
            val _ = JFiles.deleteIfExists(p)
          }
      finally listing.close()

  private def findLatestSnapshotPath(snapshotDir: JPath): Option[JPath] =
    if !JFiles.exists(snapshotDir) then None
    else
      val listing = JFiles.list(snapshotDir)
      try
        listing
          .iterator()
          .asScala
          .filter(_.getFileName.toString.endsWith(".bin"))
          .flatMap(p => parseSnapshotIndex(p).map(_ -> p))
          .toList
          .maxByOption(_._1)
          .map(_._2)
      finally listing.close()

  private def buildV2SnapshotHeader(
      lastIndex: Long,
      lastTerm: Long,
      payloadBytes: Long,
      configBytes: Array[Byte]
  ): ByteBuffer =
    val bodyLen = 33 + configBytes.length
    val buf = ByteBuffer.allocate(bodyLen + 4)
    val _ = buf.putInt(SnapshotMagic)
    val _ = buf.put(SnapshotVersionV2)
    val _ = buf.putLong(lastIndex)
    val _ = buf.putLong(lastTerm)
    val _ = buf.putLong(payloadBytes)
    val _ = buf.putInt(configBytes.length)
    val _ = buf.put(configBytes)
    val crc = new CRC32()
    crc.update(buf.array(), 0, bodyLen)
    val _ = buf.putInt(crc.getValue.toInt)
    val _ = buf.flip()
    buf

  private def parseSnapshotEnvelope(
      path: JPath
  ): Either[RaftError, SnapshotEnvelope] =
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    try
      val fileSize = channel.size()
      if fileSize < 16L then
        Left(
          RaftError.StorageCorruption(
            s"Truncated snapshot metadata in $path ($fileSize bytes)"
          )
        )
      else
        val prefixLen = math.min(fileSize, 37L).toInt
        val prefix = ByteBuffer.allocate(prefixLen)
        readFullyAt(channel, prefix, 0L)
        val _ = prefix.flip()
        val magic = prefix.getInt(0)
        if magic == SnapshotMagic then
          parseV2SnapshotEnvelope(path, channel, fileSize, prefix)
        else parseLegacyV1SnapshotEnvelope(path, channel, fileSize, prefix)
    finally channel.close()

  private def parseV2SnapshotEnvelope(
      path: JPath,
      channel: FileChannel,
      fileSize: Long,
      prefix: ByteBuffer
  ): Either[RaftError, SnapshotEnvelope] =
    if fileSize < (SnapshotV2FixedHeaderBytes + 4).toLong then
      Left(
        RaftError.StorageCorruption(
          s"Truncated RSNP v2 snapshot in $path ($fileSize bytes)"
        )
      )
    else
      val ver = prefix.get(4)
      if ver != SnapshotVersionV2 then
        Left(
          RaftError.StorageCorruption(
            s"Unsupported RSNP snapshot version $ver in $path"
          )
        )
      else
        val lastIndex = prefix.getLong(5)
        val lastTerm = prefix.getLong(13)
        val payloadBytes = prefix.getLong(21)
        val configLen = prefix.getInt(29)
        if payloadBytes < 0L || configLen < 0 then
          Left(
            RaftError.StorageCorruption(
              s"Negative snapshot length fields in $path (payload=$payloadBytes, configLen=$configLen)"
            )
          )
        else
          val headerLen = SnapshotV2FixedHeaderBytes.toLong + configLen.toLong
          val expectedFileSize = headerLen + payloadBytes + 4L
          if fileSize != expectedFileSize then
            Left(
              RaftError.StorageCorruption(
                s"Snapshot size mismatch in $path: actual=$fileSize, expected=$expectedFileSize"
              )
            )
          else
            val fullHdr = ByteBuffer.allocate(headerLen.toInt)
            readFullyAt(channel, fullHdr, 0L)
            val hdrArr = fullHdr.array()
            val bodyLen = 33 + configLen
            val crc = new CRC32()
            crc.update(hdrArr, 0, bodyLen)
            val computedHdrCrc = crc.getValue.toInt
            val storedHdrCrc = ByteBuffer.wrap(hdrArr, bodyLen, 4).getInt
            if computedHdrCrc != storedHdrCrc then
              Left(
                RaftError.StorageCorruption(
                  f"Snapshot header CRC32 mismatch in $path: stored=0x$storedHdrCrc%08x, computed=0x$computedHdrCrc%08x"
                )
              )
            else
              val cfgRes: Either[RaftError, Option[ClusterConfig]] =
                if configLen == 0 then Right(None)
                else
                  val cfgBytes = java.util.Arrays.copyOfRange(
                    hdrArr,
                    33,
                    33 + configLen
                  )
                  deserializeConfig(cfgBytes).map(Some(_))
              cfgRes.map { cfgOpt =>
                val trailer = ByteBuffer.allocate(4)
                readFullyAt(channel, trailer, fileSize - 4L)
                val _ = trailer.flip()
                val storedDataCrc = trailer.getInt()
                SnapshotEnvelope(
                  lastIndex = lastIndex,
                  lastTerm = lastTerm,
                  payloadBytes = payloadBytes,
                  config = cfgOpt,
                  dataOffset = headerLen,
                  expectedDataCrc = Some(storedDataCrc)
                )
              }

  private def parseLegacyV1SnapshotEnvelope(
      path: JPath,
      channel: FileChannel,
      fileSize: Long,
      prefix: ByteBuffer
  ): Either[RaftError, SnapshotEnvelope] =
    if fileSize < 20L then
      Left(
        RaftError.StorageCorruption(
          s"Truncated legacy snapshot config length in $path ($fileSize bytes)"
        )
      )
    else
      val lastIndex = prefix.getLong(0)
      val lastTerm = prefix.getLong(8)
      val configLen = prefix.getInt(16)
      if configLen < 0 || fileSize < 20L + configLen.toLong then
        Left(
          RaftError.StorageCorruption(
            s"Truncated legacy snapshot config in $path"
          )
        )
      else
        val cfgRes: Either[RaftError, Option[ClusterConfig]] =
          if configLen == 0 then Right(None)
          else
            val cfgBuf = ByteBuffer.allocate(configLen)
            readFullyAt(channel, cfgBuf, 20L)
            deserializeConfig(cfgBuf.array()).map(Some(_))
        val dataStart = 20L + configLen.toLong
        cfgRes.map { cfgOpt =>
          SnapshotEnvelope(
            lastIndex = lastIndex,
            lastTerm = lastTerm,
            payloadBytes = fileSize - dataStart,
            config = cfgOpt,
            dataOffset = dataStart,
            expectedDataCrc = None
          )
        }

  private def deserializeConfig(
      bytes: Array[Byte]
  ): Either[RaftError, ClusterConfig] =
    ClusterConfig.decode(bytes) match
      case Right(cfg) => Right(cfg)
      case Left(err)  =>
        try
          val str = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
          val members = str
            .split(",")
            .filter(_.nonEmpty)
            .map { entry =>
              val parts = entry.split(":")
              NodeId(parts(0)) -> NodeRole.fromOrdinal(parts(1).toInt)
            }
            .toMap
          if members.nonEmpty then Right(ClusterConfig(members))
          else Left(RaftError.StorageCorruption(err))
        catch case NonFatal(_) => Left(RaftError.StorageCorruption(err))

  @tailrec
  private def writeFully(ch: FileChannel, buf: ByteBuffer): Unit =
    if buf.hasRemaining then
      val _ = ch.write(buf)
      writeFully(ch, buf)

  @tailrec
  private def writeFullyAt(
      ch: FileChannel,
      buf: ByteBuffer,
      pos: Long
  ): Unit =
    if buf.hasRemaining then
      val written = ch.write(buf, pos)
      writeFullyAt(ch, buf, pos + math.max(0, written).toLong)

  @tailrec
  private def readFullyAt(
      ch: FileChannel,
      buf: ByteBuffer,
      pos: Long
  ): Unit =
    if buf.hasRemaining then
      val n = ch.read(buf, pos)
      if n > 0 then readFullyAt(ch, buf, pos + n.toLong)

  private def writeCheckpointAtomicallySync(
      tmpPath: JPath,
      destPath: JPath,
      bytes: Array[Byte]
  ): Unit =
    val ch = FileChannel.open(
      tmpPath,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
    try
      writeFully(ch, ByteBuffer.wrap(bytes))
      ch.force(true)
    finally ch.close()
    val _ = JFiles.move(
      tmpPath,
      destPath,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    )
    Option(destPath.getParent).foreach(fsyncDirectory)

  private def fsyncDirectory(dir: JPath): Unit =
    try
      val dirCh = FileChannel.open(dir, StandardOpenOption.READ)
      try dirCh.force(true)
      finally dirCh.close()
    catch case NonFatal(_) => ()
