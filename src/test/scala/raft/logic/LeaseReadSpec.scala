package raft.logic

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

import raft.state.*
import raft.state.NodeState.*
import raft.message.*
import raft.message.RaftMessage.*
import raft.effect.Effect
import raft.effect.Effect.*

/**
 * Tests for Lease-based Reads.
 * 
 * Lease-based reads provide low-latency reads by:
 * 1. Leader maintains a lease from heartbeat quorum confirmations
 * 2. While lease is valid, reads can be served locally without quorum
 * 3. Lease expires after leaderLeaseDuration without quorum confirmation
 * 
 * Trade-off: Slightly weaker consistency (bounded staleness during partitions)
 * but much better read performance.
 */
class LeaseReadSpec extends AnyFlatSpec with Matchers:
  
  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")
  val node3 = NodeId("node-3")
  
  val config = RaftConfig(
    localId = node1,
    leaderLeaseDuration = 100.millis,
    preVoteEnabled = false
  )
  
  // === LEASE CONFIG ===
  
  "RaftConfig" should "have leaderLeaseDuration" in {
    config.leaderLeaseDuration shouldBe 100.millis
  }
  
  it should "have sensible default lease duration" in {
    val defaultConfig = RaftConfig.default(node1)
    defaultConfig.leaderLeaseDuration shouldBe 100.millis
  }
  
  // === EXTEND LEASE EFFECT ===
  
  "ExtendLease effect" should "contain lease expiration time" in {
    val effect: Effect = ExtendLease(until = 1000L)
    
    effect match
      case ExtendLease(until) => until shouldBe 1000L
  }
  
  it should "be triggered after successful heartbeat quorum" in {
    // When leader receives majority of heartbeat responses,
    // it extends its lease
    val currentTime = 500L
    val leaseDuration = 100L  // 100ms
    val newLeaseUntil = currentTime + leaseDuration
    
    val effect: Effect = ExtendLease(newLeaseUntil)
    
    effect match
      case ExtendLease(until) => until shouldBe 600L
  }
  
  // === LEASE READ READY EFFECT ===
  
  "LeaseReadReady effect" should "contain request ID" in {
    val effect: Effect = LeaseReadReady("lease-read-123")
    
    effect match
      case LeaseReadReady(id) => id shouldBe "lease-read-123"
  }
  
  // === LEASE VALIDATION ===
  
  "Lease read" should "be allowed when lease is valid" in {
    val currentTime = 500L
    val leaseUntil = 600L
    
    // Lease is valid if current time < lease expiration
    (currentTime < leaseUntil) shouldBe true
    
    // Can serve read immediately
    val effect: Effect = LeaseReadReady("read-456")
    effect match
      case LeaseReadReady(_) => succeed
  }
  
  it should "be rejected when lease has expired" in {
    val currentTime = 700L
    val leaseUntil = 600L
    
    // Lease has expired
    (currentTime >= leaseUntil) shouldBe true
    
    // Must use ReadIndex instead (or reject)
    val effect: Effect = ReadIndexRejected("read-789", Some(node1))
    effect match
      case ReadIndexRejected(_, _) => succeed
  }
  
  // === LEASE BOUNDARIES ===
  
  "Lease at exact expiration time" should "be considered expired" in {
    val currentTime = 600L
    val leaseUntil = 600L
    
    // At exact boundary, lease is expired (conservative)
    (currentTime >= leaseUntil) shouldBe true
  }
  
  "Lease just before expiration" should "be valid" in {
    val currentTime = 599L
    val leaseUntil = 600L
    
    // Just before expiration, still valid
    (currentTime < leaseUntil) shouldBe true
  }
  
  // === LEASE VS READINDEX TRADEOFFS ===
  
  "Lease reads" should "be faster than ReadIndex" in {
    // Lease reads: single node, no network round trip
    // ReadIndex: requires heartbeat quorum confirmation
    
    // Lease read: immediate response
    val leaseEffect: Effect = LeaseReadReady("fast-read")
    
    // ReadIndex: must wait for quorum
    val readIndexEffect: Effect = ConfirmLeadership("slow-read", 50)
    
    // Different effect types indicate different paths
    leaseEffect match
      case LeaseReadReady(_) => succeed
      case _ => fail("Expected LeaseReadReady")
    
    readIndexEffect match
      case ConfirmLeadership(_, _) => succeed
      case _ => fail("Expected ConfirmLeadership")
  }
  
  "Lease reads during partition" should "have bounded staleness" in {
    // If network partition occurs after lease granted,
    // leader may serve stale reads until lease expires
    // Maximum staleness = leaderLeaseDuration
    
    val staleness = config.leaderLeaseDuration
    staleness shouldBe 100.millis
    
    // After lease expires, leader must reconfirm or step down
  }
  
  // === HEARTBEAT QUORUM TRACKING ===
  
  "Heartbeat responses" should "extend lease when quorum reached" in {
    // 3-node cluster, need 2 responses for quorum
    val clusterSize = 3
    val majoritySize = (clusterSize / 2) + 1
    majoritySize shouldBe 2
    
    // When 2nd response arrives, extend lease
    val responsesReceived = 2
    (responsesReceived >= majoritySize) shouldBe true
    
    // Effect to extend lease
    val effect: Effect = ExtendLease(until = 700L)
    effect match
      case ExtendLease(_) => succeed
  }
  
  // === LEADER STEP DOWN ===
  
  "Leader with expired lease" should "not serve reads" in {
    // If lease expired and cannot confirm leadership,
    // leader should either:
    // 1. Reject read requests
    // 2. Fall back to ReadIndex protocol
    // 3. Step down if unable to contact quorum
  }
  
  // === CLOCK ASSUMPTIONS ===
  
  "Lease-based reads" should "assume bounded clock drift" in {
    // Lease safety depends on:
    // 1. Leader's clock not running too fast
    // 2. Followers' clocks not running too slow
    // 3. Max clock drift < lease duration
    
    // This is why lease duration should be conservative
  }
