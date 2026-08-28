# Dev Tools

This directory contains repository-owned operator, CI, and local-development tooling.

Keep the root of `dev-tools/` small. Only canonical human-facing entrypoints and shared runtime helpers should live directly here. Category-specific helpers belong in a subfolder.

## Canonical root entrypoints

- `firemud-cli.sh` – simple local stack shortcuts built on top of Gradle tasks.
- `verify-fresh-bootstrap.sh` – canonical source-built Docker smoke proof from a clean state. It requires the explicit per-run ID/project binding defined in [Testing: player-flow smoke and reset boundaries](../design/architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries) before destructive teardown and defaults to the read-only `LOGIN -> PLAY -> LOOK` baseline over both transports. Defaults to a per-service serial build path for local reliability; set `FIREMUD_SMOKE_SERIAL_BUILD=0` to use a normal compose build and default `COMPOSE_PARALLEL_LIMIT=4`. Set `FIREMUD_SMOKE_NO_CACHE_SERVICES="service-a service-b"` when a smoke proof must force a fresh compose rebuild for specific Docker Compose service ids without turning the whole stack into a cold no-cache build; use `gateway`, not the Gradle module name `spring-cloud-gateway`. When running from WSL, keep Docker on a native Linux CLI pointed at `unix:///var/run/docker.sock`; Windows `docker.exe` wrappers can look healthy but break the bind mounts this proof depends on.
- `verify-restart-state.sh` – canonical source-built Docker smoke proof with preserved local state. Set the explicit per-run ID/project binding defined in [Testing: player-flow smoke and reset boundaries](../design/architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries) before running.
- `verify-smoke-images.sh` – canonical GHCR/image-tag Docker smoke proof. Set the explicit per-run ID/project binding defined in [Testing: player-flow smoke and reset boundaries](../design/architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries) before running; it defaults to the baseline and rejects shared-state mutation parity.
- `build-compose-service-jars.sh` – rebuilds the service boot jars consumed by the source-built Compose stack.
- `verify-compose-health.sh` – shared health gate used by the smoke entrypoints.
- `validation/run-locked-gradle.sh` – canonical local verification wrapper that prevents overlapping service-level Gradle runs from writing the same test-result trees at once.
- `validation/inspect-test-results.sh` – read-only JUnit XML summary helper for diagnosing quiet post-suite Gradle tails without guessing at process state.
- `evidence_digest.py` – shared canonical RFC 8785-subset evidence digest helper imported by deployment and validation gates.
- `wait-for-it.sh` – shared Docker image/runtime helper.

## Folder map

- `backups/` – local and operator backup helpers; see `backups/README.md` for the script map.
- `certs/` – tracked certificate helper scripts plus ignored generated local TLS material.
- `deploy/` – deployment preflight and overlay validation; see `deploy/README.md` for the script map.
- `hosted/` – hosted Kubernetes environment tooling; see `hosted/README.md` for the lane split.
- `docs/` – documentation generation and validation helpers; see `docs/README.md` for the script map.
- `kreya/` – Kreya gRPC client assets.
- `load-testing/` – Gatling load-testing module.
- `maintenance/` – non-canonical maintenance and analysis utilities that should not shape repo workflow.
- `observability/` – observability contract and evidence validators.
- `release/` – release/notice generation utilities.
- `restores/` – restore, state-reset, and external-credential validation helpers; see `restores/README.md` for the script map.
- `smoke/` – shared smoke defaults and the run-owned Compose access helper.
- `seed/` – local and test data seeding helpers.
- `tests/` – contract tests for repo-owned tooling.
- `validation/` – repo policy and static validation scripts used by Gradle and CI.

## Placement guidance

- Put a tool in `validation/` when its main job is enforcing a repo policy or static contract in CI/Gradle.
- Put a tool in `deploy/`, `hosted/shared/`, `hosted/preview/`, `hosted/dev-demo/`, `backups/`, or `restores/` when it owns a real operational lane.
- Keep root-level scripts only when they are canonical entrypoints contributors are expected to run directly.
- Avoid placing personal or AI-host-specific maintenance scripts at the root. If they remain in the repo at all, keep them under `maintenance/`.
