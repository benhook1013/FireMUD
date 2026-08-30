# Shared Libraries Overview

FireMUD's microservices share a set of narrowly scoped modules so each service can stay lightweight and consistent without inheriting unrelated runtime behavior. Shared artifacts use the **group ID** `net.firedevops.firemud`; the repository's `common-*` modules divide platform core, data runtime, security, saga, Temporal, web, and test concerns. References to a common library describe this family, not a requirement to rebuild one monolithic `firemud-common` artifact.

**Target-state:** Shared DTOs and correlation helpers that carry scripting work must preserve the exact `scriptPatchVersion` plus `scriptPinEpoch` tuple and must not synthesize a local active/latest/fallback pin. The canonical owner is [Scripting Contracts](./system-architecture-scripting-contracts.md); this library document owns only transport/projection reuse and shared error-shape consequences. Linked plugins and embedded scripts use the same DSL/runtime security boundary but keep their distinct artifact and lifecycle metadata as defined by the [DSL lifecycle reference](./system-architecture-scripting-dsl-reference-and-lifecycle.md).

## Implementation Status

The current `EnqueueAutomationCommandIfAbsentRequest` carries `scriptPatchVersion` but not `scriptPinEpoch`, so shared code must not synthesize an absent epoch; exact tuple propagation and final enforcement remain implementation and focused-proof gaps at that boundary. The narrow shared Redis-contract foundation, owner-local descriptor contributions, repository aggregation, ownership enforcement, and descriptor-driven proof required by [ADR 0176](./decisions/adr-0176-owner-local-redis-execution-with-aggregated-contracts.md) are not implemented. See the [Automation and Scheduler Runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status) for current status and proof evidence.

- `common-saga` adoption is not currently explicit: base service conventions add the dependency broadly, and deployed service configurations expose the Saga Flyway location even for services without a Saga workflow. This can materialize shared Saga tables and repositories outside the adopter boundary; the explicit adopter allowlist and focused conformance proof remain incomplete.

---

## Common DTOs & Error Handling

These classes define FireMUD's basic shared request/response shapes:

