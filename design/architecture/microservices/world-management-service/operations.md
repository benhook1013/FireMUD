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
- World Management's `world_instance_status` and `lifecycle_epoch` are authoritative. Temporal coordinates expiry and cleanup retries but does not decide whether the instance is admissible or terminated.
- Termination from either `PREPARING` or `ACTIVE` enters `TERMINATING` through a storage compare-and-set. The `PREPARING` transition fences stale activation before cleanup begins.
- `TERMINATED` requires durable cleanup acknowledgement from every registered owner of `gameInstanceId`-scoped data. New durable families must join the owner, replacement-classification, cleanup, acknowledgement, retry, and retention registry before they are written by launch flows.
- `FAILED_PRE_ACTIVATION` permanently closes admission for that instance but has separate durable cleanup progress and owner acknowledgements; operators must not interpret it as cleanup complete.

## Current LOOK Slice Status

- **Live:** `GetRoomSnapshot` returns room metadata, descriptions, and exit labels needed by Game Logic to render the canonical `LOOK` transcript, and telemetry for this pipeline is documented in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed:** Current snapshot data comes from the deterministic LOOK test fixtures so scripted room events, line-of-sight lighting, and procedural text remain deterministic for regression tests.
- **Deferred:** Future work will push live snapshot updates through `/ws/game/**` so Gateway and TCP Proxy clients can react to world changes as soon as they happen.

## Temporal Participation

World creation, activation, failure, and termination for a game instance use the shared Temporal substrate as the canonical `world-lifecycle` workflow family. Temporal owns restart-safe coordination, waits, retries, and progress history. The authoritative lifecycle remains the World Management database row and epoch, and cleanup completion remains the durable per-owner acknowledgement set.

Workflow activities delegate to idempotent owner-local commands keyed by stable business and step identity. Each owner commits its domain change and idempotency result in local storage, using uniqueness or compare-and-set predicates where applicable, so ambiguous responses and activity replay converge safely.

Temporal is not on the routine gameplay path. Tick processing and ordinary gameplay commands do not query workflow state or wait for cleanup; lifecycle-sensitive admission and termination boundaries read the authoritative database-backed lifecycle contract. The detailed step model is described in [`world-creation-workflow.md`](./world-creation-workflow.md).

## Lifecycle and Cleanup Observability

Metrics and alerts must cover:

- age and retry count for instances stuck in `PREPARING` or `TERMINATING`;
- `FAILED_PRE_ACTIVATION` instances whose separate cleanup state is incomplete;
- missing, failed, or stale per-owner cleanup acknowledgements;
- stale Temporal progress correlated with the authoritative lifecycle row and workflow identity;
- lifecycle compare-and-set conflicts and cleanup latency; and
- attempts to finalize `TERMINATED` with an incomplete or unknown owner set.

Operator diagnostics correlate `tenantId`, `gameInstanceId`, lifecycle state and epoch, creation or termination request identity, Temporal `workflowId`, cleanup request identity, and owner acknowledgement state. Dashboard and Temporal projections are diagnostic views, not lifecycle authority.

Current implementation is narrower: the first termination cut synchronously coordinates Entity Management and World-owned row cleanup for active instances. It does not yet implement `PREPARING` termination, separate `FAILED_PRE_ACTIVATION` cleanup state, the extensible all-owner registry and acknowledgement gate, or the complete stuck-state metric and alert set above.
