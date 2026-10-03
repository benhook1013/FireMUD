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

- `2026-09-09`: A public symmetric JWK is the signing credential
  - Context: hosted review proposed making a diagnostic `oct.k` correspond to the shared-HMAC Secret, but Account serves that document through the public JWKS route.
  - Observation: an `oct` JWK has no public-only representation; encoding the exact HMAC bytes would let every reader mint valid tokens. A mismatched diagnostic key must not be repaired by publishing the private material.
  - Expected pattern: trace the actual signer, validators, mounts, and routed JWKS endpoint before aligning key metadata. For the current diagnostic-only hosted path, publish an empty JWK Set plus non-secret correlation metadata; implement real verification only with the canonical asymmetric Account-published JWKS and custody boundary.

- `2026-09-09`: Bind cert-manager Secret bytes to durable issuance evidence
  - Context: a hosted identity controller initially treated a ready Certificate revision and a separately read TLS Secret as one issuance snapshot during renewal.
  - Observation: cert-manager writes the Secret before advancing Certificate status, so independent reads can combine different revisions; Ready status and Secret metadata do not prove that the observed certificate bytes belong to the recorded revision.
  - Expected pattern: select the uniquely owned CertificateRequest for the ready revision, require its issued certificate bytes to match the Secret exactly, carry those validated bytes forward, and re-read the Certificate snapshot before acceptance. Treat absent or ambiguous issuance evidence as retryable and remember that `CertificateRequest.status.ca` is optional.

- `2026-09-09`: CodeRabbit uncommitted review omits untracked files
  - Context: an uncommitted CLI review reported the tracked workflow refactor clean but did not include its new local composite action in `reviewedFiles`.
  - Observation: `coderabbit review --uncommitted` does not provide evidence for a new file while that file remains untracked, even when tracked callers of the file are reviewed.
  - Expected pattern: inspect `reviewedFiles` before treating an uncommitted review as complete; stage or intent-to-add new files before a later CLI cycle, or explicitly report the missing coverage and use focused proof for that file.

- `2026-09-09`: Post each adjudicated CLI review checkpoint before the next Hosted request
  - Context: an independent CLI cycle finished between two Hosted cycles, but its canonical found/accepted checkpoint was delayed until after the second Hosted request while accepted findings were being implemented.
  - Observation: implementation and verification do not need to finish before the review count is durable; once every CLI finding is adjudicated, delaying the count obscures the actual independent review cadence.
  - Expected pattern: promptly adjudicate a completed CLI cycle and post its canonical found/accepted count immediately, before fixes, verification, or the next Hosted trigger. Continue CLI, Hosted, CI, and fixes as independent parallel lanes.

- `2026-09-13`: Version-file setup requires checkout ordering
  - Context: workflow language and operational tool versions moved from repeated YAML literals to repository-owned authority files.
  - Observation: GitHub setup actions and local composites cannot consume repository files before checkout, and a top-level workflow environment value cannot read a file directly.
  - Expected pattern: keep checkout before every file-backed setup action, validate that ordering as a workflow contract, and route operational tools through a local loader that rejects malformed or missing authority files.

- `2026-09-14`: Release-asset version updates need an explicit checksum completion step
  - Context: Renovate can discover GitHub release versions but cannot derive the checksum of an arbitrary release archive into a second authority field.
  - Observation: independently managed version and checksum fields would either permit stale verification or leave routine update repair ambiguous.
  - Expected pattern: store the checksum's source version beside the digest, fail closed when it differs from the tool version, and use the repository updater to fetch the publisher's checksum manifest and update the pair together.

- `2026-09-14`: Production image pinning remains a promotion even when the runtime version is unchanged
  - Context: pinning the existing Velero CronJob tag to its registry digest changed a production-applicable manifest, while the repository has no retained staging deployment and promotion evidence for that change.
  - Observation: a correct digest does not substitute for the canonical staging, smoke, recovery, custody, and approval lineage required by production preflight.
  - Expected pattern: retain the verified digest authority and transactional update path, but commit the production projection only in an evidence-backed promotion PR; never fabricate an attestation or weaken preflight for a tooling-only change.

- `2026-09-16`: Concurrent Gradle validation can race generated sources
  - Context: focused Gradle tests for independent modules were launched concurrently during multi-module validation.
  - Observation: concurrent generation and compilation raced generated sources in a shared dependency and caused transient missing-generated-source errors that obscured the real result.
  - Expected pattern: run Gradle validation sequentially when tasks share generated-source dependencies, preferably through the canonical locked runner or one combined invocation, and parallelize only independent read-only checks such as script linting.

