# Account Service Task List

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
- [ ] **Develop Email & Notification System**
  - [ ] Implement email verification & password resets
  - [ ] Implement in-game notification system for events & messages
  - [ ] Configure SMTP provider and test templates
  - [ ] Document email and notification design in `account-service/design/README.md`
  - [ ] Add asynchronous NotificationService components with gRPC endpoints
- [ ] **Develop Monetization & Payment Module**
  - [ ] Integrate Stripe or similar for in-game purchases
  - [ ] Support subscriptions, one-time purchases, and donations
  - [ ] Enforce platform fee on transactions
  - [ ] Implement refund & chargeback handling
  - [ ] Use saga orchestrator for cross-service purchase workflows
  - [ ] Create `payment_transaction` and `subscription` entities in the Account Service
  - [ ] Add gRPC methods in `AccountService` for payments
  - [ ] Define proto contracts for payment and subscription flows in the account proto namespace
  - [ ] Add Flyway migration scripts for payment tables
  - [ ] Document monetization design in `account-service/design/README.md`
  - [ ] Implement virtual currency system (game-specific currencies)
  - [ ] Implement premium hosting tiers & features for game creators
  - [ ] Implement platform-controlled ad system (for free-to-play games)
  - [ ] Implement revenue-sharing system for game creators

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise. Gateway and
TCP Proxy skip the gRPC and database items but still expose health checks and
participate in CI.

---

## 📦 Project Setup & CI

- [ ] Register the module in `settings.gradle.kts` and apply the `java` plugin
- [ ] Add a minimal Spring Boot application with `PingController` and gRPC `PingService` *(not needed for Gateway or TCP Proxy)*
- [ ] Provide a `Dockerfile` and Gradle task to build the image
- [ ] Create `README.md` with local setup instructions and design links
- [ ] Add the service to the GitHub Actions build matrix and Buf lint step
- [ ] Include the service in the Docker image workflow (`buildDockerImages`)
- [ ] Define Kubernetes `Deployment` and `Service` manifests
- [ ] Expose `/actuator/health` for readiness and liveness probes

---

## 🧱 API Definition

- [ ] Define gRPC service stubs with explicit `Request`/`Response` messages
- [ ] Version proto files under `protos/{service}/v1` with `package {service}.v1`
- [ ] Reuse shared types (e.g., `ErrorDetail`) from `protos/shared/`
- [ ] Generate gRPC stubs via Gradle and include them in the source set
- [ ] Add the proto directory to `buf.yaml` for lint and breaking change checks
- [ ] Provide contract smoke tests using `grpcurl`
- [ ] *(If REST endpoints are exposed)* implement controllers and generate OpenAPI specs
- [ ] *(If persistent storage is used)* define JPA entities, repositories, and Flyway migrations with `tenantId` filtering

---

## 🔒 Authentication & Authorization

- [ ] Meta and admin services validate JWTs using helpers from `firemud-common`
- [ ] Check `globalRoles` and `scopedRoles` where applicable
- [ ] Gameplay services rely on the Game Session Service for session validation

---

## 🔁 Inter-Service Communication

- [ ] Use `firemud-common` protobuf types for shared messages
- [ ] Map errors to `ErrorDetail` with appropriate gRPC status codes
- [ ] Register with service discovery via helpers in `firemud-common`
- [ ] Ensure gRPC calls use mTLS certificates issued by cert-manager
- [ ] Internal traffic communicates directly over gRPC (Gateway not involved)

---

## 📚 Shared Library Integration

- [ ] Depend on `firemud-common` via Gradle
- [ ] Apply logging, tracing, and security interceptors from the library
- [ ] Use provided autoconfiguration classes to reduce boilerplate
- [ ] Reuse `DatabaseAutoConfiguration` and `RedisProperties` for environment setup

---

## 🔄 Saga Participation *(if used)*

- [ ] Use saga helpers from `firemud-common` for workflow steps
- [ ] Emit metrics and correlation IDs for compensation and retries
- [ ] Document saga participation in `design/README.md`

---

## 🔑 Redis Integration *(if used)*

- [ ] Use Redis for transient gameplay state only
- [ ] Access Redis through helpers in `firemud-common`
- [ ] Follow key conventions such as `tick:*`, `timer:*`, and `session:*` with `tenantId` prefixes
- [ ] Validate shard-local key usage and avoid per-service caching
- [ ] Emit metrics for Redis connectivity and commands
- [ ] *(If participating in ticks)* implement locking and staging per the Tick System docs
- [ ] Prefix all keys with `tenantId` to isolate game data

---

## 🧪 Testing & Quality Gates

- [ ] Add unit tests for gRPC, REST (if present), and startup behaviour
- [ ] Use Spring Boot Test and Testcontainers for integration tests
- [ ] Validate contracts with smoke tests (gRPC and REST)
- [ ] Seed minimal test data for local workflows
- [ ] Run `./gradlew check` in CI to execute all tests
- [ ] *(When workflows span services)* add cross-service integration tests

---

## 📈 Observability & Tracing

- [ ] Use Micrometer for Prometheus metrics
- [ ] Enable OpenTelemetry tracing
- [ ] Use shared interceptors to propagate `traceId` and `correlationId`
- [ ] Emit service metrics for ticks and Redis commands when relevant
- [ ] Expose `/actuator/prometheus` for scraping by Prometheus

---

## 📖 Documentation

- [ ] Create `design/README.md` summarizing APIs and sample requests
- [ ] Document proto contracts and any Redis keys in the service README
- [ ] Document required environment variables and configuration
- [ ] Note `tenantId` handling and cross-service dependencies
- [ ] Add a design document under `design/architecture/microservices/<service>/README.md`

---

*Game-specific services may define additional commands or entity behavior but follow the same deployment conventions.*
