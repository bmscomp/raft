package raft.itest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.sys.process.{Process, ProcessLogger}

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.RaftNode
import raft.impl.DefaultTimerService
import raft.message.RaftMessage.AppendEntriesRequest
import raft.multigroup.{
  CoalescedHeartbeatConfig,
  GroupConfig,
  MultiGroupTransport,
  MultiRaftNode
}
import raft.spi.{CompactionPolicy, StateMachine, TransportConfig}
import raft.state.*
import raft.state.NodeState.*
import raft.storage.{
  StorageBackedLogStore,
  StorageBackedSnapshotStore,
  StorageBackedStableStore
}
import raft.storage.mmap.MmapWalEngine
import raft.transport.grpc.{GrpcConfig, GrpcTransport}

/** Advanced End-to-End Integration Suite over live Netty HTTP/2
  * [[GrpcTransport]] and JDK FFM [[MmapWalEngine]] covering:
  *   1. Mixed-Role `Voter + Witness + Learner` cluster with Witness payload
  *      stripping on disk, Voter crash quorum continuity, and Learner promotion
  *      via Joint Consensus.
  *   2. Active Leader Self-Removal via Joint Consensus (`3 -> 3` replacement)
  *      with automatic leader step-down and failover.
  *   3. Multi-Raft physical host crash-recovery across 3 multiplexed
  *      `MmapWalEngine`-backed shards on a single TCP port per host.
  *   4. Live 3-node mTLS cluster peer identity anti-spoofing
  *      (`verifyPeerIdentity = true`) rejecting a CA-signed peer attempting to
  *      impersonate another `NodeId`.
  */
