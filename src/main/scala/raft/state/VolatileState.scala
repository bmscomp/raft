package raft.state

/** Volatile state tracked by every RAFT node (not persisted to stable storage).
  *
  * This state is re-initialized upon restart and is maintained in-memory only.
  * It tracks the commit and application progress of log entries, which allows
  * the runtime to determine which committed entries still need to be applied to
  * the state machine.
  *
  * @param commitIndex
  *   the highest log entry index known to be committed (replicated to a
  *   majority and safe to apply)
  * @param lastApplied
  *   the highest log entry index that has been applied to the state machine
  *   (always <= `commitIndex`)
  * @see
  *   [[raft.spi.StateMachine]] for applying committed entries
  * @see
  *   [[raft.effect.Effect.CommitEntries]] for the effect that advances the
  *   commit index
  */
case class VolatileState(
    commitIndex: Long = 0,
    lastApplied: Long = 0
):
  /** Advance the commit index if the new value is higher.
    *
    * @param newCommit
    *   the proposed new commit index
    * @return
    *   an updated state with `commitIndex = max(commitIndex, newCommit)`
    */
  def advanceCommitIndex(newCommit: Long): VolatileState =
    if newCommit > commitIndex then copy(commitIndex = newCommit) else this

  /** Advance the last-applied index if the new value is higher.
    *
    * @param index
    *   the index of the entry that was just applied
    * @return
    *   an updated state with `lastApplied = max(lastApplied, index)`
    */
  def advanceLastApplied(index: Long): VolatileState =
    if index > lastApplied then copy(lastApplied = index) else this

  /** Get the range of entry indices that are committed but not yet applied.
    *
    * @return
    *   an inclusive range `(lastApplied+1) to commitIndex`, or an empty range
    *   if all committed entries have already been applied
    */
  def pendingApplications: Range.Inclusive =
    if lastApplied < commitIndex then
      ((lastApplied + 1).toInt to commitIndex.toInt)
    else (1 to 0) // empty range
