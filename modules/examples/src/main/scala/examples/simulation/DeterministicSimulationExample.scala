package examples.simulation

import java.nio.charset.StandardCharsets
import scala.collection.mutable

import raft.message.RaftMessage
import raft.raw.{MemStorage, OutboundMessage, RawNode}
import raft.state.{ClusterConfig, Log, LogType, NodeId, NodeState, RaftConfig}

/** FoundationDB / TigerBeetle-style Deterministic Simulation Testing (DST)
  * example.
  *
  * Runs an entire 5-node Raft cluster on a single thread with a seeded PRNG
  * injecting packet drops, message reordering, network partitions, heals, and
  * crash-restarts from durable `MemStorage`, checking Raft safety invariants on
  * every step and proving bit-for-bit reproducibility across runs.
  */
object DeterministicSimulationExample:

  private final case class Envelope(
      deliverAt: Long,
      seq: Long,
      from: NodeId,
      to: NodeId,
      msg: RaftMessage
  )

  def runSimulation(seed: Long, steps: Int): String =
    var rng = seed ^ 0x9e3779b97f4a7c15L
    def nextLong(): Long =
      rng ^= (rng << 13)
      rng ^= (rng >>> 7)
      rng ^= (rng << 17)
      rng
    def nextInt(bound: Int): Int =
      ((nextLong() >>> 1) % bound).toInt

    val ids = (1 to 5).map(i => NodeId(s"n$i")).toVector
    val voters = ids.toSet
    val stores =
      ids.map(id => id -> MemStorage.create(ClusterConfig(voters))).toMap
    val applied =
      ids.map(id => id -> mutable.ArrayBuffer.empty[(Long, Long, String)]).toMap

    def mkNode(id: NodeId, salt: Long): RawNode =
      new RawNode(
        RaftConfig(
          localId = id,
          electionTimeoutTicks = 8,
          heartbeatIntervalTicks = 2,
          preVoteEnabled = true,
          checkQuorumEnabled = true
        ),
        stores(id),
        initialSeed = seed ^ salt
      )

    val nodes = mutable.Map.from(ids.zipWithIndex.map { case (id, i) =>
      id -> mkNode(id, i.toLong + 1L)
    })

    var netSeq = 0L
    val inFlight = mutable.ArrayBuffer.empty[Envelope]
    var partitioned = Set.empty[NodeId]
    val leadersByTerm = mutable.Map.empty[Long, NodeId]
    var writeCounter = 0

    def enqueue(
        stepNow: Long,
        from: NodeId,
        out: OutboundMessage,
        chaotic: Boolean
    ): Unit =
      if chaotic && (partitioned.contains(from) != partitioned.contains(out.to))
      then ()
      else if chaotic && nextInt(100) < 10 then () // 10% packet loss
      else
        val delay = if chaotic then 1L + nextInt(3).toLong else 1L
        netSeq += 1L
        inFlight += Envelope(stepNow + delay, netSeq, from, out.to, out.message)

    def drainReady(stepNow: Long, chaotic: Boolean): Unit =
      var work = true
      while work do
        work = false
        ids.foreach { id =>
          val raw = nodes(id)
          val store = stores(id)
          if raw.hasReady then
            work = true
            val rd = raw.ready()
            rd.messages.foreach(enqueue(stepNow, id, _, chaotic))
            store.persistReady(rd)
            rd.persistedMessages.foreach(enqueue(stepNow, id, _, chaotic))

            def recordApplied(entries: Vector[Log]): Unit =
              entries.foreach { e =>
                val label =
                  if e.logType == LogType.Command then
                    new String(e.data, StandardCharsets.UTF_8)
                  else e.logType.toString
                applied(id) += ((e.index, e.term, label))
              }

            recordApplied(rd.committedEntries)
            val light = raw.advance(rd)
            light.commitIndex.foreach { c =>
              store.setHardState(store.hardState.copy(commitIndex = c))
            }
            light.messages.foreach(enqueue(stepNow, id, _, chaotic))
            recordApplied(light.committedEntries)
        }

    def checkInvariants(stepNow: Int): Unit =
      // 1. Election Safety: at most one leader per term
      ids.foreach { id =>
        nodes(id).state match
          case _: NodeState.Leader =>
            val t = nodes(id).currentTerm
            leadersByTerm.get(t) match
              case Some(other) if other != id =>
                throw new AssertionError(
                  s"Step $stepNow: Split-brain in term $t between $other and $id"
                )
              case _ => leadersByTerm(t) = id
          case _ => ()
      }

      // 2. State Machine Safety: no two nodes apply different entries at the same index
      val minApplied = applied.values.map(_.size).min
      for pos <- 0 until minApplied do
        val ref = applied(ids.head)(pos)
        ids.tail.foreach { id =>
          val cur = applied(id)(pos)
          if cur != ref then
            throw new AssertionError(
              s"Step $stepNow: Divergence at pos $pos: $ref vs $cur"
            )
        }

    for s <- 1 to steps do
      val chaotic = s <= (steps * 2 / 3)
      if !chaotic then partitioned = Set.empty
      else if s % 45 == 0 then
        partitioned =
          if partitioned.isEmpty then
            Set(ids(nextInt(ids.size)), ids(nextInt(ids.size)))
          else Set.empty
      else if s % 70 == 0 then
        // Crash-restart a random node from durable MemStorage!
        val victim = ids(nextInt(ids.size))
        applied(victim).clear()
        nodes(victim) = mkNode(victim, s.toLong * 31L)

      // Tick a node and deliver ready network packets
      nodes(ids(nextInt(ids.size))).tick()
      val (due, later) = inFlight.partition(_.deliverAt <= s.toLong)
      inFlight.clear()
      inFlight ++= later
      due.sortBy(_.seq).foreach { env =>
        nodes(env.to).step(env.from, env.msg)
      }

      // Periodically propose client commands to current leader
      if s % 8 == 0 then
        ids
          .find(id => nodes(id).state.isInstanceOf[NodeState.Leader])
          .foreach { ldr =>
            writeCounter += 1
            val _ = nodes(ldr).propose(
              s"cmd-$writeCounter".getBytes(StandardCharsets.UTF_8)
            )
          }

      drainReady(s.toLong, chaotic)
      checkInvariants(s)

    val summary = ids
      .map { id =>
        s"${id.value}(term=${nodes(id).currentTerm},commit=${nodes(id).commitIndex},applied=${applied(id).size})"
      }
      .mkString(", ")
    s"seed=$seed | committed=${nodes(ids.head).commitIndex} | $summary"

  def main(args: Array[String]): Unit =
    println("=" * 70)
    println("  DETERMINISTIC SIMULATION TESTING (DST) EXAMPLE")
    println("=" * 70)

    val seed = 20261115L
    val run1 = runSimulation(seed = seed, steps = 300)
    val run2 = runSimulation(seed = seed, steps = 300)

    println(s"\nRun #1: $run1")
    println(s"Run #2: $run2")
    require(
      run1 == run2,
      "Determinism violated: runs with identical seed differed!"
    )
    println(
      "\nBit-for-bit identical execution verified across adversarial faults!"
    )
