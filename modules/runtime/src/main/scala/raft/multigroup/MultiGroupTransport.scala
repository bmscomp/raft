package raft.multigroup

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef

import raft.message.RaftMessage
import raft.message.RaftMessage.*
import raft.spi.RaftTransport
import raft.state.{GroupId, NodeId}

/** Group-aware transport multiplexing multiple Raft groups over a shared wire
  * connection with `O(1)` bounded mailbox demultiplexing, per-host **Coalesced
  * Heartbeats**, and bounded/strict unknown-group DoS protection (`0` `var`s,
  * `0` `while` loops, `0` runtime casts).
  */
class MultiGroupTransport[F[_]: Async] private (
    val localId: NodeId,
    registeredQueuesRef: Ref[F, Map[GroupId, Queue[F, (NodeId, RaftMessage)]]],
    stagingQueuesRef: Ref[F, Map[GroupId, Queue[F, (NodeId, RaftMessage)]]],
    closedGroupsRef: Ref[F, Set[GroupId]],
    strictRegistrationRef: Ref[F, Boolean],
    sendBatchEnvelopes: Vector[GroupEnvelope] => F[Unit],
    maxQueueSize: Int,
    maxUnregisteredGroups: Int,
    val coalescing: CoalescedHeartbeatConfig,
    pendingHeartbeatsRef: Ref[F, Map[NodeId, Vector[GroupEnvelope]]],
    statsRef: Ref[F, CoalescerStats],
    shutdownRef: SignallingRef[F, Boolean],
    coalescerFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]]
):
  private val F = Async[F]

  /** Enable or disable strict group registration.
    *
    * When `strict == true`, inbound envelopes targeting a [[GroupId]] that has
    * not been explicitly registered via [[registerGroup]] (or subscribed via
    * `receive`) are dropped immediately in `O(1)` and counted in
    * `CoalescerStats.droppedUnknownGroupEnvelopes`. When `strict == false`
    * (default), up to `maxUnregisteredGroups` startup groups may stage early
    * envelopes before registration, after which excess unknown groups are
    * dropped.
    */
  def setStrictGroupRegistration(strict: Boolean): F[Unit] =
    strictRegistrationRef.set(strict) *>
      (if strict then stagingQueuesRef.set(Map.empty) else F.unit)

  /** Start the background heartbeat coalescer fiber (idempotent). */
  def startCoalescer: F[Unit] =
    if !coalescing.enabled then F.unit
    else
      cancelCoalescer *>
        shutdownRef.set(false) *>
        runCoalescerLoop.start.flatMap(f => coalescerFiberRef.set(Some(f)))

  /** Stop the background coalescer fiber and flush remaining queued heartbeats.
    */
  def shutdown: F[Unit] =
    shutdownRef.set(true) *>
      cancelCoalescer *>
      flushCoalescedHeartbeats.attempt.void

  /** Inspect live heartbeat coalescing and unknown-group drop counters. */
  def coalescerStats: F[CoalescerStats] = statsRef.get

  /** Immediately flush all buffered per-peer heartbeats as coalesced batches.
    */
  def flushCoalescedHeartbeats: F[Unit] =
    pendingHeartbeatsRef.getAndSet(Map.empty).flatMap { pendingByPeer =>
      val slices: List[Vector[GroupEnvelope]] =
        pendingByPeer.values.toList.flatMap { envs =>
          val (resps, reqs) = envs.partition(e => isAppendResponse(e.message))
          List(reqs, resps).filter(_.nonEmpty)
        }
      if slices.isEmpty then F.unit
      else
        val totalCoalesced = slices.map(_.size.toLong).sum
        statsRef.update(s =>
          s.copy(
            batchesFlushed = s.batchesFlushed + slices.size.toLong,
            envelopesCoalesced = s.envelopesCoalesced + totalCoalesced
          )
        ) *>
          slices.parTraverse_(slice => sendBatchEnvelopes(slice).attempt.void)
    }

  /** Eagerly allocate (or re-activate) the bounded mailbox for `groupId`,
    * draining any pre-registration startup envelopes, and return its per-group
    * [[RaftTransport]] view.
    */
  def registerGroup(groupId: GroupId): F[RaftTransport[F]] =
    closedGroupsRef.update(_ - groupId) *>
      activateGroupQueue(groupId).as(transportForGroup(groupId))

  /** Remove a group's mailbox and purge any buffered heartbeats for `groupId`.
    */
  def unregisterGroup(groupId: GroupId): F[Unit] =
    closedGroupsRef.update(_ + groupId) *>
      registeredQueuesRef.update(_ - groupId) *>
      stagingQueuesRef.update(_ - groupId) *>
      pendingHeartbeatsRef.update { byPeer =>
        byPeer.map { case (peer, vec) =>
          peer -> vec.filterNot(_.groupId == groupId)
        }
      }

  /** Create a per-group transport view backed by `groupId`'s bounded mailbox.
    */
  def transportForGroup(groupId: GroupId): RaftTransport[F] =
    new RaftTransport[F]:
      def send(to: NodeId, msg: RaftMessage): F[Unit] =
        val env = GroupEnvelope(groupId, localId, msg, Some(to))
        if shouldCoalesce(msg) then enqueueHeartbeat(to, env)
        else
          val evict =
            if supersedesHeartbeat(msg) then evictSuperseded(to, groupId)
            else F.unit
          evict *> sendBatchEnvelopes(Vector(env))

      def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit] =
        if msgs.isEmpty then F.unit
        else
          val evict =
            if msgs.exists(supersedesHeartbeat) then
              evictSuperseded(to, groupId)
            else F.unit
          val envs =
            msgs.iterator
              .map(m => GroupEnvelope(groupId, localId, m, Some(to)))
              .toVector
          evict *> sendBatchEnvelopes(envs)

      def broadcast(msg: RaftMessage): F[Unit] =
        sendBatchEnvelopes(Vector(GroupEnvelope(groupId, localId, msg, None)))

      def receive: Stream[F, (NodeId, RaftMessage)] =
        Stream
          .eval(
            closedGroupsRef.update(_ - groupId) *> activateGroupQueue(groupId)
          )
          .flatMap(q => Stream.fromQueueUnterminated(q))

      def localAddress: F[String] =
        F.pure(localId.value)

      def shutdown: F[Unit] =
        unregisterGroup(groupId)

  /** Route a single incoming [[GroupEnvelope]] to its target group mailbox in
    * `O(1)` while protecting against unbounded unknown-group allocation.
    */
  def dispatch(envelope: GroupEnvelope): F[Unit] =
    if !envelope.to.forall(_ == localId) then F.unit
    else
      closedGroupsRef.get.flatMap { closed =>
        if closed.contains(envelope.groupId) then F.unit
        else
          registeredQueuesRef.get.flatMap { reg =>
            reg.get(envelope.groupId) match
              case Some(q) =>
                q.tryOffer((envelope.from, envelope.message)).void
              case None =>
                strictRegistrationRef.get.flatMap { isStrict =>
                  if isStrict then recordDroppedUnknownGroup
                  else offerToBoundedStaging(envelope)
                }
          }
      }

  /** Route a decoded batch of [[GroupEnvelope]]s to their target group
    * mailboxes.
    */
  def dispatchBatch(envelopes: Seq[GroupEnvelope]): F[Unit] =
    envelopes.toList.traverse_(dispatch)

  private def offerToBoundedStaging(envelope: GroupEnvelope): F[Unit] =
    stagingQueuesRef.get.flatMap { staging =>
      staging.get(envelope.groupId) match
        case Some(sq) =>
          sq.tryOffer((envelope.from, envelope.message)).void
        case None if staging.size >= maxUnregisteredGroups =>
          recordDroppedUnknownGroup
        case None =>
          val cap = math.max(1, math.min(maxQueueSize, 64))
          Queue.bounded[F, (NodeId, RaftMessage)](cap).flatMap { freshQ =>
            stagingQueuesRef
              .modify { latest =>
                latest.get(envelope.groupId) match
                  case Some(won) => (latest, Some(won))
                  case None if latest.size >= maxUnregisteredGroups =>
                    (latest, None)
                  case None =>
                    (latest.updated(envelope.groupId, freshQ), Some(freshQ))
              }
              .flatMap {
                case Some(q) =>
                  q.tryOffer((envelope.from, envelope.message)).void
                case None => recordDroppedUnknownGroup
              }
          }
    }

  private def recordDroppedUnknownGroup: F[Unit] =
    statsRef.update(s =>
      s.copy(droppedUnknownGroupEnvelopes = s.droppedUnknownGroupEnvelopes + 1L)
    )

  private def activateGroupQueue(
      groupId: GroupId
  ): F[Queue[F, (NodeId, RaftMessage)]] =
    for
      q <- registeredQueuesRef.get.flatMap { current =>
        current.get(groupId) match
          case Some(existing) => F.pure(existing)
          case None           =>
            Queue.bounded[F, (NodeId, RaftMessage)](maxQueueSize).flatMap {
              fresh =>
                registeredQueuesRef.modify { latest =>
                  latest.get(groupId) match
                    case Some(won) => (latest, won)
                    case None      => (latest.updated(groupId, fresh), fresh)
                }
            }
      }
      stagedOpt <- stagingQueuesRef.modify(m => (m - groupId, m.get(groupId)))
      _ <- stagedOpt.traverse_ { sq =>
        sq.tryTakeN(None).flatMap(_.traverse_(item => q.tryOffer(item).void))
      }
    yield q

  private def isAppendResponse(msg: RaftMessage): Boolean =
    msg match
      case _: AppendEntriesResponse => true
      case _                        => false

  private def isAppendRequest(msg: RaftMessage): Boolean =
    msg match
      case _: AppendEntriesRequest => true
      case _                       => false

  private def shouldCoalesce(msg: RaftMessage): Boolean =
    coalescing.enabled && (msg match
      case ae: AppendEntriesRequest if ae.entries.isEmpty => true
      case _: AppendEntriesResponse => coalescing.coalesceResponses
      case _                        => false)

  private def supersedesHeartbeat(msg: RaftMessage): Boolean =
    msg match
      case ae: AppendEntriesRequest if ae.entries.nonEmpty => true
      case _: InstallSnapshotRequest                       => true
      case _                                               => false

  private def evictSuperseded(dest: NodeId, groupId: GroupId): F[Unit] =
    if !coalescing.enabled then F.unit
    else
      pendingHeartbeatsRef
        .modify { byPeer =>
          byPeer.get(dest) match
            case None      => (byPeer, 0L)
            case Some(vec) =>
              val (dropped, kept) = vec.partition(e =>
                e.groupId == groupId && isAppendRequest(e.message)
              )
              val nextMap =
                if kept.isEmpty then byPeer - dest
                else byPeer.updated(dest, kept)
              (nextMap, dropped.size.toLong)
        }
        .flatMap { removedCount =>
          if removedCount > 0L then
            statsRef.update(s =>
              s.copy(envelopesSuperseded = s.envelopesSuperseded + removedCount)
            )
          else F.unit
        }

  private def enqueueHeartbeat(dest: NodeId, env: GroupEnvelope): F[Unit] =
    val isResp = isAppendResponse(env.message)
    for
      _ <- statsRef.update(s =>
        s.copy(heartbeatsQueued = s.heartbeatsQueued + 1L)
      )
      readySlice <- pendingHeartbeatsRef.modify { byPeer =>
        val existing = byPeer.getOrElse(dest, Vector.empty)
        val pos = existing.indexWhere(e =>
          e.groupId == env.groupId && isAppendResponse(e.message) == isResp
        )
        val updatedVec =
          if pos >= 0 then existing.updated(pos, env)
          else existing :+ env

        val matchingKindCount =
          updatedVec.count(e => isAppendResponse(e.message) == isResp)
        if matchingKindCount >= coalescing.maxBatchSize then
          val (toFlush, remaining) =
            updatedVec.partition(e => isAppendResponse(e.message) == isResp)
          val nextMap =
            if remaining.isEmpty then byPeer - dest
            else byPeer.updated(dest, remaining)
          (nextMap, toFlush)
        else (byPeer.updated(dest, updatedVec), Vector.empty)
      }
      _ <-
        if readySlice.nonEmpty then
          statsRef.update(s =>
            s.copy(
              batchesFlushed = s.batchesFlushed + 1L,
              envelopesCoalesced = s.envelopesCoalesced + readySlice.size.toLong
            )
          ) *> sendBatchEnvelopes(readySlice)
        else F.unit
    yield ()

  private def runCoalescerLoop: F[Unit] =
    Stream
      .fixedRate[F](coalescing.flushInterval)
      .evalMap(_ => flushCoalescedHeartbeats)
      .interruptWhen(shutdownRef)
      .compile
      .drain

  private def cancelCoalescer: F[Unit] =
    coalescerFiberRef.getAndSet(None).flatMap {
      case Some(f) => f.cancel
      case None    => F.unit
    }

