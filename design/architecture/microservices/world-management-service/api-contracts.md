# World Management Service API Contracts

## Implementation Status

This API contract is target-state canonical; implementation coverage is partial and must not be inferred from target examples alone.

- The current World Management gameplay bridge serializes runtime room ids as `R-<roomInstanceRowId>` and rejects scoped decimal `roomInstanceId` values such as `1021`. That storage-derived encoding is not the canonical target identity defined by the [Identifier Glossary](../../system-architecture-identifier-glossary.md).
- The bridge, cross-service callers, examples, and focused proof have not migrated together to the scoped decimal `roomInstanceId` contract. The checked-in proof still exercises the legacy encoding.
- The typed `ApplyRoomAmbientStatePatch` contract below is target-state: the current service has no complete handler or durable operation/digest-bound guard, so player-significant ambient mutations remain on the fenced Game Session admission/reconciliation path and are not direct table writes.
- Required executable migration/proof gate: `./gradlew :world-management-service:test :game-logic-service:test :game-session-service:test :tcp-proxy-service:crossServiceTest`. This gate must update the bridge, callers, examples, and checked-in proof together and pass before the target examples below may be treated as current. This document does not claim that the gate exists or passes.

## gRPC APIs

- `GetRoom` – retrieves room data including exits and environmental effects through `RoomInstanceRef`.
- `GetRoomSnapshot` – returns a minimal, LOOK-focused view scoped by `RoomInstanceRef`.
- `ListRoomOccupants` – returns the authoritative typed occupant list for actors in a room, scoped by `RoomInstanceRef`.
- `ApplyRoomAmbientStatePatch` – applies a typed ambient fact patch to the target runtime scope under expected epoch/version preconditions and an operation/aggregate/request-digest-bound guard derived from the root `EffectId`; matching replay returns the durable prior result and a conflicting identity or payload fails closed.
- `GetDraftDesignDigest` – returns the publish-gating digest for Draft world templates using the typed scope request `GetDraftDesignDigestRequest { tenantId, scope: oneof { versionId, scriptPatchVersion } }`. World Management supports `versionId` scope only and must return `UNSUPPORTED_SCOPE` for `scriptPatchVersion`.
- `ValidateWorldUpgradeMappings` – validates world-owned durable references and approved remap sets for replacement-instance cutover to a target `(tenantId, versionId)`.
- `PrepareWorldInstance` – creates or reuses the canonical `PREPARING` world lifecycle row for a resolved launch descriptor, validates release-bundle and `versionStateEpoch` proof against Game Design, and materializes first-cut instance topology rows without admitting gameplay yet.
- `ActivatePreparedWorldInstance` – performs the fenced `PREPARING -> ACTIVE` lifecycle transition after Game Session has finished local start-up work for the same `gameInstanceId`.
- `FailPreparedWorldInstance` – performs the fenced `PREPARING -> FAILED_PRE_ACTIVATION` transition when Game Session or another pre-admission consumer must roll back a prepared instance before admission opens.
- `GetWorldInstanceLifecycle` – returns the current fenced lifecycle snapshot for an existing `(tenantId, gameInstanceId)` so stop/cutover consumers can retry against fresh lifecycle truth instead of cached guesses.
- `TerminateWorldInstance` – requests or resumes fenced termination from `PREPARING`, `FAILED_PRE_ACTIVATION`, or `ACTIVE`; it reports `TERMINATED` only after every registered durable instance-data owner acknowledges cleanup and the final lifecycle CAS commits.

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

`ValidateWorldUpgradeMappings` is World Management's owner-local participant in the replacement contract owned by [ADR 0122](../../decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md). It must expose enough detail for cutover tooling to reason about World-owned references without becoming a second classification authority:

