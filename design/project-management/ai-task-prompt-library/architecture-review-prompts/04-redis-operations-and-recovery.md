# Architecture Review Prompt: Redis Operations and Recovery

Read the following documents. Follow references only when a listed document clearly points to another canonical source needed to resolve an implementation-blocking contradiction or missing operational contract. Do not recursively expand beyond that.

- `design/architecture/system-architecture-redis-operations.md`
- `design/architecture/system-architecture-redis-metrics-catalog.md`
- `design/architecture/system-architecture-redis-script-rollout-and-compatibility.md`
- `design/architecture/system-architecture-redis-ops-access.md`
- `design/architecture/system-architecture-redis-reset-and-recovery.md`
- `design/architecture/system-architecture-redis-incident-runbook.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`
- `design/architecture/system-architecture-scaling-runbook.md`

Then:

- Review Redis and tick-related operations as a single operator-facing design for failure handling, recovery, and safe intervention.
- Do not summarize the intended behavior or describe what is already good.
- Focus on issues that would leave first-implementation operations unsafe, ambiguous, or impossible to execute consistently.
- Do not attempt to list every theoretical ops improvement while blockers remain. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve operator safety or clarity.
- Return at most 5 issues, ordered by severity. If more exist, keep only the most implementation-relevant ones.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If the design is implementable and the remaining concerns are non-blocking, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once the remaining concerns have been captured as worthwhile non-blocking follow-ups.
