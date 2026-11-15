package raft.effect

import raft.state.{ClusterConfig, Log, LogIndex, NodeId, SnapshotMetadata, Term}
import raft.message.RaftMessage

/** Sealed hierarchy of pure side-effect descriptions produced by RAFT state
  * transitions.
  *
  * Every `Effect` case is pure, immutable data with structural value equality
  * (`==`) and **zero** effect-type parameters or closure callbacks — allowing
  * unit tests, property tests, and deterministic simulators to compare
  * transitions directly without mocks or runtimes.
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

  /** Persist hard state (`currentTerm` and `votedFor`) to stable storage before
    * sending outbound RPCs.
    */
  case PersistHardState(term: Long, votedFor: Option[NodeId])

  /** Signal transition into `Leader` role for the current term. */
  case BecomeLeader

  /** Signal step-down out of `Leader` role. */
  case StopLeader

  /** Apply a committed log entry to the replicated state machine. */
  case ApplyToStateMachine(entry: Log)

  /** Initiate a linearizable `ReadIndex` round for `requestId`. */
  case RunReadIndex(requestId: String)

  /** Send a `ReadIndex` confirmation to a peer or client. */
  case SendReadIndexResponse(from: NodeId, index: Long)

  /** Verify an active leader lease for `requestId`. */
  case VerifyLease(requestId: String)

  /** Notify that a lease-based read for `requestId` is safe to serve locally.
    */
  case LeaseReadReady(requestId: String)

  /** Signal that a `ReadIndex` request is confirmed at `index` and safe to
    * serve once `lastApplied >= index`.
    */
  case ReadIndexReady(id: String, index: Long)

  /** Signal that a `ReadIndex` request was rejected (e.g., not leader). */
  case ReadIndexRejected(
      id: String,
      reason: String,
      leaderHint: Option[NodeId] = None
  )

  /** Confirm leadership for `term` via heartbeat quorum round `roundId`. */
  case ConfirmLeadership(term: Long, roundId: String = "default")

  /** Request the host to take a snapshot at `lastApplied`. */
  case RequestSnapshot(lastApplied: Long, logSize: Long)

  /** Send a snapshot chunk to a lagging follower via `InstallSnapshot` RPC. */
  case SendSnapshotChunk(
      to: NodeId,
      request: raft.message.RaftMessage.InstallSnapshotRequest
  )

  /** Install snapshot metadata into the consensus state and instruct the host
    * to restore state machine state and compact log entries up to
    * `metadata.lastIncludedIndex`.
    */
  case InstallSnapshot(metadata: SnapshotMetadata)

  /** Initiate leadership transfer to `target` (§3.10). */
  case TransferLeadership(target: NodeId)

  /** Send `TimeoutNow` to `target` so it starts an immediate election. */
  case TimeoutNow(target: NodeId)

  /** Initialize leader replication state for `followers` at `lastLogIndex`. */
  case InitializeLeaderState(followers: Set[NodeId], lastLogIndex: Long)

  /** Update a follower's replicated `matchIndex` and `nextIndex`. */
  case UpdateFollowerIndex(
      followerId: NodeId,
      matchIndex: LogIndex,
      nextIndex: LogIndex
  )

  /** Replicate a message to `targets` concurrently. */
  case ParallelReplicate(targets: Set[NodeId], message: RaftMessage)

  /** Append a batch of entries atomically under `batchId`. */
  case BatchAppend(entries: Seq[Log], batchId: String = "default")

  /** Signal completion of batch append `batchId`. */
  case BatchComplete(batchId: String, result: Either[String, Unit])

  /** Send a message on replication pipeline `pipelineId`. */
  case PipelinedSend(to: NodeId, message: RaftMessage, pipelineId: String)

  /** Record an in-flight pipelined request `messageId` to `to`. */
  case TrackInflight(to: NodeId, messageId: String, maxIndex: Long = 0L)

  /** Extend the leader's logical lease until `untilTick`. */
  case ExtendLease(untilTick: Long)

  /** Apply a committed cluster membership configuration (`C_old,new` or
    * `C_new`).
    */
  case ApplyClusterConfig(config: ClusterConfig, index: Long, term: Term)
