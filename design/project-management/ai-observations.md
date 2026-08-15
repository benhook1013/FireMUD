# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, code smells, and "this should be shaped better" patterns discovered during AI work.

Entry dates use Pacific/Auckland unless stated otherwise.

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

- `2026-08-11`: Luna capacity fallback requires a delayed same-tier retry
  - Context: bounded delegation can fail before work starts when the selected Luna model/tier has no available capacity.
  - Observation: immediately upgrading or silently falling back loses the diagnostic distinction between a transient capacity failure and a genuine fallback.
  - Expected pattern: when capacity blocks the selected Luna model/tier, wait 30–60 seconds and retry that same model/tier once; upgrade only if it remains unavailable. Report the failed model/tier, wait duration or retry count, and selected fallback immediately when fallback starts, and repeat the capacity failure, delayed retry, and fallback in the round handoff.

- `2026-08-13`: Reuse subagent threads narrowly and use sentinels only for single external waits
  - Context: autonomous orchestration needed both direct continuation of a completed bounded edit and a way to await one external transition without a native watcher.
  - Observation: reusing a completed subagent thread is appropriate only for direct same-domain continuation; unrelated work needs a fresh descriptive thread. When progress blocks on one external transition, no native watcher exists, and a concurrency slot would otherwise sit idle, a cheap read-only Luna sentinel can wait or poll while the main thread yields; a native wait remains preferred.
  - Expected pattern: keep continuation scope explicit, start a fresh thread when the domain changes, and use a read-only sentinel only as the fallback for one blocked external transition.

- `2026-08-14`: Stale Gradle formatting state can miss renamed Java files
  - Context: a Java file was renamed or newly introduced while Gradle Spotless task state remained cached.
  - Observation: cached Spotless task state can report success without formatting the renamed/new file.
  - Expected pattern: explicitly run the formatter/apply task, inspect the resulting diff, and only then trust formatting checks rather than relying on stale cache state.

- `2026-08-15`: CodeRabbit rate-limit replies require direct command-comment verification
  - Context: `check-coderabbit-review.py` reported a new hosted request as unfinished and not rate-limited even though CodeRabbit had already replied to that command with `Action not completed` and a 58-minute rate-limit window.
  - Observation: the current review checker does not recognize every top-level rate-limit reply shape, so an unfinished request can be mistaken for an active review and keep a watcher waiting indefinitely.
  - Expected pattern: when a hosted request remains unfinished unexpectedly, inspect CodeRabbit's direct reply to the latest explicit command before treating the review as active; improve the checker in a dedicated tooling slice rather than adding ad hoc parsing during unrelated review work.

- `2026-08-15`: ADR consolidation must preserve authority direction without creating secondary contract copies
  - Context: script-transition consolidation exposed secondary docs calling projections authoritative or presenting target contracts as live.
  - Observation: canonical owner architecture docs define the detailed normative contracts; accepted ADRs retain binding accepted-decision constraints plus rationale and human-review provenance. Repeated technical restatement in secondary docs creates drift.
  - Expected pattern: service docs, trackers, and journeys link to the owner and retain only concise local consequences/current proof; they must not contradict or demote accepted ADR decisions, while ADR links must not be treated as co-equal self-contained contract copies or as replacing owner authority.
