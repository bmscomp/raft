package raft.impl

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.state.NodeId
import raft.message.RaftMessage.*

class InMemTransportSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers:

  val node1 = NodeId("node-1")
  val node2 = NodeId("node-2")

  "InMemTransport" should "send batches of messages" in {
    for
      // Setup cluster
      cluster <- InMemTransport.createCluster[IO](List(node1, node2))
      t1 = cluster(node1)
      t2 = cluster(node2)

      // Send batch
      msgs = List(
        RequestVoteRequest(1, node1, 0, 0),
        AppendEntriesRequest(1, node1, 0, 0, Nil, 0)
      )
      _ <- t1.sendBatch(node2, msgs)

      // Receive
      received <- t2.receive.take(2).compile.toList
    yield
      received should have size 2
      received.map(_._2) shouldBe msgs
  }
