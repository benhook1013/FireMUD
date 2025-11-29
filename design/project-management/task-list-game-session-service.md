# Game Session Service Task List

## Session Lifecycle

> **Note:** Login, session resumption, and reconnect details are now tracked in [design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md](vertical-slices/02-task-list-login-and-session-vertical-slice.md). Use that checklist for the active vertical slice instead of duplicating items here.

- [x] Implement game instance lifecycle (start, stop, restart)
- [x] Support multi-tenancy for hosted games
- [x] Persist session state in Redis for reconnect recovery
- [x] Enforce single-session control per character (session takeover on new login)
- [x] Plan for cross-region sharding and session handoff
- [ ] Implement cross-region sharding and session handoff
- [ ] Restore session state on reconnect, rebinding socket, region, timers, and in-flight actions
- [ ] Forward TOTP codes to the Account Service during login
- [ ] Refresh roles in-session when `scopedRoles` are updated
- [ ] Implement `LOGIN`/`LOGON` command handling for interactive and parameterized logins
- [ ] Forward JWTs to backend services on behalf of clients

## Tick Management

- [x] Implement tick orchestration using Redis for command queues
- [x] Implement Lua-based staging, commit, and rollback scripts for tick transactions
- [x] Implement distributed lock acquisition in Redis for tick updates
- [x] Implement tick replay and crash recovery logic
- [ ] Implement graceful degradation when Redis operations stall to avoid gameplay interruption
- [ ] Record conflict metadata during retries to highlight hotspots and enable adaptive throttling
- [ ] Support per-tenant tick intervals to customize pacing across games
- [ ] Implement stat-based prioritization for action execution
- [ ] Schedule entity updates for cooldowns, patrols, and regeneration
- [ ] Add backoff windows and retry caps for failed actions
- [ ] Add graph-based conflict resolution for repeated contention
- [ ] Batch database writes at the end of each tick
- [ ] Implement timer scanning and dynamic time scaling
- [ ] Implement session rebinding and deduplication using Redis keys
- [ ] Fan out global events across tick regions
- [ ] Implement cross-region command relay using `remote:{tenantId}:{entityId}` keys

## Analytics & Coordination

- [x] Manage runtime feature flags and expose toggle API via Logging & Admin Service ([Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md))
- [x] Implement `game_manifest` table for version coordination
- [ ] Restart active sessions when a new game version is published
- [ ] Apply script-only patches without restarting sessions
- [x] Emit gameplay analytics for operators
- [ ] Apply runtime feature flags during tick processing

## Security

- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime
- [ ] Track login attempts per IP and temporarily blacklist repeated failures
- [ ] Send notification emails for suspicious login activity
- [ ] Detect command spam or abnormal tick patterns using abuse heuristics

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise. Gateway and
TCP Proxy skip the gRPC and database items but still expose health checks and
participate in CI.

---

## 📦 Project Setup & CI

- [x] Register the module in `settings.gradle.kts` and apply the `java` plugin
- [x] Add a minimal Spring Boot application with `PingController` and gRPC `PingService` *(not needed for Gateway or TCP Proxy)*
- [x] Provide a `Dockerfile` and Gradle task to build the image
- [x] Create `README.md` with local setup instructions and design links
- [x] Add the service to the GitHub Actions build matrix and Buf lint step
- [x] Include the service in the Docker image workflow (`buildDockerImages`)
- [x] Define Kubernetes `Deployment` and `Service` manifests
- [x] Expose `/actuator/health` for readiness and liveness probes

---

## 🧱 API Definition

- [x] Define gRPC service stubs with explicit `Request`/`Response` messages
- [x] Version proto files under `protos/{service}/v1` with `package {service}.v1`
- [x] Reuse shared types (e.g., `ErrorDetail`) from `protos/shared/`
- [x] Generate gRPC stubs via Gradle and include them in the source set
- [x] Add the proto directory to `buf.yaml` for lint and breaking change checks
- [x] Provide contract smoke tests using `grpcurl`
- [x] *(If REST endpoints are exposed)* implement controllers and generate OpenAPI specs
- [x] *(If persistent storage is used)* define JPA entities, repositories, and Flyway migrations with `tenantId` filtering

---

## 🔒 Authentication & Authorization

- [x] *(N/A - gameplay service)* Meta and admin services validate JWTs using helpers from `firemud-common`
- [x] *(N/A - gameplay service)* Check `globalRoles` and `scopedRoles` where applicable
- [x] *(self)* Gameplay services rely on the Game Session Service for session validation

---

## 🔁 Inter-Service Communication

- [x] Use `firemud-common` protobuf types for shared messages
- [x] Map errors to `ErrorDetail` with appropriate gRPC status codes
- [x] Register with service discovery via helpers in `firemud-common`
- [x] Ensure gRPC calls use mTLS certificates issued by cert-manager
- [x] Internal traffic communicates directly over gRPC (Gateway not involved)

---

## 📚 Shared Library Integration

- [x] Depend on `firemud-common` via Gradle
- [x] Apply logging, tracing, and security interceptors from the library
- [x] Use provided autoconfiguration classes to reduce boilerplate
- [x] Reuse `DatabaseAutoConfiguration` and `RedisProperties` for environment setup

---

## 🔄 Saga Participation *(if used)*

- [x] Use saga helpers from `firemud-common` for workflow steps
- [x] Emit metrics and correlation IDs for compensation and retries
- [x] Document saga participation in `design/README.md`

---

## 🔑 Redis Integration *(if used)*

- [x] Use Redis for transient gameplay state only
- [x] Access Redis through helpers in `firemud-common`
- [x] Follow key conventions such as `tick:*`, `timer:*`, and `session:*` with `tenantId` prefixes
- [x] Validate shard-local key usage and avoid per-service caching
- [x] Emit metrics for Redis connectivity and commands
- [x] *(If participating in ticks)* implement locking and staging per the Tick System docs
- [x] Prefix all keys with `tenantId` to isolate game data

---

## 🧪 Testing & Quality Gates

- [x] Add unit tests for gRPC, REST (if present), and startup behaviour
- [x] Use Spring Boot Test and Testcontainers for integration tests
- [x] Validate contracts with smoke tests (gRPC and REST)
- [x] Seed minimal test data for local workflows
- [x] Run `./gradlew check` in CI to execute all tests
- [x] *(When workflows span services)* add cross-service integration tests
- [ ] Revisit the dev-isolated smoke/integration tests (`DevIsolatedGameSessionSmokeTest`, `GameSessionLoginIntegrationTest`, `GameSessionWebSocketHandlerIntegrationTest`, `SessionResumptionFlowTest`) after the real Account/Redis/GameInstance wiring lands; they are currently disabled via `@Disabled` with a TODO pointing to `design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md#7-dev-mode-stubs-and-real-service-rollout`.

---

## 📈 Observability & Tracing

- [x] Use Micrometer for Prometheus metrics
- [x] Enable OpenTelemetry tracing
- [x] Use shared interceptors to propagate `traceId` and `correlationId`
- [x] Emit service metrics for ticks and Redis commands when relevant
- [x] Expose `/actuator/prometheus` for scraping by Prometheus

---

## 📖 Documentation

- [x] Create `design/README.md` summarizing APIs and sample requests
- [x] Document proto contracts and any Redis keys in the service README
- [x] Document required environment variables and configuration
- [x] Note `tenantId` handling and cross-service dependencies
- [x] Add a design document under `design/architecture/microservices/<service>/README.md`

---

*Game-specific services may define additional commands or entity behavior but follow the same deployment conventions.*
