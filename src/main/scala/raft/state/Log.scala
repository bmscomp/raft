package raft.state

/** Types of log entries in the RAFT log.
  *
  * Each entry in the replicated log is tagged with a type that determines how
  * the runtime processes it upon commitment.
  *
  * @see
  *   [[Log]] for the entry structure that carries this type
  */
enum LogType:
  /** A client command to be applied to the replicated state machine. */
  case Command

  /** A no-op entry appended by a new leader to commit entries from previous
    * terms.
    *
    * The RAFT protocol requires a leader to commit at least one entry from its
    * own term before it can safely determine which entries are committed.
    */
  case NoOp

  /** A cluster configuration change entry for dynamic membership.
    *
    * @see
    *   [[ClusterConfig]] for the configuration data model
    */
  case Configuration

/** A single entry in the RAFT replicated log.
  *
  * Log entries are the fundamental unit of replication in RAFT. Each entry is
  * uniquely identified by its `(index, term)` pair and carries either a client
  * command, a no-op, or a configuration change.
  *
  * @param index
  *   position in the log (1-indexed; 0 means empty)
  * @param term
  *   the leader's term when this entry was created
  * @param logType
  *   the kind of entry (Command, NoOp, or Configuration)
  * @param data
  *   serialized command payload (empty for NoOp entries)
  * @see
  *   [[raft.spi.LogStore]] for persistent storage of log entries
  * @see
  *   [[raft.spi.StateMachine]] for applying committed entries
  */
final case class Log(
    index: Long,
    term: Long,
    logType: LogType,
    data: Array[Byte]
):
  override def equals(other: Any): Boolean = other match
    case that: Log =>
      index == that.index &&
      term == that.term &&
      logType == that.logType &&
      java.util.Arrays.equals(data, that.data)
    case _ => false

  override def hashCode(): Int =
    31 * (31 * (31 * index.hashCode + term.hashCode) + logType.hashCode) +
      java.util.Arrays.hashCode(data)

/** Factory methods for creating [[Log]] entries. */
object Log:
  /** Create a client command log entry.
    *
    * @param index
    *   the log position for this entry
    * @param term
    *   the leader's current term
    * @param data
    *   the serialized command payload
    * @return
    *   a new command log entry
    */
  def command(index: Long, term: Long, data: Array[Byte]): Log =
    Log(index, term, LogType.Command, data)

  /** Create a no-op log entry (used by newly elected leaders).
    *
    * @param index
    *   the log position for this entry
    * @param term
    *   the leader's current term
    * @return
    *   a new no-op log entry with an empty payload
    */
  def noOp(index: Long, term: Long): Log =
    Log(index, term, LogType.NoOp, Array.emptyByteArray)
