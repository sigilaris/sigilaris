# Codec and Prefix Laws (Notes)

- OrderedCodec law: sign(compare(x,y)) == sign(encode(x).compare(encode(y)))
  - Property-based testing: generate pairs (x,y), assert law holds.
  - Provide base instances: ByteVector (identity). Others to follow.

- Prefix-free rule (byte-level):
  - No two prefixes are equal
  - No prefix is a proper prefix of another
  - Validate with `PrefixFree.isPrefixFree` over `[len(NS)]NS[0x00][len(Name)]Name[0x00]`-encoded prefixes.

- Suggested testing approach:
  - Unit tests for small sets; fuzz with random NS/Name to ensure validator correctness.
  - Aggregation: union of module prefixes remains prefix-free; add regression tests for composition.

