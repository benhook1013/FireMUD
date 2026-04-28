# Architecture Review Prompt: Test Infrastructure, Harness, and Proof Convergence Review

Best used for:

- auditing FireMUD test architecture, shared harness usage, proof quality, and missed reuse opportunities across the current repo state

Read the following sources first. Follow references only when a listed doc clearly delegates a canonical contract or workflow needed to judge a finding. Then inspect the concrete repo code, tests, scripts, and current branch state directly.

- `AGENTS.md`
- `design/project-management/testing-focus-areas.md`
- `design/project-management/service-status-game-session-service.md`
- `design/project-management/service-status-tcp-proxy-service.md`
- `design/project-management/service-status-spring-cloud-gateway.md`
- `design/project-management/vertical-slices/02.18.16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice.md`
- `design/project-management/vertical-slices/02.18.17-task-list-gameplay-transport-test-harness-convergence-vertical-slice.md`
- `design/project-management/vertical-slices/02.18.18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice.md`
- `design/project-management/slice-support/look-cross-service-tests.md`
- `design/project-management/slice-support/look-smoke-tests.md`
- `design/developer-workflows/login-session-smoke-tests.md`

Review the current FireMUD branch for test infrastructure coherence, harness adoption, duplication, and proof quality.

Context:

- Repo: `/home/ben/src/FireMUD-wsl-copy`
- This is not a narrow bug-fix task.
- This is not primarily about product behavior correctness.
- This is about test architecture, test ergonomics, test consistency, and proof quality.
- Prefer FireMUD-specific test infrastructure over generic abstraction for its own sake.
- Focus especially on chained gameplay flow tests, cross-service tests, transport-edge tests, and smoke-adjacent proof patterns.
- Recent known context:
  - slice `02.18.17 Gameplay Transport Test Harness Convergence` was completed at the current boundary
  - shared websocket gameplay driver and readiness helpers were introduced and relevant websocket suites were migrated
  - shared telnet gameplay driver and readiness helpers were introduced and relevant telnet suites were migrated
  - shared backend assertion helpers were added for repeated gameplay entity and social request checks
- The goal is to independently review whether there is still duplication, drift, underuse, or obvious missing harness infrastructure after that convergence pass.

What to look for:

- where test flow, setup, teardown, or assertion logic is still duplicated
- where tests are not using shared harness or infrastructure patterns that already exist
- mixed-style areas where some suites use the shared path and others still hand-roll the same logic
- repeated login, play, ready, reconnect, takeover, or multi-actor orchestration
- repeated transport transcript parsing or prompt-tolerant transcript assertions
- repeated waits, polling loops, retry loops, or flaky timing patterns
- repeated backend request-shape assertions that should be shared
- duplicate or overlapping stub servers, scenario builders, seeding helpers, or transcript helpers
- missing opportunities for more shared gameplay drivers, scenario fixtures, backend assertion helpers, fixture seeding helpers, or smoke-adjacent proof helpers
- places where tests are operating at the wrong layer because shared support is missing or underused
- whether the current test infrastructure encourages readable tests, stable tests, realistic proof, and correct layer choice

What I want in the output:

1. Structure the audit as:
   - `1. Audit boundary`
   - `2. Current shared infrastructure inventory`
   - `3. Current usage and adoption`
   - `4. Duplication and drift`
   - `5. Missing infrastructure opportunities`
   - `6. Test quality and architecture assessment`
   - `7. Recommended next work`
   - `8. Final judgment`
2. In `Audit boundary`, explicitly call out:
   - unit tests
   - integration tests
   - crossService tests
   - smoke scripts and smoke-adjacent helpers
   - websocket gameplay tests
   - telnet gameplay tests
   - gateway bridge tests
3. In `Current shared infrastructure inventory`, identify the shared test drivers, fixtures, helpers, scenario builders, stub servers, and assertion helpers that currently exist, and explain what each is intended to standardize.
4. In `Current usage and adoption`, identify where the shared infrastructure is being used well, where tests still hand-roll the same logic, and where mixed-style areas remain.
5. In `Duplication and drift`, explicitly call out:
   - repeated login, play, and ready flows
   - repeated transport setup
   - repeated multi-actor setup
   - repeated waits and retry loops
   - repeated backend assertion logic
   - repeated stubs or transcript parsing patterns
   - repeated reconnect or takeover setup patterns
   - duplicate helper classes or overlapping abstractions
6. In `Missing infrastructure opportunities`, be concrete about what should be introduced and what should not be abstracted. Examples may include:
   - multi-actor scenario fixtures
   - reconnect or takeover scenario fixtures
   - gameplay readiness gates
   - canonical assertion DSLs
   - backend request-shape assertions
   - fixture seeding helpers
   - transport transcript helpers
7. In `Recommended next work`, separate:
   - high-value immediate follow-up
   - medium-priority cleanup
   - lower-priority polish
   - whether another dedicated slice or task doc is warranted
8. In `Final judgment`, explicitly classify the current state as one of:
   - `coherent and mostly converged`
   - `improved but still materially inconsistent`
   - `still too ad hoc`
   and explain why.

Constraints:

- Inspect the repo directly and ground the audit in actual files and current code, not assumptions.
- Do not just list files; reconcile patterns across them.
- Prefer identifying the canonical direction rather than preserving every current local style.
- If a current helper or abstraction looks wrong, say so plainly.
- If a gap is small and obvious, say so plainly.
- Optimize for actionable conclusions, not a passive survey.
- Default to static review unless a small targeted run materially helps confirm a concern.
- Do not make code changes unless explicitly asked.
- Record reusable lessons in `design/project-management/ai-observations.md` if you discover them.

Helpful framing:

- Treat this as a convergence audit, not a correctness pass over gameplay behavior.
- Prefer FireMUD-specific test support that makes gameplay, transport, and cross-service proof easier to read and harder to flake.
- Be skeptical of tests that currently pass only because of duplicated orchestration, brittle transcript timing, or low-level socket choreography that should already be hidden by shared support.
- Prefer one canonical shared proof path per gameplay and transport pattern over many local mini-harnesses.
