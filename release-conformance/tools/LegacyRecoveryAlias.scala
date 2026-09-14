import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.gossip.ChainId
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given ByteDecoder[Vote]

/** Standalone probe using only published M1/M2 public APIs. Run with the chosen
  * sigilaris-node-jvm_3 Maven dependency; no repository module output is
  * needed.
  */
@main def legacyRecoveryAlias(): Unit =
  val key            = CryptoOps.fromPrivate(BigInt(1))
  val hash           = UInt256.unsafeFromBigIntUnsigned(BigInt(3)).bytes.toArray
  val signature      = CryptoOps.sign(key, hash).toOption.get
  val signatureAlias = signature.copy(v = signature.v + 256)
  assert(signature.v == 27 && signatureAlias.v == 283)
  assert(CryptoOps.recover(signatureAlias, hash).contains(key.publicKey))

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
    .sign(
      UnsignedVote(
        window,
        voter,
        ProposalId(UInt256.unsafeFromBigIntUnsigned(BigInt(1))),
      ),
      key,
    )
    .toOption
    .get
  val changed = original.copy(signature =
    original.signature.copy(v = original.signature.v + 256),
  )
  val alias   = changed.copy(voteId = Vote.recomputeId(changed))
  val decoded =
    ByteDecoder[Vote].decode(ByteEncoder[Vote].encode(alias)).toOption.get
  assert(decoded.remainder.isEmpty && decoded.value == alias)
  assert(HotStuffValidator.validateVote(decoded.value, validators).isRight)
  println(
    s"Published recovery alias PASS: core 27->283; actual HotStuff ${original.signature.v}->${alias.signature.v}, codec roundtrip and vote id recomputation",
  )
