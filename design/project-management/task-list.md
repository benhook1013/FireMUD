# 🚀 MUD Game Platform Development To-Do List

This checklist is structured to **build foundational features first**, followed by **gameplay mechanics, multiplayer, administration, and optimizations**.
Service-specific tasks are tracked in separate files within this folder (e.g., `task-list-account-service.md`).

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
  - [ ] Enforce `tenantId` validation for create endpoints across all microservices
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
  - [ ] Implement hot reload for TLS certificates and JWKS keys across services
- [x] **Define high-level architecture & microservices boundaries**
- [x] **Choose technology stack (Spring Boot, PostgreSQL, Redis, WebSockets, Kubernetes, etc.)**
- [x] **Set up Docker and Kubernetes for containerized deployment**
  - [x] **Configure Flyway-based database migrations for each microservice**
- [ ] **Implement service discovery for internal microservices (Spring Cloud, Eureka, Consul, or Kubernetes-native)**
  - [ ] Ensure common package includes service discovery utilities
- [x] **Set up centralized logging & monitoring (Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager)**
  - [ ] Configure centralized log aggregation dashboards early
  - [x] **Define security best practices (OAuth2, JWT, RBAC, input validation, rate-limiting)**
  - [ ] Ensure authentication utilities from common package integrate seamlessly

#### Coding Kickoff Checklist
  - [ ] Provision ephemeral preview environments for pull requests
  - [ ] Finalize API schemas from concrete gameplay flows
    - [ ] Database schema diagrams for each microservice
    - [ ] Example Flyway migration scripts
  - [ ] Create ERD diagrams and baseline Flyway scripts for all services
    - [ ] Produce entity relationship diagrams for initial domain models
  - [ ] Automate architecture and ERD diagram generation in CI
  - [ ] Create Gradle `devUp` task to build all services and start Docker Compose with sample data
### Web Frontend
- [ ] Scaffold React-based MUD client with Vite and Material-UI
- [ ] Build web-based game editor for game creators
- [ ] Configure ESLint and Prettier for consistent formatting
- [ ] Add pre-commit hooks for frontend linting
- [ ] Add accessibility checks (Axe or Lighthouse) to CI
- [ ] Convert React frontend to TypeScript for type safety
- [ ] Run ESLint and Prettier checks in GitHub Actions

### ✅ Common Tasks Across All Microservices (Non-Infrastructure)


_Applies to the following services:_

- Spring Cloud Gateway  

#### 🎯 Project Setup and Structure
- Provide baseline `Dockerfile` and Gradle image build tasks
- Create Gradle modules with placeholder source
- Add Spring Boot skeleton with `PingController` and gRPC `PingService`
- Add base Spring Boot application and `application.yml`
- Configure `.env.sample`, Redis, and PostgreSQL in `docker-compose.yml`
- Include minimal `README.md` with local setup and design links
- Define Kubernetes Deployment and Service manifests
- Configure Docker Compose health checks for PostgreSQL, Redis, and all services

#### 🛠️ Application and Domain Setup
- Define JPA entities, repositories, and MapStruct mappers
- Implement basic JPA entities and repositories for core domain objects
- Generate skeleton REST controllers and gRPC service stubs
- Provide REST endpoints (CRUD or domain-specific)
- Define and expose gRPC proto contracts
- Use shared DTOs and mappers (e.g. MapStruct)
- Configure Flyway migrations for the initial schema
- Enable `/actuator/health` endpoints using `spring-boot-starter-actuator`
- Configure Kubernetes readiness and liveness probes
- Validate Redis usage aligns with tick-safe key naming (`tick:*`, `timer:*`, etc.)
- Use Redis utilities from `firemud-common` for all ephemeral state access
- Avoid in-service caching for gameplay state; Redis should be the sole coordination layer

