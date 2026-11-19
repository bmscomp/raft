package raft.integration

import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.state.NodeState.*

/** Multi-node integration suite verifying Joint Consensus (`C_old,new ->
  * C_new`) live membership expansion (`3 -> 5`), `Learner` catch-up and
  * promotion to `Voter`, and active leader self-removal with automatic
  * step-down.
  */
class RaftNodeJointConsensusSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val n4 = NodeId("n4")
  private val n5 = NodeId("n5")

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  "RaftNode.proposeConfChange" should "expand a live 3-voter cluster to 5 voters via Joint Consensus (C_old,new -> C_new)" in {
    val initialIds = List(n1, n2, n3)
    val allIds = List(n1, n2, n3, n4, n5)
    val threeVoterCfg = ClusterConfig(initialIds.toSet)
    val fiveVoterCfg = ClusterConfig(allIds.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(
        allIds = allIds,
        initialClusterFor =
          id => if initialIds.contains(id) then threeVoterCfg else fiveVoterCfg,
        autoStartIds = Some(initialIds.toSet)
      )
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader(initialIds)
          (_, leader) = leaderPair

          // Commit baseline entries before membership change (also satisfies §4.1 barrier)
          _ <- (1 to 5).toList.traverse_ { i =>
            leader.proposeAndCommit(utf8(s"pre-$i=v$i"))
          }

          // Boot the two new physical nodes and execute Joint Consensus 3 -> 5
          _ <- cluster.node(n4).start
          _ <- cluster.node(n5).start
          cNewLogId <- leader.proposeConfChange(fiveVoterCfg)
          _ = cNewLogId.index should be > 5L

          // Verify all 5 nodes finalized C_new (not stuck in joint consensus)
          _ <- cluster.awaitCondition(
            allIds.forallM { id =>
              cluster.node(id).getClusterConfig.map { cfg =>
                !cfg.isInJointConsensus && cfg.voters == allIds.toSet
              }
            }
          )

          // Commit entries under 5-node quorum (requires 3 of 5) and verify full 5-node replication
          latestLeaderPair <- cluster.awaitLeader(allIds)
          (_, activeLeader) = latestLeaderPair
          _ <- (1 to 10).toList.traverse_ { i =>
            activeLeader.proposeAndCommit(utf8(s"post-$i=v$i"))
          }

          expected =
            (1 to 5).map(i => s"pre-$i" -> s"v$i").toMap ++
              (1 to 10).map(i => s"post-$i" -> s"v$i").toMap

          _ <- cluster.awaitCondition(
            allIds.forallM(id =>
              cluster.stateMachine(id).currentMap.map(_ == expected)
            )
          )
        yield ()
      }

    prog.unsafeRunSync()
  }

  it should "add a non-voting Learner, replicate full state to it, and promote it to a full Voter" in {
    val initialIds = List(n1, n2, n3)
    val allIds = List(n1, n2, n3, n4)
    val threeVoterCfg = ClusterConfig(initialIds.toSet)
    val withLearnerCfg = ClusterConfig(
      Map(
        n1 -> NodeRole.Voter,
        n2 -> NodeRole.Voter,
        n3 -> NodeRole.Voter,
        n4 -> NodeRole.Learner
      )
    )
    val promotedCfg = ClusterConfig(allIds.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(
        allIds = allIds,
        initialClusterFor = id =>
          if initialIds.contains(id) then threeVoterCfg else withLearnerCfg,
        autoStartIds = Some(initialIds.toSet)
      )
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader(initialIds)
          (_, leader) = leaderPair
          _ <- leader.proposeAndCommit(utf8("seed=initial"))

          // Start n4 and register it as a non-voting Learner
          _ <- cluster.node(n4).start
          _ <- leader.proposeConfChange(withLearnerCfg)
          _ <- (1 to 8).toList.traverse_ { i =>
            leader.proposeAndCommit(utf8(s"learn-$i=ok"))
          }

          expectedAfterLearn = Map("seed" -> "initial") ++
            (1 to 8).map(i => s"learn-$i" -> "ok").toMap

          // Learner n4 must catch up all state machine entries without being a voter
          _ <- cluster.awaitCondition(
            cluster.stateMachine(n4).currentMap.map(_ == expectedAfterLearn)
          )
          learnerCfg <- cluster.node(n4).getClusterConfig
          _ = learnerCfg.learners should contain(n4)
          _ = learnerCfg.voters should not contain n4

          // Promote n4 from Learner -> Voter via Joint Consensus
          curLeaderPair <- cluster.awaitLeader(initialIds)
          _ <- curLeaderPair._2.proposeConfChange(promotedCfg)

          _ <- cluster.awaitCondition(
            allIds.forallM { id =>
              cluster.node(id).getClusterConfig.map { cfg =>
                !cfg.isInJointConsensus &&
                cfg.voters == allIds.toSet &&
                cfg.learners.isEmpty
              }
            }
          )
        yield ()
      }

    prog.unsafeRunSync()
  }

  it should "step down the active leader when C_new removes it from voters and elect a successor" in {
    val allIds = List(n1, n2, n3, n4)
    val fourVoterCfg = ClusterConfig(allIds.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(allIds, _ => fourVoterCfg)
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader(allIds)
          (removedLeaderId, oldLeader) = leaderPair
          _ <- oldLeader.proposeAndCommit(utf8("before-removal=1"))

          remainingIds = allIds.filterNot(_ == removedLeaderId)
          threeVoterCfg = ClusterConfig(remainingIds.toSet)

          // Active leader proposes removing itself from the cluster
          _ <- oldLeader.proposeConfChange(threeVoterCfg)

          // Removed leader must step down to Follower once C_new commits
          _ <- cluster.awaitCondition(
            oldLeader.getState.map {
              case _: Follower => true
              case _           => false
            }
          )
          // Isolate the decommissioned node so it no longer participates
          _ <- cluster.isolateNode(removedLeaderId)

          // Remaining 3 voters must elect a new leader and commit further writes
          successorPair <- cluster.awaitLeaderExcluding(
            excluded = removedLeaderId,
            subset = remainingIds
          )
          (_, successor) = successorPair
          _ <- successor.proposeAndCommit(utf8("after-removal=2"))

          expected = Map("before-removal" -> "1", "after-removal" -> "2")
          _ <- cluster.awaitCondition(
            remainingIds.forallM(id =>
              cluster.stateMachine(id).currentMap.map(_ == expected)
            )
          )
        yield ()
      }

    prog.unsafeRunSync()
  }
