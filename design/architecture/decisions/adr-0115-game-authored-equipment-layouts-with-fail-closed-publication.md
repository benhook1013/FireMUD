# ADR 0115: Game-Authored Equipment Layouts with Fail-Closed Publication

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `EQUIP-01`
- Primary capability: `GR-3.3` equipment, body layouts, slots, and loadouts
- Affected capabilities: `AR-1.1`, `AR-3.2`, `GR-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of global and game-authored vocabularies, creator defaults, client presentation, runtime validation, publication, and cutover

## Context

A platform-global equipment enum is simple for a conventional humanoid game, but it makes anatomy and attachment semantics a platform release concern. Games may need tails, wings, paws, asymmetric limbs, vehicle hardpoints, module bays, or other layouts that do not fit one universal vocabulary.

Game-authored opaque keys create their own obligations. Creators need useful defaults, clients need authored presentation information, and runtime must not interpret a missing schema or unknown layout as permission to equip an item. Published and migrating games also need equipment state validated against the exact target release.

The current implementation has versioned slot and layout rows and validates authored slot existence, one optional slot-group match, and body-layout membership when the relevant schema rows exist. It also has permissive missing-schema and unknown-layout behavior, assigns `DEFAULT` on the current character DTO mapping path, and does not prove complete occupancy or cutover remapping.

## Decision

Versioned game-authored slot definitions and body layouts are the sole authority for equipment vocabulary. FireMUD has no platform-global slot enum and does not use hidden platform slot definitions as runtime fallback.

Starter profiles may materialize ordinary editable slot definitions, layouts, groups, and related compatibility data into a game's Draft. After materialization those rows are game-owned content governed by the normal revision and publication workflow; the profile is not a runtime inheritance layer.

Slot definitions may include authored presentation metadata needed by clients, such as localized display labels, stable ordering, grouping, icons, and optional semantic hints. These fields improve generic UI and accessibility but do not create an authoritative global anatomy vocabulary.

A game that enables equipment capability must publish a complete valid equipment schema. Every equip-capable character, NPC, species, or other actor template must resolve to a valid body layout in that version. Publication fails closed for missing or unknown slots and layouts, invalid group or attachment references, inconsistent occupancy declarations, or other invalid equipment relationships.

Runtime validates slot existence, item compatibility, actor layout membership, and occupancy before creating or changing an equipment binding. Missing schema and unknown layouts fail closed; the legacy direct-string bootstrap fallback is retired.

Before a version cutover, existing equipped state is validated against the target version. Stable equivalents are preserved only through the ordinary compatible identity/remap rules. Renamed, removed, split, merged, or newly incompatible equipment definitions require an explicit mapping or resolution; unresolved equipped state blocks cutover rather than being silently dropped or attached to an arbitrary slot.

## Consequences

- Creators can model humanoid, non-humanoid, and mechanical attachment systems without a FireMUD code release.
- Materialized starter profiles retain easy conventional defaults without creating a second runtime authority.
- Generic clients require richer authored metadata and cannot infer anatomy from a platform enum.
- Publication and cutover perform more validation, but invalid schemas and incompatible live equipment fail before gameplay is admitted.
- Runtime validation remains a bounded equipment-operation cost and can use immutable release-scoped caches without weakening the authoritative data model.

## Alternatives Considered

### One Platform-Global Slot Vocabulary

Rejected because unusual games would require misleading aliases, platform changes, or unsupported anatomy. The small reduction in editor and runtime complexity does not justify constraining the product boundary.

### Global Semantic Categories with Game Aliases

Rejected as an authority model because every game slot would still have to fit a universal semantic taxonomy. Optional presentation hints are permitted, but they do not decide compatibility or layout membership.

### Permissive Direct-String Fallback

Rejected as the target state because missing schema or a misspelled layout silently disables validation. Starter profiles provide the bootstrap path by creating ordinary valid Draft content.

### Validate Only When Equipment Is Used

Rejected because a publish or cutover could admit a release that cannot consistently represent existing actors or equipment. Runtime validation remains defense in depth, not the first discovery point for structural content errors.

## Implementation and Proof Obligations

Publication validation must cover unique normalized keys, all slot/group/attachment references, every equip-capable actor layout, layout occupancy rules, item compatibility, and required client presentation data. Runtime proof must cover missing schema, unknown layout, undefined slot, incompatible group or attachment, occupied slot, successful equip/remove, replay, and release-scoped lookup behavior.

Cutover proof must cover unchanged layouts, explicit renames and mappings, removed slots, incompatible items, occupied bindings, failed remaps, and fail-closed admission when any surviving equipped state is unresolved. Starter-profile proof must show that defaults are materialized editable content and that later profile changes do not alter a game implicitly.

The live implementation is partial. It provides versioned slot definitions, body-layout membership, character layout keys, equipment bindings, and focused slot/group/layout validation. It does not yet fail closed for every missing schema or unknown layout, provide the complete creator and presentation schema, model and prove full occupancy/attachment behavior, or validate and remap equipped state through cutover.

## Reversibility and Revisit Triggers

Presentation fields, compatibility relations, and occupancy models may evolve through versioned authored schemas. Optional conventions may become widely adopted, but promoting them to a platform-global authority requires a new decision. Reintroducing permissive fallback would weaken publication and runtime safety and also requires a new decision.

## Required Documentation Alignment

- `design/architecture/microservices/game-design-service/item-equipment-balancing.md`
- `design/architecture/microservices/game-design-service/world-editing-tools.md`
- `design/architecture/microservices/entity-management-service/runtime-and-data.md`
- `design/architecture/microservices/entity-management-service/README.md`
