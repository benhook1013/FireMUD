# 🚀 MUD Game Platform Development To-Do List

This checklist is structured to **build foundational features first**, followed by **gameplay mechanics, multiplayer, administration, and optimizations**.
Service-specific tasks are tracked in separate files within this folder. Quick links:

- [Account Service](task-list-account-service.md)
- [Automation & Scripting Service](task-list-automation-scripting-service.md)
- [Entity Management Service](task-list-entity-management-service.md)
- [Game Design Service](task-list-game-design-service.md)
- [Game Logic Service](task-list-game-logic-service.md)
- [Game Session Service](task-list-game-session-service.md)
- [Logging & Admin Service](task-list-logging-admin-service.md)
- [Social & Groups Service](task-list-social-groups-service.md)
- [Spring Cloud Gateway](task-list-spring-cloud-gateway.md)
- [TCP Proxy Service](task-list-tcp-proxy-service.md)
- [World Management Service](task-list-world-management-service.md)

---

## 📋 Phase 0: Project Planning

- [x] **Define Vision & Scope of the Platform**
  - [x] Write a high-level product vision and key goals
  - [x] Create phased development plan

- [x] **Establish Naming Conventions & Folder Structure**
  - [x] Standardize service names, package structure, and code conventions
  - [x] Document folder and repo layout for multi-service organization

- [x] **Draft Technical Architecture Diagrams**
  - [x] High-level service map
  - [x] Data flow diagrams between client/editor/server
  - [x] Deployment architecture (e.g., Kubernetes clusters, CI/CD flow)

- [x] **Miscellaneous**
  - [x] Write initial design for each microservice
  - [x] Investigate transaction support for microservices
    - See [Transaction Strategies](../architecture/system-architecture-transactions.md)
    - [x] Document gRPC endpoints and compensating actions
    - [x] Describe Saga orchestration components in the shared library
    - [x] Provide example workflows (e.g., user registration)
  - [x] Update README after all services are defined
  - [x] Finalize architecture design documentation and diagrams
  - [x] Document service responsibility matrix
  - [x] Remove legacy Game Management Service and redistribute duties
  - [x] Conduct final review of all design documentation
  - [x] Address any missing diagrams or cross-references discovered during review
  - [x] Expand `CONTRIBUTING.md` with onboarding instructions
  - [x] Populate `FAQ.md` with common questions
  - [x] Add service-level design README links to central architecture docs
  - [x] Publish a `CODE_OF_CONDUCT.md` outlining community expectations
  - [x] Create issue template and maintain backlog for tasks and bugs
  - [x] Provide contributor guide with local setup commands and code review expectations
  - [x] Document environment variables and secrets management strategy
- [x] Expand `docker-compose.yml` to include all services
- [x] Create baseline Kubernetes manifests or Helm charts for deployment
  - [x] Create Kubernetes `NetworkPolicy` manifests to restrict service communication
    - [x] Document network policy usage in architecture docs
- [x] Create sample Terraform module to provision a local Kubernetes environment (e.g., using Kind or Minikube)
  > ⚠️ Note: These Terraform files are **for reference only** and **will not be used yet**
- [x] Write sample Terraform code to:
  - [x] Define `firemud` namespace and basic RBAC
  - [x] Optionally configure local Redis or Helm releases
- [ ] Prepare Helm charts for FireMUD services (see service-specific task lists)
- [x] Add example `values.yaml` files for local and dev environments
- [x] Support Helm-based config overrides for:
  - [x] Redis connection info
  - [x] Tick interval
  - [x] Runtime feature flags
- [x] Document deployment steps:
  - [x] Use `helm install` (or `helmfile`) to deploy FireMUD services locally
  - [x] Reference Terraform files as optional future cloud setup

---

## 🛠️ Phase 1: Core Infrastructure & Basic Services

### Immediate Next Steps
- [x] Implement PostgreSQL and Redis Docker containers for local dev
  - [x] Add `postgres` and `redis` services to `docker-compose.yml`
  - [x] Provide default credentials and mounted volumes for local data
  - [x] Include `.env.sample` with default environment variables
  - [x] Document connection settings in `DEVELOPER_SETUP.md`
  - [x] Create Docker volumes for persistent databases
  - [x] Verify services start via `./gradlew devUp`
  - [x] Confirm each service logs `Started` without errors

### Behavior and Orchestration Planning
- [x] Define core service responsibilities and runtime behaviors
  - [x] Outline tick flow, session management, reconnect logic, and command execution
  - [x] Document game instance lifecycle diagrams
- [x] Write sample gameplay use cases and trace the end-to-end flow
  - [x] Example flows: LOGIN, MOVE, CAST_SPELL
- [x] Identify the data each service needs to handle those flows
- [x] Derive minimal data models and proto schemas based on real usage
- [x] Refine shared DTOs and gRPC contracts from concrete examples
- [x] Document Redis key naming conventions and locking scheme

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

