# World Management Service API Contracts

## gRPC APIs

- `GetRoom` – retrieves room data including exits and environmental effects through `RoomInstanceRef`.
- `GetRoomSnapshot` – returns a minimal, LOOK-focused view scoped by `RoomInstanceRef`.
- `ListRoomOccupants` – returns the authoritative typed occupant list for actors in a room, scoped by `RoomInstanceRef`.
- `ApplyRoomAmbientStatePatch` – applies an ambient state patch to the target `RoomInstanceRef`, guarded by `EffectId`.
- `GetDraftDesignDigest` – returns the publish-gating digest for Draft world templates using the typed scope request `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`. World Management supports `versionId` scope only and must return `UNSUPPORTED_SCOPE` for `scriptPatchVersion`.
- `ValidateWorldUpgradeMappings` – validates world-owned durable references and approved remap sets for replacement-instance cutover to a target `(tenantId, versionId)`.
- `PrepareWorldInstance` – creates or reuses the canonical `PREPARING` world lifecycle row for a resolved launch descriptor, validates release-bundle and `versionStateEpoch` proof against Game Design, and materializes first-cut instance topology rows without admitting gameplay yet.
- `ActivatePreparedWorldInstance` – performs the fenced `PREPARING -> ACTIVE` lifecycle transition after Game Session has finished local start-up work for the same `gameInstanceId`.
- `FailPreparedWorldInstance` – performs the fenced `PREPARING -> FAILED_PRE_ACTIVATION` transition when Game Session or another pre-admission consumer must roll back a prepared instance before admission opens.
- `GetWorldInstanceLifecycle` – returns the current fenced lifecycle snapshot for an existing `(tenantId, gameInstanceId)` so stop/cutover consumers can retry against fresh lifecycle truth instead of cached guesses.
- `TerminateWorldInstance` – performs the fenced `ACTIVE -> TERMINATING -> TERMINATED` shutdown path and runs the canonical cross-service runtime cleanup before World reports termination complete.

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

Runtime/gameplay-facing requests to this service come from other internal services. Player identity is established by Game Session for those calls, so no JWT header is required on that runtime path. Design-time APIs are separate and must validate JWTs as described below. See [Security Architecture](../../system-architecture-security.md) for the shared trust model.

## Design-Time APIs

World Management exposes design-time APIs used by Game Design to write Draft template rows keyed by `(tenantId, versionId)`, such as creating or updating `room_template` rows and version-scoped topology bindings.

- Auth: design-time APIs must validate JWTs and enforce designer/admin authorization for the target `tenantId`, consistent with Game Design control-plane auth.
- Mutability: design-time writes are allowed only for Draft versions. Attempts to write templates for Published, Active, or Failed versions must fail fast.
- Scope integrity: scoped `ApplyWorldDesignMutation` requests must prove the changed topology belongs to the declared `REGION_SUBTREE` or `ZONE_SUBTREE` before advancing a scope epoch. Room exits must keep both endpoints inside the declared scope. Unsupported `NEW_EMPTY_REGION` combinations must return a normal application error instead of applying a partial topology mutation.
- Scoped generation-rule mutations must validate the declared `REGION_SUBTREE` or `ZONE_SUBTREE`, store that scope on the generation rule row, and apply `REPLACE_SCOPE` / `SEED_APPEND_ONLY` against all generation rules in the same declared scope.
- Scoped `WORLD_GENERATION_SUBTREE` mutations are the canonical multi-row generation write path. They apply generated rooms, room exits, generation rules, and spawn bindings under one declared `REGION_SUBTREE` or `ZONE_SUBTREE`; `REPLACE_SCOPE` removes prior room, exit, spawn-binding, and generation-rule rows inside the scope before applying the replacement, while `SEED_APPEND_ONLY` rejects rewrites of existing same-scope generation rules or spawn bindings.
- Runtime isolation: runtime gameplay flows and the world-lifecycle workflow must never call design APIs.

World Management also exposes a read-only design-time synchronization surface so Game Design can validate convergence before publish:

- `GetDraftDesignDigest(GetDraftDesignDigestRequest)` returns `{tenantId, scope, appliedCommitId, contentDigest, digestSchemaVersion}`.
- `appliedCommitId` means the highest Game Design commit whose complete revision set has been durably applied to the target Draft world scope.
- Revision-level ledgers may exist for replay and diagnostics, but they are not part of the publish-gate response. World Management must not expose `lastAppliedRevisionId` as a substitute convergence token for multi-revision commits.
- `contentDigest` must cover only version-scoped template/binding rows and must exclude runtime/instance rows and audit metadata.

