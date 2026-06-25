package org.sigilaris.node.txpipeline

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.SizeIs",
  ),
)
object TxPipelineRequestNormalizer:

  def normalize(
      request: TxPipelineSubmitRequest,
      limits: TxPipelineShapeLimits,
  ): Either[TxPipelineValidationFailure, TxPipelineNormalizedRequest] =
    for
      _ <- validateShape(request, limits)
    yield
      val stages = request.stages.zipWithIndex.map:
        case (stage, stageIndex) =>
          TxPipelineNormalizedStage(
            stageIndex = stageIndex,
            transactions = stage.zipWithIndex.map:
              case (payload, transactionIndex) =>
                TxPipelineSubmittedTransaction(
                  stageIndex = stageIndex,
                  transactionIndex = transactionIndex,
                  payload = payload,
                ),
          )
      TxPipelineNormalizedRequest(stages = stages, waitFor = request.waitFor)

  def oneStage(
      payload: TxPipelineTransactionPayload,
      waitFor: TxPipelineWaitMode,
      limits: TxPipelineShapeLimits,
  ): Either[TxPipelineValidationFailure, TxPipelineNormalizedRequest] =
    normalize(TxPipelineSubmitRequest.oneStage(payload, waitFor), limits)

  def validateHashShape(
      normalized: TxPipelineNormalizedRequest,
      txHashes: Vector[Vector[TxPipelineTxHash]],
  ): Either[TxPipelineValidationFailure, Unit] =
    if txHashes.size != normalized.stages.size then
      Left:
        TxPipelineValidationFailure(
          "txHashStageShapeMismatch",
          s"expected ${normalized.stages.size} hash stages, got ${txHashes.size}",
        )
    else
      val mismatch = normalized.stages
        .zip(txHashes)
        .collectFirst:
          case (stage, hashes) if hashes.size != stage.transactions.size =>
            TxPipelineValidationFailure.atStage(
              "txHashTransactionShapeMismatch",
              s"expected ${stage.transactions.size} transaction hashes, got ${hashes.size}",
              stage.stageIndex,
            )
      mismatch match
        case Some(failure) => Left(failure)
        case None          => Right(())

  def validateUniqueHashes(
      txHashes: Vector[Vector[TxPipelineTxHash]],
  ): Either[TxPipelineValidationFailure, Unit] =
    val flattened = txHashes.zipWithIndex.flatMap:
      case (stageHashes, stageIndex) =>
        stageHashes.zipWithIndex.map:
          case (txHash, transactionIndex) =>
            (txHash, stageIndex, transactionIndex)

    val duplicate = flattened
      .groupBy(_._1)
      .collectFirst:
        case (hash, duplicates) if duplicates.size > 1 =>
          val firstDuplicate = duplicates(1)
          TxPipelineValidationFailure.atTransaction(
            "duplicateTransactionHash",
            s"duplicate transaction hash ${hash.value}",
            firstDuplicate._2,
            firstDuplicate._3,
          )
    duplicate match
      case Some(failure) => Left(failure)
      case None          => Right(())

  private def validateShape(
      request: TxPipelineSubmitRequest,
      limits: TxPipelineShapeLimits,
  ): Either[TxPipelineValidationFailure, Unit] =
    if request.stages.isEmpty then
      Left(TxPipelineValidationFailure("emptyStages", "stages must be non-empty"))
    else if request.stages.size > limits.maxStages then
      Left:
        TxPipelineValidationFailure(
          "tooManyStages",
          s"stage count ${request.stages.size} exceeds limit ${limits.maxStages}",
        )
    else
      request.stages.zipWithIndex.collectFirst:
        case (stage, stageIndex) if stage.isEmpty =>
          TxPipelineValidationFailure.atStage(
            "emptyStage",
            "stage must contain at least one transaction",
            stageIndex,
          )
        case (stage, stageIndex)
            if stage.size > limits.maxTransactionsPerStage =>
          TxPipelineValidationFailure.atStage(
            "tooManyTransactionsInStage",
            s"stage transaction count ${stage.size} exceeds limit ${limits.maxTransactionsPerStage}",
            stageIndex,
          )
      match
        case Some(failure) => Left(failure)
        case None         => validateTransactionTotals(request, limits)

  private def validateTransactionTotals(
      request: TxPipelineSubmitRequest,
      limits: TxPipelineShapeLimits,
  ): Either[TxPipelineValidationFailure, Unit] =
    val totalTransactions = request.stages.map(_.size).sum
    if totalTransactions > limits.maxTotalTransactions then
      Left:
        TxPipelineValidationFailure(
          "tooManyTransactions",
          s"transaction count $totalTransactions exceeds limit ${limits.maxTotalTransactions}",
        )
    else validatePayloadSizes(request, limits)

  private def validatePayloadSizes(
      request: TxPipelineSubmitRequest,
      limits: TxPipelineShapeLimits,
  ): Either[TxPipelineValidationFailure, Unit] =
    val indexedPayloads = request.stages.zipWithIndex.flatMap:
      case (stage, stageIndex) =>
        stage.zipWithIndex.map:
          case (payload, transactionIndex) =>
            (payload, stageIndex, transactionIndex)

    indexedPayloads.collectFirst:
      case (payload, stageIndex, transactionIndex)
          if payload.utf8ByteSize > limits.maxTransactionPayloadBytes =>
        TxPipelineValidationFailure.atTransaction(
          "transactionPayloadTooLarge",
          s"transaction payload bytes ${payload.utf8ByteSize} exceeds limit ${limits.maxTransactionPayloadBytes}",
          stageIndex,
          transactionIndex,
        )
    match
      case Some(failure) => Left(failure)
      case None =>
        val totalPayloadBytes = indexedPayloads.map(_._1.utf8ByteSize).sum
        Either.cond(
          totalPayloadBytes <= limits.maxTotalPayloadBytes,
          (),
          TxPipelineValidationFailure(
            "totalPayloadTooLarge",
            s"total payload bytes $totalPayloadBytes exceeds limit ${limits.maxTotalPayloadBytes}",
          ),
        )
