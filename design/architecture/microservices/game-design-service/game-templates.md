# Game Templates and Configuration Tools

This document expands on how the Game Design Service provides reusable templates
for new games. Templates bundle world data, scripts and default settings so that
creators can quickly spin up new projects without starting from scratch.

## Template Contents

- **World Layout** – predefined regions and rooms loaded from the World
  Management Service. (TODO: Not yet implemented)
- **Starter Items and NPCs** – basic entity definitions for a new game. (TODO: Not yet implemented)
- **Default Rulesets** – gameplay rules and runtime flags stored with the
  template. (TODO: Not yet implemented)
- **Admin Accounts** – initial administrators configured at template creation. (TODO: Not yet implemented)

`GameTemplateDto` includes `id`, `tenantId`, `name`, an optional `description`,
the raw `config` JSON and a `createdAt` timestamp. The `id` is assigned by the
database when the template is saved. The `config` field is currently
free-form JSON. A structured schema describing world layout, starter items,
default rulesets and admin accounts will be introduced in a future revision.
(TODO: Not yet implemented)

> **Note**
> `tenantId` should be a string GUID as defined in [Multi-Tenancy](../../system-architecture-multi-tenancy.md). The service currently stores it as a numeric value, but migration to GUIDs is planned. (TODO: Not yet implemented)

Templates are versioned like any other design asset. Publishing a version is intended to copy
these templates to the domain services using the `version_id` workflow described
in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md). (TODO: Not yet implemented)

## Creating Templates

Creators submit a `GameTemplateDto` via the REST API:

```bash
curl -X POST http://localhost:8080/templates \
     -H 'Content-Type: application/json' \
     -d '{"tenantId":"tenant-abc","name":"Default","config":"{}"}'
```

The service validates the payload and stores it in the `game_templates` table.
Templates can then be listed per `tenantId` to help bootstrap new games.
Template names must be unique for each tenant to avoid collisions.

To list templates:

```bash
curl "http://localhost:8080/templates?tenantId=tenant-abc"
```

See [openapi.yaml](../../../../services/game-design-service/src/main/resources/openapi.yaml)
for request and response schemas.

Management currently exists only via REST. Use `POST /templates` to create templates and
`GET /templates?tenantId=<id>` to list them. gRPC endpoints for creating,
listing and updating templates are planned. (TODO: Not yet implemented)

Viewing, updating or deleting a specific template is not yet available. (TODO: Not yet implemented)

## 📚 Related Documentation

- [Game Design Service Architecture](README.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
