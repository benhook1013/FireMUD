# Entity Management Service API Contracts

This document defines the Entity Management REST and gRPC surfaces, design-time APIs, digest contract, and the LOOK entity-listing contract.

The authoritative REST schema source lives in [../../../../services/entity-management-service/src/main/resources/openapi.yaml](../../../../services/entity-management-service/src/main/resources/openapi.yaml). Proto definitions are the authoritative gRPC source.

## Implementation Status

`ApplyActorCondition` has not yet converged on the target replay contract below. Its current request has no dedicated `effectId`; Game Session places the operation-unique durable effect ID in required `sourceId`, which Entity Management first uses as the `{tenantId, effectId}` key for its durable replay marker and operation-name check. The first application then separately uses the first active-condition row matching `{tenantId, playableStateKey, characterId, sourceType, sourceId}`; that narrower lookup does not compare the complete condition payload on same-operation reuse. Until migration, current `sourceId` values must therefore be unique tenant-wide per intended operation/effect, even though the actor-condition row lookup has the narrower scope. This temporary implementation conflates replay identity with authored/source provenance. The current request also accepts caller-supplied `effectPayloadJson`; because `QueryActorState` currently evaluates persisted condition payloads into gameplay-visible actor state, callers must not treat that caller-supplied payload or payload-driven state as trusted typed-effect authority; the transitional evaluation remains until the target server-resolved typed effect snapshot is persisted and read. This is a transitional implementation gap, not mutation authority. The proto, callers, persistence, and proof must migrate together before additional producers reuse one source across applications.

The current LOOK adapter remains floor-free and is implementation-only:

### Current room-entity implementation

The current `ListRoomEntitiesRequest` carries `tenantId`, `RoomInstanceRef`, and `sessionAttestation`; it does not carry a caller-provided read-fence field. The current `entitySnapshotId` response value is a deterministic room-scope marker derived from the request scope, not proof that Entity Management observed a committed mutation version. The current adapter therefore does not claim exact caller-fence satisfaction.

The current response includes:

- `tenantId`, `gameInstanceId`, and scalar `roomInstanceId`, which identify the current adapter's room request scope.
- `entitySnapshotId` so consumers can identify the room scope in responses. In the current adapter this is only the scope marker described above and is not a mutation-freshness or invalidation proof.
- `entities[]`, each with the live proto fields `entityId`, `displayName`, `entityType`, `role`, `stateFlags`, `visionPriority`, `reloadHint`, `visible`, and `visibleRef`.
- `error` when the current adapter cannot produce the response.

## REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

## gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in `entity_management_service.proto`.
- `CreateCharacter(CreateCharacterRequest) returns (CreateCharacterResponse)` – Entity-owned allocation and persistence of a primary controllable actor after the caller has passed Account membership/grant/realm admission. The target request carries the resolved `{accountId, tenantId, worldSlug, realmSlug}`, stable `playableStateNamespaceId`, the `playableStateScope` supplied only as authoritative realm-snapshot/attestation evidence, active `gameInstanceId`, exact published entry-policy descriptor identity/version, and proof of the catalog/admission-pointer snapshot. Callers cannot choose or derive `playableStateScope`; Entity must reject caller-derived evidence and validate the exact immutable namespace/scope pair. A scope change under one namespace is rejected and never creates a second or split actor record. Entity allocates `characterId`, validates the namespace/scope and active-instance fence, persists the `{accountId, tenantId, playableStateNamespaceId, characterId}` association, and never derives identity from account/name/hash or delegates character ownership to Account. Durable persistence/keying uses `{tenantId, playableStateNamespaceId, characterId}`; `playableStateScope` remains separately validated immutable routing/authorization evidence, `gameInstanceId` is only the replaceable active-runtime fence, and neither is a durable character-identity dimension. Entity Management does not expose a direct player REST route. The current request path does not yet carry and prove this complete target boundary, so it remains an implementation/proof gap rather than a claim about live enforcement.
- `UpdateEntity(UpdateEntityRequest) returns (UpdateEntityResponse)` – updates mutable character state for the requested `{tenantId, playableStateNamespaceId, gameInstanceId, playableStateScope, characterId}` target. `playableStateScope` must be the authoritative realm-snapshot/attestation result; callers cannot choose or derive it, and Entity validates the exact immutable namespace/scope pair before mutation. A caller-selected or changed scope, missing/mismatched namespace or scope, stale snapshot, caller-asserted instance, or global `characterId` is rejected rather than updating or splitting records. Target state also requires a fresh, server-resolved active-instance authorization fence for that exact namespace/scope and instance, checked atomically immediately before mutation. The current request path does not yet carry and prove the complete fresh fence, so it remains an implementation/proof gap and must not be treated as target enforcement.
- `QueryInventory(QueryInventoryRequest) returns (QueryInventoryResponse)` – lists items for an entity with pagination. Current responses include `item_instance_id` and `visible_ref` for concrete item instances; quantity is present in the proto for forward compatibility, but stack merge behavior is still a later `06.3.2` follow-up.
- `ListCharactersByAccount` (target-state policy/descriptor/template/namespace fields) – returns only persisted actors valid for the trusted account selector and resolved `{tenantId, playableStateNamespaceId, playableStateScope}` target. Entity validates the account-to-character association before returning any roster or visibility result; an account identifier is ownership evidence, not controller identity. The target response includes exactly one published v1 entry policy (`PLAYER_CREATED`, `PRESEEDED_ONLY`, or `AUTO_PROVISIONED`) plus only its policy-applicable versioned identity: a descriptor identity/version for `PLAYER_CREATED`, a template identity/version for `AUTO_PROVISIONED`, and neither descriptor nor template fields for `PRESEEDED_ONLY`; it echoes the resolved namespace/scope. `gameInstanceId` is a fresh active-runtime fence, not durable lookup identity. Zero, one, and many results are explicit: policy determines the zero-result next action, one may be selected automatically, and many require explicit selection. A roster is discovery evidence, not admission authority. The current proto surface has none of the target policy, descriptor/template, or namespace fields and does not prove this boundary.
- `AutoProvisionCharacter` (target-state) – after explicit entry, idempotently provisions at most one actor from the published realm template using the trusted `{accountId, playableStateNamespaceId}` as the persistence and idempotency identity. `playableStateScope` is only server-validated routing/authorization-fence evidence and is not an alternate key; `gameInstanceId` is the replaceable active-runtime fence. Exact retries and concurrent duplicate requests return the same persisted actor; a changed descriptor/template version, trusted account, or namespace is rejected. The current surface and proof are not implemented.
- `QueryActorState` – returns gameplay-attested actor resources and active conditions for a scoped character.
- `ApplyActorCondition` – **target-state request:** applies a gameplay-attested active condition or transient action state for a scoped character. The canonical target request carries a dedicated operation-unique `effectId`, separate source provenance, optional expiry, and the exact release-pinned typed effect declaration/reference and digest resolved by the server-side gameplay path; caller-defined effect JSON is not mutation authority. The target mutation persists the frozen definition/release and its server-resolved typed applied-effect snapshot and digest, never a caller-supplied payload. The current request still accepts transitional `effectPayloadJson`, so this typed-plan/no-caller-payload boundary is not live enforcement yet.
- `ListRoomEntities(ListRoomEntitiesRequest) returns (ListRoomEntitiesResponse)` (target-state namespace-carrying response) – returns players, NPCs, and visible items present in a room, scoped by `RoomInstanceRef` and the resolved `playableStateNamespaceId`; PLAYER projections/lookups carry that namespace alongside the current `gameInstanceId` runtime fence. The current adapter remains floor-free and its response lacks `playableStateNamespaceId`, so this namespace boundary is not implemented or proved.
- `GetDraftDesignDigest` – returns publish-gating digest for Draft entity templates using typed scope request `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`. Entity Management supports `versionId` scope only and must return `UNSUPPORTED_SCOPE` for `scriptPatchVersion`. Minimum response fields are `{tenantId, scope, appliedCommitId, contentDigest, digestSchemaVersion}`. `appliedCommitId` means the highest Game Design commit whose full revision set has been durably applied to the target Draft entity scope. `contentDigest` must cover only version-scoped entity template/binding rows and must exclude live runtime entities and audit/history metadata.

