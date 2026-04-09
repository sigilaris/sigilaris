package org.sigilaris.core.application.feature.scheduling

import cats.instances.either.given
import cats.syntax.all.*

import org.sigilaris.core.application.execution.{
  StateModuleExecutor,
  TxExecution,
}
import org.sigilaris.core.application.feature.accounts.module.AccountsBP
import org.sigilaris.core.application.feature.accounts.transactions.{
  AddKeyIds,
  CreateNamedAccount,
  RemoveAccount,
  RemoveKeyIds,
  UpdateAccount,
}
import org.sigilaris.core.application.feature.group.module.GroupsBP
import org.sigilaris.core.application.feature.group.transactions.{
  AddAccounts,
  CreateGroup,
  DisbandGroup,
  RemoveAccounts,
  ReplaceCoordinator,
}
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.scheduling.{
  BatchPlan,
  BatchPlanner,
  ClassifiedItem,
  CompatibilityBatchPlan,
  CompatibilityMode,
  CompatibilityReason,
  FootprintConflict,
  SchedulableBatchExecutor,
  SchedulableBatchPlan,
  SchedulableExecutionFailure,
  SchedulingClassification,
}
import org.sigilaris.core.application.state.StoreState
import org.sigilaris.core.application.transactions.Signed
import org.sigilaris.core.failure.SigilarisFailure
import org.sigilaris.core.merkle.MerkleTrie

type CurrentApplicationTx =
  CreateNamedAccount | UpdateAccount | AddKeyIds | RemoveKeyIds |
    RemoveAccount | CreateGroup | DisbandGroup | AddAccounts | RemoveAccounts |
    ReplaceCoordinator

type CurrentApplicationSignedTx = Signed[CurrentApplicationTx]

final case class CurrentApplicationBatch(
    idempotencyKey: String,
    items: Vector[CurrentApplicationSignedTx],
)

enum CurrentApplicationBatchMode:
  case Schedulable
  case Compatibility(
      reason: CompatibilityReason,
      mode: CompatibilityMode,
  )

final case class CurrentApplicationBatchDiagnostics(
    mode: CurrentApplicationBatchMode,
    duplicatesDropped: Vector[CurrentApplicationSignedTx],
    classifications: Vector[ClassifiedItem[CurrentApplicationSignedTx]],
)

final case class CurrentApplicationExecutedTx(
    tx: CurrentApplicationSignedTx,
    execution: TxExecution[?, ?],
)

final case class CurrentApplicationBatchReceipt(
    diagnostics: CurrentApplicationBatchDiagnostics,
    executions: Vector[CurrentApplicationExecutedTx],
)

enum CurrentApplicationBatchOutcome:
  case Applied(receipt: CurrentApplicationBatchReceipt)
  case Deduplicated(receipt: CurrentApplicationBatchReceipt)

final case class CurrentApplicationBatchRuntimeState(
    storeState: StoreState,
    receiptsByIdempotencyKey: Map[String, CurrentApplicationBatchReceipt],
)

object CurrentApplicationBatchRuntimeState:
  val empty: CurrentApplicationBatchRuntimeState =
    CurrentApplicationBatchRuntimeState(
      storeState = StoreState.empty,
      receiptsByIdempotencyKey =
        Map.empty[String, CurrentApplicationBatchReceipt],
    )

enum CurrentApplicationBatchRejected:
  case SchedulingConflict(
      conflict: FootprintConflict[CurrentApplicationSignedTx],
      diagnostics: CurrentApplicationBatchDiagnostics,
  )
  case SchedulableExecutionFailed(
      failure: SchedulableExecutionFailure[
        CurrentApplicationSignedTx,
        SigilarisFailure,
      ],
      diagnostics: CurrentApplicationBatchDiagnostics,
  )
  case CompatibilityExecutionFailed(
      tx: CurrentApplicationSignedTx,
      cause: SigilarisFailure,
      diagnostics: CurrentApplicationBatchDiagnostics,
  )

