# World Management Service API Contracts

## gRPC APIs

- `GetRoom` – retrieves room data including exits and environmental effects through `RoomInstanceRef`.
- `GetRoomSnapshot` – returns a minimal, LOOK-focused view scoped by `RoomInstanceRef`.
- `ListRoomOccupants` – returns the authoritative typed occupant list for actors in a room, scoped by `RoomInstanceRef`.
- `ApplyRoomAmbientStatePatch` – applies a typed ambient fact patch to the target runtime scope under expected epoch/version preconditions and an operation/aggregate/request-digest-bound guard derived from the root `EffectId`; replay returns the durable prior result and a conflicting identity/payload fails closed.
- `GetDraftDesignDigest` – returns the publish-gating digest for Draft world templates using the typed scope request `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`. World Management supports `versionId` scope only and must return `UNSUPPORTED_SCOPE` for `scriptPatchVersion`.
- `ValidateWorldUpgradeMappings` – validates world-owned durable references and approved remap sets for replacement-instance cutover to a target `(tenantId, versionId)`.
- `PrepareWorldInstance` – creates or reuses the canonical `PREPARING` world lifecycle row for a resolved launch descriptor, validates release-bundle and `versionStateEpoch` proof against Game Design, and materializes first-cut instance topology rows without admitting gameplay yet. Its idempotent local writes and step result commit together.
- `ActivatePreparedWorldInstance` – performs the storage-level compare-and-set `PREPARING -> ACTIVE` lifecycle transition after Game Session has finished local start-up work for the same `gameInstanceId`; a termination transition that already advanced the epoch makes stale activation fail.
- `FailPreparedWorldInstance` – performs the storage-level compare-and-set `PREPARING -> FAILED_PRE_ACTIVATION` transition when Game Session or another pre-admission consumer must close a prepared instance before admission opens. Cleanup completion is tracked separately and is not implied by this response.
- `GetWorldInstanceLifecycle` – returns the authoritative database-backed lifecycle state and epoch for an existing `(tenantId, gameInstanceId)`, together with separate cleanup request and owner-acknowledgement progress when present, so stop/cutover consumers retry against durable truth instead of cached or Temporal-projected guesses.
- `TerminateWorldInstance` – requests or resumes fenced termination from `PREPARING` or `ACTIVE` under a stable `terminationRequestId`. It reports `TERMINATED` only after every registered durable `gameInstanceId` data owner has acknowledged cleanup and the final storage compare-and-set commits.

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

- The target input identifies `tenantId`, stable `playableStateNamespaceId`, `sourceGameInstanceId`, exact source and target versions, and optional approved `remapSetId`. Shared realms use the tenant namespace, isolated realms retain a stable realm namespace, and every new playtest uses a new namespace; none use replaceable `gameInstanceId` as durable playable identity.
- Response exhaustively enumerates every checked World-owned row family, its owner, S1/S2/S3 classification, row count, referenced template identifiers, outcome, and any unknown/unclassified row. Unknown state blocks cutover; only explicitly classified S3 state may be discarded.
- For S2, World Management must validate the actual approved versioned mapping against the exact versions and rows, apply it idempotently to World-owned state, and report mapping version plus validation/application outcome. Echoing `remapSetId` is insufficient. Paid value, unique ownership, progression, housing, or equivalent durable state cannot become S3 merely because mapping is difficult.
- If the service currently has no `S2` row families for a tenant/version pair, it must report that explicitly rather than implying compatibility from an empty response.
- The participant result includes an owner freshness epoch and contributes unchanged to the durable cutover preflight summary. Cutover revalidates namespace, classifications, mappings, lifecycle, and freshness after source writes are fenced and immediately before pointer swap.

Current live first slice:

- The RPC now exists and returns the canonical cutover-validation payload shape.
- The implementation currently proves the source `world_instance` exists for `(tenantId, sourceGameInstanceId)`, requires a cutover-eligible world lifecycle state, and verifies retained instance topology rows for `region_instance`, `zone_instance`, and `room_instance` while still reporting the initial World-owned `S3` families only.
- World therefore currently returns `stateClassesChecked=["S3"]`, `checkedFamilies=["world_instance", "region_instance", "zone_instance", "room_instance", "room_instance_exit", "world_event"]`, `hasS2Rows=false`, and `remapSetRequired=false`; it returns `INCOMPATIBLE` when the source world lifecycle or retained topology is not cutover-eligible.
- This remains a shallow implementation boundary: it does not yet prove stable namespace identity, exhaustive unknown-state blocking, actual S2 mapping validation/application, durable summary completeness, or owner freshness at fenced cutover. Later World-owned durable metadata requires the target contract above and may require the RPC schema to widen.

Illustrative responses:

