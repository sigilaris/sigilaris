package org.sigilaris.core
package merkle

import cats.syntax.eq.*

import MerkleTrieNode.MerkleRoot
import util.SafeStringInterp.*

final case class MerkleTrieState(
    root: Option[MerkleRoot],
    base: Option[MerkleRoot],
    diff: MerkleTrieStateDiff,
):
  def rebase(that: MerkleTrieState): Either[String, MerkleTrieState] =
    def right: MerkleTrieState =
      val map1 = this.diff.toMap
        .map:
          case (k, (v, count)) =>
            val thatCount = that.diff.toMap.get(k).fold(0)(_._2)
            (k, (v, count + thatCount))
        .toMap

      this.copy(
        base = that.root,
        diff = MerkleTrieStateDiff(map1),
      )
    end right

    Either.cond(
      this.base === that.base,
      right,
      ss"Different base",
    )
  override def toString: String =
    def string(option: Option[MerkleRoot]): String = option.fold("\"\"")(_.hex)
    ss"MerkleTrieState(root: ${string(root)}, base: ${string(base)}, diff: ${diff.string})"

object MerkleTrieState:
  def empty: MerkleTrieState = MerkleTrieState(
    None,
    None,
    MerkleTrieStateDiff.empty,
  )
  def fromRoot(root: MerkleRoot): MerkleTrieState = MerkleTrieState(
    root = Some(root),
    base = Some(root),
    diff = MerkleTrieStateDiff.empty,
  )
  def fromRootOption(root: Option[MerkleRoot]): MerkleTrieState =
    MerkleTrieState(
      root = root,
      base = root,
      diff = MerkleTrieStateDiff.empty,
    )
