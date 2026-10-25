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

- `2026-10-15`: Preserve receiver status when relocating inherited tracker proof
  - Context: the local #3224 parent reconciliation relocated inherited LOGIN proof into a dedicated section. Selecting the complete incoming tracker plus one receiver proof paragraph also displaced the receiver's first-party denial and historical-proof qualifications. The candidate had not been published.
  - Observation: verifying nonoverlapping blobs and paragraphs unique against the merge base missed receiver status that also appeared in the base. Main later compared the complete receiver document, restored every original receiver paragraph byte-for-byte, and retained the incoming concise status and relocated proof. No production source changed.
  - Expected pattern: inspect the complete overlap against the receiver's current contract and status, including text shared with the merge base. Proof relocation alone does not authorize replacing unrelated current-status sections with an earlier parent's view.
  - Reconsideration trigger: a native reconciliation check proves preservation across moved paragraphs and status replacements; ordinary clean merges and nonoverlap comparisons do not establish that property.

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
  - Context: Game Design template repository update path (`GameTemplateRepository.save`) and native `GameTemplateRepositoryPostgresIntegrationTest` on the template create/update correction lane.
  - Observation: The global-ID update rewrote a retained row's tenant before returning it under the supplied tenant; tenant-filtered readback did not protect the original row. The native counterexample changed the complete row and `xmin`, while the restored exact tenant predicate preserves both owner rows and their versions.
  - Expected pattern: Test controlled pre-phase rows as well as later guarded owners. Keep the create-only ID refusal before mapper/repository access, and separate this owner-local SQL proof from authenticated creator or hosted-content readiness.

- `2026-10-16`: Check existing successor repairs before reworking a failed fixture.
  - Context: While fixing the three #3251 publication transaction failures, two focused local gates failed before the worker noticed that the already-reconciled #2967 tree contained the complete selected-full schema-4 fixture repair.
  - Observation: The first attempts corrected stale collector stubs but missed the dependent fixture profile.
  - Expected pattern: Compare the bounded failing test and its called helpers against known receiving successors before changing or rerunning it; reuse the complete canonical repair without removing assertions.
  - Outcome: The exact four-path backport at `4265a81f008d0dbd343cd9adf6413bd3ac5c8904` then passed 66 unit and 10 native cases. The failed attempts retain their original outcomes; this lesson does not establish a general infrastructure cause.

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

- `2026-10-18`: Follow up the PostgreSQL endpoint refusal with one scoped reproduction
  - Context: the new Automation Flyway test-time refusal met the reconsideration trigger in the `2026-10-13` PostgreSQL endpoint-diagnosis observation.
  - Observation: one focused five-store reproduction passed with zero failures, errors or skips and no endpoint refusal. All five exact containers were observed running with host-port bindings and absent after the Gradle child ended. Six inspect errors and 20 unparsed records arose only after the case completed during sequential teardown, so continuous ownership through cleanup is not established.
  - Expected pattern: retain the original failed test evidence and capture only exact run-owned container states and ports before teardown when a new refusal justifies focused reproduction; keep a successful reproduction distinct from an explained cause.
  - Outcome: the preceding Automation Flyway refusal remains failed evidence with unknown cause; the passing reproduction does not explain it. No speculative host or readiness change is selected.
  - Reconsideration trigger: a later physical test-time endpoint refusal, or separately retained evidence that identifies the refused endpoint's failure mechanism.

- `2026-10-18`: Verify LOC report identity after a PR fast-forward
  - Context: `cloc-report.py pr 3248` ran immediately after the existing branch advanced from `6c3eed5d651b` to `eb40332cdb9f`.
  - Observation: GitHub still returned the preceding PR head/base payload, so the canonical helper completed successfully with LOC metadata for the old candidate. The worker caught the mismatch before editing the body and regenerated after payload convergence; no stale report was published. The tool-level consistency guard remains an improvement reported to Overseer.
  - Expected pattern: compare generated head/base metadata with verified current remote refs before publication; a mismatch must refresh or fail closed. A successful LOC command alone does not prove current candidate attribution.
