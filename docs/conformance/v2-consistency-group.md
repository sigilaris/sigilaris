# V2 complete consistency-group capture

This P5 prepublication addition makes the frozen `ActivationStore.prepare(VerifiedHandover, VerifiedConsistencyGroup)` boundary executable. The source is the independently installed complete namespace layout. A caller cannot select a subset at capture time. This document does not advertise any existing production deployment as audited; each deployment must supply original schema, authority, state replay, and mutation-coverage authentication.

`ConsistencyGroupStore.configured` requires the physical layout, immutable evidence baseline, `ConsistencyGroupAuthentication`, actual lifecycle coordinator, all installed schema decoders and explicit migrations, the same key-owning controller, target durable journal, and local capacity. The local capacity is an availability limit, not a consensus validity rule.

## Canonical auxiliary records

All products use the primitive encodings in [v2-core-schema.md](v2-core-schema.md): I64BE `Long`, canonical length-prefixed `Text`/`Bytes`, H32 `Hash`, canonical vector lengths, and the exact shared `DomainContext`/`Height` encodings. Fields below are in wire order. `format` is I64BE 1. There are no implicit filesystem paths or Scala maps in any committed record.

| Product | Fields in order |
| --- | --- |
| `ConsistencyNamespace` | name Text, relativePath Text, schema Long, roles Vec[Role] |
| `ConsistencyLayoutRecord` | format Long, namespaces Vec[ConsistencyNamespace] |
| `ConsistencyFile` | path Text, contentDigest H32, size Long |
| `ConsistencyNamespaceImage` | format Long, namespace ConsistencyNamespace, directories Vec[Text], files Vec[ConsistencyFile] |
| `ConsistencyFrontier` | context DomainContext, blockId H32, height Height, stateRoot H32 |
| `ConsistencyGroupRecord` | format Long, layout ConsistencyLayoutRecord, transitionDigest H32, baseline EvidenceBaseline, canonical ConsistencyFrontier, working ConsistencyFrontier, namespaces Vec[ConsistencyNamespaceImage], controllerSnapshot ControllerSnapshot, writeClosureDigest H32 |
| `ConsistencyPreparedRecord` | format Long, completeOldGroupDigest H32, namespaces Vec[ConsistencyNamespaceImage], retainedSafetyInventoryDigest H32, workingStatePayload Bytes |

Role is one byte: 1 application state, 2 application results, 3 application replay, 4 blocks, 5 consensus safety, 6 application safety, 7 exact admission, 8 exact idempotency, 9 generic pipelines, 10 configuration, 11 fence history, 12 fixed evidence, 13 historical application ledger. Roles 1–12 must all be covered; 13 must be present when independently installed. One physical namespace may implement several roles. Namespaces are sorted by unsigned UTF8 name, roles by tag, directories and files by unsigned UTF8 relative path. Paths are relative, nonempty, canonical, traversal-free, and nonoverlapping across namespaces. File parents must all be represented as directories; a file cannot also be a directory or ancestor.

The domains are `sigilaris.application.consistency.layout.v1`, `.namespace.v1`, `.group.v1`, `.prepared.v1`, and `.file.v1`, respectively. The full prefix `sigilaris.application.consistency` applies to every suffix. File hashes commit raw file bytes; other hashes commit the canonical product bytes with the shared domain framing. Durable blob namespaces are `consistency-content`, `consistency-namespaces`, `consistency-groups`, and `consistency-prepared`.

`transitionDigest` is the complete authenticated `HandoverEvidence.digest`. `completeOldGroupDigest` is `ConsistencyGroupRecord.digest`; it is **not** a generic inventory digest. `originalInventory(record)` deterministically lists every namespace image hash plus the controller snapshot and original write-closure hashes. Its generic transition-inventory digest is `retainedSafetyInventoryDigest`.

## Capture, conversion, and activation

