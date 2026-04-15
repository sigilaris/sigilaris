package org.sigilaris.node.gossip.tx

import java.nio.ByteBuffer
import java.time.Instant

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.gossip.*

/** Snapshot of the in-memory transaction sink state for testing and inspection.
  *
  * @tparam A
  *   the artifact payload type
  * @param applied
  *   events that were successfully applied
  * @param duplicates
  *   events that were detected as duplicates
  * @param appliedIds
  *   per-chain set of applied artifact ids for deduplication
  */
final case class InMemoryTxSinkSnapshot[A](
    applied: Vector[GossipEvent[A]],
    duplicates: Vector[GossipEvent[A]],
    appliedIds: Map[ChainId, Set[StableArtifactId]],
)

/** Companion for `InMemoryTxSinkSnapshot`. */
object InMemoryTxSinkSnapshot:

  /** Creates an empty sink snapshot.
    *
    * @tparam A
    *   the artifact payload type
    * @return
    *   an empty snapshot
    */
  def empty[A]: InMemoryTxSinkSnapshot[A] =
    InMemoryTxSinkSnapshot(
      applied = Vector.empty[GossipEvent[A]],
      duplicates = Vector.empty[GossipEvent[A]],
      appliedIds = Map.empty[ChainId, Set[StableArtifactId]],
    )

/** In-memory implementation of `GossipArtifactSource` for transaction
  * artifacts, primarily for testing.
  *
  * @tparam F
  *   the effect type
  * @tparam A
  *   the artifact payload type
  */
final class InMemoryTxArtifactSource[F[_]: Sync, A] private (
    clock: GossipClock[F],
    ref: Ref[F, Map[ChainId, Vector[AvailableGossipEvent[A]]]],
)(using txIdentity: TxIdentity[A])
    extends GossipArtifactSource[F, A]:

  /** Appends a new transaction payload to the in-memory source.
    *
    * @param chainId
    *   the chain to append to
    * @param payload
    *   the artifact payload
    * @param ts
    *   the event timestamp
    * @return
    *   the generated gossip event
    */
  def append(
      chainId: ChainId,
      payload: A,
      ts: Instant,
  ): F[GossipEvent[A]] =
    clock.now.flatMap: availableAt =>
      ref.modify: state =>
        val chainEvents =
          state.getOrElse(chainId, Vector.empty[AvailableGossipEvent[A]])
        val nextSequence = chainEvents.size.toLong + 1L
        val event = GossipEvent(
          chainId = chainId,
          topic = GossipTopic.tx,
          id = txIdentity.stableIdOf(payload),
          cursor = cursorFor(nextSequence),
          ts = ts,
          payload = payload,
        )
        val available = AvailableGossipEvent(
          event = event,
          availableAt = availableAt,
        )
        state.updated(chainId, chainEvents :+ available) -> event

  /** Returns all stored events for the given chain.
    *
    * @param chainId
    *   the chain to snapshot
    * @return
    *   all stored events for the chain
    */
  def snapshot(chainId: ChainId): F[Vector[GossipEvent[A]]] =
    ref.get.map(
      _.getOrElse(chainId, Vector.empty[AvailableGossipEvent[A]]).map(_.event),
    )

  override def readAfter(
      chainId: ChainId,
      topic: GossipTopic,
      cursor: Option[CursorToken],
  ): F[Either[CanonicalRejection, Vector[AvailableGossipEvent[A]]]] =
    ref.get.map: state =>
      if topic =!= GossipTopic.tx then
        CanonicalRejection
          .ArtifactContractRejected(
            reason = "unsupportedTopic",
            detail = Some(topic.value),
          )
          .asLeft[Vector[AvailableGossipEvent[A]]]
      else
        val chainEvents =
          state.getOrElse(chainId, Vector.empty[AvailableGossipEvent[A]])
        cursor match
          case None =>
            chainEvents.asRight[CanonicalRejection]
          case Some(token) =>
            decodeSequence(token)
              .flatMap: sequence =>
                val maxSequence = chainEvents.size.toLong
                Either.cond(
                  sequence >= 1L && sequence <= maxSequence,
                  chainEvents.drop(sequence.toInt),
                  CanonicalRejection.StaleCursor(
                    reason = "unknownCursor",
                    detail = Some(
                      ss"sequence=${sequence.toString} max=${maxSequence.toString}",
                    ),
                  ),
                )
              .leftWiden[CanonicalRejection]

  override def readByIds(
      chainId: ChainId,
      topic: GossipTopic,
      ids: Vector[StableArtifactId],
  ): F[Vector[AvailableGossipEvent[A]]] =
    ref.get.map: state =>
      if topic =!= GossipTopic.tx then Vector.empty
      else
        val latestById = state
          .getOrElse(chainId, Vector.empty[AvailableGossipEvent[A]])
          .foldLeft(Map.empty[StableArtifactId, AvailableGossipEvent[A]]):
            (acc, available) => acc.updated(available.event.id, available)
        ids.distinct.flatMap(latestById.get)

  private def cursorFor(sequence: Long): CursorToken =
    CursorToken.unsafeIssue:
      ByteVector.view:
        ByteBuffer.allocate(java.lang.Long.BYTES).putLong(sequence).array()

  private def decodeSequence(
      token: CursorToken,
  ): Either[CanonicalRejection.StaleCursor, Long] =
    token
      .validateVersion()
      .flatMap: validated =>
        Either.cond(
          validated.payload.size === java.lang.Long.BYTES.toLong,
          ByteBuffer.wrap(validated.payload.toArray).getLong(),
          CanonicalRejection.StaleCursor(
            reason = "invalidCursorPayload",
            detail = Some(ss"size=${validated.payload.size.toString}"),
          ),
        )

