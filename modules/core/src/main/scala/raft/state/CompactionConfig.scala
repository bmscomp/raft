package raft.state

/** Compaction level presets controlling how aggressively log compaction runs.
  */
enum CompactionLevel(val defaultThreshold: Long):
  case Disabled extends CompactionLevel(Long.MaxValue)
  case Conservative extends CompactionLevel(10_000)
  case Moderate extends CompactionLevel(1_000)
  case Aggressive extends CompactionLevel(100)
  case Custom extends CompactionLevel(0)

/** Pure configuration for automatic log compaction via snapshots.
  *
  * Kept free of effect-system imports so `raft-core` remains zero-dependency;
  * effectful `CompactionPolicy[F]` resolution is provided as an extension in
  * `raft.spi.CompactionPolicy` inside `raft-runtime`.
  *
  * @param level
  *   the compaction aggressiveness preset (default: `Conservative`)
  * @param snapshotChunkSize
  *   maximum byte size per `InstallSnapshot` chunk (default: 64 KB)
  * @param retainedEntries
  *   number of log entries retained after a snapshot so slightly lagging
  *   followers can still catch up via `AppendEntries`
  * @param policy
  *   optional host-provided compaction policy instance
  */
final case class CompactionConfig(
    level: CompactionLevel = CompactionLevel.Conservative,
    snapshotChunkSize: Int = 64 * 1024,
    retainedEntries: Long = 100,
    policy: Option[AnyRef] = None
):
  /** Pure threshold check usable directly by Sans-I/O host loops. */
  def shouldCompactPure(
      lastApplied: Long,
      lastSnapshotIndex: Long
  ): Boolean =
    level match
      case CompactionLevel.Disabled | CompactionLevel.Custom => false
      case _ => (lastApplied - lastSnapshotIndex) >= level.defaultThreshold
