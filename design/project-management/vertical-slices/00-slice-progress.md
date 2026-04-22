# Slice Progress Queue

This file is the short ordered view of what is still outstanding.

It intentionally omits completed slices and uses the individual slice docs as the canonical detail source.

## Suggested Order

### 1. Immediate implementation queue

These are narrow enough to implement now and unblock the current runtime/platform direction.

1. [08.1 Publish Digest Gating and Release Attestation](./08.1-task-list-publish-digest-gating-and-release-attestation-vertical-slice.md)
   Remaining: build on the now-live canonical publish-attempt framework, participant observations, recorded-digest baselines, typed gate failures, typed missing/unsupported-attestation reads, launch/activation hard-fail consumers, exact manifest-key repair proof, and current World/Entity version-scoped digest inputs by extending the same contract to later consumers and later domain-template families.
2. [08.2 Published Asset Manifest and Purge Lifecycle](./08.2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice.md)
   Remaining: keep the now-live artifact-state proof, frozen export-version proof, exact exported-key proof, exact-bytes repair, and tombstone/purge workflow substrate moving toward broader normalized deletion/reference-table truth and richer derived-artifact coverage.
3. [08.3 Launch Descriptor and Activation Preflight](./08.3-task-list-launch-descriptor-and-activation-preflight-vertical-slice.md)
   Remaining: extend the now-live launch-descriptor, approved remap-set control-plane, cutover-compatibility preflight, persisted `PrepareVersionUpgrade` record, retry-safe canonical prepared cutover execution path, asset-proof preflight, stricter World topology validation, and current Entity `S1` / `S2` compatibility fence into broader cutover/retirement consumers, exact Entity target-template validation once version-scoped entity-template tables exist, and later instance-scoped runtime world-state families.
4. [08.5 World Design Mutation API Surface](./08.5-task-list-world-design-mutation-api-surface-vertical-slice.md)
   Remaining: continue the review-spawned canonical World Management design-time mutation surface so Game Design applies concrete region, zone, room, exit, generation, and spawn-binding revisions through typed idempotent APIs with Draft aggregate epochs, typed generation scope enums, scope mutation policies, reference validation, and publish-digest participation instead of growing opaque JSON or ad hoc editor write paths; the first real caller path, spawn-binding validation, digest participation, typed scope contracts, and first `REPLACE_SCOPE` / `SEED_APPEND_ONLY` enforcement are now live, but broader scope-targeted generation payloads and broader caller coverage still remain.
5. [09.1 Realm Catalog and Admission-Pointer Routing](./09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md)
   Remaining: keep broader reconnect/cutover consumers on the now-live persisted Game Session admission-pointer authority now that operator-facing prepare/read/cutover/audit tooling, retry-safe cutover execution, gateway-routed operator access, and first-party stale-pointer proof are in place.
6. [09.3 Realm-Scoped Character and Playable State Policy](./09.3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice.md)
   Remaining: continue the now-live scope-aware roster plus inventory/equipment alignment into progression, resources, loadout, and later gameplay-state families without reintroducing tenant-wide shortcuts.
7. [02.1.6 Global Account and Tenant Authorization Convergence](./02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md)
   Remaining: build on the now-live account-identity-first player bootstrap, canonical `tenantAdmin` shared-auth model, account-owned lifecycle enforcement, and non-public realm grant substrate by reconciling the remaining docs/runtime reads.
8. [02.18.8 Tick Batch and Effect Ledger Hardening](./02.18.8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice.md)
   Remaining: build on the now-live durable batch/effect substrate, stale-fence batch drain, and post-drain execution seam now serving movement plus first item/equipment/container mutations by broadening durable execution truth into later command families and domain guards.
9. [02.18.9 Region Epoch, Fencing, and Runtime Ownership](./02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md)
   Remaining: the durable owner row, pause/resume epoch bumps, stale-fence batch rejection, stale post-drain effect requeue, last-committed-batch pointer, and bounded ownership-status query are now live at the current game-instance boundary; the next gap is carrying that model into true region ownership and later downstream/domain-specific effect families.
10. [02.18.10 Effect Idempotency and Replay Guards](./02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md)
   Remaining: the first ledger-side `effectKey`, deterministic `effectId`, movement-backed replay/no-op seam, Game Session communication/activity replay guard, Social Groups communication replay guard, Entity Management item mutation response replay guard, transfer-audit `effectId` plus session correlation, and first apply/replay metrics are now live; the next gap is later domain guard consumers.
