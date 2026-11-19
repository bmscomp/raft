package raft.integration

import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.state.NodeState.*

/** Multi-node integration suite verifying real `RaftNode[IO]` cluster election,
  * concurrent proposals, follower rejection hints, partition recovery, and
  * `CheckQuorum` minority step-down.
  */
class RaftNodeClusterSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val n4 = NodeId("n4")
  private val n5 = NodeId("n5")

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  "RaftNode cluster (TestControl virtual time)" should "elect a leader, commit writes, and serve readIndex deterministically" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val virtualProgram =
      RuntimeClusterHarness
        .clusterResource(
          allIds = ids,
          initialClusterFor = _ => clusterCfg,
          deterministicSeed = Some(42L)
        )
        .use { cluster =>
          for
            leaderPair <- cluster.awaitLeader()
            (_, leader) = leaderPair
            id1 <- leader.proposeAndCommit(utf8("vt-1=alpha"))
            id2 <- leader.proposeAndCommit(utf8("vt-2=beta"))
            readIdx <- leader.readIndex
            _ <- cluster.awaitCondition(
              ids.forallM(id =>
                cluster
                  .stateMachine(id)
                  .currentMap
                  .map(_ == Map("vt-1" -> "alpha", "vt-2" -> "beta"))
              )
            )
          yield {
            id2.index should be > id1.index
            readIdx should be >= id2.index
          }
        }

    TestControl.executeEmbed(virtualProgram).unsafeRunSync()
  }

  "RaftNode 5-node cluster" should "elect a single leader per term and replicate state across all 5 voters" in {
    val ids = List(n1, n2, n3, n4, n5)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(ids, _ => clusterCfg)
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader()
          (_, leader) = leaderPair
          states <- ids.traverse(id => cluster.node(id).getState)
          leadersInSameTerm = states
            .collect { case l: Leader => l.term }
            .groupBy(identity)
          _ = leadersInSameTerm.values.foreach(_.size shouldBe 1)
          _ <- (1 to 15).toList.traverse_ { i =>
            leader.proposeAndCommit(utf8(s"k$i=v$i"))
          }
          expected = (1 to 15).map(i => s"k$i" -> s"v$i").toMap
          _ <- cluster.awaitCondition(
            ids.forallM(id =>
              cluster.stateMachine(id).currentMap.map(_ == expected)
            )
          )
        yield ()
      }

    prog.unsafeRunSync()
  }

  "RaftNode 3-node cluster" should "commit 50 concurrent client proposals without lost or duplicated entries" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(ids, _ => clusterCfg)
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader()
          (_, leader) = leaderPair
          committedIds <- (1 to 50).toList.parTraverse { i =>
            leader.proposeAndCommit(utf8(s"conc-$i=val-$i"))
          }
          _ = committedIds.map(_.index).distinct.size shouldBe 50
          expected = (1 to 50).map(i => s"conc-$i" -> s"val-$i").toMap
          _ <- cluster.awaitCondition(
            ids.forallM(id =>
              cluster.stateMachine(id).currentMap.map(_ == expected)
            )
          )
          histories <- ids
            .traverse(id => cluster.stateMachine(id).appliedHistory)
          _ = histories.sliding(2).foreach {
            case List(h1, h2) => h1 shouldBe h2
            case _            => ()
          }
        yield ()
      }

    prog.unsafeRunSync()
  }

  it should "reject follower client operations with RaftError.NotLeader carrying the active leader hint" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(ids, _ => clusterCfg)
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader()
          (leaderId, leader) = leaderPair
          _ <- leader.proposeAndCommit(utf8("seed=1"))
          followerId = ids.find(_ != leaderId).get
          follower = cluster.node(followerId)
          _ <- cluster.awaitCondition(
            follower.getState.map {
              case Follower(_, _, Some(hint)) => hint == leaderId
              case _                          => false
            }
          )
          propRes <- follower.proposeAndCommit(utf8("bad=write")).attempt
          readRes <- follower.readIndex.attempt
          leaseRes <- follower.leaseRead.attempt
          confRes <- follower.proposeConfChange(clusterCfg).attempt
          xferRes <- follower.transferLeadership(leaderId).attempt
        yield {
          propRes shouldBe Left(RaftError.NotLeader(Some(leaderId)))
          readRes shouldBe Left(RaftError.NotLeader(Some(leaderId)))
          leaseRes shouldBe Left(RaftError.NotLeader(Some(leaderId)))
          confRes shouldBe Left(RaftError.NotLeader(Some(leaderId)))
          xferRes shouldBe Left(RaftError.NotLeader(Some(leaderId)))
        }
      }

    prog.unsafeRunSync()
  }

  it should "catch up an isolated follower in exact log order upon reconnection" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(ids, _ => clusterCfg)
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader()
          (leaderId, leader) = leaderPair
          laggingId = ids.find(_ != leaderId).get
          _ <- leader.proposeAndCommit(utf8("before=0"))
          _ <- cluster.isolateNode(laggingId)
          _ <- (1 to 30).toList.traverse_ { i =>
            leader.proposeAndCommit(utf8(s"gap-$i=val-$i"))
          }
          lagMapDuring <- cluster.stateMachine(laggingId).currentMap
          _ = lagMapDuring.contains("gap-1") shouldBe false
          _ <- cluster.healAll()
          expected = Map("before" -> "0") ++
            (1 to 30).map(i => s"gap-$i" -> s"val-$i").toMap
          _ <- cluster.awaitCondition(
            cluster.stateMachine(laggingId).currentMap.map(_ == expected)
          )
          leaderHist <- cluster.stateMachine(leaderId).appliedHistory
          lagHist <- cluster.stateMachine(laggingId).appliedHistory
          _ = lagHist shouldBe leaderHist
        yield ()
      }

    prog.unsafeRunSync()
  }

  it should "step down an isolated minority leader via CheckQuorum while the majority elects a new leader and commits" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(ids, _ => clusterCfg)
      .use { cluster =>
        for
          firstLeaderPair <- cluster.awaitLeader()
          (oldLeaderId, oldLeader) = firstLeaderPair
          oldTerm <- oldLeader.getState.map(_.term)
          _ <- oldLeader.proposeAndCommit(utf8("epoch-1=ok"))
          majorityIds = ids.filterNot(_ == oldLeaderId)

          // Isolate oldLeader into a 1-node minority partition
          _ <- cluster.isolateNode(oldLeaderId)

          // Old leader's blocked proposal must fail once CheckQuorum steps it down
          staleWriteFiber <- oldLeader
            .proposeAndCommit(utf8("stale=must-not-commit"))
            .attempt
            .start

          // Majority {f1, f2} elects a new higher-term leader and commits writes
          newLeaderPair <- cluster.awaitLeaderExcluding(
            excluded = oldLeaderId,
            subset = majorityIds,
            minTerm = oldTerm + 1L
          )
          (newLeaderId, newLeader) = newLeaderPair
          _ = newLeaderId should not be oldLeaderId
          _ <- (1 to 10).toList.traverse_ { i =>
            newLeader.proposeAndCommit(utf8(s"maj-$i=val-$i"))
          }

          // Isolated old leader must step down out of Leader via CheckQuorumTimeout
          _ <- cluster.awaitCondition(
            oldLeader.getState.map {
              case _: Leader => false
              case _         => true
            }
          )
          staleWriteOutcome <- staleWriteFiber.joinWithNever
          _ = staleWriteOutcome.isLeft shouldBe true

          // Heal partition and verify all 3 nodes converge without the stale write
          _ <- cluster.healAll()
          expected = Map("epoch-1" -> "ok") ++
            (1 to 10).map(i => s"maj-$i" -> s"val-$i").toMap
          _ <- cluster.awaitCondition(
            ids.forallM(id =>
              cluster.stateMachine(id).currentMap.map(_ == expected)
            )
          )
        yield ()
      }

    prog.unsafeRunSync()
  }
