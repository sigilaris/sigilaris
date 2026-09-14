package org.sigilaris.conformance

import org.sigilaris.node.jvm.runtime.application.v2.*

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import cats.data.EitherT
import cats.effect.{IO, Resource, Ref, Deferred}
import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteEncoder, ByteDecoder}
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  ValidatorId,
  ValidatorMember,
  ValidatorSet,
}
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal

/** Neutral format with independently installed signed genesis/checkpoint and
  * complete namespace/key roster, real one-member fence quorum and actual file
  * controller. This isolates complete-group mechanics; the four-validator F/P
  * conformance fixture supplies the production HotStuff continuation adapter.
  */
object V2ConsistencyGroupConformance:
  def cases: Vector[(String, () => IO[Unit])] = new Scenarios().cases
  def run(): IO[Unit]                         = cases.traverse_ { (name, run) =>
    IO.println("V2ConsistencyGroupConformance: " + name) *> IO.defer(run())
  }
  private object FixtureCheck:
    def require(
        condition: Boolean,
        code: RuntimeFailureCode,
        detail: String,
    ): Either[V2RuntimeFailure, Unit] =
      Either.cond(condition, (), V2RuntimeFailure.at(code, detail))
    def core[A](value: Either[CoreFailure, A]): Either[V2RuntimeFailure, A] =
      value.left.map(V2RuntimeFailure.fromCore)
  private final class Scenarios:
    private def verifyOriginalSignature(
        preimage: Bytes,
        signature: Bytes,
        key: Bytes,
    ): Either[V2RuntimeFailure, Unit] =
      for
        _ <- ValidatorSignature
          .validate(ValidatorSignature(Utf8("fixture-authority"), signature))
          .left
          .map(V2RuntimeFailure.fromCore)
        value = org.sigilaris.core.crypto.Signature(
          BigInt(1, signature.take(8L).toArray).toInt,
          UInt256.unsafeFromBytesBE(signature.slice(8L, 40L)),
          UInt256.unsafeFromBytesBE(signature.drop(40L)),
        )
        recovered <- CryptoOps
          .recover(value, CryptoOps.keccak256(preimage.toArray))
          .left
          .map(e =>
            V2RuntimeFailure.at(RuntimeFailureCode.InvalidSignature, e.msg),
          )
        _ <- Either.cond(
          recovered.toBytes == key,
          (),
          V2RuntimeFailure.at(
            RuntimeFailureCode.InvalidSignature,
            "original fixture authority signature mismatch",
          ),
        )
      yield ()
    private val registered =
      scala.collection.mutable.ArrayBuffer.empty[(String, () => IO[Unit])]
    def cases: Vector[(String, () => IO[Unit])] = registered.toVector
    private def scenario(name: String)(run: => IO[Unit]): Unit =
      registered.addOne(name -> (() => run)): Unit
    private def assertEquals[A, B](actual: A, expected: B): Unit =
      assert(
        actual == expected,
        "expected " + expected.toString + "; actual " + actual.toString,
      )
    private def fail(message: String): Nothing = throw new AssertionError(
      message,
    )
    private def hash(n: Int): Hash = UInt256.unsafeFromBigIntUnsigned(BigInt(n))
    private def height(n: Long): Height = InclusionHeight(
      BigNat.unsafeFromLong(n),
    )
    private def bytes(s: String): Bytes =
      ByteVector.view(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    private def core[A](v: Either[CoreFailure, A]): A =
      v.fold(e => fail(e.message), identity)
    private def accepted[A](v: Result[IO, A]): IO[A] = v.value.flatMap(
      _.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure),
    )
    private def rejected[A](v: Result[IO, A]): IO[Unit] =
      v.value.flatMap(r => IO(assert(r.isLeft)))
    private def result[A](v: Either[V2RuntimeFailure, A]): Result[IO, A] =
      EitherT.fromEither[IO](v)
    private def check(c: Boolean, d: String): Result[IO, Unit] = result(
      FixtureCheck.require(c, RuntimeFailureCode.EvidenceContradictory, d),
    )
    private def unsupported[A]: Result[IO, A] = EitherT.leftT(
      V2RuntimeFailure.at(
        RuntimeFailureCode.ProofUnavailable,
        "neutral fixture has no original proof for this format",
      ),
    )
    private def temporary: Resource[IO, Path] = Resource.make(
      IO.blocking(Files.createTempDirectory("sigilaris-group-").toRealPath()),
    )(p =>
      IO.blocking(
        Using.resource(Files.walk(p))(
          _.iterator().asScala.toVector.reverse.foreach(Files.delete),
        ),
      ),
    )
    private val authority   = CryptoOps.fromPrivate(BigInt(801))
    private val authorityId = Utf8("genesis-authority")
    private val signerId    = Utf8("group-validator")
    private val domain      = Utf8("neutral.consistency.original.v1")

    private final case class Authorized(payload: Bytes, signature: Bytes)
    private object Authorized:
      import V2Codecs.given
      given ByteEncoder[Authorized] = ByteEncoder.derived
      given ByteDecoder[Authorized] = ByteDecoder.derived
      val codec                     =
        CanonicalCodec.derived[Authorized](_ => Right[CoreFailure, Unit](()))
    private final case class Logical(name: Text, rows: Vector[Bytes])
    private object Logical:
      import V2Codecs.given
      given ByteEncoder[Logical] = ByteEncoder.derived
      given ByteDecoder[Logical] = ByteDecoder.derived
      val codec                  = CanonicalCodec.derived[Logical](v =>
        V2Validation.identifier(v.name, "logical.namespace"),
      )
    private final case class Container(schema: Long, logical: Logical)
    private object Container:
      given ByteEncoder[Container] = ByteEncoder.derived
      given ByteDecoder[Container] = ByteDecoder.derived
      val codec                    =
        CanonicalCodec.derived[Container](_ => Right[CoreFailure, Unit](()))
    private final case class Genesis(
        context: DomainContext,
        block: Hash,
        state: Bytes,
        stateRoot: Hash,
        layoutDigest: Hash,
    )
    private object Genesis:
      import V2Codecs.given
      given ByteEncoder[Genesis] = ByteEncoder.derived
      given ByteDecoder[Genesis] = ByteDecoder.derived
      val codec                  =
        CanonicalCodec.derived[Genesis](v => DomainContext.validate(v.context))
    private def rawSignature(key: KeyPair, preimage: Bytes): Bytes =
      val sig =
        CryptoOps.sign(key, CryptoOps.keccak256(preimage.toArray)).toOption.get
      ByteEncoder[Long].encode(sig.v.toLong) ++ sig.r.bytes ++ sig.s.bytes
    private def sign(key: KeyPair, payload: Bytes): Bytes =
      val sig = CryptoOps
        .sign(
          key,
          CryptoOps.keccak256(Commitment.preimage(domain, payload).toArray),
        )
        .toOption
        .get
      ByteEncoder[Long].encode(sig.v.toLong) ++ sig.r.bytes ++ sig.s.bytes
    private def signed(payload: Bytes): Bytes = core(
      Authorized.codec.encode(Authorized(payload, sign(authority, payload))),
    )
    private def original(evidence: Bytes): Either[V2RuntimeFailure, Bytes] = for
      v <- FixtureCheck.core(Authorized.codec.decode(evidence))
      _ <- verifyOriginalSignature(
        Commitment.preimage(domain, v.payload),
        v.signature,
        authority.publicKey.toBytes,
      )
    yield v.payload
    private def durable(path: Path, payload: Bytes): IO[Unit] = IO.blocking {
      Using.resource(
        FileChannel.open(
          path,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE,
        ),
      ) { out =>
        val b = ByteBuffer.wrap(payload.toArray)
        while b.hasRemaining do
          val _ = out.write(b)
        out.force(true)
      }
      Using.resource(FileChannel.open(path.getParent, StandardOpenOption.READ))(
        _.force(true),
      )
    }

    private def retainEvidence(path: Path, payload: Bytes): IO[Unit] =
      IO.blocking(ByteVector.view(Files.readAllBytes(path))).flatMap {
        existing =>
          val decoded = core(Container.codec.decode(existing))
          if decoded.logical.rows.isEmpty then durable(path, payload)
          else IO(assertEquals(existing, payload))
      }
    private final class Material(val root: Path, nonce: Int):
      val key        = CryptoOps.fromPrivate(BigInt(nonce))
      val validators = ValidatorSet(
        Vector(
          ValidatorMember(
            ValidatorId.parse(signerId.asString).toOption.get,
            key.publicKey,
          ),
        ),
      ).toOption.get
      val old = DomainContext(
        1L,
        Utf8("neutral-group-chain"),
        hash(2),
        1L,
        validators.hash.toUInt256,
      )
      val specs = ConsistencyRole.values.toVector
        .filterNot(_ == ConsistencyRole.HistoricalApplicationLedger)
        .map { role =>
          val name = if role == ConsistencyRole.FenceHistory then "controller"
          else "role-" + role.tag.toString
          ConsistencyNamespace(Utf8(name), Utf8(name), 1L, Vector(role))
        }
        .sortBy(n => V2Validation.textKey(n.name))
      val layout = ConsistencyLayout(
        root.resolve("source"),
        ConsistencyLayoutRecord(1L, specs),
      )
      val layoutDigest = core(ConsistencyLayoutRecord.digest(layout.record))
      val app          =
        specs.find(_.roles.contains(ConsistencyRole.ApplicationState)).get.name
      val safety =
        specs.find(_.roles.contains(ConsistencyRole.ApplicationSafety)).get.name
      val evidenceName =
        specs.find(_.roles.contains(ConsistencyRole.FixedEvidence)).get.name
      val blocks = specs.find(_.roles.contains(ConsistencyRole.Blocks)).get.name
      val initialState = bytes("canonical neutral genesis state")
      val stateRoot    =
        Commitment.hash(Utf8("neutral.group.application-root.v1"), initialState)
      val blockId = hash(900)
      val genesis = Genesis(old, blockId, initialState, stateRoot, layoutDigest)
      val genesisProof  = signed(core(Genesis.codec.encode(genesis)))
      val genesisDigest = Commitment.hash(domain, genesisProof)
      val family        = InputManifest(
        2L,
        Utf8("neutral-family"),
        1L,
        Vector(
          FieldManifest(
            Utf8("value"),
            FieldRole.ExactMutate,
            hash(3),
            Some(hash(4)),
          ),
        ),
        hash(5),
        hash(6),
        hash(7),
        Some(hash(8)),
      )
      val manifest = ProtocolManifest(
        2L,
        2L,
        old.chainId,
        2L,
        validators.hash.toUInt256,
        10L,
        SubstrateVersions.V2,
        ProtocolLimits.V2,
        Vector(family),
        Vector.empty,
      )
      val target = core(ProtocolManifest.context(manifest))
      val intent = TransitionIntent(
        1L,
        TransitionKind.Handover,
        old.chainId,
        target.chainId,
        target.configurationDigest,
        target.validatorSetHash,
        Some(height(1L)),
        genesisDigest,
        layoutDigest,
        hash(9),
      )
      val seedHistory = ControllerInitialHistory(
        Vector(old),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
      )
      val seed = signed(
        core(ControllerInitialHistory.codec.encode(seedHistory)),
      )
      val controllerAuth = new ControllerOperationAuthentication:
        def signing(
            request: Bytes,
            id: Text,
            publicKey: Bytes,
        ): Result[IO, ControllerSigningMaterial] = unsupported
        def authorizeSigning(
            request: Bytes,
            id: Text,
            publicKey: Bytes,
        ): Result[IO, Unit] = unsupported
        def canonicalWrite(
            request: Bytes,
        ): Result[IO, ControllerWriteMaterial] =
          unsupported
        def initialHistory(
            proof: Bytes,
            id: Text,
            publicKey: Bytes,
        ): Result[IO, ControllerInitialHistory] = for
          raw     <- result(original(proof))
          decoded <- result(
            FixtureCheck.core(ControllerInitialHistory.codec.decode(raw)),
          )
          _ <- check(
            decoded == seedHistory && id == signerId && publicKey == key.publicKey.toBytes,
            "actual independently signed genesis key inventory differs",
          )
        yield decoded
        def enforce(
            i: TransitionIntent,
            p: FencePromise,
            id: Text,
            publicKey: Bytes,
        ): Result[IO, Unit] = check(
          i == intent && (p.context == old || (p.context == target && p.scope == FenceScope.SourceOrRetiredDomainWritesAndSigning)) && p.boundary == height(
            1L,
          ) && p.signerId == signerId && id == signerId && publicKey == key.publicKey.toBytes,
          "fence differs from installed signed genesis transition/key policy",
        )
        def closeWrites(
            c: ControllerWriteClosure,
            id: Text,
            publicKey: Bytes,
        ): Result[IO, Unit] = check(
          c.transition == intent && c.context == old && id == signerId && publicKey == key.publicKey.toBytes,
          "writer retirement differs from installed old source namespace policy",
        )

      def initialize: IO[Unit] = IO.blocking {
        Files.createDirectory(layout.root)
        specs
          .filterNot(_.roles.contains(ConsistencyRole.FenceHistory))
          .foreach(n =>
            Files.createDirectory(layout.root.resolve(n.relativePath.asString)),
          )
      } *> specs
        .filterNot(_.roles.contains(ConsistencyRole.FenceHistory))
        .traverse_ { n =>
          val rows = if n.name == app then Vector(initialState)
          else if n.name == blocks then Vector(genesisProof)
          else Vector.empty
          durable(
            layout.root.resolve(n.relativePath.asString).resolve("records"),
            core(Container.codec.encode(Container(1L, Logical(n.name, rows)))),
          )
        }
      def codec(name: Text, version: Long): ConsistencyNamespaceCodec =
        new ConsistencyNamespaceCodec:
          val namespace = name
          val schema    = version
          def decode(
              content: ConsistencyNamespaceContent,
          ): Result[IO, ConsistencyNamespaceSemantics] =
            if name == Utf8("controller") then
              for
                ledger <- result(
                  content.files
                    .get(Utf8("LEDGER"))
                    .toRight(
                      V2RuntimeFailure.at(
                        RuntimeFailureCode.EvidenceMissing,
                        "physical controller ledger missing",
                      ),
                    ),
                )
                decoded <- result(
                  FixtureCheck.core(
                    ControllerSnapshot.codec.decode(ledger.drop(41L)),
                  ),
                )
                _      <- result(ControllerSnapshot.validateHistory(decoded))
                digest <- result(
                  FixtureCheck.core(ControllerSnapshot.digest(decoded)),
                )
              yield ConsistencyNamespaceSemantics(digest, Vector.empty)
            else
              for
                raw <- result(
                  content.files
                    .get(Utf8("records"))
                    .toRight(
                      V2RuntimeFailure.at(
                        RuntimeFailureCode.EvidenceMissing,
                        "canonical namespace metadata missing",
                      ),
                    ),
                )
                parsed <- result(FixtureCheck.core(Container.codec.decode(raw)))
                _      <- check(
                  parsed.schema == version && parsed.logical.name == name && content.files.keySet == Set(
                    Utf8("records"),
                  ),
                  "actual namespace schema, name, or full file set differs",
                )
                logical <- result(
                  FixtureCheck.core(Logical.codec.encode(parsed.logical)),
                )
                roots <-
                  if name == app then
                    for _ <- check(
                        parsed.logical.rows.size == 1,
                        "neutral application namespace requires one complete canonical state",
                      )
                    yield parsed.logical.rows.map(
                      Commitment
                        .hash(Utf8("neutral.group.application-root.v1"), _),
                    )
                  else EitherT.pure[IO, V2RuntimeFailure](Vector.empty[Hash])
              yield ConsistencyNamespaceSemantics(
                Commitment.hash(Utf8("neutral.group.semantic.v1"), logical),
                roots,
              )
      val codecs = specs.map(n => codec(n.name, n.schema)) :+ codec(app, 2L)
      def migration(corrupt: Boolean): ConsistencySchemaMigration =
        new ConsistencySchemaMigration:
          val namespace  = app
          val fromSchema = 1L
          val toSchema   = 2L
          def convert(
              content: ConsistencyNamespaceContent,
          ): Result[IO, Map[Text, Bytes]] = for
            before <- result(
              FixtureCheck.core(
                Container.codec.decode(content.files(Utf8("records"))),
              ),
            )
            logical =
              if corrupt then
                before.logical
                  .copy(rows = Vector(bytes("invented root conversion")))
              else before.logical
            after <- result(
              FixtureCheck.core(Container.codec.encode(Container(2L, logical))),
            )
          yield Map(Utf8("records") -> after)

      private def retainedFence(
          controller: FileFenceController,
          scope: FenceScope,
      ): IO[SignedFencePromise] =
        accepted(controller.audit).flatMap { current =>
          current.signedFences.find(p =>
            p.record.context == old && p.record.scope == scope &&
              p.record.transitionBinding == core(
                TransitionIntent.digest(intent),
              ),
          ) match
            case Some(p) => IO.pure(p)
            case None    =>
              accepted(controller.prepareFence(intent, old, scope, height(1L)))
                .flatMap(p => accepted(controller.enforce(intent, p)))
        }
      def transition(
          controller: FileFenceController,
      ): IO[TransitionMaterial] = for
        appFence <- retainedFence(controller, FenceScope.ApplicationIssuance)
        consensusFence <- retainedFence(
          controller,
          FenceScope.ConsensusProfileAtOrAbove,
        )
        snapshot <- accepted(controller.audit)
        issuanceBytes = core(
          Container.codec.encode(Container(1L, Logical(safety, Vector.empty))),
        )
        coverage       = signed(issuanceBytes)
        coverageDigest = Commitment.hash(domain, coverage)
        emptyDigest    = Commitment.hash(domain, issuanceBytes)
        drainRecord    = DrainEvidence(
          1L,
          core(TransitionIntent.digest(intent)),
          old,
          DrainKind.JournalCovered,
          core(SignedFencePromise.digest(appFence)),
          Some(emptyDigest),
          None,
          None,
          10L,
          coverageDigest,
          Some(genesisDigest),
          Some(emptyDigest),
        )
        drainDigest = core(DrainEvidence.digest(drainRecord))
        drainBytes  = core(DrainEvidence.codec.encode(drainRecord))
        fixed       = EvidenceBaseline(
          1L,
          core(TransitionIntent.digest(intent)),
          old.chainId,
          target.chainId,
          blockId,
          stateRoot,
          intent.authorityPolicyDigest,
          Vector(EvidenceRef(EvidenceKind.Drain, drainDigest)),
        )
        artifacts = Map(
          EvidenceRef(EvidenceKind.Drain, drainDigest)    -> drainBytes,
          EvidenceRef(EvidenceKind.Drain, coverageDigest) -> coverage,
        )
        _ <- retainEvidence(
          layout.root.resolve(evidenceName.asString).resolve("records"),
          core(
            Container.codec.encode(
              Container(
                1L,
                Logical(
                  evidenceName,
                  Vector(
                    drainBytes,
                    coverage,
                    genesisProof,
                    core(EvidenceBaseline.codec.encode(fixed)),
                  ),
                ),
              ),
            ),
          ),
        )
        proof = HandoverEvidence(
          1L,
          intent,
          old,
          target,
          blockId,
          height(0L),
          stateRoot,
          drainDigest,
          height(1L),
          blockId,
          stateRoot,
          Vector.empty,
          Vector.empty,
          Vector(consensusFence),
          genesisDigest,
          target.configurationDigest,
          core(EvidenceBaseline.digest(fixed)),
        )
        repository = new TransitionEvidenceRepository[IO]:
          def read(ref: EvidenceRef): Result[IO, Bytes] = result(
            artifacts
              .get(ref)
              .toRight(
                V2RuntimeFailure.at(
                  RuntimeFailureCode.EvidenceMissing,
                  "original artifact missing",
                ),
              ),
          )
          def baseline(digest: Hash): Result[IO, EvidenceBaseline] = check(
            digest == core(EvidenceBaseline.digest(fixed)),
            "fixed baseline id differs",
          ).as(fixed)
          def sourceFence(digest: Hash): Result[IO, SignedFencePromise] =
            unsupported
          def restoreAuthentication(
              record: RestoreEvidence,
          ): Result[IO, SignatureEnvelope] = unsupported
        authentication = new TransitionAuditAuthentication[IO]:
          def artifact(ref: EvidenceRef, b: Bytes): Result[IO, Unit] = check(
            artifacts.get(ref).contains(b),
            "original immutable artifact changed",
          )
          def source(
              binding: SourceBinding,
              repository: TransitionEvidenceRepository[IO],
          ): Result[IO, SourceSnapshotClosure] = unsupported
          def neverEnabled(
              record: NeverEnabled,
              repository: TransitionEvidenceRepository[IO],
          ): Result[IO, NeverEnabledAudit] = unsupported
          def absence(
              record: ArchiveAbsence,
              baseline: EvidenceBaseline,
              repository: TransitionEvidenceRepository[IO],
          ): Result[IO, HistoricalAbsenceAudit] = unsupported
          def presentArchive(
              record: PresentArchive,
              repository: TransitionEvidenceRepository[IO],
          ): Result[IO, PresentArchiveAudit] = unsupported
          def restore(
              record: RestoreEvidence,
              repository: TransitionEvidenceRepository[IO],
          ): Result[IO, RestoreAudit] = unsupported
          def restoreFence(
              promise: SignedFencePromise,
              restore: RestoreEvidence,
              policy: TransitionPolicy,
              repository: TransitionEvidenceRepository[IO],
          ): Result[IO, EnforcedFenceAudit] = unsupported
          def fence(
              promise: SignedFencePromise,
              transition: TransitionIntent,
              repository: TransitionEvidenceRepository[IO],
          ): Result[IO, EnforcedFenceAudit] = for
            current <- controller.audit
            _       <- check(
              transition == intent && current.signedFences.contains(
                promise,
              ) && current.enforcements.exists(
                _.promise == promise.record,
              ) && current.possibleSigningIntents.isEmpty,
              "actual controller did not enforce the supplied promise or omitted historical issuance",
            )
            _ <- check(
              genesisDigest == intent.sourceAuthorityScopeDigest && current.inventory.nonEmpty,
              s"fixture scope original mismatch ${genesisDigest == intent.sourceAuthorityScopeDigest}, inventory ${current.inventory.size}",
            )
          yield EnforcedFenceAudit(
            promise,
            key.publicKey.toBytes,
            genesisDigest,
            Vector.empty,
            current.inventory,
          )
          def drain(
              record: DrainEvidence,
              repository: TransitionEvidenceRepository[IO],
          ): Result[IO, DrainAudit] = for
            b <- repository.read(
              EvidenceRef(EvidenceKind.Drain, record.coverageProofDigest),
            )
            raw     <- result(original(b))
            parsed  <- result(FixtureCheck.core(Container.codec.decode(raw)))
            current <- EitherT.liftF(
              IO.blocking(
                ByteVector.view(
                  Files.readAllBytes(
                    layout.root.resolve(safety.asString).resolve("records"),
                  ),
                ),
              ),
            )
            _ <- check(
              parsed == Container(
                1L,
                Logical(safety, Vector.empty),
              ) && current == raw && record == drainRecord,
              "actual complete original issuance/reconciliation journal is not empty",
            )
            genesisRaw    <- result(original(genesisProof))
            actualGenesis <- result(
              FixtureCheck.core(Genesis.codec.decode(genesisRaw)),
            )
            _ <- check(
              actualGenesis == genesis,
              "independently trusted genesis checkpoint proof changed",
            )
          yield DrainAudit(
            old,
            appFence,
            Vector.empty,
            Vector.empty,
            Some(emptyDigest),
            None,
            None,
            10L,
            Some(
              OriginalDomainFinality(
                old,
                blockId,
                height(0L),
                stateRoot,
                genesisDigest,
                genesisProof,
              ),
            ),
            Some(emptyDigest),
            Vector(
              SurvivingApplicationInventory(
                old,
                emptyDigest,
                Vector.empty,
                Vector.empty,
                Vector.empty,
                Vector.empty,
              ),
            ),
          )
          def continuation(
              record: HandoverEvidence,
              repository: TransitionEvidenceRepository[IO],
          ): Result[IO, ContinuationAudit] = for
            raw    <- result(original(genesisProof))
            actual <- result(FixtureCheck.core(Genesis.codec.decode(raw)))
            _      <- check(
              actual == genesis && record == proof && record.retainedSuffix.isEmpty && record.safety.isEmpty,
              "genesis continuation differs from independently installed original checkpoint; no later suffix is supported",
            )
            current <- controller.audit
            _       <- check(
              current.possibleSigningIntents.isEmpty,
              "old genesis validator has observed a later signing subject",
            )
          yield ContinuationAudit(
            old,
            target,
            blockId,
            height(0L),
            stateRoot,
            blockId,
            height(0L),
            stateRoot,
            Vector.empty,
            Vector.empty,
            Vector(
              HistoricalFenceSet(
                old,
                Vector(InitialValidator(signerId, key.publicKey.toBytes)),
              ),
            ),
            Vector.empty,
          )
        policy = TransitionPolicy(
          intent,
          manifest,
          fixed,
          Vector(
            TrustedTransitionAuthority(
              authorityId,
              authority.publicKey.toBytes,
              Set(TransitionAuthorityRole.Restore),
            ),
          ),
          Vector(old.chainId),
          Vector(old),
        )
        verifier = TransitionEvidenceVerifier
          .authenticated(policy, repository, authentication)
          .fold(e => fail(e.message), identity)
        verified <- accepted(verifier.verifyHandover(proof))
        _        <- IO(assert(snapshot.possibleSigningIntents.isEmpty))
      yield TransitionMaterial(
        verified,
        fixed,
        artifacts,
        verifier,
        policy,
        repository,
        authentication,
      )

      def groupAuthentication(
          fixed: EvidenceBaseline,
      ): ConsistencyGroupAuthentication = new ConsistencyGroupAuthentication:
        def verifyFixedLayout(
            selected: ConsistencyLayoutRecord,
        ): Result[IO, Unit] = for
          raw        <- result(original(genesisProof))
          checkpoint <- result(FixtureCheck.core(Genesis.codec.decode(raw)))
          digest     <- result(
            FixtureCheck.core(ConsistencyLayoutRecord.digest(selected)),
          )
          _ <- check(
            selected == layout.record && checkpoint.layoutDigest == digest && checkpoint == genesis,
            "caller omitted or changed an independently installed namespace",
          )
        yield ()
        def verifyOriginal(
            transition: VerifiedHandover,
            group: ConsistencyGroupRecord,
            contents: Vector[ConsistencyNamespaceContent],
        ): Result[IO, ConsistencyOriginalState] = for
          _              <- verifyFixedLayout(group.layout)
          stateContainer <- result(
            FixtureCheck.core(
              Container.codec.decode(
                contents
                  .find(_.image.namespace.name == app)
                  .get
                  .files(Utf8("records")),
              ),
            ),
          )
          genesisContainer <- result(
            FixtureCheck.core(
              Container.codec.decode(
                contents
                  .find(_.image.namespace.name == blocks)
                  .get
                  .files(Utf8("records")),
              ),
            ),
          )
          _ <- check(
            genesisContainer.logical.rows == Vector(
              genesisProof,
            ) && stateContainer.logical.rows == Vector(initialState),
            "actual original block checkpoint or canonical application payload changed",
          )
          raw        <- result(original(genesisContainer.logical.rows.head))
          checkpoint <- result(FixtureCheck.core(Genesis.codec.decode(raw)))
          actualRoot = Commitment.hash(
            Utf8("neutral.group.application-root.v1"),
            stateContainer.logical.rows.head,
          )
          _ <- check(
            actualRoot == checkpoint.stateRoot && transition.evidence.source == old && transition.evidence.target == target,
            "decoded original application root differs from verified handover",
          )
          archived <- result(
            FixtureCheck.core(
              Container.codec.decode(
                contents
                  .find(_.image.namespace.name == evidenceName)
                  .get
                  .files(Utf8("records")),
              ),
            ),
          )
          _ <- check(
            archived.logical.rows.contains(
              core(EvidenceBaseline.codec.encode(fixed)),
            ) && fixed.entries.forall(ref =>
              archived.logical.rows.exists(raw =>
                DrainEvidence.codec
                  .decode(raw)
                  .toOption
                  .exists(d => core(DrainEvidence.digest(d)) == ref.digest),
              ),
            ),
            "fixed baseline or a present original artifact was lost",
          )
          semantics <- contents.traverse(c =>
            codec(c.image.namespace.name, c.image.namespace.schema)
              .decode(c)
              .map(s =>
                InventoryEntry(
                  c.image.namespace.name,
                  Utf8("semantic"),
                  s.semanticDigest,
                ),
              ),
          )
          digest <- result(
            FixtureCheck.core(TransitionInventory.digest(semantics)),
          )
          frontier = ConsistencyFrontier(
            old,
            checkpoint.block,
            height(0L),
            actualRoot,
          )
        yield ConsistencyOriginalState(
          frontier,
          frontier,
          fixed.entries,
          digest,
          stateContainer.logical.rows.head,
        )
      def store(
          controller: FenceController,
          journal: DurableJournal[IO],
          lifecycle: ConsistencyGroupLifecycle,
          fixed: EvidenceBaseline,
          corrupt: Boolean = false,
      ): ConsistencyGroupStore = ConsistencyGroupStore
        .configured(
          layout,
          fixed,
          groupAuthentication(fixed),
          lifecycle,
          codecs,
          Vector(migration(corrupt)),
          controller,
          journal,
          ConsistencyGroupCapacity(1048576L, 8388608L, 1000),
        )
        .fold(e => fail(e.message), identity)

    private final case class TransitionMaterial(
        verified: VerifiedHandover,
        fixed: EvidenceBaseline,
        artifacts: Map[EvidenceRef, Bytes],
        verifier: TransitionEvidenceVerifier[IO],
        policy: TransitionPolicy,
        repository: TransitionEvidenceRepository[IO],
        authentication: TransitionAuditAuthentication[IO],
    )

    private class AuditDelegate(delegate: TransitionAuditAuthentication[IO])
        extends TransitionAuditAuthentication[IO]:
      def artifact(ref: EvidenceRef, bytes: Bytes) =
        delegate.artifact(ref, bytes)
      def source(
          binding: SourceBinding,
          evidence: TransitionEvidenceRepository[IO],
      ) = delegate.source(binding, evidence)
      def neverEnabled(
          record: NeverEnabled,
          evidence: TransitionEvidenceRepository[IO],
      ) = delegate.neverEnabled(record, evidence)
      def absence(
          record: ArchiveAbsence,
          baseline: EvidenceBaseline,
          evidence: TransitionEvidenceRepository[IO],
      ) = delegate.absence(record, baseline, evidence)
      def presentArchive(
          record: PresentArchive,
          evidence: TransitionEvidenceRepository[IO],
      ) = delegate.presentArchive(record, evidence)
      def fence(
          promise: SignedFencePromise,
          intent: TransitionIntent,
          evidence: TransitionEvidenceRepository[IO],
      ) = delegate.fence(promise, intent, evidence)
      def drain(
          record: DrainEvidence,
          evidence: TransitionEvidenceRepository[IO],
      ) = delegate.drain(record, evidence)
      def continuation(
          record: HandoverEvidence,
          evidence: TransitionEvidenceRepository[IO],
      ) = delegate.continuation(record, evidence)
      def restore(
          record: RestoreEvidence,
          evidence: TransitionEvidenceRepository[IO],
      ) = delegate.restore(record, evidence)
      def restoreFence(
          promise: SignedFencePromise,
          restore: RestoreEvidence,
          policy: TransitionPolicy,
          evidence: TransitionEvidenceRepository[IO],
      ) = delegate.restoreFence(promise, restore, policy, evidence)

    private final case class Environment(
        material: Material,
        controller: FileFenceController,
        journal: FileApplicationJournal,
        lifecycle: ConsistencyMutationGate,
        transition: VerifiedHandover,
        baseline: EvidenceBaseline,
        groups: ConsistencyGroupStore,
        transitions: TransitionEvidenceVerifier[IO],
        originalTransition: TransitionMaterial,
    ):
      def activation: IO[ActivationStore[IO]] = ActivationStore.authenticated(
        ApplicationAnchor(
          material.target,
          material.blockId,
          height(0L),
          material.stateRoot,
        ),
        transitions,
        groups,
        journal,
      )

    private def environment(
        root: Path,
        nonce: Int,
        corrupt: Boolean = false,
        faults: JournalFaultInjector[IO] = JournalFaultInjector.none[IO],
        existing: Boolean = false,
    ): Resource[IO, Environment] = for
      material <- Resource.eval(IO.pure(new Material(root, nonce)))
      _ <- Resource.eval(if existing then IO.unit else material.initialize)
      controller <- FileFenceController.resource(
        material.layout.root.resolve("controller"),
        material.key,
        signerId,
        material.seed,
        material.controllerAuth,
        ControllerCanonicalWriter.unavailable,
      )
      transition <- Resource.eval(material.transition(controller))
      lifecycle  <- Resource.eval(
        accepted(ConsistencyMutationGate.create(controller, material.old)),
      )
      journal <- FileApplicationJournal.resourceWithFaults(
        root.resolve("target"),
        faults,
      )
      _ <- Resource.eval(accepted(journal.recover))
    yield Environment(
      material,
      controller,
      journal,
      lifecycle,
      transition.verified,
      transition.fixed,
      material.store(controller, journal, lifecycle, transition.fixed, corrupt),
      transition.verifier,
      transition,
    )

    scenario(
      "actual stopped group preserves every original namespace, migrates inactive schema, and retains F/P payload",
    ) {
      temporary.use(root =>
        environment(root, 811).use { e =>
          for
            before <- IO.blocking(
              ByteVector.view(
                Files.readAllBytes(
                  e.material.layout.root
                    .resolve(e.material.app.asString)
                    .resolve("records"),
                ),
              ),
            )
            group    <- accepted(e.groups.capture(e.transition))
            migrated <- accepted(
              e.groups.readPrepared(
                group.prepared.find(_.name == e.material.app).get.contentDigest,
              ),
            )
            actual <- IO.blocking(
              ByteVector.view(
                Files.readAllBytes(
                  e.material.layout.root
                    .resolve(e.material.app.asString)
                    .resolve("records"),
                ),
              ),
            )
            _ <- IO(assertEquals(actual, before))
            _ <- IO(
              assertEquals(
                core(
                  Container.codec.decode(migrated.files(Utf8("records"))),
                ).schema,
                2L,
              ),
            )
            _ <- IO(
              assertEquals(group.workingStatePayload, e.material.initialState),
            )
            _ <- IO(
              assertEquals(
                group.original.namespaces.map(_.namespace),
                e.material.layout.record.namespaces,
              ),
            )
            recovered <- accepted(
              e.groups.recover(
                group.completeOldGroupDigest,
                group.preparedRecordDigest,
                e.transition,
              ),
            )
            _ <- IO(assertEquals(recovered.prepared, group.prepared))
            _ <- rejected(
              e.lifecycle.mutate(
                EitherT.liftF(
                  IO.raiseError[Unit](
                    new AssertionError("retired old mutation executed"),
                  ),
                ),
              ),
            )
          yield ()
        },
      )
    }

    scenario(
      "root-changing migration is rejected with old writes still permanently retired",
    ) {
      temporary.use(root =>
        environment(root, 812, corrupt = true).use(e =>
          rejected(e.groups.capture(e.transition)) *> rejected(
            e.lifecycle.mutate(EitherT.pure[IO, V2RuntimeFailure](())),
          ),
        ),
      )
    }

    scenario(
      "caller subset, unlisted namespace, symbolic alias, and hard-link alias cannot prove complete closure",
    ) {
      temporary.use(root =>
        environment(root, 813).use { e =>
          for
            _ <- IO.unit
            subset = e.material.layout.copy(record =
              e.material.layout.record
                .copy(namespaces = e.material.specs.drop(1)),
            )
            _ <- IO(
              assert(
                ConsistencyGroupStore
                  .configured(
                    subset,
                    e.baseline,
                    e.material.groupAuthentication(e.baseline),
                    e.lifecycle,
                    e.material.codecs,
                    Vector.empty,
                    e.controller,
                    e.journal,
                    ConsistencyGroupCapacity(1024L, 8192L, 100),
                  )
                  .isLeft,
              ),
            )
            extra = e.material.layout.root.resolve("unlisted")
            _ <- IO.blocking(Files.createDirectory(extra))
            _ <- rejected(e.groups.capture(e.transition))
            _ <- IO.blocking(Files.delete(extra))
            alias = e.material.layout.root
              .resolve(e.material.app.asString)
              .resolve("alias")
            original = e.material.layout.root
              .resolve(e.material.app.asString)
              .resolve("records")
            _ <- IO.blocking(Files.createSymbolicLink(alias, original))
            _ <- rejected(e.groups.capture(e.transition))
            _ <- IO.blocking(Files.delete(alias))
            _ <- IO.blocking(Files.createLink(alias, original))
            _ <- rejected(e.groups.capture(e.transition))
          yield ()
        },
      )
    }

    scenario(
      "missing canonical metadata and later baseline artifact loss are never manufactured as empty or absence",
    ) {
      temporary.use(root =>
        environment(root, 814).use { e =>
          for
            _ <- IO.unit
            path = e.material.layout.root
              .resolve(e.material.evidenceName.asString)
              .resolve("records")
            original <- IO.blocking(ByteVector.view(Files.readAllBytes(path)))
            _        <- IO.blocking(Files.delete(path))
            _        <- rejected(e.groups.capture(e.transition))
            _        <- durable(
              path,
              core(
                Container.codec.encode(
                  Container(1L, Logical(e.material.evidenceName, Vector.empty)),
                ),
              ),
            )
            _ <- rejected(e.groups.capture(e.transition))
            _ <- durable(path, original)
            _ <- accepted(e.groups.capture(e.transition))
          yield ()
        },
      )
    }

    scenario(
      "withRevalidated holds actual mutation and controller leases through the complete decision callback",
    ) {
      temporary.use(root =>
        environment(root, 815).use { e =>
          for
            group      <- accepted(e.groups.capture(e.transition))
            entered    <- Deferred[IO, Unit]
            release    <- Deferred[IO, Unit]
            counter    <- Ref.of[IO, Int](0)
            committing <- accepted(
              e.groups.withRevalidated(group, e.transition)(_ =>
                EitherT.liftF(entered.complete(()).void *> release.get),
              ),
            ).start
            _        <- entered.get
            mutation <- e.lifecycle
              .mutate(EitherT.liftF(counter.update(_ + 1)))
              .value
              .start
            _      <- release.complete(())
            _      <- committing.joinWithNever
            result <- mutation.joinWithNever
            value  <- counter.get
            _      <- IO(assert(result.isLeft && value == 0))
          yield ()
        },
      )
    }

    scenario(
      "source advancement after complete capture rejects prepare without selecting any inactive namespace",
    ) {
      temporary.use(root =>
        environment(root, 816).use { e =>
          for
            group <- accepted(e.groups.capture(e.transition))
            path = e.material.layout.root
              .resolve(e.material.app.asString)
              .resolve("records")
            _ <- durable(
              path,
              core(
                Container.codec.encode(
                  Container(
                    1L,
                    Logical(
                      e.material.app,
                      Vector(bytes("advanced application root")),
                    ),
                  ),
                ),
              ),
            )
            selected <- Ref.of[IO, Boolean](false)
            _        <- rejected(
              e.groups.withRevalidated(group, e.transition)(_ =>
                EitherT.liftF(selected.set(true)),
              ),
            )
            result <- selected.get
            _      <- IO(assert(!result))
          yield ()
        },
      )
    }

    scenario(
      "complete actual activation selects every inactive namespace atomically and reopens the original group",
    ) {
      temporary.use { root =>
        for
          expected <- environment(root, 821).use { e =>
            for
              group     <- accepted(e.groups.capture(e.transition))
              store     <- e.activation
              prepared  <- accepted(store.prepare(e.transition, group))
              before    <- accepted(store.recover)
              _         <- IO(assertEquals(before.decision, None))
              decision  <- accepted(store.commit(prepared))
              recovered <- accepted(store.recover)
              _         <- IO(assertEquals(recovered.decision, Some(decision)))
              _         <- IO(
                assertEquals(
                  recovered.activeGroup.get.group.prepared,
                  group.prepared,
                ),
              )
              _ <- rejected(
                e.lifecycle.mutate(EitherT.pure[IO, V2RuntimeFailure](())),
              )
            yield (decision, group.completeOldGroupDigest)
          }
          _ <- environment(root, 821, existing = true).use { e =>
            for
              store     <- e.activation
              recovered <- accepted(store.recover)
              _ <- IO(assertEquals(recovered.decision, Some(expected._1)))
              _ <- IO(
                assertEquals(
                  recovered.activeGroup.get.group.completeOldGroupDigest,
                  expected._2,
                ),
              )
              _ <- rejected(
                e.lifecycle.mutate(EitherT.pure[IO, V2RuntimeFailure](())),
              )
            yield ()
          }
        yield ()
      }
    }

    scenario(
      "each physical activation prepare and decision barrier preserves the complete original group on file reopen",
    ) {
      val points = Vector(
        JournalFaultPoint.AfterFrameWrite,
        JournalFaultPoint.AfterFrameForce,
        JournalFaultPoint.AfterHeadWrite,
        JournalFaultPoint.AfterHeadMove,
        JournalFaultPoint.AfterHeadForce,
      )
      (for stage <- Vector(false, true); point <- points
      yield (stage, point)).zipWithIndex.traverse_ {
        case ((commit, point), index) =>
          temporary.use { root =>
            for
              armed <- Ref.of[IO, Boolean](false)
              injector = new JournalFaultInjector[IO]:
                def after(actual: JournalFaultPoint, sequence: Long): IO[Unit] =
                  if actual == point then
                    armed
                      .getAndSet(false)
                      .flatMap(a =>
                        if a then
                          IO.raiseError(
                            new java.io.IOException(
                              "injected actual activation barrier",
                            ),
                          )
                        else IO.unit,
                      )
                  else IO.unit
              groupDigest <- environment(root, 830 + index, faults = injector)
                .use { e =>
                  for
                    group       <- accepted(e.groups.capture(e.transition))
                    store       <- e.activation
                    preparation <-
                      if commit then
                        accepted(store.prepare(e.transition, group))
                          .map(Some(_))
                      else IO.pure(None)
                    _ <- armed.set(true)
                    _ <-
                      if commit then rejected(store.commit(preparation.get))
                      else rejected(store.prepare(e.transition, group))
                    _ <- rejected(
                      e.lifecycle.mutate(EitherT.pure[IO, V2RuntimeFailure](())),
                    )
                  yield group.completeOldGroupDigest
                }
              _ <- environment(root, 830 + index, existing = true).use { e =>
                for
                  store     <- e.activation
                  recovered <- accepted(store.recover)
                  original  <- accepted(e.groups.readOriginal(groupDigest))
                  _         <- IO(
                    assertEquals(original.layout, e.material.layout.record),
                  )
                  _ <- IO(assertEquals(recovered.decision.nonEmpty, commit))
                  _ <-
                    if commit then
                      IO(
                        assertEquals(
                          recovered.activeGroup.get.group.completeOldGroupDigest,
                          groupDigest,
                        ),
                      )
                    else IO.unit
                  _ <- rejected(
                    e.lifecycle.mutate(EitherT.pure[IO, V2RuntimeFailure](())),
                  )
                yield ()
              }
            yield ()
          }
      }
    }

    scenario(
      "lifecycle affinity, invalid image parents, and missing original backup bytes cannot grant activation",
    ) {
      temporary.use(root =>
        environment(root, 841).use { e =>
          for
            wrong <- accepted(
              ConsistencyMutationGate.create(e.controller, e.material.target),
            )
            invalid = e.material
              .store(e.controller, e.journal, wrong, e.baseline)
            _     <- rejected(invalid.capture(e.transition))
            group <- accepted(e.groups.capture(e.transition))
            image = group.original.namespaces.head
            bad   = image.copy(
              files =
                image.files.map(f => f.copy(path = Utf8("missing/records"))),
              directories = Vector.empty,
            )
            _ <- IO(assert(ConsistencyNamespaceImage.codec.encode(bad).isLeft))
            store    <- e.activation
            prepared <- accepted(store.prepare(e.transition, group))
            _        <- accepted(store.commit(prepared))
            original = group.original.namespaces.flatMap(_.files).head
            raw <- accepted(
              e.journal.readBlob(
                ConsistencyGroupStore.ContentNamespace,
                original.contentDigest,
              ),
            )
            _ <- IO(assert(raw.nonEmpty))
            broken = new DurableJournal[IO]:
              def recover                       = e.journal.recover
              def append(record: JournalRecord) = e.journal.append(record)
              def putBlob(namespace: Text, digest: Hash, bytes: Bytes) =
                e.journal.putBlob(namespace, digest, bytes)
              def readBlob(namespace: Text, digest: Hash) =
                if namespace == ConsistencyGroupStore.ContentNamespace && digest == original.contentDigest
                then
                  EitherT.leftT[IO, Bytes](
                    V2RuntimeFailure.at(
                      RuntimeFailureCode.EvidenceMissing,
                      "actual original backup unavailable",
                    ),
                  )
                else e.journal.readBlob(namespace, digest)
            groups = e.material
              .store(e.controller, broken, e.lifecycle, e.baseline)
            unavailable <- ActivationStore.authenticated(
              ApplicationAnchor(
                e.material.target,
                e.material.blockId,
                height(0L),
                e.material.stateRoot,
              ),
              e.transitions,
              groups,
              broken,
            )
            _ <- rejected(unavailable.recover)
          yield ()
        },
      )
    }

    scenario(
      "restore recomputes the complete original group domain and retains all post-backup fences and activation writes",
    ) {
      temporary.use(root =>
        environment(root, 842).use { e =>
          def inventory: Result[IO, Vector[InventoryEntry]] = for
            controller <- e.controller.audit
            journal    <- e.journal.recover
            rows       <- journal.traverse(r =>
              result(FixtureCheck.core(JournalRecord.digest(r))).map(d =>
                InventoryEntry(
                  Utf8("target-journal"),
                  Utf8(f"${r.sequence}%020d-${r.status.toString}"),
                  d,
                ),
              ),
            )
          yield (controller.inventory ++ rows).sortBy(i =>
            (V2Validation.textKey(i.namespace), V2Validation.textKey(i.key)),
          )
          for
            group         <- accepted(e.groups.capture(e.transition))
            activation    <- e.activation
            prepared      <- accepted(activation.prepare(e.transition, group))
            decision      <- accepted(activation.commit(prepared))
            restoreFences <- Vector(e.material.old, e.material.target)
              .traverse { context =>
                accepted(
                  e.controller.prepareFence(
                    e.material.intent,
                    context,
                    FenceScope.SourceOrRetiredDomainWritesAndSigning,
                    height(1L),
                  ),
                )
                  .flatMap(p =>
                    accepted(e.controller.enforce(e.material.intent, p)),
                  )
              }
            fences = restoreFences.sortBy(p =>
              (
                core(DomainContext.codec.encode(p.record.context)).toHex,
                V2Validation.textKey(p.record.signerId),
                p.record.scope.tag.toInt,
              ),
            )
            fenceDigest = core(TransitionFenceInventory.digest(fences))
            fenceBytes  = core(TransitionFenceInventory.encode(fences))
            current <- accepted(inventory)
            currentDigest = core(TransitionInventory.digest(current))
            record        = RestoreEvidence(
              1L,
              group.completeOldGroupDigest,
              currentDigest,
              fenceDigest,
              currentDigest,
              currentDigest,
              authorityId,
            )
            preimage  = core(RestoreEvidence.signingPreimage(record))
            signature = SignatureEnvelope(
              authorityId,
              1.toByte,
              authority.publicKey.toBytes,
              rawSignature(authority, preimage),
            )
            sourceBytes = core(
              ConsistencyGroupRecord.codec.encode(group.original),
            )
            sourceInventory = core(
              ConsistencyGroupStore.originalInventory(group.original),
            )
            repository = new TransitionEvidenceRepository[IO]:
              def read(ref: EvidenceRef) =
                if ref == EvidenceRef(EvidenceKind.Fence, fenceDigest) then
                  EitherT.pure[IO, V2RuntimeFailure](fenceBytes)
                else e.originalTransition.repository.read(ref)
              def baseline(digest: Hash) =
                e.originalTransition.repository.baseline(digest)
              def sourceFence(digest: Hash) =
                e.originalTransition.repository.sourceFence(digest)
              def restoreAuthentication(request: RestoreEvidence) =
                check(request == record, "restore signed subject changed").as(
                  signature,
                )
            auth = new AuditDelegate(e.originalTransition.authentication):
              override def restore(
                  request: RestoreEvidence,
                  repository: TransitionEvidenceRepository[IO],
              ): Result[IO, RestoreAudit] = for
                actual <- e.groups.recover(
                  group.completeOldGroupDigest,
                  group.preparedRecordDigest,
                  e.transition,
                )
                now <- inventory
                _   <- check(
                  request == record && actual.original == group.original && now == current,
                  "actual complete original group or current forced safety/journal inventory changed",
                )
                original <- e.groups.readOriginal(request.sourceGroupDigest)
                _        <- check(
                  core(
                    ConsistencyGroupRecord.codec.encode(original),
                  ) == sourceBytes,
                  "original complete source bytes differ",
                )
              yield RestoreAudit(
                request.sourceGroupDigest,
                sourceBytes,
                core(TransitionInventory.digest(now)),
                core(ConsistencyGroupStore.originalInventory(original)),
                now,
                Vector(e.material.old, e.material.target),
                now,
                now,
                now,
                now,
                fences,
              )
              override def restoreFence(
                  promise: SignedFencePromise,
                  request: RestoreEvidence,
                  policy: TransitionPolicy,
                  repository: TransitionEvidenceRepository[IO],
              ): Result[IO, EnforcedFenceAudit] = for
                actual <- e.controller.audit
                now    <- inventory
                _      <- check(
                  request == record && policy.intent == e.material.intent && actual.signedFences
                    .contains(promise) &&
                    actual.enforcements.exists(
                      _.promise == promise.record,
                    ) && actual.possibleSigningIntents.isEmpty && now == current,
                  "restore key fence is not enforced by the same actual complete controller",
                )
              yield EnforcedFenceAudit(
                promise,
                e.material.key.publicKey.toBytes,
                core(TransitionInventory.digest(now)),
                Vector.empty,
                actual.inventory,
              )
            verifier = TransitionEvidenceVerifier
              .authenticated(e.originalTransition.policy, repository, auth)
              .fold(e => fail(e.message), identity)
            verified <- accepted(verifier.verifyRestore(record))
            store    <- ActivationStore.authenticated(
              ApplicationAnchor(
                e.material.target,
                e.material.blockId,
                height(0L),
                e.material.stateRoot,
              ),
              verifier,
              e.groups,
              e.journal,
            )
            eligible <- accepted(store.verifyRestore(verified))
            _        <- IO(
              assertEquals(
                eligible.completeGroupDigest,
                group.completeOldGroupDigest,
              ),
            )
            _ <- IO(
              assert(
                core(
                  TransitionInventory.digest(sourceInventory),
                ) != group.completeOldGroupDigest,
              ),
            )
            preserved <- accepted(
              e.controller.preserveAfterRestore(
                group.original.controllerSnapshot,
              ),
            )
            _ <- IO(
              assert(
                preserved.records.sizeCompare(
                  group.original.controllerSnapshot.records.size,
                ) > 0,
              ),
            )
            restored <- accepted(activation.recover)
            _        <- IO(assertEquals(restored.decision, Some(decision)))
            _        <- rejected(
              e.lifecycle.mutate(EitherT.pure[IO, V2RuntimeFailure](())),
            )
            badAuth = new AuditDelegate(auth):
              override def restore(
                  request: RestoreEvidence,
                  repository: TransitionEvidenceRepository[IO],
              ) =
                auth
                  .restore(request, repository)
                  .map(
                    _.copy(sourceGroupRecord =
                      sourceBytes ++ ByteVector(0.toByte),
                    ),
                  )
            badVerifier = TransitionEvidenceVerifier
              .authenticated(e.originalTransition.policy, repository, badAuth)
              .fold(e => fail(e.message), identity)
            _ <- rejected(badVerifier.verifyRestore(record))
          yield ()
        },
      )
    }
