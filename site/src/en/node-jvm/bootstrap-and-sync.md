# Bootstrap And Sync

This page summarizes the shipped JVM bootstrap path for static-topology
deployments. It is an overview of the current public baseline, not a full
operator runbook.

## Current Baseline

- static peer topology is loaded from `sigilaris.node.gossip.peers`
- HotStuff bootstrap policy is loaded from
  `sigilaris.node.consensus.hotstuff`
- bootstrap begins from finalized-anchor suggestion discovery and static
  trust-root verification
- snapshot sync fetches and verifies trie data against the chosen anchor
- forward catch-up stays pinned to the verified anchor before normal runtime
  progression resumes
- low-priority historical backfill fills archive state behind the live tip

## Non-compiling Configuration Sketch

```hocon
# Non-compiling configuration sketch
sigilaris.node.gossip.peers {
  local-node-identity = "node-a"
  known-peers = ["node-b", "node-c"]
  direct-neighbors = ["node-b", "node-c"]
  transport-auth.peer-secrets {
    node-a = "..."
    node-b = "..."
    node-c = "..."
  }
}

sigilaris.node.consensus.hotstuff {
  local-role = validator
  validators = [ ... ]
  key-holders = [ ... ]
  local-signers = [ ... ]
  historical-sync-enabled = true
  # optional:
  # bootstrap-trust-root = { ... }
  # historical-validator-sets = [ ... ]
}
```

## Current Limitations

- The trust root is still anchored to the static validator-set baseline unless
  optional extensions are provisioned.
- Historical sync and archive behavior are runtime features, not a separate
  long-term archival service.
- Startup still assumes that the same static peer inventory and signer mapping
  have already been provisioned on every node.

## Follow-Up Work

- broader validator-set rotation and weak-subjectivity style trust-root policy
- richer operator-facing bootstrap documentation outside the narrative site

## Related Pages

- [HotStuff And Pacemaker](hotstuff-and-pacemaker.md)
- [Static Launch](static-launch.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
