# Game Session Service

## Overview

Orchestrates live game sessions, including tick execution, player input validation, and runtime feature toggles. Acts as the central hub for gameplay state.

### Terminology

- **Tenant** – a hosted game world or project, identified by `tenantId`. All database rows and Redis keys include this prefix so data is isolated between games.
- **Game instance** – a specific running instance of a tenant’s world, identified by a `gameInstanceId` in the database and runtime APIs as described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md#version-activation--rollback). Even if a deployment runs at most one instance per tenant, APIs and persistence models still carry `gameInstanceId` explicitly (for example using a stable default like `"primary"`) so multi-instance support does not require rewriting identifiers later.
- **Character identity** – gameplay identity keyed by `characterId`; legacy `playerId` fields are temporary aliases and map one-to-one to `characterId`.
- **Player gameplay session** – a single player’s live connection and gameplay context bound to a specific game instance and character identity. Gameplay sessions are stored in Redis under `session:game:<tenantId>:<gameInstanceId>:<sessionId>` and are purged when the session ends.
- **Region / region shard** – a subdivision of the world used for tick execution and scaling. Tick coordination keys are scoped per `<tenantId, regionId>` and do not follow individual player session lifecycles.

### Responsibilities

- Maintain session state and tick timing in Redis
- Persist Game Session control-plane metadata in PostgreSQL, including game-instance rows, pinned runtime-version/script-patch selections, active runtime feature-flag overrides, and operator/audit-relevant disconnect/remediation metadata
- Queue player commands and dispatch them to Game Logic Service
- Broadcast lifecycle events and world updates to other services
- Support reconnection and recovery of running games
- Own the authoritative, pinned `scriptPatchVersion` for each running game instance and enforce version fencing for script-generated work.
- Publish **coordination and tick health metrics** (per `<tenantId, regionId>`) and expose admin/control APIs that allow authorized services (such as Logging & Admin) to pause/resume tick execution and participate in scoped coordination resets.
- Front gameplay login commands and session binding, calling Account Service to verify credentials and obtain JWTs/tokens while enforcing single-session control for each character.
- Mint and attach short-lived internal `SessionAttestation` payloads on gameplay-service gRPC calls so downstream gameplay services can verify delegated player identity (`accountId`, `tenantId`, `gameInstanceId`, `characterId`, `sessionId`) in addition to mTLS caller identity.

## Architecture / Design Notes

