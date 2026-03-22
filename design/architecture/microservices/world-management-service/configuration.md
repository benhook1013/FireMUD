# World Management Service Configuration

## Environment Variables

World Management uses the configuration scheme defined in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

- It depends on PostgreSQL credentials and Redis connection variables from the shared environment catalog.
- TLS certificates are supplied via `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH`.
- Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
- The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT`.

Additional variables configure world-data caching and housekeeping:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `WORLD_ROOM_CACHE_TTL_SECONDS` | Seconds to retain room data in the cache | `60` |
| `WORLD_INSTANCE_EXPIRATION_HOURS` | Hours before a transient instance expires | `24` |
| `WORLD_EVENT_CHECK_DELAY_MS` | Delay between event-processing checks in ms | `60000` |

## Local Override Notes

- Override the World Management endpoint locally via `FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE` when running Gateway or Game Session against custom world servers.
- Some developer helpers refer to the same value as `WORLD_SERVICE_ENDPOINT`; the canonical environment variable remains `FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE`.
- The same override is wired into the `LOOK` cross-service tests so the sample world and entity fixtures stay consistent when running against custom world servers.
