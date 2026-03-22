# Architecture Review Prompt: Scripting DSL and Runtime

Read the following documents. Follow references only when a listed document explicitly delegates a canonical contract that is required to judge an implementation-blocking gap. Do not recursively fan out further.

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-scripting-dsl-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-dsl-for-designers.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-runtime-execution.md`
- `design/architecture/system-architecture-scripting-scheduler-and-timers.md`
- `design/architecture/system-architecture-scripting-examples-and-patterns.md`
- `design/architecture/system-architecture-scripting-rollout-and-rollback.md`
- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`

Then:

- Review the scripting DSL and runtime as a single end-to-end system from authoring and validation through deployment, execution, and rollback.
- Do not summarize behavior or call out what is already good.
- Focus on gaps that would force implementers to invent lifecycle semantics, sandbox boundaries, quota policy, rollback behavior, or observability contracts.
- Do not let nice-to-have DSL ergonomics and future expansion ideas crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
