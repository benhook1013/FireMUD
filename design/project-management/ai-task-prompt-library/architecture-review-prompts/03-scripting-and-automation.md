# Architecture Review Prompt: Scripting and Automation

Read the following documents:

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
- Only identify problems, conflicts, or gaps: unclear execution or lifecycle semantics, ambiguous trust or sandbox boundaries, missing quota or abuse controls, weak failure or rollback stories, inconsistencies between designer-facing docs and service responsibilities, or underspecified observability or operational hooks.
- For each issue, reference the specific document or documents involved and propose concrete, actionable improvements, such as extra lifecycle steps, clearer API or contract definitions, stronger sandbox or limits, or better integration points with other services.