11. [02.18.11 Migrate Live Gameplay Commands Onto the Durable Execution Path](./02.18.11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice.md)
   Remaining: movement, `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, `REMOVE`, `SAY`, `WHISPER`, `TELL`, and `AFK` are now migrated durable command families with Game Session-owned replay guards plus the first downstream Entity Management and Social Groups replay guards; the next gap is any later state-changing command family and its owning service guard.
12. [02.15.8 Environment Preflight and Secret-Binding Convergence](./02.15.8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice.md)
   Remaining: build on the now-live JWT secret-path startup, mounted JWKS serving, base Kubernetes JWT/JWKS mounts, preview-unique JWT/JWKS rendering, expected-binding manifest schema expansion, `expectedBindingsRef` report output, full required preflight policy-ID emission, executable production/hobby traffic-open backup gates, and report contract tests by tightening the first-pass expected-binding validation against real environment evidence as it becomes available.

### 2. Active architecture follow-through

These are already partly real in code and should continue after the immediate runtime fixes.

1. [08 Game Design Publishing and Runtime Activation](./08-task-list-game-design-publishing-and-runtime-activation-vertical-slice.md)
   Remaining: the family now has real publish-attestation, asset lifecycle, typed launch-preflight outcomes, approved remap-set control-plane substrate, cutover-compatibility preflight, persisted `PrepareVersionUpgrade` record, retry-safe prepared cutover execution, asset-proof validation, version-state, activation substrate, and stricter participant guardrails including current Entity `S1` / `S2` compatibility; the review-spawned `08.5` child now captures the missing canonical World design mutation surface before editor/generation work grows one-off write paths, while the rest of the family continues through more digest coverage, broader purge/deletion truth, broader cutover orchestration, exact target-template validation, and later runtime world-state families.
2. [09 Multi-Tenancy, Realm Routing, and Runtime Boundaries](./09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice.md)
   Remaining: the family now has canonical routing, public-production membership creation, bootstrap/connect-token alignment, scope-aware roster policy, operator-facing admission-pointer/cutover tooling, and gateway-routed operator access; the next honest work is broader admission-pointer consumers plus progression/resources/loadout follow-through.
3. [02.13.8 Built-In Command Registry and Dispatch Rollout](./02.13.8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice.md)
   Remaining: keep future built-in and authored command growth on the provider-backed registry plus family-handler seam without regressing into central interpreter branching.
4. [02.13.7 Action Classification and Activity Semantics](./02.13.7-task-list-action-classification-and-activity-semantics-vertical-slice.md)
   Remaining: built-in and first-pass authored actions now carry bounded optional tags/facets too; the next follow-through is real scripting/policy consumers of those tags rather than metadata-only attachment.
5. [02.13.9 Authored Action Definition and Execution Model](./02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md)
   Remaining: build on the now-live typed config-backed authored-action record, shared `commandId` registry seam, first non-built-in provider, authored `HELP` discovery, and fail-fast unsupported-metadata validation by adding richer targeting/cost/cooldown/effect semantics.
6. [10.1 Script Event Ingress and Handler Resolution](./10.1-task-list-script-event-ingress-and-handler-resolution-vertical-slice.md)
   Remaining: build on the now-live event-scope `TriggerScriptEvent` admission, ingress audit, built-in registry enforcement/read APIs, snapshot-token validation, durable script event bindings, first `script_work_items` outbox materialization, handler-scoped `script_event_audit` rows, real pending-work cancellation, first Game Session `onCommand` producer, and pending-work claiming by adding DSL execution, downstream tick handoff, and broader producer coverage.
7. [02.1.3 Session Activity and WHO Presence](./02.1.3-task-list-session-activity-and-who-presence-vertical-slice.md)
   Remaining: grow from the current bounded `WHO` plus explicit/auto-AFK and canonical lifecycle substrate into the fuller activity model when that work becomes active.
8. [02.1.4 Cross-Game Social Presence and Friend Activity](./02.1.4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice.md)
   Remaining: grow the first account-scoped friend presence seam, now consumed by REST, gRPC, and gameplay `FRIENDS`, into later social consumers and later privacy refinement without reopening `WHO`.
9. [06 Task List Inventory, Containers, Equipment](./06-task-list-inventory-containers-equipment-vertical-slice.md)
   Remaining: holder-transfer safety, canonical transfer-audit persistence with attested session/effect correlation, Game Session -> Game Logic -> Entity Management item command routing, game-configured equipment slot/body-layout validation, and WebSocket plus Telnet cross-service room-ground/equipment happy/failure paths are now live; the main remaining work is the `06.3` follow-through and later authored stackability depth.
10. [06.3.1 Stable Item Instance Visible Ref Allocation](./06.3.1-task-list-item-instance-visible-ref-allocation-vertical-slice.md)
   Remaining: decide whether and where ordinary prose views ever expose refs beyond management surfaces.
11. [06.3.2 Authored Stackability and Fungibility](./06.3.2-task-list-authored-stackability-and-fungibility-vertical-slice.md)
   Remaining: build on the now-live authored `stackVariantKey` plus runtime `stackFamilyKey` substrate with richer authored family sources; explicit stack-family selector UX for ambiguous holder-local families is now live.
12. [02.18 Service Boundary and Audit Hardening](./02.18-task-list-service-boundary-and-audit-hardening-vertical-slice.md)
   Remaining: keep `02.18.6` at operator-proof level and continue any later hardening only if real-load evidence justifies it.
13. [02.18.1 Audit Log and Moderation Separation](./02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md)
   Remaining: keep future callers on the dedicated log-event path; the current account/logging-admin separation is now in place and covered.

### 3. Design settled enough, but not started or only placeholder-level

These are not broad audit topics anymore; they are real future slices with known direction.

1. [10 Scripting, Automation, and Runtime Orchestration](./10-task-list-scripting-automation-and-runtime-orchestration-vertical-slice.md)
   Remaining: the scripting domain now has a canonical family instead of fragmented indirect coverage; `10.1` through `10.5` are the bounded runtime/control-plane cuts, while design-time publication boundaries remain intentionally anchored in `08.4`.
2. [02.1.1 Email OTP and Text Auth Options](./02.1.1-task-list-email-otp-and-text-auth-options-vertical-slice.md)
3. [02.1.5 Admin and God Capability and Visibility](./02.1.5-task-list-admin-god-capability-and-visibility-vertical-slice.md)
4. [02.1.4.1 Account Versus Character Social Scope](./02.1.4.1-task-list-account-vs-character-social-scope-vertical-slice.md)
5. [02.1.5.1 Hidden Staff Modes and Capability Bundles](./02.1.5.1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice.md)
6. [02.13.10 Structured Transcript and Replay End State](./02.13.10-task-list-structured-transcript-and-replay-end-state-vertical-slice.md)
7. [02.13.10.1 Structured Transcript Persistence and Replay Storage](./02.13.10.1-task-list-structured-transcript-persistence-and-replay-storage-vertical-slice.md)
8. [02.13.11 Shared Time, Duration, and Scheduler Semantics](./02.13.11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice.md)
9. [07.4 Unified Actor Model](./07.4-task-list-unified-actor-model-vertical-slice.md)
10. [07 Entity Stats and Conditions](./07-task-list-entity-stats-and-conditions-vertical-slice.md)
11. [07.1 Shared Effect Engine](./07.1-task-list-shared-effect-engine-vertical-slice.md)
12. [07.2 Equipment and Action-State Contributions](./07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md)
13. [07.3 Damage and Mitigation Resolution](./07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md)

### 4. Lower-priority platform/settings expansion

These are still valid, but they are behind the current gameplay/runtime slices.

1. [06.1 Inventory and Equipment Settings](./06.1-task-list-inventory-and-equipment-settings-vertical-slice.md)
2. [02.9.1 Settings Presets and Operator Baselines](./02.9.1-task-list-settings-presets-and-operator-baselines-vertical-slice.md)

### 5. Manual QA / operator-proof tails

These are not major design problems, but they are not fully closed.

1. [02.3 Reconnect and Session Recovery](./02.3-task-list-reconnect-and-session-recovery-vertical-slice.md)
2. [02.4 First-Party Reconnect Parity](./02.4-task-list-first-party-reconnect-parity-vertical-slice.md)
3. [02.5 Non-Edge Failover Invisibility](./02.5-task-list-non-edge-failover-invisibility-vertical-slice.md)
4. [02.8 Game Logic Restart Invisibility](./02.8-task-list-game-logic-restart-invisibility-vertical-slice.md)
5. [02.18.6 Tick Scheduler Backpressure and Merge Semantics](./02.18.6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice.md)
   Remaining: operator-proof the chosen alert thresholds in preview/prod-like runs.
6. [02.14.4 Metrics Cardinality and Label Policy Hardening](./02.14.4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice.md)
   Remaining: only opportunistic audit tail work and later policy wording cleanup.
7. [02.14.5 Player-Experience Canary and Deadman Smoke](./02.14.5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice.md)
   Remaining: implement the prod-like observability smoke proof for player-flow canaries, independent entry-path blackbox probes, external deadman heartbeat, non-production failure injection, and retained traffic-open evidence.

## Operator-Proof / Audit Tails

These are still useful, but they are not the next best feature/code-shape slices.

1. `02.18.6` operator-proof the chosen scheduler thresholds
2. `02.14.4` opportunistic metrics-cardinality tail only
3. `02.14.5` prod-like canary/deadman smoke proof
