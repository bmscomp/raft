package raft.effect

import raft.state.{NodeId, Log}
import raft.message.RaftMessage

import scala.concurrent.duration.FiniteDuration

/** Sealed hierarchy of side effects produced by pure RAFT state transitions.
  *
  * Effects are pure data structures describing actions to be performed by the runtime.
  * The RaftNode interprets and executes these effects, maintaining separation between
  * pure logic and I/O operations.
  */
enum Effect:
  /** Send a Raft protocol message to a specific peer node identified by its NodeId. */
  case SendMessage(to: NodeId, message: RaftMessage)
  
  /** Broadcast a Raft protocol message to all other nodes in the cluster. */
  case Broadcast(message: RaftMessage)
  
  /** Persist hard state (current term and votedFor) to durable storage for crash recovery. */
  case PersistHardState(term: Long, votedFor: Option[NodeId])
  
  /** Append new log entries to persistent storage in the order they appear. */
  case AppendLogs(entries: Seq[Log])
  
  /** Truncate the log from the given index onwards, removing conflicting entries. */
  case TruncateLog(fromIndex: Long)
  
  /** Apply a committed log entry to the user's state machine for execution. */
  case ApplyToStateMachine(entry: Log)
  
  /** Reset the election timer with a randomized duration to prevent split votes. */
  case ResetElectionTimer
  
  /** Reset the heartbeat timer to schedule the next heartbeat message to followers. */
  case ResetHeartbeatTimer
  
  /** Trigger a snapshot at the given index to compact the log and reduce storage. */
  case TakeSnapshot(lastIncludedIndex: Long, lastIncludedTerm: Long)
  
  /** Transition to leader state and initialize replication tracking for all followers. */
  case BecomeLeader
  
  /** Initiate graceful leadership transfer to the specified target node. */
  case TransferLeadership(target: NodeId)
  
  /** Initialize nextIndex and matchIndex maps for all followers upon becoming leader. */
  case InitializeLeaderState(followers: Set[NodeId], lastLogIndex: Long)
  
  /** Update the replication progress indices for a specific follower after response. */
  case UpdateFollowerIndex(followerId: NodeId, matchIndex: Long, nextIndex: Long)
  
  /** Commit all log entries up to the given index by applying them to the state machine. */
  case CommitEntries(upToIndex: Long)
  
  // === PARALLEL APPEND ===
  
  /** Replicate entries to multiple followers in parallel. Runtime executes concurrently. */
  case ParallelReplicate(targets: Set[NodeId], message: RaftMessage)
  
  // === BATCHING ===
  
  /** Batch multiple client commands into a single log append for efficiency. */
  case BatchAppend(entries: Seq[Log], batchId: String)
  
  /** Notify batch completion with commit status. */
  case BatchComplete(batchId: String, commitIndex: Long)
  
  // === PIPELINING ===
  
  /** Send next AppendEntries without waiting for previous response. */
  case PipelinedSend(to: NodeId, message: RaftMessage, sequenceNum: Long)
  
  /** Track in-flight pipelined requests for a follower. */
  case TrackInflight(followerId: NodeId, sequenceNum: Long, lastIndex: Long)
  
  // === LINEARIZABLE READS ===
  
  /** ReadIndex request accepted - client can read after commitIndex reaches readIndex. */
  case ReadIndexReady(requestId: String, readIndex: Long)
  
  /** ReadIndex request rejected - not leader or quorum not confirmed. */
  case ReadIndexRejected(requestId: String, leaderHint: Option[NodeId])
  
  /** Confirm leadership with heartbeat round for ReadIndex. */
  case ConfirmLeadership(requestId: String, pendingReadIndex: Long)
  
  // === LEADERSHIP TRANSFER ===
  
  /** Request target node to start election immediately. */
  case TimeoutNow(target: NodeId)
  
  // === LEASE-BASED READS ===
  
  /** Extend leader lease after successful heartbeat quorum. */
  case ExtendLease(until: Long)
  
  /** Lease read accepted - client can read immediately. */
  case LeaseReadReady(requestId: String)

