package org.sigilaris.node.jvm.storage.swaydb

import scala.concurrent.Promise

import cats.effect.IO
import munit.CatsEffectSuite

final class BagSuite extends CatsEffectSuite:
  test("global bag reuses cats-effect global runtime semantics"):
    IO:
      assertEquals(Bag.global.executionContext, cats.effect.unsafe.implicits.global.compute)

  test("global bag completes promises through the shared IO runtime"):
    val promise = Promise[Int]()
    Bag.global.complete(promise, IO.pure(42))
    IO.fromFuture(IO(promise.future)).map: completed =>
      assertEquals(completed, 42)
