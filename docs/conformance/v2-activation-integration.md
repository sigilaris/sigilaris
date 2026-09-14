# V2 activation integration

This document describes the M3 candidate implementation. The implementation and
evidence gates are tracked in the [conformance map](v2-evidence-map.md); a passing
local fixture does not publish artifacts or authorize a production deployment.

## Preserve the two handover frontiers

The finalized drain checkpoint **F** and certified continuation parent **P** are
different facts. Preserve the original proposals, certificates, execution plans,
state payloads, normalized results, highQC, lockedQC, voter watermarks and profile
provenance from F through P. Every proposal in a finality proof selects its own
authenticated historical height/configuration range. M1 and M2 are separate
release profiles even where their wire version integers coincide.

`ActivationStore.prepare` references a complete stopped consistency group and
inactive, explicitly decoded schema conversion. `commit` durably selects all its
namespaces with one frozen ActivationCommit. Immutable schema images retain the
same decoded application roots and records. Application conversion belongs in the
signed compatibility-singleton opening block, rather than schema migration.

Only a committed selection produces `VerifiedActiveGroup`.
`HistoricalCanonicalVerifier.verifyInstallation` requires that private capability
and compares the complete replayed P payload with installed state readback. The
resulting target-context anchor at P is an execution base. Public finalized state
still starts at the actual old-context F.

`HistoricalCanonicalRuntime` separately journals original-profile canonical
advances from F through P, with full real finality and original state/results.
Prepared/Committed records retain the original context. A cross-boundary proof
is fully checked before its first append. The complete old canonical history must
reach P before the first target ApplicationPrepare can be accepted, including
during recovery of a previously written target prepare. Ordinary target batches
never relabel old blocks as V2 application batches.

Use `FinalizedApplicationRuntime.handover` with that same installed historical
runtime and target safety store. Its `status.canonical` and `canonicalPayload`
expose actual finalized state; `workingParent` separately exposes P. Automatic and
explicit voting share the materializer gate through actual key use.

## Activation journal bindings

The frozen V2 journal operation tags remain unchanged: ActivationPrepare is 6,
ActivationCommit is 7, BootstrapBind is 8, Fence is 9 and BootstrapVote is 12.
`ActivationOriginalEvidence` is an auxiliary canonical format, with fields in
wire order: `format: Long = 1`, `handover: HandoverEvidence`,
`preparation: ActivationPreparation`, `preparedGroupDigest: Hash`.
It uses the [shared primitive encodings](v2-core-schema.md) and is forced in the
`activation-original` blob namespace under `ActivationPreparation.digest`.
Recovery decodes and reauthenticates its complete original products; an arbitrary
blob at that key cannot authorize installation.

Logical replay checks every committed prefix's structure, original witnesses,
acquisitions and any recorded index digest while the safety store remains fenced.
Complete transition/application/exact authentication then checks the entire
history before a pending record is completed or ordinary readiness is published.
The original Bound/Prepared prefixes are preserved; they are not required to
already have the later Opened/Committed status.

Fresh application key use requires two ephemeral permissions: the exact
context/preimage under the finalized-application gate, and the exact
context/validator/preimage/full committed history under the safety gate. Both
remain held through the actual controller call and clear on failure/cancellation.
Installed proposal/timeout/new-view signatures also require the two gates; their
control path derives exact bytes from canonical typed requests and refuses
application/vote request tags. Initial quorum signing instead requires the actual
sealed installer holding its complete-source, exact-request permission. A forced
initial intent by itself cannot authorize a new key use after source loss.
`withVotingPermission` by itself grants no key-use permission. The public
`voteLock` and `voteEffect` materializer methods supply the same exact boundary
for fast-path voters. At actual key use, original-domain finality must still be
strictly below the signed deadline; cached original signatures remain historical
evidence, and inclusion at the signed deadline follows the original block rules.

## Stop and retain actual authorities

The independently installed consistency layout must cover application state,
results/replay, blocks, consensus safety, application safety, exact admission and
idempotency, generic pipelines, configuration, key-controller history, every fixed
evidence obligation and any existing historical canonical ledger. Missing files,
unlisted roots, aliases and unsupported schemas fail closed. A caller-selected
subset or an empty directory does not establish completeness.

All original runtime mutations must use the installed lifecycle gate. The
concrete gate is bound to the identical key controller and original context.
Capture stops these mutations, forces an irreversible old-context write closure,
then retains all actual bytes and inactive outputs. Prepare and commit recheck
the group while holding the same stopped scope. A changed parent, namespace or
post-backup safety history invalidates stale preparation. Failed preparation
never removes the closure, issued promises, signatures or voter watermarks.

