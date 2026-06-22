# ADR-0031: Certified Ancestor Dependent Transaction Pipelining

## Status
Accepted

Promoted from `Proposed` to `Accepted` in Plan 0023 Phase 0 after locking the
runtime-only certified observation surface, dependency reason-code taxonomy,
proposal input and validation context requirements, and low-latency measurement
labels.

## Context
- ADR-0017 defines the HotStuff proposal, vote, and QC baseline.
- ADR-0022 defines the pacemaker and view-change baseline that low-latency
  profiles must tune without changing safety rules.
- ADR-0028 separates consensus finalization from application materialization
  and states that pacemaker timing values are not application finalization
  SLAs.
- ADR-0029 adds default unfinalized-ancestor tx uniqueness and exposes
  finalized tx range observations for embedder pending-pool eviction.
- Some payment applications need multiple application transactions with a hard
  parent-child dependency. A common example is:
  - an off-chain bank debit moves fiat from a customer balance to a deposit
    account;
  - an on-chain mint transaction creates a deposit-token UTXO for the customer;
  - a second on-chain transaction spends that newly minted UTXO into payment
    escrow for a shop.
- The mint and escrow transactions cannot be placed in the same block when the
  escrow input must reference the mint output. Waiting for the mint block to be
  finalized before submitting the escrow transaction serializes two finality
  paths and makes a sub-second payment target unrealistic under the existing
  three-chain finality model.
- The desired latency model is to let the dependent escrow transaction enter a
  dependency-held pending state as soon as the mint output can be named, often
  after the mint proposal is observed, while making it eligible for proposal
  only on a branch whose certified ancestry contains the mint block. This
  pipelines dependent work across adjacent blocks while preserving final
  settlement at the escrow finality point.

## Decision
1. **Distinguish proposed, certified, finalized, and materialized states.**
   - `Proposed` means a valid proposal artifact has been locally accepted or
     emitted.
   - `Certified` means the proposal/block has been observed with a locally
     verified quorum certificate for its subject, commonly through a child
     proposal's `justify` QC, and may safely be used as the parent of a later
     certified proposal.
   - `Finalized` means the proposal is the verified anchor of a valid
     three-chain finalization proof.
   - `Materialized` remains embedder-owned application state projection after
     consensus observation.
   - A dependent transaction pipeline may use `Proposed` as an early submission
     trigger, but must not treat it as sufficient for proposal eligibility or
     final settlement. The minimum eligibility handoff is `Certified`.

2. **The certified observation surface is runtime-only in the first
   implementation.**
   - The runtime-owned type is named `CertifiedBlockObservation`.
   - The retained observation must identify `chainId`, `proposalId`,
     `blockId`, `height`, `window`, `qcSubject`, `validatorSetHash`,
     `proposalObservedAt`, and `certifiedObservedAt`.
   - `proposalObservedAt` is the first local observation time for the certified
     proposal body when known. If the QC arrives before the proposal body, the
     runtime may retain a pending certification edge until the proposal body is
     observed and then publish the complete observation.
   - `certifiedObservedAt` is the first local time the runtime observes a
     locally verified QC whose subject matches the proposal/block.
   - The first public projection is `HotStuffNodeRuntime`
     current/recent diagnostics. HTTP, SSE, or other application-facing streams
     remain follow-up work until the runtime contract stabilizes.

3. **Dependent application transactions may be admitted before finality but
   must be branch-gated before proposal.**
   - An embedder may accept a dependent transaction into a held pending state
     before the dependency is finalized, including immediately after the
     dependency proposal is observed if the output id is deterministic.
   - The dependent transaction is eligible only for proposals whose parent
     branch contains the dependency block and whose HotStuff justification
     makes the relevant parent chain certified for local validation.
   - In the adjacent-block case, the child proposal carrying the dependent tx
     also carries the QC that certifies the parent block that created the input.
     HotStuff already enforces this edge structurally: a direct child proposal
     for `B1` must justify `B1` with a QC. The new dependency logic is therefore
     mostly admission/pre-selection and branch validation for this case, not an
     additional consensus rule.
   - For later-descendant cases, the validator must be able to prove from local
     ancestry that the dependency block is still on the proposal's parent
     branch.
   - The transaction must not be considered final or externally settled until
     its own block is finalized.

4. **The success boundary for a pipelined payment is the dependent transaction
   finality point.**
   - In the mint-then-escrow example, customer/shop payment completion should
     be reported when the escrow transaction's block is finalized.
   - Finalizing the escrow block also finalizes the required ancestor chain,
     including the mint block, under the same HotStuff branch.
   - A proposed or certified mint observation may be used to continue the
     pipeline, but it is not a customer-visible final settlement event by
     itself.

5. **Sigilaris remains application-neutral but must expose enough branch
   context for embedders to validate speculative state.**
   - Sigilaris does not interpret UTXO bodies, minted outputs, escrow state,
     account balances, or application receipts.
   - Sigilaris should expose certified proposal observations and parent-chain
     context so embedders can build and validate application-specific
     speculative state for a candidate branch.
   - Application proposal input and validation providers remain responsible for
     checking that dependent tx inputs exist in the candidate parent chain and
     are not double-spent on that branch.

6. **Certified ancestor state is branch-local.**
   - A dependent tx admitted against an output from block `B1` is valid only on
     descendants of `B1`.
   - If a sibling branch does not include `B1`, the dependent tx must stay
     ineligible for proposals on that branch.
   - If a dependent tx was submitted after only a proposed `B1` observation and
     `B1` never becomes part of a certified parent branch, the tx remains held
     or is rejected by embedder policy; Sigilaris must not finalize it on a
     branch that lacks the dependency.
   - If local ancestry is missing, proposal input or vote validation must return
     unavailable rather than signing a proposal whose dependency cannot be
     proven.

