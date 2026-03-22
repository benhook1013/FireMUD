# World Management Service API Contracts

## gRPC APIs

- `GetRoom` – retrieves room data including exits and environmental effects.
- `GetRoomSnapshot` – returns a minimal, LOOK-focused view scoped by `RoomInstanceRef`.
- `ListRoomOccupants` – returns the authoritative typed occupant list for actors in a room, scoped by `RoomInstanceRef`. The legacy `occupantEntityIds` list is a derived compatibility mirror only.
- `ApplyRoomAmbientStatePatch` – applies an ambient state patch to the target `RoomInstanceRef`, guarded by `EffectId`.
- `GetDraftDesignDigest` – returns the publish-gating digest for Draft world templates using the typed scope request `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`. World Management supports `versionId` scope only and must return `UNSUPPORTED_SCOPE` for `scriptPatchVersion`.
- `ValidateWorldUpgradeMappings` – validates world-owned durable references and approved remap sets for replacement-instance cutover to a target `(tenantId, versionId)`.
- `UpdateWorldState` – legacy bulk update surface scheduled for removal on June 30, 2026. Runtime mutation requests on this RPC must return `UNSUPPORTED_OPERATION`, and callers must use effect-shaped mutation RPCs instead.

The gRPC contract for world operations is located in [../../../../protos/world-management/v1](../../../../protos/world-management/v1). Run `./gradlew generateProto` to regenerate sources after editing these files.

Call the `Ping` method with:

