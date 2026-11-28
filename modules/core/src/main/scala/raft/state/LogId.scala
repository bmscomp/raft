package raft.state

/** Compound log coordinate `(term, index)` inspired by Rust's `openraft`. */
final case class LogId(term: Long, index: Long) extends Ordered[LogId]:
  require(term >= 0, s"LogId.term must be >= 0, got $term")
  require(index >= 0, s"LogId.index must be >= 0, got $index")

  def typedTerm: Term = Term.unsafeFrom(term)
  def typedIndex: LogIndex = LogIndex.unsafeFrom(index)

  override def compare(that: LogId): Int =
    val termCmp = java.lang.Long.compare(this.term, that.term)
    if termCmp != 0 then termCmp
    else java.lang.Long.compare(this.index, that.index)

object LogId:
  val Zero: LogId = LogId(0L, 0L)

  def fromLog(entry: Log): LogId = LogId(entry.term, entry.index)

  def of(term: Term, index: LogIndex): LogId = LogId(term.value, index.value)

  def validate(term: Long, index: Long): Validated[ConfigError, LogId] =
    Validated.mapN(
      Validated.check(term >= 0L, ConfigError.NegativeTerm(term)),
      Validated.check(index >= 0L, ConfigError.NegativeLogIndex(index))
    )((_, _) => LogId(term, index))

  given Ordering[LogId] = Ordering.fromLessThan(_ < _)

/** Persistent consensus state (`term`, `votedFor`, `commitIndex`) modeled after
  * `tikv/raft-rs`'s `HardState`.
  */
final case class HardState(
    term: Long,
    votedFor: Option[NodeId] = None,
    commitIndex: Long = 0L
):
  require(term >= 0L, s"HardState.term must be >= 0, got $term")
  require(
    commitIndex >= 0L,
    s"HardState.commitIndex must be >= 0, got $commitIndex"
  )

  /** Typed [[Term]] view of [[term]] for storage and runtime SPIs. */
  def currentTerm: Term = Term.unsafeFrom(term)

  /** Typed [[LogIndex]] view of [[commitIndex]]. */
  def typedCommitIndex: LogIndex = LogIndex.unsafeFrom(commitIndex)

  def isEmpty: Boolean = term == 0L && votedFor.isEmpty && commitIndex == 0L

object HardState:
  val Empty: HardState = HardState(0L, None, 0L)
  val empty: HardState = Empty

  /** Construct a [[HardState]] from typed [[Term]] and optional vote. */
  def apply(currentTerm: Term, votedFor: Option[NodeId]): HardState =
    HardState(currentTerm.value, votedFor, 0L)

  /** Validate coordinates purely without throwing exceptions. */
  def validate(
      term: Long,
      votedFor: Option[NodeId] = None,
      commitIndex: Long = 0L
  ): Validated[ConfigError, HardState] =
    val voteCheck = votedFor match
      case Some(id) => NodeId.validate(id.value).map(_ => ())
      case None     => Validated.valid(())
    Validated.mapN(
      Validated.mapN(
        Validated.check(term >= 0L, ConfigError.NegativeTerm(term)),
        Validated.check(
          commitIndex >= 0L,
          ConfigError.NegativeLogIndex(commitIndex)
        )
      )((_, _) => ()),
      voteCheck
    )((_, _) => HardState(term, votedFor, commitIndex))

/** Linearizable read confirmation emitted once leadership is verified at
  * `index` (modeled after `tikv/raft-rs`'s `ReadState`).
  */
final case class ReadState(
    index: Long,
    requestCtx: String
)

/** In-flight `ReadIndex` request awaiting heartbeat quorum confirmation. */
final case class PendingRead(
    requestId: String,
    readIndex: Long,
    acks: Set[NodeId],
    minRound: Long = 0L
)
