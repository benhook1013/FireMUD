# Game Logic Service Configuration

This document summarizes the Game Logic Service environment and configuration contract and the proto source location.

## Core Configuration

This service follows the conventions in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).

- Unlike other services, it does not connect to PostgreSQL or Redis at runtime; those credentials may still appear in shared `.env` files only for consistency.
- TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates).
- Peer services are discovered using variables prefixed `FIREMUD_SERVICES_`, and the implementation consumes them for gRPC endpoint resolution.
- The gRPC server listens on port `6565` by default as configured in `application.yml`.
- The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT`.

## Dependent-Service Variables

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_SERVICES_ENTITY_MANAGEMENT_SERVICE` | gRPC endpoint (host:port) for the Entity Management Service | *(none)* |
| `FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE` | gRPC endpoint for the World Management Service | *(none)* |
| `FIREMUD_SERVICES_GAME_SESSION_SERVICE` | gRPC endpoint for the Game Session Service | *(none)* |
| `FIREMUD_SERVICES_AUTOMATION_SCRIPTING_SERVICE` | gRPC endpoint for the Automation & Scripting Service | *(none)* |
| `FIREMUD_SERVICES_SOCIAL_GROUPS_SERVICE` | gRPC endpoint for the Social & Groups Service | *(none)* |

## Proto Files

gRPC service definitions can be found in [`protos/game-logic/v1`](../../../../protos/game-logic/v1). Rebuild the generated code with `./gradlew generateProto` after making changes.
