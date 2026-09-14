# Plan 0033 Phase 2 gate

This phase implements durable application voting, complete multi-owner reservations and recoverable finalized application for the
unpublished `0.3.0-M3-SNAPSHOT` candidate. Its parent is `b9fa3f0`; the phase commit includes this record. The public integration contract is
[durable integration](v2-durable-integration.md), with [application storage](v2-application-storage.md) and
[exported voting evidence](p2-voting-evidence.md).

## Implementation and trust boundary

Canonical core products cover vote/consensus intents, locks, owner claims, complete witness references, application batches and the closed
prepared/committed journal operation set. The journal retains full canonical evidence; derived state, indexes and inventory digests rebuild
from committed records. Memory and file implementations share the same authoritative projection and recovery rules.

The file backend uses full writes, explicit force, atomic HEAD selection, directory synchronization, immutable checksummed blobs and exclusive
writer ownership. Ambiguous writes fence the signing boundary until verified recovery. This assumes the documented local filesystem contract;
fault injection is not a hardware power-loss certification or detection of a coordinated rollback of journal and HEAD.

Private verified request capabilities require actual transaction/input/authority/creation/scope/certificate authentication and independently
instrumented execution. The store forces original request evidence, complete witnesses and the intent/claim transaction before invoking the
signer. Signing shares the same gate with safety publication, terminal updates and recovery. Identical intent retry retains its original proof
and subject; signer failure, cancellation, absent evidence and unknown storage success grant no conflicting vote permission.

Reservations cover the complete read/write footprint independently of eligible locks. They support shared readers and authenticated ordered
overlap, retain independent owners and deadlines, and reject reciprocal external fast conflicts. Actual finalized application atomically binds
selected owner acquisition to finality evidence even on a node holding an incompatible minority claim. That minority claim remains protected.
Verified certificate import similarly archives a completed quorum and its constraints without creating a local vote or full reservation.

Application preparation retains complete state payload, independently replayed results, selected witnesses and actual three-chain finality
evidence. One decision publishes state/result/applied index and terminal updates. Expiry requires complete same-domain canonical nonapplication
history and finality strictly above the signed deadline; it cannot erase another execution's shared reservation.

## Review iterations

The implementation received self-review and independent core, request/voting and storage/application reviews. Findings were fixed and the
affected paths rerun before the final review:

- Authenticated creation absence must include unused declared targets as reads. Actual authentication/precondition reads belong to the witness.
- Recovery must replay retained original requests and compare complete witnesses; a syntactically valid smaller witness is not sufficient.
- Full HotStuff header-version commitments must be checked before proposal execution and vote permission.
- Finalized application and certificate archival must preserve minority promises while ordinary new voting retains reciprocal interlocks.
- Application terminal cleanup closes every live owner of the same execution, preserves unrelated owners and supports late certificate import
  backed by retained terminal evidence. Retry never changes subject, proof bytes or deadline.
- Stored payload loss and ambiguous storage errors fence the shared gate; recovery returns one consistent snapshot. Index reconstruction uses
  maps and sets to handle the authenticated 100,000-identity limit without quadratic scans.

After these corrections, independent request/voting and storage/application reviews and the final self-review reported **No finding**.
The final read-then-write ordered assertion also passed after the complete source gate. P3 staged work is excluded from this review and commit.

## Validation

On **2026-09-11**, the reviewed source completed these gates:

| Command | Actual result |
| --- | --- |
| `sbt -J-Xmx4G 'coreJVM/testOnly *V2JournalSuite'` | 15 passed; 14:25:23 KST |
| `sbt -J-Xmx4G 'coreJS/testOnly *V2JournalSuite'` | 15 passed; 14:26:09 KST |
| `sbt -J-Xmx4G nodeJvm/test scalafmtCheckAll scalafmtSbtCheck` | 776 passed, zero failures/errors; node gate 15:04:51 KST, both formatting checks 15:04:54 KST |
| `sbt -J-Xmx4G 'nodeJvm/testOnly *V2PublicOrderedVotingSuite'` | 1 passed after the final read-then-write assertion; 15:06:00 KST |

The new node coverage consists of 25 file-journal, 5 memory-journal, 21 recoverable-application, 10 pure-reduction, 10 public-voting,
1 public-request, 1 public-ordered and 1 public-scale tests. The full node command also passed all existing node suites.
The production-scale public fixture measured 100,000 identities, 16,749,354 witness bytes and 256 chunks through verification, durable
reservation, signing and restart; its repeat in the final full run passed in 49.454 seconds. Separate codec checks cover exactly
16,777,216 bytes, one extra byte and a 257-chunk shape; local capacity tests reject before signing without changing protocol validity.

Source wrappers compile the exported request, voting, ordered and scale fixtures from `release-conformance/shared/v2-jvm/scala`. Persistent
application and storage fault suites additionally live in module test sources. P6 must export and run the required application consumer cases
from artifact-only builds; these source results do not claim that later gate has passed.

## Lessons applied to remaining phases

- P3 candidate execution must return a speculative candidate result, with complete bounded reservations. P2's finalized application preparation
  requires actual finality and cannot be reused to bypass pre-vote conflicts for an unfinalized candidate. First application fields come from
  the eventual canonical application decision, while certified producer ancestry can be verified before finality.
- P3 registration and lifecycle replay must authenticate the original signed outer plan and preserve stage/output ownership after terminal
  cleanup. Derive execution identity from that registered plan without placing its digest back into its own stage bytes.
- P4 ordinary assembly must compose source and application recovery authentication and preserve empty-block consensus anti-equivocation intents.
  A four-key certificate fixture does not replace four actual runtimes, independent stores and transport.
- P5 must extend historical finality verification using authenticated profile ranges and preserve external fences, preparation baselines and
  voter watermarks against backup rollback. Local journal checks cannot establish a supported legacy drain or ancestry transition.
- P6 must distinguish exported fixture source tests, independently resolved candidate artifacts and a later public Maven release result.
