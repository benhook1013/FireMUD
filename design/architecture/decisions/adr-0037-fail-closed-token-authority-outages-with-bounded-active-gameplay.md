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

- `AUTH_UNAVAILABLE` covers any registry, auth-generation, authority lease or lease-fence, binding or binding-index, token-identity fence, non-entitlement Account evidence, lease-commit, or token-issuance dependency that is unreachable or times out. It maps to HTTP `503` or the protocol-equivalent unavailable status. Clients retain local authentication state and retry with bounded backoff; this outcome does not assert that the token was revoked. The failed operation remains denied, and no cached authority may authorize it.
- `ENTITLEMENT_UNAVAILABLE` is returned when the entitlement dependency alone is unreachable or times out and the bounded ADR 0028 same-binding continuity exception cannot supply that entitlement input. It is a retryable HTTP `503` or protocol-equivalent unavailable result, does not log the client out or assert revocation, and denies the affected entitlement-dependent operation. It must not be used for a failed lease, binding, token-identity fence, non-entitlement authority read, lease commit, or token issuance.
- A completed authority read or reconciliation that is reachable but returns missing, expired, deleted, malformed, or generation-mismatched registry evidence follows ADR 0035's canonical invalid-token or revoked result and requires fresh authentication. It must never be relabeled as `AUTH_UNAVAILABLE` merely because the evidence is unusable.
- A completed Coordination Redis reset that removed issued-token records is reachable missing state, not an availability exception. Reauthentication/reissuance is required.

### Fail-Closed Operations

- For registry-backed JWT profiles (`control-ui`, `player-bootstrap`, and receiver-specific private player-delegation), Account exposes no JWT unless the profile's registry record and current generation state are established. Login issuance, bootstrap issuance, token refresh, and rotation therefore fail closed when registration or authority cannot complete: an unreachable or timed-out dependency is `AUTH_UNAVAILABLE`, while reachable malformed or mismatched authority evidence uses the applicable canonical invalid/revoked result and never produces a JWT. The ADR 0029 `gameplay-connect` profile is the explicit exception: it is not registry-backed and is governed by its dedicated issuance/replay record and Gateway replay fence; it carries no `authorityTuple`, `tokenGeneration`, or `issuanceFence`.
- Every protected control-plane request fails closed when its issued-token or applicable generation state cannot be read. Admin, support, billing, payment, and other sensitive operations receive no stale-authority exception.
- New gameplay login, join, `PLAY`, reconnect/rebind, and other admission transitions fail closed whenever their required token, membership, generation, or gameplay-binding authority cannot be established.

### Separate Coordination And Authority Leases

`coordinationHealthLease` and the [ADR 0030](./adr-0030-risk-based-active-session-revocation.md) authority-freshness lease are different authorities and different records:

- The active Game Session coordination controller is the issuer of `coordinationHealthLease`, fenced by the current coordination generation, and issues it for one gameplay binding only after proving that the queues, locks, leases, session state, and tick coordination required by that binding are healthy. Its authoritative current coordination-health record carries the current `coordinationHealthLeaseFence`; a presented lease is valid only when its binding, generation, issuer, expiry, and fence exactly equal that current record during validation. A newer issuance or renewal advances the current record fence and invalidates every older lease even if its stored expiry has not elapsed. Local process state, socket activity, and JWT validity cannot issue or renew it, and Account is never an issuer for this health lease.
- Account issues and renews the ADR 0030 authority-freshness lease only after authoritative issued-token, account, tenant, membership, grant, and cutoff-checkpoint validation. Its authoritative current binding lease record carries the current `authorityLeaseFence`; a presented lease is valid only when its binding, authority snapshot, issuer, expiry, and fence exactly equal that current record during validation. A newer issuance or renewal advances the current record fence and invalidates every older lease even if its stored expiry has not elapsed. The lease contains the applicable authority tuple, Account checkpoint, binding identity, issuer, absolute `authorityLeaseExpiresAt`, and its own fence. It proves prior positive token authority; it does not prove Redis coordination health.
- The two leases use distinct issuer identities, storage fields, fence domains, renewal endpoints, current-fence records, and validation rules. `coordinationHealthLeaseFence` and `authorityLeaseFence` are never compared across domains. Neither lease may satisfy the other lease's predicate, and no combined boolean or cached timestamp may replace either record. Renewal of one updates only its own current record, expiry, and fence; it never changes, renews, or recreates the other lease.

Account authority-freshness lease changes use an ordered, Account-owned event and Game Session checkpoint protocol:

