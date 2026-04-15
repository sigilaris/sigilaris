package org.sigilaris.core.application.scheduling

import cats.syntax.either.*

import org.sigilaris.core.application.execution.TxExecution
import org.sigilaris.core.application.state.StoreState

/** Describes why a transaction or batch fell back to compatibility mode.
  *
  * @param reason short machine-readable reason code
  * @param detail optional human-readable detail
  */
final case class CompatibilityReason(
    reason: String,
    detail: Option[String],
)

/** Companion for [[CompatibilityReason]]. */
object CompatibilityReason:
  /** Converts a footprint derivation failure into a compatibility reason.
    *
    * @param failure the derivation failure
    * @return a CompatibilityReason with the same reason and detail
    */
  def fromDerivationFailure(
      failure: FootprintDerivationFailure,
  ): CompatibilityReason =
    CompatibilityReason(
      reason = failure.reason,
      detail = failure.detail,
    )

/** Classification of a transaction for batch scheduling purposes. */
enum SchedulingClassification:
  /** Transaction has a known conflict footprint and can be scheduled. */
  case Schedulable(footprint: ConflictFootprint)
  /** Transaction must be executed in compatibility (sequential) mode. */
  case Compatibility(reason: CompatibilityReason)

/** Companion for [[SchedulingClassification]], providing derivation helpers. */
object SchedulingClassification:
  /** Creates a classification from a footprint derivation result.
    *
    * @param result either a derivation failure (compatibility) or a conflict footprint (schedulable)
    * @return the corresponding classification
    */
  def fromDerivation(
      result: Either[FootprintDerivationFailure, ConflictFootprint],
  ): SchedulingClassification =
    result.fold(
      failure => SchedulingClassification.Compatibility(CompatibilityReason.fromDerivationFailure(failure)),
      footprint => SchedulingClassification.Schedulable(footprint),
    )

  /** Derives a classification for a value using its FootprintDeriver instance.
    *
    * @tparam A the item type with a FootprintDeriver
    * @param value the item to classify
    * @return the scheduling classification
    */
  def derive[A: FootprintDeriver](
      value: A,
  ): SchedulingClassification =
    fromDerivation(FootprintDeriver.derive(value))

/** A batch item paired with its scheduling classification.
  *
  * @tparam A the item type
  * @param item the original item
  * @param classification the scheduling classification
  */
final case class ClassifiedItem[A](
    item: A,
    classification: SchedulingClassification,
):
  /** Extracts this item as a schedulable item if its classification is Schedulable.
    *
    * @return Some(SchedulableItem) if schedulable, None if compatibility
    */
  def schedulable: Option[SchedulableItem[A]] =
    classification match
      case SchedulingClassification.Schedulable(footprint) =>
        Some(SchedulableItem(item = item, declaredFootprint = footprint))
      case SchedulingClassification.Compatibility(_) =>
        None

/** A batch item with a declared conflict footprint, eligible for schedulable execution.
  *
  * @tparam A the item type
  * @param item the original item
  * @param declaredFootprint the declared read/write footprint
  */
final case class SchedulableItem[A](
    item: A,
    declaredFootprint: ConflictFootprint,
)

/** Mode of compatibility execution for a batch. */
enum CompatibilityMode:
  /** Batch contains both schedulable and compatibility transactions. */
  case MixedBatch
  /** All transactions in the batch require compatibility mode. */
  case CompatibilityOnly

/** Batch execution plan for compatibility mode (sequential execution).
  *
  * @tparam A the item type
  * @param items the classified items in execution order
  * @param mode the compatibility mode (mixed or compatibility-only)
  */
final case class CompatibilityBatchPlan[A](
    items: Vector[ClassifiedItem[A]],
    mode: CompatibilityMode,
)

/** Batch execution plan for schedulable mode (conflict-free transactions).
  *
  * @tparam A the item type
  * @param items the schedulable items with their declared footprints
  * @param aggregate the aggregate footprint of all items (verified conflict-free)
  */
final case class SchedulableBatchPlan[A](
    items: Vector[SchedulableItem[A]],
    aggregate: AggregateFootprint,
)

/** The result of batch planning: either a schedulable or compatibility plan.
  *
  * @tparam A the item type
  */
enum BatchPlan[A]:
  /** All items are schedulable and conflict-free. */
  case Schedulable(plan: SchedulableBatchPlan[A])
  /** Some or all items require compatibility-mode execution. */
  case Compatibility(plan: CompatibilityBatchPlan[A])

