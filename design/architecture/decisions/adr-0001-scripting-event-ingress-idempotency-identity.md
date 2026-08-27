# ADR 0001: Scripting Event Ingress Idempotency Identity

## Status

Accepted

## Implementation Status

The live `ScriptScheduleInstanceServiceImpl.TimerFiringCandidate.identity()` implementation remains narrower than the canonical scheduler preimage until the target and owner/plugin fields are carried. It currently resolves `playableStateScope` and emits `eventSchemaVersion=v1`, `isDryRun=false`, and `triggerMode=TRIGGER_MODE_CATCH_UP`; those values are explicit inputs even while they remain constant. It has no cross-producer golden-vector proof for the canonical preimage or its numeric formatting rules. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

## Context

Event-ingress RPCs into the Automation & Scripting Service must be idempotent under retries and failover. Multiple documents previously used inconsistent dedupe keys (for example omitting `entityId`, `eventType`, `scriptPatchVersion`, and `regionEpoch`), which creates ambiguity and collision risk.

## Decision

Automation & Scripting treats event ingress as at-most-once per **Trigger Identity**. The exact endpoint-specific field matrix is owned by [Scripting Normative Contract Tables](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) so the ADR and runtime contract cannot evolve as competing tuples.

- Gameplay/runtime handler identity includes the applicable `tenantId`, `gameInstanceId`, resolved `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptEventId`, and `isDryRun` fields.
- `isDryRun` is always an identity dimension so live and test execution cannot collide.
- Scheduler/timer identity additionally carries its due point and trigger mode. The canonical scheduler preimage fields are serialized in this fixed order: `<tenantId, gameInstanceId, playableStateScope, stableOwnerKind, stableOwnerId, regionId, regionEpoch, entityId when targetScopeType=ENTITY, scriptId when core-owned, eventType, eventSchemaVersion, scriptPatchVersion, scriptPinEpoch, scheduleDefinitionId, targetScopeType, targetScopeId, duePoint, isDryRun, triggerMode, pluginId when plugin-owned, pluginVersionId when plugin-owned, bindingId when plugin-owned, pluginActivationEpoch when plugin-owned, resumeWindowId when triggerMode=CATCH_UP>`. Fields marked `when ...` are omitted, not replaced by empty or sentinel values, when their branch does not apply; `targetScopeType` makes the target selector explicit and `stableOwnerKind` makes the core/plugin owner branch explicit. Thus `scriptEventId` itself is never part of this preimage, and `resumeWindowId` is absent for non-catch-up triggers. `duePoint` is exactly one tagged value: `dueTickId:<value>` for tick-aligned schedules or `dueAt:<epochMillis>` for wall-clock timers. Any additional scheduler schema version or trigger mode must become an explicit deterministic-ID input before it is admitted.
- The canonical target preimage is the fixed-order, length-prefixed UTF-8 serialization that `ScriptScheduleInstanceServiceImpl.TimerFiringCandidate.identity()` must implement. Each field is encoded as its UTF-8 byte length rendered as unsigned base-10 ASCII decimal with no leading zeroes, followed by a colon and its unescaped UTF-8 value; fields are concatenated without separators. Numeric identity values therefore use their canonical base-10 ASCII spelling (for example, `0`, `7`, and `-1`), and the prefix length counts UTF-8 bytes rather than Unicode code points. Semantic validation still controls which numeric values are admissible; deterministic decimal serialization does not make an otherwise invalid value valid. Boolean values serialize canonically as lowercase `true` or `false`. Enum values serialize as the contract-declared enum symbol/name, never an ordinal, localized spelling, or other implementation-specific representation. This permits delimiters and arbitrary UTF-8 text without creating tuple collisions. Hash those bytes with SHA-256, encode the digest as lowercase hexadecimal, retain the first 60 hexadecimal characters, and prefix the result with `timer-`. Cross-producer golden vectors must cover empty values, non-ASCII values, delimiters, negative and zero numeric values, both boolean values, representative enum branches, and both tagged due-point branches before this identity is considered proven.
- Tenant-readiness `onLoad` follows the explicit non-runtime exception in the normative table and does not invent sentinel runtime, region, or entity identity.
- Plugin-trigger identity additionally carries plugin and binding identity where the invocation unit is plugin- or binding-scoped.

Ordinary event-ingress callers must reuse the same full applicable Trigger Identity, including the same `scriptEventId`, when retrying a logically identical trigger. Scheduler retries instead reuse the same complete due-candidate/firing-claim identity and its deterministically derived `scriptEventId`; that derived ID is propagated into resolved handler identities but remains excluded from `TimerFiringCandidate.identity()`'s scheduler preimage.

## Consequences

- Protos and service contracts must carry enough fields to represent the endpoint-specific Trigger Identity, including runtime scope, dry-run namespace, and due-point or plugin dimensions where applicable.
- Audit records (`script_event_audit`) must be keyed by Trigger Identity, not by `scriptEventId` alone.

## Implementation and Proof Obligations

Prove the endpoint-specific Trigger Identity field matrix, fixed-order scheduler preimage, length-prefixed UTF-8 serialization, canonical numeric, boolean, and enum forms, optional-branch omission, deterministic hashing, cross-producer golden vectors, and exact retry reuse without collisions or duplicate logical triggers. Select and report the required checks and evidence under [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md); record execution results in PR/CI evidence or implementation-tracking documents rather than in this decision record.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
