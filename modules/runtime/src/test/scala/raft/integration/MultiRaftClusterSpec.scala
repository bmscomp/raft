package raft.integration

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.RaftNode
import raft.codec.JsonCodec
import raft.impl.{
  DefaultTimerService,
  InMemLogStore,
  InMemSnapshotStore,
  InMemStableStore
}
import raft.integration.RuntimeClusterHarness.TestKvStateMachine
import raft.multigroup.{
  GroupAwareCodec,
  GroupConfig,
  GroupEnvelope,
  GroupNotFoundException,
  MultiGroupTransport,
  MultiRaftNode
}
import raft.state.*
import raft.state.NodeState.*

/** Multi-node integration suite verifying `MultiRaftNode` +
  * `MultiGroupTransport` (`GroupAwareCodec`) hosting 4 concurrent Raft shards
  * across 3 physical nodes over shared wire connections.
  */
class MultiRaftClusterSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val nodeIds = List(n1, n2, n3)

  private val shardIds = (0 until 4).map(i => GroupId(s"shard-$i")).toList

  private def utf8(s: String): Array[Byte] =
    s.getBytes(StandardCharsets.UTF_8)

  private def awaitCondition(
      cond: IO[Boolean],
      retries: Int = 400,
      interval: FiniteDuration = 20.millis
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(new RuntimeException("Multi-Raft condition timed out"))
        else IO.sleep(interval) *> awaitCondition(cond, retries - 1, interval)
    }

  private def awaitShardLeader(
      coordinators: Map[NodeId, MultiRaftNode[IO]],
      groupId: GroupId,
      retries: Int = 400
  ): IO[(NodeId, RaftNode[IO])] =
    nodeIds
      .traverse { id =>
        coordinators(id).getGroup(groupId).flatMap {
          case Some(rn) => rn.getState.map(st => Some((id, rn, st)))
          case None     => IO.pure(None)
        }
      }
      .flatMap { opts =>
        val leaders = opts.flatten.collect { case (id, rn, l: Leader) =>
          (id, rn, l.term)
        }
        leaders.sortBy(-_._3).headOption.map(t => (t._1, t._2)) match
          case Some(found) => IO.pure(found)
          case None        =>
            if retries <= 0 then
              IO.raiseError(
                new RuntimeException(
                  s"No leader elected for shard ${groupId.value}"
                )
              )
            else
              IO.sleep(20.millis) *>
                awaitShardLeader(coordinators, groupId, retries - 1)
      }

  private def proposeShardWithRetry(
      coordinators: Map[NodeId, MultiRaftNode[IO]],
      groupId: GroupId,
      data: Array[Byte],
      attempts: Int = 15
  ): IO[LogId] =
    awaitShardLeader(coordinators, groupId).flatMap { case (_, leaderNode) =>
      leaderNode.proposeAndCommit(data).handleErrorWith {
        case _: RaftError if attempts > 1 =>
          IO.sleep(30.millis) *>
            proposeShardWithRetry(coordinators, groupId, data, attempts - 1)
        case err => IO.raiseError(err)
      }
    }

  "MultiRaftNode (3 physical nodes x 4 shards)" should "demultiplex shared wire traffic, isolate shard state, and cleanly remove groups" in {
    val groupCodec = new GroupAwareCodec(JsonCodec)
    val clusterCfg = ClusterConfig(nodeIds.toSet)

    val prog = for
      // Shared wire queue per physical node (simulating 1 shared TCP/gRPC stream per host)
      wireQueues <- nodeIds
        .traverse(id => Queue.unbounded[IO, String].map(id -> _))
        .map(_.toMap)

      mgTransports <- nodeIds
        .traverse { localId =>
          val sendEnv: GroupEnvelope => IO[Unit] = env => {
            val encoded = groupCodec.encode(env)
            env.to match
              case Some(dest) =>
                wireQueues.get(dest).traverse_(_.offer(encoded))
              case None =>
                wireQueues
                  .removed(localId)
                  .values
                  .toList
                  .traverse_(_.offer(encoded))
          }
          MultiGroupTransport[IO](localId, sendEnv, maxQueueSize = 1024)
            .map(localId -> _)
        }
        .map(_.toMap)

      // Background shared wire reader fiber per physical node dispatching into MultiGroupTransport
      wireFibers <- nodeIds.traverse { id =>
        Stream
          .fromQueueUnterminated(wireQueues(id))
          .evalMap { raw =>
            groupCodec.decode(raw) match
              case Right(env) => mgTransports(id).dispatch(env)
              case Left(_)    => IO.unit
          }
          .compile
          .drain
          .start
      }

      coordinators <- nodeIds
        .traverse(id => MultiRaftNode[IO](id, mgTransports(id)).map(id -> _))
        .map(_.toMap)

      stateMachinesRef <- Ref
        .of[IO, Map[(GroupId, NodeId), TestKvStateMachine]](
          Map.empty
        )

      _ <- (for
        _ <- coordinators.values.toList.traverse_(_.start)

        // Provision 4 independent Raft consensus groups across all 3 physical nodes
        _ <- shardIds.traverse_ { gid =>
          nodeIds.traverse_ { nid =>
            for
              timerSvc <- DefaultTimerService[IO]
              logStore <- InMemLogStore[IO]()
              stableStore <- InMemStableStore[IO]()
              snapStore <- InMemSnapshotStore[IO]()
              sm <- TestKvStateMachine.create
              _ <- stateMachinesRef.update(_.updated((gid, nid), sm))
              rc = RaftConfig(
                localId = nid,
                electionTimeoutMin = 300.millis,
                electionTimeoutMax = 600.millis,
                heartbeatInterval = 40.millis
              )
              gCfg = GroupConfig[IO](
                raftConfig = rc,
                cluster = clusterCfg,
                logStore = logStore,
                stableStore = stableStore,
                stateMachine = sm,
                timerService = timerSvc,
                snapshotStore = Some(snapStore)
              )
              _ <- coordinators(nid).createGroup(gid, gCfg)
            yield ()
          }
        }

        // Verify all 3 physical nodes list all 4 active groups
        _ <- nodeIds.traverse_ { nid =>
          coordinators(nid).listGroups.map(_ shouldBe shardIds.toSet)
        }

        // Elect leaders independently across all 4 shards and commit concurrent shard-isolated writes
        _ <- shardIds.parTraverse_ { gid =>
          (1 to 12).toList.traverse_ { i =>
            proposeShardWithRetry(
              coordinators,
              gid,
              utf8(s"${gid.value}-k$i=${gid.value}-v$i")
            )
          } *>
            awaitShardLeader(coordinators, gid).flatMap { case (leaderId, _) =>
              coordinators(leaderId).submitToGroup(
                gid,
                utf8(s"${gid.value}-fire=ok")
              )
            }
        }

        smLookup <- stateMachinesRef.get
        _ <- shardIds.traverse_ { gid =>
          val expectedCore =
            (1 to 12)
              .map(i => s"${gid.value}-k$i" -> s"${gid.value}-v$i")
              .toMap +
              (s"${gid.value}-fire" -> "ok")
          awaitCondition(
            nodeIds.forallM { nid =>
              smLookup((gid, nid)).currentMap.map { m =>
                // Shard state must contain its own 13 keys and ZERO keys from other shards!
                m == expectedCore && m.keys.forall(_.startsWith(gid.value))
              }
            }
          )
        }

        // Remove shard-3 dynamically across all 3 physical nodes while shard-0..2 keep serving writes
        removedShard = GroupId("shard-3")
        _ <- nodeIds.traverse_(nid =>
          coordinators(nid).removeGroup(removedShard)
        )
        _ <- nodeIds.traverse_ { nid =>
          for
            groups <- coordinators(nid).listGroups
            _ = groups should not contain removedShard
            stOpt <- coordinators(nid).getGroupState(removedShard)
            _ = stOpt shouldBe None
            submitErr <- coordinators(nid)
              .submitToGroup(removedShard, utf8("x=1"))
              .attempt
            _ = submitErr shouldBe Left(GroupNotFoundException(removedShard))
          yield ()
        }

        // Remaining shards (shard-0..2) remain healthy and commit new writes unaffected
        survivingShards = shardIds.filterNot(_ == removedShard)
        _ <- survivingShards.parTraverse_ { gid =>
          proposeShardWithRetry(
            coordinators,
            gid,
            utf8(s"${gid.value}-after-rm=alive")
          )
        }
        _ <- survivingShards.traverse_ { gid =>
          awaitCondition(
            nodeIds.forallM { nid =>
              smLookup((gid, nid))
                .get(s"${gid.value}-after-rm")
                .map(_.contains("alive"))
            }
          )
        }
      yield ()).guarantee(
        coordinators.values.toList.traverse_(_.shutdown) *>
          wireFibers.traverse_(_.cancel)
      )
    yield ()

    prog.timeout(45.seconds).unsafeRunSync()
  }
