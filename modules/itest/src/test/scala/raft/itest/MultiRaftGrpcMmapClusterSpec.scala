package raft.itest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.impl.DefaultTimerService
import raft.multigroup.{
  CoalescedHeartbeatConfig,
  GroupConfig,
  MultiGroupTransport,
  MultiRaftNode
}
import raft.spi.{StateMachine, TransportConfig}
import raft.state.*
import raft.state.NodeState.*
import raft.storage.{
  StorageBackedLogStore,
  StorageBackedSnapshotStore,
  StorageBackedStableStore
}
import raft.storage.mmap.MmapWalEngine
import raft.transport.grpc.{GrpcConfig, GrpcTransport}

/** Full-stack Multi-Raft integration suite: 3 physical gRPC servers (1 HTTP/2
  * port per host) multiplexing 6 independent `MmapWalEngine`-backed Raft shards
  * (`18` WAL engines total) using native binary `ProtobufMultiGroupCodec`,
  * per-host `CoalescedHeartbeatConfig`, group-commit batching, pipelining, and
  * `rebalanceLeadership`.
  */
class MultiRaftGrpcMmapClusterSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val nodeIds = List(n1, n2, n3)
  private val shards = (0 until 6).map(i => GroupId(s"shard-$i")).toList

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  private def deleteRecursive(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val listing = Files.list(path)
        try listing.iterator().forEachRemaining(deleteRecursive)
        finally listing.close()
      val _ = Files.deleteIfExists(path)

  private final class ShardKvStateMachine(ref: Ref[IO, Map[String, String]])
      extends StateMachine[IO, Unit]:
    def apply(log: Log): IO[Unit] =
      val raw = new String(log.data, StandardCharsets.UTF_8)
      raw.split("=", 2) match
        case Array(k, v) => ref.update(_.updated(k, v))
        case _           => IO.unit

    def snapshot: IO[Stream[IO, Byte]] =
      ref.get.map { m =>
        val bytes = m.toSeq
          .sortBy(_._1)
          .map { case (k, v) => s"$k=$v" }
          .mkString("\n")
          .getBytes(StandardCharsets.UTF_8)
        Stream.chunk(Chunk.array(bytes)).covary[IO]
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

  private final case class PhysicalHost(
      id: NodeId,
      address: String,
      grpc: GrpcTransport[IO],
      mgTransport: MultiGroupTransport[IO],
      coordinator: MultiRaftNode[IO]
  )

  private def buildHost(id: NodeId): Resource[IO, PhysicalHost] =
    val grpcCfg = GrpcConfig(
      host = "127.0.0.1",
      port = 0,
      maxRetries = 1,
      retryBackoff = 5.millis,
      maxRetryBackoff = 10.millis,
      maxInboundQueueSize = 4096
    )
    val tCfg = TransportConfig(
      localId = id,
      bindAddress = "127.0.0.1:0",
      peers = Map.empty,
      connectionTimeout = 300.millis
    )
    val coalescing = CoalescedHeartbeatConfig(
      enabled = true,
      flushInterval = 12.millis,
      maxBatchSize = 32,
      coalesceResponses = true
    )
    for
      pair <- GrpcTransport.multiGroupResource[IO](
        grpcConfig = grpcCfg,
        transportConfig = tCfg,
        coalescing = coalescing,
        maxGroupQueueSize = 1024
      )
      (rawGrpc, mg) = pair
      addr <- Resource.eval(rawGrpc.localAddress)
      coord <- MultiRaftNode.resource[IO](id, mg)
    yield PhysicalHost(id, addr, rawGrpc, mg, coord)

  private def readIndexShardWithRetry(
      hosts: Map[NodeId, PhysicalHost],
      gid: GroupId,
      attempts: Int = 20
  ): IO[Long] =
    awaitShardLeader(hosts, gid).flatMap { leaderHost =>
      leaderHost.coordinator
        .readIndexFromGroup(gid)
        .timeout(4.seconds)
        .handleErrorWith {
          case (_: RaftError | _: java.util.concurrent.TimeoutException)
              if attempts > 1 =>
            IO.sleep(35.millis) *>
              readIndexShardWithRetry(hosts, gid, attempts - 1)
          case err => IO.raiseError(err)
        }
    }

  private def awaitRebalanced(
      hostList: List[PhysicalHost],
      hosts: Map[NodeId, PhysicalHost],
      preferred: Map[GroupId, NodeId],
      retries: Int = 450
  ): IO[Unit] =
    val trigger =
      if retries % 15 == 0 then
        hostList.traverse_(h => h.coordinator.rebalanceLeadership(preferred))
      else IO.unit
    trigger *>
      preferred.toList
        .forallM { case (gid, target) =>
          hosts(target).coordinator.getGroupState(gid).map {
            case Some(_: Leader) => true
            case _               => false
          }
        }
        .flatMap {
          case true  => IO.unit
          case false =>
            if retries <= 0 then
              IO.raiseError(
                new RuntimeException("Multi-Raft gRPC rebalance timed out")
              )
            else
              IO.sleep(25.millis) *>
                awaitRebalanced(hostList, hosts, preferred, retries - 1)
        }

  private def awaitShardLeader(
      hosts: Map[NodeId, PhysicalHost],
      gid: GroupId,
      retries: Int = 450
  ): IO[PhysicalHost] =
    nodeIds
      .traverse(id =>
        hosts(id).coordinator.getGroupState(gid).map(st => (hosts(id), st))
      )
      .flatMap { pairs =>
        val leaders = pairs.collect { case (h, Some(l: Leader)) =>
          (h, l.term)
        }
        leaders.sortBy(-_._2).headOption match
          case Some((h, _)) => IO.pure(h)
          case None         =>
            if retries <= 0 then
              IO.raiseError(
                new RuntimeException(s"No leader elected for ${gid.value}")
              )
            else
              IO.sleep(20.millis) *> awaitShardLeader(hosts, gid, retries - 1)
      }

  private def writeShardWithRetry(
      hosts: Map[NodeId, PhysicalHost],
      gid: GroupId,
      payload: Array[Byte],
      attempts: Int = 20
  ): IO[LogId] =
    awaitShardLeader(hosts, gid).flatMap { leaderHost =>
      leaderHost.coordinator
        .proposeAndCommitToGroup(gid, payload)
        .timeout(4.seconds)
        .handleErrorWith {
          case (_: RaftError | _: java.util.concurrent.TimeoutException)
              if attempts > 1 =>
            IO.sleep(35.millis) *>
              writeShardWithRetry(hosts, gid, payload, attempts - 1)
          case err => IO.raiseError(err)
        }
    }

  private def awaitCondition(
      cond: IO[Boolean],
      retries: Int = 450
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(
            new RuntimeException("Multi-Raft gRPC cluster condition timed out")
          )
        else IO.sleep(20.millis) *> awaitCondition(cond, retries - 1)
    }

  "Full-stack Multi-Raft (3 gRPC hosts x 6 MmapWalEngine shards)" should "multiplex all shards over 1 TCP port per host, coalesce heartbeats, rebalance shard leaders, and commit isolated writes" in {
    val baseDir = Files.createTempDirectory("raft-itest-multiraft-grpc-")
    val clusterCfg = ClusterConfig(nodeIds.toSet)

    val prog = (for
      // Allocate 18 MmapWalEngine instances FIRST so they outlive MultiRaftNode fibers during Resource teardown!
      enginesByKey <- shards
        .flatMap(gid => nodeIds.map(nid => (gid, nid)))
        .traverse { case (gid, nid) =>
          val shardDir = baseDir.resolve(nid.value).resolve(gid.value)
          MmapWalEngine
            .resource[IO](shardDir, segmentSize = 4096L)
            .map((gid, nid) -> _)
        }
        .map(_.toMap)

      hostList <- nodeIds.traverse(buildHost)
      hosts = hostList.map(h => h.id -> h).toMap
      peerAddrs = hostList.map(h => h.id -> h.address).toMap
      _ <- Resource.eval(
        hostList.traverse_(h => h.grpc.updatePeers(peerAddrs - h.id))
      )

      enginesAndSms <- Resource.eval {
        shards
          .flatMap(gid => nodeIds.map(nid => (gid, nid)))
          .traverse { case (gid, nid) =>
            val engine = enginesByKey((gid, nid))
            for
              sm <- Ref
                .of[IO, Map[String, String]](Map.empty)
                .map(new ShardKvStateMachine(_))
              timerSvc <- DefaultTimerService[IO]
              rc = RaftConfig(
                localId = nid,
                electionTimeoutMin = 450.millis,
                electionTimeoutMax = 900.millis,
                heartbeatInterval = 50.millis,
                batching = BatchConfig(
                  enabled = true,
                  maxSize = 16,
                  maxWait = 5.millis
                ),
                pipelining = PipelineConfig(enabled = true, maxInflight = 4)
              )
              _ <- hosts(nid).coordinator.createGroup(
                gid,
                GroupConfig(
                  raftConfig = rc,
                  cluster = clusterCfg,
                  logStore = StorageBackedLogStore(engine),
                  stableStore = StorageBackedStableStore(engine),
                  stateMachine = sm,
                  timerService = timerSvc,
                  snapshotStore = Some(StorageBackedSnapshotStore(engine))
                )
              )
            yield (gid, nid) -> sm
          }
          .map(_.toMap)
      }

      _ <- Resource.eval {
        for
          // 1) Initial barrier commit on all 6 shards & ensure followers caught up before rebalancing
          _ <- shards.parTraverse_ { gid =>
            writeShardWithRetry(hosts, gid, utf8(s"${gid.value}-boot=1"))
          }
          _ <- shards.traverse_ { gid =>
            awaitCondition(
              nodeIds.forallM { nid =>
                enginesAndSms((gid, nid)).currentMap.map(
                  _.get(s"${gid.value}-boot").contains("1")
                )
              }
            )
          }

          // 2) Rebalance leadership: 2 shard leaders on each physical server (n1, n2, n3)
          preferred = Map(
            GroupId("shard-0") -> n1,
            GroupId("shard-1") -> n1,
            GroupId("shard-2") -> n2,
            GroupId("shard-3") -> n2,
            GroupId("shard-4") -> n3,
            GroupId("shard-5") -> n3
          )
          _ <- awaitRebalanced(hostList, hosts, preferred)

          // 3) Hammer all 6 shards concurrently (10 group-committed writes per shard = 60 durable writes across 3 leaders!)
          _ <- shards.parTraverse_ { gid =>
            (1 to 10).toList.parTraverse_ { i =>
              writeShardWithRetry(
                hosts,
                gid,
                utf8(s"${gid.value}-k$i=val-$i")
              )
            }
          }

          // 4) Verify shard isolation + state convergence across all 18 replicas + linearizable readIndexFromGroup
          _ <- shards.traverse_ { gid =>
            val expected =
              Map(s"${gid.value}-boot" -> "1") ++
                (1 to 10).map(i => s"${gid.value}-k$i" -> s"val-$i")
            awaitCondition(
              nodeIds.forallM { nid =>
                enginesAndSms((gid, nid)).currentMap.map { m =>
                  m == expected && m.keys.forall(_.startsWith(gid.value))
                }
              }
            ) *>
              readIndexShardWithRetry(hosts, gid).map(_ should be >= 11L)
          }

          // 5) Confirm each physical server kept at most 2 outbound Netty channels (not 6 * 2 = 12!) and coalesced heartbeats!
          _ <- IO.sleep(80.millis)
          stats <- hostList.traverse(_.mgTransport.coalescerStats)
        yield {
          stats.map(_.heartbeatsQueued).sum should be > 10L
          stats
            .map(_.envelopesCoalesced)
            .sum should be > stats.map(_.batchesFlushed).sum
        }
      }
    yield ()).use_.guarantee(IO(deleteRecursive(baseDir)))

    prog.timeout(90.seconds).unsafeRunSync()
  }
