package examples.distributed

import cats.effect.*
import cats.syntax.all.*

import raft.state.*
import raft.impl.{InMemLogStore, InMemStableStore, DefaultTimerService}
import raft.spi.StateMachine
import raft.multigroup.*

import scala.concurrent.duration.*

/** Demonstrates the Multi-Raft Group feature.
  *
  * This example creates a multi-shard key-value store where keys are
  * automatically partitioned across two independent Raft consensus groups:
  *   - '''shard-0''': keys starting with "a"–"m"
  *   - '''shard-1''': keys starting with "n"–"z"
  *
  * Each shard runs its own leader election, log replication, and state machine
  * independently. The shared [[MultiGroupTransport]] multiplexes all network
  * traffic over a single transport layer.
  *
  * This architecture mirrors production systems like CockroachDB (ranges) and
  * TiKV (regions), where data is automatically sharded across thousands of
  * independent Raft groups for horizontal scalability.
  */
object MultiGroupExample extends IOApp.Simple:

  override def run: IO[Unit] =
    for
      _ <- IO.println("═" * 70)
      _ <- IO.println("  MULTI-RAFT GROUP EXAMPLE")
      _ <- IO.println(
        "  Sharded Key-Value Store with Independent Consensus Groups"
      )
      _ <- IO.println("═" * 70)

      nodeId = NodeId("node1")
      voters = Set(nodeId, NodeId("node2"), NodeId("node3"))

      // Step 1: Create the shared multi-group transport
      _ <- IO.println("\nStep 1: Creating shared transport layer...")
      transport <- MultiGroupTransport[IO](
        nodeId,
        env =>
          IO.println(
            s"   → Sending to group=${env.groupId.value}: ${env.message.getClass.getSimpleName}"
          )
      )

      // Step 2: Create the multi-raft coordinator
      _ <- IO.println("Step 2: Creating MultiRaftNode coordinator...")
      multi <- MultiRaftNode[IO](nodeId, transport)

      // Step 3: Create two shards
      _ <- IO.println("Step 3: Creating two shard groups...")

      cfg0 <- mkShardConfig(nodeId, voters, "shard-0")
      cfg1 <- mkShardConfig(nodeId, voters, "shard-1")

      _ <- multi.createGroup(GroupId("shard-0"), cfg0)
      _ <- multi.createGroup(GroupId("shard-1"), cfg1)

      groups <- multi.listGroups
      _ <- IO.println(
        s"   Active groups: ${groups.map(_.value).mkString(", ")}"
      )

      // Step 4: Show independent group states
      _ <- IO.println("\nStep 4: Querying group states...")
      state0 <- multi.getGroupState(GroupId("shard-0"))
      state1 <- multi.getGroupState(GroupId("shard-1"))
      _ <- IO.println(
        s"   shard-0 state: ${state0.map(_.getClass.getSimpleName)}"
      )
      _ <- IO.println(
        s"   shard-1 state: ${state1.map(_.getClass.getSimpleName)}"
      )

      // Step 5: Demonstrate group routing
      _ <- IO.println("\nStep 5: Demonstrating shard routing...")
      _ <- routeKey(multi, "alice", "value-a")
      _ <- routeKey(multi, "bob", "value-b")
      _ <- routeKey(multi, "nancy", "value-n")
      _ <- routeKey(multi, "zara", "value-z")

      // Step 6: Dynamic group management
      _ <- IO.println("\nStep 6: Dynamic group management...")
      cfg2 <- mkShardConfig(nodeId, voters, "shard-2")
      _ <- multi.createGroup(GroupId("shard-2"), cfg2)
      groups2 <- multi.listGroups
      _ <- IO.println(
        s"   After adding shard-2: ${groups2.map(_.value).toList.sorted.mkString(", ")}"
      )

      _ <- multi.removeGroup(GroupId("shard-2"))
      groups3 <- multi.listGroups
      _ <- IO.println(
        s"   After removing shard-2: ${groups3.map(_.value).toList.sorted.mkString(", ")}"
      )

      // Step 7: Shutdown
      _ <- IO.println("\nStep 7: Shutting down all groups...")
      _ <- multi.shutdown
      groupsFinal <- multi.listGroups
      _ <- IO.println(s"   Active groups after shutdown: ${groupsFinal.size}")

      _ <- IO.println("\n" + "═" * 70)
      _ <- IO.println("  MULTI-RAFT GROUP SUMMARY")
      _ <- IO.println("═" * 70)
      _ <- IO.println("""
   KEY CONCEPTS:
   
   1. GroupId identifies each independent consensus group
   2. MultiRaftNode coordinates multiple RaftNode instances
   3. MultiGroupTransport multiplexes all groups over shared connections
   4. GroupEnvelope tags each wire message with its target group
   5. Each group has independent: log, state machine, timers, elections
   
   PRODUCTION PATTERNS (CockroachDB/TiKV):
   
   - Data is partitioned into ranges/regions, each = one Raft group
   - Thousands of groups run on each physical node
   - Groups can be dynamically created, split, merged, or moved
   - Heartbeats are coalesced between node pairs for efficiency
""")
    yield ()

  private def shardForKey(key: String): GroupId =
    if key.head.toLower <= 'm' then GroupId("shard-0")
    else GroupId("shard-1")

  private def routeKey(
      multi: MultiRaftNode[IO],
      key: String,
      value: String
  ): IO[Unit] =
    val shard = shardForKey(key)
    IO.println(
      s"   PUT($key=$value) → routed to ${shard.value} (key '$key' starts with '${key.head}')"
    )

  private def mkShardConfig(
      localId: NodeId,
      voters: Set[NodeId],
      name: String
  ): IO[GroupConfig[IO]] =
    for
      logStore <- InMemLogStore[IO]()
      stableStore <- InMemStableStore[IO]()
      timerService <- DefaultTimerService[IO]
    yield GroupConfig[IO](
      raftConfig = RaftConfig.default(localId),
      cluster = ClusterConfig(voters.map(_ -> NodeRole.Voter).toMap),
      logStore = logStore,
      stableStore = stableStore,
      stateMachine = new StateMachine[IO, Unit]:
        def apply(log: Log): IO[Unit] =
          IO.println(s"   [$name] Applied: ${String(log.data)}")
        def snapshot: IO[Array[Byte]] = IO.pure(Array.empty)
        def restore(data: Array[Byte]): IO[Unit] = IO.unit
      ,
      timerService = timerService
    )
