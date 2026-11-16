package raft.state

import scala.util.control.NoStackTrace

/** Sealed error hierarchy for consensus and host-integration failures.
  *
  * Replaces untyped `RuntimeException("Not leader...")` strings so callers and
  * deterministic simulators can pattern-match on failure causes exhaustively.
  */
sealed abstract class RaftError(val detail: String)
    extends RuntimeException(detail)
    with NoStackTrace

object RaftError:
  /** Node rejected a client command or read because it is not the leader. */
  final case class NotLeader(leaderId: Option[NodeId])
      extends RaftError(
        s"Not leader. Known leader: ${leaderId.map(_.value).getOrElse("unknown")}"
      )

  /** Node is currently campaigning in an election or pre-election. */
  case object ElectionInProgress
      extends RaftError("Not leader. Election in progress")

  /** Leader rejected new proposals while transferring leadership (§3.10). */
  final case class LeadershipTransferInProgress(transferee: NodeId)
      extends RaftError(
        s"Leadership transfer in progress to ${transferee.value}"
      )

  /** Leader already has an uncommitted membership configuration change (§6). */
  final case class ConfigurationChangeInProgress(pending: ClusterConfig)
      extends RaftError(
        "Another cluster configuration change is already pending commitment"
      )

  /** Leader cannot propose a membership change until at least one entry from
    * its current term (such as its initial `NoOp`) has been committed (§4.1 /
    * §6).
    */
  final case class LeaderNotReadyInTerm(term: Long)
      extends RaftError(
        s"Leader in term $term has not yet committed an entry in its current term"
      )

  /** Proposal was dropped by the consensus state machine. */
  final case class ProposalDropped(reason: String)
      extends RaftError(s"Proposal dropped: $reason")

  /** Requested Multi-Raft group does not exist on this coordinator. */
  final case class GroupNotFound(groupId: GroupId)
      extends RaftError(s"Raft group not found: ${groupId.value}")

  /** Persistent storage corruption or checksum failure. */
  final case class StorageCorruption(reason: String)
      extends RaftError(s"Storage corruption: $reason")
