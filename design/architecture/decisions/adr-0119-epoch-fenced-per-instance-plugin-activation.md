# ADR 0119: Epoch-Fenced Per-Instance Plugin Activation

## Status

Accepted

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `PLUGIN-01`
- Decision date: 2026-07-20
- Decision key: `PLUGIN-01`
- Primary capability: `AS-1.6` quotas, readiness, reload, and automation runtime operations
- Affected capabilities: `AR-3.3`, `SF-1.3`, `AR-1.5`, `GR-1.4`
- Decision owner: FireMUD human product and architecture owner

## Context

Linked plugins have an activation lifecycle independent of the base game release. Publication trust and platform acceptance determine whether exact plugin bytes are eligible for activation, but they do not determine whether a plugin may execute in a particular running game. Runtime activation must remain explicit and instance-scoped.

Fencing only by `pluginVersionId` is insufficient. If an instance activates `v1`, switches to `v2`, and later activates `v1` again, delayed work from the first `v1` activation can match the current version even though it belongs to a displaced activation. Disable and revocation have the same risk when cleanup is asynchronous. Correctness must come from a monotonic activation fence at final execution, not from assuming queues and schedules were fully purged before state changes.

Drain has a different meaning from immediate disable: it stops new admission while allowing already admitted work a bounded opportunity to complete. That grace period must not weaken forced containment after timeout.

## Decision

Automation & Scripting owns plugin activation state at `(tenantId, gameInstanceId, pluginId)`. The authoritative state binds the exact active `pluginVersionId`, a monotonically increasing `pluginActivationEpoch`, and lifecycle state.

`pluginActivationEpoch` starts at `0` for a runtime row with no admitted activation. The first successful activation advances it to `1`. A successful version switch, completed disable of an active/current lifecycle, final drain or forced drain, same-version reactivation after invalidation, or revocation of an active/current lifecycle advances it exactly once. Entering `DRAINING`, never-active disable, failed operations, no-ops, and exact retries do not advance it. Reusing a `pluginVersionId` never reuses an earlier epoch. Activation validates immutable publication and platform-acceptance evidence, granted capabilities, target-runtime compatibility, bindings, and fresh policy evidence before changing runtime state. Runtime activation does not replace the separate publication and trust controls.

Control-plane mutations use a stable request identity bound to a canonical digest of the complete operation input. Exact replay returns the recorded result. Reusing the request identity with a changed target, scope, reason, or other bound input is rejected.

Every plugin trigger, work item, durable schedule or timer firing, remote follow-up, staged command, and gameplay command carries `pluginId`, exact `pluginVersionId`, and `pluginActivationEpoch`. Game Session maintains a local, versioned projection of Automation-owned activation state. Projection updates are monotonic by epoch, and the tick path does not make synchronous Automation, Game Design, or policy calls.

Each lifecycle transition installs the exact new epoch and state through an idempotent Game Session control-plane command and receives durable acknowledgement before Automation admits work under a new activation or reports disable, revocation, or forced-drain containment complete. Notifications and cache refresh may accelerate other reads but are not this transition barrier.

Before final gameplay execution, Game Session compares the command's plugin version and activation epoch with its local projection and verifies that the projected lifecycle state permits the work to finish. Missing, stale, displaced, disabled, or revoked state fails closed. Rejected work records bounded diagnostic evidence and cannot mutate gameplay.

Disable and revocation of an active/current lifecycle stop new admission, advance the epoch and install a non-executable lifecycle state, and fence at Game Session before containment completes. Disabling a never-active epoch-0 row is an idempotent no-op. Cleanup of schedules, Automation work, follow-ups, and Game Session queue entries proceeds asynchronously and is not the correctness boundary. Drain stops new admission under the current epoch and permits already admitted work to complete within explicit bounds; completion or forced timeout advances the epoch and rejects remaining old work.

## Consequences

- Activation remains isolated per running game rather than becoming tenant-global.
- Monotonic epochs close same-version ABA and delayed-work resurrection risks.
- Game Session enforces final safety without a synchronous control-plane dependency in the tick path.
- Immediate containment does not wait for asynchronous queue, timer, follow-up, or work-item cleanup.
- Bounded drain retains graceful completion while forced timeout remains fail closed.
- Activation state and projection propagation add durable fields, transition history, reconciliation, and convergence monitoring to plugin runtime paths.
- Trust intake and runtime activation remain separate authorities with separate evidence.

## Alternatives Considered

### Version-Only Per-Instance Fencing

Carry `pluginVersionId` through work and reject only when it differs from the active version. Rejected because same-version reactivation lets delayed work from an older activation match again.

### Materialize Every Plugin into the Game Release

Import all plugin behavior into the Draft/patch lifecycle and reuse the script-patch fence. Rejected because it loses independent per-instance activation, immutable package identity, and plugin-specific update, rollback, and containment.

### Synchronous Final Policy Lookup

Call Automation or policy services in the tick path before every plugin command. Rejected because their availability and latency would enter gameplay execution; a local monotonic projection provides the fence without that dependency.

### Cleanup Before State Transition

Require all queues, work items, schedules, and follow-ups to be purged before disable or revocation becomes effective. Rejected because partial cleanup or subsystem unavailability would delay security containment.

## Implementation and Proof Obligations

Current implementation has instance-scoped plugin state, publication and compatibility preflight, policy reconciliation, ingress version checks, plugin identity on durable work and commands, schedule filtering, and cleanup surfaces. It does not yet persist or propagate `pluginActivationEpoch`, enforce the plugin activation state at Game Session final execution, coordinate every lifecycle cleanup path, or bind control request identity to a canonical operation digest.

Proof must cover first activation, version switch, immediate disable, bounded drain, drain timeout, signer/package revocation, policy loss, same-version reactivation, and `v1 -> v2 -> v1`. It must include triggers, queued and executing work, schedules, remote follow-ups, staged commands, retries, replay, restart, late projection delivery, duplicate projection delivery, fence-install failure, final gameplay mutation, exact request replay, changed-input request reuse, and concurrent activation attempts.

## Reversibility and Revisit Triggers

Projection transport, drain limits, lifecycle state names, and cleanup orchestration may evolve while preserving per-instance authority, monotonic epochs, digest-bound requests, and Game Session final fencing. Revisit the lifecycle if Draft materialization meets product needs. Removing the epoch or permitting execution from missing or stale projection requires a new decision.

## Required Documentation Alignment

- [Scripting contracts](../system-architecture-scripting-contracts.md)
- [Scripting control-plane API](../system-architecture-scripting-control-plane-api.md)
- [Scripting normative contract tables](../system-architecture-scripting-normative-contract-tables.md)
- [Scripting control-plane notifications](../system-architecture-scripting-control-plane-events.md)