#### 🔒 Authentication & Authorization
- Integrate JWT-based authentication and scoped role validation (if applicable)
- Use shared security utilities from `firemud-common`
- Configure gRPC with mTLS certificates for inter-service calls
- Use Kubernetes `cert-manager` for cert management
- Ensure meta/control services validate roles; gameplay services skip JWT validation
- Add integration test for role refresh and mid-session updates via Game Session

#### 🔁 Inter-Service Communication
- Define gRPC interfaces and message contracts
- Use shared protobufs for common types
- Validate requests and map errors to gRPC status codes
- Generate gRPC stubs via Gradle and include in CI
- Integrate proto generation into CI workflow
- Add structured error responses using `shared/ErrorDetail.proto`
- Lint proto files with `buf` and enforce versioning (`package account.v1`, etc.)

#### 📦 Common Library Usage
- Depend on `firemud-common` for:
  - DTOs and error wrappers (`ApiResponse`, `ErrorDetail`)
  - Logging, security, and configuration utilities
  - Centralized exception handling and service discovery helpers
  - Base Spring config classes for PostgreSQL/Redis
- Replace inline config or boilerplate with `firemud-common` starter classes
- Add usage examples to `README`

#### 🔁 Saga and Workflow Participation
- Participate in Saga orchestration where required
- Handle retries, compensations, and workflow coordination
- Use `firemud-common` Saga helpers for registration, retry logic, and compensation
- Emit saga metrics and log identifiers for observability
- Annotate `design/README.md` with saga workflows each service participates in

#### 🧪 Testing and Validation
- Add unit tests for `PingController` startup check and health checks
- Plan integration tests (via Testcontainers) for service collaboration
- Establish base integration test setup using Spring Boot Test and Testcontainers
- Add optional dev data seeding scripts for local testing
- Include static analysis with Spotless, Checkstyle, and SpotBugs
- Add code coverage (e.g., JaCoCo)
- Validate gRPC and REST contracts with `grpcurl` and `curl` smoke tests
- Add Redis connectivity tests to detect config issues early

#### 🚀 CI/CD and Automation
- Automate Docker image builds and publish in CI
- Lint proto files and check OpenAPI consistency
- Integrate with markdownlint and pre-commit hooks
- Add pre-commit hooks for Spotless, Checkstyle, markdownlint, and static analysis tools
- Configure Trivy for container and dependency scanning
- Auto-generate release notes and version bumps
- Schedule continuous security scans for dependencies and base images
- Include proto compatibility tests in CI (e.g., `buf breaking`)

#### 📈 Observability and Monitoring
- Configure Micrometer with Prometheus registry
- Enable OpenTelemetry tracing via `spring-boot-starter-otel`
- Propagate tracing context across gRPC and REST calls
- Use shared logging interceptor to inject trace IDs and correlation IDs
- Emit service-level tick participation and Redis command metrics (where applicable)

#### 📚 Documentation and API References
- Add `design/README.md`:
  - List REST endpoints and gRPC methods
  - Include sample cURL and `grpcurl` commands
  - Link to `.proto` files and design notes
- Summarize controller routes and include sample request/response payloads
- Document endpoints and proto contracts in each service README
- Generate OpenAPI specs and publish Swagger UI
- Provide interactive API explorer (optional)
- Note Redis keys and usage patterns per service (if applicable)
- List saga workflows the service participates in
- Document environment variable requirements and configuration structure

#### 🔧 Notes
- Game-specific services may define extra endpoints or logic, but all share the common bootstrapping, config, proto, CI, and API doc structure.
  - Use gRPC for route management / Telnet bridging
  - Expose basic health checks
  - Are defined in `docker-compose`, Kubernetes, and Helm setup

---

## 🛠️ Phase 9: Testing & Pre-Launch Preparations

- [ ] **Implement Automated Unit & Integration Tests**
  - [ ] Develop unit tests for core services (command parsing, actions, world updates)
  - [ ] Implement integration tests for multi-service interactions
  - [ ] Add integration tests for each service's create endpoints
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

## 🛠️ Phase 10: Deployment & Post-Launch Iteration

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

## 🛠️ Phase 11: Community & Funding

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
