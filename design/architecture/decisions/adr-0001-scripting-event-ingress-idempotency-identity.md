# ADR 0001: Scripting Event Ingress Idempotency Identity

## Status

Accepted

## Context

Event-ingress RPCs into the Automation & Scripting Service must be idempotent under retries and failover. Multiple documents previously used inconsistent dedupe keys (for example omitting `entityId`, `eventType`, `scriptPatchVersion`, and `regionEpoch`), which creates ambiguity and collision risk.

## Decision

Automation & Scripting treats event ingress as at-most-once per **Trigger Identity**. The exact endpoint-specific field matrix is owned by [Scripting Normative Contract Tables](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) so the ADR and runtime contract cannot evolve as competing tuples.

- Gameplay/runtime handler identity includes the applicable `tenantId`, `gameInstanceId`, resolved `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `scriptPatchVersion`, `scriptEventId`, and `isDryRun` fields.
- `isDryRun` is always an identity dimension so live and test execution cannot collide.
- Scheduler/timer identity additionally carries its due point and trigger mode. The scheduler derives `scriptEventId` from the non-ID inputs `<tenantId, gameInstanceId, regionId, regionEpoch, entityId, scriptId, eventType, scriptPatchVersion, scheduleDefinitionId, duePoint>`; `scriptEventId` itself is never part of this preimage. `duePoint` is exactly one tagged value: `dueTickId:<value>` for tick-aligned schedules or `dueAt:<epochMillis>` for wall-clock timers. The current scheduler path emits only `triggerMode=TRIGGER_MODE_CATCH_UP`, which remains an explicit field in the full Trigger Identity; any additional scheduler trigger mode must become an explicit deterministic-ID input before that mode is admitted.
- The canonical preimage is the fixed-order, pipe-delimited UTF-8 serialization used by `ScriptScheduleInstanceServiceImpl.TimerFiringCandidate.identity()`. Hash those bytes with SHA-256, encode the digest as lowercase hexadecimal, retain the first 60 hexadecimal characters, and prefix the result with `timer-`.
- Tenant-readiness `onLoad` follows the explicit non-runtime exception in the normative table and does not invent sentinel runtime, region, or entity identity.
- Plugin-trigger identity additionally carries plugin and binding identity where the invocation unit is plugin- or binding-scoped.

Callers must reuse the same `scriptEventId` when retrying a logically identical trigger.

## Consequences

- Protos and service contracts must carry enough fields to represent the endpoint-specific Trigger Identity, including runtime scope, dry-run namespace, and due-point or plugin dimensions where applicable.
- Audit records (`script_event_audit`) must be keyed by Trigger Identity, not by `scriptEventId` alone.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
