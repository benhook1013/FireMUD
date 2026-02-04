# Ability & Action Design Tools

This document outlines the editors for defining abilities, actions, and combat mechanics. The tooling is part of the Game Design Service and pushes finalized data to the [Game Logic Service](../game-logic-service/README.md) during version publishing.

The publish workflow is part of the cross‑service saga described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).

Data entered in these editors is stored as revisions using the `SaveRevision` gRPC call defined in [`game_design_service.proto`](../../../../protos/game-design/v1/game_design_service.proto).
Finalized versions are published with `PublishVersion` so the Game Logic Service can load the rules as part of the cross‑service saga described in the [Game Design Service Architecture](README.md).

Ability definitions use a structured schema delivered through the API.

Game creators build complex combat systems without modifying the core engine. All definitions are design-time data stored with a tenant so multiple games remain isolated.

The web-based editor described in [Web-Based Visual Design Interface](web-visual-interface.md) serves as the front end for these tools.

## Integration with the Scripting DSL

Abilities and actions defined through these tools can participate in scripted behavior:

- The scripting DSL exposes components that can reference abilities and action sequences (for example, nodes that conceptually “cast ability X” or “trigger action sequence Y”). These nodes emit commands into the tick system rather than bypassing Game Logic.
- From the scripting engine’s perspective, invoking an ability is just another domain command; it is subject to the same per-entity, per-tick fairness rules and idempotency guarantees described in `design/architecture/system-architecture-ticks.md` and `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`.
- Script-side quotas and budgets, as described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`, indirectly cap ability-heavy behaviors (for example, scripts that attempt to spam abilities) by limiting how often the relevant script handlers may run and how many commands they may produce.

### Version Pinning with Scripts and Plugins

At runtime, a published game version ties together:

- Ability/action definitions owned by the Game Logic Service.
- Script and plugin patch versions owned by the Automation & Scripting Service.

Within a given published game version:

- Ability schemas and identifiers are treated as **stable** for the lifetime of that version.
- Script-only and plugin-only patch versions may evolve independently, but they must not rely on incompatible ability schemas; breaking changes to abilities require a new game version publish as described in `design/architecture/system-architecture-versioning-runtime.md`.

Scripts and plugins may safely reference abilities by identifier within a game version and across script-only or plugin-only patches, but they should not assume that those identifiers remain valid across **different game versions**. Versioning runtime docs describe how these assets are pinned and how rollbacks behave when a game version is reverted.

The Game Design Service’s publish Saga is responsible for enforcing these rules at **design time**: during `PublishVersion`, it verifies that all ability identifiers referenced by scripts and plugins targeting a given `(tenantId, versionId)` exist and are compatible with the ability schema for that version. If mismatches are detected, the Saga marks the version as failed in Game Design’s own status model (for example, `PUBLISH_FAILED_DESIGN`) and does **not** hand the corresponding `scriptPatchVersion` to the Automation & Scripting Service. As a result, no `<tenantId, scriptPatchVersion>` lifecycle row is created and the patch never enters `PENDING_VALIDATION` / `ONLOAD_RUNNING` / `READY` on the runtime side; runtime handlers therefore never execute against missing or incompatible abilities.

From an observability perspective, script and plugin invocations that exercise abilities are recorded in `script_event_audit` with a `scriptEventId` that you can use to correlate designer-facing events with automation metrics and downstream tick effects, following the patterns in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.

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
