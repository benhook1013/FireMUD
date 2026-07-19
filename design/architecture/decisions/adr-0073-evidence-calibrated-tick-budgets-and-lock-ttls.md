# ADR 0073: Evidence-Calibrated Tick Budgets and Lock TTLs

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-08`
- Primary capability: `SF-1.4` configurable runtime safety limits
- Affected capabilities: `GR-1.2`, `GR-1.3`, `PO-4.2`, `AR-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent timing-contract validation and permanent-formula alternative analysis

## Context

Tick cadence, execution budget, and lock lifetime are related but serve different purposes. Cadence is player-visible gameplay timing. The execution budget is an operational capacity target. Lock TTL bounds coordination liveness, duplicate attempts, and recovery delay. One permanent arithmetic relationship cannot prove that all three suit an environment's workload, runtime pauses, owner latency, and recovery objectives.

The existing formulas provide safe initial values, but their constants are not production evidence.

## Decision

The current shared formulas remain bootstrap defaults only:

- `tick_budget_ms = tick_interval_ms * 0.8`
- `lock_ttl_ms = clamp(tick_budget_ms * 8, 500, 5_000)`

All consumers use the shared helper and canonical resolved settings. Services must not define private derivations, multipliers, clamps, or fallback formulas.

`tick_interval_ms` is gameplay cadence. It is configurable only at the declared game and operator configuration levels, remains subject to operator and platform caps, and is fixed throughout one live `regionEpoch`.

A cadence change:

1. pauses the affected region;
2. advances its epoch;
3. reconstructs only timers permitted to survive from their durable intent; and
4. re-derives new-epoch ordering without rescaling their absolute due intent.

Old volatile timer entries are not reinterpreted under the new epoch. An absolute due instant remains the same unless the owning feature's explicit semantics require a new duration calculation.

`tick_budget_ms` and `lock_ttl_ms` are operator safety settings within platform hard bounds. Production values are calibrated from measured evidence including:

- p95 and p99 tick execution time;
- participating RPC latency and error behavior;
- garbage-collection, scheduler, and runtime pause distributions;
- final cleanup lag;
- the declared takeover and recovery objective; and
- representative load and fault-injection tests.

Tenant or game configuration cannot override these safety values unless the individual setting is explicitly declared eligible at that level, and any eligible override remains constrained by operator caps and platform hard bounds.

Correctness does not depend on a lock living long enough for an attempt to finish. It comes from current Redis lease possession, the durable `executorFence`, exact owner preconditions, and durable idempotency guards. Lock TTL controls liveness, duplicate attempt frequency, contention duration, and recovery time.

Health reporting separates:

- cadence and execution-budget pressure;
- lock-expiry and renewal risk;
- durable commit progress;
- coordination cleanup lag; and
- command, effect, timer, retry, and recovery backlog.

One derived ratio must not conceal a failure in another dimension.

## Consequences

- Gameplay cadence remains explicit and deterministic within an epoch.
- Operators can tune safety and recovery behavior from deployment evidence without introducing service-specific semantics.
- Too-short TTLs are visible as expiry and duplicate-attempt risk; too-long TTLs are visible as takeover and contention cost.
- Cadence transitions require a controlled pause, epoch change, and durable timer reconstruction.
- Production acceptance gains load, pause, latency, cleanup, and fault-test overhead.
- Resolving calibrated settings and comparing existing timing signals adds no material per-action processing cost.

## Alternatives Considered

### Permanent Cadence-Derived Formulas

Rejected as the production contract because fixed `0.8`, `8x`, and clamp constants are not evidence that a deployment can meet its execution, pause, downstream-latency, cleanup, or recovery objectives. They remain useful shared bootstrap defaults.

### Service-Local Timing Formulas

Rejected because different consumers could disagree about lock validity, safety margin, health, and overload behavior for the same regional timeline.

### In-Place Cadence Changes

Rejected because existing due-tick ordering and replay identity would be reinterpreted inside one epoch.

## Implementation and Proof Obligations

Implement one canonical resolver with platform hard bounds, operator safety configuration, declared lower-level eligibility, provenance, and shared bootstrap defaults. Expose the separate health dimensions and the evidence used to accept production values.

Prove default and configured resolution; cap enforcement; rejection of ineligible tenant/game overrides; lease expiry during execution; durable-fence rejection of stale attempts; duplicate idempotent replay; GC/runtime pauses; slow and failed participant RPCs; cleanup lag; takeover timing; backlog pressure; cadence-change pause and epoch fencing; durable timer reconstruction; preserved absolute due intent; and derived new-epoch ordering.

The current implementation, environment calibration, and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Bootstrap constants, calibrated values, hard bounds, and declared eligibility may evolve from measured evidence without changing authority or fencing. Revisit the separation only if production evidence demonstrates that one shared derivation reliably satisfies cadence, execution, lock-liveness, and recovery requirements across every supported deployment class.
