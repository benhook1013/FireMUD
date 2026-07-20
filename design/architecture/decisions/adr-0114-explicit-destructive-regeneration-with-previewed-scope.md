# ADR 0114: Explicit Destructive Regeneration with Previewed Scope

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `PROC-03`
- Primary capability: `AR-1.1` world, entity, rule, and content authoring
- Affected capabilities: `AR-1.5`, `AR-2.3`, `GR-2.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of regeneration, manual-edit preservation, destructive preview, reference safety, identity continuity, and merge complexity

## Context

Generated scaffolding often becomes manually authored content. Retry and reconciliation of the same historical generation revision must not erase later creator edits, but a creator also needs an intentional way to regenerate and replace a declared part of a Draft.

An epoch check prevents a stale write, but it does not tell the creator exactly what a destructive operation will delete, which external references will break, or which identifiers will survive. Conversely, a universal three-way merge between old generated output, locally edited content, and new generated output would require asset-specific semantic rules and could produce a syntactically valid but incoherent world.

The current mutation path implements scope epochs, `REPLACE_SCOPE`, and `SEED_APPEND_ONLY`, including deletion of generated rows in a declared subtree. It does not implement or prove a complete destructive preview, plan-digest binding, cross-boundary reference treatment, or semantic replacement mapping.

## Decision

Replaying one historical generation revision reproduces that revision and then reapplies all later manual revisions in their original order. The replay does not reinterpret that historical revision as permission to delete later edits.

A new generation revision may deliberately replace content through `REPLACE_SCOPE`. This is a new destructive operation, not reconciliation of the old revision. Where generation can safely add without rewriting or deleting existing authored content, `SEED_APPEND_ONLY` is the default.

Before accepting `REPLACE_SCOPE`, FireMUD produces an exact destructive plan for the declared scope. The preview identifies at least the rows and logical objects to create, retain, replace, and delete; affected references; identifier mappings; and blocking validation failures. The approved request carries a canonical plan digest bound to the current Draft scope epoch and the exact generation inputs. If the scope epoch or any plan input changes, the plan is stale and must be regenerated and approved again.

References crossing the replacement boundary must remain valid, be covered by an explicit typed mapping, or block the operation. The operation must not silently drop or heuristically retarget a reference.

Stable persisted identifiers are preserved only when the result represents the same logical object. A rename or regenerated representation of that same object may keep its identity when the plan proves continuity. A semantic replacement, split, merge, or re-scope uses explicit durable mappings rather than opportunistically reusing an identifier.

FireMUD does not provide a generic `O/L/N` merge for generated topology. Domain-specific planning assistance may be added later, but ambiguous local edits or semantic changes require explicit creator resolution.

## Consequences

- Normal replay preserves creator work made after generation.
- Deliberate regeneration can still replace an entire declared subtree, with its destructive effect visible before mutation.
- Exact preview and current-epoch binding prevent approval of one plan from authorizing a different deletion set after concurrent edits.
- Reference scanning and identity mappings add authoring-control-plane work and can block regeneration until the creator resolves dependencies.
- Conservative failure requires more manual resolution than a heuristic merge, but avoids silent loss or corruption.

## Alternatives Considered

### Generation Always Replaces Its Original Scope

Rejected because routine replay or reconciliation could erase manual work performed after the generator ran.

### Append-Only Generation Only

Rejected because creators sometimes need an intentional clean regeneration of a bounded area. Forcing manual deletion would be slower and no safer unless it used the same reference analysis.

### Epoch Check Without an Exact Preview

Rejected because freshness does not communicate the destructive set or prove reference and identity treatment. The plan digest binds informed approval to the operation that is actually executed.

### Generic Three-Way Merge

Rejected because topology, exits, bindings, identity, and cross-scope references need domain semantics. There is no safe universal field or JSON merge.

## Implementation and Proof Obligations

The planner must produce a deterministic canonical plan and digest from the current scope epoch, exact generator inputs, current scoped graph, and relevant cross-boundary references. Apply must atomically reject a digest mismatch, epoch advance, changed inputs, or changed reference facts and require replanning.

Proof must cover preservation of later manual revisions during historical replay; safe append-only behavior; exact create/retain/replace/delete previews; concurrent manual edits after preview; inbound and outbound cross-boundary references; explicit mappings for renames, semantic replacements, splits, and merges; stable identity only for the same logical object; retry of the accepted plan; and no partial persistence on validation or apply failure.

The live World Draft mutation substrate proves scoped epoch checks and bounded `REPLACE_SCOPE` / `SEED_APPEND_ONLY` mutations. It does not yet provide or prove the required preview, plan digest, reference analysis, or identity-mapping contract.

## Reversibility and Revisit Triggers

Preview presentation and domain-specific planning assistance may evolve while preserving exact plan binding, explicit destructive intent, and fail-closed reference handling. A later typed merge can automate a proven asset family, but adopting a generic merge or implicit destructive replay requires a new decision.

## Required Documentation Alignment

- `design/architecture/system-architecture-procedural-generation.md`
- `design/architecture/microservices/world-management-service/procedural-generation-control.md`
- `design/architecture/microservices/game-design-service/world-editing-tools.md`
- `design/architecture/decisions/adr-0098-request-bounded-generation-replay-and-explicit-regeneration.md`
