package org.sigilaris.core
package merkle

import cats.syntax.eq.*

import MerkleTrieNode.MerkleRoot
import util.SafeStringInterp.*

/** Represents the state of a Merkle Trie with change tracking.
  *
  * Tracks the current root, base root, and accumulated differences
  * to enable efficient state transitions and merging.
  *
  * @param root current root hash
  * @param base base root hash (for tracking changes)
  * @param diff accumulated differences from base
  *
  * @see [[MerkleTrieStateDiff]] for difference tracking
  * @see [[MerkleTrieNode.MerkleRoot]] for root hash type
  */
final case class MerkleTrieState(
    root: Option[MerkleRoot],
    base: Option[MerkleRoot],
    diff: MerkleTrieStateDiff,
):
  /** Rebases this state onto another state.
    *
    * Combines the differences when both states share the same base.
    *
    * @param that the state to rebase onto
    * @return rebased state or error if bases don't match
    */
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

/** Companion object for [[MerkleTrieState]] with constructors. */
object MerkleTrieState:
  /** Creates an empty state with no root or base. */
  def empty: MerkleTrieState = MerkleTrieState(
    None,
    None,
    MerkleTrieStateDiff.empty,
  )

  /** Creates a state from a root hash.
    *
    * Sets both root and base to the given hash with empty diff.
    *
    * @param root the root hash
    * @return new state
    */
  def fromRoot(root: MerkleRoot): MerkleTrieState = MerkleTrieState(
    root = Some(root),
    base = Some(root),
    diff = MerkleTrieStateDiff.empty,
  )

  /** Creates a state from an optional root hash.
    *
    * Sets both root and base to the given option with empty diff.
    *
    * @param root the optional root hash
    * @return new state
    */
  def fromRootOption(root: Option[MerkleRoot]): MerkleTrieState =
    MerkleTrieState(
      root = root,
      base = root,
      diff = MerkleTrieStateDiff.empty,
    )
