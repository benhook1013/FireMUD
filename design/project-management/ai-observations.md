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

- `2026-10-15`: A failed SpotBugs task may have no enabled diagnostic report. The Document preparation gate returned only exit code 1 with no finding or report; explicitly enabling XML through a temporary init script exposed one unused fixture local. Scope that temporary script through `findProject` because Gradle also applies initialization scripts to `buildSrc`. The ordinary corrected World gate then passed. Enabling a useful report in the existing build task configuration is a tooling opportunity; no canonical build configuration was changed in this lane.

- `2026-10-15`: Keep the shared local Gradle heap qualification explicit
  - Context: repeated 1.4 GiB heap thrashing led to a shared 3 GiB local daemon setting for serialized work.
  - Observation: a mostly up-to-date run passed, but cold-build speed and total memory use remain unmeasured; worker, test, and code-generation JVMs add separate memory, and the hosted runner remains explicitly configured at 2500m.
  - Expected pattern: treat the setting as a qualified local choice, not a repository-wide memory guarantee or a change to hosted sizing.
  - Reconsideration trigger: fresh OOM/GC thrashing or a concrete need to measure cold-build memory.

- `2026-10-15`: Classes-only foreign-owner proof artifacts intentionally omit application and Flyway resources. A composed test that reads another owner’s migrations from disk must declare those directories as task inputs; otherwise a SQL-only change can leave the composition up-to-date. The selected Game Logic proof exposed this after its forward migration was reconciled, and Game Design now tracks the foreign Game Logic and World migration directories.

- `2026-10-15`: Verify the complete absolute patch destination after the first write
  - Context: Gameplay’s World digest-guard helper verified its checkout but mistyped the absolute migration path, creating a file in a similarly named sibling directory outside Git. This is distinct from the earlier relative-path incident: an absolute path alone does not identify the intended checkout.
  - Observation: Verifying the checkout did not prevent a typo in the absolute patch target. The helper reported the mistake; Main compared the misplaced and intended files byte-for-byte, removed only the confirmed new duplicate, and preserved the sibling directories. No misplaced file was published.
  - Expected pattern: Compare every patch target with the pinned worktree path, then inspect the first written file at that absolute destination before continuing. This destination check remains manual.
  - Reconsideration trigger: enforce destination verification at the native patch boundary, or repeat measurement disproves the risk; completion of migration review alone does not resolve it.

- `2026-10-15`: Allocate forward migrations against the actual resolved Flyway history, including shared Saga resources and later service-owned migrations. The selected-owner intake plan's older Account V161 and Automation V5 sketch would sort below already installed combined history. The receiving Account parent already reaches V1005, while Saga reaches V1002. Preserve existing SQL and prove a retained upgrade from the actual combined parent instead of enabling out-of-order execution or inferring a frontier from the service's older application lineage.

- `2026-10-16`: The canonical local resource lock already serializes eligible heavy runs across worktrees. Ordinary workers can submit through supported bounded admission and retry a busy result without peer permission or an idle reservation between scheduled wake-ups. A Document source gate automatically acquired the lock before its delayed peer completion message arrived; busy admission remains unrun proof.

- `2026-10-16`: A multiline conflict-marker resolver truncated three trailing file portions during an unpublished selected-owner carry. Native `git merge-file` reconstruction from the exact parents recovered the complete files. Bound marker lines explicitly, verify conflict counts and file tails, and inspect the final net diff against both original parents before committing; a clean whitespace check alone cannot detect lost content.

- 2026-10-15: Selected-full digest fixture R41/R42 produced different fixed-golden values from otherwise unchanged source. The fixture serialized `Map.of` payloads into strings inside a canonical binding; outer canonicalization preserves string contents and therefore does not normalize the inner map order. Restored explicit RFC 8785 normalization of each fixture payload before binding creation. Updating a golden from one failing run alone did not establish a stable vector; retain equality/mutation checks and verify the normalized vector before claiming deterministic proof. No production digest or retained bytes were changed. R44 and the fresh R45 JVM agree on the normalized vector; all 686 GDS unit cases pass in R45.

- 2026-10-15: Separate selected-publication owner proofs missed an Account settlement profile gap. The actual GDS terminal → authenticated World completion → both real terminal reads composition reached Account V94 and failed with SQLSTATE 23514 because its inventory validator still admitted only inventory/1 + World/3, while the current canonical producer retains closure inventory/2 + World/4. Preserve that failed composition beside prior component passes; a forward validator correction needs exact profile/refusal and existing-settlement byte/row-version upgrade proof. Do not rewrite installed migrations or treat controlled terminal readbacks as actual producer composition. Correction and qualification are in progress.

