# FireMUD System Architecture: Procedural Generation

This document outlines how FireMUD supports procedural generation of both dungeon-style and overworld-style layouts. These generators can be invoked during world creation or dynamically at runtime to produce rooms, exits, biomes, and terrain features. For long-lived overworld or static areas, generated layouts are typically treated as structural scaffolding that designers or LLM-assisted tools can refine with names, descriptions, and quests. For short-lived dungeon instances generated at runtime, the layouts are usually consumed as-is without additional authoring.

The target design includes `SimpleDungeonGenerator` and `OverworldMapGenerator`. The current implementation has a `SimpleDungeonGenerator` and registry in Automation & Scripting, contrary to the target ownership; it does not yet implement the World-owned engine, typed generation ingress, or `OverworldMapGenerator` described here.

Procedural generation allows games to quickly bootstrap playable areas, spawn instanced content, or fully generate open worlds without requiring hand-authored maps.

---

## Use Cases

- 🏗️ **World Bootstrapping** – Initialize a new world map without manual design.
- 🌀 **Dungeon Instances** – Generate instanced interiors on demand (e.g. for quests).
- 🧱 **Design Templates** – Offer scaffolds for designers to expand on.
- 🔁 **Replayable Zones** – Create consistent layouts from the same seed across sessions.

Generation flows share one pure generator engine owned by World Management but use two separate typed ingress contracts:

- **Design-time/template generation** – invoked only from Game Design workflows to produce
  versioned world scaffolding that is saved into template tables keyed by
  `(tenantId, versionId)` and later published like any other design asset.
- **Runtime/instance generation** – invoked from the Temporal world-lifecycle workflow or tick-driven
  commands to create per-instance layouts keyed by `(tenantId, gameInstanceId)`; these
  flows never modify template tables for Published versions.

The authenticated endpoint and its typed target union determine the namespace and persistence semantics:

- the design ingress accepts a Draft target keyed by `(tenantId, versionId, DraftScopeTarget)` and only Game Design may orchestrate it;
- the runtime ingress accepts an instance target keyed by `(tenantId, gameInstanceId, InstanceScopeTarget)` and only approved world-lifecycle or gameplay command paths may invoke it.

Callers do not supply a free `generationMode` as an authority selector. World Management derives the mode from the authenticated ingress and target, rejects cross-namespace combinations, validates generator output, and persists it. Design ingress may write only Draft template rows; Published rows are immutable. Runtime ingress may write only instance rows. See [ADR 0113](decisions/adr-0113-separate-generation-ingress-with-one-world-owned-engine.md).

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

Procedural generators are invoked by the World Management Service, which calls its one pure generator engine using a seed, parameters, and world context. The generators return an abstract room/region graph that World Management validates and persists as either versioned **template** records or per-instance **runtime** records according to the typed ingress. Game Design owns Draft generation intent and revision orchestration but not topology persistence. Automation & Scripting must not execute generators, return topology graphs for persistence, or persist topology.

- When invoked from Game Design workflows for **design templates**, results are
  persisted as template rows keyed by `(tenantId, versionId)` and become part of
  the published topology for that version.
- When invoked from the world-lifecycle workflow or tick-driven commands for **runtime
  instances**, results are persisted as instance rows keyed by
  `(tenantId, gameInstanceId)` and refer back to the chosen `versionId`; template
  rows remain unchanged.

Persistent instance layouts are authoritative stored topology. Restarts and disaster recovery restore those rows, retained finalized artifacts, or backups; they do not depend on indefinite re-execution of the historical generator. Generator metadata remains provenance and request-retry evidence rather than a seed-only reconstruction guarantee.

Runtime-only dungeons or short-lived instances may be treated as ephemeral data that exists only for the lifetime of a specific `gameInstanceId` and is never shared across instances or versions. If ephemeral topology is lost, its lifecycle may permit discarding it and starting a new generation request under the current permitted generator policy. Long-lived overworld-style layouts must persist their actual topology.

Optional post-generation population hooks can then run to seed NPCs, spawns, or environmental details appropriate to the template or instance context.

### Request-Bounded Replay and Explicit Regeneration

Design-time generation is part of publish safety and reconciliation, so retries of one admitted request must not depend on mutable defaults or whichever generator binary happens to be current. This compatibility obligation is bounded to that request; FireMUD does not retain every historical generator implementation indefinitely.

At admission, World Management persists a durable generation record that includes:

