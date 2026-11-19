package examples.distributed

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream

import raft.impl.{DefaultTimerService, InMemLogStore, InMemStableStore}
import raft.multigroup.*
import raft.spi.StateMachine
import raft.state.*
import raft.state.NodeState.Leader

/** Demonstrates the Multi-Raft Group architecture across a real 3-node cluster.
  *
  * Creates a 3-host sharded key-value store where keys are automatically
  * partitioned across independent Raft consensus groups:
  *   - '''shard-0''': keys starting with `"a"`–`"m"`
  *   - '''shard-1''': keys starting with `"n"`–`"z"`
  *
  * Each shard runs its own leader election, log replication, linearizable
  * reads, and state machine independently while sharing a single multiplexed
  * [[MultiGroupTransport]] with per-host heartbeat coalescing.
  */
object MultiGroupExample extends IOApp.Simple:

  private val shard0 = GroupId("shard-0")
  private val shard1 = GroupId("shard-1")
  private val shard2 = GroupId("shard-2")

  final class ShardMapStateMachine(ref: Ref[IO, Map[String, String]])
      extends StateMachine[IO, Unit]:
    def apply(log: Log): IO[Unit] =
      val str = new String(log.data, StandardCharsets.UTF_8)
      str.split("=", 2) match
        case Array(k, v) => ref.update(_.updated(k, v))
        case _           => IO.unit

    def snapshot: IO[Stream[IO, Byte]] =
      ref.get.map { m =>
        Stream
          .emits(
            m.toSeq
              .sortBy(_._1)
              .map((k, v) => s"$k=$v")
              .mkString("\n")
              .getBytes(StandardCharsets.UTF_8)
          )
          .covary[IO]
      }

    def restore(data: Stream[IO, Byte]): IO[Unit] =
      data.compile.to(Array).flatMap { bytes =>
        val str = new String(bytes, StandardCharsets.UTF_8)
        val parsed =
          if str.isEmpty then Map.empty[String, String]
          else
            str
              .split("\n")
              .flatMap(_.split("=", 2) match
                case Array(k, v) => Some(k -> v)
                case _           => None)
              .toMap
        ref.set(parsed)
      }

    def getAll: IO[Map[String, String]] = ref.get

  override def run: IO[Unit] = runClusterDemo.void

  def shardForKey(key: String): GroupId =
    if key.head.toLower <= 'm' then shard0 else shard1

  def runClusterDemo: IO[(Map[String, String], Map[String, String])] =
    val n1 = NodeId("node1")
    val n2 = NodeId("node2")
    val n3 = NodeId("node3")
    val nodeIds = List(n1, n2, n3)
    val voters = nodeIds.toSet

    MultiGroupTransport
      .createInMemoryCluster[IO](
        nodeIds,
        coalescing = CoalescedHeartbeatConfig(
          enabled = true,
          flushInterval = 10.millis,
          maxBatchSize = 16
        )
      )
      .use { transports =>
        for
          _ <- IO.println("═" * 70)
          _ <- IO.println("  MULTI-RAFT GROUP EXAMPLE")
          _ <- IO.println(
            "  3-Node Sharded Key-Value Store with Coalesced Heartbeats"
          )
          _ <- IO.println("═" * 70)

          // Step 1: Multiplexed transport cluster with coalesced heartbeats
          _ <- IO.println("\nStep 1: Created 3-node MultiGroupTransport mesh")

          // Step 2: Boot MultiRaftNode coordinators on all 3 hosts
          _ <- IO.println("Step 2: Starting MultiRaftNode coordinators...")
          coordinators <- nodeIds
            .traverse { id =>
              MultiRaftNode[IO](id, transports(id))
                .flatTap(_.start)
                .map(id -> _)
            }
            .map(_.toMap)

          // Step 3: Provision shard-0 and shard-1 across all 3 hosts
          _ <- IO.println(
            "Step 3: Provisioning shard-0 (a-m) and shard-1 (n-z)..."
          )
          sms <- List(shard0, shard1)
            .flatMap(gid => nodeIds.map(nid => (gid, nid)))
            .traverse { case (gid, nid) =>
              for
                sm <- Ref
                  .of[IO, Map[String, String]](Map.empty)
                  .map(new ShardMapStateMachine(_))
                cfg <- mkShardConfig(nid, voters, sm)
                _ <- coordinators(nid).createGroup(gid, cfg)
              yield (gid, nid) -> sm
            }
            .map(_.toMap)

          groupsOnN1 <- coordinators(n1).listGroups
          _ <- IO.println(
            s"   Active groups on ${n1.value}: ${groupsOnN1.map(_.value).toList.sorted.mkString(", ")}"
          )

          // Step 4: Route & commit sharded writes through each shard's elected leader
          _ <- IO.println("\nStep 4: Routing & committing sharded KV writes...")
          _ <- putSharded(coordinators, nodeIds, "alice", "value-a")
          _ <- putSharded(coordinators, nodeIds, "bob", "value-b")
          _ <- putSharded(coordinators, nodeIds, "nancy", "value-n")
          _ <- putSharded(coordinators, nodeIds, "zara", "value-z")

          // Step 5: Linearizable ReadIndex verification on each shard
          rIdx0 <- readIndexWithRetry(coordinators, nodeIds, shard0)
          rIdx1 <- readIndexWithRetry(coordinators, nodeIds, shard1)
          _ <- IO.println(
            s"\nStep 5: Confirmed linearizable ReadIndex: shard-0=$rIdx0, shard-1=$rIdx1"
          )

          // Step 6: Dynamic group lifecycle (add & remove shard-2)
          _ <- IO.println(
            "\nStep 6: Dynamic group lifecycle (add & remove shard-2)..."
          )
          _ <- nodeIds.traverse_ { nid =>
            for
              sm2 <- Ref
                .of[IO, Map[String, String]](Map.empty)
                .map(new ShardMapStateMachine(_))
              cfg2 <- mkShardConfig(nid, voters, sm2)
              _ <- coordinators(nid).createGroup(shard2, cfg2)
            yield ()
          }
          afterAdd <- coordinators(n1).listGroups
          _ <- IO.println(
            s"   Groups after adding shard-2: ${afterAdd.map(_.value).toList.sorted.mkString(", ")}"
          )
          _ <- nodeIds.traverse_(nid => coordinators(nid).removeGroup(shard2))
          afterRemove <- coordinators(n1).listGroups
          _ <- IO.println(
            s"   Groups after removing shard-2: ${afterRemove.map(_.value).toList.sorted.mkString(", ")}"
          )

          // Wait for all 3 replicas of shard-0 and shard-1 to converge
          _ <- awaitConverged(
            nodeIds.forallM(nid => sms((shard0, nid)).getAll.map(_.size == 2))
          )
          _ <- awaitConverged(
            nodeIds.forallM(nid => sms((shard1, nid)).getAll.map(_.size == 2))
          )
          s0Data <- sms((shard0, n1)).getAll
          s1Data <- sms((shard1, n1)).getAll

          // Step 7: Graceful shutdown
          _ <- IO.println("\nStep 7: Shutting down all MultiRaftNode hosts...")
          _ <- nodeIds.traverse_(nid => coordinators(nid).shutdown)
          _ <- IO.println(
            s"   Final shard-0=$s0Data | shard-1=$s1Data\n✅ Multi-Raft example complete!"
          )
        yield (s0Data, s1Data)
      }

  private def awaitShardLeader(
      coordinators: Map[NodeId, MultiRaftNode[IO]],
      nodeIds: List[NodeId],
      gid: GroupId,
      retries: Int = 250
  ): IO[NodeId] =
    nodeIds
      .traverse(id => coordinators(id).getGroupState(gid).map(id -> _))
      .flatMap { pairs =>
        val leaders = pairs.collect { case (id, Some(l: Leader)) =>
          (id, l.term)
        }
        leaders.sortBy(-_._2).headOption match
          case Some((id, _)) => IO.pure(id)
          case None          =>
            if retries <= 0 then
              IO.raiseError(
                new RuntimeException(s"No leader elected for ${gid.value}")
              )
            else
              IO.sleep(15.millis) *>
                awaitShardLeader(coordinators, nodeIds, gid, retries - 1)
      }

  private def putSharded(
      coordinators: Map[NodeId, MultiRaftNode[IO]],
      nodeIds: List[NodeId],
      key: String,
      value: String,
      attempts: Int = 15
  ): IO[Unit] =
    val shard = shardForKey(key)
    val payload = s"$key=$value".getBytes(StandardCharsets.UTF_8)
    awaitShardLeader(coordinators, nodeIds, shard).flatMap { leaderId =>
      coordinators(leaderId)
        .proposeAndCommitToGroup(shard, payload)
        .timeout(2.seconds)
        .flatMap { logId =>
          IO.println(
            s"   PUT($key=$value) -> ${shard.value} leader=${leaderId.value} committed at $logId"
          )
        }
        .handleErrorWith {
          case _ if attempts > 1 =>
            IO.sleep(25.millis) *>
              putSharded(coordinators, nodeIds, key, value, attempts - 1)
          case err => IO.raiseError(err)
        }
    }

  private def readIndexWithRetry(
      coordinators: Map[NodeId, MultiRaftNode[IO]],
      nodeIds: List[NodeId],
      gid: GroupId,
      attempts: Int = 15
  ): IO[Long] =
    awaitShardLeader(coordinators, nodeIds, gid).flatMap { leaderId =>
      coordinators(leaderId)
        .readIndexFromGroup(gid)
        .timeout(2.seconds)
        .handleErrorWith {
          case _ if attempts > 1 =>
            IO.sleep(25.millis) *>
              readIndexWithRetry(coordinators, nodeIds, gid, attempts - 1)
          case err => IO.raiseError(err)
        }
    }

  private def awaitConverged(
      cond: IO[Boolean],
      retries: Int = 200
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(new RuntimeException("Shard convergence timed out"))
        else IO.sleep(15.millis) *> awaitConverged(cond, retries - 1)
    }

  private def mkShardConfig(
      localId: NodeId,
      voters: Set[NodeId],
      sm: StateMachine[IO, ?]
  ): IO[GroupConfig[IO]] =
    for
      logStore <- InMemLogStore[IO]()
      stableStore <- InMemStableStore[IO]()
      timerService <- DefaultTimerService[IO]
    yield GroupConfig[IO](
      raftConfig = RaftConfig(
        localId = localId,
        electionTimeoutMin = 120.millis,
        electionTimeoutMax = 240.millis,
        heartbeatInterval = 30.millis
      ),
      cluster = ClusterConfig(voters.map(_ -> NodeRole.Voter).toMap),
      logStore = logStore,
      stableStore = stableStore,
      stateMachine = sm,
      timerService = timerService
    )
