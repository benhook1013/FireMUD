# Audit Review Intake Tracker

Purpose: temporary compilation file for importing findings from multiple external review and audit batches, checking whether each item is still relevant on the current branch, and capturing the disposition before promoting surviving work into canonical planning docs.

Current review note: re-checked against current code on 2026-04-19. Surviving work from this intake has now been promoted into canonical slice docs where appropriate; this file remains the disposition log, not the only tracker.

## Intake Rules

- Add each incoming batch under its own dated/source section.
- For every item, validate against the current codebase and docs before treating it as active.
- Keep resolved, obsolete, and duplicate findings in this file with an explicit disposition so later batches do not re-open the same point by accident.
- Move only confirmed still-relevant work into canonical task lists or review trackers after consolidation.

## Disposition Labels

- `active`: still relevant and should remain on the working list.
- `partial`: partially addressed; follow-up still required.
- `resolved`: no longer relevant because the branch already fixed it.
- `duplicate`: same underlying issue already tracked elsewhere in this file or an existing planning/review doc.
- `obsolete`: no longer relevant because the architecture, slice plan, or implementation direction changed.
- `needs-human-call`: technically unresolved, but requires product or architecture direction before deciding whether it should stay active.

## Intake Batches

### Batch 1

Source: user-provided static review findings, received 2026-04-18
Status: imported and validated

### [1.1] Player bootstrap is still tenant-bound and membership-gated

- Source wording: "The first-party bootstrap flow is implemented as a tenant-bound gameplay login, not as a global platform-account bootstrap."
- Claimed area: `account-service/auth/bootstrap`
- Current validation: `active`
- Evidence checked: [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:197), [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:231), [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:536), [system-architecture-authentication.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-authentication.md:115), [runtime-and-data.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/microservices/account-service/runtime-and-data.md:75)
- Notes: Current code still routes `issuePlayerBootstrap(...)` through `authenticate(tenantId, ...)`, which hard-requires tenant gameplay membership before the bootstrap token is minted. The bootstrap token also persists `tenantId` and later re-validates through tenant-scoped session storage. Current architecture docs say `POST /auth/player-bootstrap` establishes account identity only and defers membership/public-admission checks to discovery and `POST /auth/connect-token`.
- Follow-up target: [02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md), plus [09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md)

### [1.2] Realm-routing authority is no longer duplicated config, but it is still only partially converged

- Source wording: "Realm catalog and admission-pointer truth are still local Spring config duplicated into multiple services, not a runtime/control-plane authority."
- Claimed area: `game-session/account-service/routing`
- Current validation: `partial`
- Evidence checked: [GameplayWorldCatalog.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/GameplayWorldCatalog.java:21), [DatabaseGameplayAdmissionPointerAuthorityService.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DatabaseGameplayAdmissionPointerAuthorityService.java:18), [GameplayAdmissionPointerBootstrapInitializer.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/data/GameplayAdmissionPointerBootstrapInitializer.java:13), [09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md:14)
- Notes: The original "duplicated local config across multiple services" claim is no longer fully true. `game-session-service` now has a persisted `GameplayAdmissionPointerAuthorityService`, `GameplayWorldCatalog` reads from that authority in production wiring, and `account-service` bootstrap discovery consumes Game Session gRPC reads instead of local catalog config. The still-relevant remainder is that authority is initially bootstrapped from config when the pointer store is empty, and broader future consumers still need to stay on the same persisted routing seam.
- Follow-up target: [09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md)

### [1.3] Global roles, tenant roles, and membership authority are still collapsed into the wrong primitives

- Source wording: "The implemented identity model still collapses global account role, tenant membership, and tenant authorization into the wrong primitives."
- Claimed area: `account-service/common-security/auth-model`
- Current validation: `active`
- Evidence checked: [Account.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/entity/Account.java:23), [AccountTenantMembership.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/entity/AccountTenantMembership.java:29), [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:218), [SessionContext.java](/home/ben/src/FireMUD-wsl-copy/services/common-security/src/main/java/net/firedevops/firemud/common/security/SessionContext.java:60), [system-architecture-jwt-and-token-contracts.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-jwt-and-token-contracts.md:72), [system-architecture-multi-tenancy.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-multi-tenancy.md:23)
- Notes: The branch now has explicit `account_tenant_membership`, but membership still carries only `gameplayAdmissionAllowed`, while the account row still owns one global `role`. JWT issuance still emits that single account role as `globalRoles`, and downstream tenant access checks still look for legacy `admin`/`moderator` names instead of the documented `tenantAdmin`/`designer`/`player` scoped-role split.
- Follow-up target: [02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md)

