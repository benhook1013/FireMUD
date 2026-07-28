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
   - Have Account Service initiate one authenticated, operation-bound transition for a new asymmetric signing generation. In target custody it requests the non-exportable signer, which returns only the operation-bound generation, `kid`, and non-secret cryptographic digest or attestation for the delegated operation. In the interim fallback Account requests the materialization controller to generate, CAS-write, or prune only the named private-Secret slots; that controller additionally returns the Kubernetes `resourceVersion`, CAS result, and private-slot pruning evidence. Account validates and reconciles the result applicable to the selected custody path, promotes the generation, publishes its JWKS, advances issuer authority, and owns the lifecycle decision. Account remains authoritative for validation, promotion, issuer authority, JWKS publication, and public/private pruning policy. Rotation automation is observation-only and never receives private material. The private key must never enter rotation automation.
   - Account must reconcile the controller's authenticated CAS result and Account-owned public-JWKS status before declaring compromise rotation complete. Do not overlap, retain, or roll back to a compromised key.
   - The rotation Job/CronJob must never read or update `jwt-signing-keys` or write `jwt-jwks`; it only observes Account/controller status, runs validator-convergence checks, and records evidence.
3. Invalidate environment-wide issuer authority.
   - Advance the issuer authority generation through Account authority. This is the logical invalidation boundary: every validator rejects a registry snapshot whose issuer generation is older than the new generation, regardless of account, tenant, or token profile. Physical deletion of old token records and cleanup of gameplay/control sessions are bounded best-effort follow-up work; neither is a correctness authority, and wildcard scans or deletion cannot substitute for validator rejection of stale-generation snapshots.
   The corresponding issuer-generation projection is applied set-if-greater; missing, stale, or uncertain projection evidence fails closed. Account must record an authority-projection freshness fence that identifies the committed issuer generation, its source transaction or outbox/event version, the observed projection generation, and the projection status before recovery can advance. Reauthentication remains mandatory after the authority transition.
   - Treat compromise of the per-environment Account key as global for that issuer. Tenant-selective cleanup is not sufficient.
   - Do not treat the issuer authority-generation advance as a substitute for key rejection; an attacker holding the old private key can mint fresh claims.
4. Force validator convergence.
   - Refresh or restart every validator in the declared validator inventory. Install a fail-closed block for the compromised `kid` that overrides any still-fresh cached JWK, and atomically evict or replace that cached key before validation resumes. Each validator must also prove it has observed the current issuer authority-projection freshness fence. An unreachable registry, generation, or freshness-fence dependency remains retryable `AUTH_UNAVAILABLE` / HTTP 503 and must not be classified as revocation; once the authority is reachable, missing, malformed, ambiguous, stale, or mismatched evidence is authoritative invalid/revoked evidence and fails closed. A validator that cannot prove the required current state remains quarantined.
5. Verify convergence.
   - Confirm every validator rejects the compromised `kid` and accepts the replacement `kid`.
6. Stabilize, monitor, and reopen.
   - Watch auth failure/success metrics and 401/403 patterns for anomalous behavior.
   - Reopen protected traffic only after an authorized responder approves the complete evidence record.

## Mandatory Evidence Checklist

Before reopening player-facing traffic, incident records must include:

- Incident/ticket identifier and responder/approver identity.
- Compromised key identifiers (`kid`) and replacement key identifiers.
- Quarantine start and end timestamps plus the protected surfaces covered.
- Timestamped proof that Account Service authorized the private-material operation and reconciled the authenticated result for the selected custody path: operation-bound generation, `kid`, and non-secret cryptographic evidence for a non-exportable signer; or those fields plus Kubernetes `resourceVersion`, CAS result, and private-slot pruning evidence for the interim materialization controller. The record must also prove that Account validated and promoted the signing generation, published JWKS, reconciled the required public/private pruning policy, and that rotation automation only observed the Account-owned `jwt-jwks` update through the control/status interface.
- Issuer authority-generation, authority-projection freshness-fence, and logical session-invalidation evidence. The record must identify the committed Account source version/event, observed projection generation/status, and the exact scope that was fenced. Physical deletion of old token records and cleanup of gameplay/control sessions is bounded best-effort work and must be recorded separately; it is not required as cleanup completion before the authority-generation gate can be judged satisfied.
- Exact validator inventory, last observed JWKS generation, observed authority-projection fence, and convergence proof that each validator rejects the compromised `kid` and accepts the replacement. An unavailable registry or generation authority remains blocked as retryable `AUTH_UNAVAILABLE` / HTTP 503; it is not evidence that tokens are revoked.
- Reopen decision timestamp and approver.

## Environment Notes

- `production` and `staging`: mandatory evidence is required before reopening traffic.
- `hobby-self-hosted`: same technical flow applies; operators must still capture the evidence list in deployment notes.

## Related Documentation

- `./system-architecture-security.md#jwt-key-compromise-response`
- `./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative`
- `./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md`
- `./system-architecture-post-restore-hardening.md#post-restore-secret-hardening`
