# MUD Game Platform Development To-Do List

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
- [Web Client](task-list-web-client.md)
- [Spring Cloud Gateway](task-list-spring-cloud-gateway.md)
- [TCP Proxy Service](task-list-tcp-proxy-service.md)
- [World Management Service](task-list-world-management-service.md)

- Common microservice tasks are included in each service list.

## Phase 0: Project Planning

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
  - [x] Add `.gitignore` covering build artifacts and environment files
  - [x] Add `LICENSE.md` and `NOTICE.md` with licensing information
  - [x] Add `.gitattributes` for consistent line endings across platforms
  - [x] Add `gradle.properties` enabling build caching and parallelism
  - [x] Provide baseline `config/redis/redis.conf` for Docker Compose
    - [x] Provide script to generate Gradle wrappers for all services (`dev-tools/init-gradle-wrappers.ps1`)
  - [x] Implement CI/CD pipeline for automated builds, testing, and deployment (see [CI/CD Pipeline](../architecture/system-architecture-cicd.md))
    - [x] Ensure CI/CD includes the common package build process
    - [ ] Run `flywayValidate` for all services in the CI pipeline
    - [ ] Fully automate Docker image deployment and Kubernetes rollout
    - [ ] Provision cloud-hosted Kubernetes cluster for automated deployments
  - [x] Define API contracts & inter-service communication (REST, gRPC, WebSockets)
    - [x] Ensure API contracts include standard error handling and request validation
  - [x] Define high-level architecture & microservices boundaries
  - [x] Choose technology stack (Spring Boot, PostgreSQL, Redis, WebSockets, Kubernetes, etc.)
  - [x] Set up Docker and Kubernetes for containerized deployment
  - [x] Add Dockerfiles for each microservice
  - [x] Set up centralized logging & monitoring (Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager)
  - [x] Configure Gradle Node plugin and markdownlint tasks
    - [x] Add `config/markdownlint/.markdownlint-cli2.jsonc` with repository rules
      - [x] Provide `.pre-commit-config.yaml` and git hook script
    - [x] Add `package.json` with `markdownlint-cli2` for lint tasks
  - [x] Add `checkstyle.xml` and `spotbugs-exclude.xml` configuration files
  - [x] Apply `com.google.protobuf` plugin across services for gRPC stub generation
  - [x] Apply `org.flywaydb.flyway` plugin across services for database migrations
    - [x] Add baseline migration scripts in each service's `db/migration` directory
  - [x] Apply `org.springframework.boot` plugin across services for building and container image packaging (`bootBuildImage`)
  - [x] Add JUnit 5 and Mockito dependencies for unit tests
  - [x] Add `.windsurfrules` with AI coding guidelines
  - [x] Add `config/security/trivy.yaml` for Trivy security scans
  - [x] Add `checkstyle.xml` and `spotbugs-exclude.xml` for static analysis configuration
  - [x] Provide `.vscode` workspace settings
  - [x] Add `.editorconfig` for consistent indentation and whitespace rules
  - [x] Enable Gradle configuration cache and parallel builds
  - [x] Add GitHub workflow to build Docker images
  - [x] Add GitHub workflow to publish documentation to GitHub Pages

- [x] **Miscellaneous**
  - [x] Write initial design for each microservice
  - [x] Investigate transaction support for microservices
    - See [Transaction Strategies](../architecture/system-architecture-transactions.md)
    - [x] Document gRPC endpoints and compensating actions
    - [x] Describe Saga orchestration components in the shared library
    - [x] Provide example workflows (e.g., user registration)
    - [ ] Implement automatic saga retries and timeout recovery
    - [ ] Enforce idempotent saga steps
    - [ ] Integrate saga metrics and logging for observability
    - [ ] Support declarative saga flow definitions via YAML or annotations
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
  - [x] Add feature request issue template for contributors
  - [x] Publish `LICENSE.md` and `NOTICE.md` with licensing details
  - [x] Provide `DEVELOPER_SETUP.md` with local setup instructions
  - [x] Add pull request template for contributors
  - [x] Provide contributor guide with local setup commands and code review expectations
  - [x] Document environment variables and secrets management strategy
  - [x] Document multi-tenancy enforcement guidelines across all services (see [Multi-Tenancy](../architecture/system-architecture-multi-tenancy.md))

---

## Phase 1: Core Infrastructure & Basic Services

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
- [x] Finalize API schemas from concrete gameplay flows

### Build `firemud-common` Library

