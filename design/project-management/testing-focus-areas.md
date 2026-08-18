# FireMUD Testing Focus Areas

This document is the short catalog of the testing areas that most often deserve explicit proof in the current FireMUD tree. Keep it focused on recurring risk domains and canonical proof surfaces, not on one-off bug notes.

## How to Use This Document

- Treat each numbered section as a living list of recurring regression risks.
- Update this document only when a bug reveals a durable recurring risk or a better shared proof surface.
- Keep individual bugs, current jobs, and one-off follow-ups with their active pull request or the owning domain implementation tracker when they change a capability gap.
- Prefer linking to the canonical suite, harness, or proof doc rather than copying command transcripts or Gradle invocations into this file.
- For current capability priorities and implementation status, use `design/project-management/implementation-tracking/README.md` and the relevant domain tracker rather than this file.

## 1. Admission, Authentication, and Session Binding

- Prove `LOGIN`, `PLAY`, reconnect, takeover, and logout behavior across both WebSocket and Telnet when the change touches session ownership or account identity.
- Check fail-closed behavior when account identity, connect context, entitlement state, moderation, or routing freshness no longer matches the preserved session.
- Keep first-party bootstrap and classic text-client admission aligned on the same account and gameplay-binding rules.
- Favor proof that checks both the user-visible result and the persisted or cached session state after the flow completes.

## 2. Routing Authority, Realm Selection, and Freshness

- Treat world and realm selection as a routing-sensitive seam, not just command parsing.
- Re-prove routing freshness whenever work touches admission pointers, reconnect, connect-token issuance, gameplay-stage command admission, or presence projections.
- Prefer tests that preserve and validate the full routing bundle rather than reverse-mapping from only `tenantId` and `gameInstanceId`.
- Keep player-facing discovery and admission reads aligned with the Game Session authority surface rather than local config copies.

## 3. Transport Parity and Transcript Semantics

- WebSocket and Telnet should agree on the admitted gameplay flow for the same user-visible action unless the protocol intentionally differs.
- Transcript-oriented tests should wait for the concrete canonical message they care about rather than depending on incidental ordering.
- Telnet transcript helpers must preserve multiline blocks, prompt-tolerant reads, and timeout-returned partial output where that is the real contract under test.
- For LOOK, SAY, and related transport-sensitive proof, use the capability-support docs under `slice-support/` as the canonical entrypoint:
  - `design/project-management/slice-support/look-and-say-regressions.md`
  - `design/project-management/slice-support/look-cross-service-tests.md`
  - `design/project-management/slice-support/look-smoke-tests.md`

## 4. Shared Harnesses, Fixtures, and Scenario Isolation

- Prefer shared gameplay drivers, scenario builders, assertion helpers, and cross-service stacks over local mini-harnesses.
- When a suite mutates baseline room state, social state, session state, or runtime rows, verify that the shared reset path restores the suite-specific baseline rather than a generic global default.
- Reuse canonical ready/admitted/reconnect/takeover helpers when possible so proof stays readable and transport semantics stay consistent.
- Treat flaky local polling loops as infrastructure work to eliminate unless the timing behavior itself is the thing under test.

## 5. Cross-Service Gameplay Mutation Flows

- Re-prove full command paths whenever work crosses Game Session, Game Logic, Entity Management, Social Groups, Gateway, or TCP Proxy boundaries.
- High-risk flows include:
  - movement and destination LOOK refresh
  - inventory, container, and equipment mutations
  - friend presence, roster mutations, and visibility policy
  - communication flows such as `SAY`, `WHISPER`, and `TELL`
- Prefer proof that checks both the player-visible transcript and the backend request shape or durable command outcome.

## 6. Redis, Persistence, and Replay-Sensitive State

- Verify cleanup and isolation around Redis keys, cached session state, recent presence, and replay-sensitive command or transport state.
- Check transactional and idempotent behavior when work touches durable command rows, admission pointers, migrations, or other retry-prone persistence seams.
- When fixing reconnect, restart, or cutover behavior, prove that stale cached state does not silently survive into the next admitted command path.
- If a change affects Flyway numbering, repo-wide validation still matters because service-local green checks can miss branch-wide migration drift.

## 7. Smoke Proof and Runtime Packaging

- If a change affects startup, wiring, auth, routing, migrations, or packaged artifacts, use the canonical repo smoke entrypoints under `dev-tools/` rather than ad hoc compose loops.
- Keep source-built smoke proof aligned with the canonical scripts:
  - `dev-tools/verify-fresh-bootstrap.sh`
  - `dev-tools/verify-restart-state.sh`
  - `dev-tools/verify-smoke-images.sh`
- Prefer fresh-image or fresh-container proof when runtime behavior could be hidden by stale local artifacts.
- Hosted or preview-specific proof should still validate the same player-facing admission and basic gameplay seams as the local smoke path.

## 8. Error Contracts, Logging, and Observability

