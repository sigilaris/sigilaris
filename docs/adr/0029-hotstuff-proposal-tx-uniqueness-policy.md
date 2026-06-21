# ADR-0029: HotStuff Proposal Tx Uniqueness Policy

## Status
Accepted

## Context
- ADR-0017 defines the HotStuff proposal, vote, and QC baseline.
- ADR-0019 makes the HotStuff block view application-neutral while still
  carrying an application-neutral `ProposalTxSet`.
- Plan 0016 added the application proposal-input hook, so an embedder can
  choose the next proposal's tx ids.
- Plan 0017 added the application proposal-validation hook, so an embedder can
  suppress local votes before signing.
- The current structural validation guarantees a proposal's tx set is canonical
  and duplicate-free within that single proposal.
- It does not provide a Sigilaris-owned rule that prevents a descendant
  proposal from reusing a tx id that is already present in an unfinalized
  ancestor proposal.
- Downstream embedders can use `ProposalTxSet` ids as application transaction
  identities. For those embedders, voting for a descendant proposal that
  repeats an ancestor tx can later create materialization conflicts or divergent
  application block projections even when the HotStuff structure is otherwise
  valid.

## Decision
1. **`ProposalTxSet` ids are the consensus-level transaction identities for
   the default uniqueness policy.**
   - Sigilaris does not interpret application transaction bodies.
   - The `StableArtifactId` values in `ProposalTxSet` are sufficient for the
     generic rule: a locally voted proposal must not repeat tx ids already
     present in its unfinalized ancestor proposals on the same chain.

2. **Tx uniqueness is enabled by default for automatic HotStuff consensus.**
   - The production default is `EnforceUnfinalizedAncestors`.
   - The runtime config type is
     `HotStuffProposalTxUniquenessRuntimeConfig`.
   - The explicit compatibility mode is named
     `UnsafeAllowAncestorTxConflicts`; config helpers expose it as
     `unsafeAllowAncestorTxConflicts`.
   - A runtime may expose the unsafe compatibility mode for tests or legacy
     embedders, but automatic consensus must not silently fall back to
     duplicate-tolerant behavior.
   - Diagnostics must make disabled or unavailable uniqueness checks visible.

3. **The enforced scope is the unfinalized ancestor chain.**
   - For a candidate proposal, the uniqueness scan starts at the proposal's
     parent block and follows parents on the same chain.
   - The scan stops before the local best finalized anchor, when such an anchor
     is known and the candidate descends from it.
   - If no finalized anchor is known, every known ancestor up to genesis or the
     local ancestry boundary is treated as unfinalized.
   - The policy only scans the candidate branch's ancestors. Tx ids that appear
     only in sibling or cousin forks do not conflict with the candidate branch
     unless they also appear in the candidate's ancestor chain.
   - Sigilaris does not keep an unbounded finalized tx-id replay index in this
     policy. Embedders that require chain-wide replay protection across
     finalized history must enforce that rule in their application state,
     admission, or application proposal validator.

4. **Proposal construction is exclusion-driven, not runtime post-filtered.**
   - The proposal-input request must tell the embedder which tx ids are already
     present in the unfinalized ancestors of the target parent.
   - The request field is `txExclusion`, typed as
     `HotStuffProposalTxExclusion`, which carries a canonical duplicate-free
     `ProposalTxSet` plus bounded traversal metadata.
   - `HotStuffProposalInputRequest` keeps source compatibility by giving
     `txExclusion` a default empty value, so existing direct construction keeps
     compiling while providers can migrate to the new field.
   - The embedder must build `stateRoot`, `bodyRoot`, and `txSet` from a
     selection that excludes those ids.
   - Sigilaris must validate the returned input and suppress proposal signing
     if the provider returns a conflicting tx id.
   - A provider input that contains an excluded tx id should be reported as an
     input `Invalid` outcome with reason `proposalInputTxAncestorConflict`.
   - If the runtime cannot resolve the ancestor exclusion set, proposal input
     is suppressed with the dedicated input diagnostic outcome `Unavailable`
     and reason `proposalInputTxAncestorUnavailable`.
   - Sigilaris must not silently remove tx ids from a provider-supplied input
     after the fact, because the provider-supplied roots may already commit to
     the original tx set.

5. **Vote-time validation uses the same ancestor rule before signing.**
   - Structural proposal validation still runs first.
   - The tx uniqueness check then rejects or holds the local vote before any
     proposal vote is signed.
   - The reusable check result taxonomy is
     `HotStuffProposalTxUniquenessResult.Accepted`,
     `HotStuffProposalTxUniquenessResult.Conflict`, and
     `HotStuffProposalTxUniquenessResult.Unavailable`.
   - Application proposal validation remains available for stricter embedder
     rules, including finalized-history replay checks, admission-policy checks,
     and application-state checks.
   - A conflict outcome is vote-suppressing and is reported with
     `proposalVoteTxAncestorConflict`; unavailable ancestry is reported with
     `proposalVoteTxAncestorUnavailable`.

