# FireMUD System Architecture: Identifier Glossary

This document defines canonical identifier names and scopes used across FireMUD service designs. When other documents use ambiguous terms like `roomId`, treat this glossary as the tie-breaker and update the doc to use the appropriate scoped identifier.

## Core Identifiers

- `accountId` – identifies a platform account. Present on authentication/session records and account-owned domain relationships.
- `tenantId` – identifies the game/tenant. Present on all persistent domain tables and all cross-service APIs.
- `versionId` – identifies a design bundle/version for a tenant. Domain service template data is scoped by `(tenantId, versionId)`.
- `gameInstanceId` – identifies a running game instance for a tenant. Domain service runtime/instance data is scoped by `(tenantId, gameInstanceId)` and references the instance’s pinned `runtime_version`/`versionId`.
- `characterId` – identifies a character owned within a tenant. Gameplay session binding and any instance-local playable state use it together with `{tenantId, gameInstanceId}` scope.

## Identifier Format Conventions

- When a FireMUD identifier format is documented explicitly, use `UUID` terminology rather than `GUID`.
- `accountId`, `tenantId`, `versionId`, `gameInstanceId`, and `characterId` are canonical UUID string identifiers.
- Services must still treat these identifiers as opaque values unless a contract specifically requires validation of UUID shape.
- `tenantSlug` is not a UUID. It is a stable human-readable selector used only in player-facing lobby flows and resolved server-side to `tenantId`.

## World Identifiers

World data uses two distinct identifier families. Template identifiers must not be used in runtime APIs, and instance identifiers must not appear in design-time template graphs.

- **Template identifiers (design-time, version-scoped)** – keyed by `(tenantId, versionId)`:
  - `regionTemplateId`, `zoneTemplateId`, `roomTemplateId`
  - `RoomTemplateRef` = `(tenantId, versionId, roomTemplateId)`
- **Instance identifiers (runtime, instance-scoped)** – keyed by `(tenantId, gameInstanceId)`:
  - `regionInstanceId`, `zoneInstanceId`, `roomInstanceId`
  - `RoomInstanceRef` = `(tenantId, gameInstanceId, roomInstanceId)`
  - `roomInstanceId` is the canonical cross-service runtime room identity and must be treated as opaque by callers, even when some implementations currently use numeric-looking values.

World Management may still keep an internal numeric storage key for room topology joins, but that key is not the shared runtime contract and should use distinct naming such as `roomInstanceRowId` / `roomInstanceDbId` rather than `roomInstanceId`.

## Entity Identifiers

- `entityId` – identifies a live runtime entity (character, NPC, item instance, container entity) within a tenant.
- `entityTemplateId` – identifies a versioned entity template (items, NPC definitions, equipment templates) scoped by `(tenantId, versionId)`.

## Cross-Service Effect Identity

Tick-driven, cross-service mutations are at-least-once and must be idempotent.

- `EffectId` – the canonical target-specific idempotency identity derived from region-scoped tick context (`tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `tickId`, `effectKey`) plus `targetAggregateType` and `targetAggregateId`. The canonical tuple is complete: no participant may add an unregistered `domainScope` or replace a tuple member with a service-local identifier. A multi-aggregate operation carries one stable operation `effectKey` and one deterministic expected target set, then projects one complete `EffectId` for each affected aggregate; it does not reuse one target's terminal outcome for another target. Replay or reconciliation evaluates the complete expected set while preserving each target-specific `EffectId`.
- **EffectId derivation and propagation:** the Game Session/tick coordinator derives the identity once from the admitted command and authoritative tick context. It propagates the same canonical serialized `effectId` and its structured tuple fields to every participant, ledger row, guard, outbox record, and reconciliation record. Participants validate scope and target identity, project the same tuple into their local guard, and must not generate a random ID, re-derive from mutable payload, or silently omit `playableStateScope` or `gameInstanceId`. An implementation may use a physical opaque `effectId` column, but that column is only valid when its canonical tuple projection is retained or deterministically recoverable.

## Cross-Service Read Fence Identity

Cross-service read composition (for example `LOOK` world + entity joins) must use a shared fence token to prevent mixed-tick snapshots:

- `roomReadFence` – the canonical opaque, same-scope room-read fence. It is one byte-stable logical token, not a concatenation of service versions and not a caller-derived timestamp. The fence is valid only within its `RoomInstanceRef` scope.
- **Wire aliases:** World Management emits `worldSnapshotId` / `world_snapshot_id` as its `roomReadFence` field; Entity Management returns `entitySnapshotId` / `entity_snapshot_id` as the exact participant echo/acknowledgement of that same fence. `roomSnapshotVersion` is a descriptive target-state synonym for the source fence, not a second version or comparison domain. `lookSnapshotId` is a derived composed-view identifier and is not a substitute for `roomReadFence`.
- Scope: the read-fence token is valid only within `(tenantId, gameInstanceId, roomInstanceId)` scope for room-composition APIs such as `LOOK`, and must not be compared across scopes.
- Monotonicity: future tick-ledger-backed values must be non-decreasing for a given scope as observed by a caller; the current live snapshot-id fence is an equality token for same-scope composition.
- Comparison contract:
  - Downstream services must either return a matching same-scope fence, or
  - Fail with `STALE_READ_FENCE` or `READ_FENCE_UNAVAILABLE`; a returned participant fence difference is handled by the composition caller as a fresh-snapshot retry.
- Composition contract: callers must reject mixed-fence payloads; retries must preserve requested scope and fence semantics.

## Short Synchronous Saga Identity

Short synchronous `common-saga` orchestration uses persisted step identity and must be idempotent per step when retries are possible:

- `sagaInstanceId` – identifies a specific synchronous saga execution.
- `sagaStepName` – stable step name within the saga definition.
- `SagaStepGuardKey` – a durable step idempotency key stored by the owning service, built from business identity plus `sagaStepName` and workflow-specific scope; `sagaInstanceId` is execution-trace metadata and must not be the sole dedupe key.

## Temporal Workflow Identity

Durable Temporal workflows use explicit workflow and step identity independent of one JVM lifetime:

- `workflowId` – the canonical durable workflow identity. FireMUD formats it as `<workflowFamily>:<tenantId>:<scopeKey>:<businessKey>`.
- `workflowFamily` – the stable workflow class/family name such as `world-lifecycle`, `publish`, or `script-patch-readiness`.
- `scopeKey` – the narrow business scope for the workflow, such as `world-instance`, `version`, or `game-instance`.
- `businessKey` – the stable request or domain identity that makes workflow start/retry idempotent.
- `businessStepKey` – the durable activity/update-side idempotency key. FireMUD formats it as `<workflowId>#<stepName>#<businessKey>`.

Temporal adopter slices must use these identifiers directly rather than inventing service-local workflow-id formats.

See `design/architecture/system-architecture-ticks.md` and `design/architecture/system-architecture-transactions.md` for the full effect identity contract and replay semantics.
