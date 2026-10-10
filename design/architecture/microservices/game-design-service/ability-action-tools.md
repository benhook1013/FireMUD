# Ability & Action Design Tools

This document outlines Game Design's authored ability, action and combat definitions. Game Design owns their authoring and revision history; Game Logic owns validation and immutable retention of the exact selected gameplay-rule inputs under its [selected-release intake contract](../game-logic-service/runtime-and-data.md#selected-release-rule-inputs-and-intake). That mutation-authorized source handoff precedes the read-only publication digest gate. Game Logic is not a mutation participant in the publication workflow, and a digest read never creates or repairs its retained source.

The publish workflow is part of the durable control-plane workflow described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).

## Implementation Status

The current proven behavior is stable-reference validation against the exact published base version for the plugin publication path. `abilitySchemaDigest` is persisted in plugin metadata and exposed by the publication read, but dedicated Game Logic-owned release-attested ability-schema validation remains target-state and unproved. Current plugin publication and activation compare the plugin's `abilitySchemaDigest` with the published `AUTOMATION_SCRIPTING` aggregate participant digest; that aggregate comparison is not a dedicated ability-schema attestation. Script-patch publication does not currently enforce same-tenant published-base identity, and launch resolution does not currently prove same-base published patch identity or Automation `READY`; those are implementation gaps. Exact `scriptPinEpoch` propagation and same-version old-epoch rejection at runtime handoff are target-state and remain unproved; this design-time editor does not establish that runtime proof. See the [Automation and Scheduler Runtime tracker](../../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#script-transition-reconciliation) and [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).

The current `SaveRevision` API in [`game_design_service.proto`](../../../../protos/game-design/v1/game_design_service.proto) stores revision data; its supported `COMMAND_DEFINITION` shape and selected command-source capture do not implement the complete ability/action catalogs, policies or targeting declarations below. In particular, its existing `actionTags` are not the dedicated `admissionTags` contract. The construction includes a separate typed `GAMEPLAY_RULE` source, actual fresh-Version inventory creation, coordinated UPSERT/DELETE history and immutable synchronized snapshots. These components do not establish a registered authenticated authoring ingress or complete Game Logic intake. Generic `SaveRevision` rejects `GAMEPLAY_RULE` rather than bypassing the authorized coordinator. Any retained generic `GAMEPLAY_RULE` rows for that exact tenant/version, or nonempty legacy effective `COMMAND_DEFINITION` inventory, deny the new complete-source handoff until explicitly reconciled. Missing storage or unsupported declarations cannot establish an empty rule set. `PublishVersion` must consume independently retained owner evidence rather than populate Game Logic through the digest read.

Ability definitions use a structured schema delivered through the API.

Game creators build complex combat systems without modifying the core engine. All definitions are design-time data stored with a tenant so multiple games remain isolated.

The web-based editor described in [Web-Based Visual Design Interface](web-visual-interface.md) serves as the front end for these tools.

## Selected Rule-Source Authoring Schema

`gameplay-rule-manifest/v1` is the closed declaration grammar for selected-source production. Its root has exactly `schema` and `families`. The inventory contains an explicit array for each family below, including empty arrays. An omitted family is invalid. Each definition uses its typed logical key; display names and private database keys are never substitutes.

