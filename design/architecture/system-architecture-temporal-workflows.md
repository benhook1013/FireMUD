# FireMUD System Architecture: Temporal Control-Plane Workflows

Script rollout/readiness workflows may use Temporal for restart-independent orchestration, waits, and operator progress, but Temporal does not become the pin authority. **Target state:** Game Session durably commits the exact `(scriptPatchVersion, scriptPinEpoch)` and append-only rollout history. Automation durably owns tenant readiness, immutable compiled artifacts, schedule definitions and instances, due points, and firing claims, while exposing only non-authoritative pin/convergence projections. Temporal orchestrates those service-owned facts and workflow progress but owns none of the pin, history, readiness, artifact, schedule, due-point, firing-claim, or convergence facts. Routine rollback uses an epoch fence and does not pause ordinary gameplay ticks. See [Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md) and [ADR 0109](./decisions/adr-0109-game-session-owned-script-rollout-history.md).

## Goal and Current Status

FireMUD uses Temporal as the canonical substrate for long-running, durable control-plane workflows that must survive service restarts, support durable waiting/timers, and expose operator-visible progress independently of one JVM lifetime.

Current status (non-authoritative summary; [Shared Runtime, Service Contracts, and Persistence implementation tracking](../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md) owns current implementation status, proof evidence, gaps, and handoffs):

- the shared Temporal foundation is live in `services/common-temporal`;
- services opt in through the shared Gradle plugin `net.firedevops.firemud.temporal-conventions`;
- World Management now hosts the first real adopter through the `world-lifecycle` workflow family;
- Game Design now hosts the durable `publish` workflow family for full-version publish and release-attestation orchestration, while exposing workflow runtime metadata through `GetPublishedReleaseBundle`;
- Automation Scripting now hosts the `script-patch-readiness` workflow family for durable `onLoad`/readiness progression after `NotifyScriptVersionUpdate`;
- `common-saga` remains the canonical substrate for short synchronous orchestration that does not need durable workflow execution.

## Canonical Usage Boundary

Use Temporal when the workflow must provide one or more of the following:

- survive service restarts without manual replay or repair;
- wait durably for time or external control-plane events;
- expose operator-readable workflow state/history that matters independently of one process lifetime;
- support explicit signal, query, or update interactions while work is in progress.

Use `common-saga` instead when the orchestration is:

- short-lived;
- synchronous;
- easy to retry from the caller;
- not dependent on durable timers or external-event waiting.

