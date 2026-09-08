# Whole-Corpus Authority Review Execution Plan

Local ignored execution tracker for phase 4 of the FireMUD design-alignment program. Canonical design and status remain in the repository sources and live implementation trackers. This plan defines review scope, ordering, review effort, PR packaging, and completion mechanics; it creates no technical authority.

The companion `tmp/corpus-review-status.json` ledger and `tmp/corpus-review-status.py` read-only reporter provide the compact live section/PR view. Review counts are raw/useful after main-thread adjudication. Each accepted correction batch or material external seam bumps the affected section's content revision; the Luna floor remains cumulative, while zero-useful confirmations count only on the current revision. Every section review records `reviewMode=whole-section-unrestricted` or `reviewMode=narrow-focused`; only the former can satisfy the section floor or terminal taper, and the reporter shows the two streams separately. The reporter is an orchestration aid, not a technical authority or merge-readiness oracle.

## Whole-Section Review And Implementation Boundary

- A whole-section review covers the complete declared unit manifest and every material handoff discovered from it. It may follow claims into unchanged canonical authority, ADRs, implementation, schemas/migrations, tests, trackers, and proof. A named architecture, runtime, persistence, or cross-owner lens is narrow-focused: it supplements whole-section discovery, never replaces it, and never counts toward section terminality. Repeated independent unrestricted whole-section reviews are mandatory through the recorded floor and useful-finding taper, unless Ben explicitly stops earlier.
- Discovery is unrestricted, but implementation is adjudicated per finding. Corpus PRs fix defects introduced by the PR and may admit a pre-existing correction when it is bounded, low-risk, coherent with the section, and simpler/clearer than extensive drift prose or tracker debt. Defer or split only a substantial capability slice whose persistence, migration, concurrency, design, or proof cost would materially displace corpus review; file count alone is not determinative. Record discovered/deferred implementation gaps separately from fixes admitted to the corpus PR.
- The main worker scopes, adjudicates, inspects delegated diffs, verifies, and coordinates; it does not directly implement repository fixes. When known fixes are queued, normally reserve one subagent lane for delegated fixes and use remaining lanes for unrestricted whole-section discovery.

## Start Gate And Current Priorities

- Ben approved this review shape and terminal model on 2026-08-29. Unit 5A is active on the locally stable retained-input base.
- The independent CI-reliability work merged as PR #2659; it no longer competes for review priority.
- The non-Weather retained semantics were adjudicated and incorporated rather than importing the source branch wholesale. The isolated Weather selector remains pending outside the corpus branch until Ben makes the consequential choice.
- Unit 5A is the first corpus dependency anchor because its primitives have the highest downstream fan-out. It is not a new phase or a separate project.

## Scale And Packaging

- Review scope is a coherent cognitive unit, normally about 8–18 substantive owner, consumer, ADR, product, and tracker documents. Reviewers are not asked to hold an entire PR parcel in one mental model.
- Package adjacent units from one coherent family together: Shared Runtime 5A–5D, Gameplay 2A–2C, Access/Experience 1A–1D, Authoring 3A–3C, Automation 4A–4B, Edge/Delivery 6A–6D, or Observability/Proof 7A–7C. Split a family further when its correction set is already large; do not add an unrelated family merely to fill a parcel.
- The current map contains 23 cognitive units in seven likely PR parcels, covering an estimated 351 allocated substantive sources. The three allocation exemptions are structural/index artifacts, not review units.
- Source estimates are review-input counts, not changed-file predictions. Tests, trackers, indexes, validation collateral, and cross-unit corrections can increase the PR file count.
- Aim to publish at roughly 50–60 changed files and reserve about 30–40 files of review-growth headroom below CodeRabbit's 100-file ceiling. Before opening or materially widening a PR, recount its changed files. Split by coherent contract boundary when necessary, not by arbitrary file count.
- File/PR limits are packaging concerns only: never suppress, weaken, defer, or stop a verified coherent fix to satisfy a cap or preserve a documentation-only parcel. Adjudicate by correctness and coherence; if packaging becomes unwieldy, preserve the finding and solve the shape mechanically through commit, stack, or merge topology without losing findings or distorting scope.
- Once a family PR is active, cycle its included units to their semantic terminals before activating another family. Read-only preparation may run ahead, but corrections from later families stay on an unpublished parked branch and do not widen or distract the active review.
- Keep one corpus PR in hosted CodeRabbit review at a time. Read-only Luna discovery for the next disjoint unit may run while hosted review waits, but edits and publication must not invalidate the active review.

