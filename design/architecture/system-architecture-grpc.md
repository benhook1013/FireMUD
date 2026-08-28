# FireMUD System Architecture: gRPC API Style & Versioning Guidelines

These guidelines define how FireMUD microservices design and document their gRPC APIs. Following a consistent structure makes it easier for teams to evolve services over time and share tooling.

## Implementation Status

The normative contract below remains mTLS for internal gRPC outside intentionally relaxed local development. Hosted preview may temporarily use plaintext while the Spring gRPC `1.0.x` SSL-bundle migration and preview re-proof are in flight; this is preview-only and does not create a second target transport. The remaining hardening path is CI/static checking that rejects legacy or ignored server-TLS property usage. Protobuf generation exists, but deliberate Buf compatibility baselines, release compatibility classification, transitional bridge proof, and durable late-event safety proof under [ADR 0174](./decisions/adr-0174-maturity-scoped-protobuf-compatibility.md) remain incomplete. These current caveats do not weaken the workload identity, exact method allowlist, or canonical Spring SSL-bundle requirements.

**Current implementation gap:** the live Redis sequence-dedupe path fails open on Redis errors and is therefore reset-loss-vulnerable; it has no current connection/generation binding guard before presence, region-exit, or `GameInstance` suspension effects. The live path does not yet satisfy this target advisory-safety contract. The eventual binding design is intentionally not defined here.

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

The current `v1` namespace does not freeze every unreleased internal contract. For each individual contract, an incompatible internal change may converge directly in `v1` when that contract has no supported external consumers, no retained wire representation requires the old shape, and all callers and servers deploy as one coordinated change. An external consumer of one contract does not constrain unrelated internal contracts. Such a deployment is recreate/coordinated or roll-forward-only unless mixed-version compatibility is separately proven.

If old and new internal binaries must overlap for rolling deployment or rollback, use a temporary additive bridge in `v1`, deploy in a compatible order, and remove the obsolete shape only after no supported binary or rollback target needs it. A parallel `v2` is reserved for an incompatible replacement that must coexist with a formally supported external or otherwise deliberately long-lived `v1` contract; ordinary pre-v1 redesign does not create `v2` APIs. See [ADR 0174](decisions/adr-0174-maturity-scoped-protobuf-compatibility.md).

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

- **Buf** ([buf.yaml](../../protos/buf.yaml)) — Lints proto files and detects breaking changes against a deliberately selected baseline; it does not own build source generation. Linting applies to all contracts. Breaking checks compare compatibility-protected operational releases with the exact deployed or rollback-target proto set and supported external contracts with an immutable release, tag, or digest; an arbitrary moving development snapshot is not a permanent compatibility baseline. The repository stores this configuration under `protos/`, and [buf.work.yaml](../../config/protobuf/buf.work.yaml) defines the workspace used by Buf tooling. Gradle's `com.google.protobuf` plugin and `generateProto` task remain the authoritative Java source-generation path described below.
- **`protoc-gen-grpc-java`** — Generates Java service stubs for gRPC communication. The generated code is included in service builds via Gradle.
- **`protoc-gen-doc`** — Produces Markdown API documentation. Run
  `./dev-tools/docs/generate-grpc-docs.sh` after updating proto files to regenerate
  [`design/grpc-docs/grpc-api.md`](../grpc-docs/grpc-api.md).

## Shared Interceptors

Every gRPC service registers the `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` from the [Shared Libraries](./system-architecture-shared-libraries.md). These interceptors add trace identifiers to logs, record request metrics, and create OpenTelemetry spans so observability is consistent across services.

## Schema Evolution Rules

These rules apply throughout a declared compatibility window. A window exists when external consumers upgrade independently, old and new binaries overlap, binary rollback is supported, or retained messages/data require an older reader:

- Add fields with new field numbers; use explicit presence when absence and the default value have different meanings.
- Do not remove an existing field while its compatibility window is active.
- After that window closes, an approved removal must reserve both the field number and name in the same change, before either can be reused.
- Add enum values only when every protected consumer safely handles unknown values.
- Do not change an existing field's type or meaning inside the protected window.

