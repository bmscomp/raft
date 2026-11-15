package raft.logic

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.*
import raft.effect.Effect.*

/** Pure, deterministic RAFT state transition functions — the Functional Core of
  * the consensus engine.
  *
  * Every transition is 100% referentially transparent: zero mutable state, zero
  * wall-clock calls, zero exceptions for control flow, and zero I/O imports.
  */
object RaftLogic:

  /** Primary pure message dispatcher.
    *
    * @param state
    *   current node role and consensus state
    * @param message
    *   incoming RPC or logical timeout event
    * @param config
    *   local node protocol configuration
    * @param lastLogIndex
    *   index of the last entry in the local log (or snapshot index if empty)
    * @param lastLogTerm
    *   term of the last entry in the local log
    * @param clusterSize
    *   voting cluster size (used when `clusterOpt` is not supplied)
    * @param getTermAt
    *   synchronous lookup returning `Some(term)` at a given 1-based log index
    * @param sender
    *   optional authenticated sender `NodeId` for response deduplication and
    *   follower progress tracking
    * @param clusterOpt
    *   optional full [[ClusterConfig]] enabling Joint Consensus, Learners, and
    *   Witnesses
    */
  def onMessage(
      state: NodeState,
      message: RaftMessage,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      clusterSize: Int,
      getTermAt: Long => Option[Long] = _ => None,
      sender: Option[NodeId] = None,
      clusterOpt: Option[ClusterConfig] = None
  ): Transition =
    message match
      case req: AppendEntriesRequest =>
        onAppendEntries(state, req, config, lastLogIndex, getTermAt)

      case resp: AppendEntriesResponse =>
        onAppendEntriesResponse(
          state,
          resp,
          config,
          lastLogIndex,
          clusterSize,
          getTermAt,
          sender,
          clusterOpt
        )

      case req: RequestVoteRequest =>
        onRequestVote(state, req, config, lastLogIndex, lastLogTerm)

      case resp: RequestVoteResponse =>
        sender match
          case Some(voterId) =>
            onVoteResponse(
              state,
              voterId,
              resp,
              config,
              clusterSize,
              lastLogIndex,
              lastLogTerm,
              clusterOpt
            )
          case None =>
            onAnonymousVoteResponse(
              state,
              resp,
              config,
              clusterSize,
              lastLogIndex,
              lastLogTerm,
              clusterOpt
            )

      case req: InstallSnapshotRequest =>
        onInstallSnapshot(state, req, lastLogIndex, getTermAt)

      case resp: InstallSnapshotResponse =>
        onInstallSnapshotResponse(state, resp, sender)

      case req: ReadIndexRequest =>
        onReadIndex(state, req, config, clusterSize, getTermAt, clusterOpt)

      case req: TransferLeadershipRequest =>
        onTransferLeadership(
          state,
          req,
          config,
          lastLogIndex,
          getTermAt,
          clusterOpt
        )

      case req: TimeoutNowRequest =>
        onTimeoutNow(
          state,
          req,
          config,
          lastLogIndex,
          lastLogTerm,
          clusterSize,
          clusterOpt
        )

      case req: AddServerRequest =>
        onAddServer(state, req, config, lastLogIndex, sender, clusterOpt)

      case req: RemoveServerRequest =>
        onRemoveServer(state, req, config, lastLogIndex, sender, clusterOpt)

      case ElectionTimeout =>
        onElectionTimeout(
          state,
          config,
          lastLogIndex,
          lastLogTerm,
          clusterSize,
          clusterOpt
        )

      case HeartbeatTimeout =>
        onHeartbeatTimeout(state, config, lastLogIndex, lastLogTerm, getTermAt)

      case CheckQuorumTimeout =>
        onCheckQuorum(state, config, clusterSize, clusterOpt)

      case _: ReadIndexResponse | _: AddServerResponse |
          _: RemoveServerResponse =>
        Transition.pure(state)

  /** Handle a vote response with an explicit voter `NodeId` (deterministic,
    * idempotent deduplication).
    */
  def onVoteResponse(
      state: NodeState,
      voterId: NodeId,
      resp: RequestVoteResponse,
      config: RaftConfig,
      clusterSize: Int,
      lastLogIndex: Long = 0L,
      lastLogTerm: Long = 0L,
      clusterOpt: Option[ClusterConfig] = None
  ): Transition =
    if !resp.isPreVote && resp.term > state.term then
      val stepped = state.stepDown(resp.term)
      val stopEff = state match
        case _: Leader => List(StopLeader)
        case _         => Nil
      Transition(
        stepped,
        stopEff ++ List(
          PersistHardState(resp.term, None),
          ResetElectionTimer
        )
      )
    else
      state match
        case p: PreCandidate if resp.isPreVote && resp.term == p.term + 1 =>
          if resp.voteGranted then
            val updated = p.withPreVote(voterId)
            val wonPreVote = clusterOpt match
              case Some(c) => updated.hasPreVoteQuorum(c)
              case None    => updated.hasPreVoteMajority(clusterSize)
            if wonPreVote then
              startRealElection(
                p.term,
                config,
                lastLogIndex,
                lastLogTerm,
                clusterSize,
                clusterOpt
              )
            else Transition.pure(updated)
          else Transition.pure(p)

        case c: Candidate if !resp.isPreVote && resp.term == c.term =>
          if resp.voteGranted then
            val updated = c.withVote(voterId)
            val won = clusterOpt match
              case Some(cl) => updated.hasQuorum(cl)
              case None     => updated.hasMajority(clusterSize)
            if won then
              promoteToLeader(
                c.term,
                config,
                lastLogIndex,
                lastLogTerm,
                clusterOpt
              )
            else Transition.pure(updated)
          else Transition.pure(c)

        case _ =>
          Transition.pure(state)

  /** Fallback when legacy caller invokes `onMessage(state, VoteResponse)`
    * without supplying `sender: Option[NodeId]`: synthesizes a deterministic ID
    * derived purely from current vote set cardinality (zero mutable state).
    */
  private def onAnonymousVoteResponse(
      state: NodeState,
      resp: RequestVoteResponse,
      config: RaftConfig,
      clusterSize: Int,
      lastLogIndex: Long,
      lastLogTerm: Long,
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    val deterministicPeerId = state match
      case p: PreCandidate =>
        NodeId(s"anon-prevoter-${p.preVotesReceived.size}")
      case c: Candidate =>
        NodeId(s"anon-voter-${c.votesReceived.size}")
      case _ =>
        NodeId("anon-peer")
    onVoteResponse(
      state,
      deterministicPeerId,
      resp,
      config,
      clusterSize,
      lastLogIndex,
      lastLogTerm,
      clusterOpt
    )

  private def promoteToLeader(
      term: Long,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    val followers = clusterOpt
      .map(_.allNodes - config.localId)
      .getOrElse(Set.empty[NodeId])
    val baseLeader = Leader(
      term = term,
      recentlyActive = Set(config.localId)
    ).initializeIndices(followers, lastLogIndex)
    val heartbeat = AppendEntriesRequest(
      term = term,
      leaderId = config.localId,
      prevLogIndex = lastLogIndex,
      prevLogTerm = lastLogTerm,
      entries = Seq.empty,
      leaderCommit = 0L
    )
    Transition.withEffects(
      baseLeader,
      BecomeLeader,
      Broadcast(heartbeat),
      ResetHeartbeatTimer
    )

  private def startRealElection(
      priorTerm: Long,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      clusterSize: Int,
      clusterOpt: Option[ClusterConfig],
      isTransfer: Boolean = false
  ): Transition =
    val newTerm = priorTerm + 1L
    val selfVotes = Set(config.localId)
    val alreadyHasQuorum = clusterOpt match
      case Some(cl) => cl.hasQuorum(selfVotes)
      case None     => clusterSize == 1
    if alreadyHasQuorum then
      val leaderTrans = promoteToLeader(
        newTerm,
        config,
        lastLogIndex,
        lastLogTerm,
        clusterOpt
      )
      Transition(
        leaderTrans.state,
        PersistHardState(newTerm, Some(config.localId)) :: leaderTrans.effects
      )
    else
      val candidate = Candidate(newTerm, selfVotes)
      val voteReq = RequestVoteRequest(
        term = newTerm,
        candidateId = config.localId,
        lastLogIndex = lastLogIndex,
        lastLogTerm = lastLogTerm,
        isPreVote = false,
        isTransfer = isTransfer
      )
      Transition.withEffects(
        candidate,
        PersistHardState(newTerm, Some(config.localId)),
        Broadcast(voteReq),
        ResetElectionTimer
      )

  private def onAppendEntries(
      state: NodeState,
      req: AppendEntriesRequest,
      config: RaftConfig,
      lastLogIndex: Long,
      getTermAt: Long => Option[Long]
  ): Transition =
    if req.term < state.term || (req.term == state.term && state
        .isInstanceOf[Leader])
    then
      val response = AppendEntriesResponse(state.term, success = false, 0L)
      Transition.withEffect(state, SendMessage(req.leaderId, response))
    else
      val steppedDown = req.term > state.term
      val stopLeaderEff = state match
        case _: Leader if steppedDown => List(StopLeader)
        case _                        => Nil

      val votedForAfterStep: Option[NodeId] = state match
        case f: Follower if !steppedDown  => f.votedFor
        case c: Candidate if !steppedDown =>
          if c.votesReceived.contains(config.localId) then Some(config.localId)
          else None
        case _ => None

      val hardStateEff =
        if steppedDown then List(PersistHardState(req.term, None)) else Nil

      val followerState =
        Follower(req.term, votedForAfterStep, Some(req.leaderId))

      val prevMatches =
        if req.prevLogIndex == 0L then true
        else
          getTermAt(req.prevLogIndex) match
            case Some(actualTerm) => actualTerm == req.prevLogTerm
            case None             => false

      if !prevMatches then
        val (conflictIdx, conflictTerm) =
          computeConflictHint(req.prevLogIndex, lastLogIndex, getTermAt)
        val response = AppendEntriesResponse(
          term = req.term,
          success = false,
          matchIndex = 0L,
          conflictIndex = Some(conflictIdx),
          conflictTerm = conflictTerm
        )
        Transition(
          followerState,
          stopLeaderEff ++ hardStateEff ++ List(
            ResetElectionTimer,
            SendMessage(req.leaderId, response)
          )
        )
      else
        val (truncateEff, entriesToAppend) =
          resolveConflictingSuffix(req.entries, getTermAt)
        val newMatchIndex = req.prevLogIndex + req.entries.size
        val response = AppendEntriesResponse(
          term = req.term,
          success = true,
          matchIndex = newMatchIndex
        )
        val appendEff =
          if entriesToAppend.nonEmpty then List(AppendLogs(entriesToAppend))
          else Nil
        val commitEff =
          if req.leaderCommit > 0L then
            val commitTo = math.min(req.leaderCommit, newMatchIndex)
            if commitTo > 0L then
              List(CommitEntries(LogIndex.unsafeFrom(commitTo)))
            else Nil
          else Nil

        Transition(
          followerState,
          stopLeaderEff ++ hardStateEff ++ List(ResetElectionTimer) ++
            truncateEff ++ appendEff ++ commitEff ++
            List(SendMessage(req.leaderId, response))
        )

  /** Compute §5.3 fast-backtracking conflict hints (`conflictIndex`,
    * `conflictTerm`).
    */
  private def computeConflictHint(
      prevLogIndex: Long,
      lastLogIndex: Long,
      getTermAt: Long => Option[Long]
  ): (Long, Option[Long]) =
    if lastLogIndex < prevLogIndex then (lastLogIndex + 1L, None)
    else
      getTermAt(prevLogIndex) match
        case None        => (math.max(1L, lastLogIndex + 1L), None)
        case Some(cTerm) =>
          var firstIdx = prevLogIndex
          while firstIdx > 1L && getTermAt(firstIdx - 1L).contains(cTerm) do
            firstIdx -= 1L
          (firstIdx, Some(cTerm))

  /** Raft §5.3 Rule 3 & 4: If an existing entry conflicts with a new one (same
    * index, different terms), delete the existing entry and all that follow it;
    * append any new entries not already in the log (preserving idempotency if a
    * delayed/duplicate RPC carries a prefix of an already-longer matching log).
    */
  private def resolveConflictingSuffix(
      incoming: Seq[Log],
      getTermAt: Long => Option[Long]
  ): (List[Effect], Seq[Log]) =
    incoming.indexWhere { entry =>
      getTermAt(entry.index) match
        case Some(existingTerm) => existingTerm != entry.term
        case None               => false
    } match
      case -1 =>
        val firstMissing = incoming.indexWhere(e => getTermAt(e.index).isEmpty)
        if firstMissing == -1 then (Nil, Seq.empty)
        else (Nil, incoming.drop(firstMissing))
      case conflictPos =>
        val conflictEntry = incoming(conflictPos)
        (
          List(TruncateLog(conflictEntry.index)),
          incoming.drop(conflictPos)
        )

  private def onAppendEntriesResponse(
      state: NodeState,
      resp: AppendEntriesResponse,
      config: RaftConfig,
      leaderLastIndex: Long,
      clusterSize: Int,
      getTermAt: Long => Option[Long],
      sender: Option[NodeId],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    if resp.term > state.term then
      val stopEff = state match
        case _: Leader => List(StopLeader)
        case _         => Nil
      Transition(
        state.stepDown(resp.term),
        stopEff ++ List(
          PersistHardState(resp.term, None),
          ResetElectionTimer
        )
      )
    else
      state match
        case leader: Leader if resp.term == leader.term =>
          sender match
            case None =>
              if resp.success then
                val newCommit = leader.calculateCommitIndex(
                  clusterSize,
                  getTermAt,
                  leaderLastIndex
                )
                if newCommit > leader.commitIndex then
                  Transition.withEffect(
                    leader.withCommitIndex(newCommit),
                    CommitEntries(LogIndex.unsafeFrom(newCommit))
                  )
                else Transition.pure(leader)
              else Transition.pure(leader)

            case Some(peerId) =>
              val activeSet = leader.recentlyActive + config.localId + peerId
              if resp.success then
                val prevMatch = leader.matchIndex.getOrElse(peerId, 0L)
                val updatedMatch = math.max(prevMatch, resp.matchIndex)
                val updatedNext = math.max(
                  leader.nextIndex.getOrElse(peerId, 1L),
                  updatedMatch + 1L
                )
                val updatedLeader0 = leader
                  .withMatchIndex(peerId, updatedMatch)
                  .withNextIndex(peerId, updatedNext)
                  .copy(recentlyActive = activeSet)

                val newCommit = clusterOpt match
                  case Some(cl) =>
                    updatedLeader0.calculateCommitIndexForCluster(
                      config.localId,
                      cl,
                      leaderLastIndex,
                      getTermAt
                    )
                  case None =>
                    updatedLeader0.calculateCommitIndex(
                      clusterSize,
                      getTermAt,
                      leaderLastIndex
                    )

                val (leaderAfterReads, readEffects) =
                  resolvePendingReads(
                    updatedLeader0.withCommitIndex(newCommit),
                    peerId,
                    config.localId,
                    clusterSize,
                    clusterOpt
                  )

                val (finalLeader, transferEffects) =
                  leaderAfterReads.transferTarget match
                    case Some(target)
                        if target == peerId && updatedMatch >= leaderLastIndex =>
                      (
                        leaderAfterReads.copy(transferTarget = None),
                        List(
                          TimeoutNow(target),
                          SendMessage(
                            target,
                            TimeoutNowRequest(leader.term, config.localId)
                          )
                        )
                      )
                    case _ => (leaderAfterReads, Nil)

                val commitEff =
                  if newCommit > leader.commitIndex then
                    List(CommitEntries(LogIndex.unsafeFrom(newCommit)))
                  else Nil

                Transition(
                  finalLeader,
                  commitEff ++ readEffects ++ transferEffects
                )
              else
                val currentNext =
                  leader.nextIndex.getOrElse(peerId, leaderLastIndex + 1L)
                val matched = leader.matchIndex.getOrElse(peerId, 0L)
                val hintedNext = (resp.conflictTerm, resp.conflictIndex) match
                  case (Some(cTerm), Some(cIdx)) if cTerm > 0L =>
                    findLastIndexWithTerm(cTerm, leaderLastIndex, getTermAt)
                      .map(_ + 1L)
                      .getOrElse(cIdx)
                  case (_, Some(cIdx)) => cIdx
                  case _               => currentNext - 1L
                val backtrackedNext =
                  math.max(
                    1L,
                    math.max(
                      matched + 1L,
                      math.min(currentNext - 1L, hintedNext)
                    )
                  )
                val updatedLeader = leader
                  .withNextIndex(peerId, backtrackedNext)
                  .copy(recentlyActive = activeSet)
                val prevIdx = backtrackedNext - 1L
                val prevTerm =
                  if prevIdx == 0L then 0L
                  else getTermAt(prevIdx).getOrElse(0L)
                val retryProbe = AppendEntriesRequest(
                  term = leader.term,
                  leaderId = config.localId,
                  prevLogIndex = prevIdx,
                  prevLogTerm = prevTerm,
                  entries = Seq.empty,
                  leaderCommit = math.min(leader.commitIndex, prevIdx)
                )
                Transition.withEffect(
                  updatedLeader,
                  SendMessage(peerId, retryProbe)
                )

        case _ =>
          Transition.pure(state)

  private def findLastIndexWithTerm(
      targetTerm: Long,
      maxIndex: Long,
      getTermAt: Long => Option[Long]
  ): Option[Long] =
    var i = maxIndex
    var found: Option[Long] = None
    while i >= 1L && found.isEmpty do
      if getTermAt(i).contains(targetTerm) then found = Some(i)
      i -= 1L
    found

  private def resolvePendingReads(
      leader: Leader,
      ackFrom: NodeId,
      localId: NodeId,
      clusterSize: Int,
      clusterOpt: Option[ClusterConfig]
  ): (Leader, List[Effect]) =
    if leader.pendingReads.isEmpty then (leader, Nil)
    else
      var remaining = Map.empty[String, PendingRead]
      val readyBuf = List.newBuilder[Effect]
      leader.pendingReads.foreach { case (id, pending) =>
        val updatedAcks = pending.acks + localId + ackFrom
        val hasQuorum = clusterOpt match
          case Some(cl) => cl.hasQuorum(updatedAcks)
          case None     => updatedAcks.size > clusterSize / 2
        if hasQuorum then readyBuf += ReadIndexReady(id, pending.readIndex)
        else remaining = remaining.updated(id, pending.copy(acks = updatedAcks))
      }
      (leader.copy(pendingReads = remaining), readyBuf.result())

  /** Handle `RequestVoteRequest` (§5.2, §9.6 Pre-Vote, §6 Leader Stickiness,
    * §3.10 Leadership Transfer).
    *
    * **Critical Pre-Vote Safety (§9.6)**: When `req.isPreVote == true`, the
    * receiver MUST NEVER increment its term, step down from Leader/Candidate,
    * clear `votedFor`, or emit `PersistHardState`.
    */
  private def onRequestVote(
      state: NodeState,
      req: RequestVoteRequest,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long
  ): Transition =
    val logOk =
      LogId(req.lastLogTerm, req.lastLogIndex) >= LogId(
        lastLogTerm,
        lastLogIndex
      )

    if req.isPreVote then
      if req.term < state.term then
        Transition.withEffect(
          state,
          SendMessage(
            req.candidateId,
            RequestVoteResponse(
              state.term,
              voteGranted = false,
              isPreVote = true
            )
          )
        )
      else
        val hasActiveLeader = state match
          case _: Leader   => config.leaderStickinessEnabled
          case f: Follower =>
            config.leaderStickinessEnabled && f.leaderId.isDefined && req.term <= f.term
          case _ => false
        val grant = logOk && !hasActiveLeader
        Transition.withEffect(
          state,
          SendMessage(
            req.candidateId,
            RequestVoteResponse(req.term, voteGranted = grant, isPreVote = true)
          )
        )
    else
      val stickyReject =
        !req.isTransfer && config.leaderStickinessEnabled && req.term == state.term && (state match
          case _: Leader   => true
          case f: Follower => f.leaderId.isDefined
          case _           => false)

      if req.term < state.term || stickyReject then
        Transition.withEffect(
          state,
          SendMessage(
            req.candidateId,
            RequestVoteResponse(
              state.term,
              voteGranted = false,
              isPreVote = false
            )
          )
        )
      else
        val steppedDown = req.term > state.term
        val stopLeaderEff = state match
          case _: Leader if steppedDown => List(StopLeader)
          case _                        => Nil

        val baseState = if steppedDown then state.stepDown(req.term) else state
        baseState match
          case f: Follower =>
            val canVote =
              f.votedFor.isEmpty || f.votedFor.contains(req.candidateId)
            if canVote && logOk then
              val updated = f.copy(votedFor = Some(req.candidateId))
              Transition(
                updated,
                stopLeaderEff ++ List(
                  PersistHardState(req.term, Some(req.candidateId)),
                  ResetElectionTimer,
                  SendMessage(
                    req.candidateId,
                    RequestVoteResponse(
                      req.term,
                      voteGranted = true,
                      isPreVote = false
                    )
                  )
                )
              )
            else
              val persistEff =
                if steppedDown then List(PersistHardState(req.term, None))
                else Nil
              Transition(
                f,
                stopLeaderEff ++ persistEff ++ List(
                  SendMessage(
                    req.candidateId,
                    RequestVoteResponse(
                      req.term,
                      voteGranted = false,
                      isPreVote = false
                    )
                  )
                )
              )

          case other =>
            val persistEff =
              if steppedDown then List(PersistHardState(req.term, None))
              else Nil
            Transition(
              other,
              stopLeaderEff ++ persistEff ++ List(
                SendMessage(
                  req.candidateId,
                  RequestVoteResponse(
                    req.term,
                    voteGranted = false,
                    isPreVote = false
                  )
                )
              )
            )

  private def onElectionTimeout(
      state: NodeState,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      clusterSize: Int,
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    val isEligibleVoter = clusterOpt.forall(_.isVoter(config.localId))
    if !isEligibleVoter then Transition.pure(state)
    else
      state match
        case _: Leader =>
          Transition.pure(state)

        case f: Follower =>
          val singleVoterQuorum = clusterOpt match
            case Some(cl) => cl.hasQuorum(Set(config.localId))
            case None     => clusterSize == 1
          if singleVoterQuorum then
            startRealElection(
              f.term,
              config,
              lastLogIndex,
              lastLogTerm,
              clusterSize,
              clusterOpt
            )
          else if config.preVoteEnabled then
            val preCandidate = PreCandidate(f.term, Set(config.localId))
            val preVoteReq = RequestVoteRequest(
              term = f.term + 1L,
              candidateId = config.localId,
              lastLogIndex = lastLogIndex,
              lastLogTerm = lastLogTerm,
              isPreVote = true
            )
            Transition.withEffects(
              preCandidate,
              Broadcast(preVoteReq),
              ResetElectionTimer
            )
          else
            startRealElection(
              f.term,
              config,
              lastLogIndex,
              lastLogTerm,
              clusterSize,
              clusterOpt
            )

        case p: PreCandidate =>
          val preCandidate = PreCandidate(p.term, Set(config.localId))
          val preVoteReq = RequestVoteRequest(
            term = p.term + 1L,
            candidateId = config.localId,
            lastLogIndex = lastLogIndex,
            lastLogTerm = lastLogTerm,
            isPreVote = true
          )
          Transition.withEffects(
            preCandidate,
            Broadcast(preVoteReq),
            ResetElectionTimer
          )

        case c: Candidate =>
          startRealElection(
            c.term,
            config,
            lastLogIndex,
            lastLogTerm,
            clusterSize,
            clusterOpt
          )

  private def onHeartbeatTimeout(
      state: NodeState,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      getTermAt: Long => Option[Long]
  ): Transition =
    state match
      case l: Leader =>
        if l.nextIndex.nonEmpty then
          val perPeerEffects = l.nextIndex.toList.map {
            case (peerId, nextIdx) =>
              val prevIdx = math.max(0L, nextIdx - 1L)
              val prevTerm =
                if prevIdx == 0L then 0L
                else if prevIdx == lastLogIndex then lastLogTerm
                else getTermAt(prevIdx).getOrElse(lastLogTerm)
              SendMessage(
                peerId,
                AppendEntriesRequest(
                  term = l.term,
                  leaderId = config.localId,
                  prevLogIndex = prevIdx,
                  prevLogTerm = prevTerm,
                  entries = Seq.empty,
                  leaderCommit = math.min(l.commitIndex, prevIdx)
                )
              )
          }
          Transition(l, perPeerEffects :+ ResetHeartbeatTimer)
        else
          val heartbeat = AppendEntriesRequest(
            term = l.term,
            leaderId = config.localId,
            prevLogIndex = lastLogIndex,
            prevLogTerm = lastLogTerm,
            entries = Seq.empty,
            leaderCommit = l.commitIndex
          )
          Transition.withEffects(l, Broadcast(heartbeat), ResetHeartbeatTimer)
      case _ =>
        Transition.pure(state)

  /** `ReadIndex` handler (§6.4): confirms leadership before serving a
    * linearizable local read.
    */
  private def onReadIndex(
      state: NodeState,
      req: ReadIndexRequest,
      config: RaftConfig,
      clusterSize: Int,
      getTermAt: Long => Option[Long],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    state match
      case l: Leader =>
        val singleNodeQuorum = clusterOpt match
          case Some(cl) => cl.hasQuorum(Set(config.localId))
          case None     => clusterSize == 1

        if singleNodeQuorum then
          Transition.withEffect(l, ReadIndexReady(req.requestId, l.commitIndex))
        else if l.commitIndex > 0L && !getTermAt(l.commitIndex).forall(
            _ == l.term
          )
        then
          Transition.withEffect(
            l,
            ReadIndexRejected(
              req.requestId,
              "Leader has not yet committed an entry in its current term (§5.4.2)",
              Some(config.localId)
            )
          )
        else
          val pending = PendingRead(
            requestId = req.requestId,
            readIndex = l.commitIndex,
            acks = Set(config.localId)
          )
          val updatedLeader =
            l.copy(pendingReads =
              l.pendingReads.updated(req.requestId, pending)
            )
          val heartbeat = AppendEntriesRequest(
            term = l.term,
            leaderId = config.localId,
            prevLogIndex = 0L,
            prevLogTerm = 0L,
            entries = Seq.empty,
            leaderCommit = l.commitIndex
          )
          Transition.withEffects(
            updatedLeader,
            ConfirmLeadership(l.term, req.requestId),
            Broadcast(heartbeat)
          )

      case f: Follower =>
        val reason = f.leaderId match
          case Some(ldr) => s"Not leader. Known leader: ${ldr.value}"
          case None      => "Not leader. No known leader."
        Transition.withEffect(
          f,
          ReadIndexRejected(req.requestId, reason, f.leaderId)
        )

      case other =>
        Transition.withEffect(
          other,
          ReadIndexRejected(
            req.requestId,
            "Not leader (election in progress)",
            None
          )
        )

  /** `TransferLeadership` handler (§3.10). */
  private def onTransferLeadership(
      state: NodeState,
      req: TransferLeadershipRequest,
      config: RaftConfig,
      lastLogIndex: Long,
      getTermAt: Long => Option[Long],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    state match
      case l: Leader if req.term <= l.term =>
        if req.target == config.localId then Transition.pure(l)
        else if clusterOpt.exists(!_.isVoter(req.target)) then
          Transition.pure(l)
        else
          val targetMatch = l.matchIndex.getOrElse(req.target, 0L)
          if targetMatch >= lastLogIndex then
            Transition.withEffects(
              l.copy(transferTarget = Some(req.target)),
              TransferLeadership(req.target),
              TimeoutNow(req.target),
              SendMessage(req.target, TimeoutNowRequest(l.term, config.localId))
            )
          else
            val nextIdx = l.nextIndex.getOrElse(req.target, lastLogIndex + 1L)
            val prevIdx = math.max(0L, nextIdx - 1L)
            val prevTerm =
              if prevIdx == 0L then 0L else getTermAt(prevIdx).getOrElse(0L)
            val catchUpProbe = AppendEntriesRequest(
              term = l.term,
              leaderId = config.localId,
              prevLogIndex = prevIdx,
              prevLogTerm = prevTerm,
              entries = Seq.empty,
              leaderCommit = l.commitIndex
            )
            Transition.withEffects(
              l.copy(transferTarget = Some(req.target)),
              TransferLeadership(req.target),
              SendMessage(req.target, catchUpProbe)
            )
      case _ =>
        Transition.pure(state)

  /** `TimeoutNowRequest` handler (§3.10): recipient immediately starts a real
    * election with `isTransfer = true`, bypassing Pre-Vote and peer
    * `leaderStickiness`.
    */
  private def onTimeoutNow(
      state: NodeState,
      req: TimeoutNowRequest,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      clusterSize: Int,
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    if req.term < state.term then Transition.pure(state)
    else
      val baseTerm = math.max(state.term, req.term)
      startRealElection(
        baseTerm,
        config,
        lastLogIndex,
        lastLogTerm,
        clusterSize,
        clusterOpt,
        isTransfer = true
      )

  /** `CheckQuorumTimeout` handler (§6.2): leader steps down if partitioned from
    * a quorum of voters during the election window.
    */
  private def onCheckQuorum(
      state: NodeState,
      config: RaftConfig,
      clusterSize: Int,
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    state match
      case l: Leader if config.checkQuorumEnabled =>
        val activeWithSelf = l.recentlyActive + config.localId
        val hasQuorum = clusterOpt match
          case Some(cl) => cl.hasQuorum(activeWithSelf)
          case None     => activeWithSelf.size > clusterSize / 2
        if hasQuorum then
          Transition.pure(l.copy(recentlyActive = Set(config.localId)))
        else
          val rejectReads = l.pendingReads.keys.toList.map { id =>
            ReadIndexRejected(
              id,
              "Leader stepped down: CheckQuorum failed",
              None
            )
          }
          Transition(
            Follower(l.term, Some(config.localId), None),
            (StopLeader :: ResetElectionTimer :: rejectReads)
          )
      case _ =>
        Transition.pure(state)

  /** `InstallSnapshotRequest` handler (§7). */
  private def onInstallSnapshot(
      state: NodeState,
      req: InstallSnapshotRequest,
      lastLogIndex: Long,
      getTermAt: Long => Option[Long]
  ): Transition =
    if req.term < state.term then
      Transition.withEffect(
        state,
        SendMessage(
          req.leaderId,
          InstallSnapshotResponse(state.term, success = false, matchIndex = 0L)
        )
      )
    else
      val steppedDown = req.term > state.term
      val stopEff = state match
        case _: Leader if steppedDown => List(StopLeader)
        case _                        => Nil
      val votedFor = state match
        case f: Follower if !steppedDown => f.votedFor
        case _                           => None
      val persistEff =
        if steppedDown then List(PersistHardState(req.term, None)) else Nil
      val follower = Follower(req.term, votedFor, Some(req.leaderId))

      if !req.done then
        Transition(
          follower,
          stopEff ++ persistEff ++ List(
            ResetElectionTimer,
            SendMessage(
              req.leaderId,
              InstallSnapshotResponse(
                req.term,
                success = true,
                matchIndex = 0L
              )
            )
          )
        )
      else
        val alreadyCovered =
          req.lastIncludedIndex <= lastLogIndex &&
            getTermAt(req.lastIncludedIndex).contains(req.lastIncludedTerm)
        val installEff =
          if alreadyCovered then Nil
          else
            List(
              InstallSnapshot(
                SnapshotMetadata(req.lastIncludedIndex, req.lastIncludedTerm)
              ),
              CommitEntries(LogIndex.unsafeFrom(req.lastIncludedIndex))
            )
        Transition(
          follower,
          stopEff ++ persistEff ++ List(
            ResetElectionTimer
          ) ++ installEff ++ List(
            SendMessage(
              req.leaderId,
              InstallSnapshotResponse(
                req.term,
                success = true,
                matchIndex = req.lastIncludedIndex
              )
            )
          )
        )

  private def onInstallSnapshotResponse(
      state: NodeState,
      resp: InstallSnapshotResponse,
      sender: Option[NodeId]
  ): Transition =
    if resp.term > state.term then
      val stopEff = state match
        case _: Leader => List(StopLeader)
        case _         => Nil
      Transition(
        state.stepDown(resp.term),
        stopEff ++ List(PersistHardState(resp.term, None), ResetElectionTimer)
      )
    else
      (state, sender) match
        case (l: Leader, Some(peerId))
            if resp.success && resp.matchIndex > 0L =>
          val newMatch =
            math.max(l.matchIndex.getOrElse(peerId, 0L), resp.matchIndex)
          val newNext =
            math.max(l.nextIndex.getOrElse(peerId, 1L), newMatch + 1L)
          Transition.pure(
            l.withMatchIndex(peerId, newMatch).withNextIndex(peerId, newNext)
          )
        case _ =>
          Transition.pure(state)

  private def onAddServer(
      state: NodeState,
      req: AddServerRequest,
      config: RaftConfig,
      lastLogIndex: Long,
      sender: Option[NodeId],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    state match
      case l: Leader =>
        clusterOpt match
          case Some(cl) if !cl.isInJointConsensus =>
            val role =
              if req.isLearner then NodeRole.Learner else NodeRole.Voter
            val nextCfg =
              if role == NodeRole.Learner then cl.addMember(req.newServer, role)
              else
                cl.beginJointConsensus(
                  cl.members.updated(req.newServer, NodeRole.Voter)
                )
            val cfgEntry = Log(
              index = lastLogIndex + 1L,
              term = l.term,
              logType = LogType.Configuration,
              data = ClusterConfig.encode(nextCfg)
            )
            val updatedLeader = l
              .withNextIndex(req.newServer, lastLogIndex + 2L)
              .withMatchIndex(req.newServer, 0L)
            val replyEff = sender.toList.map { s =>
              SendMessage(
                s,
                AddServerResponse(l.term, success = true, Some(config.localId))
              )
            }
            Transition(
              updatedLeader,
              List(
                AppendLogs(Seq(cfgEntry)),
                ApplyClusterConfig(
                  nextCfg,
                  cfgEntry.index,
                  Term.unsafeFrom(l.term)
                )
              ) ++ replyEff
            )
          case _ =>
            val replyEff = sender.toList.map { s =>
              SendMessage(
                s,
                AddServerResponse(l.term, success = false, Some(config.localId))
              )
            }
            Transition(l, replyEff)

      case f: Follower =>
        val replyEff = sender.toList.map { s =>
          SendMessage(s, AddServerResponse(f.term, success = false, f.leaderId))
        }
        Transition(f, replyEff)

      case other =>
        val replyEff = sender.toList.map { s =>
          SendMessage(s, AddServerResponse(other.term, success = false, None))
        }
        Transition(other, replyEff)

  private def onRemoveServer(
      state: NodeState,
      req: RemoveServerRequest,
      config: RaftConfig,
      lastLogIndex: Long,
      sender: Option[NodeId],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    state match
      case l: Leader =>
        clusterOpt match
          case Some(cl)
              if !cl.isInJointConsensus && (cl.members - req.server).values
                .exists(r => r == NodeRole.Voter || r == NodeRole.Witness) =>
            val targetMembers = cl.members - req.server
            val nextCfg =
              if cl.isLearner(req.server) then cl.removeMember(req.server)
              else cl.beginJointConsensus(targetMembers)
            val cfgEntry = Log(
              index = lastLogIndex + 1L,
              term = l.term,
              logType = LogType.Configuration,
              data = ClusterConfig.encode(nextCfg)
            )
            val replyEff = sender.toList.map { s =>
              SendMessage(
                s,
                RemoveServerResponse(
                  l.term,
                  success = true,
                  Some(config.localId)
                )
              )
            }
            Transition(
              l,
              List(
                AppendLogs(Seq(cfgEntry)),
                ApplyClusterConfig(
                  nextCfg,
                  cfgEntry.index,
                  Term.unsafeFrom(l.term)
                )
              ) ++ replyEff
            )
          case _ =>
            val replyEff = sender.toList.map { s =>
              SendMessage(
                s,
                RemoveServerResponse(
                  l.term,
                  success = false,
                  Some(config.localId)
                )
              )
            }
            Transition(l, replyEff)

      case f: Follower =>
        val replyEff = sender.toList.map { s =>
          SendMessage(
            s,
            RemoveServerResponse(f.term, success = false, f.leaderId)
          )
        }
        Transition(f, replyEff)

      case other =>
        val replyEff = sender.toList.map { s =>
          SendMessage(
            s,
            RemoveServerResponse(other.term, success = false, None)
          )
        }
        Transition(other, replyEff)

  /** Validate whether `candidate` log is at least as up-to-date as `voter` log
    * (§5.4.1).
    */
  def isLogUpToDate(
      candLastIdx: Long,
      candLastTerm: Long,
      voterLastIdx: Long,
      voterLastTerm: Long
  ): Boolean =
    LogId(candLastTerm, candLastIdx) >= LogId(voterLastTerm, voterLastIdx)
