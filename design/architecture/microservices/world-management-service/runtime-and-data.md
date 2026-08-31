# World Management Service Runtime and Data

## Implementation Status

- The current room-cache implementation remains an unversioned TTL-only payload and must use authoritative reads until the target opaque component-version and invalidation proof exists.
- Historical migration status is incomplete: `V16__runtime_world_event_scope.sql` dropped and recreated `world_event` while changing its legacy template-region reference to a runtime-instance reference, without recorded retained-row mapping or backfill evidence. The current repository cannot reconstruct rows already dropped. This is a retained-data deployment blocker: an affected deployment must first record either a verified no-data boundary or an explicit owner disposition/mapping, with exact readback and lifecycle/admission-isolation evidence; future remediation must add migration and focused proof before contraction is claimed.
- Legacy topology scope/version introduction is also not mapped: `V2__add_tenant_and_expiration.sql` assigns `tenant_id = 0` to existing zone, room, and instance rows, while `V18__version_scoped_world_templates.sql` assigns `version_id = 1` across existing template tables without hierarchy/version-graph backfill evidence. Positive tenant/version reads can orphan or misattribute those rows. A retained-data deployment remains blocked until the owner records a verified no-data boundary or explicit retained-row disposition/mapping and exact readback; this remains migration/proof drift rather than a new identity authority.
- The live versioned topology and spawn schemas carry tenant/version columns, but parent references remain scalar-ID foreign keys, so SQL does not enforce child/parent tenant/version equality. Normal typed mutation guards resolve parents with tenant/version predicates, but that is not database integrity and does not protect retained or direct-written rows. Target convergence requires composite parent keys/foreign keys or an equivalent owner-local guard, plus database integration and readback proof. This is separate from the sentinel-default and hierarchy/version-graph backfill drift above.
- The live `ApplyWorldDesignMutation` path performs a remote Game Design Draft-state read before its local transaction, but carries no Game Design `versionStateEpoch` or owner publication fence and has no durable WMS version-freeze row. A concurrent Game Design publish can therefore pass its digest read and transition the version while the pre-checked WMS mutation is still able to commit. This is a current lifecycle-handoff gap, not proof of Published-template immutability; the target freeze/acknowledgement protocol is canonical in [Game Design Version Control](../game-design-service/version-control.md#publication-freeze-and-owner-handoff).
- The target WMS publication owner contract, including `BeginVersionPublicationFreeze` and the outcome-typed `CompleteVersionPublication` terminal handoff, is specified in [World Management API Contracts](./api-contracts.md#publication-freeze-and-terminal-handoff-target-state). The current proto, DTOs, client adapter, persistence schema, handlers, and focused race/retry proof do not implement those surfaces: `ApplyWorldDesignMutation` still has no publication request, Game Design epoch, or WMS `publicationFence` fields. This remains a publication-readiness blocker; no digest, preflight state read, or Temporal completion may be promoted to freeze or terminal owner evidence.
- Procedural-generation persistence has a current identity gap: `V21__scoped_generation_rules.sql` creates a unique index over nullable `scope_type`/`scope_id`. Under PostgreSQL, multiple NULLs are distinct, so unscoped rows can duplicate the same `(tenant_id, version_id, name)` and destabilize deterministic generation-rule identity/digests. The current scope-aware repository lookup also compares nullable scope columns with SQL `=`, so it cannot retrieve an unscoped row. The target schema and mutation path must either prohibit unscoped rows or provide an explicit null-safe schema/query uniqueness contract, with focused proof; procedural-generation readiness remains blocked until that contract is enforced.
- The current termination command returns a `TERMINATED` snapshot before comparing the supplied `terminationRequestId`. A different request identity therefore appears successful instead of conflicting with the terminal operation. The target requires exact request-identity replay and fail-closed mismatch handling, with focused terminal retry/conflict proof; this remains an implementation/proof blocker.
- The current scheduled Weather path has an additional runtime failure beyond its unresolved selector contract: `findDueEventsForShard` maps each event's region as an ID-only partial association, then `WorldEventServiceImpl` calls `RegionInstanceRepository.save`, whose update dereferences the missing `worldInstance`. A due region-associated `WEATHER_CHANGE` therefore raises an NPE, rolls back the transaction before the event is marked processed, and is retried on every scheduler pass; a regionless Weather event skips that mutation branch and is marked processed by the current code. Until the typed fenced effect contract is accepted, ingress and processing must reject/defer Weather; otherwise the owner must hydrate the exact tenant/game-scoped region or use an exact scoped update, with production-shaped proof.
- Temporal lifecycle activity retries do not yet preserve business idempotency after a local commit: activation/failure validate the supplied epoch before checking their terminal state, so a lost response retries stale, and StartSession compensation can fail to reconcile an already-`ACTIVE` world. The operation identity/result must be durable and retries must reconcile exact results; compensation must be ACTIVE-aware. After fail/terminate completes the long-lived workflow, the current orchestrator still signals that closed workflow on an exact terminal retry instead of reconciling the durable terminal row. These current gaps require focused lost-response, compensation, closed-workflow retry, and terminal-result proof.

## Template and Runtime Ownership

World Management owns both version-scoped template topology and runtime world-instance state, but the two surfaces are strictly separated. Stable playable identity and replacement classification are governed by [ADR 0122](../../decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md); this document records only World Management's storage and owner-local consequences:

- Target contract: template tables are keyed by `(tenantId, versionId)` and updated only through design-time workflows coordinated by Game Design. Current migration drift remains because the live legacy `POST /generation/rules` endpoint directly persists `generation_rule` after only a tenant-access check, defaults `versionId` to `1`, and does not enforce Draft/version authority; see [Procedural Generation Control](./procedural-generation-control.md).
- Runtime topology and explicitly disposable instance state are keyed by `(tenantId, gameInstanceId)` and created or mutated only by the world-lifecycle workflow and tick-driven gameplay flows. Any World-owned durable playable state is keyed by `(tenantId, playableStateNamespaceId)` plus the applicable World object identity; immutable server-derived `playableStateScope` travels with the namespace and active-instance proof for policy/routing/authorization validation but is not a durable-key or uniqueness dimension.
- Runtime logic must never modify template rows for published versions.
- Publication freeze is an owner-local WMS boundary, not a second lifecycle authority. The target `BeginVersionPublicationFreeze` operation locks or creates one `(tenantId, versionId)` owner-freeze row, verifies Game Design's supplied `versionStateEpoch`, persists a stable `publicationFence`, and returns the exact `{tenantId, versionId, publicationRequestId, requestDigest, versionStateEpoch, publicationFence, ownerFreezePhase, appliedCommitId, contentDigest, digestSchemaVersion}` acknowledgement. Every Draft `ApplyWorldDesignMutation` transaction takes that same row lock, including ordinary mutations without publication-request binding. Ordinary mutations require writable `OPEN` with no active freeze; a publication-bound mutation validates the exact request/digest, epoch, and fence from the acknowledgement, so it cannot be admitted before the fence exists, and may execute in `FROZEN` only for a bounded, pre-authorized final publication mutation covered by the request digest. `FROZEN` never reopens arbitrary Draft writes, and no further mutation is admitted after that bounded step. A writer already holding the lock may finish before the freeze, while a writer reaching it after `FROZEN` or with stale, mismatched, or unauthorized evidence is rejected or drained. Successful publication records the terminal handoff under the same fence; failed or ambiguous attempts reconcile before releasing the freeze. See [Game Design Version Control](../game-design-service/version-control.md#publication-freeze-and-owner-handoff) for the single cross-owner sequence and retry proof.

World Management instances are intended to be replaceable workers over authoritative runtime storage. Target owner rule: runtime room/location/ambient state that matters after process loss belongs in the service-owned database rows and documented caches, not as the sole authoritative copy in one JVM. Another World Management instance of the same type should therefore be able to continue serving and mutating runtime world state after restart without that restart itself becoming the player-visible event.

### Template Identifier Invariants

World templates follow the same stability rules as entity templates:

- Template identifiers for regions, zones, rooms, and related topology must not be repurposed to represent different conceptual locations while any non-Retired version or game template still references them.
- Structural world-layout changes must be modeled as new template rows under the appropriate `(tenantId, versionId)` rather than mutating existing identifiers in place.
- Cross-service references from Game Design or other domain services must use stable template identifiers scoped by `(tenantId, versionId)` and follow version-aware migration rules.

## Character Location Ownership

Target ownership (not current storage): World Management is the authoritative owner of character and NPC location for each game instance. The current implementation has live `room_instance` topology and a Game Session room-binding seam, but no authoritative character/NPC location or occupancy persistence.

- Target owner tables such as `character_location` and `npc_location` would live in this service’s schema and be written only by World Management logic as part of movement, instancing, and world-creation flows invoked by Game Session; those tables and the write family are absent from the current schema, migrations, and runtime implementation.
- Once implemented, other services, including Entity Management and Game Session, will treat those tables as read-only and rely on World Management gRPC APIs or cached projections. Current room reads and the Game Session binding do not establish that target authority.
- Target rule: derived caches or denormalized views of location must be refreshed from World Management rather than persisting independent authoritative location fields.

Target ownership: World Management is the fact owner for targeting predicates over location, occupancy, range, and mutable room state. Its bounded `TargetingFactSnapshot` responses contain only the compiled source/candidate facts for the requested `RoomInstanceRef` scope and a current world location/version token. For `DROP` and `PICKUP`, the target attestation carries `regionId` from Game Session's durable region authority alongside `RoomInstanceRef`, `regionEpoch`, `executorFence`, the same root `EffectId`, and the unchanged immutable `requestDigest`; World validates its own token and rejects mismatched scope, epoch, or fence. The canonical barrier, Game Logic re-resolution, and Entity commit binding are defined in [Transaction Strategies](../../system-architecture-transactions.md#drop-pickup-targeting-and-actor-fence-critical-section) and [ADR 0054](../../decisions/adr-0054-split-spatial-authority-with-causal-read-composition.md). The current proto/request and focused proof do not yet demonstrate this path.

### Spatial Effects Contract

Movement, drops, pickups, and room presence are cross-service by design:

- Target: World Management is authoritative for occupancy/location and room-scoped persistent ambient state keyed by runtime `RoomInstanceRef`; current `room_instance` topology and ambient rows do not include the absent character/NPC location or occupancy write family. Weather is an explicit exception while its target aggregate remains unresolved: the owner must choose and accept a region-scoped or room-scoped selector before any Weather identity or mutation can be defined. Weather mutation admission and reconciliation remain fenced and non-mutating until that selector is accepted, including writes represented by `world_event` and `region_instance.weather`. Initial Weather scheduling during world creation and generic `WEATHER_CHANGE` event scheduling/application follow the same rule: while the selector is unresolved they are explicitly deferred and non-mutating, so no Weather event row is inserted or processed and `region_instance.weather` is not changed.
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
- `instance` is the live legacy temporary-zone-copy table used for instanced gameplay. Its rows are tenant-scoped zone children (`zone_id`, `owner_account_id`, `created_at`, `expires_at`) and are not keyed by the modern `gameInstanceId`; its current `expires_at` job deletes rows directly. This is implementation drift, not the ADR 0123 `world_instance` lifecycle, and `expires_at` does not authorize a transition of the parent game instance. For replacement classification, the family is explicitly `S3` and **excluded from replacement mapping/cutover**: it is not copied into a target instance, and a replacement report must state that exclusion rather than claiming exhaustive mapping for this legacy family. Target cleanup either migrates the temporary content into a complete `world_instance` with its own `gameInstanceId` and fenced `InstanceTermination`, or retains a separate zone-scoped idempotent cleanup contract that cannot terminate the parent.
- `world_instance_status`, or equivalent lifecycle state, tracks the ADR 0123 transitions `PREPARING -> ACTIVE`, `PREPARING -> FAILED_PRE_ACTIVATION`, `PREPARING -> TERMINATING`, `FAILED_PRE_ACTIVATION -> TERMINATING`, `ACTIVE -> TERMINATING`, and `TERMINATING -> TERMINATED`.
- `FAILED_PRE_ACTIVATION` is terminal for admission by that `gameInstanceId`, not proof that cleanup completed; separate owner-scoped cleanup progress may continue. Recovery is modeled as provisioning a new `gameInstanceId` and rerunning world creation.
- `world_instance_lifecycle_lock`, or equivalent fenced token, enforces single-writer lifecycle transitions per `(tenantId, gameInstanceId)`.
- Target-only: `character_location` and `npc_location` would record current rooms and instance occupancy. These tables are absent from the current schema and runtime; live `room_instance` rows describe topology, while the Game Session room binding is not authoritative World location.

### Replacement cutover hold ownership

World Management also owns a separate durable one-shot cutover hold for replacement routing. The hold is not a `world_instance` lifecycle state and does not transfer lifecycle ownership to Game Session. Its conceptual record binds one opaque `cutoverHoldId` and equality-only `cutoverHoldFence` to `preparedVersionUpgradeId`, the exact `controlPlaneRequestId`, and a normalized request digest covering the known tenant/realm selector, owner-resolved `playableStateNamespaceId` and `playableStateScope`, the applicable canonical private/playtest lifecycle proof tuple `{playtestLifecycleId, playtestStateGeneration}`, source and target instance/version pairs, and expected admission-pointer version; the durable cutover execution/result separately binds the World-proven exact source and target `ACTIVE` lifecycle proofs, including that same tuple, plus hold identity/fence and authoritative World-DB `expiresAt`. The source/target `ACTIVE` lifecycle proofs, hold identity/fence, and `expiresAt` are not digest inputs. One execution allocates that identity once; retries and owner recovery reuse it. Public production omits both playtest fields and rejects supplied values.

After target activation, the World local transaction locks source and target lifecycle rows in stable order, checks both exact active epochs and the complete bound namespace/scope proof tuple `{tenantId, playableStateNamespaceId, playableStateScope}` plus the applicable canonical private/playtest lifecycle proof tuple, rejects a conflicting nonterminal hold, and commits the hold. The scope is immutable server-derived policy/routing/authorization evidence, not a durable identity dimension. Source and target termination CAS operations include absence of a nonterminal hold as a World-local predicate. A held instance remains pending/retryable for termination and cannot advance its lifecycle epoch. The hold has its own terminal/reconciliation states, including finalized, safely aborted, and `RECONCILIATION_REQUIRED`, without introducing another lifecycle state.

Game Session binds the hold identity/fence, exact lifecycle proofs, and the applicable canonical private/playtest lifecycle proof tuple, including that exact namespace/scope proof tuple, into its local pointer/audit/prepared-execution/source-cleanup/drain-fence transaction. World finalizes the hold only after authoritative post-swap Game Session readback proves that exact namespace/scope-and-lifecycle-proof-bound transaction committed. Abort requires owner evidence that the pointer transaction did not commit and the prior pointer remains authoritative; contradictory or unavailable evidence keeps the hold unresolved and termination-blocking. `expiresAt` only triggers diagnostics or repair and never auto-releases an unresolved hold. The current implementation has no hold record, coordinated wire/RPC surface, termination predicate, or focused proof; those remain target implementation gaps.

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
  - current `world_instance` lifecycle rows (lifecycle/provisioning state, not replacement payload);
  - `region_instance`, `zone_instance`, `room_instance`, `room_instance_exit`;
  - legacy `instance` rows (explicit replacement mapping `EXCLUDED`; cleanup is the separate zone-scoped legacy-instance operation, not parent `InstanceTermination`);
  - Target-only/absent in the current initial slice: `character_location`, `npc_location`, and occupancy rows;
  - ambient runtime state such as weather, door state, hazard state, instanced events, and instance-scoped schedules;
  - `world_event` rows tied to a specific `gameInstanceId`.
  - Target-only/absent in current persistence: `population_schedule_instance` rows keyed by `gameInstanceId`;
  - Target-only/absent in current persistence: runtime-instance `generation_run` rows, which are diagnostic or provenance only if later recorded.

Local consequence: the current World-owned runtime persistence includes `world_instance` lifecycle rows, `region_instance`, `zone_instance`, `room_instance`, `room_instance_exit`, and `world_event`; ambient behavior is only partial in the current slice because `region_instance.weather` and the Weather event path exist, while the current scheduled path can attempt an unfenced mutation and fail on its ID-only region association before marking the event processed. The target Weather path remains fenced and non-mutating until its selector and typed effect contract are accepted; door/hazard state, instanced-event state, and instance-scoped schedules are target-only or absent. Target-only/absent `population_schedule_instance` is classified `S3` when implemented, and target-only/absent runtime-instance `generation_run` remains diagnostic/provenance only if later recorded, never replacement payload; the legacy `instance` family is likewise explicitly `S3` but excluded from replacement mapping, and its separate cleanup operation is not lifecycle cleanup. The target-only location/occupancy families would also be `S3` when implemented, and no World-owned initial-slice family is mandatory `S2`. World Management must not silently copy room ambient state, occupancy, scheduled world events, generated instance topology, `population_schedule_instance` rows, runtime-instance `generation_run` rows, or legacy `instance` rows into the target instance. Any new family must be registered with its owner, classification, mapping/application contract where applicable, cleanup operation, and freshness evidence before launch or cutover can use it. The live first validation cut remains shallow: it checks a cutover-eligible source lifecycle and retained topology, but does not prove the complete ADR 0122 preflight.

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
- While the Weather selector is unresolved, `WEATHER_CHANGE` scheduling and application are explicitly deferred/non-mutating: no `world_event` row is inserted or processed and no `region_instance.weather` mutation is permitted. The existing event seam is not an admitted bypass. **Current implementation drift:** `WorldEventServiceImpl.scheduleEvent` accepts and persists `WEATHER_CHANGE`, resolves `regionId` through an unscoped `findById`, and does not exact-check the event tenant/game scope against the region. For a region-associated due event, `processDueEvents` receives only an ID-only partial `RegionInstance`, attempts the weather mutation, and then `RegionInstanceRepository.save` dereferences its missing `worldInstance`; the NPE rolls back the transaction before the event is marked processed, so the same event is retried on every scheduler pass. A regionless event skips the mutation branch and is marked processed without changing weather. This live path must be removed or fenced fail-closed through the accepted typed effect admission and exact runtime-scope contract before Weather can be considered safe.
- Once the selector and typed fenced command are accepted, event application must use the same effect-shaped ambient mutation contract used by tick execution, guarded by `EffectId` and scoped by runtime instance identifiers.
