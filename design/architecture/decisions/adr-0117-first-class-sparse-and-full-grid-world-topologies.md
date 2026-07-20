# ADR 0117: First-Class Sparse and Full-Grid World Topologies

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `PROC-05`
- Primary capability: `GR-2.2` location, occupancy, movement, exits, and spatial reads
- Affected capabilities: `GR-2.1`, `AR-1.1`, `AR-1.5`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of sparse and full-grid gameplay, movement policy, topology representation, stable room identity, large-graph scaling, recovery, and procedural-generation compatibility

## Context

FireMUD must support both classic text worlds built from selected meaningful rooms and simulated worlds in which every valid cell of a grid is an authoritative location that players and systems can traverse. Treating full-grid support as optional merely because it is harder to store or scale would exclude required game types.

The product guarantee must not unnecessarily prescribe a database layout. Requiring one room row and several exit rows for every cell would fit the current relational model but could make large versions, running instances, publication, backup, and migration prohibitively expensive. Conversely, deriving unexplored cells indefinitely from only an old seed would make recovery and future traversal depend on retaining historical generator implementations, contrary to the request-bounded replay and committed-output authority in ADR 0098.

Topology density also does not by itself determine gameplay pace. A sparse text world can treat each exit as one ordinary movement, while another sparse world may model long travel. A full grid may use uniform steps or terrain-sensitive cost. These are separate authored choices.

## Decision

`SPARSE_GRAPH` and `FULL_GRID` are first-class version-scoped semantic topology modes.

- `SPARSE_GRAPH` materializes selected locations such as points of interest and path nodes plus their declared connectivity.
- `FULL_GRID` defines a bounded declared lattice in which every valid cell is a stable authoritative gameplay location. The version defines its supported adjacency directions, bounds, impassable terrain, and other typed traversal rules. Supporting movement in every declared direction does not make walls, invalid cells, or world boundaries traversable.

`FULL_GRID` does not require one relational room or exit row per cell and does not require every cell to run a continuously active simulation. It requires World Management to resolve the same authoritative cell facts, stable logical identity, adjacency, and runtime room identity whenever that cell is addressed. Once a cell is exposed to runtime through `RoomInstanceRef`, its identity is stable and non-reused for the required game-instance lifetime.

Topology density and movement policy are independent versioned game-design choices. The published design declares whether movement uses uniform steps, explicit authored costs, geometric distance, terrain or elevation, a region multiplier, or another supported typed policy. `spacingMultiplier` participates only when the selected policy declares it; sparse topology does not implicitly mean longer movement, and full-grid topology does not implicitly mean uniform cost. World Management owns authoritative geometry, cell/room resolution, adjacency, and occupancy facts. Game Logic applies the published movement policy to those facts.

Physical representation remains opaque behind World Management and may vary by proved scale:

- Sparse and bounded moderate graphs may use eagerly persisted template, instance, and exit rows within enforced and tested limits.
- Before FireMUD claims support for large full grids, the implementation must use an immutable digest-attested chunk topology or equivalent bounded representation. One atomically selected root manifest identifies the complete logical topology and its immutable chunks. Readers must never observe a partially finalized topology.
- Runtime combines the immutable base topology with durable instance-scoped deltas for visited, occupied, changed, timed, or otherwise mutable cells. Caches and lazy materialization are derived projections, not authority.

Loading or materializing a cell from an already committed immutable chunk is not a new generation request. The cell's semantic topology was fixed by the original admitted generation request and release. Recovery restores the stored authoritative topology artifact and durable runtime deltas; it does not re-run an obsolete generator from seed.

An intentionally unbounded or expanding world that generates previously unfixed chunks later is a separate topology mode and decision. It must not be described as the fixed bounded `FULL_GRID` contract.

## Consequences

