# Architecture Overview

The architecture section describes FireMUD's technical contracts, runtime boundaries, infrastructure, and microservices. Product requirements and observable product behavior live in [`design/product`](../product/README.md); architecture documents define the technical contracts that implement those product outcomes.

Unless a document explicitly says otherwise, docs in `design/architecture/` describe canonical target-state technical contracts and are normative for implementation. Product requirements and observable product behavior are defined in `design/product`; read architecture overview tables, responsibility matrices, glossary terms, and explicitly labeled canonical sections as technical contracts rather than as informal background.

## High-Level Diagrams

- [**system-architecture-overview.md**](./system-architecture-overview.md) – High-level diagrams and interactions.
- [**system-architecture-diagram.md**](./system-architecture-diagram.md) – Component relationships.
- [**system-context-diagram.md**](./system-context-diagram.md) – Shows clients, DMZ components, services, and datastores.
- [**service-responsibility-matrix.md**](./service-responsibility-matrix.md) – Summary of which service handles what.

## Canonical Terms

- `session front-end` – The connected Game Session pod that owns socket I/O, connection-local state, and per-session sequencing.
- `lease owner` – The Game Session execution owner currently holding the `<tenantId, gameInstanceId, regionId>` lease required to mutate region-scoped coordination state.
- `canonical room state` – A room view assembled only from same-fence World Management occupancy data and Entity Management containment/presentation data.
- `control-plane API` – An infrastructure or domain admin API classification that is not part of player gameplay traffic; when describing ingress surfaces, prefer the named traffic planes below.
- `bypass-safe workflow` – An explicitly documented external admin workflow allowed to bypass Logging & Admin ingress because it does not rely on Logging & Admin-owned policy, cross-domain write orchestration, or control-plane availability guarantees.
- `infrastructure management plane` – Internal Gateway management and health-control traffic used for infrastructure operations such as route configuration and liveness checks; this is not an external product API surface.
- `external admin/creator API plane` – The HTTP(S) API surface exposed through Gateway for operator and creator tools on explicitly allowlisted domain routes.
- `player traffic plane` – Player-facing HTTP, WebSocket, and Telnet traffic used for gameplay admission and live play.

## Contract Authority Map

This table names the canonical owner for cross-cutting contract families represented by merged ADRs or repeated across several architecture documents. Secondary documents link to the owner and state only their local API, persistence, transport, or operational consequence. ADRs explain why a consequential choice was made; they do not replace the current contract in the owning architecture document. A dash in the ADR column means the ownership boundary consolidates existing design without introducing a consequential decision record.

