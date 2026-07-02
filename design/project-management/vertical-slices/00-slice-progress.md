# Slice Progress Queue

This file is the short ordered view of what is still outstanding.

It intentionally omits completed slices and uses the individual slice docs as the canonical detail source.

## Suggested Order

### 1. Immediate implementation queue

These are narrow enough to implement now and unblock the current runtime/platform direction.

1. [02.15.8 Environment Preflight and Secret-Binding Convergence](./02.15.8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice.md)
   Remaining: build on the now-live JWT secret-path startup, mounted JWKS serving, base Kubernetes JWT/JWKS mounts, preview-unique JWT/JWKS rendering, expected-binding manifest schema expansion, `expectedBindingsRef` report output, full required preflight policy-ID emission, executable production/hobby traffic-open backup gates, report contract tests, and now cross-manifest external-binding isolation proof by tightening the expected-binding validation further against real environment evidence as it becomes available. The next bounded child follow-through is [`02.15.8.2`](./02.15.8.2-task-list-service-discovery-override-preflight-enforcement-vertical-slice.md) for exact `allowedOverrides` enforcement on rendered service-discovery overrides.

### 2. Active architecture follow-through

These are already partly real in code and should continue after the immediate runtime fixes.

1. [08 Game Design Publishing and Runtime Activation](./08-task-list-game-design-publishing-and-runtime-activation-vertical-slice.md)
   Remaining: the family now has real publish-attestation, asset lifecycle, typed launch-preflight outcomes, approved remap-set control-plane substrate, cutover-compatibility preflight, bounded Logging & Admin cutover-compatibility readback, persisted `PrepareVersionUpgrade` record, retry-safe prepared cutover execution, asset-proof validation, version-state, activation substrate, and stricter participant guardrails including current Entity `S1` / `S2` compatibility; `08.4` and `08.5` are now complete at their current canonical seams, while the rest of the family continues through broader digest coverage, creator/editor UX adoption, broader purge/deletion truth, exact target-template validation, and later runtime world-state families.
2. [09 Multi-Tenancy, Realm Routing, and Runtime Boundaries](./09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice.md)
   Remaining: the family now has canonical routing, explicit public-production pointer metadata, public-production membership creation, bootstrap/connect-token alignment, scope-aware roster policy, first scoped friend/progression/activity mutation guards, scoped scripting runtime-state propagation, scope-aware account/friend presence reads, operator-facing admission-pointer/cutover tooling, and gateway-routed operator access; `09.1`, `09.3`, and `09.4` are complete at their current boundaries, and the next honest work is later loadout/ability/resource-table and broader state-family follow-through as those families land.
3. [02.13.8 Built-In Command Registry and Dispatch Rollout](./02.13.8-task-list-built-in-command-registry-and-dispatch-rollout-vertical-slice.md)
   Remaining: keep future built-in and authored command growth on the provider-backed registry plus family-handler seam without regressing into central interpreter branching, now that bounded Logging & Admin alias-validation readback is also live on the same canonical registry authority.
4. [02.13.7 Action Classification and Activity Semantics](./02.13.7-task-list-action-classification-and-activity-semantics-vertical-slice.md)
   Remaining: built-in and first-pass authored actions now carry bounded optional tags/facets too; the next follow-through is real scripting/policy consumers of those tags rather than metadata-only attachment.
5. [02.13.9 Authored Action Definition and Execution Model](./02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md)
   Remaining: build on the now-live typed config-backed authored-action record, shared `commandId` registry seam, first non-built-in provider, authored `HELP` discovery, and fail-fast unsupported-metadata validation by adding richer targeting/cost/cooldown/effect semantics.
6. [02.1.3 Session Activity and WHO Presence](./02.1.3-task-list-session-activity-and-who-presence-vertical-slice.md)
   Remaining: the bounded gameplay-presence, AFK/activity, recent-presence disposition, and first current-game-instance `WHO` substrate are now live; only later activity consumers and richer visibility-policy follow-through remain when that work becomes active.
