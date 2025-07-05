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
  - [ ] Address any missing diagrams or cross-references discovered during review
  - [ ] Expand `CONTRIBUTING.md` with onboarding instructions
  - [ ] Populate `FAQ.md` with common questions
  - [ ] Finalize API schemas
    - [ ] gRPC proto definitions for each microservice
    - [ ] Database schema diagrams for each microservice
    - [ ] Example Flyway migration scripts
  - [ ] Document REST endpoints and gRPC method flows in each microservice README
  - [ ] Expand `docker-compose.yml` to include all services
  - [ ] Create baseline Kubernetes manifests or Helm charts for deployment

---

## 🛠️ Phase 1: Core Infrastructure & Basic Services
- [x] Create Gradle modules for all services with placeholder sources
 - [x] Add base Spring Boot Application classes for each service
- [x] Generate skeleton controllers and service classes for each microservice
- [ ] Define base entity and repository classes for core domains
  - [ ] Account Service: `Account` and `Profile` entities with JPA repositories
  - [ ] Entity Management Service: `Character`, `Item`, `NPC` entities
  - [ ] World Management Service: `Room` and `Region` entities
  - [ ] Game Session Service: `GameInstance` entity
  - [ ] Game Design Service: design-time schema entities

- [ ] **Create a Common Package for Shared Microservice Code**
  - [x] Implement common request/response DTOs for inter-service communication
  - [x] Implement `ApiResponse`, `ResultStatus`, and `GlobalExceptionHandler`
  - [x] Implement centralized logging utilities
  - [ ] Implement authentication & authorization utilities (OAuth2, JWT helper methods)
  - [ ] Implement database connection utilities (PostgreSQL, Redis connectors)
  - [ ] Implement base configuration classes for service discovery and shared properties
   - [x] Implement common exception handling & error response structures
  - [ ] Implement configuration management (centralized properties, environment handling)
  - [ ] Publish common package to internal repository (Maven/Gradle)
  - [ ] Extend **firemud-common** with saga orchestration support
    - [ ] Define `saga` schema tables for step tracking and state
    - [ ] Implement fluent API for saga orchestration
    - [ ] Add gRPC call helpers with retry and compensation hooks
    - [ ] Document example saga usage

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
- [x] **Implement service discovery for internal microservices (Spring Cloud, Eureka, Consul, or Kubernetes-native)**
  - [ ] Ensure common package includes service discovery utilities
- [x] **Set up centralized logging & monitoring (Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager)**
  - [x] **Define security best practices (OAuth2, JWT, RBAC, input validation, rate-limiting)**
  - [ ] Ensure authentication utilities from common package integrate seamlessly
  - [ ] Add initial protobuf IDL files for all microservices
    - [ ] Account Service proto definitions
    - [ ] Game Session Service proto definitions
    - [ ] World Management Service proto definitions
    - [ ] Entity Management Service proto definitions
    - [ ] Shared common types
  - [ ] Configure Gradle protobuf plugin and generate Java gRPC stubs in each module
  - [ ] Add base `application.yml` configuration for all services
  - [ ] Expose `/actuator/health` endpoints for service monitoring
  - [ ] Provide Docker image build tasks for each service

---

## 🛠️ Phase 2: Account & Game Operations

- [ ] **Develop Account Service**
  - [ ] Implement user registration and authentication (OAuth2, JWT)
  - [ ] Implement session management and persistent logins
  - [ ] Implement role-based access control (RBAC) for admins, moderators, and players
  - [ ] Enable external account linking (Google, Discord, Steam)
  - [ ] Implement profile system with achievements, game history, and social features
  - [ ] Implement player data export & deletion (GDPR compliance)
  - [ ] Create `AccountController` REST endpoints
  - [ ] Create JPA repositories for `Account` and `Profile`
  - [ ] Add gRPC AccountService with proto contract
  - [ ] Use saga orchestrator for account creation workflow

- [ ] **Expand Game Session Service**
  - [ ] Implement game instance lifecycle (start, stop, restart)
  - [ ] Support multi-tenancy for hosted games
  - [ ] Implement tick orchestration using Redis for command queues
  - [ ] Implement Lua-based staging, commit, and rollback scripts for tick transactions
  - [ ] Persist session state in Redis for reconnect recovery
  - [ ] Enforce single-session control per character (session takeover on new login)
  - [ ] Manage runtime feature flags and expose toggle API via Logging & Admin Service ([Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md))
  - [ ] Plan for cross-region sharding and session handoff
  - [ ] Emit gameplay analytics for operators
  - [ ] Create `GameSessionController` REST endpoints
  - [ ] Add gRPC GameSessionService with proto contract
- [ ] **Expand Game Design Service**
  - [ ] Provide game templates and configuration tools
  - [ ] Enable publishing of game versions
  - [ ] Use saga orchestrator for game publishing workflow
  - [ ] Ensure domain services copy data by `version_id` and never query the design database at runtime
  - [ ] Create gRPC GameDesignService and design-time database models

