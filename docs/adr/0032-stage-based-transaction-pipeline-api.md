# ADR-0032: Stage-Based Transaction Pipeline API

## Status
Accepted

## Context
- ADR-0031 defines certified-ancestor dependent transaction pipelining:
  dependent application transactions may be accepted before finality, but they
  become proposal-eligible only on a branch whose certified ancestry satisfies
  the dependency.
- A flat transaction submission API cannot express two distinct grouping
  semantics at the same time:
  - multiple transactions that may be same-stage proposal candidates; and
  - transactions that must wait for a previous transaction group to become a
    certified ancestor before proposal.
- A pure dependency graph in the public request would make clients name and
  maintain dependency edges that the server can often infer from transaction
  contents or application validation rules.
- A pure ordered list is also insufficient, because some adjacent transactions
  should remain same-stage proposal candidates while others require a certified
  ancestor barrier.
- Clients need a response model that exposes progress per stage, not only per
  individual transaction. Without a stage-level response, a client cannot tell
  whether a later transaction is blocked by an unsatisfied certified-ancestor
  barrier, waiting for proposal, certified, finalized, or failed.
- Sigilaris does not currently define a stable application transaction
  submission HTTP contract in the core runtime. This ADR defines the pipeline
  contract and a future single-transaction convenience contract without
  claiming compatibility with an already-defined Sigilaris transaction
  endpoint.

## Decision
1. **Introduce a stage-based transaction pipeline request shape.**
   - A pipeline request contains an ordered outer vector of stages.
   - Each stage contains an ordered inner vector of signed application
     transactions.
   - The inner vector is a same-stage proposal candidate group. Transactions in
     the same stage may be proposed together in one block subject to normal
     application validation and transaction limits.
   - The outer vector is a certified-ancestor barrier sequence. Stage `N + 1`
     must not become proposal-eligible until stage `N` is certified on the
     candidate parent branch.
   - If stage `N` is split across multiple blocks because of transaction
     limits or embedder policy, the barrier is satisfied only for a candidate
     branch that contains certified blocks for every successful transaction in
     stage `N`.

   Example:

   ```json
   {
     "stages": [
       ["signed-tx-A1", "signed-tx-A2"],
       ["signed-tx-B1"],
       ["signed-tx-C1", "signed-tx-C2"]
     ],
     "waitFor": "accepted"
   }
   ```

2. **Define single-transaction submission as a convenience API that normalizes
   to a one-stage pipeline.**
   - A future node API may expose public single-transaction submission for
     clients that do not need stage-shaped responses.
   - Internally, that request is normalized to:

   ```json
   {
     "stages": [
       ["signed-tx"]
     ]
   }
   ```

   - The single-transaction response may be compact, but it must be derivable
     from the one-stage pipeline state.
   - The pipeline endpoint returns a pipeline-shaped response because it has
     stage-level state and barrier semantics that do not fit a compact
     single-transaction response.

3. **Add a dedicated pipeline submission API.**
   - The public shape is conceptually:

   ```http
   POST /tx-pipeline
   ```

   - The request body carries `stages` and a wait mode.
   - The endpoint accepts all stages as one logical pipeline submission.
   - Stage `0` is immediately eligible for normal admission.
   - Stage `N > 0` is accepted into a pipeline-held state until the barrier from
     stage `N - 1` is satisfied on the candidate branch.
   - `waitFor` is explicit. The initial portable values are:
     - `accepted`: return after the pipeline and every stage are durably
       accepted by the node;
     - `certified`: return after every required stage has reached certified
       consensus observation;
     - `finalized`: return after every required stage has finalized.
   - A node may add weaker or stronger wait modes only as named extension
     values.
   - The exact HTTP path can be adapted by an embedding node API, but the
     semantic contract is the dedicated pipeline contract above.

