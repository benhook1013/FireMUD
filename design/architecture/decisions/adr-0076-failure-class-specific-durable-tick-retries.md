# ADR 0076: Failure-Class-Specific Durable Tick Retries

## Status

Accepted

## Implementation Status

Failure-class retry identity and fair scheduler re-entry are target state; durable retry owners, class policy, circuit shedding, quarantine, and focused proof remain incomplete.

## Canonical Design

- [Tick Concepts and Invariants](../system-architecture-tick-concepts-and-invariants.md)
- [Tick Execution Flows](../system-architecture-tick-execution-flows.md)
- [Tick Failure and Operations](../system-architecture-tick-failures-and-operations.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `TICK-12`
- Primary capability: `GR-1.2` tick scheduling, execution, and deterministic command resolution
- Affected capabilities: `PO-2.4`, `PO-4.2`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent retry-identity, fairness, failure-class, and terminal-outcome validation and universal-backoff, unbounded-retry, and immediate-retry alternative analysis
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `TICK-12`

## Context

Tick work can fail before dispatch, while acquiring a lock, during a known dependency outage, or after a domain owner may already have committed a durable effect. Treating all failures as equivalent either burns tick capacity on work that cannot progress or mistakes missing acknowledgements for proof that an effect was not applied.

Retry scheduling must preserve the deterministic fairness contract of ADR 0065 and the durable identity and outcome evidence required by the tick effect ledger. A retry delay is not permission to reorder work, reconstruct it in a private queue, change its request, or invent a terminal result.

## Decision

The executor never spins inside one tick. A failed attempt that remains eligible for automation is scheduled no earlier than a future tick; the executor does not repeatedly acquire the same lock or redispatch the same work while waiting for the failure to clear.

A retry retains the original command or effect identity, immutable request digest, semantic lane, `entity_enqueue_seq`, scheduler priority, and durable owner record. Among scheduling fields, it changes only the persisted future `nextEligibleTick`; that field gates eligibility only and never replaces the original `due_tick_id`. Attempt count, failure class, last outcome, and timing evidence are recorded on that same owner record. When eligible, the work re-enters the deterministic fair scheduler defined by ADR 0065 with its original ordering tuple `(priority, due_tick_id, entity_enqueue_seq, source_kind, commandId_or_effectKey)`, including the preserved `due_tick_id`. It does not enter a retry-private FIFO or gain priority by failing.

Every retryable command or effect family must map observed outcomes into a declared failure class. Each class declaration defines the exact retryable outcomes, deterministic backoff or admission behavior, maximum automated attempts, maximum automated age, and exhaustion disposition. The required classes are:

- **Lock contention.** Only an authoritative pre-dispatch lock-contention outcome is retryable as contention. It uses deterministic capped exponential backoff measured in ticks. Attempt and age bounds terminate automation; they are never bypassed by tight retries.
- **Known dependency outage.** An open circuit, explicit unavailability signal, or admission-shed outcome uses circuit breaking and admission shedding plus delayed fair re-entry. The outage policy does not rapidly consume lock-contention attempts while the dependency is known unavailable. Its own probe or retry cadence, attempt bound, age bound, and exhaustion path are declared separately. If dispatch may have occurred, the outcome is reclassified as ambiguous-after-dispatch.
- **Ambiguous after dispatch.** A timeout, lost acknowledgement, or connection failure after dispatch is not evidence of non-application. Reconciliation queries the durable owner and, where safe, retries using the same `EffectId` and request digest until owner evidence establishes the result. Automated query and retry cadence, attempts, and age remain bounded; exhaustion moves the unresolved item to supported verification or quarantine rather than declaring it unapplied.
- **Stale precondition.** A stale epoch, version, holder, location, or other authoritative precondition is not blindly resent. Re-resolution is allowed only when the feature contract explicitly declares its semantics and safety; it is a declared feature transition rather than mutation of the ordinary retry identity or digest. Otherwise the command reaches an explicit not-applied or failure result. A staged effect reaches `ABANDONED` only when authoritative evidence or declared feature policy proves that non-application is safe.
- **Persistent technical or unclassified failure.** Only outcomes explicitly declared transient are retried automatically under the class's bounded cadence. Persistent technical errors and outcomes that remain unclassified receive bounded automation and then move to `DEAD_LETTER` or quarantine with their identity, digest, history, and owner evidence intact. Exhaustion does not fabricate `ABANDONED`.

An unstaged command may fail explicitly when its declared automation bound is exhausted or its failure is non-retryable. Once an effect is staged, retry exhaustion is only an automation state: the effect remains unresolved until it reaches `APPLIED`, a safe evidence-backed `ABANDONED`, or another verified disposition through the supported owner reconciliation path.

## Consequences

- Retry load consumes bounded future capacity without monopolizing the current tick.
- Stable ordering fields and fair scheduler re-entry prevent failures from gaining priority or starving healthy entities and lanes.
- Known outages shed work and preserve retry budgets instead of amplifying dependency failure.
- Lost acknowledgements converge through stable owner identity rather than duplicate logical effects.
- Each feature and failure class must own explicit retryability, timing, exhaustion, metrics, and player-visible failure semantics.
- Durable retry history, circuit state, dead-letter or quarantine capacity, and supported verification paths add storage and operational complexity.
- Some commands fail rather than being re-resolved when their feature contract cannot prove that refreshed preconditions preserve intent and safety.

## Alternatives Considered

### Universal Exponential Backoff

Rejected because lock contention, known outages, ambiguous dispatch, stale preconditions, and unclassified technical failure require different evidence and exhaustion semantics. One formula can burn attempts during an outage, resend stale intent, or manufacture a false non-application result after ambiguous dispatch.

### Unbounded Retry

Rejected because permanently failing work would consume capacity indefinitely, hide operational incidents, and provide no bounded player or operator outcome. Durable unresolved effects instead escalate to visible dead-letter, quarantine, and verification paths without false terminalization.

### Immediate or In-Tick Retry

Rejected because repeated acquisition or dispatch in one tick can spin, amplify outages and contention, consume the regional budget, and bypass ADR 0065 fairness.

## Implementation and Proof Obligations

Implement one durable retry owner record carrying stable identity, digest, lane, entity sequence, priority, failure class, attempt history, age, and `nextEligibleTick`; class-specific policy declarations; future-tick eligibility; ADR 0065 scheduler re-entry; circuit breaking and admission shedding; owner queries and same-`EffectId` replay; feature-declared stale-precondition handling; and bounded dead-letter, quarantine, verification, metrics, and alert paths.

Prove no in-tick spin; preservation of identity, digest, lane, `entity_enqueue_seq`, priority, and owner record across retry; mutation of only `nextEligibleTick` among scheduling fields; deterministic capped contention backoff; fair progress under retry floods; dependency-outage shedding without rapidly burning contention attempts; lost-acknowledgement convergence with the same `EffectId`; digest-conflict rejection; no blind stale-precondition resend; enforcement of feature-declared re-resolution; attempt and age exhaustion for every class; explicit unstaged-command failure; staged effects remaining unresolved after automation exhaustion; evidence-backed `APPLIED`, safe `ABANDONED`, and verified dispositions; and persistent or unclassified failures reaching `DEAD_LETTER` or quarantine without fabricated abandonment.

The current durable retry owner model, class policies, scheduler integration, operational paths, and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Backoff caps, attempt limits, age limits, circuit thresholds, and admission rates may be calibrated from measured contention, outage, fairness, and recovery evidence without changing identity or terminal-outcome authority. Revisit the failure classes or scheduler integration only when a concrete command family demonstrates that the classification cannot express a safe retry contract, or measured capacity requires a different fair scheduling model with equivalent deterministic replay and starvation proof.
