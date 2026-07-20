# ADR 0124: Epoch-Fenced Per-Instance Plugin Activation

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `PLUGIN-01`
- Primary capability: `AS-1.6` quotas, readiness, reload, and automation runtime operations
- Affected capabilities: `AR-3.3`, `SF-1.3`, `AR-1.5`, `GR-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of per-instance activation, trust and policy eligibility, same-version reactivation, displaced work, drain behavior, revocation, final execution fencing, reconciliation, and control-request idempotency

## Context

Linked plugins have an activation lifecycle independent of the base game release. Publication trust and platform acceptance determine whether exact plugin bytes are eligible for activation, but they do not determine whether that plugin may execute in a particular running game. Runtime activation must therefore remain explicit and instance-scoped.

Fencing only by `pluginVersionId` is insufficient. If an instance activates `v1`, switches to `v2`, and later activates `v1` again, delayed work from the first `v1` activation can match the current version even though it belongs to a displaced activation. Disable and revocation have a similar risk when cleanup is asynchronous. Correctness must come from a monotonic activation fence at final execution, not from assuming queues and schedules were purged completely before state changes.

Drain has a different product meaning from immediate disable. It stops new admission while allowing already admitted work a bounded opportunity to complete. That bounded grace period must not weaken forced containment after timeout.

## Decision

### Per-Instance Activation Authority

Automation & Scripting owns plugin activation state at scope `<tenantId, gameInstanceId, pluginId>`. The authoritative state binds the exact active `pluginVersionId`, a monotonically increasing `pluginActivationEpoch`, and the plugin lifecycle state.

Every successful activation or version switch, completed disable, forced drain, same-version reactivation, and revocation advances `pluginActivationEpoch`. Reusing the same `pluginVersionId` never reuses an earlier epoch. This prevents old work from becoming eligible again after a `v1 -> v2 -> v1` sequence.

Activation validates the exact immutable publication and platform-acceptance evidence, granted capabilities, target-runtime compatibility, bindings, and fresh policy evidence before changing runtime state. Marketplace origin, publisher signature, or prior activation does not bypass these checks. Signed and operator-permitted unsigned intake remain governed by ADR 0108; this decision governs runtime activation after intake eligibility exists.

Control-plane mutations use a stable request identity bound to a canonical digest of the complete operation input. Exact replay returns the recorded result. Reuse of the request identity with a changed target, scope, reason, or other bound input is rejected.

### End-to-End Epoch Propagation and Final Fence

Every plugin trigger, work item, durable schedule or timer firing, remote follow-up, staged command, and gameplay command carries `pluginId`, exact `pluginVersionId`, and `pluginActivationEpoch`. Retries and replay retain the original tuple; they do not substitute current activation state.

Game Session maintains a local, versioned projection of the Automation-owned activation state. Projection updates are monotonic by `pluginActivationEpoch`; stale or contradictory updates do not replace newer state. The tick path does not make synchronous Automation or policy calls.

Lifecycle transitions install this final-execution fence through a required idempotent control-plane step, not only an eventually delivered notification. Game Session durably acknowledges the exact new epoch and state before Automation admits work under a new activation, reports disable or revocation containment complete, or completes a forced drain. If the fence cannot be installed, activation remains non-admitting and a containment transition remains fail closed and incomplete until retry succeeds. Event or cache propagation may accelerate reads but is not the transition barrier.

Before final gameplay execution, Game Session compares the command's plugin version and activation epoch with its local projection and verifies that the projected lifecycle state permits that work to finish. A missing, stale, displaced, disabled, or revoked projection fails closed. Version equality without epoch equality is insufficient. Rejected work records a bounded diagnostic outcome and cannot mutate gameplay.

### Disable, Drain, Revocation, and Cleanup

Disable and revocation first stop new admission, advance the activation epoch into a non-executable state, and durably install that fence at Game Session before reporting containment complete. Previously admitted work becomes stale when that final fence is installed. Schedule, Automation-work, follow-up, and Game Session queue cleanup proceeds asynchronously and is not the correctness barrier.

Drain stops new admission under the current epoch and allows work already admitted under that epoch to finish within explicit bounded limits. When the drain completes, the terminal transition advances the epoch and disables execution. If the drain exceeds its bound or policy requires immediate containment, the forced transition advances the epoch, rejects remaining old work, and lets cleanup continue asynchronously.

Activation, switching, disable, drain completion, forced drain, reactivation, and revocation reconcile plugin-owned schedules and pending work. Reconciliation may lag or retry, but no displaced work can pass the version-and-epoch fence after its state ceases to permit execution.

## Consequences

- Plugin activation remains isolated per running game rather than becoming tenant-global.
- A monotonic epoch closes same-version ABA and delayed-work resurrection risks.
- Game Session can enforce final safety without a synchronous dependency in the tick path.
- Required lifecycle-time fence installation closes the permissive projection-lag window without adding a per-command network dependency.
- Immediate containment does not wait for queue, timer, follow-up, or work-item cleanup.
- Graceful drain retains bounded completion semantics while forced timeout remains fail-closed.
- Activation state and projection propagation add durable fields, transition history, reconciliation, and convergence monitoring to every plugin runtime path.
- Trust intake and runtime activation remain separate authorities with separate evidence.

## Alternatives Considered

### Version-Only Per-Instance Fencing

Carry `pluginVersionId` through work and reject only when it differs from the active version. Rejected because same-version reactivation lets delayed work from an older activation match again.

### Materialize Every Plugin into the Game Release

Import all plugin behavior into Draft and use only the script-patch pin and epoch lifecycle. This is the strongest simpler alternative because it removes the separate plugin activation authority and reuses one existing fence. It is not selected because it loses independent per-instance activation, rapid plugin-specific containment, immutable upstream package identity, and plugin-specific update or rollback.

### Synchronous Final Policy and Activation Lookup

Call Automation or Game Design from the tick path before executing every plugin command. Rejected because availability and latency of control-plane services would enter the gameplay hot path. A local monotonic projection provides the required fence without synchronous calls.

### Cleanup Before State Transition

Require all queues, work items, schedules, and follow-ups to be purged before disable or revocation becomes effective. Rejected because partial cleanup or an unavailable subsystem would delay security containment. Epoch advancement is the correctness boundary; cleanup is bounded asynchronous convergence.

## Implementation and Proof Obligations

The current implementation has instance-scoped plugin state, exact publication and compatibility preflight, policy reconciliation, ingress version checks, plugin identity on durable work and commands, schedule filtering, and explicit work/queue cleanup surfaces. It does not yet persist or propagate `pluginActivationEpoch`, enforce plugin activation state at Game Session final execution, automatically coordinate every lifecycle cleanup path, or bind control request identity to a canonical operation digest. This decision records the target contract and does not claim those gaps are resolved.

Proof must cover first activation, version switch, immediate disable, ordinary bounded drain, drain timeout, signer or package revocation, policy loss, same-version reactivation, and the `v1 -> v2 -> v1` ABA sequence. At every stage it must cover triggers, queued and executing Automation work, durable schedules, remote follow-ups, staged Game Session commands, retries, replay, lifecycle fence-install failure before and after Game Session commit, late projection delivery, duplicate projection delivery, Game Session restart, and final gameplay mutation.

Control-plane proof must cover exact request replay, concurrent activation attempts, changed-input request-identity reuse, failed preflight without epoch advancement, required Game Session fence acknowledgement before admission or containment completion, and durable transition history. Projection proof must demonstrate monotonic application, fail-closed missing or stale state, notification lag without permissive execution after an acknowledged transition, and no synchronous tick-path lookup. Cleanup proof must demonstrate that displaced work remains harmless even while cancellation, tombstoning, or purge is incomplete.

## Reversibility and Revisit Triggers

Projection transport, drain limits, lifecycle state names, and cleanup orchestration may evolve while preserving per-instance authority, monotonic activation epochs, digest-bound control requests, and Game Session final fencing. Revisit the separate lifecycle if linked plugins are rarely used and Draft materialization would meet product needs. Removing the activation epoch or permitting execution from a missing or stale projection requires a new decision.

## Required Documentation Alignment

- `design/architecture/system-architecture-scripting-normative-contract-tables.md`
- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-control-plane-api.md`
