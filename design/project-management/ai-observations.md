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

- `2026-08-18`: A failed CodeRabbit command can leave its external check pending
  - Context: a hosted full-review command replied `Action failed` within a minute, but the CodeRabbit status check remained pending and `check-coderabbit-review.py` continued to report the request as unfinished for more than an hour.
  - Observation: a pending external check and an unfinished helper result do not prove that hosted review is still active when the direct command reply is already terminal.
  - Expected pattern: when hosted review remains pending substantially longer than normal, inspect the direct CodeRabbit reply to the latest command. Treat an explicit failed reply as terminal for review-safety, preserve any prepared local fixes, and request a fresh review only after publishing the next validated head or after the applicable cooldown.

- `2026-08-18`: Structural agent-thread exhaustion is not model capacity
  - Context: autonomous delegation encountered `agent thread limit reached`.
  - Observation: the harness slot limit requires a different recovery path from a model-capacity failure.
  - Expected pattern: route thread exhaustion through the [AI Delegation And Review](../developer-workflows/ai-delegation-and-review.md) status/close-out path; reserve delayed same-tier retries for actual model-capacity failures.

- `2026-08-21`: Reconnect context and connection coordination have distinct ownership
  - Context: consolidating the durable semantic reconnect-context contract exposed a recurring temptation to treat all reconnect-related Redis data as one authority family.
  - Observation: Cache/Rate-Limit Redis holds only a derived semantic-context cache; Coordination Redis owns live connection liveness, gameplay bindings, and rebind coordination; Game Session persistence owns durable `PENDING` rebind attempts and semantic reconnect context.
  - Expected pattern: keep these ownership families separate and rebuild semantic context from Game Session persistence after cache loss.

- `2026-08-22`: Selective ADR import must preserve reviewed provenance and consolidate authority locally
  - Context: Packet 5 imported one reviewed connection/output family while later packet decisions remained intentionally pending.
  - Observation: treating an ADR packet as a bulk copy either loses exact checked decision provenance or spreads normative detail into secondary documents before the family is ready.
  - Expected pattern: import only the selected reviewed family, preserve its exact checked disposition and source keys, consolidate normative detail at the canonical owner with links and local consequences elsewhere, and defer broad residual drift to the planned whole-corpus authority review.

- `2026-08-23`: Bind safety, entitlement, and actor/session identity to explicit owners
  - Context: Packet 5 alignment in the [system overview](../architecture/system-architecture-overview.md), [Entity Management boundary](../architecture/microservices/entity-management-service/README.md), and [Game Session boundary](../architecture/microservices/game-session-service/README.md).
  - Observation: fixed safety categories keep independent owner lifecycles; tenant runtime entitlements require explicit tenant binding for account-scoped grants; Entity owns persisted actor and fork-local character identity while Game Session owns attachment and controller fencing.
  - Expected pattern: carry explicit scope and owner/fence evidence across each seam, treating source character IDs as provenance rather than authority.

- `2026-08-24`: Expected-binding declarations are intent, not deployment proof
  - Context: Packet 6 environment-bound preflight and expected-binding work.
  - Observation: binding declarations classify shareability and enabled integrations, but production state or trust material cannot be made shareable by a generic flag, and declaration alone does not prove a concrete deployment event.
  - Expected pattern: require applicable integrations only when enabled, and retain the manifest digest, event identity, and observed target binding in event-scoped evidence.

- `2026-08-22`: Preserve canonical-contract provenance for superseded keys without unique ADRs
  - Context: a reviewed superseded key had no unique ADR during alignment import.
  - Observation: use the canonical-contract `; no ADR required` provenance path; exact ADR links are direct review metadata and must not be repurposed as replacement-only links.
  - Expected pattern: retain that provenance distinction in future alignment imports.

- `2026-08-24`: Progressive rollout and rollback need durable transition identity
  - Context: Packet 6 aligned progressive rollout and compatibility-bounded rollback contracts while the current implementation remained partial.
  - Observation: a safe rollout is a durable operation with server-enforced expected-state preconditions and bounded promotion steps; rollback authority remains limited by the declared compatibility boundary rather than by an operator's ability to redeploy an older image.
  - Expected pattern: keep target transition semantics distinct from current implementation status, and require one durable operation identity plus explicit preconditions at every rollout or rollback step.

