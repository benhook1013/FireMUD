# Shared Code, Tooling, And Pattern Consolidation Review

Use this prompt for an occasional repository-wide review of repeated implementation and tooling patterns that may have accumulated across otherwise well-reviewed pull requests. It is not a per-PR requirement and does not contribute to target-design review completeness.

Apply the [shared review contract](../system-review-prompts/00-shared-review-contract.md).

## Starting Sources

- `design/architecture/repository-structure.md`
- `design/architecture/system-architecture-shared-libraries.md`
- `design/architecture/system-architecture-testing.md`
- `design/project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md`
- `design/project-management/implementation-tracking/platform-operations-and-delivery.md`
- production modules, shared modules, tests, build logic, configuration, and `dev-tools/` in the declared review boundary

Inspect representative implementations broadly enough to compare patterns across services and tools. Do not infer duplication from filenames or search counts alone.

## Review

Look for repeated or competing implementations of:

- service bootstrap, dependency injection, configuration properties, validation, and lifecycle wiring;
- clients, adapters, wrappers, DTO translation, identifiers, timestamps, paging, and error handling;
- authentication, authorization, tenant scoping, trusted headers, and service-to-service setup;
- SQL, jOOQ, Flyway, Redis, transactions, outbox, Temporal, retry, timeout, idempotency, and reconciliation helpers;
- logging, metrics, tracing, audit, health, and readiness conventions;
- test fixtures, gameplay drivers, scenario builders, stubs, transcript parsing, waits, polling, retries, seeding, and assertions;
- build conventions, validation scripts, CI helpers, generators, and developer tooling; and
- dependencies or shared modules that provide overlapping jobs.

Also identify shared abstractions that have become too broad, couple unrelated services, hide important domain behavior, or cost more than the duplication they replaced.

Recommend consolidation only when concrete repetition, correctness drift, inconsistent behavior, or maintenance cost justifies it. Do not create generic infrastructure for hypothetical reuse. Prefer extending an existing canonical implementation when it already fits.

## Output

For each high-value candidate, provide:

- concrete repeated implementations and affected modules;
- whether behavior is truly the same or only superficially similar;
- correctness or maintenance impact;
- the best existing canonical home, if one exists;
- recommended direction: reuse, extract, merge, delete, narrow, or deliberately leave separate; and
- the smallest reasonable consolidation boundary.

End with a prioritized shortlist and the review state required by the shared contract. Do not produce an implementation plan or make changes unless separately requested.
