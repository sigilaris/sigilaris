# V2 Recoverable Application Storage

This is the Phase 2 concretization of the existing `PreparedApplication.preparedStateInventory` and
`ApplicationBatch.statePayloadDigest` fields. The journal product field order and HotStuff signature
preimages remain those frozen in [the runtime schema](v2-runtime-schema.md). The application
preparation operation additionally permits its complete selected claims and witness references;
these rows and their verified finality inventory become authoritative in the same journal record.
This pre-release amendment allows a node with a minority local vote for another execution to
materialize a finalized block while preserving that outstanding vote and its claims.

All products below use the strict v2 canonical primitive codecs (`I64`, `Bytes`, `Vec`, `H32`,
`DomainContext`, and `ExecutionPlan`) from [the core schema](v2-core-schema.md). Fields are in the
listed order; integer format discriminators equal 2. These are retained storage objects, never
standalone voting or finality certificates.

| Product | Ordered fields |
| --- | --- |
| `PreparedStateInventory` | `format:I64, batchDigest:H32, statePayloadDigest:H32, evidenceDigest:H32, plan:ExecutionPlan` |
| `RetainedApplicationHistory` | `proposal:Bytes, plan:ExecutionPlan` |
| `ApplicationEvidence` | `format:I64, kind:I64, context:DomainContext, finalized:Bytes, history:Vec[RetainedApplicationHistory], executions:Vec[ExecutionId]` |

`ApplicationEvidence.kind=1` retains finality for an application preparation/decision; both history
and executions are empty. Kind 2 retains complete nonapplication evidence. Its execution identifiers
are unique and sorted by raw digest bytes. History retains canonical ancestor order. `finalized`
encodes the existing `FinalizedAnchorSuggestion`; each proposal field encodes the existing
`Proposal`. Recovery requires exact re-encoding equality and no trailing bytes, then independently
verifies all three proposal signatures, QCs, parent links, heights, and the installed chain and
validator context. Historical HotStuff byte codecs and signed preimages are unchanged.

The immutable backend namespaces and logical content identifiers are:

| Namespace | Logical digest |
| --- | --- |
| `application-state` | `H("sigilaris.application.state-payload.v2", exact canonical application payload)` |
| `application-evidence` | `H("sigilaris.application.finality-evidence.v2", canonical ApplicationEvidence)` |
| `application-inventory` | `H("sigilaris.application.prepared-inventory.v2", canonical PreparedStateInventory)` |

The state implementation owns the canonical payload format. Its configured authentication hook
must decode the complete payload, recompute the actual state root, independently derive ordered
application results against authenticated parent state, and return the identical canonical payload.
Both these results and their order must equal those preserved by `VerifiedConsensusProposal` from
independent execution. The batch binds this state payload digest and all normalized results. The
inventory subsequently binds the batch digest, state digest, finality evidence digest, and exact
plan. Evidence and state do not point back to the batch or inventory, so this graph has no hash cycle.
The physical file backend separately checks frame/blob integrity and must never reinterpret one of
these logical digests under another domain.

`ApplicationCommitVerifier.authenticated(anchor, requests, validators, stateAuthentication)` is the
public authenticated capability factory. Its methods are:

```scala
def verifyBatch(request: VerifiedConsensusProposal,
  finalized: FinalizedAnchorSuggestion, statePayload: Bytes): Result[F, VerifiedApplicationBatch]
def verifyCandidate(finalized: FinalizedAnchorSuggestion): Result[F, VerifiedCandidateBlock]
def verifyNonapplication(finalized: FinalizedAnchorSuggestion,
  history: Vector[ApplicationHistoryEntry],
  executions: Vector[ExecutionId]): Result[F, VerifiedFinalityAndNonapplication]
def verify(state: SafetyState, journal: DurableJournal[F]): Result[F, Unit]
```

The `Verified*` constructors are private to this factory. Preparing a batch requires a real finalized
candidate and an exact match to the verified request. Before journaling, the store persists all
state, evidence and inventory blobs, then prepares selected reservations and application state in
one operation under the shared safety gate. The preparation may remain inactive across a crash.
The commit decision alone selects canonical payload visibility; terminal owner closure includes the
selected owners in plan order followed by every additional live owner of the same executions in
digest order. Corresponding live locks resolve in that same decision. An unrelated execution's
claim is retained, even where that claim overlaps the now-finalized execution. Ordinary voting
continues to enforce its complete conflict checks.

