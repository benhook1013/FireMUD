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

- `2026-10-08`: Carry integration fixture targets with forward-renumbered migrations
  - Context: PR #3105 preserved imported Account migration bytes under receiving V94–V97, but donor-era Flyway targets remained in three integration suites. Physical CI exposed 18 missing-relation failures despite successful local schema generation and compilation; local PostgreSQL cases had skipped.
  - Observation: Comparing migration filenames and bytes alone does not preserve executable proof. Runtime fixture targets and historical before/after migration boundaries must follow the receiving sequence independently. Current repositories may also require tables newer than a deliberately historical fixture.
  - Expected pattern: Compare explicit Flyway targets alongside every migration remap, preserving genuine historical-row setup and before/after assertions rather than merely advancing every fixture to latest. Inspect saved per-case HTML when uploaded XML or console exceptions omit the underlying SQL error.
  - Current status: Exact receiving target corrections are in preparation; corrected PostgreSQL execution remains unproved. The separate creator-membership provenance constraint mismatch is not attributed to fixture numbering.

- `2026-10-08`: Prove derived schema inputs when importing later migrations
  - Context: Account schema generation passed before the per-Pod readiness migration, but the new constraint exposed a missing projected column that was already present in the real V52 Flyway migration. The committed donor carried a narrowly scoped jOOQ projection that had initially been omitted as unnecessary.
  - Observation: Successful earlier generation did not prove the simulator had represented every migration. V52's ignore-marker spelling hid its later ALTER from jOOQ; the donor projection also split that compound ALTER for simulation.
  - Expected pattern: Preserve required build-input transformations with an imported capability, compare the generated input against its canonical migration sources, and distinguish simulator omissions from production schema gaps. Do not remove database constraints or add duplicate runtime columns to conceal projection drift.
  - Current status: The selective Account projection restores schema generation and changes only V52's derived input; Flyway sources remain unchanged. This proves code generation, not PostgreSQL migration execution or readiness activation.

- `2026-10-08`: Distinguish public code identifiers from secrets in controller updates
  - Context: A Document job update describing the public `AuthTokenInterceptor` class was rejected as resembling credential or raw secret material; the update contained no credential bytes.
  - Observation: Rephrasing the surrounding authentication description allowed the same non-secret milestone to be recorded. The exact filter condition is unconfirmed and has been reported to the controller owner.
  - Expected pattern: Secret detection should retain fail-closed protection while permitting public source identifiers and ordinary authentication diagnostics. Do not include secret material or disable the filter to publish a status update.
  - Current status: The milestone is recorded using neutral wording; filter correction remains unproved.
  - Outcome: The controller owner confirmed that the conservative guard rejects a value immediately following an authentication-scheme word, including inline code. Preserve the public identifier with wording such as “The global authentication handler is `AuthTokenInterceptor`”; no broader filter change is needed for this case.

- `2026-10-08`: Verify framework interceptor isolation through actual composition
  - Context: Readiness adapters selected an exact TLS peer interceptor and set Spring gRPC's `blendWithGlobalInterceptors=false`, but the composed service still invoked a global interceptor.
  - Observation: Installed Spring gRPC 1.0.3 uses that flag to control sorting, not exclusion. Its supported server-factory filter removes global interceptors before explicitly selected interceptors are appended; a null-factory test cannot prove that filtering path.
  - Expected pattern: Prove isolated and ordinary service composition with the production factory customization, preserving exact peer extraction and ordinary application authentication. Annotation assertions alone are insufficient.
  - Current status: The composition test exposed the defect before publication of the routing correction. A bounded service-local factory filter and positive/negative proof are being integrated; deployed composition remains unproved.

- `2026-10-08`: Separate global WAL-frontier denial from target COMMIT durability
  - Context: PR #3105's exact original-ack recovery test denied because its captured global insert frontier remained 64 bytes beyond the logged flush. Failure-only WAL diagnostics decoded both target COMMIT records, including full record lengths and aligned end boundaries; those boundaries were covered by the independently logged flush.
  - Observation: A global-frontier shortfall does not identify an unflushed target transaction. Conversely, record presence or a COMMIT start address alone does not establish durability; alignment, continuation pages, exact transaction identity and an independent flush observation matter. COMMIT record timestamps are not authenticated physical-flush deadline evidence.
  - Expected pattern: Preserve fail-closed acceptance while distinguishing an unavailable conservative proof from a demonstrated durability failure. A production exact-record recovery mechanism needs a supported locator, retained WAL availability, timeline/transaction-epoch checks and separate flush coverage; offline artifact decoding cannot silently replace that mechanism.
  - Current status: The production recovery path has no independent exact-COMMIT locator and remains denied. A bounded supported-mechanism proposal has been routed to Overseer; no database extension, privilege expansion, deadline relaxation or runtime activation is authorized by this diagnostic.

