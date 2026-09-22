# Overseer project direction

Updated 2026-09-22 NZST. This is the sole live orchestration authority for the authored work queue. Architecture/design documents define target behavior; PR bodies and private PR ledgers own detailed findings, review counts, proof gaps, and handoffs. Do not duplicate those details here or treat archived history as instructions.

## Current merge train

Replacement containment PR #2837 merged to `develop` as `1fd8dc3feb1a746e736095645e45b6740d200a58` on 2026-09-22. General's Gate 1 #2835 is now the immediate direct-to-`develop` prerequisite and must land before the remaining stacked train can use prioritized preview. The remaining authored train is:

`develop → #2818 → #2826 → #2827 → #2828 → #2829`

- **#2837 — containment foundation replacement (merged).** The reviewed 48-file #2824 patch was recreated after #2836 reverted its premature merge. Final exact-head Hosted was 0/0, required CI was green, and it merged as `1fd8dc3feb1a746e736095645e45b6740d200a58`. CLI remained productive when explicitly stopped; this limitation was considered in the human merge judgment.
- **#2818 — publication workload identity and production activation.** Draft successor after #2837; its certificate/Helm identity must reconcile with the Gameplay tree before review and merge.
- **#2826 — 5B Schema.** Published in the authored train; four productive whole-unit Luna cycles followed by two dry cycles. Local proof is green, but PostgreSQL/Testcontainers cases were compiled and skipped without Docker. Hold for later retarget/CodeRabbit proof.
- **#2827 — V3 plugin lifecycle/fences.** Published on #2826; five productive whole-boundary cycles and a dry Cycle 6. No further Luna discovery is required before later retarget/CodeRabbit proof. Its composed validation inherits deterministic Account/Game Session ancestor failures; fix those at the earliest owning ancestor, not by claiming them for this PR.
- **#2828 — 5B Publication.** Tapered after fresh whole-Publication review and propagated into #2829. Hold for later train reconciliation and CodeRabbit proof.
- **#2829 — 5B Retention/replay V4.** Parked cleanly at `dce1d8246e3ff2bf9c3bfcb9649ae0e5c4181e6b`; Retention tapered after productive Cycle 1 and dry Cycles 2–3. The cumulative 5B closure audit completed three paired cycles: Cycle 1 dry, Cycle 2 productive with corrections propagated through #2827/#2828/#2829, and Cycle 3 dry. Deterministic ancestry/no-loss proof passed. On 2026-09-22 the Overseer accepted this completed six-review audit as the final 5B corpus stopping point; no Cycle 4 remains queued. The PR still requires later train reconciliation, CodeRabbit, CI/runtime proof, and human merge judgment.

The train preserves Automation migration order V2 readiness → V3 lifecycle fences → V4 replay/retention. Workers report readiness; Overseer owns merge and verified-superseded closure decisions.

## General programme

General Gate 1 is a separate direct-to-`develop` PR: allow only an explicitly `preview:priority`-labelled, same-repository, human-authored stacked PR to use hosted preview. Keep ordinary unlabelled eligibility limited to `main`/`develop`; exclude forks and dependency bots; keep PR-controlled code credential-free; publish/deploy from trusted default-branch code with exact base/head/merge identity and immutable `pr-merge-<merge SHA>` artifacts. Preserve the existing two-slot priority/reclamation rules. Fix the reproduced metadata-only edit path so it cannot dispatch or cancel an unchanged exact-tuple runtime build or leave a duplicate failing required Smoke context. General's final whole-boundary Luna cycle completed at `7028cf19195dbf39713a53f9d718d081eb588adb` with two accepted P2 findings: stale priority-candidate revalidation in capacity allocation, and live base/exact merge-parent validation at both preview-comment publication fences. Gameplay now owns those fixes, reconciliation onto current `develop`, and final CodeRabbit/CI proof. #2835 must merge before #2818 or later stacked previews are used.

Gameplay has assumed #2835 merge-readiness ownership, so General's active standalone develop-based tooling PR combines two related reporting repairs. First, add elapsed duration to Hosted and CLI checkpoint comments and hidden markers: exclude known cooldown/sentinel waiting; use accepted-trigger-to-terminal elapsed time for Hosted and canonical process wall time for CLI; preserve found/accepted semantics and backward parsing of historical duration-less comments; add focused rendering, parsing, and malformed/missing-duration tests. Second, combine unit and PostgreSQL/integration-test JaCoCo XML coverage before Codecov upload so exercised repository paths are not reported as 0%; preserve production-code visibility rather than adding exclusions, and verify the combined report contains representative unit and integration classes. This work does not modify the feature train or use CodeRabbit.

