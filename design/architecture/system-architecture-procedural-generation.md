# FireMUD System Architecture: Procedural Generation

This document outlines how FireMUD supports procedural generation of both dungeon-style and overworld-style layouts. These generators can be invoked during world creation or dynamically at runtime to produce rooms, exits, biomes, and terrain features. For long-lived overworld or static areas, generated layouts are typically treated as structural scaffolding that designers or LLM-assisted tools can refine with names, descriptions, and quests. For short-lived dungeon instances generated at runtime, the layouts are usually consumed as-is without additional authoring.

Implemented generators include `SimpleDungeonGenerator` and `OverworldMapGenerator`.

Procedural generation allows games to quickly bootstrap playable areas, spawn instanced content, or fully generate open worlds without requiring hand-authored maps.

---

## Use Cases

- 🏗️ **World Bootstrapping** – Initialize a new world map without manual design.
- 🌀 **Dungeon Instances** – Generate instanced interiors on demand (e.g. for quests).
- 🧱 **Design Templates** – Offer scaffolds for designers to expand on.
- 🔁 **Replayable Zones** – Create consistent layouts from the same seed across sessions.

Generation flows fall into two categories:

- **Design-time/template generation** – invoked from Game Design workflows to produce
  versioned world scaffolding that is saved into template tables keyed by
  `(tenantId, versionId)` and later published like any other design asset.
- **Runtime/instance generation** – invoked from the Temporal world-lifecycle workflow or tick-driven
  commands to create per-instance layouts keyed by `(tenantId, gameInstanceId)`; these
  flows never modify template tables for Published versions.

All generator invocations are explicitly **mode-aware**. Callers must specify a `generationMode` value:

- `DESIGN_TEMPLATE` – used only by Game Design Service design-time workflows to populate or reshape template graphs keyed by `(tenantId, versionId)` on Draft versions.
- `RUNTIME_INSTANCE` – used by World Management and Game Session for world creation and runtime instancing to create instance records keyed by `(tenantId, gameInstanceId)`; this mode is not permitted to write any template rows regardless of version state.

World Management enforces this boundary: any attempt to invoke a generator in `RUNTIME_INSTANCE` mode that would modify template tables, or to write template rows for a Published version in `DESIGN_TEMPLATE` mode, is rejected with a hard validation error. Template writes must always originate from Game Design Service design-time APIs targeting Draft versions.

---

## Generator Types

FireMUD supports the following generator types, and additional strategies can be plugged in through the registry:

### 1. `SimpleDungeonGenerator`

Creates compact room graphs with bidirectional exits — ideal for dungeons, interiors, or short instances.

#### Algorithm

1. **Seeded Initialization**  
   Select a random seed to ensure repeatable layout.

2. **Create Starting Room**  
   Designate a root room.

3. **Iterative Expansion**  
   Until room count is met:
   - Choose a random existing room.
   - Attempt to attach a new room via an available N/E/S/W exit.
   - Limit exits per room to 4 to ensure simplicity.

4. **Output Shape**  
   Produces a flat graph of interconnected rooms with optional tags (`start`, `corridor`, etc.).

> 🔗 Ideal for quest dungeons, temples, abandoned mines, etc.

Procedural generators are invoked by the World Management Service, which calls pure `Generator` implementations as library functions using a seed, parameters, and world context. The generators return an abstract room/region graph that World Management validates and persists as either versioned **template** records or per-instance **runtime** records depending on the calling context. Automation & Scripting must not execute generators or return topology graphs for persistence.

- When invoked from Game Design workflows for **design templates**, results are
  persisted as template rows keyed by `(tenantId, versionId)` and become part of
  the published topology for that version.
- When invoked from the world-lifecycle workflow or tick-driven commands for **runtime
  instances**, results are persisted as instance rows keyed by
  `(tenantId, gameInstanceId)` and refer back to the chosen `versionId`; template
  rows remain unchanged.

For persistent instance layouts, the invariant is that all `*_instance` rows must be **replayable** from:

- the published templates for the associated `versionId`, plus
- the stored generator metadata for each generation run, including `generationMode`, `seed`, `generatorType`, and an immutable `configSnapshot` with an explicit `schemaVersion`.

