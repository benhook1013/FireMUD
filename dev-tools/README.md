# Dev Tools

This directory contains repository-owned operator, CI, and local-development tooling.

Keep the root of `dev-tools/` small. Only canonical human-facing entrypoints and shared runtime helpers should live directly here. Category-specific helpers belong in a subfolder.

## Canonical root entrypoints

- `firemud-cli.sh` – simple local stack shortcuts built on top of Gradle tasks.
- `verify-fresh-bootstrap.sh` – canonical source-built Docker smoke proof from a clean state. It establishes a new claim when the expected project is empty, or reuses the exact matching claim/capability for the exact per-run ID/project binding defined in [Testing: player-flow smoke and reset boundaries](../design/architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries) before destructive teardown, and defaults to the read-only `LOGIN -> PLAY -> LOOK` baseline over both transports. Defaults to a per-service serial build path for local reliability; set `FIREMUD_SMOKE_SERIAL_BUILD=0` to use a normal compose build and default `COMPOSE_PARALLEL_LIMIT=4`. Set `FIREMUD_SMOKE_NO_CACHE_SERVICES="service-a service-b"` when a smoke proof must force a fresh compose rebuild for specific Docker Compose service ids without turning the whole stack into a cold no-cache build; use `gateway`, not the Gradle module name `spring-cloud-gateway`. When running from WSL, keep Docker on a native Linux CLI pointed at `unix:///var/run/docker.sock`; Windows `docker.exe` wrappers can look healthy but break the bind mounts this proof depends on. The complete local proof shares the user-wide Gradle resource guard and cleans its owned stack before returning by default; intentional continuation requires `--keep-running`, the original capability, and the same owning resource session (see the [Docker lifecycle examples](../docker/README.md#canonical-commands)).
- `verify-restart-state.sh` – canonical source-built Docker smoke proof with preserved local state. It requires/reuses an already-existing matching claim/capability for the exact per-run ID/project binding defined in [Testing: player-flow smoke and reset boundaries](../design/architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries) before running. Default completion tears down the claimed project; use `--keep-running` only with an explicit owner of final teardown.
- `verify-smoke-images.sh` – canonical GHCR/image-tag Docker smoke proof. Invoke it as `SMOKE_IMAGE_TAG=<published-full-commit-SHA> dev-tools/verify-smoke-images.sh`; see the [Docker guide](../docker/README.md#canonical-commands) for tag semantics and local-only mode. It establishes a new claim when the expected project is empty, or reuses the exact matching claim/capability for the exact per-run ID/project binding defined in [Testing: player-flow smoke and reset boundaries](../design/architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries) before running; it defaults to the baseline and rejects shared-state mutation parity. The same default cleanup and explicit keep-running lifecycle applies.
- `build-compose-service-jars.sh` – rebuilds the service boot jars consumed by the source-built Compose stack.
- `ensure-local-compose-env.sh` – creates or completes the local root `.env` from `.env.sample` and synchronizes `docker/.env` for Compose-based smoke commands.
- `build-local-smoke-images.sh` – builds the pinned local base and service images for a 40-character commit tag after rebuilding the service boot jars.
- `verify-compose-health.sh` – shared health gate used by the smoke entrypoints.
- `validation/run-locked-gradle.sh` – canonical local Gradle wrapper that serializes heavy builds across worktrees, protects shared output trees, and bounds lock acquisition and cancellation start. Use it for local proof and service boot-jar builds; [Local Gradle execution](../design/developer-workflows/validation-and-runtime-proof.md#local-gradle-execution) owns configuration and cancellation limits. For example: `bash dev-tools/validation/run-locked-gradle.sh :account-service:test`.
- `validation/run-gradle-with-dependency-recovery.sh` – shared one-retry runner for the complete affected-module check and source-built boot-jar commands. It refreshes dependency metadata only after a recognized repository-resolution failure; each caller supplies its complete Gradle command and runner.
- `validation/inspect-test-results.sh` – read-only JUnit XML summary helper for diagnosing quiet post-suite Gradle tails without guessing at process state. For example: `bash dev-tools/validation/inspect-test-results.sh account-service`. This is diagnostic evidence only and does not prove that the Gradle invocation completed cleanly.
- `validation/validate-jacoco-combined-report.py` – checks that the exact JaCoCo XML selected for Codecov contains covered representative production classes from the unit and PostgreSQL integration paths. CI applies it to `common-saga` after the combined report is generated and before upload; its focused proof is [the validator test](validation/test_validate_jacoco_combined_report.py).
- `validation/report-worktree-pr-topology.sh` – read-only local worktree, branch, and open-PR inventory. Use `bash dev-tools/validation/report-worktree-pr-topology.sh --json` for a machine-readable inventory, or `bash dev-tools/validation/report-worktree-pr-topology.sh --pr N --include-renovate --json` for a bounded exact branch/SHA selected-stack report that includes Renovate heads. Renovate heads are excluded from selected stacks unless `--include-renovate` is supplied; default selected-stack output exposes exact linked exclusions in `omitted_renovate` (and a corresponding text note) without including those heads. Ambiguous branch or PR identities fail closed. Its focused proof is [worktree-topology-contract.sh](tests/worktree-topology-contract.sh).
- `evidence_digest.py` – shared canonical RFC 8785-subset evidence digest helper imported by deployment and validation gates.
- `wait-for-it.sh` – shared Docker image/runtime helper.

The `report-worktree-pr-topology.sh` and `maintenance/cloc-report.py pr` entrypoints require GitHub CLI `gh` >= 2.63.0 because they request the `baseRefOid` field. The repository workflow pin is sourced from `config/workflow-tool-versions.env` as `GH_VERSION`.

## FireController

FireController is maintained outside this repository. Use the `firecontroller-use` skill with your supplied own role-bound connection and the documentation shipped with its installed release; `firecontroller-onboard` is for setup or repair. The [PR lifecycle](../design/developer-workflows/pr-lifecycle.md) retains FireMUD review policy and authority. Controller service changes belong to FireController Worker. Sealed legacy data, captures, migration proof, frozen comparison sites, and installed runtimes remain retained outside this source retirement.

## Folder map

- `backups/` – local and operator backup helpers; see `backups/README.md` for the script map.
- `certs/` – tracked certificate helper scripts plus ignored generated local TLS material.
- `certs/ensure-local-compose-mtls.sh` – prepares ordinary local-development workload certificates for the `devUp` transport profile; [Docker resources](../docker/README.md#local-runtime-notes) owns its usage and limits. Run-owned smoke certificates retain their separate ownership and fixture boundary.
- `deploy/` – deployment preflight and overlay validation; see `deploy/README.md` for the script map.
- `hosted/` – hosted Kubernetes environment tooling; see `hosted/README.md` for the lane split.
- `hosted/account-bootstrap/` – disposable fixture ownership and review-only factory inventory tooling; see the [manual qualification workflow](../.github/workflows/game-session-factory-qualification.yml) and [focused contract](tests/game-session-factory-qualification-contract.sh).
- `docs/` – documentation generation and validation helpers; see `docs/README.md` for the script map.
- `kreya/` – Kreya gRPC client assets.
- `load-testing/` – Gatling load-testing module.
- `maintenance/` – maintenance and analysis utilities, including `maintenance/cloc-report.py` for repository summaries and PR impact snippets. Report generation is read-only by default; `pr ... --update-pr` performs the guarded marked PR-body update defined by the [PR lifecycle](../design/developer-workflows/pr-lifecycle.md). Generated PR LOC blocks carry exact base, merge-base, and head metadata for freshness checks. Use `python3 dev-tools/maintenance/cloc-report.py --help` for invocation details; its focused proof is [cloc-report-contract.sh](tests/cloc-report-contract.sh).
- `maintenance/refresh-gradle-daemon-jvm.py` – validates official Adoptium metadata for six supported platforms and regenerates Java 21 daemon artifact URLs through Gradle's native generator and verifies the generated criteria and one-file boundary. The [daemon maintenance workflow](../design/developer-workflows/validation-and-runtime-proof.md#daemon-jdk-artifact-maintenance) owns scheduling, App setup, PR publication, and failure handling.
- `maintenance/update-workflow-tool.py` – updates repository-frozen checksum tuples for GitHub CLI, Buf, Kubeconform, Trivy, and Lychee. Kubectl, Helm, and the Velero CLI instead keep one exact version in the repository and fetch the publisher's checksum for that exact version during installation; `maintenance/verify-publisher-checksum.sh` validates the sidecar format, exact asset filename where the publisher supplies one, and downloaded bytes before installation. This detects corruption and publisher-side mismatches but deliberately trusts the publisher's current HTTPS artifact and checksum together rather than retaining a separately reviewed hash. The Kubernetes 1.34 hosted cluster constrains Renovate's kubectl proposals to `<1.36.0`; advance that cap only with the corresponding cluster-version upgrade. Renovate groups the Velero CLI version with the digest-pinned verifier Dockerfile image, digest-pinned Terraform server image, and Terraform Helm chart. The independently promoted backup-verifier CronJob digest remains outside that group.

- `observability/` – observability contract and evidence validators.
- `release/` – release/notice generation utilities.
- `restores/` – restore, state-reset, and external-credential validation helpers; see `restores/README.md` for the script map.
- `smoke/` – shared smoke defaults and the run-owned Compose claim/capability helper.
- `seed/` – local and test data seeding helpers.
- `tests/` – contract tests for repo-owned tooling.
- `validation/` – repo policy and static validation scripts used by Gradle and CI.

The documentation lock is sourced from the unpinned direct roots in `config/docs/requirements.in` and generated into `config/docs/requirements.txt` with the repository Python version and `pip-compile --allow-unsafe --generate-hashes --index-url=https://pypi.org/simple --output-file=config/docs/requirements.txt config/docs/requirements.in`. The existing lock seeds compilation and preserves satisfying package pins unless you explicitly request an upgrade. When the installed pip-tools version mis-serializes its default index options into an unsupported header, set `CUSTOM_COMPILE_COMMAND` to that exact command so the generated header remains parseable. Keep the source roots and lock hashes in sync; do not edit lock hashes manually.

## Placement guidance

- Put a tool in `validation/` when its main job is enforcing a repo policy or static contract in CI/Gradle.
- Put a tool in `deploy/`, `hosted/shared/`, `hosted/preview/`, `hosted/dev-demo/`, `backups/`, or `restores/` when it owns a real operational lane.
- Keep root-level scripts only when they are canonical entrypoints contributors are expected to run directly.
- Avoid placing personal or AI-host-specific maintenance scripts at the root. If they remain in the repo at all, keep them under `maintenance/`.
