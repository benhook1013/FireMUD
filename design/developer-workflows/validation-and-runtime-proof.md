# Validation And Runtime Proof

Use this guide when selecting or reporting formatting, checks, documentation validation, runtime proof, or smoke tests.

## Code And Documentation

- Java 25 is the build/test toolchain and compile target, matching the production JRE major. Java 25 bytecode is not a Java 21 compatibility target, and compiler and focused-test proof do not replace fresh-image runtime qualification.
- The lane orchestrator selects required proof by changed boundary. Use focused affected formatting and tests for quick feedback on an implementation or fix, and an appropriate broader integration or merge gate when contract or runtime impact requires it. Independent lanes and helper handoffs alone do not broaden proof.
- Use canonical commands as relevant: `spotlessApply` with the relevant `spotlessCheck -PfullCheck` or `spotlessJavaCheck -PfullCheck` for formatting-sensitive files (these check tasks are disabled unless `fullCheck` is enabled), and `:<service>:check -PfullCheck` or `check` for a broader gate, through the [local Gradle wrapper](#local-gradle-execution) for heavy local checks.
- Do not launch separate Gradle processes concurrently when tasks may share generated-source dependencies; use one combined Gradle invocation or the wrapper's serialized path. Independent read-only checks such as script linting may still run in parallel.
- For focused shell-script checks, from the repository root run `shellcheck -x <scripts>` so sourced repository helpers are followed.
- After adding a Flyway migration, run the owning service's `generateJooq` and independently prove the migration against PostgreSQL with existing-data cases. Prefer declarative preflight constraints when they express the invariant; when necessary procedural SQL is rejected by jOOQ's DDL parser, use targeted jOOQ ignore markers around only that block. Ignore markers do not replace PostgreSQL migration proof.
- Markdown or design documentation changes require `bash dev-tools/validation/run-locked-gradle.sh linkCheck lintMarkdown` and fixes for hygiene failures, including pre-existing failures in the changed scope.
- If CI exposes multiple related failures in one area, stop relying on incremental remote feedback and run fuller affected proof. After branch reconciliation changes the local head or validated scope, re-run the affected canonical proof and record any unavailable or partial local validation.
- If a required local gate cannot run, report the unrun check and why. Matching exact-head and exact-scope GitHub CI can satisfy that gate. Publish a coherent checkpoint so CI can run when local infrastructure is unavailable, subject to the active Hosted review publication fence in [PR lifecycle](./pr-lifecycle.md#review-orchestration). Use alternative proof only when it materially shortens iteration; do not add a broad mandatory pre-publication proof phase. Local infrastructure unavailability does not authorize starting Docker or provisioning remote resources; follow the user's environment constraints and [remote change authorization](../../AGENTS.md#safety).
- Do not fabricate, weaken, or bypass required proof. Do not claim completion or merge readiness while required proof is missing. Only work explicitly excluded from the merged scope may be deferred; record its owner, missing evidence, and reconsideration trigger.

Optional local Renovate extraction and the daemon JDK refresh workflow are documented in the [maintenance tools README](../../dev-tools/maintenance/README.md).

## Local Gradle Execution

Run heavy local Gradle proof, documentation validation, and service boot-jar builds through `bash dev-tools/validation/run-locked-gradle.sh <tasks>`; direct `./gradlew` bypasses its cross-worktree serialization and cleanup. It needs Linux with `/proc` and pidfd support and fails explicitly elsewhere. Complete local runtime proofs run inside `--resource-session`, which runtime wrappers enter automatically. Local runs force `--no-daemon`; under `CI=true` the local resource guard and run budget do not apply. Internals are in the [validation tools README](../../dev-tools/validation/README.md).

| Setting | Default | Effect |
| --- | --- | --- |
| `FIREMUD_LOCK_GRADLE_WAIT` | Unset | Fail immediately on lock contention; set to `1` to wait within one total acquisition budget. |
| `FIREMUD_LOCK_GRADLE_WAIT_SECONDS` | `300` | Positive lock-acquisition budget in seconds when waiting is enabled, shared across all acquired locks. |
| `FIREMUD_LOCK_GRADLE_RUN_SECONDS` | `7200` | Positive local budget in seconds before cancellation begins; increase explicitly for a longer legitimate proof. Unlimited local runs are unavailable. |
| `FIREMUD_LOCK_GRADLE_CANCEL_SECONDS` | `30` | Positive local cancellation grace in seconds before escalation from TERM to KILL. |
| `FIREMUD_LOCK_GRADLE_RESOURCE_DIR` | `${XDG_CACHE_HOME:-$HOME/.cache}/firemud/gradle-validation` | Override only for isolated tooling tests or an explicitly separate resource boundary; changing this directory bypasses contention with the default guard. |

SIGKILL, WSL shutdown, unresponsive Docker, and cleanup exceeding the cancellation grace do not establish successful stack disposal, and an owned process that survives KILL keeps the locks held. State these limits in proof reports, and do not claim complete orphan cleanup beyond the process boundary actually tested.

## Runtime And Smoke Changes

- Prefer canonical scripts under `dev-tools/` over ad hoc `docker compose` loops.
- Redis lease-script validation also follows the owner and registration contract in [Redis Ops Access](../architecture/system-architecture-redis-ops-access.md#coordination-redis-access-rules); this workflow selects proof without redefining that contract.
- For source-built bootstrap or restart behavior, use `dev-tools/verify-fresh-bootstrap.sh` or `dev-tools/verify-restart-state.sh`. For image-tag smoke, use `SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh`.
- These entrypoints run the read-only `LOGIN` -> `PLAY` -> `LOOK` baseline on both transports. Mutation smoke is off; its preconditions are in [Testing: player-flow smoke and reset boundaries](../architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries).
- Verification wrappers clean up by default on completion, failure, and handled signals. Explicit `--keep-running` transfers final authenticated teardown responsibility to the caller retaining the original capability. Automated continuation stays inside one local resource session until final teardown; completed automated stacks must not remain parked. Successful probes with failed or ambiguous cleanup fail the overall invocation, and failed proof keeps its original exit status beside the cleanup disposition.
- Fresh-bootstrap and image-tag teardown are limited to an explicitly claimed run-owned Compose project; restart requires its matching claim. These operations dispose a test deployment, not Coordination Redis reset authority. Use the canonical Redis reset/recovery sequence for Coordination Redis operations.
- Changes affecting runtime behavior, startup, authentication, wiring, migrations, or packaged artifacts require proof that rebuilds and boots fresh images rather than reusing containers or images.
- Treat `77.42.29.156` (`firemud`, runner label `preview`) as preview infrastructure; check live host or runner state before heavier use.

## Diagnostics

- If a heavy Gradle run becomes quiet after test execution, use `dev-tools/validation/inspect-test-results.sh <service>` to inspect fresh parsed XML. It is diagnostic evidence, not proof that the task completed cleanly.
