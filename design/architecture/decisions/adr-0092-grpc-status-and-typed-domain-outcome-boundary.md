# ADR 0092: gRPC Status and Typed Domain Outcome Boundary

## Status

Accepted

## Implementation Status

The transport-status and typed-domain-outcome boundary is target state. Existing services still commonly embed `ErrorDetail` in transport-`OK` responses and do not have complete RPC classification, structured status-detail, retry/reconciliation, batch/stream, metric, or focused proof enforcement for this boundary. See the [gRPC architecture](../system-architecture-grpc.md#outcome-and-transport-classification) and [shared runtime contracts and persistence tracker](../../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md#capability-status).

## Canonical Design

- [gRPC API style and versioning: Outcome and Transport Classification](../system-architecture-grpc.md#outcome-and-transport-classification)
- [Shared libraries: Common DTOs and Error Handling](../system-architecture-shared-libraries.md#common-dtos--error-handling)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `GRPC-01`
- Primary capability: `SF-1.1` shared service contracts and error handling
- Affected capabilities: `SF-1.5`, `GR-1.1`, `PO-4.1`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of transport-OK application errors, canonical gRPC statuses, mutation ambiguity, batch/stream failure, client retries, and telemetry
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `GRPC-01`

## Context

The previous blanket rule returned every application-level failure inside a transport-`OK` response and reserved non-OK gRPC status for infrastructure failures. That keeps expected domain outcomes typed, but it also hides invalid requests, authorization failures, resource absence, overload, and sometimes downstream infrastructure failures from standard gRPC clients, retry policies, circuit breakers, and telemetry. It requires every caller to inspect a payload even when the RPC itself did not complete successfully.

Using non-OK status for every non-success outcome has the opposite problem: an expected business result or an already-decided mutation can be mistaken for an ambiguous transport failure and retried incorrectly. The contract needs to classify where processing ended and whether a domain result exists.

## Decision

Transport status represents whether the RPC request was successfully processed to the point of producing its declared domain result. Typed response outcomes represent expected business decisions within that successful processing.

Use transport `OK` with a typed outcome when the service successfully evaluated the request and produced an expected domain result, including a rejected gameplay action, admission decision, version-fence result, quota decision, or other business outcome that callers must handle as part of the operation. Responses use a result `oneof` or equivalent invariant so success data and failure outcome cannot both be populated.

Use canonical non-OK gRPC status when the RPC cannot produce its domain result. This includes request decoding or validation failure, unauthenticated or unauthorized caller, unavailable query resource where absence is not itself the RPC's declared domain result, unsupported precondition before business processing, resource exhaustion that prevents admission, deadline or cancellation, unavailable dependency, and unexpected internal failure. Attach bounded structured status details using the shared error catalog; do not rely only on a free-form status description.

Infrastructure failures retain their canonical outer status. A service does not routinely translate downstream `UNAVAILABLE`, `DEADLINE_EXCEEDED`, cancellation, or internal execution failure into a transport-`OK` domain response merely to preserve the old envelope convention.

Mutating operations carry stable idempotency identity. If transport fails after the mutation might have committed, callers retry under the same identity or query the durable operation/status surface; they do not infer non-application from the non-OK status or mint a new identity. A mutation whose service has durably decided a domain rejection returns that typed terminal result over `OK`.

Batch and streaming RPCs put expected per-item domain outcomes in their messages. A stream-level inability to continue uses the canonical non-OK stream status. Partial batches define whether transport `OK` means the batch was processed with item results or whether a request-level failure prevented any valid batch result.

Every RPC declares its typed domain outcomes, request-level status mapping, retryability and idempotency requirements, deadline/cancellation behavior, and batch/stream semantics. Generated client helpers and observability count transport failures separately from bounded domain-outcome codes.

## Consequences

- Standard clients, proxies, retry policies, circuit breakers, and telemetry can observe genuine request and infrastructure failures.
- Expected domain decisions remain typed and do not masquerade as ambiguous transport failures.
- Mutation retries remain safe through stable identity and status reconciliation rather than status-code guesswork.
- Callers must handle both canonical status details and typed successful domain outcomes.
- Existing protos, handlers, tests, metrics, and client mappings require classification and convergence from the blanket response-error rule.

## Alternatives Considered

### Put Every Failure in a Transport-OK Response

Rejected because invalid requests, authorization failures, overload, dependency outages, deadlines, and internal failures become invisible to standard gRPC failure handling and can be inconsistently wrapped by intermediate services.

### Put Every Non-Success in gRPC Status

Rejected because expected gameplay and workflow decisions are part of successful domain processing, and already-decided mutation results must not look like ambiguous transport failures that generic clients may retry.

## Implementation and Proof Obligations

Inventory every unary, batch, and streaming RPC and classify its expected domain results, pre-domain rejection statuses, dependency/infrastructure statuses, structured detail, retryability, idempotency identity, and reconciliation path. Enforce result-union invariants and prevent response success data from coexisting with a failure outcome.

Proof must cover validation, authentication, authorization, absence, precondition, quota/admission, expected domain rejection, dependency unavailable, deadline, cancellation, internal failure, mutation commit before response loss, same-identity retry, durable status lookup, batch partial results, stream item failures, and stream termination. Metrics must keep bounded domain codes separate from canonical transport statuses without per-request labels.

Current services widely embed `ErrorDetail` and often return broad internal or downstream `UNAVAILABLE`/`DEADLINE_EXCEEDED` failures over transport `OK`. The shared detail currently exposes only code and message, and no complete repository enforcement proves the boundary above. This decision records the target contract and does not claim implementation.

## Reversibility and Revisit Triggers

Individual RPC classifications may be corrected without revisiting the boundary when their domain semantics become clearer. Revisit if the project adopts a shared result-envelope protocol that preserves standard transport behavior through generated tooling, or if a supported transport cannot represent both structured request failure and typed domain results cleanly.
