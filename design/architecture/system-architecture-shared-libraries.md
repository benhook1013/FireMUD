# Shared Libraries Overview

FireMUD's microservices share a set of utility classes and data transfer objects so each service can stay lightweight and consistent. The common library is published as a Gradle artifact and reused by all modules. It is released under the **group ID** `net.firedevops.firemud` with the **artifact ID** `firemud-common`. The Gradle subproject lives under `services/common-library`.

**Target-state:** Shared DTOs and correlation helpers that carry scripting work must preserve the exact `scriptPatchVersion` plus `scriptPinEpoch` tuple and must not synthesize a local active/latest/fallback pin. The canonical owner is [Scripting Contracts](./system-architecture-scripting-contracts.md); this library document owns only transport/projection reuse and shared error-shape consequences. Linked plugins and embedded scripts use the same DSL/runtime security boundary but keep their distinct artifact and lifecycle metadata as defined by the [DSL lifecycle reference](./system-architecture-scripting-dsl-reference-and-lifecycle.md).

## Implementation Status

The current `EnqueueAutomationCommandIfAbsentRequest` carries `scriptPatchVersion` but not `scriptPinEpoch`, so shared code must not synthesize an absent epoch; exact tuple propagation and final enforcement remain an implementation gap at that boundary.

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
- **Security Utilities** – Target-state `JwtUtil` support verifies Account JWKS tokens (and builds them within the Account Service only) plus `AuthTokenInterceptor`, `SessionContext`, `ReloadableJwtUtil`, and `RequireAdminRole` helpers for centrally enforcing JWT-based roles and supporting secret rotation. The current utility still uses shared HMAC material and immediate one-key replacement; asymmetric `kid`/JWKS validation and phased overlap remain implementation debt. See the [Authentication Design](./system-architecture-authentication.md).
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
- **TLS & Secret Watchers** – `GrpcServerTlsReloader` and `TlsCertificateWatcher` reload certificates without restarting the service. `JwtSecretWatcher` can detect changes to Account's `FIREMUD_AUTH_JWT_SECRET_PATH`, but the target signer must validate and atomically promote a complete signing generation only after its public JWK has converged; validators never use this private-key watcher. Client stubs already use `TlsCertificateWatcher`, while `GrpcServerTlsReloader` integrates with the running servers. HTTP and WebSocket clients that require mTLS (such as the TCP Proxy’s WebSocket connection to Spring Cloud Gateway) also reuse `TlsCertificateWatcher` and the same `FIREMUD_GRPC_*` paths. See [Environment & Secrets](./infrastructure/environment-and-secrets.md#grpc-tls-certificates) and [Authentication](./infrastructure/environment-and-secrets.md#authentication) for details.
- **gRPC Types** – Shared definitions (e.g., `ErrorDetail`, `PagingRequest`) in `protos/shared/`; each service generates its own stubs.

### Redis Key Naming & Lua Script Helpers

Tick coordination and other Redis-backed workflows rely on a small set of shared helpers provided by the common library:

- **Key Naming helpers** – A `RedisKeyNaming` (or similarly named) utility centralizes construction of tick-related keys such as `tick:{tenantRegionTag}:lock:<entityId>`, `tick:{tenantRegionTag}:pending`, `retry:{tenantRegionTag}`, and `timer:{tenantRegionTag}`. Application code must build these keys exclusively through the helper; direct string concatenation of `tick:`, `retry:`, or `timer:` prefixes in services is discouraged so hash-tag and naming rules remain consistent. The helper enforces the `{tenantRegionTag}` hash tag and is the single source of truth for tick key shapes.
- **Lua scripts, descriptors, and invocation helpers** – The common library owns:
  - All coordination-related Lua scripts (tick staging/commit/cleanup, locks, timers, retries, session CAS, automation scheduling) under a shared `redis/` resources path.
  - Machine-readable script descriptors that declare `KEYS` order, allowed prefixes, and shard-locality for each script.
  - A small Redis/Lua helper class that wraps `EVALSHA` calls for tick/session/automation scripts. It ensures that:
  - Scripts are invoked with the correct first key (a tick key with the `{tenantRegionTag}` hash tag).
  - `NOSCRIPT` errors are handled by reloading the script on the appropriate master and retrying once.
  - Callers pass keys built via `RedisKeyNaming` so multi-key operations stay shard-local.