| Family | Typed definition members |
| --- | --- |
| `STATS` | `statKey`, `primitiveKind` (`NUMERIC` or `BOOLEAN_FLAG`), `baseValue`, `flagValue`, `minimum`, `maximum`, `visibility`, `tags` |
| `RESOURCES` | `statKey`, `defaultCurrent`, `minimum`, `defaultMaximum`, `hardMaximum`, `visibility`, `tags`; this is the `BOUNDED_RESOURCE` primitive |
| `CONDITIONS` | `conditionKey`, `reapplication`, `durationPolicy`, `durationTicks`, `maximumStacks`, `visibility`, `tags`, ordered `effectKeys`, `removalPriority` |
| `EFFECTS` | `effectKey`, `lifecycle`, `operation`, `statKey`, `statField`, `amount`, `conditionKey`, `capacityChangePolicy` |
| `ABILITIES` | `abilityId`, `name`, `actionSequenceId` |
| `ACTIONS` | `actionSequenceId`, `name`, ordered `admissionTags`, `targetSets`, `costs`, `cooldowns`, `effects`, `feedbackKeys` |
| `COMMANDS` | `commandId`, `actionSequenceId`, `semanticOwner`, `executionDiscipline`, `stageRequirement`, `promptPolicy`, `actionCategory`, `historyRecordable`, `aliases`, `actionTags`, ordered `admissionTags` |
| `ADMISSION_TAGS` | `tagKey` |
| `DISPOSITIONS` | `dispositionKey`, `deniedAdmissionTags`, `safeFeedbackKey` |
| `CONTINUOUS_OVERLAYS` | `overlayKey`, `eligibleWhen`, `deniedAdmissionTags`, ordered `effectKeys` |
| `OBSERVATION_POLICIES` | `observationPolicyKey`, `observableWhen` |
| `TARGETING_POLICIES` | `targetingPolicyKey`, `candidateSelector`, `observationPolicyKey`, `eligibleWhen`, `safeFailureFeedbackKey` |
| `SELECTION_POLICIES` | `targetSelectionPolicyKey`, `cardinality`, `minTargets`, `maxTargets`, `strategy`, `truncationStrategy`, ordered `comparators` |
| `DEFAULT_BINDINGS` | `bindingKey`, ordered `targetSets` |
| `FEEDBACK` | `feedbackKey`, `outcomeEvent`, `audience`, `messageKey`, `messageTemplate`, ordered `arguments`, `visibility`, `replayPolicy` |

All members are explicit. A member inapplicable to a discriminated declaration is JSON `null`, not an inferred semantic default. A numeric stat requires `baseValue` and may declare bounds; a boolean stat requires `flagValue` and forbids numeric fields. Resource bounds and current values must agree. `maximumStacks` is required only for `STACK`; the supported condition duration is an explicit positive tick count. Numeric values must survive RFC 8785 canonicalization without precision loss, and tick counts are positive integers no greater than `9007199254740991`.

An action target set contains `targetSetKey`, `targetingPolicyKey`, `targetSelectionPolicyKey`, `required`, `unresolvedFeedbackKey` and `playerSelectorInputSlot`. `SOURCE` is implicit and cannot be declared again. Optional target sets require an authored unresolved feedback key. A player-selection strategy, including a truncation strategy, requires its declared input slot. Each action effect binds `effectKey` to an existing `targetSetKey` or `SOURCE`. A cost contains `statKey`, `amount` and `commitPolicy`; a cooldown contains `cooldownKey`, `durationTicks` and `commitPolicy`. A ranking comparator contains `statKey`, `statField` and `direction`. A feedback argument contains `key` and `kind` (`TEXT`, `NUMBER`, `BOOLEAN` or `ACTOR_ID`). These collections retain author declaration order; definition replacement retains its position and explicit deletion followed by recreation appends it within that family.

The initial retained effect grammar supports the already declared `CONTINUOUS` numeric modifiers `ADD`, `MULTIPLY`, `CLAMP_MIN` and `CLAMP_MAX` on `VALUE` or a resource's typed `MAXIMUM`, plus `INSTANT` `ADJUST_RESOURCE`, exact-key `APPLY_CONDITION` and exact-key `REMOVE_CONDITION`. Maximum modifiers may declare the capacity policy described below. Actions reference only instant effects; continuous overlays reference only continuous effects. The narrower source grammar does not infer tag/source condition-removal selectors, granted state, damage, disposition transitions or other unimplemented extensions. Such declarations deny this schema until explicitly supported.

Predicates contain `kind`, `actor`, `factKey`, `comparison`, `number` and ordered `operands`. The supported kinds are explicit `TRUE`/`FALSE`, bounded `ALL`/`ANY`/`NOT`, numeric `STAT_COMPARE`, `CONDITION_PRESENT` and `DISPOSITION_EQUALS`, using `SOURCE` or `TARGET` and declared actor catalog keys. Numeric comparisons are `EQ`, `NE`, `LT`, `LE`, `GT` and `GE`; boolean compositions have at most 64 direct operands and 32 nesting levels. These are declarations of existing actor facts, not authorization for arbitrary owner reads. Actor-tag predicates, relationship/spatial predicates and undeclared primitive kinds remain rejected until their exact typed fact-owner contract is registered. Intrinsic catalog tags remain authored metadata and never become dedicated admission tags implicitly.

Every reference resolves against the complete atomic post-mutation inventory. Unknown members, unknown kinds, duplicate keys, stale references and invalid lifecycle or selector combinations deny; no unsupported input is removed to manufacture a complete set. `GameplayRuleManifest` is the shared codec for this owner-defined grammar, not mutation authorization or a Game Logic receipt.

