package raft.multigroup

import raft.state.{ClusterConfig, RaftConfig}
import raft.spi.{
  LogStore,
  SnapshotStore,
  StableStore,
  StateMachine,
  TimerService
}
import raft.metrics.RaftMetrics

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
