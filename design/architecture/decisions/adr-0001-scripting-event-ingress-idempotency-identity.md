# ADR 0001: Scripting Event Ingress Idempotency Identity

## Status

Superseded by [ADR 0172](./adr-0172-parent-event-and-frozen-handler-execution-identity.md)

## Implementation Status

The live `ScriptScheduleInstanceServiceImpl.TimerFiringCandidate.identity()` implementation remains narrower than the canonical scheduler preimage until the target and owner/plugin fields are carried. It currently uses resolved `playableStateScope` in the identity preimage and emits `eventSchemaVersion=v1`, `isDryRun=false`, and `triggerMode=TRIGGER_MODE_CATCH_UP`; authoritative `playableStateNamespaceId` identity and separate non-identity scope evidence are target-state. The current values remain explicit preimage inputs even while they are constant. It has no cross-producer golden-vector proof for the canonical preimage or its numeric formatting rules. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

## Context

Event-ingress RPCs into the Automation & Scripting Service must be idempotent under retries and failover. Multiple documents previously used inconsistent dedupe keys (for example omitting `entityId`, `eventType`, `scriptPatchVersion`, and `regionEpoch`), which creates ambiguity and collision risk.

## Decision

Automation & Scripting treats event ingress as at-most-once per **Trigger Identity**. The exact endpoint-specific field matrix is owned by [Scripting Normative Contract Tables](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) so the ADR and runtime contract cannot evolve as competing tuples.

- Gameplay/runtime handler identity includes the applicable `tenantId`, `gameInstanceId`, authoritative `playableStateNamespaceId`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptPinEpoch`, `scriptEventId`, and `isDryRun` fields. The server-derived `playableStateScope` is retained and exact-validated as immutable policy/routing/authorization/fence evidence, not as a uniqueness discriminator. An authoritative scope transition starts a new playable-state lifecycle and namespace; stale presented scope with unchanged authority may reuse the existing identity.
- `isDryRun` is always an identity dimension so live and test execution cannot collide.
- Scheduler/timer triggers additionally carry a due point and trigger mode. The canonical scheduler candidate preimage, its conditional branches, serialization, hash, and proof requirements are owned by [Table 1's Scheduler Candidate Identity Preimage](../system-architecture-scripting-normative-contract-tables.md#scheduler-candidate-identity-preimage-normative); this superseded ADR preserves no competing specification.
- Tenant-readiness `onLoad` follows the explicit non-runtime exception in the normative table and does not invent sentinel runtime, region, or entity identity.
- Plugin-trigger identity additionally carries plugin and binding identity where the invocation unit is plugin- or binding-scoped.

Ordinary event-ingress callers must reuse the same full applicable Trigger Identity, including the same `scriptEventId`, when retrying a logically identical trigger. Scheduler retries instead reuse the same complete due-candidate/firing-claim identity and its deterministically derived `scriptEventId`; that derived ID is propagated into resolved handler identities but remains excluded from `TimerFiringCandidate.identity()`'s scheduler preimage.

## Consequences

- Protos and service contracts must carry enough fields to represent the endpoint-specific Trigger Identity, including the playable-state namespace, immutable scope fence evidence, dry-run mode, and due-point or plugin dimensions where applicable.
- Audit records (`script_event_audit`) must be keyed by Trigger Identity, not by `scriptEventId` alone.

## Implementation and Proof Obligations

Implementation and proof must satisfy the canonical identity matrix and scheduler-preimage requirements in [Scripting Normative Contract Tables](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields), including its required vectors and exact-retry behavior. Select and report the required checks and evidence under [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md); record execution results in PR/CI evidence or implementation-tracking documents rather than in this decision record.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
