package raft.multigroup

import raft.metrics.RaftMetrics
import raft.spi.{
  LogStore,
  SnapshotStore,
  StableStore,
  StateMachine,
  TimerService
}
import raft.state.{ClusterConfig, RaftConfig}

/** Strongly-typed per-group configuration preserving the [[StateMachine]]
  * output type `R` when registering a group via
  * [[MultiRaftNode.createTypedGroup]].
  */
final case class TypedGroupConfig[F[_], R](
    raftConfig: RaftConfig,
    cluster: ClusterConfig,
    logStore: LogStore[F],
    stableStore: StableStore[F],
    stateMachine: StateMachine[F, R],
    timerService: TimerService[F],
    metrics: Option[RaftMetrics[F]] = None,
    snapshotStore: Option[SnapshotStore[F]] = None
):
  /** Project to an existentially erased [[GroupConfig]]. */
  def erased: GroupConfig[F] =
    GroupConfig[F](
      raftConfig = raftConfig,
      cluster = cluster,
      logStore = logStore,
      stableStore = stableStore,
      stateMachine = stateMachine,
      timerService = timerService,
      metrics = metrics,
      snapshotStore = snapshotStore
    )

/** Per-group configuration that bundles all dependencies needed to run a single
  * Raft consensus group within a [[MultiRaftNode]].
  */
final case class GroupConfig[F[_]](
    raftConfig: RaftConfig,
    cluster: ClusterConfig,
    logStore: LogStore[F],
    stableStore: StableStore[F],
    stateMachine: StateMachine[F, ?],
    timerService: TimerService[F],
    metrics: Option[RaftMetrics[F]] = None,
    snapshotStore: Option[SnapshotStore[F]] = None
)

object GroupConfig:
  def apply[F[_]](
      raftConfig: RaftConfig,
      cluster: ClusterConfig,
      logStore: LogStore[F],
      stableStore: StableStore[F],
      stateMachine: StateMachine[F, ?],
      timerService: TimerService[F],
      metrics: RaftMetrics[F]
  ): GroupConfig[F] =
    new GroupConfig[F](
      raftConfig,
      cluster,
      logStore,
      stableStore,
      stateMachine,
      timerService,
      Some(metrics),
      None
    )
