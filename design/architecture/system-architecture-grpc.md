# FireMUD System Architecture: gRPC API Style & Versioning Guidelines

These guidelines define how FireMUD microservices design and document their gRPC APIs. Following a consistent structure makes it easier for teams to evolve services over time and share tooling.

## Service and RPC Naming

- Use **PascalCase** for all service names (e.g., `PlayerService`).
- RPC method names may use either:
  - Standard CRUD verbs like **Get**, **List**, **Create**, **Update**, **Delete**.
  - Domain‑specific actions such as `CastSpell`, `MoveToRoom`, or `ApplyEffect`.
- Avoid vague or overloaded verbs like `Execute`, `Process`, or `Do`.
- Always define explicit `Request` and `Response` messages, even if they are empty.

## Versioning Strategy

- Declare the API version in the package name inside the proto file:

  ```proto
  package player.v1;
  ```

- Mirror the version in the directory layout:

  ```text
  protos/player/v1/player_service.proto
  ```

## Proto File Layout

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
- Optional `*_events.proto` — server-side streaming RPCs for event notifications (no separate message bus)

Shared message types (for example `EntitySummary` or `ErrorDetail`) live under `protos/shared/v1/` so they version alongside all other APIs.

Directory names may use hyphens (for example `game-design`), while proto packages use underscores (`gamedesign.v1`) so that package declarations remain valid.

All proto files use `syntax = "proto3"` and set `java_package` and `java_multiple_files` options so Java packages remain consistent across services.

## Tooling

- **Buf** ([buf.yaml](../../protos/buf.yaml)) — Lints proto files, detects breaking changes, and drives code generation. The repository stores this configuration under `protos/`. The workspace file [buf.work.yaml](../../config/protobuf/buf.work.yaml) and [buf.gen.yaml](../../config/protobuf/buf.gen.yaml) specify modules and plugins for generation.
- **`protoc-gen-grpc-java`** — Generates Java service stubs for gRPC communication. The generated code is included in service builds via Gradle.
- **`protoc-gen-doc`** — Produces Markdown API documentation. Run
  `./dev-tools/docs/generate-grpc-docs.sh` after updating proto files to regenerate
  [`design/grpc-docs/grpc-api.md`](../grpc-docs/grpc-api.md).

## Shared Interceptors

Every gRPC service registers the `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` from the [Shared Libraries](./system-architecture-shared-libraries.md). These interceptors add trace identifiers to logs, record request metrics, and create OpenTelemetry spans so observability is consistent across services.

## Schema Evolution Rules

- Never reuse or remove field numbers — use `reserved` to prevent reuse.
- Only **add optional fields** or new enum values to avoid breaking compatibility.
- Use the `reserved` keyword to block deprecated field numbers or names.
- Avoid changing the type of an existing field.

## Error Handling

- For internal FireMUD RPCs, return application-level failures in the response `ErrorDetail` field (for example `INVALID_ARGUMENT`, `NOT_FOUND` codes in the shared error catalog) while keeping the gRPC transport status `OK`.
- Use a shared `ErrorDetail` message (e.g., `shared/errors.proto`) when returning rich error info.
- Prefer returning structured errors over using gRPC metadata for application faults.
- All RPCs that can fail should include an `ErrorDetail` field in the response instead of invoking `onError()`. Wrap response observers or use an interceptor to log warnings, increment a `grpc.app_error` metric, and tag tracing spans. `onError()` is reserved for transport-level or infrastructure failures only.
- Metric contract:
  - The Micrometer meter name is `grpc.app_error`; the Prometheus-exported name is `grpc_app_error_total`.
  - Required labels: `service` (from `spring.application.name`) and a bounded `code` taken from the shared error catalog.
    - The `service` label may be attached explicitly per counter increment, or injected globally via a Micrometer `commonTags("service", spring.application.name)` configuration from the shared `firemud-common` auto-configuration.
  - Forbidden labels: per-request identifiers such as `traceId`, `spanId`, `characterId`, or `sessionId`; those identifiers belong only in logs and spans, not in metric label sets.
  Shared logging, metrics, and tracing interceptors implement this contract; services must register them for every gRPC server.

Example implementation:

```java
private ErrorDetail error(String code, String message) {
  // If the shared Micrometer common-tags configuration is enabled, `service` is added automatically.
  meterRegistry.counter("grpc.app_error", "code", code).increment();
  return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
}
```

## Example Code Generation (Java)

Services use **Buf** for linting and schema enforcement, while source generation
is handled by the Gradle `com.google.protobuf` plugin:

```kotlin
protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.31.1" }
  plugins {
    id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.74.0" }
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

## Event and Streaming Semantics

Some FireMUD gRPC APIs are used as **event sinks** or long-lived streams (for example, DMZ edge services emitting disconnect hints into core gameplay services). These flows must assume **at-least-once delivery** at the transport layer and rely on idempotent consumers for correctness.

When designing such APIs:

- Treat producer → consumer delivery as **at-least-once**: events may be delivered more than once or arrive late after reconnects or retries.
- Include a stable idempotency key in every event (for example a composite like `{streamId, sequence}` or an explicit `event_id` field) so consumers can de-duplicate safely.
- Require consumers to treat events as **idempotent** with respect to that key; repeated delivery of the same key must not cause duplicate side effects.
- Document retention/expiry rules for consumer-side dedupe records so idempotency remains valid across producer retry windows and reconnect races.
- Where the consuming service may restart independently, document that dedupe retention must survive ordinary process restarts for at least the required retry/retention window; in-memory-only dedupe is insufficient unless the architecture explicitly declares that loss acceptable.
- Make failure semantics explicit in the proto comments (for example “transport is at-least-once; consumers must handle duplicates keyed by `disconnect_sequence`”) and link back to the relevant architecture documents such as [Reconnection Strategy](./system-architecture-reconnection.md) and [Transactions & Idempotency](./system-architecture-transactions.md).

The TCP Proxy Service’s `NotifyDisconnect` event sink into Game Session is the canonical example of this pattern: disconnect hints are best-effort, at-least-once signals keyed by `{proxyConnectionId, disconnectSequence}` so duplicates and late arrivals are safe. The behaviour-level contract is summarised in [Reconnection Strategy](./system-architecture-reconnection.md#notifydisconnect-behavioral-contract-summary). Restart-persistent dedupe may live in Redis for the bounded producer retry window only when losing that dedupe during a coordination reset remains harmless: authoritative binding/generation checks must prevent a repeated or late advisory from tearing down a newer session or repeating a canonical side effect. Otherwise the owning durable store retains the idempotency result. In-memory-only dedupe is insufficient across ordinary consumer restart. The TCP Proxy Service design remains canonical for message fields and retry timing, and both must remain consistent with [ADR 0062](./decisions/adr-0062-layered-gameplay-command-delivery-semantics.md).

Gameplay command streams from clients into Game Session are intentionally **different** from these event sinks. The edge path is per-connection FIFO where delivered and at-most-once only until trusted Game Session acceptance. Accepted commands have durable lifecycle identity; internal retries use that identity and domain guards and are not at-most-once transport execution. When designing new APIs, treat:

- Client-to-Game-Session edge forwarding as at-most-once per connection until trusted acceptance.
- Accepted command execution as durably tracked and idempotent, with automatic replay only for explicitly safe command classes.
- Internal event/notification streams as at‑least‑once with required idempotency keys.

## TLS Requirements

All internal gRPC calls use **mutual TLS**. FireMUD services now express the server-side TLS contract with Spring gRPC SSL bundles:

- `spring.ssl.bundle.pem.firemud-grpc.keystore.certificate`
- `spring.ssl.bundle.pem.firemud-grpc.keystore.private-key`
- `spring.ssl.bundle.pem.firemud-grpc.truststore.certificate`
- `spring.grpc.server.ssl.bundle=firemud-grpc`
- `spring.grpc.server.ssl.client-auth=REQUIRE` for services that require client certificates

Outside intentionally relaxed local development, each workload has a distinct cert-manager-issued private key and certificate in its own Kubernetes Secret. Services may share a CA trust bundle, but they must not share one leaf private key or collapse concrete workload identity into a generic “FireMUD service” certificate. Method caller allowlists authenticate the peer identity derived from this certificate.

Hosted preview may temporarily use plaintext internal gRPC while the Spring gRPC `1.0.x` SSL-bundle migration and preview re-proof are in flight. That exception is preview-only, must be documented in the preview slice/docs, and does not change the canonical non-local target state above.

The bundle material still comes from the same file paths, but the supported server-side contract is now the Spring Boot SSL bundle plus Spring gRPC server SSL bundle binding. Each service sets the following environment variables so certificates can be mounted from Secrets or local files:

| Variable | Description |
| -------- | ----------- |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Path to the service certificate chain |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Path to the private key |
| `FIREMUD_GRPC_CA_CERT_PATH` | CA bundle used to verify peers |

The [Environment & Secrets](./infrastructure/environment-and-secrets.md#grpc-tls-certificates) guide describes how these values are provided. The shared library includes a `GrpcServerTlsReloader` component to hot reload server certificates, and services use it to reload credentials automatically.

Adopting these conventions helps keep FireMUD services consistent and makes it easier for new contributors to work with the APIs. See [Security Architecture](./system-architecture-security.md#cross-service-trust) for mTLS design.

## Implementation Notes

- The canonical target state remains: internal gRPC uses mTLS everywhere outside intentionally relaxed local development.
- The canonical server-TLS contract is now Spring Boot SSL bundles plus Spring gRPC server SSL bundle binding (`spring.ssl.bundle.*` and `spring.grpc.server.ssl.*`).
- Preview-only plaintext internal gRPC is an explicit temporary exception, not a second long-lived transport model.
- New runtime or preview work should not introduce additional bespoke transport patterns. The remaining hardening path is to add CI/static checks that reject legacy or ignored gRPC server TLS property usage.

## Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Microservices Overview](./microservices/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