6. **Missing ancestry is non-signing.**
   - If the runtime cannot resolve enough ancestry to prove the unfinalized
     ancestor uniqueness rule for a candidate proposal, it must not sign a
     local vote for that proposal.
   - The diagnostic outcome should distinguish conflict from unavailable
     ancestry, for example `proposalVoteTxAncestorUnavailable`.
   - Proposal-input diagnostics should also distinguish unavailable ancestry
     from invalid provider input, preferably with a dedicated input
     `Unavailable` outcome and reason `proposalInputTxAncestorUnavailable`.
   - Catch-up and bootstrap paths may later supply the missing proposals and
     allow a new validation attempt.

7. **Finalization is the handoff point for pending-pool eviction.**
   - When finalization advances, embedders are expected to remove the tx ids
     from every newly finalized block in the finalized range from the previous
     local best finalized anchor, exclusive, to the new best finalized anchor,
     inclusive.
   - Sigilaris exposes this as one range observation per finalization
     advancement: `FinalizedTxRangeObservation`. The observation identifies the
     chain id, local finalized observation time, previous finalized anchor when
     known, new finalized anchor, and each newly finalized proposal's proposal
     id, block id, height, and tx set.
   - Anchor-only tx-set handoff is insufficient when a single finalization
     advancement commits intermediate ancestor blocks.
   - Failure to evict finalized txs from an embedder pending pool is an
     embedder materialization/admission bug, not a reason to weaken the
     consensus uniqueness rule.

8. **Traversal is bounded and memoized.**
   - The unfinalized ancestor exclusion computation memoizes by
     `(chainId, parentBlockId, bestFinalizedBlockId)`, where the finalized id is
     absent when no local finalized anchor is known.
   - The default bounds are `maxTraversalDepth = 1024` ancestors and
     `maxExcludedTxIds = 65536`.
   - Exceeding either bound returns `Unavailable` and suppresses local proposal
     or vote signing. Bounds are liveness controls during finalization stalls;
     they do not weaken the uniqueness rule.

9. **The policy is application-neutral but not application-complete.**
   - Sigilaris prevents conflicts among unfinalized proposals using only
     HotStuff proposal metadata.
   - Applications still own semantic replay prevention, nonce/account checks,
     already-confirmed transaction checks, body availability, and block
     materialization.

## Consequences
- Validators do not sign proposals that repeat tx ids already present in the
  candidate's unfinalized ancestors.
- Leaders receive an explicit exclusion set when selecting proposal input, so
  normal proposal generation avoids conflicts before signing.
- Applications get a clear finalization handoff for removing finalized tx ids
  from pending pools.
- The policy increases safety while keeping finalized-history replay protection
  application-owned and bounded by application storage.
- Nodes with missing ancestry may withhold votes until catch-up supplies the
  missing proposals. This favors safety over liveness when the local node cannot
  prove the uniqueness invariant.
- Nodes that have observed different local finalized heights may make different
  local vote decisions for proposals that reuse tx ids below one node's
  finalized boundary but above another node's finalized boundary. This is not a
  safety violation, but it can delay QC formation until finalization/catch-up
  converges or embedder finalized-history replay checks reject the proposal.

## Rejected Alternatives
1. **Leave uniqueness entirely to embedder validation**
   - The unfinalized ancestor tx-id conflict is visible in generic HotStuff
     proposal metadata.
   - Relying only on embedders lets two validators with different application
     validation wiring sign different duplicate-containing descendants.

2. **Silently post-filter provider-supplied tx sets**
   - Proposal input includes roots that may commit to the selected transaction
     set.
   - Removing tx ids after the provider returns would make the signed proposal
     inconsistent with the embedder's state/body commitments.

3. **Scan all finalized history in Sigilaris**
   - An unbounded replay index is application-state territory.
   - Applications have different replay semantics, pruning policies, archive
     layouts, nonce models, and disaster-recovery requirements.
   - Sigilaris only owns the bounded unfinalized ancestor invariant by default.

4. **Make the policy optional by default**
   - Duplicate tx ids across unfinalized ancestors can produce application
     materialization conflicts after a proposal has already received votes.
   - The safer default is to enforce the generic invariant and require explicit
     compatibility configuration for runtimes that intentionally opt out.

5. **Expose only the explicit finalized anchor's tx set**
   - In a three-chain finalization model, advancing the best finalized anchor
     can newly finalize a range of ancestor blocks, not only the explicit anchor
     proposal.
   - Anchor-only eviction can leave intermediate ancestor tx ids in an embedder
     pending pool and make later proposal selection unsafe.

## Follow-Up
- Plan 0021 defines the implementation steps for the proposal-input exclusion
  request, vote-time uniqueness validation, diagnostics, tests, and
  documentation.
- Downstream embedders should add or verify their own finalized-pending
  eviction and finalized-history replay protection.

## References
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0019: Canonical Block Header And Application-Neutral Block View](0019-canonical-block-header-and-application-neutral-block-view.md)
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [0016 - HotStuff Application Proposal Input Hook Plan](../plans/0016-hotstuff-application-proposal-input-hook-plan.md)
- [0017 - HotStuff Application Proposal Validation Hook Plan](../plans/0017-hotstuff-application-proposal-validation-hook-plan.md)
- [0021 - HotStuff Proposal Tx Uniqueness Policy Plan](../plans/0021-hotstuff-proposal-tx-uniqueness-policy-plan.md)
