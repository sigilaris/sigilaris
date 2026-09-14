package org.sigilaris.conformance

import java.nio.file.Path
import java.time.Instant

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.tx.{TxGossipRuntime, TxGossipStateStore}
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Four ordinary HotStuff runtimes with no raw local key map. Explicit public
  * emit operations make the boundary deterministic; authenticated Gossip
  * transports the actual proposal/votes and the ordinary sinks form the QCs and
  * observe three-chain finality. P4 separately covers automatic scheduling.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2HistoricalTransportFixture:
  import V2RequestConformance.*
  import V2HistoricalFixture.{accepted, check}
  private val now = Instant.parse("2026-09-11T00:00:00Z")
  private def policy[A](result: Either[HotStuffPolicyViolation, A]): IO[A] =
    IO.fromEither(
      result.leftMap(e =>
        new IllegalStateException(e.reason + ":" + e.detail.getOrElse("")),
      ),
    )
  private def transport[A](result: Either[CanonicalRejection, A]): IO[A] =
    IO.fromEither(result.leftMap(e => new IllegalStateException(e.reason)))
  private val clock = new GossipClock[IO]:
    def now: IO[Instant] = IO.pure(V2HistoricalTransportFixture.now)
  private given GossipClock[IO] = clock

  final case class Node(
      base: V2HistoricalRuntimeFixture.Node,
      runtime: HotStuffNodeRuntime[IO],
      gossip: TxGossipRuntime[IO, HotStuffGossipArtifact],
  ):
    def id: String = "historical-v2-" + base.original.index.toString
  final case class Link(from: Node, to: Node, session: DirectionalSessionId)

  private def runtime(
      source: V2HistoricalFixture.Material,
      base: V2HistoricalRuntimeFixture.Node,
  ): IO[Node] =
    val voter = base.material.voter
    val plans = HotStuffExecutionPlanPreimages.journaled[IO](
      base.safety,
      root =>
        val options = Vector(source.source.consensusPlan, ExecutionPlan.empty)
        EitherT.pure[IO, V2RuntimeFailure](
          options
            .find(plan => ExecutionPlan.computeRoot(plan).contains(root))
            .map(plan => value(ExecutionPlan.codec.encode(plan))),
        ),
    )
    val contexts = new HotStuffApplicationVotingContexts[IO]:
      def resolve(
          manifest: ProtocolManifest,
          localVoter: ValidatorId,
      ): Result[IO, HotStuffApplicationVotingContext[IO]] =
        check(
          manifest == source.source.manifest && localVoter == voter,
          "actual local manifest/controller voter changed",
        ).as(
          HotStuffApplicationVotingContext(
            manifest,
            base.safety,
            base.requests,
            base.lockConfiguration.artifacts,
            None,
          ),
        )
    val legacy = new HotStuffLegacyApplicationAuthentication[IO]:
      def verify(
          profile: HotStuffHistoricalApplicationProfile,
          proposal: Proposal,
      ): Result[IO, Unit] = for
        selected <- EitherT.fromEither[IO](source.profiles.proposal(proposal))
        expected <- EitherT.fromEither[IO](
          HistoricalArtifactProfile
            .selected(selected.profile)
            .leftMap(V2RuntimeFailure.fromCore),
        )
        _ <- check(
          profile == expected,
          "historical transport profile relabelled actual source",
        )
        _ <- source.consensus.proposal(proposal).void
      yield ()
    val dispatcher = HotStuffApplicationVoting.journaled(
      source.profiles.schedule[IO],
      plans,
      contexts,
      legacy,
    )
    val id      = "historical-v2-" + base.original.index.toString
    val holders =
      source.source.validators.members.zipWithIndex.map((member, index) =>
        ValidatorKeyHolder(
          member.id,
          PeerIdentity.unsafe("historical-v2-" + index.toString),
          ValidatorKeyHolderStatus.Active,
        ),
      )
    for
      _    <- accepted(plans.retain(source.source.consensusPlan))
      _    <- accepted(plans.retain(ExecutionPlan.empty))
      node <- HotStuffNodeRuntime
        .create[IO](
          PeerIdentity.unsafe(id),
          LocalNodeRole.Validator,
          holders,
          source.source.validators,
          Map.empty,
          automaticConsensus = false,
          proposalValidationConfig =
            HotStuffProposalValidationRuntimeConfig.controlledApplication(
              dispatcher,
              base.finalizer,
              base.controlled,
              None,
              None,
            ),
        )
        .flatMap(policy)
      topology <- IO.fromEither(
        StaticPeerTopology
          .parse(
            id,
            (0 to 3)
              .filter(_ != base.original.index)
              .map(index => "historical-v2-" + index.toString)
              .toList,
            (0 to 3)
              .filter(_ != base.original.index)
              .map(index => "historical-v2-" + index.toString)
              .toList,
          )
          .leftMap(new IllegalStateException(_)),
      )
      registry = StaticPeerRegistry(topology)
      store <- TxGossipStateStore.inMemory[IO](
        GossipSessionEngine(registry.localPeer, topology),
      )
      gossip = TxGossipRuntime.withPolicy[IO, HotStuffGossipArtifact](
        StaticPeerAuthenticator[IO](registry),
        clock,
        node.source,
        node.sink,
        node.topicContracts,
        store,
        HotStuffRuntimeBootstrap.DefaultRuntimePolicy,
      )
    yield Node(base, node, gossip)

  private def connect(from: Node, to: Node): IO[Link] =
    val chain  = from.base.material.source.chain
    val topics = SessionSubscription.unsafe(
      ChainTopic(chain, GossipTopic.consensusProposal),
      ChainTopic(chain, GossipTopic.consensusVote),
      ChainTopic(chain, GossipTopic.consensusTimeoutVote),
      ChainTopic(chain, GossipTopic.consensusNewView),
    )
    for
      proposed <- from.gossip
        .startOutbound(PeerIdentity.unsafe(to.id), topics)
        .flatMap(transport)
      inbound <- to.gossip.handleInboundProposal(proposed)
      ack     <- inbound match
        case InboundHandshakeResult.Accepted(ack, _)   => IO.pure(ack)
        case rejected: InboundHandshakeResult.Rejected =>
          IO.raiseError(new IllegalStateException(rejected.rejection.reason))
      _ <- from.gossip.applyHandshakeAck(ack).flatMap(transport)
    yield Link(from, to, proposed.sessionId)

  private def relay(link: Link): IO[Unit] = for
    messages <- link.from.gossip.pollEvents(link.session).flatMap(transport)
    decoded = messages.map {
      case EventStreamMessage.Event(event) =>
        val raw    = ByteEncoder[HotStuffGossipArtifact].encode(event.payload)
        val parsed =
          ByteDecoder[HotStuffGossipArtifact].decode(raw).toOption.get
        assert(
          parsed.remainder.isEmpty && ByteEncoder[HotStuffGossipArtifact]
            .encode(parsed.value) == raw,
        )
        EventStreamMessage.Event(event.copy(payload = parsed.value))
      case other => other
    }
    _ <- link.to.gossip.receiveEvents(link.session, decoded).flatMap(transport)
  yield ()

  def run(root: Path): IO[Unit] =
    V2HistoricalRuntimeFixture.fresh(root).use { cluster =>
      val source = cluster.source
      for
        nodes <- cluster.nodes.traverse(runtime(source, _))
        links <- nodes
          .traverse(from =>
            nodes.filter(_.id != from.id).traverse(connect(from, _)),
          )
          .map(_.flatten)
        rows         <- source.retained.get
        certificates <- source.certificates.get
        // Replay only original archived signatures. No old key is used to seed the new runtimes.
        _ <- nodes.traverse_ { node =>
          val proposals = rows.values.toVector
            .sortBy(_.block.height.toBigNat.toBigInt)
            .map(HotStuffGossipArtifact.ProposalArtifact.apply)
          val votes = certificates.values.toVector
            .sortBy(_.subject.window.height.toBigNat.toBigInt)
            .flatMap(_.votes)
            .map(HotStuffGossipArtifact.VoteArtifact.apply)
          (proposals ++ votes).zipWithIndex.traverse_ { (artifact, index) =>
            val event = GossipEvent(
              source.source.chain,
              HotStuffGossipArtifact.topicOf(artifact),
              HotStuffGossipArtifact.stableIdOf(artifact),
              CursorToken.unsafeIssue(ByteVector.fromLong(index.toLong + 1L)),
              now,
              artifact,
            )
            node.runtime.sink.applyEvent(event).flatMap(transport).void
          }
        }
        _ <- IO.println(
          "Historical transport: original 0..5 replayed on four nodes",
        )
        _ <- (6L to 8L).toVector.traverse_ { h =>
          val window = HotStuffWindow.unsafe(
            source.source.chain,
            h,
            0L,
            source.source.validators.hash,
          )
          val leader = HotStuffPacemaker.deterministicLeader(
            window,
            source.source.validators,
          )
          val proposer = nodes.find(_.base.material.voter == leader).get
          val plan     =
            if h == 6L then source.source.consensusPlan else ExecutionPlan.empty
          for
            current <- source.retained.get
            parent = current.values
              .find(_.block.height.toBigNat.toBigInt == h - 1L)
              .get
            originalQcs <- source.certificates.get
            justify   = originalQcs(parent.proposalId)
            root      = value(ExecutionPlan.computeRoot(plan))
            emptyBody = BlockBody
              .computeBodyRoot(BlockBody[Hash, Hash, Bytes](Set.empty))(using
                summon,
                summon,
                org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder,
              )
              .toOption
              .get
            header = BlockHeader(
              Some(parent.targetBlockId),
              BlockHeight.unsafeFromLong(h),
              StateRoot(source.source.nextStateRoot),
              if h == 6L then source.source.bodyRoot else emptyBody,
              BlockTimestamp.unsafeFromEpochMillis(5000L + h),
              BlockHeaderVersion.V2,
              Some(root),
            )
            emitted <- proposer.runtime
              .emitProposal(
                leader,
                header,
                if h == 6L then source.source.candidate.txSet
                else ProposalTxSet.empty,
                window,
                justify,
                now,
              )
              .flatMap(policy)
            proposal <- emitted.payload match
              case HotStuffGossipArtifact.ProposalArtifact(value) =>
                IO.pure(value)
              case _ =>
                IO.raiseError(
                  new IllegalStateException(
                    "ordinary emission returned another artifact",
                  ),
                )
            _ <- accepted(source.retain(proposal))
            _ <- proposer.runtime.sink.applyEvent(emitted).flatMap(transport)
            _ <- links.traverse_(relay)
            _ <- nodes.traverse_ { node =>
              node.runtime
                .emitVote(node.base.material.voter, proposal, now)
                .flatMap(policy)
                .flatMap(event =>
                  node.runtime.sink.applyEvent(event).flatMap(transport).void,
                )
            }
            _ <- (0 until 3).toVector.traverse_(_ => links.traverse_(relay))
            snapshots <- nodes.traverse(_.runtime.inMemorySink.get.snapshot)
            _         <- accepted(
              check(
                snapshots.forall(
                  _.qcs
                    .get(proposal.proposalId)
                    .exists(_.votes.map(_.voter).distinct.sizeCompare(3) >= 0),
                ),
                "actual four ordinary sinks did not form the transported three-voter QC",
              ),
            )
            _ <- accepted(
              source.retain(snapshots.head.qcs(proposal.proposalId)),
            )
            _ <- IO.println(
              "Historical transport: actual four-node quorum at height " + h.toString,
            )
          yield ()
        }
        states <- cluster.nodes.traverse(node => accepted(node.safety.snapshot))
        snapshots <- nodes.traverse(_.runtime.inMemorySink.get.snapshot)
        _         <- accepted(
          check(
            states.forall(_.appliedEntries.contains(source.source.executionId)),
            "ordinary finalized observer did not materialize target execution on every activated node",
          ),
        )
        _ <- accepted(
          check(
            snapshots.forall(
              _.finalization
                .get(source.source.chain)
                .flatMap(_.bestFinalized)
                .exists(_.anchorHeight.toBigNat.toBigInt == 6),
            ),
            "ordinary transported three-chain did not finalize B6",
          ),
        )
        _ <- accepted(
          check(
            nodes.forall(_.runtime.localKeys.isEmpty),
            "an activated ordinary runtime retained a raw-key fallback",
          ),
        )
      yield ()
    }
