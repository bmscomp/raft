package raft.storage.mmap

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import raft.raw.{LightReady, Ready}
import raft.storage.EngineBackedRaftStorage
import raft.state.{
  Bytes,
  ClusterConfig,
  HardState,
  Log,
  LogIndex,
  NodeId,
  Snapshot
}

/** Stateful ScalaCheck model-checking suite for [[MmapWalEngine]] and
  * [[EngineBackedRaftStorage]] (**fixes E2E4**).
  *
  * Executes random multi-step command traces against tiny `512`-byte FFM `.wal`
  * segments (forcing frequent segment rotations) and asserts exact
  * observational equivalence against a pure immutable reference model after
  * every transition, including torn uncommitted tail injection and cold
  * close/reopen cycles.
  */
class MmapWalEngineStatefulPropertySpec
    extends AnyFreeSpec
    with Matchers
    with ScalaCheckPropertyChecks:

  private def deleteRecursive(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val s = Files.list(path)
        try s.iterator().forEachRemaining(deleteRecursive)
        finally s.close()
      val _ = Files.deleteIfExists(path)

  private enum WalCmd:
    case AppendBatch(count: Int, termDelta: Int, payloadSize: Int)
    case TruncateFromOffset(backFromEnd: Int)
    case DiscardBeforeOffset(advanceFromStart: Int)
    case SaveSnap(withConfig: Boolean, payloadBytes: Int)
    case UpdateHardState(termDelta: Int, voteIdx: Int, commitOffset: Int)
    case ClearAndReanchor(nextBase: Long, count: Int, term: Long)
    case InjectTornUncommittedTail
    case CloseAndReopen

  private final case class RefSnapshot(
      index: Long,
      term: Long,
      config: Option[ClusterConfig],
      payload: Bytes
  )

  private final case class RefModel(
      entries: Map[Long, Log] = Map.empty,
      nextAppendIndex: Long = 1L,
      currentTerm: Long = 1L,
      hardState: HardState = HardState.empty,
      snapshot: Option[RefSnapshot] = None
  ):
    def sortedIndices: Vector[Long] = entries.keys.toVector.sorted
    def firstIdxOpt: Option[Long] = sortedIndices.headOption
    def lastIdx: Long = sortedIndices.lastOption.getOrElse(0L)

  private val cmdGen: Gen[WalCmd] = Gen.frequency(
    32 -> (for
      cnt <- Gen.choose(1, 6)
      td <- Gen.choose(0, 1)
      sz <- Gen.choose(4, 48)
    yield WalCmd.AppendBatch(cnt, td, sz)),
    14 -> Gen.choose(1, 5).map(WalCmd.TruncateFromOffset.apply),
    14 -> Gen.choose(1, 5).map(WalCmd.DiscardBeforeOffset.apply),
    12 -> (for
      withCfg <- Gen.oneOf(true, false)
      sz <- Gen.choose(0, 128)
    yield WalCmd.SaveSnap(withCfg, sz)),
    10 -> (for
      td <- Gen.choose(0, 2)
      vi <- Gen.choose(0, 3)
      co <- Gen.choose(0, 4)
    yield WalCmd.UpdateHardState(td, vi, co)),
    6 -> (for
      jump <- Gen.choose(10L, 40L)
      cnt <- Gen.choose(2, 5)
      t <- Gen.choose(2L, 9L)
    yield WalCmd.ClearAndReanchor(jump, cnt, t)),
    6 -> Gen.const(WalCmd.InjectTornUncommittedTail),
    6 -> Gen.const(WalCmd.CloseAndReopen)
  )

  private val traceGen: Gen[List[WalCmd]] =
    Gen.choose(20, 32).flatMap(n => Gen.listOfN(n, cmdGen))

  private val voterChoices: Vector[Option[NodeId]] =
    Vector(None, Some(NodeId("n1")), Some(NodeId("n2")), Some(NodeId("n3")))

  private val sampleCluster: ClusterConfig =
    ClusterConfig(Set(NodeId("n1"), NodeId("n2"), NodeId("n3")))

  "MmapWalEngine & EngineBackedRaftStorage Stateful Property Suite (E2E4)" - {

    "should maintain exact observational equivalence with pure RefModel across random rotations, compactions, truncations, torn tails, and restarts" in {
      implicit val generatorDrivenConfig: PropertyCheckConfiguration =
        PropertyCheckConfiguration(minSuccessful = 30)

      forAll(traceGen) { commands =>
        val dir = Files.createTempDirectory("raft-wal-stateful-prop-")
        val segSize = 512L

        def assertEquivalent(
            engine: MmapWalEngine[IO],
            model: RefModel
        ): IO[Unit] =
          for
            actualLast <- engine.lastLogIndex
            actualEmpty <- engine.logIsEmpty
            actualHs <- engine.readHardState
            actualSnapOpt <- engine.loadSnapshot
            actualSnapBytesOpt <- actualSnapOpt.traverse(
              _.data.compile.to(Array).map(Bytes.unsafeWrap)
            )
            fullRange <-
              if model.entries.isEmpty then IO.pure(Vector.empty[Log])
              else
                engine.readRange(
                  LogIndex.unsafeFrom(model.firstIdxOpt.get),
                  LogIndex.unsafeFrom(model.lastIdx)
                )
            pointChecks <- model.sortedIndices.takeRight(6).traverse { idx =>
              for
                eOpt <- engine.readEntry(LogIndex.unsafeFrom(idx))
                tOpt <- engine.termAt(LogIndex.unsafeFrom(idx))
              yield (idx, eOpt, tOpt.map(_.value))
            }
          yield
            actualLast.value shouldBe model.lastIdx
            actualEmpty shouldBe model.entries.isEmpty
            actualHs shouldBe model.hardState
            val expectedRange = model.sortedIndices.map(model.entries)
            fullRange shouldBe expectedRange
            pointChecks.foreach { case (idx, eOpt, tOpt) =>
              eOpt shouldBe Some(model.entries(idx))
              tOpt shouldBe Some(model.entries(idx).term)
            }
            model.snapshot match
              case None =>
                actualSnapOpt shouldBe None
              case Some(expSnap) =>
                actualSnapOpt.isDefined shouldBe true
                actualSnapOpt.get.lastIncludedIndex shouldBe expSnap.index
                actualSnapOpt.get.lastIncludedTerm shouldBe expSnap.term
                actualSnapOpt.get.config shouldBe expSnap.config
                actualSnapBytesOpt.get shouldBe expSnap.payload

        val runTrace = for
          engineRef <- MmapWalEngine
            .open[IO](dir, segmentSize = segSize)
            .flatMap(Ref.of[IO, MmapWalEngine[IO]])
          modelRef <- Ref.of[IO, RefModel](RefModel())
          _ <- commands.traverse_ { cmd =>
            for
              eng <- engineRef.get
              m <- modelRef.get
              nextModel <- cmd match
                case WalCmd.AppendBatch(cnt, td, sz) =>
                  val nextTerm = m.currentTerm + td.toLong
                  val startIdx =
                    if m.entries.isEmpty then m.nextAppendIndex
                    else m.lastIdx + 1L
                  val batch = (0 until cnt).toVector.map { off =>
                    val idx = startIdx + off.toLong
                    val payload = Array.tabulate[Byte](sz)(b =>
                      ((idx.toInt + b) & 0x7f).toByte
                    )
                    Log.command(idx, nextTerm, payload)
                  }
                  eng
                    .appendEntries(batch)
                    .as(
                      m.copy(
                        entries = m.entries ++ batch.map(l => l.index -> l),
                        nextAppendIndex = startIdx + cnt.toLong,
                        currentTerm = nextTerm
                      )
                    )

                case WalCmd.TruncateFromOffset(back) =>
                  val indices = m.sortedIndices
                  if indices.isEmpty then IO.pure(m)
                  else
                    val floor = m.hardState.commitIndex + 1L
                    val rawTarget = indices(
                      math.max(0, indices.length - back)
                    )
                    val fromIdx = math.max(1L, math.max(floor, rawTarget))
                    if fromIdx > m.lastIdx then IO.pure(m)
                    else
                      eng
                        .truncateFrom(LogIndex.unsafeFrom(fromIdx))
                        .as {
                          val kept = m.entries.filter(_._1 < fromIdx)
                          m.copy(
                            entries = kept,
                            nextAppendIndex =
                              kept.keys.maxOption.map(_ + 1L).getOrElse(fromIdx)
                          )
                        }

                case WalCmd.DiscardBeforeOffset(adv) =>
                  val indices = m.sortedIndices
                  if indices.size <= 1 then IO.pure(m)
                  else
                    val cutPos = math.min(indices.length - 1, adv)
                    val cutIdx = indices(cutPos)
                    eng
                      .discardBefore(LogIndex.unsafeFrom(cutIdx))
                      .as {
                        val kept = m.entries.filter(_._1 >= cutIdx)
                        m.copy(entries = kept)
                      }

                case WalCmd.SaveSnap(withCfg, pBytes) =>
                  val snapIdx =
                    if m.entries.nonEmpty then m.lastIdx
                    else m.nextAppendIndex
                  val snapTerm = m.entries
                    .get(snapIdx)
                    .map(_.term)
                    .getOrElse(m.currentTerm)
                  val cfgOpt = if withCfg then Some(sampleCluster) else None
                  val raw = Array.tabulate[Byte](pBytes)(i => (i % 97).toByte)
                  val snap = Snapshot[IO](
                    lastIncludedIndex = snapIdx,
                    lastIncludedTerm = snapTerm,
                    data = Stream.chunk(Chunk.array(raw)).covary[IO],
                    config = cfgOpt
                  )
                  eng
                    .saveSnapshot(snap)
                    .as(
                      m.copy(
                        snapshot = Some(
                          RefSnapshot(
                            snapIdx,
                            snapTerm,
                            cfgOpt,
                            Bytes.copyFrom(raw)
                          )
                        )
                      )
                    )

                case WalCmd.UpdateHardState(td, vi, co) =>
                  val nextTerm = math.max(m.hardState.term, m.currentTerm + td)
                  val vf = voterChoices(vi % voterChoices.length)
                  val maxCommit =
                    if m.entries.nonEmpty then m.lastIdx
                    else m.hardState.commitIndex
                  val nextCommit = math.min(
                    maxCommit,
                    m.hardState.commitIndex + co.toLong
                  )
                  val hs = HardState(nextTerm, vf, nextCommit)
                  eng
                    .writeHardState(hs)
                    .as(m.copy(currentTerm = nextTerm, hardState = hs))

                case WalCmd.ClearAndReanchor(jump, cnt, t) =>
                  val nextTerm = math.max(m.currentTerm, t)
                  val startIdx = math.max(m.nextAppendIndex, m.lastIdx + jump)
                  val batch = (0 until cnt).toVector.map { off =>
                    val idx = startIdx + off.toLong
                    Log.command(
                      idx,
                      nextTerm,
                      s"reanchor-$idx".getBytes(StandardCharsets.UTF_8)
                    )
                  }
                  (eng.clearLog *> eng.appendEntries(batch)).as(
                    m.copy(
                      entries = batch.map(l => l.index -> l).toMap,
                      nextAppendIndex = startIdx + cnt.toLong,
                      currentTerm = nextTerm,
                      hardState =
                        m.hardState.copy(commitIndex = 0L, term = nextTerm)
                    )
                  ) *> {
                    val resetHs =
                      m.hardState.copy(commitIndex = 0L, term = nextTerm)
                    eng
                      .writeHardState(resetHs)
                      .as(
                        m.copy(
                          entries = batch.map(l => l.index -> l).toMap,
                          nextAppendIndex = startIdx + cnt.toLong,
                          currentTerm = nextTerm,
                          hardState = resetHs
                        )
                      )
                  }

                case WalCmd.InjectTornUncommittedTail =>
                  val uncommitted =
                    m.sortedIndices.filter(_ > m.hardState.commitIndex)
                  if uncommitted.isEmpty then IO.pure(m)
                  else
                    val victimIdx = uncommitted.last
                    for
                      _ <- eng.close
                      didTear <- IO {
                        val walFiles = {
                          val s = Files.list(dir.resolve("wal"))
                          try
                            s.iterator()
                              .asScalaList
                              .sortBy(_.getFileName.toString)
                          finally s.close()
                        }
                        val lastSegPath = walFiles.last
                        val roSeg =
                          MmapSegment.open(lastSegPath, readOnly = true)
                        val count = roSeg.currentEntryCount
                        val endOff = roSeg.currentWriteOffset
                        roSeg.close()
                        if count <= 0L then false
                        else
                          val ch =
                            FileChannel.open(
                              lastSegPath,
                              StandardOpenOption.WRITE
                            )
                          try
                            val _ = ch.truncate(math.max(32L, endOff - 5L))
                          finally ch.close()
                          true
                      }
                      reopened <- MmapWalEngine.open[IO](
                        dir,
                        segmentSize = segSize
                      )
                      _ <- engineRef.set(reopened)
                    yield
                      if !didTear then m
                      else
                        val kept = m.entries - victimIdx
                        m.copy(
                          entries = kept,
                          nextAppendIndex = victimIdx
                        )

                case WalCmd.CloseAndReopen =>
                  for
                    _ <- eng.close
                    reopened <- MmapWalEngine.open[IO](
                      dir,
                      segmentSize = segSize
                    )
                    _ <- engineRef.set(reopened)
                  yield m

              _ <- modelRef.set(nextModel)
              curEng <- engineRef.get
              _ <- assertEquivalent(curEng, nextModel)
            yield ()
          }
          finalEng <- engineRef.get
          _ <- finalEng.close
        yield ()

        try runTrace.unsafeRunSync()
        finally deleteRecursive(dir)
      }
    }

    "should exercise EngineBackedRaftStorage Ready/LightReady persistence, compaction, and cold recovery" in {
      val dir = Files.createTempDirectory("raft-engine-backed-prop-")
      val prog =
        MmapWalEngine.resource[IO](dir, segmentSize = 512L).use { engine =>
          for
            storage <- EngineBackedRaftStorage.open[IO](engine, sampleCluster)
            entries1 = (1L to 12L).toVector.map(i =>
              Log.command(i, 2L, s"cmd-$i".getBytes(StandardCharsets.UTF_8))
            )
            _ <- storage.persistReady(
              Ready(
                hardState = Some(HardState(2L, Some(NodeId("n1")), 8L)),
                entries = entries1
              )
            )
            _ <- storage.persistLightReady(LightReady(commitIndex = Some(10L)))
            _ <- storage.compactTo(
              8L,
              "snap-8".getBytes(StandardCharsets.UTF_8)
            )
            _ <- storage.persistReady(
              Ready(
                truncateFromIndex = Some(11L),
                entries = Vector(
                  Log.command(
                    11L,
                    3L,
                    "cmd-11b".getBytes(StandardCharsets.UTF_8)
                  )
                ),
                hardState = Some(HardState(3L, None, 11L))
              )
            )
          yield
            storage.firstIndex shouldBe 9L
            storage.lastIndex shouldBe 11L
            storage.hardState shouldBe HardState(3L, None, 11L)
            storage.term(8L) shouldBe Some(2L)
            storage.term(11L) shouldBe Some(3L)
            storage.entries(9L, 12L).map(_.index) shouldBe Vector(9L, 10L, 11L)
            storage.snapshotMetadata.map(_.lastIncludedIndex) shouldBe Some(8L)
            storage.clusterConfig shouldBe sampleCluster
            storage.initialState._1.commitIndex shouldBe 11L
        } *> MmapWalEngine.resource[IO](dir, segmentSize = 512L).use {
          reopened =>
            EngineBackedRaftStorage.open[IO](reopened, sampleCluster).map {
              rec =>
                rec.lastIndex shouldBe 11L
                rec.hardState shouldBe HardState(3L, None, 11L)
                rec.snapshotMetadata.map(_.lastIncludedIndex) shouldBe Some(8L)
            }
        }

      try prog.unsafeRunSync()
      finally deleteRecursive(dir)
    }

    "should cover WalIndexState legacy/corrupted deserializers, sparse SuperPages, and WalIndex facade" in {
      import java.nio.ByteBuffer

      WalIndexState
        .deserializeChecked(Array.emptyByteArray)
        .isLeft shouldBe true
      WalIndexState
        .deserializeChecked(Array[Byte](1, 2, 3))
        .isLeft shouldBe true

      // Truncated WIDX header & unsupported WIDX version & bad count
      val truncWidx =
        ByteBuffer.allocate(10).putInt(WalIndex.CheckpointMagic).array()
      WalIndexState.deserializeChecked(truncWidx).isLeft shouldBe true

      val badVerWidx = ByteBuffer
        .allocate(24)
        .putInt(WalIndex.CheckpointMagic)
        .put(99.toByte)
        .array()
      WalIndexState.deserializeChecked(badVerWidx).isLeft shouldBe true

      val badLenWidx = ByteBuffer
        .allocate(24)
        .putInt(WalIndex.CheckpointMagic)
        .put(WalIndex.CheckpointVersion)
        .putLong(1L)
        .putInt(5)
        .array()
      WalIndexState.deserializeChecked(badLenWidx).isLeft shouldBe true

      // Legacy v0: [count: Long][entries: count * 20]
      val legacyV0 = ByteBuffer
        .allocate(8 + 20)
        .putLong(1L)
        .putLong(7L)
        .putInt(2)
        .putLong(64L)
        .array()
      val decV0 = WalIndexState.deserializeChecked(legacyV0)
      decV0.map(_.getSegmentId(7L)) shouldBe Right(2)

      // Legacy v0.5: [baseIdx: Long][count: Int][entries: count * 20]
      val legacyV05 = ByteBuffer
        .allocate(12 + 20)
        .putLong(5L)
        .putInt(1)
        .putLong(5L)
        .putInt(3)
        .putLong(128L)
        .array()
      WalIndexState
        .deserializeChecked(legacyV05)
        .map(_.getFileOffset(5L)) shouldBe Right(128L)
      WalIndexState.deserializeChecked(new Array[Byte](15)).isLeft shouldBe true
      WalIndexState
        .deserializeChecked(Array[Byte](0, 0, 0, 0, 0, 0, 0, 99, 1))
        .isLeft shouldBe true

      // Sparse multi-SuperPage scanning & WalIndex lock-free facade
      val facade = new WalIndex()
      facade.put(10L, 1, 32L)
      facade.put(5000L, 2, 64L)
      facade.put(12500L, 3, 96L)
      facade.snapshot.size shouldBe 3
      facade.get(5000L) shouldBe Some(facade.Location(2, 64L))
      facade.get(999L) shouldBe None
      facade.truncateFrom(9000L)
      facade.lastIndex shouldBe 5000L
      facade.discardBefore(2000L)
      facade.snapshot.firstIndex shouldBe 5000L
      facade.clear()
      facade.isEmpty shouldBe true
    }
  }

  extension [A](it: java.util.Iterator[A])
    private def asScalaList: List[A] =
      val b = List.newBuilder[A]
      while it.hasNext do b += it.next()
      b.result()