- Coordinates with Redis to store volatile session state and command queues.
- Communicates with other microservices exclusively via gRPC.
- For gameplay-domain gRPC calls made on behalf of a player, includes a signed `SessionAttestation` (as defined in Authentication & Authorization) and rotates it on bounded TTL; downstream gameplay services must reject calls missing valid attestation even when mTLS is present.
- Communicates game lifecycle changes to other services via gRPC so they can react to games starting or ending.
- Provides a single point of truth for current tick and world time.
- Uses PostgreSQL for durable Game Session control-plane metadata and audit-relevant workflow state, while Redis remains the coordination plane for gameplay session bindings, tick queues, timers, retries, and region leases.
- Implements the gameplay layer’s **session front-end + lease-owner execution** model: connected sockets bind to a stable session front-end pod, while region-scoped tick execution remains fenced to the current `<tenantId, regionId>` lease owner. Session front-ends may forward work to lease owners over internal gRPC, but only lease owners may mutate region-scoped coordination state.
- Ensures atomic command execution using Redis Lua scripts for all multi-key operations; the service does not rely on Redis `MULTI`/`EXEC` for consistency. Tick-related multi-key operations (locks, pending state, queues, timers, retry metadata) are performed exclusively via the shared Lua scripts described in [Redis Architecture](../../system-architecture-redis.md#atomicity-and-concurrency-control); ad-hoc multi-key sequences against tick keys are not allowed outside these scripts.
- Treats Redis **Coordination** and **Cache/Rate-Limit** roles as separate concerns:
  - All tick, lock, timer, retry, and session coordination keys live on Coordination Redis and are accessed only via the Lua Script Registry and shared key builders.
  - Game Session code that runs inside the tick engine (tick scheduler, staging/commit flows, lease management) never reads or writes Cache/Rate-Limit Redis directly. Any cache lookups or invalidations (for example, room views or inventory aggregates) are encapsulated inside domain services, which remain responsible for correctness and treat caches as performance hints only.
- Crash recovery replays ticks stored in Redis using AOF persistence and the idempotent replay rules described in [Tick System and Runtime Design](../../system-architecture-ticks.md#crash-recovery-and-replay); replication remains asynchronous, and Redis is treated as a volatile coordination layer rather than a durable source of truth.
  When Redis becomes slow or unavailable, the Game Session Service applies the
  graceful degradation and halt behavior defined in
  [Redis Architecture – Graceful Degradation & Redis Outage Policy](../../system-architecture-redis.md#graceful-degradation--redis-outage-policy)
  instead of buffering authoritative commands only in memory. If coordination state must be cleared or repaired, operators follow the scoped reset flows in [Redis Operations & Migrations](../../system-architecture-redis-operations.md) rather than issuing ad-hoc key deletions.
  - Every gameplay session record includes a `tenantId` identifying the owning tenant (and, via associated tables, the `gameInstanceId` when multiple instances per tenant are supported). Redis keys and database tables prefix this value so sessions from different games remain isolated. The platform may enforce per-tenant resource quotas at this level so one tenant cannot exhaust cluster capacity.
  See the [Multi-Tenancy](../../system-architecture-multi-tenancy.md) document.
  Player session state for reconnect recovery lives in Redis using keys of the form
  `session:game:<tenantId>:<gameInstanceId>:<sessionId>` and is purged when the session ends. Region-scoped tick queues, locks and pending sets share this tenant-prefixed scheme but follow `<tenantId, regionId>` lifecycles rather than individual player sessions; see also [Session Keys and Gameplay Binding](../../system-architecture-redis.md#session-keys-and-gameplay-binding) and the coordination timeline `(region_epoch, tickId)` described in [Redis Coordination Invariants](../../system-architecture-redis.md#redis-coordination-invariants).
- Restores player sessions after disconnects and enforces single-session control as outlined in the Reconnection Strategy. For Telnet clients, the service also consumes best-effort, at-least-once `NotifyDisconnect` events emitted by the TCP Proxy Service over an internal gRPC link and treats them as idempotent hints keyed by `<proxyConnectionId, disconnectSequence>` rather than a guaranteed source of truth. Game Session persists the latest processed `disconnectSequence` per `<proxyConnectionId>` and ignores older or duplicate events so retry behaviour at the proxy can remain simple while consumption stays idempotent. When the Telnet client supplies a `SESSION <gameInstanceId> <tenantId>` envelope, that `<tenantId, gameInstanceId>` is carried as advisory context and may be used for logging/audit, but Game Session still validates any game-instance ownership claims against Redis and its authenticated session state.
- Certain operations such as game startup and shutdown are implemented as Sagas
  so that all dependent services remain in sync. See
  [Transaction Strategies](../../system-architecture-transactions.md).
- Saga workflows use the shared `SagaBuilder` and emit metrics with correlation
  IDs via `SagaRunner`.
- Delegates brute-force defense responsibilities to the Account Service, which monitors login attempts, applies per-IP/account throttling and blacklisting, and triggers notification emails as described in
  [Security Architecture](../../system-architecture-security.md#brute-force-defense-and-abuse-handling). Game Session relies on these signals when binding gameplay sessions but does not implement its own credential or abuse detection logic.
- Session objects are created as soon as a client connects. They remain unauthenticated until the Account Service verifies credentials and issues a token.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

### Redis Role and Prefixes

- **Coordination Redis ownership**
  - Owns **Coordination Redis** and all tick/coordination prefixes and Lua scripts registered in the shared script registry, including:
    - `tick:{tenantRegionTag}:queue:<entityId>`
    - `tick:{tenantRegionTag}:pending`
    - `tick:{tenantRegionTag}:lock:<entityId>`
    - `timer:{tenantRegionTag}`
    - `retry:{tenantRegionTag}`
    - `tick-executor-lease:{tenantRegionTag}`
    - `remote:<tenantId>:<entityId>` and other coordination keys listed in the prefix tables in the [Redis Cheat Sheet](../../system-architecture-redis-cheatsheet.md).
  - All multi-key coordination operations (ticks, timers, retries, locks, region leadership, and tick recovery flows) use registered Lua scripts that follow the determinism and idempotency rules in [FireMUD Redis Lua Patterns](../../system-architecture-redis-lua-patterns.md).
  - Coordination prefixes are treated as **reset-tolerant** in line with [Redis Reset & Recovery](../../system-architecture-redis-reset-and-recovery.md), except for reset-sensitive session bindings (`session:game:*`): incident runbooks may clear reset-tolerant prefixes per region/tenant/cluster without affecting authoritative PostgreSQL state. Region-scoped resets preserve `session:game:*` by default; tenant/cluster resets may invalidate sessions per the reset policy matrix. Designs must remain safe under the documented tail-loss envelope.
- **Cache/Rate-Limit Redis usage**
  - Does not use Cache/Rate-Limit Redis for gameplay-critical coordination; session state, tick queues, locks, timers, and retry metadata always live on Coordination Redis so they share the same AOF and reset semantics described in [Redis Architecture – Redis Availability, Consistency, and Safety Guarantees](../../system-architecture-redis.md#redis-availability-consistency-and-safety-guarantees).
  - Uses **Cache/Rate-Limit Redis** for read-side caches that help serve hot-path session views, most notably pre-rendered room LOOK aggregates under `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>` as defined in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md#cache-rate-limit-key-catalog).
  - `view:room-look:*` entries are treated as **Class B, TTL-only caches**:
    - They contain rendered room “view” payloads derived from World Management and Entity Management; PostgreSQL remains authoritative for world/entity state.
    - TTLs are configured to be short and bounded so stale views are naturally refreshed; occasional staleness is acceptable because gameplay correctness (combat resolution, movement, visibility rules) is enforced by tick logic and authoritative reads, not by the cache.
    - Updates to underlying world or entity state do not require synchronous invalidation of `view:room-look:*` keys; cache misses and TTL expiry trigger recomputation via fresh calls to World/Entity services.
    - Correctness-critical flows (combat, movement, visibility decisions) never read from `view:room-look:*`; they always rely on World Management and Entity Management APIs (and their own Class A caches) as the source of truth.
  - Game Session must not write cache prefixes to Coordination Redis or coordination prefixes to Cache/Rate-Limit Redis. Any new cache prefixes must be registered in the central cache catalog and documented here (including correctness class and invalidation strategy) before use.
  - Game Session does not read or write Social & Groups chat history caches (`chat:say:*`, `chat:tell:*`, `chat:guild:*`, `chat:account:*`) directly; those prefixes are owned and interpreted by the Social & Groups Service. Game Session interacts with chat history only via Social & Groups APIs.
- Changes to Redis usage in this service must follow the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) so prefixes, roles, slotting, and SLOs stay consistent.

#### Key Prefix Summary

Game Session uses the following Redis key prefixes; the **authoritative catalog** of coordination and cache prefixes, reset policies, and “behavior when dropped” lives in:

- [Redis Cheat Sheet](../../system-architecture-redis-cheatsheet.md)
- [Redis Reset & Recovery – Reset Policy Matrix](../../system-architecture-redis-reset-and-recovery.md#reset-policy-matrix-prefix-summary)
- [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)

| Key prefix | Role | Notes |
| --- | --- | --- |
| `session:game:<tenantId>:<gameInstanceId>:<sessionId>` | Coordination | Session state and reconnect metadata for gameplay sessions (instance-scoped). |
| `tick:{tenantRegionTag}:queue:<entityId>` | Coordination | Per-entity command queues within a tick region. |
| `tick:{tenantRegionTag}:pending` | Coordination | Single in-flight tick payload per region. |
| `tick:{tenantRegionTag}:lock:<entityId>` | Coordination | Entity locks during tick execution. |
| `timer:{tenantRegionTag}` | Coordination | Per-region timer ZSET. |
| `retry:{tenantRegionTag}` | Coordination | Retry queue for failed actions. |
| `tick-executor-lease:{tenantRegionTag}` | Coordination | Region leadership lease key. |
| `remote:<tenantId>:<entityId>` | Coordination | Best-effort hint marker for cross-region follow-ups; durable follow-up state lives in PostgreSQL via the tick effect ledger / follow-up tables. |

Service code must construct these keys via the shared key builders in `firemud-common` rather than manual string concatenation so `{tenantRegionTag}` and hash-tag discipline remain consistent with the central Redis architecture.

## Key Features

- **Session Lifecycle Management** — creates, resumes, and terminates player sessions.
- **Tick Orchestration** — drives the hybrid tick model for deterministic action processing.
- **Runtime Configuration** — stores runtime flag values created in the Game Design Service and activates published game versions.
- **Script Patch Awareness** — tracks an optional `script_patch_version` so live
  sessions can reload updated scripts without restarting.
- **Termination Handling** — cleans up resources and logs results when a game ends.
- **Instance Initialization** — starts new games from published templates.
- **Reconnection Handling** — resumes gameplay via Redis-backed session state as described in [Reconnection Strategy](../../system-architecture-reconnection.md).
- **State Queries** — exposes gRPC methods to retrieve current game or player state for the web UI.

For Telnet clients connected over **plaintext** TCP, the service also includes a
landing-menu security warning when the main FireMUD landing menu is rendered
before login. This warning is triggered whenever the connection metadata (propagated
from the TCP Proxy and Spring Cloud Gateway) indicates `transportSecurity=PLAINTEXT_TELNET`
and is suppressed for TLS Telnet and web clients. The exact text may evolve,
but a typical banner is:

> `WARNING: You are connected over **plaintext Telnet**. Your credentials and gameplay traffic may be visible on the network. For better security, please use the TLS Telnet port advertised by the server or the FireMUD web client instead.`

The warning appears alongside the landing menu on plaintext Telnet connections,
immediately before or as part of the pre-login output, so it is visible without
affecting normal gameplay flow.

### Data Model

- `game_instances` table tracks running sessions with columns `tenant_id`, `runtime_version`, optional `script_patch_version`, `owner_account_id`, `status` (`RUNNING` or `STOPPED`), and `created_at`.
- `feature_flag` table stores runtime configuration overrides per tenant.
- `game_manifest` table lists available runtime versions that can be started.
- Redis stores volatile queues, timers, and reconnect metadata.
- Redis session state records the active `script_patch_version` so it can be restored for replay or debugging.

### Tick Execution Model

- Each session advances in fixed-length ticks controlled by a Redis-based timer.
- Commands are collected during a tick and executed in deterministic order.
- The staging Lua script only moves a limited number of commands each tick
  (`GAME_TICK_MAX_COMMANDS`) so one player cannot starve others.
- Commands with `requiresSoloTick: true` are dequeued into an isolated tick so expensive operations like runtime procedural generation do not share time with normal actions.
- After execution, results are persisted and broadcast to connected clients.
- If a command cannot acquire its required entity lock(s), the executor does not spin; it fails the attempt, rolls back any staged changes, and reschedules the command with a bounded, tick-based backoff (for example exponential backoff capped by `MAX_BACKOFF_TICKS`), tracking a per-command retry counter and enforcing a `MAX_RETRIES` limit before surfacing a player-visible error and logging a permanent failure.

The Game Session Service acts as the **authoritative tick executor** for each `<tenantId, regionId>` it owns:

- It participates in region leadership using the Redis lease key `tick-executor-lease:{tenantRegionTag}` described in the [Redis Architecture](../../system-architecture-redis.md#region-leadership-and-tick-executor-lease).
- While it holds the lease for a region, it is the only instance allowed to:
  - Consume commands from that region’s queues and timers.
  - Drive `tick:{tenantRegionTag}:pending` and commit/rollback flow.
  - Issue tick-scoped gRPC calls on behalf of that region’s commands.
- On crash or deliberate handoff, another instance acquires the lease and resumes tick processing from Redis using the epoch-scoped `(regionEpoch, tickId)` timeline and EffectId/effect-guard rules from the Tick System design.
- The executor monitors `tick_execution_time_ms_p99` and `tick_lock_ttl_ms` for each region; when a region repeatedly produces over-TTL ticks according to the thresholds described in the Redis and Tick architecture docs, it marks that region as degraded, automatically reduces tick fan-out and/or slightly lengthens the tick interval for that region, emits explicit “region degraded” metrics, and, if the condition persists beyond a configured window, may halt new ticks and reject new commands for that region until operators intervene.
  These degraded and halt transitions follow the same thresholds and policies
  captured under
  [Redis Architecture – Operational SLOs & Alert Thresholds](../../system-architecture-redis.md#operational-slos--alert-thresholds)
  so operators and implementations share a single set of “red lines” for
  coordination health.

### Session Front-End and Lease-Owner Routing

Game Session deliberately separates **socket ownership** from **region execution ownership**:

- The pod holding a player's WebSocket or proxied Telnet bridge is the **session front-end** for that gameplay session.
- Region-scoped command execution belongs to the current **lease owner** for the target `<tenantId, regionId>`.
- Session front-ends may authenticate, normalize input, manage connection-local state, and stream results to the client.
- Session front-ends must not directly stage or commit tick-owned Redis mutations for regions they do not lease.
- When a command or follow-up targets a region owned by another pod, the session front-end forwards the request over internal gRPC to the lease owner and returns the resulting output to the client.

This model keeps `/ws/game/**` stable at the edge while allowing ordinary in-cluster lease rebalancing without forcing reconnects solely because a region moved.

#### Forwarding contract

The internal front-end to lease-owner path is a fenced gameplay contract, not a best-effort proxy hop:

- Forwarded requests include `tenantId`, `gameInstanceId`, `sessionId`, `characterId`, target `regionId`, command/action identifier, and a monotonic per-session sequencing token.
- Forwarded requests include the current region lease/epoch fence. Lease owners reject stale or missing fences with an application-level stale-lease response rather than silently executing.
- The session front-end preserves per-connection FIFO when emitting forwarded work. Cross-connection ordering remains undefined during takeovers as described in the reconnection and protocol-bridging docs.
- If the lease owner rejects a stale fence before execution, the front-end refreshes ownership and may retry the request once against the new lease owner when the request is still valid.
- If forwarding fails after the executor may already have started, the front-end must treat the result as ambiguous and use the normal structured command-failure or reconnect path; it must not re-issue potentially mutating work without an idempotency guarantee.
- All forwarded execution attempts and stale-lease rejections must emit dedicated metrics and traces so operators can distinguish edge socket health from region-executor health.

### gRPC APIs

- `Ping` – basic connectivity check.
- `StartSession` – spins up a game instance from a published version. Despite the name, this operates on **game instances**, not player gameplay sessions; gameplay sessions are per-player contexts backed by `session:game:*` keys.
- `StopSession` – stops a running game instance.
- `RestartSession` – restarts a stopped game instance.
- `EnqueueCommand` – adds a player action to the next tick's queue.
- `QueryState` – retrieves condensed session or player state for monitoring.
- `ToggleFeatureFlag` – updates runtime flags for a tenant.
- `PauseTicks` – temporarily halt tick execution before a backup.
- `ResumeTicks` – resume tick processing after the backup begins.
- `GetTickStatus` – returns `RUNNING` or `PAUSED` for backup orchestration.

## Dependencies

- **Internal:**
  - Entity Management Service, Game Logic Service, World Management Service.
  - Logging & Admin Service receives session lifecycle events.
- **External:** Redis for session state.
- gRPC clients discover endpoints via `ServiceEndpointsProperties` and secure
  connections with mTLS certificates issued by cert-manager.

> See [**Gateway Architecture**](../../system-architecture-gateway.md), [**Deployment Environments**](../../infrastructure/deployment-environments.md), and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

### Scaling and Region Rebalancing

- Region-to-instance mapping is flexible and driven by a scheduler or consistent-hashing layer that assigns `<tenantId, regionId>` values to Game Session instances.
- To scale out, operators add more Game Session pods and allow the scheduler to assign regions to new instances; each instance acquires leases for its assigned regions.
- To rebalance load, an instance can stop renewing the lease for selected regions and drain in-flight work to a safe point; other instances then acquire those leases and continue tick processing from the existing Redis state.
- Combined with region sizing (splitting hot regions and merging cold ones), this lease-based ownership model allows FireMUD to scale horizontally without global downtime.

## Dev-isolated Mode

- Use `./gradlew :game-session-service:bootRunDevIsolated` (or set `GAME_SESSION_DEV_ISOLATED=true`) when you need to exercise the Game Session Service without PostgreSQL, Redis, or downstream gRPC dependencies. The dev-isolated beans acknowledge commands and lifecycle requests while only recording informational logs instead of accessing external systems.
- The `DevIsolatedGameSessionSmokeTest` in `services/game-session-service/src/test/java/integration/net/firedevops/firemud/DevIsolatedGameSessionSmokeTest.java` starts the dev profile in dev-isolated mode, posts to `POST /sessions`, and asserts the request is accepted and logged, proving the fast-path smoke test that only touches in-memory components.
- The dev-isolated smoke/integration tests (`DevIsolatedGameSessionSmokeTest`, `GameSessionLoginIntegrationTest`, `GameSessionWebSocketHandlerIntegrationTest`, `SessionResumptionFlowTest`) are currently decorated with `@Disabled` so they only act as TODO reminders until the real Account/Redis/GameInstance wiring exists (see `design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md#7-dev-mode-stubs-and-real-service-rollout`).

## Environment Variables

This service follows the configuration scheme from
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It requires the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
variables.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `GAME_TICK_DURATION_MS` | Tick cadence (`tick_interval_ms`): target interval between ticks for a region | `1000` |
| `GAME_TICK_BUDGET_MS` | Tick work budget (`tick_budget_ms`): soft max work/lock-hold time per tick; must be ≤ ~0.8× `GAME_TICK_DURATION_MS` | Derived (for example `0.8 × GAME_TICK_DURATION_MS`, capped) |
| `GAME_SOLO_TICK_BUDGET_MS` | Execution budget for isolated solo ticks | `500` |
| `GAME_TICK_MAX_COMMANDS` | Max commands staged from the queue each tick | `50` |
| `FIREMUD_SERVICES_GAME_LOGIC_SERVICE` | gRPC endpoint (host:port) for the Game Logic Service | *(none)* |
| `FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE` | gRPC endpoint (host:port) for the World Management Service | `world-management-service:6565` |
| `FIREMUD_SERVICES_ENTITY_MANAGEMENT_SERVICE` | gRPC endpoint (host:port) for the Entity Management Service | `entity-management-service:6565` |
| `FIREMUD_CONFLICT_TTL_SECONDS` | TTL for conflict hotspot tracking in Redis | `300` |

## Proto Files

Service definitions reside in
[../../../../protos/game-session/v1](../../../../protos/game-session/v1). Run
`./gradlew generateProto` after modifying these files to regenerate stubs.
The generated classes appear under `net.firedevops.firemud.gamesession.v1` in `build/generated/sources/proto/main/{grpc,java}` and are wired into `services/game-session-service/src/main/java/net/firedevops/firemud/service/impl/GameSessionGrpcService.java` so the module compiles the gRPC contract directly when it is built.

## Related Documentation

- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md) — how game instances load published versions and runtime flags.
- [Reconnection Strategy](../../system-architecture-reconnection.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Tick System and Runtime Design](../../system-architecture-ticks.md)
- [Redis Architecture](../../system-architecture-redis.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

## Minimal Text Command Protocol

Telnet and WebSocket clients share a minimal line-based command protocol that powers the initial MVP gameplay set. Clients send ASCII lines terminated by `\n`; the first token is the command name (case-insensitive) and the rest of the line is command-specific arguments. Empty lines are ignored.

At the protocol level, commands are split into two groups:

- **System commands** – session and connectivity operations fully owned by the Game Session Service (for example, `LOGIN`, `LOGON`, `PING`, and simple state/introspection queries that do not touch gameplay rules). These commands are interpreted and completed entirely within this service.
- **Gameplay commands** – all other text commands that express in-world actions (for example, `LOOK`, `SAY`, `YELL`, `WHISPER`, movement, combat). Game Session validates the session and authorization, normalizes the input, and enqueues the action for Game Logic Service; it does not re-implement gameplay mechanics or business rules for these commands.

| Command | Purpose | Example |
| ------- | ------- | ------- |
| `LOGIN <username> <password> [otp]` | Authenticates a session and binds it to an account on credential-bearing transports; append an OTP when two-factor auth is enabled. First-party `/ws/game/**` may instead use bare `LOGIN` after bootstrap/connect-token validation. | `LOGIN demo@example.com swordfish 123456` |
| `LOGON <username> <password> [otp]` | Exact alias for `LOGIN`; Telnet users often prefer the shorter name when typing from prompts. | `LOGON demo@example.com swordfish` |
| `WORLDS` | Lists worlds the authenticated account can enter (numbered menu + stable world slug) from Account Service membership + entitlement state. | `WORLDS` |
| `CHARS <world>` | Lists characters for a world (`<world>` is a world slug or a menu index from `WORLDS`) from the authoritative character store, filtered to `{accountId, tenantId}` ownership. | `CHARS demo` |
| `PLAY <world> [character]` | Binds the authenticated connection to a world and character after `LOGIN`, enforcing tenant authorization and entitlements. `<world>` is a slug or menu index; `[character]` is optional name/index. Current flow always binds `gameInstanceId=\"primary\"` per tenant. | `PLAY demo 1` |
| `LOOK` | Requests the current room snapshot (name, descriptions, exits, and visible entities) aggregated from Game Logic plus World and Entity services. | `LOOK` |
| `SAY <text>` | Broadcasts chat text to everyone in the same room. | `SAY Hello travelers` |
| `YELL <text>` | Alias for `SAY` that is rendered with higher emphasis but still delivers to the current room. | `YELL Hear me, comrades` |
| `WHISPER <player> <text>` | Directed chat that points at a single nearby player while keeping the payload in the same format. | `WHISPER Sora The forge smells of brimstone` |

Chat commands emit a shared success payload so Telnet and WebSocket clients can render the same transcript. After a successful `SAY`, `YELL`, or `WHISPER` command the server responds with:

```text
OK SAY
Speaker: Emberline
Delivered-To: Emberline, Sora, Kobold Scout
Message: Hello travelers
```

`Speaker` annotations let clients highlight who originated the message while `Delivered-To` lists the recipients that observed the chat frame, mirroring the metadata exposed to both Telnet and WebSocket clients. The `Message` line echoes the trimmed text so transport implementations can prefer the structured metadata or stitched narratives, e.g., `Emberline says, "Hello travelers"` in-game view layers. In production gameplay, the `Delivered-To` list is scoped to recipients that are visible to the speaking player and may be further redacted or disabled behind feature flags; its primary purpose is to support deterministic tests and debugging so future features such as stealth or limited eavesdropping do not have to expose full recipient sets to every client.

Chat parsing enforces that `SAY` and `YELL` include at least one non-whitespace character and that `WHISPER` provides both an existing player identifier and the message text. Submitting an empty/whitespace-only payload or exceeding the configured message limit (currently 512 characters) yields `ERROR INVALID_ARGUMENT Message text must be 1-512 characters long`. A missing whisper target or text also returns `ERROR INVALID_ARGUMENT` with the same guidance so clients can keep their parsers simple.

This small command table defines the initial MVP gameplay command set delivered by the Telnet-to-gameplay vertical slice; it should stay intentionally minimal while the protocol and interpreter mature. `LOOK` is treated as a fully data-driven gameplay command: Game Session enforces authentication, forwards it to Game Logic, which fetches room metadata from World Management and visible entities from Entity Management before the response is rendered over Telnet or WebSocket.

### Login / Logon semantics

Telnet and WebSocket clients share this line-based syntax, but transport context determines which `LOGIN` form is valid. For Telnet and generic WebSocket clients, sending `LOGIN` (or the alias `LOGON`) with no arguments is intended to start the prompt flow, whereas `LOGIN <username> <password> [otp]` (or `LOGON ...`) performs an immediate authentication attempt. For first-party `/ws/game/**` sessions that already carry a validated Gateway connect context, bare `LOGIN` completes gameplay authentication from the pre-established bootstrap identity instead of prompting for or replaying credentials. OTP values on credential-bearing logins are passed through verbatim to the Account Service so two-factor accounts get the same experience. The same `OK <COMMAND>` / `ERROR <CODE> <message>` response format applies to all transports so clients can react consistently, and the examples below demonstrate at least one success and one failure path per transport.

**Note:** Prompt-based exchanges are planned but not implemented in this slice for Telnet and non-bootstrap clients. On those transports, bare `LOGIN` currently returns `ERROR PROMPT_LOGIN_UNSUPPORTED Prompt-based login is not implemented yet; send LOGIN <username> <password>.` First-party `/ws/game/**` sessions with a validated connect context are the exception: bare `LOGIN` consumes the bootstrap-backed context and must not ask the browser to resend credentials. Gameplay commands such as `LOOK` require a successful `PLAY` after `LOGIN`/`LOGON`; unauthenticated attempts still receive `ERROR NOT_AUTHENTICATED`, and the most recent successful room snapshot is cached per session so reconnecting clients can immediately redraw the world before pending commands replay.

After `LOGIN` succeeds, clients must issue `PLAY <world> [character]` before any gameplay commands (such as `LOOK` or `SAY`). This play step binds the authenticated connection to a world-scoped gameplay session and enforces tenant authorization and entitlements as defined in the Authentication & Authorization design.

For first-party `/ws/game/**` sessions, `PLAY` scope checks (`tenantId`, `gameInstanceId`) must use the gateway-signed connect context (`X-Firemud-Connect-Context`) validated by Game Session, not raw forwarded headers. Missing/invalid/expired/replayed context where connect-token validation was required must fail admission with `CONNECT_CONTEXT_INVALID`. Mismatched validated scope fails with `CONNECT_SCOPE_MISMATCH`.

Canonical first-party `PLAY` scope errors on `/ws/game/**`:

- `CONNECT_CONTEXT_INVALID` - required gateway-signed connect context is missing or failed validation (signature, expiry, replay, or key verification).
- `CONNECT_SCOPE_MISMATCH` - validated connect context does not match requested `{tenantId, gameInstanceId}` scope.

If a gameplay session already exists for the selected `{tenantId, gameInstanceId, characterId}` and is still resumable (TTL, current membership authority, and current revocation state are valid), `PLAY` resumes it and rebinds the new socket to the existing session. On successful resume, Game Session must also rebind the session to a fresh backend token for subsequent internal calls rather than depending on the previous token to remain valid. If no resumable session exists, `PLAY` creates a new gameplay session binding. This model allows the same account to have multiple characters in multiple worlds, but requires an explicit `PLAY` selection after every reconnect so the platform never guesses which tenant/character to resume.

If a client attempts gameplay commands before selecting a world, the service returns `ERROR WORLD_NOT_SELECTED Use WORLDS/PLAY first` (or the equivalent canonical code) so clients can recover deterministically.

The Account Service returns canonical `AUTH_*` error codes (`AUTH_INVALID_CREDENTIALS`, `AUTH_OTP_REQUIRED`, `AUTH_ACCOUNT_LOCKED`, `AUTH_UPSTREAM_FAILURE`), and the Game Session Service translates them into the protocol-level responses (`ERROR INVALID_CREDENTIALS`, `ERROR OTP_REQUIRED`, etc.) so Telnet and WebSocket clients can rely on stable error semantics while the human-readable message remains flexible.

Additional Game Session-specific login failures cover parsing and session-state issues before the Account Service call:

- `PROMPT_LOGIN_UNSUPPORTED` – prompt-based LOGIN/LOGON exchanges are planned but not implemented yet on non-bootstrap transports, so those clients must send `LOGIN <username> <password>`.
- `INVALID_ACCOUNT` – the Account Service returned an account identifier that could not be parsed into the expected format.
- `ACCOUNT_MISMATCH` – the authenticated account is not permitted to attach to the requested game instance or tenant context.
- `SESSION_NOT_FOUND` – the supplied game instance identifier has no corresponding `GameInstance`.
- `INVALID_ARGUMENT` – session ID parsing or other validation failed before the handler reached gameplay state.

Telnet success (prompt-based):

```text
LOGIN
OK LOGIN Enter username:
demo@example.com
OK LOGIN Enter password:
swordfish
OK LOGIN Logged in as demo@example.com

WORLDS
OK WORLDS
1) Demo World (demo)

PLAY demo
OK PLAY Entered world: Demo World
```

The transcript above presents the planned prompt flow. In the current implementation the same exchange is represented by a single `LOGIN <username> <password>` call because the prompt-driven handler returns `ERROR PROMPT_LOGIN_UNSUPPORTED ...`.

Telnet failure (wrong password):

```text
LOGIN demo@example.com wrongpass
ERROR INVALID_CREDENTIALS Invalid username or password
```

WebSocket success (parameterized command with optional OTP omitted):

```text
LOGIN demo@example.com swordfish
OK LOGIN Logged in as demo@example.com

WORLDS
OK WORLDS
1) Demo World (demo)

PLAY demo
OK PLAY Entered world: Demo World
```

WebSocket failure (account locked):

```text
LOGIN demo@example.com swordfish
ERROR ACCOUNT_LOCKED Account locked after repeated failures
```

### LOOK transcripts

Telnet `LOOK` (after `PLAY`):

```text
PLAY demo
OK PLAY Entered world: Demo World

LOOK
OK LOOK
Room: Candle-lit Antechamber (Room Instance ID: R-1021)
Short: You stand in a basalt chamber warmed by the brazier near the western wall.
Long: Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.
Exits: NORTH (arched passage leading toward the cavern mouth), EAST (narrow fissure descending toward the forges).
Entities:
- NPC "Kobold Scout" (alert, checking the eastern balustrade)
- Player "Sora" (leaning against the southern pillar)
```

WebSocket `LOOK` (same authenticated player, different transport):

```text
PLAY demo
OK PLAY Entered world: Demo World

LOOK
OK LOOK
Room: Crafting Hall of Ember (Room Instance ID: R-2045)
Short: A vaulted hall lined with anvils and hanging banners.
Long: Sparks drift upward from the forges while metalworkers shout over the rhythm of hammers; the far wall is dominated by the etched sigil of the Ember Guild.
Exits: SOUTH (wide stair toward the guild atrium), WEST (narrow corridor past the glazing ovens).
Entities:
- NPC "Master Smith Torga" (wiping soot from his shoulders)
- Player "Sora" (now near the south stair, waving to a passing engineer)
```

### Command interpretation and immediate vs queued behavior

The `TextCommandInterpreter` returns a result that includes both enqueue metadata (for the tick/command queue) and optional immediate response text. This shape is intended to remain stable as the implementation shifts from hard-coded handlers to data-driven gameplay logic, but the following rules apply:

- **System commands** (such as `LOGIN`, `LOGON`, `PING`, and lightweight state queries) are allowed to produce synchronous responses without enqueuing any gameplay actions. Their side effects are limited to session binding, health checks, or read-only projections.
- **Gameplay commands** (such as `LOOK`, `SAY`, movement, combat) are treated as tick-driven actions. Game Session validates the session, authorizes the command, and emits enqueue metadata; any immediate response is strictly informational (for example, echoing normalized text) and must not perform gameplay state changes outside the tick executor.
- If the interpreter produces both immediate text and enqueue metadata and the enqueue step fails (for example, a Redis outage), Game Session surfaces a single `ERROR` response for the command and logs the failure; it does not report success and then silently drop the enqueued action. Commands are designed to be idempotent with respect to retries at the queue level, so a successfully enqueued action may be retried by the tick executor without requiring the client to resend the original text.

For the current Telnet-to-gameplay vertical slice, the implementation intentionally separates "system" commands (session and login related) from gameplay commands:

- `LOGIN` / `LOGON` are treated as system commands owned by the Game Session Service and will be wired into the authentication and world-selection flow described in [Authentication & Authorization](../../system-architecture-authentication.md). At this stage they are defined in the protocol and parser, but the full login flow is still being implemented under `design/project-management/task-list-game-session-service.md`.
- `LOOK` is implemented through the Game Logic Service's data-driven resolver (`ResolveLook`), which orchestrates room snapshots from World Management and visible entities from Entity Management; Game Session formats the returned `LookResult`, caches the last successful snapshot per session, and streams it back to Telnet and WebSocket clients so the gameplay flow remains deterministic while drawing from the shared world state.
- `SAY` and additional gameplay commands follow the same pattern: they are part of the shared text protocol, but their long-term behavior is provided by soft-coded definitions and the Game Logic/World services rather than hard-coded handlers in this service.

### SAY request flow

1. Game Session validates the same Redis-backed session context leveraged by `LOOK`; unauthenticated inputs are rejected with `ERROR NOT_AUTHENTICATED` before any gameplay command reaches the interpreter.
2. Authenticated `SAY`/`YELL`/`WHISPER` commands are routed through `SayCommandHandler`, which packages `tenantId`, `gameInstanceId`, `sessionId`, `playerId`, `roomInstanceId` (a `RoomInstanceRef`), normalized text, and alias metadata into a `BroadcastSay` gRPC request to Game Logic.
3. Game Logic evaluates room visibility, enforces message constraints, and forwards the payload (or a stubbed notification) to the Social & Groups Service for delivery and logging. Upon success it returns the deterministic recipient list, which Game Session uses to render the canonical `OK SAY` response and emit `gamesession.command.say.invocations`/`failures` instrumentation.
4. Backend failures (e.g., delivery blocked, Social service unavailable) propagate protocol-mapped errors such as `ERROR SAY_NOT_DELIVERED` while `ERROR NOT_AUTHENTICATED` remains the consistent pre-flight guard for untrusted requests.

### Chat slice status

- **Live:** `SAY`/`YELL`/`WHISPER` commands now route through `SayCommandHandler`, which enforces the shared session guard, forwards normalized payloads to Game Logic's `BroadcastSay`, and renders the canonical `OK SAY` transcript while emitting the `gamesession.command.say.*` meters documented in `../../../project-management/look-instrumentation.md`.
- **Stubbed:** Delivery relies on the Social & Groups Service stub used by the regression suites, which currently records webhook contexts and returns success so both Telnet and WebSocket regression runs observe deterministic `Delivered-To` lists (see `SayWebSocketCrossServiceTest` and `TelnetGatewayGameSessionAccountCrossServiceIntegrationTest`).
- **Deferred:** Future slices will enrich the Social backend with NPC roleplay responses, listening-area heuristics, and localized channel filters once the core `BroadcastSay` path proves stable and well-instrumented.

### LOOK slice status

- **Live:** Data-driven `LOOK` flows now route through Game Logic's `ResolveLook`; Game Session renders the canonical text, caches the last snapshot per session, and emits the instrumentation metrics/logs documented in `../../../project-management/look-instrumentation.md` before replying over Telnet or WebSocket.
- **Stubbed:** Room/exit metadata and visible entities still derive from the seeded demo world migration and the `firemud.look.rooms` fixtures so transcripts and regression tests stay stable while the cross-service WebSocket and Telnet flows rely on the shared stub utilities.
- **Deferred:** Dynamic lighting, line-of-sight filtering, script-driven room prose, and the optional reconnection replay of cached snapshots remain future work once instrumentation, metrics, and cross-service regression coverage stabilize.

### LOOK request flow

1. Game Session validates the Redis-backed session context created by a successful `LOGIN`/`LOGON`. If the guard fails, the service immediately returns `ERROR NOT_AUTHENTICATED`.
2. Authenticated `LOOK` commands call Game Logic's `ResolveLook`, passing `tenantId`, `gameInstanceId`, `sessionId`, `playerId`, and `roomInstanceId` (a `RoomInstanceRef`). ResolveLook enforces visibility rules, fetches room snapshots from World Management, fetches visible-entity lists from Entity Management, and returns a single `LookResult` with stable snapshot identifiers for caching.
3. Game Logic returns a structured `LookResult` (name, short/long descriptions, exits, visible entities, optional highlights), which Game Session renders into the `OK LOOK` text response, emits metrics/logs (`gamesession.command.look.*`), and caches the serialized snapshot per session so reconnections can replay it quickly.
4. Reconnecting Telnet or WebSocket clients receive the cached snapshot before buffered commands replay. If the snapshot is missing or stale, Game Session reruns `ResolveLook`, so the projection stays consistent when the world changes while the player was offline.

### LOOK error mapping & metrics

`LOOK` commands now translate Game Logic failures into protocol errors so clients see consistent responses:

- `ERROR ROOM_NOT_FOUND` (room-level missing)
- `ERROR WORLD_UNAVAILABLE` / `ERROR ENTITY_UNAVAILABLE` when downstream gRPC targets refuse the call (the error description includes the service name).
- `ERROR LOOK_UNAVAILABLE` for generic infrastructure issues and `ERROR UNEXPECTED` for server-side bugs.

Metrics `gamesession.command.look.invocations` and `gamesession.command.look.failures` are tagged with `tenantId` and (when applicable) `error`, allowing operators to match client-visible failures with the underlying reason quickly.

### Response format

- Every response is plain text. The first line is either `OK <COMMAND>` or `ERROR <CODE> <message>`.
- Success responses may include additional lines describing the outcome. A blank line terminates the response block so multiple responses can be streamed back-to-back without ambiguity.
- Asynchronous world events (such as other players talking) use the same rules but are prefixed with `EVENT <TYPE>` to distinguish them from direct command responses.
- Unknown commands return `ERROR UNKNOWN_COMMAND <rawLine>`.

Examples:

```text
LOGIN demo@example.com swordfish
OK LOGIN Logged in as demo@example.com

PLAY demo
OK PLAY Entered world: Demo World

LOOK
OK LOOK
Room: Candle-lit Antechamber (Room Instance ID: R-1021)
Short: You stand in a basalt chamber warmed by a single brazier.
Long: Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels.
Exits: NORTH (arched passage toward the cavern mouth), EAST (narrow fissure descending toward the forges).
Entities:
- NPC "Kobold Scout" (alert, leaning on the eastern balustrade)
- Player "Sora" (half-hidden in the shadowed niche)

SAY Hello travelers
OK SAY
You say: Hello travelers
EVENT SAY
A kobold says: Stay sharp.

DANCE
ERROR UNKNOWN_COMMAND DANCE
```

## Additional Details

### Configuration

Environment variables configure the PostgreSQL and Redis connections via `DatabaseAutoConfiguration` and `RedisProperties`. Refer to [Deployment Environments](../../infrastructure/deployment-environments.md) for details. The `.env.sample` file contains example values.

The service enforces multi-tenant isolation. All tables include a `tenant_id` column and Redis keys are prefixed with this value as outlined in the [Multi-Tenancy design](../../system-architecture-multi-tenancy.md).

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /sessions` – create a new game session from a published version.
- `POST /sessions/{id}/stop` – stop a running session.
- `POST /sessions/{id}/restart` – restart a stopped session.
- `POST /sessions/{id}/refresh-roles` – refresh the player's roles for an active session.

Use `/sessions/{id}/refresh-roles` after updating an account's privileges so the
session reflects the latest role assignments.

```bash
curl http://localhost:8080/ping
```

To start a session via REST:

```bash
curl -X POST http://localhost:8080/sessions \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"demo","runtimeVersion":"v42","scriptPatchVersion":"v42-script.3"}'
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_session_service.proto`](../../../../protos/game-session/v1/game_session_service.proto).
- `StartSession(StartSessionRequest) returns (StartSessionResponse)` – creates a new game instance.
- `StopSession(StopSessionRequest) returns (StopSessionResponse)` – stops a running session.
- `RestartSession(RestartSessionRequest) returns (RestartSessionResponse)` – restarts a stopped session.
- `EnqueueCommand(EnqueueCommandRequest) returns (EnqueueCommandResponse)` – queues a player action.
- `QueryState(QueryStateRequest) returns (QueryStateResponse)` – retrieves current game or player state.
- `ToggleFeatureFlag(ToggleFeatureFlagRequest) returns (ToggleFeatureFlagResponse)` – updates runtime flags for a tenant.

```bash
grpcurl -plaintext localhost:6565 game_session.v1.GameSessionService/Ping
```

Start a session via gRPC:

```bash
grpcurl -plaintext -d '{"tenantId":"demo","runtimeVersion":"v42","scriptPatchVersion":"v42-script.3"}' \
  localhost:6565 game_session.v1.GameSessionService/StartSession
```

### Additional Notes

- Metrics emitted by this service feed the operator [Analytics Dashboards](../logging-admin-service/analytics-dashboards.md). Prometheus scrapes metrics from `/actuator/prometheus`.
- Logs and metrics include a `script_patch_version` label so operators know which
  hotfix revision is active.

### Script Patch Version Pinning and Rollback

Each running game instance has a pinned `scriptPatchVersion` alongside its `runtimeVersion`:

- Event ingress to the Automation & Scripting Service includes the currently pinned `scriptPatchVersion` so script evaluation is tied to the active patch for the instance.
- Script-generated commands accepted from the Automation & Scripting Service must carry the originating `scriptPatchVersion`, `scriptId`, and `scriptEventId`.
- On execution, Game Session enforces a version fence: if a queued command’s `scriptPatchVersion` does not match the instance’s currently pinned value, it must not be executed and the drop must be observable for operators.

Control-plane operations that change the pinned patch (used by Logging & Admin tooling) are admin-only and idempotent. Their required request/response fields and the associated event contracts are specified in `design/architecture/system-architecture-scripting-control-plane-api.md` and are represented in `protos/game-session/v1/game_session_service.proto` under `GameSessionControlPlaneService`.

For cross-service invariants, see `design/architecture/system-architecture-scripting-contracts.md`.

### Runtime Feature Flags

Feature flags are stored in the `feature_flag` table and can be toggled through the Logging & Admin Service. The Game Session Service exposes a gRPC `ToggleFeatureFlag` method so administrators can enable or disable experimental behavior without restarting a session. See [Game Design Service Feature Flags](../game-design-service/feature-flags.md) for how definitions are created and published.

### Saga Participation

Game startup and shutdown are coordinated using the shared `Saga` helpers from `firemud-common`. Each dependent service (World Management, Entity Management and Game Logic) confirms its part of the workflow before the session becomes active. Failures trigger compensating steps, ensuring consistent rollbacks. See [Transaction Strategies](../../system-architecture-transactions.md) for background.

### Redis Keys

Session state needed for reconnect recovery is stored under `session:game:<tenantId>:<gameInstanceId>:<sessionId>`. These **per-session** keys (including any session-scoped command queues or metadata) are removed when the corresponding session stops or expires.

Gameplay session bindings must include the server-side auth token identity used for backend calls on behalf of the session (for example `authTokenHash` and `authTokenIssuedAt`) plus authoritative membership freshness metadata (for example `membershipVersion`) so resume logic can validate current identity, current membership authority, and current revocation state before rebinding to a fresh backend token:

- Current caller identity matches the stored gameplay binding subject,
- Membership authority still allows gameplay admission for the tenant, and
- Bulk revocation watermarks (`session:auth:revoked_after:*`) do not block the account or tenant

as defined in `design/architecture/system-architecture-authentication.md#session-and-identity-management`.

Tick coordination is **region-scoped**, not session-scoped. Tick queues, locks, timers, retry metadata, and the `tick:{tenantRegionTag}:pending` key use the `tick:{tenantRegionTag}:...` prefix described in the [Redis Architecture](../../system-architecture-redis.md#tick-integration-resilience-locking-staging). Region keys follow region lifecycle and crash-recovery rules:

- `tick:{tenantRegionTag}:pending` is created **without a TTL** so it survives process crashes and failover.
- It is cleared only when the tick is successfully committed or an operator-driven recovery flow explicitly marks the tick as skipped/failed and removes the key.

Session shutdown therefore cleans up **session** keys, but **does not** implicitly delete region-level tick coordination keys.

- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [System Architecture Overview](../../system-architecture-overview.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Publish and Start a Game Instance](../../user-journeys-creators.md#4-publish-and-start-a-game-instance)
- [User Journeys – Player Login and Gameplay](../../user-journeys-players.md#3-player-login-and-gameplay)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

### Cross-Service Integration Test

An integration test under `src/test/java/crossservice` starts this service
alongside the Game Logic Service using **Testcontainers**. Run it manually once
the dependent Docker images are built:

```bash
./gradlew :game-session-service:test --tests "*CrossServiceIntegrationTest"
```

See [System Architecture Testing](../../system-architecture-testing.md) for more
details.

## Additional Features

- Built-in analytics for player behavior.

### Multi-Cluster Sharding (Out of Scope)

The core FireMUD architecture assumes a single Kubernetes cluster per deployment, with horizontal scaling achieved via **tick-region leasing** and executor rebalancing inside the Game Session layer (see **Scaling and Region Rebalancing** earlier in this document, plus `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md` for the edge scope decision and `design/architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md` for multi-cluster adoption scope).

If multi-cluster gameplay sharding is introduced in the future, it must be captured as a dedicated design update (routing-key transport, trust model, reconnection/backoff policy) and must not conflict with the current edge contract (no client-visible shard handoff signal; close-and-reconnect remains the default).

### Gameplay Analytics

The service emits Prometheus metrics for tick timing, queue lengths and command
latency. Logs include the `tenantId` and `traceId` fields so operators can build
dashboards in the Logging & Admin Service. These metrics feed the default
[Analytics Dashboards](../logging-admin-service/analytics-dashboards.md)
to monitor game health and player activity.
