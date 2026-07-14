# Entity Stats and Conditions Vertical Slice

## Goal and Status

Goal: define one canonical gameplay-state model for numeric stats, bounded resources, conditions, buffs, debuffs, and transient action states so equipment, potions, spells, actions, and future combat all contribute through the same effect system instead of accumulating one-off rule paths. Status: the first Entity Management-owned evaluated-state substrate, shared effect evaluation, equipment contributions, and transient action-state execution are live; generic authored stat/condition definitions and damage/mitigation remain future work.

## Implementation Notes

- Entity Management now owns persisted actor resource and active-condition state through `actor_resource_states` and `actor_active_conditions`, keyed by tenant, derived playable-state namespace, and character id rather than a raw game-instance shortcut.
- `QueryActorState` exposes a gameplay-attested, playable-scope-aware read API that returns baseline character stats overlaid with persisted resource rows plus active non-expired conditions.
- The first player-facing state reader is now live: in-world `STATUS` (alias `STAT`) reads the evaluated actor state through Game Session -> Game Logic -> Entity Management, renders a typed resources/visible-conditions view for text and first-party WebSocket clients, and never exposes internal effect payload or source provenance.
- Active condition payloads, equipped item payloads, and replay-guarded transient action states now all contribute through the shared evaluator. `ApplyActorCondition` validates persisted effect JSON before replay lookup, and Entity Management expires elapsed action-state rows on its scheduled expiry path.
- Game Design validates the first release-admitted authored `APPLY_ACTION_STATE` declaration, and Game Session executes its persisted snapshot through the same actor-condition seam. Generic authored stat/condition definitions, resource-cost mutation, multi-effect actions, and damage resolution remain future work.
- Runtime game instance identifiers remain opaque strings, matching existing inventory/equipment/room-state tables rather than requiring numeric ids.

## Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

This slice sits after the first inventory/equipment work because it will become the shared substrate for health, armour, resistances, afflictions, blocking states, temporary buffs, and similar mechanics across many later gameplay systems.

## Why This Slice Exists

The repo is about to need one shared answer for gameplay state changes such as:

- health and other bounded resources;
- afflictions such as poison, bleed, or stun;
- temporary buffs and debuffs from actions, spells, potions, or room effects;
- equipment-contributed values such as armour, resistances, or slot-specific mitigation;
- transient action states such as blocking, charging, aiming, or parrying.

Without an explicit design, FireMUD will drift toward:

- hardcoded stat enums for some games and untyped maps for others;
- separate effect logic for equipment, spells, potions, and actions;
- duplicated mitigation rules in handlers instead of one shared evaluation path;
- presentation and transcript inconsistencies because runtime state has no canonical shape.

This slice should establish the target-state model before deeper combat, damage, status, or class/species systems land.

## Scope

- Define the canonical authored model for game-specific stat definitions and condition definitions.
- Define the canonical runtime model for current resource values, active conditions, active transient states, and effective derived values.
- Define the shared effect/modifier model used by:
  - worn items;
  - actions;
  - spells;
  - potions and consumables;
  - room or aura effects;
  - future scripted gameplay sources.
- Define the difference between:
  - persistent conditions;
  - transient action states;
  - recalculated equipment-derived modifiers.
- Define how effect scope can target:
  - the whole entity;
  - a body region or equipment slot;
  - a damage type;
  - an action family or tagged interaction.
- Define the first testing and presentation expectations so later slices can assert exact effective-state outcomes.

## Out of Scope

- Implementing full combat.
- Designing every possible damage type or status effect a game may want.
- Replacing the settings model with a scripting-first authoring system.
- AI-driven balance or rules generation.
- Rich first-party GUI presentation beyond recording what runtime state must make available later.

## Target-State Model

FireMUD should treat gameplay state as four related but distinct layers:

1. stat definitions
2. condition definitions
3. active effect sources
4. evaluated effective state

The shared platform should provide typed primitives, while each game authors the actual named stats and conditions it uses.

### Canonical Authored Actor-State Catalog

Game Design owns a versioned, DML-authored actor-state catalog for each `(tenantId, versionId)`. Publishing freezes that catalog into the game's release bundle; runtime services resolve it from the game instance's pinned release rather than from mutable design rows or per-command settings reads.

