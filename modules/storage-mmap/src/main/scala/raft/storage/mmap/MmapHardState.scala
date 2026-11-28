package raft.storage.mmap

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, StandardOpenOption}
import java.util.Arrays
import scala.util.control.NonFatal

import raft.state.{NodeId, RaftError}
import raft.storage.HardState

/** Crash-safe, double-buffered memory-mapped hard state persistence using JDK
  * Foreign Function & Memory (`MemorySegment` / `Arena`).
  *
  * Persists Raft's durable `HardState` (`currentTerm`, `votedFor`, and
  * `commitIndex`) into two alternating 288-byte ping-pong slots (`576` bytes
  * total) in `hard_state.bin`. Every write targets only the inactive slot with
  * a monotonically increasing `generation` counter and CRC32 checksum before
  * calling `msync` (`mapped.force()`).
  *
  * On crash mid-write (torn slot), `read` detects the CRC mismatch in the torn
  * slot and automatically recovers the intact previous generation from the
  * sibling slot instead of bricking the node. If both slots are corrupted (or
  * the sole written slot suffers bit-rot), `read` fails fast with
  * [[raft.state.RaftError.StorageCorruption]].
  *
  * Per-slot binary layout (`288` bytes, native byte order):
  * {{{
  *   Offset  Size   Field
  *   0       8      currentTerm (Long, >= 0)
  *   8       4      votedForLen (Int in [0..256], 0 = no vote)
  *   12      256    votedFor (UTF-8, zero-padded)
  *   268     4      crc32 (Int over bytes [0..268) ++ [272..288))
  *   272     8      commitIndex (Long, >= 0)
  *   280     8      generation (Long, >= 1 for written slots, 0 for empty)
  * }}}
  */
class MmapHardState private (
    private val mapped: MemorySegment,
    private val arena: Arena,
    private var lastGeneration: Long,
    private var lastSlot: Int
):
  import MmapHardState.*

  /** Read and checksum-verify the latest durable [[HardState]] from the two
    * ping-pong slots.
    *
    * @throws raft.state.RaftError.StorageCorruption
    *   if no valid slot exists and at least one slot is corrupted
    */
  def read: HardState =
    val s0 = decodeSlot(copySlot(0))
    val s1 = decodeSlot(copySlot(1))
    (s0, s1) match
      case (SlotResult.Empty, SlotResult.Empty) =>
        HardState.Empty
      case (SlotResult.Valid(g0, hs0), SlotResult.Valid(g1, hs1)) =>
        if g0 >= g1 then hs0 else hs1
      case (SlotResult.Valid(_, hs0), SlotResult.Empty) =>
        hs0
      case (SlotResult.Empty, SlotResult.Valid(_, hs1)) =>
        hs1
      case (SlotResult.Valid(_, hs0), SlotResult.Corrupted(_)) =>
        hs0
      case (SlotResult.Corrupted(_), SlotResult.Valid(_, hs1)) =>
        hs1
      case (SlotResult.Corrupted(reason), _) =>
        throw RaftError.StorageCorruption(reason)
      case (_, SlotResult.Corrupted(reason)) =>
        throw RaftError.StorageCorruption(reason)

  /** Atomically write `state` to the inactive ping-pong slot, zero-fill stale
    * bytes, update CRC32 + generation, and `msync` to durable storage.
    */
  def write(state: HardState): Unit =
    val votedBytes = state.votedFor match
      case Some(nodeId) =>
        val raw = nodeId.value.getBytes(StandardCharsets.UTF_8)
        require(
          raw.length <= MaxVotedForBytes,
          s"votedFor NodeId exceeds maximum $MaxVotedForBytes UTF-8 bytes (${raw.length})"
        )
        raw
      case None => Array.emptyByteArray

    require(
      state.commitIndex >= 0L,
      s"commitIndex must be >= 0, got ${state.commitIndex}"
    )

    val nextGen = lastGeneration + 1L
    val targetSlot = 1 - lastSlot
    val slotBase = targetSlot.toLong * SlotBytes

    val slotBuf = new Array[Byte](SlotBytes.toInt)
    val bb = ByteBuffer.wrap(slotBuf).order(ByteOrder.nativeOrder())
    bb.putLong(0, state.currentTerm.value)
    bb.putInt(8, votedBytes.length)
    if votedBytes.nonEmpty then
      System.arraycopy(votedBytes, 0, slotBuf, 12, votedBytes.length)
    bb.putLong(CommitOffset.toInt, state.commitIndex)
    bb.putLong(GenOffset.toInt, nextGen)

    val crc = new java.util.zip.CRC32()
    crc.update(slotBuf, 0, PayloadBytes)
    crc.update(slotBuf, CommitOffset.toInt, TailBytes)
    bb.putInt(CrcOffset.toInt, crc.getValue.toInt)

    MemorySegment.copy(
      slotBuf,
      0,
      mapped,
      ValueLayout.JAVA_BYTE,
      slotBase,
      SlotBytes.toInt
    )
    val _ = mapped.force()

    lastGeneration = nextGen
    lastSlot = targetSlot

  /** Close the memory mapping. */
  def close(): Unit =
    arena.close()

  private def copySlot(slotIndex: Int): Array[Byte] =
    val buf = new Array[Byte](SlotBytes.toInt)
    MemorySegment.copy(
      mapped,
      ValueLayout.JAVA_BYTE,
      slotIndex.toLong * SlotBytes,
      buf,
      0,
      SlotBytes.toInt
    )
    buf

