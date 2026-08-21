# Game Session Service Configuration

**Target-state:** Script pin epochs, current pins, and rollout history are durable Game Session state, not configurable defaults. For instance-scoped gameplay/runtime Automation admission, no service-local stale-pin grace period or operator override may authorize work when the authoritative tuple cannot be read. Tenant-readiness `onLoad` remains pre-instance-pin: it carries only candidate `scriptPatchVersion`, omits `gameInstanceId`, runtime scope, and `scriptPinEpoch`, and cannot emit gameplay work or effects. Any future preparation, timeout, or cleanup tuning must preserve exact tuple fencing and must not turn routine script rollback into a full gameplay pause.

## Implementation Status

Live pin/convergence reads exist, while complete `scriptPinEpoch` propagation, atomic pin/history commit, authoritative rollout-history reads, and final-effect fencing remain implementation/proof gaps. See the [Game Session runtime and tick coordination tracker](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#active-gaps).

## Environment Variables

Game Session follows the configuration scheme from [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md). It requires the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials) and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection) variables.

TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`. The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT`.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `GAME_TICK_DURATION_MS` | Tick cadence (`tick_interval_ms`): target interval between ticks for a region | `1000` |
| `GAME_TICK_BUDGET_MS` | Tick work budget (`tick_budget_ms`): soft execution budget for one tick, not the lock TTL; the shared bootstrap derives about `0.8 x GAME_TICK_DURATION_MS`, while `lock_ttl_ms` is resolved and calibrated separately under [ADR 0073](../../decisions/adr-0073-evidence-calibrated-tick-budgets-and-lock-ttls.md) | Derived |
| `GAME_SOLO_TICK_BUDGET_MS` | Execution budget for isolated solo ticks | `500` |
| `GAME_TICK_MAX_COMMANDS` | Max commands staged from the queue each tick | `50` |
| `FIREMUD_SERVICES_GAME_LOGIC_SERVICE` | gRPC endpoint (`host:port`) for Game Logic Service | *(none)* |
| `FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE` | gRPC endpoint (`host:port`) for World Management Service | `world-management-service:6565` |
| `FIREMUD_SERVICES_ENTITY_MANAGEMENT_SERVICE` | gRPC endpoint (`host:port`) for Entity Management Service | `entity-management-service:6565` |
| `FIREMUD_CONFLICT_TTL_SECONDS` | TTL for conflict hotspot tracking in Redis | `300` |

## FireMUD Settings Domains

The canonical per-key reference for the surfaced pre-`06` platform settings now lives in the generated artifacts below rather than being hand-maintained in this service doc:

- [Platform Settings Reference](../../generated/platform-settings-reference.md)
- [Platform Settings Schema](../../generated/platform-settings-schema.json)

Game Session currently owns the operator-default `firemud.*` layer for these surfaced domains:

- `firemud.presentation`
- `firemud.reconnection`
- `firemud.command-history`
- `firemud.command-capabilities`
- `firemud.movement`
- `firemud.world-topology`

The generated reference carries the current defaults, descriptions, valid values or ranges, scope/owner metadata, hot-reloadability, advanced flags, and example values for those keys.

## Configuration Notes

- Settings distribution follows [ADR 0113](../../decisions/adr-0113-bounded-pull-settings-distribution-with-freshness-classes.md): Game Session consumes revisioned snapshots through a bounded local cache and must retain revision, age, provenance, and degraded/expired state in its effective-settings diagnostics. The current short TTL plus explicit refresh/evict behavior is a local implementation seam; class-specific freshness outcomes and an authoritative fence for restrictive settings remain gaps. No generalized push channel or concrete TTL is implied.
- Environment variables configure PostgreSQL and Redis connections via `DatabaseAutoConfiguration` and `RedisProperties`.
- `.env.sample` contains example values for local development.
- [Deployment Environments](../../infrastructure/deployment-environments.md) remains the canonical source for concrete environment examples and deployment-specific binding expectations.
- The service enforces multi-tenant isolation. All tables include a `tenant_id` column and Redis keys are prefixed with this value as outlined in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Service discovery for downstream gRPC calls uses `ServiceEndpointsProperties` and mTLS identities issued through cert-manager.
- `firemud.presentation`, `firemud.reconnection`, `firemud.command-history`, `firemud.command-capabilities`, `firemud.movement`, and `firemud.world-topology` remain file/env-backed operator defaults.

