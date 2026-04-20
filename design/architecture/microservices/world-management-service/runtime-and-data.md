# World Management Service Runtime and Data

## Template and Runtime Ownership

World Management owns both version-scoped template topology and runtime world-instance state, but the two surfaces are strictly separated:

- Template tables are keyed by `(tenantId, versionId)` and updated only through design-time workflows coordinated by Game Design.
- Runtime instance tables are keyed by `(tenantId, gameInstanceId)` and created or mutated only by world-creation Sagas and tick-driven gameplay flows.
- Runtime logic must never modify template rows for published versions.

World Management instances are intended to be replaceable workers over authoritative runtime storage. Runtime room/location/ambient state that matters after process loss belongs in the service-owned database rows and documented caches, not as the sole authoritative copy in one JVM. Another World Management instance of the same type should therefore be able to continue serving and mutating runtime world state after restart without that restart itself becoming the player-visible event.

### Template Identifier Invariants

World templates follow the same stability rules as entity templates:

- Template identifiers for regions, zones, rooms, and related topology must not be repurposed to represent different conceptual locations while any non-Retired version or game template still references them.
- Structural world-layout changes must be modeled as new template rows under the appropriate `(tenantId, versionId)` rather than mutating existing identifiers in place.
- Cross-service references from Game Design or other domain services must use stable template identifiers scoped by `(tenantId, versionId)` and follow version-aware migration rules.

## Character Location Ownership

World Management is the authoritative owner of character and NPC location for each game instance:

- Runtime tables such as `character_location` and `npc_location` live in this service’s schema and are written only by World Management logic as part of movement, instancing, and world-creation flows invoked by Game Session.
- Other services, including Entity Management and Game Session, treat these tables as read-only and rely on World Management gRPC APIs or cached projections.
- Any derived caches or denormalized views of location must be refreshed from World Management rather than persisting independent authoritative location fields.

### Spatial Effects Contract

Movement, drops, pickups, and room presence are cross-service by design:

- World Management is authoritative for occupancy/location and persistent ambient room state keyed by runtime `RoomInstanceRef`.
- Entity Management is authoritative for containment and item instances, including synthetic room-ground containers keyed by the same `RoomInstanceRef`.
- All spatial effects must carry the target `RoomInstanceRef` and a canonical tick `EffectId`.
- Both services must implement durable idempotency guards so partial success can be retried safely until convergence.
- Cross-service retry orchestration is owned by the Game Session reconciliation backlog. World Management exposes participant acknowledgements for each `EffectId`; it is not the owner of cross-service retry scheduling.
- Ambient world mutations such as doors, hazards, and weather are applied only via effect-shaped commands carrying `EffectId` plus `RoomInstanceRef`. Operators and scripts must not write World Management instance tables directly.

Concrete per-effect required writes and reconciliation rules live in [Spatial and Ambient Effects Catalog](../../system-architecture-spatial-and-ambient-effects-catalog.md).

## Redis Role and Cache Usage

### Coordination Redis participation

- World Management does not own tick or session coordination prefixes. Tick queues, locks, timers, and region leases remain owned by Game Session and its Lua registry.
- It interacts with Coordination Redis only indirectly through Game Session and Automation & Scripting APIs. It does not issue coordination writes itself.

### Cache/Rate-Limit Redis usage

- World Management uses Cache/Rate-Limit Redis to cache hot room and topology slices for active sessions under prefixes such as `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` and `world-dynamic:<tenantId>:<aggregateId>`.
- These caches are derived from PostgreSQL tables and are treated as versioned, Class A caches where version fields exist. Consumers compare versions to authoritative values before reuse and recompute on mismatch.
- Simpler read-mostly slices may use TTL-only Class B caches only when the design explicitly says occasional staleness is acceptable and the prefixes are separately registered in the central cache catalog. `room:*` and `world-dynamic:*` remain reserved for versioned Class A aggregates; any TTL-only world caches must use distinct prefixes and be added to the central cache catalog before implementation.
- Domain events for room changes, region version activations, or world updates drive explicit deletion or refresh of affected `room:*` / `world-dynamic:*` keys.
- TTL acts as a safety valve and memory control, not the primary correctness mechanism for Class A caches.
- Cache metrics for `world-dynamic:*` and `room:*` should follow the shared cache naming guidance so operators can see hit/miss behavior and key counts.
- Tests for these caches are expected to exercise the Class A scenarios from the shared cache architecture, including version mismatches, event-driven invalidation, and behavior after cache resets.

