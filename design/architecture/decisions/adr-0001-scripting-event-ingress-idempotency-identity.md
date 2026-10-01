# ADR 0001: Scripting Event Ingress Idempotency Identity

## Status

Superseded by [ADR 0172](./adr-0172-parent-event-and-frozen-handler-execution-identity.md)

This ADR preserves historical ingress-idempotency context, not an independent current identity contract. [ADR 0172](./adr-0172-parent-event-and-frozen-handler-execution-identity.md) owns parent-event and frozen-handler execution identity; [Scripting Normative Contract Tables](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) owns the current endpoint-specific Trigger Identity fields and scheduler candidate preimage.

## Implementation Status

The live `ScriptScheduleInstanceServiceImpl.TimerFiringCandidate.identity()` implementation remains narrower than the canonical scheduler preimage until the target and owner/plugin fields are carried. It currently uses resolved `playableStateScope` in the identity preimage and emits `eventSchemaVersion=v1`, `isDryRun=false`, and `triggerMode=TRIGGER_MODE_CATCH_UP`; authoritative `playableStateNamespaceId` identity and separate non-identity scope evidence are target-state. The current values remain explicit preimage inputs even while they are constant. It has no cross-producer golden-vector proof for the canonical preimage or its numeric formatting rules. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

## Context

Event-ingress RPCs into the Automation & Scripting Service must be idempotent under retries and failover. Multiple documents previously used inconsistent dedupe keys (for example omitting `entityId`, `eventType`, `scriptPatchVersion`, and `regionEpoch`), which creates ambiguity and collision risk.

## Decision

The original decision treated event ingress as at-most-once per **Trigger Identity**. Current endpoint-specific identity semantics are owned by [Scripting Normative Contract Tables](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) and [ADR 0172](./adr-0172-parent-event-and-frozen-handler-execution-identity.md); the notes below preserve context without defining a competing tuple.

- Presented `playableStateScope` is exact-validated evidence, not a uniqueness discriminator. Stale or mismatched scope remains fenced until authoritative reconciliation; a namespace-keyed identity may be reused only if authority proves scope unchanged. An authority-proven transition starts a new playable-state lifecycle and namespace.
- Live/test separation, tenant-readiness `onLoad` exceptions, and plugin/binding identity follow the applicable fields and branches in the normative table and ADR 0172; this superseded ADR does not define their current schema.
- Scheduler/timer candidate identity, including its conditional branches, serialization, hash, and proof requirements, is owned by [Table 1's Scheduler Candidate Identity Preimage](../system-architecture-scripting-normative-contract-tables.md#scheduler-candidate-identity-preimage-normative).

Retry identity and handler materialization follow the current Trigger Identity and scheduler-candidate owners linked above; the original decision's retry rule is historical context rather than a separate current specification.

## Consequences

- Current proto and service requirements for endpoint-specific Trigger Identity and frozen handler execution are owned by [Scripting Normative Contract Tables](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) and [ADR 0172](./adr-0172-parent-event-and-frozen-handler-execution-identity.md); this ADR's original consequence is historical context.
- Audit records (`script_event_audit`) follow the current Trigger Identity contract, not `scriptEventId` alone.

## Implementation and Proof Obligations

Current implementation and proof obligations are owned by [ADR 0172](./adr-0172-parent-event-and-frozen-handler-execution-identity.md) and the canonical identity matrix and scheduler-preimage requirements in [Scripting Normative Contract Tables](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields). Select and report checks under [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md); this superseded ADR is not an independent proof authority.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
