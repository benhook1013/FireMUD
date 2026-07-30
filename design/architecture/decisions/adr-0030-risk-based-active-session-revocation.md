# ADR 0030: Risk-Based Active-Session Revocation

## Status

Accepted

## Implementation Status

No complete producer/consumer, monotonic issuer/membership versions, authority-generation workflow, bounded active-session index, or hard-cutoff proof exists. Current code principally rechecks membership and a limited entitlement response at `PLAY`; authority, grant, and billing changes do not revoke already-connected sessions.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.4` Commerce, subscriptions, purchases, donations, and platform fees
- Affected capabilities: `AA-1.2`, `AA-1.5`, `AA-2.3`, `GR-1.1`, `PO-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SESSION-07`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `SESSION-07`

## Context

Role, membership, private-realm access, account security, and tenant billing can all change while a player is connected, but they do not represent the same risk. Treating every change as an immediate kick disrupts harmless role refresh and temporary payment recovery. Waiting for reconnect or token expiry after authority or security loss permits a player to keep access they no longer possess.

The previous target distinguished soft and hard billing states and required event-driven revocation, but it left new-admission behavior during `grace`, private-realm grant revocation, and the meaning of “immediate” propagation unclear.

### Canonical Authority Tuple

Every applicable revocation event, registry-backed JWT payload, registry record, Account lease, gameplay binding, refresh request, rebind proof, and installation acknowledgement uses the same logical `authorityTuple` and exact field names:

```text
authorityTuple: {
  issuerAuthGeneration,
  accountAuthorityGeneration,
  tenantAuthorityGeneration: { tenantId: generation },
  membershipAuthorityGeneration: { tenantId: generation },
  privateRealmGrantVersions: [
    { tenantId, worldSlug, realmSlug, grantVersion }
  ],
  accountSecurityCutoff: {
    accountAuthorityGeneration,
    outboxStreamKey,
    outboxSequence
  }?,
  tenantBillingCutoff: {
    tenantId: {
      tenantAuthorityGeneration,
      tenantBillingSequence,
      outboxStreamKey,
      outboxSequence
    }
  }?
}
```

`issuerAuthGeneration` and `accountAuthorityGeneration` are positive Account-owned generations. `tenantAuthorityGeneration` and `membershipAuthorityGeneration` are independent maps keyed by exact tenant IDs; each map's applicable keys are determined separately by the token profile and route classification. The closed `billing_safe_tenant` exception can therefore require a membership entry while deliberately omitting the target-tenant generation. Explicitly unscoped artifacts use empty maps. `privateRealmGrantVersions` contains exact `{tenantId, worldSlug, realmSlug, grantVersion}` entries and is empty for public production. `accountSecurityCutoff` and `tenantBillingCutoff` are optional cutoff evidence, not new authority sources; each is omitted when it is not applicable, and a present `tenantBillingCutoff` retains only its exact applicable tenant-ID entry or entries. `membershipVersion` is separate membership projection/version data and must be compared independently from `membershipAuthorityGeneration`; neither field substitutes for the other. A missing applicable field, extra scope, malformed value, or mismatch fails closed.

The tuple is copied without renaming or reinterpretation into every applicable payload, lease, binding, refresh request, rebind proof, and registry record/claim. `issuanceFence` is copied alongside the tuple as the Account composite authority fence captured by the issuance transaction or CAS; it is not a substitute for any tuple member. Account carries that exact fence through every applicable canonical outbox event and logout/revocation evidence record so consumers can reject authority evidence assembled across different Account linearization points.

Admission decisions, exact-binding leases, and gameplay bindings carry a separate canonical `outboxCheckpoints` set with one exact `{outboxStreamKey, outboxSequence}` entry for every applicable scoped outbox stream. This set is admission freshness evidence, not a member of `authorityTuple` or a general JWT/registry claim. Any stream coordinates embedded in cutoff evidence must equal their corresponding set entries.

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
- `grace`: connected sessions and reconnect of the exact same still-resumable gameplay binding continue. A grace reconnect is not authorized by Redis binding presence or a cached billing result: before exact-binding resume, Game Session must obtain an Account-owned `resumeActivationLease` that revalidates the current lifecycle/security state, tenant authority state, `{accountId, tenantId}` membership and gameplay-admission authority, and, for a private realm, the applicable `authorityTuple.privateRealmGrantVersions` entry. If fresh entitlement evaluation alone is unavailable while Account remains able to perform that lease decision, an eligible positive last-known-good entitlement snapshot from ADR 0028 may supply only the entitlement input within its `< 5 minute` continuity window and sequence-safety conditions. It never substitutes for the lease or for current lifecycle, security, membership, grant, or revocation authority; inability to establish the Account lease fails closed. The returned identity, complete authority tuple, issuance fence, tenant, game instance, realm, and character must match the stored resume episode; unavailable, stale, revoked, expired, or mismatched authority fails closed without creating a new binding. First-time public join, first/new gameplay bindings, new instances, scale-out, and quota-increasing operations are denied.
- Grace resume activation uses an Account-owned, exact-binding and resume-episode `resumeActivationLease`. Account validates current lifecycle, billing, membership, grant, and security authority and durably commits the lease with its authority tuple, monotonic lease fence, and short expiry. Game Session may CAS its local binding only to provisional `RESUME_PENDING` with the expected resume episode and lease fence; it is not admissible until Account records matching idempotent finalization as `COMMITTED`. A concurrent Account cutoff fences the lease, so a stale local CAS or late finalization fails closed. This is an ordered, idempotent protocol across Account and Game Session stores, not a cross-store atomic transaction.
- `suspended` and effective `canceled`: gameplay admission and reconnect close, connected gameplay authority is revoked, and instances stop accepting players. Game Session may flush one bounded, non-sensitive availability notice before closing sockets, but players do not receive a continuation window. Instance processes then have at most five minutes for internal cleanup before stopping.
- Period-end cancellation remains in its preceding paid state until the effective cancellation time. Immediate cancellation enters the hard state after provider confirmation and the authoritative Account commit.
- Explicit billing-safe and support-safe management remains available so authorized users can resolve billing, inspect permitted status, or export tenant data.

### Delivery And Bounded Enforcement

- Account is the sole authority writer. One Account database transaction, or one composite Account CAS/fence that atomically covers every applicable member of `authorityTuple`, commits the authority change, applicable account-security or tenant-billing cutoff evidence, applicable private-realm grant version, the applicable `issuanceFence`, and the outbox row. Independent row updates are not an acceptable substitute. Redis and other downstream projections are derived outputs. A cutoff workflow does not report enforcement complete until the required projection and consumer convergence succeeds. [ADR 0036](./adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md) supersedes timestamp watermark ordering for bulk token-revocation decisions.
- The durable revocation outbox stream key is exactly `account:auth-authority:v1:<scopeId>`. The exact `scopeId` is `issuer/<issuerId>`, `account/<accountId>`, `tenant/<tenantId>`, `membership/<accountId>/<tenantId>`, or `grant/<accountId>/<tenantId>/<worldSlug>/<realmSlug>`. Account allocates `outboxSequence` contiguously starting at `1` independently for each exact stream key, in the same transaction/CAS as the authority mutation. `tenantBillingSequence` remains the separate Account billing sequence for the exact tenant and is copied into `tenantBillingCutoff` when applicable.
- Every authority change that can affect a new gameplay binding advances its applicable tuple member and the scoped outbox sequence in the same Account transaction/CAS. The Account-owned admission decision exposes the exact tuple and the complete `outboxCheckpoints` set it covers. Game Session may create a binding only after every checkpoint entry has its exact stream watermark at or beyond `outboxSequence` and every corresponding authority/index projection confirms coverage of that entry. A missing, stale, gapped, ambiguous, or otherwise unconfirmed checkpoint or projection fails closed before binding creation.
- An issuer-generation cutoff is also an active-session revocation authority, not only a token-validation rule. Account advances the issuer generation and emits the committed outbox event with the issuer scope, new generation, `issuanceFence`, and cutoff checkpoint. Game Session owns only the derived consumer-local projection `session:game:auth:issuer-generation:v1:<issuerId>` and applies that event with set-if-greater semantics; it never writes or mutates Account's canonical `session:auth:generation:*` projection. Duplicate or older events are no-ops, while a gap or unavailable, stale, regressed, or ambiguous projection triggers authoritative reconciliation and fail-closed admission. Every active gameplay binding persists the issuer generation captured at admission, and the issuer cutoff is applied to all affected bindings across tenants.
- Every new gameplay binding and every reconnect/resume admission must also use an Account-owned exact-binding admission lease. The lease contains the target binding identity, exact `authorityTuple`, `issuanceFence`, complete `outboxCheckpoints` set, a monotonic `leaseFence`, and a short expiry; grace resume uses the existing `resumeActivationLease` as this contract's exact-resume specialization. Game Session's binding CAS must include the expected old binding generation, exact lease identity, `leaseFence`, `issuanceFence`, and the same tuple/checkpoint set and may publish only a non-admissible provisional record until Account durably finalizes the same lease as `COMMITTED`. Account authority advancement and lease finalization serialize through the same composite Account CAS across every applicable tuple field: if any issuer, account, tenant, membership, grant, billing, or security field advances before finalization, the fence changes, finalization fails, and any provisional local binding is removed or remains blocked. A stale, missing, expired, ambiguous, or unfenced lease never becomes an admissible binding. This lease/fence predicate is the conditional authority checked by the Game Session binding CAS; it is an ordered idempotent protocol across stores, not a claimed cross-store transaction.
- Game Session remains the owner of gameplay binding records, the binding CAS, all bounded active-binding indexes, and socket/admission termination. Account finalization commits only the Account-owned lease, decision, authority snapshot, complete `outboxCheckpoints` set, and idempotency evidence; it does not create, delete, or mutate a Game Session binding or index. If Account reconciliation finds a committed or abandoned lease with no matching admissible Game Session binding, Account emits durable orphan evidence and a fenced cleanup request keyed to the exact request, lease, binding, and fence. Gateway performs any matching edge-token deny/clear cleanup, and Game Session validates the cleanup fence before removing or quarantining the binding and its indexes. Account may reconcile the decision evidence and record cleanup acknowledgements, but local Account cleanup is not physical gameplay cleanup or proof of completion.
- Game Session consumes revocation events durably and idempotently. The canonical Account outbox payload is:

  ```text
  {
    schemaVersion,
    eventId,
    eventType,
    outboxStreamKey,
    outboxSequence,
    sourceScope,
    authorityTuple,
    issuanceFence,
    occurredAt
  }
  ```

  `sourceScope` must decode to the scope encoded by `outboxStreamKey`; a mismatch is malformed evidence. A consumer stores one `lastAppliedOutboxSequence` and the applied event ID/digest for each exact `outboxStreamKey`, never one global watermark. If `outboxSequence <= lastAppliedOutboxSequence`, the matching event ID/digest is a duplicate and is a no-op; the same sequence with a different event ID/digest is a reachable stream conflict, so the scope is quarantined and reconciled fail-closed. If `outboxSequence == lastAppliedOutboxSequence + 1`, the consumer validates the full `authorityTuple` and `issuanceFence` against the matching Account evidence, applies the set-if-greater authority/index transition, and advances the scope watermark even when the authority value is older and therefore a no-op. If `outboxSequence > lastAppliedOutboxSequence + 1`, it is a gap only for that same `outboxStreamKey`: the consumer stops admitting or reconnecting affected bindings, reconciles the exact scope from Account, and advances the watermark only after the Account checkpoint proves the missing range is covered. A first event for a scope is not a false gap; the consumer first obtains the Account checkpoint and initializes that exact stream watermark. An event for an unrelated scope uses another stream key and another watermark and must not create a gap in this scope. Event delivery, projection application, and consumer convergence are part of the cutoff proof.
- An unavailable or timed-out Account reconciliation, outbox store, projection, lease, fence, or binding index returns retryable `AUTH_UNAVAILABLE` while the affected operation remains denied; reachable missing, malformed, stale, regressed, or conflicting evidence is invalid/revoked evidence. No cached tuple or empty index is accepted.
- Game Session maintains bounded active-binding indexes by issuer, account, tenant, caller-bound membership `{accountId, tenantId}`, and private-realm grant scope. A membership cutoff sweeps only the exact membership index rather than scanning every binding for the tenant. Issuer and other scope cutoffs perform the corresponding bounded index sweep, partitioned as needed within the existing revocation budget, and terminate every binding whose captured authority generation or grant version is below the committed cutoff. Revocation must not rely on Redis wildcard scans.
- If an active-binding index read, write, or repair obligation is unavailable or ambiguous during a cutoff, Game Session must not treat it as empty, use a wildcard scan, or wait for recovery. It establishes a scope-level fail-closed revocation fence at each Game Session front end: new admission and reconnect/resume stop, and local admission/transport controls terminate every locally owned binding in the affected authority scope. Durable repair obligations and authoritative binding records account for bindings not visible through the index; any binding that cannot be positively accounted for remains blocked or terminated until reconciliation proves coverage. This fallback uses the existing termination sub-budget and must complete no later than `T0 + 60 seconds`; index recovery is not a reason to extend the deadline.
- The event is the fast path. From the authoritative Account commit at `T0` to completed socket/admission termination, the end-to-end active-revocation budget is at most 60 seconds. The configured budgets for event delivery, event-gap detection, reconciliation scheduling jitter, bounded retries, authority/projection datastore timeouts, deployment clock-skew uncertainty, and socket/admission termination must be explicit and satisfy `B_event_delivery + B_gap_detection + B_scheduler_jitter + B_retries + B_datastore_timeouts + B_clock_skew + B_termination <= 60 seconds`. No retry or backoff may extend beyond its assigned budget, and the configured sum must be validated before deployment.
- A deploy-time capacity invariant must prove issuer-wide cutoff termination, not only per-binding latency. For the configured worst-case issuer scope, let `N_issuer` be the maximum active or provisional bindings covered by one issuer cutoff and let `C_terminate` be the minimum fenced termination capacity under the worst permitted partial-outage conditions. The deployment must prove `B_fixed + ceil(N_issuer / C_terminate) <= 60 seconds`, where `B_fixed = B_event_delivery + B_gap_detection + B_scheduler_jitter + B_retries + B_datastore_timeouts + B_clock_skew + B_termination_setup`. If that invariant cannot be proven for a rollout, the deployment is rejected or backpressured; if current occupancy would exceed the proven bound, new `PLAY`/admission is rejected or backpressured until capacity is restored. The proof must cover all Game Session partitions and Gateway termination work and must not assume an unbounded queue or average-case throughput.
- A missed event, detected gap, stale projection, unavailable active-binding index, exhausted retry budget, or inability to complete termination is fail-closed: new admission is denied, the affected binding is not allowed to continue gameplay, and local admission/transport controls terminate the socket without waiting for an unbounded dependency recovery. An unavailable token-authority read also denies new admission and fresh authority operations; the only already-admitted continuation is the narrow live-coordination and prior-positive-lease exception defined below. The deadline is measured from `T0` using deployment-synchronized clocks and includes the reserved clock-skew budget. The separate five-minute instance cleanup allowance starts only after active socket/admission termination and is not part of this revocation budget.
- The reconciliation freshness lease is fail-closed: new admission stops when authority freshness is unavailable, and an active binding whose authority cannot be re-established is terminated at the 60-second bound.
- The admission cutoff checkpoint is a pre-binding guard, not a replacement for active-binding convergence. It does not shorten or change the existing event-fast-path and `<=60-second` reconciliation bound for already-active bindings; it only prevents a new binding from being created while authoritative cutoff application is stale or unconfirmed.
- Routine gameplay commands do not call Account or read authority generations. The bounded reconciliation is periodic and batched by active authority scope.

### Already-Admitted Gameplay During Token-Authority Outage

- Consistent with [ADR 0037](./adr-0037-fail-closed-token-authority-outages-with-bounded-active-gameplay.md), an already-admitted binding may continue during a token-authority-only outage only when a live current-generation coordination-health check proves the required gameplay queues, locks, leases, session state, and tick coordination are healthy, and the binding still has a prior-positive, unexpired ADR 0030 authority-freshness lease. A stored but not live-validated coordination lease, a valid JWT signature, socket activity, or local process state is not sufficient.
- This exception applies only to ongoing gameplay. New admission, `PLAY`, reconnect, resume, control-plane requests, lease renewal, and any operation that needs fresh authority remain denied while token authority is unavailable. The authority-freshness deadline remains absolute and may not be extended by commands, retries, heartbeats, or local timestamps.
- A cutoff, applicable-stream outbox gap or conflict, unavailable or ambiguous active-binding index, stale or regressed projection, or complete coordination failure is not a token-authority-only outage. These conditions fail closed: affected admission remains denied and affected bindings are revoked or terminated under the bounded cleanup path. Complete coordination failure also halts correctness-sensitive gameplay mutations; it never permits local-only gameplay authority.

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

- Implement monotonic `authorityTuple` issuer/account/tenant/membership fields, `privateRealmGrantVersions`, applicable `accountSecurityCutoff` and `tenantBillingCutoff`, and `issuanceFence` with transactional durable advances, durable outbox and logout/revocation evidence carrying the exact fence, and idempotent consumers.
- Define, validate, observe, and prove the event-delivery, gap-detection, scheduler-jitter, retry, datastore-timeout, clock-skew, and termination sub-budgets; their configured sum must remain at or below 60 seconds from authoritative Account commit to completed socket/admission termination, including partial-outage and deadline-exhaustion behavior.
- Expose the applicable Account-owned authority tuple and complete committed `outboxCheckpoints` set through the admission decision, track every corresponding projection's application of its checkpoint, and fail closed before creating any new gameplay binding while any checkpoint is stale, gapped, ambiguous, or unconfirmed.
- Implement the Account-owned exact-binding admission lease and monotonic `leaseFence` for new binding and reconnect/resume admission, bind the applicable complete `authorityTuple`, `issuanceFence`, separate `membershipVersion`, and complete `outboxCheckpoints` set into that lease, include the lease/fence and checkpoint set in the atomic Game Session binding CAS, and keep the local record provisional until Account `COMMITTED` finalization. Prove cutoff-before-finalization rejects admission, finalization-before-cutoff admits only the then-current authority and is later revoked through the bounded event path, and any stale, ambiguous, expired, or uncertain lease state fails closed without leaving an admissible binding.
- Implement Account-owned issuer, account, tenant, and membership authority-generation plus realm-grant-version projections with retry, set-if-greater, and cutoff-completion semantics.
- Persist the authority versions required by active gameplay bindings and implement bounded issuer, account, tenant, caller-bound membership `{accountId, tenantId}`, and private-realm indexes for targeted termination, including bounded issuer and membership sweeps and the active-binding termination path.
- Prove issuer- and membership-generation cutoff delivery, event projection, duplicate and gap handling, bounded issuer- and membership-index coverage, and termination of every affected active binding within the same fail-closed deadline as other authority cutoffs.
- Add batched reconciliation, the 60-second freshness lease, event-gap repair, the index-unavailable fail-closed fallback, and bounded telemetry for event lag, projection failures, reconciliation age, and termination outcome.
- Prove the deploy-time issuer-wide termination capacity invariant, reject or backpressure deployment/admission when the 60-second bound cannot be proven, and include every Game Session partition plus Gateway termination capacity in that proof.
- Prove that Account finalization persists only lease/decision evidence, that Account reconciliation emits durable orphan cleanup requests, and that Gateway and Game Session perform the matching fenced edge and gameplay-binding cleanup respectively.
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
