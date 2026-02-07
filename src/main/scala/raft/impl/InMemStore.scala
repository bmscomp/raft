package raft.impl

import cats.effect.kernel.{Async, Ref}
import cats.syntax.all.*
import raft.state.{Log, LogIndex, NodeId, Term}
import raft.spi.{LogStore, LogReader, LogWriter, StableStore}

/** In-memory [[LogStore]] implementation for testing and prototyping.
  *
  * Production Raft deployments require durable log storage so that committed
  * entries survive crashes (§5.2). This implementation intentionally sacrifices
  * that durability guarantee by storing entries in a `Ref[F, Vector[Log]]`,
  * making it ideal for:
  *   - '''Unit tests''' — fast, in-process, repeatable tests against the
  *     [[raft.logic.RaftLogic]] layer without disk I/O
  *   - '''Prototyping''' — quickly standing up a multi-node cluster to explore
  *     API design and message flows
  *   - '''Property-based testing''' — generating large numbers of random
  *     scenarios without worrying about cleanup
  *
  * @tparam F
  *   the effect type (requires `Async`)
  * @see
  *   [[raft.spi.LogStore]] for the SPI contract
  * @see
  *   [[raft.spi.LogStoreFactory.inMemory]] for creating instances via the
  *   factory pattern
  */
class InMemLogStore[F[_]: Async] private (
    logs: Ref[F, Vector[Log]]
) extends LogStore[F]:

  def get(index: LogIndex): F[Option[Log]] =
    logs.get.map(_.find(_.index == index.value))

  def getRange(start: LogIndex, end: LogIndex): F[Vector[Log]] =
    logs.get.map(_.filter(l => l.index >= start.value && l.index <= end.value))

  def lastIndex: F[LogIndex] =
    logs.get.map(v =>
      LogIndex.unsafeFrom(v.lastOption.map(_.index).getOrElse(0L))
    )

  def termAt(index: LogIndex): F[Option[Term]] =
    logs.get.map(
      _.find(_.index == index.value).map(l => Term.unsafeFrom(l.term))
    )

  def isEmpty: F[Boolean] =
    logs.get.map(_.isEmpty)

  def append(entries: Seq[Log]): F[Unit] =
    logs.update(_ ++ entries)

  def truncateFrom(index: LogIndex): F[Unit] =
    logs.update(_.filter(_.index < index.value))

  def clear: F[Unit] =
    logs.set(Vector.empty)

  /** Get the index of the first entry in the log (legacy API).
    *
    * @return
    *   the first entry's index, or 0 if the log is empty
    */
  @deprecated("Use lastIndex from LogReader instead", "0.2.0")
  def firstIndex: F[Long] =
    logs.get.map(_.headOption.map(_.index).getOrElse(0L))

  /** Retrieve a log entry by raw index (legacy API).
    *
    * @param index
    *   the raw `Long` index to look up
    * @return
    *   `Some(log)` if found, `None` otherwise
    */
  @deprecated("Use get(LogIndex) from LogReader instead", "0.2.0")
  def getLog(index: Long): F[Option[Log]] =
    get(LogIndex.unsafeFrom(index))

  /** Retrieve a range of log entries by raw indices (legacy API).
    *
    * @param fromIndex
    *   start of range (inclusive)
    * @param toIndex
    *   end of range (inclusive)
    * @return
    *   the matching log entries
    */
  @deprecated(
    "Use getRange(LogIndex, LogIndex) from LogReader instead",
    "0.2.0"
  )
  def getLogs(fromIndex: Long, toIndex: Long): F[Seq[Log]] =
    getRange(LogIndex.unsafeFrom(fromIndex), LogIndex.unsafeFrom(toIndex))
      .map(_.toSeq)

  /** Append entries using the legacy API.
    *
    * @param newLogs
    *   the entries to append
    */
  @deprecated("Use append(Seq[Log]) from LogWriter instead", "0.2.0")
  def appendLogs(newLogs: Seq[Log]): F[Unit] =
    append(newLogs)

/** Companion for [[InMemLogStore]] providing a factory method. */
object InMemLogStore:
  /** Create a new empty [[InMemLogStore]].
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @return
    *   a new in-memory log store, wrapped in `F`
    */
  def apply[F[_]: Async](): F[InMemLogStore[F]] =
    Ref.of[F, Vector[Log]](Vector.empty).map(new InMemLogStore[F](_))

/** In-memory [[StableStore]] implementation for testing and prototyping.
  *
  * Stores the current term and voted-for candidate in `Ref`s with no
  * persistence. Data is lost on restart.
  *
  * @tparam F
  *   the effect type (requires `Async`)
  * @see
  *   [[raft.spi.StableStore]] for the SPI contract
  * @see
  *   [[raft.spi.StableStoreFactory.inMemory]] for creating instances via the
  *   factory pattern
  */
class InMemStableStore[F[_]: Async] private (
    termRef: Ref[F, Term],
    votedForRef: Ref[F, Option[NodeId]]
) extends StableStore[F]:

  def currentTerm: F[Term] = termRef.get
  def setCurrentTerm(term: Term): F[Unit] = termRef.set(term)
  def votedFor: F[Option[NodeId]] = votedForRef.get
  def setVotedFor(nodeId: Option[NodeId]): F[Unit] = votedForRef.set(nodeId)

  /** Get the current term as a raw `Long` (legacy API).
    *
    * @return
    *   the current term value
    */
  @deprecated("Use currentTerm from StableStore instead", "0.2.0")
  def getCurrentTerm: F[Long] = termRef.get.map(_.value)

  /** Get the voted-for candidate (legacy API alias).
    *
    * @return
    *   the voted-for node, if any
    */
  @deprecated("Use votedFor from StableStore instead", "0.2.0")
  def getVotedFor: F[Option[NodeId]] = votedFor

/** Companion for [[InMemStableStore]] providing a factory method. */
object InMemStableStore:
  /** Create a new [[InMemStableStore]] initialized to term 0 with no vote.
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @return
    *   a new in-memory stable store, wrapped in `F`
    */
  def apply[F[_]: Async](): F[InMemStableStore[F]] =
    for
      term <- Ref.of[F, Term](Term.Zero)
      votedFor <- Ref.of[F, Option[NodeId]](None)
    yield new InMemStableStore[F](term, votedFor)
