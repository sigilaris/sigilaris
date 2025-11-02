package org.sigilaris.core.application.module.runtime

import cats.Monad
import scala.Tuple.++

import org.sigilaris.core.application.module.blueprint.{ComposedBlueprint, ModuleBlueprint, SchemaInstantiation, SchemaMapper}
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.{StoreF, StoreState, Tables}
import org.sigilaris.core.application.support.compiletime.{PrefixFreePath, Requires, UniqueNames}
import org.sigilaris.core.application.transactions.model.{ModuleRoutedTx, Signed, Tx, TxRegistry}
import org.sigilaris.core.merkle.MerkleTrie

/** Path-bound state reducer for single modules.
  *
  * A StateReducer is a StateReducer0 bound to a specific Path. It knows exactly
  * where its tables are located in the trie.
  *
  * This is created during module mounting when a ModuleBlueprint is bound to a
  * path. It accepts any transaction type T <: Tx.
  *
  * Phase 5.5 update: Now explicitly models Owns and Needs like StateReducer0.
  *
  * @tparam F
  *   the effect type
  * @tparam Path
  *   the mount path tuple
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  */
trait StateReducer[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple]:
  /** Apply a signed transaction to produce a result and events.
    *
    * ADR-0012: All transactions must be wrapped in Signed[T] to ensure
    * cryptographic signatures are present at compile time.
    *
    * The reducer is polymorphic over the transaction type T, requiring only
    * that T's read and write requirements are satisfied by this reducer's
    * combined schema.
    *
    * @tparam T
    *   the transaction type
    * @param signedTx
    *   the signed transaction to apply (ADR-0012)
    * @param requiresReads
    *   evidence that T's read requirements are in Owns ++ Needs
    * @param requiresWrites
    *   evidence that T's write requirements are in Owns ++ Needs
    * @return
    *   a stateful computation returning the result and list of events
    */
  def apply[T <: Tx](signedTx: Signed[T])(using
      requiresReads: Requires[signedTx.value.Reads, Owns ++ Needs],
      requiresWrites: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])]

/** Path-bound routed state reducer for composed modules.
  *
  * A RoutedStateReducer is a RoutedStateReducer0 bound to a specific Path. It
  * REQUIRES all transactions to implement ModuleRoutedTx for routing.
  *
  * This is created during module mounting when a ComposedBlueprint is bound to
  * a path. The type bound T <: Tx & ModuleRoutedTx enforces the routing
  * requirement at compile time.
  *
  * Phase 5.5 update: Now explicitly models Owns and Needs like
  * RoutedStateReducer0.
  *
  * @tparam F
  *   the effect type
  * @tparam Path
  *   the mount path tuple
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  */
trait RoutedStateReducer[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple]:
  /** Apply a signed routed transaction to produce a result and events.
    *
    * ADR-0012: All transactions must be wrapped in Signed[T] to ensure
    * cryptographic signatures are present at compile time.
    *
    * The type bound T <: Tx & ModuleRoutedTx ensures that only transactions
    * implementing ModuleRoutedTx can be applied. This is enforced at compile
    * time.
    *
    * @tparam T
    *   the transaction type (must implement ModuleRoutedTx)
    * @param signedTx
    *   the signed transaction to apply (ADR-0012)
    * @param requiresReads
    *   evidence that T's read requirements are in Owns ++ Needs
    * @param requiresWrites
    *   evidence that T's write requirements are in Owns ++ Needs
    * @return
    *   a stateful computation returning the result and list of events
    */
  def apply[T <: Tx & ModuleRoutedTx](signedTx: Signed[T])(using
      requiresReads: Requires[signedTx.value.Reads, Owns ++ Needs],
      requiresWrites: Requires[signedTx.value.Writes, Owns ++ Needs],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])]

