package raft.multigroup

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import raft.state.{GroupId, NodeId}
import raft.message.RaftMessage
import raft.message.RaftMessage.*

class MultiGroupTransportSpec extends AnyFunSuite with Matchers:

  test("per-group transport should wrap messages in GroupEnvelope"):
    val localId = NodeId("node1")
    var capturedEnvelopes = List.empty[GroupEnvelope]

    val transport = MultiGroupTransport[IO](
      localId,
      env => IO { capturedEnvelopes = capturedEnvelopes :+ env }
    ).unsafeRunSync()

    val groupA = GroupId("shard-a")
    val groupB = GroupId("shard-b")

    val tpA = transport.transportForGroup(groupA)
    val tpB = transport.transportForGroup(groupB)

    val msg = HeartbeatTimeout

    tpA.send(NodeId("node2"), msg).unsafeRunSync()
    tpB.send(NodeId("node3"), msg).unsafeRunSync()

    capturedEnvelopes should have size 2
    capturedEnvelopes(0).groupId shouldBe groupA
    capturedEnvelopes(0).from shouldBe localId
    capturedEnvelopes(1).groupId shouldBe groupB

  test("per-group transport receive should filter by group"):
    val localId = NodeId("node1")
    val groupA = GroupId("shard-a")
    val groupB = GroupId("shard-b")

    val transport = MultiGroupTransport[IO](
      localId,
      _ => IO.unit
    ).unsafeRunSync()

    val tpA = transport.transportForGroup(groupA)

    // Dispatch messages for both groups
    val envA = GroupEnvelope(groupA, NodeId("node2"), ElectionTimeout)
    val envB = GroupEnvelope(groupB, NodeId("node3"), HeartbeatTimeout)

    // Start collecting from group A, then dispatch
    val result = (
      tpA.receive.take(1).compile.toList,
      IO.sleep(scala.concurrent.duration.FiniteDuration(50, "ms")) *>
        transport.dispatch(envB) *>
        transport.dispatch(envA)
    ).parTupled.unsafeRunSync()

    val received = result._1
    received should have size 1
    received.head._1 shouldBe NodeId("node2")
    received.head._2 shouldBe ElectionTimeout

  test("sendBatch should wrap each message individually"):
    val localId = NodeId("node1")
    var capturedEnvelopes = List.empty[GroupEnvelope]

    val transport = MultiGroupTransport[IO](
      localId,
      env => IO { capturedEnvelopes = capturedEnvelopes :+ env }
    ).unsafeRunSync()

    val group = GroupId("shard-0")
    val tp = transport.transportForGroup(group)

    tp.sendBatch(
      NodeId("node2"),
      Seq(ElectionTimeout, HeartbeatTimeout)
    ).unsafeRunSync()

    capturedEnvelopes should have size 2
    capturedEnvelopes.forall(_.groupId == group) shouldBe true
