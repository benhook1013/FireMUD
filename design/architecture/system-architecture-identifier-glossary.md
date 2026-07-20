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
- `worldSlug` is the one globally unique stable public selector for a tenant/game and resolves server-side to `tenantId`; it is not durable tenant identity.
- `realmId` is the opaque UUID identity of a durable player-addressable realm. `realmSlug` is unique within its tenant and resolves to `realmId`.
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
- Continuing the same logical authored object across versions or changing only its storage/wire representation preserves `entityTemplateId`; `versionId` pins the exact representation. Forks, semantically new replacements, scope changes, splits, and merges receive new template IDs and explicit durable mappings under [ADR 0082](decisions/adr-0082-semantic-boundary-for-cross-service-identifier-migration.md).

## Cross-Service Effect Identity

Tick-driven, cross-service mutations are at-least-once and must be idempotent.

- `EffectId` – the canonical root identity derived from region-scoped tick context (`tenantId`, `regionId`, `regionEpoch`, `tickId`, `effectKey`) plus the target aggregate identity. Participant guard identities deterministically project this root with typed operation and target aggregate, and bind it to an immutable request digest and durable outcome. Same guard identity with a different operation, target, or digest is a conflict, not a replay.

## Cross-Service Read Fence Identity

Cross-service read composition distinguishes correctness preconditions from presentation causality:

- Mutation precondition – exact expected room/epoch and relevant location or aggregate version used by an owning service to fail closed on stale correctness-sensitive writes.
- Causal read floor – minimum `(tenantId, gameInstanceId, roomInstanceId, regionEpoch, committedTickId)` requested for presentation composition such as `LOOK`.
- Component version – the actual World or Entity snapshot/version served at or beyond that floor.
- Composite snapshot identity – the requested causal floor plus every component version included in the response.

Components must match tenant, game instance, room, and epoch and must have reached the requested floor. Bounded component skew newer than the floor is allowed for presentation and remains visible in the composite identity. Equality of scope strings is not temporal snapshot equality. Callers reject mixed scope/epoch, a component below the floor, or unavailable version evidence with the bounded read-fence error family.

## Short Synchronous Saga Identity

Short synchronous `common-saga` orchestration uses persisted step identity and must be idempotent per step when retries are possible:

- `sagaInstanceId` – identifies a specific synchronous saga execution and is trace metadata, not retry authority.
- `sagaStepName` – stable step name within the saga definition.
- `sagaStepOccurrenceKey` – deterministic semantic position for a repeated or branched occurrence of the step; attempt, worker, and delivery numbers do not qualify.
- `sagaStepRole` – distinguishes forward work from compensation for the same step occurrence.
- `SagaStepGuardKey` – the durable owning-service guard identity built from the stable workflow/business scope, `sagaStepName`, `sagaStepOccurrenceKey`, and `sagaStepRole`.

The guard record binds that identity to an immutable canonical request digest and recorded outcome. The same guard identity and digest is a replay; the same guard identity with a different digest is a conflict that fails closed.

## Temporal Workflow Identity

Durable Temporal workflows use explicit workflow and step identity independent of one JVM lifetime:

- `workflowId` – the canonical durable workflow identity. FireMUD formats it as `<workflowFamily>:<tenantId>:<scopeKey>:<businessKey>`.
- `workflowFamily` – the stable workflow class/family name such as `world-lifecycle`, `publish`, or `script-patch-readiness`.
- `scopeKey` – the narrow business scope for the workflow, such as `world-instance`, `version`, or `game-instance`.
- `businessKey` – the stable request or domain identity that makes workflow start/retry idempotent.
- `businessStepKey` – the durable activity/update-side guard identity, formed from `workflowId`, stable step name, deterministic occurrence key, and forward/compensation role, and bound in durable storage to an immutable canonical request digest and outcome.

Temporal adopter slices must use these identities directly rather than inventing service-local workflow-id formats. Temporal run IDs, activity attempt IDs, process IDs, and delivery IDs remain trace metadata only. Same-identity same-digest replay returns the guarded outcome; same-identity different-digest reuse fails closed.

See `design/architecture/system-architecture-ticks.md` and `design/architecture/system-architecture-transactions.md` for the full effect identity contract and replay semantics.
