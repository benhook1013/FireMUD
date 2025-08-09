# Asset Storage Setup

Game assets such as icons or sound files are uploaded through the Game Design
Service at design time. They are stored in the service database while being
edited. When a version is published, the service uploads these assets to
tenant- and version-scoped object storage (e.g., S3, MinIO, or a CDN) and
generates a `manifest.json` that maps asset keys to public URLs. A manifest is
produced for every published version, even if no assets are present. The manifest is
stored alongside the assets and its URL is recorded in the published version
metadata so runtime clients can retrieve it. The Game Design Service is not
queried during gameplay. Each record remains tied to a `tenantId` so icons, UI
images, and audio files are isolated per game.

## Table Structure

The `game_assets` table stores the raw binary data. Columns include:

- `id` – primary key
- `tenant_id` – identifies the owning game as a GUID string stored in `VARCHAR(36)`
- `file_name` – original file name
- `content_type` – MIME type
- `data` – binary blob
- `created_at` – upload timestamp

The `data` column uses PostgreSQL `BYTEA` type to store the file contents.
When returned by the REST API this byte array is Base64 encoded by default so the JSON response remains text based.

An index named `idx_game_assets_tenant` speeds up queries scoped to a tenant.

## API

Assets are uploaded via `POST /assets` using a `multipart/form-data` request and the saved record, including the binary `data` field, is returned as a `GameAssetDto`.
See the [OpenAPI specification](../../../../services/game-design-service/src/main/resources/openapi.yaml) for request details.
Endpoints for downloading or deleting assets are not provided yet. (TODO: Not yet implemented)
There is also no gRPC endpoint for asset management at this time. (TODO: Not yet implemented)
Listing assets for a tenant is also planned but not available. (TODO: Not yet implemented)

A basic repository (`GameAssetRepository`) and service implementation
(`GameAssetServiceImpl`) persist uploads using Spring Data JPA.

At publish time, assets are exported from the database to object storage and
referenced in the generated `manifest.json`. Runtime clients load branding and
theme resources directly from the CDN using this manifest; the Game Design
Service is not involved. See [Game Design Service Architecture](README.md) for
how these assets fit into published versions.

The export location is configured with `ASSET_STORE_ENDPOINT`,
`ASSET_STORE_BUCKET`, `ASSET_STORE_REGION`, `ASSET_STORE_ACCESS_KEY`, and
`ASSET_STORE_SECRET_KEY`. For development, the Docker Compose stack runs a
`minio` container that satisfies these variables.
