# FireMUD Product Capability Taxonomy

## Purpose

This taxonomy provides stable product-capability identifiers for canonical design navigation, architecture decision indexing, implementation tracking, and code/proof reconciliation. It organizes responsibility without defining runtime behavior and without mirroring the microservice deployment topology.

## Implementation Status

This is the current allocation taxonomy. The complete canonical design corpus is being allocated against these stable identifiers.

## Taxonomy Contract

- Each capability has one primary home even when its implementation spans services.
- Service ownership, data ownership, and cross-service handoffs remain defined by canonical architecture.
- Cross-cutting documents allocate normative sections separately when one file covers multiple capabilities.
- Capability identifiers remain stable when documents, services, or implementation trackers are reorganized.
- The hierarchy is product- and authority-oriented rather than a list of current modules.

## Capability Groups

### Accounts and Access (`AA`)

#### `AA-1` Accounts, Tenancy, Entitlements, and Commerce

Tenant and account lifecycle, authentication credentials, authorization entitlements, subscriptions, purchases, account recovery, account data rights, and commercial access policy.

- `AA-1.1` Account and profile identity
- `AA-1.2` Tenant membership, roles, and delegated access
- `AA-1.3` Authentication, recovery, security policy, and account data rights
- `AA-1.4` Commerce, subscriptions, purchases, donations, and platform fees
- `AA-1.5` Entitlements, quotas, and hosting eligibility

#### `AA-2` Gameplay Admission and Session Continuity

Player login and gameplay admission, character selection, session identity, connection continuity eligibility, reconnect and resume control, logout, takeover, and session revocation.

- `AA-2.1` Gameplay login, character selection, and session binding
- `AA-2.2` Reconnect, resume eligibility, and cross-device continuity
- `AA-2.3` Takeover, logout, idle expiry, and revocation

#### `AA-3` Realm Admission, Routing, and Playable State

Game and realm discovery, runtime target identity, public and granted playable-state eligibility, admission pointers, routing bundles, and fail-closed routing normalization.

- `AA-3.1` World and realm discovery catalog
- `AA-3.2` Public visibility, explicit grants, and playable-state policy
- `AA-3.3` Runtime target identity, admission pointers, and routing freshness

### Experience and Applications (`EA`)

#### `EA-1` Commands, Presentation, and Transcript

Player command vocabulary and extension, parsing and dispatch semantics, structured input/output, rendering, prompts, reconnect transcript presentation, durable command history, and client-facing interaction policy.

- `EA-1.1` Command vocabulary, stages, parsing, aliases, dispatch, and extension
- `EA-1.2` Structured output, rendering, prompts, and presentation policy
- `EA-1.3` Resume transcript and durable player command history

#### `EA-2` Social, Communication, Presence, and Safety

Player communication, friends and groups, presence, mail, social visibility, blocking, reporting, and player-facing safety controls.

- `EA-2.1` Chat, private communication, and mail
- `EA-2.2` Friends, groups, guilds, and social relationships
- `EA-2.3` Presence, visibility, and availability
- `EA-2.4` Player blocking, reporting, and safety controls

#### `EA-3` First-Party Applications and User Experience

First-party player, creator, and operator applications; navigation and workflow UX; accessibility; client state; and application-specific integration with platform contracts.

- `EA-3.1` Player web and mobile applications
- `EA-3.2` Creator authoring applications
- `EA-3.3` Operator and moderator applications
- `EA-3.4` Shared navigation, accessibility, client state, and interaction foundations

### Gameplay Runtime (`GR`)

#### `GR-1` Game Session Runtime, Ticks, and Coordination

Live gameplay execution, session front-end responsibilities, regional execution ownership, ticks, queues, leases, fencing, runtime coordination, command execution scheduling, and failure recovery.

- `GR-1.1` Session front-end and execution routing
- `GR-1.2` Tick timelines, action fairness, queues, and scheduling
- `GR-1.3` Region leases, fencing, and executor coordination
- `GR-1.4` Runtime recovery, replay, and reconciliation

#### `GR-2` World Runtime, Spatial State, and Movement

Runtime world topology, rooms and regions, occupancy, movement, exits, spatial reads, canonical room views, environmental state, and region transitions.

