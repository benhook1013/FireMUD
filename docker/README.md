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
# Run the fresh/bootstrap restart chain in the single owning resource session below.


```

These entrypoints run the read-only `LOGIN` -> `PLAY` -> `LOOK` baseline over both transports. The fresh-bootstrap and restart-state chain below intentionally reuses the same explicit run ID/project and ownership token; image smoke uses a separate run ID/project and a fresh token. By default each wrapper performs authenticated teardown before returning, including on proof failure or a handled signal. `--keep-running` explicitly retains the stack and claim for authenticated manual inspection/direct teardown; the caller must retain the original token and perform final owner-guarded teardown. Restart preserves volumes during its proof, while its default final cleanup disposes the run-owned deployment. Successful probes with failed or ambiguous cleanup are an overall failure; an earlier failed-proof status remains the primary exit status. A retained live project prevents admission to a new expensive proof session, even with its original capability; continuation proofs must stay within the original owning resource session. Do not use keep mode to park completed automated stacks.

The owner-guarded `stop_run_owned_compose_project` helper in [`run-owned-compose.sh`](../dev-tools/smoke/run-owned-compose.sh) is the cleanup path. It requires the original run ID, Compose project name, and capability, plus the same Compose files (including the smoke-image override and matching tag for image smoke). It performs the guarded `down -v` and releases the marker only after project resources are absent. Do not remove the marker manually. The helper stores only a capability digest in its private marker; local runs derive that digest from the ownership token.

Automated local chains that need a retained intermediate stack must run within one resource session, covering builds, startup, probes, and final cleanup. The same session admits nested Gradle without releasing its user-wide lock. With the run identity and token exported as above, run from the repository root:

```bash
bash dev-tools/validation/run-locked-gradle.sh --resource-session bash -e -c '
  source dev-tools/smoke/run-owned-compose.sh
  cleanup() {
    status=$?
    trap - EXIT
    trap "" INT TERM HUP
    if ! stop_run_owned_compose_project \
      -f docker/docker-compose.yml \
      -f docker/docker-compose.override.yml \
      -f docker/docker-compose.grpc-mtls.override.yml; then
      ((status != 0)) || status=1
    fi
    exit "$status"
  }
  trap cleanup EXIT
  trap "exit 130" INT
  trap "exit 143" TERM
  trap "exit 129" HUP
  bash dev-tools/verify-fresh-bootstrap.sh --keep-running
  bash dev-tools/verify-restart-state.sh --keep-running
