package raft.state

/** Role a node can play within the RAFT cluster.
  *
  * Roles determine whether a node participates in voting, log replication, or
  * both. They are used during dynamic membership changes to safely transition
  * nodes between responsibilities.
  *
  * @see
  *   [[ClusterConfig]] for cluster-level role management
  */
enum NodeRole:
  /** Full voting member that stores the complete log and participates in
    * elections.
    */
  case Voter

  /** Non-voting member that receives log entries for catch-up but does not
    * participate in elections or quorum decisions. Learners are typically nodes
    * being brought online before promotion to Voter.
    */
  case Learner

  /** Voting member that participates in elections and quorum but does not store
    * the full log data. Used as a lightweight tie-breaker in even-sized
    * clusters.
    */
  case Witness

/** Cluster configuration for dynamic membership management.
  *
  * Tracks the current set of cluster members and their roles. Supports the
  * ''joint consensus'' protocol for safe membership transitions, where quorums
  * must be achieved in ''both'' the old and new configurations simultaneously
  * during the transition period.
  *
  * @param members
  *   current cluster members mapped to their roles
  * @param jointConfig
  *   during a joint consensus transition, holds the old configuration; `None`
  *   when operating under a single stable configuration
  * @see
  *   [[NodeRole]] for the possible roles a node can hold
  * @see
  *   [[raft.message.RaftMessage.AddServerRequest]] for membership change RPCs
  */