### [1.4] Internal gRPC auth still defaults missing caller context to platform-wide admin

- Source wording: "Internal service-to-service auth silently upgrades missing caller context to `platformAdmin`."
- Claimed area: `common-security/grpc/auth`
- Current validation: `active`
- Evidence checked: [GrpcClientAuth.java](/home/ben/src/FireMUD-wsl-copy/services/common-security/src/main/java/net/firedevops/firemud/common/security/GrpcClientAuth.java:24)
- Notes: If the current thread-local auth context is absent or empty, `GrpcClientAuth` still mints an internal token with `globalRoles = [\"platformAdmin\"]`. That remains inconsistent with the documented distinction between operator action, scoped tenant action, and background service automation, and it weakens downstream audit attribution.
- Follow-up target: [02.18.12-task-list-internal-service-identity-and-session-attestation-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.12-task-list-internal-service-identity-and-session-attestation-vertical-slice.md)

### [1.5] Account lifecycle operations remain tenant-scoped despite global-account architecture

- Source wording: "Account lifecycle endpoints are still tenant-scoped in a way that collapses global platform identity into tenant membership ownership."
- Claimed area: `account-service/account-lifecycle`
- Current validation: `active`
- Evidence checked: [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:860), [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:870), [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:886), [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:932), [user-journeys-players.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/user-journeys-players.md:243), [runtime-and-data.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/microservices/account-service/runtime-and-data.md:27)
- Notes: Export and delete still require tenant membership, and deleting the last tenant membership deletes the account row itself. Password reset and email verification tokens are also tenant-keyed. That does not match the architecture's "global platform account with per-tenant membership" model or the player-journey docs that describe account export/deletion as account-level behavior.
- Follow-up target: [02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md)

### Batch 2

Source: user-provided corruption-resistance review, received 2026-04-18
Status: imported and validated

### [2.1] Corruption-resistance target architecture is mostly ahead of the current implementation

- Source wording: "the target architecture for corruption resistance is good, but the current implementation is not there yet"
- Claimed area: `game-session/runtime-durability`
- Current validation: `active`
- Evidence checked: [TickServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java:190), [tick_stage.lua](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/resources/redis/tick_stage.lua:1), [tick_commit.lua](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/resources/redis/tick_commit.lua:1), [V8__gameplay_admission_pointer_authority.sql](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/resources/db/migration/V8__gameplay_admission_pointer_authority.sql:1), [02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md:1)
- Notes: This is accurate. The architecture/docs assume a stronger durability and replay model than the current implementation provides. The current branch still relies mainly on transient Redis coordination plus local service transactions rather than a full durable command/tick/effect convergence substrate.
- Follow-up target: [02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md)

### [2.2] Missing durable command-status substrate is already tracked explicitly

- Source wording: "I did not find implemented durable command-ingress/status rows or `GetCommandStatus`"
- Claimed area: `game-session/command-ledger`
- Current validation: `duplicate`
- Evidence checked: [02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md:1), migration listing under `services/game-session-service/src/main/resources/db/migration`
- Notes: The codebase still lacks the durable command ledger the review calls for, but that gap is already tracked directly in slice `02.18.7`, including the same missing table/status/recovery concepts.
- Follow-up target: [02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md)

### [2.3] Missing durable tick-batch and effect-ledger substrate is already tracked explicitly

- Source wording: "I did not find implemented `tick_batch`, `tick_effects`, effect reconciliation backlog, or `EffectId` guard tables"
- Claimed area: `game-session/effect-ledger/replay`
- Current validation: `duplicate`
- Evidence checked: [02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md:1), [02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md:1), [02.18.11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.11-task-list-migrate-live-gameplay-commands-onto-durable-execution-path-vertical-slice.md:1)
- Notes: The missing substrate is real, but it is already represented across the existing durability slices for command ingress, region fencing, effect idempotency, and command migration.
- Follow-up target: existing `02.18.7`, `02.18.9`, `02.18.10`, and `02.18.11` slices

### [2.4] Current Redis tick path has no durable per-command or per-effect convergence record

