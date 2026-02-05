package raft.integration

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.impl.*
import raft.state.*
import raft.message.RaftMessage

/**
 * Integration tests for in-memory implementations.
 * 
 * These tests verify the SPI implementations work correctly:
 * - InMemLogStore: Log persistence
 * - InMemStableStore: Term and vote persistence
 * - InMemTransport: Message passing
 */
class InMemoryStoreSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers:
  
  // IN-MEMORY LOG STORE TESTS
  
  "InMemLogStore" should "start with empty log" in {
    for
      store <- InMemLogStore[IO]()
      first <- store.firstIndex
      last  <- store.lastIndex
    yield
      first shouldBe 0L
      last.value shouldBe 0L
  }
  
  it should "append and retrieve single log entry" in {
    for
      store <- InMemLogStore[IO]()
      
      // Append one entry
      entry = Log.command(1, 1, "cmd1".getBytes)
      _ <- store.appendLogs(Seq(entry))
      
      // Retrieve it
      retrieved <- store.getLog(1)
    yield
      retrieved shouldBe defined
      retrieved.get.index shouldBe 1
      retrieved.get.term shouldBe 1
      new String(retrieved.get.data) shouldBe "cmd1"
  }
  
  it should "append and retrieve multiple log entries" in {
    for
      store <- InMemLogStore[IO]()
      
      // Append multiple entries
      entries = Seq(
        Log.command(1, 1, "cmd1".getBytes),
        Log.command(2, 1, "cmd2".getBytes),
        Log.command(3, 2, "cmd3".getBytes)
      )
      _ <- store.appendLogs(entries)
      
      // Check indices
      first <- store.firstIndex
      last  <- store.lastIndex
      
      // Retrieve range
      range <- store.getLogs(1, 3)
    yield
      first shouldBe 1L
      last.value shouldBe 3L
      range.size shouldBe 3
  }
  
  it should "truncate logs from given index" in {
    for
      store <- InMemLogStore[IO]()
      
      // Append entries
      entries = Seq(
        Log.command(1, 1, "cmd1".getBytes),
        Log.command(2, 1, "cmd2".getBytes),
        Log.command(3, 2, "cmd3".getBytes)
      )
      _ <- store.appendLogs(entries)
      
      // Truncate from index 2 (remove 2 and 3)
      _ <- store.truncateFrom(LogIndex.unsafeFrom(2))
      
      // Check what remains
      last  <- store.lastIndex
      entry <- store.getLog(2)
    yield
      last.value shouldBe 1L
      entry shouldBe None
  }
  
  it should "return term at specific index" in {
    for
      store <- InMemLogStore[IO]()
      
      _ <- store.appendLogs(Seq(
        Log.command(1, 5, "cmd1".getBytes),
        Log.command(2, 5, "cmd2".getBytes),
        Log.command(3, 7, "cmd3".getBytes)
      ))
      
      term1 <- store.termAt(LogIndex.unsafeFrom(1))
      term3 <- store.termAt(LogIndex.unsafeFrom(3))
      termMissing <- store.termAt(LogIndex.unsafeFrom(100))
    yield
      term1.map(_.value) shouldBe Some(5L)
      term3.map(_.value) shouldBe Some(7L)
      termMissing shouldBe None
  }
  
  // IN-MEMORY STABLE STORE TESTS
  
  "InMemStableStore" should "start with term 0 and no vote" in {
    for
      store <- InMemStableStore[IO]()
      term  <- store.currentTerm
      vote  <- store.votedFor
    yield
      term.value shouldBe 0L
      vote shouldBe None
  }
  
  it should "persist current term" in {
    for
      store <- InMemStableStore[IO]()
      
      _ <- store.setCurrentTerm(Term.unsafeFrom(42))
      term <- store.currentTerm
    yield
      term.value shouldBe 42L
  }
  
  it should "persist voted-for" in {
    for
      store <- InMemStableStore[IO]()
      
      _ <- store.setVotedFor(Some(NodeId("candidate-1")))
      vote <- store.getVotedFor
    yield
      vote shouldBe Some(NodeId("candidate-1"))
  }
  
  it should "clear voted-for" in {
    for
      store <- InMemStableStore[IO]()
      
      _ <- store.setVotedFor(Some(NodeId("candidate-1")))
      _ <- store.setVotedFor(None)
      vote <- store.getVotedFor
    yield
      vote shouldBe None
  }
  
  // IN-MEMORY TRANSPORT TESTS
  
  "InMemTransport" should "send message to peer" in {
    for
      node1Transport <- InMemTransport[IO](NodeId("node-1"))
      node2Transport <- InMemTransport[IO](NodeId("node-2"))
      
      // Wire up peers
      _ <- node1Transport.registerPeer(NodeId("node-2"), node2Transport.inbox)
      
      // Send message
      msg = RaftMessage.ElectionTimeout
      _ <- node1Transport.send(NodeId("node-2"), msg)
      
      // Receive message
      received <- node2Transport.inbox.take
    yield
      received._1 shouldBe NodeId("node-1")
      received._2 shouldBe msg
  }
  
  it should "broadcast to all peers" in {
    for
      t1 <- InMemTransport[IO](NodeId("node-1"))
      t2 <- InMemTransport[IO](NodeId("node-2"))
      t3 <- InMemTransport[IO](NodeId("node-3"))
      
      // Wire up node-1 to broadcast to node-2 and node-3
      _ <- t1.registerPeer(NodeId("node-2"), t2.inbox)
      _ <- t1.registerPeer(NodeId("node-3"), t3.inbox)
      
      // Broadcast
      msg = RaftMessage.HeartbeatTimeout
      _ <- t1.broadcast(msg)
      
      // Both should receive
      r2 <- t2.inbox.take
      r3 <- t3.inbox.take
    yield
      r2._2 shouldBe msg
      r3._2 shouldBe msg
  }
  
  "InMemTransport.createCluster" should "wire up all peers automatically" in {
    for
      transports <- InMemTransport.createCluster[IO](List(
        NodeId("n1"),
        NodeId("n2"),
        NodeId("n3")
      ))
      
      t1 = transports(NodeId("n1"))
      t2 = transports(NodeId("n2"))
      t3 = transports(NodeId("n3"))
      
      // n1 broadcasts
      _ <- t1.broadcast(RaftMessage.ElectionTimeout)
      
      // n2 and n3 should receive
      r2 <- t2.inbox.take
      r3 <- t3.inbox.take
    yield
      r2._1 shouldBe NodeId("n1")
      r3._1 shouldBe NodeId("n1")
      transports.size shouldBe 3
  }
