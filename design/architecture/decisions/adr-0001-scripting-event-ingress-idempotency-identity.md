# ADR 0001: Scripting Event Ingress Idempotency Identity

## Status

Accepted

## Context

Event-ingress RPCs into the Automation & Scripting Service must be idempotent under retries and failover. Multiple documents previously used inconsistent dedupe keys (for example omitting `entityId`, `eventType`, `scriptPatchVersion`, and `regionEpoch`), which creates ambiguity and collision risk.

## Decision

Automation & Scripting treats event ingress as at-most-once per **Trigger Identity**. The exact endpoint-specific field matrix is owned by [Scripting Normative Contract Tables](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) so the ADR and runtime contract cannot evolve as competing tuples.

- Gameplay/runtime handler identity includes the applicable `tenantId`, `gameInstanceId`, resolved `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptEventId`, and `isDryRun` fields.
- `isDryRun` is always an identity dimension so live and test execution cannot collide.
- Scheduler/timer identity additionally carries its due point and trigger mode. The canonical scheduler preimage fields are serialized in this fixed order: `<tenantId, gameInstanceId, playableStateScope, stableOwnerKind, stableOwnerId, regionId, regionEpoch, entityId when targetScopeType=ENTITY, scriptId, eventType, eventSchemaVersion, scriptPatchVersion, scriptPinEpoch, scheduleDefinitionId, targetScopeType, targetScopeId, duePoint, isDryRun, triggerMode, pluginId when plugin-owned, pluginVersionId when plugin-owned, bindingId when plugin-owned, pluginActivationEpoch when plugin-owned, resumeWindowId when triggerMode=CATCH_UP>`. Fields marked `when ...` are omitted, not replaced by empty or sentinel values, when their branch does not apply; `targetScopeType` makes the target selector explicit and `stableOwnerKind` makes the core/plugin owner branch explicit. Thus `scriptEventId` itself is never part of this preimage, and `resumeWindowId` is absent for non-catch-up triggers. `duePoint` is exactly one tagged value: `dueTickId:<value>` for tick-aligned schedules or `dueAt:<epochMillis>` for wall-clock timers. The current scheduler path resolves `playableStateScope`, emits `eventSchemaVersion=v1`, emits `isDryRun=false`, and emits `triggerMode=TRIGGER_MODE_CATCH_UP`; those values remain explicit in the fixed-order preimage even though they are currently constant. Any additional scheduler schema version or trigger mode must become an explicit deterministic-ID input before it is admitted.
- The canonical target preimage is the fixed-order, length-prefixed UTF-8 serialization that `ScriptScheduleInstanceServiceImpl.TimerFiringCandidate.identity()` must implement; the current live implementation remains narrower until the target and owner/plugin fields are carried, as recorded in the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status). Each field is encoded as its UTF-8 byte length, a colon, and its unescaped value; fields are concatenated without separators. This permits delimiters and arbitrary UTF-8 text without creating tuple collisions. Hash those bytes with SHA-256, encode the digest as lowercase hexadecimal, retain the first 60 hexadecimal characters, and prefix the result with `timer-`.
- Tenant-readiness `onLoad` follows the explicit non-runtime exception in the normative table and does not invent sentinel runtime, region, or entity identity.
- Plugin-trigger identity additionally carries plugin and binding identity where the invocation unit is plugin- or binding-scoped.

Callers must reuse the same full applicable Trigger Identity, including the same `scriptEventId`, when retrying a logically identical trigger.

## Consequences

- Protos and service contracts must carry enough fields to represent the endpoint-specific Trigger Identity, including runtime scope, dry-run namespace, and due-point or plugin dimensions where applicable.
- Audit records (`script_event_audit`) must be keyed by Trigger Identity, not by `scriptEventId` alone.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
