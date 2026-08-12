# ADR 0064: Stage-Qualified Script Outcomes

## Status

Accepted

## Implementation Status

`AS-1.5` remains partial. Current audit and handoff surfaces prove stage-linked single-command paths and current execution-fence handling, but the live taxonomy still needs convergence to `handoff_accepted`/`completed_no_commands` and authoritative per-command Game Session links with derived handler summaries. The full multi-command outcome model and its focused proof are not yet end to end. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

## Canonical Design

- [Scripting normative tables: `script_event_audit` stages and outcomes](../system-architecture-scripting-normative-contract-tables.md#table-2-script_event_audit-stages-and-outcomes)
- [Scripting observability: per-command handoff and post-handoff outcomes](../system-architecture-scripting-observability-contract.md#per-command-handoff-and-post-handoff-outcomes-required-when-present)
- [Scripting runtime execution: failure and outcome semantics](../system-architecture-scripting-runtime-execution.md#failure-modes-and-error-handling)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `SCRIPT-04`
- Primary capability: `AS-1.5` scripting audit and execution outcomes
- Affected capabilities: `PO-4.1`, `SF-2.3`, `SF-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent contract-validation and strongest-alternative analysis
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `SCRIPT-04`
- Supersedes: only ADR 0002's generic live `finalOutcome=success` label; its durable tick-handoff success boundary remains accepted as `handoff_accepted`

## Context

A script event can select several handlers, and one valid handler can emit zero, one, or many gameplay commands. A generic event-level `success` cannot state whether event ingress was accepted, DSL evaluation completed, commands were durably handed off, or gameplay mutations were ultimately applied. Treating tick handoff as final gameplay success also hides partial outcomes when one handler emits several commands.

Outcome truth must therefore preserve the boundaries between scripting evaluation, durable dispatch, and the authoritative Game Session command lifecycle.

## Decision

Event-ingress, handler-pipeline, and per-command gameplay records are separate outcome authorities:

- The event-ingress record reports event admission and correlation. It does not summarize handler or gameplay success.
- The handler-pipeline record reports DSL selection, evaluation, persistence, and handoff stages for one handler invocation under its full Trigger Identity.
- Every emitted command has its own durable dispatch identity and links to the authoritative Game Session command lifecycle and terminal outcome.

The live generic `success` outcome is renamed `handoff_accepted`. It means that the required emitted dispatches have been accepted at the durable handoff boundary; it never means that their gameplay mutations were applied.

A valid handler that evaluates successfully and intentionally emits no commands records `completed_no_commands` at the DSL-evaluation stage. It is neither a failure nor a handoff success.

After handoff, a handler may expose a derived summary containing counts and links to its command lifecycles. The summary may report full application, no application, partial application, or abandonment, but it is a projection only. It never replaces, mutates, or becomes the authority for individual command outcomes.

Operator and diagnostic tooling must distinguish at least:

- evaluated;
- persisted;
- handed off;
- applied;
- not applied;
- partial; and
- abandoned.

Tooling must retain links across these stages so an event or handler can be traced to each authoritative command result without collapsing the records into one mutable status.

## Consequences

- Operators can distinguish script correctness from durable dispatch and gameplay completion.
- Multi-command handlers retain truthful partial outcomes instead of one misleading success flag.
- Zero-command handlers have an explicit successful terminal evaluation outcome.
- Live schemas, metrics, dashboards, and documentation using generic `success` must converge on `handoff_accepted` and the stage-qualified taxonomy.
- Post-handoff summaries require projection or query work, but cannot corrupt authoritative per-command history.

## Alternatives Considered

### One Mutable Event-Level Status

Rejected because successive stages would overwrite distinct facts, multiple handlers could race to define one result, and partial multi-command outcomes could not be represented truthfully.

### Unified End-to-End Workflow Ledger

Rejected because it would duplicate Game Session command authority, increase routine write volume, and couple scripting observability to gameplay execution. Linked stage-specific records provide the required trace without creating a new shared outcome owner.

## Implementation and Proof Obligations

Specify the event-ingress, handler-pipeline, dispatch-link, and derived-summary schemas and their ownership boundaries. Remove generic live `success` where it denotes handoff and migrate it to `handoff_accepted`.

Prove successful zero-command evaluation; one- and multi-command handoff; mixed applied and not-applied command results; abandoned commands; retry without duplicate dispatch outcomes; stage-monotonic audit history; authoritative links to Game Session; derived summary reconstruction; and operator queries that distinguish every required stage and terminal disposition.

Current implementation and focused proof must be evaluated separately; this decision does not claim the contract is already implemented.

## Reversibility and Revisit Triggers

Outcome labels and projections may evolve additively while authority remains stage-qualified. Revisit only if Game Session command ownership changes or a concrete cross-service workflow requirement demonstrates that linked records cannot provide truthful lifecycle correlation without a unified ledger.
