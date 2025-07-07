# Common Steps for All Microservices (Non-Infrastructure)

These tasks apply to every service module. Gateway and TCP Proxy implement only a partial subset.

---

## 🗂️ Repository & Workflow Setup

- [x] **Set up Git repository and development workflow**
- [x] **Implement CI/CD pipeline for automated builds, testing, and deployment** (see [CI/CD Pipeline](../architecture/system-architecture-cicd.md))
  - [x] Ensure CI/CD includes the common package build process
- [x] **Define API contracts & inter-service communication (REST, gRPC, WebSockets)**
  - [x] Ensure API contracts include standard error handling and request validation
- [x] **Define high-level architecture & microservices boundaries**
- [x] **Choose technology stack (Spring Boot, PostgreSQL, Redis, WebSockets, Kubernetes, etc.)**
- [x] **Set up Docker and Kubernetes for containerized deployment**
- [x] **Set up centralized logging & monitoring (Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager)**

## 🛠 Build `firemud-common` Library

- [ ] **Create a Common Package for Shared Microservice Code**
  - [x] Implement common request/response DTOs for inter-service communication
  - [x] Implement `ApiResponse`, `ResultStatus`, and `GlobalExceptionHandler`
  - [x] Implement centralized logging utilities
  - [x] Implement gRPC interceptors for logging and metrics
  - [x] Implement authentication & authorization utilities (OAuth2, JWT helper methods)
  - [x] Implement database connection utilities (PostgreSQL, Redis connectors)
  - [x] Implement base configuration classes for service discovery and shared properties
  - [x] Implement common exception handling & error response structures
  - [x] Implement configuration management (centralized properties, environment handling)
  - [ ] Publish common package to internal repository (Maven/Gradle)
  - [x] Add `common-library` README with usage examples
  - [ ] Extend **firemud-common** with saga orchestration support
    - [ ] Define `saga` schema tables for step tracking and state
    - [ ] Implement fluent API for saga orchestration
    - [ ] Add gRPC call helpers with retry and compensation hooks
    - [ ] Document example saga usage
  - [ ] Replace placeholder classes with real implementations

## 📦 Project Setup & Bootstrapping

- [ ] Create Gradle module with `java` or `java-library` plugin
- [ ] Add baseline source structure and Spring Boot entrypoint
- [ ] Implement basic `PingController` and gRPC `PingService`
- [ ] Define Dockerfile and Gradle image build
- [ ] Add `postgres` and `redis` services to `docker-compose.yml` with default credentials and persistent volumes
- [ ] Provide `.env.sample` and document connection details in `DEVELOPER_SETUP.md`
- [ ] Configure Docker Compose health checks for PostgreSQL, Redis, and all services
- [ ] Add minimal `README.md` with local setup instructions and design links
- [ ] Define Kubernetes `Deployment` and `Service` manifests
- [ ] Add Kubernetes readiness and liveness probes
- [ ] Prepare Helm charts for each service
- [ ] Verify service startup via `./gradlew devUp` and confirm `Started` logs
- [ ] Expose `/actuator/health` endpoint with Spring Boot Actuator
- [ ] Expand `docker-compose.yml` to include all services
- [ ] Create baseline Kubernetes manifests or Helm charts for deployment
  - [ ] Create Kubernetes `NetworkPolicy` manifests to restrict service communication
    - [ ] Document network policy usage in architecture docs
- [ ] Create sample Terraform module to provision a local Kubernetes environment (e.g., using Kind or Minikube)
  > ⚠️ Note: These Terraform files are **for reference only** and **will not be used yet**
- [ ] Write sample Terraform code to:
  - [ ] Define `firemud` namespace and basic RBAC
  - [ ] Optionally configure local Redis or Helm releases
- [ ] Add example `values.yaml` files for local and dev environments
- [ ] Support Helm-based config overrides for:
  - [ ] Redis connection info
  - [ ] Tick interval
  - [ ] Runtime feature flags
- [ ] Document deployment steps:
  - [ ] Use `helm install` (or `helmfile`) to deploy FireMUD services locally
  - [ ] Reference Terraform files as optional future cloud setup

---

## 🧱 Domain Modeling & API Exposure

- [ ] Finalize API schemas from concrete gameplay flows
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