## Areas, Units, And Planned Static Review Cycles

| Parcel | Cognitive unit | Purpose | Estimated sources | Default Luna passes |
|---|---|---|---:|---:|
| 1. Access and experience/social | 1A | Identity, entitlement, and hosted terms | 17 | 3 |
| 1 | 1B | Admission, session continuity, and reconnect | 16 | 3 |
| 1 | 1C | Commands, output, and frontend presentation | 14 | 3 |
| 1 | 1D | Social, communication, and moderation-facing UX | 16 | 3 |
| 2. Gameplay, world, and session | 2A | Tick scheduling and region authority | 18 | 3 |
| 2 | 2B | Mutation and spatial authority | 15 | 4 |
| 2 | 2C | Gameplay entities, effects, and economy | 15 | 3 |
| 3. Authoring and release | 3A | Authored content and extension packaging | 18 | 3 |
| 3 | 3B | Settings, policy, and effective configuration | 15 | 3 |
| 3 | 3C | Release lifecycle and activation | 17 | 3 |
| 4. Automation and scripting | 4A | Script ingress, sandbox, and runtime execution | 18 | 3 |
| 4 | 4B | Scheduling, quotas, reload, and operational fairness | 17 | 3 |
| 5. Shared runtime | 5A | API, message, identifier, tenant, time, and authorization primitives | 17 | 4 |
| 5 | 5B | SQL, migration, schema, and retention | 18 | 3 |
| 5 | 5C | Redis roles and cache/rate-limit semantics | 17 | 3 |
| 5 | 5D | Idempotency, outbox, replay, saga, and workflow patterns | 17 | 3 |
| 6. Edge and platform delivery | 6A | Gateway routes, traffic planes, sharding, and close taxonomy | 16 | 3 |
| 6 | 6B | WebSocket, Telnet, protocol bridge, and session transport | 14 | 3 |
| 6 | 6C | Logging & Admin/operator ingress and action authorization | 18 | 4 |
| 6 | 6D | Environments, deployment, assets, backup, and delivery | 15 | 3 |
| 7. Observability and proof | 7A | Logs, metrics, tracing, SLOs, and degraded operation | 16 | 3 |
| 7 | 7B | Verification boundaries, recovery evidence, and compliance | 12 | 3 |
| 7 | 7C | Incident and operational proof surfaces | 10 | 3 |

Planned static-review baseline: 23 units, about 351 substantive sources, and 72 fresh unrestricted whole-section Luna passes: three successive passes for each ordinary unit and four for 2B, 5A, and 6C. These counts are floors, not caps. Narrow-focused passes are supplemental and do not reduce this obligation. If the final planned pass still yields useful work, fix or explicitly defer it and continue until a fresh unrestricted full-unit pass yields zero genuinely useful findings. CodeRabbit CLI reviews the corrections produced by each unit or coherent local batch, and hosted CodeRabbit reviews the assembled parcel; neither replaces static whole-section taper.

## Per-Unit Review Cycle

