# Entity Management Service Configuration

This document summarizes the Entity Management configuration contract and proto source location.

## Core Configuration

This service uses the shared configuration described in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md). It requires:

- [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
- [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
- gRPC TLS certificates via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates)
- peer service discovery via variables prefixed `FIREMUD_SERVICES_`
- optional OpenTelemetry collector override via `OTEL_ENDPOINT`

## Service-Specific Variables

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_CHARACTER_CACHE_TTL_SECONDS` | TTL for cached character graphs in Redis (seconds) | `60` |

## Proto Files

Service interface definitions are stored in [`protos/entity-management/v1`](../../../../protos/entity-management/v1). After editing the proto files, run `./gradlew generateProto` to update generated sources.
