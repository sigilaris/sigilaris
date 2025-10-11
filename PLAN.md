## JSON Codec Implementation Plan

### Goals
- Library-agnostic JSON abstraction with minimal surface area.
- Strong separation: domain ↔ JsonValue ↔ (Parser/Printer via backend).
- Support product and coproduct derivation with configurable discriminator and field naming.
- Keep `SigilarisFailure` sealed by defining parse failures within it.
- Generate unified Scaladoc (unidoc) and surface it on the sbt-typelevel-site.

### Scope
1) Core JSON types and typeclasses
   - `org.sigilaris.core.codec.json.JsonValue` (small ADT)
   - `JsonEncoder`, `JsonDecoder`, `JsonCodec`
   - `JsonConfig`: field naming, null/absent policy, discriminator config
   - `JsonKeyCodec`: map key codec (A ↔ JString) for `Map[K, V]`
2) Derivation
   - Product (case classes) + Coproduct (sealed traits) via Scala 3 mirrors
   - Discriminator strategy: wrapped-by-type-key object (no inline `_type`)
3) Ops + Backend integration
   - `org.sigilaris.core.codec.json.ops.JsonParser`, `JsonPrinter`
   - Circe adapter for parse/print and AST conversions
4) Failures
   - Add `ParseFailure` to `SigilarisFailure` (preserve sealed trait)
5) Docs and site
   - Unidoc setup; publish Scaladoc into site
   - Add JSON Codec docs (EN/KO) alongside Byte codec docs
6) Tests
   - Roundtrip tests (domain ↔ JsonValue ↔ string ↔ JsonValue ↔ domain)
   - Product/coproduct derivation, config behaviors

### Deliverables
- Core API under `modules/core/shared/src/main/scala/org/sigilaris/core/codec/json/`
- Ops and Circe backend under `.../codec/json/ops/` and `.../codec/json/backend/circe/`
- Updated `SigilarisFailure.scala` with `ParseFailure`
- Site docs: `site/src/{en,ko}/codec/json/*.md`
- sbt unidoc configuration and site integration
- Unit tests under `modules/core/shared/src/test/scala/.../codec/json/`
 - `JsonKeyCodec` typeclass and base instances (String, UUID, numeric)

### Phases
1. Core skeleton
   - Define `JsonValue`, `JsonConfig`, encoders/decoders for primitives/collections
   - Introduce `JsonCodec`
   - Introduce `JsonKeyCodec` for map keys; provide base instances (String, UUID, numeric)
   - Implement `Map[K, V]` encoder/decoder using `JsonKeyCodec[K]` + `JsonEncoder[V]`/`JsonDecoder[V]`
2. Derivation
   - Implement product and coproduct derivation
   - Discriminator: encode as { "<TypeName>": { ... } } with configurable typeName strategy
3. Ops + Backend
   - Define `JsonParser`/`JsonPrinter` interfaces in `codec.json.ops`
   - Implement Circe conversions + parser/printer
4. Failure model
   - Add `ParseFailure` into `SigilarisFailure`
5. Docs + Site
   - Enable unidoc and wire into site build
   - Write KO/EN docs with examples and API notes
6. Tests
   - Add property tests and example-based tests

### Risks & Mitigations
- Performance overhead from AST conversion: acceptable for API use; profile later. If needed, consider backend-native slot optimization.
- Config complexity: start minimal; add options only with concrete use-cases.

### Acceptance Criteria
- JSON codec can encode/decode common Scala types and derived ADTs.
- Coproduct uses wrapped-by-type-key form (no `_type` inline).
- BigInt/BigDecimal are encoded as strings by default; decoders accept both forms.
- Circe-backed parse/print works; site docs include JSON section; unidoc generated.
- Tests cover roundtrip and config-driven behaviors.
 - `Map[K, V]` supported when `JsonKeyCodec[K]` is available; otherwise decode fails


### TDD Approach
- Start with test-first, implement minimal code to compile, then make tests pass, and refactor while keeping green.
- Concrete loop:
  1) Declare data types and APIs with unimplemented bodies as `???` (temporarily suppress wartremover TripleQuestionMark wart for the module or scope).
  2) Ensure compile passes (interfaces stable, dependencies wired).
  3) Add example-based tests (e.g., primitives, collections, product/coproduct derivation, config behaviors) that initially fail.
  4) Implement smallest increments to pass failing tests ASAP.
  5) Refactor for clarity/perf without changing behavior; keep tests green.
  6) Repeat for additional features (discriminator, naming policies, parse/print integration).
- Property tests may be added after example tests for additional coverage (e.g., roundtrip invariants).

### JsonConfig Details
- Field naming: Identity | SnakeCase | KebabCase | CamelCase (encode/decode 양방향 적용)
- Discriminator (Wrapped-by-type-key, no inline):
  - shape: { "<TypeName>": { ...fields... } }
  - typeNameStrategy: SimpleName | FullyQualified | Custom(Map[String,String])
  - unknown subtype: DecodeFailure
- Null/Absent:
  - encode: dropNullValues=true → null 필드 제외
  - decode: treatAbsentAsNull=true → 결측 필드를 null로 간주
- Numbers/Instant:
  - Big numbers default as strings:
    - BigInt: writeBigIntAsString=true (default)
    - BigDecimal: writeBigDecimalAsString=true (default)
    - Decoder accepts both string and number literals for robustness
  - Instant only (java.time.Instant):
    - Encode: `instant.toString` (ISO-8601)
    - Decode: `Instant.parse`
    - Precision: millisecond precision only (sub-millisecond truncated)
 - Map keys:
   - Encode keys via `JsonKeyCodec[K]` into JSON object field names (strings)
   - No additional naming policy beyond the key codec is applied to map keys
   - Decode failure on key parsing errors or duplicate decoded keys after normalization

### Testing Matrix (Example-based)
- Primitives/Collections roundtrip
- Product naming policies (snake/kebab/camel)
- Coproduct discriminator (wrapped-by-type-key):
  - Encode: { "Subtype": { ... } }
  - Decode: 올바른 서브타입 매핑, 알 수 없는 서브타입은 DecodeFailure
- Null vs Absent interactions
- Big numbers as strings by default; decoder supports string/number 입력 모두
- Map[K, A] roundtrip when `JsonKeyCodec[K]` is available
- Key parsing failure or collisions → DecodeFailure
