package raft.dst

import java.util.Arrays
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.raw.{MemStorage, OutboundMessage, RawNode}
import raft.state.*
import raft.state.NodeState.*

/** Seeded, single-threaded Deterministic Simulation Testing (DST) harness
  * inspired by FoundationDB, TigerBeetle, and `tikv/raft-rs` network
  * simulators.
  *
  * Drives 3-node and 5-node [[RawNode]] clusters through adversarial network
  * drops, packet duplication, variable-delay reordering, symmetric and
  * asymmetric network partitions, partition healing, and abrupt crash-restarts
  * (discarding volatile RAM state and recovering solely from durable
  * [[MemStorage]]), verifying all **5 Raft safety invariants** after every
  * single simulation step:
  *   1. **Election Safety (§5.2)** — at most one leader per term
  *   2. **Leader Append-Only (§5.3)** — a leader never overwrites or truncates
  *      its own log entries within the same term
  *   3. **Log Matching (§5.3)** — identical `(index, term)` implies identical
  *      prefix across all nodes
  *   4. **Leader Completeness (§5.4)** — every committed entry is present in
  *      every higher-term leader's log
  *   5. **State Machine Safety (§5.4.3)** — no two nodes ever apply different
  *      entries at the same log index
  */
class DeterministicSimulationSpec extends AnyFlatSpec with Matchers:

  private final case class EntryFingerprint(
      index: Long,
      term: Long,
      logType: LogType,
      payload: Vector[Byte]
  )

  private object EntryFingerprint:
    def fromLog(l: Log): EntryFingerprint =
      EntryFingerprint(l.index, l.term, l.logType, l.data.toVector)

  private final case class ScheduledPacket(
      deliverAtStep: Long,
      seqNo: Long,
      envelope: OutboundMessage
  )

  private final class SimulatedNetwork(
      val nodeIds: Vector[NodeId],
      val seed: Long,
      dropProb: Double = 0.08,
      dupProb: Double = 0.03,
      maxDelaySteps: Int = 5
  ):
    private val rng = new java.util.Random(seed)
    private val cluster = ClusterConfig(nodeIds.toSet)

    val storages: Map[NodeId, MemStorage] =
      nodeIds.map(id => id -> MemStorage.create(cluster)).toMap

    val nodes: scala.collection.mutable.Map[NodeId, RawNode] =
      scala.collection.mutable.Map.from(
        nodeIds.zipWithIndex.map { case (id, idx) =>
          id -> bootRawNode(id, storages(id), seed + (idx + 1L) * 1009L)
        }
      )

    val crashed: scala.collection.mutable.Set[NodeId] =
      scala.collection.mutable.Set.empty

    // Directed blocked links: (from, to) -> cannot communicate
    val blockedLinks: scala.collection.mutable.Set[(NodeId, NodeId)] =
      scala.collection.mutable.Set.empty

    private var inFlight: Vector[ScheduledPacket] = Vector.empty
    private var pktSeq: Long = 0L
    private var stepNum: Long = 0L
    private var proposalCounter: Int = 0

    // Safety-invariant history trackers
    val leadersByTerm: scala.collection.mutable.Map[Long, NodeId] =
      scala.collection.mutable.Map.empty

    // (leaderId, term) -> prefix of EntryFingerprints observed on that leader
    val leaderObservedLogs: scala.collection.mutable.Map[(NodeId, Long), Map[
      Long,
      EntryFingerprint
    ]] =
      scala.collection.mutable.Map.empty

    // Globally committed entries: index -> EntryFingerprint
    val committedHistory: scala.collection.mutable.Map[Long, EntryFingerprint] =
      scala.collection.mutable.Map.empty

    // Per-node applied entries: nodeId -> (index -> EntryFingerprint)
    val appliedByNode
        : scala.collection.mutable.Map[NodeId, Vector[EntryFingerprint]] =
      scala.collection.mutable.Map.from(nodeIds.map(_ -> Vector.empty))

    private def bootRawNode(
        id: NodeId,
        storage: MemStorage,
        nodeSeed: Long
    ): RawNode =
      val cfg = RaftConfig(
        localId = id,
        preVoteEnabled = true,
        leaderStickinessEnabled = true,
        checkQuorumEnabled = true,
        leaseReadEnabled = true,
        electionTimeoutTicks = 10,
        heartbeatIntervalTicks = 3
      )
      new RawNode(cfg, storage, nodeSeed)

    private def enqueueMessage(
        env: OutboundMessage,
        chaosEnabled: Boolean
    ): Unit =
      if !blockedLinks.contains((env.from, env.to)) && !crashed.contains(env.to)
      then
        if !chaosEnabled || rng.nextDouble() >= dropProb then
          val delay =
            if chaosEnabled then 1 + rng.nextInt(maxDelaySteps) else 1
          pktSeq += 1L
          inFlight = inFlight :+ ScheduledPacket(stepNum + delay, pktSeq, env)
          if chaosEnabled && rng.nextDouble() < dupProb then
            val dupDelay = delay + 1 + rng.nextInt(maxDelaySteps)
            pktSeq += 1L
            inFlight =
              inFlight :+ ScheduledPacket(stepNum + dupDelay, pktSeq, env)

    def drainNode(id: NodeId, chaosEnabled: Boolean): Unit =
      if !crashed.contains(id) then
        val raw = nodes(id)
        val store = storages(id)
        while raw.hasReady do
          val rd = raw.ready()
          rd.messages.foreach(m => enqueueMessage(m, chaosEnabled))
          store.persistReady(rd)
          rd.persistedMessages.foreach(m => enqueueMessage(m, chaosEnabled))
          recordApplied(id, rd.committedEntries)
          val light = raw.advance(rd)
          light.commitIndex.foreach { ci =>
            store.setHardState(store.hardState.copy(commitIndex = ci))
          }
          recordApplied(id, light.committedEntries)
          light.messages.foreach(m => enqueueMessage(m, chaosEnabled))

    private def recordApplied(id: NodeId, entries: Vector[Log]): Unit =
      if entries.nonEmpty then
        val existing = appliedByNode(id)
        val lastAppliedIdx = existing.lastOption.map(_.index).getOrElse(0L)
        val fresh = entries
          .filter(_.index > lastAppliedIdx)
          .map(EntryFingerprint.fromLog)
        appliedByNode.update(id, existing ++ fresh)
        fresh.foreach { fp =>
          committedHistory.get(fp.index) match
            case Some(prev) =>
              withClue(
                s"[Seed $seed step $stepNum] State Machine Safety violated at index ${fp.index} on ${id.value}: "
              ) {
                fp shouldBe prev
              }
            case None =>
              committedHistory.update(fp.index, fp)
        }

    def crashNode(id: NodeId): Unit =
      if !crashed.contains(id) then
        // Ensure a majority can still form or recover later; crash is reversible
        crashed += id

    def restartNode(id: NodeId): Unit =
      if crashed.contains(id) then
        crashed -= id
        val store = storages(id)
        val restarted = bootRawNode(
          id,
          store,
          seed ^ (stepNum * 6364136223846793005L) ^ id.value.hashCode.toLong
        )
        // Replay already-applied entries from durable storage up to persisted commitIndex
        val prevAppliedCount =
          appliedByNode(id).lastOption.map(_.index).getOrElse(0L)
        val persistedCommit = store.hardState.commitIndex
        if persistedCommit > prevAppliedCount then
          val catchUp =
            store.entries(prevAppliedCount + 1L, persistedCommit + 1L)
          recordApplied(id, catchUp)
        nodes.update(id, restarted)
        drainNode(id, chaosEnabled = true)

    def injectSymmetricPartition(): Unit =
      blockedLinks.clear()
      val shuffled = rng.ints(nodeIds.size.toLong).toArray
      val _ = shuffled
      val perm = scala.util.Random(rng.nextLong()).shuffle(nodeIds)
      val split = math.max(1, perm.size / 2)
      val sideA = perm.take(split).toSet
      val sideB = perm.drop(split).toSet
      for a <- sideA; b <- sideB do
        blockedLinks += ((a, b))
        blockedLinks += ((b, a))

    def injectAsymmetricLinkBlock(): Unit =
      if nodeIds.size >= 2 then
        val a = nodeIds(rng.nextInt(nodeIds.size))
        val b = nodeIds(rng.nextInt(nodeIds.size))
        if a != b then blockedLinks += ((a, b))

    def healNetwork(): Unit =
      blockedLinks.clear()
      crashed.toVector.foreach(restartNode)

    def stepSimulation(
        chaosEnabled: Boolean,
        proposeEnabled: Boolean = true
    ): Unit =
      stepNum += 1L

      // 1. Occasionally inject/heal faults when chaosEnabled
      if chaosEnabled then
        val faultRoll = rng.nextInt(100)
        if faultRoll < 3 then injectSymmetricPartition()
        else if faultRoll < 7 then injectAsymmetricLinkBlock()
        else if faultRoll < 12 then blockedLinks.clear()
        else if faultRoll == 13 && crashed.size < (nodeIds.size - 1) / 2 then
          crashNode(nodeIds(rng.nextInt(nodeIds.size)))
        else if faultRoll < 20 && crashed.nonEmpty then
          val sorted = crashed.toVector.sortBy(_.value)
          val victim = sorted(rng.nextInt(sorted.size))
          restartNode(victim)

      // 2. Tick all live nodes
      nodeIds.foreach { id =>
        if !crashed.contains(id) then
          nodes(id).tick()
          drainNode(id, chaosEnabled)
      }

      // 3. Propose client commands periodically to any active leader
      if proposeEnabled && stepNum % 3L == 0L then
        nodeIds
          .find(id =>
            !crashed.contains(id) && nodes(id).state.isInstanceOf[Leader]
          )
          .foreach { leaderId =>
            proposalCounter += 1
            val payload = s"s$seed-k$proposalCounter".getBytes
            val _ = nodes(leaderId).propose(payload)
            drainNode(leaderId, chaosEnabled)
          }

      // 4. Deliver all packets scheduled for `deliverAtStep <= stepNum`
      val (due, later) = inFlight.partition(_.deliverAtStep <= stepNum)
      inFlight = later
      val orderedDue =
        if chaosEnabled && due.size > 1 then
          scala.util.Random(rng.nextLong()).shuffle(due)
        else due.sortBy(_.seqNo)

      orderedDue.foreach { pkt =>
        val env = pkt.envelope
        if !crashed.contains(env.to) && !blockedLinks.contains(
            (env.from, env.to)
          )
        then
          nodes(env.to).step(env.from, env.message)
          drainNode(env.to, chaosEnabled)
      }

      // 5. Verify all 5 Raft safety invariants!
      verifyAllInvariants()

    def verifyAllInvariants(): Unit =
      // Invariant 1: Election Safety (<= 1 leader per term across all time)
      // Invariant 2: Leader Append-Only
      // Invariant 4: Leader Completeness
      nodeIds.foreach { id =>
        if !crashed.contains(id) then
          val raw = nodes(id)
          val store = storages(id)
          raw.state match
            case l: Leader =>
              leadersByTerm.get(l.term) match
                case Some(existingLeader) =>
                  withClue(
                    s"[Seed $seed step $stepNum] Election Safety violated in term ${l.term}:"
                  ) {
                    existingLeader shouldBe id
                  }
                case None =>
                  leadersByTerm.update(l.term, id)

              val currentEntries = store
                .entries(store.firstIndex, store.lastIndex + 1L)
                .map(e => e.index -> EntryFingerprint.fromLog(e))
                .toMap

              // Leader Append-Only: previously persisted entries on (id, l.term) must still match
              val key = (id, l.term)
              val prevLeaderEntries =
                leaderObservedLogs.getOrElse(key, Map.empty)
              prevLeaderEntries.foreach { case (idx, prevFp) =>
                if idx >= store.firstIndex then
                  withClue(
                    s"[Seed $seed step $stepNum] Leader Append-Only violated on ${id.value} term ${l.term} idx $idx:"
                  ) {
                    currentEntries.get(idx) shouldBe Some(prevFp)
                  }
              }
              leaderObservedLogs.update(
                key,
                prevLeaderEntries ++ currentEntries
              )

              // Leader Completeness: every globally committed entry from an earlier term must exist in this leader's log
              committedHistory.foreach { case (cIdx, cFp) =>
                if cFp.term < l.term && cIdx >= store.firstIndex then
                  withClue(
                    s"[Seed $seed step $stepNum] Leader Completeness violated on ${id.value} term ${l.term} missing committed idx $cIdx:"
                  ) {
                    currentEntries.get(cIdx) shouldBe Some(cFp)
                  }
              }

            case _ => ()
      }

      // Invariant 3: Log Matching across all persisted node logs
      for
        i <- nodeIds.indices
        j <- (i + 1) until nodeIds.size
      do
        val sA = storages(nodeIds(i))
        val sB = storages(nodeIds(j))
        val low = math.max(sA.firstIndex, sB.firstIndex)
        val high = math.min(sA.lastIndex, sB.lastIndex)
        if low <= high then
          var idx = high
          var matchedPrefixChecked = false
          while idx >= low && !matchedPrefixChecked do
            val tA = sA.term(idx)
            val tB = sB.term(idx)
            if tA.isDefined && tA == tB then
              // Every preceding entry in [low..idx] MUST also be identical!
              val prefixA = sA.entries(low, idx + 1L)
              val prefixB = sB.entries(low, idx + 1L)
              prefixA.zip(prefixB).foreach { case (eA, eB) =>
                eA.index shouldBe eB.index
                eA.term shouldBe eB.term
                eA.logType shouldBe eB.logType
                Arrays.equals(eA.data, eB.data) shouldBe true
              }
              matchedPrefixChecked = true
            idx -= 1L

      // Invariant 5: State Machine Safety across all applied sequences
      for
        i <- nodeIds.indices
        j <- (i + 1) until nodeIds.size
      do
        val appA = appliedByNode(nodeIds(i))
        val appB = appliedByNode(nodeIds(j))
        val commonLen = math.min(appA.length, appB.length)
        var k = 0
        while k < commonLen do
          withClue(
            s"[Seed $seed step $stepNum] State Machine Safety divergence between ${nodeIds(i).value} and ${nodeIds(j).value} at pos $k:"
          ) {
            appA(k) shouldBe appB(k)
          }
          k += 1

  "Deterministic Simulation (3-node cluster)" should "preserve all 5 Raft safety invariants and converge across 10 adversarial seeds" in {
    val ids = Vector(NodeId("n1"), NodeId("n2"), NodeId("n3"))

    for seed <- 1L to 10L do
      val sim = new SimulatedNetwork(ids, seed = seed)

      // Phase 1: 250 steps of chaos (drops, delays, partitions, crashes, restarts)
      for _ <- 1 to 250 do sim.stepSimulation(chaosEnabled = true)

      // Phase 2: Heal partitions & revive crashed nodes, propose a few post-heal writes, then drain to quiescence
      sim.healNetwork()
      for _ <- 1 to 50 do
        sim.stepSimulation(chaosEnabled = false, proposeEnabled = true)
      for _ <- 1 to 30 do
        sim.stepSimulation(chaosEnabled = false, proposeEnabled = false)

      val leaders = ids.filter(id => sim.nodes(id).state.isInstanceOf[Leader])
      withClue(s"[Seed $seed] Expected single converged leader after heal:") {
        leaders should have size 1
      }

      val referenceApplied = sim.appliedByNode(leaders.head)
      referenceApplied should not be empty
      ids.foreach { id =>
        withClue(
          s"[Seed $seed] Node ${id.value} failed to converge to leader's applied log:"
        ) {
          sim.appliedByNode(id) shouldBe referenceApplied
        }
      }
  }

  "Deterministic Simulation (5-node cluster)" should "preserve all 5 Raft safety invariants and converge across 6 adversarial seeds" in {
    val ids = (1 to 5).map(i => NodeId(s"n$i")).toVector

    for seed <- 101L to 106L do
      val sim = new SimulatedNetwork(ids, seed = seed)

      for _ <- 1 to 250 do sim.stepSimulation(chaosEnabled = true)

      sim.healNetwork()
      for _ <- 1 to 60 do
        sim.stepSimulation(chaosEnabled = false, proposeEnabled = true)
      for _ <- 1 to 40 do
        sim.stepSimulation(chaosEnabled = false, proposeEnabled = false)

      val leaders = ids.filter(id => sim.nodes(id).state.isInstanceOf[Leader])
      withClue(
        s"[Seed $seed] Expected single converged 5-node leader after heal:"
      ) {
        leaders should have size 1
      }

      val referenceApplied = sim.appliedByNode(leaders.head)
      referenceApplied should not be empty
      ids.foreach { id =>
        withClue(s"[Seed $seed] 5-node ${id.value} failed to converge:") {
          sim.appliedByNode(id) shouldBe referenceApplied
        }
      }
  }

  "Deterministic Simulation reproducibility" should "produce bit-for-bit identical history for the same seed" in {
    val ids = Vector(NodeId("n1"), NodeId("n2"), NodeId("n3"))

    def runWithSeed(seed: Long): Vector[EntryFingerprint] =
      val sim = new SimulatedNetwork(ids, seed = seed)
      for _ <- 1 to 150 do sim.stepSimulation(chaosEnabled = true)
      sim.healNetwork()
      for _ <- 1 to 40 do
        sim.stepSimulation(chaosEnabled = false, proposeEnabled = true)
      for _ <- 1 to 20 do
        sim.stepSimulation(chaosEnabled = false, proposeEnabled = false)
      sim.appliedByNode(ids.head)

    val run1 = runWithSeed(777L)
    val run2 = runWithSeed(777L)
    run1 should not be empty
    run1 shouldBe run2
  }