- Source wording: "The current tick engine is very lightweight ... There is no durable per-command/per-effect commit record here."
- Claimed area: `game-session/tick-runtime`
- Current validation: `active`
- Evidence checked: [TickServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/TickServiceImpl.java:227), [tick_stage.lua](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/resources/redis/tick_stage.lua:1), [tick_commit.lua](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/resources/redis/tick_commit.lua:1)
- Notes: This is the concrete current-state risk behind the broader planned slices. `tick_stage.lua` only moves queue items into a pending list, and `tick_commit.lua` just drains pending. There is still no durable authoritative record tying a logical command/effect to terminal convergence after crash or replay.
- Follow-up target: [02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md)

### [2.5] Session state storage in Redis is still plain set/delete without fencing or CAS

- Source wording: "Game Session session state is Redis set/delete without CAS/version/fencing"
- Claimed area: `game-session/session-state`
- Current validation: `active`
- Evidence checked: [SessionStateServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/SessionStateServiceImpl.java:29)
- Notes: Accurate. `SessionStateServiceImpl` writes and deletes session state as unguarded Redis value operations. That is a narrower but real overwrite/ambiguity risk under concurrent writers or Redis loss, and it is not obviously captured by a dedicated existing slice.
- Follow-up target: [02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md)

### [2.6] `GameInstance` still lacks optimistic locking

- Source wording: "`GameInstance` itself has no optimistic-lock field"
- Claimed area: `game-session/persistence`
- Current validation: `active`
- Evidence checked: [GameInstance.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/entity/GameInstance.java:10)
- Notes: Accurate. `GameInstance` currently has no `@Version` field, unlike some entity/world models elsewhere. Whether that should be solved with JPA optimistic locking versus a stronger durable fencing model is an implementation decision, but the weaker concurrency protection is real.
- Follow-up target: [02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md)

### [2.7] Multi-service gameplay mutations still lack a shared durable effect identity

- Source wording: "Cross-service spatial flows are not yet protected by a shared durable idempotency key"
- Claimed area: `cross-service/gameplay-effects`
- Current validation: `duplicate`
- Evidence checked: [InventoryServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/InventoryServiceImpl.java:127), [ContainerServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/ContainerServiceImpl.java:105), [02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md:1)
- Notes: The code-level observation is correct, but it is the same underlying gap already called out by the existing `EffectId` and replay-guard slice.
- Follow-up target: [02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.10-task-list-effect-idempotency-and-replay-guards-vertical-slice.md)

### [2.8] World termination still performs remote cleanup inside the transaction boundary

- Source wording: "World termination currently makes a remote cleanup call while inside a DB transaction"
- Claimed area: `world-management/world-termination`
- Current validation: `active`
- Evidence checked: [WorldInstanceActivationServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldInstanceActivationServiceImpl.java:277)
- Notes: Accurate. `terminateWorldInstance(...)` performs `entityManagementClient.cleanupRuntimeInstance(...)` before the enclosing transaction completes. The current request is partially fenced by `terminationRequestId` and `lifecycleEpoch`, but the transactional remote call still leaves ambiguity pressure under timeout/retry paths.
- Follow-up target: [02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md)

### Batch 3

Source: user-provided gateway/feature-flag/moderation review, received 2026-04-18
Status: imported and validated

### [3.1] Gateway still exposes internal-only service families and under-protects the edge route plane

- Source wording: "Gateway still carries a parallel legacy ingress plane and an over-broad edge allowlist."
- Claimed area: `spring-cloud-gateway/edge-allowlist`
- Current validation: `partial`
- Evidence checked: [routes-prod.yml](/home/ben/src/FireMUD-wsl-copy/services/spring-cloud-gateway/src/main/resources/routes-prod.yml:7), [JwtAuthFilter.java](/home/ben/src/FireMUD-wsl-copy/services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/JwtAuthFilter.java:59), [system-architecture-overview.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-overview.md:24), [client-behavior.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/microservices/spring-cloud-gateway/client-behavior.md:47)
- Notes: The broad exposure concern is valid. `routes-prod.yml` still exposes internal-only families such as `/api/entity/**`, `/api/automation/**`, `/api/logic/**`, and `/api/world/**`, which conflicts with the current architecture allowlist. `JwtAuthFilter` also only protects `/routes` and `/api/admin`, so the gateway remains a permissive service fan-out rather than a tightly curated edge. The narrower claim that `/api/session/**` is necessarily a stale legacy route is not fully supported by current repo docs, because the gateway client-behavior doc still lists `/api/session/**` in the canonical external allowlist.
- Follow-up target: [02.15.7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.15.7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice.md)