'
```

The session lock serializes other local Gradle and runtime proof sessions across worktrees through teardown. Exact project IDs also remain in the existing resource metadata after the session exits or its owner dies. Subsequent local admission performs bounded read-only checks for only those projects; running or unavailable container state blocks heavy work. After original-capability teardown or scoped recovery, an ordinary retry automatically clears the resource hold when containers are stopped or absent. Retained volumes/networks alone do not block heavy admission, while failed full teardown still retains the separate smoke ownership marker and failed cleanup disposition. It cannot guarantee Docker cleanup after SIGKILL, WSL shutdown, or daemon unavailability. Unknown cleanup retains the ownership fence and requires owner recovery; never reconstruct the token from the marker or reclaim an unmarked deployment. Hosted CI may explicitly retain a stack for its bounded post-proof log inspection and existing final authenticated teardown.

For a separate disposable image proof, choose a new identity and token:

```bash
export FIREMUD_SMOKE_RUN_ID="local-image-$(date +%s)-$$"
export COMPOSE_PROJECT_NAME="firemud-smoke-${FIREMUD_SMOKE_RUN_ID}"
export FIREMUD_SMOKE_OWNERSHIP_TOKEN="$(openssl rand -hex 32)"
# Placeholder only: replace with the published full commit SHA before running.
SMOKE_IMAGE_TAG=0123456789abcdef0123456789abcdef01234567 dev-tools/verify-smoke-images.sh
```

The 40-hex value shown above is a placeholder, not a usable image tag. Obtain a tested, published commit-SHA tag from [Build Runtime Images](../.github/workflows/runtime-images.yml), or the immutable `pr-merge-<merge SHA>` tag for an exact PR merge, then pass it as `SMOKE_IMAGE_TAG`. The image smoke script requires a non-empty tag but does not itself prove the tag's source or validation lineage.

`SMOKE_IMAGE_LOCAL_ONLY=true` is supported only when matching tagged FireMUD images are already present locally. It sets their pull policy to `never`, while external dependency images may still be pulled.

The PR Full-Stack Smoke job also sets `SMOKE_MINIO_LOCAL_ONLY=true` after building the pinned MinIO server and client sources. It passes unique local image tags and the build-reported `sha256:` IDs to `dev-tools/verify-smoke-images.sh`; the wrapper verifies the tags still resolve to those IDs and that the optional PR Compose overlay renders those tags with `pull_policy: never`. Leave this option unset for ordinary published smoke so the canonical Compose files continue to own dependency image selection.

Do not request mutating parity through these entrypoints; the wrappers reject `SMOKE_MUTATION_EXTENSION=true` until independent transport state exists. Do not treat `docker/docker-compose.smoke-images.override.yml` as a standalone ad hoc compose file. Its contract is to be driven through the explicit ID/project binding defined in [Testing: player-flow smoke and reset boundaries](../design/architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries), with `SMOKE_IMAGE_TAG=<tag>` passed to `dev-tools/verify-smoke-images.sh`; the script writes the required local env override and runs the baseline smoke flow.

Fresh-bootstrap and image smoke intentionally use guarded `down -v` teardown for their claimed Compose project, deleting that project's `postgres-data`, `redis-coord-data`, and `minio-data` volumes. Restart-state preserves named volumes during restart; its default final proof cleanup disposes them, while explicit `--keep-running` retains them. `devDown` preserves named volumes. This disposes a test deployment; it is not authority to reset Coordination Redis. Follow the owning [Redis reset and recovery](../design/architecture/system-architecture-redis-reset-and-recovery.md) contract for reset questions.

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

## PostgreSQL 18 and Retained Data

Local Compose and hosted preview use the pinned PostgreSQL 18 image with `PGDATA=/var/lib/postgresql/18/docker` and the PostgreSQL volume mounted at `/var/lib/postgresql`, matching the [official image's PostgreSQL 18 layout](https://github.com/docker-library/docs/blob/master/postgres/README.md#pgdata). Compose retains the existing `postgres-data` volume identity. Its entrypoint guard and the preview init-container guard allow an empty fresh volume or a complete PostgreSQL 18 cluster at `18/docker`; they refuse an old Compose cluster at the volume root, an old preview cluster at `pgdata/`, and every other nonempty layout. Startup does not convert a retained cluster or treat it as a fresh database.

To keep PostgreSQL 16 data, take the affected applications offline, create a logical backup from the retained source, stop PostgreSQL 16, and restore into a separate empty PostgreSQL 18 target volume. Do not move or copy PostgreSQL data files between majors; PostgreSQL documents logical dump/restore, `pg_upgrade`, or logical replication as the supported major-upgrade choices. This FireMUD path uses the existing logical backup and restore tools: `pg-dump-rotate.sh` creates the scheduled plain-SQL `.sql.gz` artifact consumed by `restore-latest-db.sh` or `gunzip -c <dump.sql.gz> | psql -v ON_ERROR_STOP=1 --single-transaction`; `backup-db.sh` creates a custom-format `.dump` artifact consumed by `restore-db.sh` and `pg_restore`. See the [backup helper guide](../dev-tools/backups/README.md), [restore helper guide](../dev-tools/restores/README.md), and [PostgreSQL major-upgrade documentation](https://www.postgresql.org/docs/18/upgrading.html).

Keep the source volume or PVC unchanged throughout the restore. Validate the imported database on the separate target before configuring the regular Compose project to use that volume. A failed import changes only the target, so it can be repaired or recreated while the original source remains available. These local and preview steps do not establish production HA packaging or production recovery readiness.
