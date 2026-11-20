package raft.state

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files as JFiles, Path as JPath, StandardOpenOption}
import scala.annotation.tailrec

import cats.effect.Async
import fs2.Stream
import fs2.io.file.{Files, Path}

/** File-backed pending snapshot accumulator for incremental `InstallSnapshot`
  * RPCs (§7).
  *
  * Streams incoming chunks directly to a temporary file via `F.blocking` +
  * `FileChannel` (`0` heap accumulation) and lazily decodes `"RSNP"` v1/v2 wire
  * headers into `(Option[ClusterConfig], Stream[F, Byte])` via `readRange`.
  */
final class FilePendingSnapshot[F[_]: Async] private (
    val lastIncludedIndex: Long,
    val lastIncludedTerm: Long,
    val offset: Long,
    val totalBytes: Long,
    val tempFile: JPath
):
  private val F = Async[F]
  private val fs2Files = Files.forAsync[F]

  /** Append an immutable [[Bytes]] chunk directly to `tempFile` without copying
    * into an intermediate `Array[Byte]`.
    */
  def appendChunkBytes(chunk: Bytes): F[FilePendingSnapshot[F]] =
    F.blocking {
      if chunk.nonEmpty then
        val ch = FileChannel.open(
          tempFile,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.APPEND
        )
        try FilePendingSnapshot.writeFully(ch, chunk.asReadOnlyByteBuffer)
        finally ch.close()
      new FilePendingSnapshot[F](
        lastIncludedIndex,
        lastIncludedTerm,
        offset + chunk.length.toLong,
        totalBytes + chunk.length.toLong,
        tempFile
      )
    }

  /** Append a raw `Array[Byte]` chunk to `tempFile`. */
  def appendChunk(chunk: Array[Byte]): F[FilePendingSnapshot[F]] =
    appendChunkBytes(Bytes.copyFrom(chunk))

  /** Lazily decode optional `"RSNP"` v1/v2 `ClusterConfig` framing from the
    * front of `tempFile` and return a streaming `Snapshot.data` view over the
    * state-machine payload bytes (`0` contiguous heap allocation).
    */
  def decodeStreamingPayload: F[(Option[ClusterConfig], Stream[F, Byte])] =
    F.blocking {
      if totalBytes <= 0L || !JFiles.exists(tempFile) then
        (None, Stream.empty.covary[F])
      else
        val ch = FileChannel.open(tempFile, StandardOpenOption.READ)
        try
          val fileSize = ch.size()
          if fileSize <= 0L then (None, Stream.empty.covary[F])
          else if fileSize >= 10L then
            val hdr = ByteBuffer.allocate(10)
            FilePendingSnapshot.readFullyAt(ch, hdr, 0L)
            val _ = hdr.flip()
            val magic = hdr.getInt()
            val ver = hdr.get()
            val hasCfg = hdr.get() != 0
            val cfgLen = hdr.getInt()

            if magic == SnapshotMetadata.EnvelopeMagic &&
              ver == SnapshotMetadata.StreamingEnvelopeVersion &&
              cfgLen >= 0 && 10L + cfgLen.toLong <= fileSize
            then
              val cfgOpt =
                if hasCfg && cfgLen > 0 then
                  val cfgBuf = ByteBuffer.allocate(cfgLen)
                  FilePendingSnapshot.readFullyAt(ch, cfgBuf, 10L)
                  ClusterConfig
                    .decodeBytes(Bytes.unsafeWrap(cfgBuf.array()))
                    .toOption
                else None
              val startOff = 10L + cfgLen.toLong
              val stream =
                if startOff < fileSize then
                  fs2Files.readRange(
                    Path.fromNioPath(tempFile),
                    FilePendingSnapshot.ReadChunkSize,
                    startOff,
                    fileSize
                  )
                else Stream.empty.covary[F]
              (cfgOpt, stream)
            else if magic == SnapshotMetadata.EnvelopeMagic &&
              ver == SnapshotMetadata.EnvelopeVersion &&
              cfgLen >= 0 && 14L + cfgLen.toLong <= fileSize
            then
              val cfgOpt =
                if hasCfg && cfgLen > 0 then
                  val cfgBuf = ByteBuffer.allocate(cfgLen)
                  FilePendingSnapshot.readFullyAt(ch, cfgBuf, 10L)
                  ClusterConfig
                    .decodeBytes(Bytes.unsafeWrap(cfgBuf.array()))
                    .toOption
                else None
              val lenBuf = ByteBuffer.allocate(4)
              FilePendingSnapshot.readFullyAt(ch, lenBuf, 10L + cfgLen.toLong)
              val _ = lenBuf.flip()
              val dataLen = lenBuf.getInt()
              val dataStart = 14L + cfgLen.toLong
              if dataLen >= 0 && dataStart + dataLen.toLong <= fileSize then
                val stream =
                  if dataLen > 0 then
                    fs2Files.readRange(
                      Path.fromNioPath(tempFile),
                      FilePendingSnapshot.ReadChunkSize,
                      dataStart,
                      dataStart + dataLen.toLong
                    )
                  else Stream.empty.covary[F]
                (cfgOpt, stream)
              else rawFallback(ch, fileSize)
            else rawFallback(ch, fileSize)
          else rawFallback(ch, fileSize)
        finally ch.close()
    }

  private def rawFallback(
      ch: FileChannel,
      fileSize: Long
  ): (Option[ClusterConfig], Stream[F, Byte]) =
    if fileSize <= 4096L then
      val rawBuf = ByteBuffer.allocate(fileSize.toInt)
      FilePendingSnapshot.readFullyAt(ch, rawBuf, 0L)
      val rawBytes = Bytes.unsafeWrap(rawBuf.array())
      ClusterConfig.decodeBytes(rawBytes) match
        case Right(cfg) => (Some(cfg), Stream.empty.covary[F])
        case Left(_)    =>
          (
            None,
            fs2Files.readRange(
              Path.fromNioPath(tempFile),
              FilePendingSnapshot.ReadChunkSize,
              0L,
              fileSize
            )
          )
    else
      (
        None,
        fs2Files.readRange(
          Path.fromNioPath(tempFile),
          FilePendingSnapshot.ReadChunkSize,
          0L,
          fileSize
        )
      )

  /** Assemble the file-backed data into a lazy streaming [[Snapshot]]. */
  def toSnapshot: Snapshot[F] =
    Snapshot(
      lastIncludedIndex = lastIncludedIndex,
      lastIncludedTerm = lastIncludedTerm,
      data = fs2Files.readAll(Path.fromNioPath(tempFile))
    )

  /** Delete the temporary file (idempotent). */
  def cleanup: F[Unit] =
    F.blocking { val _ = JFiles.deleteIfExists(tempFile) }

