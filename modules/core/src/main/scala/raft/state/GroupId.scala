package raft.state

/** Type-safe identifier for a Raft consensus group.
  *
  * In a Multi-Raft deployment, a single physical node participates in many
  * independent consensus groups — one per data shard, partition, or range. Each
  * group runs its own leader election, log replication, and state machine
  * independently of the others.
  *
  * `GroupId` is an opaque type over `String` that prevents accidental confusion
  * between group identifiers and node identifiers ([[NodeId]]). Like [[Term]]
  * and [[LogIndex]], the opaque type incurs zero runtime cost while providing
  * compile-time safety.
  *
  * @see
  *   [[raft.multigroup.MultiRaftNode]] for the coordinator that manages
  *   multiple groups
  * @see
  *   [[raft.multigroup.GroupEnvelope]] for the wire-level message wrapper
  */
opaque type GroupId = String

/** Companion for [[GroupId]] providing constructors and extensions. */
object GroupId:

  /** Create a [[GroupId]] from a string identifier.
    *
    * @param value
    *   the group identifier string (e.g., "shard-0", "range-42")
    * @return
    *   a type-safe group identifier
    */
  def apply(value: String): GroupId = value

  /** Extension methods for [[GroupId]]. */
  extension (g: GroupId)

    /** Extract the underlying string value. */
    def value: String = g

  /** Ordering instance for [[GroupId]], enabling sorted collections. */
  given Ordering[GroupId] = Ordering.String
