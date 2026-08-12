# ADR 0063: Durable Per-Dispatch Script Handoff

## Status

Accepted

## Implementation Status

`AS-1.5` remains partial. Current Automation persists and retries a single parent work item and records the current Game Session handoff evidence, but it rejects multi-command output. Durable child dispatch rows, per-child partial-handoff retry/convergence, and retention gated by downstream replay and diagnostic horizons are target-state and are not proven end to end. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

## Canonical Design

- [Scripting runtime execution: Work Item Outbox Contract](../system-architecture-scripting-runtime-execution.md#work-item-outbox-contract-normative)
- [Scripting normative tables: Command-Handoff Identity](../system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state)
- [Cross-service scripting contracts: Script Work Item vs Tick Command Boundary](../system-architecture-scripting-contracts.md#2-script-work-item-vs-tick-command-boundary)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `SCRIPT-01`
- Primary capability: `AS-1.5` durable automation execution and handoff
- Affected capabilities: `SF-2.3`, `GR-1.1`, `SF-2.2`, `PO-4.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent contract validation and Redis-authority and unified-workflow-ledger alternative analysis
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `SCRIPT-01`

## Context

One script work item can emit multiple commands. A single parent status cannot truthfully represent a partially completed handoff, and retrying the entire parent can duplicate commands that were already accepted. Redis queue position also cannot serve as durable handoff truth because Redis coordination state is rebuildable and may be lost.

Evaluation itself may retry after a crash. Durable identity therefore has to distinguish one logical work item from each command it emits while preserving Trigger Identity across evaluation attempts.

## Decision

The PostgreSQL work-item outbox is the authoritative record after DSL evaluation. `automation:queue:*` and equivalent Redis structures are rebuildable routing pointers only; they are not execution logs or outcome authority.

Each emitted command has a durable child dispatch row beneath its parent work item. Each child uses the complete [Command-Handoff Identity](../system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state): stable source/target scope, persisted `automationDispatchId`, and deterministic `commandOrdinal`/canonical output position. `automationDispatchId` is a dispatch-group suffix and is not globally unique; the parent Trigger Identity and `outboxWorkItemId` are retained for correlation but are not part of command-child uniqueness. The durable child stores the immutable command/request digest and links to downstream outcome evidence; the same complete identity with the same digest converges on the existing child, while a conflicting digest fails closed.

Retries select only unfinished child dispatches. A previously accepted or otherwise terminal child is not emitted again merely because another child from the same evaluation failed.

The parent reaches `HANDED_OFF` only after every required child dispatch has been accepted by its downstream authority. A required-child permanent failure is an explicit non-success or dead-letter outcome; it must not be collapsed into `HANDED_OFF` or generic success. Optional children do not gate that transition: unfinished optional children continue under bounded post-handoff retry until accepted or explicit terminal failure/dead-letter. A permanent optional failure remains child/derived diagnostic evidence and does not rewrite required-child parent success; compaction waits until every child is terminal.

Evaluation may retry under the same Trigger Identity, but retries may create only one durable logical work item and one durable outcome for each deterministic child dispatch. Duplicate evaluation or handoff attempts converge on those existing records.

Terminal child dispatch evidence may be compacted only after all children are terminal and the durable downstream diagnostic and replay horizon has elapsed. Compaction must preserve enough durable identity and disposition evidence to demonstrate that duplicate retries cannot create another logical command.

## Consequences

- Multi-command evaluations can represent partial handoff accurately and retry only remaining work.
- Total Redis loss can delay automation routing but cannot erase accepted work or invent a successful outcome.
- PostgreSQL receives at least one durable child row per emitted command, increasing write and retention volume in proportion to script fan-out.
- Parent summaries are derived from child dispatch truth rather than acting as an independent outcome authority.
- Retention and compaction must account for downstream diagnostic and replay requirements before removing detailed child evidence.

## Alternatives Considered

### Redis as Handoff Authority

Rejected because list position and Redis-local deduplication can be lost during reset or failover. Work could disappear, be replayed ambiguously, or be reported as handed off without durable proof.

### One Unified End-to-End Workflow Ledger

Rejected despite offering one convenient timeline because it would centralize Automation evaluation, Game Session acceptance, gameplay execution, and reconciliation under shared ownership. It would add routine write amplification and blur which service owns each lifecycle transition. Durable child dispatch links provide the required traceability while each downstream domain retains outcome authority.

## Implementation and Proof Obligations

Proof must cover complete Command-Handoff Identity and digest-conflict rejection across evaluation retries, multi-command partial acceptance, retry of unfinished children only, duplicate dispatch convergence, parent `HANDED_OFF` only after all required children are accepted, independent bounded retry and terminal/dead-letter behavior for optional children, required-child permanent failure, Redis loss and pointer rebuild, and compaction only after every child is terminal and the downstream diagnostic/replay horizon has elapsed.

## Reversibility and Revisit Triggers

The child-dispatch model is additive within the authoritative outbox but becomes harder to change once operational history depends on its identities. Revisit if measured per-command persistence cost is unacceptable, if downstream acceptance no longer exposes stable lifecycle evidence, or if a concrete cross-domain workflow requires atomic state that links cannot represent. Any replacement must retain durable per-command identity, partial-outcome truth, and retry convergence.