An intentionally incompatible pre-v1 internal change follows the coordinated-convergence or temporary-bridge rules in [Versioning Strategy](#versioning-strategy), rather than creating a permanent compatibility generation by default.

## Outcome and Transport Classification

The RPC owner must classify the result before choosing a response shape. A successfully produced expected domain outcome (including a typed business rejection where the domain contract defines one) uses a typed result union or equivalent response fields. A canonical non-OK gRPC status, with bounded structured details, is used when the service cannot produce that domain result. This includes validation or authentication failure at the transport/pre-domain boundary, a missing precondition before domain execution, resource exhaustion, deadline or cancellation, dependency unavailability, and internal failure. The outer transport status remains authoritative for infrastructure failures.

`ErrorDetail` remains a shared representation for existing response fields and bounded structured details where an adopting contract uses it; it is not a universal requirement that every application failure keep transport status `OK`. Existing handlers and `ErrorDetail`-in-response paths are implementation drift to be migrated and proved against this owner contract, not an alternate target.

For mutations, a timeout, transport failure, or missing evidence after execution may be ambiguous. Callers retain the stable idempotency identity and reconcile the original request before replay; they must not turn an uncertain outcome into a new mutation. Batch and streaming RPCs distinguish item outcomes from stream failure: each produced item uses the typed item-result contract, while a failure that prevents the stream or batch from producing its domain result uses canonical non-OK status and bounded details. Already-produced item outcomes remain valid evidence for reconciliation.

The observability contract is shared even when the outcome channel differs:

- The Micrometer meter name is `grpc.app_error`; the Prometheus-exported name is `grpc_app_error_total`.
- Required labels are `service` (from `spring.application.name`) and a bounded `code` from the shared catalog or transport classification.
  - The `service` label may be attached explicitly per counter increment, or injected globally via a Micrometer `commonTags("service", spring.application.name)` configuration from the shared `firemud-common` auto-configuration.
- Per-request identifiers such as `traceId`, `spanId`, `characterId`, or `sessionId` are forbidden metric labels; they belong only in logs and spans.

Shared logging, metrics, and tracing interceptors implement observability for both typed outcomes and non-OK failures. RPC handlers or response-aware wrappers remain responsible for producing the contract-specific result union or bounded details, and services must register the interceptors for every gRPC server.

Example implementation for a response-level typed detail (where the owner contract calls for one):

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

The TCP Proxy Service’s `NotifyDisconnect` event sink into Game Session is the canonical example of this pattern: disconnect hints are best-effort, at-least-once signals keyed by `{proxyConnectionId, disconnectSequence}` so duplicates and late arrivals are safe. The behaviour-level contract is summarised in the **NotifyDisconnect Behavioral Contract** section of [Reconnection Strategy](./system-architecture-reconnection.md#notifydisconnect-behavioral-contract-summary). Restart-persistent dedupe may live in Redis for the bounded producer retry window only when losing that dedupe during a coordination reset remains harmless: authoritative binding/generation checks must prevent a repeated or late advisory from tearing down a newer session or repeating a canonical side effect. Otherwise the owning durable store retains the idempotency result. In-memory-only dedupe is insufficient across ordinary consumer restart. The TCP Proxy Service design remains canonical for message fields and retry timing, and both must remain consistent with [ADR 0062](./decisions/adr-0062-layered-gameplay-command-delivery-semantics.md) and [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).

Gameplay command streams from clients into Game Session are intentionally **different** from these event sinks. The edge path is per-connection, where delivery is FIFO and at-most-once only until trusted Game Session acceptance. Accepted commands have durable lifecycle identity; internal retries use that identity and domain guards and are not at-most-once transport execution. When designing new APIs, treat:

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

Outside intentionally relaxed local development and hosted preview while its documented plaintext exception remains active, each workload has a distinct cert-manager-issued private key and certificate in its own Kubernetes Secret. Services may share a CA trust bundle, but they must not share one leaf private key or collapse concrete workload identity into a generic “FireMUD service” certificate. The certificate and trust chain authenticate the peer identity; exact method caller allowlists then authorize that already-authenticated identity for the individual RPC. A valid certificate alone does not authorize every internal method.

Hosted preview may temporarily use plaintext internal gRPC while the Spring gRPC `1.0.x` SSL-bundle migration and preview re-proof are in flight. That exception is preview-only, must be documented in the preview slice/docs, and does not change the canonical non-local target state above.

The bundle material still comes from the same file paths, but the supported server-side contract is now the Spring Boot SSL bundle plus Spring gRPC server SSL bundle binding. Each service sets the following environment variables so certificates can be mounted from Secrets or local files:

| Variable | Description |
| -------- | ----------- |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Path to the service certificate chain |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Path to the private key |
| `FIREMUD_GRPC_CA_CERT_PATH` | CA bundle used to verify peers |

The [Environment & Secrets](./infrastructure/environment-and-secrets.md#grpc-tls-certificates) guide describes how these values are provided. The shared library includes a `GrpcServerTlsReloader` component to hot reload server certificates, and services use it to reload credentials automatically.

Adopting these conventions helps keep FireMUD services consistent and makes it easier for new contributors to work with the APIs. See [Security Architecture](./system-architecture-security.md#cross-service-trust) for mTLS design.

## Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Microservices Overview](./microservices/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
