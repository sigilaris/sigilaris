#!/usr/bin/env python3
"""Check M3 signing literals with a test-only RFC 6979/secp256k1 reference.

Uses only Python's standard-library HMAC-SHA256 and affine curve arithmetic.
Neither production backend nor this reference generates the expected literals
at conformance execution time. This tool is never used for production signing.
"""

import hashlib
import hmac
from pathlib import Path
import re


ORDER = int("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
PRIME = 2**256 - 2**32 - 977
GENERATOR = (
    int("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16),
    int("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16),
)


def add(left, right):
    if left is None:
        return right
    if right is None:
        return left
    x, y = left
    other_x, other_y = right
    if x == other_x and (y + other_y) % PRIME == 0:
        return None
    if left == right:
        slope = (3 * x * x) * pow(2 * y, -1, PRIME) % PRIME
    else:
        slope = (other_y - y) * pow(other_x - x, -1, PRIME) % PRIME
    result_x = (slope * slope - x - other_x) % PRIME
    return result_x, (slope * (x - result_x) - y) % PRIME


def multiply(scalar):
    result, point = None, GENERATOR
    while scalar:
        if scalar & 1:
            result = add(result, point)
        point = add(point, point)
        scalar >>= 1
    return result


def nonce(message):
    # Test key 1. bits2octets reduces the 256-bit input modulo the curve order.
    seed = (1).to_bytes(32, "big") + (message % ORDER).to_bytes(32, "big")
    key, value = bytes(32), bytes([1]) * 32

    def digest(secret, data):
        return hmac.new(secret, data, hashlib.sha256).digest()

    for separator in (0, 1):
        key = digest(key, value + bytes([separator]) + seed)
        value = digest(key, value)
    while True:
        value = digest(key, value)
        candidate = int.from_bytes(value, "big")
        if 0 < candidate < ORDER:
            return candidate
        key = digest(key, value + b"\0")
        value = digest(key, value)


def main():
    source = (Path(__file__).resolve().parents[1] /
              "shared/v2/scala/org/sigilaris/conformance/CryptoDependencyConformance.scala").read_text()
    expressions = {
        "BigInt(0)": 0,
        "order - 1": ORDER - 1,
        "order": ORDER,
        "order + 1": ORDER + 1,
        "(BigInt(1) << 256) - 1": 2**256 - 1,
        "BigInt(197)": 197,
    }
    pattern = (r"\(\s*(" + "|".join(re.escape(value) for value in expressions) +
               r'),\s*(\d+),\s*"([0-9a-f]{64})",\s*"([0-9a-f]{64})",\s*\)')
    vectors = re.findall(pattern, source)
    assert len(vectors) == len(expressions) == 6
    assert {row[0] for row in vectors} == set(expressions)
    scala_order = re.search(r'private val order = BigInt\(\s*"([0-9a-f]{64})",\s*16,\s*\)', source)
    assert scala_order and int(scala_order.group(1), 16) == ORDER, "unexpected fixture curve order"
    assert "CryptoOps.fromPrivate(BigInt(1))" in source
    for expression, recovery, expected_r, expected_s in vectors:
        message = expressions[expression]
        scalar = nonce(message)
        x, y = multiply(scalar)
        r = x % ORDER
        s = pow(scalar, -1, ORDER) * (message + r) % ORDER
        assert r and s, "reference vector requires RFC 6979 retry"
        recovery_id = (2 if x >= ORDER else 0) | (y & 1)
        if s > ORDER // 2:
            s = ORDER - s
            recovery_id ^= 1
        assert (int(recovery), int(expected_r, 16), int(expected_s, 16)) == (27 + recovery_id, r, s), expression
    assert nonce(197) < 2**248, "missing leading-zero nonce coverage"
    print("PASS: 6 independent RFC 6979/secp256k1 signature literals, including a leading-zero nonce")


if __name__ == "__main__":
    main()