After the train reaches its final published #2829 head, General Gate 2 is one coherent preview-priority child for protected bootstrap, controller/CA/issuer readiness, certificate projection/rotation convergence, CNI allow/deny, exact deployed identity, and Telnet/WebSocket `LOGIN → PLAY → LOOK` proof. Credentials, CA private material, and live cluster state never enter Git. A short post-merge develop/dev-demo confirmation is operational evidence, not a separate implementation PR. Split only for a concrete independent boundary or file ceiling.

After Gate 2, General proceeds to Phase 1 repository-wide shared-foundation/refactor/pre-v1 simplification, then Phase 2 service-local maintainability. The candidate inventory is in the supporting programme card; it is one programme, not a set of separate backlog jobs. The JWT migration must prove the canonical mTLS and typed `PlayerExecutionContext` path before deprecated JWT attestation is removed. Phase 1 completion requires repository-wide fresh corrected-state assessments until meaningful shared opportunities taper; CodeRabbit taper alone is insufficient.

## Document programme and true backlog

Document completed #2829's own Retention taper and three paired cumulative closure cycles. The Overseer accepted that evidence as final and closed the 5B corpus-review phase without commissioning an extra Cycle 4. Document's next corpus task, when resumed, is the combined 5C/5D lane consuming parked #2661 with hunk-level no-loss proof. The 25-unit source map remains the corpus authority.

The true backlog outside the train is only Document’s post-5B corpus programme and General’s Phase 1 → Phase 2 programme. Shared tooling/refactoring, CI/hosted operations, default preview TLS, and failed-empty-preview capacity are candidate inputs or acceptance checks within those programmes, not standalone jobs. No later gameplay feature slice is selected.

## Operating invariants

- Broad Luna review means serial corrected-state whole-boundary cycles; integrate and validate each cycle before the next. Parallel complementary readers count as one cycle.
- Any numerical Hosted, CLI, or Luna allowance is a report-back boundary, not merge readiness, a stopping condition, or permission to leave useful accepted findings unresolved. Useful findings reset the applicable taper. Overseer must present the raw sequence and significance for human judgment before merging.
- A wake-capable sentinel owns a wait; the parent awaits its mailbox and does not poll or narrate unchanged timers.
- Orchestrators delegate bounded, disjoint mechanical reading, inventory, implementation, and focused validation early instead of performing the bulk work in the main lane. They retain design decisions, finding adjudication, diff inspection, integration, and final evidence judgment. Do not compensate for late delegation by launching overlapping agents without distinct scopes.
- Report Hosted raw found/accepted counts separately from current/outdated unresolved threads; zero unresolved threads is not raw 0/0.
- When local Docker is unavailable, use attributable hosted CI for the named cases when available and state exactly what executed; green checks without case-level evidence do not close the proof gap.

Review capacity follows the active front and is transferred only at a coherent handoff. Gameplay normally owns Hosted/CodeRabbit for the front merge candidate; General owns its separate Gate 1 work; Document owns its assigned corpus PR review. Workers do not merge. PR bodies and private ledgers are the source for detailed PR findings and are not duplicated here.

## Overseer corrections recorded 2026-09-22

- Overseer incorrectly treated #2824's three-round Hosted report boundary as merge readiness instead of returning its still-productive `2/1 → 2/2 → 1/1` evidence for human judgment. The merge was reverted by #2836 and is being recreated as #2837.
- Overseer repeated the same cap error by describing #2837 CLI as complete after two productive rounds (`3/1`, `4/2`). CLI was resumed; future caps trigger reporting and do not stop productive review.
- Overseer allowed a new user question to interrupt creation of an already-required CI sentinel. Existing authorized work remains active across steering unless the user explicitly stops or replaces it.
- Overseer and worker orchestrators repeatedly performed delegable inventories and mechanical review preparation in their main lanes until the user intervened. Future tasks identify disjoint delegation lanes at the start rather than treating subagents as a late recovery step.
