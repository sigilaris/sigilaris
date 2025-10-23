package org.sigilaris.core
package merkle

import cats.Eq
import cats.syntax.either.*

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.*
import scodec.bits.{BitVector, ByteVector}

import codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import codec.byte.ByteEncoder.ops.*
import crypto.Hash
import datatype.{BigNat, UInt256}
import failure.DecodeFailure
import util.SafeStringInterp.*
import Nibbles.{given, *}

/** Represents a node in a Merkle Trie.
  *
  * Merkle Trie consists of three types of nodes:
  *   - `Leaf`: Terminal node storing a key-value pair
  *   - `Branch`: Branch node with only child nodes
  *   - `BranchWithData`: Branch node with both child nodes and a value
  *
  * @example
  * ```scala
  * val leaf = MerkleTrieNode.leaf(
  *   prefix = ByteVector(0x12, 0x34).toNibbles,
  *   value = ByteVector(0xab, 0xcd)
  * )
  * ```
  *
  * @see [[MerkleTrieNode.Leaf]] for terminal nodes
  * @see [[MerkleTrieNode.Branch]] for branch nodes
  * @see [[MerkleTrieNode.BranchWithData]] for branch nodes with values
  */
sealed trait MerkleTrieNode:

  /** Returns the prefix nibbles of this node. */
  def prefix: Nibbles

  /** Returns the children array if this node has children.
    *
    * @return Some(children) for Branch or BranchWithData, None for Leaf
    */
  def getChildren: Option[MerkleTrieNode.Children] = this match
    case MerkleTrieNode.Leaf(_, _)                     => None
    case MerkleTrieNode.Branch(_, children)            => Some(children)
    case MerkleTrieNode.BranchWithData(_, children, _) => Some(children)

  /** Returns the value stored in this node if present.
    *
    * @return Some(value) for Leaf or BranchWithData, None for Branch
    */
  def getValue: Option[ByteVector] = this match
    case MerkleTrieNode.Leaf(_, value)              => Some(value)
    case MerkleTrieNode.Branch(_, _)                => None
    case MerkleTrieNode.BranchWithData(_, _, value) => Some(value)

  /** Returns a new node with the updated prefix.
    *
    * @param prefix the new prefix
    * @return node with updated prefix
    */
  def setPrefix(prefix: Nibbles): MerkleTrieNode =
    this match
      case MerkleTrieNode.Leaf(_, value) => MerkleTrieNode.Leaf(prefix, value)
      case MerkleTrieNode.Branch(_, children) =>
        MerkleTrieNode.Branch(prefix, children)
      case MerkleTrieNode.BranchWithData(_, key, value) =>
        MerkleTrieNode.BranchWithData(prefix, key, value)

  /** Returns a new node with the updated children.
    *
    * @param children the new children array
    * @return node with updated children
    */
  def setChildren(
      children: MerkleTrieNode.Children,
  ): MerkleTrieNode = this match
    case MerkleTrieNode.Leaf(prefix, value) =>
      MerkleTrieNode.BranchWithData(prefix, children, value)
    case MerkleTrieNode.Branch(prefix, _) =>
      MerkleTrieNode.Branch(prefix, children)
    case MerkleTrieNode.BranchWithData(prefix, _, value) =>
      MerkleTrieNode.BranchWithData(prefix, children, value)

  /** Returns a new node with the updated value.
    *
    * @param value the new value
    * @return node with updated value
    */
  def setValue(value: ByteVector): MerkleTrieNode = this match
    case MerkleTrieNode.Leaf(prefix, _) => MerkleTrieNode.Leaf(prefix, value)
    case MerkleTrieNode.Branch(prefix, children) =>
      MerkleTrieNode.BranchWithData(prefix, children, value)
    case MerkleTrieNode.BranchWithData(prefix, children, _) =>
      MerkleTrieNode.BranchWithData(prefix, children, value)

  override def toString: String =
    @SuppressWarnings(Array("org.wartremover.warts.Any"))
    val childrenString = getChildren.fold("[]"): (childrenRefined) =>
      (0 until 16)
        .map: i =>
          f"${i}%x: ${childrenRefined(i)}"
        .mkString("[", ",", "]")
    ss"MerkleTrieNode(${prefix.value.toHex}, $childrenString, ${getValue.fold("\"\"")(_.toHex)})"