1. Materialize the exact unit manifest before dispatch: canonical owners, directly relevant ADR rationale, selected consumers, product consequences, owning tracker, inbound dependencies, outbound handoffs, and unresolved seam questions. Do not create a second decision inventory.
2. Run each unit's planned `whole-section-unrestricted` passes with fresh context and the complete manifest as the review boundary. Prefer successive corrected heads over same-head parallel duplication. Use same-head independent review only for a suspiciously quiet pass, unresolved interpretations, or an explicit high-risk variance check. Record any `narrow-focused` pass separately as supplemental evidence; it cannot replace a whole-section pass or zero.
3. Ask for stable correctness first: contradictions, missing consequential requirements, wrong or competing authority, unsafe current/target confusion, broken handoffs, false proof, and tracker/proof mismatch. Ask secondarily for harmful normative duplication while preserving useful local explanation and domain-specific consequences. Ordinary point-in-time implementation-status synchronization is lower-priority discovery work and must not displace stable contract review.
4. The main thread normalizes findings by semantic issue, verifies them against source evidence, rejects stylistic churn and false deduplication, and adjudicates accepted changes. Fix verified status drift when found so it does not consume later reviews, but do not seek exhaustive status alignment unless the drift creates an unsafe instruction, false implementation claim, impossible proof gate, or stable-contract contradiction. Only genuinely consequential competing target states require Ben.
5. Apply one bounded correction batch through a delegated subagent, then have the main thread inspect every edit and run proportionate validation. Classify each discovered implementation gap as an admitted bounded fix or a deferred/split capability; do not have the main thread implement it directly.
6. After each meaningful correction batch, commit a coherent unit or local batch and run CodeRabbit CLI against the complete active PR diff. Attribute each finding to its owning unit and any shared seam. `--base-commit` or `--dir` is supplemental focused evidence only; neither mode reviews unchanged corpus.
7. Attribute every useful CLI finding to its owning unit and any genuinely shared seam. Fix and cycle until the CLI returns zero genuinely useful work for that unit or shared seam before spending that unit's next fresh Luna pass. A finding owned solely by another active unit does not park a clean unit: keep that clean unit's Luna/fix cycle moving while the next full-PR CLI reviews the combined corrections. A Luna pass that makes no changes creates no new CLI boundary and may proceed directly to the next Luna pass or terminal adjudication.
8. Continue the planned unrestricted whole-section Luna -> delegated fix/defer -> CLI-to-zero -> fresh-unrestricted-Luna cycle through the pass floor. CLI zero establishes only diff cleanliness and never establishes static unit correctness. After the planned count, continue while the latest unrestricted whole-section pass produces genuinely useful static work or material cross-unit changes. A narrow-focused zero does not advance this taper.
9. A unit is ready for parcel integration when all accepted findings are fixed or explicitly deferred, its unrestricted whole-section Luna floor has been met, a fresh unrestricted full-unit pass yields zero genuinely useful findings, the latest full-PR CLI is zero-useful or adjudicated for that unit, and its owner/consumer seams are coherent. Run one additional unrestricted zero-useful confirmation for 2B, 5A, and 6C or whenever a candidate terminal pass is suspiciously quiet. Any useful unrestricted finding resets the unit taper. This is a semantic terminal, not a raw-count target, unless Ben explicitly stops earlier.

For every pass, record the exact head, manifest, `reviewMode`, raw finding count, genuinely useful semantic findings, duplicates/non-issues, admitted fixes, discovered/deferred implementation gaps, and next-pass reason. The 72-pass baseline is deliberate static-corpus spend, but same-head duplication remains exceptional. CLI and hosted findings are adjudicated by usefulness and do not prove the unchanged corpus was reviewed.

## Three-Lane Luna Schedule