- [x] **Create a Common Package for Shared Microservice Code**
  - [x] Implement common request/response DTOs for inter-service communication
  - [x] Implement `ApiResponse`, `ResultStatus`, and `GlobalExceptionHandler`
  - [x] Implement centralized logging utilities
  - [x] Implement gRPC interceptors for logging and metrics
  - [x] Integrate OpenTelemetry tracing helpers into `firemud-common`
  - [x] Implement authentication & authorization utilities (OAuth2, JWT helper methods)
  - [x] Implement database connection utilities (PostgreSQL, Redis connectors)
  - [x] Implement base configuration classes for service discovery and shared properties
  - [x] Implement common exception handling & error response structures
  - [x] Implement configuration management (centralized properties, environment handling)
  - [x] Include Lombok and MapStruct dependencies for annotation-based code generation
  - [x] Publish common package to internal repository (Maven/Gradle)
  - [x] Publish **firemud-protos** artifact with shared gRPC definitions
  - [x] Add `common-library` README with usage examples
  - [x] Extend **firemud-common** with saga orchestration support
    - [x] Define `saga` schema tables for step tracking and state
    - [x] Implement fluent API for saga orchestration
    - [x] Add gRPC call helpers with retry and compensation hooks
    - [x] Document example saga usage
  - [x] Replace placeholder classes with real implementations

### Infrastructure Setup

- [x] Add `postgres` and `redis` services to `docker/docker-compose.yml` with default credentials and persistent volumes
- [x] Provide `config/redis/redis.conf` with AOF settings for local development
- [x] Provide `.env.sample` and document connection details in `DEVELOPER_SETUP.md`
- [x] Include `config/redis/redis.conf` for local Redis with AOF persistence
- [x] Standardize `FIREMUD_` environment variable prefix across all services
  - [x] Configure Docker Compose health checks for PostgreSQL, Redis, and all services
  - [x] Expand `docker/docker-compose.yml` to include all services
  - [x] Include RedisInsight container in `docker/docker-compose.override.yml` for debugging
  - [x] Create sample Terraform module to provision a local Kubernetes environment (e.g., using Kind or Minikube)
  - [x] Write sample Terraform code to:
    - [x] Define `firemud` namespace and basic RBAC
    - [x] Optionally configure local Redis or Helm releases
  - [x] Deploy Redis cluster with automatic failover and AOF persistence (see [Redis Architecture](../architecture/system-architecture-redis.md))
  - [x] Deploy PostgreSQL cluster with replication and persistent volumes
  - [x] Install redis-exporter for Prometheus metrics
    - [x] Prepare Helm charts and baseline Kubernetes manifests for each service
      - [x] Include `Deployment`, `Service`, and `NetworkPolicy` manifests
  - [x] Add example `values.yaml` files for local and dev environments
  - [x] Support Helm-based config overrides for:
    - [x] Redis connection info
    - [x] Tick interval
    - [x] Runtime feature flags
  - [x] Document deployment steps:
    - [x] Use `helm install` (or `helmfile`) to deploy FireMUD services locally
    - [x] Reference Terraform files as optional cloud setup
  - [x] Document network policy usage in architecture docs
  - [x] Apply Kubernetes `NetworkPolicy` manifests across environments
    - [x] Provide Helm umbrella chart for deploying all services together
  - [x] Develop production Terraform modules for Kubernetes, PostgreSQL, and Redis
  - [x] Build shared base Docker image for microservices

### Security & Authentication

- [x] **Define security best practices (OAuth2, JWT, RBAC, input validation, rate-limiting)**
- [x] Meta/control services validate JWTs; gameplay services trust Game Session Service and skip JWT checks
- [x] Configure mutual TLS (mTLS) for gRPC between internal services
- [x] Install and configure `cert-manager` in the Kubernetes cluster
- [x] Manage certificates via Kubernetes `cert-manager`
- [x] Secure credentials using Kubernetes Secrets
- [ ] Integrate an external secret store for centralized secret management
- [x] Add integration test for mid-session role refresh via Game Session Service
- [x] Implement hot reload for TLS certificates and JWKS keys
- [x] Implement connection rate limiting in Spring Cloud Gateway

### Web Frontend

- [x] Scaffold React-based MUD client with Vite and Material-UI
- [x] Build web-based game editor for game creators
- [x] Configure ESLint and Prettier for consistent formatting
- [x] Add pre-commit hooks for frontend linting
- [x] Add accessibility checks (Axe or Lighthouse) to CI
- [x] Convert React frontend to TypeScript for type safety
- [x] Run ESLint and Prettier checks in GitHub Actions
- [ ] Implement per-tenant Material-UI themes and asset loading via published
      `manifest.json` files
