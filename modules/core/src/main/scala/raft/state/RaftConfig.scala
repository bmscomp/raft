package raft.state

import scala.concurrent.duration.*

/** Configuration for batching client commands into a single log append. */
final case class BatchConfig(
    enabled: Boolean = false,
    maxSize: Int = 100,
    maxWait: FiniteDuration = 10.millis
)

/** Configuration for pipelining `AppendEntries` RPCs (`Inflights` window). */
final case class PipelineConfig(
    enabled: Boolean = false,
    maxInflight: Int = 10
)

/** Configuration for a RAFT node.
  *
  * Supports both duration-based host timers (`electionTimeoutMin`,
  * `heartbeatInterval`) for async runtimes and logical tick intervals
  * (`electionTimeoutTicks`, `heartbeatIntervalTicks`) for deterministic
  * `RawNode.tick()` Sans-I/O loops (`tikv/raft-rs` style).
  */
final case class RaftConfig(
    localId: NodeId,
    electionTimeoutMin: FiniteDuration = 150.millis,
    electionTimeoutMax: FiniteDuration = 300.millis,
    heartbeatInterval: FiniteDuration = 50.millis,
    maxEntriesPerRequest: Int = 100,
    preVoteEnabled: Boolean = true,
    leaderStickinessEnabled: Boolean = true,
    leaderLeaseDuration: FiniteDuration = 100.millis,
    parallelReplicationEnabled: Boolean = true,
    batching: BatchConfig = BatchConfig(),
    pipelining: PipelineConfig = PipelineConfig(),
    compaction: CompactionConfig = CompactionConfig(),
    checkQuorumEnabled: Boolean = true,
    leaseReadEnabled: Boolean = false,
    electionTimeoutTicks: Int = 10,
    heartbeatIntervalTicks: Int = 1
):
  require(
    electionTimeoutTicks > heartbeatIntervalTicks,
    "electionTimeoutTicks must exceed heartbeatIntervalTicks"
  )

object RaftConfig:
  def default(localId: NodeId): RaftConfig = RaftConfig(localId)
