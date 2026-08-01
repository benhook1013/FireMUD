# FireMUD JWT Compromise Runbook

This runbook defines the mandatory response flow for suspected compromise of JWT signing key material.
The same hard-cutover key semantics are also required for player-facing post-restore hardening (`system-architecture-post-restore-hardening.md#post-restore-secret-hardening`) even when no active compromise is confirmed, because restored snapshots may resurrect stale trust material.

## Implementation Status

This runbook describes target-state behavior. The current runtime does not implement non-exportable signer delegation, Account-only asymmetric issuance and validation, issuer authority-generation advancement and validation, or the rotation/convergence evidence flow; existing HMAC/JWKS file behavior must not be treated as proof that this response flow is available.

## Trigger Conditions

Run this flow when any of the following is true:

- Signing key material (`jwt-signing-keys`) is suspected exposed.
- Unexpected token validation patterns indicate possible key misuse.
- Incident response explicitly classifies the event as key compromise.

## Required Response Flow

1. Quarantine JWT trust surfaces.
   - Stop new Account JWT issuance and block JWT-protected admission/control-plane traffic.
   - Keep protected traffic closed until the replacement, invalidation, and convergence gates pass.
2. Run compromise-mode rotation.
   - Have Account Service initiate one authenticated, operation-bound transition for a new asymmetric signing generation. Account atomically creates or recovers a durable `compromiseRotationOperationId` record bound to an immutable request digest, the expected current signer generation, a `candidateGeneration` strictly greater than `expectedSignerGeneration`, `kid`, and the incident. The promotion CAS must match the current signer generation to `expectedSignerGeneration` and the operation's exact `candidateGeneration` to the candidate being promoted; only that CAS may commit the candidate. The committed signer generation must equal that exact `candidateGeneration`; any candidate that is not strictly greater, or any committed generation that differs from the candidate, is rejected without mutation. A uniqueness constraint and compare-and-set fence permit only one nonterminal compromise operation for each expected signer generation, regardless of incident; a retry with the same operation ID and digest resumes that record, while a conflicting operation ID, digest, or expected generation is rejected without mutation. That one operation owns signing-generation promotion, Account-owned JWKS publication, and the issuer-generation advance. In target custody it requests the non-exportable signer, which returns only the operation-bound generation, `kid`, and non-secret cryptographic digest or attestation for the delegated operation. In the interim fallback Account requests the materialization controller to generate, CAS-write, or prune only the named private-Secret slots; that controller additionally returns the Kubernetes `resourceVersion`, CAS result, and private-slot pruning evidence. Account validates and reconciles the result applicable to the selected custody path, promotes the generation, publishes its JWKS, advances issuer authority, and owns the lifecycle decision. Account remains authoritative for validation, promotion, issuer authority, JWKS publication, and public/private pruning policy. Rotation automation is observation-only and never receives private material. The private key must never enter rotation automation.
   - Account promotes the new signing generation, publishes its JWKS, and performs the sole issuer-generation advance for this operation exactly once. Each stage is durably recorded against the same operation ID and reconciled before completion. A lost response retried with the same operation ID and digest returns the already committed generation, JWKS publication evidence, issuer-generation evidence, and validator-convergence state without another promotion or issuer-generation advance. A changed digest or expected generation conflicts without mutation. Account must reconcile the controller's authenticated CAS result and Account-owned public-JWKS status before declaring compromise rotation complete. Do not overlap, retain, or roll back to a compromised key.
   - The rotation Job/CronJob must never read or update `jwt-signing-keys` or write `jwt-jwks`; it only observes Account/controller status, runs validator-convergence checks, and records evidence.
3. Verify and record the environment-wide issuer authority committed in Step 2.
   - Re-read the same committed issuer generation and its authority-projection freshness/source evidence through one versioned Account snapshot. The response version or ETag binds the committed generation, source transaction/outbox version, observed projection generation, and projection status; recovery must not assemble those fields from independently changing reads. If the snapshot changes while evidence is captured, restart the read and keep the trust surface quarantined. This is the logical invalidation boundary: every validator rejects a registry snapshot whose issuer generation is older than that committed generation, regardless of account, tenant, or token profile. Physical deletion of old token records and cleanup of gameplay/control sessions are bounded best-effort follow-up work; neither is a correctness authority, and wildcard scans or deletion cannot substitute for validator rejection of stale-generation snapshots.
   The corresponding issuer-generation projection is an asynchronous outbox output, not an atomically committed part of the durable generation/event transaction, and is applied set-if-greater. Missing, stale, uncertain, or cross-version projection evidence fails closed. Account must record the versioned authority-projection freshness fence before recovery can advance. Reauthentication remains mandatory after the authority transition.
   - Treat compromise of the per-environment Account key as global for that issuer. Tenant-selective cleanup is not sufficient.
   - Do not treat the issuer authority-generation advance as a substitute for key rejection; an attacker holding the old private key can mint fresh claims.
