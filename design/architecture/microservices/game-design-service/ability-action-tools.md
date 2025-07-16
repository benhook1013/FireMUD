# Ability & Action Design Tools

This document outlines the planned editors for defining abilities, actions and combat mechanics. The tooling is part of the Game Design Service and will push finalized data to the Game Logic Service during version publishing.

> **Status: In Progress** – These tooling features are not yet implemented.

Game creators will be able to build complex combat systems without modifying the core engine. All definitions are design-time data stored with a tenant so multiple games remain isolated.

## Capabilities

- **Ability Editor** – create spell and skill definitions with cooldowns, resource costs and targeting rules. (TODO: Not yet implemented)
- **Action Sequencer** – design combos or chained actions that trigger based on events. (TODO: Not yet implemented)
- **Balancing Metrics** – display damage, healing and resource impact to help tune gameplay. (TODO: Not yet implemented)

## Workflow

1. Abilities and actions are created in the web UI and saved via `SaveRevision`. (TODO: Not yet implemented)
2. Designers can group related abilities into categories for organization. (TODO: Not yet implemented)
3. When a version is published, abilities are copied to the Game Logic Service using the `version_id`. (TODO: Not yet implemented)

## 📚 Related Documentation

- [Game Design Service Architecture](README.md)
- [World Editing & Customization Tools](world-editing-tools.md)