- [ ] **Develop Email & Notification System**
  - [ ] Implement email verification & password resets
  - [ ] Implement in-game notification system for events & messages
  - [ ] Configure SMTP provider and test templates

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
  - [ ] Create `WorldController` REST endpoints
  - [ ] Add gRPC WorldManagementService with proto contract

- [ ] **Develop Entity Management Service**
  - [ ] Implement player character storage
  - [ ] Implement NPC storage and data structures
  - [ ] Implement item and inventory management
  - [ ] Implement entity stats and progression tracking
  - [ ] Implement NPC respawn rules and timing
  - [ ] Implement cross-game account linking (allow single account across multiple hosted games)
  - [ ] Create `EntityController` REST endpoints
  - [ ] Add gRPC EntityManagementService with proto contract

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
  - [ ] Create `GameLogicController` REST endpoints
  - [ ] Add gRPC GameLogicService with proto contract

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
  - [ ] Add gRPC AutomationService with script execution API
  - [ ] Create sandboxed script runtime

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

- [ ] **Expand Scripting & Modding**
  - [ ] Implement event-driven scripting API for game creators
  - [ ] Implement in-game modding/plugin framework
  - [ ] Implement scripted AI behaviors for NPCs

---

## 🛠️ Phase 6: Multiplayer & Social Features

- [ ] **Develop TCP Proxy Service**
  - [ ] Implement Telnet networking and WebSocket bridging
  - [ ] Buffer Telnet input and discard on disconnect to support reconnection
- [ ] **Develop Spring Cloud Gateway**
  - [ ] Handle API routing and request validation
  - [ ] Terminate TLS and forward traffic to internal services using mTLS

- [ ] **Develop Social & Groups Service**
  - [ ] Enable cross-game friend lists and social graph
  - [ ] Support private messages, global chat, and guild channels
  - [ ] Implement player-to-player mail system (asynchronous in-game messaging)
  - [ ] Allow players to form and manage guilds
  - [ ] Implement guild ranking & permissions system
  - [ ] Implement shared guild storage and alliance system

---

## 🛠️ Phase 7: Moderation & Restrictions

- [ ] **Develop Logging & Admin Service**
  - [ ] Collect logs from all services and provide search dashboards
  - [ ] Allow players to report others for abuse/violations
  - [ ] Store logs for admin moderation and auditing
  - [ ] Expose runtime feature flag toggles ([Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md))
  - [ ] Provide analytics dashboards for operators
  - [ ] Define moderation policies including profanity filters
  - [ ] Integrate Alertmanager for automated alerts
  - [ ] Create **Saga Dashboard** to inspect workflow states and failures
  - [ ] Integrate saga metrics and timeout recovery
  - [ ] Use saga orchestrator for multi-service admin operations (bans, content revocation)

- [ ] **Implement Banning & Restriction System**
  - [ ] Implement IP bans, temporary suspensions, and game-specific restrictions

- [ ] **Implement Anti-Spam & Rate Limiting**
  - [ ] Prevent chat flooding, command spamming, and abuse

- [ ] **Implement Player-Owned Housing or Personal Areas**
  - [ ] Allow players to "own" rooms or private spaces in games with permissions

---

## 🛠️ Phase 8: Monetization & Payment System

- [ ] **Develop Monetization & Payment System**
  - [ ] Integrate Stripe or similar for in-game purchases
  - [ ] Support subscriptions, one-time purchases, and donations
  - [ ] Enforce platform fee on transactions
  - [ ] Implement refund & chargeback handling
  - [ ] Use saga orchestrator for cross-service purchase workflows
  - [ ] Implement virtual currency system (game-specific currencies)
  - [ ] Implement premium hosting tiers & features for game creators
  - [ ] Implement platform-controlled ad system (for free-to-play games)
  - [ ] Implement revenue-sharing system for game creators

---

## 🛠️ Phase 9: Testing & Pre-Launch Preparations

- [ ] **Implement Automated Unit & Integration Tests**
  - [ ] Develop unit tests for core services (command parsing, actions, world updates)
  - [ ] Implement integration tests for multi-service interactions
  - [ ] Perform API testing with Postman, RestAssured

- [ ] **Conduct Load & Security Testing**
  - [ ] Simulate high-concurrency scenarios to identify bottlenecks
  - [ ] Run load tests using JMeter, Gatling, or Locust
  - [ ] Implement security testing (OWASP ZAP, penetration tests, rate limiting)

- [ ] **Deploy Staging Environments for Playtesting**
  - [ ] Perform multi-user playtests and gather feedback

- [ ] **Write Developer Documentation for Game Creators**
  - [ ] Provide API references for scripting & integration
  - [ ] Develop tutorials for designing custom game worlds
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
  - [ ] Develop tutorials & guides for game creators
  - [ ] Gather feedback from early users & iterate on UI/UX
  - [ ] Add MCP support for AI assisted game creation

- [ ] **Enhance Saga Orchestration**
  - [ ] Add timeout detection and automatic recovery
  - [ ] Support declarative saga definitions via YAML or annotations
  - [ ] Integrate saga events with logging and metrics
