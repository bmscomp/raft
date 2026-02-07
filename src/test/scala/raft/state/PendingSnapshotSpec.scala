package raft.state

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files as JFiles}

class PendingSnapshotSpec extends AnyFlatSpec with Matchers:

  "FilePendingSnapshot" should "create a temp file on disk" in {
    val test = for
      fps <- FilePendingSnapshot.create[IO](100L, 5L)
      exists <- IO(JFiles.exists(fps.tempFile))
      _ <- fps.cleanup
    yield
      exists shouldBe true
      fps.lastIncludedIndex shouldBe 100L
      fps.lastIncludedTerm shouldBe 5L
      fps.totalBytes shouldBe 0L

    test.unsafeRunSync()
  }

  it should "append chunks to the temp file" in {
    val test = for
      fps0 <- FilePendingSnapshot.create[IO](100L, 5L)
      fps1 <- fps0.appendChunk("hello".getBytes("UTF-8"))
      fps2 <- fps1.appendChunk(" world".getBytes("UTF-8"))
      fileSize <- IO(JFiles.size(fps2.tempFile))
      _ <- fps2.cleanup
    yield
      fps1.totalBytes shouldBe 5L
      fps1.offset shouldBe 5L
      fps2.totalBytes shouldBe 11L
      fps2.offset shouldBe 11L
      fileSize shouldBe 11L

    test.unsafeRunSync()
  }

  it should "produce a streaming Snapshot from the temp file" in {
    val test = for
      fps0 <- FilePendingSnapshot.create[IO](200L, 8L)
      fps1 <- fps0.appendChunk("chunk-one-".getBytes("UTF-8"))
      fps2 <- fps1.appendChunk("chunk-two".getBytes("UTF-8"))
      snapshot = fps2.toSnapshot
      data <- snapshot.data.compile.to(Array)
      _ <- fps2.cleanup
    yield
      snapshot.lastIncludedIndex shouldBe 200L
      snapshot.lastIncludedTerm shouldBe 8L
      new String(data) shouldBe "chunk-one-chunk-two"

    test.unsafeRunSync()
  }

  it should "handle empty snapshot (no chunks appended)" in {
    val test = for
      fps <- FilePendingSnapshot.create[IO](0L, 0L)
      snapshot = fps.toSnapshot
      data <- snapshot.data.compile.to(Array)
      _ <- fps.cleanup
    yield
      data.length shouldBe 0
      fps.totalBytes shouldBe 0L

    test.unsafeRunSync()
  }

  it should "cleanup removes the temp file" in {
    val test = for
      fps <- FilePendingSnapshot.create[IO](50L, 3L)
      _ <- fps.appendChunk("data".getBytes("UTF-8"))
      existsBefore <- IO(JFiles.exists(fps.tempFile))
      _ <- fps.cleanup
      existsAfter <- IO(JFiles.exists(fps.tempFile))
    yield
      existsBefore shouldBe true
      existsAfter shouldBe false

    test.unsafeRunSync()
  }

  it should "create in a specific directory" in {
    val test = for
      tmpDir <- IO(JFiles.createTempDirectory("raft-fps-test-"))
      fps <- FilePendingSnapshot.createIn[IO](tmpDir.resolve("snaps"), 100L, 5L)
      _ <- fps.appendChunk("test-data".getBytes("UTF-8"))
      parentExists <- IO(JFiles.exists(tmpDir.resolve("snaps")))
      fileInDir <- IO(fps.tempFile.getParent == tmpDir.resolve("snaps"))
      _ <- fps.cleanup
      _ <- IO(JFiles.deleteIfExists(tmpDir.resolve("snaps")))
      _ <- IO(JFiles.deleteIfExists(tmpDir))
    yield
      parentExists shouldBe true
      fileInDir shouldBe true

    test.unsafeRunSync()
  }

  it should "handle large chunks without heap issues" in {
    val test = for
      fps0 <- FilePendingSnapshot.create[IO](100L, 5L)
      // Write 10 chunks of 64KB each = 640KB total
      fps <- (0 until 10).foldLeft(IO.pure(fps0)) { (accF, _) =>
        accF.flatMap(_.appendChunk(new Array[Byte](65536)))
      }
      snapshot = fps.toSnapshot
      size <- snapshot.data.compile.count
      _ <- fps.cleanup
    yield
      fps.totalBytes shouldBe (10L * 65536)
      size shouldBe (10L * 65536)

    test.unsafeRunSync()
  }
