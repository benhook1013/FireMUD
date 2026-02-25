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

### Normalized Reference Storage

Game templates participate in version retirement, auditing, and bulk migration workflows. Because `GameTemplateDto.config` is JSON, the system must not rely on ad hoc JSON parsing to enforce invariants like “do not retire a version that is still referenced” or to perform controlled rewrites of references.

The Game Design Service therefore stores normalized reference rows alongside the JSON config, derived and validated on every create/update:

- `game_template_version_ref` keyed by `(tenantId, gameTemplateId, versionId)` for the base design bundle referenced by the template.
- `game_template_world_ref` keyed by `(tenantId, gameTemplateId, versionId, regionTemplateId/roomTemplateId/...)` for any explicit world references present in the config.
- `game_template_entity_ref` keyed by `(tenantId, gameTemplateId, versionId, entityTemplateId/lootTableId/...)` for starter items/NPCs and related entity wiring.
- `game_template_script_ref` keyed by `(tenantId, gameTemplateId, versionId, scriptId/...)` for script bindings where templates need to pin or validate script identifiers.
- `game_template_script_patch_ref` keyed by `(tenantId, gameTemplateId, baseVersionId, scriptPatchVersion)` when a template pins a default `scriptPatchVersion` for a base version.

Administrative tooling and lifecycle checks (retirement eligibility, “list templates referencing version”, bulk migrations) operate on these normalized tables. The JSON config remains the user-facing payload and can be reconstructed or validated against normalized rows, but it is not the only queryable representation of dependencies.

Normalized reference invariants:

- The normalized reference tables are authoritative for dependency queries (retirement eligibility checks, “list templates referencing version”, bulk migration planning). The system must not rely on best-effort parsing of arbitrary JSON to determine dependencies.
- Create/update operations must update `game_templates.config` and all corresponding `game_template_*_ref` rows in the same database transaction. Partial updates are not allowed.
- If reference derivation fails (for example malformed config), the template write must fail; the service must not persist a config that cannot be represented in normalized reference rows.
- Introducing normalized reference tables requires a one-time backfill migration/job for existing templates. Backfill must validate consistency and mark templates `INVALID` if dependencies cannot be derived or resolved.

### Backfill, Validation, and Runtime Usage

Normalized reference storage is only safe if it is operationally enforced:

- **Cutover phases** – normalized-reference rollout is stateful and must use explicit phases:
  - `BACKFILLING` – reference rows are being derived and validated; templates may exist that are not yet safe for runtime dependency checks.
  - `VALIDATED` – backfill completed and consistency checks passed for all templates in scope.
  - `ENFORCED` – runtime and control-plane reads for dependency/retirement checks use normalized tables as the sole source of truth.
  - Instance creation must be blocked for tenants still in `BACKFILLING`.
  - Once in `ENFORCED`, dependency checks must not fall back to best-effort JSON parsing.
  - Phase scope is explicit and persisted per `tenantId`. Tooling must not infer phase from partial row counts.
  - Phase must be stored in a durable control-plane row (for example `template_reference_phase`) with a monotonic epoch for CAS-safe transitions (`BACKFILLING -> VALIDATED -> ENFORCED`).
  - A read API such as `GetTemplateReferencePhase(tenantId)` must expose the persisted phase to Game Session and retirement tooling.

- **Backfill job/migration** – when normalized reference tables are introduced (or when their derivation rules change), the Game Design Service must run a backfill process that:
  - Re-derives all `game_template_*_ref` rows from existing `game_templates.config`.
  - Validates that every referenced `(tenantId, versionId, templateId)` exists in the owning domain service and that the referenced `versionId` is not Retired.
  - Marks the template as `INVALID` (and blocks instance creation) if references cannot be derived, cannot be resolved, or violate lifecycle constraints.
- **Strict create/update enforcement** – create/update of a template must be rejected if normalized references cannot be derived and written in the same transaction as the JSON config.
- **Instance creation enforcement** – the Game Session Service (or the instance-creation orchestrator) must validate template dependencies using normalized tables before creating any `gameInstanceId` rows:
  - Fail fast if any referenced version is Retired, missing, or out of sync with its domain templates.
  - If the template pins a `scriptPatchVersion`, fail fast unless Automation & Scripting reports that patch is `READY` for the tenant.
  - Fail fast if `GetTemplateReferencePhase` is not `ENFORCED` for the target scope.

> **Note**

Templates are **versioned** like any other design asset. Publishing a
version does **not** copy a separate design database into the domain
services. Instead:

- The Game Design Service persists `game_templates` rows and their revision history as design-time artifacts.
- World Management, Entity Management, and related domain services already own the authoritative versioned templates keyed by `(tenantId, versionId)`; publish finalizes those Draft templates via the `version_id` workflow described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- `GameTemplateDto.config` composes existing domain templates by reference and is validated at publish time; it never becomes a competing source of truth for world or entity graphs.

Ownership can be summarized as:

- **Game Design Service** – owns `game_templates` and all version/control metadata for templates.
- **World Management / Entity Management / Automation & Scripting** – own world, entity, and script templates and their identifiers; game templates only reference these records via stable IDs.

### Runtime Defaults

Game templates may optionally carry default runtime configuration alongside their structural wiring:

- `GameTemplateDto.config` can include optional fields such as a default `scriptPatchVersion` or initial feature-flag presets that the Game Session Service uses when creating new `gameInstanceId` values from the template.
- When these defaults are present, instance-creation flows should apply them explicitly; when they are absent, callers must provide the desired `scriptPatchVersion` and runtime flags at creation time. Templates must not implicitly select “latest READY patch” or other moving targets without operator input.
- If a template pins a default `scriptPatchVersion`, instance creation must validate that Automation & Scripting has marked that patch `READY` for the tenant before pinning it for a running instance; otherwise instance creation fails with a clear error and no instance rows are created.

### Interaction with Version Lifecycle

Game templates participate in the same version lifecycle as the domain templates they reference:

- A `GameTemplateDto` may reference only versions that are not in the `Retired` state (also referred to as “Archived” in some UIs). When saving or updating a template, the Game Design Service must validate that all referenced `(tenantId, versionId, templateId)` triples exist and that the corresponding `versionId` is still eligible to be activated.
- The `RetireVersion` workflow in Game Design or Logging & Admin Services must refuse to retire a version while any game templates still reference it. Designers must migrate those templates to a successor version (for example by creating new templates pointing at the new version’s templates) before the old version can be retired.
- Templates that reference missing or invalid templates (for example due to a failed migration) should be marked as `OUT_OF_DATE` or `INVALID` in metadata and blocked from use when creating new games until designers repair or migrate them.

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