/** State module (path-bound).
  *
  * A StateModule is a deployed module at a specific path. It combines:
  *   - Path: the deployment location
  *   - Owns: the set of owned tables (now with computed prefixes)
  *   - Needs: the set of needed external tables
  *   - Reducer: the transaction processing logic (now path-aware)
  *   - Transactions: the set of supported transaction types
  *   - TablesProvider: provider for external table dependencies
  *
  * StateModule is generic in the reducer type R, which can be either:
  *   - StateReducer[F, Path, Owns, Needs] for single modules (accepts any Tx)
  *   - RoutedStateReducer[F, Path, Owns, Needs] for composed modules (requires
  *     ModuleRoutedTx)
  *
  * This preserves compile-time type safety throughout the entire stack.
  *
  * Phase 5.5 update: Schema split into Owns/Needs, Deps replaced by
  * TablesProvider.
  *
  * @tparam F
  *   the effect type
  * @tparam Path
  *   the mount path tuple
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  * @tparam Txs
  *   the transaction types tuple
  * @tparam R
  *   the reducer type (covariant)
  * @param tables
  *   the owned table instances (with prefixes bound to Path)
  * @param reducer
  *   the path-bound reducer
  * @param txs
  *   the transaction registry
  * @param tablesProvider
  *   the provider for external table dependencies
  * @param uniqueNames
  *   evidence that table names are unique within Owns
  * @param prefixFreePath
  *   evidence that all owned table prefixes are prefix-free
  */
final class StateModule[F[
    _,
], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, +R](
    val tables: Tables[F, Owns],
    val reducer: R,
    val txs: TxRegistry[Txs],
    val tablesProvider: TablesProvider[F, Needs],
)(using
    val uniqueNames: UniqueNames[Owns],
    val prefixFreePath: PrefixFreePath[Path, Owns],
)