Runtime-only dungeons or short-lived instances may be treated as ephemeral data that exists only for the lifetime of a specific `gameInstanceId` and is never shared across instances or versions. Long-lived overworld-style instance layouts that need to survive restarts must persist enough generator metadata to satisfy the replayable-from-templates invariant.

Optional post-generation population hooks can then run to seed NPCs, spawns, or environmental details appropriate to the template or instance context.

### Deterministic Replay Contract for Design-Time Generation

Design-time generation is part of publish safety and reconciliation, so retries must not depend on mutable defaults or the currently deployed generator binary alone.

For each design-time generation run, World Management must persist a durable generation artifact that includes:

- `generationRunId` and stable `generationRequestId`
- `tenantId`, `versionId`, `generationMode`
- `generatorType` and `generatorImplementationVersion` (or equivalent immutable build identifier)
- canonicalized `configSnapshot` including explicit `schemaVersion`
- seed and any derived deterministic inputs
- `outputDigest` computed from a canonical serialized topology output

These design-time generation artifacts are replay/reconciliation provenance, not published topology rows themselves:

- The canonical publish contract is still the finalized version-scoped template rows keyed by `(tenantId, versionId)`.
- Design-time generation artifact tables are excluded from `GetDraftDesignDigest` unless a future doc revision explicitly promotes named semantic fields into the digest manifest.
- Retention and migration rules for these artifact rows must preserve deterministic replay for non-Retired versions, but publish gating compares only the finalized template graph plus other documented semantic inputs.

Reconciliation behavior:

- Replaying a previously applied design revision must either:
  - reuse the persisted staged/finalized output artifact directly, or
  - rerun generation and verify that the regenerated output matches the recorded `outputDigest`.
- If regenerated output does not match the recorded digest, reconciliation must fail fast and mark the version `OUT_OF_SYNC` rather than silently accepting a drifted topology.
- The digest for publish gating must cover the finalized template rows produced by this artifact, so replay and publish checks converge on the same canonical state.

Generation revisions are explicit and scope-bound:

- A design-time generator invocation is a first-class revision type recorded in Game Design history, not an implicit side effect outside commit/replay ordering.
- Every generation revision must declare its target replacement scope before execution. Initial-slice allowed scopes are:
  - one or more newly created empty template containers; or
  - an explicitly named world subtree rooted at a `regionTemplateId` or `zoneTemplateId`.
- Every generation-addressable scope has a monotonic Draft scope epoch keyed by `(tenantId, versionId, scopeType, scopeId)`. Generation revisions and manual topology/content edits inside that scope must check and advance the same epoch so generated output cannot silently overwrite newer manual edits.
- The revision must also declare its replacement policy:
  - `REPLACE_SCOPE` means the generated output fully replaces the previously authored topology inside that declared target scope; rows outside the scope are untouched.
  - `SEED_APPEND_ONLY` means generation may add new rows inside the target scope but may not rewrite or delete previously existing manually authored rows.
- `REPLACE_SCOPE` must carry `expectedDraftScopeRevisionEpoch` for the target scope and fail as `DRAFT_WRITE_CONFLICT` if the scope has advanced.
- `SEED_APPEND_ONLY` must carry the same expected scope epoch and fail as `OUT_OF_SYNC` or a more specific generation conflict if replay would require rewriting or deleting rows already present in the scope.
- Reconciliation must replay `generate -> subsequent manual revisions` in original commit/revision order. It must never rerun generation over a scope that has later manual edits unless the recorded generation revision itself declared `REPLACE_SCOPE` for that same scope.
- Manual edits applied after a generation revision are canonical authored state. A later replay that would erase those edits without an explicit replacement revision is invalid and must fail the Draft as `OUT_OF_SYNC`.

Illustrative revision examples:

