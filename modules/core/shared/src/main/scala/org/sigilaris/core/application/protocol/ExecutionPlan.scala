package org.sigilaris.core.application.protocol

import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*

final case class ExecutionPlanVersion(value: Int)

object ExecutionPlanVersion:
  val V1: ExecutionPlanVersion            = ExecutionPlanVersion(1)
  given ByteEncoder[ExecutionPlanVersion] =
    ByteEncoder[Long].contramap(_.value.toLong)

enum ExecutionWave:
  case ConflictFree(members: Vector[ExecutionId])
  case Ordered(members: Vector[ExecutionId])

object ExecutionWave:
  val ConflictFreeTag: Byte = 1.toByte
  val OrderedTag: Byte      = 2.toByte

  extension (wave: ExecutionWave)
    def members: Vector[ExecutionId] = wave match
      case ExecutionWave.ConflictFree(values) => values
      case ExecutionWave.Ordered(values)      => values

  given ByteEncoder[ExecutionWave] with
    override def encode(value: ExecutionWave): ByteVector = value match
      case ExecutionWave.ConflictFree(members) =>
        ByteEncoder[Byte].encode(ConflictFreeTag) ++
          ByteEncoder[Vector[ExecutionId]].encode(members)
      case ExecutionWave.Ordered(members) =>
        ByteEncoder[Byte].encode(OrderedTag) ++
          ByteEncoder[Vector[ExecutionId]].encode(members)

final case class ExecutionPlan(
    version: ExecutionPlanVersion,
    waves: Vector[ExecutionWave],
)

enum ExecutionPlanValidationFailure:
  case UnsupportedVersion(actual: Int)
  case EmptyPlan
  case EmptyWave(waveIndex: Int)
  case ConflictFreeOrderMismatch(waveIndex: Int)
  case DuplicateExecution(executionId: ExecutionId)
  case MembershipMismatch(
      expected: Vector[ExecutionId],
      actual: Vector[ExecutionId],
  )

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ExecutionPlan:
  private val RootDomain = "sigilaris.application.execution-plan.root.v1"

  given ByteEncoder[ExecutionPlan] = ByteEncoder.derived

  def validate(
      plan: ExecutionPlan,
      expectedMembers: Vector[ExecutionId],
  ): Either[ExecutionPlanValidationFailure, Unit] =
    if plan.version != ExecutionPlanVersion.V1 then
      Left(
        ExecutionPlanValidationFailure.UnsupportedVersion(plan.version.value),
      )
    else if plan.waves.isEmpty then
      Left(ExecutionPlanValidationFailure.EmptyPlan)
    else
      plan.waves.zipWithIndex.collectFirst:
        case (wave, index) if wave.members.isEmpty =>
          ExecutionPlanValidationFailure.EmptyWave(index)
        case (ExecutionWave.ConflictFree(members), index)
            if members != members.sortBy(_.toHexLower) =>
          ExecutionPlanValidationFailure.ConflictFreeOrderMismatch(index)
      match
        case Some(failure) => Left(failure)
        case None          =>
          val actual = plan.waves.flatMap(_.members)
          firstDuplicate(actual) match
            case Some(duplicate) =>
              Left(ExecutionPlanValidationFailure.DuplicateExecution(duplicate))
            case None =>
              Either.cond(
                actual.toSet == expectedMembers.toSet &&
                  actual.sizeCompare(expectedMembers.size) == 0,
                (),
                ExecutionPlanValidationFailure.MembershipMismatch(
                  expectedMembers,
                  actual,
                ),
              )

  def computeRoot(
      plan: ExecutionPlan,
  ): ExecutionPlanRoot =
    ExecutionPlanRoot:
      ApplicationProtocolHash.hash(
        RootDomain,
        plan.toBytes,
      )

  def compatibilitySingleton(
      executionId: ExecutionId,
  ): ExecutionPlan =
    ExecutionPlan(
      version = ExecutionPlanVersion.V1,
      waves = Vector(ExecutionWave.Ordered(Vector(executionId))),
    )

  private def firstDuplicate(
      values: Vector[ExecutionId],
  ): Option[ExecutionId] =
    values
      .groupBy(_.toHexLower)
      .collectFirst:
        case (_, occurrences) if occurrences.sizeCompare(1) > 0 =>
          occurrences.headOption
      .flatten

enum OrderedExecutionFailure[+A, +E]:
  case EmptyOrderedWave
  case ExecutionFailed(item: A, cause: E)

final case class OrderedExecutionResult[S, A](
    nextState: S,
    applied: Vector[A],
)

@SuppressWarnings(Array("org.wartremover.warts.Nothing"))
object OrderedWaveExecutor:
  def executeAtomically[S, A, E](
      initial: S,
      items: Vector[A],
  )(
      execute: (S, A) => Either[E, S],
  ): Either[OrderedExecutionFailure[A, E], OrderedExecutionResult[S, A]] =
    if items.isEmpty then Left(OrderedExecutionFailure.EmptyOrderedWave)
    else
      val initialState: Either[OrderedExecutionFailure[A, E], (S, Vector[A])] =
        Right[OrderedExecutionFailure[A, E], (S, Vector[A])](
          initial -> Vector.empty,
        )
      items
        .foldLeft(initialState):
          case (Right((state, applied)), item) =>
            execute(state, item).left
              .map(error =>
                OrderedExecutionFailure.ExecutionFailed(item, error),
              )
              .map(next => next -> (applied :+ item))
          case (left @ Left(_), _) => left
        .map: (nextState, applied) =>
          OrderedExecutionResult(nextState, applied)
