package raft.raw

import raft.message.RaftMessage
import raft.state.{
  HardState,
  Log,
  NodeId,
  NodeState,
  ReadState,
  SnapshotMetadata
}

/** Addressed protocol message emitted in [[Ready]] / [[LightReady]]. */
final case class OutboundMessage(
    from: NodeId,
    to: NodeId,
    message: RaftMessage
)

/** Volatile role/leader snapshot (`tikv/raft-rs` `SoftState`). */
final case class SoftState(
    leaderId: Option[NodeId],
    role: NodeState
)

/** Point-in-time batch of work produced by [[RawNode]] for the host system to
  * execute (modeled directly after `tikv/raft-rs`'s `Ready`).
  *
  * ### Required Host Execution Order
  *   1. If `snapshot` is `Some`, install snapshot metadata and state machine.
  *   2. Send `messages` (optional parallel leader optimization §10.2.1: leader
  *      `AppendEntries` RPCs may be transmitted while step 3 flushes to disk).
  *   3. Atomically persist `truncateFromIndex`, `entries`, and `hardState` to
  *      durable storage (`fsync` / WAL flush).
  *   4. Send `persistedMessages` (votes and follower ACKs that **must** follow
  *      durable WAL flush to preserve Election Safety and Leader Completeness).
  *   5. Apply `committedEntries` to the deterministic state machine in index
  *      order, then serve `readStates` once `lastApplied >= readState.index`.
  *   6. Call `rawNode.advance(ready)` and process the returned [[LightReady]].
  */
final case class Ready(
    softState: Option[SoftState] = None,
    hardState: Option[HardState] = None,
    truncateFromIndex: Option[Long] = None,
    entries: Vector[Log] = Vector.empty,
    snapshot: Option[SnapshotMetadata] = None,
    messages: Vector[OutboundMessage] = Vector.empty,
    persistedMessages: Vector[OutboundMessage] = Vector.empty,
    committedEntries: Vector[Log] = Vector.empty,
    readStates: Vector[ReadState] = Vector.empty
):
  def isEmpty: Boolean =
    softState.isEmpty &&
      hardState.isEmpty &&
      truncateFromIndex.isEmpty &&
      entries.isEmpty &&
      snapshot.isEmpty &&
      messages.isEmpty &&
      persistedMessages.isEmpty &&
      committedEntries.isEmpty &&
      readStates.isEmpty

  def nonEmpty: Boolean = !isEmpty

/** Follow-up batch returned by `RawNode.advance(ready)` once unstable entries
  * and `HardState` have been persisted (modeled after `tikv/raft-rs`
  * `LightReady`).
  *
  * For example, on a single-node cluster or when the leader's own disk flush
  * completes a quorum commit, `advance(ready)` immediately surfaces newly
  * committed entries and follow-up replication messages without waiting for
  * another network event.
  */
final case class LightReady(
    commitIndex: Option[Long] = None,
    committedEntries: Vector[Log] = Vector.empty,
    messages: Vector[OutboundMessage] = Vector.empty
):
  def isEmpty: Boolean =
    commitIndex.isEmpty && committedEntries.isEmpty && messages.isEmpty
