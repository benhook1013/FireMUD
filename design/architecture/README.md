# Architecture Overview

The architecture section describes the platform infrastructure and each microservice.

Unless a document explicitly says otherwise, docs in `design/architecture/` describe the canonical target-state behavior and are normative for implementation. Read overview tables, responsibility matrices, glossary terms, and explicitly labeled canonical sections as contracts rather than as informal background.

## High-Level Diagrams

- [**system-architecture-overview.md**](./system-architecture-overview.md) – High-level diagrams and interactions.
- [**system-architecture-diagram.md**](./system-architecture-diagram.md) – Component relationships.
- [**system-context-diagram.md**](./system-context-diagram.md) – Shows clients, DMZ components, services, and datastores.
- [**service-responsibility-matrix.md**](./service-responsibility-matrix.md) – Summary of which service handles what.

## Canonical Terms

- `session front-end` – The connected Game Session pod that owns socket I/O, connection-local state, and per-session sequencing.
- `lease owner` – The Game Session execution owner currently holding the `<tenantId, regionId>` lease required to mutate region-scoped coordination state.
- `canonical room state` – A room view assembled only from same-fence World Management occupancy data and Entity Management containment/presentation data.
- `control-plane API` – An infrastructure or domain admin API classification that is not part of player gameplay traffic; when describing ingress surfaces, prefer the named traffic planes below.
- `bypass-safe workflow` – An explicitly documented external admin workflow allowed to bypass Logging & Admin ingress because it does not rely on Logging & Admin-owned policy, cross-domain write orchestration, or control-plane availability guarantees.
- `infrastructure management plane` – Internal Gateway management and health-control traffic used for infrastructure operations such as route configuration and liveness checks; this is not an external product API surface.
- `external admin/creator API plane` – The HTTP(S) API surface exposed through Gateway for operator and creator tools on explicitly allowlisted domain routes.
- `player traffic plane` – Player-facing HTTP, WebSocket, and Telnet traffic used for gameplay admission and live play.

## When To Update Architecture

Open or amend the architecture docs before implementation when a change would alter a canonical contract, including:

- adding a new edge-routable service or route group
- allowing a new external mutation path that bypasses Logging & Admin
- introducing a new Coordination Redis owner prefix or changing an owner boundary
- adding explicit shard handoff or lease-aware edge admission semantics
- extending gameplay execution beyond the single-cluster scope

## Directories

- [**infrastructure/**](./infrastructure/) – Deployment environments and secrets management.
- [**microservices/**](./microservices/) – Individual service responsibilities and APIs.
- [**repository-structure.md**](./repository-structure.md) – Layout of Gradle modules and repository files.

## Runtime Architecture

- [**game-customization-options.md**](./game-customization-options.md) – Ways hosted games can change appearance and behavior.
- [**performance-optimization.md**](./performance-optimization.md) – Database and network tuning tips.
- [**system-architecture-authentication.md**](./system-architecture-authentication.md) – Authentication mechanisms and session handling.
- [**system-architecture-database-migrations.md**](./system-architecture-database-migrations.md) – Managing schema changes per service.
- [**system-architecture-frontend.md**](./system-architecture-frontend.md) – React UI structure and state management.
- [**system-architecture-gateway.md**](./system-architecture-gateway.md) – Spring Cloud Gateway routing and WebSocket support.
- [**system-architecture-grpc.md**](./system-architecture-grpc.md) – Conventions for proto layout and versioning.
- [**system-architecture-logging-monitoring.md**](./system-architecture-logging-monitoring.md) – Logging and observability stack.
- [**system-architecture-input-output-and-presentation.md**](./system-architecture-input-output-and-presentation.md) – Canonical structured input, structured output, late rendering, prompt, color, and `BRIEF` model for player-facing traffic.
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
- [**system-architecture-ticks.md**](./system-architecture-ticks.md) – Tick system and runtime design.
- [**system-architecture-tracing.md**](./system-architecture-tracing.md) – Deploying the OpenTelemetry Collector and Jaeger.
- [**system-architecture-transactions.md**](./system-architecture-transactions.md) – Transaction strategies and sagas for cross-service workflows.
- [**system-architecture-versioning-runtime.md**](./system-architecture-versioning-runtime.md) – Publishing versions and runtime flags.

## Operations

- [**system-architecture-backup-recovery.md**](./system-architecture-backup-recovery.md) – Backup strategy and disaster recovery procedures.
- [**system-architecture-cicd.md**](./system-architecture-cicd.md) – CI/CD pipeline design using GitHub Actions.
- [**system-architecture-runbooks.md**](./system-architecture-runbooks.md) – Operational runbooks for deployment, scaling, and recovery.

## Additional Resources

- [**../project-management/slice-support/playtesting-feedback.md**](../project-management/slice-support/playtesting-feedback.md) – Staging playtests and feedback collection.
- [**user-journeys.md**](./user-journeys.md) – Example creator and player workflows.

Refer to the README files within each subdirectory for more details.
