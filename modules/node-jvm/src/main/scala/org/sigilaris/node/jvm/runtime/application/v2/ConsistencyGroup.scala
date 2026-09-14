package org.sigilaris.node.jvm.runtime.application.v2

import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scodec.bits.ByteVector
import cats.data.EitherT
import cats.effect.IO
import cats.effect.std.Semaphore
import cats.syntax.all.*
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import TransitionEvidenceVerifier.VerifiedHandover
import V2Codecs.given

/** Every role must be covered by the independently installed complete layout.
  * One actual journal namespace may implement several roles; duplicate physical
  * paths cannot masquerade as independent namespaces.
  */
enum ConsistencyRole(val tag: Byte):
  case ApplicationState            extends ConsistencyRole(1.toByte)
  case ApplicationResults          extends ConsistencyRole(2.toByte)
  case ApplicationReplay           extends ConsistencyRole(3.toByte)
  case Blocks                      extends ConsistencyRole(4.toByte)
  case ConsensusSafety             extends ConsistencyRole(5.toByte)
  case ApplicationSafety           extends ConsistencyRole(6.toByte)
  case ExactAdmission              extends ConsistencyRole(7.toByte)
  case ExactIdempotency            extends ConsistencyRole(8.toByte)
  case GenericPipelines            extends ConsistencyRole(9.toByte)
  case Configuration               extends ConsistencyRole(10.toByte)
  case FenceHistory                extends ConsistencyRole(11.toByte)
  case FixedEvidence               extends ConsistencyRole(12.toByte)
  case HistoricalApplicationLedger extends ConsistencyRole(13.toByte)
object ConsistencyRole:
  val mandatory: Set[ConsistencyRole] =
    values.toSet - HistoricalApplicationLedger
  given ByteEncoder[ConsistencyRole] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[ConsistencyRole] = V2Codecs.enumDecoder(
    "consistency role",
    values.toVector.map(v => v.tag -> v),
  )

final case class ConsistencyNamespace(
    name: Text,
    relativePath: Text,
    schema: Long,
    roles: Vector[ConsistencyRole],
)
object ConsistencyNamespace:
  given ByteEncoder[ConsistencyNamespace] = ByteEncoder.derived
  given ByteDecoder[ConsistencyNamespace] = ByteDecoder.derived
  val codec                               =
    CanonicalCodec.derived[ConsistencyNamespace](ConsistencyShape.namespace)
final case class ConsistencyLayoutRecord(
    format: Long,
    namespaces: Vector[ConsistencyNamespace],
)
object ConsistencyLayoutRecord:
  given ByteEncoder[ConsistencyLayoutRecord] = ByteEncoder.derived
  given ByteDecoder[ConsistencyLayoutRecord] = ByteDecoder.derived
  val codec                                  =
    CanonicalCodec.derived[ConsistencyLayoutRecord](ConsistencyShape.layout)
  def digest(v: ConsistencyLayoutRecord): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(
      Commitment.hash(Utf8("sigilaris.application.consistency.layout.v1"), _),
    )

/** The root is an independently selected physical path, not a caller-selected
  * namespace list at capture time. The exact record is bound into every backup.
  */
final case class ConsistencyLayout(root: Path, record: ConsistencyLayoutRecord)
final case class ConsistencyFile(path: Text, contentDigest: Hash, size: Long)
object ConsistencyFile:
  given ByteEncoder[ConsistencyFile] = ByteEncoder.derived
  given ByteDecoder[ConsistencyFile] = ByteDecoder.derived
final case class ConsistencyNamespaceImage(
    format: Long,
    namespace: ConsistencyNamespace,
    directories: Vector[Text],
    files: Vector[ConsistencyFile],
)
object ConsistencyNamespaceImage:
  given ByteEncoder[ConsistencyNamespaceImage] = ByteEncoder.derived
  given ByteDecoder[ConsistencyNamespaceImage] = ByteDecoder.derived
  val codec                                    =
    CanonicalCodec.derived[ConsistencyNamespaceImage](ConsistencyShape.image)
  def digest(v: ConsistencyNamespaceImage): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(
      Commitment.hash(Utf8("sigilaris.application.consistency.namespace.v1"), _),
    )
final case class ConsistencyFrontier(
    context: DomainContext,
    blockId: Hash,
    height: Height,
    stateRoot: Hash,
)
object ConsistencyFrontier:
  given ByteEncoder[ConsistencyFrontier] = ByteEncoder.derived
  given ByteDecoder[ConsistencyFrontier] = ByteDecoder.derived
final case class ConsistencyGroupRecord(
    format: Long,
    layout: ConsistencyLayoutRecord,
    transitionDigest: Hash,
    baseline: EvidenceBaseline,
    canonical: ConsistencyFrontier,
    working: ConsistencyFrontier,
    namespaces: Vector[ConsistencyNamespaceImage],
    controllerSnapshot: ControllerSnapshot,
    writeClosureDigest: Hash,
)
object ConsistencyGroupRecord:
  given ByteEncoder[ConsistencyGroupRecord] = ByteEncoder.derived
  given ByteDecoder[ConsistencyGroupRecord] = ByteDecoder.derived
  val codec                                 =
    CanonicalCodec.derived[ConsistencyGroupRecord](ConsistencyShape.group)
  def digest(v: ConsistencyGroupRecord): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(Commitment.hash(Utf8("sigilaris.application.consistency.group.v1"), _))
