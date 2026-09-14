package org.sigilaris.conformance

import org.sigilaris.node.jvm.runtime.application.v2.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import cats.data.EitherT
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal

object V2HistoricalGroupConformance:
  def cases: Vector[(String, () => IO[Unit])] = new Scenarios().cases
  def run(): IO[Unit]                         = cases.traverse_ { (name, run) =>
    IO.println("V2HistoricalGroupConformance: " + name) *> IO.defer(run())
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
    import V2HistoricalFixture.accepted
    private def temporary: Resource[IO, Path] = Resource.make(
      IO.blocking(
        Files.createTempDirectory("sigilaris-historical-group-").toRealPath(),
      ),
    )(root =>
      IO.blocking(
        Using.resource(Files.walk(root))(
          _.iterator().asScala.toVector.reverse.foreach(Files.delete),
        ),
      ),
    )
    scenario(
      "four actual F3 canonical publications become complete inactive activation groups; P5 remains working and all four reopen",
    ) {
      temporary.use { root =>
        val initial = V2HistoricalFixture
          .material(root)
          .evalTap(m => accepted(V2HistoricalGroupFixture.initialize(m)))
          .flatMap { m =>
            val publishers = V2HistoricalGroupFixture.publishers(m)
            V2HistoricalControllerFixture
              .nodes(m, publishers)
              .map(nodes => (m, nodes, publishers))
          }
        for
          selected <- initial.use { (m, nodes, publishers) =>
            for
              _ <- accepted(m.buildOld(nodes.map(_.signer)))
              _ <- accepted(
                V2HistoricalGroupFixture.publish(m, nodes, publishers),
              )
              transition <- accepted(
                V2HistoricalTransitionFixture.prepare(m, nodes),
              )
              groups <- nodes.traverse { node =>
                FileApplicationJournal
                  .resource(root.resolve("target-" + node.index.toString))
                  .use { journal =>
                    for
                      _         <- accepted(journal.recover)
                      lifecycle <- IO.pure(node.lifecycle)
                      _         <- accepted(
                        V2HistoricalGroupFixture
                          .retainOriginal(m, node, transition, lifecycle),
                      )
                      groups <- accepted(
                        V2HistoricalGroupFixture
                          .groups(m, node, transition, lifecycle, journal),
                      )
                      group <- accepted(groups.capture(transition.verified))
                      _     <- IO(
                        assertEquals(
                          group.original.canonical.height.toBigNat.toBigInt,
                          BigInt(3),
                        ),
                      )
                      _ <- IO(
                        assertEquals(
                          group.original.working.height.toBigNat.toBigInt,
                          BigInt(5),
                        ),
                      )
                      _ <- IO(
                        assertNotEquals(
                          group.original.canonical.stateRoot,
                          group.original.working.stateRoot,
                        ),
                      )
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
                      prepared <- accepted(
                        activation.prepare(transition.verified, group),
                      )
                      decision  <- accepted(activation.commit(prepared))
                      recovered <- accepted(activation.recover)
                      _ <- IO(assertEquals(recovered.decision, Some(decision)))
                      denied <- lifecycle
                        .mutate(EitherT.pure[IO, V2RuntimeFailure](()))
                        .value
                      _            <- IO(assert(denied.isLeft))
                      beforeSafety <- accepted(node.safety.archive)
                      late = transition.proof.suffix.last
                      oldQc <- node.safety
                        .observe(late.proposal, late.certificate)
                        .value
                      oldVote     <- node.signer.vote(late.proposal).value
                      afterSafety <- accepted(node.safety.archive)
                      _           <- IO {
                        assert(oldQc.isLeft)
                        assert(oldVote.isLeft)
                        assertEquals(afterSafety, beforeSafety)
                      }
                    yield decision -> group.completeOldGroupDigest
                  }
              }
            yield groups
          }
          _ <- V2HistoricalFixture
            .material(root)
            .flatMap { m =>
              val publishers = V2HistoricalGroupFixture.publishers(m)
              V2HistoricalControllerFixture.nodes(m, publishers).map(m -> _)
            }
            .use { (m, nodes) =>
              for
                transition <- accepted(
                  V2HistoricalTransitionFixture.prepare(m, nodes),
                )
                _ <- nodes.traverse_ { node =>
                  FileApplicationJournal
                    .resource(root.resolve("target-" + node.index.toString))
                    .use { journal =>
                      for
                        _         <- accepted(journal.recover)
                        lifecycle <- IO.pure(node.lifecycle)
                        groups    <- accepted(
                          V2HistoricalGroupFixture
                            .groups(m, node, transition, lifecycle, journal),
                        )
                        activation <- ActivationStore.authenticated(
                          ApplicationAnchor(
                            m.target,
                            transition.evidence.continuationParentId,
                            org.sigilaris.conformance.V2RequestConformance
                              .height(5L),
                            transition.evidence.continuationParentRoot,
                          ),
                          transition.verifier,
                          groups,
                          journal,
                        )
                        recovered <- accepted(activation.recover)
                        _         <- IO(
                          assertEquals(
                            recovered.decision,
                            Some(selected(node.index)._1),
                          ),
                        )
                        _ <- IO(
                          assertEquals(
                            recovered.activeGroup.get.group.completeOldGroupDigest,
                            selected(node.index)._2,
                          ),
                        )
                      yield ()
                    }
                }
              yield ()
            }
        yield ()
      }
    }
