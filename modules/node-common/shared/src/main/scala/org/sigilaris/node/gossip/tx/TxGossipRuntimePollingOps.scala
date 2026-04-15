package org.sigilaris.node.gossip.tx

import java.time.Instant

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.node.gossip.*

private[tx] trait TxGossipRuntimePollingOps[F[_]: Sync, A]
    extends TxGossipRuntimeSharedOps[F, A]:

  protected final def pollOpenSession(
      now: Instant,
      sessionState: TxProducerSessionState,
  ): F[
    Either[CanonicalRejection, (TxProducerSessionState, Vector[GossipEvent[A]])],
  ] =
    sessionState.subscriptions.values.toVector
      .sortBy: chainTopic =>
        val priority =
          topicContracts
            .contractFor(chainTopic.topic)
            .toOption
            .map(_.deliveryPriority)
            .getOrElse(0)
        (-priority, chainTopic.chainId.value, chainTopic.topic.value)
      .foldLeftM(
        (
          sessionState,
          Vector.empty[GossipEvent[A]],
        ).asRight[CanonicalRejection],
      ):
        case (Left(rejection), _) =>
          rejection
            .asLeft[(TxProducerSessionState, Vector[GossipEvent[A]])]
            .pure[F]
        case (Right((currentState, emitted)), chainTopic) =>
          if chainTopic.topic === GossipTopic.tx then
            pollTxChain(now, currentState, chainTopic).map:
              case Left(rejection) =>
                rejection
                  .asLeft[(TxProducerSessionState, Vector[GossipEvent[A]])]
              case Right((updatedState, chainEvents)) =>
                (updatedState -> (emitted ++ chainEvents))
                  .asRight[CanonicalRejection]
          else
            topicContracts.contractFor(chainTopic.topic) match
              case Left(rejection) =>
                rejection
                  .asLeft[(TxProducerSessionState, Vector[GossipEvent[A]])]
                  .pure[F]
              case Right(contract) =>
                pollExactKnownChain(now, currentState, chainTopic, contract)
                  .map:
                    case Left(rejection) =>
                      rejection
                        .asLeft[
                          (TxProducerSessionState, Vector[GossipEvent[A]]),
                        ]
                    case Right((updatedState, chainEvents)) =>
                      (updatedState -> (emitted ++ chainEvents))
                        .asRight[CanonicalRejection]

  protected final def pollTxChain(
      now: Instant,
      sessionState: TxProducerSessionState,
      chainTopic: ChainTopic,
  ): F[
    Either[CanonicalRejection, (TxProducerSessionState, Vector[GossipEvent[A]])],
  ] =
    val requestedIds = sessionState.pendingRequestByIds.getOrElse(
      chainTopic.chainId,
      Vector.empty[StableArtifactId],
    )
    val producerState  = sessionState.producerState
    val cursorOverride = producerState.pendingReplay.get(chainTopic)
    val startCursor =
      cursorOverride.getOrElse(producerState.startCursorFor(chainTopic))

    for
      explicitArtifacts <-
        if requestedIds.isEmpty then
          Vector.empty[AvailableGossipEvent[A]].pure[F]
        else
          source.readByIds(chainTopic.chainId, chainTopic.topic, requestedIds)
      afterCursor <- source.readAfter(
        chainTopic.chainId,
        chainTopic.topic,
        startCursor,
      )
    yield afterCursor.flatMap: candidates =>
      val explicitEvents = explicitArtifacts.map(_.event)
      val liveCandidates = candidates.filterNot(candidate =>
        explicitEvents.exists(_.id === candidate.event.id),
      )
      cascadeStrategy
        .selectLiveEvents(
          filter = sessionState.filters.get(chainTopic.chainId),
          exactKnownIds = sessionState.exactKnownIds.getOrElse(
            chainTopic.chainId,
            Set.empty[StableArtifactId],
          ),
          candidates = liveCandidates.map(_.event),
        )
        .map: selectedLiveEvents =>
          val selectedLiveArtifacts =
            selectArtifacts(liveCandidates, selectedLiveEvents)
          val explicitBatch =
            explicitEvents.take(sessionState.batchingConfig.maxBatchItems)
          val remainingCapacity =
            (sessionState.batchingConfig.maxBatchItems - explicitBatch.size)
              .max(0)
          val forceFlush = cursorOverride.nonEmpty
          val liveBatch =
            GossipProducerPolling.batchAvailableEvents(
              now = now,
              candidates = selectedLiveArtifacts,
              qos = sessionState.batchingConfig,
              forceFlush = forceFlush,
              limit = remainingCapacity,
            )
          val emitted           = explicitBatch ++ liveBatch
          val servedExplicitIds = explicitBatch.map(_.id).toSet
          val updatedProducerState =
            producerState
              .advanceStreamCursor(chainTopic, liveBatch)
              .clearReplay(chainTopic)
          val updatedState = sessionState
            .withProducerState(updatedProducerState)
            .copy(
              pendingRequestByIds =
                if servedExplicitIds.isEmpty then
                  sessionState.pendingRequestByIds
                else
                  sessionState.pendingRequestByIds
                    .updatedWith(chainTopic.chainId):
                      case None => None
                      case Some(existing) =>
                        val remaining =
                          existing.filterNot(servedExplicitIds.contains)
                        remaining.some.filter(_.nonEmpty),
            )
          updatedState -> emitted

  protected final def pollExactKnownChain(
      now: Instant,
      sessionState: TxProducerSessionState,
      chainTopic: ChainTopic,
      contract: GossipTopicContract[A],
  ): F[
    Either[CanonicalRejection, (TxProducerSessionState, Vector[GossipEvent[A]])],
  ] =
    val requestedScopes =
      sessionState.pendingRequestScopeIds.toVector.collect:
        case (scope, ids)
            if scope.chainId === chainTopic.chainId &&
              scope.topic === chainTopic.topic =>
          scope -> ids

    val requestedIds   = requestedScopes.flatMap(_._2).distinct
    val producerState  = sessionState.producerState
    val cursorOverride = producerState.pendingReplay.get(chainTopic)
    val startCursor =
      cursorOverride.getOrElse(producerState.startCursorFor(chainTopic))
    val qos = contract.producerQoS(sessionState.batchingConfig)

    for
      explicitArtifacts <-
        if requestedIds.isEmpty then
          Vector.empty[AvailableGossipEvent[A]].pure[F]
        else
          source.readByIds(chainTopic.chainId, chainTopic.topic, requestedIds)
      afterCursor <- source.readAfter(
        chainTopic.chainId,
        chainTopic.topic,
        startCursor,
      )
    yield
      val explicitResult = explicitArtifacts.traverse: available =>
        contract
          .exactKnownScopeOf(available.event)
          .map(scope => scope -> available)

      afterCursor.flatMap: candidates =>
        explicitResult.flatMap: scopedExplicitArtifacts =>
          val requestedScopeSet = requestedScopes.map(_._1).toSet
          val explicitMatched =
            scopedExplicitArtifacts.collect:
              case (Some(scope), available)
                  if requestedScopeSet.contains(scope) =>
                scope -> available
          val explicitEvents = explicitMatched.map(_._2.event)
          val liveCandidates = candidates.filterNot(candidate =>
            explicitEvents.exists(_.id === candidate.event.id),
          )
          liveCandidates
            .traverse: candidate =>
              contract
                .exactKnownScopeOf(candidate.event)
                .map(scope => scope -> candidate)
            .map: scopedLiveCandidates =>
              val filteredLiveArtifacts =
                scopedLiveCandidates.collect:
                  case (Some(scope), candidate)
                      if !sessionState.exactKnownScopeIds
                        .getOrElse(scope, Set.empty[StableArtifactId])
                        .contains(candidate.event.id) =>
                    candidate
              val explicitBatch = explicitEvents.take(qos.maxBatchItems)
              val remainingCapacity =
                (qos.maxBatchItems - explicitBatch.size).max(0)
              val forceFlush = cursorOverride.nonEmpty
              val liveBatch =
                GossipProducerPolling.batchAvailableEvents(
                  now = now,
                  candidates = filteredLiveArtifacts,
                  qos = qos,
                  forceFlush = forceFlush,
                  limit = remainingCapacity,
                )
              val emitted = explicitBatch ++ liveBatch
              val servedByScope =
                explicitMatched.foldLeft(
                  Map.empty[ExactKnownSetScope, Set[StableArtifactId]],
                ): (acc, entry) =>
                  val (scope, available) = entry
                  acc.updated(
                    scope,
                    acc.getOrElse(scope, Set.empty[StableArtifactId]) +
                      available.event.id,
                  )
              val updatedProducerState =
                producerState
                  .advanceStreamCursor(chainTopic, liveBatch)
                  .clearReplay(chainTopic)
              val updatedState = sessionState
                .withProducerState(updatedProducerState)
                .copy(
                  pendingRequestScopeIds =
                    if servedByScope.isEmpty then
                      sessionState.pendingRequestScopeIds
                    else
                      servedByScope.foldLeft(
                        sessionState.pendingRequestScopeIds,
                      ): (acc, entry) =>
                        val (scope, servedIds) = entry
                        acc.updatedWith(scope):
                          case None => None
                          case Some(existing) =>
                            val remaining =
                              existing.filterNot(servedIds.contains)
                            remaining.some.filter(_.nonEmpty),
                )
              updatedState -> emitted
