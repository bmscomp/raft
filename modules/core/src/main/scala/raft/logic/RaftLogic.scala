package raft.logic

import scala.annotation.tailrec

import raft.effect.*
import raft.effect.Effect.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.state.*
import raft.state.NodeState.*

/** Pure, deterministic RAFT state transition functions — 100% referentially
  * transparent (`0` mutable `var`s, `0` `while` loops, `0` runtime casts, `0`
  * exceptions, and `0` I/O imports).
  */
object RaftLogic:

  /** Primary pure message dispatcher. */
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
        onInstallSnapshotResponse(
          state,
          resp,
          config,
          lastLogIndex,
          clusterSize,
          getTermAt,
          sender,
          clusterOpt
        )

      case req: ReadIndexRequest =>
        onReadIndex(
          state,
          req,
          config,
          lastLogIndex,
          lastLogTerm,
          clusterSize,
          getTermAt,
          clusterOpt
        )

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
        onAddServer(
          state,
          req,
          config,
          lastLogIndex,
          getTermAt,
          sender,
          clusterOpt
        )

      case req: RemoveServerRequest =>
        onRemoveServer(
          state,
          req,
          config,
          lastLogIndex,
          getTermAt,
          sender,
          clusterOpt
        )

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
        onHeartbeatTimeout(
          state,
          config,
          lastLogIndex,
          lastLogTerm,
          getTermAt,
          clusterOpt
        )

      case CheckQuorumTimeout =>
        onCheckQuorum(state, config, clusterSize, clusterOpt)

      case _: ReadIndexResponse | _: AddServerResponse |
          _: RemoveServerResponse =>
        Transition.pure(state)

  private def stepDownEffectsFromLeader(
      state: NodeState,
      reason: String,
      leaderHint: Option[NodeId]
  ): List[Effect] =
    state match
      case l: Leader =>
        val rejectedReads = l.pendingReads.keys.toList.sorted.map { id =>
          ReadIndexRejected(id, reason, leaderHint)
        }
        StopLeader :: rejectedReads
      case _ => Nil

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
      val stopEff = stepDownEffectsFromLeader(
        state,
        s"Leader stepped down to higher term ${resp.term}",
        None
      )
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
                p.commitIndex,
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
                c.commitIndex,
                config,
                lastLogIndex,
                lastLogTerm,
                clusterOpt
              )
            else Transition.pure(updated)
          else Transition.pure(c)

        case _ =>
          Transition.pure(state)

  /** Legacy compatibility helper when caller invokes `onMessage` without
    * supplying `sender`: synthesizes a deterministic ID from current vote
    * count.
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
      priorCommitIndex: Long,
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
      commitIndex = priorCommitIndex,
      recentlyActive = Set(config.localId)
    ).initializeIndices(followers, lastLogIndex)
    val heartbeat = AppendEntriesRequest(
      term = term,
      leaderId = config.localId,
      prevLogIndex = lastLogIndex,
      prevLogTerm = lastLogTerm,
      entries = Seq.empty,
      leaderCommit = priorCommitIndex
    )
    Transition.withEffects(
      baseLeader,
      BecomeLeader,
      Broadcast(heartbeat),
      ResetHeartbeatTimer
    )

  private def startRealElection(
      priorTerm: Long,
      priorCommitIndex: Long,
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
        priorCommitIndex,
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
      val candidate = Candidate(newTerm, selfVotes, priorCommitIndex)
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
    if req.term < state.term || (req.term == state.term && state.isLeader) then
      val response = AppendEntriesResponse(state.term, success = false, 0L)
      Transition.withEffect(state, SendMessage(req.leaderId, response))
    else
      val steppedDown = req.term > state.term
      val stopLeaderEff =
        if steppedDown then
          stepDownEffectsFromLeader(
            state,
            s"Leader stepped down to term ${req.term}",
            Some(req.leaderId)
          )
        else Nil

      val votedForAfterStep: Option[NodeId] = state match
        case f: Follower if !steppedDown  => f.votedFor
        case c: Candidate if !steppedDown =>
          if c.votesReceived.contains(config.localId) then Some(config.localId)
          else None
        case _ => None

      val hardStateEff =
        if steppedDown then List(PersistHardState(req.term, None)) else Nil

      val prevCommit = state.commitIndex
      val baseFollower =
        Follower(req.term, votedForAfterStep, Some(req.leaderId), prevCommit)

      // Guard already-committed prefix (tikv/raft-rs §5.3 safety rule):
      // never conflict-check or truncate indices <= prevCommit.
      if req.prevLogIndex < prevCommit &&
        (req.prevLogIndex + req.entries.size.toLong) <= prevCommit
      then
        val response = AppendEntriesResponse(
          term = req.term,
          success = true,
          matchIndex = prevCommit
        )
        Transition(
          baseFollower,
          stopLeaderEff ++ hardStateEff ++ List(
            ResetElectionTimer,
            SendMessage(req.leaderId, response)
          )
        )
      else
        val (effectivePrevIdx, effectiveEntries, prevMatches) =
          if req.prevLogIndex < prevCommit then
            val skip = (prevCommit - req.prevLogIndex).toInt
            val anchorTerm = req.entries(skip - 1).term
            val matches = getTermAt(prevCommit).forall(_ == anchorTerm)
            (prevCommit, req.entries.drop(skip), matches)
          else
            val matches =
              req.prevLogIndex == 0L ||
                getTermAt(req.prevLogIndex).contains(req.prevLogTerm)
            (req.prevLogIndex, req.entries, matches)

        if !prevMatches then
          val (conflictIdx, conflictTerm) =
            computeConflictHint(
              effectivePrevIdx,
              lastLogIndex,
              prevCommit,
              getTermAt
            )
          val response = AppendEntriesResponse(
            term = req.term,
            success = false,
            matchIndex = 0L,
            conflictIndex = Some(conflictIdx),
            conflictTerm = conflictTerm
          )
          Transition(
            baseFollower,
            stopLeaderEff ++ hardStateEff ++ List(
              ResetElectionTimer,
              SendMessage(req.leaderId, response)
            )
          )
        else
          val (truncateEff, entriesToAppend) =
            resolveConflictingSuffix(effectiveEntries, prevCommit, getTermAt)
          val newMatchIndex = effectivePrevIdx + effectiveEntries.size.toLong
          val candidateCommit =
            if req.leaderCommit > prevCommit then
              math.max(prevCommit, math.min(req.leaderCommit, newMatchIndex))
            else prevCommit
          val finalFollower = Follower(
            req.term,
            votedForAfterStep,
            Some(req.leaderId),
            candidateCommit
          )
          val response = AppendEntriesResponse(
            term = req.term,
            success = true,
            matchIndex = newMatchIndex
          )
          val appendEff =
            if entriesToAppend.nonEmpty then List(AppendLogs(entriesToAppend))
            else Nil
          val commitEff =
            if candidateCommit > prevCommit then
              List(CommitEntries(LogIndex.unsafeFrom(candidateCommit)))
            else Nil

          Transition(
            finalFollower,
            stopLeaderEff ++ hardStateEff ++ List(ResetElectionTimer) ++
              truncateEff ++ appendEff ++ commitEff ++
              List(SendMessage(req.leaderId, response))
          )

  /** Compute §5.3 fast-backtracking conflict hints purely via `@tailrec`. */
  private def computeConflictHint(
      prevLogIndex: Long,
      lastLogIndex: Long,
      commitIndex: Long,
      getTermAt: Long => Option[Long]
  ): (Long, Option[Long]) =
    @tailrec
    def rewindTermStart(idx: Long, cTerm: Long, floor: Long): Long =
      if idx > floor && getTermAt(idx - 1L).contains(cTerm) then
        rewindTermStart(idx - 1L, cTerm, floor)
      else idx

    if lastLogIndex < prevLogIndex then (lastLogIndex + 1L, None)
    else
      getTermAt(prevLogIndex) match
        case None        => (math.max(1L, lastLogIndex + 1L), None)
        case Some(cTerm) =>
          val floor = math.max(1L, commitIndex + 1L)
          (rewindTermStart(prevLogIndex, cTerm, floor), Some(cTerm))

  /** Raft §5.3 Rule 3 & 4: truncate uncommitted conflicting suffix (`>
    * commitIndex`) and append new entries idempotently.
    */
  private def resolveConflictingSuffix(
      incoming: Seq[Log],
      commitIndex: Long,
      getTermAt: Long => Option[Long]
  ): (List[Effect], Seq[Log]) =
    val uncommitted = incoming.dropWhile(_.index <= commitIndex)
    uncommitted.indexWhere { entry =>
      getTermAt(entry.index) match
        case Some(existingTerm) => existingTerm != entry.term
        case None               => false
    } match
      case -1 =>
        val firstMissing =
          uncommitted.indexWhere(e => getTermAt(e.index).isEmpty)
        if firstMissing == -1 then (Nil, Seq.empty)
        else (Nil, uncommitted.drop(firstMissing))
      case conflictPos =>
        val conflictEntry = uncommitted(conflictPos)
        (
          List(TruncateLog(conflictEntry.index)),
          uncommitted.drop(conflictPos)
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
      val stopEff = stepDownEffectsFromLeader(
        state,
        s"Leader stepped down to term ${resp.term}",
        None
      )
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
                        leaderAfterReads.copy(
                          transferTarget = None,
                          leaseUntil = 0L
                        ),
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

  @tailrec
  private def findLastIndexWithTerm(
      targetTerm: Long,
      currentIndex: Long,
      getTermAt: Long => Option[Long]
  ): Option[Long] =
    if currentIndex < 1L then None
    else if getTermAt(currentIndex).contains(targetTerm) then Some(currentIndex)
    else findLastIndexWithTerm(targetTerm, currentIndex - 1L, getTermAt)

  private def resolvePendingReads(
      leader: Leader,
      ackFrom: NodeId,
      localId: NodeId,
      clusterSize: Int,
      clusterOpt: Option[ClusterConfig]
  ): (Leader, List[Effect]) =
    if leader.pendingReads.isEmpty then (leader, Nil)
    else
      val (remaining, readyEffectsRev) =
        leader.pendingReads.foldLeft(
          (Map.empty[String, PendingRead], List.empty[Effect])
        ) { case ((remAcc, effAcc), (id, pending)) =>
          val updatedAcks = pending.acks + localId + ackFrom
          val hasQuorum = clusterOpt match
            case Some(cl) => cl.hasQuorum(updatedAcks)
            case None     => updatedAcks.size > clusterSize / 2
          if hasQuorum then
            (remAcc, ReadIndexReady(id, pending.readIndex) :: effAcc)
          else (remAcc.updated(id, pending.copy(acks = updatedAcks)), effAcc)
        }
      (leader.copy(pendingReads = remaining), readyEffectsRev.reverse)

  /** Handle `RequestVoteRequest` (§5.2, §9.6 Pre-Vote, §6 Leader Stickiness,
    * §3.10 Leadership Transfer).
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
        val stopLeaderEff =
          if steppedDown then
            stepDownEffectsFromLeader(
              state,
              s"Leader stepped down to term ${req.term}",
              None
            )
          else Nil

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
    val isEligibleCampaigner = clusterOpt.forall(_.canCampaign(config.localId))
    if !isEligibleCampaigner then Transition.pure(state)
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
              f.commitIndex,
              config,
              lastLogIndex,
              lastLogTerm,
              clusterSize,
              clusterOpt
            )
          else if config.preVoteEnabled then
            val preCandidate =
              PreCandidate(f.term, Set(config.localId), f.commitIndex)
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
              f.commitIndex,
              config,
              lastLogIndex,
              lastLogTerm,
              clusterSize,
              clusterOpt
            )

        case p: PreCandidate =>
          val preCandidate =
            PreCandidate(p.term, Set(config.localId), p.commitIndex)
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
            c.commitIndex,
            config,
            lastLogIndex,
            lastLogTerm,
            clusterSize,
            clusterOpt
          )

  private def buildHeartbeatEffects(
      l: Leader,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      getTermAt: Long => Option[Long]
  ): List[Effect] =
    if l.nextIndex.nonEmpty then
      l.nextIndex.toList.map { case (peerId, nextIdx) =>
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
    else
      List(
        Broadcast(
          AppendEntriesRequest(
            term = l.term,
            leaderId = config.localId,
            prevLogIndex = lastLogIndex,
            prevLogTerm = lastLogTerm,
            entries = Seq.empty,
            leaderCommit = l.commitIndex
          )
        )
      )

  private def onHeartbeatTimeout(
      state: NodeState,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      getTermAt: Long => Option[Long],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    val _ = clusterOpt
    state match
      case l: Leader =>
        val hbEffects =
          buildHeartbeatEffects(l, config, lastLogIndex, lastLogTerm, getTermAt)
        Transition(l, hbEffects :+ ResetHeartbeatTimer)
      case _ =>
        Transition.pure(state)

  /** `ReadIndex` handler (§6.4). */
  private def onReadIndex(
      state: NodeState,
      req: ReadIndexRequest,
      config: RaftConfig,
      lastLogIndex: Long,
      lastLogTerm: Long,
      clusterSize: Int,
      getTermAt: Long => Option[Long],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    state match
      case l: Leader =>
        val singleNodeQuorum = clusterOpt match
          case Some(cl) => cl.hasQuorum(Set(config.localId))
          case None     => clusterSize == 1

        val currentTermCommitted =
          if l.commitIndex == 0L then lastLogIndex == 0L
          else getTermAt(l.commitIndex).contains(l.term)

        if !currentTermCommitted then
          Transition.withEffect(
            l,
            ReadIndexRejected(
              req.requestId,
              "Leader has not yet committed an entry in its current term (§5.4.2)",
              Some(config.localId)
            )
          )
        else if singleNodeQuorum then
          Transition.withEffect(l, ReadIndexReady(req.requestId, l.commitIndex))
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
          val hbEffects = buildHeartbeatEffects(
            l,
            config,
            lastLogIndex,
            lastLogTerm,
            getTermAt
          )
          Transition(
            updatedLeader,
            ConfirmLeadership(l.term, req.requestId) :: hbEffects
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
        else if clusterOpt.exists(!_.canCampaign(req.target)) then
          Transition.pure(l)
        else
          val targetMatch = l.matchIndex.getOrElse(req.target, 0L)
          if targetMatch >= lastLogIndex then
            Transition.withEffects(
              l.copy(transferTarget = Some(req.target), leaseUntil = 0L),
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
              l.copy(transferTarget = Some(req.target), leaseUntil = 0L),
              TransferLeadership(req.target),
              SendMessage(req.target, catchUpProbe)
            )
      case _ =>
        Transition.pure(state)

  /** `TimeoutNowRequest` handler (§3.10). */
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
    else if clusterOpt.exists(!_.canCampaign(config.localId)) then
      Transition.pure(state)
    else
      val baseTerm = math.max(state.term, req.term)
      startRealElection(
        baseTerm,
        state.commitIndex,
        config,
        lastLogIndex,
        lastLogTerm,
        clusterSize,
        clusterOpt,
        isTransfer = true
      )

  /** `CheckQuorumTimeout` handler (§6.2). */
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
          val stopEffs = stepDownEffectsFromLeader(
            l,
            "Leader stepped down: CheckQuorum failed",
            None
          )
          Transition(
            Follower(l.term, Some(config.localId), None, l.commitIndex),
            StopLeader :: ResetElectionTimer :: stopEffs.drop(1)
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
      val stopEff =
        if steppedDown then
          stepDownEffectsFromLeader(
            state,
            s"Leader stepped down to term ${req.term}",
            Some(req.leaderId)
          )
        else Nil
      val votedFor = state match
        case f: Follower if !steppedDown => f.votedFor
        case _                           => None
      val persistEff =
        if steppedDown then List(PersistHardState(req.term, None)) else Nil
      val prevCommit = state.commitIndex

      if !req.done then
        val follower =
          Follower(req.term, votedFor, Some(req.leaderId), prevCommit)
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
          req.lastIncludedIndex <= prevCommit || (
            req.lastIncludedIndex <= lastLogIndex &&
              getTermAt(req.lastIncludedIndex).contains(req.lastIncludedTerm)
          )
        val nextCommit =
          if alreadyCovered then prevCommit
          else math.max(prevCommit, req.lastIncludedIndex)
        val follower =
          Follower(req.term, votedFor, Some(req.leaderId), nextCommit)
        val installEff =
          if alreadyCovered then Nil
          else
            List(
              InstallSnapshot(
                SnapshotMetadata(req.lastIncludedIndex, req.lastIncludedTerm)
              ),
              CommitEntries(LogIndex.unsafeFrom(req.lastIncludedIndex))
            )
        val reportedMatch =
          if req.lastIncludedIndex <= prevCommit then prevCommit
          else req.lastIncludedIndex
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
                matchIndex = reportedMatch
              )
            )
          )
        )

  private def onInstallSnapshotResponse(
      state: NodeState,
      resp: InstallSnapshotResponse,
      config: RaftConfig,
      leaderLastIndex: Long,
      clusterSize: Int,
      getTermAt: Long => Option[Long],
      sender: Option[NodeId],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    if resp.term > state.term then
      val stopEff = stepDownEffectsFromLeader(
        state,
        s"Leader stepped down to term ${resp.term}",
        None
      )
      Transition(
        state.stepDown(resp.term),
        stopEff ++ List(PersistHardState(resp.term, None), ResetElectionTimer)
      )
    else
      (state, sender) match
        case (l: Leader, Some(peerId))
            if resp.term == l.term && resp.success && resp.matchIndex > 0L =>
          val newMatch =
            math.max(l.matchIndex.getOrElse(peerId, 0L), resp.matchIndex)
          val newNext =
            math.max(l.nextIndex.getOrElse(peerId, 1L), newMatch + 1L)
          val updated0 = l
            .withMatchIndex(peerId, newMatch)
            .withNextIndex(peerId, newNext)
            .copy(recentlyActive = l.recentlyActive + config.localId + peerId)
          val newCommit = clusterOpt match
            case Some(cl) =>
              updated0.calculateCommitIndexForCluster(
                config.localId,
                cl,
                leaderLastIndex,
                getTermAt
              )
            case None =>
              updated0.calculateCommitIndex(
                clusterSize,
                getTermAt,
                leaderLastIndex
              )
          val commitEff =
            if newCommit > l.commitIndex then
              List(CommitEntries(LogIndex.unsafeFrom(newCommit)))
            else Nil
          Transition(updated0.withCommitIndex(newCommit), commitEff)
        case (l: Leader, Some(peerId)) if resp.term == l.term && resp.success =>
          Transition.pure(
            l.copy(recentlyActive = l.recentlyActive + config.localId + peerId)
          )
        case _ =>
          Transition.pure(state)

  private def hasCommittedInLeaderTerm(
      l: Leader,
      lastLogIndex: Long,
      getTermAt: Long => Option[Long]
  ): Boolean =
    if l.commitIndex == 0L then lastLogIndex == 0L
    else getTermAt(l.commitIndex).contains(l.term)

  private def onAddServer(
      state: NodeState,
      req: AddServerRequest,
      config: RaftConfig,
      lastLogIndex: Long,
      getTermAt: Long => Option[Long],
      sender: Option[NodeId],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    state match
      case l: Leader =>
        val readyInTerm = hasCommittedInLeaderTerm(l, lastLogIndex, getTermAt)
        clusterOpt match
          case Some(cl)
              if !cl.isInJointConsensus && l.transferTarget.isEmpty &&
                (req.isLearner || readyInTerm) =>
            val role =
              if req.isLearner then NodeRole.Learner else NodeRole.Voter
            val nextCfg =
              if role == NodeRole.Learner then cl.addMember(req.newServer, role)
              else
                cl.beginJointConsensus(
                  cl.members.updated(req.newServer, NodeRole.Voter)
                )
            val cfgEntry = Log.configuration(
              index = lastLogIndex + 1L,
              term = l.term,
              config = nextCfg
            )
            val updatedLeader = l
              .withNextIndex(req.newServer, cfgEntry.index)
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
      getTermAt: Long => Option[Long],
      sender: Option[NodeId],
      clusterOpt: Option[ClusterConfig]
  ): Transition =
    state match
      case l: Leader =>
        val readyInTerm = hasCommittedInLeaderTerm(l, lastLogIndex, getTermAt)
        clusterOpt match
          case Some(cl)
              if !cl.isInJointConsensus && l.transferTarget.isEmpty &&
                (cl.isLearner(req.server) || readyInTerm) &&
                (cl.members - req.server).values
                  .exists(r => r == NodeRole.Voter || r == NodeRole.Witness) =>
            val targetMembers = cl.members - req.server
            val nextCfg =
              if cl.isLearner(req.server) then cl.removeMember(req.server)
              else cl.beginJointConsensus(targetMembers)
            val cfgEntry = Log.configuration(
              index = lastLogIndex + 1L,
              term = l.term,
              config = nextCfg
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
