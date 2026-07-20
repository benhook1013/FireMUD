# ADR 0118: Command-Plan Preview Dry-Run Isolation

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `MS-AS-DRY-RUN-ISOLATION`
- Primary capability: `AS-1.2` script sandbox and execution safety
- Affected capabilities: `AS-1.6`, `AS-1.5`, `AR-3.4`, `SF-2.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of creator expectations, input fidelity, gameplay and external side effects, resource isolation, audit semantics, and operator expectations

## Context

Creators and operators need to inspect what an Automation handler would emit without changing a running game. Calling this operation a dry run can overstate its fidelity: evaluating a handler against plausible inputs does not prove that live domain owners will accept its commands, that concurrent gameplay will leave the same facts in place, or that the resulting state will match the preview.

Isolation also requires more than skipping the final tick handoff. A test that shares live identities, quotas, circuit breakers, or the last available worker capacity can suppress or degrade production work. Conversely, a fully realistic simulation that executes real commands against a disposable copy of gameplay state would require a broader fork lifecycle and deterministic substitutes for every external integration.

The initial operation therefore needs an honest bounded promise that provides useful command inspection while preserving the production safety boundary.

## Decision

The initial Automation dry-run operation is a **command-plan preview**. It executes the selected immutable script or plugin handler through the same evaluator, component allowlist, sandbox, loop guards, and per-run input, resource, and output limits used by live execution. Trust or provenance differences do not create a weaker preview evaluator.

The preview evaluates one explicit input bundle:

- Live gameplay facts may be read only when the caller is authorized for them and every such read is bound to one explicitly supplied runtime snapshot or epoch fence.
- When no authorized fenced snapshot is supplied, all simulated facts come from declared fixtures. The evaluator must not silently fall back to unfenced live reads or substitute a newer snapshot.
- The result identifies the exact handler artifact and version, event or fixture inputs, and snapshot token or epoch used, and returns the complete ordered list of would-be commands that passed output validation.

The command list is inspection data only. A preview never persists a live script work item, submits a command to Game Session or any gameplay queue, or invokes email, network, payment, filesystem, object-store, or another external side effect. Persisting the preview's isolated audit record and bounded inspectable result is permitted; persisting simulated gameplay or domain state is not.

Dry-run identity, idempotency and audit namespaces, principal and tenant quotas, failure metrics, circuit breakers, and capacity accounting are separate from live execution. Dry-run failures do not disable live scripts or consume live quota windows. Physical workers may be shared only when a hard reservation, partition, or equivalent admission guarantee preserves live worker availability and load proof demonstrates that preview saturation cannot consume the last live capacity. A separate deployment is permitted but not required by this decision.

Every result identifies the operation as a command-plan preview and states that success proves only that the selected handler evaluated under the identified inputs and produced the returned valid command plan. It does not prove production contention or queue timing, downstream domain-command acceptance, or resulting gameplay state.

A later high-fidelity mode may execute commands against a disposable playtest fork whose external integrations are replaced by deterministic fakes. That mode is a separate capability and lifecycle; it does not change or expand the initial command-plan preview contract.

## Consequences

- Creators can inspect exact would-be commands under identifiable inputs without risking production mutations or external effects.
- Reusing the live evaluator and safety limits avoids a permissive test-only language path, while the explicit fidelity label avoids claiming production equivalence.
- Fenced snapshots provide relevant live facts when authorized; fixtures provide a safe deterministic input path when they are not.
- Separate budgets, breakers, and guaranteed live capacity add operational policy and load-proof work even when dry-run and live evaluation share physical workers.
- The initial preview cannot answer whether commands will win production races, pass downstream state-dependent validation, or produce a particular final state.
- Higher-fidelity simulation remains possible but requires disposable state forks and deterministic integration substitutes rather than weakening the preview boundary.

## Alternatives Considered

### Execute Preview Commands Against Production

Run through the ordinary work-item and gameplay handoff path and roll back or label the effects as test traffic. Rejected because downstream services and external integrations do not provide one universal rollback boundary, and test work could mutate state, contend with players, or escape through irreversible side effects.

### Provide Only Fixture-Based Evaluation

Forbid all live facts and require callers to construct every input. This is simpler and deterministic, but it makes it harder to inspect a handler against an exact current gameplay view. Authorized fenced snapshots are permitted without allowing unfenced live reads.

### Begin with a Disposable High-Fidelity Playtest Fork

Copy a gameplay scope, execute real commands in the fork, and fake external integrations. This is the strongest fidelity alternative and remains the intended shape of a later simulation mode, but it adds state-fork creation, routing, cleanup, integration-fake, and result-comparison machinery beyond the initial command-plan need.

### Share Live Isolation Controls and Best-Effort Capacity

Use live identities, quotas, breakers, and whichever workers are currently free. Rejected because test traffic could deduplicate or suppress live work, disable scripts, exhaust production budgets, or consume the last live evaluation capacity.

## Implementation and Proof Obligations

The current implementation is incomplete against this decision. It distinguishes dry-run identity, quotas, capacity counters, and no-handoff execution, but still persists dry-run work through the durable live work-item and queue path. Its ingress response does not return the command plan or exact evaluated input identity, the stored `readSnapshotToken` is not consumed by evaluator reads, and its terminal stage/outcome does not yet match the documented `DRY_RUN_RESULT` / `dry_run_success` contract. Separate counters do not by themselves prove a hard live worker reservation.

Implementation proof must cover identical evaluator, component-policy, loop, input, resource, and output enforcement for live and preview runs; authorized fenced snapshot use; declared-fixture execution; rejection of missing, stale, substituted, or unauthorized fences; exact result provenance; complete ordered command-plan return; zero gameplay work-item persistence or handoff; zero external side effects; audit/result persistence only in the preview boundary; live/dry-run identity collision attempts; independent quotas, metrics, and breaker behavior; preview saturation with guaranteed live capacity; restart and retry behavior; and explicit fidelity labeling in every caller-visible result.

High-fidelity playtest-fork proof is not required for the initial preview and must not be inferred from its success.

## Reversibility and Revisit Triggers

Result representation, fixture schemas, snapshot-token formats, physical worker topology, and reserved-capacity mechanism may evolve while retaining evaluator parity, explicit input identity, no side effects, and hard live isolation. Revisit this decision before allowing preview commands to reach a domain owner, using unfenced live facts, sharing live identities or failure controls, or presenting preview success as proof of resulting gameplay state.

## Required Documentation Alignment

- `design/architecture/microservices/automation-scripting-service/api-contracts.md`
- `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`
- `design/architecture/microservices/automation-scripting-service/runtime-and-data.md`
