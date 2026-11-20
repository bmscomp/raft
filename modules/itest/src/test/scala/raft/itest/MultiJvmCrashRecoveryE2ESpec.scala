package raft.itest

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*
import scala.sys.process.{Process as SProcess, ProcessLogger}

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Random
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

/** Standalone child-JVM worker entrypoint spawned via
  * `java.lang.ProcessBuilder` by [[MultiJvmCrashRecoveryE2ESpec]].
  *
  * Boots a real `MmapWalEngine[IO]` + mTLS `GrpcTransport.raftResource[IO]`
  * (`tlsClientAuth = true`, `verifyPeerIdentity = true`) + typed
  * `RaftNode[IO, String]`, exposing a lightweight line-oriented loopback
  * control socket for black-box multi-process client operations.
  */
object MultiJvmPeerWorker:

  final class WorkerKvStateMachine(ref: Ref[IO, Map[String, String]])
      extends StateMachine[IO, String]:
    def apply(log: Log): IO[String] =
      val str = log.payload.utf8String
      str.split("\t", -1).toList match
        case "PUT" :: k :: v :: Nil =>
          ref.update(_.updated(k, v)).as("OK")
        case "CAS" :: k :: exp :: next :: Nil =>
          ref.modify { m =>
            if m.get(k).contains(exp) then (m.updated(k, next), "CAS_OK")
            else (m, "CAS_FAIL")
          }
        case _ => IO.pure("IGNORED")

    def snapshot: IO[Stream[IO, Byte]] =
      ref.get.map { m =>
        val raw = m.toSeq
          .sortBy(_._1)
          .map { case (k, v) => s"$k=$v" }
          .mkString("\n")
          .getBytes(StandardCharsets.UTF_8)
        Stream.chunk(Chunk.array(raw)).covary[IO]
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
              .flatMap { line =>
                line.split("=", 2) match
                  case Array(k, v) if k.nonEmpty => Some(k -> v)
                  case _                         => None
              }
              .toMap
        ref.set(parsed)
      }

    def get(k: String): IO[Option[String]] = ref.get.map(_.get(k))
    def getAll: IO[Map[String, String]] = ref.get

  def main(args: Array[String]): Unit =
    val localId = NodeId(args(0))
    val raftPort = args(1).toInt
    val cmdPort = args(2).toInt
    val dataDir = Paths.get(args(3))
    val caCert = args(4)
    val nodeCert = args(5)
    val nodeKey = args(6)
    val allPeers: Map[NodeId, String] = args(7)
      .split(",")
      .toList
      .filter(_.nonEmpty)
      .map { pair =>
        val Array(id, addr) = pair.split("=", 2)
        NodeId(id) -> addr
      }
      .toMap

    val clusterCfg = ClusterConfig(allPeers.keySet)
    val grpcCfg = GrpcConfig(
      host = "127.0.0.1",
      port = raftPort,
      useTls = true,
      tlsCertPath = Some(nodeCert),
      tlsKeyPath = Some(nodeKey),
      tlsTrustCertPath = Some(caCert),
      tlsClientAuth = true,
      verifyPeerIdentity = true,
      maxRetries = 2,
      retryBackoff = 10.millis,
      maxRetryBackoff = 40.millis
    )
    val tpCfg = TransportConfig(
      localId = localId,
      bindAddress = s"127.0.0.1:$raftPort",
      peers = allPeers - localId,
      connectionTimeout = 400.millis
    )
    val compactionCfg = CompactionConfig(
      level = CompactionLevel.Custom,
      snapshotChunkSize = 512,
      retainedEntries = 2L,
      policy = Some(CompactionPolicy.threshold[IO](18L))
    )

    val runServer: IO[Unit] = (for
      engine <- MmapWalEngine.resource[IO](dataDir, segmentSize = 4096L)
      grpcPair <- GrpcTransport.raftResource[IO](grpcCfg, tpCfg)
      smRef <- Resource.eval(Ref.of[IO, Map[String, String]](Map.empty))
      sm = new WorkerKvStateMachine(smRef)
      timer <- Resource.eval(DefaultTimerService[IO])
      rn <- RaftNode[IO](
        config = RaftConfig(
          localId = localId,
          electionTimeoutMin = 240.millis,
          electionTimeoutMax = 460.millis,
          heartbeatInterval = 45.millis,
          preVoteEnabled = true,
          checkQuorumEnabled = true,
          leaseReadEnabled = true,
          leaderLeaseDuration = 140.millis,
          batching =
            BatchConfig(enabled = true, maxSize = 32, maxWait = 3.millis),
          pipelining = PipelineConfig(enabled = true, maxInflight = 6),
          compaction = compactionCfg
        ),
        cluster = clusterCfg,
        transport = grpcPair._2,
        logStore = StorageBackedLogStore(engine),
        stableStore = StorageBackedStableStore(engine),
        stateMachine = sm,
        timerService = timer,
        snapshotStoreOpt = Some(StorageBackedSnapshotStore(engine))
      )
      _ <- Resource.eval(rn.start)
      cmdServer <- Resource.make(
        IO.blocking(
          new ServerSocket(cmdPort, 64, InetAddress.getByName("127.0.0.1"))
        )
      )(srv => IO.blocking(srv.close()).attempt.void)
    yield (engine, rn, sm, cmdServer)).use { case (engine, rn, sm, cmdServer) =>
      for
        stopSignal <- cats.effect.Deferred[IO, Unit]
        _ <- IO {
          println(s"READY:${localId.value}:$cmdPort")
          System.out.flush()
        }
        acceptLoop: IO[Unit] = IO
          .blocking(cmdServer.accept())
          .flatMap { sock =>
            handleClientSocket(sock, rn, sm, engine, stopSignal).start.void
          }
          .foreverM
        _ <- IO.race(stopSignal.get, acceptLoop)
      yield ()
    }

    runServer.unsafeRunSync()

  private def handleClientSocket(
      sock: Socket,
      rn: RaftNode[IO, String],
      sm: WorkerKvStateMachine,
      engine: MmapWalEngine[IO],
      stopSignal: cats.effect.Deferred[IO, Unit]
  ): IO[Unit] =
    Resource
      .fromAutoCloseable(IO.pure(sock))
      .use { s =>
        IO.blocking {
          val in = new BufferedReader(
            new InputStreamReader(s.getInputStream, StandardCharsets.UTF_8)
          )
          val out =
            new PrintWriter(s.getOutputStream, true, StandardCharsets.UTF_8)
          Option(in.readLine()) -> out
        }.flatMap {
          case (None, _)         => IO.unit
          case (Some(line), out) =>
            val parts = line.split("\t", -1).toList
            val replyIO: IO[String] = parts match
              case "STATUS" :: Nil =>
                for
                  st <- rn.getState
                  m <- sm.getAll
                  snaps <- engine.listSnapshots
                  role = st match
                    case _: Leader => "LEADER"
                    case _         => "FOLLOWER"
                yield s"$role\t${st.term}\t${m.size}\t${snaps.size}"

              case "PUT" :: k :: v :: Nil =>
                rn.proposeAndApply(Bytes.fromUtf8(s"PUT\t$k\t$v"))
                  .timeout(3.seconds)
                  .map(a =>
                    s"OK\t${a.logId.term}\t${a.logId.index}\t${a.value}"
                  )
                  .handleError {
                    case RaftError.NotLeader(_) | RaftError.ElectionInProgress |
                        RaftError.LeadershipTransferInProgress(_) =>
                      "NOT_LEADER"
                    case other =>
                      s"ERR\t${Option(other.getMessage).getOrElse(other.getClass.getSimpleName)}"
                  }

              case "CAS" :: k :: exp :: next :: Nil =>
                rn.proposeAndApply(Bytes.fromUtf8(s"CAS\t$k\t$exp\t$next"))
                  .timeout(3.seconds)
                  .map(a => s"OK\t${a.value}")
                  .handleError {
                    case RaftError.NotLeader(_) | RaftError.ElectionInProgress |
                        RaftError.LeadershipTransferInProgress(_) =>
                      "NOT_LEADER"
                    case other =>
                      s"ERR\t${Option(other.getMessage).getOrElse(other.getClass.getSimpleName)}"
                  }

              case "GET" :: k :: Nil =>
                val confirm = rn.leaseRead.flatMap {
                  case Some(idx) => IO.pure(idx)
                  case None      => rn.readIndex
                }
                confirm
                  .flatMap(_ => sm.get(k))
                  .timeout(2.seconds)
                  .map {
                    case Some(v) => s"FOUND\t$v"
                    case None    => "MISSING"
                  }
                  .handleError {
                    case RaftError.NotLeader(_) | RaftError.ElectionInProgress |
                        RaftError.LeaderNotReadyInTerm(_) |
                        RaftError.LeadershipTransferInProgress(_) =>
                      "NOT_LEADER"
                    case other =>
                      s"ERR\t${Option(other.getMessage).getOrElse(other.getClass.getSimpleName)}"
                  }

              case "DUMP" :: Nil =>
                sm.getAll.map { m =>
                  m.toSeq
                    .sortBy(_._1)
                    .map { case (k, v) => s"$k=$v" }
                    .mkString(",")
                }

              case "SHUTDOWN" :: Nil =>
                stopSignal.complete(()).as("BYE")

              case _ => IO.pure("UNKNOWN")

            replyIO.flatMap(resp => IO.blocking(out.println(resp)))
        }
      }
      .handleError(_ => ())