The platform owns only a small typed grammar. The game owns every named mechanic, including `health`, `mana`, resistances, conditions, presentation labels, tags, and authored effect values. A game-specific universal-stat enum is not permitted.

- A stat definition has a stable `statKey`, primitive kind, default/base value, hard bounds where applicable, visibility/presentation metadata, and tags. The initial primitive kinds are `NUMERIC`, `BOUNDED_RESOURCE`, and `BOOLEAN_FLAG`; later damage or region semantics use tagged effects rather than introducing platform-owned `fire_resistance`-style stat names.
- A condition definition has a stable `conditionKey`, explicit stacking and duration policy, visibility/presentation metadata, tags, and typed effect declarations. Conditions are definitions, not unvalidated free-form payload keys.
- Effect declarations are typed, validated data that reference declared stat or condition keys. Equipment, actions, spells, consumables, room effects, and scripts use this same grammar instead of feature-local stat maps.
- Entity Management persists only runtime state and provenance. It rejects definitions or references that are absent from the actor's pinned release catalog; it must not invent defaults or reinterpret unknown keys.
- An active condition records its source, definition key, release identity, and immutable applied-effect snapshot. Later publishes therefore do not silently change an already-active condition. A replacement-instance/cutover flow must validate or remap durable actor state explicitly against its target release rather than applying best-effort name matching.

This keeps the full game design surface scriptable as versioned DML while retaining a small hardcoded grammar that services can validate deterministically.

### Capacity Changes From Continuous Sources

The default behavior when a continuous source changes a bounded resource's maximum is an effective tenant/game setting, `actorState.capacityChangePolicy`, rather than a hardcoded meaning of a resource such as `health`. The platform admits only `CLAMP_ONLY`, `PRESERVE_RATIO`, and `PRESERVE_DEFICIT`. A continuous effect declaration from an item, condition, spell-backed aura, or equivalent source may specify a more-specific override for the maximum delta it causes.

Entity Management applies that policy only at the durable source transition: attach, detach, refresh, expiry, or replacement. It never rewrites current resource state while evaluating an actor read. A transition with multiple maximum-affecting declarations executes them in frozen authored order, carries the intermediate state forward, and records each resolved policy with its idempotent result. An explicit heal or drain remains a separate instant effect. A starter experience profile may seed a recommended editable game-setting value, but it never supplies hidden runtime behavior after that setting is changed or removed.

### Starter Experience Profiles

Creators do not need to author every actor-state definition from scratch. Game Design supplies curated, versioned starter experience profiles such as a classic text-MUD baseline, a solo-RPG baseline, or a minimal sandbox. A selected base profile and optional extension packs materialize ordinary stat, condition, action, disposition, observation/targeting-policy/default-path binding, and feedback DML into the target Draft version before publication.

- The imported rows are normal game-owned DML after application: creators may edit, replace, or remove them, and a game may select no profile at all.
- Profiles are not runtime settings and do not create hidden fallback mechanics. If a game removes or does not select a definition, runtime behavior cannot silently resurrect it from a platform default.
- Optional packs compose in declared order. Duplicate definition keys fail by default; a later pack may replace an earlier definition only through an explicit recorded override. There is no implicit last-writer-wins merge.
- Game Design records selected pack identities, revisions, hashes, application order, and explicit overrides as Draft provenance. Publishing freezes only the resulting single game version and its release bundle; running instances never inherit moving profile content.

This gives new games a sane usable default experience while preserving the fully DML-authored, scriptable model for games that need different mechanics.

### Canonical Resource Floor and Actor Disposition

Reaching a bounded resource's floor has no universal platform meaning. A resource definition may optionally reference a versioned DML `floorTransition`; an omitted transition means the resource can remain at its floor without changing actor disposition.

- A floor transition fires once when an idempotent instant mutation crosses the resource downward to its declared floor. It reports the actual applied delta and a `FLOOR_REACHED` fact; reads while already at the floor do not repeat it.
- The transition references an authored `ActorDisposition` definition with action-admission, optional condition, and semantic feedback policy. It can represent unconsciousness, defeat, death, exhaustion, or a game-specific state without making any of those a platform-owned `health` rule.
- Every actor has one persisted main `dispositionKey`, normally initialized from the selected experience profile. Conditions and equipment are overlays on that base state: `stunned` can restrict action admission, while `invisible` is an optional game-authored fact that a separate targeting policy may use. Neither becomes a competing defeat/death lifecycle owner. Transport/session presence remains separate from disposition.
- Generic resource adjustment clamps to declared bounds. It does not create a corpse, respawn an actor, distribute loot, decide combat victory, or infer permanent death.
- Damage and mitigation later consume the floor transition and disposition contract. They own hit resolution, mitigation, defeat/revival, respawn, and corpse/loot policy through explicit authored rules.

