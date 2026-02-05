package raft.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SnapshotSpec extends AnyFlatSpec with Matchers:
  
  "Snapshot" should "store metadata and data" in {
    val data = "state machine data".getBytes
    val snapshot = Snapshot(
      lastIncludedIndex = 100,
      lastIncludedTerm = 5,
      data = data
    )
    
    snapshot.lastIncludedIndex shouldBe 100
    snapshot.lastIncludedTerm shouldBe 5
    new String(snapshot.data) shouldBe "state machine data"
  }
  
  it should "include cluster config" in {
    val config = ClusterConfig(Set(NodeId("a"), NodeId("b")))
    val snapshot = Snapshot(
      lastIncludedIndex = 50,
      lastIncludedTerm = 3,
      data = Array.empty,
      config = Some(config)
    )
    
    snapshot.config shouldBe defined
    snapshot.config.get.voters shouldBe Set(NodeId("a"), NodeId("b"))
  }
  
  "PendingSnapshot" should "accumulate chunks" in {
    val pending = PendingSnapshot(
      lastIncludedIndex = 100,
      lastIncludedTerm = 5,
      offset = 0,
      chunks = Vector.empty
    )
    
    val chunk1 = "chunk1".getBytes
    val chunk2 = "chunk2".getBytes
    
    val withChunk1 = pending.appendChunk(chunk1)
    withChunk1.offset shouldBe chunk1.length
    withChunk1.chunks.size shouldBe 1
    
    val withChunk2 = withChunk1.appendChunk(chunk2)
    withChunk2.offset shouldBe (chunk1.length + chunk2.length)
    withChunk2.chunks.size shouldBe 2
  }
  
  it should "convert to final Snapshot" in {
    val pending = PendingSnapshot(
      lastIncludedIndex = 100,
      lastIncludedTerm = 5,
      offset = 0,
      chunks = Vector.empty
    )
    .appendChunk("hello".getBytes)
    .appendChunk(" world".getBytes)
    
    val snapshot = pending.toSnapshot
    snapshot.lastIncludedIndex shouldBe 100
    snapshot.lastIncludedTerm shouldBe 5
    new String(snapshot.data) shouldBe "hello world"
  }
