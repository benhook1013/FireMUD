# ADR 0120: Manifest-Complete onLoad Readiness Without Durable Game Initialization

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-05`
- Primary capability: `AS-1.2` sandboxed game-authored behavior
- Affected capabilities: `AR-1.5`, `AS-1.6`, `GR-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of patch-readiness completeness, retry identity, supersession ordering, cache warming, durable initialization, and failure semantics

## Context

FireMUD runs tenant-scoped `onLoad` work after a script patch has passed static validation and compilation but before that patch becomes `READY`. This hook can validate bounded patch configuration and optionally warm recomputable caches, but it must not become an implicit migration or gameplay-state initialization mechanism.

Readiness cannot be inferred safely from whatever work happens to be observed. A missing enqueue, admission failure, incomplete handler enumeration, or empty query result must not accidentally make a non-empty patch ready. Infrastructure retries also make a literal promise that code executes physically only once unrealistic. The meaningful guarantee is one successful logical outcome under a stable identity, with all attempts fenced to the same declared readiness candidate.

Concurrent or reordered publication delivery creates a related risk. A late completion for an older patch must not reopen that candidate after a newer accepted publication supersedes it, and arrival order alone must not define which publication is newer.

## Decision

`onLoad` is tenant/script/patch readiness work. It remains limited to bounded validation and optional warming of recomputable caches before a patch becomes tenant-`READY`. It is never an authority for durable game initialization.

Each immutable script patch declares the exact set of required `onLoad` handler identities. The manifest may declare an explicitly empty set. Automation & Scripting marks `<tenantId, scriptPatchVersion>` `READY` only when every declared handler has been admitted and has reached one successful logical terminal outcome. Missing expected work, failure to admit a required handler, or failure of any required handler prevents `READY`; observing no work is sufficient only when the immutable manifest explicitly declares zero handlers.

Each declared handler uses one stable logical execution identity for that tenant and patch. Bounded infrastructure retries reuse that identity and its deterministic `scriptEventId`. “Once” means one successful logical outcome, not that infrastructure may never repeat an execution attempt. Duplicate or late attempts cannot create a second readiness result.

Tenant readiness accepts publication candidates under a monotonic accepted-publication sequence. Only a candidate with a greater accepted sequence may supersede the current non-terminal candidate. Supersession is terminal for the older candidate. Work not yet started is canceled, and late completion may be retained for audit but cannot move the older candidate to `READY` or otherwise reopen readiness.

Cache warming is an optional optimization. Cache contents are ephemeral and recomputable; correctness cannot depend on them, cache loss after `READY` cannot invalidate gameplay correctness, and successful tenant-level `onLoad` does not promise that every worker is warm.

Durable initialization uses the lifecycle owned by the affected state:

- schema evolution uses deployment migrations;
- authored game data uses Draft and publication;
- player-state transformation uses an explicit cutover or remap workflow; and
- instance initialization uses a separately fenced instance-lifecycle workflow.

`onLoad` does not write durable or semi-durable gameplay artifacts to databases, Redis, object storage, or other shared stores, and the absence of an `onUnload` hook does not weaken this prohibition.

## Consequences

- Patch readiness is complete against an immutable expected set instead of inferred from observed queue activity.
- An explicitly handler-free patch can become ready without synthetic work, while missing work for a non-empty manifest fails closed.
- Infrastructure may retry safely without changing logical readiness identity or producing multiple successful outcomes.
- Publication reordering and late completion cannot resurrect a superseded candidate.
- Cache warming can improve first-use latency but cannot become a hidden correctness dependency or cluster-wide warmness claim.
- Durable schema, content, player, and instance changes remain visible in their owning migration and lifecycle mechanisms.

## Alternatives Considered

### Platform-Defined Validation Only

Eliminate author-executable `onLoad` handlers and permit only fixed platform validation steps. This is the strongest simpler alternative because the expected work and side-effect boundary are easier to prove. It is not selected because a bounded script-level validation and cache-warming extension point remains useful when governed by the exact manifest and sandbox contract.

### Infer Readiness from Observed Work

Mark a patch ready when no pending work is visible or all observed handlers succeeded. Rejected because missed admission, partial enumeration, or projection loss can make an incomplete patch appear ready. The immutable expected-handler set is the completion authority.

### Prohibit All Infrastructure Retry

Treat `onLoad` as one physical attempt and fail the patch after any infrastructure interruption. Rejected because ordinary worker and transport failures would make publication unnecessarily fragile. Stable logical identity and bounded retry preserve correctness without claiming exactly-once execution.

### Permit Durable Initialization with Cleanup Later

Allow `onLoad` to create shared gameplay state and add cleanup only when needed. Rejected because partial execution, retries, supersession, rollback, and the absence of a symmetric deactivation lifecycle would leave ownership and compensation ambiguous. Durable state changes use their explicit owning workflows.

## Implementation and Proof Obligations

The immutable patch artifact and readiness projection must expose enough identity to compare the declared handler set with admitted and terminal logical outcomes. Proof must cover a non-empty complete manifest, a missing required admission, missing expected work, an explicitly empty manifest, admission-capacity failure, logical and sandbox failure, bounded infrastructure retry under one identity, and duplicate completion.

Supersession proof must cover reordered and duplicate publication delivery, monotonic accepted-publication sequences, cancellation of not-yet-started work, and a late old-candidate success that is audited without changing terminal readiness. Cache proof must cover cold workers and cache loss after `READY` while preserving correctness. Side-effect proof must reject durable or semi-durable writes and route schema, authored-data, player-state, and instance initialization through their owning mechanisms.

This decision records the target contract and does not claim that the manifest-completeness, sequence-fencing, retry, cache-loss, or side-effect proof is already complete.

## Reversibility and Revisit Triggers

Handler-manifest encoding, retry limits, cache implementation, and workflow internals may evolve while preserving complete expected-work accounting, stable logical identity, monotonic supersession, and the prohibition on durable initialization. Revisit the extension point if author-executable readiness work proves unnecessary and platform-defined validation can replace it. Adding any durable state initialization to `onLoad` requires a new decision with explicit ownership, idempotency, rollback, cleanup, and cutover semantics.

## Required Documentation Alignment

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-runtime-execution.md`
