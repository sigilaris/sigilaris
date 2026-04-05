package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.Applicative
import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.node.jvm.runtime.block.BlockHeight
import org.sigilaris.node.jvm.runtime.gossip.{ChainId, ControlBatch}

final case class ForwardCatchUpMaterialization(
    chainId: ChainId,
    anchor: SnapshotAnchor,
    applied: Vector[Proposal],
    queued: Vector[Proposal],
    controlBatches: Vector[ControlBatch],
    frontierBlockId: BlockId,
    frontierHeight: BlockHeight,
    voteReadiness: BootstrapVoteReadiness,
    lastUpdatedAt: Instant,
)

trait ForwardCatchUpStore[F[_]]:
  def current(
      chainId: ChainId,
  ): F[Option[ForwardCatchUpMaterialization]]

  def put(
      materialization: ForwardCatchUpMaterialization,
  ): F[Unit]

object ForwardCatchUpStore:
  def noop[F[_]: Applicative]: ForwardCatchUpStore[F] =
    new ForwardCatchUpStore[F]:
      override def current(
          chainId: ChainId,
      ): F[Option[ForwardCatchUpMaterialization]] =
        none[ForwardCatchUpMaterialization].pure[F]

      override def put(
          materialization: ForwardCatchUpMaterialization,
      ): F[Unit] =
        Applicative[F].unit

  def inMemory[F[_]: Sync]: F[ForwardCatchUpStore[F]] =
    Ref
      .of[F, Map[ChainId, ForwardCatchUpMaterialization]](Map.empty)
      .map: ref =>
        new ForwardCatchUpStore[F]:
          override def current(
              chainId: ChainId,
          ): F[Option[ForwardCatchUpMaterialization]] =
            ref.get.map(_.get(chainId))

          override def put(
              materialization: ForwardCatchUpMaterialization,
          ): F[Unit] =
            ref.update(
              _.updated(materialization.chainId, materialization),
            )

enum HistoricalArchiveSource:
  case BackgroundBackfill

final case class HistoricalArchiveEntry(
    proposal: Proposal,
    source: HistoricalArchiveSource,
    storedAt: Instant,
)

trait HistoricalProposalArchive[F[_]]:
  def list(
      chainId: ChainId,
  ): F[Vector[HistoricalArchiveEntry]]

  def contains(
      chainId: ChainId,
      proposalId: ProposalId,
  ): F[Boolean]

  def putAll(
      chainId: ChainId,
      proposals: Vector[Proposal],
      source: HistoricalArchiveSource,
      storedAt: Instant,
  ): F[Vector[ProposalId]]

  def removeAll(
      chainId: ChainId,
      proposalIds: Vector[ProposalId],
  ): F[Int]

object HistoricalProposalArchive:
  def noop[F[_]: Applicative]: HistoricalProposalArchive[F] =
    new HistoricalProposalArchive[F]:
      override def list(
          chainId: ChainId,
      ): F[Vector[HistoricalArchiveEntry]] =
        Vector.empty[HistoricalArchiveEntry].pure[F]

      override def contains(
          chainId: ChainId,
          proposalId: ProposalId,
      ): F[Boolean] =
        false.pure[F]

      override def putAll(
          chainId: ChainId,
          proposals: Vector[Proposal],
          source: HistoricalArchiveSource,
          storedAt: Instant,
      ): F[Vector[ProposalId]] =
        proposals.map(_.proposalId).pure[F]

      override def removeAll(
          chainId: ChainId,
          proposalIds: Vector[ProposalId],
      ): F[Int] =
        proposalIds.size.pure[F]

  def inMemory[F[_]: Sync]: F[HistoricalProposalArchive[F]] =
    Ref
      .of[F, Map[ChainId, Vector[HistoricalArchiveEntry]]](Map.empty)
      .map: ref =>
        new HistoricalProposalArchive[F]:
          override def list(
              chainId: ChainId,
          ): F[Vector[HistoricalArchiveEntry]] =
            ref.get.map(_.getOrElse(chainId, Vector.empty[HistoricalArchiveEntry]))

          override def contains(
              chainId: ChainId,
              proposalId: ProposalId,
          ): F[Boolean] =
            list(chainId).map(_.exists(_.proposal.proposalId === proposalId))

          override def putAll(
              chainId: ChainId,
              proposals: Vector[Proposal],
              source: HistoricalArchiveSource,
              storedAt: Instant,
          ): F[Vector[ProposalId]] =
            ref.modify: current =>
              val existing =
                current.getOrElse(chainId, Vector.empty[HistoricalArchiveEntry])
              val knownIds =
                existing.iterator.map(_.proposal.proposalId).toSet
              val appended =
                proposals
                  .filterNot(proposal => knownIds.contains(proposal.proposalId))
                  .map: proposal =>
                    HistoricalArchiveEntry(
                      proposal = proposal,
                      source = source,
                      storedAt = storedAt,
                    )
              current.updated(chainId, existing ++ appended) -> appended.map(_.proposal.proposalId)

          override def removeAll(
              chainId: ChainId,
              proposalIds: Vector[ProposalId],
          ): F[Int] =
            ref.modify: current =>
              val existing =
                current.getOrElse(chainId, Vector.empty[HistoricalArchiveEntry])
              val idsToRemove = proposalIds.toSet
              val retained =
                existing.filterNot(entry => idsToRemove.contains(entry.proposal.proposalId))
              current.updated(chainId, retained) -> (existing.size - retained.size)
