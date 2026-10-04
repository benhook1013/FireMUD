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

- `2026-09-20`: PostgreSQL migration preflights must also pass jOOQ's DDL parser
  - Context: an Automation Flyway migration used a PostgreSQL `DO` block to report duplicate active-readiness rows before adding a partial unique index.
  - Observation: the service's jOOQ DDLDatabase generation parses migration SQL without PostgreSQL execution and rejects procedural `IF` in a `DO` block as unsupported in the available jOOQ edition; normal PostgreSQL validity alone did not make the build pass.
  - Expected pattern: choose a declarative fail-closed constraint/index when it expresses the invariant, run the owning service's `generateJooq` after adding a migration, and retain PostgreSQL migration proof for existing-data failures separately.

- `2026-09-21`: GitHub workflow run `name` can contain the configured run title
  - Context: a static-analysis summary initially compared a `workflow_run` payload's `name` with the bare source workflow name while its source configured `run-name` with PR and SHA identity.
  - Observation: live Actions API runs exposed the complete configured run title in both `name` and `display_title`, so that comparison would silently skip valid source runs.
  - Expected pattern: identify the source workflow by its exact `path`, then validate `name` and `display_title` against the expected full run title and current PR identity; include a fixture with the observed payload shape.

- `2026-09-21`: A base push does not refresh a pull-request merge-image run
  - Context: an open PR's head stayed fixed while `develop` advanced; its planned preview merge ref changed during rendering, and the exact-parent guard rejected the stale plan.
  - Observation: `pull_request.synchronize` tracks head updates, so a base-only advance does not create a new PR image run or preview request even though the synthetic merge SHA changes. The PR event and API `base.sha` can also lag the live branch ref: one observed run built a merge with the new base parent while its title still named the old base.
  - Expected pattern: verify the current base branch ref and ordered merge parents, then have trusted base-branch orchestration dispatch a fresh credential-free build and preview reconciliation for that exact tuple. Consumers must reject old tags until the matching run succeeds, without requiring an empty PR-head commit.

- `2026-09-24`: Metadata-only PR workflows must retain required gate names
  - Context: two PR title/body edits produced successful metadata jobs while the last substantive Validation, Security, and Smoke gates had passed on the unchanged head. GitHub's merge box nevertheless showed those three required statuses as expected, while CodeQL and License remained satisfied.
  - Observation: renaming a metadata-only gate job to `PR Metadata Edit (...)` leaves its required context absent from the new workflow run; an earlier passing check shown by `gh pr checks --required` did not establish merge readiness. The branch's separate base advance made the UI more confusing but strict up-to-date checks were disabled.
  - Expected pattern: keep each required gate's job name stable on metadata edits and succeed only when the shared preservation action verifies prior substantive success for the same PR/base/head identity. Confirm the merge box or merge state after metadata updates; do not infer that earlier green check links prove GitHub is ready to merge.

- `2026-09-24`: Metadata preservation can start before a substantive gate exists
  - Context: on PR #2844, a metadata Validation Gate began at 04:06 UTC while the matching substantive gate appeared only at 04:18 UTC after its dependency graph and runner queue; the preservation action reached attempt 47 before it could observe that gate.
  - Observation: the old 23-minute polling budget could fail a metadata gate even while the matching substantive workflow was active. The Actions workflow-run API returned an empty `pull_requests` association for that real run, so active-run diagnostics cannot require a populated association; the canonical run title and non-skipped change-detection job provide the matching evidence.
  - Expected pattern: preserve separate metadata concurrency, extend only a bounded wait for a verified same-PR/base/head substantive run or gate, and never treat absent, pending, failed, or ambiguous gate proof as success.

- `2026-09-26`: Necessary procedural migration preflights may use jOOQ ignore markers
  - Context: Account V25's duplicate-detection preflight requires a PostgreSQL `DO` block and wraps it in `-- [jooq ignore start]` and `-- [jooq ignore stop]` markers.
  - Observation: this qualifies the 2026-09-20 expected pattern: declarative constraints remain preferred when sufficient, but a necessary procedural preflight can be excluded from jOOQ parsing while retaining separate PostgreSQL migration proof.
  - Expected pattern: prefer declarative constraints when sufficient; when procedural preflight is necessary, wrap it in the jOOQ ignore markers, run `generateJooq`, and run separate PostgreSQL migration proof.

