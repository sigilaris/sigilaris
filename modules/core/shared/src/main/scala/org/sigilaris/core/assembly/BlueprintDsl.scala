package org.sigilaris.core.assembly

import cats.Monad
import org.sigilaris.core.merkle.MerkleTrie

import org.sigilaris.core.application.module.{
  ComposedBlueprint,
  ModuleBlueprint,
  RoutedStateReducer,
  SchemaMapper,
  StateModule,
  StateReducer,
}
import org.sigilaris.core.application.support.PrefixFreePath
import org.sigilaris.core.application.transactions.ModuleId

object BlueprintDsl:
  private type PlainModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple] =
    StateModule[F, Path, Owns, Needs, Txs, StateReducer[F, Path, Owns, Needs]]

  private type RoutedModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple] =
    StateModule[F, Path, Owns, Needs, Txs, RoutedStateReducer[F, Path, Owns, Needs]]

  def mount[F[_], Name <: String & Singleton, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      binding: (Name, ModuleBlueprint[F, MName, Owns, Needs, Txs])
  )(using
      Monad[F],
      PrefixFreePath[Name *: EmptyTuple, Owns],
      MerkleTrie.NodeStore[F],
      SchemaMapper[F, Name *: EmptyTuple, Owns],
  ): PlainModule[F, Name *: EmptyTuple, Owns, Needs, Txs] =
    StateModule.mount[Name *: EmptyTuple](binding._2)

  def mountAtPath[F[_], Path <: Tuple, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      binding: (Path, ModuleBlueprint[F, MName, Owns, Needs, Txs])
  )(using
      ModuleId.StringTuple[Path],
      Monad[F],
      PrefixFreePath[Path, Owns],
      MerkleTrie.NodeStore[F],
      SchemaMapper[F, Path, Owns],
  ): PlainModule[F, Path, Owns, Needs, Txs] =
    StateModule.mount[Path](binding._2)

  def mountComposed[F[_], Name <: String & Singleton, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      binding: (Name, ComposedBlueprint[F, MName, Owns, Needs, Txs])
  )(using
      Monad[F],
      PrefixFreePath[Name *: EmptyTuple, Owns],
      MerkleTrie.NodeStore[F],
      SchemaMapper[F, Name *: EmptyTuple, Owns],
  ): RoutedModule[F, Name *: EmptyTuple, Owns, Needs, Txs] =
    StateModule.mountComposed[Name *: EmptyTuple](binding._2)

  def mountComposedAtPath[F[_], Path <: Tuple, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      binding: (Path, ComposedBlueprint[F, MName, Owns, Needs, Txs])
  )(using
      ModuleId.StringTuple[Path],
      Monad[F],
      PrefixFreePath[Path, Owns],
      MerkleTrie.NodeStore[F],
      SchemaMapper[F, Path, Owns],
  ): RoutedModule[F, Path, Owns, Needs, Txs] =
    StateModule.mountComposed[Path](binding._2)
