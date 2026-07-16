# Shared Runtime, Service Contracts, and Persistence

## Current Status

The implemented record below is the canonical reader-facing account of the shared runtime, service-contract, orchestration, persistence, and workflow boundaries represented by this tracker. The source appendix is retained unchanged as provenance and audit evidence. The record distinguishes live implementation from bounded follow-up work; it does not claim that every future service, runtime consumer, or workflow family has already adopted these conventions.

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [Shared Time, Duration, and Scheduler Semantics Vertical Slice](../vertical-slices/02.13.11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice.md) - Shared time and duration semantics | first bounded target boundary is complete: cross-service proto time-field naming guard now blocks ambiguous units at contract boundaries | 1-99 | [source evidence](#source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99) |
| [`02.18` Service Boundary and Audit Hardening](../vertical-slices/02.18-task-list-service-boundary-and-audit-hardening-vertical-slice.md) - Cross-service boundary and audit hardening | complete for the original static-audit hardening set; later durable runtime substrate remains planned separately | 1-89 | [source evidence](#source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89) |
| [`02.18.1` Separate Audit Logging From Moderation Mutations](../vertical-slices/02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md) - Audit and moderation service boundary | complete for the known audit/logging misuse | 1-55 | [source evidence](#source-02-18-1-task-list-audit-log-and-moderation-separation-vertical-slice-1-55) |
| [`02.18.12` Internal Service Identity and Session Attestation](../vertical-slices/02.18.12-task-list-internal-service-identity-and-session-attestation-vertical-slice.md) - Internal service identity and session attestation | complete for the current delegated-gameplay boundary set | 1-60 | [source evidence](#source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60) |
| [`02.18.14` Moderation Policy Definition and Enforcement Split](../vertical-slices/02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md) - Shared moderation policy contract and non-destructive model | complete | 1-18, 29-34, 42, 49 | [source evidence](#source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-1-18-29-34-42-49) |
| [Orchestration Decomposition and Pre-v1 Simplification Vertical Slice](../vertical-slices/02.18.19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice.md) - Cross-service orchestration decomposition | implemented; the HTTP shim is gone, the targeted Game Session / Automation constructor seams are cleaned up, `GameSessionControlPlaneGrpcService` now delegates | 1-149 | [source evidence](#source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149) |
| [`02.18.2` Internal Blocking gRPC Auth Propagation](../vertical-slices/02.18.2-task-list-internal-grpc-auth-propagation-vertical-slice.md) - Internal gRPC authentication propagation | complete for the audited blocking-client set | 1-72 | [source evidence](#source-02-18-2-task-list-internal-grpc-auth-propagation-vertical-slice-1-72) |
| [Shared Saga Ownership and Control-Plane Facade Thinning Vertical Slice](../vertical-slices/02.18.20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice.md) - Shared saga repository ownership and module layering | implemented | 1-7, 10-22, 25-37, 40-41 | [source evidence](#source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-1-7-10-22-25-37-40-41) |
| [`02.18.3` Workflow Transaction Boundary Hardening](../vertical-slices/02.18.3-task-list-workflow-transaction-boundary-hardening-vertical-slice.md) - Workflow transaction boundaries | implemented for the currently audited workflow and session-lifecycle paths | 1-79 | [source evidence](#source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79) |
| [`02.18.4` World and Entity Service Boundary Auth](../vertical-slices/02.18.4-task-list-world-and-entity-service-boundary-auth-vertical-slice.md) - World and entity service boundary authentication | implemented | 1-47 | [source evidence](#source-02-18-4-task-list-world-and-entity-service-boundary-auth-vertical-slice-1-47) |
| [`02.18.5` gRPC App-Error Consistency Hardening](../vertical-slices/02.18.5-task-list-grpc-app-error-consistency-hardening-vertical-slice.md) - gRPC application-error contract | implemented for the currently visible service set | 1-61 | [source evidence](#source-02-18-5-task-list-grpc-app-error-consistency-hardening-vertical-slice-1-61) |
| [jOOQ and Flyway Persistence Convergence Vertical Slice](../vertical-slices/02.19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-122 | [source evidence](#source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122) |
| [Shared jOOQ Build, Codegen, and Runtime Foundation Vertical Slice](../vertical-slices/02.19.1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented at the first-adopter foundation boundary | 1-61 | [source evidence](#source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61) |
| [Hibernate and JPA Runtime Removal Vertical Slice](../vertical-slices/02.19.10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-34 | [source evidence](#source-02-19-10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice-1-34) |
| [Shared Persistence Contract and Saga Topology Cleanup Vertical Slice](../vertical-slices/02.19.11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-42 | [source evidence](#source-02-19-11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice-1-42) |
| [Automation Scripting jOOQ Migration Vertical Slice](../vertical-slices/02.19.2-task-list-automation-scripting-jooq-migration-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-35 | [source evidence](#source-02-19-2-task-list-automation-scripting-jooq-migration-vertical-slice-1-35) |
| [Game Session jOOQ Migration Vertical Slice](../vertical-slices/02.19.3-task-list-game-session-jooq-migration-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-28 | [source evidence](#source-02-19-3-task-list-game-session-jooq-migration-vertical-slice-1-28) |
| [Game Design jOOQ Migration Vertical Slice](../vertical-slices/02.19.4-task-list-game-design-jooq-migration-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-20 | [source evidence](#source-02-19-4-task-list-game-design-jooq-migration-vertical-slice-1-20) |
| [Entity Management jOOQ Migration Vertical Slice](../vertical-slices/02.19.5-task-list-entity-management-jooq-migration-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-20 | [source evidence](#source-02-19-5-task-list-entity-management-jooq-migration-vertical-slice-1-20) |
| [Logging Admin jOOQ Migration Vertical Slice](../vertical-slices/02.19.6-task-list-logging-admin-jooq-migration-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-22 | [source evidence](#source-02-19-6-task-list-logging-admin-jooq-migration-vertical-slice-1-22) |
| [World Management jOOQ Migration Vertical Slice](../vertical-slices/02.19.7-task-list-world-management-jooq-migration-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-22 | [source evidence](#source-02-19-7-task-list-world-management-jooq-migration-vertical-slice-1-22) |
| [Social Groups jOOQ Migration Vertical Slice](../vertical-slices/02.19.8-task-list-social-groups-jooq-migration-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-24 | [source evidence](#source-02-19-8-task-list-social-groups-jooq-migration-vertical-slice-1-24) |
| [Account Service jOOQ Migration Vertical Slice](../vertical-slices/02.19.9-task-list-account-service-jooq-migration-vertical-slice.md) - Shared jOOQ, Flyway, and SQL persistence convergence | implemented | 1-21 | [source evidence](#source-02-19-9-task-list-account-service-jooq-migration-vertical-slice-1-21) |
| [Temporal Control-Plane Workflow Convergence Vertical Slice](../vertical-slices/02.20-task-list-temporal-control-plane-workflow-convergence-vertical-slice.md) - Temporal control-plane workflow convergence | implemented | 1-80 | [source evidence](#source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80) |
| [Temporal Workflow Foundation and Common Contracts Vertical Slice](../vertical-slices/02.20.1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice.md) - Temporal control-plane workflow convergence | implemented at the shared-foundation boundary | 1-35 | [source evidence](#source-02-20-1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice-1-35) |
| [Saga and Temporal Boundary Cleanup Vertical Slice](../vertical-slices/02.20.4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice.md) - Temporal control-plane workflow convergence | implemented | 1-21 | [source evidence](#source-02-20-4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice-1-21) |
| [Temporal Operator Surface and Contract Truthfulness Cleanup Vertical Slice](../vertical-slices/02.20.5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice.md) - Temporal control-plane workflow convergence | implemented | 1-63 | [source evidence](#source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63) |

## Canonical Design Sources

- [gRPC](../../architecture/system-architecture-grpc.md) defines authenticated internal transport and normal-response application errors.
- [Database migrations](../../architecture/system-architecture-database-migrations.md) defines Flyway history and relational persistence conventions.
- [Temporal workflows](../../architecture/system-architecture-temporal-workflows.md) defines durable workflow boundaries and operator truth.
- [Scripting runtime execution](../../architecture/system-architecture-scripting-runtime-execution.md) and [scheduler/timers](../../architecture/system-architecture-scripting-scheduler-and-timers.md) consume the shared timing and workflow model.
- [Logging and monitoring](../../architecture/system-architecture-logging-monitoring.md) defines audit, failure, and observability expectations at service boundaries.

## Consolidated Implementation Record

### Time Domains, Duration Contracts, and Scheduling

FireMUD has two declared time domains. Wall-clock time is used for authentication/session/operator expiry behavior such as OTP expiry, reconnect grace, and AFK policy. Gameplay-clock time is used for tick-relative mechanics such as cooldowns, buffs, temporary conditions, and action state. Cross-service contracts must declare both domain and unit; the shared vocabulary includes `occurredAt`, `expiresAt`, `duration`, `cooldownEndsAt`, and `nextEligibleAt`, with timestamp-style fields for wall-clock behavior and tick-relative fields such as `remainingTicks`, `appliesAtTick`, and `expiresAtTick` for gameplay behavior. A cross-service duration name must still declare its unit or domain.

The live contract guard is `dev-tools/validation/check-proto-time-fields.py`, wired into root verification through `checkProtoTimeFields`. It rejects ambiguous proto names including bare `time`, `timeout`, `expires`, `expiry`, `duration`, and `cooldown` unless the name declares an accepted timestamp, unit, or gameplay-tick suffix such as `_at`, `_ms`, `_seconds`, `_tick`, or `_ticks`. The checked-in proto corpus currently passes. Authored action metadata carries gameplay cooldowns as `cooldownTicks`, never milliseconds.

This is a contract guardrail, not a central timing or job platform. Runtime cooldown state, shared scheduled-effect execution, richer gameplay-clock APIs, and broad adoption by buffs/conditions remain consumer-driven follow-up work. The current tick pulse scheduler remains bounded fan-out: at most one pending or running tick exists per session, overloaded pulses merge or skip rather than accumulating debt, and merges, rejections, and scheduler pressure are observable through the implemented threshold-aware operator-proof contract. Separately, `TickQueueControlService` owns live Redis queue/lease coordination and queue-admin controls for durable tick execution; it does not change the bounded pulse-scheduler policy. Whether pulse scheduling needs a different queue/lease scheduler remains a separate follow-up requiring load evidence.

### Authenticated Internal Boundaries

The canonical caller-side pattern for secured internal blocking gRPC is the shared `BlockingGrpcStubCustomizer` seam in common platform code, implemented by common security when a local `JwtUtil` is available. Account, Logging & Admin, World Management, Game Design, Social Groups, Game Logic, and Game Session clients use this path for secured downstream calls; focused proof covers Logging & Admin to Account, Logging & Admin to Game Session, and Game Design to Automation Scripting. `TcpProxyEventClient` is an intentional raw-stub exception because it is an ingress-side Telnet event bridge, not a normal authenticated business client. Any new raw-stub exception must be role-justified and documented.

When no player or operator caller context exists, `GrpcClientAuth` mints explicit minimally privileged internal-service identity claims: `internalService`, `serviceName`, and `serviceInstanceId`. Shared parsing preserves these claims in `SessionContext`; absent context no longer silently becomes `platformAdmin`. This distinguishes operator, tenant-scoped, and background/service work for authorization and audit.

Game Session owns the first delegated-gameplay authority seam through `GameplaySessionAttestationService`. It issues signed `GAMEPLAY_SESSION` attestations and bounded `INTERNAL_PROBE` attestations. Live Game Session to World, Entity, and Game Logic gameplay RPCs carry the attestation, and downstream services validate the attestation together with internal-service identity rather than relying on generic bearer-role inference alone. Game Session remains authoritative for player-session-to-gameplay delegation; invalid or missing attestations are rejected.

World Management and Entity Management now have service-local auth configuration and JWT interceptors on their gRPC boundaries, baseline auth on exposed REST boundaries, and tenant-access checks on tenant-scoped REST/gRPC operations, including the Entity friend REST path. Tenant failures return normal `PERMISSION_DENIED` `ErrorDetail` payloads rather than becoming `INTERNAL`. Their tests assert auth wiring and tenant enforcement, and protected internal callers use the shared auth propagation seam.

Shared Security provides conditional JWT-secret reload through `ReloadableJwtUtil` and `JwtSecretWatcher` when a service configures `FIREMUD_AUTH_JWT_SECRET_PATH`. This is distinct from JWKS publication and does not provide rotation-job orchestration, key-overlap management, or proof that every deployment hot-reloads validators.

### gRPC Application Errors and Observability

Application failures use normal response payloads containing `ErrorDetail`; `GrpcAppErrors` logs warnings, increments bounded `grpc.app_error` metrics tagged by error code, and tags spans. Transport `onError` is reserved for transport or infrastructure failure, not routine authorization, validation, or business outcomes. The visible audited service set now follows this contract across Account, Gateway Management, current admin services, Social Groups, Notification, Payment, Virtual Currency, and Game Session, including lifecycle failures caused by runtime-state/dependency validation. The obsolete transport-error admin aspect was removed in favor of explicit in-band admin-role guards.

### Audit, Moderation, and Authority Boundaries

Logging & Admin exposes dedicated non-destructive `CreateLogEvent` ingress. Account logging for creation and payment uses that RPC and the `LogEvent` persistence path; it does not fabricate moderation fields, delete accounts, or stop sessions. Moderation mutation remains a separate explicit operator/admin operation. Focused tests prove both the log-event behavior and the absence of destructive account/session effects.

Logging & Admin records moderation policy actions and exposes internal `EvaluateModerationPolicy`. Game Session owns enforcement of `GAMEPLAY_ADMISSION` during `PLAY`; Social & Groups owns `CHAT_SEND` enforcement before chat persistence or publication. Policy definition, distribution/evaluation, runtime enforcement, and audit are therefore separate authorities. Broader staff capability/RBAC redesign, appeals/case-management UX, and durable moderation UI are not implemented by this boundary.

Those shared audit and moderation-policy seams do not constitute a complete Account security-lock, ban, suspension, operator-recovery, or appeal workflow. Account owns its security-state transitions and enforcement; the unfinished cross-service operator flow remains explicit rather than being attributed to Logging & Admin.

### Transactions, Saga Ownership, and Runtime Truth

The audited purchase and moderation workflows no longer use broad service-level `@Transactional` boundaries across remote effects. Payment creation/follow-on logging and moderation record/account/session mutations run as explicit saga steps. Focused failure tests prove payment compensation after post-payment logging failure and moderation-record compensation after downstream shutdown failure. Existing account notification and social/chat publication paths use narrow local commit followed by outbound effect seams; the boundary does not claim a repo-wide outbox rollout.

Game Session lifecycle uses staged durable state instead of remote Redis/runtime work in `beforeCommit`: start, stop, and restart stage `STARTING`/`STOPPING`, validate dependencies and mutate runtime state after the staging transaction commits, then write final `RUNNING`/`STOPPED` in a second local transaction. Failure compensates staged rows/runtime state, and replacement-start rollback snapshots the previous running session so failed replacement restores prior runtime truth. Lifecycle failures occur before a false final state is committed and are represented as normal application errors.

Saga persistence has one explicit shared owner. `CommonSagaAutoConfiguration` creates the canonical `SagaInstanceRepository` and `SagaStepRepository` when saga entities are present; `DatabaseAutoConfiguration` no longer synthesizes them, `common-data-runtime` no longer depends on `common-saga` for that hidden seam, and service-local same-name wrappers have been removed. Shared saga repositories address `${serviceSchema}.saga_instance` and `${serviceSchema}.saga_step` explicitly. Repository integration proof remains valid after PostgreSQL `search_path` is forced to `public`, so correctness does not depend on ambient schema search paths.

### Orchestration and Control-Plane Shape

Control-plane gRPC facades are transport/auth/error boundaries over owning collaborators. The Game Session façade delegates to `GameSessionRuntimeControlPlaneReadService`, `GameSessionRemoteControlPlaneService`, `GameSessionAdmissionPointerControlPlaneService`, `GameSessionOperatorControlPlaneService`, and `GameSessionVersionUpgradeControlPlaneService` for runtime ownership/status reads, remote follow-up queries and scheduling, admission-pointer and prepared-cutover lifecycle, operator pin/purge/pause-resume actions, and version-upgrade compatibility preparation. The HTTP command shim was removed; the canonical gameplay ingress set is text command, WebSocket, and gRPC. Test-only production constructor overloads were removed from the targeted Game Session and Automation seams in favor of explicit test collaborators/builders.

`TickServiceImpl` now orchestrates explicit collaborators: `TickQueueControlService` owns Redis queue/lease and queue-admin controls; `TickBatchExecutionService` owns durable batch/effect transitions, replay mismatch terminalization, stale-fence requeue, and queue-source stamping; `TickStagingService` owns selection, replay/sealed-manifest restoration, remote-follow-up claim/drain, and selected-work manifests; `TickRuntimeProgressService` owns gauges, timeout/paused-result reconciliation, tick advancement, and Automation progress publication. Direct seam tests cover these responsibilities. `AutomationScriptingControlPlaneGrpcService` delegates event catalog, patch/admission/schedule/dead-letter, and plugin-runtime flows to `AutomationEventControlPlaneService`, `AutomationPatchControlPlaneService`, and `AutomationPluginControlPlaneService`, with direct seam tests.

Pre-v1 compatibility paths in the audited seams now have one current truth: Automation patch-status/readiness reads use readiness projections without nullable projection or durable fallback merging; fresh-schema `genrev:legacy` and `legacy:` effect-key sentinels are replaced by canonical generation revisions and deterministic `effect:<effectId>` keys. Migration baseline reset/squash execution is owned by the separate Flyway/reset slice, not this orchestration boundary. Game Session downstream test-fixture dependencies are explicit fixture-local implementation dependencies rather than transitive `testFixturesApi` exports; the gameplay harness retains them because it boots live downstream applications and mutable stub servers.

### jOOQ, Flyway, and SQL Persistence

`jOOQ + Flyway` is the only canonical SQL persistence runtime. Flyway migrations are the schema authority; `net.firedevops.firemud.jooq-conventions` generates DSL/record sources from migration SQL, normally under `net.firedevops.firemud.<servicebase>.jooq`, and services adopt repositories through `DSLContext` and generated types. The shared foundation is intentionally thin: Java time mappings are enabled, common transaction/pagination/filter/sort/JSON/timestamp/constraint-error helpers exist where repeated value is real, database-enum bindings remain consumer-driven, and JSON/structured columns retain the owning schema's explicit storage type. `automation-scripting-service` is the first-adopter proof and generates through the shared path with `./gradlew :automation-scripting-service:generateJooq` rather than service-local task wiring.

All current SQL-backed business services have no Spring Data JPA repository surface and use explicit jOOQ repositories:

- Account: identity, tenant membership, profiles, realm grants, email/password tokens, linked accounts, payment/subscription rows, and virtual-currency balances.
- Automation Scripting: work items, readiness/pin/rollout projections, event/audit and handoff history, rollout/runtime history, plugin state, admission, authored scripts/bindings, schedules, and NPC/faction support.
- Game Session: admission pointers/audit, prepared upgrades, feature flags, manifests, game instances, gameplay commands, runtime region status, tick batches/effects, and remote follow-up/result/coordinator records.
- Game Design: versions/games/templates/assets, revisions, settings overrides, remap sets/entries, publication attempts and participant digests, release bundles, launch descriptors, asset purge workflows, and plugin publication/status rows.
- Entity Management: characters, items, NPCs, actor state/conditions/resources, equipment/body layouts, friends, visible-reference counters, transfer audit, item/container/stack instances, inventory/equipment/room-ground projections, crafting recipes, and mutation-effect replay state.
- Logging & Admin: moderation actions, player reports, log events, and moderation-policy reads; its log-event, moderation, and report repositories use shared timestamp helpers.
- World Management: generation rules, authored topology and exits, design scope/epoch/revision ledger, spawn bindings, runtime world/region/zone/room instances and exits, and world-event history.
- Social & Groups: account-scoped friends, guilds/members/storage/alliances, chat persistence and effect-id replay lookup, and mail messages.

The Account rows and entrypoints for profiles, external links, recovery/verification, payments, subscriptions, notifications, and virtual currency are live foundations. They do not imply completion of Account lifecycle transitions, purchased-entitlement fulfillment, or complete billing/subscription enforcement.

Repo-wide Hibernate/JPA build/runtime support, ORM configuration, and dead helpers have been removed. Common data runtime owns the JDBC/Flyway/Postgres contract without Hibernate default-schema assumptions; common saga uses explicit jOOQ repositories; shared Postgres-backed test support expresses service-schema boot once, including former H2-backed Game Session seams. `PostgresBackedServiceTestSupport` uses an explicit schema-to-module map rather than guessing module directories from schema names, matching reset-tooling truth. Logging & Admin saga dashboard availability is conditional on saga beans and fails closed when absent; Game Session no-database bootstrap/recovery beans are explicitly gated. Service boot, hosted manifests, Compose, and reset tooling use `flyway_schema_history_<service_schema>` consistently. Saga migration resources are bundled from `common-saga` and applied alongside service-local migrations in the owning service schema, not in a dedicated saga schema or separate common-library Flyway pass. Shared Gradle conventions now use SQL-era names (`sql-postgres-conventions` and `secured-sql-aop-service-conventions`). Logging & Admin and Social & Groups test profiles normalize H2 identifier casing where generated metadata needs it, while World Management's leftover H2 test dependency tail is removed. Social guild-member/storage/alliance migrations were also aligned with the current surrogate-id, tenant, and `Long`-identifier model rather than relying on JPA to mask drift.

### Temporal Control-Plane Workflows

Temporal is the shared durable substrate for long-lived, restart-safe, wait/timer-capable, resumable, operator-visible control-plane workflows. The shared runtime lives in `services/common-temporal`; services opt in through `net.firedevops.firemud.temporal-conventions`; `TemporalWorkerRegistrar` and `TemporalWorkerHost` provide worker hosting; `TemporalTaskQueueResolver` and `FiremudWorkflowIds` provide queue, workflow identity, and business-step identity conventions. The foundation includes canonical retry/timer, signal/query/update, and operator-read integration without speculative workflow logic. A world-management-module proof exercises the shared worker host/registration pattern without adding speculative workflow behavior to the foundation.

The implemented adopters are world creation/activation/termination, publish/release, and script-patch readiness/rollout lifecycle. Their operator read models expose stable `workflowFamily` together with `workflowId`, `workflowRunId`, and `workflowStatus`; world-management, automation-scripting, and game-design proto/DTO metadata resolvers preserve those family constants. Publication, activation, readiness, cancellation, and runtime execution remain distinguishable rather than being collapsed into one status. The operator-facing documentation now teaches the same contract.

`common-saga` remains the correct mechanism for short synchronous orchestration that does not need survive-restart execution, durable waiting, or operator-driven resume/retry/signal behavior. Gameplay ticks, Redis coordination, per-command execution, and hot runtime mutation stay on the tick/idempotency/reconciliation model and are not Temporal workflows. Temporal is not a generic CRUD job runner and new adopters require a concrete durable business process.

### Ownership Summary

| Owner | Live authority and capability | Contract boundary |
| --- | --- | --- |
| Common platform/security | Internal auth propagation, service identity parsing, gRPC app-error representation/observability, and proto time-field guard | Shared Java seams, gRPC helpers, Gradle/root verification |
| Owning domain services | Domain authority, tenant/access enforcement, local transaction boundary, durable local state, and app-error mapping | Service REST/gRPC APIs and service-local Flyway schema |
| Logging & Admin | Non-destructive log-event ingress, moderation policy definition/evaluation, moderation/report persistence, and operator projections | Audit/log-event and moderation/control-plane REST/gRPC contracts |
| Game Session | Player-session authority, gameplay attestation, lifecycle finalization/compensation, tick runtime coordination, and gameplay control-plane delegation | Session/gameplay gRPC, attestation fields, Redis/runtime records, and durable SQL ledgers |
| Common saga | Shared saga repository ownership and short synchronous saga persistence/runner contract | Schema-qualified jOOQ repositories and saga steps |
| jOOQ/Flyway tooling | Generated relational access, migration history, service-schema conventions, and Postgres-backed proof support | Build/codegen tasks, Flyway resources, reset/bootstrap tooling |
| Temporal | Durable control-plane workflow execution, identity/idempotency conventions, and canonical workflow operator read metadata | Workflow/activity contracts and operator projections |

### Recorded Proof

The evidence records focused and repository-level proof for the implemented seams. Time-contract proof includes `python3 -m unittest discover -s dev-tools/validation -p 'test_check_proto_time_fields.py'`, `./gradlew checkProtoTimeFields`, and `./gradlew lintMarkdown linkCheck`. Boundary hardening records touched-service tests/checks, `spotlessCheck`/`spotlessJavaCheck`, `./gradlew check`, and `./gradlew linkCheck lintMarkdown`; dedicated collaborator tests cover the extracted Game Session and Automation control-plane seams. Persistence convergence records `:common-saga:test`, `:common-saga:integrationTest`, `:common-test-support:testFixturesJar`, `:buildSrc:check`, affected service compilation, `./gradlew spotlessApply`, `./gradlew check`, and Markdown/link checks. Temporal operator proof covers `TemporalWorldLifecycleWorkflowMetadataResolverTest` and `WorldManagementGrpcServiceTest`, `TemporalScriptPatchReadinessWorkflowMetadataResolverTest` and `AutomationScriptingControlPlaneGrpcServiceTest`, `TemporalVersionPublishWorkflowMetadataResolverTest` and `GameDesignGrpcServiceTest`, plus `./gradlew linkCheck lintMarkdown`.

## Active Gaps

- The shared time guard is implemented, but runtime cooldown state, common timed-state handling for buffs/conditions, scheduled-effect execution, and richer gameplay-clock APIs are not implemented by this tracker.
- The bounded fan-out pulse scheduler and the separate Redis queue/lease control seam are live. Whether pulse scheduling should move to a different queue/lease scheduler remains a separate possible follow-up and requires real load evidence before design or implementation.
- New secured endpoints and blocking gRPC clients must adopt JWT propagation, explicit service identity/attestation where delegated gameplay is involved, and normal `ErrorDetail` application failures. This record does not pre-audit hypothetical future surfaces.
- Future SQL work must use jOOQ/Flyway and owning service schemas; there is no intended remaining ORM/JPA migration lane. A new shared persistence abstraction would require an explicit design decision.
- New Temporal workflow families require a concrete long-lived business process, owning-domain state, and a reason to need durable waits/resume/operator control. Gameplay/tick runtime and small CRUD orchestration remain outside Temporal.
- Destructive pre-v1 Flyway baseline restatement is owned by the separate migration-squash/reset slice; this tracker records the ownership boundary but does not claim that follow-up as part of the runtime-contract implementation.
- Account lifecycle transitions, purchased-entitlement fulfillment, complete billing/subscription enforcement, and the end-to-end account security-lock/appeal workflow remain Account-owned follow-through rather than shared-runtime completion claims.

## To Discuss

No competing current target state is recorded for explicit time-domain naming, normal-response gRPC application errors, non-destructive audit ingress, jOOQ/Flyway persistence, schema-qualified shared saga ownership, or bounded Temporal use. Before adding a central gameplay clock/scheduler API, a new Temporal family, cross-domain compensation semantics, or a new shared persistence abstraction, the owning design must settle the boundary and its proof obligations. The existing choices for `workflowFamily`, shared saga repositories, and the audit/moderation enforcement split are resolved rather than open alternatives.

## Source Evidence

The following records are the unchanged line-preserving transposition used as the audit backstop for the consolidated record above. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99

#### Shared Time, Duration, and Scheduler Semantics Vertical Slice - Shared time and duration semantics (source lines 1-99)

##### Preserved Source Text: source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99

<!-- migration-source path="design/project-management/vertical-slices/02.13.11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice.md" lines="1-99" sha256="e0dace0a99c6975f1debc312fe90799be4203ea1df4a48716a07db802b93f1ab" heading-offset="3" -->
#### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Shared Time, Duration, and Scheduler Semantics Vertical Slice

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Goal and Status

Goal: define one canonical timing and duration model for gameplay and platform workflows so cooldowns, buffs, reconnect grace, OTP expiry, AFK timers, and future scheduled effects do not each invent separate local semantics. Status: first bounded target boundary is complete: cross-service proto time-field naming guard now blocks ambiguous units at contract boundaries.

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end for the first bounded contract surface (cross-service proto naming guard).
- [x] Verify and close any follow-ups for this bounded surface.

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Completion Evidence

- Contract guard is implemented in `dev-tools/validation/check-proto-time-fields.py` and flags ambiguous proto fields without explicit unit/domain suffixes (`_at`, `_ms`, `_seconds`, `_tick`, `_ticks`).
- Contract execution is wired into repo verification via `checkProtoTimeFields` in:
  - `build.gradle.kts`
- The current proto corpus is checked with this guard through repo-level verification; no ambiguous time names currently fail.
- A focused regression test for the guard now lives in:
  - `dev-tools/validation/test_check_proto_time_fields.py`

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Validation

- `python3 -m unittest discover -s dev-tools/validation -p 'test_check_proto_time_fields.py'`
- `./gradlew checkProtoTimeFields`
- `./gradlew lintMarkdown linkCheck`

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Implementation Notes

The first contract guard is now live:

- `dev-tools/validation/check-proto-time-fields.py` scans all checked-in proto contracts for ambiguous time-related field names.
- Root `./gradlew check` now runs `checkProtoTimeFields`, so new cross-service fields such as bare `timeout`, `expires`, `expiry`, `duration`, or `cooldown` fail unless their name declares a unit/domain suffix such as `_at`, `_ms`, `_seconds`, `_tick`, or `_ticks`.
- Existing proto contracts already satisfy this first rule, including `evaluated_at`, lifecycle `*_at` fields, `ttl_ms`, and `requires_solo_tick`.

This is intentionally a guardrail, not the full timing substrate. Runtime adoption still needs to happen as cooldowns, buffs, conditions, scheduled effects, and richer gameplay-clock APIs become real.

The authored-action metadata seam now names gameplay cooldowns as `cooldownTicks`, never milliseconds. It carries the declared tick-relative duration only; the later runtime cooldown state and scheduler remain future work.

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Why This Slice Exists

Many planned systems depend on time:

- OTP and login challenges;
- reconnect grace and transcript recovery windows;
- AFK and activity thresholds;
- condition durations and buffs;
- cooldowns and temporary action states;
- future scheduled world or social effects.

Without one shared timing model, FireMUD will accumulate inconsistent expiry logic, scheduling semantics, and clock assumptions.

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Scope

- Define canonical duration and expiry semantics for gameplay and platform subsystems.
- Define where scheduling lives for:
  - near-term expiry;
  - delayed effect completion;
  - cooldown windows;
  - reconnect/auth grace periods.
- Define expectations around wall-clock time versus monotonic/runtime-safe timing where relevant.
- Define how timing metadata is exposed to actions, conditions, and presence/activity systems.

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Out of Scope

- Full job/orchestration platform design.
- Real-time combat tick architecture.
- External cron-style operator workflows.

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Known Design Considerations

- Buffs, conditions, and cooldowns should not invent separate duration primitives.
- Auth/session expiry should align with the same broad timing vocabulary even if implemented in different services.
- AFK and recent-presence policies should consume this model rather than ad hoc timeout checks.
- Future scheduled effects should remain compatible with the shared effect engine and authored action model.
- Cooldowns should be modeled as a sibling timed-state type alongside conditions and transient action states, not by pretending they are the same semantic object.

##### source-02-13-11-task-list-shared-time-duration-and-scheduler-semantics-vertical-slice-1-99: Locked Direction

- FireMUD should define one shared timing vocabulary before building a larger scheduling platform.
- Two explicit time domains must exist:
  - wall-clock time for auth/session/operator-style expiry;
  - gameplay-clock time for gameplay-tick-relative behavior.
- OTP expiry, reconnect grace, AFK timeout, and similar auth/session timers are wall-clock based unless explicitly documented otherwise.
- Cooldowns, buffs, temporary conditions, and similar gameplay mechanics are gameplay-clock based unless explicitly documented otherwise.
- Cross-boundary APIs must declare which time domain they use rather than leaving it implicit.
- The shared vocabulary should include at least:
  - `occurredAt`;
  - `expiresAt`;
  - `duration`;
  - `cooldownEndsAt`;
  - `nextEligibleAt`.
- This slice should standardize semantics first, not introduce a large central scheduler platform in the same pass.

The first contract rules should be:

- wall-clock APIs use timestamp-style fields such as `occurredAt`, `expiresAt`, and `nextEligibleAt`;
- gameplay-clock APIs use explicitly tick-relative fields such as `remainingTicks`, `appliesAtTick`, or `expiresAtTick`;
- no cross-service contract should use an ambiguous generic field such as `time`, `timeout`, or `expires` without declaring the time domain.
<!-- /migration-source -->

### source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89

#### `02.18` Service Boundary and Audit Hardening - Cross-service boundary and audit hardening (source lines 1-89)

##### Preserved Source Text: source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89

<!-- migration-source path="design/project-management/vertical-slices/02.18-task-list-service-boundary-and-audit-hardening-vertical-slice.md" lines="1-89" sha256="792431c2e6d5ab7cee2485e5858b47867b4b3b5735401079ddd61aa9043fa14d" heading-offset="3" -->
#### source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89: `02.18` Service Boundary and Audit Hardening

Goal: convert the latest static audit findings into one explicit hardening train so service-boundary safety, internal auth, moderation/audit separation, and gRPC contract consistency converge toward one canonical state instead of accumulating as isolated fixes. Status: complete for the original static-audit hardening set; later durable runtime substrate remains planned separately.

##### source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89: Implementation Notes

This slice family has closed the original static-audit hardening set on the branch:

- a dedicated non-destructive log-event path exists in Logging & Admin so account-service logging does not continue to misuse moderation RPCs;
- world-management and entity-management auth scaffolding has been added, including service-local auth config, JWT interceptors, and tenant-access enforcement at several REST/gRPC edges;
- workflow transaction-boundary cleanup is complete for the audited `PurchaseWorkflowServiceImpl` and `ModerationServiceImpl` outliers;
- current admin gRPC services now use a shared explicit admin-role guard and return in-band `ErrorDetail` responses instead of transport `PERMISSION_DENIED` exceptions;
- session lifecycle/runtime-state writes in Game Session now fail before commit instead of committing `RUNNING` / `STOPPED` DB truth and then hoping Redis/runtime side effects succeed afterward;
- `AccountGrpcService` and `GatewayManagementGrpcService` have visible `GrpcAppErrors` adoption;
- secured internal blocking gRPC clients now use the shared auth-attaching stub-customizer seam, with intentional raw-stub outliers documented instead of accidental;
- tick scheduling remains a bounded fan-out model for now rather than growing into a dedicated queue/lease scheduler:
  - at most one pending or running tick per session;
  - overloaded scheduler pulses should merge/skip rather than accumulate debt;
  - merges, rejections, and scheduler pressure should be explicit in metrics/logging.
- the current tick-scheduler implementation and its threshold-aware operator-proof contract are now both in place; any later queue/lease scheduler revisit belongs to a separate future slice if real load data justifies it.

Treat this doc set as the durable design/audit trail for the batch. The earlier temporary worklists are no longer needed now that the completed hardening items are reflected in the slice family and the remaining runtime scheduling/operator-proof and durable-runtime follow-ups have their own dedicated child slices.

For deep-dive purposes, this family now splits cleanly:

- `02.18.1` through `02.18.6` are the original audited hardening set and are the best doc-first review surfaces in this family.
- `02.18.7` and later are a separate durable runtime substrate train with real implementation, but they should be reviewed as active child slices rather than assumed complete from the parent-family status alone.

##### source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89: Why This Slice Exists

The latest static audit exposed five related classes of service-boundary weakness:

- audit/logging callers are still able to hit destructive moderation paths;
- internal gRPC clients do not consistently propagate auth metadata even though downstream services already enforce JWT on several RPCs;
- some workflow services still hold local database transactions open across remote side effects;
- world-management and entity-management still lag the rest of the repo on baseline auth/tenant enforcement;
- gRPC app-error handling still drifts from the shared contract in a few visible outliers.

These are not five unrelated cleanup tickets. They are one hardening train around the same core rule:

- service boundaries must be explicit, authenticated, non-destructive by default, and consistent in how they fail.

##### source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89: Target State

- Non-destructive audit/logging traffic uses dedicated audit/log-event RPCs, not moderation mutation RPCs.
- Internal service-to-service blocking gRPC traffic carries the required auth metadata for secured downstream calls.
- Workflow services commit local state before triggering remote side effects, or defer outbound work to `afterCommit`/outbox-style seams.
- World-management and entity-management enforce the same baseline JWT and tenant-access rules already present in other services.
- gRPC application failures return normal app-error responses through `GrpcAppErrors`, with warnings, metrics, and spans aligned to the shared contract.
- Critical runtime state transitions do not claim success in PostgreSQL while corresponding Redis/runtime truth is still best-effort.

##### source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89: Child Slices

- [02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md](../vertical-slices/02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md)
- [02.18.2-task-list-internal-grpc-auth-propagation-vertical-slice.md](../vertical-slices/02.18.2-task-list-internal-grpc-auth-propagation-vertical-slice.md)
- [02.18.3-task-list-workflow-transaction-boundary-hardening-vertical-slice.md](../vertical-slices/02.18.3-task-list-workflow-transaction-boundary-hardening-vertical-slice.md)
- [02.18.4-task-list-world-and-entity-service-boundary-auth-vertical-slice.md](../vertical-slices/02.18.4-task-list-world-and-entity-service-boundary-auth-vertical-slice.md)
- [02.18.5-task-list-grpc-app-error-consistency-hardening-vertical-slice.md](../vertical-slices/02.18.5-task-list-grpc-app-error-consistency-hardening-vertical-slice.md)
- [02.18.6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice.md](../vertical-slices/02.18.6-task-list-tick-scheduler-backpressure-and-merge-semantics-vertical-slice.md)
- [02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md](../vertical-slices/02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md)
- [02.18.8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice.md](../vertical-slices/02.18.8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice.md)
- [02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md](../vertical-slices/02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md)
- [02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md](../vertical-slices/02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md)
- [02.18.11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice.md](../vertical-slices/02.18.11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice.md)
- [02.18.12-task-list-internal-service-identity-and-session-attestation-vertical-slice.md](../vertical-slices/02.18.12-task-list-internal-service-identity-and-session-attestation-vertical-slice.md)
- [02.18.13-task-list-runtime-feature-flag-authority-convergence-vertical-slice.md](../vertical-slices/02.18.13-task-list-runtime-feature-flag-authority-convergence-vertical-slice.md)
- [02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md](../vertical-slices/02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md)
- [02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md](../vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md)
- [02.18.16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice.md](../vertical-slices/02.18.16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice.md)
- [02.18.17-task-list-gameplay-transport-test-harness-convergence-vertical-slice.md](../vertical-slices/02.18.17-task-list-gameplay-transport-test-harness-convergence-vertical-slice.md)
- [02.18.19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice.md](../vertical-slices/02.18.19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice.md)
- [02.18.20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice.md](../vertical-slices/02.18.20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice.md)

##### source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89: Remaining Follow-Up Boundary

- `02.18.7`-`02.18.11` are the separate durable runtime substrate train: command ledger, tick/effect ledger, region fencing, effect idempotency, and migration of live state-changing commands.
- `02.18.12` continues the broader service-identity/session-attestation follow-through for later gameplay consumers.
- `02.18.17` now closes that convergence at the current boundary: chained gameplay WebSocket/Telnet proof defaults to shared FireMUD transport drivers, ready-session helpers, scenario fixtures, and backend request-shape assertions instead of continuing to grow ad hoc test-local socket loops.
- `02.18.19` owns the later repo-audit simplification seam: oversized orchestrator-service decomposition, test-only constructor removal, shared-saga ownership cleanup, overlapping Game Session command-surface review, and coordination with `02.17.1` for any pre-v1 migration squashing.
- `02.18.20` now closes the narrower final audit follow-up seam left after `02.18.19`: shared saga repository ownership is explicit in `common-saga`, and the remaining Game Session command/status control-plane cluster is extracted out of the gRPC façade.
- New service-boundary audit findings should become new child slices or updates to the relevant existing child slice rather than reopening this parent as a vague catch-all.

##### source-02-18-task-list-service-boundary-and-audit-hardening-vertical-slice-1-89: Validation

- [x] `./gradlew spotlessApply`
- [x] touched-service `spotlessCheck` / `spotlessJavaCheck`
- [x] touched-service tests and checks for the affected services
- [x] `./gradlew check`
- [x] `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-18-1-task-list-audit-log-and-moderation-separation-vertical-slice-1-55

#### `02.18.1` Separate Audit Logging From Moderation Mutations - Audit and moderation service boundary (source lines 1-55)

##### Preserved Source Text: source-02-18-1-task-list-audit-log-and-moderation-separation-vertical-slice-1-55

<!-- migration-source path="design/project-management/vertical-slices/02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md" lines="1-55" sha256="7a92de860fc57cf2b381c320f176dce7f04282568d422a7527a68f5de40ed723" heading-offset="3" -->
#### source-02-18-1-task-list-audit-log-and-moderation-separation-vertical-slice-1-55: `02.18.1` Separate Audit Logging From Moderation Mutations

Goal: replace the destructive misuse of moderation RPCs for harmless audit/logging traffic with one dedicated non-destructive log-event path. Status: complete for the known audit/logging misuse.

##### source-02-18-1-task-list-audit-log-and-moderation-separation-vertical-slice-1-55: Implementation Notes

The branch contains the completed known-caller separation:

- `logging_admin_service.proto` has been extended with a dedicated `CreateLogEvent` RPC;
- account-service `LoggingAdminClient` has been switched away from `ApplyModerationAction` toward the new log-event RPC;
- Logging & Admin now has the beginnings of a dedicated `LogEventService` path using the existing `LogEvent` persistence model.
- the known account creation/payment logging paths are covered by focused tests proving they create log events without deleting accounts or stopping sessions.

Follow-up boundary:

- future audit/logging callers must use `CreateLogEvent` or another dedicated non-destructive logging API, not moderation mutation RPCs;
- reopen this slice only if a new caller routes ordinary audit/logging traffic through moderation mutation paths.

##### source-02-18-1-task-list-audit-log-and-moderation-separation-vertical-slice-1-55: Why This Follow-Up Exists

The current account-service “logging” client has been calling `ApplyModerationAction` for events such as account creation and payment logging. That is a latent destructive bug:

- the moderation service treats the call as a real moderation action;
- it persists a moderation record;
- it deletes the account;
- it stops the session.

The current logging calls are also malformed because they do not carry a valid `session_id`, while the server parses it as mandatory.

This is not a naming nit. It is a service-boundary violation where audit traffic is routed through a destructive control plane.

##### source-02-18-1-task-list-audit-log-and-moderation-separation-vertical-slice-1-55: Target State

- Audit/logging traffic uses a dedicated non-destructive RPC.
- Moderation traffic uses a dedicated mutation RPC with explicit operator/admin semantics.
- Logging callers do not need to fabricate moderation payload fields such as `session_id`.
- Logging/Admin persists ordinary log events without mutating accounts or sessions.
- Tests make the separation obvious and prevent future accidental crossover.

##### source-02-18-1-task-list-audit-log-and-moderation-separation-vertical-slice-1-55: Required Changes

- [x] Add a dedicated `CreateLogEvent` RPC to `logging_admin_service.proto`.
- [x] Add DTO/service wiring in Logging & Admin for non-destructive log-event creation.
- [x] Switch account-service logging paths away from `ApplyModerationAction`.
- [x] Audit the repo for any remaining “log through moderation” callers and move them to the non-destructive path.
- [x] Add focused tests proving:
  - account creation/payment logging creates a log event;
  - the path does not delete accounts or stop sessions;
  - moderation still retains its own explicit destructive semantics.

##### source-02-18-1-task-list-audit-log-and-moderation-separation-vertical-slice-1-55: Explicitly Out Of Scope

- redesigning the whole moderation domain;
- durable operator-facing moderation UI;
- broader social/audit ledger work beyond replacing the destructive misuse.
<!-- /migration-source -->

### source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60

#### `02.18.12` Internal Service Identity and Session Attestation - Internal service identity and session attestation (source lines 1-60)

##### Preserved Source Text: source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60

<!-- migration-source path="design/project-management/vertical-slices/02.18.12-task-list-internal-service-identity-and-session-attestation-vertical-slice.md" lines="1-60" sha256="7c6e139918f3b0a8b911e59173c49ec56ab6915a7b0085c71a52131cca4414ae" heading-offset="3" -->
#### source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60: `02.18.12` Internal Service Identity and Session Attestation

Goal: replace the current broad implicit internal-service admin fallback with one explicit service-identity model, and land the documented gameplay-domain `SessionAttestation` seam for delegated gameplay RPCs. Status: complete for the current delegated-gameplay boundary set.

##### source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60: Implementation Notes

- `GrpcClientAuth` now mints an explicit internal-service identity with `internalService`, `serviceName`, and `serviceInstanceId` claims instead of silently defaulting absent caller context to `platformAdmin`.
- Shared auth/session parsing now preserves those internal-service claims in `SessionContext`, so downstream code and tests can distinguish service work from operator work.
- `GameplaySessionAttestationService` now owns the first concrete delegated gameplay contract:
  - signed `GAMEPLAY_SESSION` attestations from Game Session
  - bounded `INTERNAL_PROBE` attestations for internal health/probe look paths
- Delegated gameplay RPCs now carry attestation fields on the live Game Session -> World / Entity / Game Logic seams, and the downstream services validate attestation plus internal-service identity instead of relying on generic bearer-role inference alone.
- Future delegated gameplay consumers should reuse this attestation model rather than inventing new bearer-only shortcuts, but there is no known unfinished current-boundary implementation gap in this slice.

##### source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60: Why This Slice Exists

The repo now has shared gRPC auth propagation and baseline JWT enforcement, but two important auth boundaries are still wrong:

- when no caller context is present, `GrpcClientAuth` still mints an internal token with `platformAdmin`, which weakens audit attribution and silently upgrades background/service work into operator power;
- later gameplay consumers still need to keep moving onto the same attestation contract instead of inventing new bearer-only shortcuts.

Those are related but not identical. They both live in the same broader service-boundary/auth hardening area, so they need one explicit slice rather than lingering as separate audit notes.

##### source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60: Scope

- default internal service identity when no player/operator caller context is present
- distinction between:
  - operator action
  - tenant-scoped action
  - background/service automation
- gameplay-domain delegated authority via Game Session-issued attestation
- downstream gameplay service validation rules for delegated gameplay RPCs

##### source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60: Out of Scope

- browser/player JWT issuance and first-party bootstrap flows
- full operator RBAC redesign
- wider runtime durability or replay-hardening concerns

##### source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60: Locked Direction

- missing caller context must not silently become `platformAdmin`.
- internal automation/service identity should be explicit, minimally privileged, and auditable as service work rather than human operator work.
- gameplay-domain downstream services should not rely on raw shared bearer claims alone once a delegated gameplay attestation seam exists.
- Game Session remains the authority for player-session-to-gameplay delegation.

##### source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60: Acceptance Shape

- `GrpcClientAuth` no longer defaults absent context to broad platform admin authority.
- shared auth docs and tests define the canonical internal-service identity claim shape.
- gameplay RPC contracts gain a bounded SessionAttestation field or metadata contract owned by Game Session.
- World, Entity, and Game Logic validate delegated gameplay authority through that attestation rather than generic bearer-role inference alone.

##### source-02-18-12-task-list-internal-service-identity-and-session-attestation-vertical-slice-1-60: Checklist

- [x] Define the canonical internal service identity claim model and remove the `platformAdmin` fallback default.
- [x] Update shared auth docs/tests so internal background work is distinct from operator authority.
- [x] Define the first concrete Game Session-issued `SessionAttestation` contract on gameplay RPC boundaries.
- [x] Update the first downstream gameplay consumers to validate the attestation path.
- [x] Add focused tests proving missing/invalid attestation rejection and valid delegated gameplay acceptance.
<!-- /migration-source -->

### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-1-18-29-34-42-49

#### `02.18.14` Moderation Policy Definition and Enforcement Split - Shared moderation policy contract and non-destructive model (source lines 1-18, 29-34, 42, 49)

##### Preserved Source Text: source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-1-18-29-34-42-49

<!-- migration-source path="design/project-management/vertical-slices/02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md" lines="1-18, 29-34, 42, 49" sha256="c51802a5032fa25e8201852a76bda888061f9d49abcf2f0f540b15c23428dd14" heading-offset="3" -->
#### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-1-18-29-34-42-49: `02.18.14` Moderation Policy Definition and Enforcement Split

Goal: replace the current destructive moderation substrate with explicit moderation policy definition, policy distribution, and enforcement-owner behavior across gameplay admission and chat/send paths. Status: complete.

##### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-1-18-29-34-42-49: Implementation Notes

Logging & Admin now records moderation policy actions and exposes an internal `EvaluateModerationPolicy` RPC. It no longer treats an ordinary moderation action as a destructive saga that deletes an account and stops a session. Game Session enforces `GAMEPLAY_ADMISSION` policy during `PLAY`, and Social & Groups enforces `CHAT_SEND` policy before chat persistence/publish.

##### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-1-18-29-34-42-49: Why This Slice Exists

The current moderation path still collapses several different concerns into one destructive implementation:

- Logging & Admin records a moderation action and then directly deletes the account and stops the session;
- Game Session does not yet enforce gameplay-ban policy during admission;
- Social & Groups reports chat moderation events but does not enforce `chat_mute` or `chat_ban`.

`02.18.1` separated ordinary audit logging from moderation mutations, but it did not redesign the moderation model itself. This slice is the remaining moderation architecture work.

<!-- source-gap: lines 19-28 -->
##### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-1-18-29-34-42-49: Out of Scope

- general audit-log infrastructure already covered elsewhere
- broader staff capability and hidden-staff behavior
- appeals/case-management product UX beyond the minimum policy substrate

<!-- source-gap: lines 35-41 -->

<!-- source-gap: lines 43-48 -->

<!-- /migration-source -->

### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149

#### Orchestration Decomposition and Pre-v1 Simplification Vertical Slice - Cross-service orchestration decomposition (source lines 1-149)

##### Preserved Source Text: source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149

<!-- migration-source path="design/project-management/vertical-slices/02.18.19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice.md" lines="1-149" sha256="91a53b16b2775a39da58bb5220fb3ceef94b4bd43e556fa4da6f1f9c3e642455" heading-offset="3" -->
#### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: Orchestration Decomposition and Pre-v1 Simplification Vertical Slice

Goal: turn the latest high-level repo audit into one explicit simplification slice so oversized orchestrator services, test-only constructor seams, legacy fallback branches, shared saga indirection, overlapping Game Session command surfaces, and heavy test-fixture coupling are reduced deliberately instead of being left as recurring review notes. Status: implemented; the HTTP shim is gone, the targeted Game Session / Automation constructor seams are cleaned up, `GameSessionControlPlaneGrpcService` now delegates runtime ownership/runtime-state reads, remote followup control-plane query/scheduling, the admission-pointer plus prepared-cutover lifecycle, and the operator-facing pin / purge / pause-resume plus version-upgrade compatibility-preparation cluster into dedicated collaborators, `ScriptWorkItemServiceImpl` now treats readiness projections as the only canonical patch-status/readiness source instead of keeping a nullable projection seam with durable fallback summaries, migration-time `genrev:legacy` and `legacy:` fresh-schema sentinels are removed from the canonical baseline, `TickServiceImpl` is now reduced to top-level orchestration over explicit queue-control, batch-execution, staging/replay, and runtime-progress collaborators instead of directly owning those responsibility clusters itself, `AutomationScriptingControlPlaneGrpcService` now delegates event catalog, patch/admission/schedule/dead-letter control-plane flow, and plugin-runtime control-plane flow into bounded collaborators rather than continuing as one 1.6k-line central façade, saga persistence ownership now converges on the shared `common-saga` repository contract instead of per-service same-name wrapper interfaces, and the remaining migration-squash execution follow-through is explicitly re-homed under the Flyway/reset family rather than left as an orphan tail here.

##### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: Why This Slice Exists

The 2026-05-16 repo review did not find one isolated bug pattern. It found a consistent shape:

- core runtime/control-plane classes are carrying too many responsibilities in one place;
- tests are influencing production constructor design;
- pre-v1 transitional branches are beginning to stick;
- some shared-library indirection is heavier than the value it currently provides;
- Game Session still has more command/application surfaces than are obviously necessary;
- the heaviest service is also becoming the test integration hub.

These are not feature gaps. They are code-shape and boundary-convergence work that needs one bounded home.

##### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: Verified Audit Signals

- [x] Orchestration concentration is real in current code, not just a size-only complaint.
  Evidence: [GameSessionControlPlaneGrpcService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcService.java), [TickServiceImpl.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java), and [AutomationScriptingControlPlaneGrpcService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationScriptingControlPlaneGrpcService.java) each combine multiple endpoint/domain/persistence/coordination concerns in one class.
- [x] Testability seams are leaking into production constructors.
  Evidence: constructor overloads in [GameSessionControlPlaneGrpcService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcService.java), [GameSessionGrpcService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionGrpcService.java), [TextCommandInterpreter.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/TextCommandInterpreter.java), and [AutomationScriptingControlPlaneGrpcService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationScriptingControlPlaneGrpcService.java) instantiate fresh config/catalog objects to satisfy narrower tests.
- [x] Transitional compatibility paths are already present in runtime and migration seams.
  Evidence at audit time: projection-plus-legacy merge logic in [ScriptWorkItemServiceImpl.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImpl.java), `genrev:legacy` in [V11__launch_descriptor_framework.sql](../../../services/game-design-service/src/main/resources/db/migration/V11__launch_descriptor_framework.sql), and a Game Session migration-time `legacy:` fallback effect-key sentinel that existed before the destructive baseline squashes. The runtime merge plus both fresh-schema sentinels are now removed in this slice.
- [x] Shared saga/data-runtime layering is heavier than the current service-local value.
  Evidence: [common-data-runtime/build.gradle.kts](../../../services/common-data-runtime/build.gradle.kts), conditional saga repository synthesis in [DatabaseAutoConfiguration.java](../../../services/common-data-runtime/src/main/java/net/firedevops/firemud/common/config/DatabaseAutoConfiguration.java), and the now-removed same-name service-local saga repository wrappers that previously shadowed the shared [SagaInstanceRepository.java](../../../services/common-saga/src/main/java/net/firedevops/firemud/common/saga/persistence/SagaInstanceRepository.java) contract in multiple services.
- [x] Game Session still had overlapping gameplay/command application surfaces when this slice was opened.
  Evidence at audit time: an extra HTTP command shim routed back into the same interpreter already used by text-command, WebSocket, and gRPC ingress. That extra path has now been removed in this slice.
- [x] Build/test coupling is materially elevated around Game Session.
  Evidence: [game-session-service/build.gradle.kts](../../../services/game-session-service/build.gradle.kts) exports multiple downstream services from `testFixturesApi`, making the heaviest service a reusable integration hub as well as a runtime owner.
- [x] The audit’s “AI-grown helper pattern” concern is too weak to own as a repo-wide cleanup target by itself.
  Keep this as a review smell only; do not open a generic dedupe pass for `firstNonBlank`, `blankToEmpty`, or per-method response-builder ceremony unless a narrower owning seam proves it worthwhile.

##### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: Scope

- Decompose oversized control-plane and runtime orchestrator classes by bounded use-case ownership.
- Remove test-only production constructor overloads in the cited seams and replace them with focused test fixtures/builders.
- Flatten pre-v1 compatibility branches where one canonical current truth can replace dual-path behavior.
- Decide whether saga persistence is truly shared infrastructure or should become service-local again.
- Re-evaluate overlapping Game Session gameplay command/application surfaces and delete non-essential shims.
- Reduce Game Session test-fixture dependency blast radius where current proof support is broader than needed.

##### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: Out of Scope

- Feature work in scripting, routing, inventory, or social domains unless needed to unblock the structural simplification.
- Repo-wide style-only cleanup or helper deduplication with no clear owning boundary.
- Reopening already-completed gameplay proof-convergence slices except where fixture dependency reduction materially overlaps them.
- Ad hoc migration rewrites outside a coordinated pre-v1 reset/squash plan.

##### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: Existing Slice Overlaps

- `02.17.1` already owns the local Flyway/reset hygiene substrate. If migration squashing is executed, it should coordinate with that slice rather than inventing a separate one-off workflow here.
- `02.18.16`, `02.18.17`, and `02.18.18` already own gameplay proof-fixture convergence. This slice only owns the extra compile/dependency blast-radius question around Game Session fixture exports.
- `02.18.8` and `02.18.9` already own live runtime/tick substrate behavior. This slice should refactor `TickServiceImpl` by responsibility, not reopen the underlying durable-runtime contract decisions they already own.

##### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: Completion Notes

###### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: 1. Decompose oversized orchestrator services

- [x] Split [GameSessionControlPlaneGrpcService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcService.java) into bounded endpoint handlers or delegated use-case components for:
  - [x] admission pointers and browse/read APIs;
  - [x] runtime ownership and status reads;
  - [x] remote followup/control-plane queries and scheduling ingress;
  - [x] cutover and version-upgrade flows;
  - [x] tick remediation/operator actions.
- [x] Split [TickServiceImpl.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java) into explicit seams for:
  - [x] Redis queue/lease coordination plus queue-admin ownership/status controls, now extracted into [TickQueueControlService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickQueueControlService.java) with dedicated proof in [TickQueueControlServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickQueueControlServiceTest.java);
  - [x] durable batch/effect execution, drain/apply/abandon transitions, stale-fence requeue handling, and queue-source stamping, now extracted into [TickBatchExecutionService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionService.java) with direct seam proof in [TickBatchExecutionServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionServiceTest.java);
  - [x] tick selection/staging and replay resolution, now extracted into [TickStagingService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickStagingService.java) with direct seam proof in [TickStagingServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickStagingServiceTest.java);
  - [x] remote followup claim/drain ingress, now carried by [TickStagingService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickStagingService.java) instead of the top-level tick façade and covered directly in [TickStagingServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickStagingServiceTest.java);
  - [x] runtime metrics/reporting and tick-progress publication, now extracted into [TickRuntimeProgressService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickRuntimeProgressService.java) with direct seam proof in [TickRuntimeProgressServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickRuntimeProgressServiceTest.java).
- [x] Split [AutomationScriptingControlPlaneGrpcService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationScriptingControlPlaneGrpcService.java) by bounded control-plane use case instead of continuing to grow one central façade.
  - [x] event-definition catalog flow, now extracted into [AutomationEventControlPlaneService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationEventControlPlaneService.java) with direct seam proof in [AutomationEventControlPlaneServiceTest.java](../../../services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/AutomationEventControlPlaneServiceTest.java);
  - [x] patch/admission/drain, rollout/schedule, handoff, and dead-letter control-plane flow, now extracted into [AutomationPatchControlPlaneService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationPatchControlPlaneService.java) with direct seam proof in [AutomationPatchControlPlaneServiceTest.java](../../../services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/AutomationPatchControlPlaneServiceTest.java);
  - [x] plugin-runtime status, policy, and activation/drain flow, now extracted into [AutomationPluginControlPlaneService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationPluginControlPlaneService.java) with direct seam proof in [AutomationPluginControlPlaneServiceTest.java](../../../services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/AutomationPluginControlPlaneServiceTest.java).

###### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: 2. Remove test-only production constructor seams

- [x] Remove fresh-config/fresh-catalog constructor overloads from:
  - [x] [GameSessionControlPlaneGrpcService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcService.java)
  - [x] [GameSessionGrpcService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionGrpcService.java)
  - [x] [TextCommandInterpreter.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/TextCommandInterpreter.java)
  - [x] [AutomationScriptingControlPlaneGrpcService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationScriptingControlPlaneGrpcService.java)
- [x] Replace the completed constructor-cleanup seams with service-local test builders, fixture factories, or narrower collaborator injection so tests no longer require production constructors to manufacture config defaults.
  The `GameSessionControlPlaneGrpcService` test surface now builds explicit collaborators locally, and its runtime ownership/runtime-state branch is delegated into [GameSessionRuntimeControlPlaneReadService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionRuntimeControlPlaneReadService.java) instead of relying on production-side default manufacturing.

###### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: 3. Flatten pre-v1 compatibility branches into one current model

- [x] Make readiness projection truth canonical in [ScriptWorkItemServiceImpl.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/ScriptWorkItemServiceImpl.java) and remove the current projection-plus-legacy merge path once the remaining callers no longer depend on legacy summaries.
  Progress: patch-status reads and lists now use [ScriptPatchReadinessProjectionService](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/ScriptPatchReadinessProjectionService.java) unconditionally, the nullable production-constructor seam is gone, and on-load replay/readiness refresh logic now assumes the same canonical projection substrate instead of branching on a missing projection service.
- [x] Replace migration-time sentinel values like `genrev:legacy` and `legacy:` fallback effect keys with squashed canonical baselines once the live schema truth is reset and restated.
  Progress: [V11__launch_descriptor_framework.sql](../../../services/game-design-service/src/main/resources/db/migration/V11__launch_descriptor_framework.sql) now backfills canonical `genrev:<tenant>:<version>:<manifestHash>` values before enforcing `NOT NULL` instead of defaulting fresh schemas to `genrev:legacy`, and the legacy Game Session effect-key backfill now resolves to deterministic `effect:<effectId>` values for non-command rows instead of using a `legacy:` sentinel prefix in the current baseline.
- [x] Review other compatibility proofs or sentinel paths in the same ownership seams before adding more of them by default.
  Progress: a direct search of the touched ownership seams no longer finds live `genrev:legacy` or `legacy:` behavior outside audit/history docs, so no further same-seam sentinel cleanup remains in product code after this batch.

###### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: 4. Simplify shared saga/data-runtime ownership

- [x] Choose one direction and apply it consistently:
  - [x] real shared saga persistence contract with no service-local same-name adapter duplication; or
  - [ ] service-local saga persistence with no auto-synthesized shared repository beans.
  Progress: the shared contract won; live saga consumers now inject [common-saga persistence repositories](../../../services/common-saga/src/main/java/net/firedevops/firemud/common/saga/persistence) directly, and the per-service same-name wrapper interfaces have been deleted instead of preserving parallel aliases.
- [x] Remove same-name adapter interfaces and keep the shared [SagaInstanceRepository.java](../../../services/common-saga/src/main/java/net/firedevops/firemud/common/saga/persistence/SagaInstanceRepository.java) / [SagaStepRepository.java](../../../services/common-saga/src/main/java/net/firedevops/firemud/common/saga/persistence/SagaStepRepository.java) contract as the only live repository surface if the shared contract remains.
- [ ] Remove conditional saga repository synthesis from [DatabaseAutoConfiguration.java](../../../services/common-data-runtime/src/main/java/net/firedevops/firemud/common/config/DatabaseAutoConfiguration.java) if service-local ownership wins.

###### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: 5. Prune overlapping Game Session command/application surfaces

- [x] Decide whether the HTTP command shim is a real supported surface or an unnecessary extra path.
- [x] If the shim is not essential, delete it instead of preserving a fourth surface for the same gameplay-command concept.
- [x] The shim did not survive review; the canonical ingress set is now text-command, WebSocket, and gRPC rather than carrying an accidental extra HTTP façade for the same enqueue concept.

###### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: 6. Reduce Game Session fixture dependency blast radius

- [x] Review `testFixturesApi` exports in [game-session-service/build.gradle.kts](../../../services/game-session-service/build.gradle.kts) and narrow them where possible.
- [x] Prefer smaller shared support modules or fixture-local implementations over treating Game Session as the default integration hub for unrelated services.
  Progress: downstream service modules are no longer re-exported transitively from Game Session test fixtures, tcp-proxy now declares the specific downstream services it compiles against directly, and the remaining Game Session test-fixture project dependencies are fixture-local implementation details for [CrossServiceAppHarness.java](../../../services/game-session-service/src/testFixtures/java/net/firedevops/firemud/gamesession/CrossServiceAppHarness.java) and [GameplayCrossServiceStack.java](../../../services/game-session-service/src/testFixtures/java/net/firedevops/firemud/gamesession/testsupport/GameplayCrossServiceStack.java), which intentionally boot live downstream Spring applications and mutable stub servers for cross-service gameplay proof.
- [x] Keep any remaining broad fixture exports explicit and justified in the doc if they survive review.
  Progress: the surviving downstream dependencies are now explicit `testFixturesImplementation` entries rather than transitive fixture API exports, and they remain justified because the shared gameplay harness owns the nested downstream app bootstrap rather than merely sharing DTO helpers.
  Progress: the downstream service project exports in [game-session-service/build.gradle.kts](../../../services/game-session-service/build.gradle.kts) are now fixture-local implementation dependencies instead of transitive fixture API exports, and [tcp-proxy-service/build.gradle.kts](../../../services/tcp-proxy-service/build.gradle.kts) now declares the specific service test dependencies it compiles against directly.

###### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: 7. Coordinate pre-v1 migration simplification

- [x] Decide which busy services should squash migrations now rather than continuing to accumulate archaeology in a pre-v1 repo.
  Progress: the remaining migration-archaeology follow-through is now explicitly narrowed to the busiest services named by the audit (`game-session-service`, `automation-scripting-service`, and `entity-management-service`) rather than being left as a vague repo-wide concern.
- [x] Execute that work through a coordinated reset/squash plan that reuses `02.17.1` tooling and avoids piecemeal migration churn.
  Progress: execution is now re-homed to [02.17.2 Pre-v1 Migration Squash and Baseline Reset](../vertical-slices/02.17.2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice.md), which is the owning Flyway/reset follow-through slice for destructive baseline restatement rather than keeping that work stranded inside this audit-driven simplification slice.

##### source-02-18-19-task-list-orchestration-decomposition-and-pre-v1-simplification-vertical-slice-1-149: Checklist

- [x] Convert the audit into a bounded slice instead of leaving it as free-form review notes.
- [x] Verify each primary audit signal against real repo evidence before putting it on the checklist.
- [x] Exclude the vague repo-wide helper-style complaint as a primary task because it is not a strong enough owning seam by itself.
- [x] Land the first simplification batch: remove the extra Game Session HTTP command shim and stop using production constructor overloads to manufacture test defaults in the targeted Game Session / Automation seams.
- [x] Land the second simplification batch: finish `GameSessionControlPlaneGrpcService` constructor cleanup and extract runtime ownership/runtime-state reads into a dedicated control-plane read collaborator.
- [x] Land the third simplification batch: remove the patch-status projection-plus-legacy merge path from `ScriptWorkItemServiceImpl` so projection-backed environments read one canonical readiness model instead of splicing in durable fallback summaries.
- [x] Land the fourth simplification batch: extract the remote coordinator/followup/result query and scheduling cluster out of `GameSessionControlPlaneGrpcService` into [GameSessionRemoteControlPlaneService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionRemoteControlPlaneService.java), keeping the gRPC façade focused on auth/error transport while the new collaborator owns remote control-plane hydration, routing-bundle freshness projection, and schedule request delegation.
- [x] Land the fifth simplification batch: extract admission-pointer listing/audit, pointer mutation, prepared-cutover validation, and same-request cutover idempotency out of `GameSessionControlPlaneGrpcService` into [GameSessionAdmissionPointerControlPlaneService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionAdmissionPointerControlPlaneService.java), including promotion of `CutoverPreparationValidationException` to a shared package-local control-plane exception instead of leaving it trapped as a private nested gRPC helper.
- [x] Land the sixth simplification batch: extract the operator-facing pinned-patch, convergence, rollback, queue-purge, and pause/resume actions into [GameSessionOperatorControlPlaneService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionOperatorControlPlaneService.java), and extract version-upgrade compatibility/preparation readback into [GameSessionVersionUpgradeControlPlaneService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionVersionUpgradeControlPlaneService.java), with added direct proof for rollback and pause/resume delegation so the remaining gRPC façade responsibility is mostly transport/auth plus automation-command status flow.
- [x] Land the seventh simplification batch: extract the Redis queue/lease coordination plus queue-admin ownership/status controls out of [TickServiceImpl.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java) into [TickQueueControlService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickQueueControlService.java), update [TickServiceImplTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickServiceImplTest.java) to stay focused on tick execution behavior, and add direct collaborator proof in [TickQueueControlServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickQueueControlServiceTest.java) for enqueue, purge, pause/resume, state lookup, and ownership fencing.
- [x] Land the eighth simplification batch: extract the durable tick-batch/effect lifecycle out of [TickServiceImpl.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java) into [TickBatchExecutionService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionService.java), covering replay-manifest mismatch terminalization, pending-projection restoration, batch drain/apply/abandon transitions, stale-fence requeue behavior, durable effect execution, and queue-source stamping, while keeping direct seam proof in [TickBatchExecutionServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickBatchExecutionServiceTest.java) and leaving `TickServiceImpl` centered on tick selection, staging, runtime progress, and remote-followup ingress.
- [x] Land the ninth simplification batch: extract tick selection/staging, replay resolution, sealed-manifest restoration, remote-followup claim/drain ingress, and selected-work manifest construction out of [TickServiceImpl.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java) into [TickStagingService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickStagingService.java), and add direct seam proof in [TickStagingServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickStagingServiceTest.java) for gameplay-manifest ordering/routing truth, replay fallback/requeue behavior, and remote-followup manifest normalization.
- [x] Land the tenth simplification batch: extract retry-depth gauges, remote-followup backlog observation, paused-result reconciliation, runtime tick advancement, timeout reconciliation, and Automation tick-progress publication out of [TickServiceImpl.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java) into [TickRuntimeProgressService.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickRuntimeProgressService.java), and add direct seam proof in [TickRuntimeProgressServiceTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/TickRuntimeProgressServiceTest.java) for backlog gauges, over-budget counters, paused-result handling, and canonical progress publication.
- [x] Land the eleventh simplification batch: decompose [AutomationScriptingControlPlaneGrpcService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationScriptingControlPlaneGrpcService.java) into [AutomationEventControlPlaneService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationEventControlPlaneService.java), [AutomationPatchControlPlaneService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationPatchControlPlaneService.java), and [AutomationPluginControlPlaneService.java](../../../services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/impl/AutomationPluginControlPlaneService.java), leaving the gRPC class as a transport/auth/error façade and adding direct seam proof in [AutomationEventControlPlaneServiceTest.java](../../../services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/AutomationEventControlPlaneServiceTest.java), [AutomationPatchControlPlaneServiceTest.java](../../../services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/AutomationPatchControlPlaneServiceTest.java), and [AutomationPluginControlPlaneServiceTest.java](../../../services/automation-scripting-service/src/test/java/unit/net/firedevops/firemud/automationscripting/service/impl/AutomationPluginControlPlaneServiceTest.java).
- [x] Land the twelfth simplification batch: narrow [game-session-service/build.gradle.kts](../../../services/game-session-service/build.gradle.kts) so downstream service modules are no longer re-exported through `testFixturesApi`, and make [tcp-proxy-service/build.gradle.kts](../../../services/tcp-proxy-service/build.gradle.kts) declare the entity/social/world service test dependencies it actually compiles against directly instead of inheriting them implicitly through Game Session test fixtures.
- [x] Land the thirteenth simplification batch: choose the shared `common-saga` repository contract as the canonical persistence seam, delete the per-service same-name saga repository wrappers across Account / Automation / Game Design / Logging Admin / Social Groups / World Management, and move the live Logging Admin consumer plus proof onto the shared `SagaInstanceRepository` / `SagaStepRepository` types directly.
- [x] Land the fourteenth simplification batch: finish `ScriptWorkItemServiceImpl` readiness-projection convergence by removing the nullable projection constructor seam, deleting durable fallback patch-status/list logic, and updating replay/readiness proof so Automation patch-status reads now depend on the canonical readiness projection contract only.
- [x] Land the fifteenth simplification batch: remove the remaining fresh-schema migration sentinels by backfilling canonical launch-descriptor generation revisions in [V11__launch_descriptor_framework.sql](../../../services/game-design-service/src/main/resources/db/migration/V11__launch_descriptor_framework.sql) and deterministic Game Session effect keys in the then-live migration chain, then re-home the remaining destructive migration-squash execution follow-through under the Flyway/reset slice family instead of leaving it as an orphan tail in this audit slice.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-18-2-task-list-internal-grpc-auth-propagation-vertical-slice-1-72

#### `02.18.2` Internal Blocking gRPC Auth Propagation - Internal gRPC authentication propagation (source lines 1-72)

##### Preserved Source Text: source-02-18-2-task-list-internal-grpc-auth-propagation-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/02.18.2-task-list-internal-grpc-auth-propagation-vertical-slice.md" lines="1-72" sha256="65d96f35bc9226939dc7386e5fa8992bfdaf794ece95161c10847d7fd81cfd9d" heading-offset="3" -->
#### source-02-18-2-task-list-internal-grpc-auth-propagation-vertical-slice-1-72: `02.18.2` Internal Blocking gRPC Auth Propagation

Goal: make secured internal blocking gRPC calls attach the required auth metadata so downstream JWT-protected RPCs work consistently and safely. Status: complete for the audited blocking-client set.

##### source-02-18-2-task-list-internal-grpc-auth-propagation-vertical-slice-1-72: Implementation Notes

This slice is complete for the audited blocking-client set and now has one canonical shared seam.

Implemented now:

- concrete admin/design/world/game-session clients already attach auth through `GrpcClientAuth`;
- common-platform-core now has a shared blocking-stub customization seam so shared clients can opt into auth attachment without importing common-security directly;
- common-security now provides the auth-attaching implementation of that seam when a local `JwtUtil` is present;
- `GameDesignSettingsAuthorityClient` now uses that seam instead of remaining a permanently raw blocking stub.
- the remaining reloading blocking clients in account, logging-admin, world-management, game-design, and social-groups now also use the shared stub-customizer seam instead of wiring `JwtUtil` and `GrpcClientAuth` individually in each client.
- `game-logic-service` now also applies the same seam to its blocking downstream stubs without introducing direct JWT, session-context, or header-coupled source references.
- `game-session-service` secured blocking clients now also route through the same shared stub-customizer seam, so the remaining direct caller-side JWT wiring is limited to intentional outliers instead of being the default client pattern.
- focused proof tests now cover the first canonical secured flows:
  - Logging/Admin -> Account
  - Logging/Admin -> Game Session
  - Game Design -> Automation Scripting

Follow-up boundary:

- no additional secured internal blocking business clients are expected to remain raw by accident;
- if a new raw-stub outlier appears, it must be justified in this slice before it is treated as acceptable.

Locked now:

- the shared blocking-stub auth seam is the canonical pattern for secured internal blocking gRPC clients;
- raw-stub outliers are acceptable only when they are intentionally different in role, such as public ingress-style or unauthenticated/bootstrap paths;
- those outliers must be documented explicitly in this slice rather than lingering as accidental exceptions.
- `TcpProxyEventClient` is the current intentional outlier because it is an ingress-side telnet event bridge, not a normal authenticated internal business client calling protected domain RPCs on behalf of a trusted service identity.

##### source-02-18-2-task-list-internal-grpc-auth-propagation-vertical-slice-1-72: Why This Follow-Up Exists

Several downstream services already require JWT on gRPC calls, but the shared internal blocking clients still construct raw stubs with no auth metadata. That leaves a bad split:

- some internal flows are already structurally broken;
- others will fail as soon as auth enforcement is tightened on the receiver.

This is especially visible for:

- Logging/Admin calling secured account and game-session RPCs;
- game-design calling automation-scripting after publish;
- world/entity/game-session cross-service calls once world/entity auth is fully enabled.

##### source-02-18-2-task-list-internal-grpc-auth-propagation-vertical-slice-1-72: Target State

- Every internal blocking gRPC client that calls a secured downstream service attaches the required auth metadata.
- The auth-attachment pattern lives at the right dependency layer instead of forcing common-platform-core to import common-security.
- Receivers continue to enforce JWT normally; the fix is on the caller side, not by weakening service auth.
- Tests cover the canonical caller pattern so future clients do not regress to raw unauthenticated stubs.

##### source-02-18-2-task-list-internal-grpc-auth-propagation-vertical-slice-1-72: Required Changes

- [x] Choose the final caller-side auth propagation seam that respects module boundaries.
- [x] Wire that seam into the currently affected reloading blocking clients.
- [x] Extend the seam to `game-logic-service` without violating its no-JWT/no-session-context source rule.
- [x] Extend the same seam to the secured non-reloading blocking clients in `game-session-service`.
- [x] Validate the first secured flows end to end, especially:
  - Logging/Admin -> Account
  - Logging/Admin -> Game Session
  - Game Design -> Automation Scripting
- [x] Document or clean up the remaining intentional outliers now that the canonical pattern is locked.
- [x] Add focused tests or integration proof so “raw stub with no auth” is no longer the default pattern.

##### source-02-18-2-task-list-internal-grpc-auth-propagation-vertical-slice-1-72: Explicitly Out Of Scope

- weakening downstream JWT enforcement;
- dual-path compatibility layers for unauthenticated internal calls;
- generic auth propagation for reactive/websocket flows unrelated to these blocking clients.
<!-- /migration-source -->

### source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-1-7-10-22-25-37-40-41

#### Shared Saga Ownership and Control-Plane Facade Thinning Vertical Slice - Shared saga repository ownership and module layering (source lines 1-7, 10-22, 25-37, 40-41)

##### Preserved Source Text: source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-1-7-10-22-25-37-40-41

<!-- migration-source path="design/project-management/vertical-slices/02.18.20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice.md" lines="1-7, 10-22, 25-37, 40-41" sha256="7c67a0c39e14a7c53b17c942f86d5fb1ea2e9b7b50541213bd418cbecc00b6ae" heading-offset="3" -->
#### source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-1-7-10-22-25-37-40-41: Shared Saga Ownership and Control-Plane Facade Thinning Vertical Slice

Goal: close the last meaningful follow-up from the 2026-05-16 architecture audit by making saga persistence ownership explicit in `common-saga` instead of auto-synthesized through `common-data-runtime`, and by thinning the remaining Game Session control-plane gRPC façade down to transport/auth/error handling plus narrow delegation. Status: implemented.

##### source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-1-7-10-22-25-37-40-41: Implementation Notes

- Shared saga repository ownership is now explicit in [CommonSagaAutoConfiguration.java](../../../services/common-saga/src/main/java/net/firedevops/firemud/common/config/CommonSagaAutoConfiguration.java), which creates the canonical `SagaInstanceRepository` and `SagaStepRepository` beans directly when saga entities are present. [DatabaseAutoConfiguration.java](../../../services/common-data-runtime/src/main/java/net/firedevops/firemud/common/config/DatabaseAutoConfiguration.java) no longer synthesizes saga repositories, and [common-data-runtime/build.gradle.kts](../../../services/common-data-runtime/build.gradle.kts) no longer carries a module-level `common-saga` dependency just to expose that hidden seam.
<!-- source-gap: lines 8-9 -->
##### source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-1-7-10-22-25-37-40-41: Why This Slice Exists

`02.18.19` closed the main orchestration and pre-v1 simplification wave, but two audit signals were still likely to recur in future reviews:

- `common-data-runtime` still owned saga repository synthesis even after per-service wrapper interfaces were deleted, leaving the shared-library layering heavier and less explicit than it needs to be.
- `GameSessionControlPlaneGrpcService` still retained one large command/control-plane cluster with status projection, staged automation admission, alias validation, and supporting mapping helpers, leaving the façade substantially larger than the surrounding extracted control-plane collaborators.

This slice exists to finish those two seams deliberately instead of letting them come back as recurring audit comments.

##### source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-1-7-10-22-25-37-40-41: Scope

- move canonical saga repository bean ownership into `common-saga` and remove the remaining `common-data-runtime` saga synthesis path;
- keep shared saga entity scan and `SagaRunner` wiring intact while making repository ownership explicit in the shared saga module;
<!-- source-gap: lines 23-24 -->
- update the audit-family docs and queue so this remaining follow-up is tracked and then closed explicitly.

##### source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-1-7-10-22-25-37-40-41: Out Of Scope

- reopening broader migration-squash work now owned by `02.17.2`;
- reopening the already-closed Tick / Automation control-plane decomposition batches from `02.18.19`;
- style-only repo-wide helper deduplication with no owning boundary.

##### source-02-18-20-task-list-shared-saga-ownership-and-control-plane-facade-thinning-vertical-slice-1-7-10-22-25-37-40-41: Checklist

- [x] Verify the remaining audit signal still exists in current code.
- [x] Move saga repository bean ownership from `common-data-runtime` to `common-saga`.
- [x] Remove the module-level `common-saga` dependency and saga repository synthesis from `common-data-runtime`.
<!-- source-gap: lines 38-39 -->
- [x] Update parent/progress docs to record this follow-up slice and close it once implemented.
- [x] Run touched-module checks, repo-wide `check`, and markdown hygiene.
<!-- /migration-source -->

### source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79

#### `02.18.3` Workflow Transaction Boundary Hardening - Workflow transaction boundaries (source lines 1-79)

##### Preserved Source Text: source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79

<!-- migration-source path="design/project-management/vertical-slices/02.18.3-task-list-workflow-transaction-boundary-hardening-vertical-slice.md" lines="1-79" sha256="59ff6c5993397f68bf058cbe0c6c336d327a6c43dccb251c78e9ca4156ce831b" heading-offset="3" -->
#### source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79: `02.18.3` Workflow Transaction Boundary Hardening

Goal: stop holding local database transactions open while workflow services perform remote side effects, and move those side effects to explicit coherent transaction boundaries. Status: implemented for the currently audited workflow and session-lifecycle paths.

##### source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79: Implementation Notes

The branch already contains the first correction:

- `@Transactional` has been removed from `PurchaseWorkflowServiceImpl`;
- `@Transactional` has been removed from `ModerationServiceImpl`.
- Game Session lifecycle no longer performs Redis/runtime work inside `beforeCommit`:
  - start/stop/restart now stage database rows through intermediate lifecycle states such as `STARTING` and `STOPPING`;
  - external dependency validation plus runtime-state mutation happen after the staging transaction commits;
  - final `RUNNING` / `STOPPED` truth is written in a second local transaction only after the runtime side succeeds;
  - compensation restores or removes staged rows/runtime state on failure instead of leaving false final truth committed.
- replacement-start rollback in `GameInstanceServiceImpl.startSession(..., true)` now snapshots the previous running session before mutation so a failed replacement path can restore the prior runtime truth correctly.

That closes the originally audited purchase/moderation outliers and the narrower Game Session lifecycle coupling problem that remained afterward.

Focused validation now exists for the current purchase and moderation workflow shape:

- purchase workflow tests prove that if the post-payment logging step fails, the payment intent is refunded through saga compensation rather than being left committed without cleanup;
- moderation workflow tests prove that if downstream session shutdown fails after the moderation record is written and account deletion is attempted, the recorded moderation action is compensated away rather than being left behind as false committed truth.

The narrower transaction-boundary audit on the known workflow outliers is also now clear:

- `PurchaseWorkflowServiceImpl` no longer wraps the saga in a broad service-level `@Transactional` boundary, so payment creation and follow-on logging run as explicit saga steps rather than one long local transaction;
- `ModerationServiceImpl` likewise runs record/save and downstream account/session mutations as explicit saga steps without a broad ambient service transaction.
- the remaining `afterCommit` seams reviewed during this pass, such as account notifications and social/chat side-effect publication, already match the intended narrow local-commit then outbound-effect pattern rather than the older workflow anti-pattern this slice was created to fix.
- Game Session lifecycle now uses the stricter staged-state model instead of that old `beforeCommit` tradeoff.

##### source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79: Why This Follow-Up Exists

Some services in the repo already move external effects to `afterCommit`, and the originally lagging workflow-heavy paths have now been brought back into line:

- account purchase no longer wraps payment creation plus follow-on logging in one broad local transaction;
- moderation no longer wraps persistence plus account/session mutation in one broad local transaction.

The slice existed to remove two risks:

- local transactions stay open across network calls;
- remote work can succeed even if the local transaction later fails.

The repo already has better examples elsewhere. This slice brings the lagging workflow services back to that standard.

##### source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79: Target State

- Workflow services commit local state before outbound effects fire.
- Remote side effects are triggered from `afterCommit` or an equivalent explicit post-commit seam.
- Local transaction scope is narrow and only wraps local database changes.
- Tests prove local rollback no longer implies remote side effects already happened.
- For runtime-critical state such as session lifecycle transitions, the authoritative state change must fail before commit if the corresponding runtime-state write cannot be made durable enough for the current architecture.
- For the currently audited workflow services, explicit saga-step orchestration is the accepted equivalent of a stricter post-commit seam; no additional outbox or deferred hook is required unless a future path reintroduces mixed local/remote work under one ambient transaction.
- For Game Session lifecycle specifically, the current accepted model is staged DB lifecycle state plus explicit post-commit runtime finalization/compensation rather than `beforeCommit` coupling.

##### source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79: Required Changes

- [x] Remove the obvious broad `@Transactional` wrapping from the known workflow outliers.
- [x] Move Game Session lifecycle runtime-state propagation out of best-effort `afterCommit` handling for `RUNNING` / `STOPPED`.
- [x] Audit those methods to ensure local persistence still has the right transactional boundary after the annotation change.
- [x] Move outbound work to explicit post-commit hooks or equivalent orchestration seams where needed for the audited workflow outliers.
- [x] Add focused validation for:
  - purchase workflow local commit vs remote logging/payment ordering;
  - moderation local persistence vs account/session mutation ordering.
- [x] Replace Game Session lifecycle `beforeCommit` runtime-state/dependency coupling with staged lifecycle state plus explicit post-commit finalization/compensation.

##### source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79: Explicitly Out Of Scope

- full outbox/event-bus rollout across every service;
- broad saga redesign unrelated to the audited workflow paths.
- replacing tick scheduling with a dedicated queue/lease scheduler, which belongs to runtime scheduling hardening rather than this transaction-boundary slice.

##### source-02-18-3-task-list-workflow-transaction-boundary-hardening-vertical-slice-1-79: Follow-Up Boundary

This slice is complete for the known purchase/moderation outliers and the audited Game Session lifecycle path. Reopen it only if a new workflow path is introduced that:

- holds a local transaction open across remote effects;
- mixes durable local truth with best-effort remote cleanup; or
- cannot be expressed safely as explicit saga steps or an existing narrow `afterCommit` seam.
<!-- /migration-source -->

### source-02-18-4-task-list-world-and-entity-service-boundary-auth-vertical-slice-1-47

#### `02.18.4` World and Entity Service Boundary Auth - World and entity service boundary authentication (source lines 1-47)

##### Preserved Source Text: source-02-18-4-task-list-world-and-entity-service-boundary-auth-vertical-slice-1-47

<!-- migration-source path="design/project-management/vertical-slices/02.18.4-task-list-world-and-entity-service-boundary-auth-vertical-slice.md" lines="1-47" sha256="03f2927a524ea639b441ed4584f0744308f2f33106f4d182ed7868f73240cb5a" heading-offset="3" -->
#### source-02-18-4-task-list-world-and-entity-service-boundary-auth-vertical-slice-1-47: `02.18.4` World and Entity Service Boundary Auth

Goal: bring world-management and entity-management up to the repo's baseline auth and tenant-access standard for both gRPC and REST boundaries. Status: implemented.

##### source-02-18-4-task-list-world-and-entity-service-boundary-auth-vertical-slice-1-47: Implementation Notes

This slice is now in place:

- service-local auth config classes and JWT interceptors have been added;
- gRPC services have been annotated with auth interceptors;
- all exposed tenant-scoped REST and gRPC surfaces now enforce tenant access at the boundary, including the previously missed entity friend REST path;
- tenant-access failures on the gRPC boundary now return normal `PERMISSION_DENIED` `ErrorDetail` payloads instead of falling through as `INTERNAL`;
- the old “no Jwt usage” tests were replaced with auth-wiring tests.
- internal gRPC callers were audited against the shared `BlockingGrpcStubCustomizer` / `GrpcClientAuth` path so protected service-to-service calls continue to attach auth metadata.

##### source-02-18-4-task-list-world-and-entity-service-boundary-auth-vertical-slice-1-47: Why This Follow-Up Exists

World-management and entity-management still lagged behind the rest of the repo:

- other services already enforce JWT on web and gRPC boundaries;
- these two still exposed major REST/gRPC surfaces without baseline auth or tenant checks.

That is especially risky because both services sit on canonical shared gameplay data:

- world topology and room snapshots;
- characters, inventories, equipment, room entities, and transfer operations.

##### source-02-18-4-task-list-world-and-entity-service-boundary-auth-vertical-slice-1-47: Target State

- World-management and entity-management enforce baseline JWT auth on gRPC and REST boundaries.
- Tenant-scoped operations require tenant access at the service boundary.
- Public or intentionally unauthenticated paths are explicit exceptions, not silent defaults.
- Their tests assert auth wiring exists instead of asserting the absence of JWT.

##### source-02-18-4-task-list-world-and-entity-service-boundary-auth-vertical-slice-1-47: Required Changes

- [x] Add service-local auth/web/grpc wiring in both services.
- [x] Protect the main gRPC services with auth interceptors.
- [x] Add tenant-access checks to the obvious tenant-scoped REST and gRPC operations.
- [x] Audit remaining controllers and RPCs for missed tenant-scoped paths.
- [x] Validate all internal callers that now hit these services with proper auth metadata.
- [x] Add focused tests proving both “auth wiring exists” and “tenant access is enforced” on the highest-value surfaces.

##### source-02-18-4-task-list-world-and-entity-service-boundary-auth-vertical-slice-1-47: Explicitly Out Of Scope

- external/public API product decisions unrelated to the current internal gameplay/data surfaces;
- broad field-level authorization beyond the tenant-boundary baseline.
<!-- /migration-source -->

### source-02-18-5-task-list-grpc-app-error-consistency-hardening-vertical-slice-1-61

#### `02.18.5` gRPC App-Error Consistency Hardening - gRPC application-error contract (source lines 1-61)

##### Preserved Source Text: source-02-18-5-task-list-grpc-app-error-consistency-hardening-vertical-slice-1-61

<!-- migration-source path="design/project-management/vertical-slices/02.18.5-task-list-grpc-app-error-consistency-hardening-vertical-slice.md" lines="1-61" sha256="dab1e20d03b0e8f085d631be988b16a7378e8fd445cc663c416ea54d0bbc820c" heading-offset="3" -->
#### source-02-18-5-task-list-grpc-app-error-consistency-hardening-vertical-slice-1-61: `02.18.5` gRPC App-Error Consistency Hardening

Goal: finish moving the visible gRPC outliers back to the shared `GrpcAppErrors` contract so application failures use one canonical response/logging/metrics path. Status: implemented for the currently visible service set.

##### source-02-18-5-task-list-grpc-app-error-consistency-hardening-vertical-slice-1-61: Implementation Notes

This slice is already partway landed on the branch:

- `AccountGrpcService` has started adopting `GrpcAppErrors` and preserving a narrow fallback path for tests that instantiate it without Spring metrics;
- `GatewayManagementGrpcService` has been reshaped to return normal app-error responses instead of dropping to transport `onError(...)` for ordinary application failures.
- current admin gRPC services now return `PERMISSION_DENIED` through normal response payloads instead of transport exceptions by using a shared explicit admin-role guard.
- `SocialGroupsGrpcService` now returns in-band `INTERNAL` `ErrorDetail` payloads for ordinary runtime failures instead of leaking them as transport errors on its visible RPCs.
- `NotificationGrpcService`, `PaymentGrpcService`, and `VirtualCurrencyGrpcService` now return in-band `INTERNAL` `ErrorDetail` payloads for ordinary runtime failures instead of leaking them as transport errors.
- the remaining visible `GameSessionGrpcService` RPCs now return in-band `INTERNAL` `ErrorDetail` payloads for ordinary runtime failures instead of leaking them as transport errors.

The focused repo scan for the original visible-drift pattern now comes back clean: no remaining visible service gRPC endpoints are left in the `INVALID_ARGUMENT`-only / transport-leak state this slice was targeting.

Another concrete example was the public Game Session lifecycle API:

- `startSession`, `stopSession`, and `restartSession` now correctly fail before commit when critical runtime-state propagation or dependency validation fails;
- those failures now return normal response `ErrorDetail` payloads instead of leaking as uncaught transport failures.

##### source-02-18-5-task-list-grpc-app-error-consistency-hardening-vertical-slice-1-61: Why This Follow-Up Exists

The repo already has a standard helper for gRPC application errors:

- return normal app-error payloads;
- log warnings;
- increment `grpc.app_error`;
- tag spans.

But a few visible services still drifted from that contract:

- account-service still hand-built `ErrorDetail` responses inconsistently;
- gateway management still had application-failure paths that could fall back to transport `onError`.
- social-groups had visible RPCs that only normalized `INVALID_ARGUMENT`, leaving ordinary runtime failures to escape as transport errors.
- parts of account-service still normalized only `INVALID_ARGUMENT` and hand-built `ErrorDetail` responses without the shared helper path.

That weakens observability and teaches the wrong pattern to future service code.

##### source-02-18-5-task-list-grpc-app-error-consistency-hardening-vertical-slice-1-61: Target State

- Application failures are returned through normal response payloads with `ErrorDetail`.
- Transport `onError` is reserved for real transport/infrastructure failures.
- The remaining visible gRPC services use `GrpcAppErrors` consistently.
- Tests reflect the shared contract instead of bespoke service-local error-building habits.

##### source-02-18-5-task-list-grpc-app-error-consistency-hardening-vertical-slice-1-61: Required Changes

- [x] Start adopting `GrpcAppErrors` in `AccountGrpcService`.
- [x] Remove ordinary application-failure `onError` fallback from `GatewayManagementGrpcService`.
- [x] Convert current admin gRPC authorization failures from transport exceptions into normal `ErrorDetail` responses with the shared metrics/logging/span path.
- [x] Convert Game Session lifecycle RPC failures caused by runtime-state/dependency validation into normal response `ErrorDetail` payloads instead of transport errors.
- [x] Remove the obsolete `@RequireAdminRole` transport-error aspect now that active admin gRPC services use the explicit in-band guard path.
- [x] Finish validation for the touched services and update tests where needed.
- [x] Audit the repo for other visible gRPC outliers that should follow the same contract.

##### source-02-18-5-task-list-grpc-app-error-consistency-hardening-vertical-slice-1-61: Explicitly Out Of Scope

- changing the repo-wide invariant that transport failures may still use `onError`;
- redesigning the `ErrorDetail` schema itself.
<!-- /migration-source -->

### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122

#### jOOQ and Flyway Persistence Convergence Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-122)

##### Preserved Source Text: source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122

<!-- migration-source path="design/project-management/vertical-slices/02.19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice.md" lines="1-122" sha256="797ab174806a6e6d0ade5b2a9bae44f4dae49f108bd07ac21228e85c70fd057d" heading-offset="3" -->
#### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: jOOQ and Flyway Persistence Convergence Vertical Slice

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Goal and Status

Goal: move FireMUD to one explicit SQL persistence model across SQL-backed services by standardizing on `jOOQ + Flyway`, using shared codegen/runtime helpers in common packages, migrating every current JPA-backed SQL service, and then removing Hibernate/JPA platform support instead of carrying a mixed persistence stack indefinitely. Status: implemented.

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Why This Slice Exists

The repo has now converged strongly on explicit schema control, explicit control-plane queries, durable queue/ledger/projection tables, and pre-v1 willingness to rewrite persistence seams directly. That shape is a poor long-term fit for a mixed `Hibernate/JPA` plus `Flyway` architecture:

- it keeps SQL behavior partially implicit in the services where query behavior matters most;
- it duplicates contributor habits, testing style, and shared persistence helpers;
- it keeps common infrastructure split between ORM-driven and SQL-driven assumptions;
- it invites recurring architecture and audit comments about persistence indirection, query predictability, and schema-heavy services.

This slice family makes the architectural decision explicit: for FireMUD SQL-backed services, `jOOQ + Flyway` is the target persistence stack, and JPA/Hibernate is migration debt rather than a co-equal long-term option.

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Verified Current Surface

When this family was opened, the repo still had live JPA-backed persistence across all current SQL-heavy business services:

- `account-service`
- `automation-scripting-service`
- `entity-management-service`
- `game-design-service`
- `game-session-service`
- `logging-admin-service`
- `social-groups-service`
- `world-management-service`

The edge/proxy services and common support modules are not themselves the primary migration targets, but they will absorb shared build/runtime helper changes as the service migrations land.

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Architecture Decision

- FireMUD should use `jOOQ + Flyway` as the canonical SQL persistence stack for SQL-backed services.
- No new architectural investment should deepen `Hibernate/JPA` dependence in those services.
- Shared persistence helpers in common packages should optimize for explicit SQL generation/execution, typed record mapping, transaction clarity, pagination/filter helpers, and predictable error translation rather than ORM abstractions.
- JPA/Hibernate should remain only until each owning service migration slice is complete.

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Scope

- establish the shared `jOOQ` build/codegen/runtime substrate;
- migrate every current SQL-backed JPA service onto that substrate;
- update service tests, proof helpers, and local reset/bootstrap expectations as each service migrates;
- update the high-level architecture, migration, repository-structure, and developer-tooling docs so the persistence direction is visible outside the slice queue;
- remove repo-wide Hibernate/JPA support once the last service migration is complete.

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Out of Scope

- replacing non-SQL persistence or transport layers;
- introducing a mixed long-term “JPA for CRUD, jOOQ for complex services” compromise;
- carrying compatibility wrappers just to preserve old repository styles during the pre-v1 migration.

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Planned Child Slices

1. [02.19.1 Shared jOOQ Build, Codegen, and Runtime Foundation](../vertical-slices/02.19.1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice.md)
2. [02.19.2 Automation Scripting jOOQ Migration](../vertical-slices/02.19.2-task-list-automation-scripting-jooq-migration-vertical-slice.md)
3. [02.19.3 Game Session jOOQ Migration](../vertical-slices/02.19.3-task-list-game-session-jooq-migration-vertical-slice.md)
4. [02.19.4 Game Design jOOQ Migration](../vertical-slices/02.19.4-task-list-game-design-jooq-migration-vertical-slice.md)
5. [02.19.5 Entity Management jOOQ Migration](../vertical-slices/02.19.5-task-list-entity-management-jooq-migration-vertical-slice.md)
6. [02.19.6 Logging Admin jOOQ Migration](../vertical-slices/02.19.6-task-list-logging-admin-jooq-migration-vertical-slice.md)
7. [02.19.7 World Management jOOQ Migration](../vertical-slices/02.19.7-task-list-world-management-jooq-migration-vertical-slice.md)
8. [02.19.8 Social Groups jOOQ Migration](../vertical-slices/02.19.8-task-list-social-groups-jooq-migration-vertical-slice.md)
9. [02.19.9 Account Service jOOQ Migration](../vertical-slices/02.19.9-task-list-account-service-jooq-migration-vertical-slice.md)
10. [02.19.10 Hibernate and JPA Runtime Removal](../vertical-slices/02.19.10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice.md)
11. [02.19.11 Shared Persistence Contract and Saga Topology Cleanup](../vertical-slices/02.19.11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice.md)
12. [02.19.12 Flyway History Contract and Hosted SQL Proof Cleanup](../vertical-slices/02.19.12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice.md)

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Recommended Execution Order

1. Shared `jOOQ` foundation
2. `automation-scripting-service`
3. `game-session-service`
4. `game-design-service`
5. `entity-management-service`
6. `logging-admin-service`
7. `world-management-service`
8. `social-groups-service`
9. `account-service`
10. final Hibernate/JPA removal

This order optimizes for setting the house style earliest in the SQL-heavy control-plane and queue/projection services, then lets simpler CRUD-shaped services migrate onto a stable shared substrate.

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Checklist

- [x] Verify that a repo-wide SQL persistence convergence decision is still a live architectural seam rather than an already-closed audit note.
- [x] Choose one canonical target stack for SQL-backed services instead of preserving a mixed long-term JPA/jOOQ model.
- [x] Land the shared `jOOQ` build/codegen/runtime foundation.
- [x] Migrate `automation-scripting-service`.
  The service now has no remaining Spring Data JPA repository surface; only the later family-wide Hibernate/JPA runtime-platform removal remains, and that cleanup is intentionally tracked in `02.19.10` rather than left as hidden service debt.
- [x] Migrate `game-session-service`.
  The service now has no remaining Spring Data JPA repository surface: admission-pointer authority and audit, prepared version-upgrade persistence, feature flags, game manifests, game instances, gameplay command rows, runtime region status, tick batch/effect persistence, and the remote followup/result/coordinator cluster are all on explicit `jOOQ` repositories. The later Hibernate/JPA runtime/platform cleanup remains intentionally tracked in `02.19.10`.
- [x] Migrate `game-design-service`.
  The service now has no remaining Spring Data JPA repository surface: publication attempts and participant digests, published release bundles, launch descriptors, version asset artifacts and purge workflows, recorded participant digests, versions/games/templates/assets, revisions, settings overrides, template remap sets/entries, and plugin publication/status control-plane persistence are all on explicit `jOOQ` repositories. The later Hibernate/JPA runtime/platform cleanup remains intentionally tracked in `02.19.10`.
- [x] Migrate `entity-management-service`.
  The service now has no remaining Spring Data JPA repository surface: characters, items, NPCs, actor state/conditions, equipment/body-layout definitions, character friends, visible-ref counters, transfer audit, item instances, item stacks, container instances, inventory/equipment/room-ground projections, crafting recipes, and entity mutation effect replay state are all on explicit `jOOQ` repositories. The later Hibernate/JPA runtime-platform cleanup remains intentionally tracked in `02.19.10`.
- [x] Migrate `logging-admin-service`.
  The service now has no remaining Spring Data JPA repository surface: moderation actions, player reports, and log-event persistence plus moderation policy reads are all on explicit `jOOQ` repositories. The later Hibernate/JPA runtime-platform cleanup remains intentionally tracked in `02.19.10`.
- [x] Migrate `world-management-service`.
  The service now has no remaining Spring Data JPA repository surface: generation rules, authored region/zone/room topology, room exits, world-design aggregate/scope epoch and revision-ledger persistence, world-entity spawn bindings, runtime world/region/zone/room instance rows plus runtime exits, and world-event history are all on explicit `jOOQ` repositories. The later Hibernate/JPA runtime-platform cleanup remains intentionally tracked in `02.19.10`.
- [x] Migrate `social-groups-service`.
  The service now has no remaining Spring Data JPA repository surface: account-scoped friend links, guilds, guild members, guild alliances, guild storage items, chat-message persistence and effect-id replay lookup, and mail-message persistence are all on explicit `jOOQ` repositories. The later Hibernate/JPA runtime-platform cleanup remains intentionally tracked in `02.19.10`.
- [x] Migrate `account-service`.
  The service now has no remaining Spring Data JPA repository surface: account identity, tenant membership, profiles, realm-access grants, email/password token flows, linked external accounts, payment/subscription rows, and virtual-currency balances are all on explicit `jOOQ` repositories. The later Hibernate/JPA runtime-platform cleanup remains intentionally tracked in `02.19.10`.
- [x] Keep the high-level architecture and developer/tooling docs aligned as the migration family lands.
- [x] Remove Hibernate/JPA runtime support once all service migrations are complete.
- [x] Close the remaining shared saga/test-support/build-convention audit tails after runtime removal.

##### source-02-19-task-list-jooq-and-flyway-persistence-convergence-vertical-slice-1-122: Final Follow-Through

`02.19.11` closes the last small but real post-closeout tails that were still likely to trigger future audits:

- `common-saga` now uses explicit service-schema-qualified `jOOQ` tables and has repository-level proof that no longer depends on ambient `currentSchema` behavior;
- shared Postgres-backed test support now resolves migration directories through canonical schema-to-module mapping instead of guessing from schema names;
- shared Gradle convention naming now teaches SQL-era rather than JPA-era plugin ids;
- the remaining docs that still described `common-library`-owned saga migrations, separate saga Flyway passes, or dedicated `saga` schemas were aligned with the actual `common-saga` plus combined `classpath:db/migration,classpath:db/migration/saga` runtime.

`02.19.12` closes the last repo-level SQL contract drift after that core convergence:

- service boot, shared Postgres-backed test support, Helm-hosted runtime manifests, Docker Compose, and destructive reset tooling now all use the same service-local `flyway_schema_history_<service_schema>` naming convention;
- `logging-admin-service` now has the same real Postgres/Flyway application-boot proof as the other migrated SQL services instead of a fake H2/no-database ping seam;
- the canonical local Docker runtime manifest no longer exports dead Hibernate/JPA environment knobs after runtime removal.
<!-- /migration-source -->

### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61

#### Shared jOOQ Build, Codegen, and Runtime Foundation Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-61)

##### Preserved Source Text: source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61

<!-- migration-source path="design/project-management/vertical-slices/02.19.1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice.md" lines="1-61" sha256="bbd4d6efdeec753683a17f34d5c2cc57ae357db2de219e378b33bb9bea2b0952" heading-offset="3" -->
#### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61: Shared jOOQ Build, Codegen, and Runtime Foundation Vertical Slice

##### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61: Goal and Status

Goal: establish one shared `jOOQ + Flyway` build/runtime substrate that every SQL-backed FireMUD service can adopt without inventing service-local codegen, transaction, mapper, or pagination conventions. Status: implemented at the first-adopter foundation boundary.

##### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61: Implementation Notes

- The shared foundation now lands as a Gradle convention plus one first-adopter proof path rather than as a speculative thick runtime abstraction layer.
- Generated DSL sources are derived from Flyway-owned SQL using `jOOQ`'s DDL database path, so the canonical schema source remains the checked-in migration files instead of a second hand-maintained schema description.
- The first adopter is `automation-scripting-service`, which now proves that a service can generate and compile `jOOQ` artifacts through the shared convention path without bespoke build logic in the service module.

##### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61: Scope

- add the shared Gradle/plugin/codegen wiring needed for `jOOQ` generation against service schemas;
- define shared naming/package conventions for generated DSL/records;
- provide common transaction, pagination, sort/filter, JSON/enum/timestamp, and constraint/error-translation helpers where they are truly shared;
- update the high-level tool and architecture docs so contributors can discover the new SQL workflow without reading slice docs first;
- document one canonical service-adoption pattern so later migrations do not each redesign build/runtime shape.

##### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61: Out of Scope

- migrating business services themselves;
- preserving JPA-compatible abstractions as a permanent shared API.

##### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61: Acceptance Criteria

- at least one service can generate and compile `jOOQ` artifacts through the shared path without ad hoc build logic;
- common-package helpers exist only for real repeated concerns rather than speculative platform layers;
- service migration slices can depend on one documented adoption pattern instead of rediscovering build/codegen conventions.

##### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61: Canonical Adoption Pattern

The current shared adoption pattern for a SQL-backed service is:

1. keep Flyway as the only schema authority;
2. apply `net.firedevops.firemud.jooq-conventions` in the service build;
3. accept the default generated package convention `net.firedevops.firemud.<servicebase>.jooq` unless the service has a compelling reason to override it;
4. generate DSL and record sources with `./gradlew :<service>:generateJooq`;
5. migrate one repository/query seam at a time onto `DSLContext` and generated table/record types instead of inventing a second local SQL style.

The first adopter proof is `automation-scripting-service`, which now generates DSL code from `src/main/resources/db/migration/*.sql` through the shared convention without service-local task wiring.

##### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61: Runtime Convention Notes

- The first foundation cut intentionally avoids a speculative thick shared runtime helper layer.
- Shared runtime helpers for paging/filter/sort, transaction scoping, or SQL error translation should be added only when the first service migrations prove real repeated value.
- Current shared type conventions are:
  - Flyway SQL is the canonical schema source for generation.
  - `jOOQ` Java time types are enabled by default.
  - database-enum-specific bindings are deferred until a migrated service actually needs them.
  - JSON/structured payload columns continue to use the explicit SQL/storage type already declared by the owning service schema until a repeated shared binding need emerges.

##### source-02-19-1-task-list-shared-jooq-build-codegen-and-runtime-foundation-vertical-slice-1-61: Checklist

- [x] Choose the canonical Gradle/plugin integration pattern for `jOOQ` generation.
- [x] Define shared generated-source package and naming conventions.
- [x] Add common runtime helpers for transaction scope, paging/filter/sort, and SQL error translation where shared value is real.
- [x] Define canonical JSON/enum/timestamp mapping conventions.
- [x] Update developer/tooling docs for the shared `jOOQ` generation workflow.
- [x] Document the service migration template used by the later `02.19.x` slices.
<!-- /migration-source -->

### source-02-19-10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice-1-34

#### Hibernate and JPA Runtime Removal Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-34)

##### Preserved Source Text: source-02-19-10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice-1-34

<!-- migration-source path="design/project-management/vertical-slices/02.19.10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice.md" lines="1-34" sha256="168b0172984025c19d22a84b082bc7d1aabf1038c00934eae92757cfd8ad2a9c" heading-offset="3" -->
#### source-02-19-10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice-1-34: Hibernate and JPA Runtime Removal Vertical Slice

##### source-02-19-10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice-1-34: Goal and Status

Goal: remove the remaining repo-wide Hibernate/JPA runtime, build, and shared-infrastructure support once every SQL-backed service has completed its `jOOQ + Flyway` migration, leaving one canonical SQL persistence stack in the repo. Status: implemented.

##### source-02-19-10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice-1-34: Scope

- delete shared JPA/Hibernate build/runtime support no longer needed by any service;
- remove service-level ORM configuration, annotations, and helper infrastructure left behind after migration;
- document one canonical current persistence model for contributors and operators.

##### source-02-19-10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice-1-34: Checklist

- [x] Verify that every SQL-backed service migration slice in `02.19` is complete.
- [x] Remove shared Hibernate/JPA runtime/build dependencies from common infrastructure.
- [x] Remove remaining service-local ORM configuration and dead persistence helpers.
- [x] Update architecture and contributor docs so `jOOQ + Flyway` is the only documented SQL path.

##### source-02-19-10-task-list-hibernate-and-jpa-runtime-removal-vertical-slice-1-34: Implementation Notes

This slice closed the repo-wide runtime tail after the service-by-service `jOOQ` migrations landed:

- shared build/runtime conventions no longer depend on `spring-boot-starter-data-jpa`, and Spring Data paging/sorting contracts now ride `spring-data-commons` directly instead of an ORM starter;
- `common-data-runtime` now owns the canonical JDBC/Flyway/Postgres contract without Hibernate default-schema assumptions;
- `common-saga` now persists saga rows through explicit `jOOQ` repositories instead of JPA entity scanning and repository synthesis;
- service application classes, entity classes, and `application*.yml` files were cleaned of the remaining JPA/Hibernate annotations and configuration that no longer had a live runtime purpose;
- the shared Postgres-backed integration test contract was widened in `common-test-support` so Postgres/Flyway/service-schema boot is expressed once and reused across services, including formerly H2-backed Game Session integration seams that now follow the canonical Postgres-backed proof path;
- the final proof path required closing a few real post-Hibernate fallout seams instead of papering over them:
  - Logging Admin saga dashboard availability is now conditional on the shared saga persistence beans and fails closed with an application error when the dashboard substrate is absent;
  - Game Session no-db bootstrap/recovery beans are now explicitly gated so disabled-database contexts do not eagerly create real SQL-backed components;
  - shared datasource/schema handling now carries the owning service schema into JDBC/Flyway paths so unqualified SQL and `JdbcTemplate` tests do not depend on old Hibernate default-schema behavior.

The repo now teaches one canonical SQL runtime model: `jOOQ + Flyway`.
<!-- /migration-source -->

### source-02-19-11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice-1-42

#### Shared Persistence Contract and Saga Topology Cleanup Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-42)

##### Preserved Source Text: source-02-19-11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice-1-42

<!-- migration-source path="design/project-management/vertical-slices/02.19.11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice.md" lines="1-42" sha256="ef1b237bc3354ca15efd1e37681accf99bdb90bf4c6886abc4d71af946178af6" heading-offset="3" -->
#### source-02-19-11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice-1-42: Shared Persistence Contract and Saga Topology Cleanup Vertical Slice

##### source-02-19-11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice-1-42: Goal and Status

Goal: close the remaining post-`02.19.10` audit tails in the shared persistence/test-support/build-convention layer so FireMUD teaches one coherent `jOOQ + Flyway` story all the way down to `common-saga`, shared Postgres-backed test support, and build plugin naming. Status: implemented.

##### source-02-19-11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice-1-42: Scope

- make `common-saga` repository table access depend on an explicit service-schema contract rather than implicit `currentSchema` behavior;
- add repository-level persistence proof that fails if saga correctness silently falls back onto global search-path coupling;
- make shared Postgres/Flyway test support resolve service migration directories canonically instead of guessing them from schema names;
- rename the remaining JPA-era convention plugin ids/classes so shared build tooling teaches SQL-era naming;
- remove the stale architecture wording that still described `common-library`, dedicated saga schemas, or separate saga Flyway passes as the canonical current model.

##### source-02-19-11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice-1-42: Checklist

- [x] Qualify `common-saga` repository table access with the owning service schema.
- [x] Add focused repository persistence proof for the shared saga repositories.
- [x] Make `PostgresBackedServiceTestSupport` use canonical schema-to-module mapping.
- [x] Rename the lingering JPA-era Gradle convention plugin ids/classes to SQL-era names and update adopters.
- [x] Sweep the remaining architecture and slice docs that still taught the pre-closeout saga migration topology.

##### source-02-19-11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice-1-42: Implementation Notes

This follow-up closed the last meaningful audit tails left after the larger `02.19` and `02.20` convergence families were already merged:

- `common-saga` repositories now target `${serviceSchema}.saga_instance` and `${serviceSchema}.saga_step` explicitly through `CommonSagaAutoConfiguration` instead of relying on the datasource search path to find unqualified table names.
- `SagaPersistenceRepositoryIntegrationTest` proves that repository reads/writes still work after forcing PostgreSQL `search_path` back to `public`, so saga persistence no longer depends on ambient default-schema behavior.
- `PostgresBackedServiceTestSupport` now keeps an explicit schema-to-module map, which covers the `gateway -> spring-cloud-gateway` mismatch and makes the shared test helper match the same canonical service-directory truth as the reset tooling.
- Shared Gradle conventions now use SQL-era plugin ids (`sql-postgres-conventions`, `secured-sql-aop-service-conventions`) instead of carrying JPA naming after the repo-wide runtime removal is complete.
- The remaining doc drift is gone: shared saga migrations are now documented as bundled `classpath:db/migration/saga` resources from `common-saga`, applied alongside service-local migrations into the owning service schema instead of through a dedicated `saga` schema or a `common-library`-owned separate Flyway pass.

##### source-02-19-11-task-list-shared-persistence-contract-and-saga-topology-cleanup-vertical-slice-1-42: Validation

- `./gradlew :common-saga:test`
- `./gradlew :common-saga:integrationTest`
- `./gradlew :common-test-support:testFixturesJar`
- `./gradlew :buildSrc:check`
- `./gradlew :game-design-service:compileJava :logging-admin-service:compileJava`
- `./gradlew spotlessApply`
- `./gradlew linkCheck lintMarkdown`
- `./gradlew check`
<!-- /migration-source -->

### source-02-19-2-task-list-automation-scripting-jooq-migration-vertical-slice-1-35

#### Automation Scripting jOOQ Migration Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-35)

##### Preserved Source Text: source-02-19-2-task-list-automation-scripting-jooq-migration-vertical-slice-1-35

<!-- migration-source path="design/project-management/vertical-slices/02.19.2-task-list-automation-scripting-jooq-migration-vertical-slice.md" lines="1-35" sha256="e30ddbd183168f436370c5f7d604ed8bde34befced59bef80c8e25797358bace" heading-offset="3" -->
#### source-02-19-2-task-list-automation-scripting-jooq-migration-vertical-slice-1-35: Automation Scripting jOOQ Migration Vertical Slice

##### source-02-19-2-task-list-automation-scripting-jooq-migration-vertical-slice-1-35: Goal and Status

Goal: make `automation-scripting-service` the first full `jOOQ + Flyway` service migration so the repo’s new SQL house style is proven on a queue/projection-heavy control-plane service rather than on a low-signal CRUD seam. Status: implemented.

##### source-02-19-2-task-list-automation-scripting-jooq-migration-vertical-slice-1-35: Implementation Notes

- The service migration now covers the full current repository seam rather than only the earlier control-plane subset.
- `script_work_items`, readiness/pin/rollout projections, script event ingress/audit plus handoff history, rollout/runtime event history, plugin runtime state, automation admission state, authored script/binding plus schedule-definition/schedule-instance persistence, and the NPC/faction gameplay-support seam are all now backed by explicit `jOOQ` repositories.
- There is no remaining Spring Data JPA repository surface in `automation-scripting-service`; the remaining Hibernate/JPA cleanup is the later family-wide runtime/platform removal tracked separately in `02.19.10`.

##### source-02-19-2-task-list-automation-scripting-jooq-migration-vertical-slice-1-35: Why This Service Goes First

`automation-scripting-service` is the strongest early proving ground:

- it is SQL-heavy;
- it owns work-item, schedule, rollout, readiness, and control-plane query seams where explicit SQL is valuable;
- it is central enough to prove the pattern, but less risky than `game-session-service` as the first migration.

##### source-02-19-2-task-list-automation-scripting-jooq-migration-vertical-slice-1-35: Scope

- replace the service’s JPA repositories with `jOOQ`-backed persistence seams;
- keep the current Flyway baseline as the schema authority;
- migrate the control-plane list/query surfaces, work-item reads/writes, and rollout/readiness projections onto explicit SQL ownership;
- update tests and local proof so the service’s product-code persistence path is fully on `jOOQ`, leaving only the later family-wide runtime/platform cleanup.

##### source-02-19-2-task-list-automation-scripting-jooq-migration-vertical-slice-1-35: Checklist

- [x] Inventory the current JPA entity/repository surface and map it to bounded SQL ownership seams.
- [x] Replace repository-driven persistence with `jOOQ` queries and mappers.
- [x] Remove JPA/Hibernate runtime/config dependencies from the service.
  Closed by the repo-wide runtime removal completed under `02.19.10`; there is no remaining service-local cleanup seam here.
- [x] Update service proof and local reset/bootstrap expectations.
- [x] Confirm the service now exemplifies the canonical FireMUD SQL persistence style.
<!-- /migration-source -->

### source-02-19-3-task-list-game-session-jooq-migration-vertical-slice-1-28

#### Game Session jOOQ Migration Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-28)

##### Preserved Source Text: source-02-19-3-task-list-game-session-jooq-migration-vertical-slice-1-28

<!-- migration-source path="design/project-management/vertical-slices/02.19.3-task-list-game-session-jooq-migration-vertical-slice.md" lines="1-28" sha256="fecb82c5711b3afc1773f02d15d3d307a47241d6115cf5b59f27ed284ec8735a" heading-offset="3" -->
#### source-02-19-3-task-list-game-session-jooq-migration-vertical-slice-1-28: Game Session jOOQ Migration Vertical Slice

##### source-02-19-3-task-list-game-session-jooq-migration-vertical-slice-1-28: Goal and Status

Goal: migrate `game-session-service` from JPA/Hibernate to `jOOQ + Flyway` so the heaviest ledger, queue, runtime-ownership, and control-plane service uses the same explicit SQL model the repo has chosen as its standard. Status: implemented.

##### source-02-19-3-task-list-game-session-jooq-migration-vertical-slice-1-28: Scope

- replace JPA repository/entity persistence across gameplay-command, tick, runtime-ownership, prepared-upgrade, admission-pointer, and remote-followup seams with explicit `jOOQ` ownership;
- preserve the already-simplified service boundary shape from the `02.18.x` work while changing only the persistence substrate;
- keep Flyway as the schema authority and align tests with explicit SQL expectations rather than ORM behavior.

##### source-02-19-3-task-list-game-session-jooq-migration-vertical-slice-1-28: Notes

- This is the highest-risk migration in the family and should follow the Automation foundation proof, not precede it.
- The service’s recent control-plane and tick decompositions are a prerequisite advantage here, because the persistence migration can follow the now-bounded collaborators instead of one monolith.
- The first Game Session `jOOQ` batch is now live on the control-plane side: admission-pointer storage, admission-pointer audit events, prepared version-upgrade records, feature flags, and game manifests are all on explicit `jOOQ` repositories, and the service baseline SQL has been canonicalized so shared `jOOQ` codegen can parse the squashed schema without service-local workarounds.
- The first hot-path runtime/tick batch is now live on explicit `jOOQ` repositories too: game instances, gameplay command rows, runtime region status, and tick batch/effect persistence no longer depend on Spring Data JPA.
- The remaining remote followup/result/coordinator cluster is now also on explicit `jOOQ` repositories, including the bounded control-plane filter/query surface and the target-region drain/claim path. At this slice boundary, Game Session no longer has any Spring Data JPA repository surface; the only remaining persistence cleanup is the later family-wide Hibernate/JPA runtime/platform removal in `02.19.10`.

##### source-02-19-3-task-list-game-session-jooq-migration-vertical-slice-1-28: Checklist

- [x] Map current JPA persistence seams to the existing bounded collaborators.
- [x] Migrate ledger/queue/runtime/control-plane persistence to `jOOQ`.
  Admission-pointer authority and audit, prepared version-upgrade rows, feature flags, game manifests, game instances, gameplay command rows, runtime region status, tick batch/effect persistence, and the remote followup/result/coordinator cluster now all use explicit `jOOQ` repositories.
- [x] Remove JPA/Hibernate runtime/config dependencies from `game-session-service`.
  Closed by the family-wide runtime/platform cleanup completed in `02.19.10`, not by additional Game Session repository migration work.
- [x] Re-prove full service checks and cross-service behavior on the new SQL path.
<!-- /migration-source -->

### source-02-19-4-task-list-game-design-jooq-migration-vertical-slice-1-20

#### Game Design jOOQ Migration Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-20)

##### Preserved Source Text: source-02-19-4-task-list-game-design-jooq-migration-vertical-slice-1-20

<!-- migration-source path="design/project-management/vertical-slices/02.19.4-task-list-game-design-jooq-migration-vertical-slice.md" lines="1-20" sha256="c99037b2855bca4c1628b457c3ea8c8a95290d5013a46706b57550b9d36d389d" heading-offset="3" -->
#### source-02-19-4-task-list-game-design-jooq-migration-vertical-slice-1-20: Game Design jOOQ Migration Vertical Slice

##### source-02-19-4-task-list-game-design-jooq-migration-vertical-slice-1-20: Goal and Status

Goal: migrate `game-design-service` onto `jOOQ + Flyway` so publication, release-attestation, asset-manifest, and launch-descriptor persistence uses the same explicit SQL model as the rest of the converged stack. Status: implemented.

##### source-02-19-4-task-list-game-design-jooq-migration-vertical-slice-1-20: Scope

- replace JPA repository/entity persistence in the Game Design publication and control-plane seams with `jOOQ`;
- keep the current Flyway baseline and current publication/runtime contracts intact;
- remove JPA/Hibernate runtime/config dependencies from the service.

##### source-02-19-4-task-list-game-design-jooq-migration-vertical-slice-1-20: Checklist

- [x] Inventory Game Design persistence seams and choose the service-local `jOOQ` ownership boundaries.
- [x] Replace JPA persistence with explicit SQL queries and mappings.
  Publication and control-plane persistence now run through explicit `jOOQ` repositories for versions, games, publish attempts and participant digests, published release bundles, recorded participant digests, version asset artifacts and purge workflows, launch descriptors, templates and assets, revisions, settings overrides, template remap sets/entries, and published plugin/version-status rows.
- [x] Remove JPA/Hibernate runtime/config dependencies.
  Closed by the repo-wide Hibernate/JPA runtime removal completed in `02.19.10`; there is no remaining Game Design-local platform tail here.
- [x] Re-prove publication/control-plane behavior on the new SQL path.
<!-- /migration-source -->

### source-02-19-5-task-list-entity-management-jooq-migration-vertical-slice-1-20

#### Entity Management jOOQ Migration Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-20)

##### Preserved Source Text: source-02-19-5-task-list-entity-management-jooq-migration-vertical-slice-1-20

<!-- migration-source path="design/project-management/vertical-slices/02.19.5-task-list-entity-management-jooq-migration-vertical-slice.md" lines="1-20" sha256="0214bfab07359cc4c0429dac11d8e405f15771292997572b0f95df3c8157b265" heading-offset="3" -->
#### source-02-19-5-task-list-entity-management-jooq-migration-vertical-slice-1-20: Entity Management jOOQ Migration Vertical Slice

##### source-02-19-5-task-list-entity-management-jooq-migration-vertical-slice-1-20: Goal and Status

Goal: migrate `entity-management-service` onto `jOOQ + Flyway` so inventories, item instances, transfer audit, actor state, and related control-plane reads follow the same explicit SQL persistence model as the rest of the repo. Status: implemented.

##### source-02-19-5-task-list-entity-management-jooq-migration-vertical-slice-1-20: Scope

- replace JPA repository/entity seams in item, actor-state, and transfer-audit persistence with `jOOQ`;
- preserve the current Flyway baseline and live gameplay contracts;
- remove JPA/Hibernate runtime/config dependencies from the service.

##### source-02-19-5-task-list-entity-management-jooq-migration-vertical-slice-1-20: Checklist

- [x] Inventory current JPA persistence seams and group them into explicit SQL ownership areas.
- [x] Replace those seams with `jOOQ` queries and mappers.
  Shared `jOOQ` build/codegen is now enabled for Entity Management, the squashed baseline was normalized to the current canonical schema so `jOOQ` can generate against it, and the full repository surface is now migrated: characters, items, NPCs, actor conditions/resources, equipment/body-layout definitions, character friends, visible-ref counters, transfer audit, item instances, item stacks, container instances, inventory/equipment/room-ground projections, crafting recipes, and entity mutation effect replay state.
- [x] Remove JPA/Hibernate runtime/config dependencies.
  Closed by the repo-wide runtime-platform cleanup completed in `02.19.10`; there is no remaining Entity Management-local JPA/Hibernate tail here.
- [x] Re-prove gameplay-facing entity flows and module checks on the new SQL path.
<!-- /migration-source -->

### source-02-19-6-task-list-logging-admin-jooq-migration-vertical-slice-1-22

#### Logging Admin jOOQ Migration Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-22)

##### Preserved Source Text: source-02-19-6-task-list-logging-admin-jooq-migration-vertical-slice-1-22

<!-- migration-source path="design/project-management/vertical-slices/02.19.6-task-list-logging-admin-jooq-migration-vertical-slice.md" lines="1-22" sha256="8d98d37a3d581f538bcc4a6dd5e459c494caec69be0b6b4fe1a220863269988b" heading-offset="3" -->
#### source-02-19-6-task-list-logging-admin-jooq-migration-vertical-slice-1-22: Logging Admin jOOQ Migration Vertical Slice

##### source-02-19-6-task-list-logging-admin-jooq-migration-vertical-slice-1-22: Goal and Status

Goal: migrate `logging-admin-service` onto `jOOQ + Flyway` so moderation actions, player reports, and log-event control-plane reads live on the same explicit SQL foundation as the rest of the SQL-backed services. Status: implemented.

##### source-02-19-6-task-list-logging-admin-jooq-migration-vertical-slice-1-22: Scope

- replace JPA repository/entity seams in moderation/report/log-event persistence with `jOOQ`;
- keep the current Flyway baseline and control-plane contracts intact;
- remove JPA/Hibernate runtime/config dependencies from the service.

##### source-02-19-6-task-list-logging-admin-jooq-migration-vertical-slice-1-22: Implementation Notes

`logging-admin-service` now applies the shared `jOOQ` conventions and no longer has any Spring Data JPA repository surface. `LogEventRepository`, `ModerationActionRepository`, and `PlayerReportRepository` are explicit `jOOQ` repositories using the shared common timestamp helpers, and the test H2 profile is normalized to lowercase identifier mode so generated metadata and integration schema behavior stay aligned. The remaining Hibernate/JPA cleanup for this service is the later family-wide runtime/platform removal tracked in `02.19.10`, not hidden service-local repository debt.

##### source-02-19-6-task-list-logging-admin-jooq-migration-vertical-slice-1-22: Checklist

- [x] Inventory current JPA persistence seams.
- [x] Replace them with `jOOQ` ownership and mappings.
- [x] Remove JPA/Hibernate runtime/config dependencies.
- [x] Re-prove moderation/report/log-event behavior and service checks.
<!-- /migration-source -->

### source-02-19-7-task-list-world-management-jooq-migration-vertical-slice-1-22

#### World Management jOOQ Migration Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-22)

##### Preserved Source Text: source-02-19-7-task-list-world-management-jooq-migration-vertical-slice-1-22

<!-- migration-source path="design/project-management/vertical-slices/02.19.7-task-list-world-management-jooq-migration-vertical-slice.md" lines="1-22" sha256="7dae89c55a083bc2048afe4be95683de03bf7bae856eda71c5ea2c1e4684f0d3" heading-offset="3" -->
#### source-02-19-7-task-list-world-management-jooq-migration-vertical-slice-1-22: World Management jOOQ Migration Vertical Slice

##### source-02-19-7-task-list-world-management-jooq-migration-vertical-slice-1-22: Goal and Status

Goal: migrate `world-management-service` onto `jOOQ + Flyway` so world design/runtime topology persistence follows the repo’s explicit SQL standard instead of remaining one of the last large JPA-backed model graphs. Status: implemented.

##### source-02-19-7-task-list-world-management-jooq-migration-vertical-slice-1-22: Scope

- replace JPA repository/entity seams across world, zone, region, room, instance, generation-rule, and design-epoch persistence with `jOOQ`;
- preserve the current Flyway baseline and world-management contracts;
- remove JPA/Hibernate runtime/config dependencies from the service.

##### source-02-19-7-task-list-world-management-jooq-migration-vertical-slice-1-22: Implementation Notes

`world-management-service` now applies the shared `jOOQ` conventions and no longer has any Spring Data JPA repository surface. Generation rules, authored topology rows, world-design epoch/ledger persistence, world-entity spawn bindings, runtime world/region/zone/room instance rows, room exits, and world events all live on explicit `jOOQ` repositories, with `JooqWorldManagementRepositorySupport` carrying the shared partial-entity and optimistic-write helpers needed by the runtime topology graph. The leftover H2 test dependency tail has been removed, so there is no longer a service-local JPA/Hibernate cleanup seam hiding under `02.19.7`; the broader repo-wide runtime/platform removal is already closed separately under `02.19.10`.

##### source-02-19-7-task-list-world-management-jooq-migration-vertical-slice-1-22: Checklist

- [x] Inventory the current JPA topology/runtime persistence seams.
- [x] Replace them with `jOOQ` queries and mappings.
- [x] Remove JPA/Hibernate runtime/config dependencies.
- [x] Re-prove world design/runtime mutation and read flows on the new SQL path.
<!-- /migration-source -->

### source-02-19-8-task-list-social-groups-jooq-migration-vertical-slice-1-24

#### Social Groups jOOQ Migration Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-24)

##### Preserved Source Text: source-02-19-8-task-list-social-groups-jooq-migration-vertical-slice-1-24

<!-- migration-source path="design/project-management/vertical-slices/02.19.8-task-list-social-groups-jooq-migration-vertical-slice.md" lines="1-24" sha256="1aae8d4174833a11472f74d7aa08c5aa9a3eca9782ee6add9eb6ac5a96619e7a" heading-offset="3" -->
#### source-02-19-8-task-list-social-groups-jooq-migration-vertical-slice-1-24: Social Groups jOOQ Migration Vertical Slice

##### source-02-19-8-task-list-social-groups-jooq-migration-vertical-slice-1-24: Goal and Status

Goal: migrate `social-groups-service` onto `jOOQ + Flyway` so friend, guild, and social-presence persistence no longer sits on a separate ORM stack after the rest of the SQL services have converged. Status: implemented.

##### source-02-19-8-task-list-social-groups-jooq-migration-vertical-slice-1-24: Scope

- replace JPA repository/entity seams in social-group persistence with `jOOQ`;
- preserve the current Flyway baseline and gameplay/REST/gRPC contracts;
- remove JPA/Hibernate runtime/config dependencies from the service.

##### source-02-19-8-task-list-social-groups-jooq-migration-vertical-slice-1-24: Implementation Notes

`social-groups-service` now applies the shared `jOOQ` conventions and no longer has any Spring Data JPA repository surface. Account-scoped friend links, guilds, guild members, guild storage items, guild alliances, chat messages, and mail messages all live on explicit `jOOQ` repositories, with `JooqSocialGroupsRepositorySupport` carrying the shared timestamp and stale-write helpers for the small service-local model. The H2-backed test profile is normalized to lowercase identifier mode so generated metadata and integration schema behavior stay aligned, and the remaining Hibernate/JPA cleanup for this service is the later family-wide runtime/platform removal tracked in `02.19.10`, not hidden repository debt.

This migration also closed a real schema drift seam that JPA had been masking: the older Flyway guild-member and guild-storage/alliance DDL no longer matched the live Java model assumptions around surrogate ids, tenant scoping, and `Long` identifier width. The canonical migrations now reflect the current service contract directly so fresh-schema `jOOQ` codegen and runtime proof agree on one shape.

##### source-02-19-8-task-list-social-groups-jooq-migration-vertical-slice-1-24: Checklist

- [x] Inventory current JPA social-group persistence seams.
- [x] Replace them with `jOOQ` queries and mappings.
- [x] Remove JPA/Hibernate runtime/config dependencies.
- [x] Re-prove social REST/gRPC/gameplay consumers on the new SQL path.
<!-- /migration-source -->

### source-02-19-9-task-list-account-service-jooq-migration-vertical-slice-1-21

#### Account Service jOOQ Migration Vertical Slice - Shared jOOQ, Flyway, and SQL persistence convergence (source lines 1-21)

##### Preserved Source Text: source-02-19-9-task-list-account-service-jooq-migration-vertical-slice-1-21

<!-- migration-source path="design/project-management/vertical-slices/02.19.9-task-list-account-service-jooq-migration-vertical-slice.md" lines="1-21" sha256="28a114a9abea8c3656149ab1f8df0de7e0b68306ea94cb39b2c5d8a4a577bf89" heading-offset="3" -->
#### source-02-19-9-task-list-account-service-jooq-migration-vertical-slice-1-21: Account Service jOOQ Migration Vertical Slice

##### source-02-19-9-task-list-account-service-jooq-migration-vertical-slice-1-21: Goal and Status

Goal: migrate `account-service` onto `jOOQ + Flyway` so even the more CRUD-shaped account/profile/membership service follows the same SQL persistence model as the rest of the converged repo. Status: implemented.

##### source-02-19-9-task-list-account-service-jooq-migration-vertical-slice-1-21: Scope

- replace JPA repository/entity seams in account, profile, token, membership, and billing-adjacent persistence with `jOOQ`;
- preserve the current Flyway baseline and live account contracts;
- remove JPA/Hibernate runtime/config dependencies from the service.

##### source-02-19-9-task-list-account-service-jooq-migration-vertical-slice-1-21: Checklist

- [x] Inventory current JPA account persistence seams.
- [x] Wire shared `jOOQ` build/codegen and test-schema conventions for Account.
- [x] Replace account/profile/membership and token persistence with `jOOQ` queries and mappings.
- [x] Replace billing and virtual-currency persistence with `jOOQ` queries and mappings.
- [x] Remove JPA/Hibernate runtime/config dependencies.
  Closed by the repo-wide runtime removal completed in `02.19.10`; there is no remaining Account-local JPA/Hibernate cleanup seam here.
- [x] Re-prove account service behavior and downstream client expectations on the new SQL path.
<!-- /migration-source -->

### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80

#### Temporal Control-Plane Workflow Convergence Vertical Slice - Temporal control-plane workflow convergence (source lines 1-80)

##### Preserved Source Text: source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80

<!-- migration-source path="design/project-management/vertical-slices/02.20-task-list-temporal-control-plane-workflow-convergence-vertical-slice.md" lines="1-80" sha256="404f5501bf61a2258d201ca3f73b0c7a3e8b462c85a3767b3b9d3109bc902926" heading-offset="3" -->
#### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: Temporal Control-Plane Workflow Convergence Vertical Slice

##### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: Goal and Status

Goal: introduce a real durable workflow engine for FireMUD’s long-running control-plane orchestration by adopting Temporal narrowly for workflow classes that need crash-proof execution, durable waits/timers, resumability, and operator-visible state, while explicitly keeping gameplay runtime, ticks, and Redis coordination out of scope. Status: implemented.

##### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: Why This Slice Exists

FireMUD’s current shared saga layer provides inline orchestration plus persisted step status, but it does not provide the same guarantees the architecture docs increasingly assume for some control-plane workflows:

- restart-safe continuation;
- durable waiting and delayed retries;
- operator-visible workflow state and history;
- resumable or signalable long-running process instances;
- workflow progress that matters independently of one process lifetime.

The architectural risk is not that FireMUD lacks a workflow abstraction. It is that the repo may gradually grow a custom durable workflow engine around `SagaRunner` if the most workflow-shaped control-plane areas continue to evolve on top of an inline helper.

This slice family makes the transition explicit and bounded: Temporal is for durable control-plane workflows, not for gameplay execution.

It is also intentionally **need-driven**. FireMUD should adopt Temporal immediately only where the repo already has definite workflow-shaped design/code pressure today, not as a blanket preemptive migration for every possible future workflow.

##### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: Architecture Decision

- Temporal is the target durable workflow substrate for FireMUD’s long-running control-plane workflows.
- The current `common-saga` layer remains acceptable for short, synchronous orchestration that does not need survive-restart execution, durable waiting, or operator-driven resume/retry/signal behavior.
- Gameplay ticks, Redis coordination, per-command execution, and hot runtime mutation paths must stay on the existing tick/idempotency/reconciliation model and must not move to Temporal.

##### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: First Adoption Scope

The first two adopting workflow classes are:

1. world creation / activation / termination;
2. publish / release / script patch readiness / rollout lifecycle.

These are the strongest fit because the design already treats them as multi-step, operator-visible, retryable control-plane workflows rather than hot gameplay logic.

These are also the only workflow classes in current scope that already have a strong enough existing design/code footprint to justify immediate transition work. Other possible candidates, such as billing or broader admin remediation, should wait until the repo has real implemented pressure that would actually consume the shared Temporal substrate.

##### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: Scope

- establish the **minimal shared Temporal workflow foundation required by the first adopters**;
- define how workflow identity, business-step idempotency, retries, signals, queries, and operator read models map onto existing FireMUD contracts;
- migrate the world creation / activation / termination workflow family first;
- migrate publish / release / script patch readiness workflows second;
- keep architecture and developer docs aligned so later slices can depend on the workflow substrate intentionally.

##### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: Out of Scope

- gameplay ticks, movement, combat, inventory mutation, or other hot runtime command execution;
- Redis-backed gameplay coordination paths;
- replacing every existing saga immediately, regardless of whether it needs durable workflow behavior;
- adopting Temporal as a generic job runner for small CRUD orchestration.
- speculative migration of workflow classes that do not yet have a concrete existing FireMUD design/code need for durable workflow behavior.

##### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: Planned Child Slices

1. [02.20.1 Temporal Workflow Foundation and Common Contracts](../vertical-slices/02.20.1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice.md)
2. [02.20.2 World Lifecycle Temporal Migration](../vertical-slices/02.20.2-task-list-world-lifecycle-temporal-migration-vertical-slice.md)
3. [02.20.3 Publish and Script Patch Temporal Migration](../vertical-slices/02.20.3-task-list-publish-and-script-patch-temporal-migration-vertical-slice.md)
4. [02.20.4 Saga and Temporal Boundary Cleanup](../vertical-slices/02.20.4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice.md)
5. [02.20.5 Temporal Operator Surface and Contract Truthfulness Cleanup](../vertical-slices/02.20.5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice.md)

##### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: Dependency Pattern for Other Slices

Later slices should not invent their own durable workflow substrate. They should:

- depend on `02.20.1` for the shared Temporal conventions and runtime substrate;
- adopt `02.20.2+` when their owning workflow class is explicitly moved to Temporal;
- continue using the current tick/idempotency or short synchronous saga patterns when they do not require durable workflow semantics or do not yet have a concrete need for Temporal.

##### source-02-20-task-list-temporal-control-plane-workflow-convergence-vertical-slice-1-80: Checklist

- [x] Verify that the current saga helper and the current architecture docs are mismatched for some long-running control-plane workflows.
- [x] Make the control-plane-only Temporal decision explicit.
- [x] Land the shared Temporal workflow foundation and common contracts.
- [x] Migrate world creation / activation / termination workflows.
- [x] Migrate publish / release / script patch readiness workflows.
- [x] Clean up shared docs and the remaining saga/Temporal boundary once the first adopters land.
- [x] Carry stable `workflowFamily` through the operator read surfaces and remove the final adopter/operator doc drift so the shared Temporal contract stays truthful.
<!-- /migration-source -->

### source-02-20-1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice-1-35

#### Temporal Workflow Foundation and Common Contracts Vertical Slice - Temporal control-plane workflow convergence (source lines 1-35)

##### Preserved Source Text: source-02-20-1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice-1-35

<!-- migration-source path="design/project-management/vertical-slices/02.20.1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice.md" lines="1-35" sha256="ab72d96729e15ba771cae440d639cb972478b05bacbfa3b8c64e02c2a0d98c61" heading-offset="3" -->
#### source-02-20-1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice-1-35: Temporal Workflow Foundation and Common Contracts Vertical Slice

##### source-02-20-1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice-1-35: Goal and Status

Goal: establish only the shared FireMUD Temporal foundation that the first real adopters need, so durable control-plane workflows can use one canonical runtime, identity model, retry/timer policy, operator-read surface, and service-integration pattern without turning the repo into a speculative workflow platform project. Status: implemented at the shared-foundation boundary.

##### source-02-20-1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice-1-35: Scope

- choose the Temporal deployment and service-runtime integration pattern for FireMUD;
- define canonical workflow identity, business-step idempotency, signal/query/update contracts, and operator-read integration;
- define how Temporal workflow state maps onto FireMUD’s current control-plane status/read surfaces;
- define the boundary between short synchronous `common-saga` usage and durable Temporal workflow usage;
- update high-level docs and tooling guidance for the new workflow substrate.

This slice intentionally stayed minimal:

- the shared runtime substrate now lives in `services/common-temporal`;
- services opt in through `net.firedevops.firemud.temporal-conventions`;
- worker hosting is driven through `TemporalWorkerRegistrar` plus `TemporalWorkerHost`;
- shared queue and workflow/business-step identity conventions are implemented in `TemporalTaskQueueResolver` and `FiremudWorkflowIds`;
- one world-management-module proof now exercises the shared host/registration pattern without pulling speculative workflow logic into the foundation slice.

##### source-02-20-1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice-1-35: Acceptance Criteria

- at least one service can host Temporal workflows with one documented shared integration pattern;
- workflow identity and business-step idempotency rules are documented canonically;
- later adoption slices can use one shared operator/read integration model instead of inventing per-workflow status semantics.

##### source-02-20-1-task-list-temporal-workflow-foundation-and-common-contracts-vertical-slice-1-35: Checklist

- [x] Choose and document the canonical FireMUD Temporal deployment/runtime integration pattern.
- [x] Define canonical workflow identity, business-step idempotency, and retry/timer conventions.
- [x] Define canonical signal/query/update and operator-read conventions.
- [x] Define the durable-workflow boundary versus short synchronous `common-saga` usage.
- [x] Update developer and architecture docs for the shared Temporal workflow model.
<!-- /migration-source -->

### source-02-20-4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice-1-21

#### Saga and Temporal Boundary Cleanup Vertical Slice - Temporal control-plane workflow convergence (source lines 1-21)

##### Preserved Source Text: source-02-20-4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice-1-21

<!-- migration-source path="design/project-management/vertical-slices/02.20.4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice.md" lines="1-21" sha256="3112423f96157ee81875feb535d28550f72b45169e9470a4d4030b5c61c61eb8" heading-offset="3" -->
#### source-02-20-4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice-1-21: Saga and Temporal Boundary Cleanup Vertical Slice

##### source-02-20-4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice-1-21: Goal and Status

Goal: clean up the shared workflow story after the first Temporal adopters land so FireMUD has one explicit distinction between short synchronous orchestration and durable control-plane workflow execution instead of two partially overlapping abstractions. Status: implemented.

##### source-02-20-4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice-1-21: Scope

- update the shared transaction/workflow docs to describe the final boundary clearly;
- remove or narrow any `common-saga` claims that imply durable workflow behavior where Temporal is now canonical;
- keep only the short synchronous orchestration uses that still genuinely fit `common-saga`.

This cleanup is about clarifying the boundary after the first concrete adopters land, not about forcing every existing short saga use onto Temporal.

##### source-02-20-4-task-list-saga-and-temporal-boundary-cleanup-vertical-slice-1-21: Checklist

- [x] Update the high-level transaction/workflow docs to reflect the final saga versus Temporal split.
- [x] Narrow `common-saga` docs and claims so they no longer imply durable workflow guarantees.
- [x] Remove any now-obsolete workflow assumptions from adopting service docs.
- [x] Leave later slices with one clear rule for when to use ticks, synchronous saga orchestration, or Temporal.
- [x] Remove the remaining stale Game Design publish references that still taught publish as a saga after the durable Temporal adopters landed.
<!-- /migration-source -->

### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63

#### Temporal Operator Surface and Contract Truthfulness Cleanup Vertical Slice - Temporal control-plane workflow convergence (source lines 1-63)

##### Preserved Source Text: source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63

<!-- migration-source path="design/project-management/vertical-slices/02.20.5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice.md" lines="1-63" sha256="3a70b9a46d41651369e45f7eaaf049cf7c909ad762d4ff9c1e2d8453fc3ad197" heading-offset="3" -->
#### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63: Temporal Operator Surface and Contract Truthfulness Cleanup Vertical Slice

##### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63: Goal and Status

Goal: close the remaining post-adopter Temporal audit tails by making the operator-facing read surfaces and the surrounding docs fully match the shared Temporal contract that FireMUD now teaches. Status: implemented.

##### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63: Why This Slice Exists

The `02.20` family already landed the hard architectural work:

- Temporal is narrowly adopted for durable control-plane workflows only;
- world lifecycle, script-patch readiness, and full publish workflows all run on the shared Temporal substrate;
- `common-saga` no longer acts as the durable workflow engine.

The remaining drift is smaller but still visible:

- several operator-facing docs still describe saga as the long-running workflow substrate even after the Temporal adopters landed;
- the shared Temporal contract says operator-facing read models should carry both `workflowId` and `workflowFamily`, but the current adopter surfaces expose only workflow id/run/status.

This slice is the follow-up that makes the operator surface and the repo’s teaching layer fully truthful after the original Temporal migration family closed.

##### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63: Scope

- scrub the remaining stale saga wording from operator-facing and adopter service docs;
- either add `workflowFamily` to the adopter read surfaces and DTO/proto contracts, or intentionally narrow the shared Temporal contract if FireMUD does not want to expose that field operator-side;
- keep the repo teaching one explicit durable-workflow story across high-level architecture docs and concrete service/operator docs.

##### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63: Out of Scope

- new Temporal adopters beyond the already-landed world lifecycle, publish, and script-patch readiness workflows;
- moving gameplay or tick runtime paths onto Temporal;
- rebuilding operator dashboards beyond the contract/data surfaces directly needed to make the current shared Temporal rule truthful.

##### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63: Checklist

- [x] Remove remaining saga-as-durable-workflow wording from operator-facing/adopter docs.
- [x] Decide whether `workflowFamily` is required on the operator read surfaces or should be removed from the shared contract.
- [x] Implement the chosen `workflowFamily` direction consistently across world lifecycle, script-patch status, and publish/read surfaces plus their DTO/proto contracts and docs.
- [x] Revalidate the shared Temporal architecture docs after the operator surface is aligned.

##### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63: Implementation Notes

Implemented:

- the repo kept the shared Temporal contract as written and propagated `workflowFamily` through the adopter read surfaces rather than weakening the contract;
- world lifecycle, script-patch readiness, and full publish operator read models now all expose `workflowFamily` alongside `workflowId`, `workflowRunId`, and `workflowStatus`;
- the corresponding world-management, automation-scripting, and game-design proto contracts and metadata resolver records now preserve the stable family constants already used internally by the Temporal adopters;
- the remaining operator-facing doc drift that still taught saga as the durable workflow engine was scrubbed from Logging & Admin, world lifecycle, and operator-journey docs so the repo now teaches one consistent split: short synchronous orchestration on `common-saga`, durable control-plane workflows on Temporal.

##### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63: Validation

- [x] `./gradlew :world-management-service:test --tests 'net.firedevops.firemud.worldmanagement.service.impl.TemporalWorldLifecycleWorkflowMetadataResolverTest' --tests 'net.firedevops.firemud.worldmanagement.service.impl.WorldManagementGrpcServiceTest'`
- [x] `./gradlew :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.TemporalScriptPatchReadinessWorkflowMetadataResolverTest' --tests 'net.firedevops.firemud.automationscripting.service.impl.AutomationScriptingControlPlaneGrpcServiceTest'`
- [x] `./gradlew :game-design-service:test --tests 'net.firedevops.firemud.gamedesign.service.impl.TemporalVersionPublishWorkflowMetadataResolverTest' --tests 'net.firedevops.firemud.gamedesign.service.impl.GameDesignGrpcServiceTest'`
- [x] `./gradlew linkCheck lintMarkdown`

##### source-02-20-5-task-list-temporal-operator-surface-and-contract-truthfulness-cleanup-vertical-slice-1-63: Validation Target

At minimum, this slice should close with:

- focused checks for the touched Temporal adopter services;
- any required proto/doc generation or compile validation for the touched contracts;
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->
