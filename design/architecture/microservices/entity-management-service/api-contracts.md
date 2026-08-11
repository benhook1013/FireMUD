# Entity Management Service API Contracts

This document defines the Entity Management REST and gRPC surfaces, design-time APIs, digest contract, and the LOOK entity-listing contract.

The authoritative REST schema source lives in [../../../../services/entity-management-service/src/main/resources/openapi.yaml](../../../../services/entity-management-service/src/main/resources/openapi.yaml). Proto definitions are the authoritative gRPC source.

## Implementation Status

`ApplyActorCondition` has not yet converged on the target replay contract below. Its current request has no dedicated `effectId`; Game Session places the operation-unique durable effect ID in required `sourceId`, and Entity Management uses `{tenantId, sourceId}` for replay. That temporary implementation conflates replay identity with authored/source provenance and does not compare the complete condition payload on same-operation reuse. The proto, callers, persistence, and proof must migrate together before additional producers reuse one source across applications.

## REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

## gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in `entity_management_service.proto`.
- `CreateCharacter(CreateCharacterRequest) returns (CreateCharacterResponse)` – internal character-persistence authority invoked by the Account-owned bootstrap character-creation facade after Account has authorized the caller and realm target. Entity Management does not expose a direct player REST route.
- `UpdateEntity(UpdateEntityRequest) returns (UpdateEntityResponse)` – updates mutable character state for the requested `{tenantId, gameInstanceId, playableStateScope, characterId}` target. The service must reject missing or mismatched playable-state scope instead of updating by global character id alone.
- `QueryInventory(QueryInventoryRequest) returns (QueryInventoryResponse)` – lists items for an entity with pagination. Current responses include `item_instance_id` and `visible_ref` for concrete item instances; quantity is present in the proto for forward compatibility, but stack merge behavior is still a later `06.3.2` follow-up.
- `ListCharactersByAccount` – returns all characters owned by an account for the requested `{tenantId, gameInstanceId, playableStateScope}` gameplay target. Each returned `Character` now echoes the resolved `playableStateScope` so later consumers do not have to infer realm policy from the request or from hidden storage-key conventions.
- `QueryActorState` – returns gameplay-attested actor resources and active conditions for a scoped character.
- `ApplyActorCondition` – applies a gameplay-attested active condition or transient action state for a scoped character. The canonical request carries a dedicated operation-unique `effectId`, separate source provenance, optional expiry, and the same internal effect payload JSON shape consumed by `QueryActorState`.
- `ListRoomEntities(ListRoomEntitiesRequest) returns (ListRoomEntitiesResponse)` – returns players, NPCs, and visible items present in a room, scoped by `RoomInstanceRef`.
- `GetDraftDesignDigest` – returns publish-gating digest for Draft entity templates using typed scope request `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`. Entity Management supports `versionId` scope only and must return `UNSUPPORTED_SCOPE` for `scriptPatchVersion`. Minimum response fields are `{tenantId, scope, appliedCommitId, contentDigest, digestSchemaVersion}`. `appliedCommitId` means the highest Game Design commit whose full revision set has been durably applied to the target Draft entity scope. `contentDigest` must cover only version-scoped entity template/binding rows and must exclude live runtime entities and audit/history metadata.

Target-state gameplay mutation RPCs that change item, equipment, or container state accept the canonical `effectId` and its complete structured identity supplied by Game Session durable effect execution. Entity Management validates the `{tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId}` projection before applying the mutation; `{tenantId, effectId}` alone is not a sufficient substitute for the scoped identity. `ApplyActorCondition` requires a dedicated `effectId` as the operation-level replay identity, while `sourceId` remains separate authored/source provenance. Duplicate delivery returns the stored applied/no-op response instead of applying the mutation again. Read-only query RPCs do not require an `effectId`. Current live mutation surfaces may still expose an optional or narrower effect field; that is an implementation gap, not permission to define a competing idempotency contract.

