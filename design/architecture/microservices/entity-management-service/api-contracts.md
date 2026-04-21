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
- `UpdateEntity(UpdateEntityRequest) returns (UpdateEntityResponse)` – updates stats or equipment for a character or NPC.
- `QueryInventory(QueryInventoryRequest) returns (QueryInventoryResponse)` – lists items for an entity with pagination. Current responses include `item_instance_id` and `visible_ref` for concrete item instances; quantity is present in the proto for forward compatibility, but stack merge behavior is still a later `06.3.2` follow-up.
- `ListCharactersByAccount` – returns all characters owned by an account across tenants.
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
- `entitySnapshotId` so consumers can cache or invalidate entity lists deterministically.
- the room-read fence value for this entity list. The live proto carries this as `entitySnapshotId`; future tick-ledger work may add an `asOfTickId` only through a coordinated proto and architecture update.
- `entities[]`, each with `entityId`, `displayName`, `entityType` (`PLAYER`, `NPC`, `ITEM`), and optional `role`/`affiliation`.
- `stateFlags` such as `isHidden`, `isInCombat`, or `isQuestTarget` so Game Logic can mask stealthy entities or highlight objectives.
- `visionPriority` to help sort players before NPCs and list visible items at the end, keeping `LOOK` render ordering consistent.
- `reloadHint` (enum) that signals whether the list is stable or dynamic, allowing Game Logic to decorate the `LOOK` output.

Game Logic treats `entitySnapshotId` as the canonical cache key for LOOK-relevant entity presence for a specific `RoomInstanceRef` at a specific read fence. When composing a full LOOK view, Game Logic combines:

- `worldSnapshotId` from World Management’s `GetRoomSnapshot`; and
- `entitySnapshotId` from `ListRoomEntities`,

then returns a `lookSnapshotId` (for example `worldSnapshotId + ":" + entitySnapshotId`) alongside the rendered `LookResult` so Game Session can cache the final transcript deterministically.

Room-entity data is derived from runtime entity state plus authoritative world location. Ground items are discovered by querying items contained by the synthetic room-ground container for the target `RoomInstanceRef`. Characters and NPCs are included when their current location (owned by World Management) matches the target `RoomInstanceRef`:

- The caller obtains the authoritative room snapshot and read fence from World Management before invoking `ListRoomEntities`.
- `ListRoomEntities` materializes display data plus room-ground inventory state owned by Entity Management for the same `RoomInstanceRef`.
- `ListRoomEntities` must return an Entity Management read fence (`entitySnapshotId`) for the same room scope; when Game Logic cannot align it with the World Management `worldSnapshotId`, composition must fail instead of returning mixed-tick data.
- The read fence is satisfied only by durable post-commit state. Redis-staged containment changes that have not yet committed the effect guard and container/item row updates for that fence are not eligible to satisfy the room-read fence.

Illustrative `ListRoomEntities` fragments:

- Success:

```json
{
  "tenantId": "t1",
  "gameInstanceId": "g1",
  "roomInstanceId": "room-antechamber",
  "entitySnapshotId": "t1:g1:room-antechamber",
  "entities": [
    {
      "entityId": "char-mara",
      "displayName": "Mara",
      "entityType": "PLAYER"
    }
  ]
}
```

- Fence mismatch:

```json
{
  "error": {
    "code": "READ_FENCE_MISMATCH",
    "message": "Entity state did not align with the requested room read fence."
  }
}
```

Entity Management must not maintain a competing room-occupancy index that can drift from World Management’s location tables. Visibility and filtering rules are applied after aggregation so LOOK output remains player-correct.

Concrete per-effect required writes and reconciliation rules live in [`system-architecture-spatial-and-ambient-effects-catalog.md`](../../system-architecture-spatial-and-ambient-effects-catalog.md).

Cross-service retry orchestration is owned by the Game Session Service reconciliation backlog described in [Transaction Strategies](../../system-architecture-transactions.md#reconciliation-owner-of-record-spatialambient-effects). Entity Management must expose participant acknowledgements for each `EffectId`; it is not the owner of cross-service retry scheduling.

Only entities approved by the `EntityVisibilityPolicy` are returned; hidden NPCs, private inventory, or offstage summons are filtered out so `LOOK` always aligns with the player’s perspective. The response deliberately omits detailed stats to keep the text output focused on presence rather than numbers.

## Implementation Status (LOOK Slice)

- **Live:** The seeded `firemud.look.rooms` entries provide the visible entities for the demo rooms, `ListRoomEntities` is wired into Game Logic's `ResolveLook`, and the resulting instrumentation is captured in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed:** Real-time behaviors such as item respawns, stealth/aura-driven visibility, and inventory states still rely on static fixtures so regression tests remain reproducible.
- **Deferred:** Future slices will catalog metadata from the `character_location` and `npc_location` tables, support multi-instance visibility rules, and surface richer context hints (combat alerts, quest markers) while keeping the public DTO focused on display data.
