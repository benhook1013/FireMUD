# Slice Progress Queue

This file is the short ordered view of what is still outstanding.

It intentionally omits completed slices and uses the individual slice docs as the canonical detail source.

## Suggested Order

### 1. Immediate implementation queue

These are narrow enough to implement now and unblock the current runtime/platform direction.

1. [02.1.3 Session Activity and WHO Presence](./02.1.3-task-list-session-activity-and-who-presence-vertical-slice.md)
   Remaining: build on the now-live activity, AFK, and canonical presence-lifecycle substrate with recent-presence policy, takeover proof, and later `WHO` presentation consumers.
2. [02.1.4 Cross-Game Social Presence and Friend Activity](./02.1.4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice.md)
   Remaining: extend the now-live account-scoped friend presence substrate with broader social consumers and later privacy refinement; canonical world/realm presentation is now live.
3. [02.1.2 Logout and Session Termination](./02.1.2-task-list-logout-and-session-termination-vertical-slice.md)
   Remaining: mostly closed; the remaining tail is broader reconnect/recent-presence lifecycle proof rather than more logout-path semantics.
4. [02.13.8 Built-In Command Registry and Dispatch Rollout](./02.13.8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice.md)
   Remaining: extend the now-live provider-backed registry and registry-owned alias seam into the first true non-built-in provider without letting the parser or interpreter grow a second command-definition path.

### 2. Active architecture follow-through

These are already partly real in code and should continue after the immediate runtime fixes.

1. [02.13.8 Built-In Command Registry and Dispatch Rollout](./02.13.8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice.md)
   Remaining: keep future built-in and authored command growth on the provider-backed registry plus family-handler seam without regressing into central interpreter branching.
2. [02.13.7 Action Classification and Activity Semantics](./02.13.7-task-list-action-classification-and-activity-semantics-vertical-slice.md)
   Remaining: extend the current primary-category plus timestamp consumer into optional tags/facets and later scripting/policy consumers.
3. [02.13.9 Authored Action Definition and Execution Model](./02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md)
   Remaining: define the first typed authored-action record and land the first non-built-in command-definition provider on the now-live shared registry-plus-alias seam.
4. [02.1.3 Session Activity and WHO Presence](./02.1.3-task-list-session-activity-and-who-presence-vertical-slice.md)
   Remaining: grow from the current bounded `WHO` plus explicit/auto-AFK and canonical lifecycle substrate into the fuller activity model when that work becomes active.
5. [02.1.4 Cross-Game Social Presence and Friend Activity](./02.1.4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice.md)
   Remaining: grow the first account-scoped friend presence seam into later social consumers and later privacy refinement without reopening `WHO`.
6. [06 Task List Inventory, Containers, Equipment](./06-task-list-inventory-containers-equipment-vertical-slice.md)
   Remaining: complete the remaining `06.3` follow-through and later authored stackability.
7. [06.3.1 Stable Item Instance Visible Ref Allocation](./06.3.1-task-list-item-instance-visible-ref-allocation-vertical-slice.md)
   Remaining: decide whether and where ordinary prose views ever expose refs beyond management surfaces.
8. [06.3.2 Authored Stackability and Fungibility](./06.3.2-task-list-authored-stackability-and-fungibility-vertical-slice.md)
   Remaining: build on the now-live `stackFamilyKey` substrate with richer authored family sources; explicit stack-family selector UX for ambiguous holder-local families is now live.
9. [02.18 Service Boundary and Audit Hardening](./02.18-task-list-service-boundary-and-audit-hardening-vertical-slice.md)
   Remaining: keep `02.18.6` at operator-proof level and continue any later hardening only if real-load evidence justifies it.
10. [02.18.1 Audit Log and Moderation Separation](./02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md)
   Remaining: keep future callers on the dedicated log-event path; the current account/logging-admin separation is now in place and covered.

### 3. Design settled enough, but not started or only placeholder-level

These are not broad audit topics anymore; they are real future slices with known direction.

