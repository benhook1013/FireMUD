# ADR 0119: Epoch-Fenced Per-Instance Plugin Activation

## Status

Accepted

## Implementation Status

The current implementation has instance-scoped plugin state and policy/publication checks, but does not yet persist or propagate the owner `lifecycleRevision`, apply the Game Session projection with the lexicographic lifecycle fence, or enforce the exact lifecycle tuple at final execution. Those are implementation and proof gaps, not relaxed runtime-fencing behavior.

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

Automation & Scripting owns plugin activation state at `(tenantId, gameInstanceId, pluginId)`. The authoritative state binds the exact selected `pluginVersionId`, a monotonically increasing `pluginActivationEpoch`, the canonical `pluginState` lifecycle field (`ENABLED`, `DRAINING`, or a non-executable state; `ACTIVE` is not an alias), and a monotonically increasing owner `lifecycleRevision` (also called the transition cursor). `lifecycleRevision` starts at `0` and advances exactly once for every committed lifecycle state transition, including a same-epoch transition into `DRAINING`; it is separate from and does not change the `pluginActivationEpoch` transition rules.

`pluginActivationEpoch` starts at `0` for a runtime row with no admitted activation. The first successful activation advances it to `1`. A successful version switch, completed disable of an `ENABLED` lifecycle, final drain or forced drain, same-version reactivation after invalidation, or revocation of an `ENABLED` lifecycle advances it exactly once. Emergency revocation or forced disable that interrupts an in-progress `DRAINING` transition likewise advances `pluginActivationEpoch` exactly once and commits the corresponding `lifecycleRevision` exactly once, extinguishing the predecessor-`ENABLED`-revision drain exception. Entering `DRAINING`, never-active disable, failed operations, no-ops, and exact retries do not advance it. Reusing a `pluginVersionId` never reuses an earlier epoch. Activation validates immutable publication and platform-acceptance evidence, granted capabilities, target-runtime compatibility, bindings, and fresh policy evidence before changing runtime state. Runtime activation does not replace the separate publication and trust controls.

Control-plane mutations use a stable request identity bound to a canonical digest of the complete operation input. Exact replay returns the recorded result. Reusing the request identity with a changed target, scope, reason, or other bound input is rejected.

Lifecycle mutations are serialized by `(tenantId, gameInstanceId, pluginId)`. The owner stores at most one pending lifecycle transition and its target tuple, reserving `targetLifecycleRevision = current lifecycleRevision + 1` for every state-changing transition and one target epoch for each epoch-advancing transition. Initiation compare-and-sets from the captured current `(pluginVersionId, pluginActivationEpoch, pluginState, lifecycleRevision)`—where `pluginState` is the canonical lifecycle field whose executable value is `ENABLED`—only when no other transition is pending; the exact same request resumes that transition with the same reserved target tuple, while a different activation, switch, drain, disable, revocation, reactivation, or policy lifecycle request fails closed with `transition_in_progress` rather than reusing or overwriting its target. The pending target, idempotent Game Session install command, durable acknowledgement, exact retry result, and completion compare-and-set all bind that reserved target lifecycle revision. Completion compare-and-sets the unchanged captured current tuple, pending request identity/digest, target epoch, and target lifecycle revision before committing; a stale completion cannot overwrite newer state, and the reserved lifecycle revision becomes current in owner state exactly once on commit. Security, component, and signer-policy fences are independent and may fail closed immediately; lifecycle serialization does not postpone those checks or their containment.

Every plugin-originated trigger, work item, durable schedule or timer firing, remote follow-up, staged command, and gameplay command carries `pluginId`, exact `pluginVersionId`, applicable `bindingId`, `pluginActivationEpoch`, and the captured `lifecycleRevision`. Game Session maintains a local, versioned projection of Automation-owned activation state. Projection updates apply an atomic lexicographic compare-and-set by `(pluginActivationEpoch, lifecycleRevision)`; lower or reordered tuples cannot overwrite newer state, and contradictory equal tuples are rejected. The tick path does not make synchronous Automation, Game Design, or policy calls. `bindingId` remains plugin provenance/fence evidence and does not become Command-Handoff child identity.

Each lifecycle transition installs the exact target epoch, lifecycle state, and reserved target lifecycle revision through an idempotent Game Session control-plane command and receives a durable acknowledgement bound to that same target tuple before Automation admits work under a new activation or reports disable, revocation, or forced-drain containment complete. Notifications and cache refresh may accelerate other reads but are not this transition barrier.

