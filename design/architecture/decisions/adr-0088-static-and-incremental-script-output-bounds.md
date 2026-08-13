# ADR 0088: Static and Incremental Script Output Bounds

## Status

Accepted

## Implementation Status

The static and incremental output-bound contract is target state. Current Automation enforcement includes configurable command-count, per-entity, and ingress-payload byte limits, but generated collections are built before total and per-entity checks; complete serialized-byte enforcement, shared versioned/digested component-cost metadata, pre-construction metering, and atomic handler-output persistence are not implemented or claimed. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

## Canonical Design

- [Scripting runtime execution: Output Budgeting and Command Fan-Out](../system-architecture-scripting-runtime-execution.md#output-budgeting-and-command-fan-out)
- [Scripting runtime execution: Static Output Cost Contract](../system-architecture-scripting-runtime-execution.md#static-output-cost-contract)
- [Scripting cross-service contracts: Output Budget Safety](../system-architecture-scripting-contracts.md#13-output-budget-safety)
- [Scripting control-plane API: Event Ingress Admission Contract](../system-architecture-scripting-control-plane-api.md#automation--scripting-event-ingress-admission-contract-normative)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-02`
- Primary capability: `AR-1.1` script and automation authoring
- Affected capabilities: `AS-1.2`, `AS-1.6`, `GR-4.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with runtime-fuel-only and publish-time-only bounding alternative analysis
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SCRIPT-02`

## Context

Script sandbox fuel can bound evaluation time without bounding how many commands a handler constructs, how much serialized output it allocates, or how much durable and tick work it emits. Checking only after a complete output collection has been built still permits a single handler to consume excessive memory before rejection, and persisting commands one by one can expose a partial handler result when a later command exceeds a limit or fails to persist.

Game Design and Automation & Scripting must also agree on component output cost. Independent or unversioned cost tables can let publication accept a graph that runtime rejects after the component registry changes.

## Decision

Script output uses both static publish-time analysis and incremental runtime metering.

Game Design and Automation & Scripting consume one shared component-cost metadata contract. The metadata is versioned and digested, and every compiled script artifact records the exact metadata version and digest used for its analysis. Component entries declare their bounded command-count, per-entity distribution, serialized-size contributions, and estimated millisecond cost, and whether each cost is static or data-dependent.

Game Design performs conservative worst-case output analysis before publication. It evaluates every reachable branch, bounded loop, bulk action, and output-producing component using the recorded component-cost metadata. A graph is not publishable when its worst-case command count, per-entity count, or serialized size exceeds the platform/runtime ceiling, when the metadata version or digest is unavailable, or when a reachable output cannot be bounded.

A data-dependent component is eligible only when it declares a finite input or fan-out cap that is enforced at runtime. The compiled artifact records that cap and its cost contribution. Runtime-discovered collection size, payload size, or loop count cannot exceed the declared cap or substitute an unbounded value.

Automation & Scripting verifies the artifact's component-cost metadata version and digest against its local shared registry before executing the handler. Missing, displaced, or mismatched metadata fails closed rather than being interpreted under a newer private cost table.

The artifact also pins the estimated millisecond cost used for deterministic scheduler admission. For an automation tick, eligible handlers are considered in canonical order and the scheduler admits the ordered prefix whose cumulative pinned estimate fits `AUTOMATION_TICK_BUDGET_MS`; the remainder is deferred. Actual runtime is recorded for calibration only and does not refund a same-tick reservation.

### Artifact Cost Digest Contract

The compiled artifact carries exactly two subordinate cost/cap digests:

- `componentCostRegistryDigest` is `sha256:` followed by lowercase SHA-256 over UTF-8 RFC 8785 canonical JSON, excluding the digest field itself. Its normalized payload contains the schema version and duplicate-free component entries sorted by stable component identifier and version, including every output-cost class, command-count/distribution, serialized-byte, estimated-millisecond, and data-bound field used by publication or runtime revalidation.
- `artifactRuntimeCapDigest` uses the same `sha256:` plus lowercase SHA-256 of UTF-8 RFC 8785 canonical JSON, excluding its own digest field. Its normalized payload contains the cap schema version and duplicate-free cap entries sorted by canonical cap key, with typed value, unit, and scope for every resolved cap used at publication.

Both normalized payloads and their exact digests are embedded in and covered by the outer immutable compiled-artifact digest. Game Design and Automation use one shared canonical encoder and golden vectors. Automation recomputes and validates both embedded payload/digest pairs and never resolves newer private registry or cap values for an already-pinned artifact.

### Publish and Readiness Admissibility

The pinned cost contract is an admissibility gate, not an oversized-item bypass. For every live handler, the immutable pinned estimated-millisecond cost is a positive, finite, schema-bounded integer and is no greater than the scheduler budget ceiling pinned for that artifact. An artifact or handler that cannot fit one empty scheduler tick window is rejected before the patch reaches `READY` or the artifact is pinned; it must not be admitted and deferred indefinitely.

Command counts, per-entity fan-out/data bounds, and serialized-byte bounds are finite, schema-bounded non-negative integers. Zero is valid independently for each of those fields when it truthfully represents no contribution in that field; a zero in one field does not require zero in the others. Missing, negative, non-integral, non-finite, or above-schema/platform-ceiling values are rejected before handler admission. Runtime revalidates the relation between each pinned estimate and the pinned scheduler ceiling before execution.

Every accumulation, multiplication, sum of costs, and ordered-prefix cumulative estimate uses checked arithmetic. Overflow is invalid and fails closed; it never wraps around to make admission appear to fit. An operator or configuration reduction of the scheduler ceiling cannot be activated for a `READY` or already-pinned artifact whose handler estimate exceeds the reduced ceiling. The reduction must be rejected or preflighted until the artifact is republished or reconfigured under a compatible ceiling, avoiding silent indefinite deferral.

Runtime meters output incrementally. Before constructing, allocating, or serializing each next output element beyond its bounded contribution, the meter charges the prospective command count, target entity, serialized bytes, and declared component cost. If that charge would exceed any ceiling or data-dependent cap, evaluation stops before the oversized element or collection is constructed.

For one handler run, generated output is persisted atomically: either the complete metered output set is durably accepted or none of its generated commands are persisted. An output-budget violation cannot leave earlier commands from that handler durably handed off while later commands are rejected. The handler audit and durable work-item outcome remain available as failure evidence.

Outcome ownership follows the stage at which the violation occurs:

- a pre-handler envelope failure, such as an oversized ingress payload detected before handler resolution, remains a structured event-ingress admission outcome; and
- a generated-output violation discovered while evaluating a resolved handler is a handler-scoped `DSL_EVAL` non-success outcome with a bounded reason such as `command_count_exceeded`, `per_entity_command_limit_exceeded`, `work_item_size_exceeded`, or the applicable declared-cap violation.

A handler-scoped generated-output failure does not retroactively change a successful event-scope ingress result or another independently resolved handler's outcome.

## Consequences

- Unbounded graphs and outputs are rejected before publication, while runtime enforcement remains authoritative against corrupted artifacts, registry drift, and data-dependent values.
- Versioned and digested metadata keeps Game Design and Automation & Scripting on the same cost interpretation.
- Incremental metering prevents rejection only after an oversized collection has already consumed memory and serialization work.
- Atomic output persistence prevents partial generated command sets from escaping one failed handler run.
- Data-dependent authoring remains possible only where designers and component owners can declare and enforce a finite cap.
- Conservative worst-case analysis may reject graphs whose ordinary output is small but whose declared maximum exceeds the ceiling.
- Shared metadata lifecycle, digest validation, incremental accounting, bounded buffering, atomic persistence, and failure auditing add publication and runtime complexity.

## Alternatives Considered

### Runtime Fuel Only

Sandbox instruction, CPU, or wall-time fuel is simple and remains necessary for evaluation safety. Rejected as the output contract because a handler can construct or emit a large number of cheap commands, allocate a large serialized collection, or partially persist output while staying within its evaluation fuel. Fuel does not provide publish-time author feedback, shared component-cost agreement, per-entity fan-out bounds, or atomic output persistence.

### Publish-Time Analysis Only

Rejected because metadata drift, corrupt artifacts, implementation defects, and data-dependent values can invalidate a static result. Runtime incremental metering remains required even for a graph that passed publication.

## Implementation and Proof Obligations

Implement one shared versioned and digested component-cost registry; the exact `componentCostRegistryDigest` and `artifactRuntimeCapDigest` contracts; artifact pinning of both subordinate payloads/digests and declared finite caps; Game Design worst-case graph analysis; Automation metadata revalidation; incremental command-count, per-entity, byte, component-cost, and declared-cap metering; bounded output construction; and atomic complete-set persistence per handler.

Prove matching and mismatched metadata versions and digests; missing metadata; static and data-dependent components; mutually exclusive and co-executing branches; nested bounded loops; bulk actions; exact-limit acceptance and one-over-limit rejection for command count, per-entity count, bytes, and declared caps; rejection before oversized allocation or serialization; complete persistence after success; zero generated-command persistence after any budget or persistence failure; crash and retry around atomic persistence; pre-handler envelope failure as an ingress outcome; generated-output failure as a handler `DSL_EVAL` outcome; and independence of other resolved handlers.

The current Automation implementation provides partial evidence through configurable command-count, per-entity, and ingress-payload byte limits plus bounded outcomes. It currently constructs the generated command collection before total and per-entity checks, does not enforce a complete generated-output serialized-byte budget, and hands commands off sequentially rather than atomically as one handler output set. The shared versioned and digested component-cost metadata, Game Design worst-case analyzer, artifact cost pinning, Automation metadata revalidation, incremental pre-construction meter, data-dependent cap enforcement, atomic output persistence, and focused proof are not implemented or claimed by this decision.

### Supplemental clarification (2026-08-13)

Estimated millisecond cost is part of the versioned artifact contract and is admission input, not post-run billing. Deterministic ordered-prefix admission and deferral use the pinned estimate; actual runtime remains calibration telemetry and cannot produce a same-tick refund.

## Reversibility and Revisit Triggers

Cost values, metadata versions, digest algorithms, output ceilings, and bounded component categories may evolve while preserving shared interpretation, finite caps, incremental enforcement, stage-correct outcomes, and all-or-none handler output persistence. Revisit the model if a concrete feature requires streaming generated output that cannot fit the bounded atomic handler set and can provide an equivalent bounded, replay-safe, non-partial outcome contract.
