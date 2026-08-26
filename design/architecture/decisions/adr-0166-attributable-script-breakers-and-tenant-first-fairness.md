# ADR 0166: Attributable Script Breakers and Tenant-First Fairness

## Status

Accepted

## Implementation Status

This decision is not implemented. Attributable failure classification, version-and-scope breaker state, tenant-first fairness, audited reset, and focused proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SCRIPT-14`
- Decision date: 2026-07-20
- Decision key: `SCRIPT-14`
- Primary capability: `AS-1.6` quotas, control plane, audit, and operator overrides
- Affected capabilities: `PO-4.2`, `PO-1.2`, `GR-1.2`, `SF-2.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of false disables, failure attribution, player-triggered errors, capacity protection, priority abuse, and tenant fairness

## Context

Repeated faulty automation can consume sandbox and queue capacity, but a breaker that counts platform outages, quota denial, rollback fencing, or player-controlled invalid input can let an incident or hostile player disable healthy game behavior. Priority tiers also cannot become a bypass around tenant or cluster limits.

## Decision

The live failure breaker counts only handler-attributable deterministic evaluation, sandbox-limit, and authored-output failures for live scripts and plugins. It excludes quota/capacity denial, infrastructure or owner unavailability, rollback/version fencing, expected gameplay precondition rejection, dry-run/test traffic, operator cancellation, and player-controlled invalid input.

Breaker state is keyed to the exact immutable script/plugin version and runtime activation scope. A trip occurs exactly when, within the operator-configured rolling window, the eligible classified sample count is greater than or equal to the configured `minimumSampleCount`, where `minimumSampleCount` has a hard lower bound of `1`, and the count of qualifying handler-attributable failures is divided by a nonzero eligible classified sample count and is greater than or equal to the configured failure-rate threshold; the configuration remains within platform hard bounds. For a plugin, target `breakerState` is separate from lifecycle `pluginState` and is exactly `CLOSED` or `DISABLED_DUE_TO_ERRORS` for the selected immutable `pluginVersionId` and runtime scope. A plugin breaker trip does not mutate `pluginState`, `pluginActivationEpoch`, or `lifecycleRevision`. The triggering classified samples, policy version, scope, and transition are durable audit evidence.

A trip changes the script breaker/runtime status or plugin `breakerState` to `DISABLED_DUE_TO_ERRORS` and blocks new admission. For a resolved plugin handler, the bounded handler audit result is `finalStage=ADMISSION`, `finalOutcome=disabled_due_to_errors`, and `finalReason=failure_rate_breaker`, with the exact breaker state/version/scope retained as audit evidence. It does not cancel accepted work, reverse committed effects, or change plugin lifecycle state. Recovery requires a new version, which starts with `breakerState=CLOSED`, or an explicit audited reset after validation; there is no automatic half-open loop. Emergency component revocation remains a separate immediate security fence.

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

Select and report the required checks and evidence under the shared [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md) workflow; record execution results in PR/CI evidence or the owning implementation tracker rather than in this decision record.

Current quota and tenant-tier accounting is partial. No complete failure-rate breaker, runtime disable authority, general priority scheduler, enforced cluster ceiling, or bounded-starvation proof exists.

Implementation must provide authoritative breaker state, classification before accounting, durable transition samples, minimum/window/threshold bounds, exact scope/version fencing, safe concurrent trips and resets, tenant-first weighted scheduling, hard ceilings, and starvation metrics. For plugins, `GetPluginStatus` and `ListPluginRuntimeEvents` must expose the separate breaker state/reason and retain breaker transitions without rewriting lifecycle state; handler admission and `script_event_audit` must enforce and expose the bounded breaker outcome. Proof must cover infrastructure and quota exclusion, hostile/player input, dry-run isolation, corrected-version recovery, duplicate outcomes, multi-tenant overload, priority abuse, background degradation, and emergency-revocation independence.

## Reversibility and Revisit Triggers

Thresholds, weights, and activation scope can change within the invariant. Revisit automatic disable if measured false trips remain material, if a throttle-first state is justified, or if workload classes require stronger eventual-execution guarantees.

## Required Documentation Alignment

- [design/architecture/system-architecture-scripting-quotas-and-operations.md](../system-architecture-scripting-quotas-and-operations.md)
- [design/architecture/system-architecture-scripting-observability-contract.md](../system-architecture-scripting-observability-contract.md)
- [design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md](../microservices/automation-scripting-service/sandbox-runtime-design.md)
- [design/architecture/system-architecture-scripting-operations-cookbook.md](../system-architecture-scripting-operations-cookbook.md)
