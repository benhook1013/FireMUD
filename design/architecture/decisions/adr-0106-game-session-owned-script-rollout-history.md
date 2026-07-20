# ADR 0106: Game Session-Owned Script Rollout History

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-16`
- Primary capability: `AS-1.6` quotas, readiness, reload, and automation runtime operations
- Affected capabilities: `AR-3.2`, `SF-1.1`, `SF-2.3`, `PO-4.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of pin authority, rollout history, projection correctness, event duplication, operator reads, and retention cost

## Context

Game Session already owns the exact script pin and pin epoch for each game instance. Making Automation & Scripting reconstruct an authoritative rollout history from observed pin state, work-item transitions, and a second rollout event family creates another lifecycle that can disagree with the mutation owner. Event loss, reordering, retention gaps, or projection refresh behavior can then make operator history differ from the pin changes that actually committed.

Rollout volume is control-plane scale. FireMUD does not currently need a second independently retained event-sourced history merely to avoid authoritative reads from Game Session.

## Decision

Game Session owns both the current `(scriptPatchVersion, scriptPinEpoch)` and an append-only history of committed pin, rollback, and repin attempts for each `(tenantId, gameInstanceId)`. A successful mutation atomically commits the new exact pin and epoch plus its history record. The record includes stable `controlPlaneRequestId`, operation kind, previous and resulting exact pin tuples, actor and reason when operator-driven, outcome, and commit time. Repeating the same request identity returns the same result and does not append another logical history entry.

Game Session exposes direct authoritative reads for the current pin and bounded, paginated rollout history. Rollback to a previously used patch remains a new committed epoch and history entry rather than being inferred from version equality. Current-state and history reads come from the same owner that enforces gameplay execution fences.

Automation & Scripting owns tenant patch readiness and only the instance-local observed exact-pin, convergence, and freshness state needed for admission, scheduling, handoff, and diagnostics. Those records are explicitly projections. Automation does not author `PINNED`, `ROLLED_BACK`, or `REPINNED` history from work-item presence, projection refresh, or local guesses, and its reads do not become rollout authority.

Logging & Admin composes Game Session's authoritative current pin and rollout history with Automation's readiness, observed-pin convergence, and freshness state. A discrepancy is presented as convergence or projection lag, not resolved by selecting Automation's history over Game Session.

A transactional outbox notification from Game Session may accelerate Automation refresh and operator updates. Consumers still recover current truth through Game Session reads, and notification loss does not erase rollout history. A mandatory event-sourced Automation rollout-history projection, a duplicate `ScriptPatchInstanceRolloutChanged` family distinct from the committed pin-change record, and independent projection retention, replay, and SLO machinery are deferred until measured query load, availability, or consumer needs justify them.

## Consequences

- Pin mutation, exact epoch, and rollout history cannot disagree across two authorities.
- Operator history remains correct through notification loss or Automation projection rebuild.
- Automation keeps the bounded projection required for safe local admission without owning a duplicate business lifecycle.
- Logging & Admin must compose owner reads and expose convergence differences explicitly.
- Authoritative rollout-history reads depend on Game Session availability, although low-volume notifications and caches may improve presentation latency.
- Future multi-consumer history projections must be reconstructible from Game Session records and remain non-authoritative.

## Alternatives Considered

### Mandatory Event-Sourced Rollout History in Automation

Rejected for the current system. It can decouple operator queries and support additional consumers, but requires a committed producer event contract, durable delivery, replay source, retention alignment, sequencing, rebuild proof, and lag SLO. Those costs do not improve authority while rollout traffic remains small.

### Store Only the Current Pin

Rejected because current state cannot explain who rolled an instance back, distinguish rollback from a same-version idempotent retry, or reconstruct epoch progression during an incident.

### Let Logging & Admin Own Rollout History

Rejected because an operator-facing projection must not become the source of truth for mutations committed by Game Session. Logging & Admin composes and presents owner evidence instead.

### Keep Both `ScriptPatchPinChanged` and a Separate Rollout Event Family

Deferred because the same committed mutation can supply notification and history semantics without two event families whose ordering and retention must agree. Add a distinct derived event only when a concrete consumer requires semantics unavailable from the committed pin record.

## Implementation and Proof Obligations

Game Session must enforce uniqueness for `controlPlaneRequestId`, atomically commit pin/epoch and append-only history, and expose bounded current/history reads with stable ordering and pagination. History records are immutable except for explicitly modeled correction metadata that cannot rewrite the committed pin result. Outbox publication, if used, commits with the same mutation transaction and is idempotent for consumers.

Proof must cover same-request retry; concurrent pin and rollback attempts; repin to the same version with a new epoch; failure before and after database commit; lost, duplicate, and reordered notifications; Automation restart and projection rebuild; direct history reads while Automation is unavailable; disagreement surfaced as convergence lag; pagination and retention; and Logging & Admin composition without a competing state write.

The current Game Session implementation persists a pin and exposes convergence reads, while Automation currently persists synthetic rollout projections and local rollout events derived from observed pin state and work-item transitions. That implementation is not proof of committed Game Session-owned rollout history or event replay. Append-only request-idempotent Game Session history, exact epoch coverage, direct history reads, and removal or demotion of the synthetic rollout lifecycle remain implementation and proof gaps.

## Reversibility and Revisit Triggers

History schema, pagination, retention, notification transport, and admin composition may evolve while Game Session remains the mutation and history authority. Revisit an Automation or shared projection only after measured Game Session read load, required read availability, history volume, or multiple independent consumers justify it. Any projection must rebuild from authoritative Game Session history, report freshness, and remain replaceable without changing rollout truth.
