# Automation-scripting Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the automation scripting service. They describe the gRPC API exposed by the service.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [Automation & Scripting Service design docs](../../../design/architecture/microservices/automation-scripting-service/README.md) and the scripting DSL reference (`design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`).

The `PingResponse` message reuses the shared `ErrorDetail` type from `protos/shared/v1/errors.proto`.

## RPC Overview

The proto files in this directory define several RPCs consumed by domain services:

- **Health and admin**
  - `Ping(PingRequest) returns (PingResponse)` – basic health check; see the service README for REST and gRPC ping usage.
- **Design-time APIs**
  - `UpdateScript` – uploads or replaces a script definition for later use as part of the Game Design → Automation & Scripting publish Saga.
  - `GetScriptStatus` – queries whether a script is queued or running for a given entity.
  - `NotifyScriptVersionUpdate` – informs the service that a new `script_patch_version` is available; the service reloads affected scripts, executes any required `onLoad` initialization, and updates its runtime registry.
- **Event ingress APIs**
  - RPCs such as `TriggerScriptEvent` (or the actual event-ingress names defined here) are called by the Game Session Service and other domain services to deliver script events. Requests carry `tenantId`, `regionId`, `entityId`, `scriptEventId`, `eventType`, `scriptPatchVersion`, and an event payload envelope.
  - Event-ingress RPCs are **idempotent** with respect to `scriptEventId` within a `<tenantId>` (and, where applicable, `<regionId>`). The Automation & Scripting Service must deduplicate repeated deliveries using the `scriptEventId` lifecycle and deduplication rules described in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md#scripteventid-lifecycle-and-deduplication`.

For metric names, outcomes, and operational semantics of these RPCs, see:

- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/system-architecture-scripting-examples-and-patterns.md`
