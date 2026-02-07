# Tutorial 3: Building a Replicated Task Queue

*How to guarantee that every task executes exactly once — even when workers crash, networks partition, and servers restart. The pattern behind Celery, Sidekiq, and every serious job processing system, made correct with Raft.*

---

## The Problem

You have a stream of tasks (send email, process payment, generate report) and a pool of workers. The requirements seem simple:

1. **Every task must execute** — no dropped tasks
2. **No task executes twice** — no duplicate emails, no double charges
3. **Tasks survive crashes** — if a worker dies mid-task, the task must be retried
4. **Order matters** (sometimes) — per-user tasks must execute in order

Redis-backed queues (Sidekiq, Bull) achieve requirement #1 and #3 but fail at #2: if a worker crashes after completing a task but before acknowledging it, the task re-executes. Message brokers (RabbitMQ, Kafka) can achieve #2 with careful idempotency design, but the burden is on the application developer.

With Raft, **the queue itself is the replicated state machine**. Task state transitions (enqueue → claimed → completed) are consensus-committed, making exactly-once semantics a property of the infrastructure, not the application.

## Why Raft Solves Exactly-Once

The key insight: a task's state transitions are **deterministic commands in the Raft log**. Every node processes the same transitions in the same order:

```
Log index 1: ENQUEUE task-42 (email to user@example.com)
Log index 2: CLAIM   task-42 by worker-A
Log index 3: ENQUEUE task-43 (process payment $49.99)
Log index 4: COMPLETE task-42 (success)
Log index 5: CLAIM   task-43 by worker-B
```

Because every node processes this log identically:
- If `COMPLETE task-42` is in the log, every node knows it completed — no re-execution
- If worker-A crashes after `CLAIM` but before `COMPLETE`, the claim TTL expires and another worker can claim it
- The Raft consensus guarantees this log is identical on every node — no split-brain queue state

## Task Lifecycle

```
  ┌──────────┐    CLAIM     ┌──────────┐   COMPLETE   ┌──────────┐
  │ PENDING  │─────────────►│ CLAIMED  │─────────────►│COMPLETED │
  └──────────┘              └──────────┘              └──────────┘
       ▲                         │
       │         TTL expires     │
       └─────────────────────────┘
                (re-enqueue)
```

Each state transition is a Raft command. The TTL expiration is also a command — submitted by a leader-side sweeper process — ensuring it's deterministically ordered relative to other state changes.

## Command Design

```scala
import java.nio.charset.StandardCharsets

case class TaskId(value: String) extends AnyVal

enum TaskCommand:
  case Enqueue(taskId: TaskId, payload: Array[Byte], maxRetries: Int)
  case Claim(taskId: TaskId, workerId: String, claimDurationMs: Long)
  case Complete(taskId: TaskId, workerId: String, result: String)
  case Fail(taskId: TaskId, workerId: String, error: String)
  case ExpireClaims(currentTimeMs: Long)

object TaskCommand:
  def encode(cmd: TaskCommand): Array[Byte] =
    cmd match
      case Enqueue(id, payload, retries) =>
        val header = s"ENQ|${id.value}|$retries|"
        header.getBytes(StandardCharsets.UTF_8) ++ payload
      case Claim(id, worker, dur) =>
        s"CLM|${id.value}|$worker|$dur".getBytes(StandardCharsets.UTF_8)
      case Complete(id, worker, result) =>
        s"CMP|${id.value}|$worker|$result".getBytes(StandardCharsets.UTF_8)
      case Fail(id, worker, err) =>
        s"FAL|${id.value}|$worker|$err".getBytes(StandardCharsets.UTF_8)
      case ExpireClaims(ts) =>
        s"EXP|$ts".getBytes(StandardCharsets.UTF_8)

  def decode(data: Array[Byte]): TaskCommand =
    val str = new String(data, StandardCharsets.UTF_8)
    val pipeIdx = str.indexOf('|')
    str.substring(0, pipeIdx) match
      case "ENQ" =>
        val parts = str.split("\\|", 4)
        Enqueue(TaskId(parts(1)), parts(3).getBytes(StandardCharsets.UTF_8),
                parts(2).toInt)
      case "CLM" =>
        val parts = str.split("\\|")
        Claim(TaskId(parts(1)), parts(2), parts(3).toLong)
      case "CMP" =>
        val parts = str.split("\\|", 4)
        Complete(TaskId(parts(1)), parts(2), parts(3))
      case "FAL" =>
        val parts = str.split("\\|", 4)
        Fail(TaskId(parts(1)), parts(2), parts(3))
      case "EXP" =>
        ExpireClaims(str.split("\\|")(1).toLong)
```

