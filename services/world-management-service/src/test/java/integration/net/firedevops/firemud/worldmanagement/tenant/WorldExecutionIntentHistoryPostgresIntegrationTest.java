package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.HISTORY;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Original SQL-ledger retention only; controlled JSON is not Account-issued execution evidence. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldExecutionIntentHistoryPostgresIntegrationTest {
  private static final String NAMESPACE = "world-history";
  private static final List<String> WITNESS_COLUMNS =
      List.of(
          "game_session_owner_mutation_id",
          "original_attempt_read_request_id",
          "original_game_session_lease_expires_at",
          "original_attempt_evidence_bytes",
          "original_attempt_evidence_sha256");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void originalPendingAndAbortedIntentsRetainBytesWithoutAcquiringAnAccountWitness() {
    String schema = "world_execution_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migration(source, schema, "1035").migrate();
    var sql = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    assertThat(sql.fetchValue("SHOW session_replication_role", String.class)).isEqualTo("origin");
    Seed pending = seed();
    Seed abortedAfterIntent = seed();
    Seed bornAborted = seed();
    assertThat(persist(sql, transaction, pending)).isPositive();
    assertThat(persist(sql, transaction, abortedAfterIntent)).isPositive();
    abort(sql, transaction, abortedAfterIntent);
    abort(sql, transaction, bornAborted);
    List<Seed> seeds = List.of(pending, abortedAfterIntent, bornAborted);
    var before = snapshots(sql, seeds);
    for (Seed seed : seeds) {
      assertThat(
              sql.fetchSingle(
                      "SELECT operation_state FROM world_canonical_instance_execution WHERE canonical_game_instance_id = ?",
                      seed.instance())
                  .get(0, String.class))
          .isEqualTo(seed.equals(pending) ? "PENDING" : "ABORTED");
      assertThat(
              sql.fetchSingle(
                      "SELECT jsonb_exists_any(to_jsonb(e), ?::text[]) FROM world_canonical_instance_execution e WHERE canonical_game_instance_id = ?",
                      WITNESS_COLUMNS.toArray(String[]::new),
                      seed.instance())
                  .get(0, Boolean.class))
          .isFalse();
    }
    var beforeHistory = history(sql);
    migration(source, schema, "1036").migrate();
    assertOriginalRows(sql, before);
    assertNoWitness(sql);
    assertThat(history(sql)).containsAll(beforeHistory);
    var after1036 = snapshots(sql, seeds);
    var after1036History = history(sql);

    var current = migration(source, schema, null);
    current.migrate();
    current.validate();
    assertThat(snapshots(sql, seeds)).isEqualTo(after1036);
    assertThat(history(sql)).containsAll(after1036History);
    assertNoWitness(sql);

    // Existing intents can be observed/retried without becoming fresh authority or being restarted.
    assertThat(persist(sql, transaction, pending)).isZero();
    abort(sql, transaction, abortedAfterIntent);
    abort(sql, transaction, bornAborted);
    assertThat(snapshots(sql, seeds)).isEqualTo(after1036);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        sql.fetchSingle(
                            "SELECT world_claim_canonical_instance_execution(?,?,?,?,?,?,?,?)",
                            pending.inputJson(),
                            digest(pending.inputJson()),
                            pending.tupleJson().getBytes(StandardCharsets.UTF_8),
                            pending.participation(),
                            7L,
                            pending.attempt(),
                            9007199254740993L,
                            pending.instance())))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("exact complete owner observation and serialization fence");
    assertThatThrownBy(
            () ->
                sql.execute(
                    "UPDATE world_canonical_instance_execution SET game_session_owner_mutation_id = ? WHERE canonical_game_instance_id = ?",
                    UUID.randomUUID(),
                    pending.instance()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("World execution identity and Account owner witness are immutable");
    Seed freshWithoutWitness = seed();
    assertThatThrownBy(() -> persist(sql, transaction, freshWithoutWitness))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining(
            "A fresh World execution intent requires its exact Account owner observation");
    assertThat(snapshots(sql, seeds)).isEqualTo(after1036);
    assertNoWitness(sql);
    assertThat(history(sql)).containsAll(after1036History);
    for (String table :
        List.of(
            "world_instance",
            "world_canonical_instance_association",
            "world_canonical_instance_preparation")) {
      assertThat(sql.fetchCount(DSL.table(DSL.name(table)))).as(table).isZero();
    }
  }

  private static Seed seed() {
    UUID tenant = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    String request = "historical-execution-" + UUID.randomUUID();
    var mapper = new ObjectMapper();
    var identity =
        Map.of(
            "targetNamespace",
            NAMESPACE,
            "canonicalTenantId",
            tenant.toString(),
            "canonicalGameInstanceId",
            instance.toString(),
            "worldSlug",
            "retained-world",
            "controlPlaneRequestId",
            request);
    var evidence = new LinkedHashMap<String, Object>(identity);
    evidence.put("descriptorJson", "{\"gameTemplateId\":71}");
    String input =
        mapper.writeValueAsString(
            Map.of(
                "identity",
                identity,
                "gameSessionReadEvidence",
                evidence,
                "gameSessionReadRequest",
                Map.of("canonicalGameInstanceId", instance.toString())));
    // These minimal predecessor SQL inputs satisfy V1035's installed validator only. They do not
    // claim the complete canonical Account carrier, authentication, reservation or issuer proof.
    String tuple =
        mapper.writeValueAsString(
            Map.of(
                "tupleSchemaId",
                "postAuthorizationExecutionTuple",
                "tupleSchemaVersion",
                "1",
                "issuanceKind",
                "human_operator",
                "controlPlaneRequestId",
                request,
                "preAuthorizationReservationTuple",
                Map.of(
                    "actionFamily",
                    "StartSession",
                    "actionFamilyRequestIdentity",
                    Map.of("requestId", request)),
                "scope",
                Map.of("targetNamespace", NAMESPACE, "tenantId", tenant.toString()),
                "target",
                Map.of("gameTemplateId", 71),
                "targetOwner",
                Map.of("ownerService", "game-session-service"),
                "authorityEvidenceBundle",
                Map.of("accountProjectionEvidence", Map.of("expiresAt", "2099-01-01T00:00:00Z"))));
    return new Seed(tenant, instance, request, UUID.randomUUID(), UUID.randomUUID(), input, tuple);
  }

  private static long persist(DSLContext sql, TransactionTemplate transaction, Seed seed) {
    return Objects.requireNonNull(
        transaction.execute(
            status ->
                sql.fetchSingle(
                        "SELECT world_persist_canonical_instance_execution_intent(?,?,?,?,?,?,?,?)",
                        seed.inputJson(),
                        digest(seed.inputJson()),
                        seed.tupleJson().getBytes(StandardCharsets.UTF_8),
                        seed.participation(),
                        7L,
                        seed.attempt(),
                        9007199254740993L,
                        seed.instance())
                    .get(0, Long.class)));
  }

  private static void abort(DSLContext sql, TransactionTemplate transaction, Seed seed) {
    var result =
        Objects.requireNonNull(
            transaction.execute(
                status ->
                    sql.fetchSingle(
                        "SELECT * FROM world_abort_canonical_instance_execution(?,?,?,?,?,?,?,?,?,?,?)",
                        NAMESPACE,
                        seed.tenant(),
                        seed.request(),
                        seed.instance(),
                        seed.inputJson(),
                        digest(seed.inputJson()),
                        seed.tupleJson().getBytes(StandardCharsets.UTF_8),
                        seed.participation(),
                        7L,
                        seed.attempt(),
                        9007199254740993L)));
    assertThat(result.get("operation_state", String.class)).isEqualTo("ABORTED");
    assertThat(result.get("world_execution_fence", Long.class)).isPositive();
    assertThat(result.get("world_instance_id", Long.class)).isNull();
  }

  private static Map<UUID, Snapshot> snapshots(DSLContext sql, List<Seed> seeds) {
    var result = new LinkedHashMap<UUID, Snapshot>();
    for (Seed seed : seeds) {
      var row =
          sql.fetchSingle(
              "SELECT to_jsonb(e)::text AS row_json, xmin::text AS xmin FROM world_canonical_instance_execution e WHERE canonical_game_instance_id = ?",
              seed.instance());
      result.put(
          seed.instance(),
          new Snapshot(row.get("row_json", String.class), row.get("xmin", String.class)));
    }
    return result;
  }

  private static void assertOriginalRows(DSLContext sql, Map<UUID, Snapshot> before) {
    before.forEach(
        (id, snapshot) -> {
          var row =
              sql.fetchSingle(
                  "SELECT (to_jsonb(e) - ?::text[]) = ?::jsonb AS original, xmin::text AS xmin FROM world_canonical_instance_execution e WHERE canonical_game_instance_id = ?",
                  WITNESS_COLUMNS.toArray(String[]::new),
                  snapshot.json(),
                  id);
          assertThat(row.get("original", Boolean.class)).isTrue();
          assertThat(row.get("xmin", String.class)).isEqualTo(snapshot.xmin());
        });
  }

  private static void assertNoWitness(DSLContext sql) {
    assertThat(sql.fetchCount(DSL.table(DSL.name("world_canonical_instance_execution"))))
        .isEqualTo(3);
    for (Map<String, Object> row :
        sql.fetch("SELECT * FROM world_canonical_instance_execution").intoMaps()) {
      for (String column : WITNESS_COLUMNS) assertThat(row).containsEntry(column, null);
    }
  }

  private static List<Map<String, Object>> history(DSLContext sql) {
    return sql.fetch("SELECT * FROM " + HISTORY + " ORDER BY installed_rank").intoMaps();
  }

  private static String digest(String value) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private record Seed(
      UUID tenant,
      UUID instance,
      String request,
      UUID participation,
      UUID attempt,
      String inputJson,
      String tupleJson) {}

  private record Snapshot(String json, String xmin) {}
}
