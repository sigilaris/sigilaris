package org.sigilaris.core.application.execution

import cats.MonadError
import scala.Tuple.++

import org.sigilaris.core.application.scheduling.ConflictFootprint
import org.sigilaris.core.failure.SigilarisFailure
import org.sigilaris.core.application.state.{Eff, StoreState}
import org.sigilaris.core.application.module.runtime.{
  RoutedStateReducer,
  StateModule,
  StateReducer,
}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.{ModuleRoutedTx, Signed, Tx}

/** Facade for executing transactions against a mounted
  * [[org.sigilaris.core.application.module.runtime.StateModule]].
  *
  * Typical usage previously required threading through `StateT.run` and
  * `EitherT.value`, which is verbose and obscures intent. This helper wraps the
  * pattern and offers both `Eff` and plain `F` results.
  */
object StateModuleExecutor:

  private type PlainModule[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple] =
    StateModule[F, Path, Owns, Needs, Txs, StateReducer[F, Path, Owns, Needs]]

  private type RoutedModule[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple] =
    StateModule[
      F,
      Path,
      Owns,
      Needs,
      Txs,
      RoutedStateReducer[F, Path, Owns, Needs],
    ]

  private def freshLogState(
      initial: StoreState,
  ): StoreState =
    StoreState.fromTrieState(initial.trieState)

  private def toExecution[Result, Event](
      nextState: StoreState,
      result: Result,
      events: List[Event],
  ): TxExecution[Result, Event] =
    TxExecution(
      nextTrieState = nextState.trieState,
      actualAccessLog = nextState.accessLog,
      actualFootprint = ConflictFootprint.fromAccessLog(nextState.accessLog),
      result = result,
      events = events,
    )

  /** Executes one transaction with a fresh access log over the current trie
    * state and returns the per-transaction execution witness.
    */
  def runExecutionWithModule[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
      module: PlainModule[F, Path, Owns, Needs, Txs],
  )(using
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][TxExecution[signedTx.value.Result, signedTx.value.Event]] =
    module.reducer
      .apply[T](signedTx)
      .run(freshLogState(initial))
      .map:
        case (nextState, (result, events)) =>
          toExecution(nextState, result, events)

  /** Executes a signed transaction against the supplied module. */
  def runWithModule[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
      module: PlainModule[F, Path, Owns, Needs, Txs],
  )(using
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runExecutionWithModule(initial, signedTx, module).map: execution =>
      execution.compatibilityTuple

  /** Legacy tuple wrapper over [[runWithModule]] using an implicit module. */
  def run[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
  )(using
      module: PlainModule[F, Path, Owns, Needs, Txs],
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runWithModule(initial, signedTx, module)

  /** Legacy tuple wrapper for routed execution against a composed module. */
  def runRoutedWithModule[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx & ModuleRoutedTx](
      initial: StoreState,
      signedTx: Signed[T],
      module: RoutedModule[F, Path, Owns, Needs, Txs],
  )(using
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runExecutionRoutedWithModule(initial, signedTx, module).map: execution =>
      execution.compatibilityTuple

  /** Executes a routed transaction with a fresh access log and returns the
    * per-transaction execution witness.
    */
  def runExecutionRoutedWithModule[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx & ModuleRoutedTx](
      initial: StoreState,
      signedTx: Signed[T],
      module: RoutedModule[F, Path, Owns, Needs, Txs],
  )(using
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][TxExecution[signedTx.value.Result, signedTx.value.Event]] =
    module.reducer
      .apply[T](signedTx)
      .run(freshLogState(initial))
      .map:
        case (nextState, (result, events)) =>
          toExecution(nextState, result, events)

  /** Legacy tuple wrapper over [[runRoutedWithModule]] using an implicit module. */
  def runRouted[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx & ModuleRoutedTx](
      initial: StoreState,
      signedTx: Signed[T],
  )(using
      module: RoutedModule[F, Path, Owns, Needs, Txs],
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runRoutedWithModule(initial, signedTx, module)

  /** Executes a transaction using an implicit module and returns the execution
    * witness.
    */
  def runExecution[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
  )(using
      module: PlainModule[F, Path, Owns, Needs, Txs],
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][TxExecution[signedTx.value.Result, signedTx.value.Event]] =
    runExecutionWithModule(initial, signedTx, module)

  /** Executes a routed transaction using an implicit module and returns the
    * execution witness.
    */
  def runExecutionRouted[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx & ModuleRoutedTx](
      initial: StoreState,
      signedTx: Signed[T],
  )(using
      module: RoutedModule[F, Path, Owns, Needs, Txs],
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][TxExecution[signedTx.value.Result, signedTx.value.Event]] =
    runExecutionRoutedWithModule(initial, signedTx, module)

  /** Runs from [[org.sigilaris.core.application.state.StoreState.empty]] for
    * common testing scenarios.
    */
  def runFromEmptyWithModule[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      signedTx: Signed[T],
      module: PlainModule[F, Path, Owns, Needs, Txs],
  )(using
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runWithModule(StoreState.empty, signedTx, module)

  /** Runs from empty state using an implicit module in scope. */
  def runFromEmpty[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      signedTx: Signed[T],
  )(using
      module: PlainModule[F, Path, Owns, Needs, Txs],
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runFromEmptyWithModule(signedTx, module)

  /** Runs from empty state with an explicit module and returns the execution
    * witness.
    */
  def runExecutionFromEmptyWithModule[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      signedTx: Signed[T],
      module: PlainModule[F, Path, Owns, Needs, Txs],
  )(using
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][TxExecution[signedTx.value.Result, signedTx.value.Event]] =
    runExecutionWithModule(StoreState.empty, signedTx, module)

  /** Runs from empty state using an implicit module and returns the execution
    * witness.
    */
  def runExecutionFromEmpty[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      signedTx: Signed[T],
  )(using
      module: PlainModule[F, Path, Owns, Needs, Txs],
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][TxExecution[signedTx.value.Result, signedTx.value.Event]] =
    runExecutionFromEmptyWithModule(signedTx, module)

  /** Convenience to obtain the plain `F` result for the legacy tuple wrapper. */
  def runValueWithModule[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
      module: PlainModule[F, Path, Owns, Needs, Txs],
  )(using
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): F[Either[
    SigilarisFailure,
    (StoreState, (signedTx.value.Result, List[signedTx.value.Event])),
  ]] =
    runWithModule(initial, signedTx, module).value

  /** Convenience to obtain the plain `F` result using an implicit module. */
  def runValue[F[
      _,
  ], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
  )(using
      module: PlainModule[F, Path, Owns, Needs, Txs],
      monadErrorF: MonadError[F, SigilarisFailure],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): F[Either[
    SigilarisFailure,
    (StoreState, (signedTx.value.Result, List[signedTx.value.Event])),
  ]] =
    run(initial, signedTx).value
