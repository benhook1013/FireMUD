# FireMUD System Architecture: Procedural Generation

This document outlines how FireMUD supports procedural generation of both dungeon-style and overworld-style layouts. These generators can be invoked during world creation or dynamically at runtime to produce rooms, exits, biomes, and terrain features. For long-lived overworld or static areas, generated layouts are typically treated as structural scaffolding that designers or LLM-assisted tools can refine with names, descriptions, and quests. For short-lived dungeon instances generated at runtime, the layouts are usually consumed as-is without additional authoring.

## Implementation Status

The target design includes a World-owned `SimpleDungeonGenerator` and `OverworldMapGenerator` engine with typed generation ingress. Current `SimpleDungeonGenerator` and registry implementations remain in Automation & Scripting, and World Management lacks typed APIs that invoke a World-owned engine.

Procedural generation allows games to quickly bootstrap playable areas, spawn instanced content, or fully generate open worlds without requiring hand-authored maps.

---

## Use Cases

- 🏗️ **World Bootstrapping** – Initialize a new world map without manual design.
- 🌀 **Dungeon Instances** – Generate instanced interiors on demand (e.g. for quests).
- 🧱 **Design Templates** – Offer scaffolds for designers to expand on.
- 🔁 **Replayable Zones** – Replay an admitted generation request from its recorded inputs; committed topology, not seed-only reconstruction, remains authoritative across sessions.

Generation flows share one pure generator engine owned by World Management but use two separate typed ingress contracts:

- **Design-time/template generation** – invoked only from Game Design workflows to produce versioned world scaffolding that is saved into template tables keyed by `(tenantId, versionId)` and later published like any other design asset.
- **Runtime/instance generation** – invoked from the Temporal world-lifecycle workflow or tick-driven commands for a concrete `(tenantId, gameInstanceId)` target. The request target is instance-scoped, but persistence follows the owner's replacement classification: `S1` and `S2` durable output is keyed by `(tenantId, playableStateNamespaceId)` and authorized for the active instance, while `S3` disposable output is keyed by `(tenantId, gameInstanceId)` only. These flows never modify template tables for Published versions.

Runtime generation is a concrete instance target, not a durable playable-state identity. Any generated family that survives replacement must use the stable `playableStateNamespaceId` plus active-instance authorization under [ADR 0122](./decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md). `S1` output and retained references survive unchanged only when their owning identity contract explicitly makes them namespace-stable. An instance-keyed reference is not `S1`: when it must survive through an explicit owner-validated mapping it is `S2`, and when its owner explicitly declares it disposable it is `S3`. `S2` mapping contents and successful owner application are required before cutover; `S3` remains instance-scoped and may be cleaned up with the old instance and regenerated for the new one. Unknown, unowned, or unclassified generated families block replacement rather than defaulting to `S3`, and a generation request or echoed mapping identifier does not authorize a namespace transition or prove mapping application.

The authenticated endpoint and its typed target union determine the namespace and persistence semantics:

- the design ingress accepts a Draft target keyed by `(tenantId, versionId, DraftScopeTarget)` and only Game Design may orchestrate it;
- the runtime ingress accepts an instance target keyed by `(tenantId, gameInstanceId, InstanceScopeTarget)` and only approved world-lifecycle or gameplay command paths may invoke it.

Callers do not supply a free `generationMode` as an authority selector. World Management derives the mode from the authenticated ingress and target, rejects cross-namespace combinations, validates generator output, and persists it. Design ingress may write only Draft template rows; Published rows are immutable. Runtime ingress may write generated durable output only according to the owner's classification: `S1`/`S2` output uses `(tenantId, playableStateNamespaceId)` with active `gameInstanceId` authorization, while declared `S3` output uses instance rows keyed by `(tenantId, gameInstanceId)` only. Instance-scoped request and generation-provenance records may retain the concrete `gameInstanceId` without making the generated durable output instance-owned. See [ADR 0100](decisions/adr-0100-separate-generation-ingress-with-one-world-owned-engine.md).

