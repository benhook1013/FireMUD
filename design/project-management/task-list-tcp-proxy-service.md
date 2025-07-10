# TCP Proxy Service Task List

- [x] **Implement dedicated TCP Proxy Service bridging Telnet clients to the Gateway**
- [x] **Define Telnet bridge gRPC APIs for TCP Proxy Service**
- [ ] **Develop TCP Proxy Service**
  - [x] Implement Telnet networking and WebSocket bridging
  - [x] Buffer Telnet input and discard on disconnect to support reconnection
  - [x] Initialize `TcpProxyServiceApplication` with Netty server (implement connection pipeline)
  - [ ] Enforce Telnet protocol command whitelist and input sanitization
  - [x] Implement connection throttling and rate limits
  - [x] Support TLS termination for secure Telnet clients

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
- [x] *(N/A - stateless service)* define JPA entities, repositories, and Flyway migrations with `tenantId` filtering

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

- [x] Depend on `firemud-common` via Gradle
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

- [x] Add unit tests for gRPC, REST (if present), and startup behaviour
- [ ] Use Spring Boot Test and Testcontainers for integration tests
- [ ] Validate contracts with smoke tests (gRPC and REST)
- [x] *(N/A - stateless service)* Seed minimal test data for local workflows
- [x] Run `./gradlew check` in CI to execute all tests
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

- [x] Create `design/README.md` summarizing APIs and sample requests
- [x] Document proto contracts and any Redis keys in the service README
- [x] Document required environment variables and configuration
- [x] Note `tenantId` handling and cross-service dependencies
- [x] Add a design document under `design/architecture/microservices/<service>/README.md`

---

*Game-specific services may define additional commands or entity behavior but follow the same deployment conventions.*
