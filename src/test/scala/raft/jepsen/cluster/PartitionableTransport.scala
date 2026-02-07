package raft.jepsen.cluster

import cats.effect.{Async, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream
import raft.state.NodeId
import raft.message.RaftMessage
import raft.spi.RaftTransport
import raft.impl.InMemTransport
import scala.concurrent.duration.FiniteDuration

/** Partition state for the simulated network. */
case class PartitionState(
    partitions: Set[Set[NodeId]] = Set.empty,
    isolated: Set[NodeId] = Set.empty,
    killed: Set[NodeId] = Set.empty,
    messageDelayMs: Option[Long] = None,
    dropProbability: Double = 0.0
):
  /** Check if a message from `from` to `to` should be delivered. */
  def canDeliver(from: NodeId, to: NodeId): Boolean =
    if killed.contains(from) || killed.contains(to) then false
    else if isolated.contains(from) || isolated.contains(to) then false
    else if partitions.isEmpty then true
    else
      // Both nodes must be in the same partition group
      partitions.exists(group => group.contains(from) && group.contains(to))

/** A transport wrapper that supports network fault injection.
  *
  * Wraps an `InMemTransport` and intercepts all message delivery to apply
  * partitioning, node isolation, kills, and message dropping. This enables
  * Jepsen-style nemesis testing without any real network manipulation.
  */
class PartitionableTransport[F[_]: Async](
    val underlying: InMemTransport[F],
    localId: NodeId,
    stateRef: Ref[F, PartitionState],
    allTransports: Ref[F, Map[NodeId, PartitionableTransport[F]]]
) extends RaftTransport[F]:

  def send(to: NodeId, msg: RaftMessage): F[Unit] =
    stateRef.get.flatMap { state =>
      if state.canDeliver(localId, to) then
        if state.dropProbability > 0 then
          Async[F].delay(math.random()).flatMap { r =>
            if r >= state.dropProbability then underlying.send(to, msg)
            else Async[F].unit
          }
        else underlying.send(to, msg)
      else Async[F].unit
    }

  def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): F[Unit] =
    msgs.toList.traverse_(msg => send(to, msg))

  def broadcast(msg: RaftMessage): F[Unit] =
    allTransports.get.flatMap { transports =>
      transports.keys.toList.traverse_ { peer =>
        if peer != localId then send(peer, msg)
        else Async[F].unit
      }
    }

  def receive: Stream[F, (NodeId, RaftMessage)] =
    Stream.fromQueueUnterminated(underlying.inbox).evalFilter {
      case (from, _) =>
        stateRef.get.map(state => state.canDeliver(from, localId))
    }

  def localAddress: F[String] = Async[F].pure(localId.value)

  def shutdown: F[Unit] = underlying.shutdown

object PartitionableTransport:

  /** Create a fully connected cluster of partitionable transports. */
  def createCluster[F[_]: Async](
      nodeIds: List[NodeId]
  ): F[(Map[NodeId, PartitionableTransport[F]], Ref[F, PartitionState])] =
    for
      state <- Ref.of[F, PartitionState](PartitionState())
      transportsRef <- Ref.of[F, Map[NodeId, PartitionableTransport[F]]](
        Map.empty
      )
      underlyingCluster <- InMemTransport.createCluster[F](nodeIds)
      partitioned = underlyingCluster.map { case (id, transport) =>
        id -> new PartitionableTransport[F](transport, id, state, transportsRef)
      }
      _ <- transportsRef.set(partitioned)
    yield (partitioned, state)
