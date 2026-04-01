package org.sigilaris.node.jvm.runtime.gossip.tx

import java.nio.ByteBuffer
import java.time.Instant

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.node.jvm.runtime.gossip.*

final case class InMemoryTxSinkSnapshot[A](
    applied: Vector[GossipEvent[A]],
    duplicates: Vector[GossipEvent[A]],
    appliedIds: Map[ChainId, Set[StableArtifactId]],
)

object InMemoryTxSinkSnapshot:
  def empty[A]: InMemoryTxSinkSnapshot[A] =
    InMemoryTxSinkSnapshot(
      applied = Vector.empty,
      duplicates = Vector.empty,
      appliedIds = Map.empty,
    )

final class InMemoryTxArtifactSource[F[_]: Sync, A] private (
    clock: GossipClock[F],
    ref: Ref[F, Map[ChainId, Vector[AvailableGossipEvent[A]]]],
)(using txIdentity: TxIdentity[A])
    extends GossipArtifactSource[F, A]:

  def append(
      chainId: ChainId,
      payload: A,
      ts: Instant,
  ): F[GossipEvent[A]] =
    clock.now.flatMap: availableAt =>
      ref.modify: state =>
        val chainEvents = state.getOrElse(chainId, Vector.empty)
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

  def snapshot(chainId: ChainId): F[Vector[GossipEvent[A]]] =
    ref.get.map(_.getOrElse(chainId, Vector.empty).map(_.event))

  override def readAfter(
      chainId: ChainId,
      topic: GossipTopic,
      cursor: Option[CursorToken],
  ): F[Either[CanonicalRejection, Vector[AvailableGossipEvent[A]]]] =
    ref.get.map: state =>
      if topic != GossipTopic.tx then
        Left(
          CanonicalRejection.ArtifactContractRejected(
            reason = "unsupportedTopic",
            detail = Some(topic.value),
          )
        )
      else
        val chainEvents = state.getOrElse(chainId, Vector.empty)
        cursor match
          case None =>
            Right(chainEvents)
          case Some(token) =>
            decodeSequence(token).flatMap: sequence =>
              val maxSequence = chainEvents.size.toLong
              Either.cond(
                sequence >= 1L && sequence <= maxSequence,
                chainEvents.drop(sequence.toInt),
                CanonicalRejection.StaleCursor(
                  reason = "unknownCursor",
                  detail = Some(s"sequence=$sequence max=$maxSequence"),
                ),
              )

  override def readByIds(
      chainId: ChainId,
      topic: GossipTopic,
      ids: Vector[StableArtifactId],
  ): F[Vector[AvailableGossipEvent[A]]] =
    ref.get.map: state =>
      if topic != GossipTopic.tx then Vector.empty
      else
        val latestById = state
          .getOrElse(chainId, Vector.empty)
          .foldLeft(Map.empty[StableArtifactId, AvailableGossipEvent[A]]): (acc, available) =>
            acc.updated(available.event.id, available)
        ids.distinct.flatMap(latestById.get)

  private def cursorFor(sequence: Long): CursorToken =
    CursorToken.issue(ByteVector.view(ByteBuffer.allocate(java.lang.Long.BYTES).putLong(sequence).array()))

  private def decodeSequence(
      token: CursorToken,
  ): Either[CanonicalRejection.StaleCursor, Long] =
    token.validateVersion().flatMap: validated =>
      Either.cond(
        validated.payload.size == java.lang.Long.BYTES.toLong,
        ByteBuffer.wrap(validated.payload.toArray).getLong(),
        CanonicalRejection.StaleCursor(
          reason = "invalidCursorPayload",
          detail = Some(s"size=${validated.payload.size}"),
        ),
      )

object InMemoryTxArtifactSource:
  def create[F[_]: Sync, A](using
      clock: GossipClock[F],
      txIdentity: TxIdentity[A]
  ): F[InMemoryTxArtifactSource[F, A]] =
    Ref
      .of[F, Map[ChainId, Vector[AvailableGossipEvent[A]]]](Map.empty)
      .map(new InMemoryTxArtifactSource[F, A](clock, _))

final class InMemoryTxArtifactSink[F[_], A] private (
    ref: Ref[F, InMemoryTxSinkSnapshot[A]],
) extends GossipArtifactSink[F, A]:
  override def applyEvent(
      event: GossipEvent[A],
  ): F[Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult]] =
    ref.modify: snapshot =>
      val knownForChain = snapshot.appliedIds.getOrElse(event.chainId, Set.empty)
      if knownForChain.contains(event.id) then
        snapshot.copy(duplicates = snapshot.duplicates :+ event) -> Right(
          ArtifactApplyResult(applied = false, duplicate = true)
        )
      else
        snapshot.copy(
          applied = snapshot.applied :+ event,
          appliedIds = snapshot.appliedIds.updated(event.chainId, knownForChain + event.id),
        ) -> Right(ArtifactApplyResult(applied = true, duplicate = false))

  def snapshot: F[InMemoryTxSinkSnapshot[A]] =
    ref.get

object InMemoryTxArtifactSink:
  def create[F[_]: Sync, A]: F[InMemoryTxArtifactSink[F, A]] =
    Ref
      .of[F, InMemoryTxSinkSnapshot[A]](InMemoryTxSinkSnapshot.empty[A])
      .map(new InMemoryTxArtifactSink[F, A](_))
