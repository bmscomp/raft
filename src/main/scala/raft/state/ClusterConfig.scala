package raft.state

/**
 * Node role in cluster (for membership changes).
 */
enum NodeRole:
  case Voter    // Full voting member
  case Learner  // Non-voting, catch-up member
  case Witness  // Voting but doesn't store data (tie-breaker)

/**
 * Cluster configuration for dynamic membership.
 * 
 * @param members current cluster members and their roles
 * @param jointConfig during joint consensus, the old config
 */
case class ClusterConfig(
  members: Map[NodeId, NodeRole] = Map.empty,
  jointConfig: Option[Map[NodeId, NodeRole]] = None
):
  def voters: Set[NodeId] = 
    members.filter(_._2 == NodeRole.Voter).keySet ++
    members.filter(_._2 == NodeRole.Witness).keySet
  
  def learners: Set[NodeId] =
    members.filter(_._2 == NodeRole.Learner).keySet
  
  def allNodes: Set[NodeId] = members.keySet
  
  def isInJointConsensus: Boolean = jointConfig.isDefined
  
  def addMember(nodeId: NodeId, role: NodeRole): ClusterConfig =
    copy(members = members.updated(nodeId, role))
  
  def removeMember(nodeId: NodeId): ClusterConfig =
    copy(members = members - nodeId)
  
  def promoteLearner(nodeId: NodeId): ClusterConfig =
    members.get(nodeId) match
      case Some(NodeRole.Learner) => copy(members = members.updated(nodeId, NodeRole.Voter))
      case _ => this
  
  /** Begin joint consensus transition */
  def beginJointConsensus(newConfig: Map[NodeId, NodeRole]): ClusterConfig =
    copy(members = members ++ newConfig, jointConfig = Some(members))
  
  /** Complete joint consensus transition */
  def completeJointConsensus: ClusterConfig =
    copy(jointConfig = None)
  
  /** Calculate majority for quorum */
  def majoritySize: Int = (voters.size / 2) + 1
  
  /** Check if we have quorum from given voters */
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

object ClusterConfig:
  def single(nodeId: NodeId): ClusterConfig =
    ClusterConfig(Map(nodeId -> NodeRole.Voter))
  
  def apply(voters: Set[NodeId]): ClusterConfig =
    ClusterConfig(voters.map(_ -> NodeRole.Voter).toMap)
