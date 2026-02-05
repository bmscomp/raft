package raft.metrics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import raft.state.NodeId
import java.util.concurrent.atomic.AtomicInteger

class RaftMetricsSpec extends AnyFlatSpec with Matchers:
  
  "RaftMetrics.noop" should "return unit for all methods" in {
    val metrics = RaftMetrics.noop[IO]
    val nodeId = NodeId("test")
    
    val program = for
      _ <- metrics.onBecomeLeader(nodeId, 1)
      _ <- metrics.onStepDown(nodeId, 1)
      _ <- metrics.onElectionStarted(nodeId, 2)
      _ <- metrics.onVoteGranted(nodeId, NodeId("other"), 2)
      _ <- metrics.onEntriesAppended(nodeId, 10)
      _ <- metrics.onEntriesCommitted(nodeId, 100)
      _ <- metrics.onEntriesApplied(nodeId, 100)
      _ <- metrics.onSnapshotTaken(nodeId, 100)
      _ <- metrics.onHeartbeatSent(nodeId, 2)
      _ <- metrics.onReplicationFailure(nodeId, NodeId("follower"))
    yield ()
    
    // Should complete without error
    program.unsafeRunSync()
  }
  
  "Custom RaftMetrics" should "track events" in {
    val leaderCount = new AtomicInteger(0)
    val electionCount = new AtomicInteger(0)
    val commitCount = new AtomicInteger(0)
    
    val metrics = new RaftMetrics[IO]:
      def onBecomeLeader(nodeId: NodeId, term: Long): IO[Unit] = 
        IO(leaderCount.incrementAndGet()).void
      def onStepDown(nodeId: NodeId, term: Long): IO[Unit] = IO.unit
      def onElectionStarted(nodeId: NodeId, term: Long): IO[Unit] = 
        IO(electionCount.incrementAndGet()).void
      def onVoteGranted(nodeId: NodeId, to: NodeId, term: Long): IO[Unit] = IO.unit
      def onEntriesAppended(nodeId: NodeId, count: Int): IO[Unit] = IO.unit
      def onEntriesCommitted(nodeId: NodeId, commitIndex: Long): IO[Unit] = 
        IO(commitCount.incrementAndGet()).void
      def onEntriesApplied(nodeId: NodeId, lastApplied: Long): IO[Unit] = IO.unit
      def onSnapshotTaken(nodeId: NodeId, lastIndex: Long): IO[Unit] = IO.unit
      def onHeartbeatSent(nodeId: NodeId, followerCount: Int): IO[Unit] = IO.unit
      def onReplicationFailure(nodeId: NodeId, followerId: NodeId): IO[Unit] = IO.unit
    
    val nodeId = NodeId("test")
    
    val program = for
      _ <- metrics.onElectionStarted(nodeId, 1)
      _ <- metrics.onElectionStarted(nodeId, 2)
      _ <- metrics.onBecomeLeader(nodeId, 2)
      _ <- metrics.onEntriesCommitted(nodeId, 10)
      _ <- metrics.onEntriesCommitted(nodeId, 20)
      _ <- metrics.onEntriesCommitted(nodeId, 30)
    yield ()
    
    program.unsafeRunSync()
    
    leaderCount.get() shouldBe 1
    electionCount.get() shouldBe 2
    commitCount.get() shouldBe 3
  }
