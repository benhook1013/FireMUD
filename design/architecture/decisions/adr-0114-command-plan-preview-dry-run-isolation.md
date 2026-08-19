# ADR 0114: Command-Plan Preview Dry-Run Isolation

## Status

Accepted

## Implementation Status

The current `TriggerScriptEvent(isDryRun=true)` path is legacy/incomplete and does not establish ADR 0114's command-plan preview guarantees. The distinct command-plan preview surface and contract remain unimplemented and unproved; its concrete API and wire shape are implementation details.

## Decision Record

- Decision date: 2026-07-20
- Decision key: `MS-AS-DRY-RUN-ISOLATION`
- Primary capability: `AS-1.2` script sandbox and execution safety
- Affected capabilities: `AS-1.6`, `AS-1.5`, `AR-3.4`, `SF-2.2`
- Decision owner: FireMUD human product and architecture owner
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MS-AS-DRY-RUN-ISOLATION`

## Context

Creators and operators need to inspect what an Automation handler would emit without changing a running game. Calling this operation a dry run must not overstate its fidelity or share controls that can suppress production work.

## Decision

The target Automation capability is a **command-plan preview** with a distinct preview surface and contract. Its concrete API and wire shape remain implementation details. The target preview executes the selected immutable script or plugin handler through the same evaluator, component allowlist, sandbox, loop guards, and per-run input, resource, and output limits used by live execution.

The preview evaluates one explicit input bundle. Authorized live gameplay facts may be read only through one caller-supplied fenced snapshot or epoch; when no authorized fence is supplied, all simulated facts come from declared fixtures. The evaluator must not silently fall back to unfenced live reads or substitute a newer snapshot. Results identify the exact handler artifact/version, event or fixture inputs, snapshot token or epoch when used, and the complete ordered list of valid would-be commands.

The command list is inspection data only. A preview never persists live gameplay work, submits commands to Game Session or a gameplay queue, or invokes email, network, payment, filesystem, object-store, or another external side effect. Persisting an isolated audit record and bounded inspectable result is permitted; simulated gameplay or domain state is not.

Dry-run identity, idempotency and audit namespaces, principal and tenant quotas, failure metrics, circuit breakers, and capacity accounting are separate from live execution. Physical workers may be shared only with a hard reservation, partition, or equivalent admission guarantee preserving live availability. Live work wins under pressure. Numeric quota values and the concrete reservation mechanism remain implementation/operator policy.

Every result identifies the operation as a command-plan preview and states that success proves only evaluation under the identified inputs and returned valid command plan. It does not prove production contention, downstream domain-command acceptance, queue timing, or resulting gameplay state. A later disposable playtest fork is a separate capability.

## Consequences

- Creators can inspect exact would-be commands under identifiable inputs without production mutation or external effects.
- Reusing the live evaluator avoids a permissive test-only language path.
- Separate budgets, breakers, and guaranteed live capacity add operational proof work.
- Preview output cannot be presented as a gameplay-state simulation.

## Implementation and Proof Obligations

Proof must cover evaluator parity, authorized fenced snapshots, declared fixtures, missing/stale/substituted/unauthorized fences, exact result provenance and ordered command plans, zero gameplay work-item persistence or handoff, zero external effects, isolated audit/results, namespace collisions, independent quotas/metrics/breakers, and preview saturation with guaranteed live capacity.

## Related Contracts

- [Scripting cross-service contracts](../system-architecture-scripting-contracts.md#dry-run--test-traffic-safety)
- [Automation API contracts](../microservices/automation-scripting-service/api-contracts.md#dry-run-and-test-execution-contract)
- [Sandbox runtime design](../microservices/automation-scripting-service/sandbox-runtime-design.md)
- [Automation runtime and data](../microservices/automation-scripting-service/runtime-and-data.md)
