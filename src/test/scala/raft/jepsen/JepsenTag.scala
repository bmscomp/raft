package raft.jepsen

import org.scalatest.Tag

/** Tag for Jepsen-style integration tests.
  *
  * These tests are excluded from `sbt test` and run separately via:
  * {{{
  *   sbt "testOnly -- -n Jepsen"
  * }}}
  */
object JepsenTag extends Tag("Jepsen")