- [x] **Set up Git repository and development workflow**
- [x] **Implement CI/CD pipeline for automated builds, testing, and deployment** (see [CI/CD Pipeline](../architecture/system-architecture-cicd.md))
  - [x] Ensure CI/CD includes the common package build process
- [x] **Define API contracts & inter-service communication (REST, gRPC, WebSockets)**
  - [x] Ensure API contracts include standard error handling and request validation
  - [x] Configure gRPC infrastructure with **mTLS** certificates for internal calls
  - [x] Install **cert-manager** and store certificates as Kubernetes Secrets
- [x] **Define high-level architecture & microservices boundaries**
- [x] **Choose technology stack (Spring Boot, PostgreSQL, Redis, WebSockets, Kubernetes, etc.)**
- [x] **Set up Docker and Kubernetes for containerized deployment**
  - [x] **Configure Flyway-based database migrations for each microservice**
- [x] **Set up centralized logging & monitoring (Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager)**
  - [ ] Configure centralized log aggregation dashboards early
  - [x] **Define security best practices (OAuth2, JWT, RBAC, input validation, rate-limiting)**

#### Coding Kickoff Checklist
  - [ ] Provision ephemeral preview environments for pull requests
  - [ ] Finalize API schemas from concrete gameplay flows
  - [ ] Create Gradle `devUp` task to build all services and start Docker Compose with sample data
### Web Frontend
- [ ] Scaffold React-based MUD client with Vite and Material-UI
- [ ] Build web-based game editor for game creators
- [ ] Configure ESLint and Prettier for consistent formatting
- [ ] Add pre-commit hooks for frontend linting
- [ ] Add accessibility checks (Axe or Lighthouse) to CI
- [ ] Convert React frontend to TypeScript for type safety
- [ ] Run ESLint and Prettier checks in GitHub Actions

### ✅ Common Steps for All Microservices (Non-Infrastructure)

The following tasks are shared across most microservices. Gateway and TCP Proxy follow a reduced subset but still implement several of these steps.

_Applies to:_

- Account Service  
- Game Session Service  
- Game Logic Service  
- World Management Service  
- Entity Management Service  
- Automation & Scripting Service  
- Game Design Service  
- Social & Groups Service  
- Logging & Admin Service  
- Spring Cloud Gateway (⚠️ partial)  
- TCP Proxy Service (⚠️ partial)

---

#### 📦 Project Setup & Bootstrapping
- [ ] Create Gradle module with `java` or `java-library` plugin
- [ ] Add baseline source structure and Spring Boot entrypoint
- [ ] Implement basic `PingController` and gRPC `PingService`
- [ ] Define Dockerfile and Gradle image build
- [ ] Add `.env.sample` and Docker Compose integration (PostgreSQL, Redis)
- [ ] Configure Docker Compose health checks for PostgreSQL, Redis, and all services
- [ ] Add minimal `README.md` with local setup instructions and design links
- [ ] Define Kubernetes `Deployment` and `Service` manifests
- [ ] Add Kubernetes readiness and liveness probes
- [ ] Expose `/actuator/health` endpoint with Spring Boot Actuator

---

#### 🧱 Domain Modeling & API Exposure
- [ ] Define JPA entities and repositories using Spring Data for core domain objects
- [ ] Implement initial JPA entities and repositories
- [ ] Configure Flyway migrations for the initial schema
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

#### 🔒 Security & Authentication
- [ ] Integrate JWT-based authentication using helpers from `firemud-common`
- [ ] Validate `globalRoles` and `scopedRoles` where applicable
- [ ] Meta/control services validate JWTs; gameplay services trust Game Session Service and skip JWT checks
- [ ] Rely on Game Session Service for gameplay session enforcement
- [ ] Configure mutual TLS (mTLS) for gRPC between internal services
- [ ] Manage certificates via Kubernetes `cert-manager`
- [ ] Use shared security utilities from `firemud-common` for JWT and role validation
- [ ] Add integration test for mid-session role refresh via Game Session Service
- [ ] Implement hot reload for TLS certificates and JWKS keys
- [ ] Ensure authentication utilities from `firemud-common` integrate seamlessly

---

#### 🔁 Inter-Service Communication
- [ ] Define clean gRPC service contracts and avoid vague method names
- [ ] Use `firemud-common` protobuf types for shared contracts
- [ ] Generate gRPC stubs using Gradle plugin and wire into source set
- [ ] Include generated sources in build and CI
- [ ] Implement structured error mapping with `ErrorDetail`
- [ ] Include `buf breaking` tests in CI for backward compatibility
- [ ] Integrate proto generation and schema validation into CI workflow
- [ ] Integrate service discovery via Eureka or Kubernetes and register each service using `firemud-common` utilities

---

#### 📚 Common Library Integration
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

#### 🔄 Saga Participation (Optional)
- [ ] Use `firemud-common` saga orchestration helpers for workflow steps
- [ ] Handle retries, rollback, and compensation via provided API
- [ ] Emit saga metrics, correlation IDs, and observability logs
- [ ] Annotate saga participation in `design/README.md`

