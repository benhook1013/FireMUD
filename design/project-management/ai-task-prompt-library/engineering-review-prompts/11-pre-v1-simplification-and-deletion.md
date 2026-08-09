# Pre-v1 Simplification And Deletion Review

Use this prompt for an occasional review of obsolete paths, compatibility baggage, unnecessary layers, and implementation structure that FireMUD should remove rather than preserve before v1. It is not a per-PR requirement and does not contribute to target-design review completeness.

Apply the [shared review contract](../system-review-prompts/00-shared-review-contract.md).

Use `AGENTS.md` only as repository-wide AI authority, safety, and workflow-routing guidance. Architecture documents own the technical target state and service boundaries.

## Starting Sources

- `AGENTS.md`
- `design/architecture/repository-structure.md`
- `design/architecture/system-architecture-overview.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/microservices/README.md`
- `design/project-management/implementation-tracking/README.md` and the trackers relevant to sampled production areas
- representative production code, configuration, migrations, and tests in the declared review boundary

## Review

Look for:

- obsolete and replacement paths still existing together;
- compatibility shims, deprecated schemas, transitional adapters, aliases, dual reads or writes, and migration history no longer required by the accepted data-retention boundary;
- dead features, unreachable code, stale configuration, unused dependencies, abandoned scripts, and unreferenced tooling;
- wrappers, layers, interfaces, DTOs, mappers, factories, or indirection that add no meaningful boundary;
- abstractions created for hypothetical future variation rather than current design;
- test-only accommodations shaping production code;
- local scaffolding left behind after a shared substrate became canonical;
- service or module boundaries that no longer match accepted architecture; and
- generated-looking repetition or fragmentation that makes the system harder to understand without protecting a real invariant.

Respect live-data retention, security, protocol, and accepted migration constraints. Pre-v1 status is not permission to discard required data or operational safety.

Keep this review distinct from consolidation:

- consolidation asks why several implementations exist and whether one should become shared;
- simplification asks whether the behavior or structure should exist at all.

## Output

For each high-value candidate, provide:

- concrete files and behavior involved;
- why the structure is unnecessary or obsolete;
- any data, protocol, security, or operational constraint that limits removal;
- recommended direction: delete, collapse, reset, replace directly, or retain with rationale; and
- expected reduction in complexity or future drift.

End with the highest-value deletion and collapse candidates and the review state required by the shared contract. Do not make changes or write a cleanup plan unless separately requested.
