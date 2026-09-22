# Overseer project direction

Updated 2026-09-22 NZST. This is the sole live orchestration authority for the authored work queue. Architecture/design documents define target behavior; PR bodies and private PR ledgers own detailed findings, review counts, proof gaps, and handoffs. Do not duplicate those details here or treat archived history as instructions.

## Current merge train

`develop → #2824 → #2818 → #2826 → #2827 → #2828 → #2829`

- **#2824 — containment foundation.** Gameplay owns the front Hosted/merge decision; Document preserves it and does not review it. Docker-dependent cases still require attributable hosted proof when local Docker is unavailable.
- **#2818 — publication workload identity and production activation.** Draft successor after #2824; its certificate/Helm identity must reconcile with the Gameplay tree before review and merge.
- **#2826 — 5B Schema.** Published on #2824; four productive whole-unit Luna cycles followed by two dry cycles. Local proof is green, but PostgreSQL/Testcontainers cases were compiled and skipped without Docker. Hold for later retarget/CodeRabbit proof.
- **#2827 — V3 plugin lifecycle/fences.** Published on #2826; five productive whole-boundary cycles and a dry Cycle 6. No further Luna discovery is required before later retarget/CodeRabbit proof. Its composed validation inherits deterministic Account/Game Session ancestor failures; fix those at the earliest owning ancestor, not by claiming them for this PR.
- **#2828 — 5B Publication.** Published on the train and currently owned by Document for fresh whole-Publication review. Cycle 1 found useful obligations; adjudicate, fix, validate, and publish accepted findings, then run the required Cycle 2. If Cycle 2 is productive, continue only under its existing bounded taper/report-back rule; hand off when that applicable condition is met.
- **#2829 — 5B Retention/replay V4.** Published after #2828, with substantive review still pending. When active, explicitly adjudicate the blank-`effectId` replay bypass in this receipt boundary; broader typed-effect capability belongs to the later 2C/shared-foundation unit.

The train preserves Automation migration order V2 readiness → V3 lifecycle fences → V4 replay/retention. Workers report readiness; Overseer owns merge and verified-superseded closure decisions.

## General programme

General Gate 1 is a separate direct-to-`develop` PR: allow only an explicitly `preview:priority`-labelled, same-repository, human-authored stacked PR to use hosted preview. Keep ordinary unlabelled eligibility limited to `main`/`develop`; exclude forks and dependency bots; keep PR-controlled code credential-free; publish/deploy from trusted default-branch code with exact base/head/merge identity and immutable `pr-merge-<merge SHA>` artifacts. Preserve the existing two-slot priority/reclamation rules. After a coherent handoff, Gate 1 temporarily takes Hosted, CodeRabbit, and merge priority; once it merges, #2824 and the authored train resume. Gate 1 must merge before stacked preview can be used.

After the train reaches its final published #2829 head, General Gate 2 is one coherent preview-priority child for protected bootstrap, controller/CA/issuer readiness, certificate projection/rotation convergence, CNI allow/deny, exact deployed identity, and Telnet/WebSocket `LOGIN → PLAY → LOOK` proof. Credentials, CA private material, and live cluster state never enter Git. A short post-merge develop/dev-demo confirmation is operational evidence, not a separate implementation PR. Split only for a concrete independent boundary or file ceiling.

After Gate 2, General proceeds to Phase 1 repository-wide shared-foundation/refactor/pre-v1 simplification, then Phase 2 service-local maintainability. The candidate inventory is in the supporting programme card; it is one programme, not a set of separate backlog jobs. The JWT migration must prove the canonical mTLS and typed `PlayerExecutionContext` path before deprecated JWT attestation is removed. Phase 1 completion requires repository-wide fresh corrected-state assessments until meaningful shared opportunities taper; CodeRabbit taper alone is insufficient.

## Document programme and true backlog

Document completes #2828, then #2829, then consumes the parked #2661 source into the later 5C/5D units. After the train, it continues the mapped whole-corpus authority programme and final owner/secondary/tracker consistency pass. The 25-unit source map is the corpus authority; 5C/5D and other later units are queued programme work, not forgotten obligations.

The true backlog outside the train is only Document’s post-5B corpus programme and General’s Phase 1 → Phase 2 programme. Shared tooling/refactoring, CI/hosted operations, default preview TLS, and failed-empty-preview capacity are candidate inputs or acceptance checks within those programmes, not standalone jobs. No later gameplay feature slice is selected.

## Operating invariants

- Broad Luna review means serial corrected-state whole-boundary cycles; integrate and validate each cycle before the next. Parallel complementary readers count as one cycle.
- A numerical Luna cap is a report-back boundary, not a taper target or permission to leave useful accepted findings unresolved.
- A wake-capable sentinel owns a wait; the parent awaits its mailbox and does not poll or narrate unchanged timers.
- Report Hosted raw found/accepted counts separately from current/outdated unresolved threads; zero unresolved threads is not raw 0/0.
- When local Docker is unavailable, use attributable hosted CI for the named cases when available and state exactly what executed; green checks without case-level evidence do not close the proof gap.

Review capacity follows the active front and is transferred only at a coherent handoff. Gameplay normally owns Hosted/CodeRabbit for the front merge candidate; General owns its separate Gate 1 work; Document owns its assigned corpus PR review. Workers do not merge. PR bodies and private ledgers are the source for detailed PR findings and are not duplicated here.
