package raft.storage

import raft.state.{Log, LogIndex, Term}
import raft.spi.LogStore

/** Adapter that bridges [[StorageEngine]] to the existing [[LogStore]] SPI.
  *
  * This allows the Raft runtime ([[raft.RaftNode]]) to consume any
  * `StorageEngine` implementation without changes to its constructor or wiring.
  *
  * @param engine
  *   the underlying storage engine
  * @tparam F
  *   the effect type
  */
class StorageBackedLogStore[F[_]](engine: StorageEngine[F]) extends LogStore[F]:

  def get(index: LogIndex): F[Option[Log]] =
    engine.readEntry(index)

  def getRange(start: LogIndex, end: LogIndex): F[Vector[Log]] =
    engine.readRange(start, end)

  def lastIndex: F[LogIndex] =
    engine.lastLogIndex

  def termAt(index: LogIndex): F[Option[Term]] =
    engine.termAt(index)

  def isEmpty: F[Boolean] =
    engine.logIsEmpty

  def append(entries: Seq[Log]): F[Unit] =
    engine.appendEntries(entries)

  def truncateFrom(index: LogIndex): F[Unit] =
    engine.truncateFrom(index)

  def clear: F[Unit] =
    engine.clearLog