Runtime generation reads the authoritative `(tenantId, gameInstanceId)` lifecycle row, state, and epoch from World Management. For already-admitted dynamic generation, it also consumes Game Session's canonical `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` and the exact referenced realm-catalog snapshot; that snapshot supplies `playableStateNamespaceId` and resolves `stateScope` to `playableStateScope` under the [Multi-Tenancy realm catalog and admission-pointer contract](./system-architecture-multi-tenancy.md#realm-catalog-and-admission-pointer-contract). Pre-admission `PREPARING` world-lifecycle work binds the same server-resolved catalog snapshot but is authorized by the exact World lifecycle proof, not by inventing an `OPEN` pointer. Before output binding or finalization, World Management exact-compares tenant, instance, namespace, scope, lifecycle state/epoch and, when admitted, the `OPEN` target plus exact `pointerVersion` and `catalogRevision`; missing, stale, ambiguous, mismatched, replacement, or replay evidence fails closed. Focused proof must cover namespace, scope, lifecycle epoch, stale pointer/catalog revision, replacement, and replay mismatches.

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

Procedural generators are invoked by the World Management Service, which calls its one pure generator engine using a seed, parameters, and world context. The engine returns an abstract generated topology. The authenticated typed ingress remains authoritative for its namespace and target; World Management validates the output and either persists bounded normalized rows or privately stages and atomically finalizes an immutable topology artifact. Private staging is never authoritative or launchable, and a version-scoped artifact becomes launchable only through canonical publication attestation. Game Design owns Draft generation intent and revision orchestration but not topology persistence. Automation & Scripting must not execute generators, return generated topology for persistence, or persist topology.

- When invoked from Game Design workflows for **design templates**, results are finalized as normalized template rows or an immutable topology artifact keyed by `(tenantId, versionId)` and become part of the published topology for that version only after canonical publication attestation.
- When invoked from the world-lifecycle workflow or tick-driven commands for **runtime instances**, `S1`/`S2` results are finalized as normalized durable rows or immutable topology artifacts keyed by `(tenantId, playableStateNamespaceId)` and authorized for the active `gameInstanceId`; declared `S3` results are finalized as instance rows or artifacts keyed by `(tenantId, gameInstanceId)` and refer back to the chosen `versionId`. Instance-scoped request/provenance records may use `gameInstanceId` for retry and audit evidence, but do not change the output classification; template rows remain unchanged.

Persistent instance layouts are authoritative stored topology. Restarts and disaster recovery restore those rows, retained finalized artifacts, or backups; they do not depend on indefinite re-execution of the historical generator. Generator metadata remains provenance and request-retry evidence rather than a seed-only reconstruction guarantee.

Runtime-only dungeons or short-lived instances may be treated as ephemeral data that exists only for the lifetime of a specific `gameInstanceId` and is never shared across instances or versions. If ephemeral topology is lost, its lifecycle may permit discarding it and starting a new generation request under the current permitted generator policy. Long-lived overworld-style layouts must persist their actual topology.

Optional post-generation population hooks can then run to seed NPCs, spawns, or environmental details appropriate to the template or instance context.

### Request-Bounded Replay and Explicit Regeneration

Design-time generation is part of publish safety and reconciliation, so retries of one admitted request must not depend on mutable defaults or whichever generator binary happens to be current. This compatibility obligation is bounded to that request; FireMUD does not retain every historical generator implementation indefinitely.

At admission, World Management persists a durable generation record that includes:

- `generationRunId` and stable `generationRequestId`
- an immutable admitted-generation revision or approval identity, or an explicit regeneration sequence, as part of `generationRequestId`
- a typed `target`: exactly one of `{kind: "DESIGN", tenantId, versionId}` or `{kind: "RUNTIME", tenantId, gameInstanceId}`; this complete target identity is part of the durable record
- for a runtime target, an immutable output binding containing `{tenantId, playableStateNamespaceId, playableStateScope, outputClassification, gameInstanceId, lifecycleState, lifecycleEpoch}` and, when the runtime has already been admitted, the complete immutable admission-pointer proof `{tenantId, worldSlug, realmSlug, state=OPEN(gameInstanceId), pointerVersion, catalogRevision}`; `gameInstanceId` is only the concrete runtime identity, while the exact World lifecycle proof authorizes activation-time work in `PREPARING` and the complete pointer proof additionally authorizes already-admitted dynamic work
- `generationMode` as derived metadata from the authenticated ingress and typed target, never as a selector or inference mechanism for the persistence namespace
- `generatorType` and `generatorImplementationVersion` (or equivalent immutable build identifier)
- canonicalized `configSnapshot` including explicit `schemaVersion`
- seed and any derived deterministic inputs
- initial output state `PENDING` with one durable output-state optimistic version field, `outputStateRevision = 0`, and no output identity, `outputDigest`, output row count, or canonical serialized-byte count. This field is separate from the immutable admitted-generation revision or approval identity.