- `generationRunId` and stable `generationRequestId`
- `tenantId`, `versionId`, `generationMode`
- `generatorType` and `generatorImplementationVersion` (or equivalent immutable build identifier)
- canonicalized `configSnapshot` including explicit `schemaVersion`
- seed and any derived deterministic inputs
- the identity of recorded or staged output, including an `outputDigest` computed from its canonical serialized topology

The resolved implementation and inputs are immutable for that request, including across rolling nodes. These records and artifacts are retry/reconciliation evidence, not published topology rows themselves:

- The canonical publish contract is still the finalized version-scoped template rows keyed by `(tenantId, versionId)`.
- Design-time generation artifact tables are excluded from `GetDraftDesignDigest` unless a future doc revision explicitly promotes named semantic fields into the digest manifest.
- Staged output must remain available for the active request's retry lifecycle. Provenance may outlive the executable implementation and does not promise seed-only reconstruction.

Reconciliation behavior:

- Retrying the same in-flight request reuses its recorded or staged output. If that output cannot be reused and the admitted implementation is unavailable, the request fails closed or is explicitly abandoned; it never substitutes a newer implementation under the same identity.
- Once finalized, committed template topology is authoritative and recovery restores committed rows, immutable releases, retained finalized artifacts, or backups rather than re-executing the generator.
- Intentional regeneration is a new request and authored revision. It may select the newest generator or model permitted by explicit game or operator policy and must obey the declared scope, epoch, and replacement rules.
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
- `SEED_APPEND_ONLY` is the safe default wherever the requested generation can be expressed without rewriting or deletion.
- Before `REPLACE_SCOPE` can be accepted, Game Design presents an exact destructive plan identifying creates, retained objects, replacements, deletions, affected references, identifier mappings, and blockers. The approved request carries a canonical plan digest bound to the exact generation inputs and current `expectedDraftScopeRevisionEpoch`.
- `REPLACE_SCOPE` fails as `DRAFT_WRITE_CONFLICT` and requires a new preview when the scope epoch, plan inputs, or relevant reference facts have changed.
- `SEED_APPEND_ONLY` must carry the same expected scope epoch and fail as `OUT_OF_SYNC` or a more specific generation conflict if replay would require rewriting or deleting rows already present in the scope.
- Reconciliation must replay `generate -> subsequent manual revisions` in original commit/revision order. Replaying the same historical generation revision never turns it into permission to erase later edits; destructive regeneration is a new, explicitly previewed `REPLACE_SCOPE` revision.
- References crossing the replacement boundary must remain valid, map through an explicit typed mapping, or block replacement. Stable persisted identifiers remain only for the same logical objects; semantic replacements, splits, merges, and re-scopes require explicit durable mappings.
- No generic old/local/new merge is implied. Ambiguous local changes or semantic replacements require explicit creator resolution.

Illustrative revision examples:

```json
{
  "revisionType": "GENERATE_WORLD_SUBTREE",
  "tenantId": "t1",
  "versionId": "v42",
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
  "tenantId": "t1",
  "versionId": "v42",
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

Generates biome-aware terrain maps with elevation, water features, forest density, and region partitioning. Topology creation is configurable: either generate a sparse graph containing selected points of interest and path nodes, or generate a bounded full grid in which every valid cell is an authoritative gameplay location.

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
| **Topology Export** | Convert terrain into authoritative location data | Either `SPARSE_GRAPH` nodes and edges or a bounded `FULL_GRID` eager/chunked topology |

The topology mode is a version-scoped semantic design input selected for the generation request:

- `SPARSE_GRAPH` emits selected locations such as POIs and waypoints plus their declared connectivity.
- `FULL_GRID` defines a bounded lattice in which every valid cell is a stable authoritative location. The design declares supported adjacency directions, bounds, impassable terrain, and other typed traversal rules.

`FULL_GRID` does not require one database row or stored exit row per cell, nor does it require continuous simulation of inactive cells. World Management must expose the same authoritative cell facts, stable identity, adjacency, movement, targeting, occupancy, and snapshot behavior regardless of whether the physical representation is eager rows or an immutable chunked topology with durable runtime deltas.

---

## Output and Metadata (Common)

Generators emit a normalized sparse graph or a bounded grid/chunk topology. Their logical locations expose the following common semantic fields even when a large grid stores them in immutable chunks rather than one row per cell:

| Field | Description |
| --- | --- |
| `roomKey` / `cellKey` | Stable logical identifier within the generated topology; World Management resolves the canonical persisted or virtualized template/runtime identity |
| `coordinates` | Grid location (used for spatial logic and editing) |
| `exitMap` | Map of direction → `roomKey` |
| `tags` | Optional labels like `"start"`, `"town"`, etc. |
| `biome` | Biome or terrain type (if applicable) |
| `elevation` | Numeric terrain height (used for visuals or logic) |
| `regionKey` | Optional grouping key for partitioned maps (not a persisted template/instance id) |

Topology density and movement policy are independent versioned choices. A sparse game may make every declared exit one uniform movement, while another may use geometric distance. A full grid may likewise use uniform steps, explicit costs, or terrain/elevation-sensitive movement. `spacingMultiplier` is stored on the containing region and participates only when the selected typed movement policy declares it. Sparse topology does not implicitly make travel slower, and full-grid topology does not implicitly make travel uniform.

In `FULL_GRID`, every valid cell is logically addressable and traversable according to the declared adjacency and terrain rules. Walls, invalid cells, impassable terrain, and lattice bounds may still block a direction. In `SPARSE_GRAPH`, only selected nodes and their declared edges are locations. Neither choice dictates movement cost by itself.

World Management exclusively assigns or resolves canonical identifiers when finalizing and exposing generator output:

- Design-time/template locations resolve to stable `roomTemplateId` values keyed by `(tenantId, versionId)`.
- Runtime locations exposed to gameplay resolve to stable `roomInstanceId` values keyed by `(tenantId, gameInstanceId)`.

Generator outputs must not assume database row identity. World Management may eagerly persist bounded graphs or resolve a cell from an immutable chunked base plus durable instance state, but the same logical cell must resolve to the same authoritative identity for its required lifetime.

### Large Full-Grid Representation

Physical topology representation is opaque behind World Management:

- Sparse and bounded moderate graphs may use eager template, instance, and exit rows within enforced and tested limits.
- Before large full-grid scale is claimed, generation must produce an immutable digest-attested chunk topology or equivalent bounded representation. A validated root manifest identifies the complete lattice and immutable chunks, and one short finalize selects that root atomically so readers never observe a partial grid.
- Runtime reads compose the immutable base cell with durable instance-scoped deltas for visited, occupied, changed, timed, or otherwise mutable locations. Caches and lazy materialization remain derived projections rather than authority.

Loading or materializing an already committed cell is not regeneration. Recovery restores the stored topology artifact and durable runtime deltas instead of re-running the historical generator from seed. A world that intentionally creates previously unfixed chunks later is an unbounded or expanding generation mode and requires a separate contract; it is not the bounded fixed `FULL_GRID` mode.

---

## Integration Guidelines

The following rules align generators with the core runtime and tooling:

1. **Solo Tick Scheduling** – Post-activation or dynamic runtime generation is queued like any other command but includes `requiresSoloTick: true`. The Game Session Service executes it in an isolated tick and, if extra runtime is required, must use the canonical `solo_tick_budget_ms` mode defined by the tick invariants rather than an ad-hoc extended budget.
2. **Heavy Post‑Gen Population** – Population scripts may declare `requiresSoloTick: true`. The Game Session Service schedules these in dedicated ticks to avoid fairness regressions and may only exceed the normal budget when `solo_tick_budget_ms` is enabled for that deployment/profile.
3. **Seed & Metadata Persistence** – All generation requests include a seed. **World Management Service** persists `seed`, `generatorType`, and raw params alongside region/room records. For design-time generation this metadata is stored on template rows keyed by `(tenantId, versionId)`; for runtime/instance generation it is stored on instance rows keyed by `(tenantId, gameInstanceId)`.
4. **Tenant Scoping** – All generation inputs/outputs are tenant‑scoped. For publish-affecting or activation-time generation, the effective inputs must come from version-scoped design rows and the frozen `generationConfigRevision`, not mutable tenant feature flags or operational defaults. Runtime-only operational knobs may affect scheduling or non-semantic execution details, but they must not change persisted topology semantics.
5. **Topology and Traversal Policy** – Density and movement policy are separate version-scoped inputs. World Management resolves authoritative locations, geometry, adjacency, and occupancy for both `SPARSE_GRAPH` and `FULL_GRID`; Game Logic applies the selected typed movement policy. Coordinate distance, terrain, elevation, explicit costs, and region `spacingMultiplier` affect cost only when that policy declares them.
6. **Post-generation Population** – After rooms are created and persisted, population work follows different rules by mode. In design-time/template generation, post-generation population may create only declarative World-owned spawn/population binding rows under `(tenantId, versionId)`; Automation & Scripting must not persist template rows, spawn bindings, or live entities as a side effect of a design-time generation revision. In runtime/instance generation, Automation & Scripting may emit runtime commands through the canonical tick/workflow handoff after topology is visible. Those commands act on `RoomInstanceRef` and runtime entity state; they do not directly mutate world topology.

   Failure and retry semantics:

   - Population is treated as a **retryable, idempotent** follow-up phase, not as part of topology persistence.
   - Launch-time topology generation/persistence for instance creation is a pre-activation workflow owned by World Management (Class A rollback semantics in `system-architecture-transactions.md`) and is not routed through Game Session ticks. Post-activation population and subsequent dynamic generation follow Class B retry-until-convergence semantics, with runtime generation using the `requiresSoloTick` command path.
   - Authoritative topology persistence or digest-attested root installation must complete atomically in World Management before population is admitted.
   - Runtime population commands must carry the same canonical identity used for tick idempotency (`EffectId`) plus `RoomInstanceRef` so downstream services can safely no-op on replays.
   - Design-time binding materialization must instead carry `tenantId`, `versionId`, the target template identifiers, `commitId`, `revisionId`, and `expectedDraftScopeRevisionEpoch`; duplicate revision replay must no-op through the same Draft write idempotency rules as other design-time mutations.
   - If population partially succeeds (for example some spawns created in Entity Management but later commands fail), retryable items retry until convergence using the original identities. A permanently invalid item reaches an explicit terminal failure with durable diagnostics rather than retrying forever.
   - Cleanup may remove only objects carrying explicit ownership by the failed generation run; it must never infer ownership from location or delete player-created, manually authored, or unrelated state. Whole-instance deletion is supported only for an explicitly **ephemeral** instance after verifying it is no longer referenced by active sessions.
   - Initial-slice scope is narrower: instance-scoped population schedules and follow-up population commands are required only for primary world creation of the launched `gameInstanceId`. Portal-driven or later dynamic instancing may adopt the same contract in future slices but is not required by this document for first delivery.
7. **Validation and Errors** – World Management validates generation requests, validates generator outputs, and guarantees **no partial persistence** for the affected template or instance scope.

   Persistence guarantees atomic reader visibility and replay-safe convergence through one of two bounded mechanisms:

   - Each generation run is assigned a `generationRunId` (scoped to the caller’s target, for example `(tenantId, versionId)` or `(tenantId, gameInstanceId)`).
   - Callers must supply (or World Management must derive deterministically) a stable `generationRequestId` so retries of “the same request” map to the same `generationRunId` and become replay-safe.
   - `generationRequestId` must be derived from business identity rather than transient execution identity (for example hash of `tenantId`, target scope key, generation step name, and canonicalized generator config). Retries through a new Temporal run or synchronous retry must reuse the same `generationRequestId`.
   - World Management must enforce a uniqueness constraint on `(tenantId, targetScopeKey, generationRequestId)` so duplicate requests converge to one run.
   - World Management must enforce single-writer semantics per target scope through the scope epoch/fence or equivalent storage-level compare-and-set, together with request uniqueness, so concurrent runs cannot both commit.
   - Generation and all graph, scope, count, byte-size, and digest validation complete before the visibility transaction. That transaction performs no generator execution or network calls.
   - An output within enforced and proved row and serialized-byte limits may write the complete result and idempotency outcome in one owner-local transaction. Readers see either the prior scope or the complete new scope.
   - Output above those limits, or output requiring chunked persistence, uses private staging keyed by `(tenantId, generationRunId)`. A short finalize transaction validates the request identity, scope fence, expected counts, and canonical digest before atomically installing or selecting the graph or immutable root chunk manifest.
   - The initial implementation may reject oversized output deterministically until the staged path exists. Callers may not bypass the bounded-transaction limits.
   - On failure World Management returns a `GenerationErrorDetail` and guarantees the target scope remains unchanged. When staging is used, World Management must define bounded diagnostic retention and garbage collection for abandoned rows.
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

- Owns invocation of generators as pure functions and persists or installs the authoritative generated topology; assigns or resolves canonical `roomTemplateId` / `roomInstanceId` values without exposing physical row or chunk identity
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

- **Computes movement/travel costs** from World-owned facts under the published typed movement policy; coordinates, explicit edge/cell cost, region `spacingMultiplier`, biome, and elevation participate only when that policy declares them

---

## Related Documentation

- [Automation & Scripting Service](./microservices/automation-scripting-service/README.md)
- [Game Design Service](./microservices/game-design-service/README.md)
- [LLM-Assisted Content Authoring](./system-architecture-llm-content-tools.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [World Management Service](./microservices/world-management-service/README.md)