- `GR-2.1` World topology, rooms, regions, and runtime instances
- `GR-2.2` Location, occupancy, movement, exits, and spatial reads
- `GR-2.3` Ambient, environmental, and scheduled world state

#### `GR-3` Entity State, Inventory, Equipment, and Containment

Characters, NPCs, items, inventory, equipment, containers, ownership, placement, entity lifecycle, and authoritative entity state.

- `GR-3.1` Character, NPC, and item state and lifecycle
- `GR-3.2` Inventory, containers, ownership, and placement
- `GR-3.3` Equipment, body layouts, slots, and loadouts

#### `GR-4` Gameplay Rules, Actions, Effects, Combat, and Progression

Game-defined actions, targeting, rules, abilities, checks, effects, statuses, combat, advancement, progression, economy, and reusable gameplay policy.

- `GR-4.1` Action, targeting, check, effect, and status resolution
- `GR-4.2` Combat and conflict resolution
- `GR-4.3` Abilities, advancement, and progression
- `GR-4.4` In-game economy, trading, crafting, and exchange

### Authoring and Release (`AR`)

#### `AR-1` Creator Authoring, Publishing, and Release Data

Creator workflows, game and world authoring, entity and rules content, script and plugin packages, procedural and LLM-assisted tools, assets, templates, validation, versioning, publishing, release artifacts and attestations, playtesting, and external authoring tools.

- `AR-1.1` World, entity, rule, and content authoring
- `AR-1.2` Procedural, LLM-assisted, and external authoring tools
- `AR-1.3` Script, plugin, mod, and extension packaging
- `AR-1.4` Game-authored assets, branding, and release manifests
- `AR-1.5` Revisions, versions, publishing, validation, and attestation

#### `AR-2` Settings and Runtime Policy

Platform defaults, tenant/game overrides, scoped settings, effective configuration, feature and capability policy, validation, and scriptable runtime policy surfaces.

- `AR-2.1` Typed settings, defaults, scopes, and precedence
- `AR-2.2` Feature, command, profile, and capability policy
- `AR-2.3` Tenant/game overrides, safety caps, validation, and effective configuration

#### `AR-3` Runtime Activation and Cutover

Release activation, runtime game-instance creation, version cutover, compatibility gates, rollback, hotfix activation, and propagation of released design into runtime authorities.

- `AR-3.1` Runtime instance launch, lifecycle, and termination
- `AR-3.2` Release readiness, compatibility, and propagation
- `AR-3.3` Cutover, rollback, hotfix, and script-patch activation
- `AR-3.4` Playtest forks, reset, expiry, and isolation

### Automation and Scripting (`AS`)

#### `AS-1` Automation, Scripting, and Scheduled Behavior

Game-authored automation execution: event ingress, sandbox lifecycle, NPC and world behavior, quotas, timers, scheduled jobs, durable handoffs, runtime readiness, reload, and operational signals.

- `AS-1.1` Trigger and event ingress contracts
- `AS-1.2` Sandbox execution and script lifecycle
- `AS-1.3` NPC, AI, quest, and world automation
- `AS-1.4` Timers, schedulers, and offline behavior
- `AS-1.5` Durable command handoff, idempotency, and outcomes
- `AS-1.6` Quotas, readiness, reload, and automation runtime operations

### Shared Runtime Foundations (`SF`)

#### `SF-1` Contracts, Identity, Security, and Time

Shared API and message contracts, identifiers, tenant scope, authentication and authorization propagation, trust boundaries, secrets, certificates, error semantics, shared libraries, clocks, and time representation.

- `SF-1.1` API, message, schema, version, and application-error contracts
- `SF-1.2` Identifiers, tenant context, and routing identity primitives
- `SF-1.3` Authentication, authorization, trust, secrets, and certificates
- `SF-1.4` Clocks, timestamps, durations, and time semantics
- `SF-1.5` Shared libraries, interceptors, and contract helpers

#### `SF-2` Persistence, Caching, Transactions, and Workflows

Authoritative data ownership, databases, schema evolution, caching, Redis ownership, transaction boundaries, idempotency, outbox and replay patterns, Temporal workflows, consistency, and retention.

