package examples.cluster

import java.nio.charset.StandardCharsets

import raft.raw.{MemStorage, OutboundMessage, RawNode}
import raft.state.{ClusterConfig, NodeId, NodeRole, NodeState, RaftConfig}

/** Demonstrates Joint Consensus cluster membership reconfiguration (§6):
  *   - Safely migrates a live 3-node cluster `{n1, n2, n3}` to
  *     `{n2, n3, n4, n5}`.
  *   - Enters `C_old,new` requiring dual majorities in BOTH old and new voter
  *     sets simultaneously so split-brain is impossible.
  *   - Automatically proposes and commits `C_new` once `C_old,new` commits.
  */
object JointConsensusExample:

  def main(args: Array[String]): Unit =
    println("=" * 70)
    println("  JOINT CONSENSUS MEMBERSHIP CHANGE EXAMPLE (§6)")
    println("=" * 70)

    val n1 = NodeId("n1")
    val n2 = NodeId("n2")
    val n3 = NodeId("n3")
    val n4 = NodeId("n4")
    val n5 = NodeId("n5")

    val initialVoters = Set(n1, n2, n3)
    val allNodes = List(n1, n2, n3, n4, n5)

    val nodes: Map[NodeId, (RawNode, MemStorage)] = allNodes.zipWithIndex.map {
      case (id, idx) =>
        val bootVoters =
          if initialVoters.contains(id) then initialVoters else Set(id)
        val store = MemStorage.create(ClusterConfig(bootVoters))
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutTicks = 10,
          heartbeatIntervalTicks = 2,
          preVoteEnabled = true
        )
        id -> (new RawNode(cfg, store, initialSeed = 900L + idx) -> store)
    }.toMap

    def pump(): Unit =
      var active = true
      while active do
        active = false
        var wire = Vector.empty[(NodeId, OutboundMessage)]
        nodes.foreach { case (id, (raw, store)) =>
          if raw.hasReady then
            active = true
            val rd = raw.ready()
            wire ++= rd.messages.map(id -> _)
            store.persistReady(rd)
            wire ++= rd.persistedMessages.map(id -> _)
            val light = raw.advance(rd)
            light.commitIndex.foreach { c =>
              store.setHardState(store.hardState.copy(commitIndex = c))
            }
            wire ++= light.messages.map(id -> _)
        }
        wire.foreach { case (from, out) =>
          nodes.get(out.to).foreach { case (target, _) =>
            target.step(from, out.message)
            active = true
          }
        }

    // 1. Elect n2 as leader of {n1, n2, n3}
    while !nodes(n2)._1.state.isInstanceOf[NodeState.Leader] do
      nodes(n2)._1.tick()
      pump()

    println(
      s"\n1. Initial cluster voters on leader ${n2.value}: ${nodes(n2)._1.clusterConfig.voters.map(_.value)}"
    )

    // 2. Propose Joint Consensus transition to {n2, n3, n4, n5}
    val targetMembers = Map(
      n2 -> NodeRole.Voter,
      n3 -> NodeRole.Voter,
      n4 -> NodeRole.Voter,
      n5 -> NodeRole.Voter
    )
    println(
      "\n2. Proposing Joint Consensus reconfiguration: {n1, n2, n3} -> {n2, n3, n4, n5}"
    )
    val res = nodes(n2)._1.proposeConfChange(targetMembers)
    println(
      s"   proposeConfChange result: $res, inJointConsensus=${nodes(n2)._1.clusterConfig.isInJointConsensus}"
    )

    // 3. Pump network: C_old,new replicates & commits -> leader auto-appends C_new -> C_new commits!
    pump()

    println(
      s"\n3. Finalized configuration on leader ${n2.value}:"
    )
    println(
      s"   inJointConsensus = ${nodes(n2)._1.clusterConfig.isInJointConsensus}"
    )
    println(
      s"   final voters     = ${nodes(n2)._1.clusterConfig.voters.map(_.value)}"
    )

    // 4. Propose a command under the new 4-voter quorum {n2, n3, n4, n5}
    val _ = nodes(n2)._1.propose(
      "post-reconfig-write".getBytes(StandardCharsets.UTF_8)
    )
    pump()
    println(
      s"\n4. Replicated post-reconfiguration entry across new voters: commitIndex=${nodes(n4)._1.commitIndex} on ${n4.value} & ${nodes(n5)._1.commitIndex} on ${n5.value}"
    )