7. [02.1.4 Cross-Game Social Presence and Friend Activity](./02.1.4-task-list-cross-game-social-presence-and-friend-activity-vertical-slice.md)
   Remaining: grow the first account-scoped friend presence seam, now consumed by REST, gRPC, gameplay `FRIENDS`, first-party-web structured friend roster/detail/summary/policy plus structured mutation-result payloads with fail-closed gameplay grammar, the now-canonical gameplay plus REST/gRPC Social Groups visibility-policy read/write seam, and one shared Account-profile JSON helper for that visibility-policy substrate across live clients plus shared runtime proof, into later social consumers and later privacy refinement without reopening `WHO`.
8. [09.3.1 Playable-State Family Namespace Follow-Through](./09.3.1-task-list-playable-state-family-namespace-follow-through-vertical-slice.md)
   Remaining: carry the `09.3` namespace contract into the next real gameplay-state family set and keep shared-state/isolated semantics aligned on `{tenantId, gameInstanceId, playableStateScope}`.
9. [02.1.4.1 Account Versus Character Social Scope](./02.1.4.1-task-list-account-vs-character-social-scope-vertical-slice.md)
   Remaining: harden mixed scope behavior beyond the first public-facing friend presence seam and keep later social consumers aligned with the same scope model.
10. [02.1.4.2 Social Privacy Policy Propagation and Consumer Hardening](./02.1.4.2-task-list-social-privacy-policy-propagation-and-consumer-hardening-vertical-slice.md)
   Remaining: propagate visibility-policy enforcement from Social Groups into next social consumers without introducing local ad hoc suppression paths.
11. [02.13.11 Shared Time, Duration, and Scheduler Semantics](./02.13.11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice.md)
   Remaining: the cross-service proto naming guard is in place; broader runtime timing/scheduling adoption remains ongoing as timed systems land.
12. [02.15.7 Gateway Edge Allowlist and Management Contract Convergence](./02.15.7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice.md)
   Remaining: keep explicit public-route inventory and owning-service enforcement synchronized as later public routes are added.
13. [02.1.7 Auth, Session, and Routing Guardrail Follow-Through](./02.1.7-task-list-auth-session-and-routing-guardrail-follow-through-vertical-slice.md)
   Remaining: use this as the single growing queue for bounded auth/session/routing hardening seams where the architecture is already decided and only fail-closed follow-through remains, especially malformed token/claim rejection, non-positive or blank routing identity rejection, replay payload mismatch guards, and application-level gRPC error normalization with focused proof. `02.1.7.1` is complete; the next bounded child follow-through docs are [`02.1.7.2`](./02.1.7.2-task-list-malformed-jwt-and-claim-shape-parity-vertical-slice.md) for malformed JWT and claim-shape parity and [`02.1.7.3`](./02.1.7.3-task-list-positive-identity-and-routing-bundle-guardrails-vertical-slice.md) for non-positive identity and partial routing-bundle fail-closed parity.
14. [02.18.10.1 Authored-Action and Resource Effect Replay Guards](./02.18.10.1-task-list-authored-action-and-resource-effect-replay-guards-vertical-slice.md)
   Remaining: the first richer actor-state follow-through is now live through replay-guarded `ApplyActorCondition`; the honest remaining tail is future authored-action or broader actor/resource families once they own real downstream mutations instead of command-local stubs.
15. [02.18.10 Effect Idempotency and Replay Guards](./02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md)
   Remaining: the first ledger-side `effectKey`, deterministic `effectId`, movement-backed replay/no-op seam, Game Session communication/activity replay guard, Social Groups communication replay guard, Entity Management item plus actor-condition mutation response replay guard, transfer-audit `effectId` plus session correlation, and first apply/replay metrics are now live; the next honest gap is later domain-specific effect guards as new owning mutation families land, not another forced proving-ground batch in the current built-in command set.
