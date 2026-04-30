# Game-session Service Proto (v1)

This directory contains version 1 protocol buffer definitions for the game session service.
They describe the gRPC API exposed by the service.

Generate Java stubs with `./gradlew generateProto` from the repository root.

- `GetGameSessionPinConvergence` exposes the persisted Game Session-side pinned script patch observation for one instance and now includes `observedAtMs` plus `isStale` so operator tooling can distinguish an old pin observation from a fresh convergence read.
For details see the [design docs](../../../design/architecture/microservices/game-session-service/README.md).

## Control Plane APIs

This proto now includes a separate `GameSessionControlPlaneService` for operator-driven actions (script patch pinning/rollback, pin-convergence/runtime-state reads, scoped tick pause/resume, and queue purge hooks for rollback safety) as specified in `design/architecture/system-architecture-scripting-control-plane-api.md`. The lightweight pin read now also returns the persisted `controlPlaneRequestId` for the last pin mutation, not only actor/timestamp fields. The command-status read accepts either `commandId` or the automation tuple `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)` so operator tooling can correlate Automation handoff history with the Game Session command ledger, and it now exposes the current-boundary comparable `enqueueSeq`, plugin provenance, and the carried automation origin-source metadata too. The runtime ownership status read returns the explicit stored `regionId` plus the current `lastCommittedTickId` from the same durable ownership row used for `regionEpoch` and executor fencing; callers may now resolve that ownership row by `regionId` directly, with `gameInstanceId` retained as the current-boundary fallback while true region partitioning remains future work. That same stored scope is also the tick progress value Game Session publishes to Automation's scheduler feed. The internal `EnqueueAutomationCommandIfAbsent` handoff surface now also allows immediate event-driven automation to omit `dueTickId`; scheduler/timer producers still carry it when a real due point exists, and the handoff preserves scripting-side origin-source metadata so later ledger and manifest reads can distinguish gameplay-triggered work from scheduler-triggered work. New automation handoffs are fenced by Game Session's current runtime ownership row and reject missing ownership, paused ownership, or stale `regionEpoch` before Redis tick-queue mutation. Automation handoff carries script-patch and plugin-version provenance so Game Session can purge queued commands by either rollback scope.

The existing `PauseTicks` / `ResumeTicks` RPCs on `GameSessionService` remain the minimal, backup-oriented APIs; the control-plane service is the normative surface for per-tenant/game/region operations.
