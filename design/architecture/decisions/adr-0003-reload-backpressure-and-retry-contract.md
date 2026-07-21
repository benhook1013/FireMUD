# ADR 0003: Reload Backpressure and Retry Contract

## Status

Superseded by [ADR 0168](./adr-0168-registry-classified-reload-admission-policy.md).

## Context

Script hot reload pauses admission for a tenant while `pendingPatchVersion` is validated and `onLoad` runs. Upstream services need a clear contract: are triggers dropped permanently during reload, or should callers retry?

## Decision

- During `reloadState=RELOADING`, the Automation & Scripting Service must return an explicit application-level backpressure signal on event ingress (for example `TriggerScriptEventResponse.admitted=false` with `admission_outcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_RELOADING`), and record the same condition in `script_event_audit` as `finalStage=ADMISSION`, `finalOutcome=skipped_reloading`, `finalReason=reloading`.
- For low-rate, external entity-scoped events (for example `onSpawn`, `onEnterRegion`, `onCommand`), callers may retry with the same `scriptEventId` using a bounded exponential backoff and jitter.
- For timer-derived/scheduler events (`onInterval`, `onTimerExpire`), the scheduler does not backfill triggers that were not admitted during reload; the normal best-effort timer semantics apply (bounded catch-up only where explicitly defined).

## Consequences

- Game Session (and other event sources) must implement bounded retry for only the supported event classes.
- Audit and metrics must distinguish “dropped” vs “backpressured” vs “skipped as best-effort timer”.

## References

- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