```json
{
  "revisionType": "GENERATE_WORLD_SUBTREE",
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "versionId": "22222222-2222-4222-8222-222222222222",
  "revisionId": "r-gen-001",
  "targetScope": {
    "scopeType": "ZONE_SUBTREE",
    "zoneTemplateId": "zoneTemplateId:starter-caves"
  },
  "replacementPolicy": "REPLACE_SCOPE",
  "generatorType": "SimpleDungeonGenerator",
  "generationRequestId": "genreq-t1-v42-starter-caves-r1"
}
```

Resulting replay semantics:

- rerunning `r-gen-001` may replace the topology inside `zoneTemplateId:starter-caves`;
- later manual revisions that edit rooms in that zone remain authoritative until another explicit `REPLACE_SCOPE` revision targets the same zone.

```json
{
  "revisionType": "GENERATE_WORLD_SUBTREE",
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "versionId": "22222222-2222-4222-8222-222222222222",
  "revisionId": "r-gen-002",
  "targetScope": {
    "scopeType": "NEW_EMPTY_REGION",
    "regionTemplateId": "regionTemplateId:northern-wilds"
  },
  "replacementPolicy": "SEED_APPEND_ONLY",
  "generatorType": "OverworldMapGenerator",
  "generationRequestId": "genreq-t1-v42-northern-wilds-r1"
}
```

Resulting replay semantics:

- `r-gen-002` may add generated rows into the declared empty region;
- if later manual revisions rename rooms or adjust exits in that region, replay must preserve those later edits rather than regenerating over them;
- if regeneration under `SEED_APPEND_ONLY` would require deleting or rewriting those later authored rows, Draft convergence fails as `OUT_OF_SYNC`.

This rule makes generated scaffolding safe to refine manually while preserving one deterministic answer to “what does replay regenerate versus preserve?”

Concrete replay example:

- Revision `r-gen-002` creates an empty-region scaffold for `regionTemplateId:northern-wilds` under `SEED_APPEND_ONLY`.
- A later manual revision renames room `nw-entrance` to `Pine Gate` and adds a custom exit to `nw-watch-post`.
- Replay must reproduce the generator-authored scaffold first, then replay the manual rename and exit addition in commit/revision order.
- If a later generator replay for `r-gen-002` would need to delete `Pine Gate`, rewrite the authored exit, or otherwise erase those later manual edits without an explicit replacement revision, Draft convergence must fail as `OUT_OF_SYNC`.

---

### 2. `OverworldMapGenerator`

Generates biome-aware terrain maps with elevation, water features, forest density, and region partitioning. Room creation is configurable: either generate **sparse rooms** only at points of interest (POIs), or generate a **full grid of rooms** based on the terrain data.

#### Generation Pipeline:

| Step | Purpose | Common Techniques |
| --- | --- | --- |
| **Elevation Map** | Define height (mountains, valleys) | `Perlin Noise`, `Diamond-Square` |
| **Moisture Map** | Define biome type (desert, swamp, forest) | Gradient sampling, additional noise layer |
| **Biome Assignment** | Use height + moisture to classify terrain | Threshold tables or biome rulesets |
| **Region Partitioning** | Divide into zones/factions | `Voronoi`, seeded points + expansion |
| **River/Lake Simulation** | Carve out natural water features | Flow fields, downhill tracing |
| **Forest/Cave Generation** | Place dense blobs of trees or underground | Cellular automata |
| **Feature Placement** | Place towns, dungeons, landmarks | `Poisson Disk Sampling`, seeded rules |
| **Connectivity Graph** | Generate roads, rivers, and path exits | A*, flow maps, elevation-aware routing |
| **Room Graph Export** | Convert terrain grid into room data | Either sparse (POIs and path nodes only) or full (1:1 room per map cell) |

> The room generation mode (sparse vs full) is selectable per generation request, depending on the game’s desired level of detail and exploration density.

---

## Output and Metadata (Common)

All generators emit a normalized structure:

| Field | Description |
| --- | --- |
| `roomKey` | Unique identifier within the generator output graph (not a persisted template/instance id) |
| `coordinates` | Grid location (used for spatial logic and editing) |
| `exitMap` | Map of direction → `roomKey` |
| `tags` | Optional labels like `"start"`, `"town"`, etc. |
| `biome` | Biome or terrain type (if applicable) |
| `elevation` | Numeric terrain height (used for visuals or logic) |
| `regionKey` | Optional grouping key for partitioned maps (not a persisted template/instance id) |

