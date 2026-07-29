# ADR 0039: Bounded Redis Operator Maintenance Surface

## Status

Accepted

## Implementation Status

The bounded Redis operator surface remains target state, not a fully shipped or proved repo-local tool. Current Redis operations documentation records the `coordination-maintenance` surface as incomplete; separate ACL verification, durable recovery/continuation lifecycle, supported-scope inventory, and break-glass post-check proof remain outstanding.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `PO-1.4` Operability, supportability, and incident response
- Affected capabilities: `SF-2.2`, `SF-1.3`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `REDIS-06`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `REDIS-06`

## Context

Coordination Redis contains correctness-sensitive, short-lived runtime state. The existing design correctly denies routine interactive writes, but also specifies eleven public maintenance verbs and region, tenant, and cluster scope before most of that tooling or scope inventory exists. Publishing every recovery phase as an operator command increases release proof, compatibility, runbook, and misuse burden without improving the underlying recovery invariant.

## Decision

- Coordination and Cache Redis remain separate deployments and ACL domains. Human operator accounts are read-only by default.
- Application writes use owned, typed key and mutation helpers. Registered Lua scripts are required where atomic multi-key behavior needs them, not for every ordinary single-key mutation.
- Normal operator mutations use version-matched, owner-supported tooling. The initial public maintenance surface is bounded to `status`, one `recover --mode <replay-first|reset|session-schema-cleanup>` operation, `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`, `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`, and the canonical `releaseMaintenanceLock(operationId, scope, maintenanceLockToken, reason, evidenceRef)` operation. Pause-and-lock is an internal fenced phase of the durable recovery operation, not a standalone public verb.
- The single public recover/reset operation owns the required ordered phases, including durable epoch handling, Account authority-projection rebuild, ledger and command convergence, metadata initialization, session policy, and the post-reset smoke gate. Reset recovery must rebuild and verify the issuer, account, tenant, and membership generation projections plus issued-token projections from Account durable authority before smoke can pass or `resume` can authorize release. Those phases may have internal APIs and focused tests, but `reset`, `reconcile-ledger`, `converge-commands`, `init-meta`, `rebind-sessions`, `smoke-check`, and `session-cleanup` are not separately public operator verbs.
- Each recovery mode has an explicit phase contract, and mode selection never bypasses safety gates:
  - `replay-first` may replay only the declared coherent durable work and must still complete authority/projection proof, affected-scope reconciliation, and the post-recovery smoke gate before any gameplay-capable `resume`.
  - `reset` must complete the full destructive reset ordering, rebuild Account authority and issued-token projections, reconcile affected bindings, and pass the post-reset smoke gate before `resume`.
  - `session-schema-cleanup` may clean only the declared session-schema state. It may not authorize gameplay resume by itself; if the operation is followed by a gameplay-capable `resume`, it must execute the same authority/projection, affected-scope, and smoke gates as the other modes.
