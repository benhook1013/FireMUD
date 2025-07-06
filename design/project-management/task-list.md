# 🚀 MUD Game Platform Development To-Do List

This checklist is structured to **build foundational features first**, followed by **gameplay mechanics, multiplayer, administration, and optimizations**.

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
  - [x] Implement dedicated **TCP Proxy Service** bridging Telnet clients to the Gateway
  - [x] Finalize [Spring Cloud Gateway design](../architecture/infrastructure/gateway-architecture.md)
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
- [ ] Prepare Helm charts for FireMUD services:
  - [x] Game Session Service
  - [x] Account Service
  - [ ] Automation & Scripting Service
  - [ ] Entity Management Service
  - [ ] Game Design Service
  - [ ] Game Logic Service
  - [ ] Logging & Admin Service
  - [ ] Social & Groups Service
  - [ ] Spring Cloud Gateway
  - [ ] TCP Proxy Service
  - [ ] World Management Service
  - [ ] Redis (clustered, tick-safe config)
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

The following tasks are applicable to all microservices (be aware some may not apply to Spring Cloud Gateway and TCP Proxy Service).

_Applies to the following services:_

- Account Service  
- Game Session Service  
- Game Logic Service  
- World Management Service  
- Entity Management Service  
- Automation & Scripting Service  
- Game Design Service  
- Social & Groups Service  
- Logging & Admin Service  
- Spring Cloud Gateway  
- TCP Proxy Service

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
- Rely on Game Session Service for gameplay session enforcement
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
- Spring Cloud Gateway and TCP Proxy Service are infrastructure layers and follow modified rules, though they still:
  - Use gRPC for route management / Telnet bridging
  - Expose basic health checks
  - Are defined in `docker-compose`, Kubernetes, and Helm setup

---

## 🛠️ Phase 2: Account & Game Operations

- [ ] **Develop Account Service**
  - [ ] Implement user registration and authentication (OAuth2, JWT)
  - [ ] Implement session management and persistent logins
  - [ ] Implement role-based access control (RBAC) for admins, moderators, and players
  - [ ] Enable external account linking (Google, Discord, Steam)
  - [ ] Implement profile system with achievements, game history, and social features
  - [ ] Implement player data export & deletion (GDPR compliance)
  - [ ] Expose JWKS endpoint for token verification
  - [ ] Use saga orchestrator for account creation workflow
  - [ ] Implement self-service account recovery
  - [ ] Add optional 2FA for admin and moderator roles

- [ ] **Expand Game Session Service**
  - [ ] Implement game instance lifecycle (start, stop, restart)
  - [ ] Support multi-tenancy for hosted games
  - [ ] Implement tick orchestration using Redis for command queues
  - [ ] Implement Lua-based staging, commit, and rollback scripts for tick transactions
  - [ ] Implement distributed lock acquisition in Redis for tick updates
  - [ ] Implement tick replay and crash recovery logic
  - [ ] Persist session state in Redis for reconnect recovery
  - [ ] Enforce single-session control per character (session takeover on new login)
  - [ ] Manage runtime feature flags and expose toggle API via Logging & Admin Service ([Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md))
  - [ ] Plan for cross-region sharding and session handoff
  - [ ] Implement `game_manifest` table for version coordination
  - [ ] Emit gameplay analytics for operators
- [ ] **Expand Game Design Service**
  - [ ] Provide game templates and configuration tools
  - [ ] Enable publishing of game versions
- [ ] Use saga orchestrator for game publishing workflow
- [ ] Ensure domain services copy data by `version_id` and never query the design database at runtime
- [x] Create design-time database models

- [ ] **Develop Email & Notification System**
  - [ ] Implement email verification & password resets
  - [ ] Implement in-game notification system for events & messages
  - [ ] Configure SMTP provider and test templates
  - [ ] Document email and notification design in `account-service/design/README.md`
  - [ ] Add asynchronous NotificationService components with gRPC endpoints

---

## 🛠️ Phase 3: Game World & Entity Persistence