1. [09 Multi-Tenancy, Realm Routing, and Runtime Boundaries](./09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice.md)
   Remaining: the family is now actively underway with shared routing/catalog substrate, public-production membership creation, and the first real realm-aware roster substrate; inventory/equipment now follow that scoped character namespace, and the main follow-through is final admission-pointer authority plus progression/resources/loadout namespaces beyond it.
2. [08 Game Design Publishing and Runtime Activation](./08-task-list-game-design-publishing-and-runtime-activation-vertical-slice.md)
   Remaining: `08.1` now has the release-bundle seam, durable publish-attempt plus participant-observation storage, Game Design control-plane digest RPC, and script-patch gating on the canonical framework; `08.2` now has persisted artifact-state proof rows, exact-bytes repair, exact exported-key proof, and operator-visible tombstone/purge workflow APIs; `08.3` now has deterministic launch-descriptor persistence plus Game Session preflight before `game_instances` row creation; the main remaining work is full-version participant coverage, broader non-local deletion-eligibility truth, and World Management/cutover activation consumers for the launch-descriptor seam.
3. [10 Scripting, Automation, and Runtime Orchestration](./10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice.md)
   Remaining: the scripting domain now has a canonical family instead of fragmented indirect coverage; `10.1` through `10.5` are the bounded runtime/control-plane cuts, while design-time publication boundaries remain intentionally anchored in `08.4`.
4. [02.1.1 Email OTP and Text Auth Options](./02.1.1-task-list-email-otp-and-text-auth-options-vertical-slice.md)
5. [02.1.2 Logout and Session Termination](./02.1.2-task-list-logout-and-session-termination-vertical-slice.md)
6. [02.1.5 Admin and God Capability and Visibility](./02.1.5-task-list-admin-god-capability-and-visibility-vertical-slice.md)
7. [02.1.4.1 Account Versus Character Social Scope](./02.1.4.1-task-list-account-vs-character-social-scope-vertical-slice.md)
8. [02.1.5.1 Hidden Staff Modes and Capability Bundles](./02.1.5.1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice.md)
9. [02.13.7 Action Classification and Activity Semantics](./02.13.7-task-list-action-classification-and-activity-semantics-vertical-slice.md)
10. [02.13.10 Structured Transcript and Replay End State](./02.13.10-task-list-structured-transcript-and-replay-end-state-vertical-slice.md)
11. [02.13.10.1 Structured Transcript Persistence and Replay Storage](./02.13.10.1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice.md)
12. [02.13.11 Shared Time, Duration, and Scheduler Semantics](./02.13.11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice.md)
13. [07.4 Unified Actor Model](./07.4-task-list-unified-actor-model-vertical-slice.md)
14. [07 Entity Stats and Conditions](./07-task-list-entity-stats-and-conditions-vertical-slice.md)
15. [07.1 Shared Effect Engine](./07.1-task-list-shared-effect-engine-vertical-slice.md)
16. [07.2 Equipment and Action-State Contributions](./07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md)
17. [07.3 Damage and Mitigation Resolution](./07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md)

### 4. Discussion-gated follow-ups

These are now explicitly tracked and should get a deliberate discussion pass before implementation starts.

1. [02.18.7 Durable Command Ingress and Status Ledger](./02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md)
   Suggested first heavy-substrate slice from the recent crash-safety audit: persist `commandId`, command lifecycle, and terminal convergence so later replay/fencing work has a canonical identity seam.
2. [02.18.8 Tick Batch and Effect Ledger Hardening](./02.18.8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice.md)
   Suggested second heavy-substrate slice: move durable execution truth into `tick_batch` / `tick_effect` style records so Redis becomes coordination rather than the only real in-flight truth.
3. [02.18.9 Region Epoch, Fencing, and Runtime Ownership](./02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md)
   Suggested third heavy-substrate slice: land durable region ownership and stale-executor fencing once batch/effect identity exists.
4. [02.18.10 Effect Idempotency and Replay Guards](./02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md)
   Suggested fourth heavy-substrate slice: make replay/no-op behavior concrete with durable `EffectId` handling and idempotent apply guards.
