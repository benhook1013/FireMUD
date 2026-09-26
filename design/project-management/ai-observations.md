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

- `2026-09-26`: One-shot certificate issuance and readback require separate proof
  - Context: the Entity baseline migrator needs a short-lived dedicated client identity without giving its certificate writer general Secret-read or deletion privileges.
  - Observation: a leaf verifying against the CA bundled in the same Secret is only self-consistent; it does not show that the namespace trusts that CA, and static issuance tests do not show the live Entity server accepts the identity.
  - Expected pattern: keep issuance opt-in and narrowly admitted, verify the projected leaf and chain against the namespace's independent trust projection under a separately authorized reader, then report live served-mTLS acceptance and later identity retirement as distinct proof.
