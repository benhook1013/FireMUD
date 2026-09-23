# General and Gameplay programme card

Updated 2026-09-22 NZST. This is supporting detail only. [project-direction-notes.md](./project-direction-notes.md) is the sole live queue authority; this card cannot override its order or ownership.

## Gate 1 — stacked preview enablement

General is preparing one direct-to-`develop` PR. An explicitly `preview:priority`-labelled, same-repository, human-authored stacked PR may use hosted preview; ordinary unlabelled previews remain limited to `main`/`develop`, with forks and dependency bots excluded. PR-controlled construction stays credential-free. Trusted default-branch publication/deployment validates the exact base/head/merge tuple and immutable `pr-merge-<merge SHA>` artifact, and reconciliation detects parent movement through merge/image identity. Preserve the two-slot priority/reclamation rules and failed-empty-preview acceptance check. Fix the reproduced metadata-only edit path so it cannot dispatch or cancel an unchanged exact-tuple runtime build or leave a duplicate failing required Smoke context. After a coherent handoff, Gate 1 temporarily takes Hosted, CodeRabbit, and merge priority; once it merges, #2837 and the authored train resume.

## Review-control refactor

General owns one new child PR directly on #2838, inserted before #2818. It replaces all existing public CodeRabbit/PR-review executables with a single `dev-tools/pr-review` command and a single private repository review-stack configuration under the shared Git common directory. The final PR removes the old commands and every reference in the same change; it does not leave compatibility shims or deferred cleanup.

The single stack has independent derived Hosted and CLI targets. Hosted never advances on a rate limit or missing completed evidence. CLI may advance serially through every consecutive stable, review-eligible PR after three consecutive zero-useful rounds on each target, stopping at the first held, unstable, unreconciled, over-ceiling, or judgment-blocked PR. Both channels run through the same command, wrong-target attempts fail before quota use, and cross-channel head changes after taper require an explicit head/checkpoint-bound human judgment to reopen or retain completion. One concise status surface replaces the three current operator reports; deeper evidence remains a subcommand. Historical checkpoint and private trigger/capture evidence stays readable. Normal workflow no longer advertises the incremental `@coderabbitai review` fallback.

For every stacked PR after the first, review preflight derives the effective parent as the nearest preceding unmerged configured PR, or the default base after predecessors merge. Its GitHub base must match that effective parent, the live parent head must equal the base tip, and that exact tip must be an ancestor of the candidate. Audit the complete adjacent effective-parent chain. Anchor review evidence to child head, parent identity/head, merge base, and unique patch identity. Parent movement marks downstream evidence `PARENT_MOVED` and blocks normal quota until reconciliation; unchanged patch identity can retain prior taper only through a recorded judgment, while overlapping or material changes reopen review. An exceptional equivalent-history path after squash/rebase also requires an explicit head-bound judgment. There is no generic force option. A reasoned, head-bound CLI-only unreconciled override may gather one provisional discovery pass, but it cannot satisfy taper, move a cursor, or support merge readiness; Hosted remains blocked.

General may use bounded implementation helpers, then runs up to six fresh serial Luna xhigh whole-boundary cycles, integrating and validating after every productive cycle. Stop at the first zero-useful cycle or after six productive cycles, publish a clean draft with current body/LOC and required CI, and hand it to Gameplay for priority CodeRabbit review. General does not use CodeRabbit.

Gameplay handoff is preceded by independent Overseer acceptance. At least two fresh root-owned subagents must operate the actual command with isolated private state and no review quota: one completes the normal configure/status/evidence/run-selection/decision workflow and assesses concise output usability; the other drives wrong-target, cooldown, reset, parent-movement, unreconciled, merged-parent, provisional-override, stale-decision, locking, and atomic-write failures. This is hands-on behavior testing, not another diff review. General must make the state location or adapter safely injectable for this test without weakening the production default. Any accepted acceptance-test defect is fixed and revalidated before Gameplay receives priority.

## Gate 2 — playable delivery

After the final #2829 head is available, General prepares one coherent preview-priority child. Use the combined stack to prove protected requester/controller/CA/issuer setup, certificate issue/serve/projection/rotation convergence, CNI allow/deny, exact deployed SHA, and Telnet/WebSocket `LOGIN → PLAY → LOOK`, including reconnect and failure behavior where practical. Credentials, CA private material, and live cluster state never enter Git. Post-merge develop/dev-demo confirmation is operational evidence.

## Phase 1 and Phase 2

Phase 1 is one repository-wide shared-foundation and pre-v1 simplification programme, after Gate 2. Candidate inputs are:

- simplify `check_dev_demo_summary.py` to a supported fail-closed bootstrap language;
- prove the canonical mTLS and typed `PlayerExecutionContext` path, then remove deprecated gameplay JWT attestation;
- converge the gRPC non-OK/`RPCErrorDetail` contract;
- review legacy global-role, god-mode, and `HIDDEN_STAFF` paths under their canonical ADRs;
- reconsider `EnvironmentIdentityPlan` construction only if a lasting API/validation choice is justified.

Phase 1 completes only after fresh corrected-state repository-wide discovery and assessments taper on meaningful shared opportunities; CodeRabbit taper alone does not establish programme completion. Phase 2 follows Phase 1 for service-local maintainability. Reconsider `HostedIdentityReconciler` role-descriptor consolidation only with proof that ordered fail-closed fences, partial-transition reporting, and per-role state remain explicit. These are programme candidates, not separate active PRs.

General and Gameplay preserve the current train and do not create substitute work while a stated gate or front review is active. Detailed PR review evidence belongs in PR bodies and private ledgers.

## Review judgment correction

Numerical Hosted and CLI allowances are report-back boundaries. They do not establish merge readiness or require stopping a productive review. Gameplay reports raw found/accepted sequences and severity; Overseer presents that evidence for human judgment before merging. An `N/0` round is dry even when raw rejected findings are nonzero.
