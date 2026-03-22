# World Management Service Operations

## Operational Notes

- World Management runs as a Kubernetes Deployment, or Docker Compose for local development, with `/actuator/health/readiness` and `/actuator/health/liveness` probes.
- `liveness` is process-local only.
- `readiness` is truthful local readiness for the currently implemented world-data slice and must fail when the service cannot safely answer room-snapshot traffic with its required local persistence, cache, and bootstrap state.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Instance Cleanup and Expiry

The service creates temporary instances of zones for dungeons or housing. Instances expire automatically based on the `world.instance.expiration-hours` property.

- Expiry processing enqueues `InstanceTermination` workflows.
- Direct periodic deletion of instance rows is not a valid cleanup path.
- Scheduled expiry jobs must participate in the same fenced lifecycle workflow documented in the service API/runtime contracts.

## Current LOOK Slice Status

- **Live:** `GetRoomSnapshot` returns room metadata, descriptions, and exit labels needed by Game Logic to render the canonical `LOOK` transcript, and telemetry for this pipeline is documented in [`look-instrumentation.md`](../../../project-management/look-instrumentation.md).
- **Stubbed:** Current snapshot data comes from the seeded demo rooms so scripted room events, line-of-sight lighting, and procedural text remain deterministic for regression tests.
- **Deferred:** Future work will push live snapshot updates through `/ws/game/**` so Gateway and TCP Proxy clients can react to world changes as soon as they happen.

## Saga Participation

World creation for a new game instance runs as a Saga using the helper utilities from `firemud-common`. Each step is described in [`world-creation-workflow.md`](./world-creation-workflow.md) and can be rolled back if a later step fails. This keeps instance world state consistent even when the workflow spans multiple services.