Game Design retains `GAMEPLAY_RULE` UPSERT/DELETE revisions under the existing coordinator. The complete affected tuple is its canonical tenant/version, `GAME_DESIGN_CONTROL_PLANE`, aggregate and scope type `GAMEPLAY_RULE_SET`, aggregate ID equal to the canonical version UUID, scope ID `effective`, and the original expected source epoch. All rule revisions in the commit share that atomic catalog epoch. Fresh genesis writes every explicit empty family only inside the actual new full-Draft Version insertion transaction; retained versions receive no backfill. Synchronized snapshots retain the exact original creating revisions and inherited commit, and become visible only with the same complete coordinator result. This authored source epoch is separate from a later Game Logic intake-operation identity.

## Canonical Ability References

Published ability and action references use stable design identifiers, not display names. Ability definitions must expose an `abilityId` stable within `(tenantId, versionId)`, and action sequences must expose an `actionSequenceId` stable within the same scope. Designer-facing names may change across revisions, but scripts, plugins, and validation records must store the stable identifiers.

Any script or plugin field that references an ability must use a typed selector such as `{"abilityId":"abilityId:<stable-id>"}`; action sequence references must use `{"actionSequenceId":"actionSequenceId:<stable-id>"}`. **Target-state publish validation (currently unproved):** Game Design must validate these selectors against the exact `baseVersionId` and its dedicated release-attested `abilitySchemaDigest` during `PublishVersion`, `PublishScriptPatchVersion`, and `PublishPluginVersion`. The current plugin path compares to the `AUTOMATION_SCRIPTING` aggregate participant digest instead, as recorded above. Runtime services must not reinterpret display names or aliases as ability identities.

Script-patch and plugin publication record these validated stable references as immutable design-time evidence. They do not select a live instance's script pin or epoch. **Target-state runtime contract (currently unproved):** Runtime execution receives the exact Game Session-owned pin tuple and must reject stale references rather than resolving a newer ability schema or patch locally. Linked-plugin runtime references additionally carry exact `pluginId`, `pluginVersionId`, and `bindingId` plus the captured `(pluginActivationEpoch, lifecycleRevision)` fence evidence alongside—not instead of—the Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple, so stale plugin references reject. The lifecycle pair is runtime evidence, not an ability or command identity. See the [canonical plugin lifecycle and runtime identity](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#one-dsl-distinct-artifact-and-lifecycle-roles). Embedded scripts and linked plugins share this typed reference validation and DSL sandbox, while plugin publication/activation remains a separate lifecycle from game-owned script revisions.

## Authored Actor-State Catalog

Every published game version includes a versioned actor-state catalog. It is DML-authored design data, not service configuration: the platform supplies a small typed grammar while each game supplies all named stats, resources, conditions, tags, presentation values, and effect declarations.

- Stat definitions use a stable `statKey`, one primitive kind (`NUMERIC`, `BOUNDED_RESOURCE`, or `BOOLEAN_FLAG`), base/default value, applicable hard bounds, visibility/presentation metadata, and tags.
- Condition definitions use a stable `conditionKey`, explicit stacking and duration policy, visibility/presentation metadata, tags, and typed effect declarations.
- Typed effects reference catalog keys. Abilities, action sequences, equipment, consumables, room effects, scripts, and later combat all use this one declaration grammar rather than creating private stat maps or platform-specific names such as `health` or `fire_resistance`.

Game Design validates every reference and effect declaration at publish time, includes the immutable catalog in the release bundle, and records its digest with the release. Runtime services resolve definitions only from the game instance's pinned release catalog; unknown or stale keys fail closed. Entity Management stores active runtime state separately, but an active condition retains its source, definition key, release identity, and immutable applied-effect snapshot so a later publish cannot rewrite an effect that is already running.

This provides a fully game-authored, scriptable design surface while keeping enough platform type information for deterministic validation and evaluation. See [Gameplay Rules, Entities, and Effects](../../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md) for the runtime ownership and evaluation contract.

## Actor Disposition and Continuous Overlay Policy

An actor's DML-authored `ActorDisposition` is the baseline for action admission and semantic feedback. `CONTINUOUS` condition, equipment, stance, and aura effects may narrow that admission policy only; their typed policy modifiers can add denied action tags but cannot grant behavior that the main disposition denies.

