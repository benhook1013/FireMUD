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

## Example Usage

The library provides `ApiResponse` and other helpers. Controllers typically return:

```java
return ResponseEntity.ok(ApiResponse.success(data));
```

For structured logging, obtain service-specific loggers via `LoggingUtil`:

```java
private static final Logger logger = LoggingUtil.getLogger(MyClass.class);
```

`JwtUtil` offers helper methods for creating and verifying JWT tokens.

See the [design document](../../design/architecture/system-architecture-shared-libraries.md) for more details and additional utilities.
