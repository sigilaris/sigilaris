package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.gossip.ChainId
import org.sigilaris.node.jvm.runtime.block.{BlockHeader, BlockHeaderVersion}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

trait HotStuffControllerArtifacts:
  def proposal(subject: QuorumCertificateSubject): Result[IO, Proposal]
  def quorum(subject: QuorumCertificateSubject): Result[IO, QuorumCertificate]

/** Implement with the actual original consensus safety journal, not a window
  * predicate. authenticate checks its immutable forced record; authorize checks
  * the current before-vote safe rule without controller-gate reentry.
  */
trait HistoricalControllerVoteAuthentication:
  def authenticate(
      proposal: Proposal,
      voter: ValidatorId,
      key: Bytes,
  ): Result[IO, ControllerSigningMaterial]
  def authorize(
      proposal: Proposal,
      voter: ValidatorId,
      key: Bytes,
  ): Result[IO, Unit]

/** Canonical request interpretation for a controller installed with complete
  * pinned historical ranges. Its result is always recomputed from actual
  * artifacts and the original forced vote closure. This helper supplies the
  * signing/authorizeSigning methods of ControllerOperationAuthentication; seed,
  * canonical-write and fence authority retain their independent interpreters.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
final class HotStuffControllerAuthentication private (
    profiles: AuthenticatedHistoricalProfiles,
    validators: ValidatorSetLookup[IO],
    artifacts: HotStuffControllerArtifacts,
    historical: HistoricalControllerVoteAuthentication,
    application: ControllerApplicationSigning,
    initial: Option[(VerifiedBootstrap, DurableJournal[IO])],
    parent: Option[VerifiedInitialParent],
    initialInstaller: IO[Option[InitialStateInstaller[IO]]],
):
  private def core[A](v: Either[CoreFailure, A]): Result[IO, A] =
    EitherT.fromEither[IO](RuntimeCheck.core(v))
  private def check(ok: Boolean, detail: String): Result[IO, Unit] =
    EitherT.fromEither[IO](
      RuntimeCheck.require(ok, RuntimeFailureCode.ProofInvalid, detail),
    )
  private def valid[A](
      value: Either[HotStuffValidationFailure, A],
  ): Result[IO, A] = EitherT.fromEither[IO](
    value.leftMap(e =>
      V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, e.reason),
    ),
  )
  private def set(window: HotStuffWindow): Result[IO, ValidatorSet] = EitherT(
    validators
      .validatorSetFor(window)
      .map(
        _.leftMap(e =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, e.reason),
        ),
      ),
  )
  private def identity(
      window: HotStuffWindow,
      id: ValidatorId,
      signer: Text,
      key: Bytes,
  ): Result[IO, DomainContext] = for
    range   <- EitherT.fromEither[IO](profiles.at(window))
    members <- set(window)
    _       <- check(
      id.value == signer.asString && members
        .member(id)
        .exists(_.publicKey.toBytes == key),
      "controlled consensus request differs from its actual historical voter/key set",
    )
  yield range.context
  private def quorum(qc: QuorumCertificate): Result[IO, Unit] = for
    _       <- EitherT.fromEither[IO](profiles.at(qc.subject.window))
    members <- set(qc.subject.window)
    _       <- valid(HotStuffValidator.validateQuorumCertificate(qc, members))
  yield ()
  private def parentFor(unsigned: UnsignedProposal): Result[IO, Unit] = for
    _ <- quorum(unsigned.justify)
    _ <- parent match
      case Some(initial)
          if unsigned.justify.subject.blockId == initial.genesisBlockId =>
        EitherT.fromEither[IO](
          InitialBootstrapParent.validate(
            initial,
            unsigned.window,
            unsigned.block.parent,
            unsigned.justify,
          ),
        ) *> check(
          unsigned.block.height.toBigNat.toBigInt == 1 &&
            unsigned.block.version == BlockHeaderVersion.V2,
          "initial parent is permitted only for the exact installed new-domain first opening",
        )
      case _
          if initial.exists((bootstrap, _) =>
            InitialBootstrapConsensus
              .genesis(bootstrap)
              .exists(_.blockId == unsigned.justify.subject.blockId),
          ) =>
        initial match
          case Some((bootstrap, journal)) =>
            InitialBootstrapParent.original(
              bootstrap,
              journal,
              unsigned.window,
              unsigned.block.parent,
              unsigned.justify,
            )
          case None =>
            EitherT.leftT[IO, Unit](
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofUnavailable,
                "original initial installation missing",
              ),
            )
      case _ =>
        for
          original <- artifacts.proposal(unsigned.justify.subject)
          _        <- EitherT.fromEither[IO](profiles.proposal(original))
          members  <- set(original.window)
          previous <- set(original.justify.subject.window)
          _        <- valid(
            HotStuffValidator.validateProposal(
              original,
              members,
              Some(previous),
            ),
          )
          _ <- check(
            original.proposalId == unsigned.justify.subject.proposalId && original.targetBlockId == unsigned.justify.subject.blockId &&
              original.window == unsigned.justify.subject.window && unsigned.block.parent
                .contains(original.targetBlockId) &&
              unsigned.block.height.toBigNat.toBigInt == original.block.height.toBigNat.toBigInt + 1,
            "controlled proposal does not extend its actual authenticated certified parent",
          )
        yield ()
  yield ()

  def signing(
      raw: Bytes,
      signer: Text,
      key: Bytes,
  ): Result[IO, ControllerSigningMaterial] =
    InitialBootstrapSigningRequest.codec.decode(raw) match
      case Right(_) =>
        initial match
          case Some((bootstrap, journal)) =>
            InitialBootstrapSigningAuthentication.signing(
              bootstrap,
              journal,
              raw,
              signer,
              key,
            )
          case None =>
            EitherT.leftT(
              V2RuntimeFailure.at(
                RuntimeFailureCode.InvalidRequest,
                "initial signing is unavailable in this installed controller",
              ),
            )
      case Left(_) =>
        for
          request  <- core(HotStuffControllerRequest.codec.decode(raw))
          material <- request.kind match
            case HotStuffControllerKind.Proposal =>
              for
                value <- core(
                  HotStuffControllerRequests.proposalCodec.decode(
                    request.payload,
                  ),
                )
                context <- identity(value.window, value.proposer, signer, key)
                range   <- EitherT.fromEither[IO](profiles.at(value.window))
                _       <- check(
                  value.block.version.tag.toLong == range.profile.headerVersion && value.block.height == value.window.height &&
                    value.targetBlockId == BlockHeader.computeId(
                      value.block,
                    ) && ProposalTxSet.isCanonical(value.txSet),
                  "controlled proposal changed its historical header, target id, height or canonical transaction set",
                )
                _ <- EitherT.fromEither[IO](
                  BlockHeader
                    .validateVersionedCommitment(value.block)
                    .leftMap(e =>
                      V2RuntimeFailure
                        .at(RuntimeFailureCode.ProofInvalid, e.reason),
                    ),
                )
                _ <- parentFor(value)
              yield ControllerSigningMaterial(
                context,
                ControllerSigningKind.Consensus,
                Some(InclusionHeight(value.window.height.toBigNat)),
                Proposal.signBytes(value),
              )
            case HotStuffControllerKind.Vote =>
              for
                value <- core(
                  ControllerConsensusVote.codec.decode(request.payload),
                )
                context <- identity(
                  value.proposal.window,
                  value.voter,
                  signer,
                  key,
                )
                profile <- EitherT.fromEither[IO](
                  profiles.proposal(value.proposal),
                )
                _ <- check(
                  profile.profile.release != HistoricalApplicationRelease.ApplicationV2,
                  "new application vote cannot use the historical vote request branch",
                )
                material <- historical.authenticate(
                  value.proposal,
                  value.voter,
                  key,
                )
                _ <- check(
                  material == ControllerSigningMaterial(
                    context,
                    ControllerSigningKind.Consensus,
                    Some(
                      InclusionHeight(value.proposal.window.height.toBigNat),
                    ),
                    Vote.signBytes(
                      UnsignedVote(
                        value.proposal.window,
                        value.voter,
                        value.proposal.proposalId,
                      ),
                    ),
                  ),
                  "original consensus safety proof changed the exact vote frame/context",
                )
              yield material
            case HotStuffControllerKind.Timeout =>
              for
                value <- core(
                  HotStuffControllerRequests.timeoutCodec.decode(
                    request.payload,
                  ),
                )
                context <- identity(
                  value.subject.window,
                  value.voter,
                  signer,
                  key,
                )
                original <- artifacts.quorum(value.subject.highestKnownQc)
                _        <- check(
                  original.subject == value.subject.highestKnownQc && original.subject.window.chainId == value.subject.window.chainId &&
                    original.subject.window.height.toBigNat.toBigInt <= value.subject.window.height.toBigNat.toBigInt,
                  "timeout vote lacks its actual same-domain highest known QC",
                )
                _ <- quorum(original)
              yield ControllerSigningMaterial(
                context,
                ControllerSigningKind.Consensus,
                Some(InclusionHeight(value.subject.window.height.toBigNat)),
                TimeoutVote.signBytes(value),
              )
            case HotStuffControllerKind.NewView =>
              for
                value <- core(
                  HotStuffControllerRequests.newViewCodec.decode(
                    request.payload,
                  ),
                )
                context     <- identity(value.window, value.sender, signer, key)
                members     <- set(value.timeoutCertificate.subject.window)
                nextMembers <- set(value.window)
                _           <- valid(
                  HotStuffValidator.validateTimeoutCertificate(
                    value.timeoutCertificate,
                    members,
                  ),
                )
                _ <- quorum(value.highestKnownQc)
                _ <- check(
                  value.window == HotStuffPacemaker.nextWindowAfter(
                    value.timeoutCertificate.subject.window,
                  ) &&
                    value.nextLeader == HotStuffPacemaker
                      .deterministicLeader(value.window, nextMembers) &&
                    value.highestKnownQc.subject == value.timeoutCertificate.subject.highestKnownQc,
                  "new-view request changed the actual timeout certificate, highest QC or next leader",
                )
              yield ControllerSigningMaterial(
                context,
                ControllerSigningKind.Consensus,
                Some(InclusionHeight(value.window.height.toBigNat)),
                NewView.signBytes(value),
              )
            case HotStuffControllerKind.Application =>
              for
                value <- core(
                  ControllerApplicationVote.codec.decode(request.payload),
                )
                material <- application.authenticate(value, signer)
                range    <- EitherT.fromOption[IO](
                  profiles.configuration.ranges.find(range =>
                    range.context == material.context &&
                      range.profile.release == HistoricalApplicationRelease.ApplicationV2,
                  ),
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.ProofInvalid,
                    "application signing context has no installed validator profile",
                  ),
                )
                chain <- EitherT.fromEither[IO](
                  ChainId
                    .parse(material.context.chainId.asString)
                    .leftMap(reason =>
                      V2RuntimeFailure
                        .at(RuntimeFailureCode.ProofInvalid, reason),
                    ),
                )
                voter <- EitherT.fromEither[IO](
                  ValidatorId
                    .parse(signer.asString)
                    .leftMap(reason =>
                      V2RuntimeFailure
                        .at(RuntimeFailureCode.ProofInvalid, reason),
                    ),
                )
                selected <- identity(
                  HotStuffWindow(
                    chain,
                    HotStuffHeight(
                      material.height.getOrElse(range.firstHeight).toBigNat,
                    ),
                    HotStuffView.Zero,
                    ValidatorSetHash(material.context.validatorSetHash),
                  ),
                  voter,
                  signer,
                  key,
                )
                _ <- check(
                  selected == material.context,
                  "application signing height selected another installed context",
                )
              yield material
        yield material

  def authorizeSigning(raw: Bytes, signer: Text, key: Bytes): Result[IO, Unit] =
    for
      material <- signing(raw, signer, key)
      _        <- HotStuffControllerRequest.codec.decode(raw) match
        case Right(request) =>
          request.kind match
            case HotStuffControllerKind.Vote =>
              core(ControllerConsensusVote.codec.decode(request.payload))
                .flatMap(value =>
                  historical.authorize(value.proposal, value.voter, key),
                )
            case HotStuffControllerKind.Application =>
              core(ControllerApplicationVote.codec.decode(request.payload))
                .flatMap(application.authorize(_, signer))
            case _ =>
              if profiles.configuration.ranges.exists(range =>
                  range.context == material.context &&
                    range.profile.release == HistoricalApplicationRelease.ApplicationV2,
                )
              then application.authorizeControl(material, signer)
              else EitherT.rightT[IO, V2RuntimeFailure](())
        case Left(_) =>
          EitherT
            .fromOptionF(
              initialInstaller,
              V2RuntimeFailure.at(
                RuntimeFailureCode.RecoveryRequired,
                "initial fresh signing requires its actual bound installer",
              ),
            )
            .flatMap(installer =>
              InitialBootstrapSigningAuthentication
                .authorize(installer, raw, signer, key),
            )
    yield ()

object HotStuffControllerAuthentication:
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def authenticated(
      profiles: AuthenticatedHistoricalProfiles,
      validators: ValidatorSetLookup[IO],
      artifacts: HotStuffControllerArtifacts,
      historical: HistoricalControllerVoteAuthentication,
      application: ControllerApplicationSigning,
      initial: Option[(VerifiedBootstrap, DurableJournal[IO])],
      parent: Option[VerifiedInitialParent],
      initialInstaller: IO[Option[InitialStateInstaller[IO]]] = IO.pure(None),
  ): HotStuffControllerAuthentication =
    new HotStuffControllerAuthentication(
      profiles,
      validators,
      artifacts,
      historical,
      application,
      initial,
      parent,
      initialInstaller,
    )