- `2026-09-19`: Canonical validation wrappers and broad formatters need scope-safe invocation
  - Context: successor validation in a dedicated stacked worktree used `dev-tools/validation/run-locked-gradle.sh` and `dev-tools/validation/validate-helm.sh`, then the repository-wide `spotlessApply` required by the validation workflow.
  - Observation: both canonical shell entrypoints are tracked as non-executable, so direct invocation fails unless callers know to use `bash`; repository-wide Spotless also reformatted clean inherited controller files outside the assigned slice, requiring explicit diff inspection and scoped reversal before handoff.
  - Expected pattern: either make canonical validation entrypoints executable or document `bash` as the required invocation, and treat broad automatic-formatting output as untrusted scope expansion until the resulting diff is inspected against ownership boundaries.

- `2026-09-20`: A partial Spotless invocation can report success without checking formatting
  - Context: focused TCP Proxy tests and `:tcp-proxy-service:spotlessCheck` passed locally, but a later full local check found formatting violations in the same changed test files.
  - Observation: without `-PfullCheck`, this module's `spotlessJavaCheck` and `spotlessCheck` tasks were skipped; the successful Gradle exit was not formatting proof.
  - Expected pattern: when claiming focused formatting proof for this module, run the locked Spotless check with `-PfullCheck` and confirm the check task executed rather than showing `SKIPPED`.

- `2026-09-20`: Workflow-code fixes need an environment-policy cutover for old branches
  - Context: preview deployment secrets were available to a branch-selectable workflow on a persistent self-hosted runner; new workflow code routes privileged jobs through the default branch.
  - Observation: an existing PR branch can retain its old workflow revision after the corrected default-branch workflow merges, and an unrestricted GitHub environment can still release secrets to that old revision.
  - Expected pattern: alongside the code change, restrict privileged GitHub environments to the trusted deployment branch and clear or rotate credentials exposed by old runner state; verify the live environment policies before declaring the trust boundary effective.

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

- `2026-09-25`: Admission fixture actors must carry the complete typed scope
  - Context: a Game Session correction rejected unowned or ambiguous `PLAY` actors, while a shared Docker-backed WebSocket integration fixture still returned actor rows with protobuf-default playable scope.
  - Observation: local integration tests skipped without Docker, but CI executed them and many otherwise unrelated scenarios failed at the first `PLAY` with `PLAY_IDENTITY_UNAVAILABLE`; the fixture's missing scope and bare selection obscured the intended assertions.
  - Expected pattern: when actor-entry validation changes, update shared fixtures with complete tenant, account, actor, and playable-scope evidence; use explicit selection for success cases and retain a separate ambiguity-denial case. Treat compiled/skipped local tests as unproved until the composed CI cases execute.

- `2026-09-26`: Review status refused a shared private allocation record
  - Context: during #2873 implementation, `dev-tools/pr-review status --pr 2873` returned `review allocation contains fields outside the private schema` before review intake.
  - Observation: the shared `2818` review allocation contains historical `stop_*` and retained-ambiguity fields that this worktree's `ReviewAllocation.from_dict` does not recognize. The controller could not report current review state; no review was requested or allocation edited. This is a cross-worktree private-state/schema mismatch, not evidence that #2873 has a review allocation.
  - Expected pattern: reconcile the shared record against the controller version that wrote it with the owning operator before CodeRabbit intake; do not bypass the controller, delete private evidence, or infer review eligibility from GitHub's visible state alone.

- `2026-09-26`: One-shot certificate issuance and readback require separate proof
  - Context: the Entity baseline migrator needs a short-lived dedicated client identity without giving its certificate writer general Secret-read or deletion privileges.
  - Observation: a leaf verifying against the CA bundled in the same Secret is only self-consistent; it does not show that the namespace trusts that CA, and static issuance tests do not show the live Entity server accepts the identity.
  - Expected pattern: keep issuance opt-in and narrowly admitted, verify the projected leaf and chain against the namespace's independent trust projection under a separately authorized reader, then report live served-mTLS acceptance and later identity retirement as distinct proof.

- `2026-09-26`: PostgreSQL identity migrations need separate jOOQ and runtime proof
  - Context: Account and Game Design added UUID identity-source migrations while preserving numeric retained rows. The configured community jOOQ DDL parser rejected `GENERATED ALWAYS AS` and some grouped `ALTER COLUMN`/PostgreSQL function syntax, even though those are database constructs.
  - Observation: replacing Account's generated source column with a trigger-bound immutable column and isolating Game Design's PostgreSQL-only DDL behind the existing jOOQ-ignore markers let schema generation proceed. Local Testcontainers cases compiled but skipped without Docker, so parser success is not PostgreSQL migration execution.
  - Expected pattern: run jOOQ generation, Flyway numbering, and focused Java tests locally; keep a separate exact-head PostgreSQL migration/readback gate on a runner with Docker, and report any skipped integration case as unproved rather than green runtime evidence.

