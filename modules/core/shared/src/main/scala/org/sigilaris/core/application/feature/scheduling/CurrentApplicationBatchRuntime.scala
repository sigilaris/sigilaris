package org.sigilaris.core.application.feature.scheduling

import cats.instances.either.given
import cats.syntax.all.*

import org.sigilaris.core.application.execution.{
  StateModuleExecutor,
  TxExecutionReceiptProjection,
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
import org.sigilaris.core.failure.{DecodeFailure, SigilarisFailure}
import org.sigilaris.core.datatype.{Utf8, ValidatedOpaqueValueCompanion}
import org.sigilaris.core.merkle.MerkleTrie

/** Union type of all transaction types supported by the current application. */
type CurrentApplicationTx =
  CreateNamedAccount | UpdateAccount | AddKeyIds | RemoveKeyIds |
    RemoveAccount | CreateGroup | DisbandGroup | AddAccounts | RemoveAccounts |
    ReplaceCoordinator

/** A signed transaction for the current application. */
type CurrentApplicationSignedTx = Signed[CurrentApplicationTx]

/** Idempotency key used to deduplicate repeated batch submissions. */
opaque type BatchIdempotencyKey = Utf8

/** Companion for [[BatchIdempotencyKey]]. */
object BatchIdempotencyKey
    extends ValidatedOpaqueValueCompanion[BatchIdempotencyKey, Utf8]:
  def apply(value: Utf8): Either[String, BatchIdempotencyKey] =
    Either.cond(
      value.asString.nonEmpty,
      wrap(value),
      "BatchIdempotencyKey must be non-empty",
    )

  def fromString(value: String): Either[String, BatchIdempotencyKey] =
    apply(Utf8(value))

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafeFromString(value: String): BatchIdempotencyKey =
    unsafe(Utf8(value))

  protected def wrap(repr: Utf8): BatchIdempotencyKey = repr

  protected def unwrap(value: BatchIdempotencyKey): Utf8 = value

  // Byte decoding stays backward-compatible with legacy empty identifiers;
  // constructor-validated entry points still reject them for new values.
  override protected def decodeByteRepr(
      repr: Utf8,
  ): Either[DecodeFailure, BatchIdempotencyKey] =
    Right[DecodeFailure, BatchIdempotencyKey](wrap(repr))

  override protected def decodeJsonRepr(
      repr: Utf8,
  ): Either[DecodeFailure, BatchIdempotencyKey] =
    Right[DecodeFailure, BatchIdempotencyKey](wrap(repr))

  extension (key: BatchIdempotencyKey)
    inline def toUtf8: Utf8 = key

/** A batch of signed transactions submitted for execution.
  *
  * @param idempotencyKey unique key for deduplication across retries
  * @param items the ordered transactions in this batch
  */
final case class CurrentApplicationBatch(
    idempotencyKey: BatchIdempotencyKey,
    items: Vector[CurrentApplicationSignedTx],
)

/** Companion for [[CurrentApplicationBatch]]. */
object CurrentApplicationBatch:
  def apply(
      idempotencyKey: String,
      items: Vector[CurrentApplicationSignedTx],
  ): Either[String, CurrentApplicationBatch] =
    BatchIdempotencyKey.fromString(idempotencyKey).map:
      CurrentApplicationBatch(_, items)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(
      idempotencyKey: String,
      items: Vector[CurrentApplicationSignedTx],
  ): CurrentApplicationBatch =
    apply(idempotencyKey = idempotencyKey, items = items) match
      case Right(batch)  => batch
      case Left(error)   => throw new IllegalArgumentException(error)

/** Describes the execution mode used for a batch. */
enum CurrentApplicationBatchMode:
  /** All transactions were schedulable (conflict-free). */
  case Schedulable
  /** Batch fell back to compatibility mode due to non-schedulable transactions. */
  case Compatibility(
      reason: CompatibilityReason,
      mode: CompatibilityMode,
  )

/** Diagnostic information collected during batch planning.
  *
  * @param mode the execution mode (schedulable or compatibility)
  * @param duplicatesDropped transactions removed during deduplication
  * @param classifications scheduling classification for each transaction
  */
final case class CurrentApplicationBatchDiagnostics(
    mode: CurrentApplicationBatchMode,
    duplicatesDropped: Vector[CurrentApplicationSignedTx],
    classifications: Vector[ClassifiedItem[CurrentApplicationSignedTx]],
)

/** A single executed transaction projected onto the public receipt surface.
  *
  * @param tx the original signed transaction
  * @param execution explicit execution projection for receipt/public consumers
  */
final case class CurrentApplicationExecutedTx(
    tx: CurrentApplicationSignedTx,
    execution: TxExecutionReceiptProjection[?, ?],
)

/** Receipt produced after a batch is fully executed.
  *
  * @param diagnostics planning diagnostics for the batch
  * @param executions per-transaction execution results in order
  */
final case class CurrentApplicationBatchReceipt(
    diagnostics: CurrentApplicationBatchDiagnostics,
    executions: Vector[CurrentApplicationExecutedTx],
)

/** Outcome of applying a batch to the runtime state. */
enum CurrentApplicationBatchOutcome:
  /** The batch was newly applied. */
  case Applied(receipt: CurrentApplicationBatchReceipt)
  /** The batch was a duplicate (idempotency key already seen). */
  case Deduplicated(receipt: CurrentApplicationBatchReceipt)

/** Mutable-free runtime state for batch processing.
  *
  * @param storeState the current Merkle trie store state
  * @param receiptsByIdempotencyKey map of processed batch receipts keyed by idempotency key
  */
final case class CurrentApplicationBatchRuntimeState(
    storeState: StoreState,
    receiptsByIdempotencyKey: Map[BatchIdempotencyKey, CurrentApplicationBatchReceipt],
)

/** Companion for [[CurrentApplicationBatchRuntimeState]]. */
object CurrentApplicationBatchRuntimeState:
  /** An empty initial runtime state with no stored data. */
  val empty: CurrentApplicationBatchRuntimeState =
    CurrentApplicationBatchRuntimeState(
      storeState = StoreState.empty,
      receiptsByIdempotencyKey =
        Map.empty[BatchIdempotencyKey, CurrentApplicationBatchReceipt],
    )

/** Reasons a batch may be rejected during planning or execution. */
enum CurrentApplicationBatchRejected:
  /** Two transactions in the batch have conflicting state footprints. */
  case SchedulingConflict(
      conflict: FootprintConflict[CurrentApplicationSignedTx],
      diagnostics: CurrentApplicationBatchDiagnostics,
  )
  /** A schedulable transaction failed during execution or footprint conformance. */
  case SchedulableExecutionFailed(
      failure: SchedulableExecutionFailure[
        CurrentApplicationSignedTx,
        SigilarisFailure,
      ],
      diagnostics: CurrentApplicationBatchDiagnostics,
  )
  /** A compatibility-mode transaction failed during execution. */
  case CompatibilityExecutionFailed(
      tx: CurrentApplicationSignedTx,
      cause: SigilarisFailure,
      diagnostics: CurrentApplicationBatchDiagnostics,
  )

/** Factory and type aliases for [[CurrentApplicationBatchRuntime]]. */
object CurrentApplicationBatchRuntime:
  /** The effect type used by the runtime (synchronous Either). */
  type RuntimeF[A] = Either[SigilarisFailure, A]

  /** Function type that classifies a signed transaction for scheduling. */
  type Classifier  = CurrentApplicationSignedTx => SchedulingClassification

  /** The default classifier that delegates to [[CurrentApplicationScheduling.classify]]. */
  val defaultClassifier: Classifier =
    signedTx => CurrentApplicationScheduling.classify(signedTx)

  /** Creates a runtime with a custom transaction classifier.
    *
    * @param classifyTx the classification function
    * @param nodeStore the MerkleTrie node store
    * @return a new batch runtime instance
    */
  def createWithClassifier(
      classifyTx: Classifier,
  )(using
      nodeStore: MerkleTrie.NodeStore[RuntimeF],
  ): CurrentApplicationBatchRuntime =
    new CurrentApplicationBatchRuntime(classifyTx)

  /** Creates a runtime using the default transaction classifier.
    *
    * @param nodeStore the MerkleTrie node store
    * @return a new batch runtime instance
    */
  def createDefault()(using
      nodeStore: MerkleTrie.NodeStore[RuntimeF],
  ): CurrentApplicationBatchRuntime =
    createWithClassifier(defaultClassifier)

/** Batch transaction runtime for the current application.
  *
  * Mounts the accounts and groups modules, wires their dependencies,
  * and provides batch execution with scheduling, deduplication, and
  * compatibility fallback.
  */
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

  /** Applies a batch of transactions to the given runtime state.
    *
    * Handles deduplication via idempotency keys, schedules transactions
    * using the configured classifier, and falls back to compatibility
    * mode when necessary.
    *
    * @param state the current runtime state
    * @param batch the batch of transactions to apply
    * @return either a rejection reason or the updated state with outcome
    */
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
                execution = item.execution.receiptProjection,
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
                  execution.receiptProjection,
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
