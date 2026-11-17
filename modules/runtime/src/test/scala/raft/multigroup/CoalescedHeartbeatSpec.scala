package raft.multigroup

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.codec.JsonCodec
import raft.impl.{
  DefaultTimerService,
  InMemLogStore,
  InMemSnapshotStore,
  InMemStableStore
}
import raft.integration.RuntimeClusterHarness.TestKvStateMachine
import raft.message.{CodecError, RaftMessage, SimpleMessageCodec}
import raft.message.RaftMessage.*
import raft.state.*
import raft.state.NodeState.*

/** Unit and multi-node integration tests for per-host Coalesced Heartbeats
  * (`CoalescedHeartbeatConfig` / `MultiGroupTransport`),
  * `BinaryGroupAwareCodec`, and extended `MultiRaftNode` operations
  * (`proposeAndCommitToGroup`, `proposeBatchAndCommitToGroup`,
  * `readIndexFromGroup`, `leaseReadFromGroup`, and `rebalanceLeadership`).
  */
class CoalescedHeartbeatSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val nodeIds = List(n1, n2, n3)

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  private val utf8JsonCodec: SimpleMessageCodec[Array[Byte]] =
    new SimpleMessageCodec[Array[Byte]]:
      def encode(msg: RaftMessage): Array[Byte] =
        JsonCodec.encode(msg).getBytes(StandardCharsets.UTF_8)
      def decode(raw: Array[Byte]): Either[CodecError, RaftMessage] =
        JsonCodec.decode(new String(raw, StandardCharsets.UTF_8))

  private def awaitShardLeader(
      coordinators: Map[NodeId, MultiRaftNode[IO]],
      groupId: GroupId,
      retries: Int = 400
  ): IO[(NodeId, MultiRaftNode[IO])] =
    nodeIds
      .traverse { id =>
        coordinators(id).getGroupState(groupId).map(stOpt => (id, stOpt))
      }
      .flatMap { pairs =>
        val leaders = pairs.collect { case (id, Some(l: Leader)) =>
          (id, l.term)
        }
        leaders.sortBy(-_._2).headOption match
          case Some((lid, _)) => IO.pure((lid, coordinators(lid)))
          case None           =>
            if retries <= 0 then
              IO.raiseError(
                new RuntimeException(s"No leader for ${groupId.value}")
              )
            else
              IO.sleep(15.millis) *>
                awaitShardLeader(coordinators, groupId, retries - 1)
      }

  private def awaitCondition(
      cond: IO[Boolean],
      retries: Int = 400
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(new RuntimeException("Condition timed out"))
        else IO.sleep(15.millis) *> awaitCondition(cond, retries - 1)
    }

  "CoalescedHeartbeatConfig" should "validate bounds and deduplicate / supersede per-group heartbeats" in {
    an[IllegalArgumentException] should be thrownBy CoalescedHeartbeatConfig(
      flushInterval = Duration.Zero
    )
    an[IllegalArgumentException] should be thrownBy CoalescedHeartbeatConfig(
      maxBatchSize = 0
    )

    val prog = for
      wireBatchesRef <- Ref.of[IO, Vector[Vector[GroupEnvelope]]](Vector.empty)
      mg <- MultiGroupTransport.withBatchSender[IO](
        localId = n1,
        sendBatchEnvelopes = batch => wireBatchesRef.update(_ :+ batch),
        coalescing = CoalescedHeartbeatConfig(
          enabled = true,
          flushInterval = 1.second,
          maxBatchSize = 10,
          coalesceResponses = true
        )
      )
      tp0 = mg.transportForGroup(GroupId("g0"))
      tp1 = mg.transportForGroup(GroupId("g1"))
      tp2 = mg.transportForGroup(GroupId("g2"))

      // 1) Queue two heartbeats for g0 (second has higher leaderCommit = 9) + one for g1 + one for g2 to n2
      _ <- tp0.send(
        n2,
        AppendEntriesRequest(1L, n1, 5L, 1L, Seq.empty, leaderCommit = 4L)
      )
      _ <- tp0.send(
        n2,
        AppendEntriesRequest(1L, n1, 5L, 1L, Seq.empty, leaderCommit = 9L)
      )
      _ <- tp1.send(
        n2,
        AppendEntriesRequest(2L, n1, 3L, 2L, Seq.empty, leaderCommit = 3L)
      )
      _ <- tp2.send(
        n2,
        AppendEntriesRequest(1L, n1, 0L, 0L, Seq.empty, leaderCommit = 0L)
      )

      // 2) Send a non-empty data AppendEntries on g1 -> MUST bypass coalescer immediately AND supersede g1's queued heartbeat!
      dataEntry = Log.command(4L, 2L, utf8("x=1"))
      _ <- tp1.send(
        n2,
        AppendEntriesRequest(2L, n1, 3L, 2L, Seq(dataEntry), leaderCommit = 3L)
      )

      batchesBeforeFlush <- wireBatchesRef.get
      _ = batchesBeforeFlush should have size 1
      _ = batchesBeforeFlush.head.map(_.groupId) shouldBe Vector(GroupId("g1"))

      // 3) Flush coalescer: only g0 (latest commit=9) and g2 remain, coalesced into 1 single wire batch!
      _ <- mg.flushCoalescedHeartbeats
      batchesAfterFlush <- wireBatchesRef.get
      stats <- mg.coalescerStats
    yield {
      batchesAfterFlush should have size 2
      val hbBatch = batchesAfterFlush(1)
      hbBatch.map(_.groupId) shouldBe Vector(GroupId("g0"), GroupId("g2"))
      hbBatch.head.message
        .asInstanceOf[AppendEntriesRequest]
        .leaderCommit shouldBe 9L
      stats.heartbeatsQueued shouldBe 4L
      stats.envelopesSuperseded shouldBe 1L
      stats.batchesFlushed shouldBe 1L
      stats.envelopesCoalesced shouldBe 2L
    }

    prog.unsafeRunSync()
  }

  it should "flush immediately when maxBatchSize is reached without waiting for flushInterval" in {
    val prog = for
      wireBatchesRef <- Ref.of[IO, Vector[Vector[GroupEnvelope]]](Vector.empty)
      mg <- MultiGroupTransport.withBatchSender[IO](
        localId = n1,
        sendBatchEnvelopes = batch => wireBatchesRef.update(_ :+ batch),
        coalescing = CoalescedHeartbeatConfig(
          enabled = true,
          flushInterval = 10.seconds,
          maxBatchSize = 3
        )
      )
      _ <- (0 until 3).toList.traverse_ { idx =>
        mg.transportForGroup(GroupId(s"g-$idx"))
          .send(n2, AppendEntriesRequest(1L, n1, 0L, 0L, Seq.empty, 0L))
      }
      flushed <- wireBatchesRef.get
    yield {
      flushed should have size 1
      flushed.head should have size 3
    }

    prog.unsafeRunSync()
  }

  "BinaryGroupAwareCodec" should "losslessly round-trip single and batched binary GroupEnvelopes and reject truncated input" in {
    val binCodec = GroupAwareCodec.binary(utf8JsonCodec)
    val env1 = GroupEnvelope(
      GroupId("shard-0"),
      n1,
      AppendEntriesRequest(
        3L,
        n1,
        10L,
        3L,
        Seq(Log.command(11L, 3L, Array[Byte](0, -1, 42))),
        10L
      ),
      Some(n2)
    )
    val env2 = GroupEnvelope(
      GroupId("shard-1"),
      n2,
      RequestVoteRequest(4L, n2, 8L, 3L, isPreVote = true, isTransfer = false),
      None
    )

    val singleBytes = binCodec.encode(env1)
    binCodec.decode(singleBytes) shouldBe Right(env1)

    val batchBytes = binCodec.encodeBatch(Vector(env1, env2))
    binCodec.decodeBatch(batchBytes) shouldBe Right(Vector(env1, env2))
    binCodec.decode(batchBytes).isLeft shouldBe true

    binCodec.decodeBatch(Array.emptyByteArray).isLeft shouldBe true
    binCodec.decodeBatch(Array[Byte](0x00, 0x01)).isLeft shouldBe true
    binCodec.decodeBatch(batchBytes.take(7)).isLeft shouldBe true
  }

  "MultiRaftNode with CoalescedHeartbeats (3 hosts x 6 shards)" should "coalesce cross-group heartbeats, serve group writes/reads, and rebalance shard leadership evenly" in {
    val shards = (0 until 6).map(i => GroupId(s"shard-$i")).toList
    val clusterCfg = ClusterConfig(nodeIds.toSet)
    val coalescingCfg = CoalescedHeartbeatConfig(
      enabled = true,
      flushInterval = 12.millis,
      maxBatchSize = 16,
      coalesceResponses = true
    )

    val prog = MultiGroupTransport
      .createInMemoryCluster[IO](
        nodeIds = nodeIds,
        coalescing = coalescingCfg
      )
      .use { transports =>
        nodeIds
          .traverse(id =>
            MultiRaftNode.resource[IO](id, transports(id)).map(id -> _)
          )
          .map(_.toMap)
          .use { coordinators =>
            for
              smsRef <- Ref.of[IO, Map[(GroupId, NodeId), TestKvStateMachine]](
                Map.empty
              )
              _ <- shards.traverse_ { gid =>
                nodeIds.traverse_ { nid =>
                  for
                    timerSvc <- DefaultTimerService[IO]
                    logStore <- InMemLogStore[IO]()
                    stableStore <- InMemStableStore[IO]()
                    snapStore <- InMemSnapshotStore[IO]()
                    sm <- TestKvStateMachine.create
                    _ <- smsRef.update(_.updated((gid, nid), sm))
                    rc = RaftConfig(
                      localId = nid,
                      electionTimeoutMin = 300.millis,
                      electionTimeoutMax = 600.millis,
                      heartbeatInterval = 35.millis,
                      leaseReadEnabled = true,
                      leaderLeaseDuration = 200.millis
                    )
                    _ <- coordinators(nid).createGroup(
                      gid,
                      GroupConfig(
                        raftConfig = rc,
                        cluster = clusterCfg,
                        logStore = logStore,
                        stableStore = stableStore,
                        stateMachine = sm,
                        timerService = timerSvc,
                        snapshotStore = Some(snapStore)
                      )
                    )
                  yield ()
                }
              }

              // Wait for all 6 shards to elect leaders and commit initial writes + batch writes
              _ <- shards.parTraverse_ { gid =>
                for
                  pair <- awaitShardLeader(coordinators, gid)
                  (_, leaderCoord) = pair
                  _ <- leaderCoord.proposeAndCommitToGroup(
                    gid,
                    utf8(s"${gid.value}-k0=v0")
                  )
                  batchIds <- leaderCoord.proposeBatchAndCommitToGroup(
                    gid,
                    Seq(
                      utf8(s"${gid.value}-k1=v1"),
                      utf8(s"${gid.value}-k2=v2")
                    )
                  )
                  _ = batchIds should have size 2
                  rIdx <- leaderCoord.readIndexFromGroup(gid)
                  _ = rIdx should be >= batchIds.last.index
                yield ()
              }

              // Rebalance 6 shards evenly across the 3 physical hosts (2 leaders per host!)
              preferred = Map(
                GroupId("shard-0") -> n1,
                GroupId("shard-1") -> n1,
                GroupId("shard-2") -> n2,
                GroupId("shard-3") -> n2,
                GroupId("shard-4") -> n3,
                GroupId("shard-5") -> n3
              )
              _ <- nodeIds.traverse_(id =>
                coordinators(id).rebalanceLeadership(preferred)
              )
              _ <- awaitCondition {
                shards.forallM { gid =>
                  val target = preferred(gid)
                  coordinators(target).getGroupState(gid).map {
                    case Some(_: Leader) => true
                    case _               => false
                  }
                }
              }

              // Let a few heartbeat windows elapse and verify coalescing compressed wire batches!
              _ <- IO.sleep(90.millis)
              allStats <- nodeIds.traverse(id => transports(id).coalescerStats)
            yield {
              val totalQueued = allStats.map(_.heartbeatsQueued).sum
              val totalBatches = allStats.map(_.batchesFlushed).sum
              val totalCoalesced = allStats.map(_.envelopesCoalesced).sum
              totalQueued should be > 10L
              totalCoalesced should be > totalBatches
            }
          }
      }

    prog.timeout(40.seconds).unsafeRunSync()
  }
