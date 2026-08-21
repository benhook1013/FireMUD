# Entity Management Service Runtime and Data

This document defines Entity Management’s runtime model, persistence ownership, versioning rules, Redis role, and instance/cutover data classification.

## Implementation Status

- Current runtime item/equipment/container mutation RPCs that carry an `effectId` use `entity_mutation_effects` as the domain-local replay table. Entity Management records the applied protobuf response for `{tenantId, effectId}` and returns that stored response on duplicate delivery so `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE` do not double-apply after Game Session replay or retry.
- The `entitymanagement.mutation.effect.execution{operation,effect_status}` metric distinguishes first apply, replay/no-op, in-progress conflict, reported reuse outcome, and unreadable stored-response outcomes.
- This current `{tenantId,effectId}` identity is not the target ADR 0054 participant guard; changed operation, target, or request reuse is not yet proven fail-closed.
- Current `jOOQ + Flyway` adoption and focused persistence proof remain implementation work.

## Architecture and Design Notes

- Uses the service-owned PostgreSQL schema and the platform `jOOQ + Flyway` persistence direction. Flyway owns schema evolution and generated jOOQ types are the default SQL access path; the narrow PostgreSQL-specific plain-SQL escape hatch requires focused proof and does not create a parallel ORM authority.
- Exposes gRPC endpoints for other microservices.
- Caches frequently accessed character data in Redis for quick lookups.
- Applies optimistic locking to avoid conflicting updates on the same entity.
- Entity Management instances are intended to be replaceable workers over authoritative persistent state and documented caches. Item, inventory, containment, and character data that must survive instance loss belongs in the service-owned database rows and cache invalidation model, not as the sole authoritative copy in one process.
- Database writes are deferred and batched for ordinary entity updates, not triggered on every gameplay action. The Game Session Service coordinates real-time updates using Redis; the database is normally updated when ticks complete.
- Spatial containment mutations that participate in cross-service effects are the exception: before Entity Management acknowledges a spatial `EffectId` back to Game Session, it must durably flush the effect’s idempotency guard plus the affected containment/container rows for that effect within the same local transaction. A participant acknowledgement must never be emitted for Redis-only staged state.
- Target-state cross-service participant guards use a structured root `EffectId`, typed operation, target aggregate, and immutable request digest; matching retries return the durable result, while an operation, target, or digest mismatch fails closed. This target guard is not the current domain-local replay table described above.
- This design reduces write frequency and contention, making optimistic locking a natural fit because most entities are updated by only one process at a time and conflicts are rare.
- Item transfers and other gameplay actions span services but execute within ticks using Redis scripts for rollback. Sagas are reserved for non-gameplay workflows. See [Transaction Strategies](../../system-architecture-transactions.md).
- For long-running, non-gameplay workflows such as publishing a game version, this service participates as a domain step in durable publish workflows coordinated by the Game Design Service as described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- All entity tables include a `tenantId` column. Service methods always filter on this value so character data for different games remains isolated; Redis keys mirror this prefix. Details are in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Durable playable state that survives runtime replacement is keyed by the complete stable identity `(tenantId, playableStateNamespaceId, playableStateScope)` and authorized against the currently active `gameInstanceId`; `gameInstanceId` alone is reserved for explicitly disposable S3 runtime families. At target, S1/S2 storage identity and owner-local replay, idempotency, and deduplication keys use that complete namespace/scope identity and omit `gameInstanceId`; the request still carries the current `gameInstanceId` only as an active-instance authorization fence. Only explicitly S3 rows use `gameInstanceId` as part of durable identity. The namespace is resolved by the realm/lifecycle contract and is never inferred from an entity id, Redis key, or caller-supplied remap identifier. See [ADR 0122](../../decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md).
- Gameplay-facing gRPC endpoints do not parse JWT tokens. The Game Session Service injects identity context using `SessionContext` and may request a new JWT from the Account Service if a player's roles change. It does not validate tokens for gameplay. Traffic between services still uses mutual TLS certificates as outlined in the [Security Architecture](../../system-architecture-security.md).
- Design-time writes are a separate surface:
  - Entity Management exposes design APIs used by the Game Design Service to mutate Draft template rows keyed by `(tenantId, versionId)` (item/NPC templates, balance curves, loot tables).
  - These design APIs must validate JWTs and enforce designer/admin authorization for the target `tenantId` and Draft `versionId`.
  - Design APIs must reject any attempt to write templates for Published/Active/Failed versions.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- Service methods are annotated with `@Timed` so inventory and character operations emit Prometheus metrics.

This means another Entity Management instance of the same type should be able to serve the same runtime data after restart without requiring gameplay clients to reconnect just because one non-edge worker disappeared. Any such visible restart remains implementation debt, not target behavior.

