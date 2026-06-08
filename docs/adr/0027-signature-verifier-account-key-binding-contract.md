# ADR-0027: SignatureVerifier Account-Key Binding Contract

## Status
Accepted

Promoted from `Proposed` to `Accepted` in the v0.2.3 release commit after Phase 4 verification confirmed the contract is backed by tests (Plan 0018 Phase 0 release-candidate decision).

## Context
- ADR-0010 defines the account model: `Account.Named(name)` is backed by registered `(name, KeyId20) -> KeyInfo` entries, while `Account.Unnamed(keyId)` is identified directly by a `KeyId20`.
- `SignatureVerifier` currently centralizes the shared signing authority check for application modules:
  - recover a public key from the signed transaction hash and recoverable ECDSA signature;
  - derive `KeyId20` from the recovered public key;
  - verify that the recovered key belongs to the claimed `AccountSignature.account`;
  - reject expired named-account keys at the transaction envelope timestamp.
- Downstream embedders need a library-level guarantee that a transaction signed by an arbitrary key cannot be accepted as authority for another account when they route signer-sensitive transactions through `SignatureVerifier`.
- This guarantee should not depend on human-readable error messages, HTTP mappings, or application-specific reducer conventions.

## Decision
1. **`SignatureVerifier` is the normative shared account-key binding verifier for signed application transactions.**
   - Reducers and application modules that need the shared account-key binding guarantee MUST call `SignatureVerifier.verifySignature` or an equivalent wrapper that preserves this ADR's checks.
   - A module MAY bypass `SignatureVerifier`, but it owns that bypass explicitly and the bypass is not part of the shared authority baseline.

2. **Signature recovery is performed over the canonical transaction hash for `T`.**
   - `recoverKeyId` computes `Hash[T](signedTx.value)` and asks `Recover[T]` to recover the public key from `signedTx.sig.sig`.
   - Recovery failure raises `CryptoFailure`.
   - The recovered public key is converted to `KeyId20` through `KeyId20.fromPublicKey`.

3. **`KeyId20` derivation remains Ethereum-style `Keccak256(publicKey).takeRight(20)`.**
   - This is the only shared key identifier derivation used by `SignatureVerifier`.
   - The verifier does not accept caller-supplied key ids as proof of authority.

4. **Named-account authority requires an active registration for the recovered key.**
   - For `Account.Named(name)`, the verifier calls the provided lookup with `(name, recoveredKeyId)`.
   - If the lookup returns `None`, verification fails with `CryptoFailure`.
   - If the lookup returns `Some(KeyInfo)` whose `expiresAt` is before the transaction envelope timestamp, verification fails with `CryptoFailure`.
   - `expiresAt = None` means the key does not expire through this verifier.
   - The expiration boundary is strict: an envelope timestamp exactly equal to `expiresAt` is still accepted; only timestamps after `expiresAt` are rejected.
   - Otherwise the named-account key ownership check succeeds.

5. **Unnamed-account authority requires exact key equality.**
   - For `Account.Unnamed(keyId)`, the verifier accepts only when `recoveredKeyId === keyId`.
   - Any mismatch fails with `CryptoFailure`.
   - No named-account lookup, guardian lookup, fallback key, or off-chain identity path is consulted for unnamed accounts.
   - Unnamed accounts have no verifier-level expiration because no `KeyInfo` lookup is consulted; any lifecycle policy for raw-key-id accounts belongs outside this shared verifier unless a later ADR changes the account model.

6. **`verifySignature` returns a recovered key only after both recovery and ownership checks pass.**
   - The returned `KeyId20` is the recovered key id, not a claimed key id supplied by the transaction.
   - Consumers may use the returned key for audit or diagnostics, but successful return already implies account-key binding under this ADR.

7. **The shared failure type is `CryptoFailure`; external error keys are adapter-owned.**
   - `CryptoFailure.msg` is diagnostic text, not a wire-level ABI.
   - HTTP status, errorKey, JSON shape, or other external protocol mappings are owned by embedders such as bbgo.
   - If a stable machine-readable verifier failure taxonomy becomes necessary, it should be added as a typed failure metadata extension without making existing message text normative.

8. **Account bootstrap transactions remain explicit special cases.**
   - `CreateNamedAccount`-style flows may recover the signing key first and then require equality with `initialKeyId`.
   - This is a bootstrap authority rule for account creation, not a relaxation of `verifySignature` for existing account authority.

## Consequences
- Embedders can state that a reducer path using `SignatureVerifier.verifySignature` rejects arbitrary-key signing for another named or unnamed account, assuming the named-account `(name, recoveredKeyId)` lookup they provide is authoritative for registered keys.
- Named-account authorization remains table-state dependent: the embedder supplies the `(name, recoveredKeyId)` lookup, and the verifier treats lookup absence as rejection.
- Named-account expiration is enforced only through the looked-up `KeyInfo`.
- Unnamed-account authorization is table-state independent and depends only on exact recovered key equality; this shared verifier does not expire unnamed accounts.
- Error-message parsing is explicitly discouraged. Adapters should map `CryptoFailure` to their own stable response contracts.
- Test coverage should include named missing-key rejection, named expired-key rejection, exact `expiresAt` boundary acceptance, recovered-key lookup, unnamed mismatch rejection, successful recovered-key return, and successful named/unnamed verification.

## Rejected Alternatives
1. **Trust the `AccountSignature.account` claim without recovered-key binding**
   - This would let the transaction payload choose its signer identity independently of the signing key.
   - It breaks the account authority model in ADR-0010.

2. **Use named-account lookup by claimed key id instead of recovered key id**
   - A claimed key id is payload data; it is not cryptographic proof.
   - The lookup must be keyed by the recovered key id.

3. **Make `CryptoFailure.msg` a public ABI**
   - Message text is useful for diagnostics but too brittle for external protocol branching.
   - Stable protocol keys belong in typed failure metadata or adapter mappings.

4. **Let unnamed accounts fall back to named-account or guardian recovery**
   - Unnamed accounts are intentionally direct key-id accounts with no recovery mechanism.
   - Adding fallback recovery would change the account model and should require a separate ADR.

## Follow-Up
- Add focused verifier contract tests if any of the above cases are only indirectly covered by account/module integration tests.
- Consider a typed `SignatureVerificationFailureReason` or `FailureCode` projection if embedders need stable machine-readable failure branches at the core layer.
- Keep bbgo's HTTP errorKey contract separate from this ADR; bbgo can map all verifier failures to its own `signature_invalid` key family.

## References
- [ADR-0010: Blockchain Account Model and Key Management](0010-blockchain-account-model-and-key-management.md)
- [ADR-0012: Signed Transaction Requirement](0012-signed-transaction-requirement.md)
- [ADR-0014: v0.1.1 Foundation Contracts](0014-v0-1-1-foundation-contracts.md)
- [0018 - v0.2.3 Signature And Finalization Contract Release Plan](../plans/0018-v0-2-3-signature-and-finalization-contract-release-plan.md)
