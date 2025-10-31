package org.sigilaris.core
package application
package support

import cats.Monad
import scala.Tuple.++

import failure.SigilarisFailure

/** Facade for executing transactions against a mounted [[StateModule]].
  *
  * Typical usage previously required threading through `StateT.run` and
  * `EitherT.value`, which is verbose and obscures intent. This helper wraps the
  * pattern and offers both `Eff` and plain `F` results.
  */
object StateModuleExecutor:

  private type PlainModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple] =
    StateModule[F, Path, Owns, Needs, Txs, StateReducer[F, Path, Owns, Needs]]

  private type RoutedModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple] =
    StateModule[F, Path, Owns, Needs, Txs, RoutedStateReducer[F, Path, Owns, Needs]]

  /** Executes a signed transaction against the supplied module. */
  def runWithModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
      module: PlainModule[F, Path, Owns, Needs, Txs],
  )(using
      @annotation.unused monadF: Monad[F],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    module.reducer.apply[T](signedTx).run(initial)

  /** Executes using an implicit module in scope. */
  def run[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
  )(using
      module: PlainModule[F, Path, Owns, Needs, Txs],
      @annotation.unused monadF: Monad[F],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runWithModule(initial, signedTx, module)

  /** Executes a routed transaction against a composed module. */
  def runRoutedWithModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx & ModuleRoutedTx](
      initial: StoreState,
      signedTx: Signed[T],
      module: RoutedModule[F, Path, Owns, Needs, Txs],
  )(using
      @annotation.unused monadF: Monad[F],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    module.reducer.apply[T](signedTx).run(initial)

  def runRouted[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx & ModuleRoutedTx](
      initial: StoreState,
      signedTx: Signed[T],
  )(using
      module: RoutedModule[F, Path, Owns, Needs, Txs],
      @annotation.unused monadF: Monad[F],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runRoutedWithModule(initial, signedTx, module)

  /** Runs from [[StoreState.empty]] for common testing scenarios. */
  def runFromEmptyWithModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      signedTx: Signed[T],
      module: PlainModule[F, Path, Owns, Needs, Txs],
  )(using
      @annotation.unused monadF: Monad[F],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runWithModule(StoreState.empty, signedTx, module)

  def runFromEmpty[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      signedTx: Signed[T],
  )(using
      module: PlainModule[F, Path, Owns, Needs, Txs],
      @annotation.unused monadF: Monad[F],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): Eff[F][(StoreState, (signedTx.value.Result, List[signedTx.value.Event]))] =
    runFromEmptyWithModule(signedTx, module)

  /** Convenience to obtain the plain `F` result. */
  def runValueWithModule[F[_]: Monad, Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
      module: PlainModule[F, Path, Owns, Needs, Txs],
  )(using
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): F[Either[SigilarisFailure, (StoreState, (signedTx.value.Result, List[signedTx.value.Event]))]] =
    runWithModule(initial, signedTx, module).value

  def runValue[F[_]: Monad, Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, T <: Tx](
      initial: StoreState,
      signedTx: Signed[T],
  )(using
      module: PlainModule[F, Path, Owns, Needs, Txs],
      readsReq: Requires[signedTx.value.Reads, Owns ++ Needs],
      writesReq: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): F[Either[SigilarisFailure, (StoreState, (signedTx.value.Result, List[signedTx.value.Event]))]] =
    run(initial, signedTx).value