- `2026-09-26`: Standalone Flyway migration fixtures must reproduce JDBC and placeholder configuration
  - Context: #2873's Account tenant-association PostgreSQL test passed local compilation but skipped without Docker. Its first hosted run could not find an inserted table because a new `?currentSchema=` was appended to a Testcontainers JDBC URL that already carried a query string. After preserving the existing separator, the next run reached the later saga migration and failed because the standalone Flyway invocation omitted `${serviceSchema}`.
  - Expected pattern: preserve existing JDBC URL parameters when adding a schema selector, and supply the same Flyway placeholders for both target-version setup and subsequent full migration. Treat each hosted failure as fixture evidence until the exact database case executes and passes; local compilation is not that proof.

- `2026-09-26`: Indexed PostgreSQL `TEXT` migrations may fail the jOOQ/H2 schema model
  - Context: Account's new authority-outbox stream key used an indexed `TEXT` column; PostgreSQL accepts that shape, but the configured jOOQ DDL interpreter mapped it to an H2 CLOB and rejected the primary-key index before Java compilation.
  - Expected pattern: use an explicit bounded `VARCHAR` for indexed identity fields with matching application validation, run the owning service's `generateJooq`, and still prove the migration and concurrency behavior separately against PostgreSQL. Parser success does not establish runtime database proof.

- `2026-09-27`: RFC 3339 fractional-second parsing needs all permitted precisions
  - Context: the Account response-envelope Secret materializer emitted canonical UTC timestamps with trailing fractional zeroes removed. A broad developer-tool run intermittently failed its CLI readback although focused tests using whole-second timestamps passed.
  - Observation: this runner's Python `datetime.fromisoformat` rejected a valid five-digit fractional-second timestamp such as `.00101Z` after offset normalization. A timestamp round-trip sweep reproduced the defect, and the parser was changed to accept the contract's one-to-six fractional digits explicitly; the full 112-test developer-tool suite then passed.
  - Expected pattern: test exact writer-to-reader timestamp round trips across fractional precisions, including live-clock output, before treating a source-generation/freshness readback as reliable.
- `2026-09-27`: Hosted database task success may lack per-case proof artifacts
  - Context: Unit 1B Account integration tests skipped locally without Docker. Hosted runs executed the integration task and passed on corrected code, but the uploaded artifact contained JaCoCo output without JUnit XML for the named PostgreSQL case.
  - Observation: task-level execution and success are useful composed evidence, but they do not independently identify which newly corrected database methods ran or whether any were skipped.
  - Expected pattern: retain and upload the exact integration JUnit XML alongside coverage artifacts when individual database regressions are needed as proof; until then report task-level success separately from per-case execution.

- `2026-09-27`: Proto-touching Gradle proof may need configuration cache disabled
  - Context: a combined Account and Game Session focused check regenerated protobuf sources after a membership wire change.
  - Observation: Gradle 9.5.1 reached Java/test work but failed while storing its configuration cache because the protobuf plugin's `GenerateProtoTask` captured unsupported project and source-directory objects. The same canonical tasks passed with `--no-configuration-cache`; the first failed exit was not valid test proof even though some tasks had run.
  - Expected pattern: for proto-affecting validation, pass `--no-configuration-cache` when this plugin error appears, retain the canonical task paths and service locks, and report the successful rerun rather than treating partial task output as a green gate.

- `2026-09-26`: Necessary procedural migration preflights may use jOOQ ignore markers
  - Context: Account V25's duplicate-detection preflight requires a PostgreSQL `DO` block and wraps it in `-- [jooq ignore start]` and `-- [jooq ignore stop]` markers.
  - Observation: this qualifies the 2026-09-20 expected pattern: declarative constraints remain preferred when sufficient, but a necessary procedural preflight can be excluded from jOOQ parsing while retaining separate PostgreSQL migration proof.
  - Expected pattern: prefer declarative constraints when sufficient; when procedural preflight is necessary, wrap it in the jOOQ ignore markers, run `generateJooq`, and run separate PostgreSQL migration proof.

- `2026-09-30`: Verify a PR's remote head branch before publishing from an isolated worktree
  - Context: #2873 was reconciled in a local preparation branch whose name differed from the PR head branch; an initial push mistakenly named #2880's branch as its destination.
  - Observation: Git rejected that push as non-fast-forward, so no remote branch changed. The local commit was then pushed to #2873's verified `headRefName`.
  - Expected pattern: read the live PR `headRefName` and exact remote head immediately before each push from an isolated branch; use the verified destination, and treat a non-fast-forward rejection as a stop-and-recheck signal rather than forcing it.

