# Architecture Review Prompt: Redis Operations and Recovery

Read the following documents. Follow references and read nearby related files as required when a listed document clearly points to another canonical source or when a closely related file is needed to resolve an implementation-blocking contradiction or missing operational contract. Do not recursively expand beyond that.

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
- Check each finding against the relevant domain tracker and implementation, in case the issue has already been resolved in code or tracking and the design now needs to import that decision back into the docs.
- Do not summarize the intended behavior or describe what is already good.
- Focus on issues that would leave first-implementation operations unsafe, ambiguous, or impossible to execute consistently.
- If trackers, protos, or current implementation already resolve the seam but the design docs are stale, classify the issue as "import resolved decision back into design" rather than as an unresolved architecture blocker.
- Do not attempt to list every theoretical ops improvement while blockers remain. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve operator safety or clarity.
- Return at most 5 issues, ordered by severity. If more exist, keep only the most implementation-relevant ones.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If the design is implementable and the remaining concerns are non-blocking, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once the remaining concerns have been captured as worthwhile non-blocking follow-ups.
