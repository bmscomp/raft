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
      value: Option[Any]
  ): Operation =
    Operation(idx, process, OpType.Ok, fn, key, None, value, ts())

  private def info(
      idx: Long,
      process: Int,
      fn: OpFunction,
      key: String,
      msg: String
  ): Operation =
    Operation(idx, process, OpType.Info, fn, key, None, Some(msg), ts())

  private def failOp(
      idx: Long,
      process: Int,
      fn: OpFunction,
      key: String,
      value: Option[Any]
  ): Operation =
    Operation(idx, process, OpType.Fail, fn, key, None, value, ts())

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

  it should "accept a read and CAS observing a value written by an indeterminate (Info) write" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "key-0", Some("c0_init")),
        ok(1, 0, OpFunction.Write, "key-0", Some("ok")),
        // Client 2 submits Write("c2_757"), which commits in Raft while client times out -> Info
        invoke(2, 2, OpFunction.Write, "key-0", Some("c2_757")),
        info(2, 2, OpFunction.Write, "key-0", "timeout"),
        // Client 3 subsequently observes "c2_757" and CASes it to "c3_766"
        invoke(3, 3, OpFunction.CAS, "key-0", Some(("c2_757", "c3_766"))),
        ok(3, 3, OpFunction.CAS, "key-0", Some("ok")),
        invoke(4, 1, OpFunction.Read, "key-0"),
        ok(4, 1, OpFunction.Read, "key-0", Some(Some("c3_766")))
      )
    )
    val result = LinearizabilityChecker.check(h, KVModel)
    result.linearizable shouldBe true
  }

  it should "accept a history where an indeterminate (Info) write never took effect" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "key-0", Some("v1")),
        ok(1, 0, OpFunction.Write, "key-0", Some("ok")),
        invoke(2, 2, OpFunction.Write, "key-0", Some("dropped")),
        info(2, 2, OpFunction.Write, "key-0", "timeout"),
        invoke(3, 1, OpFunction.Read, "key-0"),
        ok(3, 1, OpFunction.Read, "key-0", Some(Some("v1")))
      )
    )
    val result = LinearizabilityChecker.check(h, KVModel)
    result.linearizable shouldBe true
  }

  it should "reject a read that completes BEFORE an indeterminate (Info) write was even invoked" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 1, OpFunction.Read, "key-0"),
        ok(1, 1, OpFunction.Read, "key-0", Some(Some("future-val"))),
        invoke(2, 2, OpFunction.Write, "key-0", Some("future-val")),
        info(2, 2, OpFunction.Write, "key-0", "timeout")
      )
    )
    val result = LinearizabilityChecker.check(h, KVModel)
    result.linearizable shouldBe false
  }

  it should "verify monotonic increments, CAS, and reject stale reads under CounterModel" in {
    nextTs = 0
    val validHistory = History(
      Vector(
        invoke(1, 0, OpFunction.Read, "c"),
        ok(1, 0, OpFunction.Read, "c", Some(0L)),
        invoke(2, 0, OpFunction.Write, "c"),
        invoke(3, 1, OpFunction.Write, "c"),
        ok(2, 0, OpFunction.Write, "c", Some(1L)),
        ok(3, 1, OpFunction.Write, "c", Some(2L)),
        invoke(4, 0, OpFunction.CAS, "c", Some((2L, 10L))),
        ok(4, 0, OpFunction.CAS, "c", Some("ok")),
        invoke(5, 1, OpFunction.Read, "c"),
        ok(5, 1, OpFunction.Read, "c", Some(10L))
      )
    )
    LinearizabilityChecker
      .check(validHistory, CounterModel)
      .linearizable shouldBe true

    nextTs = 0
    val staleCounter = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "c"),
        ok(1, 0, OpFunction.Write, "c", Some(1L)),
        invoke(2, 0, OpFunction.Write, "c"),
        ok(2, 0, OpFunction.Write, "c", Some(2L)),
        invoke(3, 1, OpFunction.Read, "c"),
        ok(3, 1, OpFunction.Read, "c", Some(1L))
      )
    )
    LinearizabilityChecker
      .check(staleCounter, CounterModel)
      .linearizable shouldBe false
  }

  it should "reject non-linearizable crossed reads (R1 observes v2 before R2 starts and observes v1)" in {
    nextTs = 0
    val h = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "x", Some("v1")),
        ok(1, 0, OpFunction.Write, "x", Some("ok")),
        invoke(2, 0, OpFunction.Write, "x", Some("v2")),
        invoke(3, 1, OpFunction.Read, "x"),
        ok(3, 1, OpFunction.Read, "x", Some(Some("v2"))),
        invoke(4, 2, OpFunction.Read, "x"),
        ok(4, 2, OpFunction.Read, "x", Some(Some("v1"))),
        ok(2, 0, OpFunction.Write, "x", Some("ok"))
      )
    )
    val result = LinearizabilityChecker.check(h, KVModel)
    result.linearizable shouldBe false
    result.violation shouldBe defined
  }

  it should "validate OpType.Fail CAS outcomes accurately in both directions" in {
    nextTs = 0
    val validFailCas = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "k", Some("a")),
        ok(1, 0, OpFunction.Write, "k", Some("ok")),
        invoke(2, 1, OpFunction.CAS, "k", Some(("wrong", "b"))),
        failOp(2, 1, OpFunction.CAS, "k", Some("failed")),
        invoke(3, 0, OpFunction.Read, "k"),
        ok(3, 0, OpFunction.Read, "k", Some(Some("a")))
      )
    )
    LinearizabilityChecker.check(validFailCas, KVModel).linearizable shouldBe
      true

    nextTs = 0
    val bogusFailCas = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "k", Some("a")),
        ok(1, 0, OpFunction.Write, "k", Some("ok")),
        invoke(2, 1, OpFunction.CAS, "k", Some(("a", "b"))),
        failOp(2, 1, OpFunction.CAS, "k", Some("failed"))
      )
    )
    LinearizabilityChecker.check(bogusFailCas, KVModel).linearizable shouldBe
      false
  }

  it should "handle indeterminate (Info) CAS operations whether they took effect or not" in {
    nextTs = 0
    val infoCasTookEffect = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "k", Some("v0")),
        ok(1, 0, OpFunction.Write, "k", Some("ok")),
        invoke(2, 1, OpFunction.CAS, "k", Some(("v0", "v1"))),
        info(2, 1, OpFunction.CAS, "k", "timeout"),
        invoke(3, 2, OpFunction.Read, "k"),
        ok(3, 2, OpFunction.Read, "k", Some(Some("v1")))
      )
    )
    LinearizabilityChecker
      .check(infoCasTookEffect, KVModel)
      .linearizable shouldBe true

    nextTs = 0
    val infoCasDropped = History(
      Vector(
        invoke(1, 0, OpFunction.Write, "k", Some("v0")),
        ok(1, 0, OpFunction.Write, "k", Some("ok")),
        invoke(2, 1, OpFunction.CAS, "k", Some(("v0", "v1"))),
        info(2, 1, OpFunction.CAS, "k", "timeout"),
        invoke(3, 2, OpFunction.Read, "k"),
        ok(3, 2, OpFunction.Read, "k", Some(Some("v0")))
      )
    )
    LinearizabilityChecker
      .check(infoCasDropped, KVModel)
      .linearizable shouldBe true
  }