- `2026-09-30`: Await publication readback before dispatching exact-head CI
  - Context: a #2881 push yielded asynchronously and later returned a generic remote rejection. Dependent PR-body and CI operations were issued before consuming that result, so CI run `36703184951` targeted the old published head instead of the prepared correction.
  - Observation: the rejection's cause was not reported. Fresh remote readback confirmed no head change; a non-force retry then published the correction. The stale-head run was explicitly cancelled and excluded from proof.
  - Expected pattern: finish the push, verify the exact published SHA, and only then describe it as published or dispatch its CI. Independent operations may run concurrently; operations depending on publication must remain ordered even when tool calls yield.

- `2026-10-01`: Fixture names do not establish exclusive ownership
  - Context: #2911 removed unreachable legacy HMAC first-party success fixtures. A Redis value store named for first-party context also served normal gameplay presence; its mock/stubs were removed with the obsolete cases.
  - Observation: local Docker-backed integration cases skipped, so compilation and module checks did not expose the lost shared support. Exact-head CI ran the cases and all 16 WebSocket scenarios failed at a null value-operations collaborator, before the intended assertions. The fixture support was restored without restoring HMAC admission; executed correction proof remains required.
  - Expected pattern: trace every consumer of a shared fixture before removing it, use neutral names for shared support, and preserve an exact executed integration gate when cleanup affects skipped local tests. A green compiled/skipped gate is not proof that fixture dependencies were retained.

- `2026-10-01`: Shared WebSocket fixture correction gained exact executed proof
  - Outcome: exact #2911 head `b7766564d` in CI run `36769172597` executed all 16 required WebSocket integration cases with no skips or failures, including the named complete-signed-context no-mutation denial. This closes the executed correction-proof gap above, not the independent real first-party credential or live browser proof.

- `2026-10-01`: Stack ancestors may not support the tail's test-proof CLI
  - Context: Account counter-exhaustion tests belong to #2876, whose test-result inspector is diagnostic-only; the strict required-suite/case interface exists only in its later descendant.
  - Observation: inspection before publication caught an unsupported strict CI step in the older ancestor. The uncommitted step was removed and the required execution gate placed in #2911, preserving source-test ownership without copying future tooling into the ancestor. Local PostgreSQL skips remain explicitly unproved.
  - Expected pattern: verify a workflow command against that exact branch's tooling version, not the latest tail; gate composed runtime proof where the required tool exists, then keep owning-PR and composed-descendant proof distinct.

- `2026-10-01`: Observe the earliest actual transaction in PostgreSQL contention proof
  - Context: the complete Account snapshot/first-JOIN test held the Account row `FOR UPDATE` and waited for JOIN's explicit Account-lock spy. JOIN first inserts its PENDING intent, whose foreign-key check can already wait on that same row.
  - Observation: exact CI timed out at the later spy, not at a source invariant. Instrumenting the actual intent-insert backend before the real insert retained the exact `pg_blocking_pids` correlation and complete before/after DTO assertions; corrected CI `36778631119` executed the race once successfully. Worker exceptions are surfaced rather than hidden behind a latch timeout.
  - Expected pattern: trace earlier foreign-key and deferred-trigger work before selecting a concurrency signal; prove the exact blocked backend/owner relation and do not fix a misplaced signal by extending timeouts or changing production transaction order.

- `2026-10-01`: Consume the final validation result before committing a claimed green checkpoint
  - Context: a final #2917 tracker edit began a paragraph with a PR hash, which Markdown lint interpreted as a malformed heading. The documentation gate exited nonzero, but its redirected log was not inspected before the local commit.
  - Observation: no push occurred; inspection identified the lint failure and the unvalidated claim was corrected in a follow-up commit. Earlier successful documentation checks did not cover that last edit.
  - Expected pattern: consume every final gate's exit status and failure log before committing or publishing proof claims; after a status-only edit, validate that exact edited scope rather than inheriting the preceding green result.

- `2026-10-01`: Pin module-local configuration in configuration contract tests
  - Context: the Game Design owner-read test loaded `application.yml` by its generic classpath name, while several modules provide that same resource name.
  - Observation: the test saw an empty method allowlist rather than the owning service's configuration. Loading the module's explicit `src/main/resources` files exposed the actual default and production lists; the corrected assertion passed without weakening the required methods. This is local configuration proof, not live workload authentication.
  - Expected pattern: use an explicit owner-module resource when proving a service's configuration, and keep runtime classpath precedence and authenticated deployment proof separate.

