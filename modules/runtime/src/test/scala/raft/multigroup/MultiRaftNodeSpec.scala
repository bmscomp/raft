package raft.multigroup

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import fs2.Stream

import raft.state.*
import raft.state.NodeState.Follower
import raft.impl.{InMemLogStore, InMemStableStore, DefaultTimerService}
import raft.spi.StateMachine

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
        def snapshot: IO[Stream[IO, Byte]] = IO.pure(Stream.empty)
        def restore(data: Stream[IO, Byte]): IO[Unit] = data.compile.drain
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

  test("should query group cluster config and reject unknown group confChange"):
    val node1 = NodeId("node1")
    val voters = Set(node1, NodeId("node2"), NodeId("node3"))

    val (cfgPresent, cfgMissing) = (for
      transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
      multi <- MultiRaftNode[IO](node1, transport)
      cfg <- mkGroupConfig(node1, voters)
      _ <- multi.createGroup(GroupId("shard-0"), cfg)
      present <- multi.getGroupClusterConfig(GroupId("shard-0"))
      missing <- multi.getGroupClusterConfig(GroupId("ghost"))
      _ <- multi.shutdown
    yield (present, missing)).unsafeRunSync()

    cfgPresent.map(_.voters) shouldBe Some(voters)
    cfgMissing shouldBe None

    val err = intercept[GroupNotFoundException] {
      (for
        transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
        multi <- MultiRaftNode[IO](node1, transport)
        _ <- multi.proposeConfChangeToGroup(
          GroupId("ghost"),
          ClusterConfig(voters)
        )
      yield ()).unsafeRunSync()
    }
    err.groupId shouldBe GroupId("ghost")
    err.toRaftError shouldBe RaftError.GroupNotFound(GroupId("ghost"))

  test(
    "should execute proposeToGroup, proposeBytesToGroup, proposeBatchAndCommitToGroup, readIndexFromGroup, leaseReadFromGroup, and rebalanceLeadership on live single-node groups"
  ):
    import scala.concurrent.duration.*
    import raft.state.NodeState.Leader

    val node1 = NodeId("solo-1")
    val voters = Set(node1)
    val gid = GroupId("solo-shard")

    def awaitLeader(multi: MultiRaftNode[IO], retries: Int = 200): IO[Unit] =
      multi.getGroupState(gid).flatMap {
        case Some(_: Leader) => IO.unit
        case _               =>
          if retries <= 0 then
            IO.raiseError(new RuntimeException("Solo shard did not elect"))
          else IO.sleep(15.millis) *> awaitLeader(multi, retries - 1)
      }

    val prog = MultiGroupTransport
      .resource[IO](node1, _ => IO.unit)
      .flatMap(tp => MultiRaftNode.resource[IO](node1, tp))
      .use { multi =>
        for
          ls <- InMemLogStore[IO]()
          ss <- InMemStableStore[IO]()
          ts <- DefaultTimerService[IO]
          cfg = GroupConfig[IO](
            raftConfig = RaftConfig(
              localId = node1,
              electionTimeoutMin = 60.millis,
              electionTimeoutMax = 120.millis,
              heartbeatInterval = 20.millis,
              leaseReadEnabled = true,
              leaderLeaseDuration = 250.millis
            ),
            cluster = ClusterConfig(voters),
            logStore = ls,
            stableStore = ss,
            stateMachine = new StateMachine[IO, Unit]:
              def apply(log: Log): IO[Unit] = IO.unit
              def snapshot: IO[Stream[IO, Byte]] = IO.pure(Stream.empty)
              def restore(data: Stream[IO, Byte]): IO[Unit] = data.compile.drain
            ,
            timerService = ts
          )
          _ <- multi.createGroup(gid, cfg)
          _ <- awaitLeader(multi)
          id1 <- multi.proposeToGroup(gid, "k1=v1".getBytes("UTF-8"))
          id2 <- multi.proposeBytesToGroup(gid, Bytes.fromUtf8("k2=v2"))
          batch <- multi.proposeBatchAndCommitToGroup(
            gid,
            Seq("k3=v3".getBytes("UTF-8"), "k4=v4".getBytes("UTF-8"))
          )
          rIdx <- multi.readIndexFromGroup(gid)
          lIdx <- multi.leaseReadFromGroup(gid)
          rebalSelf <- multi.rebalanceLeadership(Map(gid -> node1))
          e1 <- multi
            .proposeToGroup(GroupId("missing"), Array.emptyByteArray)
            .attempt
          e2 <- multi
            .proposeAndCommitToGroup(GroupId("missing"), Array.emptyByteArray)
            .attempt
          e3 <- multi
            .proposeAndApplyToGroup(GroupId("missing"), Bytes.empty)
            .attempt
          e4 <- multi
            .proposeBatchAndCommitToGroup(GroupId("missing"), Seq.empty)
            .attempt
          e5 <- multi.readIndexFromGroup(GroupId("missing")).attempt
          e6 <- multi.leaseReadFromGroup(GroupId("missing")).attempt
          e7 <- multi.transferGroupLeadership(GroupId("missing"), node1).attempt
        yield
          id2.index should be > id1.index
          batch should have size 2
          rIdx should be >= batch.last.index
          lIdx.isDefined shouldBe true
          rebalSelf shouldBe 0
          List(e1, e2, e3, e4, e5, e6, e7).foreach { res =>
            res.left.toOption.get shouldBe GroupNotFoundException(
              GroupId("missing")
            )
          }
      }

    prog.timeout(20.seconds).unsafeRunSync()

  test("should roll back transport registration if group initialization fails"):
    val node1 = NodeId("node1")
    val gid = GroupId("failing-shard")
    val brokenStableStore = new raft.spi.StableStore[IO]:
      def currentTerm: IO[Term] =
        IO.raiseError(new RuntimeException("disk boom"))
      def setCurrentTerm(term: Term): IO[Unit] = IO.unit
      def votedFor: IO[Option[NodeId]] = IO.pure(None)
      def setVotedFor(nodeId: Option[NodeId]): IO[Unit] = IO.unit
      def hardState: IO[HardState] =
        IO.raiseError(new RuntimeException("disk boom"))
      def setHardState(term: Term, votedFor: Option[NodeId]): IO[Unit] = IO.unit

    val result = (for
      transport <- MultiGroupTransport[IO](node1, _ => IO.unit)
      multi <- MultiRaftNode[IO](node1, transport)
      ls <- InMemLogStore[IO]()
      ts <- DefaultTimerService[IO]
      badCfg = GroupConfig[IO](
        raftConfig = RaftConfig.default(node1),
        cluster = ClusterConfig(Set(node1)),
        logStore = ls,
        stableStore = brokenStableStore,
        stateMachine = new StateMachine[IO, Unit]:
          def apply(log: Log): IO[Unit] = IO.unit
          def snapshot: IO[Stream[IO, Byte]] = IO.pure(Stream.empty)
          def restore(data: Stream[IO, Byte]): IO[Unit] = data.compile.drain
        ,
        timerService = ts
      )
      attempt <- multi.createGroup(gid, badCfg).attempt
      groupsAfter <- multi.listGroups
      goodCfg <- mkGroupConfig(node1, Set(node1))
      _ <- multi.createGroup(gid, goodCfg)
      groupsRecovered <- multi.listGroups
      _ <- multi.shutdown
    yield (attempt, groupsAfter, groupsRecovered)).unsafeRunSync()

    result._1.isLeft shouldBe true
    result._2 shouldBe empty
    result._3 shouldBe Set(gid)
