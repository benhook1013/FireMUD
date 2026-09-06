# Validation And Runtime Proof

Use this guide when selecting or reporting formatting, checks, documentation validation, runtime proof, or smoke tests.

## Code And Documentation

- The lane orchestrator selects required proof by changed boundary: use focused affected formatting and tests during iteration, and an appropriate broader integration or merge gate when contract or runtime impact requires it. Independent lanes and helper handoffs alone do not broaden proof.
- Use canonical commands as relevant: `./gradlew spotlessApply` with the relevant `spotlessCheck` or `spotlessJavaCheck` for formatting-sensitive files; `./gradlew :<service>:check -PfullCheck` or `./gradlew check` for an appropriate broader gate; and `dev-tools/validation/run-locked-gradle.sh` for heavy local service checks.
- Markdown or design documentation changes require `./gradlew linkCheck lintMarkdown` and fixes for hygiene failures, including pre-existing failures in the changed scope.
- If CI exposes multiple related failures in one area, stop relying on incremental remote feedback and run fuller affected proof. After branch reconciliation changes the local head or validated scope, re-run the affected canonical proof and record any unavailable or partial local validation.
- Matching exact-head and exact-scope CI evidence can supply an unavailable local gate when the local limitation is reported. Publishing to obtain missing hosted proof is permitted, but completion or merge-ready status waits for the required proof. Preserve fresh-build, cross-service, runtime, and shared-environment mutation and reset safeguards owned by the linked contracts.

## Runtime And Smoke Changes

- Prefer canonical scripts under `dev-tools/` over ad hoc `docker compose` loops.
- Redis lease-script validation also follows the owner and registration contract in [Redis Ops Access](../architecture/system-architecture-redis-ops-access.md#coordination-redis-access-rules); this workflow selects proof without redefining that contract.
- For source-built bootstrap or restart behavior, use `dev-tools/verify-fresh-bootstrap.sh` or `dev-tools/verify-restart-state.sh`. For image-tag smoke, use `SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh`.
- These entrypoints run both transports in the read-only `LOGIN` -> `PLAY` -> `LOOK` baseline by default. Their mutation extension is intentionally rejected until independent transport identities/state are proven. Standalone transport mutation requires `SMOKE_MUTATION_EXTENSION=true`, `SMOKE_MUTATION_BOUNDARY=run-owned-compose`, and a proven claim/capability for the exact ID-to-project binding defined in [Testing: player-flow smoke and reset boundaries](../architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries); persistent/shared mutation remains unavailable pending the restricted-synthetic verifier.
- Fresh-bootstrap and image-tag teardown are limited to an explicitly claimed run-owned Compose project; restart requires its matching claim. These operations dispose a test deployment, not Coordination Redis reset authority. Use the canonical Redis reset/recovery sequence for Coordination Redis operations.
- Changes affecting runtime behavior, startup, authentication, wiring, migrations, or packaged artifacts require proof that rebuilds and boots fresh images rather than reusing containers or images.
- Treat `77.42.29.156` (`firemud`, runner label `preview`) as preview infrastructure; check live host or runner state before heavier use.

## Diagnostics

- If a heavy Gradle run becomes quiet after test execution, use `dev-tools/validation/inspect-test-results.sh <service>` to inspect fresh parsed XML. It is diagnostic evidence, not proof that the task completed cleanly.
