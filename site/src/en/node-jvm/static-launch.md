# Static Launch

The current public repository ships a reference static launch baseline and a
repo-local smoke harness. This is a narrative overview of that baseline, not a
complete production operations manual.

## Reference Smoke

Run the current launch proof with:

```bash
sbt "testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffLaunchSmokeSuite"
```

The smoke harness exercises static mesh formation, HotStuff progress across a
stalled leader, newcomer bootstrap, archive reopen, and same-validator
relocation flow.

## Non-compiling Configuration Sketch

```hocon
# Non-compiling configuration sketch
sigilaris.node.gossip.peers {
  local-node-identity = "node-a"
  known-peers = ["node-b", "node-c", "node-d"]
  direct-neighbors = ["node-b", "node-c", "node-d"]
  transport-auth.peer-secrets { ... }
}

sigilaris.node.consensus.hotstuff {
  local-role = validator
  validators = [ ... ]
  key-holders = [ ... ]
  local-signers = [ ... ]
  historical-sync-enabled = true
}
```

## Operator-Owned Baseline

- provision the same validator inventory, holder mapping, peer graph, and peer
  secrets on every node first
- start validator holders before audit/newcomer nodes that depend on bootstrap
- treat config drift, signer mismatch, and missing topic registration as startup
  failures
- fence the old validator holder before a DR relocation restart

## Current Limitations

- launch flow is still operator-managed
- the harness proves the baseline but is not a packaged daemon or orchestrator
- dynamic discovery, validator rotation, and automatic failover remain outside
  the current baseline

## Related Pages

- [Bootstrap And Sync](bootstrap-and-sync.md)
- [HotStuff And Pacemaker](hotstuff-and-pacemaker.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
