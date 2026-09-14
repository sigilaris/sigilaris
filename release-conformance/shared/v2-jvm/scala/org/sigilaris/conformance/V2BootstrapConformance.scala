package org.sigilaris.conformance

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import cats.effect.{IO, IOApp, Ref, Resource}
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.Vote

/** Real independent controller keys and file journals. Initial certificates use
  * the original HotStuff framing; G remains a header/initial subject, never a
  * fabricated ordinary Proposal or finality proof.
  */
object V2BootstrapConformance extends IOApp.Simple:
  import V2RequestConformance.*
  import V2VotingConformance.{accepted, rejected}
  import V2BootstrapFixture.*
  def temporary: Resource[IO, Path] = Resource.make(
    IO.blocking(
      Files.createTempDirectory("sigilaris-v2-bootstrap-").toRealPath(),
    ),
  )(path =>
    IO.blocking(
      Using.resource(Files.walk(path))(
        _.iterator().asScala.toVector.reverse.foreach(Files.delete),
      ),
    ),
  )
  private def ready(
      node: Node,
      bootstrap: VerifiedBootstrap,
  ): IO[BootstrapStartupRecord] = for
    bound     <- accepted(node.installer.bind(bootstrap.source, bootstrap))
    _         <- IO(assert(bound.phase == BootstrapPhase.Bound))
    installed <- accepted(node.installer.install(bound))
    _         <- IO(
      assert(
        installed.phase == BootstrapPhase.Installed && installed.issuedVoteIntents.isEmpty,
      ),
    )
  yield installed
  private def vote(node: Node, startup: BootstrapStartupRecord): IO[Vote] = for
    prepared <- accepted(node.installer.prepareInitialVote(startup))
    records  <- accepted(node.journal.recover)
    _        <- IO(
      assert(
        records.exists(r =>
          r.operation == JournalOperation.BootstrapVote && r.status == JournalStatus.Committed,
        ),
      ),
    )
    request = value(
      InitialBootstrapSigningRequest.codec.encode(
        InitialBootstrapSigningRequest(
          1L,
          prepared.record.bundleDigest,
          value(BootstrapVoteIntent.digest(prepared.intent)),
        ),
      ),
    )
    before <- accepted(node.controller.audit)
    _      <- rejected(node.controller.sign(request))
    after  <- accepted(node.controller.audit)
    _      <- IO(assert(before.observedSignatures == after.observedSignatures))
    signed <- accepted(node.installer.signInitialVote(prepared))
  yield signed

  def fourIndependent: IO[Unit] = temporary.use(path =>
    material(path).use { m =>
      for
        bootstrap <- accepted(m.verified)
        saved <- (0 until 4).toVector.traverse(i => m.node(i, bootstrap)).use {
          nodes =>
            for
              startups <- nodes.traverse(ready(_, bootstrap))
              votes    <- nodes
                .take(3)
                .zip(startups.take(3))
                .traverse((node, startup) => vote(node, startup))
              _ <- IO(assert(votes.map(_.voter).distinct.size == 3))
              insufficient = InitialBootstrapConsensus
                .assemble(bootstrap, votes.take(2))
              _           <- IO(assert(insufficient.isLeft))
              certificate <- IO.fromEither(
                InitialBootstrapConsensus
                  .assemble(bootstrap, votes)
                  .leftMap(e => new AssertionError(e.message)),
              )
              parents <- nodes.traverse(n =>
                accepted(n.installer.verifyInitialCertificate(certificate)),
              )
              _ <- IO(
                assert(
                  parents.forall(p =>
                    p.record.phase == BootstrapPhase.Opened && p.genesis.parent.isEmpty &&
                      p.genesis.height == org.sigilaris.node.jvm.runtime.block.BlockHeight.Genesis && p.genesis.stateRoot.toUInt256 == m.stateRoot && p.genesis.executionPlanRoot
                        .contains(
                          value(ExecutionPlan.computeRoot(ExecutionPlan.empty)),
                        ),
                  ),
                ),
              )
              records <- accepted(nodes(3).journal.recover)
              _       <- IO(
                assert(
                  !records.exists(_.operation == JournalOperation.BootstrapVote),
                ),
              )
              _ <- rejected(
                nodes(3).installer.prepareInitialVote(parents(3).record),
              )
              original <- accepted(
                nodes.head.installer.prepareInitialVote(parents.head.record),
              )
              repeated <- accepted(
                nodes.head.installer.signInitialVote(original),
              )
              _ <- IO(assert(repeated == votes.head))
            yield (certificate, parents.map(_.record), votes)
        }
        (certificate, startup, votes) = saved
        _ <- (0 until 4).toVector.traverse_(i =>
          m.node(i, bootstrap).use { node =>
            for
              recovered <- accepted(node.installer.recover)
              _         <- IO(assert(recovered == startup(i)))
              parent    <- accepted(
                node.installer.verifyInitialCertificate(certificate),
              )
              _ <- IO(
                assert(
                  parent.record == recovered && parent.context == m.context,
                ),
              )
              _ <-
                if i < 3 then
                  for
                    prepared <- accepted(
                      node.installer.prepareInitialVote(recovered),
                    )
                    again <- accepted(node.installer.signInitialVote(prepared))
                    _     <- IO(assert(again == votes(i)))
                  yield ()
                else IO.unit
            yield ()
          },
        )
      yield ()
    },
  )

  def rejectedBindings: IO[Unit] = temporary.use(path =>
    material(path).use { m =>
      for
        bootstrap <- accepted(m.verified)
        _         <- m.node(0, bootstrap).use { node =>
          for
            bound <- accepted(node.installer.bind(bootstrap.source, bootstrap))
            _     <- rejected(node.installer.prepareInitialVote(bound))
            _     <- rejected(
              node.installer
                .install(bound.copy(installedStateInventory = uint(999L))),
            )
            altered = m.bundle.copy(genesisTimestampMillis =
              m.bundle.genesisTimestampMillis + 1L,
            )
            signedAltered = SignedBootstrapBundle(
              altered,
              envelope(value(BootstrapBundle.signingPreimage(altered))),
            )
            other     <- accepted(m.verifier.verifyBootstrap(signedAltered))
            _         <- rejected(node.installer.bind(other.source, other))
            installed <- accepted(node.installer.install(bound))
            prepared  <- accepted(node.installer.prepareInitialVote(installed))
            _         <- IO.blocking {
              Files.write(m.dumpPath, Array(1.toByte, 2.toByte)); ()
            }
            _ <- rejected(node.installer.signInitialVote(prepared))
            request = value(
              InitialBootstrapSigningRequest.codec.encode(
                InitialBootstrapSigningRequest(
                  1L,
                  prepared.record.bundleDigest,
                  value(BootstrapVoteIntent.digest(prepared.intent)),
                ),
              ),
            )
            _         <- rejected(node.controller.sign(request))
            inventory <- accepted(node.controller.audit)
            _         <- IO(assert(inventory.observedSignatures.isEmpty))
            _ <- IO.blocking { Files.write(m.dumpPath, m.dumpRaw.toArray); () }
            _ <- accepted(node.installer.signInitialVote(prepared))
          yield ()
        }
      yield ()
    },
  )

  def initialDomainAndQuorum: IO[Unit] = temporary.use(path =>
    material(path).use { m =>
      for
        bootstrap <- accepted(m.verified)
        _ <- (0 until 4).toVector.traverse(i => m.node(i, bootstrap)).use {
          nodes =>
            for
              starts      <- nodes.traverse(ready(_, bootstrap))
              votes       <- nodes.zip(starts).traverse((n, s) => vote(n, s))
              certificate <- IO.fromEither(
                InitialBootstrapConsensus
                  .assemble(bootstrap, votes.take(3))
                  .leftMap(e => new AssertionError(e.message)),
              )
              _ <- rejected(
                nodes.head.installer.verifyInitialCertificate(
                  certificate.copy(bundleDigest = uint(999L)),
                ),
              )
              _ <- rejected(
                nodes.head.installer.verifyInitialCertificate(
                  certificate.copy(genesisBlockId = uint(999L)),
                ),
              )
              _ <- rejected(
                nodes.head.installer.verifyInitialCertificate(
                  certificate.copy(quorum = certificate.quorum ++ bytes("00")),
                ),
              )
              _ <- IO(
                assert(
                  InitialBootstrapConsensus
                    .assemble(
                      bootstrap,
                      Vector(votes.head, votes.head, votes(1)),
                    )
                    .isLeft,
                ),
              )
              first <- accepted(
                nodes.head.installer.verifyInitialCertificate(certificate),
              )
              variant <- IO.fromEither(
                InitialBootstrapConsensus
                  .assemble(bootstrap, votes.drop(1))
                  .leftMap(e => new AssertionError(e.message)),
              )
              repeated <- accepted(
                nodes.head.installer.verifyInitialCertificate(variant),
              )
              _ <- IO(
                assert(
                  first.record == repeated.record && first.genesisBlockId == repeated.genesisBlockId && repeated.certificate == variant,
                ),
              )
              _ <- IO(
                assert(
                  InitialBootstrapConsensus
                    .attachSignature(
                      bootstrap,
                      m.source.keys(1)._1,
                      signatureBytes(votes.head.signature),
                    )
                    .isLeft,
                ),
              )
            yield ()
        }
      yield ()
    },
  )

  def unknownVoteBoundary: IO[Unit] =
    Vector(
      JournalFaultPoint.AfterFrameWrite,
      JournalFaultPoint.AfterFrameForce,
      JournalFaultPoint.AfterHeadMove,
      JournalFaultPoint.AfterHeadForce,
    ).traverse_(point =>
      temporary.use(path =>
        material(path).use { m =>
          for
            bootstrap <- accepted(m.verified)
            armed     <- Ref.of[IO, Boolean](false)
            fault = new JournalFaultInjector[IO]:
              def after(at: JournalFaultPoint, sequence: Long): IO[Unit] =
                if at == point && sequence == 3L then
                  armed
                    .getAndSet(false)
                    .flatMap(yes =>
                      if yes then
                        IO.raiseError(
                          new IllegalStateException(
                            "bootstrap vote write boundary",
                          ),
                        )
                      else IO.unit,
                    )
                else IO.unit
            installed <- m.node(0, bootstrap, fault).use { node =>
              for
                record   <- ready(node, bootstrap)
                _        <- armed.set(true)
                _        <- rejected(node.installer.prepareInitialVote(record))
                observed <- accepted(node.controller.audit)
                _        <- IO(assert(observed.observedSignatures.isEmpty))
              yield record
            }
            _ <- m.node(0, bootstrap).use { node =>
              for
                recovered <- accepted(node.installer.recover)
                _         <- IO(
                  assert(
                    recovered.phase == BootstrapPhase.Signing && recovered.bundleDigest == installed.bundleDigest && recovered.issuedVoteIntents.size == 1,
                  ),
                )
                prepared <- accepted(
                  node.installer.prepareInitialVote(recovered),
                )
                _     <- accepted(node.installer.signInitialVote(prepared))
                after <- accepted(node.journal.recover)
                _     <- IO(
                  assert(
                    after.count(r =>
                      r.status == JournalStatus.Committed && r.operation == JournalOperation.BootstrapVote,
                    ) == 1,
                  ),
                )
              yield ()
            }
          yield ()
        },
      ),
    )

  def retiredOriginals: IO[Unit] = Vector(
    (true, false),
    (false, false),
    (true, true),
  ).traverse_((present, partial) =>
    temporary.use(root =>
      V2RetiredBootstrapFixture.material(root, present, partial).use { m =>
        for
          bootstrap <- accepted(m.verified)
          duplicate = m.bundle.copy(retiredEvidence =
            m.bundle.retiredEvidence :+ m.bundle.retiredEvidence.head,
          )
          _ <- IO(assert(BootstrapBundle.codec.encode(duplicate).isLeft))
          certificate <- (0 until 4).toVector
            .traverse(i => m.node(i, bootstrap))
            .use { nodes =>
              for
                starts <- nodes.traverse(ready(_, bootstrap))
                votes  <- nodes
                  .take(3)
                  .zip(starts.take(3))
                  .traverse((n, s) => vote(n, s))
                certificate <- IO.fromEither(
                  InitialBootstrapConsensus
                    .assemble(bootstrap, votes)
                    .leftMap(e => new AssertionError(e.message)),
                )
                parents <- nodes.traverse(n =>
                  accepted(n.installer.verifyInitialCertificate(certificate)),
                )
                _ <- IO(
                  assert(
                    parents.forall(p =>
                      p.statePayload == m.statePayload && p.sourceInventory == m.sourceInventory,
                    ),
                  ),
                )
              yield certificate
            }
          _ <- m.node(3, bootstrap).use { node =>
            for
              before <- accepted(node.installer.recover)
              _      <- IO.blocking(Files.delete(m.reportPath))
              _      <- rejected(node.installer.recover)
              _      <- rejected(
                node.installer.verifyInitialCertificate(certificate),
              )
              _ <- IO.blocking {
                Files.write(m.reportPath, m.reportRaw.toArray); ()
              }
              after <- accepted(node.installer.recover)
              _     <- IO(assert(after == before))
              malicious = m.bundle.copy(
                retiredEvidence = Vector.empty,
                ancestryKind = SourceAncestry.NoPriorDomain,
              )
              signed = SignedBootstrapBundle(
                malicious,
                envelope(value(BootstrapBundle.signingPreimage(malicious))),
              )
              _ <- rejected(m.verifier.verifyBootstrap(signed))
            yield ()
          }
        yield ()
      },
    ),
  )

  def retiredScopeOverlap: IO[Unit] = temporary.use(root =>
    V2RetiredBootstrapFixture
      .material(root, true, true, true)
      .use(m => rejected(m.verified).void),
  )

  def run: IO[Unit] =
    fourIndependent *> rejectedBindings *> initialDomainAndQuorum *> unknownVoteBoundary *> retiredOriginals *> retiredScopeOverlap *>
      IO.println(
        "v2-initial-bootstrap four independent file journals/controllers; source/bundle/quorum/restart/fault gates passed",
      )
