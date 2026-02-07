# Tutorial 5: Cluster Membership Management

*How to add servers, remove servers, and replace the entire cluster — without losing a single write. The operational runbook you wish existed for every consensus system.*

---

## The Problem

Every Raft cluster eventually needs membership changes:

- **Scaling up**: Adding a 4th and 5th node for higher read throughput and fault tolerance
- **Replacing hardware**: Swapping out a failing disk by moving a node to a new server
- **Rolling upgrades**: Replacing nodes one-by-one with a new software version
- **Scaling down**: Removing an unnecessary node to save costs

The danger is **split-brain during the transition**. If you naively change from a 3-node cluster to a 4-node cluster, there's a moment where:
- Old config (3 nodes): quorum = 2
- New config (4 nodes): quorum = 3
- A majority of the old config (2 nodes) and a majority of the new config (3 nodes) could overlap by **only 1 node** — meaning two independent majorities could elect two leaders

This isn't theoretical. The Raft paper dedicates an entire section (§6) to this problem, and real systems (including early etcd versions) have hit it in production.

## Joint Consensus: The Safe Approach

The library implements **joint consensus** (Raft §6), where membership changes go through a two-phase protocol that guarantees no two majorities can exist simultaneously:

```
Phase 1:  C_old → C_old,new  (joint config — both old AND new must agree)
Phase 2:  C_old,new → C_new  (new config takes effect)
```

During the joint phase (`C_old,new`), every decision (election, commit) requires agreement from **both** a majority of the old config **and** a majority of the new config. This eliminates split-brain by construction.

```
┌─────────────────────────────────────────────────────────────────┐
│                  Joint Consensus Timeline                        │
│                                                                 │
│  C_old          C_old,new                    C_new              │
│  [A,B,C]   →   [A,B,C] ∩ [A,B,C,D]    →   [A,B,C,D]          │
│                                                                 │
│  Quorum=2      Quorum = maj(old) AND        Quorum=3           │
│                         maj(new)                                │
│                = 2 of [A,B,C]                                   │
│                  AND 3 of [A,B,C,D]                             │
│                                                                 │
│  Any leader must satisfy BOTH quorum requirements               │
│  → no split brain possible                                      │
└─────────────────────────────────────────────────────────────────┘
```

## The ClusterConfig Type

The library's `ClusterConfig` encodes the cluster membership state:

```scala
import raft.state.{ClusterConfig, NodeId}

// A normal (non-transitioning) configuration
val threeNodeCluster = ClusterConfig(
  voters = Set(NodeId("A"), NodeId("B"), NodeId("C"))
)

// A joint configuration (mid-transition)
val jointConfig = ClusterConfig(
  voters = Set(NodeId("A"), NodeId("B"), NodeId("C"), NodeId("D")),
  oldVoters = Some(Set(NodeId("A"), NodeId("B"), NodeId("C"))),
  learners = Set.empty,
  witnesses = Set.empty
)

// The final configuration
val fourNodeCluster = ClusterConfig(
  voters = Set(NodeId("A"), NodeId("B"), NodeId("C"), NodeId("D"))
)
```

Key invariants enforced by the library:
- A voter cannot simultaneously be a learner or witness
- During joint consensus, `oldVoters` is `Some` — the leader requires both majorities
- The leader automatically transitions from joint to final config after the joint entry is committed

## Operational Runbook: Adding a Node

### Step 1: Start the new node as a learner

A learner receives the log but doesn't vote. This lets it catch up without affecting quorum:

```scala
val newNodeId = NodeId("D")

// On the leader: add D as a learner
val learnerConfig = currentConfig.copy(
  learners = currentConfig.learners + newNodeId
)
// Propose the new config as a configuration change entry
raftNode.proposeConfigChange(learnerConfig)
```

**Why learner first?** If you add D directly as a voter, it starts with an empty log while A, B, C have millions of entries. Until D catches up, you effectively have a 4-node cluster where only 3 nodes can participate in quorum — one slow node can stall commits.

### Step 2: Wait for the learner to catch up

Monitor the learner's log replication progress:

```scala
def isLearnerCaughtUp(
  leaderLastIndex: Long,
  learnerMatchIndex: Long,
  threshold: Long = 100  // within 100 entries
): Boolean =
  leaderLastIndex - learnerMatchIndex <= threshold
```

The leader tracks `matchIndex` for all followers, including learners. Wait until the gap is small enough that the transition will be fast:

```
Leader log: [1, 2, 3, ..., 1_000_000]
Learner D:  [1, 2, 3, ...,   999_950]  ← gap = 50, ready to promote
```

