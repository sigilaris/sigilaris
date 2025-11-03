package org.sigilaris.core.assembly

import cats.Monad
import org.sigilaris.core.merkle.MerkleTrie

import org.sigilaris.core.application.module.blueprint.{ComposedBlueprint, ModuleBlueprint, SchemaMapper}
import org.sigilaris.core.application.module.runtime.{RoutedStateReducer, StateModule, StateReducer}
import org.sigilaris.core.application.support.compiletime.PrefixFreePath
import org.sigilaris.core.application.transactions.ModuleId

/** High-level DSL for mounting blueprints at specific paths.
  *
  * Provides convenient methods for mounting module blueprints and composed blueprints
  * at paths, with automatic evidence requirements:
  *
  * Single-name mounting:
  *   - [[mount]] - mount a [[org.sigilaris.core.application.module.blueprint.ModuleBlueprint]]
  *   - [[mountComposed]] - mount a [[org.sigilaris.core.application.module.blueprint.ComposedBlueprint]]
  *
  * Multi-segment path mounting:
  *   - [[mountAtPath]] - mount ModuleBlueprint at arbitrary path
  *   - [[mountComposedAtPath]] - mount ComposedBlueprint at arbitrary path
  *
  * All methods require evidence that:
  *   - Path + Schema is prefix-free ([[org.sigilaris.core.application.support.compiletime.PrefixFreePath]])
  *   - Schema mapper can instantiate tables ([[org.sigilaris.core.application.module.blueprint.SchemaMapper]])
  *   - NodeStore is available for MerkleTrie operations
  *
  * @example {{{
  * import org.sigilaris.core.assembly.BlueprintDsl.*
  *
  * val module = mount("accounts" -> accountsBlueprint)
  * val composed = mountComposed("app" -> composedBlueprint)
  * }}}
  *
  * @see [[org.sigilaris.core.application.module.runtime.StateModule]]
  */
object BlueprintDsl:
  private type PlainModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple] =
    StateModule[F, Path, Owns, Needs, Txs, StateReducer[F, Path, Owns, Needs]]

  private type RoutedModule[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple] =
    StateModule[F, Path, Owns, Needs, Txs, RoutedStateReducer[F, Path, Owns, Needs]]

  /** Mount a module blueprint at a single-segment path.
    *
    * @tparam F the effect type
    * @tparam Name the mount path (singleton string literal)
    * @tparam MName the module name
    * @tparam Owns the owned schema tuple
    * @tparam Needs the needed schema tuple
    * @tparam Txs the transaction types tuple
    * @param binding tuple of (path name, blueprint)
    * @return a mounted StateModule with StateReducer
    */
  def mount[F[_], Name <: String & Singleton, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      binding: (Name, ModuleBlueprint[F, MName, Owns, Needs, Txs])
  )(using
      Monad[F],
      PrefixFreePath[Name *: EmptyTuple, Owns],
      MerkleTrie.NodeStore[F],
      SchemaMapper[F, Name *: EmptyTuple, Owns],
  ): PlainModule[F, Name *: EmptyTuple, Owns, Needs, Txs] =
    StateModule.mount[Name *: EmptyTuple](binding._2)

  /** Mount a module blueprint at a multi-segment path.
    *
    * @tparam F the effect type
    * @tparam Path the mount path tuple (e.g., ("app", "v1"))
    * @tparam MName the module name
    * @tparam Owns the owned schema tuple
    * @tparam Needs the needed schema tuple
    * @tparam Txs the transaction types tuple
    * @param binding tuple of (path tuple, blueprint)
    * @return a mounted StateModule with StateReducer
    */
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

  /** Mount a composed blueprint at a single-segment path.
    *
    * Composed blueprints require transactions with ModuleRoutedTx for routing.
    *
    * @tparam F the effect type
    * @tparam Name the mount path (singleton string literal)
    * @tparam MName the module name
    * @tparam Owns the owned schema tuple
    * @tparam Needs the needed schema tuple
    * @tparam Txs the transaction types tuple
    * @param binding tuple of (path name, composed blueprint)
    * @return a mounted StateModule with RoutedStateReducer
    */
  def mountComposed[F[_], Name <: String & Singleton, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      binding: (Name, ComposedBlueprint[F, MName, Owns, Needs, Txs])
  )(using
      Monad[F],
      PrefixFreePath[Name *: EmptyTuple, Owns],
      MerkleTrie.NodeStore[F],
      SchemaMapper[F, Name *: EmptyTuple, Owns],
  ): RoutedModule[F, Name *: EmptyTuple, Owns, Needs, Txs] =
    StateModule.mountComposed[Name *: EmptyTuple](binding._2)

  /** Mount a composed blueprint at a multi-segment path.
    *
    * Composed blueprints require transactions with ModuleRoutedTx for routing.
    *
    * @tparam F the effect type
    * @tparam Path the mount path tuple (e.g., ("app", "v1"))
    * @tparam MName the module name
    * @tparam Owns the owned schema tuple
    * @tparam Needs the needed schema tuple
    * @tparam Txs the transaction types tuple
    * @param binding tuple of (path tuple, composed blueprint)
    * @return a mounted StateModule with RoutedStateReducer
    */
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
