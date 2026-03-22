# World Management Service Task List

## Map & Instances

- [x] Implement world map storage (rooms, regions)
- [x] Implement instance-based game spaces (e.g., dungeons, player housing)
- [x] Define instance rules, expiration, and persistence
- [x] Implement A* or Dijkstra-based pathfinding for NPCs & movement validation
- [ ] Expose pathfinding results via Movement/Travel subsystem (Game Logic Service) gRPC API with navmesh support

## World Events & Effects

- [x] Implement world event scheduling system (seasonal events, resets)
- [x] Implement environmental effects & persistent world state (weather, dynamic NPC behaviors)
- [x] Implement travel & navigation system (movement, teleportation, pathfinding)

## Scalability & Generation

- [x] Use saga orchestrator for world creation workflow
- [x] Provide tools to fine-tune procedural generation rules
- [x] Support multi-server world shards
- [ ] Automate region redistribution for load balancing across shards
- [ ] Generate terrain chunks during world creation
- [ ] Spawn default NPCs as part of initial world setup
- [ ] Schedule initial world events during world creation
- [ ] Persist generator metadata (`seed`, `generatorType`, params) on region/graph records and link editor overlays
- [ ] Ensure A&S result payload includes seed/type/params but leaves persistence to World API
- [x] Move `TravelService` to Game Logic as `MovementTravelService`; World Management exposes geometry only

## Data Sync & Notifications

- [ ] Copy published version data into world schema via Saga
- [ ] Publish gRPC notifications when world state changes
- [ ] Track character locations and instance occupancy
- [ ] Implement `room:<tenantId>:<roomId>` and `world-dynamic:<tenantId>:<aggregateId>` caches as Class A, versioned caches (room/world version fields, version-checked reads, atomic set+TTL writes) consistent with `system-architecture-redis-cache.md`.
- [ ] Wire world/room change events and version activations to invalidate or refresh `room:*` and `world-dynamic:*` keys, and add cache metrics/tests (hit/miss, key counts, reset behavior) for these prefixes.

## Administration & Backup

- [ ] Implement world snapshot API for backup and recovery
- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise.

## Project Setup & CI

- [x] Register the module in `settings.gradle.kts` and apply the `java` plugin
- [x] Add a minimal Spring Boot application with `PingController` and gRPC `PingService` *(not needed for Gateway or TCP Proxy)*
- [x] Provide a `Dockerfile` and Gradle task to build the image
- [x] Create `README.md` with local setup instructions and design links
- [x] Add the service to the GitHub Actions build matrix and Buf lint step
- [x] Include the service in the Docker image workflow (`buildDockerImages`)
- [x] Define Kubernetes `Deployment` and `Service` manifests
- [x] Expose `/actuator/health/readiness` and `/actuator/health/liveness` probes

---

## API Definition

- [x] Define gRPC service stubs with explicit `Request`/`Response` messages
- [x] Version proto files under `protos/{service}/v1` with `package {service}.v1`
- [x] Reuse shared types (e.g., `ErrorDetail`) from `protos/shared/`
- [x] Generate gRPC stubs via Gradle and include them in the source set
- [x] Add the proto directory to `buf.yaml` for lint and breaking change checks
- [x] Provide contract smoke tests using `grpcurl`
- [x] *(If REST endpoints are exposed)* implement controllers and generate OpenAPI specs
- [x] *(If persistent storage is used)* define JPA entities, repositories, and Flyway migrations with `tenantId` filtering

---

## Authentication & Authorization

- [x] Meta and admin services validate JWTs using helpers from `firemud-common`
- [x] Check `globalRoles` and `scopedRoles` where applicable
- [x] *(N/A - internal service validated by Game Session Service)* Gameplay services rely on the Game Session Service for session validation

---

## Inter-Service Communication

- [x] Use `firemud-common` protobuf types for shared messages
- [x] Map errors to `ErrorDetail` with appropriate gRPC status codes
- [x] *(N/A - server only, no outbound clients)* Register with service discovery via helpers in `firemud-common`
- [x] *(N/A - server only, no outbound clients)* Ensure gRPC calls use mTLS certificates issued by cert-manager
- [x] Internal traffic communicates directly over gRPC (Gateway not involved)

---

## Shared Library Integration

- [x] Depend on `firemud-common` via Gradle
- [x] Apply logging, tracing, and security interceptors from the library
- [x] Use provided autoconfiguration classes to reduce boilerplate
- [x] Reuse `DatabaseAutoConfiguration` and `RedisProperties` for environment setup

---

## Saga Participation *(if used)*

- [x] Use saga helpers from `firemud-common` for workflow steps
- [x] Emit metrics and correlation IDs for compensation and retries
- [x] Document saga participation in `design/README.md`

---

## Redis Integration *(if used)*

- [x] Use Redis for transient gameplay state only
- [x] Access Redis through helpers in `firemud-common`
- [x] Follow key conventions such as `tick:*`, `timer:*`, and `session:*` with `tenantId` prefixes
- [x] Validate shard-local key usage and avoid per-service caching
- [x] Emit metrics for Redis connectivity and commands
- [x] *(N/A - not part of tick system)* implement locking and staging per the Tick System docs
- [x] Prefix all keys with `tenantId` to isolate game data
- [ ] For any new or changed Redis prefixes (for coordination or cache), register them in the central key catalogs and follow the [Redis Change Checklist](../architecture/system-architecture-redis.md#redis-change-checklist) for role selection, hash-tagging, and reset behavior.

---

## Testing & Quality Gates

- [x] Add unit tests for gRPC, REST (if present), and startup behaviour
- [x] Use Spring Boot Test and Testcontainers for integration tests
- [x] Validate contracts with smoke tests (gRPC and REST)
- [x] Seed minimal test data for local workflows
- [x] Run `./gradlew check` in CI to execute all tests
- [x] *(When workflows span services)* add cross-service integration tests

---

## Observability & Tracing

- [x] Use Micrometer for Prometheus metrics
- [x] Enable OpenTelemetry tracing
- [x] Use shared interceptors to propagate `traceId` and `correlationId`
- [x] Emit service metrics for ticks and Redis commands when relevant
- [x] Expose `/actuator/prometheus` for scraping by Prometheus

---

## Documentation

- [x] Create `design/README.md` summarizing APIs and sample requests
- [x] Document proto contracts and any Redis keys in the service README
- [x] Document required environment variables and configuration
- [x] Note `tenantId` handling and cross-service dependencies
- [x] Add a design document under `design/architecture/microservices/<service>/README.md`

---

*Game-specific services may define additional commands or entity behavior but follow the same deployment conventions.*
