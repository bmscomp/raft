package raft.state

import scala.util.control.NoStackTrace

/** Type-safe Term representation using Scala 3 opaque types.
  *
  * In the Raft paper (§5.1), a ''term'' acts as a logical clock that partitions
  * time into epochs of leadership. Terms serve the same role as Lamport
  * timestamps in general distributed systems: they impose a partial order on
  * events so that stale information can be detected without synchronized
  * physical clocks.
  *
  * Every RPC carries the sender's current term. When a node receives a message
  * with a higher term, it immediately steps down to Follower and adopts the new
  * term — this is the mechanism that resolves split-brain situations and
  * ensures at most one leader per term (Election Safety Property, §5.2).
  *
  * This library models `Term` as a Scala 3 opaque type over `Long`. The opaque
  * type ensures compile-time safety (a `Term` cannot be accidentally mixed with
  * a `Long` log index or a message count) while incurring zero runtime boxing
  * cost — the JVM sees a primitive `long` at the bytecode level.
  *
  * @see
  *   [[LogIndex]] for the companion positional type in the log
  * @see
  *   [[raft.effect.Effect.PersistHardState]] for how terms are persisted
  */
opaque type Term = Long

/** Companion for [[Term]] providing safe constructors, ordering, and arithmetic
  * extensions.
  *
  * The `apply` constructor validates non-negativity (terms start at 0 before
  * any election), while `unsafeFrom` skips validation for trusted sources such
  * as on-disk stable storage.
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
  * In Raft (§5.3), every log entry is uniquely identified by its ''(index,
  * term)'' pair. The Log Matching Property guarantees that if two logs contain
  * an entry with the same index and term, then the logs are identical in all
  * preceding entries. This invariant is the foundation of Raft's consistency
  * guarantee.
  *
  * Indices are 1-based: the first real entry is at index 1, and index 0 is a
  * sentinel meaning "empty log". This convention simplifies the
  * `prevLogIndex = 0` case in `AppendEntries`, where an empty-log follower
  * always accepts entries without a log matching check.
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
