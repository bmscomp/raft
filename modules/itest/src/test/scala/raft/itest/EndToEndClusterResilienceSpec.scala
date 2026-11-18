package raft.itest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.sys.process.*

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.RaftNode
import raft.impl.DefaultTimerService
import raft.message.RaftMessage
import raft.message.RaftMessage.AppendEntriesRequest
import raft.raw.{OutboundMessage, RawNode}
import raft.spi.{CompactionPolicy, RaftTransport, StateMachine, TransportConfig}
import raft.state.*
import raft.state.NodeState.{Follower, Leader, PreCandidate}
import raft.storage.{
  EngineBackedRaftStorage,
  StorageBackedLogStore,
  StorageBackedSnapshotStore,
  StorageBackedStableStore
}
import raft.storage.mmap.MmapWalEngine
import raft.transport.grpc.{GrpcConfig, GrpcTransport}

/** Comprehensive End-to-End Cluster Resilience, Sans-I/O Storage/Network,
  * Checkpoint Self-Healing, and Mutual TLS (`mTLS`) Integration Suite:
  *   1. '''Live 5-Node Network Partition & Split-Brain Fencing''': minority
  *      leader uncommitted `.wal` writes, `CheckQuorum` step-down, stale
  *      write/read fencing, majority failover, on-disk `.wal` conflict
  *      truncation on heal, and `PreVote` non-disruptive rejoin.
  *   2. '''Sans-I/O `RawNode` + `EngineBackedRaftStorage` (`MmapWalEngine`) +
  *      Live Netty `GrpcTransport`''': full 3-node Sans-I/O cluster with batch
  *      replication, `readIndex`, snapshot compaction, hard leader crash, and
  *      disk recovery.
  *   3. '''On-Disk `WIDX` (`index.ckpt`) Corruption Self-Healing''': 1,050+
  *      entries triggering `index.ckpt` creation, intentional byte corruption
  *      on disk, `.wal` segment replay fallback on restart, and live gRPC
  *      cluster catch-up to 1,100 entries.
  *   4. '''End-to-End Mutual TLS (`mTLS`) Encrypted Cluster & Rogue Peer
  *      Rejection''': 3-node X.509 mTLS cluster (`useTls = true`,
  *      `tlsClientAuth = true`) rejecting a spoofed `term = 9999` RPC from an
  *      untrusted certificate at the TLS handshake layer.
  */
