package raft.state

import scala.concurrent.duration.*

/** Configuration for batching client commands into a single log append.
  *
  * Batching is a throughput optimization not covered by the core Raft paper but
  * widely adopted in production implementations (etcd, CockroachDB). Instead of
  * appending and replicating each client command individually, the leader
  * buffers commands and flushes them as a single batch, amortizing the
  * per-entry overhead of disk I/O and network round-trips.
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
  * Standard Raft replication is stop-and-wait: the leader sends one
  * `AppendEntries` per follower and waits for the response before sending the
  * next. Pipelining removes this serialization by allowing multiple in-flight
  * requests, similar to TCP window-based flow control. This dramatically
  * reduces replication latency under burst writes at the cost of more complex
  * flow control and out-of-order response handling.
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
  * The Raft paper (§5.2) defines a timing requirement that is critical for
  * liveness: {{{broadcastTime ≪ electionTimeout ≪ MTBF}}}
  *
  * ''broadcastTime'' is the average time for a round-trip RPC (typically
  * 0.5–20ms). ''electionTimeout'' is the time after which a follower assumes
  * the leader has failed (150–300ms by default). ''MTBF'' is the mean time
  * between server failures (typically months). The heartbeat interval must be
  * significantly shorter than the election timeout so that followers do not
  * start unnecessary elections.
  *
  * Beyond the core timeouts, this configuration exposes several optimizations:
  *   - '''Pre-Vote''' (§9.6): prevents partitioned nodes from disrupting the
  *     cluster by incrementing their term. Enabled by default.
  *   - '''Leader Stickiness''': rejects votes while a legitimate leader is
  *     alive, reducing unnecessary leader churn in healthy clusters.
  *   - '''Leader Lease''': allows the leader to serve reads locally without a
  *     round-trip heartbeat confirmation, trading strict linearizability for
  *     lower read latency.
  *   - '''Parallel Replication''': sends `AppendEntries` to all followers
  *     concurrently rather than sequentially.
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