- **`ApiResponse<T>`** – Standard wrapper returned by controllers with `success()` and `error()` helpers.
- **`ResultStatus`** – Enum used by `ApiResponse` (`SUCCESS` / `ERROR`).
- **`ErrorDetail`** – Shared structured information used by current response-level errors and by bounded details when an RPC owner selects that representation. The canonical choice between a typed domain result and non-OK gRPC status is defined by the [gRPC outcome and transport classification](./system-architecture-grpc.md#outcome-and-transport-classification), not by this DTO alone.
- **`GlobalExceptionHandler`** – Captures exceptions and converts them into `ApiResponse<ErrorDetail>` objects.

DTO records for common tasks (paging, IDs, basic metadata) live here so services share a consistent contract.

---

## Utility Packages

- **Logging Utilities** – `LoggingUtil` is a thin SLF4J wrapper. The
  `LoggingInterceptor`, `SagaRunner`, and Temporal workflow/activity hosts attach
  correlation-friendly context using MDC so logs from different services can be
  correlated.
- **Security Utilities** – Target-state shared JWT support, including the planned JWKS verifier, verifies Account-published JWKS tokens; Account issues tokens by delegating private-key signing to approved non-exportable signer custody, so no application workload receives private signing material. The checked-in `JwtUtil` currently constructs and verifies HMAC tokens in application code; that is implementation drift, not evidence that the target JWKS verifier or asymmetric `kid` validation is live. Phased overlap remains implementation debt. `AuthTokenInterceptor`, `SessionContext`, `ReloadableJwtUtil`, and `RequireAdminRole` remain shared helpers for centrally enforcing JWT-based roles and supporting rotation. See the [Authentication Design](./system-architecture-authentication.md).
- **Database Connectors** – `DatabaseAutoConfiguration` with `PostgresProperties` and `RedisProperties` reduces boilerplate setup. Defaults suit Docker Compose but any field can be overridden with `FIREMUD_POSTGRES_*` or the Redis role‑specific environment variables. Redis‑backed services choose the appropriate prefix:
  - Coordination clients (ticks, locks, timers, sessions) bind `RedisProperties` to `FIREMUD_REDIS_COORD_HOST` / `FIREMUD_REDIS_COORD_PORT`.
  - Cache/rate‑limit clients (for example Spring Cloud Gateway) bind to `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT`.
- **gRPC Interceptors** – `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` provide consistent instrumentation and OpenTelemetry spans for every service. `LoggingInterceptor` automatically records the current `traceId` and `correlationId`, generating a new correlation ID when one is not present. They observe both typed domain outcomes and canonical non-OK failures; the [gRPC architecture](./system-architecture-grpc.md#outcome-and-transport-classification) owns the response-channel classification.
- **Tracing Configuration** – `TracingConfig` exports spans to the collector using the `otel.endpoint` property and sets the `service.name` from `spring.application.name`.
- **Temporal Workflow Foundation** – `common-temporal` owns the shared Temporal runtime substrate for durable control-plane workflows. It provides `TemporalProperties`, `WorkflowServiceStubs`, `WorkflowClient`, `WorkerFactory`, `TemporalTaskQueueResolver`, `TemporalWorkerHost`, and the shared identity helpers in `FiremudWorkflowIds`. Services opt in through the `net.firedevops.firemud.temporal-conventions` Gradle plugin and contribute `TemporalWorkerRegistrar` beans instead of inventing service-local worker startup loops.
- **Metrics Common Tags** – `CommonAutoConfiguration` attaches a stable `service` tag to all Micrometer meters using `spring.application.name` so shared dashboards and alert rules can scope queries consistently without each call site manually tagging every counter/timer.
- **Service Discovery & Config** – Central location for discovering other services and handling environment properties.
- `ServiceEndpointsProperties` loads the base URLs for each microservice and is enabled by `CommonAutoConfiguration`. It reads variables prefixed with `FIREMUD_SERVICES_` (see [Environment & Secrets](./infrastructure/environment-and-secrets.md#service-discovery)) to build endpoint URLs. The Spring Cloud Gateway uses these variables for dynamic routing.
- **Spring Boot Starter** – Provides `DatabaseAutoConfiguration` for
  PostgreSQL/Redis and `CommonAutoConfiguration` for shared service properties.
  Logging and JWT helpers are available but are configured manually.
- **Conflict Tracking** – `ConflictTracker` and `RedisConflictTracker` record
  tick conflicts in Redis for hotspot detection.
- **TLS & Secret Watchers** – `GrpcServerTlsReloader` and `TlsCertificateWatcher` reload certificates without restarting the service. `JwtSecretWatcher` can detect changes to Account's `FIREMUD_AUTH_JWT_SECRET_PATH`, but the target signer must validate and atomically promote a complete signing generation only after its public JWK has converged; validators never use this private-key watcher. Client stubs already use `TlsCertificateWatcher`, while `GrpcServerTlsReloader` integrates with the running servers. HTTP and WebSocket clients that require mTLS may reuse the watcher mechanics, but endpoint credential files and identities remain distinct: service/workload gRPC clients use `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH`; the TCP Proxy → Gateway WebSocket bridge uses `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, and `FIREMUD_GATEWAY_WS_CA_CERT_PATH`; and Gateway HTTP management tooling uses the dedicated `FIREMUD_GATEWAY_HTTP_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_HTTP_CLIENT_PRIVATE_KEY_PATH`, and `FIREMUD_GATEWAY_HTTP_CA_CERT_PATH`. These endpoint identities and trust bundles must not be silently reused across surfaces. See [Environment & Secrets](./infrastructure/environment-and-secrets-catalog.md#tls--certificates), [Gateway HTTP management TLS](./infrastructure/environment-and-secrets-catalog.md#gateway-http-management-plane-tls-target-state), [Authentication](./infrastructure/environment-and-secrets.md#authentication), and [TCP Proxy WebSocket configuration](./microservices/tcp-proxy-service/configuration.md#websocket-mtls-to-spring-cloud-gateway) for details.
- **gRPC Types** – Shared definitions (e.g., `ErrorDetail`, `PagingRequest`) in `protos/shared/`; each service generates its own stubs.

### Redis Key Naming & Lua Script Helpers

Target-state Redis helpers must follow the owner-local execution and aggregated-contract boundary in [ADR 0176](decisions/adr-0176-owner-local-redis-execution-with-aggregated-contracts.md); this boundary is not implemented in the current tree.

The target narrow shared Redis-contract module would own the descriptor schema, common outcome types, Redis-role and prefix-owner rules, hash-tag/cluster-slot validation, compatibility metadata rules, and repository registry/test aggregation. It would own executable key builders, invocation machinery, or Lua only for mutations genuinely executed by multiple independently deployed callers.

For an exclusively owned key family, the target contract requires the owning service to contribute descriptors through the shared schema while retaining its generated or typed key builders, invocation adapter, executable Lua source, and semantic tests. Direct string concatenation remains forbidden where a declared builder exists. Non-owner services must not gain executable access to private keyspaces merely by depending on the shared schema.

Once implemented, the aggregated registry and CI harness must validate that:

- every Lua source and owned key family has one descriptor and owner;
- `KEYS`/`ARGV` order, allowed prefixes, Redis role, outcome codes, reset sensitivity, tail-loss behavior, and compatibility metadata are complete;
- every multi-key invocation uses the declared hash tag and resolves to one Redis Cluster slot;
- session scripts declared single-key remain single-key and bounded script-key limits remain enforced;
- scripts cannot mix owners or Coordination and Cache key families;
- caller adapters and focused tests match the registered descriptor and supported coexistence set.

Operator visibility would come from this catalog and owner APIs. Supported tooling must normally request mutations through the owning service; if it must execute the same Lua directly, that mutation becomes a genuinely shared contract and moves into the shared module.

For Redis-backed caches and rate limiting, the common library may also provide:

- **Bounded cache writer utilities** – Helpers that:
  - Enforce a maximum serialized value size (for example `MAX_CACHE_VALUE_BYTES`) before writing to Redis, rejecting oversized payloads with clear logs/metrics instead of allowing them to bloat memory.
  - Require explicit TTL parameters and validate that they fall within configured per-key budgets, so caches cannot silently accumulate effectively permanent entries.
  - Prefer single atomic commands (set value + TTL together) over multi-step delete/insert sequences.

Services that add new tick, retry, timer, or session flows first add their owner-scoped descriptor contribution and then implement through generated or typed owner-local helpers. Repository aggregation keeps key shapes, hash-tag rules, outcomes, and Lua invocation behavior consistent without globalizing owner-exclusive execution.

---

## Publishing Strategy

Shared code is organized as the narrowly scoped Gradle modules currently included by `settings.gradle.kts`: `common-data-runtime`, `common-platform-core`, `common-saga`, `common-security`, `common-temporal`, `common-test-support`, and `common-web-support`. The target boundary is that each service depends only on the modules needed for its contract and never recreates a monolithic `common-library` or `firemud-common` artifact; the broad current `common-saga` wiring described in [Implementation Status](#implementation-status) has not yet converged on that boundary.

The versioned definitions under `protos/` remain the source for generated service stubs; they are not a replacement for the split Java modules. If a module is published for a supported distribution workflow, it retains its module-specific coordinates under the `net.firedevops.firemud` group rather than being folded into one shared runtime artifact.

Redis contracts follow [ADR 0176](decisions/adr-0176-owner-local-redis-execution-with-aggregated-contracts.md): a future narrow foundation such as `common-redis-contracts` may carry descriptor schemas, bounded outcomes, role/owner/slot validation, and repository aggregation. Owner-exclusive Redis builders, adapters, executable Lua, and semantic tests remain in the owning service. The foundation is not evidence that this target module or its registry has been implemented.

This split-module boundary aligns with the [Shared Runtime, Service Contracts, and Persistence implementation tracking](../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md) record and keeps reuse explicit without globalizing owner-exclusive execution.

## Example Usage

Controllers typically return results wrapped with `ApiResponse`:

```java
return ResponseEntity.ok(ApiResponse.success(data));
```

Structured logging is available via `LoggingUtil`:

```java
private static final Logger logger = LoggingUtil.getLogger(MyClass.class);
```

`JwtUtil` is a target-state shared verification helper for Account-published JWKS. The checked-in `JwtUtil` currently constructs and verifies HMAC tokens; that implementation is current drift and must not be described as live JWKS verification. Target Account issuance delegates private-key operations to approved non-exportable signer custody rather than exporting signing material or making shared utilities token issuers.

## Short Synchronous Saga Orchestration

The shared `common-saga` module provides a lightweight short synchronous orchestration helper for multi-step workflows that can complete in one caller-owned execution path. Flows are defined with `SagaBuilder` and may include compensation actions:

```java
new SagaBuilder()
    .step("createAccount", accountClient::createAccount,
        () -> accountClient.deleteAccount(id))
    .step("provisionCharacter", entityClient::createPlayer)
    .run();
```

Saga state is stored in the bundled `saga_instance` and `saga_step` tables. At target, these tables exist only in each explicit adopter's own schema (for example `${serviceSchema}.saga_instance` and `${serviceSchema}.saga_step`) rather than a separate dedicated `saga` schema or a non-adopter schema. Flyway migrations packaged with `common-saga` are exposed as `classpath:db/migration/saga` and run alongside the owning adopter's local `classpath:db/migration` chain; current convention wiring exposes that location more broadly, as recorded in [Implementation Status](#implementation-status).
`SagaRunner` executes the orchestration inline, emitting metrics via `SagaMetrics` and adding a `correlationId` to logs for easier troubleshooting. `SagaMetrics` tracks the number of active synchronous saga executions so the Logging & Admin Service dashboard can display progress.

`common-saga` is not FireMUD's durable workflow engine. Long-running control-plane workflows that need restart-safe continuation, durable waits, or operator-visible runtime state use `common-temporal` instead. The placement matrix and adopter proof requirement are owned by [Transaction Strategies](./system-architecture-transactions.md#mandatory-workflow-adopter-classification); this section records only the shared module and adopter-local runtime consequences.

## SQL Persistence Direction

The complete SQL persistence, schema-ownership, compatibility-gate, and identifier-migration contract is owned by [Database Migrations](./system-architecture-database-migrations.md). This library records only the reusable/adopter-local consequences. FireMUD’s SQL-backed services use `jOOQ + Flyway` as the canonical persistence stack, and shared-library work in this area should optimize for:

- explicit SQL generation and execution;
- shared transaction and error-translation helpers where the value is truly cross-service;
- common pagination, filtering, and mapping conventions for control-plane and runtime SQL reads;
- one schema authority through Flyway rather than service-local ORM interpretations.

Shared libraries should not reintroduce Hibernate/JPA runtime assumptions or a second schema authority; the canonical SQL helper surface now targets `jOOQ + Flyway` only. A PostgreSQL-specific plain-SQL exception remains service-local, narrow, and proof-bearing under the central contract.

The first shared substrate is the `net.firedevops.firemud.jooq-conventions` build path, which generates service-local DSL code directly from Flyway-owned SQL and adds only the minimal shared runtime wiring needed to compile and adopt `DSLContext`. Broader runtime helpers should be added only when multiple migrated services prove the same paging/filter/sort, transaction, or error-translation concern is truly repeated.

## Related Documentation

- [gRPC API Style & Versioning Guidelines](./system-architecture-grpc.md)
- [Transaction Strategies](./system-architecture-transactions.md)
