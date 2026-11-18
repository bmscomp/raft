package examples

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import examples.advanced.ShardedBankLedgerExample
import examples.advanced.ShardedBankLedgerExample.{
  LedgerClusterConfig,
  LedgerResponse,
  ShardedLedgerCluster
}
import raft.state.NodeId

/** Whole end-to-end test suite for the flagship
  * [[examples.advanced.ShardedBankLedgerExample]] production platform:
  *   - Multi-shard group-commit batch provisioning & shard leader rebalancing
  *   - Concurrent cross-shard Two-Phase Commit (2PC) & same-shard transfers
  *     with strict global conservation of money and zero leaked escrow holds
  *   - Exactly-once `(clientId, seqNum)` deduplication across `MmapWalEngine`
  *     snapshot compaction and `transferShardLeader`
  *   - Mid-flight 2PC crash recovery (`Preparing` rollback & `Committed`
  *     roll-forward) + physical host crash & restart from on-disk
  *     `MmapWalEngine`
  */
class AdvancedShardedLedgerE2ESpec extends AnyFlatSpec with Matchers:

  private def awaitUntil(
      cond: IO[Boolean],
      retries: Int = 350,
      delay: FiniteDuration = 20.millis
  ): IO[Unit] =
    cond.flatMap {
      case true  => IO.unit
      case false =>
        if retries <= 0 then
          IO.raiseError(new RuntimeException("Ledger E2E condition timed out"))
        else IO.sleep(delay) *> awaitUntil(cond, retries - 1, delay)
    }

  private def distinctShardPair(
      accounts: Seq[String],
      shardCount: Int
  ): (String, String) =
    val byShard = accounts.groupBy(a =>
      ShardedBankLedgerExample.shardForAccount(a, shardCount)
    )
    val keys = byShard.keys.toVector.sortBy(_.value)
    (byShard(keys(0)).head, byShard(keys(1)).head)

  "ShardedBankLedgerExample" should "preserve exact global conservation of money across concurrent cross-shard 2PC transfers, same-shard transfers, and overdraft rollbacks" in {
    val accounts = (1 to 12).map(i => s"acct-$i" -> 1000L)
    val initialTotal = accounts.map(_._2).sum // 12,000L

    val prog = ShardedLedgerCluster.resource().use { cluster =>
      for
        // 1. Seed 12 accounts via per-shard proposeBatchAndCommitToGroup
        _ <- cluster.openAccountsBatch(accounts)
        _ <- cluster.rebalanceShards

        // 2. Run 6 concurrent client fibers x 4 transfers = 24 concurrent transfers (mixing valid transfers & intentional overdrafts)
        _ <- (1 to 6).toList.parTraverse_ { cid =>
          (1 to 4).toList.traverse_ { step =>
            val fromIdx = ((cid * 3 + step) % 12) + 1
            val toIdx = ((cid * 5 + step + 1) % 12) + 1
            val safeToIdx = if toIdx == fromIdx then (toIdx % 12) + 1 else toIdx
            // Every 4th step attempts an overdraft of 50,000 so 2PC Prepare must reject & release holds!
            val amount = if step == 4 then 50000L else (25L * step)
            cluster
              .transfer(
                clientId = s"concurrent-cli-$cid",
                seqNum = step.toLong,
                fromAccount = s"acct-$fromIdx",
                toAccount = s"acct-$safeToIdx",
                amount = amount
              )
              .void
          }
        }

        // 3. Linearizable global audit across tx-coord and all 3 ledger shards
        audit <- cluster.auditGlobalBalances
        _ <- awaitUntil(cluster.allReplicasConverged)
        telemetry <- cluster.coalescerTelemetry
      yield {
        audit.totalBalance shouldBe initialTotal
        audit.activeHoldsCount shouldBe 0
        audit.completed2PCCount should be > 0
        audit.aborted2PCCount should be > 0
        telemetry.map(_.heartbeatsQueued).sum should be > 5L
      }
    }

    prog.timeout(90.seconds).unsafeRunSync()
  }

  it should "guarantee exactly-once client session idempotency across MmapWalEngine snapshot compaction and shard leadership transfer" in {
    val cfg = LedgerClusterConfig(
      walSegmentBytes = 2048L,
      compactionThreshold = 6L
    )
    val accountNames = (1 to 9).map(i => s"idemp-acct-$i")
    val (crossFrom, crossTo) =
      distinctShardPair(accountNames, cfg.shardCount)

    val prog = ShardedLedgerCluster.resource(cfg).use { cluster =>
      for
        _ <- cluster.openAccountsBatch(accountNames.map(_ -> 1000L))

        // 1. Execute initial single-shard withdrawal and cross-shard 2PC transfer
        w1 <- cluster.withdraw("pos-terminal-A", 1L, crossFrom, 150L)
        _ = w1 shouldBe LedgerResponse.BalanceUpdated(crossFrom, 850L)

        x1 <- cluster.transfer("pos-terminal-B", 1L, crossFrom, crossTo, 200L)
        _ = x1 shouldBe a[LedgerResponse.Transferred]

        // 2. Drive >8 deposits on crossFrom's shard so MmapWalEngine compacts the WAL into a snapshot
        targetShard = ShardedBankLedgerExample.shardForAccount(
          crossFrom,
          cfg.shardCount
        )
        _ <- (1 to 10).toList.traverse_ { seq =>
          cluster.deposit("compaction-driver", seq.toLong, crossFrom, 10L)
        }
        // Expected crossFrom balance: 1000 - 150 - 200 + (10 * 10) = 750
        // Expected crossTo balance:   1000 + 200 = 1200

        _ <- awaitUntil(
          cfg.hostIds.existsM(hid =>
            cluster.shardSnapshotCount(hid, targetShard).map(_ > 0)
          )
        )

        // 3. Transfer shard leadership across hosts so a DIFFERENT leader serves the duplicate retries!
        _ <- cluster.transferShardLeader(targetShard, NodeId("host-2"))
        _ <- cluster.transferShardLeader(targetShard, NodeId("host-3"))

        // 4. Re-submit duplicate (clientId, seqNum) requests after compaction + leader failover!
        wDup <- cluster.withdraw("pos-terminal-A", 1L, crossFrom, 150L)
        xDup <- cluster.transfer("pos-terminal-B", 1L, crossFrom, crossTo, 200L)

        balFrom <- cluster.linearizableBalance(crossFrom)
        balTo <- cluster.linearizableBalance(crossTo)
      yield {
        // Cached session response returned; zero double-debit or double-credit!
        wDup shouldBe LedgerResponse.BalanceUpdated(crossFrom, 850L)
        xDup shouldBe a[LedgerResponse.Transferred]
        balFrom shouldBe Some(750L)
        balTo shouldBe Some(1200L)
      }
    }

    prog.timeout(90.seconds).unsafeRunSync()
  }

  it should "recover interrupted Preparing & Committed 2PC transactions across a hard physical host crash and converge restarted MmapWalEngine replicas" in {
    val cfg = LedgerClusterConfig(
      walSegmentBytes = 4096L,
      compactionThreshold = 12L
    )
    val accountNames = (1 to 9).map(i => s"rec-acct-$i")
    val byShard = accountNames
      .groupBy(a => ShardedBankLedgerExample.shardForAccount(a, cfg.shardCount))
      .values
      .toVector
    val a0 = byShard(0)(0)
    val b0 = byShard(1)(0)
    val a1 = byShard(0)(1)
    val b1 = byShard(1)(1)
    val initialTotal = accountNames.size * 1000L // 9,000L

    val prog = ShardedLedgerCluster.resource(cfg).use { cluster =>
      for
        _ <- cluster.openAccountsBatch(accountNames.map(_ -> 1000L))
        _ <- awaitUntil(cluster.allReplicasConverged)

        // 1. Inject two interrupted mid-flight 2PC transactions:
        //    - tx-prep-stuck: stuck in `Preparing` with 300 escrowed on a0 (must ABORT & release hold)
        //    - tx-commit-stuck: `DecideCommit` durable in tx-coord & 250 debited on a1, but b1 not yet credited (must ROLL FORWARD)
        _ <- cluster.injectInterrupted2PC(
          txId = "tx-prep-stuck",
          fromAccount = a0,
          toAccount = b0,
          amount = 300L,
          stopAfterDecisionCommit = false
        )
        _ <- cluster.injectInterrupted2PC(
          txId = "tx-commit-stuck",
          fromAccount = a1,
          toAccount = b1,
          amount = 250L,
          stopAfterDecisionCommit = true
        )

        // 2. Crash physical host-1 completely (shutting down its MultiRaftNode, gRPC server, and MmapWalEngines)
        _ <- cluster.stopHost(NodeId("host-1"))
        liveDuringCrash <- cluster.liveHostIds
        _ = liveDuringCrash should contain theSameElementsAs List(
          NodeId("host-2"),
          NodeId("host-3")
        )

        // 3. Run deterministic 2PC crash recovery on the surviving {host-2, host-3} quorum!
        recoveredCount <- cluster.recoverInFlightTransactions
        _ = recoveredCount shouldBe 2

        // Also execute a fresh transfer while host-1 is offline
        _ <- cluster.transfer("post-crash-cli", 1L, b0, a0, 100L)

        auditDuringOutage <- cluster.auditGlobalBalances
        _ = auditDuringOutage.totalBalance shouldBe initialTotal
        _ = auditDuringOutage.activeHoldsCount shouldBe 0

        // 4. Restart host-1 on its SAME TCP port and EXISTING on-disk MmapWalEngine directories!
        _ <- cluster.restartHost(NodeId("host-1"))
        liveAfterRestart <- cluster.liveHostIds
        _ = liveAfterRestart should have size 3

        // Verify all 3 physical hosts converge bit-for-bit and balances match exact recovery semantics!
        _ <- awaitUntil(cluster.allReplicasConverged, retries = 450)
        balA0 <- cluster.linearizableBalance(a0)
        balB0 <- cluster.linearizableBalance(b0)
        balA1 <- cluster.linearizableBalance(a1)
        balB1 <- cluster.linearizableBalance(b1)
        finalAudit <- cluster.auditGlobalBalances
      yield {
        // tx-prep-stuck rolled back (0 net), plus post-crash b0 -> a0 (100): a0=1100, b0=900
        balA0 shouldBe Some(1100L)
        balB0 shouldBe Some(900L)
        // tx-commit-stuck rolled forward (a1 -> b1 for 250): a1=750, b1=1250
        balA1 shouldBe Some(750L)
        balB1 shouldBe Some(1250L)
        finalAudit.totalBalance shouldBe initialTotal
        finalAudit.activeHoldsCount shouldBe 0
      }
    }

    prog.timeout(90.seconds).unsafeRunSync()
  }

  it should "execute the standalone CLI demo run cleanly end-to-end" in {
    ShardedBankLedgerExample.run.timeout(60.seconds).unsafeRunSync()
  }
