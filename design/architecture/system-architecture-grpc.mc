# 📦 gRPC API Style & Versioning Guidelines

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
    errors.proto
    common_types.proto
```

Each service folder typically includes:

- `*_service.proto` — defines the gRPC service and its RPC methods
- `*_types.proto` — defines request/response messages and shared types
- Optional `*_events.proto` — streaming or pubsub interfaces

Shared message types (for example `EntitySummary` or `ErrorDetail`) live under `protos/shared/`.

## 🛠️ Tooling

- **Buf** (`buf.yaml`) — Lints proto files, detects breaking changes, and drives code generation.
- **`protoc-gen-grpc-java`** — Generates Java service stubs for gRPC communication. The generated code is included in service builds via Gradle or a Makefile.
- **`protoc-gen-doc`** — Produces HTML or Markdown API documentation to encourage inline comments.

## 🔄 Schema Evolution Rules

- Never reuse or remove field numbers — use `reserved` to prevent reuse.
- Only **add optional fields** or new enum values to avoid breaking compatibility.
- Use the `reserved` keyword to block deprecated field numbers or names.
- Avoid changing the type of an existing field.

## ⚠️ Error Handling (Optional)

- Map application-level failures to appropriate gRPC status codes (`INVALID_ARGUMENT`, `NOT_FOUND`, etc.).
- Use a shared `ErrorDetail` message (e.g., `shared/errors.proto`) when returning rich error info.
- Prefer returning structured errors over using gRPC metadata for application faults.

## 🧪 Example Code Generation (Java)

Using `buf.gen.yaml`:

```yaml
version: v1
plugins:
  - name: java
    out: gen/java
    opt: lite
  - name: grpc-java
    out: gen/java
```

Then compile generated sources via Gradle:

```kotlin
sourceSets["main"].java.srcDirs("gen/java")
```

Adopting these conventions helps keep FireMUD services consistent and makes it easier for new contributors to work with the APIs.
