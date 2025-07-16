# 📦 FireMUD System Architecture: gRPC API Style & Versioning Guidelines

These guidelines define how FireMUD microservices design and document their gRPC APIs. Following a consistent structure makes it easier for teams to evolve services over time and share tooling.

## ✅ Service and RPC Naming

- Use **PascalCase** for all service names (e.g., `PlayerService`).
- RPC method names may use either:
  - Standard CRUD verbs like **Get**, **List**, **Create**, **Update**, **Delete**.
  - Domain‑specific actions such as `CastSpell`, `MoveToRoom`, or `ApplyEffect`.
- Avoid vague or overloaded verbs like `Execute`, `Process`, or `Do`.
- Always define explicit `Request` and `Response` messages, even if they are empty.

## ✅ Versioning Strategy

- Declare the API version in the package name inside the proto file:

  ```proto
  package player.v1;
  ```

- Mirror the version in the directory layout:

  ```text
  protos/player/v1/player_service.proto
  ```

## 📁 Proto File Layout

All protobuf definitions live in a top‑level `protos/` directory outside service source trees. Files are organized by service folder and versioned subdirectory:

```text
protos/
  player/
    v1/
      player_service.proto
      player_types.proto
  entity/
    v1/
      entity_service.proto
      entity_types.proto
  shared/
    v1/
      errors.proto
      common_types.proto
```

Each service folder typically includes:

- `*_service.proto` — defines the gRPC service and its RPC methods
- `*_types.proto` — defines request/response messages and shared types
- Optional `*_events.proto` — server-side streaming RPCs for event notifications (no separate message bus) *(TODO: Not yet implemented)*

Shared message types (for example `EntitySummary` or `ErrorDetail`) live under `protos/shared/v1/` so they version alongside all other APIs.

Directory names may use hyphens (for example `game-design`), while proto packages use underscores (`gamedesign.v1`) so that package declarations remain valid.

All proto files use `syntax = "proto3"` and set `java_package` and `java_multiple_files` options so Java packages remain consistent across services.

## 🛠️ Tooling

- **Buf** ([buf.yaml](../../protos/buf.yaml)) — Lints proto files, detects breaking changes, and drives code generation. The repository stores this configuration under `protos/`. The workspace file [buf.work.yaml](../../buf.work.yaml) and [buf.gen.yaml](../../buf.gen.yaml) specify modules and plugins for generation.
- **`protoc-gen-grpc-java`** — Generates Java service stubs for gRPC communication. The generated code is included in service builds via Gradle.
- **`protoc-gen-doc`** — Produces HTML or Markdown API documentation to encourage inline comments. *(TODO: Not yet implemented)*

## 🚦 Shared Interceptors

Every gRPC service registers the `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` from the [Shared Libraries](./system-architecture-shared-libraries.md). These interceptors add trace identifiers to logs, record request metrics, and create OpenTelemetry spans so observability is consistent across services.

## 🔄 Schema Evolution Rules

- Never reuse or remove field numbers — use `reserved` to prevent reuse.
- Only **add optional fields** or new enum values to avoid breaking compatibility.
- Use the `reserved` keyword to block deprecated field numbers or names.
- Avoid changing the type of an existing field.

## ⚠️ Error Handling

- Map application-level failures to appropriate gRPC status codes (`INVALID_ARGUMENT`, `NOT_FOUND`, etc.).
- Use a shared `ErrorDetail` message (e.g., `shared/errors.proto`) when returning rich error info.
- Prefer returning structured errors over using gRPC metadata for application faults.
- All RPCs that can fail should include an `ErrorDetail` field in the response instead of invoking `onError()`. Wrap response observers or use an interceptor to log warnings, increment a `grpc.app_error` metric labeled with `error.code`, and tag tracing spans. `onError()` is reserved for transport-level or infrastructure failures.
  See [AI Project Rules](../project-management/ai-rules-local.md) for required logging and metrics interceptors.

Example implementation:

```java
private ErrorDetail error(String code, String message) {
  meterRegistry.counter("grpc.app_error", "code", code).increment();
  return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
}
```

## 🧪 Example Code Generation (Java)

Services use **Buf** for linting and schema enforcement, while source generation
is handled by the Gradle `com.google.protobuf` plugin:

```kotlin
protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.31.1" }
  plugins {
    id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.73.0" }
  }
  generateProtoTasks {
    ofSourceSet("main").forEach { it.plugins { id("grpc") } }
  }
}

sourceSets["main"].java.srcDirs(
  "build/generated/source/proto/main/java",
  "build/generated/source/proto/main/grpc"
)
```

Running `./gradlew generateProto` compiles stubs into `build/generated/source/proto` for each service.

## 🔒 TLS Requirements

All internal gRPC calls use **mutual TLS**. Each service sets the following environment variables so certificates can be mounted from Secrets or local files:

| Variable | Description |
| -------- | ----------- |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Path to the service certificate chain |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Path to the private key |
| `FIREMUD_GRPC_CA_CERT_PATH` | CA bundle used to verify peers |

The [Environment & Secrets](./infrastructure/environment-and-secrets.md#grpc-tls-certificates) guide describes how these values are provided. The shared library includes a [`GrpcServerTlsReloader`](./system-architecture-shared-libraries.md) component that hot reloads certificates when they change.

Adopting these conventions helps keep FireMUD services consistent and makes it easier for new contributors to work with the APIs. See [Security Architecture](./system-architecture-security.md#🤝-cross-service-trust) for mTLS design.

## 📚 Related Documentation

- [Microservices Overview](./microservices/README.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
