package org.sigilaris.core
package application

import cats.Monad
import cats.data.{EitherT, StateT}
import cats.syntax.eq.*
import scala.Tuple.++

import failure.RoutingFailure
import merkle.MerkleTrie.NodeStore
import util.SafeStringInterp.ss

/** Path-agnostic state reducer.
  *
  * A StateReducer0 operates on transactions without knowledge of where it will
  * be mounted. The Path is unknown at this stage - it will be bound when the
  * blueprint is mounted into a StateModule.
  *
  * This allows writing reusable module logic that can be deployed at different
  * paths in different applications.
  *
  * Phase 5.5 update: StateReducer0 now explicitly models Owns and Needs:
  *   - Owns: tables that this module owns (will be created at mount time)
  *   - Needs: tables that this module needs from external providers
  *   - Combined schema (Owns ++ Needs) is what transactions operate over
  *
  * @tparam F
  *   the effect type
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  */
trait StateReducer0[F[_], Owns <: Tuple, Needs <: Tuple]:
  /** Apply a transaction to produce a result and events.
    *
    * The reducer is polymorphic over the transaction type T, requiring only
    * that T's read and write requirements are satisfied by this reducer's
    * combined schema (Owns ++ Needs).
    *
    * The reducer receives:
    *   - ownsTables: Tables[F, Owns] - tables owned by this module
    *   - provider: TablesProvider[F, Needs] - provider for external tables
    *
    * @tparam T
    *   the transaction type
    * @param tx
    *   the transaction to apply
    * @param requiresReads
    *   evidence that T's read requirements are in Owns ++ Needs
    * @param requiresWrites
    *   evidence that T's write requirements are in Owns ++ Needs
    * @param ownsTables
    *   the owned tables
    * @param provider
    *   the provider for needed external tables
    * @return
    *   a stateful computation returning the result and list of events
    */
  def apply[T <: Tx](tx: T)(using
      requiresReads: Requires[tx.Reads, Owns ++ Needs],
      requiresWrites: Requires[tx.Writes, Owns ++ Needs],
      ownsTables: Tables[F, Owns],
      provider: TablesProvider[F, Needs],
  ): StoreF[F][(tx.Result, List[tx.Event])]

/** Routed state reducer requiring ModuleRoutedTx.
  *
  * This is a wrapper that implements StateReducer0 but only works correctly
  * with transactions that implement ModuleRoutedTx. It is used in composed
  * blueprints where routing based on module path is required.
  *
  * The user is expected to only pass ModuleRoutedTx transactions to composed
  * blueprints. Non-routed transactions will fail at runtime with a cast
  * exception, which is acceptable since the API contract (composition) requires
  * routing.
  *
  * Phase 5.5 update: Like StateReducer0, now explicitly models Owns and Needs.
  *
  * @tparam F
  *   the effect type
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  */
trait RoutedStateReducer0[F[_], Owns <: Tuple, Needs <: Tuple]:
  /** Apply a routed transaction to produce a result and events.
    *
    * The type bound T <: Tx & ModuleRoutedTx ensures that only transactions
    * implementing ModuleRoutedTx can be applied. This is enforced at compile
    * time.
    *
    * @tparam T
    *   the transaction type (must implement ModuleRoutedTx)
    * @param tx
    *   the transaction to apply
    * @param requiresReads
    *   evidence that T's read requirements are in Owns ++ Needs
    * @param requiresWrites
    *   evidence that T's write requirements are in Owns ++ Needs
    * @param ownsTables
    *   the owned tables
    * @param provider
    *   the provider for needed external tables
    * @return
    *   a stateful computation returning the result and list of events
    */
  def apply[T <: Tx & ModuleRoutedTx](tx: T)(using
      requiresReads: Requires[tx.Reads, Owns ++ Needs],
      requiresWrites: Requires[tx.Writes, Owns ++ Needs],
      ownsTables: Tables[F, Owns],
      provider: TablesProvider[F, Needs],
  ): StoreF[F][(tx.Result, List[tx.Event])]

/** Base trait for blueprints (path-independent).
  *
  * A blueprint is a module specification without a concrete deployment path.
  * The base trait is covariant in the reducer type, allowing both single-module
  * blueprints (StateReducer0) and composed blueprints (RoutedStateReducer0) to
  * be treated uniformly where appropriate.
  *
  * Phase 5.5 update: Schema is now split into Owns and Needs:
  *   - Owns: tables that this module owns and will create
  *   - Needs: tables that this module needs from external providers
  *   - Deps is replaced by TablesProvider[F, Needs]
  *
  * @tparam F
  *   the effect type
  * @tparam MName
  *   the module name (literal String type)
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  * @tparam Txs
  *   the transaction types tuple
  * @tparam R
  *   the reducer type (covariant)
  */
