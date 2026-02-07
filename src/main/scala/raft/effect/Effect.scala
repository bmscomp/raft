package raft.effect

import raft.state.{NodeId, Log, LogIndex}
import raft.message.RaftMessage
import raft.state.Snapshot

import scala.concurrent.duration.FiniteDuration

/** Sealed hierarchy of side effects produced by pure RAFT state transitions.
  *
  * Effects are the bridge between the pure consensus core and the real world.
  * Instead of performing I/O directly, [[raft.logic.RaftLogic]] returns a list
  * of `Effect` values — pure data that ''describes'' what should happen. The
  * runtime ([[raft.RaftNode]]) then interprets each effect, executing the
  * actual I/O (network sends, disk writes, timer resets).
  *
  * Effects are generic in `F` to allow carrying effectful data types (like
  * [[raft.state.Snapshot]] which contains a stream).
  */
enum Effect[+F[_]]:
  /** Send a Raft protocol message to a specific peer node. */
  case SendMessage(to: NodeId, message: RaftMessage)

  /** Broadcast a Raft protocol message to all other nodes in the cluster. */
  case Broadcast(message: RaftMessage)

  /** Reset the election timer. */
  case ResetElectionTimer

  /** Reset the heartbeat timer. */
  case ResetHeartbeatTimer

  /** Commit entries up to the given index. */
  case CommitEntries(index: LogIndex)

  /** Append new entries to the local log. */
  case AppendLogs(entries: Seq[Log])

  /** Persist hard state (current term and votedFor). */
  case PersistHardState(term: Long, votedFor: Option[NodeId])

  /** Transition to Leader state. */
  case BecomeLeader

  /** Clean up any state from a previous leadership term. */
  case StopLeader

  /** Signal that the state machine has applied a command and the client result
    * is available.
    */
  case ApplyToStateMachine(entry: Log)

  /** Initiate a linearizable read (ReadIndex) operation. */
  case RunReadIndex(callback: () => Unit)

  /** Send a ReadIndex confirmation to the leader. */
  case SendReadIndexResponse(from: NodeId, index: Long)

  /** Verify leadership and execute a callback if confirmed (lease-based read).
    */
  case VerifyLease(callback: () => Unit)

  /** Notify that a lease read is ready to be served. */
  case LeaseReadReady(requestId: String)

  /** Request the compaction manager to initiate a snapshot at the given applied
    * index.
    */
  case RequestSnapshot(lastApplied: Long, logSize: Long)

  /** Send a chunk of a snapshot to a follower via `InstallSnapshot` RPC. */
  case SendSnapshotChunk(
      to: NodeId,
      request: raft.message.RaftMessage.InstallSnapshotRequest
  )

  /** Install a complete snapshot — restore the state machine and discard the
    * log up to the snapshot's last included index.
    *
    * @param snapshot
    *   the assembled snapshot to install (containing a stream of data)
    */
  case InstallSnapshot[F[_]](snapshot: Snapshot[F]) extends Effect[F]

  /** Truncate the log from the given index onwards. */
  case TruncateLog(fromIndex: Long)

  /** Transfer leadership to a specific node. */
  case TransferLeadership(target: NodeId)

  /** Initialize leader state with follower mapping. */
  case InitializeLeaderState(followers: Set[NodeId], lastLogIndex: Long)

  /** Update a follower's match and next indices. */
  case UpdateFollowerIndex(
      followerId: NodeId,
      matchIndex: LogIndex,
      nextIndex: LogIndex
  )

  /** Replicate a message to multiple targets in parallel. */
  case ParallelReplicate(targets: Set[NodeId], message: RaftMessage)

  /** Append a batch of entries to the log. */
  case BatchAppend(
      entries: Seq[Log],
      callback: Option[Throwable => Unit] = None
  )

  /** Signal completion of a batch append. */
  case BatchComplete(batchId: String, result: Either[Throwable, Unit])

  /** Send a message as part of a pipeline. */
  case PipelinedSend(to: NodeId, message: RaftMessage, pipelineId: String)

  /** Track an inflight message for flow control. */
  case TrackInflight(to: NodeId, messageId: String, callback: () => Unit)

  /** Signal that a ReadIndex request is ready to be executed. */
  case ReadIndexReady(id: String, index: Long)

  /** Signal that a ReadIndex request was rejected. */
  case ReadIndexRejected(id: String, reason: String)

  /** Confirm leadership for a given term. */
  case ConfirmLeadership(term: Long, callback: () => Unit)

  /** Trigger an immediate election (e.g. for leadership transfer). */
  case TimeoutNow(target: NodeId)

  /** Extend the leader's lease. */
  case ExtendLease(until: Long)
