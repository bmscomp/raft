package examples

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import examples.cluster.{
  JointConsensusExample,
  LoggingStateMachine,
  ThreeNodeClusterExample
}
import examples.counter.{
  CounterCommand,
  CounterStateMachine,
  CounterWithCodecExample,
  TextMessageCodec
}
import examples.distributed.{
  DistributedCounterExample,
  DistributedTransactionExample,
  MultiGroupExample,
  RaftTransactionExample
}
import examples.kvstore.{KVCommand, KVResult, KVStateMachine, KVStoreExample}
import examples.lock.{
  DistributedLockExample,
  LockCommand,
  LockResult,
  LockStateMachine
}
import examples.protocol.{
  CommitTrackingExample,
  LeadershipTransferExample,
  LogMatchingExample,
  PreVoteExample,
  ReadIndexAndLeaseExample,
  TimerServiceExample
}
import examples.rawnode.RawNodeHostLoopExample
import examples.simulation.DeterministicSimulationExample
import raft.message.RaftMessage
import raft.state.{Log, NodeId}
import raft.state.NodeState.{Follower, Leader}

/** Automated verification suite executing **all 17 library examples** in
  * `modules/examples` end-to-end (plus snapshot/restore & codec edge cases) so
  * examples never bit-rot as protocol defaults evolve.
  */
