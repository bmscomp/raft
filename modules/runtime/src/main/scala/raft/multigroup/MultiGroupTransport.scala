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

/** Group-aware transport that multiplexes messages for multiple Raft groups
  * over a shared network connection with `O(1)` per-group bounded mailbox
  * demultiplexing and optional per-host **Coalesced Heartbeats** (CockroachDB /
  * TiKV pattern).
  *
  *   - **Inbound (`O(1)` Bounded Mailboxes)**: Routes each incoming
  *     [[GroupEnvelope]] directly to its target group's bounded [[Queue]],
  *     buffering startup traffic without `O(G)` broadcast fan-out.
  *   - **Outbound Batching (`sendBatchEnvelopes`)**: Sends pipelined
  *     `AppendEntriesRequest` bursts (`RaftTransport.sendBatch`) in a single
  *     multiplexed wire frame.
  *   - **Per-Host Coalesced Heartbeats**: When [[CoalescedHeartbeatConfig]] is
  *     enabled, unicast empty `AppendEntriesRequest` heartbeats (and optionally
  *     `AppendEntriesResponse` ACKs) across all hosted groups targeting the
  *     same peer node are deduplicated per `(peer, groupId)` and flushed
  *     together every `flushInterval` or upon reaching `maxBatchSize`, while
  *     data-carrying replication and elections bypass the coalescer with zero
  *     delay (superseding any stale queued heartbeat for that group).
  */
