package raft.storage.mmap

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class WalIndexSpec extends AnyFreeSpec with Matchers:

  "WalIndex" - {
    "should start empty" in {
      val idx = new WalIndex
      idx.isEmpty shouldBe true
      idx.lastIndex shouldBe 0L
      idx.size shouldBe 0
      idx.get(1L) shouldBe None
    }

    "put and get" - {
      "should store and retrieve a single entry" in {
        val idx = new WalIndex
        idx.put(1L, 1, 24L)
        idx.get(1L) shouldBe Some(idx.Location(1, 24L))
        idx.isEmpty shouldBe false
        idx.lastIndex shouldBe 1L
        idx.size shouldBe 1
      }

      "should store entries across multiple segments" in {
        val idx = new WalIndex
        idx.put(1L, 1, 24L)
        idx.put(2L, 1, 60L)
        idx.put(3L, 2, 24L)
        idx.put(4L, 2, 80L)

        idx.size shouldBe 4
        idx.lastIndex shouldBe 4L
        idx.get(1L).get.segmentId shouldBe 1
        idx.get(3L).get.segmentId shouldBe 2
        idx.get(4L).get.fileOffset shouldBe 80L
      }

      "should track lastIndex correctly with non-sequential inserts" in {
        val idx = new WalIndex
        idx.put(5L, 1, 100L)
        idx.lastIndex shouldBe 5L
        idx.put(3L, 1, 50L)
        idx.lastIndex shouldBe 5L // should not decrease
        idx.put(10L, 2, 200L)
        idx.lastIndex shouldBe 10L
      }

      "should overwrite existing entries" in {
        val idx = new WalIndex
        idx.put(1L, 1, 24L)
        idx.put(1L, 2, 48L) // overwrite
        idx.get(1L) shouldBe Some(idx.Location(2, 48L))
        idx.size shouldBe 1
      }
    }

    "truncateFrom" - {
      "should remove entries at and after the given index" in {
        val idx = new WalIndex
        (1L to 10L).foreach(i => idx.put(i, 1, i * 30))
        idx.truncateFrom(6L)

        idx.size shouldBe 5
        idx.lastIndex shouldBe 5L
        idx.get(5L) shouldBe defined
        idx.get(6L) shouldBe None
        idx.get(10L) shouldBe None
      }

      "should result in empty index when truncating from 1" in {
        val idx = new WalIndex
        (1L to 5L).foreach(i => idx.put(i, 1, i * 30))
        idx.truncateFrom(1L)

        idx.isEmpty shouldBe true
        idx.lastIndex shouldBe 0L
        idx.size shouldBe 0
      }

      "should be a no-op when truncating beyond last index" in {
        val idx = new WalIndex
        (1L to 3L).foreach(i => idx.put(i, 1, i * 30))
        idx.truncateFrom(10L)

        idx.size shouldBe 3
        idx.lastIndex shouldBe 3L
      }
    }

    "clear" - {
      "should remove all entries" in {
        val idx = new WalIndex
        (1L to 100L).foreach(i => idx.put(i, 1, i * 30))
        idx.clear()

        idx.isEmpty shouldBe true
        idx.lastIndex shouldBe 0L
        idx.size shouldBe 0
      }
    }

    "serialization" - {
      "should round-trip through serialize and deserialize" in {
        val idx = new WalIndex
        idx.put(1L, 1, 24L)
        idx.put(2L, 1, 60L)
        idx.put(3L, 2, 24L)
        idx.put(100L, 5, 1000L)

        val bytes = idx.serialize
        val restored = WalIndex.deserialize(bytes)

        restored.size shouldBe 4
        restored.lastIndex shouldBe 100L
        restored.get(1L) shouldBe Some(restored.Location(1, 24L))
        restored.get(2L) shouldBe Some(restored.Location(1, 60L))
        restored.get(3L) shouldBe Some(restored.Location(2, 24L))
        restored.get(100L) shouldBe Some(restored.Location(5, 1000L))
      }

      "should round-trip an empty index" in {
        val idx = new WalIndex
        val bytes = idx.serialize
        val restored = WalIndex.deserialize(bytes)

        restored.isEmpty shouldBe true
        restored.size shouldBe 0
      }

      "should round-trip a large index" in {
        val idx = new WalIndex
        (1L to 1000L).foreach(i => idx.put(i, (i / 100).toInt + 1, i * 40))

        val bytes = idx.serialize
        val restored = WalIndex.deserialize(bytes)

        restored.size shouldBe 1000
        restored.lastIndex shouldBe 1000L
        restored.get(500L).get.fileOffset shouldBe 500L * 40
      }
    }
  }
