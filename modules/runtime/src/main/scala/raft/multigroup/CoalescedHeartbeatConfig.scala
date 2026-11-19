package raft.multigroup

import scala.concurrent.duration.*

/** Per-host heartbeat coalescing policy for [[MultiGroupTransport]]
  * (CockroachDB / TiKV Multi-Raft pattern).
  *
  * When `enabled = true`, outbound unicast empty `AppendEntriesRequest`
  * heartbeats (and optionally `AppendEntriesResponse` frames when
  * `coalesceResponses = true`) across all hosted Raft groups targeting the same
  * peer physical node are buffered, deduplicated per `(peer, groupId)`, and
  * flushed together as a single wire batch every `flushInterval` or immediately
  * upon reaching `maxBatchSize`.
  *
  * Data-carrying `AppendEntriesRequest`s (`entries.nonEmpty`), elections,
  * `InstallSnapshot`, and `TimeoutNow` always bypass the coalescer with zero
  * added latency — and automatically supersede any stale buffered heartbeat for
  * the same `(peer, groupId)`.
  */
final case class CoalescedHeartbeatConfig(
    enabled: Boolean = false,
    flushInterval: FiniteDuration = 10.millis,
    maxBatchSize: Int = 256,
    coalesceResponses: Boolean = false
):
  require(
    flushInterval > Duration.Zero,
    s"CoalescedHeartbeatConfig.flushInterval must be > 0, got $flushInterval"
  )
  require(
    maxBatchSize > 0,
    s"CoalescedHeartbeatConfig.maxBatchSize must be > 0, got $maxBatchSize"
  )

/** Operational counters reported by [[MultiGroupTransport.coalescerStats]]. */
final case class CoalescerStats(
    heartbeatsQueued: Long = 0L,
    batchesFlushed: Long = 0L,
    envelopesCoalesced: Long = 0L,
    envelopesSuperseded: Long = 0L
)
