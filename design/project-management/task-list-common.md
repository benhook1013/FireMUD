# Common Steps for All Microservices

These checklist items apply to every microservice unless a note indicates otherwise.
Gateway and TCP Proxy only implement the networking and health-check pieces.

---

## 📦 Project Setup & CI

- [ ] Register the Gradle module in `settings.gradle.kts` and apply the `java` or `java-library` plugin
- [ ] Add a Spring Boot entrypoint with a minimal `PingController` and gRPC `PingService`
- [ ] Provide a Dockerfile and Gradle task for building the image
- [ ] Create `README.md` with local setup instructions and links to design docs
- [ ] Add the service to the GitHub Actions build matrix and Buf lint step
- [ ] Include the service in the Docker image workflow (`buildDockerImages`)
- [ ] Define Kubernetes `Deployment` and `Service` manifests
- [ ] Configure readiness and liveness probes using `/actuator/health`

---

## 🧱 API Design

- [ ] Define gRPC service stubs with explicit `Request`/`Response` messages
- [ ] Version proto files under `protos/{service}/v1` with `package service.v1`
- [ ] Use shared proto types (e.g., `ErrorDetail`) from `protos/shared/`
- [ ] Generate gRPC stubs via Gradle and include them in the source set
- [ ] Add the proto directory to `buf.yaml` for linting and breaking-change checks
- [ ] Provide contract smoke tests using `grpcurl`
- [ ] *(If the service exposes REST APIs)* implement controllers and generate OpenAPI specs
- [ ] *(If the service persists data)* define JPA entities, repositories, and Flyway migrations
- [ ] *(If the service stores data)* include a `tenantId` column and enforce tenant filtering

---

## 🔒 Authentication & Authorization

- [ ] Integrate JWT validation using helpers from `firemud-common` *(meta/control services only)*
- [ ] Validate `globalRoles` and `scopedRoles` when relevant
- [ ] Use shared security utilities for JWT parsing and role checks
- [ ] Gameplay services defer session validation to the Game Session Service (no direct JWT check)

---

## 🔁 Inter-Service Communication

- [ ] Use `firemud-common` protobuf types for shared messages
- [ ] Map errors to `ErrorDetail` and appropriate gRPC status codes
- [ ] Register with service discovery (Eureka or Kubernetes) via helpers in `firemud-common`
- [ ] Ensure gRPC calls use mTLS certificates managed by cert-manager
- [ ] Communicate directly over gRPC (traffic does not route through the Gateway)

---

## 📚 Common Library Integration

- [ ] Depend on `firemud-common` via Gradle
- [ ] Apply logging, tracing, and security interceptors from the library
- [ ] Replace boilerplate configuration with provided autoconfiguration classes
- [ ] Reuse `DatabaseAutoConfiguration` and `RedisProperties` for environment setup

---

## 🔄 Saga Participation (Optional)

- [ ] Use saga helpers from `firemud-common` for workflow steps
- [ ] Emit metrics and correlation IDs for compensation and retries
- [ ] Document saga participation in `design/README.md`

---

## 🔑 Redis Integration *(if used)*

- [ ] Use Redis only for transient gameplay state
- [ ] Access Redis through helpers in `firemud-common`
- [ ] Follow key conventions such as `tick:*`, `timer:*`, and `session:*`
- [ ] Validate shard-local key usage and avoid in-service caching
- [ ] Add connectivity tests and emit Redis metrics
- [ ] *(If participating in ticks)* follow the locking and staging flow described in the Tick System docs
- [ ] Prefix all keys with `tenantId` to isolate game data

---

## 🧪 Testing & Quality Gates

- [ ] Add unit tests for gRPC, REST (if present), and startup behavior
- [ ] Use Spring Boot Test and Testcontainers for integration tests
- [ ] Validate gRPC and REST contracts with smoke tests
- [ ] Seed minimal test data for local workflows
- [ ] Run `./gradlew check` in CI to execute all unit and integration tests
- [ ] *(When workflows span services)* add cross-service integration tests

---

## 📈 Observability & Tracing

- [ ] Use Micrometer for Prometheus-compatible metrics
- [ ] Enable OpenTelemetry tracing via `spring-boot-starter-otel`
- [ ] Use shared interceptors to propagate `traceId` and `correlationId`
- [ ] Emit service metrics for ticks and Redis commands when relevant
- [ ] Expose `/actuator/prometheus` for scraping by Prometheus

---

## 📖 Documentation

- [ ] Create `design/README.md` summarizing APIs and sample requests
- [ ] Document proto contracts and any Redis keys in the service README
- [ ] Document required environment variables and configuration
- [ ] Note `tenantId` handling and cross-service dependencies

---

*Game-specific services may define additional commands or entity behavior but follow the same deployment conventions.*
