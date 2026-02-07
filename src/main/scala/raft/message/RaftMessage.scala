package raft.message

import raft.state.{NodeId, Log, ClusterConfig}

/** Sealed hierarchy of all RAFT RPC messages and internal timeout events.
  *
  * Every network interaction and timer event in the protocol is represented as
  * a case of this enum, guaranteeing exhaustive pattern matching in
  * [[raft.logic.RaftLogic.onMessage]].
  *
  * Messages are grouped into:
  *   - '''AppendEntries''' — log replication and heartbeats
  *   - '''RequestVote''' — leader election (including pre-vote)
  *   - '''InstallSnapshot''' — log compaction transfer
  *   - '''Leadership Transfer''' — graceful leader handoff
  *   - '''ReadIndex''' — linearizable read protocol
  *   - '''Membership Changes''' — dynamic cluster reconfiguration
  *   - '''Internal Events''' — election and heartbeat timeouts
  *
  * @see
  *   [[raft.logic.RaftLogic]] for the pure transition functions that handle
  *   each message
  * @see
  *   [[raft.effect.Effect.SendMessage]] for the effect that sends messages
  */
enum RaftMessage:

  /** Request from the leader to append log entries to a follower's log.
    *
    * Also serves as a heartbeat when `entries` is empty.
    *
    * @param term
    *   the leader's current term
    * @param leaderId
    *   the leader's node identifier (for follower redirection)
    * @param prevLogIndex
    *   index of the log entry immediately preceding the new entries
    * @param prevLogTerm
    *   term of the entry at `prevLogIndex` (for consistency check)
    * @param entries
    *   log entries to replicate (empty for heartbeats)
    * @param leaderCommit
    *   the leader's current commit index
    */
  case AppendEntriesRequest(
      term: Long,
      leaderId: NodeId,
      prevLogIndex: Long,
      prevLogTerm: Long,
      entries: Seq[Log],
      leaderCommit: Long
  )

  /** Response to an [[AppendEntriesRequest]].
    *
    * @param term
    *   the responder's current term (for leader to detect stale term)
    * @param success
    *   `true` if the follower's log matched at `prevLogIndex`/`prevLogTerm`
    * @param matchIndex
    *   the highest log index replicated on the follower (valid when `success`)
    */
  case AppendEntriesResponse(
      term: Long,
      success: Boolean,
      matchIndex: Long
  )

  /** Request from a candidate (or pre-candidate) asking for a vote.
    *
    * @param term
    *   the candidate's term (or prospective term for pre-votes)
    * @param candidateId
    *   the candidate requesting the vote
    * @param lastLogIndex
    *   index of the candidate's last log entry
    * @param lastLogTerm
    *   term of the candidate's last log entry
    * @param isPreVote
    *   `true` if this is a pre-vote (does not bind the voter)
    */
  case RequestVoteRequest(
      term: Long,
      candidateId: NodeId,
      lastLogIndex: Long,
      lastLogTerm: Long,
      isPreVote: Boolean = false
  )

  /** Response to a [[RequestVoteRequest]].
    *
    * @param term
    *   the voter's current term
    * @param voteGranted
    *   `true` if the voter grants its vote to the candidate
    * @param isPreVote
    *   `true` if this responds to a pre-vote request
    */
  case RequestVoteResponse(
      term: Long,
      voteGranted: Boolean,
      isPreVote: Boolean = false
  )

  /** Request from the leader to install a snapshot on a lagging follower.
    *
    * Snapshots are transferred in chunks. The follower reassembles chunks and
    * replaces its log up to the snapshot's last included index.
    *
    * @param term
    *   the leader's current term
    * @param leaderId
    *   the leader's node identifier
    * @param lastIncludedIndex
    *   the last log index covered by the snapshot
    * @param lastIncludedTerm
    *   the term of the last included log entry
    * @param offset
    *   byte offset of this chunk within the snapshot data
    * @param data
    *   raw snapshot chunk bytes
    * @param done
    *   `true` if this is the final chunk
    */
  case InstallSnapshotRequest(
      term: Long,
      leaderId: NodeId,
      lastIncludedIndex: Long,
      lastIncludedTerm: Long,
      offset: Long,
      data: Array[Byte],
      done: Boolean
  )

  /** Response to an [[InstallSnapshotRequest]].
    *
    * @param term
    *   the responder's current term
    * @param success
    *   `true` if the chunk was accepted
    */
  case InstallSnapshotResponse(
      term: Long,
      success: Boolean
  )

  /** Request from the current leader to transfer leadership to a target node.
    *
    * @param term
    *   the leader's current term
    * @param target
    *   the node that should become the next leader
    * @see
    *   [[raft.effect.Effect.TransferLeadership]]
    */
  case TransferLeadershipRequest(
      term: Long,
      target: NodeId
  )

  /** Client request for a linearizable read via the ReadIndex protocol.
    *
    * @param requestId
    *   unique identifier for this read request, used to correlate the response
    * @see
    *   [[ReadIndexResponse]]
    */
  case ReadIndexRequest(
      requestId: String
  )

  /** Response to a [[ReadIndexRequest]].
    *
    * @param requestId
    *   the identifier of the original read request
    * @param success
    *   `true` if the leader confirmed its authority
    * @param readIndex
    *   the commit index at which the read can be safely served
    */
  case ReadIndexResponse(
      requestId: String,
      success: Boolean,
      readIndex: Long
  )

  /** Request to add a new server to the cluster.
    *
    * @param term
    *   the leader's current term
    * @param newServer
    *   the node identifier of the server to add
    * @param isLearner
    *   `true` to add as a non-voting learner, `false` for a full voter
    * @see
    *   [[raft.state.ClusterConfig.addMember]]
    */
  case AddServerRequest(
      term: Long,
      newServer: NodeId,
      isLearner: Boolean = false
  )

  /** Response to an [[AddServerRequest]].
    *
    * @param term
    *   the responder's current term
    * @param success
    *   `true` if the membership change was accepted
    * @param leaderHint
    *   the known leader's ID for client redirection on failure
    */
  case AddServerResponse(
      term: Long,
      success: Boolean,
      leaderHint: Option[NodeId]
  )

  /** Request to remove a server from the cluster.
    *
    * @param term
    *   the leader's current term
    * @param server
    *   the node identifier of the server to remove
    * @see
    *   [[raft.state.ClusterConfig.removeMember]]
    */
  case RemoveServerRequest(
      term: Long,
      server: NodeId
  )

  /** Response to a [[RemoveServerRequest]].
    *
    * @param term
    *   the responder's current term
    * @param success
    *   `true` if the membership change was accepted
    * @param leaderHint
    *   the known leader's ID for client redirection on failure
    */
  case RemoveServerResponse(
      term: Long,
      success: Boolean,
      leaderHint: Option[NodeId]
  )

  /** Internal event indicating that the election timer has fired.
    *
    * Triggers a transition to Candidate (or PreCandidate if pre-vote is
    * enabled).
    */
  case ElectionTimeout

  /** Internal event indicating that the heartbeat timer has fired.
    *
    * Triggers the leader to send heartbeats to all followers.
    */
  case HeartbeatTimeout

