package raft.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VolatileStateSpec extends AnyFlatSpec with Matchers:
  
  "VolatileState" should "start with zero indices" in {
    val state = VolatileState()
    state.commitIndex shouldBe 0
    state.lastApplied shouldBe 0
  }
  
  it should "advance commit index" in {
    val state = VolatileState(commitIndex = 5, lastApplied = 3)
    val advanced = state.advanceCommitIndex(10)
    advanced.commitIndex shouldBe 10
    advanced.lastApplied shouldBe 3  // unchanged
  }
  
  it should "not decrease commit index" in {
    val state = VolatileState(commitIndex = 10, lastApplied = 5)
    val same = state.advanceCommitIndex(5)
    same.commitIndex shouldBe 10  // unchanged
  }
  
  it should "advance last applied" in {
    val state = VolatileState(commitIndex = 10, lastApplied = 5)
    val advanced = state.advanceLastApplied(7)
    advanced.lastApplied shouldBe 7
  }
  
  it should "calculate pending applications" in {
    val state = VolatileState(commitIndex = 10, lastApplied = 5)
    state.pendingApplications.toList shouldBe List(6, 7, 8, 9, 10)
  }
  
  it should "return empty range when fully applied" in {
    val state = VolatileState(commitIndex = 10, lastApplied = 10)
    state.pendingApplications.isEmpty shouldBe true
  }
