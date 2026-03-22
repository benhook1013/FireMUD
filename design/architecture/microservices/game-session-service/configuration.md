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

## Configuration Notes

- Environment variables configure PostgreSQL and Redis connections via `DatabaseAutoConfiguration` and `RedisProperties`.
- `.env.sample` contains example values for local development.
- [Deployment Environments](../../infrastructure/deployment-environments.md) remains the canonical source for concrete environment examples and deployment-specific binding expectations.
- The service enforces multi-tenant isolation. All tables include a `tenant_id` column and Redis keys are prefixed with this value as outlined in [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Service discovery for downstream gRPC calls uses `ServiceEndpointsProperties` and mTLS identities issued through cert-manager.