object FilePendingSnapshot:
  private val ReadChunkSize: Int = 64 * 1024

  def create[F[_]: Async](
      lastIncludedIndex: Long,
      lastIncludedTerm: Long
  ): F[FilePendingSnapshot[F]] =
    Async[F].blocking {
      val tmp = JFiles.createTempFile("raft-snap-", ".tmp")
      new FilePendingSnapshot[F](
        lastIncludedIndex,
        lastIncludedTerm,
        offset = 0L,
        totalBytes = 0L,
        tempFile = tmp
      )
    }

  def createIn[F[_]: Async](
      dir: JPath,
      lastIncludedIndex: Long,
      lastIncludedTerm: Long
  ): F[FilePendingSnapshot[F]] =
    Async[F].blocking {
      val _ = JFiles.createDirectories(dir)
      val tmp = JFiles.createTempFile(dir, "raft-snap-", ".tmp")
      new FilePendingSnapshot[F](
        lastIncludedIndex,
        lastIncludedTerm,
        offset = 0L,
        totalBytes = 0L,
        tempFile = tmp
      )
    }

  @tailrec
  private def writeFully(ch: FileChannel, buf: ByteBuffer): Unit =
    if buf.hasRemaining then
      val _ = ch.write(buf)
      writeFully(ch, buf)

  @tailrec
  private def readFullyAt(
      ch: FileChannel,
      buf: ByteBuffer,
      pos: Long
  ): Unit =
    if buf.hasRemaining then
      val n = ch.read(buf, pos)
      if n > 0 then readFullyAt(ch, buf, pos + n.toLong)