- `2026-10-08`: Attribute composed CI to the actual checked-out merge parents
  - Context: PR #3105 Validation run 37834941641 reported the expected child head and recorded base, but its checked-out synthetic merge's first parent differed from that base.
  - Observation: Exact child-head metadata and passing owner tests do not establish which composed parent was executed. The reason for this parent discrepancy remains unresolved; no branch or queue change was inferred from it.
  - Expected pattern: Retain the actual checkout commit, tree and ordered parents with physical test results. Credit executed cases to that composition and keep intended-parent alignment open until independently verified.

- `2026-10-08`: Distinguish dependent compilation heap exhaustion from owner test failure
  - Context: PR #3105 Game Session and TCP matrix jobs exhausted Gradle's 1.5 GiB Java heap while compiling dependent services, before owner integration tests ran.
  - Observation: These jobs supplied no owner execution proof and no demonstrated semantic compiler error. Configuration-cache diagnostics were separate from the heap failure.
  - Expected pattern: Preserve the complete validation tasks and artifact capture while applying a bounded resource profile, then require corrected-head CI. The local 3 GiB, nonparallel, two-worker profile is preparation, not proof that hosted compilation is repaired.

- `2026-10-08`: Verify protobuf credential redaction in the actual language runtime
  - Context: The new unpublished Account publication-order transport marked its credential field with protobuf `debug_redact`, but the Java generated message's text representation still exposed a synthetic credential in focused proof.
  - Observation: The schema annotation did not supply the assumed Java redaction guarantee. The redacted domain request was insufficient to protect the generated wire representation.
  - Expected pattern: Prove credential-safe representations in the actual generated runtime, keep credentials out of message text and diagnostics, and never log protected authorization metadata. Do not remove a failing safety assertion to claim redaction.
  - Current status: The focused gate caught the mismatch before publication. The credential is now absent from the protobuf request and travels in method-specific protected metadata; the corrected 316-case Common unit run passes, including redacted wrappers, protected-method emission and malformed-header denial. Physical transport execution remains required, and runtime registration stays denied.

- `2026-10-08`: Resolve synthetic-merge attribution against the current target ref
  - Context: The exact `2560d12a` observer compared PR metadata's base `1a8eefe` with the live target ref and synthetic merge first parent `fe15a31`.
  - Observation: The target had advanced 21 commits beyond the recorded base, accounting for 33 extra paths in the merge tree, with no Account, Game Design or World owner paths. This explains the observed tree difference without proving each job's checkout from metadata alone.
  - Expected pattern: Distinguish recorded-base metadata, current target ref, merge-object ancestry and actual job checkout. Parent movement does not authorize topology changes or confer exact-integrated-base proof.
  - Current status: The completed observer verified Account, World, Game Design, Game Session and TCP job logs actually checked out synthetic merge `e2bdaf5`, with parents `fe15a31` and published `2560d12a`; intended-parent alignment remains a separate gate.

- `2026-10-09`: Test strict committed reads through the actual Spring proxy
  - Context: PR #3105's physical World inventory read failed because a `NOT_SUPPORTED` transaction annotation created an empty synchronization scope that the method's strict no-synchronization guard correctly rejected.
  - Observation: Calling an unproxied repository directly did not reproduce the failure; a clean caller alone did not establish a clean invocation inside the proxy.
  - Expected pattern: Exercise the real transaction proxy when proving an outside-SQL owner read. Preserve rejection of both ambient transactions and empty synchronization scopes rather than weakening the guard to accommodate its own annotation.
  - Current status: The annotation was removed and a three-case proxy regression passed locally. Corrected-head PostgreSQL/transport execution remains required.

- `2026-10-09`: Check whether a schema-generation index failure exposes redundant DDL
  - Context: The unpublished V61 receipt table's composite unique index included a PostgreSQL `TEXT` workflow identity; jOOQ's H2-backed schema simulator mapped it to an unindexable CLOB and failed generation.
  - Observation: The table's existing primary key was a strict subset of that composite key, so the extra unique index enforced no additional uniqueness invariant. This was not evidence that PostgreSQL rejected the migration.
  - Expected pattern: Compare the intended invariant with existing keys before adding simulator-specific scaffolding or weakening payload bounds. Remove a genuinely redundant index when the stronger key already enforces the contract; retain separate physical PostgreSQL migration proof.
  - Current status: Removing the redundant index restored V61 schema generation. The subsequent integration-fixture and static-check corrections passed the complete Game Design gate R193; all three V61 PostgreSQL definitions compiled but skipped without Docker. Physical V61 proof remains unexecuted locally.

