# Common Steps for All Microservices

These tasks are shared across most services. Gateway and TCP Proxy only implement the core networking pieces noted below.

---

## 📦 Project Setup & Bootstrapping

- [ ] Register the Gradle module in `settings.gradle.kts` and apply the `java` or `java-library` plugin
- [ ] Add a Spring Boot entrypoint with a minimal `PingController` and gRPC `PingService`
- [ ] Provide a Dockerfile and Gradle task for building the image
- [ ] Create `README.md` with local setup instructions and design links
- [ ] Add the service to the GitHub Actions build matrix
- [ ] Define Kubernetes `Deployment` and `Service` manifests
- [ ] Configure readiness and liveness probes using `/actuator/health`

---

## 🧱 API Design & Data Model *(not required for Gateway or TCP Proxy)*

- [ ] Define JPA entities and repositories for persisted data
- [ ] Configure Flyway migrations for the initial schema
- [ ] Add MapStruct mappers for DTO conversion
- [ ] Use shared DTOs from `firemud-common`
- [ ] Implement REST controllers when needed
- [ ] Define gRPC service stubs with explicit `Request`/`Response` messages
- [ ] Version proto files under `protos/{service}/v1` with `package service.v1`
- [ ] Use shared proto types (e.g., `ErrorDetail`) from `protos/shared/`
- [ ] Validate requests and map gRPC errors to proper status codes
- [ ] Provide contract smoke tests using `grpcurl` and `curl`

---

## 🔒 Authentication & Authorization *(not required for Gateway or TCP Proxy)*

- [ ] Integrate JWT-based authentication using helpers from `firemud-common`
- [ ] Validate `globalRoles` and `scopedRoles` when present
- [ ] Use shared security utilities for JWT parsing and role checks

---

## 🔁 Inter-Service Communication

- [ ] Define clear gRPC service contracts
- [ ] Use `firemud-common` protobuf types for shared messages
- [ ] Generate gRPC stubs via Gradle and include them in the source set
- [ ] Map errors to `ErrorDetail` and gRPC status codes
- [ ] Register with service discovery (Eureka or Kubernetes) using helpers from `firemud-common`

---

## 📚 Common Library Integration

- [ ] Depend on `firemud-common` via Gradle
- [ ] Reuse shared DTOs, error handlers, and config classes
- [ ] Apply logging, tracing, and security interceptors from the library
- [ ] Replace boilerplate config with the provided autoconfiguration

---

## 🔄 Saga Participation (Optional)

- [ ] Use saga helpers from `firemud-common` for workflow steps
- [ ] Emit metrics and correlation IDs for compensation and retries
- [ ] Document saga participation in `design/README.md`

---

## 🔑 Redis Integration (If Applicable)

- [ ] Use Redis only for transient gameplay state
- [ ] Access Redis through helpers in `firemud-common`
- [ ] Follow key conventions such as `tick:*`, `timer:*`, and `session:*`
- [ ] Validate shard-local key usage and avoid in-service caching
- [ ] Add connectivity tests and emit Redis metrics when relevant

---

## 🧪 Testing & Quality Gates

- [ ] Add unit tests for REST, gRPC, and startup behavior
- [ ] Use Spring Boot Test and Testcontainers for integration tests
- [ ] Validate gRPC and REST contracts with smoke tests (`grpcurl`, `curl`)
- [ ] Seed minimal test data for local workflows

---

## 📈 Observability & Tracing

- [ ] Use Micrometer for Prometheus-compatible metrics
- [ ] Enable OpenTelemetry tracing via `spring-boot-starter-otel`
- [ ] Use shared interceptors to propagate `traceId` and `correlationId`
- [ ] Emit service metrics for ticks and Redis commands when relevant

---

## 📖 Documentation & API Visibility

- [ ] Create `design/README.md` summarizing APIs and sample requests
- [ ] Document proto contracts and any Redis keys in the service README
- [ ] Generate OpenAPI specs if REST endpoints are present
- [ ] Document required environment variables and configuration

## 🧩 Notes

- Game-specific services may define additional commands or entity behavior but follow the same deployment conventions.
- Gateway and TCP Proxy implement only the health checks, gRPC bridging, and tracing pieces.