- **Test and lint hooks** – Shared test fixtures validate that:
  - Keys produced by `RedisKeyNaming` share a common hash tag substring and map to the same cluster slot for multi-key operations.
  - Lua scripts respect the configured `MAX_TICK_SCRIPT_KEYS` bound and do not introduce “just one more key” without extending tests.
  - Session scripts are declared as **single-key** in their descriptors; tests assert that their `KEYS` length is exactly one and fail fast if additional keys are introduced without an explicit design change.
  - Automation scripts are forbidden from mixing `automation:*` keys with `tick:{tenantRegionTag}:*` keys in a single invocation; tests assert slot alignment for any multi-key automation operations.
  - Prefix discipline is enforced consistently: tick scripts are allowed only tick/coordination prefixes (`tick:`, `retry:`, `timer:`, `remote:`), session scripts only `session:`, and automation scripts only documented Automation-owned prefixes such as `automation:queue:`. CI lints Lua sources against these rules so mixed `tick:*` + `automation:*` or `tick:*` + `session:*` scripts cannot be added inadvertently.
  - Any new tick-related script or key path added by a service includes corresponding updates to the shared scripts, descriptors, helpers, and tests in `firemud-common`; individual services do not define their own independent copies.

For Redis-backed caches and rate limiting, the common library may also provide:

- **Bounded cache writer utilities** – Helpers that:
  - Enforce a maximum serialized value size (for example `MAX_CACHE_VALUE_BYTES`) before writing to Redis, rejecting oversized payloads with clear logs/metrics instead of allowing them to bloat memory.
  - Require explicit TTL parameters and validate that they fall within configured per-key budgets, so caches cannot silently accumulate effectively permanent entries.
  - Prefer single atomic commands (set value + TTL together) over multi-step delete/insert sequences.

Services that add new tick, retry, timer, or session flows should extend the common library’s key helpers and script helpers first, then use those helpers from their own code. This keeps Redis key shapes, hash-tag rules, and Lua invocation behavior consistent across the platform.

---

## Publishing Strategy

The shared code is built as a **Gradle Java library** and published to **GitHub Packages** so all services can depend on it.

1. Define a Gradle module (e.g., `common-library`) with the `java-library` plugin. A separate `protos` subproject publishes the `firemud-protos` artifact containing all shared `.proto` files.
2. Configure publishing to GitHub Packages using `maven-publish`:

   ```kotlin
   publishing {
       repositories {
           maven {
               name = "github"
               url = uri("https://maven.pkg.github.com/<org>/firemud")
               credentials {
                   username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
                   password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
               }
           }
       }
   }
   ```

3. Version releases using semantic versioning (e.g., `1.0.0`) and publish from CI.
4. Automate tagging and version bumps using `release-please`.
5. Deploy both `firemud-common` and `firemud-protos` artifacts to GitHub Packages via CI/CD.

This library aligns with the shared-platform direction recorded in [Shared Runtime, Service Contracts, and Persistence implementation tracking](../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md) and keeps code reuse simple across all FireMUD services.

## Example Usage

Controllers typically return results wrapped with `ApiResponse`:

```java
return ResponseEntity.ok(ApiResponse.success(data));
```

Structured logging is available via `LoggingUtil`:

```java
private static final Logger logger = LoggingUtil.getLogger(MyClass.class);
```

`JwtUtil` helps verify tokens and is used by the Account Service when issuing new ones.

## Short Synchronous Saga Orchestration

The shared `common-saga` module provides a lightweight short synchronous orchestration helper for multi-step workflows that can complete in one caller-owned execution path. Flows are defined with `SagaBuilder` and may include compensation actions:

```java
new SagaBuilder()
    .step("createAccount", accountClient::createAccount,
        () -> accountClient.deleteAccount(id))
    .step("provisionCharacter", entityClient::createPlayer)
    .run();
```

Saga state is stored in the bundled `saga_instance` and `saga_step` tables.
These tables live in each adopting service's own schema (for example `${serviceSchema}.saga_instance` and `${serviceSchema}.saga_step`) rather than a separate dedicated `saga` schema.
Flyway migrations packaged with `common-saga` are exposed as `classpath:db/migration/saga` and run alongside the owning service's local `classpath:db/migration` chain.
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
