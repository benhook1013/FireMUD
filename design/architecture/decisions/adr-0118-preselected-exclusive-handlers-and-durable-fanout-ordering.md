# ADR 0118: Preselected Exclusive Handlers and Durable Fan-Out Ordering

## Status

Accepted

## Implementation Status

The current implementation does not carry durable handler sequence through work and handoff, does not resolve complete core/plugin sets with the required total ordering key, and does not prove exclusive grants or sole-handler selection across scopes.

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-13`
- Primary capability: `AS-1.1` trigger and event ingress contracts
- Affected capabilities: `AR-1.1`, `AS-1.3`, `PO-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of deterministic multi-handler ordering, failure isolation, creator expectations, plugin precedence, exclusive bindings, fairness, and durable command application
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SCRIPT-13`

## Context

One scripting event can resolve core-script and plugin handlers from several binding scopes. Queue insertion order is insufficient because independent queues, workers, retries, and handoffs can reorder effects. Short-circuiting when an exclusive binding is encountered can run earlier siblings before the supposedly exclusive handler.

## Decision

Automation & Scripting resolves the complete concrete handler set for the event scope before fan-out, including every matching core-script and active-plugin binding across applicable scopes.

At most one authorized exclusive binding may exist in that complete set. When present, it is selected as the sole handler: no sibling is admitted, evaluated, handed off, or applied before or after it. Failure or denial does not fall back to siblings. Multiple or unauthorized exclusive claims fail closed. Plugin bindings are non-exclusive by default; plugin exclusivity requires an explicit operator grant and audit evidence bound to the plugin version, binding, target policy scope, and granting actor.

When no exclusive binding is selected, handlers are ordered by:

1. `orderIndex ASC`;
2. the normalized finite handler-kind rank ASC: `SCRIPT` has the default rank before `PLUGIN`, unless an explicit operator-controlled policy places `PLUGIN` ahead; and
3. the canonical stable handler-order identity ascending, as defined by the [DSL lifecycle owner](../system-architecture-scripting-dsl-reference-and-lifecycle.md#canonical-stable-handler-order-identity). This is the ordering projection owned by that document, not the complete Trigger Identity or an informal tie-breaker: core handlers include `scriptId` plus their applicable authored binding/scope identity, and plugin handlers include `(pluginId, pluginVersionId, bindingId)`.

Automation normalizes every resolved handler to the finite kind `SCRIPT` or `PLUGIN` before ranking; designers cannot control that rank. The tuple `(orderIndex, normalized handler-kind rank, canonical stable handler-order identity)` is the total ordering key before `handlerSequence` assignment. Duplicate identical normalized total keys are rejected; Automation must not invent an ordering from arrival, queue, or worker order.

Automation assigns each handler a durable `handlerSequence` or equivalent stable ordinal. The complete ordered resolution, handler work, generated commands, handoff, retries, and final command application preserve that sequence and each handler's local command order. Queue position, timestamps, row IDs, worker claims, scheduling priority, and retry timing are not ordering authority.

Each non-exclusive handler has an independent outcome. A terminal failure or empty output closes only that sequence position and allows later siblings to proceed; a delayed lower sequence is not a terminal failure, and missing or contradictory completeness metadata fails explicitly rather than degrading to arrival order.

## Consequences

- Exclusive means sole selected ownership, not runtime short-circuiting.
- Player-visible effects remain deterministic across asynchronous evaluation and handoff.
- Broken non-exclusive handlers do not suppress siblings, while exclusive handlers have no fallback.
- Durable sequence and completeness metadata add storage, reconciliation, buffering, and downstream order-enforcement work.
- Operational capacity policy may delay execution but cannot reorder semantic effects.

## Alternatives Considered

### Always Use Non-Exclusive Fan-Out

Rejected because authorized event replacement could not be expressed without changing the binding set.

### Short-Circuit When an Exclusive Handler Is Encountered

Rejected because siblings earlier in sort order could already run.

### Parallel Unordered Fan-Out

Rejected because worker timing, retries, and deployment shape would determine gameplay effects.

### Designer-Controlled Plugin Precedence and Exclusivity

Rejected because tenant content could displace game-owned behavior without explicit operator authority and audit.

## Implementation and Proof Obligations

Proof must cover mixed bindings, equal/unequal indexes, equal total ordering keys being rejected, complete core binding/scope identity, stable plugin identity, all applicable scopes, zero/one/multiple exclusive claims, missing or revoked grants, exclusive failures without fallback, isolated non-exclusive failures, retries and queue rebuilds, delayed sequences, and ordered multi-command application. Priority and fairness may change timing but not semantic order.

## Reversibility and Revisit Triggers

Sequence encoding, resolution-manifest representation, buffering, and operator-grant storage may evolve while retaining pre-fan-out exclusive selection, complete-set validation, explicit plugin authority, failure isolation, and durable ordering. Revisit if measured latency makes ordered application unacceptable or before tenant designers can grant plugin precedence without operator policy.

## Required Documentation Alignment

- [Scripting DSL reference and lifecycle](../system-architecture-scripting-dsl-reference-and-lifecycle.md)
- [Scripting runtime execution](../system-architecture-scripting-runtime-execution.md)
- [Game Design modding framework](../microservices/game-design-service/modding-framework.md)
