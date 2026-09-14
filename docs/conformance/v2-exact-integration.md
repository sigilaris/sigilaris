# V2 exact consensus integration

This additive API uses `org.sigilaris.node.txpipeline.v2` and
`org.sigilaris.node.jvm.runtime.application.v2`. The legacy exact pipeline and its historical bytes retain their original contract.
The exact JSON discriminator is `exactV3`, with schema/codec 3; the application protocol and safety journal remain version 2.
See the [exact schema](v2-exact-schema.md), [durable integration guide](v2-durable-integration.md), and
[application storage boundary](v2-application-storage.md).

## Authenticate and admit

Configure `ExactPlanAuthentication.authenticated` with source-transaction authentication, manifest-bound input/declaration/creation
authentication and the exact profile. The profile verifies the actual outer signature, derives the explicit producer output reference, and
interprets producer/consumer results. It is retained by the private `VerifiedExactPlan`; candidate and canonical application cannot substitute
another profile. Source authentication must verify signed literal fields as well as state-dependent inputs, the admission base and common
deadline. The consumer signs an explicit opaque output promise. Its actual producer output is checked later without changing signed inputs.

Open one `JournalSafetyStore` with `ExactRecoveryAuthentication.application` when canonical application is configured, or its `.voting`
variant for a voting-only integration. Use `JournalExactPlanStore.journaled` and `ExactConsensusExecutionRuntime.journaled` over that same store.
Admission verifies the actual supplied signed plan, allocates the node-facing pipeline identifier, and durably binds stage transaction ids,
execution ids, output slots, original request and initial idempotency key before returning. Node identifiers are not consensus execution ids.
Submitting the same work preserves its original identity; an additional idempotency key is recorded in authoritative alias evidence.
Stage/output ownership survives application, expiry, failed candidates and restart. New work cannot reuse a previously owned source transaction.

Recovery authenticates source closure against its original immutable safety projection. An application authentication adapter must not call
back into the same safety-store gate while that gate is held. A read-only admission projection can assist source resolution, but the durable
registration and ownership checks remain authoritative and are repeated independently during recovery.

## Validate, reserve and sign a candidate

`ExactExecutionRequestVerifier.authenticated` combines the admitted store, original plan authentication, manifest, ordinary application
request verifier, candidate repository and `CanonicalAncestorLookup`. Repository responses are untrusted until their canonical bytes, ids,
signatures, profile, actual pre-state and full execution/footprint are checked. Artifact references must identify artifacts actually used by
the selected sources. Consensus sources require an input lock precisely for a nonempty eligible subset and require no fast effect quorum.
Certified-fast sources retain their separate nonempty lock and full effect certificate requirements.

Use `.ordered`, `.producer` or `.consumer` to obtain a private candidate capability, then invoke the corresponding runtime execution method.
OrderedAtomic runs the two stages consecutively in one ordered wave. CertifiedAncestor verifies an approved certified parent path to the
producer and checks its actual normalized output. Other independent entries can share the proposal; the complete proposal is reserved and
every admitted exact stage must independently obtain readiness before that proposal can be signed.

Successful execution returns `PreparedExactCandidate`: an immutable speculative result, its verified proposal and selected execution ids.
It does not publish canonical application state. Complete bounded reservations and their consensus intent are durable before either success
or an authenticated reducer rejection is returned. A deterministic rejected result retains its complete attempted access trace, exposes no
partial canonical producer output, and grants no signing permission. Missing authentication cannot create this rejected-execution capability.

After every exact entry is ready, call `DurableApplicationVoting.prepareConsensusVote(verifiedProposal)`, then
`signConsensusVote(preparedToken)` with the returned token.
The signing gate rechecks the original durable intent, current branch/state, live claims, deadline and per-execution exact readiness. Readiness
is deliberately ephemeral: recovery requires successful exact execution again, even when the same prepared consensus intent is retained.
One successful pipeline cannot authorize another pipeline's failed stage in the same proposal. Abandonment never releases a reservation.

## Historical ancestry and unavailable data

`CanonicalAncestorLookup.authenticated` verifies the currently approved pacemaker parent and real highQC, historical manifests/validator sets,
actual proposal signatures/QCs, parent ids/heights/state roots, complete execution plans and producer source/output. The proof repository must
retain or backfill this evidence below the current finalized tip as well as in the unfinalized certified suffix. The producer may be the direct
parent or any eligible earlier ancestor. Neither a caller-supplied id vector nor equality with the finalized tip is a proof.

`Unavailable` covers missing history, execution witnesses, approval or local capacity; `Invalid` covers contradictory, unrelated, malformed or
wrong-domain material. Both prevent a vote. Local capacity does not redefine consensus-valid ancestry length. The approved parent is checked
again at the end of lookup and before execution; changing the parent invalidates the prepared candidate.

## Canonical application, expiry and recovery

Use the ordinary `ApplicationCommitVerifier` to authenticate actual finality and complete application payload. Then use
`RecoverableApplicationStore.prepare` and `.commit`. The composed exact recovery authenticator also checks the admitted profile's actual
producer and consumer outcomes, even on a node that did not vote. Exact lifecycle, canonical state/result, applied index and every resolved
owner are selected by the same application decision. OrderedAtomic materializes both stages together; a CertifiedAncestor consumer uses the
earlier producer's canonical applied result. Actual inclusion at the signed deadline can be materialized after later finality.

Expiry requires authenticated same-domain nonapplication at finalized height strictly above the common deadline. This also applies to admitted
stages with no lock or reservation. Their exact terminal evidence is retained in the committed expiry operation. A late valid certificate is
archived with that same terminal resolution and cannot create a live claim or reopen voting. The first recorded certificate reference stays
immutable when equivalent quorum signer sets later produce a different certificate id; full subject equality is still required.

Unknown durable writes or failed recovery authentication fence the runtime. Reopen or explicitly recover the journal, reverify every source,
alias, witness, profile outcome, application proof and terminal resolution, and then re-execute pending candidates before signing. Inactive
blob writes do not expose canonical output. Discarding a failed candidate or restoring a pipeline-only snapshot cannot erase safety history.

## Executable evidence

The maintained neutral consumers are described in [P3 exact evidence](p3-exact-evidence.md). Their public sources exercise both modes and
every lock mask, real signatures, deterministic rejection, ordinary-vote ownership checks, independent pipeline readiness, actual finality,
ownerless expiry and persistent recovery. Source checks and the later standalone artifact checks are recorded separately.
