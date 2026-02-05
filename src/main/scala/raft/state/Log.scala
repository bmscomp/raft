package raft.state

/**
 * Types of log entries in the RAFT log.
 */
enum LogType:
  /** Command to be applied to state machine */
  case Command
  
  /** No-op entry for leader assertion */
  case NoOp
  
  /** Cluster configuration change */
  case Configuration

/**
 * A single entry in the RAFT log.
 * 
 * @param index position in the log (1-indexed)
 * @param term term when entry was created
 * @param logType type of entry
 * @param data serialized command data
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

object Log:
  def command(index: Long, term: Long, data: Array[Byte]): Log =
    Log(index, term, LogType.Command, data)
  
  def noOp(index: Long, term: Long): Log =
    Log(index, term, LogType.NoOp, Array.emptyByteArray)
