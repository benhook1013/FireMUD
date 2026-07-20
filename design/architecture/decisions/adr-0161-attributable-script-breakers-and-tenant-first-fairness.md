# ADR 0161: Attributable Script Breakers and Tenant-First Fairness

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-14`
- Disposition: `revised`
- Primary capability: `AS-1.6` quotas, control plane, audit, and operator overrides
- Affected capabilities: `PO-4.2`, `PO-1.2`, `GR-1.2`, `SF-2.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of false disables, failure attribution, player-triggered errors, capacity protection, priority abuse, and tenant fairness

## Context

Repeated faulty automation can consume sandbox and queue capacity, but a breaker that counts platform outages, quota denial, rollback fencing, or player-controlled invalid input can let an incident or hostile player disable healthy game behavior. Priority tiers also cannot become a bypass around tenant or cluster limits.

## Decision

The live failure breaker counts only handler-attributable deterministic evaluation, sandbox-limit, and authored-output failures. It excludes quota/capacity denial, infrastructure or owner unavailability, rollback/version fencing, expected gameplay precondition rejection, dry-run/test traffic, operator cancellation, and player-controlled invalid input.

Breaker state is keyed to the exact immutable script/plugin version and runtime activation scope. It uses an operator-configured rolling window, minimum eligible sample count, and failure threshold within platform hard bounds. The triggering classified samples, policy version, scope, and transition are durable audit evidence.

A trip changes the activation to `DISABLED_DUE_TO_ERRORS` and blocks new admission. It does not cancel accepted work or reverse committed effects. Recovery requires a new version or an explicit audited reset after validation; there is no automatic half-open loop. Emergency component revocation remains a separate immediate security fence.

Scheduling applies cross-tenant fairness before priority preference. Within a tenant's available share, bounded weighted service favors `high` over `normal` and `background`; no priority bypasses per-script, tenant, sandbox-capacity, or cluster ceilings. Background work may be delayed or dropped under sustained overload according to its declared recovery class, and starvation is measured. Correctness-bearing work must not depend on a best-effort background classification.

Exact breaker state may use high-cardinality identities internally and durably. Exported metrics remain aggregate and bounded under `SCRIPT-15`.

## Consequences

- Failure storms stop consuming new sandbox capacity without letting infrastructure faults or hostile inputs disable healthy scripts.
- Exact version/scope isolation limits blast radius but adds durable accounting and an admission-state lookup/cache.
- Tenant-first fairness prevents one tenant's high-priority work from monopolizing the cluster.
- Background simulation can degrade under sustained pressure and must declare whether delay/drop is acceptable.
- Explicit reset avoids breaker flapping but requires creator/operator intervention or a corrected version.

## Alternatives Considered

### Alert Only and Require Manual Disable

This minimizes false automatic outages but wastes resources and lengthens response during a sustained script failure storm.

### Count Every Non-Success

This is easy to implement but lets quota pressure, infrastructure incidents, expected domain rejection, or player input disable valid automation.

### Strict Global Priority

This maximizes high-priority latency but permits tenant monopoly and indefinite starvation.

## Implementation and Proof Obligations

Current quota and tenant-tier accounting is partial. No complete failure-rate breaker, runtime disable authority, general priority scheduler, enforced cluster ceiling, or bounded-starvation proof exists.

Implementation must provide authoritative breaker state, classification before accounting, durable transition samples, minimum/window/threshold bounds, exact scope/version fencing, safe concurrent trips and resets, tenant-first weighted scheduling, hard ceilings, and starvation metrics. Proof must cover infrastructure and quota exclusion, hostile/player input, dry-run isolation, corrected-version recovery, duplicate outcomes, multi-tenant overload, priority abuse, background degradation, and emergency-revocation independence.

## Reversibility and Revisit Triggers

Thresholds, weights, and activation scope can change within the invariant. Revisit automatic disable if measured false trips remain material, if a throttle-first state is justified, or if workload classes require stronger eventual-execution guarantees.

## Required Documentation Alignment

- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`
- `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`
- `design/architecture/system-architecture-scripting-operations-cookbook.md`
