package raft.spi

import cats.Applicative
import cats.syntax.all.*

/** Pluggable compaction algorithm for deciding when and where to snapshot.
  *
  * Implementations define the ''compaction strategy'' — the rules that govern
  * when the replicated log is eligible for compaction and which index to
  * snapshot at. This trait follows the library's SPI architecture, allowing
  * users to provide domain-specific compaction behaviors.
  *
  * The built-in algorithms cover common use cases:
  *   - [[CompactionPolicy.threshold]] — compact after a fixed number of entries
  *   - [[CompactionPolicy.sizeBased]] — compact when estimated log size exceeds
  *     a limit
  *   - [[CompactionPolicy.timed]] — compact based on elapsed time (via a
  *     monotonic counter proxy)
  *
  * Users can implement this trait for custom strategies, for example:
  *   - Rate-adaptive compaction (compact faster under high write load)
  *   - Off-peak compaction (only during maintenance windows)
  *   - Tiered compaction (different thresholds per entry type)
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  */
trait CompactionPolicy[F[_]]:

  /** Determine whether compaction should be triggered.
    *
    * @param lastApplied
    *   the index of the last entry applied to the state machine
    * @param lastSnapshotIndex
    *   the `lastIncludedIndex` of the most recent snapshot (0 if none)
    * @param logSize
    *   the current number of entries in the log
    * @return
    *   `true` if a snapshot should be taken now
    */
  def shouldCompact(
      lastApplied: Long,
      lastSnapshotIndex: Long,
      logSize: Long
  ): F[Boolean]

  /** Compute the index up to which the log can be compacted.
    *
    * The returned index becomes the `lastIncludedIndex` of the snapshot. All
    * log entries up to this index can be discarded after the snapshot is
    * persisted.
    *
    * @param lastApplied
    *   the index of the last entry applied to the state machine
    * @return
    *   the safe compaction index (must be ≤ `lastApplied`)
    */
  def compactionIndex(lastApplied: Long): F[Long]

object CompactionPolicy:

  /** No-op policy that never triggers compaction. */
  def disabled[F[_]: Applicative]: CompactionPolicy[F] =
    new CompactionPolicy[F]:
      def shouldCompact(
          lastApplied: Long,
          lastSnapshotIndex: Long,
          logSize: Long
      ): F[Boolean] = false.pure[F]

      def compactionIndex(lastApplied: Long): F[Long] = lastApplied.pure[F]

  /** Compact after `minEntries` new entries have accumulated since the last
    * snapshot.
    *
    * @param minEntries
    *   the minimum number of entries between snapshots before compaction
    *   triggers
    */
  def threshold[F[_]: Applicative](minEntries: Long): CompactionPolicy[F] =
    new CompactionPolicy[F]:
      def shouldCompact(
          lastApplied: Long,
          lastSnapshotIndex: Long,
          logSize: Long
      ): F[Boolean] =
        ((lastApplied - lastSnapshotIndex) >= minEntries).pure[F]

      def compactionIndex(lastApplied: Long): F[Long] = lastApplied.pure[F]

  /** Compact when the log exceeds `maxEntries` total entries.
    *
    * Unlike [[threshold]], which counts entries ''since the last snapshot'',
    * this policy monitors the ''absolute'' log size. Useful when the log
    * includes a mix of large and small entries and you want a hard cap.
    *
    * @param maxEntries
    *   the maximum number of log entries before compaction triggers
    */
  def sizeBased[F[_]: Applicative](maxEntries: Long): CompactionPolicy[F] =
    new CompactionPolicy[F]:
      def shouldCompact(
          lastApplied: Long,
          lastSnapshotIndex: Long,
          logSize: Long
      ): F[Boolean] =
        (logSize >= maxEntries).pure[F]

      def compactionIndex(lastApplied: Long): F[Long] = lastApplied.pure[F]

  /** Compact every `interval` applied entries — a simple time-proxy strategy.
    *
    * Uses the number of applied entries as a monotonic proxy for elapsed time,
    * avoiding the need for a clock dependency.
    *
    * @param interval
    *   compact every `interval` applied entries (e.g., 5000)
    */
  def timed[F[_]: Applicative](interval: Long): CompactionPolicy[F] =
    new CompactionPolicy[F]:
      def shouldCompact(
          lastApplied: Long,
          lastSnapshotIndex: Long,
          logSize: Long
      ): F[Boolean] =
        (lastApplied > 0 && lastApplied % interval == 0).pure[F]

      def compactionIndex(lastApplied: Long): F[Long] = lastApplied.pure[F]