Games author the restrictive overlays and their condition/application eligibility as ordinary release DML. Recovery, immunity, revival, or an exceptional state change is never inferred from a continuous item. It uses an explicit `INSTANT` operation: remove or prevent the restrictive condition, or transition the actor's persisted disposition. This preserves one lifecycle owner for defeat/death-like states while allowing each game to author its own recovery rules.

## Action Admission Facets

Game Design owns a versioned DML `ActionAdmissionTag` catalog for each release. Every command/action definition publishes a required ordered `admissionTags` list that references that catalog; an explicitly empty list is valid for an action intentionally unrestricted by actor disposition. Built-in commands publish their corresponding metadata through the same active command-definition registry. The platform validates tag syntax and catalog membership against the pinned release/registry but does not hardcode game-specific categories such as `canCast` or `canMove`. Admission tags are a dedicated policy field, not the existing primary action category or optional activity/AFK tags.

An `ActorDisposition` declares its denied admission tags. Continuous condition, equipment, stance, and aura overlays may add further denied tags only. An enabled, stage-valid action is admitted only when none of its `admissionTags` are denied by the resolved actor policy; unknown, stale, or absent required tags fail closed. A game that needs a broad restriction authors a shared tag such as `gameplay` and applies it to the relevant definitions rather than requiring a platform-owned all-actions boolean.

Feature availability is a separate earlier gate: `commandCapabilities` decides whether a standard command family exists for the tenant/game and returns `FEATURE_UNAVAILABLE` when disabled. Session/login/play stage validation remains separate as well. Only after those gates does Game Logic apply actor admission, returning a stable platform action-admission failure with the resolved disposition's DML-authored safe feedback. This gives a disabled feature, an unauthenticated caller, and a stunned actor distinct deterministic outcomes.

## Action Target Declarations

The authored target and effect declarations are the release-pinned typed data boundary from [ADR 0112](../../decisions/adr-0112-typed-bounded-gameplay-effect-extension.md). Game Design publishes bounded policies and registered effect references; Game Logic resolves target plans and Entity Management applies validated actor mutations. Scripts and plugins request the same registered commands and do not introduce an alternate mutation or targeting path.

Game Design owns versioned DML `ObservationPolicy`, `TargetingPolicy`, and `TargetSelectionPolicy` catalogs plus named default-path bindings for each release. An observation policy has one bounded `observableWhen` predicate tree. A targeting policy contains one typed candidate selector, an `observationPolicyKey`, an `eligibleWhen` predicate tree, and safe failure-presentation declarations. Predicate trees use `ALL`, `ANY`, and `NOT` over declared source/target actor facts, conditions, tags, dispositions, relationships, and World-owned spatial facts. The platform adds a new predicate kind only when it has stable typed data and deterministic evaluation; arbitrary script expressions do not become a hidden targeting engine.

Published actions declare a bounded keyed list of `ActionTargetSet` declarations, while standard targetable command paths resolve named default target-set bindings. `SOURCE` is the single implicit reserved target set and needs no policy. Each authored target set has a unique `targetSetKey`, a `targetingPolicyKey`, a `targetSelectionPolicyKey`, required-or-optional outcome behavior, and an optional declared player selector input slot. Only a target set with that input slot may consume player target text. The targeting policy owns the complete reusable candidate and eligibility shape, so one `visible-direct-actor`, `hostile-direct-actor`, `self`, or room-target policy may attach to many target sets without copied selector or visibility logic. The referenced selection policy owns reusable cardinality and selection behavior. The platform candidate-selector grammar begins with `SELF` and `DIRECT_ACTOR` and may add room/area modes later; it resolves only the bounded candidate set, canonical actor identity, tenant/game scope, and World occupancy. Initial target sets all resolve relative to the action source; target-set-to-target-set derivation requires a later explicit grammar addition. Missing policy keys, input-slot mismatch, or stale target-set references fail closed at publication and runtime admission.

`TargetSelectionPolicy` carries cardinality and a typed selection strategy. `EXACTLY_ONE` requires one target, `UP_TO_N` carries explicit `minTargets` and `maxTargets`, and `ALL_ELIGIBLE` uses every candidate up to an operator-enforced ceiling. The initial strategies are `PLAYER_SELECTED`, which permits only eligible candidates named by the target set's declared player selector syntax; `CANONICAL_ORDER`; `RANKED`, using a bounded DML list of published typed fact comparators and directions with canonical actor-id tie breaking; and `RANDOM_SEEDED`, which derives its result from the idempotent effect id over canonical candidate order. A policy must declare the strategy used when an operator ceiling truncates `ALL_ELIGIBLE`; no query order or implicit random fallback is valid. The `TargetingPolicy` never owns cardinality, so a single visible-hostile policy can serve a one-target strike and a room-wide effect. Optional-target outcome behavior remains part of the action target-set declaration.

