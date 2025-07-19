# Item & Equipment Balancing Tools

Game balance relies heavily on item statistics and equipment progression. This document outlines the planned tools for tuning those values. Balancing data will eventually follow the same revision and version publishing workflow used throughout the Game Design Service so that stats remain consistent across releases. Designers will store changes via the `SaveRevision` gRPC endpoint and publish them with `PublishVersion`. These operations will participate in the cross-service saga described in [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md). See [Version Control for Design Assets](version-control.md) for more detail. (TODO: Not yet implemented)

Balancing records are scoped by `tenantId` so multiple games can maintain independent item definitions. (TODO: Not yet implemented)

All capabilities described below are planned features and are not yet available in the current implementation. (TODO: Not yet implemented)

> **Status: In Progress** – These balancing tools are still under development. (TODO: Not yet implemented)

## Features

- **Item Stat Editor** – adjust damage, defense, and stat bonuses with real‑time validation. (TODO: Not yet implemented)
- **Equipment Curves** – visualize power growth across item tiers to spot outliers. (TODO: Not yet implemented)
- **Economy Impact** – preview vendor prices and drop rates to maintain a healthy in-game economy. (TODO: Not yet implemented)
- **Integration with [Ability & Action Design Tools](ability-action-tools.md)** – compare ability damage values with item stats for holistic balance. (TODO: Not yet implemented)
- **Web-Based Interface** – edit and visualize stats through the planned drag‑and‑drop UI described in [Web-Based Visual Design Interface](web-visual-interface.md). (TODO: Not yet implemented)

## Workflow

1. Items and equipment are defined through the Entity Designer (see [World Editing & Customization Tools](world-editing-tools.md)). (TODO: Not yet implemented)
2. Balancing views aggregate stats and show graphs for cost vs. power. (TODO: Not yet implemented)
3. Finalized changes are published as part of a game version and copied to the Entity Management Service. (TODO: Not yet implemented)

## 📚 Related Documentation

- [Game Design Service Architecture](README.md)
- [World Editing & Customization Tools](world-editing-tools.md)
- [Version Control for Design Assets](version-control.md)
- [Game Design Service gRPC API](../../../../protos/game-design/v1/README.md)
- [Entity Management Service](../entity-management-service/README.md)
- [Ability & Action Design Tools](ability-action-tools.md)
- [Web-Based Visual Design Interface](web-visual-interface.md)
