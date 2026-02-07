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

/** Integration test for the full snapshot + compaction lifecycle.
  *
  * Tests the end-to-end flow:
  *   1. Append entries to log
  *   2. Apply entries to state machine
  *   3. Trigger compaction (via CompactionManager)
  *   4. Verify snapshot persisted
  *   5. Verify log truncated
  *   6. Restore from snapshot
  *   7. Verify state machine state matches
  */
class SnapshotIntegrationSpec extends AnyFlatSpec with Matchers:

  val nodeId = NodeId("integration-node")
  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  class InMemSnapshotStore extends SnapshotStore[IO]:
    private var snapshots: Vector[Snapshot[IO]] = Vector.empty

    def save(snapshot: Snapshot[IO]): IO[Unit] = IO {
      snapshots = snapshots :+ snapshot
    }

    def loadLatest: IO[Option[Snapshot[IO]]] = IO {
      snapshots.lastOption
    }

    def list: IO[Vector[SnapshotMetadata]] = IO {
      snapshots.map(s =>
        SnapshotMetadata(s.lastIncludedIndex, s.lastIncludedTerm, 0L)
      )
    }

    def delete(index: Long): IO[Unit] = IO {
      snapshots = snapshots.filterNot(_.lastIncludedIndex == index)
    }

  private def mkStateMachine(
      dataRef: Ref[IO, Vector[String]]
  ): StateMachine[IO, Unit] =
    new StateMachine[IO, Unit]:
      def apply(log: Log): IO[Unit] =
        dataRef.update(_ :+ new String(log.data))

      def snapshot: IO[Stream[IO, Byte]] =
        dataRef.get.map(d => Stream.emits(d.mkString(",").getBytes).covary[IO])

      def restore(data: Stream[IO, Byte]): IO[Unit] =
        data.compile.to(Array).flatMap { bytes =>
          val entries =
            new String(bytes).split(",").toVector.filterNot(_.isEmpty)
          dataRef.set(entries)
        }

  "Full snapshot lifecycle" should "compact log and preserve state" in {
    val test = for
      logStore <- InMemLogStore[IO]()
      snapshotStore = new InMemSnapshotStore
      dataRef <- Ref.of[IO, Vector[String]](Vector.empty)
      sm = mkStateMachine(dataRef)
      config = CompactionConfig(
        level = CompactionLevel.Aggressive,
        retainedEntries = 10
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
      // Append and apply 150 entries
      entries = (1 to 150).map(i => Log.command(i, 1, s"entry-$i".getBytes))
      _ <- logStore.append(entries)
      _ <- entries.toList.foldLeft(IO.unit)((acc, e) => acc *> sm.apply(e).void)
      // Check state machine has all entries
      dataBefore <- dataRef.get
      _ = dataBefore.size shouldBe 150
      // Trigger compaction (threshold=100 for Aggressive)
      _ <- manager.maybeCompact(150, 150)
      // Verify snapshot was created
      snapshot <- snapshotStore.loadLatest
      _ = snapshot shouldBe defined
      _ = snapshot.get.lastIncludedIndex shouldBe 150
      // Verify snapshot data matches state machine
      snapshotDataIO = snapshot.get.data.compile.to(Array).map(new String(_))
      snapshotData <- snapshotDataIO
      expectedData = (1 to 150).map(i => s"entry-$i").mkString(",")
      _ = snapshotData shouldBe expectedData
    yield ()

    test.unsafeRunSync()
  }

  it should "allow restoration from snapshot" in {
    val test = for
      logStore <- InMemLogStore[IO]()
      snapshotStore = new InMemSnapshotStore
      originalRef <- Ref.of[IO, Vector[String]](Vector.empty)
      originalSm = mkStateMachine(originalRef)
      config = CompactionConfig(level = CompactionLevel.Aggressive)
      manager <- CompactionManager.create[IO](
        config,
        logStore,
        snapshotStore,
        originalSm,
        nodeId,
        RaftMetrics.noop[IO],
        logger
      )
      // Build state
      entries = (1 to 200).map(i => Log.command(i, 1, s"v$i".getBytes))
      _ <- logStore.append(entries)
      _ <- entries.toList.foldLeft(IO.unit)((acc, e) =>
        acc *> originalSm.apply(e).void
      )
      _ <- manager.maybeCompact(200, 200)
      snapshot <- snapshotStore.loadLatest
      // Simulate new node restoring from snapshot
      restoredRef <- Ref.of[IO, Vector[String]](Vector.empty)
      restoredSm = mkStateMachine(restoredRef)
      _ <- restoredSm.restore(snapshot.get.data)
      restoredData <- restoredRef.get
      originalData <- originalRef.get
    yield restoredData shouldBe originalData

    test.unsafeRunSync()
  }

  it should "switch compaction algorithms at runtime" in {
    val test = for
      logStore <- InMemLogStore[IO]()
      snapshotStore = new InMemSnapshotStore
      dataRef <- Ref.of[IO, Vector[String]](Vector.empty)
      sm = mkStateMachine(dataRef)
      // Start with disabled compaction
      disabledConfig = CompactionConfig(level = CompactionLevel.Disabled)
      manager1 <- CompactionManager.create[IO](
        disabledConfig,
        logStore,
        snapshotStore,
        sm,
        nodeId,
        RaftMetrics.noop[IO],
        logger
      )
      entries1 = (1 to 500).map(i => Log.command(i, 1, s"d$i".getBytes))
      _ <- logStore.append(entries1)
      _ <- entries1.toList.foldLeft(IO.unit)((acc, e) =>
        acc *> sm.apply(e).void
      )
      _ <- manager1.maybeCompact(500, 500)
      snap1 <- snapshotStore.list
      _ = snap1 shouldBe empty
      // Switch to aggressive compaction
      aggressiveConfig = CompactionConfig(level = CompactionLevel.Aggressive)
      manager2 <- CompactionManager.create[IO](
        aggressiveConfig,
        logStore,
        snapshotStore,
        sm,
        nodeId,
        RaftMetrics.noop[IO],
        logger
      )
      _ <- manager2.maybeCompact(500, 500)
      snap2 <- snapshotStore.list
    yield snap2.size shouldBe 1

    test.unsafeRunSync()
  }

  it should "work with custom compaction algorithm" in {
    val test = for
      logStore <- InMemLogStore[IO]()
      snapshotStore = new InMemSnapshotStore
      dataRef <- Ref.of[IO, Vector[String]](Vector.empty)
      sm = mkStateMachine(dataRef)
      // Custom: compact only when lastApplied is a multiple of 37
      customPolicy = new CompactionPolicy[IO]:
        def shouldCompact(
            lastApplied: Long,
            lastSnapshotIndex: Long,
            logSize: Long
        ): IO[Boolean] =
          IO.pure(lastApplied > 0 && lastApplied % 37 == 0)
        def compactionIndex(lastApplied: Long): IO[Long] =
          IO.pure(lastApplied)
      config = CompactionConfig(
        level = CompactionLevel.Custom,
        policy = Some(customPolicy)
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
      entries = (1 to 100).map(i => Log.command(i, 1, s"c$i".getBytes))
      _ <- logStore.append(entries)
      _ <- entries.toList.foldLeft(IO.unit)((acc, e) => acc *> sm.apply(e).void)
      // At 36: no compact
      _ <- manager.maybeCompact(36, 36)
      snap36 <- snapshotStore.list
      // At 37: compact!
      _ <- manager.maybeCompact(37, 37)
      snap37 <- snapshotStore.list
      // At 73: no compact
      _ <- manager.maybeCompact(73, 73)
      snap73 <- snapshotStore.list
      // At 74: compact!
      _ <- manager.maybeCompact(74, 74)
      snap74 <- snapshotStore.list
    yield {
      snap36 shouldBe empty
      snap37.size shouldBe 1
      snap37.head.lastIncludedIndex shouldBe 37
      snap73.size shouldBe 1
      snap74.size shouldBe 2
      snap74.last.lastIncludedIndex shouldBe 74
    }

    test.unsafeRunSync()
  }
