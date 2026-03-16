# Architecture Review Prompt: Designer Tooling and Modding

Read the following documents. Follow references only when a listed document explicitly delegates a canonical contract that is required to judge an implementation-blocking gap. Do not recursively fan out further.

- `design/architecture/microservices/game-design-service/README.md`
- `design/architecture/microservices/game-design-service/ability-action-tools.md`
- `design/architecture/microservices/game-design-service/modding-framework.md`
- `design/architecture/system-architecture-scripting-examples-and-patterns.md`

Then:

- Review designer-facing tooling and modding support as a single content-authoring design.
- Do not summarize behavior or call out what is already good.
- Focus on gaps that would force implementers to invent incompatible authoring, packaging, validation, or publish semantics.
- Do not let non-blocking tooling polish and future expansion ideas crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
