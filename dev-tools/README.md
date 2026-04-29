# Dev Tools

This directory contains repository-owned operator, CI, and local-development tooling.

Keep the root of `dev-tools/` small. Only canonical human-facing entrypoints and shared runtime helpers should live directly here. Category-specific helpers belong in a subfolder.

## Canonical root entrypoints

- `firemud-cli.sh` – simple local stack shortcuts built on top of Gradle tasks.
- `verify-fresh-bootstrap.sh` – canonical source-built Docker smoke proof from a clean state.
- `verify-restart-state.sh` – canonical source-built Docker smoke proof with preserved local state.
- `verify-smoke-images.sh` – canonical GHCR/image-tag Docker smoke proof.
- `build-compose-service-jars.sh` – rebuilds the service boot jars consumed by the source-built Compose stack.
- `verify-compose-health.sh` – shared health gate used by the smoke entrypoints.
- `reset-service-db.sh` – local service-scoped schema reset helper.
- `wait-for-it.sh` – shared Docker image/runtime helper.

## Folder map

- `backups/` – local and operator backup helpers; see `backups/README.md` for the script map.
- `certs/` – tracked certificate helper scripts plus ignored generated local TLS material.
- `deploy/` – deployment preflight and overlay validation; see `deploy/README.md` for the script map.
- `hosted/dev-demo/` – fixed `develop` hosted environment helpers.
- `docs/` – documentation generation and validation helpers.
- `insomnia/` – Insomnia client assets.
- `kreya/` – Kreya gRPC client assets.
- `load-testing/` – Gatling load-testing module.
- `maintenance/` – non-canonical maintenance and analysis utilities that should not shape repo workflow.
- `observability/` – observability contract and evidence validators.
- `hosted/preview/` – hosted PR preview orchestration helpers.
- `release/` – release/notice generation utilities.
- `restores/` – restore and external-credential validation helpers.
- `seed/` – local and test data seeding helpers.
- `tests/` – contract tests for repo-owned tooling.
- `validation/` – repo policy and static validation scripts used by Gradle and CI.

## Placement guidance

- Put a tool in `validation/` when its main job is enforcing a repo policy or static contract in CI/Gradle.
- Put a tool in `deploy/`, `hosted/preview/`, `hosted/dev-demo/`, `backups/`, or `restores/` when it owns a real operational lane.
- Keep root-level scripts only when they are canonical entrypoints contributors are expected to run directly.
- Avoid placing personal or AI-host-specific maintenance scripts at the root. If they remain in the repo at all, keep them under `maintenance/`.
