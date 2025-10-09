# Comparison with Ethereum RLP

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)

---

## Overview

This document compares Sigilaris byte codec with Ethereum's Recursive Length Prefix (RLP) encoding.

**Status:** Work in progress.

## Key Differences

| Feature | Sigilaris Codec | Ethereum RLP |
|---------|-----------------|--------------|
| Small values | 0x00~0x80 (0-128) | 0x00~0x7f (0-127) |
| Type safety | Scala 3 with cats | Dynamic |
| Collections | Deterministically sorted | Order preserved |

---

[← Codec Overview](README.md) | [API](api.md) | [Type Rules](types.md) | [Examples](examples.md) | [RLP Comparison](rlp-comparison.md)