## Data Model and Versioning

Entity Management maintains a clear separation between template/design data and live runtime entities so authoring workflows cannot corrupt active games:

- Template tables (for example item and NPC definitions, balance curves) are stored as versioned design records keyed by `(tenantId, versionId)` and are updated only through design-time workflows orchestrated by the Game Design Service. Entity Management accepts template writes only for Draft versions; once a version is marked Published in the Game Design Service, the associated template rows for that `(tenantId, versionId)` are treated as immutable and may only be read by runtime flows.
- Live runtime entities (characters, inventories, containers including room-ground containers) are stored in runtime tables keyed by the complete `(tenantId, playableStateNamespaceId, playableStateScope)` identity for durable state and `gameInstanceId` for explicitly instance-scoped state. These rows are mutated only by tick-driven gameplay flows and must validate the active-instance fence for namespace-backed writes.
- Publishing a version finalizes template rows for that `(tenantId, versionId)` and records them as immutable inputs for future game instances. Runtime entity state never changes those template rows; it only references them via stable identifiers.

Template identifiers are stable within each version: a given template ID must not be repurposed to represent a different conceptual entity while any non-Retired version still references it. When switching a game instance to a new `runtime_version`, the Game Session Service and Entity Management treat missing or incompatible templates as a fatal configuration error for that launch; the version selection must be corrected rather than silently substituting defaults or partial data.

### Target-State Replacement-Instance State Classification

Entity Management must classify its runtime persistence surface for cutover and migration tooling:

- `S1` entity-owned durable state within the resolved `(tenantId, playableStateNamespaceId, playableStateScope)` identity:
  - `character` identity/account-ownership rows and equivalent progression/currency records that do not require version remapping when referenced templates remain valid;
  - stable player-owned inventory/container membership for item instances that remain valid against the target version without remapping, keyed to the same complete namespace/scope identity.
- `S2` entity-owned version-mapped durable state within that resolved `(tenantId, playableStateNamespaceId, playableStateScope)` identity:
  - equipment-slot bindings for equipped items whose template validity depends on the target version;
  - learned-ability, starter-loadout, class/archetype, or equivalent durable character references whose validity depends on target-version template identifiers;
  - inventory or character rows that remain durable but reference templates requiring an approved remap to the target version.
- `S3` entity-owned ephemeral state:
  - synthetic room-ground containers and their contents keyed by `(tenantId, gameInstanceId, roomInstanceId)`;
  - transient containment, encounter-specific entities, corpses, summons, or equivalent rows whose lifecycle is tied to the source `gameInstanceId`;
  - `entity_tick_state` watermark rows keyed by `(tenantId, gameInstanceId, playableStateScope, regionId, targetAggregateType, entityId)` for the concrete instance/region timeline;
  - any row family explicitly documented as instance-scoped only.

### Conservative Current Implementation Inventory

The following inventory describes the conservative current implementation boundary, not proof that the target classification contract is complete. In particular, current `inventory` and `character_equipment` rows are treated as `S2` and require an approved remap; the target rules above remain the authority for replacement classification.

- `character` rows are `S1` only within the resolved `(tenantId, playableStateNamespaceId, playableStateScope)` identity. Shared-state realms use the tenant-live namespace, while isolated-state realms use their stable isolated/playtest namespace; a replacement `gameInstanceId` does not create a new durable character identity.
- Player progression/currency/account-ownership rows attached to `character` and not requiring template remap are `S1` only after the caller proves the same resolved `(tenantId, playableStateNamespaceId, playableStateScope)` target as the character row and the current active `gameInstanceId` fence. Mutation APIs must not update progression/resource-style state by global `characterId` alone.
- Inventory membership / containment rows for durable player-owned containers remain `S1` within the resolved namespace/scope identity when every referenced item template is still valid against the target version.
- `equipment_bindings` rows are `S2` within the resolved namespace/scope identity.
- Durable learned-ability, class/archetype, starter-loadout, or similar template-reference rows are `S2` within the resolved namespace/scope identity.
- Durable inventory or character rows that need an approved template remap to remain valid are `S2` within the resolved namespace/scope identity.
- Synthetic room-ground containers keyed by `(tenantId, gameInstanceId, roomInstanceId)` and their containment rows are `S3`.
- Encounter-scoped NPCs, corpses, summons, temporary containers, and any containment rows tied only to the source `gameInstanceId` are `S3`.
- `entity_tick_state` rows are `S3` instance/region timeline projections keyed by `{tenantId, gameInstanceId, playableStateScope, regionId, targetAggregateType, entityId}`. The separately validated `playableStateNamespaceId` authorizes mutation of namespace-backed S1/S2 state but is not watermark identity. Termination cleanup removes these rows with their source instance only after already-admitted source effects are reconciled; it must not delete or substitute for the stable root `EffectId`, typed operation/target guards, and request evidence that provide durable S1/S2 replay safety across replacement.

