# Game Session Service Configuration

## Environment Variables

Game Session follows the configuration scheme from [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md). It requires the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials) and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection) variables.

TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`. The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT`.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `GAME_TICK_DURATION_MS` | Tick cadence (`tick_interval_ms`): target interval between ticks for a region | `1000` |
| `GAME_TICK_BUDGET_MS` | Tick work budget (`tick_budget_ms`): soft max work/lock-hold time per tick; must be <= about `0.8 x GAME_TICK_DURATION_MS` | Derived |
| `GAME_SOLO_TICK_BUDGET_MS` | Execution budget for isolated solo ticks | `500` |
| `GAME_TICK_MAX_COMMANDS` | Max commands staged from the queue each tick | `50` |
| `FIREMUD_SERVICES_GAME_LOGIC_SERVICE` | gRPC endpoint (`host:port`) for Game Logic Service | *(none)* |
| `FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE` | gRPC endpoint (`host:port`) for World Management Service | `world-management-service:6565` |
| `FIREMUD_SERVICES_ENTITY_MANAGEMENT_SERVICE` | gRPC endpoint (`host:port`) for Entity Management Service | `entity-management-service:6565` |
| `FIREMUD_CONFLICT_TTL_SECONDS` | TTL for conflict hotspot tracking in Redis | `300` |

## FireMUD Settings Domains

The first surfaced platform settings domains in Game Session are now generation-ready through typed configuration properties plus configuration metadata.

### `firemud.presentation`

| Key | Purpose | Default |
| --- | ------- | ------- |
| `firemud.presentation.default-locale-tag` | Default locale tag used when rendering built-in localized platform text such as stage errors and entry notices | `en-NZ` |
| `firemud.presentation.default-color-mode` | Default text-renderer color/emphasis mode when no per-player preference overrides it | `NONE` |
| `firemud.presentation.brief-enabled-by-default` | Whether suppressible room-view and transcript segments default to BRIEF-style rendering | `false` |
| `firemud.presentation.prompt.enabled` | Whether prompt output is enabled by default for text-session rendering | `true` |
| `firemud.presentation.prompt.emit-after-reconnect-restore` | Whether reconnect restore appends a fresh prompt after replay and fresh `LOOK` | `true` |
| `firemud.presentation.prompt.coalesce-window-ms` | Small prompt burst window used to reduce prompt spam while still retaining prompts for explicit boundary commands like `LOOK` | `150` |

`firemud.presentation.default-color-mode` currently supports:

- `NONE`
- `BASIC`
- `RICH`

### `firemud.reconnection`

| Key | Purpose | Default |
| --- | ------- | ------- |
| `firemud.reconnection.policy.resume-window-ms` | Maximum resume window before a stale gameplay binding is treated as non-resumable | `180000` |
| `firemud.reconnection.policy.stale-resume-falls-through-to-fresh-entry` | Whether stale resume state falls through to fresh entry when `PLAY` admission still succeeds | `true` |
| `firemud.reconnection.buffer.ttl-ms` | TTL for the bounded reconnect transcript screen buffer | `1800000` |
| `firemud.reconnection.buffer.min-messages` | Minimum message floor retained for reconnect transcript replay | `8` |
| `firemud.reconnection.buffer.min-lines` | Minimum line floor retained for reconnect transcript replay | `24` |
| `firemud.reconnection.buffer.soft-max-bytes` | Soft byte ceiling used once reconnect transcript floors are satisfied | `16384` |
| `firemud.reconnection.buffer.hard-max-bytes` | Hard byte ceiling for reconnect transcript retention | `65536` |

`firemud.reconnection` currently governs reconnect/session recovery and transcript retention policy. Prompt emission after restore remains part of `firemud.presentation.prompt.*`, not `firemud.reconnection`.

### `firemud.movement`

| Key | Purpose | Default |
| --- | ------- | ------- |
| `firemud.movement.post-move-look-enabled` | Whether successful `MOVE` automatically renders the destination room view instead of returning only command acknowledgement | `true` |

### `firemud.world-topology`

| Key | Purpose | Default |
| --- | ------- | ------- |
| `firemud.world-topology.scope-model` | Highest topology model the game expresses for later scope-sensitive behavior such as `shout` routing | `MAP_ONLY` |
| `firemud.world-topology.regions-enabled` | Whether explicit world regions are enabled in the topology model | `false` |

`firemud.world-topology.scope-model` currently supports:

- `MAP_ONLY`
- `AREA_AND_MAP`
- `REGION_AREA_AND_MAP`

## Configuration Notes

- Environment variables configure PostgreSQL and Redis connections via `DatabaseAutoConfiguration` and `RedisProperties`.
- `.env.sample` contains example values for local development.
- [Deployment Environments](../../infrastructure/deployment-environments.md) remains the canonical source for concrete environment examples and deployment-specific binding expectations.
- The service enforces multi-tenant isolation. All tables include a `tenant_id` column and Redis keys are prefixed with this value as outlined in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Service discovery for downstream gRPC calls uses `ServiceEndpointsProperties` and mTLS identities issued through cert-manager.
- `firemud.presentation`, `firemud.reconnection`, `firemud.movement`, and `firemud.world-topology` are still file/env-backed operator defaults today; the later tenant/game override model remains part of the broader platform settings work in `02.9` through `02.12`.
- Game Session now also supports a first bounded file/env-backed effective-settings resolver for already-live domains via `firemud.settings-overrides.*`. These overrides are still operator-supplied, not DB-backed tenant/game state, but they prove the merge path and remove some remaining hardcoded default-only behavior.
- `firemud.settings-overrides.*` currently applies in this precedence order inside Game Session:

`firemud.presentation`, `firemud.movement`, and `firemud.world-topology` operator defaults; then tenant-scoped overrides under `firemud.settings-overrides.*-by-tenant`; then game-instance-scoped overrides under `firemud.settings-overrides.*-by-game-instance`.

- Example scoped override shape:

```yaml
firemud:
  settings-overrides:
    presentation-by-game-instance:
      "7":
        default-color-mode: BASIC
        brief-enabled-by-default: true
        prompt:
          enabled: true
          coalesce-window-ms: 500
    movement-by-game-instance:
      "7":
        post-move-look-enabled: false
    world-topology-by-tenant:
      "22":
        scope-model: REGION_AREA_AND_MAP
        regions-enabled: true
```

- Prompt exclusion from reconnect transcript replay remains part of the canonical reconnect/output policy; it is not yet surfaced as an operator-facing `firemud.presentation` setting.
- The older internal reconnect-adjacent rendered-room snapshot helper is no longer part of the surfaced settings model and should not be treated as an authoritative `LOOK` cache.
