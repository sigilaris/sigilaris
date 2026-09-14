package org.sigilaris.conformance

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption, StandardCopyOption}
import java.util.UUID
import scala.util.Using
import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Full original source namespaces. Canonical F3 is physically published by the
  * real key controller after original three-QC authentication. P5 remains only
  * working replay evidence. All source schemas and the complete layout were
  * installed before the original key resource opened.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2HistoricalGroupFixture:
  import V2HistoricalFixture.{core, check}
  import V2RequestConformance.{value, height, uint}
  val Domain      = Utf8("neutral.historical-group.logical.v1")
  val StateDomain = Utf8("neutral.historical-group.canonical-state.v1")
  final case class Rows(format: Long, namespace: Text, entries: Vector[Bytes])
  object Rows:
    given ByteEncoder[Rows] = ByteEncoder.derived
    given ByteDecoder[Rows] = ByteDecoder.derived
    val codec               = CanonicalCodec.derived[Rows](v =>
      V2Validation.format(v.format, 1L, "originalRows.format"),
    )
  final case class State(
      format: Long,
      context: DomainContext,
      blockId: Hash,
      height: Height,
      stateRoot: Hash,
      payload: Bytes,
      results: Vector[Bytes],
      replay: Vector[V2HistoricalFixture.OldMaterial],
  )
  object State:
    given ByteEncoder[State] = ByteEncoder.derived
    given ByteDecoder[State] = ByteDecoder.derived
    val codec                = CanonicalCodec.derived[State](v =>
      V2Validation
        .format(v.format, 1L, "sourceState.format")
        .flatMap(_ => DomainContext.validate(v.context)),
    )
    def digest(v: State): Hash =
      Commitment.hash(StateDomain, value(codec.encode(v)))
  final case class Publication(
      format: Long,
      finality: FinalizedAnchorSuggestion,
      state: State,
  )
  object Publication:
    import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
    given ByteEncoder[Publication] = ByteEncoder.derived
    given ByteDecoder[Publication] = ByteDecoder.derived
    val codec                      = CanonicalCodec.derived[Publication](v =>
      V2Validation.format(v.format, 1L, "sourcePublication.format"),
    )
  final case class Blocks(
      format: Long,
      proposals: Vector[Proposal],
      certificates: Vector[QuorumCertificate],
      replay: Vector[V2HistoricalFixture.OldMaterial],
  )
  object Blocks:
    import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
    given ByteEncoder[Blocks] = ByteEncoder.derived
    given ByteDecoder[Blocks] = ByteDecoder.derived
    val codec                 = CanonicalCodec.derived[Blocks](v =>
      V2Validation.format(v.format, 1L, "sourceBlocks.format"),
    )
  final case class Effect(format: Long, intentDigest: Hash, stateDigest: Hash)
  object Effect:
    given ByteEncoder[Effect] = ByteEncoder.derived
    given ByteDecoder[Effect] = ByteDecoder.derived
    val codec                 = CanonicalCodec.derived[Effect](v =>
      V2Validation.format(v.format, 1L, "sourceEffect.format"),
    )

  private def read(path: Path): Result[IO, Bytes] =
    EitherT.liftF(IO.blocking(ByteVector.view(Files.readAllBytes(path))))
  private def atomic(path: Path, bytes: Bytes): IO[Unit] = IO.blocking {
    val pending = path.resolveSibling("pending-" + UUID.randomUUID().toString)
    Using.resource(
      FileChannel
        .open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
    ) { out =>
      val buffer = ByteBuffer.wrap(bytes.toArray)
      while buffer.hasRemaining do
        val _ = out.write(buffer)
      out.force(true)
    }
    val _ = Files.move(
      pending,
      path,
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING,
    )
    Using.resource(FileChannel.open(path.getParent, StandardOpenOption.READ))(
      _.force(true),
    )
  }
  private def immutable(path: Path, bytes: Bytes): Result[IO, Unit] = for
    exists <- EitherT.liftF(IO.blocking(Files.exists(path)))
    _      <-
      if exists then
        read(path).flatMap(v =>
          check(v == bytes, "immutable original source evidence changed"),
        )
      else EitherT.liftF(atomic(path, bytes))
  yield ()
  private def sourceRoot(m: V2HistoricalFixture.Material, index: Int): Path =
    m.root.resolve("node-" + index.toString)
  private def initial(m: V2HistoricalFixture.Material): State = State(
    1L,
    m.old,
    m.genesis.targetBlockId.toUInt256,
    height(0L),
    m.stateRoot(m.initialState),
    m.stateBytes(m.initialState),
    Vector.empty,
    Vector.empty,
  )
  private def config(m: V2HistoricalFixture.Material): Bytes = value(
    Rows.codec.encode(
      Rows(
        1L,
        Utf8("configuration"),
        Vector(
          value(
            ConsistencyLayoutRecord.codec.encode(
              V2HistoricalControllerFixture.Layout,
            ),
          ),
          value(HistoricalProfileConfiguration.codec.encode(m.ranges)),
          value(ProtocolManifest.codec.encode(m.manifest)),
          value(
            TransitionIntent.codec.encode(
              V2HistoricalControllerFixture.transition(m),
            ),
          ),
        ),
      ),
    ),
  )

  /** Called only before node/controller creation. Reopening never calls this.
    */
  def initialize(m: V2HistoricalFixture.Material): Result[IO, Unit] = for _ <-
      (0 until 4).toVector.traverse_ { index =>
        val root = sourceRoot(m, index)
        for
          _ <- EitherT.liftF(IO.blocking(Files.createDirectories(root)).void)
          _ <- V2HistoricalControllerFixture.Layout.namespaces
            .filterNot(n =>
              Set("controller", "original-safety", "application-safety")
                .contains(n.name.asString),
            )
            .traverse_ { n =>
              val dir   = root.resolve(n.relativePath.asString)
              val bytes = n.name.asString match
                case "canonical-state" => value(State.codec.encode(initial(m)))
                case "configuration"   => config(m)
                case _                 =>
                  value(Rows.codec.encode(Rows(1L, n.name, Vector.empty)))
              for
                _ <- EitherT.liftF(IO.blocking(Files.createDirectory(dir)).void)
                _ <-
                  if n.name.asString == "canonical-state" then
                    EitherT.liftF(
                      IO.blocking(Files.createDirectory(dir.resolve("effects")))
                        .void,
                    )
                  else EitherT.pure[IO, V2RuntimeFailure](())
                _ <- immutable(
                  dir.resolve(if n.name.asString == "canonical-state" then
                    "HEAD"
                  else "records"),
                  bytes,
                )
              yield ()
            }
        yield ()
      }
  yield ()

  private def originalBlocks(
      m: V2HistoricalFixture.Material,
  ): Result[IO, Blocks] = for
    proposals    <- EitherT.liftF(m.retained.get)
    certificates <- EitherT.liftF(m.certificates.get)
    originals    <- EitherT.liftF(m.original.get)
    selected = proposals.values.toVector
      .filter(_.block.height.toBigNat.toBigInt <= 5)
      .sortBy(_.block.height.toBigNat.toBigInt)
    _ <- check(
      selected.map(_.block.height.toBigNat.toBigInt) == (0 to 5).toVector
        .map(BigInt(_)),
      "complete original genesis-through-P history required",
    )
    _     <- selected.drop(1).traverse_(m.consensus.proposal)
    certs <- selected.traverse(p =>
      EitherT.fromOption[IO](
        certificates.get(p.proposalId),
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "original QC missing",
        ),
      ),
    )
    _ <- check(
      selected.head == m.genesis && certs.head == m.initialQc,
      "independently installed original genesis differs",
    )
    _ <- selected
      .drop(1)
      .zip(certs.drop(1))
      .traverse_((p, c) => m.consensus.certified(p, c))
    replay <- selected
      .drop(1)
      .traverse(p =>
        EitherT.fromOption[IO](
          originals.get(p.targetBlockId.toUInt256),
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofUnavailable,
            "complete original replay missing",
          ),
        ),
      )
  yield Blocks(1L, selected, certs, replay)

  private def finalizedState(
      m: V2HistoricalFixture.Material,
      proof: FinalizedAnchorSuggestion,
  ): Result[IO, State] = for
    authenticated <- m.consensus.finalized(proof)
    blocks        <- originalBlocks(m)
    first = authenticated.ordered.head
    _ <- check(
      first.proposal.block.height.toBigNat.toBigInt == 3,
      "source publisher requires actual finalized F3",
    )
    state <- m.decodeState(first.replay.material.canonicalStatePayload)
    _     <- check(
      m.stateRoot(state) == first.proposal.block.stateRoot.toUInt256,
      "original F3 execution payload root differs",
    )
  yield State(
    1L,
    m.old,
    first.proposal.targetBlockId.toUInt256,
    height(3L),
    m.stateRoot(state),
    first.replay.material.canonicalStatePayload,
    blocks.replay.take(3).map(_.result),
    blocks.replay.take(3),
  )

  final class Publisher(m: V2HistoricalFixture.Material, index: Int)
      extends V2HistoricalControllerFixture.OriginalCanonicalPublication:
    private val root = sourceRoot(m, index).resolve("canonical-state")
    def request: Result[IO, Bytes] = for
      proof <- m.finalized(3L)
      state <- finalizedState(m, proof)
      bytes <- core(Publication.codec.encode(Publication(1L, proof, state)))
    yield bytes
    def authenticateWrite(request: Bytes): Result[IO, ControllerWriteMaterial] =
      for
        decoded <- core(Publication.codec.decode(request))
        actual  <- finalizedState(m, decoded.finality)
        _       <- check(
          actual == decoded.state,
          "canonical publication differs from actual original F3 execution",
        )
        payload <- core(State.codec.encode(actual))
      yield ControllerWriteMaterial(
        m.old,
        Utf8("canonical-state"),
        Utf8("HEAD"),
        Some(State.digest(initial(m))),
        payload,
      )
    val writer: ControllerCanonicalWriter = new ControllerCanonicalWriter:
      def apply(intent: ControllerWriteIntent): Result[IO, Bytes] = for
        actual <- authenticateWrite(intent.request)
        _      <- check(
          actual == intent.material,
          "original physical canonical writer intent differs",
        )
        head   <- read(root.resolve("HEAD"))
        before <- core(State.codec.decode(head))
        _      <- check(
          State.digest(
            before,
          ) == actual.priorDigest.get || head == actual.payload,
          "original canonical CAS would overwrite another source state",
        )
        id     <- core(ControllerWriteIntent.digest(intent))
        next   <- core(State.codec.decode(actual.payload))
        effect <- core(Effect.codec.encode(Effect(1L, id, State.digest(next))))
        _ <- immutable(root.resolve("effects").resolve(id.bytes.toHex), effect)
        _ <- EitherT.liftF(atomic(root.resolve("HEAD"), actual.payload))
      yield effect
      def verify(
          intent: ControllerWriteIntent,
          effect: Bytes,
      ): Result[IO, Unit] = for
        actual  <- authenticateWrite(intent.request)
        decoded <- core(Effect.codec.decode(effect))
        id      <- core(ControllerWriteIntent.digest(intent))
        head    <- read(root.resolve("HEAD"))
        stored  <- read(root.resolve("effects").resolve(id.bytes.toHex))
        next    <- core(State.codec.decode(actual.payload))
        _       <- check(
          actual == intent.material && decoded == Effect(
            1L,
            id,
            State.digest(next),
          ) && stored == effect && head == actual.payload,
          "actual immutable F3 publication receipt or canonical head was lost",
        )
      yield ()

  def publishers(m: V2HistoricalFixture.Material): Vector[Publisher] =
    (0 until 4).toVector.map(new Publisher(m, _))
  def publish(
      m: V2HistoricalFixture.Material,
      nodes: Vector[V2HistoricalControllerFixture.Node],
      publishers: Vector[Publisher],
  ): Result[IO, Unit] = for
    _ <- check(
      nodes.sizeCompare(4) == 0 && publishers.sizeCompare(
        m.source.keys.size,
      ) == 0,
      "complete original writer roster",
    )
    _ <- nodes
      .zip(publishers)
      .traverse_((node, publisher) =>
        publisher.request.flatMap(node.controller.writeCanonical),
      )
  yield ()

  private def encodeArtifacts(
      t: V2HistoricalTransitionFixture.Transition,
  ): Bytes = value(
    Rows.codec.encode(
      Rows(
        1L,
        Utf8("fixed-evidence"),
        Vector(
          value(EvidenceBaseline.codec.encode(t.baseline)),
          value(HandoverEvidence.codec.encode(t.evidence)),
          value(HistoricalContinuationProof.codec.encode(t.proof)),
        ) ++ t.artifacts.toVector
          .sortBy((ref, _) => (ref.kind.tag.toInt, ref.digest.bytes.toHex))
          .flatMap((ref, bytes) =>
            Vector(value(EvidenceRef.codec.encode(ref)), bytes),
          ),
      ),
    ),
  )

  /** Uses the same lifecycle coordinator as every helper-owned metadata write.
    * The actual controller refuses all additional old signatures after fences.
    */
  def retainOriginal(
      m: V2HistoricalFixture.Material,
      node: V2HistoricalControllerFixture.Node,
      transition: V2HistoricalTransitionFixture.Transition,
      lifecycle: ConsistencyMutationGate,
  ): Result[IO, Unit] = check(
    lifecycle eq node.lifecycle,
    "source metadata must use the original node mutation coordinator",
  ) *> lifecycle.mutate {
    for
      blocks <- originalBlocks(m)
      _      <- EitherT.liftF(
        atomic(
          sourceRoot(m, node.index).resolve("original-blocks/records"),
          value(Blocks.codec.encode(blocks)),
        ),
      )
      _ <- EitherT.liftF(
        atomic(
          sourceRoot(m, node.index).resolve("fixed-evidence/records"),
          encodeArtifacts(transition),
        ),
      )
    yield ()
  }

  private def safetyFiles(archive: HistoricalSafetyArchive): Map[Text, Bytes] =
    journalFiles(
      value(HistoricalSafetyProfile.digest(archive.profile)).bytes,
      archive.originalRecords,
    )
  private def issuanceFiles(
      archive: HistoricalIssuanceArchive,
  ): Map[Text, Bytes] =
    journalFiles(
      value(HistoricalIssuancePolicy.codec.encode(archive.policy)),
      archive.records,
    )
  private def journalFiles(
      identity: Bytes,
      records: Vector[Bytes],
  ): Map[Text, Bytes] =
    val zero   = uint(0)
    val frames =
      records.foldLeft(ByteVector.empty)((out, row) =>
        out ++ ByteEncoder[Long].encode(row.size) ++ row ++
          Commitment
            .hash(
              Utf8("sigilaris.application.historical-canonical.frame.v1"),
              row,
            )
            .bytes,
      )
    val last = records.foldLeft(zero)((prior, row) =>
      Commitment.hash(
        Utf8("sigilaris.application.historical-canonical.chain.v1"),
        prior.bytes ++ Commitment
          .hash(
            Utf8("sigilaris.application.historical-canonical.frame.v1"),
            row,
          )
          .bytes,
      ),
    )
    Map(
      Utf8("IDENTITY") -> identity,
      Utf8("records")  -> frames,
      Utf8("HEAD")     -> (ByteEncoder[Long]
        .encode(records.size.toLong) ++ last.bytes),
      Utf8("LOCK") -> ByteVector.empty,
    )

  def groups(
      m: V2HistoricalFixture.Material,
      node: V2HistoricalControllerFixture.Node,
      transition: V2HistoricalTransitionFixture.Transition,
      lifecycle: ConsistencyMutationGate,
      journal: DurableJournal[IO],
  ): Result[IO, ConsistencyGroupStore] = for
    _ <- check(
      lifecycle eq node.lifecycle,
      "complete capture must use the original node mutation coordinator",
    )
    expectedBlocks <- originalBlocks(m)
    expectedState  <- finalizedState(m, transition.proof.drain)
    archive        <- node.safety.archive
    issuance       <- node.issuance.archive
    configured = config(m)
    evidence   = encodeArtifacts(transition)
    codec      = (selected: ConsistencyNamespace) =>
      new ConsistencyNamespaceCodec:
        val namespaceName   = selected.name
        def namespace: Text = namespaceName
        val schema: Long    = 1L
        def decode(
            content: ConsistencyNamespaceContent,
        ): Result[IO, ConsistencyNamespaceSemantics] = for
          _ <- check(
            content.image.namespace.name == namespaceName && content.image.namespace.schema == 1L,
            "original fixed schema selection differs",
          )
          semantic <- namespaceName.asString match
            case "canonical-state" =>
              for
                bytes <- EitherT.fromOption[IO](
                  content.files.get(Utf8("HEAD")),
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.EvidenceMissing,
                    "canonical F source absent",
                  ),
                )
                state <- core(State.codec.decode(bytes))
                _     <- check(
                  state == expectedState,
                  "canonical source F3 was relabeled as P or lost original results/replay",
                )
                audit <- node.controller.audit
                write <- EitherT.fromOption[IO](
                  audit.snapshot.records
                    .filter(_.kind == ControllerEventKind.WriteIntent)
                    .lastOption,
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.EvidenceMissing,
                    "original controller F3 publication absent",
                  ),
                )
                intent <- core(
                  ControllerWriteIntent.codec.decode(write.payload),
                )
                id <- core(ControllerWriteIntent.digest(intent))
                effect = value(
                  Effect.codec.encode(Effect(1L, id, State.digest(state))),
                )
                _ <- check(
                  content.files == Map(
                    Utf8("HEAD")                      -> bytes,
                    Utf8("effects/" + id.bytes.toHex) -> effect,
                  ),
                  "canonical publication receipt namespace is incomplete",
                )
                publisher = new Publisher(m, node.index)
                _ <- publisher.writer.verify(intent, effect)
              yield ConsistencyNamespaceSemantics(
                State.digest(state),
                Vector(state.stateRoot),
              )
            case "original-blocks" =>
              exact(content, value(Blocks.codec.encode(expectedBlocks))).as(
                ConsistencyNamespaceSemantics(
                  Commitment
                    .hash(Domain, value(Blocks.codec.encode(expectedBlocks))),
                  Vector.empty,
                ),
              )
            case "original-safety" =>
              check(
                content.files == safetyFiles(archive),
                "original safety physical journal differs from actual complete pre-sign archive",
              ).as(
                ConsistencyNamespaceSemantics(
                  Commitment.hash(
                    Domain,
                    ByteEncoder[HistoricalSafetyArchive].encode(archive),
                  ),
                  Vector.empty,
                ),
              )
            case "controller" =>
              for
                ledger <- EitherT.fromOption[IO](
                  content.files.get(Utf8("LEDGER")),
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.EvidenceMissing,
                    "controller ledger absent",
                  ),
                )
                _ <- check(
                  ledger.take(9L) == ByteVector.fromValidHex(
                    "53494746454e434531",
                  ),
                  "actual controller frame magic",
                )
                snapshot <- core(
                  ControllerSnapshot.codec.decode(ledger.drop(41L)),
                )
                _ <- check(
                  Commitment
                    .hash(
                      Utf8("sigilaris.application.controller.file.v1"),
                      ledger.drop(41L),
                    )
                    .bytes == ledger.slice(9L, 41L),
                  "controller frame checksum",
                )
                current <- node.controller.snapshot
                _       <- check(
                  snapshot.configuration == current.configuration && current.records
                    .startsWith(snapshot.records),
                  "source controller history is not retained",
                )
              yield ConsistencyNamespaceSemantics(
                value(ControllerSnapshot.digest(snapshot)),
                Vector.empty,
              )
            case "application-safety" =>
              for
                current <- node.issuance.archive
                _       <- check(
                  current == issuance && content.files == issuanceFiles(
                    issuance,
                  ),
                  "actual original issuance policy, claims, terminal proofs or complete physical journal changed",
                )
                _ <- HistoricalIssuanceSafety.verify(
                  issuance,
                  node.issuanceInstallation.authentication,
                )
                digest <- core(HistoricalIssuanceArchive.digest(issuance))
              yield ConsistencyNamespaceSemantics(digest, Vector.empty)
            case "configuration" =>
              exact(content, configured).as(
                ConsistencyNamespaceSemantics(
                  Commitment.hash(Domain, configured),
                  Vector.empty,
                ),
              )
            case "fixed-evidence" =>
              exact(content, evidence).as(
                ConsistencyNamespaceSemantics(
                  Commitment.hash(Domain, evidence),
                  Vector.empty,
                ),
              )
            case _ =>
              for
                bytes <- EitherT.fromOption[IO](
                  content.files.get(Utf8("records")),
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.EvidenceMissing,
                    "canonical empty store metadata absent",
                  ),
                )
                rows <- core(Rows.codec.decode(bytes))
                _    <- check(
                  Set(
                    "exact-admission",
                    "exact-idempotency",
                    "generic-pipelines",
                  ).contains(namespaceName.asString) &&
                    rows == Rows(
                      1L,
                      namespaceName,
                      Vector.empty,
                    ) && content.files.sizeCompare(1) == 0,
                  "original installed empty store is incomplete or changed",
                )
              yield ConsistencyNamespaceSemantics(
                Commitment.hash(Domain, bytes),
                Vector.empty,
              )
        yield semantic
    codecs         = V2HistoricalControllerFixture.Layout.namespaces.map(codec)
    authentication = new ConsistencyGroupAuthentication:
      def verifyFixedLayout(layout: ConsistencyLayoutRecord): Result[IO, Unit] =
        check(
          layout == V2HistoricalControllerFixture.Layout &&
            value(
              ConsistencyLayoutRecord.digest(layout),
            ) == V2HistoricalControllerFixture.transition(m).sourceSchemaDigest,
          "complete layout differs from independently installed original transition schema",
        )
      def verifyOriginal(
          handover: VerifiedHandover,
          group: ConsistencyGroupRecord,
          contents: Vector[ConsistencyNamespaceContent],
      ): Result[IO, ConsistencyOriginalState] = for
        actual <- transition.verifier.verifyHandover(handover.evidence)
        _      <- check(
          actual.digest == transition.verified.digest,
          "original transition evidence changed",
        )
        original <- originalBlocks(m)
        _        <- check(
          original == expectedBlocks,
          "original source wrapper/QC/replay set changed",
        )
        semantics <- contents.traverse(c =>
          codecs
            .find(_.namespace == c.image.namespace.name)
            .get
            .decode(c)
            .map(s =>
              InventoryEntry(
                c.image.namespace.name,
                Utf8("semantic"),
                s.semanticDigest,
              ),
            ),
        )
        digest  <- core(TransitionInventory.digest(semantics))
        working <- m.consensus.certified(
          transition.proof.suffix.last.proposal,
          transition.proof.suffix.last.certificate,
        )
      yield ConsistencyOriginalState(
        ConsistencyFrontier(
          m.old,
          expectedState.blockId,
          height(3L),
          expectedState.stateRoot,
        ),
        ConsistencyFrontier(
          m.old,
          working.execution.proposal.targetBlockId.toUInt256,
          height(5L),
          working.execution.replay.nextStateRoot,
        ),
        transition.baseline.entries,
        digest,
        working.execution.replay.material.canonicalStatePayload,
      )
    store <- EitherT.fromEither[IO](
      ConsistencyGroupStore.configured(
        ConsistencyLayout(
          sourceRoot(m, node.index),
          V2HistoricalControllerFixture.Layout,
        ),
        transition.baseline,
        authentication,
        lifecycle,
        codecs,
        Vector.empty,
        node.controller,
        journal,
        ConsistencyGroupCapacity(16777216L, 67108864L, 10000),
      ),
    )
  yield store
  private def exact(
      content: ConsistencyNamespaceContent,
      expected: Bytes,
  ): Result[IO, Unit] =
    check(
      content.files == Map(Utf8("records") -> expected),
      "actual original canonical namespace bytes differ",
    )
