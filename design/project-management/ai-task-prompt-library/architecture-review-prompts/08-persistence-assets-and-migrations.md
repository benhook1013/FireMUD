# Architecture Review Prompt: Persistence, Assets, and Migrations

Read the following documents. Follow references only when a listed document clearly delegates a canonical contract needed to resolve an implementation-blocking contradiction or missing rule. Do not recursively expand beyond that.

- `design/architecture/microservices/entity-management-service/README.md`
- `design/architecture/microservices/game-design-service/asset-storage.md`
- `design/architecture/system-architecture-database-migrations.md`
- `design/architecture/system-architecture-transactions.md`
- `design/architecture/system-architecture-asset-store-runbook.md`

Then:

- Review persistence, asset handling, and migration rules as a single end-to-end data design.
- Do not summarize behavior or describe what is already good.
- Focus on issues that would cause incompatible implementations, unsafe data changes, or unclear ownership during the first implementation slice.
- Do not let non-blocking polish and future-scale refinements crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
