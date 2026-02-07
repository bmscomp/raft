package raft.state

import raft.spi.CompactionPolicy

/** Compaction level presets controlling how aggressively log compaction runs.
  *
  * Each level maps to a default entry threshold for the built-in
  * [[CompactionPolicy.threshold]] algorithm. The [[Custom]] level delegates
  * entirely to a user-provided [[CompactionPolicy]].
  *
  * @see
  *   [[CompactionConfig]] for the full compaction configuration
  * @see
  *   [[CompactionPolicy]] for the pluggable algorithm trait
  */
enum CompactionLevel(val defaultThreshold: Long):
  case Disabled extends CompactionLevel(Long.MaxValue)
  case Conservative extends CompactionLevel(10_000)
  case Moderate extends CompactionLevel(1_000)
  case Aggressive extends CompactionLevel(100)
  case Custom extends CompactionLevel(0)

/** Configuration for automatic log compaction via snapshots.
  *
  * Compaction is the process of replacing committed log entries with a snapshot
  * of the state machine, reducing memory and disk usage. This configuration
  * controls:
  *   - '''When''' to compact (via `level` or a custom `policy`)
  *   - '''How''' snapshots are transferred (via `snapshotChunkSize`)
  *   - '''How much''' log to retain after compaction (via `retainedEntries`)
  *
  * To use a custom compaction algorithm, set `level = Custom` and provide your
  * implementation via `policy`:
  *
  * {{{
  * val config = CompactionConfig(
  *   level = CompactionLevel.Custom,
  *   policy = Some(myCustomPolicy)
  * )
  * }}}
  *
  * @param level
  *   the compaction aggressiveness level (default: `Conservative`)
  * @param snapshotChunkSize
  *   the maximum byte size per `InstallSnapshot` chunk (default: 64KB)
  * @param retainedEntries
  *   the number of log entries to keep after a snapshot, allowing slow
  *   followers to catch up via `AppendEntries` instead of a full snapshot
  *   transfer (default: 100)
  * @param policy
  *   an optional user-provided [[CompactionPolicy]] that overrides the built-in
  *   threshold algorithm; required when `level = Custom`
  * @see
  *   [[CompactionLevel]] for available presets
  * @see
  *   [[CompactionPolicy]] for implementing custom algorithms
  */
final case class CompactionConfig(
    level: CompactionLevel = CompactionLevel.Conservative,
    snapshotChunkSize: Int = 64 * 1024,
    retainedEntries: Long = 100,
    policy: Option[CompactionPolicy[?]] = None
):

  /** Resolve the effective [[CompactionPolicy]] for this configuration.
    *
    * If a custom policy is provided, it is used regardless of level. Otherwise,
    * the level's default threshold is used with the built-in threshold
    * algorithm.
    *
    * @tparam F
    *   the effect type
    * @return
    *   the resolved compaction policy
    */
  def resolvePolicy[F[_]: cats.Applicative]: CompactionPolicy[F] =
    policy match
      case Some(p) => p.asInstanceOf[CompactionPolicy[F]]
      case None    =>
        level match
          case CompactionLevel.Disabled => CompactionPolicy.disabled[F]
          case _ => CompactionPolicy.threshold[F](level.defaultThreshold)