After canonical output is generated or private staging completes, exactly one compare-and-set transition requires the exact `PENDING` state and expected `outputStateRevision`, then binds `RECORDED` or `STAGED`, the output identity, canonical topology `outputDigest`, exact output row count, and canonical serialized-byte count while incrementing `outputStateRevision` exactly once. Every successful output-state transition increments this durable revision exactly once. The expected prior state/revision and resulting bound identity participate in the retry and finalize guard. Retry, replay, and recovery carry and compare that same persisted `outputStateRevision`. Runtime finalize, replay, and recovery compare the same applicable persisted fields exactly: tenant, namespace, scope, classification, concrete `gameInstanceId`, `lifecycleState`, `lifecycleEpoch`, and, when admission already occurred, the complete `{tenantId, worldSlug, realmSlug, state=OPEN(gameInstanceId), pointerVersion, catalogRevision}` pointer proof. Any mismatch fails closed. An exact replay may return the already-bound identity, but a stale or conflicting concurrent/replayed transition fails closed. Seed, generator type, and configuration remain separate provenance and cannot substitute for that binding. S1/S2 output remains namespace-owned; only declared S3 output is instance-owned.

The resolved implementation and inputs are immutable for that request, including across rolling nodes. These records and artifacts are retry/reconciliation evidence, not published topology rows themselves:

- For row-backed generated topology, the canonical publish contract is the finalized version-scoped template rows keyed by `(tenantId, versionId)`. For artifact-backed Draft topology, `GetDraftDesignDigest` covers the version-scoped semantic template/configuration/binding rows named by the World digest manifest, including the selected topology-root binding (immutable root key plus its actual-byte/root digest, or a typed equivalent); private staging rows and artifact bytes themselves are excluded from `contentDigest`.
- Artifact-backed topology is attested separately at publish: Game Design carries that exact immutable-root key/digest tuple unchanged into one typed `artifactDigests[]` entry with its required manifest usage key and validates the artifact bytes against it. The root's digest covers its exact ordered chunk identities and per-chunk digests; chunks are not additional release entries. Activation, rollback, backup, and recovery resolve and byte-validate that same attested root, while replay reuses the recorded or retained output rather than rerunning a generator.
- Staged output must remain available for the active request's retry lifecycle. Provenance may outlive the executable implementation and does not promise seed-only reconstruction.

Reconciliation behavior:

- Retrying the same in-flight request reuses its recorded or staged output. If that output cannot be reused and the admitted implementation is unavailable, the request fails closed or is explicitly abandoned; it never substitutes a newer implementation under the same identity.
- Once finalized, committed template topology is authoritative and recovery restores committed rows, immutable releases, retained finalized artifacts, or backups rather than re-executing the generator.
- Replaying a historical generation revision restores that revision's recorded or committed output, then reapplies later persisted manual revisions in their original order. It does not rerun an obsolete generator or reinterpret the historical revision as permission to delete later edits.
- Intentional regeneration or generation of previously unfixed topology later is a new request and, where authored content is affected, a new authored revision. Chunks generated or staged under the same admitted request retain that request's `generationRequestId` through finalization and are not new generation requests. A new request may select the newest generator or model permitted by explicit game or operator policy and must obey the declared scope, epoch, and replacement rules.
- For row-backed output, the publish-gating digest covers the finalized template rows produced by the generation request. For artifact-backed output, semantic Draft rows remain the `contentDigest` input and the artifact's actual bytes are checked through its attested `artifactDigests[]` entry, so replay and publish checks converge without hashing the same artifact bytes into two authorities.

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
- Before `REPLACE_SCOPE` can be accepted, Game Design presents an exact destructive plan identifying creates, retained objects, replacements, deletions, affected references, identifier mappings, and blockers. The approved request carries the `generationRequestId`, immutable generator or model implementation identity, exact canonical generation inputs, and a canonical generated-result/plan digest that binds the resolved output to the destructive plan, current `expectedDraftScopeRevisionEpoch`, and reference facts.
- `REPLACE_SCOPE` fails as `DRAFT_WRITE_CONFLICT` and requires a new preview when the request identity, implementation identity, canonical inputs, generated-result/plan digest, resolved output, scope epoch, or relevant reference facts differ from the approved preview.
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