- Treat the cognitive unit as the complete review-cycle boundary; never ask a reviewer to hold an entire multi-unit PR parcel as one section.
- Prefer one Luna pass per unit head so the next pass reviews the accepted corrections rather than merely duplicating findings. A second same-head lane is reserved for a suspiciously quiet result, competing interpretations, or a deliberate high-risk variance check.
- Give each active unit at most one Luna review or disjoint fix lane at a time so successive passes review accepted corrections instead of duplicating same-head work. Other lanes may review or fix other dependency-safe units, build exact manifests, or inspect a bounded shared seam. When known fixes are queued, reserve one lane for delegated fixes and use remaining lanes for unrestricted whole-section discovery; do not fill every lane with narrow review.
- CodeRabbit CLI runs on the combined active PR diff between correction checkpoints. Attribute its findings per unit: cycle a unit or shared seam to zero useful work before that unit's next Luna pass, but do not wait for unrelated units to be globally clean. CLI does not cancel any unit's remaining planned static passes.
- Keep at most three substantive Luna review/fix lanes active, matching available subagent capacity, and reduce that number whenever main-thread adjudication or shared-seam complexity cannot keep up. A unit merely awaiting the shared CLI or hosted checkpoint does not consume a Luna lane and does not prevent a dependency-safe next unit from using an idle lane. Do not accumulate findings faster than the main thread can verify and integrate them.
- Do not pipeline across a known unsettled dependency that would materially invalidate the downstream review. A pending finding elsewhere is not such a dependency by itself. For 5A/5B/5C/5D, classify each finding and handoff precisely; continue an independently clean unit unless a specific unresolved 5A primitive changes its reviewed contract. Apply the same evidence-based rule to 2A/2B, 1A/6C, and 6D/7C rather than imposing a parcel-wide stop.
- Parallelism is section-aware: independent unrestricted whole-section discovery and delegated fix lanes feed one shared full-PR CLI cycle, while the main thread owns attribution, cross-unit adjudication, integration, and validation but does not directly edit repository fixes.
- Required operating overlap: when safe useful work exists, keep one hosted review, at most one CLI review, independent delegated fix and verification lanes, and read-only preparation of the next dependency-safe local slice running concurrently. Once primary validation is green for a coherent parcel, publish it without waiting for redundant verification so hosted review and CI can overlap the remaining independent checks. While hosted review is active, continue safe local work but do not push a review-invalidating change.
- Preserve one preemptible lane for accepted edits or verification. If an accepted finding needs edits and all lanes are busy, wait for a lane or interrupt the least valuable preemptible review; the main worker scopes, adjudicates, inspects, integrates, and validates but never performs bulk/manual fixes. Avoid duplicate reviews and do not manufacture work to fill a slot. If useful work appears available while a lane remains intentionally idle, record the concrete dependency, overlap, adjudication, or validation reason in the status handover.

## CodeRabbit Role And PR Taper

- CodeRabbit CLI review modes operate on Git changes; `--dir` filters changed paths and is not an initial static scan of unchanged corpus folders. Luna supplies the static whole-corpus discovery baseline.
- CodeRabbit CLI reviews the current local/committed parcel diff and can be cycled while hosted review runs. It never substitutes for hosted evidence.
- Publish a coherent parcel after one or two adjacent units have seeded worthwhile corrections rather than waiting for every provisional unit. Early hosted rounds provide development feedback; they do not count toward final merge taper if later scope is added.
- While hosted review runs, commit local unit fixes, cycle full-PR CLI review, continue unit-local Luna review on the cleaned local head, and perform read-only discovery of the next disjoint unit. Publish no review-invalidating commit until hosted terminates; then adjudicate its possibly stale findings into the combined local batch before pushing.
- Maintain a unit ledger for every active section: Luna pass/head and usefulness, accepted fixes, latest full-PR CLI usefulness attributed to the unit, hosted findings attributed to it, and any clean checkpoint invalidated by a shared-owner or seam change.
- Hosted CodeRabbit reviews one published current head at a time. While it is active, fixes may be prepared locally but no review-invalidating commit is pushed until it reaches a terminal completion, failure, skip, or rate-limit state.
- Record hosted findings separately as stable-contract work (safety, authority, identity/scope, API semantics, proof correctness, or consequential deduplication) and status-maintenance work. Hosted merge-readiness taper is two consecutive current-head checkpoints with zero stable-contract findings by default; verified status findings are still fixed, but status-only rounds do not automatically reset the stable-contract taper. One status-only round after stable work has already reached zero may qualify with main-worker judgment because later implementation and corpus passes will refresh status. Ben may explicitly choose a faster merge boundary.
- Raw finding counts, duplicated CLI findings, rate-limit responses, and an unmet review gate are not completed hosted reviews. A substantive hosted review that returns findings succeeded; the fixes and merge gate remain outstanding.
- All unresolved current threads and summary-only findings must be adjudicated, required CI must pass, and the canonical CodeRabbit checker must pass before merge readiness is reported.

