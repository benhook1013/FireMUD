# Architecture Review Prompt: Game Loop, Ticks, and Redis

Read the following documents:

- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-tick-concepts-and-invariants.md`
- `design/architecture/system-architecture-tick-execution-flows.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`
- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-redis-cache.md`
- `design/architecture/system-architecture-redis-usage-and-profiles.md`
- `design/architecture/system-architecture-redis-design-checklist.md`
- `design/architecture/system-architecture-redis-lua-patterns.md`
- `design/architecture/system-architecture-redis-operations.md`
- `design/architecture/system-architecture-redis-ops-access.md`
- `design/architecture/system-architecture-redis-reset-and-recovery.md`
- `design/architecture/system-architecture-redis-incident-runbook.md`
- `design/architecture/system-architecture-transactions.md`
- `design/architecture/system-architecture-scaling-runbook.md`

Then:

- Review the game loop and tick model together with the Redis usage and operational docs as a single, cohesive design.
- Do not summarize the intended behavior or describe what is already good.
- Only identify problems, gaps, or contradictions: unclear tick invariants, missing guarantees (ordering, idempotency, consistency), ambiguous data ownership across services, weak failure or recovery stories, unrealistic scaling assumptions, or Redis usage that is risky or under-specified.
- For each issue, tie it back to the specific document or documents involved and propose concrete, actionable improvements (clarifications, additional flows, stronger invariants, different Redis patterns, or better operational safeguards).

