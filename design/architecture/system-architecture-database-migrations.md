# FireMUD System Architecture: Database Migrations

This document is the current target authority for SQL persistence, schema ownership, migration compatibility, and cross-service identifier migration classification and routing. Each service owns its logical PostgreSQL schema and applies its own Flyway history; reusable migration artifacts are adopter-local, not a central schema authority. For identifier migrations, this document classifies and routes the change; concrete mapping and workflow authority remains with the affected relationship or version-graph owner under [ADR 0082](./decisions/adr-0082-semantic-boundary-for-cross-service-identifier-migration.md).

## Implementation Notes

- FireMUD’s SQL target state is `jOOQ + Flyway`, with Flyway as the canonical schema authority and `jOOQ` generation/execution as the intended runtime access model for SQL-backed services.
- The `02.19` convergence family is now closed at the platform boundary: SQL-backed services use `jOOQ + Flyway`, and repo-wide Hibernate/JPA runtime support has been removed rather than preserved as a second persistence path.
- Plain or dynamic SQL is permitted only as a narrow, proven escape hatch for a PostgreSQL feature that the applicable generated jOOQ/DSL surface cannot express; it is not a second repository or schema model.
- The target contract below is authoritative even where implementation notes identify incomplete migration wiring, generated-code adoption, or focused proof.

---

## Migration Tool

- **Flyway** is used for all schema migrations.
- The root `build.gradle.kts` applies the `org.flywaydb.flyway` plugin and adds
  `flyway-core`, `flyway-database-postgresql`, and the PostgreSQL driver for
  every SQL-backed service module under `services/`.
- Versioned SQL files live under each service in `src/main/resources/db/migration/`.
- Migrations follow the `V<version>__<description>.sql` naming convention.
- Every service-local module begins with a `V1` baseline migration. On unsquashed services that is still usually `V1__init.sql`; on destructive pre-v1 squash targets it becomes a canonical `V1__baseline.sql` that replaces the older local chain.
- Shared saga migrations live in the `common-saga` module under `src/main/resources/db/migration/saga` and standalone service runtimes retain the combined `classpath:db/migration,classpath:db/migration/saga` Flyway locations. A multi-service runtime that shares one classpath must instead select service-scoped migration locations so duplicate `V1__baseline.sql` resources cannot collide. [`RuntimeRegionStatusRepositoryIntegrationTest`](../../services/game-session-service/src/test/java/integration/net/firedevops/firemud/gamesession/repository/RuntimeRegionStatusRepositoryIntegrationTest.java) demonstrates the existing scoped-location pattern by pointing Flyway only at the game-session service's `src/main/resources/db/migration` directory; dedicated multi-service-startup proof remains absent.
- `spring.flyway.enabled=true` in `application.yml` triggers migration execution on startup.
- SQL-backed service `application.yml` files also carry an explicit `spring.flyway.table` contract in the same `flyway_schema_history_<service_schema>` form used by local destructive reset tooling and hosted/runtime manifests, so plain service boot does not silently fall back to bare `flyway_schema_history`.
- Generated `jOOQ` sources derive from these migrated schemas rather than from a second hand-maintained SQL model.
- The shared `jOOQ` foundation exposes a canonical `:service:generateJooq` task that derives DSL code directly from `src/main/resources/db/migration/*.sql`.
- Flyway reads connection settings from the `FIREMUD_POSTGRES_*` environment variables described in
  [Environment & Secrets](./infrastructure/environment-and-secrets.md).