`FileFenceController` owns the signing key and durable canonical-write adapter.
An independently authenticated original key-use inventory is required when
adopting an existing key. The supported runtime routes every proposal, vote,
timeout, new-view, application vote and initial vote through its installed
controller. Current authorization and historical signature authentication have
different purposes: later state changes do not erase a valid original signature.
The controller's forced intent still precedes every new use of the key.

The default historical M1/M2 runtime did not retain the complete original
pre-sign highQC/lockedQC/voter history required by this handover. A release label,
current pacemaker snapshot, empty store or recreated watermark does not make such
a deployment eligible. The supported historical adapter must actually have
recorded original safety evidence before old signatures were issued.

## Drain and initial bootstrap

Each relevant application-voting domain independently requires complete deadline
coverage, a justified conservative inferred horizon, or independently proven
never-enabled voting. Reconcile every surviving admission, claim and pipeline;
finalize strictly beyond the applicable original-domain horizon. Enforcing a
fence, archiving consensus history or advancing a fresh chain does not expire an
old claim. Original-compatible progress must remain possible until drain finishes.

For a fresh bootstrap, force the source write fence before selecting the source
snapshot. Verify complete non-empty state and the fixed source/retired evidence
baseline, including independent never-enabled and rollback/non-ancestry evidence
for accepted historical absence. Expected-present content cannot be reclassified
as absent after loss. Keep archive kinds and original content digests distinct.

`InitialStateInstaller` durably binds the signed bundle and installed state/safety
inventory in the actual target journal before preparing or signing an initial
vote. Recovery resumes the same identity. G is an initial header/subject at height
zero, with its actual bundle-bound initial quorum; it is not an ordinary Proposal
or a fabricated finalized checkpoint. Only its first ordinary descendant may
execute the authenticated signed opening, as the sole compatibility entry with
empty lock subset and `None`. Ordinary descendants then follow the V2 profile.

## Startup and restore

Open exclusive physical storage, recover the original controller and immutable
evidence, and resolve the committed namespace selection before opening ordinary
listeners or signing. `ActivationStore.selectedGroup` may inspect a previously
committed selection while an ordinary application tail is pending; it grants no
ordinary readiness and does not resolve that tail. Compose the activation
original-history authenticator with application/exact/voting recovery and the
historical canonical runtime. All complete original records authenticate before
the target safety store becomes ready.

Unknown physical outcomes retain complete tails and require forward recovery.
Partial or corrupt records remain intact and fenced. Lost original state,
archives, baseline evidence, fences or post-backup history cannot be repaired by
relabeling a digest, resetting a journal or selecting another bootstrap bundle.

`verifyRestore` records eligibility only. It does not perform a filesystem restore.
Eligibility requires the actual complete group, current signing/write controls,
and preservation of every promise, canonical write and safety update acquired
since backup, including the target domain. If that evidence cannot be retained,
use forward recovery or a separately authenticated reverse transition.

Local exclusive locks, checksummed history and observed-prefix checks do not
detect a coordinated external rollback of all disks and keys after process loss.
Independently retained baseline/fence/key-use evidence and storage durability
guarantees remain explicit deployment prerequisites.

Retired preservation is complete per domain and evidence kind. A partly absent original archive retains both its authenticated PresentContent inventory and its fixed pre-baseline HistoricalAbsence record. The original schema interpreter supplies `PresentArchiveAudit.presentScopes`; these scopes must be nonempty, sorted, unique, and disjoint from accepted missing scopes. Duplicate records of the same kind, omitted domains, overlapping present/absent scopes, and later loss of expected-present content remain invalid. This audit field describes independently checked original coverage; it does not change the frozen wire products.

Direct embedders can use `FinalizedApplicationRuntime.voteConsensus` with the verified proposal and the durable voting instance bound to the same installed controller/safety store. Like `voteLock` and `voteEffect`, it holds the current canonical-materialization gate and exact sign-byte permission through durable acquisition and actual signature. Exact proposals still require their normal full pipeline readiness before acquisition; the ordinary `HotStuffApplicationVoting` dispatcher performs that orchestration. Exported handover fixtures use this public operation rather than package-private permission callbacks.

## Complete configured audit scope

`TransitionPolicy.applicationDomains` is trusted configuration and must enumerate every historical application context that requires drain, including retired contexts with retained content. The verifier cannot discover omitted domains from a policy that did not name them; an empty vector is not evidence that no application ran. Independently authenticated never-enabled deployment and signer interval unions must each cover the full attested lifetime, as well as the existing per-identity record-to-audit coverage checks. Different deployments or keys may legitimately overlap; gaps and empty audit scopes fail.

A domain with a historical interval containing no authorized signer cannot satisfy this never-enabled route. Such deployments need an explicitly designed evidence alternative; a fabricated signer interval or omitted lifetime segment is not a valid substitute. This deployment-fit question is retained in plan 0034.
