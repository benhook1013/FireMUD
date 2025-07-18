# Asset Storage Setup

Game assets such as icons or sound files are stored directly in the Game Design Service database.
Each record is tied to a `tenantId` so assets remain isolated between games. This keeps icons, UI images and audio files scoped to a single project.

## Table Structure

The `game_assets` table stores the raw binary data. Columns include:

- `id` – primary key
- `tenant_id` – identifies the owner game
- `file_name` – original file name
- `content_type` – MIME type
- `data` – binary blob
- `created_at` – upload timestamp

The `data` column uses PostgreSQL `BYTEA` type to store the file contents.

An index named `idx_game_assets_tenant` speeds up queries scoped to a tenant.

## API

Assets are uploaded via `POST /assets` and the saved record, including the binary `data` field, is returned as a `GameAssetDto`.
See the [OpenAPI specification](../../../../services/game-design-service/src/main/resources/openapi.yaml) for request details.
Endpoints for downloading or deleting assets are not provided yet. (TODO: Not yet implemented)

A basic repository and service persist uploads using Spring Data JPA.

When a design version is published these asset records will be copied to runtime services along with other game data. (TODO: Not yet implemented)

See [Game Design Service Architecture](README.md) for how these assets fit into published versions.
