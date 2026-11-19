package raft.state

/** Types of log entries in the RAFT log.
  *
  * @see
  *   [[Log]] for the entry structure that carries this type
  */
enum LogType:
  /** A client command to be applied to the replicated state machine. */
  case Command

  /** A no-op entry appended by a new leader to commit entries from previous
    * terms (§5.4.2, Figure 8).
    */
  case NoOp

  /** A cluster configuration change entry for dynamic membership (§6). */
  case Configuration

/** A single immutable entry in the RAFT replicated log (§5.3).
  *
  * Backed by immutable [[Bytes]] (`payload`) so mutating caller-supplied or
  * caller-extracted `Array[Byte]` buffers can never corrupt in-memory logs,
  * replication batches, or state-machine history.
  */
final case class Log(
    index: Long,
    term: Long,
    logType: LogType,
    payload: Bytes
):
  /** Defensive `Array[Byte]` copy of [[payload]] for binary SPI consumers. */
  def data: Array[Byte] = payload.toArray

  /** Typed [[LogIndex]] view of [[index]]. */
  def typedIndex: LogIndex = LogIndex.unsafeFrom(index)

  /** Typed [[Term]] view of [[term]]. */
  def typedTerm: Term = Term.unsafeFrom(term)

  /** Strip application command payload bytes when replicating to a
    * [[NodeRole.Witness]] tie-breaker while preserving `(index, term, logType)`
    * and cluster configuration entries.
    */
  def stripForWitness: Log =
    if logType == LogType.Command && payload.nonEmpty then
      copy(payload = Bytes.empty)
    else this

/** Factory methods for creating [[Log]] entries. */
object Log:
  /** Construct a [[Log]] entry by defensively copying `data` into [[Bytes]]. */
  def apply(
      index: Long,
      term: Long,
      logType: LogType,
      data: Array[Byte]
  ): Log =
    new Log(index, term, logType, Bytes.copyFrom(data))

  /** Create a client command log entry from an `Array[Byte]` (defensively
    * copied).
    */
  def command(index: Long, term: Long, data: Array[Byte]): Log =
    new Log(index, term, LogType.Command, Bytes.copyFrom(data))

  /** Create a client command log entry from immutable [[Bytes]] with zero copy.
    */
  def commandBytes(index: LogIndex, term: Term, payload: Bytes): Log =
    new Log(index.value, term.value, LogType.Command, payload)

  /** Create a cluster configuration log entry. */
  def configuration(index: Long, term: Long, config: ClusterConfig): Log =
    new Log(
      index,
      term,
      LogType.Configuration,
      ClusterConfig.encodeBytes(config)
    )

  /** Create a no-op log entry (used by newly elected leaders). */
  def noOp(index: Long, term: Long): Log =
    new Log(index, term, LogType.NoOp, Bytes.empty)
