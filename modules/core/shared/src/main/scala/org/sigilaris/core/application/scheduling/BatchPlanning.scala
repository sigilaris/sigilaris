package org.sigilaris.core.application.scheduling

import cats.syntax.either.*

import org.sigilaris.core.application.execution.TxExecution
import org.sigilaris.core.application.state.StoreState

final case class CompatibilityReason(
    reason: String,
    detail: Option[String],
)

object CompatibilityReason:
  def fromDerivationFailure(
      failure: FootprintDerivationFailure,
  ): CompatibilityReason =
    CompatibilityReason(
      reason = failure.reason,
      detail = failure.detail,
    )

enum SchedulingClassification:
  case Schedulable(footprint: ConflictFootprint)
  case Compatibility(reason: CompatibilityReason)

object SchedulingClassification:
  def fromDerivation(
      result: Either[FootprintDerivationFailure, ConflictFootprint],
  ): SchedulingClassification =
    result.fold(
      failure => SchedulingClassification.Compatibility(CompatibilityReason.fromDerivationFailure(failure)),
      footprint => SchedulingClassification.Schedulable(footprint),
    )

  def derive[A: FootprintDeriver](
      value: A,
  ): SchedulingClassification =
    fromDerivation(FootprintDeriver.derive(value))

final case class ClassifiedItem[A](
    item: A,
    classification: SchedulingClassification,
):
  def schedulable: Option[SchedulableItem[A]] =
    classification match
      case SchedulingClassification.Schedulable(footprint) =>
        Some(SchedulableItem(item = item, declaredFootprint = footprint))
      case SchedulingClassification.Compatibility(_) =>
        None

final case class SchedulableItem[A](
    item: A,
    declaredFootprint: ConflictFootprint,
)

enum CompatibilityMode:
  case MixedBatch
  case CompatibilityOnly

final case class CompatibilityBatchPlan[A](
    items: Vector[ClassifiedItem[A]],
    mode: CompatibilityMode,
)

final case class SchedulableBatchPlan[A](
    items: Vector[SchedulableItem[A]],
    aggregate: AggregateFootprint,
)

enum BatchPlan[A]:
  case Schedulable(plan: SchedulableBatchPlan[A])
  case Compatibility(plan: CompatibilityBatchPlan[A])

object BatchPlanner:
  private def compatibilityResult[A](
      plan: CompatibilityBatchPlan[A],
  ): Either[FootprintConflict[A], BatchPlan[A]] =
    BatchPlan.Compatibility(plan).asRight[FootprintConflict[A]]

  def classify[A](
      item: A,
  )(
      classifyItem: A => SchedulingClassification,
  ): ClassifiedItem[A] =
    ClassifiedItem(item = item, classification = classifyItem(item))

  def classifyWithDeriver[A: FootprintDeriver](
      item: A,
  ): ClassifiedItem[A] =
    classify(item)(SchedulingClassification.derive[A])

  def classifyAll[A](
      items: Iterable[A],
  )(
      classifyItem: A => SchedulingClassification,
  ): Vector[ClassifiedItem[A]] =
    items.iterator.map(item => classify(item)(classifyItem)).toVector

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

  def plan[A](
      items: Iterable[A],
  )(
      classifyItem: A => SchedulingClassification,
  ): Either[FootprintConflict[A], BatchPlan[A]] =
    planClassified(classifyAll(items)(classifyItem))

  def planWithDeriver[A: FootprintDeriver](
      items: Iterable[A],
  ): Either[FootprintConflict[A], BatchPlan[A]] =
    plan(items)(SchedulingClassification.derive[A])

enum SchedulableExecutionFailure[A, E]:
  case ExecutionFailed(item: A, cause: E)
  case ActualFootprintUnavailable(
      item: A,
      violation: ConflictFootprint.AccessLogInvariantViolation,
  )
  case ConformanceFailed(
      item: A,
      failure: FootprintConformanceFailure,
  )

enum ExecutionConformanceFailure[A]:
  case ActualFootprintUnavailable(
      item: A,
      violation: ConflictFootprint.AccessLogInvariantViolation,
  )
  case ConformanceFailed(
      item: A,
      failure: FootprintConformanceFailure,
  )

final case class ExecutedSchedulableItem[A](
    planned: SchedulableItem[A],
    execution: TxExecution[?, ?],
)

final case class SchedulableBatchExecution[A](
    nextState: StoreState,
    items: Vector[ExecutedSchedulableItem[A]],
)

object SchedulableExecutionVerifier:
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
