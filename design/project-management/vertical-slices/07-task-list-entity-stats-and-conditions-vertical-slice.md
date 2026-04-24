# Entity Stats and Conditions Vertical Slice

## Goal and Status

Goal: define one canonical gameplay-state model for numeric stats, bounded resources, conditions, buffs, debuffs, and transient action states so equipment, potions, spells, actions, and future combat all contribute through the same effect system instead of accumulating one-off rule paths. Status: first Entity Management-owned read substrate is live; authored definitions, shared effect evaluation, equipment/action contributions, and damage/mitigation remain future work.

## Implementation Notes

- Entity Management now owns persisted actor resource and active-condition state through `actor_resource_states` and `actor_active_conditions`, keyed by tenant, opaque game instance id, and character id.
- `QueryActorState` exposes a gameplay-attested, playable-scope-aware read API that returns baseline character stats overlaid with persisted resource rows plus active non-expired conditions.
- The first implementation is intentionally read-side only: it does not yet author stat/condition definitions, evaluate equipment/action modifiers, apply or expire effects, or resolve combat damage.
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
- The model should remain typed enough that services can validate definitions and produce deterministic effective-state evaluations.
- Presentation should consume evaluated state; it should not reverse-engineer effective gameplay state from ad hoc transcript text.
- The first implementation should preserve raw source/state identity so later debugging and audit work can answer why a final effective value exists.
- Entity Management should own canonical persisted actor gameplay state.
- Game Logic should own gameplay-rule orchestration and requests that apply, expire, consume, or evaluate that state.
- Active conditions and transient action states should both be treated as actor state rather than split across unrelated service-owned stores.
- Cooldowns should remain in the same broad timed-runtime family, but as a sibling timed-state type rather than being forced to masquerade as a condition or action stance.

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

- [ ] Define the first player-facing output expectations for status/state inspection, such as a future `STATUS` or prompt-facing resource summary, without blocking the shared runtime model on final UX polish.
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
