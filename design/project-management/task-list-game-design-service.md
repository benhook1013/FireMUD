# Game Design Service Task List

- **Prepare Helm chart for Game Design Service**
- **Expand Game Design Service**
  - Provide game templates and configuration tools
  - Enable publishing of game versions
  - Use saga orchestrator for game publishing workflow
  - Ensure domain services copy data by `version_id` and never query the design database at runtime
  - Create design-time database models
- **Develop Game Design Service**
  - Implement world editing & customization tools
  - Implement scripting & event design tools
  - Build a **visual scripting editor** using a **component-based DSL**
  - Sandbox script execution with quotas via the Automation & Scripting Service
  - Implement ability & action design tools
  - Implement item & equipment balancing tools
  - Track version history and patch notes for published games
  - Build a web-based visual design interface
  - Integrate version control for design assets
  - Configure S3-compatible object storage for game assets
    - Provide asset upload API in Game Design Service
    - Document asset storage setup and configuration
- **Expand Scripting & Modding**
  - Implement event-driven scripting API for game creators
  - Implement in-game modding/plugin framework
  - Implement scripted AI behaviors for NPCs

## Versioning & Runtime Configuration

- [ ] Implement cross-service game version publishing workflow
  - [ ] Store immutable versions in the Game Design Service
  - [ ] Copy published data to domain services using the `version_id`
  - [ ] Activate versions and runtime flags via the Game Session Service
  - [ ] Expose admin APIs for runtime flag toggles through the Logging & Admin Service

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise. Gateway and
TCP Proxy skip the gRPC and database items but still expose health checks and
participate in CI.

---

## 📦 Project Setup & CI

- [ ] Register the module in `settings.gradle.kts` and apply the `java` plugin
- [ ] Add a minimal Spring Boot application with `PingController` and gRPC `PingService` *(not needed for Gateway or TCP Proxy)*
- [ ] Provide a `Dockerfile` and Gradle task to build the image
- [ ] Create `README.md` with local setup instructions and design links
- [ ] Add the service to the GitHub Actions build matrix and Buf lint step
- [ ] Include the service in the Docker image workflow (`buildDockerImages`)
- [ ] Define Kubernetes `Deployment` and `Service` manifests
- [ ] Expose `/actuator/health` for readiness and liveness probes

---

## 🧱 API Definition

- [ ] Define gRPC service stubs with explicit `Request`/`Response` messages
- [ ] Version proto files under `protos/{service}/v1` with `package {service}.v1`
- [ ] Reuse shared types (e.g., `ErrorDetail`) from `protos/shared/`
- [ ] Generate gRPC stubs via Gradle and include them in the source set
- [ ] Add the proto directory to `buf.yaml` for lint and breaking change checks
- [ ] Provide contract smoke tests using `grpcurl`
- [ ] *(If REST endpoints are exposed)* implement controllers and generate OpenAPI specs
- [ ] *(If persistent storage is used)* define JPA entities, repositories, and Flyway migrations with `tenantId` filtering

---

## 🔒 Authentication & Authorization

- [ ] Meta and admin services validate JWTs using helpers from `firemud-common`
- [ ] Check `globalRoles` and `scopedRoles` where applicable
- [ ] Gameplay services rely on the Game Session Service for session validation

---

## 🔁 Inter-Service Communication

- [ ] Use `firemud-common` protobuf types for shared messages
- [ ] Map errors to `ErrorDetail` with appropriate gRPC status codes
- [ ] Register with service discovery via helpers in `firemud-common`
- [ ] Ensure gRPC calls use mTLS certificates issued by cert-manager
- [ ] Internal traffic communicates directly over gRPC (Gateway not involved)

---

## 📚 Shared Library Integration

- [ ] Depend on `firemud-common` via Gradle
- [ ] Apply logging, tracing, and security interceptors from the library
- [ ] Use provided autoconfiguration classes to reduce boilerplate
- [ ] Reuse `DatabaseAutoConfiguration` and `RedisProperties` for environment setup

---

## 🔄 Saga Participation *(if used)*

- [ ] Use saga helpers from `firemud-common` for workflow steps
- [ ] Emit metrics and correlation IDs for compensation and retries
- [ ] Document saga participation in `design/README.md`

---

## 🔑 Redis Integration *(if used)*

- [ ] Use Redis for transient gameplay state only
- [ ] Access Redis through helpers in `firemud-common`
- [ ] Follow key conventions such as `tick:*`, `timer:*`, and `session:*` with `tenantId` prefixes
- [ ] Validate shard-local key usage and avoid per-service caching
- [ ] Emit metrics for Redis connectivity and commands
- [ ] *(If participating in ticks)* implement locking and staging per the Tick System docs
- [ ] Prefix all keys with `tenantId` to isolate game data

---

## 🧪 Testing & Quality Gates

- [ ] Add unit tests for gRPC, REST (if present), and startup behaviour
- [ ] Use Spring Boot Test and Testcontainers for integration tests
- [ ] Validate contracts with smoke tests (gRPC and REST)
- [ ] Seed minimal test data for local workflows
- [ ] Run `./gradlew check` in CI to execute all tests
- [ ] *(When workflows span services)* add cross-service integration tests

---

## 📈 Observability & Tracing

- [ ] Use Micrometer for Prometheus metrics
- [ ] Enable OpenTelemetry tracing
- [ ] Use shared interceptors to propagate `traceId` and `correlationId`
- [ ] Emit service metrics for ticks and Redis commands when relevant
- [ ] Expose `/actuator/prometheus` for scraping by Prometheus

---

## 📖 Documentation

- [ ] Create `design/README.md` summarizing APIs and sample requests
- [ ] Document proto contracts and any Redis keys in the service README
- [ ] Document required environment variables and configuration
- [ ] Note `tenantId` handling and cross-service dependencies
- [ ] Add a design document under `design/architecture/microservices/<service>/README.md`

---

*Game-specific services may define additional commands or entity behavior but follow the same deployment conventions.*
