# ADR 0075: Depth, Cost, and Count Bounds for Generated Effect Chains

## Status

Accepted

## Implementation Status

Generated-chain depth, count, cost, and per-target limits are target state; canonical admission, authored classification, suppression evidence, metrics, and focused proof remain incomplete.

## Canonical Design

- [Tick Concepts and Invariants](../system-architecture-tick-concepts-and-invariants.md)
- [Tick Execution Flows](../system-architecture-tick-execution-flows.md)
- [Transaction Strategies](../system-architecture-transactions.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `TICK-11`
- Primary capability: `GR-4.1` typed and safe gameplay effects
- Affected capabilities: `GR-1.2`, `AS-1.2`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with depth-only, unbounded-chain, and compensating-rollback alternative analysis
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `TICK-11`

## Context

Generated effects let authored gameplay produce immediate follow-up work such as secondary hits, explosions, traps, and scripted reactions. A depth ceiling prevents infinite recursion, but depth alone does not bound broad fan-out, repeated work against one target, or the total execution cost of a root chain.

The limit contract must remain deterministic across execution and replay, preserve already committed gameplay truth, distinguish required effects from optional embellishments, and provide enough evidence to diagnose an individual suppression without putting high-cardinality identity into metrics.

## Decision

The platform retains a hard generated-effect depth ceiling with `8` as the shared bootstrap default. Each root-generated chain is additionally constrained by:

- a deterministic total root-chain effect-count budget;
- a deterministic total root-chain cost budget; and
- a deterministic per-target effect cap.

Platform hard ceilings bound every setting. Operators may configure lower environment bounds, and an authored feature may lower its own limits further, but a feature can never exceed the resolved operator or platform bounds.

Every generated child carries an immutable parent identity, immutable root identity, depth equal to `parent.depth + 1`, and a deterministic child ordinal. The ordinal is covered by the request digest. Replay reuses the recorded child identity, lineage, ordinal, and budget accounting; it does not increment them again or mint a replacement identity.

Count, cost, per-target, and depth admission are evaluated deterministically for the root chain. When admitting a new child would exceed a limit, only that child is suppressed. A suppressed child is not enqueued or applied, and no already committed parent or earlier child is rolled back.

Every authored child is classified as required or optional for its command outcome. The outcome resolver uses that classification and the committed results to derive `SUCCESS`, `PARTIAL`, or `FAILED` truthfully. Suppression must not be hidden as success when it prevented a required result, and committed parent work must not be reported as though it were rolled back.

Every suppression produces durable evidence containing:

- root and parent identities;
- authored feature, script, and version identity;
- deterministic child ordinal;
- limit reason: depth, count, cost, or per-target;
- actual and configured limit values;
- required or optional classification; and
- resulting player outcome.

Prometheus-facing metrics use only bounded dimensions for suppression reason, depth and cost bands, suppression ratio, required versus optional classification, and partial or failed outcomes. Raw root, parent, feature, script, version, target, or other high-cardinality identities are available through audit queries, not Prometheus labels.

Alerting follows the impact class:

- a one-off optional suppression produces durable audit evidence only;
- repeated optional suppression for the same authored behavior produces a designer warning;
- any required suppression promptly raises a gameplay/correctness alert; and
- sustained aggregate suppression or tick degradation raises an operational page.

## Consequences

- Runaway depth, broad fan-out, excessive aggregate cost, and repeated concentration on one target are all bounded.
- Deterministic lineage, ordinals, digests, and accounting make admission and suppression replay-stable.
- Already committed gameplay remains authoritative when a later child is suppressed.
- Required and optional classifications make player outcomes and alerts reflect actual gameplay impact.
- Durable per-suppression evidence supports precise investigation while bounded metrics avoid cardinality growth.
- Root-chain accounting, authored cost declarations, outcome classification, audit retention, and alert evaluation add implementation and authoring overhead.
- Legitimate large chains may be truncated until platform, operator, and feature bounds are tuned without exceeding the hard ceilings.

## Alternatives Considered

### Depth-Only Bounding

Rejected because a shallow chain can still generate an excessive number of children, consume excessive total cost, or concentrate work repeatedly on one target.

### Unbounded Generated Chains

Rejected because authored or scripted recursion and fan-out could exhaust tick capacity, degrade other gameplay, and make recovery time unbounded.

### Roll Back the Committed Parent on Overflow

Rejected because the parent may already be durably committed across authoritative owners. Retrospective rollback would contradict committed gameplay truth and require compensation semantics unrelated to admission of the new child.

## Implementation and Proof Obligations

Implement one canonical generated-chain admission and accounting path with the platform hard depth ceiling, shared depth bootstrap default of `8`, deterministic total count and cost budgets, per-target caps, operator bounds, and feature-lowering rules. Persist immutable parent and root identities, `parent.depth + 1`, digest-covered deterministic ordinals, required or optional classification, and the complete suppression evidence.

Prove hard-ceiling and lower-bound resolution; depth, count, cost, and per-target suppression at each boundary; deterministic admission order; cost accounting; immutable lineage and ordinals; request-digest validation; crash and replay without re-incrementing accounting or reminting identity; suppression of only the new child; preservation of committed parents and earlier children; truthful `SUCCESS`, `PARTIAL`, and `FAILED` derivation; complete durable evidence; bounded metric labels and bands; raw-identity exclusion from Prometheus; audit-query retrieval; one-off optional audit behavior; repeated optional designer warnings; prompt required-suppression alerts; and operational paging for sustained aggregate suppression or tick degradation.

The current implementation, configuration resolution, authored classification, observability, alerting, and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Bootstrap defaults, hard ceilings, operator bounds, feature-specific lower limits, cost weights, metric bands, suppression-ratio windows, and alert thresholds may evolve from measured gameplay and operational evidence without changing the authority, determinism, lineage, replay, or no-rollback rules. Revisit the decision if a concrete feature cannot fit within deterministic root-chain budgets, requires atomic all-or-nothing semantics across generated effects, or production evidence shows that the accounting model fails to bound tick degradation.
