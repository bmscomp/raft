package raft.raw

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.logic.ProgressState
import raft.state.*
import raft.state.NodeState.*

/** Comprehensive tests for the Sans-I/O [[RawNode]], [[Ready]], [[LightReady]],
  * [[ProgressTracker]], and [[MemStorage]] consensus engine.
  */
class RawNodeSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("node-1")
  private val n2 = NodeId("node-2")
  private val n3 = NodeId("node-3")
  private val n4 = NodeId("node-4")

  private def mkNode(
      id: NodeId,
      cluster: ClusterConfig,
      seed: Long = 42L,
      preVote: Boolean = true,
      checkQuorum: Boolean = true,
      leaseRead: Boolean = false,
      maxInflight: Int = 4
  ): (RawNode, MemStorage) =
    val storage = MemStorage.create(cluster)
    val cfg = RaftConfig(
      localId = id,
      preVoteEnabled = preVote,
      checkQuorumEnabled = checkQuorum,
      leaseReadEnabled = leaseRead,
      electionTimeoutTicks = 10,
      heartbeatIntervalTicks = 3,
      pipelining = PipelineConfig(enabled = true, maxInflight = maxInflight)
    )
    (new RawNode(cfg, storage, seed), storage)

  /** Drain a single node's Ready + LightReady cycle, returning all outbound
    * wire messages, newly applied log entries, and ready read states.
    */
  private def drainNode(
      raw: RawNode,
      storage: MemStorage
  ): (Vector[OutboundMessage], Vector[Log], Vector[ReadState]) =
    if !raw.hasReady then (Vector.empty, Vector.empty, Vector.empty)
    else
      val rd = raw.ready()
      val preMsgs = rd.messages
      storage.persistReady(rd)
      val postMsgs = rd.persistedMessages
      val applied1 = rd.committedEntries
      val reads = rd.readStates
      val light = raw.advance(rd)
      light.commitIndex.foreach { ci =>
        storage.setHardState(storage.hardState.copy(commitIndex = ci))
      }
      (
        preMsgs ++ postMsgs ++ light.messages,
        applied1 ++ light.committedEntries,
        reads
      )

  /** Deliver all queued messages across an in-memory map of RawNodes until
    * quiescent.
    */
  private def pumpCluster(
      nodes: Map[NodeId, (RawNode, MemStorage)]
  ): (Map[NodeId, Vector[Log]], Map[NodeId, Vector[ReadState]]) =
    var queue = Vector.empty[OutboundMessage]
    var applied = Map.empty[NodeId, Vector[Log]].withDefaultValue(Vector.empty)
    var reads =
      Map.empty[NodeId, Vector[ReadState]].withDefaultValue(Vector.empty)

    def drainAll(): Unit =
      nodes.keys.toSeq.sortBy(_.value).foreach { id =>
        val (raw, store) = nodes(id)
        var drainGuard = 0
        while raw.hasReady && drainGuard < 50 do
          drainGuard += 1
          val (msgs, app, rds) = drainNode(raw, store)
          queue = queue ++ msgs
          if app.nonEmpty then applied = applied.updated(id, applied(id) ++ app)
          if rds.nonEmpty then reads = reads.updated(id, reads(id) ++ rds)
      }

    drainAll()
    var guard = 0
    while queue.nonEmpty && guard < 500 do
      guard += 1
      val next = queue.head
      queue = queue.tail
      nodes.get(next.to).foreach { case (raw, _) =>
        raw.step(next.from, next.message)
      }
      drainAll()
    (applied, reads)

  "RawNode in a 1-node cluster" should "elect itself immediately and commit its NoOp and proposals" in {
    val (raw, store) = mkNode(n1, ClusterConfig(Set(n1)))

    var ticks = 0
    while !raw.state.isInstanceOf[Leader] && ticks < 30 do
      raw.tick()
      ticks += 1

    raw.state shouldBe a[Leader]
    raw.currentTerm shouldBe 1L

    val (msgs1, applied1, _) = drainNode(raw, store)
    msgs1 shouldBe empty
    applied1.map(_.logType) shouldBe Vector(LogType.NoOp)
    raw.commitIndex shouldBe 1L
    raw.lastApplied shouldBe 1L

    raw.propose("set x=1".getBytes) shouldBe Right(2L)
    val (_, applied2, _) = drainNode(raw, store)
    applied2 should have size 1
    new String(applied2.head.data) shouldBe "set x=1"
    raw.commitIndex shouldBe 2L

    raw.readIndex("r-1") shouldBe Right(())
    val (_, _, reads) = drainNode(raw, store)
    reads shouldBe Vector(ReadState(2L, "r-1"))
  }

  "RawNode in a 3-node cluster" should "run Pre-Vote, elect a leader, commit NoOp, and replicate proposals" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    val nodes = Map(
      n1 -> mkNode(n1, cluster, seed = 100L),
      n2 -> mkNode(n2, cluster, seed = 200L),
      n3 -> mkNode(n3, cluster, seed = 300L)
    )

    var round = 0
    while !nodes.values.exists(_._1.state.isInstanceOf[Leader]) && round < 40 do
      round += 1
      nodes.values.foreach(_._1.tick())
      val _ = pumpCluster(nodes)

    val leaders = nodes.collect {
      case (id, (raw, _)) if raw.state.isInstanceOf[Leader] => (id, raw)
    }
    leaders should have size 1
    val (leaderId, leaderRaw) = leaders.head

    // Ensure leader's mandatory NoOp commits across cluster
    val _ = pumpCluster(nodes)
    leaderRaw.commitIndex shouldBe 1L

    // Follower proposal returns NotLeader(Some(leaderId))
    val followerRaw = nodes.collectFirst {
      case (id, (r, _)) if id != leaderId => r
    }.get
    followerRaw.propose("bad".getBytes) shouldBe Left(
      RaftError.NotLeader(Some(leaderId))
    )

    // Propose 3 entries on leader and verify replication + state-machine apply
    leaderRaw.propose("cmd-1".getBytes) shouldBe Right(2L)
    leaderRaw.propose("cmd-2".getBytes) shouldBe Right(3L)
    leaderRaw.propose("cmd-3".getBytes) shouldBe Right(4L)
    val _ = pumpCluster(nodes)

    nodes.values.foreach { case (raw, store) =>
      raw.commitIndex shouldBe 4L
      raw.lastApplied shouldBe 4L
      store.lastIndex shouldBe 4L
      store.entries(2L, 5L).map(e => new String(e.data)) shouldBe Vector(
        "cmd-1",
        "cmd-2",
        "cmd-3"
      )
    }

    // Followers must have transitioned from Probe -> Replicate in ProgressTracker
    val peers = Set(n1, n2, n3) - leaderId
    peers.foreach { p =>
      leaderRaw.progressTracker
        .progress(p)
        .state shouldBe ProgressState.Replicate
    }
  }

  it should "prevent a partitioned Pre-Vote node from inflating terms or disrupting the leader" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    val (raw1, s1) = mkNode(n1, cluster, seed = 1L)
    val (raw2, s2) = mkNode(n2, cluster, seed = 2L)
    val (raw3, s3) = mkNode(n3, cluster, seed = 3L)

    // Partition n3 away; elect leader among {n1, n2}
    val connected = Map(n1 -> (raw1, s1), n2 -> (raw2, s2))
    var t = 0
    while !connected.values.exists(_._1.state.isInstanceOf[Leader]) && t < 40 do
      t += 1
      raw1.tick()
      raw2.tick()
      val _ = pumpCluster(connected)

    val activeTerm = math.max(raw1.currentTerm, raw2.currentTerm)

    // Tick isolated n3 for 100 ticks (many election timeouts)
    for _ <- 1 to 100 do
      raw3.tick()
      val _ = drainNode(raw3, s3)

    // Pre-Vote invariant: n3's term NEVER increased while isolated!
    raw3.currentTerm shouldBe 0L
    raw3.state shouldBe a[PreCandidate]

    // Reconnect n3 and run heartbeats: active leader stays undisturbed at activeTerm
    val full = Map(n1 -> (raw1, s1), n2 -> (raw2, s2), n3 -> (raw3, s3))
    for _ <- 1 to 10 do
      full.values.foreach(_._1.tick())
      val _ = pumpCluster(full)

    raw3.currentTerm shouldBe activeTerm
    full.values.count(_._1.state.isInstanceOf[Leader]) shouldBe 1
  }

  private def electLeader(
      nodes: Map[NodeId, (RawNode, MemStorage)],
      tickIds: Iterable[NodeId] = Nil
  ): Unit =
    val idsToTick = if tickIds.isEmpty then nodes.keys else tickIds
    var step = 0
    while !nodes.values.exists(_._1.state.isInstanceOf[Leader]) && step < 150 do
      step += 1
      idsToTick.foreach(id => nodes(id)._1.tick())
      val _ = pumpCluster(nodes)
    nodes.values.exists(_._1.state.isInstanceOf[Leader]) shouldBe true
    val _ = pumpCluster(nodes)

  it should "step down leader via CheckQuorum when isolated from a majority" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    val nodes = Map(
      n1 -> mkNode(n1, cluster, seed = 10L, checkQuorum = true),
      n2 -> mkNode(n2, cluster, seed = 20L, checkQuorum = true),
      n3 -> mkNode(n3, cluster, seed = 30L, checkQuorum = true)
    )

    electLeader(nodes)

    val (leaderId, (leaderRaw, leaderStore)) =
      nodes.find(_._2._1.state.isInstanceOf[Leader]).get

    // Isolate leader completely and advance two electionTimeoutTicks windows
    for _ <- 1 to 25 do
      leaderRaw.tick()
      val _ = drainNode(leaderRaw, leaderStore)

    leaderRaw.state shouldBe a[Follower]
    leaderRaw.propose("stale".getBytes) shouldBe Left(
      RaftError.NotLeader(None)
    )
    leaderId should not be NodeId("")
  }

  it should "serve ReadIndex via quorum heartbeat and LeaseRead locally within lease" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    val nodes = Map(
      n1 -> mkNode(n1, cluster, seed = 7L, leaseRead = false),
      n2 -> mkNode(n2, cluster, seed = 8L, leaseRead = false),
      n3 -> mkNode(n3, cluster, seed = 9L, leaseRead = false)
    )

    electLeader(nodes)

    val (leaderId, (leaderRaw, _)) =
      nodes.find(_._2._1.state.isInstanceOf[Leader]).get

    leaderRaw.propose("v1".getBytes) shouldBe Right(2L)
    val _ = pumpCluster(nodes)

    leaderRaw.readIndex("read-quorum-1") shouldBe Right(())
    val (_, readsMap) = pumpCluster(nodes)
    readsMap(leaderId) should contain(ReadState(2L, "read-quorum-1"))

    // Now test zero-RTT LeaseRead when leaseReadEnabled = true
    val leaseNodes = Map(
      n1 -> mkNode(n1, cluster, seed = 17L, leaseRead = true),
      n2 -> mkNode(n2, cluster, seed = 18L, leaseRead = true),
      n3 -> mkNode(n3, cluster, seed = 19L, leaseRead = true)
    )
    electLeader(leaseNodes)

    val (_, (leaseLeader, leaseStore)) =
      leaseNodes.find(_._2._1.state.isInstanceOf[Leader]).get

    leaseLeader.readIndex("lease-fast-1") shouldBe Right(())
    val (leaseMsgs, _, leaseReads) = drainNode(leaseLeader, leaseStore)
    leaseMsgs shouldBe empty
    leaseReads shouldBe Vector(ReadState(1L, "lease-fast-1"))
  }

  it should "transfer leadership cleanly to a target voter via TimeoutNow" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    val nodes = Map(
      n1 -> mkNode(n1, cluster, seed = 11L),
      n2 -> mkNode(n2, cluster, seed = 22L),
      n3 -> mkNode(n3, cluster, seed = 33L)
    )

    electLeader(nodes)

    val (oldLeaderId, (oldLeader, _)) =
      nodes.find(_._2._1.state.isInstanceOf[Leader]).get
    val targetId = (Set(n1, n2, n3) - oldLeaderId).toSeq.sortBy(_.value).head

    oldLeader.propose("before-transfer".getBytes) shouldBe Right(2L)
    val _ = pumpCluster(nodes)

    oldLeader.transferLeader(targetId) shouldBe Right(())
    val _ = pumpCluster(nodes)

    nodes(targetId)._1.state shouldBe a[Leader]
    oldLeader.state shouldBe a[Follower]
  }

  it should "execute Joint Consensus (C_old,new -> C_new) automatically upon commit" in {
    val initialCluster = ClusterConfig(Set(n1, n2, n3))
    val nodes = scala.collection.mutable.Map(
      n1 -> mkNode(n1, initialCluster, seed = 51L),
      n2 -> mkNode(n2, initialCluster, seed = 52L),
      n3 -> mkNode(n3, initialCluster, seed = 53L),
      n4 -> mkNode(n4, initialCluster, seed = 54L)
    )

    electLeader(nodes.toMap, List(n1, n2, n3))

    val (_, (leader, _)) = nodes.find(_._2._1.state.isInstanceOf[Leader]).get

    val newMembers = Map(
      n1 -> NodeRole.Voter,
      n2 -> NodeRole.Voter,
      n3 -> NodeRole.Voter,
      n4 -> NodeRole.Voter
    )
    leader.proposeConfChange(newMembers).isRight shouldBe true
    leader.clusterConfig.isInJointConsensus shouldBe true

    val _ = pumpCluster(nodes.toMap)

    // Joint config commits -> leader auto-appends final C_new -> C_new commits!
    leader.clusterConfig.isInJointConsensus shouldBe false
    leader.clusterConfig.voters shouldBe Set(n1, n2, n3, n4)
    nodes(n4)._1.clusterConfig.voters shouldBe Set(n1, n2, n3, n4)
  }

  it should "send InstallSnapshot when a follower lags behind compacted storage" in {
    val cluster = ClusterConfig(Set(n1, n2, n3))
    val (r1, s1) = mkNode(n1, cluster, seed = 71L)
    val (r2, s2) = mkNode(n2, cluster, seed = 72L)
    val (r3, s3) = mkNode(n3, cluster, seed = 73L)

    // Elect leader among n1 and n2 while n3 is offline
    val active = Map(n1 -> (r1, s1), n2 -> (r2, s2))
    electLeader(active)
    val _ = pumpCluster(active)

    val (leaderId, (leaderRaw, leaderStore)) =
      active.find(_._2._1.state.isInstanceOf[Leader]).get
    for i <- 1 to 5 do
      val _ = leaderRaw.propose(s"entry-$i".getBytes)
    val _ = pumpCluster(active)

    // Compact leader's storage up to index 5 so follower n3 (at 0) needs Snapshot
    leaderStore.compactTo(5L)
    leaderStore.firstIndex shouldBe 6L

    // Reconnect n3 and trigger heartbeat/append
    val all = Map(n1 -> (r1, s1), n2 -> (r2, s2), n3 -> (r3, s3))
    for _ <- 1 to 5 do
      leaderRaw.tick()
      val _ = pumpCluster(all)

    r3.commitIndex should be >= 5L
    s3.snapshotMetadata.map(_.lastIncludedIndex) shouldBe Some(5L)

    // Propose one more entry and confirm n3 now receives normal log entries!
    val _ = leaderRaw.propose("after-snap".getBytes)
    val _ = pumpCluster(all)
    r3.commitIndex shouldBe 7L
    leaderId should not be n3
  }
