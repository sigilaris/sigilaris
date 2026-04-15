package org.sigilaris.node.gossip

import java.time.Duration
import java.util.Locale

import hedgehog.*
import hedgehog.munit.HedgehogSuite
import scodec.bits.ByteVector

final class GossipTypingPropertySuite extends HedgehogSuite:

  private val policy = HandshakePolicy.default

  private val genTokenHead: Gen[Char] =
    Gen.alphaNum.map(_.toLower)

  private val genTokenTailChar: Gen[Char] =
    Gen.choice1(
      Gen.alphaNum.map(_.toLower),
      Gen.constant('.'),
      Gen.constant('_'),
      Gen.constant('-'),
    )

  private val genLowerToken: Gen[String] =
    for
      head <- genTokenHead
      tail <- Gen.string(genTokenTailChar, Range.linear(0, 15))
    yield s"$head$tail"

  private val genAlphaLedToken: Gen[String] =
    for
      head <- Gen.alpha.map(_.toLower)
      tail <- Gen.string(genTokenTailChar, Range.linear(0, 15))
    yield s"$head$tail"

  private val genHexLowerChar: Gen[Char] =
    Gen.choice1(
      Gen.digit,
      Gen.element1('a', 'b', 'c', 'd', 'e', 'f'),
    )

  private def genHexString(size: Int): Gen[String] =
    Gen.string(genHexLowerChar, Range.singleton(size))

  private val genUuidV4: Gen[String] =
    for
      a <- genHexString(8)
      b <- genHexString(4)
      c <- genHexString(3)
      d <- Gen.element1('8', '9', 'a', 'b')
      e <- genHexString(3)
      f <- genHexString(12)
    yield s"$a-$b-4$c-$d$e-$f"

  private def genByteVector(range: Range[Int]): Gen[ByteVector] =
    Gen.bytes(range).map(ByteVector.view)

  private val genStableArtifactId: Gen[StableArtifactId] =
    genByteVector(Range.linear(1, 64)).map(StableArtifactId.unsafeFromBytes)

  private val genTopicWindowKey: Gen[TopicWindowKey] =
    genByteVector(Range.linear(1, 64)).map(TopicWindowKey.unsafeFromBytes)

  private val genCursorToken: Gen[CursorToken] =
    for
      payload <- genByteVector(Range.linear(0, 64))
      version <- Gen.int(Range.linear(0, 255))
    yield CursorToken.unsafeIssue(payload, version)

  private val genChainTopic: Gen[ChainTopic] =
    for
      chainId <- genLowerToken.map(ChainId.unsafe)
      topic <- genLowerToken.map(GossipTopic.unsafe)
    yield ChainTopic(chainId, topic)

  private val genSessionSubscription: Gen[SessionSubscription] =
    Gen.list(genChainTopic, Range.linear(1, 6)).map: values =>
      SessionSubscription.fromSet(values.toSet).toOption.get

  private def genDurationMs(
      minimumMs: Long,
      maximumMs: Long,
  ): Gen[Duration] =
    Gen.long(Range.linear(minimumMs, maximumMs)).map(Duration.ofMillis)

  private val genProposal: Gen[SessionOpenProposal] =
    for
      sessionId <- genUuidV4.map(raw => DirectionalSessionId.parse(raw).toOption.get)
      correlationId <- genUuidV4.map(raw =>
        PeerCorrelationId.parse(raw).toOption.get,
      )
      subscriptions <- genSessionSubscription
      heartbeat <- genDurationMs(
        policy.minHeartbeatInterval.toMillis,
        policy.maxHeartbeatInterval.toMillis,
      )
      liveness <- genDurationMs(
        heartbeat.toMillis * 3L,
        heartbeat.toMillis * 6L,
      )
      retry <- genDurationMs(
        policy.minMaxControlRetryInterval.toMillis,
        policy.maxMaxControlRetryInterval.toMillis,
      )
    yield SessionOpenProposal(
      sessionId = sessionId,
      peerCorrelationId = correlationId,
      initiator = PeerIdentity.unsafe("node-a"),
      acceptor = PeerIdentity.unsafe("node-b"),
      subscriptions = subscriptions,
      heartbeatInterval = Some(heartbeat),
      livenessTimeout = Some(liveness),
      maxControlRetryInterval = Some(retry),
    )

  property("StableArtifactId parses the hex it renders"):
    for id <- genStableArtifactId.forAll
    yield Result.assert(StableArtifactId.fromHex(id.toHexLower) == Right(id))

  property("TopicWindowKey parses the hex it renders"):
    for windowKey <- genTopicWindowKey.forAll
    yield Result.assert(
      TopicWindowKey.fromHex(windowKey.toHexLower) == Right(windowKey),
    )

  property("CursorToken base64url parsing round-trips issued tokens"):
    for token <- genCursorToken.forAll
    yield Result.assert(
      CursorToken.decodeBase64Url(token.toBase64Url) == Right(token),
    )

  property("CursorToken.issue rejects out-of-range versions"):
    for
      payload <- genByteVector(Range.linear(0, 64)).forAll
      version <- Gen.int(Range.linearFrom(0, -1024, 1024)).forAll
    yield
      if version >= 0 && version <= 255 then Result.success
      else Result.assert(CursorToken.issue(payload, version).isLeft)

  property("gossip identifier parsers accept canonical lowercase tokens"):
    for value <- genLowerToken.forAll
    yield
      Result.all(
        List(
          Result.assert(PeerIdentity.parse(value).map(_.value) == Right(value)),
          Result.assert(ChainId.parse(value).map(_.value) == Right(value)),
          Result.assert(GossipTopic.parse(value).map(_.value) == Right(value)),
        ),
      )

  property("gossip identifier parsers reject uppercase tokens"):
    for value <- genAlphaLedToken.forAll
    yield
      val uppercase = value.toUpperCase(Locale.ROOT)
      Result.all(
        List(
          Result.assert(PeerIdentity.parse(uppercase).isLeft),
          Result.assert(ChainId.parse(uppercase).isLeft),
          Result.assert(GossipTopic.parse(uppercase).isLeft),
        ),
      )

  property("ControlIdempotencyKey accepts canonical UUIDv4 and rejects uppercase forms"):
    for value <- genUuidV4.forAll
    yield
      Result.all(
        List(
          Result.assert(
            ControlIdempotencyKey.parse(value).map(_.value) == Right(value),
          ),
          Result.assert(
            ControlIdempotencyKey.parse(value.toUpperCase(Locale.ROOT)).isLeft,
          ),
        ),
      )

  property("SessionSubscription.fromSet preserves generated non-empty scopes"):
    for values <- Gen.list(genChainTopic, Range.linear(1, 6)).forAll
    yield
      val unique = values.toSet
      SessionSubscription.fromSet(unique) match
        case Right(subscription) =>
          Result.all(unique.toList.map(scope => Result.assert(subscription.contains(scope))))
        case Left(error) =>
          Result.failure.log(s"generated non-empty subscription rejected: $error")

  property("acknowledge and validateAck round-trip resolved proposals"):
    for proposal <- genProposal.forAll
    yield
      val roundTrip =
        for
          resolved <- SessionNegotiation.resolveProposal(proposal)
          ack <- SessionNegotiation.acknowledge(
            proposal = proposal,
            heartbeatInterval = resolved.heartbeatInterval,
            livenessTimeout = resolved.livenessTimeout,
            maxControlRetryInterval = resolved.maxControlRetryInterval,
          )
          validated <- SessionNegotiation.validateAck(proposal, ack)
        yield resolved -> validated
      roundTrip match
        case Right((resolved, validated)) =>
          Result.assert(validated == resolved)
        case Left(rejection) =>
          Result.failure.log(
            s"generated proposal should round-trip through acknowledge/validateAck: ${rejection.reason}",
          )
