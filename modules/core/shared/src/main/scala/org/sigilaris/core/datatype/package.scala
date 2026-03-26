package org.sigilaris.core

/** Core data types with specialized encoding and type safety.
  *
  * This package provides opaque types for common data structures used throughout Sigilaris,
  * each with carefully designed byte and JSON codecs. All types are zero-cost abstractions
  * that provide compile-time safety without runtime overhead.
  *
  * = Available Types =
  *
  * '''BigNat''' - Non-negative arbitrary-precision integers
  *   - Refined BigInt constrained to values ≥ 0
  *   - Safe arithmetic operations (addition, multiplication, division)
  *   - Checked subtraction via `Either` for negative results
  *   - Variable-length byte encoding
  *   - JSON string encoding by default
  *
  * '''UInt256''' - 256-bit unsigned integers
  *   - Fixed 32-byte big-endian representation
  *   - Range: [0, 2^256 - 1]
  *   - Construction from bytes, BigInt, or hex strings
  *   - Type-safe failures via [[org.sigilaris.core.failure.UInt256Failure]]
  *   - JSON hex string encoding (lowercase, no `0x` prefix)
  *
  * '''Utf8''' - UTF-8 encoded strings
  *   - Length-prefixed byte encoding: `[size: BigNat][UTF-8 bytes]`
  *   - Automatic UTF-8 validation during decoding
  *   - JSON string encoding (delegates to String)
  *   - JSON key codec support for Map keys
  *
  * = Common Features =
  *
  * All types in this package provide:
  *   - Cats `Eq` instances for type-safe equality
  *   - `ByteEncoder` and `ByteDecoder` for binary serialization
  *   - `JsonEncoder` and `JsonDecoder` for JSON serialization
  *   - Opaque type wrappers for zero-cost abstraction
  *
  * = Usage Examples =
  *
  * @example
  * ```scala
  * import org.sigilaris.core.datatype.*
  * import org.sigilaris.core.codec.byte.ByteEncoder
  * import org.sigilaris.core.codec.json.JsonEncoder
  *
  * // BigNat - natural numbers
  * val n1 = BigNat.unsafeFromLong(42)
  * val n2 = BigNat.unsafeFromLong(10)
  * val sum = BigNat.add(n1, n2)  // 52
  * val diff = BigNat.tryToSubtract(n1, n2)  // Right(32)
  *
  * // UInt256 - fixed-size unsigned integers
  * val u1 = UInt256.fromHex("ff").toOption.get
  * val u2 = UInt256.fromBigIntUnsigned(BigInt(255)).toOption.get
  * val hex = u1.toHexLower
  *
  * // Utf8 - length-prefixed strings
  * val text = Utf8("Hello, 世界!")
  * val bytes = ByteEncoder[Utf8].encode(text)
  * val json = JsonEncoder[Utf8].encode(text)
  * ```
  *
  * = Encoding Formats =
  *
  * '''Byte Encoding:'''
  *   - BigNat: Variable-length (implementation-defined)
  *   - UInt256: Fixed 32 bytes, big-endian
  *   - Utf8: `[size: BigNat][UTF-8 bytes]`
  *
  * '''JSON Encoding:'''
  *   - BigNat: String by default (configurable via [[org.sigilaris.core.codec.json.JsonConfig]])
  *   - UInt256: Lowercase hex string without `0x` prefix
  *   - Utf8: Plain JSON string
  *
  * @see [[org.sigilaris.core.codec.byte]] for byte codec details
  * @see [[org.sigilaris.core.codec.json]] for JSON codec details
  * @see [[org.sigilaris.core.failure]] for typed failure cases
  */
package object datatype
