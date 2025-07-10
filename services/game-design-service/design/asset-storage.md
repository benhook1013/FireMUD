# Asset Storage Setup

This document describes how the Game Design Service stores uploaded assets such as images or audio files.

## Database Schema

Design assets are tracked in a `design_asset` table that records:

- `id` – unique asset identifier
- `tenant_id` – owning game
- `path` – storage location or object key
- `mime_type` – content type
- `size` – byte length

Binary payloads are stored externally (e.g., S3) or in a mounted volume referenced by `path`. Local development defaults to a directory defined by `ASSET_STORAGE_PATH`.

## Upload API

```bash
curl -X POST http://localhost:8080/assets \
     -F "file=@hero.png" \
     -F tenantId=1
```

The service validates the file and writes a record to the database. Assets are copied to domain services when a version is published so runtime servers never access the design database.

## Configuration

Set `ASSET_STORAGE_PATH` to a writable directory when running locally. Production deployments should configure an object storage bucket or persistent volume claim.

See the [Game Design Service architecture](../../../design/architecture/microservices/game-design-service/README.md) for overall context.
