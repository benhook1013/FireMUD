# ADR 0001: Scripting Event Ingress Idempotency Identity

## Status

Accepted

## Context

Event-ingress RPCs into the Automation & Scripting Service must be idempotent under retries and failover. Multiple documents previously used inconsistent dedupe keys (for example omitting `entityId`, `eventType`, `scriptPatchVersion`, and `regionEpoch`), which creates ambiguity and collision risk.

## Decision

Automation & Scripting treats event ingress as at-most-once per **Trigger Identity**:

- For entity-scoped external events:
  - `<tenantId, regionId, entityId, scriptId, eventType, scriptPatchVersion, scriptEventId>`
- For scheduler/timer events (tick-aligned):
  - `<tenantId, regionId, regionEpoch, entityId, scriptId, eventType, scriptPatchVersion, scriptEventId>`
  - `scriptEventId` must be deterministic for scheduler-originated triggers and must include the due point (for example `dueTickId` or `dueAt`) in its derivation.

Callers must reuse the same `scriptEventId` when retrying a logically identical trigger.

## Consequences

- Protos and service contracts must carry enough fields to represent Trigger Identity (including `entityId`, `eventType`, and `scriptPatchVersion`; tick-aligned scheduling must also carry `regionEpoch` and a due point).
- Audit records (`script_event_audit`) must be keyed by Trigger Identity, not by `scriptEventId` alone.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
