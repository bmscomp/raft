package raft.state

import scala.util.control.NoStackTrace

/** Type-safe Term representation using Scala 3 opaque types.
  *
  * A ''Term'' is a monotonically increasing logical clock that identifies
  * leader epochs in the RAFT protocol. Each election increments the term, and
  * every RPC carries a term so that stale leaders can be detected.
  *
  * Terms must always be non-negative. The opaque type alias provides
  * compile-time safety with zero runtime overhead, preventing accidental mixing
  * with raw `Long` values or invalid arithmetic.
  *
  * @see
  *   [[LogIndex]] for the companion positional type in the log
  * @see
  *   [[raft.effect.Effect.PersistHardState]] for how terms are persisted
  */
opaque type Term = Long

/** Companion for [[Term]] providing constructors, ordering, and extension
  * methods.
  */
object Term:
  /** The initial term before any election has occurred. */
  val Zero: Term = 0L

  /** Safely construct a [[Term]] from a `Long`, rejecting negative values.
    *
    * @param value
    *   the raw term value; must be >= 0
    * @return
    *   `Right(term)` if valid, or `Left(InvalidTerm)` if negative
    */
  def apply(value: Long): Either[InvalidTerm, Term] =
    if value >= 0 then Right(value) else Left(InvalidTerm(value))

  /** Construct a [[Term]] without validation.
    *
    * Use only when the value is known to be valid (e.g., read from trusted
    * storage).
    *
    * @param value
    *   the raw term value, assumed non-negative
    * @return
    *   the term wrapping the given value
    */
  def unsafeFrom(value: Long): Term = value

  /** Implicit ordering for [[Term]], delegating to `Long` ordering. */
  given Ordering[Term] = Ordering.Long

  extension (t: Term)
    /** Unwrap to the underlying `Long` value. */
    def value: Long = t

    /** Return the next consecutive term (current + 1). */
    def increment: Term = t + 1

    /** @return `true` if this term is strictly greater than `other` */
    def >(other: Term): Boolean = t > other

    /** @return `true` if this term is greater than or equal to `other` */
    def >=(other: Term): Boolean = t >= other

    /** @return `true` if this term is strictly less than `other` */
    def <(other: Term): Boolean = t < other

    /** @return `true` if this term is less than or equal to `other` */
    def <=(other: Term): Boolean = t <= other

    /** @return `true` if this term is equal to `other` */
    def ==(other: Term): Boolean = t == other

    /** @return the larger of this term and `other` */
    def max(other: Term): Term = if t >= other then t else other

    /** @return the smaller of this term and `other` */
    def min(other: Term): Term = if t <= other then t else other

/** Error raised when attempting to create a [[Term]] with a negative value.
  *
  * @param value
  *   the invalid negative value that was rejected
  */
case class InvalidTerm(value: Long)
    extends RuntimeException(s"Invalid term: $value")
    with NoStackTrace

/** Type-safe LogIndex representation using Scala 3 opaque types.
  *
  * A ''LogIndex'' is the 1-based position of an entry in the RAFT replicated
  * log. Index 0 is a sentinel meaning "empty log" (no entries). The first real
  * entry is always at index 1.
  *
  * Like [[Term]], this opaque type prevents accidental misuse of raw `Long`
  * values while incurring no runtime boxing cost.
  *
  * @see
  *   [[Term]] for the companion temporal type
  * @see
  *   [[raft.state.Log]] for the entries stored at each index
  */
opaque type LogIndex = Long

/** Companion for [[LogIndex]] providing constructors, ordering, and extension
  * methods.
  */
object LogIndex:
  /** Sentinel index representing an empty log (no entries). */
  val Zero: LogIndex = 0L

  /** The index of the first real entry in the log. */
  val First: LogIndex = 1L

  /** Safely construct a [[LogIndex]] from a `Long`, rejecting negative values.
    *
    * @param value
    *   the raw index value; must be >= 0
    * @return
    *   `Right(index)` if valid, or `Left(InvalidLogIndex)` if negative
    */
  def apply(value: Long): Either[InvalidLogIndex, LogIndex] =
    if value >= 0 then Right(value) else Left(InvalidLogIndex(value))

  /** Construct a [[LogIndex]] without validation.
    *
    * @param value
    *   the raw index value, assumed non-negative
    * @return
    *   the log index wrapping the given value
    */
  def unsafeFrom(value: Long): LogIndex = value

  /** Implicit ordering for [[LogIndex]], delegating to `Long` ordering. */
  given Ordering[LogIndex] = Ordering.Long

  extension (idx: LogIndex)
    /** Unwrap to the underlying `Long` value. */
    def value: Long = idx

    /** @return the next consecutive log index (current + 1) */
    def next: LogIndex = idx + 1

    /** @return the previous log index, clamped to 0 */
    def prev: LogIndex = if idx > 0 then idx - 1 else 0

    /** @return a new log index offset by the given amount */
    def +(offset: Long): LogIndex = idx + offset

    /** @return the distance (in entries) between this index and `other` */
    def -(other: LogIndex): Long = idx - other

    /** @return `true` if this index is strictly greater than `other` */
    def >(other: LogIndex): Boolean = idx > other

    /** @return `true` if this index is greater than or equal to `other` */
    def >=(other: LogIndex): Boolean = idx >= other

    /** @return `true` if this index is strictly less than `other` */
    def <(other: LogIndex): Boolean = idx < other

    /** @return `true` if this index is less than or equal to `other` */
    def <=(other: LogIndex): Boolean = idx <= other

    /** @return `true` if this index is equal to `other` */
    def ==(other: LogIndex): Boolean = idx == other

    /** @return the larger of this index and `other` */
    def max(other: LogIndex): LogIndex = if idx >= other then idx else other

    /** @return the smaller of this index and `other` */
    def min(other: LogIndex): LogIndex = if idx <= other then idx else other

/** Error raised when attempting to create a [[LogIndex]] with a negative value.
  *
  * @param value
  *   the invalid negative value that was rejected
  */
case class InvalidLogIndex(value: Long)
    extends RuntimeException(s"Invalid log index: $value")
    with NoStackTrace