The independent certificate-archive path may preserve a real externally signed quorum and its eligible lock protection alongside a
minority local claim. It verifies the full signed source and quorum, changes no existing execution subject or deadline, and creates no
local vote intent or actual reservation. An effect certificate is archived evidence; an existing local owner must still match its complete
reexecuted witness. Unknown application/nonapplication after the signed deadline is conservatively retained as live protection until a
verified terminal resolution exists. This permission only adds constraints. New local votes retain all reciprocal conflict checks.
Exact original intent retries preserve their immutable subject/owner/witness and require live execution, an unexpired publication window,
current domain/fence permission, and applicable effect/consensus state checks. They do not add a new acquisition.

Nonapplication evidence starts at the installed authenticated application anchor and supplies every
intervening canonical proposal and complete execution plan through the finalized checkpoint.
The verifier checks each proposal through the same full request verifier, consecutive parent/root/
height links, exact final endpoint, and absence of every target execution from every plan entry.
Expiry additionally rechecks the live store under the shared gate: no target is applied and the
verified checkpoint height is strictly greater than every target's common inclusion deadline.
The evidence digest in each terminal resolution names the actual retained proof bytes.

`JournalSafetyStore.open` requires `SafetyRecoveryAuthentication`. Before recovery permits any
signing, the application implementation reads every prepared inventory, state payload, and terminal
proof, reconstructs verified proposals, repeats state/result authentication and finality/nonapplication
verification, and checks the selected decision and every terminal reference. Missing or altered
referenced material fences startup. The public `SafetyRecoveryAuthentication.voting(requests)` factory combines full retained voting
source/witness authentication with an operation-scope filter that rejects application or terminal
operations. Application deployments combine `VotingRecoveryAuthentication.authenticated(requests)`
with `ApplicationCommitVerifier` through `SafetyRecoveryAuthentication.combine`; both checks must
succeed. The Phase 2 application authenticator likewise rejects later
exact/bootstrap/activation operations until their dedicated authenticated dispatch is installed.
It is intentionally bound to one installed domain; Phase 5 must dispatch authenticated historical
contexts before supporting a cross-domain handover history.

Canonical reads resolve only the current decision's preparation and state payload, and recheck
its logical content identifier. Missing/corrupt state fences the shared safety store. Full prepared
tails recover forward after validation; partial or corrupt physical journal tails are retained and
fail closed. Successful file writes assume a trusted local filesystem honoring JDK file force,
atomic same-directory replacement and directory force; unsupported barriers return typed storage
failure without a weaker fallback.

The Phase 2 review also fixes the timing boundary for the remaining exact implementation. Candidate execution and bounded reservation
preparation happen before finality and must return a separate candidate-preparation result. They cannot call the canonical overlap
exception or expose canonical state. Only actual authenticated finality subsequently yields `VerifiedApplicationBatch` and the existing
`PreparedApplication`/commit path. The pre-publication exact schema's `executeOrdered`/`executeProducer`/`executeConsumer` return signatures
currently name `PreparedApplication`; Phase 3 must amend those signatures and their public fixtures to preserve this distinction. Failure
of an ordered consumer must still leave no partial committed producer, and failed candidate reservations remain bounded until authenticated
application or expiry.


P5 review strengthened same-process original-history preservation: `FileApplicationJournal.recover` now requires the recovered canonical records to extend every record the live backend already observed. Replacing the physical log and HEAD with a valid shorter prefix cannot erase a previously completed vote, reservation, or transition; recovery rejects it and keeps writes fenced. This differs from a full external disk rollback across process restarts, which still requires the deployment's independent rollback controls. A real file replacement regression covers the retained-prefix check.

During transition-aware recovery, intermediate Bound/ActivationPrepare prefixes are structurally replayed while the store remains fenced. Witness coverage, index reconstruction, original low-level authentication and acquisition checks remain per-prefix. The complete configured original-proof/transition-readiness authentication runs before forwarding a pending record or opening Ready; an early prefix cannot be required to already contain its later Opened/ActivationCommit record.
The observed-prefix rule includes a complete frame immediately after `writeAll`, before the first force callback, and complete frames read during an interrupted recovery. Unknown later force/HEAD/blob outcomes do not erase those observations. The backend also binds the live root directory, lock file and log to their original physical `fileKey` values and checks that affinity before every journal/blob operation. Replacing a path with an identical-byte file cannot redirect or detach the exclusively owned channel.