For `DROP` and `PICKUP`, the Entity-local containment transaction accepts World `TargetingFactSnapshot` location/version evidence only through an attestation bound to the same root `EffectId`, `actorEntityId`, `RoomInstanceRef`, `regionEpoch`, `executorFence`, and immutable `requestDigest`. Entity verifies that binding and its participant guard at local commit before changing the item holder; a bare location/version token or mismatched attestation is rejected without a holder mutation. Game Session owns the actor-lock/barrier and retry orchestration and invokes Game Logic to re-resolve stale evidence under the same root `EffectId`, preserving the `requestDigest`; a changed request is rejected rather than reusing the root. The current proto/request and focused proof do not yet carry or demonstrate this attestation path, so they do not claim the target contract.

```bash
grpcurl -plaintext localhost:6565 entity_management.v1.EntityManagementService/Ping
```

## Design-Time APIs

Entity Management also exposes design-time APIs used by the Game Design Service to write Draft template rows keyed by `(tenantId, versionId)` (for example item/NPC templates, loot tables, and balancing records).

- Auth: design-time APIs must validate JWTs and enforce designer/admin authorization for the target `tenantId`.
- Mutability: design-time writes are allowed only for Draft versions; attempts to write templates for Published/Active/Failed versions must fail fast.
- Runtime isolation: runtime gameplay flows and tick-driven handlers must never call design APIs.

Entity Management must also expose a read-only design-time synchronization surface so the Game Design Service can validate convergence before publish:

- `GetDraftDesignDigest(GetDraftDesignDigestRequest)` uses request shape `{tenantId, scope: oneof {versionId, scriptPatchVersion}}`. Entity Management supports `versionId` scope only and returns `UNSUPPORTED_SCOPE` otherwise.
- Response returns `{tenantId, scope, appliedCommitId, contentDigest, digestSchemaVersion}` as described in [`world-editing-tools.md`](../game-design-service/world-editing-tools.md).

## Digest Input Manifest

Entity Management is a required publish-gate participant and must maintain a stable digest manifest for `GetDraftDesignDigest(versionId)`:

Implementation Notes:

- The current implementation hashes the version-scoped entity-definition rows for the requested `(tenantId, versionId)` and returns synthetic `appliedCommitId = "version:<versionId>"` until the later applied-revision ledger lands.
- Current version-scoped digest inputs include `items`, `npcs`, and `crafting_recipes`; later entity-template families must join this same `(tenantId, versionId)` digest contract when introduced.

- Included objects:
  - version-scoped entity-template tables such as item, NPC, equipment, loot-table, and balance-curve definitions keyed by `(tenantId, versionId)`;
  - normalized template-binding rows that affect published entity semantics, such as loot mappings or equipment/archetype constraints.
- Excluded objects:
  - all live runtime entities, inventories, containers, room-ground containers, and any rows keyed by `gameInstanceId` or `entityId`;
  - audit/history/provenance tables and non-semantic timestamps;
  - applied-revision ledgers when those rows do not affect entity semantics.
- Canonicalization rules:
  - serialize included relations in stable table order, then primary-key order;
  - include only semantic fields plus stable identifiers referenced cross-service;
  - normalize encoded structured fields before hashing.
- `digestSchemaVersion` must increment whenever included objects, semantic field selection, or serialization semantics change.

Publish gating must fail closed if Entity Management cannot attest a digest consistent with this manifest.

## LOOK Entity Listing Contract

`ListRoomEntities` is the dedicated endpoint for `LOOK` to discover which characters, items, and NPCs occupy a room. The current and target causal-read contracts are intentionally separate. The causal-floor shape and component-proof identity follow the [Identifier Glossary causal-read fence contract](../../system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity); the current transport names `worldSnapshotId` and `entitySnapshotId` are deterministic same-scope markers only, not independent freshness versions.

### Current room-entity contract

The current `ListRoomEntitiesRequest` carries `tenantId`, `RoomInstanceRef`, and `sessionAttestation`; it is floor-free and does not carry a caller-provided read-fence field. The current `entitySnapshotId` response value is a deterministic room-scope marker derived from the request scope, not proof that Entity Management observed a committed mutation version. The current adapter therefore does not claim exact caller-fence satisfaction.

The current response includes:

