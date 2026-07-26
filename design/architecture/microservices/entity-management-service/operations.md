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

- A shadow table (for example `entity_tick_state`) uses `(tenantId, gameInstanceId, playableStateScope, regionId, targetAggregateType, entityId)` as its complete lookup key and tracks `(last_region_epoch, last_tick_id)` for that key, so shared and isolated gameplay-state namespaces and aggregate types cannot reuse one watermark. For this table, `entityId` is the `targetAggregateId`; entity handlers supply the canonical entity aggregate type.
- Tick-driven handlers that mutate an entity:
  - resolve and read the `entity_tick_state` row using the complete `(tenantId, gameInstanceId, playableStateScope, regionId, targetAggregateType, entityId)` key; an `entityId`-only lookup is invalid;
  - compare `(last_region_epoch, last_tick_id)` from that exact row with `(currentRegionEpoch, currentTickId)` and treat `>=` as a replay/out-of-order no-op (or validation-only check); and
  - when the comparison is `<`, apply the change and update `(last_region_epoch, last_tick_id) = (currentRegionEpoch, currentTickId)` on that same composite-key row in the same transaction as the entity update.

Complex multi-entity operations (for example trades that touch two inventories) use the operation-level effect guard pattern described in the same tick document. They derive the complete target guard set from one stable operation `effectKey`, project one complete target-specific `EffectId` per affected aggregate, insert or verify every target guard in the same transaction before applying changes, and treat a conflict as completion only after the complete expected guard set and authoritative target state are present. A partial conflict must reconcile the original operation and its target-specific EffectIds or fail closed; it must not be treated as proof that the whole operation already applied.

Examples:

- **Damage application** – when a tick instructs Entity Management to apply damage to `entityId`, the handler:
  - derives the operation-level EffectId, including the stable `effectKey` and target aggregate identity, and inserts/verifies the corresponding `tick_effect_guard` row in the same transaction as the HP mutation;
  - treats a guard conflict as a replay only after verifying the complete effect and target state, and otherwise reconciles or fails closed with the original EffectId. Damage does not assume an at-most-one-damage-per-aggregate-per-tick invariant, so the per-aggregate `entity_tick_state` watermark is not sufficient for this path.

- **Trade between two entities** – when a tick performs a trade between `fromEntityId` and `toEntityId`:
  - the handler carries a stable logical trade operation or admitted command sequence and computes a deterministic `effectKey` such as `trade:<tradeOperationId>:<fromEntityId>:<toEntityId>:<itemId>`; the operation/sequence component must remain stable across retries and distinguish two legitimate trades with the same participants and item in one tick;
  - no canonical one-trade-per-`(fromEntityId,toEntityId,itemId)`-per-tick invariant exists, so the handler must not use the participant/item tuple alone as the effect key;
  - it inserts one complete `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType=INVENTORY, targetAggregateId)` guard row for each affected inventory aggregate before moving items between inventories; and
  - on any primary-key conflict, it verifies that guard rows for both affected inventories and the corresponding inventory state are complete and consistent; only then is the trade an already-applied no-op. A partial guard set reconciles the original operation `effectKey` and both target-specific `EffectId` values rather than being accepted as completion.