final case class ConsistencyNamespaceContent(
    image: ConsistencyNamespaceImage,
    files: Map[Text, Bytes],
)

/** Mandatory real lifecycle scope. Hold the actual old consensus/QC/listener,
  * safety/index, pipeline and namespace writer gates throughout run, in their
  * installed global order, then controller gate. Releasing before run completes
  * is invalid. The original-group authenticator independently checks the fixed
  * deployment/layout/mutation coverage. No permissive default is provided.
  */
trait ConsistencyGroupLifecycle:
  def verifyController(
      controller: FenceController,
      source: DomainContext,
  ): Result[IO, Unit]
  def withStopped[A](run: Result[IO, A]): Result[IO, A]

/** A concrete gate for integrations which route every relevant mutation through
  * one installed runtime coordinator. It neither owns keys nor stands in for a
  * deployment's independent proof that all original mutation paths use it.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
final class ConsistencyMutationGate private (
    gate: Semaphore[IO],
    controller: FenceController,
    source: DomainContext,
) extends ConsistencyGroupLifecycle:
  def verifyController(
      expected: FenceController,
      context: DomainContext,
  ): Result[IO, Unit] =
    EitherT.fromEither[IO](
      RuntimeCheck.require(
        (controller eq expected) && source == context,
        RuntimeFailureCode.InvalidRequest,
        "lifecycle must bind the same live controller and exact old source context",
      ),
    )
  def mutate[A](run: Result[IO, A]): Result[IO, A] = EitherT(
    gate.permit.use(_ =>
      (for
        current <- controller.snapshot
        history <- EitherT.fromEither[IO](ControllerHistory.recover(current))
        _       <- EitherT.fromEither[IO](
          RuntimeCheck.require(
            !history.closures.valuesIterator.exists(
              _.context == source,
            ) && !history.enforcements.valuesIterator.exists(e =>
              e.promise.scope == FenceScope.SourceOrRetiredDomainWritesAndSigning && e.promise.context.chainId == source.chainId,
            ),
            RuntimeFailureCode.UnsafeBoundary,
            "old runtime metadata/listener writes are permanently retired by the actual controller",
          ),
        )
        value <- run
      yield value).value,
    ),
  )
  def withStopped[A](run: Result[IO, A]): Result[IO, A] = EitherT(
    gate.permit.use(_ => IO.uncancelable(_ => run.value)),
  )
object ConsistencyMutationGate:
  def create(
      controller: FenceController,
      source: DomainContext,
  ): Result[IO, ConsistencyMutationGate] = for
    _ <- EitherT.fromEither[IO](
      RuntimeCheck.core(DomainContext.validate(source)),
    )
    gate <- EitherT.liftF(Semaphore[IO](1L))
  yield new ConsistencyMutationGate(gate, controller, source)

/** Original source decoding must prove these two different frontiers from the
  * actual stored namespaces and retained certified suffix replay. copied roots
  * or a submitted manifest alone do not prove closure. fixedEvidence must parse
  * every original present/absence artifact required by the fixed baseline;
  * absent directories never synthesize historical absence certificates.
  */
final case class ConsistencyOriginalState(
    canonical: ConsistencyFrontier,
    working: ConsistencyFrontier,
    fixedEvidence: Vector[EvidenceRef],
    semanticInventory: Hash,
    workingStatePayload: Bytes,
)
trait ConsistencyGroupAuthentication:
  def verifyOriginal(
      transition: VerifiedHandover,
      group: ConsistencyGroupRecord,
      contents: Vector[ConsistencyNamespaceContent],
  ): Result[IO, ConsistencyOriginalState]
  def verifyFixedLayout(layout: ConsistencyLayoutRecord): Result[IO, Unit]

/** Explicit codec for one installed namespace format. semanticDigest is derived
  * from fully decoded original logical records, not serialized layout bytes;
  * any application-state root is derived by that actual application's codec.
  */
final case class ConsistencyNamespaceSemantics(
    semanticDigest: Hash,
    applicationStateRoots: Vector[Hash],
)
trait ConsistencyNamespaceCodec:
  def namespace: Text
  def schema: Long
  def decode(
      content: ConsistencyNamespaceContent,
  ): Result[IO, ConsistencyNamespaceSemantics]
trait ConsistencySchemaMigration:
  def namespace: Text
  def fromSchema: Long
  def toSchema: Long
  def convert(
      original: ConsistencyNamespaceContent,
  ): Result[IO, Map[Text, Bytes]]

final case class ConsistencyPreparedRecord(
    format: Long,
    completeOldGroupDigest: Hash,
    namespaces: Vector[ConsistencyNamespaceImage],
    retainedSafetyInventoryDigest: Hash,
    workingStatePayload: Bytes,
)
object ConsistencyPreparedRecord:
  given ByteEncoder[ConsistencyPreparedRecord] = ByteEncoder.derived
  given ByteDecoder[ConsistencyPreparedRecord] = ByteDecoder.derived
  val codec                                    =
    CanonicalCodec.derived[ConsistencyPreparedRecord](ConsistencyShape.prepared)
  def digest(v: ConsistencyPreparedRecord): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(
      Commitment.hash(Utf8("sigilaris.application.consistency.prepared.v1"), _),
    )

/** Only the complete capture/original-closure/inactive-migration verifier
  * creates this capability. A capability does not waive current group
  * revalidation.
  */