- 2026-10-18: In the CI maintenance PR #3264 Hosted retry, an ad hoc multiline shell read a snapshot file before its producer finished; the JSON check failed but the following provider POST still executed because that shell did not enable errexit. The prior and completed fresh snapshots both showed no active Hosted request, so no conflict was observed. Keep a dependent external mutation in a separate tool call after checking producer completion and validated output; use explicit `set -euo pipefail` for dependent shell batches.

- 2026-10-18: CodeRabbit full review 5560170943 for PR #3264 emitted a complete 1/1 publication with one outside-diff Major but no bounded actionable-comment count. The existing manual adoption correctly refused automatic attribution with `Hosted publication has no unique bounded actionable-comment count`. Exact trigger 6241278398, reviewed head `187ab53426caf78cd6a01b61e092b800186aaec2`, provider result and accepted obligation were retained in the CI validation throughput controller job history without invented Hosted credit; no controller adapter change was made in this CI patch. Reconsider format support only in a separately scoped controller maintenance task.

- `2026-10-18`: PR retarget can fail exact merge-parent metadata checks
  - Context: After #3237 merged and #3246 was retargeted to `develop`, Runtime Images run `39093729815` and Preview run `39093729854` checked the retargeted head/base pair.
  - Observation: Both runs rejected the selected test-merge commit because it did not have its exact parents. Runtime failed before image production; Preview exhausted five merge-computation attempts. The PR Runtime path uses `GITHUB_SHA`; an empty dispatch-only `EVENT_MERGE_SHA` is not the cause. The reason for the mismatched merge selection is unconfirmed.
  - Expected pattern: The previous parent's green runs do not qualify the new pair. Preserve the exact-parent guard and do not rerun blindly. No guard was weakened and no blind rerun was requested.
  - Recovery: Gameplay reported the captured failures to Overseer in controller message `64512c73-9fa2-44b5-b2b7-e5b607155777`, with Preview evidence in reply `0de28b96-8790-4e5e-b8e5-a915d96de829`. After normal parent reconciliation and the required correction push to `a9d9f27a4139c2be2cbf9b7c1df4c4a33d6b8bf3` on `develop` at `efbc55874894ce5b2b420e19fac5ab23d66e384e`, replacement Runtime Images run `39094799761` and Preview run `39094798308` both succeeded. This establishes recovery of those metadata checks after that publication boundary, without explaining the original mismatch or claiming whole-CI success.

- `2026-10-15`: Allocate forward migrations against the resolved combined Flyway history
  - Context: The selected-owner intake plan's older Account V161 and Automation V5 sketch would sort below already installed combined history, including shared Saga resources and later service-owned migrations.
  - Observation: The receiving Account parent already reaches V1005, while Saga reaches V1002.
  - Expected pattern: Preserve existing SQL and prove a retained upgrade from the actual combined parent instead of enabling out-of-order execution or inferring a frontier from the service's older application lineage.

- `2026-10-16`: Use bounded admission for shared heavy runs
  - Context: The canonical local resource lock already serializes eligible heavy runs across worktrees. Ordinary workers can submit through supported bounded admission and retry a busy result without peer permission or an idle reservation between scheduled wake-ups.
  - Observation: A Document source gate automatically acquired the lock before its delayed peer completion message arrived.
  - Expected pattern: Treat busy admission as unrun proof.

- `2026-10-16`: Bound conflict markers and inspect reconstructed files
  - Context: A multiline conflict-marker resolver truncated three trailing file portions during an unpublished selected-owner carry.
  - Observation: Native `git merge-file` reconstruction from the exact parents recovered the complete files; a clean whitespace check alone cannot detect lost content.
  - Expected pattern: Bound marker lines explicitly, verify conflict counts and file tails, and inspect the final net diff against both original parents before committing.

- `2026-10-16`: Check both public mapping directions for transport identity
  - Context: An internal read requires an identity distinct from its original operation. The launch-association receiver retained the guard while a refactor removed it from the request encoder.
  - Observation: The receiver's refusal did not constrain local encoding. A producer refusal assertion reproduced the omission at `653c42cea1`, and restoring the existing encoder guard passed the full 917-case Common gate at `cefbf09ebb`.
  - Expected pattern: Keep the transport identity rule at both boundaries and distinguish local codec proof from controlled socket responses or genuine owner authority.

