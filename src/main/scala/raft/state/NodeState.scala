package raft.state

import scala.collection.immutable.Set as ImmutableSet

/** Unique, opaque identifier for a RAFT node in the cluster. */
opaque type NodeId = String

object NodeId:
  def apply(id: String): NodeId = id
  extension (id: NodeId) def value: String = id

/** Sealed enumeration representing the possible states of a RAFT node.
  *
  * Each state is immutable and encapsulates all role-specific data needed for
  * consensus operations. Pattern matching is exhaustive due to the sealed nature.
  */
enum NodeState:
  /** Follower state: passive node that accepts log entries from the leader
    * and grants votes to candidates. Waits for heartbeats from current leader.
    */
  case Follower(
    term: Long,
    votedFor: Option[NodeId] = None,
    leaderId: Option[NodeId] = None
  )
  
  /** PreCandidate state: node running a pre-vote election to check if it can
    * win before starting a real election. Does NOT increment term.
    * This prevents network-partitioned nodes from disrupting stable clusters.
    */
  case PreCandidate(
    term: Long,
    preVotesReceived: ImmutableSet[NodeId] = ImmutableSet.empty
  )
  
  /** Candidate state: node actively running an election to become the leader.
    * Accumulates votes from other nodes until majority is reached or timeout.
    */
  case Candidate(
    term: Long,
    votesReceived: ImmutableSet[NodeId] = ImmutableSet.empty
  )
  
  /** Leader state: authoritative node that replicates log entries to followers.
    * Tracks replication progress (nextIndex, matchIndex) and commit position.
    */
  case Leader(
    term: Long,
    nextIndex: Map[NodeId, Long] = Map.empty,
    matchIndex: Map[NodeId, Long] = Map.empty,
    commitIndex: Long = 0L
  )

object NodeState:
  import NodeState.*
  
  /** Extract the current term from any node state. */
  extension (state: NodeState)
    def term: Long = state match
      case Follower(t, _, _)       => t
      case PreCandidate(t, _)      => t
      case Candidate(t, _)         => t
      case Leader(t, _, _, _)      => t
  
  /** Transition to Follower state when discovering a higher term from another node. */
  extension (state: NodeState)
    def stepDown(newTerm: Long): Follower =
      Follower(newTerm, None, None)
  
  /** PreCandidate-specific operations for pre-vote tracking. */
  extension (state: PreCandidate)
    def withPreVote(voterId: NodeId): PreCandidate =
      state.copy(preVotesReceived = state.preVotesReceived + voterId)
    
    def hasPreVoteMajority(clusterSize: Int): Boolean =
      state.preVotesReceived.size > clusterSize / 2
  
  /** Candidate-specific operations for vote tracking and majority detection. */
  extension (state: Candidate)
    def withVote(voterId: NodeId): Candidate =
      state.copy(votesReceived = state.votesReceived + voterId)
    
    def hasMajority(clusterSize: Int): Boolean =
      state.votesReceived.size > clusterSize / 2
  
  /** Leader-specific operations for replication tracking and commit advancement. */
  extension (state: Leader)
    def withNextIndex(followerId: NodeId, index: Long): Leader =
      state.copy(nextIndex = state.nextIndex.updated(followerId, index))
    
    def withMatchIndex(followerId: NodeId, index: Long): Leader =
      state.copy(matchIndex = state.matchIndex.updated(followerId, index))
    
    def withCommitIndex(index: Long): Leader =
      state.copy(commitIndex = index)
    
    /** Initialize replication indices when becoming leader: nextIndex = lastLog+1, matchIndex = 0. */
    def initializeIndices(followers: Set[NodeId], lastLogIndex: Long): Leader =
      val next = followers.map(_ -> (lastLogIndex + 1)).toMap
      val matched = followers.map(_ -> 0L).toMap
      state.copy(nextIndex = next, matchIndex = matched)
    
    /** Calculate the new commit index using the median of matchIndex values.
      * Only advances if the entry at that index is from the current term (RAFT safety).
      */
    def calculateCommitIndex(clusterSize: Int, getTermAt: Long => Option[Long]): Long =
      val allIndices = state.matchIndex.values.toSeq :+ state.commitIndex
      if allIndices.isEmpty then state.commitIndex
      else
        val sorted = allIndices.sorted.reverse
        val majorityIndex = (clusterSize + 1) / 2 - 1
        val candidateIndex = sorted.lift(majorityIndex).getOrElse(state.commitIndex)
        if candidateIndex > state.commitIndex && getTermAt(candidateIndex).contains(state.term) then
          candidateIndex
        else
          state.commitIndex