- `tenantId`, `gameInstanceId`, and `roomInstanceId` (a `RoomInstanceRef`) so consumers can unambiguously scope the entity list to a running instance.
- `entitySnapshotId` so consumers can identify the room scope in responses. In the current adapter this is only the scope marker described above and is not a mutation-freshness or invalidation proof.
- `entities[]`, each with `entityId`, `displayName`, `entityType` (`PLAYER`, `NPC`, `ITEM`), and optional `role`/`affiliation`.
- `stateFlags` such as `isHidden`, `isInCombat`, or `isQuestTarget` so Game Logic can mask stealthy entities or highlight objectives.
- `visionPriority` to help sort players before NPCs and list visible items at the end, keeping `LOOK` render ordering consistent.
- `reloadHint` (enum) that signals whether the list is stable or dynamic, allowing Game Logic to decorate the `LOOK` output.

### Target causal-floor contract

The target `ListRoomEntities` request carries the same `CausalReadFence` as World Management for the `RoomInstanceRef` and `regionEpoch`, including at least `committedTickId`. Game Session allocates that floor from durable region commit authority when it invokes `ResolveLook`; Game Logic propagates the unchanged floor to Entity Management. The exact request-field shape remains deferred to the coordinated proto/design change; the current request remains floor-free and does not claim floor satisfaction.

After that protocol exists, Entity Management serves the same scope and epoch and returns a scoped `servedThroughTickId` plus an opaque Entity component version. Game Logic accepts the participant when `servedThroughTickId >=` the requested `committedTickId`; a behind-floor or mixed tenant, game instance, room, or epoch response is rejected or retried. Opaque component versions are not directly compared. Game Logic validates the served-through proof, then composes only the requested floor plus the World and Entity component versions in the room-view identity and returns that identity with `LookResult` so Game Session can retain its transcript rendering/cache behavior; it does not claim an exact cross-database historical snapshot.

Room-entity data is derived from runtime entity state plus authoritative world location. Ground items are discovered by querying items contained by the synthetic room-ground container for the target `RoomInstanceRef`. Characters and NPCs are included when their current location (owned by World Management) matches the target `RoomInstanceRef`:

- Game Session obtains the causal floor from durable region commit authority and passes it on `ResolveLook`; Game Logic propagates that floor unchanged to `ListRoomEntities`. The target request/response evolution must carry enough information for Entity Management to prove service of that floor; the current proto/request path does not yet carry the floor or served-through proof and does not claim this behavior complete.
- `ListRoomEntities` materializes display data plus room-ground inventory state owned by Entity Management for the same `RoomInstanceRef`.
- `ListRoomEntities` must return an opaque Entity component version plus scoped `servedThroughTickId` after serving the requested floor. Game Logic accepts same-scope/epoch `servedThroughTickId >= committedTickId`, rejects or retries behind-floor or mixed-scope/epoch responses, and does not directly compare opaque component versions.
- The causal floor is satisfied only by durable post-commit state. Redis-staged containment changes that have not yet committed the effect guard and container/item row updates for the requested floor are not eligible to satisfy it.

Illustrative target-state `ListRoomEntities` fragments:

