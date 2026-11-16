package raft.integration

import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.spi.CompactionPolicy
import raft.state.*

/** Multi-node integration suite verifying automatic log compaction, WAL prefix
  * discarding, multi-chunk `SnapshotTransfer` (`InstallSnapshotRequest`),
  * `RSNP` v1 `ClusterConfig` envelope preservation, and trailing log catch-up.
  */
class RaftNodeSnapshotCatchupSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val n4 = NodeId("n4")

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  private def smallChunkCompaction: CompactionConfig =
    CompactionConfig(
      level = CompactionLevel.Custom,
      snapshotChunkSize = 64,
      retainedEntries = 0L,
      policy = Some(CompactionPolicy.threshold[IO](20L))
    )

  "RaftNode snapshot catch-up" should "compact leader logs and stream multi-chunk snapshots + trailing entries to a lagging follower" in {
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = RuntimeClusterHarness
      .clusterResource(
        allIds = ids,
        initialClusterFor = _ => clusterCfg,
        configFor = id =>
          RuntimeClusterHarness
            .fastConfig(id, compaction = smallChunkCompaction)
      )
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader(ids)
          (leaderId, leader) = leaderPair
          laggingId = ids.find(_ != leaderId).get

          // Isolate laggingId while active quorum commits 45 entries (exceeding threshold=20 twice)
          _ <- cluster.isolateNode(laggingId)
          _ <- (1 to 45).toList.traverse_ { i =>
            leader.proposeAndCommit(utf8(s"snap-key-$i=payload-value-$i"))
          }

          // Verify leader saved a snapshot and discarded early log entries (retainedEntries = 0)
          leaderSnapOpt <- cluster.snapshotStore(leaderId).loadLatest
          _ = leaderSnapOpt shouldBe defined
          _ = leaderSnapOpt.get.lastIncludedIndex should be >= 20L
          firstLogOpt <- cluster.logStore(leaderId).get(LogIndex.unsafeFrom(1L))
          _ = firstLogOpt shouldBe None

          // Append 10 trailing entries after snapshot compaction
          _ <- (46 to 55).toList.traverse_ { i =>
            leader.proposeAndCommit(utf8(s"snap-key-$i=payload-value-$i"))
          }

          // Reconnect laggingId: must receive multi-chunk InstallSnapshot + trailing AppendEntries
          _ <- cluster.healAll()
          expected =
            (1 to 55).map(i => s"snap-key-$i" -> s"payload-value-$i").toMap

          _ <- cluster
            .awaitCondition(
              cluster.stateMachine(laggingId).currentMap.map(_ == expected),
              retries = 300
            )
            .handleErrorWith { err =>
              ids
                .traverse { id =>
                  for
                    st <- cluster.node(id).getState
                    m <- cluster.stateMachine(id).currentMap
                    snaps <- cluster.snapshotStore(id).list
                    lastIdx <- cluster.logStore(id).lastIndex
                  yield s"${id.value}: state=$st, mapSize=${m.size}, missing=${(expected.keySet -- m.keySet).toSeq.sorted.take(8)}, lastLog=${lastIdx.value}, snaps=${snaps.map(_.lastIncludedIndex)}"
                }
                .flatMap(lines =>
                  IO.raiseError(
                    new RuntimeException(
                      s"${err.getMessage}\n${lines.mkString("\n")}",
                      err
                    )
                  )
                )
            }
          followerSnapOpt <- cluster.snapshotStore(laggingId).loadLatest
          _ = followerSnapOpt shouldBe defined
          _ = followerSnapOpt.get.lastIncludedIndex should be >= 20L
        yield ()
      }

    prog.unsafeRunSync()
  }

  it should "preserve committed ClusterConfig inside RSNP v1 snapshot transfers to an empty joining node" in {
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

    val prog = RuntimeClusterHarness
      .clusterResource(
        allIds = allIds,
        initialClusterFor = _ => threeVoterCfg,
        configFor = id =>
          RuntimeClusterHarness
            .fastConfig(id, compaction = smallChunkCompaction),
        autoStartIds = Some(initialIds.toSet)
      )
      .use { cluster =>
        for
          leaderPair <- cluster.awaitLeader(initialIds)
          (_, leader) = leaderPair

          // Ensure §4.1 current-term commit barrier is satisfied, then commit configuration adding n4 as Learner
          // while n4 is still stopped, and write 30 entries so the Configuration entry is compacted into the snapshot!
          _ <- leader.proposeAndCommit(utf8("barrier=ready"))
          _ <- leader.proposeConfChange(withLearnerCfg)
          _ <- (1 to 30).toList.traverse_ { i =>
            leader.proposeAndCommit(utf8(s"cfg-snap-$i=v$i"))
          }

          // Now start n4 (initially booted only knowing threeVoterCfg!):
          // n4 must install the RSNP v1 snapshot and adopt withLearnerCfg + all KV entries
          _ <- cluster.node(n4).start
          expected = Map("barrier" -> "ready") ++
            (1 to 30).map(i => s"cfg-snap-$i" -> s"v$i").toMap

          _ <- cluster.awaitCondition(
            for
              smMatch <- cluster.stateMachine(n4).currentMap.map(_ == expected)
              cfgMatch <- cluster
                .node(n4)
                .getClusterConfig
                .map(_ == withLearnerCfg)
            yield smMatch && cfgMatch,
            retries = 300
          )
        yield ()
      }

    prog.unsafeRunSync()
  }
