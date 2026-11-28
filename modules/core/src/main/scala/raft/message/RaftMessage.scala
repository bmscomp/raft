package raft.message

import raft.state.{Bytes, Log, NodeId}

/** Sealed algebraic hierarchy of all RAFT RPC messages and logical timer
  * events.
  *
  * Every variant is deeply immutable and satisfies structural value equality
  * (`==`) and hashing — including [[RaftMessage.InstallSnapshotRequest]], which
  * wraps snapshot chunk bytes inside [[raft.state.Bytes]].
  */
sealed trait RaftMessage extends Product with Serializable

object RaftMessage:

  /** Replicate log entries or send a heartbeat (§5.3). */
  final case class AppendEntriesRequest(
      term: Long,
      leaderId: NodeId,
      prevLogIndex: Long,
      prevLogTerm: Long,
      entries: Seq[Log],
      leaderCommit: Long,
      heartbeatRound: Long = 0L
  ) extends RaftMessage

  /** Response to [[AppendEntriesRequest]] with optional §5.3 fast-backtracking
    * conflict hints (`conflictIndex` and `conflictTerm`).
    */
  final case class AppendEntriesResponse(
      term: Long,
      success: Boolean,
      matchIndex: Long,
      conflictIndex: Option[Long] = None,
      conflictTerm: Option[Long] = None,
      heartbeatRound: Long = 0L
  ) extends RaftMessage

  /** Request a vote or speculative pre-vote (§5.2, §9.6, §3.10). */
  final case class RequestVoteRequest(
      term: Long,
      candidateId: NodeId,
      lastLogIndex: Long,
      lastLogTerm: Long,
      isPreVote: Boolean = false,
      isTransfer: Boolean = false
  ) extends RaftMessage

  /** Response to [[RequestVoteRequest]]. */
  final case class RequestVoteResponse(
      term: Long,
      voteGranted: Boolean,
      isPreVote: Boolean = false
  ) extends RaftMessage

  /** Stream an immutable snapshot chunk from leader to lagging follower (§7).
    */
  final case class InstallSnapshotRequest(
      term: Long,
      leaderId: NodeId,
      lastIncludedIndex: Long,
      lastIncludedTerm: Long,
      offset: Long,
      payload: Bytes,
      done: Boolean
  ) extends RaftMessage:
    /** Defensive `Array[Byte]` copy of [[payload]]. */
    def data: Array[Byte] = payload.toArray

  object InstallSnapshotRequest:
    def apply(
        term: Long,
        leaderId: NodeId,
        lastIncludedIndex: Long,
        lastIncludedTerm: Long,
        offset: Long,
        data: Array[Byte],
        done: Boolean
    ): InstallSnapshotRequest =
      new InstallSnapshotRequest(
        term = term,
        leaderId = leaderId,
        lastIncludedIndex = lastIncludedIndex,
        lastIncludedTerm = lastIncludedTerm,
        offset = offset,
        payload = Bytes.copyFrom(data),
        done = done
      )

  /** Response to [[InstallSnapshotRequest]]. */
  final case class InstallSnapshotResponse(
      term: Long,
      success: Boolean,
      matchIndex: Long = 0L
  ) extends RaftMessage

  /** Administrative request on leader to transfer leadership to `target`
    * (§3.10).
    */
  final case class TransferLeadershipRequest(
      term: Long,
      target: NodeId
  ) extends RaftMessage

  /** Direct RPC from departing leader to `target` once caught up, instructing
    * `target` to start an immediate election (`isTransfer = true`).
    */
  final case class TimeoutNowRequest(
      term: Long,
      leaderId: NodeId
  ) extends RaftMessage

  /** Client request for a linearizable read via the `ReadIndex` protocol
    * (§6.4).
    */
  final case class ReadIndexRequest(
      requestId: String
  ) extends RaftMessage

  /** Response to [[ReadIndexRequest]]. */
  final case class ReadIndexResponse(
      requestId: String,
      success: Boolean,
      readIndex: Long
  ) extends RaftMessage

  /** Request to add a server (`Voter` or `Learner`) to the cluster (§6). */
  final case class AddServerRequest(
      term: Long,
      newServer: NodeId,
      isLearner: Boolean = false
  ) extends RaftMessage

  /** Response to [[AddServerRequest]]. */
  final case class AddServerResponse(
      term: Long,
      success: Boolean,
      leaderHint: Option[NodeId]
  ) extends RaftMessage

  /** Request to remove a server from the cluster (§6). */
  final case class RemoveServerRequest(
      term: Long,
      server: NodeId
  ) extends RaftMessage

  /** Response to [[RemoveServerRequest]]. */
  final case class RemoveServerResponse(
      term: Long,
      success: Boolean,
      leaderHint: Option[NodeId]
  ) extends RaftMessage

  /** Logical election timeout event. */
  case object ElectionTimeout extends RaftMessage

  /** Logical heartbeat timeout event. */
  case object HeartbeatTimeout extends RaftMessage

  /** Logical leader `CheckQuorum` verification event (§6.2). */
  case object CheckQuorumTimeout extends RaftMessage

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
