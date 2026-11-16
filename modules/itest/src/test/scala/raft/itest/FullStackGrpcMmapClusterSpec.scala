package raft.itest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicLong
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

/** Full-stack end-to-end integration suite wiring real `RaftNode[IO]` consensus
  * nodes over live HTTP/2 Netty `GrpcTransport` (`ProtobufMessageCodec`) and
  * JDK 22+ FFM `MmapWalEngine` (`hard_state.bin` + `.wal` segments +
  * `snapshots/`), verified for snapshot catch-up, process crash recovery, and
  * WGL linearizability (`LinearizabilityChecker` + `KVModel`).
  */
class FullStackGrpcMmapClusterSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val n4 = NodeId("n4")

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  private def deleteRecursive(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val listing = Files.list(path)
        try listing.iterator().forEachRemaining(deleteRecursive)
        finally listing.close()
      val _ = Files.deleteIfExists(path)

  private final class FullStackKvStateMachine private (
      ref: Ref[IO, Map[String, String]]
  ) extends StateMachine[IO, Unit]:

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

    def get(key: String): IO[Option[String]] =
      ref.get.map(_.get(key))

    def currentMap: IO[Map[String, String]] = ref.get

  private object FullStackKvStateMachine:
    def create: IO[FullStackKvStateMachine] =
      Ref
        .of[IO, Map[String, String]](Map.empty)
        .map(new FullStackKvStateMachine(_))

  private final case class LiveNode(
      id: NodeId,
      dataDir: Path,
      address: String,
      port: Int,
      engine: MmapWalEngine[IO],
      grpc: GrpcTransport[IO],
      raftNode: RaftNode[IO],
      stateMachine: FullStackKvStateMachine
  )

  private def buildNodeResource(
      id: NodeId,
      dataDir: Path,
      port: Int,
      initialPeers: Map[NodeId, String],
      clusterCfg: ClusterConfig,
      compaction: CompactionConfig,
      segmentSize: Long,
      autoStart: Boolean
  ): Resource[IO, LiveNode] =
    for
      engine <- MmapWalEngine.resource[IO](dataDir, segmentSize = segmentSize)
      logStore = StorageBackedLogStore(engine)
      stableStore = StorageBackedStableStore(engine)
      snapStore = StorageBackedSnapshotStore(engine)
      grpcCfg = GrpcConfig(
        host = "127.0.0.1",
        port = port,
        maxRetries = 1,
        retryBackoff = 5.millis,
        maxRetryBackoff = 10.millis,
        maxInboundQueueSize = 2048
      )
      transportCfg = TransportConfig(
        localId = id,
        bindAddress = s"127.0.0.1:$port",
        peers = initialPeers - id,
        connectionTimeout = 100.millis
      )
      grpcPair <- GrpcTransport.raftResource[IO](grpcCfg, transportCfg)
      (rawGrpc, raftTransport) = grpcPair
      boundAddr <- Resource.eval(rawGrpc.localAddress)
      boundPort = boundAddr.split(":").last.toInt
      timerSvc <- Resource.eval(DefaultTimerService[IO])
      sm <- Resource.eval(FullStackKvStateMachine.create)
      raftCfg = RaftConfig(
        localId = id,
        electionTimeoutMin = 150.millis,
        electionTimeoutMax = 300.millis,
        heartbeatInterval = 35.millis,
        compaction = compaction
      )
      rn <- RaftNode[IO](
        config = raftCfg,
        cluster = clusterCfg,
        transport = raftTransport,
        logStore = logStore,
        stableStore = stableStore,
        stateMachine = sm,
        timerService = timerSvc,
        snapshotStoreOpt = Some(snapStore)
      )
      _ <- Resource.eval(if autoStart then rn.start else IO.unit)
    yield LiveNode(
      id = id,
      dataDir = dataDir,
      address = boundAddr,
      port = boundPort,
      engine = engine,
      grpc = rawGrpc,
      raftNode = rn,
      stateMachine = sm
    )

  private def awaitCondition(
      cond: IO[Boolean],
      retries: Int = 300,
      interval: FiniteDuration = 20.millis
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(
            new RuntimeException("Full-stack cluster condition timed out")
          )
        else IO.sleep(interval) *> awaitCondition(cond, retries - 1, interval)
    }

  private def awaitLeader(
      nodes: Iterable[LiveNode],
      retries: Int = 300
  ): IO[LiveNode] =
    nodes.toList
      .traverse(ln => ln.raftNode.getState.map(st => (ln, st)))
      .flatMap { pairs =>
        val leaders = pairs.collect { case (ln, l: Leader) => (ln, l.term) }
        leaders.sortBy(-_._2).headOption match
          case Some((ln, _)) => IO.pure(ln)
          case None          =>
            if retries <= 0 then
              IO.raiseError(
                new RuntimeException("No leader elected over gRPC")
              )
            else IO.sleep(20.millis) *> awaitLeader(nodes, retries - 1)
      }

  "Full-stack gRPC + MmapWalEngine cluster" should "elect a leader, rotate WAL segments, compact snapshots, and stream InstallSnapshot over gRPC to a joining 4th node" in {
    val baseDir = Files.createTempDirectory("raft-itest-expand-")
    val initialIds = List(n1, n2, n3)
    val initialCfg = ClusterConfig(initialIds.toSet)
    val compactionCfg = CompactionConfig(
      level = CompactionLevel.Custom,
      snapshotChunkSize = 128,
      retainedEntries = 0L,
      policy = Some(CompactionPolicy.threshold[IO](20L))
    )

    val prog = (for
      // Bind 3 live gRPC + MmapWalEngine nodes on ephemeral ports (autoStart = false until peer ports are wired)
      initialNodes <- initialIds.traverse { id =>
        buildNodeResource(
          id = id,
          dataDir = baseDir.resolve(id.value),
          port = 0,
          initialPeers = Map.empty,
          clusterCfg = initialCfg,
          compaction = compactionCfg,
          segmentSize = 4096L,
          autoStart = false
        )
      }
      peerAddrs3 = initialNodes.map(n => n.id -> n.address).toMap
      _ <- Resource.eval(
        initialNodes.traverse_(n => n.grpc.updatePeers(peerAddrs3 - n.id)) *>
          initialNodes.traverse_(_.raftNode.start)
      )

      _ <- Resource.eval {
        for
          leader <- awaitLeader(initialNodes)
          // Commit 35 entries with ~80B payloads to trigger multi-segment 4KB WAL rotation + snapshot compaction
          _ <- (1 to 35).toList.traverse_ { i =>
            val pad = "x" * 60
            leader.raftNode.proposeAndCommit(utf8(s"fs-k$i=val-$i-$pad"))
          }
          snapsOnLeader <- leader.engine.listSnapshots
          _ = snapsOnLeader.nonEmpty shouldBe true
        yield ()
      }

      // Dynamically boot a 4th physical gRPC + MmapWalEngine node with an empty disk directory
      expandedCfg = ClusterConfig(Set(n1, n2, n3, n4))
      node4 <- buildNodeResource(
        id = n4,
        dataDir = baseDir.resolve(n4.value),
        port = 0,
        initialPeers = peerAddrs3,
        clusterCfg = expandedCfg,
        compaction = compactionCfg,
        segmentSize = 4096L,
        autoStart = true
      )
      allNodes = initialNodes :+ node4
      peerAddrs4 = allNodes.map(n => n.id -> n.address).toMap
      _ <- Resource.eval {
        for
          _ <- allNodes.traverse_(n => n.grpc.updatePeers(peerAddrs4 - n.id))
          leader <- awaitLeader(initialNodes)
          _ <- leader.raftNode.proposeConfChange(expandedCfg)
          latestLeader <- awaitLeader(allNodes)
          _ <- (36 to 45).toList.traverse_ { i =>
            val pad = "x" * 60
            latestLeader.raftNode.proposeAndCommit(utf8(s"fs-k$i=val-$i-$pad"))
          }
          expected = (1 to 45).map { i =>
            val pad = "x" * 60
            s"fs-k$i" -> s"val-$i-$pad"
          }.toMap
          // Node 4 must receive chunked gRPC InstallSnapshot + trailing WAL entries and persist its own snapshot
          _ <- awaitCondition(
            allNodes.forallM(n => n.stateMachine.currentMap.map(_ == expected)),
            retries = 350
          )
          n4Snaps <- node4.engine.listSnapshots
          _ = n4Snaps.nonEmpty shouldBe true
        yield ()
      }
    yield ()).use_.guarantee(IO(deleteRecursive(baseDir)))

    prog.unsafeRunSync()
  }

  it should "survive hard leader shutdown, elect a failover leader, and recover the restarted node from on-disk MmapWalEngine" in {
    val baseDir = Files.createTempDirectory("raft-itest-crash-")
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)
    val compactionCfg = CompactionConfig(
      level = CompactionLevel.Custom,
      snapshotChunkSize = 256,
      retainedEntries = 0L,
      policy = Some(CompactionPolicy.threshold[IO](15L))
    )

    val prog = Ref
      .of[IO, Map[NodeId, (LiveNode, IO[Unit])]](Map.empty)
      .flatMap { allocRef =>
        val runScenario = for
          // Allocate 3 physical nodes individually so we can crash & restart a single node mid-test
          _ <- ids.traverse_ { id =>
            buildNodeResource(
              id = id,
              dataDir = baseDir.resolve(id.value),
              port = 0,
              initialPeers = Map.empty,
              clusterCfg = clusterCfg,
              compaction = compactionCfg,
              segmentSize = 4096L,
              autoStart = false
            ).allocated.flatMap(pair => allocRef.update(_.updated(id, pair)))
          }
          initMap <- allocRef.get
          peerAddrs = initMap.view.mapValues(_._1.address).toMap
          _ <- initMap.values.toList.traverse_ { case (ln, _) =>
            ln.grpc.updatePeers(peerAddrs - ln.id)
          }
          _ <- initMap.values.toList.traverse_(_._1.raftNode.start)

          // Commit 25 writes (persisting both an on-disk snapshot at >=15 and uncompacted WAL tail)
          firstLeader <- awaitLeader(initMap.values.map(_._1))
          firstTerm <- firstLeader.raftNode.getState.map(_.term)
          _ <- (1 to 25).toList.traverse_ { i =>
            firstLeader.raftNode.proposeAndCommit(utf8(s"crash-k$i=before-$i"))
          }
          // Ensure followers also persisted up to 25 before killing the leader
          expectedBefore = (1 to 25)
            .map(i => s"crash-k$i" -> s"before-$i")
            .toMap
          _ <- awaitCondition(
            initMap.values.toList.forallM { case (ln, _) =>
              ln.stateMachine.currentMap.map(_ == expectedBefore)
            }
          )

          // Crash the active leader: shut down its RaftNode, Netty Server, and MmapWalEngine
          killedId = firstLeader.id
          killedPort = firstLeader.port
          killedDir = firstLeader.dataDir
          killedPair <- allocRef.modify { m =>
            (m - killedId, m(killedId))
          }
          (_, releaseKilled) = killedPair
          _ <- releaseKilled

          // Surviving 2 nodes elect a new higher-term leader and commit 15 more writes while killedId is offline
          survivors <- allocRef.get.map(_.values.map(_._1).toList)
          failoverLeader <- awaitLeader(survivors)
          _ <- awaitCondition(
            failoverLeader.raftNode.getState.map {
              case l: Leader => l.term > firstTerm
              case _         => false
            }
          )
          _ <- (26 to 40).toList.traverse_ { i =>
            failoverLeader.raftNode.proposeAndCommit(
              utf8(s"crash-k$i=during-$i")
            )
          }

          // Restart killedId on its SAME TCP port and EXISTING MmapWalEngine directory!
          restartedPair <- buildNodeResource(
            id = killedId,
            dataDir = killedDir,
            port = killedPort,
            initialPeers = peerAddrs,
            clusterCfg = clusterCfg,
            compaction = compactionCfg,
            segmentSize = 4096L,
            autoStart = true
          ).allocated
          _ <- allocRef.update(_.updated(killedId, restartedPair))

          restartedNode = restartedPair._1
          recoveredHs <- restartedNode.engine.readHardState
          _ = recoveredHs.term should be >= firstTerm

          expectedAll =
            expectedBefore ++ (26 to 40)
              .map(i => s"crash-k$i" -> s"during-$i")
              .toMap

          // Restarted node must restore its snapshot + WAL tail and catch up 26..40 over gRPC
          allLive <- allocRef.get.map(_.values.map(_._1).toList)
          _ <- awaitCondition(
            allLive
              .forallM(ln => ln.stateMachine.currentMap.map(_ == expectedAll)),
            retries = 350
          ).handleErrorWith { err =>
            allLive
              .traverse { ln =>
                for
                  st <- ln.raftNode.getState
                  m <- ln.stateMachine.currentMap
                  snaps <- ln.engine.listSnapshots
                  lastIdx <- ln.engine.lastLogIndex
                yield s"${ln.id.value}: state=$st, mapSize=${m.size}, missing=${(expectedAll.keySet -- m.keySet).toSeq.sorted.take(10)}, lastLogIdx=${lastIdx.value}, snaps=${snaps.map(_.lastIncludedIndex)}"
              }
              .flatMap(lines =>
                IO.raiseError(
                  new RuntimeException(
                    s"${err.getMessage}\nDiagnostics:\n${lines.mkString("\n")}",
                    err
                  )
                )
              )
          }

          // Confirm quorum readIndex across the healed 3-node cluster
          activeLeader <- awaitLeader(allLive)
          rIdx <- activeLeader.raftNode.readIndex
          _ = rIdx should be >= 40L
        yield ()

        runScenario.guarantee(
          allocRef.get
            .flatMap(_.values.toList.traverse_(_._2.attempt.void))
            .guarantee(IO(deleteRecursive(baseDir)))
        )
      }

    prog.unsafeRunSync()
  }

  it should "satisfy WGL linearizability (KVModel) under concurrent multi-client reads and writes over gRPC + MmapWalEngine" in {
    val baseDir = Files.createTempDirectory("raft-itest-wgl-")
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = (for
      nodes <- ids.traverse { id =>
        buildNodeResource(
          id = id,
          dataDir = baseDir.resolve(id.value),
          port = 0,
          initialPeers = Map.empty,
          clusterCfg = clusterCfg,
          compaction = CompactionConfig(),
          segmentSize = 8192L,
          autoStart = false
        )
      }
      peerAddrs = nodes.map(n => n.id -> n.address).toMap
      _ <- Resource.eval(
        nodes.traverse_(n => n.grpc.updatePeers(peerAddrs - n.id)) *>
          nodes.traverse_(_.raftNode.start)
      )
      _ <- Resource.eval {
        val nodeById = nodes.map(n => n.id -> n).toMap
        val opSeq = new AtomicLong(0L)

        for
          initialLeader <- awaitLeader(nodes)
          _ <- initialLeader.raftNode.proposeAndCommit(utf8("acct-0=init"))
          opsRef <- Ref.of[IO, Vector[Operation]](Vector.empty)

          runClient = (clientId: Int) => {
            (1 to 12).toList.traverse_ { step =>
              val key = s"acct-${(clientId + step) % 3}"
              val isWrite = step % 2 == 1
              for
                leader <- awaitLeader(nodes)
                opId <- IO(opSeq.incrementAndGet())
                _ <-
                  if isWrite then
                    val v = s"c$clientId-s$step"
                    for
                      tInv <- IO(System.nanoTime())
                      _ <- opsRef.update(
                        _ :+ Operation(
                          index = opId,
                          process = clientId,
                          opType = OpType.Invoke,
                          function = OpFunction.Write,
                          key = key,
                          inputValue = Some(v),
                          outputValue = None,
                          timestamp = tInv
                        )
                      )
                      res <- leader.raftNode
                        .proposeAndCommit(utf8(s"$key=$v"))
                        .timeout(2.seconds)
                        .attempt
                      tDone <- IO(System.nanoTime())
                      _ <- res match
                        case Right(_) =>
                          opsRef.update(
                            _ :+ Operation(
                              index = opId,
                              process = clientId,
                              opType = OpType.Ok,
                              function = OpFunction.Write,
                              key = key,
                              inputValue = Some(v),
                              outputValue = Some("ok"),
                              timestamp = tDone
                            )
                          )
                        case Left(_) =>
                          opsRef.update(
                            _ :+ Operation(
                              index = opId,
                              process = clientId,
                              opType = OpType.Info,
                              function = OpFunction.Write,
                              key = key,
                              inputValue = Some(v),
                              outputValue = None,
                              timestamp = tDone
                            )
                          )
                    yield ()
                  else
                    for
                      tInv <- IO(System.nanoTime())
                      _ <- opsRef.update(
                        _ :+ Operation(
                          index = opId,
                          process = clientId,
                          opType = OpType.Invoke,
                          function = OpFunction.Read,
                          key = key,
                          inputValue = None,
                          outputValue = None,
                          timestamp = tInv
                        )
                      )
                      res <- (for
                        _ <- leader.raftNode.readIndex
                        vOpt <- nodeById(leader.id).stateMachine.get(key)
                      yield vOpt).timeout(2.seconds).attempt
                      tDone <- IO(System.nanoTime())
                      _ <- res match
                        case Right(vOpt) =>
                          opsRef.update(
                            _ :+ Operation(
                              index = opId,
                              process = clientId,
                              opType = OpType.Ok,
                              function = OpFunction.Read,
                              key = key,
                              inputValue = None,
                              outputValue = Some(vOpt),
                              timestamp = tDone
                            )
                          )
                        case Left(_) =>
                          opsRef.update(
                            _ :+ Operation(
                              index = opId,
                              process = clientId,
                              opType = OpType.Info,
                              function = OpFunction.Read,
                              key = key,
                              inputValue = None,
                              outputValue = None,
                              timestamp = tDone
                            )
                          )
                    yield ()
              yield ()
            }
          }

          // Seed baseline acct-0="init" into recorded history at t0 before concurrent workload
          t0 <- IO(System.nanoTime())
          seedId <- IO(opSeq.incrementAndGet())
          t1 <- IO(System.nanoTime())
          _ <- opsRef.update(
            _ :+ Operation(
              seedId,
              0,
              OpType.Invoke,
              OpFunction.Write,
              "acct-0",
              Some("init"),
              None,
              t0
            ) :+ Operation(
              seedId,
              0,
              OpType.Ok,
              OpFunction.Write,
              "acct-0",
              Some("init"),
              Some("ok"),
              t1
            )
          )

          // Run 4 concurrent client fibers driving real gRPC + MmapWalEngine consensus
          _ <- (1 to 4).toList.parTraverse_(runClient)

          recordedOps <- opsRef.get
          history = History(recordedOps)
          checkResult = LinearizabilityChecker.check(history, KVModel)
        yield {
          withClue(
            s"WGL violation: ${checkResult.violation}\nTimeline:\n${history.timeline}"
          ) {
            checkResult.linearizable shouldBe true
            checkResult.violation shouldBe None
          }
          checkResult.stats.opsChecked should be >= 35
        }
      }
    yield ()).use_.guarantee(IO(deleteRecursive(baseDir)))

    prog.unsafeRunSync()
  }
