# Ability & Action Design Tools

This document outlines the editors for defining abilities, actions, and combat mechanics. The tooling is part of the Game Design Service and pushes finalized data to the [Game Logic Service](../game-logic-service/README.md) during version publishing.

The publish workflow is part of the cross‑service saga described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).

Data entered in these editors is stored as revisions using the `SaveRevision` gRPC call defined in [`game_design_service.proto`](../../../../protos/game-design/v1/game_design_service.proto).
Finalized versions are published with `PublishVersion` so the Game Logic Service can load the rules as part of the cross‑service saga described in the [Game Design Service Architecture](README.md).

Ability definitions use a structured schema delivered through the API.

Game creators build complex combat systems without modifying the core engine. All definitions are design-time data stored with a tenant so multiple games remain isolated.

The web-based editor described in [Web-Based Visual Design Interface](web-visual-interface.md) serves as the front end for these tools.

## Capabilities

- **Ability Editor** – create spell and skill definitions with cooldowns, resource costs, and targeting rules.
- **Action Sequencer** – design combos or chained actions that trigger based on events.
- **Balancing Metrics** – display damage, healing, and resource impact to help tune gameplay.
- **Integration with [Item & Equipment Balancing](item-equipment-balancing.md)** – ability damage values can be compared against item statistics for overall balance.

## Workflow

1. Abilities and actions are created in the web UI and saved via `SaveRevision`.
2. Designers can group related abilities into categories for organization.
3. Each revision becomes part of a published version through `PublishVersion`.
4. When a version is published, abilities are copied to the Game Logic Service using the `version_id`.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [World Editing & Customization Tools](world-editing-tools.md)
- [Web-Based Visual Design Interface](web-visual-interface.md)
- [Game Design Service gRPC API](../../../../protos/game-design/v1/README.md)
- [Version Control for Design Assets](version-control.md)