object MultiGroupTransport:
  val DefaultMaxUnregisteredGroups: Int = 64

  def apply[F[_]: Async](
      localId: NodeId,
      sendEnvelope: GroupEnvelope => F[Unit],
      maxQueueSize: Int = 1024
  ): F[MultiGroupTransport[F]] =
    withBatchSender(
      localId = localId,
      sendBatchEnvelopes = envs => envs.toList.traverse_(sendEnvelope),
      maxQueueSize = maxQueueSize,
      coalescing = CoalescedHeartbeatConfig()
    )

  def withBatchSender[F[_]: Async](
      localId: NodeId,
      sendBatchEnvelopes: Vector[GroupEnvelope] => F[Unit],
      maxQueueSize: Int = 1024,
      coalescing: CoalescedHeartbeatConfig = CoalescedHeartbeatConfig(),
      strictRegistration: Boolean = false,
      maxUnregisteredGroups: Int = DefaultMaxUnregisteredGroups
  ): F[MultiGroupTransport[F]] =
    require(maxQueueSize > 0, s"maxQueueSize must be > 0, got $maxQueueSize")
    require(
      maxUnregisteredGroups >= 0,
      s"maxUnregisteredGroups must be >= 0, got $maxUnregisteredGroups"
    )
    for
      registered <- Ref.of[F, Map[GroupId, Queue[F, (NodeId, RaftMessage)]]](
        Map.empty
      )
      staging <- Ref.of[F, Map[GroupId, Queue[F, (NodeId, RaftMessage)]]](
        Map.empty
      )
      closed <- Ref.of[F, Set[GroupId]](Set.empty)
      strictRef <- Ref.of[F, Boolean](strictRegistration)
      pending <- Ref.of[F, Map[NodeId, Vector[GroupEnvelope]]](Map.empty)
      stats <- Ref.of[F, CoalescerStats](CoalescerStats())
      shutdownRef <- SignallingRef.of[F, Boolean](false)
      fiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
    yield new MultiGroupTransport[F](
      localId = localId,
      registeredQueuesRef = registered,
      stagingQueuesRef = staging,
      closedGroupsRef = closed,
      strictRegistrationRef = strictRef,
      sendBatchEnvelopes = sendBatchEnvelopes,
      maxQueueSize = maxQueueSize,
      maxUnregisteredGroups = maxUnregisteredGroups,
      coalescing = coalescing,
      pendingHeartbeatsRef = pending,
      statsRef = stats,
      shutdownRef = shutdownRef,
      coalescerFiberRef = fiberRef
    )

  def resource[F[_]: Async](
      localId: NodeId,
      sendBatchEnvelopes: Vector[GroupEnvelope] => F[Unit],
      maxQueueSize: Int = 1024,
      coalescing: CoalescedHeartbeatConfig = CoalescedHeartbeatConfig(),
      strictRegistration: Boolean = false,
      maxUnregisteredGroups: Int = DefaultMaxUnregisteredGroups
  ): Resource[F, MultiGroupTransport[F]] =
    Resource.make(
      withBatchSender(
        localId = localId,
        sendBatchEnvelopes = sendBatchEnvelopes,
        maxQueueSize = maxQueueSize,
        coalescing = coalescing,
        strictRegistration = strictRegistration,
        maxUnregisteredGroups = maxUnregisteredGroups
      ).flatTap(_.startCoalescer)
    )(_.shutdown)

  def createInMemoryCluster[F[_]: Async](
      nodeIds: List[NodeId],
      maxQueueSize: Int = 1024,
      coalescing: CoalescedHeartbeatConfig = CoalescedHeartbeatConfig(),
      onWireBatch: Option[(NodeId, Vector[GroupEnvelope]) => F[Unit]] = None
  ): Resource[F, Map[NodeId, MultiGroupTransport[F]]] =
    Resource
      .eval(
        Ref.of[F, Map[NodeId, MultiGroupTransport[F]]](Map.empty)
      )
      .flatMap { registryRef =>
        nodeIds
          .traverse { localId =>
            val sendBatch: Vector[GroupEnvelope] => F[Unit] = envs =>
              if envs.isEmpty then Async[F].unit
              else
                val hook = onWireBatch
                  .map(fn => fn(localId, envs))
                  .getOrElse(Async[F].unit)
                hook *> registryRef.get.flatMap { reg =>
                  val (unicast, bcast) = envs.partition(_.to.isDefined)
                  val byDest = unicast.groupBy(_.to.get).toList
                  val deliverUnicast = byDest.parTraverse_ {
                    case (dest, slice) =>
                      reg.get(dest).traverse_(_.dispatchBatch(slice))
                  }
                  val deliverBcast =
                    if bcast.isEmpty then Async[F].unit
                    else
                      reg
                        .removed(localId)
                        .values
                        .toList
                        .parTraverse_(_.dispatchBatch(bcast))
                  deliverUnicast *> deliverBcast
                }
            resource[F](localId, sendBatch, maxQueueSize, coalescing)
              .map(localId -> _)
          }
          .evalTap(pairs => registryRef.set(pairs.toMap))
          .map(_.toMap)
      }
