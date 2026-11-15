package raft.state

import scala.collection.immutable.Set as ImmutableSet

/** Unique, opaque identifier for a RAFT node in the cluster. */
opaque type NodeId = String

object NodeId:
  def apply(id: String): NodeId = id

  extension (id: NodeId) def value: String = id

/** Pure, immutable consensus role state machine (§5.1). */
enum NodeState:
  /** Passive follower state (§5.2). */
  case Follower(
      term: Long,
      votedFor: Option[NodeId] = None,
      leaderId: Option[NodeId] = None
  )

  /** Speculative Pre-Vote state (§9.6) — never increments persistent term. */
  case PreCandidate(
      term: Long,
      preVotesReceived: ImmutableSet[NodeId] = ImmutableSet.empty
  )

  /** Active election candidate state (§5.2). */
  case Candidate(
      term: Long,
      votesReceived: ImmutableSet[NodeId] = ImmutableSet.empty
  )

  /** Authoritative cluster leader state (§5.3, §6.4, §3.10).
    *
    * @param term
    *   the term in which this node was elected leader
    * @param nextIndex
    *   per-peer map of the next log index to send
    * @param matchIndex
    *   per-peer map of the highest log index known to be replicated
    * @param commitIndex
    *   the highest log entry index known to be committed
    * @param transferTarget
    *   optional target `NodeId` during an active leadership transfer (§3.10)
    * @param pendingReads
    *   in-flight `ReadIndex` requests awaiting heartbeat quorum confirmation
    * @param leaseUntil
    *   logical tick/timestamp until which leader lease is valid
    * @param recentlyActive
    *   peers that responded during the current `CheckQuorum` window
    */
  case Leader(
      term: Long,
      nextIndex: Map[NodeId, Long] = Map.empty,
      matchIndex: Map[NodeId, Long] = Map.empty,
      commitIndex: Long = 0L,
      transferTarget: Option[NodeId] = None,
      pendingReads: Map[String, PendingRead] = Map.empty,
      leaseUntil: Long = 0L,
      recentlyActive: ImmutableSet[NodeId] = ImmutableSet.empty
  )

object NodeState:
  extension (state: NodeState)
    def term: Long = state match
      case Follower(t, _, _)              => t
      case PreCandidate(t, _)             => t
      case Candidate(t, _)                => t
      case Leader(t, _, _, _, _, _, _, _) => t

    def stepDown(newTerm: Long): Follower =
      Follower(newTerm, None, None)

  extension (state: PreCandidate)
    def withPreVote(voterId: NodeId): PreCandidate =
      state.copy(preVotesReceived = state.preVotesReceived + voterId)

    def hasPreVoteMajority(clusterSize: Int): Boolean =
      state.preVotesReceived.size > clusterSize / 2

    def hasPreVoteQuorum(cluster: ClusterConfig): Boolean =
      cluster.hasQuorum(state.preVotesReceived)

  extension (state: Candidate)
    def withVote(voterId: NodeId): Candidate =
      state.copy(votesReceived = state.votesReceived + voterId)

    def hasMajority(clusterSize: Int): Boolean =
      state.votesReceived.size > clusterSize / 2

    def hasQuorum(cluster: ClusterConfig): Boolean =
      cluster.hasQuorum(state.votesReceived)

  extension (state: Leader)
    def withNextIndex(followerId: NodeId, index: Long): Leader =
      state.copy(nextIndex = state.nextIndex.updated(followerId, index))

    def withMatchIndex(followerId: NodeId, index: Long): Leader =
      state.copy(matchIndex = state.matchIndex.updated(followerId, index))

    def withCommitIndex(index: Long): Leader =
      state.copy(commitIndex = index)

    def initializeIndices(followers: Set[NodeId], lastLogIndex: Long): Leader =
      val next = followers.map(_ -> (lastLogIndex + 1)).toMap
      val matched = followers.map(_ -> 0L).toMap
      state.copy(nextIndex = next, matchIndex = matched)

    /** Compute the highest commit index replicated on a majority of voters
      * whose entry term equals `state.term` (Raft §5.4.2).
      *
      * Includes the leader's own `leaderLastIndex` alongside follower
      * `matchIndex` entries so a 3-node cluster with 1 failed follower still
      * advances `commitIndex` once the leader + 1 follower hold the entry.
      */
    def calculateCommitIndex(
        clusterSize: Int,
        getTermAt: Long => Option[Long],
        leaderLastIndex: Long = -1L
    ): Long =
      if clusterSize <= 0 then state.commitIndex
      else if clusterSize == 1 then
        val effectiveSelf =
          if leaderLastIndex >= 0L then leaderLastIndex
          else state.matchIndex.values.maxOption.getOrElse(state.commitIndex)
        (effectiveSelf to (state.commitIndex + 1L) by -1L)
          .find(idx => getTermAt(idx).contains(state.term))
          .getOrElse(state.commitIndex)
      else
        val inferredSelf =
          if leaderLastIndex >= 0L then leaderLastIndex
          else state.matchIndex.values.maxOption.getOrElse(state.commitIndex)
        val followerIndices = state.matchIndex.values.toSeq
        val allIndices =
          if followerIndices.size >= clusterSize then followerIndices
          else followerIndices :+ inferredSelf
        val sorted = allIndices.sorted(Ordering[Long].reverse)
        val majorityPos = (clusterSize / 2)
        val quorumReplicated =
          sorted.lift(majorityPos).getOrElse(state.commitIndex)
        (quorumReplicated to (state.commitIndex + 1L) by -1L)
          .find(idx => getTermAt(idx).contains(state.term))
          .getOrElse(state.commitIndex)

    /** Joint-consensus-aware commit calculation using [[ClusterConfig]]. */
    def calculateCommitIndexForCluster(
        localId: NodeId,
        cluster: ClusterConfig,
        leaderLastIndex: Long,
        getTermAt: Long => Option[Long]
    ): Long =
      def medianFor(voterSet: Set[NodeId]): Long =
        if voterSet.isEmpty then state.commitIndex
        else
          val indices = voterSet.toSeq.map { id =>
            if id == localId then leaderLastIndex
            else state.matchIndex.getOrElse(id, 0L)
          }
          val sorted = indices.sorted(Ordering[Long].reverse)
          val majPos = voterSet.size / 2
          sorted.lift(majPos).getOrElse(state.commitIndex)

      val newCommit = medianFor(cluster.newVoters)
      val quorumIndex = cluster.oldVoters match
        case None      => newCommit
        case Some(old) => math.min(newCommit, medianFor(old))

      (quorumIndex to (state.commitIndex + 1L) by -1L)
        .find(idx => getTermAt(idx).contains(state.term))
        .getOrElse(state.commitIndex)
