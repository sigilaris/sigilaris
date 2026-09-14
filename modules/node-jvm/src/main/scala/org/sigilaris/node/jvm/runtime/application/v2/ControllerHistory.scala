package org.sigilaris.node.jvm.runtime.application.v2

import cats.syntax.all.*
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.crypto.{CryptoOps, Signature}
import org.sigilaris.core.datatype.UInt256

private[v2] final case class ControllerHistory(
    snapshot: ControllerSnapshot,
    signing: Map[Hash, ControllerSigningIntent],
    signatures: Map[Hash, Bytes],
    writes: Map[Hash, ControllerWriteIntent],
    writeReceipts: Map[Hash, Bytes],
    enforcements: Map[Hash, ControllerEnforcement],
    fences: Map[Hash, SignedFencePromise],
    closures: Map[Hash, ControllerWriteClosure],
):
  def contexts: Vector[DomainContext] =
    (snapshot.configuration.initialHistory.contexts ++ signing.valuesIterator
      .map(_.material.context) ++ writes.valuesIterator.map(
      _.material.context,
    ) ++ enforcements.valuesIterator.map(
      _.promise.context,
    ) ++ closures.valuesIterator.map(_.context)).distinct

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private[v2] object ControllerHistory:
  private def check(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(condition, RuntimeFailureCode.JournalCorrupt, detail)
  private def core[A](
      value: Either[CoreFailure, A],
  ): Either[V2RuntimeFailure, A] = RuntimeCheck.core(value)
  def verifySignature(
      preimage: Bytes,
      signature: Bytes,
      key: Bytes,
  ): Either[V2RuntimeFailure, Unit] = for
    _ <- core(
      ValidatorSignature.validate(
        ValidatorSignature(
          org.sigilaris.core.datatype.Utf8("controller"),
          signature,
        ),
      ),
    )
    sig = Signature(
      BigInt(1, signature.take(8L).toArray).toInt,
      UInt256.unsafeFromBytesBE(signature.slice(8L, 40L)),
      UInt256.unsafeFromBytesBE(signature.drop(40L)),
    )
    recovered <- CryptoOps
      .recover(sig, CryptoOps.keccak256(preimage.toArray))
      .left
      .map(e => V2RuntimeFailure.at(RuntimeFailureCode.InvalidSignature, e.msg))
    _ <- check(
      recovered.toBytes == key,
      "controller signature does not recover the exclusive key",
    )
  yield ()

  def applies(
      fence: FencePromise,
      material: ControllerSigningMaterial,
  ): Boolean = fence.scope match
    case FenceScope.ApplicationIssuance =>
      fence.context == material.context && material.kind != ControllerSigningKind.Consensus
    case FenceScope.ConsensusProfileAtOrAbove =>
      fence.context == material.context && material.kind == ControllerSigningKind.Consensus && material.height
        .exists(_.toBigNat.toBigInt >= fence.boundary.toBigNat.toBigInt)
    case FenceScope.SourceOrRetiredDomainWritesAndSigning =>
      fence.context.chainId == material.context.chainId

  def signingAllowed(
      state: ControllerHistory,
      material: ControllerSigningMaterial,
  ): Either[V2RuntimeFailure, Unit] = RuntimeCheck.require(
    !state.enforcements.valuesIterator.exists(e =>
      applies(e.promise, material),
    ),
    RuntimeFailureCode.UnsafeBoundary,
    "durable transition fence prohibits this signing action",
  )
  def writingAllowed(
      state: ControllerHistory,
      material: ControllerWriteMaterial,
  ): Either[V2RuntimeFailure, Unit] = RuntimeCheck.require(
    !state.enforcements.valuesIterator.exists(e =>
      e.promise.scope == FenceScope.SourceOrRetiredDomainWritesAndSigning && e.promise.context.chainId == material.context.chainId,
    ) && !state.closures.valuesIterator.exists(_.context == material.context),
    RuntimeFailureCode.UnsafeBoundary,
    "durable transition fence or old-context closure prohibits this canonical write",
  )

  def watermark(
      state: ControllerHistory,
      context: DomainContext,
      scope: FenceScope,
  ): Option[Height] = state.signing.valuesIterator
    .map(_.material)
    .filter { m =>
      scope match
        case FenceScope.ApplicationIssuance =>
          m.context == context && m.kind != ControllerSigningKind.Consensus
        case FenceScope.ConsensusProfileAtOrAbove =>
          m.context == context && m.kind == ControllerSigningKind.Consensus
        case FenceScope.SourceOrRetiredDomainWritesAndSigning =>
          m.context.chainId == context.chainId
    }
    .flatMap(_.height)
    .toVector
    .maxByOption(_.toBigNat.toBigInt)

  def signingHistoryDigest(
      state: ControllerHistory,
      context: DomainContext,
      scope: FenceScope,
  ): Either[V2RuntimeFailure, Hash] = for
    snapshot <- core(ControllerSnapshot.digest(state.snapshot))
    hash     <- core(
      ControllerSigningHistoryBinding.digest(
        ControllerSigningHistoryBinding(1L, snapshot, context, scope),
      ),
    )
  yield hash

  def stopped(state: ControllerHistory): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(
      state.contexts.forall { context =>
        val promises = state.enforcements.valuesIterator.map(_.promise).toVector
        promises.exists(p =>
          p.scope == FenceScope.SourceOrRetiredDomainWritesAndSigning && p.context.chainId == context.chainId,
        ) || (promises.exists(p =>
          p.context == context && p.scope == FenceScope.ApplicationIssuance,
        ) && promises.exists(p =>
          p.context == context && p.scope == FenceScope.ConsensusProfileAtOrAbove,
        ))
      },
      RuntimeFailureCode.UnsafeBoundary,
      "stopped capture requires durable signing fences for every installed or observed context",
    )

  def seed(
      configuration: ControllerConfiguration,
  ): Either[V2RuntimeFailure, ControllerHistory] =
    val initial = configuration.initialHistory
    val empty   = ControllerHistory(
      ControllerSnapshot(1L, configuration, Vector.empty),
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
    )
    for
      _      <- core(ControllerConfiguration.codec.encode(configuration))
      signed <- initial.signatures.foldLeftM(empty) { (state, observed) =>
        for
          digest <- core(ControllerSigningIntent.digest(observed.intent))
          _      <- check(
            !state.signing.contains(digest),
            "duplicate initial signing identity",
          )
          _ <- verifySignature(
            observed.intent.material.canonicalPreimage,
            observed.signature,
            configuration.publicKey,
          )
        yield state.copy(
          signing = state.signing.updated(digest, observed.intent),
          signatures = state.signatures.updated(digest, observed.signature),
        )
      }
      written <- initial.writes.foldLeftM(signed) { (state, observed) =>
        for
          digest <- core(ControllerWriteIntent.digest(observed.intent))
          _      <- check(
            !state.writes.contains(digest),
            "duplicate initial write identity",
          )
        yield state.copy(
          writes = state.writes.updated(digest, observed.intent),
          writeReceipts = state.writeReceipts.updated(digest, observed.effect),
        )
      }
      fenced <- initial.fences.foldLeftM(written) { (state, observed) =>
        for
          _      <- core(SignedFencePromise.codec.encode(observed.promise))
          digest <- core(ControllerEnforcement.digest(observed.enforcement))
          _      <- check(
            !state.enforcements.contains(
              digest,
            ) && observed.promise.record == observed.enforcement.promise && observed.promise.authentication.authorityId == configuration.signerId && observed.promise.authentication.publicKey == configuration.publicKey,
            "initial fence does not match key or original enforcement",
          )
          preimage <- core(
            FencePromise.signingPreimage(observed.promise.record),
          )
          _ <- verifySignature(
            preimage,
            observed.promise.authentication.signature,
            configuration.publicKey,
          )
        yield state.copy(
          enforcements =
            state.enforcements.updated(digest, observed.enforcement),
          fences = state.fences.updated(digest, observed.promise),
        )
      }
      closed <- initial.writeClosures.foldLeftM(fenced) { (state, closure) =>
        for
          digest <- core(ControllerWriteClosure.digest(closure))
          _      <- check(
            !state.closures.contains(digest),
            "duplicate initial canonical-write closure",
          )
        yield state.copy(closures = state.closures.updated(digest, closure))
      }
    yield closed

  def append(
      state: ControllerHistory,
      record: ControllerRecord,
  ): Either[V2RuntimeFailure, ControllerHistory] = for
    _        <- core(ControllerRecord.codec.encode(record))
    previous <- state.snapshot.records.lastOption.traverse(r =>
      core(ControllerRecord.digest(r)),
    )
    _ <- check(
      record.sequence == state.snapshot.records.size.toLong + 1L && record.previousDigest == previous
        .getOrElse(UInt256.unsafeFromBigIntUnsigned(BigInt(0))),
      "controller record sequence or digest chain differs",
    )
    next <- record.kind match
      case ControllerEventKind.SigningIntent =>
        for
          intent <- core(ControllerSigningIntent.codec.decode(record.payload))
          digest <- core(ControllerSigningIntent.digest(intent))
          _      <- check(
            !state.signing.contains(digest),
            "signing intent already exists",
          )
          _ <- signingAllowed(state, intent.material)
        yield state.copy(signing = state.signing.updated(digest, intent))
      case ControllerEventKind.SigningResult =>
        for
          result <- core(ControllerSignature.codec.decode(record.payload))
          intent <- state.signing
            .get(result.intentDigest)
            .toRight(
              V2RuntimeFailure.at(
                RuntimeFailureCode.JournalCorrupt,
                "signature has no original forced intent",
              ),
            )
          _ <- check(
            !state.signatures.contains(result.intentDigest),
            "signature completion already exists",
          )
          _ <- signingAllowed(state, intent.material)
          _ <- verifySignature(
            intent.material.canonicalPreimage,
            result.signature,
            state.snapshot.configuration.publicKey,
          )
        yield state.copy(signatures =
          state.signatures.updated(result.intentDigest, result.signature),
        )
      case ControllerEventKind.WriteIntent =>
        for
          intent <- core(ControllerWriteIntent.codec.decode(record.payload))
          digest <- core(ControllerWriteIntent.digest(intent))
          _      <- check(
            !state.writes.contains(digest),
            "write intent already exists",
          )
          _ <- writingAllowed(state, intent.material)
        yield state.copy(writes = state.writes.updated(digest, intent))
      case ControllerEventKind.WriteResult =>
        for
          result <- core(ControllerWriteReceipt.codec.decode(record.payload))
          _      <- check(
            state.writes.contains(result.intentDigest) && !state.writeReceipts
              .contains(result.intentDigest),
            "write result has no unique prior intent",
          )
        yield state.copy(writeReceipts =
          state.writeReceipts.updated(result.intentDigest, result.effect),
        )
      case ControllerEventKind.Enforcement =>
        for
          enforcement <- core(
            ControllerEnforcement.codec.decode(record.payload),
          )
          digest <- core(ControllerEnforcement.digest(enforcement))
          _      <- check(
            !state.enforcements.contains(
              digest,
            ) && enforcement.promise.signerId == state.snapshot.configuration.signerId,
            "fence identity duplicates or names another signer",
          )
          history <- signingHistoryDigest(
            state,
            enforcement.promise.context,
            enforcement.promise.scope,
          )
          _ <- check(
            enforcement.promise.signingHistoryDigest == history && enforcement.promise.greatestPreviouslySignedHeight == watermark(
              state,
              enforcement.promise.context,
              enforcement.promise.scope,
            ),
            "fence does not bind complete prior signing history and watermark",
          )
          _ <- check(
            state.writes.keySet == state.writeReceipts.keySet,
            "pending canonical write must finish before new fencing",
          )
        yield state.copy(enforcements =
          state.enforcements.updated(digest, enforcement),
        )
      case ControllerEventKind.FenceSignature =>
        for
          result <- core(ControllerFenceSignature.codec.decode(record.payload))
          enforcement <- state.enforcements
            .get(result.enforcementDigest)
            .toRight(
              V2RuntimeFailure.at(
                RuntimeFailureCode.JournalCorrupt,
                "signed fence has no original unsigned enforcement",
              ),
            )
          _ <- check(
            !state.fences.contains(
              result.enforcementDigest,
            ) && result.promise.record == enforcement.promise && result.promise.authentication.authorityId == state.snapshot.configuration.signerId && result.promise.authentication.publicKey == state.snapshot.configuration.publicKey,
            "signed fence differs from forced enforcement",
          )
          preimage <- core(FencePromise.signingPreimage(enforcement.promise))
          _        <- verifySignature(
            preimage,
            result.promise.authentication.signature,
            state.snapshot.configuration.publicKey,
          )
        yield state.copy(fences =
          state.fences.updated(result.enforcementDigest, result.promise),
        )
      case ControllerEventKind.WriteClosure =>
        for
          closure <- core(ControllerWriteClosure.codec.decode(record.payload))
          digest  <- core(ControllerWriteClosure.digest(closure))
          _       <- check(
            !state.closures.contains(digest),
            "canonical-write closure already exists",
          )
          _ <- stopped(state)
          _ <- check(
            state.writes.keySet == state.writeReceipts.keySet,
            "pending writes cannot be abandoned by a closure",
          )
        yield state.copy(closures = state.closures.updated(digest, closure))
  yield next.copy(snapshot =
    state.snapshot.copy(records = state.snapshot.records :+ record),
  )

  // Pure immutable decoding/cryptography only. Physical ownership, observed
  // prefix, original evidence and current authorization are checked by callers
  // on every invocation. Oversize histories are verified normally, not rejected.
  private val recovered = new java.util.concurrent.atomic.AtomicReference[
    Vector[ControllerHistory],
  ](Vector.empty)

  def recover(
      snapshot: ControllerSnapshot,
  ): Either[V2RuntimeFailure, ControllerHistory] =
    recovered.get().find(_.snapshot == snapshot) match
      case Some(value) => Right(value)
      case None        =>
        for
          encoded <- core(ControllerSnapshot.codec.encode(snapshot))
          initial <- seed(snapshot.configuration)
          result  <- snapshot.records.foldLeftM(initial)(append)
        yield
          if encoded.size <= 1048576L && snapshot.records.sizeIs <= 4096 then
            val _ = recovered.updateAndGet(current =>
              (result +: current.filterNot(_.snapshot == snapshot)).take(16),
            )
          result

  def prefix(prefix: ControllerSnapshot, current: ControllerSnapshot): Boolean =
    prefix.configuration == current.configuration && current.records.startsWith(
      prefix.records,
    )