/** Companion for `InMemoryTxArtifactSource`. */
object InMemoryTxArtifactSource:

  /** Creates a new empty in-memory transaction artifact source.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the artifact payload type
    * @return
    *   a new source instance
    */
  def create[F[_]: Sync, A](using
      clock: GossipClock[F],
      txIdentity: TxIdentity[A],
  ): F[InMemoryTxArtifactSource[F, A]] =
    Ref
      .of[F, Map[ChainId, Vector[AvailableGossipEvent[A]]]](Map.empty)
      .map(new InMemoryTxArtifactSource[F, A](clock, _))

/** In-memory implementation of `GossipArtifactSink` for transaction
  * artifacts, primarily for testing.
  *
  * @tparam F
  *   the effect type
  * @tparam A
  *   the artifact payload type
  */
final class InMemoryTxArtifactSink[F[_], A] private (
    ref: Ref[F, InMemoryTxSinkSnapshot[A]],
) extends GossipArtifactSink[F, A]:
  override def applyEvent(
      event: GossipEvent[A],
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    ref.modify: snapshot =>
      val knownForChain =
        snapshot.appliedIds.getOrElse(
          event.chainId,
          Set.empty[StableArtifactId],
        )
      if knownForChain.contains(event.id) then
        snapshot.copy(duplicates = snapshot.duplicates :+ event) ->
          ArtifactApplyResult(applied = false, duplicate = true)
            .asRight[CanonicalRejection.ArtifactContractRejected]
      else
        snapshot.copy(
          applied = snapshot.applied :+ event,
          appliedIds = snapshot.appliedIds
            .updated(event.chainId, knownForChain + event.id),
        ) ->
          ArtifactApplyResult(applied = true, duplicate = false)
            .asRight[CanonicalRejection.ArtifactContractRejected]

  /** Returns the current sink state snapshot.
    *
    * @return
    *   the current snapshot
    */
  def snapshot: F[InMemoryTxSinkSnapshot[A]] =
    ref.get

/** Companion for `InMemoryTxArtifactSink`. */
object InMemoryTxArtifactSink:

  /** Creates a new empty in-memory transaction artifact sink.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the artifact payload type
    * @return
    *   a new sink instance
    */
  def create[F[_]: Sync, A]: F[InMemoryTxArtifactSink[F, A]] =
    Ref
      .of[F, InMemoryTxSinkSnapshot[A]](InMemoryTxSinkSnapshot.empty[A])
      .map(new InMemoryTxArtifactSink[F, A](_))
