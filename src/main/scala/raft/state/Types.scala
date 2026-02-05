package raft.state

import scala.util.control.NoStackTrace

/**
 * Type-safe Term representation using Scala 3 opaque types.
 * 
 * Terms in RAFT must always be non-negative and increasing.
 * This opaque type provides compile-time safety.
 */
opaque type Term = Long

object Term:
  val Zero: Term = 0L
  
  /** Safe constructor that validates the term value */
  def apply(value: Long): Either[InvalidTerm, Term] =
    if value >= 0 then Right(value) else Left(InvalidTerm(value))
  
  /** Unsafe constructor - use only when value is known to be valid */
  def unsafeFrom(value: Long): Term = value
  
  /** Ordering instance for Term */
  given Ordering[Term] = Ordering.Long
  
  extension (t: Term)
    def value: Long = t
    def increment: Term = t + 1
    def >(other: Term): Boolean = t > other
    def >=(other: Term): Boolean = t >= other
    def <(other: Term): Boolean = t < other
    def <=(other: Term): Boolean = t <= other
    def ==(other: Term): Boolean = t == other
    def max(other: Term): Term = if t >= other then t else other
    def min(other: Term): Term = if t <= other then t else other

case class InvalidTerm(value: Long) extends RuntimeException(s"Invalid term: $value") with NoStackTrace

/**
 * Type-safe LogIndex representation.
 * 
 * Log indices in RAFT start at 1 (0 means empty log).
 */
opaque type LogIndex = Long

object LogIndex:
  val Zero: LogIndex = 0L
  val First: LogIndex = 1L
  
  /** Safe constructor */
  def apply(value: Long): Either[InvalidLogIndex, LogIndex] =
    if value >= 0 then Right(value) else Left(InvalidLogIndex(value))
  
  /** Unsafe constructor */
  def unsafeFrom(value: Long): LogIndex = value
  
  /** Ordering instance */
  given Ordering[LogIndex] = Ordering.Long
  
  extension (idx: LogIndex)
    def value: Long = idx
    def next: LogIndex = idx + 1
    def prev: LogIndex = if idx > 0 then idx - 1 else 0
    def +(offset: Long): LogIndex = idx + offset
    def -(other: LogIndex): Long = idx - other
    def >(other: LogIndex): Boolean = idx > other
    def >=(other: LogIndex): Boolean = idx >= other
    def <(other: LogIndex): Boolean = idx < other
    def <=(other: LogIndex): Boolean = idx <= other
    def ==(other: LogIndex): Boolean = idx == other
    def max(other: LogIndex): LogIndex = if idx >= other then idx else other
    def min(other: LogIndex): LogIndex = if idx <= other then idx else other

case class InvalidLogIndex(value: Long) extends RuntimeException(s"Invalid log index: $value") with NoStackTrace