- Input identifies `tenantId`, stable `playableStateNamespaceId`, `sourceGameInstanceId`, exact `sourceVersionId` and `targetVersionId`, and optional approved `remapSetId`. A target replacement request may also carry the prepared `targetGameInstanceId`; it is evidence for the same cutover, not a new World authority.
- Response enumerates every registered World-owned row family, owner, local S1/S2/S3 classification, count, referenced template identifiers, outcome, unknown/unclassified state, freshness epoch, and any mapping validation/application result. It binds that enumeration to the exact common state-family/owner-registry revision or to a complete set of owner-scoped catalog epochs; each family evidence row identifies the applicable owner catalog epoch and freshness evidence when owner-scoped catalogs are used. `remapSetId` must resolve to immutable persisted Game Design approval and exact source-version-to-target-version mapping evidence; a supplied or echoed id is not proof of validation or application. Unknown or unregistered state is incompatible.
- The response declares its report capability and completeness and binds the exact namespace, source/target versions, source instance, and optional target instance from the request. World may return `COMPATIBLE` only for a supported, complete report with exhaustive family evidence, current freshness evidence, the exact registry revision or complete owner-catalog epochs, and any required mapping validation/application proof. A missing, unsupported, or incomplete report cannot be interpreted as `COMPATIBLE`; family registration, ownership, or classification drift after preflight invalidates the report rather than being hidden behind an unchanged family count.
- If the service currently has no `S2` row families for a tenant/version pair, it must report that explicitly rather than implying compatibility from an empty response.

Current live first slice:

- The RPC now exists and returns the canonical cutover-validation payload shape.
- The implementation currently proves the source `world_instance` exists for `(tenantId, sourceGameInstanceId)`, requires a cutover-eligible world lifecycle state, and verifies retained instance topology rows for `region_instance`, `zone_instance`, and `room_instance` while still reporting only the initial World-owned `S3` families. It does not yet validate the exact namespace, source/target version pair, target instance binding, report capability/completeness, exact registry revision or owner-scoped catalog epochs, or persisted remap evidence required above; those are explicit implementation/proof gaps.
- World therefore currently returns `stateClassesChecked=["S3"]`, `checkedFamilies=["world_instance", "region_instance", "zone_instance", "room_instance", "room_instance_exit", "world_event"]`, `hasS2Rows=false`, and `remapSetRequired=false`; it returns `INCOMPATIBLE` when the source world lifecycle or retained topology is not cutover-eligible.
- Later World-owned durable metadata families can widen this contract to real `S2` checks without changing the owning RPC surface.

