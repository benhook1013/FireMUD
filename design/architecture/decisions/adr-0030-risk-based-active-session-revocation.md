# ADR 0030: Risk-Based Active-Session Revocation

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.4` Commerce, subscriptions, purchases, donations, and platform fees
- Affected capabilities: `AA-1.2`, `AA-1.5`, `AA-2.3`, `GR-1.1`, `PO-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SESSION-07`

## Context

Role, membership, private-realm access, account security, and tenant billing can all change while a player is connected, but they do not represent the same risk. Treating every change as an immediate kick disrupts harmless role refresh and temporary payment recovery. Waiting for reconnect or token expiry after authority or security loss permits a player to keep access they no longer possess.

The previous target distinguished soft and hard billing states and required event-driven revocation, but it left new-admission behavior during `grace`, private-realm grant revocation, and the meaning of “immediate” propagation unclear. It also had no implemented producer/consumer, monotonic membership version, revocation-watermark workflow, bounded active-session indexes, or hard-cutoff proof. Current code principally rechecks membership and a limited entitlement response at `PLAY`; already-connected sessions are not revoked by these changes.

## Decision

### Authority And Security Changes

- A role change that preserves gameplay authority refreshes the effective capability context without disconnecting the player or requiring login again.
- Loss of tenant membership, the `player`/gameplay-admission capability, or a required private-realm access grant revokes the affected gameplay binding and terminates its connected socket.
- Account security lock, ban, password-reset revocation, logout-all, or equivalent account-wide security event revokes gameplay bindings across tenants.
- A friendly scheduled playtest or realm ending uses the realm close-and-drain lifecycle before access grants are revoked. Revoking a grant is an authority removal, not a drain request.
- Global role changes do not grant or elevate gameplay authority under ADR 0026.

### Billing-State Matrix

- `trialing` and `active`: gameplay and admission continue under ordinary entitlements and quotas.
- `past_due`: existing and new gameplay continue under ordinary quotas. Operator and creator surfaces show strong warnings, but this state alone does not disconnect players or close admission.
- `grace`: connected sessions and reconnect of the exact same still-resumable gameplay binding continue. First-time public join, first/new gameplay bindings, new instances, scale-out, and quota-increasing operations are denied.
- `suspended` and effective `canceled`: gameplay admission and reconnect close, connected gameplay authority is revoked, and instances stop accepting players. Game Session may flush one bounded, non-sensitive availability notice before closing sockets, but players do not receive a continuation window. Instance processes then have at most five minutes for internal cleanup before stopping.
- Period-end cancellation remains in its preceding paid state until the effective cancellation time. Immediate cancellation enters the hard state after provider confirmation and the authoritative Account commit.
- Explicit billing-safe and support-safe management remains available so authorized users can resolve billing, inspect permitted status, or export tenant data.

### Delivery And Bounded Enforcement

- Account is the sole authority and watermark writer. It commits the durable state/version change and monotonic outbox event atomically in its database, then idempotently projects the applicable account, tenant, or membership revocation watermark. A cutoff workflow does not report enforcement complete until the watermark projection succeeds.
- Game Session consumes revocation events durably and idempotently. Events carry a stable ID and monotonic authority version; duplicates and older versions are no-ops, while gaps trigger authoritative reconciliation.
- Game Session maintains bounded active-binding indexes by account, tenant, and private-realm grant scope. Revocation must not rely on Redis wildcard scans.
- The event is the fast path. Batched watermark/version reconciliation must ensure a missed event cannot preserve revoked gameplay authority for more than 60 seconds.
- The reconciliation freshness lease is fail-closed: new admission stops when authority freshness is unavailable, and an active binding whose authority cannot be re-established is terminated at the 60-second bound.
- Routine gameplay commands do not call Account or read revocation watermarks. The bounded reconciliation is periodic and batched by active authority scope.

## Consequences

- Security and authorization removal take effect for active gameplay instead of waiting for token expiry or reconnect.
- Temporary payment failure does not destroy a live game community, while `grace` prevents new resource and admission commitments.
- A missed revocation event has a defined maximum exposure rather than an indefinite active-session loophole.
- The 60-second fail-closed reconciliation lease can disconnect players during a prolonged authority/Coordination Redis incident. This is the accepted cost of a real revocation bound; it is less expensive than a per-command authority read.
- Hard billing cutoff may consume up to the existing five-minute internal drain budget, but that window admits no players and grants no continued gameplay.
- The contract adds durable event, watermark-projection, versioning, active-index, reconciliation, and end-to-end cutoff proof obligations.

## Alternatives Considered

### Check Only At Reconnect Or Token Expiry

This is operationally simple but permits banned accounts, removed members, revoked private-realm users, and hard-blocked tenants to continue indefinitely while their socket remains connected.

### Kick On Every Role Or Billing Change

This gives one mechanical rule but disrupts harmless role updates and live games during recoverable `past_due` and `grace` periods.

### Allow Existing Sessions Through Every Billing State

Warning-only enforcement protects players from owner billing failures but permits unbounded unpaid hosting and makes cancellation ineffective until every player disconnects naturally.

### Per-Command Watermark Checks

Checking Account or Redis before every command gives a tighter revocation observation point, but adds latency and an authority-store availability dependency to the routine gameplay hot path. Durable events plus batched reconciliation provide a bounded compromise.

## Implementation and Proof Obligations

- Implement monotonic membership, grant, account-security, and tenant-billing versions with durable outbox producers and idempotent consumers.
- Implement Account-owned account, tenant, and membership watermark projection with retry and cutoff-completion semantics.
- Persist the authority versions required by active gameplay bindings and implement bounded account, tenant, and private-realm indexes for targeted termination.
- Add batched reconciliation, the 60-second freshness lease, event-gap repair, and bounded telemetry for event lag, projection failures, reconciliation age, and termination outcome.
- Extend the runtime entitlement response with explicit public-join, new-gameplay-binding, and instance/scale flags; correct `past_due` and `grace` handling.
- Prove harmless role refresh, membership/player/grant removal, password reset/security lock/logout-all, each billing state, scheduled and immediate cancellation, duplicate/gapped events, missed-event reconciliation, authority outage, notice/close behavior, reconnect denial, and five-minute instance cleanup.
- Prove routine gameplay commands perform no Account or revocation-watermark lookup.

## Required Documentation Alignment

- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/microservices/account-service/subscription-management.md`
- `design/architecture/microservices/account-service/runtime-and-data.md`
- `design/architecture/microservices/game-session-service/runtime-and-data.md`
- `design/architecture/user-journeys-operators.md`
- `design/architecture/decisions/adr-0028-differentiated-entitlement-freshness.md`
- `design/project-management/implementation-tracking/player-access-and-session.md`

## Reversibility and Revisit Triggers

Billing-state operation flags and the reconciliation interval are centralized policy and can be tightened without changing authority ownership. Revisit if measured payment recovery or player disruption shows the `grace` matrix is wrong, the 60-second bound causes unacceptable outage amplification, revocation incidents demand a tighter bound, or deployment scale makes batched active-authority reconciliation materially expensive.
