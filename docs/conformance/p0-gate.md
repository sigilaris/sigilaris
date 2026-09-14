# Plan 0033 Phase 0 gate

Phase 0 selects the implementation contract and reproduces the immutable public baseline. It does not establish production V2 runtime,
transition eligibility or release readiness. Implementation starts from source `efe4f4529a1c266e024ea8be74b3c7ad58a08847` on 2026-09-11.

## Deliverables

| Item | Concrete evidence |
| --- | --- |
| P0.1 | [Core inventory](core-compatibility-inventory.md) and [runtime inventory](runtime-compatibility-inventory.md), with separate annotated-tag objects and peeled source commits, domain/codec/schema dispatch and vote/deadline boundaries. Public M2 preimages and expected failures are pinned in maintained fixtures. |
| P0.2 | [V2 contract](v2-contract.md), exact [core](v2-core-schema.md), [runtime](v2-runtime-schema.md) and [pipeline](v2-exact-schema.md) schemas/API signatures. Development artifact `0.3.0-M3-SNAPSHOT`, candidate M3. [Neutral opening oracle](p0-opening-contract.md) executes the selected policy with real maintenance signatures and negative cases. |
| P0.3 | [Public M2 baseline](m2-baseline.md): five coordinates, ten POM/JAR checksums, actual compiler classpath checks, JVM/Scala.js normal/full execution and pinned historical bytes/digests. |
| P0.4 | [Requirement-to-evidence map](v2-evidence-map.md), including explicitly planned production fixtures, public-export locations and separate old/new/release evidence classes. No deployment support is assumed without authenticated evidence. |

P0.2's opening fixture is an independent policy oracle built with public M2 primitives. It exercises signed source/parent/manifest/deadline
binding, maintenance authority, deterministic conversion, complete read/write/creation coverage, reservation deadline/domain equality,
bootstrap lock rejection and ordinary handover lock presence/freshness. It does not claim M2 implements opening or validate the future V2 wire
codec. P1/P2/P5 must reproduce these oracle decisions through the production input/certificate/reservation/execution boundaries. This division
keeps contract proof before implementation and production boundary proof at the corresponding implementation gates.

## Review and corrections

Reviews covered code inspection, public consumers and schema consistency. A subsequent review evaluated the corrected result, not only the
original finding. The phase's final verdict is recorded after the last cross-schema correction below.

| Review scope | Findings reflected in the final contract |
| --- | --- |
| Initial core/runtime contract | Retained historical body-root codec/domain; corrected conflict-free source-id ordering; bound full classification statements/proofs; fixed metadata-bearing empty witness and canonical segmentation; specified exact API/codec layouts. |
| Storage and transition schema | Separated eligible lock claims from complete proposal reservations; gave empty-block votes a distinct intent without fabricated executions; removed circular evidence/bundle/fence commitments; added certificate-import and bootstrap-signing journal operations. |
| Historical identity | Distinguished annotated tag objects from source commits, M1/M2 validation provenance, and structural historical DomainContext decoding from new protocol-2 activation checks. |
| Exact pipeline | Removed execution-id/plan-digest self-reference; signed admission source kind; fixed explicit producer-output reference binding and verification times; retained atomic stage/output ownership through terminal state/recovery; made exact admission/lifecycle journaling explicit. |
| Opening oracle | Added the missing `candidateHeight > authenticatedBaseHeight` check and equality/below-base negative vectors, then reran JVM/JS normal/full. |
| Public artifact consumer | Verified actual compiler classpath origin/hash rather than only downloaded copies; distinguished synthetic baseline authenticators/supplied ancestry from later real cryptographic/runtime gates. |

M2 consumer review and the corrected opening oracle review reported **No finding**. The final core/exact and runtime/exact contract
cross-reviews both reported **No finding** after the corrections. The root self-review and final documentation/fixture checks found no remaining
Phase 0 finding. **The Phase 0 contract/public-baseline gate passed on 2026-09-11.** Production implementation gates remain unchecked.

## Actual checks

| Command / inspection | Result |
| --- | --- |
| `cd release-smoke && ./public-m2-baseline.sh` | Exit 0; all ten public artifact checks, five actual classpath identities, JVM and JS normal/full pass. [Retained output](../../release-smoke/fixtures/m2-baseline-output.txt). |
| `cd release-smoke && ./p0-opening-contract.sh` | Exit 0 after the base-height correction; JVM and JS normal/full oracle pass. [Retained output](../../release-smoke/fixtures/p0-opening-output.txt). |
| `shasum -a 256 -c fixtures/m2-baseline-sources.sha256` in release-smoke | All 12 recorded build/fixture inputs match. |
| `shasum -a 256 -c fixtures/p0-opening-sources.sha256` in release-smoke | All 14 recorded build/fixture inputs match. |
| `scalafmt --test` over the standalone build and five Scala fixture/entry files | All six files formatted. |
| Shell syntax, Markdown relative-link/public-export targets, matching 28 plan/checklist ids, `git diff --check` | Passed; final phase changes are rechecked before commit. |

The baseline report records exact tool versions and immutable artifact identities. None of these commands runs the final five source suites,
four-validator V2 runtime E2E, a live activation, candidate staged-artifact gate or future public-M3 release check; those remain later gates.

## Lessons applied to the remaining phases

1. **P1:** Use new types/codecs/domains with explicit historical provenance. Preserve canonical source ordering and body membership independently
   of execution ordering. Signed execution inputs must form an acyclic hash graph; actual result/proposal ids are attached after their inputs.
2. **P2:** Persist lock subjects and eligible lock claims without turning read/consensus-only accesses into distributed locks. Persist every
   complete reservation before proposal/effect signing, including lock-free work. Empty-block consensus intent has no synthetic execution id.
3. **P3:** Bind the consumer's exact output reference before admission and verify its actual output/ancestry at consumption. Atomically retain
   stage/output ownership and exact admission deadlines across separate admission/journal stores; rebuild them before reopening.
4. **P4:** Supplied ancestry context tests establish a consumer response only. Exercise ordinary proposal input, historical proof lookup,
   empty-block signing and actual independent-validator finality. Initial justify must be a real bundle-bound QC with an initial-only verifier.
5. **P5:** Freeze an acyclic transition intent before source fencing/snapshot selection. Preserve the resulting baseline and every later safety
   promise. Verify old-domain context/progress/drain evidence independently; neither a fresh chain nor an absence attestation expires old claims.
6. **P6:** Keep M2 historical consumers immutable, use a separate candidate mode/build, and record source-free staged resolution separately
   from future public-Maven availability. Exported `main` fixtures must carry the necessary checks because `src/test` is excluded.