- [ ] Load localization files per tenant at runtime

### CI/CD & Developer Automation

- [x] Create Gradle `devUp` task to build all services and start Docker Compose with sample data
- [x] Verify each service starts via `./gradlew devUp` and shows `Started` logs
- [ ] Seed development database with sample worlds and characters
- [ ] Provide an installer/configuration helper (CLI or script) that walks contributors through initial setup steps (copying `.env.sample`, configuring local PostgreSQL/Redis, enabling optional Redis persistence, and wiring S3/MinIO buckets for asset storage and backups as described in the backup and asset storage docs)
- [x] Create Gradle `devDown` task to stop the Docker Compose stack
- [x] Create Gradle `buildDockerImages` task to build all service images
- [x] Provision ephemeral preview environments for pull requests
- [x] Automate Docker image builds and registry pushes
- [x] Publish Docker images to GitHub Container Registry (GHCR)
- [ ] Automate Helm-based Kubernetes rollouts in CI/CD
- [x] Cache Gradle and Node dependencies in CI for faster builds
  - [x] Add root `buf.yaml` and `config/protobuf/buf.gen.yaml` for protobuf linting and generation
- [x] Add CI steps for:
  - [x] Protobuf generation and schema checking
  - [ ] Investigate slow Docker Compose startup in GitHub Actions smoke runs, reduce avoidable service boot overhead, and replace the current shared Compose healthcheck grace tuning with service-specific readiness/start-period settings once the slowest services are understood
  - [x] Lint `.proto` files with `buf`
  - [x] Include `buf breaking` tests in CI for backward compatibility
  - [x] Generate gRPC stubs for each service via Gradle plugin
  - [x] Integrate proto generation and schema validation into CI workflow
    - [x] Include generated sources in build and CI
    - [x] OpenAPI consistency
    - [x] Static analysis
  - [x] Generate ERD diagrams and baseline Flyway scripts for each service in CI
  - [x] Add pre-commit hooks for:
    - [x] Spotless
    - [x] Checkstyle
    - [x] markdownlint
    - [x] SpotBugs
    - [x] Buf or proto consistency (lint via pre-commit)
- [x] Use Trivy for container and dependency vulnerability scanning
- [x] Post Trivy scan report as pull request comment
- [x] Cache Trivy vulnerability database in CI for faster scans
- [x] Schedule recurring security scans (including base image and dependency scans)
- [x] Add `weekly-security-scan.yml` workflow to scan published images weekly
- [x] Add GitHub workflow to generate release notes on tag push
- [x] Automate semantic version bumps
  - [x] Integrate open source license scanning into CI
- [x] Enable Dependabot for automated dependency updates
- [x] Enable static analysis:
  - [x] Spotless for formatting
  - [x] Checkstyle for style rules
  - [x] SpotBugs for runtime defects
- [x] Enable code coverage (e.g., JaCoCo)
- [x] Post build summary and coverage percent as pull request comment
- [x] Upload JaCoCo coverage report artifacts in CI
- [x] Upload test logs when unit tests fail
- [x] Enable CodeQL code scanning
- [x] Provide Insomnia project file for manual API testing
- [x] Provide Kreya project file for manual API testing
- [ ] Provide interactive API explorer for manual testing
  - [x] Configure email notifications for failed workflows

### Observability & Tracing

- [x] Configure centralized log aggregation dashboards early
- [x] Deploy Prometheus, Grafana, and Alertmanager for metrics and alerts
- [x] Deploy OpenTelemetry Collector for distributed tracing
- [x] Deploy Jaeger or equivalent trace UI for visualizing spans
- [x] Generate gRPC API documentation with `protoc-gen-doc` and publish to project docs
- [ ] Generate OpenAPI specs and host Swagger UI in CI for manual exploration
- [x] Commit default Grafana and Kibana dashboard templates
- [x] Configure Elasticsearch index retention (14 days dev, 90 days prod)
- [ ] Propagate `traceId` labels to Prometheus metrics via `MetricsInterceptor`
- [ ] Capture `playerId` in structured JSON logs across all services

### Common Steps for All Microservices (Non-Infrastructure)

The standard microservice checklist is now copied into each service task list.

---

## Phase 2: Testing & Pre-Launch Preparations

- [x] **Write Developer Documentation for Game Creators**
  - [x] Provide API references for scripting & integration
  - [x] Guide for setting up and configuring hosted games

### Integration & Saga Testing

