package org.sigilaris.conformance

import org.sigilaris.node.jvm.runtime.application.v2.*

import cats.effect.IO
import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.conformance.V2RequestConformance
import org.sigilaris.conformance.V2RequestConformance.{height, uint, value}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffHistoricalApplicationProfile,
  HotStuffWindow,
}
import org.sigilaris.node.jvm.runtime.block.BlockHeaderVersion

object V2HistoricalProfilesConformance:
  def cases: Vector[(String, () => IO[Unit])] = new Scenarios().cases
  def run(): IO[Unit]                         = cases.traverse_ { (name, run) =>
    IO.println("V2HistoricalProfilesConformance: " + name) *> IO.defer(run())
  }
  private final class Scenarios:
    private val registered =
      scala.collection.mutable.ArrayBuffer.empty[(String, () => IO[Unit])]
    def cases: Vector[(String, () => IO[Unit])]            = registered.toVector
    private def scenario(name: String)(run: => Unit): Unit =
      registered.addOne(name -> (() => IO(run))): Unit
    private def assertEquals[A, B](actual: A, expected: B): Unit =
      assert(
        actual == expected,
        "expected " + expected.toString + "; actual " + actual.toString,
      )
    private val f =
      new V2RequestConformance.Fixture(35100L, Authority.ConsensusOnly)
    private val old =
      f.context.copy(protocolVersion = 1L, configurationDigest = uint(900))
    private val m1 = HistoricalProfileRange(
      height(0),
      Some(height(4)),
      old,
      HistoricalArtifactProfile(
        HistoricalApplicationRelease.LegacyM1,
        2L,
        None,
      ),
      uint(901),
    )
    private val m2 = HistoricalProfileRange(
      height(5),
      Some(height(9)),
      old,
      HistoricalArtifactProfile(
        HistoricalApplicationRelease.LegacyM2,
        2L,
        None,
      ),
      uint(902),
    )
    private val v2 = HistoricalProfileRange(
      height(10),
      None,
      f.context,
      HistoricalArtifactProfile(
        HistoricalApplicationRelease.ApplicationV2,
        2L,
        Some(f.manifest),
      ),
      uint(903),
    )
    private val config  = HistoricalProfileConfiguration(1L, Vector(m1, m2, v2))
    private val encoded = value(
      HistoricalProfileConfiguration.codec.encode(config),
    )
    private val pin      = value(HistoricalProfileConfiguration.digest(config))
    private val selected =
      AuthenticatedHistoricalProfiles.pinned(pin, encoded).toOption.get
    private def window(height: Long) =
      HotStuffWindow.unsafe(f.chain, height, 0L, f.validators.hash)

    scenario(
      "one historical header tag still dispatches M1, M2 and application V2 at exact authenticated boundaries",
    ):
      assertEquals(
        selected.at(window(4)).map(_.profile.release),
        Right(HistoricalApplicationRelease.LegacyM1),
      )
      assertEquals(
        selected.at(window(5)).map(_.profile.release),
        Right(HistoricalApplicationRelease.LegacyM2),
      )
      assertEquals(
        selected.at(window(9)).map(_.profile.release),
        Right(HistoricalApplicationRelease.LegacyM2),
      )
      assertEquals(
        selected.at(window(10)).map(_.profile.release),
        Right(HistoricalApplicationRelease.ApplicationV2),
      )
      assertEquals(
        selected.at(window(11)).map(_.profile.release),
        Right(HistoricalApplicationRelease.ApplicationV2),
      )
      assertEquals(
        HistoricalArtifactProfile.selected(m2.profile),
        Right(
          HotStuffHistoricalApplicationProfile.LegacyM2(BlockHeaderVersion.V2),
        ),
      )

    scenario(
      "rewriting an already authenticated M1 range as M2 cannot pass its original configuration pin",
    ):
      val relabeled = config.copy(ranges =
        config.ranges.updated(0, m1.copy(profile = m2.profile)),
      )
      val changed =
        value(HistoricalProfileConfiguration.codec.encode(relabeled))
      assertEquals(
        AuthenticatedHistoricalProfiles.pinned(pin, changed).left.map(_.code),
        Left(RuntimeFailureCode.CommitmentMismatch),
      )

    scenario(
      "missing ranges, overlaps, gaps and unordered ranges are rejected before selection",
    ):
      val invalid = Vector(
        Vector(m2, v2),
        Vector(m1.copy(lastHeight = Some(height(5))), m2, v2),
        Vector(m1.copy(lastHeight = Some(height(3))), m2, v2),
        Vector(m2, m1, v2),
        Vector(m1.copy(lastHeight = None), m2, v2),
      )
      invalid.foreach(ranges =>
        assert(
          HistoricalProfileConfiguration.codec
            .encode(config.copy(ranges = ranges))
            .isLeft,
        ),
      )

    scenario(
      "strict configuration decoding rejects trailing bytes and the wrong actual validator set",
    ):
      assertEquals(
        HistoricalProfileConfiguration.codec
          .decode(encoded ++ ByteVector(0.toByte))
          .left
          .map(_.code),
        Left(FailureCode.TrailingBytes),
      )
      val wrong = window(10).copy(validatorSetHash =
        org.sigilaris.node.jvm.runtime.consensus.hotstuff
          .ValidatorSetHash(uint(999)),
      )
      assertEquals(
        selected.at(wrong).left.map(_.code),
        Left(RuntimeFailureCode.DomainMismatch),
      )

    scenario(
      "target manifest/configuration and legacy protocol tuple cannot be mixed",
    ):
      assert(HistoricalProfileRange.codec.encode(v2.copy(context = old)).isLeft)
      assert(
        HistoricalProfileRange.codec.encode(m2.copy(context = f.context)).isLeft,
      )
      assert(
        HistoricalArtifactProfile.codec
          .encode(m2.profile.copy(manifest = Some(f.manifest)))
          .isLeft,
      )
      assert(
        HistoricalArtifactProfile.codec
          .encode(v2.profile.copy(headerVersion = 1L))
          .isLeft,
      )
