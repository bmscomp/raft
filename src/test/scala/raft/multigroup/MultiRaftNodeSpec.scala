package raft.multigroup

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.state.NodeState.Follower
import raft.impl.{InMemLogStore, InMemStableStore}
import raft.spi.{StateMachine, TimerService}
import raft.impl.DefaultTimerService

class MultiRaftNodeSpec extends AnyFunSuite with Matchers:

  private def mkGroupConfig(
      localId: NodeId,
      voters: Set[NodeId]
  ): IO[GroupConfig[IO]] =
    for
      logStore <- InMemLogStore[IO]()
      stableStore <- InMemStableStore[IO]()
      timerService <- DefaultTimerService[IO]
    yield GroupConfig[IO](
      raftConfig = RaftConfig.default(localId),
      cluster = ClusterConfig(voters.map(_ -> NodeRole.Voter).toMap),
      logStore = logStore,
      stableStore = stableStore,
      stateMachine = new StateMachine[IO, Unit]:
        def apply(log: Log): IO[Unit] = IO.unit
        def snapshot: IO[Array[Byte]] = IO.pure(Array.empty)
        def restore(data: Array[Byte]): IO[Unit] = IO.unit
      ,
      timerService = timerService
    )

  test("should create and list groups"):
    val node1 = NodeId("node1")
    val voters = Set(node1, NodeId("node2"), NodeId("node3"))

    val result = (for
      transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
      multi <- MultiRaftNode[IO](node1, transport)
      cfg1 <- mkGroupConfig(node1, voters)
      cfg2 <- mkGroupConfig(node1, voters)
      _ <- multi.createGroup(GroupId("shard-0"), cfg1)
      _ <- multi.createGroup(GroupId("shard-1"), cfg2)
      groups <- multi.listGroups
    yield groups).unsafeRunSync()

    result should contain allOf (GroupId("shard-0"), GroupId("shard-1"))
    result should have size 2

  test("should get group state"):
    val node1 = NodeId("node1")
    val voters = Set(node1, NodeId("node2"), NodeId("node3"))

    val result = (for
      transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
      multi <- MultiRaftNode[IO](node1, transport)
      cfg <- mkGroupConfig(node1, voters)
      _ <- multi.createGroup(GroupId("shard-0"), cfg)
      state <- multi.getGroupState(GroupId("shard-0"))
    yield state).unsafeRunSync()

    result shouldBe defined
    result.get shouldBe a[Follower]

  test("should return None for non-existent group state"):
    val node1 = NodeId("node1")

    val result = (for
      transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
      multi <- MultiRaftNode[IO](node1, transport)
      state <- multi.getGroupState(GroupId("ghost"))
    yield state).unsafeRunSync()

    result shouldBe None

  test("should remove group"):
    val node1 = NodeId("node1")
    val voters = Set(node1, NodeId("node2"), NodeId("node3"))

    val result = (for
      transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
      multi <- MultiRaftNode[IO](node1, transport)
      cfg <- mkGroupConfig(node1, voters)
      _ <- multi.createGroup(GroupId("shard-0"), cfg)
      _ <- multi.removeGroup(GroupId("shard-0"))
      groups <- multi.listGroups
    yield groups).unsafeRunSync()

    result shouldBe empty

  test("should raise GroupNotFoundException for submit to non-existent group"):
    val node1 = NodeId("node1")

    val error = intercept[GroupNotFoundException] {
      (for
        transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
        multi <- MultiRaftNode[IO](node1, transport)
        _ <- multi.submitToGroup(GroupId("ghost"), Array.emptyByteArray)
      yield ()).unsafeRunSync()
    }

    error.groupId shouldBe GroupId("ghost")

  test("should handle duplicate group creation gracefully"):
    val node1 = NodeId("node1")
    val voters = Set(node1, NodeId("node2"), NodeId("node3"))

    val result = (for
      transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
      multi <- MultiRaftNode[IO](node1, transport)
      cfg1 <- mkGroupConfig(node1, voters)
      cfg2 <- mkGroupConfig(node1, voters)
      _ <- multi.createGroup(GroupId("shard-0"), cfg1)
      _ <- multi.createGroup(GroupId("shard-0"), cfg2) // should be no-op
      groups <- multi.listGroups
    yield groups).unsafeRunSync()

    result should have size 1

  test("should shutdown all groups"):
    val node1 = NodeId("node1")
    val voters = Set(node1, NodeId("node2"), NodeId("node3"))

    val result = (for
      transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
      multi <- MultiRaftNode[IO](node1, transport)
      cfg1 <- mkGroupConfig(node1, voters)
      cfg2 <- mkGroupConfig(node1, voters)
      _ <- multi.createGroup(GroupId("shard-0"), cfg1)
      _ <- multi.createGroup(GroupId("shard-1"), cfg2)
      _ <- multi.shutdown
      groups <- multi.listGroups
    yield groups).unsafeRunSync()

    result shouldBe empty

  test("groups should operate independently"):
    val node1 = NodeId("node1")
    val voters = Set(node1, NodeId("node2"), NodeId("node3"))

    val (stateA, stateB) = (for
      transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
      multi <- MultiRaftNode[IO](node1, transport)
      cfgA <- mkGroupConfig(node1, voters)
      cfgB <- mkGroupConfig(node1, voters)
      _ <- multi.createGroup(GroupId("shard-a"), cfgA)
      _ <- multi.createGroup(GroupId("shard-b"), cfgB)
      nodeA <- multi.getGroup(GroupId("shard-a"))
      nodeB <- multi.getGroup(GroupId("shard-b"))
      stA <- nodeA.get.getState
      stB <- nodeB.get.getState
    yield (stA, stB)).unsafeRunSync()

    // Both groups should start as followers, independently
    stateA shouldBe a[Follower]
    stateB shouldBe a[Follower]
    // They are distinct instances
    stateA should not be theSameInstanceAs(stateB)
