package raft.state

import scala.collection.immutable.Set as ImmutableSet

/** Unique, opaque identifier for a RAFT node in the cluster.
  *
  * Wraps a `String` value (typically a hostname, URI, or UUID) that uniquely
  * identifies a participant in the consensus group. The opaque type prevents
  * accidental mixing with arbitrary strings.
  */
opaque type NodeId = String

/** Companion for [[NodeId]] providing construction and unwrapping. */
object NodeId:
  /** Wrap a raw string as a [[NodeId]].
    *
    * @param id
    *   the unique identifier string for this node
    * @return
    *   a typed node identifier
    */
  def apply(id: String): NodeId = id

  extension (id: NodeId)
    /** Unwrap to the underlying `String` value. */
    def value: String = id

/** Sealed enumeration representing the possible states of a RAFT node.
  *
  * Each state is immutable and encapsulates all role-specific data needed for
  * consensus operations. The RAFT protocol defines four roles that a node can
  * assume during its lifecycle:
  *
  *   1. '''Follower''' — passive; accepts log entries and grants votes
  *   2. '''PreCandidate''' — running a pre-vote election (optional, prevents
  *      disruption)
  *   3. '''Candidate''' — actively seeking votes to become leader
  *   4. '''Leader''' — authoritative; replicates log entries to followers
  *
  * Pattern matching is exhaustive due to the sealed `enum` nature.
  *
  * @see
  *   [[raft.logic.RaftLogic]] for the pure state transition functions
  * @see
  *   [[raft.effect.Transition]] for the state + effects result type
  */
enum NodeState:
  /** Follower state: passive node that accepts log entries from the leader and
    * grants votes to candidates. Resets its election timer on each heartbeat
    * received from the current leader.
    *
    * @param term
    *   the current term this follower is in
    * @param votedFor
    *   the candidate this follower voted for in the current term, if any
    * @param leaderId
    *   the known leader for the current term, if any
    */
  case Follower(
      term: Long,
      votedFor: Option[NodeId] = None,
      leaderId: Option[NodeId] = None
  )

  /** PreCandidate state: node running a pre-vote election to check if it can
    * win before starting a real election. Does NOT increment the term, which
    * prevents network-partitioned nodes from disrupting stable clusters with
    * ever-increasing terms.
    *
    * @param term
    *   the current term (not incremented during pre-vote)
    * @param preVotesReceived
    *   set of node IDs that granted a pre-vote
    */
  case PreCandidate(
      term: Long,
      preVotesReceived: ImmutableSet[NodeId] = ImmutableSet.empty
  )

  /** Candidate state: node actively running an election to become the leader.
    * Increments the term, votes for itself, and broadcasts `RequestVoteRequest`
    * to all peers. Accumulates votes until a majority is reached or the
    * election times out.
    *
    * @param term
    *   the election term (incremented from previous term)
    * @param votesReceived
    *   set of node IDs that granted a vote
    */
  case Candidate(
      term: Long,
      votesReceived: ImmutableSet[NodeId] = ImmutableSet.empty
  )

  /** Leader state: authoritative node that replicates log entries to followers.
    * Maintains per-follower replication progress (`nextIndex`, `matchIndex`)
    * and tracks the highest committed log index.
    *
    * @param term
    *   the term in which this node became leader
    * @param nextIndex
    *   for each follower, the next log index to send
    * @param matchIndex
    *   for each follower, the highest log index known to be replicated
    * @param commitIndex
    *   the highest log entry index known to be committed
    */
  case Leader(
      term: Long,
      nextIndex: Map[NodeId, Long] = Map.empty,
      matchIndex: Map[NodeId, Long] = Map.empty,
      commitIndex: Long = 0L
  )

/** Companion for [[NodeState]] providing extension methods for state
  * transitions and role-specific operations.
  *
  * @see
  *   [[raft.logic.RaftLogic]] for the functions that produce these transitions
  */
