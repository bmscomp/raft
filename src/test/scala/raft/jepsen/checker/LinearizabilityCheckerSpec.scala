package raft.jepsen.checker

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit tests for the linearizability checker.
  *
  * Tests both known-linearizable and known-non-linearizable histories to verify
  * the checker's correctness in both directions.
  */
class LinearizabilityCheckerSpec extends AnyFlatSpec with Matchers:

  // Helper to build histories concisely
  private var nextTs = 0L
  private def ts(): Long = { nextTs += 1; nextTs }

  private def invoke(
      idx: Long,
      process: Int,
      fn: OpFunction,
      key: String,
      value: Option[Any] = None
  ): Operation =
    Operation(idx, process, OpType.Invoke, fn, key, value, None, ts())

  private def ok(
      idx: Long,
      process: Int,
      fn: OpFunction,
      key: String,
      value: Option[Any] = None
  ): Operation =
    Operation(idx, process, OpType.Ok, fn, key, None, value, ts())

  private def fail(
      idx: Long,
      process: Int,
      fn: OpFunction,
      key: String
  ): Operation =
    Operation(idx, process, OpType.Fail, fn, key, None, None, ts())

  "LinearizabilityChecker" should "accept an empty history" in {
    val result = LinearizabilityChecker.check(History.empty, RegisterModel)
    result.linearizable shouldBe true
  }

  it should "accept a single write" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        ok(1, 0, OpFunction.Write, "x", Some("ok"))
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe true
  }

  it should "accept a sequential write-then-read" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        invoke(2, 0, OpFunction.Read, "x"),
        ok(2, 0, OpFunction.Read, "x", Some(Some(1)))
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe true
  }

  it should "reject a read that returns a value never written" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        invoke(2, 1, OpFunction.Read, "x"),
        ok(2, 1, OpFunction.Read, "x", Some(Some(999))) // 999 was never written
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe false
    result.violation shouldBe defined
  }

  it should "accept concurrent write and read with either ordering" in {
    // Write(x, 1) and Read(x) are concurrent — read can return None or Some(1)
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        invoke(2, 1, OpFunction.Read, "x"),
        ok(2, 1, OpFunction.Read, "x", Some(None)), // read before write
        ok(1, 0, OpFunction.Write, "x", Some("ok"))
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe true
  }

  it should "accept concurrent write and read returning the written value" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        invoke(2, 1, OpFunction.Read, "x"),
        ok(2, 1, OpFunction.Read, "x", Some(Some(1))), // read after write
        ok(1, 0, OpFunction.Write, "x", Some("ok"))
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe true
  }

  it should "reject a stale read that violates real-time ordering" in {
    // W(x,1) completes, then W(x,2) completes, then R(x) starts and returns 1
    // This violates linearizability: R must see 2 (or a later value)
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        invoke(2, 0, OpFunction.Write, "x", Some(2)),
        ok(2, 0, OpFunction.Write, "x", Some("ok")),
        invoke(3, 1, OpFunction.Read, "x"),
        ok(
          3,
          1,
          OpFunction.Read,
          "x",
          Some(Some(1))
        ) // stale: should be Some(2)
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe false
  }

  it should "accept read returning initial value before any writes" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Read, "y"),
        ok(1, 0, OpFunction.Read, "y", Some(None))
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe true
  }

  it should "handle CAS operations correctly" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        invoke(2, 0, OpFunction.CAS, "x", Some((1, 2))),
        ok(2, 0, OpFunction.CAS, "x", Some("ok")),
        invoke(3, 0, OpFunction.Read, "x"),
        ok(3, 0, OpFunction.Read, "x", Some(Some(2)))
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe true
  }

  it should "reject CAS that claims success but precondition wasn't met" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        invoke(
          2,
          0,
          OpFunction.CAS,
          "x",
          Some((999, 2))
        ), // precondition fails (x=1, not 999)
        ok(2, 0, OpFunction.CAS, "x", Some("ok")) // but CAS claims success
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe false
  }

  it should "handle P-compositionality — independent keys" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        invoke(2, 1, OpFunction.Write, "y", Some(2)),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        ok(2, 1, OpFunction.Write, "y", Some("ok")),
        invoke(3, 0, OpFunction.Read, "x"),
        invoke(4, 1, OpFunction.Read, "y"),
        ok(3, 0, OpFunction.Read, "x", Some(Some(1))),
        ok(4, 1, OpFunction.Read, "y", Some(Some(2)))
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe true
    result.stats.keysChecked shouldBe 2
  }

  it should "accept concurrent writes to different keys" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "a", Some("v1")),
        invoke(2, 1, OpFunction.Write, "b", Some("v2")),
        ok(2, 1, OpFunction.Write, "b", Some("ok")),
        ok(1, 0, OpFunction.Write, "a", Some("ok")),
        invoke(3, 0, OpFunction.Read, "a"),
        invoke(4, 1, OpFunction.Read, "b"),
        ok(3, 0, OpFunction.Read, "a", Some(Some("v1"))),
        ok(4, 1, OpFunction.Read, "b", Some(Some("v2")))
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe true
  }

  it should "trim incomplete operations" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        invoke(2, 1, OpFunction.Read, "x") // incomplete — no completion
      )
    )
    val trimmed = h.trimIncomplete
    trimmed.invocations.size shouldBe 1
  }

  it should "report useful stats" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        invoke(2, 0, OpFunction.Read, "x"),
        ok(2, 0, OpFunction.Read, "x", Some(Some(1)))
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.stats.opsChecked shouldBe 2
    result.stats.keysChecked shouldBe 1
    result.stats.exploredStates should be > 0L
  }

  it should "work with KVModel" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "user:1", Some("Alice")),
        ok(1, 0, OpFunction.Write, "user:1", Some("ok")),
        invoke(2, 0, OpFunction.Read, "user:1"),
        ok(2, 0, OpFunction.Read, "user:1", Some(Some("Alice")))
      )
    )
    val result = LinearizabilityChecker.check(h, KVModel)
    result.linearizable shouldBe true
  }

  it should "generate a timeline visualization" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        ok(1, 0, OpFunction.Write, "x", Some("ok"))
      )
    )
    val timeline = h.timeline
    timeline should include("Write")
    timeline should include("Invoke")
    timeline should include("Ok")
  }

  it should "handle three-way concurrent operations" in {
    nextTs = 0
    // Three concurrent writes: only one ordering is possible
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some(1)),
        invoke(2, 1, OpFunction.Write, "x", Some(2)),
        invoke(3, 2, OpFunction.Write, "x", Some(3)),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        ok(2, 1, OpFunction.Write, "x", Some("ok")),
        ok(3, 2, OpFunction.Write, "x", Some("ok")),
        invoke(4, 0, OpFunction.Read, "x"),
        ok(
          4,
          0,
          OpFunction.Read,
          "x",
          Some(Some(3))
        ) // valid if write(3) is last
      )
    )
    val result = LinearizabilityChecker.check(h, RegisterModel)
    result.linearizable shouldBe true
  }
