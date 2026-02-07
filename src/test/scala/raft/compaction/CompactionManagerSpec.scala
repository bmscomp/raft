package raft.compaction

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.Ref
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import raft.impl.InMemLogStore
import raft.metrics.RaftMetrics
import raft.spi.{
  CompactionPolicy,
  SnapshotMetadata,
  SnapshotStore,
  StateMachine
}
import raft.state.*
import fs2.Stream

/** Tests for [[CompactionManager]] — the log compaction orchestrator.
  *
  * Validates:
  *   - Policy-driven compaction triggering
  *   - Full lifecycle: snapshot → persist → truncate
  *   - Multiple compaction rounds
  *   - Different compaction algorithms
  */
class CompactionManagerSpec extends AnyFlatSpec with Matchers:

  val nodeId = NodeId("test-node")
  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private def mkLogStore: IO[InMemLogStore[IO]] = InMemLogStore[IO]()

  private def mkStateMachine(dataRef: Ref[IO, String]): StateMachine[IO, Unit] =
    new StateMachine[IO, Unit]:
      def apply(log: Log): IO[Unit] =
        dataRef.update(_ + new String(log.data))

      def snapshot: IO[Stream[IO, Byte]] =
        dataRef.get.map(s => Stream.emits(s.getBytes).covary[IO])

      def restore(data: Stream[IO, Byte]): IO[Unit] =
        data.compile.to(Array).flatMap(bytes => dataRef.set(new String(bytes)))

  private def mkSnapshotStore: IO[(InMemSnapshotStore, InMemSnapshotStore)] =
    IO {
      val store = new InMemSnapshotStore
      (store, store)
    }

  class InMemSnapshotStore extends SnapshotStore[IO]:
    private var snapshots: Vector[Snapshot[IO]] = Vector.empty

    def save(snapshot: Snapshot[IO]): IO[Unit] = IO {
      snapshots = snapshots :+ snapshot
    }

    def loadLatest: IO[Option[Snapshot[IO]]] = IO {
      snapshots.lastOption
    }

    def list: IO[Vector[SnapshotMetadata]] = IO {
      // For metadata we need size. In a real store we'd know. here we might need to separate data?
      // Or just put 0 for now as InMemSnapshotStore is a mock.
      // But let's try to be correct if we can.
      // Since Snapshot now has Stream, we can't know size without consuming it.
      // Mock store can just return 0 or we consume it and recreate stream (expensive but correct for test).
      snapshots.map(s =>
        SnapshotMetadata(s.lastIncludedIndex, s.lastIncludedTerm, 0L)
      )
    }

    def delete(index: Long): IO[Unit] = IO {
      snapshots = snapshots.filterNot(_.lastIncludedIndex == index)
    }

    def snapshotCount: Int = snapshots.size

  "CompactionManager" should "not compact when policy says no" in {
    val test = for
      logStore <- mkLogStore
      snapshotStore = new InMemSnapshotStore
      dataRef <- Ref.of[IO, String]("")
      sm = mkStateMachine(dataRef)
      config = CompactionConfig(level = CompactionLevel.Disabled)
      manager <- CompactionManager.create[IO](
        config,
        logStore,
        snapshotStore,
        sm,
        nodeId,
        RaftMetrics.noop[IO],
        logger
      )
      entries = (1 to 100).map(i => Log.command(i, 1, s"cmd-$i".getBytes))
      _ <- logStore.append(entries)
      _ <- manager.maybeCompact(100, 100)
      snapshots <- snapshotStore.list
    yield snapshots shouldBe empty

    test.unsafeRunSync()
  }

  it should "compact when threshold is reached" in {
    val test = for
      logStore <- mkLogStore
      snapshotStore = new InMemSnapshotStore
      dataRef <- Ref.of[IO, String]("")
      sm = mkStateMachine(dataRef)
      config = CompactionConfig(level = CompactionLevel.Aggressive)
      manager <- CompactionManager.create[IO](
        config,
        logStore,
        snapshotStore,
        sm,
        nodeId,
        RaftMetrics.noop[IO],
        logger
      )
      entries = (1 to 100).map(i => Log.command(i, 1, s"cmd-$i".getBytes))
      _ <- logStore.append(entries)
      // Apply all entries to state machine
      _ <- entries.toList.traverse(sm.apply)
      _ <- manager.maybeCompact(100, 100)
      snapshots <- snapshotStore.list
    yield {
      snapshots.size shouldBe 1
      // Metadata check
      snapshots.head.lastIncludedIndex shouldBe 100
    }

    test.unsafeRunSync()
  }

  it should "perform multiple compaction rounds" in {
    val test = for
      logStore <- mkLogStore
      snapshotStore = new InMemSnapshotStore
      dataRef <- Ref.of[IO, String]("")
      sm = mkStateMachine(dataRef)
      config = CompactionConfig(level = CompactionLevel.Aggressive)
      manager <- CompactionManager.create[IO](
        config,
        logStore,
        snapshotStore,
        sm,
        nodeId,
        RaftMetrics.noop[IO],
        logger
      )
      // Round 1: 100 entries
      entries1 = (1 to 100).map(i => Log.command(i, 1, s"r1-$i".getBytes))
      _ <- logStore.append(entries1)
      _ <- entries1.toList.traverse(sm.apply)
      _ <- manager.maybeCompact(100, 100)
      snap1 <- snapshotStore.list
      // Round 2: 100 more entries
      entries2 = (101 to 200).map(i => Log.command(i, 2, s"r2-$i".getBytes))
      _ <- logStore.append(entries2)
      _ <- entries2.toList.traverse(sm.apply)
      _ <- manager.maybeCompact(200, 200)
      snap2 <- snapshotStore.list
    yield {
      snap1.size shouldBe 1
      snap2.size shouldBe 2
      snap2.last.lastIncludedIndex shouldBe 200
    }

    test.unsafeRunSync()
  }

  it should "work with size-based compaction" in {
    val test = for
      logStore <- mkLogStore
      snapshotStore = new InMemSnapshotStore
      dataRef <- Ref.of[IO, String]("")
      sm = mkStateMachine(dataRef)
      sizePolicy = CompactionPolicy.sizeBased[IO](50)
      config = CompactionConfig(
        level = CompactionLevel.Custom,
        policy = Some(sizePolicy)
      )
      manager <- CompactionManager.create[IO](
        config,
        logStore,
        snapshotStore,
        sm,
        nodeId,
        RaftMetrics.noop[IO],
        logger
      )
      entries = (1 to 30).map(i => Log.command(i, 1, s"cmd-$i".getBytes))
      _ <- logStore.append(entries)
      _ <- entries.toList.traverse(sm.apply)
      // 30 entries < 50 threshold → should not compact
      _ <- manager.maybeCompact(30, 30)
      noSnap <- snapshotStore.list
      moreEntries = (31 to 60).map(i => Log.command(i, 1, s"cmd-$i".getBytes))
      _ <- logStore.append(moreEntries)
      _ <- moreEntries.toList.traverse(sm.apply)
      // 60 entries ≥ 50 threshold → should compact
      _ <- manager.maybeCompact(60, 60)
      yesSnap <- snapshotStore.list
    yield {
      noSnap shouldBe empty
      yesSnap.size shouldBe 1
      yesSnap.head.lastIncludedIndex shouldBe 60
    }

    test.unsafeRunSync()
  }

  it should "work with timed compaction" in {
    val test = for
      logStore <- mkLogStore
      snapshotStore = new InMemSnapshotStore
      dataRef <- Ref.of[IO, String]("")
      sm = mkStateMachine(dataRef)
      timedPolicy = CompactionPolicy.timed[IO](25)
      config = CompactionConfig(
        level = CompactionLevel.Custom,
        policy = Some(timedPolicy)
      )
      manager <- CompactionManager.create[IO](
        config,
        logStore,
        snapshotStore,
        sm,
        nodeId,
        RaftMetrics.noop[IO],
        logger
      )
      entries = (1 to 50).map(i => Log.command(i, 1, s"cmd-$i".getBytes))
      _ <- logStore.append(entries)
      _ <- entries.toList.traverse(sm.apply)
      // Check at 24 → no compact
      _ <- manager.maybeCompact(24, 24)
      snap24 <- snapshotStore.list
      // Check at 25 → compact
      _ <- manager.maybeCompact(25, 25)
      snap25 <- snapshotStore.list
      // Check at 49 → no compact
      _ <- manager.maybeCompact(49, 49)
      snap49 <- snapshotStore.list
      // Check at 50 → compact
      _ <- manager.maybeCompact(50, 50)
      snap50 <- snapshotStore.list
    yield {
      snap24 shouldBe empty
      snap25.size shouldBe 1
      snap49.size shouldBe 1
      snap50.size shouldBe 2
    }

    test.unsafeRunSync()
  }

  it should "capture correct state machine data in snapshot" in {
    val test = for
      logStore <- mkLogStore
      snapshotStore = new InMemSnapshotStore
      dataRef <- Ref.of[IO, String]("")
      sm = mkStateMachine(dataRef)
      config = CompactionConfig(level = CompactionLevel.Aggressive)
      manager <- CompactionManager.create[IO](
        config,
        logStore,
        snapshotStore,
        sm,
        nodeId,
        RaftMetrics.noop[IO],
        logger
      )
      entries = (1 to 100).map(i => Log.command(i, 1, "X".getBytes))
      _ <- logStore.append(entries)
      _ <- entries.toList.traverse(sm.apply)
      _ <- manager.maybeCompact(100, 100)
      snapshot <- snapshotStore.loadLatest
    yield {
      snapshot shouldBe defined
      val data = snapshot.get.data.compile.to(Array).map(new String(_))
      data.unsafeRunSync() shouldBe "X" * 100
    }

    test.unsafeRunSync()
  }

  private implicit class TraverseOps[A](as: List[A]):
    def traverse(f: A => IO[Unit]): IO[Unit] =
      as.foldLeft(IO.unit)((acc, a) => acc *> f(a))