4. **Define the stage barrier as certified-ancestor progress, not same-block
   execution order.**
   - A later stage must not rely on outputs produced earlier in the same
     proposal.
   - Stage identity is submission/node-local metadata. It is not consensus
     metadata embedded into transactions or proposals by Sigilaris core.
   - The node admission layer and proposal input provider enforce stage
     barriers by keeping later-stage work out of normal proposal selection until
     the previous stage barrier is satisfied.
   - Vote-time enforcement reduces to ADR-0031 branch dependency validation:
     an embedder validation provider rejects a proposal when a transaction's
     required certified-ancestor dependency is not present on the proposal's
     parent branch.
   - The validator-visible rejection reason should distinguish a stage-derived
     dependency branch conflict from an ordinary application reducer failure,
     using the ADR-0031 dependency validation taxonomy.
   - The barrier is satisfied only when the required previous stage block is a
     certified ancestor of the candidate parent branch.

5. **Return pipeline-shaped submission and query responses.**
   - A pipeline response preserves the same outer stage shape as the request.
   - Each stage reports:
     - `stageIndex`;
     - `status`;
     - `batchId` or equivalent grouping identifier when available;
     - proposal/certification/finalization metadata when available;
     - the certified-ancestor barrier it waits on, if any;
     - per-transaction hashes and statuses.
   - When a stage spans multiple blocks, the response reports per-block
     consensus metadata rather than implying one stage always maps to one
     proposal.
   - The initial submit response is a point-in-time snapshot. Clients use a
     query endpoint for subsequent progress.
   - Consensus observation timestamps such as `certifiedObservedAt` and
     `finalizedObservedAt` are optional diagnostics. Exposing them through a
     public projection is follow-up work from ADR-0031 and must not be treated
     as a consensus SLA.

   Example response shape:

   ```json
   {
     "pipelineId": "pipeline-id",
     "status": "running",
     "waitFor": "accepted",
     "stages": [
       {
         "stageIndex": 0,
         "status": "certified",
         "batchId": "batch-0",
         "proposal": {
           "blockHash": "block-0",
           "height": 12,
           "certifiedObservedAt": "2026-06-24T00:00:00.100Z",
           "finalizedObservedAt": null
         },
         "transactions": [
           {
             "transactionIndex": 0,
             "txHash": "tx-0",
             "pipelineState": "certified",
             "applicationStatus": null
           }
         ]
       },
       {
         "stageIndex": 1,
         "status": "held",
         "batchId": "batch-1",
         "barrier": {
           "type": "certified-ancestor",
           "dependsOnStage": 0,
           "satisfied": false,
           "satisfiedByBlockHash": null
         },
         "transactions": [
           {
             "transactionIndex": 0,
             "txHash": "tx-1",
             "pipelineState": "held",
             "applicationStatus": null
           }
         ]
       }
     ]
   }
   ```

6. **Expose a pipeline query API.**
   - The public shape is conceptually:

   ```http
   GET /tx-pipeline/{pipelineId}
   ```

   - The query response uses the same pipeline-shaped response model as
     submission.
   - It must be possible to identify the stage currently limiting progress:
     held by a barrier, eligible but not proposed, proposed, certified,
     finalized, failed, or unavailable due to missing local branch ancestry.

7. **Define status levels separately for pipeline, stage, and transaction.**
   - Pipeline-level status summarizes the whole submission:
     `accepted`, `running`, `certified`, `finalized`, `failed`,
     `partiallyFailed`.
   - Pipeline-level `accepted` means the node accepted the pipeline metadata
     and stage payloads; `running` means at least one stage has advanced beyond
     accepted while the pipeline has not reached a terminal status.
   - Stage-level status describes barrier and consensus progress:
     `accepted`, `held`, `eligible`, `proposed`, `certified`, `finalized`,
     `failed`, `unavailable`.
   - The ordinary successful stage progression is
     `accepted -> held -> eligible -> proposed -> certified -> finalized`.
     Stage `0` may skip `held`, and later stages may remain `held` until their
     certified-ancestor barrier is satisfied.
   - Transaction-level pipeline state uses the same canonical progress labels
     as stage-level status when reporting consensus progress:
     `accepted`, `held`, `eligible`, `proposed`, `certified`, `finalized`,
     `failed`, `unavailable`.
   - Application transaction status is a separate optional projection field.
     Sigilaris does not standardize values such as "pending" or "confirmed" for
     application-specific state machines.
   - The pipeline is finalized only when every required stage has finalized, or
     according to a caller-selected wait policy that explicitly asks for a
     weaker boundary.

