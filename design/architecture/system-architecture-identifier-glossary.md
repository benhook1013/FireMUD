# FireMUD System Architecture: Identifier Glossary

This document defines canonical identifier names and scopes used across FireMUD service designs. When other documents use ambiguous terms like `roomId`, treat this glossary as the tie-breaker and update the doc to use the appropriate scoped identifier.

Scripting identity terms are defined here only as identifiers: [Scripting Contracts](./system-architecture-scripting-contracts.md) owns their execution semantics, [Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md) owns transition semantics, and [DSL Reference and Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md) owns artifact/lifecycle distinctions.

## Implementation Status

The UUID target applies only to UUID-governed logical identifiers; it is not a blanket requirement that every runtime or operational identifier be a UUID. That target has not fully converged: several current REST DTOs, OpenAPI schemas, database-facing service contracts, and persisted rows still expose numeric account, tenant, version, game-instance, character, or template IDs. Existing gRPC strings and architecture examples must carry canonical UUID values for UUID-governed logical identifiers rather than decimal strings or mnemonic placeholders, but that does not make the remaining schema and persistence migration complete. Typed scoped-numeric runtime room, entity, and item-instance identifiers remain valid inside their complete scope.

## Core Identifiers

- `accountId` – identifies a platform account. Present on authentication/session records and account-owned domain relationships.
- `tenantId` – identifies the game/tenant. Present on all persistent domain tables and all cross-service APIs.
- `versionId` – identifies a design bundle/version for a tenant. Domain service template data is scoped by `(tenantId, versionId)`.
- `gameInstanceId` – identifies a running game instance for a tenant. Domain service runtime/instance data is scoped by `(tenantId, gameInstanceId)` and references the instance’s pinned `runtime_version`/`versionId`.
- `scriptPatchVersion` – identifies an immutable embedded-script patch independently of instance selection. It pairs with Game Session's per-instance `scriptPinEpoch` in the exact instance-bound tuple. Tenant-readiness `onLoad` may use a declared candidate patch in its pre-instance-pin identity.
- `scriptPinEpoch` – identifies Game Session's per-instance pin-selection epoch. It pairs with `scriptPatchVersion` in the exact instance-bound tuple; tenant-readiness `onLoad` omits it because no instance pin exists.
- `regionId` – identifies an operational tick region within `(tenantId, gameInstanceId)`. It is an opaque runtime-coordination identity, not a World Management row ID, design-time region template ID, room ID, or slug. The complete region scope is `{tenantId, gameInstanceId, regionId}`.
- `characterId` – identifies a character owned within a tenant. Gameplay session binding and any instance-local playable state use it together with `{tenantId, gameInstanceId}` scope.

## Identifier Format Conventions

- `accountId`, `tenantId`, `versionId`, `gameInstanceId`, and `characterId` are UUID-governed canonical UUID string logical identifiers. Authored template identifiers are also client-allocatable UUID strings in that logical-identifier family.
- Public HTTP/gRPC ingress and cross-service readers of a UUID-governed identifier must reject a missing required value, blank value, malformed value, or non-canonical UUID text at the boundary before authorization, lookup, routing, or persistence; an optional field may be omitted but must be validated when supplied. This is shape validation only: after validation, services treat the value as opaque and must not derive authority, routing, tenant membership, or related identifiers from UUID contents. UUID version or bit patterns do not grant authority.
- Where an existing implementation contract is still explicitly numeric, that contract remains a documented migration gap and must validate its declared numeric shape until the UUID contract is adopted; this rule does not silently change current DTOs or database schemas.
- Services may maintain numeric primary and join keys internally. Private database keys never replace or appear as reversible encodings of the canonical UUID identity in public or cross-service contracts.
- `regionInstanceId` and `zoneInstanceId` remain UUID-governed runtime identifiers. Live `roomInstanceId`, `entityId`, and `itemInstanceId` are deliberate typed scoped-numeric runtime identifiers: they may be stable numbers allocated within `(tenantId, gameInstanceId)` when the owning runtime guarantees concurrency safety and non-reuse for the required lifetime. They are not interchangeable with one another or with a UUID-governed logical identifier. `itemInstanceId` is the distinct concrete-item identity used by containment and equipment contracts; it is not an alias for `entityId` or `entityTemplateId`. Extending that exception to region or zone instances requires a future accepted architecture decision.
- Redis and other operational key builders use `tenantRegionTag` as the canonical normalized hash-tag projection of the complete `{tenantId, gameInstanceId, regionId}` scope. A region tag must include the game-instance dimension; a bare `{tenantId, regionId}` projection is not collision-safe when two instances use the same region ID.
- `tenantSlug` is a stable human-readable selector used only in player-facing lobby flows and resolved server-side to `tenantId`; it is not durable tenant identity.
- `worldSlug` is a stable authored-world selector scoped by `tenantId`. Realm catalog, admission-pointer, and lobby-routing contracts use it together with `realmSlug`; it is not an alias for `tenantSlug` and is never resolved without tenant scope.
- Identifier values are never authorization credentials. Every lookup validates the complete tenant/runtime scope and caller authority even when the ID is globally unique or difficult to guess.