- [ ] **Define security best practices (OAuth2, JWT, RBAC, input validation, rate-limiting)**
- [ ] Integrate JWT-based authentication using helpers from `firemud-common`
- [ ] Validate `globalRoles` and `scopedRoles` where applicable
- [ ] Meta/control services validate JWTs; gameplay services trust Game Session Service and skip JWT checks
- [ ] Rely on Game Session Service for gameplay session enforcement
- [ ] Configure mutual TLS (mTLS) for gRPC between internal services
- [ ] Manage certificates via Kubernetes `cert-manager`
- [ ] Secure credentials using Kubernetes Secrets (external secret stores not planned yet)
- [ ] Use shared security utilities from `firemud-common` for JWT and role validation
- [ ] Add integration test for mid-session role refresh via Game Session Service
- [ ] Implement hot reload for TLS certificates and JWKS keys
- [ ] Ensure authentication utilities from `firemud-common` integrate seamlessly

---

## 🔁 Inter-Service Communication

- [ ] Define clean gRPC service contracts and avoid vague method names
- [ ] Use `firemud-common` protobuf types for shared contracts
- [ ] Generate gRPC stubs using Gradle plugin and wire into source set
- [ ] Include generated sources in build and CI
- [ ] Implement structured error mapping with `ErrorDetail`
- [ ] Include `buf breaking` tests in CI for backward compatibility
- [ ] Integrate proto generation and schema validation into CI workflow
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
- [ ] Plan integration tests (via Testcontainers) for service collaboration
- [ ] Implement integration tests for multi-service interactions
- [ ] Validate saga workflows for account and world creation
- [ ] Perform API testing with Postman or RestAssured
- [ ] Introduce contract testing for gRPC and REST APIs (Spring Cloud Contract or Pact)
- [ ] Use Spring Boot Test and Testcontainers for integration testing
- [ ] Include optional dev data seeding for local workflows
- [ ] Enable static analysis:
  - Spotless for formatting
  - Checkstyle for style rules
  - SpotBugs for runtime defects
- [ ] Enable code coverage (e.g., JaCoCo)
- [ ] Validate gRPC and REST contracts with smoke tests using `grpcurl` and `curl`
- [ ] Add integration tests for each service's create endpoints

---

## 🚀 CI/CD & Developer Automation

- [ ] Create Gradle `devUp` task to build all services and start Docker Compose with sample data
- [ ] Provision ephemeral preview environments for pull requests
- [ ] Automate Docker image builds and registry pushes
- [ ] Add CI steps for:
  - Protobuf generation and schema checking
  - Proto linting and compatibility (`buf`)
  - OpenAPI consistency
  - Static analysis
- [ ] Add pre-commit hooks for:
  - Spotless
  - Checkstyle
  - markdownlint
  - Buf or proto consistency
- [ ] Use Trivy for container and dependency vulnerability scanning
- [ ] Schedule recurring security scans (including base image and dependency scans)
- [ ] Auto-generate release notes and semantic version bumps

---

## 📈 Observability & Tracing

- [ ] Use Micrometer for Prometheus-compatible metrics
- [ ] Enable OpenTelemetry tracing via `spring-boot-starter-otel`
- [ ] Use shared gRPC interceptor to inject `traceId` and `correlationId`
- [ ] Propagate tracing context across service boundaries
- [ ] Emit service-level tick and Redis command metrics (if applicable)
- [ ] Configure centralized log aggregation dashboards early
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

## ⚙️ Load Testing, Operations & Scaling

- [ ] **Conduct Load & Security Testing**
  - [ ] Simulate high-concurrency scenarios to identify bottlenecks
  - [ ] Run load tests using JMeter, Gatling, or Locust
  - [ ] Implement security testing (OWASP ZAP, penetration tests, rate limiting)
- [ ] **Deploy Staging Environments for Playtesting**
  - [ ] Perform multi-user playtests and gather feedback
- [ ] **Monitor Logs & Fix Issues in Production**
  - [ ] Track errors, crashes, and performance issues
  - [ ] Implement hotfixes for immediate problems
- [ ] **Scale & Optimize Performance**
  - [ ] Implement horizontal scaling (Auto-scaling, Load Balancer)
  - [ ] Optimize database queries & network traffic handling
  - [ ] Define backup & disaster recovery strategy (see [Backup & Disaster Recovery Plan](../architecture/system-architecture-backup-recovery.md))
  - [ ] Deploy **Velero** for scheduled Kubernetes and PostgreSQL backups
  - [ ] Configure production snapshots as described in [Backup & Disaster Recovery Plan](../architecture/system-architecture-backup-recovery.md)

---

## 🧩 Notes

- Game-specific services may define additional commands or entity behavior, but share the same project layout and deployment conventions.
- Gateway and TCP Proxy implement only the core health checks, gRPC bridging, and tracing pieces.