## Dependency And Parcel Order

1. Resolve CI reliability independently before corpus execution.
2. Adjudicate retained source inputs and route them only if accepted:
   - weather aggregate selection: 2B with a 5A seam;
   - deterministic plan-root identity: 5A/5D with a 2B seam;
   - destructive smoke/reset isolation and retained failure evidence: 6D with 7C.
3. Start Shared Runtime with 5A. Once its vocabulary and primitive ownership are stable, 5B, 5C, and 5D discovery may proceed independently. Parcel 5 begins near the preferred starting-size ceiling, so changed-file growth determines whether it remains one PR or becomes two coherent PRs.
4. Gameplay/world/session follows: keep 2A and 2B sequential or tightly coordinated; 2C follows their authority decisions.
5. Coordinate Access 1A with operator-ingress 6C. Within Parcel 1, 1B follows admission/session authority; 1C and 1D may be discovered in parallel where manifests are disjoint.
6. For Authoring, 3A and 3B may be discovered in parallel; 3C follows their content/configuration authority.
7. Automation units 4A and 4B may be discovered in parallel, but correction batches must respect established 5D and 2A handoffs.
8. Edge units 6A and 6B may be discovered in parallel; 6D is coordinated with 7C.
9. Observability/proof 7A, 7B, and 7C close evidence surfaces and lead into the final whole-corpus seam pass.

This is the logical dependency order, not a promise that parcel numbers must be merged numerically. The active PR remains the single integration/review focus; read-only work on the next disjoint unit is the safe source of parallelism.

## Unit 5A Exact Manifest

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

## Unit 5B Exact Manifest (Active)

Unit 5B's substantive boundary is the following 18 allocated design/product/tracker sources. Implementation migrations, repositories, jobs, tests, and generated jOOQ are evidence for a concrete current-state claim only; they do not occupy the static corpus manifest.

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

Review the canonical SQL/Flyway/schema and retention contracts against owner-local durable-state consequences. Preserve service-owned DDL and cleanup while checking jOOQ/Flyway convergence, isolated schemas and adopter-local saga migrations, objective expand/migrate/contract gates, typed identifier-migration mappings, namespace-stable versus replaceable instance identity, ADR 0163 terminality/blocker/hold/horizon/safe-watermark rules, retained-version contraction constraints, and cleanup consequences across Account, Automation, Entity, Game Session, Social, and World.

`design/architecture/system-architecture-transactions.md` is a 5D handoff anchor rather than substantive 5B scope. ADR 0149 is a directly relevant Social consumer anchor, not shared-retention authority. Gateway `route_config` ownership is a seam to classify between 5B persistence consequences and 6A/6D route/deployment ownership, not a reason to load the Gateway corpus. Redis belongs to 5C; transaction/outbox/replay/saga/Temporal behavior to 5D; deployment/reset/backup execution to 6D; verification/compliance and recovery evidence to 7B/7C.

## Unit 5C Exact Manifest (Pass 1 Active)

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

## Unit 5D Exact Manifest (Pass 1 Active)

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

## Unit 1A Exact Manifest (Prepared; Static Review Not Yet Started)

Unit 1A's substantive boundary is the following 17 identity, membership, entitlement, billing, and hosted-terms sources:

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
12. `design/architecture/decisions/adr-0025-explicit-open-enrollment-membership.md`
13. `design/architecture/decisions/adr-0026-global-roles-do-not-grant-gameplay-authority.md`
14. `design/architecture/decisions/adr-0028-differentiated-entitlement-freshness.md`
15. `design/architecture/decisions/adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md`
16. `design/architecture/decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md`
17. `design/architecture/decisions/adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md`

