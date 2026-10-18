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

- `2026-10-15`: Diagnose effective migration guards before proposing repairs
  - Context: A World APPLIED diagnosis read V1012 without following V1014, which had already replaced the six-populated-family predicate with complete explicit counts and owner-proved zeros.
  - Observation: A historical definition alone is not live behavior.
  - Expected pattern: Before declaring a migration defect, locate every later replacement of the function or trigger and assess the effective guard at the pinned receiving head, including related mapping/receipt constraints. Preserve immutable migration history and propose a new forward repair only for a demonstrated current gap.

- `2026-10-15`: Check subclass lifecycle locks when relying on a shared client
  - Context: The World terminal completion client called its shared watcher shutdown from a synchronized override, undoing the base class’s deliberate monitor release while draining callbacks.
  - Observation: Integration inspection found the same mechanism already corrected in the canonical launch client.
  - Expected pattern: Keep only the local closed/initialized update under the client monitor and drain outside it; qualify the actual override with a queued-callback shutdown test. The queued-callback shutdown case passes in the fresh 546-case Common gate, including monitor release, exact peer binding, closed-client refusal and one shutdown per constructed channel. This source finding does not establish that an earlier runtime failed for that reason.

- `2026-10-15`: Verify executed physical cases after a successful categorized gate
  - Context: Moving the unchanged GDS producer proof into its categorized cross-service suite produced a successful Gradle result with all 20 selected cases skipped after Ryuk host-port refusals. A mixed filter also omitted the held-order suite because its package lacked the integration prefix. A separate combined four-owner compilation gate stopped on daemon garbage-collector thrashing before tests.
  - Observation: A successful categorized task can contain zero executed physical cases, and a plain retry can reuse the all-skipped task as up-to-date. A successful mixed selector does not prove every required suite ran. The runner-networking cause remains unknown.
  - Expected pattern: Verify fresh executed XML for every required selector before claiming counts; explicitly rerun an up-to-date all-skipped test task when retrying recovered infrastructure. Preserve cleanup guards and the original attribution of skipped, unavailable and failed runs. If a combined gate exhausts memory before tests, qualify owners sequentially under the standard resource policy.
  - Reconsideration trigger: Revisit when the canonical gate rejects all-skipped required physical selectors and detects missing required suites.