/** Companion object for [[MerkleTrieNode]] with constructors and type definitions. */
object MerkleTrieNode:

  /** Terminal node storing a key-value pair.
    *
    * @param prefix the key suffix for this leaf
    * @param value the value stored in this leaf
    */
  final case class Leaf(prefix: Nibbles, value: ByteVector)
      extends MerkleTrieNode

  /** Branch node with only child nodes.
    *
    * @param prefix the common prefix for all children
    * @param children array of 16 optional child node hashes
    */
  final case class Branch(prefix: Nibbles, children: Children)
      extends MerkleTrieNode

  /** Branch node with both child nodes and a value.
    *
    * @param prefix the common prefix for all children
    * @param children array of 16 optional child node hashes
    * @param value the value stored at this branch
    */
  final case class BranchWithData(
      prefix: Nibbles,
      children: Children,
      value: ByteVector,
  ) extends MerkleTrieNode

  /** Creates a leaf node.
    *
    * @param prefix the key suffix
    * @param value the value to store
    * @return new Leaf node
    */
  def leaf(prefix: Nibbles, value: ByteVector): MerkleTrieNode =
    Leaf(prefix, value)

  /** Creates a branch node.
    *
    * @param prefix the common prefix
    * @param children the children array
    * @return new Branch node
    */
  def branch(
      prefix: Nibbles,
      children: Children,
  ): MerkleTrieNode = Branch(prefix, children)

  /** Creates a branch node with data.
    *
    * @param prefix the common prefix
    * @param children the children array
    * @param value the value to store
    * @return new BranchWithData node
    */
  def branchWithData(
      prefix: Nibbles,
      children: Children,
      value: ByteVector,
  ): MerkleTrieNode = BranchWithData(prefix, children, value)

  /** Hash value of a Merkle Trie node. */
  type MerkleHash = Hash.Value[MerkleTrieNode]

  /** Root hash of a Merkle Trie. */
  type MerkleRoot = MerkleHash

  /** Array of 16 optional child node hashes. */
  type Children = Vector[Option[MerkleHash]] :| ChildrenCondition

  type ChildrenCondition = Length[StrictEqual[16]]

  extension (c: Children)
    /** Updates a child at the given index.
      *
      * @param i index (0-15)
      * @param v new child hash value
      * @return updated children array
      */
    def updateChild(i: Int, v: Option[MerkleHash]): Children =
      c.updated(i, v).assume

  /** Companion object for Children providing constructors. */
  object Children:
    /** Empty children array with all None values. */
    inline def empty: Children =
      Vector.fill(16)(Option.empty[MerkleHash]).assume

  /** Cats Eq instance for MerkleTrieNode. */
  given Eq[MerkleTrieNode] = Eq.fromUniversalEquals

  /** Encodes a MerkleTrieNode to bytes.
    *
    * Encoding format:
    *   - Leaf: 1 + prefix + value
    *   - Branch: 2 + prefix + children
    *   - BranchWithData: 3 + prefix + children + value
    */
  given merkleTrieNodeEncoder: ByteEncoder[MerkleTrieNode] = node =>
    val encodePrefix: ByteVector =
      val prefixNibbleSize: Long = node.prefix.value.size / 4
      BigNat.unsafeFromLong(prefixNibbleSize).toBytes ++ node.prefix.bytes

    def encodeChildren(children: MerkleTrieNode.Children): ByteVector =
      val existenceBytes = BitVector.bits(children.map(_.nonEmpty)).bytes
      children
        .flatMap(_.toList)
        .foldLeft(existenceBytes)(_ ++ _.toUInt256.bytes)

    def encodeValue(value: ByteVector): ByteVector =
      BigNat.unsafeFromLong(value.size).toBytes ++ value
    val encoded = node match
      case Leaf(_, value) =>
        ByteVector.fromByte(1) ++ encodePrefix ++ encodeValue(value)
      case Branch(_, children) =>
        ByteVector.fromByte(2) ++ encodePrefix ++ encodeChildren(children)
      case BranchWithData(_, children, value) =>
        ByteVector.fromByte(3) ++ encodePrefix ++ encodeChildren(
          children,
        ) ++ encodeValue(value)
    encoded

  /** Decodes a MerkleTrieNode from bytes.
    *
    * Decoding format:
    *   - Leaf: 1 + prefix + value
    *   - Branch: 2 + prefix + children
    *   - BranchWithData: 3 + prefix + children + value
    */
  given merkleTrieNodeDecoder: ByteDecoder[MerkleTrieNode] =
    val childrenDecoder: ByteDecoder[MerkleTrieNode.Children] =
      ByteDecoder
        .fromFixedSizeBytes(2)(_.bits)
        .flatMap: (existenceBits) =>
          (bytes) =>
            type LoopType = Either[DecodeFailure, DecodeResult[
              Vector[Option[MerkleHash]],
            ]]
            @annotation.tailrec
            def loop(
                bits: BitVector,
                bytes: ByteVector,
                acc: List[Option[MerkleHash]],
            ): LoopType = bits.headOption match
              case None =>
                DecodeResult(acc.reverse.toVector, bytes).asRight[DecodeFailure]
              case Some(false) =>
                loop(bits.tail, bytes, None :: acc)
              case Some(true) =>
                val (hashBytes, rest) = bytes.splitAt(32)
                val hashUInt256       = UInt256.unsafeFromBytesBE(hashBytes)
                loop(
                  bits.tail,
                  rest,
                  Some(Hash.Value[MerkleTrieNode](hashUInt256)) :: acc,
                )
            end loop
            loop(existenceBits, bytes, Nil)
        .map(_.assume)

    val valueDecoder: ByteDecoder[ByteVector] =
      ByteDecoder[BigNat].flatMap: (size) =>
        ByteDecoder.fromFixedSizeBytes(size.toBigInt.toLong)(identity)

    ByteDecoder.byteDecoder
      .emap: b =>
        Either.cond(
          1 <= b && b <= 3,
          b,
          DecodeFailure(ss"wrong range: ${b.toString}"),
        )
      .flatMap:
        case 1 =>
          for
            prefix <- nibblesByteDecoder
            value  <- valueDecoder
          yield Leaf(prefix, value)
        case 2 =>
          for
            prefix   <- nibblesByteDecoder
            children <- childrenDecoder
          yield Branch(prefix, children)
        case 3 =>
          for
            prefix   <- nibblesByteDecoder
            children <- childrenDecoder
            value    <- valueDecoder
          yield BranchWithData(prefix, children, value)

  /** Hash instance for MerkleTrieNode. */
  given merkleTrieNodeHash: Hash[MerkleTrieNode] = Hash.build
