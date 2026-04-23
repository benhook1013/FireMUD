# Automation & Scripting Service Status

## Current Coverage

- The service owns the scripting runtime, event ingestion, quotas, and sandbox execution model described in the architecture docs.
- Script authoring and control-plane integration with Game Design are documented and substantially modeled in the current design set.
- Runtime/data, operations, configuration, and sandbox-specific docs are now split and maintained as the canonical reference set.
- The current runtime implementation includes event-scope ingress admission with serialized payload-size enforcement, durable script event bindings, durable `script_work_items`, handler-scoped audit rows, pending-work cancellation, pending-work claiming, the first real claimed-work evaluator/consumer path with quota enforcement plus current-boundary command-template execution, the first scope-local rollback drain read (`GetAutomationDrainStatus`) over durable work-item state, the first Automation-side pin-observation read (`GetAutomationPinConvergence`) over shared runtime state with persisted pin `controlPlaneRequestId`, bounded dead-letter listing plus controlled dead-letter replay, a first Game Session `onCommand` producer, canonical instance-aware Automation Redis key helpers for queue/tick staging plus quota counters, durable outbox-pointer envelopes for `automation:queue:*` indexes, queue-drain dedupe plus bounded scheduled queue rebuild for that derived Redis projection, the Game Session command handoff client/service boundary for emitted work-item commands, script-patch status reads derived from durable work, the first per-instance script-patch rollout reads with freshness flags, a durable plugin runtime registry/control-plane state transition surface with last-request and actor metadata, live activation-time reads from Game Design/Game Session for plugin publication plus immutable release-bundle-backed `baseVersionId` and `abilitySchemaDigest` validation, and scheduled terminal outbox cleanup governed by the documented retention knobs.

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
