# ADR 0123: Database-Authoritative, Temporal-Coordinated World Lifecycle

## Status

Accepted

## Implementation Status

The current first implementation cut has fenced preparation and activation seams and a synchronous termination surface with Entity Management cleanup, but the architecture does not yet prove the complete Temporal-coordinated lifecycle, termination from `PREPARING`, separate failed-instance cleanup convergence, an extensible durable-owner acknowledgement registry, all-owner `TERMINATED` gating, or the required stuck-state telemetry. This decision records the target contract and does not claim those gaps are closed.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MS-GR-WORLD-LIFECYCLE`
- Decision date: 2026-07-20
- Decision key: `MS-GR-WORLD-LIFECYCLE`
- Primary capability: `AR-3.1` launch, activation, rollout, rollback, and retirement
- Affected capabilities: `SF-2.4`, `GR-2.1`, `AR-3.3`, `PO-1.1`
- Decision owner: FireMUD human product and architecture owner

## Consultation

Human-led review covered lifecycle authority, activation and termination races, pre-activation failure, cross-service cleanup convergence, workflow substrate, and gameplay-path cost.

## Context

World creation and termination span multiple service-owned databases and can outlive the process that initiated them. They need durable retry, wait, and operator inspection, but correctness cannot depend on one workflow worker or on interpreting Temporal history as the current admission state. A failure before activation also closes admission without proving that every partially created durable row has been removed.

The lifecycle therefore needs one authoritative fenced state while preserving independent evidence of cross-service cleanup convergence.

## Decision

World Management's durable instance row is authoritative for world lifecycle. It retains the states `PREPARING`, `ACTIVE`, `FAILED_PRE_ACTIVATION`, `TERMINATING`, and `TERMINATED` plus a monotonic lifecycle epoch. Every transition is a storage-level compare-and-set against the expected state and epoch. Game Session and other admission consumers use this row and epoch, not Temporal workflow status, worker memory, or an eventually consistent projection, to decide whether an instance is admissible.

The canonical `world-lifecycle` Temporal workflow coordinates the control-plane sequence, durable waits, retries, and operator-visible progress. Workflow and activity identity is stable by business operation. Each activity invokes an owning service's idempotent local operation; that service commits its domain writes, step guard, and any outbox record in a local transaction and uses storage uniqueness or compare-and-set predicates. Temporal retries do not become authority and must safely no-op or reconcile after an ambiguous response.

Activation may commit only from `PREPARING` under the current lifecycle epoch after every required preparation result is durable and admission checks still pass. A termination request received during `PREPARING` uses the same fence to transition the instance to `TERMINATING`; this makes any stale activation compare-and-set fail and starts cleanup of preparation outputs. A preparation workflow must not reopen or activate an instance after that transition.

When preparation cannot converge, the lifecycle row enters `FAILED_PRE_ACTIVATION`, which is terminal for admission and activation of that `gameInstanceId` but not terminal for cleanup. The coordinator records a cleanup request and uses the current lifecycle fence to transition `FAILED_PRE_ACTIVATION -> TERMINATING` before invoking owner cleanup. At cleanup start, it durably freezes the required durable-owner set and ownership-registry revision together with the cleanup request identity. Cleanup progress is recorded separately in durable owner-scoped state, including that frozen snapshot, the owners' acknowledgements, failures, and last progress time. Every retry and terminal decision uses the same snapshot, so registry membership cannot drift during an in-flight cleanup. Retries and repair remain in `TERMINATING`; a failed finalization stays there until every required owner acknowledgement is durable, so cleanup never changes the failed instance into an admissible state.

Termination reaches `TERMINATED` only after every owner in the frozen required-owner snapshot has acknowledged its idempotent cleanup obligation. World Management then performs the final fenced transition using that same cleanup request identity and frozen owner-set/ownership-registry-revision snapshot, and retains the terminal lifecycle evidence. Any new durable `gameInstanceId`-owned table or data family must join the common ownership inventory and define its termination cleanup, replacement-state classification, stable operation identity, acknowledgement, retry, and retention behavior before launch paths may write that family. An unregistered or missing owner acknowledgement fails closed rather than being ignored.

Lifecycle orchestration is control-plane work. Routine gameplay and tick execution do not query Temporal, wait on cleanup acknowledgements, or perform lifecycle coordination. They use the already required admission and lifecycle fences at lifecycle-sensitive boundaries, so this decision adds no routine per-command or per-tick cost.

Metrics and alerts cover age and retry count in `PREPARING`, `FAILED_PRE_ACTIVATION` with incomplete cleanup, and `TERMINATING`; missing or failed owner acknowledgements; stale workflow progress; CAS conflicts; and cleanup latency. Operator diagnostics correlate the database lifecycle row, epoch, workflow identity, cleanup request, and per-owner state without treating a dashboard projection as authority.

## Consequences

- Process restarts and ambiguous activity responses converge through stable identities and owner-local storage guards.
- Termination cannot race a stale preparation into activation.
- Admission-terminal failure is distinguished from completed cleanup, preventing false success and orphaned data from being hidden.
- Adding an instance-owned data family carries an explicit cleanup and replacement-classification obligation.
- Cross-service cleanup can delay `TERMINATED` and requires operator-visible reconciliation when an owner is unavailable.
- Temporal is required for lifecycle coordination, but ordinary gameplay remains independent of Temporal availability and latency.

## Alternatives Considered

### Database State Machine With Transactional Outbox Workers Only

This is the strongest alternative. The authoritative lifecycle row, outbox, inbox/idempotency records, and lease-taking workers could provide correct fenced convergence without Temporal and would reduce workflow-platform dependency. It is rejected for the canonical lifecycle because FireMUD would have to build and operate its own durable timers, retry policy, worker takeover, multi-owner wait state, signals, history, and operator intervention surfaces. Temporal supplies those coordination facilities while the database remains authoritative. Transactional outbox workers remain appropriate for owner-local delivery that has no independently meaningful durable wait state.

### Make Temporal Workflow State the Lifecycle Authority

Rejected because domain admission and cleanup correctness would then depend on workflow visibility and history interpretation rather than an atomic predicate in the owning database.

### Let Each Service Clean Up Independently

Rejected because World Management could declare termination while another owner still retained durable instance state, and no complete owner set would prove convergence.

## Implementation and Proof Obligations

Persist and expose the authoritative lifecycle state and epoch, allowed transition matrix, stable creation and termination identities, separate cleanup state, the cleanup-request-bound frozen required-owner set and ownership-registry revision, per-owner acknowledgements, and timestamps needed for stuck-state detection. All lifecycle transitions and owner steps require database-enforced uniqueness or compare-and-set predicates. Workflow definitions must be deterministic, activities idempotent, and activity results reconstructible from owner storage after timeout or worker loss.

Proof must cover worker and service restarts; duplicate and reordered activity delivery; lost responses after local commit; concurrent activation and termination from `PREPARING`; stale lifecycle epochs; preparation failure before and after partial writes; `FAILED_PRE_ACTIVATION` with pending or failed cleanup; an unavailable, duplicate, or newly registered owner; refusal to reach `TERMINATED` without every acknowledgement; expiry-triggered termination; metrics and alerts for each stuck state; and gameplay continuing without a Temporal call on its routine path.

## Reversibility and Revisit Triggers

Activity grouping, retry policy, cleanup retention, metrics thresholds, and Temporal deployment topology may evolve while preserving database lifecycle authority, monotonic fencing, stable operation identity, and all-owner cleanup acknowledgement. Revisit Temporal if measured operational burden exceeds its durable-coordination benefit or an outbox-worker state machine can demonstrate equivalent restart, wait, intervention, and observability behavior. Do not move lifecycle authority out of the owning database or add routine gameplay dependence on the workflow engine.

## Required Documentation Alignment

- [World Management API lifecycle contracts](../microservices/world-management-service/api-contracts.md#instance-termination-contract)
- [World creation and termination workflow](../microservices/world-management-service/world-creation-workflow.md)
- [World Management operations](../microservices/world-management-service/operations.md#instance-cleanup-and-expiry)
- [Versioning and runtime termination handoff](../system-architecture-versioning-runtime.md#instance-termination-handoff)
- [Temporal control-plane workflows](../system-architecture-temporal-workflows.md)