- [ ] **Develop World Management Service**
  - [ ] Implement world map storage (rooms, regions)
  - [ ] Implement instance-based game spaces (e.g., dungeons, player housing)
  - [ ] Define instance rules, expiration, and persistence
  - [ ] Implement world event scheduling system (seasonal events, resets)
  - [ ] Implement environmental effects & persistent world state (weather, dynamic NPC behaviors)
  - [ ] Implement travel & navigation system (movement, teleportation, pathfinding)
  - [ ] Implement A* or Dijkstra-based pathfinding for NPCs & movement validation
  - [ ] Use saga orchestrator for world creation workflow
  - [ ] Provide tools to fine-tune procedural generation rules
  - [ ] Support multi-server world shards

- [ ] **Develop Entity Management Service**
  - [ ] Implement player character storage
  - [ ] Implement NPC storage and data structures
  - [ ] Implement item and inventory management
  - [ ] Implement entity stats and progression tracking
  - [ ] Implement NPC respawn rules and timing
  - [ ] Implement cross-game account linking (allow single account across multiple hosted games)
  - [ ] Implement entity graph caching for fast lookups
  - [ ] Support complex crafting recipes

- [ ] **Implement Persistence Strategy**
  - [ ] Use PostgreSQL for primary storage
  - [ ] Use Redis for transient session state and ephemeral gameplay coordination
  - [ ] Implement world saving/loading system
  - [ ] Implement per-instance state persistence
  - [ ] Configure Redis cluster with AOF persistence and replica failover

---

## 🛠️ Phase 4: Game Logic & AI

- [ ] **Develop Game Logic Service**
  - [ ] Implement command parsing & validation
  - [ ] Implement action processing (movement, interactions, combat)
  - [ ] Implement roleplay actions & emotes
  - [ ] Implement event-driven logic processing (triggers, world events)
  - [ ] Implement action aliases system (custom command mappings)
  - [ ] Add scripting hooks for custom actions
  - [ ] Optimize performance for large-scale battles

- [ ] **Develop Automation & Scripting Service**
  - [ ] Implement state-driven & event-driven NPC behaviors
  - [ ] Implement procedural world generation
  - [ ] Implement scripted events for game mechanics and NPC interactions
  - [ ] Implement AI memory & dynamic NPC behaviors (NPCs remember past player interactions)
  - [ ] Implement player vs. environment (PvE) mechanics (random encounters, environmental hazards)
  - [ ] Implement faction & reputation system (players gain faction reputation over time)
  - [ ] Implement NPC aggression states (hostile, neutral, passive)
  - [ ] Implement NPC fleeing/surrender logic
  - [ ] Implement NPC formations & squad AI
  - [ ] Create sandboxed script runtime
  - [ ] Support hot reloading of scripts published by the Game Design Service
  - [ ] Provide web UI for script creation and testing
  - [ ] Add advanced AI modules for complex behaviors
  - [ ] Enforce fairness quotas and per-script resource limits

  - [ ] Define Telnet bridge gRPC APIs for TCP Proxy Service

- [ ] **Develop Trading & Economy System**
  - [ ] Support in-game currency and player transactions
  - [ ] Implement auction house and player-to-player trading
  - [ ] Implement dynamic resource spawning & distribution (controlled item & resource generation)
  - [ ] Implement tax & fee system for in-game economies
  - [ ] Implement player-run shops/stalls
  - [ ] Implement black market & underground economy system

- [ ] **Develop Leveling & Progression System**
  - [ ] Implement experience tracking and level progression

- [ ] **Develop Crafting & Item System**
  - [ ] Support item creation and crafting mechanics

---

## 🛠️ Phase 5: Game Design & Customization

- [ ] **Develop Game Design Service**
  - [ ] Implement world editing & customization tools
  - [ ] Implement scripting & event design tools
  - [ ] Build a **visual scripting editor** using a **component-based DSL**
  - [ ] Sandbox script execution with quotas via the Automation & Scripting Service
  - [ ] Implement ability & action design tools
  - [ ] Implement item & equipment balancing tools
  - [ ] Track version history and patch notes for published games
  - [ ] Build a web-based visual design interface
  - [ ] Integrate version control for design assets
  - [ ] Configure S3-compatible object storage for game assets
    - [ ] Provide asset upload API in Game Design Service
    - [ ] Document asset storage setup and configuration