- `2026-09-28`: Review-history projections must preserve explicit routed counts
  - Context: the #2828 delivery page reverted to two-number review pills even though recent public checkpoints recorded `found / accepted / routed`.
  - Observation: the controller parsed `routed` from those checkpoints but omitted it from completed review history, so status returned an unknown third count. The page's separate evidence fallback also failed when an unrelated queued PR had unavailable identity, leaving the omission silent.
  - Expected pattern: carry explicit checkpoint fields through the status projection, test modern and legacy checkpoint shapes together, and verify the rendered latest-five results against public checkpoints. A failure to enrich one PR must not silently turn its known three-count results into legacy two-count results.

- `2026-09-28`: Manual Hosted requests need explicit attribution before checkpoint credit
  - Context: a human-posted full-review command on #2879 produced a completed CodeRabbit review and a public checkpoint, but the controller had no private trigger record and omitted the round from counted history.
  - Observation: the public command and review IDs uniquely identified the completed round; a checkpoint alone did not establish the controller's request-time attribution.
  - Expected pattern: when an external full-review command has already completed, verify and adopt its public trigger/result through the guarded controller command before relying on its checkpoint. Never post a duplicate request or infer a trigger from the checkpoint alone.

- `2026-09-29`: A failed CLI attempt is not a missing completed review
  - Context: #2879 had a long interval between CLI checkpoints while its private captures included a nine-second rate-limited attempt.
  - Observation: seven successful captures matched seven public CLI checkpoints; the failed attempt had an exit status and a provider limit but no review result. Completed-result pills alone did not explain the gap.
  - Expected pattern: keep failed attempts separate from review and taper counts, expose only coarse non-counting status from private captures, and compare successful captures with public checkpoints before claiming a result was lost.

- `2026-09-29`: Review-record growth must not silently stop SQLite recovery copies
  - Context: the hourly review-state backup failed after new structured findings introduced long, hyphenated identifiers containing words such as `bearer` and `key`.
  - Observation: the backup's generic secret heuristic rejected typed identifiers that the records writer had already accepted. The job exposed only a generic `BackupError`, and its last successful off-machine copy became stale while the live database continued changing.
  - Expected pattern: exercise backup and exact restore against representative live record shapes after importer or schema changes, keep credential screening specific to the field's meaning, and monitor the age of the last successful remote readback rather than the timer's enabled state alone.

- `2026-09-29`: Hosted sentinel identity checks must tolerate GitHub bot suffixes
  - Context: a Hosted sentinel missed an attributable result because it matched exact `coderabbitai` while GitHub returned `coderabbitai[bot]`.
  - Observation: hand-coded exact login filters can miss provider results that the controller can attribute correctly.
  - Expected pattern: use canonical controller attribution and readback, or match bot identity robustly; do not narrate unchanged waits.

- `2026-09-30`: Review-stop locks must match the affected PR and channel
  - Context: a Hosted stop on #2893 was refused while an unrelated CLI review ran on #2829.
  - Observation: the stop path acquired the repository-wide CLI runner lock even for a Hosted decision on another PR, delaying the Hosted train without protecting the affected request.
  - Expected pattern: synchronize a stop with the affected channel's request and durable state update; test that an unrelated active review cannot block it while a genuinely concurrent request on the same PR and channel remains protected.

- `2026-09-30`: Review-record upgrades must prove every selected controller entrypoint
  - Context: #2893's records migration raised SQLite's minimum writer build, leaving the original cutover marker at its earlier build; the state selector refused the valid database, and the backup service still used a separate older checkout.
  - Observation: the cutover marker records the original selection, while SQLite metadata owns the current writer floor. Requiring those values to stay equal and checking only the interactive entrypoint missed both failures.
  - Expected pattern: keep the marker's writer floor no greater than SQLite's, let SQLite reject old writers, and prove a schema upgrade through the shared controller, status site, and scheduled backup with exact readback before calling the promotion complete.
  - Outcome: the cutover-marker compatibility repair is implemented and retained in #2909 with focused SQLite readback proof. This entry does not establish whether the scheduled backup checkout was subsequently updated; its compatibility remains unverified here rather than being claimed resolved.
  - Reconsideration trigger: verify the scheduled backup's selected writer and exact database readback before the next schema promotion, or immediately if a backup reports a compatibility failure.

