# Ability & Action Design Tools

This document outlines the planned editors for defining abilities, actions and combat mechanics.

Game creators can build complex combat systems without modifying the core engine. All definitions are design-time data stored with a tenant so multiple games remain isolated.

## Capabilities

- **Ability Editor** – create spell and skill definitions with cooldowns, resource costs and targeting rules.
- **Action Sequencer** – design combos or chained actions that trigger based on events.
- **Balancing Metrics** – display damage, healing and resource impact to help tune gameplay.

## Workflow

1. Abilities and actions are created in the web UI and saved via `SaveRevision`.
2. Designers can group related abilities into categories for organization.
3. When a version is published, abilities are copied to the Game Logic Service using the `version_id`.

## 📚 Related Documentation

- [Game Design Service Architecture](README.md)
- [World Editing & Customization Tools](world-editing-tools.md)
