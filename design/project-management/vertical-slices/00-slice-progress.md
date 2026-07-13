# Slice Progress Queue

This file is the short ordered view of what is still outstanding.

It intentionally omits completed slices and uses the individual slice docs as the canonical detail source.

## Suggested Order

### 1. Next Boundary Needs Design Selection

There is no unstarted, fully specified implementation batch at the current boundary. Do not reopen completed slices from generic later-work wording; select a concrete target contract from these families before implementation resumes.

1. [02.18.9 Region Epoch, Fencing, and Runtime Ownership](./02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md)
   Remaining: move from current game-instance ownership to true region-scoped ownership; later work also carries fence tokens into session-front-end forwarding and downstream/domain-specific effect families.
2. [02.15.8 Environment Preflight and Secret-Binding Convergence](./02.15.8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice.md)
   Remaining: bind canonical traffic-open evidence production and richer Kubernetes live-state validation to a deliberate operator/deployment contract.
3. [02.13.7 Action Classification and Activity Semantics](./02.13.7-task-list-action-classification-and-activity-semantics-vertical-slice.md)
   Remaining: extend canonical category/tag truth into broader policy consumers beyond the now-live scripting, client-envelope, classic-rendering, and prompt-policy seams.
4. [02.13.9 Authored Action Definition and Execution Model](./02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md)
   Remaining: extend the live release-admitted authored-action path beyond self-targeted `APPLY_ACTION_STATE` v1 with richer targeting, cost, cooldown, timing, and effect semantics.
5. [02.13.11 Shared Time, Duration, and Scheduler Semantics](./02.13.11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice.md)
   Remaining: adopt the shared wall-clock/gameplay-clock vocabulary, runtime cooldown state, timed effects, and scheduler semantics as those systems become real.
6. [06 Task List Inventory, Containers, Equipment](./06-task-list-inventory-containers-equipment-vertical-slice.md)
   Remaining: holder-transfer safety, canonical transfer-audit persistence with attested session/effect correlation, Game Session -> Game Logic -> Entity Management item command routing, game-configured equipment slot/body-layout validation, stable item-instance refs, explicit authored stackability, and WebSocket plus Telnet cross-service room-ground/equipment happy/failure paths are now live. The honest remaining tail is later item-family depth such as settings expansion, richer authored compatibility sources, and future gameplay consumers built on the now-canonical `06.3` / `06.4` substrate.

### 2. Future and Design-Gated Families

These are real future slices, but some still need their owning design docs to settle a concrete next contract.

1. [02.1.1 Email OTP and Text Auth Options](./02.1.1-task-list-email-otp-and-text-auth-options-vertical-slice.md)
2. [02.1.5 Admin and God Capability and Visibility](./02.1.5-task-list-admin-god-capability-and-visibility-vertical-slice.md)
3. [02.1.5.1 Hidden Staff Modes and Capability Bundles](./02.1.5.1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice.md)
4. [07.4 Unified Actor Model](./07.4-task-list-unified-actor-model-vertical-slice.md)
5. [07 Entity Stats and Conditions](./07-task-list-entity-stats-and-conditions-vertical-slice.md)
   Remaining: Entity Management now has evaluated actor state, persisted resource/condition rows, shared effect evaluation, equipped-item contributions, replay-guarded action-state mutation/expiry, and the first release-admitted authored action-effect execution; generic stat/condition definitions, resource-cost mutation, multi-effect actions, and damage/mitigation remain future work.
6. [07.1 Shared Effect Engine](./07.1-task-list-shared-effect-engine-vertical-slice.md)
   Remaining: the typed evaluation seam now consumes active conditions, equipment payloads, replay-guarded action states, and the first admitted authored `APPLY_ACTION_STATE` declaration. The honest tail is generic authored stat/condition definitions, additional effect kinds, resource costs/cooldowns, and combat consumers.
7. [07.2 Equipment and Action-State Contributions](./07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md)
   Remaining: equipped item templates contribute through actor-state reads, and player-authored `BLOCK` / `GUARD` plus the first release-admitted action-state effect execute through the replay-guarded condition mutation/expiry seam. Richer scoped combat-facing consumption remains future work.
8. [07.3 Damage and Mitigation Resolution](./07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md)

### 3. Lower-Priority Platform/Settings Expansion

These are still valid, but they are behind the current gameplay/runtime slices.

1. [06.1 Inventory and Equipment Settings](./06.1-task-list-inventory-and-equipment-settings-vertical-slice.md)
2. [02.9.1 Settings Presets and Operator Baselines](./02.9.1-task-list-settings-presets-and-operator-baselines-vertical-slice.md)

### 4. Manual QA / Operator-Proof Tails

These are not major design problems, but they are not fully closed.

1. [02.3 Reconnect and Session Recovery](./02.3-task-list-reconnect-and-session-recovery-vertical-slice.md)
2. [02.4 First-Party Reconnect Parity](./02.4-task-list-first-party-reconnect-parity-vertical-slice.md)
3. [02.5 Non-Edge Failover Invisibility](./02.5-task-list-non-edge-failover-invisibility-vertical-slice.md)
4. [02.8 Game Logic Restart Invisibility](./02.8-task-list-game-logic-restart-invisibility-vertical-slice.md)
