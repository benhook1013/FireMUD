# Validation And Runtime Proof

Use this guide when selecting or reporting formatting, checks, documentation validation, runtime proof, or smoke tests.

## Code And Documentation

- Code changes require `./gradlew spotlessApply` and `./gradlew check` before hand-off. If formatting-sensitive files changed, run the relevant `spotlessCheck` or `spotlessJavaCheck` for touched services.
- Service changes should prefer the CI-equivalent `./gradlew :<service>:check -PfullCheck`. Heavy local service checks should use `dev-tools/validation/run-locked-gradle.sh` to avoid concurrent result corruption.
- Markdown or design documentation changes require `./gradlew linkCheck lintMarkdown` and fixes for hygiene failures, including pre-existing failures in the changed scope.
- If CI exposes multiple failures in one area, stop relying on incremental remote feedback and run the fuller local proof. If broad migration/build changes or multiple branch lanes are in flight, rerun repository-wide validation before hand-off.

## Runtime And Smoke Changes

- Prefer canonical scripts under `dev-tools/` over ad hoc `docker compose` loops.
- For source-built bootstrap or restart behavior, use `dev-tools/verify-fresh-bootstrap.sh` or `dev-tools/verify-restart-state.sh`. For image-tag smoke, use `SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh`.
- These entrypoints run both transports in the read-only `LOGIN` -> `PLAY` -> `LOOK` baseline by default. Their mutation extension is intentionally rejected until independent transport identities/state are proven. Standalone transport mutation requires `SMOKE_MUTATION_EXTENSION=true`, `SMOKE_MUTATION_BOUNDARY=run-owned-compose`, and a proven claim/capability for the exact ID-to-project binding defined in [Testing: player-flow smoke and reset boundaries](../architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries); persistent/shared mutation remains unavailable pending the restricted-synthetic verifier.
- Fresh-bootstrap and image-tag teardown are limited to an explicitly claimed run-owned Compose project; restart requires its matching claim. These operations dispose a test deployment, not Coordination Redis reset authority. Use the canonical Redis reset/recovery sequence for Coordination Redis operations.
- Changes affecting runtime behavior, startup, authentication, wiring, migrations, or packaged artifacts require proof that rebuilds and boots fresh images rather than reusing containers or images.
- Current limitation: the canonical WebSocket/Telnet smoke scripts unconditionally execute the demo item/container/equipment mutation, and the fresh-bootstrap, restart, and image-tag entry points do not establish the [canonical smoke and reset isolation boundary](../architecture/system-architecture-testing.md#smoke-and-reset-isolation-boundary). Use these recommendations only in a verified run-owned disposable Compose or exact-head preview boundary until read-only baseline and opt-in isolated mutation are separated; do not run them against shared or stable state as mutating-smoke proof. Deleting or tearing down that run-owned fixture is test-environment disposal, not Coordination Redis reset authority; persistent/shared coordination remains on the current read-only/fenced fallback until the [owning reset workflow](../architecture/system-architecture-redis-reset-and-recovery.md#current-operator-fallback) exists.
- When CI indicates the branch is no longer locally mirrored, run `./gradlew check` and the appropriate canonical Docker smoke proof before pushing.
- Treat `77.42.29.156` (`firemud`, runner label `preview`) as preview infrastructure; check live host or runner state before heavier use.

## Diagnostics

- If a heavy Gradle run becomes quiet after test execution, use `dev-tools/validation/inspect-test-results.sh <service>` to inspect fresh parsed XML. It is diagnostic evidence, not proof that the task completed cleanly.
