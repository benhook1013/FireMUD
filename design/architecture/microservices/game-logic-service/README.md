# Game Logic Service

## Overview

Executes the core gameplay rules and command parsing. It processes player actions and determines outcomes.

### Responsibilities

- Parse player commands and resolve actions
- Apply combat rules, cooldowns, and environmental effects
- Compute movement/travel costs and pathfinding using world geometry
- Interact with entity and world services for context data
- Push results back to the Game Session Service for distribution
- Forward chat actions to the Social & Groups Service for delivery and
  profanity checks after verifying room context via the World Management
  Service and character state via the Entity Management Service
- See the [Service Responsibility Matrix](../../service-responsibility-matrix.md)
  for how this service fits into the overall architecture.

## Architecture / Design Notes

- Stateless service accessed over gRPC by other microservices.
- Uses a modular command parser for extensibility. The text protocol’s **system commands** (such as `LOGIN`, `LOGON`, and `PING`) are interpreted and completed by the Game Session Service; this service focuses on **gameplay commands** only, as described in the [Game Session Service](../game-session-service/README.md#minimal-text-command-protocol) documentation.
- Deterministic rule execution; random seeds come from the Game Session Service.
- Fetches contextual world and entity data on demand via gRPC.
- Gameplay rules are read from this service's own versioned data when a version
  is activated; the runtime service does not query design or admin databases.
- Integrates with the tick system described in [Tick System and Runtime Design](../../system-architecture-ticks.md) to ensure deterministic command ordering.
- Cross-service combat or trade operations run within ticks and rely on Redis-based rollback, not sagas. See [Transaction Strategies](../../system-architecture-transactions.md).
- All commands are scoped by `tenantId` so that rules execute only against data
  for the active game instance. The Game Session Service passes this context on
  every request. See [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Gameplay gRPC requests do not include JWTs. The Game Session Service provides
  player identity from Redis via `SessionContext`. It may refresh a JWT from the
  Account Service if roles change but does not validate tokens for gameplay.
  Communications use mutual TLS certificates as outlined in the
  [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- Flyway is enabled for consistency with other services, but the initial migration is empty because no tables are required.

### Saga Participation

The Game Logic Service does not orchestrate or own any Saga workflows. All
gameplay commands execute inside ticks using Redis-based rollback and the
transaction model described in [Transaction Strategies](../../system-architecture-transactions.md).
When a game version is published, its rule data is prepared and finalized by
the Game Design and Game Session services; this service simply reads the
already-published, versioned rule data for the active `runtime_version` and
does not participate directly in the publish Saga.

### Redis Role and Prefixes

- **Coordination Redis**
  - This service does **not** access Coordination Redis directly. It never issues commands against `tick:*`, `timer:*`, `retry:*`, `session:*`, or other coordination prefixes; all tick scheduling, locking, and staging live in the Game Session Service and its Lua registry as described in [Redis Architecture](../../system-architecture-redis.md).
  - Tick context is provided by Game Session via gRPC (for example, `tickId`, region metadata, and effect-guard identifiers) rather than by reading Redis state.
- **Cache/Rate-Limit Redis**
  - The Game Logic Service does not maintain its own Redis-backed caches today; any future read-side caches for rules or computed aggregates must use **Cache/Rate-Limit Redis** and the key naming/TTL/versioning patterns in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md), never Coordination Redis.
  - Game Logic does not read or write shared cache prefixes owned by other services (for example `view:room-look:*`, `inventory:*`, `character-cache:*`, or `chat:*`) directly; it treats World Management, Entity Management, and Social & Groups as the owners of those aggregates and accesses them via their gRPC APIs. Correctness-critical flows (combat, visibility, movement, chat delivery decisions) are always driven from authoritative service APIs and Class A caches, not from TTL-only caches such as `view:room-look:*`. This matches the restrictions documented for `view:room-look:*` in the central Redis cache design: Game Session is the sole writer for that prefix, and Game Logic consumes LOOK results only via gRPC.
- Any future Redis usage in this service should adhere to the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md), including prefix registration, role selection, and slotting rules.

## Key Features

- Command parsing and alias system.
- Rule processing for combat and progression.
- Emote and roleplay action handling.
- In-game chat processing for say, tell, guild chat, and mail actions,
  leveraging World Management and Entity Management for context before
  delegating delivery and logging to the Social & Groups Service.
- Event dispatcher for triggers and world events.
- Effect stacking and cooldown calculation.
- Environmental effect resolution (weather, lighting) influencing gameplay.
- Economy logic for trading, shops, and pricing adjustments.
- Procedural generation commands such as generate-dungeon are executed in
  solo ticks, coordinated by the Game Session Service and handled by the
  Automation & Scripting Service to avoid impacting other players.
- Scripting hooks let creators inject custom actions into the command engine.
- Optimized rule evaluation supports large-scale battles.

### LOOK aggregation & formatting

- `ResolveLook` orchestrates World Management and Entity Management: World provides room topology and ambient world state, while Entity provides the live entities and items (including room/ground inventory from room-ground container entities belonging to that room/instance) to build a deterministic `LookResult` that Game Session renders for clients.
- A dedicated `LookResultRenderer` keeps the canonical textual output in sync with the documented protocol transcripts (room name/desc/exits/entities) so the service can log or inspect the text while keeping the structured DTO clean.
- Downstream errors from World or Entity services are labeled (`WorldManagement`, `EntityManagement`) so they surface as precise error codes (`ROOM_NOT_FOUND`, `WORLD_UNAVAILABLE`, `ENTITY_UNAVAILABLE`) when Game Session formats replies for Telnet and WebSocket clients.

### Implementation status (LOOK slice)

- **Live:** The data-driven `LOOK` path is wired into the command pipeline via `ResolveLook`; it orchestrates World Management snapshots and Entity Management listings, hands the structured `LookResult` to the `LookResultRenderer`, and publishes the telemetry described in `../../../project-management/look-instrumentation.md`.
- **Stubbed:** Room and entity context still comes from the seeded demo world and entity fixtures so the canonical transcript remains deterministic; scripted descriptions, complex lighting, and dynamic hazard cues are not yet integrated.
- **Deferred:** Future slices will expand the renderer with richer prose, annotate `LookResult` with combat/effect metadata, and surface additional visibility hints once the core text shape proves stable.

### Implementation status (chat slice)

- **Live:** `BroadcastSay` accepts authenticated `SAY`/`YELL`/`WHISPER` payloads, validates length, aggregates recipient/NPC metadata, and forwards the normalized message to the Social & Groups Service stub. The API returns delivery metadata and `shared.v1.ErrorDetail` codes so Game Session can render the canonical transcript and surface `gamesession.command.say.*` instrumentation.
- **Stubbed:** Delivery currently uses the regression stubbed Social & Groups Service that records `SendMessage` calls and echoes success while the cross-service WebSocket and Telnet tests assert the structured response before adding a narrative layer for listeners.
- **Deferred:** Richer behavior (NPC roleplay replies, localized listening areas, channel filters, profanity escalation) will arrive in later slices once the foundational flow proves stable and the instrumentation captures both success and failure paths.

### SAY broadcast flow

- Game Session channels authenticated commands through `BroadcastSay`, supplying the same `RoomInstanceRef` context (`tenantId`, `gameInstanceId`, `roomInstanceId`) that guards `LOOK`. The command parser normalizes `SAY`/`YELL`/`WHISPER` aliases before forwarding trimmed text so downstream services can enforce consistent validation.
- Game Logic validates message length/whitelist checks, determines the occupied room, and delegates delivery (currently via a stubbed Social & Groups Service hook) rather than rendering the chat locally. The resulting delivery metadata (recipient list, NPC echoes) is returned to Game Session while failures populate `shared.v1.ErrorDetail` so TextCommandInterpreter can emit `ERROR SAY_NOT_DELIVERED` or similar protocol responses.
- This pathway mirrors the `LOOK` guard: unauthenticated requests never reach BroadcastSay, and any Social/Group service outage is surfaced as a structured `PERMISSION_DENIED`/`UNAVAILABLE` error so Game Session can keep its `ERROR NOT_AUTHENTICATED` gating predictable for Telnet and WebSocket clients.

### Data Model

This service is largely stateless. It relies on:

- Contextual entity and world data fetched from other services via gRPC.
- Temporary command queues stored in Redis by the Game Session Service.

### Command Flow

1. Commands are queued in Redis by the Game Session Service.
2. This service fetches the next command, loads the required context, and
   resolves the action to a rule engine module.
3. Results are pushed back to the session queue for delivery to players.

### gRPC APIs

- `Ping` – basic connectivity check.
- `ExecuteCommand` – evaluates a parsed command and returns the outcome.
- `BroadcastSay` – accepts `tenant_id`, `session_id`, `player_id`, and a `RoomInstanceRef` (`tenant_id`, `game_instance_id`, `room_instance_id`), plus normalized `text` and an alias indicator (`SAY`/`YELL`/`WHISPER`). The handler validates length, enforces room chat controls, and returns delivery metadata (recipient identifiers, NPC echoes, optional acknowledgements) along with structured status codes so Game Session can render the canonical response. Failures populate `shared.v1.ErrorDetail` while the gRPC status remains `OK`, keeping `gamesession.command.say.*` metrics aligned with the existing instrumentation.
- All responses include a `shared.v1.ErrorDetail` field for standardized error handling.
  Application errors are returned in this field while the gRPC status remains
  `OK`, and a `grpc.app_error` metric is recorded with the error code.

## Dependencies

- **Internal:**
  - Entity Management Service for characters and items.
  - World Management Service for room and region data.
  - Game Session Service supplies tick context and command queues.
  - Automation & Scripting Service triggers additional effects during rule execution.
  - Social & Groups Service handles chat delivery and profanity filtering.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

This service follows the conventions in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
Unlike other services, it does not connect to PostgreSQL or Redis at runtime;
those credentials are present in the shared `.env` file only for consistency.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates).
Peer services are discovered using variables prefixed `FIREMUD_SERVICES_`, and the implementation consumes them for gRPC endpoint resolution.
The gRPC server listens on port `6565` by default as configured in `application.yml`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