- Tenant/game overrides for these surfaced domains now come from the shared Game Design settings authority rather than service-local file/env maps.
- The generated per-key schema/reference for those domains is the canonical operator/admin-facing documentation surface; this service doc keeps only the Game Session-specific ownership and runtime notes.
- The current resolved result of that bounded read surface is available for operator/debug inspection at `/actuator/settings/effective`. With a persisted `sessionId` it resolves settings against the stored session scope; without one it can synthesize scope from query parameters such as `tenantId`, `gameInstanceId`, and `bootstrapGameInstanceId`.
- That response now also exposes a first-class `prompt` section, resolved `commandCapabilities`, and the scoped shared `communicationOverrides` view Game Session sees for the same scope, so command availability and the 02.10 communication/prompt neighborhood are inspectable from one session-oriented surface. The fully merged effective `communication` result remains owned by Game Logic at `/actuator/settings/effective/communication`.
- In addition to the raw domain payloads, that response now includes normalized subgroup views for the live room-view/transcript seams plus movement/topology seams so operators can inspect `transcriptRendering`, `reconnectionPolicy`, `reconnectBuffer`, `movementPostMoveView`, `worldTopologyScopeModel`, and `worldTopologyRegionBehavior` directly without reverse-mapping service property classes.
- Reconnection sections also expose bounded diagnostics when a persisted tenant or stable playable-state namespace override is disregarded because its merged byte bounds are invalid; the next valid layer remains effective. The target `reconnection.buffer` override scope is `{tenantId, playableStateNamespaceId}`, not `gameInstanceId`; current actuator/query support remains game-instance based until that migration is implemented.
- `firemud.world-topology` now resolves to one canonical effective topology shape in Game Session: any region-capable configuration is normalized to `scopeModel=REGION_AREA_AND_MAP` with `regionsEnabled=true`, and the normalized effective result is what the actuator surface publishes.
- The current precedence inside Game Session is:

`firemud.*` operator defaults, then tenant-scoped persisted overrides from Game Design, then the applicable more-specific persisted override from Game Design. Ordinary domains use game-instance scope; `reconnection.buffer` uses stable `{tenantId, playableStateNamespaceId}` scope so runtime replacement inherits its bounds without copying.

- The bounded shared authority is still not the final centralized effective-settings platform:
  - `common-platform-core` now supplies the shared merged persisted override layer, but Game Session still owns the final merge into its typed operator defaults;
  - operator caps and preset expansion are not applied there yet;
  - invalidation is bounded/local rather than a distributed push model: readers use a short TTL cache plus explicit per-scope refresh/evict semantics;
  - and there is still no generalized config-distribution fabric.

- Bounded semantic reconnect context, including its retention/resource controls, scope, and non-authority semantics, is owned by [Input, Output, and Presentation](../../system-architecture-input-output-and-presentation.md#canonical-resume-context-model) and [ADR 0134](../../decisions/adr-0134-bounded-durable-semantic-reconnect-context.md). This service document links that owner rather than duplicating the full contract. Prompt/status output remains excluded from semantic reconnect context unless a future explicit transcript policy says otherwise.
- The owner documentation records the current implementation/proof gaps for oversized-single-entry handling and namespace/schema-envelope accounting; this service document does not claim strict hard-ceiling enforcement.
- The older internal reconnect-adjacent rendered-room snapshot helper is no longer part of the surfaced settings model and should not be treated as an authoritative `LOOK` cache.
