package org.sigilaris.conformance

import org.sigilaris.node.jvm.runtime.application.v2.*

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import cats.data.EitherT
import cats.effect.{IO, Ref, Resource, Deferred}
import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}

/** A neutral configured controller authentication parses actual
  * authority-signed original requests and a separately signed complete seed
  * roster. These tests exercise the real key-owning file controller and
  * physical CAS writer; they do not label this neutral proof format as a
  * production deployment audit.
  */
object V2FenceControllerConformance:
  def cases: Vector[(String, () => IO[Unit])] = new Scenarios().cases
  def run(): IO[Unit]                         = cases.traverse_ { (name, run) =>
    IO.println("V2FenceControllerConformance: " + name) *> IO.defer(run())
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
    private def core[A](v: Either[CoreFailure, A]): A =
      v.fold(e => fail(e.message), identity)
    private def accepted[A](v: Result[IO, A]): IO[A] = v.value.flatMap(
      _.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure),
    )
    private def rejected[A](v: Result[IO, A]): IO[Unit] =
      v.value.flatMap(r => IO(assert(r.isLeft)))
    private def lift[A](v: Either[V2RuntimeFailure, A]): Result[IO, A] =
      EitherT.fromEither[IO](v)
    private def bytes(s: String): Bytes =
      ByteVector.view(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    private def temporary: Resource[IO, Path] = Resource.make(
      IO.blocking(
        Files.createTempDirectory("sigilaris-controller-").toRealPath(),
      ),
    )(p =>
      IO.blocking(
        Using.resource(Files.walk(p))(
          _.iterator().asScala.toVector.reverse.foreach(Files.delete),
        ),
      ),
    )
    private val authority = CryptoOps.fromPrivate(BigInt(601))
    private val signer    = Utf8("neutral-controller")
    private val old       =
      DomainContext(1L, Utf8("controller-chain"), hash(1), 1L, hash(2))
    private val target    = DomainContext(2L, old.chainId, hash(3), 2L, hash(4))
    private val alternate = old.copy(epoch = 9L)
    private val intent    = TransitionIntent(
      1L,
      TransitionKind.Handover,
      old.chainId,
      target.chainId,
      target.configurationDigest,
      target.validatorSetHash,
      Some(height(10L)),
      hash(5),
      hash(6),
      hash(7),
    )
    private val requestDomain = Utf8("neutral.controller.request.v1")
    private val seedDomain    = Utf8("neutral.controller.seed.v1")

    private final case class Authorized(payload: Bytes, signature: Bytes)
    private object Authorized:
      import V2Codecs.given
      given ByteEncoder[Authorized] = ByteEncoder.derived
      given ByteDecoder[Authorized] = ByteDecoder.derived
      val codec                     =
        CanonicalCodec.derived[Authorized](_ => Right[CoreFailure, Unit](()))
    private final case class Action(
        context: DomainContext,
        kind: ControllerSigningKind,
        height: Height,
        nonce: Long,
    )
    private object Action:
      import V2Codecs.given
      given ByteEncoder[Action] = ByteEncoder.derived
      given ByteDecoder[Action] = ByteDecoder.derived
      val codec                 =
        CanonicalCodec.derived[Action](v => DomainContext.validate(v.context))

    private def signBytes(key: KeyPair, preimage: Bytes): Bytes =
      val s =
        CryptoOps.sign(key, CryptoOps.keccak256(preimage.toArray)).toOption.get
      ByteEncoder[Long].encode(s.v.toLong) ++ s.r.bytes ++ s.s.bytes
    private def authorize(domain: Text, payload: Bytes): Bytes = core(
      Authorized.codec.encode(
        Authorized(
          payload,
          signBytes(authority, Commitment.preimage(domain, payload)),
        ),
      ),
    )
    private def original(
        domain: Text,
        evidence: Bytes,
    ): Either[V2RuntimeFailure, Bytes] = for
      wrapped <- FixtureCheck.core(Authorized.codec.decode(evidence))
      _       <- verifyOriginalSignature(
        Commitment.preimage(domain, wrapped.payload),
        wrapped.signature,
        authority.publicKey.toBytes,
      )
    yield wrapped.payload

    private final class Material(
        val keys: KeyPair,
        history: ControllerInitialHistory,
        currentAuthorization: Result[IO, Unit] =
          EitherT.pure[IO, V2RuntimeFailure](()),
    ):
      val seed = authorize(
        seedDomain,
        core(ControllerInitialHistory.codec.encode(history)),
      )
      def signing(
          context: DomainContext = old,
          kind: ControllerSigningKind = ControllerSigningKind.Consensus,
          h: Long = 9L,
          nonce: Long = 1L,
      ): Bytes = authorize(
        requestDomain,
        core(Action.codec.encode(Action(context, kind, height(h), nonce))),
      )
      def write(
          context: DomainContext = old,
          payload: Bytes = bytes("one"),
          prior: Option[Hash] = None,
      ): Bytes = authorize(
        requestDomain,
        core(
          ControllerWriteMaterial.codec.encode(
            ControllerWriteMaterial(
              context,
              Utf8("canonical"),
              Utf8("head"),
              prior,
              payload,
            ),
          ),
        ),
      )
      val authentication = new ControllerOperationAuthentication:
        def signing(
            request: Bytes,
            id: Text,
            key: Bytes,
        ): Result[IO, ControllerSigningMaterial] = lift(
          for
            payload <- original(requestDomain, request)
            action  <- FixtureCheck.core(Action.codec.decode(payload))
            _       <- FixtureCheck.require(
              id == signer && key == keys.publicKey.toBytes && Vector(
                old,
                target,
                alternate,
              ).contains(action.context),
              RuntimeFailureCode.ProofInvalid,
              "request is outside independently configured key/context roster",
            )
          yield ControllerSigningMaterial(
            action.context,
            action.kind,
            Some(action.height),
            Commitment.preimage(requestDomain, payload),
          ),
        )
        def authorizeSigning(
            request: Bytes,
            id: Text,
            key: Bytes,
        ): Result[IO, Unit] =
          signing(request, id, key).flatMap(_ => currentAuthorization)
        def canonicalWrite(
            request: Bytes,
        ): Result[IO, ControllerWriteMaterial] =
          lift(for
            payload  <- original(requestDomain, request)
            material <- FixtureCheck.core(
              ControllerWriteMaterial.codec.decode(payload),
            )
            _ <- FixtureCheck.require(
              Vector(old, target, alternate).contains(
                material.context,
              ) && material.namespace == Utf8(
                "canonical",
              ) && material.key == Utf8("head"),
              RuntimeFailureCode.ProofInvalid,
              "write target is outside independently installed namespaces",
            )
          yield material)
        def initialHistory(
            evidence: Bytes,
            id: Text,
            key: Bytes,
        ): Result[IO, ControllerInitialHistory] = lift(for
          payload  <- original(seedDomain, evidence)
          retained <- FixtureCheck.core(
            ControllerInitialHistory.codec.decode(payload),
          )
          _ <- FixtureCheck.require(
            id == signer && key == keys.publicKey.toBytes && retained == history,
            RuntimeFailureCode.ProofInvalid,
            "original seed differs from independently retained complete authority roster",
          )
        yield retained)
        def enforce(
            transition: TransitionIntent,
            promise: FencePromise,
            id: Text,
            key: Bytes,
        ): Result[IO, Unit] = lift(for
          _ <- FixtureCheck.core(
            ControllerEnforcement.codec.encode(
              ControllerEnforcement(transition, promise),
            ),
          )
          _ <- FixtureCheck.require(
            transition == intent && promise.context == old && id == signer && key == keys.publicKey.toBytes && promise.signerId == id && promise.boundary == height(
              10L,
            ),
            RuntimeFailureCode.ProofInvalid,
            "fence differs from installed exact transition policy",
          )
        yield ())
        def closeWrites(
            closure: ControllerWriteClosure,
            id: Text,
            key: Bytes,
        ): Result[IO, Unit] = lift(
          FixtureCheck.require(
            closure.transition == intent && closure.context == old && id == signer && key == keys.publicKey.toBytes,
            RuntimeFailureCode.ProofInvalid,
            "write closure differs from installed old context policy",
          ),
        )
      def resource(
          path: Path,
          faults: ControllerFaultInjector = ControllerFaultInjector.none,
          writer: ControllerCanonicalWriter =
            ControllerCanonicalWriter.unavailable,
      ): Resource[IO, FileFenceController] = FileFenceController.resource(
        path,
        keys,
        signer,
        seed,
        authentication,
        writer,
        faults,
      )
    private def material(n: Int): Material = new Material(
      CryptoOps.fromPrivate(BigInt(n)),
      ControllerInitialHistory(
        Vector(old),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
      ),
    )
    private def fence(
        c: FileFenceController,
        scope: FenceScope,
    ): IO[SignedFencePromise] = accepted(
      c.prepareFence(intent, old, scope, height(10L)),
    ).flatMap(p => accepted(c.enforce(intent, p)))
    private def stopped(c: FileFenceController): IO[Unit] = fence(
      c,
      FenceScope.ApplicationIssuance,
    ) *> fence(c, FenceScope.ConsensusProfileAtOrAbove).void

    private def force(path: Path, payload: Bytes): Unit = Using.resource(
      FileChannel
        .open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
    ) { out =>
      val b = ByteBuffer.wrap(payload.toArray)
      while b.hasRemaining do
        val _ = out.write(b)
      out.force(true)
    }
    private def dirForce(path: Path): Unit = Using.resource(
      FileChannel.open(path, StandardOpenOption.READ),
    )(_.force(true))
    private def realWriter(root: Path): IO[ControllerCanonicalWriter] =
      IO.blocking {
        Files.createDirectory(root)
        dirForce(root.getParent)
        new ControllerCanonicalWriter:
          private def id(i: ControllerWriteIntent): String =
            core(ControllerWriteIntent.digest(i)).bytes.toHex
          private def current(i: ControllerWriteIntent): Path = root.resolve(
            i.material.context.configurationDigest.bytes.toHex + ".current",
          )
          def apply(i: ControllerWriteIntent): Result[IO, Bytes] =
            EitherT(IO.blocking {
              val evidence = core(ControllerWriteIntent.codec.encode(i))
              val effect   = root.resolve(id(i) + ".effect")
              val head     = current(i)
              val next     =
                ControllerWriteMaterial.contentDigest(i.material.payload).bytes
              if Files.exists(effect) then
                if ByteVector.view(Files.readAllBytes(effect)) != evidence then
                  throw new IllegalStateException("different immutable effect")
              else
                val prior = if Files.exists(head) then
                  Some(
                    UInt256.unsafeFromBytesBE(
                      ByteVector.view(Files.readAllBytes(head)),
                    ),
                  )
                else None
                if prior != i.material.priorDigest then
                  throw new IllegalStateException("canonical CAS prior differs")
                force(effect, evidence)
                dirForce(root)
              if !Files.exists(head) || ByteVector.view(
                  Files.readAllBytes(head),
                ) != next
              then
                val temporary = root.resolve(id(i) + ".pending")
                if !Files.exists(temporary) then force(temporary, next)
                Files.move(
                  temporary,
                  head,
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING,
                )
                dirForce(root)
              Right[V2RuntimeFailure, Bytes](evidence)
            })
          def verify(
              i: ControllerWriteIntent,
              effect: Bytes,
          ): Result[IO, Unit] =
            EitherT(IO.blocking {
              val retained = ByteVector.view(
                Files.readAllBytes(root.resolve(id(i) + ".effect")),
              )
              FixtureCheck.require(
                retained == effect && core(
                  ControllerWriteIntent.codec.encode(i),
                ) == effect,
                RuntimeFailureCode.EvidenceContradictory,
                "actual immutable canonical write effect differs",
              )
            })
      }

    scenario(
      "original unsigned fence is forced on disk before actual key use; boundary and restart remain fenced",
    ) {
      temporary.use { root =>
        val m = material(701)
        for
          checked <- Ref.of[IO, Int](0)
          faults = new ControllerFaultInjector:
            def after(
                point: ControllerFaultPoint,
                sequence: Long,
                kind: ControllerEventKind,
            ): IO[Unit] =
              if point == ControllerFaultPoint.BeforeKeyUse && kind == ControllerEventKind.FenceSignature
              then
                IO.blocking {
                  val bytes = ByteVector.view(
                    Files.readAllBytes(root.resolve("controller/LEDGER")),
                  )
                  val snapshot =
                    core(ControllerSnapshot.codec.decode(bytes.drop(41L)))
                  assertEquals(
                    snapshot.records.last.kind,
                    ControllerEventKind.Enforcement,
                  )
                  assertEquals(snapshot.records.last.sequence, sequence)
                  assert(
                    core(
                      ControllerEnforcement.codec.decode(
                        snapshot.records.last.payload,
                      ),
                    ).promise.signerId == signer,
                  )
                } *> checked.update(_ + 1)
              else IO.unit
          signed <- m.resource(root.resolve("controller"), faults).use { c =>
            for
              _      <- accepted(c.sign(m.signing()))
              signed <- fence(c, FenceScope.ConsensusProfileAtOrAbove)
              _      <- rejected(c.sign(m.signing(h = 10L)))
              _      <- accepted(c.sign(m.signing(h = 8L, nonce = 2L)))
              _      <- accepted(c.sign(m.signing(context = target, h = 10L)))
            yield signed
          }
          _ <- m.resource(root.resolve("controller")).use { c =>
            for
              _     <- rejected(c.sign(m.signing(h = 10L)))
              audit <- accepted(c.audit)
              _     <- IO(
                assert(
                  audit.snapshot.records
                    .exists(_.kind == ControllerEventKind.Enforcement),
                ),
              )
              _ <- IO(
                assertEquals(
                  signed.record.greatestPreviouslySignedHeight,
                  Some(height(9L)),
                ),
              )
            yield ()
          }
          count <- checked.get
          _     <- IO(assertEquals(count, 1))
        yield ()
      }
    }

    Vector(
      ControllerFaultPoint.AfterTempWrite,
      ControllerFaultPoint.AfterTempForce,
      ControllerFaultPoint.AfterAtomicReplace,
      ControllerFaultPoint.AfterDirectoryForce,
    ).foreach { point =>
      scenario(
        "unsigned enforcement crash at " + point + " cannot use key; complete restart enforces it",
      ) {
        temporary.use { root =>
          val m = material(702 + point.ordinal)
          for
            uses <- Ref.of[IO, Int](0)
            faults = new ControllerFaultInjector:
              def after(
                  p: ControllerFaultPoint,
                  sequence: Long,
                  kind: ControllerEventKind,
              ): IO[Unit] =
                (if p == ControllerFaultPoint.BeforeKeyUse then
                   uses.update(_ + 1)
                 else IO.unit) *>
                  (if p == point && kind == ControllerEventKind.Enforcement then
                     IO.raiseError(new RuntimeException("physical cut"))
                   else IO.unit)
            _ <- m.resource(root.resolve("controller"), faults).use { c =>
              for
                promise <- accepted(
                  c.prepareFence(
                    intent,
                    old,
                    FenceScope.ApplicationIssuance,
                    height(10L),
                  ),
                )
                _ <- rejected(c.enforce(intent, promise))
                _ <- rejected(
                  c.sign(
                    m.signing(kind = ControllerSigningKind.ApplicationLock),
                  ),
                )
              yield ()
            }
            count <- uses.get
            _     <- IO(assertEquals(count, 0))
            _     <- m.resource(root.resolve("controller")).use { c =>
              rejected(
                c.sign(
                  m.signing(kind = ControllerSigningKind.ApplicationEffect),
                ),
              ) *> accepted(c.snapshot).flatMap(s =>
                IO(
                  assert(
                    s.records.exists(_.kind == ControllerEventKind.Enforcement),
                  ),
                ),
              )
            }
          yield ()
        }
      }
    }

    scenario(
      "unknown signed fence publication retains enforcement and permits only exact administrative retry",
    ) {
      temporary.use { root =>
        val m      = material(711)
        val faults = new ControllerFaultInjector:
          def after(
              point: ControllerFaultPoint,
              sequence: Long,
              kind: ControllerEventKind,
          ): IO[Unit] =
            if point == ControllerFaultPoint.AfterKeyUse && kind == ControllerEventKind.FenceSignature
            then IO.raiseError(new RuntimeException("after key use"))
            else IO.unit
        for
          promise <- m.resource(root.resolve("controller"), faults).use { c =>
            for
              p <- accepted(
                c.prepareFence(
                  intent,
                  old,
                  FenceScope.SourceOrRetiredDomainWritesAndSigning,
                  height(10L),
                ),
              )
              _ <- rejected(c.enforce(intent, p))
            yield p
          }
          _ <- m.resource(root.resolve("controller")).use { c =>
            for
              _      <- rejected(c.sign(m.signing(context = alternate)))
              _      <- rejected(c.writeCanonical(m.write(context = target)))
              signed <- accepted(c.enforce(intent, promise))
              _      <- IO(assertEquals(signed.record, promise))
            yield ()
          }
        yield ()
      }
    }

    scenario(
      "partial pending file remains unchanged and blocks restart; it cannot prove absence",
    ) {
      temporary.use { root =>
        val m = material(712)
        for
          _ <- m
            .resource(root.resolve("controller"))
            .use(c => fence(c, FenceScope.ApplicationIssuance).void)
          damaged = bytes("incomplete record")
          path    = root.resolve("controller/pending-broken")
          _      <- IO.blocking(force(path, damaged))
          result <- m
            .resource(root.resolve("controller"))
            .use(_ => IO.unit)
            .attempt
          _      <- IO(assert(result.isLeft))
          actual <- IO.blocking(ByteVector.view(Files.readAllBytes(path)))
          _      <- IO(assertEquals(actual, damaged))
        yield ()
      }
    }

    scenario(
      "uncertain signing intent conservatively raises watermark before any consensus fence promise",
    ) {
      temporary.use { root =>
        val m      = material(713)
        val faults = new ControllerFaultInjector:
          def after(
              point: ControllerFaultPoint,
              sequence: Long,
              kind: ControllerEventKind,
          ): IO[Unit] =
            if point == ControllerFaultPoint.BeforeKeyUse && kind == ControllerEventKind.SigningResult
            then IO.raiseError(new RuntimeException("before signature"))
            else IO.unit
        m.resource(root.resolve("controller"), faults)
          .use(c => rejected(c.sign(m.signing(h = 10L)))) *>
          m.resource(root.resolve("controller"))
            .use(c =>
              rejected(
                c.prepareFence(
                  intent,
                  old,
                  FenceScope.ConsensusProfileAtOrAbove,
                  height(10L),
                ),
              ),
            )
      }
    }

    scenario(
      "stopped capture permanently closes old writes before decision and preserves current post-backup history",
    ) {
      temporary.use { root =>
        val m = material(714)
        for
          writer <- realWriter(root.resolve("writes"))
          saved <- m.resource(root.resolve("controller"), writer = writer).use {
            c =>
              for
                backup   <- accepted(c.snapshot)
                _        <- accepted(c.writeCanonical(m.write()))
                _        <- accepted(c.sign(m.signing()))
                _        <- stopped(c)
                held     <- Ref.of[IO, Option[StoppedFenceController]](None)
                captured <- accepted(c.withStopped { lease =>
                  for
                    _ <- EitherT.liftF(held.set(Some(lease)))
                    _ <- lease.closeWrites(intent, old)
                    _ <- EitherT.liftF(
                      rejected(c.sign(m.signing(context = target, h = 10L))),
                    )
                    snapshot <- lease.snapshot
                  yield snapshot
                })
                escaped <- held.get
                _       <- rejected(escaped.get.snapshot)
                _       <- rejected(
                  c.writeCanonical(
                    m.write(
                      payload = bytes("two"),
                      prior = Some(
                        ControllerWriteMaterial.contentDigest(bytes("one")),
                      ),
                    ),
                  ),
                )
                _ <- accepted(
                  c.writeCanonical(
                    m.write(context = target, payload = bytes("new-profile")),
                  ),
                )
                current <- accepted(c.preserveAfterRestore(backup))
                _       <- IO(
                  assert(
                    current.records.size > captured.records.size && current.records
                      .exists(_.kind == ControllerEventKind.WriteClosure),
                  ),
                )
              yield current
          }
          _ <- m.resource(root.resolve("controller"), writer = writer).use {
            c =>
              for
                current <- accepted(c.snapshot)
                _       <- IO(assertEquals(current, saved))
                _       <- rejected(
                  c.writeCanonical(
                    m.write(payload = bytes("old-writer-resumed")),
                  ),
                )
              yield ()
          }
        yield ()
      }
    }

    scenario(
      "canonical write interrupted after actual physical effect replays exact idempotent intent before Ready",
    ) {
      temporary.use { root =>
        val m      = material(715)
        val faults = new ControllerFaultInjector:
          def after(
              point: ControllerFaultPoint,
              sequence: Long,
              kind: ControllerEventKind,
          ): IO[Unit] = if point == ControllerFaultPoint.AfterCanonicalWrite
          then IO.raiseError(new RuntimeException("after actual CAS"))
          else IO.unit
        for
          writer <- realWriter(root.resolve("writes"))
          _      <- m
            .resource(root.resolve("controller"), faults, writer)
            .use(c => rejected(c.writeCanonical(m.write())))
          _ <- m.resource(root.resolve("controller"), writer = writer).use {
            c =>
              for
                snapshot <- accepted(c.snapshot)
                _        <- IO(
                  assertEquals(
                    snapshot.records.map(_.kind),
                    Vector(
                      ControllerEventKind.WriteIntent,
                      ControllerEventKind.WriteResult,
                    ),
                  ),
                )
                _ <- accepted(
                  c.writeCanonical(
                    m.write(
                      payload = bytes("two"),
                      prior = Some(
                        ControllerWriteMaterial.contentDigest(bytes("one")),
                      ),
                    ),
                  ),
                )
              yield ()
          }
        yield ()
      }
    }

    scenario(
      "same key/path ownership and stale handles cannot bypass actual gate",
    ) {
      temporary.use { root =>
        val m = material(716)
        for
          handle <- m.resource(root.resolve("controller")).use { c =>
            for
              samePath <- m
                .resource(root.resolve("controller"))
                .use(_ => IO.unit)
                .attempt
              sameKey <- m
                .resource(root.resolve("different"))
                .use(_ => IO.unit)
                .attempt
              _ <- IO(assert(samePath.isLeft && sameKey.isLeft))
            yield c
          }
          _ <- rejected(handle.sign(m.signing()))
          _ <- rejected(handle.recover)
          _ <- m
            .resource(root.resolve("controller"))
            .use(c => accepted(c.sign(m.signing())).void)
        yield ()
      }
    }

    scenario(
      "stop lease drains in-flight private operations before release and blocks concurrent admission",
    ) {
      temporary.use { root =>
        val m = material(717)
        m.resource(root.resolve("controller")).use { c =>
          for
            _       <- stopped(c)
            entered <- Deferred[IO, Unit]
            release <- Deferred[IO, Unit]
            fiber   <- accepted(
              c.withStopped(lease =>
                EitherT.liftF(
                  entered.complete(()).void *> release.get,
                ) *> lease.snapshot,
              ),
            ).start
            _ <- entered.get
            _ <- rejected(c.sign(m.signing(context = target, h = 10L)))
            _ <- release.complete(())
            _ <- fiber.joinWithNever
            _ <- accepted(c.sign(m.signing(context = target, h = 10L)))
          yield ()
        }
      }
    }

    Vector(
      ControllerFaultPoint.AfterTempWrite,
      ControllerFaultPoint.AfterTempForce,
      ControllerFaultPoint.AfterAtomicReplace,
      ControllerFaultPoint.AfterDirectoryForce,
    ).foreach { point =>
      scenario(
        "signed completion crash at " + point + " retains exact signature and does not reuse key on restart",
      ) {
        temporary.use { root =>
          val m = material(730 + point.ordinal)
          for
            uses <- Ref.of[IO, Int](0)
            faults = new ControllerFaultInjector:
              def after(
                  p: ControllerFaultPoint,
                  sequence: Long,
                  kind: ControllerEventKind,
              ): IO[Unit] =
                (if p == ControllerFaultPoint.BeforeKeyUse then
                   uses.update(_ + 1)
                 else IO.unit) *>
                  (if p == point && kind == ControllerEventKind.FenceSignature
                   then
                     IO.raiseError(
                       new RuntimeException("signed publication cut"),
                     )
                   else IO.unit)
            promise <- m.resource(root.resolve("controller"), faults).use { c =>
              for
                p <- accepted(
                  c.prepareFence(
                    intent,
                    old,
                    FenceScope.ApplicationIssuance,
                    height(10L),
                  ),
                )
                _ <- rejected(c.enforce(intent, p))
              yield p
            }
            _ <- m.resource(root.resolve("controller"), faults).use { c =>
              for
                restored <- accepted(c.enforce(intent, promise))
                _        <- rejected(
                  c.sign(
                    m.signing(kind = ControllerSigningKind.ApplicationEffect),
                  ),
                )
                count <- uses.get
                _     <- IO(assertEquals(count, 1))
                audit <- accepted(c.audit)
                _     <- IO(assertEquals(audit.signedFences, Vector(restored)))
              yield ()
            }
          yield ()
        }
      }
    }

    scenario(
      "stale fence draft cannot hide a newly observed signed height or signing prefix",
    ) {
      temporary.use { root =>
        val m = material(741)
        m.resource(root.resolve("controller")).use { c =>
          for
            promise <- accepted(
              c.prepareFence(
                intent,
                old,
                FenceScope.ConsensusProfileAtOrAbove,
                height(10L),
              ),
            )
            _     <- accepted(c.sign(m.signing(h = 10L)))
            _     <- rejected(c.enforce(intent, promise))
            audit <- accepted(c.audit)
            _     <- IO(
              assert(
                audit.enforcements.isEmpty && audit.observedSignatures.size == 1,
              ),
            )
            _ <- rejected(
              c.prepareFence(
                intent,
                old,
                FenceScope.ConsensusProfileAtOrAbove,
                height(10L),
              ),
            )
          yield ()
        }
      }
    }

    scenario(
      "authority signature and exact immutable seed are reverified; forged request and backup fork fail",
    ) {
      temporary.use { root =>
        val m = material(742)
        m.resource(root.resolve("controller")).use { c =>
          for
            before <- accepted(c.snapshot)
            valid  = m.signing()
            forged = valid.dropRight(1L) ++ ByteVector((valid.last ^ 1).toByte)
            _     <- rejected(c.sign(forged))
            _     <- rejected(c.writeCanonical(forged))
            _     <- accepted(c.sign(valid))
            after <- accepted(c.snapshot)
            _     <- rejected(
              c.preserveAfterRestore(
                before.copy(configuration =
                  before.configuration.copy(initialEvidence = ByteVector.empty),
                ),
              ),
            )
            _ <- rejected(
              c.preserveAfterRestore(
                after.copy(records =
                  after.records.map(r =>
                    if r.sequence == 1L then r.copy(previousDigest = hash(99))
                    else r,
                  ),
                ),
              ),
            )
            _ <- IO(assertEquals(before.records, Vector.empty))
          yield ()
        }
      }
    }

    scenario(
      "published JVM recovery aliases retain legacy HotStuff validity while V2 shape rejects them",
    ) {
      IO {
        import org.sigilaris.node.gossip.ChainId
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given ByteDecoder[
          Vote,
        ]
        val key        = CryptoOps.fromPrivate(BigInt(1))
        val voter      = ValidatorId.unsafe("legacy-validator")
        val validators = ValidatorSet(
          Vector(ValidatorMember(voter, key.publicKey)),
        ).toOption.get
        val window = HotStuffWindow.unsafe(
          ChainId.unsafe("legacy-compatibility"),
          7L,
          0L,
          validators.hash,
        )
        val original = Vote
          .sign(UnsignedVote(window, voter, ProposalId(hash(1))), key)
          .toOption
          .get
        val changed = original.copy(signature =
          original.signature.copy(v = original.signature.v + 256),
        )
        val alias   = changed.copy(voteId = Vote.recomputeId(changed))
        val encoded = ByteEncoder[Vote].encode(alias)
        val decoded = ByteDecoder[Vote].decode(encoded).toOption.get
        assert(decoded.remainder.isEmpty)
        assertEquals(decoded.value, alias)
        assertEquals(
          HotStuffValidator.validateVote(decoded.value, validators),
          Right(()),
        )
        val raw = ByteEncoder[Long].encode(
          alias.signature.v.toLong,
        ) ++ alias.signature.r.bytes ++ alias.signature.s.bytes
        assert(
          ValidatorSignature
            .validate(ValidatorSignature(Utf8("v2-validator"), raw))
            .isLeft,
        )
      }
    }

    scenario(
      "malformed low-S authority signatures return typed failure before key use and preserve controller state",
    ) {
      temporary.use { root =>
        val m = material(794)
        m.resource(root.resolve("controller")).use { controller =>
          for
            before <- accepted(controller.snapshot)
            original = core(Authorized.codec.decode(m.signing()))
            raw      = ByteEncoder[Long].encode(27L) ++ hash(5).bytes ++ hash(
              1,
            ).bytes
            malformed = core(
              Authorized.codec.encode(original.copy(signature = raw)),
            )
            _ <- IO(
              assertEquals(
                ValidatorSignature
                  .validate(ValidatorSignature(Utf8("fixture-authority"), raw)),
                Right(()),
              ),
            )
            rejected <- controller.sign(malformed).value
            _        <- IO(
              assert(
                rejected.left
                  .exists(_.code == RuntimeFailureCode.InvalidSignature),
              ),
            )
            after <- accepted(controller.snapshot)
            _     <- IO(assertEquals(after, before))
            _     <- accepted(controller.sign(m.signing()))
            audit <- accepted(controller.audit)
            _     <- IO(assertEquals(audit.observedSignatures.size, 1))
          yield ()
        }
      }
    }

    scenario(
      "active ledger loss cannot be silently overwritten by another action",
    ) {
      temporary.use { root =>
        val m = material(743)
        m.resource(root.resolve("controller")).use { c =>
          for
            _ <- accepted(c.sign(m.signing()))
            _ <- IO.blocking {
              Files.write(
                root.resolve("controller/LEDGER"),
                bytes("corrupt authoritative bytes").toArray,
                StandardOpenOption.TRUNCATE_EXISTING,
              );
              ()
            }
            _ <- rejected(c.sign(m.signing(nonce = 2L)))
            _ <- rejected(c.snapshot)
            _ <- rejected(c.recover)
          yield ()
        }
      }
    }

    scenario(
      "independently authenticated prior key history retains real pre-controller signatures and watermark",
    ) {
      temporary.use { root =>
        val original = material(744)
        val request  = original.signing(h = 12L)
        for
          material <- accepted(
            original.authentication
              .signing(request, signer, original.keys.publicKey.toBytes),
          )
          signature = signBytes(original.keys, material.canonicalPreimage)
          retained  = new Material(
            original.keys,
            ControllerInitialHistory(
              Vector(old),
              Vector(
                ControllerObservedSignature(
                  ControllerSigningIntent(request, material),
                  signature,
                ),
              ),
              Vector.empty,
              Vector.empty,
              Vector.empty,
            ),
          )
          _ <- retained.resource(root.resolve("controller")).use { c =>
            for
              _ <- rejected(
                c.prepareFence(
                  intent,
                  old,
                  FenceScope.ConsensusProfileAtOrAbove,
                  height(10L),
                ),
              )
              audit <- accepted(c.audit)
              _     <- IO(
                assertEquals(
                  audit.observedSignatures.map(_.signature),
                  Vector(signature),
                ),
              )
              _ <- IO(
                assertEquals(
                  audit.possibleSigningIntents.map(_.material.height),
                  Vector(Some(height(12L))),
                ),
              )
            yield ()
          }
        yield ()
      }
    }

    scenario(
      "same-process recovery cannot forget a completed fence when disk is replaced by a valid old snapshot",
    ) {
      temporary.use { root =>
        val m = material(745)
        m.resource(root.resolve("controller")).use { c =>
          for
            oldLedger <- IO.blocking(
              ByteVector
                .view(Files.readAllBytes(root.resolve("controller/LEDGER"))),
            )
            _ <- fence(c, FenceScope.ApplicationIssuance)
            _ <- IO.blocking(
              Using.resource(
                FileChannel.open(
                  root.resolve("controller/LEDGER"),
                  StandardOpenOption.WRITE,
                  StandardOpenOption.TRUNCATE_EXISTING,
                ),
              ) { out =>
                val buffer = ByteBuffer.wrap(oldLedger.toArray)
                while buffer.hasRemaining do
                  val _ = out.write(buffer)
                out.force(true)
              },
            )
            _ <- rejected(c.recover)
            _ <- rejected(
              c.sign(m.signing(kind = ControllerSigningKind.ApplicationEffect)),
            )
          yield ()
        }
      }
    }

    scenario(
      "independently authenticated fresh key has an atomic empty-history capture before its first target signature",
    ) {
      temporary.use { root =>
        val keys = CryptoOps.fromPrivate(BigInt(746))
        val m    = new Material(
          keys,
          ControllerInitialHistory(
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Vector.empty,
          ),
        )
        m.resource(root.resolve("controller")).use { c =>
          for
            captured <- accepted(c.withStopped { lease =>
              for
                original <- lease.snapshot
                _        <- EitherT
                  .liftF(rejected(c.sign(m.signing(context = target, h = 10L))))
              yield original
            })
            _ <- IO(
              assert(
                captured.records.isEmpty && captured.configuration.initialHistory.contexts.isEmpty,
              ),
            )
            _ <- accepted(c.sign(m.signing(context = target, h = 10L)))
            _ <- rejected(c.withStopped(_.snapshot))
          yield ()
        }
      }
    }

    scenario(
      "current authorization gates each actual key use without invalidating original historical signatures",
    ) {
      temporary.use { root =>
        for
          allowed <- Ref.of[IO, Boolean](true)
          uses    <- Ref.of[IO, Int](0)
          permission = EitherT(
            allowed.get.map(ok =>
              FixtureCheck.require(
                ok,
                RuntimeFailureCode.ProofUnavailable,
                "current independently checked readiness is unavailable",
              ),
            ),
          )
          m = new Material(
            CryptoOps.fromPrivate(BigInt(747)),
            ControllerInitialHistory(
              Vector(old),
              Vector.empty,
              Vector.empty,
              Vector.empty,
              Vector.empty,
            ),
            permission,
          )
          faults = new ControllerFaultInjector:
            def after(
                point: ControllerFaultPoint,
                sequence: Long,
                kind: ControllerEventKind,
            ): IO[Unit] =
              if point == ControllerFaultPoint.BeforeKeyUse then
                uses.update(_ + 1)
              else IO.unit
          first <- m.resource(root.resolve("controller"), faults).use { c =>
            for
              signature <- accepted(c.sign(m.signing(nonce = 1L)))
              _         <- allowed.set(false)
              same      <- accepted(c.sign(m.signing(nonce = 1L)))
              _         <- IO(assertEquals(same, signature))
              _         <- rejected(c.sign(m.signing(nonce = 2L)))
              observed  <- accepted(c.audit)
              _ <- IO(assertEquals(observed.possibleSigningIntents.size, 2))
              _ <- IO(assertEquals(observed.observedSignatures.size, 1))
            yield signature
          }
          _ <- m.resource(root.resolve("controller"), faults).use { c =>
            for
              same  <- accepted(c.sign(m.signing(nonce = 1L)))
              _     <- IO(assertEquals(same, first))
              _     <- rejected(c.sign(m.signing(nonce = 2L)))
              count <- uses.get
              _     <- IO(assertEquals(count, 1))
              _     <- allowed.set(true)
              _     <- accepted(c.sign(m.signing(nonce = 2L)))
              count <- uses.get
              _     <- IO(assertEquals(count, 2))
            yield ()
          }
        yield ()
      }
    }

    scenario(
      "successful immutable controller history reuse never accepts changed keys, records or order",
    ) {
      temporary.use { root =>
        val m = material(791)
        m.resource(root.resolve("controller")).use { controller =>
          for
            _        <- accepted(controller.sign(m.signing()))
            _        <- accepted(controller.sign(m.signing(h = 8L, nonce = 2L)))
            snapshot <- accepted(controller.snapshot)
            _        <- IO {
              assertEquals(
                ControllerSnapshot.validateHistory(snapshot),
                Right(()),
              )
              assertEquals(
                ControllerSnapshot.validateHistory(snapshot),
                Right(()),
              )
              val wrongKey = snapshot.copy(configuration =
                snapshot.configuration.copy(
                  publicKey =
                    CryptoOps.fromPrivate(BigInt(792)).publicKey.toBytes,
                ),
              )
              assert(ControllerSnapshot.validateHistory(wrongKey).isLeft)
              assert(
                ControllerSnapshot
                  .validateHistory(
                    snapshot.copy(records = snapshot.records.reverse),
                  )
                  .isLeft,
              )
              val last    = snapshot.records.last
              val changed = snapshot.copy(records =
                snapshot.records.dropRight(1) :+ last
                  .copy(payload = last.payload ++ ByteVector(0.toByte)),
              )
              assert(ControllerSnapshot.validateHistory(changed).isLeft)
              assertEquals(
                ControllerSnapshot.validateHistory(snapshot),
                Right(()),
              )
            }
          yield ()
        }
      }
    }
