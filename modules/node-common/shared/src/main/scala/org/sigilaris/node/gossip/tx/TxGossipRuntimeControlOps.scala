package org.sigilaris.node.gossip.tx

import java.time.{Duration, Instant}

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.gossip.*

private[tx] trait TxGossipRuntimeControlOps[F[_]: Sync, A]
    extends TxGossipRuntimeSharedOps[F, A]:

  protected final def prevalidateControlBatch(
      state: TxGossipRuntimeState,
      sessionId: DirectionalSessionId,
      batch: ControlBatch,
  ): F[Either[CanonicalRejection.ControlBatchRejected, Unit]] =
    openOutboundProducerSession(state, sessionId).fold(
      rejection => rejection.asLeft[Unit].pure[F],
      (_, _) =>
        batch.ops.foldLeftM(
          ().asRight[CanonicalRejection.ControlBatchRejected],
        ):
          case (Left(rejection), _) =>
            rejection.asLeft[Unit].pure[F]
          case (Right(_), ControlOp.RequestByIdTx(chainId, ids)) =>
            val distinctIds = ids.distinct
            if distinctIds.sizeIs > policy.maxTxRequestIds then
              controlRejected(
                "requestByIdTooLarge",
                ss"max=${policy.maxTxRequestIds.toString} actual=${distinctIds.size.toString}",
              ).asLeft[Unit].pure[F]
            else
              source
                .readByIds(chainId, GossipTopic.tx, distinctIds)
                .map: events =>
                  val foundIds = events.map(_.event.id).toSet
                  val missing  = distinctIds.filterNot(foundIds.contains)
                  Either.cond(
                    missing.isEmpty,
                    (),
                    controlRejected(
                      "unknownRequestedArtifact",
                      missing.map(_.toHexLower).mkString(","),
                    ),
                  )
          case (Right(_), ControlOp.RequestByIdExact(scope, ids)) =>
            openExactKnownContract(scope.topic) match
              case Left(rejection) =>
                rejection.asLeft[Unit].pure[F]
              case Right(contract) =>
                val distinctIds = ids.distinct
                contract.requestByIdLimit match
                  case Some(limit) if distinctIds.sizeIs > limit =>
                    controlRejected(
                      "requestByIdTooLarge",
                      ss"max=${limit.toString} actual=${distinctIds.size.toString}",
                    ).asLeft[Unit].pure[F]
                  case Some(_) =>
                    source
                      .readByIds(scope.chainId, scope.topic, distinctIds)
                      .map: events =>
                        val (wrongWindow, foundIds) =
                          events.foldLeft(
                            (
                              Vector.empty[StableArtifactId],
                              Set.empty[StableArtifactId],
                            ),
                          ):
                            case ((wrongWindowAcc, foundAcc), available) =>
                              contract.exactKnownScopeOf(available.event) match
                                case Right(Some(eventScope))
                                    if eventScope === scope =>
                                  wrongWindowAcc -> (foundAcc + available.event.id)
                                case Right(Some(_)) =>
                                  (wrongWindowAcc :+ available.event.id) -> foundAcc
                                case Right(None) =>
                                  (wrongWindowAcc :+ available.event.id) -> foundAcc
                                case Left(_) =>
                                  (wrongWindowAcc :+ available.event.id) -> foundAcc
                        val missing = distinctIds.filterNot(foundIds.contains)
                        if wrongWindow.nonEmpty then
                          controlRejected(
                            "wrongWindowKey",
                            wrongWindow.map(_.toHexLower).mkString(","),
                          ).asLeft[Unit]
                        else
                          Either.cond(
                            missing.isEmpty,
                            (),
                            controlRejected(
                              "unknownRequestedArtifact",
                              missing.map(_.toHexLower).mkString(","),
                            ),
                          )
                  case None =>
                    controlRejected("unsupportedTopic", scope.topic.value)
                      .asLeft[Unit]
                      .pure[F]
          case (Right(_), _) =>
            ().asRight[CanonicalRejection.ControlBatchRejected].pure[F],
    )

  protected final def applyControlBatch(
      now: Instant,
      batch: ControlBatch,
      sessionState: TxProducerSessionState,
  ): Either[
    CanonicalRejection.ControlBatchRejected,
    (TxProducerSessionState, ControlBatchOutcome),
  ] =
    val prunedKeys            = pruneIdempotencyKeys(now, sessionState)
    val sessionWithPrunedKeys = sessionState.copy(idempotencyKeys = prunedKeys)
    if prunedKeys.contains(batch.idempotencyKey) then
      (sessionWithPrunedKeys -> ControlBatchOutcome.Deduplicated)
        .asRight[CanonicalRejection.ControlBatchRejected]
    else
      batch.ops
        .foldLeft[Either[
          CanonicalRejection.ControlBatchRejected,
          TxProducerSessionState,
        ]](
          sessionWithPrunedKeys.asRight[CanonicalRejection.ControlBatchRejected],
        ):
          case (Right(current), op) =>
            applyControlOp(current, op)
          case (left, _) =>
            left
        .map: updated =>
          updated.copy(
            idempotencyKeys =
              updated.idempotencyKeys.updated(batch.idempotencyKey, now),
          ) -> ControlBatchOutcome.Applied

  protected final def applyControlOp(
      sessionState: TxProducerSessionState,
      op: ControlOp,
  ): Either[CanonicalRejection.ControlBatchRejected, TxProducerSessionState] =
    op match
      case ControlOp.SetFilter(chainId, topic, filter) =>
        validateTxSubscription(sessionState, chainId, topic).flatMap: _ =>
          filter match
            case bloomFilter: GossipFilter.TxBloomFilter =>
              TxBloomFilterSupport
                .validate(bloomFilter, policy)
                .map: validated =>
                  sessionState.copy(filters =
                    sessionState.filters.updated(chainId, validated),
                  )

      case ControlOp.SetKnownTx(chainId, ids) =>
        validateTxSubscription(sessionState, chainId, GossipTopic.tx).flatMap:
          _ =>
            val distinctNewIds = ids.toSet
            val mergedIds = sessionState.exactKnownIds.getOrElse(
              chainId,
              Set.empty[StableArtifactId],
            ) ++ distinctNewIds
            Either.cond(
              distinctNewIds.sizeIs <= policy.maxTxSetKnownEntries &&
                mergedIds.sizeIs <= policy.maxTxSetKnownEntries,
              sessionState.copy(
                exactKnownIds = sessionState.exactKnownIds.updated(
                  chainId,
                  mergedIds,
                ),
              ),
              controlRejected(
                "setKnownTooLarge",
                ss"max=${policy.maxTxSetKnownEntries.toString} actual=${mergedIds.size.toString}",
              ),
            )

      case ControlOp.SetKnownExact(scope, ids) =>
        validateExactKnownSubscription(sessionState, scope).flatMap: contract =>
          val distinctNewIds = ids.toSet
          val existing =
            sessionState.exactKnownScopeIds.getOrElse(
              scope,
              Set.empty[StableArtifactId],
            )
          val mergedIds = existing ++ distinctNewIds
          contract.exactKnownSetLimit match
            case Some(limit) =>
              Either.cond(
                distinctNewIds.sizeIs <= limit && mergedIds.sizeIs <= limit,
                sessionState.copy(
                  exactKnownScopeIds =
                    sessionState.exactKnownScopeIds.updated(scope, mergedIds),
                ),
                controlRejected(
                  "setKnownTooLarge",
                  ss"max=${limit.toString} actual=${mergedIds.size.toString}",
                ),
              )
            case None =>
              controlRejected("unsupportedTopic", scope.topic.value)
                .asLeft[TxProducerSessionState]

      case ControlOp.SetCursor(cursor) =>
        val unsupportedKeys =
          cursor.values.keySet.filterNot(sessionState.subscriptions.contains)
        Either.cond(
          unsupportedKeys.isEmpty,
          sessionState.withProducerState(
            sessionState.producerState.withDurableCursor(cursor),
          ),
          controlRejected(
            "cursorOutOfSubscription",
            unsupportedKeys
              .map(key => ss"${key.chainId.value}:${key.topic.value}")
              .mkString(","),
          ),
        )

      case ControlOp.Nack(chainId, topic, cursor) =>
        validateSubscription(sessionState, chainId, topic).map: _ =>
          sessionState.withProducerState(
            sessionState.producerState
              .withReplay(ChainTopic(chainId, topic), cursor),
          )

      case ControlOp.RequestByIdTx(chainId, ids) =>
        validateTxSubscription(sessionState, chainId, GossipTopic.tx).flatMap:
          _ =>
            val distinctIds = ids.distinct
            Either.cond(
              distinctIds.sizeIs <= policy.maxTxRequestIds,
              sessionState.copy(
                pendingRequestByIds = sessionState.pendingRequestByIds.updated(
                  chainId,
                  appendUnique(
                    sessionState.pendingRequestByIds
                      .getOrElse(chainId, Vector.empty[StableArtifactId]),
                    distinctIds,
                  ),
                ),
              ),
              controlRejected(
                "requestByIdTooLarge",
                ss"max=${policy.maxTxRequestIds.toString} actual=${distinctIds.size.toString}",
              ),
            )

      case ControlOp.RequestByIdExact(scope, ids) =>
        validateExactKnownSubscription(sessionState, scope).flatMap: contract =>
          val distinctIds = ids.distinct
          val nextRetryCount =
            sessionState.requestScopeRetryCounts.getOrElse(scope, 0) + 1
          contract.requestByIdLimit match
            case Some(limit) =>
              if distinctIds.sizeIs > limit then
                controlRejected(
                  "requestByIdTooLarge",
                  ss"max=${limit.toString} actual=${distinctIds.size.toString}",
                ).asLeft[TxProducerSessionState]
              else
                policy.maxExactRequestRetriesPerScope match
                  case Some(retryLimit) if nextRetryCount > retryLimit =>
                    controlRejected(
                      "requestByIdRetryBudgetExceeded",
                      ss"max=${retryLimit.toString} actual=${nextRetryCount.toString} scope=${scope.topic.value}",
                    ).asLeft[TxProducerSessionState]
                  case _ =>
                    sessionState
                      .copy(
                        pendingRequestScopeIds =
                          sessionState.pendingRequestScopeIds.updated(
                            scope,
                            appendUnique(
                              sessionState.pendingRequestScopeIds.getOrElse(
                                scope,
                                Vector.empty[StableArtifactId],
                              ),
                              distinctIds,
                            ),
                          ),
                        requestScopeRetryCounts =
                          sessionState.requestScopeRetryCounts
                            .updated(scope, nextRetryCount),
                      )
                      .asRight[CanonicalRejection.ControlBatchRejected]
            case None =>
              controlRejected("unsupportedTopic", scope.topic.value)
                .asLeft[TxProducerSessionState]

      case ControlOp.Config(values) =>
        values
          .foldLeft[
            Either[CanonicalRejection.ControlBatchRejected, TxBatchingConfig],
          ](
            sessionState.batchingConfig
              .asRight[CanonicalRejection.ControlBatchRejected],
          ):
            case (Right(config), (SessionConfigKey.TxMaxBatchItems, value)) =>
              positiveIntConfig("tx.maxBatchItems", value).map: parsed =>
                config.copy(maxBatchItems = parsed)
            case (Right(config), (SessionConfigKey.TxFlushIntervalMs, value)) =>
              positiveIntConfig("tx.flushIntervalMs", value).map: parsed =>
                config.copy(flushInterval = Duration.ofMillis(parsed.toLong))
            // SessionConfigKey is a closed enum in the shipped baseline.
            case (left @ Left(_), _) =>
              left
          .map: batchingConfig =>
            sessionState.copy(batchingConfig = batchingConfig)

  protected final def pruneIdempotencyKeys(
      now: Instant,
      sessionState: TxProducerSessionState,
  ): Map[ControlIdempotencyKey, Instant] =
    val cutoff = now.minus(policy.controlRetryHorizon(sessionState.negotiated))
    sessionState.idempotencyKeys.filterNot((_, appliedAt) =>
      appliedAt.isBefore(cutoff),
    )

  protected final def validateTxSubscription(
      sessionState: TxProducerSessionState,
      chainId: ChainId,
      topic: GossipTopic,
  ): Either[CanonicalRejection.ControlBatchRejected, Unit] =
    validateSubscription(sessionState, chainId, topic).flatMap: _ =>
      Either.cond(
        topic === GossipTopic.tx,
        (),
        controlRejected(
          "topicOutOfSubscription",
          ss"${chainId.value}:${topic.value}",
        ),
      )

  protected final def validateSubscription(
      sessionState: TxProducerSessionState,
      chainId: ChainId,
      topic: GossipTopic,
  ): Either[CanonicalRejection.ControlBatchRejected, Unit] =
    Either.cond(
      sessionState.subscriptions.contains(chainId, topic),
      (),
      controlRejected(
        "topicOutOfSubscription",
        ss"${chainId.value}:${topic.value}",
      ),
    )

  protected final def validateExactKnownSubscription(
      sessionState: TxProducerSessionState,
      scope: ExactKnownSetScope,
  ): Either[CanonicalRejection.ControlBatchRejected, GossipTopicContract[A]] =
    Either
      .cond(
        sessionState.subscriptions.contains(scope.chainId, scope.topic),
        (),
        controlRejected(
          "topicOutOfSubscription",
          ss"${scope.chainId.value}:${scope.topic.value}",
        ),
      )
      .flatMap(_ => openExactKnownContract(scope.topic))

  protected final def openExactKnownContract(
      topic: GossipTopic,
  ): Either[CanonicalRejection.ControlBatchRejected, GossipTopicContract[A]] =
    topicContracts
      .contractFor(topic)
      .leftMap(rejection =>
        controlRejected(
          rejection.reason,
          rejection.detail.getOrElse(topic.value),
        ),
      )
      .flatMap: contract =>
        Either.cond(
          contract.exactKnownSetLimit.nonEmpty || contract.requestByIdLimit.nonEmpty,
          contract,
          controlRejected("unsupportedTopic", topic.value),
        )
