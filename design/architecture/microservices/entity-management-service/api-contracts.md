# Entity Management Service API Contracts

This document defines the Entity Management REST and gRPC surfaces, design-time APIs, digest contract, and the LOOK entity-listing contract.

The authoritative REST schema source lives in [../../../../services/entity-management-service/src/main/resources/openapi.yaml](../../../../services/entity-management-service/src/main/resources/openapi.yaml). Proto definitions are the authoritative gRPC source.

## REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

## gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in `entity_management_service.proto`.
- `CreateCharacter(CreateCharacterRequest) returns (CreateCharacterResponse)` – builds a new player character from a template.
- `UpdateEntity(UpdateEntityRequest) returns (UpdateEntityResponse)` – updates mutable character state for the requested `{tenantId, gameInstanceId, playableStateScope, characterId}` target. The service must reject missing or mismatched playable-state scope instead of updating by global character id alone.
- `QueryInventory(QueryInventoryRequest) returns (QueryInventoryResponse)` – lists items for an entity with pagination. Current responses include `item_instance_id` and `visible_ref` for concrete item instances; quantity is present in the proto for forward compatibility, but stack merge behavior is still a later `06.3.2` follow-up.
- `ListCharactersByAccount` – returns all characters owned by an account for the requested `{tenantId, gameInstanceId, playableStateScope}` gameplay target. Each returned `Character` now echoes the resolved `playableStateScope` so later consumers do not have to infer realm policy from the request or from hidden storage-key conventions.
- `QueryActorState` – returns gameplay-attested actor resources and active conditions for a scoped character.
- `ApplyActorCondition` – applies a gameplay-attested active condition or transient action state for a scoped character. The first contract accepts source provenance, optional expiry, and the same internal effect payload JSON shape consumed by `QueryActorState`.
- `ListRoomEntities(ListRoomEntitiesRequest) returns (ListRoomEntitiesResponse)` – returns players, NPCs, and visible items present in a room, scoped by `RoomInstanceRef`.
- `GetDraftDesignDigest` – returns publish-gating digest for Draft entity templates using typed scope request `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`. Entity Management supports `versionId` scope only and must return `UNSUPPORTED_SCOPE` for `scriptPatchVersion`. Minimum response fields are `{tenantId, scope, appliedCommitId, contentDigest, digestSchemaVersion}`. `appliedCommitId` means the highest Game Design commit whose full revision set has been durably applied to the target Draft entity scope. `contentDigest` must cover only version-scoped entity template/binding rows and must exclude live runtime entities and audit/history metadata.

Gameplay mutation RPCs that change item, equipment, or container state accept an optional `effectId` supplied by Game Session durable effect execution. When present, Entity Management treats `{tenantId, effectId}` as the operation-level idempotency key and returns the stored applied response for duplicate delivery instead of applying the mutation again. Read-only query RPCs do not require an `effectId`.

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

`ListRoomEntities` is the dedicated endpoint for `LOOK` to discover which characters, items, and NPCs occupy a room. The response includes:

- `tenantId`, `gameInstanceId`, and `roomInstanceId` (a `RoomInstanceRef`) so consumers can unambiguously scope the entity list to a running instance.
- `entitySnapshotId` so consumers can cache or invalidate entity lists deterministically. The current adapter derives a scope marker; the target contract carries the committed `roomSnapshotVersion` value supplied by the room-read composition.
- the room-read fence value for this entity list. The target live-protocol meaning of `entitySnapshotId` is the exact same opaque or epoch-bearing committed fence emitted by World Management as `worldSnapshotId`, advanced after every durable mutation included in the room view. Future tick-ledger work may add an `asOfTickId` only through a coordinated proto and architecture update.
- `entities[]`, each with `entityId`, `displayName`, `entityType` (`PLAYER`, `NPC`, `ITEM`), and optional `role`/`affiliation`.
- `stateFlags` such as `isHidden`, `isInCombat`, or `isQuestTarget` so Game Logic can mask stealthy entities or highlight objectives.
- `visionPriority` to help sort players before NPCs and list visible items at the end, keeping `LOOK` render ordering consistent.
- `reloadHint` (enum) that signals whether the list is stable or dynamic, allowing Game Logic to decorate the `LOOK` output.

Game Logic treats the satisfied `entitySnapshotId` as the participant echo of the canonical committed `roomSnapshotVersion` for LOOK-relevant entity presence for a specific `RoomInstanceRef`. When composing a full LOOK view, Game Logic combines:

- `worldSnapshotId` from World Management’s `GetRoomSnapshot`; and
- the identical `entitySnapshotId` returned by `ListRoomEntities`,

then returns a `lookSnapshotId` alongside the rendered `LookResult` so Game Session can cache the final transcript deterministically. The target contract does not concatenate independent service versions; equality of the two transport fields proves that both reads satisfied one committed fence. The current scope-derived adapter value is not sufficient proof of mutation freshness.