- Success:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "gameInstanceId": "9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78",
  "regionEpoch": 17,
  "roomInstanceId": "1021",
  "servedThroughTickId": 42,
  "entityComponentVersion": "entity-component-version-23",
  "entities": [
    {
      "entityId": "char-mara",
      "displayName": "Mara",
      "entityType": "PLAYER"
    }
  ]
}
```

- Requested fence cannot be satisfied:

```json
{
  "error": {
    "code": "STALE_READ_FENCE",
    "message": "Entity state could not satisfy the requested causal read floor."
  }
}
```

Entity Management must not maintain a competing room-occupancy index that can drift from World Management’s location tables. Visibility and filtering rules are applied after aggregation so LOOK output remains player-correct.

In the target protocol, when the propagated causal floor is missing, stale, or cannot be satisfied from durable post-commit state, Entity Management returns `STALE_READ_FENCE` or `READ_FENCE_UNAVAILABLE`. A behind-floor or mixed-scope/epoch response is a caller-side composition retry condition, not a separate service error from this API: Game Session must obtain a fresh durable region-commit floor, pass it through `ResolveLook`, and have Game Logic retry the same-scope composition, or fail the room view explicitly if the fresh floor cannot be materialized. These target errors are not claims about the current request path.

The unresolved target work is tracked in [World Runtime and Movement](../../../project-management/implementation-tracking/world-runtime-and-movement.md#active-gaps): Game Session floor allocation and Game Logic propagation are target obligations, while Entity participant floor satisfaction and the opaque component-version plus scoped `servedThroughTickId` response remain unimplemented and unproved. The current proto and focused proof do not cover this path; the exact proof encoding and any remaining wire details are implementation gaps, not a competing contract.

Concrete per-effect required writes and reconciliation rules live in [`system-architecture-spatial-and-ambient-effects-catalog.md`](../../system-architecture-spatial-and-ambient-effects-catalog.md).

Cross-service retry orchestration is owned by the Game Session Service reconciliation backlog described in [Transaction Strategies](../../system-architecture-transactions.md#reconciliation-owner-of-record-spatialambient-effects). Entity Management must expose participant acknowledgements for each `EffectId`; it is not the owner of cross-service retry scheduling.

Only entities approved by the `EntityVisibilityPolicy` are returned; hidden NPCs, private inventory, or offstage summons are filtered out so `LOOK` always aligns with the player’s perspective. The response deliberately omits detailed stats to keep the text output focused on presence rather than numbers.

## Actor State Query

`QueryActorState` is the gameplay-facing read contract for current actor resources and active conditions. Callers must present typed `PlayerExecutionContext`, tenant id, character id, game instance id, and the resolved playable-state scope. Entity Management validates the concrete mTLS caller and method allowlist, context/request equality, and scoped character relationship, then emits baseline character stat resources, overlays persisted `actor_resource_states` rows, applies active condition and equipped-item `effect_payload_json` modifiers through the shared effect evaluator, and returns active `actor_active_conditions` rows whose expiry has not passed. The response is transport-neutral state data for Game Logic and Game Session consumers; presentation layers must not infer authoritative gameplay state from transcript text.

`ApplyActorCondition` is the first mutation contract for the same actor-state table. It validates the concrete mTLS caller and method allowlist, `PlayerExecutionContext`, and playable-state scope before creating an `actor_active_conditions` row with `conditionKey`, `stackCount`, `sourceType`, `sourceId`, optional `expiresAt`, and optional `effectPayloadJson`. Its dedicated `effectId` is the operation-level replay identity under `{tenantId, effectId}`, while `sourceId` remains authored/source provenance and may legitimately recur across separate applications. A completed duplicate with the same canonical request payload returns the original serialized `ApplyActorConditionResponse` without another condition mutation; an in-progress duplicate returns conflict, payload or operation reuse returns invalid argument, and an unreadable stored response fails internally. This is intended for gameplay-owned actions such as the first transient `blocking` state as well as later spells, consumables, and scripted effects. Reads ignore expired rows and the scheduled expiry sweep removes elapsed rows; callers must still treat the mutation response as the authoritative applied state for that request.

Condition and item-template effect payloads are an internal persisted contract in this first slice. They may contain a top-level `modifiers` array whose entries include `operation`, `target_key`, `value`, optional `scope_kind`, optional `scope_key`, and optional `priority`; supported operations currently match the shared evaluator primitives: `ADD`, `MULTIPLY`, `CLAMP_MIN`, `CLAMP_MAX`, `GRANT_FLAG`, and `GRANT_CONDITION`.

The current contract still leaves player command orchestration, authored stat/condition definitions, richer action-state policies, and combat resolution to later stats/effect slices.

## Implementation Status (LOOK Slice)

- **Live:** The seeded `firemud.look.rooms` entries provide the visible entities for the demo rooms, `ListRoomEntities` is wired into Game Logic's `ResolveLook`, and the resulting instrumentation is captured in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed:** Real-time behaviors such as item respawns, stealth/aura-driven visibility, and inventory states still rely on static fixtures so regression tests remain reproducible.
- **Deferred:** Future slices will catalog metadata from the `character_location` and `npc_location` tables, support multi-instance visibility rules, and surface richer context hints (combat alerts, quest markers) while keeping the public DTO focused on display data.