### Step 3: Promote to voter via joint consensus

```scala
val jointConfig = ClusterConfig(
  voters = Set(NodeId("A"), NodeId("B"), NodeId("C"), NodeId("D")),
  oldVoters = Some(Set(NodeId("A"), NodeId("B"), NodeId("C"))),
  learners = Set.empty  // D is no longer a learner
)
raftNode.proposeConfigChange(jointConfig)
```

This appends a **joint configuration entry** to the log. Once committed:
- Elections require majority of `{A,B,C}` AND majority of `{A,B,C,D}`
- The leader automatically appends the **final configuration entry**: `voters = {A,B,C,D}`, `oldVoters = None`

### Step 4: Verify the final configuration

After both config entries are committed, the cluster is in its new configuration:

```scala
def verifyConfig(config: ClusterConfig): IO[Unit] =
  for
    _ <- IO.println(s"Voters: ${config.voters.map(_.value).mkString(", ")}")
    _ <- IO.println(s"Joint: ${config.isJoint}")
    _ <- IO.println(s"Cluster size: ${config.voters.size}")
    _ <- IO.println(s"Quorum size: ${config.quorumSize}")
  yield ()

// Expected output:
// Voters: A, B, C, D
// Joint: false
// Cluster size: 4
// Quorum size: 3
```

## Operational Runbook: Removing a Node

### Step 1: Transfer leadership (if removing the leader)

You **cannot** remove the leader from the cluster while it's the leader. Transfer leadership first:

```scala
// If removing node A (the leader), transfer to B first
raftNode.transferLeadership(NodeId("B"))
// Wait for BecomeLeader on node B
```

### Step 2: Initiate removal via joint consensus

```scala
val jointConfig = ClusterConfig(
  voters = Set(NodeId("B"), NodeId("C")),
  oldVoters = Some(Set(NodeId("A"), NodeId("B"), NodeId("C")))
)
raftNode.proposeConfigChange(jointConfig)
```

### Step 3: Shut down the removed node

After the final config `{B, C}` is committed, node A is no longer part of the cluster:

```scala
// On node A: graceful shutdown
raftNode.shutdown()
```

**Important**: Node A may still receive messages from B and C during the transition. It should handle them gracefully — the `PersistHardState` effect with a higher term will cause it to step down.

### Safety considerations when going from 3 → 2 nodes

A 2-node cluster has **no fault tolerance** — losing either node loses quorum. This is generally not recommended for production. Instead:

- Replace: Remove A, add D → maintain 3-node cluster
- Scale down: Remove A, then remove B → go to 1-node mode (no replication)

## Operational Runbook: Replacing a Node

The safest approach is **add-then-remove**:

```
Step 1: Add node D as learner        [A, B, C] + learner D
Step 2: Wait for D to catch up       [A, B, C] + learner D (caught up)
Step 3: Promote D, remove A in one   Joint: [B,C,D] ∩ [A,B,C] → Final: [B,C,D]
Step 4: Shut down node A
```

This can be done as a single joint consensus transition:

```scala
// Replace A with D in one step
val jointConfig = ClusterConfig(
  voters = Set(NodeId("B"), NodeId("C"), NodeId("D")),
  oldVoters = Some(Set(NodeId("A"), NodeId("B"), NodeId("C")))
)
raftNode.proposeConfigChange(jointConfig)
```

The cluster never drops below 3 voters, and fault tolerance is maintained throughout.

## Operational Runbook: Rolling Upgrade

Upgrade every node in the cluster without downtime:

```
Initial: [A(v1), B(v1), C(v1)]

Step 1: Add D(v2) as learner → wait for catch-up
Step 2: Replace A with D: [B(v1), C(v1), D(v2)]
Step 3: Add E(v2) as learner → wait for catch-up
Step 4: Replace B with E: [C(v1), D(v2), E(v2)]
Step 5: Add F(v2) as learner → wait for catch-up
Step 6: Replace C with F: [D(v2), E(v2), F(v2)]
```

At every step, the cluster has 3 voters and can tolerate 1 failure. The upgrade is fully reversible at any point by adding v1 nodes back.

### Automation script

