# FireMUD System Architecture: Database Migrations

This document explains how FireMUD manages PostgreSQL schema changes across its microservices. Each service owns its tables and applies migrations independently.

---

## Migration Tool

- **Flyway** is used for all schema migrations.
- The root `build.gradle.kts` applies the `org.flywaydb.flyway` plugin and adds
  `flyway-core`, `flyway-database-postgresql`, and the PostgreSQL driver for
  every service module under `services/` (excluding the `common-library`
  module, which does not run Flyway itself).
- Versioned SQL files live under each service in `src/main/resources/db/migration/`.
- Migrations follow the `V<version>__<description>.sql` naming convention.
- Every module begins with a `V1__init.sql` baseline and numbers sequentially from there.
- `spring.flyway.enabled=true` in `application.yml` triggers migration execution on startup.
- Flyway reads connection settings from the `FIREMUD_POSTGRES_*` environment variables described in
  [Environment & Secrets](./infrastructure/environment-and-secrets.md).
- Java-based callbacks are avoided; migrations remain SQL-only for portability.

## Per-Service Organization

- Every microservice maintains its own migration folder and changelog.
- Schemas are isolated: services never modify each other's tables.
- Tables reside in dedicated schemas for each service to ensure isolation.
  Schema names match the owning service (for example `account_service`). See
  [Multi-Tenancy](./system-architecture-multi-tenancy.md) for details.
- Shared schema components such as the Saga tables are defined in the
  `common-library` module with their own migrations, but are still applied
  **per service database**:
  - The `common-library` contains saga table migrations described in
    [System Architecture – Transactions](./system-architecture-transactions.md).
  - Services that need these shared tables include the module as a dependency
    during their build.
  - The library packages its migrations inside the JAR so Flyway automatically
    picks them up from the classpath when each service starts.
  - The `common-library` itself does not run Flyway; each consuming service
    executes these migrations against its own database on startup, creating a
    local `saga` schema as needed.
- New migrations are committed alongside service code so history stays with the owning service.

### Version-Aware Migration Guidelines

Because game data is versioned and previously published `version_id` values may
be reactivated for rollback, migrations must be written to preserve the ability
to load existing versioned graphs:

- Prefer **additive** changes (adding tables/columns, widening types) while any
  published versions still depend on the existing schema shape.
- When a column or table needs to be removed or repurposed:
  - First introduce replacement fields and write data-migration steps that
    copy or transform data for all active and published versions.
  - Mark the old fields as deprecated in service documentation and avoid using
    them for new versions.
  - Drop or repurpose the deprecated fields only after all affected versions
    have been retired according to the lifecycle in
    [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).
- Avoid destructive operations (`DROP COLUMN`, `DROP TABLE`, type narrowing)
  that would make it impossible to reconstruct historical versions that are
  still eligible for rollback.

These rules apply both to service-local schemas and to shared saga tables so
that rollback and historical analysis remain viable across deployments.

### Version-Aware Migration Checklist

Before applying destructive or shape-changing migrations in a service that owns
versioned/template data (for example templates keyed by `(tenantId, versionId)`),
engineers should follow this checklist:

1. **Enumerate non-Retired versions**
   - Query the Game Design Service’s version metadata (or the service-local
     mirror of version state) to list all `version_id` values that are not in
     the Retired state (also referred to as “Archived” in some UIs) for the affected tenants.
   - Confirm which tables and columns participate in those versions’ template
     graphs (for example via ERD diagrams or schema documentation).
2. **Assess dependencies on the fields being changed**
   - For each non-Retired version, determine whether the column/table or JSON
     field being dropped, narrowed, or repurposed is still used in:
     - Published templates (world, entity, or asset mappings),
     - Procedural generation metadata, or
     - Runtime feature/config records that must remain readable for rollback.
   - If any non-Retired version still depends on the field, delay destructive
     changes and use additive migrations plus data backfills instead.
3. **Provide fixtures for older versions**
   - Maintain at least one fixture or seed dataset per major schema era that
     includes representative versioned graphs. Migration tests in CI should
     apply new migrations over these fixtures to ensure old versions remain
     loadable.
4. **Handle JSON/metadata schema versions**
   - When changing JSON structures (for example generator params or config
     blobs), introduce an explicit `schemaVersion` or equivalent discriminator.
   - Write migrations and code paths that can still read older `schemaVersion`
     values until all non-Retired versions that rely on them have been
     retired or upgraded.
5. **Align with version lifecycle**
   - Only drop or repurpose fields once all versions that depend on the old
     semantics have been Retired, in line with the lifecycle defined in
     [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).
6. **Enforce deploy-order compatibility (expand/migrate/contract)**
   - Apply schema and code changes in three phases:
     - **Expand** – add new schema elements and ship dual-read/dual-write compatible code.
     - **Migrate** – backfill and verify old + new representations while mixed service versions are still running.
     - **Contract** – remove deprecated paths/columns only after compatibility windows and retirement checks pass.
   - Never combine incompatible reader/writer changes in one deploy step when services may roll independently.
7. **Run mixed-version CI checks**
   - CI must include at least one scenario where new migrations run with a mixed old/new service set (old readers with new schema and new readers with old-era data fixtures).
   - Destructive contract-phase migrations are blocked unless mixed-version compatibility checks and fixture-based rollback reads pass.

