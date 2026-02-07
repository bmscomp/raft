package raft.state

import scala.concurrent.duration.*

/** Configuration for batching client commands into a single log append.
  *
  * When enabled, the leader buffers incoming client commands and appends them
  * as a single batch to reduce per-entry overhead and improve throughput under
  * high load.
  *
  * @param enabled
  *   whether to batch multiple commands into one append
  * @param maxSize
  *   maximum number of entries allowed in a single batch
  * @param maxWait
  *   maximum time to wait for a batch to fill before flushing
  * @see
  *   [[raft.effect.Effect.BatchAppend]] for the effect produced by batching
  */
case class BatchConfig(
    enabled: Boolean = false,
    maxSize: Int = 100,
    maxWait: FiniteDuration = 10.millis
)

/** Configuration for pipelining AppendEntries RPCs to followers.
  *
  * When enabled, the leader sends multiple AppendEntries requests without
  * waiting for responses from previous ones, increasing replication throughput
  * at the cost of more complex flow control.
  *
  * @param enabled
  *   whether to pipeline requests instead of waiting for each response
  * @param maxInflight
  *   maximum number of unacknowledged in-flight requests per follower
  * @see
  *   [[raft.effect.Effect.PipelinedSend]] for the effect produced by pipelining
  */
case class PipelineConfig(
    enabled: Boolean = false,
    maxInflight: Int = 10
)

/** Configuration for a RAFT node.
  *
  * All timeouts use Scala's `FiniteDuration` for type safety. Default values
  * follow the recommendations from the RAFT paper (§5.2): election timeouts
  * between 150–300ms and heartbeat intervals significantly shorter than the
  * election timeout.
  *
  * @param localId
  *   unique identifier for this node within the cluster
  * @param electionTimeoutMin
  *   minimum bound for the randomized election timeout
  * @param electionTimeoutMax
  *   maximum bound for the randomized election timeout
  * @param heartbeatInterval
  *   interval between leader heartbeats to followers
  * @param maxEntriesPerRequest
  *   maximum log entries to include in a single AppendEntries RPC
  * @param preVoteEnabled
  *   whether to use the pre-vote protocol to prevent disruption
  * @param leaderStickinessEnabled
  *   whether to reject votes while a leader is alive
  * @param leaderLeaseDuration
  *   duration of the leader lease for lease-based reads
  * @param parallelReplicationEnabled
  *   whether to replicate to followers in parallel
  * @param batching
  *   configuration for command batching
  * @param pipelining
  *   configuration for AppendEntries pipelining
  * @see
  *   [[raft.RaftNode]] for how this configuration is consumed at runtime
  * @see
  *   [[raft.logic.RaftLogic]] for how timeouts influence state transitions
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
    pipelining: PipelineConfig = PipelineConfig()
)

/** Factory methods for [[RaftConfig]]. */
object RaftConfig:
  /** Create a [[RaftConfig]] with default settings for the given node.
    *
    * @param localId
    *   unique identifier for the node
    * @return
    *   a configuration with all default timeout and feature values
    */
  def default(localId: NodeId): RaftConfig = RaftConfig(localId)
