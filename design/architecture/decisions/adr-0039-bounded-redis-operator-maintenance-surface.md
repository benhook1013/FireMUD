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
- Normal operator mutations use version-matched, owner-supported tooling. The initial public maintenance surface is bounded to `status`, one `recover --mode <replay-first|reset|session-schema-cleanup>` operation, `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`, `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`, and a maintenance-lock release operation. Pause-and-lock is an internal fenced phase of the durable recovery operation, not a standalone public verb.
- The single public recover/reset operation owns the required ordered phases, including durable epoch handling, Account authority-projection rebuild, ledger and command convergence, metadata initialization, session policy, and the post-reset smoke gate. Reset recovery must rebuild and verify the issuer, account, tenant, and membership generation projections plus issued-token projections from Account durable authority before smoke can pass or `resume` can authorize release. Those phases may have internal APIs and focused tests, but `reset`, `reconcile-ledger`, `converge-commands`, `init-meta`, `rebind-sessions`, `smoke-check`, and `session-cleanup` are not separately public operator verbs.
- Each recovery mode has an explicit phase contract, and mode selection never bypasses safety gates:
  - `replay-first` may replay only the declared coherent durable work and must still complete authority/projection proof, affected-scope reconciliation, and the post-recovery smoke gate before any gameplay-capable `resume`.
  - `reset` must complete the full destructive reset ordering, rebuild Account authority and issued-token projections, reconcile affected bindings, and pass the post-reset smoke gate before `resume`.
  - `session-schema-cleanup` may clean only the declared session-schema state. It may not authorize gameplay resume by itself; if the operation is followed by a gameplay-capable `resume`, it must execute the same authority/projection, affected-scope, and smoke gates as the other modes.
- A mode that cannot complete the required gates remains paused or failed and has no gameplay-resume path. `AWAITING_RESUME` is observational until the exact public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` checks pass and the controller records `RESUME_AUTHORIZED`.
- The tool advertises and accepts only scope levels implemented and proved by the runtime. Region, tenant, or cluster scope is added only with an authoritative durable inventory and end-to-end recovery proof for that scope.
- Raw coordination writes are break-glass only. They require actor, reason, deployment and scope audit, the covering reset or cleanup, and a passing post-check before gameplay resumes.

### Public Release-Lock Safety Contract

The public maintenance-lock release control is an audited abandonment operation, not a shortcut to resume or a general unlock API. It must satisfy all of the following gates:

- The request names the existing `operationId`, exact recorded scope selectors, server-issued `maintenanceLockToken`, an authenticated authorized actor, a non-empty reason, and an immutable `evidenceRef`. The operation record and its fencing generation are the authorities; caller-supplied scope or token metadata cannot create or transfer ownership.
- The controller resolves the token to the active operation's stored token digest and fence, verifies the deployment boundary, operation class, actor authorization, and exact scope, then compare-and-sets from the exact durable phase it observed. Release is permitted only for a paused/fenced `PAUSED`, `ready_to_reopen`, `AWAITING_RESUME`, or explicitly recorded pre-release failure phase; it rejects `RUNNING`, `releasing`, `finalized`, concurrent advancement, and every phase/evidence mismatch.
- A release request is idempotent on the exact operation-owned tuple `(operationId, recorded scope, lock identity, actor, reason digest, and evidence identity)`. A duplicate returns the recorded abandonment result without repeating release effects; a different tuple, stale token, expired lock, missing evidence, or ambiguous external state fails closed and leaves the fence in place.
- Successful abandonment records the actor, reason, evidence, phase, and fence outcome durably before any lock-release effect is observed. It retains the paused/non-gameplay-safe state and cannot authorize `resume`, `AWAITING_RESUME`, traffic reopening, or a new operation implicitly.

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