- Account emits exactly one `AUTHORITY_LEASE_RENEWED` or `AUTHORITY_LEASE_REVOKED` event for each committed authority-lease-fence change on the applicable exact authority stream. Each event carries the exact binding identity, complete authority snapshot, Account checkpoint, `authorityLeaseFence`, lease state, absolute `authorityLeaseExpiresAt` when present, `outboxStreamKey`, `outboxSequence`, event ID, and digest. Account commits the current lease record and its event in one transaction or composite CAS/fence. The fence strictly advances in Account order, and the event's outbox sequence is contiguous within that exact stream; a renewal installs the new positive lease and deadline, while revocation installs a terminal revoked state and no usable deadline.
- Game Session persists one durable per-binding `authorityLeaseCheckpoint` containing the last applied `authorityLeaseFence`, lease state, absolute deadline when present, exact source event ID/digest, and the matching Account stream checkpoint. It advances this checkpoint only after exact event-digest, binding, authority, and source-checkpoint validation. Bootstrap or reconciliation obtains the current lease record and its source event evidence from one Account snapshot and installs that checkpoint atomically; it never constructs a checkpoint from a delivered event alone.
- A received or reconciled Account fence greater than the cached or presented fence immediately invalidates that cached lease, even when its stored deadline has not elapsed. A same-fence duplicate is a no-op only with exact event and checkpoint evidence. A missing or out-of-order fence, outbox gap, conflicting duplicate, unavailable event stream, or unavailable Account checkpoint fails closed: Game Session denies new admission, reconnect/resume, lease renewal, and any replacement of the cached lease, and it terminates affected gameplay when the current coordination/authority predicates cannot be positively established. It must not skip the gap or treat an unavailable checkpoint as proof that the cached lease remains current.
- The race rules are canonical. If Account commits a renewal before Game Session observes it, the old lease remains usable only under the existing bounded-outage exception and only through its already stored deadline; observing the newer renewal fence invalidates the old lease and permits the new deadline only after exact event/checkpoint installation. If a revocation and renewal race, Account serialization orders them by fence and outbox sequence; a stale renewal cannot restore a revoked fence, and any observed revocation invalidates the old lease immediately. If authority reads or event delivery become unavailable before either event is observed, Game Session may preserve the stored deadline only while the independent live coordination-health signal remains positive and no conflicting Account fence/change has been observed; it may not rewrite or extend that deadline. At the deadline, or immediately on an observed conflicting fence or failed coordination-health predicate, gameplay fails closed.

### Clock Basis And Deadline Comparison

The deployment-synchronized clock basis for the ADR 0030 authority-freshness deadline is UTC epoch milliseconds from the deployment-approved synchronized wall clock. Deployment validation must establish that every Account and Game Session clock used for this contract stays within ADR 0030's configured `B_clock_skew` bound, expressed in the same units as the deadline; an unavailable or out-of-bound clock fails closed. Deployment must reject any configuration unless `0 <= B_clock_skew < 60000 ms`. Account is the authoritative stamping clock: a successful renewal stores `authorityLeaseExpiresAt = accountTrustedNowMs + (60000 ms - B_clock_skew)` as an absolute deadline. Game Session compares that deadline using its deployment-synchronized wall clock, not Redis time, JWT time claims, process uptime, a monotonic elapsed-time estimate, socket activity, or a local heartbeat, and treats it as expired when `gameSessionTrustedNowMs + B_clock_skew >= authorityLeaseExpiresAt`. Reserving `B_clock_skew` in the issued duration means opposite `+/- B_clock_skew` clock offsets cannot extend the real-time authority beyond 60 seconds from the authoritative renewal; the allowance is not a post-expiry grace period.

### Already-Admitted Gameplay