### Disposition and Overlay Composition

The persisted `ActorDisposition` is the DML-authored baseline for action admission and semantic feedback. A condition, equipment, stance, or aura uses a continuous overlay only to narrow that admission baseline. It cannot grant behavior that the main disposition denies, so a continuous source cannot accidentally revive a defeated actor or bypass another main-state lifecycle.

Games retain full authoring control through explicit instant effects. A recovery may remove or prevent a restrictive condition; a revival or comparable main-state change uses an idempotent disposition transition. The release declaration, effect id, and resulting disposition are recorded with the mutation, so replay never derives a different lifecycle outcome from current equipment or condition reads.

### Action Admission Facets

Game Design publishes a DML `ActionAdmissionTag` catalog with the actor-state and command definitions. Every command/action definition carries a required ordered `admissionTags` field that references it; an explicitly empty list is valid for an action that disposition does not restrict. The `ActorDisposition` definition names the tags it denies, and continuous overlays can add only further denials. An enabled action is admitted only if none of its tags are denied; invalid, unknown, stale, or omitted required tags fail closed. This is distinct from a primary action category and activity/AFK tags, which do not decide actor capability.

Tenant/game command capability policy is also separate. A command family disabled by `commandCapabilities` returns `FEATURE_UNAVAILABLE` before actor admission; login/play stage gates remain separate; only an enabled stage-valid command reaches this disposition gate. The resulting action-admission failure uses a stable platform result and the resolved disposition's authored safe feedback.

### Targeting Policies

Game Design publishes DML `ObservationPolicy` and `TargetingPolicy` catalogs plus named default bindings for platform target paths. An observation policy supplies `observableWhen`; a targeting policy supplies its candidate selector, observation-policy reference, `eligibleWhen`, and safe failure-presentation declarations. The predicates use bounded `ALL`/`ANY`/`NOT` composition over declared source/target state facts, conditions, tags, dispositions, relationships, and World-owned spatial facts. Actions and standard paths attach a reusable targeting-policy key rather than duplicating common target logic.

Visibility, hidden state, see-hidden state, targetability, faction, phase, and comparable mechanics are optional facts, not mandatory actor columns or platform booleans. A false observation expression is non-disclosing and therefore has the same player result as no matching target. A false eligibility expression may emit only the policy's approved safe feedback. A starter profile may materialize a reusable visible-actor observation policy and attach it to several direct-target policies, while a game without visibility uses `observableWhen=true`. Game Logic resolves the frozen policies only at durable execution and records their snapshots, stage results, and decisive evidence in the `ResolvedEffectPlan` or rejected action outcome; missing or stale references fail closed.

### Canonical Condition Application and Removal

Each condition definition owns its application policy. Repeated application never relies on the caller's source-id shape or on handler-local assumptions.

- `REPLACE` removes the prior matching instance before applying the new one; use it for exclusive states such as stances.
- `REFRESH` maintains one active instance and refreshes its duration; use it for effects such as shields or blocking.
- `STACK` maintains one active instance and increments its DML-authored bounded stack count; use it for poison or bleed.
- `PARALLEL` retains independently sourced active instances with their own expiry; use it where effects from separate sources must coexist.
- Every definition selects duration behavior: reset from now, extend, or preserve the longer expiry. `STACK` also declares its maximum stack count.

Active condition instances retain a stable instance id, definition/release snapshot, source provenance, stack count, start/expiry, and applied effect snapshot. Entity Management resolves reapplication atomically under the idempotent effect id.

`REMOVE_CONDITION` uses typed authored selectors: exact condition key, condition tag, or an allowed source selector. Tag-based removal follows the definition's authored removal priority and a stable instance-id tie-breaker, so cure/replay behavior stays deterministic. Player input never chooses raw payload rows or arbitrary source identifiers to delete.

### Canonical Resource-Cost and Cooldown Lifecycle

