package raft.jepsen.workload

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import raft.jepsen.checker.{Operation, OpType, OpFunction, History}

/** Thread-safe recorder for building a Jepsen operation history.
  *
  * Records invocations and their completions from concurrent client fibers,
  * maintaining strict monotonic ordering via atomic Ref updates.
  */
class HistoryRecorder[F[_]: Async]:
  private val nextIndex = Ref.unsafe[F, Long](0L)
  private val ops = Ref.unsafe[F, Vector[Operation]](Vector.empty)

  /** Record an invocation. Returns the operation index for pairing with
    * completion.
    */
  def invoke(
      process: Int,
      function: OpFunction,
      key: String,
      value: Option[Any]
  ): F[Long] =
    for
      idx <- nextIndex.getAndUpdate(_ + 1)
      op = Operation(
        idx,
        process,
        OpType.Invoke,
        function,
        key,
        value,
        None,
        System.nanoTime()
      )
      _ <- ops.update(_ :+ op)
    yield idx

  /** Record a successful completion. */
  def ok(
      index: Long,
      process: Int,
      function: OpFunction,
      key: String,
      value: Option[Any]
  ): F[Unit] =
    val op = Operation(
      index,
      process,
      OpType.Ok,
      function,
      key,
      None,
      value,
      System.nanoTime()
    )
    ops.update(_ :+ op)

  /** Record a failed operation. */
  def fail(
      index: Long,
      process: Int,
      function: OpFunction,
      key: String,
      error: String
  ): F[Unit] =
    val op = Operation(
      index,
      process,
      OpType.Fail,
      function,
      key,
      None,
      Some(error),
      System.nanoTime()
    )
    ops.update(_ :+ op)

  /** Record an informational event (e.g., timeout). */
  def info(
      index: Long,
      process: Int,
      function: OpFunction,
      key: String,
      msg: String
  ): F[Unit] =
    val op = Operation(
      index,
      process,
      OpType.Info,
      function,
      key,
      None,
      Some(msg),
      System.nanoTime()
    )
    ops.update(_ :+ op)

  /** Get the current recorded history. */
  def snapshot: F[History] =
    ops.get.map(History.fromOps)
