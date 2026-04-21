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

Each new game maps to a tenant (`tenantId`) under the [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) design. Hosting and resource limits for that tenant are controlled by subscriptions as described in the [Subscription Management Design](./microservices/account-service/subscription-management.md).

For v1, the creator lifecycle is:

1. **Create a Draft Tenant** – A creator can create and edit a tenant before paying for production gameplay. Draft tenants support authoring and internal setup but do not expose a public production realm.
2. **Assign Roles** – `designer` authors content and publishes versions. `tenantAdmin` owns tenant runtime lifecycle for that tenant: launching the production realm, creating playtest forks, pinning script patches, initiating cutovers, and rolling back. `platformAdmin` can override these actions for platform incidents or support.
3. **Go-Live Readiness** – Before the first public production realm is started, the tenant must satisfy plan/entitlement requirements and have at least one published version ready to launch.

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
- [Game Customization](./system-architecture-game-customization.md) covers themes and branding tweaks.
- **World Editing Tools** – Use the [World Editing & Customization Tools](./microservices/game-design-service/world-editing-tools.md) for room and region editing.
- **Ability & Action Tools** – Build combat mechanics with the [Ability & Action Design Tools](./microservices/game-design-service/ability-action-tools.md).
- **Item & Equipment Balancing** – Tune gear progression in the [Item & Equipment Balancing Tools](./microservices/game-design-service/item-equipment-balancing.md).
- **Visual Interface** – A [web-based visual editor](./microservices/game-design-service/web-visual-interface.md) provides drag-and-drop editing.
- **Asset Storage** – Upload icons and sound effects via the [Asset Storage Setup](./microservices/game-design-service/asset-storage.md).
- **Version Control & Templates** – [Version Control](./microservices/game-design-service/version-control.md) and [Game Templates](./microservices/game-design-service/game-templates.md) streamline collaboration and new projects.

World and entity changes are versioned so creators can iterate safely and roll back as needed. See [Game Templates](./microservices/game-design-service/game-templates.md) for starting points.

For item and equipment authoring, creators define more than item names and stats. They also define game-specific equipment slots, optional slot groups, body-layout slot membership, item stackability, and item slot compatibility. Familiar slot names such as `HEAD` or `HAND` are content choices, not platform-global enums; a game can instead define slots such as `TAIL_RING`, `WING`, `PAW`, or `MODULE_BAY` and attach those slots only to body layouts that support them. Runtime equipment validation uses those published definitions, so a player cannot equip an item into a slot their selected character body layout does not expose.

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

1. **Publish a Version** – A `designer` or `tenantAdmin` publishes the current design in the Game Design Service.
2. **Launch the Production Realm** – A `tenantAdmin` instructs the [Game Session Service](./microservices/game-session-service/README.md) to launch the tenant's default production realm on that published version. The [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md) describes how initial world state is seeded from the published world data when a brand new world is created.
3. **Check Entitlements** – Launch fails closed unless billing and plan entitlements permit gameplay for the tenant.
4. **Open Player Admission** – Once the realm is healthy, it becomes the default production realm surfaced to players in `WORLDS` / `REALMS` / `PLAY`. In v1, this production realm is also the only realm that may be publicly discoverable to authenticated players who do not already hold tenant membership.
5. **Emergency Override** – `platformAdmin` can perform the same launch path during incident response, but creators do not depend on operators for routine tenant launches.

```plaintext
Game Design Service (publish) → Tenant Admin / Platform Admin → Game Session Service (launch realm)
```

---

## 5. Patch and Update a Live Game

1. **Iterate on Content** – Creators modify worlds, items, or rules using the [Game Design Service](./microservices/game-design-service/README.md).
2. **Publish a New Version** – The updated design is published with patch notes so players can review changes.
3. **Publish a Script Patch** – For quick fixes, the [Game Design Service](./microservices/game-design-service/README.md) emits a `scriptPatchVersion` like `v42-script.3` linked to the current version.
4. **Choose the Rollout Path**
   - **Script-only patch** – A `tenantAdmin` pins the patch to the target realm. Script-only patches apply live without replacing the realm.
   - **Non-script change** – A `tenantAdmin` creates a replacement-instance cutover to a new published version. The Game Session Service performs compatibility checks, launches the replacement instance, and atomically shifts the realm route.
5. **Player Experience During Cutover** – New admissions follow the new realm target once the cutover completes. Existing players may reconnect through the normal lobby flow if the old instance drains or disconnects them.
6. **Rollback** – A `tenantAdmin` may roll back to the previous version or script patch using the same control-plane contract. `platformAdmin` is break-glass override only.
7. **Saga Coordination** – Cross-service updates are coordinated using sagas for atomic pre-activation rollback and deterministic runtime cutover. See [Transaction Strategies](./system-architecture-transactions.md).
8. **Verify Performance** – Check metrics, traces, and rollout signals after deployment; see [Logging & Monitoring](./system-architecture-logging-monitoring.md) and [Testing Strategy](./system-architecture-testing.md).

