package org.sigilaris.node.txpipeline.v2

import org.sigilaris.core.application.protocol.v2.*

/** Decodes the explicitly canonical schema-3 payload behind the core journal's
  * dependency-neutral Bytes fields. These checks grant no signing or evidence
  * authority; the installed runtime must authenticate the decoded records.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ExactJournalRecords:
  def registration(
      record: ExactPipelineRecord,
  ): Either[CoreFailure, ExactRegistration] =
    for
      _ <- ExactPipelineRecord.validate(record)
      _ <- ExactValidation.check(
        record.admissionJournalSequence.nonEmpty && record.stages.forall(
          _.lifecycle == ExactStageLifecycle.Accepted,
        ),
        "exactRegistration.initialAdmission",
      )
      bytes   <- ExactPipelineRecord.codec.encode(record)
      binding <- ExactIdentityBinding.digest(record.binding)
      request <- ExactSubmitRequest.digest(record.request)
      value = ExactRegistration(
        2L,
        record.binding.context,
        record.binding.nodePipelineId,
        bytes,
        binding,
        request,
        record.request.signedPlan.intent.lastInclusionHeight,
      )
      _ <- ExactRegistration.validate(value)
    yield value

  def decodeRegistration(
      value: ExactRegistration,
      journalSequence: Long,
  ): Either[CoreFailure, ExactPipelineRecord] =
    for
      _      <- ExactRegistration.validate(value)
      record <- ExactPipelineRecord.codec.decode(value.canonicalRecord)
      _      <- ExactValidation.check(
        journalSequence > 0L && record.admissionJournalSequence.contains(
          journalSequence,
        ),
        "exactRegistration.journalSequence",
      )
      expected <- registration(record)
      _        <- ExactValidation.check(
        value == expected,
        "exactRegistration.recordBinding",
      )
    yield record

  def update(
      previous: ExactPipelineRecord,
      next: ExactPipelineRecord,
      evidenceDigest: Hash,
  ): Either[CoreFailure, ExactRecordUpdate] =
    for
      _ <- ExactPipelineRecord.validateUpdate(previous, next)
      _ <- ExactValidation.check(
        previous.admissionJournalSequence.nonEmpty && next.admissionJournalSequence == previous.admissionJournalSequence,
        "exactUpdate.registeredAdmission",
      )
      prior   <- ExactPipelineRecord.digest(previous)
      binding <- ExactIdentityBinding.digest(previous.binding)
      bytes   <- ExactPipelineRecord.codec.encode(next)
      value = ExactRecordUpdate(
        2L,
        next.binding.context,
        next.binding.nodePipelineId,
        binding,
        prior,
        bytes,
        evidenceDigest,
      )
      _ <- ExactRecordUpdate.validate(value)
    yield value

  def decodeUpdate(
      previous: ExactPipelineRecord,
      value: ExactRecordUpdate,
  ): Either[CoreFailure, ExactPipelineRecord] =
    for
      _        <- ExactRecordUpdate.validate(value)
      next     <- ExactPipelineRecord.codec.decode(value.canonicalNextRecord)
      expected <- update(previous, next, value.evidenceDigest)
      _        <- ExactValidation.check(
        value == expected,
        "exactUpdate.priorAndNextBinding",
      )
    yield next