Digest input manifest rules live in [`runtime-and-data.md`](./runtime-and-data.md#digest-input-manifest), while generation-input ownership lives in [`procedural-generation-control.md`](./procedural-generation-control.md).

## ValidateWorldUpgradeMappings Minimum Contract

`ValidateWorldUpgradeMappings` must expose enough detail for replacement-instance cutover tooling to reason about world-owned durable references:

- Input identifies `tenantId`, `sourceGameInstanceId`, `targetVersionId`, and optional `remapSetId`.
- Response enumerates the checked world-owned row families, the template identifiers referenced by each family, whether each family is `COMPATIBLE`, `REQUIRES_MAPPING`, or `INCOMPATIBLE`, and whether the supplied `remapSetId` satisfied all required mappings.
- If the service currently has no `S2` row families for a tenant/version pair, it must report that explicitly rather than implying compatibility from an empty response.

Current live first slice:

- The RPC now exists and returns the canonical cutover-validation payload shape.
- The implementation currently proves the source `world_instance` exists for `(tenantId, sourceGameInstanceId)`, requires a cutover-eligible world lifecycle state, and verifies retained instance topology rows for `region_instance`, `zone_instance`, and `room_instance` while still reporting the initial World-owned `S3` families only.
- World therefore currently returns `stateClassesChecked=["S3"]`, `checkedFamilies=["world_instance", "region_instance", "zone_instance", "room_instance", "room_instance_exit", "world_event"]`, `hasS2Rows=false`, and `remapSetRequired=false`; it returns `INCOMPATIBLE` when the source world lifecycle or retained topology is not cutover-eligible.
- Later World-owned durable metadata families can widen this contract to real `S2` checks without changing the owning RPC surface.

Illustrative responses:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "sourceGameInstanceId": "2e3ee139-a6e8-44ad-b840-891b22c2255b",
  "targetVersionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
  "stateClassesChecked": ["S3"],
  "checkedFamilies": [
    "world_instance",
    "region_instance",
    "zone_instance",
    "room_instance",
    "world_event"
  ],
  "hasS2Rows": false,
  "result": "COMPATIBLE",
  "remapSetRequired": false
}
```

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "sourceGameInstanceId": "2e3ee139-a6e8-44ad-b840-891b22c2255b",
  "targetVersionId": "8e65e4a1-5b49-4c31-9f27-3d0b8c6a1e74",
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

`GetRoomSnapshot` is the canonical endpoint feeding Game Logic's `ResolveLook`. The current and target fence contracts are intentionally separate.

### Current room-snapshot contract

The current `GetRoomSnapshotRequest` carries `tenantId`, `RoomInstanceRef`, locale, and session attestation; it does not carry a caller-provided read-fence field. The current `worldSnapshotId` response value is a deterministic room-scope marker, not proof of a committed mutation version.

The current response returns:

- `tenantId`, `gameInstanceId`, and `roomInstanceId`, together forming the `RoomInstanceRef`;
- a `worldSnapshotId` for LOOK-relevant world data. In the current adapter this is only the scope marker described above;
- `roomName` and optional slug;
- `shortDescription` and `longDescription`, with truncation rules governed by `LOOK_MAX_DESCRIPTION_CHARS`;
- `exits`, including label, `targetRoomInstanceId`, and human-friendly direction text;
- `ambientState` as the typed canonical form; and
- optional `roomFlags` for gameplay/UI warning surfaces.

Room snapshots deliberately exclude live entities, items, and inventory contents. Those are fetched from Entity Management using room- and instance-scoped queries.

Game Logic may memoize snapshots for the duration of a tick but must refresh them after movement. The current scope-derived adapter value does not prove mutation freshness, and FireMUD must not treat it as authoritative mutation versioning or treat stale rendered `LOOK` output as room truth.

### Target same-fence contract

The target protocol maps `worldSnapshotId` to a World Management-owned committed `roomSnapshotVersion`: one opaque or epoch-bearing fence that advances after every durable mutation included in the room view. World Management will publish the relevant room-mutation changes so that this committed value can advance rather than relying on time-based guesswork. The exact-fence propagation protocol is explicitly deferred: the current request has no named requested-fence field and the current implementation does not claim this behavior complete. When the target protocol is implemented, it must carry the exact World Management committed same-scope fence into each participant read and require each participant to echo that exact satisfied token; a scope-local substitute or independently minted participant fence is not equivalent.

Once that target protocol is designed and implemented, cross-service LOOK read consistency is fence-based:

- Game Logic must propagate the logical `roomSnapshotVersion` carried as `worldSnapshotId` from `GetRoomSnapshot` as the requested fence for the same-scope Entity Management read, then compare it with the identical `entitySnapshotId` returned by `ListRoomEntities`.
- Entity Management must either answer with that exact committed same-scope fence token after satisfying it, or return target-state `STALE_READ_FENCE` / `READ_FENCE_UNAVAILABLE`; it must not mint a competing entity-local fence.
- If a participant cannot satisfy the requested fence or the returned participant fence differs, Game Logic treats that as a caller-side retry condition, obtains a fresh World Management snapshot, and retries the same-scope composition. It must not return mixed-state output or require a separate mismatch service error.

Current World Management runtime room identity notes:

- World Management emits canonical runtime room ids as opaque text in the form `R-<roomInstanceRowId>`.
- World Management gameplay bridge readers fail closed on legacy `room-1021` or `1021` request forms; callers must send the canonical opaque runtime room id and must not infer row-id semantics from its shape.

Illustrative target-state `GetRoomSnapshot` fragments:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "gameInstanceId": "9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78",
  "roomInstanceId": "R-1021",
  "worldSnapshotId": "room-snapshot-epoch-17",
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

`worldSnapshotId` carries the canonical committed `roomSnapshotVersion` for LOOK-relevant world data for a specific `RoomInstanceRef` only in the target contract. The target value is opaque or epoch-bearing, changes after every relevant durable mutation, and is emitted by World Management as the single logical fence. Entity Management must return the identical satisfied value as `entitySnapshotId`; it must not derive an independent entity-only version. Game Logic combines the equal transport fields to produce the final `lookSnapshotId` returned to Game Session. The current scope-derived adapter value remains incomplete until the committed version and its propagation protocol are implemented.

The unresolved target work is tracked in [World Runtime and Movement](../../../project-management/implementation-tracking/world-runtime-and-movement.md#active-gaps): World-owned fence allocation after Entity-owned LOOK-visible mutations, propagation to the participant read, participant acknowledgement, and durable commit ordering are not yet defined or live.

## Instance Termination Contract

World Management owns the lifecycle of `gameInstanceId` rows, but teardown is cross-service:

- Game Session must first mark the instance non-admissible and draining before World transitions lifecycle.
- Expiry or operator shutdown transitions the instance to `TERMINATING` through the durable Temporal `world-lifecycle` workflow.
- Entity Management must acknowledge idempotent cleanup of containment and room-ground containers scoped to `(tenantId, gameInstanceId)` before World marks the instance `TERMINATED`.
- Scheduled expiry jobs must start or signal the lifecycle workflow and must not directly delete world rows for a still-unconfirmed termination.
- Lifecycle fencing is mandatory. Termination acquires the same per-instance lifecycle fence used by activation. If activation and termination race, termination is authoritative unless admission has already opened and `ACTIVE` is committed.
- Game Session finalizes runtime `game_instances` termination only after World reports `TERMINATED`.

Current implementation notes:

- The first canonical activation seam is now live for the `StartSession` path.
- World Management persists `world_instance`, `region_instance`, `zone_instance`, `room_instance`, and `room_instance_exit` rows keyed by `(tenantId, gameInstanceId)` and uses `lifecycle_epoch` as the current fence token for prepare/activate/fail/terminate transitions.
- `world_event` is now runtime-owned and keyed by `(tenantId, gameInstanceId)` via `region_instance`, rather than hanging off template `region` rows.
- `stopSession` now consumes `GetWorldInstanceLifecycle` + `TerminateWorldInstance` rather than the old ping-only shutdown path; Entity Management cleanup runs through `CleanupRuntimeInstance`, and World hard-deletes its own runtime rows (`world_event`, room exits, rooms, zones, regions) before reporting `TERMINATED`.
- Replacement-instance/cutover callers beyond explicit stop/termination are still follow-on work.

## LOOK Consumer Notes

Telnet and WebSocket clients both route through the `/ws/game/**` gameplay path, so `LOOK` commands hit the same `LookCommandHandler` regardless of transport.

- Authenticated success responses follow the canonical textual contract described in the gameplay docs.
- Game Session renders the `LookResult` returned by Game Logic, which already includes both world and entity projections, into the textual transcript via `LookResultRenderer`.
- Error responses emit `ERROR <CODE> <message>` covering `ROOM_NOT_FOUND`, `WORLD_UNAVAILABLE`, `ENTITY_UNAVAILABLE`, `LOOK_UNAVAILABLE`, and `NOT_AUTHENTICATED`.

The sample rooms referenced by the LOOK lifecycle are provided by the canonical LOOK cross-service fixtures so integration tests and transcript examples remain stable.
