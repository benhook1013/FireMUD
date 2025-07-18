# 🧭 FireMUD System Architecture: Procedural Generation

This document outlines how FireMUD supports procedural generation of both dungeon-style and overworld-style layouts. These generators can be invoked during world creation or dynamically at runtime to produce rooms, exits, biomes, and terrain features. Runtime invocation is planned but not yet implemented. (TODO: Not yet implemented)

Currently the only implemented generator is `SimpleDungeonGenerator`. The `OverworldMapGenerator` referenced below is planned for a future release. (TODO: Not yet implemented)

Procedural generation allows games to quickly bootstrap playable areas, spawn instanced content, or fully generate open worlds without requiring hand-authored maps.

---

## 🎯 Use Cases

- 🏗️ **World Bootstrapping** – Initialize a new world map without manual design.
- 🌀 **Dungeon Instances** – Generate instanced interiors on demand (e.g. for quests).
- 🧱 **Design Templates** – Offer scaffolds for designers to expand on.
- 🔁 **Replayable Zones** – Create consistent layouts from the same seed across sessions.

---

## 🧩 Generator Types

FireMUD currently supports the following generator types, with pluggable strategies planned:

### 1. `SimpleDungeonGenerator`

Creates compact room graphs with bidirectional exits — ideal for dungeons, interiors, or short instances.

#### Algorithm:

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

Procedural generators are invoked through `GenerationService`, which looks up the requested `Generator` from `GeneratorRegistry` and runs any registered `GenerationHook` implementations to populate newly created rooms. Current hooks include a simple logger and a basic spawn helper.

---

### 2. `OverworldMapGenerator` (In Development) (TODO: Not yet implemented)

Generates biome-aware terrain maps with elevation, water features, forest density, and region partitioning. Room creation is configurable: either generate **sparse rooms** only at points of interest (POIs), or generate a **full grid of rooms** based on the terrain data.

#### Generation Pipeline:

| Step                     | Purpose                                     | Common Techniques                          |
|--------------------------|---------------------------------------------|--------------------------------------------|
| **Elevation Map**        | Define height (mountains, valleys)          | `Perlin Noise`, `Diamond-Square`           |
| **Moisture Map**         | Define biome type (desert, swamp, forest)   | Gradient sampling, additional noise layer  |
| **Biome Assignment**     | Use height + moisture to classify terrain   | Threshold tables or biome rulesets         |
| **Region Partitioning**  | Divide into zones/factions                  | `Voronoi`, seeded points + expansion       |
| **River/Lake Simulation**| Carve out natural water features            | Flow fields, downhill tracing              |
| **Forest/Cave Generation** | Place dense blobs of trees or underground | Cellular automata                          |
| **Feature Placement**    | Place towns, dungeons, landmarks            | `Poisson Disk Sampling`, seeded rules      |
| **Connectivity Graph**   | Generate roads, rivers, and path exits      | A*, flow maps, elevation-aware routing     |
| **Room Graph Export**    | Convert terrain grid into room data         | Either sparse (POIs and path nodes only) or full (1:1 room per map cell) |

> 🔧 The room generation mode (sparse vs full) is selectable per generation request, depending on the game’s desired level of detail and exploration density.

---

## 🧾 Output and Metadata (Common)

All generators emit a normalized structure:

| Field         | Description                                         |
|---------------|-----------------------------------------------------|
| `roomId`      | Unique identifier                                   |
| `coordinates` | Grid location (used for spatial logic and editing) |
| `exitMap`     | Map of direction → `roomId`                         |
| `tags`        | Optional labels like `"start"`, `"town"`, etc.     |
| `biome`       | Biome or terrain type (if applicable)              |
| `elevation`   | Numeric terrain height (used for visuals or logic) |
| `regionId`    | Optional grouping for partitioned maps             |

`spacingMultiplier` is stored on the containing region to adjust travel cost when rooms are sparse. (TODO: Not yet implemented)

In **full-grid mode**, every terrain tile becomes a room.
In **sparse mode**, only selected POIs and waypoints are emitted.

---

## 🛠️ Integration Guidelines

The following rules align generators with the core runtime and tooling:

1. **Solo Tick Scheduling** – Runtime generation is queued like any other command but includes `requiresSoloTick: true`. The Game Session Service executes it in an isolated tick with an extended 500&nbsp;ms budget.
2. **Seed Metadata** – All requests specify a seed and the Automation & Scripting Service stores `seed`, `generatorType`, and raw params in the region metadata table for later inspection. (TODO: Not yet implemented)
3. **Sparse Traversal Rules** – Sparse rooms exist on the map. A `spacingMultiplier` value on each region influences movement cost and travel time between them. (TODO: Not yet implemented)
4. **Post-generation Population** – After rooms are created, the Automation & Scripting Service triggers population scripts based on room tags, biome, and difficulty zone. Basic hooks exist but full script-driven population is pending. (TODO: Not yet implemented)
5. **Validation and Errors** – Generators validate parameters. Room count checks are implemented, while biome compatibility and connectivity validation are pending (TODO: Not yet implemented). Failures return `GenerationErrorDetail` objects and are logged for observability.
6. **Editor Overlays** – Generators emit coordinates and optional map layers so the Game Editor can display a preview or dry-run JSON output. (TODO: Not yet implemented)
7. **Pluggable Interface** – Generators implement the `Generator` interface and are discovered via the `GeneratorRegistry` in the Automation & Scripting Service. Discovery currently relies on Spring bean scanning and may be extended for scripted or DSL-based generators. (TODO: Not yet implemented)

Generation parameters can be tuned at runtime through the [Procedural Generation Rules API](./microservices/world-management-service/README.md#procedural-generation-rules-api). Administrators may adjust room density or terrain variation without redeploying the service. (TODO: Not yet implemented)

---

## 🧱 Service Responsibilities

- **Automation & Scripting Service**
  - Owns and executes all procedural generation logic
  - Registers available generator types
  - Exposes generation via API and scripting interfaces

- **World Management Service**
  - Persists generated rooms, biomes, and regions
  - Assigns canonical `roomId`s and manages region mappings

- **Game Session Service**
  - Can request runtime instancing for dungeons, portals, or quests (TODO: Not yet implemented)
  - Integrates with tick state and Redis coordination

---

## ⚠️ Limitations and Roadmap

| Area                     | Status                      |
|--------------------------|-----------------------------|
| Biome-based gameplay     | 🚧 Planned (e.g., movement cost, visibility) (TODO: Not yet implemented) |
| Terrain traversal rules  | 🚧 Planned per biome or elevation delta       (TODO: Not yet implemented) |
| Region-specific scripting| 🚧 Future integration with spawn rules, lore  (TODO: Not yet implemented) |

Planned enhancements:

- Procedural POI lore naming and description generation (TODO: Not yet implemented)
- Seasonal or climate-based biome variations (TODO: Not yet implemented)
- Visual preview overlays in Game Editor (TODO: Not yet implemented)
- Runtime tuning parameters via scripting (TODO: Not yet implemented)

---

## 📚 Related Documentation

- [Automation & Scripting Service](./microservices/automation-scripting-service/README.md)
- [World Management Service](./microservices/world-management-service/README.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Game Session Service](./microservices/game-session-service/README.md)