Replacement classification rule:

- Every Entity-owned family must be explicitly registered as `S1`, `S2`, or `S3` for the exact namespace/scope/version transition. Unknown, unowned, or unclassified families block cutover; they are never treated as S3 by default.
- Replacement-instance workflows must not infer template remaps from names, display text, or best-effort similarity. A supplied or echoed `remapSetId` is only a reference to resolve; it is not proof that Entity validated the exact source/target mapping or applied it successfully. Only owner-validated and owner-applied mapping evidence may satisfy `S2` compatibility.

Implementation notes:

- The current cutover-validation RPC is `ValidateEntityUpgradeMappings(tenantId, sourceGameInstanceId, targetVersionId, remapSetId?)`; the target contract must additionally bind the owner-resolved `playableStateNamespaceId` and `playableStateScope`, active-instance authorization, and the exact source/target versions. Entity validates that scope evidence; it never derives scope from the opaque namespace. The current signature and shallow implementation do not prove the target contract.
- The live implementation enumerates tenant-surviving families (`character`, `inventory`, `character_equipment`, `character_friend`) plus the currently persisted families requiring holder classification (`room_ground_inventory`, `item_instances`, `item_stacks`, `container_instances`).
- `item_instances` and `item_stacks` are not table-wide `S3` families: each row follows its holder/container graph. A durable player or durable namespace-backed container holder identified by `(tenantId, playableStateNamespaceId, playableStateScope)` is `S1` or `S2` according to template-remap requirements; only a synthetic room-ground holder or another explicitly instance-scoped holder is `S3`. Termination cleanup must apply the holder/container and namespace/scope predicate and must not delete all rows in either table by `gameInstanceId` alone. The current table-level enumeration does not yet prove this predicate, so this remains an implementation/proof gap rather than permission to classify durable inventory as `S3`.
- `character` and `character_friend` rows are supported `S1` survivor state within the resolved `(tenantId, playableStateNamespaceId, playableStateScope)` identity at the current boundary. Their presence does not require a remap set by itself.
- `inventory` and `character_equipment` rows are treated as current `S2` template-bound survivor state within the resolved `(tenantId, playableStateNamespaceId, playableStateScope)` identity. If either family has rows and no approved `remapSetId` was frozen by launch resolution, validation returns `result=INCOMPATIBLE`, `hasS2Rows=true`, `remapSetRequired=true`, and `ENTITY_REMAP_REQUIRED`.
- When template-bound `S2` rows exist within that resolved namespace/scope identity and the caller supplies the frozen approved `remapSetId`, the current implementation reports `COMPATIBLE` and echoes that id. This result is non-authoritative and remains blocking for cutover: no caller or consumer may treat the echoed identifier as admissible compatibility or cleanup proof. Entity Management must validate and apply the exact Game Design-approved mapping locally before acknowledging compatibility. Entity Management does not infer remaps or create a second remap identity; Game Design remains the source of truth for approval and the prepared cutover artifact binds the exact id used.

Entity upgrade validation minimum contract:

- The service must expose a cutover-validation API that accepts `tenantId`, owner-resolved `playableStateNamespaceId` and `playableStateScope`, `sourceGameInstanceId`, exact `sourceVersionId` and `targetVersionId`, and optional `remapSetId`, and proves that the source instance is the active authorized runtime for that namespace/scope. The target replacement requirements are this namespace/scope/version-bound classification, holder-aware S1/S2/S3 evidence, and owner-validated/applied mapping evidence; they are distinct from the current shallow `ValidateEntityUpgradeMappings` result, which may only enumerate rows and echo a remap id.
- The response must enumerate the entity-owned row families checked, the referenced template identifiers, and per-family outcomes `COMPATIBLE`, `REQUIRES_MAPPING`, or `INCOMPATIBLE`.
- If the service currently has no `S2` rows for a given namespace/source instance, it must report that explicitly rather than collapsing the result into a generic success; unknown or unclassified families must produce a blocking result.

Cutover fence contract:

- Replacement-instance validation and migration must run against a durable, fenced snapshot of Entity Management state for the source `playableStateNamespaceId`, owner-resolved `playableStateScope`, and active `gameInstanceId`; validating against Redis-staged or partially flushed deferred writes is not allowed.
- Before invoking entity cutover validation or snapshot/export for a source namespace, Game Session must quiesce gameplay admission and mutation for that namespace/scope's active `gameInstanceId`.
- Entity Management must then flush all deferred `S1` and `S2` writes for that source namespace/scope to PostgreSQL and return a committed fence token or epoch that identifies the durable state used for validation.
- The validation response must either include that fence token/epoch or be bound to an API contract that makes the same durable fence observable to the caller.
- `durableFenceToken` is an opaque server-issued value. Callers may persist and compare it for equality/identity, but they must not infer ordering, encode semantics, or generate successor tokens client-side unless a future API explicitly adds those guarantees.
- If Entity Management cannot flush deferred durable state for the source instance, cutover validation must fail closed rather than validating stale database rows.

Illustrative responses for the current live first slice:

- Current first-cut response with an incomplete, non-authoritative table-level enumeration. It intentionally predates the target namespace/scope-bound request and fence fields. The `item_instances` and `item_stacks` entries below mean only rows held by synthetic room-ground containers; they do not classify every row in either table as `S3`.

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "sourceGameInstanceId": "2e3ee139-a6e8-44ad-b840-891b22c2255b",
  "targetVersionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
  "checkedFamilies": [
    "room_ground_inventory",
    "item_instances",
    "item_stacks",
    "container_instances"
  ],
  "classificationScope": "synthetic room-ground holder rows only",
  "authoritativeClassification": false,
  "stateClassesChecked": ["S3"],
  "hasS2Rows": false,
  "result": "INCOMPLETE",
  "remapSetRequired": false
}
```

Target-state illustrative responses:

- Durable rows present but no remap required:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "playableStateNamespaceId": "2f1a1b6c-4a7d-4bc0-a7b9-6d4e5f8a9c01",
  "playableStateScope": "PLAYABLE_STATE_SCOPE_SHARED",
  "sourceGameInstanceId": "2e3ee139-a6e8-44ad-b840-891b22c2255b",
  "sourceVersionId": "1f6e7a82-3c4d-4b91-8a25-6d0e9f3b7c14",
  "targetVersionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
  "durableFenceToken": "8b7e1c4a-2d6f-4c91-a5b8-7e3d9f0a6c12",
  "checkedFamilies": [
    {
      "family": "equipment_bindings",
      "referencedTemplateIds": ["itemTemplateId:iron-sword"],
      "outcome": "COMPATIBLE"
    }
  ],
  "hasS2Rows": true,
  "result": "COMPATIBLE",
  "remapSetRequired": false
}
```

`durableFenceToken` values in these examples are illustrative opaque tokens only. Clients and operators must not infer structure from the string shape beyond equality comparison against the corresponding cutover/readiness contract.

- Durable rows require remap:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "playableStateNamespaceId": "2f1a1b6c-4a7d-4bc0-a7b9-6d4e5f8a9c01",
  "playableStateScope": "PLAYABLE_STATE_SCOPE_SHARED",
  "sourceGameInstanceId": "2e3ee139-a6e8-44ad-b840-891b22c2255b",
  "sourceVersionId": "1f6e7a82-3c4d-4b91-8a25-6d0e9f3b7c14",
  "targetVersionId": "8e65e4a1-5b49-4c31-9f27-3d0b8c6a1e74",
  "durableFenceToken": "c4a9e6f1-7b2d-4d83-9c15-6e0f2a8b4d77",
  "checkedFamilies": [
    {
      "family": "class_assignment",
      "referencedTemplateIds": ["classTemplateId:ranger-v1"],
      "outcome": "REQUIRES_MAPPING"
    }
  ],
  "hasS2Rows": true,
  "result": "INCOMPATIBLE",
  "remapSetRequired": true
}
```

- No `S2` rows for a source instance:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "playableStateNamespaceId": "2f1a1b6c-4a7d-4bc0-a7b9-6d4e5f8a9c01",
  "playableStateScope": "PLAYABLE_STATE_SCOPE_SHARED",
  "sourceGameInstanceId": "2e3ee139-a6e8-44ad-b840-891b22c2255b",
  "sourceVersionId": "1f6e7a82-3c4d-4b91-8a25-6d0e9f3b7c14",
  "targetVersionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
  "durableFenceToken": "f1d6a3c8-9e24-4b70-b5f2-8c1a6d9e3f04",
  "checkedFamilies": [
    {
      "family": "character",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "inventory",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "character_equipment",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "character_friend",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "room_ground_inventory",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "item_instances",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "item_stacks",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "container_instances",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    }
  ],
  "hasS2Rows": false,
  "result": "COMPATIBLE",
  "remapSetRequired": false
}
```

