package raft.state

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import scala.annotation.tailrec

/** Role a node can play within the RAFT cluster. */
enum NodeRole:
  /** Full voting member storing the complete log and campaigning in elections.
    */
  case Voter

  /** Non-voting member catching up via log replication before promotion. */
  case Learner

  /** Voting tie-breaker participating in elections and commit quorums with
    * stripped command payloads (never campaigns to become Leader).
    */
  case Witness

/** Cluster configuration for single-config and Joint Consensus (§6,
  * `ConfChangeV2`) membership management.
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

  /** Pure, non-throwing variant of [[beginJointConsensus]]. */
  def beginJointConsensusValidated(
      newConfig: Map[NodeId, NodeRole]
  ): Validated[ConfigError, ClusterConfig] =
    Validated
      .check(
        newConfig.values.exists(ClusterConfig.isVotingRole),
        ConfigError.EmptyVoterSet("Target Joint Consensus configuration")
      )
      .map(_ => beginJointConsensus(newConfig))

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

  /** True iff `nodeId` is a full data-bearing `Voter` eligible to campaign for
    * leadership (`Learner` and `Witness` nodes never campaign).
    */
  def canCampaign(nodeId: NodeId): Boolean =
    members.get(nodeId).contains(NodeRole.Voter)

  def isLearner(nodeId: NodeId): Boolean =
    members.get(nodeId).contains(NodeRole.Learner)

  def isWitness(nodeId: NodeId): Boolean =
    members.get(nodeId).contains(NodeRole.Witness)

  def contains(nodeId: NodeId): Boolean = members.contains(nodeId)

  /** Majority threshold for `newVoters` (or `voters` in single-config mode). */
  def majoritySize: Int = (newVoters.size / 2) + 1

  /** True iff `respondingNodes` forms a valid Raft quorum (§5.2, §6). */
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
  private val BinaryMagic: Int = 0x52434647 // "RCFG"
  private val BinaryVersion: Byte = 2

  private[state] def isVotingRole(role: NodeRole): Boolean =
    role == NodeRole.Voter || role == NodeRole.Witness

  private[state] def votingNodesOf(m: Map[NodeId, NodeRole]): Set[NodeId] =
    m.collect { case (id, role) if isVotingRole(role) => id }.toSet

  def single(nodeId: NodeId): ClusterConfig =
    ClusterConfig(Map(nodeId -> NodeRole.Voter))

  def apply(voters: Set[NodeId]): ClusterConfig =
    ClusterConfig(voters.map(_ -> NodeRole.Voter).toMap)

  /** Pure smart constructor validating all `NodeId`s and voter presence without
    * throwing exceptions.
    */
  def validate(
      members: Map[NodeId, NodeRole],
      jointConfig: Option[Map[NodeId, NodeRole]] = None,
      pendingConfig: Option[Map[NodeId, NodeRole]] = None
  ): Validated[ConfigError, ClusterConfig] =
    val allIds =
      members.keySet ++
        jointConfig.map(_.keySet).getOrElse(Set.empty) ++
        pendingConfig.map(_.keySet).getOrElse(Set.empty)
    val idChecks = allIds.toVector.foldLeft(
      Validated.valid(()): Validated[ConfigError, Unit]
    ) { (acc, id) =>
      Validated.mapN(acc, NodeId.validate(id.value))((_, _) => ())
    }
    val voterCheck = Validated.check(
      members.values.exists(isVotingRole),
      ConfigError.EmptyVoterSet("ClusterConfig.members")
    )
    val pendingCheck = pendingConfig match
      case Some(p) =>
        Validated.check(
          p.values.exists(isVotingRole),
          ConfigError.EmptyVoterSet("ClusterConfig.pendingConfig")
        )
      case None => Validated.valid(())

    Validated
      .mapN(Validated.mapN(idChecks, voterCheck)((_, _) => ()), pendingCheck)(
        (_, _) => ClusterConfig(members, jointConfig, pendingConfig)
      )

  /** Encode a [[ClusterConfig]] into a length-prefixed, CRC32-verified `"RCFG"`
    * v2 binary [[Bytes]] frame (immune to `'|'`, `','`, `':'`, or arbitrary
    * UTF-8 characters inside `NodeId`).
    */
  def encodeBytes(config: ClusterConfig): Bytes =
    def serializeMap(m: Map[NodeId, NodeRole]): Array[Byte] =
      val sorted = m.toVector.sortBy(_._1.value)
      val encodedIds = sorted.map { case (id, role) =>
        (id.value.getBytes(StandardCharsets.UTF_8), role.ordinal.toByte)
      }
      val byteSize =
        4 + encodedIds.foldLeft(0)((acc, p) => acc + 4 + p._1.length + 1)
      val buf = ByteBuffer.allocate(byteSize)
      val _ = buf.putInt(encodedIds.size)
      encodedIds.foreach { case (idBytes, roleByte) =>
        val _ = buf.putInt(idBytes.length)
        val _ = buf.put(idBytes)
        val _ = buf.put(roleByte)
      }
      buf.array()

    val curBytes = serializeMap(config.members)
    val oldBytesOpt = config.jointConfig.map(serializeMap)
    val pendBytesOpt = config.pendingConfig.map(serializeMap)

    val payloadLen =
      4 + 1 + curBytes.length +
        1 + oldBytesOpt.map(_.length).getOrElse(0) +
        1 + pendBytesOpt.map(_.length).getOrElse(0)

    val out = ByteBuffer.allocate(payloadLen + 4)
    val _ = out.putInt(BinaryMagic)
    val _ = out.put(BinaryVersion)
    val _ = out.put(curBytes)
    oldBytesOpt match
      case Some(b) =>
        val _ = out.put(1.toByte)
        val _ = out.put(b)
      case None =>
        val _ = out.put(0.toByte)
    pendBytesOpt match
      case Some(b) =>
        val _ = out.put(1.toByte)
        val _ = out.put(b)
      case None =>
        val _ = out.put(0.toByte)

    val raw = out.array()
    val crc = new CRC32()
    crc.update(raw, 0, payloadLen)
    val _ = out.putInt(payloadLen, crc.getValue.toInt)
    Bytes.unsafeWrap(raw)

  /** Encode a [[ClusterConfig]] into deterministic bytes for
    * `LogType.Configuration` entries.
    */
  def encode(config: ClusterConfig): Array[Byte] =
    encodeBytes(config).toArray

  /** Decode a [[ClusterConfig]] from [[Bytes]], supporting both `"RCFG"` v2
    * length-prefixed binary frames and legacy `"v1|..."` text frames.
    */
  def decodeBytes(bytes: Bytes): Either[String, ClusterConfig] =
    if bytes.length >= 10 then
      val buf = bytes.asReadOnlyByteBuffer
      if buf.getInt(0) == BinaryMagic then decodeBinaryV2(bytes, buf)
      else decodeLegacyV1(bytes.utf8String)
    else decodeLegacyV1(bytes.utf8String)

  /** Decode a [[ClusterConfig]] from `LogType.Configuration` entry bytes. */
  def decode(bytes: Array[Byte]): Either[String, ClusterConfig] =
    decodeBytes(Bytes.copyFrom(bytes))

  private def decodeBinaryV2(
      bytes: Bytes,
      buf: ByteBuffer
  ): Either[String, ClusterConfig] =
    val _ = buf.getInt() // magic
    val version = buf.get()
    if version != BinaryVersion then
      Left(s"Unsupported RCFG binary version: $version")
    else if bytes.length < 15 then
      Left(s"Truncated RCFG frame (${bytes.length} bytes)")
    else
      val payloadLen = bytes.length - 4
      val crc = new CRC32()
      crc.update(bytes.slice(0, payloadLen).asReadOnlyByteBuffer)
      val expectedCrc = crc.getValue.toInt
      val storedCrc = buf.getInt(payloadLen)
      if expectedCrc != storedCrc then
        Left(
          f"RCFG CRC32 mismatch: stored=0x$storedCrc%08x, computed=0x$expectedCrc%08x"
        )
      else
        val limitBuf = bytes.slice(5, payloadLen).asReadOnlyByteBuffer

        @tailrec
        def readEntries(
            remaining: Int,
            acc: Map[NodeId, NodeRole]
        ): Either[String, Map[NodeId, NodeRole]] =
          if remaining <= 0 then Right(acc)
          else if limitBuf.remaining() < 5 then
            Left("Truncated RCFG member entry header")
          else
            val idLen = limitBuf.getInt()
            if idLen <= 0 || idLen > limitBuf.remaining() - 1 then
              Left(s"Invalid RCFG NodeId byte length: $idLen")
            else
              val idBytes = new Array[Byte](idLen)
              val _ = limitBuf.get(idBytes)
              val roleOrd = limitBuf.get().toInt
              if roleOrd < 0 || roleOrd >= NodeRole.values.length then
                Left(s"Invalid RCFG role ordinal: $roleOrd")
              else
                val id = NodeId(new String(idBytes, StandardCharsets.UTF_8))
                readEntries(
                  remaining - 1,
                  acc.updated(id, NodeRole.fromOrdinal(roleOrd))
                )

        def readMap(): Either[String, Map[NodeId, NodeRole]] =
          if limitBuf.remaining() < 4 then Left("Truncated RCFG map count")
          else
            val count = limitBuf.getInt()
            if count < 0 then Left(s"Negative RCFG map count: $count")
            else readEntries(count, Map.empty)

        def readOptMap(): Either[String, Option[Map[NodeId, NodeRole]]] =
          if !limitBuf.hasRemaining then
            Left("Truncated RCFG optional map flag")
          else
            limitBuf.get() match
              case 0 => Right(None)
              case 1 => readMap().map(Some(_))
              case b => Left(s"Invalid RCFG optional map flag: $b")

        for
          members <- readMap()
          oldOpt <- readOptMap()
          pendingOpt <- readOptMap()
          _ <-
            if limitBuf.hasRemaining then
              Left(s"Unexpected trailing RCFG bytes: ${limitBuf.remaining()}")
            else Right(())
          cfg <-
            if members.values.exists(isVotingRole) then
              Right(ClusterConfig(members, oldOpt, pendingOpt))
            else Left("Decoded ClusterConfig contains no voting members")
        yield cfg

  private def decodeLegacyV1(raw: String): Either[String, ClusterConfig] =
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
