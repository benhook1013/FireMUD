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

Lifecycle mutations are serialized by `(tenantId, gameInstanceId, pluginId)`. The owner stores at most one pending lifecycle transition and its target tuple (with one reserved target epoch for an epoch-advancing transition). Initiation compare-and-sets from the captured current `(pluginVersionId, pluginActivationEpoch, pluginState)` only when no other transition is pending; the exact same request resumes that transition, while a different activation, switch, drain, disable, revocation, reactivation, or policy lifecycle request fails closed with `transition_in_progress` rather than reusing or overwriting its target. Completion compare-and-sets the unchanged captured current tuple, pending request identity/digest, and target epoch before committing; a stale completion cannot overwrite newer state. Security, component, and signer-policy fences are independent and may fail closed immediately; lifecycle serialization does not postpone those checks or their containment.

Every plugin-originated trigger, work item, durable schedule or timer firing, remote follow-up, staged command, and gameplay command carries `pluginId`, exact `pluginVersionId`, applicable `bindingId`, and `pluginActivationEpoch`. Game Session maintains a local, versioned projection of Automation-owned activation state. Projection updates are monotonic by epoch, and the tick path does not make synchronous Automation, Game Design, or policy calls. `bindingId` remains plugin provenance/fence evidence and does not become Command-Handoff child identity.

Each lifecycle transition installs the exact new epoch and state through an idempotent Game Session control-plane command and receives durable acknowledgement before Automation admits work under a new activation or reports disable, revocation, or forced-drain containment complete. Notifications and cache refresh may accelerate other reads but are not this transition barrier.

Before final gameplay execution, Game Session compares the command's plugin version and activation epoch with its local projection and verifies that the projected lifecycle state permits the work to finish. Missing, stale, displaced, disabled, or revoked state fails closed. Rejected work records bounded diagnostic evidence and cannot mutate gameplay.

Disable and revocation initiation atomically blocks new admission and records a durable, request-digest-bound pending transition/admission barrier containing the current tuple, the non-executable target state, and one reserved target epoch (`current + 1`); it does not make that target epoch current. Every admission path checks the barrier, including after restart. An idempotent Game Session fence-install command uses that same target epoch/state. A lost or failed acknowledgement leaves the barrier and pending transition in place and fail closed; an exact retry resumes the same target epoch and command and remains pending rather than returning a completed or no-op result. After durable acknowledgement, one Automation transaction conditionally advances the current epoch/state exactly once, marks the transition complete, and stores its result; exact retries after completion return that stored result. Never-active disable, an already-disabled no-op (only when its corresponding transition/fence acknowledgement is complete), failures, and retries do not add epochs. The barrier is not automatically cleared on timeout or failure; any clearance requires a separate authorized, audited pre-fence cancellation compare-and-set and is forbidden once the target fence may have installed. Advisory notifications follow the completed owner state only. Cleanup of schedules, Automation work, follow-ups, and Game Session queue entries proceeds asynchronously and is not the correctness boundary. Drain stops new admission under the current epoch and permits already admitted work to complete within explicit bounds; completion or forced timeout advances the epoch and rejects remaining old work.

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

Proof must cover first activation, version switch, immediate disable, bounded drain, drain timeout, signer/package revocation, policy loss, same-version reactivation, and `v1 -> v2 -> v1`. It must include triggers with applicable binding provenance, queued and executing work, schedules, remote follow-ups, staged commands, retries, replay, restart, late projection delivery, duplicate projection delivery, fence-install failure, final gameplay mutation, exact request replay, changed-input request reuse, and concurrent lifecycle requests. It must also prove one pending transition per lifecycle key, captured-tuple and request/target-epoch completion CAS, stale-completion rejection, one atomic active-disable/revocation completion transition, no epoch advance on initiation alone, and containment completion only after durable Game Session acknowledgement of the same new epoch and non-executable state.

## Reversibility and Revisit Triggers

Projection transport, drain limits, lifecycle state names, and cleanup orchestration may evolve while preserving per-instance authority, monotonic epochs, digest-bound requests, and Game Session final fencing. Revisit the lifecycle if Draft materialization meets product needs. Removing the epoch or permitting execution from missing or stale projection requires a new decision.

## Required Documentation Alignment

- [Scripting contracts](../system-architecture-scripting-contracts.md)
- [Scripting control-plane API](../system-architecture-scripting-control-plane-api.md)
- [Scripting normative contract tables](../system-architecture-scripting-normative-contract-tables.md)
- [Scripting control-plane notifications](../system-architecture-scripting-control-plane-events.md)