Workflow placement and the minimum adopter classification/proof obligation are owned by [Transaction Strategies](./system-architecture-transactions.md#mandatory-workflow-adopter-classification). A Temporal adopter must name its owning service, workflow family and stable identity, local idempotency/reconciliation boundary, and the focused restart, duplicate-activity, wait/timer or signal, and operator-read proof appropriate to that family. A service document retains its local API and lifecycle plus only a non-authoritative summary of implementation drift and proof; the owning implementation tracker remains authoritative for current status, gaps, and evidence. Service documents must not restate the cross-platform placement matrix.

Do not use Temporal for:

- gameplay ticks;
- Redis-backed gameplay coordination;
- per-command hot runtime logic;
- combat, movement, inventory mutation, or other tick-owned execution.

## Shared Runtime Pattern

The shared foundation is intentionally small:

- `services/common-temporal` owns Spring Boot Temporal auto-configuration and shared conventions.
- Services that host Temporal workflows apply `net.firedevops.firemud.temporal-conventions`.
- Temporal hosting is opt-in through `firemud.temporal.enabled=true`.
- Worker hosting is opt-in through `firemud.temporal.workers-enabled=true`.
- Services register workers through `TemporalWorkerRegistrar` beans instead of creating their own independent startup loops.

The shared auto-configuration provides:

- `WorkflowServiceStubs`
- `WorkflowClient`
- `WorkerFactory`
- `TemporalTaskQueueResolver`
- `TemporalWorkerHost`

`TemporalWorkerHost` collects all `TemporalWorkerRegistrar` beans, lets them register workflow implementations on canonical task queues, and starts the shared `WorkerFactory` once registration is complete.

## Canonical Identity Conventions

The general semantic contract for durable workflow/request and step retry identity is owned by [Transaction Strategies](./system-architecture-transactions.md), with the rationale recorded in [ADR 0078](./decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md). This document owns only Temporal-local key encoding and adopter consequences; it does not replace that shared contract.

Workflow identity is explicit and deterministic.

- Workflow ID format (Temporal-local encoding):
  - `<workflowFamily>:<tenantId>:<scopeKey>:<businessKey>`
- Current `FiremudWorkflowIds` business-step key format:
  - `<workflowId>#<stepName>#<businessKey>`

The current `businessStepKey` is an incomplete implementation encoding, not the canonical full step guard. Target Temporal adoption adds a stable step name, deterministic occurrence key, and execution role to the local step encoding (for example, `<workflowId>#<stableStepName>#<deterministicOccurrenceKey>#<executionRole>` or an equivalent deterministic representation). The durable guard must store and compare the immutable request digest with those identity fields under the shared contract; the current helper and adopters do not yet prove that enforcement or the focused retry, conflict, crash, and replay behavior.

`FiremudWorkflowIds` implements the workflow-ID encoding and currently exposes the legacy business-step projection above. Adopters must treat that projection as an implementation migration gap rather than evidence of a complete digest-bound guard.

Guidance:

- `workflowFamily` is a stable design-level family such as `world-lifecycle` or `script-patch-readiness`.
- `scopeKey` is the narrow workflow scope that matters operationally, such as `world-instance`, `version`, or `game-instance`.
- `businessKey` is the stable caller-visible request identity or domain identity that makes retries idempotent.

Current adopter examples:

- `world-lifecycle` uses stable world-instance identity for its workflow business key.
- full Game Design publish uses the caller-supplied `publish_request_id` from `PublishVersionRequest`, so retries converge on the same caller-visible durable workflow instead of minting a fresh internal UUID.
- script-patch readiness uses the stable patch/readiness domain tuple documented in Automation Scripting.

`workflowId` is the durable process identity. `businessStepKey` is the local activity/update-side key projection for business effects inside the workflow; it is not, by itself, the complete durable step guard until the target occurrence, role, and digest fields are enforced.

## Canonical Task Queue Convention

Task queues are service-local but follow one repo-wide shape:

- `<taskQueuePrefix>:<spring.application.name>:<workflowFamily>`

This convention is implemented in `TemporalTaskQueueResolver`.

Default prefix:

- `firemud`

Example:

- `firemud:world-management-service:world-lifecycle`

This keeps worker routing explicit while avoiding one global task queue namespace shared loosely across unrelated services.

## Signals, Queries, Updates, and Operator Reads

The shared foundation does not impose a speculative universal workflow interface, but it does impose these rules:

- Queries are the canonical path for read-only current workflow state exposed directly by the workflow runtime.
- Signals are the canonical path for asynchronous intent such as pause/cancel/retry requests that should not synchronously mutate external state.
- Updates are the canonical path for validated in-workflow mutations that need request/response semantics.
- Service-owned operator APIs remain the external read surface. They may project Temporal workflow status into existing control-plane DTOs, but they must not invent independent workflow identity rules.

Required mapping discipline for adopting services:

- operator-facing status rows or read APIs must carry the same `workflowId` and `workflowFamily` that Temporal uses;
- business-step idempotency inside activities must use the target local step encoding and durable guard semantics: stable step name, deterministic occurrence key, execution role, and stored/compared immutable request digest. The current `businessStepKey` projection is not proof of that complete guard;
- adopting services must document their exact query/signal/update names in their owning service docs and implementation tracker instead of inventing hidden names in code only.

## Configuration Contract

The shared Temporal foundation currently recognizes:

- `firemud.temporal.enabled`
- `firemud.temporal.namespace`
- `firemud.temporal.target`
- `firemud.temporal.workers-enabled`
- `firemud.temporal.task-queue-prefix`

These properties are intentionally minimal until the first real adopters need more.

## Recommended Hosting Pattern for Adopters

Each adopting service should:

1. apply `net.firedevops.firemud.temporal-conventions`;
2. enable Temporal through service configuration;
3. contribute one or more `TemporalWorkerRegistrar` beans;
4. keep workflow implementation classes service-local;
5. use `TemporalTaskQueueResolver` and `FiremudWorkflowIds` for the local queue and workflow-ID encodings rather than inventing service-local formats, while adopting the full step guard contract above for activity idempotency.

## Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Transaction Strategies](./system-architecture-transactions.md)
- [Shared Libraries Overview](./system-architecture-shared-libraries.md)
- [Identifier Glossary](./system-architecture-identifier-glossary.md)
- [Shared Runtime, Service Contracts, and Persistence implementation tracking](../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md)
