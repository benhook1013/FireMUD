# FireMUD System Architecture: Database Migrations

This document explains how FireMUD manages PostgreSQL schema changes across its microservices. Each service owns its tables and applies migrations independently.

## Implementation Notes

- FireMUD’s SQL target state is `jOOQ + Flyway`, with Flyway as the canonical schema authority and `jOOQ` generation/execution as the intended runtime access model for SQL-backed services.
- The `02.19` convergence family is now closed at the platform boundary: SQL-backed services use `jOOQ + Flyway`, and repo-wide Hibernate/JPA runtime support has been removed rather than preserved as a second persistence path.
- [ADR 0079](decisions/adr-0079-jooq-and-flyway-as-the-single-sql-persistence-stack.md) makes generated Flyway-derived jOOQ types the default query surface. Dynamic or plain SQL is permitted only as a focused escape hatch for a required PostgreSQL feature the generated or jOOQ DSL surface cannot express; it remains inside the owning persistence boundary and must prove schema alignment, parameter handling, result mapping, transaction behavior, and relevant failure cases.

---

## Migration Tool

- **Flyway** is used for all schema migrations.
- The root `build.gradle.kts` applies the `org.flywaydb.flyway` plugin and adds
  `flyway-core`, `flyway-database-postgresql`, and the PostgreSQL driver for
  every SQL-backed service module under `services/`.
- Versioned SQL files live under each service in `src/main/resources/db/migration/`.
- Migrations follow the `V<version>__<description>.sql` naming convention.
- Every service-local module begins with a `V1` baseline migration. On unsquashed services that is still usually `V1__init.sql`; on destructive pre-v1 squash targets it becomes a canonical `V1__baseline.sql` that replaces the older local chain.
- Shared saga migrations live in the `common-saga` module under `src/main/resources/db/migration/saga` and are bundled onto consuming service classpaths as the additional `classpath:db/migration/saga` Flyway location alongside the owning service's `classpath:db/migration`.
- Shared migration artifacts use a repository-checked version convention that cannot collide with adopter-local versions and remain compatible with supported library/adopter combinations during rollout. An applied shared migration is never renumbered or rewritten.
- `spring.flyway.enabled=true` in `application.yml` triggers migration execution on startup.
- SQL-backed service `application.yml` files also carry an explicit `spring.flyway.table` contract in the same `flyway_schema_history_<service_schema>` form used by local destructive reset tooling and hosted/runtime manifests, so plain service boot does not silently fall back to bare `flyway_schema_history`.
- Generated `jOOQ` sources derive from these migrated schemas rather than from a second hand-maintained SQL model.
- Application code uses those generated schema types by default. A bounded unsupported-feature exception may use dynamic or plain SQL under ADR 0079, but Flyway remains the sole durable-object authority and convenience does not justify a parallel DAO or schema model.
- The shared `jOOQ` foundation exposes a canonical `:service:generateJooq` task that derives DSL code directly from `src/main/resources/db/migration/*.sql`.
- Flyway reads connection settings from the `FIREMUD_POSTGRES_*` environment variables described in
  [Environment & Secrets](./infrastructure/environment-and-secrets.md).
- Local destructive reset and standalone Gradle Flyway workflows also need the owning service schema and Flyway history table to stay aligned with the runtime service configuration. In this repo that means local tooling should preserve `SERVICE_SCHEMA`, `SPRING_FLYWAY_TABLE`, `FLYWAY_SCHEMAS`, `FLYWAY_DEFAULT_SCHEMA`, and `FLYWAY_TABLE` instead of silently falling back to `public` and the default `flyway_schema_history`.
- Java-based callbacks are avoided; migrations remain SQL-only for portability.

## Per-Service Organization

- Every microservice maintains its own migration folder and changelog.
- Schemas are isolated: services never modify each other's tables.
- Tables reside in dedicated schemas for each service to ensure isolation.
  Schema names match the owning service (for example `account_service`). See
  [Multi-Tenancy](./system-architecture-multi-tenancy.md) for details.
- Shared schema components such as the Saga tables are defined in the
  `common-saga` module with their own migrations, but are still applied
  **per service database**:
  - The `common-saga` module contains the shared saga table migrations described in
    [System Architecture – Transactions](./system-architecture-transactions.md).
  - Services that need these shared tables include `common-saga` as a dependency and expose both `classpath:db/migration` and `classpath:db/migration/saga` to Flyway at startup.
  - Saga tables are created inside the owning service schema via the shared `${serviceSchema}.saga_*` migrations rather than a separate dedicated `saga` schema.
- New migrations are committed alongside service code so history stays with the owning service.

[ADR 0080](decisions/adr-0080-service-owned-schemas-with-adopter-local-shared-migrations.md) defines the shared-component boundary. The reusable library owns the definition of its tables and migration artifacts, while each adopter owns deliberate inclusion, its schema and Flyway history, deployment timing, and failure recovery. Services do not apply cross-service DDL, create cross-service foreign keys, or directly read another service's tables. Multiple service schemas may share one physical PostgreSQL deployment without changing those logical ownership rules.

### Version-Aware Migration Guidelines