Security, idempotency, command, event, effect, workflow-request, and correlation identifiers retain their separate high-entropy or collision-resistance contracts. The family-specific resource-ID rule does not permit predictable session/token material or retry identities that can collide within their required scope.

## World Identifiers

World data uses two distinct identifier families. Template identifiers must not be used in runtime APIs, and instance identifiers must not appear in design-time template graphs.

- **Template identifiers (design-time, version-scoped)** – keyed by `(tenantId, versionId)`:
  - `regionTemplateId`, `zoneTemplateId`, `roomTemplateId`
  - `RoomTemplateRef` = `(tenantId, versionId, roomTemplateId)`
  - template IDs are client-allocatable UUID logical identities, allowing independently created authored graph objects to reference one another before persistence
- **Instance identifiers (runtime, instance-scoped)** – keyed by `(tenantId, gameInstanceId)`:
  - `regionInstanceId`, `zoneInstanceId` (UUID-governed unless a future accepted decision extends the scoped numeric exception), `roomInstanceId`
  - `RoomInstanceRef` = `(tenantId, gameInstanceId, roomInstanceId)`
  - `roomInstanceId` is the canonical cross-service runtime room identity. It may be numeric, but callers use it only inside the complete typed scope and must not infer storage location or authorization from its value.

World Management may use a numeric room row key as `roomInstanceId` only when it guarantees scoped stability, concurrency-safe allocation, and non-reuse for the required runtime lifetime. Otherwise it allocates a separate runtime identity and names internal topology keys `roomInstanceRowId` / `roomInstanceDbId`. Undocumented reversible encodings such as `R-<rowId>` are not canonical identities.

## Entity Identifiers

- `entityId` – identifies a live runtime entity (character, NPC, or container entity) within `(tenantId, gameInstanceId)` and may be a stable scoped number. Room-presence surfaces may present an item as an entity, but that presentation identity does not replace the typed `itemInstanceId` used by containment and equipment contracts.
- `itemInstanceId` – identifies a concrete live item instance within `(tenantId, gameInstanceId)`. It is distinct from the item's `entityTemplateId` and from any `entityId` used by a generic runtime-entity surface, and may be a stable scoped number under the same allocation, stability, and non-reuse guarantees.
- `entityTemplateId` – identifies a versioned entity template (items, NPC definitions, equipment templates) scoped by `(tenantId, versionId)` and is a UUID logical identity.
- Stable numeric `entityId` values may be presented to authorized players as disambiguators and accepted in gameplay commands. Resolution still enforces tenant, game-instance, location/visibility, and authorization scope.
- Continuing the same logical authored object across versions may preserve `entityTemplateId`; `versionId` pins the exact representation. Forks and semantically new replacements receive new template IDs and use explicit mappings when state migration is intended.

## Cross-Service Effect Identity

Tick-driven, cross-service mutations are at-least-once and must be idempotent.

- `EffectId` – the stable root identity for one logical effect. Game Session assigns it once from the admitted operation and its authoritative tick context; ordinary retry, recovery, and replay preserve it. It is not derived from mutable payload or regenerated for a replay. A fresh root is reserved for a **post-abandon re-drive**: the original effect must be conclusively terminal `ABANDONED` with its source claim terminalized, then recovery allocates a later coordinate, fresh root, new retry/source identity, and durable lineage. An implementation may store it opaquely only when the canonical identity projection remains retained or deterministically recoverable.
- **Participant guard identity** – a deterministic uniqueness identity derived from exactly the root `EffectId`, typed operation, and target aggregate. Its durable guard binds that identity to the immutable request digest; the durable outcome and other evidence/reconciliation fields are mutable guard-row state protected by CAS, not part of uniqueness identity. Same guard identity with the same request returns the prior result; a changed operation, target, or digest fails closed. Derived reactions receive deterministic child `EffectId` values.
- The root effect identity, required participant set, and participant outcomes are Game Session reconciliation data. The durable guard/effect behavior is owned by [Transaction Strategies](./system-architecture-transactions.md) and must not be replaced with an ad-hoc service-local key.

## Cross-Service Causal-Read Fence Identity

Cross-service presentation composition (for example `LOOK`) uses a causal floor; it does not claim a globally atomic historical snapshot.

### Current Scope-Marker Contract

