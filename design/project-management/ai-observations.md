# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, code smells, and "this should be shaped better" patterns discovered during AI work.

Entry dates use Pacific/Auckland unless stated otherwise.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work. Prefer logging reusable observations that suggest a better repo rule, CI guard, design refinement, or shared implementation pattern.

During ordinary autonomous work, this file is append-only: add dated reusable observations, and do not silently rewrite or delete prior entries. Workers should append reusable observations, helpers should send candidate entries to the owning worker, and ordinary bugs fixed in the same work should not become entries. Explicitly assigned Overseer stewardship may consume entries under the bounded process in [AI delegation and review](../developer-workflows/ai-delegation-and-review.md); a human-requested [repository health check](../developer-workflows/repository-health-check.md) separately authorizes the worker to remove an entry only after evidence shows that it is addressed, obsolete, or disproved. Retain an entry only when a genuine blocker or deliberate postponement remains, recording the reason and reconsideration trigger. A health-check pass should reduce this inbox toward zero.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-09-09`: Bind cert-manager Secret bytes to durable issuance evidence
  - Context: hosted identity activation still depends on proving that the served TLS identity matches the uniquely owned CertificateRequest for the Ready Certificate revision.
  - Observation: Ready status and Secret metadata alone do not prove that the observed certificate bytes belong to the recorded revision; `CertificateRequest.status.ca` is optional.
  - Expected pattern: compare the issued certificate bytes with the TLS Secret, carry those validated bytes forward, and re-read the Certificate snapshot before acceptance.
  - Current status: [PR #2853](https://github.com/benhook1013/FireMUD/pull/2853) owns source-side rotation and hosted identity proof, but its current scope does not establish live byte equality, the final Certificate re-read, or served-identity convergence.
  - Reconsideration trigger: an authorized activation or renewal observation proves those live identity boundaries.

- `2026-09-20`: Complete the privileged credential cutover
  - Context: preview deployment secrets were formerly available to branch-selectable workflow code on a persistent self-hosted runner.
  - Observation: the `trusted-hosted-cluster` Environment is restricted to `develop`, but legacy repository credentials and their old identity binding remain; environment configuration alone does not prove rotation or denial.
  - Expected pattern: verify scoped credential bootstrap, revoke and remove legacy credentials and bindings, and prove the old identity cannot read Secrets, create CertificateRequests, or change an Issuer.
  - Reconsideration trigger: before the next privileged deployment or any claim that the trust-boundary cutover is complete.

- `2026-10-06`: Verify ownership of the retired remote test fixture
  - Context: Account and Game Design have retired their external PostgreSQL test modes in favor of disposable Testcontainers fixtures.
  - Observation: the original remote provisioning authorization and cleanup of the old fixture or forwarding infrastructure were not retained; removing source hooks did not establish resource deletion.
  - Expected pattern: use only the already approved bounded fixture, and keep any provisioned resource tied to an identified owner and cleanup record.
  - Reconsideration trigger: verify cleanup when the old fixture is retired or before any future authorized external provisioning.

- `2026-10-13`: Reconcile complete native CI job inventories
  - Context: PR #3078’s first jobs page omitted the rest of its 40-job inventory, and a later Validation matrix view omitted failed Buf and Game Logic rows that appeared through the fully paginated Jobs API.
  - Observation: partial pages and transient run headers can hide failures or misstate full-run counts while other jobs are still active.
  - Expected pattern: fetch all pages, reconcile `total_count` and unique job IDs, and report failed jobs as soon as they appear; distinguish required-gate results from the terminal workflow result. The [CI workflow](../../.github/workflows/ci.yml) remains the source of the current job inventory.

- `2026-10-09`: Preserve uncertain wrong-checkout before-images
  - Context: a relative patch target wrote into the shared inspection checkout; 16 paths were already reported untracked and had no retained before-images.
  - Observation: matching replacement copies cannot establish whether those prior contents survived. The 16 paths remain untouched for independent recovery assessment.
  - Expected pattern: verify absolute checkout, branch, head, and status before editing; use absolute patch targets and preserve before-images for already-dirty assigned paths.
  - Reconsideration trigger: close the 16-path data-integrity uncertainty only after a trusted before-image or the owning human establishes prior content. Exact path evidence remains in the controller inbox and active job.

- `2026-10-13`: Reopen PostgreSQL endpoint diagnosis only on a new actual refusal
  - Context: Document-owned physical PostgreSQL proof had historical endpoint-refusal reports, while the current R17 readiness controls did not reproduce a test-time refusal or cgroup OOM; failed-probe records overlapped shutdown.
  - Observation: the R15/R16 refusal cause remains unproved. Testcontainers 2.0.5 waits for two PostgreSQL ready-log lines rather than using the generic host-JDBC waiter, but no repair is selected. The R17 fixture failures have separate diagnoses and remain owned by the [Game Authoring, Publishing, and Activation tracker](implementation-tracking/game-authoring-publishing-and-activation.md#coherent-owner-receiving-prerequisite).
  - Expected pattern: do not tune host settings or change readiness speculatively; retain endpoint, case, and teardown evidence if a new refusal occurs during a test.
  - Reconsideration trigger: a new physical test-time endpoint refusal, or a documented owner decision on a focused Testcontainers readiness repair.

- `2026-10-14`: Keep Game Authoring producer authority separate from receiver proof
  - Context: the current receiving implementation does not establish a genuine Game Authoring producer-to-receiver handoff.
  - Observation: current receiver evidence does not prove Game Authoring producer authority or packaged runtime behavior.
  - Expected pattern: record actual producer authority, receiver status, and packaged runtime proof in the [owning tracker](implementation-tracking/game-authoring-publishing-and-activation.md#coherent-owner-receiving-prerequisite).
  - Reconsideration trigger: revisit when a genuine Game Authoring producer handoff and the exact current receiver and packaged runtime boundary are proved.

- `2026-10-14`: Retain whole-session ownership through Compose teardown
  - Context: a source-built Compose proof passed player-flow baselines, but its launcher lost the run capability and exited before canonical teardown.
  - Observation: bounded recovery removed the project containers and network while retaining volumes, backups, and source; this is not full owned-session teardown or new-head packaged proof. Full local Compose remains paused until cleanup and whole-session ownership are corrected.
  - Expected pattern: retain the invocation capability and logs for the entire run, and include successful canonical teardown in the owned proof boundary. Follow the [Game Session implementation tracker](implementation-tracking/game-session-runtime-and-tick-coordination.md) and [player-flow smoke and reset contract](../architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries).
  - Reconsideration trigger: resume full local Compose only after the owner corrects the whole-session capability and cleanup boundary.

- `2026-10-15`: Scope the Social cross-service test limitation
  - Context: `SocialGroupsCrossServiceIntegrationTest` conditionally starts a cached Logging Admin image but does not call it; the test calls only the Social Groups `/ping` endpoint.
  - Observation: this test does not prove a Social-to-Logging interaction, despite its cross-service location. It is a coverage limitation, not evidence of a service defect or a new active project.
  - Expected pattern: keep the limitation with the [Social and Communication tracker](implementation-tracking/player-experience-commands-and-communication.md#communication-social-and-presence) and the [Social Groups runtime contract](../architecture/microservices/social-groups-service/runtime-and-data.md#implementation-status).
  - Reconsideration trigger: revisit when paused Social cross-service work resumes.

- `2026-10-15`: Qualify native Actions callback evidence at the hosted boundary
  - Context: completed Validation runs [38662647370](https://github.com/benhook1013/FireMUD/actions/runs/38662647370) and [38651777538](https://github.com/benhook1013/FireMUD/actions/runs/38651777538) returned `pull_requests: []` despite canonical PR, base-SHA, and head titles.
  - Observation: local fixtures cover title/SHA attribution and a separately pinned current-ref write fence, but do not prove native webhook delivery or partial-rerun response shapes.
  - Expected pattern: preserve that proof limit separately from installed consumer code, and retain the required producer/activation and callback qualification in the [platform operations tracker](implementation-tracking/platform-operations-and-delivery.md).
  - Reconsideration trigger: remove after producer activation and hosted webhook plus partial-rerun qualification establish the actual callback boundary.

- `2026-10-15`: Keep the shared local Gradle heap qualification explicit
  - Context: repeated 1.4 GiB heap thrashing led to a shared 3 GiB local daemon setting for serialized work.
  - Observation: a mostly up-to-date run passed, but cold-build speed and total memory use remain unmeasured; worker, test, and code-generation JVMs add separate memory, and the hosted runner remains explicitly configured at 2500m.
  - Expected pattern: treat the setting as a qualified local choice, not a repository-wide memory guarantee or a change to hosted sizing.
  - Reconsideration trigger: fresh OOM/GC thrashing or a concrete need to measure cold-build memory.
