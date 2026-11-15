package raft.jepsen.workload

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import raft.jepsen.checker.{Operation, OpType, OpFunction, History}

/** Thread-safe recorder for building a Jepsen operation history.
  *
  * Records invocations and their completions from concurrent client fibers,
  * maintaining strict monotonic ordering via atomic Ref updates. Uses
  * `Async.monotonic` for timestamps to ensure consistency across fibers.
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
      ts <- Async[F].monotonic.map(_.toNanos)
      op = Operation(
        idx,
        process,
        OpType.Invoke,
        function,
        key,
        value,
        None,
        ts
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
    for
      ts <- Async[F].monotonic.map(_.toNanos)
      op = Operation(
        index,
        process,
        OpType.Ok,
        function,
        key,
        None,
        value,
        ts
      )
      _ <- ops.update(_ :+ op)
    yield ()

  /** Record a failed operation. */
  def fail(
      index: Long,
      process: Int,
      function: OpFunction,
      key: String,
      error: String
  ): F[Unit] =
    for
      ts <- Async[F].monotonic.map(_.toNanos)
      op = Operation(
        index,
        process,
        OpType.Fail,
        function,
        key,
        None,
        Some(error),
        ts
      )
      _ <- ops.update(_ :+ op)
    yield ()

  /** Record an informational event (e.g., timeout, not-leader). */
  def info(
      index: Long,
      process: Int,
      function: OpFunction,
      key: String,
      msg: String
  ): F[Unit] =
    for
      ts <- Async[F].monotonic.map(_.toNanos)
      op = Operation(
        index,
        process,
        OpType.Info,
        function,
        key,
        None,
        Some(msg),
        ts
      )
      _ <- ops.update(_ :+ op)
    yield ()

  /** Get the current recorded history. */
  def snapshot: F[History] =
    ops.get.map(History.fromOps)