Resource costs and cooldowns are actor gameplay state, not command-acceptance metadata or reconnect-session state. Game Design authors them in the published action declaration as `costs[]` and `cooldowns[]`, each referencing the frozen actor-state catalog and carrying its own typed commit policy.

- `ON_EXECUTION` commits after required target and rule validation succeeds, before outcome effects run.
- `ON_EFFECT_SUCCESS` commits only after the required effect plan has committed.
- Entity Management atomically checks resource availability, consumes the declared source-actor resources, creates durable actor cooldown records, and applies same-region action effects under the one idempotent effect id.
- Cooldown records carry actor identity, cooldown key, release/action provenance, start/expiry, and effect id. They are a sibling timed-state type, never synthetic conditions.
- Game Session's region timer keys are reconstructible scheduling projections. They may wake expiry or retry work, but the durable actor record decides whether an action is on cooldown across reconnect, idle regions, and scheduler recovery.
- A cross-region target-leg failure after the source action has committed is reported in the durable outcome. There is no implicit refund; a future authored refund rule must be explicit.

Two commands racing for the same actor resources or cooldown key must serialize through the authoritative mutation and yield one deterministic committed outcome. Queuing or text-command acceptance alone consumes neither cost nor cooldown.

Examples of primitive categories:

- numeric stat
- bounded resource
- boolean flag
- stacked condition
- typed resistance or affinity
- slot- or region-scoped mitigation contributor

Examples of game-authored values built from those primitives:

- `health`
- `mana`
- `armour_value`
- `slash_resist`
- `fire_resist`
- `poisoned`
- `blocking`
- `warded`

## Architecture Notes

- One shared modifier/effect engine should evaluate contributions from equipment, actions, spells, potions, and conditions.
- The platform should not create separate bespoke rule engines for "equipment stats", "buffs", and "action states".
- Game-authored stat and condition definitions should be data-driven and validated, not free-form runtime maps.
- The authored actor-state catalog is versioned DML in the Game Design Service and is frozen into the published release bundle; it is not a tenant/game setting group.
- The model should remain typed enough that services can validate definitions and produce deterministic effective-state evaluations.
- Presentation should consume evaluated state; it should not reverse-engineer effective gameplay state from ad hoc transcript text.
- The first implementation should preserve raw source/state identity so later debugging and audit work can answer why a final effective value exists.
- Entity Management should own canonical persisted actor gameplay state.
- Game Logic should own gameplay-rule orchestration and requests that apply, expire, consume, or evaluate that state.
- Active conditions and transient action states should both be treated as actor state rather than split across unrelated service-owned stores.
- Cooldowns should remain in the same broad timed-runtime family, but as a sibling timed-state type rather than being forced to masquerade as a condition or action stance.
- Resource costs and cooldowns commit only through idempotent durable action execution, with Entity Management as authoritative state and Game Session timers as reconstructible scheduling projections.
- Bounded-resource floor transitions resolve to DML-authored actor dispositions; no generic resource key has implicit death or respawn semantics.

## First Implementation Boundary

The first narrow implementation for `07` should not attempt all combat or all status UI at once.

Recommended first order:

1. define authored stat/resource/condition definitions;
2. define runtime entity state for current resources and active conditions;
3. land one shared effect-evaluation seam;
4. prove equipment and one transient action state can contribute through that seam;
5. defer full damage and mitigation resolution until the shared state model is stable.

## Locked Order

The broader `07.x` cluster should be designed and implemented in this order:

1. unified actor model;
2. shared effect engine;
3. stats and conditions;
4. equipment and transient action-state contributions;
5. damage and mitigation resolution.

Damage and mitigation should be consumers of the shared actor/state/effect model, not a second parallel rules engine.

This means the first proof should be able to express things like:

- a character with `health` and `max_health`;
- an equipped item contributing `armour_value` or typed resistances;
- a temporary condition such as `poisoned` or `blocking`;
- one evaluated effective-state query that shows the final merged result.

## 1. Design Alignment for Shared Gameplay State

- [ ] Re-read the Entity Management, Game Design, Game Logic, and Game Session design docs that currently touch inventory/equipment, communication, or future combat seams so the new stats/conditions model slots into the existing ownership boundaries cleanly.
- [ ] Update or add design docs so the repo describes one canonical target-state distinction between:
  - stat definitions;
  - condition definitions;
  - active conditions and transient action states;
  - equipment-derived effects;
  - evaluated effective values.