Capture holds the actual consensus/QC/listener/safety/index/pipeline lifecycle gate and then the controller gate. `verifyController` binds that lifecycle to the same controller object and exact old context. The controller first forces the old-context `ControllerWriteClosure` bound to the existing transition intent. Abort, unknown publication, or restore cannot remove this closure. `ConsistencyMutationGate.mutate` rechecks it from the actual controller snapshot, including after restart, so old metadata mutations remain rejected. All mutation paths must actually use that installed coordinator; the original-group authenticator independently verifies their coverage.

The complete physical root is scanned. Unlisted files and even empty directories, missing namespaces, symlinks, nonregular files, path aliases and hardlinks are rejected. Each namespace requires real canonical metadata even when its logical store is empty. Original physical file identities, lengths and modification times are checked around reads, and the layout is rescanned. A single real fence namespace must contain the exact framed `IDENTITY` and `LEDGER` of the stopped controller. Files, namespace images and the full group are forced as immutable target-journal blobs and read back before the capability is returned.

The original authenticator decodes every installed schema, independently verifies the fixed baseline's original present and absence artifacts, and executes the retained F→P historical suffix. `canonical` remains actual source F; `working` is the separately verified certified P. `workingStatePayload` must be the actual replayed P payload. It does not relabel F's canonical state. Original blocks, safety, exact/idempotency/generic records, configuration, controller and evidence remain complete.

Each explicit schema migration runs only against inactive namespace output. Both source and output are decoded by installed schema implementations, with exact equality of logical semantic digest and all application-state roots. Recovery reruns the deterministic installed converter and requires exact output equality. Application root conversion requires the separately signed opening path.

`withRevalidated(cap, handover)(run)` holds both real gates through `run`, including activation decision force. It requires the same current controller snapshot, the same entire physical source image, and a fresh original-closure verification. A newer QC, parent, safety record or source write invalidates the preparation. The private capability retains its actual controller reference; a different controller cannot reuse it for activation signing.

`readOriginal` checks canonical group hashes and every original blob. `recover` additionally reauthenticates handover, all schemas, migration outputs and source evidence. The original controller snapshot must remain a prefix of the current controller history; post-backup promises, potential signatures, watermark and writes are preserved.

## Restore binding

`RestoreAudit.sourceGroupRecord` supplies the original canonical `ConsistencyGroupRecord` bytes. Restore verification decodes them strictly, recalculates the complete-group domain hash, checks the unchanged baseline and original old-writer closure, and requires `audit.sourceGroup == originalInventory(record)`. The mandatory restore authenticator independently rechecks all original content blobs and the current safety/promise/canonical-write closure. Generic inventory hashes cannot stand in for the original group record. All currently relevant old and active contexts require actual controller-enforced restore fences.

## Validation and review lessons

`V2ConsistencyGroupConformance` exercises actual FileChannel-backed capture, inactive conversion, activation preparation/decision/reopen, five physical journal barriers at preparation and commitment, metadata retirement, immutable source loss, layout/alias/root-change rejection and a gate-held decision callback. The neutral genesis fixture authenticates one independently installed key/checkpoint; it is not the four-validator historical continuation release gate. The separate public historical fixture supplies that gate with actual M1/M2 execution, four controllers and F3/P5 finality.

Review amendments: source-directory completeness includes empty directories; recovered migrations must rerun the installed conversion; lifecycle/controller/context affinity is explicit; source F and working P payloads are independently bound; restore uses the actual complete-group hash domain. Prepared output and the original backup never authorize source writers to resume.

The actual public `V2HistoricalGroupConformance` gate now publishes F3 through four original file controllers (including immutable physical CAS receipts), captures each fixed full group, commits each activation and reopens all four controller/target journals. It verifies canonical F3 and working P5 remain distinct and each node selects the identical original decision and group digest after restart. The neutral `V2ConsistencyGroupConformance` restore case additionally verifies old and active-target restore fences against actual post-backup controller/journal inventories and rejects malformed original complete-group bytes.

Both public case families are executed by the [standalone runner](../../release-conformance/README.md); final selected-artifact evidence is recorded in the [P6 gate](p6-gate.md).
