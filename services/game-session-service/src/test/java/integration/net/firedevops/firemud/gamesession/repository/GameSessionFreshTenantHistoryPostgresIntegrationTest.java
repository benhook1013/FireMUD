package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import net.firedevops.firemud.gamesession.client.GameDesignFreshTenantCreationClient;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Original V25 storage is historical evidence, never a complete Game Design creation receipt. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionFreshTenantHistoryPostgresIntegrationTest {
  private static final String NAMESPACE = "gs-tenant-history";
  private static final List<String> TABLES =
      List.of(
          "game_session_tenant_scope_reservation",
          "game_session_tenant_canonical_claim",
          "game_session_fresh_tenant_association");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void originalV25AssociationRetainsItsPhysicalRowsAndCannotGainMissingCreationAuthority() {
    String schema = "gs_tenant_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migrate(source, schema, "25");
    var sql = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    UUID requestId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    long privateTenantId =
        sql.fetchSingle(
                "INSERT INTO game_session_tenant_scope_reservation (reservation_kind) "
                    + "VALUES ('FRESH_SOURCE_BOUND') RETURNING game_session_tenant_id")
            .get("game_session_tenant_id", Long.class);
    sql.execute(
        "INSERT INTO game_session_tenant_canonical_claim "
            + "(target_namespace, canonical_tenant_id, legacy_game_session_tenant_id, "
            + "association_kind, reservation_kind, association_operation_id, association_request_id) "
            + "VALUES (?, ?, ?, 'FRESH_SOURCE_BOUND', 'FRESH_SOURCE_BOUND', ?, ?)",
        NAMESPACE,
        tenantId,
        privateTenantId,
        operationId,
        requestId);
    sql.execute(
        "INSERT INTO game_session_fresh_tenant_association "
            + "(association_operation_id, target_namespace, association_request_id, "
            + "source_schema_version, source_target_namespace, source_request_id, "
            + "source_canonical_tenant_id, canonical_tenant_id, legacy_game_session_tenant_id, "
            + "source_game_row_id, source_game_tenant_key, provenance_kind, association_kind) "
            + "VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, 9, 'legacy-source-key', "
            + "'NEW_GAME_ROW', 'FRESH_SOURCE_BOUND')",
        operationId,
        NAMESPACE,
        requestId,
        NAMESPACE,
        requestId,
        tenantId,
        tenantId,
        privateTenantId);

    Map<String, List<String>> originalColumns = new LinkedHashMap<>();
    for (String table : TABLES) {
      originalColumns.put(
          table,
          sql.fetch(
                  "SELECT column_name FROM information_schema.columns "
                      + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                  schema,
                  table)
              .getValues("column_name", String.class));
    }
    Map<String, List<Map<String, Object>>> before = snapshot(sql, originalColumns);
    assertThat(before.values()).allSatisfy(rows -> assertThat(rows).hasSize(1));
    int lastOriginalRank =
        Objects.requireNonNull(
            sql.fetchSingle("SELECT max(installed_rank) AS rank FROM flyway_schema_history")
                .get("rank", Integer.class));
    var originalHistory = history(sql, lastOriginalRank);
    assertThat(
            sql.fetchSingle(
                    "SELECT count(*) AS rows FROM flyway_schema_history "
                        + "WHERE version = '25' AND success")
                .get("rows", Long.class))
        .isEqualTo(1L);
    assertThat(originalColumns.get("game_session_fresh_tenant_association"))
        .doesNotContain("complete_source_evidence");

    for (String target : List.of("40", "1003", "1004")) {
      migrate(source, schema, target);
      assertThat(snapshot(sql, originalColumns))
          .as("original rows after V%s", target)
          .isEqualTo(before);
      assertThat(history(sql, lastOriginalRank)).isEqualTo(originalHistory);
    }
    migrate(source, schema, null);
    assertThat(snapshot(sql, originalColumns)).isEqualTo(before);
    assertThat(history(sql, lastOriginalRank)).isEqualTo(originalHistory);
    assertThat(
            sql.fetchSingle(
                    "SELECT complete_source_evidence FROM "
                        + "game_session_fresh_tenant_association WHERE canonical_tenant_id = ?",
                    tenantId)
                .get("complete_source_evidence"))
        .isNull();

    GameDesignFreshTenantCreationClient client = mock(GameDesignFreshTenantCreationClient.class);
    var repository =
        new GameSessionFreshTenantAssociationRepository(
            sql, new DataSourceTransactionManager(source), client, NAMESPACE);
    assertThatThrownBy(() -> repository.readByCanonicalTenantId(tenantId))
        .isInstanceOf(
            GameSessionFreshTenantAssociationRepository.InvalidFreshTenantAssociationException
                .class)
        .hasMessageContaining("complete_source_evidence");
    verifyNoInteractions(client);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "UPDATE game_session_fresh_tenant_association SET complete_source_evidence = '{}'::jsonb "
                        + "WHERE canonical_tenant_id = ?",
                    tenantId))
        .isInstanceOf(DataAccessException.class);
    assertThat(snapshot(sql, originalColumns)).isEqualTo(before);
    assertThat(history(sql, lastOriginalRank)).isEqualTo(originalHistory);
    assertThat(
            sql.fetchSingle(
                    "SELECT complete_source_evidence FROM "
                        + "game_session_fresh_tenant_association WHERE canonical_tenant_id = ?",
                    tenantId)
                .get("complete_source_evidence"))
        .isNull();
    assertThat(
            sql.fetchSingle("SELECT count(*) AS rows FROM game_instances").get("rows", Long.class))
        .isZero();
  }

  private static void migrate(DriverManagerDataSource source, String schema, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath());
    if (target != null) configuration.target(target);
    configuration.load().migrate();
  }

  private static Map<String, List<Map<String, Object>>> snapshot(
      DSLContext sql, Map<String, List<String>> columns) {
    Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
    columns.forEach(
        (table, names) -> {
          String projection =
              names.stream()
                  .map(GameSessionFreshTenantHistoryPostgresIntegrationTest::quote)
                  .collect(Collectors.joining(", "));
          rows.put(
              table,
              sql.fetch(
                      "SELECT jsonb_build_array("
                          + projection
                          + ")::text AS original_bytes, xmin::text AS physical_version FROM "
                          + quote(table)
                          + " ORDER BY original_bytes")
                  .intoMaps());
        });
    return rows;
  }

  private static List<Map<String, Object>> history(DSLContext sql, int lastOriginalRank) {
    return sql.fetch(
            "SELECT row_to_json(history)::text AS original_bytes, "
                + "xmin::text AS physical_version FROM flyway_schema_history history "
                + "WHERE installed_rank <= ? ORDER BY installed_rank",
            lastOriginalRank)
        .intoMaps();
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
