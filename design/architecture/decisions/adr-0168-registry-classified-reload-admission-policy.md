# ADR 0168: Registry-Classified Reload Admission Policy

## Status

Accepted

Supersedes [ADR 0003](./adr-0003-reload-backpressure-and-retry-contract.md).

## Decision Record

- Decision date: 2026-07-21
- Decision key: `AUTO-03`
- Disposition: `revised`
- Primary capability: `AS-1.6` reload admission and runtime convergence
- Affected capabilities: `AS-1.1`, `AS-1.4`, `AS-1.5`, `GR-1.2`, `PO-2.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of post-pin convergence, stale interactive intent, retry storms, timer backfill, transient-denial idempotency, and current implementation reality

## Context

After Game Session commits a new script pin, Automation must load the exact graph and reconcile plugin/schedule-derived state before admitting work. ADR 0003 correctly requires explicit backpressure but assumes tenant-level validation and `onLoad` occur inside reload, and it names loosely selected external events for retry without a machine-readable policy. Current ingress permanently memoizes denials, has no `RELOADING` admission state or `retryAfterMs`, and producers do not implement the promised retry behavior.

## Decision

Every runtime scope enters a fail-closed post-pin barrier for the exact `(scriptPatchVersion, scriptPinEpoch)`. New script event and timer admission remains blocked while the graph, plugin activation state, and schedules reconcile. Success advances to `IDLE`; failure remains `FAILED` until repair or an explicit valid repin. Automation never falls back to the prior locally loaded graph after the authoritative pin moves.

Each event-registry entry declares exactly one reload admission policy:

- `REJECT_VISIBLE`: reject current intent explicitly with bounded reload context. Interactive events such as `onCommand` use this by default; the platform does not execute stale player intent later automatically.
- `DURABLE_RETRY`: the owning producer retains a durable event obligation and retries the same parent event identity with bounded expiry, elapsed time, backoff, and jitter. This is allowed only for event families whose semantics require preservation.
- `SKIP_RECONCILE`: record the skip and rely on the event family's declared reconciliation/catch-up behavior. Best-effort timers and ambient reactions use this rather than building an unbounded backlog.

Correctness-bearing durable timers retain their separate scheduler/timer contract. This decision does not convert them into best-effort work or create a general timer backfill rule.

Reload backpressure is transient and does not permanently consume the logical parent event identity. The service records append-only admission attempts or permits a monotonic `BACKPRESSURED -> ADMITTED` logical transition while the same digest, version/fence, producer policy, and expiry remain valid. Changed-input reuse remains an idempotency conflict under ADR 0167.

Responses include the bounded admission outcome and `retryAfterMs` where producer retry is allowed. Metrics and audit distinguish visible rejection, durable retry scheduling/exhaustion, best-effort skip, reconciliation failure, and recovery.

## Consequences

- A partial release never admits work against the wrong graph or schedule set.
- Interactive commands fail visibly during the short barrier instead of executing unexpectedly after their context changes.
- Only event families that justify preservation pay for durable retry machinery.
- Registry metadata, producer behavior, attempt auditing, expiry, and policy-specific proof add upkeep.
- Current denial memoization, proto, reload state, scheduler admission, and producer handling require substantial implementation convergence.

## Alternatives Considered

### Retry Every External Event

This appears reliable but can execute stale commands, movement, or spawn reactions after world context changes and can create retry storms during failed reloads.

### Durably Queue Everything

This preserves input but creates a general backlog system with TTL, fairness, version-remap, dead-letter, and stale-intent semantics. It is unnecessary for best-effort events.

### Drop Everything During Reload

This is simple but loses correctness-bearing producer events and hides player-visible rejection unless every producer implements a separate pause.

## Implementation and Proof Obligations

Implementation must expose `RELOADING`/`FAILED` admission state and `retryAfterMs`, add registry policy validation, separate transient attempts from final logical admission, stop schedulers before minting displaced work, and make every producer implement only its declared behavior. Proof must cover each policy, long/failed reloads, same-ID retry, changed payload conflict, retry expiry, restart during reconciliation, scheduler races, timer continuity/catch-up, no old-graph fallback, and bounded metrics/audit.

## Reversibility and Revisit Triggers

Individual events may change policy through a versioned registry update with owner review. Revisit the three-policy model only if measured usage reveals another semantically distinct delivery guarantee rather than a tuning variation.