sealed trait Blueprint[F[
    _,
], MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, +R]:
  type EffectType[A] = F[A]
  type ModuleName    = MName
  type OwnsType      = Owns
  type NeedsType     = Needs
  type TxsType       = Txs

  /** The Entry instances for owned tables (runtime values that can create
    * tables).
    */
  def owns: Owns

  /** The path-agnostic reducer. */
  def reducer0: R

  /** The transaction registry. */
  def txs: TxRegistry[Txs]

  /** The tables provider for needed external tables. */
  def provider: TablesProvider[F, Needs]

  /** Evidence that table names are unique within Owns.
    *
    * Phase 5.5: We only check uniqueness of owned tables. External tables
    * (Needs) are provided by TablesProvider and their uniqueness is the
    * responsibility of the provider module.
    */
  def uniqueNames: UniqueNames[Owns]

  /** Literal module name for this blueprint. */
  def moduleValue: ValueOf[MName]

/** Single-module blueprint (path-independent).
  *
  * A module blueprint is a specification for a single, self-contained module.
  * It uses StateReducer0, which can process any transaction type (T <: Tx) that
  * satisfies the schema requirements.
  *
  * Module blueprints are designed with the assumption that they don't know
  * where they will be deployed. The mounting process (Phase 2) binds a
  * blueprint to a specific Path, computing prefixes and using Entry instances
  * to create concrete StateTable instances.
  *
  * IMPORTANT: Blueprint contains Entry instances (runtime values), not
  * StateTable instances. Each Entry can create a StateTable when given a
  * prefix.
  *
  * Phase 5.5 update: Now explicitly models Owns and Needs:
  *   - owns: Entry tuple for tables this module will create
  *   - provider: TablesProvider for external tables this module needs
  *   - reducer0: operates on Owns ++ Needs combined schema
  *
  * @tparam F
  *   the effect type
  * @tparam MName
  *   the module name (literal String type)
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  * @tparam Txs
  *   the transaction types tuple
  * @param owns
  *   the Entry instances for owned tables (runtime values)
  * @param reducer0
  *   the path-agnostic reducer (accepts any Tx)
  * @param txs
  *   the transaction registry
  * @param provider
  *   the tables provider for needed external tables
  * @param uniqueNames
  *   evidence that table names are unique within Owns
  */
final class ModuleBlueprint[F[
    _,
], MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    val owns: Owns, // Runtime tuple of Entry instances for owned tables
    val reducer0: StateReducer0[F, Owns, Needs],
    val txs: TxRegistry[Txs],
    val provider: TablesProvider[F, Needs],
)(using
    val uniqueNames: UniqueNames[Owns],
    val moduleValue: ValueOf[MName],
) extends Blueprint[F, MName, Owns, Needs, Txs, StateReducer0[F, Owns, Needs]]

/** Composed blueprint (path-independent).
  *
  * A composed blueprint is the result of combining two or more module
  * blueprints. It uses RoutedStateReducer0, which REQUIRES all transactions to
  * implement ModuleRoutedTx for routing purposes.
  *
  * The type system enforces this requirement at compile time: attempting to
  * apply a non-routed transaction to a ComposedBlueprint's reducer will be a
  * compile error.
  *
  * Phase 5.5 update: Now explicitly models Owns and Needs like ModuleBlueprint.
  *
  * @tparam F
  *   the effect type
  * @tparam MName
  *   the module name (literal String type)
  * @tparam Owns
  *   the owned schema tuple (tuple of Entry types)
  * @tparam Needs
  *   the needed schema tuple (tuple of Entry types)
  * @tparam Txs
  *   the transaction types tuple
  * @param owns
  *   the Entry instances for owned tables (runtime values)
  * @param reducer0
  *   the routed reducer (requires ModuleRoutedTx)
  * @param txs
  *   the transaction registry
  * @param provider
  *   the tables provider for needed external tables
  * @param uniqueNames
  *   evidence that table names are unique within Owns
  */
final class ComposedBlueprint[F[
    _,
], MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    val owns: Owns,
    val reducer0: RoutedStateReducer0[F, Owns, Needs],
    val txs: TxRegistry[Txs],
    val provider: TablesProvider[F, Needs],
)(using
    val uniqueNames: UniqueNames[Owns],
    val moduleValue: ValueOf[MName],
) extends Blueprint[
      F,
      MName,
      Owns,
      Needs,
      Txs,
      RoutedStateReducer0[F, Owns, Needs],
    ]

