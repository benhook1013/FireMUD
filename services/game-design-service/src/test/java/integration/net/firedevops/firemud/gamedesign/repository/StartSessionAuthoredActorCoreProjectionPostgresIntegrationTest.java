package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.repository.StartSessionAuthoredActorCoreProjectionRepository;
import net.firedevops.firemud.gamedesign.repository.StartSessionLaunchDescriptorRepository.Binding;
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

/** PostgreSQL migration/storage proof; the synthetic row is not authenticated source authority. */
@Testcontainers(disabledWithoutDocker = true)
class StartSessionAuthoredActorCoreProjectionPostgresIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void migratesOwnerKeyReadsSyntheticRetainedStorageAndRejectsMutation() throws Exception {
    String schema = "gd_actor_core_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table(FLYWAY_TABLE)
        .placeholders(Map.of("serviceSchema", schema))
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

    UUID tenantId = UUID.randomUUID();
    UUID bindingId = UUID.randomUUID();
    String requestId = "synthetic-actor-core-owner";
    byte[] sourceBytes = new byte[] {1, 2, 3, 4};
    byte[] projectionBytes = "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_8);
    String sourceDigest = sha256(sourceBytes);
    String projectionDigest = sha256(projectionBytes);
    Binding owner = owner(tenantId, requestId);

    try (Connection connection = dataSource.getConnection()) {
      setSearchPath(connection, schema);
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        // Seed physical retained storage without claiming this synthetic row passed the owner or
        // source-authentication triggers; normal application connections never change this role.
        statement.execute("SET LOCAL session_replication_role = replica");
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO game_design_start_session_authored_actor_core_projection "
                  + "(target_namespace, canonical_tenant_id, control_plane_request_id, "
                  + "launch_descriptor_row_id, policy_order, binding_id, source_binding_bytes, "
                  + "source_binding_digest, projection_bytes, projection_digest) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        statement.setString(1, owner.targetNamespace());
        statement.setObject(2, tenantId);
        statement.setString(3, requestId);
        statement.setLong(4, owner.descriptorRowId());
        statement.setInt(5, 0);
        statement.setObject(6, bindingId);
        statement.setBytes(7, sourceBytes);
        statement.setString(8, sourceDigest);
        statement.setBytes(9, projectionBytes);
        statement.setString(10, projectionDigest);
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }

      DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
      assertThat(
              dsl.fetchValue(
                  "SELECT count(*) FROM pg_constraint "
                      + "WHERE conrelid = to_regclass("
                      + "'game_design_start_session_authored_actor_core_projection') "
                      + "AND contype = 'f'",
                  Integer.class))
          .isEqualTo(2);
      var retained = new StartSessionAuthoredActorCoreProjectionRepository(dsl).findByOwner(owner);
      assertThat(retained).hasSize(1);
      assertThat(retained.getFirst().targetNamespace()).isEqualTo(owner.targetNamespace());
      assertThat(retained.getFirst().canonicalTenantId()).isEqualTo(tenantId);
      assertThat(retained.getFirst().controlPlaneRequestId()).isEqualTo(requestId);
      assertThat(retained.getFirst().descriptorRowId()).isEqualTo(owner.descriptorRowId());
      assertThat(retained.getFirst().bindingId()).isEqualTo(bindingId);
      assertThat(retained.getFirst().sourceBindingBytes()).isEqualTo(sourceBytes);
      assertThat(retained.getFirst().sourceBindingDigest()).isEqualTo(sourceDigest);
      assertThat(retained.getFirst().projectionBytes()).isEqualTo(projectionBytes);
      assertThat(retained.getFirst().projectionDigest()).isEqualTo(projectionDigest);
      connection.commit();
    }

    assertThatThrownBy(
            () ->
                mutate(
                    dataSource,
                    schema,
                    "UPDATE game_design_start_session_authored_actor_core_projection SET policy_order = 1"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("insert-only");
    assertThatThrownBy(
            () ->
                mutate(
                    dataSource,
                    schema,
                    "DELETE FROM game_design_start_session_authored_actor_core_projection"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("insert-only");
    assertThatThrownBy(
            () ->
                mutate(
                    dataSource,
                    schema,
                    "TRUNCATE game_design_start_session_authored_actor_core_projection"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("retained");
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private static Binding owner(UUID tenantId, String requestId) {
    return new Binding(
        "world-runtime",
        tenantId,
        requestId,
        987654321L,
        123456789L,
        3L,
        new byte[] {1},
        UUID.randomUUID(),
        1L,
        7L,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "synthetic-workflow",
        digest('a'),
        digest('b'),
        UUID.randomUUID(),
        digest('c'),
        "synthetic-world",
        1L,
        "EMPTY_V1",
        "synthetic-game-logic-request",
        digest('d'),
        digest('e'),
        digest('f'),
        new byte[] {1},
        LocalDateTime.of(2026, 10, 11, 0, 0));
  }

  private static void setSearchPath(Connection connection, String schema) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + schema);
    }
  }

  private static void mutate(DriverManagerDataSource dataSource, String schema, String sql) {
    try (Connection connection = dataSource.getConnection()) {
      setSearchPath(connection, schema);
      DSL.using(connection, SQLDialect.POSTGRES).execute(sql);
    } catch (SQLException failure) {
      throw new IllegalStateException("Could not open PostgreSQL mutation connection", failure);
    }
  }

  private static String sha256(byte[] bytes) throws java.security.NoSuchAlgorithmException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    return "sha256:" + java.util.HexFormat.of().formatHex(digest);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
