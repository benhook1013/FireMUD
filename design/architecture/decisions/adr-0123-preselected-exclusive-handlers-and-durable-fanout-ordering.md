# ADR 0123: Preselected Exclusive Handlers and Durable Fan-Out Ordering

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-13`
- Primary capability: `AS-1.1` trigger and event ingress contracts
- Affected capabilities: `AR-1.1`, `AS-1.3`, `PO-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of deterministic multi-handler ordering, failure isolation, creator expectations, plugin precedence, exclusive bindings, fairness, and durable command application

## Context

One scripting event can resolve core-script and plugin handlers from several binding scopes. FireMUD needs their visible effects to be reproducible, but inserting work items in a deterministic order is not enough: independent queues, workers, retries, and command handoffs can reorder them later.

The prior exclusivity wording also allowed an exclusive binding to short-circuit when encountered during handler iteration. That could let earlier siblings run before the supposedly exclusive handler, make behavior depend on sort position, and leave creators unable to reason about whether exclusivity means replacement or merely early termination.

Failures and capacity policy are separate concerns. One non-exclusive handler's quota denial, sandbox error, compilation failure, or empty result must not cancel unrelated sibling handlers.

## Decision

Automation & Scripting first resolves the complete handler set for the concrete event scope, including every matching core-script and active-plugin binding across all applicable binding scopes.

### Exclusive Resolution

Before fan-out, Automation determines whether the complete resolved set contains an authorized exclusive binding.

- At most one binding in that complete resolved set may be exclusive.
- An authorized exclusive binding is selected as the sole handler for the event scope. No sibling is admitted, evaluated, handed off, or applied before or after it.
- If the exclusive handler is denied or fails, FireMUD records that handler outcome and does not fall back to siblings.
- Exclusive selection is based on the complete concrete resolved set, not only bindings with the same declared selector shape.
- Game Design validates conflicts and authorization that are knowable at publish time. Automation revalidates against the base scripts and active plugin tuple at instance activation and fails closed if the complete resolved set is ambiguous or unauthorized.
- Plugin bindings are non-exclusive by default. Plugin exclusivity requires an explicit operator grant and audit evidence bound to the plugin version, binding, target policy scope, and granting actor.

Runtime resolution must not reinterpret an invalid multi-exclusive set by sort order. It rejects the event scope with a bounded policy outcome until publication or activation state is repaired.

### Non-Exclusive Ordering

If no exclusive binding is selected, Automation orders all resolved handlers by:

1. `orderIndex ASC`;
2. `handlerType ASC`, with `SCRIPT` before `PLUGIN` unless an explicit operator-controlled policy defines another precedence; and
3. stable handler identity ascending, using `scriptId` for core scripts and `(pluginId, bindingId)` for plugin bindings.

Resolution assigns each handler a durable `handlerSequence` or equivalent stable ordinal. The event resolution record, handler audit, work item, generated-command set, handoff, and command-application contract preserve this value. Commands within one handler also retain their stable handler-local output order, so the effective application order for one event is the handler sequence followed by the handler-local command order.

Queue insertion order, database timestamps, generated row IDs, worker claim order, scheduling priority, and retry timing are not ordering authority. The durable resolution representation must make the complete ordered handler set known so a consumer can distinguish a delayed lower sequence from one that reached a terminal failure or produced no commands.

### Failure Isolation and Capacity

Each non-exclusive handler has its own Trigger Identity, quota decision, audit lifecycle, work item, and terminal outcome. A handler failure does not cancel, suppress, or rewrite a sibling outcome. Once an earlier handler reaches a terminal outcome, later handler commands remain eligible for ordered application even if the earlier handler failed or produced no commands.

`orderIndex` and `handlerSequence` express semantic effect order. `priorityTag`, quotas, capacity reservations, and fairness tiers remain operational scheduling policy and cannot silently change semantic application order. Core scripts and plugins remain subject to their own quotas and sandbox checks, including when one binding is exclusive.

## Consequences

- Exclusive means one selected handler, not “run handlers until this binding is encountered.”
- Creators receive deterministic cross-handler command effects despite asynchronous evaluation and handoff.
- A broken non-exclusive handler cannot suppress unrelated content, while a deliberately exclusive handler has no implicit fallback.
- Plugin replacement behavior remains possible but requires explicit operator authority and audit.
- Durable sequence and completeness metadata add storage, reconciliation, buffering, and downstream command-order enforcement work.
- Capacity policy may delay handlers, but it cannot reorder their gameplay effects.

## Alternatives Considered

### Always Use Non-Exclusive Fan-Out

Run every matching handler and require operators to disable competitors when replacement behavior is desired. This is the strongest simpler alternative and provides the clearest fairness model, but it cannot express an intentionally authorized event replacement without mutating the rest of the binding set.

### Short-Circuit When an Exclusive Handler Is Encountered

Sort all handlers and stop after reaching an exclusive binding. Rejected because siblings ordered before it would already have run, so exclusivity would depend on priority rather than mean sole ownership of the event scope.

### Parallel Unordered Fan-Out

Evaluate and apply handlers as capacity becomes available. Rejected because player-visible command effects and conflicts would depend on worker timing, retries, and deployment shape.

### Designer-Controlled Plugin Precedence and Exclusivity

Let tenant content assign plugins ahead of core scripts or claim exclusivity without operator policy. Rejected because a plugin could displace game-owned behavior without the explicit authority and audit expected for that player-visible override.

## Implementation and Proof Obligations

The current implementation does not satisfy this decision. Binding reads sort by persisted priority and `scriptId`, then resolve plugin ownership afterward; they do not implement the handler-type tie-breaker. Work items and downstream commands do not carry a durable handler sequence, so current ordering depends on insertion, queue, and claim behavior. Ingress does not use `requiresExclusiveEvent` when resolving handlers. Script-definition writes accept the flag without the required grant, while plugin activation preflight checks only identical declared target-scope keys and rejects conflicts rather than proving sole-handler selection across the complete concrete resolved set.

Proof must cover mixed core/plugin bindings; equal and unequal order indexes; stable plugin binding identity; all applicable binding scopes resolving to one concrete target; zero, one, and multiple exclusive claims; missing, stale, and revoked operator grants; publish-known and activation-only conflicts; exclusive success, denial, sandbox failure, and infrastructure failure without sibling fallback; non-exclusive quota denial, compilation failure, sandbox failure, empty output, and successful siblings; crash and retry at resolution, work persistence, evaluation, handoff, and application; queue rebuild and concurrent workers; delayed lower sequences; and ordered multi-command output through final gameplay application.

Proof must also show that `priorityTag` and fairness throttling can change execution timing without changing semantic command order, and that runtime detects incomplete or contradictory sequence metadata rather than silently applying a higher sequence first.

## Reversibility and Revisit Triggers

The sequence encoding, resolution-manifest representation, buffering mechanism, and operator-grant storage may evolve while retaining pre-fan-out exclusive selection, complete-set validation, explicit plugin authority, non-exclusive failure isolation, and durable end-to-end semantic ordering. Revisit the decision if measured latency makes cross-handler ordered application unacceptable, or before allowing tenant designers to grant plugin precedence or exclusivity without operator policy.

## Required Documentation Alignment

- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-runtime-execution.md`
- `design/architecture/microservices/game-design-service/modding-framework.md`
