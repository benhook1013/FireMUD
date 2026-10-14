package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the forward V1042.1 repair preserves the installed V1035 trigger and retained schema. The
 * protected fresh-execution guard is exercised as a denial. A temporary probe invokes the
 * production deferred function only to prove its qualified lookup and missing-terminal denial;
 * neither fixture claims Account authorization, StartSession admission, or successful settlement.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldCanonicalInstanceExecutionTerminalSchemaPostgresIntegrationTest {
  private static final String HISTORY = "flyway_schema_history_world_management_service";
  private static final String V1035_FILE =
      "V1035__world_start_session_execution_terminal_fence.sql";
  private static final String V1035_SHA256 =
      "a7931633a6e1c5ab03e12cc4026b543c8e64fa4c894f5d5f5fbe343c3c5a24be";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void forwardRepairPreservesTerminalTriggerAndWorksWithoutWorldInCallerSearchPath()
      throws Exception {
    String schema = "world_terminal_schema_" + UUID.randomUUID().toString().replace("-", "");
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
    String qualifiedExecutionTable = qualified(schema, "world_canonical_instance_execution");
    try {
      assertThat(
              sha256(
                  Files.readAllBytes(
                      root.resolve(
                          "services/world-management-service/src/main/resources/db/migration/"
                              + V1035_FILE))))
          .isEqualTo(V1035_SHA256);

      Flyway through1042 =
          flyway(source, schema, MigrationVersion.fromVersion("1042"), worldLocation, sagaLocation);
      through1042.migrate();
      through1042.validate();
      assertThat(latestVersion(dsl)).isEqualTo("1042");

      // The current table guard requires the exact Account owner observation. This direct fresh
      // PENDING attempt is expected to fail; it does not synthesize that observation or authorize
      // any production execution row.
      assertThat(executionRows(dsl, qualifiedExecutionTable)).isEmpty();
      String originalV1035History = migrationHistory(dsl, "1035");
      var originalSchema = schemaInventory(dsl, schema);
      var before = terminalTriggerMetadata(dsl, schema);
      assertThat(before.publicExecuteGrant()).isFalse();
      var denied = executionRow(UUID.randomUUID().toString());
      assertThatThrownBy(() -> insertPending(dsl, qualifiedExecutionTable, denied))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining(
              "A fresh World execution intent requires its exact Account owner observation")
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));
      assertThat(executionRows(dsl, qualifiedExecutionTable)).isEmpty();

      Flyway current = flyway(source, schema, null, worldLocation, sagaLocation);
      current.migrate();
      current.validate();
      assertThat(latestVersion(dsl)).isEqualTo("1042.1");
      assertThat(migrationHistory(dsl, "1035")).isEqualTo(originalV1035History);
      assertThat(executionRows(dsl, qualifiedExecutionTable)).isEmpty();
      assertThat(schemaInventory(dsl, schema)).isEqualTo(originalSchema);

      var after = terminalTriggerMetadata(dsl, schema);
      assertThat(after.functionOid()).isEqualTo(before.functionOid());
      assertThat(after.functionOwner()).isEqualTo(before.functionOwner());
      assertThat(after.functionAcl()).isEqualTo(before.functionAcl());
      assertThat(after.triggerOid()).isEqualTo(before.triggerOid());
      assertThat(after.triggerFunctionOid()).isEqualTo(before.triggerFunctionOid());
      assertThat(after.triggerDefinition()).isEqualTo(before.triggerDefinition());
      assertThat(after.triggerDeferrable()).isEqualTo(before.triggerDeferrable()).isTrue();
      assertThat(after.triggerInitiallyDeferred())
          .isEqualTo(before.triggerInitiallyDeferred())
          .isTrue();
      assertThat(after.triggerEnabled()).isEqualTo(before.triggerEnabled()).isEqualTo("O");
      assertThat(after.publicExecuteGrant()).isFalse();
      assertThat(after.securityDefiner()).isFalse();
      assertThat(after.functionConfig()).contains("search_path=pg_catalog");
      assertThat(after.functionSource())
          .contains("FROM \"" + schema + "\".world_canonical_instance_execution")
          .doesNotContain("FROM world_canonical_instance_execution");

      // This isolated TEMP trigger calls the production function with search_path pinned to
      // pg_catalog. No matching production row exists, so 23514 proves the function resolved its
      // service table and reached the expected missing-terminal denial rather than 42P01. It does
      // not prove production admission or a successful terminal settlement.
      assertThatThrownBy(() -> invokeProductionFunctionAgainstTempProbe(source, schema))
          .isInstanceOf(SQLException.class)
          .satisfies(
              failure -> {
                SQLException sql = (SQLException) failure;
                assertThat(sql.getSQLState()).isEqualTo("23514");
                assertThat(sql.getMessage())
                    .contains(
                        "World EXECUTING state cannot commit without exact terminal settlement");
              });
      assertThat(executionRows(dsl, qualifiedExecutionTable)).isEmpty();
    } finally {
      dsl.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
  }

  private static Flyway flyway(
      DriverManagerDataSource source, String schema, MigrationVersion target, String... locations) {
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .table(HISTORY)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(locations);
    if (target != null) configuration.target(target);
    return configuration.load();
  }

  private static ExecutionRow executionRow(String requestId) {
    String inputJson = "{\"testOnly\":\"denied-direct-insert\"}";
    return new ExecutionRow(
        "firemud",
        UUID.randomUUID(),
        requestId,
        UUID.randomUUID(),
        "sha256:" + sha256(inputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private static void insertPending(DSLContext dsl, String table, ExecutionRow row) {
    String originalTuple = "{\"testOnly\":\"denied-direct-insert\"}";
    String inputJson = "{\"testOnly\":\"denied-direct-insert\"}";
    dsl.execute(
        "INSERT INTO "
            + table
            + " (target_namespace,canonical_tenant_id,control_plane_request_id,"
            + "canonical_game_instance_id,canonical_world_slug,game_template_id,"
            + "original_post_authorization_tuple,original_post_authorization_tuple_sha256,"
            + "account_world_participation_id,account_world_participation_fence,"
            + "game_session_owner_attempt_id,game_session_owner_fence,preparation_input_digest,"
            + "preparation_input_json,world_execution_fence,operation_state) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')",
        row.namespace(),
        row.tenantId(),
        row.requestId(),
        row.gameInstanceId(),
        "test-denied-direct-insert",
        17L,
        originalTuple.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        sha256(originalTuple.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        row.accountParticipationId(),
        1L,
        row.gameSessionAttemptId(),
        1L,
        row.inputDigest(),
        inputJson,
        1L);
  }

  private static void invokeProductionFunctionAgainstTempProbe(
      DriverManagerDataSource source, String schema) throws SQLException {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (Statement statement = connection.createStatement()) {
          statement.execute("SET LOCAL search_path = pg_catalog");
          try (var result = statement.executeQuery("SELECT current_setting('search_path')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("pg_catalog");
          }
          statement.execute(
              "CREATE TEMP TABLE terminal_lookup_probe ("
                  + "target_namespace TEXT NOT NULL, canonical_tenant_id UUID NOT NULL, "
                  + "control_plane_request_id TEXT NOT NULL) ON COMMIT DROP");
        }
        DSL.using(connection, SQLDialect.POSTGRES)
            .execute(
                "CREATE CONSTRAINT TRIGGER terminal_lookup_probe_deferred "
                    + "AFTER INSERT OR UPDATE ON pg_temp.terminal_lookup_probe "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION {0}()",
                DSL.name(schema, "world_require_canonical_instance_execution_terminal"));
        try (PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO pg_temp.terminal_lookup_probe VALUES (?, ?, ?)")) {
          insert.setString(1, "firemud");
          insert.setObject(2, UUID.randomUUID());
          insert.setString(3, "missing-production-execution-row");
          insert.executeUpdate();
        }
        connection.commit();
      } catch (SQLException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }
  }

  private static String latestVersion(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT version FROM "
                + HISTORY
                + " WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
        .get(0, String.class);
  }

  private static String migrationHistory(DSLContext dsl, String version) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(h)::text FROM " + HISTORY + " h WHERE version=? AND success", version)
        .get(0, String.class);
  }

  private static List<String> executionRows(DSLContext dsl, String table) {
    return dsl.fetch(
            "SELECT xmin::text || ':' || to_jsonb(e)::text FROM "
                + table
                + " e ORDER BY target_namespace,canonical_tenant_id,control_plane_request_id")
        .getValues(0, String.class);
  }

  private static List<String> schemaInventory(DSLContext dsl, String schema) {
    return dsl.fetch(
            "SELECT 'table:' || c.relname || ':' || c.relkind::text FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? "
                + "AND c.relkind IN ('r','p') AND c.relname<>? "
                + "UNION ALL SELECT 'column:' || c.relname || ':' || a.attnum || ':' "
                + "|| a.attname || ':' || format_type(a.atttypid,a.atttypmod) || ':' "
                + "|| a.attnotnull::text || ':' || COALESCE(pg_get_expr(d.adbin,d.adrelid),'') "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d "
                + "ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE n.nspname=? "
                + "AND c.relkind IN ('r','p') AND c.relname<>? AND a.attnum>0 "
                + "AND NOT a.attisdropped ORDER BY 1",
            schema,
            HISTORY,
            schema,
            HISTORY)
        .getValues(0, String.class);
  }

  private static TriggerMetadata terminalTriggerMetadata(DSLContext dsl, String schema) {
    var row =
        dsl.fetchSingle(
            "SELECT p.oid::bigint AS function_oid,p.proowner::regrole::text AS function_owner,"
                + "p.proacl::text AS function_acl,p.prosecdef AS security_definer,"
                + "p.proconfig::text AS function_config,p.prosrc AS function_source,"
                + "t.oid::bigint AS trigger_oid,t.tgfoid::bigint AS trigger_function_oid,"
                + "t.tgdeferrable AS trigger_deferrable,t.tginitdeferred AS trigger_initially_deferred,"
                + "t.tgenabled::text AS trigger_enabled,pg_get_triggerdef(t.oid) AS trigger_definition,"
                + "(SELECT count(*) FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a "
                + "WHERE a.grantee=0 AND a.privilege_type='EXECUTE') AS public_execute_grants "
                + "FROM pg_proc p JOIN pg_namespace pn ON pn.oid=p.pronamespace "
                + "JOIN pg_trigger t ON t.tgfoid=p.oid JOIN pg_class c ON c.oid=t.tgrelid "
                + "JOIN pg_namespace tn ON tn.oid=c.relnamespace "
                + "WHERE pn.nspname=? AND tn.nspname=? "
                + "AND p.proname='world_require_canonical_instance_execution_terminal' "
                + "AND t.tgname='trg_world_canonical_instance_execution_no_inflight_commit'",
            schema,
            schema);
    return new TriggerMetadata(
        row.get("function_oid", Long.class),
        row.get("function_owner", String.class),
        row.get("function_acl", String.class),
        row.get("security_definer", Boolean.class),
        row.get("function_config", String.class),
        row.get("function_source", String.class),
        row.get("trigger_oid", Long.class),
        row.get("trigger_function_oid", Long.class),
        row.get("trigger_deferrable", Boolean.class),
        row.get("trigger_initially_deferred", Boolean.class),
        row.get("trigger_enabled", String.class),
        row.get("trigger_definition", String.class),
        row.get("public_execute_grants", Long.class) > 0);
  }

  private static String sqlState(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause())
      if (current instanceof SQLException sqlException) return sqlException.getSQLState();
    return null;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
    }
  }

  private static String qualified(String schema, String object) {
    return "\"" + schema + "\".\"" + object + "\"";
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.exists(candidate.resolve("settings.gradle.kts"))) return candidate;
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Unable to locate repository filesystem migrations");
  }

  private record ExecutionRow(
      String namespace,
      UUID tenantId,
      String requestId,
      UUID gameInstanceId,
      String inputDigest,
      UUID accountParticipationId,
      UUID gameSessionAttemptId) {}

  private record TriggerMetadata(
      long functionOid,
      String functionOwner,
      String functionAcl,
      boolean securityDefiner,
      String functionConfig,
      String functionSource,
      long triggerOid,
      long triggerFunctionOid,
      boolean triggerDeferrable,
      boolean triggerInitiallyDeferred,
      String triggerEnabled,
      String triggerDefinition,
      boolean publicExecuteGrant) {}
}