## State Machine

```scala
import cats.effect.{IO, Ref}
import raft.state.Log
import raft.spi.StateMachine

enum TaskStatus:
  case Pending
  case Claimed(workerId: String, claimExpiresAt: Long)
  case Completed(workerId: String, result: String)
  case Failed(workerId: String, error: String, retriesRemaining: Int)

case class Task(
  id: TaskId,
  payload: Array[Byte],
  status: TaskStatus,
  maxRetries: Int,
  attempts: Int,
  enqueuedAt: Long      // log index where the task was enqueued
)

case class QueueState(
  tasks: Map[TaskId, Task],
  completedCount: Long,
  failedCount: Long
)

class TaskQueueStateMachine(stateRef: Ref[IO, QueueState])
    extends StateMachine[IO, String]:

  def apply(log: Log): IO[String] =
    val cmd = TaskCommand.decode(log.data)
    cmd match
      case TaskCommand.Enqueue(taskId, payload, maxRetries) =>
        stateRef.modify { state =>
          if state.tasks.contains(taskId) then
            (state, s"DUPLICATE:${taskId.value}")
          else
            val task = Task(
              id = taskId,
              payload = payload,
              status = TaskStatus.Pending,
              maxRetries = maxRetries,
              attempts = 0,
              enqueuedAt = log.index
            )
            (state.copy(tasks = state.tasks + (taskId -> task)),
             s"ENQUEUED:${taskId.value}")
        }

      case TaskCommand.Claim(taskId, workerId, claimDurationMs) =>
        stateRef.modify { state =>
          state.tasks.get(taskId) match
            case Some(task) if task.status == TaskStatus.Pending =>
              val claimed = task.copy(
                status = TaskStatus.Claimed(workerId, log.index + claimDurationMs),
                attempts = task.attempts + 1
              )
              (state.copy(tasks = state.tasks + (taskId -> claimed)),
               s"CLAIMED:${taskId.value}:$workerId")
            case Some(task) =>
              (state, s"UNAVAILABLE:${taskId.value}:${task.status}")
            case None =>
              (state, s"NOT_FOUND:${taskId.value}")
        }

      case TaskCommand.Complete(taskId, workerId, result) =>
        stateRef.modify { state =>
          state.tasks.get(taskId) match
            case Some(task @ Task(_, _, TaskStatus.Claimed(claimant, _), _, _, _))
                if claimant == workerId =>
              val completed = task.copy(
                status = TaskStatus.Completed(workerId, result)
              )
              (state.copy(
                tasks = state.tasks + (taskId -> completed),
                completedCount = state.completedCount + 1
              ), s"COMPLETED:${taskId.value}")
            case Some(_) =>
              (state, s"NOT_OWNER:${taskId.value}:$workerId")
            case None =>
              (state, s"NOT_FOUND:${taskId.value}")
        }

      case TaskCommand.Fail(taskId, workerId, error) =>
        stateRef.modify { state =>
          state.tasks.get(taskId) match
            case Some(task @ Task(_, _, TaskStatus.Claimed(claimant, _), _, _, _))
                if claimant == workerId =>
              if task.maxRetries > 0 && task.attempts < task.maxRetries then
                // Return to pending for retry
                val retry = task.copy(
                  status = TaskStatus.Pending,
                  maxRetries = task.maxRetries
                )
                (state.copy(tasks = state.tasks + (taskId -> retry)),
                 s"RETRYING:${taskId.value}:attempt=${task.attempts}")
              else
                val failed = task.copy(
                  status = TaskStatus.Failed(workerId, error, 0)
                )
                (state.copy(
                  tasks = state.tasks + (taskId -> failed),
                  failedCount = state.failedCount + 1
                ), s"PERMANENTLY_FAILED:${taskId.value}")
            case _ =>
              (state, s"INVALID_FAIL:${taskId.value}")
        }

      case TaskCommand.ExpireClaims(currentTimeMs) =>
        stateRef.modify { state =>
          val expired = state.tasks.collect {
            case (id, task @ Task(_, _, TaskStatus.Claimed(_, expiresAt), maxR, att, _))
                if expiresAt <= currentTimeMs && att < maxR =>
              id -> task.copy(status = TaskStatus.Pending)
            case (id, task @ Task(_, _, TaskStatus.Claimed(_, expiresAt), _, att, _))
                if expiresAt <= currentTimeMs =>
              id -> task.copy(
                status = TaskStatus.Failed("timeout", "claim expired", 0)
              )
          }
          if expired.isEmpty then
            (state, "NO_EXPIRED")
          else
            (state.copy(
              tasks = state.tasks ++ expired,
              failedCount = state.failedCount +
                expired.count(_._2.status.isInstanceOf[TaskStatus.Failed])
            ), s"EXPIRED:${expired.size} tasks")
        }

  def snapshot: IO[Array[Byte]] = stateRef.get.map { state =>
    // Serialize only active tasks (pending + claimed)
    // Completed and failed tasks can be pruned during compaction
    val active = state.tasks.filter { (_, t) =>
      t.status == TaskStatus.Pending ||
      t.status.isInstanceOf[TaskStatus.Claimed]
    }
    s"${state.completedCount}|${state.failedCount}\n${active.size}"
      .getBytes(StandardCharsets.UTF_8)
  }

  def restore(data: Array[Byte]): IO[Unit] =
    // Simplified — production would deserialize full task state
    stateRef.set(QueueState(Map.empty, 0, 0))
```

