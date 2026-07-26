# Game Design Service Configuration

## Environment Variables

Configuration uses the conventions defined in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md). This service relies on the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials).

TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
For example, set `FIREMUD_SERVICES_AUTOMATION_SCRIPTING_SERVICE` to override the default gRPC endpoint used by `ServiceEndpointsProperties`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

Additional variables specific to this service:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_SERVICES_AUTOMATION_SCRIPTING_SERVICE` | gRPC endpoint for the Automation & Scripting Service | *(none)* |

## Redis Role and Prefixes

- The Game Design Service does **not** use Redis at runtime. It neither reads nor writes Coordination Redis or Cache/Rate-Limit Redis; all state lives in PostgreSQL and external asset storage as described in the parent service doc and sibling design docs.

## Asset Store

Published assets are uploaded to an S3-compatible bucket. Configure the client with:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `ASSET_STORE_ENDPOINT` | URL of the S3-compatible service | *(none)* |
| `ASSET_STORE_BUCKET` | Bucket used for published assets | *(none)* |
| `ASSET_STORE_REGION` | Region name for the S3 client | `ap-southeast-2` |
| `ASSET_STORE_ACCESS_KEY` | Access key for the bucket | *(none)* |
| `ASSET_STORE_SECRET_KEY` | Secret key for the bucket | *(none)* |
| `ASSET_STORE_FROZEN_SNAPSHOT_CACHE_MAX_ENTRIES` | Maximum process-local frozen export snapshots retained before least-recently-used eviction | `256` |
