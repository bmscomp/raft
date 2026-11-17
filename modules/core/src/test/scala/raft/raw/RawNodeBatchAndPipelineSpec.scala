package raft.raw

import java.nio.charset.StandardCharsets
import scala.util.Random

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.logic.ProgressState
import raft.message.RaftMessage.*
import raft.state.*
import raft.state.NodeState.*

/** Unit and deterministic simulation tests for [[RawNode.proposeBatch]] and
  * `PipelineConfig` (`Inflights` sliding-window flow control, stop-and-wait
  * gating, out-of-order ACK resilience, rejection fallback, and dropped-window
  * heartbeat recovery).
  */
class RawNodeBatchAndPipelineSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("node-1")
  private val n2 = NodeId("node-2")
  private val n3 = NodeId("node-3")

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  private def mkNode(
      id: NodeId,
      cluster: ClusterConfig,
      seed: Long,
      pipelineEnabled: Boolean = true,
      maxInflight: Int = 4,
      maxEntriesPerReq: Int = 4
  ): (RawNode, MemStorage) =
    val storage = MemStorage.create(cluster)
    val cfg = RaftConfig(
      localId = id,
      electionTimeoutTicks = 10,
      heartbeatIntervalTicks = 3,
      maxEntriesPerRequest = maxEntriesPerReq,
      pipelining = PipelineConfig(
        enabled = pipelineEnabled,
        maxInflight = maxInflight
      )
    )
    (new RawNode(cfg, storage, seed), storage)

  private def drainNode(
      raw: RawNode,
      storage: MemStorage
  ): (Vector[OutboundMessage], Vector[Log]) =
    if !raw.hasReady then (Vector.empty, Vector.empty)
    else
      val rd = raw.ready()
      val preMsgs = rd.messages
      storage.persistReady(rd)
      val postMsgs = rd.persistedMessages
      val applied1 = rd.committedEntries
      val light = raw.advance(rd)
      light.commitIndex.foreach { ci =>
        storage.setHardState(storage.hardState.copy(commitIndex = ci))
      }
      (
        preMsgs ++ postMsgs ++ light.messages,
        applied1 ++ light.committedEntries
      )

  private def pumpCluster(
      nodes: Map[NodeId, (RawNode, MemStorage)]
  ): Unit =
    var queue = Vector.empty[OutboundMessage]

    def drainAll(): Unit =
      nodes.keys.toSeq.sortBy(_.value).foreach { id =>
        val (raw, store) = nodes(id)
        var guard = 0
        while raw.hasReady && guard < 50 do
          guard += 1
          val (msgs, _) = drainNode(raw, store)
          queue = queue ++ msgs
      }

    drainAll()
    var stepGuard = 0
    while queue.nonEmpty && stepGuard < 500 do
      stepGuard += 1
      val next = queue.head
      queue = queue.tail
      nodes.get(next.to).foreach { case (raw, _) =>
        raw.step(next.from, next.message)
      }
      drainAll()

  private def electAndSettle(
      nodes: Map[NodeId, (RawNode, MemStorage)]
  ): (NodeId, RawNode, MemStorage) =
    var step = 0
    while !nodes.values.exists(_._1.state.isInstanceOf[Leader]) && step < 100 do
      step += 1
      nodes.values.foreach(_._1.tick())
      pumpCluster(nodes)
    pumpCluster(nodes)
    val (lid, (lraw, lstore)) =
      nodes.find(_._2._1.state.isInstanceOf[Leader]).get
    (lid, lraw, lstore)

  "RawNode.proposeBatch" should "append contiguous entries in one Ready slice and reject non-leaders" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    val nodes = Map(
      n1 -> mkNode(n1, cluster, seed = 101L, maxEntriesPerReq = 100),
      n2 -> mkNode(n2, cluster, seed = 202L, maxEntriesPerReq = 100),
      n3 -> mkNode(n3, cluster, seed = 303L, maxEntriesPerReq = 100)
    )

    val (leaderId, leaderRaw, _) = electAndSettle(nodes)
    val followerRaw = nodes.find(_._1 != leaderId).get._2._1

    // Non-leader rejects proposeBatch with leader hint
    followerRaw.proposeBatch(Seq(utf8("x=1"), utf8("x=2"))) shouldBe Left(
      RaftError.NotLeader(Some(leaderId))
    )

    // Empty batch on leader returns Right(Vector.empty) without generating Ready work
    leaderRaw.proposeBatch(Seq.empty) shouldBe Right(Vector.empty)
    leaderRaw.hasReady shouldBe false

    // Batch of 10 commands produces 1 contiguous Ready.entries slice [2..11]
    val payloads = (1 to 10).map(i => utf8(s"batch-$i"))
    val assigned = leaderRaw.proposeBatch(payloads)
    assigned shouldBe Right((2L to 11L).toVector)

    val rd = leaderRaw.ready()
    rd.entries.map(_.index) shouldBe (2L to 11L).toVector
    val appendMsgs = rd.messages.collect {
      case OutboundMessage(_, _, ae: AppendEntriesRequest) => ae
    }
    // Single batched AppendEntriesRequest per follower (2 followers total)
    appendMsgs should have size 2
    appendMsgs.foreach(_.entries.size shouldBe 10)

    nodes(leaderId)._2.persistReady(rd)
    val _ = leaderRaw.advance(rd)
    rd.messages.foreach { env =>
      nodes(env.to)._1.step(env.from, env.message)
    }
    pumpCluster(nodes)

    nodes.values.foreach { case (raw, store) =>
      raw.commitIndex shouldBe 11L
      raw.lastApplied shouldBe 11L
      store
        .entries(2L, 12L)
        .map(e => new String(e.data, StandardCharsets.UTF_8)) shouldBe
        (1 to 10).map(i => s"batch-$i").toVector
    }
  }

  it should "reject proposeBatch during ElectionInProgress and LeadershipTransferInProgress" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    val (r1, s1) = mkNode(n1, cluster, seed = 1L)
    while !r1.state.isInstanceOf[PreCandidate] && !r1.state
        .isInstanceOf[Candidate]
    do r1.tick()
    val _ = drainNode(r1, s1)
    r1.proposeBatch(Seq(utf8("cmd"))) shouldBe Left(
      RaftError.ElectionInProgress
    )

    val nodes = Map(
      n1 -> mkNode(n1, cluster, seed = 11L),
      n2 -> mkNode(n2, cluster, seed = 22L),
      n3 -> mkNode(n3, cluster, seed = 33L)
    )
    val (leaderId, leaderRaw, _) = electAndSettle(nodes)
    val target = (Set(n1, n2, n3) - leaderId).head
    // Propose an un-replicated entry so transferTarget remains pending catch-up
    val _ = leaderRaw.propose(utf8("hold"))
    leaderRaw.transferLeader(target) shouldBe Right(())
    leaderRaw.proposeBatch(Seq(utf8("blocked"))) shouldBe Left(
      RaftError.LeadershipTransferInProgress(target)
    )
  }

  "RawNode PipelineConfig" should "gate stop-and-wait (max 1 RPC) when disabled vs burst up to maxInflight when enabled" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))

    // Case 1: pipelining.enabled = false -> stop-and-wait (at most 1 in-flight batch per follower)
    val stopAndWaitNodes = Map(
      n1 -> mkNode(
        n1,
        cluster,
        seed = 10L,
        pipelineEnabled = false,
        maxInflight = 4,
        maxEntriesPerReq = 3
      ),
      n2 -> mkNode(
        n2,
        cluster,
        seed = 20L,
        pipelineEnabled = false,
        maxInflight = 4,
        maxEntriesPerReq = 3
      ),
      n3 -> mkNode(
        n3,
        cluster,
        seed = 30L,
        pipelineEnabled = false,
        maxInflight = 4,
        maxEntriesPerReq = 3
      )
    )
    val (swLeaderId, swLeader, swStore) = electAndSettle(stopAndWaitNodes)
    val swPeer = (Set(n1, n2, n3) - swLeaderId).toSeq.sortBy(_.value).head

    // Propose 12 entries (4 chunks of size 3)
    swLeader
      .proposeBatch((1 to 12).map(i => utf8(s"sw-$i")))
      .isRight shouldBe true
    val (swOutbound1, _) = drainNode(swLeader, swStore)
    val swToPeer1 = swOutbound1.collect {
      case OutboundMessage(_, to, ae: AppendEntriesRequest)
          if to == swPeer && ae.entries.nonEmpty =>
        ae
    }
    // Stop-and-wait MUST dispatch only 1 batch [2..4] and pause until ACKed
    swToPeer1 should have size 1
    swToPeer1.head.entries.map(_.index) shouldBe Vector(2L, 3L, 4L)
    swLeader.progressTracker.progress(swPeer).isPaused shouldBe true

    // Case 2: pipelining.enabled = true, maxInflight = 3 -> immediately pipelines 3 batches [2..4], [5..7], [8..10]
    val pipeNodes = Map(
      n1 -> mkNode(
        n1,
        cluster,
        seed = 10L,
        pipelineEnabled = true,
        maxInflight = 3,
        maxEntriesPerReq = 3
      ),
      n2 -> mkNode(
        n2,
        cluster,
        seed = 20L,
        pipelineEnabled = true,
        maxInflight = 3,
        maxEntriesPerReq = 3
      ),
      n3 -> mkNode(
        n3,
        cluster,
        seed = 30L,
        pipelineEnabled = true,
        maxInflight = 3,
        maxEntriesPerReq = 3
      )
    )
    val (pLeaderId, pLeader, pStore) = electAndSettle(pipeNodes)
    val pPeer = (Set(n1, n2, n3) - pLeaderId).toSeq.sortBy(_.value).head

    pLeader
      .proposeBatch((1 to 12).map(i => utf8(s"pipe-$i")))
      .isRight shouldBe true
    val (pOutbound1, _) = drainNode(pLeader, pStore)
    val pToPeer1 = pOutbound1.collect {
      case OutboundMessage(_, to, ae: AppendEntriesRequest)
          if to == pPeer && ae.entries.nonEmpty =>
        ae
    }

    // 3 batches dispatched immediately; 4th batch [11..13] held back by full Inflights(3) window
    pToPeer1 should have size 3
    pToPeer1.map(_.entries.map(_.index)) shouldBe Vector(
      Vector(2L, 3L, 4L),
      Vector(5L, 6L, 7L),
      Vector(8L, 9L, 10L)
    )
    val prAfterBurst = pLeader.progressTracker.progress(pPeer)
    prAfterBurst.inflights.isFull shouldBe true
    prAfterBurst.inflights.buffer shouldBe Vector(4L, 7L, 10L)
    prAfterBurst.nextIdx shouldBe 11L

    // Feed ACK for batch 1 (matchIndex = 4) -> frees 1 slot and immediately dispatches [11..13]!
    pLeader.step(
      pPeer,
      AppendEntriesResponse(
        pLeader.currentTerm,
        success = true,
        matchIndex = 4L
      )
    )
    val (pOutbound2, _) = drainNode(pLeader, pStore)
    val pToPeer2 = pOutbound2.collect {
      case OutboundMessage(_, to, ae: AppendEntriesRequest)
          if to == pPeer && ae.entries.nonEmpty =>
        ae
    }
    pToPeer2 should have size 1
    pToPeer2.head.entries.map(_.index) shouldBe Vector(11L, 12L, 13L)
    pLeader.progressTracker.progress(pPeer).inflights.buffer shouldBe Vector(
      7L,
      10L,
      13L
    )

    // Out-of-order stale ACK (e.g. matchIndex = 7 arrives after matchIndex = 10) MUST NOT reset Replicate -> Probe!
    pLeader.step(
      pPeer,
      AppendEntriesResponse(
        pLeader.currentTerm,
        success = true,
        matchIndex = 10L
      )
    )
    val _ = drainNode(pLeader, pStore)
    pLeader.progressTracker.progress(pPeer).matched shouldBe 10L
    pLeader.progressTracker
      .progress(pPeer)
      .state shouldBe ProgressState.Replicate
    pLeader.progressTracker.progress(pPeer).inflights.buffer shouldBe Vector(
      13L
    )

    pLeader.step(
      pPeer,
      AppendEntriesResponse(
        pLeader.currentTerm,
        success = true,
        matchIndex = 7L
      )
    )
    val (staleMsgs, _) = drainNode(pLeader, pStore)
    staleMsgs.collect {
      case OutboundMessage(_, to, ae: AppendEntriesRequest)
          if to == pPeer && ae.entries.nonEmpty =>
        ae
    } shouldBe empty
    pLeader.progressTracker
      .progress(pPeer)
      .state shouldBe ProgressState.Replicate
    pLeader.progressTracker.progress(pPeer).inflights.buffer shouldBe Vector(
      13L
    )
  }

  it should "revert Replicate -> Probe on conflict rejection and recover dropped pipelined windows on heartbeat" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    val nodes = Map(
      n1 -> mkNode(
        n1,
        cluster,
        seed = 41L,
        pipelineEnabled = true,
        maxInflight = 3,
        maxEntriesPerReq = 2
      ),
      n2 -> mkNode(
        n2,
        cluster,
        seed = 42L,
        pipelineEnabled = true,
        maxInflight = 3,
        maxEntriesPerReq = 2
      ),
      n3 -> mkNode(
        n3,
        cluster,
        seed = 43L,
        pipelineEnabled = true,
        maxInflight = 3,
        maxEntriesPerReq = 2
      )
    )
    val (leaderId, leaderRaw, leaderStore) = electAndSettle(nodes)
    val peer = (Set(n1, n2, n3) - leaderId).toSeq.sortBy(_.value).head

    // Pipeline 6 entries (3 batches of 2: [2..3], [4..5], [6..7]) and DROP all outbound packets to `peer`!
    leaderRaw
      .proposeBatch((1 to 6).map(i => utf8(s"drop-$i")))
      .isRight shouldBe true
    val (droppedMsgs, _) = drainNode(leaderRaw, leaderStore)
    // Deliver only to the OTHER follower so `peer` never receives [2..7]
    droppedMsgs.filter(_.to != peer).foreach { env =>
      nodes(env.to)._1.step(env.from, env.message)
    }
    pumpCluster(nodes.removed(peer))
    leaderRaw.progressTracker.progress(peer).inflights.isFull shouldBe true

    // Advance leader ticks to trigger Heartbeat -> deliver heartbeat to `peer` and its ACK back
    for _ <- 1 to 4 do leaderRaw.tick()
    pumpCluster(nodes)

    // `peer` must recover cleanly via heartbeat -> Probe -> Replicate and catch up to index 7!
    nodes(peer)._1.commitIndex shouldBe 7L
    nodes(peer)._2.lastIndex shouldBe 7L
  }

  it should "preserve State Machine Safety under randomized packet drops, delays, and reordering with pipelining enabled" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    for seed <- Seq(1001L, 2002L, 3003L) do
      val rng = new Random(seed)
      val nodes = Map(
        n1 -> mkNode(
          n1,
          cluster,
          seed = seed + 1L,
          pipelineEnabled = true,
          maxInflight = 4,
          maxEntriesPerReq = 2
        ),
        n2 -> mkNode(
          n2,
          cluster,
          seed = seed + 2L,
          pipelineEnabled = true,
          maxInflight = 4,
          maxEntriesPerReq = 2
        ),
        n3 -> mkNode(
          n3,
          cluster,
          seed = seed + 3L,
          pipelineEnabled = true,
          maxInflight = 4,
          maxEntriesPerReq = 2
        )
      )
      var wire = Vector.empty[(Int, OutboundMessage)]
      var applied = Map
        .empty[NodeId, Vector[(Long, Long, String)]]
        .withDefaultValue(Vector.empty)
      var cmdSeq = 0

      for step <- 1 to 220 do
        val chaos = step < 150
        if step % 3 == 0 then nodes.values.foreach(_._1.tick())

        if chaos && step % 6 == 0 then
          nodes.values.find(_._1.state.isInstanceOf[Leader]).foreach {
            case (lraw, _) =>
              val batchCount = 1 + rng.nextInt(5)
              val cmds = (1 to batchCount).map { _ =>
                cmdSeq += 1
                utf8(s"k-$cmdSeq")
              }
              val _ = lraw.proposeBatch(cmds)
          }

        nodes.foreach { case (id, (raw, store)) =>
          while raw.hasReady do
            val (msgs, app) = drainNode(raw, store)
            val fps = app
              .filter(_.logType == LogType.Command)
              .map(e =>
                (e.index, e.term, new String(e.data, StandardCharsets.UTF_8))
              )
            if fps.nonEmpty then
              applied = applied.updated(id, applied(id) ++ fps)
            msgs.foreach { m =>
              if !chaos || rng.nextDouble() >= 0.18 then
                val delay = if chaos then 1 + rng.nextInt(4) else 1
                wire = wire :+ (step + delay, m)
            }
        }

        val (due, later) = wire.partition(_._1 <= step)
        wire = later
        val orderedDue = if chaos then rng.shuffle(due) else due
        orderedDue.foreach { case (_, env) =>
          nodes.get(env.to).foreach(_._1.step(env.from, env.message))
        }

      val refHistory = applied(n1)
      refHistory should not be empty
      applied(n2) shouldBe refHistory
      applied(n3) shouldBe refHistory
  }
