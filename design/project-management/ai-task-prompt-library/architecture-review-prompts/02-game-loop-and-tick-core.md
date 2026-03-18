# Architecture Review Prompt: Game Loop and Tick Core

Read the following documents. Follow references only when a listed document clearly points to another canonical source needed to resolve an implementation-blocking contradiction or missing rule. Do not recursively expand beyond that.

- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-tick-concepts-and-invariants.md`
- `design/architecture/system-architecture-tick-execution-flows.md`
- `design/architecture/system-architecture-transactions.md`
- `design/architecture/system-architecture-scaling-runbook.md`

Then:

- Review the game loop and tick model as a single cohesive runtime design.
- Do not summarize the intended behavior or describe what is already good.
- Focus on issues that would block safe implementation of tick ownership, ordering, replay, transactional boundaries, or scaling semantics.
- Do not attempt to list every theoretical scaling or failure concern while blockers remain. Once blockers are cleared, list the highest-value non-blocking improvements, including optimizations or refactors if they would materially strengthen the design.
- Return at most 5 issues, ordered by severity. If more exist, keep only the most implementation-relevant ones.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If the design is implementable and the remaining concerns are non-blocking, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once the remaining concerns have been captured as worthwhile non-blocking follow-ups.