- `2026-09-30`: Completed-gate step ambiguity remains separate from substantive CI proof
  - Context: #2909's metadata-only Security Gate exhausted eight preservation-step snapshot refreshes and failed with ambiguous prior-run metadata, although the same-head substantive Security Gate passed. Later readback showed the substantive preservation step skipped and a separate metadata preservation run successful.
  - Observation: the failing preservation snapshot and later completed-step API readback disagreed; the precise cause remains unproven. A red preservation gate is not evidence that the underlying product or security checks failed, but it remains a required-check obligation.
  - Expected pattern: inspect the exact run, job, step, base, and head before choosing a bounded rerun; retain fail-closed preservation and do not substitute historical success for current-head merge readiness.
  - Reconsideration trigger: retain this note while the cause or current required-check state remains unresolved; revisit under authorized observation stewardship once exact run/job/step readback resolves both.

- `2026-09-30`: Waiter handoffs must separate terminal outcomes from later eligibility
  - Context: a #2909 Hosted sentinel withheld an already-terminal rate-limit response until its reset time, delaying publication of prepared fixes by about fifteen minutes. A later CLI sentinel could not access the parent's native tool session and correctly reported `Unknown process id` instead of claiming a review was being observed.
  - Observation: provider completion and permission to retry are separate events; native tool-session handles are caller-scoped in this harness and cannot be assumed transferable to a subagent.
  - Expected pattern: deliver terminal response identity, exit status, and any authoritative reset immediately, before a separate cooldown wait. Keep a native process with its owning caller, or bind a read-only sentinel to the verified OS process and exact capture; never launch another review merely to recover observation.
  - Outcome: terminal reporting was corrected to surface completion before later eligibility; caller-scoped native process handles remain unavailable to a separate sentinel.
  - Reconsideration trigger: revisit if terminal outcomes are delayed again or during authorized observation stewardship of cross-caller process ownership; do not relaunch a review just to recover a handle.

- `2026-09-30`: Completed helpers need an explicit continuation dispatch
  - Context: after #2909's controller helper returned, integration corrections were sent as messages and the parent waited as though the fix was running.
  - Observation: a message to a completed agent queues context but does not start a new turn; this was an orchestration error, not a model-capacity or review-provider failure.
  - Expected pattern: use the bounded follow-up task operation for a continuation and verify that it started before awaiting its result. Preserve completed work and never restart a provider review to compensate.
  - Outcome: the existing helper was explicitly resumed with the scoped correction; no active review or useful implementation was interrupted.
  - Reconsideration trigger: revisit if follow-up dispatch behavior changes or this failure recurs; otherwise retain the resolved orchestration guidance.

- `2026-09-30`: Separate request preparation latency from provider review duration
  - Context: the canonical Hosted request for #2909 at `e39956a56` spent about five minutes in its operator process before returning the posted trigger `5919007416`; its eventual provider response was separately rate-limited.
  - Observation: recorded provider durations exclude preparation, so they cannot explain the whole cadence. The exact slow command phase is not established; neither a silent process nor elapsed time proves a deadlock or an active provider review.
  - Expected pattern: measure preparation phases before optimizing and retain independent live head, ancestry, request, and cooldown safety checks. Do not replace live cooldown verification with an unsupported age cutoff or a stored terminal label.
  - Outcome: the canonical request posted without bypassing safety; the authoritative retry deadline remains owned by one silent timer sentinel. No speculative performance rewrite was added.
  - Reconsideration trigger: use a bounded timing investigation when preparation repeatedly threatens eligible review windows or an authorized controller performance pass is undertaken.