/** Classifies and plans batches of items for execution based on conflict footprints. */
object BatchPlanner:
  private def compatibilityResult[A](
      plan: CompatibilityBatchPlan[A],
  ): Either[FootprintConflict[A], BatchPlan[A]] =
    BatchPlan.Compatibility(plan).asRight[FootprintConflict[A]]

  /** Classifies a single item using the provided classification function.
    *
    * @tparam A the item type
    * @param item the item to classify
    * @param classifyItem the classification function
    * @return the classified item
    */
  def classify[A](
      item: A,
  )(
      classifyItem: A => SchedulingClassification,
  ): ClassifiedItem[A] =
    ClassifiedItem(item = item, classification = classifyItem(item))

  /** Classifies a single item using its FootprintDeriver instance.
    *
    * @tparam A the item type with a FootprintDeriver
    * @param item the item to classify
    * @return the classified item
    */
  def classifyWithDeriver[A: FootprintDeriver](
      item: A,
  ): ClassifiedItem[A] =
    classify(item)(SchedulingClassification.derive[A])

  /** Classifies all items in a collection.
    *
    * @tparam A the item type
    * @param items the items to classify
    * @param classifyItem the classification function
    * @return a vector of classified items preserving order
    */
  def classifyAll[A](
      items: Iterable[A],
  )(
      classifyItem: A => SchedulingClassification,
  ): Vector[ClassifiedItem[A]] =
    items.iterator.map(item => classify(item)(classifyItem)).toVector

  /** Plans a batch from already-classified items, verifying conflict-freedom if all are schedulable.
    *
    * @tparam A the item type
    * @param classified the pre-classified items
    * @return either a conflict or a batch plan
    */
  def planClassified[A](
      classified: Vector[ClassifiedItem[A]],
  ): Either[FootprintConflict[A], BatchPlan[A]] =
    val schedulableItems = classified.flatMap(_.schedulable)
    if schedulableItems.sizeCompare(classified.size) == 0 then
      ConflictFootprintVerifier
        .verifyAll:
          schedulableItems.map(item => item.item -> item.declaredFootprint)
        .map: aggregate =>
          BatchPlan.Schedulable(
            SchedulableBatchPlan(
              items = schedulableItems,
              aggregate = aggregate,
            ),
          )
    else
      val mode =
        if schedulableItems.isEmpty then CompatibilityMode.CompatibilityOnly
        else CompatibilityMode.MixedBatch
      compatibilityResult(
        CompatibilityBatchPlan(
          items = classified,
          mode = mode,
        ),
      )

  /** Classifies and plans a batch in one step.
    *
    * @tparam A the item type
    * @param items the items to plan
    * @param classifyItem the classification function
    * @return either a conflict or a batch plan
    */
  def plan[A](
      items: Iterable[A],
  )(
      classifyItem: A => SchedulingClassification,
  ): Either[FootprintConflict[A], BatchPlan[A]] =
    planClassified(classifyAll(items)(classifyItem))

  /** Classifies and plans a batch using the FootprintDeriver typeclass.
    *
    * @tparam A the item type with a FootprintDeriver
    * @param items the items to plan
    * @return either a conflict or a batch plan
    */
  def planWithDeriver[A: FootprintDeriver](
      items: Iterable[A],
  ): Either[FootprintConflict[A], BatchPlan[A]] =
    plan(items)(SchedulingClassification.derive[A])

/** Failure that may occur during schedulable batch execution.
  *
  * @tparam A the item type
  * @tparam E the execution error type
  */
enum SchedulableExecutionFailure[A, E]:
  /** The transaction's execution function returned an error. */
  case ExecutionFailed(item: A, cause: E)
  /** The actual footprint could not be derived from the access log. */
  case ActualFootprintUnavailable(
      item: A,
      violation: ConflictFootprint.AccessLogInvariantViolation,
  )
  /** The actual footprint exceeded the declared footprint. */
  case ConformanceFailed(
      item: A,
      failure: FootprintConformanceFailure,
  )

/** Conformance check failure for a single executed item.
  *
  * @tparam A the item type
  */
enum ExecutionConformanceFailure[A]:
  /** The actual footprint could not be derived from the access log. */
  case ActualFootprintUnavailable(
      item: A,
      violation: ConflictFootprint.AccessLogInvariantViolation,
  )
  /** The actual footprint exceeded the declared footprint. */
  case ConformanceFailed(
      item: A,
      failure: FootprintConformanceFailure,
  )

