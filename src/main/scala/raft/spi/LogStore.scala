package raft.spi

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import raft.state.{Log, LogIndex, Term}

/**
 * Read-only log operations.
 * 
 * Separated from write operations to support:
 * - Read replicas
 * - Testing with mock readers
 * - Follower-only nodes
 */
trait LogReader[F[_]]:
  /** Get log entry at specific index */
  def get(index: LogIndex): F[Option[Log]]
  
  /** Get range of entries [start, end] inclusive */
  def getRange(start: LogIndex, end: LogIndex): F[Vector[Log]]
  
  /** Get the last log index (0 if empty) */
  def lastIndex: F[LogIndex]
  
  /** Get term at specific index */
  def termAt(index: LogIndex): F[Option[Term]]
  
  /** Check if log is empty */
  def isEmpty: F[Boolean]

/**
 * Write operations for log.
 */
trait LogWriter[F[_]]:
  /** Append entries to the log */
  def append(entries: Seq[Log]): F[Unit]
  
  /** Truncate log from index (inclusive) onwards */
  def truncateFrom(index: LogIndex): F[Unit]
  
  /** Clear all entries */
  def clear: F[Unit]

/**
 * Combined log store with read and write capabilities.
 * 
 * Standard implementation should extend both traits.
 */
trait LogStore[F[_]] extends LogReader[F] with LogWriter[F]

/**
 * Factory for creating LogStore instances with proper resource management.
 */
trait LogStoreFactory[F[_]]:
  /** Create a new log store as a managed resource */
  def create(path: String): Resource[F, LogStore[F]]

object LogStoreFactory:
  /** In-memory factory (useful for testing) */
  def inMemory[F[_]: Async]: LogStoreFactory[F] = new LogStoreFactory[F]:
    def create(path: String): Resource[F, LogStore[F]] =
      Resource.eval(
        raft.impl.InMemLogStore[F]().map(s => s: LogStore[F])
      )
