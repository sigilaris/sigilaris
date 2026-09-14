package org.sigilaris.conformance

import java.nio.ByteBuffer
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.{
  ApplicationLockCertificate,
  ApplicationLockVote,
  ApplicationValidatorId,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal

/** Real source-policy files, original journal and controller key use. The late
  * third signature deliberately models a Byzantine retired key, while honest
  * keys remain fenced and all original deadlines/terminal records persist.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2HistoricalIssuanceConformance:
  import V2HistoricalFixture.accepted
  import V2RequestConformance.{height, value}
  private def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(
      Files.createTempDirectory("sigilaris-issued-original-").toRealPath(),
    ),
  )(path =>
    IO.blocking(
      Using.resource(Files.walk(path))(
        _.iterator().asScala.toVector.reverse.foreach(Files.delete),
      ),
    ),
  )
  private def rejected[A](action: Result[IO, A]): IO[Unit] =
    action.value.flatMap(result => IO(assert(result.isLeft)))
  private def nodes(
      m: V2HistoricalFixture.Material,
      fault: ControllerFaultInjector,
  ): Resource[IO, Vector[V2HistoricalControllerFixture.Node]] =
    V2HistoricalGroupFixture
      .publishers(m)
      .zipWithIndex
      .traverse((publisher, index) =>
        V2HistoricalControllerFixture.node(
          m,
          index,
          if index == 0 then fault else ControllerFaultInjector.none,
          publisher,
          None,
          IssuanceCapability.Enabled,
        ),
      )
  private def actualForcedClaim(
      root: Path,
      index: Int,
  ): IO[HistoricalIssuanceRecord] = IO.blocking {
    val dir =
      root.resolve("node-" + index.toString).resolve("application-safety")
    val head = Files.readAllBytes(dir.resolve("HEAD"))
    assert(
      ByteBuffer.wrap(head).getLong() >= 1L,
      "no forced original claim before actual key callback",
    )
    val raw  = ByteVector.view(Files.readAllBytes(dir.resolve("records")))
    val size = ByteBuffer.wrap(raw.take(8L).toArray).getLong()
    val row  =
      value(HistoricalIssuanceRecord.codec.decode(raw.slice(8L, 8L + size)))
    assert(row.terminalEvidence.isEmpty)
    val request = value(HistoricalIssuanceRequest.codec.decode(row.request))
    assert(request.subject.lastInclusionHeight == height(2L))
    assert(
      request.subject.inputIds == Vector(V2HistoricalIssuanceFixture.lockInput),
    )
    row
  }

  def originalBeforeKeyStrictExpiryAndLateQuorum
      : IO[Unit] = temporary.use(root =>
    V2HistoricalFixture.material(root).use { m =>
      for
        _        <- accepted(V2HistoricalGroupFixture.initialize(m))
        observed <- Ref.of[IO, Int](0)
        faults = new ControllerFaultInjector:
          def after(
              point: ControllerFaultPoint,
              sequence: Long,
              kind: ControllerEventKind,
          ): IO[Unit] =
            if point == ControllerFaultPoint.BeforeKeyUse && kind == ControllerEventKind.SigningResult
            then actualForcedClaim(root, 0).void *> observed.update(_ + 1)
            else IO.unit
        expected <- nodes(m, faults).use { all =>
          val first = all(0); val second = all(1); val third = all(2)
          for
            firstSignature <- accepted(
              first.issuance.issue(first.issuanceInstallation.request),
            )
            secondSignature <- accepted(
              second.issuance.issue(second.issuanceInstallation.request),
            )
            keyUses <- observed.get
            _       <- IO(
              assert(keyUses == 1, "before-key physical claim was not checked"),
            )
            _ <- accepted(
              third.issuance.beforeLock(third.issuanceInstallation.request),
            )
            _ <- rejected(
              third.controller.sign(third.issuanceInstallation.rawRequest),
            )
            _      <- accepted(third.controller.recover)
            before <- all.traverse(n => accepted(n.issuance.recover))
            _      <- IO(
              assert(
                before
                  .take(3)
                  .forall(v =>
                    v.live.size == 1 && v.greatestDeadline.contains(height(2)),
                  ),
              ),
            )
            _          <- accepted(m.buildOld(all.map(_.signer)))
            atDeadline <- accepted(first.issuanceInstallation.expiryProof(2L))
            _          <- rejected(
              first.issuance
                .expire(first.issuanceInstallation.rawRequest, atDeadline),
            )
            retained <- accepted(first.issuance.recover)
            _        <- IO(
              assert(
                retained.live.size == 1 && retained.claims.head.terminalHeight.isEmpty,
              ),
            )
            intent = V2HistoricalControllerFixture.transition(m)
            _ <- all.traverse_(n =>
              accepted(for
                promise <- n.controller.prepareFence(
                  intent,
                  m.old,
                  FenceScope.ApplicationIssuance,
                  height(6),
                )
                _ <- n.controller.enforce(intent, promise)
              yield ()),
            )
            _ <- rejected(
              first.issuance.issue(first.issuanceInstallation.request),
            )
            _      <- accepted(first.issuance.recover)
            _      <- rejected(V2HistoricalTransitionFixture.prepare(m, all))
            beyond <- accepted(first.issuanceInstallation.expiryProof(3L))
            originalProof = value(
              V2HistoricalIssuanceFixture.Nonapplication.codec.decode(beyond),
            )
            _ <- Vector(
              m.target,
              m.old.copy(chainId =
                org.sigilaris.core.datatype.Utf8("another-old-chain"),
              ),
            ).traverse_ { wrong =>
              val bad = value(
                V2HistoricalIssuanceFixture.Nonapplication.codec
                  .encode(originalProof.copy(context = wrong)),
              )
              rejected(
                first.issuance
                  .expire(first.issuanceInstallation.rawRequest, bad),
              ) *> accepted(first.issuance.recover).void
            }
            _ <- all
              .take(3)
              .traverse_(n =>
                accepted(
                  n.issuance.expire(n.issuanceInstallation.rawRequest, beyond),
                ),
              )
            expired <- all.traverse(n => accepted(n.issuance.recover))
            _       <- IO(
              assert(
                expired.forall(_.live.isEmpty) && expired
                  .take(3)
                  .forall(_.claims.head.terminalHeight.contains(height(3))),
              ),
            )
            activated <- activate(m, all)
            preimage = HistoricalIssuanceRequest
              .signingPreimage(first.issuanceInstallation.request)
            byzantine = CryptoOps
              .sign(m.source.keys(3)._2, CryptoOps.keccak256(preimage.toArray))
              .toOption
              .get
            byzantineVote = ApplicationLockVote(
              first.issuanceInstallation.request.subject.subject,
              ApplicationValidatorId(m.source.keys(3)._1),
              ByteEncoder[Long].encode(
                byzantine.v.toLong,
              ) ++ byzantine.r.bytes ++ byzantine.s.bytes,
            )
            certificate = ApplicationLockCertificate(
              first.issuanceInstallation.request.subject.subject,
              Vector(
                V2HistoricalIssuanceFixture
                  .vote(first.issuanceInstallation, firstSignature),
                V2HistoricalIssuanceFixture
                  .vote(second.issuanceInstallation, secondSignature),
                byzantineVote,
              ),
            )
            _ <- accepted(
              V2HistoricalIssuanceFixture.verifyCertificate(m, certificate),
            )
            _ <- IO(
              assert(certificate.subject.lastInclusionHeight == height(2)),
            )
            _ <- rejectLateV2Consumption(
              m,
              first.issuanceInstallation,
              certificate,
            )
            _ <- rejected(
              first.issuance.issue(first.issuanceInstallation.request),
            )
            _ <- accepted(first.issuance.recover)
            _ <- rejected(
              first.controller.sign(first.issuanceInstallation.rawRequest),
            )
            snapshots <- all.traverse(n => accepted(n.issuance.archive))
          yield (snapshots, certificate, activated)
        }
        _ <- nodes(m, ControllerFaultInjector.none).use(all =>
          for
            snapshots <- all.traverse(n => accepted(n.issuance.archive))
            _         <- IO(assert(snapshots == expected._1))
            _         <- accepted(
              V2HistoricalIssuanceFixture.verifyCertificate(m, expected._2),
            )
            _ <- reopenActivation(m, all, expected._3)
            _ <- all
              .take(3)
              .traverse_(n =>
                rejected(n.issuance.issue(n.issuanceInstallation.request)),
              )
          yield (),
        )
      yield ()
    },
  )

  def unknownKeyOutcomeRetainsOriginalDeadline: IO[Unit] = temporary.use(root =>
    V2HistoricalFixture.material(root).use { m =>
      for
        _      <- accepted(V2HistoricalGroupFixture.initialize(m))
        checks <- Ref.of[IO, Int](0)
        stop = new ControllerFaultInjector:
          def after(
              point: ControllerFaultPoint,
              sequence: Long,
              kind: ControllerEventKind,
          ): IO[Unit] =
            if point == ControllerFaultPoint.BeforeKeyUse && kind == ControllerEventKind.SigningResult
            then
              actualForcedClaim(root, 0).void *> checks.update(_ + 1) *> IO
                .raiseError(
                  new IllegalStateException(
                    "stop before actual old application key use",
                  ),
                )
            else IO.unit
        original <- nodes(m, stop).use(all =>
          for
            _ <- rejected(
              all(0).issuance.issue(all(0).issuanceInstallation.request),
            )
            _       <- accepted(all(0).issuance.recover)
            archive <- accepted(all(0).issuance.archive)
            calls   <- checks.get
            _       <- IO(assert(calls == 1 && archive.records.size == 1))
          yield archive,
        )
        _ <- nodes(m, ControllerFaultInjector.none).use(all =>
          for
            archive <- accepted(all(0).issuance.archive)
            before  <- accepted(all(0).controller.audit)
            _       <- IO(
              assert(
                archive == original && !before.observedSignatures.exists(
                  _.intent.material.kind == ControllerSigningKind.ApplicationLock,
                ),
              ),
            )
            signed <- accepted(
              all(0).issuance.issue(all(0).issuanceInstallation.request),
            )
            after <- accepted(all(0).issuance.archive)
            _     <- IO(assert(after == original && signed.signature.nonEmpty))
          yield (),
        )
      yield ()
    },
  )

  private def rejectLateV2Consumption(
      m: V2HistoricalFixture.Material,
      installation: V2HistoricalIssuanceFixture.Installation,
      certificate: ApplicationLockCertificate,
  ): IO[Unit] =
    val paths = Vector(
      m.root.resolve("issued-target-0/journal.log"),
      m.root.resolve("issued-target-0/HEAD"),
      m.root.resolve("node-0/canonical-state/HEAD"),
    )
    def actual = paths.traverse(path =>
      IO.blocking(ByteVector.view(Files.readAllBytes(path))),
    )
    val legacy = ByteEncoder[ApplicationLockCertificate].encode(certificate)
    val signedSource = value(
      V2HistoricalIssuanceFixture.LockSource.codec.decode(
        installation.request.sourceEvidence,
      ),
    )
    val originalTransaction = value(
      V2HistoricalIssuanceFixture.Authorized.codec.encode(
        signedSource.signedCommand,
      ),
    )
    val expired = m.source.lockSubject.copy(
      context = m.old,
      executionId = certificate.subject.executionId,
      dependencyPlanDigest = certificate.subject.dependencyPlanDigest.toUInt256,
      lastInclusionHeight = certificate.subject.lastInclusionHeight,
      inputs = certificate.subject.inputIds,
    )
    for
      before <- actual
      _      <- IO(assert(LockCertificate.codec.decode(legacy).isLeft))
      _      <- Vector(expired, expired.copy(context = m.target)).traverse_(
        subject =>
          rejected(
            m.v2.requests.verifyLock(
              subject,
              m.source.descriptor,
              originalTransaction,
              m.source.proofs,
            ),
          ),
      )
      after <- actual
      _     <- IO(
        assert(
          before == after,
          "rejected late old certificate changed actual activated journal or canonical head",
        ),
      )
    yield ()

  private def activate(
      m: V2HistoricalFixture.Material,
      all: Vector[V2HistoricalControllerFixture.Node],
  ): IO[Vector[(ActivationDecision, Hash)]] = for
    _ <- accepted(
      V2HistoricalGroupFixture.publish(
        m,
        all,
        V2HistoricalGroupFixture.publishers(m),
      ),
    )
    transition <- accepted(V2HistoricalTransitionFixture.prepare(m, all))
    selected   <- all.traverse { node =>
      FileApplicationJournal
        .resource(m.root.resolve("issued-target-" + node.index.toString))
        .use { journal =>
          for
            _ <- accepted(journal.recover)
            _ <- accepted(
              V2HistoricalGroupFixture
                .retainOriginal(m, node, transition, node.lifecycle),
            )
            groups <- accepted(
              V2HistoricalGroupFixture
                .groups(m, node, transition, node.lifecycle, journal),
            )
            group      <- accepted(groups.capture(transition.verified))
            activation <- ActivationStore.authenticated(
              ApplicationAnchor(
                m.target,
                transition.evidence.continuationParentId,
                group.original.working.height,
                transition.evidence.continuationParentRoot,
              ),
              transition.verifier,
              groups,
              journal,
            )
            preparation <- accepted(
              activation.prepare(transition.verified, group),
            )
            decision  <- accepted(activation.commit(preparation))
            recovered <- accepted(activation.recover)
            _         <- IO(
              assert(
                recovered.decision.contains(
                  decision,
                ) && group.original.canonical.height == height(3) &&
                  group.original.working.height == height(5),
              ),
            )
          yield decision -> group.completeOldGroupDigest
        }
    }
  yield selected
  private def reopenActivation(
      m: V2HistoricalFixture.Material,
      all: Vector[V2HistoricalControllerFixture.Node],
      expected: Vector[(ActivationDecision, Hash)],
  ): IO[Unit] = for
    transition <- accepted(V2HistoricalTransitionFixture.prepare(m, all))
    _          <- all.zip(expected).traverse_ { (node, selected) =>
      FileApplicationJournal
        .resource(m.root.resolve("issued-target-" + node.index.toString))
        .use { journal =>
          for
            _      <- accepted(journal.recover)
            groups <- accepted(
              V2HistoricalGroupFixture
                .groups(m, node, transition, node.lifecycle, journal),
            )
            activation <- ActivationStore.authenticated(
              ApplicationAnchor(
                m.target,
                transition.evidence.continuationParentId,
                height(5),
                transition.evidence.continuationParentRoot,
              ),
              transition.verifier,
              groups,
              journal,
            )
            recovered <- accepted(activation.recover)
            _         <- IO(
              assert(
                recovered.decision
                  .contains(selected._1) && recovered.activeGroup
                  .exists(_.group.completeOldGroupDigest == selected._2),
              ),
            )
          yield ()
        }
    }
  yield ()

  def missingBirthCannotBeRecreatedFromAnApplicationJournal(): IO[Unit] =
    temporary.use(root =>
      V2HistoricalFixture.material(root).use { m =>
        for
          installation <- V2HistoricalIssuanceFixture
            .installation(m, 0, IssuanceCapability.Enabled)
          _ <- HistoricalIssuanceSafetyStore
            .resource(
              root.resolve("node-0/application-safety"),
              installation.policy,
              installation.authentication,
              16777216L,
              JournalFaultInjector.none[IO],
            )
            .use(store => accepted(store.beforeLock(installation.request)).void)
          _ <- IO.blocking(Files.delete(root.resolve("issuance-policy-0")))
          rejected <- V2HistoricalIssuanceFixture
            .installation(m, 0, IssuanceCapability.Enabled)
            .attempt
          _ <- IO(assert(rejected.isLeft))
          _ <- IO.blocking(
            assert(!Files.exists(root.resolve("issuance-policy-0"))),
          )
        yield ()
      },
    )

  def run(): IO[Unit] =
    originalBeforeKeyStrictExpiryAndLateQuorum *> unknownKeyOutcomeRetainsOriginalDeadline *> missingBirthCannotBeRecreatedFromAnApplicationJournal() *>
      IO.println(
        "V2HistoricalIssuanceConformance PASS: birth-enabled real M1 before-key claims, strict finality expiry, retained late old quorum and actual controller unknown/reopen",
      )
