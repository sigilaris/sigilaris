package org.sigilaris.conformance

import org.sigilaris.node.jvm.runtime.application.v2.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import org.sigilaris.conformance.V2HistoricalFixture.accepted
import org.sigilaris.conformance.V2RequestConformance.height
import org.sigilaris.core.application.protocol.v2.*

object V2HistoricalConformance:
  def cases: Vector[(String, () => IO[Unit])] = new Scenarios().cases
  def run(): IO[Unit]                         = cases.traverse_ { (name, run) =>
    IO.println("V2HistoricalConformance: " + name) *> IO.defer(run())
  }
  private final class Scenarios:
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
    private def assertNotEquals[A, B](actual: A, unexpected: B): Unit =
      assert(actual != unexpected, "unexpected equality: " + actual.toString)
    private def temporary: Resource[IO, Path] = Resource.make(
      IO.blocking(
        Files
          .createTempDirectory("sigilaris-historical-conformance-")
          .toRealPath(),
      ),
    )(path =>
      IO.blocking(
        Using.resource(Files.walk(path))(
          _.iterator().asScala.toVector.reverse.foreach(Files.delete),
        ),
      ),
    )

    scenario(
      "four original key controllers force actual legacy safety before votes; M1/M2 nonempty F3 and P5 replay across restart",
    ) {
      temporary.use { root =>
        val run = V2HistoricalFixture
          .material(root)
          .flatMap(material =>
            V2HistoricalControllerFixture.nodes(material).map(material -> _),
          )
        run.use { (material, nodes) =>
          for
            chain    <- accepted(material.buildOld(nodes.map(_.signer)))
            f        <- accepted(material.finalized(3L))
            verified <- accepted(material.consensus.finalized(f))
            rows     <- nodes.traverse(node => accepted(node.safety.recover))
            audits   <- nodes.traverse(node => accepted(node.controller.audit))
            _        <- IO {
              assertEquals(chain.size, 6)
              assertEquals(
                verified.ordered.map(_.proposal.block.height.toBigNat.toBigInt),
                Vector(BigInt(3), BigInt(4), BigInt(5)),
              )
              assertNotEquals(
                chain(3).block.stateRoot,
                chain(5).block.stateRoot,
              )
              assertEquals(
                chain(5).block.stateRoot.toUInt256,
                material.source.preStateRoot,
              )
              assert(rows.forall(_.state.voteIntents.size == 5))
              assert(
                audits.forall(
                  _.possibleSigningIntents.count(
                    _.material.height.exists(_.toBigNat.toBigInt > 0),
                  ) >= 5,
                ),
              )
            }
          yield ()
        } *> run.use { (material, nodes) =>
          for
            f        <- accepted(material.finalized(3L))
            verified <- accepted(material.consensus.finalized(f))
            rows     <- nodes.traverse(node => accepted(node.safety.recover))
            _        <- IO {
              assertEquals(verified.ordered.size, 3)
              assert(
                rows.forall(
                  _.state.voterWatermark.exists(_.height.toBigNat.toBigInt == 5),
                ),
              )
            }
          yield ()
        }
      }
    }

    scenario(
      "future old quorum fence remains valid after permitted old progress; original prefix differs from current watermark",
    ) {
      temporary.use { root =>
        V2HistoricalFixture
          .material(root)
          .flatMap(material =>
            V2HistoricalControllerFixture.nodes(material).map(material -> _),
          )
          .use { (material, nodes) =>
            val intent      = V2HistoricalControllerFixture.transition(material)
            val noInstalled = new HistoricalContinuationRepository[IO]:
              def read(digest: Hash): Result[IO, Bytes] =
                org.sigilaris.conformance.V2RequestConformance
                  .unavailable("no continuation selected yet")
              def installedGroup(
                  digest: Hash,
              ): Result[IO, VerifiedActiveGroup] =
                org.sigilaris.conformance.V2RequestConformance
                  .unavailable("no activation yet")
            val audit = new HistoricalContinuationAuthentication(
              material.consensus,
              material.history,
              material.validators,
              noInstalled,
              nodes.map(_.controller),
              nodes.map(_.safety),
              V2HistoricalControllerFixture.ScopeDigest,
            )
            for
              fences <- nodes.traverse { node =>
                accepted(for
                  promise <- node.controller.prepareFence(
                    intent,
                    material.old,
                    FenceScope.ConsensusProfileAtOrAbove,
                    height(6L),
                  )
                  fence <- node.controller.enforce(intent, promise)
                yield fence)
              }
              _        <- accepted(material.buildOld(nodes.map(_.signer)))
              verified <- fences
                .traverse(fence => accepted(audit.fence(fence, intent)))
              actual <- nodes.traverse(node => accepted(node.controller.audit))
              _      <- IO {
                assert(
                  verified.forall(
                    _.originalSignedHeights.forall(_.toBigNat.toBigInt == 0),
                  ),
                )
                assert(
                  actual.forall(
                    _.possibleSigningIntents
                      .exists(_.material.height.contains(height(5L))),
                  ),
                )
              }
            yield ()
          }
      }
    }

    scenario(
      "actual four-validator handover authenticates full F/P replay and source safety while inactive payload grants no installed base",
    ) {
      temporary.use { root =>
        V2HistoricalFixture
          .material(root)
          .flatMap(material =>
            V2HistoricalControllerFixture.nodes(material).map(material -> _),
          )
          .use { (material, nodes) =>
            for
              _          <- accepted(material.buildOld(nodes.map(_.signer)))
              transition <- accepted(
                org.sigilaris.conformance.V2HistoricalTransitionFixture
                  .prepare(material, nodes),
              )
              verified <- accepted(
                transition.verifier.verifyHandover(transition.evidence),
              )
              notInstalled <- HistoricalCanonicalVerifier
                .authenticated(
                  material.consensus,
                  transition.original,
                  HistoricalCanonicalCapacity(100L),
                )
                .verifyInstallation(verified)
                .value
              _ <- IO {
                assertEquals(
                  verified.continuation.retainedSuffix
                    .map(_.height.toBigNat.toBigInt),
                  Vector(BigInt(4), BigInt(5)),
                )
                assertEquals(verified.continuation.retainedSafety.size, 4)
                assertNotEquals(
                  verified.evidence.drainStateRoot,
                  verified.evidence.continuationParentRoot,
                )
                assert(notInstalled.isLeft)
              }
            yield ()
          }
      }
    }

    scenario(
      "unknown key outcome preserves the actual original vote record across all four controller reopens",
    ) {
      temporary.use { root =>
        V2HistoricalFixture.material(root).use { material =>
          val fault = new ControllerFaultInjector:
            def after(
                point: ControllerFaultPoint,
                sequence: Long,
                kind: ControllerEventKind,
            ): IO[Unit] =
              if point == ControllerFaultPoint.BeforeKeyUse && kind == ControllerEventKind.SigningResult
              then
                IO.raiseError(
                  new IllegalStateException(
                    "injected stop before original vote key use",
                  ),
                )
              else IO.unit
          val interrupted = (0 to 3).toVector.traverse(index =>
            V2HistoricalControllerFixture.node(
              material,
              index,
              if index == 3 then fault else ControllerFaultInjector.none,
            ),
          )
          interrupted.use { nodes =>
            for
              stopped <- material.buildOld(nodes.map(_.signer)).value
              held    <- accepted(nodes.last.safety.recover)
              _       <- IO {
                assert(stopped.isLeft)
                assertEquals(held.state.voteIntents.size, 1)
                assert(
                  held.state.voterWatermark
                    .exists(_.height.toBigNat.toBigInt == 1),
                )
              }
            yield ()
          } *> V2HistoricalControllerFixture.nodes(material).use { nodes =>
            for
              chain <- accepted(material.buildOld(nodes.map(_.signer)))
              held  <- accepted(nodes.last.safety.recover)
              audit <- accepted(nodes.last.controller.audit)
              _     <- IO {
                assertEquals(chain.size, 6)
                assertEquals(held.state.voteIntents.size, 5)
                assertEquals(
                  audit.observedSignatures
                    .count(_.intent.material.height.contains(height(1L))),
                  1,
                )
              }
            yield ()
          }
        }
      }
    }

    scenario(
      "historical M2 header two cannot be relabeled as a V2 exact producer and fewer than an old fence quorum cannot activate",
    ) {
      temporary.use { root =>
        V2HistoricalFixture
          .material(root)
          .flatMap(material =>
            V2HistoricalControllerFixture.nodes(material).map(material -> _),
          )
          .use { (material, nodes) =>
            val registry = new HistoricalV2VerifierRegistry[IO]:
              def resolve(
                  manifest: ProtocolManifest,
              ): Result[IO, HistoricalV2Verifiers[IO]] = V2HistoricalFixture
                .check(manifest == material.manifest, "original manifest")
                .as(
                  HistoricalV2Verifiers(
                    manifest,
                    material.v2.requests,
                    material.v2.states,
                  ),
                )
            for
              chain   <- accepted(material.buildOld(nodes.map(_.signer)))
              profile <- HistoricalAncestryProfiles
                .profiles(material.profiles, registry)
                .historical(material.target, chain.last.window)
                .value
              transition <- accepted(
                org.sigilaris.conformance.V2HistoricalTransitionFixture
                  .prepare(material, nodes),
              )
              split <- transition.verifier
                .verifyHandover(
                  transition.evidence.copy(fencePromises =
                    transition.evidence.fencePromises.take(2),
                  ),
                )
                .value
              _ <- IO {
                assert(profile.isLeft)
                assert(split.isLeft)
              }
            yield ()
          }
      }
    }

    scenario(
      "positive crypto reuse retains actual key-set lookup, source loss checks and original past-finality ancestry",
    ) {
      temporary.use { root =>
        V2HistoricalFixture
          .material(root)
          .flatMap(material =>
            V2HistoricalControllerFixture.nodes(material).map(material -> _),
          )
          .use { (material, nodes) =>
            import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
            for
              chain    <- accepted(material.buildOld(nodes.map(_.signer)))
              selected <- Ref.of[IO, ValidatorSet](material.source.validators)
              lookup = new ValidatorSetLookup[IO]:
                val trustRoot: BootstrapTrustRoot =
                  material.validators.trustRoot
                def validatorSetFor(
                    window: HotStuffWindow,
                ): IO[Either[HotStuffValidationFailure, ValidatorSet]] =
                  selected.get.map(Right(_))
              authentication = HistoricalConsensusAuthentication.authenticated(
                material.profiles,
                lookup,
                material.history,
                material.replay,
              )
              _ <- IO {
                val transaction = material.transaction(1L)
                val key         = material.source.transactionKey.publicKey
                assertEquals(
                  material.counterSignature(transaction, key),
                  Right(true),
                )
                assertEquals(
                  material.counterSignature(transaction, key),
                  Right(true),
                )
                assertEquals(
                  material.counterSignature(
                    transaction,
                    org.sigilaris.core.crypto.CryptoOps
                      .fromPrivate(BigInt(2020))
                      .publicKey,
                  ),
                  Right(false),
                )
                assertEquals(
                  material.counterSignature(
                    transaction.copy(command =
                      transaction.command
                        .copy(delta = transaction.command.delta + 1L),
                    ),
                    key,
                  ),
                  Right(false),
                )
                assertEquals(
                  material.counterSignature(
                    transaction.copy(signature =
                      scodec.bits.ByteVector.fill(72L)(0.toByte),
                    ),
                    key,
                  ),
                  Right(false),
                )
              }
              first  <- accepted(authentication.proposal(chain(1)))
              second <- accepted(authentication.proposal(chain(1)))
              _      <- IO(assertEquals(first.replay, second.replay))
              changed = ValidatorSet
                .apply(
                  material.source.validators.members.map(member =>
                    if member.id == chain(1).proposer then
                      member.copy(publicKey =
                        org.sigilaris.core.crypto.CryptoOps
                          .fromPrivate(BigInt(1919))
                          .publicKey,
                      )
                    else member,
                  ),
                )
                .toOption
                .get
              _         <- selected.set(changed)
              wrongKeys <- authentication.proposal(chain(1)).value
              _         <- IO(assert(wrongKeys.isLeft))
              _         <- selected.set(material.source.validators)
              original  <- material.original.get
              _         <- material.original.update(
                _ - chain(1).targetBlockId.toUInt256,
              )
              unavailable <- authentication.proposal(chain(1)).value
              _           <- IO(assert(unavailable.isLeft))
              _           <- material.original.set(original)
              old         <- accepted(material.finalized(0L))
              _           <- accepted(
                authentication.verifyPastFinality(chain(3), old, 100L),
              )
              _ <- (1L to 2L).toVector.traverse_(h =>
                accepted(
                  material
                    .finalized(h)
                    .flatMap(
                      authentication.verifyPastFinality(chain(3), _, 100L),
                    ),
                ),
              )
              replacement = old.proposal.copy(block =
                old.proposal.block.copy(timestamp =
                  org.sigilaris.node.jvm.runtime.block.BlockTimestamp
                    .unsafeFromEpochMillis(90000L),
                ),
              )
              invalid <- authentication
                .verifyPastFinality(
                  chain(3),
                  old.copy(proposal = replacement),
                  100L,
                )
                .value
              _ <- IO(assert(invalid.isLeft))
              _ <- accepted(authentication.proposal(chain(1)))
            yield ()
          }
      }
    }
