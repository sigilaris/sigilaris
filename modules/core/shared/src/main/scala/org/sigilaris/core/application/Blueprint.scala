package org.sigilaris.core
package application

/** Path-agnostic state reducer.
  *
  * A StateReducer0 operates on transactions without knowledge of where it will
  * be mounted. The Path is unknown at this stage - it will be bound when the
  * blueprint is mounted into a StateModule.
  *
  * This allows writing reusable module logic that can be deployed at different
  * paths in different applications.
  *
  * @tparam F the effect type
  * @tparam Schema the schema tuple (tuple of Entry types)
  */
trait StateReducer0[F[_], Schema <: Tuple]:
  /** Apply a transaction to produce a result and events.
    *
    * The reducer is polymorphic over the transaction type T, requiring only
    * that T's read and write requirements are satisfied by this reducer's Schema.
    *
    * @tparam T the transaction type
    * @param tx the transaction to apply
    * @param requiresReads evidence that T's read requirements are in Schema
    * @param requiresWrites evidence that T's write requirements are in Schema
    * @return a stateful computation returning the result and list of events
    */
  def apply[T <: Tx](tx: T)(using
      requiresReads: Requires[tx.Reads, Schema],
      requiresWrites: Requires[tx.Writes, Schema],
  ): StoreF[F][(tx.Result, List[tx.Event])]

/** Module blueprint (path-independent).
  *
  * A blueprint is a module specification without a concrete deployment path.
  * It defines:
  *   - Schema: the set of Entry instances (runtime values that can create tables)
  *   - Reducer: the transaction processing logic (StateReducer0)
  *   - Transactions: the set of supported transaction types
  *   - Dependencies: other modules this blueprint depends on
  *
  * Blueprints are designed with the assumption that they don't know where they
  * will be deployed. The mounting process (Phase 2) binds a blueprint to a
  * specific Path, computing prefixes and using Entry instances to create
  * concrete StateTable instances.
  *
  * IMPORTANT: Blueprint contains Entry instances (runtime values), not StateTable
  * instances. Each Entry can create a StateTable when given a prefix.
  *
  * @tparam F the effect type
  * @tparam MName the module name (literal String type)
  * @tparam Schema the schema tuple (tuple of Entry types)
  * @tparam Txs the transaction types tuple
  * @tparam Deps the dependency types tuple
  * @param schema the Entry instances (runtime values)
  * @param reducer0 the path-agnostic reducer
  * @param txs the transaction registry
  * @param deps the dependencies
  * @param uniqueNames evidence that table names are unique within Schema
  */
final class ModuleBlueprint[F[_], MName <: String, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
    val schema: Schema,  // Runtime tuple of Entry instances
    val reducer0: StateReducer0[F, Schema],
    val txs: TxRegistry[Txs],
    val deps: Deps,
)(using val uniqueNames: UniqueNames[Schema])
