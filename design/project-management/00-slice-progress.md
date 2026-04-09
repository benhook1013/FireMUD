# Slice Progress Queue

This file is the short ordered view of what is still outstanding.

It intentionally omits completed slices and uses the individual slice docs as the canonical detail source.

## Suggested Order

### 1. Immediate implementation queue

These are narrow enough to implement now and unblock the current runtime/platform direction.

1. [02.2.1 Session Start Admission Ordering and IP-Limit Safety](./vertical-slices/02.2.1-task-list-session-start-admission-ordering-and-ip-limit-safety-vertical-slice.md)
   Remaining: finish the last admission-ordering edge cases so rejected replacement starts never tear down a still-valid live session.
2. [02.18.2 Internal Blocking gRPC Auth Propagation](./vertical-slices/02.18.2-task-list-internal-grpc-auth-propagation-vertical-slice.md)
   Remaining: decide whether the remaining intentional raw-stub outliers should adopt the same seam or stay distinct later.
3. [02.14.4 Metrics Cardinality and Label Policy Hardening](./vertical-slices/02.14.4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice.md)
   Remaining: continue the repo-wide audit and tighten the canonical allowlist/denylist now that gameplay/session counters, retry-queue gauges, and a first CI guardrail are in place.
4. [06.4.1 Safe Item Transfer and Handoff Semantics](./vertical-slices/06.4.1-task-list-safe-item-transfer-and-handoff-semantics-vertical-slice.md)
   Remaining: finish explicit guarded-handoff coverage and audit semantics across the remaining item-mutation paths.
5. [06.3 Replace Aggregated Item Stacks With Distinct Item Instances](./vertical-slices/06.3-task-list-container-item-instance-identity-vertical-slice.md)
   Remaining: explicit authored stackability and stack-compatibility rules on top of the now-live item-instance model.
6. [06.4 Unified Item Holder and Transfer Model](./vertical-slices/06.4-task-list-unified-item-holder-and-transfer-model-vertical-slice.md)
   Remaining: finish the shared transfer contract, shared transfer audit semantics, and the remaining holder-policy cleanup.
7. [02.18.3 Workflow Transaction Boundary Hardening](./vertical-slices/02.18.3-task-list-workflow-transaction-boundary-hardening-vertical-slice.md)
   Remaining: reduce the remaining “hold DB transaction open across external calls” cases without regressing runtime-state safety.
8. [02.18.6 Tick Scheduler Backpressure and Merge Semantics](./vertical-slices/02.18.6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice.md)
   Remaining: finish the observability, remaining merge/skip/rejection semantics, and keep Redis tick namespaces isolated across tick domains.

### 2. Active architecture follow-through

These are already partly real in code and should continue after the immediate runtime fixes.

1. [02.13.8 Built-In Command Registry and Dispatch Rollout](./vertical-slices/02.13.8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice.md)
    Remaining: finish the richer command-definition seam, especially action classification and ownership metadata.
2. [02.1.3 Session Activity and WHO Presence](./vertical-slices/02.1.3-task-list-session-activity-and-who-presence-vertical-slice.md)
    Remaining: grow from the current bounded `WHO` implementation into the fuller activity model when that work becomes active.
3. [06 Task List Inventory, Containers, Equipment](./vertical-slices/06-task-list-inventory-containers-equipment-vertical-slice.md)
    Remaining: complete the remaining `06.3` / `06.4` follow-through and later authored stackability.
4. [06.3.1 Stable Item Instance Visible Ref Allocation](./vertical-slices/06.3.1-task-list-item-instance-visible-ref-allocation-vertical-slice.md)
    Remaining: decide whether and where ordinary prose views ever expose refs beyond management surfaces.
5. [02.18 Service Boundary and Audit Hardening](./vertical-slices/02.18-task-list-service-boundary-and-audit-hardening-vertical-slice.md)
    Remaining: complete the remaining `02.18.2`, `02.18.3`, and `02.18.6` follow-ups.
6. [02.18.1 Audit Log and Moderation Separation](./vertical-slices/02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md)
    Remaining: finish the separation so harmless audit traffic and destructive moderation traffic cannot blur again.
7. [02.18.4 World and Entity Service Boundary Auth](./vertical-slices/02.18.4-task-list-world-and-entity-service-boundary-auth-vertical-slice.md)
    Remaining: finish the remaining service-boundary/auth alignment as those read/write paths harden.

### 3. Design settled enough, but not started or only placeholder-level

These are not broad audit topics anymore; they are real future slices with known direction.

1. [02.1.1 Email OTP and Text Auth Options](./vertical-slices/02.1.1-task-list-email-otp-and-text-auth-options-vertical-slice.md)
2. [02.1.2 Logout and Session Termination](./vertical-slices/02.1.2-task-list-logout-and-session-termination-vertical-slice.md)
3. [02.1.4 Cross-Game Social Presence and Friend Activity](./vertical-slices/02.1.4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice.md)
4. [02.1.5 Admin and God Capability and Visibility](./vertical-slices/02.1.5-task-list-admin-god-capability-and-visibility-vertical-slice.md)
5. [02.13.7 Action Classification and Activity Semantics](./vertical-slices/02.13.7-task-list-action-classification-and-activity-semantics-vertical-slice.md)
6. [02.13.9 Authored Action Definition and Execution Model](./vertical-slices/02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md)
7. [02.13.10 Structured Transcript and Replay End State](./vertical-slices/02.13.10-task-list-structured-transcript-and-replay-end-state-vertical-slice.md)
8. [02.13.11 Shared Time, Duration, and Scheduler Semantics](./vertical-slices/02.13.11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice.md)
9. [07 Entity Stats and Conditions](./vertical-slices/07-task-list-entity-stats-and-conditions-vertical-slice.md)
10. [07.1 Shared Effect Engine](./vertical-slices/07.1-task-list-shared-effect-engine-vertical-slice.md)
11. [07.2 Equipment and Action-State Contributions](./vertical-slices/07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md)
12. [07.3 Damage and Mitigation Resolution](./vertical-slices/07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md)
13. [07.4 Unified Actor Model](./vertical-slices/07.4-task-list-unified-actor-model-vertical-slice.md)

### 4. Discussion-gated follow-ups

These are intentionally parked until the narrower unresolved design questions are revisited.

1. [02.1.4.1 Account Versus Character Social Scope](./vertical-slices/02.1.4.1-task-list-account-vs-character-social-scope-vertical-slice.md)
2. [02.1.5.1 Hidden Staff Modes and Capability Bundles](./vertical-slices/02.1.5.1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice.md)
3. [02.13.10.1 Structured Transcript Persistence and Replay Storage](./vertical-slices/02.13.10.1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice.md)

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

## Practical Next Three

If you want the clearest immediate path, do these next:

1. `02.2.1` session-start admission ordering follow-through
2. `02.14.4` repo-wide metrics cardinality follow-through
3. `06.4.1` safe item transfer and handoff follow-through
