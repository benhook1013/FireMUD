# FireMUD Common Library

This module contains shared utilities and DTOs used by all FireMUD microservices. The full design documentation lives under [Shared Libraries Overview](../../design/architecture/system-architecture-shared-libraries.md).

## Adding the Dependency

In a service's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":common-library"))
}
```

When published to a repository, include it via `implementation("net.firedevops.firemud.shared:firemud-common:<version>")`.

### Publishing

The library is published to the GitHub Packages registry. Provide `GITHUB_ACTOR` and `GITHUB_TOKEN` when running:

```bash
./gradlew :common-library:publish
```

Artifacts are uploaded under `net.firedevops.firemud:firemud-common`.

## Example Usage

The library provides `ApiResponse` and other helpers. Controllers typically return:

```java
return ResponseEntity.ok(ApiResponse.success(data));
```

For structured logging, obtain service-specific loggers via `LoggingUtil`:

```java
private static final Logger logger = LoggingUtil.getLogger(MyClass.class);
```

`JwtUtil` provides helpers for verifying JWT tokens and building new ones for
the Account Service. Other services use it only for validation when executing
admin or control operations.

See the [design document](../../design/architecture/system-architecture-shared-libraries.md) for more details and additional utilities.

## Saga Orchestration

The library includes a lightweight saga engine for multi-step workflows across services.
Define flows using `SagaBuilder` and provide optional compensation actions:

```java
new SagaBuilder()
    .step("createAccount", accountClient::createAccount,
        () -> accountClient.deleteAccount(id))
    .step("provisionCharacter", entityClient::createPlayer)
    .run();
```

Saga state is stored in the `saga_instance` and `saga_step` tables provided in the
library's Flyway migrations.
