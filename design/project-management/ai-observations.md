# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, code smells, and "this should be shaped better" patterns discovered during AI work.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work. Prefer logging reusable observations that suggest a better repo rule, CI guard, design refinement, or shared implementation pattern.

During ordinary autonomous work, this file is append-only: add dated reusable observations, and do not silently rewrite or delete prior entries. A human-requested [repository health check](../developer-workflows/repository-health-check.md) authorizes the worker to remove an entry only after evidence shows that it is addressed, obsolete, or disproved. Retain an entry only when a genuine blocker or deliberate postponement remains, recording the reason and reconsideration trigger. A health-check pass should reduce this inbox toward zero.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-06-29`: WSL Docker smoke proof must use the native Linux CLI
  - Context: `dev-tools/verify-fresh-bootstrap.sh` and other source-built Compose proofs were intermittently hanging or behaving strangely on a WSL workstation even though `docker version` and simple image commands still worked.
  - Observation: a WSL `docker` wrapper that delegates to Windows `docker.exe` can look healthy enough to hide the real fault for a long time, but bind mounts can silently misbehave through that path. That makes source-built Compose proofs look flaky or repo-broken when the real issue is the local Docker CLI wiring.
  - Expected pattern: WSL-local FireMUD Docker work should use a native Linux Docker CLI pointed at `unix:///var/run/docker.sock`, and tooling/docs should treat Windows `docker.exe` wrappers inside WSL as unsupported for canonical smoke proof.

- `2026-06-29`: Service images must normalize boot-jar readability at image-build time
  - Context: after the WSL Docker path was corrected, the fresh-bootstrap stack exposed repeated `Unable to access jarfile /app/app.jar` failures across non-root Java service containers.
  - Observation: host-built jars can legitimately land with restrictive local modes such as `0600`. If service images inherit that mode directly, non-root runtime users fail at startup and the resulting container error looks like a runtime wiring problem instead of an artifact-packaging contract bug.
  - Expected pattern: service Dockerfiles should set explicit jar ownership and readable mode during image build, and any image that performs extra rename or pruning work should still finish by restoring the intended non-root runtime user.

- `2026-07-07`: CodeRabbit actionable findings can live only in the top-level summary comment
  - Context: a PR reported zero unresolved review threads, but the latest CodeRabbit review summary still contained an `Outside diff range comments` finding that was not surfaced by thread-only review checks.
  - Observation: treating `reviewThreads` as the entire CodeRabbit truth is insufficient. Actionable `Outside diff range comments` and `Duplicate comments` can exist in the latest top-level CodeRabbit summary comment even when unresolved inline-thread counts are zero, which makes a PR look review-clean when it is not.
  - Expected pattern: PR review gating should inspect both unresolved review-thread counts and the latest top-level CodeRabbit actionable summary sections before calling a PR review-clean or retriggering review.

- `2026-07-25`: Extract independent validated files when a review cap blocks a coherent PR
  - Context: an identity and admission ADR PR crossed CodeRabbit's current file cap after a required runtime fix, while a small smoke and validation subset in the same branch was already independently coherent and validated.
  - Observation: removing the review fix, repeatedly reshaping the large branch, or accepting an unreviewable PR would all lose useful review signal. The independent subset could instead land directly on `develop`, after which rebasing removed those files from the larger PR's diff.
  - Expected pattern: when a PR crosses a review file cap, first look for a small, already-validated, independently mergeable subset. Extract that subset to a short `develop`-based PR, merge it, and rebase the original PR; do not force this strategy when the files are coupled or the extraction would weaken the original review boundary.

- `2026-07-28`: ADR review eligibility is not human acceptance
  - Context: delegated edits encountered accepted ADR metadata while reconciling the consequential-decision inventory.
  - Observation: an ADR reference establishes eligibility for recorded review, but does not prove a human disposition by itself.
  - Expected pattern: agents preserve pending status unless the checked review queue records the completed human disposition; delegated workers may mutate external review state only when the exact scope and authorization explicitly permit it; otherwise they must not.