Target illustrative responses (field names remain conceptual until the coordinated wire change):

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "playableStateNamespaceId": "0d47c2a7-9b3d-4f52-8a11-6e0c4d88b3f7",
  "sourceGameInstanceId": "2e3ee139-a6e8-44ad-b840-891b22c2255b",
  "sourceVersionId": "54ce6198-ccea-4f94-8541-ec8ca322070d",
  "targetGameInstanceId": "4862ba66-fda2-490a-97e9-28358fbd0888",
  "targetVersionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
  "reportCapability": "EXHAUSTIVE_WORLD_FAMILY_EVIDENCE",
  "complete": true,
  "registryEvidenceMode": "OWNER_SCOPED_CATALOG_EPOCHS",
  "familyEvidence": [
    {"family": "world_instance", "owner": "world-management", "ownerCatalogEpoch": 17, "freshnessEpoch": 41, "classification": "S3", "count": 1, "outcome": "COMPATIBLE"},
    {"family": "region_instance", "owner": "world-management", "ownerCatalogEpoch": 17, "freshnessEpoch": 41, "classification": "S3", "count": 4, "outcome": "COMPATIBLE"},
    {"family": "zone_instance", "owner": "world-management", "ownerCatalogEpoch": 17, "freshnessEpoch": 41, "classification": "S3", "count": 12, "outcome": "COMPATIBLE"},
    {"family": "room_instance", "owner": "world-management", "ownerCatalogEpoch": 17, "freshnessEpoch": 41, "classification": "S3", "count": 96, "outcome": "COMPATIBLE"},
    {"family": "room_instance_exit", "owner": "world-management", "ownerCatalogEpoch": 17, "freshnessEpoch": 41, "classification": "S3", "count": 144, "outcome": "COMPATIBLE"},
    {"family": "world_event", "owner": "world-management", "ownerCatalogEpoch": 17, "freshnessEpoch": 41, "classification": "S3", "count": 3, "outcome": "COMPATIBLE"}
  ],
  "unknownFamilies": [],
  "mappingProof": {"required": false, "validation": "NOT_REQUIRED", "application": "NOT_REQUIRED"},
  "hasS2Rows": false,
  "result": "COMPATIBLE",
  "remapSetRequired": false
}
```

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "playableStateNamespaceId": "0d47c2a7-9b3d-4f52-8a11-6e0c4d88b3f7",
  "sourceGameInstanceId": "2e3ee139-a6e8-44ad-b840-891b22c2255b",
  "sourceVersionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
  "targetGameInstanceId": "f4ba2a7a-ee8f-43eb-af1a-749c07773a3a",
  "targetVersionId": "8e65e4a1-5b49-4c31-9f27-3d0b8c6a1e74",
  "reportCapability": "EXHAUSTIVE_WORLD_FAMILY_EVIDENCE",
  "complete": true,
  "registryEvidenceMode": "OWNER_SCOPED_CATALOG_EPOCHS",
  "familyEvidence": [
    {
      "family": "housing_anchor",
      "owner": "world-management",
      "ownerCatalogEpoch": 18,
      "freshnessEpoch": 45,
      "classification": "S2",
      "count": 1,
      "referencedTemplateIds": ["roomTemplateId:starter-house-01"],
      "outcome": "REQUIRES_MAPPING"
    }
  ],
  "unknownFamilies": [],
  "mappingProof": {"required": true, "validation": "MISSING", "application": "NOT_APPLIED"},
  "hasS2Rows": true,
  "result": "INCOMPATIBLE",
  "remapSetRequired": true
}
```

## LOOK Snapshot Contract

`GetRoomSnapshot` is the canonical endpoint feeding Game Logic's `ResolveLook`. The current and target fence contracts are intentionally separate.

### Current room-snapshot contract

The current `GetRoomSnapshotRequest` carries `tenantId`, `RoomInstanceRef`, locale, and session attestation; it is floor-free and does not carry a caller-provided read-fence field. The current `worldSnapshotId` response value is a deterministic room-scope marker, not proof of a committed mutation version.

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

### Target causal-floor contract

The target protocol follows the [causal-read fence contract](../../system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity). Game Session allocates the target `CausalReadFence`, including the operational `regionId`, from durable region commit authority before invoking `ResolveLook`; Game Logic propagates that floor unchanged to World Management and Entity Management. A target `GetRoomSnapshot` request carries the floor for the same `RoomInstanceRef`, `regionId`, and `regionEpoch`, including at least `committedTickId`. The exact request-field shape remains deferred to the coordinated proto/design change; the current request is floor-free and the current implementation does not claim this behavior complete.

When the target protocol is implemented, World Management serves the requested room, region, and epoch scope and returns the same region/scope/epoch proof, a scoped `servedThroughTickId`, and an opaque World component version. That returned version follows the [Identifier Glossary causal-read contract](../../system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity): World advances it atomically with relevant durable owner commits and returns the stored version on matching replay/no-op. If World cannot serve through the requested floor from its durable state, it returns `STALE_READ_FENCE` or `READ_FENCE_UNAVAILABLE` as applicable. If World returns a scoped `servedThroughTickId` below the requested `committedTickId`, Game Logic treats that as a caller-side retry/composition rejection rather than a second World service error. Mixed tenant, game instance, region, room, or epoch is rejected for composition, and Game Logic retries the participant or composition rather than treating scope markers or opaque component versions as directly comparable. World and Entity do not echo or mint one shared temporal token, and this contract does not claim an exact cross-database historical snapshot.

