# Security, Trust, Privacy, And Abuse Review

Use this prompt to review the current accepted security contracts and their implementation boundaries. This is not a substitute for a whole-platform threat model.

Apply the [shared review contract](./00-shared-review-contract.md).
Apply the [orchestrated review workstream contract](./02-orchestrated-review-workstream-contract.md).

## Orchestrated Execution

A full invocation is an orchestrated review workstream: the invoking main thread takes primary ownership and delegates bounded evidence lanes for:

- authentication, token, route, service, and workload authorization;
- tenant isolation, privacy, and data, log, trace, and artifact controls;
- TLS, mTLS, header trust, secrets, rotation, and break-glass operational boundaries; and
- replay, brute-force, flooding, moderation, rate, quota, and resource-exhaustion controls, including stale or unavailable-authority failure behavior.

The primary thread reconciles end-to-end trust boundaries and keeps the deferred whole-platform threat-model limitation prominent in the aggregate review.

## Starting Sources

- `SECURITY.md`
- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-threat-model.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-jwt-and-token-contracts.md`
- `design/architecture/system-architecture-authz-route-matrix.md`
- `design/architecture/system-architecture-authz-route-matrix.yaml`
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/infrastructure/environment-and-secrets-overview.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`
- service-specific security and operator-access contracts, route definitions, code, configuration, and focused proof

## Scope Boundary

The canonical [FireMUD threat-model architecture](../../../architecture/system-architecture-threat-model.md) marks the whole-platform threat model as deferred and not yet considered or accepted. State that limitation prominently. Review accepted security requirements and concrete trust boundaries, but do not claim exhaustive threat-model coverage or invent accepted risks. The canonical source requires a future threat-model pass to reconcile the current contracts; when that source is updated, update this scope boundary and the resulting review accordingly.

## Review

Check:

- external and internal trust boundaries, route authorization, service identity, workload identity, and tenant isolation;
- authentication factors, token lifecycle, JWT and key rotation, revocation, authority generations, clock and freshness rules;
- header trust, TLS and mTLS, plaintext Telnet policy, secret delivery, credential rotation, and break-glass access;
- authorization at edge, receiving service, data owner, and operator workflow boundaries;
- replay, brute-force, flooding, command abuse, moderation, report handling, rate limits, quotas, and resource exhaustion;
- cross-tenant leakage, presence privacy, logs and traces containing sensitive data, export and erasure access, retention, and artifact delivery;
- failure behavior when identity, policy, key, secret, or authorization authorities are stale or unavailable; and
- implementation and focused negative-path proof for accepted controls.

Keep repository evidence separate from live provider, certificate, cluster, scan, or incident evidence. Risk acceptance and threat-model scope require human decisions.

## Output

Provide:

1. a trust-boundary and control coverage table;
2. security, privacy, tenant-isolation, abuse, and fail-open findings;
3. accepted controls lacking implementation or focused proof;
4. explicit unassessed areas caused by the deferred threat model or unavailable live evidence; and
5. the review state required by the shared contract.