When changing Redis usage or adding prefixes here, follow the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md).

## Data Model

### Template tables

- `region_template`, `zone_template`, and `room_template` define the versioned world hierarchy for each game and are immutable once a version reaches Published.
- `terrain_template` and related tables capture generator outputs or authored terrain data where it is part of the versioned topology.

### Instance tables

- Implementation Notes:

- The first runtime lifecycle substrate is now live through `world_instance` plus first-cut `region_instance` rows keyed by `(tenantId, gameInstanceId)`.
- `lifecycle_epoch` is the current concrete fenced token used by the prepare/activate/fail/terminate lifecycle RPCs.
- `world_instance.termination_request_id` and `terminated_at` now retain the canonical shutdown workflow identity and terminal completion timestamp for runtime-instance teardown.
- `zone_instance`, `room_instance`, and `room_instance_exit` are now live as runtime topology storage keyed by `(tenantId, gameInstanceId, ...)`, so the main named follow-through work has moved from topology rows to later runtime world-state families.

- `region_instance`, `zone_instance`, `room_instance`, and `room_instance_exit` materialize topology for a running game instance based on the chosen version and any runtime procedural generation.
- `instance` tracks temporary copies of zones for instanced gameplay, with `expires_at` defining when instances enter `InstanceTermination`.
- `world_instance_status`, or equivalent lifecycle state, tracks monotonic lifecycle transitions: `PREPARING -> (ACTIVE | FAILED_PRE_ACTIVATION)` and `ACTIVE -> TERMINATING -> TERMINATED`.
- `FAILED_PRE_ACTIVATION` is terminal for that `gameInstanceId`; recovery is modeled as provisioning a new `gameInstanceId` and rerunning world creation.
- `world_instance_lifecycle_lock`, or equivalent fenced token, enforces single-writer lifecycle transitions per `(tenantId, gameInstanceId)`.
- `character_location` records the current room for each character, including instance occupancy.

### Runtime configuration and events

- `generation_rule_template`, or equivalent version-scoped tables, stores publish-affecting procedural-generation inputs keyed by `(tenantId, versionId)` for Draft/Published design data.
- A separate tenant-scoped `generation_runtime_default` table may hold operational-only runtime knobs that do not affect publishable template output and must never be consulted when deriving a published version’s `generationConfigRevision`.
- Published versions carry a frozen `generationConfigRevision` or equivalent hash derived from the version-scoped design inputs committed at publish time.
- `generation_run`, or equivalent, persists deterministic generation artifacts for replay-safe publish and reconciliation.
- Runtime-instance `generation_run` rows tied to instance creation are diagnostic/runtime provenance only and are not cutover payload rows.
- `world_event` stores timed runtime changes such as weather updates and is keyed by `(tenantId, gameInstanceId)` with optional `region_instance` scope.
- `region_instance.weather`, or equivalent, records current weather state for live regions.

Redis caches hot rooms for active sessions to speed up lookups. Cached rooms use keys `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` and must never be keyed by template identifiers because runtime rows may diverge from template state.

## Replacement-Instance State Classification

World Management must classify its runtime persistence surface for cutover and migration tooling:

- `S2` world-owned durable state:
  - none are mandatory in the initial implementation slice;
  - any future world-bound metadata that survives beyond a single room-instance layout and references versioned templates.
- `S3` world-owned ephemeral state:
  - `region_instance`, `zone_instance`, `room_instance`, `room_instance_exit`;
  - `character_location`, `npc_location`, and occupancy rows;
  - ambient runtime state such as weather, door state, hazard state, instanced events, and instance-scoped schedules;
  - `world_event` rows tied to a specific `gameInstanceId`.
  - `population_schedule_instance` rows keyed by `gameInstanceId`;
  - runtime-instance `generation_run` rows retained only for diagnostic or provenance purposes.

Initial-slice rule:

