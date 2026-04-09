package org.sigilaris.core.application.execution

import org.sigilaris.core.application.scheduling.ConflictFootprint
import org.sigilaris.core.application.state.{AccessLog, StoreState}
import org.sigilaris.core.merkle.MerkleTrieState

/** Witness of a single transaction execution, capturing the resulting state,
  * access log, conflict footprint, result, and emitted events.
  *
  * @tparam Result
  *   the transaction result type
  * @tparam Event
  *   the transaction event type
  * @param nextTrieState
  *   the Merkle trie state after execution
  * @param actualAccessLog
  *   the access log recorded during execution
  * @param actualFootprint
  *   the conflict footprint derived from the access log, or an invariant
  *   violation error
  * @param result
  *   the transaction result
  * @param events
  *   the events emitted during execution
  */
final case class TxExecution[+Result, +Event](
    nextTrieState: MerkleTrieState,
    actualAccessLog: AccessLog,
    actualFootprint: Either[
      ConflictFootprint.AccessLogInvariantViolation,
      ConflictFootprint,
    ],
    result: Result,
    events: List[Event],
):
  /** The post-execution store state with a fresh (empty) access log. */
  lazy val nextState: StoreState =
    StoreState.fromTrieState(nextTrieState)

  /** Backward-compat inspection surface that preserves the per-tx witness log.
    *
    * This is a synthetic view: `nextTrieState` is the post-execution trie,
    * while `actualAccessLog` is the witness recorded during execution against
    * the transaction's fresh-log state. Treat the log as an inspection witness
    * only; use [[nextState]] when chaining execution so the old log is not
    * re-attached to the continuation state.
    */
  lazy val observedState: StoreState =
    StoreState(nextTrieState, actualAccessLog)