- FireMUD retains support for meaningful-room MUDs and for simulated fully traversable grids without forcing either model on every game.
- Game designers control density and travel pacing independently through versioned design inputs.
- Runtime services use the same World-owned room, movement, targeting, occupancy, and snapshot contracts regardless of physical topology representation.
- Bounded eager rows provide a simpler initial implementation, but they are not sufficient evidence for large-grid scale.
- Chunked topology avoids mandatory per-instance duplication of every untouched cell but adds manifest, resolver, caching, delta, spatial-query, editing, backup, and diagnostic complexity.
- Cells with no active or mutable state need not consume continuous simulation resources merely because they logically exist.
- Immutable stored topology and deltas add retention and backup obligations but avoid indefinite historical-generator compatibility.

## Alternatives Considered

### Persist One Room and Every Exit for Every Cell

This matches the existing relational graph and makes room, script, occupancy, and editing paths direct. It remains permitted within proved bounds, but it is not the universal contract because large grids can multiply template, instance, exit, publication, and backup volume unnecessarily.

### Support Sparse Graphs Only

This would suit many text MUDs and simplify authoring and persistence, but it would exclude required simulations and games in which every grid cell is a playable location.

### Derive Cells Indefinitely from Seed on First Access

This minimizes stored topology, but a fixed world would depend on retaining and re-executing its historical generator forever. Using a newer generator for previously unexplored cells would instead create an evolving world and violate the claim that one bounded full grid was already fixed.

### Couple Sparse Density to Distance-Based Travel

Automatically making sparse edges slow and full-grid edges uniform is convenient but conflates representation with game mechanics. Density and movement policy remain independently authored.

## Implementation and Proof Obligations

The current implementation does not provide this capability. It has relational room and exit rows, a persisted region `spacingMultiplier`, and a small pathfinding helper, but no `OverworldMapGenerator`, first-class topology-mode schema, full-grid compiler, immutable chunk-topology artifact, lazy cell resolver, durable base-plus-delta composition, coordinate-derived movement path, or large-grid proof. Ordinary directional movement does not yet exercise the broader travel-cost subsystem.

Implementation must validate bounded lattice dimensions, cell identity, allowed adjacency directions, impassable cells, movement-policy inputs, and complete version-scoped semantic configuration. It must prove equivalent authoritative room snapshots, movement results, targeting, scripting, occupancy, and mutable room behavior across eager and chunked representations.

Large-grid proof must cover stable cell and `RoomInstanceRef` resolution; concurrent first access; bounded spatial queries; atomic root-manifest finalization; missing, duplicated, corrupt, or mismatched chunks; cache loss; restart; backup and restore of immutable topology plus runtime deltas; occupied and modified cells; script targeting without unbounded enumeration; publication, rollback, and version cutover; and measured generation, storage, memory, read, movement, and backup envelopes. Large-scale support must not be claimed until those paths and limits are demonstrated.

ADR 0098 remains authoritative for request-bounded generator selection and stored committed output. ADR 0099 remains authoritative for bounded atomic visibility: for a chunked grid, the short finalize selects the validated digest/count-checked root topology rather than inserting every logical cell in one transaction.

## Reversibility and Revisit Triggers

Database layout, chunk size, artifact encoding, cache strategy, identity-allocation mechanism, and eager-versus-chunked thresholds may evolve while the semantic topology, stable identity, World ownership, atomic visibility, and stored-output recovery guarantees remain unchanged. Revisit this decision before promising unbounded fixed-seed worlds, continuously simulated inactive cells, or a new movement policy that cannot be represented by the typed versioned contract.

## Required Documentation Alignment

- `design/architecture/system-architecture-procedural-generation.md`
- `design/architecture/microservices/world-management-service/procedural-generation-control.md`
- `design/architecture/microservices/world-management-service/runtime-and-data.md`
- `design/architecture/microservices/world-management-service/world-creation-workflow.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- `design/architecture/decisions/adr-0098-request-bounded-generation-replay-and-explicit-regeneration.md`
- `design/architecture/decisions/adr-0099-bounded-atomic-generation-with-staging-for-large-outputs.md`
