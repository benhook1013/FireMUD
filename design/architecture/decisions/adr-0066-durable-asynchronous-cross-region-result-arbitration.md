# ADR 0066: Durable Asynchronous Cross-Region Result Arbitration

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-13`
- Primary capability: `GR-1.4` runtime recovery, replay, and reconciliation
- Affected capabilities: `SF-2.3`, `GR-2.1`, `GR-2.2`, `GR-1.2`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent contract validation and synchronous transaction, wall-clock deadline, and terminal-reopening alternative analysis

## Context

Cross-region gameplay cannot make independent region databases and tick timelines behave like one transaction without introducing global locks, distributed transactions, and shared failure. Durable follow-up and result records permit each region to commit independently, but the origin still needs one unambiguous rule for a remote result racing its gameplay timeout.

Target execution must also prove that the intended target still satisfies the action's assumptions. A target epoch and due tick prove timeline placement, not entity identity, ownership, location, or aggregate state.

## Decision

PostgreSQL follow-up and result records are authoritative. Redis markers are bounded latency hints only; their loss, duplication, or delay cannot change whether remote work exists or its outcome.

Regions commit independently. Cross-region actions do not use a cross-region transaction or lock. The origin durably schedules the remote leg, the target commits its own guarded effect, and the target publishes a durable origin-addressed result.

Result admission and timeout arbitration are serialized in one origin coordinator transaction and lock domain:

- a result durably admitted before arbitration wins and is evaluated before timeout;
- if timeout wins, the coordinator reaches its immutable terminal origin outcome; and
- any result admitted after that point is classified and recorded separately as late.

Origin gameplay deadlines are durable origin timeline coordinates consisting of origin region epoch and tick ID. Gameplay deadline progression suspends while the origin is canonically `PAUSED` or `STALLED`. An operational policy may impose a separate maximum real wait and terminalize stranded coordination, but that policy must record its operational reason and must not claim that gameplay tick time advanced.

A terminal origin outcome is immutable. A late result cannot reopen or rewrite it. Features that must respond to a late result create a new linked reconciliation or compensation workflow with its own identity and audit trail.

Every feature payload carries exact target identity and the required ownership, location, and aggregate-version preconditions. The target applies the effect only when its recorded target epoch and those feature-specific preconditions still hold under the current authoritative state and executor fence.

The canonical player result vocabulary is `SUCCESS`, `PARTIAL`, and `FAILED`. The origin derives that result from the durable local, remote, timeout, and any separately linked reconciliation outcomes.

Conserved or paired workflows, including currency, trade, refunds, rewards, and unique external consequences, use an explicit saga/outbox workflow outside the tick loop. They do not rely on default late-result ignore behavior.

## Consequences

- Redis loss can delay cross-region work but cannot erase its durable existence or result.
- A result-timeout race has one durable winner without reopening terminal gameplay history.
- Paused gameplay does not time out merely because wall-clock time passes, while operations may still bound indefinite stranded coordination honestly.
- Cross-region actions remain eventually consistent and may produce explicit `PARTIAL` or `FAILED` player outcomes.
- Exact target preconditions reject effects whose target moved, changed owner, or changed relevant state before execution.
- Follow-up, result, coordinator, late-result, and linked-workflow persistence add database and operational cost.

## Alternatives Considered

### Synchronous Cross-Region Transaction or Lock

Rejected because two-phase commit or global locking couples independent region latency and availability, increases contention, and creates blocking recovery around coordinator or participant failure.

### Wall-Clock Gameplay Deadlines

Rejected because wall-clock expiry would advance gameplay semantics while an origin region is paused or stalled. A separately identified operational maximum real wait may terminalize coordination without pretending that origin tick time advanced.

### Reopen the Terminal Origin Outcome

Rejected because a late target result could rewrite a player-visible conclusion after callers and downstream systems had acted on it. Late reconciliation or compensation instead uses a new linked workflow.

## Implementation and Proof Obligations

Proof must cover durable scheduling without Redis hints; duplicate follow-up, claim, execution, and result delivery; independent region commit; atomic result-versus-timeout races in both orders; paused and stalled deadline suspension; operational maximum-real-wait terminalization; immutable origin outcome; separately recorded late results; linked reconciliation and compensation; stale target epoch, identity, ownership, location, and version rejection; canonical player result derivation; and saga/outbox behavior for conserved or paired consequences.

The current implementation and runtime proof are not claimed to satisfy this decision.

## Reversibility and Revisit Triggers

Deadline values, operational maximum-real-wait policy, and worker capacity may be calibrated without changing arbitration authority. Revisit independent regional commit only if a concrete feature demonstrates that explicit saga/outbox coordination cannot meet its required atomicity. Revisit terminal immutability only with a new externally visible result-versioning contract and migration decision.
