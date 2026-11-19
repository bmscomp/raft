package raft.state

/** Result of proposing, committing, and deterministically applying a client
  * command entry to a typed [[raft.spi.StateMachine]].
  *
  * @param logId
  *   committed `(term, index)` coordinate of the applied log entry
  * @param value
  *   typed output returned by `StateMachine.apply(log)`
  */
final case class Applied[+R](logId: LogId, value: R)
