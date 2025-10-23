package org.sigilaris.core
package merkle

import MerkleTrieNode.MerkleHash
import util.SafeStringInterp.*

opaque type MerkleTrieStateDiff = Map[MerkleHash, (MerkleTrieNode, Int)]

object MerkleTrieStateDiff:

  def apply(
      map: Map[MerkleHash, (MerkleTrieNode, Int)],
  ): MerkleTrieStateDiff = map

  def empty: MerkleTrieStateDiff = Map.empty

  extension (diff: MerkleTrieStateDiff)
    def get(hash: MerkleHash): Option[MerkleTrieNode] =
      diff.get(hash).flatMap {
        case (node, count) if count > 0 => Some(node)
        case _                          => None
      }

    def foreach(f: (MerkleHash, MerkleTrieNode) => Unit): Unit =
      diff.foreach:
        case (hash, (node, count)) if count > 0 => f(hash, node)
        case _ => ()

    def add(
        hash: MerkleHash,
        node: MerkleTrieNode,
    ): MerkleTrieStateDiff =
      diff.get(hash).fold(diff + (hash -> (node, 1))) {
        case (node, -1)    => diff - hash
        case (node, count) => diff + (hash -> (node, count + 1))
      }

    def remove(
        hash: MerkleHash,
        node: MerkleTrieNode,
    ): MerkleTrieStateDiff =
      diff.get(hash).fold(diff + (hash -> (node, -1))) {
        case (node, 1)     => diff - hash
        case (node, count) => diff + (hash -> (node, count - 1))
      }

    def toList: List[(MerkleHash, (MerkleTrieNode, Int))] = diff.toList

    def toMap: Map[MerkleHash, (MerkleTrieNode, Int)] = diff

    def string: String = diff
      .map:
        case (hash, (node, count)) => ss"${hash.hex} -> (${node.toString}, ${count.toString})"
      .mkString("Diff(", ", ", ")")
  end extension
