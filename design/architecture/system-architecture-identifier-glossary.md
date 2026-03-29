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

## Entity Identifiers

- `entityId` – identifies a live runtime entity (character, NPC, item instance, container entity) within a tenant.
- `entityTemplateId` – identifies a versioned entity template (items, NPC definitions, equipment templates) scoped by `(tenantId, versionId)`.

## Cross-Service Effect Identity

Tick-driven, cross-service mutations are at-least-once and must be idempotent.

- `EffectId` – the canonical idempotency identity derived from region-scoped tick context (`tenantId`, `regionId`, `regionEpoch`, `tickId`, `effectKey`) plus the target aggregate identity. All services participating in a tick-driven effect must use projections of the same `EffectId` for idempotency guards and reconciliation.

## Cross-Service Read Fence Identity

Cross-service read composition (for example `LOOK` world + entity joins) must use a shared fence token to prevent mixed-tick snapshots:

- `asOfTickId` – canonical read-fence token emitted by the authoritative source read (typically World Management snapshot APIs), then propagated unchanged to downstream participant reads.
- Scope: `asOfTickId` is valid only within `(tenantId, gameInstanceId, roomInstanceId)` scope for room-composition APIs such as `LOOK`, and must not be compared across scopes.
- Monotonicity: values must be non-decreasing for a given scope as observed by a caller.
- Comparison contract:
  - Downstream services must either serve data materialized at the same `asOfTickId`, or
  - Fail with `STALE_READ_FENCE` or `READ_FENCE_UNAVAILABLE`.
- Composition contract: callers must reject mixed-fence payloads; retries must preserve requested scope and fence semantics.

## Saga Workflow Identity

Long-running, cross-service workflows (publish, world creation) use sagas and must be idempotent per step:

- `sagaInstanceId` – identifies a specific saga execution.
- `sagaStepName` – stable step name within the saga definition.
- `SagaStepGuardKey` – a durable step idempotency key stored by the owning service, built from business identity plus `sagaStepName` and workflow-specific scope (for example `(tenantId, gameInstanceId, worldCreationRequestId, sagaStepName)`); `sagaInstanceId` is execution-trace metadata and must not be the sole dedupe key.

See `design/architecture/system-architecture-ticks.md` and `design/architecture/system-architecture-transactions.md` for the full effect identity contract and replay semantics.
