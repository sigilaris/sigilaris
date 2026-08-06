# ADR-0034: Canonical BigNat Wire Encoding

## Status

Accepted for Sigilaris 0.2.12.

## Context

BigNat is used transitively in durable records, wire messages, signature
preimages, hashes, and consensus inputs. Releases through 0.2.11 reserved
`0xf8` for an exact 120-byte magnitude, but the encoder also emitted `0xf8` as
the long-form marker for 121 through 255 bytes. Those bytes are ambiguous: a
decoder must consume `0xf8` as the exact-120 prefix and cannot determine whether
the following byte is magnitude data or a legacy length.

## Decision

1. Values `0x00` through `0x80` use their single-byte representation.
2. Magnitudes of 1 through 119 bytes use `0x80 + length`, followed by the
   unsigned big-endian magnitude. Single-byte values at most `0x80` may not use
   this form.
3. A 120-byte magnitude uses the historical exact form `f8 || data(120)`.
4. Magnitudes of at least 121 bytes use
   `prefix || unsigned-big-endian-length || data`, where the length width is
   `max(2, minimalUnsignedWidth(length))` and `prefix = 0xf8 + width - 1`.
   Thus 121, 255, 256, and 65,536 bytes begin with `f9 00 79`, `f9 00 ff`,
   `f9 01 00`, and `fa 01 00 00`, respectively.
5. Decoders reject leading-zero magnitudes, redundant short form, long-form
   lengths of 120 or fewer, over-wide length fields, truncation, and lengths
   above `Long.MaxValue`. Primitive decoding remains compositional and returns
   a remainder; whole-value boundaries must additionally require full
   consumption.
6. There is no heuristic migration for legacy 121-255-byte output. Operators
   must inventory typed authoritative data, re-encode from that source, and
   coordinate writers and validators or temporarily gate the affected range.

## Consequences

- Exact-120 bytes remain stable while every magnitude size has one canonical,
  unambiguous representation.
- Previously persisted 121-255-byte output cannot be repaired safely without
  its type/schema boundary and authoritative value.
- Any signature, hash, commitment, or consensus object containing an affected
  value changes bytes and must be migrated as a protocol change.
- Sigilaris 0.2.12 deliberately ships this repair at the requested patch
  coordinate despite the project's `early-semver` setting; consumers must
  follow the coordinated migration guidance in the release notes.
- Because `early-semver` treats 0.2.x patch versions as compatible, dependency
  resolution may silently evict 0.2.11 to 0.2.12. Consumers must pin a single
  Sigilaris release across all modules and enforce the resolved version in CI;
  default compatible-eviction diagnostics are not a migration guard.

## References

- [v0.2.12 release notes](../dev/v0.2.12-release-notes.md)
- [English BigNat codec rules](../../site/src/en/byte-codec/types.md#bignat-natural-numbers)
- [Korean BigNat codec rules](../../site/src/ko/byte-codec/types.md#bignat-자연수)
