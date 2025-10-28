package org.sigilaris.core
package application

import cats.Monad
import cats.data.{EitherT, StateT}
import cats.syntax.eq.*

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

  /** The Entry instances for owned tables (runtime values that can create tables). */
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
) extends Blueprint[F, MName, Owns, Needs, Txs, RoutedStateReducer0[F, Owns, Needs]]

object Blueprint:
  /** Concatenate two tuples into a flat result.
    *
    * This helper ensures that tuple concatenation at runtime matches the
    * type-level Tuple.Concat semantics. Without this, naive pairing like `(a,
    * b)` creates nested tuples that break table lookups and evidence.
    *
    * @tparam A
    *   the first tuple type
    * @tparam B
    *   the second tuple type
    * @param a
    *   the first tuple
    * @param b
    *   the second tuple
    * @return
    *   a flat concatenated tuple of type A ++ B
    */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def tupleConcat[A <: Tuple, B <: Tuple](a: A, b: B): A ++ B =
    (a ++ b).asInstanceOf[A ++ B]

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
    * Phase 5.5 SAFETY: Both blueprints MUST have Needs = EmptyTuple.
    * This is enforced via compile-time evidence (=:= constraints).
    * Provider merge strategy for Needs ≠ EmptyTuple is deferred to Phase 5.6.
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
    *   the first blueprint (must have Needs = EmptyTuple)
    * @param b
    *   the second blueprint (must have Needs = EmptyTuple)
    * @param uniqueNames
    *   evidence that combined owned schema has unique names
    * @param needsEmpty1
    *   evidence that first blueprint has no external dependencies
    * @param needsEmpty2
    *   evidence that second blueprint has no external dependencies
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
      needsEmpty1: a.NeedsType =:= EmptyTuple,
      needsEmpty2: b.NeedsType =:= EmptyTuple,
  ): ComposedBlueprint[
    F,
    MOut,
    a.OwnsType ++ b.OwnsType,
    EmptyTuple, // Phase 5.5: Needs = EmptyTuple enforced by compile-time constraints
    a.TxsType ++ b.TxsType,
  ] =
    type M1 = a.ModuleName
    type O1 = a.OwnsType
    type T1 = a.TxsType
    type M2 = b.ModuleName
    type O2 = b.OwnsType
    type T2 = b.TxsType

    type CombinedOwns = O1 ++ O2
    val uniqueNames: UniqueNames[CombinedOwns] = uniqueNames0

    // SAFETY: The =:= constraints (needsEmpty1, needsEmpty2) prove at compile-time that
    // a.NeedsType =:= EmptyTuple and b.NeedsType =:= EmptyTuple. The asInstanceOf is safe
    // because the type checker has already verified the constraint. Without these constraints,
    // this function would not compile when called with non-empty Needs.
    composeBlueprintImpl[F, MOut, M1, O1, T1, M2, O2, T2](
      a.asInstanceOf[ModuleBlueprint[F, M1, O1, EmptyTuple, T1]],
      b.asInstanceOf[ModuleBlueprint[F, M2, O2, EmptyTuple, T2]],
    )(using monadF, moduleOut, uniqueNames)

  private def composeBlueprintImpl[F[
      _,
  ], MOut <: String, M1 <: String, O1 <: Tuple, T1 <: Tuple, M2 <: String, O2 <: Tuple, T2 <: Tuple](
      a: ModuleBlueprint[F, M1, O1, EmptyTuple, T1],
      b: ModuleBlueprint[F, M2, O2, EmptyTuple, T2],
  )(using
      monadF: Monad[F],
      moduleOut: ValueOf[MOut],
      uniqueNames: UniqueNames[O1 ++ O2],
  ): ComposedBlueprint[F, MOut, O1 ++ O2, EmptyTuple, T1 ++ T2] =
    given Monad[F]              = monadF
    given UniqueNames[O1 ++ O2] = uniqueNames
    given ValueOf[MOut]         = moduleOut

    val m1Name: String = a.moduleValue.value
    val m2Name: String = b.moduleValue.value

    // Combine owned tables from both modules
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    val combinedOwns: O1 ++ O2 = (a.owns ++ b.owns).asInstanceOf[O1 ++ O2]

    // Phase 5.5 SAFETY: Both modules have Needs = EmptyTuple (enforced by signature)
    // Provider merge strategy for Needs ≠ EmptyTuple is deferred to Phase 5.6

    val routedReducer = new RoutedStateReducer0[F, O1 ++ O2, EmptyTuple]:
      @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
      def apply[T <: Tx & ModuleRoutedTx](tx: T)(using
          requiresReads: Requires[tx.Reads, (O1 ++ O2) ++ EmptyTuple],
          requiresWrites: Requires[tx.Writes, (O1 ++ O2) ++ EmptyTuple],
          ownsTables: Tables[F, O1 ++ O2],
          provider: TablesProvider[F, EmptyTuple],
      ): StoreF[F][(tx.Result, List[tx.Event])] =
        val pathHead = tx.moduleId.path.head.asInstanceOf[String]

        if pathHead === m1Name then
          // Route to first module - SAFE because Needs = EmptyTuple (enforced by signature)
          // So O1 ++ EmptyTuple is the schema for module a
          a.reducer0.apply(tx)(using
            requiresReads.asInstanceOf[Requires[tx.Reads, O1 ++ EmptyTuple]],
            requiresWrites.asInstanceOf[Requires[tx.Writes, O1 ++ EmptyTuple]],
            ownsTables.asInstanceOf[Tables[F, O1]],
            a.provider, // This is TablesProvider.empty since Needs = EmptyTuple
          )
        else if pathHead === m2Name then
          // Route to second module - SAFE because Needs = EmptyTuple (enforced by signature)
          // So O2 ++ EmptyTuple is the schema for module b
          b.reducer0.apply(tx)(using
            requiresReads.asInstanceOf[Requires[tx.Reads, O2 ++ EmptyTuple]],
            requiresWrites.asInstanceOf[Requires[tx.Writes, O2 ++ EmptyTuple]],
            ownsTables.asInstanceOf[Tables[F, O2]],
            b.provider, // This is TablesProvider.empty since Needs = EmptyTuple
          )
        else
          val msg1: String = m1Name
          val msg2: String = m2Name
          StateT.liftF:
            EitherT.leftT[F, (tx.Result, List[tx.Event])]:
              RoutingFailure:
                ss"TxRouteMissing: module '$pathHead' does not match '$msg1' or '$msg2'"

    val combinedTxs: TxRegistry[T1 ++ T2] = a.txs.combine(b.txs)

    new ComposedBlueprint[F, MOut, O1 ++ O2, EmptyTuple, T1 ++ T2](
      owns = combinedOwns,
      reducer0 = routedReducer,
      txs = combinedTxs,
      provider = TablesProvider.empty[F],
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
    StateReducer[blueprint.EffectType, Base ++ Sub, blueprint.OwnsType, blueprint.NeedsType],
  ] =
    StateModule.mount[Base ++ Sub](blueprint)