- Local destructive reset and standalone Gradle Flyway workflows also need the owning service schema and Flyway history table to stay aligned with the runtime service configuration. In this repo that means local tooling should preserve `SERVICE_SCHEMA`, `SPRING_FLYWAY_TABLE`, `FLYWAY_SCHEMAS`, `FLYWAY_DEFAULT_SCHEMA`, and `FLYWAY_TABLE` instead of silently falling back to `public` and the default `flyway_schema_history`.
- SQL migrations are the default and Java-based callbacks are avoided. The active Game Session `V8__remote_followup_target_instance_effect_identity` Flyway Java migration is the explicitly documented nontransactional exception; it remains an implementation/proof gap requiring focused exception and deployment proof, as recorded in the [shared runtime and persistence tracker](../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md#packet-5-tail-and-packet-6-p0-status-and-proof-gaps). It must not be generalized into a preferred migration path.

## Per-Service Organization

- Every microservice maintains its own migration folder and changelog.
- Service Flyway migrations own only schema-scoped objects in that service's schema. Shared database- or cluster-scoped objects such as extensions, roles, and cluster settings have one platform/database owner and a versioned infrastructure/database migration lineage; service migrations may assert those prerequisites but must not independently create or upgrade them.
- Schemas are isolated: services never modify each other's tables.
- Tables reside in dedicated schemas for each service to ensure isolation.
  Schema names match the owning service (for example `account_service`). See
  [Multi-Tenancy](./system-architecture-multi-tenancy.md) for details.
- Shared schema components such as the Saga tables are defined in the
  `common-saga` module with reusable migration artifacts, but are still applied
  **inside each adopting service schema**:
  - The `common-saga` module contains the shared saga table migrations described in
    [System Architecture – Transactions](./system-architecture-transactions.md).
  - Only services that adopt these shared tables include `common-saga` as a dependency and expose both `classpath:db/migration` and `classpath:db/migration/saga` to Flyway at startup.
  - Saga tables are created inside the owning service schema via the shared `${serviceSchema}.saga_*` migrations rather than a separate dedicated `saga` schema.
- New migrations are committed alongside service code so history stays with the owning service.

Shared migration artifacts use a collision-free versioning convention relative to adopter-local migrations. An applied shared migration is never renumbered, rewritten, or silently replaced; a shared change is a new compatible migration under that convention. A physical PostgreSQL cluster or database may host multiple service schemas, but that does not permit cross-service DDL, foreign keys, direct table reads, or one service to advance or repair another service's Flyway history.

### Objective Compatibility Gates

Every shape-changing migration evaluates two independent questions:

1. Can an older and newer application binary read or write the database during deployment, rollback, recovery, or another supported compatibility window?
2. Does retained durable data, including any non-Retired game version, still require the old representation to remain readable or reconstructable?

If either answer is yes, use expand/migrate/contract. Expand introduces a representation compatible with supported readers and writers; migrate backfills and verifies retained data; contract removes the old representation only after binary compatibility, rollback, and every retained-data or game-version dependency has ended under its owning lifecycle and retention policy.

Direct replacement is allowed only when both answers are no and all call sites, tests, migrations, generated SQL access, deployment configuration, and documentation can converge atomically. Pre-v1 or “initial development” status alone does not grant this exception. The qualifying evidence is service- and environment-specific and must be recorded with the change. Retired or unsupported representations need not remain executable forever, but contraction must not outrun a declared obligation.

These gates apply equally to service-local schemas and adopter-local shared migrations. A fixed time window cannot override an actively retained game version or durable-data obligation.

### Version-Aware Migration Checklist

Before applying a destructive or shape-changing migration, the owning service must record the following evidence whenever either compatibility gate may be true:

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

Once a service has no active compatibility obligation, it may converge directly only when the objective evidence and atomic-convergence conditions above are recorded; project phase labels do not substitute for that evidence.

### Cross-Service Identifier Migration

Classify each identifier migration before changing storage or wire shape:

- Preserve the existing logical identifier when referent, ownership scope, cardinality, and domain meaning are unchanged and only database representation, column name, wire representation, or storage encoding changes. Apply the relevant reader/writer compatibility gates.
- Allocate a new identifier and create an explicit durable, typed old-to-new mapping when logical identity changes, including material semantic replacement, ownership/scope change, split, or merge. Mappings are idempotent, record type and lineage, and remain while retained versions, durable records, retries, reconciliation, audit, or rollback may reference the old identity. Splits and merges cannot use one ambiguous alias.
- The authoritative owner of the affected relationship or version graph coordinates the migration. Game Design owns published template and release graphs and validates World, Entity, configuration, asset, and automation references; other identifier families use their own domain authority. Services do not rewrite another owner's records or infer mappings from identifier shape.
- Published and Active release attestations are immutable. Replacements or explicitly authorized re-attestations create new records and lineage. Launch-critical consumers fail closed on unknown manifest `schemaVersion`; representation-compatible additive evolution may remain within a supported version.

Cross-service identifier migrations are design-time workflows, not ad-hoc SQL rewrites. The current implementation and proof of a complete durable migration workflow remain incomplete; target conformance must not be inferred from partial remap records or launch gates.

Runtime replacement adds a mandatory local schema consequence: each service that owns runtime state must record whether a family is S1 namespace-stable, S2 mapping-dependent, or S3 disposable. S1/S2 durable identity uses the tenant, `playableStateNamespaceId`, and owning-domain identity; immutable `playableStateScope` is separately persisted and exact-validated as policy/routing/authorization evidence, and the current `gameInstanceId` is validated separately as the active-instance authorization fence. Only an owner-declared S3 family includes `gameInstanceId` in durable identity. Unknown, unowned, or unclassified families block cutover. A `remapSetId` echoed by a caller is evidence to resolve, not proof that the owning service validated and applied the mapping. The lifecycle owner registry must include every new instance-owned family and its idempotent cleanup/acknowledgement contract before launch paths write it. Concrete scheduler and tick/effect family classifications belong to those owner documents; this migration rule does not assign every runtime row one blanket class. See [ADR 0122](./decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md) and [ADR 0123](./decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md).

Runtime migration validation must keep `gameInstanceId` separate from S1/S2 identity and validate the exact runtime scope at the authoritative mutation boundary. The scope predicate and its active-instance authorization token must be checked atomically with enqueue, claim, or mutation, or immediately before apply against that authoritative token; a detached precheck cannot authorize later work. A stale, missing, or mismatched scope token fails closed.

Design-time multi-owner Draft tables likewise retain owner-local revisions/epochs and immutable base digests. Migrations must preserve durable per-owner outcomes and the synchronized commit-fence/read projection; they must not introduce a global epoch or cross-service foreign-key/transaction dependency. See [ADR 0129](./decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md).

Release-attestation records are part of the same migration surface:

- Any schema change that alters the meaning of publish digests, asset manifest hashes, or frozen generation-config identities must update the `published_release_bundle` writer/reader contracts in lockstep.
- `published_release_bundle` is persisted in the Game Design Service schema and its migrations are owned by Game Design. Other services may consume the attestation only through the canonical `GetPublishedReleaseBundle(tenantId, versionId)` read contract and must not define independent schema ownership for that record.
- Activation and repair workflows must continue to understand prior non-Retired attestation schema versions until the corresponding versions are retired or explicitly re-attested.

For manifest-like metadata such as the asset `manifest.json` files generated by Game Design Service, schema evolution follows the same rules:

- Each manifest includes an explicit `schemaVersion` field as described in `microservices/game-design-service/asset-storage.md`.
- New manifest fields and semantics are introduced under a new `schemaVersion`, and clients must remain able to read existing non-Retired manifest versions until the corresponding game versions are retired.
- Published/Active attested releases must not be migrated in place to a different manifest schema version by rerunning export unless a separate re-attestation workflow explicitly defines that contract.
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