- For each changed gRPC RPC, verify the [canonical outcome and transport classification](../architecture/system-architecture-grpc.md#outcome-and-transport-classification): expected domain outcomes use the typed result contract, while failures that prevent producing that result use canonical non-OK status with bounded details. Include mutation ambiguity/reconciliation and batch/stream item-versus-stream failure cases where applicable.
- Treat existing blanket `ErrorDetail`/transport-`OK` coverage as current implementation coverage only; it does not prove the target classification matrix or exact per-RPC mappings.
- Check that player-visible failures remain specific and safe when downstream services are unavailable or return app-level denials.
- Re-prove important command metrics, warning logs, and trace context when changing hot gameplay or admission paths.
- Favor tests that prove the exact canonical failure code or metric increment rather than only asserting that some generic error occurred.

## 9. Security and Boundary Enforcement

- Re-test account and tenant authorization whenever a route, RPC, or internal bridge changes ownership checks or session assumptions.
- Verify that internal-only seams do not quietly become caller-trusted seams through convenience shortcuts in tests or control-plane code.
- Keep transport-edge checks, session binding rules, and role-clamped visibility behavior explicitly covered where gameplay or operator work depends on them.
- For web or operator surfaces, keep standard OWASP concerns in scope, but prioritize the FireMUD-specific identity and routing boundaries first.

## 10. Bug-Driven Regression Discipline

- Significant bugs should land with a durable test, smoke proof, or harness improvement in the same risk family.
- When the real problem is missing shared test support, fix the harness or proof path rather than copying another local workaround.
- Revisit this document periodically to remove obsolete focus areas and add new recurring seams as the platform changes.

## 11. Script Transitions, Fences, and Recovery

Canonical contract and proof owners: [Scripting Contracts](../architecture/system-architecture-scripting-contracts.md), [Rollout and Rollback](../architecture/system-architecture-scripting-rollout-and-rollback.md), [Runtime Execution](../architecture/system-architecture-scripting-runtime-execution.md), [Scheduler and Timers](../architecture/system-architecture-scripting-scheduler-and-timers.md), [Normative Contract Tables](../architecture/system-architecture-scripting-normative-contract-tables.md), the [Game Session Runtime and Tick Coordination tracker](implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status), and the [Automation and Scheduler Runtime tracker](implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

The requirements in this section are target-state, capability-gated proof obligations, not claims that the live implementation already satisfies them; current implementation and proof gaps remain recorded in the linked owners.

- Test Game Session's atomic exact-pin tuple, rollout-history, and transactional-outbox boundary, including failure rollback, concurrent first pin, tagged precondition conflicts, exact-request idempotency, same-version repin, and strictly ordered projection replay. A successful same-version repin must advance `scriptPinEpoch`, emit exactly one `ScriptPatchPinChanged` outbox record, and cause final execution to reject work carrying the prior exact tuple. See the [control-plane event ordering contract](../architecture/system-architecture-scripting-control-plane-events.md#event-transport-contract-required) and [Automation projection consumption rules](../architecture/microservices/automation-scripting-service/api-contracts.md#script-patch-and-plugin-visibility-apis).

- Test instance-bound admission, scheduling, retry, handoff, and final effects with the authoritative `(scriptPatchVersion, scriptPinEpoch)` fence; reject missing, stale, or mismatched evidence without local fallback. Keep tenant-readiness `onLoad` as the explicit pre-pin exception. For fan-out and replay, assert that child Command-Handoff Identity contains complete source scope, optional target scope only when the runtime target is distinct, persisted `automationDispatchId`, and deterministic `commandOrdinal`; same-instance handoffs omit every optional target field, while the parent Trigger Identity, `outboxWorkItemId`, and `scriptEventId` remain correlation-only. See the [Normative Contract Tables](../architecture/system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state).

- Test distinct-instance remote follow-up source/target tuple binding and displacement fencing. An authoritative target `UNPINNED` must produce one durable no-script `remote_followup_result` with `outcome=ABANDONED` and `resultErrorCode=REMOTE_TARGET_UNPINNED`; exact retries replay it without Automation work, while changed identity conflicts and `authority_unavailable` create no terminal result and remain retryable. Same-instance cross-region follow-up uses one tuple.

- Test promotion and rollback pin-transition workflows against the [pin-transition state machine](../architecture/system-architecture-scripting-rollout-and-rollback.md#pin-transition-orchestration-state-machine-required), including pin-commit failure, repeated/concurrent repins, immutable `operationKind`, promotion and rollback convergence timeout, same-workflow timeout recovery idempotency, exactly-once `ScriptPinConvergenceTimedOut` and `automation_pin_convergence_timeout_total` consequences, and terminal `pin_convergence_timeout` admission. For rollback specifically, test admission barriers, cancellation and cleanup ambiguity, and preserve its pause/backpressure assertions. Preserve ordinary gameplay while scoped Automation work drains on the routine fenced path. Separately prove that a full gameplay pause is accepted only for a declared unfenced effect family, migration, or compatibility transition that cannot enforce the final `scriptPinEpoch` fence, with the smallest complete scope and quiescence evidence.

- Test stage-aware dead-letter recovery and claim reclamation with stable identities, exact generation/plugin/runtime fences, stale-owner CAS rejection, bounded selectors, and no DSL re-entry after evaluation. Purge remains separate and audited; tenant-readiness stale `ONLOAD_RUNNING` is terminalized by its recovery owner.

- Test both schedule-transition branches across patch/plugin transitions: the default or any non-opted-in side cancels and recreates the schedule with fresh due state, while explicit compatible opt-in on both sides preserves interval continuity. Verify stable plugin identity remains distinct from replaceable version provenance.

- Test operator readback composition for exact Game Session owner truth, Automation readiness/projection lag, plugin/timer/dead-letter diagnostics, and fail-closed owner `UNAVAILABLE`; projections must never replace owner truth.

- Test embedded scripts and linked plugins through the same DSL, sandbox, quota, output, and fence proof while preserving their distinct publication and activation lifecycles.

- Target/capability-gated signed-intake v1 proof uses one shared Game Design/Automation golden-vector fixture covering canonical path ordering, digest/signature encodings, tamper rejection, and revoked-signer behavior. This proves the target complete-verification capability; it does not claim the current signed-only intake implements complete-set persistence or verification. See [canonical signed-intake v1 signing](../architecture/microservices/game-design-service/modding-framework.md#signing-and-key-lifecycle-required).
