package org.sigilaris.core
package application

/** Module identifier carrying the module path.
  *
  * The path tuple identifies which module a transaction belongs to.
  * During blueprint composition, the path is used to route transactions
  * to the correct reducer.
  *
  * @param path the module path tuple (e.g., ("app", "accounts"))
  */
final case class ModuleId(path: Tuple)

/** Transaction trait defining read/write requirements and result types.
  *
  * A transaction declares:
  *   - Reads: tuple of table Entry types this transaction reads from
  *   - Writes: tuple of table Entry types this transaction writes to
  *   - Result: the type of result produced
  *   - Event: the type of events emitted
  *
  * The Reads and Writes types are used for compile-time verification that
  * a reducer has access to all required tables.
  *
  * @example
  * {{{
  * case class Transfer(from: Address, to: Address, amount: BigInt) extends Tx:
  *   type Reads = (Entry["accounts", Address, Account], Entry["balances", Address, BigInt])
  *   type Writes = Entry["balances", Address, BigInt] *: EmptyTuple
  *   type Result = Unit
  *   type Event = TransferEvent
  * }}}
  */
trait Tx:
  /** Tables this transaction reads from. */
  type Reads <: Tuple

  /** Tables this transaction writes to. */
  type Writes <: Tuple

  /** The result type produced by applying this transaction. */
  type Result

  /** The event type emitted by this transaction. */
  type Event

/** Transaction trait with module routing information.
  *
  * Transactions that implement this trait carry their module path,
  * enabling proper routing in composed blueprints. The module path
  * is used to dispatch transactions to the correct reducer.
  *
  * @example
  * {{{
  * case class AccountsTransfer(from: Address, to: Address, amount: BigInt)
  *     extends Tx with ModuleRoutedTx:
  *   val moduleId = ModuleId(("accounts" *: EmptyTuple))
  *   type Reads = Entry["balances", Address, BigInt] *: EmptyTuple
  *   type Writes = Entry["balances", Address, BigInt] *: EmptyTuple
  *   type Result = Unit
  *   type Event = TransferEvent
  * }}}
  */
trait ModuleRoutedTx extends Tx:
  /** The module identifier specifying which module this transaction belongs to. */
  def moduleId: ModuleId

/** Transaction registry holding a tuple of transaction types.
  *
  * This is a compile-time marker used for type-level transaction set definitions.
  *
  * @tparam Txs the transaction types tuple
  */
final class TxRegistry[Txs <: Tuple]:
  /** Combine this registry with another registry.
    *
    * @param other the other transaction registry
    * @return a registry containing both transaction sets
    */
  def combine[T2 <: Tuple](@annotation.unused other: TxRegistry[T2]): TxRegistry[Txs ++ T2] =
    new TxRegistry[Txs ++ T2]

object TxRegistry:
  /** Create an empty transaction registry. */
  def empty: TxRegistry[EmptyTuple] = new TxRegistry[EmptyTuple]
