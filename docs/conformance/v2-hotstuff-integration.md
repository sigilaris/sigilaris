# Ordinary HotStuff application integration

The V2 application path uses the standard `HotStuffNodeRuntime` proposal builder,
pacemaker and gossip transport. Configure
`HotStuffProposalInputRuntimeConfig.application(assembly, legacyProvider)` and
`HotStuffProposalValidationRuntimeConfig.application(voting, finalization, additionalProvider)`.
Both node creation and topology bootstrap require the typed voting and finalized
application runtimes before attaching an automatic application builder. Startup
recovers the actual application journal before listeners and automatic votes run.

## Proposal construction and retained evidence

`HotStuffProposalApplicationAssembly.default` receives an authenticated historical
profile schedule, actual parent/QC lookup, immutable application executor, plan
preimage storage and maintenance driver. It derives the header from actual
sequential results, preserving the existing block-body encoding. It verifies the
parent signatures, quorum, height and selected profile; validates plan structure,
source membership and per-entry roots; and retains and reads back the canonical
plan before supplying the standard builder input. The embedder provides source
execution and historical evidence, rather than replacing the header builder.

`HotStuffExecutionPlanPreimages.journaled` shares the application's
`JournalSafetyStore` gate and failure fence. Missing evidence remains unavailable.
Corrupt or ambiguous local storage cannot silently fall back or leave an earlier
signing permission active. A backfilled plan is authenticated by full canonical
decoding and its committed root. Durable vote/application evidence also retains
the complete original plan for recovery.

## Verification, signing and finality

Automatic and explicit vote emission use `HotStuffApplicationVoting.journaled`.
The selected manifest and journal context must agree. The configured
`ApplicationRequestVerifier` authenticates the actual proposal, complete plan,
signed sources, certificates, declarations, classification and actual state reads
and results. Every selected exact pipeline executes through its P3 mode verifier;
readiness for one pipeline does not authorize another pipeline's vote.

The runtime uses `DurableApplicationVoting` on that same safety store to retain a
consensus anti-equivocation intent and complete reservations before signing the
existing HotStuff vote bytes. Empty proposals also retain an anti-equivocation
intent. A second proposal in the same voting window returns a conflict without
poisoning valid retained history. Replay still rejects conflicting original
records as corrupt history.

The closed finalized application runtime checks actual materialization readiness
under its own gate and binds it to the identical safety-store instance. That gate
remains held through verification, durable acquisition and signing. Actual
consensus finality triggers authenticated ordered materialization; unavailable
application data leaves the consensus fact intact and blocks further application
votes until recovery. See [finalized application integration](p4-finalized-application.md).

## Historical compatibility

The six-argument `HotStuffProposalInput` and two-argument legacy runtime
configuration constructors remain available. A historical V1 header still uses
the legacy signing path when no application dispatcher is configured. Header 2
alone does not identify application protocol 2: M2 already used that header.
Signing a header-2 proposal requires the typed dispatcher and an authenticated
profile. An explicit `LegacyM2` profile invokes its required historical payload
authenticator; `ApplicationV2` invokes the new durable verifier. An unconfigured
allow-all hook cannot authorize header-2 votes. Existing embedders that used a
header-2 provider must supply this historical profile/authentication boundary.

The schedule cannot infer M1 versus M2 from protocol integer 1. It must select
the original artifact semantics, validator history and activation range. P5 adds
the authenticated range and initial-genesis implementation. An imported genesis
has no ordinary parent proposal and must use its initial-only certificate path.

## Empty progress and maintenance

An activated empty proposal has the canonical empty plan and body roots and its
actual parent's state root. An empty executor result cannot introduce a hidden
state change or bypass idle quiescence. `HotStuffMaintenanceProgress.bounded`
uses an explicit command identity, complete application context and inclusive
finalized-height target. It can continue ordinary empty rounds after the last
transaction has finalized and stops once actual finality reaches that target.

The embedder durably retains the maintenance command and reconstructs it after
restart. Attempt and elapsed-time budgets report a stall and preserve the target.
They do not expire a lock or reservation. P5 computes drain targets strictly above
the relevant recorded and inferred old-domain deadline horizons. Source-chain
and newly bootstrapped chain heights remain distinct.

## Executable evidence

The exported `V2RuntimeConformance` and `V2RuntimeMaterial` use four independent
identities, file journals, ordinary node runtimes and twelve directed transport
sessions. They retain original proposals by full proposal identity, including
different valid quorum representations of the same block. Replaying a child
resolves the parent named by its actual QC rather than a latest block wrapper.
Finalized application and exact public fixtures exercise the same maintained
production boundaries. Actual commands and final results are recorded in the
P4 gate; the independently resolved M3 artifact gate is P6.

## Repeated finality and maintenance capacity

The finalizer retains one successful complete observer proof. An identical notification skips the full drive only when no failure or pending target remains. Changed proofs, fatal tracker faults, failed/cancelled drives and explicit `recover`/`finalized` calls keep their verification paths. A failed or cancelled voting/signing operation invalidates observer reuse before releasing the shared gate, so the next identical notification can recover an unknown durable write. Fresh signing still checks current finality, original sources and the actual journal. This reduces duplicate gossip work but does not bound lifetime journal replay cost.

`HotStuffMaintenanceProgress.bounded` retains at most 4096 local request identities. `boundedWithCapacity` selects a different positive maximum. A new request at capacity returns `Stalled`; no old identity or attempt budget is silently evicted. Existing requests still observe genuine finality after their attempt budget is exhausted. Counters and capacity belong to this provider instance; after restart, the embedding source must reconstruct the target from its retained drain command. These limits govern local work and do not change validity or release safety claims.
