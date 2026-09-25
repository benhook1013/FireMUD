# Whole-Corpus Authority Review Plan

This is the maintained scope and method for the post-ADR corpus review. The [alignment index](./README.md#corpus-section-programme-status) is the **only live section-status table**. This plan defines section boundaries and starting source manifests; neither file gives technical authority. Canonical product and architecture documents own target behavior, and implementation trackers own implementation/proof state.

## Purpose and sequence

The capability allocation, human-led adversarial ADR review, selective accepted-decision application, and point-in-time implementation reconciliation preceded this effort. This review now checks the *whole* product and architecture corpus for missing or conflicting contracts, duplicate normative owners, broken owner/consumer handoffs, and misleading implementation or proof claims. It preserves useful local consequences while keeping one canonical owner. It is not another ADR decision round and does not imply that every target capability must be implemented before the corpus can be aligned.

After the section passes, reconcile affected live trackers where target, implementation, proof, or gap status changed. Then run one corpus-wide owner/secondary/tracker consistency check and the documentation validation gates before claiming the post-ADR alignment effort complete. Capability implementation continues separately under the domain trackers.

## Section boundaries

The 23 original family sections remain the top-level corpus map, but 5B is now pre-split into three semantic review subunits: 5B-Schema, 5B-Publication, and 5B-Retention. This produces 25 review units while preserving one Shared Runtime family and one 5B source union. The split responds to repeated implementation expansion across migrations, services, runtime code, and proof; it is a review-scope split, not a map of implementation PRs. The 21 recovered exact manifests from the pre-split plan each contained 10–18 existing source paths before heading-scoped overlap was counted. This restart resets **review coverage**, including previously completed 5A; it does not undo merged code or accepted ADRs. The two missing manifests, 7A and 7B, are supplied below. These numbered lists are starting anchors, not an exhaustive inventory of allocated sources. At section start, use the current [design allocation](./design-capability-allocation.md) to identify all primary sources and separately record material secondary handoffs. A source can be read in more than one section for a specific heading-scoped handoff; that does not create two normative owners.

| Family | Sections | Boundary |
| --- | --- | --- |
| Access and experience | 1A–1D | Identity and entitlement; session admission; commands and presentation; social communication and safety |
| Gameplay | 2A–2C | Tick/region authority; mutation/spatial authority; entities, effects and economy |
| Authoring | 3A–3C | Content packaging; effective settings; release and activation |
| Automation | 4A–4B | Script ingress/execution; scheduling, quotas and reload |
| Shared runtime | 5A; 5B-Schema, 5B-Publication, 5B-Retention; 5C–5D | Shared primitives; schema and durable-state evolution; durable publication/readiness; retention and cleanup; Redis; transaction, replay and workflow patterns |
| Edge and delivery | 6A–6D | Routes; protocol transport; operator ingress; environments and deployment |
| Observability and proof | 7A–7C | Logs/metrics/tracing/SLOs; verification and recovery evidence; incident/operational proof |

**5B decision before restart:** pre-split the 18-source 5B corpus union into three semantic review subunits: **5B-Schema** for SQL/schema/identifier evolution, **5B-Publication** for durable version/publication/readiness state, and **5B-Retention** for retention, cleanup, and recovery-safe durable state. Each subunit has its own heading-scoped manifest, fresh unrestricted coverage, finding adjudication, and taper. A mandatory whole-5B synthesis then checks the shared identities, migration gates, publication terminality, retention horizons, and owner handoffs across all three subunits. The synthesis is a review gate, not a fourth implementation PR. Findings that cross subunit boundaries return to the owning subunit; findings owned by 5C, 5D, 6D, 7B, 7C, or another product/domain section remain explicit handoffs. The four implementation PRs are preserved evidence and are not section boundaries: their scopes combine or omit portions of these subunits and include adjacent-family work. Reconcile and assign their remaining work when 5B resumes after 5A; do not count their historical reviews as new subunit coverage.

## Review and tracking method

1. Start with 5A. Before each section or 5B subunit, refresh its starting manifest against current allocation, canonical owner links, ADR rationale, consumer handoffs, and live trackers. Expand each ordinary section's numbered source list to include every allocated primary source within its boundary; for 5B, retain one 18-source union and record each subunit's selected heading-scoped anchors, overlaps, and explicit handoffs without requiring every subunit to repeat all 18 paths. Every numbered source path below existed when this plan was rebuilt, but inclusion is a review input, not an assertion that its wording remains correct.
2. Run a fresh broad pass over the **entire section or subunit**, including unchanged sources and material code/proof consequences. A focused check can answer a finding but does not replace broad discovery. Normalize findings by underlying issue; apply coherent fixes, route substantial implementation work to its owner, and retain honest tracker gaps. For 5B, the three subunit passes are independent coverage obligations; implementation PR topology must not substitute for them.
3. Repeat broad passes on the corrected state while they find useful work. Each 5B subunit needs its own coverage account, finding dispositions, and evidence-based taper. After the subunits reach their local terminals, run the mandatory whole-5B synthesis over the 18-source union and all material overlaps. Any useful synthesis finding reopens the owning subunit and requires a new synthesis pass after correction. No fixed pass count or demand for a review of every fix SHA overrides that judgment. PR-wide CodeRabbit and CI assess changed code and merge risk; they do not prove unchanged corpus coverage.
4. Update **only the alignment index** for section/subunit status, main PR, completion evidence, and routed obligations. The index keeps the three 5B subunit rows under the two-document tracking model; record their evidence and routed work in the planned **Evidence / routed work** column. Record the whole-5B synthesis as a non-row evidence line associated with those three rows, never as an aggregate 5B status row. The index must not become a PR-shaped ledger. A split implementation PR is linked from the relevant subunit evidence rather than creating a PR section. Keep detailed finding evidence with the relevant review/PR artifacts, not a second live section-status ledger. Any future semantic boundary change must update both maintained documents before assigning new subunits or PRs.
5. A section is complete only when its refreshed source list and material handoffs have been assessed, valid findings are fixed or explicitly owned elsewhere, and the current review has tapered. For 5B, completion additionally requires all three subunit terminals plus a tapered whole-5B synthesis. Record each subunit's reviewed/allocated source count, evidence, and routed work in the index; record synthesis evidence on its non-row line. At final closeout, compare the union of the refreshed section and subunit lists with the [allocation](./design-capability-allocation.md): every allocated source must be reviewed in an owning section, explicitly handed off by heading, or listed as an exemption with a reason. Merging one PR alone does not establish section or subunit completion.

When Overseer closes broad discovery for a section by explicit judgment before taper, record that as a discovery stop, not section completion or merge readiness. Before handing off the section, reconcile each accepted finding and relevant tracker gap against current behavior: implement unsafe exposed paths in their owning boundary, or assign substantial pre-v1 work to a concrete owner and successor with dependency and activation gate. A tracker link alone does not remediate a live defect. Record deliberately deferred features with their containment, reason and revisit trigger; keep unexecuted PostgreSQL, cross-service and deployment proof attached to the applicable readiness gate. Preserve the difference between whole-corpus coverage, implemented capability and exercised proof in the index and owning trackers.

## Starting manifests

These recovered manifests are starting review prompts, not frozen inventories or old completion claims. Refresh each when its section starts. Historical statements about what is currently implemented or unresolved must be verified before use. Numbered paths below are review inputs; heading-scoped consumers and adjacent sections remain handoffs rather than duplicate owners.

### 1A

Unit 1A's substantive boundary is the following 29 identity, membership, entitlement, billing, and hosted-terms sources:

1. `design/architecture/service-responsibility-matrix.md`
2. `design/architecture/system-architecture-overview.md`
3. `design/architecture/system-architecture-authentication.md`
4. `design/architecture/system-architecture-multi-tenancy.md`
5. `design/architecture/microservices/account-service/api-contracts.md`
6. `design/architecture/microservices/account-service/runtime-and-data.md`
7. `design/architecture/microservices/account-service/subscription-management.md`
8. `design/architecture/microservices/account-service/stripe-integration.md`
9. `design/product/requirements.md`
10. `design/project-management/implementation-tracking/player-access-and-session.md`
11. `design/architecture/decisions/adr-0021-staged-player-authentication-and-gameplay-binding.md`
12. `design/architecture/decisions/adr-0022-account-authority-and-gameplay-session-ownership.md`
13. `design/architecture/decisions/adr-0025-explicit-open-enrollment-membership.md`
14. `design/architecture/decisions/adr-0026-global-roles-do-not-grant-gameplay-authority.md`
15. `design/architecture/decisions/adr-0028-differentiated-entitlement-freshness.md`
16. `design/architecture/decisions/adr-0030-risk-based-active-session-revocation.md`
17. `design/architecture/decisions/adr-0040-account-global-control-login-and-explicit-tenant-selection.md`
18. `design/architecture/decisions/adr-0041-shared-tenant-infrastructure-with-full-environment-isolation-gate.md`
19. `design/architecture/decisions/adr-0042-global-account-and-tenant-scoped-game-relationships.md`
20. `design/architecture/decisions/adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md`
21. `design/architecture/decisions/adr-0044-account-owned-payment-instruments-with-explicit-subscription-binding.md`
22. `design/architecture/decisions/adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md`
23. `design/architecture/decisions/adr-0049-optional-provider-specific-external-identity-linking.md`
24. `design/architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md`
25. `design/architecture/decisions/adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md`
26. `design/architecture/decisions/adr-0179-firemud-managed-creator-commerce-boundary.md`
27. `design/architecture/decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md`
28. `design/architecture/decisions/adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md`
29. `design/architecture/microservices/account-service/README.md`

Review Account's sole authority for global identity, credentials, explicit membership, entitlement, Creator Party and terms evidence; account-global versus tenant-scoped records; public versus private/playtest access; strict and continuity entitlement freshness; authentication path separation; role versus gameplay authority; hosted creator mutation currentness; changed-terms decline and prior-rights continuity; and payment evidence versus runtime entitlement. Unit 1A consumes 5A token/authority primitives and hands gameplay binding to 1B and operator reference execution to 6C. Refresh for material 5A, 6C, 1B, 5B/5D, 3A, or billing/provider changes. Repository licence/terms files remain legal-policy handoffs rather than technical authority sources.

### 1B

Unit 1B's substantive boundary is the following 16 admission, binding, continuity, and reconnect sources:

1. `design/architecture/system-architecture-authentication.md`
2. `design/architecture/system-architecture-reconnection.md`
3. `design/architecture/system-architecture-session-behavior.md`
4. `design/architecture/microservices/game-session-service/api-contracts.md`
5. `design/architecture/microservices/game-session-service/protocols.md`
6. `design/architecture/microservices/game-session-service/runtime-and-data.md`
7. `design/project-management/implementation-tracking/player-access-and-session.md`
8. `design/project-management/implementation-tracking/realm-routing-and-playable-state.md`
9. `design/product/requirements.md`
10. `design/architecture/decisions/adr-0019-separate-active-session-resume-and-transcript-lifetimes.md`
11. `design/architecture/decisions/adr-0021-staged-player-authentication-and-gameplay-binding.md`
12. `design/architecture/decisions/adr-0022-account-authority-and-gameplay-session-ownership.md`
13. `design/architecture/decisions/adr-0027-single-realm-admission-target.md`
14. `design/architecture/decisions/adr-0132-namespace-scoped-single-character-controller.md`
15. `design/architecture/decisions/adr-0133-fresh-edge-reconnect-without-client-input-replay.md`
16. `design/architecture/decisions/adr-0134-bounded-durable-semantic-reconnect-context.md`

Review LOGIN versus PLAY, explicit JOIN and fresh Account evidence, public versus private/playtest admission, realm/pointer/catalog/namespace selection, roster discovery versus independent actor validation, namespace-scoped controller generation and takeover, liveness versus continuity/resume/physical TTL, fresh-edge versus retained-edge recovery, reauthentication and revocation, bounded semantic context, fresh LOOK, and disconnect deduplication. Unit 1B consumes 1A Account authority and 5A shared token/identity primitives; 1C owns rendering, 6A/6B own edge/transport carriage, and gameplay/tick owners remain outside this unit. Refresh when those consumed admission, pointer, transport, Redis, or replay contracts change materially.

Current Unit 1B execution first checked design authority and corrected exposed Game Session behavior on the cumulative Account tail; the separate typed Account reader, Entity namespace/entry-policy, signed first-party connect-context handoff, and durable reconnect-controller successors remain explicit activation dependencies rather than inferred from a green unit suite. Three earlier independent corrected-state reviews assessed the whole 16-source unit, unchanged sources, material code/proof, and cross-owner handoffs for missing or conflicting contracts, duplicated normative owners, broken handoffs, and misleading completion claims. Each found a material local correction. The superseding cadence then completed two fresh serial whole-unit Luna cycles after known owner corrections and the separate Account/edge email-link child. Cycle 1 accepted current-status and post-logout corrections; Cycle 2 found one new local cross-source status conflict and repeated three already-owned activation obligations. That local conflict was corrected, validated and published, and the mandated two-cycle report boundary is reached without claiming a dry cycle, discovery exit, merge readiness or live activation. Section exit classifies every surviving root as an exposed correction, ordered pre-v1 successor with containment, deliberate safe deferral with activation gate, or exact missing proof.

### 1C

Unit 1C's substantive boundary is the following 14 command, output, and presentation sources:

1. `design/architecture/service-responsibility-matrix.md`
2. `design/architecture/system-architecture-player-command-model.md`
3. `design/architecture/system-architecture-input-output-and-presentation.md`
4. `design/architecture/system-architecture-frontend.md`
5. `design/architecture/microservices/game-session-service/README.md`
6. `design/architecture/microservices/game-session-service/api-contracts.md`
7. `design/architecture/decisions/adr-0016-canonical-gameplay-command-status-lifecycle.md`
8. `design/architecture/decisions/adr-0062-layered-gameplay-command-delivery-semantics.md`
9. `design/architecture/decisions/adr-0135-compact-versioned-player-output-and-late-rendering.md`
10. `design/architecture/decisions/adr-0136-future-compatible-localization-boundary.md`
11. `design/architecture/decisions/adr-0144-stateless-first-party-frontend-application-boundary.md`
12. `design/architecture/decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md`
13. `design/project-management/implementation-tracking/player-experience-commands-and-communication.md`
14. `design/product/requirements.md`

Review the single pinned command registry/admission boundary, typed semantic outcomes, Game Session rendering ownership, distinct output kinds, structured versus deterministic text parity, schema compatibility, command acknowledgement/terminal status, command history versus semantic reconnect context, causal LOOK reconstruction, effective settings, deterministic localization fallback, the stateless frontend boundary, and universal plain-text playability. Unit 1C consumes 1A/1B client-visible outcomes, 2A/2B command and causal-read completion, 3B settings, 3A/3C registry identity, 5A/5B/5D shared primitives and durability, 1D communication outcomes, and 6A/6B carriage. Refresh only affected seams when those contracts change.

### 1D

Unit 1D's substantive boundary is the following 16 social, communication, moderation, and player/operator UX sources:

1. `design/architecture/microservices/social-groups-service/README.md`
2. `design/architecture/microservices/social-groups-service/api-contracts.md`
3. `design/architecture/microservices/social-groups-service/runtime-and-data.md`
4. `design/architecture/microservices/game-session-service/protocols.md`
5. `design/architecture/microservices/logging-admin-service/moderation-policies.md`
6. `design/architecture/decisions/adr-0141-fixed-safety-restriction-categories-and-independent-lifecycles.md`
7. `design/architecture/decisions/adr-0142-bounded-moderation-appeal-cases.md`
8. `design/architecture/decisions/adr-0146-owner-local-moderation-enforcement.md`
9. `design/architecture/decisions/adr-0147-explicit-communication-classes-and-owner-delivery.md`
10. `design/architecture/decisions/adr-0148-social-relationship-authority-and-entity-owned-value.md`
11. `design/architecture/decisions/adr-0149-communication-type-specific-history-and-retention.md`
12. `design/architecture/decisions/adr-0150-closed-observer-views-and-profile-scoped-shout.md`
13. `design/architecture/decisions/adr-0046-bounded-friend-presence-with-private-by-failure-redaction.md`
14. `design/product/user-journeys/players.md`
15. `design/product/user-journeys/operators.md`
16. `design/project-management/implementation-tracking/player-experience-commands-and-communication.md`

Review current compatibility moderation reads versus owner-local enforcement; authenticated sender/replay identity; fixed-category stacking, broadest-effect safe denials, notices and independent lifecycles; policy-intent versus owner-command identity; fail-closed send/participation/history enforcement; appeals; communication-class ownership; audience and closed observer views; delivery versus history acknowledgement; per-type retention/evidence/receipts; relationship/group/value ownership; and private-by-failure friend presence. Coordinate 1A Account restrictions, 1B session delivery, 1C presentation, 2B/2C gameplay/value facts, 5A/5B/5C/5D substrate, and 6C policy/operator ingress without absorbing them. Refresh only seams changed by those owners.

### 2A

Unit 2A's substantive boundary is the following 18 tick-scheduling, region-authority, owner, rationale, tracker, and product sources:

1. `design/architecture/system-architecture-ticks.md`
2. `design/architecture/system-architecture-tick-concepts-and-invariants.md`
3. `design/architecture/system-architecture-tick-execution-flows.md`
4. `design/architecture/microservices/game-session-service/README.md`
5. `design/architecture/microservices/game-session-service/api-contracts.md`
6. `design/architecture/microservices/world-management-service/README.md`
7. `design/architecture/microservices/world-management-service/api-contracts.md`
8. `design/architecture/decisions/adr-0052-redis-liveness-lease-with-durable-executor-fence.md`
9. `design/architecture/decisions/adr-0055-durable-cross-region-effects-with-static-live-topology.md`
10. `design/architecture/decisions/adr-0065-deterministic-fair-entity-tick-scheduling.md`
11. `design/architecture/decisions/adr-0066-durable-asynchronous-cross-region-result-arbitration.md`
12. `design/architecture/decisions/adr-0067-abandon-old-epoch-work-and-reschedule-with-new-lineage.md`
13. `design/architecture/decisions/adr-0070-bounded-within-tick-visibility-by-semantic-phase.md`
14. `design/architecture/decisions/adr-0071-durable-tick-commit-before-fenced-coordination-cleanup.md`
15. `design/architecture/decisions/adr-0077-durable-global-effect-fanout-and-lightweight-idle-ticks.md`
16. `design/architecture/decisions/adr-0170-fenced-command-forwarding-and-authoritative-region-transition.md`
17. `design/project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md`
18. `design/product/requirements.md`

Review target/current separation for per-region `RegionStatus` versus live instance-scoped `RuntimeOwnershipStatus`; distinguish `regionEpoch`, the opaque durable executor fence, the ephemeral Redis lease token, and entity-lock tokens; verify fair source/lane classification, durable batch/source-claim uniqueness before Redis staging, durable-first commit and exact cleanup, cross-region arbitration, old-epoch lineage, ADR 0170 forwarding, and World topology/location ownership without a competing tick scheduler. Do not infer a shared gameplay-clock API or claim ADR 0077 target empty-tick/global-fan-out behavior is implemented.

Narrow evidence anchors are the tick/lease headings in Game Session and World runtime/data, the directly related rows in the realm-routing, World, Automation, and shared-runtime trackers, the timer-progress handoff in the scripting scheduler, the recovery headings in tick failures/operations, Redis gameplay-key/reset headings, ADR 0073 timing, and the ADR 0054/0061 handoff into 2B. Spatial mutation and participant guards remain 2B; gameplay effects/economy remain 2C; SQL, Redis, and workflow implementation remain 5B-5D except at their direct tick handoffs.

There is no parcel-wide Shared Runtime gate. Refresh or pause Unit 2A only if an accepted 5A-5D correction changes a primitive it consumes: scoped identifiers/time/fence representation; ownership/status schema, tick-batch uniqueness, retention, or cleanup terminality; gameplay Redis key/reset/lease behavior; or durable-first commit, replay identity, arbitration, and reconciliation states. Implementation-drift or secondary-wording findings elsewhere do not block its later dispatch. The unresolved Weather selector is outside Unit 2A unless its eventual outcome adds a scheduler work source or changes the region aggregate; that outcome would require refreshing the affected seams before review.

### 2B

Unit 2B's substantive boundary is the following 15 spatial/mutation owner, consumer, rationale, tracker, and product sources:

1. `design/architecture/service-responsibility-matrix.md`
2. `design/architecture/system-architecture-overview.md`
3. `design/architecture/system-architecture-spatial-and-ambient-effects-catalog.md`
4. `design/architecture/microservices/world-management-service/api-contracts.md`
5. `design/architecture/microservices/world-management-service/runtime-and-data.md`
6. `design/architecture/microservices/game-logic-service/api-contracts.md`
7. `design/architecture/microservices/entity-management-service/runtime-and-data.md`
8. `design/architecture/microservices/game-session-service/runtime-and-data.md`
9. `design/architecture/decisions/adr-0054-split-spatial-authority-with-causal-read-composition.md`
10. `design/architecture/decisions/adr-0059-causal-floor-cross-service-presentation-reads.md`
11. `design/architecture/decisions/adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md`
12. `design/architecture/decisions/adr-0061-single-owner-spatial-mutations-across-split-authority.md`
13. `design/project-management/implementation-tracking/world-runtime-and-movement.md`
14. `design/project-management/implementation-tracking/gameplay-rules-entities-and-effects.md`
15. `design/product/requirements.md`

Review the World/Entity ownership split, Game Logic composition without persistence authority, MOVE versus DROP/PICKUP transaction owners, actor-lock/barrier and region/executor fences, root/child effect guards and immutable digests, presentation causal floors versus mutation preconditions, current `R-<rowId>` and scope-marker limitations, and honest current gaps for World location/occupancy, LOOK, DROP/PICKUP, and ambient mutation.

Narrow evidence anchors are the spatial headings in transactions and the identifier glossary; tick region/floor/effect headings; World creation's initial-event consequence; Game Logic runtime/cache constraints; concise Entity ownership and Game Session protocol handoffs; directly relevant player/creator journeys and ADR rationale; and focused current implementations/tests named by the World and Gameplay trackers. Unit 2A owns region/tick scheduling; 2C owns gameplay-domain effects/economy; 3A owns authored topology/defaults; 4A/4B own script/scheduler ingress; 5B-5D own persistence/Redis/workflow substrate; later units own operational and proof consequences.

The unresolved Weather selector is a scoped seam, not a blanket Unit 2B gate. Non-Weather MOVE, DROP/PICKUP, LOOK, door/hazard, ambient-ownership, and fail-closed direct-write review may proceed. Region-versus-room Weather aggregate choice, final target identity/binding, ADR0054/ADR0060/transaction/catalog alignment, Weather guards/component versions, activation seeding, scheduling/direct-handler behavior, and focused replay/reconciliation proof remain blocked. Until adjudicated, Weather remains explicitly non-mutating and Unit 2B cannot claim terminal completion for that seam.

### 2C

Unit 2C's substantive boundary is the following 15 gameplay-entity, effect, economy, owner, rationale, tracker, and product sources:

1. `design/architecture/service-responsibility-matrix.md`
2. `design/architecture/microservices/entity-management-service/api-contracts.md`
3. `design/architecture/microservices/entity-management-service/runtime-and-data.md`
4. `design/architecture/microservices/game-logic-service/api-contracts.md`
5. `design/architecture/microservices/game-logic-service/runtime-and-data.md`
6. `design/architecture/microservices/game-design-service/ability-action-tools.md`
7. `design/architecture/microservices/game-design-service/item-equipment-balancing.md`
8. `design/architecture/system-architecture-player-command-model.md`
9. `design/architecture/decisions/adr-0053-command-atomicity-by-invariant-class.md`
10. `design/architecture/decisions/adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md`
11. `design/architecture/decisions/adr-0112-typed-bounded-gameplay-effect-extension.md`
12. `design/architecture/decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md`
13. `design/architecture/decisions/adr-0148-social-relationship-authority-and-entity-owned-value.md`
14. `design/project-management/implementation-tracking/gameplay-rules-entities-and-effects.md`
15. `design/product/requirements.md`

Review the separation between Entity persistence/mutation, Game Logic bounded resolution, Game Design frozen declarations, and Game Session durable admission/replay; actor/character/namespace/scope/instance identity; the transitional `ApplyActorCondition` identity; continuous versus instant effects; explicit costs/cooldowns/lifecycle outcomes; required/optional effects and command atomicity; generated-effect lineage and budgets; authored equipment schema and fail-closed cutover; unresolved combat/defeat/loot/revival rules; target-cycling and duplicate-name semantics; and whether economy/crafting stays ordinary tick work or requires reservation/escrow.

Narrow anchors are the owner summaries, Game Session durable effect handoff, relevant tick/effect/transaction/identifier headings, Unit 2B's World/Entity spatial handoff, ADRs 0051/0056/0069, and relevant player/creator journey consequences. Implementation classes, migrations, protobuf/jOOQ, and tests remain current-state evidence only. Exclude complete tick/region execution (2A), spatial/Weather authority (2B), publish/cutover (3A/3C), scripting (4A/4B), shared substrate (5B-5D), presentation/social outside the ADR0148 value seam, and operational/proof surfaces.

Unit 2A implementation drift is not a blanket blocker, but refresh any seam if an accepted 2A correction changes lane/phase order, plan/root identity, region/executor fences, cross-region outcomes, durable-first commit, replay, or terminal aggregation. Non-Weather Unit 2C review can proceed only after Unit 2B's World/Entity ownership, location/occupancy, causal-floor, target-fact, and spatial-guard contracts are stable. The unresolved Weather selector blocks only effects that consume Weather/ambient facts, not actor state, inventory, equipment, non-Weather targeting, or economy ownership.

### 3A

Unit 3A's substantive boundary is the following 18 authored-content, packaging, owner, rationale, tracker, and product sources:

1. `design/architecture/microservices/game-design-service/README.md`
2. `design/architecture/microservices/game-design-service/api-contracts.md`
3. `design/architecture/microservices/game-design-service/game-templates.md`
4. `design/architecture/microservices/game-design-service/modding-framework.md`
5. `design/architecture/microservices/game-design-service/asset-storage.md`
6. `design/architecture/microservices/game-design-service/version-control.md`
7. `design/architecture/microservices/game-design-service/world-editing-tools.md`
8. `design/architecture/microservices/game-design-service/ability-action-tools.md`
9. `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
10. `design/architecture/system-architecture-versioning-runtime.md`
11. `design/architecture/decisions/adr-0093-game-design-coordinated-digest-attested-content-publication.md`
12. `design/architecture/decisions/adr-0095-content-addressed-published-assets-with-cas-lifecycle-authority.md`
13. `design/architecture/decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md`
14. `design/architecture/decisions/adr-0112-typed-bounded-gameplay-effect-extension.md`
15. `design/architecture/decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md`
16. `design/architecture/decisions/adr-0128-game-design-plugin-trust-provenance.md`
17. `design/project-management/implementation-tracking/game-authoring-publishing-and-activation.md`
18. `design/product/requirements.md`

Review Game Design publication authority versus World/Entity/Game Logic domain ownership; release attestation and participant digests; immutable assets/manifests and CAS lifecycle; Draft revisions and multi-owner commits; starter-profile/default materialization; typed authored actions; embedded-script versus plugin packaging; signed and approved-unsigned provenance; publication versus readiness/activation; the initial package/non-portability boundary; hosted creator-terms gating; and current numeric version transports versus target UUID identity. Narrow anchors are customization, ADRs 0096/0124-0127/0129/0180/0181, LLM authoring, scripting control-plane/rollout, direct service handoffs, and asset/release operations. Implementation files remain evidence only.

Refresh only when a consumed contract changes materially: 5A identifiers/auth/idempotency/digests/fences; 5B durable revision/version/asset/attestation retention; 5D publish workflow identity/recovery; 2B authored World/Entity ownership; 2C ability-schema/effect catalogs; 4A/4B DSL validation/readiness/activation; 3B settings/default precedence; 1A/6C hosted-terms authority; or 6D asset-origin delivery. Unit 3C consumes the settled release bundle and does not block initial discovery unless it changes that bundle. Exclude runtime scripting/scheduling, runtime plugin activation/cutover, gameplay/spatial execution, shared substrate as a standalone topic, edge/deployment execution, and general observability/proof.

### 3B

Unit 3B's substantive boundary is the following 15 settings, policy, effective-configuration, owner, rationale, tracker, and product sources:

1. `design/architecture/system-architecture-settings-model.md`
2. `design/architecture/decisions/adr-0012-settings-value-precedence-and-constraints.md`
3. `design/architecture/decisions/adr-0113-bounded-pull-settings-distribution-with-freshness-classes.md`
4. `design/architecture/decisions/adr-0175-release-pinned-command-capabilities-and-private-history.md`
5. `design/architecture/system-architecture-player-command-model.md`
6. `design/architecture/system-architecture-input-output-and-presentation.md`
7. `design/architecture/service-responsibility-matrix.md`
8. `design/architecture/microservices/game-design-service/README.md`
9. `design/architecture/microservices/game-design-service/feature-flags.md`
10. `design/architecture/microservices/game-session-service/configuration.md`
11. `design/architecture/microservices/game-logic-service/configuration.md`
12. `design/project-management/implementation-tracking/game-authoring-publishing-and-activation.md`
13. `design/project-management/implementation-tracking/player-experience-commands-and-communication.md`
14. `design/project-management/implementation-tracking/world-runtime-and-movement.md`
15. `design/product/requirements.md`

Review permitted setting sources/scopes, six-layer precedence, independent caps/hard bounds, Game Design persistence authority versus shared resolution/runtime merge, revision/CAS/digest/provenance/freshness handling, restrictive fail-closed behavior, operator presets/defaults/cap changes, standard capability enforcement across every ingress, feature-definition versus runtime-state versus operator-ingress ownership, presentation/prompt versus reconnect lifecycle, profile/template suggestions without hidden inheritance, and current instance scope versus target stable namespace scope.

Narrow anchors are generated settings outputs and their publication inputs, the Game Design settings API and current implementation proof, Game Templates runtime-default headings, versioning's flag/default handoff, reconnection/ADR0134 boundaries, and Logging & Admin's operator-ingress seam. Generated files, code, migrations, protobuf, and tests remain evidence only. Refresh only for material 5A identity/revision/provenance/freshness changes, 5B persistence/retention changes, 3A profile-setting ownership changes, or consumer changes to a canonical key/scope/precedence/stale-data/ownership rule. Unit 3C must consume rather than recreate this authority. Preset composition/removal, operator defaults, and cap semantics may block terminal closure of their sub-seams but do not block initial review. Exclude general authoring, release/cutover, reconnect lifecycle, tick/spatial/script execution, shared substrate as standalone scope, edge/operator/deployment execution, and general observability/proof.

### 3C

Unit 3C's substantive boundary is the following 17 release-lifecycle and activation sources:

1. `design/architecture/system-architecture-versioning-runtime.md`
2. `design/architecture/microservices/game-design-service/README.md`
3. `design/architecture/microservices/game-design-service/api-contracts.md`
4. `design/architecture/microservices/game-design-service/game-templates.md`
5. `design/architecture/microservices/game-design-service/version-control.md`
6. `design/architecture/microservices/game-design-service/asset-storage.md`
7. `design/architecture/microservices/game-session-service/api-contracts.md`
8. `design/architecture/microservices/world-management-service/api-contracts.md`
9. `design/architecture/microservices/world-management-service/world-creation-workflow.md`
10. `design/architecture/decisions/adr-0094-explicit-cohesive-runtime-release-tuples.md`
11. `design/architecture/decisions/adr-0096-attested-publication-gate-and-quarantined-failed-assets.md`
12. `design/architecture/decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md`
13. `design/architecture/decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md`
14. `design/architecture/decisions/adr-0137-isolated-playtest-state-modes-and-reset.md`
15. `design/architecture/decisions/adr-0139-tenant-owned-runtime-lifecycle-with-audited-break-glass.md`
16. `design/project-management/implementation-tracking/game-authoring-publishing-and-activation.md`
17. `design/product/requirements.md`

Review the immutable release bundle and attestation gate; exact launch descriptors and retry identity; template-to-runtime mapping; version/manifest/generation/epoch/remap binding; Game Design publication authority versus Game Session cutover and World prepared activation; stable playable-state namespaces; lifecycle epochs and fences; tenant-admin, designer, and break-glass authority; failure-safe replacement; and distinct rollback, retirement, and purge operations. Narrow anchors are the responsibility matrix, multi-tenancy and customization handoffs, Automation patch readiness, Account entitlement, Logging/Admin ingress, ADRs 0093/0095/0106/0108/0110/0119/0124-0129/0138/0180/0181, and the creator journey. ADR 0137 remains a substantive lifecycle source only for its playtest namespace/reset consequences; image-promotion attestation is explicitly outside this tenant/game release unit.

Unit 3C consumes stable 3A publication/manifest fields and 3B immutable resolved settings. Refresh for material changes to 5A identity/auth/digest/fence fields, 5B durable release retention, 5D workflow identity/recovery, 2B/2C World/Entity/ability compatibility, 4A/4B exact readiness or plugin publication, 1A/6C entitlement and lifecycle authorization, or 6D release-artifact proof. Exclude authoring/package construction, runtime scripting and scheduling, gameplay/spatial execution, shared substrate as a standalone topic, repository release/image promotion, and general proof operations.

### 4A

Unit 4A's substantive boundary is the following 18 script-ingress, sandbox, and runtime-execution sources:

1. `design/architecture/system-architecture-scripting-contracts.md`
2. `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
3. `design/architecture/system-architecture-scripting-event-registry.md`
4. `design/architecture/system-architecture-scripting-normative-contract-tables.md`
5. `design/architecture/system-architecture-scripting-runtime-execution.md`
6. `design/architecture/microservices/automation-scripting-service/README.md`
7. `design/architecture/microservices/automation-scripting-service/api-contracts.md`
8. `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`
9. `design/architecture/microservices/automation-scripting-service/runtime-and-data.md`
10. `design/architecture/microservices/game-session-service/api-contracts.md`
11. `design/architecture/decisions/adr-0063-durable-per-dispatch-script-handoff.md`
12. `design/architecture/decisions/adr-0064-stage-qualified-script-outcomes.md`
13. `design/architecture/decisions/adr-0088-static-and-incremental-script-output-bounds.md`
14. `design/architecture/decisions/adr-0090-recorded-script-input-manifests-for-reproducible-evaluation.md`
15. `design/architecture/decisions/adr-0117-producer-owned-event-schemas-with-one-materialized-catalogue.md`
16. `design/architecture/decisions/adr-0118-preselected-exclusive-handlers-and-durable-fanout-ordering.md`
17. `design/architecture/decisions/adr-0172-parent-event-and-frozen-handler-execution-identity.md`
18. `design/project-management/implementation-tracking/automation-and-scheduler-runtime.md`

Review producer-owned event semantics and materialized registry identity; event-scope admission versus handler execution identity; deterministic bounded DSL inputs/outputs; immutable manifests, schemas, digests, seeds, and causal floors; durable pre-evaluation work creation; no DSL re-entry after committed evaluation; complete preselected handler sets and stable fan-out; per-command durable Game Session handoff; stage-qualified outcomes; and bounded `onLoad` readiness. Narrow anchors are scripting observability, control-plane ingress, Game Session command custody, designer-facing graph constraints, quota terminology, ADRs 0002/0069/0114-0116, product consequences, tracker proof rows, and focused current implementation/tests.

Unit 4A consumes stable 5A identity/transport primitives, 5D durable work/outbox/replay contracts, 5C queue-pointer semantics, 2A command custody and region fences, 3A compiled-artifact identity/provenance, and 4B quota/readiness/reload/plugin-lifecycle fields. Refresh only when one of those consumed seams changes materially. Exclude scheduler/timer fairness, quota operations, reload/rollout/plugin breakers, authoring/publication, gameplay mutation, shared substrate as a standalone topic, and edge/deployment/proof operations.

### 4B

Unit 4B's substantive boundary is the following 17 scheduling, quota, reload, and operational-fairness sources:

1. `design/architecture/system-architecture-scripting-quotas-and-operations.md`
2. `design/architecture/system-architecture-scripting-scheduler-and-timers.md`
3. `design/architecture/system-architecture-scripting-control-plane-operations.md`
4. `design/architecture/system-architecture-scripting-control-plane-api.md`
5. `design/architecture/system-architecture-scripting-contracts.md`
6. `design/architecture/system-architecture-scripting-event-registry.md`
7. `design/architecture/microservices/automation-scripting-service/api-contracts.md`
8. `design/architecture/microservices/automation-scripting-service/configuration.md`
9. `design/architecture/microservices/automation-scripting-service/operations.md`
10. `design/architecture/microservices/automation-scripting-service/runtime-and-data.md`
11. `design/architecture/decisions/adr-0072-class-specific-timer-durability-and-recovery.md`
12. `design/architecture/decisions/adr-0089-durable-script-usage-charges-and-fenced-capacity-leases.md`
13. `design/architecture/decisions/adr-0091-class-specific-script-timer-clocks-and-recovery.md`
14. `design/architecture/decisions/adr-0166-attributable-script-breakers-and-tenant-first-fairness.md`
15. `design/architecture/decisions/adr-0173-registry-classified-reload-admission-policy.md`
16. `design/project-management/implementation-tracking/automation-and-scheduler-runtime.md`
17. `design/product/requirements.md`

Review tick-time versus wall-clock timers, recovery classes and resume windows, exact scope/epoch fencing, once-only due/firing transitions, tenant-first fairness, independent quota/budget/capacity states, readiness isolation, registry-selected reload policy, schedule continuity, plugin lifecycle versus breaker state, and owner-directed operator controls. Unit 4B consumes 5A identity/time, 5B durable schedule/charge state, 5C Redis projections, 5D retry/workflow, 2A tick/region authority, 4A trigger/execution semantics, 3A/3C artifact/plugin lifecycle, 6C operator ingress, and 7A/7C evidence contracts. Superseded ADR 0003 and implementation files are evidence only.

### 5A

Unit 5A's substantive review boundary is the following 17 primary shared-primitive sources. ADRs and selected consumers below are evidence anchors consulted for a concrete seam; they are not an instruction to load another entire parcel into one review context.

1. `design/architecture/service-responsibility-matrix.md`
2. `design/architecture/system-architecture-authz-route-matrix.md`
3. `design/architecture/system-architecture-diagram.md`
4. `design/architecture/system-architecture-grpc.md`
5. `design/architecture/system-architecture-identifier-glossary.md`
6. `design/architecture/system-architecture-jwt-and-token-contracts.md`
7. `design/architecture/system-architecture-jwt-compromise-runbook.md`
8. `design/architecture/system-architecture-operator-credentials-runbook.md`
9. `design/architecture/system-architecture-overview.md`
10. `design/architecture/system-architecture-scripting-control-plane-events.md`
11. `design/architecture/system-architecture-scripting-normative-contract-tables.md`
12. `design/architecture/system-architecture-security.md`
13. `design/architecture/system-architecture-shared-libraries.md`
14. `design/architecture/system-architecture-threat-model.md`
15. `design/architecture/infrastructure/environment-and-secrets-catalog.md`
16. `design/architecture/infrastructure/environment-and-secrets-overview.md`
17. `design/architecture/infrastructure/environment-and-secrets.md`

Review the last three environment sources, `design/architecture/system-architecture-diagram.md`, and the runbooks only for their shared primitive/authority claims. Deployment execution belongs to 6D. `design/architecture/README.md` and `design/architecture/system-context-diagram.md` are navigation lenses rather than substantive 5A sources.

Direct rationale anchors are the files for ADRs 0014, 0020, 0023, 0024, 0032, 0035, 0036, 0037, 0038, 0073, 0092, and 0174 under `design/architecture/decisions/`. Open only the specific rationale needed for a concrete seam rather than treating this set as a second decision inventory. Adjacent ADRs 0048, 0059, 0062, 0078, 0120, 0169, and the Redis ADRs are handoff pointers to 5D, 6B/6D, or 5C.

Selected consumer evidence is `design/architecture/system-architecture-authentication.md`, `design/architecture/system-architecture-multi-tenancy.md`, `design/architecture/system-architecture-gateway.md`, `design/architecture/microservices/account-service/api-contracts.md`, `design/architecture/microservices/game-session-service/api-contracts.md`, `design/architecture/system-architecture-ticks.md`, `design/architecture/system-architecture-scripting-scheduler-and-timers.md`, and `design/product/requirements.md`. The owning implementation record is `design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md`. Consult the consumer that directly exercises the primitive under review rather than loading all consumers by default. `PlayerExecutionContext` is a seam symbol, not another path.

Primary seam questions are typed gRPC outcomes versus transport status; UUID/logical identity versus private numeric persistence keys; legacy gameplay-attestation removal and `PlayerExecutionContext` limits; asymmetric signing/JWKS, mTLS URI-SAN identity, method allowlists, token registry, and generation enforcement; route-inventory/default-deny adoption; gameplay-connect separation; whether a shared gameplay-clock API is warranted; scripting table ownership; the preview plaintext exception; and preservation of operator mutation vocabulary without absorbing 6C/5D execution.

Explicit exclusions are SQL/Flyway/jOOQ and retention (5B), Redis execution/keyspaces/reset/cache/rate-limit (5C), idempotency/outbox/saga/Temporal/replay/workflow execution (5D), gameplay and tick authority (2A/2B), scripting runtime and scheduling (4A/4B), edge routing/transport/operator/deployment execution (6A-6D), and observability/recovery proof (7A-7C). Review only their shared primitive handoffs.

### 5B — three semantic review subunits

The 5B source union is the following 18 allocated design/product/tracker sources. Implementation migrations, repositories, jobs, tests, and generated jOOQ are evidence for a concrete current-state claim only; they do not occupy the static corpus manifest. The three subunits below are heading-scoped review boundaries over this same union. They do not create duplicate normative owners, and they are not a 1:1 map of the implementation PR stack.

1. `design/architecture/system-architecture-database-migrations.md`
2. `design/architecture/system-architecture-scaling-runbook.md`
3. `design/architecture/system-architecture-shared-libraries.md`
4. `design/architecture/service-responsibility-matrix.md`
5. `design/architecture/system-architecture-versioning-runtime.md`
6. `design/architecture/decisions/adr-0079-jooq-and-flyway-as-the-single-sql-persistence-stack.md`
7. `design/architecture/decisions/adr-0080-service-owned-schemas-with-adopter-local-shared-migrations.md`
8. `design/architecture/decisions/adr-0081-objective-compatibility-gates-for-database-evolution.md`
9. `design/architecture/decisions/adr-0082-semantic-boundary-for-cross-service-identifier-migration.md`
10. `design/architecture/decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md`
11. `design/architecture/microservices/account-service/runtime-and-data.md`
12. `design/architecture/microservices/automation-scripting-service/runtime-and-data.md`
13. `design/architecture/microservices/entity-management-service/runtime-and-data.md`
14. `design/architecture/microservices/game-session-service/runtime-and-data.md`
15. `design/architecture/microservices/social-groups-service/runtime-and-data.md`
16. `design/architecture/microservices/world-management-service/runtime-and-data.md`
17. `design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md`
18. `design/product/requirements.md`

**5B family synthesis scope:** review the canonical SQL/Flyway/schema and retention contracts against owner-local durable-state consequences. Preserve service-owned DDL and cleanup while checking jOOQ/Flyway convergence, isolated schemas and adopter-local saga migrations, objective expand/migrate/contract gates, typed identifier-migration mappings, namespace-stable versus replaceable instance identity, ADR 0163 terminality/blocker/hold/horizon/safe-watermark rules, retained-version contraction constraints, and cleanup consequences across Account, Automation, Entity, Game Session, Social, and World. This synthesis follows the three subunit passes; it is not an additional prerequisite broad review before them.

`design/architecture/system-architecture-transactions.md` is a 5D handoff anchor rather than substantive 5B scope. ADR 0149 is a directly relevant Social consumer anchor, not shared-retention authority. Gateway `route_config` ownership is a seam to classify between 5B persistence consequences and 6A/6D route/deployment ownership, not a reason to load the Gateway corpus. Redis belongs to 5C; transaction/outbox/replay/saga/Temporal behavior to 5D; deployment/reset/backup execution to 6D; verification/compliance and recovery evidence to 7B/7C.

#### 5B-Schema — SQL, schema, and identifier evolution

This subunit owns the SQL persistence and schema-evolution lens. Its primary anchors are:

- `design/architecture/system-architecture-database-migrations.md`: **Migration Tool**, **Per-Service Organization**, **Objective Compatibility Gates**, **Version-Aware Migration Checklist**, **Cross-Service Identifier Migration**, and **Examples of Versioned World and Entity Migrations**. **CI/CD Execution** is a handoff to 6D/7B.
- `design/architecture/system-architecture-scaling-runbook.md`: **Scaling PostgreSQL**; **Data Retention and High-Churn Tables** overlaps 5B-Retention, while **Verification** is a 7B handoff.
- `design/architecture/system-architecture-shared-libraries.md`: **SQL Persistence Direction**; **Publishing Strategy** overlaps 5B-Publication, and **Short Synchronous Saga Orchestration** is a 5D handoff.
- `design/architecture/service-responsibility-matrix.md`: **Target Responsibility Model** and implementation-status ownership claims; Redis and movement/moderation notes remain handoffs.
- ADRs 0079–0082, with ADR 0163's retained-data consequences overlapping 5B-Retention.
- `design/architecture/system-architecture-versioning-runtime.md`: **Schema Migrations vs Design Data** and the identifier/owner portions of **Game Version Publishing**, **Version Lifecycle**, and **Replacement-Instance Upgrade Contract**; the publication and cleanup portions are overlaps with the other subunits.
- `design/architecture/microservices/account-service/runtime-and-data.md`: **Data Model** and identity portions of **Membership and Entitlement Authority**; token-retention headings overlap 5B-Retention.
- `design/architecture/microservices/automation-scripting-service/runtime-and-data.md`: **Runtime Data Model** and durable identity portions of **Script Lifecycle**; **Workflow Participation** and **Durable Script Work-Item Outbox** are 5D handoffs.
- `design/architecture/microservices/entity-management-service/runtime-and-data.md`: **Data Model and Versioning**, **Persisted actor and realm-entry identity**, **Runtime Actor Identity**, and schema portions of **Runtime Data Model**.
- `design/architecture/microservices/game-session-service/runtime-and-data.md`: **Persisted actor admission boundary**, **Registered Durable Families and Lifecycle Participation**, and **Session Keys and Indexes**; pin/readiness is 5B-Publication and command/effect execution is a 5D handoff.
- `design/architecture/microservices/social-groups-service/runtime-and-data.md`: durable identity portions of **Data Model**; Redis and delivery remain handoffs.
- `design/architecture/microservices/world-management-service/runtime-and-data.md`: **Template and Runtime Ownership**, **Template Identifier Invariants**, and schema portions of **Data Model**; digest is 5B-Publication and replacement/cleanup is 5B-Retention.
- `design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md`: **Unit 5B persistence review consequences**, **jOOQ, Flyway, and SQL Persistence**, and related **Active Gaps**; transaction and Temporal sections are 5D handoffs.
- `design/product/requirements.md`: **2.9 Versioning & Runtime Configuration**, **3.2 Persistence & Caching**, and the persistence/identity consequences of the service requirements.

The subunit checks service-owned DDL, adopter-local shared migrations, generated-access convergence, mixed-binary compatibility, durable identity mappings, and the legality of schema contraction while other subunits still require retained records. It hands Redis execution to 5C, transaction/workflow execution to 5D, deployment/reset execution to 6D, and runtime-domain semantics to their owning sections.

#### 5B-Publication — durable version, publication, and readiness state

This subunit owns only durable publication and readiness consequences while leaving canonical publication authority, authoring, release activation, and runtime execution with their owning sections. Canonical Game Design publication authority remains [`version-control.md#design-time-synchronization`](../../architecture/microservices/game-design-service/version-control.md#design-time-synchronization); this lens checks the persistence, schema, readiness, and cross-service handoff consequences of that authority. Its primary anchors are:

- `design/architecture/system-architecture-versioning-runtime.md`: **Game Version Publishing**, **Version State Ownership and CAS Authority**, **Version Lifecycle**, **Script-Only Patch Versions**, **Cross-Asset Version Cohesion**, **Launch Descriptor Version-Resolution Rules**, and **Capacity Delta Wire and Digest Contract**. Schema-migration portions overlap 5B-Schema; retirement, termination, and retained-release portions overlap 5B-Retention.
- `design/architecture/system-architecture-shared-libraries.md`: **Publishing Strategy**; SQL persistence and saga text is a handoff to 5B-Schema/5D.
- `design/architecture/service-responsibility-matrix.md`: **Target Responsibility Model** only where it assigns publication, digest, readiness, or durable-state ownership.
- ADRs 0079–0082 for publication-record schemas, compatibility, and typed identity; ADR 0163 for retained published-version terminality.
- `design/architecture/microservices/automation-scripting-service/runtime-and-data.md`: **Runtime Data Model** and **Script Lifecycle** readiness, pin, and binding portions.
- `design/architecture/microservices/entity-management-service/runtime-and-data.md`: **Data Model and Versioning** version-reference portions.
- `design/architecture/microservices/game-session-service/runtime-and-data.md`: **Script Patch Version Pinning and Rollback** and the durable readiness/readback portions of **Registered Durable Families and Lifecycle Participation**.
- `design/architecture/microservices/world-management-service/runtime-and-data.md`: **Template Identifier Invariants** and **Digest Input Manifest**; template persistence remains a 5B-Schema overlap.
- Account and Social `runtime-and-data.md` are consulted only for a direct publication participant or entitlement/readiness handoff; their general lifecycle and delivery contracts remain outside this subunit.
- `design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md`: **Script-transition cross-service handoff**, **Unit 5B persistence review consequences**, publication-related portions of **Orchestration and Control-Plane Shape**, and the SQL records that persist publication state.
- `design/product/requirements.md`: **2.2 Game Design & Customization**, **2.7 Extensibility & Game Customization**, and **2.9 Versioning & Runtime Configuration**.

The subunit checks stable publication identities, participant-digest scope, owner readback, readiness ordering, publication freeze/readiness handoffs, and durable attempt/bundle state. It does not absorb Game Design authoring semantics, release activation, Automation scheduling/execution, or 5D transaction/workflow authority; it records their direct persistence and readiness consequences and routes the rest to 3A/3C, 4A/4B, and 5D.

#### 5B-Retention — retention, cleanup, and recovery-safe durable state

This subunit owns the durable safety rules that determine when records may be retained, contracted, deleted, tombstoned, or recovered. Its primary anchors are:

- `design/architecture/decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md` in full.
- `design/architecture/system-architecture-database-migrations.md`: **Objective Compatibility Gates**, **Version-Aware Migration Checklist**, **Cross-Service Identifier Migration**, and retained-data portions of the migration examples; schema ownership remains 5B-Schema.
- `design/architecture/system-architecture-scaling-runbook.md`: **Scaling PostgreSQL** and **Data Retention and High-Churn Tables**; operational verification is a 7B handoff.
- `design/architecture/system-architecture-versioning-runtime.md`: **Version Lifecycle**, **Version State Ownership and CAS Authority**, **Replacement-Instance Upgrade Contract**, **Instance Termination Handoff**, **Tenant-Owned Runtime Lifecycle Authority**, and **Fork-Snapshot Boundary**; schema and publication-state portions overlap 5B-Schema/5B-Publication.
- `design/architecture/microservices/account-service/runtime-and-data.md`: **Account Lifecycle State Model**, **Connect-token issuance persistence and retention**, and retained-subject portions of **Data Model**.
- `design/architecture/microservices/automation-scripting-service/runtime-and-data.md`: **Hot Reload and Failure Handling**, cleanup/replay-safe portions of **Runtime Data Model**, and durable work-item consequences; workflow execution is a 5D handoff.
- `design/architecture/microservices/entity-management-service/runtime-and-data.md`: **Instance Termination Cleanup Contract**, cleanup/receipt portions of **Runtime Data Model**, and **Workflow Participation** only as a handoff.
- `design/architecture/microservices/game-session-service/runtime-and-data.md`: **Registered Durable Families and Lifecycle Participation**, **Session Keys and Indexes**, and durable command/effect retention portions of **Durable Command and Effect Execution**; Saga and replay execution remain 5D.
- `design/architecture/microservices/social-groups-service/runtime-and-data.md`: durable/history portions of **Data Model** and **Chat and Voice Delivery**.
- `design/architecture/microservices/world-management-service/runtime-and-data.md`: **Replacement-Instance State Classification**, **Instance-Scoped Population Schedule Contract**, and cleanup/lifecycle portions of **World Events**.
- `design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md`: **Unit 5B persistence review consequences**, persistence-related **Active Gaps**, and recorded retention/proof obligations.
- `design/product/requirements.md`: **3.2 Persistence & Caching**, **3.4 Gameplay Session Architecture**, and recovery/retention consequences in **Section 4 Non-Functional Requirements**.

The subunit checks terminality, blockers, holds, horizons, safe watermarks, retained-version contraction, owner-local cleanup, and recovery evidence. It does not absorb gameplay meaning, Redis reset execution, Temporal/replay/workflow execution, backup/restore execution, or incident proof; those remain explicit 2A–2C, 5C, 5D, 6D, and 7B/7C handoffs.

#### Whole-5B synthesis and handoff gate

After each subunit has completed its independent fresh coverage and taper, run one unrestricted synthesis over the complete 18-source union and every declared overlap. The synthesis must reconcile the durable identity tuple across schema and publication records; migration compatibility against published and retained versions; readiness terminality against retention and cleanup; owner-local foreign-key and mapping consequences; and the boundary from 5B durable state into 5C Redis, 5D transactions/replay/workflows, 6D deployment/reset/backup, and 7B/7C proof. A useful synthesis finding returns to the owning subunit and requires a new synthesis pass after correction. The 5B family is complete only when all three subunits and this synthesis have tapered; no child PR's closure or historical review count satisfies that gate.

### 5C

Unit 5C's substantive boundary is the following 17 allocated Redis owner, rationale, primary-consumer, tracker, and product sources:

1. `design/architecture/system-architecture-redis.md`
2. `design/architecture/system-architecture-redis-cache.md`
3. `design/architecture/system-architecture-redis-cache-reference.md`
4. `design/architecture/system-architecture-redis-design-checklist.md`
5. `design/architecture/system-architecture-redis-lua-patterns.md`
6. `design/architecture/system-architecture-redis-operations.md`
7. `design/architecture/system-architecture-redis-reset-and-recovery.md`
8. `design/architecture/system-architecture-redis-script-rollout-and-compatibility.md`
9. `design/architecture/system-architecture-redis-usage-and-profiles.md`
10. `design/architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md`
11. `design/architecture/decisions/adr-0058-class-specific-redis-loss-outcomes.md`
12. `design/architecture/decisions/adr-0084-evidence-scoped-redis-lua-compatibility.md`
13. `design/architecture/decisions/adr-0087-isolated-subject-rate-limits-with-explicit-loss-semantics.md`
14. `design/architecture/decisions/adr-0171-separated-redis-role-processes-and-owned-keyspaces.md`
15. `design/architecture/microservices/game-session-service/runtime-and-data.md`
16. `design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md`
17. `design/product/requirements.md`

Review strict Coordination versus Cache/Rate-Limit separation; PostgreSQL durable authority; owner keyspaces, hash tags, script ordering, and cross-role rejection; Class A version validation and Class B fallback; opaque subject-isolated rate-limit loss semantics; external fencing and replay-first resets; evidence-scoped Lua compatibility; and role-specific profiles, ACLs, clients, maxmemory, eviction, and collision guards. World, Entity, and Automation runtime/data documents are selected consumer evidence for concrete cache/prefix/queue seams rather than additional substantive sources.

The Redis cheat sheet is a navigation lens. Redis incident and metrics catalogs belong to 7A/7C, and ops access belongs to 6C; consult them only for a concrete handoff. ADR 0176 is an SF-1/scripting registry anchor rather than substantive 5C scope. SQL/migrations/retention belong to 5B; transaction/outbox/replay/saga behavior to 5D; deployment/profile delivery and backup execution to 6D. Implementation Lua, configuration, and tests are current-state evidence only.

### 5D

Unit 5D's substantive boundary is the following 17 allocated transaction/workflow owner, consumer, rationale, tracker, and product sources:

1. `design/architecture/system-architecture-transactions.md`
2. `design/architecture/system-architecture-temporal-workflows.md`
3. `design/architecture/system-architecture-scripting-runtime-execution.md`
4. `design/architecture/system-architecture-tick-failures-and-operations.md`
5. `design/architecture/microservices/game-session-service/runtime-and-data.md`
6. `design/architecture/microservices/automation-scripting-service/runtime-and-data.md`
7. `design/architecture/microservices/world-management-service/world-creation-workflow.md`
8. `design/architecture/microservices/game-design-service/operations.md`
9. `design/architecture/microservices/logging-admin-service/runtime-and-data.md`
10. `design/architecture/decisions/adr-0053-command-atomicity-by-invariant-class.md`
11. `design/architecture/decisions/adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md`
12. `design/architecture/decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md`
13. `design/architecture/decisions/adr-0083-no-general-event-broker-until-measured-adoption-gates.md`
14. `design/architecture/decisions/adr-0085-evidence-gated-coordination-replay-and-fenced-reset.md`
15. `design/architecture/decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md`
16. `design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md`
17. `design/product/requirements.md`

Review exact local transaction and outbox boundaries; ingress, root/child effect, saga, workflow, run, step, and business identities; one logical terminal outcome under at-least-once execution; event-broker adoption gates; short synchronous saga versus Temporal selection; database-authoritative lifecycle fencing across retry/cancel/termination/cleanup; replay-first versus reset handoffs; retention dependencies needed for replay/reconciliation; and operator projections that do not acquire execution authority.

Mixed-boundary consumer files are heading-scoped: do not expand gameplay/tick authority, release/cutover, scripting execution and quotas, or operator ingress into 5D. ADR 0163 is a 5B substantive source and only a retention handoff here. Redis execution belongs to 5C; gameplay/tick/spatial semantics to 2A-2C; activation and scripting behavior to 3C/4A/4B; operator/deployment execution to 6C/6D; observability and proof to 7A-7C. Implementation code, migrations, generated jOOQ, and tests are evidence only.

### 6A

Unit 6A's substantive boundary is the following 16 Gateway-route, traffic-plane, sharding, and close-taxonomy sources:

1. `design/architecture/system-architecture-overview.md`
2. `design/architecture/system-architecture-gateway.md`
3. `design/architecture/system-architecture-authz-route-matrix.md`
4. `design/architecture/system-architecture-authz-route-matrix.yaml`
5. `design/architecture/service-responsibility-matrix.md`
6. `design/architecture/microservices/spring-cloud-gateway/api-contracts.md`
7. `design/architecture/microservices/spring-cloud-gateway/client-behavior.md`
8. `design/architecture/system-architecture-reconnection.md`
9. `design/project-management/implementation-tracking/platform-operations-and-delivery.md`
10. `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`
11. `design/architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md`
12. `design/architecture/decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md`
13. `design/architecture/decisions/adr-0018-declarative-production-gateway-routes.md`
14. `design/architecture/decisions/adr-0023-central-route-authorization-governance.md`
15. `design/architecture/decisions/adr-0131-lifecycle-distinct-gameplay-close-taxonomy.md`
16. `design/architecture/decisions/adr-0157-dependency-classified-liveness-readiness-and-route-admission.md`

Review one released declarative route authority, complete source-stable route inventory/default deny, explicit route families and traffic surfaces, Gateway's narrow token validation and header trust, rate-limit ownership, connect-token replay continuity, no edge-owned gameplay sharding, the single-active-cluster rule, bounded invisible non-edge recovery, close-category translation, and route-specific readiness. Unit 6A consumes 5A/5C identity and Redis primitives, 1A/1B admission/reconnect, 2A lease-owner routing, 6B transport mapping, 6C operator route additions, 6D environment binding, and 7A/7C evidence. The absent target `routes.yml` and incomplete YAML inventory remain implementation gaps rather than competing authority.

### 6B

Unit 6B's substantive boundary is the following 14 WebSocket, Telnet, protocol-bridge, and session-transport sources:

1. `design/architecture/service-responsibility-matrix.md`
2. `design/architecture/system-architecture-protocol-bridging.md`
3. `design/architecture/system-architecture-reconnection.md`
4. `design/architecture/system-architecture-mud-client-protocol.md`
5. `design/architecture/microservices/tcp-proxy-service/README.md`
6. `design/architecture/microservices/tcp-proxy-service/protocols.md`
7. `design/architecture/microservices/tcp-proxy-service/api-contracts.md`
8. `design/architecture/microservices/tcp-proxy-service/runtime-and-data.md`
9. `design/architecture/microservices/game-session-service/protocols.md`
10. `design/architecture/microservices/game-session-service/runtime-and-data.md`
11. `design/architecture/decisions/adr-0131-lifecycle-distinct-gameplay-close-taxonomy.md`
12. `design/architecture/decisions/adr-0133-fresh-edge-reconnect-without-client-input-replay.md`
13. `design/architecture/decisions/adr-0033-public-player-facing-telnet-requires-tls.md`
14. `design/project-management/implementation-tracking/player-access-and-session.md`

Review one `/ws/game/**` transport contract with distinct first-party and trusted-proxy admission; header/PROXY trust; mutually exclusive public TLS modes; bounded line/frame ordering and buffers; ambiguous-write closure without replay; fresh-edge versus retained-edge recovery; advisory deduplicated disconnect; top-level close preservation; grace/circuit-breaker alignment; session-front-end forwarding; plain-text parity; and exact response framing. Unit 6B consumes 6A route/close authority, 1B session admission, 1C semantic output, 5A/5C/5D shared trust and durability, 2A forwarding fences, 6D listener/certificate wiring, and 7A-7C proof. Future MCP/GMCP remains deferred and cannot create proxy-owned semantics.

### 6C

Unit 6C's substantive boundary is the following 18 operator-ingress, action-authorization, moderation, and availability sources:

1. `design/architecture/microservices/logging-admin-service/api-contracts.md`
2. `design/architecture/microservices/logging-admin-service/runtime-and-data.md`
3. `design/architecture/microservices/logging-admin-service/moderation-policies.md`
4. `design/architecture/service-responsibility-matrix.md`
5. `design/architecture/system-architecture-authz-route-matrix.md`
6. `design/architecture/system-architecture-authz-route-matrix.yaml`
7. `design/architecture/system-architecture-operator-credentials-runbook.md`
8. `design/product/user-journeys/operators.md`
9. `design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md`
10. `design/architecture/decisions/adr-0023-central-route-authorization-governance.md`
11. `design/architecture/decisions/adr-0039-bounded-redis-operator-maintenance-surface.md`
12. `design/architecture/decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md`
13. `design/architecture/decisions/adr-0048-durable-idempotent-operator-write-execution.md`
14. `design/architecture/decisions/adr-0139-tenant-owned-runtime-lifecycle-with-audited-break-glass.md`
15. `design/architecture/decisions/adr-0141-fixed-safety-restriction-categories-and-independent-lifecycles.md`
16. `design/architecture/decisions/adr-0142-bounded-moderation-appeal-cases.md`
17. `design/architecture/decisions/adr-0146-owner-local-moderation-enforcement.md`
18. `design/architecture/decisions/adr-0165-authoritative-control-actions-during-observability-loss.md`

Review normative machine-readable route policy versus explanatory inventory; default-deny incomplete coverage; Gateway admission versus Logging & Admin mutation ingress; human versus automation authorization references; complete typed action/digest/scope/fence evidence; single receiving-boundary redemption; distinct control/policy/owner/appeal identities; durable phase and uncertain-outcome reconciliation; authority preservation; fixed moderation categories and owner-local enforcement; tenant routine authority versus platform break-glass; bounded Redis maintenance; and core-control availability during observability loss. Unit 6C consumes 1A Account/reference authority, 5A auth/digest primitives, 6A route exposure, 2A/3C owner actions, 5B-5D durability, 6D credential delivery, and 7A-7C proof. Because this is a high-risk authorization boundary, confirm any apparently dry pass independently before marking the section complete.

### 6D

Unit 6D's substantive boundary is the following 18 environment, deployment, asset-delivery, backup/recovery, rationale, and tracker sources:

1. `design/architecture/infrastructure/deployment-environments.md`
2. `design/architecture/system-architecture-cicd.md`
3. `design/architecture/system-architecture-deployment-runbook.md`
4. `design/architecture/system-architecture-deploy-preflight-policy.md`
5. `design/architecture/system-architecture-asset-store-runbook.md`
6. `design/architecture/system-architecture-backup-recovery.md`
7. `design/architecture/system-architecture-post-restore-hardening.md`
8. `design/architecture/system-architecture-promotion-attestation.md`
9. `design/project-management/implementation-tracking/platform-operations-and-delivery.md` (`PO-3.1` through `PO-3.4` only)
10. `design/architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md`
11. `design/architecture/decisions/adr-0097-git-and-ci-validated-single-operator-promotion-evidence.md`
12. `design/architecture/decisions/adr-0151-event-scoped-automated-tier-a-credential-compliance.md`
13. `design/architecture/decisions/adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md`
14. `design/architecture/decisions/adr-0153-measured-online-backup-rpo-and-future-pitr-trigger.md`
15. `design/architecture/decisions/adr-0154-automated-recovery-proof-and-differentiated-traffic-open-gates.md`
16. `design/architecture/decisions/adr-0155-automated-event-classified-post-restore-trust-reset.md`
17. `design/architecture/decisions/adr-0156-risk-tiered-progressive-rollout-with-compatibility-bounded-rollback.md`
18. `design/architecture/decisions/adr-0177-exact-plan-authorized-automated-production-deployment.md`

Platform Operations owns environment/deployment execution, preflight, image and digest promotion infrastructure, object storage/CDN/registry delivery, backup/recovery execution, and post-restore hardening. Game Design retains publication, release-descriptor, asset-manifest/CAS-lifecycle, and purge-eligibility authority. Selected consumer lenses are operator and creator journeys plus product requirements; they supply consequences rather than technical authority. Narrow evidence anchors are the deploy/preflight and secret-compliance tools/contracts, preview/Helm workflow contracts, current Game Design asset export/storage implementation and proof, and Velero/manual backup/restore tools and contract tests.

Unit 7C owns incident, smoke, queryability, observability-loss, and retained failure-evidence consequences. Unit 6D supplies environment/deployment/reset prerequisites and consumes 7C proof without duplicating it. Important review seams are incomplete live preflight and credential custody, absent public asset origin/browser delivery, missing exact-plan rollout execution, incomplete production attestation enforcement, staging backup policy, and dependencies on Units 5A/5C/5D and 6A-6C.

### 7A

Starting source manifest for logs, metrics, tracing, player-experience SLOs, and degraded observability. Heading-scoped operational consumers do not gain normative ownership from inclusion here.

1. `design/architecture/system-architecture-logging-monitoring.md`
2. `design/architecture/system-architecture-tracing.md`
3. `design/architecture/system-architecture-scripting-observability-contract.md`
4. `design/architecture/system-architecture-redis-metrics-catalog.md`
5. `design/architecture/microservices/logging-admin-service/analytics-dashboards.md`
6. `design/architecture/microservices/logging-admin-service/operations.md` (observability headings)
7. `design/architecture/decisions/adr-0017-capability-gated-operational-tracing.md`
8. `design/architecture/decisions/adr-0158-simplified-observability-degradation-without-fallback-alert-authority.md`
9. `design/architecture/decisions/adr-0159-profile-dependent-independent-deadman-and-public-path-monitoring.md`
10. `design/architecture/decisions/adr-0160-staged-profile-aware-player-experience-slo-contract.md`
11. `design/architecture/decisions/adr-0161-profile-aware-isolated-synthetic-player-flow-canaries.md`
12. `design/architecture/decisions/adr-0162-profile-aware-asynchronous-end-to-end-log-queryability-evidence.md`
13. `design/architecture/decisions/adr-0167-allowlisted-sensitive-trace-attributes.md`
14. `design/project-management/implementation-tracking/platform-operations-and-delivery.md` (`PO-4.1` and `PO-4.2`)
15. `design/product/requirements.md` (reliability and observability consequences)

Review producer/consumer metric ownership, label bounds, log and trace correlation, sensitive attributes, queryability, profile-aware SLO eligibility and calibration, and degraded observability behavior. Unit 7B owns verification evidence; 7C owns incident response and external detection; 6D owns deployment of monitoring components. Consult dashboards and deployed rules as implementation evidence rather than a second normative metric schema.

### 7B

Starting source manifest for verification boundaries, recovery evidence, and compliance. Deployment and backup authorities listed below are heading-scoped handoffs from 6D, not duplicate owners.

1. `design/architecture/system-architecture-testing.md`
2. `design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md`
3. `design/architecture/system-architecture-cicd.md` (verification/evidence headings)
4. `design/architecture/system-architecture-deploy-preflight-policy.md` (proof-gate headings)
5. `design/architecture/system-architecture-promotion-attestation.md` (evidence handoff)
6. `design/architecture/system-architecture-backup-recovery.md` (recovery-input handoff)
7. `design/architecture/decisions/adr-0151-event-scoped-automated-tier-a-credential-compliance.md`
8. `design/architecture/decisions/adr-0154-automated-recovery-proof-and-differentiated-traffic-open-gates.md`
9. `design/architecture/decisions/adr-0155-automated-event-classified-post-restore-trust-reset.md`
10. `design/architecture/decisions/adr-0164-three-boundary-profile-aware-verification-evidence.md`
11. `design/architecture/decisions/adr-0178-disposable-transport-complete-pr-preview-proof.md`
12. `design/project-management/implementation-tracking/platform-operations-and-delivery.md` (`PO-3.4` and `PO-4` proof rows)
13. `design/product/requirements.md` (recovery and verification consequences)
14. `design/architecture/infrastructure/environment-and-secrets-overview.md` (credential-compliance handoff from 6D/5A)

Review what unit, cross-service, preview, staging, and production evidence actually proves; recovery-record provenance and participant dispositions; traffic-open versus eligibility decisions; credential-compliance evidence; and current proof gaps. Consume 7A's metric, log, and trace schemas and 6D's environment and credential authorities without redefining them. Unit 6D owns deployment, backup and restore execution; 7C consumes evidence for incidents and operations. Do not convert green CI or a documentation assertion into unperformed runtime proof.

### 7C

Unit 7C's substantive boundary is the following 10 incident, operational-proof, external-monitoring, product, and tracker sources:

1. `design/architecture/system-architecture-observability-incident-runbook.md`
2. `design/architecture/system-architecture-player-experience-incident-runbook.md`
3. `design/architecture/system-architecture-tick-incident-runbook.md`
4. `design/architecture/system-architecture-redis-incident-runbook.md`
5. `design/observability/external-monitoring/README.md`
6. `design/architecture/microservices/logging-admin-service/operations.md`
7. `design/architecture/microservices/logging-admin-service/analytics-dashboards.md`
8. `design/architecture/system-architecture-jwt-compromise-runbook.md`
9. `design/project-management/implementation-tracking/platform-operations-and-delivery.md` (`PO-4.1` through `PO-4.4` only)
10. `design/product/requirements.md` (reliability, observability, recovery, reconnect, and non-functional consequences only)

Accepted observability rationale in ADRs 0158, 0159, 0161, and 0162 belongs to 7A; 7C consumes its detection and canary consequences. ADR 0164 is a 7B verification handoff, and ADR 0160 is a 7A SLO handoff. ADRs 0153-0155 remain 6D recovery authorities whose evidence 7C consumes. Operator journeys are a selected heading-scoped consumer already allocated to Unit 6C and are not duplicated here.

Inbound dependencies are Units 7A, 7B, 6D, 6C, 6A/6B, 2A/5D, 5C, and Account-owned synthetic identity/JWT authority. Unit 7C supplies exact incident and recovery-readiness evidence outward but does not authorize traffic reopen, redefine operator mutation authority, or own transport, Redis, tick, workflow, or metric schemas. Narrow evidence anchors are the player-experience smoke runner/evidence validators, observability/cardinality validators, readiness/runtime-identity and Gateway observability implementations, Prometheus rules, and focused smoke/readiness/tick/logging proof.

Important review seams are the absent authoritative off-cluster pager and expected-series inventory, unavailable Account synthetic verifier, incomplete end-to-end log queryability, canary lease/browser-path/failure-alert gaps, uncalibrated SLO producers, unproved routed-alert behavior under observability loss, incomplete recovery-controller convergence evidence, and unsettled owner-directed tick/Redis remediation readback.
