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

Procedural generators are invoked by the World Management Service, which calls pure `Generator` implementations as library functions using a seed, parameters, and world context. The generators return an abstract room/region graph that World Management validates and persists as versioned world records. Optional post-generation population hooks can then run to seed NPCs, spawns, or environmental details.

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
| `roomId` | Unique identifier |
| `coordinates` | Grid location (used for spatial logic and editing) |
| `exitMap` | Map of direction → `roomId` |
| `tags` | Optional labels like `"start"`, `"town"`, etc. |
| `biome` | Biome or terrain type (if applicable) |
| `elevation` | Numeric terrain height (used for visuals or logic) |
| `regionId` | Optional grouping for partitioned maps |

`spacingMultiplier` is stored on the containing region (World Management) and can globally scale movement speed across the map. In sparse layouts Game Logic uses room coordinates and this `spacingMultiplier` to derive movement/travel cost, so nearby rooms are quick to traverse while large gaps produce longer travel times.

In **full-grid mode**, every terrain tile becomes a room.
In **sparse mode**, only selected POIs and waypoints are emitted, and the distance between them determines travel cost.

---

## Integration Guidelines

The following rules align generators with the core runtime and tooling:

1. **Solo Tick Scheduling** – Runtime generation is queued like any other command but includes `requiresSoloTick: true`. The Game Session Service executes it in an isolated tick with an extended, configurable time budget.
2. **Heavy Post‑Gen Population** – Population scripts may declare `requiresSoloTick: true`. The Game Session Service schedules these in dedicated ticks to avoid fairness regressions.
3. **Seed & Metadata Persistence** – All generation requests include a seed. **World Management Service** persists `seed`, `generatorType`, and raw params alongside region/room records. The **Automation & Scripting Service** includes these values in its result payload but **does not store** them.
4. **Tenant Scoping** – All generation inputs/outputs are tenant‑scoped. Generators resolve tenant feature flags/config before execution.
5. **Sparse Traversal Rules** – Exit costs between sparse rooms are derived from their coordinate distance. **Game Logic** uses region `spacingMultiplier` to scale the overall pace if needed.
6. **Post-generation Population** – After rooms are created, the Automation & Scripting Service triggers population scripts based on room tags, biome, and difficulty zone.
7. **Validation and Errors** – A&S validates parameters and connectivity and returns an atomic graph on success; otherwise a `GenerationErrorDetail` with no partial persistence.
8. **Editor Overlays** – Generators emit coordinates and optional map layers so the Game Editor can display a preview or dry-run JSON output.
9. **Pluggable Interface** – Generators implement the `Generator` interface and are discovered via the `GeneratorRegistry` in the Automation & Scripting Service. Discovery uses Spring bean scanning, and scripted or DSL-based generators are supported.

Generation parameters can be tuned at runtime through the [Procedural Generation Rules API](./microservices/world-management-service/README.md#procedural-generation-rules-api). Administrators may adjust room density or terrain variation without redeploying the service. These rules are treated as **runtime configuration**, not versioned design data:

- Rules are keyed by `tenantId` and updated in place via REST.
- World creation and runtime generation calls snapshot the effective parameters
  they use (including the generator type, seed, and rule identifiers or hashes)
  alongside the generated regions and rooms so that operators can later
  reconstruct the inputs used for a particular world or instance.

---

## Service Responsibilities

### World Management Service

- Owns invocation of generators as pure functions and persists generated rooms/biomes/regions; assigns canonical `roomId`s
- Persists generator metadata (`seed`, `generatorType`, params) and editor overlays, including a snapshot of the effective procedural rule configuration used for each generation run
- Provides read APIs for geometry, overlays, and region metadata

### Automation & Scripting Service

- Provides optional post-generation population scripts (for example, spawning NPCs or loot) that can be invoked by World Management based on tags, biome, and difficulty
- Integrates procedural generation results with the broader scripting and automation framework where needed

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