class ExamplesVerificationSpec extends AnyFlatSpec with Matchers:

  "Sans-I/O & Simulation examples" should "run RawNodeHostLoopExample and DeterministicSimulationExample with bit-for-bit replay" in {
    RawNodeHostLoopExample.main(Array.empty)
    DeterministicSimulationExample.main(Array.empty)

    val r1 =
      DeterministicSimulationExample.runSimulation(seed = 777L, steps = 150)
    val r2 =
      DeterministicSimulationExample.runSimulation(seed = 777L, steps = 150)
    val rDifferent =
      DeterministicSimulationExample.runSimulation(seed = 778L, steps = 150)

    r1 shouldBe r2
    r1 should not be rDifferent
  }

  "Cluster & Membership examples" should "elect a genuine leader in ThreeNodeClusterExample and migrate voters in JointConsensusExample" in {
    val (s1, s2, s3) = ThreeNodeClusterExample.runSimulation.unsafeRunSync()
    s1 shouldBe a[Leader]
    s1.term shouldBe 1L
    s2 shouldBe Follower(1L, Some(NodeId("node-1")), Some(NodeId("node-1")))
    s3 shouldBe Follower(1L, Some(NodeId("node-1")), Some(NodeId("node-1")))

    // Verify empty & non-empty snapshot round-trip on LoggingStateMachine
    val emptyRoundTrip = (for
      fsm1 <- LoggingStateMachine[IO]
      snapEmpty <- fsm1.snapshot
      fsm2 <- LoggingStateMachine[IO]
      _ <- fsm2.apply(Log.command(1, 1, "temp".getBytes))
      _ <- fsm2.restore(snapEmpty)
      restoredEmpty <- fsm2.getApplied
    yield restoredEmpty).unsafeRunSync()
    emptyRoundTrip shouldBe Nil

    val (stillJoint, finalVoters, n4Commit) =
      JointConsensusExample.runMigration()
    stillJoint shouldBe false
    finalVoters shouldBe Set(
      NodeId("n2"),
      NodeId("n3"),
      NodeId("n4"),
      NodeId("n5")
    )
    n4Commit shouldBe 4L
  }

  "Protocol mechanics examples" should "execute PreVote, LogMatching, CommitTracking, TimerService, ReadIndex/LeaseRead, and LeadershipTransfer cleanly" in {
    PreVoteExample.run.timeout(15.seconds).unsafeRunSync()
    LogMatchingExample.run.timeout(15.seconds).unsafeRunSync()
    CommitTrackingExample.run.timeout(15.seconds).unsafeRunSync()
    TimerServiceExample.run.timeout(15.seconds).unsafeRunSync()
    ReadIndexAndLeaseExample.main(Array.empty)
    LeadershipTransferExample.main(Array.empty)
  }

  "KVStore, DistributedLock, and CounterWithCodec examples" should "elect leaders via Pre-Vote + Vote and preserve state across snapshot/restore" in {
    val kvFinal = KVStoreExample.runScenario.timeout(15.seconds).unsafeRunSync()
    kvFinal shouldBe Map(
      "user:1" -> Some("Alice"),
      "user:2" -> None,
      "config:timeout" -> Some("30s")
    )

    // Verify KVStateMachine CAS & values containing '=' survive snapshot/restore
    val kvSnapCheck = (for
      fsm <- KVStateMachine[IO]
      _ <- fsm.apply(
        Log.command(
          1,
          1,
          KVCommand.encode(KVCommand.Put("jdbc", "host=db;p=5432"))
        )
      )
      casOk <- fsm.apply(
        Log.command(
          2,
          1,
          KVCommand.encode(
            KVCommand.CAS("jdbc", "host=db;p=5432", "host=replica;p=5432")
          )
        )
      )
      casFail <- fsm.apply(
        Log.command(
          3,
          1,
          KVCommand.encode(KVCommand.CAS("jdbc", "stale", "other"))
        )
      )
      snap <- fsm.snapshot
      restored <- KVStateMachine[IO]
      _ <- restored.restore(snap)
      v <- restored.get("jdbc")
    yield (casOk, casFail, v)).unsafeRunSync()

    kvSnapCheck._1 shouldBe KVResult.CasOk
    kvSnapCheck._2 shouldBe a[KVResult.CasFailed]
    kvSnapCheck._3 shouldBe Some("host=replica;p=5432")

    // DistributedLockExample
    val (lockNodeState, finalOwner) =
      DistributedLockExample.runScenario.timeout(15.seconds).unsafeRunSync()
    lockNodeState shouldBe a[Leader]
    finalOwner shouldBe Some("client-2")

    val lockSnapCheck = (for
      lsm <- LockStateMachine[IO]
      r1 <- lsm.apply(
        Log.command(
          1,
          1,
          LockCommand.encode(
            LockCommand.Acquire("db-lock", "worker-1", 60.seconds)
          )
        )
      )
      rRenew <- lsm.apply(
        Log.command(
          2,
          1,
          LockCommand.encode(LockCommand.Heartbeat("db-lock", "worker-1"))
        )
      )
      snap <- lsm.snapshot
      lsm2 <- LockStateMachine[IO]
      _ <- lsm2.restore(snap)
      owner <- lsm2.getOwner("db-lock")
    yield (r1, rRenew, owner)).unsafeRunSync()

    lockSnapCheck shouldBe (
      LockResult.Acquired,
      LockResult.Renewed,
      Some("worker-1")
    )

    // CounterWithCodecExample
    val (counterNodeState, counterVal) =
      CounterWithCodecExample.runScenario.timeout(15.seconds).unsafeRunSync()
    counterNodeState shouldBe a[Leader]
    counterVal shouldBe 12L

    val voteMsg = RaftMessage.RequestVoteRequest(
      term = 9L,
      candidateId = NodeId("n1"),
      lastLogIndex = 42L,
      lastLogTerm = 8L,
      isPreVote = true
    )
    TextMessageCodec.decode(TextMessageCodec.encode(voteMsg)) shouldBe Right(
      voteMsg
    )

    val cntSnap = (for
      c1 <- CounterStateMachine[IO]
      _ <- c1.apply(
        Log.command(1, 1, CounterCommand.encode(CounterCommand.Increment(25L)))
      )
      snap <- c1.snapshot
      c2 <- CounterStateMachine[IO]
      _ <- c2.restore(snap)
      v <- c2.get
    yield v).unsafeRunSync()
    cntSnap shouldBe 25L
  }

  "Distributed Counter, Transactions, and Multi-Group examples" should "converge all 3 replicas via real Raft replication" in {
    // 1. DistributedCounterExample: +1, +1, +10, -1 = 11 across all 3 nodes
    val counterReplicas = DistributedCounterExample.runSimulationWithResult
      .timeout(15.seconds)
      .unsafeRunSync()
    counterReplicas shouldBe List(11L, 11L, 11L)

    // 2. DistributedTransactionExample: CAS + multi-op + versioned snapshot/restore
    val txnStore = DistributedTransactionExample.runDemoWithStore
      .timeout(15.seconds)
      .unsafeRunSync()
    val (stateBefore, verBefore, stateAfter, verAfter) = (for
      sb <- txnStore.getAll
      vb <- txnStore.getVersion("user:alice")
      snap <- txnStore.snapshot
      restored <- DistributedTransactionExample.TransactionalStore[IO]
      _ <- restored.restore(snap)
      sa <- restored.getAll
      va <- restored.getVersion("user:alice")
    yield (sb, vb, sa, va)).unsafeRunSync()

    stateBefore("account:A") shouldBe "850"
    stateBefore("counter:visits") shouldBe "6"
    stateAfter shouldBe stateBefore
    verAfter shouldBe verBefore
    verAfter shouldBe defined

    // 3. RaftTransactionExample: all 3 replicas converge to alice=800, bob=700
    val replicaMaps = RaftTransactionExample.runDemoStates
      .timeout(15.seconds)
      .unsafeRunSync()
    replicaMaps should have size 3
    replicaMaps.distinct should have size 1
    replicaMaps.head("account:alice") shouldBe "800"
    replicaMaps.head("account:bob") shouldBe "700"

    // 4. MultiGroupExample: 3-node sharded Multi-Raft cluster converges shard-0 & shard-1
    val (shard0Map, shard1Map) = MultiGroupExample.runClusterDemo
      .timeout(30.seconds)
      .unsafeRunSync()
    shard0Map shouldBe Map("alice" -> "value-a", "bob" -> "value-b")
    shard1Map shouldBe Map("nancy" -> "value-n", "zara" -> "value-z")
  }
