# Shared Libraries Overview

FireMUD's microservices share a set of utility classes and data transfer objects so each service can stay lightweight and consistent. The common library is published as a Gradle artifact and reused by all modules. It is released under the **group ID** `net.firedevops.firemud` with the **artifact ID** `firemud-common`. The Gradle subproject lives under `services/common-library`.

---

## Common DTOs & Error Handling

These classes define the basic request/response shapes recommended in [AI Project Rules](../project-management/ai-rules-local.md):

- **`ApiResponse<T>`** – Standard wrapper returned by controllers with `success()` and `error()` helpers.
- **`ResultStatus`** – Enum used by `ApiResponse` (`SUCCESS` / `ERROR`).
- **`ErrorDetail`** – Structured error information for validation problems or failed operations.
- **`GlobalExceptionHandler`** – Captures exceptions and converts them into `ApiResponse<ErrorDetail>` objects.

DTO records for common tasks (paging, IDs, basic metadata) live here so services share a consistent contract.

---

## Utility Packages

- **Logging Utilities** – `LoggingUtil` is a thin SLF4J wrapper. The
  `LoggingInterceptor` and `SagaRunner` attach a `correlationId` using MDC so logs
  from different services can be correlated.
- **Security Utilities** – `JwtUtil` for verifying tokens (and building them
  within the Account Service only) plus `AuthTokenInterceptor`,
  `SessionContext`, `ReloadableJwtUtil`, and `RequireAdminRole` helpers for
  centrally enforcing JWT-based roles and supporting secret rotation. See the
  [Authentication Design](./system-architecture-authentication.md).
- **Database Connectors** – `DatabaseAutoConfiguration` with `PostgresProperties` and `RedisProperties` reduces boilerplate setup. Defaults suit Docker Compose but any field can be overridden with `FIREMUD_POSTGRES_*` or `FIREMUD_REDIS_*` environment variables.
- **gRPC Interceptors** – `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` provide consistent instrumentation and OpenTelemetry spans for every service. `LoggingInterceptor` automatically records the current `traceId` and `correlationId`, generating a new correlation ID when one is not present.
- **Tracing Configuration** – `TracingConfig` exports spans to the collector using the `otel.endpoint` property and sets the `service.name` from `spring.application.name`.
- **Service Discovery & Config** – Central location for discovering other services and handling environment properties.
- `ServiceEndpointsProperties` loads the base URLs for each microservice and is enabled by `CommonAutoConfiguration`. It reads variables prefixed with `FIREMUD_SERVICES_` (see [Environment & Secrets](./infrastructure/environment-and-secrets.md#service-discovery)) to build endpoint URLs. The Spring Cloud Gateway uses these variables for dynamic routing.
- **Spring Boot Starter** – Provides `DatabaseAutoConfiguration` for
  PostgreSQL/Redis and `CommonAutoConfiguration` for shared service properties.
  Logging and JWT helpers are available but are configured manually.
- **Conflict Tracking** – `ConflictTracker` and `RedisConflictTracker` record
  tick conflicts in Redis for hotspot detection.
- **TLS & Secret Watchers** – `GrpcServerTlsReloader`, `TlsCertificateWatcher`, and `JwtSecretWatcher` reload certificates and JWT secrets without restarting the service. Client stubs already use `TlsCertificateWatcher`, while `GrpcServerTlsReloader` integrates with the running servers. These watchers monitor the paths from `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`, and `FIREMUD_AUTH_JWT_SECRET_PATH`. See [Environment & Secrets](./infrastructure/environment-and-secrets.md#grpc-tls-certificates) and [Authentication](./infrastructure/environment-and-secrets.md#authentication) for details.
- **gRPC Types** – Shared definitions (e.g., `ErrorDetail`, `PagingRequest`) in `protos/shared/`; each service generates its own stubs.

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

This library aligns with the [Common Package](../project-management/task-list.md#phase-1-core-infrastructure--basic-services) tasks and keeps code reuse simple across all FireMUD services.

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

## Saga Orchestration

The library also provides a lightweight saga engine for multi-step workflows. Flows are defined with `SagaBuilder` and may include compensation actions:

```java
new SagaBuilder()
    .step("createAccount", accountClient::createAccount,
        () -> accountClient.deleteAccount(id))
    .step("provisionCharacter", entityClient::createPlayer)
    .run();
```

Saga state is stored in the bundled `saga_instance` and `saga_step` tables.
These tables live in a shared `saga` schema so migrations only run once across services.
Flyway migrations packaged with the library create these tables automatically.
`SagaRunner` executes the workflow, emitting metrics via `SagaMetrics` and adding a `correlationId` to logs for easier troubleshooting. `SagaMetrics` tracks the number of active sagas so the Logging & Admin Service dashboard can display progress.

## Related Documentation

- [gRPC API Style & Versioning Guidelines](./system-architecture-grpc.md)
- [Transaction Strategies](./system-architecture-transactions.md)
