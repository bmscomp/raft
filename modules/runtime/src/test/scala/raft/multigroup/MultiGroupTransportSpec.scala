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
    capturedEnvelopes.forall(_.to.contains(NodeId("node2"))) shouldBe true

  test(
    "unicast send should populate target NodeId while broadcast sets to=None"
  ):
    val localId = NodeId("node1")
    var captured = List.empty[GroupEnvelope]

    val transport = MultiGroupTransport[IO](
      localId,
      env => IO { captured = captured :+ env }
    ).unsafeRunSync()

    val tp = transport.transportForGroup(GroupId("shard-x"))
    tp.send(NodeId("node2"), ElectionTimeout).unsafeRunSync()
    tp.broadcast(HeartbeatTimeout).unsafeRunSync()

    captured should have size 2
    captured(0).to shouldBe Some(NodeId("node2"))
    captured(1).to shouldBe None

  test(
    "dispatch before receive subscription should buffer messages without loss"
  ):
    val localId = NodeId("node1")
    val groupA = GroupId("shard-eager")
    val transport =
      MultiGroupTransport[IO](localId, _ => IO.unit).unsafeRunSync()

    // Dispatch BEFORE transportForGroup / receive fiber is even started!
    transport
      .dispatch(GroupEnvelope(groupA, NodeId("node2"), ElectionTimeout))
      .unsafeRunSync()
    transport
      .dispatch(GroupEnvelope(groupA, NodeId("node3"), HeartbeatTimeout))
      .unsafeRunSync()

    val tpA = transport.transportForGroup(groupA)
    val received = tpA.receive.take(2).compile.toList.unsafeRunSync()
    received shouldBe List(
      (NodeId("node2"), ElectionTimeout),
      (NodeId("node3"), HeartbeatTimeout)
    )

  test("unregisterGroup should drop buffered mailbox and ignore late packets"):
    val localId = NodeId("node1")
    val groupGone = GroupId("shard-removed")
    val transport =
      MultiGroupTransport[IO](localId, _ => IO.unit).unsafeRunSync()

    transport.registerGroup(groupGone).unsafeRunSync()
    transport
      .dispatch(GroupEnvelope(groupGone, NodeId("node2"), ElectionTimeout))
      .unsafeRunSync()
    transport.unregisterGroup(groupGone).unsafeRunSync()
    transport
      .dispatch(GroupEnvelope(groupGone, NodeId("node3"), HeartbeatTimeout))
      .unsafeRunSync()

    // Re-registering starts from a clean empty queue
    val tpFresh = transport.registerGroup(groupGone).unsafeRunSync()
    transport
      .dispatch(GroupEnvelope(groupGone, NodeId("node4"), CheckQuorumTimeout))
      .unsafeRunSync()
    val received = tpFresh.receive.take(1).compile.toList.unsafeRunSync()
    received shouldBe List((NodeId("node4"), CheckQuorumTimeout))
