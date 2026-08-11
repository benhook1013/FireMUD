# Cross-Service Invariant And Workflow Closure Review

Use this prompt to test whether FireMUD's important workflows remain coherent when followed across service, storage, transport, and operational boundaries.

Apply the [shared review contract](./00-shared-review-contract.md).
Apply the [orchestrated review workstream contract](./02-orchestrated-review-workstream-contract.md).

## Orchestrated Execution

A full invocation is an orchestrated review workstream: the invoking main thread takes primary ownership and delegates bounded evidence lanes for:

- account, identity, admission, and session lifecycle;
- gameplay and runtime mutations;
- authoring, publication, and activation;
- social, moderation, operator, and data-rights workflows; and
- delivery, migration, backup, restore, traffic, and recovery.

The primary thread reconciles shared invariants and seams across these workflow families.

## Starting Sources

- `design/product/user-journeys/overview.md`
- `design/architecture/README.md`
- `design/architecture/system-architecture-overview.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/system-architecture-identifier-glossary.md`
- `design/architecture/system-architecture-grpc.md`
- `design/architecture/system-architecture-transactions.md`
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- `design/architecture/microservices/README.md`
- `design/project-management/implementation-tracking/capability-allocation.md`

Follow the contract-authority map, capability handoffs, service documents, protocols, schemas, and focused proof for each selected workflow.

## Workflow Coverage

The comprehensive pass covers at least:

- account creation, authentication, gameplay admission, `JOIN`, `PLAY`, reconnection, takeover, logout, suspension, and deletion;
- command admission, tick execution, movement, entity mutation, effects, combat, automation, timers, and durable outcomes;
- creator authoring, validation, asset handling, scripting, publishing, activation, live cutover, rollback, and playtest isolation;
- social communication, moderation, reports, operator mutations, export, erasure, and audit evidence; and
- deployment, migration, backup, restore, traffic opening, degradation, and recovery.

For each workflow, identify:

- the canonical owner and participating services;
- identities, tenant and runtime scopes, authority generations, versions, epochs, fences, and idempotency keys;
- public and internal contract shapes;
- authoritative and derived stores;
- ordering, retry, timeout, duplicate, partial-commit, replay, and unavailable-authority behavior;
- client-visible and operator-visible outcomes;
- observability and recovery requirements; and
- current implementation and focused proof status.

## Output

Provide:

1. a workflow and invariant coverage table;
2. missing handoffs, competing authorities, incompatible contract shapes, and lifecycle gaps;
3. failures that do not converge safely or produce a defined user/operator outcome;
4. target/current/proof disagreements; and
5. the review state required by the shared contract.

Do not replace service-local review with this prompt. Cross-service findings own the shared seam; local follow-up belongs to the relevant service boundary review.
