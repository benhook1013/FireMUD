# Entity Management Service Operations

This document collects Entity Management’s readiness model, tick-lock/tick-idempotency operational assumptions, and deployment-level operational notes.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- `liveness` is process-local only.
- `readiness` is truthful local readiness for the currently implemented entity-query slice and must fail when the service cannot safely answer room/entity lookup traffic with its required local persistence/cache/bootstrap state.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- `entity.actor-condition.expiry-interval-seconds` controls the scheduled active-condition expiry sweep. The default is 30 seconds and removes rows whose `expires_at` has elapsed; gameplay reads also ignore expired rows, so the sweep is cleanup and convergence rather than the sole correctness guard.

## Tick Locking

This service participates in tick processing by acquiring Redis locks before mutating entity state. The `TickLockService` uses the `tick:{tenantRegionTag}:lock:<entityId>` key described in the [Redis Architecture](../../system-architecture-redis.md) document so that lock keys share a hash tag with tick queues and pending state. Lock TTLs come from the shared tick/Redis helpers that implement the canonical formulas defined in [Tick Concepts & Invariants](../../system-architecture-tick-concepts-and-invariants.md#tick-budget-ttls-and-region-health-conceptual):

- The Game Session Service exposes `game.tick-interval-ms` as the primary pacing knob.
- Internally it derives a soft budget and TTLs using the shared helpers (for example `tick_budget_ms = tick_interval_ms * 0.8`, `lock_ttl_ms = clamp(tick_budget_ms * 8, 500, 5_000)`).

Entity Management treats `lock_ttl_ms` as an opaque value supplied by shared helpers; it does not define its own lock TTL configuration. This keeps locks alive long enough for normal ticks to complete while still bounding the recovery window for stalled ticks.

At runtime, the Game Session Service also compares observed tick execution time to `tick_lock_ttl_ms` (the effective lock TTL for the region, derived from `lock_ttl_ms`) as described in the [Tick System design](../../system-architecture-ticks.md#timeout-and-fairness-policy). Regions whose `p99` tick runtime begins to approach or exceed a configured fraction of that TTL are treated as degraded, and operators are expected to either increase the tick interval or simplify per-tick work. Entity Management does not adjust TTLs itself; it relies on the shared helpers and scheduler behavior to keep lock usage within safe bounds.

Entity Management assumes the per-command execution phases described in the [Tick System design](../../system-architecture-ticks.md#per-command-execution-phases): commands that touch multiple entities in the same region resolve their target set first (for example, the two parties in a trade or all entities in a room for AoE effects), then acquire the necessary `tick:{tenantRegionTag}:lock:<entityId>` keys in a deterministic order. If any required lock is unavailable, the command fails, staged changes are rolled back via Redis, and the Game Session Service reschedules the work using the retry mechanisms described in the tick and Redis designs.

## Tick Idempotency

Entity Management implements tick idempotency using the per-aggregate last-tick state pattern described in the [Tick System and Runtime Design](../../system-architecture-ticks.md#domain-idempotency-rules-region-epoch--tickid-in-postgresql) document:

- A shadow table (for example `entity_tick_state`) tracks `(last_region_epoch, last_tick_id)` (and associated tenant/game-instance/region metadata) per `entityId`.
- Tick-driven handlers that mutate an entity:
  - load the current tick state for that `entityId`;
  - treat calls where `(last_region_epoch, last_tick_id) >= (currentRegionEpoch, currentTickId)` as replays/out-of-order and perform a no-op (or validation-only check); and
  - apply changes only when `(last_region_epoch, last_tick_id) < (currentRegionEpoch, currentTickId)`, then update `(last_region_epoch, last_tick_id) = (currentRegionEpoch, currentTickId)` in the same transaction as the entity update.

Complex multi-entity operations (for example trades that touch two inventories) use the operation-level effect guard pattern described in the same tick document, inserting a `(tenantId, gameInstanceId, regionId, region_epoch, tickId, effectKey)` row into a guard table before applying changes so replays of the same logical effect become safe no-ops instead of double-applications.

Examples:

- **Damage application** – when a tick instructs Entity Management to apply damage to `entityId`, the handler:
  - reads `entity_tick_state` for that `entityId`;
  - skips the update if `(last_region_epoch, last_tick_id) >= (currentRegionEpoch, currentTickId)` (replay/out-of-order), or applies the HP change and sets `(last_region_epoch, last_tick_id) = (currentRegionEpoch, currentTickId)` in the same transaction if `(last_region_epoch, last_tick_id) < (currentRegionEpoch, currentTickId)`.

- **Trade between two entities** – when a tick performs a trade between `fromEntityId` and `toEntityId`:
  - the handler computes a deterministic `effectKey` such as `trade:<fromEntityId>:<toEntityId>:<itemId>`;
  - it inserts `(tenantId, gameInstanceId, regionId, region_epoch, tickId, effectKey)` into the guard table before moving items between inventories; and
  - on primary-key conflict, the trade is treated as an already-applied effect for that tick and becomes a no-op.
