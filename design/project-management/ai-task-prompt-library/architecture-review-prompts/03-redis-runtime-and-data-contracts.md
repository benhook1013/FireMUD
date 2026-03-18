# Architecture Review Prompt: Redis Runtime and Data Contracts

Read the following documents. Follow references only when a listed document clearly points to another canonical source needed to resolve an implementation-blocking contradiction or missing contract. Do not recursively expand beyond that.

- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-redis-cache.md`
- `design/architecture/system-architecture-redis-usage-and-profiles.md`
- `design/architecture/system-architecture-redis-design-checklist.md`
- `design/architecture/system-architecture-redis-lua-patterns.md`
- `design/architecture/system-architecture-transactions.md`

Then:

- Review Redis usage as a single runtime and data-contract design across caches, ownership, mutation patterns, and Lua usage.
- Do not summarize the intended behavior or describe what is already good.
- Focus on issues that would block safe implementation of Redis ownership, mutation semantics, concurrency control, or data lifecycle.
- Do not attempt to list every theoretical scaling or failure concern while blockers remain. Once blockers are cleared, list the highest-value non-blocking improvements, including optimizations or refactors if they would materially strengthen the design.
- Return at most 5 issues, ordered by severity. If more exist, keep only the most implementation-relevant ones.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If the design is implementable and the remaining concerns are non-blocking, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once the remaining concerns have been captured as worthwhile non-blocking follow-ups.
