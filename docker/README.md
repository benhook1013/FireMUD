# Docker Resources

This directory holds the local Docker/Compose runtime surfaces for FireMUD. Run commands from the repository root.

Copy `.env.sample` to `.env` in the repository root before running the local Compose stack. `.env.sample` is the canonical source for local default credentials and service settings; `docker-compose.yml` intentionally reads from `.env` instead of repeating those defaults inline.

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
COMPOSE_PROJECT_NAME=firemud-smoke-local-20260829-1 dev-tools/verify-fresh-bootstrap.sh
dev-tools/verify-restart-state.sh
COMPOSE_PROJECT_NAME=firemud-smoke-local-image-20260829-1 SMOKE_IMAGE_TAG=sha-0123456789abcdef dev-tools/verify-smoke-images.sh
```

These entrypoints run the read-only `LOGIN` -> `PLAY` -> `LOOK` baseline over both transports. Do not request mutating parity through them; the wrappers reject `SMOKE_MUTATION_EXTENSION=true` until independent transport state exists. Do not treat `docker/docker-compose.smoke-images.override.yml` as a standalone ad hoc compose file. Its contract is to be driven through `COMPOSE_PROJECT_NAME=<run-owned-name> SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh`, which validates the tag, writes the required local env override, and runs the baseline smoke flow. Whole-stack teardown is test-deployment disposal, not a Coordination Redis reset.

Gradle-managed local prebuilt-image stack:

```bash
./gradlew devUp
./gradlew devDown
```

## Local Runtime Notes

- Compose service discovery uses Docker DNS plus the defaults in service `application.yml` files and the local Compose env overrides; it does not depend on a separate Spring `dev` profile lane.
- The local override files intentionally relax internal gRPC/TLS settings for the Docker development stack.
- The Compose stack also runs `pg-dump-cron`, which writes rotated PostgreSQL dumps under `docker/backups/`.
