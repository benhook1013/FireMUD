# Slice Progress Queue

This file is the short ordered view of what is still outstanding.

It intentionally omits completed slices and uses the individual slice docs as the canonical detail source.

## Suggested Order

### 1. Immediate implementation queue

These are narrow enough to implement now and unblock the current runtime/platform direction.

1. [06.3 Replace Aggregated Item Stacks With Distinct Item Instances](./vertical-slices/06.3-task-list-container-item-instance-identity-vertical-slice.md)
   Remaining: extend the now-live first strict stack model with bounded authored compatibility modes beyond definition-level sameness.
2. [02.13.8 Built-In Command Registry and Dispatch Rollout](./vertical-slices/02.13.8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice.md)
   Remaining: keep future built-in command growth on the canonical registry-plus-family-handler seam and carry the richer command-definition metadata forward into later authored-command registration.

### 2. Active architecture follow-through

These are already partly real in code and should continue after the immediate runtime fixes.

1. [02.13.8 Built-In Command Registry and Dispatch Rollout](./vertical-slices/02.13.8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice.md)
   Remaining: prove ongoing built-in growth goes through registry plus family-handler extension, and carry the same seam forward into later authored-command registration.
2. [02.1.3 Session Activity and WHO Presence](./vertical-slices/02.1.3-task-list-session-activity-and-who-presence-vertical-slice.md)
   Remaining: grow from the current bounded `WHO` implementation into the fuller activity model when that work becomes active.
3. [06 Task List Inventory, Containers, Equipment](./vertical-slices/06-task-list-inventory-containers-equipment-vertical-slice.md)
   Remaining: complete the remaining `06.3` follow-through and later authored stackability.
4. [06.3.1 Stable Item Instance Visible Ref Allocation](./vertical-slices/06.3.1-task-list-item-instance-visible-ref-allocation-vertical-slice.md)
   Remaining: decide whether and where ordinary prose views ever expose refs beyond management surfaces.
5. [06.3.2 Authored Stackability and Fungibility](./vertical-slices/06.3.2-task-list-authored-stackability-and-fungibility-vertical-slice.md)
   Remaining: add bounded authored compatibility modes and prove incompatible stack rows stay separate.
6. [02.18 Service Boundary and Audit Hardening](./vertical-slices/02.18-task-list-service-boundary-and-audit-hardening-vertical-slice.md)
   Remaining: keep `02.18.6` at operator-proof level and continue any later hardening only if real-load evidence justifies it.
7. [02.18.1 Audit Log and Moderation Separation](./vertical-slices/02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md)
   Remaining: keep future callers on the dedicated log-event path; the current account/logging-admin separation is now in place and covered.

### 3. Design settled enough, but not started or only placeholder-level

These are not broad audit topics anymore; they are real future slices with known direction.

1. [02.1.1 Email OTP and Text Auth Options](./vertical-slices/02.1.1-task-list-email-otp-and-text-auth-options-vertical-slice.md)
2. [02.1.2 Logout and Session Termination](./vertical-slices/02.1.2-task-list-logout-and-session-termination-vertical-slice.md)
3. [02.1.4 Cross-Game Social Presence and Friend Activity](./vertical-slices/02.1.4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice.md)
4. [02.1.5 Admin and God Capability and Visibility](./vertical-slices/02.1.5-task-list-admin-god-capability-and-visibility-vertical-slice.md)
5. [02.1.4.1 Account Versus Character Social Scope](./vertical-slices/02.1.4.1-task-list-account-vs-character-social-scope-vertical-slice.md)
6. [02.1.5.1 Hidden Staff Modes and Capability Bundles](./vertical-slices/02.1.5.1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice.md)
7. [02.13.7 Action Classification and Activity Semantics](./vertical-slices/02.13.7-task-list-action-classification-and-activity-semantics-vertical-slice.md)
8. [02.13.9 Authored Action Definition and Execution Model](./vertical-slices/02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md)
9. [02.13.10 Structured Transcript and Replay End State](./vertical-slices/02.13.10-task-list-structured-transcript-and-replay-end-state-vertical-slice.md)
10. [02.13.10.1 Structured Transcript Persistence and Replay Storage](./vertical-slices/02.13.10.1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice.md)
11. [02.13.11 Shared Time, Duration, and Scheduler Semantics](./vertical-slices/02.13.11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice.md)
12. [07.4 Unified Actor Model](./vertical-slices/07.4-task-list-unified-actor-model-vertical-slice.md)
13. [07 Entity Stats and Conditions](./vertical-slices/07-task-list-entity-stats-and-conditions-vertical-slice.md)
14. [07.1 Shared Effect Engine](./vertical-slices/07.1-task-list-shared-effect-engine-vertical-slice.md)
15. [07.2 Equipment and Action-State Contributions](./vertical-slices/07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md)
16. [07.3 Damage and Mitigation Resolution](./vertical-slices/07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md)

### 4. Discussion-gated follow-ups

No immediate discussion-gated follow-ups remain in the currently active queue. Park new items here only when a slice still needs a deliberate design pass before implementation can safely start.

### 5. Lower-priority platform/settings expansion

These are still valid, but they are behind the current gameplay/runtime slices.

1. [06.1 Inventory and Equipment Settings](./vertical-slices/06.1-task-list-inventory-and-equipment-settings-vertical-slice.md)
2. [02.9.1 Settings Presets and Operator Baselines](./vertical-slices/02.9.1-task-list-settings-presets-and-operator-baselines-vertical-slice.md)

### 6. Manual QA / operator-proof tails

These are not major design problems, but they are not fully closed.

1. [02.3 Reconnect and Session Recovery](./vertical-slices/02.3-task-list-reconnect-and-session-recovery-vertical-slice.md)
2. [02.4 First-Party Reconnect Parity](./vertical-slices/02.4-task-list-first-party-reconnect-parity-vertical-slice.md)
3. [02.5 Non-Edge Failover Invisibility](./vertical-slices/02.5-task-list-non-edge-failover-invisibility-vertical-slice.md)
4. [02.8 Game Logic Restart Invisibility](./vertical-slices/02.8-task-list-game-logic-restart-invisibility-vertical-slice.md)
5. [02.18.6 Tick Scheduler Backpressure and Merge Semantics](./vertical-slices/02.18.6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice.md)
   Remaining: operator-proof the chosen alert thresholds in preview/prod-like runs.
6. [02.14.4 Metrics Cardinality and Label Policy Hardening](./vertical-slices/02.14.4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice.md)
   Remaining: only opportunistic audit tail work and later policy wording cleanup.

## Practical Next Code Slices

If you want the clearest immediate path, do these next:

1. `06.3.2` bounded authored compatibility-mode follow-up
   Best next real code batch because the storage/runtime groundwork is already live and the remaining work is now contract-locked.
2. `02.13.8` built-in command registry follow-through
   Best next platform-shape batch because the dispatcher seam is now real and the remaining work is mainly future authored-command follow-through.
3. `02.18.4` world/entity service-boundary auth follow-through
   Best next hardening batch if you want runtime/platform work instead of more gameplay model work.

## Operator-Proof / Audit Tails

These are still useful, but they are not the next best feature/code-shape slices.

1. `02.18.6` operator-proof the chosen scheduler thresholds
2. `02.14.4` opportunistic metrics-cardinality tail only
