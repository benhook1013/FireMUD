# ADR 0030: Risk-Based Active-Session Revocation

## Status

Accepted

## Implementation Status

No complete producer/consumer, monotonic membership version, authority-generation workflow, bounded active-session index, or hard-cutoff proof exists. Current code principally rechecks membership and a limited entitlement response at `PLAY`; authority, grant, and billing changes do not revoke already-connected sessions.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.4` Commerce, subscriptions, purchases, donations, and platform fees
- Affected capabilities: `AA-1.2`, `AA-1.5`, `AA-2.3`, `GR-1.1`, `PO-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SESSION-07`

## Context

Role, membership, private-realm access, account security, and tenant billing can all change while a player is connected, but they do not represent the same risk. Treating every change as an immediate kick disrupts harmless role refresh and temporary payment recovery. Waiting for reconnect or token expiry after authority or security loss permits a player to keep access they no longer possess.

The previous target distinguished soft and hard billing states and required event-driven revocation, but it left new-admission behavior during `grace`, private-realm grant revocation, and the meaning of “immediate” propagation unclear.

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
- `grace`: connected sessions and reconnect of the exact same still-resumable gameplay binding continue. A grace reconnect is not authorized by Redis binding presence or a cached billing result: before exact-binding resume, Game Session must obtain an Account-owned `resumeActivationLease` that revalidates the current lifecycle/security state, tenant authority state, `{accountId, tenantId}` membership and gameplay-admission authority, and, for a private realm, the current realm grant and `grantVersion`. If fresh entitlement evaluation alone is unavailable while Account remains able to perform that lease decision, an eligible positive last-known-good entitlement snapshot from ADR 0028 may supply only the entitlement input within its `< 5 minute` continuity window and sequence-safety conditions. It never substitutes for the lease or for current lifecycle, security, membership, grant, or revocation authority; inability to establish the Account lease fails closed. The returned identity, authority versions, tenant, game instance, realm, and character must match the stored resume episode; unavailable, stale, revoked, expired, or mismatched authority fails closed without creating a new binding. First-time public join, first/new gameplay bindings, new instances, scale-out, and quota-increasing operations are denied.
- Grace resume activation uses an Account-owned, exact-binding and resume-episode `resumeActivationLease`. Account validates current lifecycle, billing, membership, grant, and security authority and durably commits the lease with its authority tuple, monotonic lease fence, and short expiry. Game Session may CAS its local binding only to provisional `RESUME_PENDING` with the expected resume episode and lease fence; it is not admissible until Account records matching idempotent finalization as `COMMITTED`. A concurrent Account cutoff fences the lease, so a stale local CAS or late finalization fails closed. This is an ordered, idempotent protocol across Account and Game Session stores, not a cross-store atomic transaction.
- `suspended` and effective `canceled`: gameplay admission and reconnect close, connected gameplay authority is revoked, and instances stop accepting players. Game Session may flush one bounded, non-sensitive availability notice before closing sockets, but players do not receive a continuation window. Instance processes then have at most five minutes for internal cleanup before stopping.
- Period-end cancellation remains in its preceding paid state until the effective cancellation time. Immediate cancellation enters the hard state after provider confirmation and the authoritative Account commit.
- Explicit billing-safe and support-safe management remains available so authorized users can resolve billing, inspect permitted status, or export tenant data.

### Delivery And Bounded Enforcement

- Account is the sole authority-generation writer. It commits the authority state/version change, the applicable durable account, tenant, or membership authority-generation advance or realm-grant version advance, and the monotonic outbox event atomically in one database transaction. Redis and other downstream projections then idempotently reflect that committed authority state. A cutoff workflow does not report enforcement complete until the required projection and consumer convergence succeeds. [ADR 0036](./adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md) supersedes timestamp watermark ordering for bulk token-revocation decisions.
- Every authority change that can affect a new gameplay binding advances its existing applicable Account-owned authority generation or grant version and the monotonic outbox event sequence in that same Account transaction. Together, that authority tuple and committed event sequence are the admission cutoff checkpoint; this ADR does not introduce a separate global fence. The Account-owned admission decision/read exposes the exact checkpoint it covers, and Game Session may create a new binding only after confirming that its relevant projection has applied that checkpoint. A missing, stale, gapped, ambiguous, or otherwise unconfirmed projection fails closed before binding creation. This is an Account authority and projection-confirmation protocol, not a claimed cross-store transaction.
- Every new gameplay binding and every reconnect/resume admission must also use an Account-owned exact-binding admission lease. The lease contains the target binding identity, the applicable membership/grant/billing/security authority tuple, the committed outbox cutoff checkpoint, a monotonic `leaseFence`, and a short expiry; grace resume uses the existing `resumeActivationLease` as this contract's exact-resume specialization. Game Session's binding CAS must include the expected old binding generation, exact lease identity, `leaseFence`, and cutoff checkpoint in its predicate and may publish only a non-admissible provisional record until Account durably finalizes the same lease as `COMMITTED`. Account authority advancement and lease finalization serialize through the same Account CAS: if a membership, grant, billing, or other applicable cutoff wins before finalization, it advances the fence, finalization fails, and any provisional local binding is removed or remains blocked; if finalization wins first, that binding commit was valid and the later authority event follows the ordinary bounded active-revocation path. A stale, missing, expired, ambiguous, or unfenced lease never becomes an admissible binding. This lease/fence predicate is the conditional authority checked by the Game Session binding CAS; it is an ordered idempotent protocol across stores, not a claimed cross-store transaction.
- Game Session consumes revocation events durably and idempotently. Events carry a stable ID and monotonic authority version; duplicates and older versions are no-ops, while gaps trigger authoritative reconciliation.
- Game Session maintains bounded active-binding indexes by account, tenant, and private-realm grant scope. Revocation must not rely on Redis wildcard scans.
- If an active-binding index read, write, or repair obligation is unavailable or ambiguous during a cutoff, Game Session must not treat it as empty, use a wildcard scan, or wait for recovery. It establishes a scope-level fail-closed revocation fence at each Game Session front end: new admission and reconnect/resume stop, and local admission/transport controls terminate every locally owned binding in the affected authority scope. Durable repair obligations and authoritative binding records account for bindings not visible through the index; any binding that cannot be positively accounted for remains blocked or terminated until reconciliation proves coverage. This fallback uses the existing termination sub-budget and must complete no later than `T0 + 60 seconds`; index recovery is not a reason to extend the deadline.
- The event is the fast path. From the authoritative Account commit at `T0` to completed socket/admission termination, the end-to-end active-revocation budget is at most 60 seconds. The configured budgets for event delivery, event-gap detection, reconciliation scheduling jitter, bounded retries, authority/projection datastore timeouts, deployment clock-skew uncertainty, and socket/admission termination must be explicit and satisfy `B_event_delivery + B_gap_detection + B_scheduler_jitter + B_retries + B_datastore_timeouts + B_clock_skew + B_termination <= 60 seconds`. No retry or backoff may extend beyond its assigned budget, and the configured sum must be validated before deployment.
- A missed event, detected gap, stale projection, unavailable authority store, unavailable active-binding index, exhausted retry budget, or inability to complete termination is fail-closed: new admission is denied, the affected binding is not allowed to continue gameplay, and local admission/transport controls terminate the socket without waiting for an unbounded dependency recovery. The deadline is measured from `T0` using deployment-synchronized clocks and includes the reserved clock-skew budget. The separate five-minute instance cleanup allowance starts only after active socket/admission termination and is not part of this revocation budget.
- The reconciliation freshness lease is fail-closed: new admission stops when authority freshness is unavailable, and an active binding whose authority cannot be re-established is terminated at the 60-second bound.
- The admission cutoff checkpoint is a pre-binding guard, not a replacement for active-binding convergence. It does not shorten or change the existing event-fast-path and `<=60-second` reconciliation bound for already-active bindings; it only prevents a new binding from being created while authoritative cutoff application is stale or unconfirmed.
- Routine gameplay commands do not call Account or read authority generations. The bounded reconciliation is periodic and batched by active authority scope.

## Consequences

- Security and authorization removal take effect for active gameplay instead of waiting for token expiry or reconnect.
- Temporary payment failure does not destroy a live game community, while `grace` prevents new resource and admission commitments.
- A missed revocation event has a defined maximum exposure rather than an indefinite active-session loophole.
- The 60-second fail-closed reconciliation lease can disconnect players during a prolonged authority/Coordination Redis incident. This is the accepted cost of a real revocation bound; it is less expensive than a per-command authority read.
- Hard billing cutoff may consume up to the existing five-minute internal drain budget, but that window admits no players and grants no continued gameplay.
- The contract adds durable event, authority-generation projection, versioning, active-index, reconciliation, and end-to-end cutoff proof obligations.

## Alternatives Considered

### Check Only At Reconnect Or Token Expiry

This is operationally simple but permits banned accounts, removed members, revoked private-realm users, and hard-blocked tenants to continue indefinitely while their socket remains connected.

### Kick On Every Role Or Billing Change

This gives one mechanical rule but disrupts harmless role updates and live games during recoverable `past_due` and `grace` periods.

### Allow Existing Sessions Through Every Billing State

Warning-only enforcement protects players from owner billing failures but permits unbounded unpaid hosting and makes cancellation ineffective until every player disconnects naturally.

### Per-Command Authority-Generation Checks

Checking Account or Redis before every command gives a tighter revocation observation point, but adds latency and an authority-store availability dependency to the routine gameplay hot path. Durable events plus batched reconciliation provide a bounded compromise.

## Implementation and Proof Obligations

- Implement monotonic membership, grant, account-security, and tenant-billing versions with transactional durable authority-generation advances, durable outbox producers, and idempotent consumers.
- Define, validate, observe, and prove the event-delivery, gap-detection, scheduler-jitter, retry, datastore-timeout, clock-skew, and termination sub-budgets; their configured sum must remain at or below 60 seconds from authoritative Account commit to completed socket/admission termination, including partial-outage and deadline-exhaustion behavior.
- Expose the applicable Account-owned authority tuple and committed outbox checkpoint through the admission decision, track projection application of that checkpoint, and fail closed before creating any new gameplay binding while it is stale, gapped, ambiguous, or unconfirmed.
- Implement the Account-owned exact-binding admission lease and monotonic `leaseFence` for new binding and reconnect/resume admission, include that lease/fence and cutoff checkpoint in the atomic Game Session binding CAS, and keep the local record provisional until Account `COMMITTED` finalization. Prove cutoff-before-finalization rejects admission, finalization-before-cutoff admits only the then-current authority and is later revoked through the bounded event path, and any stale, ambiguous, expired, or uncertain lease state fails closed without leaving an admissible binding.
- Implement Account-owned account, tenant, and membership authority-generation plus realm-grant-version projections with retry, set-if-greater, and cutoff-completion semantics.
- Persist the authority versions required by active gameplay bindings and implement bounded account, tenant, and private-realm indexes for targeted termination.
- Add batched reconciliation, the 60-second freshness lease, event-gap repair, the index-unavailable fail-closed fallback, and bounded telemetry for event lag, projection failures, reconciliation age, and termination outcome.
- Extend the runtime entitlement response with explicit public-join, new-gameplay-binding, and instance/scale flags; correct `past_due` and `grace` handling.
- Prove harmless role refresh, membership/player/grant removal, password reset/security lock/logout-all, each billing state, scheduled and immediate cancellation, duplicate/gapped events, missed-event reconciliation, authority outage, grace-resume activation racing a current Account cutoff, notice/close behavior, reconnect denial, and five-minute instance cleanup.
- Prove routine gameplay commands perform no Account or authority-generation lookup.

## Required Documentation Alignment

- [Session behavior](../system-architecture-session-behavior.md)
- [Authentication and authorization](../system-architecture-authentication.md)
- [Account subscription management](../microservices/account-service/subscription-management.md)
- [Account Service runtime and data](../microservices/account-service/runtime-and-data.md)
- [Game Session runtime and data](../microservices/game-session-service/runtime-and-data.md)
- [Operator journeys](../user-journeys-operators.md)
- [ADR 0028: Differentiated Entitlement Freshness](./adr-0028-differentiated-entitlement-freshness.md)
- [Player access and session tracking](../../project-management/implementation-tracking/player-access-and-session.md)

## Reversibility and Revisit Triggers

Billing-state operation flags and the reconciliation interval are centralized policy and can be tightened without changing authority ownership. Revisit if measured payment recovery or player disruption shows the `grace` matrix is wrong, the 60-second bound causes unacceptable outage amplification, revocation incidents demand a tighter bound, or deployment scale makes batched active-authority reconciliation materially expensive.
