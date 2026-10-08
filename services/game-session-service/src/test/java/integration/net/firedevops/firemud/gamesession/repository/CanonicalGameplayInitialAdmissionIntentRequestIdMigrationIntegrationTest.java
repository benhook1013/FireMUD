package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingRuntimeTestFixtures;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for the V34.3 initial-admission intent request identity width. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayInitialAdmissionIntentRequestIdMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String RAW_SHA256 = "a".repeat(64);
  private static final String SHA256 = "sha256:" + RAW_SHA256;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void intentLedgerAcceptsAttemptWidthAndRejectsAnIdentityBeyond128Characters() {
    String schema = "gs_intent_width_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      var schemaReadback = dsl.fetchOne("SELECT current_schema() AS actual_schema");
      assertThat(schemaReadback.get("actual_schema", String.class)).isEqualTo(schema);

      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget length121 =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              dsl, UUID.randomUUID(), 2L, 24L, UUID.randomUUID());
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget length128 =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              dsl, UUID.randomUUID(), 3L, 25L, UUID.randomUUID());
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget length129 =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              dsl, UUID.randomUUID(), 4L, 26L, UUID.randomUUID());

      assertThat(insertIntent(dsl, length121, "r".repeat(121))).isEqualTo(1);
      assertThat(insertIntent(dsl, length128, "r".repeat(128))).isEqualTo(1);
      assertThatThrownBy(() -> insertIntent(dsl, length129, "r".repeat(129)))
          .isInstanceOf(DataAccessException.class);
    } finally {
      dropSchema(schema);
    }
  }

  private static int insertIntent(
      DSLContext dsl,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target,
      String requestId) {
    return dsl.execute(
        "INSERT INTO game_session_canonical_initial_admission_intent"
            + " (target_namespace, initial_admission_request_id, request_digest,"
            + " canonical_tenant_id, world_slug, realm_id, playable_state_namespace_id,"
            + " playable_state_scope, catalog_visible, catalog_public_production,"
            + " catalog_revision, catalog_creation_request_id, catalog_request_digest,"
            + " catalog_receipt_digest, tenant_association_operation_id, game_session_tenant_id,"
            + " canonical_game_instance_id, game_instance_id, canonical_version_id,"
            + " runtime_version_id, control_plane_request_id, launch_descriptor_id,"
            + " captured_starting_row_version, current_row_version, descriptor_request_digest,"
            + " descriptor_result_digest, release_attestation_digest, canonical_hold_request_bytes,"
            + " canonical_lifecycle_request_bytes, hold_identity_bytes, state, created_at,"
            + " updated_at, terminal_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 0, 1, ?, ?, ?, decode('01', 'hex'), decode('02', 'hex'), NULL, 'PENDING_HOLD',"
            + " now(), now(), NULL)",
        target.targetNamespace(),
        requestId,
        RAW_SHA256,
        target.canonicalTenantId(),
        target.worldSlug(),
        target.realmId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.catalogRevision(),
        target.catalogCreationRequestId(),
        SHA256,
        SHA256,
        target.tenantAssociationOperationId(),
        target.gameSessionTenantId(),
        target.gameInstanceUuid(),
        target.gameInstanceId(),
        target.canonicalVersionId(),
        target.runtimeVersionId(),
        target.controlPlaneRequestId(),
        target.launchDescriptorId(),
        SHA256,
        SHA256,
        SHA256);
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
    String jdbcUrl = postgres.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose intent-width test schema", failure);
    }
  }
}
