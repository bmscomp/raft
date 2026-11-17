package raft

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.impl.{
  DefaultTimerService,
  InMemLogStore,
  InMemSnapshotStore,
  InMemStableStore,
  InMemTransport
}
import raft.spi.{CompactionPolicy, StateMachine}
import raft.state.*
import raft.state.NodeState.*

class RaftNodeSpec extends AnyFlatSpec with Matchers:

  private final class KvStateMachine(ref: Ref[IO, Map[String, String]])
      extends StateMachine[IO, Unit]:
    def apply(log: Log): IO[Unit] =
      val s = new String(log.data, StandardCharsets.UTF_8)
      s.split("=", 2) match
        case Array(k, v) => ref.update(_.updated(k, v))
        case _           => IO.unit

    def snapshot: IO[Stream[IO, Byte]] =
      ref.get.map { m =>
        val raw = m.toSeq
          .sortBy(_._1)
          .map { case (k, v) => s"$k=$v" }
          .mkString("\n")
          .getBytes(StandardCharsets.UTF_8)
        Stream.chunk(Chunk.array(raw)).covary[IO]
      }

    def restore(data: Stream[IO, Byte]): IO[Unit] =
      data.compile.to(Array).flatMap { bytes =>
        val str = new String(bytes, StandardCharsets.UTF_8)
        val parsed =
          if str.isEmpty then Map.empty[String, String]
          else
            str
              .split("\n")
              .toList
              .flatMap { line =>
                line.split("=", 2) match
                  case Array(k, v) => Some(k -> v)
                  case _           => None
              }
              .toMap
        ref.set(parsed)
      }

    def currentMap: IO[Map[String, String]] = ref.get

  private def awaitLeader(
      nodes: List[(NodeId, RaftNode[IO])],
      retries: Int = 100
  ): IO[(NodeId, RaftNode[IO])] =
    nodes
      .traverse { case (id, n) => n.getState.map(st => (id, n, st)) }
      .flatMap { states =>
        states.collectFirst { case (id, n, _: Leader) => (id, n) } match
          case Some(found) => IO.pure(found)
          case None        =>
            if retries <= 0 then
              IO.raiseError(new RuntimeException("No leader elected"))
            else IO.sleep(20.millis) *> awaitLeader(nodes, retries - 1)
      }

  private def awaitCondition(
      cond: IO[Boolean],
      retries: Int = 100
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(new RuntimeException("Condition timed out"))
        else IO.sleep(20.millis) *> awaitCondition(cond, retries - 1)
    }

  "InMemStableStore" should "atomically read and persist HardState" in {
    val prog = for
      store <- InMemStableStore[IO]()
      init <- store.hardState
      _ = init shouldBe HardState.Empty
      _ <- store.setHardState(Term.unsafeFrom(7L), Some(NodeId("node-2")))
      after <- store.hardState
      term <- store.currentTerm
      vf <- store.votedFor
    yield {
      after shouldBe HardState(7L, Some(NodeId("node-2")))
      term.value shouldBe 7L
      vf shouldBe Some(NodeId("node-2"))
    }
    prog.unsafeRunSync()
  }

  "RaftNode (3-node cluster)" should "commit proposeAndCommit, serve readIndex & leaseRead, and reject follower writes" in {
    val n1 = NodeId("n1")
    val n2 = NodeId("n2")
    val n3 = NodeId("n3")
    val ids = List(n1, n2, n3)
    val cluster = ClusterConfig(ids.toSet)

    val prog = for
      transports <- InMemTransport.createCluster[IO](ids)
      timerSvc <- DefaultTimerService[IO]
      sms <- ids
        .traverse(id =>
          Ref
            .of[IO, Map[String, String]](Map.empty)
            .map(r => id -> new KvStateMachine(r))
        )
        .map(_.toMap)
      resources = ids.map { id =>
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutMin = 60.millis,
          electionTimeoutMax = 120.millis,
          heartbeatInterval = 20.millis,
          leaderLeaseDuration = 200.millis,
          leaseReadEnabled = true,
          checkQuorumEnabled = true
        )
        for
          logStore <- cats.effect.Resource.eval(InMemLogStore[IO]())
          stableStore <- cats.effect.Resource.eval(InMemStableStore[IO]())
          node <- RaftNode[IO](
            cfg,
            cluster,
            transports(id),
            logStore,
            stableStore,
            sms(id),
            timerSvc
          )
        yield id -> node
      }
      _ <- resources.sequence.use { nodes =>
        for
          _ <- nodes.traverse_(_._2.start)
          leaderPair <- awaitLeader(nodes)
          leaderId = leaderPair._1
          leaderNode = leaderPair._2
          // Wait for initial NoOp to commit so readIndex/leaseRead are ready
          logId1 <- leaderNode.proposeAndCommit(
            "alpha=1".getBytes(StandardCharsets.UTF_8)
          )
          logId2 <- leaderNode.proposeAndCommit(
            "beta=2".getBytes(StandardCharsets.UTF_8)
          )
          _ = logId2.index should be > logId1.index

          // Follower should reject proposeAndCommit with NotLeader
          followerNode = nodes.find(_._1 != leaderId).get._2
          followerAttempt <- followerNode
            .proposeAndCommit("bad=write".getBytes(StandardCharsets.UTF_8))
            .attempt
          _ = followerAttempt.left.toOption.get shouldBe a[RaftError.NotLeader]

          // Quorum ReadIndex
          rIdx <- leaderNode.readIndex
          _ = rIdx should be >= logId2.index

          // Fast LeaseRead
          leaseIdxOpt <- leaderNode.leaseRead
          _ = leaseIdxOpt shouldBe defined
          _ = leaseIdxOpt.get should be >= logId2.index

          // Verify all 3 state machines converge
          _ <- awaitCondition(
            ids.forallM(id =>
              sms(id).currentMap.map(_ == Map("alpha" -> "1", "beta" -> "2"))
            )
          )
        yield ()
      }
    yield ()

    prog.unsafeRunSync()
  }

  it should "auto-finalize Joint Consensus (C_old,new -> C_new), transfer leadership, and stream snapshots to lagging peers" in {
    val n1 = NodeId("n1")
    val n2 = NodeId("n2")
    val n3 = NodeId("n3")
    val n4 = NodeId("n4")
    val allIds = List(n1, n2, n3, n4)
    val initialCluster = ClusterConfig(Set(n1, n2, n3))

    val prog = for
      transports <- InMemTransport.createCluster[IO](allIds)
      timerSvc <- DefaultTimerService[IO]
      sms <- allIds
        .traverse(id =>
          Ref
            .of[IO, Map[String, String]](Map.empty)
            .map(r => id -> new KvStateMachine(r))
        )
        .map(_.toMap)
      snapStores <- allIds
        .traverse(id => InMemSnapshotStore[IO]().map(id -> _))
        .map(_.toMap)
      logStores <- allIds
        .traverse(id => InMemLogStore[IO]().map(id -> _))
        .map(_.toMap)
      stableStores <- allIds
        .traverse(id => InMemStableStore[IO]().map(id -> _))
        .map(_.toMap)
      mkNode = (id: NodeId, cl: ClusterConfig) =>
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutMin = 60.millis,
          electionTimeoutMax = 120.millis,
          heartbeatInterval = 20.millis,
          compaction = CompactionConfig(
            level = CompactionLevel.Custom,
            snapshotChunkSize = 32,
            retainedEntries = 0L,
            policy = Some(CompactionPolicy.threshold[IO](10L))
          )
        )
        RaftNode[IO](
          cfg,
          cl,
          transports(id),
          logStores(id),
          stableStores(id),
          sms(id),
          timerSvc,
          snapshotStoreOpt = Some(snapStores(id))
        )
      _ <- List(n1, n2, n3)
        .traverse(id => mkNode(id, initialCluster).map(id -> _))
        .use { initialNodes =>
          for
            // Start only n1, n2, n3 initially; write 15 entries to trigger compaction!
            _ <- initialNodes.traverse_(_._2.start)
            leaderPair <- awaitLeader(initialNodes)
            leaderId = leaderPair._1
            leaderNode = leaderPair._2
            _ <- (1 to 15).toList.traverse_ { i =>
              leaderNode.proposeAndCommit(
                s"k$i=v$i".getBytes(StandardCharsets.UTF_8)
              )
            }
            // Verify leader took a snapshot via CompactionManager
            leaderSnaps <- snapStores(leaderId).list
            _ = leaderSnaps should not be empty

            // Now start n4 and expand cluster to {n1, n2, n3, n4} via Joint Consensus!
            expandedCluster = ClusterConfig(Set(n1, n2, n3, n4))
            _ <- mkNode(n4, expandedCluster).use { node4 =>
              for
                _ <- node4.start
                confLogId <- leaderNode.proposeConfChange(expandedCluster)
                _ = confLogId.index should be > 15L
                finalCfg <- leaderNode.getClusterConfig
                _ = finalCfg.isInJointConsensus shouldBe false
                _ = finalCfg.voters shouldBe Set(n1, n2, n3, n4)

                // Verify n4 received the compacted snapshot via SnapshotTransfer + post-snapshot entries!
                _ <- leaderNode.proposeAndCommit(
                  "afterSnap=ok".getBytes(StandardCharsets.UTF_8)
                )
                _ <- awaitCondition(
                  sms(n4).currentMap.map { m =>
                    m.get("k1").contains("v1") &&
                    m.get("k15").contains("v15") &&
                    m.get("afterSnap").contains("ok")
                  }
                )

                // Transfer leadership to another voter
                targetId = (Set(n1, n2, n3) - leaderId).head
                _ <- leaderNode.transferLeadership(targetId)
                _ <- awaitCondition(
                  initialNodes.find(_._1 == targetId).get._2.getState.map {
                    case _: Leader => true
                    case _         => false
                  }
                )
              yield ()
            }
          yield ()
        }
    yield ()

    prog.unsafeRunSync()
  }