Room-entity data is derived from runtime entity state plus authoritative world location. Ground items are discovered by querying items contained by the synthetic room-ground container for the target `RoomInstanceRef`. Characters and NPCs are included when their current location (owned by World Management) matches the target `RoomInstanceRef`:

- The caller obtains the authoritative room snapshot and committed read fence from World Management before invoking `ListRoomEntities`. The target request/response evolution must carry enough information for Entity Management to prove satisfaction of that fence; the current proto/request path does not yet claim this behavior complete.
- `ListRoomEntities` materializes display data plus room-ground inventory state owned by Entity Management for the same `RoomInstanceRef`.
- `ListRoomEntities` must return an Entity Management read fence (`entitySnapshotId`) for the same room scope; when Game Logic cannot align it with the World Management `worldSnapshotId`, composition must fail instead of returning mixed-tick data.
- The read fence is satisfied only by durable post-commit state. Redis-staged containment changes that have not yet committed the effect guard and container/item row updates for that fence are not eligible to satisfy the room-read fence.

Illustrative `ListRoomEntities` fragments:

- Success:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "gameInstanceId": "9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78",
  "roomInstanceId": "R-1021",
  "entitySnapshotId": "room-snapshot-epoch-17",
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
    "message": "Entity state could not satisfy the requested room read fence."
  }
}
```

Entity Management must not maintain a competing room-occupancy index that can drift from World Management’s location tables. Visibility and filtering rules are applied after aggregation so LOOK output remains player-correct.

When the requested fence is missing, stale, or cannot be satisfied from durable post-commit state, Entity Management returns `STALE_READ_FENCE` or `READ_FENCE_UNAVAILABLE`. A participant fence difference is a caller-side composition retry condition, not a separate service error from this API: Game Logic must obtain a fresh World Management snapshot and retry the same-scope composition, or fail the room view explicitly if the fresh read cannot be materialized.

Concrete per-effect required writes and reconciliation rules live in [`system-architecture-spatial-and-ambient-effects-catalog.md`](../../system-architecture-spatial-and-ambient-effects-catalog.md).

Cross-service retry orchestration is owned by the Game Session Service reconciliation backlog described in [Transaction Strategies](../../system-architecture-transactions.md#reconciliation-owner-of-record-spatialambient-effects). Entity Management must expose participant acknowledgements for each `EffectId`; it is not the owner of cross-service retry scheduling.

Only entities approved by the `EntityVisibilityPolicy` are returned; hidden NPCs, private inventory, or offstage summons are filtered out so `LOOK` always aligns with the player’s perspective. The response deliberately omits detailed stats to keep the text output focused on presence rather than numbers.

## Actor State Query

`QueryActorState` is the gameplay-facing read contract for current actor resources and active conditions. Callers must present gameplay session attestation, tenant id, character id, game instance id, and the resolved playable-state scope. Entity Management validates the attestation and scope, reads the scoped character, emits baseline character stat resources, overlays persisted `actor_resource_states` rows, applies active condition and equipped-item `effect_payload_json` modifiers through the shared effect evaluator, and returns active `actor_active_conditions` rows whose expiry has not passed. The response is transport-neutral state data for Game Logic and Game Session consumers; presentation layers must not infer authoritative gameplay state from transcript text.

`ApplyActorCondition` is the first mutation contract for the same actor-state table. It validates gameplay session attestation and playable-state scope before creating an `actor_active_conditions` row with `conditionKey`, `stackCount`, `sourceType`, optional `sourceId`, optional `expiresAt`, and optional `effectPayloadJson`. This is intended for gameplay-owned actions such as the first transient `blocking` state as well as later spells, consumables, and scripted effects. Reads ignore expired rows and the scheduled expiry sweep removes elapsed rows; callers must still treat the mutation response as the authoritative applied state for that request.

Condition and item-template effect payloads are an internal persisted contract in this first slice. They may contain a top-level `modifiers` array whose entries include `operation`, `target_key`, `value`, optional `scope_kind`, optional `scope_key`, and optional `priority`; supported operations currently match the shared evaluator primitives: `ADD`, `MULTIPLY`, `CLAMP_MIN`, `CLAMP_MAX`, `GRANT_FLAG`, and `GRANT_CONDITION`.

The current contract still leaves player command orchestration, authored stat/condition definitions, richer action-state policies, and combat resolution to later stats/effect slices.

## Implementation Status (LOOK Slice)

- **Live:** The seeded `firemud.look.rooms` entries provide the visible entities for the demo rooms, `ListRoomEntities` is wired into Game Logic's `ResolveLook`, and the resulting instrumentation is captured in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed:** Real-time behaviors such as item respawns, stealth/aura-driven visibility, and inventory states still rely on static fixtures so regression tests remain reproducible.
- **Deferred:** Future slices will catalog metadata from the `character_location` and `npc_location` tables, support multi-instance visibility rules, and surface richer context hints (combat alerts, quest markers) while keeping the public DTO focused on display data.
