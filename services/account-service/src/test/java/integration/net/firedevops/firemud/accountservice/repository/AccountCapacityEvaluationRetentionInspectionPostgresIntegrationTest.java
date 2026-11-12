package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionInspectionOwner;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionInspectionOwner.Blocker;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disposable PostgreSQL proof of bounded read-only inspection and unchanged immutable guards. This
 * suite establishes no policy approval or deletion authority.
 */
class AccountCapacityEvaluationRetentionInspectionPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void freshAndPopulatedForwardMigrationPreserveRowsAndGuardsWhileBoundingObservations() {
    verifyForwardMigration(false);
    verifyForwardMigration(true);
  }

  private static void verifyForwardMigration(boolean populated) {
    String schema = "capacity_retention_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      configure(data, schema).target("1043").load().migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transactions = new DataSourceTransactionManager(data);
      var write = new TransactionTemplate(transactions);
      if (populated) populate(dsl, write);
      var beforeRows = rowImages(dsl);
      if (populated) {
        assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_reservations"))).isOne();
        assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_attempt_history"))).isOne();
        assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_recovery_evidence"))).isOne();
      }
      var beforeGuards = guardImages(dsl);
      var beforeFlyway =
          dsl.fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
      String originalMigration =
          dsl.fetchSingle("SELECT checksum::text FROM flyway_schema_history WHERE version = '1040'")
              .get(0, String.class);

      configure(data, schema).load().migrate();

      assertThat(rowImages(dsl)).isEqualTo(beforeRows);
      assertThat(guardImages(dsl)).isEqualTo(beforeGuards);
      assertThat(
              dsl.fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank")
                  .intoMaps()
                  .subList(0, beforeFlyway.size()))
          .isEqualTo(beforeFlyway);
      var conservation =
          dsl.fetch(
                  "SELECT to_jsonb(c)::text AS row, xmin::text AS xmin "
                      + "FROM account_tenant_capacity_conservation c ORDER BY tenant_uuid, owner_service")
              .intoMaps();
      assertThat(conservation).hasSize(populated ? 2 : 0);
      assertThat(
              dsl.fetchSingle(
                      "SELECT checksum::text FROM flyway_schema_history WHERE version = '1040'")
                  .get(0, String.class))
          .isEqualTo(originalMigration);
      assertThat(configure(data, schema).load().validateWithResult().validationSuccessful).isTrue();
      assertThat(
              dsl.fetchSingle(
                      "SELECT pg_get_indexdef('account_capacity_evaluation_oldest_retention_idx'::regclass)")
                  .get(0, String.class))
          .contains("evaluated_at, evaluation_identity");
      assertThat(
              dsl.fetchSingle(
                      "SELECT pg_get_indexdef('account_capacity_reservation_retention_scope_idx'::regclass)")
                  .get(0, String.class))
          .contains("tenant_uuid, owner_service");
      var owner = new AccountCapacityEvaluationRetentionInspectionOwner(dsl, transactions);
      var observed = owner.observe(2);
      assertThat(observed.totalRelationBytes()).isPositive();
      assertThat(observed.sampledRows()).isEqualTo(populated ? 2 : 0);
      assertThat(observed.sampleTruncated()).isEqualTo(populated);
      assertThat(observed.governanceBlocker())
          .isEqualTo(Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE);
      if (populated) {
        // The oldest evaluation has no current FK, but its reservation scope/history is retained.
        assertThat(observed.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows()).isEqualTo(1);
        assertThat(observed.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).evidenceBytes())
            .isEqualTo(262144);
        assertThat(observed.blockers().get(Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE).rows())
            .isEqualTo(1);
        assertThat(observed.blockers().get(Blocker.LIVE_EVALUATION).rows()).isZero();
        var all = owner.observe(1000);
        assertThat(all.sampledRows()).isEqualTo(3);
        assertThat(all.sampleTruncated()).isFalse();
        assertThat(all.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows()).isEqualTo(2);
        for (String sql :
            List.of(
                "UPDATE account_tenant_capacity_evaluations SET canonical_evidence = decode('01', 'hex')",
                "DELETE FROM account_tenant_capacity_evaluations WHERE evaluation_identity = '"
                    + digest('c')
                    + "'",
                "TRUNCATE account_tenant_capacity_evaluations CASCADE",
                "DELETE FROM account_tenant_capacity_reservations",
                "UPDATE account_tenant_capacity_attempt_history SET canonical_payload = decode('01', 'hex')",
                "DELETE FROM account_tenant_capacity_attempt_history",
                "TRUNCATE account_tenant_capacity_attempt_history",
                "UPDATE account_tenant_capacity_recovery_evidence SET outcome = 'INDETERMINATE'",
                "DELETE FROM account_tenant_capacity_recovery_evidence",
                "TRUNCATE account_tenant_capacity_recovery_evidence")) {
          assertThatThrownBy(() -> write.executeWithoutResult(status -> dsl.execute(sql)))
              .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));
        }
      }
      assertThat(rowImages(dsl)).isEqualTo(beforeRows);
      assertThat(guardImages(dsl)).isEqualTo(beforeGuards);
      assertThat(
              dsl.fetch(
                      "SELECT to_jsonb(c)::text AS row, xmin::text AS xmin "
                          + "FROM account_tenant_capacity_conservation c ORDER BY tenant_uuid, owner_service")
                  .intoMaps())
          .isEqualTo(conservation);
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private static org.flywaydb.core.api.configuration.FluentConfiguration configure(
      javax.sql.DataSource data, String schema) {
    return Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration");
  }

  private static List<String> rowImages(DSLContext dsl) {
    return dsl.fetch(
            "SELECT family || ':' || image AS image FROM ("
                + "SELECT 'evaluation' AS family, (to_jsonb(e) || jsonb_build_object('xmin', e.xmin::text))::text AS image "
                + "FROM account_tenant_capacity_evaluations e UNION ALL "
                + "SELECT 'history', (to_jsonb(h) || jsonb_build_object('xmin', h.xmin::text))::text "
                + "FROM account_tenant_capacity_attempt_history h UNION ALL "
                + "SELECT 'recovery', (to_jsonb(r) || jsonb_build_object('xmin', r.xmin::text))::text "
                + "FROM account_tenant_capacity_recovery_evidence r UNION ALL "
                + "SELECT 'reservation', (to_jsonb(r) || jsonb_build_object('xmin', r.xmin::text))::text "
                + "FROM account_tenant_capacity_reservations r UNION ALL "
                + "SELECT 'usage', (to_jsonb(u) || jsonb_build_object('xmin', u.xmin::text))::text "
                + "FROM account_tenant_capacity_usage u) retained ORDER BY family, image")
        .getValues("image", String.class);
  }

  private static List<String> guardImages(DSLContext dsl) {
    return dsl.fetch(
            "SELECT pg_get_triggerdef(oid) AS definition FROM pg_trigger WHERE NOT tgisinternal "
                + "AND tgrelid IN ('account_tenant_capacity_evaluations'::regclass, "
                + "'account_tenant_capacity_attempt_history'::regclass, "
                + "'account_tenant_capacity_recovery_evidence'::regclass) ORDER BY tgrelid, tgname")
        .getValues("definition", String.class);
  }

  private static void populate(DSLContext dsl, TransactionTemplate write) {
    var tenant = source();
    UUID bootstrap = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    UUID admission = UUID.randomUUID();
    write.executeWithoutResult(
        status -> {
          UUID account =
              dsl.fetchSingle(
                      "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, 'test-password') RETURNING account_uuid",
                      "retention-" + UUID.randomUUID(),
                      "retention-" + UUID.randomUUID() + "@example.test")
                  .get(0, UUID.class);
          new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(tenant);
          dsl.execute(
              "INSERT INTO account_tenant_creation_bootstrap_operations "
                  + "(request_id, schema_version, initiating_account_uuid, tenant_uuid, creation_request_id, "
                  + "creation_operation_id, account_authorization_operation_id, account_authorization_digest, "
                  + "creator_evidence_digest, creator_evidence_payload, source_snapshot_payload, source_snapshot_digest, "
                  + "request_payload, request_digest, baseline_membership_version, baseline_membership_authority_generation, "
                  + "baseline_event_sequence, status) "
                  + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 0, 'IN_PROGRESS')",
              bootstrap,
              account,
              tenant.canonicalTenantId(),
              tenant.creationRequestId(),
              tenant.operationId(),
              bootstrap,
              digest('d'),
              tenant.evidenceDigest(),
              new byte[] {1},
              new byte[] {2},
              digest('e'),
              new byte[] {3},
              digest('f'));
          dsl.execute(
              "UPDATE account_tenant_creation_bootstrap_operations SET membership_id = 1, membership_version = 2, "
                  + "membership_authority_generation = 1, membership_lifecycle_state = 'ACTIVE', gameplay_admission_allowed = FALSE, "
                  + "membership_roles_payload = convert_to('[\"tenantAdmin\"]', 'UTF8'), event_stream_key = ?, "
                  + "event_request_id = ?, event_sequence = 1, event_id = ?, event_digest = ?, caller_bound_authority_invalidated = FALSE, "
                  + "event_payload = ?, audit_event_id = ?, audit_event_type = 'ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED', "
                  + "audit_occurred_at = CURRENT_TIMESTAMP, audit_payload_digest = ?, audit_payload = ?, result_payload = ?, "
                  + "result_digest = ?, status = 'COMMITTED' WHERE request_id = ?",
              "account:auth-authority:v1:membership/" + account + "/" + tenant.canonicalTenantId(),
              bootstrap.toString(),
              "retention-bootstrap-" + bootstrap,
              digest('1'),
              new byte[] {4},
              UUID.randomUUID(),
              digest('2'),
              new byte[] {5},
              new byte[] {6},
              digest('3'),
              bootstrap);
          for (var evaluation :
              List.of(
                  new Object[] {digest('a'), "game-session-service", 3, -2, new byte[262144]},
                  new Object[] {digest('c'), "world-management-service", 2, -1, new byte[] {1}},
                  new Object[] {digest('b'), "game-session-service", 1, 1, new byte[] {2}})) {
            dsl.execute(
                "INSERT INTO account_tenant_capacity_evaluations (evaluation_identity, snapshot_identity, tenant_uuid, "
                    + "caller_workload, owner_service, canonical_evidence, evaluated_at, expires_at) "
                    + "VALUES (?, ?, ?, 'test-workload', ?, ?, CURRENT_TIMESTAMP - ? * INTERVAL '1 day', "
                    + "CURRENT_TIMESTAMP + ? * INTERVAL '1 day')",
                evaluation[0],
                digest('d'),
                tenant.canonicalTenantId(),
                evaluation[1],
                evaluation[4],
                evaluation[2],
                evaluation[3]);
          }
          dsl.execute(
              "INSERT INTO account_tenant_capacity_reservations (tenant_uuid, request_id, admission_id, reservation_id, "
                  + "owner_service, caller_workload, admission_attempt_id, capacity_rearm_fence, reservation_fence, mutation_digest, "
                  + "canonical_payload, evaluation_identity, capacity_delta, expires_at, state, execution_status, reconcile_deadline) "
                  + "VALUES (?, ?, ?, ?, 'game-session-service', 'test-workload', ?, 1, 1, ?, ?, ?, 0, "
                  + "CURRENT_TIMESTAMP + INTERVAL '1 day', 'RESERVED', 'OWNER_EXECUTION_PENDING', CURRENT_TIMESTAMP + INTERVAL '2 days')",
              tenant.canonicalTenantId(),
              request,
              admission,
              UUID.randomUUID(),
              UUID.randomUUID(),
              "e".repeat(64),
              new byte[] {7},
              digest('b'));
          dsl.execute(
              "INSERT INTO account_tenant_capacity_attempt_history (tenant_uuid, request_id, admission_id, admission_attempt_id, "
                  + "capacity_rearm_fence, mutation_digest, canonical_payload, owner_readback) VALUES (?, ?, ?, ?, 1, ?, convert_to(?, 'UTF8'), ?)",
              tenant.canonicalTenantId(),
              request,
              admission,
              UUID.randomUUID(),
              "f".repeat(64),
              digest('a'),
              new byte[] {8});
          // Controlled SQL lineage for inspection, not a genuine execution-source observation.
          // Establish the ordinary fenced claim before retaining its unavailable-source evidence.
          dsl.execute(
              "UPDATE account_tenant_capacity_reservations SET recovery_owner = ?, "
                  + "recovery_claim_fence = 1, recovery_claim_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day' "
                  + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
              UUID.randomUUID(),
              tenant.canonicalTenantId(),
              request,
              admission);
          dsl.execute(
              "INSERT INTO account_tenant_capacity_recovery_evidence "
                  + "(tenant_uuid, request_id, admission_id, admission_attempt_id, recovery_claim_fence, "
                  + "recovery_policy_version, recovery_attempt_number, observed_at, outcome) "
                  + "SELECT tenant_uuid, request_id, admission_id, admission_attempt_id, recovery_claim_fence, "
                  + "recovery_policy_version, 1, CURRENT_TIMESTAMP, 'SOURCE_UNAVAILABLE' "
                  + "FROM account_tenant_capacity_reservations "
                  + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
              tenant.canonicalTenantId(),
              request,
              admission);
          dsl.execute(
              "UPDATE account_tenant_capacity_reservations SET recovery_attempt_count = 1 "
                  + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
              tenant.canonicalTenantId(),
              request,
              admission);
        });
  }

  private static FreshTenantCreationEvidence source() {
    UUID request = UUID.randomUUID(), operation = UUID.randomUUID(), tenant = UUID.randomUUID();
    String slug = "retention-" + UUID.randomUUID().toString().substring(0, 8);
    String digest =
        GameTenantCreationDigest.requestDigest("prod", request, slug, "Retention", null);
    long row = Math.abs((long) tenant.hashCode()) + 1;
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        request,
        operation,
        digest,
        tenant,
        row,
        slug,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "prod", request, operation, digest, tenant, row, slug, "NEW_GAME_ROW"));
  }

  private static String digest(char digit) {
    return "sha256:" + String.valueOf(digit).repeat(64);
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql) return sql.getSQLState();
    }
    return null;
  }
}