### Why claim expiration is a command, not a timer

A subtle but critical design decision: claim expiration (`ExpireClaims`) is a Raft command, not a background timer on each node. Here's why:

If each node independently expired claims using its local clock:
- Node A (clock slightly fast) expires the claim at t=1000
- Node B (clock slightly slow) still thinks the claim is valid at t=1000
- The state machines diverge — **violating State Machine Safety**

By making expiration a command in the Raft log, all nodes process the same expiration at the same log position, deterministically. The leader runs a sweeper that periodically submits `ExpireClaims` commands:

```scala
def claimSweeper(interval: FiniteDuration): fs2.Stream[IO, Unit] =
  fs2.Stream
    .fixedDelay[IO](interval)
    .evalMap { _ =>
      val now = System.currentTimeMillis()
      val cmd = TaskCommand.encode(TaskCommand.ExpireClaims(now))
      raftNode.propose(Log.Command(0, 0, cmd))
    }
```

> **Determinism principle**: Wall-clock time enters the system once (when the leader creates the command) and is then replicated through the log. Every node uses the same timestamp from the log entry, not its own clock.

## Worker Implementation

```scala
class TaskWorker(workerId: String, raftNode: RaftNodeApi):

  def run: IO[Unit] =
    fs2.Stream
      .repeatEval(claimAndProcess)
      .metered(100.millis)  // poll interval
      .compile
      .drain

  private def claimAndProcess: IO[Unit] =
    for
      // Find a pending task (read from state machine)
      maybePending <- findPendingTask()
      _ <- maybePending match
        case Some(taskId) =>
          for
            // Claim the task (Raft write — committed across cluster)
            claimResult <- submitCommand(
              TaskCommand.Claim(taskId, workerId, claimDurationMs = 30_000)
            )
            _ <- claimResult match
              case s if s.startsWith("CLAIMED") =>
                executeTask(taskId).attempt.flatMap {
                  case Right(result) =>
                    submitCommand(TaskCommand.Complete(taskId, workerId, result))
                  case Left(error) =>
                    submitCommand(
                      TaskCommand.Fail(taskId, workerId, error.getMessage)
                    )
                }
              case _ =>
                IO.unit  // task was claimed by another worker — skip
          yield ()
        case None =>
          IO.unit  // no pending tasks
    yield ()

  private def executeTask(taskId: TaskId): IO[String] =
    // Your application logic here
    IO.println(s"[$workerId] Executing task ${taskId.value}") *>
    IO.pure("success")

  private def submitCommand(cmd: TaskCommand): IO[String] =
    val encoded = TaskCommand.encode(cmd)
    raftNode.propose(Log.Command(0, 0, encoded))
```

### The claim-then-execute pattern

The worker follows a strict protocol:

1. **Claim** (Raft write, committed) — "I'm working on task X"
2. **Execute** (local, no Raft) — the actual task work
3. **Complete** or **Fail** (Raft write, committed) — "task X is done/failed"

