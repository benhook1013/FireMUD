# FireMUD User Journeys: Creators

This guide summarizes typical workflows for game creators using FireMUD. It focuses on flows such as game creation, world design, scripting, publishing, and live updates. Each numbered step links to the microservice or design document that manages that portion of the flow. Use it alongside the [Architecture Overview](./README.md), the [System Architecture Overview](./system-architecture-overview.md), the [System Architecture Diagram](./system-architecture-diagram.md), and the [System Context Diagram](./system-context-diagram.md).

For other personas, see:

- [Player Journeys](./user-journeys-players.md)
- [Operator Journeys](./user-journeys-operators.md)
- [User Journeys Hub](./user-journeys.md)

## Table of Contents

- [Goals](#goals)
- [Quick Reference](#quick-reference)
- [1. Game Creation](#1-game-creation)
- [2. World and Entity Design](#2-world-and-entity-design)
- [3. Add Automation & Scripting](#3-add-automation--scripting)
- [4. Publish and Start a Game Instance](#4-publish-and-start-a-game-instance)
- [5. Patch and Update a Live Game](#5-patch-and-update-a-live-game)
- [6. Branding and Customization](#6-branding-and-customization)
- [7. Playtesting & Analytics](#7-playtesting--analytics)
- [8. Extensibility & External Tools](#8-extensibility--external-tools)
- [Related Documentation](#related-documentation)

---

## Goals

- Describe creator-centric flows from initial game setup through live operations.
- Map each step to the microservices and tools used by creators.
- Connect creator flows to player and operator journeys without duplicating details.

---

## Quick Reference

- [Game Creation](#1-game-creation) – Start a new game project.
- [World and Entity Design](#2-world-and-entity-design) – Build worlds, entities, and content.
- [Add Automation & Scripting](#3-add-automation--scripting) – Add behaviors and scripted logic.
- [Publish and Start a Game Instance](#4-publish-and-start-a-game-instance) – Launch playable game instances.
- [Patch and Update a Live Game](#5-patch-and-update-a-live-game) – Ship updates and hotfixes.
- [Branding and Customization](#6-branding-and-customization) – Configure visual identity and theme.
- [Playtesting & Analytics](#7-playtesting--analytics) – Iterate using tests, feedback, and telemetry.
- [Extensibility & External Tools](#8-extensibility--external-tools) – Integrate external tools and plugins.

Account creation and login flows are covered in the [Player Journeys](./user-journeys-players.md#1-sign-up). Deployment, CI/CD, and platform upgrades are covered in the [Operator Journeys](./user-journeys-operators.md).

---

## 1. Game Creation

After signing up, creators start a new project using the [Game Design Service](./microservices/game-design-service/README.md).

```plaintext
Account Service (user) → Game Design Service (new game)
```

---

## 2. World and Entity Design

Creators refine the world and its inhabitants using several services:

- **[Game Design Service](./microservices/game-design-service/README.md)** – Provides versioned templates, ability editors, and runtime flag definitions.
- **[World Management Service](./microservices/world-management-service/README.md)** – Stores zones and maps, generates new areas, and maintains pathfinding data. Scheduled world events notify other services when the environment changes.
- **[Entity Management Service](./microservices/entity-management-service/README.md)** – Manages characters, NPCs, items, and inventory with deferred writes coordinated by the Game Session Service.
- **Procedural Generation** – The [Automation & Scripting Service](./microservices/automation-scripting-service/README.md) provides dungeon seeds and templates. See [Procedural Generation](./system-architecture-procedural-generation.md).
- **MCP-Enhanced Clients** – Use the [Mud Client Protocol](./system-architecture-mud-client-protocol.md) to drive rich Telnet client features such as status panels, maps, and background notifications.
- [Game Customization Options](./game-customization-options.md) covers themes and branding tweaks.
- **World Editing Tools** – Use the [World Editing & Customization Tools](./microservices/game-design-service/world-editing-tools.md) for room and region editing.
- **Ability & Action Tools** – Build combat mechanics with the [Ability & Action Design Tools](./microservices/game-design-service/ability-action-tools.md).

World and entity changes are versioned so creators can iterate safely and roll back as needed. See [Game Templates](./microservices/game-design-service/game-templates.md) for starting points.

---

## 3. Add Automation & Scripting

Dynamic behavior is implemented via the [Automation & Scripting Service](./microservices/automation-scripting-service/README.md):

- Script quests and NPC routines.
- Trigger world events in response to player actions.
- See [Scripting & Automation Framework](./system-architecture-scripting.md) for details on the component-based DSL and sandboxing model.
- [Modding Framework](./microservices/game-design-service/modding-framework.md) enables runtime plugins using the same scripting sandbox.

---

## 4. Publish and Start a Game Instance

Once the world is ready:

1. **Publish a Version** – Creators publish the current design in the Game Design Service.
2. **Start a Game Instance** – The [Game Session Service](./microservices/game-session-service/README.md) launches a live instance using that published version. The [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md) describes how initial world state is seeded from the published world data when a brand new world is created. Cross-service steps are orchestrated with **sagas** to ensure consistency. For the full rollout process, see [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

```plaintext
Game Design Service (publish) → Game Session Service (start instance)
```

---

## 5. Patch and Update a Live Game

1. **Iterate on Content** – Creators modify worlds, items, or rules using the [Game Design Service](./microservices/game-design-service/README.md).
2. **Publish a New Version** – The updated design is published with patch notes so players can review changes.
3. **Publish a Script Patch** – For quick fixes, the [Game Design Service](./microservices/game-design-service/README.md) emits a `scriptPatchVersion` like `v42-script.3` linked to the current version.
4. **Restart Game Instance** – Administrators instruct the [Game Session Service](./microservices/game-session-service/README.md) to load the new `version_id` when a full update is required. Script-only patches are applied live without restarting.
5. **Saga Coordination** – Cross-service updates are coordinated using sagas for atomic rollbacks. See [Transaction Strategies](./system-architecture-transactions.md).
6. **Verify Performance** – Check metrics after deployment; see [Performance Optimization Guidelines](./performance-optimization.md).

```plaintext
Game Design Service (publish) → Game Session Service (restart)
```

Hotfix procedures and runtime rollout steps are shared with operators; see [Testing & Continuous Delivery](./user-journeys-operators.md#testing--continuous-delivery) and [Platform Service Updates](./user-journeys-operators.md#platform-service-updates) for CI/CD details.

---

## 6. Branding and Customization

Creators adjust the look and feel of their games through the Game Design Service at design time. When a version is published, branding assets are uploaded to tenant- and version-scoped object storage and a `manifest.json` is generated. Runtime clients fetch this manifest—not the Game Design Service—to load logos, favicons, and theme overrides before applying them in the UI. See [Frontend Architecture](./system-architecture-frontend.md) and [Game Customization Options](./game-customization-options.md) for details.

---

## 7. Playtesting & Analytics

Before launch or after major updates, creators invite testers to staged environments. Feedback is collected per the [Playtesting & Feedback](../project-management/playtesting-feedback.md) and telemetry is reviewed using the [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md).

---

## 8. Extensibility & External Tools

Creators extend gameplay using external editors and runtime plugins:

1. **Mud Client Protocol** – The [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) negotiates MCP so advanced clients can open auxiliary panes, structured notifications, and specialized views while keeping the main text protocol compatible with plain Telnet. See [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md).
2. **Modding Framework** – Plugins packaged through the [Game Design Service](./microservices/game-design-service/modding-framework.md) inject custom logic at runtime. The [Automation & Scripting Service](./microservices/automation-scripting-service/README.md) executes them in a sandbox.

```plaintext
MCP-Aware Client → TCP Proxy Service → Game Session Service and other backend services
```

---

## Related Documentation

- [Analytics Dashboards](./microservices/logging-admin-service/analytics-dashboards.md)
- [Game Creator Guide](../user-guides/game-creator-guide.md)
- [Game Customization Options](./game-customization-options.md)
- [Game Templates](./microservices/game-design-service/game-templates.md)
- [Modding Framework](./microservices/game-design-service/modding-framework.md)
- [Playtesting & Feedback](../project-management/playtesting-feedback.md)
- [Procedural Generation](./system-architecture-procedural-generation.md)
- [Scripting & Automation Framework](./system-architecture-scripting.md)
- [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)
