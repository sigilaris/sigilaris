package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  Vote,
  Proposal,
  TimeoutVote,
  NewView,
}

/** Local admission capacity never alters the protocol witness limits. */
final case class SafetyProfile(
    manifest: ProtocolManifest,
    authentication: ArtifactAuthentication,
)

final case class SafetyCapacity(maxLiveOwners: Long, maxWitnessBytes: Long)
object SafetyCapacity:
  val unbounded: SafetyCapacity = SafetyCapacity(Long.MaxValue, Long.MaxValue)

/** All voters and canonical application share this gate and journal. A storage
  * interruption leaves the instance fenced until a complete forward recovery.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
final class JournalSafetyStore[F[_]: Async] private (
    val anchor: ApplicationAnchor,
    private[v2] val journal: DurableJournal[F],
    publication: SafetyPublication[F],
    profile: SafetyProfile,
    recoveryAuthentication: SafetyRecoveryAuthentication[F],
    ordering: ReservationOrdering[F],
    capacity: SafetyCapacity,
    gate: Semaphore[F],
    state: Ref[F, SafetyState],
    ready: Ref[F, Boolean],
    effectPermissions: Ref[F, Map[Hash, VerifiedEffectRequest]],
    consensusPermissions: Ref[F, Map[Hash, VerifiedConsensusProposal]],
    exactPermissions: Ref[F, Map[Hash, Set[ExecutionId]]],
    activeSigning: Ref[F, Option[JournalSafetyStore.ActiveSigning]],
) extends SafetyStore[F]:
  def context: DomainContext = anchor.context

  /** Read-only capability check from inside the actual key-controller gate. The
    * lease exists only while the authenticated safety gate is held around this
    * exact validator/preimage and immutable complete journal projection.
    * Reading it never reacquires that gate.
    */
  private[v2] def verifyActiveSigning(
      requestedContext: DomainContext,
      validatorId: Text,
      preimage: Bytes,
      committed: Vector[JournalRecord],
  ): Result[F, Unit] = for
    enabled <- EitherT.liftF(ready.get)
    lease   <- EitherT.liftF(activeSigning.get)
    _       <- check(
      enabled && lease.exists(value =>
        value.context == requestedContext && value.validatorId == validatorId &&
          value.preimage == preimage && value.committed == committed,
      ),
      RuntimeFailureCode.RecoveryRequired,
      "actual key use has no matching authenticated active safety signing lease",
    )
  yield ()

  /** Only typed proposal/timeout/new-view requests may borrow full-store
    * readiness without an application vote intent. Recompute the exact signed
    * bytes here so this path cannot bypass vote reservations or deadlines. The
    * caller also holds the finalizer's exact-preimage gate. The controller
    * still forces its own original intent and authenticates consensus safety.
    */
  private[v2] def withControlSigningPermission[A](
      validatorId: Text,
      request: Bytes,
      preimage: Bytes,
  )(sign: F[A]): Result[F, A] = transaction { current =>
    for
      decoded  <- core(HotStuffControllerRequest.codec.decode(request))
      expected <- decoded.kind match
        case HotStuffControllerKind.Proposal =>
          core(HotStuffControllerRequests.proposalCodec.decode(decoded.payload))
            .map(value =>
              (value.window, value.proposer, Proposal.signBytes(value)),
            )
        case HotStuffControllerKind.Timeout =>
          core(HotStuffControllerRequests.timeoutCodec.decode(decoded.payload))
            .map(value =>
              (value.subject.window, value.voter, TimeoutVote.signBytes(value)),
            )
        case HotStuffControllerKind.NewView =>
          core(HotStuffControllerRequests.newViewCodec.decode(decoded.payload))
            .map(value =>
              (value.window, value.sender, NewView.signBytes(value)),
            )
        case _ =>
          EitherT.leftT[
            F,
            (
                org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffWindow,
                org.sigilaris.node.jvm.runtime.consensus.hotstuff.ValidatorId,
                Bytes,
            ),
          ](
            V2RuntimeFailure.at(
              RuntimeFailureCode.InvalidRequest,
              "control signing cannot authorize a vote, lock or effect",
            ),
          )
      (window, voter, bytes) = expected
      _ <- check(
        voter.value == validatorId.asString && bytes == preimage &&
          window.chainId.value == context.chainId.asString && window.validatorSetHash.toUInt256 == context.validatorSetHash,
        RuntimeFailureCode.DomainMismatch,
        "control signing changed its actual request, context, validator or preimage",
      )
      _ <- rebuild(current)
      lease = JournalSafetyStore.ActiveSigning(
        context,
        validatorId,
        preimage,
        current.committed,
      )
      result <- EitherT(
        Async[F]
          .guarantee(
            activeSigning.set(Some(lease)) *> sign.attempt,
            activeSigning.set(None),
          )
          .map(
            _.leftMap(error =>
              V2RuntimeFailure.at(
                RuntimeFailureCode.SignerFailure,
                Option(error.getMessage).getOrElse(error.getClass.getName),
              ),
            ),
          ),
      )
    yield result
  }

  private[v2] def verifyNewExactDeadline(deadline: Height): Result[F, Unit] =
    publication
      .finalizedHeight(context)
      .flatMap(height =>
        check(
          height.toBigNat.toBigInt < deadline.toBigNat.toBigInt,
          RuntimeFailureCode.DeadlineMismatch,
          "new exact admission is already past its voting deadline",
        ),
      )

  private[v2] def revokeExactReadinessUnderGate(
      intent: Hash,
      executions: Vector[ExecutionId],
  ): Result[F, Unit] =
    EitherT.liftF(
      exactPermissions.update(values =>
        values.updated(
          intent,
          values.getOrElse(intent, Set.empty[ExecutionId]) -- executions,
        ),
      ),
    )

  private[v2] def authorizeExactCandidate(
      prepared: PreparedExactCandidate,
  ): Result[F, Unit] =
    transaction(current =>
      for
        _ <- check(
          current.consensusIntents.contains(prepared.consensusIntentDigest),
          RuntimeFailureCode.RecoveryRequired,
          "exact readiness has no durable consensus intent",
        )
        _ <- prepared.verifiedProposal.reservations.traverse_(reservation =>
          eligible(
            current,
            reservation.owner.executionId,
            reservation.lastInclusionHeight,
          ),
        )
        _ <- publication.verifyConsensusState(prepared.verifiedProposal)
        _ <- EitherT.liftF(
          exactPermissions.update(values =>
            values.updated(
              prepared.consensusIntentDigest,
              values.getOrElse(
                prepared.consensusIntentDigest,
                Set.empty[ExecutionId],
              ) ++ prepared.executionIds,
            ),
          ),
        )
      yield (),
    )

  private def pure[A](value: Either[V2RuntimeFailure, A]): Result[F, A] =
    EitherT.fromEither[F](value)
  private def core[A](value: Either[CoreFailure, A]): Result[F, A] = pure(
    RuntimeCheck.core(value),
  )
  private def check(
      condition: Boolean,
      code: RuntimeFailureCode,
      detail: String,
  ): Result[F, Unit] =
    pure(RuntimeCheck.require(condition, code, detail))
  private def available: Result[F, Unit] = EitherT
    .liftF(ready.get)
    .flatMap(value =>
      check(
        value,
        RuntimeFailureCode.RecoveryRequired,
        "complete safety recovery is required",
      ),
    )
  private def locked[A](run: Result[F, A]): Result[F, A] = EitherT(
    gate.permit.use(_ => run.value),
  )

  /** Package-private integration boundary. The callback receives the current
    * complete projection under the same gate as signing. Persist is
    * uncancelable.
    */
  private[v2] def transaction[A](
      run: SafetyState => Result[F, A],
  ): Result[F, A] = locked:
    EitherT(
      (available *> EitherT.liftF(state.get).flatMap(run)).value.attempt
        .flatMap {
          case Left(error) =>
            ready
              .set(false)
              .as(
                Left[V2RuntimeFailure, A](
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.StorageUnknown,
                    Option(error.getMessage).getOrElse(error.getClass.getName),
                  ),
                ),
              )
          case Right(result @ Left(error))
              if Set(
                RuntimeFailureCode.StorageUnknown,
                RuntimeFailureCode.JournalCorrupt,
                RuntimeFailureCode.IncompleteWitness,
                RuntimeFailureCode.IncompleteIndex,
              ).contains(error.code) =>
            ready.set(false).as(result)
          case Right(result) => Async[F].pure(result)
        },
    )

  private[v2] def retainBlob(
      namespace: Text,
      digest: Hash,
      bytes: Bytes,
  ): Result[F, Unit] =
    EitherT(
      Async[F].uncancelable(_ =>
        ready.set(false) *> journal
          .putBlob(namespace, digest, bytes)
          .value
          .flatMap:
            case Left(error) =>
              Async[F].pure(Left[V2RuntimeFailure, Unit](error))
            case Right(_) =>
              ready.set(true).as(Right[V2RuntimeFailure, Unit](())),
      ),
    )

  private def retainWitness(
      witness: ReservationWitness,
  ): Result[F, WitnessRef] =
    for
      ref   <- core(WitnessRef.fromWitness(witness))
      bytes <- core(ReservationWitness.codec.encode(witness))
      _     <- retainBlob(
        org.sigilaris.core.datatype.Utf8("witness"),
        ref.witnessDigest,
        bytes,
      )
    yield ref

  private[v2] def persist(
      current: SafetyState,
      operation: JournalOperation,
      payload: JournalPayload,
  ): Result[F, SafetyState] =
    for
      _ <- check(
        current.sequence < Long.MaxValue,
        RuntimeFailureCode.CapacityUnavailable,
        "journal sequence exhausted",
      )
      bytes    <- core(JournalPayload.codec.encode(payload))
      digest   <- core(JournalPayload.digest(payload))
      previous <- pure(SafetyJournalReduction.head(current))
      record = JournalRecord(
        2L,
        current.sequence + 1L,
        operation,
        previous,
        bytes,
        digest,
        JournalStatus.Committed,
      )
      projected <- pure(SafetyJournalReduction(current, record, anchor))
      complete  <- rebuild(projected)
      _         <- verifyAcquisitions(current, record)
      _         <- payload.rebuiltIndexDigest.traverse_(expected =>
        core(ConflictIndexRow.inventoryDigest(complete.index)).flatMap(actual =>
          check(
            actual == expected,
            RuntimeFailureCode.IncompleteIndex,
            "index rebuild digest differs from complete witnesses",
          ),
        ),
      )
      _ <- EitherT(
        Async[F].uncancelable(_ =>
          ready.set(false) *> (journal.append(
            record.copy(status = JournalStatus.Prepared),
          ) *>
            journal.append(record)).value.flatMap:
            case Left(error) =>
              Async[F].pure(Left[V2RuntimeFailure, Unit](error))
            case Right(_) =>
              state.set(complete) *> ready
                .set(true)
                .as(Right[V2RuntimeFailure, Unit](())),
        ),
      )
    yield complete

  /** Prefix replay checks all witness/index/acquisition structure while the
    * store remains fenced. Transition readiness belongs to the complete
    * history: its original Bound/Prepared prefixes cannot already be
    * Opened/Committed.
    */
  private def rebuildOriginal(current: SafetyState): Result[F, SafetyState] =
    (for
      pairs <- current.witnesses.values.toVector.traverse(ref =>
        WitnessStorage.read(journal, ref).map(ref.witnessDigest -> _),
      )
      index <- pure(SafetyJournalReduction.index(current, pairs.toMap))
      complete = current.copy(index = index)
      _ <- authenticateRecovered(complete)
    yield complete).leftSemiflatMap(error => ready.set(false).as(error))

  private def rebuild(current: SafetyState): Result[F, SafetyState] =
    rebuildOriginal(current)
      .flatTap(complete => recoveryAuthentication.verify(complete, journal))
      .leftSemiflatMap(error => ready.set(false).as(error))

  private def authenticateRecovered(current: SafetyState): Result[F, Unit] =
    for
      validators <- core(profile.authentication.historicalValidators(context))
      _          <- check(
        current.intents.valuesIterator.forall(intent =>
          validators.contains(intent.validatorId),
        ) &&
          current.consensusIntents.valuesIterator.forall(intent =>
            validators.contains(intent.validatorId),
          ),
        RuntimeFailureCode.InvalidSignature,
        "retained voter is absent from trusted validator history",
      )
      _ <- current.intents.values.toVector.traverse_ { intent =>
        intent.kind match
          case VoteIntentKind.Lock =>
            core(LockSubject.codec.decode(intent.subject)).flatMap(subject =>
              core(
                profile.authentication
                  .verifyFinalizedBase(context, subject.admissionBase),
              ),
            )
          case VoteIntentKind.Effect =>
            for
              subject <- core(EffectSubject.codec.decode(intent.subject))
              ref     <- EitherT.fromOption[F](
                intent.witness,
                V2RuntimeFailure.at(
                  RuntimeFailureCode.IncompleteWitness,
                  "effect intent has no complete witness",
                ),
              )
              witness <- WitnessStorage.read(journal, ref)
              footprint = Footprint(
                2L,
                witness.entries
                  .filter(_.access == WitnessAccess.Read)
                  .map(_.identity),
                witness.entries
                  .filter(_.access == WitnessAccess.Write)
                  .map(_.identity),
              )
              actual <- core(Footprint.actualCommitment(footprint))
              _      <- check(
                actual == subject.actualFootprintCommitment,
                RuntimeFailureCode.IncompleteWitness,
                "retained effect witness differs from its durable signed footprint",
              )
            yield ()
      }
      _ <- current.lockCertificates.values.toVector.traverse_(certificate =>
        core(
          LockCertificate.verify(
            certificate,
            profile.manifest,
            profile.authentication,
          ),
        ),
      )
      _ <- current.effectCertificates.values.toVector.traverse_(certificate =>
        EitherT
          .fromOption[F](
            current.lockCertificates
              .get(certificate.subject.inputLockCertificateId),
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofUnavailable,
              "retained effect has no complete input lock certificate",
            ),
          )
          .flatMap(lock =>
            core(
              EffectCertificate.verify(
                certificate,
                lock,
                profile.manifest,
                profile.authentication,
              ),
            ),
          ),
      )
    yield ()

  private def validDeadline(deadline: Height): Result[F, Unit] =
    publication
      .finalizedHeight(context)
      .flatMap(height =>
        check(
          height.toBigNat.toBigInt < deadline.toBigNat.toBigInt,
          RuntimeFailureCode.AlreadyTerminal,
          "no future inclusion remains at the signed deadline",
        ),
      )

  private def unresolved(
      current: SafetyState,
      execution: ExecutionId,
  ): Result[F, Unit] =
    pure(ExactTerminalEvidence.resolution(current, context, execution)).flatMap(
      resolution =>
        check(
          resolution.isEmpty,
          RuntimeFailureCode.AlreadyTerminal,
          "execution has a retained exact nonapplication outcome",
        ),
    ) *> check(
      !current.appliedEntries.contains(execution),
      RuntimeFailureCode.AlreadyApplied,
      "execution already has a canonical application",
    ) *>
      check(
        !current.locks
          .get(execution)
          .exists(_.lifecycle != ClaimLifecycle.Live) &&
          !current.claims.valuesIterator.exists(claim =>
            claim.owner.executionId == execution && claim.lifecycle != ClaimLifecycle.Live,
          ),
        RuntimeFailureCode.AlreadyTerminal,
        "execution has a retained terminal outcome",
      )

  private def eligible(
      current: SafetyState,
      execution: ExecutionId,
      deadline: Height,
  ): Result[F, Unit] =
    unresolved(current, execution) *> validDeadline(deadline)

  private def lockClaim(subject: LockSubject): Result[F, LiveLockClaim] =
    core(LockSubject.codec.encode(subject)).map(bytes =>
      LiveLockClaim(
        2L,
        subject.context,
        subject.executionId,
        Commitment.hash(LockSubject.Domain, bytes),
        subject.inputs,
        subject.lastInclusionHeight,
        ClaimLifecycle.Live,
        None,
      ),
    )

  private def checkLockState(
      current: SafetyState,
      incoming: LiveLockClaim,
  ): Result[F, Unit] =
    for
      _ <- check(
        incoming.context == context,
        RuntimeFailureCode.DomainMismatch,
        "lock context differs from installed context",
      )
      _ <- unresolved(current, incoming.executionId)
      _ <- check(
        current.locks.get(incoming.executionId).forall(_ == incoming),
        RuntimeFailureCode.Conflict,
        "execution lock subject or deadline differs",
      )
      incomingIds = incoming.inputIds.toSet
      _ <- check(
        !current.locks.valuesIterator.exists(old =>
          old.lifecycle == ClaimLifecycle.Live && old.executionId != incoming.executionId &&
            old.inputIds.exists(incomingIds.contains),
        ),
        RuntimeFailureCode.Conflict,
        "eligible identities have another live lock",
      )
      _ <- check(
        !current.index.exists(row =>
          incomingIds.contains(row.identity) && row.owners.exists(indexed =>
            current.claims
              .get(indexed.ownerDigest)
              .exists(_.owner.executionId != incoming.executionId),
          ),
        ),
        RuntimeFailureCode.Conflict,
        "eligible identities overlap another execution's complete reservation",
      )
    yield ()

  private def checkLock(
      current: SafetyState,
      incoming: LiveLockClaim,
  ): Result[F, Unit] =
    checkLockState(current, incoming) *> validDeadline(
      incoming.lastInclusionHeight,
    )

  private def addClaim(
      current: SafetyState,
      id: Hash,
      claim: ReservationClaim,
      witness: ReservationWitness,
  ): Result[F, SafetyState] =
    core(ReservationClaim.digest(claim)).map { digest =>
      val rows = witness.entries
        .foldLeft(current.index.map(row => row.identity -> row).toMap) {
          (lookup, entry) =>
            val indexed =
              IndexedOwner(id, digest, entry.access, claim.lastInclusionHeight)
            val owners = lookup
              .get(entry.identity)
              .fold(Vector.empty[IndexedOwner])(_.owners)
              .filterNot(_.ownerDigest == id) :+ indexed
            lookup.updated(
              entry.identity,
              ConflictIndexRow(
                2L,
                entry.identity,
                owners.sortBy(_.ownerDigest.bytes.toHex),
              ),
            )
        }
        .values
        .toVector
        .sortBy(_.identity.toHex)
      current.copy(claims = current.claims.updated(id, claim), index = rows)
    }

  /** Replays original acquisition decisions against their preceding live state.
    * This also re-fetches cross-proposal ordering evidence on restart. Only an
    * authenticated atomic ApplicationPrepare may introduce canonical overlap.
    * Certificate archives add constraints without granting a new local vote.
    */
  private def verifyAcquisitions(
      before: SafetyState,
      record: JournalRecord,
  ): Result[F, Unit] =
    for
      payload   <- core(JournalPayload.codec.decode(record.payload))
      withLocks <- payload.locks
        .filter(_.lifecycle == ClaimLifecycle.Live)
        .foldLeft(EitherT.rightT[F, V2RuntimeFailure](before))((acc, lock) =>
          acc.flatMap(current =>
            (if record.operation == JournalOperation.CertificateImport then
               check(
                 current.locks.get(lock.executionId).forall(_ == lock),
                 RuntimeFailureCode.Conflict,
                 "certificate archive changes a retained lock subject",
               )
             else checkLockState(current, lock)).as(
              current
                .copy(locks = current.locks.updated(lock.executionId, lock)),
            ),
          ),
        )
      _ <-
        if record.operation == JournalOperation.ApplicationPrepare then
          EitherT.rightT[F, V2RuntimeFailure](())
        else
          payload.claims
            .filter(_.lifecycle == ClaimLifecycle.Live)
            .foldLeft(EitherT.rightT[F, V2RuntimeFailure](withLocks))(
              (acc, claim) =>
                acc.flatMap(current =>
                  for
                    witness <- WitnessStorage.read(journal, claim.witness)
                    _       <- checkReservation(
                      current,
                      claim,
                      witness,
                      _ => EitherT.rightT[F, V2RuntimeFailure](()),
                    )
                    id   <- core(Owner.digest(claim.owner))
                    next <- addClaim(current, id, claim, witness)
                  yield next,
                ),
            )
            .void
    yield ()

  private def overlap(existing: Owner, incoming: Owner): Result[F, Unit] =
    if existing == incoming || existing.executionId == incoming.executionId then
      EitherT.rightT[F, V2RuntimeFailure](())
    else if existing.scope.kind == ScopeKind.FastAdmission || incoming.scope.kind == ScopeKind.FastAdmission
    then
      check(
        false,
        RuntimeFailureCode.Conflict,
        "fast reservations conflict with other overlapping executions",
      )
    else if existing.scope.parentBlockId == incoming.scope.parentBlockId && existing.scope.planRoot == incoming.scope.planRoot &&
      existing.scope.candidateHeight == incoming.scope.candidateHeight
    then
      check(
        existing.scope.kind == ScopeKind.ConsensusOrdered && incoming.scope.kind == ScopeKind.ConsensusOrdered &&
          existing.scope.entryIndex != incoming.scope.entryIndex,
        RuntimeFailureCode.Conflict,
        "same-plan overlap is not authenticated ordered execution",
      )
    else ordering.verify(existing, incoming)

  private def checkReservation(
      current: SafetyState,
      claim: ReservationClaim,
      witness: ReservationWitness,
      deadlineCheck: Height => Result[F, Unit],
  ): Result[F, Unit] =
    for
      _ <- core(ReservationClaim.validate(claim))
      _ <- core(WitnessRef.verify(claim.witness, witness))
      _ <- check(
        claim.owner.context == context,
        RuntimeFailureCode.DomainMismatch,
        "reservation context differs from installed context",
      )
      _ <- unresolved(current, claim.owner.executionId) *> deadlineCheck(
        claim.lastInclusionHeight,
      )
      _ <- check(
        current.claims.valuesIterator
          .filter(_.owner.executionId == claim.owner.executionId)
          .forall(
            _.lastInclusionHeight == claim.lastInclusionHeight,
          ) && current.locks
          .get(claim.owner.executionId)
          .forall(_.lastInclusionHeight == claim.lastInclusionHeight),
        RuntimeFailureCode.DeadlineMismatch,
        "same execution cannot acquire a different deadline",
      )
      id <- core(Owner.digest(claim.owner))
      _  <- check(
        current.claims.get(id).forall(_ == claim),
        RuntimeFailureCode.Conflict,
        "owner was rebound to another witness or deadline",
      )
      incomingIdentities = witness.entries.map(_.identity).toSet
      _ <- check(
        !current.locks.valuesIterator.exists(lock =>
          lock.lifecycle == ClaimLifecycle.Live && lock.executionId != claim.owner.executionId &&
            lock.inputIds.exists(incomingIdentities.contains),
        ),
        RuntimeFailureCode.Conflict,
        "reservation conflicts with another execution's eligible lock",
      )
      incoming = witness.entries
        .map(entry => entry.identity -> entry.access)
        .toMap
      conflicts = current.index
        .flatMap(row =>
          incoming
            .get(row.identity)
            .toList
            .toVector
            .flatMap(mode =>
              row.owners.filter(owner =>
                mode == WitnessAccess.Write || owner.mode == WitnessAccess.Write,
              ),
            ),
        )
        .map(_.ownerDigest)
        .distinct
      _ <- conflicts.traverse_(other =>
        current.claims
          .get(other)
          .fold(
            check(
              false,
              RuntimeFailureCode.IncompleteIndex,
              "index owner is missing",
            ),
          )(old => overlap(old.owner, claim.owner)),
      )
    yield ()

  private def capacityFor(
      current: SafetyState,
      additions: Vector[ReservationClaim],
  ): Result[F, Unit] =
    for
      pairs <- additions.traverse(claim =>
        core(Owner.digest(claim.owner)).map(_ -> claim),
      )
      live = (current.claims ++ pairs).valuesIterator
        .filter(_.lifecycle == ClaimLifecycle.Live)
        .toVector
      bytes = live
        .map(_.witness)
        .distinct
        .foldLeft(BigInt(0))((sum, ref) => sum + ref.encodedBytes)
      _ <- check(
        live.size.toLong <= capacity.maxLiveOwners && bytes <= BigInt(
          capacity.maxWitnessBytes,
        ),
        RuntimeFailureCode.CapacityUnavailable,
        "local reservation capacity is unavailable",
      )
    yield ()

  private def reservations(
      current: SafetyState,
      values: Vector[(Owner, ReservationWitness, Height)],
      deadlineCheck: Height => Result[F, Unit],
  ): Result[F, (Vector[ReservationClaim], Vector[WitnessRef])] =
    for
      prepared <- values.traverse((owner, witness, deadline) =>
        core(WitnessRef.fromWitness(witness)).map(ref =>
          ReservationClaim(
            2L,
            owner,
            ref,
            deadline,
            ClaimLifecycle.Live,
            None,
          ) -> witness,
        ),
      )
      _ <- capacityFor(current, prepared.map(_._1))
      _ <- prepared.foldLeft(EitherT.rightT[F, V2RuntimeFailure](current))(
        (acc, pair) =>
          acc.flatMap(intermediate =>
            for
              _ <- checkReservation(
                intermediate,
                pair._1,
                pair._2,
                deadlineCheck,
              )
              id          <- core(Owner.digest(pair._1.owner))
              claimDigest <- core(ReservationClaim.digest(pair._1))
              added = pair._2.entries
                .foldLeft(
                  intermediate.index.map(row => row.identity -> row).toMap,
                ) { (rows, entry) =>
                  val indexed = IndexedOwner(
                    id,
                    claimDigest,
                    entry.access,
                    pair._1.lastInclusionHeight,
                  )
                  val owners = rows
                    .get(entry.identity)
                    .fold(Vector.empty[IndexedOwner])(_.owners)
                    .filterNot(_.ownerDigest == id) :+ indexed
                  rows.updated(
                    entry.identity,
                    ConflictIndexRow(
                      2L,
                      entry.identity,
                      owners.sortBy(_.ownerDigest.bytes.toHex),
                    ),
                  )
                }
                .values
                .toVector
                .sortBy(_.identity.toHex)
            yield intermediate.copy(
              claims = intermediate.claims.updated(id, pair._1),
              index = added,
            ),
          ),
      )
      refs  <- prepared.map(_._2).distinct.traverse(retainWitness)
      keyed <- prepared
        .map(_._1)
        .traverse(claim =>
          core(Owner.digest(claim.owner)).map(id => id -> claim),
        )
    yield (
      keyed.sortBy(_._1.bytes.toHex).map(_._2),
      refs.distinct.sortBy(_.witnessDigest.bytes.toHex),
    )

  /** Only the finalized application verifier invokes this helper. The full
    * finality/inventory evidence and all selected owners become authoritative
    * in one record. A minority local vote cannot veto canonical
    * materialization.
    */
  private[v2] def prepareApplication(
      current: SafetyState,
      prepared: PreparedApplication,
      finalized: VerifiedApplicationBatch,
  ): Result[F, SafetyState] =
    val request = finalized.request
    for
      _ <- check(
        request.context == context && prepared.batch.context == context,
        RuntimeFailureCode.DomainMismatch,
        "application context differs from installed context",
      )
      owners <- request.reservations.traverse(value =>
        core(Owner.digest(value.owner)),
      )
      _ <- check(
        prepared.batch.entries.map(_.ownerDigest) == owners &&
          prepared.batch.entries.map(_.executionId) == request.reservations.map(
            _.owner.executionId,
          ) &&
          prepared.batch.entries.map(
            _.lastInclusionHeight,
          ) == request.reservations.map(_.lastInclusionHeight) &&
          prepared.batch.candidateHeight == request.candidateHeight && prepared.batch.parentBlockId == request.parentBlockId &&
          prepared.batch.priorStateRoot == request.parentStateRoot && prepared.batch.nextStateRoot == request.validatedStateRoot,
        RuntimeFailureCode.InvalidRequest,
        "application preparation differs from independently executed candidate",
      )
      pairs <- request.reservations.traverse(value =>
        for
          _   <- unresolved(current, value.owner.executionId)
          ref <- core(WitnessRef.fromWitness(value.witness))
          claim = ReservationClaim(
            2L,
            value.owner,
            ref,
            value.lastInclusionHeight,
            ClaimLifecycle.Live,
            None,
          )
          id <- core(Owner.digest(value.owner))
          _  <- check(
            current.claims.get(id).forall(_ == claim),
            RuntimeFailureCode.Conflict,
            "canonical application cannot rebind an existing owner",
          )
        yield (id, claim, value.witness),
      )
      _      <- capacityFor(current, pairs.map(_._2))
      refs   <- pairs.map(_._3).distinct.traverse(retainWitness)
      result <- persist(
        current,
        JournalOperation.ApplicationPrepare,
        JournalPayload.empty.copy(
          claims = pairs.sortBy(_._1.bytes.toHex).map(_._2),
          witnesses = refs.sortBy(_.witnessDigest.bytes.toHex),
          applicationPreparation = Some(prepared),
        ),
      )
    yield result

  def snapshot: Result[F, SafetyState] =
    transaction(current => EitherT.rightT[F, V2RuntimeFailure](current))

  def claimLock(
      request: VerifiedLockRequest,
      validatorId: Text,
  ): Result[F, VoteIntent] = transaction: current =>
    for
      _     <- pure(ExactOwnershipBinding.lock(current, request))
      claim <- lockClaim(request.subject)
      bytes <- core(LockSubject.codec.encode(request.subject))
      intent = VoteIntent(
        2L,
        context,
        validatorId,
        VoteIntentKind.Lock,
        claim.executionId,
        bytes,
        claim.subjectDigest,
        None,
        None,
        claim.lastInclusionHeight,
      )
      id <- core(VoteIntent.digest(intent))
      _  <-
        if current.intents.contains(id) then
          eligible(
            current,
            intent.executionId,
            intent.lastInclusionHeight,
          ) *> rebuild(current).void
        else
          for
            _        <- checkLock(current, claim)
            evidence <- core(VotingEvidenceStorage.encodeLock(request))
            _ <- retainBlob(VotingEvidenceStorage.Namespace, id, evidence)
            _ <- persist(
              current,
              JournalOperation.VoteIntent,
              JournalPayload.empty
                .copy(intents = Vector(intent), locks = Vector(claim)),
            )
          yield ()
    yield intent

  def claimEffect(
      request: VerifiedEffectRequest,
      validatorId: Text,
  ): Result[F, VoteIntent] = transaction: initial =>
    for
      _     <- pure(ExactOwnershipBinding.effect(initial, request))
      _     <- publication.verifyEffectState(request)
      ref   <- core(WitnessRef.fromWitness(request.witness))
      bytes <- core(EffectSubject.codec.encode(request.subject))
      intent = VoteIntent(
        2L,
        context,
        validatorId,
        VoteIntentKind.Effect,
        request.subject.executionId,
        bytes,
        Commitment.hash(EffectSubject.Domain, bytes),
        Some(request.owner),
        Some(ref),
        request.subject.lastInclusionHeight,
      )
      id <- core(VoteIntent.digest(intent))
      _  <-
        if initial.intents.contains(id) then
          eligible(
            initial,
            intent.executionId,
            intent.lastInclusionHeight,
          ) *> rebuild(initial).void
        else
          for
            current <- importLockUnderGate(initial, request.sourceLock)
            pair    <- reservations(
              current,
              Vector(
                (
                  request.owner,
                  request.witness,
                  request.subject.lastInclusionHeight,
                ),
              ),
              validDeadline,
            )
            evidence <- core(VotingEvidenceStorage.encodeEffect(request))
            _ <- retainBlob(VotingEvidenceStorage.Namespace, id, evidence)
            _ <- persist(
              current,
              JournalOperation.VoteIntent,
              JournalPayload.empty.copy(
                intents = Vector(intent),
                claims = pair._1,
                witnesses = pair._2,
              ),
            )
          yield ()
      _ <- EitherT.liftF(effectPermissions.update(_.updated(id, request)))
    yield intent

  def claimConsensus(
      request: VerifiedConsensusProposal,
      validatorId: Text,
  ): Result[F, ConsensusVoteIntent] = transaction: current =>
    for
      _ <- pure(ExactOwnershipBinding.consensus(current, request))
      _ <- check(
        request.context == context,
        RuntimeFailureCode.DomainMismatch,
        "proposal context differs from installed context",
      )
      _ <- check(
        request.unsignedVote.voter.value == validatorId.asString,
        RuntimeFailureCode.InvalidRequest,
        "proposal vote belongs to a different signer",
      )
      _      <- publication.verifyConsensusState(request)
      owners <- request.reservations.traverse(value =>
        core(Owner.digest(value.owner)),
      )
      root <- core(ExecutionPlan.computeRoot(request.plan))
      intent = ConsensusVoteIntent(
        2L,
        context,
        validatorId,
        Vote.signBytes(request.unsignedVote),
        request.proposal.proposalId.toUInt256,
        request.proposal.targetBlockId.toUInt256,
        root.toUInt256,
        request.bodyRoot,
        request.validatedStateRoot,
        owners,
      )
      fields      <- pure(ConsensusSignFields.validateIntent(intent))
      priorFields <- pure(
        current.consensusIntents.values.toVector
          .traverse(ConsensusSignFields.validateIntent),
      )
      _ <- check(
        !priorFields.exists(old =>
          old.equivocationKey == fields.equivocationKey && old != fields,
        ),
        RuntimeFailureCode.Conflict,
        "another proposal already owns this durable HotStuff voting window",
      )
      id <- core(ConsensusVoteIntent.digest(intent))
      _  <-
        if current.consensusIntents.contains(id) then
          request.reservations.traverse_(value =>
            eligible(
              current,
              value.owner.executionId,
              value.lastInclusionHeight,
            ),
          ) *> rebuild(current).void
        else
          for
            pair <- reservations(
              current,
              request.reservations.map(value =>
                (value.owner, value.witness, value.lastInclusionHeight),
              ),
              validDeadline,
            )
            evidence <- core(VotingEvidenceStorage.encodeConsensus(request))
            _ <- retainBlob(VotingEvidenceStorage.Namespace, id, evidence)
            _ <- persist(
              current,
              JournalOperation.VoteIntent,
              JournalPayload.empty.copy(
                consensusIntent = Some(intent),
                claims = pair._1,
                witnesses = pair._2,
              ),
            )
          yield ()
      _ <- EitherT.liftF(consensusPermissions.update(_.updated(id, request)))
    yield intent

  def withSigningPermission[A](intentDigest: Hash)(sign: F[A]): Result[F, A] =
    transaction: current =>
      val permission = current.intents.get(intentDigest) match
        case Some(intent) =>
          eligible(
            current,
            intent.executionId,
            intent.lastInclusionHeight,
          ) *> (intent.kind match
            case VoteIntentKind.Lock => EitherT.rightT[F, V2RuntimeFailure](())
            case VoteIntentKind.Effect =>
              EitherT
                .liftF(effectPermissions.get)
                .flatMap(values =>
                  EitherT
                    .fromOption[F](
                      values.get(intentDigest),
                      V2RuntimeFailure.at(
                        RuntimeFailureCode.RecoveryRequired,
                        "effect request must be reverified after restart",
                      ),
                    )
                    .flatMap(publication.verifyEffectState),
                ))
        case None =>
          for
            intent <- EitherT.fromOption[F](
              current.consensusIntents.get(intentDigest),
              V2RuntimeFailure.at(
                RuntimeFailureCode.InvalidRequest,
                "no retained durable intent",
              ),
            )
            request <- EitherT
              .liftF(consensusPermissions.get)
              .flatMap(values =>
                EitherT.fromOption[F](
                  values.get(intentDigest),
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.RecoveryRequired,
                    "consensus proposal must be reverified after restart",
                  ),
                ),
              )
            _          <- publication.verifyConsensusState(request)
            authorized <- EitherT.liftF(exactPermissions.get)
            _          <- check(
              request.reservations
                .filter(_.exactBinding.nonEmpty)
                .forall(reservation =>
                  authorized
                    .getOrElse(intentDigest, Set.empty[ExecutionId])
                    .contains(reservation.owner.executionId),
                ),
              RuntimeFailureCode.RecoveryRequired,
              "exact candidate requires successful mode, output and ancestry verification before signing",
            )
            _ <- intent.ownerDigests.traverse_(id =>
              EitherT
                .fromOption[F](
                  current.claims.get(id),
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.IncompleteWitness,
                    "intent owner is unavailable",
                  ),
                )
                .flatMap(claim =>
                  eligible(
                    current,
                    claim.owner.executionId,
                    claim.lastInclusionHeight,
                  ),
                ),
            )
          yield ()
      val material = current.intents.get(intentDigest) match
        case Some(intent) =>
          val preimage = intent.kind match
            case VoteIntentKind.Lock =>
              LockSubject.codec
                .decode(intent.subject)
                .flatMap(LockSubject.signingPreimage)
            case VoteIntentKind.Effect =>
              EffectSubject.codec
                .decode(intent.subject)
                .flatMap(EffectSubject.signingPreimage)
          core(preimage).map(bytes =>
            JournalSafetyStore.ActiveSigning(
              intent.context,
              intent.validatorId,
              bytes,
              current.committed,
            ),
          )
        case None =>
          EitherT
            .fromOption[F](
              current.consensusIntents.get(intentDigest),
              V2RuntimeFailure.at(
                RuntimeFailureCode.InvalidRequest,
                "active signing intent is unavailable",
              ),
            )
            .map(intent =>
              JournalSafetyStore.ActiveSigning(
                intent.context,
                intent.validatorId,
                intent.unsignedVoteSignBytes,
                current.committed,
              ),
            )
      permission *> rebuild(current).void *> material.flatMap(lease =>
        EitherT(
          Async[F]
            .guarantee(
              activeSigning.set(Some(lease)) *> sign.attempt,
              activeSigning.set(None),
            )
            .map(
              _.leftMap(error =>
                V2RuntimeFailure.at(
                  RuntimeFailureCode.SignerFailure,
                  Option(error.getMessage).getOrElse(error.getClass.getName),
                ),
              ),
            ),
        ),
      )

  private def importLockUnderGate(
      current: SafetyState,
      verified: VerifiedLockCertificate,
  ): Result[F, SafetyState] =
    for
      id   <- core(LockCertificate.id(verified.certificate))
      next <-
        if current.lockCertificates.contains(id) then
          EitherT.rightT[F, V2RuntimeFailure](current)
        else
          for
            live          <- lockClaim(verified.certificate.subject)
            exactTerminal <- pure(
              ExactTerminalEvidence
                .resolution(current, context, live.executionId),
            )
            terminal = current.locks
              .get(live.executionId)
              .filter(_.lifecycle != ClaimLifecycle.Live)
              .map(value => (value.lifecycle, value.terminal))
              .orElse(
                current.claims.valuesIterator
                  .find(value =>
                    value.owner.executionId == live.executionId &&
                      value.lifecycle != ClaimLifecycle.Live,
                  )
                  .map(value => (value.lifecycle, value.terminal)),
              )
              .orElse(
                exactTerminal
                  .map(value => (ClaimLifecycle.ExpiredUnapplied, Some(value))),
              )
            claim = terminal.fold(live)((lifecycle, resolution) =>
              live.copy(lifecycle = lifecycle, terminal = resolution),
            )
            _ <- check(
              current.locks.get(live.executionId).forall(_ == claim),
              RuntimeFailureCode.Conflict,
              "certificate archive changes a retained lock subject",
            )
            next <- persist(
              current,
              JournalOperation.CertificateImport,
              JournalPayload.empty.copy(
                locks = Vector(claim),
                importedCertificates =
                  Vector(ImportedCertificate.Lock(verified.certificate)),
              ),
            )
          yield next
    yield next

  def importLock(certificate: VerifiedLockCertificate): Result[F, Unit] =
    transaction(current => importLockUnderGate(current, certificate).void)
  def importEffect(certificate: VerifiedEffectCertificate): Result[F, Unit] =
    transaction: initial =>
      for
        current <- importLockUnderGate(initial, certificate.lockCertificate)
        id      <- core(EffectCertificate.id(certificate.certificate))
        _       <-
          if current.effectCertificates.contains(id) then
            EitherT.rightT[F, V2RuntimeFailure](())
          else
            persist(
              current,
              JournalOperation.CertificateImport,
              JournalPayload.empty.copy(
                importedCertificates =
                  Vector(ImportedCertificate.Effect(certificate.certificate)),
              ),
            ).void
      yield ()

  def putInactive(witness: ReservationWitness): Result[F, WitnessRef] =
    transaction: _ =>
      core(WitnessRef.fromWitness(witness)).flatMap(ref =>
        check(
          ref.encodedBytes <= capacity.maxWitnessBytes,
          RuntimeFailureCode.CapacityUnavailable,
          "local witness capacity unavailable",
        ) *>
          retainWitness(witness),
      )
  def read(ref: WitnessRef): Result[F, ReservationWitness] =
    transaction(_ => WitnessStorage.read(journal, ref))
  def readChunk(ref: WitnessRef, index: Int): Result[F, WitnessChunk] =
    transaction(_ => WitnessStorage.chunk(journal, ref, index))

  private def inventory(current: SafetyState): Result[F, VotingRecovery] =
    for
      head  <- pure(SafetyJournalReduction.head(current))
      index <- core(ConflictIndexRow.inventoryDigest(current.index))
    yield VotingRecovery(
      current.sequence,
      SafetyInventory.digest(head, index),
      index,
    )

  def verifyAndRebuildIndex: Result[F, ReservationRecovery] = transaction:
    current =>
      for
        rebuilt <- rebuild(current)
        digest  <- core(ConflictIndexRow.inventoryDigest(rebuilt.index))
        next    <- persist(
          current,
          JournalOperation.IndexRebuild,
          JournalPayload.empty.copy(rebuiltIndexDigest = Some(digest)),
        )
        result <- inventory(next)
      yield ReservationRecovery(
        result.highestSequence,
        result.inventoryDigest,
        result.indexDigest,
      )

  def recover: Result[F, VotingRecovery] = locked:
    for
      _ <- EitherT.liftF(
        ready.set(false) *> effectPermissions.set(
          Map.empty,
        ) *> consensusPermissions.set(Map.empty) *> exactPermissions.set(
          Map.empty,
        ),
      )
      records   <- journal.recover
      history   <- pure(JournalHistory.validate(records))
      recovered <- history.records
        .filter(_.status == JournalStatus.Committed)
        .foldLeft(EitherT.rightT[F, V2RuntimeFailure](SafetyState.empty))(
          (acc, record) =>
            acc.flatMap(current =>
              pure(SafetyJournalReduction(current, record, anchor))
                .flatMap(rebuildOriginal)
                .flatTap(_ => verifyAcquisitions(current, record))
                .flatTap(next =>
                  core(JournalPayload.codec.decode(record.payload)).flatMap(
                    payload =>
                      payload.rebuiltIndexDigest.traverse_(expected =>
                        core(ConflictIndexRow.inventoryDigest(next.index))
                          .flatMap(actual =>
                            check(
                              actual == expected,
                              RuntimeFailureCode.IncompleteIndex,
                              "retained rebuilt index differs from authoritative witnesses",
                            ),
                          ),
                      ),
                  ),
                ),
            ),
        )
      completed <- history.pending.fold(
        rebuild(recovered),
      )(pending =>
        val committed = pending.copy(status = JournalStatus.Committed)
        for
          next <- pure(SafetyJournalReduction(recovered, committed, anchor))
            .flatMap(rebuild)
          _       <- verifyAcquisitions(recovered, committed)
          payload <- core(JournalPayload.codec.decode(committed.payload))
          _       <- payload.rebuiltIndexDigest.traverse_(expected =>
            core(ConflictIndexRow.inventoryDigest(next.index)).flatMap(actual =>
              check(
                actual == expected,
                RuntimeFailureCode.IncompleteIndex,
                "pending index differs from complete witness coverage",
              ),
            ),
          )
          _ <- journal.append(committed)
        yield next,
      )
      _      <- EitherT.liftF(state.set(completed) *> ready.set(true))
      result <- inventory(completed)
    yield result

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object JournalSafetyStore:
  private final case class ActiveSigning(
      context: DomainContext,
      validatorId: Text,
      preimage: Bytes,
      committed: Vector[JournalRecord],
  )
  def open[F[_]: Async](
      anchor: ApplicationAnchor,
      journal: DurableJournal[F],
      publication: SafetyPublication[F],
      profile: SafetyProfile,
      recoveryAuthentication: SafetyRecoveryAuthentication[F],
      ordering: ReservationOrdering[F],
      capacity: SafetyCapacity,
  ): Result[F, JournalSafetyStore[F]] =
    for
      _ <- EitherT.fromEither[F](
        RuntimeCheck.core(DomainContext.validateActive(anchor.context)),
      )
      configured <- EitherT.fromEither[F](
        RuntimeCheck.core(ProtocolManifest.context(profile.manifest)),
      )
      _ <- EitherT.fromEither[F](
        RuntimeCheck.require(
          configured == anchor.context,
          RuntimeFailureCode.DomainMismatch,
          "installed anchor differs from trusted manifest",
        ),
      )
      _ <- EitherT.fromEither[F](
        RuntimeCheck.require(
          capacity.maxLiveOwners >= 0L && capacity.maxWitnessBytes >= 0L,
          RuntimeFailureCode.InvalidRequest,
          "local capacity must be nonnegative",
        ),
      )
      gate    <- EitherT.liftF(Semaphore[F](1L))
      state   <- EitherT.liftF(Ref.of[F, SafetyState](SafetyState.empty))
      ready   <- EitherT.liftF(Ref.of[F, Boolean](false))
      effects <- EitherT.liftF(
        Ref.of[F, Map[Hash, VerifiedEffectRequest]](Map.empty),
      )
      consensus <- EitherT.liftF(
        Ref.of[F, Map[Hash, VerifiedConsensusProposal]](Map.empty),
      )
      exact  <- EitherT.liftF(Ref.of[F, Map[Hash, Set[ExecutionId]]](Map.empty))
      active <- EitherT.liftF(Ref.of[F, Option[ActiveSigning]](None))
      store = new JournalSafetyStore(
        anchor,
        journal,
        publication,
        profile,
        recoveryAuthentication,
        ordering,
        capacity,
        gate,
        state,
        ready,
        effects,
        consensus,
        exact,
        active,
      )
      _ <- store.recover
    yield store