type VerifiedConsistencyGroup = ConsistencyGroupStore.VerifiedConsistencyGroup
trait ConsistencyGroupStore:
  def capture(
      transition: VerifiedHandover,
  ): Result[IO, VerifiedConsistencyGroup]
  def withRevalidated[A](
      group: VerifiedConsistencyGroup,
      transition: VerifiedHandover,
  )(run: VerifiedConsistencyGroup => Result[IO, A]): Result[IO, A]
  def readOriginal(
      completeOldGroupDigest: Hash,
  ): Result[IO, ConsistencyGroupRecord]
  def readPrepared(contentDigest: Hash): Result[IO, ConsistencyNamespaceContent]
  def recover(
      completeOldGroupDigest: Hash,
      preparedRecordDigest: Hash,
      transition: VerifiedHandover,
  ): Result[IO, VerifiedConsistencyGroup]

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
private[v2] object ConsistencyShape:
  private def check(
      condition: Boolean,
      field: String,
  ): Either[CoreFailure, Unit] =
    V2Validation.require(condition, FailureCode.ProofInvalid, field)
  def path(value: Text): Either[CoreFailure, Unit] =
    val s = value.asString
    V2Validation
      .identifier(value, "consistency.path")
      .flatMap(_ =>
        check(
          !s.startsWith("/") && !s.contains("\\") && s
            .split("/", -1)
            .forall(p => p.nonEmpty && p != "." && p != "..") && !s
            .contains(0.toChar),
          "consistency.relativePath",
        ),
      )
  def namespace(v: ConsistencyNamespace): Either[CoreFailure, Unit] = for
    _ <- V2Validation.identifier(v.name, "consistency.namespace")
    _ <- path(v.relativePath)
    _ <- check(
      v.schema >= 0L && v.roles.nonEmpty && v.roles
        .map(_.tag) == v.roles.map(_.tag).distinct.sorted,
      "consistency.schemaRoles",
    )
  yield ()
  def layout(v: ConsistencyLayoutRecord): Either[CoreFailure, Unit] = for
    _ <- V2Validation.format(v.format, 1L, "consistency.layoutFormat")
    _ <- v.namespaces.traverse_(namespace)
    _ <- V2Validation.sortedUnique(
      v.namespaces.map(n => V2Validation.textKey(n.name)),
      "consistency.namespaces",
    )
    _ <- check(
      v.namespaces
        .flatMap(_.roles)
        .toSet
        .intersect(ConsistencyRole.mandatory) == ConsistencyRole.mandatory,
      "consistency.completeRoles",
    )
    paths = v.namespaces.map(_.relativePath.asString)
    _ <- check(
      paths.distinct.sizeCompare(paths.size) == 0 && !paths.exists(p =>
        paths.exists(q => p != q && q.startsWith(p + "/")),
      ),
      "consistency.namespaceAliasing",
    )
  yield ()
  def image(v: ConsistencyNamespaceImage): Either[CoreFailure, Unit] = for
    _ <- V2Validation.format(v.format, 1L, "consistency.imageFormat")
    _ <- namespace(v.namespace)
    _ <- v.directories.traverse_(path)
    _ <- V2Validation.sortedUnique(
      v.directories.map(V2Validation.textKey),
      "consistency.directories",
    )
    _ <- v.files.traverse_(f =>
      path(f.path).flatMap(_ => check(f.size >= 0L, "consistency.fileSize")),
    )
    _ <- V2Validation.sortedUnique(
      v.files.map(f => V2Validation.textKey(f.path)),
      "consistency.files",
    )
    _ <- check(
      v.files.nonEmpty && !v.files.exists(f => v.directories.contains(f.path)),
      "consistency.completeNamespace",
    )
    allPaths    = v.directories ++ v.files.map(_.path)
    directories = v.directories.map(_.asString).toSet
    _ <- check(
      allPaths.forall(p =>
        val segments = p.asString.split("/").toVector
        (1 until segments.size).forall(n =>
          directories.contains(segments.take(n).mkString("/")),
        ),
      ),
      "consistency.parentDirectories",
    )
    _ <- check(
      !v.files.exists(f =>
        allPaths.exists(p => p.asString.startsWith(f.path.asString + "/")),
      ),
      "consistency.fileAncestor",
    )
  yield ()
  def group(v: ConsistencyGroupRecord): Either[CoreFailure, Unit] = for
    _ <- V2Validation.format(v.format, 1L, "consistency.groupFormat")
    _ <- layout(v.layout)
    _ <- EvidenceBaseline.validate(v.baseline)
    _ <- v.namespaces.traverse_(image)
    _ <- check(
      v.namespaces.map(_.namespace) == v.layout.namespaces,
      "consistency.completeGroup",
    )
    _ <- DomainContext.validate(v.canonical.context)
    _ <- DomainContext.validate(v.working.context)
    _ <- ControllerSnapshot.codec.encode(v.controllerSnapshot).map(_ => ())
  yield ()
  def prepared(v: ConsistencyPreparedRecord): Either[CoreFailure, Unit] = for
    _ <- V2Validation.format(v.format, 1L, "consistency.preparedFormat")
    _ <- v.namespaces.traverse_(image)
    _ <- V2Validation.sortedUnique(
      v.namespaces.map(n => V2Validation.textKey(n.namespace.name)),
      "consistency.preparedNamespaces",
    )
  yield ()

