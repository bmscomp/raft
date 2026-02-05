package raft.state

import scala.concurrent.duration.*

/**
 * Configuration for batching client commands.
 * 
 * @param enabled whether to batch multiple commands
 * @param maxSize maximum entries in a batch
 * @param maxWait maximum time to wait for batch to fill
 */
case class BatchConfig(
  enabled: Boolean = false,
  maxSize: Int = 100,
  maxWait: FiniteDuration = 10.millis
)

/**
 * Configuration for pipelining AppendEntries.
 * 
 * @param enabled whether to pipeline requests
 * @param maxInflight maximum in-flight requests per follower
 */
case class PipelineConfig(
  enabled: Boolean = false,
  maxInflight: Int = 10
)

/**
 * Configuration for a RAFT node.
 * 
 * All timeouts use Scala's FiniteDuration for type safety.
 */
final case class RaftConfig(
  /** Unique identifier for this node */
  localId: NodeId,
  
  /** Timeout before starting election (randomized between min and max) */
  electionTimeoutMin: FiniteDuration = 150.millis,
  electionTimeoutMax: FiniteDuration = 300.millis,
  
  /** Heartbeat interval for leader */
  heartbeatInterval: FiniteDuration = 50.millis,
  
  /** Maximum entries per AppendEntries RPC */
  maxEntriesPerRequest: Int = 100,
  
  /** Enable pre-voting to prevent disruption */
  preVoteEnabled: Boolean = true,
  
  /** Enable leader stickiness (reject votes if leader is alive) */
  leaderStickinessEnabled: Boolean = true,
  
  /** Leader lease duration for lease-based reads */
  leaderLeaseDuration: FiniteDuration = 100.millis,
  
  /** Enable parallel replication to followers */
  parallelReplicationEnabled: Boolean = true,
  
  /** Batching configuration */
  batching: BatchConfig = BatchConfig(),
  
  /** Pipelining configuration */
  pipelining: PipelineConfig = PipelineConfig()
)

object RaftConfig:
  def default(localId: NodeId): RaftConfig = RaftConfig(localId)