- A mode that cannot complete the required gates remains paused or failed and has no gameplay-resume path. `AWAITING_RESUME` is observational until the exact public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` checks pass and the controller records `RESUME_AUTHORIZED`.
- A strictly observational dry run is the only direct-finalization exception. It may perform discovery and validation without acquiring a gameplay fence or maintenance lock and without writing, deleting, rebuilding, or releasing any runtime state; after those no-effect conditions are durably recorded, it may transition directly to `finalized` and `SUCCEEDED`. Any run that acquires a fence or lock, mutates state, or could require a release effect is not observational and must follow the ordinary continuation, authorization, release, readback, and finalization lifecycle.
- `continueRecovery` is the sole public phase-continuation operation. It accepts only the exact durable `operationId`, canonical `expectedPhase=ready_to_reopen`, server-issued `maintenanceLockToken`, and immutable `evidenceRef`; validates the operation-owned scope, phase, fence, and evidence; and compare-and-sets only into `AWAITING_RESUME`. Exact-tuple retries return the recorded transition result, while any scope, phase, token, evidence, or concurrent-state mismatch fails closed. It cannot select an internal phase, authorize release, or reopen gameplay.
- `resume` is the separate public release-authorization gate. It accepts canonical `expectedPhase=awaiting_resume`, revalidates the same operation-owned scope, server-issued lock, immutable evidence, and required Account/replay proof, and records only `RESUME_AUTHORIZED`. Only the controller's internal fenced release phase may then apply each release effect under the retained fence and read back its durable/current state. An apply without a successful matching readback is incomplete; a failed or ambiguous effect after another may have applied enters `PARTIAL_RELEASE_RECONCILING` / persisted `partial_release_reconciling`, contains or re-fences already-released effects, and may return to `releasing` only after containment is durably observed. Only complete per-effect apply-and-readback verification may finalize the operation, record `SUCCEEDED`, release the fence, or permit new-epoch ticks.
- The tool advertises and accepts only scope levels implemented and proved by the runtime. Region, tenant, or cluster scope is added only with an authoritative durable inventory and end-to-end recovery proof for that scope.
- Raw coordination writes are break-glass only. They require actor, reason, deployment and scope audit, the covering reset or cleanup, and a passing post-check before gameplay resumes.

### Recovery Phase Representation

This maintenance surface uses the recovery phase representation defined by [ADR 0015](./adr-0015-online-backup-and-environment-wide-cold-start-recovery.md). The durable controller preserves the broader recovery contract's exact phase identifiers: `PAUSED`, `collecting`, `ready_to_reopen`, `AWAITING_RESUME`, `RESUME_AUTHORIZED`, `releasing`, `PARTIAL_RELEASE_RECONCILING`, and `finalized`; `PAUSED` is the fenced pause state and `collecting` is the pre-release failure/retry phase. `PARTIAL_RELEASE_RECONCILING` is the fail-closed post-release partial state and is persisted in recovery projections as `partial_release_reconciling`. `SUCCEEDED` is a separate terminal operation-status field, not a controller phase or valid `expectedPhase`; it may be recorded only after phase `finalized`, and mutating operations must also have all applicable release postconditions durably observed. The strictly observational no-fence/no-lock/no-mutation dry run defined above has no release effects or release postconditions, but it still reaches `finalized` before recording `SUCCEEDED`. Public `expectedPhase` is a lower-snake-case wire precondition mapped exactly as follows; case variants and aliases are invalid:

| Public wire `expectedPhase` | Durable controller state checked or recorded |
| --- | --- |
| `ready_to_reopen` | `ready_to_reopen` |
| `awaiting_resume` | `AWAITING_RESUME` |

`continueRecovery` accepts only `ready_to_reopen` and records `AWAITING_RESUME`; `resume` accepts only `awaiting_resume` and records `RESUME_AUTHORIZED`. `PAUSED`, `collecting`, `RESUME_AUTHORIZED`, `releasing`, `PARTIAL_RELEASE_RECONCILING`, and `finalized` remain internal durable phases and are never caller-supplied `expectedPhase` values. A persisted recovery projection maps `PARTIAL_RELEASE_RECONCILING` to exactly `partial_release_reconciling`; it does not create a second phase or permit a caller alias. Durable phase state and audit use the canonical phase identity and its documented persisted representation; terminal status validation separately requires `phase=finalized` before `status=SUCCEEDED`. Public request parsing and examples use the wire spelling, with no case normalization or aliasing.

### Public Release-Lock Safety Contract

The canonical public maintenance-lock release operation is `releaseMaintenanceLock`. Its CLI spelling is `coordination-maintenance release-lock`. The request wire fields are exactly:

| Field | Contract |
| --- | --- |
| `operationId` | The existing durable recovery operation identifier. |
| `scope` | The typed scope kind and exact recorded selectors; missing, extra, or non-matching selectors are rejected. |
| `maintenanceLockToken` | The server-issued opaque lock token for that operation, supplied to the CLI through the permissioned token-file or protected-FD form rather than a command-line value. |
| `reason` | A non-empty operator reason recorded in the abandonment audit. |
| `evidenceRef` | The immutable evidence reference for the abandonment decision. |

The authenticated actor or workload identity, deployment boundary, and authorization are request context, not caller-supplied wire fields. `releaseMaintenanceLock` has no public `expectedPhase` field: the controller resolves the current durable phase for `operationId` and compare-and-sets only from that exact observed phase. It accepts `PAUSED`, `collecting`, `ready_to_reopen`, or `AWAITING_RESUME`; it rejects `RESUME_AUTHORIZED`, `releasing`, `PARTIAL_RELEASE_RECONCILING`, `finalized`, unknown phases, and any operation whose current state cannot be resolved unambiguously. `RUNNING`, `FAILED`, and `SUCCEEDED` are operation statuses, not phases; status does not replace the exact phase check. A `RUNNING` operation is rejected by default. The only exception is an explicit authorized running-operation abandonment gate: the actor must hold the dedicated running-abandonment authorization and `evidenceRef` must identify the approved abandonment or scope-adoption decision. For that exception, the controller must atomically persist the abandonment-pending state, advance the fencing generation, and stop or fence every worker lease for that operation before invoking the lock-release effect; it must durably observe that no worker can make further progress. If that transition or observation cannot be completed atomically and unambiguously, the request fails closed and the maintenance lock and gameplay fence remain in place. The durable record preserves the exact observed phase, the pre-abandonment fencing generation, the new fencing generation, and the existing public request fields; the generation advance is the required fencing action, not a phase normalization or scope transfer.

The controller first durably records the validated abandonment intent as nonterminal `PENDING_RELEASE`, including the exact request tuple, observed phase, and fencing generation. That intent is committed before any lock-release effect is invoked or observed, and the operation remains paused and non-gameplay-safe while release is pending. The controller then applies the lock-release effect under that recorded fence and observes its result. Until that observation succeeds, the response is nonterminal:

```json
{
  "operationId": "...",
  "scope": { "kind": "...", "selectors": {} },
  "phase": "<recordedObservedPhase>",
  "outcome": "PENDING_RELEASE",
  "maintenanceLockReleased": false,
  "gameplayResumeAuthorized": false,
  "evidenceRef": "...",
  "recordedAt": "..."
}
```

After the same fenced release effect has been observed, the terminal response is the stable record:

```json
{
  "operationId": "...",
  "scope": { "kind": "...", "selectors": {} },
  "phase": "<recordedObservedPhase>",
  "outcome": "ABANDONED",
  "maintenanceLockReleased": true,
  "gameplayResumeAuthorized": false,
  "evidenceRef": "...",
  "recordedAt": "..."
}
```

The response contains no raw maintenance-lock token. Both `PENDING_RELEASE` and `ABANDONED` responses preserve the exact durable phase observed when the abandonment intent was recorded; they do not normalize that phase to `collecting` or recompute it after the release effect. An exact-tuple retry while the durable record is `PENDING_RELEASE` resumes or reconciles the same fenced, idempotent release effect; it never starts a second effect. `ABANDONED` is terminal only after lock release has been observed and that observation is durably committed. A retry after that point returns the recorded terminal result without repeating release.

The public maintenance-lock release control is an audited abandonment operation, not a shortcut to resume or a general unlock API. It must satisfy all of the following gates:

- The request names the existing `operationId`, exact recorded scope selectors, server-issued `maintenanceLockToken`, an authenticated authorized actor, a non-empty reason, and an immutable `evidenceRef`. The operation record and its fencing generation are the authorities; caller-supplied scope or token metadata cannot create or transfer ownership.
- The controller resolves the token to the active operation's stored token digest and fence, verifies the deployment boundary, operation class, actor authorization, and exact scope, then compare-and-sets from the exact durable phase it observed. Release is permitted only for a paused/fenced `PAUSED`, `collecting`, `ready_to_reopen`, or `AWAITING_RESUME`; it rejects `RESUME_AUTHORIZED`, `releasing`, `PARTIAL_RELEASE_RECONCILING`, `finalized`, concurrent advancement, and every phase/evidence mismatch. A `RUNNING` status additionally requires the explicit running-operation abandonment gate above, with worker stop/fence observation committed before `PENDING_RELEASE` can invoke the release effect; otherwise it is rejected without changing the fence.
- A release request is idempotent on exactly `(operationName=releaseMaintenanceLock, operationId, recorded scope selectors, maintenance-lock identity, authenticated actor/workload identity, reasonDigest, evidenceRef identity)`, where `reasonDigest` is the canonical digest of the supplied reason and maintenance-lock identity is the stored token digest plus fencing generation. The first valid request durably records `PENDING_RELEASE` before invoking the fenced release effect. An exact duplicate resumes that same effect and an already-observed effect is finalized without repetition; any different tuple returns `IDEMPOTENCY_CONFLICT` without invoking the release effect, while a stale token, expired lock, missing evidence, or ambiguous external state fails closed and leaves the fence in place.
- The terminal `ABANDONED` result records the actor, reason, evidence, phase, and fence outcome only after the same fenced release effect has been observed and that observation commits durably. Until then it retains `PENDING_RELEASE` and the paused/non-gameplay-safe state, and it cannot authorize `resume`, `AWAITING_RESUME`, traffic reopening, or a new operation implicitly.

## Consequences

- The safety and authority boundary remains strict while the supported operator surface is smaller and easier to release-test.
- Recovery orchestration can evolve internally without making every phase a stable public compatibility contract.
- Unusual incidents initially have fewer fine-grained manual controls and may require a broader supported reset or audited break-glass recovery.
- Specialist public verbs and wider scopes remain available as evidence-driven additions rather than mandatory upfront platform work.

## Alternatives Considered

### Publish Every Recovery Phase and Scope Upfront

This maximizes manual control, but commits the project to a large compatibility, authorization, documentation, and release-test surface before demonstrated operator need or runtime support exists.

### Permit Routine Direct Redis Administration

This is operationally flexible, but bypasses typed key ownership, reset ordering, audit, fencing, and post-recovery proof.

## Implementation and Proof Obligations

- Provision and statically verify separate application and read-only operator ACLs for Coordination and Cache Redis.
- Implement the bounded maintenance entrypoints through owned key and mutation helpers, with one audited resumable recovery workflow and explicit failure state.
- Prove internal pause fencing, durable affected-scope inventory, same-operation continuation, mode-to-phase restrictions, reset ordering, Account generation and issued-token projection rebuild, covering cleanup, smoke gating, tuple-bound resume audit, and refusal to resume an unsafe scope. Every mode capable of gameplay resume must prove the same required authority, affected-scope, and post-recovery smoke gates.
- Reject unsupported scopes and direct service mutation paths rather than silently degrading them.
- Add a specialist public verb or wider scope only with a concrete incident workflow and focused release proof.

## Reversibility and Revisit Triggers

The public surface can grow without changing the Redis authority model. Revisit when repeated incidents require independently resumable phases, when tenant or cluster recovery has a durable inventory and proof, or when a supported external operations API becomes preferable to the repository-owned tool.
