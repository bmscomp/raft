package raft.effect

import raft.state.{NodeId, Log}
import raft.message.RaftMessage

import scala.concurrent.duration.FiniteDuration

/** Sealed hierarchy of side effects produced by pure RAFT state transitions.
  *
  * Effects are the bridge between the pure consensus core and the real world.
  * Instead of performing I/O directly, [[raft.logic.RaftLogic]] returns a list
  * of `Effect` values — pure data that ''describes'' what should happen. The
  * runtime ([[raft.RaftNode]]) then interprets each effect, executing the
  * actual I/O (network sends, disk writes, timer resets).
  *
  * This '''reified effect pattern''' is analogous to a free monad or the Writer
  * monad pattern: the logic ''writes'' effects to a list, and the runtime
  * ''reads'' them and acts. The key benefit is testability: unit tests can
  * inspect the returned `List[Effect]` without mocking any infrastructure.
  *
  * Effects are grouped into categories:
  *   - '''Messaging''' — send/broadcast/pipelining of RPC messages
  *   - '''Persistence''' — hard-state writes and log mutations (§5.2
  *     durability)
  *   - '''State Machine''' — applying committed entries and snapshotting (§5.3)
  *   - '''Timers''' — resetting election and heartbeat timers (§5.2)
  *   - '''Leadership''' — leader initialization, transfer, and commit tracking
  *   - '''Linearizable Reads''' — ReadIndex and lease-based read protocols
  *
  * @see
  *   [[Transition]] for the state + effects result type
  * @see
  *   [[raft.logic.RaftLogic]] for the functions that produce effects
  * @see
  *   [[raft.RaftNode]] for the runtime that executes effects
  */