- `2026-09-30`: Exercise the live evidence adapter in channel-overlap regressions
  - Context: #2909's eligible Hosted retry was refused as accepted findings pending while a correctly pinned CLI provider was running on the same published head; the live status had zero threads and no Hosted finding obligations.
  - Observation: the runtime audit flattened the active CLI observation into a generic unresolved-finding string before the controller's existing exact-identity overlap check could run. A controller regression with a mocked empty audit did not exercise that adapter interaction.
  - Expected pattern: preserve structured run/lock-owner/head/parent/patch evidence through the audit and test the real adapter-to-allocation path. Permit only positively verified same-candidate overlap; genuine pending findings and incomplete identities remain held.
  - Outcome: the bounded controller/runtime correction passes real-adapter overlap and refusal regressions; live request verification follows publication. The running CLI was not interrupted, and no duplicate Hosted request or private-state bypass was used.

- `2026-10-01`: Preserve native observation when a sentinel slot is unavailable
  - Context: a read-only Hosted sentinel launch for #2839 was refused with `agent thread limit reached`; the visible agents contained useful active work and completed helpers, but no clearly obsolete pending or interrupted thread to recover.
  - Observation: this is structural harness exhaustion, not evidence of model quota exhaustion or a failed provider request. The exact slot-accounting cause remains unknown.
  - Expected pattern: preserve useful workers and the posted trigger, use the canonical exact-trigger native waiter when available, and do not change models, interrupt useful work, duplicate the review, or repeatedly probe for a slot.
  - Outcome: the native waiter promptly identified the attributable rate limit and its exact reset; no duplicate review was posted.
  - Reconsideration trigger: investigate slot accounting only if structural exhaustion repeatedly prevents wake-capable observation and no native wait can preserve the transition.

- `2026-10-01`: Isolate implicit repository record discovery in live-adapter tests
  - Context: PR #2916's SQL-first CLI discovery made two adapter tests without private context resolve the workspace's actual review-record database; their read-only queries reported its older schema instead of exercising their synthetic Hosted fixtures.
  - Observation: mocking GitHub calls and capture files does not isolate the repository-scoped SQLite discovery path. No live database write or promotion occurred.
  - Expected pattern: give adapter tests an isolated temporary Git common directory by default, overriding it only with an explicit fixture context; keep production schema checks fail closed.
  - Outcome: the runtime test fixture now isolates implicit capture/record resolution, and the 115-test runtime suite passes without relying on live state.

- `2026-10-01`: An ownership clarification is not a lane-wide stop
  - Context: the human transferred the #2916 controller child to Overseer for review/fix cycles while Gameplay still owned #2839. Gameplay ended its turn after acknowledging the transfer, requiring an explicit resume.
  - Observation: the child-only ownership boundary was incorrectly treated as a stopping point for the continuing parent assignment.
  - Expected pattern: stop touching the transferred child, but continue the owned parent and independent review lanes. A coordination acknowledgement or status answer must not terminate the standing train without an explicit pause or actual blocker.
  - Outcome: Gameplay resumed #2839, preserved the child untouched, adjudicated its complete saved CLI result, and dispatched the accepted parent-only corrections.

- `2026-10-01`: Distinguish request admission contention from an active provider review
  - Context: several closely launched #2839 Hosted/CLI commands produced a delayed Hosted refusal saying another Hosted request was active, without a new posted trigger; a guarded later request posted normally.
  - Observation: the exact contention phase and cause remain unproven. That refusal alone does not establish that CodeRabbit is reviewing, and preparation delay consumes eligible Hosted windows independently of provider duration.
  - Expected pattern: retain the canonical request handle and verify its actual posted or refused outcome. Investigate the narrow admission/lock boundary without bypassing duplicate-request or exact-identity safeguards; keep the other safe lane moving.
  - Reconsideration trigger: investigate when the controller owner can reproduce the contention or another eligible Hosted window is lost; do not silently label an unposted request as an active review.

- `2026-10-01`: Do not infer CLI exclusion from unpublished fixes
  - Context: #2839's local corrections were committed at 06:20:21 UTC and Hosted finished at 06:25:38 UTC; Gameplay made no CLI attempt during that 5m 17s interval, incorrectly assuming the unpublished corrections prevented overlap.
  - Observation: the live runner permits CLI on the exact published Hosted candidate, excluding local descendant changes. An unattempted safety assumption is not a demonstrated controller hold.
  - Outcome: subsequent CLI requests ran independently. A later Hosted retry encountered a closed-#2750 reservation verification error before POST; its current record and attributable terminal review were valid on one read-only reproduction, and a canonical retry posted successfully. The original exception remains unknown because the wrapper hides its phase/cause; no record was edited or safety check bypassed.
  - Expected pattern: use the canonical eligible channel, retain actual refusal evidence, and keep the other lane moving. Preserve safe phase/cause diagnostics for future reservation-verification failures rather than treating an unposted request as provider activity.

