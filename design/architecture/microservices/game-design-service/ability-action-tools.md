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
- Runtime output ceilings also apply here: a single handler firing must stay within explicit per-run command-count and work-item-size budgets, so ability-heavy graphs with excessive bounded fan-out must be rejected at validation time or fail as output-budget violations at runtime.

Example: if an `onInterval` combat-support handler can branch into at most eight "cast ability" actions plus one follow-up emote, Game Design must validate that this bounded fan-out fits within `maxCommandsPerRun` and `maxSerializedWorkItemBytes`. If a graph revision increases the bounded fan-out beyond those ceilings, the patch should be rejected at publish time rather than relying on repeated runtime output-budget failures after deployment.

### Version Pinning with Scripts and Plugins

At runtime, a published game version ties together:

- Ability/action definitions owned by the Game Logic Service.
- Script and plugin patch versions owned by the Automation & Scripting Service.

Within a given published game version:

- Ability schemas and identifiers are treated as **stable** for the lifetime of that version.
- Script-only and plugin-only patch versions may evolve independently, but they must not rely on incompatible ability schemas; breaking changes to abilities require a new game version publish as described in `design/architecture/system-architecture-versioning-runtime.md`.

Scripts and plugins may safely reference abilities by identifier within a game version and across script-only or plugin-only patches, but they should not assume that those identifiers remain valid across **different game versions**. Versioning runtime docs describe how these assets are pinned and how rollbacks behave when a game version is reverted.

The Game Design Service’s publish workflows are responsible for enforcing these rules at **design time**:

- During `PublishVersion`, it verifies that all ability identifiers referenced by scripts and plugins targeting a given `(tenantId, versionId)` exist and are compatible with the ability schema for that version.
- During `PublishScriptPatchVersion` and plugin bundle publication/enablement, it must re-validate that the patch/plugin remains compatible with the pinned `baseVersionId` (the underlying published game version) and its ability schema. Script-only and plugin-only patches must not introduce new dependencies that require a new `versionId`.
- Compatibility checks must be bound to an immutable `abilitySchemaDigest` associated with `baseVersionId`. Patch/plugin validation must use that digest snapshot, and the same digest must be recorded in publish metadata so validation cannot drift due to mutable schema reads.
- For plugins, compatibility is exact: one `pluginVersionId` targets one `baseVersionId` and one `abilitySchemaDigest`. If a creator needs the same logical plugin on a different game version, they must publish a new plugin version rather than relying on an implicit “compatible version” rule.

If mismatches are detected, Game Design fails the relevant design-time operation and does **not** hand incompatible content to the runtime:

- For script patches, the publish attempt is recorded as a design-time failure (for example `PUBLISH_FAILED_DESIGN`), no `<tenantId, scriptPatchVersion>` lifecycle row is created in Automation & Scripting, and the patch never enters `PENDING_VALIDATION` / `ONLOAD_RUNNING` / `READY`.
- For plugins, the bundle version remains in a non-published design-time status such as `VALIDATION_FAILED_DESIGN`, and runtime activation APIs must reject it as not eligible for activation.

In both cases, runtime handlers therefore never execute against missing or incompatible abilities.

From an observability perspective, script and plugin invocations that exercise abilities are recorded in `script_event_audit` with a `scriptEventId` that you can use to correlate designer-facing events with logs/traces and downstream tick effects; aggregate automation metrics should be used at the `scriptId` / `eventType` / `tenantId` level rather than per `scriptEventId`, following the patterns in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.

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
