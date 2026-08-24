# Social & Groups Service Configuration

This document summarizes the Social & Groups Service configuration contract and proto source location.

## Core Configuration

The service follows the conventions from [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md). It relies on:

- [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
- [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
- gRPC TLS certificates via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates)
- peer service discovery via variables prefixed `FIREMUD_SERVICES_`
- optional OpenTelemetry collector override via `OTEL_ENDPOINT`

## Service-Specific Variables

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_SERVICES_LOGGING_ADMIN_SERVICE` | `host:port` of the Logging Admin service | `logging-admin-service:6565` |
| `FIREMUD_VOICE_TOKEN_EXPIRATION_MS` | Expiration of voice chat tokens | `300000` |

Chat history cache behavior can be tuned with the following variables:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_CHAT_SAYS_TTL_SECONDS` | Seconds to keep `say` messages per player | `7200` |
| `FIREMUD_CHAT_SAYS_MAX_MESSAGES` | Max cached `say` messages per player | `50` |
| `FIREMUD_CHAT_WHISPERS_TTL_SECONDS` | Seconds to keep `whisper` messages per tenant and account | `7200` |
| `FIREMUD_CHAT_WHISPERS_MAX_MESSAGES` | Max cached `whisper` messages per tenant and account | `50` |
| `FIREMUD_CHAT_TELLS_TTL_SECONDS` | Seconds to keep direct tells/messages | `172800` |
| `FIREMUD_CHAT_TELLS_MAX_MESSAGES` | Max cached tells/messages per player | `50` |
| `FIREMUD_CHAT_GUILD_TTL_SECONDS` | Seconds to keep guild chat per guild | `172800` |
| `FIREMUD_CHAT_GUILD_MAX_MESSAGES` | Max cached guild chat messages | `50` |
| `FIREMUD_CHAT_CITY_TTL_SECONDS` | Seconds to keep city chat per city | `172800` |
| `FIREMUD_CHAT_CITY_MAX_MESSAGES` | Max cached city chat messages | `50` |
| `FIREMUD_CHAT_ACCOUNT_TTL_SECONDS` | Seconds to keep account-to-account messages | `172800` |
| `FIREMUD_CHAT_ACCOUNT_MAX_MESSAGES` | Max cached account messages | `50` |

## Proto Files

The social APIs are defined in [`protos/social-groups/v1`](../../../../protos/social-groups/v1). Regenerate the service stubs with `./gradlew generateProto` whenever the proto files change.
