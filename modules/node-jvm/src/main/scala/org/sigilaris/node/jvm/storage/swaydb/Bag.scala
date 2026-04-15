package org.sigilaris.node.jvm.storage.swaydb

import scala.concurrent.{ExecutionContext, Future, Promise}

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import swaydb.{IO as SwayIO}

/** Provides a cats-effect IO-based `swaydb.Bag.Async` implementation for SwayDB integration. */
@SuppressWarnings(Array("org.wartremover.warts.Nothing"))
object Bag:

  /** Type alias for the SwayDB asynchronous bag. */
  type Async[F[_]] = swaydb.Bag.Async[F]

  /** Creates an asynchronous SwayDB bag backed by the given cats-effect IO runtime.
    *
    * @param runtime the IORuntime to use for executing IO effects
    * @return an `Async[IO]` bag suitable for SwayDB operations
    */
  def fromRuntime(runtime: IORuntime): Async[IO] =
    new swaydb.Bag.Async[IO]:
      given IORuntime = runtime

      override def executionContext: ExecutionContext =
        runtime.compute

      override val unit: IO[Unit] =
        IO.unit

      override def none[A]: IO[Option[A]] =
        IO.pure(Option.empty)

      override def apply[A](a: => A): IO[A] =
        IO(a)

      override def map[A, B](a: IO[A])(f: A => B): IO[B] =
        a.map(f)

      override def transform[A, B](a: IO[A])(f: A => B): IO[B] =
        a.map(f)

      override def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] =
        fa.flatMap(f)

      override def success[A](value: A): IO[A] =
        IO.pure(value)

      override def failure[A](exception: Throwable): IO[A] =
        IO.raiseError(exception)

      override def foreach[A](a: IO[A])(f: A => Unit): Unit =
        f(a.unsafeRunSync())

      def fromPromise[A](promise: Promise[A]): IO[A] =
        IO.fromFuture(IO(promise.future))

      override def complete[A](promise: Promise[A], a: IO[A]): Unit =
        promise.completeWith(a.unsafeToFuture())

      override def fromIO[E: SwayIO.ExceptionHandler, A](
          a: SwayIO[E, A],
      ): IO[A] =
        IO.fromTry(a.toTry)

      override def fromFuture[A](a: Future[A]): IO[A] =
        IO.fromFuture(IO(a))

      override def suspend[B](f: => IO[B]): IO[B] =
        IO.defer(f)

      override def flatten[A](fa: IO[IO[A]]): IO[A] =
        fa.flatMap(identity)

  /** A global `Async[IO]` bag using the default cats-effect global runtime. */
  val global: Async[IO] =
    fromRuntime(cats.effect.unsafe.implicits.global)