Target-state gameplay mutation RPCs that change item, equipment, or container state accept the canonical `effectId` and its complete structured identity supplied by Game Session durable effect execution. Entity Management validates the `{tenantId, playableStateNamespaceId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId}` projection before applying the mutation; `{tenantId, effectId}` alone is not a sufficient substitute for the scoped identity. `ApplyActorCondition` requires a dedicated `effectId` as the operation-level replay identity, while `sourceId` remains separate authored/source provenance. Duplicate delivery returns the stored applied/no-op response instead of applying the mutation again. Read-only query RPCs do not require an `effectId`. Equipment occupancy and compatibility additionally require the complete published game-authored vocabulary/body-layout schema and digest; missing, partial, or mismatched schema/mapping evidence fails closed. Current live mutation surfaces may still expose an optional or narrower effect field; that is an implementation gap, not permission to define a competing idempotency contract.

The actor-entry contract deliberately does not make `race`, `class`, attributes, abilities, ship configuration, or other RPG-shaped columns platform requirements. Such values are authored components validated against the exact published descriptor/template. A playtest copy has a new fork-local `characterId`; when `sourceCharacterId` is retained, it must carry the immutable `{sourceTenantId, sourcePlayableStateNamespaceId}` binding and remains provenance-only, never authorizing cross-namespace reads or writes or any other identity, ownership, mutation, controller, reconnect, or merge operation.

