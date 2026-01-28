# Architecture Review Prompt: World, Content, and Persistence

Read the following documents:

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
- Only identify problems, contradictions, or gaps: unclear ownership boundaries between services, inconsistencies in how world, entity, and asset data are modeled, weak migration or rollback stories, missing rules for live content changes, or persistence patterns that are likely to cause data corruption, performance issues, or developer-experience issues.
- For each issue, reference the specific document or documents involved and propose concrete, actionable improvements, such as clearer ownership diagrams, stronger invariants, explicit migration or rollback flows, or adjusted responsibilities between services.