```plaintext
Game Design Service (publish) → Tenant Admin / Platform Admin → Script Patch Pin or Replacement-Instance Cutover
```

### Example Hotfix DSL

```yaml
- action: hotfix_script
  version: "v42"
  patchVersion: "v42-script.3"
  scripts:
    - "npc-barkeep"
    - "docks-rat-encounter"
  reason: "Live AI bug fix during event"
```

Hotfixes follow the steps in the [Hotfix Procedure](./system-architecture-runbooks.md#-hotfix-procedure) to ensure minimal downtime.

Example rollout choice:

- **Use a script-patch pin** when the change is limited to automation behavior, such as fixing an NPC conversation tree or encounter trigger while keeping the same published world, entity, and asset bundle.
- **Use a replacement-instance cutover** when the change includes new rooms, altered entity templates, balance data, assets, or any other non-script content that requires a new published version to become active.

Hotfix procedures and runtime rollout steps are shared with operators for auditability and incident response; see [Testing & Continuous Delivery](./user-journeys-operators.md#testing--continuous-delivery) and [Platform Service Updates](./user-journeys-operators.md#platform-service-updates) for CI/CD details.

---

## 6. Branding and Customization

Creators adjust the look and feel of their games through the Game Design Service at design time. When a version is published, branding assets are uploaded to tenant- and version-scoped object storage and a `manifest.json` is generated. Runtime clients fetch the manifest for the bundle actually resolved at `PLAY` time, not just "the tenant in general," so production and fork realms can present different branding when they run different published builds. See [Frontend Architecture](./system-architecture-frontend.md) and [Game Customization](./system-architecture-game-customization.md) for details.

---

## 7. Playtesting & Analytics

Before launch or after major updates, creators validate changes with **forked playtest realms**:

1. **Fork a Source Realm** – A `tenantAdmin` selects a source realm, usually the live production realm, and requests a fork. The platform snapshots the source realm using the canonical v1 fork-snapshot boundary from [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md) and creates a temporary isolated playtest realm with its own `gameInstanceId`.
2. **Choose the Target Build** – The fork may run the same version as production for reproduction, or a newer `versionId` / `scriptPatchVersion` for validation against realistic state.
3. **Invite Testers** – Access is explicit. The fork uses the same platform accounts as production, but only authorized testers, creators, and operators see it in `REALMS <world>`. In v1, `tenantAdmin` manages these explicit access grants for the fork, while `platformAdmin` remains break-glass override only.
   - Example fork/playtest realms in this document assume the caller already has the required explicit realm-access grant; they are not meant to imply public discoverability for non-production realms.
   - The minimum access-grant record is `{tenantId, realmSlug, accountId, grantedByAccountId, grantedAt, expiresAt?}`.
   - Revoking the grant removes future realm visibility and admission for that account without deleting the fork itself.
   - Revocation is forward-looking for live sessions: already connected testers may finish the current fork session, but the next `PLAY`, reconnect, or fresh discovery/auth bootstrap must fail unless a new grant exists.
4. **Collect Feedback** – Feedback is collected per the [Playtesting & Feedback](../project-management/slice-support/playtesting-feedback.md) flow and correlated with the fork realm in analytics.
5. **Reset or Expire the Fork** – Forks are time-bounded and may be reset repeatedly from source snapshots during an iteration cycle. Runtime writes remain isolated to the fork and never merge back into production automatically.
6. **Promote by Normal Launch/Cutover** – Successful playtests inform a normal production rollout; there is no direct "promote this fork" merge path for runtime state.

Common fork use cases:

- **Reproduce the current live problem** – Fork the current production realm on the same `versionId` and `scriptPatchVersion` to reproduce a bug against copied live gameplay state without risking the public realm.
- **Validate an upcoming release** – Fork the current production realm but launch the fork on a newer `versionId` or `scriptPatchVersion` so testers can evaluate the new build against realistic copied state before the production cutover.

Fork lifecycle choices:

- **Reset an existing fork** – Reuse the same playtest realm identity, but replace its fork-local gameplay state with a fresh application of the chosen source snapshot. Use this when the same tester group and fork purpose remain valid across iterations.
- **Create a new fork** – Create a new playtest realm with a new identity and fresh visibility/access configuration. Use this when the next test cycle needs a separate audience, separate audit history, or side-by-side comparison with another fork.

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
- [Game Customization](./system-architecture-game-customization.md)
- [Game Templates](./microservices/game-design-service/game-templates.md)
- [Modding Framework](./microservices/game-design-service/modding-framework.md)
- [Playtesting & Feedback](../project-management/slice-support/playtesting-feedback.md)
- [Procedural Generation](./system-architecture-procedural-generation.md)
- [Scripting & Automation Framework](./system-architecture-scripting.md)
- [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)