- `2026-10-18`: Keep automatic Git repack failures separate from local commit proof
  - Context: The #3248 private-parent receiving commit `029b6299b0ec63a6cd0a1cd30197cc7b3ca56ded` triggered automatic Git maintenance in the shared repository.
  - Observation: The commit succeeded, and background repack reported `fatal: bad object refs/remotes/origin/codex/document-original-world-preparation-20261015` and `fatal: failed to run repack`. The exact cause and repository object health remain unknown.
  - Expected pattern: Record commit and receiving evidence separately from background maintenance results. Investigate only under qualified shared-repository maintenance or when an actual delivery blocker requires it; `gc.log` or unreachable-object warnings do not authorize cleanup.
  - Status and reconsideration: The commit retained prospective tree `733ed58d9390a5ba00e24088435af7b847567527`, all 84 child-owned postimages and the deletion, and all 12 qualified incoming blobs. Evidence is in Controller current-gameplay update 2151, `private-parent-carry-via-09e-verification.json`, and `receive-qualified-private-cli1-parent.patch` under the private #3248 local current-parent-carry evidence. Revisit if a qualified maintenance pass identifies the cause or the failure blocks delivery.

- `2026-10-18`: Validation allowance NONE recurred in #3249
  - Context: A parent-carry helper again ran a scoped `git diff --cached --check` under an explicit validation allowance of NONE, repeating the [2026-10-17: A validation allowance of NONE includes whitespace checks](#ai-observations) finding.
  - Observation: The check exited zero and grants no proof credit. Main corrected the contradictory no-validation claim and retained ownership of the named integration gate.
  - Expected pattern: Honor NONE as excluding every check, including scoped Git whitespace checks. If a check runs anyway, report it accurately, give it no proof credit, and retain the orchestrator's ownership of the named integration gate.

- `2026-10-19`: Preserve completed CLI evidence when review-record saving fails
  - Context: #3248's CLI review `run.42bffcfb0b4c4402b50518add5f4ca48` completed with two findings after 212 seconds under the installed Controller runtime.
  - Observation: The wrapper returned success but warned only that the SQLite review record was not saved. The durable capture contained the result while the canonical attempt remained started, so provider completion did not establish recorded review credit. The warning hid the underlying exception and referred to a recovery heading that was absent from the lifecycle guide; the existing migration command was broader than the required one-capture recovery.
  - Expected pattern: Preserve the immutable capture and original attempt identity, report the recording gap, and use an explicitly bounded canonical recovery before adjudication and finalization. Do not rerun a completed review, invent credit, or use an unrelated schema migration to repair its record.
  - Status and reconsideration: Overseer owns bounded recovery in #3271; no live recovery or migration is authorized yet. The exact persistence cause remains unconfirmed. Revisit after that maintenance change is qualified and promoted and the original capture is recovered through its exact native command.

- `2026-10-15`: Normalize nested fixture payloads before computing a digest golden
  - Context: Selected-full digest fixture R41/R42 produced different fixed-golden values from otherwise unchanged source.
  - Observation: The fixture serialized `Map.of` payloads into strings inside a canonical binding; outer canonicalization preserves string contents and therefore does not normalize the inner map order.
  - Expected pattern: Restored explicit RFC 8785 normalization of each fixture payload before binding creation. Updating a golden from one failing run alone did not establish a stable vector; retain equality/mutation checks and verify the normalized vector before claiming deterministic proof.
  - Outcome: No production digest or retained bytes were changed. R44 and the fresh R45 JVM agree on the normalized vector; all 686 GDS unit cases pass in R45.

- `2026-10-15`: Separate selected-publication owner proofs missed an Account settlement profile gap.
  - Context: The actual GDS terminal → authenticated World completion → both real terminal reads composition reached Account V94 and failed with SQLSTATE 23514 because its inventory validator still admitted only inventory/1 + World/3, while the current canonical producer retains closure inventory/2 + World/4.
  - Observation: Correction and qualification are in progress.
  - Outcome: Forward V1011 converges both validation profiles; the following selected-publication settlement entry records the R14/R16 qualification and its controlled-proof limits.
  - Expected pattern: Preserve that failed composition beside prior component passes; a forward validator correction needs exact profile/refusal and existing-settlement byte/row-version upgrade proof. Do not rewrite installed migrations or treat controlled terminal readbacks as actual producer composition.

- `2026-10-15`: Selected-publication settlement profile follow-up.
  - Context: Account's forward V1011 now converges the exact inventory/1 + World/3 and closure inventory/2 + World/4 validation profiles while retaining the original framing, digest, source and artifact guards.
  - Observation: R14 passes the actual six-store terminal-to-settlement composition and nine malformed-profile refusals; R16 passes original PUBLISHED and NO_PUBLICATION settlement upgrade/replay with unchanged stored bytes and row versions.
  - Outcome: A test-only V1010 predecessor allowlist omission was corrected separately.
  - Expected pattern: These controlled component proofs supply no genuine creator, issuer, environment or deployed authority.

- `2026-10-16`: Keep FireController briefs within the size limit
  - Context: FireController job briefs are capped at 200,000 characters.
  - Observation: Repeatedly prepending completed and running checkpoints caused a revision to be rejected before the active execution state could be saved.
  - Expected pattern: Archive the full previous brief, retain standing instructions and superseding decisions, replace the active execution section, and put milestones in native updates/checkpoints. Consolidation restored guarded updates without losing source history or standing authority.

- `2026-10-16`: Keep the original Logging claim-read client workload-only
  - Context: A generic gRPC channel and stub customizer did not establish the original Logging claim-read client's workload-only boundary: the common customizer attaches bearer metadata, and a trusted TLS channel alone does not pin the expected SPIFFE workload.
  - Observation: The corrected client uses the existing Common peer credentials and response verifier, preserves default-off composition, and rejects ambient SQL, synchronization and caller context before RPC. Controlled loopback mTLS and the existing native owner consumer now prove this bounded behavior; they do not establish genuine upstream authority.
  - Expected pattern: Keep the configured server identity separate from mutation target data, and follow canonical usable TLS-material policy rather than copying a donor's regular-file predicate.

- `2026-10-16`: Bound subprocess output and waits in the original claim-read test PKI fixture
  - Context: Reading subprocess output to EOF before a timed wait leaves the supposed deadline unbounded.
  - Observation: The original claim-read test PKI helper now redirects output to an owned temporary file, waits before reading it, and terminates/reaps a timed-out or interrupted child with interrupt preservation. Actual stalled-keytool timeout and interruption cases pass alongside the affected TLS and native owner proofs.
  - Expected pattern: Keep this correction in the purpose-specific fixture; it does not require a general command framework or change production TLS material policy.

- `2026-10-16`: Recheck original ControlUI expiry after owner locks
  - Context: An initial ControlUI token expiry check became stale across later owner locks in both current-actor paths. Registry cleanup retention and authorization-reference expiry did not replace the original token's validity boundary.
  - Observation: The focused native fixture advances the existing actor clock at the real locking read: the old code entered both callbacks after expiry; the correction rejects them without changing the original row while preserving exact held-order recovery.
  - Expected pattern: Keep current authorization and historical recovery distinct when reconciling displaced guards.

- `2026-10-16`: Compare the complete original validity interval at one owner boundary
  - Context: original creator callbacks can wait for Account owner locks after initial token verification.
  - Observation: an expiry-only final check misses a clock observation before the original not-before bound; registry cleanup retention and authorization-reference expiry do not define the original token's full interval.
  - Expected pattern: use one existing-clock observation for the canonical original `issuedAt <= now < expiresAt` interval, test both denial bounds and exact lower-bound equality, preserve immutable evidence, and keep historical exact-order recovery separate from fresh authorization.

- `2026-10-16`: Separate original signer provenance from present public-key acceptance
  - Context: fresh signed and committed-JTI creator callbacks must retain their original key identity.
  - Observation: an immutable source pin, historical committed-signer receipt and still-fresh signature cache do not independently establish that the original key remains in the current protected public snapshot.
  - Expected pattern: load the existing trusted source outside SQL and synchronization, compare the original key ID and RFC 7638 fingerprint, preserve accepted retiring-key membership and historical exact recovery, and state the per-invocation snapshot limit beside the proof.

- `2026-10-16`: Bound child waits in standalone receiver PKI fixtures
  - Context: The standalone activation and World participation receiver PKI fixtures had the same output-before-wait pattern, leaving child execution unbounded.
  - Observation: Both use the existing purpose-specific bounded-child pattern; four actual stalled-JDK-child timeout/interruption cases and all eight consuming receiver cases pass.
  - Expected pattern: Compare each remaining fixture owner independently; an earlier Logging fixture pass does not prove these locations, and controlled socket proof does not establish real Account settlement or genuine PKI.

- `2026-10-19`: Independent maintenance temporarily becomes the product queue's parent
  - Context: Controller recovery maintenance PR #3271 was inserted before the existing product front to obtain the next admissible shared review slot.
  - Observation: the current controller queue is strictly linear and has no independent maintenance root. Inserting an open maintenance PR before the product front changes the product front's effective parent even though the patches are independent, creating an integration fence until maintenance merges.
  - Expected pattern: assess and communicate this queue consequence before insertion; preserve the product's qualified head, original parent and proof rather than rebasing it onto unmerged maintenance. Keep the temporary maintenance front through its normal review requirements and release the integration fence at its human-authorized merge. This records the current operational limitation, not a new queue design or review-policy exception.
  - Reconsideration trigger: if existing tooling gains independent maintenance-root scheduling, qualify its parent/proof behavior before changing this operational assumption.

- `2026-10-19`: Paginate the complete CI observer context inventory
  - Context: On 2026-10-18 UTC / 2026-10-19 NZDT, the first 100 GraphQL contexts omitted the [#3249 Smoke Gate failure](https://github.com/benhook1013/FireMUD/actions/runs/39142129196/job/117718532427) at `b83`, delaying its detection by about 19 minutes; the complete inventory contained 186 contexts. The private observer now reads all pages within its bound and fails closed if the final `pageInfo.hasNextPage` remains true, errors occur, or head identity differs.
  - Observation: A first-page result can miss a later failure in the same run. The observer now fails closed when pagination or response checks are incomplete; this records the observer recovery and does not establish or claim an underlying gate bug fix.
  - Expected pattern: Treat a complete paginated inventory as authoritative, verify page completion, errors, and the requested head, and report no complete result when any check fails.

- `2026-10-19`: Tie routed review-thread closure to the published candidate
  - Context: On 2026-10-18 UTC / 2026-10-19 NZDT, Main prematurely resolved a routed #3269 H1 thread after the parent #3249 fix was published but before the child carry was published. The thread was immediately reopened and finally resolved after child commit `bc10` was published.
  - Observation: A routed parent fix alone did not prove that the child candidate carried the fix.
  - Expected pattern: Close a routed thread only against the relevant published candidate; verify the child carry publication before treating the thread as resolved.

- `2026-10-20`: Preserve Testcontainers startup-failure limits
  - Context: The focused canonical gate at `54e9ef35454f669a1f07cec73d13511febe12233` passed eight native cases across the two previously failing Account integration classes in 78.543 seconds, with zero failures, errors or skips; no Redis source or readiness change was made for this rerun.
  - Observation: Testcontainers 2.0.5 `HostPortWaitStrategy` checks internal container and external mapped-port reachability concurrently, then reports a generic timeout without preserving the subcheck cause. Redis-ready logs alone do not isolate host forwarding. A child-command timeout or Future cancellation does not prove the Docker exec RPC ended.
  - Expected pattern: Match exact container, fixture and mapped-port evidence before assigning a cause. Credit a focused rerun as its own proof; it neither erases the original failed aggregate nor proves a repair.

- `2026-10-23`: Give expired-certificate fixtures explicit past validity dates
  - Context: [#3269's dev-tool contract job](https://github.com/benhook1013/FireMUD/actions/runs/39914844923/job/120286735224) failed while generating its expired RSA CA fixture with `openssl x509 -days -1`, before the bootstrap denial assertion could run.
  - Expected pattern: Use an explicitly ordered past validity interval for an expired test certificate, retaining its CA/key-usage constraints and the assertion that expired authority cannot enable active mode. A fixture-generation failure is not evidence that the production expiry check failed; qualify the replacement fixture and the complete owning contract separately.
  - Status: The corrected fixture, complete owning contract and ShellCheck passed locally on OpenSSL 3.0.2. Current-head CI remains required, and the earlier failed job remains failed evidence.

- `2026-10-23`: Isolate uv managed-Python launchers as well as installations
  - Observation: A temporary docs-lock generator set `UV_PYTHON_INSTALL_DIR` inside its task directory, but uv 0.13.5 also created `~/.local/bin/python3.15`. The exact newly created task-owned symlink was verified and removed; no preexisting launcher was overwritten.
  - Expected pattern: Set `UV_PYTHON_BIN_DIR` to a task-private path before temporary installation and confirm it with `uv python dir --bin`; this override was verified locally. Alternatively, `uv python install --no-bin` is supported by the installed help. Installation-directory isolation alone does not isolate launchers.

- `2026-10-23`: Preserve incomplete ORT license-scan evidence after Maven Central 403s
  - Context: PR #3251 License Gate run `39934774515`, job `120353147724`, recorded 403 responses for 392 distinct Maven Central URLs during Gradle analysis; the response origin remains unknown.
  - Observation: ORT exited with analyzer errors, emitted unknown-dependency rule warnings, and could not create the WebApp report within its 5120 MiB heap. CycloneDX reports were generated, but artifact upload did not run, so the scan supplies no qualified license proof.
  - Expected pattern: Keep the required gate fail-closed and establish the 403 response origin before selecting a correction; do not infer a fix from the secondary reporter OOM or unknown-dependency warnings.
  - Status: Root informed for recurrence and recovery tracking; no cause or repository correction established.

- `2026-10-24`: Enforce ORT analyzer completion separately from report generation
  - Observation: The pinned ORT CI action accepts `violations`, while both license jobs supplied the unrecognized `policy-violation` value. The action also intentionally tolerates analyzer exit code 2 so evaluation and reporting can continue; a successful report therefore does not establish complete dependency analysis or enforced policy.
  - Correction and proof: Use the supported violation gate and explicitly require analyzer exit code 0 in both jobs, retaining partial results and Gradle failure diagnostics. Focused tests execute the actual workflow guards for successful, failed, missing and malformed statuses. This repairs enforcement independently of the earlier Maven Central 403 incident; its response origin remains unknown and local tests do not establish hosted scan success.

- `2026-10-24`: Preserve uncertain coordination delivery during controller response failures
  - Observation: Gameplay's own bound CLI inbox read and native inbox/job reads returned `invalid_response`; the native attempt to notify FireController Worker returned the same error. The message delivery outcome is unknown, so no successful report or handled inbox state is claimed.
  - Expected pattern: Retain the original notification key and request for supported recovery, avoid debugging or changing the service, and continue independent local work. Fresh instructions, review capture and official progress recording remain unavailable until the owning connection recovers.

- `2026-10-24`: Carry the build environment and explicit package metadata into ORT containers
  - Observation: The ORT analyzer container did not inherit `CI`, so this project's Node Gradle plugin enabled a download repository that ORT cannot analyze. ORT 97 also disables repository package curations by default, even when it discovers a valid `.ort.yml`.
  - Expected pattern: Forward the existing CI environment contract explicitly and enable the documented repository-curation option while retaining global providers. Normalize only exact package/version metadata supported by upstream license text, and record a package-specific choice for an explicitly offered alternative. Custom licenses require their own policy decision; do not relabel them as SPDX aliases or waive unresolved policy errors.
  - Proof limit: Workflow wiring, YAML, failure guards and upstream metadata are checked locally. Only actual analyzer/evaluator results can qualify the hosted correction; the earlier Maven Central 403 response origin remains unknown.
  - Live-policy correction: The default evaluator rejects package curations inside `.ort.yml` even when the analyzer supports them. Keep exact package metadata in a read-only child mount under the existing global `DefaultDir` provider instead, preserving its other entries and rules; keep the valid OR-license choice in an NPM-scoped repository configuration so Gradle-only scans do not reject an absent package choice. Configuration-schema support alone did not establish policy acceptance, and actual hosted proof remains required.

- `2026-10-24`: Distinguish PostgreSQL health from the Docker-published connection path
  - Context: A forced Account integration run at `3534f58291439f7c059759cc57a7385106051e39` ran 333 cases with 13 mapped-localhost connection failures and no skips. One failed fixture recorded a running PostgreSQL container, successful internal Unix-socket `SELECT 1`, IPv4 localhost resolution and a Docker host-port binding while the test JVM's mapped JDBC connection was refused. The other twelve failures shared a different fixture without equivalent internal-health diagnostics.
  - Observation: Those facts isolate the first failure to the test-to-published-port path without identifying a Docker Desktop, WSL, fixture or cleanup cause. Successful internal SQL or published binding metadata alone is insufficient host-path readiness proof.
  - Outcome: A later focused run passed all seven source-evidence and twelve selected-owner cases without a Docker, host-setting or fixture change. This qualifies those nineteen cases separately; it neither turns the earlier failed aggregate green nor proves the connection problem repaired.
  - Expected pattern: Retain exact fixture, host, mapped-port and internal SQL evidence beside the original failed run; qualify a focused retry independently and investigate recurrence without weakening readiness or cleaning unrelated containers.

- `2026-10-25`: Local wrapper disappearance recurred without an established cause
  - Context: #3294 R191 repeated the command-session loss recorded on 2026-10-16. The canonical supervisor reported `Gradle wrapper exited; cancelling its owned run.` and exit 143 during PostgreSQL validation; the single-use daemon recorded client disconnection and a broken pipe.
  - Observation: the wrapper disappeared in under two minutes, below the configured 7,200-second budget. A bounded readable kernel-log check found no OOM evidence. The cause remains unknown; neither timeout nor OOM is established. Only the owned invocation was cancelled, and its partial or stale test artifacts earned no aggregate pass. The same canonical checks under a retained terminal session passed as R192 in 2m48s. Fresh-image R193 then passed held-edge and isolated-backend checks with authenticated teardown and independent confirmation of zero remaining run-owned containers, networks and volumes. The retry does not establish that terminal allocation corrected the cause.
  - Expected pattern: on recurrence, retain the wrapper's parent/terminal lifecycle and delivered signal or exit evidence before attributing the disappearance or changing the [canonical wrapper](../../dev-tools/validation/run-locked-gradle.sh).

- `2026-10-25`: Read workflow guidance from the active worktree
  - Context: #3294's active checkout already used FireController readiness, but an older dirty root checkout and injected chat instructions still named the retired `check-coderabbit-review.py`. An attempted invocation failed because that script was absent.
  - Observation: FireController verified that the active commit's documentation was already correct; this was a context mismatch, not a missing tool or runtime defect. The lane reread the active worktree's instructions and retained the failed attempt without recreating the retired script or claiming it passed.
  - Expected pattern: pin the active worktree and revision before selecting workflow commands; use the installed release's explicit readiness observation and native assessment, while independently consuming complete review summaries and findings as required by [PR lifecycle](../developer-workflows/pr-lifecycle.md).

- `2026-10-25`: Focused service checks can compile another helper's unfinished dependency
  - Context: Gameplay R206 selected Game Session checks while a disjoint Entity helper was still editing its repository; Gradle transitively compiled Entity and failed on newly referenced classification methods that had not yet been added.
  - Observation: this was an incomplete shared working tree, not evidence against a completed correction. Serializing Gradle processes does not stabilize concurrently edited source dependencies.
  - Expected pattern: wait for every helper editing a transitive source dependency to hand off before launching dependent checks. The lane owner stopped further launches and consolidated proof after handoff; preserve the failed run without attributing it to a qualified candidate.

- `2026-10-25`: PostgreSQL published-port refusal recurred in Entity proof
  - Context: Gameplay R217 ran the full Entity gate after focused R211 proof. All four `EntityEmptySelectedSourceIntakeMigrationPostgresIntegrationTest` cases failed in `@BeforeEach` against the same mapped localhost port after Testcontainers reported startup; their assertions never ran. The exact container was gone before inspection, while other fixtures used distinct containers and ports.
  - Observation: this meets the endpoint-refusal reconsideration trigger recorded on 2026-10-18 and 2026-10-24. Available evidence does not identify container exit, cleanup, startup timing, or the published-port path as the cause; no shared-container or parallelism defect was established.
  - Expected pattern: retain R217's failed aggregate and XML, capture state, port and internal SQL evidence for only that exact run-owned fixture during the next justified execution, and keep any passing retry separate from a diagnosed repair. Do not change host settings, weaken readiness or clean unrelated containers on this evidence.

- `2026-10-25`: Correlate observer output with the actual completion timestamp
  - Context: Gameplay reported a pending-code versus green-readiness mismatch for #3294 from an older native watcher output. The actual substantive Validation gate succeeded at 16:05:45 UTC and its supplementary status at 16:06:17 UTC, before the cited later readiness observation.
  - Observation: the green metadata jobs were separate native checks; the acknowledged supplementary status remained pending until substantive proof succeeded. The bounded #3295 comparison also retained its pending barrier while substantive Validation was unfinished. These records did not establish a gate-integrity or controller defect.
  - Expected pattern: compare exact head, base, required context, App identity and source completion timestamps before reporting a current contradiction. Retain a failed substantive job independently of its unfinished aggregate; stale watcher text does not establish that source proof is still pending. Overseer received the correction; no workflow, controller or branch-protection repair was justified by this case.
- `2026-10-25`: Reduce commit-status history before assessing current CI
  - Context: the shared Gameplay observer inspected paginated GitHub commit-status records for the PR train.
  - Observation: `/statuses` retained superseded pending and failed entries even after the same gate had published success. Treating every historical entry as current briefly misclassified a green candidate; the direct combined `/status` response and latest entry per context established the actual state before any CI mutation or escalation.
  - Expected pattern: use the current combined-status response and latest status per context for readiness; preserve pagination for full check-run coverage, and treat raw status history as history. Verify a reported failure against its exact run and job before retrying or attributing a CI defect.

## 2026-10-26: Reuse concrete PostgreSQL and binary Redis fixture bindings

In Gameplay R517, jOOQ plain SQL bound an `OffsetDateTime` fixture value as `VARCHAR`; PostgreSQL refused the timestamp column before the migration test reached its subject. Use an explicit `CAST(? AS timestamp with time zone)` at that fixture binding while retaining the timestamp and schema constraints. R518 then passed the retained-row upgrade and restart case.

For Lettuce `RedisCommands<byte[], byte[]>` key varargs, reuse `List.of(key).toArray(byte[][]::new)` from the [initial-context AOF probe cleanup](../../services/game-session-service/src/test/java/integration/net/firedevops/firemud/gamesession/service/InitialGameplayLoginContextRedisIntegrationTest.java). Both a direct `byte[]` and an inline single-element `byte[][]` triggered SpotBugs' primitive-array varargs detector in the new CLOSED-barrier fixture. Keep the native test result separate from the aggregate's static failure; use the explicit typed collection conversion rather than suppressing the detector.

- **2026-10-26 R526 Redis startup refusal:** the CLOSED-owner test failed while its Redis 8.10 container was starting and waiting on `localhost:54214`; assertions did not run. The exact run-owned container `e00a9761678b8fa9dead11e45604ad263049409c19746034c72b7565d549b669` was absent at inspection, as were all 11 R526 containers. The cause is unknown; this does not diagnose a test defect, timeout, or OOM. Preserve the failed aggregate and rerun focused normal proof without timeout or host-setting changes; a passing retry would not diagnose the cause.

- **2026-10-26 R533 Account finalization failure timing:** `staleSerializableGenuineAckCreatorRetriesAndRetainsOneExactWinner` failed in `finalized()` after 1.292 seconds, before the serializable race began. The SQL guard message `storage deadline expired` covers either `evaluatedAt >` the database clock or `expiresAt <=` the database clock, so the branch and clock cause are unknown. Retain the failed result; an unchanged future retry cannot recover the original failure-time clock evidence. Capture the original evidence timestamps, JVM and database wall clocks, and monotonic elapsed time only after a failure, without delaying the original COMMIT or changing its lifetime or guard.
