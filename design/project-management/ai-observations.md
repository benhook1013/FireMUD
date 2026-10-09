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