### [3.2] Runtime feature flags still have split persistence authority between Logging & Admin and Game Session

- Source wording: "Runtime feature flags currently have two independent persistence authorities."
- Claimed area: `logging-admin/game-session/feature-flags`
- Current validation: `active`
- Evidence checked: [logging-admin FeatureFlagServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/FeatureFlagServiceImpl.java:16), [game-session FeatureFlagServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/FeatureFlagServiceImpl.java:19), [service-responsibility-matrix.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/service-responsibility-matrix.md:89), [logging-admin README.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/microservices/logging-admin-service/README.md:22)
- Notes: Accurate. Logging & Admin currently persists flag toggles in its own database, while Game Session also persists its own feature flags independently. Current architecture says external operator writes for runtime feature flags must enter through Logging & Admin, with Logging & Admin acting as UI/audit ingress to owning domain control-plane APIs, not as a second runtime-truth store.
- Follow-up target: [02.18.13-task-list-runtime-feature-flag-authority-convergence-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.13-task-list-runtime-feature-flag-authority-convergence-vertical-slice.md)

### [3.3] Moderation mutations still use a destructive substrate that collapses distinct enforcement concerns

- Source wording: "Moderation actions are implemented as 'record an action, then delete the account and stop a session'"
- Claimed area: `logging-admin/moderation`
- Current validation: `active`
- Evidence checked: [ModerationServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/ModerationServiceImpl.java:47), [service-responsibility-matrix.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/service-responsibility-matrix.md:121), [system-architecture-overview.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-overview.md:157), [02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.1-task-list-audit-log-and-moderation-separation-vertical-slice.md:1)
- Notes: Accurate. The current moderation implementation persists a moderation record and then directly deletes the account and stops the session. Existing slice `02.18.1` only fixed the misuse of moderation for ordinary audit logging; it did not redesign the moderation substrate itself. This remains a live architecture mismatch with the documented split between account-security bans, gameplay/chat moderation policy definitions, and enforcement owners.
- Follow-up target: [02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md)

### [3.4] Moderation policy enforcement is still missing on gameplay admission and chat send paths

- Source wording: "The moderation control plane is ahead of the actual enforcement model."
- Claimed area: `game-session/social-groups/moderation-enforcement`
- Current validation: `active`
- Evidence checked: [PlayCommandHandler.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/PlayCommandHandler.java:439), [ChatServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/social-groups-service/src/main/java/net/firedevops/firemud/socialgroups/service/impl/ChatServiceImpl.java:79), [system-architecture-overview.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-overview.md:22), [logging-admin runtime-and-data.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/microservices/logging-admin-service/runtime-and-data.md:67)
- Notes: Accurate. `PLAY` currently checks runtime membership and entitlements but not `gameplay_ban` policy, and chat send currently filters profanity and reports moderation events without enforcing `chat_mute` or `chat_ban`. The architecture already defines Logging & Admin as moderation-policy source of truth and Game Session / Social & Groups as enforcement owners via versioned policy snapshots. That enforcement substrate is not yet live.
- Follow-up target: [02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md)

### Batch 4

Source: user-provided contract-seam static review, received 2026-04-18
Status: imported and validated

### [4.1] Same-fence LOOK contract is still docs-only on the live World ↔ Entity ↔ Game Logic seam

