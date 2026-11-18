package examples.advanced

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

import cats.effect.*
import cats.syntax.all.*
import fs2.{Chunk, Stream}

import raft.impl.DefaultTimerService
import raft.multigroup.{
  CoalescedHeartbeatConfig,
  CoalescerStats,
  GroupConfig,
  MultiGroupTransport,
  MultiRaftNode
}
import raft.spi.{CompactionPolicy, StateMachine, TransportConfig}
import raft.state.*
import raft.state.NodeState.Leader
import raft.storage.{
  StorageBackedLogStore,
  StorageBackedSnapshotStore,
  StorageBackedStableStore
}
import raft.storage.mmap.MmapWalEngine
import raft.transport.grpc.{GrpcConfig, GrpcTransport}

/** Flagship Production Stack Example: Multi-Shard Replicated Bank Ledger.
  *
  * Combines every production module and capability in the library into a
  * cohesive, crash-resilient distributed transactional system:
  *   - '''Multi-Raft Sharding''' (`MultiRaftNode`): 1 `tx-coord` group + `N`
  *     account ledger shards multiplexed over 3 physical hosts.
  *   - '''Single-Port Binary gRPC''' (`GrpcTransport.multiGroupResource`,
  *     `ProtobufMultiGroupCodec`, `CoalescedHeartbeatConfig`).
  *   - '''Off-Heap FFM WAL & Snapshots''' (`MmapWalEngine` + `CompactionPolicy`
  *     log compaction + `RSNP` snapshot streaming).
  *   - '''High-Throughput Replication''' (`BatchConfig` group commit,
  *     `proposeBatchAndCommitToGroup`, and `PipelineConfig` `Inflights`).
  *   - '''Exactly-Once Client Sessions''': `(clientId, seqNum)` deduplication
  *     persisted across snapshots so retries never double-debit accounts.
  *   - '''Cross-Shard Two-Phase Commit (2PC)''': Raft-backed transaction
  *     coordinator (`tx-coord`) + idempotent `PrepareDebit` / `PrepareCredit`
  *     escrow holds + `Commit2PC` / `Abort2PC` resolution + deterministic
  *     `recoverInFlightTransactions` crash recovery.
  *   - '''Linearizable Audits & Leader Rebalancing''': `leaseReadFromGroup`
  *     zero-RTT fast path with `readIndexFromGroup` fallback, plus
  *     `rebalanceLeadership` across physical hosts.
  */
