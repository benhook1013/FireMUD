# Common Steps for All Microservices (Non-Infrastructure)

These tasks apply to every service module. Gateway and TCP Proxy implement only a partial subset.

---

## 📦 Project Setup & Bootstrapping

- [ ] Create Gradle module with `java` or `java-library` plugin
- [ ] Add baseline source structure and Spring Boot entrypoint
- [ ] Implement basic `PingController` and gRPC `PingService`
- [ ] Define Dockerfile and Gradle image build
- [ ] Add minimal `README.md` with local setup instructions and design links
- [ ] Define Kubernetes `Deployment` and `Service` manifests
- [ ] Add Kubernetes readiness and liveness probes
- [ ] Prepare Helm charts for each service
- [ ] Verify service startup via `./gradlew devUp` and confirm `Started` logs
- [ ] Expose `/actuator/health` endpoint with Spring Boot Actuator
- [ ] Create baseline Kubernetes manifests or Helm charts for deployment
  - [ ] Create Kubernetes `NetworkPolicy` manifests to restrict service communication

---

## 🧱 Domain Modeling & API Exposure

- [ ] Define JPA entities and repositories using Spring Data for core domain objects
- [ ] Implement initial JPA entities and repositories
- [ ] Configure Flyway migrations for each service's initial schema
- [ ] Add MapStruct mappers for DTO conversion
- [ ] Use shared DTOs and mappers from `firemud-common`
- [ ] Generate initial REST controllers (CRUD or domain-specific endpoints)
- [ ] Define and implement gRPC service stubs with explicit `Request`/`Response` messages
- [ ] Version all `.proto` files (`package service.v1`) and place under `protos/{service}/v1`
- [ ] Use shared types (e.g., `ErrorDetail`, `EntityId`) from `protos/shared/`
- [ ] Lint `.proto` files with `buf` and enforce schema compatibility and versioning in CI
- [ ] Validate requests and map gRPC errors to appropriate status codes
- [ ] Add structured error responses using `shared/ErrorDetail.proto`
- [ ] Add contract smoke tests for gRPC (`grpcurl`) and REST (`curl`)
- [ ] Generate gRPC stubs via Gradle and include in CI pipeline
- [ ] Enforce `tenantId` validation for create endpoints
- [ ] Generate ERD diagrams and baseline Flyway scripts for each service (automated in CI)

---

## 🔒 Security & Authentication

- [ ] Integrate JWT-based authentication using helpers from `firemud-common`
- [ ] Validate `globalRoles` and `scopedRoles` where applicable
- [ ] Use shared security utilities from `firemud-common` for JWT and role validation
- [ ] Ensure authentication utilities from `firemud-common` integrate seamlessly

---

## 🔁 Inter-Service Communication

- [ ] Define clean gRPC service contracts and avoid vague method names
- [ ] Use `firemud-common` protobuf types for shared contracts
- [ ] Generate gRPC stubs using Gradle plugin and wire into source set
- [ ] Implement structured error mapping with `ErrorDetail`
- [ ] Integrate service discovery via Eureka or Kubernetes and register each service using `firemud-common` utilities

---

## 📚 Common Library Integration

- [ ] Depend on `firemud-common` via Gradle
- [ ] Use shared classes for:
  - DTOs (`ApiResponse<T>`, `ResultStatus`)
  - Error handling (`ErrorDetail`, `GlobalExceptionHandler`)
  - PostgreSQL and Redis config (base Spring config classes)
  - Logging and correlation ID propagation
  - gRPC interceptors for tracing and auth
  - Security utilities (JWT helpers, role validation)
  - Service discovery and environment config
- [ ] Replace boilerplate config with autoconfig starters from common lib
- [ ] Add examples of `firemud-common` usage in service README

---

## 🔄 Saga Participation (Optional)

- [ ] Use `firemud-common` saga orchestration helpers for workflow steps
- [ ] Handle retries, rollback, and compensation via provided API
- [ ] Emit saga metrics, correlation IDs, and observability logs
- [ ] Annotate saga participation in `design/README.md`
- [ ] Add timeout detection and automatic recovery
- [ ] Support declarative saga definitions via YAML or annotations
- [ ] Integrate saga events with logging and metrics

---

## 🔑 Redis Integration (If Applicable)

- [ ] Use Redis exclusively for transient, gameplay-related state
- [ ] Use `firemud-common` Redis connector utilities
- [ ] Follow tick-safe key conventions: `tick:*`, `timer:*`, `session:*`
- [ ] Validate Redis key usage for shard-local safety and naming discipline
- [ ] Avoid in-service caching for state handled by Redis
- [ ] Add Redis connectivity tests to catch misconfigurations early
- [ ] Emit service-level tick participation and Redis command metrics (if applicable)

---

## 🧪 Testing & Quality Gates

- [ ] Add unit tests for REST, gRPC, and startup behavior (e.g. `PingController`)
- [ ] Develop unit tests for core service logic (command parsing, actions, world updates)
- [ ] Perform API testing with Postman or RestAssured
- [ ] Introduce contract testing for gRPC and REST APIs (Spring Cloud Contract or Pact)
- [ ] Use Spring Boot Test and Testcontainers for integration testing
- [ ] Include optional dev data seeding for local workflows
- [ ] Validate gRPC and REST contracts with smoke tests using `grpcurl` and `curl`
- [ ] Add integration tests for each service's create endpoints

---

## 📈 Observability & Tracing

- [ ] Use Micrometer for Prometheus-compatible metrics
- [ ] Enable OpenTelemetry tracing via `spring-boot-starter-otel`
- [ ] Use shared gRPC interceptor to inject `traceId` and `correlationId`
- [ ] Propagate tracing context across service boundaries
- [ ] Emit service-level tick and Redis command metrics (if applicable)
- [ ] Use shared logging interceptor to ensure trace/correlation propagation

---

## 📖 Documentation & API Visibility

- [ ] Create `design/README.md` with:
  - gRPC method list
  - REST endpoint summaries
  - Sample `curl` and `grpcurl` invocations
  - Redis key usage (if applicable)
  - Saga participation details (if applicable)
- [ ] Summarize controller routes with sample request/response payloads
- [ ] Document endpoints and proto contracts in each service README
- [ ] Generate and publish OpenAPI specs (if REST used)
- [ ] Optionally provide Swagger UI or interactive API explorer
- [ ] Document required environment variables and config structure

## 🧩 Notes

- Game-specific services may define additional commands or entity behavior, but share the same project layout and deployment conventions.
- Gateway and TCP Proxy implement only the core health checks, gRPC bridging, and tracing pieces.
