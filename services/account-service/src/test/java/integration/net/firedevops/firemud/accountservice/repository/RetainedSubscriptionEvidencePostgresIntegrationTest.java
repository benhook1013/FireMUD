package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Populated, combined-history forward upgrade; no generated or synthetic canonical subscription.
 */
@Testcontainers(disabledWithoutDocker = true)
class RetainedSubscriptionEvidencePostgresIntegrationTest {
  private static final String EVIDENCE = "account_retained_subscription_evidence";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void forwardUpgradePreservesEveryValueHeapIdentityXminAndPublishedFlywayHistory() {
    Context context = predecessor();
    DSLContext dsl = context.dsl();
    List<Map<String, Object>> before = rows(dsl, "subscription");
    List<Map<String, Object>> history = history(dsl);
    Long originalOid = relationOid(dsl, "subscription");
    List<Map<String, Object>> accounts =
        dsl.fetch(
                "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM accounts a ORDER BY id")
            .intoMaps();

    configuration(context).load().migrate();

    assertThat(rows(dsl, EVIDENCE)).isEqualTo(before);
    assertThat(relationOid(dsl, EVIDENCE)).isEqualTo(originalOid);
    var afterHistory = history(dsl);
    assertThat(afterHistory).hasSize(history.size() + 4);
    assertThat(afterHistory.subList(history.size(), afterHistory.size()))
        .extracting(row -> row.get("version"))
        .containsExactly("1027", "1027.1", "1027.2", "1028");
    assertThat(afterHistory.subList(0, history.size())).containsExactlyElementsOf(history);
    assertThat(history(dsl).getLast().get("version")).isEqualTo("1028");
    assertThat(
            dsl.fetch(
                "SELECT version FROM flyway_schema_history WHERE version IN ('1000', '1001', '1002')"))
        .hasSize(3);
    assertThat(
            dsl.fetch(
                    "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM accounts a ORDER BY id")
                .intoMaps())
        .isEqualTo(accounts);
    assertThat(scalar(dsl, "SELECT to_regclass('subscription')", String.class)).isNull();
    assertThat(
            scalar(
                dsl,
                "SELECT to_regprocedure('advance_subscription_authority_generation()')",
                String.class))
        .isNull();
    assertThat(
            dsl.fetch(
                    "SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass ORDER BY conname",
                    EVIDENCE)
                .getValues(0, String.class))
        .containsExactly(
            "account_retained_subscription_evidence_account_fk",
            "account_retained_subscription_evidence_generation_non_nil",
            "account_retained_subscription_evidence_pkey",
            "account_retained_subscription_evidence_version_positive",
            "subscription_account_id_not_null",
            "subscription_entitlement_version_not_null",
            "subscription_id_not_null",
            "subscription_plan_id_not_null",
            "subscription_status_not_null",
            "subscription_tenant_authority_generation_not_null",
            "subscription_tenant_id_not_null");
    assertThat(
            dsl.fetch(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = ?",
                    EVIDENCE)
                .getValues(0, String.class))
        .containsExactlyInAnyOrder(
            "account_retained_subscription_evidence_pkey",
            "idx_account_retained_subscription_evidence_account",
            "idx_account_retained_subscription_evidence_tenant");
    assertThat(
            scalar(
                dsl,
                "SELECT to_regclass('account_retained_subscription_evidence_id_seq')",
                String.class))
        .isNotNull();
    configuration(context).load().validate();
  }

