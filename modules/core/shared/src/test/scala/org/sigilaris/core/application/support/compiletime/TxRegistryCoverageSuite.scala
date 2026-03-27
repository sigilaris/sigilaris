package org.sigilaris.core.application.support.compiletime

import munit.FunSuite

import org.sigilaris.core.application.transactions.{ReducerCoverage, Tx, TxRegistry}

class TxRegistryCoverageSuite extends FunSuite:
  final case class CoveredTx() extends Tx:
    type Reads = EmptyTuple
    type Writes = EmptyTuple
    type Result = Unit
    type Event = Unit

  given ReducerCoverage[CoveredTx] with {}

  test("TxRegistry.of compiles when all registered transactions have coverage"):
    val registry = TxRegistry.of[CoveredTx *: EmptyTuple]
    assert(registry ne null)

  test("TxRegistry.of fails to compile when coverage is missing"):
    val errors = compileErrors("""
      import _root_.org.sigilaris.core.application.transactions.{ReducerCoverage, Tx, TxRegistry}

      final case class CoveredTx() extends Tx:
        type Reads = EmptyTuple
        type Writes = EmptyTuple
        type Result = Unit
        type Event = Unit

      final case class MissingTx() extends Tx:
        type Reads = EmptyTuple
        type Writes = EmptyTuple
        type Result = Unit
        type Event = Unit

      given ReducerCoverage[CoveredTx] with {}

      val registry = TxRegistry.of[CoveredTx *: MissingTx *: EmptyTuple]
    """)

    assert(clue(errors).contains("ReducerCoverage"))
    assert(clue(errors).contains("MissingTx"))