/** Companion for [[RaftMessage]] providing extension methods.
  *
  * @see
  *   [[raft.logic.RaftLogic]] for message handling
  */
object RaftMessage:
  import RaftMessage.*

  /** Extract the protocol term from a message, if present.
    *
    * Internal timeout events (`ElectionTimeout`, `HeartbeatTimeout`) and
    * `ReadIndex` messages do not carry a term.
    *
    * @return
    *   `Some(term)` for RPC messages, `None` for internal events and read
    *   requests
    */
  extension (msg: RaftMessage)
    def term: Option[Long] = msg match
      case m: AppendEntriesRequest      => Some(m.term)
      case m: AppendEntriesResponse     => Some(m.term)
      case m: RequestVoteRequest        => Some(m.term)
      case m: RequestVoteResponse       => Some(m.term)
      case m: InstallSnapshotRequest    => Some(m.term)
      case m: InstallSnapshotResponse   => Some(m.term)
      case m: TransferLeadershipRequest => Some(m.term)
      case m: AddServerRequest          => Some(m.term)
      case m: AddServerResponse         => Some(m.term)
      case m: RemoveServerRequest       => Some(m.term)
      case m: RemoveServerResponse      => Some(m.term)
      case _: ReadIndexRequest          => None
      case _: ReadIndexResponse         => None
      case ElectionTimeout              => None
      case HeartbeatTimeout             => None
