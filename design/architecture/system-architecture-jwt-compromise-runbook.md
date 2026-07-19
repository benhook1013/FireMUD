# FireMUD JWT Compromise Runbook

This runbook defines the mandatory response flow for suspected compromise of JWT signing key material.
The same hard-cutover key semantics are also required for player-facing post-restore hardening (`system-architecture-backup-recovery.md#post-restore-secret-hardening`) even when no active compromise is confirmed, because restored snapshots may resurrect stale trust material.

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
   - Generate a new asymmetric signing keypair and Account signing generation.
   - Regenerate `jwt-jwks` with only uncompromised keys. Do not overlap, retain, or roll back to a compromised key.
3. Invalidate environment-wide issuer authority.
   - Advance `session:auth:generation:issuer:<issuerId>` through Account authority and perform the required bounded issued-token/session cleanup so reauthentication is mandatory.
   - Treat compromise of the per-environment Account key as global for that issuer. Tenant-selective cleanup is not sufficient.
   - Do not treat the watermark as a substitute for key rejection; an attacker holding the old private key can mint fresh claims.
4. Force validator convergence.
   - Refresh or restart every validator in the declared validator inventory.
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
- Timestamped proof of `jwt-signing-keys` and `jwt-jwks` update completion.
- Issuer-wide watermark and session invalidation completion evidence.
- Exact validator inventory, last observed JWKS generation, and convergence proof that each validator rejects the compromised `kid` and accepts the replacement.
- Reopen decision timestamp and approver.

## Environment Notes

- `production` and `staging`: mandatory evidence is required before reopening traffic.
- `hobby-self-hosted`: same technical flow applies; operators must still capture the evidence list in deployment notes.

## Related Documentation

- `design/architecture/system-architecture-security.md#jwt-key-compromise-response`
- `design/architecture/system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative`
- `design/architecture/decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md`
- `design/architecture/system-architecture-backup-recovery.md#post-restore-secret-hardening`
