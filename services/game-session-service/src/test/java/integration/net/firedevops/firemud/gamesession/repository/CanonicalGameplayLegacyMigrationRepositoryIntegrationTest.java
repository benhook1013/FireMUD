package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayLegacyMigrationRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void durableFenceAndPhysicalIdentityDoNotPublishV31BeforeReadback() {
    String schema = "gs_legacy_migration_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
      var repository = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
      UUID cohortId = UUID.randomUUID();
      UUID writerFence = UUID.randomUUID();
      var identity = identity();
      var operation = repository.begin(cohortId, writerFence, identity);
      var fencedCohort =
          new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
            @Override
            public UUID cohortId() {
              return cohortId;
            }

            @Override
            public UUID legacyWriterFence() {
              return writerFence;
            }

            @Override
            public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
              return identity;
            }

            @Override
            public void requireStillFenced(
                CanonicalGameplayLegacyMigrationStorageIdentity expectedIdentity) {
              assertThat(expectedIdentity).isEqualTo(identity);
            }
          };

      assertThat(operation.state())
          .isEqualTo(CanonicalGameplayLegacyMigrationOperation.State.FENCED);
      var initialDisposition =
          java.util.Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT disposition_state FROM game_session_canonical_binding_legacy_disposition"
                      + " WHERE singleton_id = 1"),
              "initial legacy disposition row");
      assertThat(initialDisposition.get("disposition_state", String.class)).isEqualTo("REQUIRED");
      assertThatThrownBy(() -> repository.publishVerified(operation.operationId(), fencedCohort))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("Only exact complete readback");
      assertThat(repository.read(operation.operationId()).state())
          .isEqualTo(CanonicalGameplayLegacyMigrationOperation.State.FENCED);
      var retainedDisposition =
          java.util.Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT disposition_state FROM game_session_canonical_binding_legacy_disposition"
                      + " WHERE singleton_id = 1"),
              "retained legacy disposition row");
      assertThat(retainedDisposition.get("disposition_state", String.class)).isEqualTo("REQUIRED");
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void futureRevisionRowsBlockInsteadOfDisappearingFromTheCanonicalSnapshot() {
    String schema = "gs_legacy_future_revision_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingInventoryRepositoryIntegrationTest.seedActiveSource(
          dsl,
          CanonicalGameplayBindingInventoryRepositoryIntegrationTest.identity(
              "dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
      dsl.execute(
          "UPDATE game_session_canonical_binding_inventory_clock"
              + " SET inventory_revision = 7 WHERE singleton_id = 1");

      var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
      var repository = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
      var operation = repository.begin(UUID.randomUUID(), UUID.randomUUID(), identity());
      var canonical = inventory.readSnapshot();
      assertThat(canonical.inventoryRevision()).isEqualTo(java.math.BigInteger.valueOf(7));
      assertThat(canonical.bindings()).isEmpty();

      assertThatThrownBy(
              () ->
                  repository.captureSnapshots(
                      operation.operationId(), emptyLegacySnapshot(), canonical))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("rows beyond the inventory clock");
      assertThat(repository.read(operation.operationId()).state())
          .isEqualTo(CanonicalGameplayLegacyMigrationOperation.State.FENCED);
      var inventoryRowCount =
          java.util.Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT count(*) AS row_count FROM game_session_canonical_gameplay_binding_inventory"),
              "canonical inventory row count");
      assertThat(inventoryRowCount.get("row_count", Long.class)).isEqualTo(1L);
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void nonemptyLegacyInventoryBlocksBeforeAnyIndexRebuildOrSourceDeletion() {
    String schema = "gs_legacy_nonempty_source_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
      var repository = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
      var cohortId = UUID.randomUUID();
      var writerFence = UUID.randomUUID();
      var identity = identity();
      var cohort =
          new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
            @Override
            public UUID cohortId() {
              return cohortId;
            }

            @Override
            public UUID legacyWriterFence() {
              return writerFence;
            }

            @Override
            public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
              return identity;
            }

            @Override
            public void requireStillFenced(
                CanonicalGameplayLegacyMigrationStorageIdentity expectedIdentity) {
              assertThat(expectedIdentity).isEqualTo(identity);
            }
          };
      var retainedSourceRows = new ArrayList<>(List.of("sessionctx:tenant:retained"));
      var rebuildCalls = new AtomicInteger();
      var owner =
          new CanonicalGameplayLegacyMigrationOwner(
              repository,
              inventory,
              () -> cohort,
              ignored ->
                  new CanonicalGameplayLegacyMigrationSourceSnapshot(
                      completeLegacyFamilies(),
                      List.of(
                          new CanonicalGameplayLegacyMigrationSourceSnapshot.Entry(
                              CanonicalGameplayLegacyMigrationSourceSnapshot.Family
                                  .TENANT_SESSION_CONTEXT,
                              "sha256:" + "a".repeat(64),
                              CanonicalGameplayLegacyMigrationSourceSnapshot.Disposition
                                  .UNMAPPABLE))),
              new CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner() {
                @Override
                public void rebuildExact(
                    CanonicalGameplayLegacyMigrationOwner.FencedCohort ignoredCohort,
                    CanonicalGameplayLegacyMigrationSourceSnapshot legacySnapshot,
                    net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingInventorySnapshot
                        canonicalSnapshot) {
                  rebuildCalls.incrementAndGet();
                  retainedSourceRows.clear();
                }

                @Override
                public CanonicalGameplayLegacyMigrationReadback readBackExact(
                    CanonicalGameplayLegacyMigrationOwner.FencedCohort ignoredCohort,
                    net.firedevops.firemud.gamesession.binding
                            .CanonicalGameplayBindingInventorySnapshot
                        canonicalSnapshot) {
                  throw new AssertionError("readback must not run for a blocked retained source");
                }
              });

      var operation = owner.migrate();

      assertThat(operation.state())
          .isEqualTo(CanonicalGameplayLegacyMigrationOperation.State.BLOCKED);
      assertThat(operation.blockedReason()).isEqualTo("UNMAPPABLE_SOURCE_RECORD");
      assertThat(rebuildCalls.get()).isZero();
      assertThat(retainedSourceRows).containsExactly("sessionctx:tenant:retained");
      var blockedDisposition =
          java.util.Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT disposition_state FROM game_session_canonical_binding_legacy_disposition"
                      + " WHERE singleton_id = 1"),
              "blocked legacy disposition row");
      assertThat(blockedDisposition.get("disposition_state", String.class)).isEqualTo("REQUIRED");
    } finally {
      dropSchema(schema);
    }
  }

  private static CanonicalGameplayLegacyMigrationStorageIdentity identity() {
    return new CanonicalGameplayLegacyMigrationStorageIdentity(
        "cluster-uid",
        "namespace-uid",
        "producer-pod-uid",
        "container-id",
        "node-uid",
        "987654321",
        42L,
        "postgres-volume-uid",
        "redis-run-id",
        "redis-volume-uid");
  }

  private static CanonicalGameplayLegacyMigrationSourceSnapshot emptyLegacySnapshot() {
    return new CanonicalGameplayLegacyMigrationSourceSnapshot(completeLegacyFamilies(), List.of());
  }

  private static EnumSet<CanonicalGameplayLegacyMigrationSourceSnapshot.Family>
      completeLegacyFamilies() {
    return EnumSet.of(
        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.TENANT_SESSION_CONTEXT,
        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.SESSION_ALIAS_CONTEXT,
        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.GAMEPLAY_IDENTITY_CONTEXT,
        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.GAMEPLAY_NAME_CONTEXT,
        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.MOVEMENT_EFFECT,
        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.DURABLE_EFFECT);
  }

  private static void migrate(String schema, DriverManagerDataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl() + "?currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose legacy migration test schema", failure);
    }
  }
}
