package raft.jepsen.checker

/** Operation type in a Jepsen history. */
enum OpType:
  case Invoke
  case Ok
  case Fail
  case Info

/** Operation function (what the client requested). */
enum OpFunction:
  case Read
  case Write
  case CAS

/** A single operation in a Jepsen history.
  *
  * @param index
  *   unique operation index (invocation index)
  * @param process
  *   client thread/fiber ID
  * @param opType
  *   whether this is an invocation, completion, failure, or info
  * @param function
  *   the operation type
  * @param key
  *   the key being operated on
  * @param inputValue
  *   the value sent with the request
  * @param outputValue
  *   the value returned in the response
  * @param timestamp
  *   monotonic nanosecond timestamp
  */
case class Operation(
    index: Long,
    process: Int,
    opType: OpType,
    function: OpFunction,
    key: String,
    inputValue: Option[Any],
    outputValue: Option[Any],
    timestamp: Long
)

/** An immutable history of operations recorded during a Jepsen test. */
case class History(operations: Vector[Operation]):

  /** All invocations in this history. */
  def invocations: Vector[Operation] =
    operations.filter(_.opType == OpType.Invoke)

  /** All definite completions (Ok or Fail) in this history. */
  def completions: Vector[Operation] =
    operations.filter(op => op.opType == OpType.Ok || op.opType == OpType.Fail)

  /** All completion records including Info (indeterminate). */
  def allCompletions: Vector[Operation] =
    operations.filter(_.opType != OpType.Invoke)

  /** Pair each invocation with its completion (if any). */
  def pairs: Vector[(Operation, Option[Operation])] =
    val completionMap =
      completions.groupBy(_.index).view.mapValues(_.head).toMap
    invocations.map(inv => (inv, completionMap.get(inv.index)))

  /** Extract sub-history for a single key (P-compositionality). */
  def forKey(key: String): History =
    History(operations.filter(_.key == key))

  /** All distinct keys in this history. */
  def keys: Set[String] =
    operations.map(_.key).toSet

  /** Number of operations. */
  def size: Int = operations.size

  /** Remove Info (indeterminate) operations entirely — both their invocations
    * and info records. In Jepsen, Info ops represent operations whose outcome
    * is unknown (timeouts, not-leader), so they must not participate in
    * linearizability checking.
    */
  def withoutInfo: History =
    val infoIndices = operations
      .filter(_.opType == OpType.Info)
      .map(_.index)
      .toSet
    History(operations.filter { op =>
      op.opType != OpType.Info &&
      !(op.opType == OpType.Invoke && infoIndices.contains(op.index))
    })

  /** Remove incomplete operations (invocations without any completion). An
    * invocation is complete if it has an Ok, Fail, or Info record.
    */
  def trimIncomplete: History =
    val completedIndices = allCompletions.map(_.index).toSet
    History(operations.filter { op =>
      op.opType != OpType.Invoke || completedIndices.contains(op.index)
    })

  /** ASCII timeline visualization. */
  def timeline: String =
    val sb = new StringBuilder
    sb.append(
      f"${"Index"}%6s ${"Process"}%8s ${"Type"}%7s ${"Fn"}%6s ${"Key"}%10s ${"In"}%10s ${"Out"}%10s\n"
    )
    sb.append("-" * 70 + "\n")
    operations.foreach { op =>
      sb.append(
        f"${op.index}%6d ${op.process}%8d ${op.opType}%7s ${op.function}%6s ${op.key}%10s ${op.inputValue.getOrElse("-")}%10s ${op.outputValue.getOrElse("-")}%10s\n"
      )
    }
    sb.toString

object History:
  def empty: History = History(Vector.empty)

  /** Build a history from a mutable builder (used by HistoryRecorder). */
  def fromOps(ops: Seq[Operation]): History = History(ops.toVector)