class MultiGroupTransport[F[_]: Async] private (
    val localId: NodeId,
    queuesRef: Ref[F, Map[GroupId, Queue[F, (NodeId, RaftMessage)]]],
    closedGroupsRef: Ref[F, Set[GroupId]],
    sendBatchEnvelopes: Vector[GroupEnvelope] => F[Unit],
    maxQueueSize: Int,
    val coalescing: CoalescedHeartbeatConfig,
    pendingHeartbeatsRef: Ref[F, Map[NodeId, Vector[GroupEnvelope]]],
    statsRef: Ref[F, CoalescerStats],
    shutdownRef: SignallingRef[F, Boolean],
    coalescerFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]]
):

  /** Start the background heartbeat coalescer fiber (idempotent; no-op when
    * `coalescing.enabled == false`).
    */
  def startCoalescer: F[Unit] =
    if !coalescing.enabled then Async[F].unit
    else
      cancelCoalescer *>
        shutdownRef.set(false) *>
        runCoalescerLoop.start.flatMap(f => coalescerFiberRef.set(Some(f)))

  /** Stop the background coalescer fiber and flush any remaining queued
    * heartbeats.
    */
  def shutdown: F[Unit] =
    shutdownRef.set(true) *>
      cancelCoalescer *>
      flushCoalescedHeartbeats.attempt.void

  /** Inspect live heartbeat coalescing counters. */
  def coalescerStats: F[CoalescerStats] = statsRef.get

  /** Immediately flush all buffered per-peer heartbeats as coalesced batches.
    */
  def flushCoalescedHeartbeats: F[Unit] =
    pendingHeartbeatsRef.getAndSet(Map.empty).flatMap { pendingByPeer =>
      val slices: List[Vector[GroupEnvelope]] =
        pendingByPeer.values.toList.flatMap { envs =>
          val (resps, reqs) =
            envs.partition(_.message.isInstanceOf[AppendEntriesResponse])
          List(reqs, resps).filter(_.nonEmpty)
        }
      if slices.isEmpty then Async[F].unit
      else
        val totalCoalesced = slices.map(_.size.toLong).sum
        statsRef.update(s =>
          s.copy(
            batchesFlushed = s.batchesFlushed + slices.size.toLong,
            envelopesCoalesced = s.envelopesCoalesced + totalCoalesced
          )
        ) *>
          slices.parTraverse_(slice => sendBatchEnvelopes(slice))
    }

  /** Eagerly allocate (or re-activate) the bounded mailbox for `groupId` and
    * return its per-group [[RaftTransport]] view.
    */
  def registerGroup(groupId: GroupId): F[RaftTransport[F]] =
    closedGroupsRef.update(_ - groupId) *>
      getOrCreateQueue(groupId).as(transportForGroup(groupId))

  /** Remove a group's mailbox and purge any buffered heartbeats for `groupId`.
    */
  def unregisterGroup(groupId: GroupId): F[Unit] =
    closedGroupsRef.update(_ + groupId) *>
      queuesRef.update(_ - groupId) *>
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
            else Async[F].unit
          evict *> sendBatchEnvelopes(Vector(env))

      def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit] =
        if msgs.isEmpty then Async[F].unit
        else
          val evict =
            if msgs.exists(supersedesHeartbeat) then
              evictSuperseded(to, groupId)
            else Async[F].unit
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
            closedGroupsRef.update(_ - groupId) *> getOrCreateQueue(groupId)
          )
          .flatMap(q => Stream.fromQueueUnterminated(q))

      def localAddress: F[String] =
        Async[F].pure(localId.value)

      def shutdown: F[Unit] =
        unregisterGroup(groupId)

  /** Route a single incoming [[GroupEnvelope]] to its target group mailbox in
    * `O(1)`.
    */
  def dispatch(envelope: GroupEnvelope): F[Unit] =
    if !envelope.to.forall(_ == localId) then Async[F].unit
    else
      closedGroupsRef.get.flatMap { closed =>
        if closed.contains(envelope.groupId) then Async[F].unit
        else
          getOrCreateQueue(envelope.groupId).flatMap { q =>
            q.tryOffer((envelope.from, envelope.message)).void
          }
      }

  /** Route a decoded batch of [[GroupEnvelope]]s (e.g., from a
    * `MultiGroupBatchProto` or `CoalescedHeartbeatBatchProto` frame) to their
    * target group mailboxes.
    */
  def dispatchBatch(envelopes: Seq[GroupEnvelope]): F[Unit] =
    envelopes.toList.traverse_(dispatch)

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
    if !coalescing.enabled then Async[F].unit
    else
      pendingHeartbeatsRef
        .modify { byPeer =>
          byPeer.get(dest) match
            case None      => (byPeer, 0L)
            case Some(vec) =>
              val (dropped, kept) = vec.partition(e =>
                e.groupId == groupId && e.message
                  .isInstanceOf[AppendEntriesRequest]
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
          else Async[F].unit
        }

  private def enqueueHeartbeat(dest: NodeId, env: GroupEnvelope): F[Unit] =
    val isResp = env.message.isInstanceOf[AppendEntriesResponse]
    for
      _ <- statsRef.update(s =>
        s.copy(heartbeatsQueued = s.heartbeatsQueued + 1L)
      )
      readySlice <- pendingHeartbeatsRef.modify { byPeer =>
        val existing = byPeer.getOrElse(dest, Vector.empty)
        val pos = existing.indexWhere(e =>
          e.groupId == env.groupId &&
            e.message.isInstanceOf[AppendEntriesResponse] == isResp
        )
        val updatedVec =
          if pos >= 0 then existing.updated(pos, env)
          else existing :+ env

        val matchingKindCount =
          updatedVec.count(
            _.message.isInstanceOf[AppendEntriesResponse] == isResp
          )
        if matchingKindCount >= coalescing.maxBatchSize then
          val (toFlush, remaining) = updatedVec.partition(
            _.message.isInstanceOf[AppendEntriesResponse] == isResp
          )
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
        else Async[F].unit
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
      case None    => Async[F].unit
    }

  private def getOrCreateQueue(
      groupId: GroupId
  ): F[Queue[F, (NodeId, RaftMessage)]] =
    queuesRef.get.flatMap { current =>
      current.get(groupId) match
        case Some(existing) => Async[F].pure(existing)
        case None           =>
          Queue.bounded[F, (NodeId, RaftMessage)](maxQueueSize).flatMap { q =>
            queuesRef.modify { latest =>
              latest.get(groupId) match
                case Some(won) => (latest, won)
                case None      => (latest.updated(groupId, q), q)
            }
          }
    }

/** Companion for [[MultiGroupTransport]] providing single-envelope,
  * batch-envelope, `Resource`-managed, and in-memory cluster factories.
  */
object MultiGroupTransport:

  /** Create a [[MultiGroupTransport]] wrapping a single-envelope sender
    * callback.
    */
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

  /** Create a [[MultiGroupTransport]] backed by a batch wire sender and
    * configurable [[CoalescedHeartbeatConfig]] (call `startCoalescer` or prefer
    * [[resource]] when `coalescing.enabled == true`).
    */
  def withBatchSender[F[_]: Async](
      localId: NodeId,
      sendBatchEnvelopes: Vector[GroupEnvelope] => F[Unit],
      maxQueueSize: Int = 1024,
      coalescing: CoalescedHeartbeatConfig = CoalescedHeartbeatConfig()
  ): F[MultiGroupTransport[F]] =
    require(maxQueueSize > 0, s"maxQueueSize must be > 0, got $maxQueueSize")
    for
      queues <- Ref.of[F, Map[GroupId, Queue[F, (NodeId, RaftMessage)]]](
        Map.empty
      )
      closed <- Ref.of[F, Set[GroupId]](Set.empty)
      pending <- Ref.of[F, Map[NodeId, Vector[GroupEnvelope]]](Map.empty)
      stats <- Ref.of[F, CoalescerStats](CoalescerStats())
      shutdownRef <- SignallingRef.of[F, Boolean](false)
      fiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
    yield new MultiGroupTransport[F](
      localId = localId,
      queuesRef = queues,
      closedGroupsRef = closed,
      sendBatchEnvelopes = sendBatchEnvelopes,
      maxQueueSize = maxQueueSize,
      coalescing = coalescing,
      pendingHeartbeatsRef = pending,
      statsRef = stats,
      shutdownRef = shutdownRef,
      coalescerFiberRef = fiberRef
    )

  /** Managed [[Resource]] creating a [[MultiGroupTransport]], starting its
    * background heartbeat coalescer (when `coalescing.enabled == true`), and
    * shutting it down cleanly on release.
    */
  def resource[F[_]: Async](
      localId: NodeId,
      sendBatchEnvelopes: Vector[GroupEnvelope] => F[Unit],
      maxQueueSize: Int = 1024,
      coalescing: CoalescedHeartbeatConfig = CoalescedHeartbeatConfig()
  ): Resource[F, MultiGroupTransport[F]] =
    Resource.make(
      withBatchSender(
        localId,
        sendBatchEnvelopes,
        maxQueueSize,
        coalescing
      ).flatTap(_.startCoalescer)
    )(_.shutdown)

  /** Create a fully connected in-memory cluster of [[MultiGroupTransport]]s
    * sharing one logical batch wire link per `(src, dst)` physical host pair.
    */
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