```json
{
  "tenantId": "t1",
  "sourceGameInstanceId": "g-old",
  "targetVersionId": "v2",
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
- a stable `worldSnapshotId` for LOOK-relevant world data. The live proto uses this as the room-read fence; future tick-ledger work may add an `asOfTickId` only through a coordinated proto and architecture update;
- `roomName` and optional slug;
- `shortDescription` and `longDescription`, with truncation rules governed by `LOOK_MAX_DESCRIPTION_CHARS`;
- `exits`, including label, `targetRoomInstanceId`, and human-friendly direction text;
- `ambientState` as the typed canonical form; and
- optional `roomFlags` for gameplay/UI warning surfaces.

Room snapshots deliberately exclude live entities, items, and inventory contents. Those are fetched from Entity Management using room- and instance-scoped queries.

Game Logic may memoize snapshots for the duration of a tick but must refresh them after movement. World Management publishes room-mutation change events so any future validated room-view read model can use explicit `worldSnapshotId` invalidation rather than time-based guesswork. FireMUD must not treat stale rendered `LOOK` output as authoritative room truth.

Cross-service LOOK read consistency is fence-based:

- Game Logic must compare the `worldSnapshotId` fence token from `GetRoomSnapshot` with the `entitySnapshotId` returned by Entity Management `ListRoomEntities` for the same room scope.
- Entity Management must either answer with a matching same-scope fence token or return `STALE_READ_FENCE` / `READ_FENCE_UNAVAILABLE`.
- If fences do not match, Game Logic retries composition instead of returning mixed-state output.

Current World Management runtime room identity notes:

- World Management emits canonical runtime room ids as opaque text in the form `R-<roomInstanceRowId>`.
- World Management gameplay bridge readers fail closed on legacy `room-1021` or `1021` request forms; callers must send the canonical opaque runtime room id and must not infer row-id semantics from its shape.

Illustrative `GetRoomSnapshot` fragments:

```json
{
  "tenantId": "t1",
  "gameInstanceId": "g1",
  "roomInstanceId": "R-1021",
  "worldSnapshotId": "t1:g1:R-1021",
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

World Management owns the authoritative lifecycle row and monotonic `lifecycle_epoch` for each `gameInstanceId`; Temporal coordinates teardown but its history and status are not admission or termination authority. Teardown remains cross-service:

- Game Session must first mark an `ACTIVE` instance non-admissible and draining. A `PREPARING` instance is already non-admissible.
- During replacement, the old instance remains retained while the target is prepared and validated. Termination cannot begin until the fenced realm-pointer swap is durable, old admission is closed, sessions have drained or moved under the lifecycle contract, and all owners acknowledge cleanup safety.
- Expiry or operator shutdown starts or signals the durable Temporal `world-lifecycle` workflow. The owning World activity performs a storage-level compare-and-set from `PREPARING` or `ACTIVE` to `TERMINATING` under the expected epoch.
- A successful `PREPARING -> TERMINATING` transition fences activation. If activation and termination race, the database compare-and-set selects the winner; stale callers reread lifecycle truth and do not infer precedence from workflow order.
- Termination records a stable request identity, the required registered durable data-owner set, and separate per-owner cleanup state. Each owner acknowledges its idempotent local cleanup in durable storage; ambiguous activity delivery must replay to the stored result.
- Entity Management's containment and room-ground containers are one required owner family, not the complete future owner set. World Management marks the instance `TERMINATED` only after every registered owner has acknowledged cleanup and the final `TERMINATING -> TERMINATED` compare-and-set succeeds.
- Cleanup may delete only families explicitly classified as S3 for that instance. S1/S2 namespace state is not generic instance cleanup payload, and unknown or unclassified state blocks cleanup.
- `FAILED_PRE_ACTIVATION` is admission-terminal for the `gameInstanceId`, but its separate cleanup state may remain pending or failed. The lifecycle status must not be presented as proof of cleanup completion.
- Any new durable family keyed by `gameInstanceId` must register its ownership, replacement-state classification, termination cleanup, stable operation identity, acknowledgement, retry, and retention contract before launch paths write it. Missing or unknown owner acknowledgement fails finalization closed.
- Scheduled expiry jobs must start or signal the lifecycle workflow and must not directly delete world rows for a still-unconfirmed termination.
- Game Session finalizes runtime `game_instances` termination only after World reports authoritative `TERMINATED`.
- Routine gameplay and tick execution do not call Temporal or wait on cleanup state; they use the lifecycle surface only at admission, cutover, and termination boundaries.

Current implementation notes:

- The first canonical activation seam is now live for the `StartSession` path.
- World Management persists `world_instance`, `region_instance`, `zone_instance`, `room_instance`, and `room_instance_exit` rows keyed by `(tenantId, gameInstanceId)` and uses `lifecycle_epoch` as the current fence token for prepare/activate/fail/terminate transitions.
- `world_event` is now runtime-owned and keyed by `(tenantId, gameInstanceId)` via `region_instance`, rather than hanging off template `region` rows.
- `stopSession` now consumes `GetWorldInstanceLifecycle` + `TerminateWorldInstance` rather than the old ping-only shutdown path; Entity Management cleanup runs through `CleanupRuntimeInstance`, and World hard-deletes its own runtime rows (`world_event`, room exits, rooms, zones, regions) before reporting `TERMINATED`.
- The live termination path is still the synchronous active-instance first cut. It does not yet accept `PREPARING` termination or persist separate cleanup request/owner acknowledgement state, and its Entity-plus-World sequence is not the extensible all-owner gate required above.
- Lifecycle/cleanup metrics and alerts do not yet prove stuck-state detection or cleanup convergence.
- Replacement-instance/cutover callers beyond explicit stop/termination are still follow-on work.

## LOOK Consumer Notes

Telnet and WebSocket clients both route through the `/ws/game/**` gameplay path, so `LOOK` commands hit the same `LookCommandHandler` regardless of transport.

- Authenticated success responses follow the canonical textual contract described in the gameplay docs.
- Game Session renders the `LookResult` returned by Game Logic, which already includes both world and entity projections, into the textual transcript via `LookResultRenderer`.
- Error responses emit `ERROR <CODE> <message>` covering `ROOM_NOT_FOUND`, `WORLD_UNAVAILABLE`, `ENTITY_UNAVAILABLE`, `LOOK_UNAVAILABLE`, and `NOT_AUTHENTICATED`.

The sample rooms referenced by the LOOK lifecycle are provided by the canonical LOOK cross-service fixtures so integration tests and transcript examples remain stable.