- [x] Plan integration tests (via Testcontainers) for service collaboration
- [x] Implement integration tests for multi-service interactions
- [x] Validate saga workflows for account and world creation
- [x] Create cross-service integration example scripts (account creation, game session startup)
- [ ] Add unified `crossServiceTest` Gradle task and run cross-service tests in CI
- [ ] Automate test data seeding for integration tests
- [ ] Establish a centralized directory/listing of all test suites (unit, integration, cross-service, load, etc.) that explains what each covers and the environments/flags that skip or gate them, and link this catalog from the relevant docs and task lists.

---

## Phase 3: Deployment & Post-Launch Iteration

- [x] **Iterate on Features & Add More Game Customization**
  - [x] Expand game customization options for hosted games
  - [x] Improve scripting capabilities & developer tools
- [x] **Onboard Game Creators & Improve UX**
  - [x] Develop tutorials & guides for game creators on customizing worlds and configuring hosted games
  - [x] Gather feedback from early users & iterate on UI/UX
  - [ ] Add MCP support for AI assisted game creation

### Load Testing, Operations & Scaling

- [x] **Conduct Load & Security Testing**
  - [x] Simulate high-concurrency scenarios to identify bottlenecks
  - [x] Run load tests using JMeter, Gatling, or Locust
  - [x] Implement security testing (OWASP ZAP, penetration tests, rate limiting)
  - [ ] Automate load testing in the CI pipeline
- [x] **Deploy Staging Environments for Playtesting**
  - [x] Perform multi-user playtests and gather feedback
  - [ ] Set up dedicated staging Kubernetes cluster for community playtests
  - [ ] Invite community testers via Discord and email
- [x] **Monitor Logs & Fix Issues in Production**
  - [x] Track errors, crashes, and performance issues
  - [x] Implement hotfixes for immediate problems
  - [x] Document operational runbooks for deployment, scaling, and recovery
- [x] **Scale & Optimize Performance**
  - [x] Implement horizontal scaling (Auto-scaling, Load Balancer)
  - [x] Optimize database queries & network traffic handling
  - [x] Define backup & disaster recovery strategy (see [Backup & Disaster Recovery Plan](../architecture/system-architecture-backup-recovery.md))
  - [x] Deploy **Velero** for scheduled Kubernetes manifest backups
  - [x] Configure the `pg_dump` CronJob for PostgreSQL data as described in [Backup & Disaster Recovery Plan](../architecture/system-architecture-backup-recovery.md)

## Phase 4: Community & Funding

- [x] Set up financial contribution options
  - [x] Add PayPal donation link
  - [x] Add Patreon donation link
  - [x] Configure GitHub Sponsors profile
- [x] Create Patreon page

---

## Additional Tasks

- [x] Provide command-line tooling for local game and session management
- [x] Plan for **end-to-end UI testing** using Cypress or Playwright once the
  web UI is stable
- [x] Evaluate localization and internationalization support for the React client
- [ ] Configure **IPVS** or similar load balancing mode in Kubernetes clusters
- [ ] Add **PostgreSQL exporter** for Prometheus metrics
- [ ] Automate **database password rotation** using cert-manager or Secret syncing
- [ ] Automate JWT signing key rotation via cert-manager
- [ ] Issue TLS and mTLS certificates via cert-manager and mount them in Kubernetes Secrets
- [ ] Integrate `GrpcServerTlsReloader` across services for server certificate hot reload
- [ ] Implement server-side streaming gRPC event APIs for real-time notifications
- [ ] Support **multi-region deployments** for lower latency
- [ ] Schedule **nightly resets** of the staging playtest environment
- [ ] Implement layered reconnection across Proxy, Gateway, and Game Session services using Redis-backed session state
- [ ] Migrate all services to GUID-based `tenantId` values and dedicated database schemas
- [ ] Enforce `tenantId` filtering on every service query
- [ ] Load tenant-specific themes and branding in the React frontend
- [ ] Apply per-game resource quotas to prevent cluster capacity exhaustion
- [ ] Expose OpenTelemetry Collector metrics and scrape them with Prometheus
- [ ] Add OpenTelemetry Collector and Jaeger to the local Docker Compose stack
- [ ] Add Fluent Bit, Prometheus, and Grafana to the local Docker Compose stack
- [ ] Configure environment-specific Jaeger retention policies
- [ ] Deploy Redis as a clustered StatefulSet with automatic failover in production
- [ ] Upgrade the platform to Spring Boot 3.6+ (or 4.x when stable) so we can migrate tests from `@MockBean` to the new `@MockitoBean` support and drop the deprecated annotation usage currently suppressed in the cross-service harness.
