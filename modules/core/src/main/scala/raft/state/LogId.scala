package raft.state

/** Compound log coordinate `(term, index)` inspired by Rust's `openraft`.
  *
  * Ordering `(term, index)` lexicographically matches Raft's §5.4.1 Election
  * Restriction ("which of two logs is more up-to-date?"): a candidate's last
  * log entry is at least as up-to-date as a voter's iff
  * `candidateLastLogId >= voterLastLogId`.
  */
final case class LogId(term: Long, index: Long) extends Ordered[LogId]:
  require(term >= 0, s"LogId.term must be >= 0, got $term")
  require(index >= 0, s"LogId.index must be >= 0, got $index")

  override def compare(that: LogId): Int =
    val termCmp = java.lang.Long.compare(this.term, that.term)
    if termCmp != 0 then termCmp
    else java.lang.Long.compare(this.index, that.index)

object LogId:
  val Zero: LogId = LogId(0L, 0L)

  def fromLog(entry: Log): LogId = LogId(entry.term, entry.index)

  given Ordering[LogId] = Ordering.fromLessThan(_ < _)

/** Persistent consensus state (`term`, `votedFor`, `commitIndex`) modeled after
  * `tikv/raft-rs`'s `HardState`.
  *
  * Must be persisted to stable storage before sending any messages in
  * `Ready.persistedMessages`.
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

  def isEmpty: Boolean = term == 0L && votedFor.isEmpty && commitIndex == 0L

object HardState:
  val Empty: HardState = HardState(0L, None, 0L)
  val empty: HardState = Empty

  /** Construct a [[HardState]] from typed [[Term]] and optional vote. */
  def apply(currentTerm: Term, votedFor: Option[NodeId]): HardState =
    HardState(currentTerm.value, votedFor, 0L)

/** Linearizable read confirmation emitted once leadership is verified at
  * `index` (modeled after `tikv/raft-rs`'s `ReadState`).
  *
  * Once the host system's state machine has applied entries up to `index`
  * (`lastApplied >= index`), the read identified by `requestCtx` may safely be
  * served from local state without touching the Raft log.
  */
final case class ReadState(
    index: Long,
    requestCtx: String
)

/** In-flight `ReadIndex` request awaiting heartbeat quorum confirmation. */
final case class PendingRead(
    requestId: String,
    readIndex: Long,
    acks: Set[NodeId]
)