- `2026-08-25`: Terminal substantive review completion can fail the merge gate
  - Context: `check-coderabbit-review.py` exited 1 after a substantive review completed with findings remaining.
  - Observation: an ordinary exit 1 can represent a completed substantive review whose merge gate fails, not only an active pending review; hosted watchers must terminate for adjudication when `review_finished_after_latest_request=true` or equivalent substantive completion is present even if `ok=false`.
  - Expected pattern: when elapsed time appears stuck, inspect the direct CodeRabbit command/review record and distinguish terminal findings from an active hosted review before continuing to watch.

- `2026-08-26`: Keep parcel provenance, dependency coordinates, and finite metrics exact
  - Context: Packet import and dependency review exposed three recurring boundary checks.
  - Observation: ADR parcel splitting must keep each decision, ADR, and provenance with its canonical owner and truthful intermediate counts; Testcontainers 2 version bumps also require prefixed module coordinates; exact finite metric schemas need enumerated vocabularies and a required service label with bounded values.
  - Expected pattern: reconcile owner/count/provenance tuples before publishing a parcel, inspect every catalog alias and consumer coordinate during a Testcontainers bump, and enumerate finite metric values while enforcing the bounded service label.

- `2026-08-27`: Preserve scripting stage, fence, and metric ownership
  - Context: Packet 6 assurance-tail review exposed ambiguity between scheduler candidate skips and handler-scoped tenant-budget denials in an operations cookbook example.
  - Observation: Query examples must preserve the observability boundary: pre-handler or catch-up candidate skips use the bounded skip metric, while a denial after executor claim uses handler-scoped trigger and budget-denial metrics plus its audit row. The same discipline keeps dry-run breaker accounting isolated, breaker aggregates exact in scope with resets auditable, advisory notifications subordinate to lifecycle and admission fences, resume windows isolated by mode with terminal catch-up skips visible, and metric-label definitions coupled to alert validation; broad or unbounded label placeholders can hide these distinctions.
  - Expected pattern: when documenting scripting metrics, state the lifecycle stage and increment unit beside each query, enumerate every finite label value, bind breaker aggregates and reset evidence to exact scope, keep notifications advisory, separate resume modes and terminal skips, and validate alerts against the canonical finite-label vocabulary.

- `2026-08-29`: Direct CodeRabbit command replies outrank conflicting derived rate-limit state
  - Context: a hosted full-review request remained unfinished with no findings while the checker reported `latest_review_request_rate_limited=false`; the direct CodeRabbit command reply was already terminal `Action not completed — Review rate limited`.
  - Observation: a derived checker flag can miss terminal rate-limit evidence and leave a watcher treating a completed request as active.
  - Expected pattern: when a hosted request appears stuck, inspect the direct reply. Treat an explicit rate-limit response as terminal, stop its watcher, and request again only when authorized on the next meaningful head or after the stated availability window.

- `2026-08-29`: Caller-selected names are selectors, not destructive ownership proof
  - Context: tightening local Compose smoke project binding and lifecycle checks.
  - Observation: a project name supplied by the caller does not prove ownership of destructive access.
  - Expected pattern: require a claim/capability, verify project resources and the canonical endpoint, and fail closed on stale, colliding, or mismatched state.

- `2026-09-06`: Preview priority requires lifecycle serialization, not only capacity ordering
  - Context: adding a small priority override to the automatic pull-request preview pool.
  - Observation: selecting an ordinary victim under a capacity check is unsafe if deployment or hosted proof can still be using that namespace, and an unaware reconciler can immediately recreate a displaced environment.
  - Expected pattern: follow the [deployment-environment preview contract](../architecture/infrastructure/deployment-environments.md): serialize allocation, deployment, proof, reclaim, and cleanup without cancelling the active lifecycle; re-read target and victim priority at the destructive boundary; and make reconciliation capacity-aware so ordinary requests wait instead of forming a reclaim loop.
