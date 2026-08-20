# World Management Service Runtime and Data

## Implementation Status

- The current room-cache implementation remains an unversioned TTL-only payload and must use authoritative reads until the target opaque component-version and invalidation proof exists.

## Template and Runtime Ownership

World Management owns both version-scoped template topology and runtime world-instance state, but the two surfaces are strictly separated. Stable playable identity and replacement classification are governed by [ADR 0122](../../decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md); this document records only World Management's storage and owner-local consequences:

- Template tables are keyed by `(tenantId, versionId)` and updated only through design-time workflows coordinated by Game Design.
- Runtime topology and explicitly disposable instance state are keyed by `(tenantId, gameInstanceId)` and created or mutated only by the world-lifecycle workflow and tick-driven gameplay flows. Any World-owned durable playable state is keyed by `(tenantId, playableStateNamespaceId)` and retains active-instance context only for authorization.
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

World Management is the fact owner for targeting predicates over location, occupancy, range, and mutable room state. Its bounded `TargetingFactSnapshot` responses contain only the compiled source/candidate facts for the requested `RoomInstanceRef` scope and a current world location/version token. For `DROP` and `PICKUP`, the target attestation carries `regionId` from Game Session's durable region authority alongside `RoomInstanceRef`, `regionEpoch`, `executorFence`, the same root `EffectId`, and the unchanged immutable `requestDigest`; World validates its own token and rejects mismatched scope, epoch, or fence. The canonical barrier, Game Logic re-resolution, and Entity commit binding are defined in [Transaction Strategies](../../system-architecture-transactions.md#drop-pickup-targeting-and-actor-fence-critical-section) and [ADR 0054](../../decisions/adr-0054-split-spatial-authority-with-causal-read-composition.md). The current proto/request and focused proof do not yet demonstrate this path.

### Spatial Effects Contract

Movement, drops, pickups, and room presence are cross-service by design:

- World Management is authoritative for occupancy/location and persistent ambient world state keyed by runtime `RoomInstanceRef`; the target Weather aggregate scope remains unresolved in the owner contract. Weather mutation admission and reconciliation remain fenced and non-mutating until the exact region-scoped versus room-scoped selector is accepted, including writes represented by `world_event` and `region_instance.weather`. Initial Weather scheduling during world creation and generic `WEATHER_CHANGE` event scheduling/application follow the same rule: while the selector is unresolved they are explicitly deferred and non-mutating, so no Weather event row is inserted or processed and `region_instance.weather` is not changed.
- Entity Management is authoritative for containment and item instances, including synthetic room-ground containers keyed by the same `RoomInstanceRef`.
- All spatial effects must carry the target `RoomInstanceRef` and a canonical tick root `EffectId`; correctness-sensitive mutations also carry exact epoch and relevant owner-state preconditions.
- `DROP` and `PICKUP` use the canonical Game Session barrier/attestation sequence before the Entity-local holder commit; World Management owns only the World fact validation and attestation issuance. The binding and handoff rules are defined in [Transaction Strategies](../../system-architecture-transactions.md#drop-pickup-targeting-and-actor-fence-critical-section) and [ADR 0054](../../decisions/adr-0054-split-spatial-authority-with-causal-read-composition.md). The current proto/request and focused proof do not yet demonstrate this validation path.
- Each actual mutation participant derives an operation/aggregate-specific durable guard from the root effect and binds it to the immutable request digest and result. A service that supplies evidence but performs no mutation is not added as an artificial mutation participant. Matching retries replay the stored result; a changed operation, target, or digest fails closed.
- Game Session owns one durable reconciliation row per logical effect, with its immutable expected participant set and per-participant outcomes, and runs retries in isolated workers. World Management exposes participant acknowledgements for each root `EffectId`; it is not the owner of cross-service retry scheduling or player-outcome derivation. See [ADR 0057](../../decisions/adr-0057-game-session-owned-reconciliation-with-isolated-workers.md).
- Cross-region effects use durable asynchronous legs rather than shared region locks. World Management claims and mutates only under the current region epoch and executor fence; an old-epoch follow-up remains under its original identity for evidence-qualified reconciliation and is never silently carried into a new timeline. Proven non-application may permit `ABANDONED` and a fresh lineage-linked continuation, while inconclusive work remains non-terminal and blocks reopen. While an instance is open, region topology remains static; split/merge is an operator-controlled maintenance cutover with an admission barrier and durable rebuild, not live elasticity. See [ADR 0055](../../decisions/adr-0055-durable-cross-region-effects-with-static-live-topology.md) and [ADR 0067](../../decisions/adr-0067-abandon-old-epoch-work-and-reschedule-with-new-lineage.md).
- Ambient world mutations such as doors and hazards are applied only via typed effect-shaped commands carrying the root `EffectId`, runtime scope, expected epoch/version, operation and target identity, and immutable request digest. World owns the durable fact and authoritative version; Game Logic owns gameplay interpretation and consequences. Weather must use the eventual World-owner aggregate selector (region-scoped or room-scoped); this document does not choose between them. Until that selector is accepted, Weather admission/reconciliation and the underlying `world_event`/`region_instance.weather` writes remain fenced and non-mutating, including any generic event handler. Correctness-bearing player, automation, script, and operator changes use Game Session's durable effect-admission and outcome path. Operators and scripts must not write World Management instance tables directly. See [ADR 0060](../../decisions/adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md) and [ADR 0061](../../decisions/adr-0061-single-owner-spatial-mutations-across-split-authority.md).

Concrete per-effect required writes and reconciliation rules live in [Spatial and Ambient Effects Catalog](../../system-architecture-spatial-and-ambient-effects-catalog.md).

## Redis Role and Cache Usage

### Coordination Redis participation

- World Management does not own tick or session coordination prefixes. Tick queues, locks, timers, and region leases remain owned by Game Session and its Lua registry.
- It interacts with Coordination Redis only indirectly through Game Session and Automation & Scripting APIs. It does not issue coordination writes itself.

### Cache/Rate-Limit Redis usage

- World Management uses Cache/Rate-Limit Redis to cache hot room and topology slices for active sessions under prefixes such as `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` and `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>`.
- Target-state `room:*` payloads carry the owner-validated `regionId` and `regionEpoch` alongside the exact opaque room component version, and readers reject a mismatched scope or epoch. Payload, scope, epoch, version, and TTL refresh atomically; an epoch change invalidates the entry. This remains target-only; current readers use authoritative reads when the contract is unavailable.
- These caches are derived from PostgreSQL tables and are treated as owner-local, versioned Class A caches only where the owner can obtain current authoritative version/fence proof during the read. World Management is the only service that may consume these entries for correctness-sensitive movement, pathing, or visibility decisions; other services call World Management APIs. A version embedded only in the cached payload, a TTL, or an invalidation event is not sufficient proof. On unavailable, ambiguous, below-floor, wrong-scope, or mismatched proof, World falls back to PostgreSQL or fails closed.
- `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>` is specifically the room-scoped dynamic-state cache validated against `roomDynamicVersion`; additional dynamic world aggregates must use separately registered prefixes rather than overloading `world-dynamic:*` with a generic aggregate id.
- Simpler read-mostly slices may use TTL-only Class B caches only when the design explicitly says occasional staleness is acceptable and the prefixes are separately registered in the central cache catalog. `room:*` and `world-dynamic:*` remain reserved for versioned Class A aggregates; any TTL-only world caches must use distinct prefixes and be added to the central cache catalog before implementation.
- Domain events for room changes, region version activations, or world updates drive explicit deletion or refresh of affected `room:*` / `world-dynamic:*` keys, but invalidation is a load optimization and does not replace owner-local proof.
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
- `instance` is the legacy temporary-zone-copy table used for instanced gameplay. Its current `expires_at` job deletes rows directly; this is implementation drift, not the ADR 0123 `world_instance` lifecycle, and `expires_at` does not authorize a transition of the parent game instance. Target cleanup either models the temporary content as a complete `world_instance` with its own `gameInstanceId` and uses fenced `InstanceTermination`, or retains a separate zone-scoped idempotent cleanup contract that cannot terminate the parent.
- `world_instance_status`, or equivalent lifecycle state, tracks the ADR 0123 transitions `PREPARING -> ACTIVE`, `PREPARING -> FAILED_PRE_ACTIVATION`, `PREPARING -> TERMINATING`, `FAILED_PRE_ACTIVATION -> TERMINATING`, `ACTIVE -> TERMINATING`, and `TERMINATING -> TERMINATED`.
- `FAILED_PRE_ACTIVATION` is terminal for admission by that `gameInstanceId`, not proof that cleanup completed; separate owner-scoped cleanup progress may continue. Recovery is modeled as provisioning a new `gameInstanceId` and rerunning world creation.
- `world_instance_lifecycle_lock`, or equivalent fenced token, enforces single-writer lifecycle transitions per `(tenantId, gameInstanceId)`.
- `character_location` records the current room for each character, including instance occupancy.

### Replacement cutover hold ownership

World Management also owns a separate durable one-shot cutover hold for replacement routing. The hold is not a `world_instance` lifecycle state and does not transfer lifecycle ownership to Game Session. Its conceptual record binds one opaque `cutoverHoldId` and equality-only `cutoverHoldFence` to `preparedVersionUpgradeId`, the exact `controlPlaneRequestId` and normalized digest, tenant/realm selector, stable `playableStateNamespaceId`, source and target instance/version pairs, exact source and target `ACTIVE` lifecycle state/epochs, expected admission-pointer version, and the authoritative World-DB `expiresAt`. One execution allocates that identity once; retries and owner recovery reuse it.

After target activation, the World local transaction locks source and target lifecycle rows in stable order, checks both exact active epochs and the complete bound identity, rejects a conflicting nonterminal hold, and commits the hold. Source and target termination CAS operations include absence of a nonterminal hold as a World-local predicate. A held instance remains pending/retryable for termination and cannot advance its lifecycle epoch. The hold has its own terminal/reconciliation states, including finalized, safely aborted, and `RECONCILIATION_REQUIRED`, without introducing another lifecycle state.

Game Session binds the hold identity/fence and exact lifecycle proofs into its local pointer/audit/prepared-execution/source-cleanup/drain-fence transaction. World finalizes the hold only after authoritative post-swap Game Session readback proves that transaction committed. Abort requires owner evidence that the pointer transaction did not commit and the prior pointer remains authoritative; contradictory or unavailable evidence keeps the hold unresolved and termination-blocking. `expiresAt` only triggers diagnostics or repair and never auto-releases an unresolved hold. The current implementation has no hold record, coordinated wire/RPC surface, termination predicate, or focused proof; those remain target implementation gaps.

### Runtime configuration and events

- `generation_rule_template`, or equivalent version-scoped tables, stores publish-affecting procedural-generation inputs keyed by `(tenantId, versionId)` for Draft/Published design data.
- A separate tenant-scoped `generation_runtime_default` table may hold operational-only runtime knobs that do not affect publishable template output and must never be consulted when deriving a published version’s `generationConfigRevision`.
- Published versions carry a frozen `generationConfigRevision` or equivalent hash derived from the version-scoped design inputs committed at publish time.
- Implementation Notes:
  - The current first-slice implementation derives `generationConfigRevision` from the release identity as `genrev:<tenantId>:<versionId>:<manifestHash>` after digest gates and asset export complete. This is a current implementation seam, not permission for activation to read mutable generation defaults.
  - As richer version-scoped generation-input tables land, they must either be covered by the World digest manifest below or by a dedicated generation-config hash that remains frozen into `published_release_bundle` before launch.
- Target generation-run contract: `generation_run`, or equivalent, persists the stable `generationRequestId`, immutable generator selection and inputs, provenance, canonical `outputDigest`, expected output row count, expected canonical serialized-byte count, and recorded or staged output identity required to retry one admitted generation request safely. Finalize exact-checks the staged digest, row count, and canonical serialized-byte count against those bound values and rejects missing, duplicate, or truncated staging. A retry of the same request reuses its recorded or staged output and fails closed if that output is unavailable rather than silently selecting a different implementation. Committed topology is authoritative after finalize; the row does not require indefinite retention or executability of the historical generator.
- Current implementation status: preparation and hard-coded `SimpleDungeonGenerator` metadata do not persist and compare the complete frozen `generationConfigRevision` against `expectedGenerationConfigRevision`, so the safe target replay contract is not live.
- Runtime-instance `generation_run` rows tied to instance creation are diagnostic/runtime provenance only and are not cutover payload rows.
- `world_event` stores timed runtime changes such as weather updates and is keyed by `(tenantId, gameInstanceId)` with optional `region_instance` scope in the current storage model; that current scope does not settle the target Weather aggregate, and Weather writes remain fenced until the selector is accepted.
- `region_instance.weather`, or equivalent, records current weather state for live regions where the current implementation has that field. The target Weather aggregate remains an explicit unresolved World-owner decision between region-scoped and room-scoped state; this field is not a permitted mutation path while that decision is pending.

Redis caches hot rooms for active sessions to speed up lookups. Target Class A cached rooms use keys `room:<tenantId>:<gameInstanceId>:<roomInstanceId>` and must never be keyed by template identifiers because runtime rows may diverge from template state.

## Replacement-Instance State Classification

The exhaustive S1/S2/S3 rules, unknown-state fail-closed behavior, namespace modes, owner mapping/application evidence, and freshness-bound preflight are owned by [ADR 0122](../../decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md). World Management publishes only this local family inventory and current implementation caveat; it must not infer a class for an unregistered family.

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

Local consequence: the initial World-owned runtime families listed above are explicitly `S3`; no World-owned initial-slice family is mandatory `S2`. World Management must not silently copy room ambient state, occupancy, scheduled world events, or generated instance topology into the target instance. Any new family must be registered with its owner, classification, mapping/application contract where applicable, cleanup operation, and freshness evidence before launch or cutover can use it. The live first validation cut remains shallow: it checks a cutover-eligible source lifecycle and retained topology, but does not prove the complete ADR 0122 preflight.

## Digest Input Manifest

World Management is a required publish-gate participant and maintains a stable digest manifest for `GetDraftDesignDigest(versionId)`:

Implementation Notes:

- The applied-revision ledger is implemented and records successful Draft mutations transactionally. The current digest still returns synthetic `appliedCommitId = "version:<versionId>"`; the remaining gap is deriving a commit-level token only after that commit's complete revision set is durably applied. Revision-ledger identities remain replay and idempotency evidence rather than the publish-convergence token.
- Current version-scoped digest inputs include `region`, `zone`, `room`, `room_exit`, `generation_rule`, and `world_entity_spawn_binding`; later topology and generation-template families must join this same `(tenantId, versionId)` digest contract when introduced.
- Current concrete `region` digest fields include `id`, `shardId`, `name`, `weather`, `generationSeed`, `generatorType`, `generatorParams`, and `spacingMultiplier`.
- Current concrete `zone` digest fields include `id`, `regionId`, and `name`.
- Current concrete `room` digest fields include `id`, `zoneId`, `name`, `description`, `nameLocalizedVariantsJson`, and `descriptionLocalizedVariantsJson`.
- Current concrete `room_exit` digest fields include `id`, `fromRoomId`, `toRoomId`, `direction`, and `cost`.
- Current concrete `generation_rule` digest fields include `id`, `name`, `scopeType`, `scopeId`, and `value`; generation-rule mutations that carry a subtree scope must validate that declared scope, share the same scope epoch, and participate in `REPLACE_SCOPE` / `SEED_APPEND_ONLY` enforcement.
- Current concrete `world_entity_spawn_binding` digest fields include `id`, `roomId`, `entityTemplateType`, `entityTemplateId`, `spawnCount`, and `respawnDelaySeconds`.

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
- Current implementation note: the concrete first-slice table order is `regions`, `zones`, `rooms`, `roomExits`, `generationRules`, then `worldEntitySpawnBindings`, each ordered by ascending primary key. Null string values are canonicalized as empty strings before hashing. The current World digest schema version is `2` because scoped generation-rule metadata is now hashed.
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

Non-Weather world events are persisted in `world_event` and processed periodically by `WorldEventService` under their owning contract. Weather event scheduling and application are a fenced exception until the World owner accepts the aggregate selector and typed effect command.

World event invariants:

- Events are runtime-only and keyed by `(tenantId, gameInstanceId)`, never `(tenantId, versionId)`.
- `world_event.region_instance_id` must reference runtime `region_instance` rows, not template `region` rows.
- Event application must be idempotent through a stable event identity or derived identity tuple.
- While the Weather selector is unresolved, `WEATHER_CHANGE` scheduling and application are explicitly deferred/non-mutating: no `world_event` row is inserted or processed and no `region_instance.weather` mutation is permitted. The existing event seam is not an admitted bypass.
- Once the selector and typed fenced command are accepted, event application must use the same effect-shaped ambient mutation contract used by tick execution, guarded by `EffectId` and scoped by runtime instance identifiers.
