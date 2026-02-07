package raft.jepsen

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.jepsen.checker.*
import raft.jepsen.nemesis.*
import raft.jepsen.workload.*
import scala.concurrent.duration.*

/** Jepsen-style verification test suite for the Raft library.
  *
  * Each test creates a multi-node Raft cluster, runs concurrent client
  * workloads with fault injection, and verifies that the resulting operation
  * history is linearizable.
  */
class JepsenSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers:

  "Raft cluster" should "maintain linearizability with no faults (baseline)" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 3,
      clients = 3,
      duration = 5.seconds,
      nemesisSchedule = None,
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.totalOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "maintain linearizability under random partitions" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 3,
      duration = 10.seconds,
      nemesisSchedule = Some(NemesisSchedule.RandomPartitions(3.seconds)),
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "maintain linearizability under rolling restarts" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 3,
      duration = 10.seconds,
      nemesisSchedule = Some(NemesisSchedule.RollingRestart(2.seconds)),
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "maintain linearizability under split-brain scenarios" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 3,
      duration = 10.seconds,
      nemesisSchedule = Some(NemesisSchedule.SplitBrain(3.seconds)),
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "maintain linearizability under combined faults" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 5,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.Combined(3.seconds)),
      workload = Workload.KVStore(3),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "handle multi-key workloads with partitions" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 5,
      duration = 10.seconds,
      nemesisSchedule = Some(NemesisSchedule.RandomPartitions(3.seconds)),
      workload = Workload.KVStore(5),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.totalOps should be > 0
      result.linearizable shouldBe true
    }
  }
