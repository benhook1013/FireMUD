package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Current-default World SQL refusal proof, not a successful preparation or Account-authority proof.
 * The World owner guard rejects a fresh execution row without the complete original-attempt witness
 * fields; controlled SQL values do not claim that Account issued or authenticated a witness, or
 * establish a genuine creator or production StartSession operation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldCanonicalInstancePreparationPostgresIntegrationTest {
  private static final String HISTORY = "flyway_schema_history_world_management_service";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void currentDefaultOwnerGuardRefusesFreshExecutionWithoutExactAccountObservation()
      throws Exception {
    String schema = "world_preparation_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    Path root = repositoryRoot();
    String worldLocation =
        "filesystem:"
            + root.resolve("services/world-management-service/src/main/resources/db/migration");
    String sagaLocation =
        "filesystem:" + root.resolve("services/common-saga/src/main/resources/db/migration/saga");
    try {
      // Do not pin an older schema frontier: this proof applies the repository's complete current
      // World and Saga migration history, including the preserved V1035-V1039 owner guards and
      // V1042.x service-schema repairs.
      Flyway current =
          Flyway.configure()
              .dataSource(source)
              .schemas(schema)
              .defaultSchema(schema)
              .table(HISTORY)
              .placeholders(Map.of("serviceSchema", schema))
              .locations(worldLocation, sagaLocation)
              .load();
      current.migrate();
      current.validate();
      assertThat(migrationSucceeded(dsl, "1036")).isTrue();
      assertThat(migrationSucceeded(dsl, "1039")).isTrue();
      assertThat(migrationSucceeded(dsl, "1042.3")).isTrue();

      long instancesBefore = rowCount(dsl, "world_instance");
      long preparationsBefore = rowCount(dsl, "world_canonical_instance_preparation");
      long associationsBefore = rowCount(dsl, "world_canonical_instance_association");
      long executionsBefore = rowCount(dsl, "world_canonical_instance_execution");
      UUID tenant = UUID.randomUUID();
      UUID gameInstance = UUID.randomUUID();
      UUID participation = UUID.randomUUID();
      UUID attempt = UUID.randomUUID();
      String requestId = "missing-account-observation-" + UUID.randomUUID();
      byte[] tuple =
          "{\"controlledFixture\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      String inputJson = "{\"schemaVersion\":4,\"controlledFixture\":true}";
      String inputDigest =
          "sha256:" + sha256(inputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

      // All otherwise-required owner identity fields are concrete, but the original owner
      // mutation/read-request/lease/evidence fields remain absent. The installed World trigger
      // must reject the incomplete fresh PENDING row before any canonical instance or association
      // can be written. This direct SQL probe does not establish who could authenticate such a
      // witness through Account's separate mTLS and currentness path.
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "INSERT INTO world_canonical_instance_execution ("
                          + "target_namespace,canonical_tenant_id,control_plane_request_id,"
                          + "canonical_game_instance_id,canonical_world_slug,game_template_id,"
                          + "original_post_authorization_tuple,original_post_authorization_tuple_sha256,"
                          + "account_world_participation_id,account_world_participation_fence,"
                          + "game_session_owner_attempt_id,game_session_owner_fence,"
                          + "preparation_input_digest,preparation_input_json,world_execution_fence,operation_state) "
                          + "VALUES (?, ?, ?, ?, 'controlled-proof', 1, ?, ?, ?, 1, ?, 1, ?, ?, "
                          + "nextval('world_canonical_instance_execution_fence_seq'), 'PENDING')",
                      "world-runtime",
                      tenant,
                      requestId,
                      gameInstance,
                      tuple,
                      sha256(tuple),
                      participation,
                      attempt,
                      inputDigest,
                      inputJson))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining(
              "A fresh World execution intent requires its exact Account owner observation")
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));

      assertThat(rowCount(dsl, "world_instance")).isEqualTo(instancesBefore);
      assertThat(rowCount(dsl, "world_canonical_instance_preparation"))
          .isEqualTo(preparationsBefore);
      assertThat(rowCount(dsl, "world_canonical_instance_association"))
          .isEqualTo(associationsBefore);
      assertThat(rowCount(dsl, "world_canonical_instance_execution")).isEqualTo(executionsBefore);
    } finally {
      dsl.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
  }

  private static boolean migrationSucceeded(DSLContext dsl, String version) {
    return Boolean.TRUE.equals(
        dsl.fetchSingle(
                "SELECT EXISTS(SELECT 1 FROM " + HISTORY + " WHERE version=? AND success)", version)
            .get(0, Boolean.class));
  }

  private static long rowCount(DSLContext dsl, String table) {
    return dsl.fetchSingle("SELECT count(*) FROM " + table).get(0, Long.class);
  }

  private static String sqlState(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException) return sqlException.getSQLState();
    }
    return null;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
    }
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("settings.gradle.kts"))) return candidate;
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Unable to locate repository filesystem migrations");
  }
}