4. Force validator convergence.
   - Freeze a versioned validator inventory when quarantine begins, then refresh or restart every validator in that inventory. A validator added, replaced, or restarted after the freeze is quarantined by default and must register in a newer inventory version and prove the same convergence contract before protected traffic can reopen. Install a fail-closed block for the compromised `kid` that overrides any still-fresh cached JWK, and atomically evict or replace that cached key before validation resumes. Each validator must also prove it has observed the current issuer authority-projection freshness fence. The final convergence check re-reads the inventory version; any membership or version change invalidates the prior completeness result until every current validator has supplied evidence. An unreachable registry, generation, or freshness-fence dependency remains retryable `AUTH_UNAVAILABLE` / HTTP 503 and must not be classified as revocation; once the authority is reachable, missing, malformed, ambiguous, stale, or mismatched evidence is authoritative invalid/revoked evidence and fails closed. A validator that cannot prove the required current state remains quarantined.
5. Verify convergence.
   - Confirm every validator rejects the compromised `kid` and accepts the replacement `kid`.
6. Stabilize, monitor, and reopen.
   - Watch auth failure/success metrics and 401/403 patterns for anomalous behavior.
   - Reopen protected traffic only after an authorized responder approves the complete evidence record.

## Mandatory Evidence Checklist

Before reopening player-facing traffic, incident records must include:

- Incident/ticket identifier and responder/approver identity.
- Compromised key identifiers (`kid`) and replacement key identifiers.
- Durable compromise-rotation operation ID, immutable request digest, expected signer generation, candidate generation strictly greater than expected, committed signer generation equal to that candidate, uniqueness/CAS evidence, JWKS publication evidence, and proof that the issuer generation advanced exactly once; a same-operation retry must return this evidence without another advance, while a conflicting operation or digest must leave state unchanged.
- Quarantine start and end timestamps plus the protected surfaces covered.
- Timestamped proof that Account Service authorized the private-material operation and reconciled the authenticated result for the selected custody path: operation-bound generation, `kid`, and non-secret cryptographic evidence for a non-exportable signer; or those fields plus Kubernetes `resourceVersion`, CAS result, and private-slot pruning evidence for the interim materialization controller. The record must also prove that Account validated and promoted the signing generation, published JWKS, reconciled the required public/private pruning policy, and that rotation automation only observed the Account-owned `jwt-jwks` update through the control/status interface.
- Issuer authority-generation, authority-projection freshness-fence, and logical session-invalidation evidence from one versioned Account snapshot. The record must identify the snapshot version/ETag, committed Account source version/event, observed projection generation/status, and the exact scope that was fenced. Physical deletion of old token records and cleanup of gameplay/control sessions is bounded best-effort work and must be recorded separately; it is not required as cleanup completion before the authority-generation gate can be judged satisfied.
- Frozen and final validator inventory versions, last observed JWKS generation, observed authority-projection fence, and convergence proof that each current validator rejects the compromised `kid` and accepts the replacement. The evidence must account for validators added, replaced, or restarted after the initial freeze. An unavailable registry, generation authority, or freshness-fence authority remains blocked as retryable `AUTH_UNAVAILABLE` / HTTP 503; it is not evidence that tokens are revoked.
- Reopen decision timestamp and approver.

## Environment Notes

- `production` and `staging`: mandatory evidence is required before reopening traffic.
- `hobby-self-hosted`: same technical flow applies; operators must still capture the evidence list in deployment notes.

## Related Documentation

- `./system-architecture-security.md#jwt-key-compromise-response`
- `./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative`
- `./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md`
- `./system-architecture-post-restore-hardening.md#post-restore-secret-hardening`