object Blueprint:
  /** Compose two blueprints into a single composed blueprint.
    *
    * This is the core operation for Phase 3: combining two independent module
    * blueprints into a single ComposedBlueprint with:
    *   - Unioned owned schemas (O1 ++ O2)
    *   - Unioned transaction sets (T1 ++ T2)
    *   - Merged reducer logic with module-based routing
    *
    * The composed blueprint requires evidence that the combined schema has
    * unique table names (UniqueNames[O1 ++ O2]).
    *
    * Reducer routing strategy:
    *   - Transactions MUST implement ModuleRoutedTx (enforced by
    *     RoutedStateReducer0 type)
    *   - The moduleId.path is module-relative (MName *: SubPath)
    *   - The reducer routes based on the first segment matching M1 or M2
    *   - Type safety is compile-time: non-routed transactions will not compile
    *
    * IMPORTANT: ModuleId is always module-relative. When a blueprint is mounted
    * at a path, the mount path is NOT prepended to transaction moduleIds. Full
    * paths (mountPath ++ moduleId.path) are only constructed at system edges
    * for telemetry or logging.
    *
    * Phase 5.6 UPGRADE: Now supports modules with non-empty Needs. The providers
    * are merged using TablesProvider.merge, which requires that the dependency
    * schemas are disjoint (DisjointSchemas[N1, N2]). This prevents ambiguous
    * table lookups while allowing flexible composition of dependent modules.
    *
    * @tparam F
    *   the effect type
    * @tparam MOut
    *   the output module name
    * @tparam M1
    *   first module name
    * @tparam O1
    *   first owned schema tuple
    * @tparam T1
    *   first transaction types tuple
    * @tparam M2
    *   second module name
    * @tparam O2
    *   second owned schema tuple
    * @tparam T2
    *   second transaction types tuple
    * @param a
    *   the first blueprint
    * @param b
    *   the second blueprint
    * @param uniqueNames
    *   evidence that combined owned schema has unique names
    * @param disjointNeeds
    *   evidence that dependency schemas are disjoint (Phase 5.6)
    * @return
    *   a ComposedBlueprint with RoutedStateReducer0
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def composeBlueprint[F[_], MOut <: String](
      a: ModuleBlueprint[F, ?, ?, ?, ?],
      b: ModuleBlueprint[F, ?, ?, ?, ?],
  )(using
      monadF: cats.Monad[F],
      moduleOut: ValueOf[MOut],
      uniqueNames0: UniqueNames[a.OwnsType ++ b.OwnsType],
      disjointNeeds: TablesProvider.DisjointSchemas[a.NeedsType, b.NeedsType],
  ): ComposedBlueprint[
    F,
    MOut,
    a.OwnsType ++ b.OwnsType,
    a.NeedsType ++ b.NeedsType, // Phase 5.6: Merged Needs from both blueprints
    a.TxsType ++ b.TxsType,
  ] =
    type M1 = a.ModuleName
    type O1 = a.OwnsType
    type N1 = a.NeedsType
    type T1 = a.TxsType
    type M2 = b.ModuleName
    type O2 = b.OwnsType
    type N2 = b.NeedsType
    type T2 = b.TxsType

    type CombinedOwns = O1 ++ O2
    val uniqueNames: UniqueNames[CombinedOwns] = uniqueNames0

    composeBlueprintImpl[F, MOut, M1, O1, N1, T1, M2, O2, N2, T2](
      a.asInstanceOf[ModuleBlueprint[F, M1, O1, N1, T1]],
      b.asInstanceOf[ModuleBlueprint[F, M2, O2, N2, T2]],
    )(using monadF, moduleOut, uniqueNames, disjointNeeds)

  private def composeBlueprintImpl[F[
      _,
  ], MOut <: String, M1 <: String, O1 <: Tuple, N1 <: Tuple, T1 <: Tuple, M2 <: String, O2 <: Tuple, N2 <: Tuple, T2 <: Tuple](
      a: ModuleBlueprint[F, M1, O1, N1, T1],
      b: ModuleBlueprint[F, M2, O2, N2, T2],
  )(using
      monadF: Monad[F],
      moduleOut: ValueOf[MOut],
      uniqueNames: UniqueNames[O1 ++ O2],
      disjointNeeds: TablesProvider.DisjointSchemas[N1, N2],
  ): ComposedBlueprint[F, MOut, O1 ++ O2, N1 ++ N2, T1 ++ T2] =
    given Monad[F]              = monadF
    given UniqueNames[O1 ++ O2] = uniqueNames
    given ValueOf[MOut]         = moduleOut

    val m1Name: String = a.moduleValue.value
    val m2Name: String = b.moduleValue.value

    // Phase 5.6: Merge providers from both modules
    // DisjointSchemas evidence ensures no ambiguous table lookups
    val mergedProvider: TablesProvider[F, N1 ++ N2] =
      TablesProvider.merge(a.provider, b.provider)(using disjointNeeds)

    val routedReducer = new RoutedStateReducer0[F, O1 ++ O2, N1 ++ N2]:
      @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
      def apply[T <: Tx & ModuleRoutedTx](tx: T)(using
          requiresReads: Requires[tx.Reads, (O1 ++ O2) ++ (N1 ++ N2)],
          requiresWrites: Requires[tx.Writes, (O1 ++ O2) ++ (N1 ++ N2)],
          ownsTables: Tables[F, O1 ++ O2],
          provider: TablesProvider[F, N1 ++ N2],
      ): StoreF[F][(tx.Result, List[tx.Event])] =
        val pathHead = tx.moduleId.path.head.asInstanceOf[String]

        if pathHead === m1Name then
          // Route to first module
          // SAFETY: We need to narrow the merged provider to just N1 for module a.
          // The provider parameter contains N1 ++ N2, and we extract just N1.
          // We use asInstanceOf for the provider since N1 is a subset of N1 ++ N2.
          a.reducer0.apply(tx)(using
            requiresReads.asInstanceOf[Requires[tx.Reads, O1 ++ N1]],
            requiresWrites.asInstanceOf[Requires[tx.Writes, O1 ++ N1]],
            ownsTables.asInstanceOf[Tables[F, O1]],
            provider.asInstanceOf[TablesProvider[F, N1]], // Extract subset for module a
          )
        else if pathHead === m2Name then
          // Route to second module
          // SAFETY: We need to narrow the merged provider to just N2 for module b.
          // The provider parameter contains N1 ++ N2, and we extract just N2.
          b.reducer0.apply(tx)(using
            requiresReads.asInstanceOf[Requires[tx.Reads, O2 ++ N2]],
            requiresWrites.asInstanceOf[Requires[tx.Writes, O2 ++ N2]],
            ownsTables.asInstanceOf[Tables[F, O2]],
            provider.asInstanceOf[TablesProvider[F, N2]], // Extract subset for module b
          )
        else
          val msg1: String = m1Name
          val msg2: String = m2Name
          StateT.liftF:
            EitherT.leftT[F, (tx.Result, List[tx.Event])]:
              RoutingFailure:
                ss"TxRouteMissing: module '$pathHead' does not match '$msg1' or '$msg2'"

    val combinedTxs: TxRegistry[T1 ++ T2] = a.txs.combine(b.txs)

    new ComposedBlueprint[F, MOut, O1 ++ O2, N1 ++ N2, T1 ++ T2](
      owns = a.owns ++ b.owns,
      reducer0 = routedReducer,
      txs = combinedTxs,
      provider = mergedProvider, // Phase 5.6: Use merged provider
    )

  /** Mount a blueprint at a path composed from base and sub paths.
    *
    * This is a convenience helper for Phase 3 that simplifies mounting a
    * blueprint at a nested path.
    *
    * Type inference: Only Base and Sub need to be specified explicitly. All
    * other types (F, MName, Owns, Needs, Txs) are inferred from the blueprint
    * parameter.
    *
    * @tparam Base
    *   the base path tuple (needs explicit specification)
    * @tparam Sub
    *   the sub-path tuple (needs explicit specification)
    * @param blueprint
    *   the blueprint to mount
    * @param monad
    *   the Monad instance for F
    * @param prefixFreePath
    *   evidence that Base ++ Sub with Owns is prefix-free
    * @param nodeStore
    *   the MerkleTrie node store
    * @param schemaMapper
    *   the schema mapper for instantiating tables
    * @return
    *   a state module mounted at Base ++ Sub
    */
  def mountAt[Base <: Tuple, Sub <: Tuple](
      blueprint: ModuleBlueprint[?, ?, ?, ?, ?],
  )(using
      @annotation.unused monad: Monad[blueprint.EffectType],
      prefixFreePath: PrefixFreePath[Base ++ Sub, blueprint.OwnsType],
      @annotation.unused nodeStore: NodeStore[blueprint.EffectType],
      schemaMapper: SchemaMapper[
        blueprint.EffectType,
        Base ++ Sub,
        blueprint.OwnsType,
      ],
  ): StateModule[
    blueprint.EffectType,
    Base ++ Sub,
    blueprint.OwnsType,
    blueprint.NeedsType,
    blueprint.TxsType,
    StateReducer[
      blueprint.EffectType,
      Base ++ Sub,
      blueprint.OwnsType,
      blueprint.NeedsType,
    ],
  ] =
    StateModule.mount[Base ++ Sub](blueprint)
