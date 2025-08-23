# Item & Equipment Balancing Tools

Game balance relies heavily on item statistics and equipment progression. This document outlines tools for tuning those values. Balancing data follows the same revision and version publishing workflow used throughout the Game Design Service so that stats remain consistent across releases. Designers store changes via the `SaveRevision` gRPC endpoint and publish them with `PublishVersion`. These operations participate in the cross-service saga described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md). See [Version Control for Design Assets](version-control.md) for more detail.

Balancing records are scoped by `tenantId` so multiple games can maintain independent item definitions.

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
3. Finalized changes are published as part of a game version and copied to the Entity Management Service.

## 📚 Related Documentation

- [Game Design Service Architecture](README.md)
- [World Editing & Customization Tools](world-editing-tools.md)
- [Version Control for Design Assets](version-control.md)
- [Game Design Service gRPC API](../../../../protos/game-design/v1/README.md)
- [Entity Management Service](../entity-management-service/README.md)
- [Ability & Action Design Tools](ability-action-tools.md)
- [Web-Based Visual Design Interface](web-visual-interface.md)