- Source wording: "The canonical same-fence `LOOK` contract is not implemented anywhere on the live World ↔ Entity ↔ Game Logic seam."
- Claimed area: `look/world-entity-game-logic`
- Current validation: `active`
- Evidence checked: [world-management-service/api-contracts.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/microservices/world-management-service/api-contracts.md:106), [entity-management-service/api-contracts.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/microservices/entity-management-service/api-contracts.md:87), [world_management_service.proto](/home/ben/src/FireMUD-wsl-copy/protos/world-management/v1/world_management_service.proto:130), [entity_management_service.proto](/home/ben/src/FireMUD-wsl-copy/protos/entity-management/v1/entity_management_service.proto:278), [LookAggregationService.java](/home/ben/src/FireMUD-wsl-copy/services/game-logic-service/src/main/java/net/firedevops/firemud/gamelogic/service/LookAggregationService.java:41)
- Notes: Accurate. The architecture/docs require `asOfTickId` propagation plus `STALE_READ_FENCE` / `READ_FENCE_UNAVAILABLE` handling, but the current proto surfaces do not carry the fence and `LookAggregationService` fetches snapshot and entities independently. This leaves the live `LOOK` path vulnerable to mixed-tick composition while still returning success.
- Follow-up target: [03.1-task-list-same-fence-look-read-consistency-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/03.1-task-list-same-fence-look-read-consistency-vertical-slice.md), plus [03-task-list-data-driven-look-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/03-task-list-data-driven-look-vertical-slice.md)

### [4.2] Admission-pointer runtime contract still uses world-facing slug identity instead of canonical `{tenantId, realmSlug}`

- Source wording: "The implemented seam still keys the lookup and persistence model by `worldSlug + realmSlug`, with no `requestId`"
- Claimed area: `game-session/account-service/admission-pointer`
- Current validation: `active`
- Evidence checked: [system-architecture-multi-tenancy.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-multi-tenancy.md:115), [game_session_service.proto](/home/ben/src/FireMUD-wsl-copy/protos/game-session/v1/game_session_service.proto:185), [GameplayAdmissionPointer.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/entity/GameplayAdmissionPointer.java:9), [GameplayAdmissionPointerRepository.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/repository/GameplayAdmissionPointerRepository.java:10), [DatabaseGameplayAdmissionPointerAuthorityService.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DatabaseGameplayAdmissionPointerAuthorityService.java:44), [GameSessionGrpcService.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameSessionGrpcService.java:598), [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:1078)
- Notes: Accurate. The branch improved routing authority, but the canonical read shape in architecture is still not the live runtime contract. Persistence, lookup, and gRPC still center on `worldSlug + realmSlug`, and Account compensates by scanning visible worlds/realms to rediscover the pointer for a tenant. This is still live follow-through on the `09.1` slice rather than a resolved seam.
- Follow-up target: [09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md)

### [4.3] Gameplay-domain SessionAttestation contract is still not implemented on live RPC boundaries

- Source wording: "Gameplay-domain delegation is documented as requiring a Game Session-issued `SessionAttestation` ... The live implementation has no attestation surface in the gameplay RPC contracts"
- Claimed area: `gameplay-service-auth/delegation`
- Current validation: `active`
- Evidence checked: [game-session-service/api-contracts.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/microservices/game-session-service/api-contracts.md:5), [system-architecture-authentication.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-authentication.md:595), [game_logic_service.proto](/home/ben/src/FireMUD-wsl-copy/protos/game-logic/v1/game_logic_service.proto:27), [AuthTokenInterceptor.java](/home/ben/src/FireMUD-wsl-copy/services/common-security/src/main/java/net/firedevops/firemud/common/security/AuthTokenInterceptor.java:18), [WorldManagementGrpcService.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldManagementGrpcService.java:51), [EntityManagementGrpcService.java](/home/ben/src/FireMUD-wsl-copy/services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcService.java:75)
- Notes: Accurate. The current repo has baseline internal JWT propagation and service-boundary JWT enforcement, but not the documented gameplay-domain attestation contract. Protos do not carry an attestation field or metadata contract, and downstream gameplay services currently trust a generic bearer-token interceptor that only surfaces `accountId/globalRoles/scopedRoles`.
- Follow-up target: [02.18.12-task-list-internal-service-identity-and-session-attestation-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.12-task-list-internal-service-identity-and-session-attestation-vertical-slice.md)

### [4.4] Canonical tenant role-name drift is already tracked elsewhere in this intake

- Source wording: "The canonical tenant role names are `player`, `designer`, `tenantAdmin`, and `moderator`, but the shared auth enforcement code still authorizes tenant access using `admin`/`moderator`."
- Claimed area: `common-security/auth-role-model`
- Current validation: `duplicate`
- Evidence checked: [system-architecture-authentication.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-authentication.md:43), [SessionContext.java](/home/ben/src/FireMUD-wsl-copy/services/common-security/src/main/java/net/firedevops/firemud/common/security/SessionContext.java:60), [account JwtAuthInterceptor.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/security/JwtAuthInterceptor.java:61), [logging-admin JwtAuthInterceptor.java](/home/ben/src/FireMUD-wsl-copy/services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/security/JwtAuthInterceptor.java:54)
- Notes: Accurate, but already captured by item `1.3` in this intake tracker as part of the broader global-role / tenant-role / membership drift problem.
- Follow-up target: [audit-review-intake-2026-04-18-temp.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/audit-review-intake-2026-04-18-temp.md)