[ADR 0081](decisions/adr-0081-objective-compatibility-gates-for-database-evolution.md) replaces a subjective “initial development” boundary with two objective compatibility questions. Expand/migrate/contract is required when either old and new application binaries can overlap during a supported deployment or rollback window, or retained durable data—including a non-Retired game version—still requires the old representation to remain readable or reconstructable.

Direct replacement remains appropriate only when neither obligation exists: no retained data requires compatibility, no old reader or writer may coexist or be restored, and all call sites, generated SQL access, tests, deployment configuration, and documentation can converge atomically. Pre-v1 status alone does not establish this exception.

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
engineers should follow this checklist whenever retained data or supported old/new
binary overlap activates the compatibility gates:

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

Direct-replacement exception:

- A service or environment may replace schema and contracts directly only when
  it has no retained-data or non-Retired-version preservation requirement, no
  supported overlap with or rollback to an old reader or writer, and every call
  site, generated SQL surface, test, deployment configuration, and document can
  converge atomically.
- The change records the evidence for both gates. Labels such as pre-v1,
  bootstrap, development, or production are not substitutes for that evidence.
- Contract-phase removal is allowed only after both the supported binary
  overlap/rollback window and every applicable durable-data or game-version
  dependency have ended under their owning release and retention policies.

### Cross-Service Identifier Migration

Some schema changes affect identifiers or records referenced across multiple services. [ADR 0082](decisions/adr-0082-semantic-boundary-for-cross-service-identifier-migration.md) first classifies each change by logical identity rather than treating every representation migration as object replacement:

- Preserve the logical identifier when its referent, ownership scope, cardinality, and domain meaning remain unchanged and only a column name, database representation, wire representation, or storage encoding changes. Use the applicable reader/writer and database compatibility process without introducing a replacement mapping.
- Allocate new identifiers and explicit durable typed mappings when identity or meaning changes, including replacement, ownership/scope change, split, or merge. Mappings retain unambiguous lineage for as long as a retained version, durable record, retry, reconciliation path, audit, or rollback may reference the old identity.
- Never repurpose an old identifier to mean the replacement object. A split or merge cannot be hidden behind one ambiguous alias.

The authoritative owner of the affected relationship or version graph coordinates an identity-changing migration. Game Design owns this role for design-time template and published-release graphs; another domain remains the authority for its account, runtime, operational, or other identifier family. For template-graph migrations, introduce replacement template rows in the owning service, deprecate the old templates at the design layer, and use Game Design workflows to migrate references in:
  - World templates and population bindings (World Management),
  - Entity templates and loot tables (Entity Management),
  - Game templates and configuration payloads (Game Design Service),
  - Script or automation bindings (Automation & Scripting).
- Coordinate these reference updates under a dedicated migration or workflow that:
  - Updates all affected cross-service mappings to point at the new identifiers for Draft or upcoming versions; and
  - Ensures that no non-Retired version or live `gameInstanceId` relies on the old identifiers before they are removed.
- Only once all versions that reference the old identifiers have been retired, and all cross-service mappings have been updated, may destructive DDL (for example dropping deprecated columns or tables) be applied.

The Game Design Service remains the source of truth for which versions and revisions reference which domain templates; template-graph identifier migrations are orchestrated from there so migration steps can be validated against version metadata before rollout. This does not make Game Design the migration authority for unrelated identifier families.

Release-attestation records are part of the same migration surface:

- Any schema change that alters the meaning of publish digests, asset manifest hashes, or frozen generation-config identities must update the `published_release_bundle` writer/reader contracts in lockstep.
- `published_release_bundle` is persisted in the Game Design Service schema and its migrations are owned by Game Design. Other services may consume the attestation only through the canonical `GetPublishedReleaseBundle(tenantId, versionId)` read contract and must not define independent schema ownership for that record.
- Activation and repair workflows must continue to understand prior non-Retired attestation schema versions until the corresponding versions are retired or receive an explicitly authorized successor attestation with lineage.

For manifest-like metadata such as the asset `manifest.json` files generated by Game Design Service, schema evolution follows the same rules:

- Each manifest includes an explicit `schemaVersion` field as described in `microservices/game-design-service/asset-storage.md`.
- New manifest fields and semantics are introduced under a new `schemaVersion`, and clients must remain able to read existing non-Retired manifest versions until the corresponding game versions are retired.
- Published/Active attested releases are not rewritten in place. A separately authorized re-attestation workflow creates a new attestation record with explicit lineage to the prior record and preserves the historical attestation.
- If a runtime consumer cannot interpret a manifest `schemaVersion` for a launch-critical release, it must fail closed rather than guessing field locations or object keys.

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
- For service-scoped local rebuilds, use [`dev-tools/restores/reset-service-db.sh`](../../dev-tools/restores/reset-service-db.sh) instead of `flywayClean`. It drops only the tables created by that service's migrations plus that service's Flyway history table, and saga-backed services also include the shared saga tables in that destructive scope before rerunning `flywayMigrate` for the same service. The script waits for local Postgres readiness, exports standard `FLYWAY_*` connection variables, and preserves the owning service schema plus Flyway history table (`SERVICE_SCHEMA`, `SPRING_FLYWAY_TABLE`, `FLYWAY_SCHEMAS`, `FLYWAY_DEFAULT_SCHEMA`, `FLYWAY_TABLE`) before rerunning migrations. Pass `--dry-run` first if you want to inspect the destructive scope.
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
