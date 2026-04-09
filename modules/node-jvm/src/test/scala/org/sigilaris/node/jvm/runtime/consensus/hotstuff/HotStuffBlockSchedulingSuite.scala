package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import munit.FunSuite
import scodec.bits.ByteVector

import org.sigilaris.core.application.scheduling.{
  CompatibilityReason,
  ConflictFootprint,
  ConflictKind,
  SchedulingClassification,
  StateRef,
}
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.{CryptoOps, Hash}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockHeader,
  BlockHeight,
  BlockRecord,
  BlockTimestamp,
  BlockView,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.gossip.ChainId

final class HotStuffBlockSchedulingSuite extends FunSuite:
  private val chainId       = ChainId.unsafe("chain-main")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )

  private final case class TestTxRef(
      id: Utf8,
  ) derives ByteEncoder

  private given Hash[TestTxRef] = Hash.build

  private type TestRecord = BlockRecord[TestTxRef, Utf8, Utf8]

  test(
    "ConflictFreeBlockBodySelector keeps only schedulable conflict-free records",
  ):
    val recordA = record("a")
    val recordB = record("b")
    val recordC = record("c")
    val recordD = record("d")
    val classification = Map(
      recordA.tx -> schedulable(writes = Set("alpha")),
      recordB.tx -> compatibility("dynamicDiscovery"),
      recordC.tx -> schedulable(reads = Set("alpha")),
      recordD.tx -> schedulable(writes = Set("delta")),
    )

    val selection =
      ConflictFreeBlockBodySelector.select(
        Vector(recordA, recordB, recordC, recordD),
      ): tx =>
        classification(tx)

    assertEquals(selection.accepted.map(_.tx.id.asString), Vector("a", "d"))
    assertEquals(
      selection.rejected.map(_.record.tx.id.asString),
      Vector("b", "c"),
    )
    assertEquals(
      selection.rejected.map(_.reason),
      Vector(
        BlockRecordRejectionReason.Compatibility(
          CompatibilityReason("dynamicDiscovery", None),
        ),
        BlockRecordRejectionReason.Conflict(
          stateRef = stateRef("alpha"),
          kind = ConflictKind.ReadWrite,
        ),
      ),
    )
    assertEquals(selection.toBody.map(_.records.size), Right(2))

  test(
    "ConflictFreeBlockBodySelector rejects write-write conflicts explicitly",
  ):
    val first  = record("first")
    val second = record("second")
    val classification = Map(
      first.tx  -> schedulable(writes = Set("shared")),
      second.tx -> schedulable(writes = Set("shared")),
    )

    val selection =
      ConflictFreeBlockBodySelector.select(Vector(first, second)): tx =>
        classification(tx)

    assertEquals(selection.accepted.map(_.tx.id.asString), Vector("first"))
    assertEquals(
      selection.rejected.map(_.reason),
      Vector(
        BlockRecordRejectionReason.Conflict(
          stateRef = stateRef("shared"),
          kind = ConflictKind.WriteWrite,
        ),
      ),
    )

  test(
    "ConflictFreeBlockBodySelection rejects duplicate selected records when materializing a body",
  ):
    val duplicate = record("duplicate")
    val selection =
      ConflictFreeBlockBodySelection(
        accepted = Vector(duplicate, duplicate),
        rejected = Vector.empty[RejectedBlockRecord[TestTxRef, Utf8, Utf8]],
        aggregate =
          org.sigilaris.core.application.scheduling.AggregateFootprint.empty,
      )

    assertEquals(
      selection.toBody.left.map(_.reason),
      Left("duplicateSelectedBlockRecord"),
    )

  test(
    "HotStuffBlockBodyVerifier accepts a conflict-free body and canonicalizes set order",
  ):
    val recordA = record("a")
    val recordB = record("b")
    val body    = BlockBody(Set(recordB, recordA))
    val view    = blockView(body, rootHex = "51")
    val classification = Map(
      recordA.tx -> schedulable(reads = Set("shared")),
      recordB.tx -> schedulable(writes = Set("owned")),
    )

    assertEquals(
      HotStuffBlockBodyVerifier.validateView(view)(tx => classification(tx)),
      Right(()),
    )

  test(
    "HotStuffBlockBodyVerifier rejects compatibility transactions in a block body",
  ):
    val compatRecord = record("compat")
    val body         = BlockBody(Set(compatRecord))

    val result =
      HotStuffBlockBodyVerifier.validateBody(body): _ =>
        compatibility("automaticInputSelection", Some("missing explicit refs"))

    assertEquals(
      result.left.map(_.reason),
      Left("compatibilityTransactionInBlockBody"),
    )
    assert(
      result.left.toOption
        .flatMap(_.detail)
        .exists(_.contains("automaticInputSelection")),
    )

  test("HotStuffBlockBodyVerifier accepts the empty block body"):
    val emptyBody = BlockBody(Set.empty[TestRecord])

    assertEquals(
      HotStuffBlockBodyVerifier.validateBody(emptyBody)(_ =>
        compatibility("unreachable"),
      ),
      Right(()),
    )

  test("HotStuffBlockBodyVerifier rejects conflicting block bodies"):
    val writer = record("writer")
    val reader = record("reader")
    val body   = BlockBody(Set(writer, reader))
    val classification = Map(
      writer.tx -> schedulable(writes = Set("shared")),
      reader.tx -> schedulable(reads = Set("shared")),
    )

    val result =
      HotStuffBlockBodyVerifier.validateBody(body): tx =>
        classification(tx)

    assertEquals(
      result.left.map(_.reason),
      Left("conflictingBlockBodyTransaction"),
    )
    assert(
      result.left.toOption
        .flatMap(_.detail)
        .exists(_.contains(stateRef("shared").toHexLower)),
    )

  test(
    "HotStuffBlockBodyVerifier is ordering-independent for equivalent conflicting bodies",
  ):
    val writer = record("writer")
    val reader = record("reader")
    val bodyA  = BlockBody(Set(writer, reader))
    val bodyB  = BlockBody(Set(reader, writer))
    val classification = Map(
      writer.tx -> schedulable(writes = Set("shared")),
      reader.tx -> schedulable(reads = Set("shared")),
    )

    val resultA =
      HotStuffBlockBodyVerifier.validateBody(bodyA): tx =>
        classification(tx)
    val resultB =
      HotStuffBlockBodyVerifier.validateBody(bodyB): tx =>
        classification(tx)

    assertEquals(resultA, resultB)

  test(
    "HotStuffProposalViewValidator rejects conflicting bodies before proposal acceptance",
  ):
    val writer   = record("writer")
    val reader   = record("reader")
    val body     = BlockBody(Set(writer, reader))
    val view     = blockView(body, rootHex = "61")
    val proposal = signedProposal(view.header, body)
    val classification = Map(
      writer.tx -> schedulable(writes = Set("shared")),
      reader.tx -> schedulable(reads = Set("shared")),
    )

    val result =
      HotStuffProposalViewValidator.validateProposalView(
        proposal,
        view,
        validatorSet,
      ): tx =>
        classification(tx)

    assertEquals(
      result.left.map(_.reason),
      Left("conflictingBlockBodyTransaction"),
    )

  test(
    "HotStuffProposalViewValidator rejects proposal and block-view header mismatches",
  ):
    val body = BlockBody(Set(record("ok")))
    val proposalHeader =
      blockHeader(body, rootHex = "71", timestampMillis = 1_712_345_678_000L)
    val mismatchedView =
      BlockView(
        header = blockHeader(
          body,
          rootHex = "71",
          timestampMillis = 1_712_345_679_000L,
        ),
        body = body,
      )
    val proposal = signedProposal(proposalHeader, body)

    val result =
      HotStuffProposalViewValidator.validateProposalView(
        proposal,
        mismatchedView,
        validatorSet,
      ): _ =>
        schedulable(writes = Set("ok"))

    assertEquals(result.left.map(_.reason), Left("proposalBlockViewMismatch"))

  test(
    "HotStuffProposalViewValidator rejects proposal tx-set mismatches before body validation",
  ):
    val body     = BlockBody(Set(record("only")))
    val view     = blockView(body, rootHex = "72")
    val proposal = signedProposal(view.header, body, Some(ProposalTxSet.empty))

    val result =
      HotStuffProposalViewValidator.validateProposalView(
        proposal,
        view,
        validatorSet,
      ): _ =>
        schedulable(writes = Set("only"))

    assertEquals(result.left.map(_.reason), Left("proposalTxSetMismatch"))

  private def record(
      id: String,
  ): TestRecord =
    BlockRecord(
      tx = TestTxRef(Utf8(id)),
      result = None,
      events = Vector.empty[Utf8],
    )

  private def schedulable(
      reads: Set[String] = Set.empty[String],
      writes: Set[String] = Set.empty[String],
  ): SchedulingClassification =
    SchedulingClassification.Schedulable(
      ConflictFootprint(
        reads = reads.map(stateRef),
        writes = writes.map(stateRef),
      ),
    )

  private def compatibility(
      reason: String,
      detail: Option[String] = None,
  ): SchedulingClassification =
    SchedulingClassification.Compatibility(
      CompatibilityReason(reason, detail),
    )

  private def stateRef(
      label: String,
  ): StateRef =
    StateRef.fromBytes(ByteVector.encodeUtf8(label).toOption.get)

  private def blockView(
      body: BlockBody[TestTxRef, Utf8, Utf8],
      rootHex: String,
  ): BlockView[TestTxRef, Utf8, Utf8] =
    BlockView(
      header = blockHeader(body, rootHex, timestampMillis = 1_712_345_678_000L),
      body = body,
    )

  private def blockHeader(
      body: BlockBody[TestTxRef, Utf8, Utf8],
      rootHex: String,
      timestampMillis: Long,
  ): BlockHeader =
    val parent = parentHeader()
    BlockHeader(
      parent = Some(BlockHeader.computeId(parent)),
      height = BlockHeight.unsafeFromLong(1),
      stateRoot = StateRoot(hex(rootHex)),
      bodyRoot = BlockBody.computeBodyRoot(body).toOption.get,
      timestamp = BlockTimestamp.unsafeFromEpochMillis(timestampMillis),
    )

  private def signedProposal(
      block: BlockHeader,
      body: BlockBody[TestTxRef, Utf8, Utf8],
      txSet: Option[ProposalTxSet] = None,
  ): Proposal =
    val parentBlock      = parentHeader()
    val parentWindow     = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("10"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = BlockHeader.computeId(parentBlock),
    )
    val justify = QuorumCertificateAssembler
      .assemble(
        subject = subject,
        votes = Vector(
          signedVoteFor(parentWindow, parentProposalId, 0),
          signedVoteFor(parentWindow, parentProposalId, 1),
          signedVoteFor(parentWindow, parentProposalId, 2),
        ),
        validatorSet = validatorSet,
      )
      .toOption
      .get

    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, 1L, 1L, validatorSet.hash),
          proposer = validatorSet.members.head.id,
          targetBlockId = BlockHeader.computeId(block),
          block = block,
          txSet = txSet.getOrElse(
            ProposalTxSet.fromTxs(body.records.toVector.map(_.tx)),
          ),
          justify = justify,
        ),
        validatorKeys.head,
      )
      .toOption
      .get

  private def signedVoteFor(
      window: HotStuffWindow,
      proposalId: ProposalId,
      voterIndex: Int,
  ): Vote =
    Vote
      .sign(
        UnsignedVote(
          window = window,
          voter = validatorSet.members(voterIndex).id,
          targetProposalId = proposalId,
        ),
        validatorKeys(voterIndex),
      )
      .toOption
      .get

  private def parentHeader(): BlockHeader =
    BlockHeader(
      parent = None,
      height = BlockHeight.unsafeFromLong(0),
      stateRoot = StateRoot(hex("01")),
      bodyRoot = BodyRoot(hex("01")),
      timestamp = BlockTimestamp.unsafeFromEpochMillis(1_712_345_677_000L),
    )

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
