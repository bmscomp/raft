package raft.integration

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Random
import cats.syntax.all.*
import fs2.{Chunk, Stream}

import raft.{RaftNode, UnitRaftNode}
import raft.impl.{
  DefaultTimerService,
  InMemLogStore,
  InMemSnapshotStore,
  InMemStableStore,
  InMemTransport
}
import raft.spi.{StateMachine, TimerService}
import raft.state.*
import raft.state.NodeState.*

/** Shared multi-node cluster test harness for `raft-runtime` integration
  * suites.
  */
object RuntimeClusterHarness:

  /** Deterministic key-value [[StateMachine]] supporting `PUT` (`k=v`), `DEL`
    * (`DEL:k`), and `CAS` (`CAS:k:expected:newVal`) commands, plus ordered
    * applied-entry tracking and snapshot serialization.
    */
  final class TestKvStateMachine private (
      mapRef: Ref[IO, Map[String, String]],
      appliedRef: Ref[IO, Vector[(Long, String)]]
  ) extends StateMachine[IO, Unit]:

    def apply(log: Log): IO[Unit] =
      val raw = new String(log.data, StandardCharsets.UTF_8)
      val updateMap: IO[Unit] =
        if raw.startsWith("DEL:") then
          val key = raw.stripPrefix("DEL:")
          mapRef.update(_ - key)
        else if raw.startsWith("CAS:") then
          raw.stripPrefix("CAS:").split(":", 3) match
            case Array(k, expected, newVal) =>
              mapRef.update { cur =>
                if cur.get(k).contains(expected) then cur.updated(k, newVal)
                else cur
              }
            case _ => IO.unit
        else
          raw.split("=", 2) match
            case Array(k, v) => mapRef.update(_.updated(k, v))
            case _           => IO.unit

      updateMap *> appliedRef.update(_ :+ (log.index -> raw))

    def snapshot: IO[Stream[IO, Byte]] =
      mapRef.get.map { m =>
        val encoded = m.toSeq
          .sortBy(_._1)
          .map { case (k, v) => s"$k=$v" }
          .mkString("\n")
          .getBytes(StandardCharsets.UTF_8)
        Stream.chunk(Chunk.array(encoded)).covary[IO]
      }

    def restore(data: Stream[IO, Byte]): IO[Unit] =
      data.compile.to(Array).flatMap { bytes =>
        val text = new String(bytes, StandardCharsets.UTF_8)
        val parsed =
          if text.isEmpty then Map.empty[String, String]
          else
            text
              .split("\n")
              .toList
              .flatMap { line =>
                line.split("=", 2) match
                  case Array(k, v) => Some(k -> v)
                  case _           => None
              }
              .toMap
        mapRef.set(parsed)
      }

    def get(key: String): IO[Option[String]] =
      mapRef.get.map(_.get(key))

    def currentMap: IO[Map[String, String]] = mapRef.get

    def appliedHistory: IO[Vector[(Long, String)]] = appliedRef.get

  object TestKvStateMachine:
    def create: IO[TestKvStateMachine] =
      for
        mapRef <- Ref.of[IO, Map[String, String]](Map.empty)
        appliedRef <- Ref.of[IO, Vector[(Long, String)]](Vector.empty)
      yield new TestKvStateMachine(mapRef, appliedRef)

  final case class NodeBundle(
      id: NodeId,
      node: UnitRaftNode[IO],
      transport: InMemTransport[IO],
      logStore: InMemLogStore[IO],
      stableStore: InMemStableStore[IO],
      snapshotStore: InMemSnapshotStore[IO],
      stateMachine: TestKvStateMachine
  )

  final case class RunningCluster(
      bundles: Map[NodeId, NodeBundle],
      orderedIds: List[NodeId]
  ):
    def node(id: NodeId): UnitRaftNode[IO] = bundles(id).node
    def stateMachine(id: NodeId): TestKvStateMachine = bundles(id).stateMachine
    def logStore(id: NodeId): InMemLogStore[IO] = bundles(id).logStore
    def snapshotStore(id: NodeId): InMemSnapshotStore[IO] =
      bundles(id).snapshotStore

    def nodesFor(ids: Iterable[NodeId]): List[(NodeId, UnitRaftNode[IO])] =
      ids.toList.map(id => id -> bundles(id).node)

    def allNodes: List[(NodeId, UnitRaftNode[IO])] =
      orderedIds.map(id => id -> bundles(id).node)

    /** Bi-directionally isolate `target` from every other node in the mesh. */
    def isolateNode(target: NodeId): IO[Unit] =
      val others = bundles.keySet - target
      partition(Set(target), others)

    /** Bi-directionally sever communication between `groupA` and `groupB`. */
    def partition(groupA: Set[NodeId], groupB: Set[NodeId]): IO[Unit] =
      for
        _ <- groupA.toList.traverse_ { a =>
          groupB.toList.traverse_(b => bundles(a).transport.unregisterPeer(b))
        }
        _ <- groupB.toList.traverse_ { b =>
          groupA.toList.traverse_(a => bundles(b).transport.unregisterPeer(a))
        }
      yield ()

    /** Restore full connectivity across all nodes in the cluster mesh. */
    def healAll(): IO[Unit] =
      bundles.toList.traverse_ { case (id, bundle) =>
        bundles.toList.traverse_ { case (peerId, peerBundle) =>
          if id != peerId then
            bundle.transport.registerPeer(peerId, peerBundle.transport.inbox)
          else IO.unit
        }
      }

    def awaitCondition(
        cond: IO[Boolean],
        retries: Int = 400,
        interval: FiniteDuration = 20.millis
    ): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if retries <= 0 then
            IO.raiseError(
              new RuntimeException("Cluster condition timed out")
            )
          else IO.sleep(interval) *> awaitCondition(cond, retries - 1, interval)
      }

    def awaitLeader(
        subset: List[NodeId] = orderedIds,
        retries: Int = 400,
        interval: FiniteDuration = 20.millis
    ): IO[(NodeId, UnitRaftNode[IO])] =
      subset
        .traverse(id => bundles(id).node.getState.map(st => (id, st)))
        .flatMap { states =>
          val leaders = states.collect { case (id, l: Leader) => (id, l.term) }
          leaders.sortBy(-_._2).headOption match
            case Some((leaderId, _)) =>
              IO.pure(leaderId -> bundles(leaderId).node)
            case None =>
              if retries <= 0 then
                IO.raiseError(
                  new RuntimeException(s"No leader elected among $subset")
                )
              else
                IO.sleep(interval) *>
                  awaitLeader(subset, retries - 1, interval)
        }

    def awaitLeaderExcluding(
        excluded: NodeId,
        subset: List[NodeId],
        minTerm: Long = 0L,
        retries: Int = 250,
        interval: FiniteDuration = 15.millis
    ): IO[(NodeId, UnitRaftNode[IO])] =
      subset
        .filterNot(_ == excluded)
        .traverse(id => bundles(id).node.getState.map(st => (id, st)))
        .flatMap { states =>
          val candidates = states.collect {
            case (id, l: Leader) if l.term >= minTerm => (id, l.term)
          }
          candidates.sortBy(-_._2).headOption match
            case Some((leaderId, _)) =>
              IO.pure(leaderId -> bundles(leaderId).node)
            case None =>
              if retries <= 0 then
                IO.raiseError(
                  new RuntimeException(
                    s"No new leader elected among ${subset.filterNot(_ == excluded)} (excluding ${excluded.value})"
                  )
                )
              else
                IO.sleep(interval) *>
                  awaitLeaderExcluding(
                    excluded,
                    subset,
                    minTerm,
                    retries - 1,
                    interval
                  )
        }

  def fastConfig(
      id: NodeId,
      compaction: CompactionConfig = CompactionConfig(),
      checkQuorumEnabled: Boolean = true,
      leaseReadEnabled: Boolean = false,
      leaderLeaseDuration: FiniteDuration = 200.millis
  ): RaftConfig =
    RaftConfig(
      localId = id,
      electionTimeoutMin = 250.millis,
      electionTimeoutMax = 500.millis,
      heartbeatInterval = 35.millis,
      leaderLeaseDuration = leaderLeaseDuration,
      compaction = compaction,
      checkQuorumEnabled = checkQuorumEnabled,
      leaseReadEnabled = leaseReadEnabled
    )

  /** Spin up a managed multi-node `RaftNode[IO]` cluster.
    *
    * @param allIds
    *   all physical nodes to create in the transport mesh (may include future
    *   joiners/learners)
    * @param initialClusterFor
    *   per-node initial `ClusterConfig`
    * @param configFor
    *   per-node `RaftConfig`
    * @param autoStartIds
    *   subset of `allIds` whose event loops should be started immediately
    * @param deterministicSeed
    *   optional seed for deterministic `TimerService` jitter (e.g., under
    *   `TestControl`)
    */
  def clusterResource(
      allIds: List[NodeId],
      initialClusterFor: NodeId => ClusterConfig,
      configFor: NodeId => RaftConfig = id => fastConfig(id),
      autoStartIds: Option[Set[NodeId]] = None,
      deterministicSeed: Option[Long] = None
  ): Resource[IO, RunningCluster] =
    val startSet = autoStartIds.getOrElse(allIds.toSet)
    for
      transports <- Resource.eval(InMemTransport.createCluster[IO](allIds))
      bundlesList <- allIds.zipWithIndex.traverse { case (id, idx) =>
        for
          timerSvc <- Resource.eval[IO, TimerService[IO]](
            deterministicSeed match
              case Some(seed) =>
                Random
                  .scalaUtilRandomSeedLong[IO](seed + idx.toLong * 997L)
                  .map(r => new DefaultTimerService[IO](r))
              case None =>
                DefaultTimerService[IO].widen[TimerService[IO]]
          )
          logStore <- Resource.eval(InMemLogStore[IO]())
          stableStore <- Resource.eval(InMemStableStore[IO]())
          snapStore <- Resource.eval(InMemSnapshotStore[IO]())
          sm <- Resource.eval(TestKvStateMachine.create)
          node <- RaftNode[IO](
            config = configFor(id),
            cluster = initialClusterFor(id),
            transport = transports(id),
            logStore = logStore,
            stableStore = stableStore,
            stateMachine = sm,
            timerService = timerSvc,
            snapshotStoreOpt = Some(snapStore)
          )
          _ <- Resource.eval(
            if startSet.contains(id) then node.start else IO.unit
          )
        yield id -> NodeBundle(
          id = id,
          node = node,
          transport = transports(id),
          logStore = logStore,
          stableStore = stableStore,
          snapshotStore = snapStore,
          stateMachine = sm
        )
      }
    yield RunningCluster(bundlesList.toMap, allIds)
