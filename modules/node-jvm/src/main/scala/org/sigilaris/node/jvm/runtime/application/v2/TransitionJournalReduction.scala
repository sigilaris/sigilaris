package org.sigilaris.node.jvm.runtime.application.v2

import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*

/** Reconstructed structural history, never evidence of transition authority. */
final case class TransitionJournalState(
    preparations: Map[Hash, ActivationPreparation],
    latestPreparation: Option[Hash],
    decision: Option[ActivationDecision],
    startup: Option[BootstrapStartupRecord],
    bootstrapIntents: Map[Hash, BootstrapVoteIntent],
    fences: Vector[SignedFencePromise],
    ordinaryRecords: Boolean,
):
  def ordinaryReady: Boolean = startup match
    case None        => preparations.isEmpty || decision.nonEmpty
    case Some(value) =>
      value.phase match
        case BootstrapPhase.Opened => true
        case _                     => false

/** The frozen operation payloads have one monotonic projection. Their complete
  * original proofs and current controls must additionally authenticate before
  * this projection can be published or used to open application voting.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object TransitionJournalReduction:
  val operations: Set[JournalOperation] = Set(
    JournalOperation.ActivationPrepare,
    JournalOperation.ActivationCommit,
    JournalOperation.BootstrapBind,
    JournalOperation.BootstrapVote,
    JournalOperation.Fence,
  )

  val empty: TransitionJournalState = TransitionJournalState(
    Map.empty,
    None,
    None,
    None,
    Map.empty,
    Vector.empty,
    false,
  )

  private def check(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(condition, RuntimeFailureCode.JournalCorrupt, detail)

  def projection(
      records: Vector[JournalRecord],
      anchor: ApplicationAnchor,
  ): Either[V2RuntimeFailure, TransitionJournalState] =
    records.foldLeft[Either[V2RuntimeFailure, TransitionJournalState]](
      Right(empty),
    )((prior, record) => prior.flatMap(append(_, record, anchor)))

  private[v2] def append(
      state: TransitionJournalState,
      record: JournalRecord,
      anchor: ApplicationAnchor,
  ): Either[V2RuntimeFailure, TransitionJournalState] = for
    _ <- RuntimeCheck.core(JournalRecord.validate(record))
    _ <- check(
      record.status == JournalStatus.Committed,
      "transition projection requires committed logical records",
    )
    payload <- RuntimeCheck.core(JournalPayload.codec.decode(record.payload))
    next    <- record.operation match
      case JournalOperation.Fence =>
        for _ <- payload.fences.traverse_(promise =>
            state.fences
              .filter(previous =>
                previous.record.context == promise.record.context &&
                  previous.record.signerId == promise.record.signerId &&
                  previous.record.scope == promise.record.scope,
              )
              .traverse_(previous =>
                check(
                  previous == promise,
                  "a retained fence promise cannot be replaced or rebound",
                ),
              ),
          )
        yield state.copy(fences = (state.fences ++ payload.fences).distinct)
      case JournalOperation.ActivationPrepare =>
        for
          preparation <- payload.activationPreparation.toRight(
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "activation preparation is missing",
            ),
          )
          _ <- check(
            state.startup.isEmpty && state.decision.isEmpty && !state.ordinaryRecords,
            "activation cannot replace bootstrap, a committed activation or a previously used target journal",
          )
          _ <- check(
            preparation.firstHeight.toBigNat.toBigInt > 0 &&
              preparation.targetManifestDigest == anchor.context.configurationDigest,
            "activation preparation has an invalid target boundary or manifest",
          )
          _ <- state.preparations.values.toVector.traverse_(prior =>
            check(
              prior.baselineDigest == preparation.baselineDigest &&
                prior.targetManifestDigest == preparation.targetManifestDigest &&
                prior.firstHeight == preparation.firstHeight,
              "activation preparation changed its fixed baseline, boundary or target manifest",
            ),
          )
          digest <- RuntimeCheck.core(ActivationPreparation.digest(preparation))
        yield state.copy(
          preparations = state.preparations.updated(digest, preparation),
          latestPreparation = Some(digest),
        )
      case JournalOperation.ActivationCommit =>
        for
          decision <- payload.activationDecision.toRight(
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "activation decision is missing",
            ),
          )
          preparation <- state.preparations
            .get(decision.preparationDigest)
            .toRight(
              V2RuntimeFailure.at(
                RuntimeFailureCode.JournalCorrupt,
                "activation decision has no original preparation",
              ),
            )
          _ <- check(
            state.startup.isEmpty && state.decision.isEmpty &&
              state.latestPreparation.contains(decision.preparationDigest) &&
              decision.decisionSequence == record.sequence &&
              decision.parentBlockId == anchor.blockId &&
              decision.firstHeight.toBigNat.toBigInt == anchor.height.toBigNat.toBigInt + 1 &&
              decision.parentBlockId == preparation.parentBlockId &&
              decision.firstHeight == preparation.firstHeight &&
              decision.targetManifestDigest == preparation.targetManifestDigest &&
              decision.retainedSafetyInventoryDigest == preparation.retainedSafetyInventoryDigest,
            "activation decision does not select the latest complete preparation exactly once",
          )
        yield state.copy(decision = Some(decision))
      case JournalOperation.BootstrapBind | JournalOperation.BootstrapVote =>
        bootstrap(state, record, payload, anchor)
      case _ =>
        check(
          state.ordinaryReady,
          "ordinary application work precedes completed bootstrap or activation",
        )
          .as(state.copy(ordinaryRecords = true))
  yield next

  private def bootstrap(
      state: TransitionJournalState,
      record: JournalRecord,
      payload: JournalPayload,
      anchor: ApplicationAnchor,
  ): Either[V2RuntimeFailure, TransitionJournalState] = for
    startup <- payload.bootstrapStartup.toRight(
      V2RuntimeFailure.at(
        RuntimeFailureCode.JournalCorrupt,
        "bootstrap startup identity is missing",
      ),
    )
    _ <- check(
      state.preparations.isEmpty && state.decision.isEmpty &&
        anchor.height.toBigNat.toBigInt == 0 && startup.genesisBlockId == anchor.blockId,
      "bootstrap startup cannot replace a handover or another genesis anchor",
    )
    _ <- state.startup match
      case None =>
        check(
          record.operation == JournalOperation.BootstrapBind && startup.phase == BootstrapPhase.Bound &&
            startup.issuedVoteIntents.isEmpty && !state.ordinaryRecords,
          "the first bootstrap record must bind the unsigned installed identity",
        )
      case Some(prior) =>
        for
          _ <- check(
            prior.copy(
              phase = startup.phase,
              issuedVoteIntents = startup.issuedVoteIntents,
            ) == startup,
            "bootstrap changed its fixed bundle, genesis, source, safety or baseline identity",
          )
          _ <- check(
            prior.phase == startup.phase ||
              (prior.phase == BootstrapPhase.Bound && startup.phase == BootstrapPhase.Installed) ||
              (prior.phase == BootstrapPhase.Installed &&
                (startup.phase == BootstrapPhase.Signing || startup.phase == BootstrapPhase.Opened)) ||
              (prior.phase == BootstrapPhase.Signing && startup.phase == BootstrapPhase.Opened),
            "bootstrap startup phase regressed or skipped installation",
          )
        yield ()
    intents <- record.operation match
      case JournalOperation.BootstrapBind =>
        check(
          startup.issuedVoteIntents.toSet == state.bootstrapIntents.keySet &&
            (startup.phase != BootstrapPhase.Signing || state.startup.exists(
              _.phase == BootstrapPhase.Signing,
            )),
          "bootstrap binding cannot issue or remove a vote intent",
        ).as(state.bootstrapIntents)
      case JournalOperation.BootstrapVote =>
        for
          intent <- payload.bootstrapVoteIntent.toRight(
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "bootstrap signing intent is missing",
            ),
          )
          digest  <- RuntimeCheck.core(BootstrapVoteIntent.digest(intent))
          subject <- RuntimeCheck.core(
            BootstrapSubject.digest(
              BootstrapSubject(1L, startup.bundleDigest, startup.genesisBlockId),
            ),
          )
          fields <- RuntimeCheck.core(
            ConsensusSignFields.codec.decode(intent.unsignedVoteSignBytes),
          )
          _ <- check(
            state.startup.exists(prior =>
              prior.phase == BootstrapPhase.Installed || prior.phase == BootstrapPhase.Signing,
            ) &&
              startup.phase == BootstrapPhase.Signing &&
              intent.bundleDigest == startup.bundleDigest && intent.genesisBlockId == startup.genesisBlockId &&
              intent.initializedSafetyInventory == startup.initializedSafetyInventory && intent.baselineDigest == startup.baselineDigest &&
              fields.chainId == anchor.context.chainId && fields.validatorSetHash == anchor.context.validatorSetHash &&
              fields.height.toBigInt == 0 && fields.view.toBigInt == 0 && fields.voter == intent.validatorId &&
              fields.targetProposalId == subject,
            "bootstrap intent differs from its installed identity and original initial-only HotStuff vote bytes",
          )
          _ <- check(
            !state.bootstrapIntents.contains(digest) &&
              state.bootstrapIntents.values
                .forall(_.validatorId != intent.validatorId) &&
              startup.issuedVoteIntents.toSet == state.bootstrapIntents.keySet + digest,
            "bootstrap intent was repeated, rebound or omitted from startup history",
          )
        yield state.bootstrapIntents.updated(digest, intent)
      case _ =>
        Left(
          V2RuntimeFailure.at(
            RuntimeFailureCode.JournalCorrupt,
            "unexpected bootstrap operation",
          ),
        )
  yield state.copy(startup = Some(startup), bootstrapIntents = intents)

  private[v2] def apply(
      original: SafetyState,
      projected: SafetyState,
      record: JournalRecord,
      anchor: ApplicationAnchor,
  ): Either[V2RuntimeFailure, SafetyState] = for
    previous <- projection(original.committed, anchor)
    next     <- append(previous, record, anchor)
  yield projected.copy(fences = next.fences)