enum Effect:
  /** Send a Raft protocol message to a specific peer node.
    *
    * @param to
    *   the target node identifier
    * @param message
    *   the RPC message to send
    */
  case SendMessage(to: NodeId, message: RaftMessage)

  /** Broadcast a Raft protocol message to all other nodes in the cluster.
    *
    * @param message
    *   the RPC message to broadcast
    */
  case Broadcast(message: RaftMessage)

  /** Persist hard state (current term and votedFor) to durable storage.
    *
    * This must be flushed to disk before responding to any RPC, as required by
    * the RAFT safety guarantees for crash recovery.
    *
    * @param term
    *   the current term to persist
    * @param votedFor
    *   the candidate voted for in the current term (if any)
    */
  case PersistHardState(term: Long, votedFor: Option[NodeId])

  /** Append new log entries to persistent storage in the order they appear.
    *
    * @param entries
    *   the log entries to append
    */
  case AppendLogs(entries: Seq[Log])

  /** Truncate the log from the given index onwards, removing conflicting
    * entries.
    *
    * This occurs when a follower discovers entries that conflict with the
    * leader's log during the log matching check.
    *
    * @param fromIndex
    *   the first index to remove (inclusive); all entries at or after this
    *   index are deleted
    */
  case TruncateLog(fromIndex: Long)

  /** Apply a committed log entry to the user's state machine.
    *
    * @param entry
    *   the log entry whose command should be executed
    */
  case ApplyToStateMachine(entry: Log)

  /** Reset the election timer with a randomized duration to prevent split
    * votes.
    *
    * Called when a follower receives a valid heartbeat or grants a vote,
    * ensuring it does not start an unnecessary election.
    */
  case ResetElectionTimer

  /** Reset the heartbeat timer to schedule the next heartbeat to followers.
    *
    * Called after a leader sends heartbeats, ensuring periodic keep-alive
    * messages.
    */
  case ResetHeartbeatTimer

  /** Trigger a snapshot at the given index to compact the log and reduce
    * storage.
    *
    * @param lastIncludedIndex
    *   the last log index included in the snapshot
    * @param lastIncludedTerm
    *   the term of the last included log entry
    */
  case TakeSnapshot(lastIncludedIndex: Long, lastIncludedTerm: Long)

  /** Transition to leader state and initialize replication tracking for all
    * followers.
    */
  case BecomeLeader

  /** Initiate graceful leadership transfer to the specified target node.
    *
    * @param target
    *   the node that should become the next leader
    * @see
    *   [[TimeoutNow]] for the follow-up effect that triggers immediate election
    */
  case TransferLeadership(target: NodeId)

  /** Initialize nextIndex and matchIndex maps for all followers upon becoming
    * leader.
    *
    * Sets `nextIndex = lastLogIndex + 1` and `matchIndex = 0` for each
    * follower, following the RAFT leader initialization protocol.
    *
    * @param followers
    *   the set of follower node IDs to track
    * @param lastLogIndex
    *   the index of the last entry in the leader's log
    */
  case InitializeLeaderState(followers: Set[NodeId], lastLogIndex: Long)

  /** Update the replication progress for a specific follower after receiving a
    * response.
    *
    * @param followerId
    *   the follower whose indices are being updated
    * @param matchIndex
    *   the highest log index replicated on the follower
    * @param nextIndex
    *   the next log index to send to the follower
    */
  case UpdateFollowerIndex(
      followerId: NodeId,
      matchIndex: Long,
      nextIndex: Long
  )

  /** Commit all log entries up to the given index by applying them to the state
    * machine.
    *
    * @param upToIndex
    *   the highest index to commit (inclusive)
    */
  case CommitEntries(upToIndex: Long)

  /** Replicate entries to multiple followers in parallel.
    *
    * The runtime should execute sends concurrently for improved throughput.
    *
    * @param targets
    *   the set of follower node IDs to replicate to
    * @param message
    *   the AppendEntries message to send
    */
  case ParallelReplicate(targets: Set[NodeId], message: RaftMessage)

  /** Batch multiple client commands into a single log append for throughput.
    *
    * @param entries
    *   the batched log entries
    * @param batchId
    *   unique identifier for tracking this batch
    * @see
    *   [[BatchComplete]] for the completion notification
    */
  case BatchAppend(entries: Seq[Log], batchId: String)

  /** Notify that a batch has been committed.
    *
    * @param batchId
    *   the identifier of the completed batch
    * @param commitIndex
    *   the commit index at completion time
    */
  case BatchComplete(batchId: String, commitIndex: Long)

  /** Send the next AppendEntries without waiting for the previous response.
    *
    * Pipelining increases replication throughput by overlapping RPCs.
    *
    * @param to
    *   the target follower
    * @param message
    *   the AppendEntries message to send
    * @param sequenceNum
    *   monotonic sequence number for ordering pipelined requests
    */
  case PipelinedSend(to: NodeId, message: RaftMessage, sequenceNum: Long)

  /** Track an in-flight pipelined request for flow control.
    *
    * @param followerId
    *   the follower receiving the pipelined request
    * @param sequenceNum
    *   the sequence number of the in-flight request
    * @param lastIndex
    *   the last log index included in the request
    */
  case TrackInflight(followerId: NodeId, sequenceNum: Long, lastIndex: Long)

  /** ReadIndex request accepted — client can read after commitIndex reaches
    * readIndex.
    *
    * @param requestId
    *   the unique identifier of the read request
    * @param readIndex
    *   the commit index the client must wait for before reading
    * @see
    *   [[ReadIndexRejected]] for the failure case
    */
  case ReadIndexReady(requestId: String, readIndex: Long)

  /** ReadIndex request rejected — node is not the leader or quorum was not
    * confirmed.
    *
    * @param requestId
    *   the unique identifier of the rejected read request
    * @param leaderHint
    *   the known leader's ID (if any) for client redirection
    */
  case ReadIndexRejected(requestId: String, leaderHint: Option[NodeId])

  /** Confirm leadership via a heartbeat round for the ReadIndex protocol.
    *
    * Before serving a ReadIndex request, the leader must verify it still holds
    * authority by receiving responses from a majority.
    *
    * @param requestId
    *   the read request waiting for confirmation
    * @param pendingReadIndex
    *   the commit index to serve once leadership is confirmed
    */
  case ConfirmLeadership(requestId: String, pendingReadIndex: Long)

  /** Request the target node to start an election immediately.
    *
    * Used during leadership transfer to trigger the target's election without
    * waiting for an election timeout.
    *
    * @param target
    *   the node that should immediately start an election
    * @see
    *   [[TransferLeadership]] for the initiating effect
    */
  case TimeoutNow(target: NodeId)

  /** Extend the leader lease after a successful heartbeat quorum.
    *
    * @param until
    *   the timestamp (epoch millis) until which the lease is valid
    * @see
    *   [[LeaseReadReady]] for reads served under an active lease
    */
  case ExtendLease(until: Long)

  /** Lease read accepted — the leader's lease is active and the client can read
    * immediately.
    *
    * @param requestId
    *   the unique identifier of the lease read request
    */
  case LeaseReadReady(requestId: String)
