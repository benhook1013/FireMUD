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

## Playtest Fork Lifecycle Authority

World Management owns allocation, lifecycle, and expiry for each playtest fork under [ADR 0137](../../decisions/adr-0137-isolated-playtest-state-modes-and-reset.md) and [ADR 0138](../../decisions/adr-0138-expiring-playtest-grants-with-bounded-active-revocation.md). A new fork receives a fresh immutable canonical `playtestLifecycleId` and its exact `forkExpiresAt`, bound to the World-owned lifecycle/version/epoch proof. World is the sole producer of this identity and deadline; fork extension changes World lifecycle authority and never silently extends Account grants.

World supplies the exact current proof to Game Session for its admission-pointer transaction and authenticated readback. Game Session and Account consume that proof without allocating an ID, deriving an expiry, or creating a competing lifecycle record. Allocation and authenticated owner readback are not implemented; current legacy runtime rows do not prove the target lifecycle or expiry.

## Implementation Status

The current `instance` table models legacy temporary zone copies for dungeons or housing, with `expires_at` derived from `world.instance.expiration-hours`. The live scheduled cleanup still deletes those rows directly. That is explicit implementation drift, not evidence that the legacy row participates in the [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md) lifecycle.

## Current LOOK Slice Status

- **Implemented RPC / current data:** `GetRoomSnapshot` is implemented and returns room metadata, descriptions, and exit labels as World-owned facts for Game Logic to combine with Entity data into a typed `LookResult`; its current response data is fixture-backed rather than production room data. Game Session maps accepted outcomes to compact versioned `PlayerOutput` and owns the final player-facing prose, rendering, and delivery; telemetry for this pipeline is documented in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed test behavior:** Deterministic LOOK fixtures keep scripted room events, line-of-sight lighting, and procedural text stable for regression tests. Game Logic's local `LookResultRenderer` remains a fixture/diagnostic aid; it is not the player-facing renderer owner.
- **Deferred:** Future work will push live snapshot updates through `/ws/game/**` so Gateway and TCP Proxy clients can react to world changes as soon as they happen.

## Temporal Participation

The [Transaction Strategies workflow classification](../../system-architecture-transactions.md#mandatory-workflow-adopter-classification) and [Temporal adopter contract](../../system-architecture-temporal-workflows.md) own placement rules. [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md) owns lifecycle authority and all-owner completion. World Management's local consequence is that creation, activation, failure, and termination for the target `world_instance` lifecycle use the shared Temporal `world-lifecycle` family for coordination while the durable World row and epoch remain authoritative; legacy temporary zone-copy cleanup remains the implementation drift described above, and gameplay runtime remains out of scope. Current restart/failure, owner-registry, and durable step-guard gaps are detailed in [`world-creation-workflow.md`](./world-creation-workflow.md) and the implementation tracker.