Services that own versioned templates (such as Game Design, World Management,
Entity Management, and Asset Storage) should reference this checklist in their
local docs and treat it as part of their migration review process.

### Cross-Service Identifier Migration

Some schema changes affect identifiers or records that are referenced across multiple services (for example, item templates referenced from world templates, game templates, or procedural generation bindings). These changes must be handled as **design-time workflows**, not as ad-hoc SQL rewrites:

- Do not repurpose or rename identifiers in-place (`item_template_id`, `room_template_id`, loot-table IDs, script IDs, etc.) while any non-Retired version still references them from another service.
- Instead, introduce new template rows (with new identifiers) in the owning service and mark the old templates as deprecated at the design layer. Use Game Design Service workflows to migrate references in:
  - World templates and population bindings (World Management),
  - Entity templates and loot tables (Entity Management),
  - Game templates and configuration payloads (Game Design Service),
  - Script or automation bindings (Automation & Scripting).
- Coordinate these reference updates under a dedicated migration or Saga that:
  - Updates all affected cross-service mappings to point at the new identifiers for Draft or upcoming versions; and
  - Ensures that no non-Retired version or live `gameInstanceId` relies on the old identifiers before they are removed.
- Only once all versions that reference the old identifiers have been retired, and all cross-service mappings have been updated, may destructive DDL (for example dropping deprecated columns or tables) be applied.

The Game Design Service remains the source of truth for which versions and revisions reference which domain templates; cross-service identifier migrations should be orchestrated from there so migration steps can be validated against version metadata before rollout.

Release-attestation records are part of the same migration surface:

- Any schema change that alters the meaning of publish digests, asset manifest hashes, or frozen generation-config identities must update the `published_release_bundle` writer/reader contracts in lockstep.
- Activation and repair workflows must continue to understand prior non-Retired attestation schema versions until the corresponding versions are retired or explicitly re-attested.

For manifest-like metadata such as the asset `manifest.json` files generated by Game Design Service, schema evolution follows the same rules:

- Each manifest includes an explicit `schemaVersion` field as described in `microservices/game-design-service/asset-storage.md`.
- New manifest fields and semantics are introduced under a new `schemaVersion`, and clients must remain able to read existing non-Retired manifest versions until the corresponding game versions are retired or explicitly migrated by rerunning the manifest export step.

### Examples of Versioned World and Entity Migrations

The following examples illustrate how to apply the version-aware guidelines to common schema changes:

- **Expanding world coordinates** – changing from `(x, y)` to `(x, y, z)`:
  - Add a new nullable `z` column and update code to treat `NULL` as a default plane for existing versions.
  - Backfill `z` for all non-Retired versions where a meaningful value is known; otherwise leave it `NULL`.
  - Only once all versions that rely on the old two-dimensional semantics are Retired should code stop treating `NULL` specially, and only then consider dropping legacy helper fields if any were introduced.

- **Evolving generator configuration JSON** – adding new parameters to procedural-generation rules:
  - Introduce a new `schemaVersion` for the generator config blob and add new fields alongside existing ones, defaulting them when absent.
  - Update World Management to continue understanding older `schemaVersion` values and to persist a new `schemaVersion` only for newly created versions or generation runs.
  - Delay removal or repurposing of old fields until all versions that depend on the old schema have been Retired; see also `system-architecture-procedural-generation.md` for generator-specific guidance.

- **Splitting item stats into a new table** – moving some columns off the main item template table:
  - Create a new stats table keyed by the same primary key as the item template and backfill rows for all existing items in non-Retired versions.
  - Update Entity Management read paths to join the new table while still tolerating missing rows during the backfill period.
  - Once backfill and verification are complete, mark the old columns as deprecated in documentation and stop writing to them for new versions.
  - Only after all versions that relied on the old columns have been Retired should you consider dropping or repurposing those columns.

## CI/CD Execution

- Flyway runs automatically when a service container starts.
  If any migration fails, the application startup aborts so issues are caught early.
- In development you can run `./gradlew flywayMigrate` for a single service.
- Execute this task from the service directory or prefix the project name (e.g.,
  `./gradlew :account-service:flywayMigrate`).
- You can also run `./gradlew :service:flywayInfo`, `flywayClean`, or `flywayRepair` to troubleshoot local databases. **Use `flywayClean` with caution** because it drops tables.
- Run `./gradlew :service:flywayValidate` to verify migrations before committing.
- The CI pipeline runs `flywayValidate` for all services to catch migration issues early.
- See [DEVELOPER_SETUP.md](../../DEVELOPER_SETUP.md) for the environment variables needed to connect to your local PostgreSQL instance. Copy the `FIREMUD_POSTGRES_*` values from `.env.sample` into `.env` so Flyway can connect locally.
- During deployment GitHub Actions builds the Docker image, pushes it, and Kubernetes restarts the service. This step is fully automated.
- On startup the container executes Flyway against its database schema before the Spring application fully starts.
- The [`dev-tools/docs/generate-erd.sh`](../../dev-tools/docs/generate-erd.sh) script uses Flyway to clean and migrate temporary databases when generating ERD diagrams.
- Diagrams are written to `design/erd/` and the CI workflow collects this
   directory as an artifact.

Migrations are therefore applied consistently in every environment without manual steps.

---

## Related Documentation

- [Backup & Disaster Recovery](./system-architecture-backup-recovery.md)
- [CI/CD Pipeline](./system-architecture-cicd.md)