### [4.5] Gateway management proto/docs still drift from the shared gRPC response contract

- Source wording: "Gateway management proto drifted away from the shared gRPC response shape."
- Claimed area: `spring-cloud-gateway/grpc-contract`
- Current validation: `active`
- Evidence checked: [spring-cloud-gateway/api-contracts.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/microservices/spring-cloud-gateway/api-contracts.md:72), [gateway_management_service.proto](/home/ben/src/FireMUD-wsl-copy/protos/spring-cloud-gateway/v1/gateway_management_service.proto:3), [gateway_management_service.proto](/home/ben/src/FireMUD-wsl-copy/protos/spring-cloud-gateway/v1/gateway_management_service.proto:45)
- Notes: Accurate. The docs/examples refer to `spring_cloud_gateway.v1`, while the proto package is still `gateway.v1`, and `PingResponse` omits `ErrorDetail` even though the repo convention for application-level gRPC errors is in-band error payloads. This is a smaller seam than the auth/routing issues, but it is real contract drift.
- Follow-up target: [02.15.7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.15.7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice.md)

### [4.6] Non-public realm-access grant design is still only partially implemented

- Source wording: "The docs require Account-owned realm-access grants ... but the current Account implementation appears to gate on public-production membership plus Game Session realm visibility and has no corresponding grant read/write contract in code."
- Claimed area: `account-service/non-public-realm-grants`
- Current validation: `active`
- Evidence checked: [system-architecture-authentication.md](/home/ben/src/FireMUD-wsl-copy/design/architecture/system-architecture-authentication.md:224), [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:277), [AccountServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java:331)
- Notes: Accurate. The current bootstrap/connect-token path is realm-aware, but the explicit Account-owned non-public realm grant substrate described by the docs is still not visible in code. This overlaps the earlier bootstrap/admission gap work, but the grant-specific absence is still a live follow-up and should not be lost.
- Follow-up target: [02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.1.6-task-list-global-account-and-tenant-authorization-convergence-vertical-slice.md), plus [09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md)

### Batch 5

Source: user-provided lifecycle/auth/concurrency static review, received 2026-04-18
Status: imported and validated

### [5.1] Default internal gRPC auth escalation to `platformAdmin` is already confirmed elsewhere

- Source wording: "Internal gRPC auth currently mints broad admin authority by default."
- Claimed area: `common-security/internal-grpc-auth`
- Current validation: `duplicate`
- Evidence checked: [GrpcClientAuth.java](/home/ben/src/FireMUD-wsl-copy/services/common-security/src/main/java/net/firedevops/firemud/common/security/GrpcClientAuth.java:24), [CommonSecurityAutoConfiguration.java](/home/ben/src/FireMUD-wsl-copy/services/common-security/src/main/java/net/firedevops/firemud/common/config/CommonSecurityAutoConfiguration.java:16), [WorldManagementGrpcService.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldManagementGrpcService.java:394), [EntityManagementGrpcService.java](/home/ben/src/FireMUD-wsl-copy/services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcService.java:1027)
- Notes: Accurate, but already captured by item `1.4`. This batch adds useful detail that the auto-configured blocking stub customizer makes the broad fallback the default across internal clients, not just an isolated helper bug.
- Follow-up target: [audit-review-intake-2026-04-18-temp.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/audit-review-intake-2026-04-18-temp.md)

### [5.2] Manual epoch/version fields still lack atomic database enforcement

