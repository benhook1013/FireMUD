# ADR 0037: Fail-Closed Token-Authority Outages With Bounded Active Gameplay

## Status

Accepted

## Implementation Status

The fail-closed outage classification and bounded active-gameplay contract remain target state rather than a complete implementation. Registry/generation enforcement and end-to-end authority-freshness lease behavior are still incomplete; proof that every protected and admission path fails closed and that unresolved active gameplay terminates at the 60-second bound remains outstanding.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `SF-1.3` Authentication, authorization, service identity, and secret handling
- Affected capabilities: `SF-2.2`, `AA-2.1`, `AA-2.3`, `AA-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `JWT-03`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `JWT-03`

## Context

Protected Browser, player-bootstrap, and private control-plane JWTs depend on Account-owned issued-token and auth-generation state in Coordination Redis. The same Redis role also carries gameplay session, queue, lease, and tick coordination. “Redis unavailable” can therefore mean either that token authority alone cannot be reached while gameplay coordination still works, or that the full coordination plane is unavailable.

The earlier design said control-plane and new admission fail closed but did not cleanly distinguish unavailable authority from a reachable missing/revoked token record. It also referred ongoing gameplay to general Redis policy without stating how the existing 60-second active-authority freshness lease applies.

## Decision

### Error And Client-State Boundary

- A timeout, connection failure, or unavailable issued-token/auth-generation dependency returns the retryable infrastructure outcome `AUTH_UNAVAILABLE` with HTTP `503` or the protocol-equivalent unavailable status. Clients retain local authentication state and retry with bounded backoff; this outcome does not assert that the token was revoked.
- Reachable authoritative state that is missing, expired, deleted, malformed, or mismatched returns `AUTH_SESSION_REVOKED` or the more specific canonical invalid-token outcome and requires fresh authentication.
- A completed Coordination Redis reset that removed issued-token records is reachable missing state, not an availability exception. Reauthentication/reissuance is required.

### Fail-Closed Operations

- Account exposes no JWT unless its registry record and current generation state are established. Login issuance, bootstrap issuance, token refresh, and rotation therefore fail with `AUTH_UNAVAILABLE` when registration or authority cannot complete.
- Every protected control-plane request fails closed when its issued-token or applicable generation state cannot be read. Admin, support, billing, payment, and other sensitive operations receive no stale-authority exception.
- New gameplay login, join, `PLAY`, reconnect/rebind, and other admission transitions fail closed whenever their required token, membership, generation, or gameplay-binding authority cannot be established.

### Already-Admitted Gameplay

- Ordinary gameplay commands do not consult the issued-token registry or auth generations per command.
- If token-authority access alone is unavailable while gameplay coordination remains healthy, an already-admitted binding may continue only through the last successfully renewed ADR 0030 authority-freshness lease. The lease is never extended without fresh authority, new admission remains closed, and an unresolved binding terminates at the existing 60-second maximum.
- A still-valid bounded lease is prior positive authority, not permission to infer or recreate authority from arbitrary local JWT claims or process memory.
- If the complete Coordination Redis role is unavailable, Game Session does not execute gameplay mutations whose queues, locks, leases, session state, or tick coordination cannot be established. Transport/socket recovery may follow the existing bounded recovery and close contracts, but it does not authorize local-only gameplay processing.

## Consequences

- Control-plane and admission availability depend on Coordination Redis token authority, but outage handling never turns cryptographic validation alone into authorization.
- Frontends can distinguish a retryable infrastructure incident from logout, password reset, ban, or another real revocation.
- A registry-only incident does not add a lookup to gameplay commands and does not instantly eject established players; unresolved active authority remains bounded to 60 seconds.
- A complete Coordination Redis outage already removes correctness-critical gameplay coordination, so halting mutations is not an additional token-policy outage.

## Alternatives Considered

### JWT-Only Validation During Redis Failure

This improves availability but bypasses per-token logout, bulk generations, and the issued-token defense against signing-key misuse exactly when server-side authority cannot be checked.

### Cached Grace For Protected Control-Plane Requests

A short positive cache could preserve some reads or mutations, but it delays logout and security revocation for every protected surface and complicates classification. FireMUD keeps the grace boundary only for already-admitted gameplay under ADR 0030, not control-plane requests or admission.

### Separate Auth Redis From Gameplay Coordination Redis

This improves fault isolation but adds another security-critical datastore, reset contract, backup/restore boundary, and availability dependency. Revisit only if measured incidents show shared-role fault coupling materially harms the product.

## Implementation and Proof Obligations

- Define one stable `AUTH_UNAVAILABLE` mapping across HTTP, gRPC, text/bootstrap flows, shared clients, metrics, and audit-safe logs.
- Prove unavailable registry/generation state preserves frontend auth state while missing/deleted/mismatched state causes hard reauthentication.
- Prove Account never exposes a token whose registry/generation establishment failed.
- Prove protected control-plane, sensitive mutations, admission, reconnect, and refresh fail closed without stale authority.
- Prove registry-only outages add no per-command gameplay lookup, never renew the authority lease, and terminate unresolved bindings by 60 seconds.
- Prove complete Coordination Redis loss halts correctness-sensitive gameplay processing and uses existing bounded recovery/close behavior without inventing local authority.

## Reversibility and Revisit Triggers

Error classification and the authority lease are bounded contracts rather than persistent data formats. Revisit if token authority moves to a separately available store, measured control-plane availability cannot meet SLOs, or a route-specific read-only stale-authority model has a demonstrated product need and explicit security analysis.
