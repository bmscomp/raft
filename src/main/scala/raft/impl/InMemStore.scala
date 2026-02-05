package raft.impl

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*
import raft.state.{Log, LogIndex, NodeId, Term}
import raft.spi.{LogStore, LogReader, LogWriter, StableStore}

/**
 * In-memory implementation of LogStore for testing.
 * Implements the enhanced Reader/Writer interface.
 */
class InMemLogStore[F[_]: Async] private (
  logs: Ref[F, Vector[Log]]
) extends LogStore[F]:
  
  // === LogReader ===
  
  def get(index: LogIndex): F[Option[Log]] =
    logs.get.map(_.find(_.index == index.value))
  
  def getRange(start: LogIndex, end: LogIndex): F[Vector[Log]] =
    logs.get.map(_.filter(l => l.index >= start.value && l.index <= end.value))
  
  def lastIndex: F[LogIndex] =
    logs.get.map(v => LogIndex.unsafeFrom(v.lastOption.map(_.index).getOrElse(0L)))
  
  def termAt(index: LogIndex): F[Option[Term]] =
    logs.get.map(_.find(_.index == index.value).map(l => Term.unsafeFrom(l.term)))
  
  def isEmpty: F[Boolean] =
    logs.get.map(_.isEmpty)
  
  // === LogWriter ===
  
  def append(entries: Seq[Log]): F[Unit] =
    logs.update(_ ++ entries)
  
  def truncateFrom(index: LogIndex): F[Unit] =
    logs.update(_.filter(_.index < index.value))
  
  def clear: F[Unit] =
    logs.set(Vector.empty)
  
  // === Legacy compatibility methods ===
  
  def firstIndex: F[Long] =
    logs.get.map(_.headOption.map(_.index).getOrElse(0L))
  
  def getLog(index: Long): F[Option[Log]] =
    get(LogIndex.unsafeFrom(index))
  
  def getLogs(fromIndex: Long, toIndex: Long): F[Seq[Log]] =
    getRange(LogIndex.unsafeFrom(fromIndex), LogIndex.unsafeFrom(toIndex)).asInstanceOf[F[Seq[Log]]]
  
  def appendLogs(newLogs: Seq[Log]): F[Unit] =
    append(newLogs)

object InMemLogStore:
  def apply[F[_]: Async](): F[InMemLogStore[F]] =
    Ref.of[F, Vector[Log]](Vector.empty).map(new InMemLogStore[F](_))

/**
 * In-memory implementation of StableStore for testing.
 * Uses opaque Term type.
 */
class InMemStableStore[F[_]: Async] private (
  termRef: Ref[F, Term],
  votedForRef: Ref[F, Option[NodeId]]
) extends StableStore[F]:
  
  def currentTerm: F[Term] = termRef.get
  def setCurrentTerm(term: Term): F[Unit] = termRef.set(term)
  def votedFor: F[Option[NodeId]] = votedForRef.get
  def setVotedFor(nodeId: Option[NodeId]): F[Unit] = votedForRef.set(nodeId)
  
  // Legacy compatibility
  def getCurrentTerm: F[Long] = termRef.get.map(_.value)
  def getVotedFor: F[Option[NodeId]] = votedFor

object InMemStableStore:
  def apply[F[_]: Async](): F[InMemStableStore[F]] =
    for
      term     <- Ref.of[F, Term](Term.Zero)
      votedFor <- Ref.of[F, Option[NodeId]](None)
    yield new InMemStableStore[F](term, votedFor)
