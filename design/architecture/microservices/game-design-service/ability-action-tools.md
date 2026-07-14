# Ability & Action Design Tools

This document outlines the editors for defining abilities, actions, and combat mechanics. The tooling is part of the Game Design Service and pushes finalized data to the [Game Logic Service](../game-logic-service/README.md) during version publishing.

The publish workflow is part of the durable control-plane workflow described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).

Data entered in these editors is stored as revisions using the `SaveRevision` gRPC call defined in [`game_design_service.proto`](../../../../protos/game-design/v1/game_design_service.proto).
Finalized versions are published with `PublishVersion` so the Game Logic Service can load the rules as part of the cross-service publish workflow described in the [Game Design Service Architecture](README.md).

Ability definitions use a structured schema delivered through the API.

Game creators build complex combat systems without modifying the core engine. All definitions are design-time data stored with a tenant so multiple games remain isolated.

The web-based editor described in [Web-Based Visual Design Interface](web-visual-interface.md) serves as the front end for these tools.

## Canonical Ability References

Published ability and action references use stable design identifiers, not display names. Ability definitions must expose an `abilityId` stable within `(tenantId, versionId)`, and action sequences must expose an `actionSequenceId` stable within the same scope. Designer-facing names may change across revisions, but scripts, plugins, and validation records must store the stable identifiers.

Any script or plugin field that references an ability must use a typed selector such as `{"abilityId":"abilityId:<stable-id>"}`; action sequence references must use `{"actionSequenceId":"actionSequenceId:<stable-id>"}`. Game Design validates these selectors against the exact `baseVersionId` and its recorded `abilitySchemaDigest` during `PublishVersion`, `PublishScriptPatchVersion`, and `PublishPluginVersion`. Runtime services must not reinterpret display names or aliases as ability identities.

## Authored Actor-State Catalog

Every published game version includes a versioned actor-state catalog. It is DML-authored design data, not service configuration: the platform supplies a small typed grammar while each game supplies all named stats, resources, conditions, tags, presentation values, and effect declarations.

- Stat definitions use a stable `statKey`, one primitive kind (`NUMERIC`, `BOUNDED_RESOURCE`, or `BOOLEAN_FLAG`), base/default value, applicable hard bounds, visibility/presentation metadata, and tags.
- Condition definitions use a stable `conditionKey`, explicit stacking and duration policy, visibility/presentation metadata, tags, and typed effect declarations.
- Typed effects reference catalog keys. Abilities, action sequences, equipment, consumables, room effects, scripts, and later combat all use this one declaration grammar rather than creating private stat maps or platform-specific names such as `health` or `fire_resistance`.

Game Design validates every reference and effect declaration at publish time, includes the immutable catalog in the release bundle, and records its digest with the release. Runtime services resolve definitions only from the game instance's pinned release catalog; unknown or stale keys fail closed. Entity Management stores active runtime state separately, but an active condition retains its source, definition key, release identity, and immutable applied-effect snapshot so a later publish cannot rewrite an effect that is already running.

This provides a fully game-authored, scriptable design surface while keeping enough platform type information for deterministic validation and evaluation. See [Entity Stats and Conditions](../../../project-management/vertical-slices/07-task-list-entity-stats-and-conditions-vertical-slice.md) for the runtime ownership and evaluation contract.

## Actor Disposition and Continuous Overlay Policy

An actor's DML-authored `ActorDisposition` is the baseline for action admission, targetability, visibility, and semantic feedback. `CONTINUOUS` condition, equipment, stance, and aura effects may narrow that baseline only; their typed policy modifiers can deny or reduce what the disposition admits, but cannot grant a capability, targetability, or visibility that the main disposition denies.

Games author the restrictive overlays and their condition/application eligibility as ordinary release DML. Recovery, immunity, revival, or an exceptional state change is never inferred from a continuous item. It uses an explicit `INSTANT` operation: remove or prevent the restrictive condition, or transition the actor's persisted disposition. This preserves one lifecycle owner for defeat/death-like states while allowing each game to author its own recovery rules.

## Action Target Declarations

Published actions declare a typed targeting mode and DML-authored targeting policy. The platform grammar begins with `SELF` and `DIRECT_ACTOR` and may add room/area modes later; it does not contain game-specific range, faction, visibility, or eligibility rules.

An action declaration supplies the target filters, range/scope constraints, tags, visibility/targetability requirements, and optional-target outcome policy for its selected mode. Game Logic resolves that declaration at execution time into a `ResolvedEffectPlan` using canonical actor identity and World Management occupancy. The release declaration and final target set are frozen into the plan before Entity Management applies effects, so target text, display names, or mutable design rows cannot be reinterpreted during mutation.

## Resource Cost and Cooldown Declarations

Published actions declare `costs[]` and `cooldowns[]` as typed DML. Each cost references a declared bounded-resource `statKey`; each cooldown references a stable author-defined `cooldownKey`. A declaration also chooses a typed commit policy: `ON_EXECUTION` after required validation and target resolution, or `ON_EFFECT_SUCCESS` after required effect application.

