# ADR 0039: Bounded Redis Operator Maintenance Surface

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `PO-1.4` Operability, supportability, and incident response
- Affected capabilities: `SF-2.2`, `SF-1.3`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `REDIS-06`

## Context

Coordination Redis contains correctness-sensitive, short-lived runtime state. The existing design correctly denies routine interactive writes, but also specifies eleven public maintenance verbs and region, tenant, and cluster scope before most of that tooling or scope inventory exists. Publishing every recovery phase as an operator command increases release proof, compatibility, runbook, and misuse burden without improving the underlying recovery invariant.

## Decision

- Coordination and Cache Redis remain separate deployments and ACL domains. Human operator accounts are read-only by default.
- Application writes use owned, typed key and mutation helpers. Registered Lua scripts are required where atomic multi-key behavior needs them, not for every ordinary single-key mutation.
- Normal operator mutations use version-matched, owner-supported tooling. The initial public maintenance surface is bounded to `pause`, `status`, one `recover --mode <replay-first|reset|session-schema-cleanup>` operation, `resume`, and a maintenance-lock release operation.
- The single public recover/reset operation owns the required ordered phases, including durable epoch handling, ledger and command convergence, metadata initialization, session policy, and the post-reset smoke gate. Those phases may have internal APIs and focused tests, but `reset`, `reconcile-ledger`, `converge-commands`, `init-meta`, `rebind-sessions`, `smoke-check`, and `session-cleanup` are not separately public operator verbs.
- The tool advertises and accepts only scope levels implemented and proved by the runtime. Region, tenant, or cluster scope is added only with an authoritative durable inventory and end-to-end recovery proof for that scope.
- Raw coordination writes are break-glass only. They require actor, reason, deployment and scope audit, the covering reset or cleanup, and a passing post-check before gameplay resumes.

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
- Prove pause fencing, durable affected-scope inventory, reset ordering, covering cleanup, smoke gating, and refusal to resume an unsafe scope.
- Reject unsupported scopes and direct service mutation paths rather than silently degrading them.
- Add a specialist public verb or wider scope only with a concrete incident workflow and focused release proof.

## Reversibility and Revisit Triggers

The public surface can grow without changing the Redis authority model. Revisit when repeated incidents require independently resumable phases, when tenant or cluster recovery has a durable inventory and proof, or when a supported external operations API becomes preferable to the repository-owned tool.
