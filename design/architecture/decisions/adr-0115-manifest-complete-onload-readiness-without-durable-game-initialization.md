# ADR 0115: Manifest-Complete onLoad Readiness Without Durable Game Initialization

## Status

Accepted

## Implementation Status

The current path admits bounded tenant-readiness `onLoad` work and exposes a readiness projection, but it does not yet seal or prove the immutable complete handler manifest required by this ADR. Monotonic publication acceptance and late-completion fencing, along with readiness-owner recovery and terminalization of stale `ONLOAD_RUNNING` work, remain target convergence and proof gaps; the generic replay behavior described in the automation tracker is implementation drift rather than supported readiness recovery.

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-05`
- Primary capability: `AS-1.2` sandboxed game-authored behavior
- Affected capabilities: `AR-1.5`, `AS-1.6`, `GR-1.4`
- Decision owner: FireMUD human product and architecture owner
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SCRIPT-05`

## Context

FireMUD runs tenant-scoped `onLoad` work after a script patch passes static validation and compilation but before that patch becomes `READY`. The hook can validate bounded patch configuration and warm recomputable caches, but must not become an implicit migration or gameplay-state initialization mechanism.

Readiness cannot be inferred from whatever work happens to be observed. A missing enqueue, admission failure, incomplete handler enumeration, or empty query result must not accidentally make a non-empty patch ready. Reordered publication delivery also must not let an older completion reopen a superseded candidate.

## Decision

`onLoad` is tenant/script/patch readiness work limited to bounded validation and optional warming of recomputable caches. It is never an authority for durable game initialization.

Each immutable script patch declares the exact set of required `onLoad` handler identities, including an explicitly empty set. Automation marks `<tenantId, scriptPatchVersion>` `READY` only when every declared handler has been admitted and reached one successful logical terminal outcome. Missing expected work, failure to admit a required handler, or failure of any required handler prevents `READY`; observing no work is sufficient only when the immutable manifest declares zero handlers.

Each handler uses one stable logical execution identity for that tenant and patch. Bounded infrastructure retries reuse that identity and deterministic `scriptEventId`; “once” means one successful logical outcome, not one physical attempt. Duplicate or late attempts cannot create a second readiness result.

Tenant readiness accepts publication candidates under a monotonic accepted-publication sequence. Only a candidate with a greater accepted sequence may supersede the current non-terminal candidate. Supersession is terminal for the older candidate; work not yet started is canceled, and late completion may be retained for audit but cannot move the older candidate to `READY` or reopen readiness.

Cache warming is optional and recomputable. Shared-cache warming may be performed only when it is loss-safe and recomputable; it is not a required readiness outcome and cannot make a patch `READY` or otherwise affect readiness. Cache loss after `READY` cannot invalidate gameplay correctness, and successful tenant-level `onLoad` does not promise every worker is warm. Durable or semi-durable gameplay artifacts, and any non-recomputable shared effect, are prohibited. Schema evolution, authored game data, player-state transformation, and instance initialization use their owning migrations, publication, cutover/remap, or instance-lifecycle workflows. `onLoad` does not write durable or semi-durable gameplay artifacts to shared stores.

## Consequences

- Patch readiness is complete against an immutable expected set instead of inferred from observed queue activity.
- An explicitly handler-free patch can become ready without synthetic work, while missing work for a non-empty manifest fails closed.
- Infrastructure may retry safely without changing logical readiness identity.
- Publication reordering and late completion cannot resurrect a superseded candidate.
- Durable schema, content, player, and instance changes remain visible in their owning workflows.

## Implementation and Proof Obligations

The immutable patch artifact and readiness projection must expose enough identity to compare declared, admitted, and terminal handler sets. Proof must cover complete and empty manifests, missing admission, capacity failure, logical/sandbox failure, bounded retry under one identity, duplicate completion, reordered publication, monotonic supersession, cancellation of not-yet-started work, late old-candidate success, cache loss after `READY`, and rejection of durable/shared side effects.

## Related Contracts

- [Scripting overview](../system-architecture-scripting.md)
- [Scripting DSL reference and lifecycle](../system-architecture-scripting-dsl-reference-and-lifecycle.md)
- [Scripting runtime execution](../system-architecture-scripting-runtime-execution.md)
- [Scripting event registry](../system-architecture-scripting-event-registry.md)
