# Entity Management Service Runtime and Data

This document defines Entity Management’s runtime model, persistence ownership, versioning rules, Redis role, and instance/cutover data classification.

## Implementation Status

- The current crafting REST adapter is runtime-reachable legacy implementation drift, not a supported or advertised boundary: the crafting paths and schemas are omitted from the ping-only authoritative OpenAPI, whose root `security: []` applies only to the health contract, so the adapter remains unsupported/nonconformant. The deployed interceptor requires any `AUTHENTICATED` bearer because this non-public path is not in the public-route list, but neither layer provides tenant binding; there is no controller `SessionContext` tenant guard. `CraftingServiceImpl` trusts the DTO's `tenantId`, `resultItemId`, and ingredient item IDs; `CraftingRecipeRepository.findWithIngredientsById` and the update branch use recipe ID alone, while ingredient loading also uses recipe ID alone. The tenant-qualified list methods first select IDs but then re-enter that unqualified lookup, so they are not evidence of an end-to-end tenant predicate. Existing crafting tests do not establish caller-tenant authorization, cross-tenant recipe isolation, or same-tenant result/ingredient ownership.
- World owns guarded, manually composed initial `character_location` storage and its unregistered placement/read boundary. General movement, occupancy and NPC location remain unqualified; `room_instance` is topology, and a Game Session room binding is not World location authority. Entity Management must not add competing location persistence or treat a cache/projection as authoritative.
- **Target crafting boundary:** require authenticated caller context and exact tenant authorization before any recipe operation, derive the target tenant rather than treating request `tenantId` as authority, and make recipe read/update and ingredient load/update predicates tenant-qualified. Validate that the result item and every ingredient item belong to the target tenant and the same applicable version/Draft scope before writing. A missing context, tenant mismatch, recipe mismatch, or cross-tenant item reference fails closed before any recipe or ingredient mutation; the target negative proof must exercise each case.
- Current runtime item/equipment/container mutation RPCs that carry an `effectId` use `entity_mutation_effects` as the domain-local replay table. Entity Management records the applied protobuf response for `{tenantId, effectId}` and returns that stored response on duplicate delivery so `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE` do not double-apply after Game Session replay or retry. Requests with a blank `effectId` bypass this table and execute directly; that fallback is current compatibility behavior, not a safe gameplay admission path. This is the current durable effect-replay guard, not a replacement tick-watermark implementation.
- The `entitymanagement.mutation.effect.execution{operation,effect_status}` metric distinguishes first apply, replay/no-op, in-progress conflict, reported reuse outcome, and unreadable stored-response outcomes.
- This current `{tenantId,effectId}` identity is not the target ADR 0054 participant guard: the replay record persists only the operation/status/payload result and reuse checks only the operation name. It does not yet persist or validate the typed target, namespace/scope/fence, or immutable request digest, and changed operation, target, or request reuse is not yet proven fail-closed.
- The current concurrent first-apply path catches a duplicate-marker `DataIntegrityViolationException` inside the outer replay transaction and then attempts the loser read in that same transaction. PostgreSQL transaction-abort/rollback-only behavior means this is not a reliable replay or conflict result; the target requires a conflict-safe marker insert or a same-transaction savepoint around marker handling, preserving marker/mutation atomicity and never isolating marker insertion in a separate transaction. The [Entity Management Operations implementation status](./operations.md#implementation-status) records this local correction under the canonical [Transaction Strategies](../../system-architecture-transactions.md) participant contract. Real PostgreSQL concurrent first-apply/replay proof remains unavailable, so replay readiness remains blocked.
- Character updates are also not currently optimistic CAS: `CharacterRepository` increments `version` but updates by row ID without the old-version and tenant predicates or an affected-row conflict check. Concurrent progression/last-login writes can therefore overwrite each other. The target requires tenant- and namespace-bound version CAS with stale-write failure and focused concurrent proof; the current `@Transactional` service methods do not establish that invariant.
- Current `jOOQ + Flyway` adoption and focused persistence proof remain implementation work.
- The current actor-condition expiry job uses Spring fixed-delay scheduling, with a 30-second default delay after the previous invocation completes, and invokes a global, unbounded delete for rows whose `expires_at` has elapsed, without tenant/namespace/instance predicates or a batch bound. Reads filter expired rows before evaluating state, so expired gameplay effects are not restored. The deletion targets only `actor_active_conditions`; it does not delete the separate `entity_mutation_effects` receipt that `ApplyActorCondition` checks before mutation. No concrete duplicate-application or S2-loss claim is made from this path. The target still requires explicit S1/S2 classification, a replay/restore horizon, dependent-reference/hold checks, a receipt-retention guarantee, a safe watermark, and typed target, request-digest, and concurrency proof under [ADR 0163](../../decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md); expiry is not itself disposal eligibility.
- Realm-authored actor entry remains partial: legacy creation and actor rows still expose fixed RPG-oriented fields, policy/descriptor/template resolution and auto-provision idempotency are incomplete, and synthetic-ID/fork-copy proof gaps remain. These target rules do not claim runtime convergence.
- The live runtime-instance cleanup path currently deletes room-ground inventory, item stacks, item instances, and container instances by `(tenantId, gameInstanceId)` alone. It does not apply namespace, scope, and holder/container closure or prove S3 classification, so its cleanup acknowledgement is unsafe for replacement until owner-local classification, fencing, and focused proof are complete.
- The live Entity schema does not yet represent the target namespace-stable identity for durable S1/S2 state: `characters` and `actor_resource_states` retain legacy `playable_state_key`, while `inventory`, `character_friend`, `character_equipment`, and durable holder/containment rows omit `playableStateNamespaceId` (and some omit `tenant_id` entirely). The current repositories consequently cannot enforce the target `{tenantId, playableStateNamespaceId, domainObjectId}` identity at the SQL boundary. A convergent migration, legacy-row disposition/backfill evidence, namespace-aware repository predicates, and focused isolation proof remain required; until those are complete, legacy `playable_state_key` rows (including `character_friend` associations reached through them) are not namespace-isolated proof and cutover readers must not treat them as namespace-backed survivor state. This does not change the target contract above.
- The live `item_stacks` uniqueness constraint includes nullable holder columns, so PostgreSQL can accept duplicate rows for the same tenant/holder/item/fingerprint when another holder column is `NULL`. Inventory and container mutation paths still use find-then-create/save rather than an atomic conflict-safe operation. With no explicit `stackFamilyKey`, source resolution rejects more than one candidate row; with an explicit family key, the current implementation selects the first matching row and does not reject duplicate matches. Target resolution must enforce exactly one matching stack (or fail closed), alongside holder-kind-specific uniqueness or normalized non-null identity, atomic upsert/locking, and concurrent proof.
- The current `characterGraph` helper caches `getWithInventory(characterId)` by character ID alone with a TTL and performs no namespace or authoritative version validation. It is not the target namespace-qualified Class A `character-cache:*` contract; until that contract is implemented and proved, this cache is not correctness authority.
- `EntityDraftDesignDigestServiceImpl` currently emits `digestSchemaVersion=1` without hashing item `equipmentSlotGroupKey`, while `EquipmentServiceImpl` uses that field for slot compatibility admission. The digest is therefore incomplete for equipment semantics: different slot-group constraints may attest the same content digest. Target convergence adds the normalized field, bumps the schema version, and proves cross-version digest isolation plus publish-gate rejection of a changed constraint.

- Entity V1003–V1005 adds independent opaque actor UUIDs, quarantine metadata, exact namespace/scope constraints, and canonical lookup indexes without inferring Account or tenant UUID provenance. Legacy values are retained. The exact canonical repository reader excludes quarantined rows. Existing legacy consumers retain develop behavior for legacy rows, while numeric character, inventory, equipment, friend, resource and condition predicates exclude actors carrying canonical `tenant_uuid` provenance even when their status becomes `QUARANTINED`, including updates and condition expiry. Legitimate legacy/quarantined rows with null canonical tenant provenance retain legacy behavior. Entity's private tenant-row association is not a platform numeric tenant identity; equal numeric values do not authorize legacy access to a canonical actor. Canonical UUID assignment, receipt and roster predicates remain separate. This narrow storage bridge boundary does not activate runtime quarantine enforcement or canonical player admission.
- Entity V1006–V1010 adds committed assignment/receipt and sealed roster-snapshot storage with canonical publication identity and exact owner counters. The services and certificate-guarded client/read-adapter source definitions are manually composed and unregistered; neither default scanning nor an optional flag registers them. No authenticated production transport, actor promotion of retained legacy rows, or runtime admission is activated. The detailed assignment/roster contract remains in [Entity API Contracts](./api-contracts.md#implementation-status).
- Forward V1016 adds the immutable `PLAYER_CREATED` source identity and created-receipt branch while preserving the `PRESEEDED_ONLY` guards and budgets. The [owning tracker](../../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md#player-created-owner-and-roster-preparation) records qualified controlled initial storage/transport composition and separate retention components. Full guarded post-issuance supersession through retirement remains unrun; the path stays unregistered, with production custody, Account settlement, admission and activation unproved.
- Sealed canonical roster snapshots and their actor references are immutable Entity-owned records: append a new snapshot/reference set when the canonical target or roster changes, preserving the existing deletion guards. The [family retention declaration](#sealed-canonical-roster-retention) defines its ADR 0163 obligations. Before any handler, service, or alternative snapshot writer is registered, owner-local disposal must be proved with authoritative terminal/reference/hold carriers and compatible configured horizons. Local carrier and disposal source is prepared with focused qualification; genuine producer composition and registration remain unavailable. See the [implementation tracker](../../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md#canonical-sealed-roster-retention-prerequisite). This does not set a numeric horizon, authorize deletion, or establish indefinite retention. The current passive writer has no registered production call chain; its pending implementation obligation is tracked under #2967 and is not completion or merge proof.
- Standard, production, and test Flyway profiles use PostgreSQL `transactional-lock=false` for V1004's concurrent index builds. Entity V1003–V1010 follows unchanged develop Saga V1000–V1002 under the default combined migration locations. Current storage, fresh packaged legacy player-flow, and actual-parent upgrade qualification and its local infrastructure limits are recorded under [GR-3.1](../../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md#validation-and-proof).
- Actual authenticated owner provenance and the real Game Session caller/controller/execution replacement remain incomplete. Test-created `OWNER_RESOLVED` rows establish bounded storage isolation only; legacy names, numeric IDs, `shared-live`, `instance:<id>`, and runtime pointers never fill missing canonical owner evidence. Runtime quarantine guards, cleanup holds, and condition-expiry retention belong to the later runtime boundary.

## Architecture and Design Notes

- Uses the service-owned PostgreSQL schema and the platform `jOOQ + Flyway` persistence direction. Flyway owns schema evolution and generated jOOQ types are the default SQL access path; the narrow PostgreSQL-specific plain-SQL escape hatch requires focused proof and does not create a parallel ORM authority.
- Exposes gRPC endpoints for other microservices.
- Caches frequently accessed character data in Redis for quick lookups.
- The target persistence design applies tenant- and namespace-bound optimistic version CAS to avoid conflicting updates on the same entity; the current character update path is not proof of that invariant (see Implementation Status).
- Entity Management instances are intended to be replaceable workers over authoritative persistent state and documented caches. Item, inventory, containment, and character data that must survive instance loss belongs in the service-owned database rows and cache invalidation model, not as the sole authoritative copy in one process.
- Database writes are deferred and batched for ordinary entity updates, not triggered on every gameplay action. The Game Session Service coordinates real-time updates using Redis; the database is normally updated when ticks complete.
- Spatial containment mutations that participate in cross-service effects are the exception: before Entity Management acknowledges a spatial mutation's persisted `EffectId` (the root `EffectId` for a root effect or the persisted child `EffectId` for a generated child effect) back to Game Session, it must durably flush the effect’s idempotency guard plus the affected containment/container rows for that effect within the same local transaction. A participant acknowledgement must never be emitted for Redis-only staged state.
- Target-state cross-service participant guards follow the owner-declared runtime-family partition: `(tenantId, playableStateNamespaceId)` for S1/S2 or `(tenantId, gameInstanceId)` only for explicitly classified S3, together with the persisted mutation `EffectId`, typed operation, and exact target aggregate. `requestDigest` is separately persisted and exact-validated conflict metadata, not a uniqueness dimension; S1/S2 scope and active-instance evidence are validated separately, and any enclosing `rootEffectId` is lineage and reconciliation context. A mismatch fails closed. This target guard is not the current domain-local replay table described above; see [Transaction Strategies](../../system-architecture-transactions.md#tick-effects-are-at-least-once-idempotency-is-mandatory).
- The target design reduces write frequency and contention, making optimistic version CAS a natural fit because most entities are updated by only one process at a time and conflicts are rare; it remains target-only until the predicates, affected-row checks, and concurrent proof exist.
- Item transfers and other gameplay actions span services but execute within ticks using Redis scripts for rollback. Sagas are reserved for non-gameplay workflows. See [Transaction Strategies](../../system-architecture-transactions.md).
- For long-running, non-gameplay workflows such as publishing a game version, this service participates as a domain step in durable publish workflows coordinated by the Game Design Service as described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- **Target state:** Entity-owned tables carry a `tenantId` column wherever the row is a tenant-isolation boundary, and service methods enforce that tenant predicate; Redis keys mirror this prefix. **Current exceptions:** the live `inventory` and `character_equipment` tables have no `tenant_id` and obtain isolation by joining their `character_id` to `characters.tenant_id`; `crafting_ingredients` has no `tenant_id` and is reached through its tenant-owned `crafting_recipes` parent. The current `CraftingRecipeRepository.findWithIngredientsById` lookup is ID-only, so it does not itself prove a tenant predicate even though tenant-scoped list methods select recipe IDs first. These exceptions and the holder/containment cleanup gaps above mean the target all-table/filtering claim is not current proof. Details are in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Durable playable state that survives runtime replacement is keyed by `(tenantId, playableStateNamespaceId)` plus the applicable domain object identity and is authorized against the separately validated immutable `playableStateScope` and currently active `gameInstanceId`; `gameInstanceId` alone is reserved for explicitly disposable S3 runtime families. At target, S1/S2 durable storage identity is `{tenantId, playableStateNamespaceId, domainObjectId}` and omits both routing scope and `gameInstanceId`. Cross-service effect participant replay/deduplication follows the owner-declared partition: namespace-backed S1/S2 uses `(tenantId, playableStateNamespaceId)`, while explicitly classified S3 uses `(tenantId, gameInstanceId)`. For S1/S2, `playableStateScope` and active `gameInstanceId` are separately validated evidence, not participant uniqueness; the explicit S3 `gameInstanceId` is part of its partition. `requestDigest` is separately exact-validated conflict metadata, not a uniqueness dimension. Other service-local idempotency keys remain operation-specific. The namespace is resolved by the realm/lifecycle contract and is never inferred from an entity id, Redis key, or caller-supplied remap identifier. See [ADR 0122](../../decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md) and [Transaction Strategies](../../system-architecture-transactions.md#tick-effects-are-at-least-once-idempotency-is-mandatory).
- `playableStateScope` is accepted only as the realm/lifecycle owner's authoritative resolved snapshot or attestation. Callers cannot select or derive it, and Entity validates the exact immutable `{playableStateNamespaceId, playableStateScope}` pair before create or update; a scope change for an existing namespace is rejected rather than reinterpreting or splitting durable state. The `{tenantId, playableStateNamespaceId, characterId}` key is the stable actor storage identity, while `{tenantId, accountId, playableStateNamespaceId}` is the auto-provision actor-uniqueness identity; the separate operation replay guard uses `{tenantId, playableStateNamespaceId, accountId, autoProvisionRequestId}` and binds the exact `mutationDigest`. Neither identity reinterprets scope. `gameInstanceId` remains only the active-instance authorization fence. See [ADR 0122](../../decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md).
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
- Live runtime entities (characters, inventories, containers including room-ground containers) are stored in runtime tables keyed by `(tenantId, playableStateNamespaceId)` plus domain object identity for durable state and by `gameInstanceId` for explicitly instance-scoped state. Namespace-backed writes separately validate the immutable resolved scope and active-instance fence.
- Publishing a version finalizes template rows for that `(tenantId, versionId)` and records them as immutable inputs for future game instances. Runtime entity state never changes those template rows; it only references them via stable identifiers.

Template identifiers are stable within each version: a given template ID must not be repurposed to represent a different conceptual entity while any non-Retired version still references it. When switching a game instance to a new `runtime_version`, the Game Session Service and Entity Management treat missing or incompatible templates as a fatal configuration error for that launch; the version selection must be corrected rather than silently substituting defaults or partial data.

### Target-State Replacement-Instance State Classification

Entity Management must classify its runtime persistence surface for cutover and migration tooling:

- `S1` entity-owned durable state within the resolved `(tenantId, playableStateNamespaceId)` identity, with `playableStateScope` separately validated as immutable routing/authorization evidence:
  - `character` identity/account-ownership rows and equivalent progression/currency records that do not require version remapping when referenced templates remain valid;
  - persisted `actor_resource_states` rows whose resource identity and source provenance remain valid in the target release without a template remap;
  - stable player-owned inventory/container membership for item instances that remain valid against the target version without remapping, keyed to the same namespace plus domain object identity.
- `S2` entity-owned version-mapped durable state within that resolved `(tenantId, playableStateNamespaceId)` identity, with the same separate scope validation:
  - equipment-slot bindings for equipped items whose template validity depends on the target version;
  - learned-ability, starter-loadout, class/archetype, or equivalent durable character references whose validity depends on target-version template identifiers;
  - persisted `actor_active_conditions` instances, when their frozen condition definition/release or applied-effect snapshot requires target-version mapping;
  - inventory or character rows that remain durable but reference templates requiring an approved remap to the target version.
- `S3` entity-owned ephemeral state:
  - synthetic room-ground containers and their contents keyed by `(tenantId, gameInstanceId, roomInstanceId)`;
  - transient containment, encounter-specific entities, corpses, summons, or equivalent rows whose lifecycle is tied to the source `gameInstanceId`;
  - Target-only `entity_tick_state` watermark rows keyed by `(tenantId, gameInstanceId, playableStateNamespaceId, regionId, targetAggregateType, entityId)` for the concrete instance/region timeline; `playableStateScope` is separately persisted and exact-validated evidence, not a key dimension. No such live table or projection is currently implemented: the live schema has `entity_mutation_effects` for narrower effect/operation replay instead. Entity exact-validates the owner-resolved `playableStateNamespaceId` and immutable `playableStateScope` pair before reading or writing this future disposable watermark; neither value is inferred from an entity or instance id. Replay reads that exact row and compares `(last_region_epoch, last_tick_id)`, while current durable effect replay uses the `{tenantId, effectId}` table with operation-name-only matching; the namespace/scope/fence, typed-target, and request-digest guard remains target-only;
  - any row family explicitly documented as instance-scoped only.

### Conservative Current Implementation Inventory

The following inventory describes the conservative current implementation boundary, not proof that the target classification contract is complete. In particular, current `inventory` and `character_equipment` rows are treated as `S2` and require an approved remap; the target rules above remain the authority for replacement classification.

- `character` rows are target `S1` only within the resolved `(tenantId, playableStateNamespaceId)` identity after validating its immutable `playableStateScope`. Shared-state realms use the tenant-live namespace, while isolated-state realms use their stable isolated/playtest namespace; a replacement `gameInstanceId` does not create a new durable character identity. Current legacy rows remain provisional until the namespace migration, legacy-row disposition/backfill, namespace-aware predicates, and isolation proof complete; cutover readers must not treat a legacy `playable_state_key` as namespace-backed.
- Player progression/currency/account-ownership rows attached to `character` and not requiring template remap are `S1` only after the caller proves the same resolved namespace, immutable scope evidence, and current active `gameInstanceId` fence as the character row. Mutation APIs must not update progression/resource-style state by global `characterId` alone.
- Inventory membership / containment rows for durable player-owned containers remain `S1` within the resolved namespace, with separate scope validation, when every referenced item template is still valid against the target version.
- `equipment_bindings` rows are `S2` within the resolved namespace with separate scope validation.
- Durable learned-ability, class/archetype, starter-loadout, or similar template-reference rows are `S2` within the resolved namespace with separate scope validation.
- `actor_resource_states` rows are `S1` when their resource and source-provenance identity remains valid without a target-version remap; rows whose authored source requires remapping are `S2`.
- `actor_active_conditions` rows are durable actor-state survivors: `S1` when their frozen definition/release and applied-effect snapshot remain valid without remapping, otherwise `S2` and requiring an approved mapping. Their expiry and source metadata do not make them `S3`.
- Durable inventory or character rows that need an approved template remap to remain valid are `S2` within the resolved namespace with separate scope validation.
- Synthetic room-ground containers keyed by `(tenantId, gameInstanceId, roomInstanceId)` and their containment rows are `S3`.
- Encounter-scoped NPCs, corpses, summons, temporary containers, and any containment rows tied only to the source `gameInstanceId` are `S3`.

Replacement classification rule:

- Every Entity-owned family must be explicitly registered as `S1`, `S2`, or `S3` for the exact namespace/scope/version transition. Unknown, unowned, or unclassified families block cutover; they are never treated as S3 by default.
- Replacement-instance workflows must not infer template remaps from names, display text, or best-effort similarity. A supplied or echoed `remapSetId` is only a reference to resolve; it is not proof that Entity validated the exact source/target mapping or applied it successfully. Only owner-validated and owner-applied mapping evidence may satisfy `S2` compatibility.

Implementation notes:

- The current cutover-validation RPC is `ValidateEntityUpgradeMappings(tenantId, sourceGameInstanceId, targetVersionId, remapSetId?)`; the target contract must additionally bind the owner-resolved `playableStateNamespaceId` and `playableStateScope`, active-instance authorization, and the exact source/target versions. Entity validates that scope evidence; it never derives scope from the opaque namespace. The current signature and shallow implementation do not prove the target contract.
- The live implementation enumerates tenant-surviving families (`character`, `inventory`, `character_equipment`, `character_friend`) plus the currently persisted families requiring holder classification (`room_ground_inventory`, `item_instances`, `item_stacks`, `container_instances`). The storage/table family `character` is emitted as the plural wire label `characters` in `checkedFamilies`; that is one explicit storage-to-wire mapping, not two families. The mapping should remain canonicalized or explicitly represented in the target family registry. It does not currently enumerate the durable `actor_resource_states` or `actor_active_conditions` families, even though both are live persistence tables and are consumed by actor-state reads/mutations; the current validation response therefore cannot claim complete actor-state classification.
- `item_instances` and `item_stacks` are not table-wide `S3` families: each row follows its holder/container graph. A durable player or durable namespace-backed container holder identified by `(tenantId, playableStateNamespaceId)` plus its domain object identity is `S1` or `S2` according to template-remap requirements; `playableStateScope` remains a separately validated predicate, and only a synthetic room-ground holder or another explicitly instance-scoped holder is `S3`. Termination cleanup must apply the holder/container, namespace, and scope predicates and must not delete all rows in either table by `gameInstanceId` alone. The current table-level enumeration does not yet prove this predicate, so this remains an implementation/proof gap rather than permission to classify durable inventory as `S3`.
- `character` and `character_friend` rows have the target `S1` survivor classification within the resolved `(tenantId, playableStateNamespaceId)` identity and immutable scope evidence, and their presence does not require a remap set by itself. At the current boundary this classification is provisional: the live `character`/`character_friend` data is not namespace-isolated proof while legacy `playable_state_key`/namespace-free rows remain, so cutover readers must fail closed or require the completed migration, namespace-aware predicates, and focused isolation proof before treating either family as namespace-backed.
- **Target classification:** `inventory` and `character_equipment` rows are `S2` template-bound survivor state only within the resolved `(tenantId, playableStateNamespaceId)` identity and immutable scope evidence. The current schema has no namespace column for these families, so the live tenant-wide `hasS2Rows` count cannot establish that rows belong to the requested namespace/source instance and is non-authoritative cutover evidence; a namespace-bound implementation must first prove the rows' scope. Under the target contract, if either family has rows in that resolved namespace and no approved `remapSetId` was frozen by launch resolution, validation returns `result=INCOMPATIBLE`, `hasS2Rows=true`, `remapSetRequired=true`, and `ENTITY_REMAP_REQUIRED`.
- The live `actor_resource_states` and `actor_active_conditions` tables retain legacy `playable_state_key` rather than the target namespace identity and are not included in the current shallow family enumeration. Their current repositories and validation path therefore do not prove namespace isolation, source-instance coverage, or version-aware classification. Cutover readers must keep both families blocking/unclassified until a namespace-aware migration and owner-local validation registers them explicitly as `S1` or `S2`; the target classification above does not claim this is implemented.
- When template-bound `S2` rows exist within that resolved namespace and scope evidence and the caller supplies the frozen approved `remapSetId`, the current implementation reports `COMPATIBLE` and echoes that id. This result is non-authoritative and remains blocking for cutover: no caller or consumer may treat the echoed identifier as admissible compatibility or cleanup proof. Entity Management must validate and apply the exact Game Design-approved mapping locally before acknowledging compatibility. Entity Management does not infer remaps or create a second remap identity; Game Design remains the source of truth for approval and the prepared cutover artifact binds the exact id used.

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

- The current request carries `tenant_id`, `source_game_instance_id`, and `target_version_id` as positive decimal strings (for example, `"7"`, `"42"`, and `"43"`), which the service parses to numeric internal IDs; `remap_set_id` is optional text. The response below is only the live `ValidateEntityUpgradeMappingsResponse`: it does not echo tenant/source/target identifiers and exposes no target-only namespace, scope, fence, or classification fields. Its `stateClassesChecked` and `checkedFamilies` fields are aggregate current-response fields: `checkedFamilies` contains emitted family names, including the live spelling `characters`, and carries no per-family outcome. The live `hasS2Rows` calculation uses tenant-wide row counts rather than proving rows for the requested source instance, so it is not source-instance cutover evidence. The response is an incomplete, non-authoritative table-level enumeration. The `item_instances` and `item_stacks` entries below are family names consistent with the live `checkedFamilies` response only; they do not provide holder-scoped evidence or establish a synthetic-room-ground-only filter, nor do they classify every row in either table as `S3`.

```json
{
  "stateClassesChecked": ["S1", "S2", "S3"],
  "checkedFamilies": [
    "characters",
    "inventory",
    "character_equipment",
    "character_friend",
    "room_ground_inventory",
    "item_instances",
    "item_stacks",
    "container_instances"
  ],
  "hasS2Rows": true,
  "result": "INCOMPATIBLE",
  "remapSetRequired": true,
  "reasons": [
    "ENTITY_REMAP_REQUIRED: inventory and equipment rows reference durable entity templates and require an approved remapSetId before replacement-instance cutover"
  ]
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
  "familyClassifications": [
    {
      "family": "actor_resource_states",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "actor_active_conditions",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
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
  "familyClassifications": [
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
  "familyClassifications": [
    {
      "family": "character",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "actor_resource_states",
      "referencedTemplateIds": [],
      "outcome": "COMPATIBLE"
    },
    {
      "family": "actor_active_conditions",
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
- The first shared effect-evaluation seam is an in-process Entity Management service that evaluates typed resource modifiers and granted states deterministically. Active condition payloads and equipped item-template payloads are `CONTINUOUS` effect sources: they contribute while their source exists and never write derived values into current resources during reads. When an attached source changes a bounded-resource maximum, its attach, detach, refresh, expiry, or replacement mutation performs the one idempotent capacity normalization using the declaration override or resolved tenant/game `actorState.capacityChangePolicy` captured by that mutation. Evaluation reads never normalize current state. Gameplay-attested `ApplyActorCondition` calls are the first `INSTANT` mutation path, creating active condition/action-state rows through the same internal mutation service under an idempotent effect id. A scheduled Entity Management job globally and without a batch bound deletes elapsed active-condition rows on a 30-second default fixed delay measured after the previous invocation completes; read-time filtering prevents expired effects from being restored before cleanup. The job does not delete separate `entity_mutation_effects` receipts, which are checked before mutation, but receipt-retention and typed replay/concurrency proof remain target gaps. Player command wiring remains future work.
- Target-state active-condition rows are condition instances with stable instance ids, frozen definition/release snapshots, source provenance, stack counts, expiry, and applied-effect snapshots. Condition-definition DML, not source-id coincidence, selects `REPLACE`, `REFRESH`, `STACK`, or `PARALLEL` reapplication behavior and its duration policy. Typed removal resolves exact keys, authored tags, or permitted sources in deterministic definition priority and instance-id order; it must not expose raw row or payload deletion to player-facing callers.
- World Management owns the guarded initial `character_location` placement and purpose-specific read boundary described by the [World initial-location owner](../world-management-service/runtime-and-data.md#character-location-ownership). General movement, occupancy, NPC location and instance-membership behavior remain target state. Entity Management owns and persists item instances and inventories, but must not add competing actor-location fields or tables.
- Entity graphs cache inventory relationships for fast lookups.

### Sealed canonical roster retention

Implementation status: sealed canonical roster retention remains unregistered, and current source and proof status is recorded in the [implementation tracker](../../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md#canonical-sealed-roster-retention-prerequisite).

Entity Management owns the exact `entity_canonical_gameplay_roster_snapshots` and `entity_canonical_gameplay_roster_snapshot_actors` family together with its `entity_canonical_roster_retention_lifecycle`, `entity_canonical_roster_retention_references` and `entity_canonical_roster_retention_releases` protection records, plus `entity_canonical_roster_original_selections` immutable original Game Session operation/fence receipts, `entity_canonical_roster_admissions` local admission nonces and `entity_canonical_roster_disposal_receipts`, together with durable `entity_canonical_roster_retirement_transfers` observation and delivery lineage and compact owner-local retired-target seals. Its [ADR 0163](../../decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md) class is **recovery and reconciliation lineage**: the sealed ordered actor references and immutable reference-operation outcomes protect original roster selection and exact assignment readback. This family does not include the referenced actor records or the retained assignment-operation/receipt family, and disposal never grants authority to delete those records.

Only an owner-retired, sealed snapshot whose dependent selections, retries, recovery and reconciliation are terminal can become eligible. A different active target, an older `created_at`, a changed roster, or an instance termination alone does not establish retirement. A current snapshot, any outstanding original-selection/readback reference, any recovery/reconciliation dependency, and any owner or governance hold blocks disposal. Unknown reference, terminal, currentness or hold state blocks disposal as well. Retirement and final disposal must bind the exact snapshot UUID/digest, account, tenant, namespace/scope and complete target including pointer version and active world epoch; stale target or fence evidence fails closed.

The finite minimum horizon is configuration-backed operational policy derived from the supported retry and restore/replay windows, with no universal numeric duration or indefinite-retention fallback. The restore/replay window includes all time during which a supported restore can still resurrect and retry a dependent selection. Both declared windows must be covered by the minimum horizon, measured from authoritative terminal retirement rather than snapshot creation. `CanonicalGameplayRosterRetentionPolicy.fromConfiguration` requires all `firemud.canonical-roster.retention.*` inputs: `minimum-retention`, `supported-retry-horizon`, `supported-restore-replay-horizon`, `cleanup-cadence` (positive ISO-8601 durations), and `batch-size` (a positive finite integer). Missing, malformed, nonpositive or incompatible inputs leave cleanup unavailable; configured values alone never establish eligibility.

Preparation advances through bounded stable original-operation pages; physical disposal removes an eligible snapshot's actor references, completed protection-operation records, lifecycle and parent in one fully qualified owner transaction. Protection-operation receipts remain online for their entire supported retry/restore horizon; a reference's release never permits early removal of its acquire/release outcome. Disposal requires reference acquisition, preparation and disposal to serialize on authoritative owner state. Its final revalidation binds the exact snapshot/account/full target, persisted authenticated retirement proof and final local epoch, rechecks all applicable horizons and current reference/hold closure, and positively establishes preparation for every actual original operation and local nonce against the sealed original inventory. Missing originals, nonces, acquisitions, releases or acknowledgement intents never constitute completion. Progress cursors or counters do not replace those final checks. Forward owner-local SQL guards preserve existing immutability, provenance, original-transfer and disposal-receipt requirements, and all child and parent deletion rolls back on failure. A caller-set session flag or an unqualified direct table delete cannot authorize cleanup. There is no archive substitution for an online original-selection/readback dependency, and no partial compaction of a protected sealed snapshot.

A local admission nonce is reserved before the Game Session owner read and must remain open at final capture. Authenticated Game Session retirement closes original references only for its exact shared account/target fence; unknown local recovery references and governance holds still block disposal. A positive owner seal may be retained locally while a hold remains, so later source unavailability cannot erase already observed immutable authority. Holds may be acquired or released after owner retirement; those changes advance the local epoch and restart the Entity-local minimum horizon. New original or recovery acquisition is refused after that seal. Disposal revokes every bound admission nonce atomically, removes only this snapshot family, and retains compact disposal/admission receipts for the complete configured local retry/restore horizon. After bounded tombstone cleanup, a delayed handler's old nonce remains unusable because final capture requires the existing exact nonce and never creates one. Entity uses only its own database clock for observation, reference changes, horizons and cleanup.

Initial retirement observation atomically retains the independently authenticated exact Game Session proof and records a per-snapshot seal under the shared account/full-target acquisition fence. The first seal closes new or recovered original acquisition, delayed capture by an already reserved nonce, and alternate snapshot creation for every sibling at that account and full target. Existing sibling snapshots and their already-retained owner records remain readable for readback and retirement-observation work; the first seal does not authorize a delayed nonce capture or assert that sibling preparation or disposal is complete. Each seal retains its own snapshot UUID/digest, owner proof, inventory, local epoch, holds and stored horizons, so retirement evidence remains individually qualified. The V1019 `entity_canonical_roster_snapshot_targets` inventory records each retained snapshot UUID/digest with a `SNAPSHOT` or `DISPOSED_SEAL` source kind. Its non-authoritative target projection is derived from the exact immutable snapshot columns, including entry policy and nullable `canonical_player_created_source_identity`; snapshot source projection and, for a disposed seal, the positive owner-retirement proof remain separately retained. This lets the shared fence enumerate direct-capture siblings that have no admission or original-selection row, even after an anchor payload is gone. The inventory grants no retirement, disposal or release authority. New sealed snapshots are captured by the migration trigger; preexisting snapshots and positively disposed seals require bounded exact-source initialization. Missing, malformed or unknown source evidence remains pending and protected; absence of payload is not evidence of disposal.

Payload disposal is evaluated per snapshot. A compact seal remains while its sibling snapshots or surviving nonce, original-selection, transfer or hold lineage can still support work. Removing seals so that the shared acquisition fence disappears requires all sibling snapshots to satisfy their own disposal conditions and all surviving shared dependencies and each stored retry/restore horizon to be closed. Retained immutable proof remains usable while Game Session is unavailable.

Each bounded preparation page rechecks the sealed proof and current local epoch. For each original it qualifies the unchanged original request, actual admission nonce, immutable snapshot UUID/digest, full account/target and shared Game Session owner fence under admission-before-snapshot-before-lifecycle locking. Its qualified original-reference release and stable durable acknowledgement intent commit atomically. Existing exact transfer lineage may supply progress; missing or mismatched work remains incomplete. Durable stable-page advancement gives later originals their turn after an incomplete original, and completed originals do not cause repeated per-original preparation while a hold protects the payload. Unknown local recovery dependencies and governance holds remain unreleased and block final disposal while independently qualified originals can still advance. Existing intent for an operation prevents a new reservation or delayed capture from reusing that operation as live authority. A proof anchored by another original may cover an intent only for the same immutable snapshot and sealed full account/target fence. Pending transfer evidence survives local snapshot, original-selection and nonce disposal.

Compact target seals remain protected while pre-seal nonce, original or transfer lineage can support delayed work. Removal of any seal for an account/full target requires every sibling snapshot's positively recorded, fully qualified payload disposal, all applicable stored retry/restore horizons, and closure of all surviving shared nonce, original-selection, transfer and hold lineage under their own stored horizons. Owner-local target classification metadata binds the exact immutable admission or transfer source bytes without rewriting those source records. The sibling metadata bridge separately binds snapshot identity to the exact target, so admission/transfer lineage remains distinct from sibling inventory and neither record type supplies the other’s authority. Classification proceeds in bounded, restartable pages. The owner distinguishes a source awaiting its first classification from malformed or otherwise unclassifiable source bytes; neither state identifies a verified target or permits release. Until every retained source is initialized and positively classified, one unresolved source conservatively blocks acquisition and seal cleanup across this roster boundary, including for otherwise unrelated accounts. Once sources are fully classified, known target effects are exact and indexed, so dependencies for one target do not block unrelated known targets. A validated positive retirement transfer fences only its own complete account/target while that transfer remains protected. Classification metadata becomes removable only after its source has been removed through the existing owner guards. Seal cleanup cannot revive a revoked nonce: final capture still requires its exact existing open nonce and a qualifying original owner read. Neither a timeout nor an alert substitutes for resolving an unclassifiable source or authorizes a safe release.

The bounded sweep emits and retries the [Game Session retirement acknowledgement](../game-session-service/api-contracts.md#original-canonical-roster-selection-reference) outside SQL. Entity commits the actual authenticated first acknowledgement response locally before sending delivery confirmation. Lost replies therefore retry the same original intent and compact receipt; only confirmed transfer records whose complete stored local retry/restore horizon has elapsed may be removed. An unbound closed admission that is disposed while its Game Session original is merely `DENIED` atomically leaves a `WAITING_RETIREMENT` intent with its exact original closure receipt and nonce, so later retirement can still be observed and acknowledged. Missing or nonterminal owner evidence keeps that intent protected. A retained positive retirement proof permits subsequent snapshot disposal without another Game Session read.

Original-selection recovery closes an admission without releasing its original snapshot/reference family. The [recovery API](./api-contracts.md#implementation-status) requires an independent exact Game Session recovery read before local SQL. The admission row retains the immutable recovery request/response, owner request/fences, recovery identity, Entity-clock `closed_at` and configured minimum retention. Closing and binding serialize on that same nonce row: a completed bind supplies the original local receipt, while a completed closure makes a delayed bind fail. Bound `CLOSED` admissions remain attached to protected snapshots and can become `DISPOSED` only through the existing fully qualified snapshot disposal path. An unbound `CLOSED` admission remains protected while Game Session is unavailable, unknown or nonterminal. Its bounded sweep reads Game Session outside SQL and purges only after the independently read exact same recovery UUID, owner fence and target fence positively acknowledge terminal `DENIED` or complete retirement and the Entity-local closure retention has elapsed. Persisted acknowledgement evidence and local state are rechecked under the nonce lock; time alone never authorizes that purge.

An unbound `OPEN` admission with no local original selection is a distinct local orphan case. After its Entity-clock `recorded_at` plus the configured minimum horizon, bounded cleanup locks the nonce, captures that finite horizon in transient `EXPIRED` state, and deletes it in the same transaction. SQL guards require both the elapsed local horizon and exact absence of a bound snapshot or original selection. Binding uses the same nonce lock, so cleanup cannot delete a concurrently committed binding. A later reserve allocates a different random nonce; delayed handlers retain the revoked nonce and cannot bind it. This local orphan cleanup never establishes a Game Session terminal outcome or releases any snapshot, reference, hold or recovery lineage.

The configured cadence and batch bound limit each owner-local sweep. Its bounded health metrics must report oldest blocking retirement, cleanup lag and per-sweep disposed/blocked/failure counts with fixed outcome labels; snapshot, actor, account, tenant and namespace identifiers must not become metric labels. These are family-specific cleanup obligations, not a global garbage-collection registration.

#### Player-created snapshot storage

Forward `V1021__character_create_binary_safe_immutability.sql` preserves the existing creation intent and terminal transition with an exact typed-row comparison, avoiding JSON materialization of large binary terminal/proof columns. Published creation V1015 remains unchanged. Forward `V1016__player_created_roster_snapshot_receipts.sql` extends the existing sealed-roster family after Entity creation V1015. A `PLAYER_CREATED` snapshot requires `SHARED` scope and immutable canonical `canonical_player_created_source_identity` bytes; preseeded snapshots keep that column null. Immutable entry-policy metadata selects the applicable admission, original receipt, lifecycle-proof and outbox branch. The created original receipt is distinct and mutually exclusive with the preseeded receipt. Prior preseeded digest/history, provenance, immutability, reference/hold, epoch, conditional-disposal and late-nonce guards remain effective. Created receipt/proof/ACK storage uses its explicit purpose budgets, including the complete selected terminal in retirement evidence, while existing preseeded caps remain unchanged. The [Entity API owner](./api-contracts.md#player-created-roster-and-selected-character) defines the wire identities and bounds. Entity's original-reference, retirement acknowledgement and confirmation client calls use a finite ten-second RPC deadline for validated `PLAYER_CREATED` targets; preseeded calls and generic owner reads retain five seconds. These deadlines cover the RPC itself, with request prevalidation and response validation performed separately.

Forward `V1020__canonical_roster_created_retirement_recognition.sql` extends retained target classification and snapshot inventory with additive typed recognition. A created retirement target binds the canonical account, complete target and complete canonical created-source identity; preseeded target bytes remain unchanged. Bounded initialization adds version-two metadata against exact immutable source bytes and positive retirement proof without replacing the original classification, snapshot, transfer or seal rows. The effective metadata view requires the applicable actual source family and exact retained evidence; a version marker alone grants no authority. A valid typed classification can qualify an earlier unrecognized created source, while malformed or uninitialized retained evidence keeps the roster boundary protected. Legacy seals whose stored target omits the created source stay protected rather than being rewritten. Created siblings use the same shared acquisition fence and independent per-snapshot preparation, holds, horizons and disposal checks described in [sealed roster retention](#sealed-canonical-roster-retention).

Capture uses one short writable owner transaction in the established order: admission nonce, original operation, namespace enrollment, tenant association and account roster guard, then the applicable snapshot/reference rows. Under those fences it checks actual retained creation-operation and actor provenance, captures the ordered roster, persists or reuses its exact source-bound snapshot, seals it and binds admission plus original receipt atomically. Any failed validation or bind rolls the capture back. Exact operation replay reads the retained receipt; selected readback requires the existing nonce and immutable original snapshot, checks the persisted original creation terminal and performs no actor allocation. Source and owner RPCs precede SQL, and the shared retention/recovery/outbox family preserves full created receipt bytes while using a validated generic projection only for snapshot comparisons. Unknown outcomes and outstanding references or holds retain their existing protection.

### Persisted actor and realm-entry identity

Under [ADR 0140](../../decisions/adr-0140-realm-authored-controllable-actor-entry.md), Entity Management is the persistence authority for the generic primary controllable actor. It allocates canonical `characterId` values and stores the namespace/association tuple `{accountId, tenantId, playableStateNamespaceId, characterId}`. The durable actor storage key is `{tenantId, playableStateNamespaceId, characterId}`; `playableStateScope` is separately validated server-derived policy/routing evidence and `gameInstanceId` is only the replaceable active-runtime fence for namespace-backed writes.

The scope evidence above must be the authoritative realm snapshot or attestation; callers cannot choose or derive it. Entity validates the exact immutable namespace/scope pair before create or update and rejects a scope change rather than creating a second or split actor record. A replaceable `gameInstanceId` remains an active-runtime fence, not a durable identity or a source from which scope may be derived.

The published realm catalog supplies exactly one `PLAYER_CREATED`, `PRESEEDED_ONLY`, or `AUTO_PROVISIONED` entry policy plus its policy-applicable versioned identity: a descriptor for `PLAYER_CREATED`, a template for `AUTO_PROVISIONED`, and neither for `PRESEEDED_ONLY`. Entity validates that policy and required identity/version before creating or listing actors. Auto-provision enforces at most one actor for `{tenantId, accountId, playableStateNamespaceId}` and separately guards each operation by `{tenantId, playableStateNamespaceId, accountId, autoProvisionRequestId}` plus the exact `mutationDigest`. Entity admits and locks that replay guard before allocation and persists the exact published template identity/version with the guarded outcome. An exact replay returns the persisted actor only when the guard, digest, and provenance match; a changed digest or provenance fails closed as an idempotency conflict and never silently reuses the actor or provisions a replacement. `playableStateScope` is validated server-derived routing/authorization-fence evidence only, not a competing persistence or replay key; `gameInstanceId` is the replaceable active-runtime fence. `CHARS`-equivalent reads return only persisted actors valid for the trusted account, tenant, realm namespace, and published policy; the response carries the policy-applicable descriptor/template identity/version and omits both for `PRESEEDED_ONLY`, so callers do not infer policy from row count. Zero actors therefore means create, provision, or deny according to policy; one may be selected automatically; many require explicit selection.

Copies into an isolated playtest namespace allocate a new fork-local `characterId`. When retained, `sourceCharacterId` must be accompanied by the immutable `{sourceTenantId, sourcePlayableStateNamespaceId}` binding and remains provenance only: that source tuple is never a live reference or authority for ownership, mutation, controller uniqueness, reconnect, cross-namespace reads, or merge-back. Game-specific RPG or non-RPG state is authored component data tied to the published descriptor/template and is not mandatory platform schema.

### Runtime Actor Identity

Entity Management owns one persisted runtime actor for every active gameplay being. In the target runtime projection, its actor identity is the typed numeric `runtimeEntityId`, scoped by `{tenantId, gameInstanceId}` as defined by the [identifier glossary](../../system-architecture-identifier-glossary.md). It is separate from the durable character UUID scoped by `{tenantId, playableStateNamespaceId}`. An older transport's `actorId` or character UUID is not silently reinterpreted as this runtime identity; services must not substitute private character row keys, parse or hash UUIDs, invent composite references such as `PLAYER:<characterId>`, or introduce another opaque alias for the same runtime actor.

- The actor core carries `tenantId`, `playableStateNamespaceId`, `gameInstanceId`, the resolved `playableStateScope` when it represents durable playable state, `actorKind`, display name, and presence state. It does not persist universal targetability or visibility fields.
- A `PLAYER` actor is a runtime projection of the persisted actor and is unique for the active `{tenantId, playableStateNamespaceId, gameInstanceId, characterId}` execution context; it does not replace the canonical durable actor storage key `{tenantId, playableStateNamespaceId, characterId}` or turn separately validated `playableStateScope` into an identity dimension. `gameInstanceId` remains the replaceable runtime target/fence, and this S3 projection may be recreated during replacement while preserving the namespace-backed character. Disconnect/reconnect changes presence on the projection rather than creating a replacement durable identity.
- An `NPC` actor links to one NPC runtime instance. An authored NPC definition may create many concurrent runtime NPC actors and is not itself actor identity.
- `PET` and `SUMMON` extend the same core when implemented. God/admin behavior is a capability and authorized presentation overlay on a `PLAYER` actor, not a separate actor kind.

Each actor also has one persisted, release-admitted `dispositionKey`: its main gameplay state. The disposition supplies base action-admission through published `ActionAdmissionTag` denials and semantic feedback policy. Conditions and equipment are explicit continuous overlays over that base; they can only add tag denials or otherwise narrow it and never grant behavior denied by the main disposition. They may also contribute ordinary game-authored state facts for later reusable `ObservationPolicy` and `TargetingPolicy` evaluation, but those facts do not bypass action admission or become universal visibility/targetability fields. Recovery, immunity, revival, and other exceptional main-state changes use an idempotent instant condition removal/prevention or disposition transition, so continuous sources do not become competing death/defeat lifecycle owners. Transport/session presence is a separate fact and must not be repurposed as disposition.

The actor is the shared subject for gameplay targeting, effects, stats, conditions, and communication. It does not move other service ownership:

- World Management is authoritative for guarded initial character placement through its [initial-location owner](../world-management-service/runtime-and-data.md#character-location-ownership). General actor movement, NPC location and room occupancy remain target state; topology and session bindings do not create that authority.
- Game Session owns only ephemeral session attachment, protocol state, and player-facing presence projection.
- Account Service remains authoritative for account identity and authorization inputs.

For published targeting predicates whose facts it owns, Entity Management exposes a bounded `TargetingFactSnapshot` for requested `{tenantId, playableStateNamespaceId, playableStateScope, characterId}` actor identities and the current runtime fence where required. The response includes only the requested actor-state facts and an actor-state revision token; it is not a general actor read. Before applying an approved effect plan, Entity Management validates the recorded token for every material Entity-owned fact. If any token is stale, it reports a pre-commit mismatch so Game Logic can discard and re-resolve the plan under the same effect id before any source cost or target mutation commits.

Character and NPC records remain the durable domain records for their respective variants. The runtime actor links those records into one gameplay subject model; it does not replace character progression, authored NPC definitions, or World Management location state.

### Initial Runtime Actor Projection and Reference

Fresh protected `PLAYER_CREATED` settlement allocates its own nonadmitting runtime projection in the same SQL transaction as the first `COMMITTED` terminal. The projection uses the shared runtime sequence and retains the exact creation operation, mutation digest, original terminal UUID/raw SHA256 and complete actor target in `entity_player_created_runtime_projections`. It is a separate purpose from a PRESEEDED assignment; a creation operation never becomes an assignment ID. Abort, terminal replay, roster selection and historical reference reads allocate nothing and never backfill an older actor. Missing projection state fails closed. The immutable projection and original terminal remain retained through retries and owner recreation; V1023 forbids update, delete and truncate and grants no cleanup or production registration authority.

The Entity-owned initial runtime projection is allocated only through the applicable protected creation or authenticated assignment owner chain after its current authorization and target checks. Its complete unique association is `{tenantId, playableStateNamespaceId, gameInstanceId, characterId}`. The durable projection retains its original purpose-specific operation and receipt/digest, complete target, projection fence and projection generation. Existing assignment, placement and roster character-UUID fields and immutable receipt bytes remain unchanged. For this projection boundary, the durable Entity `entity_runtime_entity_id_sequence` is the sole allocator. Adopting another canonical allocator requires an explicit migration that preserves uniqueness and nonreuse; allocator unavailability never authorizes runtime fallback to another allocator. A private database row key is never promoted into this role.

Allocation guarantees concurrent uniqueness, stable exact retry and restart, and nonreuse throughout the required runtime and retention lifetime. Reconnect alone does not replace the projection. Runtime replacement is target state: the replacement owner allocates a new projection generation and runtime ID while retaining and fencing the previous association. A controlled source cannot authorize production allocation. Allocation alone establishes neither World placement, an active lifecycle, a Game Session region lease nor admission.

The `entity_preseeded_actor_runtime_projections` and `entity_player_created_runtime_projections` runtime projection families are Entity-owned S3 for instance-lifecycle partitioning under [ADR 0163](../../decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md). This local classification records their storage and lifecycle consequence; it grants no deletion permission, numeric retention horizon, World registration, or cleanup authority. Their immutable associations and purpose-specific provenance remain recovery and nonreuse lineage subject to the canonical reference, hold, and retention rules. V1022 currently permits initial preseeded projections only, with assignment and association uniqueness constraints and update, delete, and truncate guards preventing replacement. V1023 currently retains player-created projections as immutable initial projections and forbids update, delete, and truncate. Implementing the target replacement transition requires forward schema evolution and the qualified replacement-owner chain while preserving retained associations and nonreuse. Entity has not registered either family with World Management or defined their termination disposition and cleanup acknowledgement, so room-ground cleanup does not cover them and whole-instance `TERMINATED` remains blocked until lifecycle registration and retention/cleanup proof exist. See [Instance Termination Cleanup Contract](#instance-termination-cleanup-contract), [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md), and the [Gameplay tracker](../../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md#active-gaps).

The existing assignment service exposes one separate purpose-specific runtime-reference read to the exact same-namespace Game Session workload. It selects the original assignment, complete target and character, loads the actual retained projection, and returns a closed bounded typed reference with its full owner-qualified association, original assignment provenance, projection fence and generation. The read never allocates or repairs missing projection state, and an unavailable, stale or contradictory source fails closed. The World-only immutable assignment-receipt read retains its distinct purpose and authority.

Game Session may join this reference to a region binding only after its independent exact World-applied initial placement, current region/lifecycle and actual region lease/fence checks. It must revalidate the original assignment, projection generation and expected epoch before the decisive bridge and final admission CAS. World location, Redis possession and supplied reference fields cannot create Entity authority. This narrow producer/read boundary grants no general entity lookup, public character creation, production registration, PKI custody or runtime activation.

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

The target inventory/equipment operation context for a durable holder or binding is `{tenantId, playableStateNamespaceId, playableStateScope, gameInstanceId, holderRef, itemInstanceId, itemDefinitionId, equipmentBindingId?, slotKey?}`, with one typed `holderRef` for each source, destination, or equipment owner required by the operation. A holder reference is a closed tagged union: character inventory or equipment carries `CHARACTER {characterId}`; NPC, pet, or summon ownership carries `ACTOR {runtimeEntityId}`, validated with `tenantId` and `gameInstanceId` under the current runtime fence; an ordinary bag, chest, corpse, bank, vendor stock, or nested holder carries `CONTAINER {containerInstanceId}`; and room-ground storage carries `ROOM_GROUND {roomInstanceId, containerInstanceId}`. `characterId` is therefore required only for character-owned inventory or equipment and must not be fabricated for another holder kind. For namespace-backed S1/S2 inventory, character, and equipment state, durable storage identity is `{tenantId, playableStateNamespaceId, ...domainObjectId}` and omits both `playableStateScope` and `gameInstanceId`; those values are validated separately as immutable routing/authorization evidence and the active-instance fence. Cross-service effect participant replay/deduplication follows the owner-declared partition: S1/S2 uses `(tenantId, playableStateNamespaceId)`, while explicitly classified S3 uses `(tenantId, gameInstanceId)`. `requestDigest` is separately exact-validated conflict metadata, not a uniqueness dimension; for S1/S2, `playableStateScope` and active `gameInstanceId` are validation evidence rather than participant uniqueness. Other service-local idempotency keys remain operation-specific. Explicitly instance-scoped S3 holders include `gameInstanceId` in durable identity. Future proto and OpenAPI surfaces must carry the same typed holder-reference union consistently, but current surfaces do not yet carry this complete operation context on every inventory/equipment read or mutation, so this is a target contract only: this ADR parcel does not add wire fields, regenerate protos, or change runtime lookup behavior. See [Transaction Strategies](../../system-architecture-transactions.md#tick-effects-are-at-least-once-idempotency-is-mandatory) for the canonical participant-guard contract.

This keeps the platform compatible with games that need unusual body plans or attachment models such as horns instead of hands, asymmetric limbs, species-specific slot topologies, or non-humanoid wearable layouts.

### Inventory Queries and Type Filtering

The current `QueryInventory` response is an unpaged repeated `InventoryItem items[]` projection. The live handler emits item/template identity and display fields (`item_id`, `item_name`, `item_description`, and `quantity`), with `container_instance_id`, `item_instance_id`, and `visible_ref` when present. The older `item_ids[]` description is not the current wire shape. This projection is only sufficient for the earliest bootstrap slices; the target-state inventory contract should support richer gameplay and UI queries:

- Query by containment scope such as inventory container, room-ground container, nested container, bank, vendor stock, or corpse.
- Query by equipped state and slot binding.
- Query by structural properties such as stackability, quantity, visibility, accessibility, and ownership.
- Query by game-defined item types, tags, or category taxonomies so gameplay commands and GUIs can filter for concepts such as quest items, reagents, weapons, salvage, consumables, rarity classes, or other design-defined groupings.

Current stack behavior:

- the live runtime model treats ordinary non-stackable items as distinct `item_instances`;
- item definitions expose an explicit authored `stackable` capability flag, defaulting to non-stackable;
- eligible authored stackable items use holder-local `item_stacks` rows with compatibility fingerprints, optional family keys, quantity-bearing query views, and merge/move mutation paths;
- same-definition non-stackable items remain separate physical instances rather than silently merging into aggregate quantity state.

This holder-local stack behavior is live but remains partial: nullable stack-holder uniqueness, find-then-create concurrency, and explicit-family duplicate handling remain the gaps recorded in the implementation-status item above. Target convergence still requires exactly-one matching-stack resolution (or fail-closed ambiguity), holder-kind-specific uniqueness or normalized non-null identity, atomic upsert/locking, and concurrent proof.

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
- Entity Management must register every additional `gameInstanceId`-owned family, its S3 classification, cleanup request identity, retention rule, and acknowledgement status with the lifecycle owner. Missing or unregistered Entity families block `TERMINATED`; cleanup of one known room-ground family is not whole-service completion. Namespace-backed S1/S2 state under `(tenantId, playableStateNamespaceId)` plus its domain object identity is retained or mapped under the replacement contract; the immutable `playableStateScope` remains separately validated and must not be reinterpreted, and that state is not deleted merely because one runtime instance ended. See [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md).

### Workflow Participation

Entity Management does not orchestrate its own synchronous saga or Temporal workflows and does not use them for tick-driven gameplay or inventory operations. For long-running, non-gameplay workflows such as publishing or rolling back a game version, it participates as a domain step in workflows coordinated by the Game Design Service and Game Session Service. These workflows finalize or validate versioned template data for `(tenantId, versionId)` without touching live runtime entities. See [Transaction Strategies](../../system-architecture-transactions.md) and [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) for the overall workflow patterns.

## Redis Role and Prefixes

- **Coordination Redis participation**
  - **Target state:** acquires tick locks via shared helpers using keys of the form `tick:{tenantRegionTag}:lock:<entityId>` so locks share a hash tag with tick queues and pending state as described in [Redis Architecture](../../system-architecture-redis.md#coordination-key-examples). **Current implementation:** `TickLockServiceImpl` still writes the legacy unscoped `tick:lock:<tenantId>:<entityId>` key with its private TTL formula and without `gameInstanceId`, `regionId`, or the canonical mutation fence; this drift is not a second lock authority and cannot authorize canonical mutation. See [Entity Management Operations](./operations.md#tick-locking) and the [Redis reset policy matrix](../../system-architecture-redis-reset-and-recovery.md#reset-policy-matrix-prefix-summary).
  - **Target state:** Treats lock TTLs and other coordination parameters as opaque values derived by the Game Session Service and shared helpers; it does not define its own coordination-specific configuration.
- **Cache/Rate-Limit Redis usage**
  - **Target state:** Uses Cache/Rate-Limit Redis to cache frequently accessed namespace-backed character graphs and related aggregates under prefixes such as `character-cache:<tenantId>:<playableStateNamespaceId>:<characterId>`, following the key naming and TTL/versioning patterns in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md). Explicitly instance-scoped S3 projections use their complete instance scope instead. The current character-ID-only helper is the implementation drift recorded above, not this cache contract.
  - **Target state:** These character graph caches are treated as Class A, versioned caches:
    - Cached payloads include a stable version or `lastModified` value derived from the authoritative character tables (for example, the `character.version` or `last_modified` columns exposed via Entity Management APIs).
    - Readers validate versions against PostgreSQL (or version fields surfaced via gRPC) before reusing cached data; on mismatch they recompute the graph and overwrite the cache atomically (value + TTL).
    - TTLs (for example, `FIREMUD_CHARACTER_CACHE_TTL_SECONDS`) act as a safety valve for memory and stale entries, not as the primary correctness mechanism.
  - Future namespace-backed inventory/containment caches use the `inventory:<tenantId>:<playableStateNamespaceId>:<containerId>` prefix:
    - Inventories and containers are also treated as Class A: authoritative state and versions live in PostgreSQL, and cache entries must be invalidated via events or version checks when items move. Namespace-backed durable holders use the namespace-qualified prefix above; explicitly S3 room-ground containers use their complete instance-qualified cache scope.
    - Event-based invalidation is driven by Entity Management’s own domain events (inventory changed, item moved, container destroyed); listeners delete or refresh the affected complete namespace-qualified `inventory:<tenantId>:<playableStateNamespaceId>:<containerId>` key (or the complete instance scope for an S3 holder), with `inventory:*` used only as a bounded catalog wildcard.
    - Implementations must document which APIs expose the version/`lastModified` fields used for these caches and keep them aligned with the central `inventory:*` entry in `system-architecture-redis-cache.md` and the reset matrix in `system-architecture-redis-reset-and-recovery.md`.
  - Cache metrics for `character-cache:*` and `inventory:*` should follow the recommendations in `system-architecture-redis-cache.md` so hit/miss behavior and key counts are observable.
  - Tests covering these caches are expected to exercise the Class A scenarios described in `system-architecture-redis-cache.md` (miss -> populate -> hit, version mismatch and event-driven invalidation, and behavior after a cache reset).
- Any change to Redis usage in this service should be reviewed against the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) to confirm prefix registration, role selection, slotting, and observability updates.

### Version Sources for Entity Caches

Entity Management is the invalidator of record for namespace-qualified `character-cache:*` and `inventory:*` entries:

- Authoritative versions or `lastModified` values for characters and containers are stored alongside the corresponding aggregates in PostgreSQL and surfaced via Entity Management’s gRPC APIs.
- Cache payloads for `character-cache:<tenantId>:<playableStateNamespaceId>:<characterId>` and `inventory:<tenantId>:<playableStateNamespaceId>:<containerId>` must embed those same version fields and complete namespace scope so readers can compare cached vs authoritative versions before reuse. Instance-scoped S3 cache entries must instead carry their complete instance scope.
- When schema or API fields that act as the version for these aggregates change, this section and the central cache catalog (`system-architecture-redis-cache.md`) must be updated together so reviewers can see exactly which columns/fields drive Class A cache correctness.

Testing expectations for these caches follow the Class A guidance in `system-architecture-redis-cache.md`: unit/integration tests should cover version mismatches, event-driven invalidation, and repopulation after a Redis reset.

If you change Redis usage for this service, you must read and apply:

- [Redis Architecture](../../system-architecture-redis.md)
- [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
- [FireMUD Redis Lua Patterns](../../system-architecture-redis-lua-patterns.md)
- [Redis Operations & Migrations](../../system-architecture-redis-operations.md)