- `2026-10-02`: Use explicit typed result extraction for jOOQ SQL fixture readback
  - Context: repeated Account PostgreSQL fixtures supplied `Long.class` or `UUID.class` to `DSLContext.fetchValue(String, Object...)` as if that SQL overload selected a return type.
  - Observation: the class is a bind argument, not a type selector; compilation alone did not prove correct SQL binding. Integration inspection corrected the fixtures before claiming execution.
  - Expected pattern: bind only actual SQL parameters with `resultQuery(sql, parameters)`, then use `fetchOne(columnIndex, type)` for typed extraction and an explicit required-row check where absence is impossible. Retain separate PostgreSQL execution proof; analyzer or compiler success is not that proof.

- `2026-10-02`: Use the affected SpotBugs task's detailed log when no report is emitted
  - Context: an Account `fullCheck` failed at `spotbugsTest` with exit code 1 but no report artifact or finding in the normal log.
  - Observation: rerunning the affected canonical task with `-PfullCheck --info` exposed the exact unused-method finding. The obsolete numeric assertion helper was removed and the consolidated gate rerun; no analyzer suppression or disabled check was needed.
  - Expected pattern: inspect the affected task's detailed output before searching for a report that its configuration does not emit, and keep the diagnostic run separate from the final consolidated proof.

- `2026-10-02`: Stub the underlying Mockito target, not a Spring repository proxy
  - Context: exact Account PostgreSQL CI36888416843 executed all14required UUID/OTP/HTTP cases but failed one setup with `UnfinishedStubbingException`; its stack crossed the persistence-exception-translation proxy while configuring `doAnswer`.
  - Observation: a prior green run did not establish stable spy setup. The fixture correction unwraps the ultimate Mockito target for setup and verification, while production calls still traverse the injected Spring proxy and the real owner transaction. A separately logged scheduled-cleanup warning is not proved to cause this failure.
  - Expected pattern: use `AopTestUtils.getUltimateTargetObject` when configuring a proxied spy, preserve the actual proxy on the tested call path and retain transaction/no-mutation assertions. Require executed runner proof for the corrected setup; local compilation and Docker-skipped tests cannot establish recovery.

- `2026-10-02`: Bind test servers before discovering their ephemeral port
  - Context: the consolidated Unit 1B Game Session check failed `WorldManagementStubServerTest.unknownRoomInstanceIdReturnsNotFound` before its behavior assertion with `Address already in use` on a port selected by `TestSocketUtils.findAvailableTcpPort`.
  - Observation: checking a free port and releasing it before the actual bind leaves a race. The existing stub already accepts port zero and reports its bound port; its three tests now use that interface without changing their domain assertions. The colliding process was not identified. All three tests and the complete corrected Account/Game Session/Common Test Support check subsequently passed, establishing local recovery without a domain-assertion change.
  - Expected pattern: use kernel-assigned port-zero binding and the started server's actual port when the fixture supports it, rather than an unreserved free-port probe. Do not weaken an assertion or label a failure as flaky without its exact bind evidence.

- `2026-10-02`: JDBC UUID fixture bindings need executed PostgreSQL proof
  - Context: #2929's local module checks passed with PostgreSQL/cross-service cases skipped, but exact [CI36909989685](https://github.com/benhook1013/FireMUD/actions/runs/36909989685) failed 34 of 35 Game Session cross-service cases during shared fixture insertion. The report explicitly rejected a `character varying` expression for `game_instances.owner_account_uuid`.
  - Observation: the fixture passed a canonical UUID string to `JdbcTemplate`, whose JDBC binding remained a string despite the correct domain value. The fixture now binds a `java.util.UUID`, and its focused mock assertion requires that typed value. Local compilation or mock agreement is not proof that PostgreSQL accepts a bind; corrected runner execution remains pending.
  - Expected pattern: bind UUID columns with typed UUID values or an explicit, owner-appropriate SQL cast. Preserve the exact canonical domain checks and require executed PostgreSQL/setup proof before attributing subsequent assertions to the changed behavior.

- `2026-10-02`: Corrected JDBC UUID fixture runner readback
  - Outcome: exact #2929 source `96fb1b803d4dd1c6a3cc8f39bda91ae537e25bb6` [CI36912843232](https://github.com/benhook1013/FireMUD/actions/runs/36912843232) strictly passed the owner migration and changed-owner snapshot cases and both Account profile cases, each executed once without skips/failures/errors. Game Session passed the old fixture insertion failure and reached16behavioral assertions before cancellation. This closes the typed-bind setup and profile-username recovery questions above, not the wider behavior or aggregate CI gate.

