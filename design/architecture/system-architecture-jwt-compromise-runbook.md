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
   - Have Account Service request or generate a new asymmetric signing keypair and Account signing generation, validate the resulting generation, promote it, publish its JWKS, and prune any compromised public/private rollback material. Account remains authoritative for validation, promotion, JWKS publication, and pruning. Target-state private-key operations are delegated to a non-exportable signer in every environment; the signer may perform no validation, promotion, publication, or pruning. Until that capability is implemented, the controlled fallback is Account-only Kubernetes Secret custody described in [ADR 0014](./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md): validators receive only public JWKS and rotation automation never receives private material. The private key must never enter rotation automation.
   - Through the single Account JWT rotation control/status interface, have rotation automation request Account to publish only uncompromised public keys. Do not overlap, retain, or roll back to a compromised key.
   - The rotation Job/CronJob must never read or update `jwt-signing-keys` or write `jwt-jwks`; it observes Account-owned publication and pruning, runs validator-convergence checks, and records evidence only.
3. Invalidate environment-wide issuer authority.
   - Advance the issuer authority generation through Account authority. This is the logical invalidation boundary: every validator rejects a registry snapshot whose issuer generation is older than the new generation, regardless of account, tenant, or token profile. Physical deletion of old token records and cleanup of gameplay/control sessions are bounded best-effort follow-up work; neither is a correctness authority, and wildcard scans or deletion cannot substitute for validator rejection of stale-generation snapshots.
   - Treat compromise of the per-environment Account key as global for that issuer. Tenant-selective cleanup is not sufficient.
   - Do not treat the authority-generation advance as a substitute for key rejection; an attacker holding the old private key can mint fresh claims.
4. Force validator convergence.
   - Refresh or restart every validator in the declared validator inventory. Install a fail-closed block for the compromised `kid` that overrides any still-fresh cached JWK, and atomically evict or replace that cached key before validation resumes. A validator that cannot prove this state remains quarantined.
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
- Timestamped proof that Account Service authorized or completed private-key generation, validated and promoted the signing generation, published JWKS, and performed any required public/private pruning, including any delegated private-key operation performed by a non-exportable signer, plus proof that rotation automation observed the Account-owned `jwt-jwks` update through the control/status interface.
- Issuer authority-generation and session invalidation completion evidence.
- Exact validator inventory, last observed JWKS generation, and convergence proof that each validator rejects the compromised `kid` and accepts the replacement.
- Reopen decision timestamp and approver.

## Environment Notes

- `production` and `staging`: mandatory evidence is required before reopening traffic.
- `hobby-self-hosted`: same technical flow applies; operators must still capture the evidence list in deployment notes.

## Related Documentation

- `./system-architecture-security.md#jwt-key-compromise-response`
- `./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative`
- `./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md`
- `./system-architecture-post-restore-hardening.md#post-restore-secret-hardening`
