package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.datatype.Utf8

trait ReservationWitnessStore[F[_]]:
  def putInactive(witness: ReservationWitness): Result[F, WitnessRef]
  def read(ref: WitnessRef): Result[F, ReservationWitness]
  def readChunk(ref: WitnessRef, index: Int): Result[F, WitnessChunk]
  def verifyAndRebuildIndex: Result[F, ReservationRecovery]

/** Content-addressed complete witnesses are stored once. Canonical transport
  * chunks are derived from those authenticated bytes, not separate authorities.
  */
private[v2] object WitnessStorage:
  private val Namespace = Utf8("witness")

  def put[F[_]: Monad](
      journal: DurableJournal[F],
      witness: ReservationWitness,
  ): Result[F, WitnessRef] =
    for
      ref <- EitherT.fromEither[F](
        RuntimeCheck.core(WitnessRef.fromWitness(witness)),
      )
      bytes <- EitherT.fromEither[F](
        RuntimeCheck.core(ReservationWitness.codec.encode(witness)),
      )
      _ <- journal.putBlob(Namespace, ref.witnessDigest, bytes)
    yield ref

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  def read[F[_]: Monad](
      journal: DurableJournal[F],
      ref: WitnessRef,
  ): Result[F, ReservationWitness] =
    for
      _ <- EitherT.fromEither[F](
        RuntimeCheck.core(WitnessRef.codec.encode(ref).map(_ => ())),
      )
      bytes <- journal
        .readBlob(Namespace, ref.witnessDigest)
        .leftMap(error =>
          V2RuntimeFailure
            .at(RuntimeFailureCode.IncompleteWitness, error.message),
        )
      witness <- EitherT.fromEither[F](
        ReservationWitness.codec
          .decode(bytes)
          .left
          .map(error =>
            V2RuntimeFailure
              .at(RuntimeFailureCode.IncompleteWitness, error.message),
          ),
      )
      actual <- EitherT.fromEither[F](
        RuntimeCheck.core(WitnessRef.fromWitness(witness)),
      )
      _ <- EitherT.fromEither[F](
        RuntimeCheck.require(
          actual == ref,
          RuntimeFailureCode.IncompleteWitness,
          "stored complete witness differs from its authoritative reference",
        ),
      )
    yield witness

  def chunk[F[_]: Monad](
      journal: DurableJournal[F],
      ref: WitnessRef,
      index: Int,
  ): Result[F, WitnessChunk] =
    for
      witness <- read(journal, ref)
      chunks  <- EitherT.fromEither[F](
        RuntimeCheck.core(ReservationWitness.chunks(witness, ProtocolLimits.V2)),
      )
      value <- EitherT.fromOption[F](
        chunks.lift(index),
        V2RuntimeFailure.at(
          RuntimeFailureCode.InvalidRequest,
          "witness chunk index is outside the complete witness",
        ),
      )
    yield value
