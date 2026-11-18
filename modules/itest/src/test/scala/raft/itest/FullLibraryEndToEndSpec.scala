package raft.itest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.duration.*

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.RaftNode
import raft.impl.DefaultTimerService
import raft.jepsen.checker.{
  History,
  KVModel,
  LinearizabilityChecker,
  OpFunction,
  OpType,
  Operation
}
import raft.metrics.RaftMetrics
import raft.multigroup.{CoalescedHeartbeatConfig, GroupConfig, MultiRaftNode}
import raft.spi.{
  CompactionPolicy,
  StateMachine,
  Transport,
  TransportConfig,
  TransportFactory,
  TransportRegistry
}
import raft.state.*
import raft.state.NodeState.Leader
import raft.storage.{
  StorageBackedLogStore,
  StorageBackedSnapshotStore,
  StorageBackedStableStore
}
import raft.storage.mmap.MmapWalEngine
import raft.transport.grpc.{
  GrpcConfig,
  GrpcTransport,
  GrpcTransportFactory,
  ProtobufMessageCodec
}

/** Master end-to-end integration test suite exercising **every module and SPI
  * layer** in the repository together in cohesive lifecycles:
  *   - `raft-core`: `RaftLogic`, `ClusterConfig` Joint Consensus,
  *     `BatchConfig`, `PipelineConfig` `Inflights`, `ReadIndex` & `LeaseRead`,
  *     `TimeoutNow`
  *   - `raft-runtime`: `RaftNode`, `MultiRaftNode` (including
  *     `proposeConfChangeToGroup`), `CompactionManager`, `SnapshotTransfer`,
  *     `TransportRegistry`, `RaftMetrics`
  *   - `raft-storage-mmap`: `MmapWalEngine.factory` (`StorageEngineFactory`),
  *     `MmapSegment` rotation, `MmapHardState` CRC32, `RSNP` snapshots
  *   - `raft-transport-grpc`: `GrpcTransportFactory`, `GrpcTransport`,
  *     `ProtobufMessageCodec`, `ProtobufMultiGroupCodec`,
  *     `CoalescedHeartbeatConfig`
  *   - `raft-jepsen`: `LinearizabilityChecker` + `KVModel` (`Write`, `Read`,
  *     `Cas`)
  */