case class ClusterConfig(
    members: Map[NodeId, NodeRole] = Map.empty,
    jointConfig: Option[Map[NodeId, NodeRole]] = None,
    pendingConfig: Option[Map[NodeId, NodeRole]] = None
):
  require(
    members.values.exists(r => r == NodeRole.Voter || r == NodeRole.Witness),
    "Cluster configuration must have at least one voter"
  )

  /** All voting-eligible members (Voter + Witness roles).
    *
    * @return
    *   set of node IDs that participate in elections and quorum decisions
    */
  def voters: Set[NodeId] =
    members.filter(_._2 == NodeRole.Voter).keySet ++
      members.filter(_._2 == NodeRole.Witness).keySet

  /** All non-voting learner members.
    *
    * @return
    *   set of node IDs in the Learner role
    */
  def learners: Set[NodeId] =
    members.filter(_._2 == NodeRole.Learner).keySet

  /** All nodes in the cluster regardless of role.
    *
    * @return
    *   set of all member node IDs
    */
  def allNodes: Set[NodeId] = members.keySet

  /** Whether the cluster is currently in a joint consensus transition.
    *
    * @return
    *   `true` if both old and new configurations are active simultaneously
    */
  def isInJointConsensus: Boolean = jointConfig.isDefined

  /** Add a new member to the cluster with the given role.
    *
    * @param nodeId
    *   the identifier of the node to add
    * @param role
    *   the role to assign to the new member
    * @return
    *   an updated configuration with the new member included
    */
  def addMember(nodeId: NodeId, role: NodeRole): ClusterConfig =
    copy(members = members.updated(nodeId, role))

  /** Remove a member from the cluster.
    *
    * @param nodeId
    *   the identifier of the node to remove
    * @return
    *   an updated configuration with the member removed
    */
  def removeMember(nodeId: NodeId): ClusterConfig =
    copy(members = members - nodeId)

  /** Promote a Learner to a full Voter.
    *
    * Has no effect if the node is not currently a Learner.
    *
    * @param nodeId
    *   the identifier of the learner to promote
    * @return
    *   an updated configuration (unchanged if `nodeId` is not a Learner)
    */
  def promoteLearner(nodeId: NodeId): ClusterConfig =
    members.get(nodeId) match
      case Some(NodeRole.Learner) =>
        copy(members = members.updated(nodeId, NodeRole.Voter))
      case _ => this

  /** Begin a joint consensus transition by switching to the new configuration
    * while preserving the old one in `jointConfig`.
    *
    * During joint consensus, quorum decisions require majorities in ''both''
    * the old (preserved in `jointConfig`) and the new (active in `members`)
    * configurations.
    *
    * @param newConfig
    *   the new member-role mappings to switch to
    * @return
    *   an updated configuration in joint consensus mode
    * @throws IllegalArgumentException
    *   if newConfig is empty or contains no voters
    * @see
    *   [[completeJointConsensus]] to finalize the transition
    */
  def beginJointConsensus(newConfig: Map[NodeId, NodeRole]): ClusterConfig =
    require(newConfig.nonEmpty, "New configuration cannot be empty")
    require(
      newConfig.values.exists(_ != NodeRole.Learner),
      "New configuration must have at least one voter"
    )
    val merged = members ++ newConfig
    copy(
      members = merged,
      jointConfig = Some(members),
      pendingConfig = Some(newConfig)
    )

  /** Complete the joint consensus transition, dropping the old configuration.
    *
    * After this call, quorum decisions use only the current `members` map.
    *
    * @return
    *   a configuration operating under a single stable membership
    * @see
    *   [[beginJointConsensus]] to start a transition
    */
  def completeJointConsensus: ClusterConfig =
    pendingConfig match
      case Some(newMembers) =>
        copy(members = newMembers, jointConfig = None, pendingConfig = None)
      case None => copy(jointConfig = None, pendingConfig = None)

  /** Abort a joint consensus transition, reverting to the old configuration.
    *
    * This is useful if the transition fails or times out before being
    * committed.
    *
    * @return
    *   the original configuration before the transition started
    */
  def abortJointConsensus: ClusterConfig =
    jointConfig match
      case Some(oldConfig) =>
        copy(members = oldConfig, jointConfig = None, pendingConfig = None)
      case None => this

  /** Check if a node is a voting member (Voter or Witness). */
  def isVoter(nodeId: NodeId): Boolean =
    members
      .get(nodeId)
      .exists(r => r == NodeRole.Voter || r == NodeRole.Witness)

  /** Check if a node is a Learner. */
  def isLearner(nodeId: NodeId): Boolean =
    members.get(nodeId).contains(NodeRole.Learner)

  /** Check if a node is part of the cluster in any role. */
  def contains(nodeId: NodeId): Boolean = members.contains(nodeId)

  /** The number of votes required for a majority among current voters.
    *
    * @return
    *   the minimum number of agreeing voters needed for quorum
    */
  def majoritySize: Int = (voters.size / 2) + 1

  /** Check whether a quorum has been achieved from the given set of responding
    * nodes.
    *
    * During joint consensus, a quorum requires a majority in ''both'' the old
    * and new configurations. Under a single configuration, a simple majority of
    * voters suffices.
    *
    * @param respondingNodes
    *   set of node IDs that have responded affirmatively
    * @return
    *   `true` if the responding nodes form a quorum
    */
  def hasQuorum(respondingNodes: Set[NodeId]): Boolean =
    val votingResponders = respondingNodes.intersect(voters)
    jointConfig match
      case None =>
        votingResponders.size >= majoritySize
      case Some(oldMembers) =>
        // Joint consensus requires majority in BOTH configs
        val oldVoters = oldMembers.filter(_._2 == NodeRole.Voter).keySet
        val oldMajority = (oldVoters.size / 2) + 1
        val newMajority = majoritySize
        votingResponders.intersect(oldVoters).size >= oldMajority &&
        votingResponders.size >= newMajority

/** Factory methods for [[ClusterConfig]]. */
object ClusterConfig:
  /** Create a single-node cluster configuration.
    *
    * @param nodeId
    *   the sole voting member
    * @return
    *   a cluster configuration containing one Voter
    */
  def single(nodeId: NodeId): ClusterConfig =
    ClusterConfig(Map(nodeId -> NodeRole.Voter))

  /** Create a cluster configuration from a set of voter node IDs.
    *
    * All nodes are assigned the [[NodeRole.Voter]] role.
    *
    * @param voters
    *   the set of node IDs to include as voters
    * @return
    *   a cluster configuration with all members as voters
    */
  def apply(voters: Set[NodeId]): ClusterConfig =
    ClusterConfig(voters.map(_ -> NodeRole.Voter).toMap)
