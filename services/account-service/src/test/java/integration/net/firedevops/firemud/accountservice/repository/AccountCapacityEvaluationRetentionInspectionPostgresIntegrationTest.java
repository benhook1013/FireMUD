package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountCapacityOriginDecisionRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBinding;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCapacityEvaluationRetentionInspectionOwner.Blocker;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
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

  @Test
  void policyQualifiedInspectionFindsRearmedOldEvaluationAndLeavesUnrelatedRowCandidate()
      throws InterruptedException {
    String schema = "capacity_retention_history_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      configure(data, schema).load().migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transactions = new DataSourceTransactionManager(data);
      var write = new TransactionTemplate(transactions);
      FreshTenantCreationEvidence tenant = bootstrapFreshTenant(dsl, write);

      Instant priorAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
      var prior =
          playerRequest(
              tenant.canonicalTenantId(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              priorAt,
              priorAt.plusSeconds(10));
      var unrelated =
          playerRequest(
              tenant.canonicalTenantId(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              priorAt.minus(Duration.ofDays(10)),
              priorAt.minus(Duration.ofDays(10)).plusSeconds(90));
      UUID reservationId = UUID.randomUUID();
      UUID recoveryOwner = UUID.randomUUID();
      String readbackDigest = "b".repeat(64);
      write.executeWithoutResult(
          status -> {
            insertEvaluation(dsl, prior, priorAt, priorAt.plusSeconds(10));
            insertEvaluation(
                dsl,
                unrelated,
                unrelated.evidence().evaluatedAt(),
                unrelated.evidence().expiresAt());
            new AccountCapacityOriginDecisionRepository(dsl).accept(prior);
            dsl.execute(
                "INSERT INTO account_tenant_capacity_reservations "
                    + "(tenant_uuid, request_id, admission_id, reservation_id, owner_service, caller_workload, "
                    + "admission_attempt_id, capacity_rearm_fence, reservation_fence, mutation_digest, "
                    + "canonical_payload, evaluation_identity, capacity_delta, expires_at, state, execution_status, "
                    + "reconcile_deadline) VALUES (?, ?, ?, ?, 'game-session-service', ?, ?, 1, 1, ?, ?, ?, 1, "
                    + "?::timestamptz, 'RESERVED', 'OWNER_EXECUTION_PENDING', ?::timestamptz)",
                prior.identity().tenantId(),
                prior.identity().requestId(),
                prior.identity().admissionId(),
                reservationId,
                prior.evidence().callerWorkload(),
                prior.admissionAttemptId(),
                prior.mutationDigest(),
                CapacityAdmissionDigest.preimage(prior),
                prior.evidence().evaluationIdentity(),
                prior.evidence().expiresAt().toString(),
                prior.evidence().expiresAt().plusSeconds(60).toString());
            dsl.execute(
                "UPDATE account_tenant_capacity_usage SET held_units = 1, usage_revision = 2 "
                    + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
                tenant.canonicalTenantId());
          });

      waitUntil(prior.evidence().expiresAt().plusMillis(100));
      Instant nextAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
      var nextSeed =
          playerRequest(
              tenant.canonicalTenantId(),
              prior.identity().requestId(),
              prior.identity().admissionId(),
              UUID.randomUUID(),
              nextAt,
              nextAt.plusSeconds(60));
      var next =
          new TenantCapacityAdmission.Request(
                  prior.identity(),
                  nextSeed.admissionAttemptId(),
                  2,
                  prior.targetNamespace(),
                  prior.context(),
                  prior.operation(),
                  prior.priorShape(),
                  prior.requestedShape(),
                  nextSeed.evidence(),
                  prior.capacityDelta(),
                  null)
              .withDigest();
      byte[] ownerReadback =
          "controlled-terminal-no-commit".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      write.executeWithoutResult(
          status -> {
            insertEvaluation(dsl, next, next.evidence().evaluatedAt(), next.evidence().expiresAt());
            dsl.execute(
                "UPDATE account_tenant_capacity_reservations SET recovery_owner = ?, "
                    + "recovery_claim_fence = 1, recovery_claim_expires_at = clock_timestamp() + INTERVAL '1 day', "
                    + "execution_status = 'NOT_EXECUTED_AFTER_AUTHORIZATION', owner_readback = ?, "
                    + "owner_readback_digest = ? WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                recoveryOwner,
                ownerReadback,
                readbackDigest,
                prior.identity().tenantId(),
                prior.identity().requestId(),
                prior.identity().admissionId());
          });
      var capacityOwner = capacityOwner(dsl, transactions, next);
      var rearmed = capacityOwner.rearm(prior, next, recoveryOwner, 1L);
      assertThat(rearmed.evaluationIdentity()).isEqualTo(next.evidence().evaluationIdentity());

      List<String> beforeObservation = rowImages(dsl);
      var owner = new AccountCapacityEvaluationRetentionInspectionOwner(dsl, transactions);
      Instant policyObservedAt = Instant.now().minusSeconds(1);
      var schedule =
          new AccountCapacityEvaluationRetentionPolicy.Schedule(
              "physical-fixture/v1",
              Duration.ofDays(30),
              Duration.ofDays(2),
              Duration.ofDays(3),
              Duration.ofDays(4),
              Duration.ofDays(5),
              Duration.ofDays(6));
      var safety =
          new AccountCapacityEvaluationRetentionPolicy.Safety(
              "physical-fixture/v1",
              policyObservedAt,
              policyObservedAt.plus(Duration.ofHours(1)),
              policyObservedAt.minus(Duration.ofDays(1)),
              Map.of(
                  prior.evidence().evaluationIdentity(),
                      AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR,
                  next.evidence().evaluationIdentity(),
                      AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR,
                  unrelated.evidence().evaluationIdentity(),
                      AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR));

      var observation = owner.observe(10, schedule, safety);

      assertThat(observation.sampledRows()).isEqualTo(3);
      assertThat(observation.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows())
          .isEqualTo(2);
      assertThat(observation.candidates().rows()).isEqualTo(1);
      assertThat(rowImages(dsl)).isEqualTo(beforeObservation);
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @Test
  void populatedMixedCohortKeepsExactImagesAcrossBoundedPolicyAndUnqualifiedReads() {
    String schema = "capacity_retention_mixed_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      configure(data, schema).target("1043").load().migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transactions = new DataSourceTransactionManager(data);
      var write = new TransactionTemplate(transactions);
      populate(dsl, write);
      UUID tenant =
          dsl.fetchSingle("SELECT tenant_uuid FROM account_tenant_capacity_reservations")
              .get(0, UUID.class);
      Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
      // Controlled historical storage, not production hold authority or cleanup eligibility.
      // The existing game-session history/direct references share this tenant but not the
      // world-management owner scope of these unrelated evaluations.
      write.executeWithoutResult(
          status -> {
            for (var row :
                List.of(
                    new Object[] {
                      digest('4'), now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(9)), 4
                    },
                    new Object[] {
                      digest('5'), now.minus(Duration.ofDays(9)), now.minus(Duration.ofDays(8)), 5
                    },
                    new Object[] {
                      digest('6'), now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(7)), 6
                    },
                    new Object[] {
                      digest('7'),
                      now.minus(Duration.ofHours(12)),
                      now.minus(Duration.ofHours(6)),
                      7
                    },
                    new Object[] {
                      digest('8'), now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), 8
                    })) {
              dsl.execute(
                  "INSERT INTO account_tenant_capacity_evaluations "
                      + "(evaluation_identity, snapshot_identity, tenant_uuid, caller_workload, owner_service, "
                      + "canonical_evidence, evaluated_at, expires_at) "
                      + "VALUES (?, ?, ?, 'test-workload', 'world-management-service', ?, ?::timestamptz, ?::timestamptz)",
                  row[0],
                  digest('d'),
                  tenant,
                  new byte[(int) row[3]],
                  row[1].toString(),
                  row[2].toString());
            }
          });
      var beforeMigration = rowImages(dsl);
      var guards = guardImages(dsl);
      configure(data, schema).load().migrate();
      assertThat(rowImages(dsl)).isEqualTo(beforeMigration);
      assertThat(guardImages(dsl)).isEqualTo(guards);
      var conservation =
          dsl.fetch(
                  "SELECT to_jsonb(c)::text AS row, xmin::text AS xmin "
                      + "FROM account_tenant_capacity_conservation c ORDER BY tenant_uuid, owner_service")
              .intoMaps();
      var flyway =
          dsl.fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
      var owner = new AccountCapacityEvaluationRetentionInspectionOwner(dsl, transactions);
      // Same controlled schedule as the existing physical qualification, not a new policy choice.
      var schedule =
          new AccountCapacityEvaluationRetentionPolicy.Schedule(
              "physical-fixture/v1",
              Duration.ofDays(30),
              Duration.ofDays(2),
              Duration.ofDays(3),
              Duration.ofDays(4),
              Duration.ofDays(5),
              Duration.ofDays(6));
      var safety =
          new AccountCapacityEvaluationRetentionPolicy.Safety(
              "physical-fixture/v1",
              now.minusSeconds(1),
              now.plus(Duration.ofHours(1)),
              now.minus(Duration.ofDays(1)),
              Map.of(
                  digest('a'), AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR,
                  digest('b'), AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR,
                  digest('c'), AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR,
                  digest('4'), AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR,
                  digest('5'), AccountCapacityEvaluationRetentionPolicy.Hold.HELD,
                  digest('7'), AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR,
                  digest('8'), AccountCapacityEvaluationRetentionPolicy.Hold.CLEAR));
      for (int repeat = 0; repeat < 2; repeat++) {
        var bounded = owner.observe(3, schedule, safety);
        assertThat(bounded.sampledRows()).isEqualTo(3);
        assertThat(bounded.sampleTruncated()).isTrue();
        assertThat(bounded.candidates().rows()).isOne();
        assertThat(bounded.candidates().evidenceBytes()).isEqualTo(4);
        assertThat(bounded.blockers().get(Blocker.HELD).rows()).isOne();
        assertThat(bounded.blockers().get(Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE).rows())
            .isOne();
        var all = owner.observe(10, schedule, safety);
        assertThat(all.sampledRows()).isEqualTo(8);
        assertThat(all.sampleTruncated()).isFalse();
        assertThat(all.governanceBlocker()).isNull();
        assertThat(all.candidates().rows()).isOne();
        assertThat(all.candidates().evidenceBytes()).isEqualTo(4);
        assertThat(all.candidates().oldestEvaluatedAt()).contains(now.minus(Duration.ofDays(10)));
        assertThat(all.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows()).isEqualTo(2);
        assertThat(all.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).evidenceBytes())
            .isEqualTo(262145);
        assertThat(all.blockers().get(Blocker.HELD).rows()).isOne();
        assertThat(all.blockers().get(Blocker.HELD).evidenceBytes()).isEqualTo(5);
        assertThat(all.blockers().get(Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE).rows())
            .isOne();
        assertThat(all.blockers().get(Blocker.ABOVE_SAFE_WATERMARK).rows()).isOne();
        assertThat(all.blockers().get(Blocker.RETENTION_WINDOW_OPEN).rows()).isOne();
        assertThat(all.blockers().get(Blocker.LIVE_EVALUATION).rows()).isOne();
        var unqualified = owner.observe(10);
        assertThat(unqualified.candidates().rows()).isZero();
        assertThat(unqualified.blockers().get(Blocker.RETAINED_RESERVATION_SCOPE).rows())
            .isEqualTo(2);
        assertThat(unqualified.blockers().get(Blocker.LIVE_EVALUATION).rows()).isOne();
        assertThat(unqualified.blockers().get(Blocker.POLICY_AND_HOLD_AUTHORITY_UNAVAILABLE).rows())
            .isEqualTo(5);
        assertThat(rowImages(dsl)).isEqualTo(beforeMigration);
        assertThat(guardImages(dsl)).isEqualTo(guards);
        assertThat(
                dsl.fetch(
                        "SELECT to_jsonb(c)::text AS row, xmin::text AS xmin "
                            + "FROM account_tenant_capacity_conservation c ORDER BY tenant_uuid, owner_service")
                    .intoMaps())
            .isEqualTo(conservation);
        assertThat(
                dsl.fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps())
            .isEqualTo(flyway);
      }
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
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
          UUID attempt = UUID.randomUUID();
          String mutationDigest = "e".repeat(64);
          byte[] capacityPayload = {7};
          dsl.execute(
              "INSERT INTO account_capacity_origin_decisions "
                  + "(tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, "
                  + "mutation_digest, canonical_payload, decision, decision_id) "
                  + "VALUES (?, ?, ?, 1, ?, ?, ?, 'ACCEPTED', ?)",
              tenant.canonicalTenantId(),
              request,
              admission,
              attempt,
              mutationDigest,
              capacityPayload,
              UUID.randomUUID());
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
              attempt,
              mutationDigest,
              capacityPayload,
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

  private static FreshTenantCreationEvidence bootstrapFreshTenant(
      DSLContext dsl, TransactionTemplate write) {
    FreshTenantCreationEvidence tenant = source();
    UUID bootstrap = UUID.randomUUID();
    write.executeWithoutResult(
        status -> {
          UUID account =
              dsl.fetchSingle(
                      "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, 'test-password') RETURNING account_uuid",
                      "lineage-" + UUID.randomUUID(),
                      "lineage-" + UUID.randomUUID() + "@example.test")
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
                  + "event_request_id = ?, event_sequence = 1, event_id = ?, event_digest = ?, event_payload = ?, "
                  + "audit_event_id = ?, audit_event_type = 'ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED', "
                  + "audit_occurred_at = CURRENT_TIMESTAMP, audit_payload_digest = ?, audit_payload = ?, result_payload = ?, "
                  + "result_digest = ?, status = 'COMMITTED' WHERE request_id = ?",
              "account:auth-authority:v1:membership/" + account + "/" + tenant.canonicalTenantId(),
              bootstrap.toString(),
              "lineage-bootstrap-" + bootstrap,
              digest('1'),
              new byte[] {4},
              UUID.randomUUID(),
              digest('2'),
              new byte[] {5},
              new byte[] {6},
              digest('3'),
              bootstrap);
        });
    return tenant;
  }

  @SuppressWarnings("unchecked")
  private static AccountTenantCapacityOwner capacityOwner(
      DSLContext dsl,
      DataSourceTransactionManager transactions,
      TenantCapacityAdmission.Request request) {
    var player = (TenantCapacityAdmission.PlayerContext) request.context();
    var parsed =
        RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(
            request.evidence().canonicalBytes());
    AccountRepository accounts = mock(AccountRepository.class);
    when(accounts.findByAccountUuidForUpdate(player.accountId()))
        .thenReturn(Optional.of(new Account()));
    AccountCanonicalRuntimeMembershipSnapshotRepository memberships =
        mock(AccountCanonicalRuntimeMembershipSnapshotRepository.class);
    when(memberships.readCurrent(player.accountId(), player.tenantId()))
        .thenReturn(parsed.membershipSource());

    Map<String, Object> evidenceFields = request.evidence().fields();
    Map<String, Object> sourceReferences =
        (Map<String, Object>) evidenceFields.get("sourceReferences");
    Map<String, Object> target = (Map<String, Object>) evidenceFields.get("target");
    UUID tokenJti = UUID.fromString((String) sourceReferences.get("callerTokenJti"));
    UUID callerContextId = UUID.fromString((String) sourceReferences.get("callerContextId"));
    UUID operationId = UUID.fromString((String) sourceReferences.get("callerOperationId"));
    long now = Instant.now().getEpochSecond();
    var issuanceIdentity =
        new AccountGameplayDelegationPendingIdentity(
            operationId,
            request.identity().requestId(),
            player.accountId(),
            request.evidence().callerWorkload(),
            callerContextId,
            new AccountGameplayCredentialRequestBinding(1, "fixture", "f".repeat(64)),
            "e".repeat(64),
            tokenJti,
            now - 10,
            now - 10,
            now + 3600);
    var issuance =
        mock(AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData.class);
    when(issuance.identity()).thenReturn(issuanceIdentity);
    when(issuance.tokenSha256()).thenReturn((String) sourceReferences.get("callerTokenSha256"));
    when(issuance.canonicalRegistryRecordSha256())
        .thenReturn((String) sourceReferences.get("callerRegistryRecordSha256"));
    when(issuance.commitProofSha256())
        .thenReturn((String) sourceReferences.get("callerCommitProofSha256"));
    AccountGameplayDelegationIssuanceRepository issuances =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    when(issuances.readCurrentCommittedCandidateByJti(tokenJti)).thenReturn(Optional.of(issuance));

    var scope = mock(AccountConnectScopeRepository.CanonicalConnectScopeEvidence.class);
    when(scope.accountUuid()).thenReturn(player.accountId());
    when(scope.tenantUuid()).thenReturn(player.tenantId());
    when(scope.scopeDigest()).thenReturn((String) sourceReferences.get("scopeDigest"));
    when(scope.gameInstanceUuid()).thenReturn(player.gameInstanceId());
    when(scope.playableStateNamespaceUuid()).thenReturn(player.playableStateNamespaceId());
    when(scope.realmId()).thenReturn(player.realmId());
    when(scope.playableStateScope()).thenReturn(player.playableStateScope());
    when(scope.connectScopeExpiresAt()).thenReturn((String) target.get("connectScopeExpiresAt"));
    AccountConnectScopeRepository scopes = mock(AccountConnectScopeRepository.class);
    when(scopes.findCanonicalEvidenceByTokenHash(parsed.verifiedConnectScopeTokenHash()))
        .thenReturn(Optional.of(scope));

    return new AccountTenantCapacityOwner(
        dsl, accounts, memberships, issuances, scopes, transactions, Clock.systemUTC(), "prod");
  }

  @SuppressWarnings("unchecked")
  private static TenantCapacityAdmission.Request playerRequest(
      UUID tenantId,
      UUID requestId,
      UUID admissionId,
      UUID attemptId,
      Instant at,
      Instant scopeExpiry) {
    var template = CapacityAdmissionTestFixture.playerRequest();
    var original = (TenantCapacityAdmission.PlayerContext) template.context();
    var player =
        new TenantCapacityAdmission.PlayerContext(
            original.accountId(),
            tenantId,
            original.playableStateNamespaceId(),
            original.gameInstanceId(),
            original.characterId(),
            original.sessionId(),
            original.realmId(),
            requestId,
            original.playableStateScope());
    var target =
        new LinkedHashMap<String, Object>(
            (Map<String, Object>) template.evidence().fields().get("target"));
    target.put("tenantId", tenantId.toString());
    target.put("evaluatedAt", at.toString());
    target.put("connectScopeExpiresAt", scopeExpiry.toString());
    return CapacityAdmissionTestFixture.playerRequestFor(
        player, admissionId, attemptId, "prod", target, at);
  }

  private static void insertEvaluation(
      DSLContext dsl,
      TenantCapacityAdmission.Request request,
      Instant evaluatedAt,
      Instant expiresAt) {
    dsl.execute(
        "INSERT INTO account_tenant_capacity_evaluations "
            + "(evaluation_identity, snapshot_identity, tenant_uuid, caller_workload, owner_service, "
            + "canonical_evidence, evaluated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
        request.evidence().evaluationIdentity(),
        request.evidence().snapshotIdentity(),
        request.identity().tenantId(),
        request.evidence().callerWorkload(),
        request.context().owner().service(),
        request.evidence().canonicalBytes(),
        evaluatedAt.toString(),
        expiresAt.toString());
  }

  private static void waitUntil(Instant deadline) throws InterruptedException {
    long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
    if (remainingMillis > 0) Thread.sleep(remainingMillis);
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
