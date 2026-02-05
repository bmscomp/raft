package raft.message

import raft.state.{NodeId, Log, ClusterConfig}

/**
 * Sealed hierarchy of all RAFT RPC messages.
 * 
 * Enables exhaustive pattern matching for message handling.
 */
enum RaftMessage:
  // AppendEntries
  case AppendEntriesRequest(
    term: Long,
    leaderId: NodeId,
    prevLogIndex: Long,
    prevLogTerm: Long,
    entries: Seq[Log],
    leaderCommit: Long
  )
  
  case AppendEntriesResponse(
    term: Long,
    success: Boolean,
    matchIndex: Long
  )
  
  // RequestVote
  case RequestVoteRequest(
    term: Long,
    candidateId: NodeId,
    lastLogIndex: Long,
    lastLogTerm: Long,
    isPreVote: Boolean = false
  )
  
  case RequestVoteResponse(
    term: Long,
    voteGranted: Boolean,
    isPreVote: Boolean = false
  )
  
  // InstallSnapshot for log compaction
  case InstallSnapshotRequest(
    term: Long,
    leaderId: NodeId,
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    offset: Long,
    data: Array[Byte],
    done: Boolean
  )
  
  case InstallSnapshotResponse(
    term: Long,
    success: Boolean
  )
  
  // Leadership transfer
  case TransferLeadershipRequest(
    term: Long,
    target: NodeId
  )
  
  // Linearizable reads (ReadIndex)
  case ReadIndexRequest(
    requestId: String
  )
  
  case ReadIndexResponse(
    requestId: String,
    success: Boolean,
    readIndex: Long
  )
  
  // Membership changes
  case AddServerRequest(
    term: Long,
    newServer: NodeId,
    isLearner: Boolean = false
  )
  
  case AddServerResponse(
    term: Long,
    success: Boolean,
    leaderHint: Option[NodeId]
  )
  
  case RemoveServerRequest(
    term: Long,
    server: NodeId
  )
  
  case RemoveServerResponse(
    term: Long,
    success: Boolean,
    leaderHint: Option[NodeId]
  )
  
  // Timeout events (internal)
  case ElectionTimeout
  case HeartbeatTimeout

object RaftMessage:
  import RaftMessage.*
  
  extension (msg: RaftMessage)
    def term: Option[Long] = msg match
      case m: AppendEntriesRequest   => Some(m.term)
      case m: AppendEntriesResponse  => Some(m.term)
      case m: RequestVoteRequest     => Some(m.term)
      case m: RequestVoteResponse    => Some(m.term)
      case m: InstallSnapshotRequest => Some(m.term)
      case m: InstallSnapshotResponse => Some(m.term)
      case m: TransferLeadershipRequest => Some(m.term)
      case m: AddServerRequest       => Some(m.term)
      case m: AddServerResponse      => Some(m.term)
      case m: RemoveServerRequest    => Some(m.term)
      case m: RemoveServerResponse   => Some(m.term)
      case _: ReadIndexRequest       => None
      case _: ReadIndexResponse      => None
      case ElectionTimeout           => None
      case HeartbeatTimeout          => None