object NodeState:
  import NodeState.*

  /** Extract the current term from any node state.
    *
    * @return
    *   the term value common to all state variants
    */
  extension (state: NodeState)
    def term: Long = state match
      case Follower(t, _, _)  => t
      case PreCandidate(t, _) => t
      case Candidate(t, _)    => t
      case Leader(t, _, _, _) => t

  /** Transition to Follower state when discovering a higher term from another
    * node.
    *
    * This implements the RAFT rule: "If RPC request or response contains term T
    * > currentTerm, set currentTerm = T and convert to follower."
    *
    * @param newTerm
    *   the higher term discovered from a peer
    * @return
    *   a fresh Follower state at the new term with no vote or known leader
    */
  extension (state: NodeState)
    def stepDown(newTerm: Long): Follower =
      Follower(newTerm, None, None)

  /** PreCandidate-specific operations for pre-vote tracking.
    *
    * @see
    *   [[NodeState.PreCandidate]]
    */
  extension (state: PreCandidate)
    /** Record a pre-vote grant from a peer.
      *
      * @param voterId
      *   the node that granted the pre-vote
      * @return
      *   an updated PreCandidate with the voter added
      */
    def withPreVote(voterId: NodeId): PreCandidate =
      state.copy(preVotesReceived = state.preVotesReceived + voterId)

    /** Check whether a pre-vote majority has been achieved.
      *
      * @param clusterSize
      *   the total number of voting members in the cluster
      * @return
      *   `true` if more than half the cluster has granted pre-votes
      */
    def hasPreVoteMajority(clusterSize: Int): Boolean =
      state.preVotesReceived.size > clusterSize / 2

  /** Candidate-specific operations for vote tracking and majority detection.
    *
    * @see
    *   [[NodeState.Candidate]]
    */
  extension (state: Candidate)
    /** Record a vote grant from a peer.
      *
      * @param voterId
      *   the node that granted the vote
      * @return
      *   an updated Candidate with the voter added
      */
    def withVote(voterId: NodeId): Candidate =
      state.copy(votesReceived = state.votesReceived + voterId)

    /** Check whether an election majority has been achieved.
      *
      * @param clusterSize
      *   the total number of voting members in the cluster
      * @return
      *   `true` if more than half the cluster has granted votes
      */
    def hasMajority(clusterSize: Int): Boolean =
      state.votesReceived.size > clusterSize / 2

  /** Leader-specific operations for replication tracking and commit
    * advancement.
    *
    * @see
    *   [[NodeState.Leader]]
    */
  extension (state: Leader)
    /** Update the next log index to send to a follower.
      *
      * @param followerId
      *   the follower whose nextIndex is being updated
      * @param index
      *   the new nextIndex value
      * @return
      *   an updated Leader state
      */
    def withNextIndex(followerId: NodeId, index: Long): Leader =
      state.copy(nextIndex = state.nextIndex.updated(followerId, index))

    /** Update the highest replicated index for a follower.
      *
      * @param followerId
      *   the follower whose matchIndex is being updated
      * @param index
      *   the new matchIndex value
      * @return
      *   an updated Leader state
      */
    def withMatchIndex(followerId: NodeId, index: Long): Leader =
      state.copy(matchIndex = state.matchIndex.updated(followerId, index))

    /** Update the commit index tracked by the leader.
      *
      * @param index
      *   the new commit index
      * @return
      *   an updated Leader state
      */
    def withCommitIndex(index: Long): Leader =
      state.copy(commitIndex = index)

    /** Initialize replication indices when first becoming leader.
      *
      * Sets `nextIndex = lastLogIndex + 1` and `matchIndex = 0` for every
      * follower, as specified by the RAFT protocol's leader initialization
      * rule.
      *
      * @param followers
      *   the set of follower node IDs to track
      * @param lastLogIndex
      *   the index of the last entry in the leader's log
      * @return
      *   an updated Leader state with initialized tracking maps
      */
    def initializeIndices(followers: Set[NodeId], lastLogIndex: Long): Leader =
      val next = followers.map(_ -> (lastLogIndex + 1)).toMap
      val matched = followers.map(_ -> 0L).toMap
      state.copy(nextIndex = next, matchIndex = matched)

    /** Calculate the new commit index using the median of matchIndex values.
      *
      * Only advances the commit index if the entry at the candidate index was
      * created in the current term. This is the RAFT safety property that
      * prevents committing entries from previous terms by counting replicas
      * alone.
      *
      * @param clusterSize
      *   the total number of voting members in the cluster
      * @param getTermAt
      *   a callback to look up the term of the entry at a given index
      * @return
      *   the new commit index (may be unchanged if no advancement is possible)
      */
    def calculateCommitIndex(
        clusterSize: Int,
        getTermAt: Long => Option[Long]
    ): Long =
      val allIndices = state.matchIndex.values.toSeq :+ state.commitIndex
      if allIndices.isEmpty then state.commitIndex
      else
        val sorted = allIndices.sorted.reverse
        val majorityIndex = (clusterSize + 1) / 2 - 1
        val candidateIndex =
          sorted.lift(majorityIndex).getOrElse(state.commitIndex)
        if candidateIndex > state.commitIndex && getTermAt(candidateIndex)
            .contains(state.term)
        then candidateIndex
        else state.commitIndex
