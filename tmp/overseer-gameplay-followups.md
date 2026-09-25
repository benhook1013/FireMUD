# General programme card

Updated 2026-09-25 NZST. Gate 2's repository slice was published in draft #2853; its protected live proof remains open. [General's stack-consolidation brief](./task-briefs/general-phase1-stack-consolidation.md) owns the current assigned work. This card retains supporting candidates for later phases; [Overseer project direction](./project-direction-notes.md) owns the live queue.

## Gate 2 — playable delivery

The repository slice is in #2853. Protected credential/CA setup, served-certificate and consumer convergence, CNI, playable Telnet/WebSocket proof, and post-merge develop/dev-demo confirmation remain operational evidence; the live direction and PR handoff own their status.

## Phase 1 and Phase 2

After Gate 2, Phase 1 is one repository-wide shared-foundation and pre-v1 simplification programme. The dev-demo summary parser was published as #2855 and is assigned for incorporation into #2853. Remaining candidate inputs are:

- prove canonical mTLS and typed `PlayerExecutionContext`, then remove deprecated gameplay JWT attestation;
- converge the gRPC non-OK/`RPCErrorDetail` contract;
- review legacy global-role, god-mode, and `HIDDEN_STAFF` paths under their canonical ADRs;
- reconsider `EnvironmentIdentityPlan` construction only if a lasting API/validation choice is justified.

Phase 1 completes only after fresh corrected-state repository-wide assessments taper on meaningful shared opportunities; CodeRabbit taper alone is insufficient. Phase 2 then addresses service-local maintainability. Reconsider `HostedIdentityReconciler` role-descriptor consolidation only with proof that ordered fail-closed fences, partial-transition reporting, and per-role state remain explicit. These are programme candidates, not separate active PRs.
