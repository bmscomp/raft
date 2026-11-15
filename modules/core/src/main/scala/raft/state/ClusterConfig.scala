package raft.state

import java.nio.charset.StandardCharsets

/** Role a node can play within the RAFT cluster. */
enum NodeRole:
  /** Full voting member storing the complete log and voting in elections. */
  case Voter

  /** Non-voting member catching up via log replication before promotion. */
  case Learner

  /** Voting tie-breaker participating in elections and quorum. */
  case Witness

/** Cluster configuration for single-config and Joint Consensus (§6,
  * `ConfChangeV2`) membership management.
  *
  * During joint consensus (`isInJointConsensus == true`), **every** election
  * and log commitment decision requires independent majorities in *both* the
  * old voter set (`oldVoters`) and the target voter set (`newVoters`),
  * eliminating split-brain transitions by construction.
  *
  * @param members
  *   active membership map (union of old and new during joint consensus)
  * @param jointConfig
  *   preserved `C_old` membership map when in joint consensus
  * @param pendingConfig
  *   target `C_new` membership map when in joint consensus
  */
final case class ClusterConfig(
    members: Map[NodeId, NodeRole] = Map.empty,
    jointConfig: Option[Map[NodeId, NodeRole]] = None,
    pendingConfig: Option[Map[NodeId, NodeRole]] = None
):
  require(
    members.values.exists(ClusterConfig.isVotingRole),
    "Cluster configuration must have at least one voter"
  )

  /** All voting-eligible members (`Voter` + `Witness`) in `members`. */
  def voters: Set[NodeId] =
    ClusterConfig.votingNodesOf(members)

  /** Target `C_new` voter set (equals `voters` when not in joint consensus). */
  def newVoters: Set[NodeId] =
    pendingConfig match
      case Some(target) => ClusterConfig.votingNodesOf(target)
      case None         => voters

  /** Preserved `C_old` voter set when in joint consensus. */
  def oldVoters: Option[Set[NodeId]] =
    jointConfig.map(ClusterConfig.votingNodesOf)

  /** All non-voting learner members. */
  def learners: Set[NodeId] =
    members.collect { case (id, NodeRole.Learner) => id }.toSet

  /** All witness members. */
  def witnesses: Set[NodeId] =
    members.collect { case (id, NodeRole.Witness) => id }.toSet

  /** All nodes currently tracked by this configuration (union during joint). */
  def allNodes: Set[NodeId] = members.keySet

  /** Whether the cluster is currently in a Joint Consensus (`C_old,new`)
    * transition.
    */
  def isInJointConsensus: Boolean = jointConfig.isDefined

  /** Add or update a member in single-config mode. */
  def addMember(nodeId: NodeId, role: NodeRole): ClusterConfig =
    copy(members = members.updated(nodeId, role))

  /** Remove a member in single-config mode. */
  def removeMember(nodeId: NodeId): ClusterConfig =
    copy(members = members - nodeId)

  /** Promote an existing `Learner` to `Voter`. */
  def promoteLearner(nodeId: NodeId): ClusterConfig =
    members.get(nodeId) match
      case Some(NodeRole.Learner) =>
        copy(members = members.updated(nodeId, NodeRole.Voter))
      case _ => this

  /** Enter Joint Consensus `C_old,new` (§6). */
  def beginJointConsensus(newConfig: Map[NodeId, NodeRole]): ClusterConfig =
    require(newConfig.nonEmpty, "New configuration cannot be empty")
    require(
      newConfig.values.exists(ClusterConfig.isVotingRole),
      "New configuration must have at least one voter"
    )
    val merged = members ++ newConfig
    copy(
      members = merged,
      jointConfig = Some(members),
      pendingConfig = Some(newConfig)
    )

  /** Finalize Joint Consensus, transitioning from `C_old,new` to `C_new`. */
  def completeJointConsensus: ClusterConfig =
    pendingConfig match
      case Some(newMembers) =>
        copy(members = newMembers, jointConfig = None, pendingConfig = None)
      case None =>
        copy(jointConfig = None, pendingConfig = None)

  /** Roll back an uncommitted Joint Consensus transition to `C_old`. */
  def abortJointConsensus: ClusterConfig =
    jointConfig match
      case Some(oldConfig) =>
        copy(members = oldConfig, jointConfig = None, pendingConfig = None)
      case None => this

  def isVoter(nodeId: NodeId): Boolean =
    members.get(nodeId).exists(ClusterConfig.isVotingRole)

  def isLearner(nodeId: NodeId): Boolean =
    members.get(nodeId).contains(NodeRole.Learner)

  def contains(nodeId: NodeId): Boolean = members.contains(nodeId)

  /** Majority threshold for `newVoters` (or `voters` in single-config mode). */
  def majoritySize: Int = (newVoters.size / 2) + 1

  /** True iff `respondingNodes` forms a valid Raft quorum (§5.2, §6).
    *
    * In Joint Consensus, `respondingNodes` must independently achieve a strict
    * majority in **both** `C_old` (`oldVoters`) and `C_new` (`newVoters`).
    */
  def hasQuorum(respondingNodes: Set[NodeId]): Boolean =
    val targetVoters = newVoters
    val targetMaj = (targetVoters.size / 2) + 1
    val hasNewQuorum = respondingNodes.intersect(targetVoters).size >= targetMaj
    oldVoters match
      case None      => hasNewQuorum
      case Some(old) =>
        val oldMaj = (old.size / 2) + 1
        hasNewQuorum && (respondingNodes.intersect(old).size >= oldMaj)