Additional variables referencing dependent services:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_SERVICES_ENTITY_MANAGEMENT_SERVICE` | gRPC endpoint (host:port) for the Entity Management Service | *(none)* |
| `FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE` | gRPC endpoint for the World Management Service | *(none)* |
| `FIREMUD_SERVICES_GAME_SESSION_SERVICE` | gRPC endpoint for the Game Session Service | *(none)* |
| `FIREMUD_SERVICES_AUTOMATION_SCRIPTING_SERVICE` | gRPC endpoint for the Automation & Scripting Service | *(none)* |
| `FIREMUD_SERVICES_SOCIAL_GROUPS_SERVICE` | gRPC endpoint for the Social & Groups Service | *(none)* |

## Proto Files

gRPC service definitions can be found in
[../../../../protos/game-logic/v1](../../../../protos/game-logic/v1). Rebuild
the generated code with `./gradlew generateProto` after making changes.

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Tick System and Runtime Design](../../system-architecture-ticks.md)
- [Redis Architecture](../../system-architecture-redis.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Security Architecture](../../system-architecture-security.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [User Journeys – Player Login and Gameplay](../../user-journeys-players.md#3-player-login-and-gameplay)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

## Additional Details

### REST & gRPC Endpoints

#### REST

- `GET /ping` – returns `ApiResponse` with the string `pong` in `data`.
- `POST /command` – submit a gameplay command body as plain text and receive an `ApiResponse<String>` result.
These are the only REST endpoints; gameplay commands are primarily processed through the gRPC interface.

```bash
curl http://localhost:8080/ping
```

Expected response:

```json
{
  "status": "SUCCESS",
  "data": "pong",
  "error": null
}
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_logic_service.proto`](../../../../protos/game-logic/v1/game_logic_service.proto).
- `ExecuteCommand(ExecuteCommandRequest) returns (ExecuteCommandResponse)` – process a command and return the result.

```bash
grpcurl -plaintext localhost:6565 game_logic.v1.GameLogicService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

Call `ExecuteCommand` with:

```bash
grpcurl -plaintext -d '{"tenant_id":"demo","session_id":"demo","command":"look"}' \
  localhost:6565 game_logic.v1.GameLogicService/ExecuteCommand
```

- [Service Responsibility Matrix](../../service-responsibility-matrix.md)

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

### Local Development Notes

The `smoke-test.sh` script under `services/game-logic-service` verifies both REST
and gRPC endpoints.

### Cross-Service Integration Test

An integration test at
`services/game-session-service/src/test/java/crossservice/net/firedevops/firemud/GameSessionCrossServiceIntegrationTest.java`
starts this service alongside the Game Session Service using **Testcontainers**.
Run it manually after building the Docker images:

```bash
./gradlew :game-session-service:test --tests "*CrossServiceIntegrationTest"
```