If the worker crashes between steps 1 and 3, the claim's TTL expires and the task returns to Pending. The next worker that claims it will re-execute it. This is safe because:
- The previous execution either had no side effects (crashed before doing anything) or had side effects that are idempotent (the fencing token pattern from Tutorial 2 ensures external systems reject stale operations)

## Failure Scenarios

### Scenario 1: Worker crashes mid-task

```
t=0    Worker-A claims task-42 (committed at log index 10)
t=1    Worker-A starts executing task-42
t=5    Worker-A crashes
t=35   ExpireClaims command committed — task-42 returns to Pending
t=36   Worker-B claims task-42 (committed at log index 15)
t=37   Worker-B executes and completes task-42
```

The task executes twice (partially on Worker-A, fully on Worker-B), but only Worker-B's completion is committed. The application must handle partial execution on Worker-A:
- For **idempotent tasks** (sending an email with a unique message ID): safe, the email provider deduplicates
- For **non-idempotent tasks** (charging a credit card): use the fencing token pattern with the claim's log index as the token

### Scenario 2: Leader crashes with pending ENQUEUEs

```
t=0    Client submits ENQUEUE task-99 to Leader
t=1    Leader appends to its log — crashes before replication
t=2    New leader elected — task-99 is NOT in the log
```

**Result**: Task-99 is lost. The client's `propose` call times out, and the client must re-submit. This is identical to any write-ahead system — uncommitted writes are lost on crash.

**Mitigation**: The client should retry with the same `TaskId`. The state machine's `if state.tasks.contains(taskId)` check prevents duplicate tasks from being created if the original ENQUEUE actually was committed before the crash.

### Scenario 3: Duplicate ENQUEUE after retry

```
t=0    Client submits ENQUEUE task-99 (attempt 1)
t=1    Committed, but client's connection drops before receiving ACK
t=2    Client retries: ENQUEUE task-99 (attempt 2)
t=3    State machine: tasks.contains(task-99) → true → "DUPLICATE"
```

**Result**: Exactly one task-99 exists in the queue. The idempotency key (`TaskId`) prevents duplicates even with client retries.

## Production Considerations

### Queue depth monitoring

```scala
def queueMetrics: IO[QueueMetrics] =
  stateRef.get.map { state =>
    val pending = state.tasks.count(_._2.status == TaskStatus.Pending)
    val claimed = state.tasks.count(_._2.status.isInstanceOf[TaskStatus.Claimed])
    QueueMetrics(
      pendingTasks = pending,
      claimedTasks = claimed,
      completedTotal = state.completedCount,
      failedTotal = state.failedCount
    )
  }
```

| Metric | Alert threshold | Action |
|--------|----------------|--------|
| Pending tasks | > 1000 | Add workers or increase claim concurrency |
| Claimed task age | > 2× claim TTL | Workers may be stuck — investigate |
| Failed task rate | > 5% | Application bugs or downstream failures |
| Queue size growth | Monotonically increasing | Processing can't keep up — scale out |

### Task compaction

Completed and failed tasks accumulate in the state machine. Compact them during snapshotting:

```scala
def compactState(state: QueueState, keepCompleted: Int = 100): QueueState =
  val active = state.tasks.filter { (_, t) =>
    t.status == TaskStatus.Pending ||
    t.status.isInstanceOf[TaskStatus.Claimed]
  }
  // Keep only the last N completed/failed for debugging
  val recent = state.tasks
    .filter((_, t) => t.status.isInstanceOf[TaskStatus.Completed] ||
                      t.status.isInstanceOf[TaskStatus.Failed])
    .toVector
    .sortBy(_._2.enqueuedAt)
    .takeRight(keepCompleted)
    .toMap
  state.copy(tasks = active ++ recent)
```

### Scaling with Multi-Raft Groups

A single Raft group handles ~10,000 tasks/second (limited by consensus round-trips). For higher throughput, partition the queue across multiple Raft groups using consistent hashing:

```scala
val numGroups = 16

def groupForTask(taskId: TaskId): GroupId =
  val hash = taskId.value.hashCode.abs % numGroups
  GroupId(s"queue-group-$hash")
```

Each group manages an independent subset of tasks. Workers can process tasks from any group, maximizing parallelism.

---

*Next: [Tutorial 4 — Distributed Rate Limiter](04-distributed-rate-limiter.md) — coordinating request throttling across multiple API gateway instances.*
