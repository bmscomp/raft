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
  * Raft (§5.1) defines a strict role lifecycle that every node follows:
  * {{{Follower → PreCandidate → Candidate → Leader → (step-down) → Follower}}}
  *
  * At any given moment a node is in exactly one of these roles, and every role
  * transition is driven by a protocol event (election timeout, vote majority,
  * higher-term message). This design ensures the '''Election Safety''' property
  * (§5.2): at most one leader can be elected in any given term.
  *
  * The four roles are:
  *   - '''Follower''' — the default, passive state. Accepts log entries from
  *     the leader and grants votes to candidates. A node starts as Follower and
  *     steps down to Follower whenever it discovers a higher term.
  *   - '''PreCandidate''' — an optional optimization (§9.6, Pre-Vote) that
  *     prevents partitioned nodes from disrupting a stable cluster by
  *     incrementing their term. The PreCandidate asks peers whether they
  *     ''would'' vote before committing to a real election.
  *   - '''Candidate''' — a node actively seeking leadership. Increments its
  *     term, votes for itself, and solicits votes from all peers. Becomes
  *     Leader upon receiving a majority.
  *   - '''Leader''' — the authoritative node that manages replication. Sends
  *     heartbeats, accepts client commands, and tracks per-follower `nextIndex`
  *     / `matchIndex` for log synchronization.
  *
  * Each variant is immutable, carrying only the data relevant to its role. This
  * purity is central to the library's design: [[raft.logic.RaftLogic]] computes
  * transitions as pure functions `(NodeState, Message) → Transition`, making
  * the consensus logic fully deterministic and testable.
  *
  * @see
  *   [[raft.logic.RaftLogic]] for the pure state transition functions
  * @see
  *   [[raft.effect.Transition]] for the state + effects result type
  */
enum NodeState:
  /** Follower state — the default role in the Raft protocol (§5.2).
    *
    * A node begins as Follower and returns to this state whenever it discovers
    * a higher term from any peer. Followers are purely passive: they respond to
    * RPCs but never initiate communication. They accept `AppendEntries` from
    * the current leader and grant `RequestVote` to candidates with up-to-date
    * logs.
    *
    * The Follower resets its election timer on every valid heartbeat. If the
    * timer expires without hearing from a leader, the node transitions to
    * PreCandidate (or Candidate if pre-vote is disabled).
    *
    * @param term
    *   the current term this follower has observed
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

  /** PreCandidate state — the Pre-Vote optimization (§9.6, Ongaro thesis).
    *
    * Without Pre-Vote, a node isolated by a network partition will repeatedly
    * time out, increment its term, and start elections nobody can win. When the
    * partition heals, its inflated term forces the legitimate leader to step
    * down — a disruptive and unnecessary leadership change.
    *
    * Pre-Vote solves this by adding a speculative round: the PreCandidate asks
    * ''"would you vote for me?"'' without incrementing its term. Only if a
    * majority says yes does the node proceed to a real election. A partitioned
    * node will never get a pre-vote majority and therefore never disrupts the
    * cluster.
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

  /** Candidate state — a node actively seeking leadership (§5.2).
    *
    * Upon entering this state the node increments its term, votes for itself,
    * and broadcasts `RequestVoteRequest` to all peers. The Candidate wins the
    * election if it receives votes from a majority of the cluster (ensuring the
    * Election Safety Property: at most one leader per term).
    *
    * Three outcomes end a Candidate's election:
    *   1. ''Win'' — majority votes received → become Leader
    *   1. ''Lose'' — receives `AppendEntries` from a leader with `≥ term` →
    *      step down to Follower
    *   1. ''Timeout'' — no majority within the election timeout → start a new
    *      election with an incremented term
    *
    * @param term
    *   the election term (incremented from the previous term)
    * @param votesReceived
    *   set of node IDs that granted a vote
    */
  case Candidate(
      term: Long,
      votesReceived: ImmutableSet[NodeId] = ImmutableSet.empty
  )

  /** Leader state — the authoritative node managing the cluster (§5.3).
    *
    * Upon election the Leader initializes `nextIndex` for every follower to
    * `lastLogIndex + 1` and `matchIndex` to 0 (the "optimistic start"). It then
    * sends heartbeats at a fixed interval to prevent election timeouts, and
    * replicates client commands via `AppendEntries` RPCs.
    *
    * The Leader advances `commitIndex` when an entry has been replicated to a
    * majority ''and'' the entry's term equals the Leader's current term (the
    * ''Leader Completeness Property'', §5.4). This term check prevents the
    * scenario illustrated in Figure 8 of the Raft paper, where a leader commits
    * entries from a previous term that could be overwritten.
    *
    * The Leader never deletes or overwrites entries in its own log — it only
    * appends. It is the single source of truth for the replicated log.
    *
    * @param term
    *   the term in which this node was elected leader
    * @param nextIndex
    *   per-follower map: next log index to send to each follower
    * @param matchIndex
    *   per-follower map: highest log index known to be replicated
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
  * State-specific operations (vote counting, majority detection, index
  * initialization) are implemented as extension methods rather than being
  * embedded in the enum variants. This keeps the data classes minimal and moves
  * logic closer to the call site in [[raft.logic.RaftLogic]].
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
