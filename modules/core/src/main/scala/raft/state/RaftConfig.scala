package raft.state

import scala.concurrent.duration.*

/** Configuration for batching client commands into a single log append. */
final case class BatchConfig(
    enabled: Boolean = false,
    maxSize: Int = 100,
    maxWait: FiniteDuration = 10.millis
):
  require(maxSize > 0, s"BatchConfig.maxSize must be > 0, got $maxSize")
  require(
    maxWait >= Duration.Zero,
    s"BatchConfig.maxWait must be >= 0, got $maxWait"
  )

object BatchConfig:
  def validate(
      enabled: Boolean = false,
      maxSize: Int = 100,
      maxWait: FiniteDuration = 10.millis
  ): Validated[ConfigError, BatchConfig] =
    Validated.mapN(
      Validated.check(
        maxSize > 0,
        ConfigError.InvalidPositiveInt("BatchConfig.maxSize", maxSize)
      ),
      Validated.check(
        maxWait >= Duration.Zero,
        ConfigError.InvalidDuration(
          "BatchConfig.maxWait",
          s"must be >= 0, got $maxWait"
        )
      )
    )((_, _) => BatchConfig(enabled, maxSize, maxWait))

/** Configuration for pipelining `AppendEntries` RPCs (`Inflights` window). */
final case class PipelineConfig(
    enabled: Boolean = false,
    maxInflight: Int = 10
):
  require(
    maxInflight > 0,
    s"PipelineConfig.maxInflight must be > 0, got $maxInflight"
  )

object PipelineConfig:
  def validate(
      enabled: Boolean = false,
      maxInflight: Int = 10
  ): Validated[ConfigError, PipelineConfig] =
    Validated
      .check(
        maxInflight > 0,
        ConfigError.InvalidPositiveInt(
          "PipelineConfig.maxInflight",
          maxInflight
        )
      )
      .map(_ => PipelineConfig(enabled, maxInflight))

/** Configuration for a RAFT node. */
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
    heartbeatIntervalTicks: Int = 1,
    maxInboundQueueSize: Int = 8192,
    maxPendingProposals: Int = 8192
):
  require(
    electionTimeoutMin > Duration.Zero,
    s"electionTimeoutMin must be > 0, got $electionTimeoutMin"
  )
  require(
    electionTimeoutMax >= electionTimeoutMin,
    s"electionTimeoutMax ($electionTimeoutMax) must be >= electionTimeoutMin ($electionTimeoutMin)"
  )
  require(
    heartbeatInterval > Duration.Zero && heartbeatInterval < electionTimeoutMin,
    s"heartbeatInterval ($heartbeatInterval) must be > 0 and < electionTimeoutMin ($electionTimeoutMin)"
  )
  require(
    maxEntriesPerRequest > 0,
    s"maxEntriesPerRequest must be > 0, got $maxEntriesPerRequest"
  )
  require(
    heartbeatIntervalTicks > 0,
    s"heartbeatIntervalTicks must be > 0, got $heartbeatIntervalTicks"
  )
  require(
    electionTimeoutTicks > heartbeatIntervalTicks,
    "electionTimeoutTicks must exceed heartbeatIntervalTicks"
  )
  require(
    maxInboundQueueSize > 0,
    s"maxInboundQueueSize must be > 0, got $maxInboundQueueSize"
  )
  require(
    maxPendingProposals > 0,
    s"maxPendingProposals must be > 0, got $maxPendingProposals"
  )

object RaftConfig:
  def default(localId: NodeId): RaftConfig = RaftConfig(localId)

  /** Pure, error-accumulating smart constructor validating every field of
    * [[RaftConfig]] without throwing exceptions.
    */
  def validate(
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
      heartbeatIntervalTicks: Int = 1,
      maxInboundQueueSize: Int = 8192,
      maxPendingProposals: Int = 8192
  ): Validated[ConfigError, RaftConfig] =
    val checks = Vector(
      NodeId.validate(localId.value).map(_ => ()),
      Validated.check(
        electionTimeoutMin > Duration.Zero,
        ConfigError.InvalidDuration(
          "electionTimeoutMin",
          s"must be > 0, got $electionTimeoutMin"
        )
      ),
      Validated.check(
        electionTimeoutMax >= electionTimeoutMin,
        ConfigError.InvalidIntervalOrdering(
          "electionTimeoutMax",
          s"($electionTimeoutMax) must be >= electionTimeoutMin ($electionTimeoutMin)"
        )
      ),
      Validated.check(
        heartbeatInterval > Duration.Zero && heartbeatInterval < electionTimeoutMin,
        ConfigError.InvalidIntervalOrdering(
          "heartbeatInterval",
          s"($heartbeatInterval) must be > 0 and < electionTimeoutMin ($electionTimeoutMin)"
        )
      ),
      Validated.check(
        maxEntriesPerRequest > 0,
        ConfigError.InvalidPositiveInt(
          "maxEntriesPerRequest",
          maxEntriesPerRequest
        )
      ),
      Validated.check(
        heartbeatIntervalTicks > 0,
        ConfigError.InvalidPositiveInt(
          "heartbeatIntervalTicks",
          heartbeatIntervalTicks
        )
      ),
      Validated.check(
        electionTimeoutTicks > heartbeatIntervalTicks,
        ConfigError.InvalidIntervalOrdering(
          "electionTimeoutTicks",
          s"($electionTimeoutTicks) must exceed heartbeatIntervalTicks ($heartbeatIntervalTicks)"
        )
      ),
      Validated.check(
        maxInboundQueueSize > 0,
        ConfigError.InvalidPositiveInt(
          "maxInboundQueueSize",
          maxInboundQueueSize
        )
      ),
      Validated.check(
        maxPendingProposals > 0,
        ConfigError.InvalidPositiveInt(
          "maxPendingProposals",
          maxPendingProposals
        )
      )
    )

    checks
      .foldLeft(Validated.valid(()): Validated[ConfigError, Unit]) { (a, b) =>
        Validated.mapN(a, b)((_, _) => ())
      }
      .map(_ =>
        RaftConfig(
          localId = localId,
          electionTimeoutMin = electionTimeoutMin,
          electionTimeoutMax = electionTimeoutMax,
          heartbeatInterval = heartbeatInterval,
          maxEntriesPerRequest = maxEntriesPerRequest,
          preVoteEnabled = preVoteEnabled,
          leaderStickinessEnabled = leaderStickinessEnabled,
          leaderLeaseDuration = leaderLeaseDuration,
          parallelReplicationEnabled = parallelReplicationEnabled,
          batching = batching,
          pipelining = pipelining,
          compaction = compaction,
          checkQuorumEnabled = checkQuorumEnabled,
          leaseReadEnabled = leaseReadEnabled,
          electionTimeoutTicks = electionTimeoutTicks,
          heartbeatIntervalTicks = heartbeatIntervalTicks,
          maxInboundQueueSize = maxInboundQueueSize,
          maxPendingProposals = maxPendingProposals
        )
      )