- `2026-10-15`: Scope the Social cross-service test limitation
  - Context: `SocialGroupsCrossServiceIntegrationTest` conditionally starts a cached Logging Admin image but does not call it; the test calls only the Social Groups `/ping` endpoint.
  - Observation: this test does not prove a Social-to-Logging interaction, despite its cross-service location. It is a coverage limitation, not evidence of a service defect or a new active project.
  - Expected pattern: keep the limitation with the [Social and Communication tracker](implementation-tracking/player-experience-commands-and-communication.md#communication-social-and-presence) and the [Social Groups runtime contract](../architecture/microservices/social-groups-service/runtime-and-data.md#implementation-status).
  - Reconsideration trigger: revisit when paused Social cross-service work resumes.

- `2026-10-15`: Qualify native Actions callback evidence at the hosted boundary
  - Context: completed Validation runs [38662647370](https://github.com/benhook1013/FireMUD/actions/runs/38662647370) and [38651777538](https://github.com/benhook1013/FireMUD/actions/runs/38651777538) covered 20 modules and zero modules respectively, and returned `pull_requests: []` despite canonical PR, base-SHA, and head titles.
  - Observation: association-required fixtures would have passed locally while disabling reporting. The optional summary distinguishes title/SHA attribution from a separately pinned current-ref write fence; local fixtures do not prove historical-ref or required-gate provenance, native webhook delivery, or partial-rerun response shapes.
  - Expected pattern: preserve that proof limit separately from installed consumer code, and retain the required producer/activation and callback qualification in the [platform operations tracker](implementation-tracking/platform-operations-and-delivery.md).
  - Reconsideration trigger: remove after producer activation and hosted webhook plus partial-rerun qualification establish the actual callback boundary.

- `2026-10-15`: Make failed SpotBugs tasks expose actionable diagnostics
  - Context: the Document preparation gate returned exit code 1 without a finding or report; a temporary init script enabled XML output and exposed one unused fixture local.
  - Observation: Gradle also applies initialization scripts to `buildSrc`, so an unscoped script can affect projects outside the intended service.
  - Expected pattern: scope temporary report configuration through `findProject`, then use the ordinary corrected service gate; consider enabling useful reports in canonical task configuration when the opportunity is justified. No canonical build configuration changed in this lane.
  - Reconsideration trigger: when canonical SpotBugs tasks expose actionable XML or HTML reports on failure, verify the original diagnostic gap is closed and revisit the temporary-report guidance.

- `2026-10-15`: Keep the shared local Gradle heap qualification explicit
  - Context: repeated 1.4 GiB heap thrashing led to a shared 3 GiB local daemon setting for serialized work.
  - Observation: a mostly up-to-date run passed, but cold-build speed and total memory use remain unmeasured; worker, test, and code-generation JVMs add separate memory, and the hosted runner remains explicitly configured at 2500m.
  - Expected pattern: treat the setting as a qualified local choice, not a repository-wide memory guarantee or a change to hosted sizing.
  - Reconsideration trigger: fresh OOM/GC thrashing or a concrete need to measure cold-build memory.

- `2026-10-15`: Declare foreign migration inputs for classes-only compositions
  - Context: A composed proof can read another owner’s Flyway migrations from disk while its classes-only test artifacts omit application and Flyway resources.
  - Observation: Without declaring those migration directories as task inputs, a SQL-only change can leave the composition up-to-date.
  - Expected pattern: Declare every foreign migration directory read by a composed test as a task input so SQL changes invalidate that proof.
  - Reconsideration trigger: The composition no longer reads foreign migration files from disk, or its execution model tracks those files through another verified dependency.

- `2026-10-15`: Verify the complete absolute patch destination after the first write
  - Context: Gameplay’s World digest-guard helper verified its checkout but mistyped the absolute migration path, creating a file in a similarly named sibling directory outside Git. This is distinct from the earlier relative-path incident: an absolute path alone does not identify the intended checkout.
  - Observation: Verifying the checkout did not prevent a typo in the absolute patch target. The helper reported the mistake; Main compared the misplaced and intended files byte-for-byte, removed only the confirmed new duplicate, and preserved the sibling directories. No misplaced file was published.
  - Expected pattern: Compare every patch target with the pinned worktree path, then inspect the first written file at that absolute destination before continuing. This destination check remains manual.
  - Reconsideration trigger: enforce destination verification at the native patch boundary, or repeat measurement disproves the risk; completion of migration review alone does not resolve it.

- `2026-10-15`: Allocate forward migrations against the actual resolved Flyway history, including shared Saga resources and later service-owned migrations. The selected-owner intake plan's older Account V161 and Automation V5 sketch would sort below already installed combined history. The receiving Account parent already reaches V1005, while Saga reaches V1002. Preserve existing SQL and prove a retained upgrade from the actual combined parent instead of enabling out-of-order execution or inferring a frontier from the service's older application lineage.

- `2026-10-16`: The canonical local resource lock already serializes eligible heavy runs across worktrees. Ordinary workers can submit through supported bounded admission and retry a busy result without peer permission or an idle reservation between scheduled wake-ups. A Document source gate automatically acquired the lock before its delayed peer completion message arrived; busy admission remains unrun proof.

- `2026-10-16`: A multiline conflict-marker resolver truncated three trailing file portions during an unpublished selected-owner carry. Native `git merge-file` reconstruction from the exact parents recovered the complete files. Bound marker lines explicitly, verify conflict counts and file tails, and inspect the final net diff against both original parents before committing; a clean whitespace check alone cannot detect lost content.

- `2026-10-15`: Preserve receiver status when relocating inherited tracker proof
  - Context: the local #3224 parent reconciliation relocated inherited LOGIN proof into a dedicated section. Selecting the complete incoming tracker plus one receiver proof paragraph also displaced the receiver's first-party denial and historical-proof qualifications. The candidate had not been published.
  - Observation: verifying nonoverlapping blobs and paragraphs unique against the merge base missed receiver status that also appeared in the base. Main later compared the complete receiver document, restored every original receiver paragraph byte-for-byte, and retained the incoming concise status and relocated proof. No production source changed.
  - Expected pattern: inspect the complete overlap against the receiver's current contract and status, including text shared with the merge base. Proof relocation alone does not authorize replacing unrelated current-status sections with an earlier parent's view.
  - Reconsideration trigger: a native reconciliation check proves preservation across moved paragraphs and status replacements; ordinary clean merges and nonoverlap comparisons do not establish that property.

- `2026-10-16`: Check both public mapping directions when an internal read requires an identity distinct from its original operation. The launch-association receiver retained that guard while a refactor removed it from the request encoder; the receiver's refusal did not constrain local encoding. A producer refusal assertion reproduced the omission at `653c42cea1`, and restoring the existing encoder guard passed the full 917-case Common gate at `cefbf09ebb`. Keep the transport identity rule at both boundaries and distinguish local codec proof from controlled socket responses or genuine owner authority.

- `2026-10-16`: Report source validation separately from packaged smoke
  - Context: PR #3224 had a successful Validation result and a separate runtime-image Smoke failure in run [38844094461](https://github.com/benhook1013/FireMUD/actions/runs/38844094461); the retained local job record showed Gateway and TCP Proxy public readiness intentionally out of service after the bounded Compose wait.
  - Observation: successful source validation does not replace packaged-image runtime proof, and an expected held-readiness result does not justify weakening public readiness or extending timeouts.
  - Expected pattern: report Validation and Smoke outcomes independently, select the held-admission qualification explicitly, and keep its positive backend claim separate from successful public admission. Follow the [player-flow smoke boundary](../architecture/system-architecture-testing.md#player-flow-smoke-and-reset-boundaries).
  - Reconsideration trigger: reconsider the qualification after the held-admission runtime path has fresh packaged-image proof and the separate successful-admission obligation is complete.

- `2026-10-16`: Bind publication destinations to the live PR branch
  - Context: while receiving #3248, a manually typed plausible branch name created an unintended remote branch instead of updating the PR's actual `headRefName`. The correct PR branch remained unchanged until the mistake was detected.
  - Observation: a successful push does not establish that the intended PR received the change. The worker used the live status response to publish to the correct branch, then deleted only its just-created accidental branch under an exact-commit lease. No PR or queue topology changed.
  - Expected pattern: derive the push destination directly from the verified live PR identity, verify the destination and expected prior head before writing, then read back the PR head. A guarded controller publication operation could remove this manual mapping step.
  - Reconsideration trigger: a canonical publication primitive enforces this identity binding; until then use the live `headRefName` without retyping it.

- `2026-10-16`: Preserve terminal capture when a runtime command session disappears
  - Context: a fresh #3224 held-admission attempt on `887586fb50769855e2bb74fa98192a92868933f3` started at 05:03:37 UTC, built images and started Compose, then its command session disappeared before qualification. The preserved console reports `Gradle wrapper exited; cancelling its owned run.` and authenticated teardown.
  - Observation: the capture retained its initial identity and full console but no terminal exit code or end timestamp. The reason the owning process ended is unknown; the elapsed time alone does not establish a command timeout or the resource-admission budget as its cause. Targeted checks found no run-owned processes, containers, networks, volumes or ownership marker afterward. No Gateway/Telnet or backend positive proof completed.
  - Expected pattern: keep the foreground command and its native session supervised through terminal completion, preserve the raw capture and report missing terminal evidence explicitly. An interrupted attempt and successful cleanup cannot count as runtime qualification. Supply command arguments as an explicit array when delegating; a sentence-ending period was separately mistaken for an argument and caused an avoidable pre-execution failure.
  - Recovery: the next foreground canonical attempt on `3c71c8c24379af623ca66550a4f4acaadd0ffd81` completed at 05:27:42 UTC with exit zero, full terminal capture, held-edge and backend proof, authenticated teardown and independently absent project resources. This does not establish why the earlier owner ended.
  - Reconsideration trigger: a subsequent canonical attempt completes with terminal capture, or bounded process evidence identifies why the previous owner ended.

- `2026-10-16`: Qualify tenant mutation predicates
  - Context: Before relying on filtered readback or later owner guards, account for the template repository's global-ID update, which could rewrite a retained row's tenant before returning it under the supplied tenant; a tenant-filtered readback did not protect the original row.
  - Observation: The native counterexample changed the complete row and `xmin`, while the restored exact tenant predicate preserves both owner rows and their versions.
  - Expected pattern: Test controlled pre-phase rows as well as later guarded owners. Keep the create-only ID refusal before mapper/repository access, and separate this owner-local SQL proof from authenticated creator or hosted-content readiness.

- `2026-10-16`: Derive focused Gradle selectors from declared Java packages
  - Context: a Gameplay capture proof supplied `integration.net...` as the selector, and Gradle executed no tests before failing discovery.
  - Observation: `integration` came from the categorized source root, while the class declared `net...`; a categorized source root is not necessarily part of the Java package, and a zero-test discovery failure establishes no test proof.
  - Expected pattern: read the source package and class declaration before constructing `--tests`, and verify fresh testcase counts before crediting proof. A categorized source root is not necessarily part of the Java package. Archive completed full-suite reports before a filtered rerun replaces the task output.
  - Recovery: the declared-package selector then passed all sixteen owning native cases; the zero-test attempt retains no proof credit.
  - Recurrence (`2026-10-17`, #3231): a mixed-selector review-fix gate requested two World classes, but one selector incorrectly included the categorized `unit` source-root segment in its Java package. The matching selector executed nine terminal-read cases, so the task succeeded while the launch-binding comparator class remained unrun. A successful task does not establish coverage of every supplied pattern. Verify every expected class in fresh XML, preserve completed reports before a corrected filtered run replaces task output, and credit the missed class only after its own execution.
  - Reconsideration trigger: when the canonical Gradle selector tooling or CI guard can verify every explicitly requested test class rather than only aggregate task success, qualify that guard against a mixed valid/invalid selector and revisit this manual proof step.

- `2026-10-17`: Explain inbox read failures during SQLite writes
  - Context: Gameplay used the canonical `inbox read` command for an Overseer message at 11:01 UTC on 2026-10-16.
  - Observation: the command returned no message body after 9.95 seconds and reported only `SQLite inbox write failed`. The error did not identify the failed write, contention state or safe retry boundary; its underlying cause remains unknown.
  - Expected pattern: make message delivery and seen-state recording failures distinguishable, with enough operation context to choose a safe canonical retry without inspecting or mutating the database directly.
  - Recovery: one canonical retry returned the full message after 6.30 seconds. Gameplay reported the evidence to Overseer for the User-requested Controller improvements; no database or Controller change was made by this lane.

- `2026-10-17`: Distinguish queued source CI from early metadata runs
  - Context: Six newly published Gameplay heads initially had only early metadata jobs inspectable.
  - Observation: The observer reported that the heads had no source validation. Their original source workflows later completed change detection and started the build/test matrices. No missing-source CI defect or cancellation was established; the premature report sent unnecessary investigation to Overseer.
  - Expected pattern: Retain each original run and classify source selection as pending or unknown while discovery or queue admission is incomplete. A completed metadata gate establishes neither source success nor source absence. The report and observer guidance were corrected with the exact source run identities.
  - Reconsideration trigger: when the observer or CI tooling distinguishes pending source selection from absent proof, qualify it against an early-metadata run with a queued original source workflow and revisit the manual attribution step.

- `2026-10-17`: Targeted Controller status can exhaust its deadline on older queue history
  - Context: Current-front readiness readback followed publication and successful direct head/base verification.
  - Observation: `firemud-controller --worker-identity Gameplay reviews status --pr 3224 --json` failed at its 120-second deadline during `target_history_pr_2846_cli`, after eighty prior phases consumed 119.6 seconds. It returned no JSON, delaying current-front readiness readback.
  - Expected pattern: Keep current-target status bounded while retaining historical review evidence. Overseer received the exact diagnostic; no state deletion, quota reset or installed-runtime bypass was used. The subsequent exact-checkpoint ancestry reconciliations succeeded; the failed status invocation itself remains unavailable evidence.
  - Reconsideration trigger: when targeted `reviews status --pr` completes within its deadline for a PR with long queue history, or the Controller bounds historical phases separately, qualify that behavior and revisit the retained status-readback limitation.

- `2026-10-17`: Repeated Hosted capture can reject a previously finalized provider comment
  - Context: #3231’s second Hosted review was admitted after the first review had been finalized.
  - Observation: Admission reported a nonblocking `predecessor_terminal_capture` warning for the finalized first review: `Hosted finding comment contains unmarked content after its final cr-comment:v1 marker`. The original seven findings, accepted-fix records and checkpoint 6214527024 had already been recorded; the next request posted successfully as trigger 6214841384. The exact trailing-content cause remains unverified.
  - Expected pattern: Preserve completed review evidence and surface the exact capture incompatibility without silently replacing counts or requiring a quota reset. Overseer received the retained diagnostic; Gameplay made no database or installed-runtime bypass.
  - Reconsideration trigger: when the trailing-content cause after the final `cr-comment:v1` marker is identified, or a repeated capture of the affected finalized predecessor completes without the warning, verify the outcome and revisit this retained capture limitation.

- `2026-10-18`: Retain admission context for independent review refusals
  - Context: a CLI request returned `review-threads:1:0` while peer Hosted findings were recorded as accepted and fix preparation was underway; no provider call occurred.
  - Observation: the short error does not establish why the documented peer-channel exception was not applied. Preserve the command result and any existing admission-audit pointer; no Controller defect or cause was established here.
  - Expected pattern: retain the CLI result and admission-audit pointer for each independent review refusal, then inspect the recorded admission decision before assigning a cause.
  - Outcome: ordinary qualified publication and thread resolution cleared admission, and the next request reached the provider. Overseer confirmed that independent-channel guidance remains valid and requested no new investigation or Controller change from this error alone.
  - Reconsideration trigger: if this recurs, retain the existing admission context for adjudication rather than infer a cause from the short refusal.

- `2026-10-18`: Expose review-allocation decision provenance beside its remaining budget
  - Context: #3233 had three lifetime Hosted results while the current native selector correctly showed a separate additional allowance with zero of two used. A retained handover incorrectly inferred exhaustion from the lifetime total.
  - Observation: the current allocation status and review-record history exposed the budget, null checkpoint and taper explanation, but no allocation decision timestamp or original grant rationale. Verifying the controlling allowance required a complete queue readback; no head-change counter reset was established.
  - Expected pattern: expose the controlling grant decision, timestamp, actor, original reason and baseline beside its additional-result count through an existing read-only controller command, so a worker can distinguish lifetime history from the current allowance without guessing. This is a tooling suggestion; existing counting and authorization rules stay unchanged.
  - Reconsideration trigger: when an existing read-only controller command exposes the controlling grant decision, timestamp, rationale and baseline beside the remaining allowance, verify it against separate lifetime and current allowances and revisit the manual queue readback.

- `2026-10-17`: A validation allowance of NONE includes whitespace checks.
  - Context: A historical-test receiving helper ran `git diff --check` despite an explicit validation allowance of NONE.
  - Observation: It reported the extra check; no runtime tests or source edits resulted from it. The handoff also said no validation ran, which obscured the distinction.
  - Expected pattern: Treat standalone whitespace checks as validation under the closed allowance. Report the actual check and its outcome, and wait for the orchestrator’s named gate. Main gave that bounded correction and selected receiving proof separately; the extra check earns no test credit.
  - Recurrence (`2026-10-18`, #3249): a parent-carry helper again ran a scoped `git diff --cached --check` under an explicit validation allowance of NONE. The check exited zero and grants no proof credit. Main corrected the contradictory no-validation claim and retained ownership of the named integration gate.

- 2026-10-18: In the CI maintenance PR #3264 Hosted retry, an ad hoc multiline shell read a snapshot file before its producer finished; the JSON check failed but the following provider POST still executed because that shell did not enable errexit. The prior and completed fresh snapshots both showed no active Hosted request, so no conflict was observed. Keep a dependent external mutation in a separate tool call after checking producer completion and validated output; use explicit `set -euo pipefail` for dependent shell batches.

- 2026-10-18: CodeRabbit full review 5560170943 for PR #3264 emitted a complete 1/1 publication with one outside-diff Major but no bounded actionable-comment count. The existing manual adoption correctly refused automatic attribution with `Hosted publication has no unique bounded actionable-comment count`. Exact trigger 6241278398, reviewed head `187ab53426caf78cd6a01b61e092b800186aaec2`, provider result and accepted obligation were retained in the CI validation throughput controller job history without invented Hosted credit; no controller adapter change was made in this CI patch. Reconsider format support only in a separately scoped controller maintenance task.

- `2026-10-18`: Keep automatic Git repack failures separate from local commit proof
  - Context: The #3248 private-parent receiving commit `029b6299b0ec63a6cd0a1cd30197cc7b3ca56ded` triggered automatic Git maintenance in the shared repository.
  - Observation: The commit succeeded, and background repack reported `fatal: bad object refs/remotes/origin/codex/document-original-world-preparation-20261015` and `fatal: failed to run repack`. The exact cause and repository object health remain unknown.
  - Expected pattern: Record commit and receiving evidence separately from background maintenance results. Investigate only under qualified shared-repository maintenance or when an actual delivery blocker requires it; `gc.log` or unreachable-object warnings do not authorize cleanup.
  - Status and reconsideration: The commit retained prospective tree `733ed58d9390a5ba00e24088435af7b847567527`, all 84 child-owned postimages and the deletion, and all 12 qualified incoming blobs. Evidence is in Controller current-gameplay update 2151, `private-parent-carry-via-09e-verification.json`, and `receive-qualified-private-cli1-parent.patch` under the private #3248 local current-parent-carry evidence. Revisit if a qualified maintenance pass identifies the cause or the failure blocks delivery.

## 2026-10-18 — PR retarget can fail exact merge-parent metadata checks

After #3237 merged and #3246 was retargeted to `develop`, Runtime Images run `39093729815` and Preview run `39093729854` rejected the retargeted head/base pair because the selected test-merge commit did not have its exact parents. Runtime failed before image production; Preview exhausted five merge-computation attempts. The previous parent’s green runs do not qualify the new pair. The PR Runtime path uses `GITHUB_SHA`; an empty dispatch-only `EVENT_MERGE_SHA` is not the cause. The reason for the mismatched merge selection is unconfirmed.

Gameplay reported the captured failures to Overseer in controller message `64512c73-9fa2-44b5-b2b7-e5b607155777`, with Preview evidence in reply `0de28b96-8790-4e5e-b8e5-a915d96de829`. No guard was weakened and no blind rerun was requested. Normal parent reconciliation and the already-required correction push are the next recovery boundary; replacement CI has not yet established recovery.
