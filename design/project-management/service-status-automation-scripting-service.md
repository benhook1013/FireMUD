# Automation & Scripting Service Status

## Current Coverage

- The service owns the scripting runtime, event ingestion, quotas, and sandbox execution model described in the architecture docs.
- Script authoring and control-plane integration with Game Design are documented and substantially modeled in the current design set.
- Runtime/data, operations, configuration, and sandbox-specific docs are now split and maintained as the canonical reference set.
- The current runtime implementation includes event-scope ingress admission with serialized payload-size enforcement, a durable Automation-owned rollback admission barrier (`automation_admission_states`) with live `SetAutomationAdmissionMode`, durable script event bindings, durable `script_work_items` stamped with `admissionEpoch` and plugin provenance, handler-scoped audit rows, pending-work cancellation by script patch and plugin version, pending-work claiming, the first real claimed-work evaluator/consumer path with handler-admission per-script quota enforcement, execution-reservation tenant-budget enforcement, and current-boundary command-template execution, a real scope-local rollback drain read (`GetAutomationDrainStatus`) over durable admission state plus durable work-item truth, durable late-handoff fencing on rollback epoch advancement, a durable Automation-owned `script_patch_pin_projections` read model for `GetAutomationPinConvergence` with persisted pin `controlPlaneRequestId` and freshness flags, bounded dead-letter listing plus controlled dead-letter replay, a first Game Session `onCommand` producer, canonical instance-aware Automation Redis key helpers for queue/tick staging plus quota/budget counters, durable outbox-pointer envelopes for `automation:queue:*` indexes, queue-drain dedupe plus bounded scheduled queue rebuild for that derived Redis projection, the Game Session command handoff client/service boundary for emitted work-item commands including script-patch and plugin-version provenance, script-patch status reads derived from durable work, a durable `script_patch_instance_rollout_projections` read model for per-instance script-patch rollout reads with freshness flags plus first `REPINNED` preservation after rollback recovery, append-only rollout transition events exposed by `ListScriptPatchInstanceRolloutEvents`, a durable plugin runtime registry/control-plane state transition surface with last-request and actor metadata, live activation-time reads from Game Design/Game Session for plugin publication plus immutable release-bundle-backed `baseVersionId` and `abilitySchemaDigest` validation, signer-revocation and component-policy activation gates, and scheduled terminal outbox cleanup governed by the documented retention knobs.

## Current Role In The Platform

- Executes sandboxed gameplay automation outside the tick loop and emits gameplay work back into the runtime through controlled queues.
- Acts as the runtime target for script publication, patch rollout, and quota enforcement.
- Supports creator tooling through the Game Design Service rather than exposing the primary design UX itself.

## Partial / Stubbed / Deferred Areas

- Script-driven generation and richer procedural population flows are still future implementation areas rather than established gameplay slices.
- Runtime rollout and validation behavior is strongly specified in docs, but much of the remaining work is broader platform build-out rather than isolated scripting tasks.
- Richer graph DSL execution beyond the current command-template evaluator, scheduler/timer producers, richer plugin compatibility enforcement beyond publication plus base-version validation, and richer operator convergence reads beyond current patch/plugin status summaries remain incomplete.
- Cross-service integration confidence outside the current reviewed doc set should still be treated as evolving.

## Planning Notes

- Treat this file as a service summary, not a backlog.
- New scripting work should be planned as vertical slices or phase docs, not appended here as checkboxes.
