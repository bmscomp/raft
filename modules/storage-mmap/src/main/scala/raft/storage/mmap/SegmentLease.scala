package raft.storage.mmap

import java.nio.file.Files as JFiles

import cats.effect.{Async, Deferred, Ref}
import cats.effect.syntax.monadCancel.*
import cats.syntax.all.*

/** Reference-counted functional lease guarding an off-heap FFM [[MmapSegment]]
  * (`Arena.ofShared()`) against concurrent close/unmap races.
  *
  * Readers (`readEntry`, `readRange`, `termAt`) pin the segment in `O(1)` via
  * [[withReader]] without acquiring the engine write `Mutex`. When compaction,
  * truncation, log clear, or shutdown invokes [[retire]], new readers stop
  * entering immediately while physical `arena.close()` (and optional `.wal`
  * deletion) is deferred until the last in-flight reader finishes.
  */
private[mmap] final class SegmentLease[F[_]: Async] private (
    val segmentId: Int,
    val segment: MmapSegment,
    stateRef: Ref[F, SegmentLease.LeaseState],
    drained: Deferred[F, Unit]
):
  private val F = Async[F]

  /** Number of currently active concurrent readers holding a pin on this
    * segment.
    */
  def activeReaderCount: F[Int] =
    stateRef.get.map(_.activeReaders)

  /** True once [[retire]] has been requested on this segment lease. */
  def isRetiring: F[Boolean] =
    stateRef.get.map(_.retireRequested)

  /** Pin this segment for the duration of `use`. Returns `None` if the segment
    * has already been retired or closed.
    */
  def withReader[A](use: MmapSegment => F[A]): F[Option[A]] =
    F.uncancelable { poll =>
      stateRef
        .modify { st =>
          if st.retireRequested || st.closed then (st, false)
          else (st.copy(activeReaders = st.activeReaders + 1), true)
        }
        .flatMap { acquired =>
          if !acquired then F.pure(None)
          else
            poll(use(segment))
              .map(Some(_))
              .guarantee(releaseReader)
        }
    }

  /** Retire this segment from service, waiting for any in-flight readers to
    * finish before closing its FFM `Arena` (and deleting its file when
    * `deleteFile == true`).
    */
  def retire(deleteFile: Boolean): F[Unit] =
    F.uncancelable { poll =>
      stateRef
        .modify { st =>
          val mergedDelete = st.deleteOnClose || deleteFile
          if st.closed then (st, None)
          else if st.activeReaders == 0 then
            (
              st.copy(
                retireRequested = true,
                deleteOnClose = mergedDelete,
                closed = true
              ),
              Some(mergedDelete)
            )
          else
            (
              st.copy(
                retireRequested = true,
                deleteOnClose = mergedDelete
              ),
              None
            )
        }
        .flatMap {
          case Some(shouldDelete) =>
            closePhysically(shouldDelete).guarantee(drained.complete(()).void)
          case None =>
            poll(drained.get)
        }
    }

  private def releaseReader: F[Unit] =
    stateRef
      .modify { st =>
        val next = math.max(0, st.activeReaders - 1)
        if next == 0 && st.retireRequested && !st.closed then
          (
            st.copy(activeReaders = 0, closed = true),
            Some(st.deleteOnClose)
          )
        else (st.copy(activeReaders = next), None)
      }
      .flatMap {
        case Some(shouldDelete) =>
          closePhysically(shouldDelete).guarantee(drained.complete(()).void)
        case None =>
          F.unit
      }

  private def closePhysically(deleteFile: Boolean): F[Unit] =
    F.blocking {
      segment.close()
      if deleteFile then
        val _ = JFiles.deleteIfExists(segment.path)
    }

private[mmap] object SegmentLease:
  final case class LeaseState(
      activeReaders: Int,
      retireRequested: Boolean,
      deleteOnClose: Boolean,
      closed: Boolean
  )

  def create[F[_]: Async](
      segmentId: Int,
      segment: MmapSegment
  ): F[SegmentLease[F]] =
    for
      stRef <- Ref.of[F, LeaseState](
        LeaseState(
          activeReaders = 0,
          retireRequested = false,
          deleteOnClose = false,
          closed = false
        )
      )
      done <- Deferred[F, Unit]
    yield new SegmentLease[F](segmentId, segment, stRef, done)
