package raft.compaction

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import raft.spi.{CompactionPolicy, LogStore, SnapshotStore, StateMachine}
import raft.state.{
  ClusterConfig,
  CompactionConfig,
  LogIndex,
  NodeId,
  Snapshot,
  SnapshotMetadata as CoreSnapshotMetadata,
  resolvePolicy
}
import raft.metrics.RaftMetrics

/** Orchestrates the log compaction lifecycle.
  *
  * The `CompactionManager` monitors the applied log index and triggers
  * snapshots according to the configured [[CompactionPolicy]]. The full
  * lifecycle is:
  *
  *   1. '''Check''' — ask the policy whether compaction is due
  *   1. '''Snapshot''' — call `StateMachine.snapshot` to capture current state
  *   1. '''Persist''' — write the snapshot to durable storage via
  *      [[SnapshotStore]]
  *   1. '''Truncate''' — discard log entries before the snapshot index (minus
  *      `retainedEntries`)
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @param config
  *   compaction settings (level, chunk size, retained entries)
  * @param policy
  *   the resolved compaction algorithm
  * @param logStore
  *   the log to compact
  * @param snapshotStore
  *   durable snapshot storage
  * @param stateMachine
  *   the replicated state machine to snapshot
  * @param nodeId
  *   the local node's identifier (for metrics/logging)
  * @param lastSnapshotIndexRef
  *   shared reference tracking the most recent snapshot index
  * @param metrics
  *   observability hooks
  * @param logger
  *   structured logger
  */
class CompactionManager[F[_]: Async](
    config: CompactionConfig,
    policy: CompactionPolicy[F],
    logStore: LogStore[F],
    snapshotStore: SnapshotStore[F],
    stateMachine: StateMachine[F, ?],
    nodeId: NodeId,
    lastSnapshotIndexRef: Ref[F, Long],
    metrics: RaftMetrics[F],
    logger: Logger[F],
    clusterProvider: Option[F[Option[ClusterConfig]]] = None,
    onCompacted: Option[CoreSnapshotMetadata => F[Unit]] = None
):

  /** Record an externally installed snapshot index so compaction thresholds
    * measure entries strictly after the installed snapshot.
    */
  def recordInstalledSnapshot(index: Long): F[Unit] =
    lastSnapshotIndexRef.update(math.max(_, index))

  /** Check the compaction policy and execute a snapshot if warranted.
    *
    * This method is safe to call on every applied entry — it short-circuits
    * quickly when compaction is not due.
    *
    * @param lastApplied
    *   the index of the most recently applied log entry
    * @param logSize
    *   the current number of entries in the log
    */
  def maybeCompact(lastApplied: Long, logSize: Long): F[Unit] =
    for
      lastSnapshotIdx <- lastSnapshotIndexRef.get
      shouldCompact <- policy.shouldCompact(
        lastApplied,
        lastSnapshotIdx,
        logSize
      )
      _ <-
        if shouldCompact then executeCompaction(lastApplied)
        else Async[F].unit
    yield ()

  /** Execute the full compaction lifecycle at the given index. */
  private def executeCompaction(lastApplied: Long): F[Unit] =
    for
      requestedIdx <- policy.compactionIndex(lastApplied)
      // stateMachine.snapshot always captures state at `lastApplied`; tagging
      // it with an earlier index would double-apply (requestedIdx+1..lastApplied)
      // upon restore. Use `lastApplied` as the snapshot boundary while honoring
      // `requestedIdx` as a conservative upper bound on log pruning.
      snapshotIdx = lastApplied
      _ <-
        if snapshotIdx <= 0L then Async[F].unit
        else
          for
            termFromLog <- logStore.termAt(LogIndex.unsafeFrom(snapshotIdx))
            termOpt <- termFromLog match
              case some @ Some(_) => Async[F].pure(some.map(_.value))
              case None           =>
                snapshotStore.loadLatest.map {
                  case Some(s) if s.lastIncludedIndex == snapshotIdx =>
                    Some(s.lastIncludedTerm)
                  case _ => None
                }
            _ <- termOpt match
              case None =>
                logger.warn(
                  s"Skipping compaction at index=$snapshotIdx: term unavailable in log or snapshot store"
                )
              case Some(term) =>
                for
                  _ <- logger.info(
                    s"Starting log compaction at index=$snapshotIdx"
                  )
                  clusterOpt <- clusterProvider.getOrElse(Async[F].pure(None))
                  data <- stateMachine.snapshot
                  snapshot = Snapshot(snapshotIdx, term, data, clusterOpt)
                  _ <- snapshotStore.save(snapshot)
                  _ <- lastSnapshotIndexRef.set(snapshotIdx)
                  _ <- onCompacted.traverse_(
                    _.apply(
                      CoreSnapshotMetadata(snapshotIdx, term, clusterOpt)
                    )
                  )
                  pruneBase = math.min(snapshotIdx, requestedIdx)
                  discardIdx = math.max(1L, pruneBase - config.retainedEntries)
                  _ <- logStore.discardBefore(LogIndex.unsafeFrom(discardIdx))
                  _ <- metrics.onSnapshotTaken(nodeId, snapshotIdx)
                  _ <- logger.info(
                    s"Compaction complete: snapshot@$snapshotIdx, discarded log before $discardIdx"
                  )
                yield ()
          yield ()
    yield ()

object CompactionManager:

  /** Create a [[CompactionManager]] with its internal snapshot index reference.
    */
  def create[F[_]: Async](
      config: CompactionConfig,
      logStore: LogStore[F],
      snapshotStore: SnapshotStore[F],
      stateMachine: StateMachine[F, ?],
      nodeId: NodeId,
      metrics: RaftMetrics[F],
      logger: Logger[F],
      clusterProvider: Option[F[Option[ClusterConfig]]] = None,
      onCompacted: Option[CoreSnapshotMetadata => F[Unit]] = None
  ): F[CompactionManager[F]] =
    for
      lastSnapshotIdx <- snapshotStore.loadLatest.map(
        _.map(_.lastIncludedIndex).getOrElse(0L)
      )
      ref <- Ref.of[F, Long](lastSnapshotIdx)
      policy = config.resolvePolicy[F]
    yield new CompactionManager(
      config,
      policy,
      logStore,
      snapshotStore,
      stateMachine,
      nodeId,
      ref,
      metrics,
      logger,
      clusterProvider,
      onCompacted
    )
