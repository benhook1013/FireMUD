# Social & Groups Service Task List

- [ ] **Develop Social & Groups Service**
  - [x] Enable cross-game friend lists and social graph
  - [x] Support private messages, global chat, and guild channels
  - [x] Implement player-to-player mail system (asynchronous in-game messaging)
  - [x] Allow players to form and manage guilds
  - [x] Implement guild ranking & permissions system
  - [x] Implement shared guild storage and alliance system
  - [x] Provide rich moderation tools for chat
  - [x] Add optional voice chat integration
  - [x] Use saga orchestrator for guild creation workflow

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise. Gateway and
TCP Proxy skip the gRPC and database items but still expose health checks and
participate in CI.

---

## 📦 Project Setup & CI

- [x] Register the module in `settings.gradle.kts` and apply the `java` plugin
- [x] Add a minimal Spring Boot application with `PingController` and gRPC `PingService` _(not needed for Gateway or TCP Proxy)_
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
- [x] _(If REST endpoints are exposed)_ implement controllers and generate OpenAPI specs
- [x] _(If persistent storage is used)_ define JPA entities, repositories, and Flyway migrations with `tenantId` filtering

---

## 🔒 Authentication & Authorization

- [x] Meta and admin services validate JWTs using helpers from `firemud-common`
- [x] Check `globalRoles` and `scopedRoles` where applicable
- [x] *(N/A - meta service)* Gameplay services rely on the Game Session Service for session validation

---

## 🔁 Inter-Service Communication

- [x] Use `firemud-common` protobuf types for shared messages
- [x] Map errors to `ErrorDetail` with appropriate gRPC status codes
- [x] *(N/A - Kubernetes DNS)* Register with service discovery via helpers in `firemud-common`
- [x] Ensure gRPC calls use mTLS certificates issued by cert-manager
- [x] Internal traffic communicates directly over gRPC (Gateway not involved)

---

## 📚 Shared Library Integration

- [x] Depend on `firemud-common` via Gradle
- [x] Apply logging, tracing, and security interceptors from the library
- [x] Use provided autoconfiguration classes to reduce boilerplate
- [x] Reuse `DatabaseAutoConfiguration` and `RedisProperties` for environment setup

---

## 🔄 Saga Participation _(if used)_

- [ ] Use saga helpers from `firemud-common` for workflow steps
- [ ] Emit metrics and correlation IDs for compensation and retries
- [x] Document saga participation in `design/README.md`

---

## 🔑 Redis Integration _(if used)_

- [x] Use Redis for transient gameplay state only
- [x] Access Redis through helpers in `firemud-common`
- [x] Follow key conventions such as `tick:*`, `timer:*`, and `session:*` with `tenantId` prefixes
- [ ] Validate shard-local key usage and avoid per-service caching
- [x] Emit metrics for Redis connectivity and commands
- [ ] _(If participating in ticks)_ implement locking and staging per the Tick System docs
- [x] Prefix all keys with `tenantId` to isolate game data

---

## 🧪 Testing & Quality Gates

- [x] Add unit tests for gRPC, REST (if present), and startup behaviour
- [x] Use Spring Boot Test and Testcontainers for integration tests
- [ ] Validate contracts with smoke tests (gRPC and REST)
- [ ] Seed minimal test data for local workflows
- [x] Run `./gradlew check` in CI to execute all tests
- [ ] _(When workflows span services)_ add cross-service integration tests

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

_Game-specific services may define additional commands or entity behavior but follow the same deployment conventions._
