package raft.message

import raft.state.{Log, NodeId}

/** Sealed hierarchy of all RAFT RPC messages and logical timer events. */
enum RaftMessage:

  /** Replicate log entries or send a heartbeat (§5.3). */
  case AppendEntriesRequest(
      term: Long,
      leaderId: NodeId,
      prevLogIndex: Long,
      prevLogTerm: Long,
      entries: Seq[Log],
      leaderCommit: Long
  )

  /** Response to [[AppendEntriesRequest]] with optional §5.3 fast-backtracking
    * conflict hints (`conflictIndex` and `conflictTerm`).
    */
  case AppendEntriesResponse(
      term: Long,
      success: Boolean,
      matchIndex: Long,
      conflictIndex: Option[Long] = None,
      conflictTerm: Option[Long] = None
  )

  /** Request a vote or speculative pre-vote (§5.2, §9.6, §3.10).
    *
    * @param isPreVote
    *   `true` for non-binding Pre-Vote probes (`term` is `currentTerm + 1`)
    * @param isTransfer
    *   `true` when campaigning immediately upon receiving `TimeoutNowRequest`,
    *   bypassing `leaderStickiness` on peers (`tikv/raft-rs`
    *   `CampaignTransfer`)
    */
  case RequestVoteRequest(
      term: Long,
      candidateId: NodeId,
      lastLogIndex: Long,
      lastLogTerm: Long,
      isPreVote: Boolean = false,
      isTransfer: Boolean = false
  )

  /** Response to [[RequestVoteRequest]]. */
  case RequestVoteResponse(
      term: Long,
      voteGranted: Boolean,
      isPreVote: Boolean = false
  )

  /** Stream a snapshot chunk from leader to lagging follower (§7). */
  case InstallSnapshotRequest(
      term: Long,
      leaderId: NodeId,
      lastIncludedIndex: Long,
      lastIncludedTerm: Long,
      offset: Long,
      data: Array[Byte],
      done: Boolean
  )

  /** Response to [[InstallSnapshotRequest]]. */
  case InstallSnapshotResponse(
      term: Long,
      success: Boolean,
      matchIndex: Long = 0L
  )

  /** Administrative request on leader to transfer leadership to `target`
    * (§3.10).
    */
  case TransferLeadershipRequest(
      term: Long,
      target: NodeId
  )

  /** Direct RPC from departing leader to `target` once caught up, instructing
    * `target` to start an immediate election (`isTransfer = true`).
    */
  case TimeoutNowRequest(
      term: Long,
      leaderId: NodeId
  )

  /** Client request for a linearizable read via the `ReadIndex` protocol
    * (§6.4).
    */
  case ReadIndexRequest(
      requestId: String
  )

  /** Response to [[ReadIndexRequest]]. */
  case ReadIndexResponse(
      requestId: String,
      success: Boolean,
      readIndex: Long
  )

  /** Request to add a server (`Voter` or `Learner`) to the cluster (§6). */
  case AddServerRequest(
      term: Long,
      newServer: NodeId,
      isLearner: Boolean = false
  )

  /** Response to [[AddServerRequest]]. */
  case AddServerResponse(
      term: Long,
      success: Boolean,
      leaderHint: Option[NodeId]
  )

  /** Request to remove a server from the cluster (§6). */
  case RemoveServerRequest(
      term: Long,
      server: NodeId
  )

  /** Response to [[RemoveServerRequest]]. */
  case RemoveServerResponse(
      term: Long,
      success: Boolean,
      leaderHint: Option[NodeId]
  )

  /** Logical election timeout event. */
  case ElectionTimeout

  /** Logical heartbeat timeout event. */
  case HeartbeatTimeout

  /** Logical leader `CheckQuorum` verification event (§6.2). */
  case CheckQuorumTimeout

object RaftMessage:
  extension (msg: RaftMessage)
    def term: Option[Long] = msg match
      case m: AppendEntriesRequest      => Some(m.term)
      case m: AppendEntriesResponse     => Some(m.term)
      case m: RequestVoteRequest        => Some(m.term)
      case m: RequestVoteResponse       => Some(m.term)
      case m: InstallSnapshotRequest    => Some(m.term)
      case m: InstallSnapshotResponse   => Some(m.term)
      case m: TransferLeadershipRequest => Some(m.term)
      case m: TimeoutNowRequest         => Some(m.term)
      case m: AddServerRequest          => Some(m.term)
      case m: AddServerResponse         => Some(m.term)
      case m: RemoveServerRequest       => Some(m.term)
      case m: RemoveServerResponse      => Some(m.term)
      case _: ReadIndexRequest          => None
      case _: ReadIndexResponse         => None
      case ElectionTimeout              => None
      case HeartbeatTimeout             => None
      case CheckQuorumTimeout           => None