object ClusterConfig:
  private[state] def isVotingRole(role: NodeRole): Boolean =
    role == NodeRole.Voter || role == NodeRole.Witness

  private[state] def votingNodesOf(m: Map[NodeId, NodeRole]): Set[NodeId] =
    m.collect { case (id, role) if isVotingRole(role) => id }.toSet

  def single(nodeId: NodeId): ClusterConfig =
    ClusterConfig(Map(nodeId -> NodeRole.Voter))

  def apply(voters: Set[NodeId]): ClusterConfig =
    ClusterConfig(voters.map(_ -> NodeRole.Voter).toMap)

  /** Encode a [[ClusterConfig]] into deterministic UTF-8 bytes for
    * `LogType.Configuration` entries.
    */
  def encode(config: ClusterConfig): Array[Byte] =
    def encodeMap(m: Map[NodeId, NodeRole]): String =
      m.toSeq
        .sortBy(_._1.value)
        .map { case (id, role) => s"${id.value}:${role.ordinal}" }
        .mkString(",")

    val currentPart = encodeMap(config.members)
    val oldPart = config.jointConfig.map(encodeMap).getOrElse("")
    val pendingPart = config.pendingConfig.map(encodeMap).getOrElse("")
    s"v1|$currentPart|$oldPart|$pendingPart".getBytes(StandardCharsets.UTF_8)

  /** Decode a [[ClusterConfig]] from `LogType.Configuration` entry payload. */
  def decode(bytes: Array[Byte]): Either[String, ClusterConfig] =
    def decodeMap(part: String): Either[String, Map[NodeId, NodeRole]] =
      if part.isEmpty then Right(Map.empty)
      else
        val entries = part.split(",").toList
        entries
          .foldLeft[Either[String, Map[NodeId, NodeRole]]](Right(Map.empty)) {
            case (Left(err), _)     => Left(err)
            case (Right(acc), item) =>
              val idx = item.lastIndexOf(':')
              if idx <= 0 then Left(s"Malformed member entry: $item")
              else
                val id = NodeId(item.substring(0, idx))
                item.substring(idx + 1).toIntOption match
                  case Some(ord) if ord >= 0 && ord < NodeRole.values.length =>
                    Right(acc.updated(id, NodeRole.fromOrdinal(ord)))
                  case _ => Left(s"Invalid role ordinal in: $item")
          }

    val raw = new String(bytes, StandardCharsets.UTF_8)
    raw.split("\\|", -1).toList match
      case "v1" :: currentPart :: oldPart :: pendingPart :: Nil =>
        for
          members <- decodeMap(currentPart)
          oldOpt <-
            if oldPart.isEmpty then Right(None)
            else decodeMap(oldPart).map(Some(_))
          pendingOpt <-
            if pendingPart.isEmpty then Right(None)
            else decodeMap(pendingPart).map(Some(_))
          cfg <-
            if members.values.exists(isVotingRole) then
              Right(ClusterConfig(members, oldOpt, pendingOpt))
            else Left("Decoded ClusterConfig contains no voting members")
        yield cfg
      case _ => Left(s"Unsupported ClusterConfig wire payload: $raw")
