package raft.state

import fs2.{Chunk, Stream}

/** Streaming snapshot representation used by the Cats Effect `raft-runtime`
  * layer (§7).
  *
  * Consensus metadata (`SnapshotMetadata` and `PendingSnapshot`) lives in
  * zero-dependency `raft-core`, while this streaming wrapper pairs that
  * metadata with a lazy `fs2.Stream[F, Byte]` for constant-memory disk/network
  * transfer.
  */
final case class Snapshot[F[_]](
    lastIncludedIndex: Long,
    lastIncludedTerm: Long,
    data: Stream[F, Byte],
    config: Option[ClusterConfig] = None
):
  /** Pure metadata view of this snapshot. */
  def metadata: SnapshotMetadata =
    SnapshotMetadata(lastIncludedIndex, lastIncludedTerm, config)

object Snapshot:
  /** Assemble a pure [[PendingSnapshot]] buffer into a streaming `Snapshot[F]`.
    */
  def fromPending[F[_]](pending: PendingSnapshot): Snapshot[F] =
    Snapshot(
      lastIncludedIndex = pending.lastIncludedIndex,
      lastIncludedTerm = pending.lastIncludedTerm,
      data = Stream
        .emits(pending.chunks)
        .flatMap(chunk => Stream.chunk(Chunk.array(chunk))),
      config = pending.config
    )

extension (pending: PendingSnapshot)
  /** Convert buffered chunks into a streaming `Snapshot[F]`. */
  def toSnapshot[F[_]]: Snapshot[F] = Snapshot.fromPending[F](pending)

extension (cfg: CompactionConfig)
  /** Resolve the effective [[raft.spi.CompactionPolicy]] for this config. */
  def resolvePolicy[F[_]: cats.Applicative]: raft.spi.CompactionPolicy[F] =
    raft.spi.CompactionPolicy.resolve[F](cfg)