- `2026-10-01`: Closed-PR history must not retain a Hosted execution slot
  - Context: the repository-wide Hosted admission sweep reread the complete review history of merged #2750; an exception became an admission hold even though the live open-PR inventory had already excluded it.
  - Outcome: the repair is published in #2916 and installed as a separately tested writer-build-5 backport. Confirmed closed PRs no longer require that historical network read. An exact, attributable, locally archived rate-limit response still holds the repository until its proven reset time, including a response whose terminal edit occurred after creation. No historical record, count, or taper decision was removed.
  - Proof: 118 focused runtime tests, 883 tests against the compatible live-controller baseline, and 920 tests on the owning child passed. The original transient exception's underlying network cause remains unknown; the reproduced failure mode is now covered without weakening open-PR request safety or promoting an unmerged record schema.
  - Observation: the Hosted admission inventory must distinguish active candidates from historical closed PRs before any history-dependent slot decision.
  - Expected pattern: exclude confirmed closed PRs before reserving a Hosted execution slot; retain only exact, attributable rate-limit evidence as a repository-wide hold through its verified reset time.

- `2026-10-01`: Keep validation-script inputs stable during execution
  - Context: an architecture-contract run passed its test cases but then read a truncated final command after the same shell script was edited while its process was still running. A complete rerun against the finished script passed.
  - Observation: a shell process may continue reading its script after earlier commands finish; editing that script during execution can invalidate the run independently of the corrected contract.
  - Expected pattern: finish script edits before starting its validation, or discard the affected run as proof and rerun the complete named check after edits settle. Do not diagnose the resulting partial command as a missing repository tool without checking the input race.

- `2026-10-02`: Isolate embedded HTTP test configuration from MVC slice discovery
  - Context: the focused removed-route HTTP test passed, but the combined Account suite later produced 40 unexpected 404 failures. Test output showed unrelated MVC slices discovering that test's nested `@SpringBootConfiguration` instead of the real Account application.
  - Observation: an explicitly selected embedded test application can still contaminate neighboring Spring test discovery when it is globally discoverable. Its isolated passing result does not prove test-suite coexistence.
  - Expected pattern: use isolated test configuration with explicit application selection, preserve the real HTTP route proof, and run the affected service suite when introducing an embedded application alongside MVC slices.
  - Outcome: an explicit context using `@Configuration` and `@TestComponent` isolates the embedded application; all 284 source-branch Account tests passed with zero skips, failures, or errors. `@TestConfiguration` alone loaded the real application's unrelated gRPC clients and was not sufficient here. The earlier failed combined run remains non-completion evidence.

- `2026-10-02`: Preserve the producer exit status when capturing validation logs
  - Context: a Game Session validation run failed a new fixture assertion, but a pipeline ending in `tee` returned exit 0 because the shell did not enable `pipefail`.
  - Expected pattern: enable `set -o pipefail` before captured validation pipelines and verify the terminal build result and test reports; a log sink's success is not the validation process's success.
  - Outcome: the failed build was identified from its terminal report, not reported as passing. After the fixture correction, the guarded complete rerun passed; no production check or negative assertion was weakened.

- `2026-10-03`: Assert fresh initialization before testing idempotence
  - Context: the Compose mTLS certificate contract checked private authority-directory permissions only after running setup twice.
  - Observation: the second call repaired the first call's incorrect permissions, so the passing contract masked a broken fresh-initialization postcondition.
  - Expected pattern: assert the first-call postconditions before any verification or idempotence rerun, then separately prove the rerun preserves the valid fixture.
  - Outcome: setup now restores owner-only authority permissions after generation, and the focused contract independently proves the first-call boundary and repeated-call preservation.

