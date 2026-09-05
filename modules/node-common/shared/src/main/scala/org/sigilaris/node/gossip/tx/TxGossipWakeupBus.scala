package org.sigilaris.node.gossip.tx

import cats.effect.kernel.{Concurrent, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*

import org.sigilaris.node.gossip.*

/** Advisory wakeup reason for the transaction gossip event stream driver. */
enum TxGossipWakeup:
  case SourceAppend(chainTopic: ChainTopic)
  case ControlApplied(sessionId: DirectionalSessionId)
  case SessionTerminated(sessionId: DirectionalSessionId)
  case StreamOpened(sessionId: DirectionalSessionId)
  case TimerDeadline(sessionId: DirectionalSessionId)

/** A registered stream wakeup subscription. */
final class TxGossipWakeupSubscription[F[_]] private[tx] (
    val receive: F[TxGossipWakeup],
)

/** Runtime-owned bounded, coalescing wakeup bus for outbound event streams.
  *
  * Each active stream owns one queue with capacity one. Additional wakeups for
  * a stream that is already awake are coalesced, while the retained source and
  * session cursor state remain the delivery source of truth.
  */
final class TxGossipWakeupBus[F[_]: Concurrent] private (
    ref: Ref[F, TxGossipWakeupBus.State[F]],
) extends GossipSourceAppendNotifier[F]:

  /** Registers an active stream for its session subscriptions. */
  def register(
      sessionId: DirectionalSessionId,
      subscriptions: SessionSubscription,
  ): Resource[F, TxGossipWakeupSubscription[F]] =
    Resource
      .eval:
        Queue
          .bounded[F, TxGossipWakeup](1)
          .flatMap: queue =>
            ref
              .modify: state =>
                if state.registrations.values.exists(_.sessionId === sessionId)
                then state -> none[(Long, Queue[F, TxGossipWakeup])]
                else
                  val registrationId = state.nextRegistrationId
                  val registration   = TxGossipWakeupBus.RegisteredSession(
                    sessionId = sessionId,
                    subscriptions = subscriptions.values,
                    queue = queue,
                  )
                  state.copy(
                    nextRegistrationId = registrationId + 1L,
                    registrations =
                      state.registrations.updated(registrationId, registration),
                  ) -> (registrationId, queue).some
              .flatMap:
                case Some(value) =>
                  value.pure[F]
                case None =>
                  Concurrent[F].raiseError:
                    new IllegalStateException(
                      "event stream already registered for session " +
                        sessionId.value,
                    )
      .flatMap: (registrationId, queue) =>
        Resource
          .make(Concurrent[F].unit)(_ =>
            ref.update(state =>
              state.copy(registrations = state.registrations - registrationId),
            ),
          )
          .as(TxGossipWakeupSubscription(queue.take))

  /** Publishes a session-local wakeup. */
  def wakeSession(
      sessionId: DirectionalSessionId,
      wakeup: TxGossipWakeup,
  ): F[Unit] =
    publish(_.sessionId === sessionId, wakeup)

  /** Publishes that retained source data may be available for a chain-topic. */
  override def sourceAppended(chainTopic: ChainTopic): F[Unit] =
    publish(
      _.subscriptions.contains(chainTopic),
      TxGossipWakeup.SourceAppend(chainTopic),
    )

  /** Exposes registration count for cancellation/resource tests. */
  def activeRegistrationCount: F[Int] =
    ref.get.map(_.registrations.size)

  private def publish(
      select: TxGossipWakeupBus.RegisteredSession[F] => Boolean,
      wakeup: TxGossipWakeup,
  ): F[Unit] =
    ref.get.flatMap: state =>
      state.registrations.values.toVector
        .filter(select)
        .traverse_(_.queue.tryOffer(wakeup).void)

object TxGossipWakeupBus:

  private final case class RegisteredSession[F[_]](
      sessionId: DirectionalSessionId,
      subscriptions: Set[ChainTopic],
      queue: Queue[F, TxGossipWakeup],
  )

  private final case class State[F[_]](
      nextRegistrationId: Long,
      registrations: Map[Long, RegisteredSession[F]],
  )

  private object State:
    def empty[F[_]]: State[F] =
      State(
        nextRegistrationId = 1L,
        registrations = Map.empty[Long, RegisteredSession[F]],
      )

  /** Creates an empty runtime wakeup bus. */
  def create[F[_]: Concurrent]: F[TxGossipWakeupBus[F]] =
    Ref
      .of[F, State[F]](State.empty[F])
      .map(new TxGossipWakeupBus[F](_))