`spacingMultiplier` is stored on the containing region (World Management) and can globally scale movement speed across the map. In sparse layouts Game Logic uses room coordinates and this `spacingMultiplier` to derive movement/travel cost, so nearby rooms are quick to traverse while large gaps produce longer travel times.

In **full-grid mode**, every terrain tile becomes a room.
In **sparse mode**, only selected POIs and waypoints are emitted, and the distance between them determines travel cost.

World Management assigns canonical persisted identifiers when saving generator outputs:

- Design-time/template generation persists `roomTemplateId` values keyed by `(tenantId, versionId)`.
- Runtime/instance generation persists `roomInstanceId` values keyed by `(tenantId, gameInstanceId)`.

Generator outputs must not embed or assume these persisted identifiers; they are assigned at persistence time by World Management.

---

## Integration Guidelines

The following rules align generators with the core runtime and tooling:

1. **Solo Tick Scheduling** – Post-activation or dynamic runtime generation is queued like any other command but includes `requiresSoloTick: true`. The Game Session Service executes it in an isolated tick and, if extra runtime is required, must use the canonical `solo_tick_budget_ms` mode defined by the tick invariants rather than an ad-hoc extended budget.
2. **Heavy Post‑Gen Population** – Population scripts may declare `requiresSoloTick: true`. The Game Session Service schedules these in dedicated ticks to avoid fairness regressions and may only exceed the normal budget when `solo_tick_budget_ms` is enabled for that deployment/profile.
3. **Seed & Metadata Persistence** – All generation requests include a seed. **World Management Service** persists `seed`, `generatorType`, and raw params alongside region/room records. For design-time generation this metadata is stored on template rows keyed by `(tenantId, versionId)`; for runtime/instance generation it is stored on instance rows keyed by `(tenantId, gameInstanceId)`.
4. **Tenant Scoping** – All generation inputs/outputs are tenant‑scoped. For publish-affecting or activation-time generation, the effective inputs must come from version-scoped design rows and the frozen `generationConfigRevision`, not mutable tenant feature flags or operational defaults. Runtime-only operational knobs may affect scheduling or non-semantic execution details, but they must not change persisted topology semantics.
5. **Sparse Traversal Rules** – Exit costs between sparse rooms are derived from their coordinate distance. **Game Logic** uses region `spacingMultiplier` to scale the overall pace if needed.
6. **Post-generation Population** – After rooms are created and persisted, population work follows different rules by mode. In design-time/template generation, post-generation population may create only declarative World-owned spawn/population binding rows under `(tenantId, versionId)`; Automation & Scripting must not persist template rows, spawn bindings, or live entities as a side effect of a design-time generation revision. In runtime/instance generation, Automation & Scripting may emit runtime commands through the canonical tick/workflow handoff after topology is visible. Those commands act on `RoomInstanceRef` and runtime entity state; they do not directly mutate world topology.

   Failure and retry semantics:

   - Population is treated as a **retryable, idempotent** follow-up phase, not as part of topology persistence.
   - Launch-time topology generation/persistence for instance creation is a pre-activation workflow owned by World Management (Class A rollback semantics in `system-architecture-transactions.md`) and is not routed through Game Session ticks. Post-activation population and subsequent dynamic generation follow Class B retry-until-convergence semantics, with runtime generation using the `requiresSoloTick` command path.
   - Topology persistence (template or instance rows) must complete atomically in World Management before population is admitted.
   - Runtime population commands must carry the same canonical identity used for tick idempotency (`EffectId`) plus `RoomInstanceRef` so downstream services can safely no-op on replays.
   - Design-time binding materialization must instead carry `tenantId`, `versionId`, the target template identifiers, `commitId`, `revisionId`, and `expectedDraftScopeRevisionEpoch`; duplicate revision replay must no-op through the same Draft write idempotency rules as other design-time mutations.
   - If population partially succeeds (for example some spawns created in Entity Management but later commands fail), the system retries until convergence using the original identities. It must not attempt to “undo” already-persisted topology or “roll back” created entities by issuing compensating deletes from within the tick loop.
   - The only supported destructive rollback is deleting an entire **ephemeral** instance as a unit (for example a short-lived dungeon instance), after verifying it is no longer referenced by active sessions.
   - Initial-slice scope is narrower: instance-scoped population schedules and follow-up population commands are required only for primary world creation of the launched `gameInstanceId`. Portal-driven or later dynamic instancing may adopt the same contract in future slices but is not required by this document for first delivery.