class FullLibraryEndToEndSpec extends AnyFlatSpec with Matchers:

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  private def deleteRecursive(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val listing = Files.list(path)
        try listing.iterator().forEachRemaining(deleteRecursive)
        finally listing.close()
      val _ = Files.deleteIfExists(path)

  /** Thread-safe `RaftMetrics[IO]` collector verifying operational telemetry
    * hooks fire across elections, replication, snapshots, and step-downs.
    */
  private final class RecordingRaftMetrics extends RaftMetrics[IO]:
    val leaderPromotions = new AtomicInteger(0)
    val stepDowns = new AtomicInteger(0)
    val electionsStarted = new AtomicInteger(0)
    val votesGranted = new AtomicInteger(0)
    val entriesAppended = new AtomicInteger(0)
    val commitsObserved = new AtomicInteger(0)
    val entriesApplied = new AtomicInteger(0)
    val snapshotsTaken = new AtomicInteger(0)
    val heartbeatsSent = new AtomicInteger(0)
    val replicationFailures = new AtomicInteger(0)

    def onBecomeLeader(nodeId: NodeId, term: Long): IO[Unit] =
      IO(leaderPromotions.incrementAndGet()).void
    def onStepDown(nodeId: NodeId, term: Long): IO[Unit] =
      IO(stepDowns.incrementAndGet()).void
    def onElectionStarted(nodeId: NodeId, term: Long): IO[Unit] =
      IO(electionsStarted.incrementAndGet()).void
    def onVoteGranted(nodeId: NodeId, to: NodeId, term: Long): IO[Unit] =
      IO(votesGranted.incrementAndGet()).void
    def onEntriesAppended(nodeId: NodeId, count: Int): IO[Unit] =
      IO(entriesAppended.addAndGet(count)).void
    def onEntriesCommitted(nodeId: NodeId, commitIndex: Long): IO[Unit] =
      IO(commitsObserved.incrementAndGet()).void
    def onEntriesApplied(nodeId: NodeId, lastApplied: Long): IO[Unit] =
      IO(entriesApplied.incrementAndGet()).void
    def onSnapshotTaken(nodeId: NodeId, lastIndex: Long): IO[Unit] =
      IO(snapshotsTaken.incrementAndGet()).void
    def onHeartbeatSent(nodeId: NodeId, followerCount: Int): IO[Unit] =
      IO(heartbeatsSent.incrementAndGet()).void
    def onReplicationFailure(nodeId: NodeId, followerId: NodeId): IO[Unit] =
      IO(replicationFailures.incrementAndGet()).void

  /** Replicated KV + CAS state machine recording per-index command outcomes so
    * callers can verify exact linearizable CAS semantics (`KVModel`).
    */
  private enum E2ECmdResult:
    case PutOk
    case CasResult(succeeded: Boolean)

  private final class CasKvStateMachine(
      stateRef: Ref[IO, Map[String, String]],
      outcomesRef: Ref[IO, Map[Long, E2ECmdResult]]
  ) extends StateMachine[IO, E2ECmdResult]:

    def apply(log: Log): IO[E2ECmdResult] =
      val str = new String(log.data, StandardCharsets.UTF_8)
      val compute: IO[E2ECmdResult] = str.split("\t", 4).toList match
        case "PUT" :: k :: v :: Nil =>
          stateRef.update(_.updated(k, v)).as(E2ECmdResult.PutOk)
        case "CAS" :: k :: exp :: nw :: Nil =>
          stateRef.modify { m =>
            if m.get(k).contains(exp) then
              (m.updated(k, nw), E2ECmdResult.CasResult(true))
            else (m, E2ECmdResult.CasResult(false))
          }
        case _ =>
          IO.pure(E2ECmdResult.PutOk)

      compute.flatTap { res =>
        outcomesRef.update { m =>
          val pruned =
            if m.size > 2048 then m.filter(_._1 >= log.index - 1024L) else m
          pruned.updated(log.index, res)
        }
      }

    def snapshot: IO[Stream[IO, Byte]] =
      stateRef.get.map { m =>
        val bytes = m.toSeq
          .sortBy(_._1)
          .map((k, v) => s"$k\t$v")
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
              .flatMap(_.split("\t", 2) match
                case Array(k, v) => Some(k -> v)
                case _           => None)
              .toMap
        stateRef.set(parsed)
      }

    def get(key: String): IO[Option[String]] = stateRef.get.map(_.get(key))
    def getAll: IO[Map[String, String]] = stateRef.get
    def outcomeAt(idx: Long): IO[Option[E2ECmdResult]] =
      outcomesRef.get.map(_.get(idx))

  private object CasKvStateMachine:
    def create: IO[CasKvStateMachine] =
      for
        s <- Ref.of[IO, Map[String, String]](Map.empty)
        o <- Ref.of[IO, Map[Long, E2ECmdResult]](Map.empty)
      yield new CasKvStateMachine(s, o)

  private final case class ManagedPeer(
      id: NodeId,
      dataDir: Path,
      port: Int,
      address: String,
      grpc: GrpcTransport[IO],
      raftNode: RaftNode[IO],
      sm: CasKvStateMachine,
      metrics: RecordingRaftMetrics
  )

  /** Boots a node strictly through the SPI factory abstractions:
    * `MmapWalEngine.factory[IO]` (`StorageEngineFactory`) + `TransportRegistry`
    * (`GrpcTransportFactory`) +
    * `Transport.withCodec(..., ProtobufMessageCodec)`.
    */
  private def bootPeerViaSpiFactories(
      id: NodeId,
      dataDir: Path,
      port: Int,
      initialPeers: Map[NodeId, String],
      clusterCfg: ClusterConfig,
      autoStart: Boolean
  ): Resource[IO, ManagedPeer] =
    val storageFactory = MmapWalEngine.factory[IO]
    val grpcCfg = GrpcConfig(
      host = "127.0.0.1",
      port = port,
      maxRetries = 1,
      retryBackoff = 5.millis,
      maxRetryBackoff = 10.millis,
      maxInboundQueueSize = 2048
    )
    val tCfg = TransportConfig(
      localId = id,
      bindAddress = s"127.0.0.1:$port",
      peers = initialPeers - id,
      connectionTimeout = 250.millis
    )
    val compactionCfg = CompactionConfig(
      level = CompactionLevel.Custom,
      snapshotChunkSize = 128,
      retainedEntries = 0L,
      policy = Some(CompactionPolicy.threshold[IO](10L))
    )

    for
      // 1. StorageEngineFactory SPI -> StorageBacked* adapters
      engine <- storageFactory.open(dataDir.toString)
      logStore = StorageBackedLogStore(engine)
      stableStore = StorageBackedStableStore(engine)
      snapStore = StorageBackedSnapshotStore(engine)

      // 2. TransportRegistry SPI -> GrpcTransportFactory -> ProtobufMessageCodec
      registry <- Resource.eval(TransportRegistry.create[IO])
      _ <- Resource.eval(
        registry.register("grpc", new GrpcTransportFactory[IO](grpcCfg))
      )
      avail <- Resource.eval(registry.available)
      _ = avail should contain("grpc")
      factoryOpt <- Resource.eval(registry.get("grpc"))
      typedFactory = factoryOpt.get
        .asInstanceOf[TransportFactory[IO, Array[Byte]]]
      rawTransport <- typedFactory.create(tCfg)
      rawGrpc = rawTransport.asInstanceOf[GrpcTransport[IO]]
      raftTransport = Transport.withCodec(rawTransport, ProtobufMessageCodec)
      boundAddr <- Resource.eval(rawGrpc.localAddress)
      boundPort = boundAddr.split(":").last.toInt

      // 3. Runtime + StateMachine + Metrics
      timerSvc <- Resource.eval(DefaultTimerService[IO])
      sm <- Resource.eval(CasKvStateMachine.create)
      metrics = new RecordingRaftMetrics
      raftCfg = RaftConfig(
        localId = id,
        electionTimeoutMin = 300.millis,
        electionTimeoutMax = 600.millis,
        heartbeatInterval = 45.millis,
        checkQuorumEnabled = true,
        leaseReadEnabled = true,
        maxEntriesPerRequest = 16,
        batching = BatchConfig(
          enabled = true,
          maxSize = 16,
          maxWait = 5.millis
        ),
        pipelining = PipelineConfig(enabled = true, maxInflight = 6),
        compaction = compactionCfg
      )
      rn <- RaftNode[IO](
        config = raftCfg,
        cluster = clusterCfg,
        transport = raftTransport,
        logStore = logStore,
        stableStore = stableStore,
        stateMachine = sm,
        timerService = timerSvc,
        metrics = Some(metrics),
        snapshotStoreOpt = Some(snapStore)
      )
      _ <- Resource.eval(if autoStart then rn.start else IO.unit)
    yield ManagedPeer(
      id = id,
      dataDir = dataDir,
      port = boundPort,
      address = boundAddr,
      grpc = rawGrpc,
      raftNode = rn,
      sm = sm,
      metrics = metrics
    )

  private def awaitCondition(
      cond: IO[Boolean],
      retries: Int = 400,
      delay: FiniteDuration = 20.millis
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(new RuntimeException("E2E condition timed out"))
        else IO.sleep(delay) *> awaitCondition(cond, retries - 1, delay)
    }

  private def awaitLeader(
      peers: Iterable[ManagedPeer],
      retries: Int = 450
  ): IO[ManagedPeer] =
    peers.toList
      .traverse(p => p.raftNode.getState.map(st => (p, st)))
      .flatMap { pairs =>
        val leaders = pairs.collect { case (p, l: Leader) => (p, l.term) }
        leaders.sortBy(-_._2).headOption match
          case Some((p, _)) => IO.pure(p)
          case None         =>
            if retries <= 0 then
              IO.raiseError(new RuntimeException("No leader elected"))
            else IO.sleep(20.millis) *> awaitLeader(peers, retries - 1)
      }

  private def putWithRetry(
      peers: Iterable[ManagedPeer],
      key: String,
      value: String,
      attempts: Int = 20
  ): IO[LogId] =
    awaitLeader(peers).flatMap { ldr =>
      ldr.raftNode
        .proposeAndCommit(utf8(s"PUT\t$key\t$value"))
        .timeout(4.seconds)
        .handleErrorWith {
          case _ if attempts > 1 =>
            IO.sleep(35.millis) *> putWithRetry(peers, key, value, attempts - 1)
          case err => IO.raiseError(err)
        }
    }

  private def reconfigWithRetry(
      peers: Iterable[ManagedPeer],
      targetCfg: ClusterConfig,
      attempts: Int = 25
  ): IO[Unit] =
    awaitLeader(peers).flatMap { ldr =>
      ldr.raftNode.getClusterConfig.flatMap { cur =>
        if !cur.isInJointConsensus && cur == targetCfg then IO.unit
        else
          ldr.raftNode
            .proposeConfChange(targetCfg)
            .void
            .timeout(5.seconds)
            .handleErrorWith {
              case _ if attempts > 1 =>
                IO.sleep(50.millis) *>
                  reconfigWithRetry(peers, targetCfg, attempts - 1)
              case err => IO.raiseError(err)
            }
      }
    }

  "Full Library & Module End-to-End Lifecycle" should "wire SPI factories, stage Learners with snapshot streaming, migrate 3 -> 5 -> 3 via Joint Consensus, transfer leadership, survive crash-restart, and pass WGL CAS+KV linearizability" in {
    val baseDir = Files.createTempDirectory("raft-full-e2e-lifecycle-")
    val n1 = NodeId("n1")
    val n2 = NodeId("n2")
    val n3 = NodeId("n3")
    val n4 = NodeId("n4")
    val n5 = NodeId("n5")
    val cfg3Initial = ClusterConfig(Set(n1, n2, n3))

    val prog = Ref
      .of[IO, Map[NodeId, (ManagedPeer, IO[Unit])]](Map.empty)
      .flatMap { peersRef =>
        def livePeers(ids: Iterable[NodeId]): IO[List[ManagedPeer]] =
          peersRef.get.map(m => ids.toList.flatMap(id => m.get(id).map(_._1)))

        val scenario = for
          // ── Phase 1: Boot {n1, n2, n3} via StorageEngineFactory & TransportRegistry ──
          _ <- List(n1, n2, n3).traverse_ { id =>
            bootPeerViaSpiFactories(
              id = id,
              dataDir = baseDir.resolve(id.value),
              port = 0,
              initialPeers = Map.empty,
              clusterCfg = cfg3Initial,
              autoStart = false
            ).allocated.flatMap(pair => peersRef.update(_.updated(id, pair)))
          }
          p3 <- livePeers(List(n1, n2, n3))
          addrs3 = p3.map(p => p.id -> p.address).toMap
          _ <- p3.traverse_(p => p.grpc.updatePeers(addrs3 - p.id))
          _ <- p3.traverse_(_.raftNode.start)

          // Commit a group-committed batch + sequential puts to trigger snapshot compaction (>10 entries)
          ldr0 <- awaitLeader(p3)
          batchIds <- ldr0.raftNode.proposeBatchAndCommit(
            (1 to 8).map(i => utf8(s"PUT\tk$i\tv$i"))
          )
          _ = batchIds should have size 8
          _ <- (9 to 14).toList.traverse_(i => putWithRetry(p3, s"k$i", s"v$i"))

          // Verify SnapshotStore compaction fired
          _ <- awaitCondition(
            p3.existsM(p => IO(p.metrics.snapshotsTaken.get() > 0))
          )

          // ── Phase 2: Stage n4 as non-voting Learner & catch up via InstallSnapshot ──
          cfgWithLearner = ClusterConfig(
            Map(
              n1 -> NodeRole.Voter,
              n2 -> NodeRole.Voter,
              n3 -> NodeRole.Voter,
              n4 -> NodeRole.Learner
            )
          )
          n4Alloc <- bootPeerViaSpiFactories(
            id = n4,
            dataDir = baseDir.resolve(n4.value),
            port = 0,
            initialPeers = addrs3,
            clusterCfg = cfgWithLearner,
            autoStart = false
          ).allocated
          _ <- peersRef.update(_.updated(n4, n4Alloc))
          p4 <- livePeers(List(n1, n2, n3, n4))
          addrs4 = p4.map(p => p.id -> p.address).toMap
          _ <- p4.traverse_(p => p.grpc.updatePeers(addrs4 - p.id))
          _ <- n4Alloc._1.raftNode.start
          _ <- reconfigWithRetry(p3, cfgWithLearner)
          _ <- putWithRetry(p3, "learner-gate", "staged")

          // Learner n4 must receive chunked snapshot + trailing log entries!
          _ <- awaitCondition(
            n4Alloc._1.sm.get("learner-gate").map(_.contains("staged"))
          )

          // ── Phase 3: Joint Consensus 3 -> 5 Voters, Transfer Leadership to n4, then Shrink 5 -> 3 ({n3, n4, n5}) ──
          cfg5Voters = ClusterConfig(Set(n1, n2, n3, n4, n5))
          n5Alloc <- bootPeerViaSpiFactories(
            id = n5,
            dataDir = baseDir.resolve(n5.value),
            port = 0,
            initialPeers = addrs4,
            clusterCfg = cfg5Voters,
            autoStart = false
          ).allocated
          _ <- peersRef.update(_.updated(n5, n5Alloc))
          p5 <- livePeers(List(n1, n2, n3, n4, n5))
          addrs5 = p5.map(p => p.id -> p.address).toMap
          _ <- p5.traverse_(p => p.grpc.updatePeers(addrs5 - p.id))
          _ <- n5Alloc._1.raftNode.start

          _ <- reconfigWithRetry(p5, cfg5Voters)
          _ <- putWithRetry(p5, "five-voter-key", "quorum-5")
          _ <- awaitCondition(
            p5.forallM(_.sm.get("five-voter-key").map(_.contains("quorum-5")))
          )

          // Transfer leadership explicitly to newly promoted voter n4 (§3.10)
          _ <- awaitCondition(
            for
              curLdr <- awaitLeader(p5)
              _ <-
                if curLdr.id == n4 then IO.unit
                else curLdr.raftNode.transferLeadership(n4).attempt.void
              isN4Leader <- n4Alloc._1.raftNode.getState.map {
                case _: Leader => true
                case _         => false
              }
            yield isN4Leader,
            retries = 350,
            delay = 25.millis
          )

          // Verify zero-RTT LeaseRead & quorum ReadIndex on leader n4
          leaseIdxOpt <- n4Alloc._1.raftNode.leaseRead
          readIdx <- n4Alloc._1.raftNode.readIndex
          _ = readIdx should be >= 15L
          _ = leaseIdxOpt.foreach(_ should be <= readIdx)

          // Joint Consensus shrink from {n1, n2, n3, n4, n5} -> {n3, n4, n5}, decommissioning n1 & n2!
          cfgFinal3 = ClusterConfig(Set(n3, n4, n5))
          _ <- reconfigWithRetry(p5, cfgFinal3)
          survivors3 <- livePeers(List(n3, n4, n5))
          addrsFinal3 = survivors3.map(p => p.id -> p.address).toMap
          // Shut down decommissioned n1 & n2 and evict their channels from {n3, n4, n5}
          _ <- List(n1, n2).traverse_ { oldId =>
            peersRef
              .modify(m => (m - oldId, m.get(oldId)))
              .flatMap(_.traverse_(_._2))
          }
          _ <- survivors3.traverse_(p => p.grpc.updatePeers(addrsFinal3 - p.id))

          // ── Phase 4: Hard Crash + Restart of n3 + Concurrent Multi-Client WGL CAS/Read/Write Verification ──
          _ <- putWithRetry(survivors3, "wgl-0", "v0")
          _ <- putWithRetry(survivors3, "wgl-1", "v0")

          // Crash n3 while {n4, n5} maintain a 2/3 majority quorum!
          n3Saved <- peersRef.modify(m => (m - n3, m(n3)))
          n3Port = n3Saved._1.port
          n3Dir = n3Saved._1.dataDir
          _ <- n3Saved._2

          activeDuringCrash <- livePeers(List(n4, n5))
          opSeq = new AtomicLong(0L)
          opsRef <- Ref.of[IO, Vector[Operation]](Vector.empty)

          // Seed baseline into WGL history before concurrent clients
          tSeed0 <- IO(System.nanoTime())
          idSeed0 <- IO(opSeq.incrementAndGet())
          tSeed1 <- IO(System.nanoTime())
          idSeed1 <- IO(opSeq.incrementAndGet())
          tSeed2 <- IO(System.nanoTime())
          _ <- opsRef.update(
            _ ++ Vector(
              Operation(
                idSeed0,
                0,
                OpType.Invoke,
                OpFunction.Write,
                "wgl-0",
                Some("v0"),
                None,
                tSeed0
              ),
              Operation(
                idSeed0,
                0,
                OpType.Ok,
                OpFunction.Write,
                "wgl-0",
                Some("v0"),
                Some("ok"),
                tSeed1
              ),
              Operation(
                idSeed1,
                0,
                OpType.Invoke,
                OpFunction.Write,
                "wgl-1",
                Some("v0"),
                None,
                tSeed1
              ),
              Operation(
                idSeed1,
                0,
                OpType.Ok,
                OpFunction.Write,
                "wgl-1",
                Some("v0"),
                Some("ok"),
                tSeed2
              )
            )
          )

          runWglClient = (cid: Int) =>
            (1 to 10).toList.traverse_ { step =>
              val key = s"wgl-${(cid + step) % 2}"
              val mode = step % 3
              for
                ldr <- awaitLeader(activeDuringCrash)
                opId <- IO(opSeq.incrementAndGet())
                _ <- mode match
                  case 0 => // Linearizable Read
                    for
                      t0 <- IO(System.nanoTime())
                      _ <- opsRef.update(
                        _ :+ Operation(
                          opId,
                          cid,
                          OpType.Invoke,
                          OpFunction.Read,
                          key,
                          None,
                          None,
                          t0
                        )
                      )
                      res <- (ldr.raftNode.readIndex *> ldr.sm.get(key))
                        .timeout(2.seconds)
                        .attempt
                      t1 <- IO(System.nanoTime())
                      _ <- opsRef.update { h =>
                        res match
                          case Right(vOpt) =>
                            h :+ Operation(
                              opId,
                              cid,
                              OpType.Ok,
                              OpFunction.Read,
                              key,
                              None,
                              Some(vOpt),
                              t1
                            )
                          case Left(_) =>
                            h :+ Operation(
                              opId,
                              cid,
                              OpType.Info,
                              OpFunction.Read,
                              key,
                              None,
                              None,
                              t1
                            )
                      }
                    yield ()

                  case 1 => // Write
                    val newVal = s"v-$cid-$step"
                    for
                      t0 <- IO(System.nanoTime())
                      _ <- opsRef.update(
                        _ :+ Operation(
                          opId,
                          cid,
                          OpType.Invoke,
                          OpFunction.Write,
                          key,
                          Some(newVal),
                          None,
                          t0
                        )
                      )
                      res <- ldr.raftNode
                        .proposeAndCommit(utf8(s"PUT\t$key\t$newVal"))
                        .timeout(2.seconds)
                        .attempt
                      t1 <- IO(System.nanoTime())
                      _ <- opsRef.update { h =>
                        res match
                          case Right(_) =>
                            h :+ Operation(
                              opId,
                              cid,
                              OpType.Ok,
                              OpFunction.Write,
                              key,
                              Some(newVal),
                              Some("ok"),
                              t1
                            )
                          case Left(_) =>
                            h :+ Operation(
                              opId,
                              cid,
                              OpType.Info,
                              OpFunction.Write,
                              key,
                              Some(newVal),
                              None,
                              t1
                            )
                      }
                    yield ()

                  case _ => // Compare-And-Swap (OpFunction.CAS)
                    val expVal =
                      if step <= 3 then "v0" else s"v-$cid-${step - 1}"
                    val nextVal = s"cas-$cid-$step"
                    for
                      t0 <- IO(System.nanoTime())
                      _ <- opsRef.update(
                        _ :+ Operation(
                          opId,
                          cid,
                          OpType.Invoke,
                          OpFunction.CAS,
                          key,
                          Some((expVal, nextVal)),
                          None,
                          t0
                        )
                      )
                      res <- (for
                        logId <- ldr.raftNode.proposeAndCommit(
                          utf8(s"CAS\t$key\t$expVal\t$nextVal")
                        )
                        outOpt <- ldr.sm.outcomeAt(logId.index)
                      yield outOpt).timeout(2.seconds).attempt
                      t1 <- IO(System.nanoTime())
                      _ <- opsRef.update { h =>
                        res match
                          case Right(Some(E2ECmdResult.CasResult(true))) =>
                            h :+ Operation(
                              opId,
                              cid,
                              OpType.Ok,
                              OpFunction.CAS,
                              key,
                              Some((expVal, nextVal)),
                              Some("ok"),
                              t1
                            )
                          case Right(Some(E2ECmdResult.CasResult(false))) =>
                            h :+ Operation(
                              opId,
                              cid,
                              OpType.Fail,
                              OpFunction.CAS,
                              key,
                              Some((expVal, nextVal)),
                              Some("failed"),
                              t1
                            )
                          case _ =>
                            h :+ Operation(
                              opId,
                              cid,
                              OpType.Info,
                              OpFunction.CAS,
                              key,
                              Some((expVal, nextVal)),
                              None,
                              t1
                            )
                      }
                    yield ()
              yield ()
            }

          _ <- (1 to 3).toList.parTraverse_(runWglClient)

          // Restart n3 from its on-disk MmapWalEngine and verify full 3-node convergence!
          n3Reborn <- bootPeerViaSpiFactories(
            id = n3,
            dataDir = n3Dir,
            port = n3Port,
            initialPeers = addrsFinal3,
            clusterCfg = cfgFinal3,
            autoStart = true
          ).allocated
          _ <- peersRef.update(_.updated(n3, n3Reborn))

          healed3 <- livePeers(List(n3, n4, n5))
          _ <- putWithRetry(healed3, "final-healed", "converged")
          _ <- awaitCondition(
            healed3
              .traverse(_.sm.getAll)
              .map(maps =>
                maps.distinct.size == 1 &&
                  maps.head.get("final-healed").contains("converged")
              )
          )

          // Verify WGL Linearizability (Write + Read + CAS)
          ops <- opsRef.get
          wglResult = LinearizabilityChecker.check(History(ops), KVModel)

          // Aggregate RaftMetrics across surviving peers
          totalPromotions = healed3.map(_.metrics.leaderPromotions.get()).sum
          totalElections = healed3.map(_.metrics.electionsStarted.get()).sum
          totalVotesGranted = healed3.map(_.metrics.votesGranted.get()).sum
          totalAppended = healed3.map(_.metrics.entriesAppended.get()).sum
          totalCommitted = healed3.map(_.metrics.commitsObserved.get()).sum
          totalApplied = healed3.map(_.metrics.entriesApplied.get()).sum
          totalHeartbeats = healed3.map(_.metrics.heartbeatsSent.get()).sum
        yield {
          withClue(s"WGL violation: ${wglResult.violation}") {
            wglResult.linearizable shouldBe true
          }
          totalPromotions should be >= 1
          totalElections should be >= 1
          totalVotesGranted should be >= 1
          totalAppended should be > 20
          totalCommitted should be > 10
          totalApplied should be > 20
          totalHeartbeats should be > 5
        }

        scenario.guarantee(
          peersRef.get
            .flatMap(_.values.toList.traverse_(_._2.attempt.void))
            .guarantee(IO(deleteRecursive(baseDir)))
        )
      }

    prog.timeout(100.seconds).unsafeRunSync()
  }

  it should "dynamically migrate an individual Multi-Raft shard across physical hosts via proposeConfChangeToGroup and recover from host crash" in {
    val baseDir = Files.createTempDirectory("raft-multiraft-reconfig-e2e-")
    val h1 = NodeId("h1")
    val h2 = NodeId("h2")
    val h3 = NodeId("h3")
    val h4 = NodeId("h4")
    val shardAlpha = GroupId("shard-alpha")
    val shardBeta = GroupId("shard-beta")

    final case class HostBox(
        id: NodeId,
        port: Int,
        address: String,
        grpc: GrpcTransport[IO],
        coordinator: MultiRaftNode[IO],
        sms: Map[GroupId, CasKvStateMachine]
    )

    def allocateMultiHost(
        id: NodeId,
        port: Int,
        groupsWithVoters: List[(GroupId, ClusterConfig)],
        peers: Map[NodeId, String]
    ): Resource[IO, HostBox] =
      for
        engines <- groupsWithVoters
          .traverse { case (gid, _) =>
            MmapWalEngine
              .resource[IO](
                baseDir.resolve(id.value).resolve(gid.value),
                segmentSize = 4096L
              )
              .map(gid -> _)
          }
          .map(_.toMap)
        grpcPair <- GrpcTransport.multiGroupResource[IO](
          grpcConfig = GrpcConfig(
            host = "127.0.0.1",
            port = port,
            maxRetries = 1,
            retryBackoff = 5.millis,
            maxRetryBackoff = 10.millis
          ),
          transportConfig = TransportConfig(
            localId = id,
            bindAddress = s"127.0.0.1:$port",
            peers = peers,
            connectionTimeout = 250.millis
          ),
          coalescing = CoalescedHeartbeatConfig(
            enabled = true,
            flushInterval = 10.millis
          )
        )
        (rawGrpc, mg) = grpcPair
        addr <- Resource.eval(rawGrpc.localAddress)
        boundPort = addr.split(":").last.toInt
        coord <- MultiRaftNode.resource[IO](id, mg)
        sms <- Resource.eval {
          groupsWithVoters
            .traverse { case (gid, clusterCfg) =>
              for
                sm <- CasKvStateMachine.create
                timer <- DefaultTimerService[IO]
                _ <- coord.createGroup(
                  gid,
                  GroupConfig(
                    raftConfig = RaftConfig(
                      localId = id,
                      electionTimeoutMin = 300.millis,
                      electionTimeoutMax = 600.millis,
                      heartbeatInterval = 45.millis,
                      batching =
                        BatchConfig(enabled = true, maxWait = 5.millis),
                      pipelining =
                        PipelineConfig(enabled = true, maxInflight = 4)
                    ),
                    cluster = clusterCfg,
                    logStore = StorageBackedLogStore(engines(gid)),
                    stableStore = StorageBackedStableStore(engines(gid)),
                    stateMachine = sm,
                    timerService = timer,
                    snapshotStore = Some(
                      StorageBackedSnapshotStore(engines(gid))
                    )
                  )
                )
              yield gid -> sm
            }
            .map(_.toMap)
        }
      yield HostBox(id, boundPort, addr, rawGrpc, coord, sms)

    val cfgInitial = ClusterConfig(Set(h1, h2, h3))
    val cfgMigratedAlpha = ClusterConfig(Set(h2, h3, h4))

    val prog = Ref
      .of[IO, Map[NodeId, (HostBox, IO[Unit])]](Map.empty)
      .flatMap { hostsRef =>
        def awaitGroupLeaderOn(
            gid: GroupId,
            candidates: List[NodeId],
            retries: Int = 400
        ): IO[HostBox] =
          hostsRef.get.flatMap { m =>
            val boxes = candidates.flatMap(id => m.get(id).map(_._1))
            boxes
              .traverse(b => b.coordinator.getGroupState(gid).map(b -> _))
              .flatMap { pairs =>
                pairs
                  .collect { case (b, Some(l: Leader)) => (b, l.term) }
                  .sortBy(-_._2)
                  .headOption match
                  case Some((b, _)) => IO.pure(b)
                  case None         =>
                    if retries <= 0 then
                      IO.raiseError(
                        new RuntimeException(s"No leader for ${gid.value}")
                      )
                    else
                      IO.sleep(20.millis) *>
                        awaitGroupLeaderOn(gid, candidates, retries - 1)
              }
          }

        def writeGroup(
            gid: GroupId,
            candidates: List[NodeId],
            k: String,
            v: String,
            attempts: Int = 20
        ): IO[Unit] =
          awaitGroupLeaderOn(gid, candidates).flatMap { ldr =>
            ldr.coordinator
              .proposeAndCommitToGroup(gid, utf8(s"PUT\t$k\t$v"))
              .timeout(4.seconds)
              .void
              .handleErrorWith {
                case _ if attempts > 1 =>
                  IO.sleep(30.millis) *>
                    writeGroup(gid, candidates, k, v, attempts - 1)
                case err => IO.raiseError(err)
              }
          }

        val scenario = for
          // Boot h1, h2, h3 with both shard-alpha & shard-beta; boot h4 ready to receive shard-alpha
          _ <- List(h1, h2, h3).traverse_ { id =>
            allocateMultiHost(
              id,
              port = 0,
              List(shardAlpha -> cfgInitial, shardBeta -> cfgInitial),
              Map.empty
            ).allocated.flatMap(pair => hostsRef.update(_.updated(id, pair)))
          }
          _ <- allocateMultiHost(
            h4,
            port = 0,
            List(shardAlpha -> cfgMigratedAlpha),
            Map.empty
          ).allocated.flatMap(pair => hostsRef.update(_.updated(h4, pair)))

          initMap <- hostsRef.get
          allAddrs = initMap.view.mapValues(_._1.address).toMap
          _ <- initMap.values.toList.traverse_ { case (b, _) =>
            b.grpc.updatePeers(allAddrs - b.id)
          }

          // Commit baseline writes on both shards across {h1, h2, h3}
          _ <- writeGroup(shardAlpha, List(h1, h2, h3), "a1", "before-move")
          _ <- writeGroup(shardBeta, List(h1, h2, h3), "b1", "stay-on-123")

          // Migrate ONLY shard-alpha from {h1, h2, h3} -> {h2, h3, h4} via MultiRaftNode.proposeConfChangeToGroup!
          _ <- awaitCondition(
            for
              ldr <- awaitGroupLeaderOn(shardAlpha, List(h1, h2, h3, h4))
              curOpt <- ldr.coordinator.getGroupClusterConfig(shardAlpha)
              done <-
                if curOpt
                    .exists(c => !c.isInJointConsensus && c == cfgMigratedAlpha)
                then IO.pure(true)
                else
                  ldr.coordinator
                    .proposeConfChangeToGroup(shardAlpha, cfgMigratedAlpha)
                    .timeout(5.seconds)
                    .attempt
                    .as(false)
            yield done,
            retries = 120,
            delay = 60.millis
          )

          // Remove shard-alpha from h1 and verify shard-alpha commits on {h2, h3, h4} while shard-beta commits on {h1, h2, h3}
          _ <- initMap(h1)._1.coordinator.removeGroup(shardAlpha)
          _ <- writeGroup(shardAlpha, List(h2, h3, h4), "a2", "after-move")
          _ <- writeGroup(shardBeta, List(h1, h2, h3), "b2", "still-isolated")

          // Crash h2 and restart h2 from its on-disk MmapWalEngines!
          h2Saved <- hostsRef.modify(m => (m - h2, m(h2)))
          h2Port = h2Saved._1.port
          _ <- h2Saved._2

          _ <- writeGroup(shardAlpha, List(h3, h4), "a3", "during-h2-down")
          _ <- writeGroup(shardBeta, List(h1, h3), "b3", "during-h2-down")

          h2Reborn <- allocateMultiHost(
            h2,
            port = h2Port,
            List(shardAlpha -> cfgMigratedAlpha, shardBeta -> cfgInitial),
            allAddrs - h2
          ).allocated
          _ <- hostsRef.update(_.updated(h2, h2Reborn))

          // Verify h2 catches up both shards from disk + gRPC replication!
          _ <- awaitCondition(
            for
              m <- hostsRef.get
              alphaOk <- List(h2, h3, h4).forallM { id =>
                m(id)._1.sms(shardAlpha).getAll.map { kv =>
                  kv == Map(
                    "a1" -> "before-move",
                    "a2" -> "after-move",
                    "a3" -> "during-h2-down"
                  )
                }
              }
              betaOk <- List(h1, h2, h3).forallM { id =>
                m(id)._1.sms(shardBeta).getAll.map { kv =>
                  kv == Map(
                    "b1" -> "stay-on-123",
                    "b2" -> "still-isolated",
                    "b3" -> "during-h2-down"
                  )
                }
              }
            yield alphaOk && betaOk
          )
        yield ()

        scenario.guarantee(
          hostsRef.get
            .flatMap(_.values.toList.traverse_(_._2.attempt.void))
            .guarantee(IO(deleteRecursive(baseDir)))
        )
      }

    prog.timeout(90.seconds).unsafeRunSync()
  }
