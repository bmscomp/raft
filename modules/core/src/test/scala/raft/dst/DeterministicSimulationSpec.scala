package raft.dst

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  DataInputStream,
  DataOutputStream
}
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
  * asymmetric network partitions, partition healing, abrupt crash-restarts, log
  * compaction + `InstallSnapshot`, dynamic Joint Consensus (`3 -> 5 -> 3`),
  * leadership transfers, and linearizable `ReadIndex` verification, asserting
  * all **5 Raft safety invariants** after every single simulation step:
  *   1. **Election Safety (§5.2)** — at most one leader per term
  *   2. **Leader Append-Only (§5.3)** — a leader never overwrites or truncates
  *      its own log entries within the same term
  *   3. **Log Matching (§5.3)** — identical `(index, term)` implies identical
  *      prefix across all nodes
  *   4. **Leader Completeness (§5.4)** — every committed entry is present in
  *      every higher-term leader's log (or compacted snapshot)
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

    def encodeSeq(entries: Vector[EntryFingerprint]): Array[Byte] =
      val baos = new ByteArrayOutputStream()
      val dos = new DataOutputStream(baos)
      dos.writeInt(entries.size)
      entries.foreach { fp =>
        dos.writeLong(fp.index)
        dos.writeLong(fp.term)
        dos.writeInt(fp.logType.ordinal)
        dos.writeInt(fp.payload.size)
        dos.write(fp.payload.toArray)
      }
      dos.flush()
      baos.toByteArray

    def decodeSeq(bytes: Array[Byte]): Vector[EntryFingerprint] =
      if bytes.isEmpty then Vector.empty
      else
        val dis = new DataInputStream(new ByteArrayInputStream(bytes))
        val count = dis.readInt()
        val b = Vector.newBuilder[EntryFingerprint]
        var i = 0
        while i < count do
          val idx = dis.readLong()
          val term = dis.readLong()
          val ltOrd = dis.readInt()
          val len = dis.readInt()
          val buf = new Array[Byte](len)
          dis.readFully(buf)
          b += EntryFingerprint(
            idx,
            term,
            LogType.fromOrdinal(ltOrd),
            buf.toVector
          )
          i += 1
        b.result()

  private final case class ScheduledPacket(
      deliverAtStep: Long,
      seqNo: Long,
      envelope: OutboundMessage
  )

  private final class SimulatedNetwork(
      val nodeIds: Vector[NodeId],
      val seed: Long,
      initialVotersOpt: Option[Set[NodeId]] = None,
      leaseReadEnabled: Boolean = true,
      dropProb: Double = 0.08,
      dupProb: Double = 0.03,
      maxDelaySteps: Int = 5
  ):
    private val rng = new java.util.Random(seed)
    private val initialCluster =
      ClusterConfig(initialVotersOpt.getOrElse(nodeIds.toSet))

    val storages: Map[NodeId, MemStorage] =
      nodeIds.map(id => id -> MemStorage.create(initialCluster)).toMap

    val nodes: scala.collection.mutable.Map[NodeId, RawNode] =
      scala.collection.mutable.Map.from(
        nodeIds.zipWithIndex.map { case (id, idx) =>
          id -> bootRawNode(id, storages(id), seed + (idx + 1L) * 1009L)
        }
      )

    val crashed: scala.collection.mutable.Set[NodeId] =
      scala.collection.mutable.Set.empty

    val blockedLinks: scala.collection.mutable.Set[(NodeId, NodeId)] =
      scala.collection.mutable.Set.empty

    private var inFlight: Vector[ScheduledPacket] = Vector.empty
    private var pktSeq: Long = 0L
    private var stepNum: Long = 0L
    private var proposalCounter: Int = 0
    private var readCounter: Int = 0

    // Read linearizability tracking: requestCtx -> max globally committed index at request invocation
    val readLowerBounds: scala.collection.mutable.Map[String, Long] =
      scala.collection.mutable.Map.empty
    var completedReads: Int = 0
    var snapshotsInstalled: Int = 0
    var compactionsTriggered: Int = 0
    var transfersInitiated: Int = 0

    // Safety-invariant history trackers
    val leadersByTerm: scala.collection.mutable.Map[Long, NodeId] =
      scala.collection.mutable.Map.empty

    val leaderObservedLogs: scala.collection.mutable.Map[(NodeId, Long), Map[
      Long,
      EntryFingerprint
    ]] =
      scala.collection.mutable.Map.empty

    val committedHistory: scala.collection.mutable.Map[Long, EntryFingerprint] =
      scala.collection.mutable.Map.empty

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
        leaseReadEnabled = leaseReadEnabled,
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
          rd.snapshot.foreach { snapMeta =>
            applyInstalledSnapshotToStateMachine(id, snapMeta)
          }
          rd.messages.foreach(m => enqueueMessage(m, chaosEnabled))
          store.persistReady(rd)
          rd.persistedMessages.foreach(m => enqueueMessage(m, chaosEnabled))
          recordApplied(id, rd.committedEntries)
          verifyReadStates(id, rd.readStates)

          val light = raw.advance(rd)
          light.commitIndex.foreach { ci =>
            store.setHardState(store.hardState.copy(commitIndex = ci))
          }
          recordApplied(id, light.committedEntries)
          light.messages.foreach(m => enqueueMessage(m, chaosEnabled))

    private def applyInstalledSnapshotToStateMachine(
        id: NodeId,
        snapMeta: SnapshotMetadata
    ): Unit =
      val snapEntries = EntryFingerprint.decodeSeq(snapMeta.data)
      if snapEntries.nonEmpty then
        snapEntries.last.index shouldBe snapMeta.lastIncludedIndex
        snapEntries.foreach(fp => verifyCommittedFingerprint(id, fp))
        val existing = appliedByNode(id)
        val lastApplied = existing.lastOption.map(_.index).getOrElse(0L)
        if snapMeta.lastIncludedIndex > lastApplied then
          snapshotsInstalled += 1
          appliedByNode.update(id, snapEntries)

    private def verifyCommittedFingerprint(
        id: NodeId,
        fp: EntryFingerprint
    ): Unit =
      committedHistory.get(fp.index) match
        case Some(prev) =>
          withClue(
            s"[Seed $seed step $stepNum] State Machine Safety violated at index ${fp.index} on ${id.value}: "
          ) {
            fp shouldBe prev
          }
        case None =>
          committedHistory.update(fp.index, fp)

    private def recordApplied(id: NodeId, entries: Vector[Log]): Unit =
      if entries.nonEmpty then
        val existing = appliedByNode(id)
        val lastAppliedIdx = existing.lastOption.map(_.index).getOrElse(0L)
        val fresh = entries
          .filter(_.index > lastAppliedIdx)
          .map(EntryFingerprint.fromLog)
        appliedByNode.update(id, existing ++ fresh)
        fresh.foreach(fp => verifyCommittedFingerprint(id, fp))

    private def verifyReadStates(
        id: NodeId,
        readStates: Vector[ReadState]
    ): Unit =
      readStates.foreach { rs =>
        readLowerBounds.get(rs.requestCtx).foreach { minRequiredIdx =>
          withClue(
            s"[Seed $seed step $stepNum] ReadIndex linearizability violated on ${id.value} for ${rs.requestCtx}: returned ${rs.index} < globally committed $minRequiredIdx"
          ) {
            rs.index should be >= minRequiredIdx
          }
          completedReads += 1
        }
      }

    def compactNodeLog(id: NodeId, keepTail: Int = 6): Boolean =
      if crashed.contains(id) then false
      else
        val store = storages(id)
        val applied = appliedByNode(id)
        val lastApplied = applied.lastOption.map(_.index).getOrElse(0L)
        val compactTarget = lastApplied - keepTail.toLong
        if compactTarget >= store.firstIndex && store
            .term(compactTarget)
            .isDefined
        then
          val prefix = applied.takeWhile(_.index <= compactTarget)
          if prefix.lastOption.exists(_.index == compactTarget) then
            val snapBytes = EntryFingerprint.encodeSeq(prefix)
            store.compactTo(compactTarget, snapBytes)
            compactionsTriggered += 1
            true
          else false
        else false

    def crashNode(id: NodeId): Unit =
      if !crashed.contains(id) then crashed += id

    def restartNode(id: NodeId): Unit =
      if crashed.contains(id) then
        crashed -= id
        val store = storages(id)
        val restarted = bootRawNode(
          id,
          store,
          seed ^ (stepNum * 6364136223846793005L) ^ id.value.hashCode.toLong
        )
        // Reconstruct state machine from durable snapshot + persisted committed WAL entries
        val snapPrefix = store.snapshotMetadata
          .map(m => EntryFingerprint.decodeSeq(m.data))
          .getOrElse(Vector.empty)
        val persistedCommit = store.hardState.commitIndex
        val fromIdx =
          math.max(
            store.firstIndex,
            snapPrefix.lastOption.map(_.index + 1L).getOrElse(1L)
          )
        val walSuffix =
          if persistedCommit >= fromIdx then
            store
              .entries(fromIdx, persistedCommit + 1L)
              .map(EntryFingerprint.fromLog)
          else Vector.empty
        val recovered =
          if snapPrefix.nonEmpty || store.snapshotMetadata.isDefined then
            snapPrefix ++ walSuffix
          else
            val existing = appliedByNode(id)
            val prevLast = existing.lastOption.map(_.index).getOrElse(0L)
            if persistedCommit > prevLast then
              existing ++ store
                .entries(prevLast + 1L, persistedCommit + 1L)
                .map(EntryFingerprint.fromLog)
            else existing

        recovered.foreach(fp => verifyCommittedFingerprint(id, fp))
        appliedByNode.update(id, recovered)
        nodes.update(id, restarted)
        drainNode(id, chaosEnabled = true)

    def injectSymmetricPartition(): Unit =
      blockedLinks.clear()
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

    def activeLeaders: Vector[NodeId] =
      nodeIds.filter(id =>
        !crashed.contains(id) && nodes(id).state.isInstanceOf[Leader]
      )

    def issueReadOnLeader(leaderId: NodeId, chaosEnabled: Boolean): Unit =
      readCounter += 1
      val ctx = s"s$seed-r$readCounter"
      val globalCommitted =
        committedHistory.keysIterator.maxOption.getOrElse(0L)
      readLowerBounds.update(ctx, globalCommitted)
      if nodes(leaderId).readIndex(ctx).isRight then
        drainNode(leaderId, chaosEnabled)
      else
        val _ = readLowerBounds.remove(ctx)

    def stepSimulation(
        chaosEnabled: Boolean,
        proposeEnabled: Boolean = true,
        compactEnabled: Boolean = false,
        readEnabled: Boolean = false,
        transferEnabled: Boolean = false
    ): Unit =
      stepNum += 1L

      // 1. Occasionally inject/heal faults when chaosEnabled
      if chaosEnabled then
        val faultRoll = rng.nextInt(100)
        if faultRoll < 3 then injectSymmetricPartition()
        else if faultRoll < 7 then injectAsymmetricLinkBlock()
        else if faultRoll < 12 then blockedLinks.clear()
        else if faultRoll == 13 && crashed.size < 1 then
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
        activeLeaders.headOption.foreach { leaderId =>
          proposalCounter += 1
          val payload = s"s$seed-k$proposalCounter".getBytes
          val _ = nodes(leaderId).propose(payload)
          drainNode(leaderId, chaosEnabled)
        }

      // 4. Periodically compact applied prefixes to exercise InstallSnapshot
      if compactEnabled && stepNum % 19L == 0L then
        val candidate = nodeIds(rng.nextInt(nodeIds.size))
        val _ = compactNodeLog(candidate, keepTail = 5)

      // 5. Periodically issue ReadIndex against any live leader (including potentially partitioned ones!)
      if readEnabled && stepNum % 7L == 0L then
        val leaders = activeLeaders
        if leaders.nonEmpty then
          val chosen = leaders(rng.nextInt(leaders.size))
          issueReadOnLeader(chosen, chaosEnabled)

      // 6. Occasionally trigger graceful leadership transfer
      if transferEnabled && stepNum % 47L == 0L then
        activeLeaders.headOption.foreach { leaderId =>
          val voters =
            (nodes(leaderId).clusterConfig.voters - leaderId)
              .filterNot(crashed.contains)
              .toVector
              .sortBy(_.value)
          if voters.nonEmpty then
            val target = voters(rng.nextInt(voters.size))
            if nodes(leaderId).transferLeader(target).isRight then
              transfersInitiated += 1
              drainNode(leaderId, chaosEnabled)
        }

      // 7. Deliver all packets scheduled for `deliverAtStep <= stepNum`
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

      // 8. Verify all 5 Raft safety invariants!
      verifyAllInvariants()

    def verifyAllInvariants(): Unit =
      nodeIds.foreach { id =>
        if !crashed.contains(id) then
          val raw = nodes(id)
          val store = storages(id)
          raw.state match
            case l: Leader =>
              // Invariant 1: Election Safety
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
              val snapEntries = store.snapshotMetadata
                .map(m =>
                  EntryFingerprint
                    .decodeSeq(m.data)
                    .map(fp => fp.index -> fp)
                    .toMap
                )
                .getOrElse(Map.empty)

              // Invariant 2: Leader Append-Only
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
                else if snapEntries.nonEmpty then
                  snapEntries.get(idx) shouldBe Some(prevFp)
              }
              leaderObservedLogs.update(
                key,
                prevLeaderEntries ++ currentEntries
              )

              // Invariant 4: Leader Completeness (across WAL suffix and compacted snapshot)
              committedHistory.foreach { case (cIdx, cFp) =>
                if cFp.term < l.term then
                  if cIdx >= store.firstIndex then
                    withClue(
                      s"[Seed $seed step $stepNum] Leader Completeness violated on ${id.value} term ${l.term} missing committed idx $cIdx:"
                    ) {
                      currentEntries.get(cIdx) shouldBe Some(cFp)
                    }
                  else if snapEntries.nonEmpty then
                    withClue(
                      s"[Seed $seed step $stepNum] Leader Completeness violated in snapshot on ${id.value} term ${l.term} idx $cIdx:"
                    ) {
                      snapEntries.get(cIdx) shouldBe Some(cFp)
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

      for _ <- 1 to 250 do sim.stepSimulation(chaosEnabled = true)

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

  "Deterministic Simulation (Full-Feature Chaos: Joint Consensus 3->5->3 + Compaction + InstallSnapshot + TransferLeader + ReadIndex)" should
    "preserve all 5 safety invariants and ReadIndex linearizability across 6 seeds" in {
      val allIds = (1 to 5).map(i => NodeId(s"n$i")).toVector
      val initialThree = Set(NodeId("n1"), NodeId("n2"), NodeId("n3"))
      val fiveMembers = allIds.map(_ -> NodeRole.Voter).toMap
      val finalThreeMembers = initialThree.map(_ -> NodeRole.Voter).toMap

      var totalSnapshotsInstalled = 0
      var totalCompletedReads = 0
      var totalTransfers = 0

      for seed <- 201L to 206L do
        val sim = new SimulatedNetwork(
          nodeIds = allIds,
          seed = seed,
          initialVotersOpt = Some(initialThree),
          leaseReadEnabled = false
        )

        // Stage 1: Boot 3-voter cluster under chaos, write & compact logs so n4/n5 will lag behind snapshot!
        for _ <- 1 to 90 do
          sim.stepSimulation(
            chaosEnabled = true,
            proposeEnabled = true,
            compactEnabled = true,
            readEnabled = true,
            transferEnabled = true
          )

        // Force compaction on all initial 3 nodes if enough entries were applied
        initialThree.foreach(id => sim.compactNodeLog(id, keepTail = 3))

        // Stage 2: Expand 3 -> 5 voters via Joint Consensus (C_{1..3},{1..5} -> auto C_{1..5})
        sim.healNetwork()
        var expandedProposed = false
        var guard1 = 0
        while !expandedProposed && guard1 < 60 do
          sim.stepSimulation(
            chaosEnabled = false,
            proposeEnabled = true,
            compactEnabled = false,
            readEnabled = true
          )
          sim.activeLeaders.headOption.foreach { leaderId =>
            if sim.nodes(leaderId).proposeConfChange(fiveMembers).isRight then
              sim.drainNode(leaderId, chaosEnabled = false)
              expandedProposed = true
          }
          guard1 += 1

        expandedProposed shouldBe true

        // Run chaos across the 5-voter cluster (n4 & n5 catch up via InstallSnapshot, participate in quorums, transfers & reads)
        for _ <- 1 to 140 do
          sim.stepSimulation(
            chaosEnabled = true,
            proposeEnabled = true,
            compactEnabled = true,
            readEnabled = true,
            transferEnabled = true
          )

        // Stage 3: Shrink 5 -> 3 voters via Joint Consensus (including potential leader self-removal if n4/n5 leads!)
        sim.healNetwork()
        var shrunkProposed = false
        var guard2 = 0
        while !shrunkProposed && guard2 < 80 do
          sim.stepSimulation(
            chaosEnabled = false,
            proposeEnabled = true,
            compactEnabled = false,
            readEnabled = true
          )
          sim.activeLeaders.headOption.foreach { leaderId =>
            val cfg = sim.nodes(leaderId).clusterConfig
            if !cfg.isInJointConsensus && cfg.voters.size == 5 then
              if sim
                  .nodes(leaderId)
                  .proposeConfChange(finalThreeMembers)
                  .isRight
              then
                sim.drainNode(leaderId, chaosEnabled = false)
                shrunkProposed = true
          }
          guard2 += 1

        shrunkProposed shouldBe true

        // Stage 4: Finalize shrink, run more writes/reads, and converge final 3-voter cluster
        for _ <- 1 to 70 do
          sim.stepSimulation(
            chaosEnabled = false,
            proposeEnabled = true,
            compactEnabled = true,
            readEnabled = true,
            transferEnabled = true
          )
        for _ <- 1 to 40 do
          sim.stepSimulation(chaosEnabled = false, proposeEnabled = false)

        val finalVoters = initialThree.toVector.sortBy(_.value)
        val leaders =
          finalVoters.filter(id => sim.nodes(id).state.isInstanceOf[Leader])
        withClue(
          s"[Seed $seed] Expected single converged leader in final 3-voter config:"
        ) {
          leaders should have size 1
        }

        val leaderCfg = sim.nodes(leaders.head).clusterConfig
        leaderCfg.isInJointConsensus shouldBe false
        leaderCfg.voters shouldBe initialThree

        val refApplied = sim.appliedByNode(leaders.head)
        refApplied should not be empty
        finalVoters.foreach { id =>
          withClue(
            s"[Seed $seed] Final voter ${id.value} failed to converge:"
          ) {
            sim.appliedByNode(id) shouldBe refApplied
          }
        }

        totalSnapshotsInstalled += sim.snapshotsInstalled
        totalCompletedReads += sim.completedReads
        totalTransfers += sim.transfersInitiated

      totalSnapshotsInstalled should be > 0
      totalCompletedReads should be > 0
      totalTransfers should be > 0
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
