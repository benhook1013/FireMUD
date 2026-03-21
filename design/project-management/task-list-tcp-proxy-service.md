# TCP Proxy Service Task List

## Telnet Bridge

> **Note:** The Telnet `SESSION` + `LOGIN` + `LOOK` parity and reconnection flows are tracked in [design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md](vertical-slices/02-task-list-login-and-session-vertical-slice.md), and the canonical protocol rules live in the [Telnet Session Envelope & Event Metrics](../architecture/microservices/tcp-proxy-service/README.md#telnet-session-envelope--event-metrics) section of the TCP Proxy Service design. Refer to those docs rather than redefining behaviour here.

- [x] Implement dedicated TCP Proxy Service bridging Telnet clients to the Gateway
- [x] Define Telnet bridge gRPC APIs for TCP Proxy Service
- [x] Implement Telnet networking and WebSocket bridging
- [x] Support TLS termination for secure Telnet clients
- [ ] Wire mutual TLS for Proxy → Gateway WebSocket traffic (update the TCP Proxy Service WebSocket client to use `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, and `FIREMUD_GATEWAY_WS_CA_CERT_PATH` so it matches the target-state design described in `design/architecture/microservices/tcp-proxy-service/README.md` and `design/architecture/system-architecture-overview.md`. This task is complete when the Proxy → Gateway hop uses `wss://` with client certificates in all target environments, TLS failures are surfaced via `tcpproxy.gateway.handshake.failures{reason=...}`, and the architecture docs no longer need “target-state” qualifiers for this link.)
- [ ] Handle Telnet option negotiation and character encoding quirks
- [ ] Negotiate the Mud Client Protocol (MCP) when supported
- [x] Add a developer-local WebSocket echo target and console logging path so Telnet/MUD clients can validate stubbed traffic without a full gateway stack (implemented via the `/dev/echo` WebSocket handler, `TCP_PROXY_DEV_ISOLATED` mode, and the local flows documented in `services/tcp-proxy-service/README.md` and the TCP Proxy design’s **Local development and echo loop** section)
- [x] Document the lightweight cross-service harness (`TelnetGatewayGameSessionCrossServiceIntegrationTest` + `stub/GatewayStubApplication`) so developers can quickly spin up the Telnet → Gateway → Game Session flow without the full gateway stack.

## Connection Management

- [x] Buffer Telnet input in memory while connected and discard on disconnect
- [x] Initialize `TcpProxyServiceApplication` with Netty server (implement connection pipeline)
- [x] Enforce Telnet protocol command whitelist and input sanitization
- [ ] Support PROXY protocol from a Telnet edge proxy (HAProxy) so the TCP Proxy Service can recover the real client IP address in Kubernetes SNAT scenarios and set `X-Proxy-Client-IP` on its internal WebSocket hop accordingly (Spring Cloud Gateway then sets canonical `X-Client-IP`). This task is complete when the dedicated PROXY-protocol listener (see `TCP_PROXY_PROXY_PROTOCOL_PORT` in the TCP Proxy design) parses PROXY headers, the recovered client IP is consistently propagated via `X-Proxy-Client-IP`, appropriate metrics are emitted, and the Implementation Status row “Telnet client IP preservation via PROXY protocol” in the TCP Proxy design can be updated to reflect full implementation.
- [ ] Implement connection throttling and rate limits
- [ ] Integrate `NotifyDisconnect` with the Reconnection Strategy as a best-effort, idempotent hint to support session resumption, matching the invariants described in the TCP Proxy and Reconnection design docs

## Security

- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime
- [ ] Add advanced Telnet abuse detection heuristics and command filtering
- [ ] Extend MCP and abuse observability metrics once Mud Client Protocol (MCP) support lands: add counters for dropped MCP control lines vs Telnet negotiation commands, per-reason breakdowns for discarded Telnet traffic (for example `reason="unsupported_option"`, `reason="malformed_envelope"`, `reason="buffer_overflow"`), and update the logging/monitoring docs so operators can distinguish legacy client quirks from truly abusive patterns.

## Scalability

- [ ] Implement auto-scaling policies for heavy traffic bursts

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise. For the shared checklist, see `design/project-management/reusable-microservice-checklist.md`. TCP Proxy still follows those conventions for health checks and gRPC wiring but skips the database items and does not initiate outbound gameplay gRPC calls to other services.

## Project Setup & CI

- [x] Register the module in `settings.gradle.kts` and apply the `java` plugin
- [x] Add a minimal Spring Boot application with `PingController` and gRPC `PingService` *(already implemented for Gateway and TCP Proxy)*
- [x] Provide a `Dockerfile` and Gradle task to build the image
- [x] Create `README.md` with local setup instructions and design links
- [ ] Ship dev-friendly gRPC TLS defaults (sample certs or plaintext toggle) so the proxy starts locally without provisioning certificates
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
- [x] *(N/A - stateless service)* define JPA entities, repositories, and Flyway migrations with `tenantId` filtering

---

## Authentication & Authorization

- [x] *(N/A - proxy does not process JWTs)* Meta and admin services validate JWTs using helpers from `firemud-common`
- [x] *(N/A - proxy performs no role checks)* Check `globalRoles` and `scopedRoles` where applicable
- [x] Gameplay services rely on the Game Session Service for session validation

---

## Inter-Service Communication

- [x] Use `firemud-common` protobuf types for shared messages
- [x] *(events-only gRPC)* Map errors to `ErrorDetail` with appropriate gRPC status codes
- [x] *(N/A - no service discovery)* Register with service discovery via helpers in `firemud-common`
- [x] *(server-only; no outbound gRPC client calls)* Ensure any gRPC endpoints use mTLS certificates issued by cert-manager
- [x] *(server-only; no outbound gRPC client calls)* Internal gRPC traffic terminates directly on this service (Gateway not involved)

---

## Shared Library Integration

- [x] Depend on `firemud-common` via Gradle
- [x] Apply logging, tracing, and security interceptors from the library
- [x] Use provided autoconfiguration classes to reduce boilerplate
- [x] *(N/A - stateless service)* Reuse `DatabaseAutoConfiguration` and `RedisProperties` for environment setup

---

## Saga Participation *(if used)*

- [x] *(N/A - stateless proxy)* Use saga helpers from `firemud-common` for workflow steps
- [x] *(N/A - stateless proxy)* Emit metrics and correlation IDs for compensation and retries
- [x] *(N/A - stateless proxy)* Document saga participation in `design/README.md`

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
- [x] *(N/A - stateless service)* Seed minimal test data for local workflows
- [x] Run `./gradlew check` in CI to execute all tests
- [x] *(When workflows span services)* add cross-service integration tests

---

## Observability & Tracing

- [x] Use Micrometer for Prometheus metrics
- [x] Enable OpenTelemetry tracing
- [x] Use shared interceptors to propagate `traceId` and `correlationId`
- [x] *(N/A - no Redis)* Emit service metrics for ticks and Redis commands when relevant
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