class EndToEndClusterResilienceSpec extends AnyFlatSpec with Matchers:

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  private def deleteRecursive(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val listing = Files.list(path)
        try listing.iterator().forEachRemaining(deleteRecursive)
        finally listing.close()
      val _ = Files.deleteIfExists(path)

  private final class KvMapStateMachine(ref: Ref[IO, Map[String, String]])
      extends StateMachine[IO, Unit]:
    def apply(log: Log): IO[Unit] =
      if log.data.isEmpty then IO.unit
      else
        new String(log.data, StandardCharsets.UTF_8).split("=", 2) match
          case Array(k, v) => ref.update(_.updated(k, v))
          case _           => IO.unit

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
        val str = new String(bytes, StandardCharsets.UTF_8)
        val parsed =
          if str.isEmpty then Map.empty[String, String]
          else
            str
              .split("\n")
              .toList
              .flatMap(_.split("=", 2) match
                case Array(k, v) => Some(k -> v)
                case _           => None)
              .toMap
        ref.set(parsed)
      }

    def getAll: IO[Map[String, String]] = ref.get
    def size: IO[Int] = ref.get.map(_.size)

  private object KvMapStateMachine:
    def create: IO[KvMapStateMachine] =
      Ref.of[IO, Map[String, String]](Map.empty).map(new KvMapStateMachine(_))

  /** Wraps a live Netty `RaftTransport[IO]` with dynamic directional link
    * blocking so real gRPC + `MmapWalEngine` nodes experience genuine network
    * partitions without stopping their timers, sockets, or WAL engines.
    */
  private final class LinkFilterTransport(
      localId: NodeId,
      underlying: RaftTransport[IO],
      allPeerIdsRef: Ref[IO, Set[NodeId]],
      blockedLinksRef: Ref[IO, Set[(NodeId, NodeId)]]
  ) extends RaftTransport[IO]:

    private def allowed(from: NodeId, to: NodeId): IO[Boolean] =
      blockedLinksRef.get.map(blocked => !blocked.contains((from, to)))

    def send(to: NodeId, msg: RaftMessage): IO[Unit] =
      allowed(localId, to).flatMap {
        case true  => underlying.send(to, msg)
        case false => IO.unit
      }

    def sendBatch(to: NodeId, msgs: Seq[RaftMessage]): IO[Unit] =
      allowed(localId, to).flatMap {
        case true  => underlying.sendBatch(to, msgs)
        case false => IO.unit
      }

    def broadcast(msg: RaftMessage): IO[Unit] =
      allPeerIdsRef.get.flatMap { peers =>
        (peers - localId).toList.parTraverse_(peer => send(peer, msg))
      }

    def receive: Stream[IO, (NodeId, RaftMessage)] =
      underlying.receive.evalFilter { case (from, _) =>
        allowed(from, localId)
      }

    def localAddress: IO[String] = underlying.localAddress
    def shutdown: IO[Unit] = underlying.shutdown

  private final case class LiveClusterNode(
      id: NodeId,
      dataDir: Path,
      port: Int,
      address: String,
      engine: MmapWalEngine[IO],
      grpc: GrpcTransport[IO],
      raftNode: RaftNode[IO],
      sm: KvMapStateMachine
  )

  private def buildRuntimeNode(
      id: NodeId,
      dataDir: Path,
      port: Int,
      initialPeers: Map[NodeId, String],
      clusterCfg: ClusterConfig,
      segmentSize: Long = 8192L,
      compactionThreshold: Long = 200L,
      grpcBaseCfg: Option[GrpcConfig] = None,
      filterLinks: Option[
        (Ref[IO, Set[NodeId]], Ref[IO, Set[(NodeId, NodeId)]])
      ] = None,
      autoStart: Boolean = false
  ): Resource[IO, LiveClusterNode] =
    val effectiveGrpc = grpcBaseCfg.getOrElse(
      GrpcConfig(
        host = "127.0.0.1",
        port = port,
        maxRetries = 1,
        retryBackoff = 5.millis,
        maxRetryBackoff = 10.millis,
        maxInboundQueueSize = 4096
      )
    )
    val tCfg = TransportConfig(
      localId = id,
      bindAddress = s"127.0.0.1:$port",
      peers = initialPeers - id,
      connectionTimeout = 300.millis
    )
    val compactionCfg = CompactionConfig(
      level = CompactionLevel.Custom,
      snapshotChunkSize = 512,
      retainedEntries = 0L,
      policy = Some(CompactionPolicy.threshold[IO](compactionThreshold))
    )

    for
      engine <- MmapWalEngine.resource[IO](dataDir, segmentSize = segmentSize)
      grpcPair <- GrpcTransport.raftResource[IO](effectiveGrpc, tCfg)
      (rawGrpc, nettyRaftTransport) = grpcPair
      effectiveTransport = filterLinks match
        case Some((peersRef, blockedRef)) =>
          new LinkFilterTransport(
            id,
            nettyRaftTransport,
            peersRef,
            blockedRef
          )
        case None => nettyRaftTransport
      boundAddr <- Resource.eval(rawGrpc.localAddress)
      boundPort = boundAddr.split(":").last.toInt
      timer <- Resource.eval(DefaultTimerService[IO])
      sm <- Resource.eval(KvMapStateMachine.create)
      rn <- RaftNode[IO](
        config = RaftConfig(
          localId = id,
          electionTimeoutMin = 250.millis,
          electionTimeoutMax = 500.millis,
          heartbeatInterval = 40.millis,
          preVoteEnabled = true,
          checkQuorumEnabled = true,
          leaseReadEnabled = true,
          batching = BatchConfig(
            enabled = true,
            maxSize = 64,
            maxWait = 4.millis
          ),
          pipelining = PipelineConfig(enabled = true, maxInflight = 8),
          compaction = compactionCfg
        ),
        cluster = clusterCfg,
        transport = effectiveTransport,
        logStore = StorageBackedLogStore(engine),
        stableStore = StorageBackedStableStore(engine),
        stateMachine = sm,
        timerService = timer,
        snapshotStoreOpt = Some(StorageBackedSnapshotStore(engine))
      )
      _ <- Resource.eval(if autoStart then rn.start else IO.unit)
    yield LiveClusterNode(
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
            new RuntimeException("End-to-end resilience condition timed out")
          )
        else IO.sleep(delay) *> awaitCondition(cond, retries - 1, delay)
    }

  private def awaitLeader(
      nodes: Iterable[LiveClusterNode],
      retries: Int = 450
  ): IO[LiveClusterNode] =
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

  private def writeWithRetry(
      nodes: Iterable[LiveClusterNode],
      key: String,
      value: String,
      attempts: Int = 25
  ): IO[LogId] =
    awaitLeader(nodes).flatMap { ldr =>
      ldr.raftNode
        .proposeAndCommit(utf8(s"$key=$value"))
        .timeout(4.seconds)
        .handleErrorWith {
          case _ if attempts > 1 =>
            IO.sleep(35.millis) *>
              writeWithRetry(nodes, key, value, attempts - 1)
          case err => IO.raiseError(err)
        }
    }

  private def writeBatchWithRetry(
      nodes: Iterable[LiveClusterNode],
      entries: Seq[(String, String)],
      attempts: Int = 25
  ): IO[Vector[LogId]] =
    val payloads = entries.map((k, v) => utf8(s"$k=$v"))
    awaitLeader(nodes).flatMap { ldr =>
      ldr.raftNode
        .proposeBatchAndCommit(payloads)
        .timeout(5.seconds)
        .handleErrorWith {
          case _ if attempts > 1 =>
            IO.sleep(35.millis) *>
              writeBatchWithRetry(nodes, entries, attempts - 1)
          case err => IO.raiseError(err)
        }
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Scenario 1: Live 5-Node Network Partition, Split-Brain Fencing,
  //             Conflicting WAL Truncation & PreVote Non-Disruptive Rejoin
  // ─────────────────────────────────────────────────────────────────────────

  "End-to-End Cluster Resilience Suite" should "fence a partitioned minority leader via CheckQuorum, truncate uncommitted WAL conflicts on heal, and prevent PreVote term inflation over live gRPC + MmapWalEngine" in {
    val baseDir = Files.createTempDirectory("raft-e2e-partition-")
    val ids = (1 to 5).map(i => NodeId(s"n$i")).toList
    val clusterCfg = ClusterConfig(ids.toSet)

    def cutBetween(a: Set[NodeId], b: Set[NodeId]): Set[(NodeId, NodeId)] =
      for
        x <- a
        y <- b
        pair <- List((x, y), (y, x))
      yield pair

    val prog = (for
      peersSetRef <- Resource.eval(Ref.of[IO, Set[NodeId]](ids.toSet))
      blockedRef <- Resource.eval(Ref.of[IO, Set[(NodeId, NodeId)]](Set.empty))
      nodes <- ids.traverse { id =>
        buildRuntimeNode(
          id = id,
          dataDir = baseDir.resolve(id.value),
          port = 0,
          initialPeers = Map.empty,
          clusterCfg = clusterCfg,
          segmentSize = 4096L,
          filterLinks = Some((peersSetRef, blockedRef))
        )
      }
      addrs = nodes.map(n => n.id -> n.address).toMap
      _ <- Resource.eval(
        nodes.traverse_(n => n.grpc.updatePeers(addrs - n.id)) *>
          nodes.traverse_(_.raftNode.start)
      )
      _ <- Resource.eval {
        for
          // 1. Commit baseline k1..k10 across all 5 live nodes
          _ <- writeBatchWithRetry(
            nodes,
            (1 to 10).map(i => s"k$i" -> s"init-$i")
          )
          baselineExpected = (1 to 10).map(i => s"k$i" -> s"init-$i").toMap
          _ <- awaitCondition(
            nodes.forallM(_.sm.getAll.map(_ == baselineExpected))
          )

          oldLeader <- awaitLeader(nodes)
          oldTerm <- oldLeader.raftNode.getState.map(_.term)
          others = nodes.filterNot(_.id == oldLeader.id)
          minFollower = others.head
          majorityNodes = others.tail // 3/5 majority!
          minorityIds = Set(oldLeader.id, minFollower.id)
          majorityIds = majorityNodes.map(_.id).toSet

          // 2. Inject 2/5 vs 3/5 network partition!
          _ <- blockedRef.set(cutBetween(minorityIds, majorityIds))

          // Append uncommitted phantom entries onto oldLeader's local MmapWalEngine before CheckQuorum fires
          _ <- (1 to 3).toList.traverse_ { i =>
            oldLeader.raftNode
              .propose(utf8(s"phantom-$i=stale"))
              .attempt
              .void
          }

          // Verify oldLeader CANNOT commit writes or serve linearizable ReadIndex in a minority!
          fencedWrite <- oldLeader.raftNode
            .proposeAndCommit(utf8("phantom-commit=never"))
            .timeout(900.millis)
            .attempt
          _ = fencedWrite.isLeft shouldBe true

          fencedRead <- oldLeader.raftNode.readIndex
            .timeout(900.millis)
            .attempt
          _ = fencedRead.isLeft shouldBe true

          // Verify CheckQuorum automatically steps oldLeader down out of Leader role (Follower or PreCandidate at oldTerm)!
          _ <- awaitCondition(
            oldLeader.raftNode.getState.map {
              case _: Follower | _: PreCandidate => true
              case _                             => false
            },
            retries = 300
          )

          // 3. Majority {m1, m2, m3} elects a new higher-term leader and commits k11..k25
          newLeader <- awaitLeader(majorityNodes)
          newTerm <- newLeader.raftNode.getState.map(_.term)
          _ = newTerm should be > oldTerm
          _ <- writeBatchWithRetry(
            majorityNodes,
            (11 to 25).map(i => s"k$i" -> s"maj-$i")
          )

          // 4. Heal partition -> oldLeader's uncommitted phantom WAL entries MUST be truncated and overwritten!
          _ <- blockedRef.set(Set.empty)
          healedExpected =
            baselineExpected ++ (11 to 25).map(i => s"k$i" -> s"maj-$i").toMap
          _ <- awaitCondition(
            nodes.forallM(_.sm.getAll.map(_ == healedExpected)),
            retries = 450
          )

          // 5. Isolate a single follower (1/5) for >2 election timeouts & verify PreVote prevents on-disk term inflation!
          stableLeader <- awaitLeader(nodes)
          stableTerm <- stableLeader.raftNode.getState.map(_.term)
          loneVictim = nodes.find(_.id != stableLeader.id).get
          loneHsBefore <- loneVictim.engine.readHardState
          _ <- blockedRef.set(
            cutBetween(Set(loneVictim.id), ids.toSet - loneVictim.id)
          )
          _ <- IO.sleep(850.millis)
          loneHsDuring <- loneVictim.engine.readHardState
          _ = loneHsDuring.term shouldBe loneHsBefore.term

          // Reconnect loneVictim -> active leader stays undisturbed at stableTerm!
          _ <- blockedRef.set(Set.empty)
          _ <- writeWithRetry(nodes, "k26", "after-prevote-rejoin")
          _ <- awaitCondition(
            nodes.forallM(
              _.sm.getAll.map(
                _.get("k26").contains("after-prevote-rejoin")
              )
            )
          )
          termAfterRejoin <- stableLeader.raftNode.getState.map(_.term)
          _ = termAfterRejoin shouldBe stableTerm
        yield ()
      }
    yield ()).use_.guarantee(IO(deleteRecursive(baseDir)))

    prog.timeout(90.seconds).unsafeRunSync()
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Scenario 2: Sans-I/O RawNode + EngineBackedRaftStorage (MmapWalEngine) +
  //             Live Netty GrpcTransport Cluster & Crash Recovery
  // ─────────────────────────────────────────────────────────────────────────

  private enum RawPeerCmd:
    case Tick
    case Step(from: NodeId, msg: RaftMessage)
    case ProposeBatch(
        payloads: Seq[Array[Byte]],
        reply: Deferred[IO, Either[RaftError, Vector[Long]]]
    )
    case ReadIdx(
        reqId: String,
        reply: Deferred[IO, Either[RaftError, Long]]
    )
    case Compact(upToIndex: Long, done: Deferred[IO, Unit])
    case Stop

  private final class SansIoGrpcPeer(
      val id: NodeId,
      val port: Int,
      val address: String,
      val grpc: GrpcTransport[IO],
      raftTransport: RaftTransport[IO],
      val storage: EngineBackedRaftStorage[IO],
      raw: RawNode,
      inbox: Queue[IO, RawPeerCmd],
      kvRef: Ref[IO, Map[String, String]],
      pendingReadsRef: Ref[
        IO,
        Map[String, Deferred[IO, Either[RaftError, Long]]]
      ]
  ):
    @volatile var isLeaderVolatile: Boolean = false

    private def applyCommitted(entries: Vector[Log]): IO[Unit] =
      entries.traverse_ { e =>
        if e.logType == LogType.Command && e.data.nonEmpty then
          new String(e.data, StandardCharsets.UTF_8).split("=", 2) match
            case Array(k, v) => kvRef.update(_.updated(k, v))
            case _           => IO.unit
        else IO.unit
      }

    private def dispatchOutbound(msgs: Vector[OutboundMessage]): IO[Unit] =
      msgs
        .groupBy(_.to)
        .toList
        .parTraverse_ { case (dest, slice) =>
          raftTransport.sendBatch(dest, slice.map(_.message))
        }

    private def drainReady(): IO[Unit] =
      IO(raw.hasReady).flatMap {
        case false => IO.unit
        case true  =>
          for
            rd <- IO(raw.ready())
            _ <- IO {
              isLeaderVolatile = raw.state.isInstanceOf[Leader]
            }
            // 1. Optimistic pre-persist messages
            _ <- dispatchOutbound(rd.messages)
            // 2. If a snapshot arrived over gRPC, restore KV map before persisting
            _ <- rd.snapshot.traverse_ { snapMeta =>
              val str = new String(snapMeta.data, StandardCharsets.UTF_8)
              val restored =
                if str.isEmpty then Map.empty[String, String]
                else
                  str
                    .split("\n")
                    .toList
                    .flatMap(_.split("=", 2) match
                      case Array(k, v) => Some(k -> v)
                      case _           => None)
                    .toMap
              kvRef.set(restored)
            }
            // 3. Flush Ready durably (msync) to MmapWalEngine via EngineBackedRaftStorage
            _ <- storage.persistReady(rd)
            // 4. Send post-persist messages
            _ <- dispatchOutbound(rd.persistedMessages)
            // 5. Apply committed entries
            _ <- applyCommitted(rd.committedEntries)
            // 6. Complete any confirmed linearizable ReadStates
            _ <- rd.readStates.traverse_ { rs =>
              pendingReadsRef
                .modify(m => (m - rs.requestCtx, m.get(rs.requestCtx)))
                .flatMap(_.traverse_(d => d.complete(Right(rs.index)).void))
            }
            // 7. Advance RawNode & process follow-up LightReady
            light <- IO(raw.advance(rd))
            _ <- storage.persistLightReady(light)
            _ <- dispatchOutbound(light.messages)
            _ <- applyCommitted(light.committedEntries)
            _ <- IO {
              isLeaderVolatile = raw.state.isInstanceOf[Leader]
            }
            _ <- drainReady()
          yield ()
      }

    def runEventLoop: IO[Unit] =
      inbox.take.flatMap {
        case RawPeerCmd.Stop => IO.unit
        case cmd             =>
          val stepEffect = cmd match
            case RawPeerCmd.Tick =>
              IO(raw.tick())
            case RawPeerCmd.Step(from, msg) =>
              IO(raw.step(from, msg))
            case RawPeerCmd.ProposeBatch(payloads, reply) =>
              IO(raw.proposeBatch(payloads)).flatMap(r =>
                reply.complete(r).void
              )
            case RawPeerCmd.ReadIdx(reqId, reply) =>
              IO(raw.readIndex(reqId)).flatMap {
                case Left(err) => reply.complete(Left(err)).void
                case Right(()) =>
                  pendingReadsRef.update(_.updated(reqId, reply))
              }
            case RawPeerCmd.Compact(upTo, done) =>
              for
                curKv <- kvRef.get
                snapBytes = curKv.toSeq
                  .sortBy(_._1)
                  .map((k, v) => s"$k=$v")
                  .mkString("\n")
                  .getBytes(StandardCharsets.UTF_8)
                _ <- storage.compactTo(upTo, snapBytes)
                _ <- done.complete(())
              yield ()
            case RawPeerCmd.Stop => IO.unit
          stepEffect *> drainReady() *> runEventLoop
      }

    def proposeBatch(
        payloads: Seq[Array[Byte]]
    ): IO[Either[RaftError, Vector[Long]]] =
      for
        d <- Deferred[IO, Either[RaftError, Vector[Long]]]
        _ <- inbox.offer(RawPeerCmd.ProposeBatch(payloads, d))
        res <- d.get
      yield res

    def readIndex(reqId: String): IO[Either[RaftError, Long]] =
      for
        d <- Deferred[IO, Either[RaftError, Long]]
        _ <- inbox.offer(RawPeerCmd.ReadIdx(reqId, d))
        res <- d.get
      yield res

    def compactTo(upToIndex: Long): IO[Unit] =
      for
        d <- Deferred[IO, Unit]
        _ <- inbox.offer(RawPeerCmd.Compact(upToIndex, d))
        _ <- d.get
      yield ()

    def currentKv: IO[Map[String, String]] = kvRef.get

  private def allocateSansIoPeer(
      id: NodeId,
      dataDir: Path,
      port: Int,
      peers: Map[NodeId, String],
      voters: ClusterConfig,
      seed: Long
  ): Resource[IO, SansIoGrpcPeer] =
    for
      engine <- MmapWalEngine.resource[IO](dataDir, segmentSize = 4096L)
      storage <- Resource.eval(EngineBackedRaftStorage.open[IO](engine, voters))
      initialKv = storage.snapshotMetadata
        .map { sm =>
          val str = new String(sm.data, StandardCharsets.UTF_8)
          if str.isEmpty then Map.empty[String, String]
          else
            str
              .split("\n")
              .toList
              .flatMap(_.split("=", 2) match
                case Array(k, v) => Some(k -> v)
                case _           => None)
              .toMap
        }
        .getOrElse(Map.empty[String, String])
      kvRef <- Resource.eval(Ref.of[IO, Map[String, String]](initialKv))
      readsRef <- Resource.eval(
        Ref.of[IO, Map[String, Deferred[IO, Either[RaftError, Long]]]](
          Map.empty
        )
      )
      inbox <- Resource.eval(Queue.unbounded[IO, RawPeerCmd])
      grpcPair <- GrpcTransport.raftResource[IO](
        GrpcConfig(
          host = "127.0.0.1",
          port = port,
          maxRetries = 1,
          retryBackoff = 5.millis,
          maxRetryBackoff = 10.millis
        ),
        TransportConfig(
          localId = id,
          bindAddress = s"127.0.0.1:$port",
          peers = peers - id,
          connectionTimeout = 250.millis
        )
      )
      (rawGrpc, raftTransport) = grpcPair
      addr <- Resource.eval(rawGrpc.localAddress)
      boundPort = addr.split(":").last.toInt
      raw = new RawNode(
        RaftConfig(
          localId = id,
          electionTimeoutTicks = 10,
          heartbeatIntervalTicks = 2,
          preVoteEnabled = true,
          checkQuorumEnabled = true,
          pipelining = PipelineConfig(enabled = true, maxInflight = 6)
        ),
        storage,
        initialSeed = seed
      )
      peer = new SansIoGrpcPeer(
        id,
        boundPort,
        addr,
        rawGrpc,
        raftTransport,
        storage,
        raw,
        inbox,
        kvRef,
        readsRef
      )
      // Background fiber feeding inbound gRPC frames into RawNode.step
      _ <- raftTransport.receive
        .evalMap { case (from, msg) => inbox.offer(RawPeerCmd.Step(from, msg)) }
        .compile
        .drain
        .background
      // Background logical clock ticker (25ms per tick)
      _ <- Stream
        .awakeEvery[IO](25.millis)
        .evalMap(_ => inbox.offer(RawPeerCmd.Tick))
        .compile
        .drain
        .background
      // Main single-threaded Sans-I/O event loop fiber
      _ <- Resource
        .make(peer.runEventLoop.start)(f =>
          inbox.offer(RawPeerCmd.Stop) *> f.cancel
        )
    yield peer

  it should "drive zero-dependency Sans-I/O RawNode over MmapWalEngine (EngineBackedRaftStorage) and live Netty GrpcTransport across compaction and crash recovery" in {
    val baseDir = Files.createTempDirectory("raft-e2e-sansio-grpc-")
    val r1 = NodeId("raw-1")
    val r2 = NodeId("raw-2")
    val r3 = NodeId("raw-3")
    val ids = List(r1, r2, r3)
    val voters = ClusterConfig(ids.toSet)

    val prog = Ref
      .of[IO, Map[NodeId, (SansIoGrpcPeer, IO[Unit])]](Map.empty)
      .flatMap { peersRef =>
        def awaitRawLeader(retries: Int = 400): IO[SansIoGrpcPeer] =
          peersRef.get.flatMap { m =>
            m.values.map(_._1).find(_.isLeaderVolatile) match
              case Some(ldr) => IO.pure(ldr)
              case None      =>
                if retries <= 0 then
                  IO.raiseError(
                    new RuntimeException("No Sans-I/O RawNode leader elected")
                  )
                else IO.sleep(20.millis) *> awaitRawLeader(retries - 1)
          }

        def proposeRawBatch(
            entries: Seq[(String, String)],
            attempts: Int = 25
        ): IO[Unit] =
          val payloads = entries.map((k, v) => utf8(s"$k=$v"))
          awaitRawLeader().flatMap { ldr =>
            ldr.proposeBatch(payloads).flatMap {
              case Right(_)                => IO.unit
              case Left(_) if attempts > 1 =>
                IO.sleep(30.millis) *> proposeRawBatch(entries, attempts - 1)
              case Left(err) => IO.raiseError(err)
            }
          }

        def rawReadIndexWithRetry(
            attempts: Int = 15
        ): IO[(SansIoGrpcPeer, Long)] =
          awaitRawLeader().flatMap { ldr =>
            ldr
              .readIndex(s"sansio-read-$attempts")
              .timeout(2.seconds)
              .flatMap {
                case Right(idx) => IO.pure((ldr, idx))
                case Left(err)  => IO.raiseError(err)
              }
              .handleErrorWith {
                case _ if attempts > 1 =>
                  IO.sleep(35.millis) *> rawReadIndexWithRetry(attempts - 1)
                case err => IO.raiseError(err)
              }
          }

        val scenario = for
          _ <- ids.zipWithIndex.traverse_ { case (id, idx) =>
            allocateSansIoPeer(
              id,
              baseDir.resolve(id.value),
              port = 0,
              peers = Map.empty,
              voters = voters,
              seed = 5000L + idx
            ).allocated.flatMap(pair => peersRef.update(_.updated(id, pair)))
          }
          initMap <- peersRef.get
          addrs = initMap.view.mapValues(_._1.address).toMap
          _ <- initMap.values.toList.traverse_ { case (p, _) =>
            p.grpc.updatePeers(addrs - p.id)
          }

          // 1. Propose 20 entries in 2 batches over RawNode + MmapWalEngine + GrpcTransport
          _ <- proposeRawBatch((1 to 10).map(i => s"rk-$i" -> s"rv-$i"))
          _ <- proposeRawBatch((11 to 20).map(i => s"rk-$i" -> s"rv-$i"))

          expected20 = (1 to 20).map(i => s"rk-$i" -> s"rv-$i").toMap
          _ <- awaitCondition(
            initMap.values.toList.forallM(_._1.currentKv.map(_ == expected20))
          )

          // 2. Confirm linearizable ReadIndex & compact WAL up to index 12 on the RawNode leader
          readPair <- rawReadIndexWithRetry()
          (ldr1, confirmedReadIdx) = readPair
          _ = confirmedReadIdx should be >= 20L
          _ <- ldr1.compactTo(12L)

          // 3. Crash the active RawNode leader!
          crashedId = ldr1.id
          crashedPort = ldr1.port
          crashedPair <- peersRef.modify(m => (m - crashedId, m(crashedId)))
          _ <- crashedPair._2

          // 4. Surviving 2 RawNodes elect a new leader & commit rk-21..rk-30 while crashedId is offline
          _ <- proposeRawBatch((21 to 30).map(i => s"rk-$i" -> s"rv-$i"))
          expected30 =
            expected20 ++ (21 to 30).map(i => s"rk-$i" -> s"rv-$i").toMap

          // 5. Restart crashedId from its on-disk MmapWalEngine (snapshot@12 + tail 13..20) and catch up 21..30 over gRPC!
          restarted <- allocateSansIoPeer(
            crashedId,
            baseDir.resolve(crashedId.value),
            port = crashedPort,
            peers = addrs,
            voters = voters,
            seed = 9999L
          ).allocated
          _ <- peersRef.update(_.updated(crashedId, restarted))

          allThree <- peersRef.get.map(_.values.map(_._1).toList)
          _ <- awaitCondition(
            allThree.forallM(_.currentKv.map(_ == expected30)),
            retries = 450
          )
        yield ()

        scenario.guarantee(
          peersRef.get
            .flatMap(_.values.toList.traverse_(_._2.attempt.void))
            .guarantee(IO(deleteRecursive(baseDir)))
        )
      }

    prog.timeout(90.seconds).unsafeRunSync()
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Scenario 3: On-Disk WIDX (index.ckpt) Corruption Self-Healing in Live Cluster
  // ─────────────────────────────────────────────────────────────────────────

  it should "self-heal a corrupted on-disk WIDX index.ckpt via multi-segment .wal replay and catch up 1,100 entries in a live gRPC cluster" in {
    val baseDir = Files.createTempDirectory("raft-e2e-ckpt-heal-")
    val n1 = NodeId("n1")
    val n2 = NodeId("n2")
    val n3 = NodeId("n3")
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)

    val prog = Ref
      .of[IO, Map[NodeId, (LiveClusterNode, IO[Unit])]](Map.empty)
      .flatMap { nodesRef =>
        val scenario = for
          _ <- ids.traverse_ { id =>
            buildRuntimeNode(
              id = id,
              dataDir = baseDir.resolve(id.value),
              port = 0,
              initialPeers = Map.empty,
              clusterCfg = clusterCfg,
              segmentSize = 16384L,
              compactionThreshold =
                5000L // Keep full WAL segments so index.ckpt >1000 triggers!
            ).allocated.flatMap(pair => nodesRef.update(_.updated(id, pair)))
          }
          initMap <- nodesRef.get
          addrs = initMap.view.mapValues(_._1.address).toMap
          _ <- initMap.values.toList.traverse_ { case (n, _) =>
            n.grpc.updatePeers(addrs - n.id)
          }
          _ <- initMap.values.toList.traverse_(_._1.raftNode.start)

          // 1. Commit 1,050 entries in 21 batches of 50 -> triggers automatic WIDX index.ckpt (>= 1,000 entries)!
          live3 = initMap.values.map(_._1).toList
          _ <- (0 until 21).toList.traverse_ { b =>
            val slice = (1 to 50).map { j =>
              val idx = b * 50 + j
              s"ck-$idx" -> s"val-$idx"
            }
            writeBatchWithRetry(live3, slice)
          }
          _ <- awaitCondition(live3.forallM(_.sm.size.map(_ == 1050)))

          // Pick a follower so we don't even force an extra election, verify index.ckpt exists on disk
          curLeader <- awaitLeader(live3)
          victim = live3.find(_.id != curLeader.id).get
          ckptPath = victim.dataDir.resolve("index.ckpt")
          _ = Files.exists(ckptPath) shouldBe true

          // 2. Shut down victim and intentionally corrupt its index.ckpt payload bytes on disk!
          victimPort = victim.port
          victimDir = victim.dataDir
          victimSaved <- nodesRef.modify(m => (m - victim.id, m(victim.id)))
          _ <- victimSaved._2

          _ <- IO {
            val rawBytes = Files.readAllBytes(ckptPath)
            rawBytes.length should be > 16
            // Flip payload bytes so WIDX CRC32 verification fails on restart!
            for idx <- 8 until math.min(20, rawBytes.length) do
              rawBytes(idx) = (rawBytes(idx) ^ 0xff).toByte
            val _ = Files.write(ckptPath, rawBytes)
          }

          // 3. Commit 50 more entries (1051..1100) on the 2 surviving nodes while victim is offline
          survivors <- nodesRef.get.map(_.values.map(_._1).toList)
          _ <- writeBatchWithRetry(
            survivors,
            (1051 to 1100).map(i => s"ck-$i" -> s"val-$i")
          )

          // 4. Restart victim on its corrupted directory -> MmapWalEngine must discard bad index.ckpt,
          //    replay all .wal segments from disk (1..1050), and catch up 1051..1100 over gRPC!
          restarted <- buildRuntimeNode(
            id = victim.id,
            dataDir = victimDir,
            port = victimPort,
            initialPeers = addrs,
            clusterCfg = clusterCfg,
            segmentSize = 16384L,
            compactionThreshold = 5000L,
            autoStart = true
          ).allocated
          _ <- nodesRef.update(_.updated(victim.id, restarted))

          allNodes <- nodesRef.get.map(_.values.map(_._1).toList)
          _ <- awaitCondition(
            allNodes.forallM(_.sm.size.map(_ == 1100)),
            retries = 450
          )
          restartedMap <- restarted._1.sm.getAll
        yield {
          restartedMap("ck-1") shouldBe "val-1"
          restartedMap("ck-1000") shouldBe "val-1000"
          restartedMap("ck-1100") shouldBe "val-1100"
        }

        scenario.guarantee(
          nodesRef.get
            .flatMap(_.values.toList.traverse_(_._2.attempt.void))
            .guarantee(IO(deleteRecursive(baseDir)))
        )
      }

    prog.timeout(90.seconds).unsafeRunSync()
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Scenario 4: End-to-End Mutual TLS (mTLS) Cluster + Rogue Peer Rejection
  // ─────────────────────────────────────────────────────────────────────────

  private final case class CertBundle(
      caCert: Path,
      nodeCerts: Map[NodeId, (Path, Path)],
      rogueCert: Path,
      rogueKey: Path
  )

  private def generateMtlsPki(pkiDir: Path, nodeIds: List[NodeId]): CertBundle =
    def runCmd(args: Seq[String]): Unit =
      val logger = ProcessLogger(_ => (), _ => ())
      val code = Process(args, pkiDir.toFile).!(logger)
      require(
        code == 0,
        s"openssl command failed ($code): ${args.mkString(" ")}"
      )

    val caKey = pkiDir.resolve("ca.key")
    val caCert = pkiDir.resolve("ca.crt")
    val sanExt = pkiDir.resolve("san.ext")
    val _ = Files.writeString(
      sanExt,
      "subjectAltName=IP:127.0.0.1,DNS:localhost\nextendedKeyUsage=serverAuth,clientAuth\n"
    )

    // 1. Trusted Cluster Root CA
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
        caCert.toString,
        "-days",
        "2",
        "-subj",
        "/CN=RaftClusterRootCA"
      )
    )

    // 2. Per-node PKCS#8 key + CA-signed certificate
    val nodeCerts = nodeIds.map { id =>
      val rawKey = pkiDir.resolve(s"${id.value}-raw.key")
      val pkcs8Key = pkiDir.resolve(s"${id.value}.key")
      val csr = pkiDir.resolve(s"${id.value}.csr")
      val crt = pkiDir.resolve(s"${id.value}.crt")

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
          pkcs8Key.toString
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
          caCert.toString,
          "-CAkey",
          caKey.toString,
          "-CAcreateserial",
          "-out",
          crt.toString,
          "-days",
          "2",
          "-extfile",
          sanExt.toString
        )
      )
      id -> (crt, pkcs8Key)
    }.toMap

    // 3. Separate Untrusted Rogue CA + Rogue Client Certificate
    val rogueRawKey = pkiDir.resolve("rogue-raw.key")
    val rogueKey = pkiDir.resolve("rogue.key")
    val rogueCert = pkiDir.resolve("rogue.crt")
    runCmd(
      Seq(
        "openssl",
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        rogueRawKey.toString,
        "-out",
        rogueCert.toString,
        "-days",
        "2",
        "-subj",
        "/CN=UntrustedRoguePeer"
      )
    )
    runCmd(
      Seq(
        "openssl",
        "pkcs8",
        "-topk8",
        "-nocrypt",
        "-in",
        rogueRawKey.toString,
        "-out",
        rogueKey.toString
      )
    )

    CertBundle(caCert, nodeCerts, rogueCert, rogueKey)

  it should "elect a leader, replicate batches over Mutual TLS (mTLS) Netty HTTP/2, and reject spoofed RPCs from an untrusted certificate" in {
    val baseDir = Files.createTempDirectory("raft-e2e-mtls-")
    val pkiDir = Files.createDirectories(baseDir.resolve("pki"))
    val n1 = NodeId("tls-1")
    val n2 = NodeId("tls-2")
    val n3 = NodeId("tls-3")
    val ids = List(n1, n2, n3)
    val clusterCfg = ClusterConfig(ids.toSet)
    val pki = generateMtlsPki(pkiDir, ids)

    def mtlsConfig(cert: Path, key: Path): GrpcConfig =
      GrpcConfig(
        host = "127.0.0.1",
        port = 0,
        useTls = true,
        tlsCertPath = Some(cert.toString),
        tlsKeyPath = Some(key.toString),
        tlsTrustCertPath = Some(pki.caCert.toString),
        tlsClientAuth = true,
        maxRetries = 1,
        retryBackoff = 10.millis,
        maxRetryBackoff = 20.millis
      )

    val prog = (for
      nodes <- ids.traverse { id =>
        val (cert, key) = pki.nodeCerts(id)
        buildRuntimeNode(
          id = id,
          dataDir = baseDir.resolve(id.value),
          port = 0,
          initialPeers = Map.empty,
          clusterCfg = clusterCfg,
          grpcBaseCfg = Some(mtlsConfig(cert, key))
        )
      }
      addrs = nodes.map(n => n.id -> n.address).toMap
      _ <- Resource.eval(
        nodes.traverse_(n => n.grpc.updatePeers(addrs - n.id)) *>
          nodes.traverse_(_.raftNode.start)
      )

      // Spin up an untrusted rogue client presenting rogue.crt/rogue.key
      roguePair <- GrpcTransport.raftResource[IO](
        GrpcConfig(
          host = "127.0.0.1",
          port = 0,
          useTls = true,
          tlsCertPath = Some(pki.rogueCert.toString),
          tlsKeyPath = Some(pki.rogueKey.toString),
          tlsTrustCertPath = Some(pki.caCert.toString),
          tlsClientAuth = true,
          maxRetries = 0
        ),
        TransportConfig(
          localId = NodeId("rogue"),
          bindAddress = "127.0.0.1:0",
          peers = addrs,
          connectionTimeout = 400.millis
        )
      )
      (_, rogueRaftTransport) = roguePair

      _ <- Resource.eval {
        for
          // 1. Replicate 20 entries over mTLS-encrypted gRPC + MmapWalEngine
          _ <- writeBatchWithRetry(
            nodes,
            (1 to 20).map(i => s"tls-k$i" -> s"secret-$i")
          )
          expected = (1 to 20).map(i => s"tls-k$i" -> s"secret-$i").toMap
          _ <- awaitCondition(nodes.forallM(_.sm.getAll.map(_ == expected)))

          leader <- awaitLeader(nodes)
          legitTerm <- leader.raftNode.getState.map(_.term)
          rIdx <- leader.raftNode.readIndex.timeout(4.seconds)
          _ = rIdx should be >= 20L

          // 2. Rogue peer attempts to inject a spoofed term=9999 AppendEntriesRequest to dethrone the mTLS leader!
          _ <- rogueRaftTransport.send(
            leader.id,
            AppendEntriesRequest(
              term = 9999L,
              leaderId = NodeId("rogue"),
              prevLogIndex = 0L,
              prevLogTerm = 0L,
              entries = Seq.empty,
              leaderCommit = 0L
            )
          )
          _ <- IO.sleep(150.millis)

          // mTLS ClientAuth.REQUIRE rejected the TLS handshake -> leader remains undisturbed at legitTerm!
          stateAfterAttack <- leader.raftNode.getState
        yield {
          stateAfterAttack shouldBe a[Leader]
          stateAfterAttack.term shouldBe legitTerm
        }
      }
    yield ()).use_.guarantee(IO(deleteRecursive(baseDir)))

    prog.timeout(90.seconds).unsafeRunSync()
  }
