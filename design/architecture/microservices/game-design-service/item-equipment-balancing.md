# Item & Equipment Balancing Tools

Game balance relies heavily on item statistics and equipment progression. This document outlines tools for tuning those values. Balancing data follows the same revision and version publishing workflow used throughout the Game Design Service so that stats remain consistent across releases. Designers store changes via the `SaveRevision` gRPC endpoint and publish them with `PublishVersion`. Those publish operations now run through the durable `publish` Temporal workflow described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md). See [Version Control for Design Assets](version-control.md) for more detail.

Balancing records are scoped by `tenantId` so multiple games can maintain independent item definitions. They are stored as versioned template rows keyed by `(tenantId, versionId)` and published alongside other entity definitions so runtime services always load a consistent, immutable template for the active version.

The capabilities below describe the target creator-facing balancing surface. The current implementation provides version-scoped item/equipment data and the runtime schema substrate, but not the complete editor and visualization experience described here.

## Features

- **Item Stat Editor** – adjust damage, defense, and stat bonuses with real‑time validation.
- **Equipment Curves** – visualize power growth across item tiers to spot outliers.
- **Economy Impact** – preview vendor prices and drop rates to maintain a healthy in-game economy.
- **Integration with [Ability & Action Design Tools](ability-action-tools.md)** – compare ability damage values with item stats for holistic balance.
- **Web-Based Interface** – edit and visualize stats through the drag‑and‑drop UI described in [Web-Based Visual Design Interface](web-visual-interface.md).

## Workflow

The workflow below is the target authoring and publication flow. Today, the live substrate is the versioned Entity Management DML plus Game Design revision/publish orchestration and Entity Management runtime validation; the creator-facing editor, balancing views, and visual web interface remain future application work.

1. Items and equipment are defined through the Entity Designer (see [World Editing & Customization Tools](world-editing-tools.md)).
2. Balancing views aggregate stats and show graphs for cost vs. power.
3. Finalized changes are published as part of a game version and persisted as
   versioned item records in the Entity Management Service, which uses the
   active `version_id` at runtime. Balancing edits are applied only to Draft
   versions; once a version is Published, its item templates are immutable and
   new balance changes must flow through a new Draft and publish cycle. For live
   games this means non-script balance adjustments are deployed by publishing a
   new version and performing replacement-instance cutover (prepare new
   `gameInstanceId`, swap admission pointer, then drain/terminate old instance)
   against the new `runtime_version`, not by editing templates for an Active
   version in place.

Cross-service references to item and equipment templates follow the normalized invariants described in `world-editing-tools.md`:

- World layouts, loot tables, and procedural generation rules refer to items and equipment only via stable template identifiers owned by the Entity Management Service, always scoped by `(tenantId, versionId)`.
- Balancing tools do not create separate, competing representations of item stats in Game Design Service; they operate directly on the versioned templates in Entity Management via design-time APIs and record provenance in Game Design history.

Current `06.3` / item-instance note:

- item templates now expose an explicit authored `stackable` capability rather than relying on implicit "same item means same stack" behavior;
- eligible stackable items now use holder-local `item_stacks` records with compatibility and stack-family validation; non-stackable item-instance identity remains distinct;
- non-stackable remains the safe default for ordinary physical items, especially equipment, containers, and other stateful items.

Current equipment-schema note:

- slots and body layouts are game-authored versioned concepts, not platform-global enums;
- item templates use `equipmentSlot` as their default target slot for the current player command loop and may use `equipmentSlotGroupKey` to constrain compatibility against the authored slot definition;
- starter profiles may materialize conventional editable slots, groups, layouts, and compatibility rows into a Draft, but those rows become ordinary game-owned content rather than a runtime inheritance layer;
- slot definitions may carry authored localized labels, ordering, grouping, icons, and optional semantic hints for clients without making those hints a platform-global compatibility vocabulary;
- a game with equipment capability must publish a complete valid schema, and every equip-capable actor must resolve a valid body layout for that version;
- runtime equipment binding fails closed on missing or unknown schemas and validates slot existence, item compatibility, body-layout membership, and occupancy; the legacy direct-string bootstrap fallback is not part of the target state; and
- version cutover validates surviving equipped state against the target schema and requires explicit mappings or resolution for renamed, removed, split, merged, or newly incompatible definitions.

The live implementation is partial: versioned slot/layout data and focused slot, group, and layout validation exist, but missing-schema and unknown-layout fallback remain permissive, the complete presentation and occupancy model is absent, and equipped-state cutover validation/remapping is not proved. See [ADR 0115](../../decisions/adr-0115-game-authored-equipment-layouts-with-fail-closed-publication.md).

Platform settings do not duplicate these versioned item and equipment facts. The existing inventory command capability controls whether the standard player inventory family is available for a tenant/game; slots, body layouts, compatibility, and stackability remain release-owned DML data. Future settings belong only to concrete runtime behavior policy that is neither authored content nor a canonical platform contract.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [World Editing & Customization Tools](world-editing-tools.md)
- [Version Control for Design Assets](version-control.md)
- [Game Design Service gRPC API](../../../../protos/game-design/v1/README.md)
- [Entity Management Service](../entity-management-service/README.md)
- [Ability & Action Design Tools](ability-action-tools.md)
- [Web-Based Visual Design Interface](web-visual-interface.md)