The referenced observation policy's `observableWhen` runs first. A false result removes the candidate from player-visible resolution and returns only the policy's generic safe unavailable result, so a named hidden actor is indistinguishable from no matching actor. `eligibleWhen` runs only for an observable candidate. Its false result may use the policy's authored safe feedback, such as an immunity or ally restriction, without revealing predicates classified as non-disclosing. A game whose targeting does not use visibility uses a reusable observation policy with `observableWhen=true`.

`visibility`, `hidden`, `seeInvisible`, `untargetable`, faction, phase, and similar mechanics are optional game-authored facts, not platform fields. For example, a starter profile may materialize a reusable `visible-actor` observation policy whose predicate is `(target lacks hidden) OR (source has see-hidden)`, then attach it to multiple targeting policies. Game Logic evaluates the frozen policies at durable execution and records policy snapshots, canonical candidate order, selected targets, ranking values or random seed, stage results, and decisive predicate evidence in target-resolution evidence for the `ResolvedEffectPlan` or rejected action outcome; Entity Management never reinterprets player text or mutable design data during mutation.

Every action-owned `EffectDeclaration` names `SOURCE` or one declared `targetSetKey`. A required target set that does not resolve prevents the action from committing. An unresolved optional set contributes its explicit outcome and applies none of its attached effects; it is never silently skipped. Effects cannot parse selector text, create an undeclared target set, or apply to a candidate outside their resolved target set.

Every platform predicate kind has one documented fact-owner contract. Publication rejects a policy whose predicate cannot be compiled to that bounded contract; DML cannot request arbitrary service data or an ad hoc script read. At durable execution, Game Logic derives the exact source and candidate fact keys referenced by the frozen policies and asks each owner for one bounded `TargetingFactSnapshot` per resolution. The snapshot contains only requested facts plus that owner's version or fence token. This keeps reusable game-authored policies soft-coded while preserving typed, observable, and deterministic cross-service reads.

## Resource Cost and Cooldown Declarations

Published actions declare `costs[]` and `cooldowns[]` as typed DML. Each cost references a declared bounded-resource `statKey`; each cooldown references a stable author-defined `cooldownKey`. A declaration also chooses a typed commit policy: `ON_EXECUTION` after required validation and target resolution, or `ON_EFFECT_SUCCESS` after required effect application.

Game Logic and Entity Management execute this immutable release declaration under the durable effect id. Queue admission does not consume resources or start a cooldown. Entity Management atomically checks source-actor availability, applies committed costs and same-region effects, and records durable actor cooldown state. A later cross-region leg failure is an explicit action outcome, not an implicit refund; refund behavior requires a future explicit authored declaration.

## Action Feedback Declarations

Published actions define semantic feedback declarations as versioned DML. Each declaration identifies an outcome event, audience role, message key/template, typed argument contract, visibility classification, and replay policy. Audience roles such as source actor, direct target, and authorized observer are resolved by Game Logic from the action outcome; display text is rendered later by Game Session.

The resulting `GameplayPresentationEvent` is idempotent and tied to the action effect lifecycle, so replay cannot duplicate feedback and late remote completion can emit a distinct final event. Game-authored feedback cannot override platform validation, authorization, or infrastructure error codes.

## Effect Declaration Lifecycle

One typed `EffectDeclaration` grammar serves equipment, conditions, actions, and future auras. Its `lifecycle` prevents a declarative effect from being interpreted differently at each runtime seam.

- `CONTINUOUS` declarations attach to a source such as an equipped item, active condition, stance, or aura and contribute to evaluated actor state for as long as that source exists. They use derived-state modifiers such as `ADD`, `MULTIPLY`, clamps, and granted state.
- `INSTANT` declarations execute once through the `ResolvedEffectPlan` under its idempotent effect id. When owned by an action, each declaration uses that action's declared target set; equipment, conditions, and other attached sources retain their own attached subject. The initial mutation grammar is `ADJUST_RESOURCE`, `APPLY_CONDITION`, and `REMOVE_CONDITION`; a later `TRANSITION_DISPOSITION` operation owns explicit defeat, recovery, revival, or other main-state changes.
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

