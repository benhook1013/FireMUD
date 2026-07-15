# Gameplay Rules, Entities, and Effects

## Current Status

The lossless source transposition is complete. This tracker consolidates the live gameplay rules, entity, transfer, action, and effect boundaries by capability; the unchanged source evidence remains the audit backstop while Spark coverage review verifies every allocation.

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [Authored Action Definition and Execution Model Vertical Slice](../vertical-slices/02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md) - Runtime execution, targeting, and effect semantics | partially complete; the bounded release-admitted self-targeted `APPLY_ACTION_STATE` v1 path is live, while the broader authored-action model remains in progress | 38-42, 50, 89, 98-99 | [source evidence](#source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-38-42-50-89-98-99) |
| [`02.18.10` Effect Idempotency and Replay Guards](../vertical-slices/02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md) - Gameplay effect idempotency and replay guards | complete at the current bounded boundary | 1-154 | [source evidence](#source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154) |
| [02.18.10.1 Task List: Authored-Action and Resource Effect Replay Guards Vertical Slice](../vertical-slices/02.18.10.1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice.md) - Authored action and resource replay guards | complete at the current bounded boundary | 1-90 | [source evidence](#source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90) |
| [Data-Driven LOOK Vertical Slice Task List](../vertical-slices/03-task-list-data-driven-look-vertical-slice.md) - Visible-entity and containment-model facts | the main LOOK flow and several regression tests are implemented; this document continues to describe the target-state behaviour, with implementation details and | 41-51 | [source evidence](#source-03-task-list-data-driven-look-vertical-slice-41-51) |
| [Inventory, Containers, and Equipment Vertical Slice Task List](../vertical-slices/06-task-list-inventory-containers-equipment-vertical-slice.md) - Inventory and equipment | partially implemented | 1-198 | [source evidence](#source-06-task-list-inventory-containers-equipment-vertical-slice-1-198) |
| [`06.2` Replace Character-Scoped Container Contents With Container Instances](../vertical-slices/06.2-task-list-container-instance-identity-vertical-slice.md) - Container-instance identity and holder validation | complete | 1-91 | [source evidence](#source-06-2-task-list-container-instance-identity-vertical-slice-1-91) |
| [`06.3` Replace Aggregated Item Stacks With Distinct Item Instances](../vertical-slices/06.3-task-list-container-item-instance-identity-vertical-slice.md) - Concrete item-instance identity and mutation semantics | complete at the current bounded boundary | 1-105 | [source evidence](#source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105) |
| [`06.3.1` Stable Item Instance Visible Ref Allocation](../vertical-slices/06.3.1-task-list-item-instance-visible-ref-allocation-vertical-slice.md) - Stable item-instance visible references | complete at the current bounded boundary | 1-72 | [source evidence](#source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72) |
| [`06.3.2` Authored Stackability and Fungibility](../vertical-slices/06.3.2-task-list-authored-stackability-and-fungibility-vertical-slice.md) - Authored stackability runtime behavior | complete at the current bounded boundary | 1-140 | [source evidence](#source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140) |
| [Unified Item Holder and Transfer Model Vertical Slice](../vertical-slices/06.4-task-list-unified-item-holder-and-transfer-model-vertical-slice.md) - Unified item holder and guarded transfer model | complete at the current bounded boundary | 1-146 | [source evidence](#source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146) |
| [Safe Item Transfer and Handoff Semantics Vertical Slice](../vertical-slices/06.4.1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice.md) - Safe item transfer and handoff | implemented for the current live holder-mutation paths; broader replay/idempotency remains intentionally out of scope until a caller needs it | 1-83 | [source evidence](#source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83) |
| [Entity Stats and Conditions Vertical Slice](../vertical-slices/07-task-list-entity-stats-and-conditions-vertical-slice.md) - Entity stats and conditions | the first Entity Management-owned evaluated-state substrate, shared effect evaluation, equipment contributions, and transient action-state execution are live; g | 1-343 | [source evidence](#source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343) |
| [Shared Effect Engine Vertical Slice](../vertical-slices/07.1-task-list-shared-effect-engine-vertical-slice.md) - Shared typed effect evaluation | the first typed Entity Management evaluation seam is live and now consumes conditions, equipped items, replay-guarded action states, and the first release-admit | 1-143 | [source evidence](#source-07-1-task-list-shared-effect-engine-vertical-slice-1-143) |
| [Equipment and Action-State Contributions Vertical Slice](../vertical-slices/07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md) - Equipment and action-state effect contributions | first equipment contribution path is live, and the first transient action-state command path applies `blocking` through the shared actor-state/effect seam; rich | 1-67 | [source evidence](#source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67) |
| [Damage and Mitigation Resolution Vertical Slice](../vertical-slices/07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md) - Damage and mitigation resolution design | direction locked; implementation is future work | 1-56 | [source evidence](#source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56) |
| [Unified Actor Model Vertical Slice](../vertical-slices/07.4-task-list-unified-actor-model-vertical-slice.md) - Unified runtime actor model | runtime identity direction locked; implementation is future work | 1-112 | [source evidence](#source-07-4-task-list-unified-actor-model-vertical-slice-1-112) |

## Canonical Design Sources

- [Entity Management Service](../../architecture/microservices/entity-management-service/README.md) and its [runtime/data model](../../architecture/microservices/entity-management-service/runtime-and-data.md) define entity ownership, items, actor state, conditions, and transfer semantics.
- [Game Design ability and action tools](../../architecture/microservices/game-design-service/ability-action-tools.md) and [item/equipment balancing](../../architecture/microservices/game-design-service/item-equipment-balancing.md) define versioned authored definitions.
- [Player command model](../../architecture/system-architecture-player-command-model.md) defines action invocation, targeting, and outcome policy.
- [Tick concepts and invariants](../../architecture/system-architecture-tick-concepts-and-invariants.md) defines durable effect identity, replay, and ownership constraints.
- [Game Logic Service](../../architecture/microservices/game-logic-service/README.md) owns orchestration and rule evaluation over the authoritative domain state.

## Consolidated Implementation Record

### Visible World Facts and Room-Ground State

World Management owns room identity, location, exits, and occupancy. Entity Management owns room-ground items through room-attached container instances. Game Logic resolves the current `LOOK` projection from those authorities; ordinary `LOOK` remains room prose, while `INV HERE` is the explicit room-ground inventory management view. Nested container contents are not expanded implicitly in room presentation.

The present live room/entity view is intentionally bounded. Richer prose, combat/effect annotations, NPC response, localized listening, and broader non-fixture room/entity context remain later gameplay presentation work.

### Items, Containers, Equipment, and Guarded Transfers

The live command surface includes `INVENTORY`, `INV HERE`, `GET`, `DROP`, `CONTAINER`, `PUT`, `TAKE`, `EQUIPMENT`, `WEAR`, and `REMOVE`. Physical items are persisted distinct `item_instances`; containers have their own instance identity; inventory, equipped slots, room ground, and containers use one direct-holder-field model. A transfer is one guarded source-to-destination mutation with audit facts, not a remove/add sequence that can expose a transient duplicate or disappearance.

Current transfer validation fails explicitly for stale, missing, moved, or incompatible source state. Later holder families such as banks, vendors, mail, crafting, and richer nested-container UX must reuse this holder and guarded-handoff model rather than introducing parallel item ownership.

### Item Identity, Visible References, and Stackability

Definition equality does not imply fungibility. Each physical item remains a distinct instance with a durable compact visible reference on explicit management and targeting surfaces; ordinary prose does not expose those refs by default. Authored `stackable`, `stackCompatibilityMode`, and `stackVariantKey` determine whether compatible physical items can merge into a holder-local stack. Quantity belongs to stack records, not to the ordinary instance identity.

Stack merge is eager within the holder and preserves the stack family through transfers. Ambiguous selections reject rather than guessing. More sophisticated authored compatibility inputs and any non-merging escape hatch require a real product consumer before the current direct model broadens.

### Actor State, Conditions, and Evaluated Effects

Entity Management persists actor resources and active conditions. `QueryActorState` evaluates the current view by overlaying release-pinned baseline stats, resources, non-expired conditions, equipped-item contributions, and replay-guarded transient action state. `STATUS` and `STAT` expose that evaluated state. `BLOCK` and `GUARD` prove the first bounded transient `blocking` condition through the shared actor-state/effect seam.

The first evaluator is deterministic and in-process. Game-authored release-pinned definitions own named stats and conditions; continuous sources contribute during evaluation, while instant effects mutate durable state. Disposition is separate from resource floors, conditions, and transport/session presence.

### Authored Actions, Targeting, and Outcomes

Admitted release bundles provide the current runtime declaration authority. An authored command snapshots its release bundle, version, canonical command, and declared effect on the durable command before enqueue; execution validates that immutable snapshot and current gameplay identity before replay lookup. The live v1 executor supports only a self-targeted `APPLY_ACTION_STATE` declaration and forwards the declared payload to Game Logic rather than substituting a built-in action payload. Missing, mismatched, malformed, effectless, or unsupported declarations fail closed.

The designed target model is broader but intentionally not claimed live: actions declare target sets, source/target semantics, selection policy, required versus optional outcomes, costs, cooldowns, durations, and shared effect execution. `EXACTLY_ONE`, bounded `UP_TO_N`, operator-capped `ALL_ELIGIBLE`, player-selected, canonical-order, typed ranked, and deterministic seeded-random selection are the target policy vocabulary. Required unresolved selection rejects before commit; optional unresolved selection has an explicit no-mutation outcome.

### Durable Effects, Replay, and Idempotency

Durable `effectId` derives deterministically from `tickBatchId` and a stable same-batch-unique `effectKey`. Game Session records durable `APPLIED`, `REPLAY_NOOP`, and `REJECTED` outcomes after ownership fence checks. Mutating services own duplicate application: movement, Game Session communication/activity/action-state/authored-action execution, Entity Management item/equipment/container/condition mutation, and Social Groups message persistence each return stored or no-op outcomes instead of applying a second consequence.

There is no misleading global guard table. The canonical pattern is durable identity plus an owning-domain uniqueness/replay boundary and observability. Future mutating families must adopt this contract before they become authoritative; transfer replay remains an explicit caller contract rather than an accidental side effect of holder mutation.

### Unified Actors and Combat Direction

The repository does not yet have a general persisted actor runtime model. Current `WHO` is player-presence oriented, and god/admin behavior is a player role/presentation overlay. The locked direction is one opaque actor core shared by `PLAYER` and `NPC`, with World Management owning location, Entity Management owning actor identity and durable actor state, and Game Session owning the session projection. Presence and disposition remain separate concerns.

Damage, mitigation, combat timing, body regions, defeat, corpses, loot, respawn, and revival are not implemented. Future combat consumes the shared actor/effect model; resource floors cannot become a hidden death or victory system, and Game Session cannot become combat-state authority.

## Active Gaps

- Generic authored actions, multi-effect execution, costs, cooldown lifecycle, cross-actor targeting, target-resolution evidence, and structured general action outcomes remain future work.
- The generic authored stat/condition runtime, resource-cost mutation, wider effect catalog, and combat consumption remain beyond the first evaluated actor-state seam.
- A unified persisted actor model, NPC runtime instances, actor-state linkage, and generic targeting/communication adoption are designed but not implemented.
- Combat design is locked at the architectural level but has no damage/mitigation implementation yet.
- Future holder families and new gameplay mutations must adopt the direct-holder, scoped-playable-state, durable-effect, and owning-domain replay contracts rather than introducing shortcuts.

## To Discuss

No competing target state is currently recorded for direct item holders, authored-definition snapshots, owning-domain replay guards, or the future actor/effect/combat direction. Future work needs design discussion before expanding action targeting semantics, adding a new stack-compatibility source, defining cross-domain transfer replay, or implementing combat timing and defeat lifecycle. The exact historical detail remains in the source evidence.

## Service and Contract Map

| Owner | Current responsibility | Primary contract boundary |
| --- | --- | --- |
| Game Design | Versioned DML definitions, release bundles, action/state/equipment/targeting policy | Game Design release and definition contracts |
| Game Session | Command ingress, durable command/tick execution, effect identity, player-facing delivery | Durable command/effect records and gameplay command contracts |
| Game Logic | Gameplay orchestration, selector/target resolution, rule evaluation, structured outcomes | Gameplay/action gRPC contracts |
| Entity Management | Items, holders, containers, equipment, actor resources/conditions, transfer audit, idempotent mutations | Entity REST/gRPC and durable mutation replay records |
| World Management | Rooms, locations, occupancy, spatial facts | World/read and movement contracts |
| Social Groups | Durable communication mutation replay | Communication persistence and fanout contracts |
| Account and Common Security | Account identity, caller scope, gameplay attestation | Auth/session and gameplay-attestation helpers |
| Automation Scripting | Future effect-producing automation, never a substitute for owning-domain mutation authority | Script/event handoff contracts |

Focused item, transfer, stackability, actor-state, action-state, authored-action, effect-idempotency, and replay proofs remain recorded with exact commands in the source evidence. Spark coverage review will verify the consolidated statements against each allocated range before this tracker is marked fully reviewed.

## Source Evidence

The following records are the unchanged line-preserving transposition used as the audit backstop for the consolidated record above. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-38-42-50-89-98-99

#### Authored Action Definition and Execution Model Vertical Slice - Runtime execution, targeting, and effect semantics (source lines 38-42, 50, 89, 98-99)

##### Preserved Source Text: source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-38-42-50-89-98-99

<!-- migration-source path="design/project-management/vertical-slices/02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md" lines="38-42, 50, 89, 98-99" sha256="a0680c6e555d3f3e33c990005c3359128e80676b2612eeef7d67d76438ced32f" heading-offset="3" -->
- accepted authored ingress snapshots the admitted release-bundle id, version id, canonical command id, and declared effects on the durable gameplay command rather than relying on a later live registry read;
- durable execution now accepts only one snapshotted `APPLY_ACTION_STATE` v1 declaration, validates it before replay lookup, applies it through the existing replay-backed actor-condition seam, and fails closed for missing or unsupported snapshots;
- authored action-state execution now also requires the durable command's recorded tenant/game/character identity to still match the resolved session before replay lookup, so a session switch cannot apply a queued action to a later gameplay identity;
- runtime admission likewise queues only definitions with that single supported effect; effectless or future multi-effect definitions remain discoverable but return the canonical execution-unavailable outcome without a durable enqueue;
- the action-state adapter forwards the declared effect payload to Game Logic rather than substituting a built-in `BLOCK` payload.
<!-- source-gap: lines 43-49 -->
Current runtime follow-through: Game Session resolves authored aliases, direct HELP topics, authored dispatch validation, and first-party command-result action metadata against the release bundle admitted for the live game instance. A gameplay invocation resolves and persists its exact admitted declaration snapshot before durable enqueue. Durable execution uses that snapshot even when the currently admitted registry has changed, validates the first supported `APPLY_ACTION_STATE` v1 shape, and applies it through the existing replay-backed actor-condition seam. A missing, mismatched, malformed, or unsupported snapshot fails closed before replay or script publication. There is no configuration-backed authored-command catalog or test-profile fallback in runtime code.
<!-- source-gap: lines 51-88 -->
- Effects should eventually flow through the shared effect engine rather than action-local math.
<!-- source-gap: lines 90-97 -->
- Authored actions snapshot DML `ActionTargetSet` declarations and their referenced targeting/selection policies. `SOURCE` is implicit; each action-owned effect names `SOURCE` or one declared target set. `EXACTLY_ONE`, bounded `UP_TO_N`, and operator-capped `ALL_ELIGIBLE` combine with `PLAYER_SELECTED`, `CANONICAL_ORDER`, typed `RANKED`, or deterministic effect-id-seeded `RANDOM_SEEDED` selection; an unresolved required set rejects before commit, while an unresolved optional set has an explicit no-mutation outcome.
- Costs, cooldowns, durations, and effects remain consumers of the later shared timing/effect substrate instead of being reinvented here.
<!-- /migration-source -->

### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154

#### `02.18.10` Effect Idempotency and Replay Guards - Gameplay effect idempotency and replay guards (source lines 1-154)

##### Preserved Source Text: source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154

<!-- migration-source path="design/project-management/vertical-slices/02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md" lines="1-154" sha256="26249286191b6ef8090064fd5b81a89373ff8e7106ac8efc668ef39845b395c4" heading-offset="3" -->
#### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: `02.18.10` Effect Idempotency and Replay Guards

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Goal and Status

Goal: add durable effect-identity and idempotency guards so replay, retry, and failover converge safely without double-applying gameplay consequences. Status: complete at the current bounded boundary.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Checklist

- [x] Define target-state behavior and scope.
- [x] Discussion pass with user before implementation.
- [x] Implement the first current-runtime proving-ground boundary end-to-end.
- [x] Verify and close later owning-domain follow-ups.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Why This Slice Exists

The current architecture already assumes `EffectId`-backed replay safety, but the runtime does not yet consistently implement that substrate:

- effect identity is not yet a durable first-class runtime boundary across the tick path;
- later retry/replay semantics therefore remain weaker than the architecture docs intend;
- without durable idempotency guards, duplicate application risk remains a structural concern under crash/retry/failover.

This slice exists so replay safety becomes concrete runtime behavior rather than staying an architecture promise.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Discussion Gate

The discussion gate has been cleared. The agreed first pass is:

- start by making `tick_effect` identity concrete at the current Game Session batch/effect ledger boundary rather than pretending domain-level guards already exist everywhere;
- add one durable human-readable `effectKey` plus deterministic `effectId` derivation in the current runtime;
- defer domain-level guard tables and replay/no-op mutation semantics to the later participating gameplay services once this identity substrate exists.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Implementation Notes

First current-boundary effect-identity substrate now live:

- `tick_effect` now persists one bounded stable `effectKey` alongside `effectId` instead of relying only on a random row identifier.
- the current schema now also enforces `effectKey` uniqueness within one durable `tickBatchId`, so same-batch duplicate logical effects fail fast instead of drifting into silent duplicate ledger rows.
- The current first `effectKey` vocabulary is honest and narrow:
  - `command:<commandId>` when the gameplay command already has a durable command identity
  - `command-text:<hash>:slot:<index>` when the current queue entry has no durable `commandId`
- The current `effectId` is now deterministic from `tickBatchId + effectKey`, so replay of the same durable batch does not invent new row identity for the same logical effect entry.
- Game Session now has a first real idempotent-apply boundary instead of only ledger identity:
  - movement effects use one dedicated `MovementEffectIdempotencyService` keyed by `effectId`;
  - the apply seam is tied to the authoritative session-context mutation, so a replayed move converges to `REPLAY_NOOP` instead of applying the room change a second time;
  - durable effect execution now resumes from `DRAINED` ledger rows after restart, then marks each effect `APPLIED`, `REPLAY_NOOP`, or `REJECTED` as it converges.
  - stale ownership is checked again before applying drained effects; an old executor abandons and requeues unapplied effects instead of invoking the idempotent apply seam under the wrong fence.
- Entity Management now has the first cross-service domain guard for item/equipment/container mutations:
  - Game Session sends the durable `tick_effect.effectId` on `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE` downstream mutation RPCs;
  - Entity Management persists one `entity_mutation_effects` row per `{tenantId, effectId}` and stores the applied protobuf response bytes for replay;
  - duplicate delivery of an already-applied effect returns the stored response instead of applying the inventory/container/equipment mutation again.
  - Entity Management emits `entitymanagement.mutation.effect.execution{operation,effect_status}` so first-apply, replay/no-op, in-progress conflict, rejected reuse, and unreadable stored-response outcomes are observable.
- Entity Management now also uses that same durable replay substrate for the first richer actor-state mutation seam:
  - `ApplyActorCondition` now routes through `EntityMutationEffectReplayService` instead of depending only on local active-condition source-identity dedupe, with the current wire contract carrying the durable effect id as actor-condition `sourceId`;
  - duplicate durable `effectId` delivery replays the stored `ApplyActorConditionResponse` instead of reinvoking `ActorConditionMutationService`;
  - the first action-state actor-condition family therefore now shares the same durable replay and observability contract as the earlier Entity Management item mutation families.
- Game Session now has the first non-movement local replay guard for command families it owns directly:
  - durable `SAY`, `WHISPER`, `TELL`, and `AFK` execution now stores one replay record per `{tenantId, sessionId, effectId}`;
  - replayed durable communication/activity effects return `REPLAY_NOOP` and re-deliver only the actor-facing outputs, instead of re-invoking the underlying communication or AFK mutation handler a second time;
  - this keeps later drain/replay/failover passes from duplicating the Game Session-owned mutation seam even though those commands do not use the Entity Management replay table.
- Game Session now also uses that same local replay-guard pattern for the first action-state mutation family:
  - durable `BLOCK` execution stores the same `{tenantId, sessionId, effectId}` replay record shape;
  - replayed durable action-state effects re-deliver actor-facing outputs and converge to `REPLAY_NOOP` instead of reapplying the mutation.
- the remaining Game Session local replay-backed mutation families now also converge on one shared execution helper inside `DefaultDurableGameplayCommandExecutionService`, so communication, AFK, action-state, and authored-action replay lookup/save/deliver behavior no longer drifts across near-identical local implementations.
- Social Groups now has the first downstream communication replay guard:
  - Game Session propagates the durable `tick_effect.effectId` through Game Logic into Social Groups `SendMessage`;
  - Social Groups persists one `chat_messages.effect_id` value per applied communication mutation and enforces `{tenantId, effectId}` uniqueness;
  - duplicate delivery of the same durable communication effect now returns the stored chat message DTO instead of persisting or redis-publishing the chat message a second time.
- This is still not the full target state:
  - Game Session is not yet using one cross-service/domain guard table;
  - only the first participating gameplay mutation boundary currently returns explicit replay/no-op outcomes on that identity;
  - the first durable executor metrics seam is now live via `gamesession.durable.effect.execution{command,effect_status,gameplay_result}` so first-apply versus replay/no-op outcomes are no longer only implicit in row state;
  - the current movement-backed guard, local communication/activity/action-state replay guards, and first Entity Management mutation guard are proving grounds that later slices can carry into broader domain guards and later owning mutation families.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Completion Evidence

- Durable effect-idempotency substrate:
  - [DefaultDurableGameplayCommandExecutionService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionService.java)
  - [RedisMovementEffectIdempotencyService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/RedisMovementEffectIdempotencyService.java)
  - [MovementEffectIdempotencyService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/MovementEffectIdempotencyService.java)
  - [EntityMutationEffectReplayService.java](../../../services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/EntityMutationEffectReplayService.java)
- Migration and replay-row constraint proof:
  - [V1__baseline.sql](../../../services/game-session-service/src/main/resources/db/migration/V1__baseline.sql)
  - [V1__baseline.sql](../../../services/entity-management-service/src/main/resources/db/migration/V1__baseline.sql)
- Focused validation:
  - [RedisMovementEffectIdempotencyServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/RedisMovementEffectIdempotencyServiceTest.java)
  - [DefaultDurableGameplayCommandExecutionServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionServiceTest.java)
  - [EntityMutationEffectReplayServiceTest.java](../../../services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/EntityMutationEffectReplayServiceTest.java)

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Current Remaining Work

- [x] Treat the current built-in mutation surface as sufficiently proved; do not force another replay-guard batch only to keep this slice busy.
- [x] Carry the same `effectId` replay/no-op contract into later owning domains as new authoritative mutation families land; the next bounded child slice is [`02.18.10.1`](../vertical-slices/02.18.10.1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice.md), and that owned authored-action follow-up boundary is now closed for this cut.
- [x] Keep later queue-source and ownership work in `02.18.8` and `02.18.9`; this slice is now about owning-domain duplicate-application convergence, not broader tick-substrate breadth.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Suggested Direction

The first implementation should build on:

- durable command ids;
- durable tick batch/effect ledger;
- region epoch/fence context.

Then add:

- stable `EffectId`
- durable effect guard rows or equivalent uniqueness boundary
- explicit replay/no-op outcomes for duplicate attempts

Owning services should treat duplicate `EffectId` application as:

- safe replay/no-op
- not second application

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Scope

- Define one canonical `EffectId` shape.
- Define one durable idempotency boundary for effect application.
- Define how replay/no-op outcomes are represented.
- Define how effect idempotency interacts with retries, crash recovery, and failover.
- Define the minimum shared helper or common pattern expected across services.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Out of Scope

- Full combat/effect-engine design.
- Every gameplay service migration in one slice.
- Full operator dashboards.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Target State

- Every replayable gameplay effect has a stable `EffectId`.
- Duplicate attempts converge to no-op/replay rather than double-apply.
- Effect application status is durable and operator-auditable.
- Replay behavior is consistent across services rather than ad hoc.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Recommended First Implementation Boundary

The first pass should likely:

1. define the first canonical ledger-side `effectKey` / `EffectId` projection;
2. persist it on durable effect rows;
3. add one shared idempotent-apply helper/pattern or guard-table pattern in the first participating domain;
4. prove replay/no-op behavior on one or two real gameplay mutations first;
5. only then broaden service coverage.

##### source-02-18-10-task-list-effect-idempotency-and-replay-guards-vertical-slice-1-154: Validation

- [x] Prove duplicate `EffectId` application becomes replay/no-op.
- [x] Prove crash/retry paths do not double-apply the same gameplay effect at the first participating movement/session-context seam.
- [x] Prove durable effect rows now carry stable `effectKey` plus deterministic ledger-side `effectId` across replay of the same durable batch.
- [x] Prove one shared helper/pattern is sufficient for the first participating services.
- [x] Prove domain-level item/equipment/container mutation replay converges safely at the Entity Management boundary.
- [x] Prove Game Session-owned communication/activity durable effects replay as no-op instead of re-invoking the local mutation handler.
- [x] Prove downstream communication persistence converges safely on duplicate durable `effectId` delivery.
- [x] Prove logs/metrics can distinguish first apply from replay across the first movement/Game Session and item/Entity Management seams.
<!-- /migration-source -->

### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90

#### 02.18.10.1 Task List: Authored-Action and Resource Effect Replay Guards Vertical Slice - Authored action and resource replay guards (source lines 1-90)

##### Preserved Source Text: source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90

<!-- migration-source path="design/project-management/vertical-slices/02.18.10.1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice.md" lines="1-90" sha256="8fbddd9576075187f48deeb9cdbb0bd49c3eaa1f8a5934f80a6d0c943c8f308c" heading-offset="3" -->
#### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: 02.18.10.1 Task List: Authored-Action and Resource Effect Replay Guards Vertical Slice

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Goal and Status

Goal: extend the now-live durable `effectId` replay/no-op contract into the next higher-level gameplay mutation seam, especially authored-action-owned effects and richer actor/resource mutations, so those families do not become a second duplicate-application path outside `02.18.10`. Status: complete at the current bounded boundary.

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Why This Slice Exists

`02.18.10` already proved the first replay/idempotency substrate across movement, item mutations, communication, action-state mutation, and downstream social-message persistence. One bounded adoption gap remains:

- later higher-level mutation families can still drift outside the `effectId` replay/no-op fence if they land after the first proving-ground batch;
- authored or richer resource mutations are exactly the kind of owning-domain follow-through that should reuse the same durable replay contract rather than inventing local duplicate guards;
- the current parent slice is explicit that later domain adoption is the remaining work.

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Scope

- the next real gameplay mutation seams above the current `02.18.10` boundary, especially authored-action-owned mutations and richer actor/resource mutation families where live;
- durable `effectId` propagation, replay/no-op storage, and owning-service duplicate application behavior for the touched families;
- focused proof for duplicate delivery, replay, and outcome observability on the touched seams.

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Out of Scope

- broader tick-batch or queue-source work already owned by `02.18.8` and siblings;
- full combat/effect-engine design;
- migrating every future mutation family in one batch.

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Locked Direction

- later mutation families should reuse the same durable `effectId` replay/no-op contract instead of introducing family-local duplicate semantics;
- duplicate application should remain an owning-service no-op/replay truth, not an accidental second mutation;
- this slice should converge one real next family set, not broaden into a generic architecture pass.

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Completed Work

###### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: 1. Next Owning-Family Audit

- [x] Enumerate the authored-action seam in scope for this bounded pass and keep richer actor/resource families deferred.
- [x] Prioritize authored-action-owned mutations for this boundary because they are the first high-level live mutation family outside the first proving-ground batch.
- [x] Skip stale or not-yet-real actor/resource families and keep them on the later 02.18.10 follow-through boundary.

###### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: 2. Replay-Guard Follow-Through

- [x] Carry durable `effectId` propagation into the touched authored-action family seam.
- [x] Add the owning-service replay/no-op guard and replay-record pattern for authored-action effects.
- [x] Keep replay outcomes aligned with `APPLIED`, `REPLAY_NOOP`, and `REJECTED` style vocabulary.

###### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: 3. Focused Proof and Docs

- [x] Add focused proof for duplicate authored-effect application, replay/no-op convergence, and touched metrics/log outcomes in durable command tests.
- [x] Update `02.18.10` docs/status so the next remaining adoption tail is explicit after this cut.
- [x] Re-run touched validation and Markdown/link proof.

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Completion Evidence

- Authored-action replay/no-op now participates in the durable command execution path through `EffectId`:
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/AuthoredActionRuntimeHandler.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/AuthoredActionCommandHandler.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionService.java`
- Focused tests proving idempotent authored-effect behavior are in:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionServiceTest.java`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionServiceTest#executeAppliesDurableAuthoredActionAndStoresReplay`
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/DefaultDurableGameplayCommandExecutionServiceTest#executeReplaysStoredAuthoredActionWithoutInvokingHandler`
- Current broader actor/resource replay-follow-through remains on `02.18.10` as a later follow-up boundary.

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Acceptance Shape

- the touched higher-level mutation families no longer sit outside the canonical `effectId` replay/no-op contract;
- duplicate delivery converges safely to replay/no-op instead of double application for the touched seams;
- focused proof covers owning-service replay behavior and observability for the touched family set.

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Spark Delegation Notes

- Audit first, then choose the smallest still-live authored/resource mutation family set worth converging now.
- Do not reopen the already-proved movement, item, communication, or `BLOCK` replay seams unless a touched family genuinely shares code there.
- Return the exact families covered, exact changed files, and exact validation commands run.

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Suggested Starting Surfaces

- `services/game-session-service`
- `services/entity-management-service`
- `services/automation-scripting-service`
- `design/project-management/vertical-slices/02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md`

##### source-02-18-10-1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice-1-90: Validation

- `./gradlew spotlessApply`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew :entity-management-service:check -PfullCheck`
- `./gradlew :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-03-task-list-data-driven-look-vertical-slice-41-51

#### Data-Driven LOOK Vertical Slice Task List - Visible-entity and containment-model facts (source lines 41-51)

##### Preserved Source Text: source-03-task-list-data-driven-look-vertical-slice-41-51

<!-- migration-source path="design/project-management/vertical-slices/03-task-list-data-driven-look-vertical-slice.md" lines="41-51" sha256="453637cf37341b1ff9ddd06777ed8f8b83c227bd29658ff5289121804a860782" heading-offset="3" -->
##### source-03-task-list-data-driven-look-vertical-slice-41-51: 3. Entity Management Service: Visible Entity Listings

- [x] Before changing this service for the slice, run `./gradlew :entity-management-service:test` and either get the existing tests passing or clearly document/temporarily disable any failing tests so the baseline is stable. *(ran successfully prior to these edits; see build log above or `./gradlew :entity-management-service:test` locally).*
- [x] Define or refine a minimal gRPC API in the Entity Management Service that can list entities visible in a room for `LOOK` (characters, NPCs, key items), keyed by `tenantId` and `roomInstance` at minimum.
- [x] Ensure the entity listing response is structured for gameplay text rendering (e.g., includes stable display names and simple flags like `isPlayer` / `isNpc` / `isItem` instead of eagerly exposing internal stats).
- [x] Seed a minimal set of entities in the rooms used by the test world (e.g., one demo player character, one NPC, and one visible item) with deterministic IDs and names to keep transcripts stable.
- [x] Add unit and/or integration tests in `services/entity-management-service` that verify the entity listing API returns the expected entities for the target rooms, including empty-room and multi-entity cases.
- [x] Update the Entity Management Service README/design docs with a subsection describing the `LOOK`-oriented entity listing API and how it fits with broader character and inventory design.

Sample data for this slice lives in the test fixtures referenced above: the World Management room fixtures define the rooms and exits, while `services/entity-management-service/src/main/resources/application.yml` holds the `firemud.look.rooms` entries that drive the entity transcripts. When future slices or transcripts need different rooms, add or edit entries in those fixtures and configuration so the documented scenarios stay in sync.

<!-- /migration-source -->

### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198

#### Inventory, Containers, and Equipment Vertical Slice Task List - Inventory and equipment (source lines 1-198)

##### Preserved Source Text: source-06-task-list-inventory-containers-equipment-vertical-slice-1-198

<!-- migration-source path="design/project-management/vertical-slices/06-task-list-inventory-containers-equipment-vertical-slice.md" lines="1-198" sha256="bc04825ce701f566216990125daeec874f26feb9434a5cd5c382ed845aaf7e32" heading-offset="3" -->
#### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: Inventory, Containers, and Equipment Vertical Slice Task List

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: Goal and Status

Goal: extend the current playable loop beyond `LOOK`, `SAY`, and movement so players can inspect and manipulate items through a unified item-holder model, with room-ground inventory, first-class item instances, equipment bindings, richer management queries, and later auditable transfer history. Status: partially implemented.

This slice builds on the current authenticated gameplay path, authoritative room state, and movement support. It has already delivered the first real item-interaction loop rather than remaining a purely planned item-system rewrite.

Scope note: this slice should establish the canonical container/equipment/audit model and prove the first player-facing item actions such as `INVENTORY`, `GET`, `DROP`, and one equipment action. It should not try to solve crafting, shops, banks, loot generation, or deep scripted item behaviors in the same change.

Architectural note: inventory, equipment, room-ground items, and containers should remain one shared item-holder and transfer system with different holder kinds and presentation rules, not separate gameplay subsystems. The dedicated convergence follow-up is captured in `06.4-task-list-unified-item-holder-and-transfer-model-vertical-slice.md`.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: Implementation Notes

The current branch state is materially ahead of the original `06` plan:

- `INVENTORY`, `GET`, `DROP`, `EQUIPMENT`, `WEAR`, `REMOVE`, `CONTAINER`, `PUT`, and `TAKE` all exist as live gameplay command paths;
- room-ground management is now surfaced through `INV HERE` rather than forcing room prose to carry management semantics;
- inventory, equipment, room-ground items, and container contents are now backed by persisted `item_instances`;
- stable compact visible refs such as `satchel12` are now allocated and surfaced through management views and exact-item matching;
- the gameplay command layer has its first bounded unification pass through `ItemCommandHandler`;
- the remaining work under `06` is no longer "start item interactions", but tightening the canonical holder/transfer contract, adding explicit authored stackability, and aligning audit/validation semantics.
- successful inventory, room-ground, equipment, and container-holder transfers now persist canonical `item_transfer_audits` rows for both item-instance and stack-backed movement in the same local transaction boundary as the mutation.
- replay-safe gameplay mutation requests now also thread durable `effectId` plus attested gameplay `sessionId` into those transfer-audit rows, so operator audit history can line up with the first live Entity Management replay guard and the owning player session instead of treating durability and item audit as separate tracks.
- WebSocket cross-service coverage now proves `LOGIN` / `PLAY` / `LOOK` / `INV HERE` / `GET` / `CONTAINER` / `PUT` / `TAKE` / `DROP` / `INV HERE` through the live Game Session command path, including parser preservation of `INV HERE`, nearby room-ground container interaction, room-ground state changes, inventory/container refreshes, Entity Management request context, quantity, and nonblank durable effect ids.
- WebSocket cross-service coverage also proves a carried-item equipment loop with `EQUIPMENT` / `WEAR` / `EQUIPMENT` / `REMOVE` / `EQUIPMENT`, plus a representative `SLOT_INCOMPATIBLE` failure, including Entity Management equipment request context and nonblank durable effect ids.
- Telnet cross-service coverage now proves the same player-visible item/container/equipment command surface through TCP Proxy and Gateway, including `LOOK`, room-ground pickup/drop, nearby room-ground container interaction, equipment bind/unbind, incompatible equipment failure, and Entity Management request/effect-id assertions.
- Game Session now records central item-command invocation/failure metrics through `gamesession.command.item.*` with `type` and `error` tags, so operators can distinguish expected player mistakes from backend or validation failures consistently across inventory, equipment, and container verbs.
- The developer smoke guide and player playtest checklist now include the WebSocket/Telnet item-container-equipment extension: `INV HERE`, `GET`, `INVENTORY`, `CONTAINER`, `PUT`, `TAKE`, `DROP`, `EQUIPMENT`, `WEAR`, `REMOVE`, and an incompatible-equipment error check where the fixture exists.
- Game Logic now exposes the first item/container/equipment runtime RPC seam, so Game Session text handlers dispatch `INVENTORY`, `INV HERE`, `GET`, `DROP`, `CONTAINER`, `PUT`, `TAKE`, `EQUIPMENT`, `WEAR`, and `REMOVE` through Game Logic before Entity Management persistence is touched.
- Game Logic now owns player-facing room-ground and carried-item selector resolution for `GET` and `DROP`: Game Session passes the current session/game/room context plus raw item reference and quantity, Game Logic lists the appropriate holder surface, resolves names/visible refs/container refs/stack refs, and then delegates the concrete mutation to Entity Management.
- Successful `GET` / `DROP` responses now also refresh both mutated holder views in one command response, so the immediate transcript shows carried inventory plus current room-ground state instead of collapsing post-mutation feedback back to the carried side only.
- `InventoryViewOutput` now also carries additive typed item entries plus optional container view context, so first-party/replayed inventory-family payloads expose refs, quantities, descriptions, slots, and viewed-container identity directly instead of forcing structured clients to scrape that metadata back out of transcript lines.
- First-party WebSocket cross-service proof now asserts those enriched `inventory_view` payloads end-to-end across `GET`, `CONTAINER`, and `EQUIPMENT`, so the typed item metadata/context seam is no longer only covered in unit/replay tests.
- Successful `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE` responses now use one typed item-mutation notice carrying the action, mutated item entry, and source/target holder context. First-party WebSocket envelopes expose it as `item_mutation_result`, while Telnet and generic WebSocket retain the existing canonical transcript prose through the shared renderer.
- First-party WebSocket cross-service proof now covers every typed item mutation: room-ground pickup/drop, container put/take with container identity, and equipment wear/remove with slot identity. The generic WebSocket and Telnet loops continue to prove the shared transcript prose.
- Entity Management now has the first game-configured equipment schema substrate: versioned slot definitions, slot groups, body-layout slot membership, character body-layout keys, and runtime equipment validation against those authored concepts.
- the authored stackability/fungibility follow-up is now tracked explicitly in `06.3.2-task-list-authored-stackability-and-fungibility-vertical-slice.md`.
- `06.1` now confirms the settings boundary: inventory command availability is tenant/game policy, while slot/body/compatibility/stackability facts remain versioned DML-backed design data rather than a duplicate settings domain.

The most important remaining design work in this slice family is:

- make the canonical transfer contract explicit;
- decide whether the direct holder fields on `item_instances` are the canonical runtime model or only an implementation step toward a more abstract holder contract;
- tighten shared transfer-audit and validation language across all holder kinds;
- finish the explicit authored stackability follow-up on top of the now-stable item-instance truth.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: First Implementation Boundary

The first narrow implementation for `06` should start from the authoritative runtime model, not from parser or transcript polish.

Recommended first order:

1. strengthen the authoritative inventory/runtime contract in `entity-management-service`
2. add room-ground storage as the first visible non-inventory location
3. expose the first player-facing command loop:
   - `INVENTORY`
   - `GET <item>`
   - `DROP <item>`
4. add presentation/help coverage for that loop
5. defer general named containers, nested containers, and full equipment/body-layout behavior until the runtime path is solid

This ordering is now historical context rather than future plan:

- room-ground storage did become the first visible transfer target/source;
- `LOOK` remains room-context output rather than a replacement for inventory/equipment queries;
- equipment and named containers are already in the live `06` command surface;
- the active follow-up work has moved from MVP verb enablement to architectural convergence and instance identity.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: 1. Design Alignment for Containment, Equipment, and Audit

- [x] Re-read the [Entity Management Service](../../architecture/microservices/entity-management-service/README.md), [Entity Management runtime/data model](../../architecture/microservices/entity-management-service/runtime-and-data.md), [World Management Service](../../architecture/microservices/world-management-service/README.md), and [Game Logic Service](../../architecture/microservices/game-logic-service/README.md) docs to confirm the ownership split for room identity, room-ground containers, item instances, hidden inventory containers, and equipment bindings.
- [x] Update the inventory- and item-related design docs so they describe one canonical target-state model:
  - character/NPC inventory is a hidden container owned by that runtime entity;
  - room-ground inventory is a room-attached container identified from authoritative room instance identity;
  - equipped items use first-class equipment bindings rather than "bag position with a flag";
  - slot definitions and body layouts are game-configured rather than platform-global enums.
- [x] Keep the design explicit that inventory, equipment, room-ground, and container contents are all holder kinds within one transfer model, not different item ontologies or unrelated command subsystems.
- [x] Decide and document the minimum player-facing protocol surface for the first inventory slice, including at least `INVENTORY`, `GET <item>`, `DROP <item>`, and one equipment action such as `WEAR` / `EQUIP` or `REMOVE`.
- [x] Explicitly document that the first MVP command loop is expected to land as:
  - `INVENTORY`
  - `GET <item>`
  - `DROP <item>`
  and that named containers plus richer equip/unequip flows may remain one bounded follow-up if they jeopardize the first transfer proof.
- [x] Document the canonical success and failure transcript shapes for both WebSocket and Telnet, including at least one successful pickup from room ground, one successful drop to room ground, one successful equipment change, and one failure such as `ERROR ITEM_NOT_FOUND` or `ERROR SLOT_INCOMPATIBLE`.
- [x] Document the canonical audit requirement for inventory/equipment mutation so future implementation treats item movement as an auditable core invariant rather than optional observability.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: 2. Entity Management Service: Runtime Containment and Query Contract

- [x] Before changing this service for the slice, run `./gradlew :entity-management-service:test` and stabilize the baseline if necessary.
- [x] Replace or extend the current weak inventory-facing contract (`QueryInventory -> item_ids[]`) with a richer inventory query shape that can return item instance metadata, container/equipped state, quantity, and game-defined type/tag information needed for gameplay and future GUIs.
- [x] Treat this authoritative runtime contract as the real starting point for `06`; do not begin by adding command text without first landing the inventory/query/transfer model that the command path will call.
- [x] Introduce or refine explicit runtime records for:
  - containers;
  - containment entries;
  - equipment bindings;
  - room-ground containers attached to `(tenantId, gameInstanceId, roomInstanceId)`.
- [x] Keep hidden/internal inventory containers implementation-owned in the first pass rather than directly player-addressable.
- [x] Ensure equipped items are not simultaneously represented as normal inventory-container members while equipped unless the design is deliberately revised and documented. The default target state is one authoritative location/binding per item instance.
- [x] Define the first mutation contract(s) needed for the slice, such as transfer item between container and room-ground container, bind item to equipment slot, and unbind item back to inventory container.
- [x] Add or refine validation rules for:
  - missing item instance;
  - inaccessible source/destination container;
  - invalid or full slot/body-layout incompatibility;
  - room/instance mismatch;
  - stack split/merge invariants if stacking is in scope for MVP.
- [x] Add unit/integration tests for hidden inventory containers, room-ground containers, equipment bindings, and basic query filtering behavior.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: 3. Entity Management Service: Inventory Transfer Audit

- [x] Introduce a canonical audit/event record for inventory and equipment mutation covering:
  - item instance id;
  - item definition/template id;
  - quantity or stack delta;
  - source container/binding;
  - destination container/binding;
  - actor/session/effect/correlation identifiers;
  - tenant/game instance/room context;
  - action reason such as `pickup`, `drop`, `put`, `take`, `equip`, `unequip`, `create`, `destroy`, `split_stack`, `merge_stack`, or `admin_grant`.
- [x] Ensure the authoritative containment mutation and audit write happen in the same local transactional boundary where feasible so the system does not acknowledge state changes without a corresponding audit trail.
- [x] Document the intended operational use of this audit trail for item-duplication investigations, suspicious transfer analysis, and later invariant checks.
- [x] Add tests for at least: successful audited transfer, failed transfer producing no committed audit row, and deterministic correlation data on retried/idempotent operations.

Current implementation note:

- the live audit record is `item_transfer_audits`;
- current callers populate verb, actor character, item/item-instance identity, quantity or stack-family delta, and source/destination holder context;
- durable gameplay item/equipment/container mutations now populate both `effectId` and attested `sessionId`, and use `effectId` as the explicit audit correlation key when present;
- the default deterministic correlation key is derived from verb, actor, item identity, quantity/family, and holder endpoints so repeated identical calls produce stable audit correlation data without pretending to offer broader replay semantics.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: 4. Game Design Service and Configurable Equipment Model

- [x] Before changing this service for the slice, run `./gradlew :game-design-service:test` and stabilize the baseline if necessary.
- [x] Define or refine the design-time model for configurable slot definitions, body layouts, or equivalent equipment schemas so different games can define species/archetype-specific attachment points.
- [x] Avoid hardcoding a universal humanoid slot enum as the authoritative model. Familiar names like `head` or `left_hand` may appear in data, but they must be game-configured concepts rather than platform truth.
- [x] Define how item templates declare equipment compatibility, such as slot groups, attachment rules, or other design-defined compatibility metadata.
- [x] Document how runtime entities resolve which slots exist for a given character/NPC/body layout and how that interacts with future species/class rules without dragging the slice into a full progression rewrite.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: 5. Game Logic Service: Item Interaction Resolution

- [x] Before changing this service for the slice, run `./gradlew :game-logic-service:test` and stabilize the baseline if necessary.
- [x] Introduce or refine gameplay-oriented RPCs for the first inventory actions, for example:
  - `QueryVisibleInventory` / `QueryContainerContents`;
  - `PickupItem`;
  - `DropItem`;
  - `EquipItem`;
  - `UnequipItem`.
- [x] Prefer landing room-ground pickup/drop orchestration before broader container semantics so the first player-visible loop is narrow and auditable.
- [x] Keep the Game Logic layer responsible for gameplay-facing validation and orchestration, while Entity Management remains authoritative for item/container/equipment persistence.
- [x] Ensure Game Logic can combine room visibility, room-ground container identity, session/character identity, and item-filter/query semantics into stable player-facing results.
- [x] Add unit tests covering successful pickup/drop/equip flows, invalid item names/selectors, incompatible slots, inaccessible containers, and backend error propagation.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: 6. Game Session Service: Text Command Wiring and UX

- [x] Before changing this service for the slice, run `./gradlew :game-session-service:test` and stabilize the baseline if necessary.
- [x] Extend the text command interpreter so the first inventory commands are authenticated gameplay commands using the same session/context guard already used by `LOOK`, `SAY`, and movement.
- [x] Add handlers for the initial item command set, mapping structured runtime results into canonical text/WebSocket responses without inventing a second competing inventory format.
- [x] Keep the first parser/handler surface intentionally narrow:
  - `INVENTORY`
  - `GET <item>`
  - `DROP <item>`
  and only add broader container/equipment verbs once the underlying runtime path is stable.
- [x] Decide and document how players refer to items in the MVP:
  - simple name matching;
  - stable item selectors;
  - ordinal disambiguation such as `GET 2.SWORD`;
  - or another explicit pattern.
- [x] Emit item-command metrics/logs with high-level error tags so operators can distinguish player mistakes (`ITEM_NOT_FOUND`, `SLOT_INCOMPATIBLE`) from backend failures.
- [x] Add unit/integration tests covering successful `INVENTORY`, `GET`, `DROP`, one equip/unequip action, unauthenticated access, and representative failure responses.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: 7. Cross-Service End-to-End Coverage

- [x] Add a WebSocket cross-service regression that performs `LOGIN` / `PLAY` / `LOOK`, picks up an item from the room-ground container, verifies `INVENTORY`, drops the item, and verifies room state again.
- [x] Add a Telnet-focused variant through TCP Proxy and Gateway covering the same path and asserting transcript parity with WebSocket up to framing differences.
- [x] Add at least one equipment-focused cross-service regression showing a successful bind to a configurable slot and a representative failure case such as incompatible slot/body-layout.
- [x] Assert the item path traverses the intended service boundary (Game Session -> Game Logic -> Entity Management, with World Management room identity where relevant) using logs, metrics, or interceptors similar to the existing LOOK/SAY/movement slices.
- [x] Ensure the regression coverage also proves the audit trail is written for successful transfer/equip operations.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: 8. Developer Workflows, Docs, and Examples

- [x] Add or update a smoke/manual verification sequence demonstrating `LOGIN` / `PLAY` / `LOOK` / `GET` / `INVENTORY` / `DROP` over WebSocket.
- [x] Add a second Telnet-oriented example with the same flow and at least one equipment action.
- [x] Update the Entity Management, Game Logic, Game Session, and Game Design docs with short implementation-status notes once the slice starts landing so readers can tell what is live versus deferred.
- [x] Update any user-journey or gameplay examples that currently imply rooms only show static descriptions; after this slice they should also reflect visible room items, carrying state, and basic equipment state where relevant.

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: 9. Final QA Checklist

- [x] Run the relevant Entity Management, Game Design, Game Logic, Game Session, and cross-service test targets for the item slice and confirm they pass.
- [ ] Manually verify one happy-path pickup/drop flow and one happy-path equipment flow over both WebSocket and Telnet.
- [x] Confirm the audit trail is written for successful item/equipment mutations and that representative failure paths do not leave partially applied state.
- [x] Confirm inventory queries can distinguish carried, equipped, and room-ground items in a way that is compatible with future filtered gameplay commands and richer GUIs.

---

##### source-06-task-list-inventory-containers-equipment-vertical-slice-1-198: Deferred Follow-Up

- Later slices can expand beyond the MVP item loop into stacking depth, nested container UX polish, crafting/material flows, banking/vendor inventories, loot generation, scripted item behavior, equipment durability, and richer client-side filtered inventory views.
- Additional follow-up slices may also introduce stronger invariant/alert tooling over the now-live inventory transfer audit trail once the canonical movement and equipment flows are stable.
<!-- /migration-source -->

### source-06-2-task-list-container-instance-identity-vertical-slice-1-91

#### `06.2` Replace Character-Scoped Container Contents With Container Instances - Container-instance identity and holder validation (source lines 1-91)

##### Preserved Source Text: source-06-2-task-list-container-instance-identity-vertical-slice-1-91

<!-- migration-source path="design/project-management/vertical-slices/06.2-task-list-container-instance-identity-vertical-slice.md" lines="1-91" sha256="1c6ab967e15a248f35de9464a1c9e053a5880cc15788a9b03410a616f18dddc4" heading-offset="3" -->
#### source-06-2-task-list-container-instance-identity-vertical-slice-1-91: `06.2` Replace Character-Scoped Container Contents With Container Instances

Goal: replace the first carried-container slice's character-scoped contents model with a durable container-instance model so container contents survive drop, pickup, and later ownership changes without leaking or stranding state. Status: complete.

##### source-06-2-task-list-container-instance-identity-vertical-slice-1-91: Implementation Notes

Current branch state now has the identity and location replacement in place:

- `container_instance_id` is the canonical carried/equipped container reference exposed through the gRPC API.
- Container contents are keyed by `container_instance_id`, not by `(character_id, container_item_id)`.
- Game Session container commands now use `container_instance_id`.
- Container-instance access now resolves against current holder or current room location instead of assuming the container is still carried.
- Container instances now move cleanly between carried, equipped, and room-ground states without losing contents.
- The first room-ground container command path is live too: players can inspect and use nearby room-ground containers without `LOOK` expanding nested contents inline.
- Later broader physical-item identity and stackability follow-through lives in `06.3`, `06.3.1`, and `06.3.2`.

##### source-06-2-task-list-container-instance-identity-vertical-slice-1-91: Why This Follow-Up Exists

The first container slice proved the player command loop for `CONTAINER`, `PUT`, and `TAKE`, but it intentionally took a narrower persistence model keyed by carried ownership. That model is not durable enough for broader container gameplay.

Current `container_contents` identity is effectively:

- `tenant_id`
- `character_id`
- `container_item_id`
- `item_id`

That means contents are keyed to the character currently carrying the container and to the container item definition, not to a concrete movable container instance.

This creates three bad classes of behavior:

- dropping a non-empty container leaves contents behind on the old character key;
- picking up or later transferring that container to another character makes the new holder see an empty-looking container while the old contents remain stranded;
- multiple identical containers for one character are not distinguishable from each other.

The identity and location problems are now both addressed on branch.

##### source-06-2-task-list-container-instance-identity-vertical-slice-1-91: Target State

- A container is a concrete tenant-local container instance, not an aggregate `(character_id, item_id)` inventory row.
- Container contents are keyed by `container_instance_id`, not by `character_id`.
- Dropping, picking up, equipping, or later transferring a container moves only the container instance location or holder.
- Contents remain attached to that same container instance unchanged across those moves.
- Access checks derive from the container instance's current location or holder, not from the contents key.
- The first durable slice may continue aggregating contained payload by `item_id + quantity` inside one container instance.
- Inventory and equipment query results surface `container_instance_id` for container-capable rows so Game Session can carry durable identity forward without a full item-instance rollout.
- A room-ground container is still visible as one room item in `LOOK`; nested contents remain hidden unless explicitly inspected through container commands.
- Nested containers remain out of scope.

##### source-06-2-task-list-container-instance-identity-vertical-slice-1-91: First Commit Boundary

The first coherent `06.2` implementation boundary should be:

- introduce `container_instances` and `container_instance_contents`;
- replace `container_item_id` with `container_instance_id` on carried-container APIs;
- surface `container_instance_id` in inventory/equipment query results for container-capable rows;
- update Game Session clients and container command handling to use `container_instance_id`;
- follow immediately with location-transfer semantics for drop, pickup, wear, and remove.

That boundary is intentionally narrower than full `06.2`. It fixes the bad identity model first without immediately widening into room-ground relocation, equipment relocation, or broader transfer flows.

##### source-06-2-task-list-container-instance-identity-vertical-slice-1-91: Required Changes

- [x] Add a first-class `container_instance` persistence model with a stable `container_instance_id`.
- [x] Link each container instance to:
  - `tenant_id`
  - container `item_id` as the definition/template
  - current holder/location metadata
- [x] Replace character-scoped `container_contents` identity with container-instance identity.
- [x] Update the carried-container APIs so container reads and writes resolve against `container_instance_id`.
- [x] Surface `container_instance_id` through inventory and equipment query results for container-capable rows.
- [x] Update access validation so the actor must be able to reach the container in its current location, not just carry an item with the same definition id.
- [x] Keep contained payload aggregated by `item_id + quantity` in the first durable pass; do not introduce full item-instance identity for every item yet.
- [x] Move container-instance location on drop, pickup, wear, and remove while preserving contents unchanged.
- [x] Remove the now-obsolete "empty before transfer" guard once location movement is validated.

##### source-06-2-task-list-container-instance-identity-vertical-slice-1-91: Explicitly Out Of Scope

- nested containers;
- full item-instance identity for every item in the game;
- room-ground containers as a broad player-facing slice before container-instance identity is in place;
- migration scaffolding that preserves the old character-scoped contents model.

##### source-06-2-task-list-container-instance-identity-vertical-slice-1-91: Validation

- [x] Add unit and integration coverage for:
  - dropping a non-empty container and picking it up again;
  - transferring a non-empty container between holders without losing contents;
  - two identical container definitions owned by the same character but holding different contents.
- [x] Add targeted proof that `LOOK` still shows room-ground container instances as room items while nested contents remain hidden until `CONTAINER` is used.
- [x] Confirm the resulting API and persistence model stay compatible with later `GIVE`, trade, mail, vendor, and room-ground container flows.
<!-- /migration-source -->

### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105

#### `06.3` Replace Aggregated Item Stacks With Distinct Item Instances - Concrete item-instance identity and mutation semantics (source lines 1-105)

##### Preserved Source Text: source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105

<!-- migration-source path="design/project-management/vertical-slices/06.3-task-list-container-item-instance-identity-vertical-slice.md" lines="1-105" sha256="d27305010029846504c6aaa57dc8e1f6848eb313dba21e09e8641f49b4e4729d" heading-offset="3" -->
#### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105: `06.3` Replace Aggregated Item Stacks With Distinct Item Instances

##### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105: Goal and Status

Goal: stop collapsing physical items by item definition so two identical satchels, swords, potions, backpacks, or chests can exist as distinct things with different locations, different state, and later different per-instance history or effects. Status: complete at the current bounded boundary.

##### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105: Implementation Notes

Current branch state after `06.2` is materially better:

- container contents are keyed by `container_instance_id`;
- container instances now move across carried, equipped, and room-ground states;
- room-ground interactions can now carry `container_instance_id` through the gameplay path.
- entity-management query surfaces now expose a first bounded `item_instance_id` seam where durable physical identity already exists today:
  - carried container items;
  - equipped container items;
  - room-ground container items;
- game-session inventory-style management views can now render compact explicit refs for those currently durable container/item instances.
- inventory, equipment, and room-ground runtime truth is now backed by persisted `item_instances` rather than aggregate `(holder, item_id)` rows;
- room-facing item entities now come from those same `item_instances`, so room selection and management selection stop disagreeing about which physical thing exists;
- the relevant mutation RPCs and game-session command path now carry optional `item_instance_id` so explicit refs can act on the same physical instance the query surface exposed.
- container contents are now also backed by `item_instances`, so carried inventory, equipment, room-ground, and contained items all share the same physical-item holder truth.
- item instances now persist canonical compact visible refs such as `oldchest10`, and those refs are surfaced through management queries plus exact-item matching paths.
- room-ground management now reads through a dedicated quantity-bearing room inventory query, so `INV HERE` can expose stable refs without depending on prose-oriented room entity output.
- the remaining authored stackability work has been split into `06.3.2` so the still-missing fungibility model is tracked as a real follow-up rather than a vague tail note.
- item definitions now expose an explicit `stackable` capability flag, and the first strict holder-local stack model is now live for inventory, room-ground, and container holders;
- explicitly stackable items now merge eagerly into `item_stacks` holder records keyed by a canonical compatibility fingerprint instead of persisting fake one-per-unit `item_instances`;
- non-stackable items still remain concrete `item_instances` with stable visible refs and exact-instance targeting;
- room entity reads now also surface room-ground stack rows so ordinary room prose and management reads continue to agree about what exists.

The current item-instance boundary is complete. Later authored stack-compatibility growth now belongs to narrower follow-up work rather than staying open in this parent.

##### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105: Why This Follow-Up Exists

`06.2` fixed the identity of container contents and holder transitions, but it did not fully fix the identity of physical item instances themselves.

Current remaining behavior still collapses by item definition in places like:

- `inventory` rows keyed by `(character_id, item_id)`;
- `room_ground_inventory` rows keyed by `(tenant_id, game_instance_id, room_instance_id, item_id)`;
- room-facing command matching that still assumes one actionable target per item definition per location.

That means these cases are still not modeled correctly:

- two identical satchels carried by the same character;
- two identical swords carried by the same character;
- two identical chests dropped into the same room with different contents;
- picking up, dropping, wearing, removing, or moving one matching item without affecting the other;
- later transfer flows that need to refer to one concrete item, not an aggregate stack;
- future durability, enchantment, provenance, theft, decay, or ownership rules that belong to one physical item instance.

##### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105: Target State

- Every physical item is a first-class item instance with its own stable internal identity.
- Container state remains attached to the concrete item instance that acts as the container.
- Carried inventory, equipment, room-ground storage, and container contents stop treating physical items as aggregate stacks in storage truth.
- Player-facing commands can resolve one concrete matching item even when multiple identical names exist.
- Generic references such as `get satchel`, `wear sword`, or `put torch in pouch` should use deterministic resolution rules when multiple matching items are available.
- Specific references such as `satchel12` should allow the player to bypass ambiguity and target one concrete item directly.
- The platform should choose one canonical displayed/tab-completed explicit instance format, and the default should strongly favor compact text-client tab completion over readability punctuation.
- The visible ref for a concrete item should be stable for the lifetime of that item instance, not renumbered per room or per current holder.
- The visible suffix should come from a stable monotonic per-type sequence, not a random GUID-like token.
- Non-stackable items remain distinct instances in storage and management views even when they share the same definition.
- True stack behavior is reserved for explicitly authored stackable items that are fungible by type and interchangeable in gameplay terms.
- Stackable items may merge into shared quantity-bearing stack state when their stack-compatibility fingerprint matches.
- Stackability is not an automatic consequence of "same item definition"; it must be explicitly authored.
- Containers, equipment, weapons, items with contents, items with durability, items with effects, and other stateful items should not stack.

##### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105: Required Changes

- [x] Introduce a first-class persisted identity for each physical item instance.
- [x] Replace aggregated inventory, equipment, room-ground, and container-content storage truth with item-instance identity.
- [x] Keep `container_instance_id` canonically linked to the corresponding item instance rather than letting container identity float separately from item identity.
- [x] Surface concrete item-instance identity through the entity-management API where Game Session must disambiguate one item from another.
- [x] Update command resolution so repeated names produce deterministic disambiguation behavior instead of hidden stack collapse.
- [x] Surface one canonical compact explicit item-reference form for output/help/tab completion while keeping the interpreter free to accept bounded equivalent readability aliases if they remain cheap and unambiguous.
- [x] Allocate stable lifetime-visible refs for item instances using a monotonic per-type sequence in the game/world namespace (tracked in `06.3.1`).
- [x] Define authored stackability as an explicit capability rather than a derived consequence of item sameness (tracked in `06.3.2`).
- [x] Define stack compatibility so only fungible interchangeable items merge into quantity-bearing stack state (tracked in `06.3.2`).
- [x] Keep ordinary prose views natural, while inventory/management views surface per-instance refs for non-stackable items.

##### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105: Recommended Slice Order

1. introduce persisted item-instance identity;
2. convert holder/location storage to reference item instances rather than `item_id` aggregates for non-stackable items;
3. allocate stable visible refs and surface them through query/command paths;
4. define and implement explicit fungible stack behavior for authored stackable items;
5. update query APIs and command resolution to use concrete item instances plus explicit stacks;
6. expand exact-instance targeting proofs across inventory, equipment, room-ground, and containers.

##### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105: Explicitly Out Of Scope

- nested containers;
- general mail, vendor, auction, or trade flows;
- creator-authored container naming or labelling systems.

##### source-06-3-task-list-container-item-instance-identity-vertical-slice-1-105: Validation

- [x] Prove that two identical carried containers can hold different contents simultaneously.
- [x] Prove that two identical carried non-container items can exist and be targeted independently.
- [x] Prove that two identical room-ground items can be dropped, listed, and picked up independently.
- [x] Prove that explicitly stackable fungible items merge correctly into quantity-bearing stacks.
- [x] Prove that non-stackable same-definition items remain separate instances even when adjacent in the same holder or room.
- [x] Prove that management views such as inventory-style listings show stable refs for non-stackable instances where durable identity already exists.
- [x] Prove that `CONTAINER`, `PUT`, `TAKE`, `DROP`, `GET`, `WEAR`, and `REMOVE` continue to act on the correct concrete item instance when duplicates exist for the currently implemented item-instance-backed holder classes.
<!-- /migration-source -->

### source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72

#### `06.3.1` Stable Item Instance Visible Ref Allocation - Stable item-instance visible references (source lines 1-72)

##### Preserved Source Text: source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/06.3.1-task-list-item-instance-visible-ref-allocation-vertical-slice.md" lines="1-72" sha256="bf0bba0d7b792faa345e91da4566dde9554927915ae7727f5757cd5761c5f1eb" heading-offset="3" -->
#### source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72: `06.3.1` Stable Item Instance Visible Ref Allocation

##### source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72: Goal and Status

Goal: allocate one canonical compact visible ref for each concrete item instance so management views, exact targeting, and tab completion stop depending on ad hoc fallback identifiers. Status: complete at the current bounded boundary.

##### source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72: Implementation Notes

The first bounded allocator pass is now live:

- `item_instances` persist `visible_ref_token`, `visible_ref_sequence`, and canonical `visible_ref`;
- entity-management allocates refs through a shared per-token counter instead of synthesizing them client-side;
- inventory, equipment, container contents, room-ground query surfaces, and room-entity reads now expose the canonical visible ref;
- game-session management views and exact-item matching now use the persisted visible ref instead of deriving compact refs from container ids or item names;
- room-ground management is now surfaced through `INV HERE`, so players can discover and use exact refs for dropped duplicate items without polluting ordinary `LOOK` prose.

Current remaining work now belongs to later owning slices rather than this bounded visible-ref cut:

- future NPC or non-player actor duplication work should reuse the same explicit targeting pattern where needed;
- ordinary prose views should continue to keep refs hidden by default unless a later deliberate disambiguation surface owns that tradeoff; and
- authored stackability growth should continue on top of the now-stable instance-ref substrate without reopening visible-ref allocation itself.

Locked direction for prose exposure:

- visible refs are targeting-surface-first, not ordinary-prose-first;
- inventory, equipment, container, room-ground management, `HERE`-style room-inventory views, and other explicit targeting/management surfaces remain eligible to show refs;
- ordinary room prose and ordinary action transcripts should not show refs by default;
- if a future prose-facing surface truly needs disambiguation, that should be an intentional fallback/disambiguation mode rather than the default presentation style.
- all non-player item and entity instances should have a stable visible ref that is available on some explicit targeting surface even when ordinary prose hides it.
- players are the special case: room/inventory targeting surfaces identify them by character name rather than by an added type-plus-sequence visible ref.

##### source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72: Why This Follow-Up Exists

`06.3` establishes physical item-instance truth. This narrower follow-up turns that identity into the stable player-facing explicit reference model discussed for commands such as `get satchel12`, `put torch in pouch7`, and later duplicate-NPC targeting through explicit room-targeting surfaces such as `HERE`.

The visible ref system needs its own bounded slice because it spans:

- storage/allocation rules;
- API surfacing;
- management-view rendering;
- exact-target command matching;
- help and tab-completion conventions.

Keeping that work as a dedicated follow-up makes `06.3` easier to track while container/inventory/room-ground holder conversion is still being completed.

##### source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72: Target State

- Every concrete item instance has one stable visible ref for its lifetime.
- Every concrete non-player entity that requires duplicate-safe explicit targeting should also have one stable visible ref exposed on at least one explicit targeting surface.
- The default canonical visible form is compact and text-client-friendly, such as `satchel12`.
- The numeric suffix is allocated from a monotonic per-type sequence in the game/world namespace.
- Visible refs do not renumber when the item moves rooms, owners, or holders.
- Inventory-style and management views surface those refs for non-stackable item instances.
- Explicit room-inventory / `HERE`-style targeting views surface those refs for eligible non-player entities as well as item instances.
- Command resolution can use those refs for exact-item targeting without guessing from display names alone.
- Player characters remain targetable by character name rather than by a generated visible ref.

##### source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72: Required Changes

- [x] Persist the visible-ref allocation fields needed to derive canonical `type + sequence` references.
- [x] Define the allocation scope and uniqueness rule in code and docs.
- [x] Surface the visible ref through the relevant query APIs used by inventory/equipment/container/room management views.
- [x] Render the canonical visible ref in management views for non-stackable item instances.
- [x] Use the canonical visible ref in exact-item command matching.
- [x] Keep ordinary prose views natural unless exact disambiguation is required there.

##### source-06-3-1-task-list-item-instance-visible-ref-allocation-vertical-slice-1-72: Validation

- [x] Prove that two same-type item instances receive different stable visible refs.
- [x] Prove that an item's visible ref remains unchanged when it moves between inventory, equipment, room-ground, and container holders.
- [x] Prove that management views show the canonical visible ref for eligible non-stackable items.
- [x] Prove that exact-item commands resolve by visible ref instead of display-name coincidence.
<!-- /migration-source -->

### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140

#### `06.3.2` Authored Stackability and Fungibility - Authored stackability runtime behavior (source lines 1-140)

##### Preserved Source Text: source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140

<!-- migration-source path="design/project-management/vertical-slices/06.3.2-task-list-authored-stackability-and-fungibility-vertical-slice.md" lines="1-140" sha256="9b54b163db7ddf74971a854d92881b2512b63d6f6d1a5a0dc3e204eeb8c6c28f" heading-offset="3" -->
#### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140: `06.3.2` Authored Stackability and Fungibility

Goal: add an explicit authored stackability model on top of the now-live item-instance storage so fungible items can intentionally merge into quantity-bearing stack state without regressing distinct physical item identity for ordinary equipment, containers, or other stateful items.

##### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140: Goal and Status

Status: complete at the current bounded boundary.

The current live `06.3` branch correctly treats ordinary items as distinct physical item instances. What does not exist yet is the authored seam that says when two instances are intentionally fungible and therefore eligible to collapse into one quantity-bearing stack view or storage record.

The first narrow authored seam is now live:

- item definitions now expose an explicit `stackable` capability flag, defaulting to `false`;
- the first holder-local stack record implementation is now live for inventory, room-ground, and container holders;
- explicitly stackable items now merge eagerly into `item_stacks` rows keyed by a canonical compatibility fingerprint;
- quantity-bearing query views now return merged stack rows for eligible stackable items while non-stackable items remain concrete `item_instances` with exact refs.
- item definitions now also expose an explicit `stackCompatibilityMode` plus authored `stackVariantKey`, and stack rows carry a canonical runtime `stackFamilyKey` where the compatibility mode requires more than plain definition-level sameness;
- inventory/container/room-ground stack mutations now preserve source stack family and reject ambiguous same-definition multi-family selection instead of silently merging or selecting one family arbitrarily.

The remaining core model decision is now also locked:

- quantity-bearing stack state should live in a holder-local stack record rather than on arbitrary physical item instances;
- first-pass merge behavior should be eager on holder mutation rather than a view-only aggregation layer.

##### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140: Why This Slice Exists

The item-instance work solved the first correctness problem:

- two swords can exist independently;
- two chests can hold different contents;
- explicit refs can target one physical thing.

That should not be undone by treating "same item definition" as a shortcut for stack merge. Without a separate authored stackability seam, the platform will drift toward the same ambiguous aggregate behavior `06.3` was created to remove.

This slice exists to keep two different ideas separate:

- physical item identity;
- authored fungibility.

Only the second one should ever produce stack merge.

##### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140: Locked Direction

- Stackability must be explicit authored capability, not an automatic consequence of item-definition equality.
- Non-stackable items stay as distinct `item_instances` in storage and management views even when adjacent in the same holder.
- Containers, equippable items, weapons, items with contents, and other stateful items must remain non-stackable by default.
- A stackable item must also define how stack compatibility is evaluated.
- Stack compatibility must be strict enough that only gameplay-interchangeable items merge.
- The first stack-compatible merge rule is strict and deterministic:
  - same item definition;
  - same authored stackability mode;
  - same canonical compatibility fingerprint.
- The current safe baseline fingerprint is definition-level sameness only.
- Richer authored compatibility must extend that baseline through bounded authored modes rather than ad hoc runtime logic.
- The next authored compatibility seam should prefer explicit modes such as:
  - `DEFINITION_ONLY`;
  - `DEFINITION_AND_FAMILY`.
- Future compatibility must be derived from canonical authored/item-state fields rather than arbitrary script-owned metadata.
- Hidden mutable state such as durability, enchantment, charge count, custom naming, contamination, ownership marks, or contents must not silently merge unless an authored compatibility mode explicitly says they are interchangeable.
- If the compatibility fingerprint differs, the items remain separate even if they share the same item definition id.
- Quantity-bearing stack state belongs to the holder-facing stack record, not to arbitrary non-stackable item instances.
- The first implementation should merge eagerly when holder mutations occur rather than preserving divergent storage truth and layering a view-only merge over the top.
- Prose views may still collapse identical visible names naturally, but the authoritative runtime/storage model must not lose the distinction between non-stackable instances and authored stacks.

##### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140: Target State

- Item definitions expose an explicit stackability mode.
- The minimum target-state distinction is:
  - non-stackable physical instance;
  - explicitly stackable fungible item.
- Stackable items define or derive a canonical stack-compatibility fingerprint.
- Stackable items merge into explicit holder-local stack records rather than overloading non-stackable item-instance identity.
- Item creation, pickup, drop, put, take, wear, and remove paths use that authored stackability model consistently.
- Quantity-bearing query views can show merged stacks for stackable items while still returning distinct item instances for non-stackable items.
- Exact refs continue to target one concrete item instance where instance identity exists; stack-oriented selectors for fungible items now use the surfaced stack visible ref / family key when one holder contains multiple compatible families for the same item definition.

##### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140: Minimum Authored Model

The first authored seam should be intentionally narrow:

- `stackable` or equivalent explicit capability flag on the item definition;
- optional bounded stack-compatibility strategy/fingerprint source when simple definition-level sameness is no longer sufficient;
- holder-local stack rows or equivalent explicit stack records carrying quantity semantics, rather than arbitrary non-stackable item instances.

The first concrete authored schema should stay bounded and inspectable:

- `stackable: boolean`;
- `stackCompatibilityMode: enum`;
- optional `stackVariantKey` when the chosen compatibility mode requires a second authored discriminator.

The first compatibility enum should be:

- `DEFINITION_ONLY`;
- `DEFINITION_AND_FAMILY`.

The intended meaning is:

- `DEFINITION_ONLY`: same item definition merges;
- `DEFINITION_AND_FAMILY`: same item definition and same canonical stack family key merge.

Implementation guardrail:

- the authored item definition should carry `stackVariantKey`, while the transferable holder-local stack substrate continues to carry `stackFamilyKey`, so two quantities of the same item definition can remain distinct when they came from different authored stack families;
- quantity-preserving mutations must carry the source `stackFamilyKey` forward rather than recomputing from only `item_id`;
- if more than one stack family for the same item definition exists in a holder and the command path did not identify which family to use, the runtime must reject the action as ambiguous rather than silently picking one.

The first implementation does not need to solve every future variant such as durability-aware partial compatibility or creator-authored custom merge scripts. It does need to prevent accidental merge of ordinary physical items.

##### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140: Current Live Boundary

The current implementation now covers:

1. explicit authored `stackable` item capability;
2. holder-local quantity-bearing stack records;
3. eager merge/split behavior across inventory, room-ground, and container mutation paths;
4. canonical `stackCompatibilityMode` plus transferable `stackFamilyKey` substrate for stack-compatible items;
5. explicit authored `stackVariantKey` naming on item definitions, separate from the runtime holder-local `stackFamilyKey` carried by concrete stack rows and transfer mutations;
6. family-preserving stack transfers across inventory, room-ground, and container holders;
7. ambiguity rejection when one item definition now exists in multiple stack families within the same holder;
8. merged stack query surfaces for those holders;
9. room-ground prose/entity reads that stay aligned with the new stack storage truth;
10. explicit stack-family selector UX for `GET`, `DROP`, `PUT`, and `TAKE`, using surfaced stack refs when ambiguity exists inside one holder.

The current authored stackability boundary is complete. Any later work now belongs to narrower follow-up seams:

1. richer authored sources for the runtime `stackFamilyKey` beyond the current item-definition `stackVariantKey` seam; and
2. any later non-merging escape hatch once a real consumer exists for that behavior.

##### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140: Explicitly Out Of Scope

- deep economics, loot generation, vendors, or crafting consumption policy;
- durability-aware or enchantment-aware partial-stack compatibility unless needed to support the first authored model.

##### source-06-3-2-task-list-authored-stackability-and-fungibility-vertical-slice-1-140: Validation

- [x] Prove that item definitions default to non-stackable unless explicitly authored otherwise.
- [x] Prove that two same-definition non-stackable items still remain separate instances in the same holder.
- [x] Prove that explicitly stackable fungible items can merge into one quantity-bearing result when their compatibility fingerprint matches.
- [x] Prove that incompatible stack families do not silently merge even when the base item definition matches.
- [x] Prove that players can explicitly target one surfaced stack family when one holder contains several same-definition stack families.
<!-- /migration-source -->

### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146

#### Unified Item Holder and Transfer Model Vertical Slice - Unified item holder and guarded transfer model (source lines 1-146)

##### Preserved Source Text: source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146

<!-- migration-source path="design/project-management/vertical-slices/06.4-task-list-unified-item-holder-and-transfer-model-vertical-slice.md" lines="1-146" sha256="8e2ea8b3b489819016a540fe275d7d86575429c65e2a2754832cedef51214208" heading-offset="3" -->
#### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Unified Item Holder and Transfer Model Vertical Slice

##### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Goal and Status

Goal: converge inventory, equipment, room-ground items, and containers onto one shared item-holder and transfer model so they remain one gameplay system with different holder kinds and presentation rules, not four separate subsystems. Status: complete at the current bounded boundary.

##### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Implementation Notes

The gameplay command layer now has its first bounded unification pass:

- inventory, equipment, and container verbs converge through one `ItemCommandHandler` seam in game-session;
- command dispatch is no longer split across separate inventory/equipment/container dispatch branches in the interpreter;
- inventory, equipment, and room-ground runtime storage is now driven through one shared `item_instances` holder model in entity-management;
- room-facing item listing now reads from the same item-instance holder truth as inventory/equipment mutation paths;
- room-ground management now reads through a dedicated quantity-bearing room inventory query instead of piggybacking on room-entity prose output, so `INV HERE` stays aligned with the same holder/query model as inventory and container listings;
- container contents now use that same `item_instances` holder model, so all live `06` holder classes share the same physical item truth;
- container-instance holder synchronization is now centralized in one backend support seam instead of being reimplemented separately in inventory, equipment, and container services;
- container accessibility and containment rules are now centralized in one holder-policy seam instead of remaining ad hoc `ContainerServiceImpl` logic;
- all live inventory, room-ground, equipment, and container mutations now route through the shared guarded transfer helper with shared transfer-audit context rather than per-command holder rewrites;
- the guarded-handoff contract is now explicit at the shared transfer-helper seam and directly unit-proved across inventory, room-ground, equipment, and container holder kinds.

The current holder/transfer convergence boundary is complete. Later storage, validation, or gameplay-family growth should stay on the same holder/transfer contract through narrower owning follow-up slices rather than reopening this convergence doc.

Current runtime note:

- today the live backend model expresses holder state directly on `item_instances` via fields such as `character`, `equipment_slot`, `game_instance_id`, `room_instance_id`, and `container_instance_id`;
- the direct-field holder model is now the canonical target-state runtime representation;
- the canonical mutation model should still be an explicit guarded handoff contract at the service boundary:
  - move one `item_instance` from expected source holder to destination holder;
  - succeed only if source ownership still matches at mutation time;
  - do not model transfer as unrelated remove/add semantics;
- future `06` work should not casually drift into separate command-family semantics or rely on scheduler perfection for duplication safety.

This slice is now important because the live `06` implementation has enough inventory/equipment/container behavior that architectural drift is becoming visible. The intended design is still unified, but the current code shape can drift toward isolated command families if the holder model is not made explicit.

##### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Why This Slice Exists

The correct mental model is:

- inventory is an item holder;
- equipment is an item holder with slot/body-layout policy;
- room ground is an item holder attached to a room instance;
- a container is an item holder attached to an item instance.

That means `GET`, `DROP`, `WEAR`, `REMOVE`, `PUT`, and `TAKE` are all transfer operations between holder kinds.

Without an explicit slice for that unification, drift becomes likely:

- inventory starts looking like one system;
- equipment starts looking like a second special-case system;
- containers start looking like a third transfer subsystem;
- room-ground flows gain separate resolution rules;
- validation and audits split across parallel code paths instead of one canonical transfer model.

FireMUD should not let that happen. The command surface may remain different, but the underlying runtime model should be one item-holder/transfer architecture.

The safety reason matters too. Classic MUDs are notorious for item-duplication or item-loss bugs caused by unsafe handoff semantics:

- two actions both believe the source still owns the item;
- retries repeat a move after the first mutation already succeeded;
- source removal and destination insertion are treated as unrelated operations;
- container/inventory/room/equipment flows each drift into slightly different safety rules.

So this slice is also where FireMUD needs one duplication-resistant handoff model, not just cleaner terminology.

##### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Scope

- Define one canonical holder model for:
  - carried inventory;
  - equipment slots/body-layout bindings;
  - room-ground storage;
  - container contents.
- Define one canonical transfer model between holder kinds.
- Define where holder-kind-specific validation lives.
- Define which view/presentation differences are legitimate and which should remain shared.
- Align the `06` runtime slices so item identity, selection, transfer audit, and validation sit on one model.

##### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Out of Scope

- Full crafting/vendor/mail/bank systems.
- Full combat/body-damage semantics.
- Nested-container UX beyond what existing `06` slices already cover.
- Rewriting all current `06` code in one change.

##### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Target State

- FireMUD has one authoritative item-holder abstraction.
- The authoritative runtime representation is direct holder fields on `item_instances`, not a separate holder object.
- Holder kinds include at least:
  - character inventory;
  - equipment slot;
  - room-ground;
  - container instance.
- Every item transfer is modeled as movement between holder kinds, with shared guarded handoff semantics plus holder-specific validation.
- The canonical transfer contract is:
  - move `item_instance X`
  - from expected source holder `A`
  - to destination holder `B`
  - only if the current authoritative holder still matches `A`
- Transfer safety must resist duplication/loss failure modes:
  - stale source ownership fails rather than silently duplicating;
  - retries do not create a second copy of the item;
  - the move is one authoritative state transition, not separate remove/add mutation semantics.
- Commands are just front doors into those transfer operations:
  - `GET` = room-ground -> inventory
  - `DROP` = inventory -> room-ground
  - `WEAR` = inventory -> equipment-slot
  - `REMOVE` = equipment-slot -> inventory
  - `PUT` = inventory -> container
  - `TAKE` = container -> inventory
- The gameplay command layer should converge on one item-manipulation handler/dispatcher seam rather than separate inventory/equipment/container subsystems.
- Item resolution, instance identity, transfer auditing, and stack handling stay shared across those flows.
- Presentation differences remain legitimate:
  - `INVENTORY`
  - `EQUIPMENT`
  - `CONTAINER <x>`
  - room `LOOK`
  but they are views over the same holder/transfer model rather than separate subsystems.

##### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Required Changes

- [x] Define one canonical holder-kind model in the `06` design docs.
- [x] Define one canonical transfer operation shape between holders.
- [x] Decide and document that direct holder fields on `item_instances` are the canonical runtime representation.
- [x] Move slot/body-layout checks into holder-policy validation rather than separate equipment-only ontology.
- [x] Move container accessibility checks into holder-policy validation rather than container-only ontology.
- [x] Keep room-ground, inventory, equipment, and container selection rules aligned on the same item-instance and explicit-ref model at the gameplay command seam where durable identity already exists.
- [x] Keep transfer audit semantics shared across holder kinds instead of splitting by command surface.
- [x] Define the exact guarded-handoff precondition/audit contract for retries, stale source ownership, and duplicate execution attempts.

##### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Recommended First Implementation Boundary

1. formalize the holder-kind model in runtime/service contracts;
2. align mutation contracts around guarded source holder -> destination holder handoff semantics;
3. keep the gameplay command layer thin and map all item-manipulation verbs into one shared item-transfer handler seam;
4. remove duplicated inventory/equipment/container command resolution as the shared transfer path becomes authoritative.

The first pass does not need to rewrite every touched service at once, but it should make the shared holder/transfer model explicit enough that future command and runtime work converge instead of drift.

##### source-06-4-task-list-unified-item-holder-and-transfer-model-vertical-slice-1-146: Validation

- [x] Prove that the design describes inventory, equipment, room-ground, and containers as one holder/transfer system.
- [x] Prove transfer audit language is shared across those holder kinds.
- [x] Prove the guarded-handoff semantics are strong enough to prevent classic duping/loss failures under stale source ownership or duplicate execution for the current live holder-mutation paths.
- [x] Prove later item-instance and explicit-targeting work can apply once to all holder kinds instead of being re-designed separately for each command family.
- [x] Prove the gameplay command architecture can route all core item-manipulation verbs through one shared handler/dispatcher seam.
<!-- /migration-source -->

### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83

#### Safe Item Transfer and Handoff Semantics Vertical Slice - Safe item transfer and handoff (source lines 1-83)

##### Preserved Source Text: source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83

<!-- migration-source path="design/project-management/vertical-slices/06.4.1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice.md" lines="1-83" sha256="144341ac3a731b914a0f5c07ed52fa02c4bbab03c515e3f68dae2d9eaa377a5f" heading-offset="3" -->
#### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83: Safe Item Transfer and Handoff Semantics Vertical Slice

##### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83: Goal and Status

Goal: define one duplication-resistant handoff contract for moving physical item instances between holders so inventory, room-ground, equipment, and containers cannot drift into classic MUD duping/loss behavior under retries, races, or partial failures. Status: implemented for the current live holder-mutation paths; broader replay/idempotency remains intentionally out of scope until a caller needs it.

##### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83: Why This Slice Exists

The broader `06.4` holder/transfer slice now locks two important design decisions:

- direct holder fields on `item_instances` are the canonical runtime representation;
- movement between holders should still be governed by an explicit guarded handoff contract.

That still leaves a narrower unresolved problem:

- what exactly proves the source holder still owns the item;
- how retries or duplicate execution attempts are handled;
- whether some forms of "already moved" should be rejected or treated as safe no-op;
- what audit information is needed to debug suspicious moves later.

This slice exists so that item-transfer safety is discussed explicitly before deeper transfer implementation continues.

##### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83: Implementation Notes

Implemented now:

- item movement in inventory, equipment, and container mutation services flows through a shared guarded transfer helper;
- the mutation boundary now checks expected source holder state before rewriting holder fields;
- stale-source unit proofs exist for inventory, equipment, and container moves.
- direct helper-level unit proofs now cover successful and rejected transfers across inventory, room-ground, equipment, and container holder kinds without relying on service-specific selection code.
- container `PUT` and `TAKE` now honor explicit `item_instance_id` selection instead of silently collapsing back to definition-only movement.
- room-ground `GET` and inventory `DROP` now distinguish generic name matches from exact instance-style refs, so `item_instance_id` is forwarded only for explicit refs and exact refs consistently require quantity `1`.
- suspicious/stale transfer failures now log a canonical warning payload with item instance id, item id, expected source, current holder, and destination before rejecting the move.
- the shared transfer helper now also distinguishes "already at destination" from generic stale-source mismatch and rejects it explicitly instead of silently succeeding.
- transfer warnings now also include the transfer verb/cause and actor character id when the caller has that context.
- a follow-up audit confirms the live inventory, room-ground, equipment, and container mutation paths no longer contain ad hoc holder rewrites outside the shared guarded transfer helper.
- successful live holder transfers now also persist canonical `item_transfer_audits` rows for both item-instance and stack-backed movement in the same local transaction boundary as the mutation.

Still open:

- later, add a dedicated replay/idempotency contract only if a caller path explicitly needs one.

##### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83: Validation

- [x] Prove stale-source mismatches fail before holder fields are rewritten.
- [x] Prove transfers already at the intended destination fail explicitly rather than silently succeeding.
- [x] Prove the shared helper can move an item between holder kinds when the expected source matches.
- [x] Prove tenant/source validation is enforced at the same guarded-handoff seam as holder checks.

Locked now:

- stale-source mismatches remain hard failures;
- transfers that find the item already at the intended destination also fail by default rather than silently succeeding;
- implicit idempotent success is out of scope unless a future caller path introduces an explicit idempotency or replay contract;
- the canonical transfer-audit payload now includes item instance id or stack-family/quantity delta, item id, source, destination, transfer verb or cause, and actor or character context when available, while leaving session/effect correlation fields ready for later caller-owned propagation.

##### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83: Scope

- Define the canonical guarded handoff contract for moving one item instance between holders.
- Define source-ownership preconditions checked at mutation time.
- Define retry, duplicate-execution, and stale-source behavior.
- Define what transfer audit information is required for operator/debug trust.

##### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83: Out of Scope

- Full economy, banking, mail, or market movement.
- True stackable merge semantics.
- Full distributed transaction infrastructure.

##### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83: Known Design Considerations

- Scheduler/tick/action serialization can reduce races but must not be the only thing preventing duplication.
- Safety should come from the authoritative item-transfer mutation itself.
- Transfer must behave as one move of one item instance, not as unrelated remove/add semantics.
- Container, room-ground, equipment, and inventory moves should inherit the same safety contract.

##### source-06-4-1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice-1-83: Locked Direction

- source ownership is currently proved by item instance identity plus explicit expected source holder semantics;
- scheduler/action serialization may reduce races but is not treated as the primary duplication defense;
- the transfer mutation itself is the guarded handoff boundary;
- already-moved items do not become silent success by default;
- broader replay/idempotency support requires an explicit caller contract rather than ad hoc detection.
<!-- /migration-source -->

### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343

#### Entity Stats and Conditions Vertical Slice - Entity stats and conditions (source lines 1-343)

##### Preserved Source Text: source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343

<!-- migration-source path="design/project-management/vertical-slices/07-task-list-entity-stats-and-conditions-vertical-slice.md" lines="1-343" sha256="077d3bfcae48f1b74b3b306a6c33eefc24aae030017fdcbb398a9ece3c524efd" heading-offset="3" -->
#### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Entity Stats and Conditions Vertical Slice

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Goal and Status

Goal: define one canonical gameplay-state model for numeric stats, bounded resources, conditions, buffs, debuffs, and transient action states so equipment, potions, spells, actions, and future combat all contribute through the same effect system instead of accumulating one-off rule paths. Status: the first Entity Management-owned evaluated-state substrate, shared effect evaluation, equipment contributions, and transient action-state execution are live; generic authored stat/condition definitions and damage/mitigation remain future work.

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Implementation Notes

- Entity Management now owns persisted actor resource and active-condition state through `actor_resource_states` and `actor_active_conditions`, keyed by tenant, derived playable-state namespace, and character id rather than a raw game-instance shortcut.
- `QueryActorState` exposes a gameplay-attested, playable-scope-aware read API that returns baseline character stats overlaid with persisted resource rows plus active non-expired conditions.
- The first player-facing state reader is now live: in-world `STATUS` (alias `STAT`) reads the evaluated actor state through Game Session -> Game Logic -> Entity Management, renders a typed resources/visible-conditions view for text and first-party WebSocket clients, and never exposes internal effect payload or source provenance.
- Active condition payloads, equipped item payloads, and replay-guarded transient action states now all contribute through the shared evaluator. `ApplyActorCondition` validates persisted effect JSON before replay lookup, and Entity Management expires elapsed action-state rows on its scheduled expiry path.
- Game Design validates the first release-admitted authored `APPLY_ACTION_STATE` declaration, and Game Session executes its persisted snapshot through the same actor-condition seam. Generic authored stat/condition definitions, resource-cost mutation, multi-effect actions, and damage resolution remain future work.
- Runtime game instance identifiers remain opaque strings, matching existing inventory/equipment/room-state tables rather than requiring numeric ids.

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

This slice sits after the first inventory/equipment work because it will become the shared substrate for health, armour, resistances, afflictions, blocking states, temporary buffs, and similar mechanics across many later gameplay systems.

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Why This Slice Exists

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

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Scope

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

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Out of Scope

- Implementing full combat.
- Designing every possible damage type or status effect a game may want.
- Replacing the settings model with a scripting-first authoring system.
- AI-driven balance or rules generation.
- Rich first-party GUI presentation beyond recording what runtime state must make available later.

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Target-State Model

FireMUD should treat gameplay state as four related but distinct layers:

1. stat definitions
2. condition definitions
3. active effect sources
4. evaluated effective state

The shared platform should provide typed primitives, while each game authors the actual named stats and conditions it uses.

###### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Canonical Authored Actor-State Catalog

Game Design owns a versioned, DML-authored actor-state catalog for each `(tenantId, versionId)`. Publishing freezes that catalog into the game's release bundle; runtime services resolve it from the game instance's pinned release rather than from mutable design rows or per-command settings reads.

The platform owns only a small typed grammar. The game owns every named mechanic, including `health`, `mana`, resistances, conditions, presentation labels, tags, and authored effect values. A game-specific universal-stat enum is not permitted.

- A stat definition has a stable `statKey`, primitive kind, default/base value, hard bounds where applicable, visibility/presentation metadata, and tags. The initial primitive kinds are `NUMERIC`, `BOUNDED_RESOURCE`, and `BOOLEAN_FLAG`; later damage or region semantics use tagged effects rather than introducing platform-owned `fire_resistance`-style stat names.
- A condition definition has a stable `conditionKey`, explicit stacking and duration policy, visibility/presentation metadata, tags, and typed effect declarations. Conditions are definitions, not unvalidated free-form payload keys.
- Effect declarations are typed, validated data that reference declared stat or condition keys. Equipment, actions, spells, consumables, room effects, and scripts use this same grammar instead of feature-local stat maps.
- Entity Management persists only runtime state and provenance. It rejects definitions or references that are absent from the actor's pinned release catalog; it must not invent defaults or reinterpret unknown keys.
- An active condition records its source, definition key, release identity, and immutable applied-effect snapshot. Later publishes therefore do not silently change an already-active condition. A replacement-instance/cutover flow must validate or remap durable actor state explicitly against its target release rather than applying best-effort name matching.

This keeps the full game design surface scriptable as versioned DML while retaining a small hardcoded grammar that services can validate deterministically.

###### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Capacity Changes From Continuous Sources

The default behavior when a continuous source changes a bounded resource's maximum is an effective tenant/game setting, `actorState.capacityChangePolicy`, rather than a hardcoded meaning of a resource such as `health`. The platform admits only `CLAMP_ONLY`, `PRESERVE_RATIO`, and `PRESERVE_DEFICIT`. A continuous effect declaration from an item, condition, spell-backed aura, or equivalent source may specify a more-specific override for the maximum delta it causes.

Entity Management applies that policy only at the durable source transition: attach, detach, refresh, expiry, or replacement. It never rewrites current resource state while evaluating an actor read. A transition with multiple maximum-affecting declarations executes them in frozen authored order, carries the intermediate state forward, and records each resolved policy with its idempotent result. An explicit heal or drain remains a separate instant effect. A starter experience profile may seed a recommended editable game-setting value, but it never supplies hidden runtime behavior after that setting is changed or removed.

###### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Starter Experience Profiles

Creators do not need to author every actor-state definition from scratch. Game Design supplies curated, versioned starter experience profiles such as a classic text-MUD baseline, a solo-RPG baseline, or a minimal sandbox. A selected base profile and optional extension packs materialize ordinary stat, condition, action, disposition, observation/targeting/target-selection-policy/default-path binding, and feedback DML into the target Draft version before publication.

- The imported rows are normal game-owned DML after application: creators may edit, replace, or remove them, and a game may select no profile at all.
- Profiles are not runtime settings and do not create hidden fallback mechanics. If a game removes or does not select a definition, runtime behavior cannot silently resurrect it from a platform default.
- Optional packs compose in declared order. Duplicate definition keys fail by default; a later pack may replace an earlier definition only through an explicit recorded override. There is no implicit last-writer-wins merge.
- Game Design records selected pack identities, revisions, hashes, application order, and explicit overrides as Draft provenance. Publishing freezes only the resulting single game version and its release bundle; running instances never inherit moving profile content.

This gives new games a sane usable default experience while preserving the fully DML-authored, scriptable model for games that need different mechanics.

###### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Canonical Resource Floor and Actor Disposition

Reaching a bounded resource's floor has no universal platform meaning. A resource definition may optionally reference a versioned DML `floorTransition`; an omitted transition means the resource can remain at its floor without changing actor disposition.

- A floor transition fires once when an idempotent instant mutation crosses the resource downward to its declared floor. It reports the actual applied delta and a `FLOOR_REACHED` fact; reads while already at the floor do not repeat it.
- The transition references an authored `ActorDisposition` definition with action-admission, optional condition, and semantic feedback policy. It can represent unconsciousness, defeat, death, exhaustion, or a game-specific state without making any of those a platform-owned `health` rule.
- Every actor has one persisted main `dispositionKey`, normally initialized from the selected experience profile. Conditions and equipment are overlays on that base state: `stunned` can restrict action admission, while `invisible` is an optional game-authored fact that a separate targeting policy may use. Neither becomes a competing defeat/death lifecycle owner. Transport/session presence remains separate from disposition.
- Generic resource adjustment clamps to declared bounds. It does not create a corpse, respawn an actor, distribute loot, decide combat victory, or infer permanent death.
- Damage and mitigation later consume the floor transition and disposition contract. They own hit resolution, mitigation, defeat/revival, respawn, and corpse/loot policy through explicit authored rules.

###### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Disposition and Overlay Composition

The persisted `ActorDisposition` is the DML-authored baseline for action admission and semantic feedback. A condition, equipment, stance, or aura uses a continuous overlay only to narrow that admission baseline. It cannot grant behavior that the main disposition denies, so a continuous source cannot accidentally revive a defeated actor or bypass another main-state lifecycle.

Games retain full authoring control through explicit instant effects. A recovery may remove or prevent a restrictive condition; a revival or comparable main-state change uses an idempotent disposition transition. The release declaration, effect id, and resulting disposition are recorded with the mutation, so replay never derives a different lifecycle outcome from current equipment or condition reads.

###### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Action Admission Facets

Game Design publishes a DML `ActionAdmissionTag` catalog with the actor-state and command definitions. Every command/action definition carries a required ordered `admissionTags` field that references it; an explicitly empty list is valid for an action that disposition does not restrict. The `ActorDisposition` definition names the tags it denies, and continuous overlays can add only further denials. An enabled action is admitted only if none of its tags are denied; invalid, unknown, stale, or omitted required tags fail closed. This is distinct from a primary action category and activity/AFK tags, which do not decide actor capability.

Tenant/game command capability policy is also separate. A command family disabled by `commandCapabilities` returns `FEATURE_UNAVAILABLE` before actor admission; login/play stage gates remain separate; only an enabled stage-valid command reaches this disposition gate. The resulting action-admission failure uses a stable platform result and the resolved disposition's authored safe feedback.

###### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Targeting Policies

Game Design publishes DML `ObservationPolicy`, `TargetingPolicy`, and `TargetSelectionPolicy` catalogs plus named default target-set bindings for platform paths. An observation policy supplies `observableWhen`; a targeting policy supplies its candidate selector, observation-policy reference, `eligibleWhen`, and safe failure-presentation declarations. The predicates use bounded `ALL`/`ANY`/`NOT` composition over declared source/target state facts, conditions, tags, dispositions, relationships, and World-owned spatial facts. Actions declare an implicit `SOURCE` target set plus bounded keyed `ActionTargetSet` declarations; standard paths resolve named default sets. Each authored set attaches reusable targeting and selection keys rather than duplicating common target logic, and may declare an input slot only when it consumes player selector text.

Each authored target set or standard-path default set references a frozen reusable DML `TargetSelectionPolicy` over its eligible candidates. It declares `EXACTLY_ONE`, bounded `UP_TO_N`, or `ALL_ELIGIBLE` within an operator ceiling and chooses by `PLAYER_SELECTED`, `CANONICAL_ORDER`, typed `RANKED`, or effect-id-seeded `RANDOM_SEEDED` strategy. This keeps target and selection policy reusable while required-or-optional outcomes remain target-set-specific. Candidate order is canonical and stable; the policy must declare ceiling truncation and records its snapshot, selected targets, ranking values or seed in target-resolution evidence. Each action effect names `SOURCE` or one declared target set; optional unresolved sets apply none of their effects and remain explicit in the outcome.

Visibility, hidden state, see-hidden state, targetability, faction, phase, and comparable mechanics are optional facts, not mandatory actor columns or platform booleans. A false observation expression is non-disclosing and therefore has the same player result as no matching target. A false eligibility expression may emit only the policy's approved safe feedback. A starter profile may materialize a reusable visible-actor observation policy and attach it to several direct-target policies, while a game without visibility uses `observableWhen=true`. Game Logic compiles the frozen policies into bounded fact requests to each documented owner and records policy snapshots, returned fact snapshots, owner version/fence tokens, stage results, and decisive evidence in the `ResolvedEffectPlan` or rejected action outcome; missing, unavailable, or stale facts fail closed. Before any mutation, material fact owners must validate their recorded tokens; a mismatch discards and re-resolves the plan under the same effect id rather than applying stale targeting.

###### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Canonical Condition Application and Removal

Each condition definition owns its application policy. Repeated application never relies on the caller's source-id shape or on handler-local assumptions.

- `REPLACE` removes the prior matching instance before applying the new one; use it for exclusive states such as stances.
- `REFRESH` maintains one active instance and refreshes its duration; use it for effects such as shields or blocking.
- `STACK` maintains one active instance and increments its DML-authored bounded stack count; use it for poison or bleed.
- `PARALLEL` retains independently sourced active instances with their own expiry; use it where effects from separate sources must coexist.
- Every definition selects duration behavior: reset from now, extend, or preserve the longer expiry. `STACK` also declares its maximum stack count.

Active condition instances retain a stable instance id, definition/release snapshot, source provenance, stack count, start/expiry, and applied effect snapshot. Entity Management resolves reapplication atomically under the idempotent effect id.

`REMOVE_CONDITION` uses typed authored selectors: exact condition key, condition tag, or an allowed source selector. Tag-based removal follows the definition's authored removal priority and a stable instance-id tie-breaker, so cure/replay behavior stays deterministic. Player input never chooses raw payload rows or arbitrary source identifiers to delete.

###### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Canonical Resource-Cost and Cooldown Lifecycle

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

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Architecture Notes

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

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: First Implementation Boundary

The first narrow implementation for `07` should not attempt all combat or all status UI at once.

Recommended first order:

1. define authored stat/resource/condition definitions;
2. define runtime entity state for current resources and active conditions;
3. land one shared effect-evaluation seam;
4. prove equipment and one transient action state can contribute through that seam;
5. defer full damage and mitigation resolution until the shared state model is stable.

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Locked Order

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

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: 1. Design Alignment for Shared Gameplay State

- [ ] Re-read the Entity Management, Game Design, Game Logic, and Game Session design docs that currently touch inventory/equipment, communication, or future combat seams so the new stats/conditions model slots into the existing ownership boundaries cleanly.
- [ ] Update or add design docs so the repo describes one canonical target-state distinction between:
  - stat definitions;
  - condition definitions;
  - active conditions and transient action states;
  - equipment-derived effects;
  - evaluated effective values.
- [ ] Document that persistent conditions, transient action states, and recalculated equipment modifiers are related but not identical concepts.
- [ ] Document that later combat, potion, spell, and equipment slices must contribute through one shared effect model rather than creating separate per-feature stat logic.

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: 2. Game Design Service: Authored Definitions

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

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: 3. Entity Management Service: Runtime State Ownership

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

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: 4. Shared Effect Evaluation Model

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

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: 5. Game Logic Service: Gameplay-Oriented Evaluation

- [ ] Define the first gameplay-facing RPC or service seam for querying effective stats/conditions without forcing Game Session to reconstruct gameplay state.
- [ ] Keep Game Logic responsible for gameplay-facing orchestration and later rule composition, while Entity Management remains authoritative for persisted runtime state.
- [ ] Add unit tests showing later gameplay actions can consume effective-state queries without duplicating evaluation rules locally.

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: 6. Game Session Service: Presentation and Help Expectations

- [x] Define and implement the first player-facing state inspection command: in-world `STATUS` / `STAT` projects evaluated resources and active visible conditions without making Game Session the source of truth.
- [ ] Document how conditions, buffs, and action states will eventually surface in transcripts or prompts without making Game Session the source of truth for gameplay evaluation.
- [ ] Ensure later help/docs can explain stats and conditions using canonical definition keys rather than hardcoded prose tied to one game.

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: 7. Cross-Service Proof Shape

- [ ] Add or plan a bounded cross-service proof where:
  - a character has one base resource;
  - one equipped item contributes a modifier;
  - one temporary condition contributes another modifier or flag;
  - the effective-state query returns the deterministic merged result.
- [ ] Keep the first proof intentionally narrow and data-driven so later combat slices can build on it instead of replacing it.

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: 8. Final QA Checklist

- [ ] The repo has one explicit target-state model for game-authored stats, conditions, and shared effects.
- [ ] Equipment, actions, potions, and future spells are expected to contribute through one shared effect engine rather than separate bespoke logic paths.
- [ ] The first implementation boundary is narrow enough to land before full combat while still proving the canonical runtime model.

---

##### source-07-task-list-entity-stats-and-conditions-vertical-slice-1-343: Related Follow-On Slices

- [07.1-task-list-shared-effect-engine-vertical-slice.md](../vertical-slices/07.1-task-list-shared-effect-engine-vertical-slice.md)
- [07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md](../vertical-slices/07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md)
- [07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md](../vertical-slices/07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md)
<!-- /migration-source -->

### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143

#### Shared Effect Engine Vertical Slice - Shared typed effect evaluation (source lines 1-143)

##### Preserved Source Text: source-07-1-task-list-shared-effect-engine-vertical-slice-1-143

<!-- migration-source path="design/project-management/vertical-slices/07.1-task-list-shared-effect-engine-vertical-slice.md" lines="1-143" sha256="f38b91eb5d5c2ae67a4345783b394ae654c831d3a9913669a38b48a2d8e4a868" heading-offset="3" -->
#### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Shared Effect Engine Vertical Slice

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Goal and Status

Define the first shared typed effect engine so equipment, consumables, conditions, transient action states, and future spells/actions all modify entity state through one canonical evaluation path. Status: the first typed Entity Management evaluation seam is live and now consumes conditions, equipped items, replay-guarded action states, and the first release-admitted authored action effect; broader effect kinds and combat consumers remain future work.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Implementation Notes

- Entity Management now has `EffectEvaluationService` and `DefaultEffectEvaluationService` as the first shared typed effect seam.
- The first model supports additive numeric modifiers, multiplicative numeric modifiers, min/max clamps, granted flags, granted conditions, scoped effect metadata, and source provenance.
- Evaluation is deterministic: modifiers are sorted by priority and source metadata, numeric phases run as base, additive, multiplicative, clamp min, clamp max, and granted states are projected after numeric resources.
- Active condition `effect_payload_json` rows can now project typed modifiers through the shared evaluator during `QueryActorState`.
- The seam remains in-process and behavior-focused. It now has a narrow attested action-state mutation API and the first typed authored-action declaration, but it is not yet a generic stat/condition authoring schema, multi-effect execution model, resource-cost engine, or combat resolver.
- Game Session durable command routing no longer infers action-state mutation from broad action tags such as `COMBAT`; only explicitly declared legacy command behavior routes to a concrete state mutation. Tags remain descriptive until typed effect declarations are admitted and executed through this engine.
- Entity Management now consumes the same canonical modifier scalar contract as Game Design and Game Session: persisted effect payloads require a `modifiers` array, registered operation, identifier `target_key`, numeric `value`, optional identifier scope fields, and optional integer priority. Legacy alias keys and implicit values are rejected instead of being silently normalized.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Why This Slice Exists

The broader stats/conditions slice needs one concrete follow-up that prevents the codebase from fragmenting into:

- equipment-only stat math;
- buff/debuff-only condition math;
- action-state-only mitigation logic;
- potion/spell-side bespoke modifiers.

This slice exists to make "effect source differs, evaluation engine stays shared" the canonical repo direction.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Scope

- Define the typed effect primitives the platform supports first.
- Define deterministic evaluation order and stacking behavior.
- Define how effect provenance and scope are represented.
- Define the first authoritative evaluation entrypoint used by later gameplay code.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Out of Scope

- Full combat or damage resolution.
- Full scripting/creator-authored rule DSL.
- GUI rendering or final prompt UX.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Acceptance Shape

- The repo has one explicit effect model shared by equipment, conditions, and transient states.
- The first implementation can prove at least:
  - additive numeric modifiers;
  - granted conditions or flags;
  - scoped modifiers for later slot/damage targeting.
- Later gameplay slices can plug into the same evaluation engine instead of creating local stat math.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Canonical Effect Target Resolution and Application

Cross-actor effects use one Game Logic-owned `ResolvedEffectPlan`. Game Session may carry a player's raw target selector, but it must not choose final actor identities or send an unverified target list to Entity Management.

1. At durable execution time, Game Logic loads the action's frozen release declaration and resolves the source actor plus its implicit `SOURCE` target set.
2. It resolves each authored `ActionTargetSet` relative to that source. Each set carries a release-pinned `TargetingPolicy`, `TargetSelectionPolicy`, required-or-optional outcome behavior, and an optional declared player selector input slot.
3. For each authored set, it resolves the typed candidate selector against canonical actor identity and World Management occupancy, then compiles the referenced predicates to bounded fact requests for each documented fact owner.
4. Each owner returns only the requested source/candidate facts in a versioned or fenced `TargetingFactSnapshot`; unavailable reads fail closed, and resolution never becomes one RPC per predicate or candidate.
5. It evaluates `ObservationPolicy.observableWhen` before `eligibleWhen` for observable candidates and validates the policy's typed predicates, including target tags, range/scope rules, and game-authored actor eligibility requirements.
6. It records resolved target actors by target-set key, release/action snapshot, effect id, and target-resolution evidence, including policy snapshots and fact-owner tokens, in the `ResolvedEffectPlan`. Every action effect names `SOURCE` or one declared target set.
7. Before any source cost, cooldown, or effect mutation commits, every owner of a material fact validates its recorded token. A mismatch discards the plan and re-resolves under the same effect id; no stale selected target or fallback candidate may be used. A required unresolved set rejects the action; an optional unresolved set has its explicit no-mutation outcome.
8. Entity Management applies that approved plan idempotently. It receives canonical actor ids and typed effects, never an unresolved player name or selector.

The platform owns the small candidate-selection grammar (`SELF`, `DIRECT_ACTOR`, and later room/area forms). Games author reusable `ObservationPolicy` and `TargetingPolicy` definitions plus default-path bindings using typed boolean predicates over optional facts such as tags, conditions, relationships, range, hidden/see-hidden status, or other declared actor state. A targeting policy owns its selector and observation-policy reference, so the same common target shape can attach to many actions. A game that has no visibility mechanic uses an observation policy with `observableWhen=true`. This preserves deterministic runtime validation without hardcoding game-specific targeting policy.

Each authored `ActionTargetSet` or standard-path default target-set binding references a frozen reusable DML `TargetSelectionPolicy` over its eligible candidates. It declares `EXACTLY_ONE`, bounded `UP_TO_N`, or `ALL_ELIGIBLE` within an operator ceiling and selects using `PLAYER_SELECTED`, `CANONICAL_ORDER`, typed `RANKED`, or effect-id-seeded `RANDOM_SEEDED` strategy. A policy explicitly declares how it truncates `ALL_ELIGIBLE` at the ceiling; query order and implicit random selection are invalid. The target set owns required-or-optional outcome behavior and optional player-input binding, while the reusable policy can safely serve a direct strike, multi-target ability, and room-wide effect. Evidence records each set's policy snapshot, canonical candidate order, selected targets, and ranking values or random seed.

A false `ObservationPolicy.observableWhen` result removes the candidate from player-visible resolution and returns the same safe unavailable outcome as no matching actor. A false `eligibleWhen` result may return only policy-approved authored feedback. Target-resolution evidence records the policy snapshots, stage results, and decisive predicate facts in the `ResolvedEffectPlan` on success; a rejected action outcome retains the same evidence with its failed stage. No presentation path may infer or reveal non-disclosing facts from that evidence.

- For same-region required targets, Entity Management applies the complete resolved plan in one local transaction or rejects it before any target mutation. Silent partial fan-out is not allowed.
- Cross-region target legs use the durable remote coordinator/follow-up pattern. Each leg re-reads and validates its region-owned targeting facts before it mutates, has its own idempotent outcome, and is reported explicitly by the parent action rather than claiming impossible distributed atomicity.
- A future action may declare optional targets only through an explicit authored outcome policy. Missing or ineligible optional targets must be visible in the action outcome; they are never silently dropped.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: To Discuss Before Targeting Implementation

- Player target-selector defaults for duplicate observable names: retain the requirement that no command arbitrarily selects a database row, then decide the game-authored choice between explicit disambiguation and selecting the first candidate in a declared visible order. Raw runtime actor ids remain outside player command syntax.
- Cycling generic targeting: some games rotate their room-visible target list after a generic action. Preserve this as a product interaction choice; decide later whether it is driven by room presentation order, actor-local selection state, or another model, together with its reconnect and replay semantics. Do not introduce a cursor mechanism speculatively.
- Derived target sets: initial `ActionTargetSet` declarations resolve only relative to the action source. A later chain, bounce, or secondary-target grammar may resolve from a selected target set, but it requires its own bounded candidate, ordering, and replay contract.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Canonical Action Outcome and Presentation Contract

Every durable action produces one structured `GameplayActionOutcome`. It replaces lossy free-form success text as the canonical result stored with the command and, where applicable, its remote coordinator.

- The outcome identifies the action/release/effect, source actor, resource-cost and cooldown commit state, completion state, and ordered target-leg outcomes.
- `commitState` says whether source state committed. `completionState` says whether execution is locally final, awaiting remote legs, or remote-final. These are separate so a committed source action is never falsely represented as an unqualified success while a remote result is pending or failed.
- Each target outcome records canonical actor identity, required/optional classification, result code, and durable remote-leg identity when applicable.
- Game Logic emits idempotent `GameplayPresentationEvent` entries from that outcome. An event carries a stable event id, resolved authorized audience, semantic message key, typed arguments, visibility classification, and replay policy.
- Game Session delivers and renders those events as `PlayerOutput`. It does not inspect target rows or infer gameplay success from a text result. Late remote completion emits a later event bound to the original effect id without replaying prior feedback.
- Game-authored feedback declarations are versioned DML. Platform validation and infrastructure failures retain stable platform error codes and safe platform-owned messages.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Unified Effect Declaration Lifecycle

Equipment, conditions, actions, and later auras use one typed `EffectDeclaration` grammar. The declaration's lifecycle determines whether it is a read-time contribution or a one-time mutation; it does not select a separate effect engine.

- `CONTINUOUS` declarations are attached sources evaluated whenever effective actor state is read. Equipped item bindings, active conditions, stances, and future auras use this mode. Their `ADD`, `MULTIPLY`, clamp, and granted-state modifiers exist only while the source exists; they never write a derived value into persistent current resource state.
- `INSTANT` declarations execute once under a `ResolvedEffectPlan` and its idempotent effect id. Action-owned declarations name `SOURCE` or a declared `ActionTargetSet`; other source types retain their own attached subject. The initial runtime mutation operations are `ADJUST_RESOURCE`, `APPLY_CONDITION`, and `REMOVE_CONDITION`; a later `TRANSITION_DISPOSITION` operation owns explicit main-state recovery, revival, defeat, and comparable lifecycle changes. They persist their result or active source and are never re-applied by later state reads.
- `APPLY_CONDITION` consults the frozen condition definition for `REPLACE`, `REFRESH`, `STACK`, or `PARALLEL` behavior and duration policy. `REMOVE_CONDITION` uses typed exact-key, authored-tag, or permitted-source selectors with deterministic removal order; neither operation treats an arbitrary payload row as an action target.
- Equipment is not exceptional: its worn effect is `CONTINUOUS`; a future on-equip or on-unequip trigger is an `INSTANT` declaration through the same plan and outcome path.
- Continuous conditions, equipment, stances, and auras may narrow the DML-authored policy of the actor's main disposition, but they cannot grant behavior denied by it. A game models immunity or recovery through explicit condition application/removal eligibility or an instant disposition transition rather than a hidden continuous override.
- Resource costs remain the dedicated conditional-debit declaration. `DAMAGE` remains deferred until combat resolution can apply hit, mitigation, and defeat rules before any resource mutation.
- Every declaration references only keys admitted by the frozen actor-state catalog. The current first payload format is a transitional `CONTINUOUS` source contract; catalog implementation retires synthetic unknown-resource evaluation.

##### source-07-1-task-list-shared-effect-engine-vertical-slice-1-143: Locked Direction

- The shared effect engine is the central execution seam for later stats/conditions/equipment/action-state work.
- Equipment and transient action states must contribute through this effect engine rather than bespoke side channels.
- The effect engine follows the unified actor model rather than inventing a separate gameplay subject concept.
- Game Logic owns target resolution and passes `ResolvedEffectPlan` objects to Entity Management; Game Session never becomes a final actor-target authority.
- Game Logic produces durable structured action outcomes and semantic presentation events; Game Session remains the transport, delivery, and rendering owner.
- One `EffectDeclaration` lifecycle grammar serves equipment, conditions, actions, and future auras; lifecycle constraints prevent read-time sources from replaying mutations.
- The implementation order remains:
  - unified actor model first;
  - shared effect engine second;
  - deeper stats/conditions and damage work after those seams exist.

The first typed effect set should include:

- additive numeric modifier;
- multiplicative numeric modifier;
- granted flag or trait;
- granted condition/state;
- scoped modifier for later slot/body-part, damage-family, or action-family application.

The first effect provenance model should include:

- `sourceType`;
- `sourceId`;
- `actorId`;
- optional duration metadata;
- stack policy.

The first evaluation order should stay deterministic:

1. base value;
2. additive modifiers;
3. multiplicative modifiers;
4. clamps or min/max constraints;
5. derived flags/conditions where projection needs them.
<!-- /migration-source -->

### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67

#### Equipment and Action-State Contributions Vertical Slice - Equipment and action-state effect contributions (source lines 1-67)

##### Preserved Source Text: source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67

<!-- migration-source path="design/project-management/vertical-slices/07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md" lines="1-67" sha256="be623c58252535d58c5b6e9696ab5914d594a798196941e06f1d8efebef870e9" heading-offset="3" -->
#### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: Equipment and Action-State Contributions Vertical Slice

##### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: Goal and Status

Prove that worn items and transient action states both contribute through the shared effect engine, including slot- or region-sensitive behavior where needed, without requiring the full combat system to exist yet. Status: first equipment contribution path is live, and the first transient action-state command path applies `blocking` through the shared actor-state/effect seam; richer combat-facing consumption remains future work.

##### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: Implementation Notes

- Item templates can now carry `effect_payload_json`.
- Equipped item instances feed their item payload modifiers into `QueryActorState` through the same shared effect evaluator used by active condition payloads.
- `ActorConditionMutationService` provides the first internal apply/expire seam for active conditions and transient action states, using the same actor state store queried by `QueryActorState`.
- `ApplyActorCondition` exposes that mutation seam as a gameplay-attested Entity Management gRPC contract, and Game Logic now routes that mutation for Game Session callers.
- `ApplyActorCondition` is wired to the production actor-condition mutation service and validates its nonblank source/effect id plus canonical effect payload JSON before idempotent replay lookup, so malformed requests cannot bypass or be acknowledged by a prior replay response.
- `BLOCK` / `GUARD` is the first player-facing transient action-state command. It runs through durable Game Session command execution, uses the durable effect id as actor-condition source provenance, and applies a short-lived `blocking` state with a `block_mitigation` effect payload.
- Entity Management runs a scheduled actor-condition expiry job using `entity.actor-condition.expiry-interval-seconds` so elapsed transient states are removed from the active-state table without requiring a gameplay read to clean them up.
- The first equipment proof supports passive resource/stat contributions such as `armour_value` or resistances; it does not yet include final creator-facing authoring UI or combat consumption.

##### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: Why This Slice Exists

FireMUD already has equipment as a gameplay concept and will soon need temporary states such as blocking, aiming, parrying, or charging. Those must not grow as disconnected mechanics.

This slice exists to prove one shared answer for:

- passive equipment-derived modifiers;
- slot/body-region-sensitive contributions;
- temporary action-state contributions with expiry or lifecycle rules.

##### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: Scope

- Define how worn items attach effect payloads to an entity's evaluated state.
- Define how transient action states are created, tracked, and expired.
- Define how scopes such as slot, body region, or tagged defense family participate in evaluation.
- Prove at least one equipment contribution and one transient action-state contribution through the same runtime path.
- Keep transient action states as canonical actor state owned with the rest of runtime gameplay state, even when they are short-lived.

##### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: Out of Scope

- Full weapon-resolution logic.
- Full combat timing windows.
- Rich UI beyond recording the state shape later presentation will need.

##### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: Acceptance Shape

- One equipped item can contribute a durable modifier such as armour or resistance.
- One transient action state such as `blocking` can contribute an additional scoped modifier or flag.
- Both contributions evaluate through the same shared effect model rather than two custom systems.
- The runtime ownership split remains:
  - Entity Management owns active state records;
  - Game Logic applies and evaluates them through gameplay rules.

##### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: Locked Direction

- Equipment contributions and transient action-state contributions are effect inputs, not separate runtime systems.
- Worn equipment contributes `CONTINUOUS` effect declarations while its equipment binding exists. A future on-equip or on-unequip trigger is an `INSTANT` declaration using the same resolved-plan, idempotency, outcome, and presentation path as any other action.
- Short-lived action states still belong to canonical actor gameplay state even when they expire quickly.
- This slice follows the shared actor/effect/state model and must land before damage-resolution work consumes those contributions.

##### source-07-2-task-list-equipment-and-action-state-contributions-vertical-slice-1-67: To Discuss Before Richer Equipment Implementation

- Equipment lifecycle triggers beyond passive worn state: define the authored event hooks and ordering for equip, unequip, use, break, and replacement without introducing an equipment-only execution path.
- Equipment and action-state interaction: define whether later authored action steps can require, consume, or transform an equipment contribution, and how those steps participate in the shared action outcome.
<!-- /migration-source -->

### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56

#### Damage and Mitigation Resolution Vertical Slice - Damage and mitigation resolution design (source lines 1-56)

##### Preserved Source Text: source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56

<!-- migration-source path="design/project-management/vertical-slices/07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md" lines="1-56" sha256="3e78a26856abd16a3917a0a4a843496c095575b3113463a8999c9ec15fef8bfb" heading-offset="3" -->
#### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56: Damage and Mitigation Resolution Vertical Slice

##### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56: Goal and Status

Define the later combat-facing slice that consumes the shared stats, conditions, and effect engine to resolve damage, mitigation, resistances, and action-gated defenses deterministically. Status: direction locked; implementation is future work.

##### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56: Why This Slice Exists

Once shared state and effect evaluation exist, FireMUD still needs one bounded design for how attacks and harmful effects actually consume that state.

This slice exists so the repo can later answer questions like:

- flat armour versus typed resistances;
- slot/body-region mitigation;
- block/parry/guard states;
- damage-family-specific reduction;
- condition-triggered amplifiers or immunities.

##### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56: Scope

- Define one canonical damage-resolution pipeline using the shared effect model.
- Define how mitigation scopes participate, including body region, damage type, and action-state tags.
- Define deterministic ordering for:
  - attack properties;
  - defensive effects;
  - resistances;
  - post-hit conditions.

##### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56: Out of Scope

- Full combat UX.
- Advanced AI tactics.
- Balance tuning for individual games.

##### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56: Acceptance Shape

- The repo has one explicit later slice to consume the shared state/effect foundation for combat-style resolution.
- Damage and mitigation are documented as consumers of the shared model, not a parallel bespoke system.

##### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56: Locked Direction

- Damage and mitigation resolution sits after unified actor state and shared effect evaluation, not before them.
- Damage, mitigation, resistances, and action-gated defenses are consumers of the shared actor/state/effect model rather than a second rules engine.
- Damage consumes bounded-resource floor transitions and authored actor dispositions. It must not assume a platform `health` key or make generic resource adjustment responsible for death, respawn, corpse, loot, victory, or revival policy.

##### source-07-3-task-list-damage-and-mitigation-resolution-vertical-slice-1-56: To Discuss Before Combat Implementation

- Authored damage packet and hit-resolution grammar: define typed damage families, delivery/hit stages, and target-set binding without making a resource key or a combat verb platform-owned.
- Mitigation order: define the authored precedence, caps, rounding, and reporting shape for equipment, conditions, resistances, block/guard/parry states, and later reactive effects.
- Combat timing: define how action windows and reactive defenses join the durable action/effect lifecycle without giving Game Session a second combat-state authority.
<!-- /migration-source -->

### source-07-4-task-list-unified-actor-model-vertical-slice-1-112

#### Unified Actor Model Vertical Slice - Unified runtime actor model (source lines 1-112)

##### Preserved Source Text: source-07-4-task-list-unified-actor-model-vertical-slice-1-112

<!-- migration-source path="design/project-management/vertical-slices/07.4-task-list-unified-actor-model-vertical-slice.md" lines="1-112" sha256="9a30d8a5c960b8fa8b534a1b38c49f365f0dad47c09c5acacf92cc63d53dfca2" heading-offset="3" -->
#### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Unified Actor Model Vertical Slice

##### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Goal and Status

Goal: define one canonical actor model for players, NPCs, gods, and later summons/pets so gameplay, presence, targeting, stats, and permissions build on one shared runtime concept rather than diverging entity families. Status: runtime identity direction locked; implementation is future work.

##### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Implementation Notes

- Current implementation still treats this area narrowly:
  - `WHO` uses player gameplay presence only;
  - god/admin presence is currently a role/presentation bucket over player presence;
  - no broader canonical actor runtime model is implemented yet.
- The target-state design below is now locked, even though the runtime implementation is still future work.

##### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Canonical Runtime Actor Decision

Every active gameplay being has one persisted runtime actor with an opaque `actorId`; it is not represented by a composite string such as `PLAYER:<characterId>` or by a different identity shape at every service boundary. The actor is the runtime gameplay subject, while character and NPC records remain the durable domain records that it links to.

- A player actor is unique for its active `{tenantId, gameInstanceId, characterId}` gameplay scope and links to its owning account and character.
- An NPC actor is unique for its NPC runtime instance, not merely for its authored NPC definition. Multiple live instances of the same definition are distinct actors.
- The actor core records tenant/game-instance context, `actorKind`, display name, transport/world presence state, and one persisted `dispositionKey`. It has no universal targetability or visibility fields; Game Logic evaluates release-pinned targeting policies against optional game-authored facts and actor variants add their player-character or NPC-runtime-instance linkage without changing that core.
- Disconnect and reconnect update the same player actor's presence state. They do not manufacture a replacement actor identity for the same active gameplay scope.
- `PLAYER` and `NPC` are the initial actor kinds. `PET` and `SUMMON` extend the same persisted core later. Gods and admins remain `PLAYER` actors with capability and staff-presentation overlays, never a separate actor kind.

The persisted disposition is the actor's one main gameplay state, authored in the frozen game version. A normal state, unconsciousness, defeat, death, ghost state, or exhaustion are examples, not platform enums. It defines base action admission through DML-authored `admissionTags` and feedback policy. Conditions and equipment are continuous overlays that can only add denied admission tags or other restrictions; they cannot grant behavior that the main disposition denies or become competing lifecycle owners. They may contribute ordinary game-authored facts that separate reusable DML `ObservationPolicy` and `TargetingPolicy` definitions consume, such as a hidden or see-hidden status, without making those concepts universal actor fields. Recovery, immunity, revival, and another exceptional state change use an explicit idempotent instant condition removal/prevention or disposition transition. Transport/session presence remains separate: disconnecting does not defeat an actor, and an NPC can be defeated without a player session.

Ownership remains explicit:

- Entity Management owns the persisted actor core and its links to character or NPC runtime state.
- World Management owns authoritative location and room occupancy for an actor.
- Game Session owns ephemeral session attachment, protocol state, and player-facing presence projection; it does not become a second actor authority.
- Game Logic resolves gameplay targets and applies effects against actor identity. Account Service remains authoritative for account identity and authorization inputs.

##### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Why This Slice Exists

Recent design work established that gods are elevated players, not a separate kind of being. That same principle points toward a broader actor-model need:

- players and NPCs should share more gameplay shape than they differ on;
- gods should be players with elevated capabilities and presentation;
- later target resolution, conditions, combat, and communication want one actor abstraction;
- future summons/pets/hirelings should not force another ontology reset.

##### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Scope

- Define one shared actor concept for gameplay-relevant beings.
- Define which fields are common across players and NPCs.
- Define how actor identity relates to:
  - account/character ownership;
  - runtime presence;
  - permissions/capabilities;
  - targeting and communication.
- Define how elevated roles such as gods/admins attach to player actors.

##### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Out of Scope

- Full combat AI or behavior-tree design.
- Full account/character creation flow.
- Item identity and holder semantics, which belong to `06`.

##### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Known Design Considerations

- Gods are players with elevated role/capability state, not a separate actor class.
- NPCs and players should converge where gameplay systems consume actors generically.
- `WHO` should continue to list player presences only in the first slice, but the underlying actor model should not block later generic actor targeting and presentation.
- Future stats/conditions/effect work should attach cleanly to shared actor state.
- Canonical gameplay-facing identity should be the persisted actor, not a mixture of account/session/player-only types or composite reference strings.
- Suggested actor core should include:
  - actor id;
  - tenant id;
  - game instance id;
  - actor kind such as `PLAYER`, `NPC`, later `PET` or `SUMMON`;
  - display name;
  - shared gameplay-state attachment point.
- Player actors should additionally link to account and character identity.
- NPC actors should not require account linkage but should still share the common gameplay-facing actor shape.
- Gods/admins are elevated player actors, not a separate actor kind.
- Gameplay systems should consume normalized actor-facing classification rather than repeatedly re-deriving role/visibility from auth/session claims.

##### source-07-4-task-list-unified-actor-model-vertical-slice-1-112: Locked Direction

- The unified actor model should be designed before deeper effect/stat layering.
- Players, NPCs, gods, and later summons/pets are actor variants over one shared runtime concept.
- Gods/admins remain elevated player actors rather than a separate actor kind.
- The shared effect engine and later stats/conditions work should attach to this actor model rather than inventing a separate gameplay subject model.
- The first concrete implementation pass should define:
  - what makes something an actor at runtime;
  - which actor state is canonical versus derived;
  - how player, NPC, and elevated-player variants share one core model without splitting into separate subsystems.

The first concrete actor core should be:

- `actorId`;
- `tenantId`;
- `gameInstanceId`;
- `actorKind`;
- `displayName`;
- `presenceState`;
- `dispositionKey`.

The first actor kinds should be:

- `PLAYER`;
- `NPC`;
- later `PET` and `SUMMON` without changing the shared core.

Player actors link to account and character identity. NPC actors link to an NPC runtime instance and may reference its authored NPC definition. Elevated staff/god state attaches as capability/visibility over a `PLAYER` actor rather than creating a separate actor kind. A player actor persists across disconnect/reconnect within the same active gameplay scope; presence changes without replacing `actorId`.
<!-- /migration-source -->