- [ ] Document that persistent conditions, transient action states, and recalculated equipment modifiers are related but not identical concepts.
- [ ] Document that later combat, potion, spell, and equipment slices must contribute through one shared effect model rather than creating separate per-feature stat logic.

## 2. Game Design Service: Authored Definitions

- [ ] Define a design-time model for game-authored stat definitions, including at least:
  - canonical key;
  - primitive kind;
  - default value or bounds;
  - optional caps/floors;
  - visibility or presentation hints;
  - tags for later rule targeting.
- [ ] Define a design-time model for game-authored condition definitions, including at least:
  - canonical key;
  - stacking policy;
  - duration/expiry semantics;
  - visibility hints;
  - tags;
  - effect payload references.
- [ ] Define how authored effects are expressed in a typed way, such as:
  - add;
  - multiply;
  - clamp;
  - set;
  - grant condition;
  - modify damage/resistance inputs later.
- [ ] Avoid hardcoding a universal platform stat list beyond primitive types and reserved infrastructure fields.
- [ ] Define how equipment templates, consumables, and future actions reference authored effect definitions.

## 3. Entity Management Service: Runtime State Ownership

- [x] Define the authoritative runtime model for entity resources and active conditions.
- [x] Decide which parts of state are persisted versus recalculated, including:
  - current resource values;
  - active timed conditions;
  - source-linked effect instances;
  - recalculated equipment contributions.
- [x] Define how runtime state records carry enough provenance to explain effective results, such as:
  - source type;
  - source id;
  - tenant/game/entity context;
  - start time and expiry;
  - stacking keys where relevant.
- [x] Introduce or refine query shapes that can return effective gameplay state without leaking transport-specific rendering decisions.
- [ ] Add tests proving deterministic effective-state evaluation for at least one resource stat, one equipment contribution, and one timed condition.

## 4. Shared Effect Evaluation Model

- [ ] Define one shared effect-evaluation pipeline that merges:
  - base definitions;
  - persisted current values;
  - active condition effects;
  - transient action-state effects;
  - equipment-derived effects.
- [ ] Define deterministic precedence and stacking rules so later slices do not invent conflicting local behavior.
- [ ] Support scoped effects for at least:
  - whole entity;
  - equipment slot or body region;
  - tagged damage family;
  - tagged action family.
- [ ] Define exact failure behavior for invalid or stale effect references so evaluation remains authoritative and auditable.

## 5. Game Logic Service: Gameplay-Oriented Evaluation

- [ ] Define the first gameplay-facing RPC or service seam for querying effective stats/conditions without forcing Game Session to reconstruct gameplay state.
- [ ] Keep Game Logic responsible for gameplay-facing orchestration and later rule composition, while Entity Management remains authoritative for persisted runtime state.
- [ ] Add unit tests showing later gameplay actions can consume effective-state queries without duplicating evaluation rules locally.

## 6. Game Session Service: Presentation and Help Expectations

- [x] Define and implement the first player-facing state inspection command: in-world `STATUS` / `STAT` projects evaluated resources and active visible conditions without making Game Session the source of truth.
- [ ] Document how conditions, buffs, and action states will eventually surface in transcripts or prompts without making Game Session the source of truth for gameplay evaluation.
- [ ] Ensure later help/docs can explain stats and conditions using canonical definition keys rather than hardcoded prose tied to one game.

## 7. Cross-Service Proof Shape

- [ ] Add or plan a bounded cross-service proof where:
  - a character has one base resource;
  - one equipped item contributes a modifier;
  - one temporary condition contributes another modifier or flag;
  - the effective-state query returns the deterministic merged result.
- [ ] Keep the first proof intentionally narrow and data-driven so later combat slices can build on it instead of replacing it.

## 8. Final QA Checklist

- [ ] The repo has one explicit target-state model for game-authored stats, conditions, and shared effects.
- [ ] Equipment, actions, potions, and future spells are expected to contribute through one shared effect engine rather than separate bespoke logic paths.
- [ ] The first implementation boundary is narrow enough to land before full combat while still proving the canonical runtime model.

---

## Related Follow-On Slices

- [07.1-task-list-shared-effect-engine-vertical-slice.md](./07.1-task-list-shared-effect-engine-vertical-slice.md)
- [07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md](./07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md)
- [07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md](./07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md)
