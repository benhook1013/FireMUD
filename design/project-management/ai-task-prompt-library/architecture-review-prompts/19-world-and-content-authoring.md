# Architecture Review Prompt: World and Content Authoring

Read the following documents. Follow references and read nearby related files as required when a listed document clearly delegates a canonical contract or when a closely related file is needed to resolve an implementation-blocking contradiction or missing rule. Do not recursively expand beyond that.

- `design/architecture/microservices/world-management-service/README.md`
- `design/architecture/microservices/world-management-service/world-creation-workflow.md`
- `design/architecture/microservices/game-design-service/README.md`
- `design/architecture/microservices/game-design-service/world-editing-tools.md`
- `design/architecture/microservices/game-design-service/game-templates.md`
- `design/architecture/microservices/game-design-service/version-control.md`
- `design/architecture/microservices/game-design-service/item-equipment-balancing.md`
- `design/architecture/system-architecture-procedural-generation.md`

Then:

- Review world and content authoring as a single end-to-end design for creation, editing, versioning, and publishing.
- Check each finding against the relevant domain tracker and implementation, in case the issue has already been resolved in code or tracking and the design now needs to import that decision back into the docs.
- Do not summarize behavior or describe what is already good.
- Focus on issues that would cause incompatible implementations, unclear ownership, or unsafe content changes during initial implementation.
- If trackers, protos, or current implementation already resolve the seam but the design docs are stale, classify the issue as "import resolved decision back into design" rather than as an unresolved architecture blocker.
- Do not let non-blocking polish, later-scale optimizations, and speculative live-editing edge cases crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
