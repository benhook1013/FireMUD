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

- [Common Microservice Tasks](task-list-common.md)

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

- [x] **Repository & Workflow Setup**
  - [x] Set up Git repository and development workflow
  - [x] Provide script to generate Gradle wrappers for all services (`init-gradle-wrappers.ps1`)
  - [x] Implement CI/CD pipeline for automated builds, testing, and deployment (see [CI/CD Pipeline](../architecture/system-architecture-cicd.md))
    - [x] Ensure CI/CD includes the common package build process
  - [x] Define API contracts & inter-service communication (REST, gRPC, WebSockets)
    - [x] Ensure API contracts include standard error handling and request validation
  - [x] Define high-level architecture & microservices boundaries
  - [x] Choose technology stack (Spring Boot, PostgreSQL, Redis, WebSockets, Kubernetes, etc.)
  - [x] Set up Docker and Kubernetes for containerized deployment
  - [x] Set up centralized logging & monitoring (Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager)
  - [x] Configure Gradle Node plugin and markdownlint tasks
    - [x] Add `.markdownlint-cli2.jsonc` with repository rules
    - [x] Provide `.pre-commit-config.yaml` and git hook script

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
  - [ ] Add pull request template for contributors
  - [ ] Establish Architecture Decision Record (ADR) process
  - [x] Provide contributor guide with local setup commands and code review expectations
  - [x] Document environment variables and secrets management strategy

---

## 🛠️ Phase 1: Core Infrastructure & Basic Services

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
- [ ] Finalize API schemas from concrete gameplay flows

### Build `firemud-common` Library

- [ ] **Create a Common Package for Shared Microservice Code**
  - [x] Implement common request/response DTOs for inter-service communication
  - [x] Implement `ApiResponse`, `ResultStatus`, and `GlobalExceptionHandler`
  - [x] Implement centralized logging utilities
  - [x] Implement gRPC interceptors for logging and metrics
  - [ ] Integrate OpenTelemetry tracing helpers into `firemud-common`
  - [x] Implement authentication & authorization utilities (OAuth2, JWT helper methods)
  - [x] Implement database connection utilities (PostgreSQL, Redis connectors)
  - [x] Implement base configuration classes for service discovery and shared properties
  - [x] Implement common exception handling & error response structures
  - [x] Implement configuration management (centralized properties, environment handling)
  - [x] Include Lombok and MapStruct dependencies for annotation-based code generation
  - [ ] Publish common package to internal repository (Maven/Gradle)
  - [ ] Publish **firemud-protos** artifact with shared gRPC definitions
  - [x] Add `common-library` README with usage examples
  - [ ] Extend **firemud-common** with saga orchestration support
    - [ ] Define `saga` schema tables for step tracking and state
    - [ ] Implement fluent API for saga orchestration
    - [ ] Add gRPC call helpers with retry and compensation hooks
    - [ ] Document example saga usage
  - [ ] Replace placeholder classes with real implementations

### Infrastructure Setup

- [ ] Add `postgres` and `redis` services to `docker-compose.yml` with default credentials and persistent volumes
- [ ] Provide `.env.sample` and document connection details in `DEVELOPER_SETUP.md`
- [ ] Standardize `FIREMUD_` environment variable prefix across all services
- [ ] Configure Docker Compose health checks for PostgreSQL, Redis, and all services
- [ ] Expand `docker-compose.yml` to include all services
- [x] Include RedisInsight container in `docker-compose.override.yml` for debugging
- [ ] Create sample Terraform module to provision a local Kubernetes environment (e.g., using Kind or Minikube)
- [ ] Write sample Terraform code to:
  - [ ] Define `firemud` namespace and basic RBAC
  - [ ] Optionally configure local Redis or Helm releases
  - [ ] Deploy Redis cluster with automatic failover and AOF persistence (see [Redis Architecture](../architecture/system-architecture-redis.md))
  - [ ] Install redis-exporter for Prometheus metrics
  - [ ] Prepare Helm charts and baseline Kubernetes manifests for each service
    - [ ] Include `Deployment`, `Service`, and `NetworkPolicy` manifests
- [ ] Add example `values.yaml` files for local and dev environments
- [ ] Support Helm-based config overrides for:
  - [ ] Redis connection info
  - [ ] Tick interval
  - [ ] Runtime feature flags
- [ ] Document deployment steps:
  - [ ] Use `helm install` (or `helmfile`) to deploy FireMUD services locally
  - [ ] Reference Terraform files as optional future cloud setup
- [ ] Document network policy usage in architecture docs

### Security & Authentication

- [ ] **Define security best practices (OAuth2, JWT, RBAC, input validation, rate-limiting)**
- [ ] Meta/control services validate JWTs; gameplay services trust Game Session Service and skip JWT checks
- [ ] Rely on Game Session Service for gameplay session enforcement
- [ ] Configure mutual TLS (mTLS) for gRPC between internal services
- [ ] Manage certificates via Kubernetes `cert-manager`
- [ ] Secure credentials using Kubernetes Secrets (external secret stores not planned yet)
- [ ] Add integration test for mid-session role refresh via Game Session Service
- [ ] Implement hot reload for TLS certificates and JWKS keys

### Web Frontend

