# World Management Service Operations

## Operational Notes

- World Management runs as a Kubernetes Deployment, or Docker Compose for local development, with `/actuator/health/readiness` and `/actuator/health/liveness` probes.
- `liveness` is process-local only.
- `readiness` is truthful local readiness for the currently implemented world-data slice and must fail when the service cannot safely answer room-snapshot traffic with its required local persistence, cache, and bootstrap state.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Instance Cleanup and Expiry

- When temporary content is modeled as a complete `world_instance` with its own `gameInstanceId`, expiry must use the canonical `world-lifecycle` Temporal workflow and fenced `TerminateWorldInstance` RPC; the World row and lifecycle epoch remain authoritative rather than Temporal status.
- When temporary content remains a zone-scoped child of a parent game instance, it requires a separate scoped, idempotent cleanup contract. Expiring the child must not transition or terminate the parent `world_instance`.
- Direct periodic deletion is not a valid target cleanup path for either boundary.

## Implementation Status

The current `instance` table models legacy temporary zone copies for dungeons or housing, with `expires_at` derived from `world.instance.expiration-hours`. The live scheduled cleanup still deletes those rows directly. That is explicit implementation drift, not evidence that the legacy row participates in the [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md) lifecycle.

## Current LOOK Slice Status

- **Live:** `GetRoomSnapshot` returns room metadata, descriptions, and exit labels needed by Game Logic to render the canonical `LOOK` transcript, and telemetry for this pipeline is documented in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed:** Current snapshot data comes from the deterministic LOOK test fixtures so scripted room events, line-of-sight lighting, and procedural text remain deterministic for regression tests.
- **Deferred:** Future work will push live snapshot updates through `/ws/game/**` so Gateway and TCP Proxy clients can react to world changes as soon as they happen.

## Temporal Participation

The [Transaction Strategies workflow classification](../../system-architecture-transactions.md#mandatory-workflow-adopter-classification) and [Temporal adopter contract](../../system-architecture-temporal-workflows.md) own placement rules. [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md) owns lifecycle authority and all-owner completion. World Management's local consequence is that creation, activation, failure, and termination use the shared Temporal `world-lifecycle` family for coordination while the durable World row and epoch remain authoritative; gameplay runtime remains out of scope. Current restart/failure, owner-registry, and durable step-guard gaps are detailed in [`world-creation-workflow.md`](./world-creation-workflow.md) and the implementation tracker.
