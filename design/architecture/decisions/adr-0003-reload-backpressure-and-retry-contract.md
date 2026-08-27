# ADR 0003: Reload Backpressure and Retry Contract

## Status

Superseded by [ADR 0173](./adr-0173-registry-classified-reload-admission-policy.md)

## Context

Script hot reload pauses admission for a tenant while `pendingPatchVersion` is validated and `onLoad` runs. Upstream services need a clear contract: are triggers dropped permanently during reload, or should callers retry?

## Decision

- During `reloadState=RELOADING`, the Automation & Scripting Service must return an explicit application-level backpressure signal on event ingress (for example `TriggerScriptEventResponse.admitted=false` with `admission_outcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_RELOADING`) and record the pre-resolution denial in `script_event_ingress_audit`. If reload backpressure is applied after a concrete handler is resolved, that handler's `script_event_audit` row uses `finalStage=ADMISSION`, `finalOutcome=skipped_reloading`, `finalReason=reloading`.
- For low-rate, external entity-scoped events (for example `onSpawn`, `onEnterRegion`, `onCommand`), callers must retry reload backpressure with the same full applicable Trigger Identity, including the same `scriptEventId`, using bounded exponential backoff and jitter.
- For recurring and advisory timer-derived events (`onInterval`, or advisory uses of `onTimerExpire`), the scheduler does not backfill triggers that were not admitted during reload; the declared `SKIP_MISSED` or `COALESCE_ONE` policy applies. A correctness-bearing one-shot is not best-effort: its durable intent remains recoverable under the same logical identity. Physical execution may be at least once and replay-safe; identity and digest guards converge retries to one logical terminal outcome under [ADR 0072](./adr-0072-class-specific-timer-durability-and-recovery.md).

## Consequences

- Game Session (and other event sources) must implement bounded retry for only the supported event classes.
- Audit and metrics must distinguish dropped, backpressured, recurring/advisory skipped, and correctness-bearing one-shot recovery outcomes.

## References

- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