- `2026-10-03`: Native process handles may be scoped to the launching agent
  - Context: Gameplay tried to transfer an already-running full CLI command's native process handle to a read-only sentinel.
  - Observation: the sentinel received `Unknown process id`, while the launching agent could still read the same live process. Shared filesystem access did not imply shared process-handle access; the review was neither restarted nor canceled.
  - Expected pattern: retain an existing native process wait in its launching agent. For delegated external waits, let the sentinel launch its own canonical read-only waiter for the exact durable trigger or CI run; never retry the evidence-producing operation merely because a handle is unavailable in another agent.
  - Current status: the launching Gameplay agent retained the live CLI wait. Whether cross-agent process handles are supported in other execution environments is unverified.
  - Reconsideration trigger: revisit if a future harness requires cross-platform handle portability or a canonical watcher fix is verified.

- `2026-10-03`: Anchor repetitive fixture edits to the owning test method
  - Context: a row-lock reconciliation fix first matched two identical repository stubs in activation/failure tests instead of the intended terminal-retry tests, producing four test failures.
  - Expected pattern: include the test-method context when patching repeated fixture statements, inspect the exact changed methods before validation, and retain failed runs as non-completion evidence.
  - Outcome: the unrelated fixture changes were corrected in place; the final diff changes only the two terminal-retry lookup stubs, and the complete affected proof passes without changing production behavior or weakening assertions.

- `2026-10-03`: A merge preview needs positive conflict evidence, not a marker-only filter
  - Context: a read-only parent-forwarding inventory used this checkout's older three-argument `git merge-tree` and searched only for standard conflict markers.
  - Observation: the inventory incorrectly called the merge clean; the actual isolated no-commit merge reported six documentation conflicts. The preview's `changed in both` records and command outcome had not been inspected.
  - Expected pattern: verify the installed command's supported mode, inspect its complete conflict records and exit outcome, and treat an isolated actual merge as authoritative. Missing matches from a marker filter do not prove a conflict-free merge.
  - Outcome: the claim was retracted before publication, and the documentation intersections are being resolved explicitly while both unique patches remain preserved.

- `2026-10-03`: A terminal Hosted notification needs prompt owner consumption
  - Context: the exact-head #2898 Hosted review finished with five findings, but Gameplay delayed consuming the sentinel result while handling parent CI; adjudication and the public checkpoint followed about eleven minutes later.
  - Observation: an active watcher does not guarantee prompt adjudication. Its reassuring handoff wording cannot replace checking the attributable result and raw findings.
  - Expected pattern: consume terminal review evidence, adjudicate it, and publish the canonical checkpoint before returning to integration work. Keep CI repair independent rather than postponing completed review reporting.
  - Outcome: review `5398994837` was recorded as 5 found / 4 accepted / 1 routed before implementing its accepted batch; the inherited Account observation has a canonical target route.

- `2026-10-04`: Inspect and finalize complete CLI evidence before publishing counts
  - Context: on 2026-10-03, a 0/0 public checkpoint was briefly posted before the complete CLI capture was inspected. The terminal capture showed 1 found / 1 accepted; source finalization first refused the undecided finding, and linked checkpoint `5969229965` was corrected.
  - Expected pattern: inspect the complete terminal raw capture, adjudicate every finding, and confirm successful source finalization before building public counts. If finalization refuses, resolve the disposition and correct the linked checkpoint before reporting the result.
  - Outcome: the source decision and finalization were recorded with no dry-review credit.

- `2026-09-25`: Admission fixture actors must carry the complete typed scope
  - Context: a Game Session correction rejected unowned or ambiguous `PLAY` actors, while a shared Docker-backed WebSocket integration fixture still returned actor rows with protobuf-default playable scope.
  - Observation: local integration tests skipped without Docker, but CI executed them and many otherwise unrelated scenarios failed at the first `PLAY` with `PLAY_IDENTITY_UNAVAILABLE`; the fixture's missing scope and bare selection obscured the intended assertions.
  - Expected pattern: when actor-entry validation changes, update shared fixtures with complete tenant, account, actor, and playable-scope evidence; use explicit selection for success cases and retain a separate ambiguity-denial case. Treat compiled/skipped local tests as unproved until the composed CI cases execute.