- Game Design-authored ability/action definitions validated and immutably retained by Game Logic for the exact selected release.
- Script and plugin patch versions owned by the Automation & Scripting Service.

Within a given published game version:

- Ability schemas and identifiers are treated as **stable** for the lifetime of that version.
- Script-only and plugin-only patch versions may evolve independently, but they must not rely on incompatible ability schemas; breaking changes to abilities require a new game version publish as described in `design/architecture/system-architecture-versioning-runtime.md`.

Scripts and plugins may safely reference abilities by identifier within a game version and across script-only or plugin-only patches, but they should not assume that those identifiers remain valid across **different game versions**. Versioning runtime docs describe how these assets are pinned and how rollbacks behave when a game version is reverted.

The Game Design Service’s `PublishVersion`, `PublishScriptPatchVersion`, and `PublishPluginVersion` workflows are responsible for enforcing these rules at **design time** in the target contract (the dedicated ability-schema validation remains unimplemented/unproved). They do not own instance activation compatibility; Automation & Scripting owns the runtime `SetPluginActiveVersion` checks against the current published plugin metadata and running-instance compatibility/fence state.

- During `PublishVersion`, it verifies that all ability identifiers referenced by scripts and plugins targeting a given `(tenantId, versionId)` exist and are compatible with the ability schema for that version.
- During `PublishScriptPatchVersion` and `PublishPluginVersion`, it must re-validate that the patch/plugin remains compatible with the pinned `baseVersionId` (the underlying published game version) and its ability schema. Script-only and plugin-only patches must not introduce new dependencies that require a new `versionId`.
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

From an observability perspective, live script and plugin invocations (`isDryRun=false`) that exercise abilities are recorded in `script_event_audit` with a `scriptEventId` that you can use to correlate designer-facing events with approved operational evidence and downstream tick effects. The current legacy materialized dry-run/test handler path records its terminal `DSL_EVAL` result there as `finalOutcome=dry_run_completed` with `finalReason=dry_run_no_handoff`; that outcome does not imply an alternative terminal result or gameplay handoff. Target ADR 0114 command-plan previews use an isolated preview result/audit surface or return the result directly and never create a handler `script_event_audit` row. Live and target-preview execution use their corresponding metric families: target previews and dry-run/test handlers use test-only metrics and never increment live families. Aggregate automation metrics must use Table 4's bounded canonical dimensions (such as `service`, `scope`, `script_kind`, `event_class`, outcome/reason, `plugin_origin`, and priority/tier as applicable), not raw `scriptId`, `eventType`, `tenantId`, or `scriptEventId` labels. Exact plugin and version identities may appear only in explicitly approved audit records, protected logs, named span families whose documented incident query requires them, and authorized control-plane or diagnostic queries. Trace use follows [ADR 0167](../../decisions/adr-0167-allowlisted-sensitive-trace-attributes.md): producer-side span-family allowlists, least-privilege environment-scoped access with audited queries, finite profile-defined retention, and declared export/privacy/erasure handling apply; availability in all logs, traces, or queries is not implied. These surfaces follow the patterns in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.

## Capabilities

- **Ability Editor** – create spell and skill definitions with cooldowns, resource costs, and targeting rules.
- **Action Sequencer** – design combos or chained actions that trigger based on events.
- **Balancing Metrics** – display damage, healing, and resource impact to help tune gameplay.
- **Integration with [Item & Equipment Balancing](item-equipment-balancing.md)** – ability damage values can be compared against item statistics for overall balance.

## Target Workflow

1. Designers author typed ability/action and referenced catalog definitions through Game Design's revision and synchronized-commit boundary. The complete editor and source writers remain subject to the implementation limits above.
2. Before publication digest collection, an authenticated, mutation-authorized source operation delivers the exact selected commit's complete effective inputs to Game Logic for validation and immutable retention.
3. `PublishVersion` reads the already retained Game Logic evidence and independently checks its aggregate manifest and dedicated ability-schema attestations for that same selection. It neither copies abilities as a publication mutation nor repairs missing source through a digest read.
4. Runtime consumes only the resulting pinned, published release. Intake or digest success alone does not activate it.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [World Editing & Customization Tools](world-editing-tools.md)
- [Web-Based Visual Design Interface](web-visual-interface.md)
- [Game Design Service gRPC API](../../../../protos/game-design/v1/README.md)
- [Version Control for Design Assets](version-control.md)
