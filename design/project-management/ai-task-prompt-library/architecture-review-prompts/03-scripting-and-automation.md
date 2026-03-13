# Architecture Review Prompt: Scripting and Automation

Read the following documents. Follow references only when a listed document explicitly delegates a canonical contract that is required to judge an implementation-blocking gap. Do not recursively fan out further.

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-scripting-dsl-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-dsl-for-designers.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-examples-and-patterns.md`
- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`
- `design/architecture/microservices/game-design-service/README.md`
- `design/architecture/microservices/game-design-service/ability-action-tools.md`
- `design/architecture/microservices/game-design-service/modding-framework.md`

Then:

- Review the scripting DSL, automation-scripting service, and related game-design tooling as a single, end-to-end system (authoring to validation to deployment to execution to monitoring to rollback).
- Do not summarize behavior or call out what is already good.
- Focus on gaps that would force implementers to invent lifecycle semantics, sandbox boundaries, quota policy, rollback behavior, or observability contracts.
- Do not let nice-to-have DSL ergonomics, future expansion ideas, and edge-case tooling improvements crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