8. **Define barrier failure propagation.**
   - If a stage fails before satisfying its certified-ancestor barrier, later
     stages that depend on it must not remain silently held forever.
   - The pipeline projection reports the failed stage as `failed`.
   - Directly dependent later stages become `failed` when the barrier can never
     be satisfied, or `unavailable` when the local node cannot currently prove
     whether the barrier can be satisfied because branch ancestry is missing.
   - A pipeline with at least one failed stage and at least one non-failed stage
     reports `partiallyFailed`.
   - Cancellation and retry policies are embedder API concerns and are not
     defined by this ADR.

9. **Define pipeline identity and idempotency at the node API boundary.**
   - `pipelineId` is server-generated unless an embedding API explicitly
     accepts a caller-supplied idempotency key.
   - A caller-supplied idempotency key, when supported, is not a consensus
     identifier and must not be embedded into HotStuff artifacts by Sigilaris
     core.
   - A repeated submission with the same idempotency key should return the
     existing pipeline snapshot rather than create duplicate stages.
   - A repeated submission with the same idempotency key and a different
     canonical payload must be rejected as an idempotency conflict.
   - Without an idempotency key, repeated submissions are independent pipeline
     submissions.

10. **Keep Sigilaris application-neutral.**
   - Sigilaris does not infer application-specific ledger object, account,
     balance, entitlement, or receipt semantics.
   - The stage contract gives embedders a generic ordering and
     certified-ancestor barrier model.
   - Application-specific providers and validators remain responsible for
     checking whether transactions inside a stage may execute together, and
     whether a later stage is semantically valid on the candidate branch.
   - Sigilaris should provide enough branch context and diagnostics for
     embedders to implement those checks without moving application semantics
     into the consensus runtime.

11. **Treat dependency inference as an optimization, not the public contract.**
   - The public request expresses explicit stage barriers through the outer
     vector.
   - A future embedder may infer a more parallel dependency graph from the
     transactions and split or merge stages internally only when that preserves
     the public stage ordering contract.
   - The first implementation should not weaken a caller-provided stage
     barrier merely because the runtime cannot prove a dependency.

12. **Lock the first implementation contract.**
   - The first public wire payload is an opaque string transaction envelope.
     Sigilaris stores and reports the exact submitted string, but application
     decoding and signature semantics remain embedder-owned.
   - `POST /tx-pipeline` carries a JSON body with required `stages` and
     required `waitFor`. `stages` is a non-empty outer array of non-empty inner
     arrays. `waitFor` accepts `accepted`, `certified`, or `finalized`.
   - HTTP idempotency uses the `Idempotency-Key` header. In-process admission
     may pass the same key through an optional metadata field, but the key is
     never embedded into HotStuff artifacts.
   - The canonical idempotency payload hash is SHA-256 over the normalized
     pipeline submit shape: ordered stages, ordered transaction payload strings,
     and normalized wait mode. The idempotency key itself is not part of the
     hash.
   - Transaction hashes are computed through an embedder-supplied canonical
     transaction hasher. The default test hasher may hash the submitted opaque
     string bytes, but production embedders own canonical transaction identity.
   - Duplicate transaction hashes inside one canonical pipeline request are
     rejected. A matching idempotent replay returns the existing snapshot
     without creating duplicate work.
   - The initial configurable shape limits are `64` stages, `1024`
     transactions per stage, `4096` total transactions, `1048576` bytes per
     opaque transaction string, `16777216` total transaction payload bytes, and
     `10000` accepted-but-nonterminal pipelines per node.
   - Shared request, response, status, validation, idempotency, and Circe codec
     models live in `node-common`. JVM admission, persistence, wait
     coordination, Tapir endpoints, and Armeria adapters live in `node-jvm`.
   - Application admission failures before durable acceptance are returned as
     validation failures with stable reason codes and optional stage/transaction
     locations. After durable acceptance, application-owned failures are
     projected as pipeline status changes without adding application semantics
     to consensus artifacts.
   - Successful `POST` responses return a pipeline snapshot. A newly accepted,
     still-running, or server-side wait-timeout snapshot uses HTTP
     `202 Accepted`; idempotent replay, query, terminal, and unavailable
     snapshots use HTTP `200 OK`. Validation failures use `400 Bad Request`,
     idempotency conflicts use `409 Conflict`, and missing pipeline queries use
     `404 Not Found`.
   - Accepted pipeline metadata, stage payload references, transaction hashes,
     placements, and observation links must survive restart. A node rebuilds a
     query snapshot from persisted metadata plus persisted or replayed
     HotStuff observations.
   - Nonterminal pipelines are retained until they reach a terminal projection.
     Terminal pipelines are retained for a configurable grace period, default
     `24 hours`, and then become eligible for explicit pruning. Until automatic
     pruning is wired by an embedder, nodes exposing the endpoint to unbounded
     public traffic must configure and run a pruning sweep.