- The current live proto seam carries the room-scope correlation value as World Management `worldSnapshotId` / `world_snapshot_id` and Entity Management `entitySnapshotId` / `entity_snapshot_id`.
- Current World Management and Entity Management requests are floor-free: their adapters derive those values from `(tenantId, gameInstanceId, roomInstanceId)` alone. They are deterministic same-scope markers that may compare equal, but they do not prove mutation freshness, committed ordering, or a durable read fence.
- Target Game Session allocation/propagation of a `CausalReadFence` and participant `servedThroughTickId` proof are not implemented in the current request/proto path. Current marker equality must not be described as complete target-state behavior.

### Target Causal-Floor Contract

- `CausalReadFence` identifies the requested floor: at least `(tenantId, gameInstanceId, regionId, roomInstanceId, regionEpoch, committedTickId)`. `regionId` is the operational tick-region identity obtained from Game Session's durable region commit authority; it is carried explicitly and is not inferred from a World Management row identifier. The fence is valid only in that complete scope and is never a claim that World and Entity served an exact same-time snapshot.
- A participant response includes the same complete scope, including `regionId` and `regionEpoch`, a scoped comparable `servedThroughTickId`, and an opaque local component version. Game Logic accepts the response only when the returned region/scope/epoch exactly matches the requested floor and `servedThroughTickId >= committedTickId`; it returns a composed-view identity containing the requested floor plus the World and Entity component versions, but must not compare the opaque component-version values themselves.
- Game Session allocates the fence from one Game Session-owned durable region-status snapshot after validating the current authority. That snapshot supplies the operational `regionId`, `regionEpoch`, and `lastCommittedTickId`; `committedTickId` is that last committed value. An accepted epoch starts with `lastCommittedTickId = -1` before tick `0`, and allocator state is never carried across an epoch boundary. The existing [RegionStatus timeline contract](./system-architecture-ticks.md#bootstrap-vs-stream-authoritative-timeline-source) defines this source; no additional allocator API or record is implied.
- Each opaque participant component version is scoped to its complete owner-defined runtime scope and current epoch, remains stable while the represented durable component state is unchanged, and changes atomically with each relevant durable owner commit. A matching replay or no-op returns the stored version without incrementing it, and a version is never reused for different state in that scope and epoch. If a related cache survives an owner restart, its component version must survive with the durable state; otherwise the cache is invalidated before it can serve.
- Mixed tenant, game instance, region, room, or epoch, or a response whose `servedThroughTickId` is behind the floor, is rejected or retried. No numeric ordering, newer-skew unit, or newer-skew maximum is assigned to opaque component versions. Exact read-as-of semantics require a separate historical-snapshot design.
- The current `worldSnapshotId` / `entitySnapshotId` values remain scope markers only; they are not the target causal floor or component-version contract.

## Short Synchronous Saga Identity

Short synchronous `common-saga` orchestration uses persisted step identity and must be idempotent per step when retries are possible:

- The general workflow/request and step-guard semantics are owned by [Transaction Strategies](./system-architecture-transactions.md) and [ADR 0078](./decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md); this glossary keeps only the identity vocabulary and local reference.
- `sagaInstanceId` – identifies a specific synchronous saga execution.
- `sagaStepName` – stable step name within the saga definition.
- `SagaStepGuardKey` – the owning service’s local durable step-guard reference; its identity fields and immutable-digest binding follow [Transaction Strategies](./system-architecture-transactions.md) rather than this glossary. `sagaInstanceId` is execution-trace metadata and must not be the sole dedupe key.

## Temporal Workflow Identity

Durable Temporal workflows use explicit workflow and step identity independent of one JVM lifetime:

- The general workflow/request and step-retry semantics are owned by [Transaction Strategies](./system-architecture-transactions.md), with the rationale in [ADR 0078](./decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md). This section retains concise vocabulary and the Temporal-local encoding reference; adopter-specific usage and proof remain in adopter docs and trackers.
- `workflowId` – the canonical durable workflow identity. FireMUD formats it as `<workflowFamily>:<tenantId>:<scopeKey>:<businessKey>`.
- `workflowFamily` – the stable workflow class/family name such as `world-lifecycle`, `publish`, or `script-patch-readiness`.
- `scopeKey` – the narrow business scope for the workflow, such as `world-instance`, `version`, or `game-instance`.
- `businessKey` – the stable request or domain identity that makes workflow start/retry idempotent.
- `businessStepKey` – the current legacy `FiremudWorkflowIds` activity/update-side key projection, formatted as `<workflowId>#<stepName>#<businessKey>`. It is an incomplete implementation and not the canonical full guard; target local encoding includes a stable step name, deterministic occurrence key, and execution role, with the immutable request digest stored and compared under the shared contract.

Temporal adopter slices must use the workflow identity and local encoding reference above rather than inventing service-local workflow-ID formats, while treating the legacy `businessStepKey` projection as an implementation gap until the full guard is adopted.

See [Transaction Strategies](./system-architecture-transactions.md) and [ADR 0078](./decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md) for the full workflow/step identity and replay semantics. See `design/architecture/system-architecture-ticks.md` for the separate gameplay effect identity contract.
