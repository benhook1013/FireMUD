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
  - Context: a hosted identity controller initially treated a ready Certificate revision and a separately read TLS Secret as one issuance snapshot during renewal.
  - Observation: cert-manager writes the Secret before advancing Certificate status, so independent reads can combine different revisions; Ready status and Secret metadata do not prove that the observed certificate bytes belong to the recorded revision.
  - Expected pattern: select the uniquely owned CertificateRequest for the ready revision, require its issued certificate bytes to match the Secret exactly, carry those validated bytes forward, and re-read the Certificate snapshot before acceptance. Treat absent or ambiguous issuance evidence as retryable and remember that `CertificateRequest.status.ca` is optional.
  - Current status: [PR #2853](https://github.com/benhook1013/FireMUD/pull/2853) owns the source-side TLS rotation and hosted identity proof work. Its current scope does not prove that live CertificateRequest bytes equal the TLS Secret, that the Ready Certificate snapshot is re-read afterward, or that the served identity uses the rotated certificate; these checks remain an activation gate.
  - Reconsideration trigger: revisit during an authorized activation or renewal observation that can compare the uniquely owned CertificateRequest bytes with the TLS Secret and re-read the Ready Certificate snapshot.

- `2026-09-20`: Workflow-code fixes need an environment-policy cutover for old branches
  - Context: preview deployment secrets were available to a branch-selectable workflow on a persistent self-hosted runner; new workflow code routes privileged jobs through the default branch.
  - Observation: an existing PR branch can retain its old workflow revision after the corrected default-branch workflow merges, and an unrestricted GitHub environment can still release secrets to that old revision.
  - Expected pattern: alongside the code change, restrict privileged GitHub environments to the trusted deployment branch and clear or rotate credentials exposed by old runner state; verify the live environment policies before declaring the trust boundary effective.
  - Current status: The live `trusted-hosted-cluster` Environment allows only `develop`; its environment-scoped secret-name list is empty, while repository secrets still include legacy `PREVIEW_KUBECONFIG`. The scoped credential bootstrap/finalization, token rotation, legacy binding removal, and old-identity RBAC denial proof remain unverified.
  - Reconsideration trigger: before the next privileged deployment or declaring cutover complete, an authorized operator should provision and verify the canonical scoped credentials, finalize removal and revocation of the legacy credential and binding, and prove the old identity cannot read Secrets, create CertificateRequests, or change an Issuer. Secret-name presence or absence alone is not rotation or RBAC proof.

- `2026-10-03`: Native process handles may be scoped to the launching agent
  - Context: Gameplay tried to transfer an already-running full CLI command's native process handle to a read-only sentinel.
  - Observation: the sentinel received `Unknown process id`, while the launching agent could still read the same live process. Shared filesystem access did not imply shared process-handle access; the review was neither restarted nor canceled.
  - Expected pattern: retain an existing native process wait in its launching agent. For delegated external waits, let the sentinel launch its own canonical read-only waiter for the exact durable trigger or CI run; never retry the evidence-producing operation merely because a handle is unavailable in another agent.
  - Current status: the launching Gameplay agent retained the live CLI wait. Whether cross-agent process handles are supported in other execution environments is unverified.
  - Reconsideration trigger: revisit if a future harness requires cross-platform handle portability or a canonical watcher fix is verified.

- `2026-10-05`: Optional CI reports need different cancellation and token handling from required gates
  - Context: the dependency-management PR exposed cancellation-resistant native summary jobs, stale Smoke comment writers, and a scanner pipeline whose final `tee` could mask scanner failure under the implicit Linux shell.
  - Expected pattern: use `!cancelled()` for optional reports, verify the live open PR head/base tuple before comment mutation, and omit native comment jobs where fork or Dependabot tokens cannot write. Keep required gate `always()` conditions and prerequisite evaluation independent. Use explicit `shell: bash` for pipelines that must propagate upstream failures.
  - Current status: focused workflow contracts exercise publisher guards and trust predicates, plus an offline fake scanner through the real step under explicit Actions Bash semantics. Static-analysis source matching distinguishes conventional workflow `name` from the exact identity-bearing `display_title`.
  - Proof limits: mocked API and shell fixtures prove local behavior; they do not establish live token permissions or hosted run completion. Reconsider when adding report publishers, changing event/concurrency policy, or wrapping failure-producing commands in pipelines.

- `2026-10-06`: Remote test infrastructure needs explicit authorization and retained ownership
  - Context: an isolated remote PostgreSQL fixture was used while local Docker was deliberately disabled.
  - Observation: the original setup command and authorization were not retained, and the setup was not announced before use. Existing host access does not establish permission to provision test infrastructure.
  - Expected pattern: obtain explicit infrastructure authorization before provisioning, record the exact run-owned resource and cleanup responsibility privately, and announce remote proof distinctly from local tests and required CI. Use a permitted existing fixture only when targeted iteration is materially faster; do not add a redundant pre-publication gate.
  - Current status: continued use of the existing bounded fixture is explicitly approved; additional provisioning is not. Original setup authorization remains unverified, and hosted CI remains required.
  - Reconsideration trigger: revisit when the original provisioning authorization is confirmed or the fixture is retired, and before any additional remote provisioning.

- `2026-10-07`: Redacted backup failures need trusted phase diagnostics
  - Context: seven public status-page publishing timeouts coincided with failed controller backup and retained-backup restore verification after a compatible runtime promotion.
  - Observation: healthy host/API snapshots and intermittent SSH pre-authentication stalls do not establish a common root cause. The canonical `BackupError` output hid the failing phase; a bounded read-only SFTP check succeeded for the root listing but timed out on the next directory metadata call.
  - Expected pattern: retain remote output and payload suppression while reporting trusted static phase, timeout versus nonzero exit, and secondary cleanup failure labels.
  - Current status: safe backup diagnostics are being added; fresh backup and isolated restore proof remain unavailable. Publishing and backup root causes are unresolved.
  - Reconsideration trigger: update the outcome after an authorized exact-phase failure capture and successful fresh backup/isolated restore verification; do not treat host health or a single successful SFTP listing as recovery proof.

- `2026-10-08`: Fail closed on incomplete Ubuntu indexes in runtime image builds
  - Context: repeated PR runtime-image build failures, including [PR #3074 run 37667066630](https://github.com/benhook1013/FireMUD/actions/runs/37667066630), timed out fetching Ubuntu archive indexes over HTTP before the `curl` install.
  - Observation: `apt-get update` can exit successfully after ignoring failed indexes, leaving package selection to fail later with incomplete dependency metadata.
  - Expected pattern: use the pinned Ubuntu base's canonical HTTPS deb822 sources, bounded `Acquire::Retries`, and `apt-get update --error-on=any` while retaining the required runtime package.
  - Current status: the Dockerfile source guard and offline APT-command fixture now cover source rewriting and fail-closed ordering; the exact image build remains for normal CI.
  - Reconsideration trigger: update after a normal image build proves a fresh signed-index fetch and successful curl installation.

- `2026-10-08`: Verify complete native CI inventories before reporting full-run counts
  - Context: The PR #3078 watcher initially counted the first 30 jobs as the full run; complete coverage showed 40 jobs, with 38 successful and two skipped. Required gates were green and the merge remained valid.
  - Observation: The run header also transiently reported queued while jobs progressed, then completed successfully; its cause remains unknown. Partial job pages and a transient header cannot establish a full-run count or terminal outcome.
  - Expected pattern: Fetch complete pagination, reconcile `total_count`, and verify unique job IDs before reporting full-run counts. Report required-job/gate proof separately from the terminal run header, without inferring the cause of inconsistent intermediate snapshots.

- `2026-10-09`: A shell workdir does not redirect patch-tool targets
  - Context: Gameplay's Account integration helper used relative patch headers despite its absolute-target instruction. The patch tool wrote 53 additions into the shared inspection checkout instead of the assigned worktree; 16 paths were inside directories already reported as untracked, without retained before-images.
  - Observation: For those 16 paths, the prior contents and whether they were overwritten remain unknown because no before-images were retained; matching replacement copies do not establish preservation.
  - Expected pattern: Verify the absolute worktree, branch, head, and relevant status before editing; use absolute paths in every patch header and verify the first write in the assigned checkout. A shell command's working directory does not establish a separate patch tool's destination. Preserve before-images when an assigned path already contains uncommitted work.
  - Current status: The helper corrected all owned targets. Only 37 confirmed new wrong-checkout paths were removed after verifying identical receiver and owned-target copies; the 16 uncertain paths remain untouched for independent recovery assessment. Matching the newly written bytes does not prove prior contents survived. Exact paths and evidence are retained in the controller inbox and active job, not another ledger.
  - Reconsideration trigger: Close the uncertain recovery only after a trusted before-image or the owning human establishes the previous content; do not infer preservation from hashes of replacement copies.

- `2026-10-09`: Credential screening can reject ordinary authentication prose
  - Context: A guarded Gameplay job-brief update was rejected as credential material even though it contained only implementation and proof status.
  - Observation: The strict screening rule interpreted the ordinary phrase `no-bearer dispatch` as a bearer credential. The prior brief remained intact; a checkpoint succeeded, and rephrasing the description as workload-only dispatch allowed the guarded update.
  - Expected pattern: Preserve the rejected update and prior brief, report the non-secret triggering phrase without raw credential material, and use field/category/line diagnostics to distinguish prose from accidental disclosure. Do not disable screening or bypass the controller.
  - Current status: The corrected brief is saved. Overseer reports merged #3116 now supplies the diagnostic location while preserving the strict detector; ordinary authentication prose can still trigger false positives.
  - Reconsideration trigger: revisit if an authorized guarded update shows that location diagnostics do not make an ordinary-prose false positive actionable; any detector refinement must preserve strict credential screening.

- `2026-10-09`: Foojay endpoint denial can look like missing JDK platforms
  - Context: dependency maintenance ran the canonical locked `updateDaemonJvm --jvm-version=21 --jvm-vendor=adoptium` generator with Gradle 9.8.1 and the Foojay resolver 1.0.0.
  - Observation: Gradle reported that no requested platform had matching download URLs during configuration-cache serialization; a direct official Foojay package query returned HTTP 403. The denial's cause is not established, and no fresh artifact IDs were generated.
  - Expected pattern: verify resolver endpoint access before interpreting this error as absent Java support or inventing platform mappings. Keep generated artifacts unchanged on failure and prove the generator through the read-only hosted PR job when local endpoint access is unavailable.
  - Current status: local generator proof is unavailable; the bounded maintenance workflow includes hosted generation proof without publishing credentials. Buf remote generation and compilation passed independently.
  - Reconsideration trigger: revisit after the hosted generator result or restored local Foojay access establishes whether the denial is environment-specific; remove the blocker only after actual generator output is verified.

- `2026-10-09`: Foojay failure follow-up distinguishes request denial from absent vendor metadata
  - Context: the read-only hosted Java 21/Adoptium generator failed with the same missing-platform message as the earlier local attempt.
  - Correction to the preceding observation: HTTP 403 depended on request headers and did not establish the generator's cause. Requests matching the resolver's actual endpoints succeeded, but Foojay's Temurin distribution metadata omitted Java 21 and its Java 21 package query returned no packages. The failure was therefore not established as WSL-specific network denial.
  - Expected pattern: inspect the resolver's actual distribution and package metadata before attributing configuration-cache failures to transport. A bounded official Adoptium metadata input to Gradle's native generator preserves generator ownership without inventing Foojay IDs or unsupported platform aliases.
  - Current status: the maintenance helper now validates one official Java 21 release across six supported platforms; successful generation and hosted exact-head proof must be recorded separately.
  - Reconsideration trigger: revisit resolver-only maintenance after official Foojay metadata again provides the required Temurin platforms; retain the metadata validation and generated-artifact proof.

- `2026-10-10`: Completed Hosted replay can conceal a late publication batch
  - Context: PR #3134's CodeRabbit publication reported 14 findings across two explicitly linked batches, but the controller imported 13 from the first batch and repeated sync reported success without reading the continuation.
  - Observation: a substantive primary review and an idempotent terminal record do not prove the provider publication is complete; freezing the first batch's timestamp also excludes later finding evidence.
  - Expected pattern: require complete immutable publication membership before completion, and recover verified older partial unfinalized runs additively without replacing dispositions or inventing another round. Retain original and supplemental evidence together.
  - Current status: the bounded controller repair passes focused local regression proof; trusted promotion and canonical live recovery remain separate steps, so the live missing finding is not yet confirmed recovered.

- `2026-10-11`: Concurrent CI watchers can exhaust shared GitHub API quota
  - Context: six native `gh run watch` processes in the Gameplay lane encountered the same REST rate limit while waiting for one candidate's checks.
  - Observation: separate low-cost-looking watchers share one credential's API budget; multiplying short-interval reads can block artifact inspection and other workers' CI evidence collection.
  - Expected pattern: use one quota-aware observation owner with bounded low-frequency consolidated reads across relevant exact heads, and back off until the reported reset. One helper launching six independent pollers is still prohibited fanout, not a single observer. Stop redundant local watchers without cancelling GitHub runs; continue safe local implementation and validation.
  - Current status: the six distinct workflow watchers are stopped and the correction is recorded in the SQL job brief. Exact artifact qualification and CI completion remain separate evidence, not inferred from historical green runs.

  - Reconsideration trigger: revisit after one quota-aware observer completes exact-head artifact qualification and CI without a rate-limit error, or the controller provides a shared consolidated watcher. Retain while either proof or the tooling improvement remains incomplete.

- `2026-10-12`: Retrospective evidence needs source-clock provenance
  - Context: a curated import of an independently completed review retained unknown review clocks but displayed generated recording timestamps as its start and finish.
  - Observation: a storage default can create false chronology even when the immutable evidence accurately discloses missing clocks.
  - Expected pattern: retain supplied-versus-generated provenance and project unknown source clocks explicitly; show recording events separately without rewriting raw evidence or inferring review times from later outcomes.

- `2026-10-12`: Pin the authoritative checkout before resuming a review lane
  - Context: Gameplay resumed from inherited workspace on `develop` at `1ea2c934`, which predated the actual merged review runtime `4fad59e`.
  - Observation: Reading the older workflow led Gameplay to treat the retired standalone review checker and prior review taper as current. The Overseer restored the current native controller and its one Hosted/three CLI normal taper, preserving explicit allocations. `--expect-pr` blocked the wrong selection before quota was used, so no extra Hosted request was posted.
  - Expected pattern: On a resumed lane, identify and pin the current authoritative merged/source checkout before reading workflow guidance. Preserve standing review allocations and do not trust stale startup documentation.

- `2026-10-12`: Make the next checkpoint action discoverable after CLI review
  - Context: CLI review completion produced captured evidence, but the next channel launch remained held until Gameplay posted and imported the established minimal public checkpoint.
  - Observation: Gameplay had to locate the exact checkpoint marker format in the current canonical implementation; the next publication command was not obvious from the returned help or output. Checkpoint publication then completed normally, so this is an improvement candidate rather than an established controller defect.
  - Expected pattern: Make the required next action discoverable while preserving the counts, head, file, duration line, attribution markers, and captured evidence. Do not issue a duplicate provider request.

- `2026-10-12`: Bound review admission history work to its selected dependency
  - Context: Gameplay requested the next Hosted review on #3171; FireController measured 55.2 seconds of preflight before posting the command and projected 170 history entries, including later unrelated queue PRs.
  - Observation: Repeated whole-queue history work adds latency to a front review request even when its exact head and parent are already selected. The request posted successfully; this is an efficiency candidate, not evidence of incorrect admission or a provider review failure.
  - Expected pattern: Reuse validated shared history where safe and bound fresh evidence to the selected target and dependencies, while preserving identity, reservation and quota checks. Overseer received the measured trace; no tool change has been attempted.

- `2026-10-12`: Review-ready channel stops can disagree with the final PR readiness projection
  - Context: On #3171 revision `60d495b36906ff72e12f57dda5a740333853110c`, native review status reported both channels `READY` with `CAP_AUDITED_STOP`, clear accepted findings/threads, fresh LOC, all required expected-app checks successful, and GitHub `MERGEABLE`/`CLEAN`.
  - Observation: The final verdict remained `NOT READY`; its only reasons were the affirmative channel-ready and cap-stop descriptions. This forced manual evidence adjudication at merge closeout. The precise cause is still under investigation by Overseer.
  - Expected pattern: Preserve mandatory findings, proof and CI holds while projecting exhausted human channel caps consistently into the final readiness result; a stop does not manufacture taper or review coverage.
  - Current status: Reported to Overseer with the exact final guard; the existing controller readiness work owns diagnosis and correction.

- `2026-10-12`: Retention follow-up for the Gameplay review-controller observations above
  - Context: This dated follow-up supplies current retention reasons and reconsideration triggers without rewriting the preceding append-only observations; bounded removal remains with Overseer stewardship.
  - Authoritative checkout observation: the immediate stale-checkout error is corrected. Stewardship disposition of the reusable startup lesson is deliberately deferred to the next observations checkpoint, because ordinary worker authority does not permit removing historical entries. Reconsideration trigger: the Overseer evaluates whether canonical startup guidance now covers the lesson and removes the entry if it no longer identifies a gap.
  - CLI checkpoint discoverability observation: checkpoint publication works, but help/output guidance remains an improvement candidate rather than a demonstrated correctness blocker. Tool changes are deliberately deferred to the owning controller workstream while Gameplay repairs the front PR. Reconsideration trigger: the owner selects a bounded help/output change or demonstrates an existing discoverable next-action response.
  - Admission-history latency observation: after the merged #3173 runtime promotion, the next measured request took 35.2 seconds with 86 history entries, improved from the recorded 55.2 seconds and 170 entries. Selected-target status still required substantial history work. Further optimization is deliberately deferred to the controller owner to preserve admission identity and quota checks. Reconsideration trigger: the owner evaluates these measured traces at its next controller performance checkpoint.
  - Readiness-projection observation: Gameplay has not independently verified final capped-readiness recovery on a new candidate; the reported #3171 inconsistency remains owned by Overseer controller work. Reconsideration trigger: an exact candidate with exhausted human caps, resolved findings, current required checks and a current native guard establishes the final projection, allowing stewardship to retain only any remaining discrepancy.

- `2026-10-12`: Group physical PostgreSQL proof after transient CI infrastructure failures
  - Context: Local full-check selected PostgreSQL cases but skipped them because Docker was unavailable. Recent CI also failed resolving Kotlin 2.4.10 POM dependencies from the Gradle Plugin Portal, although the redirected POM URL now returns HTTP 200, and one Redis image pull from ECR returned `toomanyrequests: Rate exceeded`.
  - Observation: Physical PostgreSQL proof remains pending; the CI logs establish these failures but not a common root cause or a global artifact outage.
  - Expected pattern: Group affected physical PostgreSQL proof with fresh-source CI and retry only the current failed job when transient evidence supports it. Do not claim missing global artifacts or count metadata-deferred callbacks as cases. Existing maintenance-board diagnostics own follow-up; no registry, cache, pipeline, or infrastructure rollout is established.
  - Reconsideration trigger: revisit after fresh-source CI completes the affected physical PostgreSQL suites, or another dependency/image-fetch failure changes the evidence. Retain while physical proof is pending or the maintenance owner still needs runner diagnostics; close the observation through authorized stewardship once both are resolved.

- `2026-10-12`: Hosted admission during a parent merge left CLI overlap unavailable
  - Context: #3122's final Hosted request posted on unchanged head `24ee463534999c79e89947d72da9bd98215d500f` while parent #3172 merged during admission. The controller retained the actual reviewed base and trigger [6121614014](https://github.com/benhook1013/FireMUD/pull/3122#issuecomment-6121614014).
  - Observation: POST succeeded but reported `SQLite Hosted terminal capture failed (HostedCaptureError)`. Subsequent CLI admission refused overlap because it could not attribute the active Hosted request to the exact published head. The child head and actual base were unchanged, and the native wait observed Hosted as active. Overseer subsequently traced the comparison to the configured queue parent instead of the unchanged actual PR base. Terminal Hosted sync recovered the attributable result; the comparison repair and verified overlap recovery remain pending.
  - Expected pattern: preserve the active review and its base, consume terminal evidence, and investigate native capture/overlap diagnostics through the controller maintenance owner. Do not post another trigger or bypass admission; an opaque capture warning should expose enough context for bounded recovery.
  - Reconsideration trigger: revisit when this exact Hosted trigger completes and native capture/CLI overlap recovery is verified, or when the controller maintenance owner establishes the capture cause. Retain while attribution or recovery diagnostics remain unresolved.

- `2026-10-12`: Retire the bounded external PostgreSQL test modes
  - Context: Follow-up to the `2026-10-06` remote test infrastructure authorization observation. The Overseer selected removal after the User permitted retiring the partial service-specific external modes.
  - Disposition: Account and Game Design test fixtures now retain only the disposable Testcontainers backend. External URL, credential, and tunnel-specific configuration hooks are removed; missing Docker leaves physical PostgreSQL proof unavailable.
  - Historical limits: Original provisioning authorization remains unverified. Removing the source hooks does not establish that the remote fixture or forwarding infrastructure has been deleted; resource ownership and any authorized cleanup remain separate responsibilities.
  - Reconsideration trigger: A future external test backend requires an explicitly supported isolation, lifecycle, credential, version, and proof contract plus any necessary infrastructure authorization.

- `2026-10-12`: Verify the live base branch after concurrent maintenance merges
  - Context: #3122 at `b71ff6875c3949266b269876707394be3c25f553` conflicted with the newly merged #3177. GitHub PR metadata still reported base `750626108b233fa96f9fa0177d682342b86a0171`, while a Git fetch showed develop at `5769a353bb222ae22428b5104f4037fb79a8a8bb`; the User noticed the conflict before the worker did.
  - Observation: A saved head/base tuple and prior proof did not establish current mergeability. The normal merge exposed one documentation conflict; the maintenance fixture changes merged cleanly.
  - Expected pattern: At publication and closeout, verify the actual base branch tip and current mergeability through the bounded canonical topology/status checks. Treat a stale PR base OID or missing new-head source CI as unresolved state, and reconcile before claiming readiness.
  - Reconsideration trigger: Verify the next published front against its actual remote base and current required CI; retain any remaining stale-base diagnostic gap for controller maintenance.

- `2026-10-13`: Public image registries can return transient anonymous manifest throttles
  - Context: A rebuilt runtime workflow failed after Gradle when an ECR Public manifest request returned HTTP 429 `DataLimitExceeded`; the earlier Docker Hub failure log's provenance is unknown. Fresh read-only checks found each selected Hub and ECR OCI index byte-identical and returned HTTP 200, without pulling images. GitHub-hosted runners are exempt from Docker Hub public-image pull limits, while ECR Public documents anonymous pull quotas.
  - Observation: An HTTP-successful manifest/index read does not establish that an image pull, build, or runtime proof will succeed. The available checks do not establish that the original Hub failure was disproved or identify why the later ECR request was throttled.
  - Expected pattern: Preserve the exact official image tags and digests, use the public Hub source on affected GitHub-hosted jobs, and report manifest, pull, build, and smoke evidence as separate boundaries. Do not add credentials, caches, mirrors, or alternate authorities from manifest-only evidence.
  - Current status: The shared test, Compose, preview, base-image, and upgrade references now use short official image names. All assigned local contracts passed, and the physical PostgreSQL 16-to-18 proof passed with the pinned Hub images: the historical source remained intact, the separate target was restored, and the Compose and Kubernetes dump clients produced seeded backups. Runtime base-image build and full-stack smoke proof remain unestablished; the affected hosted runtime job's ECR 429 still lacks subsequent exact-head CI evidence.
  - Reconsideration trigger: Revisit after exact-head hosted runtime-image and smoke execution, or if a later fetch failure changes the evidence.

- `2026-10-13`: Local PostgreSQL availability can fail after container startup
  - Context: Gameplay’s #3120 final-parent full gate at `42c524c` selected 105 PostgreSQL cases but 13 errors in three suites reported connection refusal on ephemeral localhost ports, after the preceding complete gate at `d0362dd` passed all 105 cases.
  - Observation: Docker daemon availability and a started-container event do not prove a fixture’s JDBC endpoint remains reachable; the failure cause and responsible lifecycle are unknown. These connection errors do not establish failed product assertions.
  - Expected pattern: classify connection-refused fixture failures separately from product assertions; capture live container events, PostgreSQL logs and endpoint state before fixture cleanup, and qualify the exact suites before crediting physical proof. Retained Docker events are bounded and destroyed containers lose inspect/log evidence; an exit code of 137 after an explicit cleanup kill does not establish OOM or external cleanup.
  - Current status: the three affected suites passed all 13 cases in the composed #3125 run and fresh exact-parent #3120 CI (all 105 PostgreSQL cases passed). The subsequent #3155 local Account gate also failed before assertions in 24 of 218 integration cases across eight suites, leaving PostgreSQL proof partial and Redis initialization unproved. The local connection-refused cause remains unknown; retain time-correlated container and endpoint evidence without weakening fixtures or changing the environment speculatively.
  - Reconsideration trigger: revisit after the affected cases execute physically against the final parent and the endpoint failure is explained or shown to be transient; retain until the cause and proof limits are recorded.

- `2026-10-13`: Local PostgreSQL readiness does not prove mapped endpoint availability
  - Context: During the otherwise-required Account correction gate on 2026-10-12 UTC, two participation repository cases refused the mapped `localhost:57828` endpoint before Flyway.
  - Observation: The bounded container/event/server capture tied that port to its PostgreSQL 18 container: final TCP readiness and Testcontainers startup confirmation preceded the case failures, while the recorded kill followed suite completion. This rules out a simple pre-readiness attempt for that capture but does not identify the refused-connection cause or cleanup initiator.
  - Expected pattern: Preserve correlated port, readiness, case and teardown evidence during required gates. Do not infer a source defect, early cleanup or OOM from a generic refusal, add speculative wait/configuration changes, or start a diagnostics-only rerun. Report passing subsets and missing full proof separately; matching current-candidate CI remains a qualification path when local endpoints are unavailable.

- `2026-10-13`: Controller route provenance selects its receiving command
  - Context: Gameplay opened the remaining #3155 owner-tuple obligation on #3183 through `decide route open`, then attempted the documented `records route decide` and `records route resolve` receiving operations.
  - Observation: The newly opened route retained `legacy_controller` provenance, so both native receiving commands rejected it. The canonical `decide route accepted-fixed` path successfully recorded verified successor proof; a separate native source resolution retained the actual child SHA and frozen review counts.
  - Expected pattern: Use the command family selected by the recorded route provenance. The README distinguishes route origins; controller help should also identify that choice before an operator attempts a mutation; opening a new route does not imply native source-record provenance.

- `2026-10-13`: A completed Hosted checkpoint can still expose a recapture warning
  - Context: The #3155 second Hosted request reported a predecessor terminal-capture warning after the earlier Hosted run had already been captured and finalized.
  - Observation: `HostedCaptureError` reported unmarked content after a finding comment's final `cr-comment:v1` marker. The warning retained a diagnostic and did not prevent the new request from being posted; the exact content or actor responsible remains unverified.
  - Expected pattern: Preserve the completed checkpoint and frozen counts, distinguish a recapture warning from a failed new provider request, and report the diagnostic to controller maintenance. Do not invent replacement findings or spend another review to repair historical capture metadata.

- `2026-10-13`: Certificate fixture validity can mask the intended negative assertion
  - Context: the #3156 local Game Design PostgreSQL gate failed the descriptor-readback mismatch fixture before reaching that boundary because its newly issued certificate raised `CertificateNotYetValidException`.
  - Observation: certificate readiness and live validation time disagreed transiently; the exact operating-system clock cause remains unproven. Successful certificate generation alone did not establish that the fixture was valid for the next consumer check.
  - Expected pattern: verify generated fixture certificates with the same actual validity check before exercising a later boundary, use a short monotonic readiness budget, and propagate expired or malformed certificates immediately. Preserve production certificate validation and the intended negative assertion.
  - Current status: the test-only readiness correction passed the 75-case local Game Design PostgreSQL suite, including the intended descriptor-readback negative case. No production validity check, certificate lifetime, or required proof has been relaxed.

- `2026-10-13`: Compact selected-PR JSON still includes large inventories
  - Context: Gameplay closeout used controller `reviews status --pr 3183 --compact --json` and the equivalent #3156 request.
  - Observation: their JSON captures were approximately 251 KB and 172 KB, including check inventories and historical transition details; printing the complete selected response consumed substantial context despite requesting compact output.
  - Expected pattern: a compact selected-PR projection should retain identity, review obligations, required-check outcomes, routes and readiness reasons, with detailed inventories available separately. Until then, capture JSON and print only the fields needed for the current decision.
  - Current status: reported to Overseer for controller improvement; no controller implementation change was made in this lane.

- `2026-10-13`: Follow-up to compact selected-PR output
  - Context: the 2026-10-13 compact selected-PR output entry requested a smaller routine status projection after printing large detailed captures.
  - Observation: that entry confused the roles of the existing summary and compact options; the detailed capture size did not establish a missing controller projection.
  - Outcome: Overseer identified the existing `reviews status --pr N --summary --json` projection, which retains the same safety result without ancestor rows or check inventories. `--compact` intentionally keeps the selected PR's detailed evidence; the earlier expectation conflated the two options.
  - Expected pattern: use `--summary --json` for routine identity and readiness; use full status, history, and routes for needed finding/provenance drilldown. No missing projection or controller implementation defect is established by the earlier large capture.

- `2026-10-13`: Workflow-run PR metadata is not frozen base provenance
  - Context: after #3183 merged and #3156 was retargeted, an observer initially treated the associated `pull_requests[].base.sha` on an older workflow run as its original event base.
  - Observation: that field reflected the new PR base, while the completed checkout log proved the run had merged the same head into the previous base. The initial exact-base claim was withdrawn.
  - Expected pattern: attribute execution to immutable event/proof metadata or the actual checkout/merge log; qualify unchanged-source reuse separately. A current associated PR tuple does not rewrite historical execution provenance.
  - Current status: the observer corrected its report; the front was reconciled and published on the merged develop parent for replacement CI. The post-retarget runtime/preview guards correctly refused stale test-merge parents.

- `2026-10-13`: Clarification of the summary status projection
  - Context: the compact-output follow-up describes the existing `--summary --json` option for routine PR identity and readiness.
  - Observation: the summary omits ancestor rows and detailed check inventories while retaining verdict and readiness fields, pending and failed check names, the observed-check count, optional-check counts, and inventory availability/status. Omitting detailed inventories does not mean omitting all check information.
  - Expected pattern: use the summary for routine identity and readiness, then read detailed status, history, or routes when check inventory or finding/provenance evidence is needed.

- `2026-10-13`: CI matrix failure discovery requires every jobs page
  - Context: the shared Gameplay observer monitored a large Validation matrix through `gh run view --json jobs`.
  - Observation: the returned jobs view omitted failed Buf and Game Logic rows. Their completion timestamps were 01:04:25 and 01:08:08 UTC, but the observer first discovered them around 01:16 UTC through the fully paginated Jobs API; the exact time the API exposed each failure was not recorded. This delayed front-PR failure reporting by roughly 12 and 8 minutes; no GitHub API or FireController failure was established.
  - Expected pattern: inspect the complete paginated source-run jobs inventory before metadata callbacks and report failed jobs while other jobs remain active. Do not wait for the whole workflow to finish or treat a partial jobs view as exhaustive.

- `2026-10-13`: Focused validation must use declared test packages and enabled task flags
  - Context: Gameplay’s #3120 review-fix prompts assumed that the `unit/` directory prefix was part of every Java package and omitted `-PfullCheck` from static-check tasks.
  - Observation: the wrong class filter executed no tests and failed explicitly; the static tasks were reported `SKIPPED` despite a successful focused unit build. Neither result qualified the missing proof.
  - Expected pattern: read the class’s declared package before specifying a test filter, and verify task execution rather than equating build success with every requested check passing. Enable the repository’s canonical full-check property when selecting static tasks.
  - Current status: corrected package selection passed 50 focused unit cases; the subsequent runtime fix passed 58 selected unit cases. A separate canonical `-PfullCheck` run executed the required static checks successfully. No validation requirement was waived.

- `2026-10-13`: Native Linux Docker proof can still invoke a Windows credential helper
  - Context: the claimed Gameplay fresh-bootstrap run rebuilt all service jars and images successfully, then failed before startup while pulling public Redis images. WSL reported `UtilAcceptVsock` and `accept4` timeout errors followed by a Docker credential lookup failure.
  - Observation: using the native Linux Docker socket does not select a separate client credential configuration. A run-scoped private client configuration can omit unavailable Windows credential helpers while preserving static registry settings and Compose plugin discovery; the global client configuration need not change.
  - Current status: the required proof using packaged service artifacts passes both transports with the isolated configuration and pinned public images. This client failure establishes neither an application regression nor a cause for the earlier Testcontainers endpoint refusals.

- `2026-10-14`: Qualify a receiving boundary from all current postimages and actual owner dependencies
  - Context: the Document receiving reconstruction initially inventoried only the published delta and untracked additions, omitting modified tracked source. Treating additive storage guards as passive also missed the fresh-v2 callers and ordinary asset source owner required by current positive publication tests.
  - Observation: the complete preserved delta had 84 paths, with a coherent 63-path Game Design boundary after inherited Account/process and separate Logging paths were accounted for. Current-owner proof required actual asset genesis, reviewed evidence and synchronized projection; decoding retained v1 history could not substitute for those callers. PostgreSQL's unnamed constraint identity also differed from jOOQ's H2 metadata emulation.
  - Expected pattern: inventory committed, modified tracked and untracked postimages together, then reconcile actual imports and owner invariants on the selected parent. Keep retained-byte proof separate from current positive owner proof. Use targeted jOOQ ignore markers only for procedural or constraint blocks that do not change generated columns, with independent PostgreSQL upgrade and current-owner proof. Tie each proof count to its recorded postimage and parent; the historical counts here are examples, and current execution totals belong solely in the [owning implementation tracker](implementation-tracking/game-authoring-publishing-and-activation.md#coherent-owner-receiving-prerequisite).
  - Current status: the 63-path Game Design receiver on the earlier preparation parent passed its canonical gate, including all 127 physical PostgreSQL cases without skips. This historical result does not establish genuine cross-service producer authority or packaged runtime proof. Current receiver totals and proof belong in the [owning implementation tracker](implementation-tracking/game-authoring-publishing-and-activation.md#coherent-owner-receiving-prerequisite).

- `2026-10-14`: Fresh database proof needs a new run identity
  - Context: a supplementary Gameplay fresh-bootstrap proof reused a previously completed run identity after canonical teardown.
  - Observation: the run retained immutable authored-world source receipts while its PostgreSQL volume was rebuilt, so Game Design correctly rejected the new database owner evidence as conflicting with the old receipt. Reusing the ownership capability did not make the two database histories one proof.
  - Expected pattern: allocate a new run ID and ownership token for a new fresh-database proof; preserve the prior receipt and use canonical run-owned teardown. Use the restart entrypoint when proving continuity of an existing deployment. Do not delete receipt evidence to make a reused fresh-bootstrap identity succeed or credit a setup failure as runtime proof.
  - Current status: the reused-identity invocation failed and was canonically torn down; a unique-identity invocation rebuilt current jars and images, passed both WebSocket and Telnet player-flow baselines, and completed canonical teardown.

- `2026-10-14`: Retain runtime ownership until teardown completes
  - Context: a Gameplay source-built Compose proof passed both player-flow baselines, but its launcher generated the invocation token only in an ephemeral shell and did not include teardown before exiting.
  - Observation: the wrapper left the owned project running, while its retained digest could no longer be matched with the lost token. Subsequent WSL memory and swap exhaustion coincided with local test timeouts; the contribution of other workloads was not established. An apparent WSL restart cleared swap and temporary logs but restarted the project's containers.
  - Expected pattern: retain the invocation capability privately before launching, keep proof logs outside temporary storage when they must survive a restart, and include successful canonical teardown in the owned run boundary. A lost capability does not authorize rewriting ownership markers or reclaiming unrelated resources.
  - Current status: Overseer verified bounded recovery and removed the completed project's containers and unused network, retaining volumes, backups and source. Normal serialized Gradle work resumed and the current Game Design checks passed; full local Compose remains paused while cleanup and whole-session ownership are corrected. Earlier successful transport proof remains attributed to its original source, and this recovery is not canonical full teardown or new-head packaged proof.

- `2026-10-14`: Optional coverage upload can consume the validation job budget
  - Context: the #3196 source Validation run completed its Game Design Gradle checks, runtime JAR and coverage artifact, then remained in the optional Codecov upload.
  - Observation: the upload consumed the matrix job's 20-minute budget, cancelling the job and making the required Validation Gate red despite passing source checks. Metadata callbacks used a separate concurrency group and did not cause this cancellation; the slow upload's underlying cause remains unknown.
  - Expected pattern: bound optional external uploads within the containing job's budget and preserve time for required proof artifacts. `continue-on-error` alone cannot protect against whole-job cancellation. Keep cancelled aggregate proof separate from successful individual checks.
  - Current status: the Codecov step is bounded to two minutes with its existing failure tolerance retained. New-head cloud validation remains required to verify the workflow outcome.

- `2026-10-14`: Wait for the database-owned expiry condition in lock-race proof
  - Context: a canonical Game Session pin test established its real PostgreSQL INSERT lock wait, then expected a finite owner lease to have expired after a fixed host sleep.
  - Observation: the database still reported the lease live after the sleep, so the intended post-expiry race was not established.
  - Expected pattern: retain the original finite lease and exact blocker, poll the same PostgreSQL expiry predicate used by the owner within a bounded deadline, then release the lock and assert refusal without mutation. Do not change the lease, renew authority, weaken the refusal assertion or infer clock skew from the failed sleep assumption.
  - Current status: the test now waits for the database-owned expiry condition; current physical qualification remains required.

- `2026-10-14`: Hold asynchronous recovery until failure-state assertions finish
  - Context: the TLS certificate watcher callback-failure test signalled its first attempt before the watcher recorded an unhealthy state. A successful retry could restore health before the test observed that state.
  - Expected pattern: gate the successful test callback until the existing unhealthy-state and aggregate-health assertions finish, release the gate in `finally`, then verify retry and recovered health. Preserve the production retry policy and every failure/recovery assertion.
  - Current status: the test-only latch correction passed the full 486-case Common unit suite and static checks. The separate PostgreSQL refusal and cleanup chronology remains unexplained under the existing local PostgreSQL availability observation; later passing proof does not identify that cause.

- 2026-10-14: Use explicit shell failure propagation for dependent proof and publication steps.
  - Observation: a multiline Bash execution continued to stage and commit after an auxiliary Python count assertion failed because the shell did not enable `errexit`. The actual gate passed 11 PostgreSQL cases; the failed assertion used a preliminary count of 10. The committed code remained qualified, but the command sequence did not enforce its intended dependency.
  - Guidance: use `set -euo pipefail` or separate sequential tool calls when a proof assertion must succeed before staging, committing or publishing. Read actual result counts before pinning them; an overall shell exit code can otherwise hide an earlier failure.
  - Current status: the [#3211](https://github.com/benhook1013/FireMUD/pull/3211) result was checked against the actual XML, corrected to 11 passing cases, and subsequent dependent commands use explicit failure propagation.

- `2026-10-14`: Review-period bookkeeping must not contend for provider execution locks
  - Context: the Overseer reported that Gameplay's three concurrent metadata reset batches encountered active-provider errors during the native rollout, with no actual provider active.
  - Observation: reset-versus-reset global lock contention was reported as provider activity, and network/full-stack refresh made local counter bookkeeping slow and coupled to unrelated source readiness.
  - Expected pattern: snapshot retained completed native results and append the channel period in one local SQLite transaction; preserve provider reservations and enforce external evidence only at ordinary review/status admission.
  - Current status: local transactional implementation is prepared; installed-selector promotion and live rollout verification remain pending with the owning orchestrator.
  - Reconsideration trigger: verify concurrent metadata batches after coordinated version-matched selector promotion.

- `2026-10-14`: Transfer terminal-owned work at a native command boundary
  - Context: a replacement subagent could not attach to the main agent’s existing execution session; the tools returned an unknown process ID even though the main agent could still observe its running batch.
  - Expected pattern: keep one execution owner, finish the current native command without cancellation, then resume only the missing stable operation keys from the canonical store in the new owner’s terminal. An execution-session ID does not transfer process ownership across agent contexts. Use the same boundary to drain incompatible database writers before coordinated selector promotion.
  - Evidence limits: completion counts and rollout timings remain in the owning controller job. This handoff does not replace the separate concurrent-reset reconsideration trigger above.

- `2026-10-14`: Verify user-visible rendering against installed controller input
  - Observation: Root deployed the numeric review-limit renderer before the matching controller fields were installed. Formatter tests passed, but interim live queue rows showed `Limits not reported`.
  - Expected pattern: pair rendering changes with the version-matched controller input and verify the actual endpoint before claiming the related user-facing outcome is complete. This is a reusable verification lesson, not an additional approval gate or lifecycle policy.
  - Recovery: coordinated controller promotion and actual-endpoint verification restored numeric limits; the owning controller job retains the rollout details.

- 2026-10-15: Administrative review settings share a dispatch boundary with provider-backed commands. When removing latency from one setting command, inspect the neighbouring stop/allocation/reset dispatch family and prove each actual entrypoint with Git, GitHub and subprocess access forbidden; method-only fixtures can preserve legacy behavior while missing expensive controller construction. Retain evidence-bound consumption as a separate operation rather than weakening request admission.

- `2026-10-14`: Native fix attribution needs the exact requested postimage
  - Context: Gameplay recorded a requested AA-1.3 tracker-row repair as fixed after its nearby narrative and migration proof passed, while the named row still lacked the migration anchors.
  - Observation: the next CLI review exposed the unchanged row. Documentation hygiene checks had passed because they do not verify that a requested semantic target changed. The original native fix record therefore overstated its proof.
  - Expected pattern: inspect the exact requested row or declaration before recording an accepted-fix resolution; verify its named anchors instead of relying on a nearby paragraph or a broad substring replacement.
  - Current status: the exact row is corrected and verified. An immutable audited resolution correction points to the published repair while retaining the original wrong attribution; the User and Overseer were informed.

- `2026-10-15`: Diagnose effective migration guards before proposing repairs. A World APPLIED diagnosis read V1012 without following V1014, which had already replaced the six-populated-family predicate with complete explicit counts and owner-proved zeros. Before declaring a migration defect, locate every later replacement of the function or trigger and assess the effective guard at the pinned receiving head, including related mapping/receipt constraints. Preserve immutable migration history and propose a new forward repair only for a demonstrated current gap; a historical definition alone is not live behavior.

- `2026-10-14`: Follow typed construction before accepting a missing validation finding.
  - Context: A CLI finding claimed World accepted non-STARTING fixture allocations.
  - Observation: Main initially accepted it after reading the codec and client but missed the shared result constructor's existing STARTING requirement; the attempted negative fixture failed before reaching World.
  - Recovery: The redundant production guard was removed, the decision was corrected in the immutable controller audit, and the public checkpoint correction preserves the original review credit. Focused wire-response rejection proof verifies the actual transitive boundary.
  - Expected pattern: Follow the complete typed construction path before adjudicating an absent check. A local consumer need not repeat an invariant already enforced by every constructible input; retained findings and changed code alone are not fix proof.

- `2026-10-15`: Check subclass lifecycle locks when relying on a shared client. The World terminal completion client called its shared watcher shutdown from a synchronized override, undoing the base class’s deliberate monitor release while draining callbacks. Integration inspection found the same mechanism already corrected in the canonical launch client. Keep only the local closed/initialized update under the client monitor and drain outside it; qualify the actual override with a queued-callback shutdown test. The queued-callback shutdown case passes in the fresh 546-case Common gate, including monitor release, exact peer binding, closed-client refusal and one shutdown per constructed channel. This source finding does not establish that an earlier runtime failed for that reason.

- `2026-10-15`: Verify executed physical cases after a successful categorized gate. Moving the unchanged GDS producer proof to its canonical cross-service suite yielded a successful Gradle result with all 20 selected cases skipped: the worker discovered Docker and started Ryuk, but repeated host-port connection refusals prevented reaper startup. Ordinary integrations in the same invocation ran successfully. Treat this as unavailable physical proof, inspect the suite report for the actual cause and retry focused proof without weakening guards or disabling cleanup. The runner-networking cause remains unknown. A separate combined four-owner compilation gate stopped on daemon garbage-collector thrashing before tests; owner-sequential gates preserve the standard resource policy and result attribution. Focused recovery is in progress. A mixed test filter also omitted the held-order suite because its package lacked the integration prefix; verify a fresh XML result for every required selector before claiming its count. The overstated peer report was corrected immediately, and the omitted suite is queued with its actual package. A plain retry reused the all-skipped task as up-to-date; explicitly rerun that test task and verify fresh executed XML before treating runner recovery as proof.

- `2026-10-15`: Physical-proof recovery outcome. The explicit categorized rerun executes all 20 relocated cross-service cases without skips, and the omitted held-order suite separately executes all three cases successfully. The original caller then passes all four composed Account/Game Design/World cases, with 136 fresh Game Design native cases and full affected checks. Earlier skipped, unavailable and failed runs retain their original attribution. Controlled upstream provenance remains a genuine-authority gap.

- `2026-10-14`: Selected-PR status can exhaust its deadline while reading historical targets
  - Observation: Gameplay's full `reviews status --pr 3206` failed at its 120-second deadline in `target_history_pr_3162_cli`, after 99 prior phases, so it produced no completed readiness result. The existing `--summary --json` projection subsequently succeeded; no safety guard was bypassed.
  - Consequence and unknowns: the timeout delayed current-front adjudication. The diagnostic identifies historical evidence work, but does not establish which lookup caused the overall latency or that the summary avoids that work.
  - Current status: the exact diagnostic was reported to Overseer for the controller owner's existing performance work. Prefer the documented summary projection for routine status and retain an explicit unavailable result when a required inspection times out; reconsider at the owner's next measured performance checkpoint.

- `2026-10-15`: Keep Gradle metadata refresh behind a confirmed resolution failure
  - Context: Validation's module matrix globally refreshed all dependency metadata to recover a cached negative Spring Boot 4.1.1 result. Later warm-cache builds still made remote Maven requests; retained CI logs show both a Maven Central rate limit and a separate Netty POM connection reset before tests.
  - Observation: a full metadata refresh on every affected module repeats repository checks and disables configuration-cache reuse even when the restored dependency cache is warm. A dependency-resolution cause can be separated from compiler or test failures when the nested repository error and execution stage are retained.
  - Expected pattern: run the unchanged full module check against its restored cache first; retry it at most once with metadata refresh only for a recognized fixed-coordinate repository-resolution failure before substantive compilation or test execution. Preserve both outputs and return the final check's status; do not retry arbitrary configuration, compiler, test, interruption, or capture failures.
  - Evidence limits: the exact historical Spring Boot cache entry is not directly inspectable from the retained run note, so the implementation preserves that recovery path without claiming every missing artifact is a stale cache entry. Reconsider if fresh exact-head evidence shows the guarded retry still causes broad request pressure or misses a distinct failure class.

- `2026-10-15`: A failed SpotBugs task may have no enabled diagnostic report. The Document preparation gate returned only exit code 1 with no finding or report; explicitly enabling XML through a temporary init script exposed one unused fixture local. Scope that temporary script through `findProject` because Gradle also applies initialization scripts to `buildSrc`. The ordinary corrected World gate then passed. Enabling a useful report in the existing build task configuration is a tooling opportunity; no canonical build configuration was changed in this lane.
- `2026-10-15`: Raise the shared local Gradle heap after repeated GC thrashing
  - Context: Document R57/R65 and Gameplay #2967 failed with a 1.4 GiB maximum heap amid repeated garbage-collection thrashing. The receiving task passed with a per-run 3 GiB heap in 1m27s, while WSL was configured with a 10 GB limit and had 5.2–6.8 GiB available without swap-out or OOM evidence.
  - Observation: the repeated failures support a higher shared local daemon heap for serialized work; the passing run was mostly up-to-date and does not establish cold-build speedup or a total-memory guarantee.
  - Qualification: the 3 GiB setting is not a restoration of an earlier default; the shared 1500m setting predates the recent guard since August 28. Worker, test, and code-generation JVM memory remains additional, and the hosted module runner's explicit 2500m setting is unchanged.

- `2026-10-15`: Classes-only foreign-owner proof artifacts intentionally omit application and Flyway resources. A composed test that reads another owner’s migrations from disk must declare those directories as task inputs; otherwise a SQL-only change can leave the composition up-to-date. The selected Game Logic proof exposed this after its forward migration was reconciled, and Game Design now tracks the foreign Game Logic and World migration directories.

- `2026-10-15`: Allocate forward migrations against the actual resolved Flyway history, including shared Saga resources and later service-owned migrations. The selected-owner intake plan's older Account V161 and Automation V5 sketch would sort below already installed combined history. The receiving Account parent already reaches V1005, while Saga reaches V1002. Preserve existing SQL and prove a retained upgrade from the actual combined parent instead of enabling out-of-order execution or inferring a frontier from the service's older application lineage.

- `2026-10-16`: The canonical local resource lock already serializes eligible heavy runs across worktrees. Ordinary workers can submit through supported bounded admission and retry a busy result without peer permission or an idle reservation between scheduled wake-ups. A Document source gate automatically acquired the lock before its delayed peer completion message arrived; busy admission remains unrun proof.

- `2026-10-16`: A multiline conflict-marker resolver truncated three trailing file portions during an unpublished selected-owner carry. Native `git merge-file` reconstruction from the exact parents recovered the complete files. Bound marker lines explicitly, verify conflict counts and file tails, and inspect the final net diff against both original parents before committing; a clean whitespace check alone cannot detect lost content.

- 2026-10-15: Selected-full digest fixture R41/R42 produced different fixed-golden values from otherwise unchanged source. The fixture serialized `Map.of` payloads into strings inside a canonical binding; outer canonicalization preserves string contents and therefore does not normalize the inner map order. Restored explicit RFC 8785 normalization of each fixture payload before binding creation. Updating a golden from one failing run alone did not establish a stable vector; retain equality/mutation checks and verify the normalized vector before claiming deterministic proof. No production digest or retained bytes were changed. R44 and the fresh R45 JVM agree on the normalized vector; all 686 GDS unit cases pass in R45.

- 2026-10-15: Separate selected-publication owner proofs missed an Account settlement profile gap. The actual GDS terminal → authenticated World completion → both real terminal reads composition reached Account V94 and failed with SQLSTATE 23514 because its inventory validator still admitted only inventory/1 + World/3, while the current canonical producer retains closure inventory/2 + World/4. Preserve that failed composition beside prior component passes; a forward validator correction needs exact profile/refusal and existing-settlement byte/row-version upgrade proof. Do not rewrite installed migrations or treat controlled terminal readbacks as actual producer composition. Correction and qualification are in progress.
