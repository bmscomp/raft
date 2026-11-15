package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import raft.state.*
import raft.state.NodeState.*
import raft.message.RaftMessage.*
import raft.effect.Effect
import raft.effect.Effect.*
import raft.raw.{MemStorage, RawNode}

/** Tests for Lease-based Reads and `CheckQuorum` safety (§6.2, §6.4). */
class LeaseReadSpec extends AnyFlatSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")

  val config = RaftConfig(
    localId = node1,
    leaderLeaseDuration = 100.millis,
    preVoteEnabled = false,
    checkQuorumEnabled = true,
    leaseReadEnabled = true,
    electionTimeoutTicks = 10,
    heartbeatIntervalTicks = 2
  )

  "RaftConfig" should "configure leaderLeaseDuration and leaseReadEnabled" in {
    config.leaderLeaseDuration shouldBe 100.millis
    config.leaseReadEnabled shouldBe true
    config.checkQuorumEnabled shouldBe true
  }

  "ExtendLease and LeaseReadReady effects" should "preserve values cleanly" in {
    val extend: Effect = ExtendLease(untilTick = 1000L)
    extend shouldBe ExtendLease(1000L)

    val ready: Effect = LeaseReadReady("lease-read-123")
    ready shouldBe LeaseReadReady("lease-read-123")
  }

  "CheckQuorumTimeout on partitioned Leader" should "force step-down to Follower and reject pending reads" in {
    val cluster = ClusterConfig(Set(node1, node2, node3))
    val partitionedLeader = Leader(
      term = 5,
      commitIndex = 20L,
      recentlyActive = Set(node1), // No follower responses in election window!
      pendingReads = Map("r1" -> PendingRead("r1", 20L, Set(node1)))
    )

    val trans = RaftLogic.onMessage(
      partitionedLeader,
      CheckQuorumTimeout,
      config,
      lastLogIndex = 20L,
      lastLogTerm = 5L,
      clusterSize = 3,
      clusterOpt = Some(cluster)
    )

    trans.state shouldBe a[Follower]
    trans.effects should contain(StopLeader)
    trans.effects should contain(ResetElectionTimer)
    trans.effects.collectFirst { case r: ReadIndexRejected =>
      r.id
    } shouldBe Some("r1")
  }

  "CheckQuorumTimeout on healthy Leader" should "keep Leader active and reset recentlyActive window" in {
    val cluster = ClusterConfig(Set(node1, node2, node3))
    val healthyLeader = Leader(
      term = 5,
      commitIndex = 20L,
      recentlyActive = Set(node1, node2) // Quorum active!
    )

    val trans = RaftLogic.onMessage(
      healthyLeader,
      CheckQuorumTimeout,
      config,
      lastLogIndex = 20L,
      lastLogTerm = 5L,
      clusterSize = 3,
      clusterOpt = Some(cluster)
    )

    trans.state shouldBe a[Leader]
    trans.state.asInstanceOf[Leader].recentlyActive shouldBe Set(node1)
  }

  "RawNode with leaseReadEnabled" should "serve reads locally while tick lease is valid" in {
    val storage = MemStorage.create(ClusterConfig(Set(node1, node2, node3)))
    val raw = RawNode(config, storage)

    // Campaign and win election in term 1
    for _ <- 1 to 20 do raw.tick()
    val r1 = raw.ready()
    storage.persistReady(r1)
    raw.advance(r1)

    raw.step(node2, RequestVoteResponse(1L, voteGranted = true))
    val r2 = raw.ready()
    storage.persistReady(r2)
    raw.advance(r2)

    // Follower node-2 acks the leader's term-1 no-op entry -> extends lease AND commits term-1 entry!
    raw.step(node2, AppendEntriesResponse(1L, success = true, matchIndex = 1L))
    val r3 = raw.ready()
    storage.persistReady(r3)
    raw.advance(r3)

    // Read while lease is valid -> immediately emitted in ready().readStates without network traffic!
    raw.readIndex("fast-lease-read") shouldBe Right(())
    val r4 = raw.ready()
    r4.readStates should contain(
      ReadState(index = 1L, requestCtx = "fast-lease-read")
    )
    r4.messages shouldBe empty
    val _ = raw.advance(r4)
  }
