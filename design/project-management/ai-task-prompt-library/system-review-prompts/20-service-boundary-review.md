# Service Boundary Review

Use this prompt to review one selected deployed service in its system context. Supply the service name when invoking it. Do not create one permanent prompt file per service.

Apply the [shared review contract](./00-shared-review-contract.md).

## Starting Sources

- `design/architecture/system-architecture-overview.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/microservices/README.md`
- the selected service's complete documentation under `design/architecture/microservices/<service>/`
- the selected service's proto, OpenAPI, route, configuration, schema, migration, production-code, and test surfaces
- every implementation tracker that owns or receives a capability handoff involving the service

Follow canonical cross-cutting contracts that the service consumes. Review shared modules as dependencies, not as separately deployed services.

## Review

Check:

- whether the service has one coherent responsibility boundary and does not duplicate another service's authority;
- public, internal, operator, and runtime-facing APIs and their actual consumers;
- identifier, tenant, realm, game-instance, version, authority-generation, and lifecycle handling;
- owned SQL, Redis, asset, workflow, cache, and derived-data boundaries;
- transaction, retry, timeout, idempotency, replay, and error semantics at every integration seam;
- configuration ownership, defaults, validation, secret use, readiness, and fail-closed behavior;
- logging, metrics, tracing, runbooks, recovery hooks, and operator access;
- agreement among target design, schemas, current production code, trackers, and focused proof; and
- missing negative paths that would force a neighboring service or operator to invent behavior.

Do not turn local framework or style preferences into architecture findings unless they create a contract, ownership, correctness, security, or proof problem. Repository-wide consolidation belongs to the engineering-maintenance review.

## Output

Provide:

1. a compact service contract card covering responsibilities, surfaces, stores, dependencies, configuration, operations, status, and proof;
2. findings local to the selected service boundary;
3. cross-service findings that must be reconciled by their canonical owner;
4. unavailable live or provider evidence; and
5. the review state required by the shared contract.
