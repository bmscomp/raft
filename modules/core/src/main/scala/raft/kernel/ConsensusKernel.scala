package raft.kernel

import scala.annotation.tailrec

import raft.logic.{ProgressState, ProgressTracker, VoteResult}
import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.raw.{LightReady, OutboundMessage, RaftStorage, Ready, SoftState}
import raft.state.*
import raft.state.NodeState.*

/** Unpersisted log overlay buffered inside [[KernelState]] until the host
  * flushes [[Ready]] and invokes [[ConsensusKernel.advance]].
  */
final case class UnstableLog(
    truncateFrom: Option[Long] = None,
    entries: Vector[Log] = Vector.empty,
    pendingSnapshot: Option[SnapshotMetadata] = None
)

/** Complete, immutable state snapshot of a single Raft consensus replica driven
  * by [[ConsensusKernel]].
  *
  * Contains **zero** mutable fields, threads, clocks, sockets, or file handles:
  * identical `(KernelState, input)` pairs produce bit-for-bit identical next
  * states and [[Ready]] batches.
  */
final case class KernelState(
    roleState: NodeState,
    commitIndex: Long,
    appliedIndex: Long,
    lastPersistedHardState: HardState,
    lastEmittedSoftState: SoftState,
    tracker: ProgressTracker,
    bootstrapClusterConfig: ClusterConfig,
    unstable: UnstableLog = UnstableLog(),
    chunkedSnapshot: Option[PendingSnapshot] = None,
    prngState: Long,
    logicalTick: Long = 0L,
    electionElapsed: Int = 0,
    heartbeatElapsed: Int = 0,
    randomizedElectionTimeout: Int,
    ticksSinceLeaderContact: Int,
    transferElapsed: Int = 0,
    prePersistMsgs: Vector[OutboundMessage] = Vector.empty,
    postPersistMsgs: Vector[OutboundMessage] = Vector.empty,
    readyReadStates: Vector[ReadState] = Vector.empty
):
  def currentTerm: Long = roleState.term

  def clusterConfig: ClusterConfig = tracker.config

  def leaderId(localId: NodeId): Option[NodeId] = roleState match
    case _: Leader                      => Some(localId)
    case f: Follower                    => f.leaderId
    case _: Candidate | _: PreCandidate => None

  def currentSoftState(localId: NodeId): SoftState =
    SoftState(leaderId(localId), roleState)

  def currentHardState(localId: NodeId): HardState =
    val votedForOpt = roleState match
      case f: Follower                              => f.votedFor
      case c: Candidate if c.votesReceived(localId) => Some(localId)
      case _: Leader                                => Some(localId)
      case _                                        =>
        lastPersistedHardState.votedFor.filter(_ =>
          currentTerm == lastPersistedHardState.term
        )
    HardState(currentTerm, votedForOpt, commitIndex)

  /** True iff `term` or `votedFor` has changed relative to
    * [[lastPersistedHardState]] and MUST be durably flushed before any outbound
    * RPC in the new term/vote becomes externally visible (Raft §5.1 / §5.2).
    */
  def hasUnpersistedTermOrVote(localId: NodeId): Boolean =
    val hs = currentHardState(localId)
    hs.term != lastPersistedHardState.term ||
    hs.votedFor != lastPersistedHardState.votedFor

/** Pure, referentially transparent Mealy consensus engine (`0` `var`s, `0`
  * `while` loops, `0` `null`s, `0` runtime casts, `0` thrown exceptions).
  */