Review Account's sole authority for global identity, credentials, explicit membership, entitlement, Creator Party and terms evidence; account-global versus tenant-scoped records; public versus private/playtest access; strict and continuity entitlement freshness; authentication path separation; role versus gameplay authority; hosted creator mutation currentness; changed-terms decline and prior-rights continuity; and payment evidence versus runtime entitlement. Unit 1A consumes 5A token/authority primitives and hands gameplay binding to 1B and operator reference execution to 6C. Refresh for material 5A, 6C, 1B, 5B/5D, 3A, or billing/provider changes. Repository licence/terms files remain legal-policy handoffs rather than technical authority sources.

## Unit 1B Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 1C Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 1D Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 2A Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 2B Exact Manifest (Prepared; Non-Weather Review Eligible)

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

## Unit 2C Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 3A Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 3B Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 3C Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 4A Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 4B Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 6A Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 6B Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 6C Exact Manifest (Prepared; Static Review Not Yet Started)

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

Review normative machine-readable route policy versus explanatory inventory; default-deny incomplete coverage; Gateway admission versus Logging & Admin mutation ingress; human versus automation authorization references; complete typed action/digest/scope/fence evidence; single receiving-boundary redemption; distinct control/policy/owner/appeal identities; durable phase and uncertain-outcome reconciliation; authority preservation; fixed moderation categories and owner-local enforcement; tenant routine authority versus platform break-glass; bounded Redis maintenance; and core-control availability during observability loss. Unit 6C consumes 1A Account/reference authority, 5A auth/digest primitives, 6A route exposure, 2A/3C owner actions, 5B-5D durability, 6D credential delivery, and 7A-7C proof. Four fresh passes plus an additional zero-useful confirmation remain required for this high-risk unit.

## Unit 6D Exact Manifest (Prepared; Static Review Not Yet Started)

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

## Unit 7C Exact Manifest (Prepared; Static Review Not Yet Started)

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

Accepted rationale anchors are ADRs 0158, 0159, 0161, 0162, and 0164. ADR 0160 is a Unit 7A normative SLO handoff; ADRs 0153-0155 remain Unit 6D recovery authorities whose evidence 7C consumes. Operator journeys are a selected heading-scoped consumer already allocated to Unit 6C and are not duplicated here.

Inbound dependencies are Units 7A, 7B, 6D, 6C, 6A/6B, 2A/5D, 5C, and Account-owned synthetic identity/JWT authority. Unit 7C supplies exact incident and recovery-readiness evidence outward but does not authorize traffic reopen, redefine operator mutation authority, or own transport, Redis, tick, workflow, or metric schemas. Narrow evidence anchors are the player-experience smoke runner/evidence validators, observability/cardinality validators, readiness/runtime-identity and Gateway observability implementations, Prometheus rules, and focused smoke/readiness/tick/logging proof.

Important review seams are the absent authoritative off-cluster pager and expected-series inventory, unavailable Account synthetic verifier, incomplete end-to-end log queryability, canary lease/browser-path/failure-alert gaps, uncalibrated SLO producers, unproved routed-alert behavior under observability loss, incomplete recovery-controller convergence evidence, and unsettled owner-directed tick/Redis remediation readback.

## Parcel And Program Completion

After each parcel:

- inspect inbound/outbound owner handoffs rather than asking one reviewer to reread the whole parcel;
- reconcile trackers only where target, implementation, proof, or remaining-gap status actually changed;
- run focused validation plus the repository-required documentation and PR checks;
- record which units completed, material residual seams, and the next dependency-ready unit in this file.

After all parcels:

- verify canonical-owner uniqueness and secondary handoffs across the whole corpus;
- verify product/architecture coverage, mixed-document heading ownership, tracker/proof alignment, allocation, links, and architecture contracts;
- revisit the dispositions of all three retained design inputs;
- resolve genuine residual consequential decisions with Ben;
- complete the phase-5 reconciliation/closeout and only then declare the post-ADR design-alignment mini-project complete.

## Execution Status