object StateModule:
  /** Mount a single-module blueprint at a specific path, creating a
    * StateModule.
    *
    * This is the key operation in Phase 2: it takes a path-independent
    * ModuleBlueprint and binds it to a concrete path, computing table prefixes
    * and creating usable table instances.
    *
    * The mounting process:
    *   1. Computes the prefix for each owned table: encodePath(Path) ++
    *      encodeSegment(TableName) 2. Creates fresh StateTable instances bound
    *      to these prefixes 3. Wraps the reducer (StateReducer0) to create a
    *      path-aware StateReducer
    *
    * Each mount produces a completely independent set of tables, ensuring that
    * mounting the same blueprint at different paths results in isolated
    * keyspaces.
    *
    * Phase 5.5 update: Mounts Owns tables, passes through TablesProvider for
    * Needs.
    *
    * Type inference: Only Path needs to be specified explicitly. All other
    * types (F, MName, Owns, Needs, Txs) are inferred from the blueprint
    * parameter.
    *
    * @tparam Path
    *   the mount path (only type parameter that needs explicit specification)
    * @param blueprint
    *   the module blueprint to mount
    * @param monad
    *   the Monad instance for F (used by SchemaMapper derivation)
    * @param prefixFreePath
    *   evidence that the path+owns combination is prefix-free
    * @param nodeStore
    *   the MerkleTrie node store (used by SchemaMapper derivation)
    * @param schemaMapper
    *   the schema mapper for instantiating owned tables
    * @return
    *   a mounted state module with path-bound tables
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def mount[Path <: Tuple](
      blueprint: ModuleBlueprint[?, ?, ?, ?, ?],
  )(using
      @annotation.unused monad: Monad[blueprint.EffectType],
      prefixFreePath: PrefixFreePath[Path, blueprint.OwnsType],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[blueprint.EffectType],
      schemaMapper: SchemaMapper[blueprint.EffectType, Path, blueprint.OwnsType],
  ): StateModule[
    blueprint.EffectType,
    Path,
    blueprint.OwnsType,
    blueprint.NeedsType,
    blueprint.TxsType,
    StateReducer[
      blueprint.EffectType,
      Path,
      blueprint.OwnsType,
      blueprint.NeedsType,
    ],
  ] =
    type F[A]  = blueprint.EffectType[A]
    type MName = blueprint.ModuleName
    type Owns  = blueprint.OwnsType
    type Needs = blueprint.NeedsType
    type Txs   = blueprint.TxsType

    mountImpl[F, MName, Path, Owns, Needs, Txs]:
      blueprint.asInstanceOf[ModuleBlueprint[F, MName, Owns, Needs, Txs]]

  private def mountImpl[F[
      _,
  ], MName <: String, Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      blueprint: ModuleBlueprint[F, MName, Owns, Needs, Txs],
  )(using
      @annotation.unused monad: Monad[F],
      prefixFreePath: PrefixFreePath[Path, Owns],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[F],
      schemaMapper: SchemaMapper[F, Path, Owns],
  ): StateModule[F, Path, Owns, Needs, Txs, StateReducer[
    F,
    Path,
    Owns,
    Needs,
  ]] =
    val ownsTables: Tables[F, Owns] =
      SchemaInstantiation.instantiateTablesFromEntries[F, Path, Owns](
        blueprint.owns,
      )

    val pathBoundReducer = new StateReducer[F, Path, Owns, Needs]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Owns ++ Needs],
          requiresWrites: Requires[signedTx.value.Writes, Owns ++ Needs],
      ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] =
        blueprint.reducer0.apply(signedTx)(using
          requiresReads,
          requiresWrites,
          ownsTables,
          blueprint.provider,
        )

    new StateModule[
      F,
      Path,
      Owns,
      Needs,
      Txs,
      StateReducer[F, Path, Owns, Needs],
    ](
      tables = ownsTables,
      reducer = pathBoundReducer,
      txs = blueprint.txs,
      tablesProvider = blueprint.provider,
    )(using blueprint.uniqueNames, prefixFreePath)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def mountComposed[Path <: Tuple](
      blueprint: ComposedBlueprint[?, ?, ?, ?, ?],
  )(using
      @annotation.unused monad: Monad[blueprint.EffectType],
      prefixFreePath: PrefixFreePath[Path, blueprint.OwnsType],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[blueprint.EffectType],
      schemaMapper: SchemaMapper[blueprint.EffectType, Path, blueprint.OwnsType],
  ): StateModule[
    blueprint.EffectType,
    Path,
    blueprint.OwnsType,
    blueprint.NeedsType,
    blueprint.TxsType,
    RoutedStateReducer[
      blueprint.EffectType,
      Path,
      blueprint.OwnsType,
      blueprint.NeedsType,
    ],
  ] =
    type F[A]  = blueprint.EffectType[A]
    type MName = blueprint.ModuleName
    type Owns  = blueprint.OwnsType
    type Needs = blueprint.NeedsType
    type Txs   = blueprint.TxsType

    mountComposedImpl[F, MName, Path, Owns, Needs, Txs]:
      blueprint.asInstanceOf[ComposedBlueprint[F, MName, Owns, Needs, Txs]]

  private def mountComposedImpl[F[
      _,
  ], MName <: String, Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
      blueprint: ComposedBlueprint[F, MName, Owns, Needs, Txs],
  )(using
      @annotation.unused monad: Monad[F],
      prefixFreePath: PrefixFreePath[Path, Owns],
      @annotation.unused nodeStore: MerkleTrie.NodeStore[F],
      schemaMapper: SchemaMapper[F, Path, Owns],
  ): StateModule[F, Path, Owns, Needs, Txs, RoutedStateReducer[
    F,
    Path,
    Owns,
    Needs,
  ]] =
    val ownsTables: Tables[F, Owns] =
      SchemaInstantiation.instantiateTablesFromEntries[F, Path, Owns]:
        blueprint.owns

    val pathBoundReducer = new RoutedStateReducer[F, Path, Owns, Needs]:
      def apply[T <: Tx & ModuleRoutedTx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Owns ++ Needs],
          requiresWrites: Requires[signedTx.value.Writes, Owns ++ Needs],
      ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] =
        blueprint.reducer0.apply(signedTx)(using
          requiresReads,
          requiresWrites,
          ownsTables,
          blueprint.provider,
        )

    new StateModule[
      F,
      Path,
      Owns,
      Needs,
      Txs,
      RoutedStateReducer[F, Path, Owns, Needs],
    ](
      tables = ownsTables,
      reducer = pathBoundReducer,
      txs = blueprint.txs,
      tablesProvider = blueprint.provider,
    )(using blueprint.uniqueNames, prefixFreePath)

  def extend[F[_]
    : cats.Monad, Path <: Tuple, O1 <: Tuple, N1 <: Tuple, O2 <: Tuple, N2 <: Tuple, T1 <: Tuple, T2 <: Tuple, R1, R2](
      a: StateModule[F, Path, O1, N1, T1, R1],
      b: StateModule[F, Path, O2, N2, T2, R2],
  )(using
      uniqueNames: UniqueNames[O1 ++ O2],
      prefixFreePath: PrefixFreePath[Path, O1 ++ O2],
      disjointNeeds: TablesProvider.DisjointSchemas[N1, N2],
  ): StateModule[F, Path, O1 ++ O2, N1 ++ N2, T1 ++ T2, StateReducer[
    F,
    Path,
    O1 ++ O2,
    N1 ++ N2,
  ]] =
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    val mergedTables: Tables[F, O1 ++ O2] =
      (a.tables ++ b.tables).asInstanceOf[Tables[F, O1 ++ O2]]

    val mergedProvider: TablesProvider[F, N1 ++ N2] =
      TablesProvider.merge(a.tablesProvider, b.tablesProvider)(using
        disjointNeeds,
      )

    val mergedReducer = mergeReducers[F, Path, O1, N1, O2, N2](
      a.reducer.asInstanceOf[StateReducer[F, Path, O1, N1]],
      b.reducer.asInstanceOf[StateReducer[F, Path, O2, N2]],
    )

    val mergedTxs: TxRegistry[T1 ++ T2] = a.txs.combine(b.txs)

    new StateModule[
      F,
      Path,
      O1 ++ O2,
      N1 ++ N2,
      T1 ++ T2,
      StateReducer[F, Path, O1 ++ O2, N1 ++ N2],
    ](
      tables = mergedTables,
      reducer = mergedReducer,
      txs = mergedTxs,
      tablesProvider = mergedProvider,
    )(using uniqueNames, prefixFreePath)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def mergeReducers[F[_]
    : cats.Monad, Path <: Tuple, O1 <: Tuple, N1 <: Tuple, O2 <: Tuple, N2 <: Tuple](
      r1: StateReducer[F, Path, O1, N1],
      r2: StateReducer[F, Path, O2, N2],
  ): StateReducer[F, Path, O1 ++ O2, N1 ++ N2] =
    new StateReducer[F, Path, O1 ++ O2, N1 ++ N2]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, (O1 ++ O2) ++ (N1 ++ N2)],
          requiresWrites: Requires[signedTx.value.Writes, (O1 ++ O2) ++ (N1 ++ N2)],
      ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] =
        val r1Result = r1.apply(signedTx)(using
          requiresReads.asInstanceOf[Requires[signedTx.value.Reads, O1 ++ N1]],
          requiresWrites.asInstanceOf[Requires[signedTx.value.Writes, O1 ++ N1]],
        )

        import cats.data.StateT
        import cats.data.EitherT

        StateT: (s: StoreState) =>
          r1Result
            .run(s)
            .recoverWith: _ =>
              r2.apply(signedTx)(using
                requiresReads.asInstanceOf[Requires[signedTx.value.Reads, O2 ++ N2]],
                requiresWrites.asInstanceOf[Requires[signedTx.value.Writes, O2 ++ N2]],
              ).run(s)

  trait ModuleFactory[F[_], Owns <: Tuple, Txs <: Tuple]:
    def build[Path <: Tuple](using
        @annotation.unused monad: cats.Monad[F],
        prefixFreePath: PrefixFreePath[Path, Owns],
        @annotation.unused nodeStore: org.sigilaris.core.merkle.MerkleTrie.NodeStore[F],
        schemaMapper: SchemaMapper[F, Path, Owns],
    ): StateModule[F, Path, Owns, EmptyTuple, Txs, StateReducer[
      F,
      Path,
      Owns,
      EmptyTuple,
    ]]

  object ModuleFactory:
    def fromBlueprint[F[_], MName <: String, Owns <: Tuple, Txs <: Tuple](
        blueprint: ModuleBlueprint[F, MName, Owns, EmptyTuple, Txs],
    ): ModuleFactory[F, Owns, Txs] =
      new ModuleFactory[F, Owns, Txs]:
        def build[Path <: Tuple](using
            @annotation.unused monad: cats.Monad[F],
            prefixFreePath: PrefixFreePath[Path, Owns],
            @annotation.unused nodeStore: org.sigilaris.core.merkle.MerkleTrie.NodeStore[F],
            schemaMapper: SchemaMapper[F, Path, Owns],
        ): StateModule[F, Path, Owns, EmptyTuple, Txs, StateReducer[
          F,
          Path,
          Owns,
          EmptyTuple,
        ]] =
          StateModule.mount[Path](blueprint)