For `DROP` and `PICKUP`, Entity is the holder-mutation participant in the canonical barrier/attestation contract defined in [Transaction Strategies](../../system-architecture-transactions.md#drop-pickup-targeting-and-actor-fence-critical-section) and [ADR 0054](../../decisions/adr-0054-split-spatial-authority-with-causal-read-composition.md). Its target commit binding includes `regionId` from Game Session's durable region authority alongside `RoomInstanceRef`, `regionEpoch`, `executorFence`, the same root `EffectId`, and the unchanged immutable `requestDigest`; World validation and Game Logic re-resolution preserve that binding, and Entity rejects a missing or mismatched attestation before holder mutation. The current proto/request and focused proof do not yet carry or demonstrate this attestation path, so they do not claim the target contract.

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

`ListRoomEntities` is the dedicated endpoint for `LOOK` to discover which characters, items, and NPCs occupy a room. The target causal-floor and component-proof contract below is canonical; the current floor-free adapter is recorded as implementation status above. The target shape follows the [Identifier Glossary causal-read fence contract](../../system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity); current transport names `worldSnapshotId` and `entitySnapshotId` remain deterministic same-scope markers only, not independent freshness versions.

### Target causal-floor contract

The target response carries flattened `tenantId`, `gameInstanceId`, scalar `roomInstanceId`, and `playableStateNamespaceId`; the namespace-qualified `RoomInstanceRef` is the target room identity, with `gameInstanceId` retained as the replaceable active-runtime fence. These fields are target-only and are not present as a complete boundary in the current adapter.

The target `ListRoomEntities` request carries the same `CausalReadFence` as World Management for the `RoomInstanceRef`, operational `regionId`, and `regionEpoch`, including at least `committedTickId`. Game Session allocates that floor from durable region commit authority before invoking `ResolveLook`; Game Logic propagates the unchanged floor to Entity Management. The exact request-field shape remains deferred to the coordinated proto/design change; the current request remains floor-free and does not claim floor satisfaction.

After that protocol exists, Entity Management serves the same region/scope/epoch and returns that proof, a scoped `servedThroughTickId`, and an opaque Entity component version. That returned version follows the [Identifier Glossary causal-read contract](../../system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity): Entity advances it atomically with relevant durable containment-owner commits and returns the stored version on matching replay/no-op. Game Logic accepts the participant when the returned region/scope/epoch matches and `servedThroughTickId >=` the requested `committedTickId`; a behind-floor or mixed tenant, game instance, region, room, or epoch response is rejected or retried. Opaque component versions are not directly compared. Game Logic validates the served-through proof, then composes only the requested floor plus the World and Entity component versions in the room-view identity and returns that identity with `LookResult` so Game Session can retain its transcript rendering/cache behavior; it does not claim an exact cross-database historical snapshot.

Room-entity data is derived from runtime entity state plus authoritative world location. Ground items are discovered by querying items contained by the synthetic room-ground container for the target `RoomInstanceRef`. Characters and NPCs are included when their current location (owned by World Management) matches the target `RoomInstanceRef`:

- Game Session obtains the complete causal floor, including `regionId`, from durable region commit authority before invoking `ResolveLook`; Game Logic propagates that floor unchanged to `ListRoomEntities`. The target request/response evolution must carry enough information for Entity Management to prove service of that region/scope/epoch floor; the current proto/request path does not yet carry the floor or served-through proof and does not claim this behavior complete.
- `ListRoomEntities` materializes display data plus room-ground inventory state owned by Entity Management for the same `RoomInstanceRef`.
- `ListRoomEntities` must return the same region/scope/epoch proof, an opaque Entity component version, and scoped `servedThroughTickId` after serving the requested floor. Game Logic accepts same-region/scope/epoch `servedThroughTickId >= committedTickId`, rejects or retries behind-floor or mixed-region/scope/epoch responses, and does not directly compare opaque component versions.
- The causal floor is satisfied only by durable post-commit state. Redis-staged containment changes that have not yet committed the effect guard and container/item row updates for the requested floor are not eligible to satisfy it.

Illustrative target-state `ListRoomEntities` fragments:

- Success:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "playableStateNamespaceId": "2f1a1b6c-4a7d-4bc0-a7b9-6d4e5f8a9c01",
  "gameInstanceId": "9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78",
  "regionId": "R7",
  "regionEpoch": 17,
  "roomInstanceId": "1021",
  "servedThroughTickId": 42,
  "entityComponentVersion": "entity-component-version-23",
  "entities": [
    {
      "entityId": "char-mara",
      "displayName": "Mara",
      "entityType": "PLAYER",
      "playableStateNamespaceId": "2f1a1b6c-4a7d-4bc0-a7b9-6d4e5f8a9c01"
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

In the target protocol, when the propagated causal floor is missing, stale, or cannot be satisfied from durable post-commit state, Entity Management returns `STALE_READ_FENCE` or `READ_FENCE_UNAVAILABLE`. A behind-floor or mixed-region/scope/epoch response is a caller-side composition retry condition, not a separate service error from this API: Game Session must obtain a fresh durable region-commit floor, pass it through `ResolveLook`, and have Game Logic retry the same-region/scope composition, or fail the room view explicitly if the fresh floor cannot be materialized. These target errors are not claims about the current request path.

The unresolved target work is tracked in [World Runtime and Movement](../../../project-management/implementation-tracking/world-runtime-and-movement.md#active-gaps): Game Session floor allocation and Game Logic propagation are target obligations, while Entity participant floor satisfaction and the opaque component-version plus scoped `servedThroughTickId` response remain unimplemented and unproved. The current proto and focused proof do not cover this path; the exact proof encoding and any remaining wire details are implementation gaps, not a competing contract.

Concrete per-effect required writes and reconciliation rules live in [`system-architecture-spatial-and-ambient-effects-catalog.md`](../../system-architecture-spatial-and-ambient-effects-catalog.md).

Cross-service retry orchestration is owned by the Game Session Service reconciliation backlog described in [Transaction Strategies](../../system-architecture-transactions.md#reconciliation-owner-of-record-spatialambient-effects). Entity Management must expose participant acknowledgements for each `EffectId`; it is not the owner of cross-service retry scheduling.

Only entities approved by the `EntityVisibilityPolicy` are returned; hidden NPCs, private inventory, or offstage summons are filtered out so `LOOK` always aligns with the player’s perspective. The response deliberately omits detailed stats to keep the text output focused on presence rather than numbers.

## Actor State Query

`QueryActorState` is the gameplay-facing read contract for current actor resources and active conditions. Callers must present typed `PlayerExecutionContext`, tenant id, character id, stable `playableStateNamespaceId`, active game instance id, and the resolved playable-state scope. Entity Management validates the concrete mTLS caller and method allowlist, context/request equality, active-instance authorization, and scoped character relationship, then emits baseline character stat resources, overlays persisted `actor_resource_states` rows, applies active condition and equipped-item `effect_payload_json` modifiers through the shared effect evaluator, and returns active `actor_active_conditions` rows whose expiry has not passed. The response is transport-neutral state data for Game Logic and Game Session consumers; presentation layers must not infer authoritative gameplay state from transcript text.

Under [ADR 0112](../../decisions/adr-0112-typed-bounded-gameplay-effect-extension.md), Entity Management is the canonical owner of actor/effect mutation, resource costs, and cooldown state. It accepts only a validated typed plan or registered effect operation resolved by the owning server-side gameplay path from the exact release-pinned typed effect declaration/reference and digest, preserves the operation/effect replay identity and request digest, and never parses script text, executes caller-supplied DML, or reconstructs targeting policy from a partial request.

`ApplyActorCondition` is the first mutation contract for the same actor-state table. The current implementation validates the concrete mTLS caller and method allowlist, `PlayerExecutionContext`, and playable-state scope before creating an `actor_active_conditions` row with `conditionKey`, `stackCount`, `sourceType`, `sourceId`, optional `expiresAt`, and optional caller-supplied `effectPayloadJson`; accepting that payload directly, and allowing `QueryActorState` to evaluate it into gameplay-visible state, is the transitional gap identified above. Until the target typed request exists, callers using the current wire contract must keep a supplied `sourceId` unique tenant-wide per intended operation/effect because it is also the durable replay marker key. Within that operation, the actor-condition table lookup is narrower—`{tenantId, playableStateKey, characterId, sourceType, sourceId}`—and returns the first matching source row without comparing changed `effectPayloadJson` values. The target contract carries a dedicated `effectId` as the operation-level replay identity under `{tenantId, effectId}`, while `sourceId` remains authored/source provenance and may legitimately recur across separate applications. Target-state replay behavior (not currently enforced by this transitional request) is: a completed duplicate with the same canonical request payload returns the original serialized `ApplyActorConditionResponse` without another condition mutation; an in-progress duplicate returns conflict, payload or operation reuse returns invalid argument, and an unreadable stored response fails internally. Until dedicated `effectId`, canonical request-digest, and stored-response enforcement are live, clients must not rely on those outcomes or on changed-payload conflict detection. This is intended for gameplay-owned actions such as the first transient `blocking` state as well as later spells, consumables, and scripted effects. Reads ignore expired rows and the scheduled expiry sweep removes elapsed rows; callers must still treat the mutation response as the authoritative applied state for that request.

Condition and item-template effect payloads are an internal persisted contract in this first slice. They may contain a top-level `modifiers` array whose entries include `operation`, `target_key`, `value`, optional `scope_kind`, optional `scope_key`, and optional `priority`; supported operations currently match the shared evaluator primitives: `ADD`, `MULTIPLY`, `CLAMP_MIN`, `CLAMP_MAX`, `GRANT_FLAG`, and `GRANT_CONDITION`.

The current contract still leaves player command orchestration, authored stat/condition definitions, richer action-state policies, and combat resolution to later stats/effect slices.

## Implementation Status (LOOK Slice)

- **Live:** The seeded `firemud.look.rooms` entries provide the visible entities for the demo rooms, `ListRoomEntities` is wired into Game Logic's `ResolveLook`, and the resulting instrumentation is captured in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed:** Real-time behaviors such as item respawns, stealth/aura-driven visibility, and inventory states still rely on static fixtures so regression tests remain reproducible.
- **Deferred:** Future slices will catalog metadata from the `character_location` and `npc_location` tables, support multi-instance visibility rules, and surface richer context hints (combat alerts, quest markers) while keeping the public DTO focused on display data.
