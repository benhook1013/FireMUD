# 📚 Shared Libraries Overview

FireMUD's microservices share a set of utility classes and data transfer objects so each service can stay lightweight and consistent. The common library is published as a Gradle artifact and reused by all modules. It is released under the **group ID** `net.firedevops.firemud.shared` with the **artifact ID** `firemud-common`.

---

## 📖 Common DTOs & Error Handling

These classes define the basic request/response shapes recommended in [AI Project Rules](../project-management/ai-rules-local.md):

- **`ApiResponse<T>`** – Standard wrapper returned by controllers with `success()` and `error()` helpers.
- **`ResultStatus`** – Enum used by `ApiResponse` (`SUCCESS` / `ERROR`).
- **`ErrorDetail`** – Structured error information for validation problems or failed operations.
- **`GlobalExceptionHandler`** – Captures exceptions and converts them into `ApiResponse<ErrorDetail>` objects.

DTO records for common tasks (paging, IDs, basic metadata) live here so services share a consistent contract.

---

## 🔧 Utility Packages

- **Logging Utilities** – SLF4J wrappers and helpers for correlation IDs.
- **Security Utilities** – JWT creation/verification and role helpers aligned with the [Authentication Design](./system-architecture-authentication.md).
- **Database Connectors** – Spring Boot configuration helpers for PostgreSQL and Redis, reducing boilerplate setup.
- **Service Discovery & Config** – Central location for discovering other services and handling environment properties.
- **Spring Boot Starter** – Lightweight autoconfiguration for logging, JWT, Redis and PostgreSQL so services can opt in.
- **gRPC Types** – Shared definitions (e.g., `ErrorDetail`, `PagingRequest`) in `protos/shared/`; each service generates its own stubs.

---

## 🚚 Publishing Strategy

The shared code is built as a **Gradle Java library** and published to **GitHub Packages** so all services can depend on it.

1. Define a Gradle module (e.g., `common-library`) with the `java-library` plugin.
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
4. Automate tagging and version bumps using `semantic-release`.
5. Deploy artifacts to GitHub Packages via CI/CD. If needed, publish a separate `firemud-protos` artifact containing only the shared gRPC definitions.

This library aligns with the [Common Package](../project-management/task-list.md#phase-1-core-infrastructure--basic-services) tasks and keeps code reuse simple across all FireMUD services.
