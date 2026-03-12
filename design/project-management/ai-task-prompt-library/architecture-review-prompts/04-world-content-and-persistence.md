# Architecture Review Prompt: World, Content, and Persistence

Read the following documents. Follow references only when a listed document clearly delegates a canonical contract needed to resolve an implementation-blocking contradiction or missing rule. Do not recursively expand beyond that.

- `design/architecture/microservices/world-management-service/README.md`
- `design/architecture/microservices/world-management-service/world-creation-workflow.md`
- `design/architecture/microservices/entity-management-service/README.md`
- `design/architecture/microservices/game-design-service/README.md`
- `design/architecture/microservices/game-design-service/world-editing-tools.md`
- `design/architecture/microservices/game-design-service/asset-storage.md`
- `design/architecture/microservices/game-design-service/game-templates.md`
- `design/architecture/microservices/game-design-service/version-control.md`
- `design/architecture/microservices/game-design-service/item-equipment-balancing.md`
- `design/architecture/system-architecture-database-migrations.md`
- `design/architecture/system-architecture-transactions.md`
- `design/architecture/system-architecture-asset-store-runbook.md`
- `design/architecture/system-architecture-procedural-generation.md`

Then:

- Review the world and content model, entity model, and asset storage as a single, end-to-end persistence design (authoring, versioning, deployment, runtime updates, and migrations).
- Do not summarize behavior or describe what is already good.
- Focus on issues that would cause incompatible implementations, unsafe data changes, or unclear ownership during the first implementation slice.
- Ignore non-blocking polish, later-scale optimizations, and speculative live-editing edge cases unless they materially affect current contracts.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and optionally list up to 3 deferred follow-ups.
- Stop once only non-blocking refinement remains.