- Ordinary gameplay commands do not consult the issued-token registry or auth generations per command.
- The token-authority read and the coordination-health read are separate typed operations and dependency boundaries even when they use the same Redis deployment or role. A token-authority-only outage is recognized only when the authority operation times out or is unreachable while an independent live controller health operation succeeds for the current coordination generation and positively verifies the required queue, lock, lease, session, and tick-coordination sentinel state. A transport, role, or deployment failure that prevents that independent health operation is a complete coordination outage, not a token-only outage.
- A token-authority-only outage is recognized only when the binding carries both a current positive `coordinationHealthLease` and a still-valid prior-positive ADR 0030 authority-freshness lease. At the classification decision, Game Session must validate the coordination lease through a live current-generation health read or successfully renew it through the coordination controller; an unexpired stored lease snapshot alone is insufficient. The coordination signal must identify the coordination generation and attest that the gameplay queues, locks, leases, session state, and tick coordination required by the binding are currently healthy. An explicitly detected coordination failure takes precedence immediately even when the stored lease expiry has not elapsed. A missing, expired, conflicting, ambiguous, or non-renewable health signal fails closed and is treated as inability to establish the complete coordination plane; local process state or a successful token signature is not a positive health signal.
- If token-authority access alone is unavailable while the positive coordination-health signal remains current, an already-admitted binding may continue only through the last successfully renewed ADR 0030 authority-freshness lease. A successful authoritative renewal stores the absolute `authorityLeaseExpiresAt = accountTrustedNowMs + (60000 ms - B_clock_skew)` deadline into the binding; the deadline is measured from that renewal, not from the last command, socket activity, retry, reconnect attempt, or local heartbeat. No local operation may rewrite, postpone, or recreate that deadline. A newer Account issuance or ordered lease event invalidates the older lease even before its stored expiry, and a revocation event invalidates it immediately; the lease is never extended without a new authoritative renewal. New admission remains closed, and an unresolved binding terminates at its stored absolute deadline.
- A still-valid bounded lease is prior positive authority, not permission to infer or recreate authority from arbitrary local JWT claims or process memory.
- If the complete Coordination Redis role is unavailable, Game Session does not execute gameplay mutations whose queues, locks, leases, session state, or tick coordination cannot be established. Transport/socket recovery may follow the existing bounded recovery and close contracts, but it does not authorize local-only gameplay processing.

## Consequences

- Control-plane and admission availability depend on Coordination Redis token authority, but outage handling never turns cryptographic validation alone into authorization.
- Frontends can distinguish a retryable infrastructure incident from logout, password reset, ban, or another real revocation.
- A registry-only incident does not add a lookup to gameplay commands and does not instantly eject established players; unresolved active authority remains bounded by the absolute `authorityLeaseExpiresAt` set by the last authoritative renewal, at no more than 60 seconds.
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
- Prove Account never exposes a registry-backed JWT whose registry/generation establishment failed, while preserving the ADR 0029 non-registry-backed `gameplay-connect` issuance/replay exception and its absence of `authorityTuple`, `tokenGeneration`, and `issuanceFence`.
- Prove protected control-plane, sensitive mutations, admission, reconnect, and refresh fail closed without stale authority.
- Prove registry-only outages add no per-command gameplay lookup, never renew or reset the authority lease from commands, socket activity, retries, reconnect attempts, or local heartbeats, and terminate unresolved bindings at the absolute deadline set by the last authoritative renewal and no later than 60 seconds.
- Prove the positive `coordinationHealthLease` identifies the current coordination generation and required gameplay coordination health, is live-read or renewed at the classification decision, and its presented fence exactly equals the current coordination-health record fence; a newer issuance invalidates an older lease even while unexpired. It cannot be reused from an unexpired stored snapshot after coordination failure. Missing, expired, conflicting, ambiguous, or non-renewable health evidence and explicit current coordination failure must fail closed rather than being classified as a token-authority-only outage.
- Prove `coordinationHealthLease` and the ADR 0030 authority-freshness lease use distinct issuers, current-fence records, binding fields, fence domains, renewal paths, expiry deadlines, and failure classifications; each presented fence must exactly equal its own current issuer/record fence, a newer issuance invalidates an older lease, and neither can be replayed as the other or renewed by the other.
- Prove deployment rejects every `B_clock_skew` outside `0 <= B_clock_skew < 60000 ms` and prove the ordered Account `AUTHORITY_LEASE_RENEWED`/`AUTHORITY_LEASE_REVOKED` events, durable Game Session `authorityLeaseCheckpoint`, exact fence and source evidence, gap/unavailability fail-closed behavior, and renewal/revocation/outage races. In particular, prove that a stored deadline is preserved only when no conflicting Account change has been observed and is never locally extended.
- Prove the stable client mapping for `AUTH_UNAVAILABLE` across registry, lease, binding, token-identity fence, non-entitlement evidence, lease commit, and token issuance failures, and the distinct retryable `ENTITLEMENT_UNAVAILABLE` behavior for entitlement-only failures outside the bounded same-binding exception.
- Prove complete Coordination Redis loss halts correctness-sensitive gameplay processing and uses existing bounded recovery/close behavior without inventing local authority.

## Reversibility and Revisit Triggers

Error classification and the authority lease are bounded contracts rather than persistent data formats. Revisit if token authority moves to a separately available store, measured control-plane availability cannot meet SLOs, or a route-specific read-only stale-authority model has a demonstrated product need and explicit security analysis.
