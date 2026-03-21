# Logging & Admin Service Task List

## Logging & Monitoring

- [x] Collect logs from all services and provide search dashboards
- [x] Deploy Fluent Bit sidecars to forward logs to Elasticsearch
- [x] Integrate Alertmanager for automated alerts
- [x] Provide analytics dashboards for operators
- [ ] Integrate Prometheus metrics into admin dashboards
- [ ] Surface Jaeger trace data within admin dashboards
- [ ] Display Alertmanager notifications in the admin UI
- [ ] Support dashboard filtering by `playerId`
- [ ] Implement real-time analytics on game performance
- [ ] Provide real-time saga workflow state charts
- [ ] Allow per-game custom dashboards using shared templates
- [ ] Export analytics for external BI tools
- [x] Evaluate adopting a zero-trust network model for internal traffic

## Moderation Tools

- [x] Allow players to report others for abuse/violations
- [x] Store logs for admin moderation and auditing
- [x] Define moderation policies including profanity filters
- [ ] Provide web interface to review flagged logs
- [x] Build role-based admin UI
- [ ] Link moderation decisions to related log entries for context
- [ ] Implement automated detection and enforcement for hate speech, harassment, spam, and cheating
- [ ] Allow per-tenant customization of profanity word lists and detect bypass attempts
- [ ] Support temporary suspensions with configurable durations and notify players via the Account Service
- [ ] Handle ban appeal submissions from the Account Service web form
- [ ] Implement playtesting feedback form and store results for analytics

## Feature Flags & Configuration

- [x] Expose runtime feature flag toggles ([Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md))
- [ ] Add UI for managing runtime feature flags
- [ ] Record audit trails for feature flag changes and account events
- [ ] Persist transaction logs for purchases and subscription events

## Plugin Management

- [ ] Provide APIs to enable or disable game plugins
- [ ] Store plugin metrics and error logs for auditing

## Saga Operations

- [x] Create **Saga Dashboard** to inspect workflow states and failures
- [x] Integrate saga metrics and timeout recovery
- [x] Use saga orchestrator for multi-service admin operations (bans, content revocation)

## Security

- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime
- [ ] Add optional 2FA for administrator accounts via TOTP codes

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise. Gateway and
TCP Proxy skip the gRPC and database items but still expose health checks and
participate in CI.

---

## Project Setup & CI

- [x] Register the module in `settings.gradle.kts` and apply the `java` plugin
- [x] Add a minimal Spring Boot application with `PingController` and gRPC `PingService` *(not needed for Gateway or TCP Proxy)*
- [x] Provide a `Dockerfile` and Gradle task to build the image
- [x] Create `README.md` with local setup instructions and design links
- [x] Add the service to the GitHub Actions build matrix and Buf lint step
- [x] Include the service in the Docker image workflow (`buildDockerImages`)
- [x] Define Kubernetes `Deployment` and `Service` manifests
- [x] Expose `/actuator/health/readiness` and `/actuator/health/liveness` probes

---

## API Definition

- [x] Define gRPC service stubs with explicit `Request`/`Response` messages
- [x] Version proto files under `protos/{service}/v1` with `package {service}.v1`
- [x] Reuse shared types (e.g., `ErrorDetail`) from `protos/shared/`
- [x] Generate gRPC stubs via Gradle and include them in the source set
- [x] Add the proto directory to `buf.yaml` for lint and breaking change checks
- [x] Provide contract smoke tests using `grpcurl`
- [x] *(If REST endpoints are exposed)* implement controllers and generate OpenAPI specs
- [x] *(If persistent storage is used)* define JPA entities, repositories, and Flyway migrations with `tenantId` filtering

---

## Authentication & Authorization

- [x] Meta and admin services validate JWTs using helpers from `firemud-common`
- [x] Check `globalRoles` and `scopedRoles` where applicable
- [x] *(N/A - meta service)* Gameplay services rely on the Game Session Service for session validation

---

## Inter-Service Communication

- [x] Use `firemud-common` protobuf types for shared messages
- [x] Map errors to `ErrorDetail` with appropriate gRPC status codes
- [x] Register with service discovery via helpers in `firemud-common`
- [x] Ensure gRPC calls use mTLS certificates issued by cert-manager
- [x] Internal traffic communicates directly over gRPC (Gateway not involved)

---

## Shared Library Integration

- [x] Depend on `firemud-common` via Gradle
- [x] Apply logging, tracing, and security interceptors from the library
- [x] Use provided autoconfiguration classes to reduce boilerplate
- [x] Reuse `DatabaseAutoConfiguration` and `RedisProperties` for environment setup

---

## Saga Participation *(if used)*

- [x] Use saga helpers from `firemud-common` for workflow steps
- [x] Emit metrics and correlation IDs for compensation and retries
- [x] Document saga participation in `design/README.md`

---

## Redis Integration *(if used)*

- [x] *(N/A - no Redis usage)* Use Redis for transient gameplay state only
- [x] *(N/A - no Redis usage)* Access Redis through helpers in `firemud-common`
- [x] *(N/A - no Redis usage)* Follow key conventions such as `tick:*`, `timer:*`, and `session:*` with `tenantId` prefixes
- [x] *(N/A - no Redis usage)* Validate shard-local key usage and avoid per-service caching
- [x] *(N/A - no Redis usage)* Emit metrics for Redis connectivity and commands
- [x] *(N/A - no Redis usage)* *(If participating in ticks)* implement locking and staging per the Tick System docs
- [x] *(N/A - no Redis usage)* Prefix all keys with `tenantId` to isolate game data

---

## Testing & Quality Gates

- [x] Add unit tests for gRPC, REST (if present), and startup behaviour
- [x] Use Spring Boot Test and Testcontainers for integration tests
- [x] Validate contracts with smoke tests (gRPC and REST)
- [x] Seed minimal test data for local workflows
- [x] Run `./gradlew check` in CI to execute all tests
- [x] *(When workflows span services)* add cross-service integration tests

---

## Observability & Tracing

- [x] Use Micrometer for Prometheus metrics
- [x] Enable OpenTelemetry tracing
- [x] Use shared interceptors to propagate `traceId` and `correlationId`
- [x] *(N/A - no tick or Redis metrics)* Emit service metrics for ticks and Redis commands when relevant
- [x] Expose `/actuator/prometheus` for scraping by Prometheus

---

## Documentation

- [x] Create `design/README.md` summarizing APIs and sample requests
- [x] Document proto contracts and any Redis keys in the service README
  - [x] Document required environment variables and configuration
  - [x] Note `tenantId` handling and cross-service dependencies
- [x] Add a design document under `design/architecture/microservices/<service>/README.md`

---

*Game-specific services may define additional commands or entity behavior but follow the same deployment conventions.*
