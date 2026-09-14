package org.sigilaris.node.jvm.runtime.application.v2

import cats.effect.kernel.Async

import org.sigilaris.core.application.protocol.v2.{Bytes, Hash, Text}

/** Live immutable artifact operations share the voting gate and failure fence.
  * Recovery callbacks already hold that gate and must read their provided
  * DurableJournal directly; retained vote/application evidence carries its
  * complete canonical plan, so recovery never needs this live lookup adapter.
  */
trait ApplicationArtifactStorage[F[_]]:
  def retain(namespace: Text, digest: Hash, bytes: Bytes): Result[F, Unit]
  def read(namespace: Text, digest: Hash): Result[F, Bytes]

object ApplicationArtifactStorage:
  def journaled[F[_]: Async](
      safety: JournalSafetyStore[F],
  ): ApplicationArtifactStorage[F] = new ApplicationArtifactStorage[F]:
    def retain(namespace: Text, digest: Hash, bytes: Bytes): Result[F, Unit] =
      safety.transaction(_ => safety.retainBlob(namespace, digest, bytes))

    def read(namespace: Text, digest: Hash): Result[F, Bytes] =
      safety.transaction(_ =>
        safety.journal.readBlob(namespace, digest).leftMap { error =>
          // File journals poison every failed read except an absent blob. This
          // includes capacity/namespace failures, not just corruption or an
          // already-fenced backend. Existing signing retries must stop too.
          error.code match
            case RuntimeFailureCode.ProofUnavailable => error
            case _                                   =>
              V2RuntimeFailure.at(
                RuntimeFailureCode.StorageUnknown,
                "artifact backend requires recovery: " + error.detail,
              )
        },
      )
