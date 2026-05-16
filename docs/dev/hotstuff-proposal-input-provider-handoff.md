# HotStuff Proposal Input Provider Handoff

This note documents the Sigilaris-side handoff point for embedders that want
autonomous HotStuff leaders to propose application work.

## Contract

The proposal hook is owned by
`org.sigilaris.node.jvm.runtime.consensus.hotstuff`.

Embedders provide a `HotStuffProposalInputProvider[F]` through
`HotStuffProposalInputRuntimeConfig` when creating a `HotStuffNodeRuntime` or an
assembled `HotStuffRuntimeBootstrap`.

The provider receives `HotStuffProposalInputRequest`, which contains only
consensus context:

- `window`
- `proposer`
- `parent`
- `height`
- `justify`
- `now`: the wall-clock instant at proposal input lookup time
- `timestamp`: the consensus block timestamp derived for the proposal
- `bounds`

The provider returns `HotStuffProposalInputProviderResult`:

- `Supplied(input)` when application work is ready
- `NoWork(reason, detail)` when the application intentionally has no work
- `Rejected(reason, detail)` when this view should not emit application input
- `Failed(reason, detail)` when provider execution failed in a controlled way

The supplied `HotStuffProposalInput` contains the application-neutral data that
Sigilaris can sign: parent, height, state root, body root, timestamp, and
`ProposalTxSet`. Application queue semantics and batch admission policy remain
embedder-owned.

## Fallback Policy

`HotStuffProposalInputFallbackPolicy.AllowLegacyEmpty` preserves the historical
autonomous empty proposal behavior. It is the compatibility default.

`HotStuffProposalInputFallbackPolicy.RequireProviderInput` is the production
visibility mode. Automatic consensus refuses to start without a provider, and a
provider no-work, rejection, failure, or invalid input result does not silently
fall back to an empty proposal.

Pacemaker diagnostics record provider outcome, reason/detail metadata, and
whether fallback was used. They do not include application payload bodies.

## Minimal Adapter Shape

An embedder adapter usually has two responsibilities:

1. Select bounded pending work from the application queue.
2. Convert that selection into `HotStuffProposalInput`.

Minimal shape:

```scala
final class ApplicationProposalInputProvider[F[_]](
    pending: PendingApplicationWork[F],
) extends HotStuffProposalInputProvider[F]:

  override def nextProposalInput(
      request: HotStuffProposalInputRequest,
  ): F[HotStuffProposalInputProviderResult] =
    pending.select(request.bounds).map:
      case None =>
        HotStuffProposalInputProviderResult.NoWork("queueEmpty", None)
      case Some(selection) =>
        HotStuffProposalInputProviderResult.Supplied(
          HotStuffProposalInput(
            parent = request.parent,
            height = request.height,
            stateRoot = selection.stateRoot,
            bodyRoot = selection.bodyRoot,
            timestamp = request.timestamp,
            txSet = selection.proposalTxSet,
          ),
        )
```

The adapter should not import Sigilaris application feature packages into the
HotStuff runtime. It should live in the embedding application and depend on the
HotStuff provider contract.

## Smoke Handoff

The test
`HotStuffProposalInputPacemakerIntegrationSuite` demonstrates the replacement
for manual proposal-materialization harnesses:

1. local admission is represented by a fake provider returning a non-empty
   `ProposalTxSet`;
2. the autonomous pacemaker emits a provider-backed leader proposal;
3. local votes produce a QC;
4. the in-memory sink stores and validates the proposal through the existing
   HotStuff path.

Embedding applications can use the same pattern with a real pending-work
selector and their own block body/materialization stores.