| Contract family | Canonical owner | Secondary documents retain | Merged ADRs |
| --- | --- | --- | --- |
| Script trigger identity, ingress ownership, audit outcomes, and timer semantics | [Scripting normative contract tables](./system-architecture-scripting-normative-contract-tables.md#document-precedence-normative) | Service-local request fields, persistence, and instrumentation consequences | 0001-0003 |
| Scripting metric names and labels | [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md#metrics-authoritative-names-and-label-rules) | Service-local metric emission and dashboard consequences | — |
| Scripting queue ownership, command handoff, version fencing, dry-run safety, and reload admission | [Scripting cross-service contracts](./system-architecture-scripting-contracts.md) | Service-local handler, storage, and retry consequences | 0001-0003 |
| Script-patch promotion, rollback, convergence, and degraded operation | [Scripting rollout and rollback](./system-architecture-scripting-rollout-and-rollback.md) | API-specific participation and operator procedure | — |
| Gameplay edge routing, sharding, route lifecycle, and close translation | [Gateway architecture](./system-architecture-gateway.md) | Protocol-specific carriers and client-visible translation | 0004, 0006-0008, 0018 |
| Session front-end and lease-owner routing | [Game Session API contracts](./microservices/game-session-service/api-contracts.md#session-front-end-and-lease-owner-routing) | Caller-specific routing and retry behavior | 0011 |
| Settings ownership, precedence, and effective caps | [Settings model](./system-architecture-settings-model.md#ownership-and-precedence) | Local settings consumed and fail-closed behavior | 0012 |
| Session continuity, active-binding inventory and indexes, active-token refresh, and logout | [Session behavior](./system-architecture-session-behavior.md) | Redis key shape, transport reconnect procedure, and service-local cleanup | 0013, 0030, 0031 |
| JWT issuance, registry identity, profiles, token and authority-generation rules, outage validity, and key rotation | [JWT and token contracts](./system-architecture-jwt-and-token-contracts.md) | Route carriage and service-local validation consequences | 0014, 0035-0038 |
| Backup/recovery lifecycle and restore readiness | [Backup & Disaster Recovery](./system-architecture-backup-recovery.md) | Service-specific backup hooks and operator commands | 0015 |
| Recovery evidence schemas, lineage, participant dispositions, hardening results, and compliance fields | [Backup Recovery Evidence and Compliance](./system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record) | Service-specific recovery-evidence hooks and operator commands | 0015 |
| Durable command outcome lifecycle and status | [Tick execution flows](./system-architecture-tick-execution-flows.md#command-outcome-status-surface-required) | Optional projections and caller presentation | 0016 |
| Distributed trace propagation and correlation | [Tracing](./system-architecture-tracing.md) | Service-local instrumentation details | 0017 |
| Identifier representation and boundary validation | [Identifier glossary](./system-architecture-identifier-glossary.md) | Domain-specific identity meaning and validation | 0005, 0020 |
| Authentication admission, `JOIN`, and gameplay identity binding | [Authentication](./system-architecture-authentication.md) | Admission and identity-binding semantics consume Account-owned durable membership state, mutations, and entitlement authority; Game Session translates the flow | 0021, 0022, 0024, 0025, 0026, 0040, 0042, 0045 |
| Route-class authorization and operator receiving boundaries | [Authorization route matrix](./system-architecture-authz-route-matrix.md) | Receiving-service semantic authorization and audit evidence | 0023, 0047, 0048 |
| Gameplay workload trust and secret delivery | [Security](./system-architecture-security.md) | Workload-local credential consumption | 0024, 0032 |
| Player execution context schema and gameplay identity carriage | [Authentication](./system-architecture-authentication.md#gameplay-player-execution-context-contract-normative) | Receiving-service scope validation and domain authorization | 0024 |
| Tenant identity and isolation | [Multi-tenancy](./system-architecture-multi-tenancy.md) | Service-owned tenant data and routing consequences | 0027 |
| Realm admission-pointer identity and authority | [Game Session API contracts](./microservices/game-session-service/api-contracts.md) | Admission-pointer API, persistence, and routing consequences | 0041 |
| Published version state and replacement-instance version cutover lifecycle | [Versioning and runtime](./system-architecture-versioning-runtime.md) | Consumer-specific admission and refresh behavior | 0041 |
| Durable tenant membership and authority state, membership mutations, and runtime entitlement authority | [Account runtime and data](./microservices/account-service/runtime-and-data.md#membership-and-entitlement-authority) | Authentication and other consumers enforce Account-owned state during admission and `JOIN` | 0028 |
| Tenant-aware edge connect token | [Gateway architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake) | Token issuance and gameplay-context validation | 0029 |
| Plaintext Telnet policy and command-channel controls | [Security](./system-architecture-security.md#plaintext-telnet-policy) | Gateway and TCP Proxy transport enforcement | 0033 |
| Brute-force and abuse controls | [Security](./system-architecture-security.md#brute-force-defense-and-abuse-handling) | Route- and service-specific limits | 0034 |
| Redis operator access and recovery evidence | [Redis operations access](./system-architecture-redis-ops-access.md) | Service-owned prefixes and recovery hooks | 0039 |
| Coordination Redis reset sequence and phase lifecycle | [Redis operations](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) | Reset-model context, scenario scope, storage handling, and current fallback | — |
| Account lifecycle state | [Account runtime and data](./microservices/account-service/runtime-and-data.md#account-lifecycle-state-model) | Consumer treatment of suspended or deleted accounts | 0043 |
| Billing instrument ownership and provider integration | [Account Stripe integration](./microservices/account-service/stripe-integration.md) | Provider adapters and reconciliation operations | 0044 |
| Subscription lifecycle, plan changes, and cancellation timing | [Account subscription management](./microservices/account-service/subscription-management.md) | Provider event handling and runtime entitlement projection | — |
| Friend presence privacy | [Social and Groups API contracts](./microservices/social-groups-service/api-contracts.md#friend-presence-privacy-contract) | Request pagination and subject-local redaction | 0046 |
| External identity attachment | [Account runtime and data](./microservices/account-service/runtime-and-data.md) | Provider adapter details; provider lifecycle remains an explicit design gap | 0049 |
| Account export and erasure request binding | [Account API contracts](./microservices/account-service/api-contracts.md#subject-binding-rules-normative) | Artifact delivery and storage-specific erasure mechanics; a platform retention-registry owner remains an explicit gap | 0050 |

## When To Update Architecture

Open or amend the architecture docs before implementation when a change would alter a canonical contract, including:

- adding a new edge-routable service or route group
- allowing a new external mutation path that bypasses Logging & Admin
- introducing a new Coordination Redis owner prefix or changing an owner boundary
- adding explicit shard handoff or lease-aware edge admission semantics
- extending gameplay execution beyond the single-cluster scope

## Directories

- [**decisions/**](./decisions/README.md) – Consequential architecture decision records and their current status.
- [**generated/**](./generated/README.md) – Checked-in references generated from canonical settings metadata.
- [**infrastructure/**](./infrastructure/) – Deployment environments and secrets management.
- [**microservices/**](./microservices/) – Individual service responsibilities and APIs.
- [**repository-structure.md**](./repository-structure.md) – Layout of Gradle modules and repository files.

## Runtime Architecture

- [**system-architecture-game-customization.md**](./system-architecture-game-customization.md) – Ways hosted games can change appearance and behavior.
- [**system-architecture-authentication.md**](./system-architecture-authentication.md) – Authentication mechanisms and session handling.
- [**system-architecture-database-migrations.md**](./system-architecture-database-migrations.md) – Managing schema changes per service.
- [**system-architecture-frontend.md**](./system-architecture-frontend.md) – React UI structure and state management.
- [**system-architecture-gateway.md**](./system-architecture-gateway.md) – Spring Cloud Gateway routing and WebSocket support.
- [**system-architecture-grpc.md**](./system-architecture-grpc.md) – Conventions for proto layout and versioning.
- [**system-architecture-logging-monitoring.md**](./system-architecture-logging-monitoring.md) – Logging and observability stack.
- [**system-architecture-input-output-and-presentation.md**](./system-architecture-input-output-and-presentation.md) – Canonical structured input, structured output, late rendering, prompt, color, and `BRIEF` model for player-facing traffic.
- [**system-architecture-player-command-model.md**](./system-architecture-player-command-model.md) – Canonical standard player-command catalog, stages, capability policy, and game-authored command extension rules.
- [**system-architecture-settings-model.md**](./system-architecture-settings-model.md) – Canonical layered settings ownership, scopes, domains, and effective-config precedence.
- [**system-architecture-llm-content-tools.md**](./system-architecture-llm-content-tools.md) – Design-time LLM-assisted content authoring workflows.
- [**system-architecture-mud-client-protocol.md**](./system-architecture-mud-client-protocol.md) – Mud Client Protocol integration for external editors and scripted clients.
- [**system-architecture-multi-tenancy.md**](./system-architecture-multi-tenancy.md) – Hosting multiple games on shared infrastructure.
- [**system-architecture-procedural-generation.md**](./system-architecture-procedural-generation.md) – Basic dungeon generation used during world creation.
- [**system-architecture-protocol-bridging.md**](./system-architecture-protocol-bridging.md) – Bridging Telnet and WebSocket clients.
- [**system-architecture-reconnection.md**](./system-architecture-reconnection.md) – Client reconnect flow across services.
- [**system-architecture-redis.md**](./system-architecture-redis.md) – Redis deployment topology and usage patterns.
- [**system-architecture-scripting.md**](./system-architecture-scripting.md) – Automation and scripting framework.
- [**system-architecture-security.md**](./system-architecture-security.md) – Cross-service security and secret management.
- [**system-architecture-shared-libraries.md**](./system-architecture-shared-libraries.md) – Common libraries for microservices.
- [**system-architecture-testing.md**](./system-architecture-testing.md) – Unit, integration, and load testing strategy.
- [**system-architecture-threat-model.md**](./system-architecture-threat-model.md) – Deferred placeholder for a future whole-platform threat model; no threat model is accepted yet.
- [**system-architecture-ticks.md**](./system-architecture-ticks.md) – Tick system and runtime design.
- [**system-architecture-tracing.md**](./system-architecture-tracing.md) – Deploying the OpenTelemetry Collector and Jaeger.
- [**system-architecture-transactions.md**](./system-architecture-transactions.md) – Transaction strategies, synchronous sagas, and Temporal boundaries for cross-service workflows.
- [**system-architecture-versioning-runtime.md**](./system-architecture-versioning-runtime.md) – Publishing versions and runtime flags.

## Operations

- [**system-architecture-backup-recovery.md**](./system-architecture-backup-recovery.md) – Backup strategy and disaster recovery procedures.
- [**system-architecture-cicd.md**](./system-architecture-cicd.md) – CI/CD pipeline design using GitHub Actions.
- [**Operations documentation**](../operations/README.md) – Operator-facing deployment, recovery, incident, credential, and compliance procedures.

## Additional Resources

- [**Playtesting and feedback**](../project-management/slice-support/playtesting-feedback.md) – Staging playtests and feedback collection.
- [**../developer-workflows/player-playtest-checklist.md**](../developer-workflows/player-playtest-checklist.md) – High-level manual player checklist covering the currently implemented gameplay feature surface.
- [**Product user journeys**](../product/user-journeys/overview.md) – Product-facing creator, player, and operator workflows.

Refer to the README files within each subdirectory for more details.