## Consequences
- A single-transaction convenience API can be offered without creating a second
  internal execution model.
- New clients can submit same-stage proposal candidates and
  certified-ancestor dependent stages in one request without racing client-side
  timing.
- The node implementation can converge on one internal pipeline engine for
  admission, held-stage tracking, proposal selection, validation, status, and
  metrics.
- Stage-level observability becomes part of the public contract. This improves
  debugging but requires additional persisted or reconstructable pipeline
  metadata.
- Pipeline submission increases API and storage complexity:
  `pipelineId`, stage indexes, transaction indexes, stage barriers, batch ids,
  and per-stage consensus observations must remain correlated.
- The stage model is conservative. Independent transactions placed in later
  stages wait for earlier stages even if they could have been proposed sooner.
  A future dependency-graph optimizer may improve this under an explicit policy.
- Proposal input keeps cross-stage work out of the same proposal when the stage
  barrier requires certified ancestry. Vote validation enforces the same safety
  through ADR-0031 dependency branch checks, not through consensus-visible stage
  metadata.

## Rejected Alternatives
1. **Expose only the pipeline endpoint and no single-transaction convenience
   endpoint**
   - This would force simple clients to adopt a stage-shaped response even for a
     one-transaction submission.
   - A single-transaction convenience endpoint can remain a thin projection over
     the same internal pipeline model.

2. **Use a flat ordered array**
   - A flat array cannot distinguish transactions that may share a block from
     transactions that require a certified-ancestor barrier.
   - Treating every adjacent transaction as a barrier is overly conservative.
   - Treating every adjacent transaction as same-block ordered execution breaks
     certified-ancestor dependent transaction safety.

3. **Require clients to submit an explicit dependency graph**
   - A graph can be more precise, but it exposes too much application-specific
     structure to simple clients.
   - Clients may name dependencies incorrectly or omit edges that the server can
     infer from signed transaction contents.
   - Stage ordering is a simpler public contract. Graph inference can remain an
     internal or future advanced feature.

4. **Only accept later-stage transactions after the previous stage finalizes**
   - This serializes finality paths and loses the latency benefit described in
     ADR-0031.
   - The required safety boundary for proposal eligibility is certified
     ancestry, while external settlement can still wait for finality.

5. **Allow cross-stage transactions in the same proposal if the application
   reducer can order them**
   - This changes the meaning of a stage barrier from certified ancestry to
     intra-block execution order.
   - It would make the pipeline API ambiguous and harder to validate across
     independent validators.
   - Same-block execution remains available by placing transactions in the same
     inner stage.

## References
- [ADR-0031: Certified Ancestor Dependent Transaction Pipelining](0031-certified-ancestor-dependent-transaction-pipelining.md)
- [ADR-0029: HotStuff Proposal Tx Uniqueness Policy](0029-hotstuff-proposal-tx-uniqueness-policy.md)

## Follow-Up
- Define the compact public single-transaction convenience endpoint once the
  pipeline projection is stable.
- Define concrete embedder validation reason codes for stage-derived
  dependency branch conflicts and unavailable branch ancestry.
- Decide when ADR-0031 runtime-only observation timestamps are promoted into a
  public node projection.
