# Unit 5B Follow-up Seed

Local ignored orchestration ledger for the small Unit 5B follow-up after PR #2669. This file creates no design authority. Findings must be rechecked against the post-merge `develop` head before editing.

## Source checkpoint

- Reviewed branch: `codex/pr2669-rebuilt`
- Final documentation-review head: `309a8ebff4257c3de4728914b6e85ed5c8463f1e`
- Current PR head: `14b480eb36b324585b87b2447459439bbe9a7ee6` (focused tick-staging CI fix)
- PR: #2669
- Follow-up branch: `codex/corpus-review-5b-followup`
- Follow-up worktree: `/home/ben/src/FireMUD-corpus-review-5b-followup`
- Temporary stack base: `309a8ebff4257c3de4728914b6e85ed5c8463f1e`; rebase onto the resulting `develop` head after #2669 merges and before publication.

## Corroborated static findings

1. Game Session runtime documentation assigns complete startup/shutdown lifecycle coordination and compensation to a nonexistent monolithic `firemud-common` Saga path. Correct the owner boundary: current `common-saga` covers only bounded optional readiness checks; durable World lifecycle uses the World-owned database/CAS boundary and Temporal workflow.
2. ADR 0080 and canonical migration guidance require adopter-local `common-saga` migrations, but blanket service-convention dependencies, Flyway locations, test migration support, auto-configuration, ERD/reset tooling, and one migration instruction wire Saga into non-adopters. Define the explicit adopter boundary and remove or accurately track broad wiring.
3. `system-architecture-versioning-runtime.md` presents legacy untenant-qualified `game_manifest` as launch/retirement authority even though Game Session and live code identify it as non-authoritative drift. Restore tenant-qualified Game Design release/attestation authority and treat any listing as a non-authorizing projection.
4. Automation runtime documentation places Cache/Rate-Limit queue/quota/capacity prefixes inside the Coordination Redis participation section. Keep those families solely under Cache/Rate-Limit Redis and reserve Coordination wording for its actual target families.

## Independent adversarial findings

5. `ScheduleRemoteFollowup` lacks an exact receiver-side internal workload/service-method authorization boundary. Add the intended allowlist/scope binding and negative proof.
6. Remote-followup scheduling and coordinator mirroring resolve source `commandId` globally, then can mutate a command from another tenant or game instance. Bind lookup and mutation to tenant, origin instance, and immutable source provenance.
7. Result projections use an unscoped `resultCommandId` fallback and can misattribute another tenant/instance command. Bind lookup to the result's target scope or leave it unresolved on mismatch.

## Final broad-review findings

8. Scripting architecture still describes monolithic `firemud-common` Lua registries, builders, and invocation helpers. Preserve only shared descriptor/validation foundations; executable key building and scripts remain owner-local, and Automation hands off through the Game Session service contract.
9. Account creation audit prose describes a compensated Saga step, while the implementation performs post-commit best-effort logging and deliberately allows account creation to succeed when logging fails. Correct current status and keep mandatory audit as a durable owner-local outbox/retry/reconciliation target.
10. Transactions prose says consuming services automatically create Saga tables, contradicting ADR 0080's adopter-local target. Point to the explicit adopter boundary and mark blanket current wiring as implementation drift.
11. The already-tracked generic Redis-client gap has a concrete deployment consequence: Game Session correctness and cache consumers can share one generic endpoint/template despite distinct deployed roles, risking correctness state on eviction-enabled cache Redis or resolution of an unavailable generic Kubernetes hostname. Preserve this as implementation work; do not smuggle the broad Redis refactor into the corpus follow-up.
12. Shared scripting prose must distinguish shared descriptor schema/aggregation/validation from each owner's descriptor contribution and executable builders/Lua; “shared foundations provide descriptors” is still too ambiguous under ADR 0176.
13. Saga adoption has no finite canonical service/workflow registry despite prose claiming an authoritative adopter classification. Add the registry at the migration owner only if accepted workflow authority determines the set; otherwise remove the false claim and track the unresolved classification instead of inventing adopters.
14. Mandatory Account audit delivery needs a stable tenant-scoped outbox/event identity, payload digest mismatch rejection, receiver deduplication, and exact readback/reconciliation across timeout-after-commit retries; the current `CreateLogEvent` RPC inserts a fresh row per call and cannot prove that target.
15. Current blanket `common-saga` dependency, auto-configuration, and Flyway wiring contradicts adopter-local target language; describe it as drift and require a machine-enforced opt-in/classification boundary rather than treating current inclusion as authorization.
16. Account docs still give open-ended Saga permission even though actual `accountCreation` and `purchase` uses have no recorded adopter/workflow classification. Current use must not silently become target authority.
17. Logging Admin's Saga Dashboard reads only its local Saga repository but Transactions describes platform-wide visibility. Current local-only, target owner read APIs plus bounded aggregation, and unknown-versus-empty semantics must be explicit.
18. Mandatory Account audit delivery cannot use the receiver's operator `AdminRoleGuard`; it needs a dedicated non-destructive Account workload/mTLS service-method authorization path.
19. The Account audit identity must conform to the repository event envelope: producer `occurredAt`, schema version, versioned digest preimage, and an exact receipt/readback shape belong at the Logging Admin API owner rather than being duplicated in Account docs.
20. Remote-followup point and bulk target-command resolution still trusts tenant-only or pre-resolved candidates without binding the full target game instance/region/epoch. Existing tests missed the vulnerable linked-result path; a corrective pass is active.