object ShardedBankLedgerExample extends IOApp.Simple:

  val CoordGroup: GroupId = GroupId("tx-coord")

  def ledgerShardId(idx: Int): GroupId = GroupId(s"ledger-shard-$idx")

  def shardForAccount(accountId: String, shardCount: Int): GroupId =
    val bucket = (accountId.hashCode & Int.MaxValue) % shardCount
    ledgerShardId(bucket)

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Account Shard Commands, Responses & Binary Snapshot Codec
  // ─────────────────────────────────────────────────────────────────────────

  enum LedgerCommand:
    case OpenAccount(
        clientId: String,
        seqNum: Long,
        accountId: String,
        initialBalance: Long
    )
    case Deposit(
        clientId: String,
        seqNum: Long,
        accountId: String,
        amount: Long
    )
    case Withdraw(
        clientId: String,
        seqNum: Long,
        accountId: String,
        amount: Long
    )
    case SameShardTransfer(
        clientId: String,
        seqNum: Long,
        fromAccount: String,
        toAccount: String,
        amount: Long
    )
    case PrepareDebit(txId: String, accountId: String, amount: Long)
    case PrepareCredit(txId: String, accountId: String, amount: Long)
    case Commit2PC(txId: String)
    case Abort2PC(txId: String)

  enum LedgerResponse:
    case AccountOpened(accountId: String, balance: Long)
    case BalanceUpdated(accountId: String, newBalance: Long)
    case Transferred(
        txId: String,
        fromBalance: Long,
        toBalance: Long
    )
    case Prepared(txId: String)
    case Committed2PC(txId: String)
    case Aborted2PC(txId: String)
    case Rejected(reason: String)

  object WireCodec:
    def putStr(buf: ByteBuffer, s: String): Unit =
      val bytes = s.getBytes(StandardCharsets.UTF_8)
      val _ = buf.putInt(bytes.length)
      val _ = buf.put(bytes)

    def getStr(buf: ByteBuffer): String =
      val len = buf.getInt()
      val bytes = new Array[Byte](len)
      val _ = buf.get(bytes)
      new String(bytes, StandardCharsets.UTF_8)

    def strSize(s: String): Int = 4 + s.getBytes(StandardCharsets.UTF_8).length

    def encodeCommand(cmd: LedgerCommand): Array[Byte] = cmd match
      case LedgerCommand.OpenAccount(cid, seq, acct, init) =>
        val b = ByteBuffer.allocate(1 + strSize(cid) + 8 + strSize(acct) + 8)
        val _ = b.put(1.toByte)
        putStr(b, cid); val _ = b.putLong(seq); putStr(b, acct)
        val _ = b.putLong(init)
        b.array()
      case LedgerCommand.Deposit(cid, seq, acct, amt) =>
        val b = ByteBuffer.allocate(1 + strSize(cid) + 8 + strSize(acct) + 8)
        val _ = b.put(2.toByte)
        putStr(b, cid); val _ = b.putLong(seq); putStr(b, acct)
        val _ = b.putLong(amt)
        b.array()
      case LedgerCommand.Withdraw(cid, seq, acct, amt) =>
        val b = ByteBuffer.allocate(1 + strSize(cid) + 8 + strSize(acct) + 8)
        val _ = b.put(3.toByte)
        putStr(b, cid); val _ = b.putLong(seq); putStr(b, acct)
        val _ = b.putLong(amt)
        b.array()
      case LedgerCommand.SameShardTransfer(cid, seq, from, to, amt) =>
        val b = ByteBuffer.allocate(
          1 + strSize(cid) + 8 + strSize(from) + strSize(to) + 8
        )
        val _ = b.put(4.toByte)
        putStr(b, cid); val _ = b.putLong(seq); putStr(b, from); putStr(b, to)
        val _ = b.putLong(amt)
        b.array()
      case LedgerCommand.PrepareDebit(txId, acct, amt) =>
        val b = ByteBuffer.allocate(1 + strSize(txId) + strSize(acct) + 8)
        val _ = b.put(5.toByte)
        putStr(b, txId); putStr(b, acct); val _ = b.putLong(amt)
        b.array()
      case LedgerCommand.PrepareCredit(txId, acct, amt) =>
        val b = ByteBuffer.allocate(1 + strSize(txId) + strSize(acct) + 8)
        val _ = b.put(6.toByte)
        putStr(b, txId); putStr(b, acct); val _ = b.putLong(amt)
        b.array()
      case LedgerCommand.Commit2PC(txId) =>
        val b = ByteBuffer.allocate(1 + strSize(txId))
        val _ = b.put(7.toByte)
        putStr(b, txId)
        b.array()
      case LedgerCommand.Abort2PC(txId) =>
        val b = ByteBuffer.allocate(1 + strSize(txId))
        val _ = b.put(8.toByte)
        putStr(b, txId)
        b.array()

    def decodeCommand(bytes: Array[Byte]): Either[String, LedgerCommand] =
      try
        val b = ByteBuffer.wrap(bytes)
        b.get().toInt match
          case 1 =>
            Right(
              LedgerCommand.OpenAccount(
                getStr(b),
                b.getLong(),
                getStr(b),
                b.getLong()
              )
            )
          case 2 =>
            Right(
              LedgerCommand.Deposit(
                getStr(b),
                b.getLong(),
                getStr(b),
                b.getLong()
              )
            )
          case 3 =>
            Right(
              LedgerCommand.Withdraw(
                getStr(b),
                b.getLong(),
                getStr(b),
                b.getLong()
              )
            )
          case 4 =>
            Right(
              LedgerCommand.SameShardTransfer(
                getStr(b),
                b.getLong(),
                getStr(b),
                getStr(b),
                b.getLong()
              )
            )
          case 5 =>
            Right(LedgerCommand.PrepareDebit(getStr(b), getStr(b), b.getLong()))
          case 6 =>
            Right(
              LedgerCommand.PrepareCredit(getStr(b), getStr(b), b.getLong())
            )
          case 7 => Right(LedgerCommand.Commit2PC(getStr(b)))
          case 8 => Right(LedgerCommand.Abort2PC(getStr(b)))
          case t => Left(s"Unknown LedgerCommand tag: $t")
      catch case e: Exception => Left(s"Decode error: ${e.getMessage}")

    def encodeResponse(resp: LedgerResponse): Array[Byte] = resp match
      case LedgerResponse.AccountOpened(acct, bal) =>
        val b = ByteBuffer.allocate(1 + strSize(acct) + 8)
        val _ = b.put(1.toByte); putStr(b, acct); val _ = b.putLong(bal)
        b.array()
      case LedgerResponse.BalanceUpdated(acct, bal) =>
        val b = ByteBuffer.allocate(1 + strSize(acct) + 8)
        val _ = b.put(2.toByte); putStr(b, acct); val _ = b.putLong(bal)
        b.array()
      case LedgerResponse.Transferred(txId, fb, tb) =>
        val b = ByteBuffer.allocate(1 + strSize(txId) + 16)
        val _ = b.put(3.toByte); putStr(b, txId)
        val _ = b.putLong(fb); val _ = b.putLong(tb)
        b.array()
      case LedgerResponse.Prepared(txId) =>
        val b = ByteBuffer.allocate(1 + strSize(txId))
        val _ = b.put(4.toByte); putStr(b, txId)
        b.array()
      case LedgerResponse.Committed2PC(txId) =>
        val b = ByteBuffer.allocate(1 + strSize(txId))
        val _ = b.put(5.toByte); putStr(b, txId)
        b.array()
      case LedgerResponse.Aborted2PC(txId) =>
        val b = ByteBuffer.allocate(1 + strSize(txId))
        val _ = b.put(6.toByte); putStr(b, txId)
        b.array()
      case LedgerResponse.Rejected(reason) =>
        val b = ByteBuffer.allocate(1 + strSize(reason))
        val _ = b.put(7.toByte); putStr(b, reason)
        b.array()

    def decodeResponse(b: ByteBuffer): LedgerResponse =
      b.get().toInt match
        case 1 => LedgerResponse.AccountOpened(getStr(b), b.getLong())
        case 2 => LedgerResponse.BalanceUpdated(getStr(b), b.getLong())
        case 3 =>
          LedgerResponse.Transferred(getStr(b), b.getLong(), b.getLong())
        case 4 => LedgerResponse.Prepared(getStr(b))
        case 5 => LedgerResponse.Committed2PC(getStr(b))
        case 6 => LedgerResponse.Aborted2PC(getStr(b))
        case _ => LedgerResponse.Rejected(getStr(b))

  // ─────────────────────────────────────────────────────────────────────────
  // 2. Replicated Account Shard State Machine (Holds + Session Deduplication)
  // ─────────────────────────────────────────────────────────────────────────

  final case class Hold(
      txId: String,
      accountId: String,
      amount: Long,
      isDebit: Boolean
  )

  final case class ShardState(
      balances: Map[String, Long] = Map.empty,
      holds: Map[String, Hold] = Map.empty,
      resolvedTxs: Map[String, Boolean] = Map.empty,
      sessions: Map[String, (Long, LedgerResponse)] = Map.empty,
      resultsByIndex: Map[Long, LedgerResponse] = Map.empty
  ):
    def reservedFor(accountId: String): Long =
      holds.values.collect {
        case h if h.isDebit && h.accountId == accountId => h.amount
      }.sum

    def availableBalance(accountId: String): Option[Long] =
      balances.get(accountId).map(_ - reservedFor(accountId))

  final class LedgerShardStateMachine(ref: Ref[IO, ShardState])
      extends StateMachine[IO, LedgerResponse]:

    def apply(log: Log): IO[LedgerResponse] =
      if log.data.isEmpty then
        IO.pure(LedgerResponse.Rejected("noop-empty-entry"))
      else
        WireCodec.decodeCommand(log.data) match
          case Left(err)  => IO.pure(LedgerResponse.Rejected(err))
          case Right(cmd) =>
            ref.modify { st =>
              val (nextSt, resp) = stepCommand(st, cmd)
              val prunedIdx =
                if nextSt.resultsByIndex.size > 2048 then
                  val cutoff = log.index - 1024L
                  nextSt.resultsByIndex.filter(_._1 >= cutoff)
                else nextSt.resultsByIndex
              (
                nextSt.copy(resultsByIndex =
                  prunedIdx.updated(log.index, resp)
                ),
                resp
              )
            }

    private def withSessionDedup(
        st: ShardState,
        clientId: String,
        seqNum: Long
    )(compute: ShardState => (ShardState, LedgerResponse)): (
        ShardState,
        LedgerResponse
    ) =
      st.sessions.get(clientId) match
        case Some((lastSeq, cachedResp)) if seqNum <= lastSeq =>
          (st, cachedResp)
        case _ =>
          val (st2, resp) = compute(st)
          (
            st2.copy(sessions = st2.sessions.updated(clientId, (seqNum, resp))),
            resp
          )

    private def stepCommand(
        st: ShardState,
        cmd: LedgerCommand
    ): (ShardState, LedgerResponse) =
      cmd match
        case LedgerCommand.OpenAccount(cid, seq, acct, init) =>
          withSessionDedup(st, cid, seq) { cur =>
            if init < 0L then
              (
                cur,
                LedgerResponse.Rejected("Initial balance cannot be negative")
              )
            else
              val bal = cur.balances.getOrElse(acct, init)
              val updated =
                if cur.balances.contains(acct) then cur
                else cur.copy(balances = cur.balances.updated(acct, init))
              (updated, LedgerResponse.AccountOpened(acct, bal))
          }

        case LedgerCommand.Deposit(cid, seq, acct, amt) =>
          withSessionDedup(st, cid, seq) { cur =>
            cur.balances.get(acct) match
              case None =>
                (cur, LedgerResponse.Rejected(s"Unknown account: $acct"))
              case Some(_) if amt <= 0L =>
                (
                  cur,
                  LedgerResponse.Rejected("Deposit amount must be positive")
                )
              case Some(bal) =>
                val nextBal = bal + amt
                (
                  cur.copy(balances = cur.balances.updated(acct, nextBal)),
                  LedgerResponse.BalanceUpdated(acct, nextBal)
                )
          }

        case LedgerCommand.Withdraw(cid, seq, acct, amt) =>
          withSessionDedup(st, cid, seq) { cur =>
            cur.availableBalance(acct) match
              case None =>
                (cur, LedgerResponse.Rejected(s"Unknown account: $acct"))
              case Some(_) if amt <= 0L =>
                (
                  cur,
                  LedgerResponse.Rejected("Withdraw amount must be positive")
                )
              case Some(avail) if avail < amt =>
                (
                  cur,
                  LedgerResponse.Rejected(
                    s"Insufficient available funds on $acct: avail=$avail < $amt"
                  )
                )
              case Some(_) =>
                val nextBal = cur.balances(acct) - amt
                (
                  cur.copy(balances = cur.balances.updated(acct, nextBal)),
                  LedgerResponse.BalanceUpdated(acct, nextBal)
                )
          }

        case LedgerCommand.SameShardTransfer(cid, seq, from, to, amt) =>
          withSessionDedup(st, cid, seq) { cur =>
            if from == to then
              (cur, LedgerResponse.Rejected("Cannot transfer to same account"))
            else if amt <= 0L then
              (cur, LedgerResponse.Rejected("Transfer amount must be positive"))
            else
              (cur.availableBalance(from), cur.balances.get(to)) match
                case (None, _) =>
                  (cur, LedgerResponse.Rejected(s"Unknown source: $from"))
                case (_, None) =>
                  (cur, LedgerResponse.Rejected(s"Unknown destination: $to"))
                case (Some(avail), _) if avail < amt =>
                  (
                    cur,
                    LedgerResponse.Rejected(
                      s"Insufficient funds on $from: avail=$avail < $amt"
                    )
                  )
                case (Some(_), Some(toBal)) =>
                  val newFrom = cur.balances(from) - amt
                  val newTo = toBal + amt
                  val updated = cur.copy(
                    balances = cur.balances
                      .updated(from, newFrom)
                      .updated(to, newTo)
                  )
                  (
                    updated,
                    LedgerResponse.Transferred(
                      s"local-$cid-$seq",
                      newFrom,
                      newTo
                    )
                  )
          }

        case LedgerCommand.PrepareDebit(txId, acct, amt) =>
          st.resolvedTxs.get(txId) match
            case Some(true)  => (st, LedgerResponse.Committed2PC(txId))
            case Some(false) => (st, LedgerResponse.Aborted2PC(txId))
            case None        =>
              if st.holds.contains(txId) then
                (st, LedgerResponse.Prepared(txId))
              else if amt <= 0L then
                (st, LedgerResponse.Rejected("Invalid transfer amount"))
              else
                st.availableBalance(acct) match
                  case None =>
                    (st, LedgerResponse.Rejected(s"Unknown account: $acct"))
                  case Some(avail) if avail < amt =>
                    (
                      st,
                      LedgerResponse.Rejected(
                        s"Insufficient funds on $acct: avail=$avail < $amt"
                      )
                    )
                  case Some(_) =>
                    val hold = Hold(txId, acct, amt, isDebit = true)
                    (
                      st.copy(holds = st.holds.updated(txId, hold)),
                      LedgerResponse.Prepared(txId)
                    )

        case LedgerCommand.PrepareCredit(txId, acct, amt) =>
          st.resolvedTxs.get(txId) match
            case Some(true)  => (st, LedgerResponse.Committed2PC(txId))
            case Some(false) => (st, LedgerResponse.Aborted2PC(txId))
            case None        =>
              if st.holds.contains(txId) then
                (st, LedgerResponse.Prepared(txId))
              else if amt <= 0L then
                (st, LedgerResponse.Rejected("Invalid transfer amount"))
              else if !st.balances.contains(acct) then
                (st, LedgerResponse.Rejected(s"Unknown account: $acct"))
              else
                val hold = Hold(txId, acct, amt, isDebit = false)
                (
                  st.copy(holds = st.holds.updated(txId, hold)),
                  LedgerResponse.Prepared(txId)
                )

        case LedgerCommand.Commit2PC(txId) =>
          if st.resolvedTxs.get(txId).contains(true) then
            (st, LedgerResponse.Committed2PC(txId))
          else
            val nextBalances = st.holds.get(txId) match
              case Some(Hold(_, acct, amt, true)) =>
                st.balances.updated(acct, st.balances(acct) - amt)
              case Some(Hold(_, acct, amt, false)) =>
                st.balances.updated(acct, st.balances(acct) + amt)
              case None => st.balances
            val updated = st.copy(
              balances = nextBalances,
              holds = st.holds - txId,
              resolvedTxs = st.resolvedTxs.updated(txId, true)
            )
            (updated, LedgerResponse.Committed2PC(txId))

        case LedgerCommand.Abort2PC(txId) =>
          if st.resolvedTxs.get(txId).contains(false) then
            (st, LedgerResponse.Aborted2PC(txId))
          else
            val updated = st.copy(
              holds = st.holds - txId,
              resolvedTxs = st.resolvedTxs.updated(txId, false)
            )
            (updated, LedgerResponse.Aborted2PC(txId))

    def snapshot: IO[Stream[IO, Byte]] =
      ref.get.map { st =>
        val balSeq = st.balances.toVector.sortBy(_._1)
        val holdSeq = st.holds.values.toVector.sortBy(_.txId)
        val resSeq = st.resolvedTxs.toVector.sortBy(_._1)
        val sessSeq =
          st.sessions.toVector.sortBy(_._1).map { case (cid, (seq, resp)) =>
            (cid, seq, WireCodec.encodeResponse(resp))
          }

        val totalSize =
          4 + balSeq.map((k, _) => WireCodec.strSize(k) + 8).sum +
            4 + holdSeq
              .map(h =>
                WireCodec.strSize(h.txId) + WireCodec.strSize(h.accountId) + 9
              )
              .sum +
            4 + resSeq.map((txId, _) => WireCodec.strSize(txId) + 1).sum +
            4 + sessSeq
              .map((cid, _, rb) => WireCodec.strSize(cid) + 8 + 4 + rb.length)
              .sum

        val buf = ByteBuffer.allocate(totalSize)
        val _ = buf.putInt(balSeq.size)
        balSeq.foreach { case (k, v) =>
          WireCodec.putStr(buf, k); val _ = buf.putLong(v)
        }
        val _ = buf.putInt(holdSeq.size)
        holdSeq.foreach { h =>
          WireCodec.putStr(buf, h.txId)
          WireCodec.putStr(buf, h.accountId)
          val _ = buf.putLong(h.amount)
          val _ = buf.put(if h.isDebit then 1.toByte else 0.toByte)
        }
        val _ = buf.putInt(resSeq.size)
        resSeq.foreach { case (txId, c) =>
          WireCodec.putStr(buf, txId)
          val _ = buf.put(if c then 1.toByte else 0.toByte)
        }
        val _ = buf.putInt(sessSeq.size)
        sessSeq.foreach { case (cid, seq, rb) =>
          WireCodec.putStr(buf, cid)
          val _ = buf.putLong(seq)
          val _ = buf.putInt(rb.length)
          val _ = buf.put(rb)
        }
        Stream.chunk(Chunk.array(buf.array())).covary[IO]
      }

    def restore(data: Stream[IO, Byte]): IO[Unit] =
      data.compile.to(Array).flatMap { bytes =>
        if bytes.isEmpty then ref.set(ShardState())
        else
          val buf = ByteBuffer.wrap(bytes)
          val balCount = buf.getInt()
          val balances = (0 until balCount).map { _ =>
            WireCodec.getStr(buf) -> buf.getLong()
          }.toMap
          val holdCount = buf.getInt()
          val holds = (0 until holdCount).map { _ =>
            val txId = WireCodec.getStr(buf)
            val acct = WireCodec.getStr(buf)
            val amt = buf.getLong()
            val isDeb = buf.get() == 1.toByte
            txId -> Hold(txId, acct, amt, isDeb)
          }.toMap
          val resCount = buf.getInt()
          val resolved = (0 until resCount).map { _ =>
            WireCodec.getStr(buf) -> (buf.get() == 1.toByte)
          }.toMap
          val sessCount = buf.getInt()
          val sessions = (0 until sessCount).map { _ =>
            val cid = WireCodec.getStr(buf)
            val seq = buf.getLong()
            val rLen = buf.getInt()
            val rBytes = new Array[Byte](rLen)
            val _ = buf.get(rBytes)
            val resp = WireCodec.decodeResponse(ByteBuffer.wrap(rBytes))
            cid -> (seq, resp)
          }.toMap
          ref.set(ShardState(balances, holds, resolved, sessions, Map.empty))
      }

    def getState: IO[ShardState] = ref.get
    def getResultAt(logIndex: Long): IO[Option[LedgerResponse]] =
      ref.get.map(_.resultsByIndex.get(logIndex))

  object LedgerShardStateMachine:
    def create: IO[LedgerShardStateMachine] =
      Ref.of[IO, ShardState](ShardState()).map(new LedgerShardStateMachine(_))

  // ─────────────────────────────────────────────────────────────────────────
  // 3. Replicated 2PC Coordinator State Machine (`tx-coord` Raft Group)
  // ─────────────────────────────────────────────────────────────────────────

  enum TxPhase:
    case Preparing
    case Committed
    case Aborted
    case Completed

  final case class TxRecord(
      txId: String,
      clientId: String,
      seqNum: Long,
      fromAccount: String,
      toAccount: String,
      amount: Long,
      phase: TxPhase,
      reason: Option[String] = None
  )

  enum CoordCommand:
    case Begin(
        txId: String,
        clientId: String,
        seqNum: Long,
        fromAccount: String,
        toAccount: String,
        amount: Long
    )
    case DecideCommit(txId: String)
    case DecideAbort(txId: String, reason: String)
    case MarkCompleted(txId: String)

  object CoordCodec:
    import WireCodec.{getStr, putStr, strSize}

    def encode(cmd: CoordCommand): Array[Byte] = cmd match
      case CoordCommand.Begin(txId, cid, seq, from, to, amt) =>
        val b = ByteBuffer.allocate(
          1 + strSize(txId) + strSize(cid) + 8 + strSize(from) + strSize(to) + 8
        )
        val _ = b.put(1.toByte)
        putStr(b, txId); putStr(b, cid); val _ = b.putLong(seq)
        putStr(b, from); putStr(b, to); val _ = b.putLong(amt)
        b.array()
      case CoordCommand.DecideCommit(txId) =>
        val b = ByteBuffer.allocate(1 + strSize(txId))
        val _ = b.put(2.toByte); putStr(b, txId); b.array()
      case CoordCommand.DecideAbort(txId, reason) =>
        val b = ByteBuffer.allocate(1 + strSize(txId) + strSize(reason))
        val _ = b.put(3.toByte); putStr(b, txId); putStr(b, reason); b.array()
      case CoordCommand.MarkCompleted(txId) =>
        val b = ByteBuffer.allocate(1 + strSize(txId))
        val _ = b.put(4.toByte); putStr(b, txId); b.array()

    def decode(bytes: Array[Byte]): Option[CoordCommand] =
      if bytes.isEmpty then None
      else
        val b = ByteBuffer.wrap(bytes)
        b.get().toInt match
          case 1 =>
            Some(
              CoordCommand.Begin(
                getStr(b),
                getStr(b),
                b.getLong(),
                getStr(b),
                getStr(b),
                b.getLong()
              )
            )
          case 2 => Some(CoordCommand.DecideCommit(getStr(b)))
          case 3 => Some(CoordCommand.DecideAbort(getStr(b), getStr(b)))
          case 4 => Some(CoordCommand.MarkCompleted(getStr(b)))
          case _ => None

  final class CoordStateMachine(ref: Ref[IO, Map[String, TxRecord]])
      extends StateMachine[IO, Unit]:

    def apply(log: Log): IO[Unit] =
      CoordCodec.decode(log.data) match
        case None      => IO.unit
        case Some(cmd) =>
          ref.update { m =>
            cmd match
              case CoordCommand.Begin(txId, cid, seq, from, to, amt) =>
                if m.contains(txId) then m
                else
                  m.updated(
                    txId,
                    TxRecord(txId, cid, seq, from, to, amt, TxPhase.Preparing)
                  )
              case CoordCommand.DecideCommit(txId) =>
                m.get(txId) match
                  case Some(r) if r.phase == TxPhase.Preparing =>
                    m.updated(txId, r.copy(phase = TxPhase.Committed))
                  case _ => m
              case CoordCommand.DecideAbort(txId, reason) =>
                m.get(txId) match
                  case Some(r) if r.phase == TxPhase.Preparing =>
                    m.updated(
                      txId,
                      r.copy(phase = TxPhase.Aborted, reason = Some(reason))
                    )
                  case _ => m
              case CoordCommand.MarkCompleted(txId) =>
                m.get(txId) match
                  case Some(r) =>
                    m.updated(txId, r.copy(phase = TxPhase.Completed))
                  case None => m
          }

    def snapshot: IO[Stream[IO, Byte]] =
      ref.get.map { m =>
        val recs = m.values.toVector.sortBy(_.txId)
        val size = 4 + recs.map { r =>
          WireCodec.strSize(r.txId) + WireCodec.strSize(r.clientId) + 8 +
            WireCodec.strSize(r.fromAccount) + WireCodec.strSize(r.toAccount) +
            8 + 1 + WireCodec.strSize(r.reason.getOrElse(""))
        }.sum
        val b = ByteBuffer.allocate(size)
        val _ = b.putInt(recs.size)
        recs.foreach { r =>
          WireCodec.putStr(b, r.txId)
          WireCodec.putStr(b, r.clientId)
          val _ = b.putLong(r.seqNum)
          WireCodec.putStr(b, r.fromAccount)
          WireCodec.putStr(b, r.toAccount)
          val _ = b.putLong(r.amount)
          val _ = b.put(r.phase.ordinal.toByte)
          WireCodec.putStr(b, r.reason.getOrElse(""))
        }
        Stream.chunk(Chunk.array(b.array())).covary[IO]
      }

    def restore(data: Stream[IO, Byte]): IO[Unit] =
      data.compile.to(Array).flatMap { bytes =>
        if bytes.isEmpty then ref.set(Map.empty)
        else
          val b = ByteBuffer.wrap(bytes)
          val count = b.getInt()
          val parsed = (0 until count).map { _ =>
            val txId = WireCodec.getStr(b)
            val cid = WireCodec.getStr(b)
            val seq = b.getLong()
            val from = WireCodec.getStr(b)
            val to = WireCodec.getStr(b)
            val amt = b.getLong()
            val phase = TxPhase.fromOrdinal(b.get().toInt)
            val rsn = WireCodec.getStr(b)
            txId -> TxRecord(
              txId,
              cid,
              seq,
              from,
              to,
              amt,
              phase,
              if rsn.isEmpty then None else Some(rsn)
            )
          }.toMap
          ref.set(parsed)
      }

    def allRecords: IO[Map[String, TxRecord]] = ref.get

  object CoordStateMachine:
    def create: IO[CoordStateMachine] =
      Ref.of[IO, Map[String, TxRecord]](Map.empty).map(new CoordStateMachine(_))

  // ─────────────────────────────────────────────────────────────────────────
  // 4. Full-Stack Multi-Raft Physical Cluster Harness & Client Coordinator
  // ─────────────────────────────────────────────────────────────────────────

  final case class GlobalAuditReport(
      balancesByShard: Map[GroupId, Map[String, Long]],
      totalBalance: Long,
      activeHoldsCount: Int,
      completed2PCCount: Int,
      aborted2PCCount: Int
  )

  final case class LedgerClusterConfig(
      hostIds: List[NodeId] = List(
        NodeId("host-1"),
        NodeId("host-2"),
        NodeId("host-3")
      ),
      shardCount: Int = 3,
      walSegmentBytes: Long = 8192L,
      compactionThreshold: Long = 20L
  )

  private[ShardedBankLedgerExample] final case class HostRuntime(
      id: NodeId,
      port: Int,
      address: String,
      grpc: GrpcTransport[IO],
      mgTransport: MultiGroupTransport[IO],
      coordinator: MultiRaftNode[IO],
      engines: Map[GroupId, MmapWalEngine[IO]],
      shardSms: Map[GroupId, LedgerShardStateMachine],
      coordSm: CoordStateMachine
  )

  final class ShardedLedgerCluster private (
      val config: LedgerClusterConfig,
      val baseDir: Path,
      hostsRef: Ref[IO, Map[NodeId, (HostRuntime, IO[Unit])]],
      peerAddrsRef: Ref[IO, Map[NodeId, String]]
  ):
    val shardGroups: Vector[GroupId] =
      (0 until config.shardCount).map(ledgerShardId).toVector
    val allGroups: Vector[GroupId] = CoordGroup +: shardGroups

    /** Crash/stop a single physical host (releasing its MultiRaftNode, Netty
      * Server, and MmapWalEngine file locks while preserving its on-disk WAL
      * and snapshot directories).
      */
    def stopHost(id: NodeId): IO[Unit] =
      hostsRef
        .modify { m =>
          (m - id, m.get(id))
        }
        .flatMap(_.traverse_(_._2))

    /** Restart a previously crashed physical host on its SAME TCP port and
      * EXISTING on-disk `MmapWalEngine` directories.
      */
    def restartHost(id: NodeId): IO[Unit] =
      for
        addrs <- peerAddrsRef.get
        addr = addrs(id)
        port = addr.split(":").last.toInt
        allocated <- ShardedLedgerCluster
          .allocateHost(
            id = id,
            port = port,
            baseDir = baseDir,
            cfg = config,
            initialPeers = addrs - id
          )
          .allocated
        _ <- hostsRef.update(_.updated(id, allocated))
      yield ()

    def liveHostIds: IO[List[NodeId]] =
      hostsRef.get.map(_.keys.toList.sorted(Ordering.by(_.value)))

    def shardSnapshotCount(hostId: NodeId, gid: GroupId): IO[Int] =
      hostsRef.get.flatMap { m =>
        m(hostId)._1.engines(gid).listSnapshots.map(_.size)
      }

    def allReplicasConverged: IO[Boolean] =
      hostsRef.get.flatMap { m =>
        val runtimes = m.values.map(_._1).toList
        if runtimes.size <= 1 then IO.pure(true)
        else
          shardGroups.forallM { gid =>
            runtimes
              .traverse(
                _.shardSms(gid).getState.map(s => (s.balances, s.holds))
              )
              .map(states => states.distinct.size == 1)
          }
      }

    def coalescerTelemetry: IO[Vector[CoalescerStats]] =
      hostsRef.get.flatMap { m =>
        m.values.toVector.traverse(_._1.mgTransport.coalescerStats)
      }

    def rebalanceShards: IO[Unit] =
      val preferred: Map[GroupId, NodeId] = allGroups.zipWithIndex.map {
        case (gid, idx) =>
          gid -> config.hostIds(idx % config.hostIds.size)
      }.toMap

      def loop(retries: Int): IO[Unit] =
        for
          liveMap <- hostsRef.get
          runtimes = liveMap.values.map(_._1).toList
          _ <-
            if retries % 15 == 0 then
              runtimes.traverse_(_.coordinator.rebalanceLeadership(preferred))
            else IO.unit
          done <- preferred.toList.forallM { case (gid, target) =>
            liveMap.get(target) match
              case None          => IO.pure(true)
              case Some((rt, _)) =>
                rt.coordinator.getGroupState(gid).map {
                  case Some(_: Leader) => true
                  case _               => false
                }
          }
          _ <-
            if done then IO.unit
            else if retries <= 0 then
              IO.raiseError(new RuntimeException("Shard rebalance timed out"))
            else IO.sleep(25.millis) *> loop(retries - 1)
        yield ()

      loop(450)

    def transferShardLeader(gid: GroupId, target: NodeId): IO[Unit] =
      def loop(retries: Int): IO[Unit] =
        for
          ldr <- awaitGroupLeader(gid)
          _ <-
            if ldr.id == target then IO.unit
            else
              (if retries % 15 == 0 then
                 ldr.coordinator
                   .transferGroupLeadership(gid, target)
                   .attempt
                   .void
               else IO.unit) *> {
                if retries <= 0 then
                  IO.raiseError(
                    new RuntimeException(
                      s"Leadership transfer of ${gid.value} to ${target.value} timed out"
                    )
                  )
                else IO.sleep(25.millis) *> loop(retries - 1)
              }
        yield ()
      loop(400)

    def openAccountsBatch(
        accounts: Seq[(String, Long)],
        clientIdPrefix: String = "seed"
    ): IO[Unit] =
      val byShard = accounts.zipWithIndex.groupBy { case ((acct, _), _) =>
        shardForAccount(acct, config.shardCount)
      }
      byShard.toList.parTraverse_ { case (gid, entries) =>
        val payloads = entries.map { case ((acct, init), idx) =>
          WireCodec.encodeCommand(
            LedgerCommand.OpenAccount(
              clientId = s"$clientIdPrefix-$acct",
              seqNum = idx.toLong + 1L,
              accountId = acct,
              initialBalance = init
            )
          )
        }
        proposeBatchWithRetry(gid, payloads).void
      }

    def deposit(
        clientId: String,
        seqNum: Long,
        accountId: String,
        amount: Long
    ): IO[LedgerResponse] =
      val gid = shardForAccount(accountId, config.shardCount)
      proposeShardCommand(
        gid,
        LedgerCommand.Deposit(clientId, seqNum, accountId, amount)
      )

    def withdraw(
        clientId: String,
        seqNum: Long,
        accountId: String,
        amount: Long
    ): IO[LedgerResponse] =
      val gid = shardForAccount(accountId, config.shardCount)
      proposeShardCommand(
        gid,
        LedgerCommand.Withdraw(clientId, seqNum, accountId, amount)
      )

    /** Execute an atomic transfer:
      *   - Uses single-entry `SameShardTransfer` when `fromAccount` and
      *     `toAccount` map to the same Raft shard.
      *   - Uses Raft-coordinated Two-Phase Commit (`tx-coord` + `PrepareDebit`
      *     + `PrepareCredit` + `Commit2PC` / `Abort2PC`) across shards when
      *     they live on different Raft groups!
      */
    def transfer(
        clientId: String,
        seqNum: Long,
        fromAccount: String,
        toAccount: String,
        amount: Long
    ): IO[LedgerResponse] =
      val fromShard = shardForAccount(fromAccount, config.shardCount)
      val toShard = shardForAccount(toAccount, config.shardCount)
      if fromShard == toShard then
        proposeShardCommand(
          fromShard,
          LedgerCommand.SameShardTransfer(
            clientId,
            seqNum,
            fromAccount,
            toAccount,
            amount
          )
        )
      else
        executeCrossShard2PC(
          clientId,
          seqNum,
          fromAccount,
          toAccount,
          amount
        )

    private def executeCrossShard2PC(
        clientId: String,
        seqNum: Long,
        fromAccount: String,
        toAccount: String,
        amount: Long
    ): IO[LedgerResponse] =
      val txId = s"2pc-$clientId-$seqNum"
      val fromShard = shardForAccount(fromAccount, config.shardCount)
      val toShard = shardForAccount(toAccount, config.shardCount)

      for
        // Step 1: Durably log `Preparing` intent in `tx-coord`
        _ <- proposeCoord(
          CoordCommand.Begin(
            txId,
            clientId,
            seqNum,
            fromAccount,
            toAccount,
            amount
          )
        )

        // Step 2: Phase 1 Prepare on both participant shards in parallel
        votes <- (
          proposeShardCommand(
            fromShard,
            LedgerCommand.PrepareDebit(txId, fromAccount, amount)
          ),
          proposeShardCommand(
            toShard,
            LedgerCommand.PrepareCredit(txId, toAccount, amount)
          )
        ).parTupled

        result <- votes match
          case (
                LedgerResponse.Prepared(_) | LedgerResponse.Committed2PC(_),
                LedgerResponse.Prepared(_) | LedgerResponse.Committed2PC(_)
              ) =>
            // Step 3a: Both shards locked funds/validated -> commit decision in `tx-coord`!
            for
              _ <- proposeCoord(CoordCommand.DecideCommit(txId))
              _ <- (
                proposeShardCommand(fromShard, LedgerCommand.Commit2PC(txId)),
                proposeShardCommand(toShard, LedgerCommand.Commit2PC(txId))
              ).parTupled
              _ <- proposeCoord(CoordCommand.MarkCompleted(txId))
              fromBalOpt <- linearizableBalance(fromAccount)
              toBalOpt <- linearizableBalance(toAccount)
            yield LedgerResponse.Transferred(
              txId,
              fromBalOpt.getOrElse(0L),
              toBalOpt.getOrElse(0L)
            )

          case (r1, r2) =>
            // Step 3b: At least one shard rejected -> abort decision & release holds!
            val reason = List(r1, r2)
              .collectFirst { case LedgerResponse.Rejected(msg) => msg }
              .getOrElse(s"Prepare aborted: ($r1, $r2)")
            for
              _ <- proposeCoord(CoordCommand.DecideAbort(txId, reason))
              _ <- (
                proposeShardCommand(fromShard, LedgerCommand.Abort2PC(txId)),
                proposeShardCommand(toShard, LedgerCommand.Abort2PC(txId))
              ).parTupled
              _ <- proposeCoord(CoordCommand.MarkCompleted(txId))
            yield LedgerResponse.Rejected(reason)
      yield result

    /** Fault-injection helper for testing coordinator crash mid-2PC:
      *   - When `stopAfterDecisionCommit = false`: leaves transaction in
      *     `TxPhase.Preparing` with `PrepareDebit` hold locked on `fromShard`.
      *   - When `stopAfterDecisionCommit = true`: commits `DecideCommit` in
      *     `tx-coord` and applies `Commit2PC` ONLY to `fromShard`, leaving
      *     `toShard` un-credited until `recoverInFlightTransactions` runs!
      */
    def injectInterrupted2PC(
        txId: String,
        fromAccount: String,
        toAccount: String,
        amount: Long,
        stopAfterDecisionCommit: Boolean
    ): IO[Unit] =
      val fromShard = shardForAccount(fromAccount, config.shardCount)
      val toShard = shardForAccount(toAccount, config.shardCount)
      for
        _ <- proposeCoord(
          CoordCommand.Begin(
            txId,
            s"injected-$txId",
            1L,
            fromAccount,
            toAccount,
            amount
          )
        )
        _ <- proposeShardCommand(
          fromShard,
          LedgerCommand.PrepareDebit(txId, fromAccount, amount)
        )
        _ <-
          if stopAfterDecisionCommit then
            proposeShardCommand(
              toShard,
              LedgerCommand.PrepareCredit(txId, toAccount, amount)
            ) *>
              proposeCoord(CoordCommand.DecideCommit(txId)) *>
              proposeShardCommand(fromShard, LedgerCommand.Commit2PC(txId)).void
          else IO.unit
      yield ()

    /** Deterministic 2PC recovery loop: reads linearizable `tx-coord` state and
      * drives any interrupted `Preparing` (abort & release holds) or
      * `Committed` (roll forward idempotently) transactions to `Completed`.
      */
    def recoverInFlightTransactions: IO[Int] =
      for
        coordLeader <- linearizableGroupLeader(CoordGroup)
        records <- coordLeader.coordSm.allRecords
        incomplete = records.values.toList.filter(
          _.phase != TxPhase.Completed
        )
        _ <- incomplete.traverse_ { rec =>
          val fromShard = shardForAccount(rec.fromAccount, config.shardCount)
          val toShard = shardForAccount(rec.toAccount, config.shardCount)
          rec.phase match
            case TxPhase.Preparing | TxPhase.Aborted =>
              proposeCoord(
                CoordCommand.DecideAbort(rec.txId, "Coordinator crash recovery")
              ) *>
                (
                  proposeShardCommand(
                    fromShard,
                    LedgerCommand.Abort2PC(rec.txId)
                  ),
                  proposeShardCommand(toShard, LedgerCommand.Abort2PC(rec.txId))
                ).parTupled *>
                proposeCoord(CoordCommand.MarkCompleted(rec.txId))

            case TxPhase.Committed =>
              (
                proposeShardCommand(
                  fromShard,
                  LedgerCommand.Commit2PC(rec.txId)
                ),
                proposeShardCommand(toShard, LedgerCommand.Commit2PC(rec.txId))
              ).parTupled *>
                proposeCoord(CoordCommand.MarkCompleted(rec.txId))

            case TxPhase.Completed => IO.unit
        }
      yield incomplete.size

    /** Linearizable single-account balance read: tries zero-RTT `leaseRead`
      * first and falls back to quorum `readIndex` when lease is unconfirmed.
      */
    def linearizableBalance(accountId: String): IO[Option[Long]] =
      val gid = shardForAccount(accountId, config.shardCount)
      def loop(attempts: Int): IO[Option[Long]] =
        awaitGroupLeader(gid).flatMap { ldr =>
          val readGate = ldr.coordinator.leaseReadFromGroup(gid).flatMap {
            case Some(_) => IO.unit
            case None    => ldr.coordinator.readIndexFromGroup(gid).void
          }
          (readGate *> ldr
            .shardSms(gid)
            .getState
            .map(_.balances.get(accountId)))
            .timeout(3.seconds)
            .handleErrorWith {
              case _ if attempts > 1 =>
                IO.sleep(30.millis) *> loop(attempts - 1)
              case err => IO.raiseError(err)
            }
        }
      loop(20)

    /** Quorum-verified linearizable audit across `tx-coord` and all account
      * shards.
      */
    def auditGlobalBalances: IO[GlobalAuditReport] =
      for
        shardSnapshots <- shardGroups.traverse { gid =>
          linearizableGroupLeader(gid).flatMap { ldr =>
            ldr.shardSms(gid).getState.map(st => gid -> st)
          }
        }
        coordLeader <- linearizableGroupLeader(CoordGroup)
        txRecords <- coordLeader.coordSm.allRecords
      yield
        val byShard = shardSnapshots.map((gid, st) => gid -> st.balances).toMap
        val total = byShard.values.flatMap(_.values).sum
        val activeHolds = shardSnapshots.map(_._2.holds.size).sum
        val completed = txRecords.values.count(r =>
          r.phase == TxPhase.Completed && r.reason.isEmpty
        )
        val aborted = txRecords.values.count(_.reason.isDefined)
        GlobalAuditReport(byShard, total, activeHolds, completed, aborted)

    private def awaitGroupLeader(
        gid: GroupId,
        retries: Int = 450
    ): IO[HostRuntime] =
      hostsRef.get.flatMap { liveMap =>
        val runtimes = liveMap.values.map(_._1).toList
        runtimes
          .traverse(rt => rt.coordinator.getGroupState(gid).map(rt -> _))
          .flatMap { pairs =>
            val leaders = pairs.collect { case (rt, Some(l: Leader)) =>
              (rt, l.term)
            }
            leaders.sortBy(-_._2).headOption match
              case Some((rt, _)) => IO.pure(rt)
              case None          =>
                if retries <= 0 then
                  IO.raiseError(
                    new RuntimeException(s"No leader elected for ${gid.value}")
                  )
                else IO.sleep(20.millis) *> awaitGroupLeader(gid, retries - 1)
          }
      }

    private def linearizableGroupLeader(
        gid: GroupId,
        attempts: Int = 20
    ): IO[HostRuntime] =
      awaitGroupLeader(gid).flatMap { ldr =>
        ldr.coordinator
          .readIndexFromGroup(gid)
          .timeout(3.seconds)
          .as(ldr)
          .handleErrorWith {
            case _ if attempts > 1 =>
              IO.sleep(30.millis) *> linearizableGroupLeader(gid, attempts - 1)
            case err => IO.raiseError(err)
          }
      }

    private def proposeCoord(
        cmd: CoordCommand,
        attempts: Int = 20
    ): IO[Unit] =
      val payload = CoordCodec.encode(cmd)
      awaitGroupLeader(CoordGroup).flatMap { ldr =>
        ldr.coordinator
          .proposeAndCommitToGroup(CoordGroup, payload)
          .timeout(4.seconds)
          .void
          .handleErrorWith {
            case _ if attempts > 1 =>
              IO.sleep(30.millis) *> proposeCoord(cmd, attempts - 1)
            case err => IO.raiseError(err)
          }
      }

    private def proposeBatchWithRetry(
        gid: GroupId,
        payloads: Seq[Array[Byte]],
        attempts: Int = 20
    ): IO[Vector[LogId]] =
      awaitGroupLeader(gid).flatMap { ldr =>
        ldr.coordinator
          .proposeBatchAndCommitToGroup(gid, payloads)
          .timeout(4.seconds)
          .handleErrorWith {
            case _ if attempts > 1 =>
              IO.sleep(30.millis) *>
                proposeBatchWithRetry(gid, payloads, attempts - 1)
            case err => IO.raiseError(err)
          }
      }

    private def proposeShardCommand(
        gid: GroupId,
        cmd: LedgerCommand,
        attempts: Int = 20
    ): IO[LedgerResponse] =
      val payload = WireCodec.encodeCommand(cmd)
      awaitGroupLeader(gid).flatMap { ldr =>
        (for
          logId <- ldr.coordinator.proposeAndCommitToGroup(gid, payload)
          respOpt <- ldr.shardSms(gid).getResultAt(logId.index)
          resp <- IO.fromOption(respOpt)(
            new RuntimeException(
              s"Missing state machine result at index ${logId.index}"
            )
          )
        yield resp)
          .timeout(4.seconds)
          .handleErrorWith {
            case _ if attempts > 1 =>
              IO.sleep(30.millis) *> proposeShardCommand(gid, cmd, attempts - 1)
            case err => IO.raiseError(err)
          }
      }

  object ShardedLedgerCluster:

    private def deleteRecursive(path: Path): Unit =
      if Files.exists(path) then
        if Files.isDirectory(path) then
          val listing = Files.list(path)
          try listing.iterator().forEachRemaining(deleteRecursive)
          finally listing.close()
        val _ = Files.deleteIfExists(path)

    private[ShardedBankLedgerExample] def allocateHost(
        id: NodeId,
        port: Int,
        baseDir: Path,
        cfg: LedgerClusterConfig,
        initialPeers: Map[NodeId, String]
    ): Resource[IO, HostRuntime] =
      val shardGroups = (0 until cfg.shardCount).map(ledgerShardId).toList
      val allGroups = CoordGroup :: shardGroups
      val clusterCfg = ClusterConfig(cfg.hostIds.toSet)
      val compaction = CompactionConfig(
        level = CompactionLevel.Custom,
        snapshotChunkSize = 512,
        retainedEntries = 0L,
        policy = Some(CompactionPolicy.threshold[IO](cfg.compactionThreshold))
      )

      for
        // Allocate MmapWalEngines FIRST so they outlive MultiRaftNode group fibers during teardown!
        engines <- allGroups
          .traverse { gid =>
            val dir = baseDir.resolve(id.value).resolve(gid.value)
            MmapWalEngine
              .resource[IO](dir, segmentSize = cfg.walSegmentBytes)
              .map(gid -> _)
          }
          .map(_.toMap)

        grpcCfg = GrpcConfig(
          host = "127.0.0.1",
          port = port,
          maxRetries = 1,
          retryBackoff = 5.millis,
          maxRetryBackoff = 15.millis,
          maxInboundQueueSize = 4096
        )
        tCfg = TransportConfig(
          localId = id,
          bindAddress = s"127.0.0.1:$port",
          peers = initialPeers,
          connectionTimeout = 300.millis
        )
        coalescing = CoalescedHeartbeatConfig(
          enabled = true,
          flushInterval = 12.millis,
          maxBatchSize = 32,
          coalesceResponses = true
        )
        pair <- GrpcTransport.multiGroupResource[IO](
          grpcConfig = grpcCfg,
          transportConfig = tCfg,
          coalescing = coalescing,
          maxGroupQueueSize = 1024
        )
        (rawGrpc, mg) = pair
        boundAddr <- Resource.eval(rawGrpc.localAddress)
        boundPort = boundAddr.split(":").last.toInt
        coordinator <- MultiRaftNode.resource[IO](id, mg)

        coordSm <- Resource.eval(CoordStateMachine.create)
        shardSms <- Resource.eval(
          shardGroups
            .traverse(gid => LedgerShardStateMachine.create.map(gid -> _))
            .map(_.toMap)
        )

        _ <- Resource.eval {
          allGroups.traverse_ { gid =>
            val engine = engines(gid)
            val sm: StateMachine[IO, ?] =
              if gid == CoordGroup then coordSm else shardSms(gid)
            for
              timerSvc <- DefaultTimerService[IO]
              rc = RaftConfig(
                localId = id,
                electionTimeoutMin = 350.millis,
                electionTimeoutMax = 700.millis,
                heartbeatInterval = 45.millis,
                checkQuorumEnabled = true,
                leaseReadEnabled = true,
                batching = BatchConfig(
                  enabled = true,
                  maxSize = 16,
                  maxWait = 5.millis
                ),
                pipelining = PipelineConfig(enabled = true, maxInflight = 6),
                compaction = compaction
              )
              _ <- coordinator.createGroup(
                gid,
                GroupConfig(
                  raftConfig = rc,
                  cluster = clusterCfg,
                  logStore = StorageBackedLogStore(engine),
                  stableStore = StorageBackedStableStore(engine),
                  stateMachine = sm,
                  timerService = timerSvc,
                  snapshotStore = Some(StorageBackedSnapshotStore(engine))
                )
              )
            yield ()
          }
        }
      yield HostRuntime(
        id = id,
        port = boundPort,
        address = boundAddr,
        grpc = rawGrpc,
        mgTransport = mg,
        coordinator = coordinator,
        engines = engines,
        shardSms = shardSms,
        coordSm = coordSm
      )

    def resource(
        cfg: LedgerClusterConfig = LedgerClusterConfig()
    ): Resource[IO, ShardedLedgerCluster] =
      Resource
        .make(
          for
            baseDir <- IO(Files.createTempDirectory("raft-sharded-ledger-"))
            hostsRef <- Ref.of[IO, Map[NodeId, (HostRuntime, IO[Unit])]](
              Map.empty
            )
            peerAddrsRef <- Ref.of[IO, Map[NodeId, String]](Map.empty)
            _ <- cfg.hostIds
              .traverse_ { id =>
                allocateHost(id, port = 0, baseDir, cfg, Map.empty).allocated
                  .flatMap(pair => hostsRef.update(_.updated(id, pair)))
              }
              .onError { _ =>
                hostsRef.get
                  .flatMap(_.values.toList.traverse_(_._2.attempt.void))
                  .guarantee(IO(deleteRecursive(baseDir)))
              }
            liveMap <- hostsRef.get
            addrs = liveMap.view.mapValues(_._1.address).toMap
            _ <- peerAddrsRef.set(addrs)
            _ <- liveMap.values.toList.traverse_ { case (rt, _) =>
              rt.grpc.updatePeers(addrs - rt.id)
            }
          yield (
            new ShardedLedgerCluster(cfg, baseDir, hostsRef, peerAddrsRef),
            hostsRef,
            baseDir
          )
        ) { case (_, hostsRef, baseDir) =>
          hostsRef.get
            .flatMap(_.values.toList.traverse_(_._2.attempt.void))
            .guarantee(IO(deleteRecursive(baseDir)))
        }
        .map(_._1)

  // ─────────────────────────────────────────────────────────────────────────
  // 5. Runnable CLI Demo (`sbt "examples/runMain examples.advanced.ShardedBankLedgerExample"`)
  // ─────────────────────────────────────────────────────────────────────────

  override def run: IO[Unit] =
    ShardedLedgerCluster.resource().use { cluster =>
      val initialAccounts =
        (1 to 6).map(i => s"acct-$i" -> 1000L)
      for
        _ <- IO.println("═" * 72)
        _ <- IO.println(
          "  SHARDED BANK LEDGER (Multi-Raft + gRPC + MmapWal + 2PC + Sessions)"
        )
        _ <- IO.println("═" * 72)

        _ <- IO.println(
          "\n1. Batch-seeding 6 accounts (1,000 each = 6,000 total) across 3 shards..."
        )
        _ <- cluster.openAccountsBatch(initialAccounts)
        _ <- cluster.rebalanceShards
        _ <- IO.println(
          "   ✓ Accounts seeded and shard leadership balanced across host-1..3"
        )

        _ <- IO.println(
          "\n2. Executing concurrent cross-shard & same-shard atomic transfers..."
        )
        t1 <- cluster.transfer("cli-1", 1L, "acct-1", "acct-2", 250L)
        t2 <- cluster.transfer("cli-2", 1L, "acct-3", "acct-5", 400L)
        // Intentional overdraft -> 2PC Prepare rejects and cleanly releases holds!
        tOverdraft <- cluster.transfer("cli-3", 1L, "acct-1", "acct-6", 5000L)
        // Duplicate retry with identical (clientId, seqNum) -> idempotent!
        dup <- cluster.deposit("cli-pos", 1L, "acct-4", 150L)
        dupRetry <- cluster.deposit("cli-pos", 1L, "acct-4", 150L)

        _ <- IO.println(s"   Transfer acct-1 -> acct-2 (250): $t1")
        _ <- IO.println(s"   Transfer acct-3 -> acct-5 (400): $t2")
        _ <- IO.println(s"   Overdraft acct-1 -> acct-6 (5000): $tOverdraft")
        _ <- IO.println(
          s"   Idempotent deposit retry match: ${dup == dupRetry} ($dupRetry)"
        )

        audit <- cluster.auditGlobalBalances
        stats <- cluster.coalescerTelemetry
        _ <- IO.println("\n3. Linearizable Global Audit:")
        _ <- IO.println(
          s"   Total Balance (expected 6,150): ${audit.totalBalance}"
        )
        _ <- IO.println(
          s"   Active Holds: ${audit.activeHoldsCount} | Completed 2PC: ${audit.completed2PCCount} | Aborted 2PC: ${audit.aborted2PCCount}"
        )
        _ <- IO.println(
          s"   Coalesced Heartbeats Queued: ${stats.map(_.heartbeatsQueued).sum}"
        )
        _ <- IO.println("\n✅ Advanced Sharded Bank Ledger Demo Complete!")
      yield ()
    }
