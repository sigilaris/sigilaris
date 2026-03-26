package org.sigilaris.core.application.transactions

import scala.Tuple.++

import org.sigilaris.core.application.feature.accounts.domain.Account
import org.sigilaris.core.crypto.Signature

/** Module identifier carrying the module-relative path.
  *
  * IMPORTANT: The path is always module-relative (MName *: SubPath), never
  * prefixed with the mount path. This ensures transactions remain portable
  * across different deployment paths.
  *
  * The path tuple identifies which module a transaction belongs to. During
  * blueprint composition, the path is used to route transactions to the correct
  * reducer by matching the first segment (module name).
  *
  * @param path
  *   the module-relative path tuple (e.g., ("accounts" *: EmptyTuple) or
  *   ("accounts" *: "v1" *: EmptyTuple))
  *
  * @example
  *   {{{
  * // A transaction for the "accounts" module
  * val moduleId = ModuleId("accounts" *: EmptyTuple)
  *
  * // Even when the module is mounted at ("app"), the moduleId stays ("accounts")
  * // The full path ("app", "accounts") is only constructed at system boundaries
  *   }}}
  */
final case class ModuleId[Path <: Tuple] private (path: Path)

object ModuleId:
  /** Evidence that a tuple consists only of `String` elements. */
  sealed trait StringTuple[Path <: Tuple]
  object StringTuple:
    given StringTuple[EmptyTuple] with {}
    given [H <: String, T <: Tuple](using StringTuple[T]): StringTuple[H *: T]
    with {}

  type Any = ModuleId[? <: Tuple]

  /** Construct a `ModuleId` ensuring the tuple elements are all `String`s at
    * compile time.
    */
  def apply[Path <: Tuple](path: Path)(using
      StringTuple[Path],
  ): ModuleId[Path] =
    new ModuleId(path)

  /** Pattern matching helper exposing the underlying tuple path. */
  def unapply[Path <: Tuple](id: ModuleId[Path]): Some[Path] = Some(id.path)

/** Transaction trait defining read/write requirements and result types.
  *
  * A transaction declares:
  *   - Reads: tuple of table Entry types this transaction reads from
  *   - Writes: tuple of table Entry types this transaction writes to
  *   - Result: the type of result produced
  *   - Event: the type of events emitted
  *
  * The Reads and Writes types are used for compile-time verification that a
  * reducer has access to all required tables.
  *
  * @example
  *   {{{
  * case class Transfer(from: Address, to: Address, amount: BigInt) extends Tx:
  *   type Reads = (Entry["accounts", Address, Account], Entry["balances", Address, BigInt])
  *   type Writes = Entry["balances", Address, BigInt] *: EmptyTuple
  *   type Result = Unit
  *   type Event = TransferEvent
  *   }}}
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
  * Transactions that implement this trait carry their module path, enabling
  * proper routing in composed blueprints. The module path is used to dispatch
  * transactions to the correct reducer.
  *
  * @example
  *   {{{
  * case class AccountsTransfer(from: Address, to: Address, amount: BigInt)
  *     extends Tx with ModuleRoutedTx:
  *   val moduleId = ModuleId(("accounts" *: EmptyTuple))
  *   type Reads = Entry["balances", Address, Account] *: EmptyTuple
  *   type Writes = Entry["balances", Address, Account] *: EmptyTuple
  *   type Result = Unit
  *   type Event = TransferEvent
  *   }}}
  */
trait ModuleRoutedTx extends Tx:
  /** The module identifier specifying which module this transaction belongs to.
    */
  def moduleId: ModuleId.Any

/** Transaction registry holding a tuple of transaction types.
  *
  * This is a compile-time marker used for type-level transaction set
  * definitions.
  *
  * @tparam Txs
  *   the transaction types tuple
  */
final class TxRegistry[Txs <: Tuple]:
  /** Combine this registry with another registry.
    *
    * @param other
    *   the other transaction registry
    * @return
    *   a registry containing both transaction sets
    */
  def combine[T2 <: Tuple](
      @annotation.unused other: TxRegistry[T2],
  ): TxRegistry[Txs ++ T2] =
    new TxRegistry[Txs ++ T2]

object TxRegistry:
  /** Create an empty transaction registry. */
  def empty: TxRegistry[EmptyTuple] = new TxRegistry[EmptyTuple]

/** Account signature containing both the signing account and the signature.
  *
  * This binds a cryptographic signature to the account that produced it,
  * enabling verification that the signature was created by a key controlled
  * by the specified account.
  *
  * @param account
  *   the account that signed the transaction
  * @param sig
  *   the cryptographic signature
  *
  * @example
  *   {{{
  * val accountSig = AccountSignature(
  *   account = Account.Named(Utf8("alice")),
  *   sig = signature
  * )
  *   }}}
  */
final case class AccountSignature(
    account: Account,
    sig: Signature,
)

/** Signed transaction wrapper enforcing signature requirement at type level.
  *
  * All blockchain transactions must be wrapped in Signed[A] to ensure they
  * carry a valid signature. This is enforced at compile time - attempting to
  * submit an unsigned transaction to a blueprint will result in a type error.
  *
  * The covariant type parameter (+A <: Tx) allows Signed[CreateNamedAccount]
  * to be used where Signed[Tx & ModuleRoutedTx] is expected, enabling flexible
  * composition while maintaining type safety.
  *
  * @tparam A
  *   the transaction type (covariant)
  * @param sig
  *   the account signature (account + cryptographic signature)
  * @param value
  *   the actual transaction payload
  *
  * @example
  *   {{{
  * // Creating a signed transaction
  * val tx = CreateNamedAccount(...)
  * val keyPair = CryptoOps.generate()
  * val account = Account.Named(Utf8("alice"))
  *
  * // Sign the transaction
  * val signedTx: Either[SigilarisFailure, Signed[CreateNamedAccount]] =
  *   for
  *     sig <- keyPair.sign(tx)
  *   yield Signed(AccountSignature(account, sig), tx)
  *
  * // Use in blueprint (requires Signed wrapper)
  * module.reducer.apply(signedTx)
  *   }}}
  *
  * @see
  *   [[AccountSignature]] for signature structure
  * @see
  *   ADR-0012 for design rationale
  */
final case class Signed[+A <: Tx](
    sig: AccountSignature,
    value: A,
)