#### Generation Pipeline

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
| `exitMap` | Map of direction → target `roomKey` for `SPARSE_GRAPH`, or direction → target `cellKey` for `FULL_GRID` |
| `tags` | Optional labels like `"start"`, `"town"`, etc. |
| `biome` | Biome or terrain type (if applicable) |
| `elevation` | Numeric terrain height (used for visuals or logic) |
| `regionKey` | Optional grouping key for partitioned maps (not a persisted template/instance id) |

Topology density and movement policy are independent versioned choices. A sparse game may make every declared exit one uniform movement, while another may use geometric distance. A full grid may likewise use uniform steps, explicit costs, or terrain/elevation-sensitive movement. `spacingMultiplier` is stored on the containing region and participates only when the selected typed movement policy declares it. Sparse topology does not implicitly make travel slower, and full-grid topology does not implicitly make travel uniform.

In `FULL_GRID`, every valid cell is logically addressable and traversable according to the declared adjacency and terrain rules. Walls, invalid cells, impassable terrain, and lattice bounds may still block a direction. In `SPARSE_GRAPH`, only selected nodes and their declared edges are locations. Neither choice dictates movement cost by itself.

World Management exclusively assigns or resolves canonical identifiers when finalizing and exposing generator output:

- Design-time/template locations resolve to stable `roomTemplateId` values keyed by `(tenantId, versionId)`.
- Current World runtime locations/topology are `S3` instance-scoped output and resolve to `roomInstanceId` values keyed by `(tenantId, gameInstanceId)`, carried through the instance-scoped `RoomInstanceRef`.

Generator outputs must not assume database row identity. World Management may eagerly persist bounded graphs or resolve a cell from an immutable chunked base plus durable instance state, but the same logical cell must resolve to the same authoritative identity for its required lifetime.

If a future generated location family is classified as `S1` or `S2`, its durable location identity must instead be namespace-backed by `playableStateNamespaceId`, and readers must perform an explicit namespace-to-active-instance resolution before obtaining an instance-scoped `RoomInstanceRef`. An instance-keyed `roomInstanceId` cannot serve as that durable identity. This future rule does not reclassify the current World runtime families or add a mapping API; it preserves their existing `S3` instance scope.

### Large Full-Grid Representation

Physical topology representation is opaque behind World Management:

- Sparse and bounded moderate graphs may use eager template, instance, and exit rows within enforced and tested limits.
- Before large full-grid scale is claimed, generation must produce an immutable digest-attested chunk topology or equivalent bounded representation. A validated root manifest identifies the complete lattice and immutable chunks, and one short finalize selects that root atomically so readers never observe a partial grid.
- For a published bounded `FULL_GRID`, the committed root manifest is the single `artifactDigests[]` entry for the topology, and its usage key is required by the published release bundle. The immutable root contains the exact ordered chunk identities and per-chunk digests, so the root's actual-byte digest transitively attests the complete chunk set; individual chunks are not separate release-bundle entries. Private staging is excluded. Publication, activation, rollback, backup, and recovery use this same attested root and validate every chunk's bytes against it.
- Runtime reads compose the immutable base cell with durable instance-scoped deltas for visited, occupied, changed, timed, or otherwise mutable locations. Caches and lazy materialization remain derived projections rather than authority.

Loading or materializing an already committed cell is not regeneration. Recovery restores the stored topology artifact and durable runtime deltas instead of re-running an obsolete generator from seed. A world that intentionally creates previously unfixed chunks later is an unbounded or expanding generation mode and requires a separate contract; it is not the bounded fixed `FULL_GRID` mode.

---

## Integration Guidelines

The following rules align generators with the core runtime and tooling:

1. **Solo Tick Scheduling** – Post-activation or dynamic runtime generation is queued like any other command but includes `requiresSoloTick: true`. The Game Session Service executes it in an isolated tick and, if extra runtime is required, must use the canonical `solo_tick_budget_ms` mode defined by the tick invariants rather than an ad-hoc extended budget.
2. **Heavy Post‑Gen Population** – Population scripts may declare `requiresSoloTick: true`. The Game Session Service schedules these in dedicated ticks to avoid fairness regressions and may only exceed the normal budget when `solo_tick_budget_ms` is enabled for that deployment/profile.
3. **Seed & Metadata Persistence** – Every admitted generation keeps its authoritative typed request/output binding separate from provenance containing the seed, `generatorType`, and canonical configuration snapshot. A design-time request binds the complete version-scoped target. A runtime request binds `{tenantId, playableStateNamespaceId, playableStateScope, outputClassification, gameInstanceId, lifecycleState, lifecycleEpoch}` and, after admission, the complete immutable pointer proof `{tenantId, worldSlug, realmSlug, state=OPEN(gameInstanceId), pointerVersion, catalogRevision}`. `gameInstanceId` is only the concrete runtime identity. Activation-time work in `PREPARING` carries the exact World lifecycle proof without a pointer, while already-admitted dynamic work also carries the complete pointer proof that authorizes it. Its provenance may retain the concrete `gameInstanceId` for retry and audit. Metadata required to interpret S1/S2 output is namespace-owned or durably linked to the namespace-keyed output, while only declared S3 output uses `gameInstanceId` as durable output identity. The provenance record is not an alternate authority for generated topology.
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

   - Each generation run is assigned a `generationRunId` scoped to the complete typed target identity and target scope; a design target and a runtime target are never interchangeable.
   - Callers must supply (or World Management must derive deterministically) a stable `generationRequestId` so retries of “the same request” map to the same `generationRunId` and become replay-safe.
   - `generationRequestId` must be derived from business identity rather than transient execution identity (for example a hash of `tenantId`, target scope key, generation step name, canonicalized generator config, and the immutable admitted-generation revision or approval identity, or explicit regeneration sequence). Retries through a new Temporal run or synchronous retry must reuse the same `generationRequestId`; an intentional regeneration with the same inputs must use a distinct sequence and request ID.
   - World Management must enforce a uniqueness constraint on `(typedTarget, targetScopeKey, generationRequestId)`, where `typedTarget` contains exactly one complete design or runtime target identity, so duplicate requests converge to one run without crossing namespaces.
   - World Management must enforce single-writer semantics per typed target and target scope through the scope epoch/fence or equivalent storage-level compare-and-set, together with request uniqueness, so concurrent runs, retries, and recovery cannot cross targets or both commit.
   - Generation and all graph, scope, count, byte-size, and digest validation complete before the visibility transaction. That transaction performs no generator execution or network calls.
   - An output within enforced and proved row and serialized-byte limits may write the complete result and idempotency outcome in one owner-local transaction. Readers see either the prior scope or the complete new scope.
   - Output above those limits, or output requiring chunked persistence, uses private staging keyed by `(tenantId, generationRunId)`. A short finalize transaction validates the request identity, scope fence, bound output state/epoch and identity, exact expected output row count, exact expected canonical serialized-byte count, and canonical digest before atomically installing or selecting the graph or immutable root chunk manifest.
   - The initial implementation may reject oversized output deterministically until the staged path exists. Callers may not bypass the bounded-transaction limits.
   - On failure World Management returns a `GenerationErrorDetail` and guarantees the target scope remains unchanged. When staging is used, World Management must define bounded diagnostic retention and garbage collection for abandoned rows.
   - Focused proof must show that retrying one admitted request reuses its request identity and recorded output, while an intentional same-input regeneration receives a distinct sequence and identity; recovery must restore the committed revision output and reapply later persisted manual revisions without invoking an obsolete generator.
8. **Editor Overlays** – Generators emit coordinates and optional map layers so the Game Editor can display a preview or dry-run JSON output.
9. **Pluggable Interface** – In the target state, generators implement the `Generator` interface and are discovered via the `GeneratorRegistry` in the World Management Service. Discovery uses Spring bean scanning, and additional generators may be provided by shared libraries or service-local modules.

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

- Owns launch-time topology generation and persistence as a pre-activation world-lifecycle workflow outside Game Session ticks
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

- Requests launch-time instance creation through the World-owned pre-activation workflow without scheduling topology generation as tick work
- Schedules `requiresSoloTick` only for post-activation population and later dynamic generation, including portal- or quest-driven instancing commands
- Coordinates Redis tick isolation and invokes World Management for those post-activation or later dynamic commands

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