- [ ] **Expand Scripting & Modding**
  - [ ] Implement event-driven scripting API for game creators
  - [ ] Implement in-game modding/plugin framework
  - [ ] Implement scripted AI behaviors for NPCs

---

## 🛠️ Phase 6: Multiplayer & Social Features

- [ ] **Develop TCP Proxy Service**
  - [ ] Implement Telnet networking and WebSocket bridging
  - [ ] Buffer Telnet input and discard on disconnect to support reconnection
  - [ ] Initialize `TcpProxyServiceApplication` with Netty server (implement connection pipeline)
  - [ ] Enforce Telnet command whitelist and input sanitization
  - [ ] Implement connection throttling and rate limits
  - [ ] Support TLS termination for secure Telnet clients
- [ ] **Develop Spring Cloud Gateway**
  - [ ] Handle API routing and request validation
  - [ ] Terminate TLS and forward traffic to internal services using mTLS
  - [ ] Collect connection metrics and throttle abusive clients
  - [ ] Create gateway route configuration files for all services
  - [x] Add baseline route configuration for Spring Cloud Gateway
  - [x] Create `GatewayController` endpoints for dynamic route management
    - [x] Allow creation of custom gateway routes via API
  - [x] Add gRPC `GatewayManagementService` for remote route configuration

- [ ] **Develop Social & Groups Service**
  - [ ] Enable cross-game friend lists and social graph
  - [ ] Support private messages, global chat, and guild channels
  - [ ] Implement player-to-player mail system (asynchronous in-game messaging)
  - [ ] Allow players to form and manage guilds
  - [ ] Implement guild ranking & permissions system
  - [ ] Implement shared guild storage and alliance system
  - [ ] Provide rich moderation tools for chat
  - [ ] Add optional voice chat integration
  - [ ] Use saga orchestrator for guild creation workflow

---

## 🛠️ Phase 7: Moderation & Restrictions

- [ ] **Develop Logging & Admin Service**
  - [ ] Collect logs from all services and provide search dashboards
  - [x] Allow players to report others for abuse/violations
  - [ ] Store logs for admin moderation and auditing
  - [ ] Expose runtime feature flag toggles ([Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md))
   - [x] Provide analytics dashboards for operators
   - [x] Define moderation policies including profanity filters
  - [ ] Integrate Alertmanager for automated alerts
  - [ ] Deploy Fluent Bit sidecars to forward logs to Elasticsearch
  - [ ] Evaluate adopting a zero-trust network model for internal traffic
  - [ ] Create **Saga Dashboard** to inspect workflow states and failures
  - [ ] Integrate saga metrics and timeout recovery
  - [ ] Use saga orchestrator for multi-service admin operations (bans, content revocation)
  - [ ] Build role-based admin UI

- [ ] **Implement Banning & Restriction System**
  - [ ] Implement IP bans, temporary suspensions, and game-specific restrictions

- [ ] **Implement Anti-Spam & Rate Limiting**
  - [ ] Prevent chat flooding, command spamming, and abuse

- [ ] **Implement Player-Owned Housing or Personal Areas**
  - [ ] Allow players to "own" rooms or private spaces in games with permissions

---

## 🛠️ Phase 8: Monetization & Payment System

- [ ] **Develop Monetization & Payment Module (Account Service)**
  - [ ] Integrate Stripe or similar for in-game purchases
  - [ ] Support subscriptions, one-time purchases, and donations
  - [ ] Enforce platform fee on transactions
  - [ ] Implement refund & chargeback handling
  - [ ] Use saga orchestrator for cross-service purchase workflows
  - [ ] Create `payment_transaction` and `subscription` entities in the Account Service
  - [x] Add gRPC methods in `AccountService` for payments
  - [x] Define proto contracts for payment and subscription flows in the account proto namespace
  - [x] Add Flyway migration scripts for payment tables
  - [x] Document monetization design in `account-service/design/README.md`
  - [ ] Implement virtual currency system (game-specific currencies)
  - [ ] Implement premium hosting tiers & features for game creators
  - [ ] Implement platform-controlled ad system (for free-to-play games)
  - [ ] Implement revenue-sharing system for game creators

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
