package raft.state

/** Types of log entries in the RAFT log.
  *
  * Each entry in the replicated log is tagged with a type that determines how
  * the runtime processes it upon commitment. The type system distinguishes
  * between client commands (the primary payload), internal no-op entries used
  * for leader commitment, and configuration change entries for dynamic
  * membership (§6).
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
    * Raft's commitment rule (§5.4.2) states that a leader can only commit
    * entries from its ''own'' term using the majority-replication criterion.
    * Entries from previous terms are committed indirectly — once the leader's
    * own entry at a higher index is committed, all preceding entries are
    * implicitly committed. A freshly elected leader therefore appends a NoOp to
    * its log immediately, ensuring it can advance `commitIndex` as soon as the
    * NoOp is replicated to a majority.
    *
    * Without NoOp, a leader with no incoming client requests would be unable to
    * commit entries from earlier terms, which is the scenario depicted in
    * Figure 8 of the Raft paper.
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
  * The replicated log is Raft's central data structure (§5.3). It provides two
  * critical guarantees known as the '''Log Matching Property''':
  *
  *   1. If two entries in different logs have the same index and term, they
  *      store the same command — because a leader creates at most one entry per
  *      index per term.
  *   1. If two entries in different logs have the same index and term, then the
  *      logs are identical in all preceding entries — enforced by the
  *      `prevLogIndex` / `prevLogTerm` consistency check in `AppendEntries`.
  *
  * Together these properties ensure that committed entries are durable and
  * consistent across all nodes — the foundation of Raft's safety guarantees.
  *
  * Each entry carries serialized `data` that the [[raft.spi.StateMachine]]
  * decodes and applies upon commitment. Entries are applied in strict index
  * order, providing deterministic state machine replication.
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
