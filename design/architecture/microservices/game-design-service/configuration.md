# Game Design Service Configuration

## Implementation Status

The service binds the private authenticated S3 API and public `/assets/**` base from separate environment variables. It never derives a public URL from the private endpoint, and an explicitly configured base does not establish an approved or provisioned public delivery origin. The delivery authority and activation gates remain owned by [Asset Storage](./asset-storage.md#target-external-delivery-classification).

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

## Authored-World Source Delivery

The owner-local delivery worker uses `firemud.authored-world-source.enabled` (default `false`), `delivery.batch-size` (default `25`, range `1..100`) and `delivery.poll-interval-ms` (default `5000`, minimum `1000`). `FIREMUD_GRPC_WORKLOAD_NAMESPACE` supplies the exact workload namespace; no namespace is inferred. The worker reads bounded namespace-qualified pending pages, invokes the existing authenticated World intake and independent readback outside database transactions, then acknowledges the exact durable result. Failed or ambiguous deliveries retain their original request identity for automatic retry.

The feature remains disabled until the [creator/source prerequisites](../../../project-management/implementation-tracking/game-authoring-publishing-and-activation.md#current-status) are proved. Configuration and workload authentication do not establish creator authorization, release attestation or runtime admission. The source contract remains owned by [API Contracts](./api-contracts.md).

## Asset Store

Published assets are uploaded to an S3-compatible bucket. Configure the client with:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `ASSET_STORE_ENDPOINT` | Private authenticated S3-compatible API used by Game Design reads and writes | *(unset)* |
| `ASSET_STORE_PUBLIC_BASE_URL` | Separately configured `/assets` base used for generated manifest links; never defaults from the private endpoint | *(unset)* |
| `ASSET_STORE_BUCKET` | Bucket used for published assets | *(unset)* |
| `ASSET_STORE_REGION` | Region name for the S3 client | `ap-southeast-2` |
| `ASSET_STORE_ACCESS_KEY` | Access key for the bucket | *(unset)* |
| `ASSET_STORE_SECRET_KEY` | Secret key for the bucket | *(unset)* |