final case class ConsistencyGroupCapacity(
    maximumFileBytes: Long,
    maximumGroupBytes: Long,
    maximumFiles: Int,
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Any",
  ),
)
object ConsistencyGroupStore:
  final class VerifiedConsistencyGroup private[ConsistencyGroupStore] (
      val original: ConsistencyGroupRecord,
      val completeOldGroupDigest: Hash,
      val preparedRecord: ConsistencyPreparedRecord,
      val preparedRecordDigest: Hash,
      val prepared: Vector[PreparedNamespace],
      val retainedSafetyInventoryDigest: Hash,
      private[v2] val controller: FenceController,
  ):
    def workingStatePayload: Bytes = preparedRecord.workingStatePayload

  val ContentNamespace: Text  = Utf8("consistency-content")
  val ImageNamespace: Text    = Utf8("consistency-namespaces")
  val GroupNamespace: Text    = Utf8("consistency-groups")
  val PreparedNamespace: Text = Utf8("consistency-prepared")
  val ContentDomain: Text = Utf8("sigilaris.application.consistency.file.v1")
  def contentDigest(bytes: Bytes): Hash = Commitment.hash(ContentDomain, bytes)

  /** Inventory derived from the complete canonical source group. Its digest is
    * the retained-safety inventory, never a substitute for GroupRecord.digest.
    */
  def originalInventory(
      record: ConsistencyGroupRecord,
  ): Either[CoreFailure, Vector[InventoryEntry]] = for
    _          <- ConsistencyGroupRecord.codec.encode(record)
    namespaces <- record.namespaces.traverse(n =>
      ConsistencyNamespaceImage
        .digest(n)
        .map(d => InventoryEntry(n.namespace.name, Utf8("namespace"), d)),
    )
    snapshot <- ControllerSnapshot.digest(record.controllerSnapshot)
    entries = (namespaces ++ Vector(
      InventoryEntry(Utf8("controller"), Utf8("snapshot"), snapshot),
      InventoryEntry(
        Utf8("controller"),
        Utf8("write-closure"),
        record.writeClosureDigest,
      ),
    )).sortBy(e =>
      (V2Validation.textKey(e.namespace), V2Validation.textKey(e.key)),
    )
    _ <- TransitionInventory.digest(entries)
  yield entries

  def configured(
      layout: ConsistencyLayout,
      baseline: EvidenceBaseline,
      authentication: ConsistencyGroupAuthentication,
      lifecycle: ConsistencyGroupLifecycle,
      codecs: Vector[ConsistencyNamespaceCodec],
      migrations: Vector[ConsistencySchemaMigration],
      controller: FenceController,
      journal: DurableJournal[IO],
      capacity: ConsistencyGroupCapacity,
  ): Either[V2RuntimeFailure, ConsistencyGroupStore] = for
    _ <- RuntimeCheck.core(ConsistencyLayoutRecord.codec.encode(layout.record))
    _ <- RuntimeCheck.core(EvidenceBaseline.codec.encode(baseline))
    _ <- RuntimeCheck.require(
      capacity.maximumFileBytes > 0L && capacity.maximumFileBytes < Int.MaxValue.toLong && capacity.maximumGroupBytes >= capacity.maximumFileBytes && capacity.maximumFiles > 0,
      RuntimeFailureCode.CapacityUnavailable,
      "invalid local complete-group capacity",
    )
    codecKeys     = codecs.map(c => (c.namespace, c.schema))
    migrationKeys = migrations.map(_.namespace)
    _ <- RuntimeCheck.require(
      codecKeys.distinct.sizeCompare(
        codecKeys.size,
      ) == 0 && migrationKeys.distinct.sizeCompare(migrationKeys.size) == 0,
      RuntimeFailureCode.InvalidRequest,
      "namespace codecs or explicit migrations duplicate identities",
    )
    _ <- RuntimeCheck.require(
      layout.record.namespaces.forall(n =>
        codecKeys.contains(n.name -> n.schema),
      ) && migrations.forall(m =>
        layout.record.namespaces.exists(n =>
          n.name == m.namespace && n.schema == m.fromSchema,
        ) && codecKeys.contains(m.namespace -> m.toSchema),
      ),
      RuntimeFailureCode.UnsupportedUpgrade,
      "every original and explicit output schema requires its installed decoder",
    )
  yield new Store(
    layout,
    baseline,
    authentication,
    lifecycle,
    codecs.map(c => (c.namespace -> c.schema) -> c).toMap,
    migrations.map(m => m.namespace -> m).toMap,
    controller,
    journal,
    capacity,
  )

  private final class Store(
      layout: ConsistencyLayout,
      baseline: EvidenceBaseline,
      authentication: ConsistencyGroupAuthentication,
      lifecycle: ConsistencyGroupLifecycle,
      codecs: Map[(Text, Long), ConsistencyNamespaceCodec],
      migrations: Map[Text, ConsistencySchemaMigration],
      controller: FenceController,
      journal: DurableJournal[IO],
      capacity: ConsistencyGroupCapacity,
  ) extends ConsistencyGroupStore:
    private val root = layout.root.toAbsolutePath.normalize()
    private def value[A](v: Either[V2RuntimeFailure, A]): Result[IO, A] =
      EitherT.fromEither[IO](v)
    private def core[A](v: Either[CoreFailure, A]): Result[IO, A] = value(
      RuntimeCheck.core(v),
    )
    private def check(condition: Boolean, detail: String): Result[IO, Unit] =
      value(
        RuntimeCheck.require(
          condition,
          RuntimeFailureCode.EvidenceContradictory,
          detail,
        ),
      )
    private def present[A](v: Option[A], detail: String): Result[IO, A] =
      EitherT.fromOption[IO](
        v,
        V2RuntimeFailure.at(RuntimeFailureCode.EvidenceMissing, detail),
      )
    private def physical[A](run: IO[A]): Result[IO, A] = EitherT(
      run.attempt.map(_.left.map {
        case e: GroupFailure => e.failure
        case _               =>
          V2RuntimeFailure.at(
            RuntimeFailureCode.StorageUnknown,
            "complete consistency-group filesystem outcome is unknown",
          )
      }),
    )
    private def stored(
        namespace: Text,
        digest: Hash,
        bytes: Bytes,
    ): Result[IO, Unit] = for
      _      <- journal.putBlob(namespace, digest, bytes)
      actual <- journal.readBlob(namespace, digest)
      _      <- check(
        actual == bytes,
        "forced immutable consistency blob differs on readback",
      )
    yield ()
    private def readContent(file: ConsistencyFile): Result[IO, Bytes] = for
      bytes <- journal.readBlob(ContentNamespace, file.contentDigest)
      _     <- check(
        bytes.size == file.size && contentDigest(bytes) == file.contentDigest,
        "original consistency content is missing or changed",
      )
    yield bytes
    private def contentCapacity(
        images: Vector[ConsistencyNamespaceImage],
    ): Result[IO, Unit] =
      value(
        RuntimeCheck.require(
          images.foldLeft(BigInt(0))((n, i) => n + i.files.size) <= BigInt(
            capacity.maximumFiles,
          ) &&
            images
              .flatMap(_.files)
              .forall(_.size <= capacity.maximumFileBytes) &&
            images
              .flatMap(_.files)
              .foldLeft(BigInt(0))((n, f) => n + f.size) <= BigInt(
              capacity.maximumGroupBytes,
            ),
          RuntimeFailureCode.CapacityUnavailable,
          "retained complete group exceeds local capacity",
        ),
      )
    private def contents(
        image: ConsistencyNamespaceImage,
    ): Result[IO, ConsistencyNamespaceContent] = for
      _     <- contentCapacity(Vector(image))
      files <- image.files.traverse(f => readContent(f).map(f.path -> _))
    yield ConsistencyNamespaceContent(image, files.toMap)
    private def sourceCodec(
        content: ConsistencyNamespaceContent,
    ): Result[IO, ConsistencyNamespaceSemantics] = present(
      codecs.get(
        content.image.namespace.name -> content.image.namespace.schema,
      ),
      "original namespace schema decoder is not installed",
    ).flatMap(_.decode(content))
    private def semanticInventory(
        contents: Vector[ConsistencyNamespaceContent],
    ): Result[IO, Hash] = for
      entries <- contents.traverse(c =>
        sourceCodec(c).map(s =>
          InventoryEntry(
            c.image.namespace.name,
            Utf8("semantic"),
            s.semanticDigest,
          ),
        ),
      )
      digest <- core(TransitionInventory.digest(entries))
    yield digest
    private def controllerClosure(
        snapshot: ControllerSnapshot,
        transition: VerifiedHandover,
        expected: Hash,
    ): Result[IO, Unit] = for
      history <- value(ControllerHistory.recover(snapshot))
      closure <- present(
        history.closures.get(expected),
        "stopped group lacks its original predecision write closure",
      )
      _ <- check(
        closure.context == transition.evidence.source && closure.transition == transition.evidence.transitionIntent,
        "old-context writer closure differs from exact handover",
      )
      _ <- value(ControllerHistory.stopped(history))
    yield ()
    private def validateFixed(
        record: ConsistencyGroupRecord,
    ): Result[IO, Unit] = for
      _ <- core(ConsistencyGroupRecord.codec.encode(record))
      _ <- check(
        record.layout == layout.record && record.baseline == baseline,
        "original group changed its independently fixed layout or evidence baseline",
      )
      _ <- authentication.verifyFixedLayout(layout.record)
    yield ()
    private def expectedCanonical(
        transition: VerifiedHandover,
    ): ConsistencyFrontier = ConsistencyFrontier(
      transition.evidence.source,
      transition.evidence.drainCheckpointId,
      transition.evidence.drainCheckpointHeight,
      transition.evidence.drainStateRoot,
    )
    private def expectedWorking(
        transition: VerifiedHandover,
    ): ConsistencyFrontier = ConsistencyFrontier(
      transition.evidence.source,
      transition.evidence.continuationParentId,
      transition.continuation.parentHeight,
      transition.evidence.continuationParentRoot,
    )
    private def original(
        transition: VerifiedHandover,
        record: ConsistencyGroupRecord,
        contents: Vector[ConsistencyNamespaceContent],
    ): Result[IO, ConsistencyOriginalState] = for
      _ <- lifecycle.verifyController(controller, transition.evidence.source)
      _ <- validateFixed(record)
      baselineDigest <- core(EvidenceBaseline.digest(baseline))
      _              <- check(
        record.transitionDigest == transition.digest && transition.evidence.evidenceBaselineDigest == baselineDigest && record.canonical == expectedCanonical(
          transition,
        ) && record.working == expectedWorking(transition),
        "source canonical F or certified working P differs from verified handover",
      )
      _ <- controllerClosure(
        record.controllerSnapshot,
        transition,
        record.writeClosureDigest,
      )
      _ <- check(
        contents.map(_.image) == record.namespaces,
        "original namespace contents are incomplete",
      )
      _         <- controllerFiles(record.controllerSnapshot, contents)
      semantics <- semanticInventory(contents)
      actual    <- authentication.verifyOriginal(transition, record, contents)
      _         <- check(
        actual.canonical == record.canonical && actual.working == record.working && actual.fixedEvidence == baseline.entries && actual.semanticInventory == semantics,
        "actual decoded source/history/baseline closure differs from captured group",
      )
    yield actual

    /** The physical controller namespace must contain the actual framed ledger
      * and identity corresponding to this live stopped lease, not a copied
      * audit from a different key or a caller-provided digest-only placeholder.
      */
    private def controllerFiles(
        snapshot: ControllerSnapshot,
        contents: Vector[ConsistencyNamespaceContent],
    ): Result[IO, Unit] = for
      namespaces <- value(
        RuntimeCheck.require(
          contents.count(
            _.image.namespace.roles.contains(ConsistencyRole.FenceHistory),
          ) == 1,
          RuntimeFailureCode.InvalidRequest,
          "one complete physical fence-controller namespace is required",
        ),
      ).as(
        contents.filter(
          _.image.namespace.roles.contains(ConsistencyRole.FenceHistory),
        ),
      )
      content <- present(
        namespaces.headOption,
        "actual controller namespace is missing",
      )
      ledger <- present(
        content.files.get(Utf8("LEDGER")),
        "actual controller ledger is missing",
      )
      identity <- present(
        content.files.get(Utf8("IDENTITY")),
        "actual controller identity is missing",
      )
      encoded         <- core(ControllerSnapshot.codec.encode(snapshot))
      encodedIdentity <- core(
        ControllerConfiguration.codec.encode(snapshot.configuration),
      )
      _ <- check(
        ledger == controllerFrame(encoded) && identity == controllerFrame(
          encodedIdentity,
        ),
        "physical controller state differs from the actual stopped key resource",
      )
    yield ()

    private def retain(content: ConsistencyNamespaceContent): Result[IO, Unit] =
      for
        _ <- content.image.files.traverse_(file =>
          present(
            content.files.get(file.path),
            "captured namespace file missing",
          ).flatMap(stored(ContentNamespace, file.contentDigest, _)),
        )
        bytes  <- core(ConsistencyNamespaceImage.codec.encode(content.image))
        digest <- core(ConsistencyNamespaceImage.digest(content.image))
        _      <- stored(ImageNamespace, digest, bytes)
      yield ()
    private def retainedSafety(
        record: ConsistencyGroupRecord,
    ): Result[IO, Hash] = for
      entries <- core(originalInventory(record))
      result  <- core(TransitionInventory.digest(entries))
    yield result

    private def migrated(
        content: ConsistencyNamespaceContent,
    ): Result[IO, ConsistencyNamespaceContent] =
      migrations.get(content.image.namespace.name) match
        case None            => EitherT.pure(content)
        case Some(migration) =>
          for
            before <- sourceCodec(content)
            files  <- migration.convert(content)
            namespace = content.image.namespace
              .copy(schema = migration.toSchema)
            image <- core(imageFromFiles(namespace, files))
            converted = ConsistencyNamespaceContent(image, files)
            after <- sourceCodec(converted)
            _     <- check(
              after == before,
              "inactive schema migration changed decoded application roots or logical records; use signed opening for root conversion",
            )
          yield converted

    private def preparedCap(
        record: ConsistencyGroupRecord,
        prepared: ConsistencyPreparedRecord,
        transition: VerifiedHandover,
        source: Vector[ConsistencyNamespaceContent],
        outputs: Vector[ConsistencyNamespaceContent],
    ): Result[IO, VerifiedConsistencyGroup] = for
      actual         <- original(transition, record, source)
      digest         <- core(ConsistencyGroupRecord.digest(record))
      preparedDigest <- core(ConsistencyPreparedRecord.digest(prepared))
      safety         <- retainedSafety(record)
      _              <- check(
        prepared.completeOldGroupDigest == digest && prepared.retainedSafetyInventoryDigest == safety && prepared.workingStatePayload == actual.workingStatePayload && prepared.namespaces == outputs
          .map(_.image) && outputs.map(
          _.image.namespace.name,
        ) == record.namespaces.map(_.namespace.name),
        "inactive preparation loses an original namespace or working-state proof",
      )
      _ <- source.zip(outputs).traverse_ { (before, after) =>
        for
          expected <- migrated(before)
          _        <- check(
            after == expected,
            "recovered inactive conversion differs from the installed deterministic migration",
          )
        yield ()
      }
      namespaces <- outputs.traverse { n =>
        for
          content     <- core(ConsistencyNamespaceImage.digest(n.image))
          sourceImage <- present(
            record.namespaces.find(_.namespace.name == n.image.namespace.name),
            "prepared namespace has no original source",
          )
          originalDigest <- core(ConsistencyNamespaceImage.digest(sourceImage))
        yield org.sigilaris.core.application.protocol.v2.PreparedNamespace(
          n.image.namespace.name,
          n.image.namespace.schema,
          content,
          originalDigest,
        )
      }
    yield new VerifiedConsistencyGroup(
      record,
      digest,
      prepared,
      preparedDigest,
      namespaces,
      safety,
      controller,
    )

    def capture(
        transition: VerifiedHandover,
    ): Result[IO, VerifiedConsistencyGroup] =
      lifecycle.withStopped(controller.withStopped { lease =>
        for
          _ <- lifecycle.verifyController(
            controller,
            transition.evidence.source,
          )
          _       <- authentication.verifyFixedLayout(layout.record)
          closure <- lease.closeWrites(
            transition.evidence.transitionIntent,
            transition.evidence.source,
          )
          snapshot <- lease.snapshot
          source   <- physical(
            IO.blocking(readPhysical(root, layout.record, capacity)),
          )
          record = ConsistencyGroupRecord(
            1L,
            layout.record,
            transition.digest,
            baseline,
            expectedCanonical(transition),
            expectedWorking(transition),
            source.map(_.image),
            snapshot,
            closure,
          )
          actual     <- original(transition, record, source)
          _          <- source.traverse_(retain)
          groupBytes <- core(ConsistencyGroupRecord.codec.encode(record))
          digest     <- core(ConsistencyGroupRecord.digest(record))
          _          <- stored(GroupNamespace, digest, groupBytes)
          outputs    <- source.traverse(migrated)
          _          <- outputs.traverse_(retain)
          safety     <- retainedSafety(record)
          prepared = ConsistencyPreparedRecord(
            1L,
            digest,
            outputs.map(_.image),
            safety,
            actual.workingStatePayload,
          )
          preparedBytes <- core(
            ConsistencyPreparedRecord.codec.encode(prepared),
          )
          preparedDigest <- core(ConsistencyPreparedRecord.digest(prepared))
          _   <- stored(PreparedNamespace, preparedDigest, preparedBytes)
          cap <- preparedCap(record, prepared, transition, source, outputs)
        yield cap
      })

    def readOriginal(digest: Hash): Result[IO, ConsistencyGroupRecord] = for
      bytes  <- journal.readBlob(GroupNamespace, digest)
      record <- core(ConsistencyGroupRecord.codec.decode(bytes))
      actual <- core(ConsistencyGroupRecord.digest(record))
      _      <- check(
        actual == digest,
        "complete source group digest differs from original content",
      )
      _ <- validateFixed(record)
      _ <- contentCapacity(record.namespaces)
      _ <- record.namespaces.traverse_(contents)
    yield record
    def readPrepared(digest: Hash): Result[IO, ConsistencyNamespaceContent] =
      for
        bytes  <- journal.readBlob(ImageNamespace, digest)
        image  <- core(ConsistencyNamespaceImage.codec.decode(bytes))
        actual <- core(ConsistencyNamespaceImage.digest(image))
        _      <- check(
          actual == digest,
          "namespace image differs from its selected content digest",
        )
        result <- contents(image)
      yield result
    def recover(
        groupDigest: Hash,
        preparedDigest: Hash,
        transition: VerifiedHandover,
    ): Result[IO, VerifiedConsistencyGroup] = for
      record   <- readOriginal(groupDigest)
      bytes    <- journal.readBlob(PreparedNamespace, preparedDigest)
      prepared <- core(ConsistencyPreparedRecord.codec.decode(bytes))
      actual   <- core(ConsistencyPreparedRecord.digest(prepared))
      _        <- check(
        actual == preparedDigest,
        "inactive preparation digest differs",
      )
      _       <- contentCapacity(prepared.namespaces)
      source  <- record.namespaces.traverse(contents)
      outputs <- prepared.namespaces.traverse(contents)
      current <- controller.snapshot
      _       <- check(
        ControllerHistory.prefix(record.controllerSnapshot, current),
        "recovery would forget post-backup controller promises, watermark, writes or closures",
      )
      cap <- preparedCap(record, prepared, transition, source, outputs)
    yield cap
    def withRevalidated[A](
        group: VerifiedConsistencyGroup,
        transition: VerifiedHandover,
    )(run: VerifiedConsistencyGroup => Result[IO, A]): Result[IO, A] =
      lifecycle.withStopped(controller.withStopped { lease =>
        for
          _ <- lease.closeWrites(
            transition.evidence.transitionIntent,
            transition.evidence.source,
          )
          current          <- lease.snapshot
          physicalContents <- physical(
            IO.blocking(readPhysical(root, layout.record, capacity)),
          )
          _ <- check(
            current == group.original.controllerSnapshot && physicalContents
              .map(_.image) == group.original.namespaces,
            "old namespaces, parent proof, or safety history advanced after capture; prepare a new complete group",
          )
          verified <- recover(
            group.completeOldGroupDigest,
            group.preparedRecordDigest,
            transition,
          )
          _      <- original(transition, verified.original, physicalContents)
          result <- run(verified)
        yield result
      })

  private final class GroupFailure(val failure: V2RuntimeFailure)
      extends RuntimeException(failure.message)
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def fail(code: RuntimeFailureCode, detail: String): Nothing =
    throw new GroupFailure(V2RuntimeFailure.at(code, detail))
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def required[A](v: Either[CoreFailure, A]): A =
    v.fold(e => throw new GroupFailure(V2RuntimeFailure.fromCore(e)), identity)
  private def controllerFrame(bytes: Bytes): Bytes =
    ByteVector.fromValidHex("53494746454e434531") ++ Commitment
      .hash(Utf8("sigilaris.application.controller.file.v1"), bytes)
      .bytes ++ bytes
  private def relative(root: Path, path: Path): Text = Utf8(
    root.relativize(path).iterator().asScala.map(_.toString).mkString("/"),
  )
  private def paths(root: Path): Vector[Path] =
    Using.resource(Files.walk(root))(_.iterator().asScala.toVector)
  private def physicalDirectory(path: Path): Unit =
    if !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files
        .isSymbolicLink(path)
    then
      fail(
        RuntimeFailureCode.EvidenceMissing,
        "required physical namespace directory is absent or aliased",
      )
  private def readPhysical(
      root: Path,
      layout: ConsistencyLayoutRecord,
      capacity: ConsistencyGroupCapacity,
  ): Vector[ConsistencyNamespaceContent] =
    Iterator
      .iterate(root)(_.getParent)
      .takeWhile(_ != null)
      .foreach(physicalDirectory)
    val roots = layout.namespaces.map(n =>
      root.resolve(n.relativePath.asString).normalize(),
    )
    roots.foreach(physicalDirectory)
    val all = paths(root)
    all.filter(p => Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).foreach {
      p =>
        if p != root && !roots.exists(r => p.startsWith(r) || r.startsWith(p))
        then
          fail(
            RuntimeFailureCode.EvidenceContradictory,
            "unlisted directory cannot be omitted from complete source group",
          )
    }
    val files =
      all.filter(p => !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
    if files.sizeCompare(capacity.maximumFiles) > 0 then
      fail(
        RuntimeFailureCode.CapacityUnavailable,
        "complete group exceeds local file count capacity",
      )
    files.foreach { p =>
      if !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files
          .isSymbolicLink(p) || roots.count(p.startsWith) != 1
      then
        fail(
          RuntimeFailureCode.EvidenceContradictory,
          "unlisted, symbolic, or non-regular file in complete source group",
        )
    }
    val attributes = files
      .map(p =>
        p -> Files.readAttributes(
          p,
          classOf[BasicFileAttributes],
          LinkOption.NOFOLLOW_LINKS,
        ),
      )
      .toMap
    val keys = attributes.values.toVector.map(_.fileKey())
    if keys.exists(_ == null) || keys.distinct.sizeCompare(keys.size) != 0 then
      fail(
        RuntimeFailureCode.EvidenceContradictory,
        "source files lack unique physical identities or share hard-link aliases",
      )
    val total = attributes.valuesIterator.map(a => BigInt(a.size())).sum
    if total > BigInt(capacity.maximumGroupBytes) || attributes.valuesIterator
        .exists(_.size() > capacity.maximumFileBytes)
    then
      fail(
        RuntimeFailureCode.CapacityUnavailable,
        "complete group exceeds local byte capacity",
      )
    val result = layout.namespaces.zip(roots).map { (namespace, directory) =>
      val selected = files
        .filter(_.startsWith(directory))
        .sortBy(p => V2Validation.textKey(relative(directory, p)))
      val contents = selected.map { path =>
        val before = attributes(path)
        val bytes  = ByteVector.view(Files.readAllBytes(path))
        val after  = Files.readAttributes(
          path,
          classOf[BasicFileAttributes],
          LinkOption.NOFOLLOW_LINKS,
        )
        if before.size() != bytes.size || before.size() != after
            .size() || before.lastModifiedTime() != after
            .lastModifiedTime() || before.fileKey() != after.fileKey()
        then
          fail(
            RuntimeFailureCode.EvidenceContradictory,
            "source file changed while actual mutation scope was stopped",
          )
        relative(directory, path) -> bytes
      }.toMap
      val directories = all
        .filter(p =>
          p != directory && p.startsWith(directory) && Files
            .isDirectory(p, LinkOption.NOFOLLOW_LINKS),
        )
        .map(relative(directory, _))
        .sortBy(V2Validation.textKey)
      val image = required(imageFromFiles(namespace, contents))
        .copy(directories = directories)
      val _ = required(ConsistencyNamespaceImage.codec.encode(image))
      ConsistencyNamespaceContent(image, contents)
    }
    if paths(root).toSet != all.toSet then
      fail(
        RuntimeFailureCode.EvidenceContradictory,
        "complete group layout changed while stopped",
      )
    result
  private def imageFromFiles(
      namespace: ConsistencyNamespace,
      files: Map[Text, Bytes],
  ): Either[CoreFailure, ConsistencyNamespaceImage] =
    val directories = files.keys.toVector
      .flatMap { path =>
        val pieces = path.asString.split("/", -1).toVector
        (1 until pieces.size).toVector.map(n =>
          Utf8(pieces.take(n).mkString("/")),
        )
      }
      .distinct
      .sortBy(V2Validation.textKey)
    val image = ConsistencyNamespaceImage(
      1L,
      namespace,
      directories,
      files.toVector
        .sortBy((path, _) => V2Validation.textKey(path))
        .map((path, bytes) =>
          ConsistencyFile(path, contentDigest(bytes), bytes.size),
        ),
    )
    ConsistencyNamespaceImage.codec.encode(image).as(image)
