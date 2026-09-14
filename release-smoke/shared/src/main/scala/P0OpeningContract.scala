import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{CryptoOps, PublicKey, Signature}
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}

/** Executable P0 policy oracle, independent of the future production V2 API.
  * The envelope below is a neutral application's signed payload, not the V2
  * protocol OpeningEnvelope codec. P1/P5 must reproduce these decisions through
  * their public production boundaries.
  */
object P0OpeningContract:
  private final case class Envelope(
      source: Utf8,
      target: Utf8,
      sourceSchema: Long,
      sourceRoot: UInt256,
      parent: UInt256,
      height: Long,
      manifest: UInt256,
      base: Long,
      deadline: Long,
      conversion: Utf8,
      reason: UInt256,
  ) derives ByteEncoder

  private final case class Signed(envelope: Envelope, signature: Signature)
  private final case class Cell(value: Long, lockEligible: Boolean)
  private final case class Access(id: String, writes: Boolean)
  private final case class Exact(id: String, expected: Long)
  private final case class LockCertificate(
      ids: Set[String],
      deadline: Long,
      target: String,
  )
  private final case class Claim(
      target: String,
      deadline: Long,
      accesses: Set[Access],
  )
  private final case class Conversion(
      next: Map[String, Cell],
      actual: Set[Access],
      claim: Claim,
  )

  private val initial = Map(
    "counter" -> Cell(4L, false),
    "read"    -> Cell(7L, false),
  )
  // Public, deliberately fixed fixture key; it has no deployment authority.
  private val authority = CryptoOps.fromPrivate(BigInt(1))
  private val stranger  = CryptoOps.fromPrivate(BigInt(2))
  private val digest    = UInt256.fromHex("ab" * 32).toOption.get
  private val envelope  = Envelope(
    Utf8("neutral-source"),
    Utf8("neutral-target"),
    1L,
    digest,
    digest,
    1L,
    digest,
    0L,
    8L,
    Utf8("increment-and-create"),
    digest,
  )
  private val footprint = Set(
    Access("counter", true),
    Access("read", false),
    Access("created", true),
  )

  private def signingHash(value: Envelope): Array[Byte] =
    val bytes = value.toBytes
    CryptoOps.keccak256(
      (
        Utf8("neutral.application.opening.reference.v1").toBytes ++
          BigNat.unsafeFromLong(bytes.size).toBytes ++ bytes
      ).toArray,
    )

  private def signed(value: Envelope): Signed =
    Signed(value, CryptoOps.sign(authority, signingHash(value)).toOption.get)

  private def convert(
      state: Map[String, Cell],
      value: Envelope,
  ): Either[String, Conversion] =
    for
      counter <- state.get("counter").toRight("counterMissing")
      read    <- state.get("read").toRight("readMissing")
      _ <- Either.cond(!state.contains("created"), (), "creationAlreadyExists")
      next = state
        .updated("counter", counter.copy(value = counter.value + 1L))
        .updated("created", Cell(read.value, false))
    yield Conversion(
      next,
      footprint,
      Claim(value.target.asString, value.deadline, footprint),
    )

  private def validate(
      submission: Signed,
      trustedAuthority: PublicKey,
      state: Map[String, Cell],
      exact: Vector[Exact],
      certificate: Option[LockCertificate],
      declared: Set[Access],
      bootstrap: Boolean,
      baseAuthenticated: Boolean,
      actualHeight: Long,
  ): Either[String, Conversion] =
    val value = submission.envelope
    for
      _ <- Either.cond(
        CryptoOps
          .recover(submission.signature, signingHash(value))
          .toOption
          .contains(trustedAuthority),
        (),
        "unauthorized",
      )
      _ <- Either.cond(
        value.source == envelope.source && value.target == envelope.target &&
          value.sourceRoot == envelope.sourceRoot && value.sourceSchema == envelope.sourceSchema &&
          value.parent == envelope.parent && value.manifest == envelope.manifest &&
          value.height == actualHeight && value.conversion == envelope.conversion && value.reason == envelope.reason,
        (),
        "openingBindingMismatch",
      )
      _ <- Either.cond(baseAuthenticated, (), "baseUnauthenticated")
      _ <- Either.cond(
        !bootstrap || (value.base == 0L && actualHeight == 1L),
        (),
        "bootstrapBoundaryMismatch",
      )
      // BigInt models checked arithmetic independently of implementation types.
      _ <- Either.cond(
        value.base >= 0L && actualHeight > value.base && value.deadline > value.base &&
          BigInt(value.deadline) <= BigInt(
            value.base,
          ) + 64 && actualHeight <= value.deadline,
        (),
        "deadlineInvalid",
      )
      _ <- Either.cond(
        exact.map(_.id).distinct.size == exact.size,
        (),
        "duplicateExact",
      )
      _ <- Either.cond(
        exact.forall(e => state.get(e.id).exists(_.value == e.expected)),
        (),
        "staleExact",
      )
      result <- convert(state, value)
      eligible = result.actual
        .filter(a => a.writes && state.get(a.id).exists(_.lockEligible))
        .map(_.id)
      _ <- Either.cond(
        eligible.subsetOf(exact.map(_.id).toSet),
        (),
        "undeclaredEligibleMutation",
      )
      _ <- Either.cond(
        !bootstrap || eligible.isEmpty,
        (),
        "bootstrapLockUnsupported",
      )
      _ <- Either.cond(
        if eligible.isEmpty then certificate.isEmpty
        else
          certificate.contains(
            LockCertificate(eligible, value.deadline, value.target.asString),
          )
        ,
        (),
        "lockCertificateMismatch",
      )
      _ <- Either.cond(
        result.actual.subsetOf(declared),
        (),
        "actualAccessUncovered",
      )
      _ <- Either.cond(
        result.claim.target == value.target.asString && result.claim.deadline == value.deadline,
        (),
        "reservationBindingMismatch",
      )
    yield result

  def main(args: Array[String]): Unit =
    val submission = signed(envelope)
    def check(
        state: Map[String, Cell] = initial,
        exact: Vector[Exact] = Vector.empty,
        certificate: Option[LockCertificate] = None,
        declared: Set[Access] = footprint,
        bootstrap: Boolean = true,
        authenticated: Boolean = true,
        value: Signed = submission,
        trusted: PublicKey = authority.publicKey,
        at: Long = 1L,
    ): Either[String, Conversion] = validate(
      value,
      trusted,
      state,
      exact,
      certificate,
      declared,
      bootstrap,
      authenticated,
      at,
    )

    val converted = check().toOption.get
    assert(converted.next("counter").value == 5L)
    assert(converted.next("created").value == 7L)
    assert(converted.claim == Claim("neutral-target", 8L, footprint))
    assert(check() == check())
    assert(check(trusted = stranger.publicKey) == Left("unauthorized"))
    assert(
      check(value =
        submission.copy(envelope = envelope.copy(deadline = 9L)),
      ) == Left("unauthorized"),
    )
    assert(
      check(value = signed(envelope.copy(deadline = 0L))) == Left(
        "deadlineInvalid",
      ),
    )
    assert(
      check(value = signed(envelope.copy(deadline = 65L))) == Left(
        "deadlineInvalid",
      ),
    )
    assert(check(authenticated = false) == Left("baseUnauthenticated"))
    assert(
      check(value = signed(envelope.copy(height = 2L)), at = 2L) == Left(
        "bootstrapBoundaryMismatch",
      ),
    )
    assert(
      check(declared = footprint - Access("created", true)) == Left(
        "actualAccessUncovered",
      ),
    )
    assert(
      check(declared = footprint - Access("read", false)) == Left(
        "actualAccessUncovered",
      ),
    )
    assert(
      check(declared =
        footprint - Access("counter", true) + Access("counter", false),
      ) == Left("actualAccessUncovered"),
    )
    val eligible = initial.updated("counter", Cell(4L, true))
    val exact    = Vector(Exact("counter", 4L))
    val cert     = Some(LockCertificate(Set("counter"), 8L, "neutral-target"))
    assert(check(state = eligible) == Left("undeclaredEligibleMutation"))
    assert(
      check(state = eligible, exact = exact) == Left("bootstrapLockUnsupported"),
    )
    assert(
      check(state = eligible, exact = exact, certificate = cert) == Left(
        "bootstrapLockUnsupported",
      ),
    )
    assert(check(certificate = cert) == Left("lockCertificateMismatch"))
    assert(
      check(state = eligible, exact = exact, bootstrap = false) == Left(
        "lockCertificateMismatch",
      ),
    )
    assert(
      check(
        state = eligible,
        exact = exact,
        certificate = cert,
        bootstrap = false,
      ).isRight,
    )
    assert(
      check(
        state = eligible,
        exact = Vector(Exact("counter", 3L)),
        certificate = cert,
        bootstrap = false,
      ) == Left("staleExact"),
    )
    assert(
      check(
        state = eligible,
        exact = exact,
        certificate = cert.map(_.copy(deadline = 9L)),
        bootstrap = false,
      ) == Left("lockCertificateMismatch"),
    )
    val inclusive = signed(envelope.copy(height = 8L))
    assert(
      check(
        value = signed(envelope.copy(height = 0L)),
        at = 0L,
        bootstrap = false,
      ) == Left("deadlineInvalid"),
    )
    assert(
      check(
        value = signed(envelope.copy(height = -1L)),
        at = -1L,
        bootstrap = false,
      ) == Left("deadlineInvalid"),
    )
    assert(check(value = inclusive, at = 8L, bootstrap = false).isRight)
    assert(
      check(
        value = signed(envelope.copy(height = 9L)),
        at = 9L,
        bootstrap = false,
      ) == Left("deadlineInvalid"),
    )
    def mayExpire(
        claim: Claim,
        domain: String,
        finalized: Long,
        nonapplication: Boolean,
    ): Boolean =
      claim.target == domain && finalized > claim.deadline && nonapplication
    assert(!mayExpire(converted.claim, "neutral-target", 8L, true))
    assert(!mayExpire(converted.claim, "neutral-source", 100L, true))
    assert(!mayExpire(converted.claim, "neutral-target", 9L, false))
    assert(mayExpire(converted.claim, "neutral-target", 9L, true))
    println(
      "P0 opening policy oracle PASS: real authority signature, signed deadline, complete accesses, bootstrap/handover locks, inclusive application and same-domain expiry",
    )
    println(
      "Reference contract evidence only; production V2 runtime and activation gates remain pending",
    )