Game Logic validates each participant's region/scope/epoch served-through proof, then composes only the requested causal floor plus the World and Entity opaque component versions in the room-view identity. It retries a participant that is behind the floor or has mixed region/scope/epoch; it does not require exact equality between the current `worldSnapshotId` and `entitySnapshotId` markers or compare opaque component versions directly.

## Room Identity Migration

### Target behavior

The target runtime identity at this boundary is `RoomInstanceRef = {tenantId, gameInstanceId, roomInstanceId}`. `roomInstanceId` is a service-owned runtime identity, not a database row id. When a string is required at a transport boundary, it uses canonical decimal text such as `1021`; the value remains opaque outside the full `RoomInstanceRef` scope.

These examples remain target-only until the required executable migration/proof gate in [Implementation Status](#implementation-status) has updated the bridge, callers, examples, and checked-in proof and has passed.

Illustrative target-state `GetRoomSnapshot` fragments:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "gameInstanceId": "9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78",
  "regionId": "R7",
  "regionEpoch": 17,
  "roomInstanceId": "1021",
  "servedThroughTickId": 42,
  "worldComponentVersion": "world-component-version-17",
  "roomName": "Candle-lit Antechamber"
}
```

```json
{
  "error": {
    "code": "READ_FENCE_UNAVAILABLE",
    "message": "Room snapshot could not be materialized for the requested causal read floor."
  }
}
```

The current `worldSnapshotId` remains the deterministic same-scope marker described above and provides no mutation-freshness proof. In the target protocol, the response includes the same region/scope/epoch proof, an opaque World component version, and scoped `servedThroughTickId` after serving the requested causal floor; it is not a shared token that Entity Management must echo. Game Logic validates each participant's served-through proof and exposes only the requested floor plus the World and Entity opaque component versions in the composite room-view identity. Same-region/scope/epoch responses with served-through values at or beyond the requested floor may compose, while `STALE_READ_FENCE` or `READ_FENCE_UNAVAILABLE` responses and mixed-region/scope/epoch composition failures are retried according to the Game Logic contract. Opaque component versions are not directly compared, and no exact cross-database historical snapshot is claimed.

The unresolved target work is tracked in [World Runtime and Movement](../../../project-management/implementation-tracking/world-runtime-and-movement.md#active-gaps): Game Session floor allocation from durable region commit authority and Game Logic propagation are target obligations, while World participant floor satisfaction and the opaque component-version plus scoped `servedThroughTickId` response remain unimplemented and unproved. The current proto/request and focused proof do not carry or demonstrate this contract; the exact coordinated wire shape and proof encoding remain implementation gaps.

## Instance Termination Contract

World Management owns the authoritative lifecycle row and monotonic epoch for `gameInstanceId` rows. The lifecycle state, owner registry, cleanup acknowledgements, and Temporal boundary are owned by [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md); this section records the World API consequence. Teardown is cross-service:

- Game Session must first mark the instance non-admissible and draining before World transitions lifecycle.
- A termination request may fence `PREPARING`, `FAILED_PRE_ACTIVATION`, or `ACTIVE` to `TERMINATING` through the durable Temporal `world-lifecycle` workflow; the same epoch makes stale activation fail.
- Every registered durable `gameInstanceId` owner must acknowledge idempotent cleanup in its own durable state before World marks the instance `TERMINATED`; Entity Management is one required owner, not the complete future registry.
- `FAILED_PRE_ACTIVATION` is admission-terminal but does not imply cleanup completion; its cleanup progress is read separately from lifecycle status.
- Scheduled expiry jobs must start or signal the lifecycle workflow and must not directly delete world rows for a still-unconfirmed termination.
- Lifecycle fencing is mandatory. Every transition is a storage-level compare-and-set against expected state and epoch; if activation and termination race, only the winning CAS advances the row and stale callers reread authoritative state.
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
