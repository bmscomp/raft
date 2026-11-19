package raft.multigroup

import scala.concurrent.duration.*

import raft.state.{ConfigError, Validated}

/** Per-host heartbeat coalescing policy for [[MultiGroupTransport]]
  * (CockroachDB / TiKV Multi-Raft pattern).
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

object CoalescedHeartbeatConfig:
  def validate(
      enabled: Boolean = false,
      flushInterval: FiniteDuration = 10.millis,
      maxBatchSize: Int = 256,
      coalesceResponses: Boolean = false
  ): Validated[ConfigError, CoalescedHeartbeatConfig] =
    Validated.mapN(
      Validated.check(
        flushInterval > Duration.Zero,
        ConfigError.InvalidDuration(
          "CoalescedHeartbeatConfig.flushInterval",
          s"must be > 0, got $flushInterval"
        )
      ),
      Validated.check(
        maxBatchSize > 0,
        ConfigError.InvalidPositiveInt(
          "CoalescedHeartbeatConfig.maxBatchSize",
          maxBatchSize
        )
      )
    )((_, _) =>
      CoalescedHeartbeatConfig(
        enabled = enabled,
        flushInterval = flushInterval,
        maxBatchSize = maxBatchSize,
        coalesceResponses = coalesceResponses
      )
    )

/** Operational counters reported by [[MultiGroupTransport.coalescerStats]]. */
final case class CoalescerStats(
    heartbeatsQueued: Long = 0L,
    batchesFlushed: Long = 0L,
    envelopesCoalesced: Long = 0L,
    envelopesSuperseded: Long = 0L,
    droppedUnknownGroupEnvelopes: Long = 0L
)
