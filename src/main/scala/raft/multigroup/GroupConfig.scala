package raft.multigroup

import raft.state.{RaftConfig, ClusterConfig}
import raft.spi.{LogStore, StableStore, StateMachine, TimerService}
import raft.metrics.RaftMetrics

/** Per-group configuration that bundles all dependencies needed to run a single
  * Raft consensus group within a [[MultiRaftNode]].
  *
  * Each group in a Multi-Raft deployment operates independently with its own:
  *   - '''Log store''' — isolated log entries that don't interfere across
  *     groups
  *   - '''Stable store''' — independent term/votedFor persistence per group
  *   - '''State machine''' — the user's application logic for this group's data
  *   - '''Timer service''' — independent election/heartbeat timers per group
  *
  * The [[raftConfig]] and [[cluster]] define the group's protocol behavior and
  * membership respectively. Note that `raftConfig.localId` identifies ''this
  * node'' within the group and must be the same across all groups on the same
  * physical node.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @param raftConfig
  *   protocol configuration (timeouts, features, local node ID)
  * @param cluster
  *   initial cluster membership for this group
  * @param logStore
  *   persistent log storage for this group
  * @param stableStore
  *   durable hard-state storage for this group
  * @param stateMachine
  *   the user's state machine for this group
  * @param timerService
  *   timer scheduling service for this group
  * @param metrics
  *   optional observability hooks (defaults to no-op)
  * @see
  *   [[MultiRaftNode.createGroup]] for how this config is consumed
  */
case class GroupConfig[F[_]](
    raftConfig: RaftConfig,
    cluster: ClusterConfig,
    logStore: LogStore[F],
    stableStore: StableStore[F],
    stateMachine: StateMachine[F, ?],
    timerService: TimerService[F],
    metrics: RaftMetrics[F] = null.asInstanceOf[RaftMetrics[F]]
)
