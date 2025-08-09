# Game Design Service Task List

## Game Templates & Publishing

- [x] Provide game templates and configuration tools
- [ ] Configure default administrator accounts when creating a new game template
- [x] Enable publishing of game versions
- [x] Use saga orchestrator for game publishing workflow
- [x] Ensure domain services copy data by `version_id` and never query the design database at runtime
- [x] Create design-time database models

## Design Tools

- [ ] Implement world editing & customization tools
- [x] Implement scripting & event design tools
- [x] Build a **visual scripting editor** using a **component-based DSL**
- [x] Sandbox script execution with quotas via the Automation & Scripting Service
- [ ] Implement ability & action design tools
- [ ] Implement item & equipment balancing tools
- [x] Track version history and patch notes for published games
- [x] Build a web-based visual design interface
- [x] Integrate version control for design assets
- [ ] Implement data-driven rule configuration so games can adjust mechanics without redeploying
- [ ] Support JSON import for rooms, items, and NPCs
- [x] Configure database storage for game assets
  - [x] Provide asset upload API in Game Design Service
  - [x] Document asset storage setup and configuration
  - [ ] Provide asset download and delete APIs
  - [ ] Add gRPC endpoints for asset management
  - [ ] Upload published assets and a `manifest.json` to version-scoped object
        storage so runtime clients can load them from a CDN

## Scripting & Modding

- [x] Implement event-driven scripting API for game creators
- [ ] Implement in-game modding/plugin framework
- [x] Implement scripted AI behaviors for NPCs
- [ ] Forward plugin metrics and error logs to the Logging & Admin Service
- [ ] Expose plugin enable/disable APIs via the Logging & Admin Service
- [ ] Notify downstream services when new versions are published
- [ ] Add import/export of design assets for sharing between games
- [ ] Add `owner_id` association to games and API

## Admin, Security & MCP

- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime
- [ ] Add MCP commands for room and item editing
- [ ] Support bulk import and transactional MCP content creation

## Versioning & Runtime Configuration

- [x] Implement cross-service game version publishing workflow
  - [ ] Create `runtime_flag` table and API for flag definitions
- [ ] Support script-only patch publishing (`scriptPatchVersion`) for hotfixes
- [x] Store immutable versions in the Game Design Service
- [x] Copy published data to domain services using the `version_id`
  - [x] Activate versions and runtime flags via the Game Session Service
  - [x] Expose admin APIs for runtime flag toggles through the Logging & Admin Service

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

- [x] Meta and admin services validate JWTs using helpers from `firemud-common`
- [x] Check `globalRoles` and `scopedRoles` where applicable
- [x] Gameplay services rely on the Game Session Service for session validation

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

- [x] Use Redis for transient gameplay state only *(N/A - service does not use Redis)*
- [x] Access Redis through helpers in `firemud-common` *(N/A - service does not use Redis)*
- [x] Follow key conventions such as `tick:*`, `timer:*`, and `session:*` with `tenantId` prefixes *(N/A - service does not use Redis)*
- [x] Validate shard-local key usage and avoid per-service caching *(N/A - service does not use Redis)*
- [x] Emit metrics for Redis connectivity and commands *(N/A - service does not use Redis)*
- [x] *(If participating in ticks)* implement locking and staging per the Tick System docs *(N/A - service does not participate in ticks)*
- [x] Prefix all keys with `tenantId` to isolate game data *(N/A - service does not use Redis)*

---

## 🧪 Testing & Quality Gates

- [x] Add unit tests for gRPC, REST (if present), and startup behaviour
- [x] Use Spring Boot Test and Testcontainers for integration tests
- [x] Validate contracts with smoke tests (gRPC and REST)
- [x] Seed minimal test data for local workflows
- [x] Run `./gradlew check` in CI to execute all tests
- [x] *(When workflows span services)* add cross-service integration tests

---

## 📈 Observability & Tracing

- [x] Use Micrometer for Prometheus metrics
- [x] Enable OpenTelemetry tracing
- [x] Use shared interceptors to propagate `traceId` and `correlationId`
- [x] Emit service metrics for ticks and Redis commands when relevant *(N/A - service does not use Redis or ticks)*
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