| Item | Status | Notes |
|---|---|---|
| CI-reliability reconciliation | Merged | PR #2659 merged on 2026-08-29 from clean published head `90c7cd793`. Its terminal 13:34 hosted request completed at 13:42 with no actionable comments on the current head; the updated CodeRabbit summary recorded the reviewed range through `90c7cd793`. All review threads were resolved and current-head CI was green before merge. |
| Retained design source audit | Weather choice pending | The focused Shared Runtime tree preserves the adjudicated non-Weather retained content from `3be751a91` and `2e4ad9039`, including its plan-root, root-versus-child guard, smoke/reset, owner-dedup, and tracker-status corrections; the split rebuilt commit topology, so preservation is tree/content based rather than ancestry based. Six isolated Weather files remain outside the active parcel pending Ben's consequential selector choice. Accepting region-scoped Weather requires new exact review provenance plus consistent ADR0054/transaction/inventory authority, canonical UUID-to-row identity and exact tenant/game/logical-region binding, mandatory mutation fences, deterministic activation seeding, explicit scheduling/direct-handler drift, and ADR0060-owned dedup. |
| Whole-corpus plan | Approved | Per-unit Luna floors, section-aware three-lane Luna scheduling, shared full-PR CLI attribution, and semantic terminals accepted on 2026-08-29 |
| Unit 5A manifest and reviews | Active; fresh static passes remain | Pass 1 produced `3/3 useful`. Its accepted corrections and the focused hosted follow-through are published at `af4e14548`. The four-pass high-risk floor and an additional zero-useful confirmation still apply; a useful finding resets taper. |
| Unit 5B manifest and reviews | Static terminal reached; CLI attribution clean so far | Passes 1-3 produced `1/1`, `3 raw / 2 useful`, and `9 raw / 7 useful`. Fresh pass 4 reported 16 raw observations; bounded adjudication found `0` genuinely new corpus corrections: six were direct pass-3 repeats and the rest were already explicit implementation/proof gaps or two nonissues. The planned floor and fresh zero-useful terminal are therefore met. Post-split CLI rounds through round 4 have produced no new 5B-owned corpus work; refresh only if a later shared-owner correction materially changes its SQL/migration/retention seams. |
| Unit 5C manifest and reviews | Active; pass 3 produced `2/2 useful`; terminal zero remains | Pass 1 produced `12 raw / 7 useful`; pass 2 produced `14 raw / 6 useful`; pass 3 narrowed to two precise Redis-operations authority corrections: class-specific reset handling and Redis promotion projection versus durable owner authority. Both are fixed locally after pass 3. This is a strong `7 → 6 → 2` useful-finding taper but not the required fresh zero-useful terminal. Ben temporarily authorized otherwise-idle Luna lanes to proceed before complete CodeRabbit quiet because a usage reset is expected on 2026-08-31. |
| Unit 5D manifest and reviews | Active; pass 2 running, pass 3 and terminal zero remain | Pass 1 produced `10 raw / 9 useful`: retention-owner contradiction, effect/backlog terminality, the accepted-command loss boundary, World Temporal identity/current-state clarity, Saga/Temporal and dashboard ownership, and the current non-durable `onCommand` publisher seam. All nine are preserved on focused head `af4e14548`. Under Ben's temporary otherwise-idle-lane authorization, fresh pass 2 is reviewing the current post-round-4 local tree; pass 3 plus a fresh zero-useful pass remain. |
| Corpus PR parcels | PR #2661 focused at published `2fb8f9c19`; local `6977d1c5e` plus current fixes; downstream seed parked at `2c54fae88` | The oversized 104-file prospective tree was split without content loss. Active #2661 contains only the coherent Shared Runtime 5A-5D family at 50 changed files; the remaining 55-file tree is preserved on `codex/corpus-review-downstream-seed` and receives no active review until #2661 is terminal. Post-split focused CLI rounds produced `6/6 useful`, `6/6 useful`, `4/4 useful`, and `8 raw / 7 useful`; the sole round-4 nonissue was an already-correct Transactions backlog-state suggestion. All seven accepted round-4 findings are fixed and locally committed at `6977d1c5e`. The hosted full review requested at 00:48 completed at 01:04 on exact published head `2fb8f9c19` with `8 raw / 6 useful`: one finding is already fixed locally, one is a false scope contradiction, and six require narrow authority/current-state/wording corrections. CLI round 5 attempted at 01:03 and hit a 32-minute quota wait without consuming a review; a zero-lane local timer is due at 01:35. |
| Unit 2A preparation | Parked downstream after pass 1 | The 18-source pass produced `11 raw / 9 useful`; accepted documentation/current-state corrections are preserved on `codex/corpus-review-downstream-seed`. No further review runs while Shared Runtime #2661 is active. |
| Unit 2B preparation | Parked downstream after pass 1 | The 15-source non-Weather pass produced `11 raw / 7 proposed`; main accepted four genuinely new corrections and classified MOVE, DROP/PICKUP, and LOOK as already accurately tracked gaps. Corrections are preserved on `codex/corpus-review-downstream-seed`; Weather remains blocked and no further review runs while #2661 is active. |
| Unit 2C preparation | Parked downstream after pass 1 | The 15-source non-Weather pass produced `2 raw / 1 useful`; the bounded correction is preserved on `codex/corpus-review-downstream-seed`. No further review runs while #2661 is active. |
| Unit 3A preparation | Parked downstream after pass 1 | Pass 1 produced `7 raw / 6 useful`; bounded documentation/tracker corrections are preserved on `codex/corpus-review-downstream-seed`, with no runtime/proto implementation added. |
| Unit 3B preparation | Parked downstream after pass 1 | Pass 1 produced `12 raw / 6 useful`; bounded documentation/generator/assertion corrections are preserved downstream, with no runtime implementation added. |
| Unit 3C preparation | Parked downstream after pass 1 | Pass 1 produced `2/2 useful`; both bounded documentation corrections are preserved downstream. |
| Unit 4A preparation | Parked downstream after pass 1 | Pass 1 produced `11 raw / 2 useful`; bounded documentation corrections are preserved downstream. |
| Unit 4B preparation | Parked downstream after pass 1 | Pass 1 produced `3 raw / 2 useful`; both bounded documentation corrections are preserved downstream. |
| Unit 1A preparation | Parked downstream after pass 1 | Pass 1 produced `1/1 useful`; the bounded overview correction is preserved downstream. |
| Unit 1B preparation | Parked downstream after pass 1 | Pass 1 produced `1/1 useful`; the bounded reconnection clarification is preserved downstream. |
| Unit 1C preparation | Parked downstream after pass 1 | Pass 1 produced `2/2 useful`; bounded API/model/tracker corrections are preserved downstream. |
| Unit 1D preparation | Parked downstream after pass 1 | Pass 1 produced `10 raw / 8 useful`; bounded documentation/tracker corrections are preserved downstream. |
| Unit 6A preparation | Parked downstream after pass 1 | Pass 1 produced `8 raw / 3 useful`; bounded corrections are preserved downstream. |
| Unit 6B preparation | Parked downstream after pass 1 | Pass 1 produced `8 raw / 4 useful`; bounded documentation/tracker corrections are preserved downstream, with no runtime implementation added. |
| Unit 6C preparation | Parked downstream after pass 1 | Pass 1 produced `5 raw / 3 useful`; bounded corrections are preserved downstream. Its later high-risk multi-pass plus zero-useful confirmation terminal remains. |
| Unit 6D preparation | Parked on existing branch after pass 1 | The coordinated 18-source environment/deployment/asset/backup pass produced nine useful findings. Its corrections remain preserved at `c2af10ee7` on `codex/corpus-review-6d-7c`, but that branch's old stack base must be reconciled later. No PR, CodeRabbit request, rebase, or further review is active while #2661 is being completed. |
| Unit 7C preparation | Parked on existing branch after pass 1 | The coordinated 10-source incident/operational-proof pass produced six useful findings. Its corrections remain preserved at `c2af10ee7` with 6D, but no further work occurs until the active Shared Runtime family is terminal and later parcel topology is chosen. |
| Final whole-corpus pass | Not started | Runs after all parcel integrations |