object ConsensusKernel:

  /** Construct the initial [[KernelState]] from durable [[RaftStorage]]. */
  def initialize(
      config: RaftConfig,
      storage: RaftStorage,
      initialSeed: Long = 0x9e3779b97f4a7c15L
  ): KernelState =
    val seed0 = initialSeed ^ (config.localId.value.hashCode.toLong << 17)
    val (nextSeed, randTimeout) =
      nextRandomizedTimeout(seed0, config.electionTimeoutTicks)
    val (initHard, initCluster) = storage.initialState
    val initApplied = storage.snapshotMetadata
      .map(s => math.max(s.lastIncludedIndex, 0L))
      .getOrElse(0L)
    val initCommit = math.max(initHard.commitIndex, initApplied)
    val initRole = Follower(
      term = initHard.term,
      votedFor = initHard.votedFor,
      leaderId = None,
      commitIndex = initCommit
    )
    val maxInflight = effectiveMaxInflight(config)
    val initTracker = ProgressTracker.forLeader(
      config.localId,
      initCluster,
      storage.lastIndex,
      maxInflight
    )
    KernelState(
      roleState = initRole,
      commitIndex = initCommit,
      appliedIndex = initApplied,
      lastPersistedHardState = initHard,
      lastEmittedSoftState = SoftState(None, initRole),
      tracker = initTracker,
      bootstrapClusterConfig = initCluster,
      prngState = nextSeed,
      randomizedElectionTimeout = randTimeout,
      ticksSinceLeaderContact = config.electionTimeoutTicks
    )

  private def effectiveMaxInflight(config: RaftConfig): Int =
    if config.pipelining.enabled then config.pipelining.maxInflight else 1

  /** Pure SplitMix64 step returning `(nextSeed, timeoutInRange_[T, 2T))`. */
  def nextRandomizedTimeout(
      prngState: Long,
      baseTicks: Int
  ): (Long, Int) =
    val nextSeed = prngState + 0x9e3779b97f4a7c15L
    val z1 = (nextSeed ^ (nextSeed >>> 30)) * 0xbf58476d1ce4e5b9L
    val z2 = (z1 ^ (z1 >>> 27)) * 0x94d049bb133111ebL
    val z3 = z2 ^ (z2 >>> 31)
    val safeBase = math.max(1, baseTicks)
    val jitter = math.abs((z3 & 0x7fffffffL).toInt) % safeBase
    (nextSeed, safeBase + jitter)

  // === Composite Log Inspection (Storage + Unstable Overlay) ===

  def lastLogIndex(st: KernelState, storage: RaftStorage): Long =
    st.unstable.entries.lastOption
      .map(_.index)
      .orElse(st.unstable.truncateFrom.map(t => math.max(0L, t - 1L)))
      .orElse(st.unstable.pendingSnapshot.map(_.lastIncludedIndex))
      .getOrElse(storage.lastIndex)

  def termAt(
      st: KernelState,
      storage: RaftStorage,
      index: Long
  ): Option[Long] =
    if index == 0L then Some(0L)
    else
      st.unstable.pendingSnapshot
        .collect {
          case s if s.lastIncludedIndex == index => s.lastIncludedTerm
        }
        .orElse(st.unstable.entries.find(_.index == index).map(_.term))
        .orElse {
          st.unstable.truncateFrom match
            case Some(cut) if index >= cut => None
            case _                         => storage.term(index)
        }

  def lastLogTerm(st: KernelState, storage: RaftStorage): Long =
    termAt(st, storage, lastLogIndex(st, storage)).getOrElse(0L)

  def entriesBetween(
      st: KernelState,
      storage: RaftStorage,
      low: Long,
      high: Long
  ): Vector[Log] =
    if low >= high then Vector.empty
    else
      val storageLimit = st.unstable.truncateFrom match
        case Some(cut) => math.min(high, cut)
        case None      => high
      st.unstable.entries.headOption.map(_.index) match
        case None =>
          if low >= storageLimit then Vector.empty
          else storage.entries(low, storageLimit)
        case Some(uStart) if high <= uStart =>
          val capped = math.min(storageLimit, uStart)
          if low >= capped then Vector.empty
          else storage.entries(low, capped)
        case Some(uStart) if low >= uStart =>
          st.unstable.entries.filter(e => e.index >= low && e.index < high)
        case Some(uStart) =>
          val persistedEnd = math.min(storageLimit, uStart)
          val persistedPrefix =
            if low >= persistedEnd then Vector.empty
            else storage.entries(low, persistedEnd)
          persistedPrefix ++
            st.unstable.entries.filter(e => e.index >= uStart && e.index < high)

  // === Tick-Driven Logical Clock ===

  def tick(
      st0: KernelState,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    val st1 = st0.copy(
      logicalTick = st0.logicalTick + 1L,
      ticksSinceLeaderContact = st0.ticksSinceLeaderContact + 1
    )
    st1.roleState match
      case l: Leader =>
        val hbNext = st1.heartbeatElapsed + 1
        val elNext = st1.electionElapsed + 1

        val st2 = l.transferTarget match
          case Some(_) =>
            val trNext = st1.transferElapsed + 1
            if trNext >= config.electionTimeoutTicks then
              st1.copy(
                roleState = l.copy(transferTarget = None),
                transferElapsed = 0,
                heartbeatElapsed = hbNext,
                electionElapsed = elNext
              )
            else
              st1.copy(
                transferElapsed = trNext,
                heartbeatElapsed = hbNext,
                electionElapsed = elNext
              )
          case None =>
            st1.copy(heartbeatElapsed = hbNext, electionElapsed = elNext)

        val st3 =
          if st2.electionElapsed >= config.electionTimeoutTicks then
            val resetEl = st2.copy(electionElapsed = 0)
            if config.checkQuorumEnabled then
              if resetEl.tracker.quorumRecentlyActive(config.localId) then
                resetEl.copy(
                  tracker = resetEl.tracker.resetRecentActive(config.localId)
                )
              else
                stepDownToFollower(
                  resetEl,
                  config,
                  l.term,
                  votedFor = Some(config.localId),
                  leader = None
                )
            else resetEl
          else st2

        st3.roleState match
          case _: Leader
              if st3.heartbeatElapsed >= config.heartbeatIntervalTicks =>
            bcastHeartbeat(st3.copy(heartbeatElapsed = 0), config, storage)
          case _ => st3

      case _ =>
        if st1.tracker.config.canCampaign(config.localId) then
          val elNext = st1.electionElapsed + 1
          if elNext >= st1.randomizedElectionTimeout then
            val (seed2, nextRand) =
              nextRandomizedTimeout(st1.prngState, config.electionTimeoutTicks)
            val prepped = st1.copy(
              electionElapsed = 0,
              prngState = seed2,
              randomizedElectionTimeout = nextRand
            )
            campaign(
              prepped,
              config,
              storage,
              isPreVote =
                config.preVoteEnabled && !isSingleVoterQuorum(prepped, config),
              isTransfer = false
            )
          else st1.copy(electionElapsed = elNext)
        else st1

  // === Client & Admin Proposals ===

  def propose(
      st: KernelState,
      payload: Bytes,
      config: RaftConfig,
      storage: RaftStorage
  ): (KernelState, Either[RaftError, Long]) =
    st.roleState match
      case l: Leader =>
        l.transferTarget match
          case Some(target) =>
            (st, Left(RaftError.LeadershipTransferInProgress(target)))
          case None =>
            val (st1, idx) =
              appendLeaderEntry(st, storage, LogType.Command, payload, l.term)
            (bcastAppend(st1, config, storage), Right(idx))
      case f: Follower =>
        (st, Left(RaftError.NotLeader(f.leaderId)))
      case _ =>
        (st, Left(RaftError.ElectionInProgress))

  def proposeBatch(
      st: KernelState,
      commands: Seq[Bytes],
      config: RaftConfig,
      storage: RaftStorage
  ): (KernelState, Either[RaftError, Vector[Long]]) =
    st.roleState match
      case l: Leader =>
        l.transferTarget match
          case Some(target) =>
            (st, Left(RaftError.LeadershipTransferInProgress(target)))
          case None =>
            if commands.isEmpty then (st, Right(Vector.empty))
            else
              val (stAppended, indices) =
                commands.foldLeft((st, Vector.empty[Long])) {
                  case ((cur, acc), cmd) =>
                    val (nxt, idx) = appendLeaderEntry(
                      cur,
                      storage,
                      LogType.Command,
                      cmd,
                      l.term
                    )
                    (nxt, acc :+ idx)
                }
              (bcastAppend(stAppended, config, storage), Right(indices))
      case f: Follower =>
        (st, Left(RaftError.NotLeader(f.leaderId)))
      case _ =>
        (st, Left(RaftError.ElectionInProgress))

  def proposeConfChange(
      st: KernelState,
      newMembers: Map[NodeId, NodeRole],
      config: RaftConfig,
      storage: RaftStorage
  ): (KernelState, Either[RaftError, Long]) =
    st.roleState match
      case l: Leader =>
        l.transferTarget match
          case Some(target) =>
            (st, Left(RaftError.LeadershipTransferInProgress(target)))
          case None =>
            if !hasCommittedInCurrentTerm(st, storage, l.term) then
              (st, Left(RaftError.LeaderNotReadyInTerm(l.term)))
            else if st.tracker.config.isInJointConsensus ||
              hasUncommittedConfigEntry(st, storage)
            then
              (
                st,
                Left(RaftError.ConfigurationChangeInProgress(st.tracker.config))
              )
            else if !newMembers.values.exists(r =>
                r == NodeRole.Voter || r == NodeRole.Witness
              )
            then
              (
                st,
                Left(
                  RaftError.ProposalDropped(
                    "Target configuration must retain at least one voter"
                  )
                )
              )
            else
              val nextCfg =
                if newMembers == st.tracker.config.members then
                  st.tracker.config
                else st.tracker.config.beginJointConsensus(newMembers)
              val stCfg = applyClusterConfig(st, config, storage, nextCfg)
              val (stAppended, idx) = appendLeaderEntry(
                stCfg,
                storage,
                LogType.Configuration,
                ClusterConfig.encodeBytes(nextCfg),
                l.term
              )
              (
                bcastAppend(stAppended, config, storage, forceSendEmpty = true),
                Right(idx)
              )
      case f: Follower =>
        (st, Left(RaftError.NotLeader(f.leaderId)))
      case _ =>
        (st, Left(RaftError.ElectionInProgress))

  def readIndex(
      st: KernelState,
      requestCtx: String,
      config: RaftConfig,
      storage: RaftStorage
  ): (KernelState, Either[RaftError, Unit]) =
    st.roleState match
      case l: Leader =>
        if isSingleVoterQuorum(st, config) &&
          hasCommittedInCurrentTerm(st, storage, l.term)
        then
          val next = st.copy(
            readyReadStates =
              st.readyReadStates :+ ReadState(st.commitIndex, requestCtx)
          )
          (next, Right(()))
        else if config.leaseReadEnabled && config.checkQuorumEnabled &&
          st.logicalTick < l.leaseUntil &&
          hasCommittedInCurrentTerm(st, storage, l.term)
        then
          val next = st.copy(
            readyReadStates =
              st.readyReadStates :+ ReadState(st.commitIndex, requestCtx)
          )
          (next, Right(()))
        else
          val pending = PendingRead(
            requestId = requestCtx,
            readIndex = st.commitIndex,
            acks = Set(config.localId)
          )
          val updatedLeader = l.copy(
            pendingReads = l.pendingReads.updated(requestCtx, pending)
          )
          val withPending = st.copy(roleState = updatedLeader)
          (bcastHeartbeat(withPending, config, storage), Right(()))
      case f: Follower =>
        (st, Left(RaftError.NotLeader(f.leaderId)))
      case _ =>
        (st, Left(RaftError.ElectionInProgress))

  def transferLeader(
      st: KernelState,
      transferee: NodeId,
      config: RaftConfig,
      storage: RaftStorage
  ): (KernelState, Either[RaftError, Unit]) =
    st.roleState match
      case l: Leader =>
        if transferee == config.localId then (st, Right(()))
        else if !st.tracker.config.canCampaign(transferee) then
          (
            st,
            Left(
              RaftError.ProposalDropped(
                s"Target ${transferee.value} is not an eligible voting member"
              )
            )
          )
        else
          val st1 = st.copy(
            transferElapsed = 0,
            roleState =
              l.copy(transferTarget = Some(transferee), leaseUntil = 0L)
          )
          val matched =
            st1.tracker.progress.get(transferee).map(_.matched).getOrElse(0L)
          val st2 =
            if matched >= lastLogIndex(st1, storage) then
              enqueueMessage(
                st1,
                config,
                transferee,
                TimeoutNowRequest(l.term, config.localId),
                prePersist = true
              )
            else
              sendAppend(
                st1,
                config,
                storage,
                transferee,
                forceSendEmpty = true
              )
          (st2, Right(()))
      case f: Follower =>
        (st, Left(RaftError.NotLeader(f.leaderId)))
      case _ =>
        (st, Left(RaftError.ElectionInProgress))

  // === Protocol Message Step ===

  def step(
      st: KernelState,
      from: NodeId,
      msg: RaftMessage,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    msg match
      case ElectionTimeout =>
        if !st.roleState.isLeader && st.tracker.config.canCampaign(
            config.localId
          )
        then
          campaign(
            st,
            config,
            storage,
            isPreVote =
              config.preVoteEnabled && !isSingleVoterQuorum(st, config),
            isTransfer = false
          )
        else st

      case HeartbeatTimeout =>
        if st.roleState.isLeader then bcastHeartbeat(st, config, storage)
        else st

      case CheckQuorumTimeout =>
        st.roleState match
          case l: Leader if config.checkQuorumEnabled =>
            if st.tracker.quorumRecentlyActive(config.localId) then
              st.copy(tracker = st.tracker.resetRecentActive(config.localId))
            else
              stepDownToFollower(
                st,
                config,
                l.term,
                votedFor = Some(config.localId),
                leader = None
              )
          case _ => st

      case req: ReadIndexRequest =>
        val (st1, res) = readIndex(st, req.requestId, config, storage)
        res match
          case Right(()) => st1
          case Left(_)   =>
            enqueueMessage(
              st1,
              config,
              from,
              ReadIndexResponse(req.requestId, success = false, readIndex = 0L),
              prePersist = true
            )

      case req: TransferLeadershipRequest =>
        transferLeader(st, req.target, config, storage)._1

      case req: TimeoutNowRequest =>
        if req.term >= st.currentTerm &&
          st.tracker.config.canCampaign(config.localId)
        then
          val st1 =
            if req.term > st.currentTerm then
              stepDownToFollower(
                st,
                config,
                req.term,
                votedFor = None,
                leader = Some(req.leaderId)
              )
            else st
          campaign(st1, config, storage, isPreVote = false, isTransfer = true)
        else st

      case req: RequestVoteRequest =>
        handleRequestVote(st, from, req, config, storage)

      case resp: RequestVoteResponse =>
        handleVoteResponse(st, from, resp, config, storage)

      case req: AppendEntriesRequest =>
        handleAppendEntries(st, from, req, config, storage)

      case resp: AppendEntriesResponse =>
        handleAppendEntriesResponse(st, from, resp, config, storage)

      case req: InstallSnapshotRequest =>
        handleInstallSnapshot(st, from, req, config, storage)

      case resp: InstallSnapshotResponse =>
        handleInstallSnapshotResponse(st, from, resp, config, storage)

      case req: AddServerRequest =>
        handleAddServer(st, from, req, config, storage)

      case req: RemoveServerRequest =>
        val targetMap = st.tracker.config.members - req.server
        val (st1, res) = proposeConfChange(st, targetMap, config, storage)
        enqueueMessage(
          st1,
          config,
          from,
          RemoveServerResponse(
            st1.currentTerm,
            success = res.isRight,
            st1.leaderId(config.localId)
          ),
          prePersist = true
        )

      case _: ReadIndexResponse | _: AddServerResponse |
          _: RemoveServerResponse =>
        st

  // === Ready / Advance Lifecycle ===

  def hasReady(
      st: KernelState,
      config: RaftConfig,
      storage: RaftStorage
  ): Boolean =
    st.currentHardState(config.localId) != st.lastPersistedHardState ||
      st.currentSoftState(config.localId) != st.lastEmittedSoftState ||
      st.unstable.truncateFrom.isDefined ||
      st.unstable.entries.nonEmpty ||
      st.unstable.pendingSnapshot.isDefined ||
      st.prePersistMsgs.nonEmpty ||
      st.postPersistMsgs.nonEmpty ||
      st.readyReadStates.nonEmpty ||
      hasUnappliedCommittedEntries(st, storage)

  def ready(st: KernelState, config: RaftConfig, storage: RaftStorage): Ready =
    val hs = st.currentHardState(config.localId)
    val hardOpt = if hs != st.lastPersistedHardState then Some(hs) else None
    val ss = st.currentSoftState(config.localId)
    val softOpt = if ss != st.lastEmittedSoftState then Some(ss) else None
    val committed = collectEntriesToApply(st, storage)

    Ready(
      softState = softOpt,
      hardState = hardOpt,
      truncateFromIndex = st.unstable.truncateFrom,
      entries = st.unstable.entries,
      snapshot = st.unstable.pendingSnapshot,
      messages = st.prePersistMsgs,
      persistedMessages = st.postPersistMsgs,
      committedEntries = committed,
      readStates = st.readyReadStates
    )

  def advance(
      st0: KernelState,
      rd: Ready,
      config: RaftConfig,
      storage: RaftStorage
  ): (KernelState, LightReady) =
    val nextEmittedSoft = rd.softState.getOrElse(st0.lastEmittedSoftState)
    val nextPersistedHard0 = rd.hardState.getOrElse(st0.lastPersistedHardState)

    val (appliedAfterSnap, nextPendingSnap, nextBaseCfg) = rd.snapshot match
      case Some(snap) =>
        val app = math.max(st0.appliedIndex, snap.lastIncludedIndex)
        val remSnap =
          if st0.unstable.pendingSnapshot.contains(snap) then None
          else st0.unstable.pendingSnapshot
        val baseCfg = snap.config.getOrElse(st0.bootstrapClusterConfig)
        (app, remSnap, baseCfg)
      case None =>
        (
          st0.appliedIndex,
          st0.unstable.pendingSnapshot,
          st0.bootstrapClusterConfig
        )

    val appliedAfterCommitted = rd.committedEntries.lastOption match
      case Some(last) => math.max(appliedAfterSnap, last.index)
      case None       => appliedAfterSnap

    val remainingUnstableEntries = rd.entries.lastOption match
      case Some(lastPersisted) =>
        st0.unstable.entries.dropWhile(_.index <= lastPersisted.index)
      case None =>
        st0.unstable.entries

    val stCleared = st0.copy(
      appliedIndex = appliedAfterCommitted,
      lastEmittedSoftState = nextEmittedSoft,
      lastPersistedHardState = nextPersistedHard0,
      bootstrapClusterConfig = nextBaseCfg,
      unstable = UnstableLog(
        truncateFrom = None,
        entries = remainingUnstableEntries,
        pendingSnapshot = nextPendingSnap
      ),
      prePersistMsgs = Vector.empty,
      postPersistMsgs = Vector.empty,
      readyReadStates = Vector.empty
    )

    val stAfterLeaderFlush = (rd.entries.lastOption, stCleared.roleState) match
      case (Some(lastPersisted), l: Leader) =>
        val updatedTracker = stCleared.tracker.updateProgress(
          config.localId,
          p => p.maybeUpdate(lastPersisted.index)._1
        )
        maybeAdvanceLeaderCommit(
          stCleared.copy(tracker = updatedTracker),
          l,
          config,
          storage
        )
      case _ => stCleared

    val newCommitted = collectEntriesToApply(stAfterLeaderFlush, storage)
    val finalApplied = newCommitted.lastOption match
      case Some(last) => math.max(stAfterLeaderFlush.appliedIndex, last.index)
      case None       => stAfterLeaderFlush.appliedIndex

    val followUpMsgs =
      stAfterLeaderFlush.prePersistMsgs ++ stAfterLeaderFlush.postPersistMsgs

    val (finalPersistedHard, commitChangedOpt) =
      if stAfterLeaderFlush.commitIndex > stAfterLeaderFlush.lastPersistedHardState.commitIndex
      then
        (
          stAfterLeaderFlush.lastPersistedHardState.copy(
            commitIndex = stAfterLeaderFlush.commitIndex
          ),
          Some(stAfterLeaderFlush.commitIndex)
        )
      else (stAfterLeaderFlush.lastPersistedHardState, None)

    val finalState = stAfterLeaderFlush.copy(
      appliedIndex = finalApplied,
      lastPersistedHardState = finalPersistedHard,
      prePersistMsgs = Vector.empty,
      postPersistMsgs = Vector.empty
    )

    (
      finalState,
      LightReady(
        commitIndex = commitChangedOpt,
        committedEntries = newCommitted,
        messages = followUpMsgs
      )
    )

  // === Internal Pure Transition Helpers ===

  /** Enqueue an outbound RPC, enforcing Raft §5.1/§5.2 Persistence-Before-RPC:
    * if `hasUnpersistedTermOrVote` holds, **every** message is routed to
    * `postPersistMsgs` (`Ready.persistedMessages`) regardless of `prePersist`.
    */
  private def enqueueMessage(
      st: KernelState,
      config: RaftConfig,
      to: NodeId,
      msg: RaftMessage,
      prePersist: Boolean
  ): KernelState =
    if to == config.localId then st
    else
      val env = OutboundMessage(config.localId, to, msg)
      val allowPrePersist =
        prePersist && !st.hasUnpersistedTermOrVote(config.localId)
      if allowPrePersist then st.copy(prePersistMsgs = st.prePersistMsgs :+ env)
      else st.copy(postPersistMsgs = st.postPersistMsgs :+ env)

  private def isSingleVoterQuorum(
      st: KernelState,
      config: RaftConfig
  ): Boolean =
    st.tracker.config.hasQuorum(Set(config.localId))

  private def hasActiveLeaderLease(
      st: KernelState,
      config: RaftConfig
  ): Boolean =
    config.leaderStickinessEnabled && (st.roleState match
      case _: Leader   => config.checkQuorumEnabled
      case f: Follower =>
        f.leaderId.isDefined && st.ticksSinceLeaderContact < config.electionTimeoutTicks
      case _ => false)

  private def hasCommittedInCurrentTerm(
      st: KernelState,
      storage: RaftStorage,
      term: Long
  ): Boolean =
    st.commitIndex > 0L && termAt(st, storage, st.commitIndex).contains(term)

  private def hasUncommittedConfigEntry(
      st: KernelState,
      storage: RaftStorage
  ): Boolean =
    val start = st.commitIndex + 1L
    val end = lastLogIndex(st, storage) + 1L
    start < end &&
    entriesBetween(st, storage, start, end)
      .exists(_.logType == LogType.Configuration)

  private def hasUnappliedCommittedEntries(
      st: KernelState,
      storage: RaftStorage
  ): Boolean =
    st.commitIndex > st.appliedIndex &&
      collectEntriesToApply(st, storage).nonEmpty

  private def collectEntriesToApply(
      st: KernelState,
      storage: RaftStorage
  ): Vector[Log] =
    val start = math.max(st.appliedIndex + 1L, storage.firstIndex)
    if st.commitIndex < start then Vector.empty
    else entriesBetween(st, storage, start, st.commitIndex + 1L)

  private def stepDownToFollower(
      st: KernelState,
      config: RaftConfig,
      newTerm: Long,
      votedFor: Option[NodeId],
      leader: Option[NodeId]
  ): KernelState =
    val (seed2, nextRand) =
      nextRandomizedTimeout(st.prngState, config.electionTimeoutTicks)
    val contactTicks =
      if leader.isDefined then 0 else st.ticksSinceLeaderContact
    st.copy(
      electionElapsed = 0,
      heartbeatElapsed = 0,
      transferElapsed = 0,
      prngState = seed2,
      randomizedElectionTimeout = nextRand,
      ticksSinceLeaderContact = contactTicks,
      roleState = Follower(newTerm, votedFor, leader, st.commitIndex),
      tracker = st.tracker.resetVotes
    )

  private def campaign(
      st0: KernelState,
      config: RaftConfig,
      storage: RaftStorage,
      isPreVote: Boolean,
      isTransfer: Boolean
  ): KernelState =
    val (seed2, nextRand) =
      nextRandomizedTimeout(st0.prngState, config.electionTimeoutTicks)
    val st1 = st0.copy(
      electionElapsed = 0,
      prngState = seed2,
      randomizedElectionTimeout = nextRand,
      tracker =
        st0.tracker.resetVotes.recordVote(config.localId, granted = true)
    )

    if isSingleVoterQuorum(st1, config) && !isPreVote then
      becomeLeader(st1, config, storage, st1.currentTerm + 1L)
    else if isPreVote then
      val prospectiveTerm = st1.currentTerm + 1L
      val stPre = st1.copy(
        roleState =
          PreCandidate(st1.currentTerm, Set(config.localId), st1.commitIndex)
      )
      val req = RequestVoteRequest(
        term = prospectiveTerm,
        candidateId = config.localId,
        lastLogIndex = lastLogIndex(stPre, storage),
        lastLogTerm = lastLogTerm(stPre, storage),
        isPreVote = true,
        isTransfer = false
      )
      broadcastToVoters(stPre, config, req, prePersist = true)
    else
      val nextTerm = st1.currentTerm + 1L
      val stCand = st1.copy(
        roleState = Candidate(nextTerm, Set(config.localId), st1.commitIndex)
      )
      if stCand.tracker.tallyVotes == VoteResult.Won then
        becomeLeader(stCand, config, storage, nextTerm)
      else
        val req = RequestVoteRequest(
          term = nextTerm,
          candidateId = config.localId,
          lastLogIndex = lastLogIndex(stCand, storage),
          lastLogTerm = lastLogTerm(stCand, storage),
          isPreVote = false,
          isTransfer = isTransfer
        )
        broadcastToVoters(stCand, config, req, prePersist = false)

  private def broadcastToVoters(
      st: KernelState,
      config: RaftConfig,
      msg: RaftMessage,
      prePersist: Boolean
  ): KernelState =
    val targets =
      (st.tracker.config.newVoters ++
        st.tracker.config.oldVoters.getOrElse(Set.empty)) - config.localId
    targets.toVector.sortBy(_.value).foldLeft(st) { (cur, peer) =>
      enqueueMessage(cur, config, peer, msg, prePersist = prePersist)
    }

  private def becomeLeader(
      st0: KernelState,
      config: RaftConfig,
      storage: RaftStorage,
      term: Long
  ): KernelState =
    val baseLast = lastLogIndex(st0, storage)
    val nextTracker = ProgressTracker.forLeader(
      config.localId,
      st0.tracker.config,
      baseLast,
      effectiveMaxInflight(config)
    )
    val peers = nextTracker.config.allNodes - config.localId
    val nextMap = peers.map(_ -> (baseLast + 2L)).toMap
    val matchMap = peers.map(_ -> 0L).toMap
    val leaseTicks = math.max(1L, (config.electionTimeoutTicks * 2L) / 3L)
    val leaderRole = Leader(
      term = term,
      nextIndex = nextMap,
      matchIndex = matchMap,
      commitIndex = st0.commitIndex,
      leaseUntil = st0.logicalTick + leaseTicks,
      recentlyActive = Set(config.localId)
    )
    val stLeader = st0.copy(
      electionElapsed = 0,
      heartbeatElapsed = 0,
      transferElapsed = 0,
      ticksSinceLeaderContact = 0,
      tracker = nextTracker,
      roleState = leaderRole
    )
    // Mandatory §5.4.2 / Figure 8 NoOp entry in the new leader's own term!
    val (stWithNoOp, _) =
      appendLeaderEntry(stLeader, storage, LogType.NoOp, Bytes.empty, term)
    bcastAppend(stWithNoOp, config, storage)

  private def appendLeaderEntry(
      st: KernelState,
      storage: RaftStorage,
      logType: LogType,
      payload: Bytes,
      term: Long
  ): (KernelState, Long) =
    val newIdx = lastLogIndex(st, storage) + 1L
    val entry = new Log(newIdx, term, logType, payload)
    val nextUnstable =
      st.unstable.copy(entries = st.unstable.entries :+ entry)
    (st.copy(unstable = nextUnstable), newIdx)

  private def applyClusterConfig(
      st: KernelState,
      config: RaftConfig,
      storage: RaftStorage,
      cfg: ClusterConfig
  ): KernelState =
    val lIdx = lastLogIndex(st, storage)
    val nextTracker = st.tracker.withConfig(cfg, lIdx)
    val nextRole = st.roleState match
      case l: Leader =>
        val peers = cfg.allNodes - config.localId
        val nextM = peers.map { id =>
          id -> nextTracker.progress
            .get(id)
            .map(_.nextIdx)
            .getOrElse(lIdx + 1L)
        }.toMap
        val matchM = peers.map { id =>
          id -> nextTracker.progress.get(id).map(_.matched).getOrElse(0L)
        }.toMap
        l.copy(nextIndex = nextM, matchIndex = matchM)
      case other => other
    st.copy(tracker = nextTracker, roleState = nextRole)

  /** Reconstruct the authoritative [[ClusterConfig]] from snapshot + surviving
    * persisted/unstable log entries after truncating uncommitted entries
    * (Ongaro §4.1 / §6 configuration rollback safety).
    */
  private def reconstructClusterConfig(
      st: KernelState,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    val snapCfg = st.unstable.pendingSnapshot
      .flatMap(_.config)
      .orElse(storage.snapshotMetadata.flatMap(_.config))
      .getOrElse(st.bootstrapClusterConfig)

    val floor = math.max(1L, storage.firstIndex)
    val ceil = lastLogIndex(st, storage) + 1L
    val surviving =
      if ceil > floor then entriesBetween(st, storage, floor, ceil)
      else Vector.empty

    val resolvedCfg = surviving
      .filter(_.logType == LogType.Configuration)
      .lastOption
      .flatMap(e => ClusterConfig.decodeBytes(e.payload).toOption)
      .getOrElse(snapCfg)

    applyClusterConfig(st, config, storage, resolvedCfg)

  private def syncLeaderMapsFromTracker(
      st: KernelState,
      config: RaftConfig
  ): KernelState =
    st.roleState match
      case l: Leader =>
        val peers = st.tracker.config.allNodes - config.localId
        val nextM = peers
          .flatMap(id => st.tracker.progress.get(id).map(id -> _.nextIdx))
          .toMap
        val matchM = peers
          .flatMap(id => st.tracker.progress.get(id).map(id -> _.matched))
          .toMap
        st.copy(
          roleState = l.copy(
            nextIndex = nextM,
            matchIndex = matchM,
            commitIndex = st.commitIndex
          )
        )
      case _ => st

  private def bcastAppend(
      st: KernelState,
      config: RaftConfig,
      storage: RaftStorage,
      forceSendEmpty: Boolean = false
  ): KernelState =
    val peers =
      (st.tracker.config.allNodes - config.localId).toVector.sortBy(_.value)
    peers.foldLeft(st) { (cur, peer) =>
      sendAppend(cur, config, storage, peer, forceSendEmpty = forceSendEmpty)
    }

  private def bcastHeartbeat(
      st: KernelState,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    val peers =
      (st.tracker.config.allNodes - config.localId).toVector.sortBy(_.value)
    val lIdx = lastLogIndex(st, storage)
    peers.foldLeft(st) { (cur, peer) =>
      val prOpt = cur.tracker.progress.get(peer)
      val prevIdx = math.min(prOpt.map(_.matched).getOrElse(0L), lIdx)
      val prevTerm = termAt(cur, storage, prevIdx).getOrElse(0L)
      val commitForPeer = math.min(cur.commitIndex, prevIdx)
      enqueueMessage(
        cur,
        config,
        peer,
        AppendEntriesRequest(
          term = cur.currentTerm,
          leaderId = config.localId,
          prevLogIndex = prevIdx,
          prevLogTerm = prevTerm,
          entries = Seq.empty,
          leaderCommit = commitForPeer
        ),
        prePersist = true
      )
    }

  @tailrec
  private def sendAppend(
      st: KernelState,
      config: RaftConfig,
      storage: RaftStorage,
      peer: NodeId,
      forceSendEmpty: Boolean
  ): KernelState =
    st.tracker.progress.get(peer) match
      case None                                       => st
      case Some(pr) if pr.isPaused && !forceSendEmpty => st
      case Some(pr)                                   =>
        val prevIdx = pr.nextIdx - 1L
        val prevTermOpt = termAt(st, storage, prevIdx)
        val firstAvail = storage.firstIndex

        if (pr.nextIdx < firstAvail || prevTermOpt.isEmpty) &&
          storage.snapshotMetadata.isDefined
        then
          storage.snapshotMetadata match
            case None       => st
            case Some(snap) =>
              val nextTracker = st.tracker.updateProgress(
                peer,
                _.becomeSnapshot(snap.lastIncludedIndex)
              )
              val wirePayload =
                SnapshotMetadata.encodePayloadBytes(snap.config, snap.payload)
              enqueueMessage(
                st.copy(tracker = nextTracker),
                config,
                peer,
                new InstallSnapshotRequest(
                  term = st.currentTerm,
                  leaderId = config.localId,
                  lastIncludedIndex = snap.lastIncludedIndex,
                  lastIncludedTerm = snap.lastIncludedTerm,
                  offset = 0L,
                  payload = wirePayload,
                  done = true
                ),
                prePersist = true
              )
        else
          val prevTerm = prevTermOpt.getOrElse(0L)
          val lIdx = lastLogIndex(st, storage)
          val upper = math.min(
            lIdx + 1L,
            pr.nextIdx + config.maxEntriesPerRequest.toLong
          )
          val rawEnts =
            if pr.isPaused then Vector.empty
            else entriesBetween(st, storage, pr.nextIdx, upper)
          val ents =
            if st.tracker.config.isWitness(peer) then
              rawEnts.map(_.stripForWitness)
            else rawEnts

          if ents.isEmpty && !forceSendEmpty then st
          else
            val updatedPr =
              if ents.nonEmpty then pr.optimisticUpdate(ents.last.index)
              else pr
            val stUpdatedTracker = syncLeaderMapsFromTracker(
              st.copy(tracker =
                st.tracker.updateProgress(peer, _ => updatedPr)
              ),
              config
            )
            val stSent = enqueueMessage(
              stUpdatedTracker,
              config,
              peer,
              AppendEntriesRequest(
                term = stUpdatedTracker.currentTerm,
                leaderId = config.localId,
                prevLogIndex = prevIdx,
                prevLogTerm = prevTerm,
                entries = ents,
                leaderCommit = stUpdatedTracker.commitIndex
              ),
              prePersist = true
            )
            if ents.nonEmpty && !updatedPr.isPaused && updatedPr.nextIdx <= lIdx
            then
              sendAppend(stSent, config, storage, peer, forceSendEmpty = false)
            else stSent

  private def handleAddServer(
      st: KernelState,
      from: NodeId,
      req: AddServerRequest,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    val role = if req.isLearner then NodeRole.Learner else NodeRole.Voter
    val targetMap = st.tracker.config.members.updated(req.newServer, role)
    st.roleState match
      case l: Leader
          if role == NodeRole.Learner &&
            l.transferTarget.isEmpty &&
            !st.tracker.config.isInJointConsensus &&
            !hasUncommittedConfigEntry(st, storage) =>
        val nextCfg =
          st.tracker.config.addMember(req.newServer, NodeRole.Learner)
        val stCfg = applyClusterConfig(st, config, storage, nextCfg)
        val (stApp, _) = appendLeaderEntry(
          stCfg,
          storage,
          LogType.Configuration,
          ClusterConfig.encodeBytes(nextCfg),
          stCfg.currentTerm
        )
        val stBcast =
          bcastAppend(stApp, config, storage, forceSendEmpty = true)
        enqueueMessage(
          stBcast,
          config,
          from,
          AddServerResponse(
            stBcast.currentTerm,
            success = true,
            stBcast.leaderId(config.localId)
          ),
          prePersist = true
        )

      case _ =>
        val (st1, res) =
          if role == NodeRole.Learner then
            (
              st,
              Left(RaftError.ProposalDropped("Cannot add learner right now"))
            )
          else proposeConfChange(st, targetMap, config, storage)
        enqueueMessage(
          st1,
          config,
          from,
          AddServerResponse(
            st1.currentTerm,
            success = res.isRight,
            st1.leaderId(config.localId)
          ),
          prePersist = true
        )

  private def handleRequestVote(
      st: KernelState,
      from: NodeId,
      req: RequestVoteRequest,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    val logUpToDate =
      LogId(req.lastLogTerm, req.lastLogIndex) >= LogId(
        lastLogTerm(st, storage),
        lastLogIndex(st, storage)
      )

    if req.isPreVote then
      if req.term < st.currentTerm then
        enqueueMessage(
          st,
          config,
          from,
          RequestVoteResponse(
            st.currentTerm,
            voteGranted = false,
            isPreVote = true
          ),
          prePersist = true
        )
      else
        val rejectForLeader =
          hasActiveLeaderLease(st, config) &&
            req.term <= st.currentTerm + 1L && (st.roleState match
              case _: Leader   => true
              case f: Follower =>
                f.leaderId.isDefined &&
                req.term <= f.term + 1L &&
                st.ticksSinceLeaderContact < config.electionTimeoutTicks
              case _ => false)
        val grant = logUpToDate && !rejectForLeader
        enqueueMessage(
          st,
          config,
          from,
          RequestVoteResponse(req.term, voteGranted = grant, isPreVote = true),
          prePersist = true
        )
    else
      val stickyReject = !req.isTransfer && hasActiveLeaderLease(st, config)
      if req.term < st.currentTerm || stickyReject then
        enqueueMessage(
          st,
          config,
          from,
          RequestVoteResponse(
            st.currentTerm,
            voteGranted = false,
            isPreVote = false
          ),
          prePersist = true
        )
      else
        val st1 =
          if req.term > st.currentTerm then
            stepDownToFollower(
              st,
              config,
              req.term,
              votedFor = None,
              leader = None
            )
          else st

        st1.roleState match
          case f: Follower =>
            val canVote =
              f.votedFor.isEmpty || f.votedFor.contains(req.candidateId)
            if canVote && logUpToDate then
              val (seed2, nextRand) =
                nextRandomizedTimeout(
                  st1.prngState,
                  config.electionTimeoutTicks
                )
              val stVoted = st1.copy(
                electionElapsed = 0,
                prngState = seed2,
                randomizedElectionTimeout = nextRand,
                roleState = Follower(
                  req.term,
                  Some(req.candidateId),
                  None,
                  st1.commitIndex
                )
              )
              enqueueMessage(
                stVoted,
                config,
                from,
                RequestVoteResponse(
                  req.term,
                  voteGranted = true,
                  isPreVote = false
                ),
                prePersist = false
              )
            else
              enqueueMessage(
                st1,
                config,
                from,
                RequestVoteResponse(
                  st1.currentTerm,
                  voteGranted = false,
                  isPreVote = false
                ),
                prePersist = false
              )

          case _ =>
            enqueueMessage(
              st1,
              config,
              from,
              RequestVoteResponse(
                st1.currentTerm,
                voteGranted = false,
                isPreVote = false
              ),
              prePersist = false
            )

  private def handleVoteResponse(
      st: KernelState,
      from: NodeId,
      resp: RequestVoteResponse,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    if resp.term > st.currentTerm && !resp.isPreVote then
      stepDownToFollower(st, config, resp.term, votedFor = None, leader = None)
    else
      st.roleState match
        case p: PreCandidate if resp.isPreVote && resp.term == p.term + 1L =>
          val nextTracker = st.tracker.recordVote(from, resp.voteGranted)
          val nextRole = if resp.voteGranted then p.withPreVote(from) else p
          val st1 = st.copy(tracker = nextTracker, roleState = nextRole)
          nextTracker.tallyVotes match
            case VoteResult.Won =>
              campaign(
                st1,
                config,
                storage,
                isPreVote = false,
                isTransfer = false
              )
            case VoteResult.Lost =>
              stepDownToFollower(
                st1,
                config,
                p.term,
                votedFor = None,
                leader = None
              )
            case VoteResult.Pending => st1

        case c: Candidate if !resp.isPreVote && resp.term == c.term =>
          val nextTracker = st.tracker.recordVote(from, resp.voteGranted)
          val nextRole = if resp.voteGranted then c.withVote(from) else c
          val st1 = st.copy(tracker = nextTracker, roleState = nextRole)
          nextTracker.tallyVotes match
            case VoteResult.Won  => becomeLeader(st1, config, storage, c.term)
            case VoteResult.Lost =>
              stepDownToFollower(
                st1,
                config,
                c.term,
                votedFor = Some(config.localId),
                leader = None
              )
            case VoteResult.Pending => st1

        case _ => st

  private def handleAppendEntries(
      st: KernelState,
      from: NodeId,
      req: AppendEntriesRequest,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    if req.term < st.currentTerm ||
      (req.term == st.currentTerm && st.roleState.isLeader)
    then
      enqueueMessage(
        st,
        config,
        from,
        AppendEntriesResponse(st.currentTerm, success = false, matchIndex = 0L),
        prePersist = true
      )
    else
      val priorVote =
        if req.term > st.currentTerm then None
        else
          st.roleState match
            case f: Follower                                     => f.votedFor
            case c: Candidate if c.votesReceived(config.localId) =>
              Some(config.localId)
            case _: Leader => Some(config.localId)
            case _         => None

      val st1 = stepDownToFollower(
        st,
        config,
        req.term,
        votedFor = priorVote,
        leader = Some(req.leaderId)
      )

      if req.prevLogIndex < st1.commitIndex &&
        (req.prevLogIndex + req.entries.size.toLong) <= st1.commitIndex
      then
        enqueueMessage(
          st1,
          config,
          from,
          AppendEntriesResponse(
            req.term,
            success = true,
            matchIndex = st1.commitIndex
          ),
          prePersist = true
        )
      else
        val (effectivePrevIdx, effectiveEntries, prevMatches) =
          if req.prevLogIndex < st1.commitIndex then
            val skip = (st1.commitIndex - req.prevLogIndex).toInt
            val anchorTerm = req.entries(skip - 1).term
            val matches =
              termAt(st1, storage, st1.commitIndex).forall(_ == anchorTerm)
            (st1.commitIndex, req.entries.drop(skip), matches)
          else
            val matches =
              req.prevLogIndex == 0L ||
                termAt(st1, storage, req.prevLogIndex).contains(req.prevLogTerm)
            (req.prevLogIndex, req.entries, matches)

        if !prevMatches then
          val lIdx = lastLogIndex(st1, storage)
          val (cIdx, cTerm) = computeFollowerConflictHint(
            st1,
            storage,
            effectivePrevIdx,
            lIdx
          )
          enqueueMessage(
            st1,
            config,
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
          val conflictPos = effectiveEntries.indexWhere { entry =>
            termAt(st1, storage, entry.index) match
              case Some(existingTerm) => existingTerm != entry.term
              case None               => false
          }

          val (st2, mutatedLog) =
            if conflictPos >= 0 then
              val conflictEntry = effectiveEntries(conflictPos)
              val stTrunc = truncateUnstableAndStorageOverlay(
                st1,
                config,
                storage,
                conflictEntry.index
              )
              val suffix = effectiveEntries.drop(conflictPos).toVector
              val stApp = stTrunc.copy(
                unstable = stTrunc.unstable.copy(entries =
                  stTrunc.unstable.entries ++ suffix
                )
              )
              val stCfg = applyAppendedConfigs(stApp, config, storage, suffix)
              (stCfg, true)
            else
              val firstNewPos = effectiveEntries.indexWhere(e =>
                termAt(st1, storage, e.index).isEmpty
              )
              if firstNewPos >= 0 then
                val suffix = effectiveEntries.drop(firstNewPos).toVector
                val stApp = st1.copy(
                  unstable =
                    st1.unstable.copy(entries = st1.unstable.entries ++ suffix)
                )
                val stCfg = applyAppendedConfigs(stApp, config, storage, suffix)
                (stCfg, true)
              else (st1, false)

          val lastNewIdx = effectivePrevIdx + effectiveEntries.size.toLong
          val nextCommit =
            if req.leaderCommit > st2.commitIndex then
              math.max(st2.commitIndex, math.min(req.leaderCommit, lastNewIdx))
            else st2.commitIndex
          val nextRole = st2.roleState match
            case f: Follower => f.withFollowerCommitIndex(nextCommit)
            case other       => other
          val st3 = st2.copy(commitIndex = nextCommit, roleState = nextRole)

          enqueueMessage(
            st3,
            config,
            from,
            AppendEntriesResponse(
              term = req.term,
              success = true,
              matchIndex = lastNewIdx
            ),
            prePersist = !mutatedLog
          )

  private def applyAppendedConfigs(
      st: KernelState,
      config: RaftConfig,
      storage: RaftStorage,
      suffix: Vector[Log]
  ): KernelState =
    suffix.foldLeft(st) { (cur, entry) =>
      if entry.logType == LogType.Configuration then
        ClusterConfig.decodeBytes(entry.payload) match
          case Right(cfg) => applyClusterConfig(cur, config, storage, cfg)
          case Left(_)    => cur
      else cur
    }

  private def computeFollowerConflictHint(
      st: KernelState,
      storage: RaftStorage,
      prevLogIndex: Long,
      lIdx: Long
  ): (Long, Option[Long]) =
    @tailrec
    def rewind(idx: Long, cTerm: Long, floor: Long): Long =
      if idx > floor && termAt(st, storage, idx - 1L).contains(cTerm) then
        rewind(idx - 1L, cTerm, floor)
      else idx

    if lIdx < prevLogIndex then (lIdx + 1L, None)
    else
      termAt(st, storage, prevLogIndex) match
        case None     => (math.max(1L, lIdx + 1L), None)
        case Some(ct) =>
          val floor = math.max(1L, st.commitIndex + 1L)
          (rewind(prevLogIndex, ct, floor), Some(ct))

  private def truncateUnstableAndStorageOverlay(
      st: KernelState,
      config: RaftConfig,
      storage: RaftStorage,
      fromIndex: Long
  ): KernelState =
    val keptUnstable = st.unstable.entries.takeWhile(_.index < fromIndex)
    val nextTrunc =
      if fromIndex <= storage.lastIndex then
        Some(
          st.unstable.truncateFrom
            .map(math.min(_, fromIndex))
            .getOrElse(fromIndex)
        )
      else st.unstable.truncateFrom
    val stTrunc = st.copy(
      unstable =
        st.unstable.copy(entries = keptUnstable, truncateFrom = nextTrunc)
    )
    reconstructClusterConfig(stTrunc, config, storage)

  private def handleAppendEntriesResponse(
      st: KernelState,
      from: NodeId,
      resp: AppendEntriesResponse,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    if resp.term > st.currentTerm then
      stepDownToFollower(st, config, resp.term, votedFor = None, leader = None)
    else
      st.roleState match
        case l0: Leader if resp.term == l0.term =>
          val trActive =
            st.tracker.updateProgress(from, _.copy(recentActive = true))
          val stActive =
            if trActive.quorumRecentlyActive(config.localId) then
              val leaseTicks =
                math.max(1L, (config.electionTimeoutTicks * 2L) / 3L)
              st.copy(
                tracker = trActive,
                roleState = l0.copy(
                  recentlyActive = l0.recentlyActive + from,
                  leaseUntil = st.logicalTick + leaseTicks
                )
              )
            else st.copy(tracker = trActive)

          val lIdx = lastLogIndex(stActive, storage)

          if resp.success then
            val (nextTracker, (advanced, retryDroppedSnapshot)) =
              stActive.tracker.modifyProgress(from, (false, false)) { pr =>
                val (u0, moved) = pr.maybeUpdate(resp.matchIndex)
                u0.state match
                  case ProgressState.Probe =>
                    val next = if moved then u0.becomeReplicate() else u0
                    (next, (moved, false))
                  case ProgressState.Replicate
                      if !moved && resp.matchIndex == u0.matched && u0.matched < lIdx =>
                    (u0.becomeProbe(), (false, false))
                  case ProgressState.Snapshot(snapIdx) =>
                    if u0.matched >= snapIdx then
                      (u0.becomeProbe(), (moved, false))
                    else
                      (
                        u0.copy(
                          state = ProgressState.Probe,
                          nextIdx = math.max(1L, u0.matched + 1L),
                          probeSent = false,
                          inflights = u0.inflights.reset
                        ),
                        (moved, true)
                      )
                  case _ =>
                    (u0, (moved, false))
              }

            val stSynced = syncLeaderMapsFromTracker(
              stActive.copy(tracker = nextTracker),
              config
            )
            val stReads = stSynced.roleState match
              case curL: Leader =>
                resolveLeaderReads(stSynced, curL, from, config, storage)
              case _ => stSynced

            val stCommitted =
              if advanced then
                stReads.roleState match
                  case curL: Leader =>
                    maybeAdvanceLeaderCommit(stReads, curL, config, storage)
                  case _ => stReads
              else stReads

            stCommitted.roleState match
              case lAfter: Leader =>
                lAfter.transferTarget match
                  case Some(target) if target == from =>
                    val m = stCommitted.tracker.progress
                      .get(target)
                      .map(_.matched)
                      .getOrElse(0L)
                    if m >= lastLogIndex(stCommitted, storage) then
                      val stClearedTransfer = stCommitted.copy(
                        roleState =
                          lAfter.copy(transferTarget = None, leaseUntil = 0L)
                      )
                      enqueueMessage(
                        stClearedTransfer,
                        config,
                        target,
                        TimeoutNowRequest(lAfter.term, config.localId),
                        prePersist = true
                      )
                    else
                      sendAppend(
                        stCommitted,
                        config,
                        storage,
                        from,
                        forceSendEmpty = false
                      )
                  case _ =>
                    if retryDroppedSnapshot then
                      sendAppend(
                        stCommitted,
                        config,
                        storage,
                        from,
                        forceSendEmpty = true
                      )
                    else if stCommitted.tracker.progress
                        .get(from)
                        .exists(p =>
                          p.nextIdx <= lastLogIndex(
                            stCommitted,
                            storage
                          ) && !p.isPaused
                        )
                    then
                      sendAppend(
                        stCommitted,
                        config,
                        storage,
                        from,
                        forceSendEmpty = false
                      )
                    else stCommitted
              case _ => stCommitted
          else
            val floor = math.max(1L, storage.firstIndex - 1L)
            val (nextTracker, decremented) =
              stActive.tracker.modifyProgress(from, false) { pr =>
                pr.maybeDecrTo(
                  rejectedIndex = pr.nextIdx - 1L,
                  conflictIndex = resp.conflictIndex,
                  conflictTerm = resp.conflictTerm,
                  findLastIndexOfTerm = t =>
                    findLastIndexWithTerm(
                      stActive,
                      storage,
                      t,
                      lastLogIndex(stActive, storage),
                      floor
                    )
                )
              }
            val stSynced = syncLeaderMapsFromTracker(
              stActive.copy(tracker = nextTracker),
              config
            )
            if decremented then
              sendAppend(stSynced, config, storage, from, forceSendEmpty = true)
            else stSynced

        case _ => st

  @tailrec
  private def findLastIndexWithTerm(
      st: KernelState,
      storage: RaftStorage,
      targetTerm: Long,
      idx: Long,
      floor: Long
  ): Option[Long] =
    if idx < floor then None
    else if termAt(st, storage, idx).contains(targetTerm) then Some(idx)
    else findLastIndexWithTerm(st, storage, targetTerm, idx - 1L, floor)

  private def maybeAdvanceLeaderCommit(
      st0: KernelState,
      leader: Leader,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    val candidate = st0.tracker.maximalCommittedIndex
    if candidate <= st0.commitIndex ||
      !termAt(st0, storage, candidate).contains(leader.term)
    then st0
    else
      val prevCommit = st0.commitIndex
      val st1 = st0.copy(
        commitIndex = candidate,
        roleState = leader.withCommitIndex(candidate)
      )
      val stBcast = bcastAppend(st1, config, storage, forceSendEmpty = true)
      val newlyCommitted =
        entriesBetween(stBcast, storage, prevCommit + 1L, candidate + 1L)

      val stAfterCfgs = newlyCommitted.foldLeft(stBcast) { (cur, entry) =>
        if entry.logType == LogType.Configuration then
          ClusterConfig.decodeBytes(entry.payload) match
            case Right(committedCfg) =>
              if committedCfg.isInJointConsensus &&
                cur.tracker.config.isInJointConsensus
              then
                val finalCfg = cur.tracker.config.completeJointConsensus
                val stFinalCfg =
                  applyClusterConfig(cur, config, storage, finalCfg)
                val (stApp, _) = appendLeaderEntry(
                  stFinalCfg,
                  storage,
                  LogType.Configuration,
                  ClusterConfig.encodeBytes(finalCfg),
                  leader.term
                )
                bcastAppend(stApp, config, storage, forceSendEmpty = true)
              else if !committedCfg.canCampaign(config.localId) then
                stepDownToFollower(
                  cur,
                  config,
                  leader.term,
                  votedFor = None,
                  leader = None
                )
              else cur
            case Left(_) => cur
        else cur
      }

      stAfterCfgs.roleState match
        case lNow: Leader if lNow.pendingReads.nonEmpty =>
          resolveLeaderReads(stAfterCfgs, lNow, config.localId, config, storage)
        case _ => stAfterCfgs

  private def resolveLeaderReads(
      st: KernelState,
      leader: Leader,
      ackFrom: NodeId,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    if leader.pendingReads.isEmpty then st
    else
      val readyInTerm = hasCommittedInCurrentTerm(st, storage, leader.term)
      val (remaining, confirmed) =
        leader.pendingReads.foldLeft(
          (Map.empty[String, PendingRead], Vector.empty[ReadState])
        ) { case ((remAcc, rsAcc), (ctx, pending)) =>
          val acks = pending.acks + config.localId + ackFrom
          if st.tracker.config.hasQuorum(acks) && readyInTerm then
            val effectiveReadIdx = math.max(pending.readIndex, st.commitIndex)
            (remAcc, rsAcc :+ ReadState(effectiveReadIdx, ctx))
          else (remAcc.updated(ctx, pending.copy(acks = acks)), rsAcc)
        }
      st.copy(
        roleState = leader.copy(pendingReads = remaining),
        readyReadStates = st.readyReadStates ++ confirmed
      )

  private def handleInstallSnapshot(
      st: KernelState,
      from: NodeId,
      req: InstallSnapshotRequest,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    if req.term < st.currentTerm then
      enqueueMessage(
        st,
        config,
        from,
        InstallSnapshotResponse(
          st.currentTerm,
          success = false,
          matchIndex = 0L
        ),
        prePersist = true
      )
    else
      val priorVote =
        if req.term > st.currentTerm then None
        else
          st.roleState match
            case f: Follower => f.votedFor
            case _           => None

      val st1 = stepDownToFollower(
        st,
        config,
        req.term,
        votedFor = priorVote,
        leader = Some(req.leaderId)
      )

      if req.lastIncludedIndex <= st1.commitIndex then
        val stClean = st1.copy(chunkedSnapshot = None)
        enqueueMessage(
          stClean,
          config,
          from,
          InstallSnapshotResponse(
            req.term,
            success = true,
            matchIndex = stClean.commitIndex
          ),
          prePersist = true
        )
      else
        val chunkAccOpt: Option[PendingSnapshot] =
          if req.offset == 0L then
            Some(
              PendingSnapshot(
                lastIncludedIndex = req.lastIncludedIndex,
                lastIncludedTerm = req.lastIncludedTerm,
                offset = 0L
              ).appendChunkBytes(req.payload)
            )
          else
            st1.chunkedSnapshot.collect {
              case pending
                  if pending.lastIncludedIndex == req.lastIncludedIndex &&
                    pending.lastIncludedTerm == req.lastIncludedTerm &&
                    pending.offset == req.offset =>
                pending.appendChunkBytes(req.payload)
            }

        chunkAccOpt match
          case None =>
            enqueueMessage(
              st1.copy(chunkedSnapshot = None),
              config,
              from,
              InstallSnapshotResponse(
                req.term,
                success = false,
                matchIndex = 0L
              ),
              prePersist = true
            )

          case Some(accumulated) if !req.done =>
            enqueueMessage(
              st1.copy(chunkedSnapshot = Some(accumulated)),
              config,
              from,
              InstallSnapshotResponse(
                req.term,
                success = true,
                matchIndex = 0L
              ),
              prePersist = true
            )

          case Some(accumulated) =>
            val fullWire = accumulated.toBytesValue
            val (decodedCfg, stateBytes) =
              SnapshotMetadata.decodePayloadBytes(fullWire)
            val meta = SnapshotMetadata(
              req.lastIncludedIndex,
              req.lastIncludedTerm,
              decodedCfg,
              stateBytes
            )
            val matchingExistingPrefix = termAt(
              st1,
              storage,
              req.lastIncludedIndex
            ).contains(req.lastIncludedTerm)

            val nextUnstable =
              if matchingExistingPrefix then
                st1.unstable.copy(
                  entries = st1.unstable.entries
                    .dropWhile(_.index <= req.lastIncludedIndex),
                  pendingSnapshot = Some(meta)
                )
              else
                UnstableLog(
                  truncateFrom = None,
                  entries = Vector.empty,
                  pendingSnapshot = Some(meta)
                )

            val nextCommit = math.max(st1.commitIndex, req.lastIncludedIndex)
            val nextRole = st1.roleState match
              case f: Follower => f.withFollowerCommitIndex(nextCommit)
              case other       => other

            val stWithSnap = st1.copy(
              commitIndex = nextCommit,
              roleState = nextRole,
              unstable = nextUnstable,
              chunkedSnapshot = None
            )
            val stCfgApplied = decodedCfg match
              case Some(cfg) =>
                applyClusterConfig(stWithSnap, config, storage, cfg)
              case None => stWithSnap

            enqueueMessage(
              stCfgApplied,
              config,
              from,
              InstallSnapshotResponse(
                req.term,
                success = true,
                matchIndex = req.lastIncludedIndex
              ),
              prePersist = false
            )

  private def handleInstallSnapshotResponse(
      st: KernelState,
      from: NodeId,
      resp: InstallSnapshotResponse,
      config: RaftConfig,
      storage: RaftStorage
  ): KernelState =
    if resp.term > st.currentTerm then
      stepDownToFollower(st, config, resp.term, votedFor = None, leader = None)
    else
      st.roleState match
        case l: Leader if resp.term == l.term && resp.success =>
          val nextTracker = st.tracker.updateProgress(
            from,
            pr => {
              val (u, _) = pr.maybeUpdate(resp.matchIndex)
              u.copy(recentActive = true).becomeProbe()
            }
          )
          val stSynced = syncLeaderMapsFromTracker(
            st.copy(
              tracker = nextTracker,
              roleState = l.copy(recentlyActive = l.recentlyActive + from)
            ),
            config
          )
          val stCommitted = stSynced.roleState match
            case curL: Leader =>
              maybeAdvanceLeaderCommit(stSynced, curL, config, storage)
            case _ => stSynced
          sendAppend(stCommitted, config, storage, from, forceSendEmpty = false)
        case _ => st
