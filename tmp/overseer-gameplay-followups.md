# General and Gameplay programme card

Updated 2026-09-22 NZST. This is supporting detail only. [project-direction-notes.md](./project-direction-notes.md) is the sole live queue authority; this card cannot override its order or ownership.

## Gate 1 — stacked preview enablement

General is preparing one direct-to-`develop` PR. An explicitly `preview:priority`-labelled, same-repository, human-authored stacked PR may use hosted preview; ordinary unlabelled previews remain limited to `main`/`develop`, with forks and dependency bots excluded. PR-controlled construction stays credential-free. Trusted default-branch publication/deployment validates the exact base/head/merge tuple and immutable `pr-merge-<merge SHA>` artifact, and reconciliation detects parent movement through merge/image identity. Preserve the two-slot priority/reclamation rules and failed-empty-preview acceptance check. After a coherent handoff, Gate 1 temporarily takes Hosted, CodeRabbit, and merge priority; once it merges, #2824 and the authored train resume.

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