Game Logic and Entity Management execute this immutable release declaration under the durable effect id. Queue admission does not consume resources or start a cooldown. Entity Management atomically checks source-actor availability, applies committed costs and same-region effects, and records durable actor cooldown state. A later cross-region leg failure is an explicit action outcome, not an implicit refund; refund behavior requires a future explicit authored declaration.

## Action Feedback Declarations

Published actions define semantic feedback declarations as versioned DML. Each declaration identifies an outcome event, audience role, message key/template, typed argument contract, visibility classification, and replay policy. Audience roles such as source actor, direct target, and authorized observer are resolved by Game Logic from the action outcome; display text is rendered later by Game Session.

The resulting `GameplayPresentationEvent` is idempotent and tied to the action effect lifecycle, so replay cannot duplicate feedback and late remote completion can emit a distinct final event. Game-authored feedback cannot override platform validation, authorization, or infrastructure error codes.

## Effect Declaration Lifecycle

One typed `EffectDeclaration` grammar serves equipment, conditions, actions, and future auras. Its `lifecycle` prevents a declarative effect from being interpreted differently at each runtime seam.

- `CONTINUOUS` declarations attach to a source such as an equipped item, active condition, stance, or aura and contribute to evaluated actor state for as long as that source exists. They use derived-state modifiers such as `ADD`, `MULTIPLY`, clamps, and granted state.
- `INSTANT` declarations execute once through the `ResolvedEffectPlan` under its idempotent effect id. The initial mutation grammar is `ADJUST_RESOURCE`, `APPLY_CONDITION`, and `REMOVE_CONDITION`; a later `TRANSITION_DISPOSITION` operation owns explicit defeat, recovery, revival, or other main-state changes.
- Equipment may use both modes: passive worn effects are continuous, while a future on-equip/on-unequip trigger is instant. This is not an equipment-specific side channel.
- Resource cost declarations remain conditional debits, separate from generic adjustment. Damage is deferred to the combat pipeline so hit, mitigation, and defeat semantics are not bypassed by a generic resource delta.

### Capacity Changes From Continuous Sources

A bounded-resource maximum is a typed field of the resource identified by `statKey`; the platform never introduces separate magic keys such as `max_health`. When a `CONTINUOUS` declaration from equipment, a condition, a spell-backed aura, or another attached source changes that field, Entity Management performs one durable normalization at the attach, detach, refresh, expiry, or replacement transition. Evaluating effective state remains read-only.

The ordinary normalization policy is the resolved `actorState.capacityChangePolicy` tenant/game setting. Its platform grammar is deliberately small: `CLAMP_ONLY` leaves current value unchanged except for the new bounds; `PRESERVE_RATIO` keeps the current-to-maximum ratio; and `PRESERVE_DEFICIT` keeps the distance below maximum. A continuous declaration that changes a maximum may carry an optional `capacityChangePolicy` override. That override applies only to the maximum delta caused by that declared source, not as a global change to the game setting or to unrelated sources. An intentional heal, drain, or restoration remains a separate `INSTANT` resource mutation rather than an accidental side effect of raising capacity.

When one lifecycle transition applies multiple maximum-changing declarations, Game Design's published declaration order is the deterministic execution order. Entity Management carries the resource current/maximum result forward one declaration at a time, using each declaration's override when supplied and the effective tenant/game setting otherwise. The idempotent transition records the resolved policy and resulting resource state, so replay cannot produce a different current value after a setting change.

Resource-floor transitions and actor dispositions follow the same rule: a starter profile may supply common `health`, defeat, or recovery behavior as ordinary DML, but no action or runtime service may assume that a particular resource key means death. Games may replace or remove those imported declarations before publishing.

## Condition Application and Cure Declarations

Condition definitions choose one DML-authored reapplication policy: `REPLACE`, `REFRESH`, `STACK`, or `PARALLEL`. They also declare duration behavior (reset, extend, or preserve longer expiry), and stacked conditions declare a maximum stack count. Runtime callers do not infer these rules from condition names or source identifiers.

`REMOVE_CONDITION` declarations use typed selectors for an exact key, authored condition tag, or permitted source. Tag removals use the definition's authored removal priority and a stable instance-id tie-breaker. This lets starter profiles provide ordinary poison, bleed, shield, stance, and cure mechanics while games replace them with their own DML without custom mutation handlers.

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

Required publication/readiness separation:

- Game Design must expose immutable design-time read surfaces for these artifacts (for example `GetPublishedScriptPatchVersion` and `GetPublishedPluginVersion`) that report design acceptance state, `baseVersionId`, and `abilitySchemaDigest`.
- Automation & Scripting runtime reads (`GetScriptPatchStatus`, `GetPluginStatus`) remain separate and must report tenant readiness / instance activation state rather than re-encoding design-time acceptance.
- Operator and creator tooling that needs a complete picture must join the design-time publication read with the runtime readiness/activation read instead of inventing a single blended status field.

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
