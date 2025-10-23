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

sealed trait MerkleTrieNode:

  def prefix: Nibbles

  def getChildren: Option[MerkleTrieNode.Children] = this match
    case MerkleTrieNode.Leaf(_, _)                     => None
    case MerkleTrieNode.Branch(_, children)            => Some(children)
    case MerkleTrieNode.BranchWithData(_, children, _) => Some(children)

  def getValue: Option[ByteVector] = this match
    case MerkleTrieNode.Leaf(_, value)              => Some(value)
    case MerkleTrieNode.Branch(_, _)                => None
    case MerkleTrieNode.BranchWithData(_, _, value) => Some(value)

  def setPrefix(prefix: Nibbles): MerkleTrieNode =
    this match
      case MerkleTrieNode.Leaf(_, value) => MerkleTrieNode.Leaf(prefix, value)
      case MerkleTrieNode.Branch(_, children) =>
        MerkleTrieNode.Branch(prefix, children)
      case MerkleTrieNode.BranchWithData(_, key, value) =>
        MerkleTrieNode.BranchWithData(prefix, key, value)

  def setChildren(
      children: MerkleTrieNode.Children,
  ): MerkleTrieNode = this match
    case MerkleTrieNode.Leaf(prefix, value) =>
      MerkleTrieNode.BranchWithData(prefix, children, value)
    case MerkleTrieNode.Branch(prefix, _) =>
      MerkleTrieNode.Branch(prefix, children)
    case MerkleTrieNode.BranchWithData(prefix, _, value) =>
      MerkleTrieNode.BranchWithData(prefix, children, value)

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

object MerkleTrieNode:

  final case class Leaf(prefix: Nibbles, value: ByteVector)
      extends MerkleTrieNode
  final case class Branch(prefix: Nibbles, children: Children)
      extends MerkleTrieNode
  final case class BranchWithData(
      prefix: Nibbles,
      children: Children,
      value: ByteVector,
  ) extends MerkleTrieNode

  def leaf(prefix: Nibbles, value: ByteVector): MerkleTrieNode =
    Leaf(prefix, value)

  def branch(
      prefix: Nibbles,
      children: Children,
  ): MerkleTrieNode = Branch(prefix, children)

  def branchWithData(
      prefix: Nibbles,
      children: Children,
      value: ByteVector,
  ): MerkleTrieNode = BranchWithData(prefix, children, value)

  type MerkleHash = Hash.Value[MerkleTrieNode]
  type MerkleRoot = MerkleHash

  type Children = Vector[Option[MerkleHash]] :| ChildrenCondition

  type ChildrenCondition = Length[StrictEqual[16]]

  extension (c: Children)
    def updateChild(i: Int, v: Option[MerkleHash]): Children =
      c.updated(i, v).assume
  object Children:
    inline def empty: Children =
      Vector.fill(16)(Option.empty[MerkleHash]).assume

  given Eq[MerkleTrieNode] = Eq.fromUniversalEquals

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

  given merkleTrieNodeHash: Hash[MerkleTrieNode] = Hash.build
