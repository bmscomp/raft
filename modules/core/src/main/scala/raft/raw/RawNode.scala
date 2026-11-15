package raft.raw

import raft.logic.{ProgressState, ProgressTracker, VoteResult}
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.state.*
import raft.state.NodeState.*

/** Sans-I/O deterministic Raft consensus driver modeled after Rust's
  * `tikv/raft-rs` `RawNode`.
  *
  * `RawNode` owns **zero** threads, timers, sockets, or file handles. The host
  * system drives consensus entirely through four synchronous methods:
  *   - [[tick]] — advance logical time by 1 tick
  *   - [[step]] — feed an incoming [[RaftMessage]] from `from: NodeId`
  *   - [[propose]] / [[proposeConfChange]] / [[readIndex]] / [[transferLeader]]
  *   - [[ready]] & [[advance]] — drain persistence, network, and state-machine
  *     work batches
  *
  * Because every decision—including election jitter via a seeded pure PRNG—is
  * deterministic, identical input sequences from the same `initialSeed` are
  * guaranteed to produce bit-for-bit identical [[Ready]] batches.
  */
final class RawNode(
    val config: RaftConfig,
    val storage: RaftStorage,
    initialSeed: Long = 0x9e3779b97f4a7c15L
):
  private var prngState: Long =
    initialSeed ^ (config.localId.value.hashCode.toLong << 17)

  private val (initHard, initCluster) = storage.initialState

  private var roleState: NodeState =
    Follower(initHard.term, initHard.votedFor, None)
  private var commitIdx: Long = initHard.commitIndex
  private var appliedIdx: Long =
    storage.snapshotMetadata
      .map(s => math.max(s.lastIncludedIndex, 0L))
      .getOrElse(0L)

  private var lastPersistedHardState: HardState = initHard
  private var lastEmittedSoftState: SoftState =
    SoftState(None, roleState)

  private var tracker: ProgressTracker = ProgressTracker.forLeader(
    config.localId,
    initCluster,
    storage.lastIndex,
    config.pipelining.maxInflight
  )

  // Unstable log overlay (entries generated since last `advance`, before host persists)
  private var unstableTruncateFrom: Option[Long] = None
  private var unstableEntries: Vector[Log] = Vector.empty
  private var pendingSnapshotMeta: Option[SnapshotMetadata] = None

  // Logical tick counters
  private var logicalTick: Long = 0L
  private var electionElapsed: Int = 0
  private var heartbeatElapsed: Int = 0
  private var randomizedElectionTimeout: Int = nextRandomizedElectionTimeout()
  private var ticksSinceLeaderContact: Int = config.electionTimeoutTicks
  private var transferElapsed: Int = 0

  // Outbound buffers for next `Ready`
  private var prePersistMsgs: Vector[OutboundMessage] = Vector.empty
  private var postPersistMsgs: Vector[OutboundMessage] = Vector.empty
  private var readyReadStates: Vector[ReadState] = Vector.empty

  // === Public Introspection ===

  def state: NodeState = roleState
  def currentTerm: Long = roleState.term
  def commitIndex: Long = commitIdx
  def lastApplied: Long = appliedIdx
  def clusterConfig: ClusterConfig = tracker.config
  def progressTracker: ProgressTracker = tracker
  def currentTick: Long = logicalTick

  def leaderId: Option[NodeId] = roleState match
    case _: Leader           => Some(config.localId)
    case Follower(_, _, ldr) => ldr
    case _                   => None

  /** Highest log index across both `storage` and buffered `unstableEntries`. */
  def lastLogIndex: Long =
    unstableEntries.lastOption
      .map(_.index)
      .orElse(unstableTruncateFrom.map(t => math.max(0L, t - 1L)))
      .orElse(pendingSnapshotMeta.map(_.lastIncludedIndex))
      .getOrElse(storage.lastIndex)

  /** Term at `index`, consulting `pendingSnapshotMeta`, `unstableEntries`, and
    * `storage` in order.
    */
  def termAt(index: Long): Option[Long] =
    if index == 0L then Some(0L)
    else
      pendingSnapshotMeta
        .collect {
          case s if s.lastIncludedIndex == index => s.lastIncludedTerm
        }
        .orElse(unstableEntries.find(_.index == index).map(_.term))
        .orElse {
          unstableTruncateFrom match
            case Some(cut) if index >= cut => None
            case _                         => storage.term(index)
        }

  def lastLogTerm: Long = termAt(lastLogIndex).getOrElse(0L)

  // === Tick-Driven Logical Clock ===

  /** Advance logical time by one tick (`tikv/raft-rs` `RawNode::tick`). */
  def tick(): Unit =
    logicalTick += 1L
    ticksSinceLeaderContact += 1
    roleState match
      case l: Leader =>
        heartbeatElapsed += 1
        electionElapsed += 1

        if l.transferTarget.isDefined then
          transferElapsed += 1
          if transferElapsed >= config.electionTimeoutTicks then
            roleState = l.copy(transferTarget = None)
            transferElapsed = 0

        if electionElapsed >= config.electionTimeoutTicks then
          electionElapsed = 0
          if config.checkQuorumEnabled then
            if tracker.quorumRecentlyActive(config.localId) then
              tracker = tracker.resetRecentActive(config.localId)
            else
              stepDownToFollower(
                l.term,
                votedFor = Some(config.localId),
                leader = None
              )

        if roleState.isInstanceOf[
            Leader
          ] && heartbeatElapsed >= config.heartbeatIntervalTicks
        then
          heartbeatElapsed = 0
          bcastHeartbeat()

      case _ =>
        if tracker.config.isVoter(config.localId) then
          electionElapsed += 1
          if electionElapsed >= randomizedElectionTimeout then
            electionElapsed = 0
            randomizedElectionTimeout = nextRandomizedElectionTimeout()
            campaign(
              isPreVote = config.preVoteEnabled && !isSingleVoterQuorum,
              isTransfer = false
            )

  // === Client & Admin Operations ===

  /** Propose a client command payload to be appended and replicated. */
  def propose(data: Array[Byte]): Either[RaftError, Long] =
    roleState match
      case l: Leader =>
        l.transferTarget match
          case Some(target) =>
            Left(RaftError.LeadershipTransferInProgress(target))
          case None =>
            val idx = appendLeaderEntry(LogType.Command, data, l.term)
            bcastAppend()
            Right(idx)
      case f: Follower =>
        Left(RaftError.NotLeader(f.leaderId))
      case _ =>
        Left(RaftError.ElectionInProgress)

  /** Propose a safe dynamic membership change (`ConfChangeV2` Joint Consensus
    * §6).
    *
    * Enforces the **single pending configuration** safety rule: rejects if
    * another uncommitted configuration entry already exists after `commitIdx`
    * or if the cluster has not yet committed an entry in the leader's current
    * term.
    */
  def proposeConfChange(
      newMembers: Map[NodeId, NodeRole]
  ): Either[RaftError, Long] =
    roleState match
      case l: Leader =>
        if l.transferTarget.isDefined then
          Left(RaftError.LeadershipTransferInProgress(l.transferTarget.get))
        else if !hasCommittedInCurrentTerm(l.term) then
          Left(RaftError.LeaderNotReadyInTerm(l.term))
        else if tracker.config.isInJointConsensus || hasUncommittedConfigEntry
        then Left(RaftError.ConfigurationChangeInProgress(tracker.config))
        else if !newMembers.values.exists(r =>
            r == NodeRole.Voter || r == NodeRole.Witness
          )
        then
          Left(
            RaftError.ProposalDropped(
              "Target configuration must retain at least one voter"
            )
          )
        else
          val nextCfg =
            if newMembers == tracker.config.members then tracker.config
            else tracker.config.beginJointConsensus(newMembers)
          val idx = appendLeaderEntry(
            LogType.Configuration,
            ClusterConfig.encode(nextCfg),
            l.term
          )
          applyClusterConfig(nextCfg)
          bcastAppend()
          Right(idx)
      case f: Follower =>
        Left(RaftError.NotLeader(f.leaderId))
      case _ =>
        Left(RaftError.ElectionInProgress)

  /** Request a linearizable read index (`ReadIndex` / `LeaseRead` §6.4). */
  def readIndex(requestCtx: String): Either[RaftError, Unit] =
    roleState match
      case l: Leader =>
        if isSingleVoterQuorum && hasCommittedInCurrentTerm(l.term) then
          readyReadStates = readyReadStates :+ ReadState(commitIdx, requestCtx)
          Right(())
        else if config.leaseReadEnabled && config.checkQuorumEnabled &&
          logicalTick < l.leaseUntil && hasCommittedInCurrentTerm(l.term)
        then
          readyReadStates = readyReadStates :+ ReadState(commitIdx, requestCtx)
          Right(())
        else
          val pending = PendingRead(
            requestId = requestCtx,
            readIndex = commitIdx,
            acks = Set(config.localId)
          )
          roleState = l.copy(
            pendingReads = l.pendingReads.updated(requestCtx, pending)
          )
          bcastHeartbeat()
          Right(())
      case f: Follower =>
        Left(RaftError.NotLeader(f.leaderId))
      case _ =>
        Left(RaftError.ElectionInProgress)

  /** Initiate graceful leadership transfer to `transferee` (§3.10). */
  def transferLeader(transferee: NodeId): Either[RaftError, Unit] =
    roleState match
      case l: Leader =>
        if transferee == config.localId then Right(())
        else if !tracker.config.isVoter(transferee) then
          Left(
            RaftError.ProposalDropped(
              s"Target ${transferee.value} is not a voting member"
            )
          )
        else
          transferElapsed = 0
          roleState = l.copy(transferTarget = Some(transferee), leaseUntil = 0L)
          val matched =
            tracker.progress.get(transferee).map(_.matched).getOrElse(0L)
          if matched >= lastLogIndex then
            sendOrigin(
              transferee,
              TimeoutNowRequest(l.term, config.localId),
              prePersist = true
            )
          else sendAppend(transferee, forceSendEmpty = true)
          Right(())
      case f: Follower =>
        Left(RaftError.NotLeader(f.leaderId))
      case _ =>
        Left(RaftError.ElectionInProgress)

  // === Protocol Message Step ===

  /** Step the state machine with an inbound message from `from` (`tikv/raft-rs`
    * `RawNode::step`).
    */
  def step(from: NodeId, msg: RaftMessage): Unit =
    msg match
      case ElectionTimeout =>
        if !roleState
            .isInstanceOf[Leader] && tracker.config.isVoter(config.localId)
        then
          campaign(
            isPreVote = config.preVoteEnabled && !isSingleVoterQuorum,
            isTransfer = false
          )

      case HeartbeatTimeout =>
        if roleState.isInstanceOf[Leader] then bcastHeartbeat()

      case CheckQuorumTimeout =>
        roleState match
          case l: Leader if config.checkQuorumEnabled =>
            if tracker.quorumRecentlyActive(config.localId) then
              tracker = tracker.resetRecentActive(config.localId)
            else
              stepDownToFollower(
                l.term,
                votedFor = Some(config.localId),
                leader = None
              )
          case _ => ()

      case req: ReadIndexRequest =>
        readIndex(req.requestId) match
          case Right(_) => ()
          case Left(_)  =>
            sendOrigin(
              from,
              ReadIndexResponse(req.requestId, success = false, readIndex = 0L),
              prePersist = true
            )

      case req: TransferLeadershipRequest =>
        val _ = transferLeader(req.target)

      case req: TimeoutNowRequest =>
        if req.term >= currentTerm && tracker.config.isVoter(config.localId)
        then
          if req.term > currentTerm then
            stepDownToFollower(
              req.term,
              votedFor = None,
              leader = Some(req.leaderId)
            )
          campaign(isPreVote = false, isTransfer = true)

      case req: RequestVoteRequest =>
        handleRequestVote(from, req)

      case resp: RequestVoteResponse =>
        handleVoteResponse(from, resp)

      case req: AppendEntriesRequest =>
        handleAppendEntries(from, req)

      case resp: AppendEntriesResponse =>
        handleAppendEntriesResponse(from, resp)

      case req: InstallSnapshotRequest =>
        handleInstallSnapshot(from, req)

      case resp: InstallSnapshotResponse =>
        handleInstallSnapshotResponse(from, resp)

      case req: AddServerRequest =>
        val role = if req.isLearner then NodeRole.Learner else NodeRole.Voter
        val targetMap = tracker.config.members.updated(req.newServer, role)
        val ok =
          if role == NodeRole.Learner && roleState.isInstanceOf[Leader] then
            val nextCfg =
              tracker.config.addMember(req.newServer, NodeRole.Learner)
            val _ = appendLeaderEntry(
              LogType.Configuration,
              ClusterConfig.encode(nextCfg),
              currentTerm
            )
            applyClusterConfig(nextCfg)
            bcastAppend()
            true
          else proposeConfChange(targetMap).isRight
        sendOrigin(
          from,
          AddServerResponse(currentTerm, success = ok, leaderId),
          prePersist = true
        )

      case req: RemoveServerRequest =>
        val targetMap = tracker.config.members - req.server
        val ok = proposeConfChange(targetMap).isRight
        sendOrigin(
          from,
          RemoveServerResponse(currentTerm, success = ok, leaderId),
          prePersist = true
        )

      case _: ReadIndexResponse | _: AddServerResponse |
          _: RemoveServerResponse =>
        ()

  // === Ready / Advance Lifecycle ===

  /** True iff there is pending persistence, network, or state-machine work. */
  def hasReady: Boolean =
    currentHardState != lastPersistedHardState ||
      currentSoftState != lastEmittedSoftState ||
      unstableTruncateFrom.isDefined ||
      unstableEntries.nonEmpty ||
      pendingSnapshotMeta.isDefined ||
      prePersistMsgs.nonEmpty ||
      postPersistMsgs.nonEmpty ||
      readyReadStates.nonEmpty ||
      hasUnappliedCommittedEntries

  /** Snapshot the current [[Ready]] batch without mutating persisted watermarks
    * (host must call [[advance]] once finished executing the batch).
    */
  def ready(): Ready =
    val hs = currentHardState
    val hardOpt = if hs != lastPersistedHardState then Some(hs) else None
    val ss = currentSoftState
    val softOpt = if ss != lastEmittedSoftState then Some(ss) else None
    val committed = collectEntriesToApply()

    Ready(
      softState = softOpt,
      hardState = hardOpt,
      truncateFromIndex = unstableTruncateFrom,
      entries = unstableEntries,
      snapshot = pendingSnapshotMeta,
      messages = prePersistMsgs,
      persistedMessages = postPersistMsgs,
      committedEntries = committed,
      readStates = readyReadStates
    )

  /** Acknowledge that the host has persisted `ready`'s storage mutations, sent
    * its messages, and applied `ready.committedEntries` (`tikv/raft-rs`
    * `RawNode::advance`).
    */
  def advance(rd: Ready): LightReady =
    rd.softState.foreach(ss => lastEmittedSoftState = ss)
    rd.hardState.foreach(hs => lastPersistedHardState = hs)

    prePersistMsgs = Vector.empty
    postPersistMsgs = Vector.empty
    readyReadStates = Vector.empty

    rd.snapshot.foreach { snap =>
      appliedIdx = math.max(appliedIdx, snap.lastIncludedIndex)
      if pendingSnapshotMeta.contains(snap) then pendingSnapshotMeta = None
    }

    if rd.committedEntries.nonEmpty then
      appliedIdx = math.max(appliedIdx, rd.committedEntries.last.index)

    if rd.entries.nonEmpty then
      val persistedUpTo = rd.entries.last.index
      unstableEntries = unstableEntries.dropWhile(_.index <= persistedUpTo)
      unstableTruncateFrom = None

      roleState match
        case l: Leader =>
          tracker = tracker.updateProgress(
            config.localId,
            p => p.maybeUpdate(persistedUpTo)._1
          )
          maybeAdvanceLeaderCommit(l)
        case _ => ()
    else unstableTruncateFrom = None

    val newCommitted = collectEntriesToApply()
    if newCommitted.nonEmpty then
      appliedIdx = math.max(appliedIdx, newCommitted.last.index)

    val followUpMsgs = prePersistMsgs ++ postPersistMsgs
    prePersistMsgs = Vector.empty
    postPersistMsgs = Vector.empty

    val commitChanged =
      if commitIdx > lastPersistedHardState.commitIndex then
        lastPersistedHardState =
          lastPersistedHardState.copy(commitIndex = commitIdx)
        Some(commitIdx)
      else None

    LightReady(
      commitIndex = commitChanged,
      committedEntries = newCommitted,
      messages = followUpMsgs
    )

  // === Internal Consensus Mechanics ===

  private def currentHardState: HardState =
    val votedForOpt = roleState match
      case Follower(_, vf, _)                                    => vf
      case Candidate(_, votes) if votes.contains(config.localId) =>
        Some(config.localId)
      case _: Leader => Some(config.localId)
      case _         =>
        lastPersistedHardState.votedFor.filter(_ =>
          currentTerm == lastPersistedHardState.term
        )
    HardState(currentTerm, votedForOpt, commitIdx)

  private def currentSoftState: SoftState =
    SoftState(leaderId, roleState)

  private def isSingleVoterQuorum: Boolean =
    tracker.config.hasQuorum(Set(config.localId))

  private def hasActiveLeaderLease: Boolean =
    config.leaderStickinessEnabled && (roleState match
      case _: Leader   => config.checkQuorumEnabled
      case f: Follower =>
        f.leaderId.isDefined && ticksSinceLeaderContact < config.electionTimeoutTicks
      case _ => false)

  private def hasCommittedInCurrentTerm(term: Long): Boolean =
    commitIdx > 0L && termAt(commitIdx).contains(term)

  private def hasUncommittedConfigEntry: Boolean =
    val start = commitIdx + 1L
    val end = lastLogIndex + 1L
    if start >= end then false
    else entriesBetween(start, end).exists(_.logType == LogType.Configuration)

  private def hasUnappliedCommittedEntries: Boolean =
    commitIdx > appliedIdx && collectEntriesToApply().nonEmpty

  private def collectEntriesToApply(): Vector[Log] =
    val start = math.max(appliedIdx + 1L, storage.firstIndex)
    if commitIdx < start then Vector.empty
    else entriesBetween(start, commitIdx + 1L)

  private def entriesBetween(low: Long, high: Long): Vector[Log] =
    if low >= high then Vector.empty
    else
      val firstUnstable = unstableEntries.headOption.map(_.index)
      firstUnstable match
        case None =>
          storage.entries(low, high)
        case Some(uStart) if high <= uStart =>
          storage.entries(low, high)
        case Some(uStart) if low >= uStart =>
          unstableEntries.filter(e => e.index >= low && e.index < high)
        case Some(uStart) =>
          storage.entries(low, uStart) ++
            unstableEntries.filter(e => e.index >= uStart && e.index < high)

  private def sendOrigin(
      to: NodeId,
      msg: RaftMessage,
      prePersist: Boolean
  ): Unit =
    if to != config.localId then
      val env = OutboundMessage(config.localId, to, msg)
      if prePersist then prePersistMsgs = prePersistMsgs :+ env
      else postPersistMsgs = postPersistMsgs :+ env

  private def stepDownToFollower(
      newTerm: Long,
      votedFor: Option[NodeId],
      leader: Option[NodeId]
  ): Unit =
    electionElapsed = 0
    heartbeatElapsed = 0
    transferElapsed = 0
    randomizedElectionTimeout = nextRandomizedElectionTimeout()
    if leader.isDefined then ticksSinceLeaderContact = 0
    roleState = Follower(newTerm, votedFor, leader)
    tracker = tracker.resetVotes

  private def campaign(isPreVote: Boolean, isTransfer: Boolean): Unit =
    electionElapsed = 0
    randomizedElectionTimeout = nextRandomizedElectionTimeout()
    tracker = tracker.resetVotes.recordVote(config.localId, granted = true)

    if isSingleVoterQuorum && !isPreVote then
      val nextTerm = currentTerm + 1L
      becomeLeader(nextTerm)
    else if isPreVote then
      val prospectiveTerm = currentTerm + 1L
      roleState = PreCandidate(currentTerm, Set(config.localId))
      val req = RequestVoteRequest(
        term = prospectiveTerm,
        candidateId = config.localId,
        lastLogIndex = lastLogIndex,
        lastLogTerm = lastLogTerm,
        isPreVote = true,
        isTransfer = false
      )
      broadcastToVoters(req, prePersist = true)
    else
      val nextTerm = currentTerm + 1L
      roleState = Candidate(nextTerm, Set(config.localId))
      if tracker.tallyVotes == VoteResult.Won then becomeLeader(nextTerm)
      else
        val req = RequestVoteRequest(
          term = nextTerm,
          candidateId = config.localId,
          lastLogIndex = lastLogIndex,
          lastLogTerm = lastLogTerm,
          isPreVote = false,
          isTransfer = isTransfer
        )
        broadcastToVoters(req, prePersist = false)

  private def broadcastToVoters(msg: RaftMessage, prePersist: Boolean): Unit =
    val targets =
      (tracker.config.newVoters ++ tracker.config.oldVoters.getOrElse(
        Set.empty
      )) - config.localId
    targets.toSeq.sortBy(_.value).foreach { peer =>
      sendOrigin(peer, msg, prePersist = prePersist)
    }

  private def becomeLeader(term: Long): Unit =
    electionElapsed = 0
    heartbeatElapsed = 0
    transferElapsed = 0
    ticksSinceLeaderContact = 0
    val baseLast = lastLogIndex
    tracker = ProgressTracker.forLeader(
      config.localId,
      tracker.config,
      baseLast,
      config.pipelining.maxInflight
    )
    val peers = tracker.config.allNodes - config.localId
    val nextMap = peers.map(_ -> (baseLast + 2L)).toMap
    val matchMap = peers.map(_ -> 0L).toMap
    val leaseTicks = math.max(
      1L,
      (config.electionTimeoutTicks * 2L) / 3L
    )
    roleState = Leader(
      term = term,
      nextIndex = nextMap,
      matchIndex = matchMap,
      commitIndex = commitIdx,
      leaseUntil = logicalTick + leaseTicks,
      recentlyActive = Set(config.localId)
    )
    // Mandatory §5.4.2 / Figure 8 NoOp entry in the new leader's own term!
    val _ = appendLeaderEntry(LogType.NoOp, Array.emptyByteArray, term)
    bcastAppend()

  private def appendLeaderEntry(
      logType: LogType,
      data: Array[Byte],
      term: Long
  ): Long =
    val newIdx = lastLogIndex + 1L
    val entry = Log(newIdx, term, logType, data)
    unstableEntries = unstableEntries :+ entry
    entry.index

  private def applyClusterConfig(cfg: ClusterConfig): Unit =
    tracker = tracker.withConfig(cfg, lastLogIndex)
    roleState match
      case l: Leader =>
        val peers = cfg.allNodes - config.localId
        val nextM = peers.map { id =>
          id -> tracker.progress
            .get(id)
            .map(_.nextIdx)
            .getOrElse(lastLogIndex + 1L)
        }.toMap
        val matchM = peers.map { id =>
          id -> tracker.progress.get(id).map(_.matched).getOrElse(0L)
        }.toMap
        roleState = l.copy(nextIndex = nextM, matchIndex = matchM)
      case _ => ()

  private def bcastAppend(forceSendEmpty: Boolean = false): Unit =
    val peers = (tracker.config.allNodes - config.localId).toSeq.sortBy(_.value)
    peers.foreach(peer => sendAppend(peer, forceSendEmpty = forceSendEmpty))

  private def bcastHeartbeat(): Unit =
    val peers = (tracker.config.allNodes - config.localId).toSeq.sortBy(_.value)
    peers.foreach { peer =>
      val p = tracker.progress.get(peer)
      val prevIdx = math.min(p.map(_.matched).getOrElse(0L), lastLogIndex)
      val prevTerm = termAt(prevIdx).getOrElse(0L)
      val commitForPeer = math.min(commitIdx, prevIdx)
      sendOrigin(
        peer,
        AppendEntriesRequest(
          term = currentTerm,
          leaderId = config.localId,
          prevLogIndex = prevIdx,
          prevLogTerm = prevTerm,
          entries = Seq.empty,
          leaderCommit = commitForPeer
        ),
        prePersist = true
      )
    }

  private def sendAppend(peer: NodeId, forceSendEmpty: Boolean): Unit =
    tracker.progress.get(peer).foreach { pr =>
      if !pr.isPaused || forceSendEmpty then
        val prevIdx = pr.nextIdx - 1L
        val prevTermOpt = termAt(prevIdx)
        val firstAvail = storage.firstIndex

        if (pr.nextIdx < firstAvail || prevTermOpt.isEmpty) &&
          storage.snapshotMetadata.isDefined
        then
          // Follower lags behind compacted log; transition to Snapshot state
          storage.snapshotMetadata.foreach { snap =>
            tracker = tracker.updateProgress(
              peer,
              _.becomeSnapshot(snap.lastIncludedIndex)
            )
            sendOrigin(
              peer,
              InstallSnapshotRequest(
                term = currentTerm,
                leaderId = config.localId,
                lastIncludedIndex = snap.lastIncludedIndex,
                lastIncludedTerm = snap.lastIncludedTerm,
                offset = 0L,
                data = SnapshotMetadata.encodePayload(snap.config, snap.data),
                done = true
              ),
              prePersist = true
            )
          }
        else
          val prevTerm = prevTermOpt.getOrElse(0L)
          val upper = math.min(
            lastLogIndex + 1L,
            pr.nextIdx + config.maxEntriesPerRequest.toLong
          )
          val ents =
            if tracker.config.witnesses.contains(peer) || pr.isPaused then
              Vector.empty
            else entriesBetween(pr.nextIdx, upper)

          if ents.nonEmpty || forceSendEmpty then
            val updatedPr =
              if ents.nonEmpty then pr.optimisticUpdate(ents.last.index)
              else pr
            tracker = tracker.updateProgress(peer, _ => updatedPr)
            syncLeaderMapsFromTracker()
            sendOrigin(
              peer,
              AppendEntriesRequest(
                term = currentTerm,
                leaderId = config.localId,
                prevLogIndex = prevIdx,
                prevLogTerm = prevTerm,
                entries = ents,
                leaderCommit = commitIdx
              ),
              prePersist = true
            )
    }

  private def handleRequestVote(from: NodeId, req: RequestVoteRequest): Unit =
    val logUpToDate =
      LogId(req.lastLogTerm, req.lastLogIndex) >= LogId(
        lastLogTerm,
        lastLogIndex
      )

    if req.isPreVote then
      if req.term < currentTerm then
        sendOrigin(
          from,
          RequestVoteResponse(
            currentTerm,
            voteGranted = false,
            isPreVote = true
          ),
          prePersist = true
        )
      else
        val rejectForLeader =
          hasActiveLeaderLease && req.term <= currentTerm + 1L && (roleState match
            case _: Leader   => true
            case f: Follower =>
              f.leaderId.isDefined && req.term <= f.term + 1L && ticksSinceLeaderContact < config.electionTimeoutTicks
            case _ => false)
        val grant = logUpToDate && !rejectForLeader
        sendOrigin(
          from,
          RequestVoteResponse(req.term, voteGranted = grant, isPreVote = true),
          prePersist = true
        )
    else
      val stickyReject = !req.isTransfer && hasActiveLeaderLease
      if req.term < currentTerm || stickyReject then
        sendOrigin(
          from,
          RequestVoteResponse(
            currentTerm,
            voteGranted = false,
            isPreVote = false
          ),
          prePersist = true
        )
      else
        if req.term > currentTerm then
          stepDownToFollower(req.term, votedFor = None, leader = None)

        val currentVote = roleState match
          case Follower(_, vf, _)                              => vf
          case Candidate(_, vs) if vs.contains(config.localId) =>
            Some(config.localId)
          case _: Leader => Some(config.localId)
          case _         => None

        val canVote =
          (currentVote.isEmpty || currentVote.contains(req.candidateId)) &&
            roleState.isInstanceOf[Follower]

        if canVote && logUpToDate then
          electionElapsed = 0
          randomizedElectionTimeout = nextRandomizedElectionTimeout()
          roleState = Follower(req.term, Some(req.candidateId), None)
          sendOrigin(
            from,
            RequestVoteResponse(
              req.term,
              voteGranted = true,
              isPreVote = false
            ),
            prePersist = false
          )
        else
          sendOrigin(
            from,
            RequestVoteResponse(
              currentTerm,
              voteGranted = false,
              isPreVote = false
            ),
            prePersist = false
          )

  private def handleVoteResponse(
      from: NodeId,
      resp: RequestVoteResponse
  ): Unit =
    if resp.term > currentTerm && !resp.isPreVote then
      stepDownToFollower(resp.term, votedFor = None, leader = None)
    else
      roleState match
        case p: PreCandidate if resp.isPreVote && resp.term == p.term + 1L =>
          tracker = tracker.recordVote(from, resp.voteGranted)
          if resp.voteGranted then roleState = p.withPreVote(from)
          tracker.tallyVotes match
            case VoteResult.Won =>
              campaign(isPreVote = false, isTransfer = false)
            case VoteResult.Lost =>
              stepDownToFollower(p.term, votedFor = None, leader = None)
            case VoteResult.Pending => ()

        case c: Candidate if !resp.isPreVote && resp.term == c.term =>
          tracker = tracker.recordVote(from, resp.voteGranted)
          if resp.voteGranted then roleState = c.withVote(from)
          tracker.tallyVotes match
            case VoteResult.Won  => becomeLeader(c.term)
            case VoteResult.Lost =>
              stepDownToFollower(
                c.term,
                votedFor = Some(config.localId),
                leader = None
              )
            case VoteResult.Pending => ()

        case _ => ()

  private def handleAppendEntries(
      from: NodeId,
      req: AppendEntriesRequest
  ): Unit =
    if req.term < currentTerm || (req.term == currentTerm && roleState
        .isInstanceOf[Leader])
    then
      sendOrigin(
        from,
        AppendEntriesResponse(currentTerm, success = false, matchIndex = 0L),
        prePersist = true
      )
    else
      val priorVote =
        if req.term > currentTerm then None
        else
          roleState match
            case Follower(_, vf, _)                              => vf
            case Candidate(_, vs) if vs.contains(config.localId) =>
              Some(config.localId)
            case _: Leader => Some(config.localId)
            case _         => None

      stepDownToFollower(
        req.term,
        votedFor = priorVote,
        leader = Some(req.leaderId)
      )

      if req.prevLogIndex < commitIdx then
        // Outdated/already-committed prefix: inform leader of commitIdx so it advances nextIdx safely
        sendOrigin(
          from,
          AppendEntriesResponse(
            req.term,
            success = true,
            matchIndex = commitIdx
          ),
          prePersist = true
        )
      else
        val prevMatches =
          req.prevLogIndex == 0L || termAt(req.prevLogIndex).contains(
            req.prevLogTerm
          )

        if !prevMatches then
          val lIdx = lastLogIndex
          val (cIdx, cTerm) =
            if lIdx < req.prevLogIndex then (lIdx + 1L, None)
            else
              termAt(req.prevLogIndex) match
                case None     => (math.max(1L, lIdx + 1L), None)
                case Some(ct) =>
                  var first = req.prevLogIndex
                  while first > commitIdx + 1L && termAt(first - 1L).contains(
                      ct
                    )
                  do first -= 1L
                  (first, Some(ct))
          sendOrigin(
            from,
            AppendEntriesResponse(
              term = req.term,
              success = false,
              matchIndex = 0L,
              conflictIndex = Some(cIdx),
              conflictTerm = cTerm
            ),
            prePersist = true
          )
        else
          var mutatedLog = false
          val conflictPos = req.entries.indexWhere { entry =>
            termAt(entry.index) match
              case Some(existingTerm) => existingTerm != entry.term
              case None               => false
          }

          if conflictPos >= 0 then
            val conflictEntry = req.entries(conflictPos)
            truncateUnstableAndStorageOverlay(conflictEntry.index)
            val suffix = req.entries.drop(conflictPos)
            unstableEntries = unstableEntries ++ suffix
            suffix.foreach { e =>
              if e.logType == LogType.Configuration then
                ClusterConfig.decode(e.data).foreach(applyClusterConfig)
            }
            mutatedLog = true
          else
            val firstNewPos =
              req.entries.indexWhere(e => termAt(e.index).isEmpty)
            if firstNewPos >= 0 then
              val suffix = req.entries.drop(firstNewPos)
              unstableEntries = unstableEntries ++ suffix
              suffix.foreach { e =>
                if e.logType == LogType.Configuration then
                  ClusterConfig.decode(e.data).foreach(applyClusterConfig)
              }
              mutatedLog = true

          val lastNewIdx = req.prevLogIndex + req.entries.size
          if req.leaderCommit > commitIdx then
            commitIdx =
              math.max(commitIdx, math.min(req.leaderCommit, lastNewIdx))

          sendOrigin(
            from,
            AppendEntriesResponse(
              term = req.term,
              success = true,
              matchIndex = lastNewIdx
            ),
            prePersist = !mutatedLog
          )

  private def truncateUnstableAndStorageOverlay(fromIndex: Long): Unit =
    unstableEntries = unstableEntries.takeWhile(_.index < fromIndex)
    if fromIndex <= storage.lastIndex then
      unstableTruncateFrom = Some(
        unstableTruncateFrom.map(math.min(_, fromIndex)).getOrElse(fromIndex)
      )

  private def handleAppendEntriesResponse(
      from: NodeId,
      resp: AppendEntriesResponse
  ): Unit =
    if resp.term > currentTerm then
      stepDownToFollower(resp.term, votedFor = None, leader = None)
    else
      roleState match
        case l: Leader if resp.term == l.term =>
          tracker = tracker.updateProgress(from, _.copy(recentActive = true))
          if tracker.quorumRecentlyActive(config.localId) then
            val leaseTicks =
              math.max(1L, (config.electionTimeoutTicks * 2L) / 3L)
            roleState = l.copy(
              recentlyActive = l.recentlyActive + from,
              leaseUntil = logicalTick + leaseTicks
            )

          if resp.success then
            var advanced = false
            var retryDroppedSnapshot = false
            tracker = tracker.updateProgress(
              from,
              pr => {
                val (updated0, moved) = pr.maybeUpdate(resp.matchIndex)
                advanced = moved
                val updated1 = updated0.state match
                  case ProgressState.Probe =>
                    if moved then updated0.becomeReplicate() else updated0
                  case ProgressState.Replicate
                      if !moved && updated0.matched < lastLogIndex =>
                    updated0.becomeProbe()
                  case ProgressState.Snapshot(snapIdx) =>
                    if updated0.matched >= snapIdx then updated0.becomeProbe()
                    else
                      retryDroppedSnapshot = true
                      updated0.copy(
                        state = ProgressState.Probe,
                        nextIdx = math.max(1L, updated0.matched + 1L),
                        probeSent = false,
                        inflights = updated0.inflights.reset
                      )
                  case _ => updated0
                updated1
              }
            )
            syncLeaderMapsFromTracker()

            val currentL = roleState.asInstanceOf[Leader]
            resolveLeaderReads(currentL, from)

            if advanced then
              maybeAdvanceLeaderCommit(roleState.asInstanceOf[Leader])

            roleState match
              case lAfter: Leader =>
                lAfter.transferTarget match
                  case Some(target) if target == from =>
                    val m =
                      tracker.progress.get(target).map(_.matched).getOrElse(0L)
                    if m >= lastLogIndex then
                      roleState =
                        lAfter.copy(transferTarget = None, leaseUntil = 0L)
                      sendOrigin(
                        target,
                        TimeoutNowRequest(lAfter.term, config.localId),
                        prePersist = true
                      )
                    else sendAppend(from, forceSendEmpty = false)
                  case _ =>
                    if retryDroppedSnapshot then
                      sendAppend(from, forceSendEmpty = true)
                    else if tracker.progress
                        .get(from)
                        .exists(p => p.nextIdx <= lastLogIndex && !p.isPaused)
                    then sendAppend(from, forceSendEmpty = false)
              case _ => ()
          else
            var decremented = false
            tracker = tracker.updateProgress(
              from,
              pr => {
                val (updated, dec) = pr.maybeDecrTo(
                  rejectedIndex = pr.nextIdx - 1L,
                  conflictIndex = resp.conflictIndex,
                  conflictTerm = resp.conflictTerm,
                  findLastIndexOfTerm = findLastIndexWithTerm
                )
                decremented = dec
                updated
              }
            )
            syncLeaderMapsFromTracker()
            if decremented then sendAppend(from, forceSendEmpty = true)

        case _ => ()

  private def findLastIndexWithTerm(targetTerm: Long): Option[Long] =
    var idx = lastLogIndex
    val floor = storage.firstIndex
    var result: Option[Long] = None
    while idx >= math.max(1L, floor - 1L) && result.isEmpty do
      if termAt(idx).contains(targetTerm) then result = Some(idx)
      idx -= 1L
    result

  private def maybeAdvanceLeaderCommit(leader: Leader): Unit =
    val candidate = tracker.maximalCommittedIndex
    if candidate > commitIdx && termAt(candidate).contains(leader.term) then
      val prevCommit = commitIdx
      commitIdx = candidate
      roleState = leader.withCommitIndex(commitIdx)
      bcastAppend(forceSendEmpty = true)

      // Raft §6 Joint Consensus auto-finalization: once C_old,new is committed,
      // automatically append and replicate C_new (or step down if removed!).
      val newlyCommitted = entriesBetween(prevCommit + 1L, commitIdx + 1L)
      newlyCommitted.foreach { entry =>
        if entry.logType == LogType.Configuration then
          ClusterConfig.decode(entry.data).foreach { committedCfg =>
            if committedCfg.isInJointConsensus && tracker.config.isInJointConsensus
            then
              val finalCfg = tracker.config.completeJointConsensus
              val _ = appendLeaderEntry(
                LogType.Configuration,
                ClusterConfig.encode(finalCfg),
                leader.term
              )
              applyClusterConfig(finalCfg)
              bcastAppend()
            else if !committedCfg.isVoter(config.localId) then
              stepDownToFollower(leader.term, votedFor = None, leader = None)
          }
      }

      // Flush any readStates that were waiting on current-term commit
      roleState match
        case lNow: Leader if lNow.pendingReads.nonEmpty =>
          resolveLeaderReads(lNow, config.localId)
        case _ => ()

  private def resolveLeaderReads(leader: Leader, ackFrom: NodeId): Unit =
    if leader.pendingReads.nonEmpty then
      var remaining = Map.empty[String, PendingRead]
      leader.pendingReads.foreach { case (ctx, pending) =>
        val acks = pending.acks + config.localId + ackFrom
        if tracker.config.hasQuorum(acks) && hasCommittedInCurrentTerm(
            leader.term
          )
        then
          val effectiveReadIdx = math.max(pending.readIndex, commitIdx)
          readyReadStates = readyReadStates :+ ReadState(effectiveReadIdx, ctx)
        else remaining = remaining.updated(ctx, pending.copy(acks = acks))
      }
      roleState = leader.copy(pendingReads = remaining)

  private def handleInstallSnapshot(
      from: NodeId,
      req: InstallSnapshotRequest
  ): Unit =
    if req.term < currentTerm then
      sendOrigin(
        from,
        InstallSnapshotResponse(currentTerm, success = false, matchIndex = 0L),
        prePersist = true
      )
    else
      val priorVote =
        if req.term > currentTerm then None
        else
          roleState match
            case Follower(_, vf, _) => vf
            case _                  => None
      stepDownToFollower(
        req.term,
        votedFor = priorVote,
        leader = Some(req.leaderId)
      )

      if !req.done then
        sendOrigin(
          from,
          InstallSnapshotResponse(req.term, success = true, matchIndex = 0L),
          prePersist = true
        )
      else if req.lastIncludedIndex <= commitIdx then
        sendOrigin(
          from,
          InstallSnapshotResponse(
            req.term,
            success = true,
            matchIndex = commitIdx
          ),
          prePersist = true
        )
      else
        val (decodedCfg, stateBytes) = SnapshotMetadata.decodePayload(req.data)
        val meta = SnapshotMetadata(
          req.lastIncludedIndex,
          req.lastIncludedTerm,
          decodedCfg,
          stateBytes
        )
        val matchingExistingPrefix =
          termAt(req.lastIncludedIndex).contains(req.lastIncludedTerm)
        pendingSnapshotMeta = Some(meta)
        decodedCfg.foreach(applyClusterConfig)

        if matchingExistingPrefix then
          unstableEntries =
            unstableEntries.dropWhile(_.index <= req.lastIncludedIndex)
        else
          unstableEntries = Vector.empty
          unstableTruncateFrom = None

        commitIdx = math.max(commitIdx, req.lastIncludedIndex)
        sendOrigin(
          from,
          InstallSnapshotResponse(
            req.term,
            success = true,
            matchIndex = req.lastIncludedIndex
          ),
          prePersist = false
        )

  private def handleInstallSnapshotResponse(
      from: NodeId,
      resp: InstallSnapshotResponse
  ): Unit =
    if resp.term > currentTerm then
      stepDownToFollower(resp.term, votedFor = None, leader = None)
    else
      roleState match
        case l: Leader if resp.term == l.term && resp.success =>
          tracker = tracker.updateProgress(
            from,
            pr => {
              val (u, _) = pr.maybeUpdate(resp.matchIndex)
              u.copy(recentActive = true).becomeProbe()
            }
          )
          syncLeaderMapsFromTracker()
          maybeAdvanceLeaderCommit(roleState.asInstanceOf[Leader])
          sendAppend(from, forceSendEmpty = false)
        case _ => ()

  private def syncLeaderMapsFromTracker(): Unit =
    roleState match
      case l: Leader =>
        val peers = tracker.config.allNodes - config.localId
        val nextM = peers
          .flatMap(id => tracker.progress.get(id).map(id -> _.nextIdx))
          .toMap
        val matchM = peers
          .flatMap(id => tracker.progress.get(id).map(id -> _.matched))
          .toMap
        roleState = l.copy(nextIndex = nextM, matchIndex = matchM)
      case _ => ()

  private def nextRandomizedElectionTimeout(): Int =
    // Pure SplitMix64 step — deterministic per-node jitter in [T, 2T)
    prngState += 0x9e3779b97f4a7c15L
    var z = prngState
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z = z ^ (z >>> 31)
    val base = config.electionTimeoutTicks
    val jitter = math.abs((z & 0x7fffffffL).toInt) % base
    base + jitter