- Durable flush could not complete, validation refused:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "sourceGameInstanceId": "2e3ee139-a6e8-44ad-b840-891b22c2255b",
  "targetVersionId": "4f035f76-4b87-4a5e-8b9f-ea6c9e66e620",
  "error": {
    "code": "CUTOVER_FENCE_UNAVAILABLE",
    "message": "Deferred durable writes for sourceGameInstanceId=2e3ee139-a6e8-44ad-b840-891b22c2255b could not be flushed to PostgreSQL; cutover validation refused."
  }
}
```

See [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) and [`item-equipment-balancing.md`](../game-design-service/item-equipment-balancing.md) for how design-time definitions flow into these versioned templates.

## Runtime Data Model

- `character` and `npc` tables share a base entity for stats and inventory slots.
- `item` table stores equipment, consumables, and quest objects.
- Many-to-many tables define inventory and equipment relationships, including container contents and room/ground inventory. Room/ground inventory is modeled as items whose container references a synthetic room-ground container entity keyed by `(tenantId, gameInstanceId, roomInstanceId)` so limits such as max items on the ground or special container rules can be enforced consistently without cross-instance collisions.
- Actor gameplay state is persisted separately from the legacy character stat columns. `actor_resource_states` stores current/base/max resource values with source provenance, while `actor_active_conditions` stores active condition keys, stack counts, source provenance, start/expiry timestamps, and effect payload JSON. Reads merge baseline character stat fields with persisted resource rows so later mutation/effect slices can converge on the new state model without removing the bootstrap character fields first.
- The first shared effect-evaluation seam is an in-process Entity Management service that evaluates typed resource modifiers and granted states deterministically. Active condition payloads and equipped item-template payloads are `CONTINUOUS` effect sources: they contribute while their source exists and never write derived values into current resources during reads. When an attached source changes a bounded-resource maximum, its attach, detach, refresh, expiry, or replacement mutation performs the one idempotent capacity normalization using the declaration override or resolved tenant/game `actorState.capacityChangePolicy` captured by that mutation. Evaluation reads never normalize current state. Gameplay-attested `ApplyActorCondition` calls are the first `INSTANT` mutation path, creating active condition/action-state rows through the same internal mutation service under an idempotent effect id. A scheduled Entity Management job also expires elapsed active-condition rows on a bounded cadence. Player command wiring remains future work.
- Target-state active-condition rows are condition instances with stable instance ids, frozen definition/release snapshots, source provenance, stack counts, expiry, and applied-effect snapshots. Condition-definition DML, not source-id coincidence, selects `REPLACE`, `REFRESH`, `STACK`, or `PARALLEL` reapplication behavior and its duration policy. Typed removal resolves exact keys, authored tags, or permitted sources in deterministic definition priority and instance-id order; it must not expose raw row or payload deletion to player-facing callers.
- Character location and instance membership are stored by the World Management Service rather than this service, but all item instances and inventories remain owned and persisted here.
- Entity graphs cache inventory relationships for fast lookups.

### Persisted actor and realm-entry identity

Under [ADR 0140](../../decisions/adr-0140-realm-authored-controllable-actor-entry.md), Entity Management is the persistence authority for the generic primary controllable actor. It allocates canonical `characterId` values and stores the association `{accountId, tenantId, playableStateNamespaceId, characterId}`. The durable identity is scoped by `{tenantId, playableStateNamespaceId}`; `playableStateScope` is server-derived policy/routing evidence and `gameInstanceId` is only the current active-runtime fence for namespace-backed writes.

The published realm catalog supplies exactly one `PLAYER_CREATED`, `PRESEEDED_ONLY`, or `AUTO_PROVISIONED` entry policy plus its exact versioned descriptor/template identity. Entity validates that policy and version before creating or listing actors. Auto-provision uses an idempotency key scoped to `{accountId, playableStateNamespaceId}` and returns the existing persisted actor on an exact retry or concurrent duplicate. `CHARS`-equivalent reads return only persisted actors valid for the account, tenant, realm namespace, and published policy; the response carries policy and descriptor/template version so callers do not infer policy from row count. Zero actors therefore means create, provision, or deny according to policy; one may be selected automatically; many require explicit selection.

Copies into an isolated playtest namespace allocate a new fork-local `characterId`. An optional `sourceCharacterId` is immutable provenance only: it is never a live reference or authority for ownership, mutation, controller uniqueness, reconnect, or merge-back. Game-specific RPG or non-RPG state is authored component data tied to the published descriptor/template and is not mandatory platform schema.

The current implementation remains partial: legacy creation and actor rows still expose fixed RPG-oriented fields, policy/descriptor/template resolution and auto-provision idempotency are incomplete, and synthetic-ID/fork-copy proof gaps remain. These target rules do not claim runtime convergence.

### Runtime Actor Identity

Entity Management owns one persisted runtime actor for every active gameplay being. `actorId` is an opaque canonical gameplay-facing identity; services must not substitute composite reference strings such as `PLAYER:<characterId>` or re-derive different player/NPC identity forms at each boundary.

- The actor core carries `tenantId`, `gameInstanceId`, the resolved `playableStateNamespaceId` and `playableStateScope` when it represents durable playable state, `actorKind`, display name, and presence state. It does not persist universal targetability or visibility fields.
- A `PLAYER` actor is a runtime projection of the persisted actor and is unique for the active `{tenantId, gameInstanceId, characterId}` execution context; it does not replace the canonical durable identity `{tenantId, playableStateNamespaceId, characterId}`. `gameInstanceId` remains the runtime target/fence, and this S3 projection may be recreated during replacement while preserving the namespace-backed character. Disconnect/reconnect changes presence on the projection rather than creating a replacement durable identity.
- An `NPC` actor links to one NPC runtime instance. An authored NPC definition may create many concurrent runtime NPC actors and is not itself actor identity.
- `PET` and `SUMMON` extend the same core when implemented. God/admin behavior is a capability and authorized presentation overlay on a `PLAYER` actor, not a separate actor kind.

Each actor also has one persisted, release-admitted `dispositionKey`: its main gameplay state. The disposition supplies base action-admission through published `ActionAdmissionTag` denials and semantic feedback policy. Conditions and equipment are explicit continuous overlays over that base; they can only add tag denials or otherwise narrow it and never grant behavior denied by the main disposition. They may also contribute ordinary game-authored state facts for later reusable `ObservationPolicy` and `TargetingPolicy` evaluation, but those facts do not bypass action admission or become universal visibility/targetability fields. Recovery, immunity, revival, and other exceptional main-state changes use an idempotent instant condition removal/prevention or disposition transition, so continuous sources do not become competing death/defeat lifecycle owners. Transport/session presence is a separate fact and must not be repurposed as disposition.

The actor is the shared subject for gameplay targeting, effects, stats, conditions, and communication. It does not move other service ownership:

- World Management remains authoritative for an actor's room location and the room occupancy view.
- Game Session owns only ephemeral session attachment, protocol state, and player-facing presence projection.
- Account Service remains authoritative for account identity and authorization inputs.

For published targeting predicates whose facts it owns, Entity Management exposes a bounded `TargetingFactSnapshot` for the requested scoped actor ids. The response includes only the requested actor-state facts and an actor-state revision token; it is not a general actor read. Before applying an approved effect plan, Entity Management validates the recorded token for every material Entity-owned fact. If any token is stale, it reports a pre-commit mismatch so Game Logic can discard and re-resolve the plan under the same effect id before any source cost or target mutation commits.

Character and NPC records remain the durable domain records for their respective variants. The runtime actor links those records into one gameplay subject model; it does not replace character progression, authored NPC definitions, or World Management location state.

### Containment and Equipment Model

Entity Management's target-state containment model is container-first:

- Every item instance lives in a container or is attached through a first-class equipment binding.
- Character and NPC inventories are hidden containers owned by that entity.
- Bags, chests, corpses, banks, vendor stock, and similar holders are containers using the same core containment rules.
- Room-ground inventory is also a container, not a special world-owned list. World Management remains authoritative for the room identity and occupancy, while Entity Management owns the attached room-ground container and its contents.

Room-ground containers must feel attached to a room instance rather than like an unrelated global mapping:

- The room identity comes from World Management via `RoomInstanceRef`.
- Entity Management persists the attached ground container and its contents using a deterministic runtime identity derived from `(tenantId, gameInstanceId, roomInstanceId)`.
- Implementations may store the attached container as a synthetic entity or equivalent runtime record, but the conceptual contract is "this room instance has a ground container."

Equipment is intentionally not modeled as "just another bag position":

- Equipped items remain owned by the same runtime entity but are represented through a first-class equipment relation.
- Equipment slot definitions are game-configured design data, not fixed platform-wide enums.
- Body layouts or equivalent runtime configuration determine which slots are available to a particular character/NPC/species.
- Item definitions declare compatibility through configurable slot groups, attachment rules, or equivalent game-defined constraints rather than through a hardcoded universal slot set.
- The complete-schema and fail-closed behavior in the following contract is target state, not current implementation; current missing-schema and unknown-layout bootstrap fallback gaps are recorded in the [Entity Management README](./README.md) and [Item & Equipment Balancing Tools](../game-design-service/item-equipment-balancing.md#target-equipment-schema-requirements).
- Game Design owns the complete published equipment vocabulary/body-layout schema and its digest. Entity Management consumes that exact schema for occupancy and compatibility checks; all missing, partial, or mismatched vocabulary or mapping evidence is rejected and validation fails closed. It does not provide a platform-global slot fallback. Replacement cutover remaps bindings only through the owner-validated mapping contract; profiles materialize authored equipment content rather than supplying runtime defaults. See [ADR 0127](../../decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md).

The target inventory/equipment operation context for a durable holder or binding is the complete tuple `{tenantId, playableStateNamespaceId, playableStateScope, gameInstanceId, characterId, itemInstanceId, itemDefinitionId, containerInstanceId?, equipmentBindingId?, slotKey?, roomInstanceId?}`. For namespace-backed S1/S2 inventory, character, and equipment state, durable storage and replay/deduplication identity use `{tenantId, playableStateNamespaceId, playableStateScope, ...}` and omit `gameInstanceId`; the current `gameInstanceId` is validated only as the active-instance fence. Explicitly instance-scoped S3 holders instead include `gameInstanceId` in durable identity. The current proto and OpenAPI surfaces do not yet carry this complete operation context on every inventory/equipment read or mutation, so this is a target contract only: this ADR parcel does not add wire fields, regenerate protos, or change runtime lookup behavior.

This keeps the platform compatible with games that need unusual body plans or attachment models such as horns instead of hands, asymmetric limbs, species-specific slot topologies, or non-humanoid wearable layouts.

### Inventory Queries and Type Filtering

The current minimal `QueryInventory -> item_ids[]` shape is only sufficient for the earliest bootstrap slices. The target-state inventory contract should support richer gameplay and UI queries:

- Query by containment scope such as inventory container, room-ground container, nested container, bank, vendor stock, or corpse.
- Query by equipped state and slot binding.
- Query by structural properties such as stackability, quantity, visibility, accessibility, and ownership.
- Query by game-defined item types, tags, or category taxonomies so gameplay commands and GUIs can filter for concepts such as quest items, reagents, weapons, salvage, consumables, rarity classes, or other design-defined groupings.

Current `06.3` note:

- the live runtime model already treats ordinary items as distinct `item_instances`;
- item definitions now already expose an explicit authored `stackable` capability flag, defaulting to non-stackable;
- authored stackability still remains a later holder/query behavior, not an implied consequence of item-definition sameness;
- until that authored seam exists, same-definition items should remain separate physical instances rather than silently merging into aggregate quantity state.

This richer query model is required both for player actions and for future clients with multiple inventory panes, filtered item grids, equipment screens, contextual loot UIs, or admin/operator tooling.

### Inventory Transfer Audit

Inventory and equipment mutations must be auditable. This is a deliberate design requirement because item duplication bugs or invalid transfers can be difficult to investigate after the fact.

Every transfer-like mutation should emit an audit record using one canonical movement model:

- item instance id
- item definition/template id
- quantity or stack delta
- source container id or equipped binding
- destination container id or equipped binding
- actor entity/account/session when applicable
- action reason such as `pickup`, `drop`, `loot`, `put`, `take`, `equip`, `unequip`, `split_stack`, `merge_stack`, `create`, `destroy`, or `admin_grant`
- tenant id, `playableStateNamespaceId`, `playableStateScope`, game instance id, and room context when applicable
- timestamp plus correlation id / command id / effect id

Because everything is modeled as container movement plus equipment bindings, the audit format can stay uniform across player inventories, room-ground transfers, nested containers, equipment changes, scripted rewards, and administrative interventions.

Where possible, the audit write must participate in the same local transactional boundary as the authoritative containment mutation so the system does not record state changes without an audit trail or emit audit rows for rolled-back state.

Operators and later fraud/dupe-detection tooling should also be able to derive lightweight invariants from these records, for example:

- the same item instance appearing in multiple locations
- negative or impossible stack counts
- transfer retries reusing the same correlation id unexpectedly
- suspicious create/destroy imbalances
- source or destination containers inconsistent with room/entity ownership rules

### Instance Termination Cleanup Contract

Synthetic room-ground containers scoped by `(tenantId, gameInstanceId, roomInstanceId)` must be removed through the durable Temporal `world-lifecycle` termination flow described in World Management docs:

- Game Session must already have closed admissions for the target instance before cleanup starts.
- Entity Management owns cleanup of containers and contained items for a terminating `gameInstanceId`.
- Cleanup must be idempotent and guarded by a durable workflow step key so retries converge without double-deletes.
- Entity Management must not treat world row deletion as implicit cleanup confirmation; World Management marks an instance `TERMINATED` only after this service confirms cleanup completion.
- Entity Management must register every additional `gameInstanceId`-owned family, its S3 classification, cleanup request identity, retention rule, and acknowledgement status with the lifecycle owner. Missing or unregistered Entity families block `TERMINATED`; cleanup of one known room-ground family is not whole-service completion. Namespace-backed S1/S2 state under the complete `(tenantId, playableStateNamespaceId, playableStateScope)` identity is retained or mapped under the replacement contract and is not deleted merely because one runtime instance ended. See [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md).

### Workflow Participation

Entity Management does not orchestrate its own synchronous saga or Temporal workflows and does not use them for tick-driven gameplay or inventory operations. For long-running, non-gameplay workflows such as publishing or rolling back a game version, it participates as a domain step in workflows coordinated by the Game Design Service and Game Session Service. These workflows finalize or validate versioned template data for `(tenantId, versionId)` without touching live runtime entities. See [Transaction Strategies](../../system-architecture-transactions.md) and [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) for the overall workflow patterns.

## Redis Role and Prefixes

- **Coordination Redis participation**
  - Acquires tick locks via shared helpers using keys of the form `tick:{tenantRegionTag}:lock:<entityId>` so locks share a hash tag with tick queues and pending state as described in [Redis Architecture](../../system-architecture-redis.md#key-format-examples).
  - Treats lock TTLs and other coordination parameters as opaque values derived by the Game Session Service and shared helpers; it does not define its own coordination-specific configuration.
- **Cache/Rate-Limit Redis usage**
  - Uses Cache/Rate-Limit Redis to cache frequently accessed character graphs and related aggregates under prefixes such as `character-cache:<tenantId>:<characterId>`, following the key naming and TTL/versioning patterns in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
  - These character graph caches are treated as Class A, versioned caches:
    - Cached payloads include a stable version or `lastModified` value derived from the authoritative character tables (for example, the `character.version` or `last_modified` columns exposed via Entity Management APIs).
    - Readers validate versions against PostgreSQL (or version fields surfaced via gRPC) before reusing cached data; on mismatch they recompute the graph and overwrite the cache atomically (value + TTL).
    - TTLs (for example, `FIREMUD_CHARACTER_CACHE_TTL_SECONDS`) act as a safety valve for memory and stale entries, not as the primary correctness mechanism.
  - Future inventory/containment caches use the `inventory:<tenantId>:<containerId>` prefix from the Redis cache catalog:
    - Inventories and containers (including room-ground containers) are also treated as Class A: authoritative state and versions live in PostgreSQL, and cache entries must be invalidated via events or version checks when items move.
    - Event-based invalidation is driven by Entity Management’s own domain events (inventory changed, item moved, container destroyed); listeners delete or refresh affected `inventory:*` keys.
    - Implementations must document which APIs expose the version/`lastModified` fields used for these caches and keep them aligned with the central `inventory:*` entry in `system-architecture-redis-cache.md` and the reset matrix in `system-architecture-redis-reset-and-recovery.md`.
  - Cache metrics for `character-cache:*` and `inventory:*` should follow the recommendations in `system-architecture-redis-cache.md` so hit/miss behavior and key counts are observable.
  - Tests covering these caches are expected to exercise the Class A scenarios described in `system-architecture-redis-cache.md` (miss -> populate -> hit, version mismatch and event-driven invalidation, and behavior after a cache reset).
- Any change to Redis usage in this service should be reviewed against the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) to confirm prefix registration, role selection, slotting, and observability updates.

### Version Sources for Entity Caches

Entity Management is the invalidator of record for `character-cache:*` and `inventory:*`:

- Authoritative versions or `lastModified` values for characters and containers are stored alongside the corresponding aggregates in PostgreSQL and surfaced via Entity Management’s gRPC APIs.
- Cache payloads for `character-cache:<tenantId>:<characterId>` and `inventory:<tenantId>:<containerId>` must embed those same version fields so readers can compare cached vs authoritative versions before reuse.
- When schema or API fields that act as the version for these aggregates change, this section and the central cache catalog (`system-architecture-redis-cache.md`) must be updated together so reviewers can see exactly which columns/fields drive Class A cache correctness.

Testing expectations for these caches follow the Class A guidance in `system-architecture-redis-cache.md`: unit/integration tests should cover version mismatches, event-driven invalidation, and repopulation after a Redis reset.

If you change Redis usage for this service, you must read and apply:

- [Redis Architecture](../../system-architecture-redis.md)
- [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
- [FireMUD Redis Lua Patterns](../../system-architecture-redis-lua-patterns.md)
- [Redis Operations & Migrations](../../system-architecture-redis-operations.md)
