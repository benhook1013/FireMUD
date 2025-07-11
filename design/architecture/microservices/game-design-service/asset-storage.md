# Asset Storage Setup

Game assets such as icons or sound files are stored directly in the Game Design Service database.
Each record is tied to a `tenantId` so assets remain isolated between games.

## Table Structure

The `game_assets` table stores the raw binary data. Columns include:

- `id` – primary key
- `tenant_id` – identifies the owner game
- `file_name` – original file name
- `content_type` – MIME type
- `data` – binary blob
- `created_at` – upload timestamp

Assets are uploaded via `POST /assets` and returned as `GameAssetDto` objects.
A basic repository and service persist uploads using Spring Data JPA.

See [Game Design Service Architecture](../../../design/architecture/microservices/game-design-service/README.md) for how these assets fit into published versions.
