package raft.multigroup

import raft.state.{GroupId, NodeId}
import raft.message.RaftMessage

/** Wire-level wrapper that tags a [[RaftMessage]] with its target group.
  *
  * In a Multi-Raft deployment, a single network connection between two physical
  * nodes carries messages for ''many'' independent consensus groups. The
  * `GroupEnvelope` is the multiplexing unit: it wraps any standard Raft RPC
  * with a [[GroupId]] so the receiving node can route it to the correct group's
  * event loop.
  *
  * This design follows the CockroachDB/TiKV pattern of ''connection reuse'' —
  * rather than opening one TCP connection per group pair, all groups share the
  * same transport infrastructure and rely on the envelope's `groupId` for
  * demultiplexing.
  *
  * @param groupId
  *   the target consensus group for this message
  * @param from
  *   the sender node's identifier
  * @param message
  *   the wrapped Raft protocol message
  * @see
  *   [[MultiGroupTransport]] for the transport layer that sends/receives
  *   envelopes
  * @see
  *   [[MultiRaftNode]] for the coordinator that routes envelopes to group
  *   instances
  */
case class GroupEnvelope(
    groupId: GroupId,
    from: NodeId,
    message: RaftMessage
)
