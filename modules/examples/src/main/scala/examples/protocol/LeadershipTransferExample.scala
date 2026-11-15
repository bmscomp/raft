package examples.protocol

import java.nio.charset.StandardCharsets

import raft.raw.{MemStorage, OutboundMessage, RawNode}
import raft.state.{ClusterConfig, NodeId, NodeState, RaftConfig}

/** Demonstrates zero-downtime Leadership Transfer (§3.10):
  *   1. Current leader catches up a lagging target replica if needed.
  *   2. Leader fences new client proposals while transfer is in flight.
  *   3. Once target's `matchIndex == lastLogIndex`, leader sends
  *      `TimeoutNowRequest` so target starts an immediate election without
  *      waiting for an election timeout.
  */
object LeadershipTransferExample:

  def main(args: Array[String]): Unit =
    println("=" * 70)
    println("  LEADERSHIP TRANSFER EXAMPLE (§3.10 TimeoutNow)")
    println("=" * 70)

    val n1 = NodeId("node-1")
    val n2 = NodeId("node-2")
    val n3 = NodeId("node-3")
    val voters = Set(n1, n2, n3)

    var dropToN3 = false
    val nodes: Map[NodeId, (RawNode, MemStorage)] =
      List(n1, n2, n3).zipWithIndex.map { case (id, idx) =>
        val store = MemStorage.create(ClusterConfig(voters))
        val cfg = RaftConfig(
          localId = id,
          electionTimeoutTicks = 10,
          heartbeatIntervalTicks = 2,
          preVoteEnabled = true,
          checkQuorumEnabled = true
        )
        id -> (new RawNode(cfg, store, initialSeed = 700L + idx) -> store)
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
          if !(dropToN3 && out.to == n3) then
            nodes(out.to)._1.step(from, out.message)
            active = true
        }

    // 1. Elect node-1
    while !nodes(n1)._1.state.isInstanceOf[NodeState.Leader] do
      nodes(n1)._1.tick()
      pump()
    println(
      s"\n1. Initial leader: ${n1.value} (term ${nodes(n1)._1.currentTerm})"
    )

    // 2. Make node-3 lag behind while node-1 & node-2 commit 2 entries
    dropToN3 = true
    val _ = nodes(n1)._1.propose("tx-1".getBytes(StandardCharsets.UTF_8))
    val _ = nodes(n1)._1.propose("tx-2".getBytes(StandardCharsets.UTF_8))
    pump()
    println(
      s"2. Lagging ${n3.value} lastLogIndex=${nodes(n3)._1.lastLogIndex} vs leader lastLogIndex=${nodes(n1)._1.lastLogIndex}"
    )

    // 3. Reconnect node-3 and initiate leadership transfer to node-3
    dropToN3 = false
    println(s"3. Initiating transferLeader(${n3.value})...")
    val _ = nodes(n1)._1.transferLeader(n3)

    // Verify new proposals on old leader are rejected while transfer is active
    val fenced =
      nodes(n1)._1.propose(
        "tx-during-transfer".getBytes(StandardCharsets.UTF_8)
      )
    println(s"   Proposal to old leader during transfer: $fenced")

    // Pump messages — leader catches up node-3, sends TimeoutNow, node-3 wins election immediately!
    pump()

    println(
      s"\n4. Transfer complete in zero election-timeout ticks!"
    )
    println(
      s"   ${n1.value} role: ${nodes(n1)._1.state.getClass.getSimpleName} (term ${nodes(n1)._1.currentTerm})"
    )
    println(
      s"   ${n3.value} role: ${nodes(n3)._1.state.getClass.getSimpleName} (term ${nodes(n3)._1.currentTerm}, lastLogIndex=${nodes(n3)._1.lastLogIndex})"
    )