```bash
grpcurl -plaintext localhost:6565 world_management.v1.WorldManagementService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

## REST Endpoints

- `GET /ping` – basic health check returning `"pong"`.
- `GET /regions?tenantId=...` – list regions for a tenant.
- `POST /generation/runtime-defaults` – create or update runtime-only generation defaults for a tenant.
- `GET /generation/runtime-defaults?tenantId=...` – list runtime-only generation defaults for a tenant.

The service exposes an OpenAPI specification under `/v3/api-docs` with a Swagger UI at `/swagger-ui.html` when running locally.

```bash
curl http://localhost:8080/ping
```

Requests to this service come from other internal services. Player identity is established by Game Session, so no JWT header is required here. See [Security Architecture](../../system-architecture-security.md) for the shared trust model.

## Design-Time APIs

World Management exposes design-time APIs used by Game Design to write Draft template rows keyed by `(tenantId, versionId)`, such as creating or updating `room_template` rows and version-scoped topology bindings.

- Auth: design-time APIs must validate JWTs and enforce designer/admin authorization for the target `tenantId`, consistent with Game Design control-plane auth.
- Mutability: design-time writes are allowed only for Draft versions. Attempts to write templates for Published, Active, or Failed versions must fail fast.
- Runtime isolation: runtime gameplay flows and world-creation Sagas must never call design APIs.

World Management also exposes a read-only design-time synchronization surface so Game Design can validate convergence before publish:

- `GetDraftDesignDigest(GetDraftDesignDigestRequest)` returns `{tenantId, scope, appliedCommitId or lastAppliedRevisionId, contentDigest, digestSchemaVersion}`.
- `appliedCommitId` means the highest Game Design commit whose complete revision set has been durably applied to the target Draft world scope.
- `contentDigest` must cover only version-scoped template/binding rows and must exclude runtime/instance rows and audit metadata.

Digest input manifest rules live in [`runtime-and-data.md`](./runtime-and-data.md#digest-input-manifest), while generation-input ownership lives in [`procedural-generation-control.md`](./procedural-generation-control.md).

## ValidateWorldUpgradeMappings Minimum Contract

`ValidateWorldUpgradeMappings` must expose enough detail for replacement-instance cutover tooling to reason about world-owned durable references:

- Input identifies `tenantId`, `sourceGameInstanceId`, `targetVersionId`, and optional `remapSetId`.
- Response enumerates the checked world-owned row families, the template identifiers referenced by each family, whether each family is `COMPATIBLE`, `REQUIRES_MAPPING`, or `INCOMPATIBLE`, and whether the supplied `remapSetId` satisfied all required mappings.
- If the service currently has no `S2` row families for a tenant/version pair, it must report that explicitly rather than implying compatibility from an empty response.

Illustrative responses:

```json
{
  "tenantId": "t1",
  "sourceGameInstanceId": "g-old",
  "targetVersionId": "v2",
  "checkedFamilies": [],
  "hasS2Rows": false,
  "result": "COMPATIBLE",
  "remapSetRequired": false
}
```

```json
{
  "tenantId": "t1",
  "sourceGameInstanceId": "g-old",
  "targetVersionId": "v3",
  "checkedFamilies": [
    {
      "family": "housing_anchor",
      "referencedTemplateIds": ["roomTemplateId:starter-house-01"],
      "outcome": "REQUIRES_MAPPING"
    }
  ],
  "hasS2Rows": true,
  "result": "INCOMPATIBLE",
  "remapSetRequired": true
}
```

## LOOK Snapshot Contract

`GetRoomSnapshot` is the canonical endpoint feeding Game Logic's `ResolveLook`. It returns:

- `tenantId`, `gameInstanceId`, and `roomInstanceId`, together forming the `RoomInstanceRef`;
- a stable `worldSnapshotId` for LOOK-relevant world data;
- `asOfTickId`, or equivalent monotonic room/read fence token;
- `roomName` and optional slug;
- `shortDescription` and `longDescription`, with truncation rules governed by `LOOK_MAX_DESCRIPTION_CHARS`;
- `exits`, including label, `targetRoomInstanceId`, and human-friendly direction text;
- `ambientState` fields for compatibility and `ambientStateV2` as the typed canonical form; and
- optional `roomFlags` for gameplay/UI warning surfaces.

Room snapshots deliberately exclude live entities, items, and inventory contents. Those are fetched from Entity Management using room- and instance-scoped queries.

Cross-service LOOK read consistency is fence-based:

- Game Logic must send the same `asOfTickId` fence token from `GetRoomSnapshot` when calling Entity Management `ListRoomEntities`.
- Entity Management must either answer using the same fence token or return `STALE_READ_FENCE` / `READ_FENCE_UNAVAILABLE`.
- If fences do not match, Game Logic retries composition instead of returning mixed-state output.

Illustrative `GetRoomSnapshot` fragments:

```json
{
  "tenantId": "t1",
  "gameInstanceId": "g1",
  "roomInstanceId": "room-antechamber",
  "worldSnapshotId": "worldsnap-184",
  "asOfTickId": 184,
  "roomName": "Candle-lit Antechamber"
}
```

```json
{
  "error": {
    "code": "READ_FENCE_UNAVAILABLE",
    "message": "Room snapshot could not be materialized for the requested read fence."
  }
}
```

`worldSnapshotId` is the canonical cache key for LOOK-relevant world data for a specific `RoomInstanceRef` at a specific fence. Game Logic combines `worldSnapshotId` from `GetRoomSnapshot` with `entitySnapshotId` from Entity Management `ListRoomEntities` to produce the final `lookSnapshotId` returned to Game Session.

## Instance Termination Contract

World Management owns the lifecycle of `gameInstanceId` rows, but teardown is cross-service:

- Game Session must first mark the instance non-admissible and draining before World transitions lifecycle.
- Expiry or operator shutdown transitions the instance to `TERMINATING` and starts an `InstanceTermination` Saga.
- Entity Management must acknowledge idempotent cleanup of containment and room-ground containers scoped to `(tenantId, gameInstanceId)` before World marks the instance `TERMINATED`.
- Scheduled expiry jobs must enqueue the Saga and must not directly delete world rows for a still-unconfirmed termination.
- Lifecycle fencing is mandatory. Termination acquires the same per-instance lifecycle fence used by activation. If activation and termination race, termination is authoritative unless admission has already opened and `ACTIVE` is committed.
- Game Session finalizes runtime `game_instances` termination only after World reports `TERMINATED`.

## LOOK Consumer Notes

Telnet and WebSocket clients both route through the `/ws/game/**` gameplay path, so `LOOK` commands hit the same `LookCommandHandler` regardless of transport.

- Authenticated success responses follow the canonical textual contract described in the gameplay docs.
- Game Session renders the `LookResult` returned by Game Logic, which already includes both world and entity projections, into the textual transcript via `LookResultRenderer`.
- Error responses emit `ERROR <CODE> <message>` covering `ROOM_NOT_FOUND`, `WORLD_UNAVAILABLE`, `ENTITY_UNAVAILABLE`, `LOOK_UNAVAILABLE`, and `NOT_AUTHENTICATED`.

The `V10__seed_demo_world.sql` migration seeds the demo rooms referenced by the LOOK lifecycle so integration tests and transcript examples remain stable.