  @Test
  void archiveRefusesAllMutationIncludingNoOpEmptyStatementsAndTruncateCascade() {
    Context context = predecessor();
    configuration(context).load().migrate();
    DSLContext dsl = context.dsl();
    var before = rows(dsl, EVIDENCE);
    for (String sql :
        List.of(
            "INSERT INTO " + EVIDENCE + " SELECT * FROM " + EVIDENCE + " WHERE false",
            "INSERT INTO "
                + EVIDENCE
                + " (id, account_id, plan_id, status, tenant_id) VALUES (9999, "
                + context.accountId()
                + ", 'forbidden', 'active', 7)",
            "UPDATE " + EVIDENCE + " SET status = status",
            "UPDATE " + EVIDENCE + " SET tenant_id = 99 WHERE false",
            "DELETE FROM " + EVIDENCE + " WHERE false",
            "DELETE FROM " + EVIDENCE,
            "TRUNCATE " + EVIDENCE,
            "TRUNCATE " + EVIDENCE + " RESTART IDENTITY CASCADE")) {
      assertThatThrownBy(() -> dsl.execute(sql))
          .hasMessageContaining("Retained subscription evidence is immutable and non-authorizing");
      assertThat(rows(dsl, EVIDENCE)).isEqualTo(before);
    }
    // Account FK remains restrictive even for terminal-looking historical rows.
    assertThatThrownBy(() -> dsl.execute("DELETE FROM accounts WHERE id = ?", context.accountId()))
        .hasMessageContaining("account_retained_subscription_evidence_account_fk");
    // Other retained Account families can refuse first; no cascade may erase this evidence.
    assertThatThrownBy(() -> dsl.execute("TRUNCATE accounts CASCADE"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(rows(dsl, EVIDENCE)).isEqualTo(before);
  }

  @Test
  void historicalRepositoryReadsOnlyTheRequestedAccountWithoutChoosingATenantWinner() {
    Context context = predecessor();
    configuration(context).load().migrate();
    var repository = new RetainedSubscriptionEvidenceRepository(context.dsl());
    var history = repository.findHistoryByAccountId(context.accountId());
    assertThat(history).hasSize(4);
    assertThat(history)
        .extracting(row -> row.status())
        .containsExactly("active", "canceled", "grace", "provider_unknown");
    assertThat(history).extracting(row -> row.accountId()).containsOnly(context.accountId());
    assertThat(history).extracting(row -> row.tenantId()).containsExactly(7L, 7L, 8L, 8L);
    assertThat(repository.findHistoryByAccountId(-1L)).isEmpty();
    assertThat(rows(context.dsl(), EVIDENCE)).hasSize(6);
  }

  private static List<Map<String, Object>> rows(DSLContext dsl, String relation) {
    return dsl.fetch(
            "SELECT row_to_json(s)::text AS value, xmin::text AS xmin, ctid::text AS ctid FROM "
                + relation
                + " s ORDER BY id")
        .intoMaps();
  }

  private static List<Map<String, Object>> history(DSLContext dsl) {
    return dsl.fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
  }

  private static <T> T scalar(DSLContext dsl, String sql, Class<T> type, Object... bindings) {
    return Objects.requireNonNull(dsl.fetchOne(sql, bindings)).get(0, type);
  }

  private static Long relationOid(DSLContext dsl, String relation) {
    return scalar(dsl, "SELECT ?::regclass::oid::bigint", Long.class, relation);
  }

  private static FluentConfiguration configuration(Context context) {
    return Flyway.configure()
        .dataSource(context.source())
        .target("1028")
        .schemas(context.schema())
        .defaultSchema(context.schema())
        .placeholders(Map.of("serviceSchema", context.schema()))
        .locations("classpath:db/migration", "classpath:db/migration/saga");
  }

  private static Context predecessor() {
    String schema = "subscription_retention_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    DSLContext dsl = DSL.using(source, SQLDialect.POSTGRES);
    var empty = new Context(source, schema, dsl, 0L);
    configuration(empty).target("1026").load().migrate();
    Long accountId =
        Objects.requireNonNull(
            scalar(
                dsl,
                "INSERT INTO accounts(username,email,password_hash) VALUES ('retained','retained@example.test','hash') RETURNING id",
                Long.class));
    Long otherAccount =
        Objects.requireNonNull(
            scalar(
                dsl,
                "INSERT INTO accounts(username,email,password_hash) VALUES ('other','other@example.test','hash') RETURNING id",
                Long.class));
    dsl.execute(
        "INSERT INTO subscription(id,account_id,plan_id,status,started_at,ended_at,tenant_id,entitlement_version,tenant_authority_generation) VALUES "
            + "(91,?,'unqualified-active','active','2025-01-02 03:04:05.123456',NULL,7,19,?::uuid),"
            + "(92,?,'retained-canceled','canceled',NULL,'2025-02-03 04:05:06.654321',7,21,?::uuid),"
            + "(93,?,'grace','grace',NULL,NULL,8,1,?::uuid),"
            + "(94,?,'unknown','provider_unknown',NULL,NULL,8,2,?::uuid),"
            + "(95,?,'other-account','active',NULL,NULL,7,5,?::uuid)",
        accountId,
        UUID.randomUUID(),
        accountId,
        UUID.randomUUID(),
        accountId,
        UUID.randomUUID(),
        accountId,
        UUID.randomUUID(),
        otherAccount,
        UUID.randomUUID());
    // Preserve V5's numeric default as historical evidence, not canonical tenant provenance.
    dsl.execute(
        "INSERT INTO subscription(id,account_id,plan_id,status) VALUES (96,?,'default-tenant','active')",
        otherAccount);
    return new Context(source, schema, dsl, accountId);
  }

  private record Context(
      DriverManagerDataSource source, String schema, DSLContext dsl, Long accountId) {}
}