- `2026-10-02`: Constrain artifact downloads and prove temporary-directory ownership before cleanup
  - Context: a bounded CI investigation authorized only the named Game Session artifact in a fresh `mktemp` directory. The helper instead invoked an unfiltered download into `/tmp/not-used`, receiving three unrelated coverage artifact folders, then removed that directory without proving it was absent before the command. No repository files changed; the helper reported only the three downloaded coverage folders were present, but prior exclusivity was not established.
  - Consequence: neither unrelated artifacts nor inferred prior directory ownership supplies valid diagnostic evidence. The directory was removed; its earlier state cannot be established from that inspection. Root stopped further cleanup and restricted the continuation to a new exclusive directory and the exact named artifact.
  - Expected pattern: use an artifact-name filter and a freshly created exclusive temporary directory, separate read-only evidence retrieval from cleanup, and never recursively delete a generic output directory merely because a preceding tool wrote into it. Report a scope violation before recovery; do not broaden a read-only task with unapproved cleanup.

- `2026-10-02`: Do not use a truncated PR file array as the changed-file count
  - Context: `gh pr view --json files` returned 100 entries for the coherent Unit 1B carrier, while the GitHub `changedFiles` scalar and the exact local base/head diff both reported 116. Counting the returned array alone would have understated the published scope.
  - Expected pattern: use the PR's `changedFiles` scalar for scope counts and independently compare the exact base/head diff; when individual paths are needed, retrieve the complete paginated list rather than treating a capped array as full coverage. A truncated array is not evidence that the patch fits a reviewer ceiling.

- `2026-10-02`: Keep prerequisite proof stages inside coherent review packages
  - Context: the Account source stage and retained Game Session tenant-UUID consumer were published as separate 21- and 16-file PRs, but their combined patch was only 35 unique files. Human direction favors related packages of roughly 60–80 files when suitable, without padding or weakening independently named proof and activation limits.
  - Outcome: #2933 absorbed #2935 by advancing to its identical published head, preserving every hunk and history. GitHub then marked the child merged because its base contained that exact head; no PR merge or auto-merge action was executed. Exact-head CI remains attributable to the unchanged tree.
  - Expected pattern: separate contracts and proof in the PR handoff rather than creating a PR per helper or test stage. Check actual unique paths and complete-tree preservation, publish the combined boundary, then read back both GitHub state and the configured queue; do not infer deployment or readiness from a child's merged label.

- `2026-10-02`: Match long Gradle report filenames separately from JUnit suite identities
  - Context: exact CI36947366507 strictly proved the eight reset cases and eleven retained-association cases, but artifact paths using the literal suite name missed the producer and retained-association XML. The actual producer filename was `TEST-integ-LL300RMKP9ANC.net.firedevops.firemud.accountservice.PasswordResetAuthorityProducerPostgresIntegrationTest.xml`, while its XML retained the canonical full suite name.
  - Expected pattern: use class-bounded filename globs for artifact capture and retain exact canonical suite/case identities in the strict verifier. Prove the glob matches the actual reports; an upload success with one file is not evidence that every intended suite was retained. Keep execution-log proof distinct from missing raw XML and require a subsequent runner outcome for the corrected capture path.

- `2026-10-02`: Keep helper file targets and validation inside the pinned assignment
  - Context: a helper assigned to the isolated Unit 1B worktree accidentally created an empty `DO_NOT_USE.java` in the default checkout, reported it, and removed its own disposable file; the orchestrator confirmed it was absent. Another helper ran an unrequested diff check despite a validation-none assignment. No user change or useful implementation was lost, and neither action supplied credited validation.
  - Expected pattern: verify the worktree identity before edits, use absolute patch targets, and treat validation-none as a closed boundary rather than permission for substitute checks. Report scope mistakes immediately and distinguish recovery from canonical proof; a successful helper handoff does not substitute for orchestrator inspection and validation.

- `2026-10-02`: Distinguish caught service warnings from the exception that fails gameplay proof
  - Context: the first CI36967764374 sentinel summary highlighted Game Design DNS failure as the WebSocket PLAY cause. The fixture helper followed the complete trace and found that warning was caught with defaults; the actual socket-closing exception was a null character-list response because the mock still selected a numeric Account ID after LOGIN returned the canonical UUID.
  - Outcome: the fixture correction preserves production guards, uses the exact UUID selector and configures the existing fake service endpoint; exact CI36970584573 subsequently executed all sixteen WebSocket cases cleanly. The initial diagnosis was corrected in the private evidence and public checkpoint before claiming proof.
  - Expected pattern: identify the fatal test/assertion path and distinguish caught warnings and secondary teardown failures. A prominent stack trace or a green component suite alone does not establish the root cause of broader gameplay failure. Likewise, select the exact matrix job before attributing conditional proof steps: a CI36969757041 sentinel inspected the non-Account job and reported skipped proof, while root's exact Account-job readback showed strict verification failed and XML upload succeeded.