5. [02.18.11 Migrate Live Gameplay Commands Onto the Durable Execution Path](./02.18.11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice.md)
   Suggested migration slice after the earlier substrate exists: start with movement and other direct state-changing gameplay commands.

### 5. Lower-priority platform/settings expansion

These are still valid, but they are behind the current gameplay/runtime slices.

1. [06.1 Inventory and Equipment Settings](./06.1-task-list-inventory-and-equipment-settings-vertical-slice.md)
2. [02.9.1 Settings Presets and Operator Baselines](./02.9.1-task-list-settings-presets-and-operator-baselines-vertical-slice.md)

### 6. Manual QA / operator-proof tails

These are not major design problems, but they are not fully closed.

1. [02.3 Reconnect and Session Recovery](./02.3-task-list-reconnect-and-session-recovery-vertical-slice.md)
2. [02.4 First-Party Reconnect Parity](./02.4-task-list-first-party-reconnect-parity-vertical-slice.md)
3. [02.5 Non-Edge Failover Invisibility](./02.5-task-list-non-edge-failover-invisibility-vertical-slice.md)
4. [02.8 Game Logic Restart Invisibility](./02.8-task-list-game-logic-restart-invisibility-vertical-slice.md)
5. [02.18.6 Tick Scheduler Backpressure and Merge Semantics](./02.18.6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice.md)
   Remaining: operator-proof the chosen alert thresholds in preview/prod-like runs.
6. [02.14.4 Metrics Cardinality and Label Policy Hardening](./02.14.4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice.md)
   Remaining: only opportunistic audit tail work and later policy wording cleanup.

## Practical Next Code Slices

If you want the clearest immediate path, do these next:

1. `02.1.3` presence/WHO follow-through
   Best next gameplay-runtime batch because the activity, AFK, and canonical lifecycle substrate are now live and the remaining work is recent-presence policy, takeover proof, and later `WHO` presentation rather than vague design.
2. `02.1.4` cross-game social presence follow-through
   Best next social batch because the first account-scoped friend presence seam, honest `lastSeenAt`, account-owned visibility-policy seam, and canonical world/realm labels are now live, leaving broader consumers and later privacy refinement rather than substrate work.
3. `02.13.9` authored action definition and execution model
   Best next platform-shape batch because the provider-backed registry and alias seam are now live and the next honest step is the first typed authored-action model rather than more built-in-only growth.
4. `02.1.2` logout and deliberate session termination
   Best next runtime tail because the core logout command is now live and the remaining work is proof/alignment across reconnect and transport variants rather than fresh design.
5. `06.3.2` bounded authored compatibility-mode follow-up
   Best next item-model batch if we want to extend the now-live stack-family substrate into richer authored family sourcing and later authored compatibility modes beyond the now-live explicit selector UX.

If the goal is continued design-to-slice cleanup rather than code, do this next:

1. `09` multi-tenancy / realm-routing family
   Implementation is now underway; the next honest follow-through is final admission-pointer authority plus realm-/instance-aware roster/state namespaces rather than more family creation.
2. `08` game-design publish/version/activation family
   Best next control-plane implementation family after `09`; `08.1` now has the canonical publish-attempt framework and script-patch gating, `08.2` now has artifact-state proof / repair, and `08.3` now has launch-descriptor persistence plus Game Session preflight, so the next honest work is full-version participant coverage, purge workflow coverage, and World Management/cutover activation consumers.
3. `10` scripting/automation/runtime-orchestration family
   Best next runtime/control-plane planning family after `08` and `09`; start with `10.1`, then `10.2`, then `10.3`, keeping `08.4` as the publication-boundary companion rather than duplicating it.

## Operator-Proof / Audit Tails

These are still useful, but they are not the next best feature/code-shape slices.

1. `02.18.6` operator-proof the chosen scheduler thresholds
2. `02.14.4` opportunistic metrics-cardinality tail only
