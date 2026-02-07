package raft.spi

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import raft.state.{Log, LogIndex, Term}

/** Read-only view of the RAFT log.
  *
  * Separated from write operations to support:
  *   - Read replicas and follower-only nodes
  *   - Testing with mock readers
  *   - Fine-grained access control
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[LogWriter]] for write operations
  * @see
  *   [[LogStore]] for the combined read/write interface
  */
trait LogReader[F[_]]:
  /** Retrieve the log entry at a specific index.
    *
    * @param index
    *   the 1-based log position to look up
    * @return
    *   `Some(log)` if an entry exists at the index, `None` otherwise
    */
  def get(index: LogIndex): F[Option[Log]]

  /** Retrieve a contiguous range of log entries (inclusive on both ends).
    *
    * @param start
    *   the first index in the range
    * @param end
    *   the last index in the range (inclusive)
    * @return
    *   a vector of log entries within `[start, end]`, in index order
    */
  def getRange(start: LogIndex, end: LogIndex): F[Vector[Log]]

  /** Get the index of the last entry in the log.
    *
    * @return
    *   the last log index, or [[LogIndex.Zero]] if the log is empty
    */
  def lastIndex: F[LogIndex]

  /** Look up the term of the entry at a specific index.
    *
    * @param index
    *   the log position to query
    * @return
    *   `Some(term)` if an entry exists at the index, `None` otherwise
    */
  def termAt(index: LogIndex): F[Option[Term]]

  /** Check whether the log contains no entries.
    *
    * @return
    *   `true` if the log is empty
    */
  def isEmpty: F[Boolean]

/** Write operations for the RAFT log.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[LogReader]] for read operations
  * @see
  *   [[LogStore]] for the combined read/write interface
  */
trait LogWriter[F[_]]:
  /** Append entries to the end of the log.
    *
    * Entries must be appended atomically and in the order provided.
    *
    * @param entries
    *   the log entries to append
    */
  def append(entries: Seq[Log]): F[Unit]

  /** Truncate the log from the given index onwards (inclusive).
    *
    * All entries at or after `index` are removed. This is used during log
    * conflict resolution when a follower's log diverges from the leader's.
    *
    * @param index
    *   the first index to remove (inclusive)
    */
  def truncateFrom(index: LogIndex): F[Unit]

  /** Remove all entries from the log.
    *
    * Typically used during snapshot restoration.
    */
  def clear: F[Unit]

/** Combined log store providing both read and write capabilities.
  *
  * This is the primary interface that [[raft.RaftNode]] depends on for log
  * persistence. Implementations must ensure durability — entries must survive
  * process restarts.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  * @see
  *   [[raft.impl.InMemLogStore]] for an in-memory test implementation
  */
trait LogStore[F[_]] extends LogReader[F] with LogWriter[F]

/** Factory for creating [[LogStore]] instances with proper resource lifecycle
  * management.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  */
trait LogStoreFactory[F[_]]:
  /** Create a new log store as a managed resource.
    *
    * The resource will be properly finalized when released.
    *
    * @param path
    *   the storage path for the log (e.g., a directory on disk)
    * @return
    *   a managed resource wrapping the created log store
    */
  def create(path: String): Resource[F, LogStore[F]]

/** Companion for [[LogStoreFactory]] providing built-in factory
  * implementations.
  */
object LogStoreFactory:
  /** Create an in-memory [[LogStoreFactory]] (useful for testing).
    *
    * @tparam F
    *   the effect type (requires `Async`)
    * @return
    *   a factory that produces [[raft.impl.InMemLogStore]] instances
    */
  def inMemory[F[_]: Async]: LogStoreFactory[F] = new LogStoreFactory[F]:
    def create(path: String): Resource[F, LogStore[F]] =
      Resource.eval(
        raft.impl.InMemLogStore[F]().map(s => s: LogStore[F])
      )