Before final gameplay execution, Game Session compares the command's exact plugin version and captured `(pluginActivationEpoch, lifecycleRevision)` with the current local projection and verifies that the projected lifecycle state permits the work to finish. `ENABLED` execution requires the exact current lifecycle revision. A `DRAINING` projection retains the exact immediately preceding `ENABLED` lifecycle revision as bounded drain evidence and may allow only work whose winning admission CAS durably committed that revision before the `DRAINING` transition, with the same exact plugin version and activation epoch while every other fence passes. The projection/admission evidence must bind the durable Automation-owned `DRAINING` barrier identity and the ordering evidence that the winning CAS preceded its creation; missing, contradictory, or unverifiable barrier identity/order evidence is rejected. Merely observing or capturing the old revision without that committed admission/fence evidence is rejected; an arbitrary lower revision is never accepted. Non-executable or epoch-advancing transitions provide no predecessor exception and reject displaced work. A delayed same-epoch lifecycle state therefore cannot overwrite or authorize work against a newer transition. Missing, stale, displaced, disabled, or revoked state fails closed. Rejected work records bounded diagnostic evidence and cannot mutate gameplay.

Disable and revocation initiation atomically blocks new admission and records a durable, request-digest-bound pending transition/admission barrier containing the current tuple, the non-executable target state, one reserved target epoch (`current + 1`), and the reserved target lifecycle revision; it does not make that target tuple current. Every admission path checks the barrier, including after restart. An idempotent Game Session fence-install command uses that same target epoch, state, and lifecycle revision. A lost or failed acknowledgement leaves the barrier and pending transition in place and fail closed; an exact retry resumes the same target tuple and command and remains pending rather than returning a completed or no-op result. After durable acknowledgement, one Automation transaction conditionally advances the current epoch, state, and lifecycle revision exactly once, marks the transition complete, and stores its result; exact retries after completion return that stored result. Never-active disable, an already-disabled no-op (only when its corresponding transition/fence acknowledgement is complete), failures, and retries do not add epochs. The barrier is not automatically cleared on timeout or failure; any clearance requires a separate authorized, audited pre-fence cancellation compare-and-set and is forbidden once the target fence may have installed. Advisory notifications follow the completed owner state only. Cleanup of schedules, Automation work, follow-ups, and Game Session queue entries proceeds asynchronously and is not the correctness boundary. Drain stops new admission under the current epoch and permits already admitted work to complete within explicit bounds; completion or forced timeout advances the epoch and rejects remaining old work.

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

Current implementation has instance-scoped plugin state, publication and compatibility preflight, policy reconciliation, ingress version checks, plugin identity on durable work and commands, schedule filtering, and cleanup surfaces. It does not yet persist or propagate `pluginActivationEpoch` or owner `lifecycleRevision`, apply the Game Session projection's lexicographic `(pluginActivationEpoch, lifecycleRevision)` fence, enforce the exact lifecycle revision at Game Session final execution, coordinate every lifecycle cleanup path, or bind control request identity to a canonical operation digest.

Proof must cover first activation, version switch, immediate disable, bounded drain, drain timeout, signer/package revocation, policy loss, same-version reactivation, and the immutable-publication sequence `v1a -> v2 -> v1b` (where `v1b` is a new publication identity, not reactivation of a `SUPERSEDED` ID). It must separately cover same-version runtime reactivation only for an artifact that remains `PUBLISHED` after runtime disable. It must include triggers with applicable binding provenance and captured lifecycle revision, queued and executing work, schedules, remote follow-ups, staged commands, retries, replay, restart, late projection delivery, duplicate projection delivery, lower/reordered projection delivery, contradictory equal projection delivery, same-epoch lifecycle transitions including `DRAINING`, fence-install failure, final gameplay mutation, exact request replay, changed-input request reuse, and concurrent lifecycle requests. It must also prove one pending transition per lifecycle key; reservation of `targetLifecycleRevision = current + 1` for every state-changing transition; binding of that revision through the pending target, install command, acknowledgement, exact retry, and completion compare-and-set; stale-completion rejection; exactly-once advancement on commit; lexicographic projection compare-and-set; exact current revision for `ENABLED`; only the retained immediately preceding `ENABLED` revision whose winning admission CAS committed before `DRAINING` for same-version, same-epoch bounded completion; rejection of capture-only evidence, arbitrary lower revisions, and displaced work after non-executable or epoch-advancing transitions; one atomic disable/revocation completion transition; no epoch advance on initiation alone; and containment completion only after durable Game Session acknowledgement of the same new epoch and non-executable state.

## Reversibility and Revisit Triggers

Projection transport, drain limits, lifecycle state names, and cleanup orchestration may evolve while preserving per-instance authority, monotonic epochs, digest-bound requests, and Game Session final fencing. Revisit the lifecycle if Draft materialization meets product needs. Removing the epoch or permitting execution from missing or stale projection requires a new decision.

## Required Documentation Alignment

- [Scripting contracts](../system-architecture-scripting-contracts.md)
- [Scripting control-plane API](../system-architecture-scripting-control-plane-api.md)
- [Scripting normative contract tables](../system-architecture-scripting-normative-contract-tables.md)
- [Scripting control-plane notifications](../system-architecture-scripting-control-plane-events.md)
