# Docker Resources

This directory holds the local Docker/Compose runtime surfaces for FireMUD. Run commands from the repository root.

Copy `.env.sample` to `.env` in the repository root if you need to override the default local credentials or service settings.

## Local Docker Lanes

Use the lane that matches what you are trying to prove:

- Source-built Compose stack
  - Uses `docker/docker-compose.yml` plus `docker/docker-compose.override.yml`
  - Builds current boot jars, then builds service images from `services/*/build/libs`
  - Best for normal local development and source-built smoke
- Local prebuilt-image Compose stack
  - Adds `docker/docker-compose.local-images.override.yml`
  - Uses the locally built `*:0.1.0` images from Gradle `bootBuildImage`
  - Best for `./gradlew devUp` / `devDown`
- GHCR smoke-image stack
  - Adds `docker/docker-compose.smoke-images.override.yml`
  - Pulls `ghcr.io/benhook1013/*:${SMOKE_IMAGE_TAG}`
  - Must be driven with `SMOKE_IMAGE_TAG=<tag>`
  - Best for image-tag smoke proof and CI-aligned runtime validation

## Canonical Commands

Source-built local stack:

```bash
dev-tools/build-compose-service-jars.sh
docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml up --build -d
docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down
```

Canonical smoke/bootstrap proof:

```bash
dev-tools/verify-fresh-bootstrap.sh
dev-tools/verify-restart-state.sh
SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh
```

Do not treat `docker/docker-compose.smoke-images.override.yml` as a standalone ad hoc compose file. Its contract is to be driven through `SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh`, which validates the tag, writes the required local env override, and runs the full smoke flow.

Gradle-managed local prebuilt-image stack:

```bash
./gradlew devUp
./gradlew devDown
```

## Local Runtime Notes

- Compose service discovery uses Docker DNS plus the defaults in service `application.yml` files and the local Compose env overrides; it does not depend on a separate Spring `dev` profile lane.
- The local override files intentionally relax internal gRPC/TLS settings for the Docker development stack.
- The Compose stack also runs `pg-dump-cron`, which writes rotated PostgreSQL dumps under `docker/backups/`.
