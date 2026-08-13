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

- **Live:** `GetRoomSnapshot` returns room metadata, descriptions, and exit labels needed by Game Logic to render the canonical `LOOK` transcript, and telemetry for this pipeline is documented in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed:** Current snapshot data comes from the deterministic LOOK test fixtures so scripted room events, line-of-sight lighting, and procedural text remain deterministic for regression tests.
- **Deferred:** Future work will push live snapshot updates through `/ws/game/**` so Gateway and TCP Proxy clients can react to world changes as soon as they happen.

## Temporal Participation

The [Transaction Strategies workflow classification](../../system-architecture-transactions.md#mandatory-workflow-adopter-classification) and [Temporal adopter contract](../../system-architecture-temporal-workflows.md) own placement rules. The [World Runtime and Movement implementation tracker](../../../project-management/implementation-tracking/world-runtime-and-movement.md) owns current implementation/proof status, gaps, and evidence. World Management's local consequence is that creation, activation, failure, and termination for a game instance run through the shared Temporal `world-lifecycle` family: command activities own durable lifecycle steps, the lifecycle read surface projects deterministic workflow identity/status, and gameplay runtime remains out of scope. Local lifecycle architecture and consequences, including the current restart/failure and durable step-guard gaps, are detailed in [`world-creation-workflow.md`](./world-creation-workflow.md).