- `2026-10-02`: Bound PostgreSQL fixture identifiers before appending the full random nonce
  - Context: all eight new logout-all PostgreSQL cases in CI36969757041 failed during Flyway setup because the descriptive schema prefix plus a full UUID exceeded PostgreSQL's 63-byte identifier limit. The physically truncated schema did not match the requested name, producing duplicate-schema creation errors before the operation assertions.
  - Outcome: the fixture now uses the ASCII `logout_src` prefix and preserves its complete 128-bit nonce, keeping names at 43 bytes. Corrected database execution remains required; compiling or discovering the class locally did not expose this runtime setup error.
  - Expected pattern: use a short ASCII prefix with a full unique nonce, verify the complete identifier length, and retain the same declared schema in JDBC/Flyway/readback. Do not fix this by weakening migration, isolation or transaction proof.

- `2026-10-02`: Verify negative preservation scope as well as transferred split paths
  - Context: while mechanically transferring the Redis projection from #2933 to its existing child, a helper patch briefly deleted the parent-owned PostgreSQL/socket-mTLS test outside the transfer allowlist. The helper reported the scope error and immediately restored that file byte-for-byte from the pinned commit with a patch; the resulting working diff leaves it unchanged.
  - Expected pattern: explicitly check every parent-owned retained blob and the complete cumulative child tree, not just the moved path count. A reviewed transfer map does not make a generated deletion list safe by itself. Keep published history intact and verify no-loss before publication.

- `2026-10-02`: Distinguish structural collaboration exhaustion from model capacity
  - Context: dispatching the Account current-recipient helper failed with `agent thread limit reached` while two useful bounded workers and one CI sentinel were active. This was a structural slot error, not evidence that Luna was unavailable.
  - Outcome: following the slot-recovery skill, the orchestrator closed out a completed split helper with a return-only continuation, checked agent state and retried the same fresh Luna xhigh assignment once. It succeeded without interrupting useful work or changing model/tier. The harness's precise reclamation timing remains unknown; the retry success does not prove that the close-out message alone released the slot.
  - Expected pattern: preserve active work, distinguish the exact failure class and use a bounded structural recovery attempt. Do not substitute models or relabel a reused helper as a fresh independent review.

- `2026-10-02`: Isolate delegated command text from prose punctuation
  - Context: two focused Account/Game Session helper invocations treated a trailing prose period as a Gradle task named `.` and failed before the intended tasks ran. Corrected invocations without that argument succeeded; the failed invocations supplied no test proof.
  - Expected pattern: put exact allowed commands on standalone fenced or explicitly delimited lines, with no sentence punctuation attached. Read back the actual invocation and task outcome before attributing validation; an intended command is not an executed gate.

- `2026-10-02`: Check migrated constraints before manufacturing a missing-history failure
  - Context: an Account outbox investigation initially treated a missing stream head with retained events as a possible runtime gap. Reading V33's immediate foreign key, immutable-event guards and unconditional head-delete rejection disproved that state under the intact schema.
  - Outcome: the unnecessary local query and unit changes were removed before publication. Focused PostgreSQL tests instead exercise rejected deletion with unchanged evidence and exact-stream isolation without synthesizing authority; local Docker absence still requires executed runner proof.
  - Expected pattern: inspect the complete owning migration before accepting a repository-level failure premise. Do not disable constraints or invent corrupted fixtures to justify a runtime repair; distinguish actual invariant proof from recovery for a separately specified corruption model.

- `2026-10-03`: Script convenience APIs can widen a deliberately narrow Redis grant
  - Context: the projection helpers' Spring Redis script convenience path can fall back from `EVALSHA` to raw `EVAL`. Giving the isolated fixture both commands masked the difference from the canonical owner-helper `SCRIPT LOAD`/`EVALSHA` grant.
  - Outcome: the Account and issuer candidates use verified registered bytes, check the identity returned by `SCRIPT LOAD`, and invoke `EVALSHA` directly without fallback or a blind retry. Isolated Redis proof denies raw `EVAL`; local database/Redis skips still require executed runner evidence.
  - Expected pattern: inspect convenience-API recovery behavior against the exact command grant, and test the forbidden command as well as the positive registered-helper path. Do not widen ACLs just to accommodate an implicit fallback.

- `2026-10-03`: Preserve the exact external run identifier in watcher assignments
  - Context: two bounded CI sentinels inspected a shortened or mistyped run identifier instead of the supplied full identifier and reported HTTP 404. That was not evidence that the authorized run was inaccessible or nonexistent.
  - Outcome: direct same-task corrections restored observation of the intended runs. The current run37030807027 is visible; its earlier lookup of37007080727 supplied no validation evidence. No repository or CI mutation was needed.
  - Expected pattern: copy the complete identifier from the assigned run URL, verify the returned run identifier and head SHA before attribution, and include the actually inspected identifier in a lookup-failure report. Do not escalate a mistyped-ID lookup as a platform-access failure or silently substitute a different run.

