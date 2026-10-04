package net.firedevops.firemud.loggingadmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CanonicalAuditIdentityMigrationIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void versionThreePointOnePreservesRetainedVersionOneReceiptAndProjectionBytes()
      throws SQLException {
    migrateThrough("3");
    String eventId = "6ca11f0d-72e3-4c3f-b745-679775108404";
    UUID receiptId = UUID.fromString("ef23c682-65f9-4b4d-9ed8-071f8b29aa4f");
    byte[] payload =
        "{\"retained\":\"exact-v1-bytes\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    List<Object> projectionBefore;
    List<Object> receiptBefore;

    try (Connection connection = connection()) {
      insertVersionOneRows(connection, eventId, receiptId, payload);
      projectionBefore = readProjection(connection, eventId);
      receiptBefore = readReceipt(connection, eventId);
    }

    migrateThrough("3.1");

    try (Connection connection = connection()) {
      assertThat(readProjection(connection, eventId)).containsExactlyElementsOf(projectionBefore);
      assertThat(readReceipt(connection, eventId)).containsExactlyElementsOf(receiptBefore);
      try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT tenant_identity_version, tenant_uuid FROM log_events "
                      + "WHERE audit_event_id = ?");
          PreparedStatement receiptStatement =
              connection.prepareStatement(
                  "SELECT tenant_identity_version, tenant_uuid, payload FROM account_audit_receipts "
                      + "WHERE audit_event_id = ?")) {
        statement.setString(1, eventId);
        receiptStatement.setString(1, eventId);
        try (ResultSet projection = statement.executeQuery();
            ResultSet receipt = receiptStatement.executeQuery()) {
          assertThat(projection.next()).isTrue();
          assertThat(projection.getInt(1)).isEqualTo(1);
          assertThat(projection.getObject(2)).isNull();
          assertThat(receipt.next()).isTrue();
          assertThat(receipt.getInt(1)).isEqualTo(1);
          assertThat(receipt.getObject(2)).isNull();
          assertThat(receipt.getBytes(3)).containsExactly(payload);
        }
      }
    }
  }

  private static void migrateThrough(String target) {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas("logging_admin_service")
        .defaultSchema("logging_admin_service")
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion(target))
        .load()
        .migrate();
  }

  private static Connection connection() throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    connection.setSchema("logging_admin_service");
    return connection;
  }

  private static void insertVersionOneRows(
      Connection connection, String eventId, UUID receiptId, byte[] payload) throws SQLException {
    long logEventId;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO log_events "
                + "(tenant_id, scope, tenant_key, audit_event_id, type, message, timestamp) "
                + "VALUES (42, 'tenant', 42, ?, 'ACCOUNT_AUDIT', ?, ?) RETURNING id")) {
      statement.setString(1, eventId);
      statement.setString(2, "Account audit event " + eventId);
      statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.of(2026, 9, 24, 0, 0)));
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        logEventId = result.getLong(1);
      }
    }

    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO account_audit_receipts "
                + "(log_event_id, receipt_id, scope, tenant_id, tenant_key, audit_event_id, "
                + "producer_service, event_type, occurred_at_seconds, occurred_at_nanos, "
                + "schema_version, payload_digest_version, payload_digest, payload, status, outcome) "
                + "VALUES (?, ?, 'tenant', 42, 42, ?, 'account-service', 'ACCOUNT_REGISTERED', "
                + "1790208000, 123456789, 1, 1, ?, ?, 'COMMITTED', 'ACCEPTED')")) {
      statement.setLong(1, logEventId);
      statement.setObject(2, receiptId);
      statement.setString(3, eventId);
      statement.setString(4, "sha256:" + "a".repeat(64));
      statement.setBytes(5, payload);
      statement.executeUpdate();
    }
  }

  private static List<Object> readProjection(Connection connection, String eventId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id, tenant_id, scope, tenant_key, audit_event_id, type, message, timestamp, "
                + "account_id FROM log_events WHERE audit_event_id = ?")) {
      statement.setString(1, eventId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return readRow(result, 9);
      }
    }
  }

  private static List<Object> readReceipt(Connection connection, String eventId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id, log_event_id, receipt_id, scope, tenant_id, tenant_key, audit_event_id, "
                + "producer_service, event_type, occurred_at_seconds, occurred_at_nanos, "
                + "schema_version, payload_digest_version, payload_digest, payload, status, outcome, "
                + "created_at FROM account_audit_receipts WHERE audit_event_id = ?")) {
      statement.setString(1, eventId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        List<Object> fields = readRow(result, 18);
        fields.set(14, HexFormat.of().formatHex((byte[]) fields.get(14)));
        return fields;
      }
    }
  }

  private static List<Object> readRow(ResultSet result, int fieldCount) throws SQLException {
    List<Object> fields = new ArrayList<>(fieldCount);
    for (int column = 1; column <= fieldCount; column++) {
      fields.add(result.getObject(column));
    }
    return fields;
  }
}
