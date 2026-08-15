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

- Prove that Game Session atomically commits the exact per-instance `{scriptPatchVersion, scriptPinEpoch}` and rollout-history entry plus exactly one `ScriptPatchPinChanged` transactional-outbox record for each authoritative change; consumer progress is independent of the owner commit and replay is idempotent. An exact retry with the same `controlPlaneRequestId` and canonical request fingerprint returns the original result, adds no history row, and does not advance `scriptPinEpoch`; separately, a new `controlPlaneRequestId` that repins the same patch creates exactly one new `REPIN` history row and advances `scriptPinEpoch` exactly once.
- Prove script-derived Automation admission, scheduler firings, retries, plugin triggers, durable work, handoff, and final gameplay effects carry the exact authoritative `{scriptPatchVersion, scriptPinEpoch}` tuple and reject missing, stale, or mismatched tuples without a local/last-known patch fallback or stale-pin operator override; ordinary non-script gameplay admission and ticks should continue during scoped Automation failure.
- Exercise rollback preparation failure, concurrent/repeated repins, in-flight old-epoch evaluation, asynchronous cancellation/purge, and convergence timeout without routine gameplay pause. Reserve full tick-pause proof for an explicitly declared unfenced effect family.
- Exercise stage-aware dead-letter recovery: evaluation-stage retry uses the original frozen manifest and graph; post-evaluation recovery replays stored output and unfinished child dispatches without DSL re-entry; missing or contradictory evidence remains dead-lettered; purge is separate and audited.
- Verify timer transition behavior across patch/plugin changes: cancel and recreate by default, preserve only with explicit compatible continuity, and never transfer one-shot timers through the interval rule. Verify that plugin stable identity is distinct from replaceable plugin-version provenance.
- Prove operator readback composition joins Game Session's exact pin/epoch/history with Automation readiness, projection freshness, plugin, timer, and dead-letter diagnostics; cover scoped freshness/mismatch behavior, owner `UNAVAILABLE` fail-closed handling, and Automation lag that never replaces owner truth.
- Verify that embedded scripts and linked plugins use identical DSL/sandbox safety and output/fence proof while retaining their distinct publication and activation lifecycles.