/** Phase 5 Multi-JVM `SIGKILL -9` Crash-Recovery & Full-Stack `ReadIndex`
  * Jepsen E2E Suite (**fixes E2E1, E2E3**).
  */
class MultiJvmCrashRecoveryE2ESpec extends AnyFlatSpec with Matchers:

  private def deleteRecursive(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val s = Files.list(path)
        try s.iterator().forEachRemaining(deleteRecursive)
        finally s.close()
      val _ = Files.deleteIfExists(path)

  private def reserveFreePorts(count: Int): Vector[Int] =
    val sockets = Vector.fill(count)(new ServerSocket(0))
    val ports = sockets.map(_.getLocalPort)
    sockets.foreach(_.close())
    ports

  private def generateClusterPki(
      pkiDir: Path,
      ids: List[NodeId]
  ): (Path, Map[NodeId, (Path, Path)]) =
    def runCmd(args: Seq[String]): Unit =
      val log = ProcessLogger(_ => (), _ => ())
      val code = SProcess(args, pkiDir.toFile).!(log)
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
        "/CN=MultiJvmRaftCA"
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
        s"subjectAltName=IP:127.0.0.1,DNS:localhost,URI:spiffe://raft.local/ns/itest/sa/${id.value}\nextendedKeyUsage=serverAuth,clientAuth\n"
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

  private final case class ChildJvm(
      id: NodeId,
      raftPort: Int,
      cmdPort: Int,
      dataDir: Path,
      process: Process
  )

  private def spawnWorkerJvm(
      id: NodeId,
      raftPort: Int,
      cmdPort: Int,
      dataDir: Path,
      caCert: Path,
      nodeCert: Path,
      nodeKey: Path,
      peerSpec: String
  ): IO[ChildJvm] =
    IO.blocking {
      val javaBin = ProcessHandle
        .current()
        .info()
        .command()
        .orElse("java")
      val classPath = System.getProperty("java.class.path")
      val pb = new ProcessBuilder(
        javaBin,
        "--enable-native-access=ALL-UNNAMED",
        "-Xms64m",
        "-Xmx256m",
        "-cp",
        classPath,
        "raft.itest.MultiJvmPeerWorker",
        id.value,
        raftPort.toString,
        cmdPort.toString,
        dataDir.toString,
        caCert.toString,
        nodeCert.toString,
        nodeKey.toString,
        peerSpec
      )
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val reader = new BufferedReader(
        new InputStreamReader(proc.getInputStream, StandardCharsets.UTF_8)
      )
      val bootLines = scala.collection.mutable.ListBuffer.empty[String]
      @scala.annotation.tailrec
      def awaitReadyBanner(): Option[String] =
        val line = reader.readLine()
        if line == null then None
        else if line.startsWith("READY:") then Some(line)
        else
          bootLines += line
          awaitReadyBanner()

      if awaitReadyBanner().isEmpty then
        proc.destroyForcibly()
        throw new RuntimeException(
          s"Worker JVM ${id.value} failed to start; output:\n${bootLines.mkString("\n")}"
        )
      // Drain remaining worker stdout asynchronously so pipe buffer never blocks
      val drainThread = new Thread(() => {
        try while reader.readLine() != null do ()
        catch case _: Throwable => ()
      })
      drainThread.setDaemon(true)
      drainThread.start()
      ChildJvm(id, raftPort, cmdPort, dataDir, proc)
    }.timeout(20.seconds)

  private def sendWorkerCmd(cmdPort: Int, command: String): IO[String] =
    IO.blocking {
      val sock = new Socket()
      try
        val connected =
          try
            sock.connect(new InetSocketAddress("127.0.0.1", cmdPort), 1000)
            true
          catch case _: java.net.ConnectException => false
        if !connected then "NOT_CONNECTED"
        else
          sock.setSoTimeout(4000)
          val out =
            new PrintWriter(sock.getOutputStream, true, StandardCharsets.UTF_8)
          val in = new BufferedReader(
            new InputStreamReader(sock.getInputStream, StandardCharsets.UTF_8)
          )
          out.println(command)
          Option(in.readLine()).getOrElse("ERR\tEOF")
      finally sock.close()
    }.handleError(e => s"ERR\t${e.getClass.getSimpleName}")

  private def awaitJvmLeader(
      procs: List[ChildJvm],
      retries: Int = 300
  ): IO[(ChildJvm, Long)] =
    procs
      .traverse { p =>
        sendWorkerCmd(p.cmdPort, "STATUS").map { resp =>
          resp.split("\t").toList match
            case "LEADER" :: termStr :: _ =>
              termStr.toLongOption.map(t => (p, t))
            case _ => None
        }
      }
      .flatMap { opts =>
        opts.flatten.sortBy(-_._2).headOption match
          case Some(found) => IO.pure(found)
          case None        =>
            if retries <= 0 then
              IO.raiseError(
                new RuntimeException("No leader elected across child JVMs")
              )
            else IO.sleep(25.millis) *> awaitJvmLeader(procs, retries - 1)
      }

  private def putWithRetry(
      procsRef: Ref[IO, Map[NodeId, ChildJvm]],
      k: String,
      v: String,
      attempts: Int = 30
  ): IO[Unit] =
    procsRef.get.flatMap { m =>
      awaitJvmLeader(m.values.toList).flatMap { case (ldr, _) =>
        sendWorkerCmd(ldr.cmdPort, s"PUT\t$k\t$v").flatMap {
          case resp if resp.startsWith("OK\t") => IO.unit
          case _ if attempts > 1               =>
            IO.sleep(35.millis) *> putWithRetry(procsRef, k, v, attempts - 1)
          case other =>
            IO.raiseError(
              new RuntimeException(s"Multi-JVM PUT failed: $other")
            )
        }
      }
    }

  "Multi-JVM SIGKILL (-9) Crash-Recovery Suite" should
    "survive abrupt destroyForcibly() of the active leader JVM mid-workload over mTLS gRPC + MmapWalEngine and preserve WGL linearizability (E2E3)" in {
      val baseDir = Files.createTempDirectory("raft-multijvm-sigkill-")
      val pkiDir = baseDir.resolve("pki")
      val _ = Files.createDirectories(pkiDir)
      val ids = List(NodeId("n1"), NodeId("n2"), NodeId("n3"))
      val ports = reserveFreePorts(6)
      val raftPorts = ids.zip(ports.take(3)).toMap
      val cmdPorts = ids.zip(ports.drop(3)).toMap
      val peerSpec = ids
        .map(id => s"${id.value}=127.0.0.1:${raftPorts(id)}")
        .mkString(",")
      val (caCrt, nodeCerts) = generateClusterPki(pkiDir, ids)

      val opSeq = new AtomicLong(0L)

      val prog =
        Ref.of[IO, Map[NodeId, ChildJvm]](Map.empty).flatMap { procsRef =>
          val scenario = for
            // 1. Spawn 3 independent OS child JVMs over mTLS HTTP/2 + MmapWalEngine
            _ <- ids.traverse_ { id =>
              val (crt, key) = nodeCerts(id)
              val dir = baseDir.resolve(id.value)
              spawnWorkerJvm(
                id = id,
                raftPort = raftPorts(id),
                cmdPort = cmdPorts(id),
                dataDir = dir,
                caCert = caCrt,
                nodeCert = crt,
                nodeKey = key,
                peerSpec = peerSpec
              ).flatMap(child => procsRef.update(_.updated(id, child)))
            }

            initMap <- procsRef.get
            firstLdrPair <- awaitJvmLeader(initMap.values.toList)
            firstTerm = firstLdrPair._2

            // 2. Seed 22 baseline entries (crossing snapshot compaction threshold = 18!)
            _ <- (1 to 22).toList.traverse_ { i =>
              putWithRetry(procsRef, s"seed-$i", s"v-$i")
            }

            // 3. Launch 3 concurrent WGL client fibers executing PUT, CAS, and linearizable GET while we SIGKILL -9 the leader!
            opsRef <- Ref.of[IO, Vector[Operation]](Vector.empty)
            stopClients <- Ref.of[IO, Boolean](false)

            runClient = (cid: Int) =>
              for
                rnd <- Random.scalaUtilRandom[IO]
                leaderHintRef <- Ref.of[IO, Option[NodeId]](
                  Some(firstLdrPair._1.id)
                )
                callCluster = (command: String) => {
                  def loop(rem: Int): IO[Option[String]] =
                    for
                      liveMap <- procsRef.get
                      hintOpt <- leaderHintRef.get
                      targetOpt <- hintOpt.flatMap(liveMap.get) match
                        case some @ Some(_) => IO.pure(some)
                        case None           =>
                          val vec = liveMap.values.toVector
                          if vec.isEmpty then IO.pure(None)
                          else
                            rnd.betweenInt(0, vec.size).map(i => Some(vec(i)))
                      res <- targetOpt match
                        case None         => IO.pure(None)
                        case Some(target) =>
                          sendWorkerCmd(target.cmdPort, command).flatMap {
                            case "NOT_LEADER" | "NOT_CONNECTED" =>
                              leaderHintRef.set(None) *> (
                                if rem <= 0 then IO.pure(None)
                                else IO.sleep(15.millis) *> loop(rem - 1)
                              )
                            case err if err.startsWith("ERR\t") =>
                              leaderHintRef.set(None).as(Some(err))
                            case ok =>
                              leaderHintRef.set(Some(target.id)).as(Some(ok))
                          }
                    yield res
                  loop(35)
                }
                _ <- {
                  def step: IO[Unit] =
                    stopClients.get.flatMap {
                      case true  => IO.unit
                      case false =>
                        for
                          keyIdx <- rnd.betweenInt(0, 3)
                          key = s"wgl-k$keyIdx"
                          roll <- rnd.betweenInt(0, 10)
                          opId = opSeq.incrementAndGet()
                          t0 <- IO.monotonic.map(_.toNanos)
                          _ <-
                            if roll < 4 then
                              val v = s"c$cid-$opId"
                              val inv = Operation(
                                opId,
                                cid,
                                OpType.Invoke,
                                OpFunction.Write,
                                key,
                                Some(v),
                                None,
                                t0
                              )
                              opsRef.update(_ :+ inv) *>
                                callCluster(s"PUT\t$key\t$v").flatMap {
                                  case None =>
                                    opsRef.update(_.filterNot(_.index == opId))
                                  case Some(resp) =>
                                    IO.monotonic.map(_.toNanos).flatMap { t1 =>
                                      val doneOp =
                                        if resp.startsWith("OK\t") then
                                          Operation(
                                            opId,
                                            cid,
                                            OpType.Ok,
                                            OpFunction.Write,
                                            key,
                                            Some(v),
                                            Some("ok"),
                                            t1
                                          )
                                        else
                                          Operation(
                                            opId,
                                            cid,
                                            OpType.Info,
                                            OpFunction.Write,
                                            key,
                                            Some(v),
                                            None,
                                            t1
                                          )
                                      opsRef.update(_ :+ doneOp)
                                    }
                                }
                            else if roll < 7 then
                              val inv = Operation(
                                opId,
                                cid,
                                OpType.Invoke,
                                OpFunction.Read,
                                key,
                                None,
                                None,
                                t0
                              )
                              opsRef.update(_ :+ inv) *>
                                callCluster(s"GET\t$key").flatMap {
                                  case None =>
                                    opsRef.update(_.filterNot(_.index == opId))
                                  case Some(resp) =>
                                    IO.monotonic.map(_.toNanos).flatMap { t1 =>
                                      val doneOp =
                                        if resp.startsWith("FOUND\t") then
                                          Operation(
                                            opId,
                                            cid,
                                            OpType.Ok,
                                            OpFunction.Read,
                                            key,
                                            None,
                                            Some(
                                              Some(resp.stripPrefix("FOUND\t"))
                                            ),
                                            t1
                                          )
                                        else if resp == "MISSING" then
                                          Operation(
                                            opId,
                                            cid,
                                            OpType.Ok,
                                            OpFunction.Read,
                                            key,
                                            None,
                                            Some(None),
                                            t1
                                          )
                                        else
                                          Operation(
                                            opId,
                                            cid,
                                            OpType.Info,
                                            OpFunction.Read,
                                            key,
                                            None,
                                            None,
                                            t1
                                          )
                                      opsRef.update(_ :+ doneOp)
                                    }
                                }
                            else
                              val exp = s"c$cid-${opId - 1}"
                              val next = s"cas-$cid-$opId"
                              val inv = Operation(
                                opId,
                                cid,
                                OpType.Invoke,
                                OpFunction.CAS,
                                key,
                                Some((exp, next)),
                                None,
                                t0
                              )
                              opsRef.update(_ :+ inv) *>
                                callCluster(s"CAS\t$key\t$exp\t$next").flatMap {
                                  case None =>
                                    opsRef.update(_.filterNot(_.index == opId))
                                  case Some(resp) =>
                                    IO.monotonic.map(_.toNanos).flatMap { t1 =>
                                      val doneOp =
                                        if resp == "OK\tCAS_OK" then
                                          Operation(
                                            opId,
                                            cid,
                                            OpType.Ok,
                                            OpFunction.CAS,
                                            key,
                                            Some((exp, next)),
                                            Some("ok"),
                                            t1
                                          )
                                        else if resp == "OK\tCAS_FAIL" then
                                          Operation(
                                            opId,
                                            cid,
                                            OpType.Fail,
                                            OpFunction.CAS,
                                            key,
                                            Some((exp, next)),
                                            Some("failed"),
                                            t1
                                          )
                                        else
                                          Operation(
                                            opId,
                                            cid,
                                            OpType.Info,
                                            OpFunction.CAS,
                                            key,
                                            Some((exp, next)),
                                            None,
                                            t1
                                          )
                                      opsRef.update(_ :+ doneOp)
                                    }
                                }
                          _ <- IO.sleep(15.millis)
                          _ <- step
                        yield ()
                    }
                  step
                }
              yield ()

            clientsFiber <- (1 to 3).toList.parTraverse_(runClient).start
            _ <- IO.sleep(250.millis)

            // 4. Identify current leader JVM and kill it abruptly with SIGKILL -9 (destroyForcibly)!
            liveBeforeKill <- procsRef.get
            victimPair <- awaitJvmLeader(liveBeforeKill.values.toList)
            victim = victimPair._1
            _ <- procsRef.update(_ - victim.id)
            _ <- IO.blocking {
              val _ = victim.process.destroyForcibly()
              val _ = victim.process.waitFor(5, TimeUnit.SECONDS)
            }
            _ = victim.process.isAlive shouldBe false

            // 5. Surviving 2 child JVMs MUST elect a failover leader while WGL clients keep running!
            survivors <- procsRef.get.map(_.values.toList)
            failoverPair <- awaitJvmLeader(survivors)
            (_, failoverTerm) = failoverPair
            _ = failoverTerm should be > firstTerm
            _ <- IO.sleep(300.millis)

            // 6. Respawn a fresh child JVM on victim's UNCLOSED MmapWalEngine dataDir + mTLS identity!
            (vCrt, vKey) = nodeCerts(victim.id)
            reborn <- spawnWorkerJvm(
              id = victim.id,
              raftPort = victim.raftPort,
              cmdPort = victim.cmdPort,
              dataDir = victim.dataDir,
              caCert = caCrt,
              nodeCert = vCrt,
              nodeKey = vKey,
              peerSpec = peerSpec
            )
            _ <- procsRef.update(_.updated(victim.id, reborn))
            _ <- IO.sleep(250.millis)
            _ <- stopClients.set(true)
            _ <- clientsFiber.joinWithNever

            // 7. Write final barrier key and assert all 3 child JVMs converge to identical state!
            _ <- putWithRetry(procsRef, "post-sigkill-barrier", "converged")
            allThree <- procsRef.get.map(_.values.toList.sortBy(_.id.value))
            dumps <- {
              def pollConverged(rem: Int): IO[List[String]] =
                allThree
                  .traverse(p => sendWorkerCmd(p.cmdPort, "DUMP"))
                  .flatMap { ds =>
                    val ok =
                      ds.distinct.size == 1 &&
                        ds.head.contains("post-sigkill-barrier=converged") &&
                        ds.head.contains("seed-1=v-1")
                    if ok then IO.pure(ds)
                    else if rem <= 0 then
                      IO.raiseError(
                        new RuntimeException(
                          s"Child JVMs failed to converge:\n${ds.mkString("\n")}"
                        )
                      )
                    else IO.sleep(30.millis) *> pollConverged(rem - 1)
                  }
              pollConverged(300)
            }

            ops <- opsRef.get
            wgl = LinearizabilityChecker.check(History(ops), KVModel)
          yield
            dumps.distinct should have size 1
            withClue(s"WGL linearizability violation: ${wgl.violation}") {
              wgl.linearizable shouldBe true
            }

          scenario.guarantee(
            procsRef.get
              .flatMap { m =>
                m.values.toList.traverse_ { child =>
                  sendWorkerCmd(child.cmdPort, "SHUTDOWN").attempt *>
                    IO.blocking {
                      if !child.process.waitFor(2, TimeUnit.SECONDS) then
                        val _ = child.process.destroyForcibly()
                        val _ = child.process.waitFor(3, TimeUnit.SECONDS)
                    }.attempt
                      .void
                }
              }
              .guarantee(IO(deleteRecursive(baseDir)))
          )
        }

      prog.timeout(90.seconds).unsafeRunSync()
    }
