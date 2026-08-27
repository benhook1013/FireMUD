# ADR 0172: Parent Event and Frozen Handler Execution Identity

## Status

Accepted

Supersedes [ADR 0001](./adr-0001-scripting-event-ingress-idempotency-identity.md).

## Implementation Status

This decision is not implemented. Durable parent-event identity, frozen handler manifests, binding-scoped execution identity, activation-epoch fencing, and the required proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-21
- Human review disposition: Revised
- Review source: `AUTO-01`
- Decision date: 2026-07-21
- Decision key: `AUTO-01`
- Primary capability: `AS-1.1` automation trigger identity
- Affected capabilities: `AS-1.5`, `SF-1.2`, `SF-2.3`, `GR-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review plus disjoint evidence checks of event ingress, handler fan-out, plugin bindings, activation ABA, mutable routing provenance, schema uniqueness, and retry behavior

## Context

ADR 0001 calls both an incoming event and an individual resolved handler a Trigger Identity. Later documents distinguish event-scope admission from handler-scoped execution, but the field tables omit plugin `bindingId` while current storage puts mutable slugs and pointer version into ingress uniqueness. Current handler dedupe can collapse distinct plugin bindings, while a changed routing pointer can make a retry fan out again.

## Decision

Automation uses two durable identities.

The **parent event identity** represents one producer event before handler resolution. It is a stable producer-namespaced `scriptEventId` within its immutable tenant/runtime or dry-run scope. The first request binds that identity to a canonical digest of the normalized payload, event schema, source identity, required runtime/version fences, due point and trigger mode where applicable, and dry-run namespace. Reuse with a different digest is an idempotency conflict, not a new event or successful replay.

World and realm slugs, admission-pointer version, and other selector/routing observations are stored and validated as provenance. They do not become dedupe dimensions that allow one logical producer event to be recreated after routing metadata changes.

On first successful admission, Automation atomically freezes the complete resolved handler manifest and its deterministic `handlerSequence`. A retry reads that manifest rather than resolving the then-current handler set.

Each manifest entry receives a **handler execution identity**, which is the durable child/lineage identity for handler work and execution. It is deliberately distinct from the handler-scoped Trigger Identity and from the Command-Handoff Identity:

- core script: `{parentEventId, scriptId}`;
- plugin handler: `{parentEventId, pluginId, pluginVersionId, bindingId, pluginActivationEpoch}`.

Core scripts execute once per parent event even if more than one matching scope resolves the same `scriptId`. Plugins are binding-scoped because one plugin version may intentionally contribute several handlers. A future requirement to invoke one core script independently through several bindings must introduce stable core binding identity explicitly rather than relying on duplicate resolution.

The handler-scoped Trigger Identity used for admission, trigger audit, and logical retry is the full applicable event identity plus plugin `{pluginId, pluginVersionId, bindingId}`; it does not add `pluginActivationEpoch`. The handler execution identity above is derived from that admitted parent/handler pair and adds the captured activation epoch for durable child work, execution lineage, and fence comparison. Retries and recovery reuse the same execution identity and never recompute it from current plugin state. Command-Handoff Identity remains its own source/target scope plus dispatch/ordinal identity; the activation epoch travels as immutable fence evidence rather than becoming a command-child uniqueness dimension.

For scheduler triggers, the captured activation epoch is included in the schedule-candidate and firing-claim identity and in the derived parent `scriptEventId`; a new activation therefore creates a fresh parent and, transitively, a fresh handler Trigger Identity and execution identity. For ordinary producer ingress, retries reuse the original parent `scriptEventId` and frozen manifest; a changed or unavailable activation epoch fails closed rather than creating a second child. This preserves the separation while preventing version-ABA aliasing.

Plugin activation epoch is frozen with the manifest and carried through work, audit, handoff, and timers so a version reactivation cannot resurrect displaced work. Plugin-owned scheduler identities also include stable schedule/binding identity, activation epoch, and the exact due point.

## Consequences

- Producer retries cannot gain new handlers, lose existing handlers, or duplicate work merely because selectors, pointer state, or the active plugin set changed.
- Distinct plugin bindings no longer collide on a shared `scriptId`.
- Idempotency conflicts expose producer bugs instead of silently returning an unrelated prior outcome.
- The model adds a durable parent record, canonical request digest, frozen manifest, and child uniqueness constraints.
- Current proto, schema, binding persistence, handler lookup, timer derivation, and proof require convergence.

## Alternatives Considered

### One Flat Composite Identity Per Handler

This avoids a parent row but retries must re-resolve handlers. A changed binding set can therefore add or omit executions for the same producer event.

### Globally Unique `scriptEventId` Alone

This makes indexes smaller but still requires digest conflict detection and a frozen handler manifest. It is an acceptable physical key for the parent only when producer namespace/scope is enforced.

### Put Mutable Routing Selectors in Uniqueness

This preserves exact ingress observations but makes a pointer or slug change manufacture a second logical event. Provenance belongs in the immutable digest/audit, not dedupe authority.

## Implementation and Proof Obligations

Implementation must persist stable plugin `bindingId` and `pluginActivationEpoch`, remove mutable selector provenance from uniqueness, add canonical digest conflict handling, freeze resolution atomically, and make every work/audit/handoff child reference its parent and handler identity. Proof must cover concurrent duplicate ingress, changed payload under one ID, pointer change, binding-set change between attempts, several bindings from one plugin, version ABA reactivation, timer due-point identity, dry-run separation, and crash recovery before and after manifest creation.

## Reversibility and Revisit Triggers

The physical parent key may evolve without changing the two-level semantic boundary. Revisit core-script coalescing only for an explicit product feature requiring multiple independent invocations of one core script from one event.
