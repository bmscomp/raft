package raft.kernel

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import raft.logic.RaftLogic
import raft.message.RaftMessage.*
import raft.raw.{MemStorage, RawNode}
import raft.state.*
import raft.state.NodeState.*

/** Targeted regression tests for P0 Raft protocol safety & durability
  * invariants:
  *   - Election safety (§5.2): `votedFor` preserved across `PreCandidate`,
  *     same-term `AppendEntries`/`InstallSnapshot`, and leader demotion
  *   - Persistence-before-ACK (§5.3): never pre-persist ACK while
  *     entries/snapshots remain unpersisted in `UnstableLog`
  *   - Snapshot installation safety (§7): never double-apply pre-snapshot
  *     entries alongside `Ready.snapshot`, and discard divergent suffixes
  *   - Linearizable `ReadIndex` round correlation & `LeaseRead` quorum
  *     send-tick anchoring (§6.4)
  */
class P0SafetyRegressionSpec extends AnyFlatSpec with Matchers:

  private val n1 = NodeId("n1")
  private val n2 = NodeId("n2")
  private val n3 = NodeId("n3")
  private val n4 = NodeId("n4")
  private val n5 = NodeId("n5")

  private val cluster3 = ClusterConfig(Set(n1, n2, n3))
  private val cluster5 = ClusterConfig(Set(n1, n2, n3, n4, n5))

  private def mkRaw(
      id: NodeId,
      cluster: ClusterConfig,
      preVote: Boolean,
      leaseRead: Boolean = false,
      hardState: HardState = HardState.Empty,
      entries: Vector[Log] = Vector.empty
  ): (RawNode, MemStorage) =
    val cfg = RaftConfig(
      localId = id,
      preVoteEnabled = preVote,
      checkQuorumEnabled = true,
      leaseReadEnabled = leaseRead,
      leaderStickinessEnabled = false,
      electionTimeoutTicks = 10,
      heartbeatIntervalTicks = 2
    )
    val storage =
      MemStorage.create(cluster, hardState = hardState, entries = entries)
    (RawNode(cfg, storage, initialSeed = 11L), storage)

  "Election Safety (§5.2)" should "preserve votedFor across PreCandidate loss and reject a second vote in the same term" in {
    val (raw, storage) = mkRaw(
      n1,
      cluster3,
      preVote = true,
      hardState = HardState(term = 5L, votedFor = Some(n2))
    )

    // Tick until n1 enters PreCandidate at term 5
    var guard = 0
    while !raw.state.isInstanceOf[PreCandidate] && guard < 30 do
      raw.tick()
      guard += 1
    raw.state shouldBe a[PreCandidate]
    raw.state.asInstanceOf[PreCandidate].votedFor shouldBe Some(n2)

    // Pre-Vote loses -> steps back to Follower(term = 5) while keeping votedFor = Some(n2)
    raw.step(n2, RequestVoteResponse(6L, voteGranted = false, isPreVote = true))
    raw.step(n3, RequestVoteResponse(6L, voteGranted = false, isPreVote = true))
    raw.state shouldBe a[Follower]
    raw.state.asInstanceOf[Follower].votedFor shouldBe Some(n2)
    val r1 = raw.ready()
    storage.persistReady(r1)
    raw.advance(r1)
    storage.hardState.votedFor shouldBe Some(n2)

    // Candidate n3 requests a vote in the same term 5 -> MUST be rejected!
    raw.step(
      n3,
      RequestVoteRequest(
        term = 5L,
        candidateId = n3,
        lastLogIndex = 10L,
        lastLogTerm = 5L,
        isPreVote = false
      )
    )
    val r2 = raw.ready()
    val voteResp = (r2.messages ++ r2.persistedMessages).collectFirst {
      case env if env.to == n3 => env.message.asInstanceOf[RequestVoteResponse]
    }.get
    voteResp.voteGranted shouldBe false
    storage.persistReady(r2)
    raw.advance(r2)
  }

  it should "preserve votedFor when PreCandidate or Candidate receives same-term AppendEntries or InstallSnapshot" in {
    val cfg = RaftConfig(localId = n1, preVoteEnabled = true)
    val preCand = PreCandidate(
      term = 4L,
      preVotesReceived = Set(n1),
      commitIndex = 0L,
      votedFor = Some(n2)
    )

    val aeTrans = RaftLogic.onMessage(
      preCand,
      AppendEntriesRequest(4L, n2, 0L, 0L, Seq.empty, 0L),
      cfg,
      lastLogIndex = 0L,
      lastLogTerm = 0L,
      clusterSize = 3
    )
    aeTrans.state.asInstanceOf[Follower].votedFor shouldBe Some(n2)

    val snapTrans = RaftLogic.onMessage(
      preCand,
      InstallSnapshotRequest(4L, n2, 10L, 4L, 0L, Bytes.empty, done = true),
      cfg,
      lastLogIndex = 0L,
      lastLogTerm = 0L,
      clusterSize = 3
    )
    snapTrans.state.asInstanceOf[Follower].votedFor shouldBe Some(n2)

    val cand = Candidate(term = 4L, votesReceived = Set(n1), commitIndex = 0L)
    val candSnapTrans = RaftLogic.onMessage(
      cand,
      InstallSnapshotRequest(4L, n2, 10L, 4L, 0L, Bytes.empty, done = true),
      cfg,
      lastLogIndex = 0L,
      lastLogTerm = 0L,
      clusterSize = 3
    )
    candSnapTrans.state.asInstanceOf[Follower].votedFor shouldBe Some(n1)
  }

  it should "preserve self-vote when Leader is demoted to Witness in its own term" in {
    val (raw, storage) = mkRaw(n1, cluster3, preVote = false)
    for _ <- 1 to 25 do raw.tick()
    val r1 = raw.ready()
    storage.persistReady(r1)
    raw.advance(r1)

    raw.step(n2, RequestVoteResponse(1L, voteGranted = true))
    val r2 = raw.ready()
    storage.persistReady(r2)
    raw.advance(r2)
    raw.state.isLeader shouldBe true

    // Commit term-1 NoOp
    raw.step(n2, AppendEntriesResponse(1L, success = true, matchIndex = 1L))
    val r3 = raw.ready()
    storage.persistReady(r3)
    raw.advance(r3)

    // Demote n1 from Voter to Witness (still a voting role!)
    val target = Map(
      n1 -> NodeRole.Witness,
      n2 -> NodeRole.Voter,
      n3 -> NodeRole.Voter
    )
    raw.proposeConfChange(target).isRight shouldBe true
    val r4 = raw.ready()
    storage.persistReady(r4)
    raw.advance(r4)

    // Commit C_old,new (index 2) -> leader appends C_new (index 3)
    raw.step(n2, AppendEntriesResponse(1L, success = true, matchIndex = 2L))
    val r5 = raw.ready()
    storage.persistReady(r5)
    raw.advance(r5)

    // Commit C_new (index 3) -> n1 steps down from Leader in term 1
    raw.step(n2, AppendEntriesResponse(1L, success = true, matchIndex = 3L))
    val r6 = raw.ready()
    storage.persistReady(r6)
    raw.advance(r6)

    raw.state shouldBe a[Follower]
    raw.state.asInstanceOf[Follower].term shouldBe 1L
    raw.state.asInstanceOf[Follower].votedFor shouldBe Some(n1)
    storage.hardState.votedFor shouldBe Some(n1)
  }

  "Persistence-Before-ACK (§5.3)" should "never place AppendEntriesResponse in pre-persist Ready.messages while entries are unpersisted" in {
    val (raw, storage) = mkRaw(n1, cluster3, preVote = false)
    val entry1 = Log.command(1L, 1L, "cmd-1".getBytes)

    // First AppendEntries puts entry1 into unstable.entries
    raw.step(n2, AppendEntriesRequest(1L, n2, 0L, 0L, Seq(entry1), 0L))
    // Retransmitted duplicate arrives BEFORE ready()/persistReady()!
    raw.step(n2, AppendEntriesRequest(1L, n2, 0L, 0L, Seq(entry1), 0L))

    val rd = raw.ready()
    rd.entries shouldBe Vector(entry1)
    // NEITHER response may be sent before entry1 is durably persisted
    rd.messages.filter(
      _.message.isInstanceOf[AppendEntriesResponse]
    ) shouldBe empty
    rd.persistedMessages.count(
      _.message.isInstanceOf[AppendEntriesResponse]
    ) shouldBe 2
    storage.persistReady(rd)
    raw.advance(rd)
  }

  "InstallSnapshot safety (§7)" should "not double-apply pre-snapshot entries in Ready.committedEntries and must discard divergent suffix" in {
    val divergentEntries = Vector(
      Log.command(1L, 1L, "old-1".getBytes),
      Log.command(2L, 1L, "old-2".getBytes),
      Log.command(3L, 1L, "old-3".getBytes),
      Log.command(4L, 1L, "divergent-4".getBytes),
      Log.command(5L, 1L, "divergent-5".getBytes)
    )
    val (raw, storage) = mkRaw(
      n1,
      cluster3,
      preVote = false,
      hardState = HardState(term = 1L, votedFor = None, commitIndex = 0L),
      entries = divergentEntries
    )

    // Leader at term 2 sends snapshot at (index=3, term=2) which conflicts with local (3, term=1)
    val snapPayload =
      SnapshotMetadata.encodePayloadBytes(
        Some(cluster3),
        Bytes.fromUtf8("snap-state-3")
      )
    raw.step(
      n2,
      new InstallSnapshotRequest(
        term = 2L,
        leaderId = n2,
        lastIncludedIndex = 3L,
        lastIncludedTerm = 2L,
        offset = 0L,
        payload = snapPayload,
        done = true
      )
    )

    val rd = raw.ready()
    rd.snapshot shouldBe defined
    rd.snapshot.get.lastIncludedIndex shouldBe 3L
    // MUST NOT emit stale entries <= 3 alongside the snapshot!
    rd.committedEntries shouldBe empty
    // Response MUST be in persistedMessages, not pre-persist messages
    rd.messages.filter(
      _.message.isInstanceOf[InstallSnapshotResponse]
    ) shouldBe empty
    rd.persistedMessages.count(
      _.message.isInstanceOf[InstallSnapshotResponse]
    ) shouldBe 1

    storage.persistReady(rd)
    val light = raw.advance(rd)
    light.committedEntries shouldBe empty
    // Divergent suffix (4..5@term1) MUST be discarded per §7 Rule 7!
    storage.lastIndex shouldBe 3L
    storage.entries(1L, 10L) shouldBe empty
    storage.term(4L) shouldBe None
  }

  "ReadIndex & LeaseRead linearizability (§6.4)" should "reject stale pre-request heartbeat ACKs for ReadIndex and anchor LeaseRead to quorum send tick" in {
    val (raw, storage) = mkRaw(n1, cluster5, preVote = false, leaseRead = true)
    for _ <- 1 to 25 do raw.tick()
    val r1 = raw.ready()
    storage.persistReady(r1)
    raw.advance(r1)

    // Win election in 5-node cluster with votes from n2 and n3
    raw.step(n2, RequestVoteResponse(1L, voteGranted = true))
    raw.step(n3, RequestVoteResponse(1L, voteGranted = true))
    val r2 = raw.ready()
    storage.persistReady(r2)
    raw.advance(r2)
    raw.state.isLeader shouldBe true

    val round1 = r2.messages.collectFirst {
      case env if env.to == n2 =>
        env.message match
          case ae: AppendEntriesRequest => ae.heartbeatRound
          case _                        => 0L
    }.get
    round1 should be > 0L

    // Quorum {n1, n2, n3} ACKs round1 (sent at tick T0) -> commits NoOp and establishes lease [T0, T0 + 6)
    raw.step(
      n2,
      AppendEntriesResponse(
        1L,
        success = true,
        matchIndex = 1L,
        heartbeatRound = round1
      )
    )
    raw.step(
      n3,
      AppendEntriesResponse(
        1L,
        success = true,
        matchIndex = 1L,
        heartbeatRound = round1
      )
    )
    val r3 = raw.ready()
    storage.persistReady(r3)
    raw.advance(r3)

    // Advance past the initial lease window (leaseTicks = 10 * 2 / 3 = 6) so lease expires
    for _ <- 1 to 7 do raw.tick()
    val rHb = raw.ready()
    storage.persistReady(rHb)
    raw.advance(rHb)

    // Only a MINORITY peer (n4) responds to the newer heartbeat -> MUST NOT extend lease!
    val newerRound = rHb.messages.collectFirst {
      case env if env.to == n4 =>
        env.message match
          case ae: AppendEntriesRequest => ae.heartbeatRound
          case _                        => 0L
    }.get
    raw.step(
      n4,
      AppendEntriesResponse(
        1L,
        success = true,
        matchIndex = 1L,
        heartbeatRound = newerRound
      )
    )
    val rMin = raw.ready()
    storage.persistReady(rMin)
    raw.advance(rMin)

    // Issuing readIndex now MUST NOT serve locally (lease expired, minority cannot extend it)
    raw.readIndex("ctx-after-expiry") shouldBe Right(())
    val rRead = raw.ready()
    rRead.readStates shouldBe empty
    val readRound = rRead.messages.collectFirst {
      case env if env.to == n2 =>
        env.message match
          case ae: AppendEntriesRequest => ae.heartbeatRound
          case _                        => 0L
    }.get
    readRound should be > newerRound
    storage.persistReady(rRead)
    raw.advance(rRead)

    // Stale pre-request responses (round1) from n2 and n3 MUST NOT confirm ReadIndex!
    raw.step(
      n2,
      AppendEntriesResponse(
        1L,
        success = true,
        matchIndex = 1L,
        heartbeatRound = round1
      )
    )
    raw.step(
      n3,
      AppendEntriesResponse(
        1L,
        success = true,
        matchIndex = 1L,
        heartbeatRound = round1
      )
    )
    val rStale = raw.ready()
    rStale.readStates shouldBe empty
    storage.persistReady(rStale)
    raw.advance(rStale)

    // Fresh post-request responses (readRound) from n2 and n3 MUST confirm ReadIndex!
    raw.step(
      n2,
      AppendEntriesResponse(
        1L,
        success = true,
        matchIndex = 1L,
        heartbeatRound = readRound
      )
    )
    raw.step(
      n3,
      AppendEntriesResponse(
        1L,
        success = true,
        matchIndex = 1L,
        heartbeatRound = readRound
      )
    )
    val rFresh = raw.ready()
    rFresh.readStates should contain(ReadState(1L, "ctx-after-expiry"))
    storage.persistReady(rFresh)
    raw.advance(rFresh)
  }

  "P1 Wire Security & Validation" should "reject deeply nested JSON without StackOverflowError and reject negative coordinates" in {
    import raft.codec.JsonCodec
    import raft.message.CodecError

    val deepNested = "[" * 500 + "]" * 500
    val depthRes = JsonCodec.decode(deepNested)
    depthRes.isLeft shouldBe true
    depthRes.left.toOption.get shouldBe a[CodecError.ParseError]

    val negTerm =
      """{"type":"RV_REQ","v":1,"term":-1,"candidate":"n1","lastIndex":0,"lastTerm":0}"""
    JsonCodec.decode(negTerm).left.toOption.get shouldBe a[
      CodecError.InvalidField
    ]

    val negOffset =
      """{"type":"IS_REQ","v":1,"term":1,"leader":"n1","lastIndex":5,"lastTerm":1,"offset":-4,"data":"","done":true}"""
    JsonCodec.decode(negOffset).left.toOption.get shouldBe a[
      CodecError.InvalidField
    ]
  }

  it should "ignore remote TimerTick signals in ConsensusKernel.step" in {
    val (raw, _) = mkRaw(n1, cluster3, preVote = false)
    raw.state shouldBe a[Follower]
    // Remote peer n2 attempting to inject ElectionTimeout MUST be ignored
    raw.step(n2, ElectionTimeout)
    raw.state shouldBe a[Follower]
    // Local node n1 triggering ElectionTimeout is still accepted
    raw.step(n1, ElectionTimeout)
    raw.state shouldBe a[Candidate]
  }