class AdvancedTopologyAndRecoveryE2ESpec extends AnyFlatSpec with Matchers:

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  private def deleteRecursive(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val s = Files.list(path)
        try s.iterator().forEachRemaining(deleteRecursive)
        finally s.close()
      val _ = Files.deleteIfExists(path)

  private final class KvStateMachine(ref: Ref[IO, Map[String, String]])
      extends StateMachine[IO, String]:
    def apply(log: Log): IO[String] =
      if log.data.isEmpty then IO.pure("EMPTY")
      else
        val raw = new String(log.data, StandardCharsets.UTF_8)
        raw.split("=", 2) match
          case Array(k, v) => ref.update(_.updated(k, v)).as(s"SET:$k=$v")
          case _           => IO.pure("IGNORED")

    def snapshot: IO[Stream[IO, Byte]] =
      ref.get.map { m =>
        val bytes = m.toSeq
          .sortBy(_._1)
          .map((k, v) => s"$k=$v")
          .mkString("\n")
          .getBytes(StandardCharsets.UTF_8)
        Stream.chunk(Chunk.array(bytes)).covary[IO]
      }

    def restore(data: Stream[IO, Byte]): IO[Unit] =
      data.compile.to(Array).flatMap { bytes =>
        val text = new String(bytes, StandardCharsets.UTF_8)
        val parsed =
          if text.isEmpty then Map.empty[String, String]
          else
            text
              .split("\n")
              .toList
              .flatMap(_.split("=", 2) match
                case Array(k, v) if k.nonEmpty => Some(k -> v)
                case _                         => None)
              .toMap
        ref.set(parsed)
      }

    def getAll: IO[Map[String, String]] = ref.get

  private object KvStateMachine:
    def create: IO[KvStateMachine] =
      Ref.of[IO, Map[String, String]](Map.empty).map(new KvStateMachine(_))

  private final case class LiveNode(
      id: NodeId,
      dataDir: Path,
      port: Int,
      address: String,
      engine: MmapWalEngine[IO],
      grpc: GrpcTransport[IO],
      raftNode: RaftNode[IO, String],
      sm: KvStateMachine
  )

  private def buildNode(
      id: NodeId,
      dataDir: Path,
      port: Int,
      initialPeers: Map[NodeId, String],
      clusterCfg: ClusterConfig,
      grpcOverride: Option[GrpcConfig] = None,
      compactionThreshold: Long = 100L,
      autoStart: Boolean = false
  ): Resource[IO, LiveNode] =
    val grpcCfg = grpcOverride.getOrElse(
      GrpcConfig(
        host = "127.0.0.1",
        port = port,
        maxRetries = 1,
        retryBackoff = 5.millis,
        maxRetryBackoff = 15.millis,
        maxInboundQueueSize = 2048
      )
    )
    val tCfg = TransportConfig(
      localId = id,
      bindAddress = s"127.0.0.1:$port",
      peers = initialPeers - id,
      connectionTimeout = 300.millis
    )
    val compaction = CompactionConfig(
      level = CompactionLevel.Custom,
      snapshotChunkSize = 256,
      retainedEntries = 0L,
      policy = Some(CompactionPolicy.threshold[IO](compactionThreshold))
    )
    for
      engine <- MmapWalEngine.resource[IO](dataDir, segmentSize = 4096L)
      grpcPair <- GrpcTransport.raftResource[IO](grpcCfg, tCfg)
      (rawGrpc, raftTransport) = grpcPair
      boundAddr <- Resource.eval(rawGrpc.localAddress)
      boundPort = boundAddr.split(":").last.toInt
      timer <- Resource.eval(DefaultTimerService[IO])
      sm <- Resource.eval(KvStateMachine.create)
      rn <- RaftNode[IO](
        config = RaftConfig(
          localId = id,
          electionTimeoutMin = 220.millis,
          electionTimeoutMax = 440.millis,
          heartbeatInterval = 35.millis,
          preVoteEnabled = true,
          checkQuorumEnabled = true,
          leaseReadEnabled = true,
          leaderLeaseDuration = 180.millis,
          batching = BatchConfig(
            enabled = true,
            maxSize = 32,
            maxWait = 4.millis
          ),
          pipelining = PipelineConfig(enabled = true, maxInflight = 6),
          compaction = compaction
        ),
        cluster = clusterCfg,
        transport = raftTransport,
        logStore = StorageBackedLogStore(engine),
        stableStore = StorageBackedStableStore(engine),
        stateMachine = sm,
        timerService = timer,
        snapshotStoreOpt = Some(StorageBackedSnapshotStore(engine))
      )
      _ <- Resource.eval(if autoStart then rn.start else IO.unit)
    yield LiveNode(
      id = id,
      dataDir = dataDir,
      port = boundPort,
      address = boundAddr,
      engine = engine,
      grpc = rawGrpc,
      raftNode = rn,
      sm = sm
    )

  private def awaitCondition(
      cond: IO[Boolean],
      retries: Int = 450,
      delay: FiniteDuration = 20.millis
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(
            new RuntimeException("Advanced E2E condition timed out")
          )
        else IO.sleep(delay) *> awaitCondition(cond, retries - 1, delay)
    }

  private def awaitLeader(
      nodes: Iterable[LiveNode],
      retries: Int = 450
  ): IO[LiveNode] =
    nodes.toList
      .traverse(n => n.raftNode.getState.map(st => (n, st)))
      .flatMap { pairs =>
        pairs
          .collect { case (n, l: Leader) => (n, l.term) }
          .sortBy(-_._2)
          .headOption match
          case Some((n, _)) => IO.pure(n)
          case None         =>
            if retries <= 0 then
              IO.raiseError(new RuntimeException("No leader elected"))
            else IO.sleep(20.millis) *> awaitLeader(nodes, retries - 1)
      }

  private def proposeWithRetry(
      nodes: Iterable[LiveNode],
      key: String,
      value: String,
      attempts: Int = 25
  ): IO[Applied[String]] =
    awaitLeader(nodes).flatMap { ldr =>
      ldr.raftNode
        .proposeAndApply(Bytes.fromUtf8(s"$key=$value"))
        .timeout(4.seconds)
        .handleErrorWith {
          case _ if attempts > 1 =>
            IO.sleep(35.millis) *>
              proposeWithRetry(nodes, key, value, attempts - 1)
          case err => IO.raiseError(err)
        }
    }

  private def reconfigWithRetry(
      nodes: Iterable[LiveNode],
      targetCfg: ClusterConfig,
      attempts: Int = 25
  ): IO[Unit] =
    awaitLeader(nodes).flatMap { ldr =>
      ldr.raftNode.getClusterConfig.flatMap { cur =>
        if !cur.isInJointConsensus && cur == targetCfg then IO.unit
        else
          ldr.raftNode
            .proposeConfChange(targetCfg)
            .void
            .timeout(5.seconds)
            .handleErrorWith {
              case _ if attempts > 1 =>
                IO.sleep(45.millis) *>
                  reconfigWithRetry(nodes, targetCfg, attempts - 1)
              case err => IO.raiseError(err)
            }
      }
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Scenario 1: Mixed-Role Voter + Witness + Learner over Live gRPC + MmapWAL
  // ─────────────────────────────────────────────────────────────────────────

  "Advanced Topology & Recovery E2E Suite" should
    "run Voter + Witness + Learner over live gRPC + MmapWalEngine, strip Witness payloads on disk, survive Voter crash, and promote Learner" in {
      val baseDir = Files.createTempDirectory("raft-e2e-mixed-roles-")
      val v1 = NodeId("v1")
      val v2 = NodeId("v2")
      val w1 = NodeId("w1")
      val l1 = NodeId("l1")

      val initialCfg = ClusterConfig(
        Map(
          v1 -> NodeRole.Voter,
          v2 -> NodeRole.Voter,
          w1 -> NodeRole.Witness
        )
      )
      val cfgWithLearner = initialCfg.addMember(l1, NodeRole.Learner)
      val cfgPromoted = cfgWithLearner.promoteLearner(l1)

      val prog = Ref
        .of[IO, Map[NodeId, (LiveNode, IO[Unit])]](Map.empty)
        .flatMap { allocRef =>
          def live(ids: List[NodeId]): IO[List[LiveNode]] =
            allocRef.get.map(m => ids.flatMap(id => m.get(id).map(_._1)))

          val scenario = for
            // 1. Boot {v1: Voter, v2: Voter, w1: Witness}
            _ <- List(v1, v2, w1).traverse_ { id =>
              buildNode(
                id = id,
                dataDir = baseDir.resolve(id.value),
                port = 0,
                initialPeers = Map.empty,
                clusterCfg = initialCfg,
                compactionThreshold = 500L
              ).allocated.flatMap(p => allocRef.update(_.updated(id, p)))
            }
            n3 <- live(List(v1, v2, w1))
            addrs3 = n3.map(n => n.id -> n.address).toMap
            _ <- n3.traverse_(n => n.grpc.updatePeers(addrs3 - n.id))
            _ <- n3.traverse_(_.raftNode.start)

            // Commit k1..k8 -> leader must be v1 or v2 (never Witness w1!)
            _ <- (1 to 8).toList.traverse_ { i =>
              proposeWithRetry(n3, s"mr-$i", s"val-$i").map { app =>
                app.value shouldBe s"SET:mr-$i=val-$i"
              }
            }
            leader1 <- awaitLeader(n3)
            _ = leader1.id should not be w1

            expected8 = (1 to 8).map(i => s"mr-$i" -> s"val-$i").toMap
            _ <- awaitCondition(
              List(v1, v2).forallM { id =>
                allocRef.get
                  .flatMap(m => m(id)._1.sm.getAll.map(_ == expected8))
              }
            )

            // Verify Witness w1 persisted entries on MmapWalEngine with 0-byte Command payloads!
            witnessNode <- allocRef.get.map(_(w1)._1)
            _ <- awaitCondition(
              witnessNode.engine.lastLogIndex.map(_.value >= 8L)
            )
            wLast <- witnessNode.engine.lastLogIndex
            wLogs <- witnessNode.engine.readRange(
              LogIndex.unsafeFrom(1L),
              wLast
            )
            wCmds = wLogs.filter(_.logType == LogType.Command)
            _ = wCmds should not be empty
            _ = wCmds.forall(_.payload.isEmpty) shouldBe true
            wKv <- witnessNode.sm.getAll
            _ = wKv shouldBe empty

            // 2. Crash the follower Voter while {leader1, w1} maintains 2/3 quorum!
            crashedVoterId = if leader1.id == v1 then v2 else v1
            crashedPair <- allocRef
              .modify(m => (m - crashedVoterId, m(crashedVoterId)))
            crashedPort = crashedPair._1.port
            crashedDir = crashedPair._1.dataDir
            _ <- crashedPair._2

            quorum2 <- live(List(leader1.id, w1))
            _ <- (9 to 12).toList.traverse_ { i =>
              proposeWithRetry(quorum2, s"mr-$i", s"quorum-witness-$i")
            }

            // 3. Restart crashed Voter + boot Learner l1, add Learner, then promote Learner -> Voter!
            rebornVoter <- buildNode(
              id = crashedVoterId,
              dataDir = crashedDir,
              port = crashedPort,
              initialPeers = addrs3,
              clusterCfg = initialCfg,
              compactionThreshold = 500L,
              autoStart = true
            ).allocated
            _ <- allocRef.update(_.updated(crashedVoterId, rebornVoter))

            l1Pair <- buildNode(
              id = l1,
              dataDir = baseDir.resolve(l1.value),
              port = 0,
              initialPeers = addrs3,
              clusterCfg = cfgWithLearner,
              compactionThreshold = 500L,
              autoStart = false
            ).allocated
            _ <- allocRef.update(_.updated(l1, l1Pair))

            n4 <- live(List(v1, v2, w1, l1))
            addrs4 = n4.map(n => n.id -> n.address).toMap
            _ <- n4.traverse_(n => n.grpc.updatePeers(addrs4 - n.id))
            _ <- l1Pair._1.raftNode.start

            _ <- reconfigWithRetry(n4, cfgWithLearner)
            _ <- proposeWithRetry(n4, "mr-13", "learner-staged")
            _ <- awaitCondition(
              l1Pair._1.sm.getAll.map(_.get("mr-13").contains("learner-staged"))
            )

            // Promote l1 -> Voter via Joint Consensus!
            _ <- reconfigWithRetry(n4, cfgPromoted)
            _ <- (14 to 16).toList.traverse_ { i =>
              proposeWithRetry(n4, s"mr-$i", s"promoted-$i")
            }

            expectedAll =
              expected8 ++
                (9 to 12).map(i => s"mr-$i" -> s"quorum-witness-$i") ++
                Map("mr-13" -> "learner-staged") ++
                (14 to 16).map(i => s"mr-$i" -> s"promoted-$i")

            // All 3 full data replicas (v1, v2, promoted l1) converge identically!
            dataReplicas <- live(List(v1, v2, l1))
            _ <- awaitCondition(
              dataReplicas.forallM(_.sm.getAll.map(_ == expectedAll))
            )

            // Witness w1 still never holds Command payload bytes on disk!
            wLastFinal <- witnessNode.engine.lastLogIndex
            wLogsFinal <- witnessNode.engine.readRange(
              LogIndex.unsafeFrom(1L),
              wLastFinal
            )
            wCmdsFinal = wLogsFinal.filter(_.logType == LogType.Command)
            _ = wCmdsFinal.size should be >= 16
            _ = wCmdsFinal.forall(_.payload.isEmpty) shouldBe true
          yield ()

          scenario.guarantee(
            allocRef.get
              .flatMap(_.values.toList.traverse_(_._2.attempt.void))
              .guarantee(IO(deleteRecursive(baseDir)))
          )
        }

      prog.timeout(90.seconds).unsafeRunSync()
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Scenario 2: Active Leader Self-Removal via Joint Consensus over gRPC
  // ─────────────────────────────────────────────────────────────────────────

  it should "safely execute active leader self-removal via Joint Consensus over live gRPC + MmapWalEngine and elect a failover leader" in {
    val baseDir = Files.createTempDirectory("raft-e2e-self-removal-")
    val n1 = NodeId("sr-1")
    val n2 = NodeId("sr-2")
    val n3 = NodeId("sr-3")
    val n4 = NodeId("sr-4")
    val initIds = List(n1, n2, n3)
    val initCfg = ClusterConfig(initIds.toSet)

    val prog = Ref
      .of[IO, Map[NodeId, (LiveNode, IO[Unit])]](Map.empty)
      .flatMap { allocRef =>
        def live(ids: List[NodeId]): IO[List[LiveNode]] =
          allocRef.get.map(m => ids.flatMap(id => m.get(id).map(_._1)))

        val scenario = for
          _ <- initIds.traverse_ { id =>
            buildNode(
              id = id,
              dataDir = baseDir.resolve(id.value),
              port = 0,
              initialPeers = Map.empty,
              clusterCfg = initCfg
            ).allocated.flatMap(p => allocRef.update(_.updated(id, p)))
          }
          nodes3 <- live(initIds)
          addrs3 = nodes3.map(n => n.id -> n.address).toMap
          _ <- nodes3.traverse_(n => n.grpc.updatePeers(addrs3 - n.id))
          _ <- nodes3.traverse_(_.raftNode.start)

          _ <- (1 to 6).toList.traverse_ { i =>
            proposeWithRetry(nodes3, s"sr-$i", s"before-$i")
          }
          oldLeader <- awaitLeader(nodes3)
          oldTerm <- oldLeader.raftNode.getState.map(_.term)

          // Build replacement 3-voter config that EXCLUDES oldLeader and INCLUDES n4!
          replacementIds = (initIds.filterNot(_ == oldLeader.id)) :+ n4
          replacementCfg = ClusterConfig(replacementIds.toSet)

          n4Pair <- buildNode(
            id = n4,
            dataDir = baseDir.resolve(n4.value),
            port = 0,
            initialPeers = addrs3,
            clusterCfg = replacementCfg,
            autoStart = false
          ).allocated
          _ <- allocRef.update(_.updated(n4, n4Pair))

          all4 <- live(initIds :+ n4)
          addrs4 = all4.map(n => n.id -> n.address).toMap
          _ <- all4.traverse_(n => n.grpc.updatePeers(addrs4 - n.id))
          _ <- n4Pair._1.raftNode.start

          // Trigger Joint Consensus self-removal on oldLeader!
          _ <- reconfigWithRetry(all4, replacementCfg)

          // oldLeader MUST step down automatically once C_new commits!
          _ <- awaitCondition(
            oldLeader.raftNode.getState.map(!_.isInstanceOf[Leader]),
            retries = 350
          )

          // Shut down decommissioned oldLeader and verify surviving 3 voters elect a new leader & commit!
          removedPair <- allocRef
            .modify(m => (m - oldLeader.id, m(oldLeader.id)))
          _ <- removedPair._2

          survivors <- live(replacementIds)
          addrsFinal = survivors.map(n => n.id -> n.address).toMap
          _ <- survivors.traverse_(n => n.grpc.updatePeers(addrsFinal - n.id))

          newLeader <- awaitLeader(survivors)
          newTerm <- newLeader.raftNode.getState.map(_.term)
          _ = newLeader.id should not be oldLeader.id
          _ = newTerm should be >= oldTerm

          _ <- (7 to 14).toList.traverse_ { i =>
            proposeWithRetry(survivors, s"sr-$i", s"after-$i")
          }
          expected =
            (1 to 6).map(i => s"sr-$i" -> s"before-$i").toMap ++
              (7 to 14).map(i => s"sr-$i" -> s"after-$i").toMap

          _ <- awaitCondition(
            survivors.forallM(_.sm.getAll.map(_ == expected))
          )
          rIdx <- newLeader.raftNode.readIndex.timeout(4.seconds)
          _ = rIdx should be >= 14L
        yield ()

        scenario.guarantee(
          allocRef.get
            .flatMap(_.values.toList.traverse_(_._2.attempt.void))
            .guarantee(IO(deleteRecursive(baseDir)))
        )
      }

    prog.timeout(90.seconds).unsafeRunSync()
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Scenario 3: Multi-Raft Physical Host Crash-Recovery over Live gRPC + WAL
  // ─────────────────────────────────────────────────────────────────────────

  private final case class MultiHost(
      id: NodeId,
      port: Int,
      address: String,
      grpc: GrpcTransport[IO],
      mgTransport: MultiGroupTransport[IO],
      coordinator: MultiRaftNode[IO],
      sms: Map[GroupId, KvStateMachine]
  )

  private def buildMultiHost(
      id: NodeId,
      baseDir: Path,
      port: Int,
      peers: Map[NodeId, String],
      shards: List[GroupId],
      clusterCfg: ClusterConfig
  ): Resource[IO, MultiHost] =
    val grpcCfg = GrpcConfig(
      host = "127.0.0.1",
      port = port,
      maxRetries = 1,
      retryBackoff = 5.millis,
      maxRetryBackoff = 15.millis,
      maxInboundQueueSize = 4096
    )
    val tCfg = TransportConfig(
      localId = id,
      bindAddress = s"127.0.0.1:$port",
      peers = peers - id,
      connectionTimeout = 300.millis
    )
    val coalescing = CoalescedHeartbeatConfig(
      enabled = true,
      flushInterval = 10.millis,
      maxBatchSize = 16,
      coalesceResponses = true
    )
    for
      engines <- shards
        .traverse { gid =>
          MmapWalEngine
            .resource[IO](
              baseDir.resolve(id.value).resolve(gid.value),
              segmentSize = 4096L
            )
            .map(gid -> _)
        }
        .map(_.toMap)
      pair <- GrpcTransport.multiGroupResource[IO](
        grpcConfig = grpcCfg,
        transportConfig = tCfg,
        coalescing = coalescing,
        maxGroupQueueSize = 1024
      )
      (rawGrpc, mg) = pair
      addr <- Resource.eval(rawGrpc.localAddress)
      boundPort = addr.split(":").last.toInt
      coord <- MultiRaftNode.resource[IO](id, mg)
      sms <- Resource.eval {
        shards
          .traverse { gid =>
            val engine = engines(gid)
            for
              sm <- KvStateMachine.create
              timer <- DefaultTimerService[IO]
              rc = RaftConfig(
                localId = id,
                electionTimeoutMin = 260.millis,
                electionTimeoutMax = 520.millis,
                heartbeatInterval = 40.millis,
                leaseReadEnabled = true,
                leaderLeaseDuration = 180.millis,
                batching =
                  BatchConfig(enabled = true, maxSize = 16, maxWait = 4.millis),
                pipelining = PipelineConfig(enabled = true, maxInflight = 4)
              )
              _ <- coord.createGroup(
                gid,
                GroupConfig(
                  raftConfig = rc,
                  cluster = clusterCfg,
                  logStore = StorageBackedLogStore(engine),
                  stableStore = StorageBackedStableStore(engine),
                  stateMachine = sm,
                  timerService = timer,
                  snapshotStore = Some(StorageBackedSnapshotStore(engine))
                )
              )
            yield gid -> sm
          }
          .map(_.toMap)
      }
    yield MultiHost(id, boundPort, addr, rawGrpc, mg, coord, sms)

  it should "survive physical host crash-recovery across 3 multiplexed MmapWalEngine shards on 1 TCP port per host" in {
    val baseDir = Files.createTempDirectory("raft-e2e-multiraft-crash-")
    val h1 = NodeId("h1")
    val h2 = NodeId("h2")
    val h3 = NodeId("h3")
    val hostIds = List(h1, h2, h3)
    val shards = List(GroupId("s0"), GroupId("s1"), GroupId("s2"))
    val clusterCfg = ClusterConfig(hostIds.toSet)

    def awaitShardLeader(
        hosts: List[MultiHost],
        gid: GroupId,
        retries: Int = 450
    ): IO[MultiHost] =
      hosts
        .traverse(h => h.coordinator.getGroupState(gid).map(st => (h, st)))
        .flatMap { pairs =>
          pairs
            .collect { case (h, Some(l: Leader)) => (h, l.term) }
            .sortBy(-_._2)
            .headOption match
            case Some((h, _)) => IO.pure(h)
            case None         =>
              if retries <= 0 then
                IO.raiseError(
                  new RuntimeException(s"No leader for shard ${gid.value}")
                )
              else
                IO.sleep(20.millis) *> awaitShardLeader(hosts, gid, retries - 1)
        }

    def writeBatchShard(
        hosts: List[MultiHost],
        gid: GroupId,
        kvs: Seq[(String, String)],
        attempts: Int = 25
    ): IO[Unit] =
      awaitShardLeader(hosts, gid).flatMap { ldr =>
        ldr.coordinator
          .proposeBatchAndCommitToGroup(gid, kvs.map((k, v) => utf8(s"$k=$v")))
          .void
          .timeout(5.seconds)
          .handleErrorWith {
            case _ if attempts > 1 =>
              IO.sleep(35.millis) *>
                writeBatchShard(hosts, gid, kvs, attempts - 1)
            case err => IO.raiseError(err)
          }
      }

    val prog = Ref
      .of[IO, Map[NodeId, (MultiHost, IO[Unit])]](Map.empty)
      .flatMap { hostsRef =>
        def liveHosts: IO[List[MultiHost]] =
          hostsRef.get.map(_.values.map(_._1).toList)

        val scenario = for
          // 1. Boot 3 physical hosts (9 MmapWalEngine shards total)
          _ <- hostIds.traverse_ { id =>
            buildMultiHost(
              id,
              baseDir,
              0,
              Map.empty,
              shards,
              clusterCfg
            ).allocated
              .flatMap(p => hostsRef.update(_.updated(id, p)))
          }
          initHosts <- liveHosts
          addrs = initHosts.map(h => h.id -> h.address).toMap
          _ <- initHosts.traverse_(h => h.grpc.updatePeers(addrs - h.id))

          // 2. Commit 8 entries per shard using proposeBatchAndCommitToGroup + proposeAndApplyToGroup
          _ <- shards.parTraverse_ { gid =>
            writeBatchShard(
              initHosts,
              gid,
              (1 to 7).map(i => s"${gid.value}-k$i" -> s"before-$i")
            ) *>
              awaitShardLeader(initHosts, gid).flatMap { ldr =>
                ldr.coordinator
                  .proposeAndApplyToGroup(
                    gid,
                    Bytes.fromUtf8(s"${gid.value}-k8=applied-8")
                  )
                  .map(_.value shouldBe s"SET:${gid.value}-k8=applied-8")
              }
          }
          _ <- shards.traverse_ { gid =>
            awaitCondition(
              initHosts.forallM(_.sms(gid).getAll.map(_.size == 8))
            )
          }

          // 3. Crash physical host h3 (tears down its MultiRaftNode, gRPC server, and all 3 WAL engines!)
          h3Saved <- hostsRef.modify(m => (m - h3, m(h3)))
          h3Port = h3Saved._1.port
          _ <- h3Saved._2

          // 4. Surviving {h1, h2} maintain 2/3 quorum and commit 6 more writes per shard!
          survivors <- liveHosts
          _ <- shards.parTraverse_ { gid =>
            writeBatchShard(
              survivors,
              gid,
              (9 to 14).map(i => s"${gid.value}-k$i" -> s"during-$i")
            )
          }

          // 5. Reboot h3 on its SAME TCP port and EXISTING shard WAL directories!
          h3Reborn <- buildMultiHost(
            h3,
            baseDir,
            h3Port,
            addrs,
            shards,
            clusterCfg
          ).allocated
          _ <- hostsRef.update(_.updated(h3, h3Reborn))

          allHosts <- liveHosts
          _ <- shards.traverse_ { gid =>
            val expected =
              (1 to 7).map(i => s"${gid.value}-k$i" -> s"before-$i").toMap +
                (s"${gid.value}-k8" -> "applied-8") ++
                (9 to 14).map(i => s"${gid.value}-k$i" -> s"during-$i")
            awaitCondition(
              allHosts.forallM { h =>
                h.sms(gid).getAll.map { m =>
                  m == expected && m.keys.forall(_.startsWith(gid.value))
                }
              },
              retries = 450
            ) *>
              awaitShardLeader(allHosts, gid).flatMap { ldr =>
                for
                  rIdx <- ldr.coordinator
                    .readIndexFromGroup(gid)
                    .timeout(4.seconds)
                  _ = rIdx should be >= 14L
                  _ <- ldr.coordinator.leaseReadFromGroup(gid)
                yield ()
              }
          }
        yield ()

        scenario.guarantee(
          hostsRef.get
            .flatMap(_.values.toList.traverse_(_._2.attempt.void))
            .guarantee(IO(deleteRecursive(baseDir)))
        )
      }

    prog.timeout(90.seconds).unsafeRunSync()
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Scenario 4: Live 3-Node mTLS Peer Identity Anti-Spoofing (CA-Signed Peer)
  // ─────────────────────────────────────────────────────────────────────────

  private def generateSpiffePki(
      pkiDir: Path,
      ids: List[NodeId]
  ): (Path, Map[NodeId, (Path, Path)]) =
    def runCmd(args: Seq[String]): Unit =
      val log = ProcessLogger(_ => (), _ => ())
      val code = Process(args, pkiDir.toFile).!(log)
      require(code == 0, s"openssl failed ($code): ${args.mkString(" ")}")

    val caKey = pkiDir.resolve("ca.key")
    val caCrt = pkiDir.resolve("ca.crt")
    runCmd(
      Seq(
        "openssl",
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        caKey.toString,
        "-out",
        caCrt.toString,
        "-days",
        "2",
        "-subj",
        "/CN=StrictIdentityClusterCA"
      )
    )

    val certs = ids.map { id =>
      val rawKey = pkiDir.resolve(s"${id.value}-raw.key")
      val key = pkiDir.resolve(s"${id.value}.key")
      val csr = pkiDir.resolve(s"${id.value}.csr")
      val crt = pkiDir.resolve(s"${id.value}.crt")
      val ext = pkiDir.resolve(s"${id.value}.ext")
      val _ = Files.writeString(
        ext,
        s"subjectAltName=IP:127.0.0.1,DNS:localhost,URI:spiffe://raft.local/ns/e2e/sa/${id.value}\nextendedKeyUsage=serverAuth,clientAuth\n"
      )
      runCmd(
        Seq(
          "openssl",
          "req",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          rawKey.toString,
          "-out",
          csr.toString,
          "-subj",
          s"/CN=${id.value}"
        )
      )
      runCmd(
        Seq(
          "openssl",
          "pkcs8",
          "-topk8",
          "-nocrypt",
          "-in",
          rawKey.toString,
          "-out",
          key.toString
        )
      )
      runCmd(
        Seq(
          "openssl",
          "x509",
          "-req",
          "-in",
          csr.toString,
          "-CA",
          caCrt.toString,
          "-CAkey",
          caKey.toString,
          "-CAcreateserial",
          "-out",
          crt.toString,
          "-days",
          "2",
          "-extfile",
          ext.toString
        )
      )
      id -> (crt, key)
    }.toMap

    (caCrt, certs)

  it should "reject a CA-signed peer spoofing another NodeId under mTLS verifyPeerIdentity in a live 3-node cluster" in {
    val baseDir = Files.createTempDirectory("raft-e2e-mtls-identity-")
    val pkiDir = Files.createDirectories(baseDir.resolve("pki"))
    val t1 = NodeId("id-1")
    val t2 = NodeId("id-2")
    val t3 = NodeId("id-3")
    val ids = List(t1, t2, t3)
    val clusterCfg = ClusterConfig(ids.toSet)
    val (caCrt, nodeCerts) = generateSpiffePki(pkiDir, ids)

    def strictMtlsCfg(cert: Path, key: Path): GrpcConfig =
      GrpcConfig(
        host = "127.0.0.1",
        port = 0,
        useTls = true,
        tlsCertPath = Some(cert.toString),
        tlsKeyPath = Some(key.toString),
        tlsTrustCertPath = Some(caCrt.toString),
        tlsClientAuth = true,
        verifyPeerIdentity = true,
        maxRetries = 1,
        retryBackoff = 10.millis,
        maxRetryBackoff = 20.millis
      )

    val prog = (for
      nodes <- ids.traverse { id =>
        val (crt, key) = nodeCerts(id)
        buildNode(
          id = id,
          dataDir = baseDir.resolve(id.value),
          port = 0,
          initialPeers = Map.empty,
          clusterCfg = clusterCfg,
          grpcOverride = Some(strictMtlsCfg(crt, key))
        )
      }
      addrs = nodes.map(n => n.id -> n.address).toMap
      _ <- Resource.eval(
        nodes.traverse_(n => n.grpc.updatePeers(addrs - n.id)) *>
          nodes.traverse_(_.raftNode.start)
      )

      // Compromised client holds legitimately CA-signed id-2 cert, but spoofs localId = id-3!
      (id2Crt, id2Key) = nodeCerts(t2)
      spooferPair <- GrpcTransport.raftResource[IO](
        strictMtlsCfg(id2Crt, id2Key).copy(maxRetries = 0),
        TransportConfig(
          localId = t3,
          bindAddress = "127.0.0.1:0",
          peers = addrs,
          connectionTimeout = 400.millis
        )
      )
      (_, spooferTransport) = spooferPair

      _ <- Resource.eval {
        for
          _ <- (1 to 10).toList.traverse_ { i =>
            proposeWithRetry(nodes, s"sec-$i", s"val-$i")
          }
          leader <- awaitLeader(nodes)
          legitTerm <- leader.raftNode.getState.map(_.term)

          // Spoofed term=9999 RPC using valid CA cert for id-2 claiming sender_id=id-3!
          _ <- spooferTransport.send(
            leader.id,
            AppendEntriesRequest(
              term = 9999L,
              leaderId = t3,
              prevLogIndex = 0L,
              prevLogTerm = 0L,
              entries = Seq.empty,
              leaderCommit = 0L
            )
          )
          _ <- IO.sleep(150.millis)

          stateAfter <- leader.raftNode.getState
          leaderStats <- leader.grpc.transportStats
          _ <- proposeWithRetry(nodes, "sec-11", "still-healthy")
          _ <- awaitCondition(
            nodes.forallM(
              _.sm.getAll.map(_.get("sec-11").contains("still-healthy"))
            )
          )
        yield
          stateAfter shouldBe a[Leader]
          stateAfter.term shouldBe legitTerm
          leaderStats.rejectedSpoofedEnvelopes should be >= 1L
      }
    yield ()).use_.guarantee(IO(deleteRecursive(baseDir)))

    prog.timeout(90.seconds).unsafeRunSync()
  }
