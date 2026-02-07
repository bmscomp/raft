package raft.storage

import cats.effect.Resource
import raft.state.{Log, LogIndex, NodeId, Snapshot, Term}

/** Persistent hard state that must be fsync'd before responding to any RPC.
  *
  * The Raft paper (§5.2, Figure 2) requires these two values to survive
  * crashes:
  *   - '''currentTerm''' — prevents term regression and double voting
  *   - '''votedFor''' — prevents granting a second vote in the same term
  *
  * @param currentTerm
  *   the latest term this node has seen
  * @param votedFor
  *   the candidate this node voted for in the current term, if any
  */
case class HardState(currentTerm: Term, votedFor: Option[NodeId])

object HardState:
  /** Initial hard state before any election. */
  val empty: HardState = HardState(Term.Zero, None)

/** Pluggable storage engine for all durable Raft state.
  *
  * This trait unifies log storage, hard state persistence, and snapshot
  * management behind a single interface. Implementations can use any backing
  * technology — memory-mapped WAL files, RocksDB, LMDB, or even cloud object
  * storage — and the Raft runtime remains unchanged.
  *
  * The existing [[raft.spi.LogStore]] and [[raft.spi.StableStore]] interfaces
  * are preserved as thin adapters over this engine (see
  * [[StorageBackedLogStore]] and [[StorageBackedStableStore]]).
  *
  * '''Durability contract''': `appendEntries` and `writeHardState` must ensure
  * data is durable (fsync'd) before their `F` completes. A crash immediately
  * after return must not lose the written data.
  *
  * @tparam F
  *   the effect type (e.g., `IO`)
  */
trait StorageEngine[F[_]]:

  /** Append log entries atomically and durably.
    *
    * All entries must be persisted in order. The operation must fsync before
    * returning.
    *
    * @param entries
    *   the log entries to append
    */
  def appendEntries(entries: Seq[Log]): F[Unit]

  /** Read a single log entry by index.
    *
    * @param index
    *   the 1-based log position
    * @return
    *   `Some(log)` if present, `None` otherwise
    */
  def readEntry(index: LogIndex): F[Option[Log]]

  /** Read a contiguous range of log entries (inclusive).
    *
    * @param start
    *   the first index in the range
    * @param end
    *   the last index in the range (inclusive)
    * @return
    *   entries within `[start, end]`, in index order
    */
  def readRange(start: LogIndex, end: LogIndex): F[Vector[Log]]

  /** Get the index of the last log entry.
    *
    * @return
    *   the last log index, or `LogIndex.Zero` if the log is empty
    */
  def lastLogIndex: F[LogIndex]

  /** Look up the term of the entry at a specific index.
    *
    * @param index
    *   the log position to query
    * @return
    *   `Some(term)` if an entry exists, `None` otherwise
    */
  def termAt(index: LogIndex): F[Option[Term]]

  /** Check whether the log contains no entries. */
  def logIsEmpty: F[Boolean]

  /** Truncate the log from the given index onwards (inclusive).
    *
    * All entries at or after `index` are removed. Used during log conflict
    * resolution.
    *
    * @param index
    *   the first index to remove (inclusive)
    */
  def truncateFrom(index: LogIndex): F[Unit]

  /** Remove all entries before the given index (exclusive).
    *
    * Used for log compaction/garbage collection. Implementations should delete
    * or archive old segments that beneficial to reclaim space. The index
    * provided is typically the `lastIncludedIndex` of a newly installed
    * snapshot.
    *
    * @param index
    *   the index up to which logs can be discarded (entries with index <
    *   `index` are removed)
    */
  def discardBefore(index: LogIndex): F[Unit]

  /** Remove all entries from the log (e.g., during snapshot restore). */
  def clearLog: F[Unit]

  /** Read the persisted hard state (term + votedFor).
    *
    * @return
    *   the current hard state, or [[HardState.empty]] if never written
    */
  def readHardState: F[HardState]

  /** Persist the hard state durably. Must fsync before returning.
    *
    * @param state
    *   the hard state to persist
    */
  def writeHardState(state: HardState): F[Unit]

  /** Save a snapshot to durable storage.
    *
    * @param snapshot
    *   the snapshot to persist
    */
  def saveSnapshot(snapshot: Snapshot[F]): F[Unit]

  /** Load the most recent snapshot, if any.
    *
    * @return
    *   `Some(snapshot)` if a snapshot exists, `None` otherwise
    */
  def loadSnapshot: F[Option[Snapshot[F]]]

/** Factory for creating [[StorageEngine]] instances with resource lifecycle.
  *
  * @tparam F
  *   the effect type
  */
trait StorageEngineFactory[F[_]]:
  /** Open a storage engine at the given directory.
    *
    * The returned resource handles initialization and cleanup (e.g., unmapping
    * memory segments, closing file handles).
    *
    * @param dataDir
    *   the root directory for all persistent data
    * @return
    *   a managed resource wrapping the storage engine
    */
  def open(dataDir: String): Resource[F, StorageEngine[F]]