16. [06 Task List Inventory, Containers, Equipment](./06-task-list-inventory-containers-equipment-vertical-slice.md)
   Remaining: holder-transfer safety, canonical transfer-audit persistence with attested session/effect correlation, Game Session -> Game Logic -> Entity Management item command routing, game-configured equipment slot/body-layout validation, and WebSocket plus Telnet cross-service room-ground/equipment happy/failure paths are now live; the main remaining work is the `06.3` follow-through and later authored stackability depth.
17. [06.3.1 Stable Item Instance Visible Ref Allocation](./06.3.1-task-list-item-instance-visible-ref-allocation-vertical-slice.md)
   Remaining: build on the now-live stable visible-ref substrate across inventory/equipment/container/room-ground and `HERE`-style targeting views for non-player items and entities; players remain the special case and are still identified by character name rather than a generated visible ref.
18. [06.3.2 Authored Stackability and Fungibility](./06.3.2-task-list-authored-stackability-and-fungibility-vertical-slice.md)
   Remaining: build on the now-live authored `stackVariantKey` plus runtime `stackFamilyKey` substrate with richer authored family sources; explicit stack-family selector UX for ambiguous holder-local families is now live.
19. [02.18 Service Boundary and Audit Hardening](./02.18-task-list-service-boundary-and-audit-hardening-vertical-slice.md)
   Remaining: continue any later hardening only if real-load evidence justifies it; the original audit-family scheduler-pressure/operator-proof tail in `02.18.6` is now closed at the current boundary.
20. [02.18.1 Audit Log and Moderation Separation](./02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md)
   Remaining: keep future callers on the dedicated log-event path; the current account/logging-admin separation is now in place and covered.

### 3. Design settled enough, but not started or only placeholder-level

These are not broad audit topics anymore; they are real future slices with known direction.

1. [02.1.1 Email OTP and Text Auth Options](./02.1.1-task-list-email-otp-and-text-auth-options-vertical-slice.md)
2. [02.1.5 Admin and God Capability and Visibility](./02.1.5-task-list-admin-god-capability-and-visibility-vertical-slice.md)
3. [02.1.5.1 Hidden Staff Modes and Capability Bundles](./02.1.5.1-task-list-hidden-staff-modes-and-capability-bundles-vertical-slice.md)
4. [02.13.10 Structured Transcript and Replay End State](./02.13.10-task-list-structured-transcript-and-replay-end-state-vertical-slice.md)
   Remaining: the hot reconnect buffer now stores structured `PlayerOutput` replay metadata alongside classic rendered protocol text for new entries and keeps text-only legacy entries readable; the narrower storage-model follow-through is now locked at the design boundary, so the remaining work is bounded durable transcript history on top of that explicit transcript-entry and retention-class contract.
5. [07.4 Unified Actor Model](./07.4-task-list-unified-actor-model-vertical-slice.md)
6. [07 Entity Stats and Conditions](./07-task-list-entity-stats-and-conditions-vertical-slice.md)
   Remaining: Entity Management now has the first gameplay-attested actor-state read substrate for baseline resources plus persisted resource/condition rows; authored definitions, shared effect evaluation, mutation/expiry, equipment/action contributions, and damage/mitigation remain future work.
7. [07.1 Shared Effect Engine](./07.1-task-list-shared-effect-engine-vertical-slice.md)
   Remaining: the first typed in-process effect evaluation seam now exists for additive/multiplicative/clamp modifiers, granted flags/conditions, scopes, and provenance, and active condition payloads are wired through it for actor-state reads; authored definitions plus equipment/action-state producer wiring remain future work.
8. [07.2 Equipment and Action-State Contributions](./07.2-task-list-equipment-and-action-state-contributions-vertical-slice.md)
   Remaining: equipped item templates can now contribute effect payload modifiers through actor-state reads, and an internal condition/action-state apply/expire seam exists; player command wiring, scheduled expiry execution, and richer scoped combat-facing consumption remain future work.
9. [07.3 Damage and Mitigation Resolution](./07.3-task-list-damage-and-mitigation-resolution-vertical-slice.md)

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