7. **Validation and Errors** – World Management validates generation requests, validates generator outputs, and guarantees **no partial persistence** for the affected template or instance scope.

   Persistence must use a staged/finalize model so large graphs can be written safely without relying on oversized single transactions:

   - Each generation run is assigned a `generationRunId` (scoped to the caller’s target, for example `(tenantId, versionId)` or `(tenantId, gameInstanceId)`).
   - Callers must supply (or World Management must derive deterministically) a stable `generationRequestId` so retries of “the same request” map to the same `generationRunId` and become replay-safe.
   - `generationRequestId` must be derived from business identity rather than transient execution identity (for example hash of `tenantId`, target scope key, generation step name, and canonicalized generator config). Retries through a new Temporal run or synchronous retry must reuse the same `generationRequestId`.
   - World Management must enforce a uniqueness constraint on `(tenantId, targetScopeKey, generationRequestId)` so duplicate requests converge to one run.
   - World Management must enforce single-writer semantics per target scope (for example via a lock keyed by `(tenantId, versionId)` for design-time, or `(tenantId, gameInstanceId)` for runtime) so two concurrent runs cannot race to finalize into the same template/instance scope.
   - World Management writes all generated rooms/exits/metadata into staging rows keyed by `(tenantId, generationRunId)` and records an immutable config snapshot (`seed`, `generatorType`, `schemaVersion`, and serialized parameters).
   - A single finalize transaction atomically:
     - Marks the staged run as committed (or swaps it into the active template/instance scope), and
     - Makes the generated topology visible to readers.
   - On failure World Management returns a `GenerationErrorDetail` and guarantees the target scope remains unchanged (staged rows may be left for diagnostics or garbage-collected by `generationRunId`).
   - World Management must document and implement a garbage-collection policy for abandoned staging rows keyed by `(tenantId, generationRunId)` (for example time-based cleanup for `FAILED`/`ABORTED` runs, while retaining a short diagnostic window).
8. **Editor Overlays** – Generators emit coordinates and optional map layers so the Game Editor can display a preview or dry-run JSON output.
9. **Pluggable Interface** – Generators implement the `Generator` interface and are discovered via the `GeneratorRegistry` in the World Management Service. Discovery uses Spring bean scanning, and additional generators may be provided by shared libraries or service-local modules.

Initial-slice delivery expectation:

- Primary world creation does not have to exercise every runtime-generation capability described in this document.
- For the first implementation slice, runtime generation and instance-scoped population scheduling are required only when the published launch descriptor and version-scoped design inputs explicitly call for them.
- If a launched version does not require those capabilities, the initial slice may omit those runtime stages without violating the persistence contract, provided the world-creation workflow still records deterministic “not required” outcomes under the same launch identity.
- Those recorded outcomes should use a stable stage result such as `SKIPPED_NOT_REQUIRED` so operator tooling and replay/debug flows can distinguish “not part of this launch” from “step failed before execution”.
- Operator-facing workflow or admin diagnostics should surface that same recorded result without reinterpretation so the service-local workflow record and the control-plane view use identical outcome vocabulary.

Procedural-generation configuration is split into two classes:

- **Version-scoped design inputs** that affect published topology or published generation semantics. These are authored through Game Design workflows, stored in World Management-owned versioned tables, and participate in digest/publish contracts.
- **Operational runtime defaults** that affect only non-publish runtime behavior. These are owned by World Management operations surfaces and must never change the effective inputs of an already-authored Draft or Published version.

Semantic input boundary:

- If changing an input can alter generated rooms, exits, tags, descriptions, coordinates, region partitioning, spawn/materialization provenance, or any other persisted topology semantics, that input is version-scoped design data and must be frozen into `generationConfigRevision`.
- If an input is not frozen into `generationConfigRevision`, implementations must treat it as non-semantic. Such inputs are limited to execution concerns like worker routing, shard selection, or time budgets and must not affect the persisted graph.

Operational provenance requirement for `generation_rule` updates:

- Each update to publish-affecting generation inputs must persist audit fields (`changedBy`, `changedAt`, `changeReason`, `changeDigest`) and originating Game Design commit/revision identifiers.
- If a change alters effective Draft generation inputs for a publish target, design synchronization must mark that target `OUT_OF_SYNC` until digest reconciliation re-establishes convergence.
- Operational runtime-default updates must not alter the effective design-time graph for any `(tenantId, versionId)`.

For activation/runtime determinism, publish must freeze a generation config identity per version:

- On `PublishVersion`, the system records a `generationConfigRevision`/hash for that `(tenantId, versionId)` from the version-scoped design inputs committed for that version.
- World creation for a published version must resolve and use the frozen generation config identity; if it cannot be resolved, activation fails closed.
- Editing tenant defaults after publish must not change generation inputs for already published versions unless a new version is published (or an explicit version-scoped override migration is executed and republished).
- Any implementation behavior that depends on local default generator parameters, mutable tenant feature flags, or deployment-specific configuration must either:
  - be proven non-semantic and excluded from persisted topology output, or
  - be moved into version-scoped design data so it contributes to `generationConfigRevision`.

Each individual generation run persists an **immutable snapshot** of the configuration it actually used:

- World creation and runtime generation calls snapshot the effective parameters
  they use (including generator type, seed, and a serialized config blob
  carrying an explicit `schemaVersion`) alongside the generated regions and
  rooms so that operators can later reconstruct the inputs used for a particular
  world or instance, even if live rules have changed since then.
- Instance metadata must include the frozen `generationConfigRevision`/hash used for the run so rollback/debug tooling can verify deterministic inputs.
- Installations that need different tuning per version must store that tuning as version-scoped design data keyed by `(tenantId, versionId)`. If legacy override tables exist, they must be treated as transitional readers only and retired in favor of version-scoped authoring. The Version-Aware Migration Checklist in `system-architecture-database-migrations.md` applies equally to these version-scoped rows.

When the shape of generator configuration evolves, schema changes must follow the version-aware migration rules in `system-architecture-database-migrations.md`. New fields should be added under a new `schemaVersion`, and World Management and related services must continue to understand existing non-Retired `schemaVersion` values until the corresponding versions have been retired or explicitly migrated.

---

## Service Responsibilities

### World Management Service

- Owns invocation of generators as pure functions and persists generated rooms/biomes/regions; assigns canonical `roomTemplateId` / `roomInstanceId` values at persistence time
- Persists generator metadata (`seed`, `generatorType`, and an immutable config
  snapshot with `schemaVersion`) and editor overlays, including a snapshot of
  the effective procedural rule configuration used for each generation run
- Provides read APIs for geometry, overlays, and region metadata

### Automation & Scripting Service

- Provides optional post-generation population scripts (for example, spawning NPCs or loot) that can be invoked by World Management based on tags, biome, and difficulty
- Integrates procedural generation results with the broader scripting and automation framework where needed
- Does not execute or return world-topology graphs for persistence; it produces commands and bindings that act on already-persisted world instance state

### Game Session Service

- Requests runtime instancing (portals/quests), schedules **solo ticks** for generation
- Coordinates Redis tick isolation and invokes World Management to run generation within isolated ticks

### Game Logic Service (Movement/Travel)

- **Computes movement/travel costs** using World geometry (`coordinates`, region `spacingMultiplier`, biome/elevation rules)

---

## Related Documentation

- [Automation & Scripting Service](./microservices/automation-scripting-service/README.md)
- [Game Design Service](./microservices/game-design-service/README.md)
- [LLM-Assisted Content Authoring](./system-architecture-llm-content-tools.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [World Management Service](./microservices/world-management-service/README.md)