- [ ] Scaffold React-based MUD client with Vite and Material-UI
- [ ] Build web-based game editor for game creators
- [ ] Configure ESLint and Prettier for consistent formatting
- [ ] Add pre-commit hooks for frontend linting
- [ ] Add accessibility checks (Axe or Lighthouse) to CI
- [ ] Convert React frontend to TypeScript for type safety
- [ ] Run ESLint and Prettier checks in GitHub Actions

### CI/CD & Developer Automation

- [x] Create Gradle `devUp` task to build all services and start Docker Compose with sample data
- [x] Verify each service starts via `./gradlew devUp` and shows `Started` logs
- [ ] Provision ephemeral preview environments for pull requests
- [x] Automate Docker image builds and registry pushes
- [ ] Add CI steps for:
  - Protobuf generation and schema checking
  - Lint `.proto` files with `buf` and enforce schema versioning
  - Include `buf breaking` tests in CI for backward compatibility
  - Generate gRPC stubs for each service via Gradle plugin
  - Integrate proto generation and schema validation into CI workflow
    - Include generated sources in build and CI
    - OpenAPI consistency
    - Static analysis
  - [ ] Generate ERD diagrams and baseline Flyway scripts for each service in CI
  - [x] Add pre-commit hooks for:
    - [x] Spotless
    - [x] Checkstyle
    - [x] markdownlint
    - [x] SpotBugs
    - [ ] Buf or proto consistency
- [x] Use Trivy for container and dependency vulnerability scanning
- [x] Schedule recurring security scans (including base image and dependency scans)
- [ ] Auto-generate release notes and semantic version bumps
- [x] Enable Dependabot for automated dependency updates
- [x] Enable static analysis:
  - [x] Spotless for formatting
  - [x] Checkstyle for style rules
  - [x] SpotBugs for runtime defects
- [x] Enable code coverage (e.g., JaCoCo)
- [x] Enable CodeQL code scanning
- [x] Provide Insomnia and Kreya project files for manual API testing

### Observability & Tracing

- [ ] Configure centralized log aggregation dashboards early
- [ ] Deploy Prometheus, Grafana, and Alertmanager for metrics and alerts
- [ ] Deploy OpenTelemetry Collector for distributed tracing
- [ ] Generate gRPC API documentation with `protoc-gen-doc` and publish to project docs
- [ ] Commit default Grafana and Kibana dashboard templates

### ✅ Common Steps for All Microservices (Non-Infrastructure)

See [task-list-common.md](task-list-common.md) for tasks shared across all services.

---

## 🛠️ Phase 2: Testing & Pre-Launch Preparations

- [ ] **Write Developer Documentation for Game Creators**
  - [ ] Provide API references for scripting & integration
  - [ ] Guide for setting up and configuring hosted games

### Integration & Saga Testing

- [ ] Plan integration tests (via Testcontainers) for service collaboration
- [ ] Implement integration tests for multi-service interactions
- [ ] Validate saga workflows for account and world creation
- [ ] Create cross-service integration example scripts (account creation, game session startup)

---

## 🛠️ Phase 3: Deployment & Post-Launch Iteration

- [ ] **Iterate on Features & Add More Game Customization**
  - [ ] Expand game customization options for hosted games
  - [ ] Improve scripting capabilities & developer tools
- [ ] **Onboard Game Creators & Improve UX**
  - [ ] Develop tutorials & guides for game creators on customizing worlds and configuring hosted games
  - [ ] Gather feedback from early users & iterate on UI/UX
  - [ ] Add MCP support for AI assisted game creation

### ⚙️ Load Testing, Operations & Scaling

- [ ] **Conduct Load & Security Testing**
  - [ ] Simulate high-concurrency scenarios to identify bottlenecks
  - [ ] Run load tests using JMeter, Gatling, or Locust
  - [ ] Implement security testing (OWASP ZAP, penetration tests, rate limiting)
- [ ] **Deploy Staging Environments for Playtesting**
  - [ ] Perform multi-user playtests and gather feedback
- [ ] **Monitor Logs & Fix Issues in Production**
  - [ ] Track errors, crashes, and performance issues
  - [ ] Implement hotfixes for immediate problems
  - [ ] Document operational runbooks for deployment, scaling, and recovery
- [ ] **Scale & Optimize Performance**
  - [ ] Implement horizontal scaling (Auto-scaling, Load Balancer)
  - [ ] Optimize database queries & network traffic handling
  - [ ] Define backup & disaster recovery strategy (see [Backup & Disaster Recovery Plan](../architecture/system-architecture-backup-recovery.md))
  - [ ] Deploy **Velero** for scheduled Kubernetes and PostgreSQL backups
  - [ ] Configure production snapshots as described in [Backup & Disaster Recovery Plan](../architecture/system-architecture-backup-recovery.md)

## 🛠️ Phase 4: Community & Funding

- [ ] Set up financial contribution options
  - [ ] Add PayPal donation link
  - [ ] Configure GitHub Sponsors profile
- [ ] Create Patreon page

---

## ➕ Additional Tasks

- [ ] Provide command-line tooling for local game and session management
- [ ] Plan for **end-to-end UI testing** using Cypress or Playwright once the
  web UI is stable
- [ ] Evaluate localization and internationalization support for the React client