- Source wording: "The codebase is using manual fence/version fields without atomic enforcement"
- Claimed area: `world-management/game-session/concurrency`
- Current validation: `active`
- Evidence checked: [WorldInstance.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/entity/WorldInstance.java:16), [WorldInstanceActivationServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldInstanceActivationServiceImpl.java:200), [requireLifecycleEpoch](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldInstanceActivationServiceImpl.java:458), [GameplayAdmissionPointer.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/entity/GameplayAdmissionPointer.java:16), [DatabaseGameplayAdmissionPointerAuthorityService.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/DatabaseGameplayAdmissionPointerAuthorityService.java:64), [GameInstance.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/entity/GameInstance.java:8), [V1__init.sql](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/resources/db/migration/V1__init.sql:1)
- Notes: Accurate. Some of this was already visible in items `2.6` and `2.8`, but this finding usefully broadens the pattern: manual epoch/version comparisons are still read-check-save logic rather than database-enforced CAS/optimistic-lock boundaries, and `game_instances` still lacks stronger invariants for concurrency safety.
- Follow-up target: [02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md)

### [5.3] World lifecycle methods still hold transactions open across blocking gRPC calls

- Source wording: "World lifecycle methods hold database transactions open across blocking gRPC calls."
- Claimed area: `world-management/lifecycle-transactions`
- Current validation: `active`
- Evidence checked: [WorldInstanceActivationServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldInstanceActivationServiceImpl.java:137), [WorldInstanceActivationServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldInstanceActivationServiceImpl.java:359), [WorldInstanceActivationServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldInstanceActivationServiceImpl.java:198), [WorldInstanceActivationServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldInstanceActivationServiceImpl.java:277)
- Notes: Accurate. Item `2.8` already captured the terminate path’s remote cleanup inside an open transaction. This batch confirms the same long-lived transactional pattern also affects prepare/activate via attestation validation RPCs, so the risk is broader than one termination call site.
- Follow-up target: [02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md)

### [5.4] Game Session start/stop orchestration is still not crash-safe across partial publication steps

- Source wording: "Game Session start/stop orchestration is not crash-safe; it relies on in-process compensation after partially publishing state."
- Claimed area: `game-session/lifecycle-orchestration`
- Current validation: `active`
- Evidence checked: [GameInstanceServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameInstanceServiceImpl.java:167), [GameInstanceServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameInstanceServiceImpl.java:191), [GameInstanceServiceImpl.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameInstanceServiceImpl.java:221)
- Notes: Accurate. The current start/stop flow still depends on local in-process compensation after publishing partial durable/Redis state. A process crash between those steps can leave Game Session durable state, Redis state, and World lifecycle state out of sync.
- Follow-up target: [02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md)

### [5.5] LOOK snapshot/read-fence implementation drift is already confirmed elsewhere

- Source wording: "The LOOK snapshot contract is only partially implemented, and the missing fields are already in the proto."
- Claimed area: `look/read-contract`
- Current validation: `duplicate`
- Evidence checked: [world_management_service.proto](/home/ben/src/FireMUD-wsl-copy/protos/world-management/v1/world_management_service.proto:130), [entity_management_service.proto](/home/ben/src/FireMUD-wsl-copy/protos/entity-management/v1/entity_management_service.proto:278), [WorldManagementGrpcService.java](/home/ben/src/FireMUD-wsl-copy/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldManagementGrpcService.java:435), [EntityManagementGrpcService.java](/home/ben/src/FireMUD-wsl-copy/services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/EntityManagementGrpcService.java:1027), [LookAggregationService.java](/home/ben/src/FireMUD-wsl-copy/services/game-logic-service/src/main/java/net/firedevops/firemud/gamelogic/service/LookAggregationService.java:71), [EntityManagementClient.java](/home/ben/src/FireMUD-wsl-copy/services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/client/EntityManagementClient.java:504)
- Notes: Accurate, but already captured by item `4.1`. This batch adds a sharper implementation detail: the proto already advertises snapshot metadata fields that the current service implementations still do not populate, which makes the seam especially misleading.
- Follow-up target: [audit-review-intake-2026-04-18-temp.md](/home/ben/src/FireMUD-wsl-copy/design/project-management/audit-review-intake-2026-04-18-temp.md)

### Batch 6

Source: pending
Status: pending import

## Item Template

Use this structure for imported findings:

```md
### [batch.item] Short finding title

- Source wording: "<concise paraphrase or short excerpt>"
- Claimed area: `service/doc/domain`
- Current validation: `active|partial|resolved|duplicate|obsolete|needs-human-call`
- Evidence checked: [path/to/file](../../path/to/file) and/or command summary
- Notes: why it still matters, why it no longer applies, or what changed
- Follow-up target: existing tracker/doc, new task, or `none`
```
