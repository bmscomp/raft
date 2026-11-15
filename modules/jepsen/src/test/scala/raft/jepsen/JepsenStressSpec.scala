package raft.jepsen

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import raft.jepsen.checker.*
import raft.jepsen.nemesis.*
import raft.jepsen.workload.*
import scala.concurrent.duration.*

/** Stress tests for verifying Raft linearizability under extreme conditions.
  *
  * These scenarios push the cluster harder than the standard JepsenSpec tests:
  * higher client concurrency, faster fault injection, larger clusters, and more
  * aggressive nemesis patterns. Each test verifies that the resulting operation
  * history remains linearizable.
  */
class JepsenStressSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers:

  "Stress" should "survive flapping partitions (rapid partition cycling)" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 5,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.FlappingPartitions(800.millis)),
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "survive minority node isolation" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 3,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.MinorityIsolation(2.seconds)),
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "survive network degradation (message drops and latency)" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 3,
      duration = 10.seconds,
      nemesisSchedule =
        Some(NemesisSchedule.NetworkDegradation(3.seconds, 0.15)),
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "survive cascading failures (minority killed simultaneously)" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 3,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.CascadingFailures(3.seconds)),
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "handle thundering herd (high client concurrency)" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 15,
      duration = 10.seconds,
      nemesisSchedule = Some(NemesisSchedule.RandomPartitions(3.seconds)),
      workload = Workload.KVStore(5),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.totalOps should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "handle rapid leader kills (1-second interval)" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 5,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.LeaderHunter(1.second)),
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "handle high key contention under partitions" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 10,
      duration = 10.seconds,
      nemesisSchedule = Some(NemesisSchedule.RandomPartitions(2.seconds)),
      workload = Workload.Register(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.totalOps should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "handle wide key space under combined faults" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 5,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.Combined(2.seconds)),
      workload = Workload.KVStore(20),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "handle leader hunting with high concurrency and multi-key" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 10,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.LeaderHunter(2.seconds)),
      workload = Workload.KVStore(10),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "survive split-brain with high client contention" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 10,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.SplitBrain(2.seconds)),
      workload = Workload.Register(3),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "maintain linearizability with CAS workload (baseline)" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 3,
      clients = 3,
      duration = 10.seconds,
      nemesisSchedule = None,
      workload = Workload.CASRegister(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.totalOps should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "maintain linearizability with CAS under partitions" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 5,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.RandomPartitions(3.seconds)),
      workload = Workload.CASRegister(3),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "maintain linearizability with CAS under leader hunting" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 5,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.LeaderHunter(2.seconds)),
      workload = Workload.CASRegister(1),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }

  it should "maintain linearizability with CAS under combined faults" taggedAs JepsenTag in {
    JepsenTest(
      clusterSize = 5,
      clients = 8,
      duration = 15.seconds,
      nemesisSchedule = Some(NemesisSchedule.Combined(2.seconds)),
      workload = Workload.CASRegister(5),
      model = RegisterModel
    ).run.asserting { result =>
      result.stats.nemesisEvents should be > 0
      result.stats.successfulOps should be > 0
      result.linearizable shouldBe true
    }
  }
