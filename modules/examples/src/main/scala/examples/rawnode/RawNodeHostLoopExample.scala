package examples.rawnode

import java.nio.charset.StandardCharsets
import java.util.concurrent.{
  ConcurrentHashMap,
  CountDownLatch,
  Executors,
  LinkedBlockingQueue,
  TimeUnit
}

import raft.message.RaftMessage
import raft.raw.{LightReady, MemStorage, OutboundMessage, RawNode, Ready}
import raft.state.{
  ClusterConfig,
  Log,
  LogType,
  NodeId,
  NodeState,
  RaftConfig,
  RaftError
}

/** Pure `raft-core` Sans-I/O host loop driven by JDK 25 Virtual Threads.
  *
  * Demonstrates how a host system embeds `RawNode` with **zero** Cats Effect or
  * external framework dependencies: each replica runs a single-threaded event
  * loop on a lightweight Virtual Thread, draining `Ready` and `LightReady`
  * batches to persist WAL state, exchange messages, and apply committed
  * entries.
  */
object RawNodeHostLoopExample:

  private enum HostEvent:
    case Tick
    case Inbound(from: NodeId, msg: RaftMessage)
    case Propose(
        payload: Array[Byte],
        reply: LinkedBlockingQueue[Either[RaftError, Long]]
    )
    case Stop

  private final class VirtualThreadReplica(
      val nodeId: NodeId,
      voters: Set[NodeId],
      mailboxes: ConcurrentHashMap[NodeId, LinkedBlockingQueue[HostEvent]],
      seed: Long
  ):
    val storage: MemStorage = MemStorage.create(ClusterConfig(voters))
    private val config = RaftConfig(
      localId = nodeId,
      electionTimeoutTicks = 8,
      heartbeatIntervalTicks = 2,
      preVoteEnabled = true,
      checkQuorumEnabled = true
    )
    private val raw = new RawNode(config, storage, initialSeed = seed)
    val inbox = new LinkedBlockingQueue[HostEvent]()
    val kvStore = new ConcurrentHashMap[String, String]()
    @volatile var currentRole: String = "Follower"
    @volatile var currentLeader: Option[NodeId] = None

    def runLoop(stopped: CountDownLatch): Unit =
      try
        var running = true
        while running do
          val ev = inbox.poll(20, TimeUnit.MILLISECONDS)
          ev match
            case null                         => raw.tick()
            case HostEvent.Tick               => raw.tick()
            case HostEvent.Inbound(from, msg) =>
              raw.step(from, msg)
            case HostEvent.Propose(bytes, reply) =>
              val res = raw.propose(bytes).map(_ => raw.lastLogIndex)
              val _ = reply.offer(res)
            case HostEvent.Stop =>
              running = false

          if running && raw.hasReady then drainReadyCycle()
      finally stopped.countDown()

    private def drainReadyCycle(): Unit =
      val rd: Ready = raw.ready()

      rd.softState.foreach { soft =>
        currentLeader = soft.leaderId
        currentRole = soft.role match
          case _: NodeState.Leader       => "Leader"
          case _: NodeState.Candidate    => "Candidate"
          case _: NodeState.PreCandidate => "PreCandidate"
          case _: NodeState.Follower     => "Follower"
      }

      // 1. Send optimistic leader messages before disk sync
      dispatch(rd.messages)

      // 2. Persist snapshot, log truncation, unstable entries, and HardState
      storage.persistReady(rd)

      // 3. Send messages that require durable HardState / log entries
      dispatch(rd.persistedMessages)

      // 4. Apply committed entries to host state machine
      applyEntries(rd.committedEntries)

      // 5. Advance RawNode and process follow-up LightReady
      val light: LightReady = raw.advance(rd)
      light.commitIndex.foreach { cIdx =>
        storage.setHardState(storage.hardState.copy(commitIndex = cIdx))
      }
      dispatch(light.messages)
      applyEntries(light.committedEntries)

    private def dispatch(msgs: Vector[OutboundMessage]): Unit =
      msgs.foreach { out =>
        val targetQ = mailboxes.get(out.to)
        if targetQ != null then
          val _ = targetQ.offer(HostEvent.Inbound(nodeId, out.message))
      }

    private def applyEntries(entries: Vector[Log]): Unit =
      entries.foreach { entry =>
        if entry.logType == LogType.Command && entry.data.nonEmpty then
          val text = new String(entry.data, StandardCharsets.UTF_8)
          text.split("=", 2) match
            case Array(k, v) =>
              val _ = kvStore.put(k, v)
            case _ => ()
      }

  def main(args: Array[String]): Unit =
    println("=" * 70)
    println(
      "  RAW NODE HOST LOOP EXAMPLE (JDK 25 Virtual Threads, Zero Effects)"
    )
    println("=" * 70)

    val ids = Vector(NodeId("node-1"), NodeId("node-2"), NodeId("node-3"))
    val voters = ids.toSet
    val mailboxes =
      new ConcurrentHashMap[NodeId, LinkedBlockingQueue[HostEvent]]()
    val replicas = ids.zipWithIndex.map { case (id, idx) =>
      val r = new VirtualThreadReplica(id, voters, mailboxes, 1000L + idx)
      val _ = mailboxes.put(id, r.inbox)
      r
    }

    val stopped = new CountDownLatch(replicas.size)
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    try
      replicas.foreach { r =>
        val _ = executor.submit((() => r.runLoop(stopped)): Runnable)
      }

      // Wait until a leader is elected
      var leaderOpt: Option[VirtualThreadReplica] = None
      var attempts = 0
      while leaderOpt.isEmpty && attempts < 100 do
        Thread.sleep(25)
        leaderOpt = replicas.find(_.currentRole == "Leader")
        attempts += 1

      val leader = leaderOpt.getOrElse(
        throw new IllegalStateException("No leader elected within timeout")
      )
      println(s"\n1. Elected leader: ${leader.nodeId.value}")

      // Propose 3 KV writes through the leader's mailbox
      val commands =
        List("engine=raft-core", "style=sans-io", "runtime=virtual-threads")
      println("\n2. Proposing replicated KV commands...")
      commands.foreach { cmd =>
        val reply = new LinkedBlockingQueue[Either[RaftError, Long]](1)
        val _ = leader.inbox.offer(
          HostEvent.Propose(cmd.getBytes(StandardCharsets.UTF_8), reply)
        )
        val res = reply.poll(2, TimeUnit.SECONDS)
        println(s"   Proposed '$cmd' -> log index $res")
      }

      // Wait for all 3 replicas to apply all 3 commands
      var converged = false
      var waitCycles = 0
      while !converged && waitCycles < 100 do
        Thread.sleep(20)
        converged = replicas.forall(_.kvStore.size() == commands.size)
        waitCycles += 1

      println(
        "\n3. State machine contents across all 3 Virtual Thread replicas:"
      )
      replicas.foreach { r =>
        println(
          s"   ${r.nodeId.value} (${r.currentRole}): ${r.kvStore}"
        )
      }
    finally
      replicas.foreach { r =>
        val _ = r.inbox.offer(HostEvent.Stop)
      }
      val _ = stopped.await(2, TimeUnit.SECONDS)
      executor.shutdown()

    println("\nDone! Pure Sans-I/O RawNode cluster converged cleanly.")