object MmapHardState:
  private[mmap] val SlotBytes: Long = 288L
  private[mmap] val FileBytes: Long = SlotBytes * 2L
  private val PayloadBytes: Int = 268
  private val MaxVotedForBytes: Int = 256
  private val CrcOffset: Long = 268L
  private val CommitOffset: Long = 272L
  private val GenOffset: Long = 280L
  private val TailBytes: Int = 16 // commitIndex(8) + generation(8)

  private enum SlotResult:
    case Empty
    case Valid(generation: Long, state: HardState)
    case Corrupted(reason: String)

  private def decodeSlot(slotBytes: Array[Byte]): SlotResult =
    if isAllZero(slotBytes) then SlotResult.Empty
    else
      val bb = ByteBuffer.wrap(slotBytes).order(ByteOrder.nativeOrder())
      val storedCrc = bb.getInt(CrcOffset.toInt)
      val crc = new java.util.zip.CRC32()
      crc.update(slotBytes, 0, PayloadBytes)
      crc.update(slotBytes, CommitOffset.toInt, TailBytes)
      val computedCrc = crc.getValue.toInt
      if computedCrc != storedCrc then
        SlotResult.Corrupted(
          f"MmapHardState CRC32 mismatch: stored=0x$storedCrc%08x, computed=0x$computedCrc%08x"
        )
      else
        val term = bb.getLong(0)
        val votedForLen = bb.getInt(8)
        val commitIdx = bb.getLong(CommitOffset.toInt)
        val gen = bb.getLong(GenOffset.toInt)
        if term < 0L then
          SlotResult.Corrupted(s"MmapHardState negative term: $term")
        else if votedForLen < 0 || votedForLen > MaxVotedForBytes then
          SlotResult.Corrupted(
            s"MmapHardState invalid votedForLen: $votedForLen (must be in 0..$MaxVotedForBytes)"
          )
        else if commitIdx < 0L then
          SlotResult.Corrupted(
            s"MmapHardState negative commitIndex: $commitIdx"
          )
        else if gen <= 0L then
          SlotResult.Corrupted(
            s"MmapHardState invalid generation: $gen"
          )
        else
          val votedFor =
            if votedForLen == 0 then None
            else
              val utf8 = Arrays.copyOfRange(slotBytes, 12, 12 + votedForLen)
              Some(NodeId(new String(utf8, StandardCharsets.UTF_8)))
          SlotResult.Valid(gen, HardState(term, votedFor, commitIdx))

  private def isAllZero(arr: Array[Byte]): Boolean =
    @scala.annotation.tailrec
    def loop(i: Int): Boolean =
      if i >= arr.length then true
      else if arr(i) != 0.toByte then false
      else loop(i + 1)
    loop(0)

  private def fsyncParentDir(path: Path): Unit =
    Option(path.getParent).foreach { dir =>
      try
        val dirCh = FileChannel.open(dir, StandardOpenOption.READ)
        try dirCh.force(true)
        finally dirCh.close()
      catch case NonFatal(_) => ()
    }

  /** Open or create the hard state file at the given path. */
  def open(path: Path): MmapHardState =
    val exists = java.nio.file.Files.exists(path)
    val channel = FileChannel.open(
      path,
      StandardOpenOption.CREATE,
      StandardOpenOption.READ,
      StandardOpenOption.WRITE
    )
    try
      val arena = Arena.ofShared()
      try
        val mapped =
          channel.map(FileChannel.MapMode.READ_WRITE, 0, FileBytes, arena)

        val (initGen, initSlot) =
          if !exists then (0L, 1)
          else
            val s0Buf = new Array[Byte](SlotBytes.toInt)
            val s1Buf = new Array[Byte](SlotBytes.toInt)
            MemorySegment.copy(
              mapped,
              ValueLayout.JAVA_BYTE,
              0L,
              s0Buf,
              0,
              SlotBytes.toInt
            )
            MemorySegment.copy(
              mapped,
              ValueLayout.JAVA_BYTE,
              SlotBytes,
              s1Buf,
              0,
              SlotBytes.toInt
            )
            (decodeSlot(s0Buf), decodeSlot(s1Buf)) match
              case (SlotResult.Valid(g0, _), SlotResult.Valid(g1, _)) =>
                if g0 >= g1 then (g0, 0) else (g1, 1)
              case (SlotResult.Valid(g0, _), _) => (g0, 0)
              case (_, SlotResult.Valid(g1, _)) => (g1, 1)
              case _                            => (0L, 1)

        val hs = new MmapHardState(mapped, arena, initGen, initSlot)
        if !exists then
          hs.write(HardState.empty)
          fsyncParentDir(path)
        hs
      catch
        case t: Throwable =>
          arena.close()
          throw t
    finally channel.close()