- `SF-2.1` PostgreSQL ownership, schemas, migrations, and retention
- `SF-2.2` Redis coordination, caching, rate limiting, and reset semantics
- `SF-2.3` Idempotency, outbox, replay, and reconciliation
- `SF-2.4` Local transactions, sagas, and Temporal workflows

### Platform and Operations (`PO`)

#### `PO-1` Operator Control, Moderation, Audit, and Compliance

Operator and control-plane workflows, moderation authority and enforcement, audit records, administrative access, compliance operations, incident controls, and platform governance.

- `PO-1.1` Administrative and control-plane workflows
- `PO-1.2` Moderation policy, enforcement, and appeals
- `PO-1.3` Audit evidence, compliance, and governance
- `PO-1.4` Incident remediation, overrides, and break-glass control

#### `PO-2` Edge Networking, Gateway, and Protocols

External traffic planes, routing and exposure policy, Gateway behavior, Telnet and WebSocket edges, TCP proxying, protocol bridging, client protocols, rate limiting, and edge failure semantics.

- `PO-2.1` Route exposure, edge trust, and traffic-plane policy
- `PO-2.2` WebSocket, Telnet, TCP proxy, and protocol bridging
- `PO-2.3` Client protocol negotiation and structured protocol extensions
- `PO-2.4` Edge limits, backpressure, close taxonomy, and retry behavior

#### `PO-3` Delivery, Environments, Assets, Backup, and Recovery

Build and deployment, environment classes, configuration delivery, infrastructure topology, platform asset infrastructure, migrations, release promotion, backups, restores, disaster recovery, and self-hosted operation.

- `PO-3.1` Packaging, CI/CD, deployment, promotion, and infrastructure topology
- `PO-3.2` Environment, configuration, secret, certificate, and service-discovery delivery
- `PO-3.3` Object storage, CDN, registry, and platform asset delivery infrastructure
- `PO-3.4` Backup, restore, disaster recovery, and self-hosted recovery

#### `PO-4` Observability, Reliability, Testing, and Verification

Logs, metrics, traces, alerting, health and readiness, reliability policy, incident evidence, test strategy, static analysis, smoke and load proof, operational verification, and architecture conformance checks.

- `PO-4.1` Logging, metrics, tracing, dashboards, and alerting
- `PO-4.2` Health, readiness, reliability policy, SLOs, and degraded operation
- `PO-4.3` Unit, integration, contract, static-analysis, and load verification
- `PO-4.4` Smoke, canary, incident evidence, and architecture conformance proof

## Boundary Rules

- Authentication identity and entitlement policy belong to `AA-1`; propagation and cryptographic trust contracts belong to `SF-1`; admission use of authenticated identity belongs to `AA-2` or `AA-3`.
- Reconnect eligibility and session lifecycle belong to `AA-2`; the rendered transcript and command-history experience belong to `EA-1`; persistence mechanics belong secondarily to `SF-2`.
- Runtime routing eligibility belongs to `AA-3`; edge transport routing belongs to `PO-2`; regional execution ownership belongs to `GR-1`.
- Player-facing safety controls belong to `EA-2`; operator moderation authority, enforcement governance, and audit belong to `PO-1`.
- Authored gameplay content belongs to `AR-1`; reusable runtime rule semantics belong to `GR-4`; settings and attachable policy selection belong to `AR-2`; script execution belongs to `AS-1`.
- Runtime world topology and occupancy belong to `GR-2`; entity placement and containment belong to `GR-3`; their joined room presentation is a declared handoff rather than duplicate ownership.
- Release data construction belongs to `AR-1`; effective settings belong to `AR-2`; activation and runtime cutover belong to `AR-3`.
- Operational jobs owned by platform infrastructure belong to `PO-3` or `PO-4`; game-authored scheduled behavior belongs to `AS-1`.
- Service-local storage details allocate to the capability whose authority they implement, with `SF-2` as a secondary handoff unless the document establishes a shared persistence or workflow contract.
- Platform commerce and billing belong to `AA-1`; in-game economy, trading, and crafting belong to `GR-4`.
- Published game assets and manifests belong to `AR-1`; object-store, CDN, registry, and environment delivery infrastructure belong to `PO-3`.
- Runtime execution recovery belongs to `GR-1`; player reconnect continuity belongs to `AA-2`; infrastructure disaster recovery belongs to `PO-3`; reliability objectives and proof belong to `PO-4`.
- Player, creator, operator, and platform-developer perspectives are journey and review lenses across capabilities, not separate capability authorities.

