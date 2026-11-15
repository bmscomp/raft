package raft.effect

import raft.state.{ClusterConfig, Log, LogIndex, NodeId, SnapshotMetadata, Term}
import raft.message.RaftMessage

/** Minimal, exhaustive algebra of pure side-effect descriptions emitted by
  * [[raft.logic.RaftLogic]] state transitions.
  *
  * Every `Effect` case is pure, immutable data with structural value equality
  * (`==`) and **zero** effect-type parameters or closure callbacks — allowing
  * unit tests, property tests, and deterministic simulators to inspect and
  * assert transitions directly without mocks or async runtimes.
  */
enum Effect:
  /** Send a Raft protocol message to a specific peer node. */
  case SendMessage(to: NodeId, message: RaftMessage)

  /** Broadcast a Raft protocol message to all peer nodes in the cluster. */
  case Broadcast(message: RaftMessage)

  /** Reset the randomized election timer. */
  case ResetElectionTimer

  /** Reset the periodic leader heartbeat timer. */
  case ResetHeartbeatTimer

  /** Advance the committed log index up to `index`. */
  case CommitEntries(index: LogIndex)

  /** Append new entries to the local persistent log before sending responses.
    */
  case AppendLogs(entries: Seq[Log])

  /** Truncate conflicting entries from `fromIndex` (inclusive) onwards (§5.3).
    */
  case TruncateLog(fromIndex: Long)

  /** Atomically persist hard state (`currentTerm` and `votedFor`) to stable
    * storage before sending outbound RPCs.
    */
  case PersistHardState(term: Long, votedFor: Option[NodeId])

  /** Signal transition into `Leader` role for the current term. */
  case BecomeLeader

  /** Signal step-down out of `Leader` role. */
  case StopLeader

  /** Signal that a `ReadIndex` request is confirmed at `index` and safe to
    * serve once `lastApplied >= index` (§6.4).
    */
  case ReadIndexReady(id: String, index: Long)

  /** Signal that a `ReadIndex` request was rejected (e.g., not leader or
    * current-term entry not yet committed).
    */
  case ReadIndexRejected(
      id: String,
      reason: String,
      leaderHint: Option[NodeId] = None
  )

  /** Confirm leadership for `term` via heartbeat quorum round `roundId`. */
  case ConfirmLeadership(term: Long, roundId: String = "default")

  /** Request the host to take a snapshot at `lastApplied`. */
  case RequestSnapshot(lastApplied: Long, logSize: Long)

  /** Install snapshot metadata into the consensus state and instruct the host
    * to restore state machine state and compact log entries up to
    * `metadata.lastIncludedIndex`.
    */
  case InstallSnapshot(metadata: SnapshotMetadata)

  /** Initiate leadership transfer to `target` (§3.10). */
  case TransferLeadership(target: NodeId)

  /** Send `TimeoutNow` to `target` so it starts an immediate election. */
  case TimeoutNow(target: NodeId)

  /** Apply a committed or appended cluster membership configuration
    * (`C_old,new` or `C_new`).
    */
  case ApplyClusterConfig(config: ClusterConfig, index: Long, term: Term)
