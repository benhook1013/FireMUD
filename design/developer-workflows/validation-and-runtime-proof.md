# Validation And Runtime Proof

Use this guide when selecting or reporting formatting, checks, documentation validation, runtime proof, or smoke tests.

## Code And Documentation

- The lane orchestrator selects required proof by changed boundary: use focused affected formatting and tests during iteration, and an appropriate broader integration or merge gate when contract or runtime impact requires it. Independent lanes and helper handoffs alone do not broaden proof.
- Use canonical commands as relevant: `./gradlew spotlessApply` with the relevant `spotlessCheck -PfullCheck` or `spotlessJavaCheck -PfullCheck` for formatting-sensitive files; these check tasks are disabled unless `fullCheck` is enabled. Use `./gradlew :<service>:check -PfullCheck` or `./gradlew check` for an appropriate broader gate, and the [local Gradle wrapper](#local-gradle-execution) for heavy local checks.
- Do not launch separate Gradle processes concurrently when tasks may share generated-source dependencies; use one combined Gradle invocation or the canonical `dev-tools/validation/run-locked-gradle.sh` serialized path. Independent read-only checks such as script linting may still run in parallel.
- For focused shell-script checks, from the repository root run `shellcheck -x <scripts>` so sourced repository helpers are followed.
- After adding a Flyway migration, run the owning service's `generateJooq` and independently prove the migration against PostgreSQL with existing-data cases. Prefer declarative preflight constraints when they express the invariant; when necessary procedural SQL is rejected by jOOQ's DDL parser, use targeted jOOQ ignore markers around only that block. Ignore markers do not replace PostgreSQL migration proof.
- Markdown or design documentation changes require `bash dev-tools/validation/run-locked-gradle.sh linkCheck lintMarkdown` and fixes for hygiene failures, including pre-existing failures in the changed scope.
- If CI exposes multiple related failures in one area, stop relying on incremental remote feedback and run fuller affected proof. After branch reconciliation changes the local head or validated scope, re-run the affected canonical proof and record any unavailable or partial local validation.
- Matching exact-head and exact-scope CI evidence can supply an unavailable local gate when the local limitation is reported. Publishing to obtain missing hosted proof is permitted, but completion or merge-ready status waits for the required proof. Preserve fresh-build, cross-service, runtime, and shared-environment mutation and reset safeguards owned by the linked contracts.
- When required proof cannot be produced truthfully, do not fabricate evidence or weaken or bypass its check. Missing required proof blocks completion and merge readiness for work in the merged scope. Only work explicitly excluded from the merged scope may be deferred; record its owner, missing evidence, and reconsideration trigger.

## Local Gradle Execution

Run heavy local Gradle proof, documentation validation, and service boot-jar builds through `bash dev-tools/validation/run-locked-gradle.sh <tasks>`. The source-built Compose boot-jar builder uses this entrypoint. Local execution requires Linux with `/proc`; unsupported environments fail explicitly. Direct `./gradlew` invocations bypass these protections, so do not use them as a concurrent heavy-build path.

The wrapper acquires a user-wide exclusive resource lock across worktrees before its existing worktree output locks. Service-scoped tasks retain a service lock and shared repository guard; unscoped or mixed tasks retain the exclusive repository guard. Output-lock metadata remains under `.gradle/firemud-validation-locks/`. The resource lock defaults to `${XDG_CACHE_HOME:-$HOME/.cache}/firemud/gradle-validation`, using the home cache when `XDG_CACHE_HOME` is unset or empty, independently of `GRADLE_USER_HOME`; separate Gradle caches do not create extra local build slots. Serialization limits concurrent admitted builds, not the memory usage of one build.

Local runs force `--no-daemon` and reject explicit `--daemon`. Gradle may still start a single-use daemon to satisfy JVM settings; reusable dependency and task caches remain available, with a JVM startup cost on each run. The repository's ten-minute reusable-daemon idle expiry applies to direct Gradle launches. Under `CI=true`, the wrapper bypasses the local resource guard, retains the existing daemon policy and output locks, and keeps bounded lock acquisition. CI runs have no finite local run budget and do not parse `FIREMUD_LOCK_GRADLE_RUN_SECONDS` or `FIREMUD_LOCK_GRADLE_CANCEL_SECONDS`; this local policy does not serialize hosted jobs.

| Setting | Default | Effect |
| --- | --- | --- |
| `FIREMUD_LOCK_GRADLE_WAIT` | Unset | Fail immediately on lock contention; set to `1` to wait within one total acquisition budget. |
| `FIREMUD_LOCK_GRADLE_WAIT_SECONDS` | `300` | Positive lock-acquisition budget in seconds when waiting is enabled, shared across all acquired locks. |
| `FIREMUD_LOCK_GRADLE_RUN_SECONDS` | `7200` | Positive local budget in seconds before cancellation begins; increase explicitly for a longer legitimate proof. Unlimited local runs are unavailable. |
| `FIREMUD_LOCK_GRADLE_CANCEL_SECONDS` | `30` | Positive local cancellation grace in seconds before escalation from TERM to KILL. |
| `FIREMUD_LOCK_GRADLE_RESOURCE_DIR` | User cache path above | Override only for isolated tooling tests or an explicitly separate resource boundary; changing this directory bypasses contention with the default guard. |

A supervisor retains the locks during execution and cancellation. A random per-run environment marker admits owned processes, whose exact PID/start identities remain remembered even if an exec changes their environment. The wrapper itself is tracked by exact PID/start identity. Wrapper termination or disappearance triggers cancellation of the owned client, daemon, and workers; the run budget bounds the start of cancellation when the app disappears but the wrapper remains alive. App death is not immediate cancellation in that case. Closed or failing diagnostic output cannot bypass cleanup. Cancellation does not stop unrelated Gradle daemons or builds.

If an owned process survives KILL, for example because of uninterruptible kernel I/O, the supervisor keeps the locks while contenders fail immediately or exhaust their acquisition budget. The run budget bounds cancellation start, not guaranteed kernel process termination. Native or escaped processes outside the ownership evidence are not an established cleanup guarantee; focused proof must state the actual process boundary tested before claiming complete orphan cleanup.

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