- `2026-10-03`: Use the installed shared controller after SQLite cutover
  - Context: an older stacked worktree's repo-local controller refused status with `SQLite cutover marker does not match database metadata`. This was a reader/version mismatch, not evidence about the PR's review or CI state.
  - Outcome: the human-designated `/home/ben/.local/bin/firemud-pr-review` reports writer build 8 and is used for the readback instead. No private database, marker or review evidence was edited to bypass the refusal.
  - Expected pattern: select the installed shared entrypoint named by the active brief, verify its version and preserve fail-closed cutover records; do not repair private metadata from an outdated checkout.

- `2026-10-03`: Match PostgreSQL rejection proof to the actual exception-translation boundary
  - Context: exact CI37109999851 executed seven descriptor and eight World intake PostgreSQL cases. Three failed assertions expected jOOQ `DataAccessException`, while the Spring-managed DSL translated correctly rejected writes into `DataIntegrityViolationException`; the concurrent World failure arrived through its future. Raw-connection fixtures retained their different jOOQ boundary.
  - Outcome: the focused fixture correction preserves the descriptor's exact constraint and World's reservation message, SQL state and no-mutation assertions. Corrected runner execution remains required; local compilation or skipped PostgreSQL cases cannot prove the repaired assertions.
  - Expected pattern: inspect the actual cause chain and preserve the exact constraint or SQL-state rejection rather than accepting any database error, catching every runtime exception or weakening a production guard. Do not blindly replace exception types in raw-connection fixtures merely because the service-managed DSL translates them.
  - `2026-10-03` verified follow-up: exact CI37111727823 at `73ab357d694618a56c65129394fd324b38ad652e` executed all seven descriptor and eight World intake PostgreSQL cases cleanly, plus thirteen descriptor and nine source-read physical mTLS cases. Independently parsed and rehashed raw XML confirms all 37 focused cases have zero skips, failures or errors. The aggregate run still failed on identified checks outside these four suites; focused success is not live deployment or enabled runtime proof.

- `2026-10-03`: Authenticate a mutation's receiver before releasing request bytes
  - Context: the existing gRPC server-peer response interceptor validates the certificate only when response headers or content arrive. Integration inspection of the World intake client and its initial socket fixture found that the wrong authenticated server could receive the mutation before its response was rejected; that initial fixture was not executed. This response guard is not pre-send receiver authorization.
  - Outcome: the unregistered World intake client now also checks the negotiated TLS peer through call credentials before releasing request metadata/body, retaining the response guard. All seven focused socket cases execute cleanly locally, including both wrong-server intake/read rejection with no owner interaction. The receiver service is mocked in that suite; it does not prove database composition, delivery or runtime activation.
  - Expected pattern: for owner mutations, prove both denial of wrong-peer responses and that the wrong receiver never sees the owner request. Do not treat response rejection as evidence that no mutation was attempted.

- `2026-10-04`: Tie scoped source inventory to the same verified working directory
  - Context: a bounded read-only helper reported that login-shell behavior had redirected its source inspection to the default checkout, then repeated negative inventory claims about files the orchestrator verified existed in the pinned worktree. Its initial identity check did not establish the location of those later reads. The cause of the remaining contradictory inventory is unproved.
  - Outcome: the orchestrator rejected that source inventory and commissioned a bounded recheck with explicit working directory, disabled login-shell behavior and contemporaneous source-path verification. The helper made no edits; build diagnostics independently identified the intended worktree. No absent-file or architecture decision is inferred from the rejected map.
  - Expected pattern: for revision-sensitive worktree evidence, verify the directory, branch and head together with the actual scoped source paths, and use absolute paths or an explicit directory change when shell behavior is uncertain. Treat contradictory source presence as an attribution problem before inferring a missing capability.

- `2026-10-04`: Paginate CI artifact inventory before reporting missing proof
  - Context: the exact CI37141351309 observer initially reported the bare-LOGIN XML artifact missing from an unpaginated artifact response, although its upload succeeded and it existed on the second page.
  - Outcome: the complete paginated inventory recovered the artifact; independent raw-XML parsing and hashing proved all fourteen storage cases executed cleanly. The initial missing-artifact statement supplied no evidence of a skipped test or broken upload.
  - Expected pattern: inspect every artifact page and the exact upload outcome before classifying evidence as absent. Preserve the exact run/head and distinguish unavailable capture from execution failure.