object CurrentApplicationBatchRuntime:
  type RuntimeF[A] = Either[SigilarisFailure, A]
  type Classifier  = CurrentApplicationSignedTx => SchedulingClassification

  val defaultClassifier: Classifier =
    signedTx => CurrentApplicationScheduling.classify(signedTx)

  def createWithClassifier(
      classifyTx: Classifier,
  )(using
      nodeStore: MerkleTrie.NodeStore[RuntimeF],
  ): CurrentApplicationBatchRuntime =
    new CurrentApplicationBatchRuntime(classifyTx)

  def createDefault()(using
      nodeStore: MerkleTrie.NodeStore[RuntimeF],
  ): CurrentApplicationBatchRuntime =
    createWithClassifier(defaultClassifier)

final class CurrentApplicationBatchRuntime private (
    classifyTx: CurrentApplicationBatchRuntime.Classifier,
)(using
    nodeStore: MerkleTrie.NodeStore[CurrentApplicationBatchRuntime.RuntimeF],
):
  import CurrentApplicationBatchRuntime.RuntimeF

  private val accountsModule =
    org.sigilaris.core.application.module.runtime.StateModule
      .mount[("app", "accounts")](AccountsBP[RuntimeF])

  private val groupsModule =
    org.sigilaris.core.application.module.runtime.StateModule
      .mount[("app", "groups")](
        GroupsBP[RuntimeF](TablesProvider.fromModule(accountsModule)),
      )

  def applyBatch(
      state: CurrentApplicationBatchRuntimeState,
      batch: CurrentApplicationBatch,
  ): Either[
    CurrentApplicationBatchRejected,
    (CurrentApplicationBatchRuntimeState, CurrentApplicationBatchOutcome),
  ] =
    state.receiptsByIdempotencyKey.get(batch.idempotencyKey) match
      case Some(existingReceipt) =>
        (
          state,
          CurrentApplicationBatchOutcome.Deduplicated(existingReceipt),
        ).asRight[CurrentApplicationBatchRejected]
      case None =>
        val (uniqueItems, duplicatesDropped) =
          deduplicate(batch.items)
        val classified =
          BatchPlanner.classifyAll(uniqueItems)(classifyTx)
        BatchPlanner
          .planClassified(classified)
          .leftMap: conflict =>
            CurrentApplicationBatchRejected.SchedulingConflict(
              conflict = conflict,
              diagnostics =
                schedulableDiagnostics(classified, duplicatesDropped),
            )
          .flatMap:
            case BatchPlan.Schedulable(plan) =>
              executeSchedulable(
                state = state,
                batch = batch,
                plan = plan,
                classified = classified,
                duplicatesDropped = duplicatesDropped,
              )
            case BatchPlan.Compatibility(plan) =>
              executeCompatibility(
                state = state,
                batch = batch,
                plan = plan,
                classified = classified,
                duplicatesDropped = duplicatesDropped,
              )

  private def executeSchedulable(
      state: CurrentApplicationBatchRuntimeState,
      batch: CurrentApplicationBatch,
      plan: SchedulableBatchPlan[CurrentApplicationSignedTx],
      classified: Vector[ClassifiedItem[CurrentApplicationSignedTx]],
      duplicatesDropped: Vector[CurrentApplicationSignedTx],
  ): Either[
    CurrentApplicationBatchRejected,
    (CurrentApplicationBatchRuntimeState, CurrentApplicationBatchOutcome),
  ] =
    val diagnostics =
      schedulableDiagnostics(classified, duplicatesDropped)
    SchedulableBatchExecutor
      .executeSequentially(state.storeState, plan)(executeTx)
      .leftMap: failure =>
        CurrentApplicationBatchRejected.SchedulableExecutionFailed(
          failure = failure,
          diagnostics = diagnostics,
        )
      .map: execution =>
        val receipt =
          CurrentApplicationBatchReceipt(
            diagnostics = diagnostics,
            executions = execution.items.map: item =>
              CurrentApplicationExecutedTx(
                tx = item.planned.item,
                execution = item.execution,
              ),
          )
        val nextState =
          state.copy(
            storeState = execution.nextState,
            receiptsByIdempotencyKey = state.receiptsByIdempotencyKey
              .updated(batch.idempotencyKey, receipt),
          )
        nextState -> CurrentApplicationBatchOutcome.Applied(receipt)

  private def executeCompatibility(
      state: CurrentApplicationBatchRuntimeState,
      batch: CurrentApplicationBatch,
      plan: CompatibilityBatchPlan[CurrentApplicationSignedTx],
      classified: Vector[ClassifiedItem[CurrentApplicationSignedTx]],
      duplicatesDropped: Vector[CurrentApplicationSignedTx],
  ): Either[
    CurrentApplicationBatchRejected,
    (CurrentApplicationBatchRuntimeState, CurrentApplicationBatchOutcome),
  ] =
    val diagnostics =
      compatibilityDiagnostics(plan, duplicatesDropped, classified)
    plan.items
      .foldLeft[
        Either[
          CurrentApplicationBatchRejected,
          (StoreState, Vector[CurrentApplicationExecutedTx]),
        ],
      ](
        (state.storeState, Vector.empty[CurrentApplicationExecutedTx])
          .asRight[CurrentApplicationBatchRejected],
      ):
        case (Right((currentState, executed)), classifiedItem) =>
          executeTx(currentState, classifiedItem.item)
            .leftMap: cause =>
              CurrentApplicationBatchRejected.CompatibilityExecutionFailed(
                tx = classifiedItem.item,
                cause = cause,
                diagnostics = diagnostics,
              )
            .map: execution =>
              execution.nextState ->
                (executed :+ CurrentApplicationExecutedTx(
                  classifiedItem.item,
                  execution,
                ))
        case (left @ Left(_), _) =>
          left
      .map: (nextStoreState, executions) =>
        val receipt =
          CurrentApplicationBatchReceipt(
            diagnostics = diagnostics,
            executions = executions,
          )
        val nextState =
          state.copy(
            storeState = nextStoreState,
            receiptsByIdempotencyKey = state.receiptsByIdempotencyKey
              .updated(batch.idempotencyKey, receipt),
          )
        nextState -> CurrentApplicationBatchOutcome.Applied(receipt)

  private def schedulableDiagnostics(
      classified: Vector[ClassifiedItem[CurrentApplicationSignedTx]],
      duplicatesDropped: Vector[CurrentApplicationSignedTx],
  ): CurrentApplicationBatchDiagnostics =
    CurrentApplicationBatchDiagnostics(
      mode = CurrentApplicationBatchMode.Schedulable,
      duplicatesDropped = duplicatesDropped,
      classifications = classified,
    )

  private def compatibilityDiagnostics(
      plan: CompatibilityBatchPlan[CurrentApplicationSignedTx],
      duplicatesDropped: Vector[CurrentApplicationSignedTx],
      classified: Vector[ClassifiedItem[CurrentApplicationSignedTx]],
  ): CurrentApplicationBatchDiagnostics =
    CurrentApplicationBatchDiagnostics(
      mode = CurrentApplicationBatchMode.Compatibility(
        reason = compatibilityReason(plan),
        mode = plan.mode,
      ),
      duplicatesDropped = duplicatesDropped,
      classifications = classified,
    )

  private def compatibilityReason(
      plan: CompatibilityBatchPlan[CurrentApplicationSignedTx],
  ): CompatibilityReason =
    val compatibilityReasons =
      plan.items.collect:
        case ClassifiedItem(
              _,
              SchedulingClassification.Compatibility(reason),
            ) =>
          reason

    plan.mode match
      case CompatibilityMode.MixedBatch =>
        CompatibilityReason(
          reason = "mixedBatch",
          detail = compatibilityReasons
            .map(renderCompatibilityReason)
            .distinct
            .mkString(",")
            .some
            .filter(_.nonEmpty),
        )
      case CompatibilityMode.CompatibilityOnly =>
        compatibilityReasons.headOption match
          case Some(head) =>
            head.copy(
              detail = (head.detail.toList ++ compatibilityReasons
                .drop(1)
                .map(renderCompatibilityReason)).distinct
                .mkString(",")
                .some
                .filter(_.nonEmpty),
            )
          case None =>
            CompatibilityReason(
              reason = "compatibilityOnly",
              detail = None,
            )

  private def renderCompatibilityReason(
      reason: CompatibilityReason,
  ): String =
    reason.detail match
      case Some(detail) => reason.reason + ":" + detail
      case None         => reason.reason

  private def deduplicate(
      items: Vector[CurrentApplicationSignedTx],
  ): (Vector[CurrentApplicationSignedTx], Vector[CurrentApplicationSignedTx]) =
    items.foldLeft(
      (
        Vector.empty[CurrentApplicationSignedTx],
        Set.empty[CurrentApplicationSignedTx],
        Vector.empty[CurrentApplicationSignedTx],
      ),
    ):
      case ((unique, seen, duplicates), item) =>
        if seen.contains(item) then (unique, seen, duplicates :+ item)
        else (unique :+ item, seen + item, duplicates)
    match
      case (unique, _, duplicates) =>
        unique -> duplicates

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def executeTx(
      initial: StoreState,
      signedTx: CurrentApplicationSignedTx,
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    signedTx.value match
      case _: CreateNamedAccount =>
        executeCreateNamedAccount(
          initial,
          signedTx.asInstanceOf[Signed[CreateNamedAccount]],
        )
      case _: UpdateAccount =>
        executeUpdateAccount(
          initial,
          signedTx.asInstanceOf[Signed[UpdateAccount]],
        )
      case _: AddKeyIds =>
        executeAddKeyIds(
          initial,
          signedTx.asInstanceOf[Signed[AddKeyIds]],
        )
      case _: RemoveKeyIds =>
        executeRemoveKeyIds(
          initial,
          signedTx.asInstanceOf[Signed[RemoveKeyIds]],
        )
      case _: RemoveAccount =>
        executeRemoveAccount(
          initial,
          signedTx.asInstanceOf[Signed[RemoveAccount]],
        )
      case _: CreateGroup =>
        executeCreateGroup(
          initial,
          signedTx.asInstanceOf[Signed[CreateGroup]],
        )
      case _: DisbandGroup =>
        executeDisbandGroup(
          initial,
          signedTx.asInstanceOf[Signed[DisbandGroup]],
        )
      case _: AddAccounts =>
        executeAddAccounts(
          initial,
          signedTx.asInstanceOf[Signed[AddAccounts]],
        )
      case _: RemoveAccounts =>
        executeRemoveAccounts(
          initial,
          signedTx.asInstanceOf[Signed[RemoveAccounts]],
        )
      case _: ReplaceCoordinator =>
        executeReplaceCoordinator(
          initial,
          signedTx.asInstanceOf[Signed[ReplaceCoordinator]],
        )

  private def executeCreateNamedAccount(
      initial: StoreState,
      signedTx: Signed[CreateNamedAccount],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, accountsModule)
      .value
      .flatMap(identity)

  private def executeUpdateAccount(
      initial: StoreState,
      signedTx: Signed[UpdateAccount],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, accountsModule)
      .value
      .flatMap(identity)

  private def executeAddKeyIds(
      initial: StoreState,
      signedTx: Signed[AddKeyIds],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, accountsModule)
      .value
      .flatMap(identity)

  private def executeRemoveKeyIds(
      initial: StoreState,
      signedTx: Signed[RemoveKeyIds],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, accountsModule)
      .value
      .flatMap(identity)

  private def executeRemoveAccount(
      initial: StoreState,
      signedTx: Signed[RemoveAccount],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, accountsModule)
      .value
      .flatMap(identity)

  private def executeCreateGroup(
      initial: StoreState,
      signedTx: Signed[CreateGroup],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, groupsModule)
      .value
      .flatMap(identity)

  private def executeDisbandGroup(
      initial: StoreState,
      signedTx: Signed[DisbandGroup],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, groupsModule)
      .value
      .flatMap(identity)

  private def executeAddAccounts(
      initial: StoreState,
      signedTx: Signed[AddAccounts],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, groupsModule)
      .value
      .flatMap(identity)

  private def executeRemoveAccounts(
      initial: StoreState,
      signedTx: Signed[RemoveAccounts],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, groupsModule)
      .value
      .flatMap(identity)

  private def executeReplaceCoordinator(
      initial: StoreState,
      signedTx: Signed[ReplaceCoordinator],
  ): Either[SigilarisFailure, TxExecution[?, ?]] =
    StateModuleExecutor
      .runExecutionWithModule(initial, signedTx, groupsModule)
      .value
      .flatMap(identity)
