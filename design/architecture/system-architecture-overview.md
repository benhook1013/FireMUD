# FireMUD System Architecture: Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## Architecture Decisions (Canonical)

The documents linked from this overview describe the target-state design, but the following decisions are treated as **canonical contracts** that other architecture docs must align to.

- **Gateway responsibility model:** Spring Cloud Gateway is the single ingress for HTTP and WebSocket traffic and the central place for routing, coarse route gating, rate limiting, and observability. It is not the platform’s authorization authority: JWT validation and role/tenant authorization are performed by the consuming meta/control services using shared middleware and the Account Service JWKS.
- **Gameplay sharding scope (edge vs Game Session):** Spring Cloud Gateway does not own a gameplay shard routing plane. `/ws/game/**` routes to a stable Game Session service surface; any lease ownership and region sharding are internal to the Game Session layer and its coordination mechanisms. See `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md` for the canonical scope decision.
- **Gameplay session routing inside Game Session:** Connected gameplay sockets attach to a stable Game Session session front-end pod, while region-scoped tick execution remains fenced to the current lease owner for `<tenantId, regionId>`. Session front-ends may forward region-owned work over internal gRPC, but only the lease owner may mutate tick coordination state. See `design/architecture/decisions/adr-0011-gameplay-session-front-end-and-region-execution.md`.
- **Multi-cluster gameplay sharding scope:** FireMUD target state assumes single-cluster gameplay execution per deployment, with scale via lease-based in-cluster Game Session rebalancing. Cross-cluster gameplay sharding is out of scope until a dedicated end-to-end design package is accepted. See `design/architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md`.
- **Lease moves and reconnect behavior:** The platform favors **close-and-reconnect** over mid-connection migration. The edge contract does not define a distinct “shard handoff” close category; client-visible outcomes remain limited to the standard close taxonomy (for example `backend_unavailable` for sustained gameplay-path failures). If a future design introduces explicit handoff semantics at the edge, it must be defined as a dedicated design update and integrated into the gateway + protocol bridging contracts (see `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`).
- **Quotas and entitlements source of truth:** Subscription entitlements and plan-driven quota values are owned by the Account Service (for example via `GetTenantEntitlements(tenantId)`). Logging & Admin provides dashboards, audit trails, and operator UX; any operator overrides must be represented as an overlay that is merged into the Account Service entitlement contract so enforcement points consume a single canonical view.
- **Operator control-plane availability split:** Logging & Admin may depend on Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, and Alertmanager for observability-heavy experiences, but core operator actions such as moderation, feature-flag requests, quota overrides, and tick-remediation controls must remain available when those backends are degraded. Logging & Admin owns the operator UX, request validation, and audit trail for these actions, while the owning domain services remain the only components allowed to mutate runtime or policy state. Readiness, resource isolation, and degradation behavior must preserve this split.
- **Durable async contract:** Best-effort edge hints may use internal gRPC event sinks, but durable cross-service business events and saga updates must use the transactional outbox/background-worker pattern described in `design/architecture/system-architecture-transactions.md`. High-level docs must not imply an unspecified shared event bus.
- **Edge-route exposure default:** Besides `/ws/game/**`, only explicitly allowlisted admin/creator APIs are edge-routable through Gateway. Account, Game Design, Game Session control-plane APIs, Social & Groups admin APIs, and Logging & Admin are edge-routable; World Management, Entity Management, Game Logic, and Automation & Scripting remain internal-only unless a dedicated design update expands the allowlist.
- **Redis topology policy:** In all non-ephemeral environments, Coordination Redis and Cache/Rate-Limit Redis are separate deployments. Local development is treated as non-ephemeral and should run two Redis deployments to exercise role separation. Truly ephemeral CI/preview stacks may collapse roles into a single Redis instance only when explicitly documented and guarded as an ephemeral topology.
- **Coordination Redis ownership boundary:** Coordination Redis prefixes are owner-governed (Game Session for gameplay coordination prefixes such as `session:game:*`, `tick:*`, `timer:*`, `retry:*`, and `tick-executor-lease:*`; Account Service for `session:auth:*`; Automation & Scripting for `automation:*`), and non-owner participation is allowed only through documented shared-helper contracts. See `design/architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md`.
- **TCP Proxy identity canonicalization:** For Gateway header trust on the TCP Proxy → Gateway mTLS hop, URI SAN identity is canonical in production; DNS SAN is transitional and fingerprint pinning is break-glass only. See `design/architecture/decisions/adr-0010-tcp-proxy-identity-canonicalization.md`.

## Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities
- **Spring Cloud Gateway** serves as the unified HTTP and WebSocket entry point for all clients
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway (in production this is typically fronted by a Telnet edge proxy that forwards to the TCP Proxy using PROXY protocol). The Proxy → Gateway hop is secured with mutual TLS; see [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway), [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-edge-proxy-and-proxy-protocol), and the TCP Proxy Service design’s **Implementation Status** section for environment-specific wiring details.
- **Consistent end-to-end WebSocket flow**: Telnet (TCP) → TCP Proxy Service (WebSocket upgrade) → Spring Cloud Gateway → Game Session Service
- **All application-level gameplay and admin traffic is routed through the Spring Cloud Gateway**, ensuring centralized **traffic routing, monitoring, and observability**. External admin and creator APIs are HTTP(S) surfaces routed through the Gateway allowlist; external domain gRPC is not an edge contract unless a dedicated design update explicitly adds it. Raw Telnet TCP terminates at the Telnet edge proxy and TCP Proxy Service before being bridged to the Gateway over WebSocket. See [Gateway Architecture](./system-architecture-gateway.md) for deployment details and stateless behavior.
  - Ordering and delivery guarantees for the combined Telnet and WebSocket path (FIFO where delivered, at-most-once semantics, and explicit drop conditions) are documented in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
  - Backpressure and slow-client behavior across the TCP Proxy and WebSocket layers are described in [Protocol Bridging](./system-architecture-protocol-bridging.md#backpressure--slow-clients).
   > 🛑 **Gameplay login is fronted by the Game Session Service**, which handles the `LOGIN` command and binds sessions in Redis. It calls the Account Service to verify credentials and obtain JWTs/tokens. The Gateway simply forwards any admin tokens, and JWTs are validated by the admin or logging services themselves; gameplay protocol clients do not present JWTs, while first-party WebSocket clients use a short-lived edge connect token on `/ws/game/**` before `LOGIN`/`PLAY`. See [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) for the full login flow.
- **Telnet clients maintain sticky TCP connections only to the TCP Proxy Service**, which buffers **active input** but **discards it across reconnects**
- **Reconnection logic is handled in layers** to preserve gameplay continuity
- **Synchronous internal service-to-service communication from the Game Session Service onward uses gRPC**, with strict schema enforcement and low latency. All calls are encrypted with **mutual TLS**; see [Security Architecture](./system-architecture-security.md). Asynchronous cross-service signaling (for example edge disconnect hints and saga/domain events) uses documented event contracts and idempotency keys.
- **Gameplay session bindings and tick coordination state are stored in Redis**, while durable Game Session control-plane metadata remains in PostgreSQL; this keeps the gameplay coordination path stateless at the pod level and enables full reconnect recovery
- **Game definitions and rules are data-driven and editable via tooling without redeploying code**; see the [Game Design Service documentation](./microservices/game-design-service/README.md).
- **Game Session Service orchestrates live game instances**, handling tick execution and runtime configuration
- [**Feature flags**](./microservices/game-design-service/feature-flags.md) are defined at design-time in the Game Design Service; Logging & Admin provides the operator UI for runtime toggles, while Game Session owns the runtime override state and enforcement during gameplay.
- **One active gameplay binding per identity key is enforced** — logging in from another client forcibly transfers control to the new session and terminates the old one. The canonical uniqueness key is `{tenantId, gameInstanceId, characterId}`, as defined in [Authentication & Authorization](./system-architecture-authentication.md#contract-decisions-normative).
- **Multi-tenant architecture shares infrastructure across games; per-game resource quotas prevent one tenant from exhausting cluster capacity.**
- **Admin and operations tooling communicates with Spring Cloud Gateway over an internal gRPC management API** for route and health management; no gameplay traffic flows over this control-plane path.

### Admin Entry Points and Control Plane

All external admin and creator tools access the platform through the **Spring Cloud Gateway**; Logging & Admin Service is never exposed directly at the network edge.

- **Control-plane API:** Admin/ops tools use an internal **gRPC management API** on the Gateway for route configuration, health checks, and runtime configuration that affects Gateway behavior itself. This path is for infrastructure and routing concerns only; it does not directly perform moderation or gameplay actions.
- **Admin/creator data-plane APIs:** Admin and moderation UIs talk to **Logging & Admin Service and other domain services via the Gateway**, using standard HTTP(S) APIs routed through Gateway’s configuration. This path centralizes routing, coarse route protections, rate limiting, and audit/observability hooks; JWT validation and fine-grained authorization are performed by the consuming services.
- **Internal-only dependencies:** Logging & Admin Service calls Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, and Alertmanager directly from the internal network for analytics and dashboards. These observability backends are **not** exposed to clients and are treated as internal, operator-facing dependencies of Logging & Admin.

#### Edge Exposure Policy (Canonical)

Gateway-routed external surfaces are intentionally narrow:

| Surface | Edge-routable status | Notes |
| --- | --- | --- |
| `/ws/game/**` gameplay WebSocket | Yes | Canonical gameplay entry point for web clients and TCP Proxy bridged Telnet sessions. |
| Logging & Admin admin APIs | Yes | Routed through Gateway allowlist only. |
| Account control-plane APIs | Yes | Routed through Gateway allowlist only. |
| Game Design control-plane APIs | Yes | Routed through Gateway allowlist only. |
| Game Session control-plane/admin APIs | Yes | Routed through Gateway allowlist only. |
| Social & Groups admin APIs | Yes | Routed through Gateway allowlist only. |
| World Management, Entity Management, Game Logic, Automation & Scripting direct APIs | No by default | Internal-only service surfaces unless a dedicated design update explicitly adds an edge route group and auth model. |

Network policies and ingress configuration must reflect this model:

- Only Gateway and TCP Proxy Service are reachable from external networks.
- Logging & Admin Service accepts traffic only from Gateway (and from observability systems where necessary), not from the public internet or VPN clients directly.

#### Gateway Management Plane Capability Matrix (Canonical)

The management-plane contract must be explicit so operator tooling does not assume unsupported mutating behavior in production-like environments.

| Gateway capability | Intended use | Current production-like status | Notes |
| --- | --- | --- | --- |
| gRPC/REST health and route inspection | Internal diagnostics and control-plane health checks | Supported | Internal-only network surface; mTLS-authenticated operator clients. |
| Baseline route configuration (`routes-*.yml` + env overrides) | Canonical route definitions and controlled deployment-time changes | Supported | Baseline config files remain the source of truth. |
| Dynamic route override APIs (runtime mutating overrides) | Ephemeral route changes without redeploy | **Not supported in production-like environments (dev/test only)** | Until shared persistence, multi-pod convergence, and full route-change auditing are implemented. |

This matrix is canonical for high-level architecture docs and must remain aligned with [Gateway Architecture](./system-architecture-gateway.md#dynamic-route-override-lifecycle).

#### Core Operator Action Backing Contracts (Canonical)

Core operator actions must not rely on observability backends for write success. Logging & Admin is the operator-facing entry point for these actions, but it is not allowed to become the runtime state owner for other domains.

| Operator action | Operator-facing entry point | Runtime/policy owner | Required write path | Required durable store(s) for success | Observability dependency allowed for write success |
| --- | --- | --- | --- | --- | --- |
| Moderation action (`gameplay_ban`, `chat_mute`, `chat_ban`) | Logging & Admin HTTP(S) APIs via Gateway | Logging & Admin defines policy; Game Session or Social & Groups enforce runtime scope | Logging & Admin records audit and calls owning enforcement/policy APIs | Logging & Admin PostgreSQL audit state plus owning service PostgreSQL/control-plane state | No |
| Runtime feature-flag override | Logging & Admin HTTP(S) APIs via Gateway | Game Session | Logging & Admin records audit and calls Game Session `ToggleFeatureFlag`/equivalent control API | Game Session PostgreSQL plus Logging & Admin PostgreSQL audit state | No |
| Quota override | Logging & Admin HTTP(S) APIs via Gateway | Account Service canonical entitlement contract | Logging & Admin records audit and calls Account control-plane API so the merged entitlement view remains canonical at Account | Account PostgreSQL plus Logging & Admin PostgreSQL audit state | No |
| Tick remediation (`PauseTicks`, `ResumeTicks`, scoped remediation requests) | Logging & Admin HTTP(S) APIs via Gateway | Game Session | Logging & Admin records audit and calls Game Session control APIs; direct Redis mutation is reserved for documented runbooks, not UI/API request handlers | Game Session PostgreSQL/control-plane state plus Logging & Admin PostgreSQL audit state | No |

### Authentication Modes and Boundaries

FireMUD uses two complementary authentication modes that share a common identity model but differ in how they are presented by clients:

- **Gameplay sessions (players)**  
  - Players authenticate using the `LOGIN` command handled by the **Game Session Service**.  
  - Game Session delegates credential verification (including 2FA, external identity providers, and lockout rules) to the **Account Service**, which owns all credential and account-security decisions.  
  - On success, Game Session creates and maintains a Redis-backed gameplay session binding (tenant, character, tick-region context) and enforces one active binding per `{tenantId, gameInstanceId, characterId}`. Gameplay traffic is authenticated by this Redis session context rather than by browser-style JWTs sent on each message.

- **Admin and creator sessions (control plane)**  
  - Admin and creator tools authenticate via HTTP/gRPC using JWTs issued by the **Account Service**, which publishes JWKS and remains the source of truth for token semantics.  
  - Internal services validate JWTs using a shared library and JWKS; they do not make ad-hoc token-parsing decisions.  
  - Spring Cloud Gateway forwards auth headers and can enforce coarse-grained route protections (for example, “admin endpoints require a valid JWT”) but does not own credential verification or authorization policy.

This split keeps gameplay session management and tick-sensitive orchestration in the Game Session Service while ensuring that account security, token issuance, and policy remain centralized in the Account Service. See [Authentication & Authorization](./system-architecture-authentication.md) for detailed flows.

> 🔗 See [System Architecture Diagram](./system-architecture-diagram.md) and [System Context Diagram](./system-context-diagram.md).

---

## Implementation Status

Unless otherwise noted, this document describes the **target-state architecture** for FireMUD. The Telnet edge chain (Telnet client → Telnet edge proxy with PROXY protocol → TCP Proxy Service → Spring Cloud Gateway with mTLS) and related certificate wiring are being rolled out incrementally.

For current rollout and configuration details, refer to:

- The **Implementation Status** section in the [TCP Proxy Service design](./microservices/tcp-proxy-service/README.md)
- The [Telnet Path Degraded Runbook](./system-architecture-telnet-degraded-runbook.md)
- The relevant sections in [Security Architecture](./system-architecture-security.md) and [Protocol Bridging](./system-architecture-protocol-bridging.md)

## Reconnection Strategy

FireMUD supports seamless gameplay recovery through a layered reconnection model:

| Layer | Responsibility |
| --- | --- |
| TCP Proxy Service | Buffers Telnet input; clears on disconnect |
| Spring Cloud Gateway | Stateless; does not replay; enforces close-code taxonomy and triggers reconnects on backend unavailability |
| Game Session Service | Restores gameplay session using Redis |

Certain failures can affect only the Telnet path while web clients remain healthy, such as misconfigured TLS or mTLS on the TCP Proxy → Gateway WebSocket bridge or issues in the Telnet edge proxy/PROXY-protocol chain. When Telnet is degraded but WebSocket remains healthy, operators should consult the [Telnet Path Degraded Runbook](./system-architecture-telnet-degraded-runbook.md) alongside the general [Reconnection Strategy](./system-architecture-reconnection.md).

> 🔗 See [Reconnection Strategy](./system-architecture-reconnection.md) for full details on session resumption, reauthentication, and failure handling.

---

## Redis Roles, Keyspace Partitioning, and Data Ownership

Persistent, authoritative data and transient coordination state are deliberately separated so gameplay remains consistent under load:

- **Authoritative data** (accounts, world topology, entities, chat history, moderation records, and similar) is stored in PostgreSQL by domain-aligned services.
- **Coordination Redis** holds volatile, gameplay-critical structures (session bindings, tick queues, locks, timers) owned primarily by the Game Session Service and a small number of cooperating services using shared helpers.
- **Cache/Rate-Limit Redis** is used for best-effort caches and rate limiting by Spring Cloud Gateway, the TCP Proxy Service, and selected backend services; these keys use dedicated prefixes and must not share a deployment with coordination keys in player-facing environments.

Within Redis, keys are further partitioned by responsibility and, in production, can be mapped onto different logical databases or clusters:

- **Coordination and session keys (Coordination Redis)**  
  - Examples: gameplay sessions, tick-region leases, command queues, timers, and automation tick coordination.  
  - Canonical prefixes include (non-exhaustive):  
    - `session:game:*` – gameplay session bindings and takeover metadata (Game Session-owned).
    - `session:auth:*` – auth token allowlist and revocation-watermark prefixes (Account-owned).
    - `tick:*` – tick queues, region scheduling, and pacing-related state.  
    - `timer:*`, `retry:*`, `tick-executor-lease:*` – tick coordination helpers owned by Game Session.
    - `automation:*` – automation and scripting coordination keys owned by Automation & Scripting Service (other services interact via gRPC APIs rather than writing these keys directly).

- **Cache and rate-limit keys (Cache/Rate-Limit Redis)**  
  - Examples: read-side caches, rate-limit counters, and quota tracking for non-critical flows.  
  - Canonical prefixes include (non-exhaustive):  
    - `cache:*` – general-purpose caches for derived data, short-lived lookups, and infrequently updated views.  
    - `ratelimit:*` – per-account or per-IP rate limiting for APIs, login attempts, and abuse prevention.

Coordination Redis and Cache/Rate-Limit Redis are **separate Redis deployments in all non-ephemeral environments** so cache or rate-limit spikes cannot degrade tick execution or session coordination. Local development runs both roles as separate deployments to exercise role separation. Truly ephemeral CI/preview stacks may collapse roles into a single Redis instance only when explicitly documented as an ephemeral topology.

See [Redis Architecture](./system-architecture-redis.md) and [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md) for the detailed key structure, multi-tenant key design, and allowed patterns, and the [Service Responsibility Matrix](./service-responsibility-matrix.md) for which services participate in each Redis role.

---

## Major Components and Their Roles

| Component | Purpose |
| --- | --- |
| **Web Clients** | Modern browser clients using WebSocket or HTTP to access the platform |
| **MUD Clients** | Traditional Telnet clients connecting via TCP, proxied into the system |
| **[TCP Proxy Service](./microservices/tcp-proxy-service/README.md)** | Accepts Telnet connections, buffers input, forwards over WebSocket; proxy-to-gateway mTLS secures the link |
| **[Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md)** | Handles WebSocket termination, routing, and observability; enforces coarse-grained admin access controls but does not own gameplay authentication or authorization decisions |
| **[Game Session Service](./microservices/game-session-service/README.md)** | Fronts gameplay login commands and session binding, manages player sessions, tick orchestration, runtime flags, input validation, and durable game-instance/runtime control metadata |
| **[Account Service](./microservices/account-service/README.md)** | Manages player accounts, credentials, authentication, and JWT/JWKS issuance; handles subscriptions and account-security ban state; publishes the canonical tenant entitlement/quota contract consumed by enforcement points |
| **[Entity Management Service](./microservices/entity-management-service/README.md)** | Handles all runtime entity data: players, NPCs, items, stats, and all inventories/containment (player inventory/equipment, containers, and items on the ground held in room-ground container entities keyed by room/instance ID) |
| **[World Management Service](./microservices/world-management-service/README.md)** | Owns maps, rooms, and tick region structure; provides room/region geometry and snapshots, plus authoritative runtime location/occupancy and mutable room-environment state (doors, hazards, and persistent ambient flags) |
| **[Game Logic Service](./microservices/game-logic-service/README.md)** | Executes gameplay mechanics; resolves actions deterministically, including movement/travel cost computation |
| **[Automation & Scripting Service](./microservices/automation-scripting-service/README.md)** | Triggers AI and scripted behaviors |
| **[Social & Groups Service](./microservices/social-groups-service/README.md)** | Manages chat, mail, guilds, and social features, and enforces chat mutes/bans at message send time based on moderation decisions from Logging & Admin Service |
| **[Logging & Admin Service](./microservices/logging-admin-service/README.md)** | Provides admin tools, metrics dashboards, and audit logs; owns moderation policy definition and audit trails that downstream services enforce; provides operator UX and auditing for quota/limit overrides that are represented as an overlay on Account Service entitlements |
| **[Game Design Service](./microservices/game-design-service/README.md)** | Authoring tool for designing and publishing game data; defines feature flags; publishing workflow copies data to runtime services |

> 🔗 See [Microservices Documentation](./microservices/README.md) for the full list of responsibilities and APIs.

## Communication Flows

| Flow | Protocol |
| --- | --- |
| Web Clients → Spring Cloud Gateway | WebSocket (wss) / HTTP (https) (public ingress) |
| MUD Clients → TCP Proxy Service | Raw TCP (Telnet) |
| TCP Proxy Service → Spring Cloud Gateway | WebSocket (`ws://` in local/dev; `wss://` with mTLS in production) |
| Spring Cloud Gateway → Game Session Service | WebSocket (`ws://` in-cluster) |
| Game Session Service → Other Microservices | gRPC (internal synchronous RPCs) |

✅ Internal synchronous RPC communication from the Game Session Service onward uses **gRPC** with strict schema enforcement.

## Asynchronous and Event Flows

The architecture also relies on explicit asynchronous contracts that are separate from synchronous request/response RPCs:

| Flow | Delivery semantics | Authority and safety rules |
| --- | --- | --- |
| TCP Proxy Service → Game Session Service `NotifyDisconnect` | At-least-once best-effort gRPC event sink | Advisory only; dedupe key `{proxyConnectionId, disconnectSequence}`; Redis + gameplay activity remain liveness source of truth. |
| Account/Domain services → Logging & Admin (audit/moderation/saga events) | Durable domain events/saga-step updates with at-least-once delivery | Event envelopes must carry a stable dedupe identity (for example `{tenantId, producerService, eventType, eventId}`), `occurredAt`, and a schema version; consumers must process idempotently. Logging & Admin is a control-plane consumer; runtime enforcement still occurs in owning domain services. |
| Game Session Service → Logging & Admin (session lifecycle/coordination health signals) | Streaming metrics/events | Used for operator workflows and automation; does not transfer gameplay state authority away from Game Session. |

Durable domain-event delivery in FireMUD is implemented via the transactional outbox/background-worker pattern described in [Transaction Strategies](./system-architecture-transactions.md#tick-adjacent-workflows-outbox-boundary), not via an implicit shared event bus.

---

## Data and State Management

- **Persistent data** (accounts, entities, rooms) is stored in PostgreSQL by domain-aligned services
- **Volatile gameplay coordination state** (gameplay session bindings, command queues, timers, retries, and region leases) is stored in Redis and coordinated by the Game Session Service, while Game Session control-plane/runtime metadata remains in PostgreSQL
- **Redis** is a **non-authoritative coordination buffer** — but **critical** for consistency, ticks, retries, and recovery
- **Tick regions** are shard-aligned in Redis to preserve atomicity
- **DMZ services (TCP Proxy Service and Spring Cloud Gateway)** remain stateless with respect to PostgreSQL; they may use **Cache/Rate-Limit Redis** and always emit logs/metrics, but do not own persistent domain tables.
- Runtime services do **not** directly read or write another service’s PostgreSQL tables; cross-domain access is through owned APIs/contracts.

### Canonical Runtime State Boundaries

The following boundaries are canonical for first-slice implementation and are intentionally restated here so teams do not infer competing ownership from lower-level docs.

| Runtime concern | Canonical owner | Canonical persistence / mutation boundary |
| --- | --- | --- |
| Gameplay session bindings, tick queues, timers, retry metadata, region leases | Game Session | Coordination Redis only; owner-governed prefixes and Lua-scripted mutation paths |
| Game Session control-plane runtime metadata | Game Session | Game Session PostgreSQL tables for game instances, pinned runtime version/script patch state, active feature-flag overrides, disconnect dedupe state, and other operator/audit-relevant control metadata |
| Account/session token control metadata | Account Service | Account PostgreSQL plus Account-owned Coordination Redis prefixes such as `session:auth:*` |
| Runtime entity state, inventories, item containment, room-ground containers | Entity Management | Entity PostgreSQL/runtime tables only |
| Runtime room occupancy/location and mutable room environment state | World Management | World PostgreSQL/runtime tables only |

### Canonical Room Runtime Contract

- World Management is the sole owner of runtime room occupancy/location and mutable room-environment state.
- Entity Management is the sole owner of inventories, containment, and room-ground containers keyed by `RoomInstanceRef`.
- Game Session orchestrates movement and other tick-owned actions, but it must not maintain a competing authoritative occupancy index.
- Room-view assembly must read occupancy from World Management and containment/entity presentation from Entity Management using a shared read fence or equivalent same-tick aggregation contract; mixed-tick best-effort joins are not allowed for canonical room state.

> 🔗 See [Redis Architecture](./system-architecture-redis.md) for key structure and durability strategies.

---

## Game Loop / Tick Model

FireMUD uses a **Hybrid Tick Model** to balance responsiveness and fairness:

- **One action per entity per tick** (pulled from command queues)
- **Region-scoped ticks** execute independently for parallelism
- **Tick state** (locks, queues, timers) is stored and coordinated via Redis

> 🔗 See [Tick System and Runtime Design](./system-architecture-ticks.md) for tick execution, staging/rollback, retry policies, and crash recovery.

---

## Scaling Model

FireMUD’s gameplay services are designed to scale horizontally:

- **Game Session Service** scales out across nodes and shards work by tick region, using Redis keys and Lua scripts to coordinate region-local ticks without a single authoritative process.
- **Game Logic Service** is stateless and horizontally scalable; each instance resolves actions deterministically based on the input state it receives from Game Session and Entity Management.
- Other microservices (Account, Entity, World, Social, Logging & Admin) scale independently behind Kubernetes `Deployment` objects and shared PostgreSQL/Redis infrastructure.

This model avoids single-node bottlenecks for ticks or session handling; see [Tick System and Runtime Design](./system-architecture-ticks.md) and [System Architecture – Scaling Runbook](./system-architecture-scaling-runbook.md) for detailed guidance on region sizing, pod counts, and operational tuning.

### Session Sharding & Routing

Game Session Service instances are deployed as a **pool of identical workers**. Ownership of tick work and live gameplay session execution is partitioned by `<tenantId, regionId>` using Coordination Redis leases as described in [Tick System and Runtime Design](./system-architecture-ticks.md).

Per `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`, shard/lease ownership remains internal to the Game Session layer: the edge does not implement lease-aware admission or a client-visible shard handoff signal. `/ws/game/**` is routed to a stable Game Session service surface and relies on the Game Session coordination model to respect tick ownership invariants.

Per `design/architecture/decisions/adr-0011-gameplay-session-front-end-and-region-execution.md`, this stable surface is implemented as a **session front-end + lease-owner execution** model:

- A gameplay socket binds to a Game Session **session front-end** pod that owns connection-local state and client I/O.
- Region-scoped execution remains fenced to the current **lease owner** for `<tenantId, regionId>`.
- Session front-ends may forward command execution or region-owned work over internal gRPC to the lease owner.
- Only the lease owner may mutate region-scoped coordination keys or commit tick-owned gameplay state for that region.

Forwarded internal gameplay requests must include a lease/epoch fence plus session identity and sequencing metadata so stale front-ends cannot race or reorder region-owned mutations after lease loss. Stale-fence forwards are rejected at the application layer and require the front-end to refresh ownership before retrying.

This preserves a stable edge contract while allowing in-cluster lease rebalancing without requiring client-visible shard routing.

If a future architecture introduces explicit edge shard routing or client-visible handoff semantics, it must be defined as a dedicated design update (routing-key transport, trust model, reconnection/backoff policy) and then integrated into:

- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/system-architecture-reconnection.md`

---

## Authentication and Authorization Flow

Clients authenticate using the `LOGIN` command, processed by the **Game Session Service**.
On initial login, Game Session delegates full credential verification (including lockout and MFA rules) to the **Account Service**.
On disconnect, clients reconnect by issuing `LOGIN` again with credentials (and OTP when required), then re-binding gameplay scope with `PLAY` (`PLAY <world> [character]`) before gameplay commands are accepted. Game Session uses Coordination Redis to decide whether to resume an existing gameplay session for the selected `{tenantId, gameInstanceId, characterId}` binding (for example, when the previous session binding is still valid and not revoked) or start a fresh session when keys or auth state no longer permit resumption.
Session state is stored in Coordination Redis and reused for recovery when the Redis-backed gameplay session and auth token allowlist entries are still valid.

> 🔗 See [Authentication & Authorization](./system-architecture-authentication.md) and [Reconnection Strategy](./system-architecture-reconnection.md) for detailed JWT format and session flow.

---

## Observability and Monitoring

See [Logging & Monitoring](./system-architecture-logging-monitoring.md) for the full pipeline, including Fluent Bit, Prometheus, and related dashboards.

From the perspective of admin and moderation tooling there are two broad classes of features:

- **Core admin actions** – Feature flag toggles, bans/unbans, basic account and session controls, and other actions that primarily talk to domain microservices (for example, Account, Game Session, Social & Groups) via the Gateway. These are designed to remain available even if Elasticsearch, Prometheus, Jaeger, or Alertmanager are temporarily unavailable.
- **Observability-driven workflows** – Log search, metrics and trace dashboards, and alert-centric investigations that depend on Elasticsearch, Prometheus, Jaeger, and Alertmanager being healthy. These surfaces may degrade or become read-only during observability outages but should not block core admin actions.

Implementations of Logging & Admin must preserve this separation with independent readiness/degradation behavior and resource isolation so observability outages do not take down the operator control plane.

## Gameplay Hot Path Policy

Common gameplay commands must use a bounded synchronous fan-out model:

- One service may orchestrate a hot-path read or command evaluation, but downstream participants on that path should avoid recursively building new cross-service fan-out trees.
- Read-heavy commands with stable transcript shapes (for example `LOOK`) should prefer pre-rendered or pre-aggregated gameplay read models where available, such as Game Session-owned `view:room-look:*` caches, with authoritative recomputation on miss.
- For `LOOK`-class reads, World Management remains the authority for room snapshot and occupancy, while Entity Management enriches caller-supplied occupant/entity references with entity-owned display state. Entity Management should not make a nested occupancy fetch back into World Management on the steady-state hot path.
- New command designs that require synchronous calls to more than two downstream domain services in steady-state must document latency budgets, fallback behavior, and why a read model or pre-aggregation approach is insufficient.

> 🔗 See additional Redis metrics and SLO guidance in [Redis Operations & Migrations](./system-architecture-redis-operations.md).

---

## Deployment Layers

| Layer | Technology |
| --- | --- |
| Client Layer | Browser, Telnet MUD Clients |
| Proxy Layer | TCP Proxy Service (LoadBalancer Service) |
| API Gateway Layer | Spring Cloud Gateway (LoadBalancer Service) |
| Gameplay Session Layer | Game Session Service |
| Service Layer | Microservices (Account, Entity, World, Logic, etc.) |
| Infrastructure Layer | Kubernetes with IPVS, Docker Compose (for local development) |

Deployment health checks (readiness and liveness probes) for these layers are described in detail in [Deployment Environments](./infrastructure/deployment-environments.md).

Environment-specific routing is configured via Spring profiles defined in `application.yml` and selected by the `SPRING_PROFILES_ACTIVE` environment variable. See [Deployment Environments](./infrastructure/deployment-environments.md#spring-profile-configuration) for how the `dev` and `prod` profiles differ between Docker Compose and Kubernetes.

---

## Notes on Responsibility Alignment

- Functional responsibilities are defined in the [Service Responsibility Matrix](./service-responsibility-matrix.md)
- **Game Session Service** orchestrates tick lifecycles, retries, and session management
- **Game Logic Service** resolves individual actions deterministically based on input state
- **Redis** acts as a passive, high-speed execution substrate — storing volatile state and enabling atomic coordination via Lua scripts

**Movement/Travel** rules are part of **Game Logic Service**. World stores geometry and region metadata (e.g., `spacingMultiplier`), while **Game Logic** derives movement/travel costs at runtime.

🧠 **Why Game Session Service vs Game Logic Service?**
Game Logic Service is stateless and deterministic.
Game Session Service governs pacing, conflict handling, and orchestration across distributed tick regions.

### Command Fan-Out, Orchestration, and Scaling

Game Session Service is an **orchestrator**, not a business-logic owner. To avoid turning it into an accidental monolith and to keep latency predictable:

- Gameplay commands are represented as **coarse-grained operations** (for example, “execute command for character in region X”) rather than many fine-grained calls.
- Game Session may issue a small, bounded number of synchronous gRPC calls per command (for example, a single call to Game Logic plus at most one read-model fetch). If a feature would require more than this, the design must introduce read models, projections, or caching instead of adding further fan-out.
- Game Logic Service owns deterministic mechanics (combat, movement, progression). Game Session is responsible for ordering, conflict resolution, and deciding when to invoke Logic and when to defer or drop commands based on tick and quota state.
- Horizontal scaling is based on **tenant + tick-region** sharding. Redis keys for **region-local coordination** (for example, `tick:{tenantRegionTag}:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`) must be designed so that all state needed for a tick region can be executed locally on a single Game Session shard. Gameplay session bindings are not region-hash-scoped; they are tenant/instance scoped (for example, `session:game:<tenantId>:<gameInstanceId>:<sessionId>`) and follow authentication/reconnection lifecycles rather than region epochs.
- Session front-end to lease-owner forwarding is itself a coarse-grained Game Session internal call. It must use fenced identity, preserve per-session ordering, and must not devolve into ad hoc fan-out from front-end pods directly to multiple gameplay-domain services.

New APIs and Redis keys should be reviewed with this orchestration model in mind: Game Session should be able to drive gameplay using a small number of deterministic calls and region-local Redis operations for each tick, rather than building deep, ad hoc call graphs at runtime.

### Authoritative Data Ownership (Examples)

The following examples illustrate where key concepts live; the full matrix remains canonical:

| Concept | Owning service | Notes |
| --- | --- | --- |
| Accounts, login credentials, JWT issuance | Account Service | Issues and validates JWTs; manages subscriptions and bans. |
| Characters, NPCs, items, inventories | Entity Management Service | Owns persistent entity state, inventories, and stats. |
| World topology (rooms, regions, maps) | World Management Service | Stores published room graphs, regions, and pathfinding metadata; Game Design Service is the design-time authoring tool and publishes topology versions into World Management. |
| Dynamic room state (doors, hazards, persistent environment flags) | World Management Service | Owns mutable room-environment state keyed by `RoomInstanceRef` (for example door open/closed flags, persistent hazards, and ambient flags) while remaining the source of truth for world topology and snapshots. Entity Management never stores these flags as entity state; it consumes them for rendering/visibility as needed. |
| Room occupancy (entities present in each room) | World Management Service | Owns authoritative character/NPC location and the derived occupancy view per room instance; other services consume occupancy via World Management APIs or projections rather than persisting their own competing occupancy indexes. |
| Game assets (published content and exported artifacts) | Game Design Service | Owns game asset publishing to the S3-compatible object store; other services and clients consume published assets via configured URLs rather than writing to the store directly. |
| Gameplay mechanics (combat, movement, progression) | Game Logic Service | Implements deterministic rules; no persistent ownership. |
| Live sessions, ticks, command queues | Game Session Service | Owns Redis-backed coordination for active gameplay. |
| Chat, groups, social graph | Social & Groups Service | Manages chat channels, guilds, friends/blocks. |
| Moderation events, admin dashboards | Logging & Admin Service | Aggregates logs/metrics/traces and powers moderation tooling. |

### Movement and Location Consistency Contract

To avoid drift between gameplay orchestration, entity state, and world occupancy, movement and location updates use one explicit write contract:

1. Game Session orchestrates the movement command under the tick timeline and supplies the idempotency/effect identity for downstream writes.
2. Game Logic computes deterministic movement/travel outcomes (valid destination, cost, and mechanics).
3. World Management performs the authoritative location/occupancy commit for the target room/region instance.
4. Entity Management applies entity-side state updates (stats/effects/inventory consequences) but does not maintain a competing authoritative occupancy index.
5. Retries/replays use the same effect identity and converge through idempotent handlers; no service may treat partial local updates as authoritative completion.

If a feature needs a different movement write order, it must be documented as a design change in tick + transactions docs before implementation.

### Moderation Policy Distribution and Enforcement Contract

Moderation behavior is split between policy ownership and enforcement points and must follow a single propagation contract:

1. Logging & Admin Service is the source of truth for moderation policy definitions and audit history.
2. Enforcement services (at minimum Game Session for gameplay bans and Social & Groups for chat mutes/bans) consume policy updates through versioned APIs/events and record the policy version used for each enforcement decision.
3. Enforcement caches must be bounded and invalidated by policy-version changes; enforcement on stale policy beyond the bounded window is an incident.
4. On policy-source or propagation outages, enforcement behavior must be explicit and fail-safe for high-risk actions (for example, deny message send or gameplay admission when required policy cannot be validated), while emitting clear operator-visible errors/metrics.
5. Cross-service moderation decisions must remain auditable end-to-end (policy version, actor, target, enforcement outcome, timestamp).

Service-specific APIs and TTL/eventing details belong in service docs, but any deviation from this contract is an architecture change.

### Ban and Moderation Taxonomy (Canonical)

To remove ambiguity around “bans,” FireMUD uses the following canonical taxonomy:

| Ban/Moderation Type | Policy Owner | Primary Enforcement Point | Scope |
| --- | --- | --- | --- |
| `account_security_ban` (for example compromised account, severe ToS account suspension) | Account Service | Account auth and token/session revocation surfaces | Account-wide across tenants |
| `gameplay_ban` (deny gameplay admission/actions for a tenant) | Logging & Admin Service | Game Session Service | Tenant gameplay scope |
| `chat_mute` / `chat_ban` | Logging & Admin Service | Social & Groups Service | Tenant chat and messaging scope |

Implementation notes:

- Account Service remains the sole writer for auth revocation watermarks and account-security lockout/ban state.
- Logging & Admin defines moderation policy and audit trails for gameplay/chat moderation; enforcement services consume that policy through the moderation propagation contract above.
- “Bans” in docs and APIs must name the specific taxonomy type above instead of using an unqualified `ban` term.

### Design-Time vs Runtime World Data

World and room data flows through two distinct phases:

- **Design-time authoring (Game Design Service):** Creators edit rooms, zones, and world graphs using the Game Design Service and its web-based tools. All edits are versioned, and draft configurations can be validated and tested in isolation.
- **Published runtime topology (World Management Service):** When a version is published, the Game Design Service performs a copy/publish step that materializes the topology and region layout into World Management as read-optimized, immutable structures (per game version). World Management owns this published topology and any derived navigation data such as navmeshes.

Game Session Service controls **which published version is active** per tenant and region. Game Design Service can request or schedule version changes, but activation ultimately happens via Game Session and runtime configuration flows (see [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)).

---

### Multi-Tenancy Enforcement

Multi-tenant isolation is enforced both at the data layer and at specific enforcement points in the runtime:

- **Entitlements and quotas source of truth** – Subscription entitlements and plan-driven quota values are owned by the Account Service (for example via `GetTenantEntitlements(tenantId)`). Operator overrides are surfaced and audited in Logging & Admin and represented as an overlay merged into the Account entitlement contract so enforcement points consume one canonical view.
- **Gateway enforcement (edge-safety)** – Spring Cloud Gateway enforces per-IP and per-connection request/handshake limits for HTTP and WebSocket traffic using Cache/Rate-Limit Redis and shared rate-limit helpers. For gameplay WebSockets, Gateway does not attempt to infer tenant identity from post-login traffic; tenant-aware limits are enforced by Game Session after `LOGIN` binds the session.
- **Game Session enforcement** – Game Session Service enforces per-tenant caps on active gameplay sessions and tick-region load, rejecting or deferring new logins when quotas are exceeded for a tenant or region.
- **Downstream services** – Where additional quotas are needed (for example, chat message volume in Social & Groups), services reuse the same quota configuration and Cache/Rate-Limit Redis helpers rather than introducing ad hoc mechanisms.

## Related Documentation

### Diagrams

- [System Architecture Diagram](./system-architecture-diagram.md)
- [System Context Diagram](./system-context-diagram.md)

### Infrastructure & Deployment

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Multi-Tenancy Architecture](./system-architecture-multi-tenancy.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)

### Runtime & Security

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Security Architecture](./system-architecture-security.md)
- [Testing Strategy](./system-architecture-testing.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)

### Gameplay & Tools

- [Frontend Architecture](./system-architecture-frontend.md)
- [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md)
- [Procedural Generation](./system-architecture-procedural-generation.md)
- [Scripting & Automation Framework](./system-architecture-scripting.md)

### Responsibilities

- [Microservices Responsibility Matrix](./service-responsibility-matrix.md)

### Service-to-Module Mapping

Each microservice described in this overview is implemented as a Gradle module under `services/`:

- Game Session Service → `:game-session-service` (path: `services/game-session-service`)
- Account Service → `:account-service` (path: `services/account-service`)
- World Management Service → `:world-management-service` (path: `services/world-management-service`)
- Entity Management Service → `:entity-management-service` (path: `services/entity-management-service`)
- Game Logic Service → `:game-logic-service` (path: `services/game-logic-service`)
- Game Design Service → `:game-design-service` (path: `services/game-design-service`)
- Automation & Scripting Service → `:automation-scripting-service` (path: `services/automation-scripting-service`)
- Social & Groups Service → `:social-groups-service` (path: `services/social-groups-service`)
- Logging & Admin Service → `:logging-admin-service` (path: `services/logging-admin-service`)
- Spring Cloud Gateway → `:spring-cloud-gateway` (path: `services/spring-cloud-gateway`)
- TCP Proxy Service → `:tcp-proxy-service` (path: `services/tcp-proxy-service`)