## Broad candidates requiring strict adjudication

- Logging Admin Saga reads may report an owner-local empty store as platform-wide emptiness rather than federating owner reads or returning unknown.
- Entity replacement compatibility may leave actor resource-state and active-condition tables unclassified.
- Game Session lifecycle compensation may restore `RUNNING` after stop/drain begins, and activation may mark Game Session running before World is active.
- Start/restart idempotency, terminal cleanup acknowledgements/safe-watermarks, World synchronous fallback, and lifecycle request-identity semantics may remain incomplete.
- Automation queue rebuild may include `EVALUATING` rows without a stale-owner/CAS recovery gate.

These candidates are not accepted merely because a reviewer raised them. Recheck target authority, current implementation, and existing tracker coverage before editing.

## Completed-review convergence

- Authority board: 3 actionable findings; items 1, 2, and 4.
- Initial schema board: 3 preliminary findings; items 1, 2, and 3. It read all 18 sources but conservatively marked the run incomplete after an unrelated branch-head update.
- Replacement schema board: 3 actionable findings; independently confirmed items 1, 2, and 3.
- Adversarial board: 3 actionable findings; items 5, 6, and 7.
- Retention/lifecycle board: 12 candidates, retained above only as an adjudication pool because several are broad known implementation gaps.
- Final cross-service-handoff board: 6 confirmed residuals; items 1, 4, and 8-11, with item 3 explicitly rejected as already correct legacy/non-authoritative wording on the reviewed head.
- One final service-boundary board stopped cleanly when #2669's head advanced; it produced no findings and made no changes.

## CLI seed pool

- Post-cutoff CodeRabbit CLI: 26 raw findings over the accumulated 93-file diff.
- Focused post-CI-fix CLI at `14b480eb3`: 12 raw findings. Material follow-up candidates are recovery acknowledgement identity/scope, serialized reset-token contract enforcement, ordinary admission-state reads taking an exclusive instance lock, generic-bootstrap routing not clearing `playableStateScope`, and a missing malformed-vs-present routing-bundle test. Automation Redis wording duplicates item 4; test moves/renames, helper extraction, extra warning logs, and unreachable-catch cleanup are low-value unless later review gives them corrective scope.
- Do not treat raw count as useful count. Adjudicate into this follow-up only after #2669 merges.
- Material candidates already visible include routing-scope clearing and canonical projection, bounded queue consumption, RPC/result scope authorization, tick/Redis representation correctness, migration/runbook proof, and current/target owner wording.
- Expected rejects/deferrals include test renames, optional helper extraction, generic logging requests, already-tracked rollout gaps, and cosmetic status synchronization.

## Current blocker

#2669 auto-merge is armed. The focused tick-staging fix is pushed at `14b480eb3`: the Lua validator unwraps production JDK-serialized String queue entries without changing stored bytes, and focused tests cover raw entries, `TC_STRING`, `TC_LONGSTRING`, malformed envelopes, atomic rejection, and production-format keys. The first rerun stopped at Spotless before executing tests; canonical formatting is pushed at `8ae8c9b88`, and CI is rerunning again. The follow-up worktree may accumulate local fixes meanwhile but must be rebased onto merged `develop` before publication.
