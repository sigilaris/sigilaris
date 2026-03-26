package org.sigilaris.core
package merkle

import MerkleTrieNode.MerkleHash
import util.SafeStringInterp.*

/** Tracks differences in Merkle Trie state using reference counting.
  *
  * Maintains a map from node hashes to (node, reference count) pairs.
  * Positive counts indicate additions, negative counts indicate removals.
  *
  * @see [[MerkleTrieState]] for usage context
  */
opaque type MerkleTrieStateDiff = Map[MerkleHash, (MerkleTrieNode, Int)]

/** Companion object for [[MerkleTrieStateDiff]] with operations. */
object MerkleTrieStateDiff:

  /** Creates a diff from a map.
    *
    * @param map the underlying map
    * @return new diff
    */
  def apply(
      map: Map[MerkleHash, (MerkleTrieNode, Int)],
  ): MerkleTrieStateDiff = map

  /** Creates an empty diff. */
  def empty: MerkleTrieStateDiff = Map.empty

  extension (diff: MerkleTrieStateDiff)
    /** Gets a node if it exists with positive reference count.
      *
      * @param hash the node hash
      * @return Some(node) if present with positive count, None otherwise
      */
    def get(hash: MerkleHash): Option[MerkleTrieNode] =
      diff.get(hash).flatMap {
        case (node, count) if count > 0 => Some(node)
        case _                          => None
      }

    /** Applies a function to all nodes with positive reference count.
      *
      * @param f function to apply to each (hash, node) pair
      */
    def foreach(f: (MerkleHash, MerkleTrieNode) => Unit): Unit =
      diff.foreach:
        case (hash, (node, count)) if count > 0 => f(hash, node)
        case _ => ()

    /** Adds a node reference to the diff.
      *
      * Increments the reference count, or cancels out a -1 count.
      *
      * @param hash the node hash
      * @param node the node
      * @return updated diff
      */
    def add(
        hash: MerkleHash,
        node: MerkleTrieNode,
    ): MerkleTrieStateDiff =
      diff.get(hash).fold(diff + (hash -> (node, 1))) {
        case (node, -1)    => diff - hash
        case (node, count) => diff + (hash -> (node, count + 1))
      }

    /** Removes a node reference from the diff.
      *
      * Decrements the reference count, or cancels out a +1 count.
      *
      * @param hash the node hash
      * @param node the node
      * @return updated diff
      */
    def remove(
        hash: MerkleHash,
        node: MerkleTrieNode,
    ): MerkleTrieStateDiff =
      diff.get(hash).fold(diff + (hash -> (node, -1))) {
        case (node, 1)     => diff - hash
        case (node, count) => diff + (hash -> (node, count - 1))
      }

    /** Converts diff to list. */
    def toList: List[(MerkleHash, (MerkleTrieNode, Int))] = diff.toList

    /** Converts diff to map. */
    def toMap: Map[MerkleHash, (MerkleTrieNode, Int)] = diff

    /** Converts diff to string for debugging. */
    def string: String = diff
      .map:
        case (hash, (node, count)) => ss"${hash.hex} -> (${node.toString}, ${count.toString})"
      .mkString("Diff(", ", ", ")")
  end extension
