package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ApplicationValidatorId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.crypto.{CryptoOps, Signature}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Actual before-vote consensus persistence. Controller authentication rechecks
  * its original durable record independently before using the key.
  */
trait HotStuffControllerVotePreparation[F[_]]:
  def prepare(voter: ValidatorId, proposal: Proposal): Result[F, Unit]

/** Supported runtimes keep no raw key. All typed operations use the same
  * key-owning controller and its installed original/current authorization.
  */
sealed trait HotStuffControlledSigning[F[_]]:
  def validators: Set[ValidatorId]
  def publicKeys: Map[ValidatorId, Bytes]
  private[jvm] def installed: Boolean
  private[jvm] def verifyStore(
      store: JournalSafetyStore[F],
  ): Result[F, Unit]
  def proposal(value: UnsignedProposal): Result[F, Proposal]
  def vote(voter: ValidatorId, proposal: Proposal): Result[F, Vote]
  def timeout(value: UnsignedTimeoutVote): Result[F, TimeoutVote]
  def newView(value: UnsignedNewView): Result[F, NewView]
  def applicationSigner(voter: ValidatorId): Result[F, ApplicationVoteSigner[F]]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HotStuffControlledSigning:
  def bootstrap(
      parent: VerifiedInitialParent,
      safety: JournalSafetyStore[IO],
      finalizer: FinalizedApplicationRuntime[IO],
      profiles: AuthenticatedHistoricalProfiles,
      voterPreparation: HotStuffControllerVotePreparation[IO],
  ): Result[IO, HotStuffControlledSigning[IO]] = for
    _ <- EitherT.fromEither[IO](
      RuntimeCheck.require(
        (parent.journal eq safety.journal) && safety.context == parent.context &&
          safety.anchor.blockId == parent.genesisBlockId.toUInt256 && safety.anchor.stateRoot == parent.genesis.stateRoot.toUInt256 &&
          safety.anchor.height.toBigNat.toBigInt == 0 && parent.record.phase == org.sigilaris.core.application.protocol.v2.BootstrapPhase.Opened,
        RuntimeFailureCode.StartupIdentityMismatch,
        "controlled bootstrap signer and safety must use the same installed original journal/G/state",
      ),
    )
    id <- EitherT.fromEither[IO](
      ValidatorId
        .parse(parent.controller.signerId.asString)
        .leftMap(detail =>
          V2RuntimeFailure.at(RuntimeFailureCode.InvalidRequest, detail),
        ),
    )
    _       <- safety.snapshot
    profile <- EitherT.fromEither[IO](profiles.at(parent.window))
    _       <- EitherT.fromEither[IO](
      RuntimeCheck.require(
        profile.context == parent.context,
        RuntimeFailureCode.DomainMismatch,
        "controlled bootstrap profile changed the installed initial context",
      ),
    )
  yield new Live(
    Map(id -> parent.controller),
    profiles,
    voterPreparation,
    Some(safety -> finalizer),
  )

  def handover(
      active: VerifiedActiveGroup,
      safety: JournalSafetyStore[IO],
      finalizer: FinalizedApplicationRuntime[IO],
      profiles: AuthenticatedHistoricalProfiles,
      voterPreparation: HotStuffControllerVotePreparation[IO],
  ): Result[IO, HotStuffControlledSigning[IO]] = for
    _ <- EitherT.fromEither[IO](
      RuntimeCheck.require(
        (active.journal eq safety.journal) && safety.context == active.handover.evidence.target &&
          safety.anchor.blockId == active.decision.parentBlockId &&
          safety.anchor.stateRoot == active.handover.evidence.continuationParentRoot &&
          safety.anchor.height.toBigNat.toBigInt + 1 == active.decision.firstHeight.toBigNat.toBigInt,
        RuntimeFailureCode.StartupIdentityMismatch,
        "controlled activation signer must use the actual decided group journal, continuation parent and target context",
      ),
    )
    id <- EitherT.fromEither[IO](
      ValidatorId
        .parse(active.controller.signerId.asString)
        .leftMap(detail =>
          V2RuntimeFailure.at(RuntimeFailureCode.InvalidRequest, detail),
        ),
    )
    _ <- safety.snapshot
    _ <- EitherT.fromEither[IO](
      RuntimeCheck.require(
        profiles.configuration.ranges.exists(range =>
          range.context == safety.context,
        ),
        RuntimeFailureCode.DomainMismatch,
        "controlled activation lacks its authenticated target historical range",
      ),
    )
  yield new Live(
    Map(id -> active.controller),
    profiles,
    voterPreparation,
    Some(safety -> finalizer),
  )

  def fromControllers(
      controllers: Map[ValidatorId, FenceController],
      profiles: AuthenticatedHistoricalProfiles,
      voterPreparation: HotStuffControllerVotePreparation[IO],
  ): Either[V2RuntimeFailure, HotStuffControlledSigning[IO]] =
    RuntimeCheck
      .require(
        controllers.nonEmpty && controllers
          .forall((id, controller) => id.value == controller.signerId.asString),
        RuntimeFailureCode.InvalidRequest,
        "controlled runtime requires actual key-owning controllers with exact validator identities",
      )
      .as(new Live(controllers, profiles, voterPreparation, None))

  private final class Live(
      controllers: Map[ValidatorId, FenceController],
      profiles: AuthenticatedHistoricalProfiles,
      preparation: HotStuffControllerVotePreparation[IO],
      installation: Option[
        (JournalSafetyStore[IO], FinalizedApplicationRuntime[IO]),
      ],
  ) extends HotStuffControlledSigning[IO]:
    private[jvm] def installed: Boolean = installation.nonEmpty
    private[jvm] def verifyStore(
        store: JournalSafetyStore[IO],
    ): Result[IO, Unit] =
      EitherT.fromEither[IO](
        RuntimeCheck.require(
          installation.exists(_._1 eq store),
          RuntimeFailureCode.StartupIdentityMismatch,
          "controlled signer is bound to another application safety store",
        ),
      )
    val validators: Set[ValidatorId]        = controllers.keySet
    val publicKeys: Map[ValidatorId, Bytes] =
      controllers.map((id, value) => id -> value.publicKey)
    private val zero = UInt256.unsafeFromBigIntUnsigned(BigInt(0))
    private def core[A](value: Either[CoreFailure, A]): Result[IO, A] =
      EitherT.fromEither[IO](RuntimeCheck.core(value))
    private def check(ok: Boolean, detail: String): Result[IO, Unit] =
      EitherT.fromEither[IO](
        RuntimeCheck.require(ok, RuntimeFailureCode.InvalidSignature, detail),
      )
    private def controller(id: ValidatorId): Result[IO, FenceController] =
      EitherT.fromOption[IO](
        controllers.get(id),
        V2RuntimeFailure.at(
          RuntimeFailureCode.SignerFailure,
          "the local runtime has no controlled key for this validator",
        ),
      )
    private def decode(raw: Bytes): Result[IO, Signature] = for _ <- core(
        ValidatorSignature.validate(ValidatorSignature(Utf8("controller"), raw)),
      )
    yield Signature(
      BigInt(1, raw.take(8L).toArray).toInt,
      UInt256.unsafeFromBytesBE(raw.slice(8L, 40L)),
      UInt256.unsafeFromBytesBE(raw.drop(40L)),
    )
    private def signed(
        id: ValidatorId,
        context: DomainContext,
        request: Bytes,
        preimage: Bytes,
        kind: ControllerSigningKind,
        height: Option[Height],
    ): Result[IO, Signature] = for
      actual <- controller(id)
      result <- installation match
        case Some((safety, finalizer)) if context == safety.context =>
          finalizer.withSigningPermission(safety, context, preimage)(
            safety
              .withControlSigningPermission(Utf8(id.value), request, preimage)(
                actual.sign(request).value,
              )
              .flatMap(result => EitherT.fromEither[IO](result)),
          )
        case _ => actual.sign(request)
      expected <- core(
        ControllerSigningIntent.digest(
          ControllerSigningIntent(
            request,
            ControllerSigningMaterial(context, kind, height, preimage),
          ),
        ),
      )
      _ <- check(
        result.intentDigest == expected,
        "controller signed another exact operation/context/preimage",
      )
      signature <- decode(result.signature)
      recovered <- EitherT.fromEither[IO](
        CryptoOps
          .recover(signature, CryptoOps.keccak256(preimage.toArray))
          .leftMap(e =>
            V2RuntimeFailure.at(RuntimeFailureCode.InvalidSignature, e.msg),
          ),
      )
      _ <- check(
        recovered.toBytes == actual.publicKey,
        "controlled signature does not recover the configured validator key",
      )
    yield signature
    private def context(window: HotStuffWindow): Result[IO, DomainContext] =
      EitherT.fromEither[IO](profiles.at(window).map(_.context))
    private def at(window: HotStuffWindow): Option[Height] = Some(
      org.sigilaris.core.application.protocol
        .InclusionHeight(window.height.toBigNat),
    )

    def proposal(value: UnsignedProposal): Result[IO, Proposal] = for
      domain    <- context(value.window)
      request   <- core(HotStuffControllerRequests.proposal(value))
      signature <- signed(
        value.proposer,
        domain,
        request,
        Proposal.signBytes(value),
        ControllerSigningKind.Consensus,
        at(value.window),
      )
      pending = Proposal(
        ProposalId(zero),
        value.window,
        value.proposer,
        value.targetBlockId,
        value.block,
        value.txSet,
        value.justify,
        signature,
      )
    yield pending.copy(proposalId = Proposal.recomputeId(pending))
    def vote(voter: ValidatorId, proposal: Proposal): Result[IO, Vote] = for
      profile <- EitherT.fromEither[IO](profiles.proposal(proposal))
      _       <- check(
        profile.profile.release != HistoricalApplicationRelease.ApplicationV2,
        "application V2 votes must pass through durable application voting",
      )
      _       <- preparation.prepare(voter, proposal)
      request <- core(HotStuffControllerRequests.vote(voter, proposal))
      unsigned = UnsignedVote(proposal.window, voter, proposal.proposalId)
      signature <- signed(
        voter,
        profile.context,
        request,
        Vote.signBytes(unsigned),
        ControllerSigningKind.Consensus,
        at(proposal.window),
      )
      pending = Vote(
        VoteId(zero),
        unsigned.window,
        voter,
        unsigned.targetProposalId,
        signature,
      )
    yield pending.copy(voteId = Vote.recomputeId(pending))
    def timeout(value: UnsignedTimeoutVote): Result[IO, TimeoutVote] = for
      domain    <- context(value.subject.window)
      request   <- core(HotStuffControllerRequests.timeout(value))
      signature <- signed(
        value.voter,
        domain,
        request,
        TimeoutVote.signBytes(value),
        ControllerSigningKind.Consensus,
        at(value.subject.window),
      )
      pending = TimeoutVote(
        TimeoutVoteId(zero),
        value.subject,
        value.voter,
        signature,
      )
    yield pending.copy(timeoutVoteId = TimeoutVote.recomputeId(pending))
    def newView(value: UnsignedNewView): Result[IO, NewView] = for
      domain    <- context(value.window)
      request   <- core(HotStuffControllerRequests.newView(value))
      signature <- signed(
        value.sender,
        domain,
        request,
        NewView.signBytes(value),
        ControllerSigningKind.Consensus,
        at(value.window),
      )
      pending = NewView(
        NewViewId(zero),
        value.window,
        value.sender,
        value.nextLeader,
        value.highestKnownQc,
        value.timeoutCertificate,
        signature,
      )
    yield pending.copy(newViewId = NewView.recomputeId(pending))
    def applicationSigner(
        voter: ValidatorId,
    ): Result[IO, ApplicationVoteSigner[IO]] =
      controller(voter).map(actual =>
        new ApplicationVoteSigner[IO]:
          val validatorId: ApplicationValidatorId =
            ApplicationValidatorId(Utf8(voter.value))
          def sign(
              context: DomainContext,
              canonicalPreimage: Bytes,
          ): IO[Bytes] =
            val run = for
              request <- core(
                HotStuffControllerRequests.application(
                  context,
                  canonicalPreimage,
                ),
              )
              material <- EitherT.fromEither[IO](
                ControllerApplicationSigning.material(
                  ControllerApplicationVote(context, canonicalPreimage),
                ),
              )
              expected <- core(
                ControllerSigningIntent.digest(
                  ControllerSigningIntent(request, material),
                ),
              )
              result <- actual.sign(request)
              _      <- check(
                result.intentDigest == expected,
                "controller changed the application request fence kind or full context",
              )
              signature <- decode(result.signature)
              recovered <- EitherT.fromEither[IO](
                CryptoOps
                  .recover(
                    signature,
                    CryptoOps.keccak256(canonicalPreimage.toArray),
                  )
                  .leftMap(e =>
                    V2RuntimeFailure
                      .at(RuntimeFailureCode.InvalidSignature, e.msg),
                  ),
              )
              _ <- check(
                recovered.toBytes == actual.publicKey,
                "application signature differs from its actual guarded key/preimage",
              )
            yield result.signature
            run.value.flatMap(
              _.fold(
                e => IO.raiseError(new IllegalStateException(e.message)),
                IO.pure,
              ),
            ),
      )
