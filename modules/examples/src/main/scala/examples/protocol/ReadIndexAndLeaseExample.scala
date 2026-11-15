package examples.protocol

import java.nio.charset.StandardCharsets

import raft.raw.{MemStorage, OutboundMessage, RawNode}
import raft.state.{ClusterConfig, NodeId, NodeState, RaftConfig}

/** Demonstrates linearizable reads in Raft (§6.4):
  *   1. Heartbeat-quorum `ReadIndex` (`RawNode.readIndex`) — safe against
  *      network partitions without writing to the Raft log.
  *   2. Clock-bounded `LeaseRead` (`leaseReadEnabled = true`) — zero-RTT local
  *      reads while the leader holds an active `CheckQuorum` lease.
  */
object ReadIndexAndLeaseExample:

  private final class SimCluster(leaseRead: Boolean):
    val ids = Vector(NodeId("n1"), NodeId("n2"), NodeId("n3"))
    val voters = ids.toSet
    private var partitionedFrom: Set[(NodeId, NodeId)] = Set.empty

    val nodes: Map[NodeId, (RawNode, MemStorage)] = ids.zipWithIndex.map {
      case (id, i) =>
        val store = MemStorage.create(ClusterConfig(voters))
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutTicks = 10,
          heartbeatIntervalTicks = 2,
          preVoteEnabled = true,
          checkQuorumEnabled = true,
          leaseReadEnabled = leaseRead
        )
        id -> (new RawNode(cfg, store, initialSeed = 42L + i) -> store)
    }.toMap

    def setPartition(isolated: NodeId): Unit =
      val others = voters - isolated
      partitionedFrom = others.flatMap(o => Set((isolated, o), (o, isolated)))

    def drainAll(): Vector[(NodeId, Long, String)] =
      var readResults = Vector.empty[(NodeId, Long, String)]
      var progress = true
      while progress do
        progress = false
        var outbound = Vector.empty[(NodeId, OutboundMessage)]
        nodes.foreach { case (id, (raw, store)) =>
          if raw.hasReady then
            progress = true
            val rd = raw.ready()
            rd.readStates.foreach { rs =>
              readResults = readResults :+ (id, rs.index, rs.requestCtx)
            }
            outbound ++= rd.messages.map(id -> _)
            store.persistReady(rd)
            outbound ++= rd.persistedMessages.map(id -> _)
            val light = raw.advance(rd)
            light.commitIndex.foreach { c =>
              store.setHardState(store.hardState.copy(commitIndex = c))
            }
            outbound ++= light.messages.map(id -> _)
        }
        outbound.foreach { case (from, out) =>
          if !partitionedFrom.contains((from, out.to)) then
            nodes(out.to)._1.step(from, out.message)
            progress = true
        }
      readResults

    def electLeader(): NodeId =
      var ticks = 0
      while !nodes.values.exists(
          _._1.state.isInstanceOf[NodeState.Leader]
        ) && ticks < 50
      do
        nodes(NodeId("n1"))._1.tick()
        val _ = drainAll()
        ticks += 1
      nodes.collectFirst {
        case (id, (raw, _)) if raw.state.isInstanceOf[NodeState.Leader] => id
      }.get

  def main(args: Array[String]): Unit =
    println("=" * 70)
    println("  LINEARIZABLE READS: ReadIndex vs. LeaseRead (§6.4)")
    println("=" * 70)

    // Part 1: Heartbeat-quorum ReadIndex
    println("\n1. Heartbeat-Quorum ReadIndex (leaseReadEnabled = false)")
    val c1 = new SimCluster(leaseRead = false)
    val leader1 = c1.electLeader()
    val _ = c1
      .nodes(leader1)
      ._1
      .propose(
        "balance=500".getBytes(StandardCharsets.UTF_8)
      )
    val _ = c1.drainAll()
    println(
      s"   Leader ${leader1.value} committed up to index ${c1.nodes(leader1)._1.commitIndex}"
    )

    val _ = c1.nodes(leader1)._1.readIndex("req-quorum-1")
    val confirmed = c1.drainAll()
    println(s"   Healthy cluster ReadIndex confirmed: $confirmed")

    // Isolate leader1 and verify a partitioned stale leader CANNOT confirm ReadIndex
    c1.setPartition(leader1)
    val _ = c1.nodes(leader1)._1.readIndex("req-stale-leader")
    val blocked = c1.drainAll()
    println(
      s"   Partitioned leader ReadIndex completions (must be empty!): $blocked"
    )

    // Part 2: Zero-RTT LeaseRead
    println("\n2. Clock-Bounded LeaseRead (leaseReadEnabled = true)")
    val c2 = new SimCluster(leaseRead = true)
    val leader2 = c2.electLeader()
    val _ = c2
      .nodes(leader2)
      ._1
      .propose(
        "config=v2".getBytes(StandardCharsets.UTF_8)
      )
    val _ = c2.drainAll()

    // Isolate leader2 while its lease is STILL active -> immediate local ReadState!
    c2.setPartition(leader2)
    val _ = c2.nodes(leader2)._1.readIndex("req-lease-fast")
    val fastLease = c2.drainAll()
    println(s"   Active-lease zero-RTT ReadState: $fastLease")

    // Advance ticks past two electionTimeoutTicks windows without quorum -> CheckQuorum steps down!
    for _ <- 1 to 22 do
      c2.nodes(leader2)._1.tick()
      val _ = c2.drainAll()
    println(
      s"   After lease expiry without quorum, ${leader2.value} state is now: ${c2.nodes(leader2)._1.state.getClass.getSimpleName}"
    )
