# Docker Resources

This directory holds the local Docker/Compose runtime surfaces for FireMUD. Run commands from the repository root.

Copy `.env.sample` to `.env` in the repository root before running the local Compose stack. `.env.sample` is the canonical source for local default credentials and service settings; `docker-compose.yml` intentionally reads from `.env` instead of repeating those defaults inline. The Gradle-managed `devUp` task can create and synchronize this file for you.

## Local Docker Lanes

Use the lane that matches what you are trying to prove:

- Source-built Compose stack
  - Uses `docker/docker-compose.yml` plus `docker/docker-compose.override.yml`
  - Builds current boot jars, then builds service images from `services/*/build/libs`
  - Use the canonical `dev-tools/verify-fresh-bootstrap.sh` entrypoint for source-built smoke
- Local prebuilt-image Compose stack
  - Adds `docker/docker-compose.local-images.override.yml`
  - Uses application images from Gradle `bootBuildImage` and the separately built `pg-dump-cron` image
  - Best for `./gradlew devUp` / `devDown`
- GHCR smoke-image stack
  - Adds `docker/docker-compose.smoke-images.override.yml`
  - Pulls `ghcr.io/benhook1013/*:${SMOKE_IMAGE_TAG}`
  - Must be driven with `SMOKE_IMAGE_TAG=<tag>`
  - Best for image-tag smoke proof and CI-aligned runtime validation

## Canonical Commands

Source-built local stack:

```bash
dev-tools/ensure-local-compose-env.sh
dev-tools/certs/ensure-dev-certs.sh
dev-tools/build-compose-service-jars.sh
docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml up --build -d
docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down
```

For ordinary local development, prefer `./gradlew devUp` and `./gradlew devDown`. The Gradle task builds the base image, application images, and `pg-dump-cron` image, generates development certificates, synchronizes the local Compose environment, and waits for the requested services to become ready. The raw source-built commands above are useful when iterating directly on the Compose build path; the certificate helper prepares the mounted local material, while the local override still selects plaintext internal gRPC for this development stack.

Canonical smoke/bootstrap proof:

```bash
export FIREMUD_SMOKE_RUN_ID="local-$(date +%s)-$$"
export COMPOSE_PROJECT_NAME="firemud-smoke-${FIREMUD_SMOKE_RUN_ID}"
export FIREMUD_SMOKE_OWNERSHIP_TOKEN="$(openssl rand -hex 32)"
dev-tools/verify-fresh-bootstrap.sh
dev-tools/verify-restart-state.sh

export FIREMUD_SMOKE_RUN_ID="local-image-$(date +%s)-$$"
export COMPOSE_PROJECT_NAME="firemud-smoke-${FIREMUD_SMOKE_RUN_ID}"
export FIREMUD_SMOKE_OWNERSHIP_TOKEN="$(openssl rand -hex 32)"
# Placeholder only: replace with the published full commit SHA before running.
SMOKE_IMAGE_TAG=0123456789abcdef0123456789abcdef01234567 dev-tools/verify-smoke-images.sh
```

These entrypoints run the read-only `LOGIN` -> `PLAY` -> `LOOK` baseline over both transports. The fresh-bootstrap and restart-state commands above intentionally reuse the same explicit run ID/project and ownership token; image smoke uses a separate run ID/project and a fresh token. A successful wrapper leaves its stack and ownership claim running so a matching restart or separately authorized smoke leg can reuse it.

The owner-guarded `stop_run_owned_compose_project` helper in [`run-owned-compose.sh`](../dev-tools/smoke/run-owned-compose.sh) is the cleanup path. It requires the original run ID, Compose project name, and capability, plus the same Compose files (including the smoke-image override and matching tag for image smoke). It performs the guarded `down -v` and releases the marker only after project resources are absent. Do not remove the marker manually. The helper stores only a capability digest in its private marker; local runs derive that digest from the ownership token.

The 40-hex value shown above is a placeholder, not a usable image tag. Obtain the published full commit SHA from a successful [Build and Publish Docker Images](../.github/workflows/docker-images.yml) or [Build Runtime Images](../.github/workflows/runtime-images.yml) run/artifact, or an equivalent repository-owned publication source, then pass it as `SMOKE_IMAGE_TAG`. The image smoke script requires a non-empty tag but does not validate its format; the expected publication contract is a full 40-character lowercase commit SHA.

`SMOKE_IMAGE_LOCAL_ONLY=true` is supported only when matching tagged FireMUD images are already present locally. It sets their pull policy to `never`, while external dependency images may still be pulled.

Do not request mutating parity through these entrypoints; the wrappers reject `SMOKE_MUTATION_EXTENSION=true` until independent transport state exists. Do not treat `docker/docker-compose.smoke-images.override.yml` as a standalone ad hoc compose file. Its contract is to be driven through the explicit ID/project binding defined in [Testing: player-flow smoke and reset boundaries](../design/architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries), with `SMOKE_IMAGE_TAG=<tag>` passed to `dev-tools/verify-smoke-images.sh`; the script writes the required local env override and runs the baseline smoke flow.

Fresh-bootstrap and image smoke intentionally use guarded `down -v` teardown for their claimed Compose project, deleting that project's `postgres-data`, `redis-coord-data`, and `minio-data` volumes. Restart-state and `devDown` preserve named volumes. This disposes a test deployment; it is not authority to reset Coordination Redis. Follow the owning [Redis reset and recovery](../design/architecture/system-architecture-redis-reset-and-recovery.md) contract for reset questions.

Gradle-managed local prebuilt-image stack:

```bash
./gradlew devUp
./gradlew devDown
```

`./gradlew buildDockerImages` builds the shared base image, all application images through `bootBuildImage`, and `pg-dump-cron` through its separate `buildPgDumpCronImage` task. Local image names and the canonical project version are maintained in [`docker-compose.local-images.override.yml`](docker-compose.local-images.override.yml) and [`gradle.properties`](../gradle.properties).

## Local Runtime Notes

- Compose service discovery uses Docker DNS plus the defaults in service `application.yml` files and the local Compose env overrides; it does not depend on a separate Spring `dev` profile lane.
- The local override files intentionally relax internal gRPC/TLS settings for the Docker development stack.
- The Compose stack also runs `pg-dump-cron`, which writes rotated PostgreSQL dumps under `docker/backups/`.
- The scripted smoke clients require Python 3 and the `websocket-client` package; see [Smoke Tests for Login + PLAY + LOOK](../design/developer-workflows/login-session-smoke-tests.md#requirements) for the client prerequisites and transport-specific controls.