- Unless a world-owned row family is explicitly documented as `S2`, treat it as `S3` and discard it during replacement-instance cutover.
- World Management must not silently copy room ambient state, occupancy, scheduled world events, or generated instance topology into the target instance.
- No World-owned initial-slice table is classified as mandatory `S2`.
- The live first validation cut is still honest about that boundary, but it now does more than existence-check one row: replacement-instance preflight requires the source `world_instance` to be in a cutover-eligible lifecycle state and to retain `region_instance`, `zone_instance`, and `room_instance` topology before World Management returns `COMPATIBLE`.

## Digest Input Manifest

World Management is a required publish-gate participant and maintains a stable digest manifest for `GetDraftDesignDigest(versionId)`:

Implementation Notes:

- The current implementation computes the digest from the tenant’s draft topology rows using the existing tenant-scoped schema and returns synthetic `appliedCommitId = "version:<versionId>"`.
- That keeps the publish gate honest against the current data model while the broader target-state `(tenantId, versionId)` draft graph is still being shaped into service storage.

- Included objects:
  - version-scoped topology tables such as `region_template`, `zone_template`, `room_template`, `terrain_template`, `room_exit_template`, and equivalent normalized topology relations;
  - version-scoped declarative spawn and population binding tables;
  - version-scoped generation design inputs such as `generation_rule_template`.
- Excluded objects:
  - all runtime/instance tables keyed by `gameInstanceId`;
  - `generation_run` rows created for runtime instances;
  - staged or diagnostic generation artifacts that do not contribute to published topology semantics;
  - audit/provenance columns that do not affect semantics.
  - navmesh/path-graph payload bytes and related object-store metadata, which are attested separately through publish-workflow artifact digests rather than folded into `contentDigest`.
- Canonicalization rules:
  - stable table ordering;
  - primary-key ordering within each table;
  - deterministic encoding for included semantic fields.
- `digestSchemaVersion` must increment whenever included tables, field-selection rules, or canonical serialization semantics change.

Publish gating fails closed if World Management cannot attest a digest consistent with this manifest.

In the initial slice, exported world artifacts such as navmesh/path-graph bundles are not folded into World Management’s `contentDigest`. Instead, the publish workflow must attest them separately through typed artifact digest entries and manifest usage keys bound to the same release identity as the world participant digest.

## Instance-Scoped Population Schedule Contract

Runtime population materialization is documented separately from published spawn bindings:

- Version-scoped spawn bindings are publish-time design data and are the only population records included in `GetDraftDesignDigest`.
- Instance-scoped population schedules/materializations are runtime rows keyed by `(tenantId, gameInstanceId, scheduleId or roomInstanceId, sourceBindingId)` and derived from published bindings during world creation or runtime instancing.
- These runtime schedule rows must record provenance back to the published binding and, when relevant, the `generationRunId` that materialized them.
- Runtime schedules are not part of publish digests and must be recreated or restored only through runtime workflows.
- Active schedule rows survive normal restarts for the same `(tenantId, gameInstanceId)` and are cleaned up only through the documented termination or recovery lifecycle.
- `InstanceTermination` hard-deletes schedule rows for the terminating instance after any bounded diagnostic export has completed.
- Optional diagnostics for failed activation or termination must live in separate bounded-retention diagnostic tables or exports.

Initial-slice scope:

- Instance-scoped population schedules are materialized only during world creation for the primary `gameInstanceId`.
- Later runtime instancing or portal-driven population scheduling may reuse the same lifecycle contract, but those flows are not part of the initial slice.
- Until a concrete schema is published, implementation docs must use one stable row-family name for these rows, map it explicitly to the concrete table names used by the service, and document cleanup ordering for termination, backup, and replay tooling.

## World Events

World events are persisted in `world_event` and processed periodically by `WorldEventService`.

World event invariants:

- Events are runtime-only and keyed by `(tenantId, gameInstanceId)`, never `(tenantId, versionId)`.
- `world_event.region_instance_id` must reference runtime `region_instance` rows, not template `region` rows.
- Event application must be idempotent through a stable event identity or derived identity tuple.
- Weather change events update runtime weather state before notifying other services.
- Event application must use the same effect-shaped ambient mutation contract used by tick execution, guarded by `EffectId` and scoped by runtime instance identifiers.