## Product Coverage Basis

The taxonomy was checked against the canonical product requirements, persona journeys, system overview, service responsibility matrix, infrastructure overview, and service architecture. The following sources establish why every capability group is a real product or platform concern rather than an arbitrary tracking bucket.

| Capability | Representative canonical sources |
| --- | --- |
| `AA-1` | [Core requirements](../project-management/core-requirements.md), [Account Service](./microservices/account-service/README.md), [Player journeys](./user-journeys-players.md) |
| `AA-2` | [Authentication](./system-architecture-authentication.md), [Reconnection](./system-architecture-reconnection.md), [Session behavior](./system-architecture-session-behavior.md) |
| `AA-3` | [Multi-tenancy](./system-architecture-multi-tenancy.md), [Versioning and runtime](./system-architecture-versioning-runtime.md), [Player journeys](./user-journeys-players.md) |
| `EA-1` | [Player command model](./system-architecture-player-command-model.md), [Input, output, and presentation](./system-architecture-input-output-and-presentation.md) |
| `EA-2` | [Core requirements](../project-management/core-requirements.md), [Social and Groups Service](./microservices/social-groups-service/README.md), [Player journeys](./user-journeys-players.md) |
| `EA-3` | [Frontend architecture](./system-architecture-frontend.md), [Player journeys](./user-journeys-players.md), [Creator journeys](./user-journeys-creators.md), [Operator journeys](./user-journeys-operators.md) |
| `GR-1` | [Tick architecture](./system-architecture-ticks.md), [System overview](./system-architecture-overview.md), [Game Session Service](./microservices/game-session-service/README.md) |
| `GR-2` | [Core requirements](../project-management/core-requirements.md), [World Management Service](./microservices/world-management-service/README.md), [System overview](./system-architecture-overview.md) |
| `GR-3` | [Core requirements](../project-management/core-requirements.md), [Entity Management Service](./microservices/entity-management-service/README.md) |
| `GR-4` | [Core requirements](../project-management/core-requirements.md), [Game Logic Service](./microservices/game-logic-service/README.md) |
| `AR-1` | [Creator journeys](./user-journeys-creators.md), [Game Design Service](./microservices/game-design-service/README.md), [LLM content tools](./system-architecture-llm-content-tools.md) |
| `AR-2` | [Settings model](./system-architecture-settings-model.md), [Game customization](./system-architecture-game-customization.md) |
| `AR-3` | [Versioning and runtime](./system-architecture-versioning-runtime.md), [Creator journeys](./user-journeys-creators.md), [World creation workflow](./microservices/world-management-service/world-creation-workflow.md) |
| `AS-1` | [Scripting architecture](./system-architecture-scripting.md), [Automation and Scripting Service](./microservices/automation-scripting-service/README.md) |
| `SF-1` | [gRPC architecture](./system-architecture-grpc.md), [Security](./system-architecture-security.md), [Identifier glossary](./system-architecture-identifier-glossary.md) |
| `SF-2` | [Database migrations](./system-architecture-database-migrations.md), [Redis architecture](./system-architecture-redis.md), [Transactions](./system-architecture-transactions.md), [Temporal workflows](./system-architecture-temporal-workflows.md) |
| `PO-1` | [Operator journeys](./user-journeys-operators.md), [Logging and Admin Service](./microservices/logging-admin-service/README.md), [Moderation policies](./microservices/logging-admin-service/moderation-policies.md) |
| `PO-2` | [Gateway](./system-architecture-gateway.md), [Protocol bridging](./system-architecture-protocol-bridging.md), [TCP Proxy Service](./microservices/tcp-proxy-service/README.md) |
| `PO-3` | [Infrastructure](./infrastructure/README.md), [Deployment environments](./infrastructure/deployment-environments.md), [Backup and recovery](./system-architecture-backup-recovery.md), [CI/CD](./system-architecture-cicd.md) |
| `PO-4` | [Logging and monitoring](./system-architecture-logging-monitoring.md), [Testing](./system-architecture-testing.md), [Tracing](./system-architecture-tracing.md) |
