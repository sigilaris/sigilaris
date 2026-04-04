package org.sigilaris.core.application.execution

import org.sigilaris.core.application.scheduling.ConflictFootprint
import org.sigilaris.core.application.state.{AccessLog, StoreState}
import org.sigilaris.core.merkle.MerkleTrieState

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