---

#### 🔑 Redis Integration (If Applicable)
- [ ] Use Redis exclusively for transient, gameplay-related state
- [ ] Use `firemud-common` Redis connector utilities
- [ ] Follow tick-safe key conventions: `tick:*`, `timer:*`, `session:*`
- [ ] Validate Redis key usage for shard-local safety and naming discipline
- [ ] Avoid in-service caching for state handled by Redis
- [ ] Add Redis connectivity tests to catch misconfigurations early
- [ ] Emit service-level tick participation and Redis command metrics (if applicable)

---

#### 🧪 Testing & Quality Gates
- [ ] Add unit tests for REST, gRPC, and startup behavior (e.g. `PingController`)
- [ ] Plan integration tests (via Testcontainers) for service collaboration
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

#### 🚀 CI/CD & Developer Automation
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

#### 📈 Observability & Tracing
- [ ] Use Micrometer for Prometheus-compatible metrics
- [ ] Enable OpenTelemetry tracing via `spring-boot-starter-otel`
- [ ] Use shared gRPC interceptor to inject `traceId` and `correlationId`
- [ ] Propagate tracing context across service boundaries
- [ ] Emit service-level tick and Redis command metrics (if applicable)
- [ ] Use shared logging interceptor to ensure trace/correlation propagation

---

#### 📖 Documentation & API Visibility
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

---

#### 🧩 Notes
- Game-specific services may define additional commands or entity behavior, but all follow the same project layout, config model, and deployment conventions.
- Spring Cloud Gateway and TCP Proxy implement a reduced subset:
  - Expose `/actuator/health`
  - Bridge traffic via gRPC or WebSocket
  - Use shared gRPC interceptors and Redis helpers where relevant
  - Use gRPC for route management and Telnet bridging (TCP Proxy)
  - Are included in Docker Compose, Kubernetes, and Helm setup
  - Follow CI, tracing, and health check conventions

---

## 🛠️ Phase 2: Testing & Pre-Launch Preparations

- [ ] **Implement Automated Unit & Integration Tests**
  - [ ] Develop unit tests for core services (command parsing, actions, world updates)
  - [ ] Implement integration tests for multi-service interactions
  - [ ] Validate saga workflows for account and world creation
  - [ ] Perform API testing with Postman, RestAssured
  - [ ] Introduce contract testing for gRPC and REST APIs (Spring Cloud Contract or Pact)

- [ ] **Conduct Load & Security Testing**
  - [ ] Simulate high-concurrency scenarios to identify bottlenecks
  - [ ] Run load tests using JMeter, Gatling, or Locust
  - [ ] Implement security testing (OWASP ZAP, penetration tests, rate limiting)

- [ ] **Deploy Staging Environments for Playtesting**
  - [ ] Perform multi-user playtests and gather feedback

- [ ] **Write Developer Documentation for Game Creators**
  - [ ] Provide API references for scripting & integration
  - [ ] Guide for setting up and configuring hosted games

---

## 🛠️ Phase 3: Deployment & Post-Launch Iteration

- [ ] **Monitor Logs & Fix Issues in Production**
  - [ ] Track errors, crashes, and performance issues
  - [ ] Implement hotfixes for immediate problems

- [ ] **Scale & Optimize Performance**
  - [ ] Implement horizontal scaling (Auto-scaling, Load Balancer)
  - [ ] Optimize database queries & network traffic handling
  - [ ] Define backup & disaster recovery strategy (see [Backup & Disaster Recovery Plan](../architecture/system-architecture-backup-recovery.md))
  - [ ] Deploy **Velero** for scheduled Kubernetes and PostgreSQL backups
  - [ ] Configure production snapshots as described in [Backup & Disaster Recovery Plan](../architecture/system-architecture-backup-recovery.md)
- [ ] **Iterate on Features & Add More Game Customization**
  - [ ] Expand game customization options for hosted games
  - [ ] Improve scripting capabilities & developer tools
- [ ] **Onboard Game Creators & Improve UX**
    - [ ] Develop tutorials & guides for game creators on customizing worlds and configuring hosted games
  - [ ] Gather feedback from early users & iterate on UI/UX
  - [ ] Add MCP support for AI assisted game creation

- [ ] **Enhance Saga Orchestration**
  - [ ] Add timeout detection and automatic recovery
  - [ ] Support declarative saga definitions via YAML or annotations
  - [ ] Integrate saga events with logging and metrics

---

## 🛠️ Phase 4: Community & Funding

- [ ] Set up financial contribution options
  - [ ] Add PayPal donation link
  - [ ] Configure GitHub Sponsors profile
- [ ] Create Patreon page

---

## ➕ Additional Tasks

- [ ] Integrate **Kubernetes Secrets** for storing all credentials across services
  - External secret stores are not planned at this stage
- [ ] Provide command-line tooling for local game and session management
- [ ] Plan for **end-to-end UI testing** using Cypress or Playwright once the
  web UI is stable
- [ ] Evaluate localization and internationalization support for the React client