7. **The proposal input contract supports dependency-aware selection through
   branch context and held-work diagnostics.**
   - The proposal input provider must receive the target parent block/proposal
     identity, known unfinalized-ancestor metadata, certified observation
     status for the parent branch when available, and the already existing tx
     exclusion set.
   - Embedders can use that context to select dependent txs whose required
     inputs are present in the target parent branch.
   - Sigilaris must not mutate provider output after selection, because the
     provider-supplied roots may already commit to the selected branch state.
   - A dependency-held selection outcome is reported with
     `proposalInputDependencyHeld`.
   - Missing branch ancestry for dependency eligibility is reported as
     unavailable with `proposalInputDependencyAncestorUnavailable`.
   - A provider returning a tx for the wrong dependency branch is invalid and is
     reported with `proposalInputDependencyBranchConflict`.

8. **The proposal validation contract supports dependency-aware votes.**
   - Before signing a local vote, the embedder's validation provider must be
     able to validate a received proposal against the proposal's parent branch,
     including certified unfinalized ancestors.
   - A dependency conflict maps to application validation rejection. Missing
     ancestry maps to unavailable, preserving safety over liveness.
   - Missing dependency ancestry is reported with
     `proposalVoteDependencyAncestorUnavailable`.
   - A proposal whose dependent tx is tied to a sibling or otherwise
     incompatible branch is rejected with
     `proposalVoteDependencyBranchConflict`.

9. **Latency targets are deployment profiles, not consensus guarantees.**
   - A low-latency deployment may target adjacent-block dependent tx finality
     under 500ms in warm same-region conditions.
   - With `T1` in `B1` and `T2` first proposed in child `B2`, `T2` finality
     requires sequential progress through approximately four block/QC rounds:
     `B1 proposed -> B1 QC(certified) -> B2(T2) proposed -> B2 QC -> B3 -> B4`,
     where the `B4` proposal makes `B2` the three-chain finalized anchor.
   - A 500ms target therefore assumes roughly 100ms-class proposal/QC rounds
     with only small additional headroom for materialization and client
     observation. It is a precision target for a warm profile, not a general
     HotStuff guarantee.
   - This target requires event-driven proposal/pacemaker wake-up, low-latency
     peer transport, readiness that excludes cold bootstrap, and telemetry that
     separates certified and finalized observations.
   - Sigilaris should expose diagnostics for these timing domains but must not
     encode a hard SLA in the core consensus state model.
   - The profile labels are:
     `t1AdmittedToT1Certified`, `t1CertifiedToT2Selected`,
     `t2AdmittedToT2Certified`, `t2CertifiedToT2Finalized`, and
     `t1AdmittedToT2Finalized`.
   - Block-round timing is labelled separately from client wall-clock latency;
     the adjacent-child critical path is recorded as `B1 -> B4`.

## Consequences
- Applications can pipeline dependent transactions across adjacent certified
  blocks instead of waiting for the first transaction to finalize before making
  the second transaction branch-eligible.
- Payment-like flows can keep final settlement semantics at the dependent
  transaction finality point while reducing end-to-end latency.
- Embedders must maintain branch-local speculative application state or an
  equivalent dependency index for certified but unfinalized ancestors.
- Missing ancestry or delayed certified observations can temporarily suppress
  proposal/vote progress for dependency-heavy workloads.
- The runtime needs a clearer certified-block observation surface in addition
  to the existing finalized-anchor observation surface.
- The approach increases integration complexity: admission, pending selection,
  proposal validation, status APIs, and metrics must distinguish certified
  progress from final settlement.

## Rejected Alternatives
1. **Put dependent transactions in the same block**
   - This does not work for UTXO models where the second transaction input must
     reference an output created by the first transaction.
   - Same-block execution order would require application-specific intra-block
     semantics that Sigilaris should not standardize as a HotStuff rule.

2. **Wait for the mint transaction to finalize before submitting escrow**
   - This is the simplest safety story, but it serializes two finality paths.
   - Under three-chain finality, two sequential finalized transactions have a
     minimum latency that does not fit the target sub-second payment budget.

3. **Treat proposal observation as sufficient for eligibility or settlement**
   - A proposed block without QC is useful as an early client trigger when the
     dependent tx can name the expected output.
   - It is too weak as a proposal eligibility or settlement rule.
   - The next proposer and validators need a certified parent-chain view, not
     merely a locally observed proposal artifact.

4. **Move UTXO semantics into Sigilaris**
   - Sigilaris is an application-neutral consensus/runtime library.
   - UTXO output creation, spend rules, escrow semantics, and off-chain debit
     reconciliation belong to the embedding application.

5. **Report payment success at mint certification**
   - Certification of the mint block is a useful pipeline trigger, not final
     customer/shop settlement.
   - The payment intent is fulfilled only when the escrow spend is finalized.

## Follow-Up
- Plan 0023 defines the implementation path for certified observations,
  dependency-aware proposal input/validation, low-latency wake-up, telemetry,
  and integration tests.
- Plan 0023 Phase 0 locked the final certified observation fields, dependency
  reason codes, and profile measurement labels before implementation work
  started.
- A later release may add a stable public HTTP/SSE projection for certified
  observations after the runtime diagnostics and embedder contracts are tested.

## References
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [ADR-0029: HotStuff Proposal Tx Uniqueness Policy](0029-hotstuff-proposal-tx-uniqueness-policy.md)
- [0021 - HotStuff Proposal Tx Uniqueness Policy Plan](../plans/0021-hotstuff-proposal-tx-uniqueness-policy-plan.md)
- [0023 - Certified Ancestor Dependent Transaction Pipelining Plan](../plans/0023-certified-ancestor-dependent-transaction-pipelining-plan.md)