- 2026-10-15 follow-up: Account's forward V1011 now converges the exact inventory/1 + World/3 and closure inventory/2 + World/4 validation profiles while retaining the original framing, digest, source and artifact guards. R14 passes the actual six-store terminal-to-settlement composition and nine malformed-profile refusals; R16 passes original PUBLISHED and NO_PUBLICATION settlement upgrade/replay with unchanged stored bytes and row versions. A test-only V1010 predecessor allowlist omission was corrected separately. These controlled component proofs supply no genuine creator, issuer, environment or deployed authority.

- `2026-10-15`: Preserve receiver status when relocating inherited tracker proof
  - Context: the local #3224 parent reconciliation relocated inherited LOGIN proof into a dedicated section. Selecting the complete incoming tracker plus one receiver proof paragraph also displaced the receiver's first-party denial and historical-proof qualifications. The candidate had not been published.
  - Observation: verifying nonoverlapping blobs and paragraphs unique against the merge base missed receiver status that also appeared in the base. Main later compared the complete receiver document, restored every original receiver paragraph byte-for-byte, and retained the incoming concise status and relocated proof. No production source changed.
  - Expected pattern: inspect the complete overlap against the receiver's current contract and status, including text shared with the merge base. Proof relocation alone does not authorize replacing unrelated current-status sections with an earlier parent's view.
  - Reconsideration trigger: a native reconciliation check proves preservation across moved paragraphs and status replacements; ordinary clean merges and nonoverlap comparisons do not establish that property.

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

- `2026-10-16`: Qualify tenant mutation predicates before relying on filtered readback or later owner guards. The template repository's global-ID update could rewrite a retained row's tenant before returning it under the supplied tenant; a tenant-filtered readback did not protect the original row. Test controlled pre-phase rows as well as later guarded owners: the native counterexample changed the complete row and `xmin`, while the restored exact tenant predicate preserves both owner rows and their versions. Keep the create-only ID refusal before mapper/repository access, and separate this owner-local SQL proof from authenticated creator or hosted-content readiness.

- `2026-10-16`: Check existing successor repairs before reworking a failed fixture. While fixing the three #3251 publication transaction failures, two focused local gates failed before the worker noticed that the already-reconciled #2967 tree contained the complete selected-full schema-4 fixture repair. The first attempts corrected stale collector stubs but missed the dependent fixture profile. Compare the bounded failing test and its called helpers against known receiving successors before changing or rerunning it; reuse the complete canonical repair without removing assertions. The exact four-path backport at `4265a81f008d0dbd343cd9adf6413bd3ac5c8904` then passed66 unit and10 native cases. The failed attempts retain their original outcomes; this lesson does not establish a general infrastructure cause.

- `2026-10-16`: Derive focused Gradle selectors from declared Java packages
  - Context: a Gameplay capture proof supplied `integration.net...` as the selector, and Gradle executed no tests before failing discovery.
  - Observation: `integration` came from the categorized source root, while the class declared `net...`; a categorized source root is not necessarily part of the Java package, and a zero-test discovery failure establishes no test proof.
  - Expected pattern: read the source package and class declaration before constructing `--tests`, and verify fresh testcase counts before crediting proof. A categorized source root is not necessarily part of the Java package. Archive completed full-suite reports before a filtered rerun replaces the task output.
  - Recovery: the declared-package selector then passed all sixteen owning native cases; the zero-test attempt retains no proof credit.

- `2026-10-17`: Explain inbox read failures during SQLite writes
  - Context: Gameplay used the canonical `inbox read` command for an Overseer message at 11:01 UTC on 2026-10-16.
  - Observation: the command returned no message body after 9.95 seconds and reported only `SQLite inbox write failed`. The error did not identify the failed write, contention state or safe retry boundary; its underlying cause remains unknown.
  - Expected pattern: make message delivery and seen-state recording failures distinguishable, with enough operation context to choose a safe canonical retry without inspecting or mutating the database directly.
  - Recovery: one canonical retry returned the full message after 6.30 seconds. Gameplay reported the evidence to Overseer for the User-requested Controller improvements; no database or Controller change was made by this lane.
