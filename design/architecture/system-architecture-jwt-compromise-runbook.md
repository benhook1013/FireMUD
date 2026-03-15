# FireMUD JWT Compromise Runbook

This runbook defines the mandatory response flow for suspected compromise of JWT signing key material.
The same hard-cutover key semantics are also required for player-facing post-restore hardening (`system-architecture-backup-recovery.md#post-restore-secret-hardening`) even when no active compromise is confirmed, because restored snapshots may resurrect stale trust material.

## Trigger Conditions

Run this flow when any of the following is true:

- Signing key material (`jwt-signing-keys`) is suspected exposed.
- Unexpected token validation patterns indicate possible key misuse.
- Incident response explicitly classifies the event as key compromise.

## Required Response Flow

1. Run compromise-mode rotation.
   - Generate a new signing keypair and update `jwt-signing-keys`.
   - Regenerate `jwt-jwks` with only uncompromised keys (no overlap with compromised keys).
2. Invalidate active sessions.
   - Run scoped or global session cleanup so reconnect requires fresh `LOGIN`.
3. Force validator convergence.
   - Restart or force key reload for validators that may cache JWKS/keys.
4. Verify convergence.
   - Confirm no service accepts tokens signed by compromised `kid`.
5. Stabilize and monitor.
   - Watch auth failure/success metrics and 401/403 patterns for anomalous behavior.

## Mandatory Evidence Checklist

Before reopening player-facing traffic, incident records must include:

- Incident/ticket identifier and responder/approver identity.
- Compromised key identifiers (`kid`) and replacement key identifiers.
- Timestamped proof of `jwt-signing-keys` and `jwt-jwks` update completion.
- Session invalidation scope and completion evidence.
- Validator convergence proof (for example targeted validation checks/log evidence that compromised `kid` is rejected).
- Reopen decision timestamp and approver.

## Environment Notes

- `production` and `staging`: mandatory evidence is required before reopening traffic.
- `hobby-self-hosted`: same technical flow applies; operators must still capture the evidence list in deployment notes.

## Related Documentation

- `design/architecture/system-architecture-security.md#jwt-key-compromise-response`
- `design/architecture/system-architecture-backup-recovery.md#post-restore-secret-hardening`
