# Item & Equipment Balancing Tools

Game balance relies heavily on item statistics and equipment progression. This document outlines tools for tuning those values. Balancing data follows the same revision and version publishing workflow used throughout the Game Design Service so that stats remain consistent across releases. Designers store changes via the `SaveRevision` gRPC endpoint and publish them with `PublishVersion`. These operations participate in the cross-service saga described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md). See [Version Control for Design Assets](version-control.md) for more detail.

Balancing records are scoped by `tenantId` so multiple games can maintain independent item definitions. They are stored as versioned template rows keyed by `(tenantId, versionId)` and published alongside other entity definitions so runtime services always load a consistent, immutable template for the active version.

These capabilities are available in the current implementation.

## Features

- **Item Stat Editor** – adjust damage, defense, and stat bonuses with real‑time validation.
- **Equipment Curves** – visualize power growth across item tiers to spot outliers.
- **Economy Impact** – preview vendor prices and drop rates to maintain a healthy in-game economy.
- **Integration with [Ability & Action Design Tools](ability-action-tools.md)** – compare ability damage values with item stats for holistic balance.
- **Web-Based Interface** – edit and visualize stats through the drag‑and‑drop UI described in [Web-Based Visual Design Interface](web-visual-interface.md).

## Workflow

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

## Related Documentation

- [Game Design Service Architecture](README.md)
- [World Editing & Customization Tools](world-editing-tools.md)
- [Version Control for Design Assets](version-control.md)
- [Game Design Service gRPC API](../../../../protos/game-design/v1/README.md)
- [Entity Management Service](../entity-management-service/README.md)
- [Ability & Action Design Tools](ability-action-tools.md)
- [Web-Based Visual Design Interface](web-visual-interface.md)
