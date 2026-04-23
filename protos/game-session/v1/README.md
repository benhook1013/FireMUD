# Game-session Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the game session service.
They describe the gRPC API exposed by the service.

Generate Java stubs with `./gradlew generateProto` from the repository root.
For details see the [design docs](../../../design/architecture/microservices/game-session-service/README.md).

## Control Plane APIs

This proto now includes a separate `GameSessionControlPlaneService` for operator-driven actions (script patch pinning/rollback, pin-convergence/runtime-state reads, scoped tick pause/resume, and queue purge hooks for rollback safety) as specified in `design/architecture/system-architecture-scripting-control-plane-api.md`. The lightweight pin read now also returns the persisted `controlPlaneRequestId` for the last pin mutation, not only actor/timestamp fields. The internal `EnqueueAutomationCommandIfAbsent` handoff surface now also allows immediate event-driven automation to omit `dueTickId`; scheduler/timer producers still carry it when a real due point exists.

The existing `PauseTicks` / `ResumeTicks` RPCs on `GameSessionService` remain the minimal, backup-oriented APIs; the control-plane service is the normative surface for per-tenant/game/region operations.