```scala
def rollingUpgrade(
  oldNodes: List[NodeId],
  newNodes: List[NodeId],
  raftNode: RaftNodeApi
): IO[Unit] =
  oldNodes.zip(newNodes).foldLeft(IO.unit) { case (prev, (old, replacement)) =>
    prev *> replaceNode(old, replacement, raftNode)
  }

def replaceNode(
  oldNode: NodeId,
  newNode: NodeId,
  raftNode: RaftNodeApi
): IO[Unit] =
  for
    // Add new node as learner
    config <- raftNode.currentConfig
    _ <- raftNode.proposeConfigChange(
      config.copy(learners = config.learners + newNode)
    )

    // Wait for catch-up
    _ <- waitForCatchUp(newNode, raftNode)

    // Transfer leadership if needed
    leader <- raftNode.currentLeader
    _ <- if leader == oldNode then raftNode.transferLeadership(newNode)
         else IO.unit

    // Replace in joint consensus
    updatedConfig <- raftNode.currentConfig
    newVoters = updatedConfig.voters - oldNode + newNode
    _ <- raftNode.proposeConfigChange(
      ClusterConfig(
        voters = newVoters,
        oldVoters = Some(updatedConfig.voters),
        learners = updatedConfig.learners - newNode
      )
    )

    // Shut down old node
    _ <- IO.println(s"Shutting down ${oldNode.value}")
  yield ()
```

## Failure Scenarios

### Scenario 1: Leader crash during joint consensus

```
t=0   Leader A proposes joint config [A,B,C] ∩ [A,B,C,D]
t=1   Joint entry committed on A and B ── A crashes
t=3   B or C elected (must satisfy both old AND new quorum)
t=4   New leader completes the transition → final config [A,B,C,D]
```

**What happens**: The joint consensus protocol survives leader failure. The committed joint entry is in B's log, so the new leader knows to complete the transition. If the joint entry hadn't been committed, the old config remains — the change is simply aborted.

### Scenario 2: New node fails during catch-up

```
t=0   D joins as learner, starts receiving entries
t=5   D crashes at index 50,000 (leader is at 1,000,000)
t=10  D restarts, resumes from index 50,000
```

**What happens**: Learners are non-voting — D's failure doesn't affect the cluster. D can restart and catch up at its own pace. If D can't catch up (e.g., persistent hardware issue), simply remove the learner:

```scala
val config = currentConfig.copy(learners = currentConfig.learners - newNodeId)
raftNode.proposeConfigChange(config)
```

### Scenario 3: Removing a node that's needed for quorum

```
Configuration: [A, B, C] (quorum = 2)
Remove C: [A, B] (quorum = 2)
B crashes: only A remains — NO QUORUM
```

**What happens**: The cluster is permanently stuck — writes are impossible, reads may be stale. This is why removing nodes is dangerous.

**Prevention**: Always add a replacement before removing a node. The safe flow is:
1. Add D → `[A, B, C, D]` (quorum = 3, can lose 1)
2. Remove C → `[A, B, D]` (quorum = 2, can lose 1)

Never: Remove C → `[A, B]` (quorum = 2, can lose 0)

## Pre-Change Checklist

Before any membership change:

```
□ All current voters are healthy and replicating
□ Leader is stable (no recent elections in the last 60 seconds)
□ The change won't reduce fault tolerance below acceptable levels
□ A rollback plan exists (which node to add back if the change fails)
□ Monitoring is in place for:
  - Election frequency (spike = transition problem)
  - Commit latency (spike = quorum issues)
  - Log replication lag (growing = new node can't keep up)
```

## Production Considerations

### Witness nodes

For cross-datacenter deployments, witnesses provide quorum participation without full data replication:

```scala
val geoConfig = ClusterConfig(
  voters = Set(NodeId("dc1-a"), NodeId("dc1-b"), NodeId("dc2-a")),
  witnesses = Set(NodeId("dc2-witness"))
)
```

Witnesses vote in elections and commit decisions but don't store the full log — they're lightweight quorum participants.

### Configuration entry compaction

Configuration changes are stored as special log entries. During log compaction (snapshotting), the current configuration must be preserved in the snapshot metadata:

```scala
case class SnapshotMetadata(
  lastIncludedIndex: Long,
  lastIncludedTerm: Long,
  currentConfig: ClusterConfig  // the active configuration at snapshot time
)
```

A node restoring from a snapshot must use this configuration, not whatever was in its previous hard state.

### Monitoring membership transitions

| Metric | Normal | Alert |
|--------|--------|-------|
| Joint consensus duration | < 5s | > 30s — new node may be slow |
| Learner catch-up rate | > 1000 entries/s | < 100 entries/s — network bottleneck |
| Elections during transition | 0 | > 0 — check network stability |
| Config changes in last hour | 1-2 | > 5 — possible flapping |

---

*This completes the tutorial series. For API reference, see [Appendix A](../docs/book/appendix-a-quick-reference.md). For the complete book, see the [Table of Contents](../docs/book/README.md).*
