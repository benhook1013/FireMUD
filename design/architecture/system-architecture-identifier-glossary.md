# FireMUD System Architecture: Identifier Glossary

This document defines canonical identifier names and scopes used across FireMUD service designs. When other documents use ambiguous terms like `roomId`, treat this glossary as the tie-breaker and update the doc to use the appropriate scoped identifier.

## Core Identifiers

- `accountId` – identifies a platform account. Present on authentication/session records and account-owned domain relationships.
- `tenantId` – identifies the game/tenant. Present on all persistent domain tables and all cross-service APIs.
- `versionId` – identifies a design bundle/version for a tenant. Domain service template data is scoped by `(tenantId, versionId)`.
- `gameInstanceId` – identifies a running game instance for a tenant. Domain service runtime/instance data is scoped by `(tenantId, gameInstanceId)` and references the instance’s pinned `runtime_version`/`versionId`.
- `characterId` – identifies a character owned within a tenant. Gameplay session binding and any instance-local playable state use it together with `{tenantId, gameInstanceId}` scope.

## Identifier Format Conventions

- `accountId`, `tenantId`, `versionId`, `gameInstanceId`, and `characterId` are canonical UUID string logical identifiers. Authored template identifiers are also client-allocatable UUID strings.
- Services treat these identifiers as opaque values unless a contract specifically requires UUID-shape validation. Consumers must not derive authority, routing, or related identifiers from their contents.
- Services may maintain numeric primary and join keys internally. Private database keys never replace or appear as reversible encodings of the canonical UUID identity in public or cross-service contracts.
- Live room, entity, and item instance identifiers are the deliberate exception: they may be stable numbers allocated within `(tenantId, gameInstanceId)` when the owning runtime guarantees concurrency safety and non-reuse for the required lifetime.
- `tenantSlug` is a stable human-readable selector used only in player-facing lobby flows and resolved server-side to `tenantId`; it is not durable tenant identity.
- Identifier values are never authorization credentials. Every lookup validates the complete tenant/runtime scope and caller authority even when the ID is globally unique or difficult to guess.

Security, idempotency, command, event, effect, workflow-request, and correlation identifiers retain their separate high-entropy or collision-resistance contracts. The family-specific resource-ID rule does not permit predictable session/token material or retry identities that can collide within their required scope.

## World Identifiers

World data uses two distinct identifier families. Template identifiers must not be used in runtime APIs, and instance identifiers must not appear in design-time template graphs.

- **Template identifiers (design-time, version-scoped)** – keyed by `(tenantId, versionId)`:
  - `regionTemplateId`, `zoneTemplateId`, `roomTemplateId`
  - `RoomTemplateRef` = `(tenantId, versionId, roomTemplateId)`
  - template IDs are client-allocatable UUID logical identities, allowing independently created authored graph objects to reference one another before persistence
- **Instance identifiers (runtime, instance-scoped)** – keyed by `(tenantId, gameInstanceId)`:
  - `regionInstanceId`, `zoneInstanceId`, `roomInstanceId`
  - `RoomInstanceRef` = `(tenantId, gameInstanceId, roomInstanceId)`
  - `roomInstanceId` is the canonical cross-service runtime room identity. It may be numeric, but callers use it only inside the complete typed scope and must not infer storage location or authorization from its value.

World Management may use a numeric room row key as `roomInstanceId` only when it guarantees scoped stability, concurrency-safe allocation, and non-reuse for the required runtime lifetime. Otherwise it allocates a separate runtime identity and names internal topology keys `roomInstanceRowId` / `roomInstanceDbId`. Undocumented reversible encodings such as `R-<rowId>` are not canonical identities.

## Entity Identifiers

- `entityId` – identifies a live runtime entity (character, NPC, item instance, container entity) within `(tenantId, gameInstanceId)` and may be a stable scoped number.
- `entityTemplateId` – identifies a versioned entity template (items, NPC definitions, equipment templates) scoped by `(tenantId, versionId)` and is a UUID logical identity.
- Stable numeric `entityId` values may be presented to authorized players as disambiguators and accepted in gameplay commands. Resolution still enforces tenant, game-instance, location/visibility, and authorization scope.
- Continuing the same logical authored object across versions may preserve `entityTemplateId`; `versionId` pins the exact representation. Forks and semantically new replacements receive new template IDs and use explicit mappings when state migration is intended.

## Cross-Service Effect Identity

Tick-driven, cross-service mutations are at-least-once and must be idempotent.

- `EffectId` – the canonical idempotency identity derived from region-scoped tick context (`tenantId`, `regionId`, `regionEpoch`, `tickId`, `effectKey`) plus the target aggregate identity. All services participating in a tick-driven effect must use projections of the same `EffectId` for idempotency guards and reconciliation.

## Cross-Service Read Fence Identity

Cross-service read composition (for example `LOOK` world + entity joins) must use a shared fence token to prevent mixed-tick snapshots:

- Read-fence token – canonical room-read fence emitted by the authoritative source read, currently World Management `worldSnapshotId` / `world_snapshot_id`, and compared with the participant fence returned by Entity Management as `entitySnapshotId` / `entity_snapshot_id`.
- Scope: the read-fence token is valid only within `(tenantId, gameInstanceId, roomInstanceId)` scope for room-composition APIs such as `LOOK`, and must not be compared across scopes.
- Monotonicity: future tick-ledger-backed values must be non-decreasing for a given scope as observed by a caller; the current live snapshot-id fence is an equality token for same-scope composition.
- Comparison contract:
  - Downstream services must either return a matching same-scope fence, or
  - Fail with `READ_FENCE_MISMATCH`, `STALE_READ_FENCE`, or `READ_FENCE_UNAVAILABLE`.
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
