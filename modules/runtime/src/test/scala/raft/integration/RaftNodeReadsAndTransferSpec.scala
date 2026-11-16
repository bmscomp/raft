package raft.integration

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.state.*
import raft.state.NodeState.*

/** Multi-node integration suite verifying linearizable `readIndex`, zero-RTT
  * `leaseRead` fast-path & expiration fallback, partitioned stale-leader read
  * rejection, and graceful `transferLeadership`.
  */
class RaftNodeReadsAndTransferSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  "RaftNode.readIndex" should "guarantee read-after-write linearizability across interleaved mutations" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(ids, _ => clusterCfg)
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader()
          (leaderId, leader) = leaderPair
          leaderSm = cluster.stateMachine(leaderId)
          _ <- (1 to 20).toList.traverse_ { step =>
            for
              logId <- leader.proposeAndCommit(utf8(s"counter=$step"))
              rIdx <- leader.readIndex
              observed <- leaderSm.get("counter")
            yield {
              rIdx should be >= logId.index
              observed shouldBe Some(step.toString)
            }
          }
        yield ()
      }

    prog.unsafeRunSync()
  }

  it should "never serve stale state from a minority-partitioned leader" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(ids, _ => clusterCfg)
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader()
          (oldLeaderId, oldLeader) = leaderPair
          oldTerm <- oldLeader.getState.map(_.term)
          _ <- oldLeader.proposeAndCommit(utf8("key=v1"))

          // Partition oldLeader away from the majority
          majorityIds = ids.filterNot(_ == oldLeaderId)
          _ <- cluster.isolateNode(oldLeaderId)

          // Majority elects a new leader and overwrites key=v2
          newLeaderPair <- cluster.awaitLeaderExcluding(
            excluded = oldLeaderId,
            subset = majorityIds,
            minTerm = oldTerm + 1L
          )
          (_, newLeader) = newLeaderPair
          _ <- newLeader.proposeAndCommit(utf8("key=v2"))

          // Attempt linearizable readIndex on isolated oldLeader: must fail (never return stale v1 as valid!)
          isolatedRead <- oldLeader.readIndex.timeout(1200.millis).attempt
          _ = isolatedRead.isLeft shouldBe true

          // Meanwhile newLeader serves fresh v2 via readIndex
          freshIdx <- newLeader.readIndex
          freshVal <- cluster.stateMachine(newLeaderPair._1).get("key")
          _ = freshIdx should be > 0L
          _ = freshVal shouldBe Some("v2")
        yield ()
      }

    prog.unsafeRunSync()
  }

  "RaftNode.leaseRead" should "serve zero-RTT reads while lease holds and return None once partitioned past lease duration" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(
        ids,
        _ => clusterCfg,
        configFor = id =>
          RuntimeClusterHarness.fastConfig(
            id,
            // Disable checkQuorum so old leader stays in Leader state long enough
            // to directly test leaseRead returning None upon lease expiry
            checkQuorumEnabled = false,
            leaseReadEnabled = true,
            leaderLeaseDuration = 220.millis
          )
      )
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader()
          (_, leader) = leaderPair
          committed <- leader.proposeAndCommit(utf8("lease-k=fresh"))

          // Allow a heartbeat round-trip to refresh monotonic lease
          _ <- cluster.awaitCondition(
            leader.leaseRead.map(_.exists(_ >= committed.index))
          )
          activeLease <- leader.leaseRead
          _ = activeLease shouldBe defined
          _ = activeLease.get should be >= committed.index

          // Isolate leader so heartbeats are no longer ACKed, then wait past leaderLeaseDuration
          _ <- cluster.isolateNode(leaderPair._1)
          _ <- IO.sleep(320.millis)
          expiredLease <- leader.leaseRead
          _ = expiredLease shouldBe None
        yield ()
      }

    prog.unsafeRunSync()
  }

  "RaftNode.transferLeadership" should "hand off leadership to the target voter and reject non-voter targets" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(ids, _ => clusterCfg)
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader()
          (initialLeaderId, initialLeader) = leaderPair
          _ <- initialLeader.proposeAndCommit(utf8("pre-xfer=1"))

          // Reject transfer to a non-existent / non-voter node
          badTarget <- initialLeader
            .transferLeadership(NodeId("ghost-node"))
            .attempt
          _ = badTarget match
            case Left(_: RaftError.ProposalDropped) => succeed
            case other                              =>
              fail(s"Expected ProposalDropped for non-voter, got $other")

          targetId = ids.find(_ != initialLeaderId).get
          _ <- initialLeader.transferLeadership(targetId)

          // Target voter must rapidly win election via TimeoutNow
          _ <- cluster.awaitCondition(
            cluster.node(targetId).getState.map {
              case _: Leader => true
              case _         => false
            }
          )
          targetLeader = cluster.node(targetId)
          postCommit <- targetLeader.proposeAndCommit(utf8("post-xfer=2"))
          _ = postCommit.index should be > 1L

          _ <- cluster.awaitCondition(
            ids.forallM(id =>
              cluster
                .stateMachine(id)
                .currentMap
                .map(_ == Map("pre-xfer" -> "1", "post-xfer" -> "2"))
            )
          )
        yield ()
      }

    prog.unsafeRunSync()
  }
