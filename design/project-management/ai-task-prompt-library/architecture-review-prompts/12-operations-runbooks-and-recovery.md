# Architecture Review Prompt: Operations, Runbooks, and Recovery

Read the following documents. Follow references only when a listed document points to another canonical source needed to judge an implementation-blocking operational gap. Do not recursively traverse the full operations doc tree.

- `design/architecture/system-architecture-runbooks.md`
- `design/architecture/system-architecture-backup-recovery.md`
- `design/architecture/system-architecture-scaling-runbook.md`
- `design/architecture/system-architecture-redis-operations.md`
- `design/architecture/system-architecture-redis-incident-runbook.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`

Then:

- Review operations and recovery behavior as a unified design for runbooks, alerts, failure handling, backup recovery, and operator actions.
- Do not summarize what already works well or restate the basic runbook structure.
- Focus on gaps that would leave the first implementation unsafe to operate or impossible to recover consistently.
- Do not let dashboard polish, extra nice-to-have alerts, and distant maturity improvements crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve operability.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