/** A schedulable item that has been executed, pairing the plan with the execution result.
  *
  * @tparam A the item type
  * @param planned the original planned schedulable item
  * @param execution the execution result
  */
final case class ExecutedSchedulableItem[A](
    planned: SchedulableItem[A],
    execution: TxExecution[?, ?],
)

/** Result of executing a schedulable batch plan.
  *
  * @tparam A the item type
  * @param nextState the store state after all transactions have been applied
  * @param items the executed items in order
  */
final case class SchedulableBatchExecution[A](
    nextState: StoreState,
    items: Vector[ExecutedSchedulableItem[A]],
)

/** Validates that an executed transaction's actual footprint conforms to its declared footprint. */
object SchedulableExecutionVerifier:
  /** Validates conformance of a single executed item.
    *
    * @tparam A the item type
    * @param planned the planned schedulable item with declared footprint
    * @param execution the execution result containing the actual footprint
    * @return either a conformance failure or unit on success
    */
  def validate[A](
      planned: SchedulableItem[A],
      execution: TxExecution[?, ?],
  ): Either[ExecutionConformanceFailure[A], Unit] =
    execution.actualFootprint.left
      .map: violation =>
        ExecutionConformanceFailure.ActualFootprintUnavailable(
          item = planned.item,
          violation = violation,
        )
      .flatMap: actual =>
        ConflictFootprintConformance
          .validate(actual, planned.declaredFootprint)
          .left
          .map: failure =>
            ExecutionConformanceFailure.ConformanceFailed(
              item = planned.item,
              failure = failure,
            )

/** Executes a schedulable batch plan sequentially, verifying footprint conformance after each item. */
object SchedulableBatchExecutor:
  private def widenFailure[A, E](
      failure: ExecutionConformanceFailure[A],
  ): SchedulableExecutionFailure[A, E] =
    failure match
      case ExecutionConformanceFailure.ActualFootprintUnavailable(
            item,
            violation,
          ) =>
        SchedulableExecutionFailure.ActualFootprintUnavailable[A, E](
          item = item,
          violation = violation,
        )
      case ExecutionConformanceFailure.ConformanceFailed(item, failure) =>
        SchedulableExecutionFailure.ConformanceFailed[A, E](
          item = item,
          failure = failure,
        )

  private def validateExecution[A, E](
      planned: SchedulableItem[A],
      result: Either[E, TxExecution[?, ?]],
  ): Either[SchedulableExecutionFailure[A, E], TxExecution[?, ?]] =
    result.left
      .map: cause =>
        SchedulableExecutionFailure.ExecutionFailed(
          item = planned.item,
          cause = cause,
        )
      .flatMap: execution =>
        SchedulableExecutionVerifier
          .validate(planned, execution)
          .left
          .map(widenFailure[A, E])
          .map(_ => execution)

  /** Executes all items in a schedulable batch plan sequentially, verifying conformance after each.
    *
    * @tparam A the item type
    * @tparam E the execution error type
    * @param initial the initial store state
    * @param plan the schedulable batch plan to execute
    * @param execute the function that executes a single item against a store state
    * @return either an execution failure or the batch execution result
    */
  def executeSequentially[A, E](
      initial: StoreState,
      plan: SchedulableBatchPlan[A],
  )(
      execute: (StoreState, A) => Either[E, TxExecution[?, ?]],
  ): Either[SchedulableExecutionFailure[A, E], SchedulableBatchExecution[A]] =
    val initialAcc =
      scala.Right[SchedulableExecutionFailure[
        A,
        E,
      ], (StoreState, Vector[ExecutedSchedulableItem[A]])]:
        initial -> Vector.empty[ExecutedSchedulableItem[A]]

    plan.items
      .foldLeft[
        Either[
          SchedulableExecutionFailure[A, E],
          (StoreState, Vector[ExecutedSchedulableItem[A]]),
        ],
      ](initialAcc):
        case (Right((currentState, executed)), planned) =>
          validateExecution(
            planned,
            execute(currentState, planned.item),
          ).map: execution =>
            execution.nextState -> (executed :+ ExecutedSchedulableItem(
              planned,
              execution,
            ))
        case (left @ Left(_), _) =>
          left
      .map: (nextState, items) =>
        SchedulableBatchExecution(
          nextState = nextState,
          items = items,
        )
