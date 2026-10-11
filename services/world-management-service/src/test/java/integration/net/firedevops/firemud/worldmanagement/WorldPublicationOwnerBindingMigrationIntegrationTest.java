package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldPublicationOwnerBindingMigrationIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final String DIGEST = "a".repeat(64);
  private static final String REQUEST_DIGEST = "sha256:" + DIGEST;
  private static final long GAME_DESIGN_VERSION_ID = 9_000_000_042L;
  private static final String WORKFLOW_ID_PREFIX = "publish:";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void freshV27PointOneMigrationAcceptsOnlyExactVersionIdentityBindings() throws SQLException {
    String schema = newSchema("world_publication_owner_fresh_");
    try {
      migrateFixture(schema, MigrationVersion.fromVersion("27.1"));
      try (Connection connection = fixtureConnection(schema)) {
        Fixture fixture = seedOwnerBindingData(connection);
        insertAttempt(
            connection,
            fixture,
            "fresh-exact",
            1,
            fixture.versionIdentityOperationId(),
            fixture.canonicalVersionId(),
            GAME_DESIGN_VERSION_ID,
            REQUEST_DIGEST);

        assertRejected(
            connection,
            () ->
                insertAttempt(
                    connection,
                    fixture,
                    "fresh-incomplete",
                    1,
                    fixture.versionIdentityOperationId(),
                    null,
                    GAME_DESIGN_VERSION_ID,
                    REQUEST_DIGEST),
            "23514");
        assertRejected(
            connection,
            () ->
                insertAttempt(
                    connection,
                    fixture,
                    "fresh-unknown-schema",
                    2,
                    fixture.versionIdentityOperationId(),
                    fixture.canonicalVersionId(),
                    GAME_DESIGN_VERSION_ID,
                    REQUEST_DIGEST),
            "23514");
        assertRejected(
            connection,
            () ->
                insertAttempt(
                    connection,
                    fixture,
                    "fresh-cross-bound",
                    1,
                    fixture.versionIdentityOperationId(),
                    fixture.canonicalVersionId(),
                    GAME_DESIGN_VERSION_ID + 1,
                    REQUEST_DIGEST),
            "23503");
      }
    } finally {
      dropFixtureSchema(schema);
    }
  }

  @Test
  void v27UpgradeRetainsAttemptsAsSchemaZeroWithoutInferringIdentity() throws SQLException {
    String schema = newSchema("world_publication_owner_v27_");
    UUID legacyFence = UUID.randomUUID();
    try {
      migrateFixture(schema, MigrationVersion.fromVersion("27"));
      try (Connection connection = fixtureConnection(schema)) {
        Fixture fixture = seedOwnerBindingData(connection);
        assertThat(fixture.localVersionKey()).isNotEqualTo(GAME_DESIGN_VERSION_ID);
        insertLegacyAttempt(connection, fixture, legacyFence);
      }

      migrateFixture(schema, MigrationVersion.fromVersion("27.1"));

      try (Connection connection = fixtureConnection(schema)) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT owner_binding_schema_version, canonical_version_id, "
                    + "version_identity_operation_id, game_design_version_id, "
                    + "intake_request_digest, version_id FROM world_design_publication_fence_attempt "
                    + "WHERE publication_fence = ?")) {
          statement.setObject(1, legacyFence);
          try (ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getShort("owner_binding_schema_version")).isZero();
            assertThat(result.getObject("canonical_version_id")).isNull();
            assertThat(result.getObject("version_identity_operation_id")).isNull();
            assertThat(result.getObject("game_design_version_id")).isNull();
            assertThat(result.getString("intake_request_digest")).isNull();
            assertThat(result.getLong("version_id")).isEqualTo(GAME_DESIGN_VERSION_ID);
            assertThat(result.next()).isFalse();
          }
        }

        assertRejected(
            connection, () -> updateLegacyAttemptBinding(connection, legacyFence), "55000");
      }
    } finally {
      dropFixtureSchema(schema);
    }
  }

  private static Fixture seedOwnerBindingData(Connection connection) throws SQLException {
    UUID canonicalTenantId = UUID.randomUUID();
    UUID intakeOperationId = UUID.randomUUID();
    UUID intakeRequestId = UUID.randomUUID();
    UUID sourceRegistrationRequestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    UUID canonicalVersionId = UUID.randomUUID();
    UUID versionIdentityOperationId = UUID.randomUUID();
    long localTenantKey = 7_001L;
    String tenantSlug = "tenant-" + canonicalTenantId.toString().replace("-", "");
    String worldSlug = "migration-proof-world";

    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_authored_source_tenant_association "
                + "(target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id, "
                + "source_game_tenant_key, source_provenance_kind, local_tenant_key) "
                + "VALUES (?, ?, ?, 77, 'migration-proof-source', 'NEW_GAME_ROW', ?)")) {
      statement.setString(1, NAMESPACE);
      statement.setObject(2, canonicalTenantId);
      statement.setString(3, tenantSlug);
      statement.setLong(4, localTenantKey);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_authored_source_tenant_key_reservation "
                + "(tenant_key, claim_kind, target_namespace, canonical_tenant_id) "
                + "VALUES (?, 'CANONICAL_AUTHORED_SOURCE', ?, ?)")) {
      statement.setLong(1, localTenantKey);
      statement.setString(2, NAMESPACE);
      statement.setObject(3, canonicalTenantId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_authored_source_intake (operation_id, schema_version, "
                + "target_namespace, intake_request_id, request_digest, local_tenant_key, "
                + "source_schema_version, source_registration_request_id, source_operation_id, "
                + "source_request_digest, canonical_tenant_id, tenant_slug, world_slug, "
                + "world_display_name, source_game_row_id, source_game_tenant_key, "
                + "source_provenance_kind, source_evidence_digest, receipt_digest) "
                + "VALUES (?, 1, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 'Migration proof', 77, "
                + "'migration-proof-source', 'NEW_GAME_ROW', ?, ?)")) {
      statement.setObject(1, intakeOperationId);
      statement.setString(2, NAMESPACE);
      statement.setObject(3, intakeRequestId);
      statement.setString(4, REQUEST_DIGEST);
      statement.setLong(5, localTenantKey);
      statement.setObject(6, sourceRegistrationRequestId);
      statement.setObject(7, sourceOperationId);
      statement.setString(8, REQUEST_DIGEST);
      statement.setObject(9, canonicalTenantId);
      statement.setString(10, tenantSlug);
      statement.setString(11, worldSlug);
      statement.setString(12, REQUEST_DIGEST);
      statement.setString(13, REQUEST_DIGEST);
      statement.executeUpdate();
    }

    long localVersionKey;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_authored_version_identity (operation_id, target_namespace, "
                + "canonical_tenant_id, world_slug, canonical_version_id, game_design_version_id, "
                + "intake_operation_id, intake_request_id, local_tenant_key, intake_request_digest, "
                + "source_operation_id, source_evidence_digest, intake_receipt_digest, "
                + "version_state_schema_version, version_state_read_request_id, "
                + "version_state_state, version_state_epoch, version_state_evidence_digest, "
                + "version_state_evidence_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 'PUBLISHED', 1, ?, '{}') "
                + "RETURNING local_version_key")) {
      statement.setObject(1, versionIdentityOperationId);
      statement.setString(2, NAMESPACE);
      statement.setObject(3, canonicalTenantId);
      statement.setString(4, worldSlug);
      statement.setObject(5, canonicalVersionId);
      statement.setLong(6, GAME_DESIGN_VERSION_ID);
      statement.setObject(7, intakeOperationId);
      statement.setObject(8, intakeRequestId);
      statement.setLong(9, localTenantKey);
      statement.setString(10, REQUEST_DIGEST);
      statement.setObject(11, sourceOperationId);
      statement.setString(12, REQUEST_DIGEST);
      statement.setString(13, REQUEST_DIGEST);
      statement.setObject(14, UUID.randomUUID());
      statement.setString(15, REQUEST_DIGEST);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        localVersionKey = result.getLong(1);
      }
    }

    return new Fixture(
        canonicalTenantId,
        localTenantKey,
        intakeOperationId,
        intakeRequestId,
        sourceOperationId,
        canonicalVersionId,
        versionIdentityOperationId,
        localVersionKey);
  }

  private static void insertLegacyAttempt(
      Connection connection, Fixture fixture, UUID publicationFence) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO world_design_publication_fence_attempt (publication_fence, "
                + "target_namespace, canonical_tenant_id, local_tenant_key, version_id, "
                + "intake_operation_id, intake_request_id, source_operation_id, "
                + "source_evidence_digest, intake_receipt_digest, publication_request_id, "
                + "request_digest, version_state_epoch, publish_workflow_id, applied_commit_id, "
                + "content_digest, digest_schema_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      bindCommonAttempt(
          statement,
          fixture,
          publicationFence,
          GAME_DESIGN_VERSION_ID,
          "legacy-request",
          "legacy-commit");
      statement.executeUpdate();
    }
  }

  private static void insertAttempt(
      Connection connection,
      Fixture fixture,
      String publicationRequestId,
      int ownerBindingSchemaVersion,
      UUID versionIdentityOperationId,
      UUID canonicalVersionId,
      long gameDesignVersionId,
      String intakeRequestDigest)
      throws SQLException {
    String sql =
        "INSERT INTO world_design_publication_fence_attempt (publication_fence, "
            + "target_namespace, canonical_tenant_id, local_tenant_key, version_id, "
            + "intake_operation_id, intake_request_id, source_operation_id, "
            + "source_evidence_digest, intake_receipt_digest, publication_request_id, "
            + "request_digest, version_state_epoch, publish_workflow_id, applied_commit_id, "
            + "content_digest, digest_schema_version, owner_binding_schema_version, "
            + "canonical_version_id, version_identity_operation_id, game_design_version_id, "
            + "intake_request_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      UUID fence = UUID.randomUUID();
      bindCommonAttempt(
          statement,
          fixture,
          fence,
          fixture.localVersionKey(),
          publicationRequestId,
          "bound-commit");
      statement.setInt(18, ownerBindingSchemaVersion);
      statement.setObject(19, canonicalVersionId);
      statement.setObject(20, versionIdentityOperationId);
      if (gameDesignVersionId > 0L) {
        statement.setLong(21, gameDesignVersionId);
      } else {
        statement.setNull(21, java.sql.Types.BIGINT);
      }
      statement.setString(22, intakeRequestDigest);
      statement.executeUpdate();
    }
  }

  private static void bindCommonAttempt(
      PreparedStatement statement,
      Fixture fixture,
      UUID publicationFence,
      long versionId,
      String publicationRequestId,
      String commitId)
      throws SQLException {
    statement.setObject(1, publicationFence);
    statement.setString(2, NAMESPACE);
    statement.setObject(3, fixture.canonicalTenantId());
    statement.setLong(4, fixture.localTenantKey());
    statement.setLong(5, versionId);
    statement.setObject(6, fixture.intakeOperationId());
    statement.setObject(7, fixture.intakeRequestId());
    statement.setObject(8, fixture.sourceOperationId());
    statement.setString(9, REQUEST_DIGEST);
    statement.setString(10, REQUEST_DIGEST);
    statement.setString(11, publicationRequestId);
    statement.setString(12, DIGEST);
    statement.setLong(13, 1L);
    statement.setString(
        14,
        WORKFLOW_ID_PREFIX
            + fixture.canonicalTenantId()
            + ":publish-request:"
            + publicationRequestId);
    statement.setString(15, commitId);
    statement.setString(16, DIGEST);
    statement.setInt(17, 3);
  }

  private static void assertRejected(
      Connection connection, SqlAction action, String expectedSqlState) throws SQLException {
    try {
      action.run();
    } catch (SQLException exception) {
      assertThat(exception.getSQLState()).isEqualTo(expectedSqlState);
      return;
    }
    throw new AssertionError("Expected PostgreSQL SQLSTATE " + expectedSqlState);
  }

  private static void updateLegacyAttemptBinding(Connection connection, UUID legacyFence)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE world_design_publication_fence_attempt "
                + "SET owner_binding_schema_version = 1, canonical_version_id = "
                + "'11111111-1111-4111-8111-111111111111'::uuid "
                + "WHERE publication_fence = ?")) {
      statement.setObject(1, legacyFence);
      statement.executeUpdate();
    }
  }

  private static void migrateFixture(String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static Connection fixtureConnection(String schema) throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    connection.setSchema(schema);
    return connection;
  }

  private static String newSchema(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  private static void dropFixtureSchema(String schema) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private record Fixture(
      UUID canonicalTenantId,
      long localTenantKey,
      UUID intakeOperationId,
      UUID intakeRequestId,
      UUID sourceOperationId,
      UUID canonicalVersionId,
      UUID versionIdentityOperationId,
      long localVersionKey) {}

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }
}
