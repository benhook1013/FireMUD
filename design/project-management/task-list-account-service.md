# Account Service Task List

## Account Management

- [ ] *(Login/session tasks maintained in the Login & Session vertical slice `design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md`.)*
- [x] Implement user registration and authentication (OAuth2, JWT)
- [x] Implement session management and persistent logins
- [x] Implement role-based access control (RBAC) for admins, moderators, and players
- [x] Enable external account linking (Google, Discord, Steam)
- [x] Implement profile system with achievements, game history, and social features
- [x] Implement player data export & deletion (GDPR compliance)
- [x] Expose JWKS endpoint for token verification
- [x] Use saga orchestrator for account creation workflow
- [x] Implement self-service account recovery
- [x] Add optional 2FA for admin and moderator roles
- [ ] Hash user passwords before storage using a strong algorithm like Argon2
- [ ] Track character ownership per account
- [ ] Implement account ban and suspension workflows with audit logging
- [ ] Provide web form and endpoints for players to submit ban appeals
- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime
- [ ] Automate JWKS key rotation using cert-manager and update services to poll for changes

## Email & Notification System

- [x] Implement email verification & password resets
- [x] Implement in-game notification system for events & messages
- [x] Configure SMTP provider and test templates
- [x] Document email and notification design in `account-service/design/README.md`
- [x] Add asynchronous NotificationService components with gRPC endpoints

## Monetization & Payment Module

- [x] Integrate Stripe or similar for in-game purchases
- [x] Support subscriptions, one-time purchases, and donations
- [x] Enforce platform fee on transactions
- [x] Implement refund & chargeback handling
- [x] Use saga orchestrator for cross-service purchase workflows
- [x] Create `payment_transaction` and `subscription` entities in the Account Service
- [x] Add gRPC methods in `AccountService` for payments
- [x] Define proto contracts for payment and subscription flows in the account proto namespace
- [x] Add Flyway migration scripts for payment tables
- [x] Document monetization design in `account-service/design/README.md`
- [x] Implement virtual currency system (game-specific currencies)
- [x] Implement premium hosting tiers & features for game creators
- [x] Implement revenue-sharing system for game creators

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
- [x] *(If persistent storage is used)* define JPA entities, repositories, and Flyway migrations with `tenantId` filtering

---

## 🔒 Authentication & Authorization

- [x] Meta and admin services validate JWTs using helpers from `firemud-common`
- [x] Check `globalRoles` and `scopedRoles` where applicable
- [x] *(N/A - meta service)* Gameplay services rely on the Game Session Service for session validation

---

## 🔁 Inter-Service Communication

- [x] Use `firemud-common` protobuf types for shared messages
- [x] Map errors to `ErrorDetail` with appropriate gRPC status codes
- [x] Register with service discovery via helpers in `firemud-common`
- [x] Ensure gRPC calls use mTLS certificates issued by cert-manager
  - [x] Internal traffic communicates directly over gRPC (Gateway not involved)

---

## 📚 Shared Library Integration

- [x] Depend on `firemud-common` via Gradle
- [x] Apply logging, tracing, and security interceptors from the library
- [x] Use provided autoconfiguration classes to reduce boilerplate
- [x] Reuse `DatabaseAutoConfiguration` and `RedisProperties` for environment setup

---

## 🔄 Saga Participation *(if used)*

- [x] Use saga helpers from `firemud-common` for workflow steps
- [x] Emit metrics and correlation IDs for compensation and retries
- [x] Document saga participation in `design/README.md`

---

## 🔑 Redis Integration *(if used)*

- [x] Use Redis for transient gameplay state only
- [x] Access Redis through helpers in `firemud-common`
- [x] Follow key conventions such as `tick:*`, `timer:*`, and `session:*` with `tenantId` prefixes
- [x] Validate shard-local key usage and avoid per-service caching
- [x] Emit metrics for Redis connectivity and commands
- [x] *(N/A - not a tick participant)* implement locking and staging per the Tick System docs
- [x] Prefix all keys with `tenantId` to isolate game data

---

## 🧪 Testing & Quality Gates

- [x] Add unit tests for gRPC, REST (if present), and startup behaviour
- [x] Use Spring Boot Test and Testcontainers for integration tests
- [x] Validate contracts with smoke tests (gRPC and REST)
- [x] Seed minimal test data for local workflows
- [x] Run `./gradlew check` in CI to execute all tests
- [x] *(When workflows span services)* add cross-service integration tests

---

## 📈 Observability & Tracing

- [x] Use Micrometer for Prometheus metrics
- [x] Enable OpenTelemetry tracing
- [x] Use shared interceptors to propagate `traceId` and `correlationId`
- [x] Emit service metrics for ticks and Redis commands when relevant
- [x] Expose `/actuator/prometheus` for scraping by Prometheus

---

## 📖 Documentation

- [x] Create `design/README.md` summarizing APIs and sample requests
- [x] Document proto contracts and any Redis keys in the service README
- [x] Document required environment variables and configuration
- [x] Note `tenantId` handling and cross-service dependencies
- [x] Add a design document under `design/architecture/microservices/<service>/README.md`

---

*Game-specific services may define additional commands or entity behavior but follow the same deployment conventions.*
