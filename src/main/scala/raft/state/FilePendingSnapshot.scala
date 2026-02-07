package raft.state

import cats.effect.Async
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.io.file.{Files, Path}

import java.nio.file.{Files as JFiles, Path as JPath, StandardOpenOption}

/** File-backed pending snapshot for incremental InstallSnapshot RPC (§7).
  *
  * Unlike [[PendingSnapshot]] which holds all chunks in JVM heap, this class
  * streams each chunk directly to a temporary file on disk. This prevents
  * OutOfMemoryErrors when installing snapshots of GB-scale state machines.
  *
  * Use [[FilePendingSnapshot.create]] to obtain an instance, then call
  * [[appendChunk]] for each received chunk, and finally [[toSnapshot]] to
  * produce a streaming [[Snapshot]] that reads from the temp file.
  *
  * @tparam F
  *   the effect type (requires `Async`)
  * @param lastIncludedIndex
  *   the last log index covered by this snapshot
  * @param lastIncludedTerm
  *   the term of the last log entry covered
  * @param offset
  *   the current byte offset (advanced after each chunk)
  * @param totalBytes
  *   total bytes written so far
  * @param tempFile
  *   the temporary file backing this snapshot data
  */
final class FilePendingSnapshot[F[_]: Async] private (
    val lastIncludedIndex: Long,
    val lastIncludedTerm: Long,
    val offset: Long,
    val totalBytes: Long,
    val tempFile: JPath
):
  private val F = Async[F]

  /** Append a new data chunk by writing it to the temp file.
    *
    * @param chunk
    *   the raw bytes to append
    * @return
    *   an updated `FilePendingSnapshot` with advanced offset and totalBytes
    */
  def appendChunk(chunk: Array[Byte]): F[FilePendingSnapshot[F]] =
    F.delay {
      JFiles.write(tempFile, chunk, StandardOpenOption.APPEND)
      new FilePendingSnapshot[F](
        lastIncludedIndex,
        lastIncludedTerm,
        offset + chunk.length,
        totalBytes + chunk.length,
        tempFile
      )
    }

  /** Assemble the file-backed data into a complete [[Snapshot]].
    *
    * The returned snapshot's `data` field is a lazy `fs2.Stream` that reads
    * from the temp file on demand — no heap buffering.
    *
    * @return
    *   a streaming snapshot backed by the temp file
    */
  def toSnapshot: Snapshot[F] =
    val fs2Files = Files.forAsync[F]
    Snapshot(
      lastIncludedIndex = lastIncludedIndex,
      lastIncludedTerm = lastIncludedTerm,
      data = fs2Files.readAll(Path.fromNioPath(tempFile))
    )

  /** Delete the temporary file.
    *
    * Call this when the snapshot transfer is complete or aborted to avoid
    * leaving temp files on disk.
    */
  def cleanup: F[Unit] =
    F.delay(JFiles.deleteIfExists(tempFile)).void

object FilePendingSnapshot:

  /** Create a new file-backed pending snapshot.
    *
    * Creates a temporary file in the system temp directory. The file is deleted
    * when [[FilePendingSnapshot.cleanup]] is called.
    *
    * @param lastIncludedIndex
    *   the last log index covered by this snapshot
    * @param lastIncludedTerm
    *   the term of the last log entry covered
    * @return
    *   a fresh `FilePendingSnapshot` ready to accept chunks
    */
  def create[F[_]: Async](
      lastIncludedIndex: Long,
      lastIncludedTerm: Long
  ): F[FilePendingSnapshot[F]] =
    Async[F].delay {
      val tmp = JFiles.createTempFile("raft-snap-", ".tmp")
      new FilePendingSnapshot[F](
        lastIncludedIndex,
        lastIncludedTerm,
        offset = 0L,
        totalBytes = 0L,
        tempFile = tmp
      )
    }

  /** Create a new file-backed pending snapshot in a specific directory.
    *
    * @param dir
    *   the directory to create the temp file in
    * @param lastIncludedIndex
    *   the last log index covered by this snapshot
    * @param lastIncludedTerm
    *   the term of the last log entry covered
    * @return
    *   a fresh `FilePendingSnapshot` ready to accept chunks
    */
  def createIn[F[_]: Async](
      dir: JPath,
      lastIncludedIndex: Long,
      lastIncludedTerm: Long
  ): F[FilePendingSnapshot[F]] =
    Async[F].delay {
      JFiles.createDirectories(dir)
      val tmp = JFiles.createTempFile(dir, "raft-snap-", ".tmp")
      new FilePendingSnapshot[F](
        lastIncludedIndex,
        lastIncludedTerm,
        offset = 0L,
        totalBytes = 0L,
        tempFile = tmp
      )
    }