- `2026-10-09`: Distinguish subagent startup builder failures from model capacity
  - Context: After publishing PR #3105's source-read checkpoint, fresh Sol High/Medium and Luna XHigh implementation helpers failed at startup with `builder error`, including same-scope retries, before edits or validation. An earlier Luna investigation completed normally.
  - Observation: The tool supplied no capacity, thread-limit or model-service diagnostic, so neither model exhaustion nor successful slot recovery is established. Repeated replacement launches supplied no execution work.
  - Expected pattern: Preserve the published checkpoint, report the actual error and affected models, and continue dependency-safe lane integration where possible. Do not count failed launches as review evidence or silently treat a model substitution as a successful recovery.
  - Current status: Reported to Overseer through controller message `8684fed6`; the cause remains unknown. The parent continued the bounded approved projection. A later same-scope Luna XHigh retry began the Account transport assignment; this establishes partial recovery, not a diagnosed cause or recovery of every failed launch.

- `2026-10-09`: A launched validation is not a completed continuation checkpoint
  - Context: Document ended a heartbeat turn after launching consolidated gate R202 and recording an inbox decision, despite unfinished authorized implementation. The user had to prompt continuation; the gate subsequently exposed seven test failures.
  - Observation: Saying validation was underway did not consume its terminal result or advance the assigned correction. A process identifier is not a wake-capable handoff.
  - Expected pattern: Remain with local validation through terminal evidence, integrate failures and continue safe assigned work. If a genuine interruption requires yielding, preserve the exact pending process and next action without describing it as completed work.
  - Current status: The terminal result was consumed, disjoint Common and Account fixture/transport corrections were delegated, and the parent resumed integration. No proof or completion credit was assigned to the failed gate.

- `2026-10-09`: Cross-owner test dependencies can import another service's Flyway migrations
  - Context: Document added Game Logic's ordinary project dependency to Game Design integration tests for a genuine source-to-receipt composition. Local compilation and checks passed with Docker-dependent cases skipped; required CI then failed Spring fixture startup with duplicate V1 migrations from both service jars.
  - Observation: Successful compilation and skipped database tests did not establish a safe resolved resource classpath. Account already provided a resource-isolated opt-in proof artifact for this same cross-owner testing boundary.
  - Expected pattern: Consume an opt-in classes-only proof artifact when another service's owner classes are needed. Keep each owner migration directory explicit in composed fixtures; do not suppress Flyway checks or change production resource scanning to accommodate tests.
  - Current status: Game Logic's resource-isolated proof artifact and its Game Design consumer are prepared. Resolved artifact inspection and corrected-head physical PostgreSQL execution remain required; no production migration or activation guard changed.

- `2026-10-09`: Verify CI job conclusions before assigning failures from watcher annotations
  - Context: The initial observer summary for PR #3105 run 37901040926 attributed failure to Workflow Lint and World, but exact job and step conclusions showed both succeeded. The required gate failed because Account and Game Design Build/Test failed.
  - Observation: A workflow-level failure or diagnostic annotation does not establish which owner failed. Misattribution would route unnecessary shared-workflow or World repairs while leaving the real fixture and admission-WAL failures unresolved.
  - Expected pattern: Verify terminal job/step conclusions and originating owner artifacts before routing a failure; retain actual checkout provenance and distinguish missing artifacts from passed tests.
  - Current status: The parent corrected the report using the completed job evidence and XML: World 206 passing PostgreSQL cases, Game Design four fixture failures and Account one admission-WAL failure. The fixture corrections were validated locally; no physical rerun or WAL completion credit follows.

- `2026-10-10`: Newer metadata CI can obscure an active same-head substantive run
  - Context: A six-result branch query after PR #3105's body update showed only metadata-preservation runs. Their dependency-deferred failures were initially mistaken for the complete current-head CI state.
  - Observation: The separate substantive Validation run 37928625721 was still executing on the same head; metadata and required runs use distinct concurrency groups. The body edit had not cancelled it. A pending-proof metadata failure is neither a runtime failure nor evidence that no physical validation was dispatched.
  - Expected pattern: Before requesting replacement CI or routing a dispatch failure, inspect enough same-head runs to distinguish substantive execution from newer metadata preservation, then assign one observer to the actual execution transition.
  - Current status: Wider read-only inspection established the active run, and the parent corrected the Overseer request. A sole sentinel owns its outcome; no replacement run or workflow/topology change was made.

- `2026-10-10`: Validate required controller fields before replacing a standing brief
  - Context: Document read the absent JSON field `body` instead of the required `brief` field when appending an accepted disposition through guarded `jobs revise`.
  - Observation: The revision guard prevented concurrent replacement but did not prevent a semantically incomplete payload; the standing brief was briefly replaced by the addendum alone.
  - Expected pattern: Require a string-valued `brief`, preserve it without a missing-field fallback, and read back both the original mandate and the appended instruction after revision. A revision guard is not content validation.
  - Current status: Restored the complete revision-302 brief from controller history and appended the disposition in revision 304; readback confirmed 66,031 characters and the original mandate. Overseer was informed and preserved the correction in revision 305. No repository behavior or controller policy changed.
