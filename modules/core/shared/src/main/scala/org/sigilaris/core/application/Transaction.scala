package org.sigilaris.core
package application

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

/** Transaction registry holding a tuple of transaction types.
  *
  * This is a compile-time marker used for type-level transaction set definitions.
  *
  * @tparam Txs the transaction types tuple
  */
final class TxRegistry[Txs <: Tuple]

object TxRegistry:
  /** Create an empty transaction registry. */
  def empty: TxRegistry[EmptyTuple] = new TxRegistry[EmptyTuple]

  /** Combine two transaction registries.
    *
    * @return a registry containing both transaction sets
    */
  def combine[T1 <: Tuple, T2 <: Tuple]: TxRegistry[Tuple.Concat[T1, T2]] =
    new TxRegistry[Tuple.Concat[T1, T2]]
