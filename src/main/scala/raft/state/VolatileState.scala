package raft.state

/**
 * Volatile state tracked by every node (not persisted).
 * 
 * @param commitIndex highest log index known to be committed
 * @param lastApplied highest log index applied to state machine
 */
case class VolatileState(
  commitIndex: Long = 0,
  lastApplied: Long = 0
):
  def advanceCommitIndex(newCommit: Long): VolatileState =
    if newCommit > commitIndex then copy(commitIndex = newCommit) else this
  
  def advanceLastApplied(index: Long): VolatileState =
    if index > lastApplied then copy(lastApplied = index) else this
  
  /** Get entries that need to be applied (committed but not yet applied) */
  def pendingApplications: Range.Inclusive =
    if lastApplied < commitIndex then 
      ((lastApplied + 1).toInt to commitIndex.toInt)
    else 
      (1 to 0) // empty range
