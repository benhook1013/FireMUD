# Game Templates and Configuration Tools

This document expands on how the Game Design Service provides reusable templates
for new games. Templates bundle world data, scripts and default settings so that
creators can quickly spin up new projects without starting from scratch.

## Template Contents

- **World Layout** – predefined regions and rooms loaded from the World
  Management Service.
- **Starter Items and NPCs** – basic entity definitions for a new game.
- **Default Rulesets** – gameplay rules and runtime flags stored with the
  template.
- **Admin Accounts** – initial administrators configured at template creation.

`GameTemplateDto` includes `id`, `tenantId`, `name`, an optional `description`,
the raw `config` JSON and a `createdAt` timestamp. The `id` is assigned by the
database when the template is saved. The `config` field uses a structured
schema describing world layout, starter items, default rulesets, and admin
accounts.

The `config` payload does not embed authoritative copies of world, entity, or script definitions. Instead it carries:

- References to world templates (regions, rooms) using stable identifiers owned by the World Management Service and scoped by `(tenantId, versionId)`.
- References to starter items, NPCs, and equipment using stable identifiers owned by the Entity Management Service and scoped by `(tenantId, versionId)`.
- References to rulesets and scripts via identifiers defined by the Automation & Scripting Service.

Canonical schemas, identifiers, and versioned template rows remain in the owning domain services; `GameTemplateDto.config` is a configuration and wiring layer that composes these existing templates for bootstrapping new games.

> **Note**

Templates are **versioned** like any other design asset. Publishing a
version copies these templates to the domain services using the
`version_id` workflow described in
[Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).

## Creating Templates

Creators submit a `GameTemplateDto` via the REST API:

```bash
curl -X POST http://localhost:8080/templates \
     -H 'Content-Type: application/json' \
     -d '{"tenantId":"11111111-1111-1111-1111-111111111111","name":"Default","config":"{}"}'
```

The service validates the payload and stores it in the `game_templates` table.
Templates can then be listed per `tenantId` to help bootstrap new games.
Template names must be unique for each tenant to avoid collisions.

To list templates:

```bash
curl "http://localhost:8080/templates?tenantId=11111111-1111-1111-1111-111111111111"
```

See [openapi.yaml](../../../../services/game-design-service/src/main/resources/openapi.yaml)
for request and response schemas.

Management exists via REST and gRPC. Use `POST /templates` to create templates,
`GET /templates?tenantId=<id>` to list them, and the gRPC endpoints to create,
list, update, or delete templates.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
